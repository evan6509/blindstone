#!/usr/bin/env python3
"""Bounded, offline, loopback-only 26.3 admission test against the installed production JAR.

Run `./gradlew prepareAdmission` first. No client mod, shared in-process registries, development
flag, proxy, or existing server is used. This protocol client does not render or download packs.
"""
import argparse
from collections import Counter
import hashlib
import io
import json
import math
from pathlib import Path
import shutil
import socket
import struct
import subprocess
import threading
import time
import traceback
import uuid
import zlib

ROOT = Path(__file__).resolve().parents[1]
MAX_FRAME = 8 * 1024 * 1024


def varint(value):
    value &= 0xffffffff
    out = bytearray()
    while value > 127:
        out.append((value & 127) | 128)
        value >>= 7
    return bytes(out) + bytes([value])


def string(value):
    data = value.encode()
    return varint(len(data)) + data


class Reader:
    def __init__(self, data):
        self.stream = io.BytesIO(data)

    def read(self, count):
        assert 0 <= count <= MAX_FRAME, f"Invalid field size: {count}"
        data = self.stream.read(count)
        assert len(data) == count, "Truncated packet"
        return data

    def number(self, fmt):
        return struct.unpack(">" + fmt, self.read(struct.calcsize(fmt)))[0]

    def vint(self):
        value = 0
        for shift in range(0, 35, 7):
            part = self.number("B")
            value |= (part & 127) << shift
            if not part & 128:
                return value
        raise AssertionError("Overlong VarInt")

    def text(self):
        return self.read(self.vint()).decode()

    def nbt(self, kind=None, depth=0):
        assert depth < 64, "NBT nesting exceeded"
        if kind is None:
            kind = self.number("B")
        formats = {1: "b", 2: "h", 3: "i", 4: "q", 5: "f", 6: "d"}
        if kind == 0:
            return None
        if kind in formats:
            return self.number(formats[kind])
        if kind == 7:
            return self.read(self.number("i"))
        if kind == 8:
            return self.read(self.number("H")).decode()
        if kind == 9:
            item, count = self.number("B"), self.number("i")
            assert 0 <= count <= 100000
            return [self.nbt(item, depth + 1) for _ in range(count)]
        if kind == 10:
            result = {}
            while (item := self.number("B")) != 0:
                name = self.read(self.number("H")).decode()
                result[name] = self.nbt(item, depth + 1)
            return result
        if kind in (11, 12):
            count = self.number("i")
            assert 0 <= count <= 100000
            return [self.number("i" if kind == 11 else "q") for _ in range(count)]
        raise AssertionError(f"Unknown NBT tag {kind}")

    def end(self):
        assert not self.stream.read(1), "Unconsumed audited packet fields"


def palette(reader, entries, local_max):
    bits = reader.number("B")
    assert bits <= 32
    if bits == 0:
        return [reader.vint()] * entries
    values = [reader.vint() for _ in range(reader.vint())] if bits <= local_max else None
    per_long = 64 // bits
    words = [reader.number("Q") for _ in range(math.ceil(entries / per_long))]
    indices = [(words[i // per_long] >> ((i % per_long) * bits)) & ((1 << bits) - 1) for i in range(entries)]
    return [values[i] for i in indices] if values is not None else indices


class Bot:
    def __init__(self, port, name, metadata):
        self.name, self.meta = name, metadata
        self.phase = "login"
        self.counts = Counter()
        self.registries = {}
        self.samples = {}
        self.effects = []
        self.payloads = set()
        self.respawns = []
        self.dimension = None
        self.position = (0.0, 0.0, 0.0)
        self.rotation = (0.0, 0.0)
        self.keepalives = 0
        self.chunks = 0
        self.game_time = None
        self.pack_responses = []
        self.server_links = []
        self.batch_chunks = None
        self.bundle_open = False
        self.error = None
        self.closed = False
        self.compression = -1
        self.started = time.monotonic()
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=5)
        self.sock.settimeout(1)
        self.lock = threading.Lock()
        self.send_raw(0, varint(metadata["protocol"]) + string("localhost") + struct.pack(">H", port) + varint(2))
        self.send("hello", string(name) + uuid.uuid4().bytes)
        self.thread = threading.Thread(target=self.run, daemon=True)
        self.thread.start()

    def send_raw(self, packet_id, body=b""):
        data = varint(packet_id) + body
        if self.compression >= 0:
            data = varint(len(data)) + zlib.compress(data) if len(data) >= self.compression else b"\x00" + data
        with self.lock:
            self.sock.sendall(varint(len(data)) + data)

    def send(self, name, body=b""):
        self.send_raw(self.meta[self.phase + "_serverbound"][name], body)

    def receive_exact(self, count):
        data = bytearray()
        while len(data) < count:
            try:
                part = self.sock.recv(count - len(data))
            except socket.timeout:
                if self.closed:
                    raise EOFError("Fixture closed")
                continue
            if not part:
                raise EOFError("Server closed socket")
            data.extend(part)
        return bytes(data)

    def frame(self):
        length = 0
        for shift in range(0, 21, 7):
            part = self.receive_exact(1)[0]
            length |= (part & 127) << shift
            if not part & 128:
                break
        else:
            raise AssertionError("Overlong frame length")
        assert 0 < length <= MAX_FRAME
        data = self.receive_exact(length)
        if self.compression >= 0:
            r = Reader(data)
            expected = r.vint()
            data = r.stream.read()
            if expected:
                assert self.compression <= expected <= MAX_FRAME
                inflater = zlib.decompressobj()
                data = inflater.decompress(data, MAX_FRAME + 1)
                assert inflater.eof and len(data) == expected
        return Reader(data)

    def run(self):
        try:
            while not self.closed:
                r = self.frame()
                packet_id = r.vint()
                names = self.meta[self.phase + "_clientbound"]
                name = next((n for n, i in names.items() if i == packet_id), None)
                assert name is not None, f"Unknown {self.phase} wire ID {packet_id}"
                self.counts[self.phase + ":" + name] += 1
                self.handle(name, r)
        except Exception as error:
            if not self.closed:
                self.error = f"{type(error).__name__}: {error}"
                self.error_traceback = traceback.format_exc()

    def handle(self, name, r):
        if name in ("disconnect", "login_disconnect"):
            reason = r.text() if self.phase == "login" else r.nbt()
            raise AssertionError(f"Disconnect in {self.phase}: {reason}")
        if name == "keep_alive":
            self.send("keep_alive", r.read(8))
            self.keepalives += 1
        elif name == "ping":
            self.send("pong", r.read(4))
        elif name == "cookie_request":
            self.send("cookie_response", string(r.text()) + b"\x00")
        elif name == "custom_payload":
            self.payloads.add(self.phase + ":" + r.text())
        elif name == "server_links":
            for _ in range(r.vint()):
                label = r.vint() if r.number("B") else r.nbt()
                self.server_links.append({"phase": self.phase, "label": label, "url": r.text()})
            r.end()
        elif name == "resource_pack_push":
            pack_id = r.read(16)
            url, digest, required = r.text(), r.text(), r.number("B")
            if r.number("B"):
                r.nbt()  # Optional authored prompt.
            r.end()
            assert not required, "Fixture only declines optional packs; required pack retrieval needs a real client"
            self.send("resource_pack", pack_id + varint(1))  # DECLINED, a real terminal response.
            self.pack_responses.append({"phase": self.phase, "url": url, "hash": digest, "action": "DECLINED"})
        elif self.phase == "login":
            if name == "login_compression":
                self.compression = r.vint()
            elif name == "custom_query":
                self.send("custom_query_answer", varint(r.vint()) + b"\x00")
            elif name == "hello":
                raise AssertionError("Online authentication unexpectedly enabled")
            elif name == "login_finished":
                self.send("login_acknowledged")
                self.phase = "configuration"
                self.send("client_information", string("en_us") + b"\x02\x00\x01\x00\x01\x00\x01\x00")
        elif self.phase == "configuration":
            if name == "select_known_packs":
                # Claim no local packs: require complete network registry entries, not shared server state.
                self.send("select_known_packs", varint(0))
            elif name == "registry_data":
                registry = r.text()
                entries = []
                for _ in range(r.vint()):
                    key = r.text()
                    assert r.number("B") == 1, "Server omitted data for a pack the client does not have"
                    entries.append((key, r.nbt()))
                r.end()
                self.registries[registry] = entries
            elif name == "finish_configuration":
                assert len(self.registries) >= 10 and "minecraft:dimension_type" in self.registries
                self.send("finish_configuration")
                self.phase = "play"
            elif name == "code_of_conduct":
                self.send("accept_code_of_conduct")
        elif self.phase == "play":
            if name == "login":
                r.read(5)
                for _ in range(r.vint()):
                    r.text()
                for _ in range(3):
                    r.vint()
                r.read(3)
                self.spawn_info(r)
                self.send("player_loaded")
            elif name == "respawn":
                self.spawn_info(r)
                self.respawns.append(self.dimension)
                self.send("player_loaded")
            elif name == "player_position":
                teleport = r.vint()
                position = tuple(r.number("d") for _ in range(3))
                r.read(24)  # Delta movement.
                rotation = tuple(r.number("f") for _ in range(2))
                flags = r.number("i")
                self.position = tuple(value + self.position[i] if flags & (1 << i) else value for i, value in enumerate(position))
                self.rotation = tuple(value + self.rotation[i] if flags & (1 << (3 + i)) else value for i, value in enumerate(rotation))
                self.send("accept_teleportation", varint(teleport) + struct.pack(">dddff", *self.position, *self.rotation))
            elif name == "post_effects":
                self.effects.append([r.text() for _ in range(r.vint())])
                r.end()
            elif name == "set_time":
                self.game_time = r.number("q")
            elif name == "chunk_batch_start":
                assert self.batch_chunks is None, "Nested chunk batch"
                self.batch_chunks = 0
            elif name == "level_chunk_with_light":
                self.chunk(r)
                if self.batch_chunks is not None:
                    self.batch_chunks += 1
            elif name == "chunk_batch_finished":
                assert r.vint() == self.batch_chunks, "Chunk batch size mismatch"
                self.batch_chunks = None
                self.send("chunk_batch_received", struct.pack(">f", 4.0))
            elif name == "bundle_delimiter":
                self.bundle_open = not self.bundle_open
            elif name == "start_configuration":
                self.send("configuration_acknowledged")
                self.phase = "configuration"

    def spawn_info(self, r):
        type_id = r.vint()
        self.dimension = r.text()
        _, data = self.registries["minecraft:dimension_type"][type_id]
        self.min_y, self.height = data["min_y"], data["height"]
        self.samples = {}

    def chunk(self, r):
        x, z = r.number("i"), r.number("i")
        for _ in range(r.vint()):
            r.vint()  # Heightmap type.
            r.read(r.vint() * 8)
        sections = Reader(r.read(r.vint()))
        sample = {}
        for y in range(self.min_y, self.min_y + self.height, 16):
            sections.read(4)  # Non-air and fluid counts, separately in 26.3.
            states = palette(sections, 4096, 8)
            palette(sections, 64, 3)
            if x == 0 and z == 0:
                for sample_y in (20, 22):
                    if y <= sample_y < y + 16:
                        sample[sample_y] = states[((sample_y & 15) << 8) | (8 << 4) | 8]
        sections.end()
        for _ in range(r.vint()):
            r.read(3)  # Packed XZ and signed Y.
            r.vint()  # Block entity type.
            r.nbt()
        masks = [r.read(r.vint()) for _ in range(4)]  # ByteBufCodecs.BIT_SET uses byte arrays in 26.3.
        for mask in masks[:2]:
            count = r.vint()
            assert sum(byte.bit_count() for byte in mask) == count, "Light mask/array mismatch"
            for _ in range(count):
                assert r.vint() == 2048, "Invalid light array"
                r.read(2048)
        r.end()
        self.chunks += 1
        if sample:
            self.samples = sample

    def check(self):
        assert self.error is None, f"{self.name}: {self.error}\n{getattr(self, 'error_traceback', '')}"

    def close(self):
        self.closed = True
        self.sock.close()
        self.thread.join(2)

    def report(self):
        return {"name": self.name, "seconds": round(time.monotonic() - self.started, 3),
                "packets": dict(sorted(self.counts.items())), "registry_entries": {k: len(v) for k, v in self.registries.items()},
                "custom_payload_ids": sorted(self.payloads), "chunks": self.chunks, "keepalives": self.keepalives,
                "effects": self.effects, "respawn_dimensions": self.respawns, "pack_responses": self.pack_responses,
                "server_links": self.server_links, "error": self.error}


def until(predicate, bots=(), timeout=40):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for bot in bots:
            bot.check()
        if predicate():
            return
        time.sleep(0.05)
    raise AssertionError("Admission fixture condition timed out")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--observe-seconds", type=int, default=20)
    parser.add_argument("--artifact", type=Path, help="Optional production artifact, e.g. a recorded pre-fix JAR for RED verification")
    parser.add_argument("--development-denial", action="store_true", help="Negative control: verify isolated development mode still denies outside clients")
    args = parser.parse_args()
    assert 16 <= args.observe_seconds <= 120
    meta = json.loads((ROOT / "build/admission-metadata.json").read_text())
    launch = json.loads((ROOT / "build/admission-launch.json").read_text())
    if args.artifact:
        launch["artifact"] = str(args.artifact.resolve(strict=True))
    assert meta["minecraft"] == "26.3"
    run = ROOT / "build/admission" / str(uuid.uuid4())
    (run / "mods").mkdir(parents=True)
    for path in (launch["artifact"], launch["fabricApi"]):
        shutil.copy2(path, run / "mods")
    (run / "eula.txt").write_text("eula=true\n")
    # Reserve an ephemeral loopback address. The short close/bind race is detected by readiness failure.
    with socket.socket() as reserved:
        reserved.bind(("127.0.0.1", 0))
        port = reserved.getsockname()[1]
    flat = {"biome": "minecraft:plains", "layers": [{"block": "minecraft:stone", "height": 144}], "structure_overrides": []}
    (run / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nenforce-secure-profile=false\n"
        "level-type=minecraft:flat\ngamemode=creative\nview-distance=2\nsimulation-distance=2\n"
        "spawn-protection=0\nmax-players=3\nallow-flight=true\nnetwork-compression-threshold=256\nwhite-list=false\n"
        "enable-query=false\nenable-rcon=false\naccepts-transfers=false\n"
        "resource-pack=https://example.invalid/blindstone-fixture.zip\nrequire-resource-pack=false\n"
        "bug-report-link=https://example.invalid/bugs\n"
        "generator-settings=" + json.dumps(flat) + "\n")
    command = [launch["java"], "-Xms256M", "-Xmx2G", "-Dfabric.development=false",
               "-Dfabric.gameJarPath=" + launch["serverJar"], "-cp", launch["classpath"],
               "net.fabricmc.loader.impl.launch.knot.KnotServer", "nogui"]
    if args.development_denial:
        command.insert(3, "-Dblindstone.development=true")
    print(f"Evidence directory: {run}", flush=True)
    lines, bots, checks = [], [], []
    result = {"minecraft": "26.3", "java": "25", "loader": "0.19.5", "fabric_api": "0.163.0+26.3",
              "mode": "installed production JAR; loopback TCP; offline authentication; no proxy; headless",
              "artifact_sha256": hashlib.sha256(Path(launch["artifact"]).read_bytes()).hexdigest(),
              "command": command, "port": port, "observation_seconds": args.observe_seconds, "checks": checks}
    result["development_denial_control"] = args.development_denial
    process = subprocess.Popen(command, cwd=run, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, bufsize=1)

    def read_log():
        with (run / "server.log").open("w") as output:
            for line in process.stdout:
                output.write(line)
                output.flush()
                lines.append(line)

    logger = threading.Thread(target=read_log, daemon=True)
    logger.start()

    def console(text):
        assert process.poll() is None, "Disposable server exited"
        marker = "HARNESS_" + uuid.uuid4().hex
        process.stdin.write(text + "\nsay " + marker + "\n")
        process.stdin.flush()
        until(lambda: any(marker in line for line in lines), bots, timeout=20)

    def concealed(bot):
        return bot.dimension == "minecraft:overworld" and bot.samples == {20: meta["blocks"]["stone"], 22: meta["blocks"]["stone"]}

    try:
        until(lambda: any("Done (" in line for line in lines) or process.poll() is not None, timeout=90)
        assert process.poll() is None, "Server failed to reach readiness; see server.log"
        if args.development_denial:
            bot = Bot(port, "BlindstoneDenied", meta)
            bots.append(bot)
            until(lambda: bot.error is not None, timeout=20)
            assert "Blindstone isolated development tests" in bot.error and bot.chunks == 0, bot.error
            checks.append("isolated development mode rejects ordinary external login before world delivery")
        else:
            console("blindstone status")
            assert any("ENFORCING" in line and "OPEN" in line for line in lines), "Normal protection/admission not active"
            console("setworldspawn 0 80 0")
            console("gamerule minecraft:respawn_radius 0")
            console("gamerule minecraft:spawn_mobs false")
            console("forceload add 0 0")
            for _ in range(100):
                console("execute if loaded 8 79 8 run say HARNESS_CHUNK_READY")
                if any("HARNESS_CHUNK_READY" in line for line in lines):
                    break
            assert any("HARNESS_CHUNK_READY" in line for line in lines), "Fixture chunk did not load"
            console("fill 7 20 7 9 23 9 minecraft:air")
            console("setblock 8 20 8 minecraft:diamond_ore")
            console("execute if block 8 20 8 minecraft:diamond_ore run say HARNESS_ORE_READY")
            assert any("HARNESS_ORE_READY" in line for line in lines), "Source ore fixture was not installed"
            for name in ("BlindstoneOne", "BlindstoneTwo"):
                bot = Bot(port, name, meta)
                bots.append(bot)
                until(lambda: concealed(bot) and [] in bot.effects, bots)
            checks.append("two ordinary handshake/login/configuration/registry/placement joins with empty post effects and concealed ore/air")
            first, second = bots
            console("posteffect add BlindstoneOne minecraft:creeper")
            until(lambda: ["minecraft:creeper"] in first.effects, bots)
            console("posteffect clear BlindstoneOne")
            until(lambda: first.effects[-1] == [], bots)
            checks.append("nonempty and cleared vanilla post-effect command state decoded in PLAY")
            console("tp BlindstoneOne 8.5 21 8.5")
            until(lambda: first.samples == {20: meta["blocks"]["diamond_ore"], 22: meta["blocks"]["air"]}, bots)
            assert concealed(second), "Independent surface viewer learned the underground chamber"
            checks.append("first viewer receives real body cells while second retains stone for the same ore and air")
            console("tp BlindstoneOne 0.5 80 0.5")
            console("spawnpoint BlindstoneOne 0 80 0")
            console("gamemode survival BlindstoneOne")
            before = len(first.respawns)
            console("kill BlindstoneOne")
            first.send("client_command", varint(0))
            until(lambda: len(first.respawns) > before and concealed(first), bots)
            checks.append("death and serverbound respawn request return to concealed Overworld chunks")
            console("gamemode creative BlindstoneOne")
            for dimension in ("the_nether", "overworld"):
                before_chunks = first.chunks
                console(f"execute in minecraft:{dimension} run tp BlindstoneOne 0.5 80 0.5")
                until(lambda: first.dimension == "minecraft:" + dimension and first.chunks > before_chunks, bots)
            until(lambda: concealed(first), bots)
            checks.append("leave protected dimension and return through real respawn/teleport placement")
            before_chunks = (first.chunks, second.chunks)
            console("blindstone reload")
            until(lambda: first.chunks > before_chunks[0] and second.chunks > before_chunks[1], bots)
            until(lambda: concealed(first) and concealed(second), bots)
            checks.append("active-viewer reload retains concealment")
            started = time.monotonic()
            before_game_time = first.game_time
            until(lambda: time.monotonic() - started >= args.observe_seconds, bots, timeout=args.observe_seconds + 5)
            assert all(bot.counts["play:keep_alive"] > 0 for bot in bots), "No normal PLAY keepalive roundtrip observed"
            result["observation_ticks"] = first.game_time - before_game_time
            assert all(not bot.bundle_open for bot in bots), "Unterminated vanilla bundle"
            checks.append(f"both independent viewers remain connected for {args.observe_seconds}s observation and answer normal keepalives")
            assert all(bot.pack_responses and bot.counts["configuration:server_links"] > 0 for bot in bots)
            checks.append("optional configuration pack is explicitly declined and server-information links received; no pack download/render claim")
            first.close()
            console("list")
            until(lambda: any("BlindstoneOne lost connection" in line for line in lines), bots[1:])
            rejoined = Bot(port, "BlindstoneOne", meta)
            bots.append(rejoined)
            until(lambda: concealed(rejoined) and [] in rejoined.effects, bots[1:])
            checks.append("same identity disconnect/rejoin performs a new full admission and conceals the former underground view")
            console("blindstone status")
            console("blindstone report")
        result["status"] = "PASS"
    except Exception as error:
        result["status"] = "FAIL"
        result["failure"] = f"{type(error).__name__}: {error}"
        traceback.print_exc()
    finally:
        result["clients"] = [bot.report() for bot in bots]
        for bot in bots:
            bot.close()
        if process.poll() is None:
            process.stdin.write("stop\n")
            process.stdin.flush()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.terminate()
                process.wait(timeout=10)
        logger.join(3)
        result["server_exit"] = process.returncode
        (run / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps({"status": result["status"], "checks": checks, "result": str(run / "result.json")}, indent=2), flush=True)
    return 0 if result["status"] == "PASS" and result["server_exit"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
