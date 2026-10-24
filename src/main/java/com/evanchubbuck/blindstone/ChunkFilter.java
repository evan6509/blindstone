package com.evanchubbuck.blindstone;

import java.util.EnumSet;
import java.util.Map;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;

/** Detached vanilla chunk data used by the outgoing pipeline; matching light is supplied separately. */
public final class ChunkFilter {
    private ChunkFilter() { }
    private record EntityTag(net.minecraft.world.level.block.entity.BlockEntityType<?> type,net.minecraft.nbt.CompoundTag tag) { }
    private record ViewKey(ConcealmentPolicy policy,Map<Integer,SectionVisibility> masks,
            Map<net.minecraft.core.BlockPos,EntityTag> entities) { }
    private record Cached(ViewKey key,ClientboundLevelChunkPacketData data) { }
    // Weak terrain identities, one immutable view per chunk, with a global encoded-byte cap.
    private static final java.util.WeakHashMap<LevelChunk,Cached> CACHE=new java.util.WeakHashMap<>();
    private static final int CACHE_LIMIT=16*1024*1024;
    private static long cacheBytes;
    public static void invalidate(LevelChunk chunk) { CACHE.remove(chunk); }


    public static ClientboundLevelChunkPacketData snapshotCached(LevelChunk real, ConcealmentPolicy policy,
            Map<Integer,SectionVisibility> visibility) {
        if(!(real.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread())
            throw new IllegalStateException("Chunk filtering requires the owning server thread");
        if(visibility.keySet().stream().anyMatch(y->y<policy.minY()/16 || y>=policy.maxY()/16))
            throw new IllegalArgumentException("Mask outside protected sections");
        var nonempty=new java.util.HashMap<Integer,SectionVisibility>();
        visibility.forEach((y,m)->{if(m.count()!=0)nonempty.put(y,m);});
        var tags=new java.util.HashMap<net.minecraft.core.BlockPos,EntityTag>();
        real.getBlockEntities().forEach((pos,entity)-> {
            if(!policy.protects(real,pos.getX(),pos.getY(),pos.getZ()) || visibility.getOrDefault(pos.getY()>>4,SectionVisibility.none())
                    .isRevealed(pos.getX()&15,pos.getY()&15,pos.getZ()&15))
                tags.put(pos,new EntityTag(entity.getType(),entity.getUpdateTag(real.getLevel().registryAccess()).copy()));
        });
        var cacheKey=new ViewKey(policy,Map.copyOf(nonempty),Map.copyOf(tags));
        var cached=CACHE.get(real);
        if(cached!=null && cached.key().equals(cacheKey)) return cached.data();
        var data=snapshot(real,policy,visibility);
        {
            var buffer=data.getReadBuffer();
            int bytes;
            try {bytes=buffer.readableBytes();} finally {buffer.release();}
            bytes+=8192+nonempty.size()*600;
            for(var entity:tags.values()) {
                long tagged=(long)bytes+2L*entity.tag().sizeInBytes()+256;
                bytes=(int)Math.min(Integer.MAX_VALUE,tagged);
            }
            if(bytes>CACHE_LIMIT) return data;
            if(cacheBytes+bytes>CACHE_LIMIT) {CACHE.clear();cacheBytes=0;}
            CACHE.put(real,new Cached(cacheKey,data));cacheBytes+=bytes;
        }
        return data;
    }

    public static ClientboundLevelChunkPacketData snapshot(LevelChunk real, ConcealmentPolicy policy,
                                                          Map<Integer, SectionVisibility> visibility) {
        if (!(real.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
            throw new IllegalStateException("Chunk filtering requires the owning server thread");
        }
        if (!level.dimension().equals(Level.OVERWORLD) && !level.dimension().equals(Level.NETHER)) {
            throw new IllegalArgumentException("Only the Overworld and Nether are supported");
        }
        if (policy.minY() != real.getMinY() || policy.maxY() > real.getMaxY() + 1) {
            throw new IllegalArgumentException("Protection bounds do not fit this world");
        }
        if (com.evanchubbuck.blindstone.diagnostics.WorkMetrics.enabled) com.evanchubbuck.blindstone.diagnostics.WorkMetrics.chunkFilters++;
        Map<Integer, SectionVisibility> masks = Map.copyOf(visibility);
        if (masks.keySet().stream().anyMatch(y -> y < policy.minY() / 16 || y >= policy.maxY() / 16)) {
            throw new IllegalArgumentException("Mask outside protected sections");
        }

        int[] boundary=policy.terrainEnabled() && policy.exteriorMinY()<policy.maxY()?TerrainBoundary.snapshot(real):null;
        int surfaceMax = policy.terrainEnabled() && policy.exteriorMinY() < policy.maxY() ? TerrainBoundary.maximum(real) : policy.maxY();
        int surfaceMin = policy.terrainEnabled() && policy.exteriorMinY() < policy.maxY() ? TerrainBoundary.minimum(real) : policy.maxY();
        // This chunk is never registered, ticked, saved, or used for world simulation.
        LevelChunk view = new LevelChunk(level, real.getPos());
        for (int i = 0; i < real.getSections().length; i++) {
            int sectionY = real.getMinSectionY() + i;
            LevelChunkSection source = real.getSections()[i];
            if (!policy.protects(sectionY * 16) || (!policy.terrainEnabled() && !policy.ores().mayHide(source, sectionY * 16)) || (sectionY * 16 >= policy.exteriorMinY()
                    && sectionY * 16 >= surfaceMax && !policy.ores().mayHide(source, sectionY * 16))) {
                view.getSections()[i] = source.copy();
                continue;
            }
            SectionVisibility mask = masks.getOrDefault(sectionY, SectionVisibility.none());
            if (mask.count() == SectionVisibility.BLOCK_COUNT) {
                view.getSections()[i] = source.copy();
                continue;
            }
            if (mask.count() != 0 && com.evanchubbuck.blindstone.diagnostics.WorkMetrics.enabled) com.evanchubbuck.blindstone.diagnostics.WorkMetrics.partialSections++;
            // Never copy the original block palette: unused entries would reveal hidden content.
            var states = new PalettedContainer<>(policy.replacement(sectionY * 16), level.palettedContainerFactory().blockStatesStrategy());
            if (policy.terrainEnabled() && sectionY * 16 + 15 < Math.max(policy.exteriorMinY(), surfaceMin)) {
                for (int index = mask.nextRevealedIndex(0); index >= 0; index = mask.nextRevealedIndex(index + 1)) {
                    int x = index & 15, z = (index >> 4) & 15, y = index >> 8;
                    if (com.evanchubbuck.blindstone.diagnostics.WorkMetrics.enabled) com.evanchubbuck.blindstone.diagnostics.WorkMetrics.sectionWrites++;
                    states.getAndSetUnchecked(x, y, z, source.getBlockState(x, y, z));
                }
            } else {
                // Public exterior is copied directly, never accumulated into private exploration.
                boolean hasOres=policy.ores().mayHide(source,sectionY*16);
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    int worldY=sectionY*16+y;
                    boolean revealed=mask.isRevealed(x,y,z);
                    boolean interior=policy.terrainEnabled() && (worldY<policy.exteriorMinY()
                            || boundary!=null && worldY<boundary[z*16+x]);
                    if(interior && !revealed) continue;
                    var actual=source.getBlockState(x,y,z);
                    if(revealed || !hasOres || !policy.ores().hides(actual,worldY)) {
                        if (com.evanchubbuck.blindstone.diagnostics.WorkMetrics.enabled) com.evanchubbuck.blindstone.diagnostics.WorkMetrics.sectionWrites++;
                        states.getAndSetUnchecked(x,y,z,actual);
                    }
                }
            }
            view.getSections()[i] = new LevelChunkSection(states, source.getBiomes().copy());
        }
        real.getBlockEntities().forEach((pos, entity) -> {
            if (!policy.protects(real, pos.getX(), pos.getY(), pos.getZ()) || masks.getOrDefault(Math.floorDiv(pos.getY(), 16), SectionVisibility.none())
                    .isRevealed(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15)) {
                // Read update tags on the server thread; never attach/move the live entity itself.
                view.getBlockEntities().put(pos, entity);
            }
        });
        Heightmap.primeHeightmaps(view, EnumSet.allOf(Heightmap.Types.class));
        return new ClientboundLevelChunkPacketData(view);
    }
}
