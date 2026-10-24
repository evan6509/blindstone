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

    public static ClientboundLevelChunkPacketData snapshot(LevelChunk real, ConcealmentPolicy policy,
                                                          Map<Integer, SectionVisibility> visibility) {
        if (!(real.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
            throw new IllegalStateException("Chunk filtering requires the owning server thread");
        }
        if (!level.dimension().equals(Level.OVERWORLD)) {
            throw new IllegalArgumentException("Only the Overworld is supported");
        }
        if (policy.minY() != real.getMinY() || policy.maxY() > real.getMaxY() + 1) {
            throw new IllegalArgumentException("Protection bounds do not fit this world");
        }
        if (com.evanchubbuck.blindstone.diagnostics.WorkMetrics.enabled) com.evanchubbuck.blindstone.diagnostics.WorkMetrics.chunkFilters++;
        Map<Integer, SectionVisibility> masks = Map.copyOf(visibility);
        if (masks.keySet().stream().anyMatch(y -> y < policy.minY() / 16 || y >= policy.maxY() / 16)) {
            throw new IllegalArgumentException("Mask outside protected sections");
        }

        // This chunk is never registered, ticked, saved, or used for world simulation.
        LevelChunk view = new LevelChunk(level, real.getPos());
        for (int i = 0; i < real.getSections().length; i++) {
            int sectionY = real.getMinSectionY() + i;
            LevelChunkSection source = real.getSections()[i];
            if (!policy.protects(sectionY * 16)) {
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
            // Uniform replacement is implicit; only authorized cells touch the real source.
            for (int index = mask.nextRevealedIndex(0); index >= 0; index = mask.nextRevealedIndex(index + 1)) {
                int x = index & 15, z = (index >> 4) & 15, y = index >> 8;
                if (com.evanchubbuck.blindstone.diagnostics.WorkMetrics.enabled) com.evanchubbuck.blindstone.diagnostics.WorkMetrics.sectionWrites++;
                states.getAndSetUnchecked(x, y, z, source.getBlockState(x, y, z));
            }
            view.getSections()[i] = new LevelChunkSection(states, source.getBiomes().copy());
        }
        real.getBlockEntities().forEach((pos, entity) -> {
            if (!policy.protects(pos.getY()) || masks.getOrDefault(Math.floorDiv(pos.getY(), 16), SectionVisibility.none())
                    .isRevealed(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15)) {
                // Read update tags on the server thread; never attach/move the live entity itself.
                view.getBlockEntities().put(pos, entity);
            }
        });
        Heightmap.primeHeightmaps(view, EnumSet.allOf(Heightmap.Types.class));
        return new ClientboundLevelChunkPacketData(view);
    }
}
