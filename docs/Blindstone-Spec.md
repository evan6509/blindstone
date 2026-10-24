Implementation update (2026-10-24): bounded cardinal terrain horizons supplement narrow-hole closing; public exterior remains separate from per-viewer discovery. Common `#c:ores` resolves by default. Native Nether ore/update/light coverage and production-path range-16 acceptance gates are included. See [repair evidence and classifier limits](ACCEPTANCE_REPAIR.md); this remains deterministic filtering, not a full AntiXray engine-mode port.

# Blindstone

Development scope update: independent deterministic ore targets now supplement the Overworld mountain policy, with a separate Nether ore-only policy. The packet authorization rule is private discovery or a non-target public state. Exterior sight may authorize targeted ore, never connected room geometry. No random decoy engines or operator bypass are implemented. See [the unaccepted continuation checkpoint](ORE_PERFORMANCE_CHECKPOINT.md); older version-1 dimension statements below describe the earlier scope.

## Fabric hidden-base protection mod — product and engineering specification

**Status:** Implemented private-alpha anti-base policy; automated evidence and outstanding live acceptance are recorded separately.  
**Date:** October 24, 2026  
**Mod ID:** `blindstone`  
**Pinned target:** Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.163.0+26.3, Java 25.  
**Client requirement:** None; ordinary clients must be able to connect.

## 1. Purpose

Blindstone makes undiscovered underground bases harder to find using cave minimaps, X-ray, freecam, entity/block-entity ESP, and client profiler techniques commonly called pie-chart searching.

The server keeps the real world. Each player receives a filtered view in which concealed underground blocks, including air pockets, appear as ordinary solid terrain. Real information is sent as the player legitimately approaches or exposes an area under the reveal policy below.

**Product promise:** Reduce information available to clients about undiscovered underground areas while preserving normal exploration, mining, and building.

Blindstone must protect empty rooms as well as valuable blocks. Merely replacing chests or ores while leaving room geometry visible does not meet this specification.

## 2. Scope and exclusions

### Required for the first public release

- Server-side Fabric installation with no mandatory client mod or custom client handshake.
- Per-player underground block and air concealment.
- Ore concealment within the protected area, using the same visibility system.
- Matching concealment of block entities and selected non-player entities.
- Protection of subsequent updates, not just initial chunk delivery.
- Concealed lighting and audited secondary packet paths.
- Predictable reveal behavior, bounded work, diagnostics, and a published test report.
- Existing-world support without regenerating chunks or changing saved terrain.

### Explicitly excluded

- World-seed protection, seed spoofing, seed randomization, and seed-cracker countermeasures.
- Changes to terrain generation, biome selection, structure placement, ores, or dimension seeds.
- Combat/movement anti-cheat, cheat detection, account sanctions, or client mod bans.
- Guaranteed concealment of surface bases, visible entrances, or terrain outside configured protection bounds.
- Player ESP protection in version 1. Player tracking remains normal and must be listed as a remaining discovery route.
- Claims/ownership systems, trusted-base-member lists, and automatic identification of player-built bases.
- Nether/End support, cross-version compatibility, and a Paper port in version 1.
- Guaranteed protection from arbitrary modded payloads or every possible timing side channel.

The release may be all-in-one for the specified underground concealment functions. It must not be advertised as complete server security or seed protection.

## 3. Threat model and limits

Assume an untrusted client can inspect, retain, and analyze every packet it receives; move a local freecam; render through walls; and ignore cooperative minimap settings. The server is authoritative and uncompromised.

The primary adversary is a player who has not visited a hidden base and is scanning normally delivered chunks for rooms, tunnels, storage, villagers, armor stands, or related activity.

Known limits must be communicated plainly:

1. Previously revealed data cannot be taken back from a modified client. Re-concealment can stop fresh updates, but cannot erase a downloaded map.
2. Players can still discover bases through exploration, surface clues, following other players, or information shared by someone who visited.
3. Legitimately audible nearby activity remains a clue under the default sound policy.
4. Biomes and visible terrain remain available. Blindstone makes no seed-secrecy claim.
5. Protection applies only inside configured dimensions and vertical bounds.
6. A minimap may record legitimately explored terrain. Blindstone does not remotely disable mapping or the client profiler.

Cooperative minimap fair-play flags may be considered later, but are never the security boundary.

## 4. User experience

An ordinary player should explore caves, mine, open doors, ride supported vehicles, and enter bases without walking into persistent fake walls or seeing concealed entities attack while invisible.

An unauthorized client above a sealed base should receive ordinary stone/deepslate instead of the room, its contents, and concealed entities. The player must not receive an identifiable fake patch placed only around a base: the same concealment policy applies to natural caves and ordinary hidden terrain throughout the protected area.

No claims or owner setup are required. Installing Blindstone on an existing world protects future eligible deliveries; it cannot undo earlier downloads.

## 5. Concealment policy

### 5.1 Protected area

Version 1 targets the Overworld only. Protection is defined by dimension ID and an inclusive minimum/exclusive maximum Y range.

The default exclusive cap is Y=320. Below Y=64 all undiscovered cells remain concealed; above it, a cached geological exterior boundary distinguishes public mountain surface from private interior. Schema-1 default cap 64 migrates to 320; custom caps remain intentional limits. This foundation remains subject to the opening/terrain limitations in IMPLEMENTATION.md.

Use world minimum height for the lower bound. Configuration must show the effective range in status output and reject invalid dimension/block identifiers or reversed bounds.

### 5.2 Replacement terrain

Concealed blocks include normal air, cave air, fluids, ores, player-built materials, redstone, storage blocks, and other block states inside the protected area. Air concealment is mandatory in the normal protection preset.

Use a stable replacement based on dimension and Y: stone above the ordinary deepslate transition and deepslate below it. The exact transition must be checked against the target version. Administrators may choose another simple opaque full block, but luminous blocks, block entities, fluids, and special-interaction blocks are invalid replacements in version 1.

Replacement choices must not depend on whether the real location contains a base, an ore, a cave, or a chest. Avoid rare-block noise and fake valuables.

Serialized palettes, counts, section metadata, light data, and heightmaps must agree with the filtered representation wherever they would otherwise expose concealed content. A full fake section must not retain a real cave's non-air count or a concealed chest's palette entry.

### 5.3 Per-player state

Maintain independent visibility for each viewer. Revealing a room to one player must not reveal it to everyone else. Permission to administer Blindstone does not itself grant a viewing bypass.

For each tracked chunk, distinguish:

- **Concealed:** Real data has not been authorized for this viewer.
- **Reveal queued:** A fresh visibility result authorizes specific data; delivery is pending.
- **Revealed:** The corresponding real data has been sent.
- **Untracked:** The chunk is no longer tracked; active visibility and queued work are discarded.

Use a chunk/session revision to reject work calculated before block changes, teleportation, disconnect, dimension changes, or configuration changes.

### 5.4 Reveal rules

All interior cells start concealed. Below Y=64 there is no public exterior exception. Above it, public exterior classification is independent of private exploration and does not flood into caves. Narrow shaft air below the closed boundary stays concealed. Low valleys and hidden cave interiors can appear filled until explored.

Only accepted eyes inside protected terrain start private sight. Accepted body/vehicle cells and their fixed safety envelope remain immediate exceptions at the boundary. Sight jobs retain their immutable origin until movement of at least two blocks, body size change, intersecting geometry/chunk changes or lifecycle/configuration reset. Smaller movements receive immediate body safety, with sight refreshed at the documented rebase threshold. Completed stationary jobs do no repeat ray work. Knowledge is per viewer and accumulates only after publication.

Reveal decisions use the server-accepted player position and real server geometry. Never trust a client-reported camera location or freecam position.

- Evaluate visibility around the player in all directions to accommodate normal camera movement and third-person play; do not rely exclusively on view direction.
- Reveal reachable visible air and first visible block surfaces through clear paths. Merely sharing a chunk or a connected cave network is insufficient.
- Do not reveal an entire chunk, section, cave component, or base because one entrance or block becomes visible.
- Do not use an unconditional large distance bubble that exposes rooms through walls.
- Use per-block masks within chunk sections as the initial design. Coarser indexing may accelerate candidate selection, but must not authorize extra real blocks.
- Treat visibility as a conservative server-side geometric test. Sampled rays may discover candidates; an unobstructed test must justify each revealed candidate. Sampling can miss geometry and cause pop-in, so visual coverage must be tested separately.
- Account explicitly for transparent blocks, partial blocks, fluids, open doors, and third-person camera offsets. A camera offset must have a clear path from the player; it cannot be placed through a wall.
- Reveal a small safety envelope immediately around the player's body and accepted movement path to avoid trapping the client in fake solid blocks. The exact envelope and any through-wall disclosure must be documented and tested. It must not grow silently with server load or latency.
- An incoming request to interact with a hidden coordinate is not authorization to reveal it. Validate normal reach, obstruction, and action rules first.

On accepted mining, door changes, explosions, pistons, or other geometry changes, prioritize recalculation for affected viewers. Reveal newly exposed terrain promptly, without automatically exposing connected sealed rooms or ores behind intact walls.

Keep revealed block masks for the current tracking session to reduce flicker. Clear active masks on unload and recompute on reload. This design deliberately prioritizes protection of undiscovered areas; concealment cannot remove the player's prior knowledge.

## 6. Packet coverage contract

Implementation begins with a packet inventory for the exact target version. The categories below describe required behavior, not assumed API or packet class names.

| Path | Required first-release behavior |
| --- | --- |
| Initial chunk, full resend, chunk batch | Send only the viewer's filtered states, consistent section metadata, and permitted block entities. |
| Single/multiple block updates | Preserve fake terrain at concealed positions; do not forward a hidden real state after an unrelated change. |
| Initial and later block-entity data | Remove concealed entries entirely, including identifiers, coordinates, and data. Restore only after the corresponding block is authorized. |
| Light updates | Conceal light from hidden sources and sanitize affected boundaries; publish coherent light when terrain is revealed. |
| Entity creation and subsequent tracking | Withhold selected concealed entities and their metadata, equipment, movement, passengers, leash references, and other identifying references. |
| Particles, block events, break animations, positional world events | Suppress concealed remote sources unless an explicitly documented gameplay exception applies. |
| Explosions and other compound packets | Inspect all embedded positions/block lists; preserve necessary recipient gameplay effects without forwarding concealed coordinates. |
| Sounds | Apply the default audible-cue policy below; handle both positional and entity-attached sounds without introducing hidden entity references. |
| Bundles and modded/alternate send paths | Ensure supported vanilla payloads cannot bypass filtering through wrapping or a second send route. Unsupported custom payloads require an explicit compatibility limitation. |

Light filtering must consider light crossing into neighboring sections or revealed space. Zeroing a hidden section alone is insufficient if a concealed torch still creates a distinctive signal outside it. Any intentional visible light spill must be classified as revealed information and included in tests.

The implementation must publish a coverage table listing each actual packet path as implemented, tested, unsupported, or intentionally allowed. A missing required vanilla path is a release blocker.

## 7. Entity, profiler, and sound policy

### Non-player entities

Cover storage minecarts, item frames, armor stands, villagers, other mobs, and dropped items when concealed. Entity policy must use real visibility and a small safety range; publishing everything in a revealed chunk is insufficient.

Reveal entities before legitimate close interaction or combat. Prioritize moving entities and projectiles entering visible space. Preserve passenger relationships and avoid dangling references. Clear or rebuild viewer tracking on teleport, respawn, dimension change, and reveal transitions.

Player entities are excluded from version 1 concealment. Players inside bases may therefore remain discoverable using player ESP; this must be prominent in release notes.

### Pie-chart and profiler techniques

Blindstone cannot remove a client's profiler. Its objective is to withhold concealed entities and block entities that the client would otherwise instantiate and profile. Test the exact target client version with present/absent fixtures; do not infer success solely from an empty screen or a successful server boot.

### Sound defaults

Keep ordinary nearby audible cues within normal server delivery rules. A sound packet must not force a hidden entity to be spawned; where supported, use a positional sound instead. This preserves normal play but may reveal a source's approximate or exact location to a modified client.

A later optional strict sound mode may suppress concealed sources at the cost of changing legitimate gameplay. It is not a prerequisite for the first release, and the default must not be marketed as eliminating sound-based discovery.

## 8. Engineering constraints

Use narrow version-specific integration points for outgoing vanilla chunk serialization, updates, and entity tracking. Fabric custom networking alone must not be assumed to intercept all vanilla packet paths; verify actual call sites during the implementation spike.

- Never modify live world blocks, saved chunks, inventories, scheduled ticks, collisions, mob AI, or world generation to create the fake view.
- Never mutate a shared packet in place if it could be delivered to another viewer. Produce isolated viewer output.
- Keep world access on its owning server thread. Background work may process immutable snapshots with revision checks before publication.
- Serialize reveal transitions coherently: block before block entity; entity spawn before metadata; no fresh real update before authorization.
- Invalidate affected masks/caches on real geometry changes, including piston movement and explosions across chunk boundaries.
- Bound per-player memory, work queues, cache sizes, and computation. Prioritize movement and interaction over distant scanning.
- Do not reveal extra terrain when overloaded. Defer distant work; if movement-safe delivery cannot be maintained, disconnect affected viewers with a clear message.
- Use explicit integration checks for optimization mods that replace chunk or entity send paths. Do not advertise broad compatibility before tests.

Recommended internal responsibilities: configuration, geometry/visibility evaluation, viewer state, chunk filtering, update filtering, entity filtering, secondary event filtering, and diagnostics. Exact class structure is an implementation choice.

## 9. Failure behavior

Protection must not silently fall back to sending real concealed data.

- **Startup:** Invalid required configuration or missing mandatory hooks prevents activation and stops startup with an actionable error before player admission.
- **Runtime:** Drop an unsafe protected packet, invalidate affected work, and disconnect the affected viewer if a safe bounded recovery is unavailable. A systemic failure blocks protected-world admission until restart or repair.
- **Overload:** Keep the filtered representation and prioritize safe movement. Do not leave a player indefinitely in a frozen fake world.
- **Configuration reload:** Validate the complete replacement configuration first. Apply atomically and invalidate stale work. Reject changes that cannot be reconciled safely online.
- **Manual disable:** Console-only in version 1, explicitly warn that real world information will resume, then refresh affected clients coherently. Never disable protection automatically after an error.

Logs must identify the failing subsystem and distinguish loss of availability from a confirmed information leak. Do not report healthy protection merely because hooks loaded.

## 10. Configuration and administration

See CONFIGURATION.md for the validated schema and numeric defaults.

| Setting | Current behavior |
| --- | --- |
| Enabled dimensions | Overworld only initially; unsupported dimensions rejected if protection is requested. |
| Protected Y range | World minimum through Y<320 potential band; exterior boundary above Y=64 keeps public terrain real. |
| Replacement terrain | Stone/deepslate by Y; only validated opaque full blocks. |
| Air concealment | Enabled and required for the normal protection preset. |
| Reveal range and refresh interval | Bounded, documented, benchmarked; actual values chosen through movement tests. |
| Movement safety envelope | Small explicit bound; disclose its security tradeoff. |
| Non-player entity protection | Enabled for the supported categories. |
| Player concealment | Unavailable in version 1. |
| Sound policy | Preserve ordinary nearby sounds; disclose remaining clue. |
| Work/memory budgets | Bounded per viewer and globally; overload never grants visibility. |
| Failure policy | Startup refusal and affected-viewer disconnect; no automatic unfiltered fallback. |

Commands:

- `/blindstone status` — activation, versions, dimensions/bounds, active viewers, backlog, and current errors.
- `/blindstone verify` — separate hook readiness, observed packet paths, enforced filtering, and test evidence. Inactive/unobserved paths must remain labeled as such.
- `/blindstone inspect <player>` — viewer masks/queue summaries, bypass state, and recent filtering failures; restricted administrator output.
- `/blindstone reload` — validated configuration reload.
- `/blindstone report` — local sanitized diagnostics with configuration summary, timings, counters, and coverage state; omit identities, coordinates, seeds, and raw world data by default.

Version 1 may use vanilla operator permission levels. A permissions-provider dependency is unnecessary. Any bypass must be explicit, default off, and observable; ordinary operator status must not automatically bypass protection.

Do not include telemetry or an automatic updater in the first release. Diagnostics remain local unless the administrator chooses to share them.

## 11. Verification and acceptance

### Controlled fixtures

Create a dedicated test world with sealed bases, natural caves, and comparable solid terrain. Include chests, furnaces, hoppers, signs, villagers, armor stands, item frames, minecarts, redstone, light sources, fluids, doors, and rooms spanning chunk/section boundaries.

Capture decoded outgoing data for an unauthorized viewer and an authorized nearby viewer. Compare semantically equivalent filtered representations. Do not rely on screenshots alone, and do not require encrypted/compressed network streams to be byte-identical.

### Required scenarios

1. **Sealed base:** Outside reveal/safety bounds, no true room geometry, storage block data, selected entities, or hidden-only palette/count information is delivered.
2. **Air pockets:** An X-ray/cave view cannot distinguish the concealed room layout from ordinary filtered terrain.
3. **Ordinary cave:** Visible cave surfaces load correctly; seeing an entrance does not authorize the entire cave network.
4. **Mining and interaction:** Walls and doors open promptly; newly exposed blocks/entities appear; attempts through intact walls do not force reveals.
5. **Updates while concealed:** Chest changes, hopper operation, redstone, lighting, pistons, fluids, and explosions do not bypass the relevant packet policy.
6. **Independent viewers:** One player exploring a base does not reveal it to another unexposed player.
7. **Lifecycle:** Join, reconnect, chunk reload, teleport, respawn, dimension change, and configuration reload cannot reuse stale authorization or publish real chunks first.
8. **Movement:** Sprinting, falling, swimming, third-person views, and supported vehicles do not produce persistent fake obstructions or invisible close hazards. Test fast travel explicitly.
9. **Entity transitions:** Reveals, removals, passengers, moving mobs, and projectiles behave correctly with no dangling references.
10. **Profiler:** Compare the target client's profiler with and without concealed fixtures; document any remaining observable signal.
11. **Secondary paths:** Inspect particles, block events, positional effects, sounds, light boundaries, and compound packets. Record sound exceptions as allowed disclosures.
12. **Failure injection:** Serialization error, stale snapshot, missing hook, invalid config, and queue saturation never trigger automatic real-data fallback.
13. **World integrity:** Seed, generation settings, real blocks, entity state, and ordinary simulation remain unchanged by concealment; compare against an equivalent unfiltered run.
14. **Compatibility:** Test a plain Fabric stack first, then each advertised optimization/mod combination on the exact pinned version.

Use ordinary clients plus selected cave-minimap, X-ray/freecam, and ESP clients during live testing. Record exact client/mod versions and settings. A cooperative minimap obeying fair-play settings does not prove packet concealment.

### Performance evidence

The current deterministic before/after production-path benchmark is documented in PERFORMANCE.md; it is not a real SMP tick benchmark. Future live acceptance should benchmark identical scenes with Blindstone disabled and enabled, including idle users, simultaneous new-chunk exploration, fast movement, and active base updates. Record hardware, Java/settings, view distance, dimensions, player count, and all installed mods.

Report tick-time percentiles, filter CPU cost, reveal delay, memory per player, backlog, and network bytes. Initial engineering targets are p95 added tick work under 5 ms and p95 reveal delay under 100 ms at an explicitly chosen representative SMP load. These are provisional acceptance targets, not demonstrated capacity or fixed guarantees. Revise only with recorded evidence and an explicit scope/performance decision.

### Release gate

All required paths and scenarios must pass on the pinned server version. Publish separate evidence for automated checks, decoded packet inspection, live visual/gameplay testing, and performance measurements. A build, clean boot, or passing unit test suite alone does not establish protection.

## 12. Delivery plan

1. **Integration spike:** Confirm the target toolchain; inventory vanilla send paths; build an isolated filtered chunk/air prototype for one viewer; prove world data is unchanged.
2. **Playable reveal prototype:** Implement per-player masks, visibility, movement safety, chunk lifecycle, and update invalidation. Measure whether the block-level design is practical before expanding scope.
3. **Complete version 1 coverage:** Add block entities, selected non-player entities, lighting, secondary events, diagnostics, and failure handling.
4. **Private SMP trial:** Run packet captures, adversarial client tests, and representative load tests. Fix leaks and gameplay defects before broad distribution.
5. **Public version 1:** Ship a pinned Fabric build, configuration guide, coverage/limitations report, and reproducible test results.

If the visibility prototype cannot satisfy both gameplay and performance targets, revise the reveal policy and document the resulting disclosure before proceeding. Do not silently replace it with whole-chunk reveals or disable air concealment.

Later candidates include Nether/End policies, player concealment, strict sound suppression, and additional version support. Seed protection remains a separate project or independent optional integration, not a dependency for this release.

## 13. Reference material

- [ChunkVeil repository and documented protection model](https://github.com/DeKaeyman/ChunkVeil) — Paper/ProtocolLib reference for underground concealment, air masking, entity filtering, and secondary packets. Its documented behavior is background research, not proof of Blindstone's implementation or a commitment to reproduce its architecture.
- [Fabric networking documentation](https://docs.fabricmc.net/develop/networking) — background for networking and server/client responsibilities. Exact vanilla interception points must be verified in target-version source.

References checked October 16, 2026. If code is reused from another project, inspect the applicable license at the reused revision and preserve its required notices. Choice of Blindstone's own license remains open.
