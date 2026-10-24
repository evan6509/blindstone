package com.evanchubbuck.blindstone.network;

import io.netty.buffer.Unpooled;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.HashMap;
import java.util.BitSet;
import com.evanchubbuck.blindstone.ConcealmentPolicy;
import com.evanchubbuck.blindstone.SectionVisibility;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

/** Pure filtered-geometry fixtures; never consumes a real server light array. */
public final class FilteredLightGameTests {
    private static final FilteredLight.Budget BUDGET = new FilteredLight.Budget(1_000_000, 50_000_000, 5_000_000_000L);
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();

    @GameTest
    public void mountainTorchAndShaftUseProductionFilteredLighting(GameTestHelper helper) {
        var level = helper.getLevel();
        var center = ChunkPos.containing(helper.absolutePos(new BlockPos(1, 1, 1)));
        var saved = new HashMap<BlockPos, BlockState>();
        var policy = com.evanchubbuck.blindstone.config.BlindstoneConfig.defaults()
                .forWorldBounds(level.getMinY(), level.getMaxY() + 1).policy();
        var context = new PacketFilter.Context() {
            public ServerLevel level() { return level; }
            public ConcealmentPolicy policy() { return policy; }
            public boolean isAuthorized(BlockPos pos) { return false; }
            public boolean isEntityVisible(int id) { return false; }
            public Map<Integer, SectionVisibility> masks(ChunkPos chunk) { return Map.of(); }
        };
        var source = new BlockPos(center.x() * 16 + 8, 193, center.z() * 16 + 8);
        try {
            for (int y = 190; y <= 208; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                var pos = new BlockPos(center.x() * 16 + x, y, center.z() * 16 + z);
                saved.put(pos, level.getBlockState(pos));
                boolean room = y >= 192 && y <= 194 && x >= 6 && x <= 10 && z >= 6 && z <= 10;
                boolean shaft = x == 10 && z == 10 && y >= 192;
                level.setBlock(pos, room || shaft ? AIR : STONE, 2);
            }
            level.setBlock(source, Blocks.TORCH.defaultBlockState(), 2);
            var withTorch = FilteredLight.reconstruct(center, context, BUDGET);
            helper.assertTrue(lightAt(withTorch, false, level, source) == 0, "Hidden mountain torch is dark");
            helper.assertTrue(lightAt(withTorch, true, level, source) == 0, "Sky-connected narrow shaft cannot light private room");
            helper.assertTrue(lightAt(withTorch, true, level, source.atY(210)) == 15, "Exterior sky stays bright");
            level.setBlock(source, STONE, 2);
            helper.assertTrue(Arrays.equals(encode(withTorch), encode(FilteredLight.reconstruct(center, context, BUDGET))),
                    "Hidden mountain torch cannot change any outgoing light byte");
        } finally { saved.forEach((pos, state) -> level.setBlock(pos, state, 2)); }
        helper.succeed();
    }

    @GameTest
    public void directSkyIsBrightAboveFilteredSolidCutoffAndDarkBelow(GameTestHelper helper) {
        var result = FilteredLight.reconstructFiltered(0, 32, (x, y, z) -> y < 16 ? STONE : AIR, BUDGET);
        helper.assertTrue(result.skyAt(0, 31, 0) == 15 && result.skyAt(15, 16, 15) == 15,
                "Public sky remains fully bright above filtered terrain");
        helper.assertTrue(result.skyAt(0, 15, 0) == 0 && result.skyAt(15, 0, 15) == 0,
                "Filtered solid cutoff blocks skylight at every chunk edge");
        helper.succeed();
    }

    @GameTest
    public void authorizedTorchReconstructsLightAcrossChunkAndSectionBoundaries(GameTestHelper helper) {
        var result = FilteredLight.reconstructFiltered(0, 32, (x, y, z) -> {
            if (y == 15 && z == 8 && x == -1) return Blocks.TORCH.defaultBlockState();
            return y >= 14 && y <= 17 && z >= 7 && z <= 9 ? AIR : STONE;
        }, BUDGET);
        helper.assertTrue(result.blockAt(-1, 15, 8) == 14 && result.blockAt(0, 15, 8) == 13
                && result.blockAt(0, 16, 8) == 12, "Visible torch spills through actual air across both boundaries");
        helper.assertTrue(result.blockAt(0, 13, 8) == 0, "Opaque geometry stops light");
        helper.succeed();
    }

    @GameTest
    public void filteredHiddenTorchCannotInfluenceVisibleAirAcrossBoundary(GameTestHelper helper) {
        // Both hidden real-world variants become the same STONE before this reconstruction boundary.
        FilteredLight.StateSource filtered = (x, y, z) -> y == 8 && z == 8 && x >= 0 && x <= 5 ? AIR : STONE;
        var a = FilteredLight.reconstructFiltered(0, 16, filtered, BUDGET);
        var b = FilteredLight.reconstructFiltered(0, 16, filtered, BUDGET);
        helper.assertTrue(a.blockAt(0, 8, 8) == 0 && Arrays.equals(encode(a.packet()), encode(b.packet())),
                "Identical filtered terrain has identical light regardless of hidden source variant");
        helper.succeed();
    }

    @GameTest
    public void lightOcclusionUsesActualSlabFacesAndTransparentStates(GameTestHelper helper) {
        var top = Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP);
        var result = FilteredLight.reconstructFiltered(0, 16, (x, y, z) -> {
            if (x == 8 && z == 8 && y == 9) return Blocks.GLOWSTONE.defaultBlockState();
            if (x == 8 && z == 8 && y == 8) return top;
            if (x == 8 && z == 8 && y == 7) return AIR;
            if (x == 9 && z == 8 && y == 9) return Blocks.GLASS.defaultBlockState();
            return STONE;
        }, BUDGET);
        helper.assertTrue(result.blockAt(9, 9, 8) == 14, "Glass transmits permitted light");
        helper.assertTrue(result.blockAt(8, 8, 8) == 0 && result.blockAt(8, 7, 8) == 0,
                "Top slab's upper face blocks a source directly above it");
        helper.succeed();
    }

    @GameTest
    public void skylightSpillsIntoExposedCaveWithoutLeakingThroughRoof(GameTestHelper helper) {
        var result = FilteredLight.reconstructFiltered(0, 32, (x, y, z) -> {
            if (y >= 20 || x < 0) return AIR;
            if (y == 18 && z == 8 && x <= 5) return AIR;
            return STONE;
        }, BUDGET);
        helper.assertTrue(result.skyAt(0, 18, 8) == 14 && result.skyAt(1, 18, 8) == 13,
                "Public open sky enters an exposed cave laterally with vanilla attenuation");
        helper.assertTrue(result.skyAt(0, 19, 8) == 0, "Opaque roof remains dark despite sunlight immediately above");
        helper.succeed();
    }

    @GameTest
    public void attenuationHaloCoversAllContributingBlockSources(GameTestHelper helper) {
        var result = FilteredLight.reconstructFiltered(0, 16, (x, y, z) -> {
            if (x == -14 && y == 8 && z == 8) return Blocks.GLOWSTONE.defaultBlockState();
            return y == 8 && z == 8 ? AIR : STONE;
        }, BUDGET);
        helper.assertTrue(result.blockAt(0, 8, 8) == 1 && result.blockAt(1, 8, 8) == 0,
                "Level15 contributes through14 steps and is exhausted at15");
        helper.succeed();
    }

    @GameTest
    public void packetCodecIncludesEverySectionAndBothWorldBoundaries(GameTestHelper helper) {
        var result = FilteredLight.reconstructFiltered(0, 32, (x, y, z) -> AIR, BUDGET);
        var buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(encode(result.packet())));
        try {
            var decoded = ClientboundLightUpdatePacketData.STREAM_CODEC.decode(buffer);
            var sky = (java.util.BitSet) decoded.skyYMask().clone();
            sky.or(decoded.emptySkyYMask());
            var block = (java.util.BitSet) decoded.blockYMask().clone();
            block.or(decoded.emptyBlockYMask());
            helper.assertTrue(!buffer.isReadable() && sky.cardinality() == 4 && block.cardinality() == 4,
                    "Every section and both outside-world boundaries must serialize explicitly");
            helper.assertTrue(decoded.emptySkyYMask().get(0) && decoded.skyYMask().get(3),
                    "Bottom boundary is opaque and top boundary is public sunlight");
        } finally { buffer.release(); }
        helper.succeed();
    }

    @GameTest
    public void realHiddenTorchNeverChangesReconstructedOutgoingLight(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(1, 2, 1));
        var previous = level.getBlockState(pos);
        var context = new PacketFilter.Context() {
            @Override public ServerLevel level() { return level; }
            @Override public ConcealmentPolicy policy() { return new ConcealmentPolicy(level.getMinY(), 64); }
            @Override public boolean isAuthorized(BlockPos position) { return false; }
            @Override public boolean isEntityVisible(int id) { return false; }
            @Override public Map<Integer, SectionVisibility> masks(ChunkPos chunk) { return Map.of(); }
        };
        helper.assertTrue(pos.getY() < 64, "Fixture must lie within protected bounds");
        try {
            level.setBlock(pos, Blocks.TORCH.defaultBlockState(), 2);
            byte[] source = encode(FilteredLight.reconstruct(ChunkPos.containing(pos), context, BUDGET));
            level.setBlock(pos, STONE, 2);
            byte[] solid = encode(FilteredLight.reconstruct(ChunkPos.containing(pos), context, BUDGET));
            helper.assertTrue(Arrays.equals(source, solid), "Actual hidden emission cannot affect any outgoing light section");
        } finally { level.setBlock(pos, previous, 2); }
        helper.succeed();
    }

    @GameTest
    public void budgetsRejectAllocationAndPropagationWithoutReturningRealLight(GameTestHelper helper) {
        boolean cells = false;
        try { FilteredLight.reconstructFiltered(0, 32, (x, y, z) -> AIR, new FilteredLight.Budget(1, 100, 1_000_000)); }
        catch (FilteredLight.BudgetExceededException expected) { cells = true; }
        boolean work = false;
        try { FilteredLight.reconstructFiltered(0, 16, (x, y, z) -> AIR, new FilteredLight.Budget(100_000, 1, 5_000_000_000L)); }
        catch (FilteredLight.BudgetExceededException expected) { work = true; }
        helper.assertTrue(cells && work, "Insufficient cell/propagation budgets must abort safely");
        helper.succeed();
    }

    @GameTest
    public void schedulerPublishesOnlyCompletedGenerationAndPreservesOrdinarySunlight(GameTestHelper helper) {
        var level = helper.getLevel();
        var chunk = ChunkPos.containing(helper.absolutePos(BlockPos.ZERO));
        var memory = new LightScheduler.MemoryBudget(32 * 1024 * 1024);
        var scheduler = new LightScheduler(2, 4, memory);
        try {
            helper.assertTrue(scheduler.request(chunk, context(level, Set.of()), 1), "Initial light request fits bounded memory");
            scheduler.step(128, 1_000_000);
            helper.assertTrue(scheduler.getReady(chunk, 1) == null, "Partial capture must never publish light");
            helper.assertTrue(scheduler.request(chunk, context(level, Set.of()), 2), "New generation replaces stale partial work");
            complete(helper, scheduler);
            var ready = scheduler.getReady(chunk, 2);
            helper.assertTrue(ready != null && scheduler.getReady(chunk, 1) == null, "Only matching completed generation may publish");
            var skyPos = new BlockPos(chunk.x() * 16 + 8, level.getMaxY(), chunk.z() * 16 + 8);
            helper.assertTrue(lightAt(ready, true, level, skyPos) == level.getBrightness(net.minecraft.world.level.LightLayer.SKY, skyPos),
                    "Safe upper-world sunlight remains ordinary vanilla light");
            helper.assertTrue(scheduler.lastCompletedCells() == 46 * 46 * 16,
                    "Fully concealed initial chunks solve only the 16-block public boundary band");
            org.slf4j.LoggerFactory.getLogger("blindstone-light-benchmark").info(
                    "Initial light fixture: cells={}, operations={}, computeMillis={}", scheduler.lastCompletedCells(),
                    scheduler.completedOperations(), scheduler.completedNanos() / 1_000_000.0);
        } finally { scheduler.reset(); }
        helper.assertTrue(memory.usedBytes() == 0, "Reset releases jobs and cache allocations");
        helper.succeed();
    }

    @GameTest
    public void schedulerReconstructsAuthorizedTorchAndKeepsHiddenTorchDark(GameTestHelper helper) {
        var level = helper.getLevel();
        var torch = helper.absolutePos(new BlockPos(2, 2, 2));
        var previous = new HashMap<BlockPos, BlockState>();
        var visible = new HashSet<BlockPos>();
        for (int y = 0; y < 3; y++) for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) {
            var pos = torch.offset(x, y, z);
            previous.put(pos, level.getBlockState(pos));
            visible.add(pos);
            level.setBlock(pos, AIR, 2);
        }
        var floor = torch.below();
        previous.put(floor, level.getBlockState(floor));
        level.setBlock(floor, STONE, 2);
        level.setBlock(torch, Blocks.TORCH.defaultBlockState(), 2);
        var memory = new LightScheduler.MemoryBudget(32 * 1024 * 1024);
        var scheduler = new LightScheduler(1, 2, memory);
        var chunk = ChunkPos.containing(torch);
        try {
            helper.assertTrue(scheduler.request(chunk, context(level, visible), 1), "Authorized light request is accepted");
            complete(helper, scheduler);
            var permitted = scheduler.getReady(chunk, 1);
            var adjacent = (torch.getX() & 15) == 15 ? torch.west() : torch.east();
            helper.assertTrue(lightAt(permitted, false, level, torch) == 14
                    && lightAt(permitted, false, level, adjacent) == 13, "Permitted torch illuminates ordinary revealed room air");
            scheduler.invalidate(chunk);
            helper.assertTrue(scheduler.request(chunk, context(level, Set.of()), 2), "Concealed generation may be reconstructed");
            complete(helper, scheduler);
            helper.assertTrue(lightAt(scheduler.getReady(chunk, 2), false, level, torch) == 0,
                    "Real hidden torch never contributes to filtered lighting");
        } finally {
            scheduler.reset();
            previous.forEach((pos, state) -> level.setBlock(pos, state, 2));
        }
        helper.succeed();
    }

    @GameTest
    public void netherProductionLightAndUpdatesUseNativeOrePolicy(GameTestHelper helper) {
        var level=helper.getLevel().getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        var chunk=level.getChunk(0,0);var ore=new BlockPos(8,81,8);var lamp=ore.below();
        var oldOre=level.getBlockState(ore);var oldLamp=level.getBlockState(lamp);
        var policy=com.evanchubbuck.blindstone.config.BlindstoneConfig.defaults().policyFor(level);
        var context=new PacketFilter.Context() {
            public ServerLevel level(){return level;}
            public ConcealmentPolicy policy(){return policy;}
            public boolean isAuthorized(BlockPos pos){return !policy.protects(level,pos);}
            public boolean isEntityVisible(int id){return false;}
            public Map<Integer,SectionVisibility> masks(ChunkPos pos){return Map.of();}
        };
        var scheduler=new LightScheduler(1,2,new LightScheduler.MemoryBudget(32*1024*1024));
        try {
            level.setBlock(ore,Blocks.ANCIENT_DEBRIS.defaultBlockState(),2);level.setBlock(lamp,Blocks.GLOWSTONE.defaultBlockState(),2);
            var update=(net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket)PacketFilter.filter(
                    new net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(ore,level.getBlockState(ore)),context);
            helper.assertTrue(update!=null && update.getBlockState().is(Blocks.NETHERRACK),"Actual Nether update conceals native target");
            scheduler.request(chunk.getPos(),context,1);complete(helper,scheduler);
            var light=scheduler.getReady(chunk.getPos(),1);
            helper.assertTrue(lightAt(light,true,level,lamp)==0 && lightAt(light,false,level,lamp)==15,
                    "Native Nether light has no skylight and preserves public emission");
        } finally {scheduler.reset();level.setBlock(ore,oldOre,2);level.setBlock(lamp,oldLamp,2);}
        helper.succeed();
    }

    @GameTest
    public void sharedJobsRequireIdenticalPrivateMasksAndReleaseTheirMemory(GameTestHelper helper) {
        var level=helper.getLevel();
        var base=helper.absolutePos(BlockPos.ZERO);
        var torch=new BlockPos(base.getX()+2,30,base.getZ()+2);
        var adjacent=torch.east();
        var oldTorch=level.getBlockState(torch);var oldAir=level.getBlockState(adjacent);
        var memory=new LightScheduler.MemoryBudget(32*1024*1024);
        var first=new LightScheduler(2,4,memory);var same=new LightScheduler(2,4,memory);var observer=new LightScheduler(2,4,memory);
        try {
            level.setBlock(torch,Blocks.TORCH.defaultBlockState(),2);level.setBlock(adjacent,AIR,2);
            var chunk=ChunkPos.containing(torch);
            first.request(chunk,context(level,Set.of(torch,adjacent)),1);
            long once=memory.usedBytes();
            same.request(chunk,context(level,Set.of(torch,adjacent)),7);
            helper.assertTrue(memory.usedBytes()==once,"Equivalent masks from independent sessions share one bounded job");
            observer.request(chunk,context(level,Set.of()),1);
            helper.assertTrue(memory.usedBytes()>once,"Different private masks allocate independent work");
            complete(helper,first);complete(helper,same);complete(helper,observer);
            helper.assertTrue(first.getReady(chunk,1)==same.getReady(chunk,7),"Identical filtered views share an immutable result");
            helper.assertTrue(lightAt(first.getReady(chunk,1),false,level,torch)==14
                    && lightAt(observer.getReady(chunk,1),false,level,torch)==0,"Shared cache never transfers a private torch to another viewer");
            first.reset();
            helper.assertTrue(same.getReady(chunk,7)!=null && memory.usedBytes()>0,"One viewer leaving preserves other owners");
        } finally {
            first.reset();same.reset();observer.reset();
            level.setBlock(torch,oldTorch,2);level.setBlock(adjacent,oldAir,2);
        }
        helper.assertTrue(memory.usedBytes()==0,"Last owner releases all shared memory");
        helper.succeed();
    }

    @GameTest
    public void schedulerInvalidatesNeighborHalosAndBoundsGlobalMemory(GameTestHelper helper) {
        var level = helper.getLevel();
        var chunk = ChunkPos.containing(helper.absolutePos(BlockPos.ZERO));
        var context = context(level, Set.of());
        var memory = new LightScheduler.MemoryBudget(32 * 1024 * 1024);
        var scheduler = new LightScheduler(1, 2, memory);
        try {
            helper.assertTrue(scheduler.request(chunk, context, 1), "First job enters bounded scheduler");
            helper.assertTrue(!scheduler.request(new ChunkPos(chunk.x() + 1, chunk.z()), context, 1), "Per-viewer job capacity is enforced");
            complete(helper, scheduler);
            helper.assertTrue(memory.usedBytes() > 0, "Ready cache counts against shared memory");
            scheduler.invalidate(new ChunkPos(chunk.x() + 1, chunk.z()));
            helper.assertTrue(scheduler.readyCount() == 0 && scheduler.getReady(chunk, 1) == null && memory.usedBytes() == 0,
                    "A neighboring geometry change invalidates dependent ready lighting and releases memory");
            var tiny = new LightScheduler(1, 1, new LightScheduler.MemoryBudget(262144));
            helper.assertTrue(!tiny.request(chunk, context, 1) && tiny.pendingCount() == 0,
                    "Global memory exhaustion defers before allocating a job");
        } finally { scheduler.reset(); }
        helper.succeed();
    }

    private static void complete(GameTestHelper helper, LightScheduler scheduler) {
        int slices = 0;
        while (scheduler.pendingCount() != 0 && slices++ < 10000) {
            int used = scheduler.step(8192, 2_000_000);
            helper.assertTrue(used <= 8198, "Cooperative work cannot exceed a slice by more than one six-neighbor cell");
        }
        helper.assertTrue(scheduler.pendingCount() == 0, "Bounded fixture must finish");
    }

    private static PacketFilter.Context context(ServerLevel level, Set<BlockPos> visible) {
        var masks = new HashMap<Long, Map<Integer, SectionVisibility>>();
        var bits = new HashMap<Long, Map<Integer, BitSet>>();
        for (var pos : visible) bits.computeIfAbsent(ChunkPos.containing(pos).pack(), ignored -> new HashMap<>())
                .computeIfAbsent(Math.floorDiv(pos.getY(), 16), ignored -> new BitSet())
                .set(SectionVisibility.index(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15));
        bits.forEach((chunk, sections) -> {
            var result = new HashMap<Integer, SectionVisibility>();
            sections.forEach((y, value) -> result.put(y, SectionVisibility.from(value)));
            masks.put(chunk, Map.copyOf(result));
        });
        return new PacketFilter.Context() {
            @Override public ServerLevel level() { return level; }
            @Override public ConcealmentPolicy policy() { return new ConcealmentPolicy(level.getMinY(), 64); }
            @Override public boolean isAuthorized(BlockPos pos) { return visible.contains(pos); }
            @Override public boolean isEntityVisible(int id) { return false; }
            @Override public Map<Integer, SectionVisibility> masks(ChunkPos chunk) { return masks.getOrDefault(chunk.pack(), Map.of()); }
        };
    }

    private static int lightAt(ClientboundLightUpdatePacketData data, boolean sky, ServerLevel level, BlockPos pos) {
        if (data == null) throw new IllegalStateException("Fixture light is not ready");
        int section = Math.floorDiv(pos.getY(), 16) - level.getMinSectionY() + 1;
        var mask = sky ? data.skyYMask() : data.blockYMask();
        if (!mask.get(section)) return 0;
        int update = mask.get(0, section).cardinality();
        byte[] array = (sky ? data.skyUpdates() : data.blockUpdates()).get(update);
        int nibble = ((pos.getY() & 15) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15);
        return array[nibble >> 1] >> ((nibble & 1) * 4) & 15;
    }

    private static byte[] encode(ClientboundLightUpdatePacketData data) {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ClientboundLightUpdatePacketData.STREAM_CODEC.encode(buffer, data);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally { buffer.release(); }
    }
}
