package com.evanchubbuck.blindstone;

import io.netty.buffer.Unpooled;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;

/** Actual 26.3 server/codec tests; these do not exercise outgoing interception or connected clients. */
public final class BlindstoneGameTests {
    @GameTest
    public void concealedRoomsAndSolidTerrainHaveIdenticalPayloads(GameTestHelper helper) {
        var level = helper.getLevel();
        var room = fixture(level);
        var solid = solid(level);
        var policy = policy(level);
        var a = roundTrip(helper, ChunkFilter.snapshot(room, policy, Map.of()));
        var b = roundTrip(helper, ChunkFilter.snapshot(solid, policy, Map.of()));
        check(helper, Arrays.equals(bytes(a), bytes(b)), "Hidden room geometry must not change section bytes");
        check(helper, Arrays.equals(encode(level, a), encode(level, b)),
                "Hidden blocks, air, block entities and real heightmaps must not change chunk-data payload");
        helper.succeed();
    }

    @GameTest
    public void concealedPalettesAndCountsDescribeOnlyReplacementTerrain(GameTestHelper helper) {
        var level = helper.getLevel();
        var packet = ChunkFilter.snapshot(fixture(level), policy(level), Map.of());
        var decoded = decode(helper, packet);
        for (int sectionY = level.getMinSectionY(); sectionY < 4; sectionY++) {
            int i = sectionY - level.getMinSectionY();
            var section = decoded.sections.get(i);
            check(helper, decoded.nonAir.get(i) == 4096, "Fake sections must report 4096 solid blocks");
            check(helper, decoded.fluids.get(i) == 0, "Concealed water must not survive in fluid counts");
            check(helper, !section.hasOnlyAir(), "Fake air must serialize as solid terrain");
            List<BlockState> entries = new ArrayList<>();
            section.getStates().getAll(entries::add);
            check(helper, entries.size() == 1 && entries.getFirst().is(sectionY < 0 ? Blocks.DEEPSLATE : Blocks.STONE),
                    "Only the replacement may remain in fully concealed palettes");
        }
        check(helper, tags(packet).isEmpty(), "Hidden block-entity identifiers, positions and tags must be absent");
        helper.succeed();
    }

    @GameTest
    public void masksRevealExactlyAuthorizedBlocksAndMatchingBlockEntities(GameTestHelper helper) {
        var level = helper.getLevel();
        var real = fixture(level);
        var mask = mask(new BlockPos(2, 20, 3), new BlockPos(3, 20, 3));
        var packet = roundTrip(helper, ChunkFilter.snapshot(real, policy(level), Map.of(1, mask)));
        var decoded = decode(helper, packet);
        check(helper, block(level, decoded, 2, 20, 3).is(Blocks.CHEST), "Authorized chest block must be restored");
        check(helper, block(level, decoded, 3, 20, 3).isAir(), "Authorized room air must be restored");
        check(helper, block(level, decoded, 4, 20, 3).is(Blocks.STONE), "Adjacent hidden ore must remain stone");
        check(helper, block(level, decoded, 5, 20, 3).is(Blocks.STONE), "Adjacent hidden water must remain stone");
        check(helper, tags(packet).equals(List.of(new BlockPos(2, 20, 3))), "Only authorized block-entity entries are included");
        helper.succeed();
    }

    @GameTest
    public void heightmapsUseFilteredGeometry(GameTestHelper helper) {
        var level = helper.getLevel();
        var real = blank(level);
        var packet = ChunkFilter.snapshot(real, policy(level), Map.of());
        for (var entry : packet.getHeightmaps().entrySet()) {
            var map = new Heightmap(real, entry.getKey());
            map.setRawData(real, entry.getKey(), entry.getValue());
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                check(helper, map.getFirstAvailable(x, z) == 64, "Heightmaps must describe the fake solid cutoff");
            }
        }
        check(helper, !packet.getHeightmaps().isEmpty(), "Vanilla client heightmaps must be present");
        helper.succeed();
    }

    @GameTest
    public void verticalCutoffIsExclusiveAndUnprotectedDataIsPreserved(GameTestHelper helper) {
        var level = helper.getLevel();
        var real = blank(level);
        put(real, 1, 63, 1, Blocks.DIAMOND_ORE.defaultBlockState());
        put(real, 1, 64, 1, Blocks.CHEST.defaultBlockState());
        var pos = new BlockPos(1, 64, 1);
        var chest = new ChestBlockEntity(pos, Blocks.CHEST.defaultBlockState());
        chest.setLevel(level);
        real.getBlockEntities().put(pos, chest);
        var packet = roundTrip(helper, ChunkFilter.snapshot(real, policy(level), Map.of()));
        var decoded = decode(helper, packet);
        check(helper, block(level, decoded, 1, 63, 1).is(Blocks.STONE), "Y63 is protected");
        check(helper, block(level, decoded, 1, 64, 1).is(Blocks.CHEST), "Y64 is outside protection");
        check(helper, block(level, decoded, 1, level.getMinY(), 1).is(Blocks.DEEPSLATE), "World minimum is protected");
        check(helper, tags(packet).contains(pos), "Unprotected block entities must remain");
        helper.succeed();
    }

    @GameTest
    public void oneViewerCannotRevealAnotherViewersChunk(GameTestHelper helper) {
        var level = helper.getLevel();
        var real = fixture(level);
        var before = encode(level, ChunkFilter.snapshot(real, policy(level), Map.of()));
        var visible = ChunkFilter.snapshot(real, policy(level), Map.of(1, mask(new BlockPos(2, 20, 3))));
        var after = encode(level, ChunkFilter.snapshot(real, policy(level), Map.of()));
        check(helper, Arrays.equals(before, after), "A second viewer must keep their original concealed output");
        check(helper, !Arrays.equals(before, encode(level, visible)), "Authorized viewer must receive different output");
        helper.succeed();
    }

    @GameTest
    public void filteringDoesNotMutateLiveWorldChunk(GameTestHelper helper) {
        var level = helper.getLevel();
        var real = level.getChunkAt(helper.absolutePos(BlockPos.ZERO));
        var sectionsBefore = real.getSections().clone();
        var entitiesBefore = new HashMap<>(real.getBlockEntities());
        boolean unsaved = real.isUnsaved();
        byte[] before = encode(level, new ClientboundLevelChunkPacketData(real));
        var filtered = ChunkFilter.snapshot(real, policy(level), Map.of());
        check(helper, filtered != null, "Filter must produce isolated data");
        check(helper, Arrays.equals(before, encode(level, new ClientboundLevelChunkPacketData(real))),
                "Real block states, biomes, counts, heightmaps and block entity tags must be unchanged");
        check(helper, Arrays.equals(sectionsBefore, real.getSections()), "Live section objects must be unchanged");
        check(helper, entitiesBefore.equals(real.getBlockEntities()), "Live block-entity objects must be unchanged");
        check(helper, real.isUnsaved() == unsaved, "Filtering must not dirty the live chunk");
        helper.succeed();
    }

    @GameTest
    public void mutableInputsDoNotEscapeIntoMasksOrQueuedPlans(GameTestHelper helper) {
        BitSet bits = new BitSet();
        bits.set(SectionVisibility.index(1, 2, 3));
        var mask = SectionVisibility.from(bits);
        bits.set(0, 4096);
        var input = new HashMap<Integer, SectionVisibility>();
        input.put(0, mask);
        var viewer = viewer();
        viewer.track(1);
        var plan = viewer.queue(1, input);
        input.clear();
        check(helper, plan.sections().size() == 1 && plan.sections().get(0).count() == 1,
                "Queued authorization must be an immutable snapshot");
        expectFailure(helper, () -> plan.sections().clear(), UnsupportedOperationException.class);
        helper.succeed();
    }

    @GameTest
    public void queuedVisibilityIsNotDeliveredUntilCommit(GameTestHelper helper) {
        var viewer = viewer();
        viewer.track(1);
        var plan = viewer.queue(1, Map.of(0, mask(new BlockPos(1, 2, 3))));
        check(helper, viewer.phase(1) == ViewerState.Phase.REVEAL_QUEUED, "Plan enters queued phase");
        check(helper, viewer.delivered(1, 0).count() == 0, "Queued masks must not authorize real updates");
        check(helper, viewer.markDelivered(plan), "Current plan may commit after delivery");
        check(helper, viewer.phase(1) == ViewerState.Phase.REVEALED && viewer.delivered(1, 0).count() == 1,
                "Only sent masks enter revealed phase");
        check(helper, !viewer.markDelivered(plan), "A delivered plan cannot be replayed");
        helper.succeed();
    }

    @GameTest
    public void successiveRevealsRetainOnlyPreviouslyDeliveredBlocks(GameTestHelper helper) {
        var viewer = viewer();
        viewer.track(1);
        viewer.markDelivered(viewer.queue(1, Map.of(0, mask(new BlockPos(1, 2, 3)))));
        viewer.markDelivered(viewer.queue(1, Map.of(0, mask(new BlockPos(4, 5, 6)))));
        var delivered = viewer.delivered(1, 0);
        check(helper, delivered.count() == 2 && delivered.isRevealed(1, 2, 3) && delivered.isRevealed(4, 5, 6),
                "Successive deliveries must accumulate exact block masks");
        check(helper, !delivered.isRevealed(2, 2, 3), "A reveal cannot authorize adjacent blocks");
        helper.succeed();
    }

    @GameTest
    public void negativeChunkAndSectionCoordinatesUseLocalMasks(GameTestHelper helper) {
        var level = helper.getLevel();
        var real = new LevelChunk(level, new ChunkPos(-1, -1));
        var pos = new BlockPos(-14, -12, -13);
        put(real, pos.getX() & 15, pos.getY(), pos.getZ() & 15, Blocks.CHEST.defaultBlockState());
        var chest = new ChestBlockEntity(pos, Blocks.CHEST.defaultBlockState());
        chest.setLevel(level);
        real.getBlockEntities().put(pos, chest);
        var packet = roundTrip(helper, ChunkFilter.snapshot(real, policy(level), Map.of(-1, mask(pos))));
        var decoded = decode(helper, packet);
        check(helper, block(level, decoded, 2, -12, 3).is(Blocks.CHEST), "Negative section must use floor division");
        check(helper, block(level, decoded, 3, -12, 3).is(Blocks.DEEPSLATE), "Adjacent negative coordinate remains concealed");
        List<BlockPos> positions = new ArrayList<>();
        packet.forEachBlockEntityTag(-1, -1, (p, type, tag) -> positions.add(p.immutable()));
        check(helper, positions.equals(List.of(pos)), "Negative block-entity coordinates must round trip exactly");
        helper.succeed();
    }

    @GameTest
    public void geometryChangesRejectStaleWorkWithoutForgettingDeliveredKnowledge(GameTestHelper helper) {
        var viewer = viewer();
        viewer.track(1);
        viewer.markDelivered(viewer.queue(1, Map.of(0, mask(new BlockPos(1, 2, 3)))));
        var stale = viewer.queue(1, Map.of(0, mask(new BlockPos(4, 5, 6))));
        viewer.geometryChanged(1);
        check(helper, !viewer.isCurrent(stale) && !viewer.markDelivered(stale), "Old geometry must not authorize data");
        check(helper, viewer.delivered(1, 0).count() == 1, "Previously sent knowledge persists during tracking");
        check(helper, viewer.pendingCount() == 0, "Stale queued work must be discarded");
        helper.succeed();
    }

    @GameTest
    public void unloadReloadResetAndDifferentViewersRejectOldTickets(GameTestHelper helper) {
        var a = viewer();
        var b = viewer();
        a.track(1);
        b.track(1);
        var plan = a.queue(1, Map.of(0, mask(new BlockPos(1, 2, 3))));
        check(helper, !b.markDelivered(plan), "Plans cannot cross viewers");
        a.untrack(1);
        check(helper, a.phase(1) == ViewerState.Phase.UNTRACKED, "Unload removes active chunk state");
        a.track(1);
        check(helper, !a.markDelivered(plan), "Reload of the same coordinate cannot revive old work");
        var resetPlan = a.queue(1, plan.sections());
        a.reset();
        a.track(1);
        check(helper, !a.markDelivered(resetPlan), "Session reset invalidates work even after retracking");
        check(helper, a.delivered(1, 0).count() == 0, "Rejoin starts concealed");
        helper.succeed();
    }

    @GameTest
    public void replacementPlansAndSaturationDoNotGrantVisibility(GameTestHelper helper) {
        var viewer = new ViewerState(1, -4, 4);
        viewer.track(1);
        var old = viewer.queue(1, Map.of(0, mask(new BlockPos(1, 2, 3))));
        var current = viewer.queue(1, Map.of(0, mask(new BlockPos(4, 5, 6))));
        check(helper, !viewer.markDelivered(old) && viewer.isCurrent(current), "Replacing pending work invalidates old plans");
        check(helper, !viewer.track(2), "Full memory budget must refuse additional chunks");
        check(helper, viewer.trackedCount() == 1 && viewer.pendingCount() == 1, "Pending work is bounded per chunk");
        check(helper, viewer.phase(2) == ViewerState.Phase.UNTRACKED && viewer.delivered(2, 0).count() == 0,
                "Overload cannot reveal extra terrain");
        expectFailure(helper, () -> viewer.queue(2, Map.of()), IllegalStateException.class);
        helper.succeed();
    }

    @GameTest
    public void invalidBoundsAndMasksFailInsteadOfReturningRealData(GameTestHelper helper) {
        expectFailure(helper, () -> new ConcealmentPolicy(64, -64), IllegalArgumentException.class);
        expectFailure(helper, () -> new ConcealmentPolicy(-63, 64), IllegalArgumentException.class);
        expectFailure(helper, () -> new ViewerState(Integer.MAX_VALUE, -4, 4), IllegalArgumentException.class);
        BitSet oversized = new BitSet();
        oversized.set(4096);
        expectFailure(helper, () -> SectionVisibility.from(oversized), IllegalArgumentException.class);
        expectFailure(helper, () -> SectionVisibility.index(16, 0, 0), IllegalArgumentException.class);
        var level = helper.getLevel();
        expectFailure(helper, () -> ChunkFilter.snapshot(blank(level), new ConcealmentPolicy(-80, 64), Map.of()), IllegalArgumentException.class);
        expectFailure(helper, () -> ChunkFilter.snapshot(blank(level), policy(level), Map.of(4, SectionVisibility.none())), IllegalArgumentException.class);
        var viewer = viewer();
        viewer.track(1);
        expectFailure(helper, () -> viewer.queue(1, Map.of(4, SectionVisibility.none())), IllegalArgumentException.class);
        helper.succeed();
    }

    @GameTest
    public void filteringRejectsBackgroundWorldAccess(GameTestHelper helper) throws InterruptedException {
        var level = helper.getLevel();
        var real = blank(level);
        var policy = policy(level);
        var failure = new AtomicReference<Throwable>();
        Thread worker = Thread.startVirtualThread(() -> {
            try { ChunkFilter.snapshot(real, policy, Map.of()); }
            catch (Throwable expected) { failure.set(expected); }
        });
        worker.join(5000);
        check(helper, !worker.isAlive() && failure.get() instanceof IllegalStateException, "World access must remain on owning server thread");
        helper.succeed();
    }

    @GameTest
    public void missingPacketHooksRefuseNormalStartup(GameTestHelper helper) {
        expectFailure(helper, () -> BlindstoneMod.requireDevelopmentMode(false), IllegalStateException.class);
        helper.succeed();
    }

    @GameTest
    public void transformedAdmissionHookDeniesDevelopmentLogins(GameTestHelper helper) {
        var rejection = helper.getLevel().getServer().getPlayerList().canPlayerLogin(
                new InetSocketAddress("127.0.0.1", 25565), new NameAndId(UUID.randomUUID(), "BlindstoneProbe"));
        check(helper, rejection != null && rejection.getString().equals(BlindstoneMod.ADMISSION_DENIED),
                "Mandatory transformed login hook must deny players before any real world is delivered");
        helper.succeed();
    }

    private static ViewerState viewer() { return new ViewerState(16, -4, 4); }

    private static ConcealmentPolicy policy(ServerLevel level) { return new ConcealmentPolicy(level.getMinY(), 64); }

    private static SectionVisibility mask(BlockPos... positions) {
        BitSet bits = new BitSet();
        for (var p : positions) bits.set(SectionVisibility.index(p.getX() & 15, p.getY() & 15, p.getZ() & 15));
        return SectionVisibility.from(bits);
    }

    private static LevelChunk blank(ServerLevel level) { return new LevelChunk(level, new ChunkPos(0, 0)); }

    private static LevelChunk solid(ServerLevel level) {
        var real = blank(level);
        for (int sectionY = level.getMinSectionY(); sectionY < 4; sectionY++) {
            int i = sectionY - level.getMinSectionY();
            real.getSections()[i] = new LevelChunkSection(new PalettedContainer<>(
                    (sectionY < 0 ? Blocks.DEEPSLATE : Blocks.STONE).defaultBlockState(),
                    level.palettedContainerFactory().blockStatesStrategy()), real.getSections()[i].getBiomes().copy());
        }
        return real;
    }

    private static LevelChunk fixture(ServerLevel level) {
        var real = solid(level);
        for (int x = 1; x <= 8; x++) for (int y = 18; y <= 22; y++) for (int z = 2; z <= 6; z++) {
            put(real, x, y, z, Blocks.AIR.defaultBlockState());
        }
        // A sealed-room analogue, an ore, a fluid, storage, redstone, a torch and section/chunk edges.
        put(real, 2, 20, 3, Blocks.CHEST.defaultBlockState());
        put(real, 4, 20, 3, Blocks.DIAMOND_ORE.defaultBlockState());
        put(real, 5, 20, 3, Blocks.WATER.defaultBlockState());
        put(real, 6, 20, 3, Blocks.REDSTONE_BLOCK.defaultBlockState());
        put(real, 7, 20, 3, Blocks.TORCH.defaultBlockState());
        put(real, 15, -1, 15, Blocks.GOLD_BLOCK.defaultBlockState());
        put(real, 0, 0, 0, Blocks.CAVE_AIR.defaultBlockState());
        var pos = new BlockPos(2, 20, 3);
        var chest = new ChestBlockEntity(pos, Blocks.CHEST.defaultBlockState());
        chest.setLevel(level);
        real.getBlockEntities().put(pos, chest);
        return real;
    }

    private static void put(LevelChunk chunk, int x, int y, int z, BlockState state) {
        chunk.getSection(chunk.getSectionIndex(y)).setBlockState(x, y & 15, z, state);
    }

    private static List<BlockPos> tags(ClientboundLevelChunkPacketData packet) {
        List<BlockPos> positions = new ArrayList<>();
        packet.forEachBlockEntityTag(0, 0, (pos, type, tag) -> positions.add(pos.immutable()));
        return positions;
    }

    private static byte[] bytes(ClientboundLevelChunkPacketData packet) {
        var buf = packet.getReadBuffer();
        try {
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally { buf.release(); }
    }

    private static byte[] encode(ServerLevel level, ClientboundLevelChunkPacketData packet) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        try {
            ClientboundLevelChunkPacketData.STREAM_CODEC.encode(buffer, packet);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally { buffer.release(); }
    }

    private static ClientboundLevelChunkPacketData roundTrip(GameTestHelper helper, ClientboundLevelChunkPacketData packet) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(encode(helper.getLevel(), packet)), helper.getLevel().registryAccess());
        try {
            var decoded = ClientboundLevelChunkPacketData.STREAM_CODEC.decode(buffer);
            check(helper, !buffer.isReadable(), "Codec must consume the entire payload");
            return decoded;
        } finally { buffer.release(); }
    }

    private record Decoded(List<LevelChunkSection> sections, List<Integer> nonAir, List<Integer> fluids) { }

    private static Decoded decode(GameTestHelper helper, ClientboundLevelChunkPacketData packet) {
        FriendlyByteBuf buffer = packet.getReadBuffer();
        try {
            List<LevelChunkSection> sections = new ArrayList<>();
            List<Integer> nonAir = new ArrayList<>();
            List<Integer> fluids = new ArrayList<>();
            for (int i = 0; i < helper.getLevel().getSectionsCount(); i++) {
                nonAir.add((int) buffer.getShort(buffer.readerIndex()));
                fluids.add((int) buffer.getShort(buffer.readerIndex() + 2));
                var section = new LevelChunkSection(helper.getLevel().palettedContainerFactory());
                section.read(buffer);
                sections.add(section);
            }
            check(helper, !buffer.isReadable(), "Section buffer must contain exactly the world's sections");
            return new Decoded(sections, nonAir, fluids);
        } finally { buffer.release(); }
    }

    private static BlockState block(ServerLevel level, Decoded decoded, int x, int y, int z) {
        return decoded.sections.get(level.getSectionIndex(y)).getBlockState(x, y & 15, z);
    }

    private static void expectFailure(GameTestHelper helper, Runnable action, Class<? extends Throwable> expected) {
        Throwable failure = null;
        try { action.run(); } catch (Throwable caught) { failure = caught; }
        check(helper, expected.isInstance(failure), "Operation must fail with " + expected.getSimpleName());
    }

    private static void check(GameTestHelper helper, boolean condition, String message) {
        helper.assertTrue(condition, message);
    }
}
