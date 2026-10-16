# Development pipeline status

This checkpoint extends the isolated chunk-data spike into a closed-admission server pipeline. Normal initialization still refuses activation; `-Dblindstone.development=true` permits GameTests/console development, with mandatory login denial. It is not a public protection release.

## Implemented

- `ChunkFilter` creates detached per-viewer block/air sections with rebuilt palettes, non-air/fluid counts, initial block-entity entries and heightmaps. World blocks, saved chunks and simulation are never replaced by fake terrain.
- `GeometrySnapshot` freezes loaded-only block shapes and cells. Missing cells are opaque; world capture stays on the owning server thread.
- `VisibilityEvaluator` authorizes individual cells with clear paths, first surfaces and fixed samples. It tests all directions, transparent/partial blocks, water/lava, doors, powder snow and conservative moving-piston geometry. Camera offsets require both sight and collision clearance.
- Resumable sight jobs retain camera, sample, voxel and shape-box cursors across work slices. Even a one-unit work budget makes progress. Geometry changes invalidate intersecting snapshots and their one-block shape halo; remote activity does not restart unrelated scans.
- Movement safety reveals cells intersecting the accepted body expanded by exactly 1/16 block and its swept path. Movement segments above 1.5 blocks, unsupported body sizes and mounted travel fail closed. This small body envelope is the explicit through-wall disclosure; it never expands under load.
- `ViewerState` separates queued authorization from delivered masks. Runtime tracking, accepted position/respawn/configuration transitions, unloads and geometry changes invalidate work. Same-dimension teleports retain subscriptions with fresh masks/ticket owners; entity IDs remain known until removal so transitions can remove already spawned entities.
- A mandatory `Connection.sendPacket` wrapper handles listener sends, direct sends and pending actions before event-loop dispatch. It preserves callback/flush behavior and constructs isolated output. World packets from worker threads and unknown payload routes fail closed.
- The packet policy handles full chunk data, block/section corrections, initial/later block entities, recursive bundles, secondary events, entity references, selected metadata, movement paths and ordinary sound conversion. Its deliberate visual/gameplay restrictions are listed below and in the coverage document.
- `ChunkMap` pairing includes per-viewer visibility. Bounded rotating refresh handles visibility changes without section movement; tracker failures disconnect an affected viewer instead of escaping through the world tick.
- Strict immutable configuration validates dimensions, bounds, required air/entity concealment, replacements and budgets. Default creation/report writes use atomic moves. Reload validates before publication and currently refuses active viewers.
- Local status/verify/inspect/reload/report commands expose sanitized counters and separate loaded, observed, filtered, enforced, automated and live evidence. There is no bypass from operator status and no telemetry/updater.

## Lighting work

`network.FilteredLight` reconstructs sky and block light from filtered states, with a 15-block horizontal halo, unknown opaque cells, vanilla face occlusion/dampening, and explicit allocation/work/time budgets. It does not copy real light arrays or read concealed states. Its isolated solver/codec tests are separate from the packet pipeline.

The outgoing packet policy still uses **constant darkness in every light section**, including above the protection cutoff, to avoid hidden-source boundary leaks. The reconstructed solver is not yet integrated: its cost requires resumable scheduling, per-viewer caching/invalidation, neighboring-chunk consistency and coherent publication with block reveals. Combining real light with filtered blocks is not an acceptable fallback.

## Remaining gameplay and release blockers

1. Integrate bounded reconstructed lighting and measure the delay/cost of first chunks and reveal updates. Complete client heightmap updates during incremental reveals.
2. Complete type-specific entity spawn data and metadata adapters. Current integer/reference/item-stack/particle/complex metadata restrictions break normal non-player appearance/behavior; neutral spawn data affects frames, falling blocks and projectiles. Relative non-player teleport reconstruction and mixed passenger transitions still need full support.
3. Complete compound particle adapters, supported vanilla debug/waypoint/query policies and compatibility payloads. Unknown custom packets currently disconnect; ordinary sounds intentionally remain location clues.
4. Validate/extend diagonal third-person views and tiny openings. Camera positions currently sample six axis offsets; visibility samples center plus six face centers. These are conservative coverage limits, not evidence of smooth live rendering.
5. Add supported vehicle envelopes and fast-travel destination handling, coherent online configuration changes and console-only manual disable/refresh. No disable/unfiltered fallback is provided at this development stage.
6. Harden systematic hook-readiness and recovery classification. The systemic-failure latch exists; fault injection and end-to-end coverage must prove each required route and recovery behavior.
7. Capture decoded outgoing traffic for two real clients. Test ordinary clients, minimaps, X-ray/freecam/ESP, profiler comparisons, movement, mining, combat, life-cycle transitions and failure scenarios from the specification.
8. Benchmark an explicit SMP load and advertised optimization stacks. The provisional 5 ms work target is checked at safe stage/packet boundaries; capture, shape construction and one bounded solver step can overshoot internally. No latency, tick percentile, memory-capacity or compatibility claim is established.

Keep player admission blocked until the normal-play policies and evidence gates are complete. Player ESP, already downloaded terrain, normal audible activity, surface clues and seed information remain outside the intended concealment guarantee.
