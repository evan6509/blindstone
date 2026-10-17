# Minecraft 26.3 private-alpha packet coverage

Normal admission opens after required hooks and configuration validate; isolated development admission stays closed. The table describes implemented policies and remaining validation, not a completed public-release acceptance report. The [lexical class index](vanilla-packet-inventory.json) lists 153 outgoing classes and static policy routes; it does not establish runtime observation.

The mandatory `Connection.sendPacket` wrapper runs before the event-loop hop. Listener sends, direct sends and queued actions reach this boundary. Transport/keepalive/disconnect and player chat are intentionally allowed. Every other PLAY send requires the owning server thread, including outside the Overworld; the owner check uses immutable listener ownership before reading player/world state. Unsupported protected-PLAY schemas fail closed. Pre-PLAY protocol states and non-Overworld worlds are outside underground filtering, subject to that thread restriction. Arbitrary direct Netty writes and replacement routes need explicit integration work.

| Path | Private-alpha policy | Remaining validation/limit |
| --- | --- | --- |
| Initial chunks/resends/batches | Detached viewer-specific states, palettes, counts, block entities and heightmaps; batches wait for matching generated light and masks | Ordinary graphical batch/loading behavior and representative first-chunk delay |
| Single/section block updates | Real states only at delivered/actively publishing authorized cells; replacement elsewhere; input packets unchanged | Mining, doors, pistons, fluids and correction-path gameplay |
| Initial/later block entities | Concealed entries absent; authorized blocks precede entity updates | Representative content fixtures and exact profiler present/absent comparison |
| Chunk light and later light | Cooperative filtered-state sky/block reconstruction, 15-block halo, generation caches, neighbor invalidation and coherent chunk publication | SMP latency/throughput; unknown neighbors conservatively opaque; transient under-lighting after immediate body reveal |
| Safe upper light | Real light retained only at Y≥protectedMaxY+16, beyond maximum upward influence from protected blocks/sources | Validate extreme-height and optimization-stack behavior; bound documented in implementation |
| Non-player spawn/tracking | Geometric pairing, known-spawn state, authoritative ID/type/UUID checks and explicit vanilla spawn-field adapters | Uncommon entities, combat, transitions and actual client appearance |
| Player entities | Normal player spawn/metadata/reference behavior | Player ESP deliberately outside concealment |
| Metadata | Actual vanilla class/field/serializer schema checked; ordinary item/name/health/variant/pose data retained; hidden entity IDs, block/global positions and particle destinations sanitized | Non-vanilla entities/serializers unsupported; visible authored/item data remains legitimate client information |
| Movement/equipment/effects | Previously spawned visible entities only; embedded position paths checked; relative non-player teleports rebuilt from accepted server coordinates | Interpolation, projectiles and vehicle client behavior |
| Passenger/leash/pickup/damage references | Hidden IDs omitted, passenger lists rebuilt, hidden leash links cleared, concealed damage causes/positions stripped | Live relationship transitions and combat cues |
| Particles/events/break animations | Concealed sources suppressed; vanilla option adapters validate embedded vibration/trail destinations and entity references | Rare visuals and modded option schemas; effect suppression can change rendering |
| Explosions | Recipient knockback retained; hidden aggregate block payload removed; concealed visual source neutralized; ordinary sound retained positionally | Filtered visual appearance and real client knockback behavior |
| Sounds | Ordinary positional cues allowed; hidden entity-attached sounds converted without spawning/reference leakage | Audible coordinates intentionally remain a discovery route |
| Bundles | Recursive filtering in order, with tracking/lifecycle bookkeeping; delimiters generated later by codec unpacking | Direct delimiter sends remain rejected; graphical bundle behavior and compound fault injection |
| Debug/query/waypoint/look-at | Explicit unsupported paths suppressed | Some optional vanilla diagnostics/locator or command-driven effects unavailable |
| Common/custom payloads | Vanilla brand, Fabric registration/version negotiation and explicit safe common packets allowed; unknown custom gameplay payloads disconnect | Other mod/plugin integrations need schema audits |
| Player post effects | Empty and nonempty identifier lists allowed; vanilla placement, player tick, respawn and dimension-transition sends supported | Graphical shader availability/rendering unverified |
| Resource packs / server metadata | Push/pop, report detail strings, server links, clear-dialog and transfer endpoint packets explicitly allowed | Pack loading, dialog showing and actual destination/proxy traversal are separate; see the schema audit |
| Cookies / show dialog | Explicit unsupported protected-PLAY disconnect with failed completion | Opaque cookie and nested dialog/action content need schema support; no suppression of a required response flow |
| Completion / flush | Suppression and intercepted light retain previous writes' flush; deferred callbacks complete on publication, cancellation or failure | Tested success, suppression, exception, disconnect and reload; arbitrary asynchronous world delivery remains unsupported |
| Lifecycle/geometry | Real mutations, load/unload/accessibility changes, respawn/teleport/world/reset and disconnect invalidate relevant jobs/state | Full ordinary-client transition acceptance matrix |
| Reload/manual disable | Candidate validates before publication; incompatible active-world bound changes rejected; actual console can explicitly disable with confirmation and reconnect refresh | Operational acceptance under active load |
| Readiness/failure/overload | Mandatory target transformation checks, no unfiltered fallback, bounded queues/jobs/cache/masks, affected-viewer disconnect and systemic failure latch | Systematic fault injection and published service limits |

The [integration audit](COMPATIBILITY.md) records exact schemas, originating send paths, client handling, baseline Fabric payloads and intentional limitations. Controlled embedded/PLAY-codec tests and the installed-JAR offline admission harness provide separate evidence from authenticated/proxy traversal, a graphical client, adversarial/profiler comparisons or an SMP benchmark. Consult [TEST_REPORT.md](TEST_REPORT.md).

## Inventory

```sh
./gradlew --no-daemon genSources
python3 scripts/inventory_packets.py
```

The inventory contains names/reference locations without redistributing Minecraft source. Inherited routes, callback timing and payload fields still require manual review. Source-specific details include separate non-air/fluid section counts and a record chunk-with-light codec. The 26.3 explosion payload contains center/radius/block count/knockback/particles/sound rather than a historical destroyed-position list. The vanilla deepslate gradient spans Y0–Y8; the replacement policy intentionally uses a stable step at Y0.
