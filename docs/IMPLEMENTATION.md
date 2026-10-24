# Private-alpha implementation

Continuation implementation: `OrePolicy` is composed with `ConcealmentPolicy` and uses the same chunk, update, entity/event and lighting authorization paths. Sessions retain a dimension-specific immutable policy. Nether uses netherrack for concealed targets and keeps cave air real. Light snapshots cache detached terrain-boundary tables, section masks and ore-presence flags, avoiding per-cell weak-map and target-set work in ordinary rock sections. Memory reservation follows the actual reconstruction band. See [acceptance repair evidence](ACCEPTANCE_REPAIR.md).

`0.1.0-alpha.1` permits normal dedicated-server startup and ordinary player admission after mandatory transformed hooks and configuration validate. Isolated `blindstone.development` runs keep admission closed. The alpha's runnable pipeline and its acceptance evidence are separate; [TEST_REPORT.md](TEST_REPORT.md) records observed results.

## World views and visibility

Below Y=64, protected cells have no public-exterior exception. Above it, `TerrainBoundary` caches geological column heights and directional geological horizons alongside the narrow-hole closing. The lowest of four cardinal horizons encloses broad sky openings while an open valley axis or cliff face remains exterior. Live chunks consult up to sixteen blocks beyond each column, including loaded neighboring chunks; unknown neighbors are opaque. Public cells are copied directly and never merged into private exploration masks. Surface eyes cannot start interior sight; accepted interior eyes and body safety continue to authorize per-viewer cells. The same coordinate-aware policy controls chunk data, block entities, updates/events, non-player entities and filtered light.

The geological anchors are Overworld base stone/dirt/sand/terracotta tags plus grass, stone/deepslate, gravel, snow blocks, packed ice and bedrock. Trees, wooden structures, water and glass are excluded as anchors. On mutation, only the changed column becomes dirty; its next use scans at most one world-height column and invalidates the neighboring horizon tables and recomputes bounded 256-column tables on demand. Chunk load/unload invalidates neighboring tables too. Weak chunk keys prevent retention or reuse across unload/reload. No per-tick global scan occurs.

Seven-cell enclosed openings, an eight-cell loaded chunk-seam opening, seam mutations, and eight-cell-wide open valley/cliff controls are covered by serialized packet tests. This remains a bounded geometric classifier: enclosed natural depressions can be treated as interiors, openings beyond the sampled horizons and unusual diagonal/curved terrain need additional coverage, custom geology may need anchor support, and geological surface construction can act as a roof. It is not a semantic detector of player bases. A sealed interior beneath a recognized roof stays hidden independently of connected air.

Safety availability results are cached by the exact accepted player/vehicle body path. Tracking, chunk availability, reload/reset and session changes invalidate relevant safety state. A stationary viewer performs no repeated safety capture. Completed sight jobs remain retained; movement of two blocks, body size changes or intersecting geometry changes rebase them. The cache does not authorize neighboring sections or other viewers.

`ChunkFilter` creates detached per-viewer sections and rebuilds palettes, non-air/fluid counts, initial block-entity entries and client heightmaps. It never installs fake states in live chunks or changes saved terrain, inventories, collisions, generation or mob behavior.

Fully concealed sections use a single replacement-state palette without a 4096-cell rebuild; fully authorized sections copy the source. Partial masks start with uniform replacement and write only authorized indices; hidden source palette entries are never copied.

`GeometrySnapshot` captures loaded cells and shapes on the owning server thread. Missing cells are opaque. Resumable visibility jobs test individual candidates in every direction using clear paths and first surfaces. Third-person camera offsets must themselves be reachable without crossing geometry. Fixed candidate samples and finite camera offsets are conservative coverage choices; smooth rendering through every tiny opening still needs client validation.

Before sorting candidates, visibility jobs conservatively prune cells disconnected from the eye clearance region by full sight cubes. Solid boundary faces remain candidates; partial shapes remain traversable for this prefilter. Only the existing exact rays authorize a reveal. The flood is bounded by the immutable snapshot and never shares viewer knowledge.

Viewer state separates pending authorization from delivered masks. Chunk/session revisions prevent replaced work, geometry changes, unloads, teleports, dimension changes and configuration changes from authorizing stale output. Knowledge accumulates within a tracking session and is recomputed after unload.

The accepted body, supported root-vehicle body and swept path receive a fixed 1/16-block safety margin. The straight sweep between accepted endpoints uses the same margin at any supported speed; it does not reconstruct intermediate axis-resolved movement bends. Bounds on bodies, path distance and scanned cells fail closed when exceeded. A body cell may expose information just across a wall; this is the documented movement-safety exception.

## Lighting and coherent delivery

`LightScheduler` captures filtered states cooperatively, reconstructs sky/block light and caches output by viewer/chunk generation. Equivalent immutable policies and exact private masks may share work/results; different private masks cannot. Keys include loaded chunk identities and revisions across the light halo and its terrain-horizon dependencies. The last viewer reset releases all shared allocations; a bounded 64-entry/16-MiB idle result cache avoids repeat streaming reconstruction within the same 128-MiB global cap. Jobs use a 15-block horizontal halo, vanilla face occlusion and dampening, authorized emission only, and bounded duplicate-suppressed propagation queues. Missing neighboring chunks are opaque. Global light-job/cache accounting is capped at 128 MiB; active jobs and ready entries also have per-viewer caps.

Per-column slabs proven opaque, uniformly sunlit, or dark avoid repeated voxel scans; propagation queues allocate only when needed. Vanilla missing block-light layers are zero and missing sky layers inherit one horizontal plane. A non-emitting edit with zero filtered sky/block light in the edited cell and all six neighbors can reuse the settled light field only when the geological column height is unchanged. Geometry still gets serialized and delivered. Other edits rebuild generation-checked lighting. Chunk serialization also shares revision-invalidated immutable snapshots keyed by exact masks and fresh visible block-entity update tags, capped at 16 MiB.

A fully concealed neighborhood has analytically solid protected sections, so initial lighting solves only the public boundary band. If any protected section is authorized in the halo, the computed region extends down to its lowest authorized section. Outside-world air is included where needed at build boundaries.

Upper real light is copied only for complete sections at **Y≥the section-rounded highest potentially private boundary in the captured halo +16**. Every upward light step loses at least one level and vanilla light levels are at most 15. Hidden blocks and sources below that coordinate-aware boundary therefore cannot influence that upper region. Unattenuated direct skylight travels downward, so it does not invalidate this bound. Safe upper-boundary light seeds the reconstructed lower band, preserving ordinary sunlight and mountain shadows without copying hidden-derived light into protected space.

Chunk batches wait for matching light and frozen masks. New desired visibility is coalesced behind an in-progress plan rather than restarting it every tick. Geometry changes invalidate relevant jobs, neighboring terrain horizons and dependent light halos. Publication and intercepted/filter packet work debit the same runtime work allowance as reconstruction. Exterior sight excludes non-ore candidates before tracing; regions without nearby ore targets avoid sight capture until movement or invalidation requires a new check. Body safety may authorize additional cells immediately; publishing a frozen plan unions already-delivered body masks so a resend cannot restore fake walls over them. Additional disclosure can leave transient conservative under-lighting until the next matching light refresh, similar to an asynchronous light update; it cannot introduce a concealed source.

## Packet, tracking and lifecycle boundary

The mandatory wrapper at `Connection.sendPacket` covers listener sends, direct sends and queued actions before event-loop dispatch. It preserves completion callbacks and flush behavior. Protected-world access stays on the server thread; transport and player chat have an explicit non-world policy.

Per-viewer entity pairing is refreshed even without section movement. Complete sight decisions persist across ticks, keyed by entity UUID, accepted eyes and bounding box. Relevant horizontal geometry/chunk changes and disclosure/lifecycle changes invalidate them; exhausted/incomplete decisions are retried. Field adapters validate non-player spawn identity and type, metadata fields, references and compound positions. Player tracking remains normal. Hidden entities are not instantiated merely to play ordinary audible cues; those sounds become positional packets.

Actual block mutations, chunk load/unload and full-chunk accessibility changes invalidate affected geometry and light. Teleports, respawns, dimensions and disconnects reset or rebuild the relevant viewer state. Reload validates a complete candidate before replacing configuration and invalidates work coherently. Console-only disable warns first, then disconnects current viewers so reconnecting clients receive an explicitly unfiltered world.

Startup forces required target classes to transform and verifies their marker/accessor interfaces. Required mixins use `defaultRequire=1`. Missing hooks prevent normal activation/admission; later failures stop affected delivery instead of falling back to real concealed data. Marker presence is readiness evidence, not proof of gameplay or compatibility.

## Acceptance work

- Exercise ordinary graphical clients, third-person cameras, tiny openings, mining, doors, fluids, pistons, building, combat, vehicles, fast travel and dimension/respawn/rejoin flows.
- Capture decoded views for two independent clients and adversarial minimap/X-ray/freecam/ESP clients; compare controlled present/absent profiler fixtures.
- Validate supported entity classes and compound visual payloads against actual client behavior, including uncommon adapters and relationship transitions.
- Benchmark named player/chunk/entity loads and publish first-chunk delay, reveal latency, memory use and tick percentiles. The default 5 ms instrumented-work guard is not a hard whole-server tick deadline.
- Validate each optimization/alternate-send stack explicitly. Arbitrary payload schemas and direct channel writes are not covered by a vanilla class inventory.

These are public-release acceptance gates. They do not require keeping the private alpha's ordinary startup disabled.
