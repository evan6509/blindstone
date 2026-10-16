# Minecraft 26.3 development packet coverage

**Protection/admission:** development only; real player logins remain denied. This table describes implemented policies and their limits, not first-release acceptance. The [class-reference index](vanilla-packet-inventory.json) inventories 153 outgoing classes (126 game-protocol classes) and labels static policy routes. Static classification does not establish observation or passing live tests.

The mandatory wrapper is at `Connection.sendPacket`, before the event-loop hop. Listener sends, direct `Connection.send` calls and queued pending actions reach this boundary. Transport/keepalive/disconnect and player chat are intentionally allowed. Other protected-world packets require the server thread; unsupported paths disconnect rather than forwarding unfiltered data. Pre-play protocol states and non-Overworld worlds are outside underground filtering. Arbitrary direct Netty writes or optimization mods replacing this path remain unsupported pending explicit integration tests.

| Path | Development implementation | Remaining release requirement |
| --- | --- | --- |
| Initial chunks/resends/batches | Isolated per-viewer section states, palettes, counts, initial block entities and heightmaps; batch wrappers allowed | Coherent reconstructed lighting, real-client batching tests, runtime cost measurement |
| Single/section block updates | Real state retained only at delivered/actively publishing authorized cells; fake terrain otherwise; input packets unchanged | Mining/door/piston/fluid gameplay and correction-path testing |
| Initial and later block-entity data | Hidden entries removed; reveal publishes blocks before entity update data | Exact client profiler comparison and representative block-entity fixtures |
| Light packets and chunk light | All sky/block arrays replaced by independent empty masks covering every light section, including outer slots | Current rendering is deliberately dark. `FilteredLight` reconstruction is isolated, not integrated into sends/reveals/caches |
| Non-player spawning/tracking | Geometric pairing, spawn-known state, authoritative entity/type/UUID checks, coordinate authorization, selected safe scalar metadata, known-ID removal | Full type adapters, legitimate combat/interaction and supported vehicles |
| Player entities | Normal player spawn data/metadata preserved; player references explicitly recognized | Player ESP remains excluded from v1 concealment |
| Equipment/attributes/effects/movement | Visible and previously spawned entities only; embedded absolute paths checked | Type-specific item/attribute payload audit and relative teleport bases; relative non-player teleports currently withheld |
| Passenger/leash/pickup/damage references | All required IDs checked; hidden damage sources/coordinates stripped; unknown removal IDs suppressed | Mixed passenger transitions, moving projectiles and live relationship reconstruction |
| Entity metadata/spawn fields | Explicit safe serializer allowlist for non-player flags, health, pose, rotations, directions and variants; non-player spawn data neutralized | Integer/entity references, item stacks, global/block positions, particles, transforms and type-dependent data need adapters; gameplay incomplete |
| Particles/events/break animations | Hidden sources suppressed; only simple audited particle options allowed; block events/positional effects require authorization | Compound option payloads, exact gameplay exceptions and live effect coverage |
| Explosions | Recipient knockback retained; aggregate block count/radius/block-particle data removed; hidden visual center neutralized; ordinary sound cue retained positionally | Correct filtered visual semantics and live explosion behavior |
| Sounds | Ordinary positional cues allowed; hidden entity sound converted to positional sound without spawning/reference disclosure | Exact delivery/range validation. Audible coordinates intentionally remain discovery clues |
| Bundles | Children recursively filtered, order maintained, bookkeeping includes nested tracking/lifecycle transitions | Actual client bundle behavior and compound failure injection |
| Debug/query/waypoint/look-at | Explicit unsupported paths suppressed | Define and test supported vanilla policy; no operator viewing bypass |
| Common/custom payloads | Explicit safe non-world allowlist; unknown/custom paths disconnect | Audit supported baseline/compatibility payloads before player admission |
| Lifecycle/geometry | Tracking/unload/reset and real block mutation hooks invalidate pending work; fresh masks on teleport/respawn, world-specific state | Full reconnect/dimension/client-cache fixtures and coherent online reload/disable |
| Failure/overload | No unfiltered fallback; affected-viewer disconnect; bounded jobs/tracking/known IDs, global work guards and systemic latch | Systematic fault injection, readiness checks and measured service guarantees |

Controlled `RuntimeGameTests` exercise transformed actual listener/direct send paths through Netty embedded channels, callbacks, full chunks and unknown payload refusal. These are stronger than isolated codec calls but are not ordinary-client delivery, visual, profiler, SMP or compatibility evidence. See [the test report](TEST_REPORT.md).

## Reproducing source inventory

```sh
./gradlew --no-daemon genSources
python3 scripts/inventory_packets.py
```

The script lists class/reference locations without redistributing Minecraft source. Factories, inherited routes, callback timing and embedded payload schemas still require manual review. Packet policy statuses are generated from the current explicit class handlers/allowlist, not inferred from a successful build.

Source-specific details: 26.3 chunk sections serialize both non-air and fluid counts. `ClientboundLevelChunkWithLightPacket` is a record with a stream codec. The explosion packet contains center/radius/block count/knockback/particles/sound, rather than the historical destroyed-position list. The vanilla deepslate material gradient spans Y0–Y8; Blindstone uses a deterministic Y0 step independent of hidden terrain or seed.

Official background: [Fabric 26.3 notes](https://www.fabricmc.net/2026/09/15/263.html), [networking documentation](https://docs.fabricmc.net/develop/networking), [Loader metadata](https://meta.fabricmc.net/v2/versions/loader/26.3), [API metadata](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml).
