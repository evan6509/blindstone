# Acceptance repair — 2026-10-24

Cold-cache follow-up: [COLD_CACHE_PERFORMANCE.md](COLD_CACHE_PERFORMANCE.md) records the separate cold 1/2/4/8-viewer regression, profiling, source fixes and clean 151/151 validation. Earlier shared-region eight-viewer tables in this report are warm-cache evidence, not cold-start results.

This report captures the first local repair run from the dirty `codex/development` working tree based on `7ed06e0106cea065cef7c9d7fce3eee610be23a4`. No timeout, protection setting, viewer count or drain allowance was relaxed. At that test capture, no commit, push, CI submission, deployment, ordinary server/client, live-server access or EULA acceptance occurred; isolated GameTests kept `eula=false`. The later cold-cache work and independent rerun are recorded in [COLD_CACHE_PERFORMANCE.md](COLD_CACHE_PERFORMANCE.md).

## Security and native ore coverage

The original seven-cell sky-opening regression was observed RED. Wide open valley and cliff controls passed. Terrain classification now combines the narrow-hole closing with the lowest of four cardinal geological horizons, using loaded neighboring columns across seams. Enclosed openings cannot recursively authorize connected air. Seven-cell openings, an eight-cell loaded seam opening, seam opening-to-valley mutation, the prior seam fixture and exterior valley/cliff controls pass actual serialized-section assertions.

Only changed geological columns are rescanned. Neighboring horizon/snapshot tables are invalidated on mutation and chunk availability changes; light keys include the extra terrain dependencies beyond the 15-block light halo. Unknown neighboring terrain is opaque. Geological anchors exclude foliage, wood, glass and water.

Default Overworld ore rules now include the actual Fabric `#c:ores` registry tag, vanilla ore tags and raw ore blocks. Registry-backed tests resolve real common-tag members and ordered exclusions. Existing legacy cutoff migration, custom caps/settings, exclusive ore heights, per-player discovery, real chunk/block-update delivery, hidden mountain light and deterministic host-rock replacements remain covered. Actual Nether target updates use netherrack; native cooperative Nether lighting has zero skylight and preserves public emission. Nether cave geometry is public; the End remains unprotected. This is native deterministic discovery filtering, not an AntiXray engine-2/3 port. No third-party implementation code was copied; the original reference is DrexHD/AntiXray SHA `afd90724dc48be28ad7452331232525f24383d08`, MIT.

## Performance root causes and changes

Intermediate phase counters showed about 174–185 ms of light scanning per settled single edit across nine halos, with essentially no propagation. Initial masks also caused repeated neighboring reconstructions as each chunk published. Exact frozen authorization dependencies now prevent redundant invalidation, and identical immutable policies/masks share reconstruction and output across viewers. Different private masks have separate keys; an explicit two-viewer torch regression proves isolation and complete shared-memory release.

Uniform opaque/sunlit/dark column spans use analytical shortcuts. Uniform states are implicit and nonuniform state arrays allocate by section. Propagation queues use bounded segments and duplicate-suppression bits. A diagnostic JFR run identified 445 `Arrays.fill` samples beneath light-job constructors; redundant dense reference fills were removed. Missing vanilla light layers use their proven zero-block-light/inherited-sky-plane behavior. All shape/torch/privacy tests remain enforced.

A non-emitting edit reuses a settled light field only if its cell and all six neighbors have zero filtered sky/block light and the geological height cannot change. It still resends changed geometry. New emitters, lit cells, terrain roofs and shafts reject that shortcut. Adding full opaque host rock below an unchanged geological top does not recapture a completed, fully published sight result; incomplete authorization is still revoked. Geometry removal and movement still refresh discovery.

Revision-invalidated chunk snapshots share only exact masks and fresh visible block-entity tags, with a 16-MiB accounting cap. Light jobs/results remain inside the existing global 128-MiB budget; the idle result cache has additional 64-entry/16-MiB limits and is released with the last session. Publication and packet interception/filtering debit the same work allowance as reconstruction. Exterior discovery selects ore candidates before tracing and avoids capture in regions with no nearby ore targets.

## Acceptance method

The matched mountain/ore fixture retains default range 16, nine tracked chunks and independent 1/2/4/8-viewer sessions. Initial preparation is reported separately from settled local edit, ore exposure, four-cell burst, roof, shaft, movement and resend events. Every reached count is recorded before preparation, so a failed/skipped count cannot become a partial pass. Initial delivery still has the production 200-refresh deadline. Stationary controls require zero repeated captures, rays, light jobs/operations and filtering.

Single edits must drain within 20 refreshes. Burst, exposure, roof and shaft gates are 40 refreshes. Eight-viewer active work p95 must be at most 10 ms and every measured frame/event max below 50 ms, including initial preparation. The final sixteen quiet confirmation refreshes are excluded from active percentiles and drain. The default-range production benchmark separately tests 32 movement/edit/burst/stream cycles for surface and underground viewers at every count, with hard timing and tail-drain assertions. Event frames and follow-up frames are separate; event path and refresh p95 are also recorded. No ordinary-server TPS or real-player capacity claim follows from these synthetic EmbeddedChannel measurements.

## Results

Final measurement tables and artifact verification appear below. Original failed evidence remains in `ore-continuation-evidence/`; all repair logs, intermediate failed rows, diagnostic JFR and final JSON/XML are preserved under `/home/evan/.hermes/cache/scratch/blindstone-combined-20261024/acceptance-repair-evidence/`. Earlier checkpoint documents remain historical records, not current acceptance status.

## Limits

The terrain classifier is bounded and geometric: enclosed natural depressions may be filled from outside, horizons beyond the sampled region and unusual diagonal/curved openings need additional adversarial coverage, custom geological blocks need anchor support, and stone-built roofs can act as terrain. Tests establish the named broad-opening/seam/exterior cases, not every possible mountain. Per-viewer exploration/body safety is the explicit route into concealed interiors. Previously sent knowledge cannot be revoked. Graphical/adversarial client behavior, custom transport stacks and representative SMP capacity remain outside this isolated repair validation. No independent external reviewer or remote CI run is claimed.

## Final verified measurements

Full suite: **150/150 GameTests**, zero failures, twice on the final sparse-storage implementation, followed by a third passing validation after cache validation/accounting review. A clean `build runGametest` passed; final `build runGametest` passed. Python release-helper tests: **32 passed** (publication output is mocked). JAR audit and `git diff --check` passed.

Drain refreshes, excluding sixteen quiet confirmations:

| Event | Before 1 viewer | Final 1 | Before 2 | Final 2 | Final 4 | Final 8 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| single-edit | 54 | 1 | 108 | 1 | 1 | 1 |
| ore-exposure | 106 | 14 | 212 | 12 | 13 | 15 |
| four-cell-burst | 55 | 14 | 108 | 12 | 13 | 15 |
| roof | 54 | 13 | 108 | 12 | 13 | 15 |
| shaft | 107 | 16 | 214 | 18 | 21 | 32 |
| movement | 165 | 18 | 336 | 19 | 23 | 32 |
| resend | 6 | 1 | 11 | 1 | 1 | 1 |

Before repair, initial preparation disconnected at four viewers; eight viewers were not reached. Every final 1/2/4/8 count delivered and settled without disconnection. Eight-viewer mountain initial active preparation took 32 refreshes, with sixteen additional quiet confirmations.

Eight-viewer mountain active frame timing (ms; idle confirmations excluded):

| Event | Active p95 | Max |
| --- | ---: | ---: |
| initial | 5.490 | 5.826 |
| single-edit | 0.438 | 0.438 |
| ore-exposure | 9.271 | 9.271 |
| four-cell-burst | 5.073 | 5.073 |
| roof | 5.137 | 5.137 |
| shaft | 5.420 | 5.478 |
| movement | 5.691 | 9.521 |
| resend | 0.500 | 0.500 |

Default-range production benchmark, eight viewers, 32 event cycles (ms):

| Position | Event | Event p95 | Follow-up p95 | Max across both |
| --- | --- | ---: | ---: | ---: |
| Surface | static | 0.015 | 0.010 | 0.021 |
| Surface | movement | 0.135 | 0.022 | 4.545 |
| Surface | edit | 0.106 | 0.011 | 0.138 |
| Surface | burst | 0.191 | 0.011 | 0.204 |
| Surface | stream | 4.285 | 0.015 | 9.424 |
| Underground | static | 0.010 | 0.008 | 0.013 |
| Underground | movement | 7.125 | 7.044 | 11.097 |
| Underground | edit | 7.262 | 6.696 | 11.125 |
| Underground | burst | 6.003 | 5.462 | 7.927 |
| Underground | stream | 7.464 | 6.932 | 10.840 |

All stationary controls passed with zero repeated geometry/light work. The default repeated-edit and burst tail gates also passed. These are isolated synthetic results, not live-server TPS.

Artifact from this earlier repair run: `blindstone-0.1.0-alpha.1+mc26.3.jar`, manifest commit `local`, SHA-256 `bb1b1321c5de65f7dea43103f5e41eb7fe881cc6e3a43a7d655ff18d9393a8e0`. It is not the later cold-cache build artifact. These measurements came from a working tree based on source HEAD `7ed06e0106cea065cef7c9d7fce3eee610be23a4`; at that capture the changes were uncommitted and no remote CI result was included. This historical report makes no release or deployment claim.
