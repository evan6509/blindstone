package com.evanchubbuck.blindstone.network;

import com.evanchubbuck.blindstone.SectionVisibility;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LightEngine;

/**
 * Bounded, isolated light reconstruction. Concealed light is derived exclusively from filtered
 * states, with a 15-block horizontal halo. Cooperative jobs copy real light only at least 16 blocks
 * above protection, where maximum 15 attenuation proves independence from concealed sources and
 * geometry; the synchronous reference path reads no real light. No heightmaps or block entities
 * are used. Unknown/unloaded columns are opaque. Jobs require generation-checked publication.
 */
public final class FilteredLight {
    public static final int HALO = 15;
    private static final int WIDTH = 16 + HALO * 2;
    private static final int PLANE = WIDTH * WIDTH;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState OPAQUE = Blocks.BEDROCK.defaultBlockState();

    private FilteredLight() { }

    public record Budget(int maxCells, long maxNeighborChecks, long maxNanos) {
        public Budget {
            if (maxCells < 1 || maxCells > 2_000_000 || maxNeighborChecks < 1
                    || maxNeighborChecks > 500_000_000L || maxNanos < 1 || maxNanos > 10_000_000_000L) {
                throw new IllegalArgumentException("Invalid filtered-light budget");
            }
        }
    }

    public static final class BudgetExceededException extends IllegalStateException {
        public BudgetExceededException(String message) { super(message); }
    }

    /** Captures only loaded terrain on the owning thread. Budget failure never returns real light. */
    public static ClientboundLightUpdatePacketData reconstruct(ChunkPos center, PacketFilter.Context context, Budget budget) {
        var level = context.level();
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Light capture requires the owning server thread");
        if (!level.dimension().equals(Level.OVERWORLD) && !level.dimension().equals(Level.NETHER)) throw new IllegalArgumentException("Filtered light currently supports the Overworld only");
        if (context.policy().minY() != level.getMinY() || context.policy().maxY() > level.getMaxY() + 1) {
            throw new IllegalArgumentException("Protection bounds do not fit the light world");
        }
        var work = new Work(budget);
        int minY = level.getMinY();
        int maxY = level.getMaxY() + 1;
        int cells = checkedCells(minY, maxY, budget);
        BlockState[] states = new BlockState[cells];
        int minX = Math.subtractExact(Math.multiplyExact(center.x(), 16), HALO);
        int minZ = Math.subtractExact(Math.multiplyExact(center.z(), 16), HALO);
        ChunkSnapshot[] loaded = new ChunkSnapshot[9];
        var cursor = new BlockPos.MutableBlockPos();
        for (int chunkZ = center.z() - 1; chunkZ <= center.z() + 1; chunkZ++) {
            for (int chunkX = center.x() - 1; chunkX <= center.x() + 1; chunkX++) {
                var chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk != null) {
                    loaded[(chunkZ - center.z() + 1) * 3 + chunkX - center.x() + 1] =
                            captureChunk(chunk, Map.copyOf(context.masks(new ChunkPos(chunkX, chunkZ))), context.policy());
                }
            }
        }
        for (int y = minY; y < maxY; y++) {
            for (int z = 0; z < WIDTH; z++) for (int x = 0; x < WIDTH; x++) {
                int worldX = minX + x;
                int worldZ = minZ + z;
                int chunkIndex = (Math.floorDiv(worldZ, 16) - center.z() + 1) * 3
                        + Math.floorDiv(worldX, 16) - center.x() + 1;
                var chunk = loaded[chunkIndex];
                BlockState state = OPAQUE;
                if (chunk != null) {
                    state = chunk.filtered(context.policy(), worldX, y, worldZ);
                }
                states[index(x, y - minY, z)] = state;
                work.checkpoint();
            }
        }
        return solve(states, minY, maxY, work, level.dimensionType().hasSkyLight()).packet();
    }

    /** Pure filtered-world fixture entry point. Caller must supply already-filtered states. */
    static Result reconstructFiltered(int minY, int maxY, StateSource source, Budget budget) {
        int count = checkedCells(minY, maxY, budget);
        var work = new Work(budget);
        BlockState[] states = new BlockState[count];
        for (int y = minY; y < maxY; y++) for (int z = 0; z < WIDTH; z++) for (int x = 0; x < WIDTH; x++) {
            BlockState state = source.state(x - HALO, y, z - HALO);
            states[index(x, y - minY, z)] = state == null ? OPAQUE : state;
            work.checkpoint();
        }
        return solve(states, minY, maxY, work);
    }

    private record ChunkSnapshot(LevelChunk source, SectionVisibility[] masks, int[] boundary, boolean[] oreSections, boolean[][] authorizedColumns) {
        BlockState filtered(com.evanchubbuck.blindstone.ConcealmentPolicy policy, int x, int y, int z) {
            int section = source.getSectionIndex(y);
            int lx=x&15, ly=y&15, lz=z&15;
            if (policy.protects(y) && !masks[section].isRevealed(lx,ly,lz)) {
                if (policy.terrainEnabled() && (y < policy.exteriorMinY() || boundary != null && y < boundary[lz*16+lx]))
                    return policy.replacement(y);
                var state = source.getSections()[section].getBlockState(lx,ly,lz);
                return oreSections[section] && policy.ores().hides(state,y) ? policy.replacement(y) : state;
            }
            return source.getSections()[section].getBlockState(lx,ly,lz);
        }
    }

    private static ChunkSnapshot captureChunk(LevelChunk chunk, Map<Integer,SectionVisibility> masks,
            com.evanchubbuck.blindstone.ConcealmentPolicy policy) {
        var sections = new SectionVisibility[chunk.getSections().length];
        var ores = new boolean[sections.length];
        var columns=new boolean[sections.length][];
        for (int i=0;i<sections.length;i++) {
            int sectionY=chunk.getMinSectionY()+i;
            sections[i]=masks.getOrDefault(sectionY,SectionVisibility.none());
            if(sections[i].count()!=0) {
                columns[i]=new boolean[256];
                for(int bit=sections[i].nextRevealedIndex(0);bit>=0;bit=sections[i].nextRevealedIndex(bit+1))
                    columns[i][bit&255]=true;
            }
            ores[i]=policy.ores().mayHide(chunk.getSections()[i],sectionY*16);
        }
        int[] boundary=policy.terrainEnabled() && policy.exteriorMinY()<policy.maxY()
                ? com.evanchubbuck.blindstone.TerrainBoundary.snapshot(chunk) : null;
        return new ChunkSnapshot(chunk,sections,boundary,ores,columns);
    }

    @FunctionalInterface
    interface StateSource { BlockState state(int x, int y, int z); }

    record Result(ClientboundLightUpdatePacketData packet, byte[] sky, byte[] block, int minY, int maxY) {
        int skyAt(int x, int y, int z) { return sky[index(x + HALO, y - minY, z + HALO)]; }
        int blockAt(int x, int y, int z) { return block[index(x + HALO, y - minY, z + HALO)]; }
    }

    private static Result solve(BlockState[] states, int minY, int maxY, Work work) {
        return solve(states, minY, maxY, work, true);
    }

    private static Result solve(BlockState[] states, int minY, int maxY, Work work, boolean skylight) {
        int height = maxY - minY;
        byte[] sky = new byte[states.length];
        byte[] block = new byte[states.length];
        var queue = new Queue(states.length);
        // All direct sky columns start at a public dimension boundary, not a real heightmap.
        for (int z = 0; z < WIDTH; z++) for (int x = 0; x < WIDTH; x++) {
            int incoming = skylight ? 15 : 0;
            BlockState above = AIR;
            for (int y = height - 1; y >= 0; y--) {
                int index = index(x, y, z);
                var state = states[index];
                int dampening = incoming == 15 ? state.getLightDampening() : Math.max(1, state.getLightDampening());
                int loss = LightEngine.getLightDampeningInto(above, state, Direction.DOWN, dampening);
                incoming = Math.max(0, incoming - loss);
                sky[index] = (byte) incoming;
                block[index] = (byte) state.getLightEmission();
                above = state;
                work.checkpoint();
            }
        }
        seedFrontier(sky, height, queue, work);
        flood(sky, states, height, queue, work);
        for (int i = 0; i < block.length; i++) {
            if (block[i] > 1) queue.add(i);
            work.checkpoint();
        }
        flood(block, states, height, queue, work);
        return new Result(packet(sky, block, minY, maxY, skylight), sky, block, minY, maxY);
    }

    private static void seedFrontier(byte[] light, int height, Queue queue, Work work) {
        for (int i = 0; i < light.length; i++) {
            if (light[i] > 1) {
                int x = i % WIDTH;
                int z = i / WIDTH % WIDTH;
                int y = i / PLANE;
                for (Direction direction : DIRECTIONS) {
                    int nx = x + direction.getStepX();
                    int ny = y + direction.getStepY();
                    int nz = z + direction.getStepZ();
                    work.neighbor();
                    if (inside(nx, ny, nz, height) && light[index(nx, ny, nz)] < light[i] - 1) {
                        queue.add(i);
                        break;
                    }
                }
            }
            work.checkpoint();
        }
    }

    private static void flood(byte[] light, BlockState[] states, int height, Queue queue, Work work) {
        while (!queue.isEmpty()) {
            int from = queue.remove();
            int value = light[from];
            int x = from % WIDTH;
            int z = from / WIDTH % WIDTH;
            int y = from / PLANE;
            for (Direction direction : DIRECTIONS) {
                work.neighbor();
                int nx = x + direction.getStepX();
                int ny = y + direction.getStepY();
                int nz = z + direction.getStepZ();
                if (!inside(nx, ny, nz, height)) continue;
                int to = index(nx, ny, nz);
                if (value - 1 <= light[to]) continue;
                int loss = LightEngine.getLightDampeningInto(states[from], states[to], direction,
                        Math.max(1, states[to].getLightDampening()));
                int next = value - loss;
                if (next > light[to]) {
                    light[to] = (byte) next;
                    if (next > 1) queue.add(to);
                }
            }
        }
    }

    private static ClientboundLightUpdatePacketData packet(byte[] sky, byte[] block, int minY, int maxY, boolean skylight) {
        int sections = (maxY - minY) / 16;
        BitSet skyMask = new BitSet(sections + 2);
        BitSet blockMask = new BitSet(sections + 2);
        BitSet emptySky = new BitSet(sections + 2);
        BitSet emptyBlock = new BitSet(sections + 2);
        List<byte[]> skyUpdates = new ArrayList<>();
        List<byte[]> blockUpdates = new ArrayList<>();
        // Lower outside-world boundary is opaque; upper outside-world boundary is public sunlight.
        emptySky.set(0);
        emptyBlock.set(0);
        for (int section = 0; section < sections; section++) {
            byte[] skyLayer = new byte[2048];
            byte[] blockLayer = new byte[2048];
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int source = index(x + HALO, section * 16 + y, z + HALO);
                int nibble = (y << 8) | (z << 4) | x;
                skyLayer[nibble >> 1] |= (byte) (sky[source] << ((nibble & 1) * 4));
                blockLayer[nibble >> 1] |= (byte) (block[source] << ((nibble & 1) * 4));
            }
            appendLayer(section + 1, skyLayer, skyMask, emptySky, skyUpdates);
            appendLayer(section + 1, blockLayer, blockMask, emptyBlock, blockUpdates);
        }
        byte[] topSky = new byte[2048];
        if (skylight) Arrays.fill(topSky, (byte) 0xff);
        appendLayer(sections + 1, topSky, skyMask, emptySky, skyUpdates);
        emptyBlock.set(sections + 1);
        return new ClientboundLightUpdatePacketData(skyMask, blockMask, emptySky, emptyBlock, skyUpdates, blockUpdates);
    }

    private static void appendLayer(int section, byte[] data, BitSet mask, BitSet empty, List<byte[]> updates) {
        boolean dark = true;
        for (byte value : data) if (value != 0) { dark = false; break; }
        if (dark) empty.set(section);
        else { mask.set(section); updates.add(data); }
    }

    private static int checkedCells(int minY, int maxY, Budget budget) {
        if (minY >= maxY || (minY & 15) != 0 || (maxY & 15) != 0) throw new IllegalArgumentException("Light bounds must be ordered and section aligned");
        long count = ((long) maxY - minY) * PLANE;
        if (count > budget.maxCells()) throw new BudgetExceededException("Filtered light exceeds cell budget");
        return Math.toIntExact(count);
    }

    private static int index(int x, int y, int z) { return y * PLANE + z * WIDTH + x; }
    private static boolean inside(int x, int y, int z, int height) {
        return x >= 0 && x < WIDTH && y >= 0 && y < height && z >= 0 && z < WIDTH;
    }

    /** Upper real-light sections are independent of concealed changes at least 16 blocks below.
     * Every upward propagation step loses at least one level, and vanilla's maximum is 15.
     * Direct skylight sources only propagate downward without attenuation. Thus neither hidden
     * emission nor hidden geometry can affect a coordinate Y >= protectedMaxY + 16.
     */
    public static long estimatedJobBytes(ChunkPos center, PacketFilter.Context context) {
        int worldMin = context.level().getMinY(), worldMax = context.level().getMaxY() + 1;
        int lowest = Math.min(context.policy().maxY(), context.policy().exteriorMinY() & ~15);
        int highest = Math.min(context.policy().maxY(), context.policy().exteriorMinY());
        for (int z = center.z() - 1; z <= center.z() + 1; z++) for (int x = center.x() - 1; x <= center.x() + 1; x++) {
            var chunk = context.level().getChunkSource().getChunkNow(x, z);
            if (chunk == null) continue;
            if (context.policy().terrainEnabled() && context.policy().exteriorMinY() < context.policy().maxY())
                highest = Math.max(highest, Math.min(context.policy().maxY(),
                        com.evanchubbuck.blindstone.TerrainBoundary.maximum(chunk) + 1));
            for (int i = 0; i < chunk.getSections().length; i++) {
                int sectionMin = (chunk.getMinSectionY() + i) * 16;
                if (context.policy().ores().mayHide(chunk.getSections()[i], sectionMin))
                    highest = Math.max(highest, Math.min(context.policy().maxY(),
                            Math.min(context.policy().ores().maxBlockHeight(), sectionMin + 16)));
            }
            for (var entry : context.masks(new ChunkPos(x, z)).entrySet())
                if (entry.getValue().count() != 0 && context.policy().protects(entry.getKey() * 16))
                    lowest = Math.min(lowest, entry.getKey() * 16);
        }
        int min = lowest == worldMin ? worldMin - 16 : lowest;
        int safe = ((highest + 15) & ~15) + 16;
        int max = safe >= worldMax ? worldMax + 16 : safe;
        long cells = ((long) max - min) * PLANE;
        if (cells < 1 || cells > 2_000_000) throw new BudgetExceededException("Filtered light job exceeds cell bound");
        return cells * 12 + 262144;
    }

    public static Job begin(ChunkPos center, PacketFilter.Context context) { return new Job(center, context); }

    /** Cooperative job. Its frozen masks and world revision must remain current until publication. */
    public static final class Job {
        private enum Phase { CAPTURE, BOUNDARY, INITIALIZE, SKY_SEED, SKY_FLOOD, BLOCK_SEED, BLOCK_FLOOD, PACK, DONE }
        private final ChunkPos center;
        private final PacketFilter.Context context;
        private final ChunkSnapshot[] loaded = new ChunkSnapshot[9];
        private final BlockState[][] states;
        private final int cellCount;
        private final boolean[] opaqueSections;
        private final boolean[] opaqueColumnSlabs;
        private final boolean[] directSkyColumnSlabs;
        private final boolean[] airColumnSlabs;
        private final boolean[] darkBlockColumnSlabs;
        private final BlockState[] boundaryStates = new BlockState[PLANE];
        private final byte[] boundarySky = new byte[PLANE];
        private final byte[] boundaryBlock = new byte[PLANE];
        private final byte[] sky;
        private final byte[] block;
        private final Queue queue;
        private final int worldMin;
        private final int worldMax;
        private final int minY;
        private final int maxY;
        private final int height;
        private final int minX;
        private final int minZ;
        private final int safeCopyY;
        private final int lightMinSection;
        private final byte[][] skyLayers;
        private final byte[][] blockLayers;
        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        private Phase phase = Phase.CAPTURE;
        private int offset;
        private int incomingSky;
        private int incomingBlock;
        private BlockState above;
        private int packSection;
        private int packCell;
        private boolean rawSky;
        private boolean rawBlock;
        private boolean inheritedSky;
        private ClientboundLightUpdatePacketData result;
        private long totalOperations;
        private long totalNanos;

        private Job(ChunkPos center, PacketFilter.Context context) {
            this.center = center;
            this.context = context;
            requireThread();
            if (!context.level().dimension().equals(Level.OVERWORLD) && !context.level().dimension().equals(Level.NETHER)) throw new IllegalArgumentException("Filtered light supports the Overworld only");
            worldMin = context.level().getMinY();
            worldMax = context.level().getMaxY() + 1;
            if (context.policy().minY() != worldMin || context.policy().maxY() > worldMax) throw new IllegalArgumentException("Invalid light policy bounds");
            int lowest = Math.min(context.policy().maxY(), context.policy().exteriorMinY() & ~15);
            int highestPrivate = Math.min(context.policy().maxY(), context.policy().exteriorMinY());
            for (int z = center.z() - 1; z <= center.z() + 1; z++) for (int x = center.x() - 1; x <= center.x() + 1; x++) {
                var chunk = context.level().getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                if (context.policy().terrainEnabled() && context.policy().exteriorMinY() < context.policy().maxY())
                    highestPrivate = Math.max(highestPrivate, Math.min(context.policy().maxY(),
                            com.evanchubbuck.blindstone.TerrainBoundary.maximum(chunk) + 1));
                for (int i = 0; i < chunk.getSections().length; i++) {
                    int sectionMin = (chunk.getMinSectionY() + i) * 16;
                    if (context.policy().ores().mayHide(chunk.getSections()[i], sectionMin))
                        highestPrivate = Math.max(highestPrivate, Math.min(context.policy().maxY(),
                                Math.min(context.policy().ores().maxBlockHeight(), sectionMin + 16)));
                }
                var masks = Map.copyOf(context.masks(new ChunkPos(x, z)));
                loaded[(z - center.z() + 1) * 3 + x - center.x() + 1] = captureChunk(chunk, masks, context.policy());
                for (var entry : masks.entrySet()) {
                    int sectionY = entry.getKey() * 16;
                    if (entry.getValue().count() != 0 && context.policy().protects(sectionY)) lowest = Math.min(lowest, sectionY);
                }
            }
            // Everything below the first authorized section is analytically opaque replacement.
            // If that section reaches the build floor, include the public void boundary as air.
            minY = lowest == worldMin ? worldMin - 16 : lowest;
            safeCopyY = Math.addExact((highestPrivate + 15) & ~15, 16);
            maxY = safeCopyY >= worldMax ? worldMax + 16 : safeCopyY;
            height = maxY - minY;
            int cells = Math.toIntExact((long) height * PLANE);
            if (cells < 1 || cells > 2_000_000) throw new BudgetExceededException("Filtered light job exceeds cell bound");
            cellCount=cells;
            states = new BlockState[height/16][];
            // A whole halo slab below every terrain boundary with no private mask is
            // exactly replacement rock. Do not reconstruct its half-million dark cells.
            opaqueSections = new boolean[height / 16];
            opaqueColumnSlabs=new boolean[height/16*PLANE];
            directSkyColumnSlabs=new boolean[opaqueColumnSlabs.length];
            airColumnSlabs=new boolean[opaqueColumnSlabs.length];
            darkBlockColumnSlabs=new boolean[opaqueColumnSlabs.length];
            for (int slab=0;slab<opaqueSections.length;slab++) {
                int bottom=minY+slab*16;
                boolean opaque=context.policy().terrainEnabled() && bottom>=worldMin
                        && bottom>=context.policy().minY() && bottom+16<=context.policy().maxY();
                for (var snapshot:loaded) if (snapshot!=null && opaque) {
                    int section=snapshot.source().getSectionIndex(bottom);
                    if (snapshot.masks()[section].count()!=0) opaque=false;
                    if (bottom+16>context.policy().exteriorMinY()) {
                        if (snapshot.boundary()==null) opaque=false;
                        else for(int boundary:snapshot.boundary()) if(bottom+16>boundary) {opaque=false;break;}
                    }
                }
                opaqueSections[slab]=opaque;
            }
            sky = new byte[cells];
            block = new byte[cells];
            queue = new Queue(cells);
            minX = Math.subtractExact(Math.multiplyExact(center.x(), 16), HALO);
            minZ = Math.subtractExact(Math.multiplyExact(center.z(), 16), HALO);
            lightMinSection = Math.floorDiv(worldMin, 16) - 1;
            int sectionCount = (worldMax - worldMin) / 16 + 2;
            skyLayers = new byte[sectionCount][];
            blockLayers = new byte[sectionCount][];
        }

        public boolean isDone() { return phase == Phase.DONE; }
        public long totalOperations() { return totalOperations; }
        public long totalNanos() { return totalNanos; }
        public int cellCount() { return cellCount; }
        public ClientboundLightUpdatePacketData result() {
            if (!isDone()) throw new IllegalStateException("Light job is not ready");
            return result;
        }

        /** Returns consumed work units. No background world access and no unbounded flood phase. */
        public int step(int maxOperations, long maxNanos) {
            requireThread();
            if (maxOperations < 1 || maxNanos < 1) throw new IllegalArgumentException("Positive light slice budget required");
            long start = System.nanoTime();
            int operations = 0;
            boolean expired=false;
            while (!isDone() && operations < maxOperations && !expired) {
                var current=phase;
                long phaseStart=System.nanoTime();
                do {
                    operations += one(maxOperations-operations);
                    if ((operations & 255) < 16 && System.nanoTime() - start >= maxNanos) {expired=true;break;}
                } while(phase==current && operations<maxOperations);
                if(com.evanchubbuck.blindstone.diagnostics.WorkMetrics.enabled)
                    com.evanchubbuck.blindstone.diagnostics.WorkMetrics.lightPhaseNanos[current.ordinal()]+=System.nanoTime()-phaseStart;
            }
            totalOperations += operations;
            totalNanos += System.nanoTime() - start;
            return operations;
        }

        private void requireThread() {
            if (!context.level().getServer().isSameThread()) throw new IllegalStateException("Light jobs require the owning server thread");
        }

        private int one(int remainingOperations) {
            switch (phase) {
                case CAPTURE -> {
                    int column=offset/height;
                    int relativeY=offset%height;
                    int x=column%WIDTH,z=column/WIDTH;
                    int y=minY+relativeY;
                    int length=Math.min(remainingOperations,Math.min(16-(y&15),height-relativeY));
                    var snapshot=chunk(x,z);
                    var policy=context.policy();
                    int worldX=minX+x,worldZ=minZ+z;
                    boolean hidden=false;
                    boolean columnAuthorized=false;
                    boolean oreSection=false;
                    int boundary=Integer.MIN_VALUE;
                    net.minecraft.world.level.chunk.LevelChunkSection section=null;
                    SectionVisibility mask=SectionVisibility.none();
                    if(y>=worldMin && y<worldMax && snapshot!=null) {
                        int si=snapshot.source().getSectionIndex(y);
                        section=snapshot.source().getSections()[si];
                        mask=snapshot.masks()[si];
                        columnAuthorized=snapshot.authorizedColumns()[si]!=null && snapshot.authorizedColumns()[si][(worldZ&15)*16+(worldX&15)];
                        oreSection=snapshot.oreSections()[si];
                        boundary=policy.terrainEnabled()?Math.max(policy.exteriorMinY(),snapshot.boundary()==null?policy.exteriorMinY():snapshot.boundary()[(worldZ&15)*16+(worldX&15)]):Integer.MIN_VALUE;
                        hidden=!columnAuthorized && policy.protects(y) && y+length<=Math.min(policy.maxY(),boundary);
                    }
                    opaqueColumnSlabs[(relativeY/16)*PLANE+column]=(snapshot==null || !columnAuthorized
                            && policy.protects(y&~15) && (y&~15)+16<=Math.min(policy.maxY(),boundary)) && y>=worldMin && y<worldMax;
                    boolean publicAir=section!=null && section.hasOnlyAir() && (!policy.terrainEnabled()
                            || y>=policy.maxY() || y>=Math.max(policy.exteriorMinY(),snapshot.boundary()==null?policy.exteriorMinY():snapshot.boundary()[(worldZ&15)*16+(worldX&15)]));
                    airColumnSlabs[(relativeY/16)*PLANE+column]=publicAir && ((y&~15)>=boundary || !policy.terrainEnabled() || (y&~15)>=policy.maxY());
                    for(int n=0;!publicAir && !opaqueColumnSlabs[(relativeY/16)*PLANE+column] && n<length;n++) {
                        BlockState state;
                        if(y<worldMin || y>=worldMax) state=AIR;
                        else if(snapshot==null || hidden) state=OPAQUE;
                        else if(publicAir) state=AIR;
                        else if(!oreSection && (!policy.terrainEnabled() || y>=boundary || y>=policy.maxY()))
                            state=section.getBlockState(worldX&15,(y+n)&15,worldZ&15);
                        else state=snapshot.filtered(policy,worldX,y+n,worldZ);
                        if(state!=AIR) {
                            int target=(relativeY+n)*PLANE+column;
                            int slab=target/(16*PLANE);
                            if(states[slab]==null) states[slab]=new BlockState[16*PLANE];
                            states[slab][target%(16*PLANE)]=state;
                        }
                    }
                    offset+=length;
                    if(offset==cellCount) {offset=0;phase=Phase.BOUNDARY;}
                    return hidden || publicAir?1:length;
                }
                case BOUNDARY -> {
                    int x = offset % WIDTH;
                    int z = offset / WIDTH;
                    var chunk = chunk(x, z);
                    if (maxY >= worldMax) {
                        boundaryStates[offset] = AIR;
                        boundarySky[offset] = (byte) (context.level().dimensionType().hasSkyLight() ? 15 : 0);
                    } else if (chunk == null) boundaryStates[offset] = OPAQUE;
                    else {
                        // maxY == safeCopyY. Every copied input here satisfies the upward-distance proof.
                        cursor.set(minX + x, maxY, minZ + z);
                        boundaryStates[offset] = chunk.source().getBlockState(cursor);
                        boundarySky[offset] = (byte) context.level().getBrightness(LightLayer.SKY, cursor);
                        boundaryBlock[offset] = (byte) context.level().getBrightness(LightLayer.BLOCK, cursor);
                    }
                    if (++offset == PLANE) { offset = 0; phase = Phase.INITIALIZE; }
                }
                case INITIALIZE -> {
                    int column = offset / height;
                    int y = height - 1 - offset % height;
                    int index = y * PLANE + column;
                    if (y == height - 1) {
                        above = boundaryStates[column];
                        incomingSky = boundarySky[column];
                        incomingBlock = boundaryBlock[column];
                    }
                    if (opaqueColumnSlabs[(y/16)*PLANE+column]) {
                        incomingSky=incomingBlock=0; above=OPAQUE;
                        offset += (y & 15)+1;
                        if(offset==cellCount) {offset=0;phase=Phase.SKY_SEED;}
                        return 1;
                    }
                    int currentSlab=(y/16)*PLANE+column;
                    if((y&15)==15 && airColumnSlabs[currentSlab] && above==AIR && incomingSky==15 && incomingBlock==0) {
                        for(int n=0;n<16;n++) sky[(y-n)*PLANE+column]=15;
                        directSkyColumnSlabs[currentSlab]=true;
                        darkBlockColumnSlabs[currentSlab]=true;
                        offset+=16;
                        if(offset==cellCount) {offset=0;phase=Phase.SKY_SEED;}
                        return 1;
                    }
                    if(above==AIR && incomingSky==15 && incomingBlock==0 && stateAt(index)==AIR) {
                        int span=0;
                        while(span<=(y&15) && stateAt(index-span*PLANE)==AIR) {
                            sky[index-span*PLANE]=15;span++;
                        }
                        if((y&15)==15) {directSkyColumnSlabs[currentSlab]=true;darkBlockColumnSlabs[currentSlab]=true;}
                        offset+=span;
                        if(offset==cellCount) {offset=0;phase=Phase.SKY_SEED;}
                        return 1;
                    }
                    var state = stateAt(index);
                    if(state==OPAQUE || state.getLightDampening()==15) {
                        incomingSky=0;incomingBlock=state.getLightEmission();
                    } else if(state==AIR && above==AIR) {
                        if(incomingSky!=15) incomingSky=Math.max(0,incomingSky-1);
                        incomingBlock=Math.max(0,incomingBlock-1);
                    } else {
                        int dampening = incomingSky == 15 ? state.getLightDampening() : Math.max(1, state.getLightDampening());
                        incomingSky = Math.max(0, incomingSky - LightEngine.getLightDampeningInto(above, state, Direction.DOWN, dampening));
                        incomingBlock = Math.max(state.getLightEmission(), incomingBlock - LightEngine.getLightDampeningInto(
                                above, state, Direction.DOWN, Math.max(1, state.getLightDampening())));
                    }
                    sky[index] = (byte) incomingSky;
                    block[index] = (byte) incomingBlock;
                    int slabIndex=(y/16)*PLANE+column;
                    if((y&15)==15) {directSkyColumnSlabs[slabIndex]=true;darkBlockColumnSlabs[slabIndex]=true;}
                    directSkyColumnSlabs[slabIndex] &= incomingSky==15 && state==AIR;
                    darkBlockColumnSlabs[slabIndex] &= incomingBlock==0;
                    above = state;
                    if (++offset == cellCount) { offset = 0; phase = Phase.SKY_SEED; }
                }
                case SKY_SEED -> {
                    int column=offset/height,y=offset%height;
                    if(opaqueColumnSlabs[(y/16)*PLANE+column]) {
                        offset+=16-(y&15);
                        if(offset==cellCount) {offset=0;phase=Phase.SKY_FLOOD;}
                        return 1;
                    }
                    int slabIndex=(y/16)*PLANE+column;
                    int x=column%WIDTH,z=column/WIDTH;
                    if((y&15)==0 && directSkyColumnSlabs[slabIndex]
                            && (x==0 || uniformSkyNeighbor(slabIndex-1))
                            && (x==WIDTH-1 || uniformSkyNeighbor(slabIndex+1))
                            && (z==0 || uniformSkyNeighbor(slabIndex-WIDTH))
                            && (z==WIDTH-1 || uniformSkyNeighbor(slabIndex+WIDTH))) {
                        int bottom=y*PLANE+column,top=(y+15)*PLANE+column;
                        if(y>0 && !uniformSkyNeighbor(slabIndex-PLANE) && hasLowerNeighbor(bottom)) queue.add(bottom);
                        if(y+16<height && !uniformSkyNeighbor(slabIndex+PLANE) && hasLowerNeighbor(top)) queue.add(top);
                        offset+=16;
                        if(offset==cellCount) {offset=0;phase=Phase.SKY_FLOOD;}
                        return 1;
                    }
                    int index=y*PLANE+column;
                    int cost=1;
                    if(sky[index]>1) {cost=7;if(hasLowerNeighbor(index)) queue.add(index);}
                    if(++offset==cellCount) {offset=0;phase=Phase.SKY_FLOOD;}
                    return cost;
                }
                case SKY_FLOOD -> {
                    if (queue.isEmpty()) phase = Phase.BLOCK_SEED;
                    else propagate(sky, queue.remove());
                    return 7;
                }
                case BLOCK_SEED -> {
                    int column=offset/height,y=offset%height;
                    if(opaqueColumnSlabs[(y/16)*PLANE+column] || darkBlockColumnSlabs[(y/16)*PLANE+column]) {
                        offset+=16-(y&15);
                        if(offset==cellCount) {offset=0;phase=Phase.BLOCK_FLOOD;}
                        return 1;
                    }
                    int index=y*PLANE+column;
                    if(block[index]>1) queue.add(index);
                    if(++offset==cellCount) {offset=0;phase=Phase.BLOCK_FLOOD;}
                }
                case BLOCK_FLOOD -> {
                    if (queue.isEmpty()) phase = Phase.PACK;
                    else propagate(block, queue.remove());
                    return 7;
                }
                case PACK -> pack();
                case DONE -> { }
            }
            return 1;
        }

        private BlockState filtered(int x, int y, int z) {
            if (y < worldMin || y >= worldMax) return AIR;
            var chunk = chunk(x, z);
            if (chunk == null) return OPAQUE;
            int worldX = minX + x;
            int worldZ = minZ + z;
            return chunk.filtered(context.policy(), worldX, y, worldZ);
        }

        private ChunkSnapshot chunk(int x, int z) {
            return loaded[(Math.floorDiv(minZ + z, 16) - center.z() + 1) * 3
                    + Math.floorDiv(minX + x, 16) - center.x() + 1];
        }

        /** Null entries represent analytically uniform cells, avoiding redundant reference fills. */
        private BlockState stateAt(int index) {
            var slab=states[index/(16*PLANE)];
            var state=slab==null?null:slab[index%(16*PLANE)];
            if(state!=null) return state;
            int y=index/PLANE,column=index%PLANE;
            return opaqueColumnSlabs[(y/16)*PLANE+column]?OPAQUE:AIR;
        }

        private boolean uniformSkyNeighbor(int slabIndex) {
            return directSkyColumnSlabs[slabIndex] || opaqueColumnSlabs[slabIndex];
        }

        private boolean hasLowerNeighbor(int from) {
            int x = from % WIDTH;
            int z = from / WIDTH % WIDTH;
            int y = from / PLANE;
            for (Direction direction : DIRECTIONS) {
                int nx = x + direction.getStepX(), ny = y + direction.getStepY(), nz = z + direction.getStepZ();
                if (inside(nx, ny, nz, height)) {
                    int to=index(nx,ny,nz);
                    if(sky[to]<sky[from]-1 && stateAt(to).getLightDampening()<sky[from]) return true;
                }
            }
            return false;
        }

        private void propagate(byte[] light, int from) {
            int value = light[from];
            int x = from % WIDTH;
            int z = from / WIDTH % WIDTH;
            int y = from / PLANE;
            for (Direction direction : DIRECTIONS) {
                int nx = x + direction.getStepX(), ny = y + direction.getStepY(), nz = z + direction.getStepZ();
                if (!inside(nx, ny, nz, height)) continue;
                int to = index(nx, ny, nz);
                if (value - 1 <= light[to]) continue;
                int next = value - LightEngine.getLightDampeningInto(stateAt(from), stateAt(to), direction,
                        Math.max(1, stateAt(to).getLightDampening()));
                if (next > light[to]) { light[to] = (byte) next; if (next > 1) queue.add(to); }
            }
        }

        private void pack() {
            if (packSection == skyLayers.length) {
                BitSet skyMask = new BitSet(), blockMask = new BitSet(), emptySky = new BitSet(), emptyBlock = new BitSet();
                List<byte[]> skyUpdates = new ArrayList<>(), blockUpdates = new ArrayList<>();
                for (int i = 0; i < skyLayers.length; i++) {
                    appendLayer(i, skyLayers[i], skyMask, emptySky, skyUpdates);
                    appendLayer(i, blockLayers[i], blockMask, emptyBlock, blockUpdates);
                }
                result = new ClientboundLightUpdatePacketData(skyMask, blockMask, emptySky, emptyBlock, skyUpdates, blockUpdates);
                phase = Phase.DONE;
                return;
            }
            int sectionY = (lightMinSection + packSection) * 16;
            if (packCell == 0) {
                // An entire analytically concealed section is uniformly dark and requires no voxel scan.
                if (sectionY + 16 <= minY || sectionY>=minY && sectionY<maxY && opaqueSections[(sectionY-minY)/16]) {
                    skyLayers[packSection] = new byte[2048]; blockLayers[packSection] = new byte[2048];
                    packSection++; return;
                }
                rawSky = rawBlock = inheritedSky = false;
                if (sectionY >= safeCopyY) {
                    var skyData = context.level().getLightEngine().getLayerListener(LightLayer.SKY)
                            .getDataLayerData(SectionPos.of(center.x(), lightMinSection + packSection, center.z()));
                    var blockData = context.level().getLightEngine().getLayerListener(LightLayer.BLOCK)
                            .getDataLayerData(SectionPos.of(center.x(), lightMinSection + packSection, center.z()));
                    if (skyData != null) { skyLayers[packSection] = skyData.copy().getData(); rawSky = true; }
                    if (blockData != null) blockLayers[packSection] = blockData.copy().getData();
                    else blockLayers[packSection]=new byte[2048];
                    // Vanilla BlockLightSectionStorage returns zero for a missing layer.
                    rawBlock=true;
                    // A missing sky layer inherits the next layer's bottom plane at every Y.
                    inheritedSky=skyData==null;
                }
                if (!rawSky) skyLayers[packSection] = new byte[2048];
                if (!rawBlock) blockLayers[packSection] = new byte[2048];
                if (rawSky && rawBlock) { packSection++; return; }
            }
            int x = packCell & 15, z = packCell >> 4 & 15, y = sectionY + (packCell >> 8);
            int s, b;
            if (y >= minY && y < maxY) {
                int index = index(x + HALO, y - minY, z + HALO);
                s = sky[index]; b = block[index];
            } else if (y >= safeCopyY) {
                cursor.set(center.x() * 16 + x, y, center.z() * 16 + z);
                s = rawSky ? 0 : context.level().getBrightness(LightLayer.SKY, cursor);
                b = rawBlock ? 0 : context.level().getBrightness(LightLayer.BLOCK, cursor);
            } else { s = y >= worldMax && context.level().dimensionType().hasSkyLight() ? 15 : 0; b = 0; }
            int shift = (packCell & 1) * 4;
            if (!rawSky) skyLayers[packSection][packCell >> 1] |= (byte) (s << shift);
            if (!rawBlock) blockLayers[packSection][packCell >> 1] |= (byte) (b << shift);
            if(inheritedSky && packCell==255) {
                for(int layer=1;layer<16;layer++) System.arraycopy(skyLayers[packSection],0,skyLayers[packSection],layer*128,128);
                packCell=0;packSection++;return;
            }
            if (++packCell == 4096) { packCell = 0; packSection++; }
        }
    }

    private static final class Work {
        private final Budget budget;
        private final long start = System.nanoTime();
        private long checkpoints;
        private long neighbors;
        private Work(Budget budget) { this.budget = budget; }
        private void checkpoint() {
            if ((++checkpoints & 4095) == 0 && System.nanoTime() - start > budget.maxNanos()) {
                throw new BudgetExceededException("Filtered light exceeds time budget");
            }
        }
        private void neighbor() {
            if (++neighbors > budget.maxNeighborChecks()) throw new BudgetExceededException("Filtered light exceeds propagation budget");
            checkpoint();
        }
    }

    /** Fixed logical capacity, segmented allocation and duplicate suppression. */
    private static final class Queue {
        private final int capacity;
        private final int[][] entries;
        private final BitSet queued;
        private int read;
        private int write;
        private int size;
        private Queue(int capacity) {
            this.capacity=capacity;entries=new int[(capacity+4095)/4096][];queued=new BitSet();
        }
        private void add(int index) {
            if(queued.get(index)) return;
            int segment=write>>12;
            if(entries[segment]==null) entries[segment]=new int[Math.min(4096,capacity-segment*4096)];
            entries[segment][write&4095]=index;
            if(++write==capacity) write=0;
            size++;queued.set(index);
        }
        private int remove() {
            int index=entries[read>>12][read&4095];
            if(++read==capacity) read=0;
            size--;queued.clear(index);return index;
        }
        private boolean isEmpty() {return size==0;}
    }
}
