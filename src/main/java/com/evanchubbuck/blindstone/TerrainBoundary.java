package com.evanchubbuck.blindstone;

import java.util.WeakHashMap;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;

/** Server-thread terrain horizons. Public air never recursively authorizes connected air.
 * Open valley axes and cliff faces retain their own geological surface.
 * Keys are weak: unload/reload never retains terrain or transfers classification to a new chunk.
 */
public final class TerrainBoundary {
    private static final WeakHashMap<LevelChunk, Entry> CACHE = new WeakHashMap<>();
    private static final class Entry {
        final int[] heights = new int[256];
        int[] boundary;
        final java.util.BitSet dirty = new java.util.BitSet(256);
        Entry() { dirty.set(0, 256); }
    }
    private TerrainBoundary() { }

    public static void invalidate(LevelChunk chunk, int x, int z) {
        if (com.evanchubbuck.blindstone.diagnostics.WorkMetrics.enabled) com.evanchubbuck.blindstone.diagnostics.WorkMetrics.boundaryInvalidations++;
        ChunkFilter.invalidate(chunk);
        var entry = CACHE.get(chunk);
        if (entry != null) { entry.dirty.set((z & 15) * 16 + (x & 15)); entry.boundary = null; }
        invalidateNeighbors(chunk);
    }

    public static int surface(LevelChunk chunk, int x, int z) {
        var entry = CACHE.computeIfAbsent(chunk, ignored -> new Entry());
        if (entry.boundary == null) {
            long start = com.evanchubbuck.blindstone.diagnostics.WorkMetrics.enabled ? System.nanoTime() : 0;
            entry.boundary = capture(chunk, entry);
            if (com.evanchubbuck.blindstone.diagnostics.WorkMetrics.enabled) com.evanchubbuck.blindstone.diagnostics.WorkMetrics.boundaryNanos += System.nanoTime()-start;
        }
        else if (com.evanchubbuck.blindstone.diagnostics.WorkMetrics.enabled) com.evanchubbuck.blindstone.diagnostics.WorkMetrics.boundaryCacheHits++;
        return entry.boundary[(z & 15) * 16 + (x & 15)];
    }

    /** Detached column table for a generation-checked job; never expose the cached mutable array. */
    public static int[] snapshot(LevelChunk chunk) {
        surface(chunk, 0, 0);
        return CACHE.get(chunk).boundary.clone();
    }

    public static int rawSurface(LevelChunk chunk,int x,int z) {
        var entry=CACHE.computeIfAbsent(chunk,ignored->new Entry());
        return rawHeights(chunk,entry)[(z&15)*16+(x&15)];
    }

    public static int maximum(LevelChunk chunk) {
        surface(chunk, 0, 0);
        return java.util.Arrays.stream(CACHE.get(chunk).boundary).max().orElse(chunk.getMinY());
    }

    public static int minimum(LevelChunk chunk) {
        surface(chunk, 0, 0);
        return java.util.Arrays.stream(CACHE.get(chunk).boundary).min().orElse(chunk.getMinY());
    }

    public static void invalidateNeighbors(LevelChunk chunk) {
        if (!(chunk.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) return;
        invalidateNeighborhood(level,chunk.getPos());
    }

    public static void invalidateNeighborhood(net.minecraft.server.level.ServerLevel level,net.minecraft.world.level.ChunkPos pos) {
        for(int dz=-1;dz<=1;dz++) for(int dx=-1;dx<=1;dx++) {
            var neighbor=level.getChunkSource().getChunkNow(pos.x()+dx,pos.z()+dz);
            var cached=neighbor==null?null:CACHE.get(neighbor);
            if(cached!=null) cached.boundary=null;
            if(neighbor!=null) ChunkFilter.invalidate(neighbor);
        }
    }

    private static int[] rawHeights(LevelChunk chunk, Entry entry) {
        int[] heights = entry.heights;
        // Recompute only mutated columns. Natural terrain anchors exclude foliage, glass, water
        // and above-ground wooden structures; those stay real above the geological boundary.
        for (int index = entry.dirty.nextSetBit(0); index >= 0; index = entry.dirty.nextSetBit(index + 1)) {
            if (com.evanchubbuck.blindstone.diagnostics.WorkMetrics.enabled) com.evanchubbuck.blindstone.diagnostics.WorkMetrics.boundaryScans++;
            int x = index & 15, z = index >> 4;
            heights[index] = chunk.getMinY() - 1;
            // The maintained WORLD_SURFACE height is only an upper bound: foliage, buildings
            // and fluids above rock still need classification. Detached test chunks can mutate
            // section palettes directly, so do not trust their optional heightmap.
            boolean live = chunk.getLevel() instanceof net.minecraft.server.level.ServerLevel level
                    && level.getChunkSource().getChunkNow(chunk.getPos().x(),chunk.getPos().z()) == chunk;
            int top = live ? Math.min(chunk.getMaxY(), chunk.getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,x,z)+1) : chunk.getMaxY();
            for (int y = top; y >= chunk.getMinY(); y--) {
                var section = chunk.getSection(chunk.getSectionIndex(y));
                // Empty sections cannot contain a geological anchor. Skip their full vertical
                // span before touching palettes/tags; cold columns otherwise walk world height.
                if (section.hasOnlyAir()) { y = (y & ~15); continue; }
                if (com.evanchubbuck.blindstone.diagnostics.WorkMetrics.enabled) com.evanchubbuck.blindstone.diagnostics.WorkMetrics.boundaryCellReads++;
                var state = section.getBlockState(x, y & 15, z);
                if (state.isAir()) continue;
                if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE)
                        || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.DIRT)
                        || state.is(BlockTags.SAND) || state.is(BlockTags.TERRACOTTA)
                        || state.is(Blocks.GRAVEL) || state.is(Blocks.SNOW_BLOCK)
                        || state.is(Blocks.PACKED_ICE) || state.is(Blocks.BEDROCK)) {
                    heights[index] = y;
                    break;
                }
            }
        }
        entry.dirty.clear();
        return heights;
    }

    private static int[] capture(LevelChunk chunk, Entry entry) {
        int[] heights=rawHeights(chunk,entry),closed=new int[256];
        int[][] neighbors=new int[9][];
        boolean live=false;
        neighbors[4]=heights;
        if(chunk.getLevel() instanceof net.minecraft.server.level.ServerLevel level
                && level.getChunkSource().getChunkNow(chunk.getPos().x(),chunk.getPos().z())==chunk) {
            live=true;
            for(int dz=-1;dz<=1;dz++) for(int dx=-1;dx<=1;dx++) {
                if(dx==0&&dz==0) continue;
                var neighbor=level.getChunkSource().getChunkNow(chunk.getPos().x()+dx,chunk.getPos().z()+dz);
                if(neighbor!=null) neighbors[(dz+1)*3+dx+1]=rawHeights(neighbor,CACHE.computeIfAbsent(neighbor,ignored->new Entry()));
            }
        }
        int[] dilated=new int[256];
        for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
            int high=Integer.MIN_VALUE;
            for(int dz=-2;dz<=2;dz++) for(int dx=-2;dx<=2;dx++)
                high=Math.max(high,heights[clamp(z+dz)*16+clamp(x+dx)]);
            dilated[z*16+x]=high;
        }
        // An exterior valley has an unbounded horizontal escape direction. A shaft
        // enclosed on all four sides does not, however broad its vertical sky opening.
        // Use the lowest opposing horizon, never a connectivity flood from public air.
        for (int z=0;z<16;z++) for(int x=0;x<16;x++) {
            int west=heights[z*16+x],east=west,north=west,south=west;
            for(int n=0;n<x;n++) west=Math.max(west,heights[z*16+n]);
            for(int n=x+1;n<16;n++) east=Math.max(east,heights[z*16+n]);
            for(int n=0;n<z;n++) north=Math.max(north,heights[n*16+x]);
            for(int n=z+1;n<16;n++) south=Math.max(south,heights[n*16+x]);
            for(int n=1;n<=16;n++) {
                west=Math.max(west,height(neighbors,x-n,z,live?chunk.getMaxY():heights[z*16+x]));
                east=Math.max(east,height(neighbors,x+n,z,live?chunk.getMaxY():heights[z*16+x]));
                north=Math.max(north,height(neighbors,x,z-n,live?chunk.getMaxY():heights[z*16+x]));
                south=Math.max(south,height(neighbors,x,z+n,live?chunk.getMaxY():heights[z*16+x]));
            }
            int narrow=Integer.MAX_VALUE;
            for(int dz=-2;dz<=2;dz++) for(int dx=-2;dx<=2;dx++)
                narrow=Math.min(narrow,dilated[clamp(z+dz)*16+clamp(x+dx)]);
            closed[z*16+x]=Math.max(narrow,Math.min(Math.min(west,east),Math.min(north,south)));
        }
        return closed;
    }
    private static int height(int[][] neighbors,int x,int z,int fallback) {
        var table=neighbors[(Math.floorDiv(z,16)+1)*3+Math.floorDiv(x,16)+1];
        return table==null?fallback:table[(z&15)*16+(x&15)];
    }
    private static int clamp(int n) { return Math.clamp(n, 0, 15); }
}
