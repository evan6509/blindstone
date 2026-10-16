# Initial integration spike test report

**Date:** October 16, 2026. **Protection status:** inactive; not ready for player use.

## Confirmed local evidence

| Check | Result |
| --- | --- |
| Exact toolchain resolves; vanilla sources generate | PASS — Minecraft 26.3, Loader 0.19.5, Fabric API 0.163.0+26.3, Loom 1.17.14, Gradle 9.6.1, Java 25 |
| Clean production build | PASS — `./gradlew --no-daemon clean build runGametest` |
| Blindstone server integration tests | PASS — 18 Blindstone cases, zero failures/errors; XML also contains vanilla's one `always_pass` case, for 19 total |
| Production artifact verification | PASS — `python3 scripts/verify_jar.py`; exact dependency pins, server environment, Java 25 classes, required admission mixin, no GameTest classes bundled |
| Default actual server launch | PASS (expected refusal) — `./gradlew --no-daemon runServer --args nogui` exits nonzero during mod initialization with `Blindstone protection cannot activate: mandatory outgoing packet hooks are not implemented` |
| Development admission hook | PASS — actual transformed `PlayerList.canPlayerLogin` returns the explicit development rejection in a running Fabric GameTest server |
| Live chunk integrity within filtering call | PASS — serialized real section/biome/count/heightmap/block-entity data, section/entity object references and unsaved flag unchanged |
| CI configuration | Added; GitHub-hosted execution has not been run |

The local runtime was Homebrew OpenJDK 25.0.4.1 on macOS ARM64. The test server used the plain pinned Fabric stack plus Blindstone and its test mod. Results are in `build/gametest-results.xml`; run directories and full logs are generated/ignored rather than committed.

Verified development artifact: `build/libs/blindstone-0.1.0-dev.1+mc26.3.jar`.

SHA-256: `80b90e84d6971c4aa6b7040223d1f3643725a73e172a01d23971eb4bbb4d8907`.

## What the 18 Blindstone tests establish

1. A sealed-room fixture and corresponding solid terrain produce identical filtered chunk-data payloads after vanilla codec round trips.
2. Fully concealed block palettes contain only replacement terrain; non-air counts are 4096, fluid counts zero, and hidden block-entity entries are absent.
3. Explicit masks restore only authorized blocks/air and matching initial block entities; adjacent ores and fluids remain hidden.
4. Client heightmaps describe the filtered geometry, including a fake solid cutoff at Y64 above an originally empty chunk.
5. Y63 is concealed, Y64 is preserved, and the world minimum is included; unprotected block entities survive.
6. Filtering for an authorized viewer does not change the output for another viewer.
7. Filtering a real loaded world chunk does not change its serialized state, object references or dirty flag during the call.
8. Mutable caller bitsets/maps cannot alter immutable masks or a queued plan.
9. Pending reveals do not authorize ordinary delivered-state reads before commit; committed plans cannot replay.
10. Successive commits accumulate exact block masks without revealing neighboring blocks.
11. Negative chunk, section and block-entity coordinates round trip with the correct local mask.
12. Geometry changes invalidate pending work while retaining knowledge already delivered in the same tracking session.
13. Another viewer, unload/reload at the same coordinate, and session reset reject old plans.
14. Replacement plans invalidate older work; chunk/queue saturation refuses new work without granting visibility.
15. Invalid bounds, oversized masks, out-of-range coordinates/sections and invalid budgets throw instead of yielding unfiltered output.
16. Background calls to the chunk filter are rejected before world content is read.
17. Missing outgoing protection hooks reject normal initialization.
18. The mandatory transformed login hook denies development player admission.

The code under test uses real 26.3 `LevelChunkSection` and `ClientboundLevelChunkPacketData` stream codecs and a running Minecraft server. The byte comparisons cover constructed chunk-data payloads. They are not captures of outgoing client deliveries, encrypted traffic, or a complete chunk-with-light packet.

## Required evidence still absent

| Area | Status |
| --- | --- |
| Real per-player outgoing packet interception and decoded network delivery | Not implemented/tested |
| Visibility geometry, third-person offsets, safety envelope and legitimate interaction authorization | Not implemented/tested |
| Chunk lifecycle/geometry event wiring, movement and reveal timing | Not implemented/tested |
| Single/multiple block corrections and subsequent block-entity updates | Not implemented/tested |
| Hidden lighting and light crossing section/chunk boundaries | Not implemented/tested |
| Non-player entity spawning, references, movement, combat and profiler signals | Not implemented/tested |
| Secondary events, compounds, entity-attached sounds and bundles | Not implemented/tested |
| Full startup hook readiness/runtime failure recovery and queue-overload disconnects | Admission/startup scaffolding tested; full enforcement recovery pending |
| Config file/reload, inspect/report commands and sanitized runtime diagnostics | Pending; only development status/verify exist |
| Real ordinary/adversarial clients, minimaps, freecam, X-ray and ESP | Not tested |
| Existing-world saved-data/generation/seed/simulation equivalence across a full run | Not tested; the narrower live-chunk non-mutation check above passes |
| SMP performance, tick percentiles, per-player memory/network cost and reveal latency | Not benchmarked; no capacity claim |
| Optimization mods, other dimensions, other Minecraft versions | Not tested/supported |

All required packet paths and live acceptance scenarios in the specification remain release gates. A successful build and these passing tests establish a usable engineering foundation, not base-protection effectiveness.
