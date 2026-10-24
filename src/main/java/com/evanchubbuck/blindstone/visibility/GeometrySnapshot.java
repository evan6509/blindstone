package com.evanchubbuck.blindstone.visibility;

import java.util.List;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.AbstractChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
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
    private final boolean sightGeometry;
    private static final Cell EMPTY = new Cell(List.of(), List.of());
    private static final AABB UNIT_BOX = new AABB(0, 0, 0, 1, 1, 1);
    private static final Cell SOLID = new Cell(List.of(UNIT_BOX), List.of(UNIT_BOX));

    private GeometrySnapshot(BlockPos min, int sizeX, int sizeY, int sizeZ, Cell[] cells, int unavailable, boolean sightGeometry) {
        this.min = min.immutable();
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.cells = cells;
        this.unavailable = unavailable;
        this.sightGeometry = sightGeometry;
    }

    /** Copies states first, then resolves neighbor-dependent shapes against that same frozen copy. */
    public static GeometrySnapshot capture(BlockGetter source, BlockPos min, BlockPos max,
            Predicate<BlockPos> available) {
        long sx = (long) max.getX() - min.getX() + 1;
        long sy = (long) max.getY() - min.getY() + 1;
        long sz = (long) max.getZ() - min.getZ() + 1;
        if (sx < 1 || sy < 1 || sz < 1 || sx > 35 || sy > 35 || sz > 35
                || sx * sy * sz > MAX_BLOCKS) throw new IllegalArgumentException("Snapshot exceeds fixed geometry bounds");
        if (com.evanchubbuck.blindstone.diagnostics.WorkMetrics.enabled) { com.evanchubbuck.blindstone.diagnostics.WorkMetrics.captures++; com.evanchubbuck.blindstone.diagnostics.WorkMetrics.cells += sx * sy * sz; }
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
            if (state == null) continue;
            if (state.getBlock() instanceof AbstractChestBlock<?>) {
                // The chest lid can animate into the cell above; no live animation state is trusted.
                if (y + 1 < sizeY) movingHalo[((y + 1) * sizeZ + z) * sizeX + x] = true;
            }
            if (state.getBlock() instanceof ShulkerBoxBlock) {
                var direction = state.getValue(ShulkerBoxBlock.FACING);
                int nx = x + direction.getStepX(), ny = y + direction.getStepY(), nz = z + direction.getStepZ();
                if (nx >= 0 && ny >= 0 && nz >= 0 && nx < sizeX && ny < sizeY && nz < sizeZ)
                    movingHalo[(ny * sizeZ + nz) * sizeX + nx] = true;
            }
            if (!state.is(Blocks.MOVING_PISTON)) continue;
            // A piston can occlude in its neighboring cell while its block entity animates.
            for (int ny = Math.max(0, y - 1); ny <= Math.min(sizeY - 1, y + 1); ny++)
                for (int nz = Math.max(0, z - 1); nz <= Math.min(sizeZ - 1, z + 1); nz++)
                    for (int nx = Math.max(0, x - 1); nx <= Math.min(sizeX - 1, x + 1); nx++)
                        movingHalo[(ny * sizeZ + nz) * sizeX + nx] = true;
        }
        Cell[] cells = new Cell[states.length];
        Map<BlockState, Cell> staticShapes = new IdentityHashMap<>();
        for (int y = 0; y < sizeY; y++) for (int z = 0; z < sizeZ; z++) for (int x = 0; x < sizeX; x++) {
            int i = (y * sizeZ + z) * sizeX + x;
            BlockState state = states[i];
            if (state == null) continue;
            if (movingHalo[i] || state.is(Blocks.POWDER_SNOW)) { cells[i] = SOLID; continue; }
            boolean cacheable = !state.getBlock().hasDynamicShape() && !state.hasOffsetFunction()
                    && !state.getFluidState().is(FluidTags.LAVA);
            if (cacheable && staticShapes.containsKey(state)) { cells[i] = staticShapes.get(state); continue; }
            BlockPos pos = min.offset(x, y, z);
            VoxelShape sight = state.getBlock() instanceof HalfTransparentBlock
                    || state.getBlock() instanceof StainedGlassPaneBlock || state.is(Blocks.GLASS_PANE)
                    ? Shapes.empty() : state.getVisualShape(frozen, pos, CollisionContext.empty());
            FluidState fluid = state.getFluidState();
            if (fluid.is(FluidTags.LAVA)) sight = Shapes.or(sight, fluid.getShape(frozen, pos));
            // A traversable but visually opaque block cannot justify a camera across its wall.
            VoxelShape camera = Shapes.or(sight, state.getCollisionShape(frozen, pos));
            cells[i] = sight.isEmpty() && camera.isEmpty() ? EMPTY : new Cell(boxes(sight), boxes(camera));
            if (cacheable) staticShapes.put(state, cells[i]);
        }
        return new GeometrySnapshot(min, sizeX, sizeY, sizeZ, cells, unavailable, true);
    }

    /** Availability-only body snapshots are intentionally forbidden as inputs to sight evaluation. */
    public static GeometrySnapshot availability(BlockPos min, BlockPos max, Predicate<BlockPos> available) {
        long sx = (long) max.getX() - min.getX() + 1, sy = (long) max.getY() - min.getY() + 1, sz = (long) max.getZ() - min.getZ() + 1;
        if (sx < 1 || sy < 1 || sz < 1 || sx > 35 || sy > 35 || sz > 35 || sx * sy * sz > MAX_BLOCKS)
            throw new IllegalArgumentException("Safety snapshot exceeds fixed bounds");
        Cell[] cells = new Cell[(int) (sx * sy * sz)];
        int unavailable = 0;
        for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++) {
            if (available.test(min.offset(x, y, z))) cells[(int) ((y * sz + z) * sx + x)] = EMPTY;
            else unavailable++;
        }
        return new GeometrySnapshot(min, (int) sx, (int) sy, (int) sz, cells, unavailable, false);
    }

    private static List<AABB> boxes(VoxelShape shape) {
        List<AABB> local = shape.toAabbs();
        // Unusually complex shapes conservatively become solid; complexity never expands disclosure.
        if (local.size() > MAX_SHAPE_BOXES) return List.of(UNIT_BOX);
        return List.copyOf(local);
    }

    Cell cell(BlockPos pos) {
        int x = pos.getX() - min.getX(), y = pos.getY() - min.getY(), z = pos.getZ() - min.getZ();
        return x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ
                ? null : cells[(y * sizeZ + z) * sizeX + x];
    }

    public int blocks() { return cells.length; }
    public int unavailableBlocks() { return unavailable; }
    public boolean hasSightGeometry() { return sightGeometry; }

    /**
     * Conservative connected component used only to reject impossible ray targets. Full sight
     * cubes terminate traversal but remain candidate wall faces. Partial shapes, glass and fluids
     * remain traversable here; the exact rays still decide disclosure. Six-neighbor traversal
     * includes every cell visited by our axis-ordered DDA, including its edge/corner tie steps.
     * Seed the eye clearance cube as well as the eye so camera-corner starts and DDA epsilon
     * cannot make this prefilter narrower than a valid ray path.
     */
    BitSet reachableSightCells(Vec3 eyes) {
        BitSet reached=new BitSet(cells.length);
        int[] queue=new int[cells.length];
        int tail=0;
        double margin=VisibilityEvaluator.CAMERA_HALF_WIDTH+0.00001;
        for(int y=(int)Math.floor(eyes.y-margin);y<=Math.floor(eyes.y+margin);y++)
            for(int z=(int)Math.floor(eyes.z-margin);z<=Math.floor(eyes.z+margin);z++)
                for(int x=(int)Math.floor(eyes.x-margin);x<=Math.floor(eyes.x+margin);x++) {
                    int lx=x-min.getX(),ly=y-min.getY(),lz=z-min.getZ();
                    if(lx<0||ly<0||lz<0||lx>=sizeX||ly>=sizeY||lz>=sizeZ)continue;
                    int i=(ly*sizeZ+lz)*sizeX+lx;
                    if(cells[i]!=null&&!reached.get(i)) {
                        reached.set(i);
                        if(!fullSight(cells[i]))queue[tail++]=i;
                    }
                }
        for(int head=0;head<tail;head++) {
            int i=queue[head],x=i%sizeX,z=(i/sizeX)%sizeZ,y=i/(sizeX*sizeZ);
            for(int direction=0;direction<6;direction++) {
                int next=switch(direction) {
                    case 0 -> x>0?i-1:-1;
                    case 1 -> x+1<sizeX?i+1:-1;
                    case 2 -> z>0?i-sizeX:-1;
                    case 3 -> z+1<sizeZ?i+sizeX:-1;
                    case 4 -> y>0?i-sizeX*sizeZ:-1;
                    default -> y+1<sizeY?i+sizeX*sizeZ:-1;
                };
                if(next<0||cells[next]==null||reached.get(next))continue;
                reached.set(next);
                if(!fullSight(cells[next]))queue[tail++]=next;
            }
        }
        return reached;
    }

    boolean reachableSightCell(BitSet reached,BlockPos pos) {
        int x=pos.getX()-min.getX(),y=pos.getY()-min.getY(),z=pos.getZ()-min.getZ();
        if(x<0||y<0||z<0||x>=sizeX||y>=sizeY||z>=sizeZ)return false;
        int index=(y*sizeZ+z)*sizeX+x;
        // Keep missing candidates so the evaluator still reports incomplete geometry explicitly.
        return cells[index]==null||reached.get(index);
    }

    /** Fully buried solids have no visible face; avoid sending seven rays into each terrain cell. */
    boolean enclosedSolid(BlockPos pos) {
        return fullSight(pos) && fullSight(pos.above()) && fullSight(pos.below())
                && fullSight(pos.north()) && fullSight(pos.south()) && fullSight(pos.east()) && fullSight(pos.west());
    }

    private boolean fullSight(BlockPos pos) {
        Cell cell = cell(pos);
        return fullSight(cell);
    }

    private static boolean fullSight(Cell cell) {
        return cell != null && cell.sight().size() == 1 && cell.sight().getFirst().equals(UNIT_BOX);
    }

    /** Includes one neighboring cell whose change can affect a partial/fluid/piston shape. */
    public boolean intersectsChanged(BlockPos pos) {
        return pos.getX() >= min.getX() - 1 && pos.getX() <= min.getX() + sizeX
                && pos.getY() >= min.getY() - 1 && pos.getY() <= min.getY() + sizeY
                && pos.getZ() >= min.getZ() - 1 && pos.getZ() <= min.getZ() + sizeZ;
    }

    /** Chunk availability changes affect the captured horizontal region and its shape halo only. */
    public boolean intersectsChanged(ChunkPos chunk) {
        return chunk.getMaxBlockX() >= min.getX() - 1 && chunk.getMinBlockX() <= min.getX() + sizeX
                && chunk.getMaxBlockZ() >= min.getZ() - 1 && chunk.getMinBlockZ() <= min.getZ() + sizeZ;
    }

    record Cell(List<AABB> sight, List<AABB> collision) {}
}
