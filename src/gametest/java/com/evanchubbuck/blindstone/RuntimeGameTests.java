package com.evanchubbuck.blindstone;

import com.evanchubbuck.blindstone.runtime.BlindstoneRuntime;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.nio.file.Files;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Optional;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/** Exercises the transformed Connection boundary with actual vanilla listener/direct send routes. */
public final class RuntimeGameTests {
    @GameTest
    public void fabricNegotiationUsesAuditedSchemasRatherThanChannelNames(GameTestHelper helper) throws Exception {
        var channel = Identifier.parse("fixture:channel");
        List<net.minecraft.network.protocol.common.custom.CustomPacketPayload> payloads = List.of(
                new net.fabricmc.fabric.impl.networking.RegistrationPayload(net.fabricmc.fabric.impl.networking.RegistrationPayload.REGISTER, List.of(channel)),
                new net.fabricmc.fabric.impl.networking.RegistrationPayload(net.fabricmc.fabric.impl.networking.RegistrationPayload.UNREGISTER, List.of(channel)),
                new net.fabricmc.fabric.impl.networking.CommonRegisterPayload(1, "play", java.util.Set.of(channel)),
                new net.fabricmc.fabric.impl.networking.CommonVersionPayload(new int[] {1}));
        try (var fixture = new Fixture(helper)) {
            for (var payload : payloads) fixture.connection.send(new ClientboundCustomPayloadPacket(payload));
            helper.assertTrue(fixture.take().size() == payloads.size() && fixture.connection.isConnected(),
                    "Bounded channel/version negotiation schemas must remain supported");
        }
        try (var fixture = new Fixture(helper)) {
            fixture.connection.send(new ClientboundCustomPayloadPacket(new DiscardedPayload(Identifier.parse("c:register"))));
            helper.assertTrue(fixture.take().isEmpty() && !fixture.connection.isConnected(),
                    "An opaque payload with a trusted-looking name cannot bypass the schema policy");
        }
        helper.succeed();
    }

    @GameTest
    public void unauditedDialogAndCookieRequestsFailExplicitly(GameTestHelper helper) throws Exception {
        var data = new net.minecraft.server.dialog.CommonDialogData(net.minecraft.network.chat.Component.literal("Fixture"),
                Optional.empty(), true, false, net.minecraft.server.dialog.DialogAction.CLOSE, List.of(), List.of());
        var dialog = new net.minecraft.server.dialog.NoticeDialog(data, net.minecraft.server.dialog.NoticeDialog.DEFAULT_ACTION);
        for (Packet<?> packet : List.<Packet<?>>of(new net.minecraft.network.protocol.common.ClientboundShowDialogPacket(
                net.minecraft.core.Holder.direct(dialog)), new net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket(
                        Identifier.parse("fixture:cookie")))) {
            try (var fixture = new Fixture(helper)) {
                var calls = new AtomicInteger();
                fixture.connection.send(packet, f -> { if (!f.isSuccess()) calls.incrementAndGet(); });
                helper.assertTrue(fixture.take().isEmpty() && !fixture.connection.isConnected() && calls.get() == 1,
                        "Unsupported nested/opaque flows must disconnect and fail completion once");
            }
        }
        helper.succeed();
    }

    @GameTest
    public void interceptedLightFlushPublishesEarlierFrame(GameTestHelper helper) throws Exception {
        try (var fixture = new Fixture(helper)) {
            var calls = new AtomicInteger();
            var chunk = fixture.player.chunkPosition();
            fixture.connection.send(new ClientboundBlockUpdatePacket(new BlockPos(0, 20, 0), Blocks.DIAMOND_ORE.defaultBlockState()),
                    f -> { if (f.isSuccess()) calls.incrementAndGet(); }, false);
            fixture.connection.send(new net.minecraft.network.protocol.game.ClientboundLightUpdatePacket(chunk.x(), chunk.z(),
                    com.evanchubbuck.blindstone.network.PacketFilter.darkLight(helper.getLevel())),
                    f -> { if (f.isSuccess()) calls.incrementAndGet(); }, true);
            helper.assertTrue(fixture.take().size() == 1 && calls.get() == 2,
                    "Runtime-intercepted light must retain the flush and complete both sources once");
        }
        helper.succeed();
    }

    @GameTest
    public void deferredCallbackFailsOnceOnDisconnect(GameTestHelper helper) throws Exception {
        try (var fixture = new Fixture(helper)) {
            var calls = new AtomicInteger();
            var failed = new AtomicBoolean();
            fixture.connection.send(new ClientboundLevelChunkWithLightPacket(helper.getLevel().getChunkAt(fixture.player.blockPosition()),
                    helper.getLevel().getLightEngine(), null, null), f -> { calls.incrementAndGet(); failed.set(!f.isSuccess()); });
            helper.assertTrue(calls.get() == 0, "Deferred source callback must wait for publication");
            fixture.connection.disconnect(net.minecraft.network.chat.Component.literal("fixture disconnect"));
            fixture.runtime.beginTick();
            fixture.runtime.remove(fixture.player);
            fixture.take();
            helper.assertTrue(calls.get() == 1 && failed.get(), "Session teardown must fail each retained callback exactly once");
        }
        helper.succeed();
    }

    @GameTest
    public void reloadRetainsDeferredCallbackUntilSuccessfulPublication(GameTestHelper helper) throws Exception {
        try (var fixture = new Fixture(helper)) {
            var calls = new AtomicInteger();
            var succeeded = new AtomicBoolean();
            fixture.connection.send(new ClientboundLevelChunkWithLightPacket(helper.getLevel().getChunkAt(fixture.player.blockPosition()),
                    helper.getLevel().getLightEngine(), null, null), f -> { calls.incrementAndGet(); succeeded.set(f.isSuccess()); });
            fixture.runtime.reload();
            helper.assertTrue(calls.get() == 0, "Reload must retain rather than prematurely complete deferred callbacks");
            for (int i = 0; i < 200 && calls.get() == 0; i++) {
                fixture.runtime.beginTick(); fixture.runtime.refresh(helper.getLevel()); fixture.take();
            }
            helper.assertTrue(calls.get() == 1 && succeeded.get(), "Reloaded coherent publication must complete exactly once");
            fixture.runtime.remove(fixture.player); fixture.take();
            helper.assertTrue(calls.get() == 1, "Teardown cannot complete an already published callback again");
        }
        helper.succeed();
    }

    @GameTest
    public void callbackExceptionDisconnectsWithoutRetry(GameTestHelper helper) throws Exception {
        try (var fixture = new Fixture(helper)) {
            var calls = new AtomicInteger();
            var pos = new BlockPos(0, 20, 0);
            var chest = new ChestBlockEntity(pos, Blocks.CHEST.defaultBlockState()); chest.setLevel(helper.getLevel());
            fixture.connection.send(ClientboundBlockEntityDataPacket.create(chest), f -> {
                calls.incrementAndGet(); throw new IllegalStateException("intentional callback failure");
            });
            fixture.take();
            helper.assertTrue(calls.get() == 1 && !fixture.connection.isConnected(), "Throwing callback must not be retried or left pending");
        }
        helper.succeed();
    }

    @GameTest
    public void asynchronousUnprotectedWorldSendIsExplicitlyUnsupported(GameTestHelper helper) throws Exception {
        try (var fixture = new Fixture(helper, helper.getLevel().getServer().getLevel(net.minecraft.world.level.Level.NETHER))) {
            var failed = new AtomicBoolean();
            var worker = new Thread(() -> fixture.connection.send(new ClientboundBlockUpdatePacket(new BlockPos(0, 20, 0),
                    Blocks.DIAMOND_ORE.defaultBlockState()), f -> failed.set(!f.isSuccess())));
            worker.start(); worker.join(5000); fixture.take();
            helper.assertTrue(!worker.isAlive() && failed.get() && !fixture.connection.isConnected(),
                    "The explicit off-thread PLAY restriction also applies outside the protected dimension");
        }
        helper.succeed();
    }

    @GameTest
    public void asynchronousKeepalivePreservesTransportCompletion(GameTestHelper helper) throws Exception {
        try (var fixture = new Fixture(helper)) {
            var calls = new AtomicInteger();
            var worker = new Thread(() -> fixture.connection.send(new net.minecraft.network.protocol.common.ClientboundKeepAlivePacket(42L),
                    f -> { if (f.isSuccess()) calls.incrementAndGet(); }));
            worker.start(); worker.join(5000);
            helper.assertTrue(fixture.take().size() == 1 && fixture.connection.isConnected() && calls.get() == 1,
                    "World-independent keepalives remain supported off-thread");
        }
        helper.succeed();
    }

    @GameTest
    public void suppressedFlushPublishesEarlierUnflushedFrame(GameTestHelper helper) throws Exception {
        try (var fixture = new Fixture(helper)) {
            var pos = new BlockPos(0, 20, 0);
            var chest = new ChestBlockEntity(pos, Blocks.CHEST.defaultBlockState());
            chest.setLevel(helper.getLevel());
            var calls = new AtomicInteger();
            fixture.connection.send(new ClientboundBlockUpdatePacket(pos, Blocks.DIAMOND_ORE.defaultBlockState()),
                    f -> { if (f.isSuccess()) calls.incrementAndGet(); }, false);
            fixture.connection.send(ClientboundBlockEntityDataPacket.create(chest),
                    f -> { if (f.isSuccess()) calls.incrementAndGet(); }, true);
            helper.assertTrue(fixture.take().size() == 1, "A suppressed flush must still publish the earlier filtered frame");
            helper.assertTrue(calls.get() == 2, "Both original operations must complete exactly once");
        }
        helper.succeed();
    }

    @GameTest
    public void ordinaryEmptyPostEffectsReachTransformedConnection(GameTestHelper helper) throws Exception {
        try (var fixture = new Fixture(helper)) {
            fixture.player.sendPostEffects();
            var output = fixture.take();
            helper.assertTrue(fixture.connection.isConnected(), "Ordinary placement's empty post-effects state must not disconnect");
            helper.assertTrue(output.size() == 1 && output.getFirst() instanceof
                    net.minecraft.network.protocol.common.ClientboundPostEffectsPacket effects && effects.postEffects().isEmpty(),
                    "The vanilla sendPostEffects route must deliver the empty state");
        }
        helper.succeed();
    }

    @GameTest
    public void nonemptyAndClearedPostEffectsReachTransformedConnection(GameTestHelper helper) throws Exception {
        try (var fixture = new Fixture(helper)) {
            var effect = Identifier.parse("minecraft:creeper");
            fixture.player.addPostEffect(effect);
            fixture.player.sendPostEffects();
            var nonempty = fixture.take();
            helper.assertTrue(nonempty.size() == 1 && nonempty.getFirst() instanceof
                    net.minecraft.network.protocol.common.ClientboundPostEffectsPacket effects && effects.postEffects().equals(List.of(effect)),
                    "Representative shader identifiers must survive the vanilla sender");
            fixture.player.clearPostEffects();
            fixture.player.sendPostEffects();
            var output = fixture.take();
            helper.assertTrue(fixture.connection.isConnected(), "Post-effect changes must keep the viewer connected");
            helper.assertTrue(output.size() == 1 && output.getFirst() instanceof
                    net.minecraft.network.protocol.common.ClientboundPostEffectsPacket effects && effects.postEffects().isEmpty(),
                    "Clearing post effects must deliver the empty state");
        }
        helper.succeed();
    }

    @GameTest
    public void auditedCommonPlayPacketsPreserveFieldsAndCompletion(GameTestHelper helper) throws Exception {
        try (var fixture = new Fixture(helper)) {
            var id = UUID.randomUUID();
            List<Packet<?>> packets = List.of(
                    new net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket(id,
                            "https://example.invalid/pack.zip", "0123456789abcdef", true,
                            Optional.of(net.minecraft.network.chat.Component.literal("Fixture pack"))),
                    new net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket(Optional.of(id)),
                    new net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket(Optional.empty()),
                    new net.minecraft.network.protocol.common.ClientboundCustomReportDetailsPacket(Map.of("fixture", "26.3")),
                    new net.minecraft.network.protocol.common.ClientboundServerLinksPacket(List.of()),
                    net.minecraft.network.protocol.common.ClientboundClearDialogPacket.INSTANCE,
                    new net.minecraft.network.protocol.common.ClientboundTransferPacket("example.invalid", 25565));
            var completed = new AtomicInteger();
            for (var packet : packets) fixture.connection.send(packet, f -> { if (f.isSuccess()) completed.incrementAndGet(); });
            helper.assertTrue(fixture.take().equals(packets), "Audited common packets must retain their fields and order");
            helper.assertTrue(fixture.connection.isConnected() && completed.get() == packets.size(),
                    "Every supported common packet must complete exactly once");
        }
        helper.succeed();
    }

    @GameTest
    public void opaqueCookieIsRejectedAndCompletesFailure(GameTestHelper helper) throws Exception {
        try (var fixture = new Fixture(helper)) {
            var calls = new AtomicInteger();
            var failed = new AtomicBoolean();
            fixture.connection.send(new net.minecraft.network.protocol.common.ClientboundStoreCookiePacket(
                    Identifier.parse("fixture:opaque"), new byte[] {1, 2, 3}), f -> {
                        calls.incrementAndGet(); failed.set(!f.isSuccess());
                    });
            helper.assertTrue(fixture.take().isEmpty() && !fixture.connection.isConnected(), "Opaque cookie bytes must fail closed");
            helper.assertTrue(calls.get() == 1 && failed.get(), "Unsupported policy must complete once with failure");
        }
        helper.succeed();
    }

    @GameTest
    public void asynchronousWorldSendFailsClosedAndCompletes(GameTestHelper helper) throws Exception {
        try (var fixture = new Fixture(helper)) {
            var calls = new AtomicInteger();
            var failed = new AtomicBoolean();
            Thread worker = new Thread(() -> fixture.connection.send(new ClientboundBlockUpdatePacket(
                    new BlockPos(0, 20, 0), Blocks.DIAMOND_ORE.defaultBlockState()), f -> {
                        calls.incrementAndGet(); failed.set(!f.isSuccess());
                    }), "blindstone-async-regression");
            worker.start(); worker.join(5000);
            helper.assertTrue(!worker.isAlive(), "Asynchronous rejection must be bounded");
            helper.assertTrue(fixture.take().isEmpty() && !fixture.connection.isConnected(), "Off-thread world data cannot be forwarded");
            helper.assertTrue(calls.get() == 1 && failed.get(), "Off-thread rejection must complete exactly once");
        }
        helper.succeed();
    }

    @GameTest
    public void codecBundleDelimiterCannotBeSentDirectly(GameTestHelper helper) throws Exception {
        try (var fixture = new Fixture(helper)) {
            var calls = new AtomicInteger();
            fixture.connection.send(new net.minecraft.network.protocol.game.ClientboundBundleDelimiterPacket(), f -> calls.incrementAndGet());
            helper.assertTrue(fixture.take().isEmpty() && !fixture.connection.isConnected(), "Direct unaudited delimiters must fail closed");
            helper.assertTrue(calls.get() == 1, "Rejected direct delimiter must complete once");
        }
        helper.succeed();
    }

    @GameTest
    public void runtimePublishesSafetyAndResumesSightWithTinyBudgets(GameTestHelper helper) throws Exception {
        var runtime = BlindstoneRuntime.get(helper.getLevel().getServer());
        var file = FabricLoader.getInstance().getConfigDir().resolve("blindstone.json");
        String original = Files.readString(file);
        try {
            var json = JsonParser.parseString(original).getAsJsonObject();
            json.addProperty("revealRangeBlocks", 4);
            json.getAsJsonObject("budgets").addProperty("maxGeometryChecksPerTickPerViewer", 32);
            json.getAsJsonObject("budgets").addProperty("maxWorkNanosPerTick", 50000000);
            Files.writeString(file, json.toString());
            runtime.reload();
            try (var fixture = new Fixture(helper)) {
                var chunk = helper.getLevel().getChunkAt(fixture.player.blockPosition());
                fixture.connection.send(new ClientboundLevelChunkWithLightPacket(chunk, helper.getLevel().getLightEngine(), null, null));
                fixture.take();
                int sent = 0;
                for (int i = 0; i < 128; i++) {
                    runtime.beginTick();
                    runtime.refresh(helper.getLevel());
                    sent += fixture.take().stream().filter(packet -> packet instanceof ClientboundLevelChunkWithLightPacket).count();
                    helper.assertTrue(fixture.connection.isConnected(), "Bounded sight jobs must keep the movement-safe test viewer connected");
                }
                helper.assertTrue(sent > 0, "Runtime must publish a coherent movement-safe full chunk through the real Connection hook");
                helper.assertTrue(!runtime.inspect(fixture.player).contains("visibility candidates=0/"),
                        "Retained sight work must advance beyond initial camera work under a 32-check budget");
            }
        } finally {
            Files.writeString(file, original);
            runtime.reload();
        }
        helper.succeed();
    }

    @GameTest
    public void listenerAndDirectConnectionUpdatesAreBothFiltered(GameTestHelper helper) throws Exception {
        try (var fixture = new Fixture(helper)) {
            var hidden = new BlockPos(fixture.player.getBlockX() + 1, 20, fixture.player.getBlockZ());
            var source = new ClientboundBlockUpdatePacket(hidden, Blocks.DIAMOND_ORE.defaultBlockState());
            fixture.player.connection.send(source);
            fixture.connection.send(source);
            var packets = fixture.take();
            helper.assertTrue(packets.size() == 2, "Both send routes must reach the transformed boundary");
            for (Object packet : packets) helper.assertTrue(packet instanceof ClientboundBlockUpdatePacket update
                    && update.getBlockState().is(Blocks.STONE), "Concealed direct updates must preserve fake terrain");
            helper.assertTrue(source.getBlockState().is(Blocks.DIAMOND_ORE), "Shared original packet must be unchanged");
        }
        helper.succeed();
    }

    @GameTest
    public void nestedBundleCannotBypassUpdateFiltering(GameTestHelper helper) throws Exception {
        try (var fixture = new Fixture(helper)) {
            var hidden = new BlockPos(fixture.player.getBlockX() + 1, 20, fixture.player.getBlockZ());
            fixture.connection.send(new ClientboundBundlePacket(List.of(new ClientboundBundlePacket(List.of(
                    new ClientboundBlockUpdatePacket(hidden, Blocks.CHEST.defaultBlockState()))))));
            var packets = fixture.take();
            helper.assertTrue(packets.size() == 1 && packets.getFirst() instanceof ClientboundBundlePacket,
                    "Filtered wrapper must remain a game bundle");
            var bundle = (ClientboundBundlePacket) packets.getFirst();
            for (var packet : bundle.subPackets()) helper.assertTrue(packet instanceof ClientboundBlockUpdatePacket update
                    && update.getBlockState().is(Blocks.STONE), "Nested hidden state cannot reach the wire");
        }
        helper.succeed();
    }

    @GameTest
    public void suppressedPacketsPreserveCompletionCallbacks(GameTestHelper helper) throws Exception {
        try (var fixture = new Fixture(helper)) {
            var pos = new BlockPos(fixture.player.getBlockX(), 20, fixture.player.getBlockZ());
            var chest = new ChestBlockEntity(pos, Blocks.CHEST.defaultBlockState());
            chest.setLevel(helper.getLevel());
            var completed = new AtomicBoolean();
            fixture.connection.send(ClientboundBlockEntityDataPacket.create(chest), future -> completed.set(future.isSuccess()));
            fixture.channel.runPendingTasks();
            helper.assertTrue(fixture.take().isEmpty(), "Hidden block entity must be suppressed");
            helper.assertTrue(completed.get(), "Deliberate concealment must complete the caller's callback");
        }
        helper.succeed();
    }

    @GameTest
    public void realChunkSendsUseDetachedFilteredData(GameTestHelper helper) throws Exception {
        try (var fixture = new Fixture(helper)) {
            var chunk = helper.getLevel().getChunkAt(fixture.player.blockPosition());
            var packet = new ClientboundLevelChunkWithLightPacket(chunk, helper.getLevel().getLightEngine(), null, null);
            fixture.connection.send(packet);
            List<Object> output = new ArrayList<>();
            for(int i=0;i<200&&output.isEmpty();i++) {
                fixture.runtime.beginTick();fixture.runtime.refresh(helper.getLevel());output.addAll(fixture.take());
            }
            helper.assertTrue(output.size() == 1 && output.getFirst() instanceof ClientboundLevelChunkWithLightPacket,
                    "Full chunks must pass through the actual Connection hook");
            var safe = (ClientboundLevelChunkWithLightPacket) output.getFirst();
            helper.assertTrue(safe != packet && safe.chunkData() != packet.chunkData(), "Each viewer gets isolated chunk data");
            helper.assertTrue(safe.lightData()!=packet.lightData()
                    && safe.lightData().skyYMask().cardinality()==safe.lightData().skyUpdates().size(),
                    "Runtime must supply detached reconstructed light with coherent arrays");
            helper.assertTrue(fixture.runtime.inspect(fixture.player).contains("Tracked chunks=1"),
                    "Initial chunk delivery must register the tracking session");
        }
        helper.succeed();
    }

    @GameTest
    public void unknownCustomPayloadDisconnectsWithoutForwarding(GameTestHelper helper) throws Exception {
        try (var fixture = new Fixture(helper)) {
            fixture.connection.send(new ClientboundCustomPayloadPacket(new DiscardedPayload(Identifier.parse("fixture:unsupported"))));
            helper.assertTrue(fixture.take().isEmpty(), "Unsupported custom packets cannot bypass concealment");
            helper.assertTrue(!fixture.connection.isConnected(), "Unsafe compatibility route must disconnect affected viewer");
        }
        helper.succeed();
    }

    private static final class Fixture implements AutoCloseable {
        private final ServerPlayer player;
        private final Connection connection;
        private final EmbeddedChannel channel;
        private final BlindstoneRuntime runtime;

        private Fixture(GameTestHelper helper) throws Exception {
            this(helper, helper.getLevel());
        }

        private Fixture(GameTestHelper helper, net.minecraft.server.level.ServerLevel level) throws Exception {
            var server = helper.getLevel().getServer();
            runtime = BlindstoneRuntime.get(server);
            if (runtime == null) throw new IllegalStateException("Lifecycle hook did not initialize the runtime");
            var profile = new GameProfile(UUID.randomUUID(), "blindstone_test");
            player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
            var pos = helper.absolutePos(new BlockPos(0, 1, 0));
            player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            connection = new Connection(PacketFlow.SERVERBOUND);
            channel = new EmbeddedChannel(connection);
            var listener = new ServerGamePacketListenerImpl(server, connection, player, CommonListenerCookie.createInitial(profile, false));
            var field = Connection.class.getDeclaredField("packetListener");
            field.setAccessible(true);
            field.set(connection, listener);
        }

        private List<Object> take() {
            channel.runPendingTasks();
            List<Object> output = new ArrayList<>();
            Object packet;
            while ((packet = channel.readOutbound()) != null) output.add(packet);
            return output;
        }

        @Override public void close() {
            runtime.remove(player);
            player.discard();
            channel.finishAndReleaseAll();
        }
    }
}
