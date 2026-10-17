# 26.3 login and integration audit

Scope: Minecraft 26.3, Java 25, Loader 0.19.5 and Fabric API 0.163.0+26.3. This audit uses the pinned generated Minecraft sources, installed class files, matching Fabric sources and the tests described in [TEST_REPORT.md](TEST_REPORT.md). It does not certify an arbitrary backend mod set or proxy.

## Common PLAY policy

The explicit class allowlist remains closed by default. Pre-PLAY traffic does not enter underground filtering. Authored UI text, URLs and effect identifiers are public server metadata under the same contract as existing authored chat/items; server authors must not deliberately put concealed world data in these fields.

| Packet | Exact schema and audited origins | Protected PLAY policy |
| --- | --- | --- |
| Post effects | List of resource identifiers; `ServerPlayer.sendPostEffects`, called by `PlayerList.placeNewPlayer`, respawn, dimension transitions and dirty player tick state. Client assigns the player's active shader identifiers. No positions, entity references or world samples. | Passthrough, including empty state and custom resource identifiers. Tests exercise the vanilla sender, actual TCP codecs and ordinary placement/lifecycle. Identifier acceptance does not promise the client has that resource. |
| Resource pack push | UUID, URL, hash (maximum 40 characters), required flag, optional authored prompt. `ServerResourcePackConfigurationTask` and `ServerPackCommand`. Client prompts/downloads and sends `ServerboundResourcePackPacket` terminal/intermediate status. | Passthrough without losing required/prompt/identity fields; no suppression or synthetic server success. Required PLAY codec tested; ordinary admission explicitly declines an optional configuration pack. Actual downloading/application remains unverified. |
| Resource pack pop | Optional UUID, with empty meaning all packs. `ServerPackCommand`; client removes matching/all downloaded packs. | Passthrough. UUID and empty forms tested. |
| Custom report details | Up to 32 authored string pairs, key maximum 128 and value maximum 4096 characters. No vanilla constructor send site found in the lexical source audit; exposed by the common listener/codec. Client retains report metadata. | Passthrough; nonempty map codec tested. This is conditional integration support, not an ordinary-login requirement. |
| Server links | List of known-type or authored-label links with URL strings. `ServerConfigurationPacketListenerImpl.startConfiguration`; client validates URLs and stores links. | Passthrough. Nonempty PLAY codec and real configuration delivery tested. |
| Clear dialog | Unit packet, no fields. `DialogCommand.clearDialog`; closes current UI. | Passthrough. Does not imply support for showing arbitrary dialogs. |
| Show dialog | Registry holder or nested dialog: text/items, inputs, command/click actions and templates. `ServerPlayer.openDialog` / `DialogCommand`, plus pre-PLAY `DebugConfigCommand`. | Explicit unsupported disconnect in protected PLAY; nested action/content schemas are unaudited. A minimal notice also rejects with a failed callback. No silent drop that could strand an interactive flow. |
| Store cookie / cookie request | Identifier plus opaque bytes (store maximum 5120 bytes); request carries an identifier and expects a response. No vanilla store-cookie constructor send site found. Client stores/returns arbitrary bytes. | Explicit unsupported disconnect in protected PLAY. A trusted-looking identifier does not audit opaque contents. LOGIN/configuration cookie handling is outside this hook. |
| Transfer | Host string and port. `TransferCommand`; client closes the current connection and connects to the specified endpoint using retained cookies. | Packet passthrough and codec tested. Actual destination traversal and cookie-dependent transfer integrations remain unverified/unsupported where they need protected-PLAY cookies. |

These common packets are conditional features except the post-effects state in ordinary placement. They are not all sent by every server during login.

## Bundle boundary and completions

`GameProtocols` registers a unit delimiter using `ProtocolInfoBuilder.withBundlePacket`. `PacketBundleUnpacker` produces wire delimiters from a `ClientboundBundlePacket` later in the Netty pipeline, after the Connection hook. No vanilla constructor send site for a direct delimiter was found. The decoded TCP bundle fixture proves ordinary wrapper delivery, ordering, suppression and source callback completion without a delimiter disconnect. An explicit direct delimiter send is rejected and tested; this is not evidence of a vanilla join failure.

Suppressed frames and intercepted engine-light frames now retain their flush boundary for preceding writes. Deferred batches preserve frame callbacks/order and flush at publication (including an empty suppressed batch). Regressions cover successful publication, intentional suppression, unsupported-schema exception, callback exception, disconnect cancellation and reload retaining a pending callback until coherent publication. Cancellation fails retained callbacks once; suppression succeeds once. No unbounded asynchronous world-filter queue was introduced.

## Supported payload baseline

The installed test server contains only Blindstone and the exact Fabric API umbrella JAR with its bundled modules, loaded by Loader 0.19.5. It contains no test companion mod, FabricProxy-Lite, optimization mod or alternate transport. The runtime log records the complete loaded module versions. Wire reports separately record CONFIGURATION and PLAY payload IDs.

| Payload schema | Fields / status |
| --- | --- |
| `minecraft:brand` (`BrandPayload`) | Authored brand string; allowed. Observed in configuration. |
| `minecraft:register`, `minecraft:unregister` (`RegistrationPayload`) | Fabric bounded channel identifier lists; allowed only for these exact type identities. Register observed in the baseline. |
| `c:register` (`CommonRegisterPayload`) | Negotiation version, protocol string, bounded identifier set; allowed exact schema. |
| `c:version` (`CommonVersionPayload`) | Negotiation integer versions; allowed exact schema. |
| `fabric:attachment_sync_v1` | Fabric attachment targets/data can carry entity, block/chunk or arbitrary attachment state. Unsupported in protected PLAY. |
| `fabric-menu-api-v1:open_screen` | Menu identifier, container ID, title, extension-specific codec/data. Unsupported extension data in protected PLAY. |
| `fabric:recipe_sync` | Recipe serializer IDs, recipe IDs and serializer-specific recipe data. Unsupported in protected PLAY. |
| `fabric:split` / internal passthrough frame | Opaque fragments/already encoded content; no global bypass. Unsupported if supplied to the Connection boundary. |
| Registry sync, attachment acceptance, custom ingredient/recipe negotiation, extended particle fallback | Matching Fabric modules register configuration schemas outside this PLAY hook. The vanilla client baseline does not activate arbitrary modded gameplay features. |

Only `minecraft:brand` and `minecraft:register` were observed in the offline vanilla-protocol baseline. Fabric's other registered schemas above were inspected, not proven active with every feature. Schema-specific negotiation and same-ID opaque spoof rejection are tested. Arbitrary custom payloads still disconnect; installing Fabric API does not make every extension feature compatible.

## Thread ownership and proxy boundary

The hook checks immutable listener server ownership before accessing the player's mutable level or runtime/viewer data. Vanilla placement, player post-effects ticks, respawn/dimension transitions and console commands execute on the owning server thread. The observed normal-login run needed no asynchronous world send. Vanilla Connection exception handling can send a disconnect off-thread; world-independent disconnect, keepalive, ping and player/system chat retain the existing transport exceptions. LOGIN authentication workers and configuration tasks use non-PLAY listeners and bypass underground filtering.

Other asynchronous PLAY sends intentionally disconnect, including in unprotected dimensions or when protection is disabled. Tests cover the protected and unprotected cases, failure completion and off-thread keepalive success. Integrations must send supported world packets on the server thread; no safe scheduling/order contract is advertised for arbitrary worker sends. Raw channel writes or replaced networking hooks remain unsupported.

FabricProxy-Lite performs LOGIN forwarding. The PLAY custom-payload allowlist alone does not demonstrate a forwarding-query blocker. No exact target FabricProxy-Lite artifact or supported proxy environment was supplied or tested here; authenticated forwarding, online account authentication and cross-server traversal remain unverified. Do not advertise proxy compatibility from source signatures or metadata. No Hub, proxy configuration, existing listener or service was changed.
