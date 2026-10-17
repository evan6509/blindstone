# Blindstone

A server-side Fabric **private alpha** that conceals undiscovered Overworld blocks, air pockets, light sources, block entities, and non-player entities separately for each viewer. Ordinary clients can join without installing Blindstone. The server keeps its real world; concealed underground data is replaced only in outgoing views.

Normal startup enables filtering after required hooks and configuration validate. `-Dblindstone.development=true` is reserved for isolated automated tests and deliberately denies player admission. Ordinary offline admission and lifecycle now have a real handshake/configuration regression against the installed production JAR. Graphical gameplay, authenticated/proxy admission, adversarial-client acceptance, and representative SMP capacity remain unverified. See [test evidence](docs/TEST_REPORT.md).

## Install

1. Use a dedicated **Minecraft 26.3** server with **Java 25**, **Fabric Loader 0.19.5**, and **Fabric API 0.163.0+26.3**.
2. Put `blindstone-0.1.0-alpha.1+mc26.3.jar` and the pinned Fabric API JAR in the server's `mods` directory.
3. Start the server normally, without the development JVM flag. The first start creates `config/blindstone.json`.
4. Check `/blindstone status` and `/blindstone verify`. Operators do not receive a viewing bypass.

Use a separate test server or a copy of your world for the alpha's gameplay acceptance checks. Install it server-side only. The exact version pins are intentional; other game versions and optimization stacks require separate compatibility tests.

## Current behavior

- The default protected range is world minimum through **Y<64**, using stone at Y≥0 and deepslate below zero. Concealment includes air, fluids, ores, storage and other underground construction.
- Outdoor air, clear water columns, and the first sky-visible terrain surfaces are preserved in the initial chunk, including below Y=64. Adjacent exterior faces are public too; this does not flood through covered rooms or connected caves. Directly sky-open shafts and terrain beneath foliage/glass are public clues.
- Shape-aware sight runs in all directions from the accepted server position, including constrained third-person offsets. Authorization is per block, not per cave, section, or chunk.
- A small body and accepted-movement envelope reveals nearby cells immediately. This explicit safety exception can disclose cells just across a wall; it stays fixed under load.
- Full chunk data, later block/entity updates, bundles and direct vanilla send paths are filtered. Section palettes, counts, block entities and heightmaps describe the filtered representation.
- Filtered lighting is reconstructed incrementally and published with matching chunk views. Visible torches illuminate revealed air; concealed sources cannot supply protected light. Safe upper-world light is preserved using a documented attenuation bound.
- Non-player tracking and packet field adapters withhold concealed entities and references. Player entities and ordinary audible activity remain available.
- Work, queues, masks, light jobs and light caches are bounded. Unsafe or unsupported delivery disconnects the affected viewer instead of reverting to real hidden data.
- Exterior columns/masks are cached across viewers. Flying beyond underground reveal range skips sight-geometry capture; public surface cells do not create private reveal resends. Fully concealed sections use a uniform palette without scanning all 4096 blocks.

[Configuration and commands](docs/CONFIGURATION.md), [packet coverage](docs/PACKET_COVERAGE.md), and [implementation details](docs/IMPLEMENTATION.md) describe the current policies and limits. The [specification](docs/Blindstone-Spec.md) remains the product target, not a statement that every acceptance scenario has passed.

## Build and test

| Component | Pin |
| --- | --- |
| Minecraft | 26.3, unobfuscated names |
| Java | 25 |
| Fabric Loader | 0.19.5 |
| Fabric API | 0.163.0+26.3 |
| Fabric Loom | 1.17.14 |
| Gradle | 9.6.1, wrapper distribution SHA-256 verified |

With a Java 25 JDK on `JAVA_HOME`/`PATH`:

```sh
./gradlew --no-daemon clean build runGametest
python3 scripts/verify_jar.py
./gradlew --no-daemon prepareAdmission
python3 scripts/test_admission.py
python3 scripts/test_admission.py --development-denial
```

The installable artifact is `build/libs/blindstone-0.1.0-alpha.1+mc26.3.jar`. GameTests use `build/gametest`, keep external player admission closed, and write `build/gametest-results.xml`.

The admission harness installs only the production JAR and pinned Fabric API into a fresh `build/admission/<uuid>` world. It binds an ephemeral loopback listener, completes offline login/configuration with two independent headless clients, decodes concealed chunks, exercises lifecycle/rejoin and observes normal keepalives for 20 seconds. It stops its own server and writes `result.json` and `server.log`. It requires no client mod or development flag. The separate `--development-denial` negative control verifies the isolation flag still rejects external logins. These checks also run in CI.

For a separate normal-mode development server, prepare `build/smoke/eula.txt` and server properties, then run:

```sh
./gradlew --no-daemon runSmoke --args nogui
```

This run uses `build/smoke` and **does not** set the development flag. Type `stop` in its console to shut down. The smoke run is separate from automated gameplay fixtures.

To reproduce the lexical packet inventory:

```sh
./gradlew --no-daemon genSources
python3 scripts/inventory_packets.py
```

## Limits

Previously downloaded terrain cannot be erased from a modified client. Player ESP, normal sounds, surface clues, public terrain/biomes, and information shared by other players remain discovery routes. Blindstone provides no seed secrecy, world-generation changes, claims, or anti-cheat.

The Overworld is the only protected dimension. Sampled sight can cause delayed reveals around small openings; supported movement remains bounded. Post-effect identifiers, resource-pack controls and audited server UI metadata have explicit policies. Protected-PLAY cookies, show-dialog schemas and arbitrary mod payloads remain unsupported. Non-transport PLAY packets sent off-thread disconnect even outside the Overworld. Direct Netty writes and replacement send paths need explicit compatibility work. See the [integration audit](docs/COMPATIBILITY.md). Automated admission does not prove graphical playability, profiler resistance, or SMP performance.

Development commits belong on `codex/development`; `main` requires explicit integration approval. CI builds/tests only; no release or publishing workflow is configured. Redistribution rights remain reserved pending a license decision.
