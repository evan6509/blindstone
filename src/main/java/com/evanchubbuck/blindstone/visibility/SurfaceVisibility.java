package com.evanchubbuck.blindstone.visibility;

import com.evanchubbuck.blindstone.ConcealmentPolicy;
import com.evanchubbuck.blindstone.SectionVisibility;
import java.util.BitSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.AbstractChestBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Public exterior geometry, independent of a viewer's private underground knowledge. A vertical
 * column is exposed down to its first sight blocker, inclusive. Only the first face beside such
 * an open column is added: this is not a flood through connected caves or covered rooms.
 * Sky-open shafts, foliage and clear water columns are deliberately public surface clues.
 */
public final class SurfaceVisibility {
    public static final int MAX_CACHED_CHUNKS = 1024;
    private final ServerLevel level;
    private final LinkedHashMap<Long, Entry> cache = new LinkedHashMap<>(16, 0.75F, true);
    private ConcealmentPolicy policy;
    private long captures;

    public SurfaceVisibility(ServerLevel level) { this.level = level; }

    public Map<Integer, SectionVisibility> masks(ChunkPos chunk, ConcealmentPolicy requestedPolicy) {
        checkThread();
        if (!requestedPolicy.equals(policy)) { clear(); policy = requestedPolicy; }
        Entry center = columns(chunk.x(), chunk.z());
        if (center == null) return Map.of();
        if (center.masks != null) return center.masks;
        Entry west = columns(chunk.x() - 1, chunk.z()), east = columns(chunk.x() + 1, chunk.z());
        Entry north = columns(chunk.x(), chunk.z() - 1), south = columns(chunk.x(), chunk.z() + 1);
        Map<Integer, BitSet> bits = new HashMap<>();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int floor = center.floors[z * 16 + x];
            // Neighboring blockers themselves are not open air. Expose only faces above them.
            floor = Math.min(floor, height(x == 0 ? west : center, (x + 15) & 15, z) + 1);
            floor = Math.min(floor, height(x == 15 ? east : center, (x + 1) & 15, z) + 1);
            floor = Math.min(floor, height(z == 0 ? north : center, x, (z + 15) & 15) + 1);
            floor = Math.min(floor, height(z == 15 ? south : center, x, (z + 1) & 15) + 1);
            for (int y = Math.max(floor, policy.minY()); y < policy.maxY(); y++) {
                bits.computeIfAbsent(y >> 4, ignored -> new BitSet(4096)).set(SectionVisibility.index(x, y & 15, z));
            }
        }
        Map<Integer, SectionVisibility> masks = new HashMap<>();
        bits.forEach((y, mask) -> masks.put(y, SectionVisibility.from(mask)));
        center.masks = Map.copyOf(masks);
        return center.masks;
    }

    public boolean isExposed(BlockPos pos, ConcealmentPolicy policy) {
        return !policy.protects(pos.getY()) || masks(ChunkPos.containing(pos), policy)
                .getOrDefault(pos.getY() >> 4, SectionVisibility.none())
                .isRevealed(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    /** Call for block edits and chunk availability changes before preparing any new view. */
    public void invalidate(BlockPos changed) {
        checkThread();
        var entry = cache.get(ChunkPos.pack(changed));
        // A change below an unchanged first blocker cannot alter any sky-open column or face.
        if (entry != null && changed.getY() < entry.floors[(changed.getZ() & 15) * 16 + (changed.getX() & 15)]) return;
        invalidate(ChunkPos.containing(changed));
    }

    public void invalidate(ChunkPos changed) {
        checkThread();
        cache.remove(changed.pack());
        for (int z = changed.z() - 1; z <= changed.z() + 1; z++) for (int x = changed.x() - 1; x <= changed.x() + 1; x++) {
            var entry = cache.get(ChunkPos.pack(x, z));
            if (entry != null) entry.masks = null;
        }
    }

    public void clear() { checkThread(); cache.clear(); }
    public int cachedChunks() { return cache.size(); }
    public long captures() { return captures; }

    private Entry columns(int x, int z) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
        if (chunk == null) return null; // Never load a chunk to classify the surface.
        long key = chunk.getPos().pack();
        Entry entry = cache.get(key);
        if (entry != null && entry.source == chunk) return entry;
        int[] floors = new int[256];
        var cursor = new BlockPos.MutableBlockPos();
        Map<BlockState, Boolean> transparent = new IdentityHashMap<>();
        BlockGetter loaded = new BlockGetter() {
            public int getHeight() { return level.getHeight(); }
            public int getMinY() { return level.getMinY(); }
            public BlockEntity getBlockEntity(BlockPos pos) { return null; }
            public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
            public BlockState getBlockState(BlockPos pos) {
                var source = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
                return source == null ? Blocks.BEDROCK.defaultBlockState() : source.getBlockState(pos);
            }
        };
        for (int localZ = 0; localZ < 16; localZ++) for (int localX = 0; localX < 16; localX++) {
            int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ);
            for (; y >= level.getMinY(); y--) {
                cursor.set(x * 16 + localX, y, z * 16 + localZ);
                BlockState state = chunk.getBlockState(cursor);
                boolean passes = transparent.computeIfAbsent(state, value -> transmits(value, loaded, cursor));
                if (!passes) break;
            }
            floors[localZ * 16 + localX] = Math.max(y, level.getMinY());
        }
        entry = new Entry(chunk, floors);
        cache.put(key, entry);
        if (cache.size() > MAX_CACHED_CHUNKS) cache.remove(cache.keySet().iterator().next());
        captures++;
        return entry;
    }

    private static boolean transmits(BlockState state, BlockGetter loaded, BlockPos pos) {
        if (state.is(Blocks.POWDER_SNOW) || state.is(Blocks.MOVING_PISTON)
                || state.getFluidState().is(FluidTags.LAVA) || state.getBlock() instanceof AbstractChestBlock<?>
                || state.getBlock() instanceof ShulkerBoxBlock || state.getBlock().hasDynamicShape()) return false;
        return state.isAir() || state.is(BlockTags.LEAVES) || state.getBlock() instanceof HalfTransparentBlock
                || state.getBlock() instanceof StainedGlassPaneBlock || state.is(Blocks.GLASS_PANE)
                || state.getVisualShape(loaded, pos, CollisionContext.empty()).isEmpty();
    }

    private int height(Entry entry, int x, int z) { return entry == null ? level.getMaxY() + 1 : entry.floors[z * 16 + x]; }
    private void checkThread() { if (!level.getServer().isSameThread()) throw new IllegalStateException("Surface capture requires the owning server thread"); }
    private static final class Entry {
        final LevelChunk source;
        final int[] floors;
        Map<Integer, SectionVisibility> masks;
        Entry(LevelChunk source, int[] floors) { this.source = source; this.floors = floors; }
    }
}
