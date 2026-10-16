# Implementation status and next milestones

## Integration spike: implemented core, incomplete interception

The repository began empty apart from its Git workflow. This checkpoint establishes the 26.3 development environment and isolates serialization before connecting a partial implementation to clients.

`ChunkFilter.snapshot` accepts a server-owned `LevelChunk`, validated section-aligned Overworld bounds, and immutable per-section authorization. It creates an unregistered temporary chunk and serializes `ClientboundLevelChunkPacketData`. Protected sections start with a fresh block palette containing an actual output state; concealed source palette entries are never copied. Section constructors recalculate the non-air and fluid counts that 26.3 sends. Biome containers are copied, authorized initial block entities are read on the server thread, and all heightmaps are primed against filtered states. The temporary chunk is not registered, saved, or ticked. No fake-world simulation is introduced.

The return value excludes lighting. It must not be combined with real lighting and advertised as secure. It is currently called only by tests, and does not replace any live vanilla send path.

`ViewerState` tracks a configured maximum number of chunks (1–4096) across a configured section range (at most 64 sections). Masks use at most 4096 bits each. There is one pending plan per chunk; replacement discards the previous pending authorization. Saturation refuses new tracking. There is no automatic eviction or visibility expansion. The integration caller must bound the number of viewers globally, geometry jobs, snapshot sizes, and work per tick before connecting this to gameplay.

Each reveal plan is bound to its originating tracked-chunk object, revision, and pending-plan identity. A new tracking session cannot revive an old plan even at the same coordinate. `geometryChanged` invalidates pending work and retains already delivered masks. `reset` drops all active knowledge and must eventually be wired to teleport, respawn, disconnect, dimension, and configuration transitions. These methods are tested in isolation; event wiring does not exist yet.

Queuing a plan does not authorize routine updates. The future sender must check `isCurrent` immediately before publication, send blocks before block entities, and call `markDelivered` only after coherent delivery. Server-thread confinement is required for mutable viewer state. Incoming interaction coordinates never directly feed authorization in this checkpoint.

Normal initialization throws an actionable missing-hooks error. Explicit development mode permits a console and GameTests, with a mandatory `PlayerList.canPlayerLogin` mixin denying all real logins. Source inspection confirms vanilla calls that method before completing login. The transformed method is also tested. This gate is temporary scaffolding to prevent an incomplete prototype from appearing to provide protection.

## Next: playable reveal prototype

1. Introduce immutable geometry snapshots and a bounded visibility evaluator using server-accepted player positions, clear-path tests, first visible surfaces, and per-block masks. Define transparent/partial/fluid behavior and test camera offsets, doors, and sealed adjacent rooms.
2. Define and test the exact movement safety envelope and overload deadline. Do not increase its disclosure under load. Disconnect when bounded safe movement delivery cannot be maintained.
3. Implement coherent filtered lighting, including cross-section and neighboring-chunk boundaries. The isolated chunk snapshot alone cannot safely enter a live send path.
4. Integrate viewer lifecycle and geometry invalidation, covering accepted mining, support-block corrections, failed interactions, pistons, explosions, and fluid/light changes.
5. Intercept and rewrite the actual per-recipient chunk and subsequent-update paths. Preserve shared packet isolation, callbacks, ordering, and bundle behavior. Audit both listener sends and direct `Connection` sends before allowing developer test clients.
6. Capture decoded outgoing data for two clients and measure reveal latency and tick work before expanding the security surface.

## Then: full vanilla coverage and private trial

Complete non-player entity tracking (spawn/removal, metadata, movement, equipment, passenger/leash references), secondary events, sounds, diagnostics/configuration, runtime recovery, and admission readiness checks. Inventory exceptions explicitly; defaults retain ordinary sound clues, while player tracking is outside v1 protection.

Build controlled live fixtures from the specification. Record adversarial client/profiler results, movement/interaction behavior, world integrity, failure injection, compatibility, and representative SMP performance separately from automated code/codec tests. Every missing required path remains a public release blocker.

Do not replace the per-block design with whole-chunk reveals, a large through-wall bubble, or ore-only hiding to meet a performance target. Revisit the policy explicitly if measurements require a different disclosure tradeoff.
