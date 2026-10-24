package com.evanchubbuck.blindstone;

import java.util.Set;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Native deterministic target filtering, independent of public terrain classification. */
public record OrePolicy(int maxBlockHeight, Set<Block> targets) {
    public OrePolicy { targets = Set.copyOf(targets); }
    public static OrePolicy disabled() { return new OrePolicy(Integer.MIN_VALUE, Set.of()); }
    public boolean hides(BlockState state, int y) { return y < maxBlockHeight && targets.contains(state.getBlock()); }
    public boolean mayHide(LevelChunkSection section, int minY) {
        return minY < maxBlockHeight && section.maybeHas(state -> targets.contains(state.getBlock()));
    }
}
