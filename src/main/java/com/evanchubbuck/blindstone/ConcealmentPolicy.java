package com.evanchubbuck.blindstone;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Potential protection band, optional public terrain boundary, and deterministic replacements. */
public record ConcealmentPolicy(int minY, int maxY, int exteriorMinY, OrePolicy ores, boolean terrainEnabled) {
    public ConcealmentPolicy(int minY, int maxY, int exteriorMinY, OrePolicy ores) {
        this(minY, maxY, exteriorMinY, ores, true);
    }
    public ConcealmentPolicy(int minY, int maxY, int exteriorMinY) { this(minY, maxY, exteriorMinY, OrePolicy.disabled()); }
    public ConcealmentPolicy(int minY, int maxY) { this(minY, maxY, maxY); }

    public ConcealmentPolicy {
        if (minY >= maxY || (minY & 15) != 0 || (maxY & 15) != 0
                || (long) maxY - minY > 1024) {
            throw new IllegalArgumentException("Protection bounds must be ordered, section-aligned, and at most 1024 blocks tall");
        }
    }

    /** Broad eligibility only; outgoing authorization must use the coordinate-aware overload. */
    public boolean protects(int y) { return y >= minY && y < maxY; }

    /** Public exterior is independent of all viewer masks. Missing chunks stay concealed. */
    public boolean protects(net.minecraft.world.level.chunk.LevelChunk chunk, int x, int y, int z) {
        return protects(y) && ((terrainEnabled && (y < exteriorMinY || y < TerrainBoundary.surface(chunk, x, z)))
                || ores.hides(chunk.getSection(chunk.getSectionIndex(y)).getBlockState(x & 15, y & 15, z & 15), y));
    }

    public boolean protects(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos) {
        if (!protects(pos.getY())) return false;
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null || protects(chunk, pos.getX(), pos.getY(), pos.getZ());
    }

    /** Deterministic step at zero, deliberately independent of real terrain and seed. */
    public BlockState replacement(int y) {
        if (!terrainEnabled) return Blocks.NETHERRACK.defaultBlockState();
        return (y < 0 ? Blocks.DEEPSLATE : Blocks.STONE).defaultBlockState();
    }
}
