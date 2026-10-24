# Measured anti-base redesign — October 24, 2026

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
