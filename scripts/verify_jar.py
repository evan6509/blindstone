#!/usr/bin/env python3
"""Check the built server artifact, version pins, entrypoints, and required admission hook."""
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def main():
    properties = dict(line.split("=", 1) for line in Path("gradle.properties").read_text().splitlines() if "=" in line)
    version = properties["mod_version"] + "+mc" + properties["minecraft_version"]
    jars = [p for p in Path("build/libs").glob("*.jar") if not p.name.endswith("-sources.jar")]
    require(len(jars) == 1, "Expected exactly one installable JAR; run clean build")
    jar = jars[0]
    require(jar.name == f"blindstone-{version}.jar", "Unexpected JAR name/version")
    with ZipFile(jar) as archive:
        require(archive.testzip() is None, "Invalid ZIP data")
        metadata = json.loads(archive.read("fabric.mod.json"))
        require(metadata["id"] == "blindstone" and metadata["version"] == version, "Bad mod identity")
        require(metadata["environment"] == "server", "Must be a server-only mod")
        require(metadata["depends"]["minecraft"] == "=" + properties["minecraft_version"], "Minecraft must be exactly pinned")
        require(metadata["depends"]["fabricloader"] == "=" + properties["loader_version"], "Loader pin differs")
        require(metadata["depends"]["fabric-api"] == "=" + properties["fabric_version"], "API pin differs")
        require(metadata["mixins"] == ["blindstone.mixins.json"], "Missing admission mixin registration")
        mixins = json.loads(archive.read("blindstone.mixins.json"))
        require(mixins["required"] and mixins["injectors"]["defaultRequire"] == 1, "Admission hook must be mandatory")
        require("PlayerListMixin" in mixins["mixins"], "Missing admission hook")
        for hook in ["ConnectionMixin", "ChunkMapMixin", "TrackedEntityAccess", "TrackedEntityMixin", "LevelChunkMixin", "ServerCommonListenerAccess", "PlayerChunkSenderMixin", "HookReadinessMixin", "CommandSourceAccess"]:
            require(hook in mixins["mixins"], f"Missing mandatory alpha hook: {hook}")
        for hook in mixins["mixins"]:
            bytecode = archive.read(mixins["package"].replace(".", "/") + "/" + hook + ".class")
            require(int.from_bytes(bytecode[6:8], "big") == 69, f"Missing Java 25 mixin: {hook}")
        prefix = "com/evanchubbuck/blindstone/"
        for name in ["BlindstoneMod", "ChunkFilter", "ConcealmentPolicy", "SectionVisibility", "ViewerState", "mixin/PlayerListMixin",
                     "mixin/ConnectionMixin", "mixin/ChunkMapMixin", "mixin/TrackedEntityMixin", "mixin/LevelChunkMixin",
                     "runtime/BlindstoneRuntime", "network/PacketFilter", "visibility/GeometrySnapshot", "visibility/VisibilityEvaluator",
                     "config/ConfigManager", "config/BlindstoneConfig", "diagnostics/BlindstoneDiagnostics", "HookReadiness",
                     "network/FilteredLight", "network/LightScheduler", "network/EntityPolicy", "mixin/CommandSourceAccess",
                     "mixin/HookReadinessMixin", "mixin/PlayerChunkSenderMixin"]:
            bytecode = archive.read(prefix + name + ".class")
            require(int.from_bytes(bytecode[6:8], "big") == 69, f"Expected Java 25 class: {name}")
        require(not any("GameTests" in n or "/admission/" in n or n == "fabric-gametest-api-v1" for n in archive.namelist()), "Test code bundled in mod")
        require(metadata["entrypoints"]["main"] == ["com.evanchubbuck.blindstone.BlindstoneMod"], "Missing server initializer")
    print(f"Verified {jar}\nSHA256 {hashlib.sha256(jar.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
