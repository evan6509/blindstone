# Private alpha test report

## Anti-base redesign — October 24, 2026

The current policy removes protected public-exterior/sky-column authorization and keeps per-viewer block-level sight/body safety. Full evidence and limitations: [PERFORMANCE.md](PERFORMANCE.md), [machine-readable build summary](evidence/redesign-2026-10-24.json).

- Clean pinned `clean build runGametest` passed with two Gradle workers: **127 total tests, 126 Blindstone tests, zero failures/errors**. Python unit suite: **32 passed**. Artifact verifier and `git diff --check` passed.
- RED controls first confirmed delivered Y-layer exposure, repeated stationary safety captures, per-tick entity sight capture, global unsent-plan invalidation and 4096 writes for a one-cell partial reveal. Each targeted regression passes after repair; the entity cache also rejects local edits and nearby chunk lifecycle changes.
- New actual-pipeline initial/resend checks compare complete serialized chunk data against uniform protected replacement, including palettes/counts/heightmaps/block-entity entries, and inspect hidden light plus preserved above-cutoff glass/leaves. Existing sealed-room/independent-viewer, movement, single/section updates, bundle, block entity, light, non-player entity/event, lifecycle and compatibility/limit fixtures remain passing. Four October 17 public-exterior tests were intentionally replaced because their expected disclosure conflicts with the anti-base policy.
- Baseline vs redesign measured 1/2/4/8 viewers under identical deterministic range-4 workloads. Stationary safety captures fell from 128 per viewer over 64 refreshes to zero. Stationary full sight/chunk/light work was already zero. Stricter concealment increases ray work and worst edit refresh time; see the numeric report, including the eight-viewer max change from 42.63 to 50.07 ms. No overall real-server speedup is claimed.
- Production JAR SHA-256: `2f7a9da6583c8802e7d9c82d05467eff62790efe0da81b56657044cb9786e525`. Exact pinned server metadata, Java 25 bytecode, mandatory hooks and absence of tests/client classes were audited. The local build manifest labels source `local`; this hash identifies the reviewed working-tree build.
- No ordinary server/admission harness or EULA acceptance was run. Automatic CI admission steps now require an explicit manual EULA input. Historical admission results below apply only to their old builds. No deployment, release or main merge occurred. Graphical clients/minimaps/profiler, authentication/proxies, modpacks and representative SMP ticks remain untested for this redesign.

## Historical surface correction — October 17, 2026

Historical source was the surface correction in the working tree on `codex/development`, based on `95a99794cc6b90071130da2df09396858cb86bc0`. The installable JAR remains `build/libs/blindstone-0.1.0-alpha.1+mc26.3.jar`.

- `./gradlew --offline --no-daemon build runGametest prepareAdmission` passed; the final `build runGametest` rerun after adding the stale-surface regression also passed. XML reports **126 total tests, 125 Blindstone tests, zero failures/errors**.
- `python3 scripts/verify_jar.py` passed, including the new surface classifier, exact pins, mandatory hooks and exclusion of test code.
- `python3 scripts/test_admission.py` passed against the exact production JAR below. Two independent clients completed normal offline admission, hidden-room isolation and lifecycle/rejoin checks, then remained connected for **20 seconds / 393 observed server ticks** with normal keepalives. The separate development-denial control was not rerun for this correction.
- New decoded-packet regressions preserve grass beneath plants/foliage, outdoor air below Y=64, a complete clear water column with skylight, and exposed cliff faces while retaining buried chest/torch/ore concealment. A flying viewer performs no underground sight captures or reveal resends over unchanged public terrain. Tests also cover roof changes before initial publication, a formerly public yard becoming covered, sky-open-shaft invalidation, cache reuse and non-recursive first-face exposure.
- Exterior geometry is deliberately public regardless of viewer position. Sky-open shafts, foliage/glass sight columns and immediately adjacent first faces are disclosure exceptions; this does not flood through covered rooms or connected caves. Exterior masks do not accumulate as permanent private knowledge. Wide opaque overhangs can still require local sight.

Artifact SHA-256: `a79d9a2885acecf85a35a16a06f2aea46c162eced56030d5ad011f96ad2b452e`.

Tracked summary: [surface-correction-2026-10-17.json](evidence/surface-correction-2026-10-17.json). Local logs are `build/surface-validation.log`, `build/surface-admission.log` and the admission UUID directory referenced by the summary. These checks do not establish graphical correctness on the user's server, live tick-time improvement, or representative SMP capacity. No deployment was performed.

## Earlier login remediation baseline

The remaining commit, artifact hash, counts, timings and RED controls below describe the earlier login-remediation build, before the surface correction.

**Date:** October 17, 2026. **Artifact:** `blindstone-0.1.0-alpha.1+mc26.3.jar`.
Implementation source: `9de9b8d7be398f63b5847fe9d81905d88a26192a` on `codex/development`, compared with reviewed source `4ea6c45bda39b757a9243f1398f81719d6147215`.
Normal startup enables filtering and player admission after mandatory hooks/configuration validate. The isolated development test mode still denies outside logins.

## Confirmed local results

| Check | Result |
| --- | --- |
| Clean build and transformed Minecraft tests | PASS — `./gradlew --no-daemon clean build runGametest prepareAdmission`; `BUILD SUCCESSFUL in 14s` |
| Blindstone tests | **121 passed**, zero failures/errors; vanilla `always_pass` makes **122 total**, parsed from actual XML |
| Original chunk/mask/state/readiness tests | 19 passed |
| Geometry and resumable visibility | 22 passed |
| Packet policies and vanilla codecs | 30 passed |
| Reference/incremental lighting and scheduler | 12 passed |
| Configuration and diagnostic lifecycle | 8 passed |
| Transformed Connection/runtime routes | 21 passed |
| Deferred gameplay pipeline | 6 passed |
| Actual loopback TCP PLAY transport | 3 passed |
| Artifact verification | PASS — exact dependency pins, server-only metadata, Java 25 classes, mandatory hooks, no test classes in JAR |
| Installed production-JAR admission | PASS — no development flag; real handshake, offline login, configuration/32 registries, ordinary placement, chunks and keepalives; two independent viewers plus rejoin |
| Normal runtime lifecycle | PASS — vanilla post-effect commands, death/respawn request, Nether/Overworld travel, active-viewer reload, status/report and clean stop |
| Development isolation control | PASS — installed JAR with isolation flag rejects external login before world delivery |
| GitHub CI | Workflow includes the full admission and development-denial harnesses; local results here do not claim a remote run |

Toolchain: Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.163.0+26.3, Loom 1.17.14, Gradle 9.6.1 and Homebrew OpenJDK 25.0.4.1 on macOS ARM64. Tests used the pinned Fabric stack without optimization mods.

Artifact SHA-256: `a2995097d88ef475eb966da9e99d4b604d0fdadb8a3ddfdedc25b46f0b04c0a7`.

Machine-readable tracked summary: [login-remediation-2026-10-17.json](evidence/login-remediation-2026-10-17.json). Full local evidence is under `build/verification`, including RED/GREEN XML, build logs, admission results/server logs/properties/configuration and the runtime report. Generated evidence is ignored and a clean build removes it; each fresh harness run prints its UUID evidence directory under `build/admission`.

The final admission run used the extracted production Minecraft server with Fabric Loader, only the installed Blindstone/Fabric API JARs in `mods`, and `fabric.development=false`. No GameTest/companion mod or shared client/server registries were used. Offline authentication and ephemeral loopback ports were confined to new disposable worlds. No authenticated or proxy traversal is established.

## Reproduction and RED controls

With the pinned Java 25 JDK on `JAVA_HOME`:

```sh
./gradlew --no-daemon clean build runGametest prepareAdmission
python3 scripts/verify_jar.py
python3 scripts/test_admission.py
python3 scripts/test_admission.py --development-denial
git diff --check
```

All commands passed locally. The preparation task was also rerun after confining its utility logs to `build`; it passed and the artifact hash remained identical.

| Regression control | Actual expected RED result | GREEN evidence |
| --- | --- | --- |
| New empty/nonempty vanilla post-effects and common-policy fixtures against original filter | 112 XML cases, 3 failures, 0 errors | All 122 final cases pass |
| Suppressed-frame flush before repair | 113 XML cases, 1 failure, 0 errors: earlier unflushed frame not published | Frame and both callbacks delivered |
| Intercepted-light flush with only its repair removed | 122 XML cases, 1 failure, 0 errors | Frame and both callbacks delivered |
| Ordinary login using original reviewed `PacketFilter` compiled into a control production JAR | Harness FAIL during placement; socket closes; server logs `Unaudited outgoing packet: net.minecraft.network.protocol.common.ClientboundPostEffectsPacket`, with `sendPostEffects` / `placeNewPlayer` stack | Clean-built installed production JAR completes admission and lifecycle |

The control JAR contains the reviewed filter with the other current hook changes; it is a targeted mutation control, not a byte-identical reconstruction of the historical artifact. Its RED evidence is `build/verification/admission-red`. Restore/keep the fixed source before building the deliverable. Baseline failures were recorded before each repair or by removing only the specific repair and immediately restoring it.

## What the tests establish

**World views:** Sealed-room and solid-terrain chunk payloads are equivalent while concealed. Real authorized states, air and block entities restore individually. Palettes, non-air/fluid counts and heightmaps agree with the filtered representation. Inputs and live chunks are preserved; negative coordinates, independent viewers, unload/reset, stale plans and bounds are covered.

**Visibility and movement:** Fixtures exercise walls, glass/panes, fluids, doors, slabs, powder snow, conservative piston/chest/shulker geometry, clipped third-person camera paths, fixed body safety, falling/diagonal accepted movement and a supported root-vehicle body. Resumable jobs advance under tiny budgets and match uninterrupted evaluation. Remote block/chunk changes retain unrelated jobs. Safety reveals only the explicit straight swept body corridor with a fixed 1/16-block margin; it does not reconstruct intermediate movement bends.

**Entity and secondary packets:** Tests validate vanilla field/serializer identities, ordinary item/variant/facing/state data, hidden ID and position references, projectile owners, passenger pruning, leash clearing, absolute reconstruction of relative teleports, allowed particle options, explosion knockback preservation and positional sound conversion. Unknown schemas/payloads fail closed. These tests do not establish the appearance or behavior of every vanilla entity in a rendered client.

**Deferred delivery:** Real transformed listener/direct Connection sends and EmbeddedChannel fixtures exercise source callbacks, batch ordering/counts, body-safe initial chunks, later sight resends, chest tags, heightmaps and reconstructed sky/torch light. Independent viewers receive different sealed-room views without mutation of shared source packets. Geometry changes and reload rebuild pending generations. Closing a barrier revokes authorized-but-unsent room masks before publication; previously delivered knowledge is retained.

**Lighting:** Filtered-state emission and sky propagation use vanilla opacity/face occlusion and a 15-block halo. Hidden sources do not contribute protected light; allowed torches propagate across sections/chunks. Scheduler fixtures cover cooperative progress, generation invalidation, cache/global memory bounds and immutable output. The live pipeline now uses this reconstruction rather than constant darkness. Real light is copied only at the documented safe upper boundary; unknown neighbors remain conservatively opaque. Immediate new body disclosure can be temporarily under-lit until its next light refresh.

**Actual TCP:** Two localhost NIO sockets use vanilla framing, packet encoders/decoders and PLAY protocols to inspect independent filtered block/bundle output, hidden block-entity suppression and source callbacks. A second fixture decodes a deferred full chunk, every section, accepted body state, concealed distant air and light mask/array counts. Server and client share in-process registry access and enter PLAY directly. These fixtures explicitly bypass login, account authentication and configuration/registry exchange; they are not retail graphical clients.

The third TCP fixture round-trips empty/nonempty post effects, required resource-pack push with prompt, pop, nonempty report details/server link, clear-dialog and transfer packets, preserving fields/order and completing all callbacks after an explicit flush. It verifies codecs, not actual resource downloading, shader rendering or destination traversal.

**Ordinary admission:** `scripts/test_admission.py` uses protocol IDs extracted from the exact pinned runtime and independent socket clients. Each client declares no known packs, receives and parses 32 complete network registries, acknowledges configuration, receives PLAY login/post effects, acknowledges 26.3 teleport positions/rotation and chunk batches, and decodes every received chunk section and light mask/array. The controlled flat world has a sealed air chamber and real diamond ore at `(8,20,8)`. Both surface viewers receive stone for the ore and chamber air. Moving only the first viewer inside reveals the real diamond/air there while the other viewer retains stone.

The final normal run observed **20 seconds / 400 server ticks** with both independent viewers connected and one normal PLAY keepalive response each. It decoded 99/98 full chunks for the initial viewers and 9 on rejoin. Initial placement, nonempty/cleared effects, serverbound death-respawn, leaving/returning to Overworld, active reload and same-identity disconnect/rejoin passed. Every join received the optional configuration resource pack and explicitly replied DECLINED; the server correctly continued admission. Nonempty server links were decoded. No resource pack was downloaded/applied. No unexpected client disconnect reason was recorded; the disposable server exited 0.

Normal evidence: `build/verification/admission-green/{result.json,server.log,server.properties,blindstone.json,runtime-report.json}`. Development-denial evidence: `build/verification/development-green/{result.json,server.log}`. Each result records versions, mode, exact launch command, observation interval, packet counts, payload IDs and any client failure. The development negative control deliberately records the expected login rejection; its server also exits 0.

**Common packets, payloads and threads:** The [compatibility audit](COMPATIBILITY.md) documents exact common schemas/client actions/send sites, codec-generated bundle delimiters, all baseline Fabric API payload families and the unsupported cookie/dialog/modded routes. New transformed tests cover schema identity versus same-name opaque payloads, off-thread world rejection in protected/unprotected dimensions, off-thread keepalive success and callback success/suppression/exception/disconnect/reload. Only immutable listener server ownership is read before the thread guard. The unknown-packet default and all concealment boundaries remain fail-closed.

**Configuration/readiness/admin:** Strict complete JSON validation, atomic defaults/reports, immutable configuration, validation-before-invalidation, callback retention across reload, hook marker/accessor checks and exact console source identity are covered. The October 16 empty-server smoke covered the console disable warning/confirmation and disabled status. The current installed-JAR admission run additionally covers status/report and reload with connected viewers. Active-load/manual-disable acceptance remains incomplete. Runtime reports keep hook loading, observed/enforced paths and imported automated/live evidence distinct; this test report does not automatically mark a production server as live-tested.

## Measurements and remaining acceptance

The admission harness used default Blindstone settings: range 16, Y<64, fixed 1/16-block body margin and 15 ms measured-work guard; server view/simulation distance 2, one controlled flat world and two active viewers. The development-denial control ran in a separate disposable world. This is correctness evidence, **not** first-login/reveal latency, sustained throughput, tick percentiles or SMP capacity. Existing range-4/50 ms manually stepped pipeline fixtures remain synthetic tests. The configured work guard is not a hard whole-server tick deadline.

Before broad/public distribution, complete:

- Online account authentication, exact FabricProxy-Lite artifact/proxy forwarding and supported integration-stack traversal.
- Rendered gameplay: mining, building, combat, projectiles, cameras, tiny openings, vehicles and supported fast travel; graphical confirmation of automated lifecycle paths.
- Adversarial minimap/X-ray/freecam/ESP inspection and exact profiler comparisons for matched present/absent fixtures.
- Representative multiplayer load, memory, network bytes, first-chunk/reveal delay and tick percentiles; systematic overload/fault injection.
- Operational reload/disable tests with active clients and explicit compatibility tests for each optimization or alternate networking stack.

Unsupported debug/tag-query/locator/look-at paths remain suppressed; opaque protected-PLAY cookies, show-dialog schemas and arbitrary custom gameplay payloads disconnect. Unsupported asynchronous non-transport PLAY sends also disconnect before dimension policy. Visible authored text/items, players, ordinary sounds, public terrain/biomes and prior knowledge remain available information. See [packet coverage](PACKET_COVERAGE.md), [compatibility](COMPATIBILITY.md) and [implementation](IMPLEMENTATION.md). No Hub integration, existing service/configuration change, deployment or release was performed. This is a private alpha with the stated evidence boundaries, not a production-readiness or exhaustive bug-free claim.
