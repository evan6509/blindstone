# Default-range follow-up — October 24, 2026

The local eight-viewer range-16 movement/edit workload now meets the requested p95 ≤10 ms and max <50 ms targets. This is a bounded synthetic production-path measurement, not a real-player capacity claim. Lighting backlogs remain a material limitation: lower per-refresh cost also spreads work over more refreshes.

## Matched method and test-first evidence

Baseline is production behavior at `17c1b22316c365ac6efc3c9f14ad081f1c57f5de`, with only opt-in light-operation instrumentation and the new harness/regression added. It was measured before the production repair, and measured again with the final harness. Its XML contains 128 passing tests and exactly one expected failure: the new sealed-room regression sees **17,148 candidates**, exceeding its ≤200 bound. The final clean run passes all **129** tests, including the candidate bound, <20,000 ray-work bound, visible wall disclosure, concealed exterior, and existing privacy/lifecycle/packet regressions. No timing thresholds are CI assertions.

`PlayableGameTests.defaultRangeProductionBenchmark` runs with the complete generated defaults, including range **16**, 65,536 ray-work units per viewer and 262,144 globally. The baseline default cooperative work allowance is **15 ms**; the final generated default is **5 ms**. All other defaults remain unchanged. Existing configuration files are preserved; installations retaining 15 ms do not automatically get the final 5 ms timing result. An intermediate pruning-only run at 15 ms still has eight-viewer movement/edit/burst event p95 of 16.039 / 15.255 / 15.444 ms; the final result combines less ray work with more conservative scheduling. [Intermediate raw measurements](evidence/followup-pruned-2026-10-24.json) precede the final light-operation/drain instrumentation and edge-streaming case and are diagnostic, not a fully matched third run.

Same host/JDK/pins as the historical method below; [fresh host metadata](evidence/followup-host-2026-10-24.json). No CPU pinning, GC isolation, extra optimization mods, or timing portability is claimed. Gradle heap is 2 GiB with two workers; the GameTest JVM uses its existing ergonomic heap.

Each surface (feet Y=65) and underground (Y=30) case uses **1/2/4/8 independent sessions**, with colocated accepted bodies and separate reveal/delivery state, sharing actual loaded scene geometry. Each tracks a **3×3, nine-chunk area** through the real transformed Connection → deferred filtering → lighting → publication path. A sealed gallery permits two accepted positions three blocks apart. These are simulated viewers using EmbeddedChannels, not player or transport throughput.

Before measurement and between workloads, step until **16 consecutive refreshes have no captures, rays, light operations, filtering, new queues or output**, bounded at 20,000 refreshes. All viewers must stay connected. Initial nine-chunk delivery and chunk loading are outside measurement. The old fixed 512 warm-up was insufficient at range 16; counting only newly created light jobs also falsely suggested quiescence while existing jobs ran. The final harness explicitly counts consumed light operations.

For each workload, run 32 cycles of one event frame plus four follow-up refreshes (160 total). Movement alternates accepted positions by **3 blocks** every cycle, exceeding the two-block rebase threshold. Assertions require exactly **32 sight captures per underground viewer** across event/follow-up frames. Edit alternates stone/air at one nearby cell; burst changes four cells. Streaming forgets an edge chunk and delivers a different already loaded edge chunk, alternating two edges while keeping nine tracked chunks. Static controls perform no event. After each workload, separately measure the entire drain through the final 16 idle refreshes (`*-tail` rows).

Timing includes beginTick, refresh, EmbeddedChannel draining, and event-frame fixture changes or stream source packet construction. It excludes setup, loading, initial delivery, reporting/counter aggregation, normal server simulation, real transport and rendering. Event timing is therefore an inclusive local path cost, not an isolated CPU profiler attribution to mod code alone. Follow-up frames can still contain deferred active work; they are not called idle. Percentiles use nearest rank; raw JSON retains nanoseconds and every counter. With 32 event samples, p99 equals max.

## Range-16 results

Underground times in **ms**, p50 / p95 / p99 / max. Each event row has 32 samples. Static controls are separate from these percentiles.

| Viewers | Event | Baseline, 15 ms allowance | Final, 5 ms allowance |
| ---: | --- | --- | --- |
| 1 | movement | 7.342 / 12.767 / 14.273 / 14.273 | 2.322 / 2.500 / 8.970 / 8.970 |
| 1 | edit | 12.666 / 15.084 / 15.086 / 15.086 | 5.083 / 5.204 / 5.216 / 5.216 |
| 1 | burst | 12.730 / 15.182 / 15.203 / 15.203 | 5.150 / 5.587 / 7.264 / 7.264 |
| 1 | stream | 12.081 / 14.263 / 15.143 / 15.143 | 5.072 / 5.203 / 5.306 / 5.306 |
| 2 | movement | 13.880 / 18.258 / 19.739 / 19.739 | 4.518 / 4.812 / 5.629 / 5.629 |
| 2 | edit | 19.583 / 23.739 / 24.018 / 24.018 | 5.100 / 5.643 / 6.935 / 6.935 |
| 2 | burst | 19.643 / 24.137 / 24.301 / 24.301 | 5.205 / 5.908 / 5.998 / 5.998 |
| 2 | stream | 18.953 / 22.848 / 23.220 / 23.220 | 5.125 / 5.209 / 5.323 / 5.323 |
| 4 | movement | 20.664 / 21.454 / 25.231 / 25.231 | 6.800 / 7.016 / 7.119 / 7.119 |
| 4 | edit | 19.511 / 23.894 / 24.111 / 24.111 | 5.090 / 5.193 / 6.103 / 6.103 |
| 4 | burst | 19.655 / 23.499 / 24.877 / 24.877 | 5.178 / 5.301 / 6.920 / 6.920 |
| 4 | stream | 18.868 / 23.238 / 23.815 / 23.815 | 5.210 / 5.265 / 5.374 / 5.374 |
| 8 | movement | 20.606 / 21.379 / 23.258 / 23.258 | 6.985 / 7.146 / 8.746 / 8.746 |
| 8 | edit | 19.593 / 23.384 / 23.576 / 23.576 | 5.145 / 5.215 / 5.230 / 5.230 |
| 8 | burst | 19.759 / 23.590 / 23.690 / 23.690 | 5.312 / 5.402 / 5.465 / 5.465 |
| 8 | stream | 19.222 / 24.524 / 24.769 / 24.769 | 5.380 / 5.432 / 5.500 / 5.500 |

Eight-viewer follow-up and drain costs are kept separate from event frames. Follow-up rows each have 128 samples; drain sample counts include the final 16 idle refreshes, and timing percentiles alone must not obscure their duration.

| Workload/phase | Baseline ms | Final ms | Drain refreshes before → after |
| --- | --- | --- | ---: |
| movement, four follow-ups/cycle | 16.558 / 21.087 / 21.584 / 22.083 | 0.032 / 7.009 / 7.267 / 9.678 | — |
| movement, final drain | 5.139 / 6.634 / 8.495 / 10.402 | 0.007 / 0.011 / 0.011 / 0.011 | 555 → 16 |
| edit, four follow-ups/cycle | 16.783 / 20.610 / 20.806 / 21.975 | 5.033 / 7.140 / 8.556 / 10.054 | — |
| edit, final drain | 5.331 / 15.078 / 15.806 / 20.807 | 5.014 / 5.047 / 5.076 / 5.128 | 565 → 163 |
| burst, four follow-ups/cycle | 16.897 / 20.529 / 20.924 / 20.997 | 5.034 / 7.063 / 8.197 / 8.315 | — |
| burst, final drain | 5.317 / 15.069 / 15.856 / 16.598 | 5.014 / 5.048 / 5.117 / 5.134 | 563 → 168 |
| stream, four follow-ups/cycle | 15.036 / 21.401 / 21.504 / 23.878 | 5.019 / 5.027 / 5.049 / 6.604 | — |
| stream, final drain | 5.107 / 6.667 / 15.026 / 15.179 | 0.012 / 5.028 / 5.057 / 5.057 | 559 → 31 |

Static controls: all surface and underground 1/2/4/8-viewer cases have zero shape/safety captures, rays, section writes, chunk filters, light jobs/operations, queued generations and packets in their 160 measured static frames, before and after. Eight-viewer static timings are:

| Scene/phase | Baseline ms | Final ms |
| --- | --- | --- |
| Surface, event slot | 0.009 / 0.012 / 0.016 / 0.016 | 0.009 / 0.013 / 0.014 / 0.014 |
| Surface, follow-up slots | 0.009 / 0.011 / 0.017 / 0.020 | 0.009 / 0.012 / 0.017 / 0.022 |
| Underground, event slot | 0.008 / 0.020 / 0.025 / 0.025 | 0.008 / 0.012 / 0.017 / 0.017 |
| Underground, follow-up slots | 0.008 / 0.009 / 0.016 / 0.020 | 0.007 / 0.008 / 0.010 / 0.011 |

All surface event/follow-up/drain metrics, and every smaller-viewer control/phase, are included in the raw files rather than mixed into underground event percentiles.

## Work reduction and remaining queues

Eight-viewer totals below include **event + follow-up + full drain**, with the final idle confirmations. Each entry is baseline → final. `queued` counts newly queued generations, not peak queue depth; coalescing/cancellation means these totals are not distinct delivered chunks. Existing configured queue/memory caps remain enforced. `lightOperations` counts work consumed by existing jobs as well as new jobs, preventing unobserved light processing during drain.

| Workload | Captures / copied cells | Ray-work units | Section writes | Chunk filters | Light jobs / operations | Queued / packets |
| --- | --- | --- | --- | --- | --- | --- |
| movement | 256 → 256 / 10,976,000 → 10,976,000 | 180,050,584 → 319,488 | 0 → 0 | 0 → 0 | 0 → 0 / 0 → 0 | 0 → 0 / 0 → 0 |
| edit | 253 → 223 / 10,847,375 → 9,561,125 | 168,319,640 → 256,937 | 2,472 → 2,472 | 72 → 72 | 940 → 941 / 104,075,951 → 76,725,697 | 2,304 → 2,304 / 72 → 72 |
| burst | 253 → 225 / 10,847,375 → 9,646,875 | 168,319,640 → 259,383 | 2,472 → 2,472 | 72 → 72 | 940 → 944 / 103,545,395 → 79,218,626 | 9,216 → 9,216 / 72 → 72 |
| stream | 206 → 163 / 8,832,250 → 6,988,625 | 164,190,872 → 199,349 | 0 → 0 | 99 → 8 | 200 → 162 / 78,425,139 → 45,919,155 | 256 → 256 / 355 → 264 |

The largest final eight-viewer movement/edit/burst sample across events, follow-ups and complete drains is **10.054 ms**. All viewers stay connected and settle. The 5 ms allowance remains cooperative: one capture/evaluator slice, safety and serialization can overshoot it. The local targets are met for this fixture, not guaranteed by the constant.

The main work repair rejects candidates disconnected from the accepted eye clearance region by complete opaque cubes before candidate sorting and ray creation. A bounded six-neighbor flood includes the cells visited by axis-ordered DDA, stops at solid cubes while retaining their wall faces, and traverses partial/transparent shapes conservatively. Exact shape-aware rays still authorize every reveal. Missing candidates remain explicitly incomplete. No flood result authorizes disclosure. Snapshot bounds, per-viewer knowledge, Y<64 surface concealment, body margin, accepted movement bounds, update/entity/block-entity/light filtering and fail-closed paths remain intact.

Full 42,875-cell captures remain per viewer/rebase; this is **not** a sliding snapshot or shared geometry cache. The added flood uses a bounded int queue and bitset per job (at most 42,875 queue entries). Ray/candidate work falls substantially, but no measured allocation-byte or retained-heap claim is made. Shared chunk-revision caches and sliding snapshots were considered: frozen neighbor-dependent shapes, moving halos, availability and lifecycle invalidation add correctness complexity. The measured first repair is candidate pruning. Existing local edit invalidation still rebuilds neighboring light halos; completed decisions already persist. Range 8 would have a 6,859-cell bound instead of 42,875, but was not benchmarked here or selected as a new default.

Lower work per refresh does not mean immediate editing/streaming delivery. The final eight-viewer edit and burst drains still require **163 and 168 refreshes**, including 16 idle confirmations, after 32 repeatedly invalidating events. At nominal 20 Hz those refresh counts would span seconds; manually stepped timings are not real-server delivery latency. Coalesced light work is cancelled/restarted during repeated edits. This remains a substantial backlog limitation; the benchmark does not establish responsiveness for simultaneous exploration into unknown cells during edits. Broad open/connected cave systems may defeat the connectivity pruning and remain expensive. Different viewer locations, one-way travel into fresh terrain, generation/loading, entity-dense scenes, retained memory, packet bytes, real SMP ticks and graphical playability remain unmeasured. The historical performance complaint is not proven solved.

## Reproduction and evidence

- [Final matched baseline, all phases/counters](evidence/followup-baseline-2026-10-24.json)
- [Final clean-build result, all phases/counters](evidence/followup-after-2026-10-24.json)
- [Host and configuration metadata](evidence/followup-host-2026-10-24.json)

With the pinned Java 25 JDK on PATH, run the clean build/GameTests, Python tests, verifier and diff check shown in the historical reproduction section below. The new test writes `build/gametest/default-range-benchmark.json`; CI uploads it along with the historical benchmark and actual XML. To reproduce the baseline, apply the final harness plus opt-in `WorkMetrics`/`LightScheduler` instrumentation to `17c1b22`, keeping its production evaluator/snapshot/defaults; the new sealed-room regression is expected to fail. The default-range benchmark itself must pass. Final behavior passes all tests. No EULA-accepting admission, ordinary dedicated server/client, deployment or release is part of reproduction.

---

# Historical range-4 anti-base redesign — October 24, 2026

The historical complaint has no captured server profile/log and has **not** been reproduced as a deterministic real-server slowdown. These measurements establish specific repeat-work defects and their removal. They do not establish SMP capacity or explain the user's old experience.

## Method

Baseline: `ba2121cc335b52442f0f352dab5ca74d23ff9805`, unchanged production behavior with opt-in counters and the new test harness only. Baseline was run before production behavior changes. `PlayableGameTests.deterministicProductionBenchmark` drives the real transformed Connection boundary, deferred chunk filtering, lighting and `BlindstoneRuntime` using EmbeddedChannels and actual loaded fixture geometry. It is an isolated Fabric GameTest, with external admission closed. No ordinary dedicated server/admission harness was run.

Host: Linux x86_64, Ubuntu kernel 7.0.0-38-generic, Intel i5-9400F @ 2.90 GHz, six physical/logical cores. Temurin Java 25.0.4.1+1-LTS; Minecraft 26.3; Loader 0.19.5; Fabric API 0.163.0+26.3; Loom 1.17.14; Gradle 9.6.1. Gradle uses `-Xmx2G`, two workers; GameTest heap uses JVM ergonomics with no explicit override. No optimization mods. This is a shared desktop, without CPU pinning, frequency control or GC isolation.

Both runs use identical range **4**, a 50 ms instrumented-work guard, all other validated defaults, a sealed stone room, and one explicitly delivered tracked chunk per viewer. Surface accepted feet Y=65; underground feet Y=30. Each 1/2/4/8-viewer case warms for 512 refreshes, then measures 64 refreshes for each event: stationary, one accepted 0.5-block movement, one nearby torch edit. There are 128 settling refreshes between events. The edit is restored outside measurement. Viewers share scene geometry but retain independent delivery state. Timings include beginTick, refresh and draining EmbeddedChannel output; they exclude setup, initial packet arrival, warm-up and real transport/client rendering. Default range 16, sustained movement/streaming, entity-dense scenes and real server ticks remain unmeasured.

Counters are opt-in and disabled in normal operation. `captures`/`cells` count full shape snapshots and copied geometry cells; `safetyCaptures` counts separate availability-only snapshots; `rayChecks` counts spent ray/cell/shape work units; `chunkFilters` counts detached serialized chunk builds; `partialSections` counts partial section rebuilds; `lightJobs` counts new jobs; `queued` counts queued chunk generations; `packets` counts drained output frames. `sectionWrites` was added for the one-cell regression after baseline: the old path writes exactly 4096 cells per partial section; the new counter measures actual writes. None of these counters is a whole-server time cap.

Allocation proxy: each safety capture constructs at least one GeometrySnapshot and one Cell array, before its maps/masks; captures and copied cells are a defensible lower-bound allocation/work proxy, **not allocated bytes** or retained heap measurements. Chunk builds/light jobs/partial writes are additional work proxies. No byte allocation claim is made.

## Stationary results

Times are microseconds per manually stepped refresh, shown as p50 / p95 / p99 / max (nearest-rank over 64 samples; p99 equals max). Counts cover all 64 samples.

| Scene | Viewers | Safety captures before → after | Baseline µs | Redesigned µs |
| --- | ---: | ---: | --- | --- |
| Surface | 1 | 128 → 0 | 13.23 / 14.75 / 40.41 / 40.41 | 5.78 / 8.12 / 49.43 / 49.43 |
| Surface | 2 | 256 → 0 | 16.34 / 17.96 / 20.29 / 20.29 | 4.79 / 5.83 / 8.98 / 8.98 |
| Surface | 4 | 512 → 0 | 13.09 / 15.76 / 32.49 / 32.49 | 8.95 / 9.65 / 13.56 / 13.56 |
| Surface | 8 | 1024 → 0 | 17.11 / 18.75 / 21.53 / 21.53 | 10.44 / 12.56 / 20.06 / 20.06 |
| Underground | 1 | 128 → 0 | 9.68 / 12.80 / 33.21 / 33.21 | 4.54 / 7.13 / 26.61 / 26.61 |
| Underground | 2 | 256 → 0 | 12.40 / 13.77 / 33.92 / 33.92 | 4.18 / 4.69 / 6.83 / 6.83 |
| Underground | 4 | 512 → 0 | 18.50 / 20.49 / 26.27 / 26.27 | 5.76 / 6.42 / 7.97 / 7.97 |
| Underground | 8 | 1024 → 0 | 29.65 / 33.69 / 36.48 / 36.48 | 8.71 / 10.91 / 25.56 / 25.56 |

All stationary cases had zero full geometry captures, ray checks, chunk filters, light jobs, queued generations and output packets **both before and after**. The original completed sight job was already retained: blaming repeated stationary full sight scans is disproved for this fixture. Repeat safety snapshots fell from two per viewer per refresh to zero. Timing gains vary; the counter assertions are the CI contract, not timing thresholds.

## Movement and edit results

Counts cover 64 refreshes after the single event. Full numeric p50/p95/p99/max and all counters are in the linked JSON files.

| Scene/event | Viewers | Safety captures before → after | Shape captures before → after | Rays before → after | Light jobs / chunks / packets after | p95 µs before → after |
| --- | ---: | ---: | ---: | ---: | --- | ---: |
| Surface movement | 1 | 128 → 0 | 0 → 0 | 0 → 0 | 0 / 0 / 0 | 14.00 → 10.81 |
| Surface edit | 1 | 128 → 0 | 0 → 0 | 0 → 0 | 0 / 0 / 0 | 14.15 → 5.85 |
| Surface movement | 2 | 256 → 0 | 0 → 0 | 0 → 0 | 0 / 0 / 0 | 19.13 → 6.49 |
| Surface edit | 2 | 256 → 0 | 0 → 0 | 0 → 0 | 0 / 0 / 0 | 19.88 → 6.91 |
| Surface movement | 4 | 512 → 0 | 0 → 0 | 0 → 0 | 0 / 0 / 0 | 14.72 → 12.44 |
| Surface edit | 4 | 512 → 0 | 0 → 0 | 0 → 0 | 0 / 0 / 0 | 14.84 → 10.02 |
| Surface movement | 8 | 1024 → 0 | 0 → 0 | 0 → 0 | 0 / 0 / 0 | 19.47 → 11.26 |
| Surface edit | 8 | 1024 → 0 | 0 → 0 | 0 → 0 | 0 / 0 / 0 | 33.83 → 11.17 |
| Underground movement | 1 | 128 → 2 | 0 → 0 | 0 → 0 | 0 / 0 / 0 | 11.88 → 4.62 |
| Underground edit | 1 | 128 → 0 | 1 → 1 | 1275 → 41017 | 1 / 1 / 1 | 3296.83 → 9.66 |
| Underground movement | 2 | 256 → 4 | 0 → 0 | 0 → 0 | 0 / 0 / 0 | 19.19 → 5.27 |
| Underground edit | 2 | 256 → 0 | 2 → 2 | 2550 → 82034 | 2 / 2 / 2 | 3964.84 → 8.24 |
| Underground movement | 4 | 512 → 8 | 0 → 0 | 0 → 0 | 0 / 0 / 0 | 22.12 → 9.70 |
| Underground edit | 4 | 512 → 0 | 4 → 4 | 5100 → 164068 | 4 / 4 / 4 | 6492.06 → 14.88 |
| Underground movement | 8 | 1024 → 16 | 0 → 0 | 0 → 0 | 0 / 0 / 0 | 36.33 → 21.79 |
| Underground edit | 8 | 1024 → 0 | 8 → 8 | 10200 → 328136 | 8 / 8 / 8 | 19151.34 → 23.28 |

The stronger policy does more work on an underground edit: ray units rise from 1,275 to 41,017 per viewer because former public exterior cells are no longer preauthorized. One-viewer edit max rises from 5.74 to 8.78 ms; eight-viewer max rises from 42.63 to 50.07 ms. Most samples after a single event are idle, so the much smaller p95 does **not** mean edit latency improved. These maxima and the additional ray work are an explicit active-work regression/tradeoff for stricter concealment, despite lower stationary work and far fewer partial writes. Default-range sustained multiplayer performance remains an acceptance gap.

A separate stationary armor-stand regression confirms zero new sight captures/rays over 16 ticks after the first complete decision; the old per-tick cache clear fails it. Local opaque edits and nearby chunk lifecycle changes invalidate that decision. Budget-exhausted decisions are not retained as permanent negatives. A separate one-cell partial-section regression fails at 4096 writes before the repair and passes at one afterward. Completely concealed sections retain implicit uniform palettes, with zero protected-cell writes. Local plan invalidation retains a distant unsent plan; light invalidation now addresses nine neighboring keys rather than scanning each cache.

## Policy and bounds

Protected Overworld cells from world minimum through Y<64 receive stone at Y≥0 and deepslate below zero. No public sky-column, foliage, water, glass or adjacent-face exception remains. Surface eyes cannot authorize protected sight. Ordinary terrain at/above the cutoff remains real. Low valleys and cave entrances below the cutoff deliberately appear filled before underground exploration.

Underground accepted eyes use the existing bounded per-block shape-aware sight and reachable third-person cameras. Completed jobs persist; movement of at least two blocks from the job origin, body size changes, intersecting edits/chunk lifecycle or session/configuration changes rebase sight. Immediate accepted player/root-vehicle body and swept movement receive the fixed 1/16-block safety margin, with the existing 16-block accepted-segment/body/8192-capture bounds. This can disclose cells touching that narrow body corridor; it never grants an entire radius, section or cave. Smaller movements receive body safety while the retained sight origin remains within its two-block threshold. Previously sent knowledge remains per viewer until untracking/reset.

Default caps remain 128 viewers, 1024 tracked chunks per viewer / 8192 globally, 128 queued chunks per viewer / 8192 globally, 64 MiB configured mask payload accounting and 128 MiB global light-job/cache accounting; the mask estimate excludes general object overhead and retained geometry/packet objects. Light schedulers bound jobs/ready entries at 16/128 per viewer; entity sight entries are bounded at 16384. Local unsent masks within two chunk offsets are revoked conservatively; distant pending plans survive. At most nine light halo keys are invalidated per affected center. Source capture stays on the server thread and never loads unknown chunks. Queues defer within their configured bounds; inability to maintain safe publication disconnects the affected viewer, with no unfiltered fallback. The default 15 ms instrumented-work guard is cooperative and **not a hard whole-server tick cap**; mandatory safety, serialization, other mods and ordinary simulation can exceed it.

## Evidence and reproduction

- [Baseline counters/timings](evidence/redesign-baseline-2026-10-24.json)
- [Redesigned counters/timings](evidence/redesign-after-2026-10-24.json)
- [Host/settings/build summary](evidence/redesign-2026-10-24.json)

With the pinned JDK on PATH:

```sh
./gradlew --offline --no-daemon --max-workers=2 clean build runGametest
python3 -m unittest discover -s tests -v
python3 scripts/verify_jar.py
git diff --check
```

The suite writes fresh measurements to `build/gametest/benchmark.json`; CI retains that artifact. Timings are evidence, never flaky pass/fail thresholds. Ordinary admission requires new explicit EULA authorization and is gated off on automatic pushes. Real multiplayer ticks, graphical/minimap/profiler clients, authentication/proxies, modpacks and deployment remain untested for this redesign.
