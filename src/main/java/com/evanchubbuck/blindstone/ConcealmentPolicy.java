package com.evanchubbuck.blindstone;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Initial spike supports section-aligned Overworld bounds and fixed safe replacement blocks. */
public record ConcealmentPolicy(int minY, int maxY) {
    public ConcealmentPolicy {
        if (minY >= maxY || (minY & 15) != 0 || (maxY & 15) != 0
                || (long) maxY - minY > 1024) {
            throw new IllegalArgumentException("Protection bounds must be ordered, section-aligned, and at most 1024 blocks tall");
        }
    }

    public boolean protects(int y) { return y >= minY && y < maxY; }

    /** Deterministic step at zero, deliberately independent of real terrain and seed. */
    public BlockState replacement(int y) {
        return (y < 0 ? Blocks.DEEPSLATE : Blocks.STONE).defaultBlockState();
    }
}
