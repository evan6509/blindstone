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
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
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
            var server = helper.getLevel().getServer();
            runtime = BlindstoneRuntime.get(server);
            if (runtime == null) throw new IllegalStateException("Lifecycle hook did not initialize the runtime");
            var profile = new GameProfile(UUID.randomUUID(), "blindstone_test");
            player = new ServerPlayer(server, helper.getLevel(), profile, ClientInformation.createDefault());
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
