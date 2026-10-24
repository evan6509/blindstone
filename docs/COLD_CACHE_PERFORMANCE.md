# Cold-cache performance — 2026-10-24

The clean validation passed **151/151 GameTests**, **32 Python tests**, the installable JAR audit and `git diff --check`. Both cold and warm eight-viewer gates pass. At that earlier checkpoint, the working tree was uncommitted on `codex/development`; no push, merge, release, deployment, EULA acceptance, ordinary-server test or live-server access had occurred. `eula=false` is retained. Python publication messages are mocked unit-test output. The independent rerun for that earlier snapshot is summarized above the latest final-slice section.

## What is cold

`coldMountainOreQueueAcceptance` uses a new terrain region for each of 1/2/4/8 viewers, with origins separated by at least 1,024 blocks. It never clears caches through reflection. Each count constructs its own mountain and incoming mountain 128 blocks away, outside all original terrain/light/snapshot dependency neighborhoods. Terrain construction and vanilla chunk generation are fixture setup, outside runtime timing; they do not query mod terrain/filter/light caches. This measures cold mod preparation of already-loaded chunks, not world-generation cost or cold JVM startup.

Every initial first refresh scans **2,304 geological columns** (nine full chunks), with **zero boundary cache hits** and zero boundary invalidations. Every incoming stream also scans 2,304 fresh columns in its first refresh. Lower-count stream frames can subsequently hit tables created earlier in that same frame; those hits do not replace the mandatory new scans. All four initial and stream regions are distinct. Within a count, normal cache sharing across its viewers is intentional and included in group timing.

The existing shared-region 1/2/4/8 workload remains separate in `mountain-ore-acceptance.json`, labeled `warm-shared`. Its first count is a baseline preparation with potentially mixed existing terrain caches; later counts reuse that region. It is **not cold-start eight-viewer evidence**. Cold results are in `mountain-ore-cold-acceptance.json`.

Both workloads retain range 16, nine tracked chunks per viewer, all existing edit/exposure/burst/roof/shaft/movement/resend events and stationary controls. The added stream forgets the previous nine chunks, performs the same lifecycle reset used for accepted teleportation, and sends nine chunks from the distinct incoming mountain. It does not authorize an oversized movement sweep. Ordinary movement remains the existing three-block accepted move. The default-range repeated 32-cycle workload remains in place.

Each count is recorded before terrain/viewer preparation. Event rows are recorded before mutation and retain failure status if settling throws. Reports retain preparation/event wall time, all refresh times, active percentiles, first-frame counters and peak-frame counters. The final sixteen quiet confirmations are excluded from active p95 and drain. No idle confirmations dilute acceptance. Initial preparation remains within 200 refreshes; edits retain the 20/40-refresh limits. Every eight-viewer event has active p95 <=10 ms and both event preparation and refresh maximum <50 ms. No thresholds or production budgets were relaxed.

## Reproduced spike and source changes

The historical 57.365-ms first-viewer sample had no accompanying profile; its exact stack cannot be reconstructed retrospectively. Instrumentation reproduced an **83.329-ms cold one-viewer refresh**, with **71.129 ms in terrain-boundary capture**, 2,304 column scans and 427,504 palette reads. A cold incoming-stream peak spent **18.594 ms** in boundary capture, with 294,912 palette reads in that frame. JFR also sampled `TerrainBoundary.rawHeights` and `capture`. These measurements identify the synchronous full-world-height geological search as the reproduced cold spike's dominant source.

`TerrainBoundary` now skips wholly empty sections and avoids geological-tag checks for air. For live loaded chunk identities it starts below the maintained vanilla WORLD_SURFACE upper bound, then searches for the same geological anchors. Foliage, buildings, water and other non-geological tops still require classification below that bound. Detached chunks retain section scanning because tests may mutate their palettes without maintaining heightmaps. Invalidations, neighboring horizons, unknown-neighbor opacity and disclosure rules are unchanged.

The pinned 26.3 bytecode was checked: `ChunkAccess.getHeight` primes a missing heightmap before reading it, and `LevelChunk.setBlockState` updates `WORLD_SURFACE`. The existing live surface-layer test exercises foliage above a lower geological surface, while detached palette-mutating tests verify the full-scan fallback. Remaining coverage gaps: there is no dedicated live high-mountain foliage/building/fluid overlay fixture or deliberately corrupted persisted-heightmap fixture; direct palette mutation of a live chunk outside the normal `LevelChunk` API is unsupported. `GeometrySnapshot` tests unavailable cells generically and the loaded-chunk-array implementation was statically checked to fail closed, but there is no `captureBounds`-specific unloaded-slot regression yet.

The new deterministic read regression failed before optimization and after section skipping alone. With the final implementation every cold initial row reads **46,349 states**, down from 427,504; the gate allows at most 32 palette reads per scanned column for this fixture. This added work gate supplements all existing timing/security gates.

Intermediate full runs also exposed a 14.069-ms p95 in the existing repeated-movement gate and a 24.303-ms one-capture warm ore-exposure frame. Those failures remain archived. Profiling showed repeated geometry reads and substantial temporary allocations: allocation samples estimated about 2.54 GB beneath `GeometrySnapshot.capture` and 1.36 GB beneath visibility-job construction over the diagnostic run, with frequent GC pauses. These are sampling estimates, not exact allocation counts or proof that a particular unrecorded frame coincided with GC.

Geometry capture now resolves both availability and block states from its bounded array of already-loaded chunk identities, avoiding per-cell boxed map probes and duplicate world chunk lookup. Geometry/state/shape capture and candidate enumeration use reusable position cursors; retained candidates are immutable. Candidate-distance comparisons avoid temporary vectors. Missing terrain remains unavailable/opaque, all geometry is frozen before tracing, and no chunks load during capture. Full functional, privacy, shape, light and adversarial tests pass.

## Clean-run measurements

All values below are milliseconds. Ten-millisecond p95 acceptance applies to eight viewers; smaller-count timings are retained without implying that all are below 10 ms. In particular the cold two-viewer initial p95 remains 15.920 ms in this run.

| Cold initial viewers | Active p95 | Refresh max | Preparation event | Active refreshes | Column scans | First-frame hits |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 6.270 | 18.392 | 3.098 | 33 | 2304 | 0 |
| 2 | 15.920 | 16.432 | 5.529 | 27 | 2304 | 0 |
| 4 | 5.739 | 8.032 | 17.813 | 32 | 2304 | 0 |
| 8 | 5.970 | 21.024 | 19.011 | 51 | 2304 | 0 |

Eight-viewer maxima below include both event/preparation wall time and refresh frames. Initial/stream delivery is below the original 200-refresh deadline. No disconnections occurred.

| Event | Cold active p95 | Cold max | Cold drain | Warm active p95 | Warm max | Warm drain |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| initial | 5.970 | 21.024 | 51 | 5.471 | 15.732 | 41 |
| single-edit | 0.457 | 0.457 | 1 | 0.340 | 0.340 | 1 |
| ore-exposure | 6.957 | 6.957 | 15 | 7.235 | 7.235 | 14 |
| four-cell-burst | 5.813 | 5.813 | 15 | 5.452 | 5.452 | 15 |
| roof | 5.273 | 5.273 | 15 | 5.693 | 5.693 | 15 |
| shaft | 5.122 | 5.492 | 31 | 5.044 | 5.269 | 31 |
| movement | 5.364 | 12.052 | 27 | 5.128 | 5.744 | 26 |
| resend | 0.570 | 0.570 | 1 | 0.341 | 0.341 | 1 |
| stream | 5.556 | 8.666 | 33 | 5.375 | 5.734 | 31 |

Stationary controls report zero repeated geometry/safety captures, rays, filtering, light jobs/operations and boundary scans. All default-range eight-viewer event and follow-up p95 values are <=6.401 ms, with maximum 9.820 ms. These isolated EmbeddedChannel/manual-stepping results establish the named synthetic gates, not real-server TPS, player capacity or graphical-client behavior.

## Reproduction and evidence

With the installed Java 25 JDK:

```sh
JAVA_HOME=/home/evan/.local/jdks/temurin-25.0.4.1+1 \
PATH=/home/evan/.local/jdks/temurin-25.0.4.1+1/bin:$PATH \
./gradlew --no-daemon --max-workers=2 clean build runGametest
python3 -m unittest discover -s tests -v
python3 scripts/verify_jar.py
git diff --check
```

Clean build: `BUILD SUCCESSFUL in 1m 10s`; XML has 151 cases, no failures/errors. Installable JAR SHA-256: `0f0815fc21a4ab9411c262067973918446b2d28018e2bb9b3921d32f44e7cc6a`, manifest commit `local`.

Evidence is preserved under `/home/evan/.hermes/cache/scratch/blindstone-combined-20261024/cold-cache-evidence/`: original baseline artifacts, each intermediate failed JSON/XML/log set, `red.jfr`, optimized diagnostic JFR/JSON, and `final/` with cold/warm/default JSON, XML, full clean log, Python/JAR logs, gate-verification JSON, EULA file and both JARs. Diagnostic recordings are not acceptance timing runs. Earlier report tables remain historical shared-cache results and must not be interpreted as cold eight-viewer results. No publication or ordinary admission test was run.

## Independent clean rerun

The parent independently ran `./gradlew --no-daemon --max-workers=2 clean build runGametest`, then `python3 -m unittest discover -s tests -v`, `python3 scripts/verify_jar.py` and `git diff --check`. The independent run passed **151/151 GameTests**, **32 Python tests**, the artifact audit and diff check; `eula=false` remained. The installable JAR SHA-256 matched `0f0815fc21a4ab9411c262067973918446b2d28018e2bb9b3921d32f44e7cc6a`.

For that independent run, cold eight-viewer initial active p95/max were **5.733/34.330 ms**; cold incoming-stream active p95/max were **5.452/7.687 ms**. Cold drains were 1 refresh for a single edit, 13 for ore exposure, 14 for a four-cell burst, 14 for roof, 29 for shaft, 25 for movement, 1 for resend and 38 for stream. Cold/warm mountain events remained within the 10/50 ms eight-viewer limits; every default-range eight-viewer p95 was below 10 ms and every max below 50 ms. The independent run's two-viewer cold initial p95 was 5.588 ms, while the earlier Codex run recorded 15.920 ms; this run-to-run variation is retained rather than assigned an unsupported cause. The gate is defined for eight viewers. These remain synthetic GameTest results, not live-server TPS or player-capacity evidence.
## Latest final-slice verification — 2026-10-24

The later 16,384-operation resumable sight-slice validation supersedes the earlier candidate measurements above. Its six exact-source clean runs (`voxel-1` through `voxel-6`) passed all 151 GameTests each (906/906) with unchanged gates; the full run table, retained failures, source manifest and artifact checksums are in [COLD_CACHE_GATE_INVESTIGATION.md](COLD_CACHE_GATE_INVESTIGATION.md) and [cold-cache-gate-metrics.json](cold-cache-gate-metrics.json). The parent independently ran a fresh clean build/GameTest against the same source: 151/151 passed. The fresh run also passed all 32 Python tests, the installable JAR audit and `git diff --check`; JAR SHA-256: `30bebc9c3a4648a8c6182fdbc6228aae8bee322e36f4c90e9350f3f46bfcdf6c`. `eula=false` remained.

In that independent run, the eight-viewer cold initial active p95/max/event were **5.810/7.280/18.141 ms**; warm-shared initial values were **5.755/6.799/12.793 ms**. Both mountain/ore acceptance files contained 36 rows with zero gate failures. The eight-viewer default-range p95 maximum was **6.674 ms**; maximum measured frame was **17.152 ms**. All are isolated synthetic GameTest measurements, not real-server TPS, player capacity, graphical gameplay or modpack compatibility.

Repeated opening/restoration acceptance computes the 10-ms p95 over the combined active-frame population for the same event and direction across its repeated cycles, excluding each cycle's own 16 quiet confirmations. Individual cycle p95 values and every frame remain in the raw output; each cycle's maximum, event duration and drain are gated separately. Thus a short cycle may show a p95 above 10 ms without failing the explicitly pooled event-p95 contract; no samples or GC-associated frames are removed. This is not a claim that every individual cycle's p95 is below 10 ms.

The reviewed coverage gap remains: there is no dedicated `captureBounds` regression that exercises an actual unloaded chunk slot, although missing geometry is represented as unavailable/opaque and the current capture path was statically reviewed. No live server was contacted or modified.
