# Mountain foundation phase — implemented, overall task open

This bounded phase report records the intermediate mountain foundation. It is not overall completion, release approval, or performance acceptance. At the time of the report, the tree was uncommitted on `codex/development`, based on `7ed06e0106cea065cef7c9d7fce3eee610be23a4`; later ore integration, acceptance repair and cold-cache performance reports supersede its pending status. No deployment, installation, ordinary server/client, admission or EULA acceptance occurred during that phase. See the current acceptance/cold-cache reports for later verification.

## Implementation

`ConcealmentPolicy` keeps a broad vertical eligibility band and adds coordinate-aware authorization. Schema 2 defaults cap protection at 320, with unconditional concealment below 64 and a public geological exterior boundary above it. This is not blanket high-altitude replacement. Schema-1 default cap 64 migrates to 320; custom caps and unrelated range/budget values survive.

`TerrainBoundary` captures geological column heights, then performs a radius-two horizontal max/min closing. Narrow shafts do not authorize their connected rooms. Public exterior is not inserted into viewer exploration masks. The weak-key chunk cache is independent of viewer state; mutations dirty only the changed column and recompute the fixed 256-cell boundary on demand. Chunk reloads use new identities. No stationary/global terrain rescan occurs. Chunk filtering retains fully public and uniform-hidden section fast paths.

The coordinate rule is shared by chunk serialization/block entities, block updates, entity/event authorization, runtime surface-eye sight eligibility, and synchronous/cooperative filtered lighting. Body movement remains an explicit private exception. Existing chunk lifecycle invalidation cancels affected sight/light work; high terrain edits also invalidate affected light/resend generations. Light copying starts a complete section plus 16 blocks above the highest potentially private halo boundary, avoiding full-height work for ordinary plains.

## Verification

- Strengthened RED: room/chest/torch fixtures failed because mountain storage tags were public; legacy migration also failed. Same-altitude exterior control passed.
- Targeted GREEN: **6/6** `blindstone_tests:*mountain*` tests passed. Complete production packet codec fixtures exercise 5×5×3 rooms at **96, 128 and 192**, roof/sky at **208/209**, diamond ore, storage tags and narrow shaft. Assertions at every elevation executed.
- Private-mask/resend test checks two independent viewer snapshots and roof mutations through the transformed `LevelChunk.setBlockState` hook; another chunk remains unaffected.
- Production light capture checks hidden high torch/stone variants produce identical complete outgoing light bytes, dark private room/shaft and bright exterior sky.
- Two embedded live connection pipelines check legitimate high-room discovery/chest tags/torch lighting for an interior viewer, continued concealment for a surface viewer, and a later filtered chest update.
- Full `runGametest`: **136/136 passed**, EULA remains **false**. The old TCP underground control now selects below the unconditional band rather than incorrectly selecting public Y=319 and attempting a roof outside build height.
- Release-helper Python tests: **32 passed**.
- Fresh `build`: passed. Fresh JAR audit: passed, including the new terrain class. JAR SHA-256: `3fe0f929f1db430ca8f28c42f87228c0124f08e615ae3b7802ab08cc6e625f3d` (manifest commit `local`).
- Evidence logs/XML: scratch `mountain-foundation-{red,targeted,full,python,build,jar-audit}.log` and `mountain-foundation-results.xml`. The original baseline evidence remains intact. Full-suite benchmark execution is regression coverage, not a passed performance gate.

## Remaining scope and coverage gaps

Explicit AntiXray-equivalent ore IDs/tags/configuration and exposure behavior remain a later phase; high mountain ore is currently concealed by the same interior rule. No independent final verification or final CI has run. The historical **157/158-refresh edit/burst drain** remains an open blocker; new matched mountain/ore/open-cave performance workloads and optimization remain required. Do not claim performance is good.

The boundary is chunk-local and closing only bridges narrow openings (up to four cells); broad openings, chunk-seam combinations, complex cliffs, overhangs, custom geology and stone-built exterior structures need further adversarial tests/design. Geological anchors exclude wooden structures/foliage/water/glass, but extensive high-altitude fixture/render coverage is not established. New mountain connection coverage is embedded rather than a graphical or independent adversarial client. Roof mutation coverage verifies local classification/resend snapshots; a fully delivered roof/shaft lifecycle sequence with simultaneous viewers remains additional coverage. Nether and End remain unprotected. Previously delivered private knowledge cannot be revoked from a client.

## Exact changed files

- `README.md`
- `docs/Blindstone-Spec.md`
- `docs/CONFIGURATION.md`
- `docs/IMPLEMENTATION.md`
- `docs/MOUNTAIN_PHASE.md`
- `docs/PACKET_COVERAGE.md`
- `docs/PERFORMANCE.md`
- `docs/TEST_REPORT.md`
- `scripts/verify_jar.py`
- `src/gametest/java/com/evanchubbuck/blindstone/ConfigurationGameTests.java`
- `src/gametest/java/com/evanchubbuck/blindstone/LiveConnectionGameTests.java`
- `src/gametest/java/com/evanchubbuck/blindstone/PacketFilterGameTests.java`
- `src/gametest/java/com/evanchubbuck/blindstone/PlayableGameTests.java`
- `src/gametest/java/com/evanchubbuck/blindstone/network/FilteredLightGameTests.java`
- `src/main/java/com/evanchubbuck/blindstone/ChunkFilter.java`
- `src/main/java/com/evanchubbuck/blindstone/ConcealmentPolicy.java`
- `src/main/java/com/evanchubbuck/blindstone/TerrainBoundary.java`
- `src/main/java/com/evanchubbuck/blindstone/config/BlindstoneConfig.java`
- `src/main/java/com/evanchubbuck/blindstone/config/ConfigCodec.java`
- `src/main/java/com/evanchubbuck/blindstone/mixin/LevelChunkMixin.java`
- `src/main/java/com/evanchubbuck/blindstone/network/EntityPolicy.java`
- `src/main/java/com/evanchubbuck/blindstone/network/FilteredLight.java`
- `src/main/java/com/evanchubbuck/blindstone/network/PacketFilter.java`
- `src/main/java/com/evanchubbuck/blindstone/runtime/BlindstoneRuntime.java`
