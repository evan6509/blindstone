#!/usr/bin/env python3
"""Inventory packet class references from the pinned Loom-generated vanilla sources.

This is lexical research support, not proof of interception or a complete call graph.
No vanilla source text is copied into the generated report.
"""
import argparse
import json
import re
from pathlib import Path
from zipfile import ZipFile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sources", type=Path)
    parser.add_argument("--output", type=Path, default=Path("docs/vanilla-packet-inventory.json"))
    args = parser.parse_args()
    if args.sources is None:
        candidates = list(Path(".gradle/loom-cache/minecraftMaven").rglob("*26.3-sources.jar"))
        if len(candidates) != 1:
            parser.error("Run ./gradlew genSources first, or supply --sources for Minecraft 26.3")
        args.sources = candidates[0]
    with ZipFile(args.sources) as archive:
        names = archive.namelist()
        packets = sorted(n for n in names if n.startswith("net/minecraft/network/protocol/")
                         and n.rsplit("/", 1)[-1].startswith("Clientbound") and n.endswith("Packet.java"))
        references = {n.rsplit("/", 1)[-1][:-5]: [] for n in packets}
        pattern = re.compile(r"\b(Clientbound\w+Packet)\b")
        for name in sorted(names):
            if not name.endswith(".java") or not name.startswith(("net/minecraft/server/", "net/minecraft/world/", "net/minecraft/network/")):
                continue
            if name in packets:
                continue
            text = re.sub(r"(?m)^import .*;$", "", archive.read(name).decode())
            for packet in set(pattern.findall(text)):
                if packet in references:
                    references[packet].append(name)
        # A known packet and the primary vanilla chunk sender guard against wrong/empty input.
        if "net/minecraft/server/network/PlayerChunkSender.java" not in references.get("ClientboundLevelChunkWithLightPacket", []):
            parser.error("Sources do not contain the expected 26.3 chunk send path")
        result = {
            "minecraft": "26.3",
            "method": "Lexical class references excluding imports; not a complete send-path audit.",
            "protection_active": False,
            "packets": [{"class": n[:-5].replace("/", "."), "runtime_status": "not_intercepted",
                         "referenced_by": references[n.rsplit("/", 1)[-1][:-5]]} for n in packets],
        }
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + "\n")
        print(f"Inventoried {len(packets)} outgoing packet classes; all marked not_intercepted")


if __name__ == "__main__":
    main()
