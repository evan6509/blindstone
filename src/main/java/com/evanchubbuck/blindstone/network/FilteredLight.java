package com.evanchubbuck.blindstone.network;

import com.evanchubbuck.blindstone.SectionVisibility;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LightEngine;

/**
 * Bounded, isolated light reconstruction from the viewer's filtered blocks only. Never reads the
 * real light engine, cached heightmaps, block entities, or hidden block states. The full world
 * height and a 15-block horizontal halo bound every source that can affect the central chunk.
 * Unknown/unloaded columns and space below the world are opaque; this may under-light an edge,
 * never introduce real hidden light. This reference implementation is intentionally uncached.
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
        if (!level.dimension().equals(Level.OVERWORLD)) throw new IllegalArgumentException("Filtered light currently supports the Overworld only");
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
                            new ChunkSnapshot(chunk, Map.copyOf(context.masks(new ChunkPos(chunkX, chunkZ))));
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
                    boolean authorized = !context.policy().protects(y) || chunk.masks()
                            .getOrDefault(Math.floorDiv(y, 16), SectionVisibility.none())
                            .isRevealed(worldX & 15, y & 15, worldZ & 15);
                    // Do not even read an unauthorized block state, including its emission/opacity.
                    state = authorized ? chunk.source().getBlockState(cursor.set(worldX, y, worldZ)) : context.policy().replacement(y);
                }
                states[index(x, y - minY, z)] = state;
                work.checkpoint();
            }
        }
        return solve(states, minY, maxY, work).packet();
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

    private record ChunkSnapshot(LevelChunk source, Map<Integer, SectionVisibility> masks) { }

    @FunctionalInterface
    interface StateSource { BlockState state(int x, int y, int z); }

    record Result(ClientboundLightUpdatePacketData packet, byte[] sky, byte[] block, int minY, int maxY) {
        int skyAt(int x, int y, int z) { return sky[index(x + HALO, y - minY, z + HALO)]; }
        int blockAt(int x, int y, int z) { return block[index(x + HALO, y - minY, z + HALO)]; }
    }

    private static Result solve(BlockState[] states, int minY, int maxY, Work work) {
        int height = maxY - minY;
        byte[] sky = new byte[states.length];
        byte[] block = new byte[states.length];
        var queue = new Queue(states.length);
        // All direct sky columns start at a public dimension boundary, not a real heightmap.
        for (int z = 0; z < WIDTH; z++) for (int x = 0; x < WIDTH; x++) {
            int incoming = 15;
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
        return new Result(packet(sky, block, minY, maxY), sky, block, minY, maxY);
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

    private static ClientboundLightUpdatePacketData packet(byte[] sky, byte[] block, int minY, int maxY) {
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
        Arrays.fill(topSky, (byte) 0xff);
        skyMask.set(sections + 1);
        skyUpdates.add(topSky);
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

    /** Fixed capacity and duplicate suppression bound queued memory to exactly one entry per cell. */
    private static final class Queue {
        private final int[] entries;
        private final boolean[] queued;
        private int read;
        private int write;
        private int size;
        private Queue(int capacity) { entries = new int[capacity]; queued = new boolean[capacity]; }
        private void add(int index) {
            if (queued[index]) return;
            entries[write] = index;
            write = (write + 1) % entries.length;
            size++;
            queued[index] = true;
        }
        private int remove() {
            int index = entries[read];
            read = (read + 1) % entries.length;
            size--;
            queued[index] = false;
            return index;
        }
        private boolean isEmpty() { return size == 0; }
    }
}
