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

/** Isolated vanilla chunk-data prototype. NOT an outgoing packet hook; light is not included. */
public final class ChunkFilter {
    private ChunkFilter() { }

    public static ClientboundLevelChunkPacketData snapshot(LevelChunk real, ConcealmentPolicy policy,
                                                          Map<Integer, SectionVisibility> visibility) {
        if (!(real.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
            throw new IllegalStateException("Chunk filtering requires the owning server thread");
        }
        if (!level.dimension().equals(Level.OVERWORLD)) {
            throw new IllegalArgumentException("Only the Overworld is supported by the spike");
        }
        if (policy.minY() != real.getMinY() || policy.maxY() > real.getMaxY() + 1) {
            throw new IllegalArgumentException("Protection bounds do not fit this world");
        }
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
            // Never copy the original block palette: unused entries would reveal hidden content.
            var initial = mask.isRevealed(0, 0, 0) ? source.getBlockState(0, 0, 0)
                    : policy.replacement(sectionY * 16);
            var states = new PalettedContainer<>(initial, level.palettedContainerFactory().blockStatesStrategy());
            // Section-aligned replacements are uniform. Fully hidden sections need no 4096-cell
            // palette rebuild, which is the common case underneath an exterior-only chunk.
            if (mask.count() != 0) for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        states.getAndSetUnchecked(x, y, z, mask.isRevealed(x, y, z)
                                ? source.getBlockState(x, y, z) : policy.replacement(sectionY * 16 + y));
                    }
                }
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
