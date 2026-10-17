package com.evanchubbuck.blindstone;

import com.evanchubbuck.blindstone.runtime.BlindstoneRuntime;
import com.mojang.authlib.GameProfile;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.UnconfiguredPipelineHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Actual loopback TCP, vanilla framing/codecs and decoded PLAY packets. These fixtures deliberately
 * install an authenticated server-side player directly in PLAY; they do NOT test login, account
 * authentication, configuration/registry exchange, rendering, controls, or a retail game client.
 */
public final class LiveConnectionGameTests {
    @GameTest(maxTicks = 100)
    public void auditedCommonPacketsRoundTripOnRealTcp(GameTestHelper helper) throws Exception {
        try (var fixture = new TcpPlayFixture(helper)) {
            var id = UUID.randomUUID();
            List<Packet<?>> packets = List.of(
                    new net.minecraft.network.protocol.common.ClientboundPostEffectsPacket(List.of()),
                    new net.minecraft.network.protocol.common.ClientboundPostEffectsPacket(List.of(net.minecraft.resources.Identifier.parse("minecraft:creeper"))),
                    new net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket(id, "https://example.invalid/pack.zip", "", true,
                            java.util.Optional.of(Component.literal("Fixture required pack"))),
                    new net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket(java.util.Optional.of(id)),
                    new net.minecraft.network.protocol.common.ClientboundCustomReportDetailsPacket(java.util.Map.of("fixture", "26.3")),
                    new net.minecraft.network.protocol.common.ClientboundServerLinksPacket(List.of(
                            new net.minecraft.server.ServerLinks.UntrustedEntry(com.mojang.datafixers.util.Either.left(
                                    net.minecraft.server.ServerLinks.KnownLinkType.BUG_REPORT), "https://example.invalid/bugs"))),
                    net.minecraft.network.protocol.common.ClientboundClearDialogPacket.INSTANCE,
                    new net.minecraft.network.protocol.common.ClientboundTransferPacket("example.invalid", 25565));
            var callbacks = new java.util.concurrent.atomic.AtomicInteger();
            for (var packet : packets) {
                fixture.serverConnection.send(packet, f -> { if (f.isSuccess()) callbacks.incrementAndGet(); }, false);
            }
            fixture.serverConnection.flushChannel();
            for (var expected : packets) helper.assertTrue(expected.equals(fixture.awaitPacket()),
                    "Pinned common packet codec must preserve every audited field and order: " + expected.type());
            helper.assertTrue(callbacks.get() == packets.size(), "TCP flush must complete every source callback once");
            fixture.assertConnected(helper);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void twoTcpPlayConnectionsDecodeIndependentFilteredUpdatesAndBundles(GameTestHelper helper) throws Exception {
        try (var first = new TcpPlayFixture(helper); var second = new TcpPlayFixture(helper)) {
            BlockPos hidden = new BlockPos(first.player.getBlockX(), 20, first.player.getBlockZ());
            var original = new ClientboundBlockUpdatePacket(hidden, Blocks.DIAMOND_ORE.defaultBlockState());
            var chest = new ChestBlockEntity(hidden, Blocks.CHEST.defaultBlockState());
            chest.setLevel(helper.getLevel());
            var callback = new CountDownLatch(1);
            first.serverConnection.send(new ClientboundBundlePacket(List.of(original, ClientboundBlockEntityDataPacket.create(chest))),
                    future -> { if (future.isSuccess()) callback.countDown(); });
            second.serverConnection.send(original);
            var firstPackets = flatten(first.awaitPacket());
            var secondPackets = flatten(second.awaitPacket());
            helper.assertTrue(callback.await(5, TimeUnit.SECONDS), "Actual TCP publication must complete its source callback");
            for (var packets : List.of(firstPackets, secondPackets)) {
                helper.assertTrue(packets.size() == 1 && packets.getFirst() instanceof ClientboundBlockUpdatePacket,
                        "A concealed block entity must not survive actual encoded transport");
                var update = (ClientboundBlockUpdatePacket) packets.getFirst();
                helper.assertTrue(update != original && update.getBlockState().is(Blocks.STONE),
                        "Independent real sockets must decode replacement terrain, not the shared source ore");
            }
            helper.assertTrue(original.getBlockState().is(Blocks.DIAMOND_ORE), "The original shared packet must remain unchanged");
            first.assertConnected(helper);
            second.assertConnected(helper);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 400)
    public void tcpPlayClientDecodesDeferredChunkWithBodySafetyAndConcealedAir(GameTestHelper helper) throws Exception {
        var fixture = new TcpPlayFixture(helper);
        helper.runBeforeTestEnd(fixture::close);
        var chunk = helper.getLevel().getChunkAt(fixture.player.blockPosition());
        BlockPos hidden = null;
        var policy = fixture.runtime.configuration().current().policy();
        for (int y = policy.maxY() - 1; y >= policy.minY(); y--) {
            var candidate = new BlockPos(chunk.getPos().getMinBlockX() + 12, y, chunk.getPos().getMinBlockZ() + 12);
            if (Math.abs(y - fixture.player.getY()) > 20 && chunk.getBlockState(candidate).isAir()) { hidden = candidate; break; }
        }
        if (hidden == null) { fixture.close(); throw new IllegalStateException("TCP fixture needs protected real air beyond the maximum reveal range"); }
        final BlockPos distantAir = hidden;
        // Outdoor air is public even below the cutoff. Explicitly cover this fixture's distant
        // air so the real TCP regression continues to exercise concealed underground geometry.
        var roof = new java.util.LinkedHashMap<BlockPos, BlockState>();
        for (int z = -2; z <= 2; z++) for (int x = -2; x <= 2; x++) {
            var pos = distantAir.offset(x, 1, z);
            roof.put(pos, helper.getLevel().getBlockState(pos));
            helper.getLevel().setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
        }
        helper.runBeforeTestEnd(() -> roof.forEach((pos, state) -> helper.getLevel().setBlock(pos, state, 2)));
        var source = new ClientboundLevelChunkWithLightPacket(chunk, helper.getLevel().getLightEngine(), null, null);
        var completed = new AtomicBoolean();
        fixture.serverConnection.send(source, future -> completed.set(future.isSuccess()));
        var decoded = new AtomicReference<ClientboundLevelChunkWithLightPacket>();
        helper.succeedWhen(() -> {
            fixture.assertConnected(helper);
            Packet<?> next;
            while ((next = fixture.received.poll()) != null) for (var packet : flatten(next)) {
                if (packet instanceof ClientboundLevelChunkWithLightPacket full) decoded.set(full);
            }
            var packet = decoded.get();
            helper.assertTrue(packet != null, "A real TCP client must receive the asynchronously prepared initial chunk");
            helper.assertTrue(completed.get(), "Deferred TCP chunk callback must complete after publication");
            var body = fixture.player.blockPosition();
            helper.assertTrue(readBlock(helper, packet, body).equals(chunk.getBlockState(body)),
                    "The decoded initial chunk must contain the accepted body's true block state");
            helper.assertTrue(chunk.getBlockState(distantAir).isAir(), "Controlled distant source cell must be real air");
            helper.assertTrue(readBlock(helper, packet, distantAir).equals(policy.replacement(distantAir.getY())),
                    "Decoded distant covered air must remain replacement terrain");
            helper.assertTrue(packet.lightData().skyYMask().cardinality() == packet.lightData().skyUpdates().size()
                    && packet.lightData().blockYMask().cardinality() == packet.lightData().blockUpdates().size(),
                    "Actual wire light masks must match their decoded array counts");
            fixture.close();
        });
    }

    private static BlockState readBlock(GameTestHelper helper, ClientboundLevelChunkWithLightPacket packet, BlockPos pos) {
        var buffer = packet.chunkData().getReadBuffer();
        try {
            BlockState result = null;
            for (int sectionY = helper.getLevel().getMinSectionY(); sectionY <= helper.getLevel().getMaxSectionY(); sectionY++) {
                var section = new LevelChunkSection(helper.getLevel().palettedContainerFactory());
                section.read(buffer);
                if (sectionY == pos.getY() >> 4) result = section.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
            }
            helper.assertTrue(!buffer.isReadable() && result != null, "Real TCP chunk data must decode exactly every world section");
            return result;
        } finally { buffer.release(); }
    }

    private static List<Packet<?>> flatten(Packet<?> packet) {
        List<Packet<?>> result = new ArrayList<>();
        if (packet instanceof ClientboundBundlePacket bundle) for (var child : bundle.subPackets()) result.addAll(flatten(child));
        else result.add(packet);
        return result;
    }

    private static final class TcpPlayFixture implements AutoCloseable {
        private final BlindstoneRuntime runtime;
        private final ServerPlayer player;
        private final Connection serverConnection;
        private final Connection clientConnection;
        private final Channel listener;
        private final LinkedBlockingQueue<Packet<?>> received = new LinkedBlockingQueue<>(16_384);
        private final AtomicBoolean closed = new AtomicBoolean();

        private TcpPlayFixture(GameTestHelper helper) throws Exception {
            var server = helper.getLevel().getServer();
            runtime = BlindstoneRuntime.get(server);
            if (runtime == null) throw new IllegalStateException("Runtime lifecycle did not initialize");
            var accepted = new AtomicReference<Connection>();
            var ready = new CountDownLatch(1);
            var group = EventLoopGroupHolder.remote(false);
            listener = new ServerBootstrap().group(group.eventLoopGroup()).channel(group.serverChannelCls())
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override protected void initChannel(Channel channel) {
                            var connection = new Connection(PacketFlow.SERVERBOUND);
                            Connection.configureSerialization(channel.pipeline(), PacketFlow.SERVERBOUND, false, null);
                            // Direct PLAY setup starts in the same unconfigured state that vanilla's
                            // terminal handshake/login packets normally install before a phase swap.
                            channel.pipeline().replace("decoder", "inbound_config", new UnconfiguredPipelineHandler.Inbound());
                            connection.configurePacketHandler(channel.pipeline());
                            channel.pipeline().addLast("blindstone_test_ready", new ChannelInboundHandlerAdapter() {
                                @Override public void channelActive(ChannelHandlerContext context) throws Exception {
                                    accepted.set(connection);
                                    ready.countDown();
                                    super.channelActive(context);
                                }
                            });
                        }
                    }).bind(InetAddress.getLoopbackAddress(), 0).syncUninterruptibly().channel();
            Connection client = null;
            ServerPlayer created = null;
            try {
                client = new Connection(PacketFlow.CLIENTBOUND);
                var connected = Connection.connect((InetSocketAddress) listener.localAddress(), group, client).syncUninterruptibly();
                connected.channel().eventLoop().submit(() -> connected.channel().pipeline().replace(
                        "encoder", "outbound_config", new UnconfiguredPipelineHandler.Outbound())).syncUninterruptibly();
                if (!ready.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Loopback server did not accept the TCP client");
                serverConnection = accepted.get();
                clientConnection = client;
                var profile = new GameProfile(UUID.randomUUID(), "blindstone_tcp");
                created = new ServerPlayer(server, helper.getLevel(), profile, ClientInformation.createDefault());
                player = created;
                var pos = helper.absolutePos(new BlockPos(0, 1, 0));
                player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
                var clientListener = (ClientGamePacketListener) Proxy.newProxyInstance(ClientGamePacketListener.class.getClassLoader(),
                        new Class<?>[] {ClientGamePacketListener.class}, (proxy, method, arguments) -> {
                            return switch (method.getName()) {
                                case "flow" -> PacketFlow.CLIENTBOUND;
                                case "protocol" -> ConnectionProtocol.PLAY;
                                case "isAcceptingMessages", "shouldHandleMessage" -> !closed.get() && clientConnection.isConnected();
                                case "createDisconnectionInfo" -> new DisconnectionDetails((Component) arguments[0]);
                                case "toString" -> "Blindstone actual TCP decoded PLAY listener";
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == arguments[0];
                                default -> {
                                    if (arguments != null && arguments.length == 1 && arguments[0] instanceof Packet<?> packet
                                            && !received.offer(packet)) throw new IllegalStateException("Bounded decoded-packet queue exhausted");
                                    yield null;
                                }
                            };
                        });
                var decoration = RegistryFriendlyByteBuf.decorator(server.registryAccess());
                var game = new ServerGamePacketListenerImpl(server, serverConnection, player, CommonListenerCookie.createInitial(profile, false));
                clientConnection.setupInboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(decoration), clientListener);
                clientConnection.setupOutboundProtocol(GameProtocols.SERVERBOUND_TEMPLATE.bind(decoration, game));
                serverConnection.setupInboundProtocol(GameProtocols.SERVERBOUND_TEMPLATE.bind(decoration, game), game);
                serverConnection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(decoration));
                if (serverConnection.isMemoryConnection() || clientConnection.isMemoryConnection())
                    throw new IllegalStateException("TCP fixture unexpectedly used a local in-memory channel");
            } catch (Throwable failure) {
                if (created != null) { runtime.remove(created); created.discard(); }
                if (client != null) client.disconnect(Component.literal("TCP fixture initialization failed"));
                var incoming = accepted.get();
                if (incoming != null) incoming.disconnect(Component.literal("TCP fixture initialization failed"));
                listener.close().syncUninterruptibly();
                throw failure;
            }
        }

        private Packet<?> awaitPacket() throws InterruptedException {
            Packet<?> packet = received.poll(5, TimeUnit.SECONDS);
            if (packet == null) throw new IllegalStateException("No decoded TCP payload; server=" + serverConnection.getDisconnectionDetails()
                    + ", client=" + clientConnection.getDisconnectionDetails());
            return packet;
        }

        private void assertConnected(GameTestHelper helper) {
            helper.assertTrue(serverConnection.isConnected() && clientConnection.isConnected(), "Actual TCP peers must remain connected; server="
                    + serverConnection.getDisconnectionDetails() + ", client=" + clientConnection.getDisconnectionDetails());
        }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            runtime.remove(player);
            player.discard();
            serverConnection.disconnect(Component.literal("TCP integration fixture complete"));
            clientConnection.disconnect(Component.literal("TCP integration fixture complete"));
            listener.close().syncUninterruptibly();
        }
    }
}
