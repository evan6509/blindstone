# Development pipeline test report

**Date:** October 16, 2026. **Artifact:** `blindstone-0.1.0-dev.2+mc26.3.jar`. **Public activation/admission:** refused; development mode denies real logins.

## Confirmed local evidence

| Check | Result |
| --- | --- |
| Clean build and running Fabric server tests | PASS — `./gradlew --offline --no-daemon clean build runGametest` |
| Blindstone tests | **75 passed**, zero failures/errors. XML also contains vanilla's one `always_pass`, for 76 total |
| Original chunk/mask/state tests | 18 passed |
| Geometry/resumable visibility tests | 17 passed |
| Packet-policy/codec tests | 17 passed |
| Filtered-light reference solver tests | 9 passed |
| Configuration/diagnostic tests | 8 passed |
| Transformed runtime/Connection tests | 6 passed |
| Final artifact verification | PASS — exact pinned dependencies, server metadata, Java 25 bytecode, mandatory admission/send/lifecycle/tracker hooks and no bundled test classes |
| Normal server startup | Expected refusal during mod initialization while release gates are incomplete |
| Development real-player admission | Transformed `PlayerList.canPlayerLogin` rejects admission |
| GitHub CI | Configuration present; current remote execution not checked in this checkpoint |

Toolchain: Minecraft 26.3 (unobfuscated names), Loader 0.19.5, Fabric API 0.163.0+26.3, Loom 1.17.14, Gradle 9.6.1, Homebrew OpenJDK 25.0.4.1 on macOS ARM64. No optimization mods or connected ordinary/adversarial clients were used.

Artifact SHA-256: `977599a7ad8a2ffa653b9b31944acb932f2a7eb5c0502687d233864fa5c910f5`.

Results: `build/gametest-results.xml`; full generated logs/run directories are ignored. The final resource/artifact build was reverified after removing duplicate reload-error counting; the preceding complete suite covered the same filtering and runtime implementation.

## Evidence boundaries

**Chunk/mask/state:** Sealed-room and corresponding solid terrain produce identical filtered chunk-data payloads. Authorized blocks/air and matching block entities restore individually. Counts/palettes/heightmaps agree with fake geometry. Negative coordinates, independent viewers, invalid bounds, stale/replaced/replayed plans, unload/reload, reset and bounded tracking are tested. Filtering a loaded real chunk preserves serialized state, object references and its unsaved flag during the call. This is narrower than complete saved-world/simulation equivalence over a long run.

**Geometry:** Tests cover sealed walls, all directions, individual range limits, glass/panes, water/lava, doors, slabs, powder snow, conservative piston halos, camera collision clearance, immutable snapshots/masks, unavailable cells, fixed body/swept-path safety, invalid motion, and primitive-work ceilings. Resumable masks match uninterrupted evaluation; even a one-unit slice progresses. Entity point-sight follows the same opacity policy. A retained runtime job survives a remote block event and is invalidated by a local/halo event.

**Packet policies:** Real vanilla stream codecs test block/section updates, block entities, full chunks, isolated light masks, nested bundles, spawn validation/ordering, metadata allowlists, damage/reference stripping, removals, embedded movement paths and positional sound conversion. Source packets/shared chunk data remain unchanged. Sound assertions inspect encoded eighth-block integers rather than assuming more precision from distant-coordinate float getters.

**Runtime hooks:** Netty `EmbeddedChannel` connections and actual `ServerGamePacketListenerImpl` instances exercise the transformed private Connection dispatch, listener/direct routes, recursive bundles, completion callbacks, tracking registration, unknown-payload disconnection and full-chunk replacement. A tiny-budget runtime fixture repeatedly refreshes visibility and verifies body-safety plus later sight block publications through that hook. This does not establish actual ordinary-client receipt or client rendering.

**Lighting:** The bounded reference solver propagates only filtered-state skylight/emission using vanilla opacity/face occlusion. Tests cover hidden-source equivalence, allowed torch propagation across sections/chunks, cave openings, glass/slab occlusion, attenuation distance, codec masks, immutable results and allocation/work/time refusal. A loaded-world fixture compares hidden real torch versus stone and restores the fixture afterward. Reconstruction is **not connected to outgoing delivery yet**; the active development packet policy remains all-dark. World-edge under-lighting is conservative and not a validated visual policy.

**Configuration/diagnostics:** Strict schema/duplicate-key checks, safe replacements, actual world bounds, numeric budgets, immutable state, atomic defaults, validation/invalidation/publication ordering, reload retention, server-thread confinement and sanitized reports are tested. The report accepts aggregate typed fields and omits player identities, coordinates, seeds and raw world payloads. Readiness categories remain separate; a loaded hook alone is not marked live-tested.

## Still required before enabling players

- Coherent scheduled/cached lighting and boundary refresh on every relevant mask/world change; incremental heightmap consistency.
- Complete type-specific entity spawn/metadata, passenger, projectile, item-stack and relative teleport adapters.
- Supported compound particles and vanilla/custom compatibility payload policies instead of blanket suppression/disconnection.
- Supported vehicles and fast movement, camera/coverage quality, interaction/combat and complete lifecycle/client-cache testing.
- Systematic hook-readiness/failure injection, overload behavior and coherent online configuration/disable transitions.
- Decoded network capture from two real clients, adversarial minimap/X-ray/freecam/ESP and exact client profiler comparisons.
- Representative SMP CPU/tick/reveal-delay/network/memory measurements and every advertised optimization stack.

No p95 performance, gameplay quality, client-profiler resistance, production protection or cross-version compatibility claim follows from these passing automated tests. The source packet inventory and [implementation roadmap](IMPLEMENTATION.md) retain the remaining release gates explicitly.
