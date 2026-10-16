# Blindstone

Server-side Fabric research prototype for hiding undiscovered underground blocks, air pockets, and base contents from clients. The design is in [the specification](docs/Blindstone-Spec.md).

**Development spike only. Protection is not active, and this is not a playable or deployable base-protection mod yet.** Normal startup deliberately fails because required outgoing hooks are missing. Isolated development mode blocks player admission, including operators. No client mod or custom networking handshake has been introduced.

## First milestone

- Pinned Minecraft 26.3 / Java 25 Fabric project with reproducible Gradle wrapper and CI configuration.
- Immutable 4096-block section masks and independent viewer state with bounded tracking and pending work.
- Revision checks reject replaced plans, changed geometry, unload/reload, session resets, and another viewer's plans.
- Isolated chunk-data filtering covers hidden air, ores, fluids, storage, and other block states. It rebuilds palettes, non-air/fluid counts, initial block-entity entries, and client heightmaps using vanilla serialization.
- Prototype reads real chunks on the server thread and constructs detached output; it never installs fake blocks in the world.
- `/blindstone status` and `/blindstone verify` provide development diagnostics from the server console.

The filter takes explicit authorization masks. A visibility solver does not exist yet: passing a mask in a test is not evidence that a player has legitimately earned it. No outgoing gameplay packets are currently intercepted. Lighting, update packets, entities, secondary effects, and sound handling remain required work. See the [packet coverage inventory](docs/PACKET_COVERAGE.md), [test report](docs/TEST_REPORT.md), and [next milestones](docs/IMPLEMENTATION.md).

## Build and test

| Component | Pin |
| --- | --- |
| Minecraft | 26.3, unobfuscated names; no separate mappings artifact |
| Java language/runtime | 25 |
| Fabric Loader | 0.19.5 |
| Fabric API | 0.163.0+26.3 |
| Fabric Loom | 1.17.14 |
| Gradle | 9.6.1, wrapper distribution SHA-256 verified |

Use a Java 25 JDK on `JAVA_HOME`/`PATH`:

```sh
./gradlew --no-daemon clean build runGametest
python3 scripts/verify_jar.py
```

GameTests automatically use an isolated directory under `build/gametest` and enable the admission-blocked development mode. Results: `build/gametest-results.xml`. The installable development artifact is `build/libs/blindstone-0.1.0-dev.1+mc26.3.jar`; keep it out of player servers.

To reproduce the packet class inventory:

```sh
./gradlew --no-daemon genSources
python3 scripts/inventory_packets.py
```

The generated inventory contains class names and reference locations, not redistributed Minecraft source. It is research support, not a complete call graph or a passing protection audit.

For an isolated console-only development server, supply `-Dblindstone.development=true` to the **server JVM**. This permits development startup while retaining mandatory login denial. Passing this flag only to the Gradle client JVM does not necessarily propagate it to a launched server. Normal `./gradlew runServer --args nogui` intentionally exits with the missing-hooks error.

## Protection limits

The intended first release covers the Overworld, initially world minimum through Y<64. The spike accepts section-aligned bounds and fixed stone for Y>=0/deepslate for Y<0. It leaves biomes and unprotected sections available. The vanilla 26.3 deepslate gradient spans Y0 to Y8; the prototype intentionally uses a seed-independent step at zero.

Player ESP, already downloaded terrain, surface clues, and ordinary audible activity remain discovery routes in the intended product. This project does not provide seed secrecy, world generation changes, claims, or anti-cheat.

Development commits belong on `codex/development`; `main` is reserved for explicitly approved integration. CI builds and tests only; there is no publishing or release workflow. A redistribution license has not yet been selected; the development metadata reserves rights pending that decision.
