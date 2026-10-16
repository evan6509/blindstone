# Private alpha test report

**Date:** October 16, 2026. **Artifact:** `blindstone-0.1.0-alpha.1+mc26.3.jar`.
Normal startup enables filtering and player admission after mandatory hooks/configuration validate. The isolated development test mode still denies outside logins.

## Confirmed local results

| Check | Result |
| --- | --- |
| Clean build and transformed Minecraft tests | PASS — `./gradlew --offline --no-daemon clean build runGametest` |
| Blindstone tests | **105 passed**, zero failures/errors; vanilla `always_pass` makes **106 total** |
| Original chunk/mask/state/readiness tests | 19 passed |
| Geometry and resumable visibility | 22 passed |
| Packet policies and vanilla codecs | 30 passed |
| Reference/incremental lighting and scheduler | 12 passed |
| Configuration and diagnostic lifecycle | 8 passed |
| Transformed Connection/runtime routes | 6 passed |
| Deferred gameplay pipeline | 6 passed |
| Actual loopback TCP PLAY transport | 2 passed |
| Artifact verification | PASS — exact dependency pins, server-only metadata, Java 25 classes, mandatory hooks, no test classes in JAR |
| Normal dedicated-server development run | PASS — no development flag, loopback-only disposable flat world, startup completed and status reported `ENFORCING` / admission `OPEN` |
| Console administration | PASS — status, verify, reload, sanitized report, disable warning, explicit disable confirmation, disabled status and clean stop |
| GitHub CI | Workflow present; this local checkpoint has not been uploaded or run remotely |

Toolchain: Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.163.0+26.3, Loom 1.17.14, Gradle 9.6.1 and Homebrew OpenJDK 25.0.4.1 on macOS ARM64. Tests used the pinned Fabric stack without optimization mods.

Artifact SHA-256: `cfa3e4ff3223f6e97c0df11fe73f0254152721ba3ec1c61cba2dee9886e49b2e`.

Machine-readable local results: `build/gametest-results.xml`. Generated run directories/logs are ignored. The normal boot used Loom's main-source development launch, not an independently installed production-server distribution. Its offline authentication setting and ephemeral loopback port were confined to the disposable smoke world; authenticated admission is not established by it.

## What the tests establish

**World views:** Sealed-room and solid-terrain chunk payloads are equivalent while concealed. Real authorized states, air and block entities restore individually. Palettes, non-air/fluid counts and heightmaps agree with the filtered representation. Inputs and live chunks are preserved; negative coordinates, independent viewers, unload/reset, stale plans and bounds are covered.

**Visibility and movement:** Fixtures exercise walls, glass/panes, fluids, doors, slabs, powder snow, conservative piston/chest/shulker geometry, clipped third-person camera paths, fixed body safety, falling/diagonal accepted movement and a supported root-vehicle body. Resumable jobs advance under tiny budgets and match uninterrupted evaluation. Remote block/chunk changes retain unrelated jobs. Safety reveals only the explicit straight swept body corridor with a fixed 1/16-block margin; it does not reconstruct intermediate movement bends.

**Entity and secondary packets:** Tests validate vanilla field/serializer identities, ordinary item/variant/facing/state data, hidden ID and position references, projectile owners, passenger pruning, leash clearing, absolute reconstruction of relative teleports, allowed particle options, explosion knockback preservation and positional sound conversion. Unknown schemas/payloads fail closed. These tests do not establish the appearance or behavior of every vanilla entity in a rendered client.

**Deferred delivery:** Real transformed listener/direct Connection sends and EmbeddedChannel fixtures exercise source callbacks, batch ordering/counts, body-safe initial chunks, later sight resends, chest tags, heightmaps and reconstructed sky/torch light. Independent viewers receive different sealed-room views without mutation of shared source packets. Geometry changes and reload rebuild pending generations. Closing a barrier revokes authorized-but-unsent room masks before publication; previously delivered knowledge is retained.

**Lighting:** Filtered-state emission and sky propagation use vanilla opacity/face occlusion and a 15-block halo. Hidden sources do not contribute protected light; allowed torches propagate across sections/chunks. Scheduler fixtures cover cooperative progress, generation invalidation, cache/global memory bounds and immutable output. The live pipeline now uses this reconstruction rather than constant darkness. Real light is copied only at the documented safe upper boundary; unknown neighbors remain conservatively opaque. Immediate new body disclosure can be temporarily under-lit until its next light refresh.

**Actual TCP:** Two localhost NIO sockets use vanilla framing, packet encoders/decoders and PLAY protocols to inspect independent filtered block/bundle output, hidden block-entity suppression and source callbacks. A second fixture decodes a deferred full chunk, every section, accepted body state, concealed distant air and light mask/array counts. Server and client share in-process registry access and enter PLAY directly. These fixtures explicitly bypass login, account authentication and configuration/registry exchange; they are not retail graphical clients.

**Configuration/readiness/admin:** Strict complete JSON validation, atomic defaults/reports, immutable configuration, validation-before-invalidation, callback retention across reload, hook marker/accessor checks and exact console source identity are covered. The normal-server smoke run exercised the console commands with no connected viewers. Active-player command transitions have narrower automated pipeline coverage and still need operational acceptance. Runtime reports keep hook loading, observed/enforced paths and imported automated/live evidence distinct; this test report does not automatically mark a production server as live-tested.

## Measurements and remaining acceptance

The controlled range-4 pipeline fixtures used manually advanced runtime steps, a 50 ms work allowance and one/two embedded viewers. In the final run they completed their selected conditions in 1–2 steps; recorded fixture wall times ranged from 2.2 to 19.0 ms and the largest measured individual step was 10.4 ms. The TCP tests completed in 0.112 s and 0.024 s in the test runner. These are synthetic fixture measurements, **not** first-login/reveal latency, sustained throughput, added-tick percentiles or SMP capacity. Defaults use range 16 and a 15 ms measured-work guard, which is not a hard whole-server tick deadline.

Before broad/public distribution, complete:

- Ordinary-client login/configuration and rendered gameplay: mining, building, combat, projectiles, cameras, tiny openings, vehicles, supported fast travel, teleport, respawn, dimension changes and reconnects.
- Adversarial minimap/X-ray/freecam/ESP inspection and exact profiler comparisons for matched present/absent fixtures.
- Representative multiplayer load, memory, network bytes, first-chunk/reveal delay and tick percentiles; systematic overload/fault injection.
- Operational reload/disable tests with active clients and explicit compatibility tests for each optimization or alternate networking stack.

Unsupported debug/tag-query/locator/look-at paths remain suppressed, arbitrary custom gameplay payloads disconnect, and some visual effects are sanitized. Visible authored text/items, players, ordinary sounds, public terrain/biomes and prior downloaded knowledge remain available information. See [packet coverage](PACKET_COVERAGE.md) and [implementation](IMPLEMENTATION.md). This is a runnable private alpha with the above evidence boundaries, not a production protection or performance claim.
