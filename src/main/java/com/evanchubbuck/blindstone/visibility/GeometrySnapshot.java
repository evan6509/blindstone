package com.evanchubbuck.blindstone.visibility;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * An immutable, bounded copy of block geometry. Capture on the server thread; tracing never reads
 * the live level, block entities, lighting, or loads chunks. Missing cells are opaque, never air.
 * Vanilla visual shapes handle doors and partial blocks. Glass/panes, ice, slime, honey and water
 * transmit sight; lava stops sight at its actual fluid shape. Camera paths stop at either sight
 * blockers or collisions. Opaque powder snow is explicitly solid for sight. Moving-piston geometry
 * depends on a live block entity, so its cell and the immediately adjacent cells conservatively
 * block both sight and cameras until the piston settles. No block-entity data enters this snapshot.
 */
public final class GeometrySnapshot {
    public static final int MAX_BLOCKS = 35 * 35 * 35;
    private static final int MAX_SHAPE_BOXES = 256;
    private final BlockPos min;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final Cell[] cells;
    private final int unavailable;

    private GeometrySnapshot(BlockPos min, int sizeX, int sizeY, int sizeZ, Cell[] cells, int unavailable) {
        this.min = min.immutable();
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.cells = cells;
        this.unavailable = unavailable;
    }

    /** Copies states first, then resolves neighbor-dependent shapes against that same frozen copy. */
    public static GeometrySnapshot capture(BlockGetter source, BlockPos min, BlockPos max,
            Predicate<BlockPos> available) {
        long sx = (long) max.getX() - min.getX() + 1;
        long sy = (long) max.getY() - min.getY() + 1;
        long sz = (long) max.getZ() - min.getZ() + 1;
        if (sx < 1 || sy < 1 || sz < 1 || sx > 35 || sy > 35 || sz > 35
                || sx * sy * sz > MAX_BLOCKS) throw new IllegalArgumentException("Snapshot exceeds fixed geometry bounds");
        int sizeX = (int) sx, sizeY = (int) sy, sizeZ = (int) sz;
        BlockState[] states = new BlockState[sizeX * sizeY * sizeZ];
        int unavailable = 0;
        for (int y = 0; y < sizeY; y++) for (int z = 0; z < sizeZ; z++) for (int x = 0; x < sizeX; x++) {
            BlockPos pos = min.offset(x, y, z);
            if (available.test(pos)) states[(y * sizeZ + z) * sizeX + x] = source.getBlockState(pos);
            else unavailable++;
        }
        BlockGetter frozen = new BlockGetter() {
            @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
            @Override public int getHeight() { return sizeY; }
            @Override public int getMinY() { return min.getY(); }
            @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
            @Override public BlockState getBlockState(BlockPos pos) {
                int x = pos.getX() - min.getX(), y = pos.getY() - min.getY(), z = pos.getZ() - min.getZ();
                if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) return Blocks.BEDROCK.defaultBlockState();
                BlockState state = states[(y * sizeZ + z) * sizeX + x];
                return state == null ? Blocks.BEDROCK.defaultBlockState() : state;
            }
        };
        boolean[] movingHalo = new boolean[states.length];
        for (int y = 0; y < sizeY; y++) for (int z = 0; z < sizeZ; z++) for (int x = 0; x < sizeX; x++) {
            BlockState state = states[(y * sizeZ + z) * sizeX + x];
            if (state == null || !state.is(Blocks.MOVING_PISTON)) continue;
            // A piston can occlude in its neighboring cell while its block entity animates.
            for (int ny = Math.max(0, y - 1); ny <= Math.min(sizeY - 1, y + 1); ny++)
                for (int nz = Math.max(0, z - 1); nz <= Math.min(sizeZ - 1, z + 1); nz++)
                    for (int nx = Math.max(0, x - 1); nx <= Math.min(sizeX - 1, x + 1); nx++)
                        movingHalo[(ny * sizeZ + nz) * sizeX + nx] = true;
        }
        Cell[] cells = new Cell[states.length];
        for (int y = 0; y < sizeY; y++) for (int z = 0; z < sizeZ; z++) for (int x = 0; x < sizeX; x++) {
            int i = (y * sizeZ + z) * sizeX + x;
            BlockState state = states[i];
            if (state == null) continue;
            BlockPos pos = min.offset(x, y, z);
            VoxelShape sight = state.getBlock() instanceof HalfTransparentBlock
                    || state.getBlock() instanceof StainedGlassPaneBlock || state.is(Blocks.GLASS_PANE)
                    ? Shapes.empty() : state.getVisualShape(frozen, pos, CollisionContext.empty());
            if (state.is(Blocks.POWDER_SNOW) || movingHalo[i]) sight = Shapes.block();
            FluidState fluid = state.getFluidState();
            if (fluid.is(FluidTags.LAVA)) sight = Shapes.or(sight, fluid.getShape(frozen, pos));
            // A traversable but visually opaque block cannot justify a camera across its wall.
            VoxelShape camera = Shapes.or(sight, state.getCollisionShape(frozen, pos));
            cells[i] = new Cell(boxes(sight, pos), boxes(camera, pos));
        }
        return new GeometrySnapshot(min, sizeX, sizeY, sizeZ, cells, unavailable);
    }

    private static List<AABB> boxes(VoxelShape shape, BlockPos pos) {
        List<AABB> local = shape.toAabbs();
        // Unusually complex shapes conservatively become solid; complexity never expands disclosure.
        if (local.size() > MAX_SHAPE_BOXES) return List.of(new AABB(pos));
        List<AABB> result = new ArrayList<>(local.size());
        for (AABB box : local) result.add(box.move(pos));
        return List.copyOf(result);
    }

    Cell cell(BlockPos pos) {
        int x = pos.getX() - min.getX(), y = pos.getY() - min.getY(), z = pos.getZ() - min.getZ();
        return x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ
                ? null : cells[(y * sizeZ + z) * sizeX + x];
    }

    public int blocks() { return cells.length; }
    public int unavailableBlocks() { return unavailable; }

    /** Includes one neighboring cell whose change can affect a partial/fluid/piston shape. */
    public boolean intersectsChanged(BlockPos pos) {
        return pos.getX() >= min.getX() - 1 && pos.getX() <= min.getX() + sizeX
                && pos.getY() >= min.getY() - 1 && pos.getY() <= min.getY() + sizeY
                && pos.getZ() >= min.getZ() - 1 && pos.getZ() <= min.getZ() + sizeZ;
    }

    record Cell(List<AABB> sight, List<AABB> collision) {}
}
