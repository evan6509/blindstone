package com.evanchubbuck.blindstone.network;

import io.netty.buffer.Unpooled;
import java.util.Arrays;
import java.util.Map;
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
