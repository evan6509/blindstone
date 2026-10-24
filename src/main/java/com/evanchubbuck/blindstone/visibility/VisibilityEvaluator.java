package com.evanchubbuck.blindstone.visibility;

import com.evanchubbuck.blindstone.ConcealmentPolicy;
import com.evanchubbuck.blindstone.SectionVisibility;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Conservative per-block sight, independent of view direction and client-supplied camera data. */
public final class VisibilityEvaluator {
    public static final int MAX_RANGE = 16;
    public static final int CAMERA_DISTANCE = 4;
    public static final double CAMERA_HALF_WIDTH = 0.1;
    public static final double SAFETY_MARGIN = 1.0 / 16.0;
    public static final double MAX_MOVEMENT_SEGMENT = 1.5;
    public static final double DEFAULT_ACCEPTED_MOVEMENT = 16;
    public static final double MAX_ACCEPTED_MOVEMENT = 24;
    public static final int MAX_SAFETY_SCAN_CELLS = 8192;
    private static final double[][] SAMPLES = {
        {0.5, 0.5, 0.5}, {0.001, 0.5, 0.5}, {0.999, 0.5, 0.5},
        {0.5, 0.001, 0.5}, {0.5, 0.999, 0.5}, {0.5, 0.5, 0.001}, {0.5, 0.5, 0.999}
    };
    private static final Vec3[] CAMERA_OFFSETS = cameraOffsets();

    private static Vec3[] cameraOffsets() {
        List<Vec3> result = new ArrayList<>();
        for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) {
            if ((x | y | z) != 0) result.add(new Vec3(x, y, z).normalize().scale(CAMERA_DISTANCE));
        }
        return result.toArray(Vec3[]::new);
    }

    private VisibilityEvaluator() {}

    /**
     * Server-thread entry point. Only already loaded chunks are read; missing chunks stop rays.
     * The default evaluates the current accepted body only. Runtime movement callers should pass
     * their prior accepted body using the overload and gate movement when safetyComplete is false.
     */
    public static Result evaluate(ServerPlayer player, ConcealmentPolicy policy, int range,
            int maxCandidates, int maxRayWork) {
        return evaluate(player, null, policy, range, maxCandidates, maxRayWork);
    }

    public static Result evaluate(ServerPlayer player, AABB previousAcceptedBody, ConcealmentPolicy policy,
            int range, int maxCandidates, int maxRayWork) {
        Viewpoint view = new Viewpoint(player.getEyePosition(), player.getBoundingBox(), previousAcceptedBody, true);
        return evaluate(capture(player.level(), view, range), view, policy, range, maxCandidates, maxRayWork);
    }

    public static GeometrySnapshot capture(ServerLevel level, Viewpoint view, int range) {
        checkRange(range);
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Geometry must be captured on the server thread");
        int radius = Math.max(range, CAMERA_DISTANCE) + 1;
        BlockPos origin = BlockPos.containing(view.eyes());
        BlockPos min = origin.offset(-radius, -radius, -radius), max = origin.offset(radius, radius, radius);
        return captureBounds(level, min, max);
    }

    private static GeometrySnapshot captureBounds(ServerLevel level, BlockPos min, BlockPos max) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Geometry must be captured on the server thread");
        int minChunkX=min.getX()>>4, minChunkZ=min.getZ()>>4;
        int width=(max.getX()>>4)-minChunkX+1, depth=(max.getZ()>>4)-minChunkZ+1;
        // Fixed geometry bounds allow at most four chunks on either horizontal axis.
        if(width<1 || depth<1 || width>4 || depth>4) throw new IllegalArgumentException("Geometry exceeds chunk bounds");
        var loaded=new LevelChunk[width*depth];
        for(int z=0;z<depth;z++) for(int x=0;x<width;x++)
            loaded[z*width+x]=level.getChunkSource().getChunkNow(minChunkX+x,minChunkZ+z);
        // Resolve availability and states from the same already-loaded identities. Avoid boxed
        // chunk-map probes and ServerLevel.getBlockState's second chunk lookup for every cell.
        var source=new net.minecraft.world.level.BlockGetter() {
            private LevelChunk chunk(BlockPos pos) {
                int x=(pos.getX()>>4)-minChunkX,z=(pos.getZ()>>4)-minChunkZ;
                return x<0||z<0||x>=width||z>=depth?null:loaded[z*width+x];
            }
            @Override public int getHeight() {return level.getHeight();}
            @Override public int getMinY() {return level.getMinY();}
            @Override public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) {return null;}
            @Override public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {return getBlockState(pos).getFluidState();}
            @Override public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos pos) {
                var chunk=chunk(pos);
                return chunk==null || !level.isInsideBuildHeight(pos)
                        ? net.minecraft.world.level.block.Blocks.BEDROCK.defaultBlockState() : chunk.getBlockState(pos);
            }
            boolean available(BlockPos pos) {return level.isInsideBuildHeight(pos) && chunk(pos)!=null;}
        };
        return GeometrySnapshot.capture(source,min,max,source::available);
    }

    /** Exact cells for the eyes-to-target box with one neighbor halo; oversized paths fail closed. */
    public static int sightSnapshotBlockBound(Vec3 from, Vec3 to) {
        checkPoints(from, to);
        long x = Math.abs((long) Math.floor(from.x) - (long) Math.floor(to.x)) + 3;
        long y = Math.abs((long) Math.floor(from.y) - (long) Math.floor(to.y)) + 3;
        long z = Math.abs((long) Math.floor(from.z) - (long) Math.floor(to.z)) + 3;
        return x > 35 || y > 35 || z > 35 || x * y * z > GeometrySnapshot.MAX_BLOCKS
                ? Integer.MAX_VALUE : (int) (x * y * z);
    }

    public static GeometrySnapshot captureSight(ServerLevel level, Vec3 from, Vec3 to, int maxBlocks) {
        int bound = sightSnapshotBlockBound(from, to);
        if (maxBlocks < 1 || maxBlocks > GeometrySnapshot.MAX_BLOCKS || bound > maxBlocks)
            throw new IllegalArgumentException("Point sight snapshot exceeds its budget");
        BlockPos a = BlockPos.containing(from), b = BlockPos.containing(to);
        BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()) - 1, Math.min(a.getY(), b.getY()) - 1, Math.min(a.getZ(), b.getZ()) - 1);
        BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + 1, Math.max(a.getZ(), b.getZ()) + 1);
        return captureBounds(level, min, max);
    }

    /** Strict point sight for entities: the complete segment, including its end cell, must be clear. */
    public static SightResult clearSight(GeometrySnapshot geometry, Vec3 from, Vec3 to, int maxRayWork) {
        return pointSight(geometry, from, to, maxRayWork, false);
    }

    public static SightResult clearCameraPath(GeometrySnapshot geometry, Vec3 from, Vec3 to, int maxRayWork) {
        return pointSight(geometry, from, to, maxRayWork, true);
    }

    private static SightResult pointSight(GeometrySnapshot geometry, Vec3 from, Vec3 to, int maxRayWork, boolean collision) {
        checkPoints(from, to);
        if (!geometry.hasSightGeometry()) throw new IllegalArgumentException("Availability-only body snapshot cannot authorize sight");
        if (maxRayWork < 1 || maxRayWork > 20_000_000) throw new IllegalArgumentException("Point sight work budget outside fixed bounds");
        Budget budget = new Budget(maxRayWork);
        Ray status = new RayCursor(from, to, null, collision).advance(geometry, budget);
        return new SightResult(status == Ray.CLEAR, status != Ray.EXHAUSTED && !budget.unavailable,
                budget.used, geometry.blocks());
    }

    private static void checkPoints(Vec3 from, Vec3 to) {
        if (from == null || to == null || !Viewpoint.finite(from.x, from.y, from.z, to.x, to.y, to.z))
            throw new IllegalArgumentException("Invalid accepted sight points");
    }

    /** Exact allocation ceiling for server capture, allowing admission checks before any copying. */
    public static int snapshotBlockBound(int range) {
        checkRange(range);
        int side = 2 * (Math.max(range, CAMERA_DISTANCE) + 1) + 1;
        return side * side * side;
    }

    /**
     * Pure tracing entry point: immutable snapshots can be evaluated away from the server thread.
     * Completeness is explicit. Budget exhaustion returns only already justified blocks and never
     * opens a room, section, chunk, range, or safety margin. Work counts voxel visits plus box tests.
     */
    public static Result evaluate(GeometrySnapshot geometry, Viewpoint view, ConcealmentPolicy policy,
            int range, int maxCandidates, int maxRayWork) {
        Result sight = createJob(geometry, view, policy, range).step(maxCandidates, maxRayWork);
        Result movement = evaluateSafety(geometry, view, policy);
        Map<Long, Map<Integer, SectionVisibility>> masks = new HashMap<>();
        sight.chunks().forEach((chunk, sections) -> masks.put(chunk, new HashMap<>(sections)));
        movement.chunks().forEach((chunk, sections) -> {
            var target = masks.computeIfAbsent(chunk, ignored -> new HashMap<>());
            sections.forEach((section, mask) -> target.merge(section, mask, SectionVisibility::union));
        });
        masks.replaceAll((chunk, sections) -> Map.copyOf(sections));
        return new Result(Map.copyOf(masks), sight.complete() && movement.safetyComplete(), movement.safetyComplete(),
                sight.candidates(), sight.rayWork(), sight.snapshotBlocks(), sight.unavailableBlocks());
    }

    public static Job createJob(GeometrySnapshot geometry, Viewpoint view, ConcealmentPolicy policy, int range) {
        checkRange(range);
        if (!geometry.hasSightGeometry()) throw new IllegalArgumentException("Availability-only body snapshot cannot authorize sight");
        return new Job(geometry, view, policy, range, Map.of());
    }

    /** Already public/delivered cells need no new sight authorization or ray work. */
    public static Job createJob(GeometrySnapshot geometry, Viewpoint view, ConcealmentPolicy policy, int range,
            Map<Long, Map<Integer, SectionVisibility>> known) {
        checkRange(range);
        if (!geometry.hasSightGeometry()) throw new IllegalArgumentException("Availability-only body snapshot cannot authorize sight");
        return new Job(geometry, view, policy, range, known);
    }

    /** Exterior discovery authorizes ore targets only; exclude other candidates before tracing. */
    public static Job createJob(GeometrySnapshot geometry, Viewpoint view, ConcealmentPolicy policy, int range,
            Map<Long,Map<Integer,SectionVisibility>> known, java.util.function.Predicate<BlockPos> eligible) {
        checkRange(range);
        if(!geometry.hasSightGeometry()) throw new IllegalArgumentException("Availability-only body snapshot cannot authorize sight");
        return new Job(geometry,view,policy,range,known,eligible);
    }

    /** Independently refresh this fixed body envelope each tick while an older sight job progresses. */
    public static Result evaluateSafety(GeometrySnapshot geometry, Viewpoint view, ConcealmentPolicy policy) {
        return evaluateSafety(geometry, new SafetyInput(List.of(new BodyPath(view.body(), view.previousBody())), MAX_MOVEMENT_SEGMENT), policy);
    }

    public static Result evaluateSafety(GeometrySnapshot geometry, SafetyInput input, ConcealmentPolicy policy) {
        Map<Long, Map<Integer, BitSet>> bits = new HashMap<>();
        boolean complete = safety(geometry, input, policy, bits);
        return result(geometry, bits, complete, complete, 0, 0);
    }

    /** Upper bound for copying accepted player/vehicle paths, independent of visibility range. */
    public static int safetySnapshotBlockBound(SafetyInput input) {
        SafetyBounds bounds = safetyBounds(input);
        return bounds == null ? Integer.MAX_VALUE : bounds.blocks();
    }

    public static GeometrySnapshot captureSafety(ServerLevel level, SafetyInput input, int maxBlocks) {
        if (com.evanchubbuck.blindstone.diagnostics.WorkMetrics.enabled) com.evanchubbuck.blindstone.diagnostics.WorkMetrics.safetyCaptures++;
        SafetyBounds bounds = safetyBounds(input);
        if (bounds == null || bounds.blocks() > maxBlocks || maxBlocks < 1)
            throw new IllegalArgumentException("Accepted body path exceeds its fixed safety budget");
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Body availability must be captured on the server thread");
        Map<Long, LevelChunk> loaded = new HashMap<>();
        for (int z = bounds.min().getZ() >> 4; z <= bounds.max().getZ() >> 4; z++)
            for (int x = bounds.min().getX() >> 4; x <= bounds.max().getX() >> 4; x++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk != null) loaded.put(ChunkPos.pack(x, z), chunk);
            }
        return GeometrySnapshot.availability(bounds.min(), bounds.max(), pos -> level.isInsideBuildHeight(pos)
                && loaded.containsKey(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4)));
    }

    public static SafetyInput rebaseSafety(List<AABB> acceptedBodies, double maxAcceptedSegment) {
        return new SafetyInput(acceptedBodies.stream().map(body -> new BodyPath(body, null)).toList(), maxAcceptedSegment);
    }

    private static Result result(GeometrySnapshot geometry, Map<Long, Map<Integer, BitSet>> bits,
            boolean complete, boolean safetyComplete, int candidates, int work) {
        Map<Long, Map<Integer, SectionVisibility>> result = new HashMap<>();
        bits.forEach((chunk, sections) -> {
            Map<Integer, SectionVisibility> masks = new HashMap<>();
            sections.forEach((section, mask) -> masks.put(section, SectionVisibility.from(mask)));
            result.put(chunk, Map.copyOf(masks));
        });
        return new Result(Map.copyOf(result), complete, safetyComplete, candidates, work,
                geometry.blocks(), geometry.unavailableBlocks());
    }

    /**
     * Single-caller resumable work bound to one immutable geometry generation and accepted origin.
     * Runtime must discard it when geometry changes, a lifecycle resets, or the accepted origin
     * moves outside its chosen rebase threshold. Each step publishes only newly justified blocks
     * plus the original bounded body safety. Camera, candidate, sample, voxel and shape-box cursors
     * survive exhaustion, so even a one-unit budget eventually progresses without widening sight.
     */
    public static final class Job {
        private final GeometrySnapshot geometry;
        private final Viewpoint view;
        private final List<BlockPos> candidates = new ArrayList<>();
        private final List<Vec3> origins = new ArrayList<>();
        private final Map<Long, Map<Integer, BitSet>> completedBits = new HashMap<>();
        private final boolean safetyComplete;
        private boolean unavailable;
        private int cameraOffset;
        private int cameraCorner;
        private double cameraDistance = CAMERA_DISTANCE;
        private int cameraClips;
        private int candidate;
        private int origin;
        private int sample;
        private RayCursor ray;

        private Job(GeometrySnapshot geometry, Viewpoint view, ConcealmentPolicy policy, int range,
                Map<Long, Map<Integer, SectionVisibility>> known) {
            this(geometry,view,policy,range,known,pos->true);
        }

        private Job(GeometrySnapshot geometry, Viewpoint view, ConcealmentPolicy policy, int range,
                Map<Long,Map<Integer,SectionVisibility>> known,java.util.function.Predicate<BlockPos> eligible) {
            this.geometry = geometry;
            this.view = view;
            safetyComplete = safety(geometry, new SafetyInput(List.of(new BodyPath(view.body(), null)), MAX_MOVEMENT_SEGMENT), policy, completedBits);
            origins.add(view.eyes());
            cameraOffset = view.thirdPerson() ? 0 : CAMERA_OFFSETS.length;
            BlockPos center = BlockPos.containing(view.eyes());
            BitSet reachable=geometry.reachableSightCells(view.eyes());
            // Import this viewer's immutable masks once. Primitive lookup avoids a boxed
            // chunk key allocation for every reachable candidate in the snapshot volume.
            var knownChunks = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Map<Integer,SectionVisibility>>(known.size());
            known.forEach((key,sections)->knownChunks.put(key.longValue(),sections));
            var cursor = new BlockPos.MutableBlockPos();
            for (int y = -range; y <= range; y++) for (int z = -range; z <= range; z++) for (int x = -range; x <= range; x++) {
                BlockPos pos = cursor.set(center.getX()+x,center.getY()+y,center.getZ()+z);
                if (policy.protects(pos.getY()) && eligible.test(pos) && geometry.reachableSightCell(reachable,pos)
                        && view.eyes().distanceToSqr(pos.getX()+0.5,pos.getY()+0.5,pos.getZ()+0.5) <= range * range
                        && !knownChunks.getOrDefault(ChunkPos.pack(pos), Map.of()).getOrDefault(pos.getY() >> 4, SectionVisibility.none())
                            .isRevealed(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15))
                    candidates.add(pos.immutable());
            }
            candidates.sort(Comparator.comparingDouble(pos -> view.eyes().distanceToSqr(pos.getX()+0.5,pos.getY()+0.5,pos.getZ()+0.5)));
        }

        public boolean done() { return cameraOffset == CAMERA_OFFSETS.length && candidate == candidates.size(); }
        public int completedCandidates() { return candidate; }
        public int totalCandidates() { return candidates.size(); }
        public boolean intersectsChanged(BlockPos pos) { return geometry.intersectsChanged(pos); }
        public boolean intersectsChanged(ChunkPos chunk) { return geometry.intersectsChanged(chunk); }
        public List<Vec3> cameraOrigins() { return List.copyOf(origins); }

        /** One resumable runtime refresh slice. Body safety is delivered separately;
         * defer immutable mask copies until runtime can publish the completed job. */
        public Result stepRefresh(int maxRayWork, java.util.function.BooleanSupplier hasTime) {
            return stepWithTime(32768, Math.min(16384, maxRayWork), hasTime, true);
        }

        /** Yield at voxel/shape boundaries without revealing an unfinished ray. */
        public Result step(int maxCandidates, int maxRayWork, java.util.function.BooleanSupplier hasTime) {
            return stepWithTime(maxCandidates, maxRayWork, hasTime, false);
        }

        public Result step(int maxCandidates, int maxRayWork) {
            return stepWithTime(maxCandidates, maxRayWork, () -> true, false);
        }

        private Result stepWithTime(int maxCandidates, int maxRayWork, java.util.function.BooleanSupplier hasTime, boolean completionOnly) {
            if (maxCandidates < 1 || maxCandidates > 100_000 || maxRayWork < 1 || maxRayWork > 20_000_000)
                throw new IllegalArgumentException("Visibility budgets outside fixed bounds");
            Budget budget = new Budget(maxRayWork, hasTime);
            // Keep only completed clear rays (and explicit body safety) across yields.
            // Runtime publishes this immutable result when the job finishes, avoiding
            // repeated lighting rebuilds for intermediate slices of the same job.
            var bits = completedBits;
            // Admit every explicit camera only after all nine clearance rays have completed.
            while (cameraOffset < CAMERA_OFFSETS.length && hasTime.getAsBoolean()) {
                Vec3 camera = view.eyes().add(CAMERA_OFFSETS[cameraOffset].scale(cameraDistance / CAMERA_DISTANCE));
                if (ray == null) {
                    Vec3 corner = cameraCorner == 8 ? Vec3.ZERO : new Vec3(
                            (cameraCorner & 1) == 0 ? -CAMERA_HALF_WIDTH : CAMERA_HALF_WIDTH,
                            (cameraCorner & 2) == 0 ? -CAMERA_HALF_WIDTH : CAMERA_HALF_WIDTH,
                            (cameraCorner & 4) == 0 ? -CAMERA_HALF_WIDTH : CAMERA_HALF_WIDTH);
                    ray = new RayCursor(view.eyes().add(corner), camera.add(corner), null, true);
                }
                Ray status = ray.advance(geometry, budget);
                if (status == Ray.EXHAUSTED) break;
                double hitFraction = ray.blockedFraction;
                ray = null;
                if (status == Ray.BLOCKED) {
                    cameraDistance = Math.max(0, cameraDistance * hitFraction - 0.02);
                    cameraCorner = 0;
                    if (cameraDistance < 0.25 || ++cameraClips > 256) nextCamera();
                } else if (++cameraCorner == 9) { origins.add(camera); nextCamera(); }
            }
            int checked = 0;
            while (cameraOffset == CAMERA_OFFSETS.length && candidate < candidates.size() && checked < maxCandidates && hasTime.getAsBoolean()) {
                BlockPos pos = candidates.get(candidate);
                if (geometry.cell(pos) == null) {
                    budget.unavailable = true;
                    candidate++;
                    checked++;
                    continue;
                }
                if (geometry.enclosedSolid(pos)) {
                    candidate++;
                    checked++;
                    continue;
                }
                if (ray == null) {
                    double[] point = SAMPLES[sample];
                    ray = new RayCursor(origins.get(origin),
                            new Vec3(pos.getX() + point[0], pos.getY() + point[1], pos.getZ() + point[2]), pos, false);
                }
                Ray status = ray.advance(geometry, budget);
                if (status == Ray.EXHAUSTED) break;
                ray = null;
                if (status == Ray.CLEAR) {
                    reveal(bits, pos);
                    candidate++;
                    checked++;
                    origin = sample = 0;
                } else if (++sample == SAMPLES.length) {
                    sample = 0;
                    if (++origin == origins.size()) {
                        origin = 0;
                        candidate++;
                        checked++;
                    }
                }
            }
            unavailable |= budget.unavailable;
            return result(geometry, completionOnly && !done() ? Map.of() : bits,
                    done() && !unavailable && safetyComplete, safetyComplete, checked, budget.used);
        }

        private void nextCamera() {
            cameraOffset++;
            cameraCorner = 0;
            cameraDistance = CAMERA_DISTANCE;
            cameraClips = 0;
        }
    }

    /** Voxel DDA with the same endpoint epsilon and axis tie order as vanilla traverseBlocks. */
    private static final class RayCursor {
        private final Vec3 from;
        private final Vec3 to;
        private final BlockPos target;
        private final boolean collision;
        private final int signX, signY, signZ;
        private final double deltaX, deltaY, deltaZ;
        private double nextX, nextY, nextZ;
        private int x, y, z;
        private List<AABB> boxes;
        private int box;
        private double entryFraction;
        private double blockedFraction;

        private RayCursor(Vec3 from, Vec3 to, BlockPos target, boolean collision) {
            this.from = from;
            this.to = to;
            this.target = target;
            this.collision = collision;
            double fx = from.x - 1.0E-7 * (to.x - from.x), fy = from.y - 1.0E-7 * (to.y - from.y), fz = from.z - 1.0E-7 * (to.z - from.z);
            double dx = to.x + 1.0E-7 * (to.x - from.x) - fx;
            double dy = to.y + 1.0E-7 * (to.y - from.y) - fy;
            double dz = to.z + 1.0E-7 * (to.z - from.z) - fz;
            x = (int) Math.floor(fx); y = (int) Math.floor(fy); z = (int) Math.floor(fz);
            signX = (int) Math.signum(dx); signY = (int) Math.signum(dy); signZ = (int) Math.signum(dz);
            deltaX = signX == 0 ? Double.POSITIVE_INFINITY : signX / dx;
            deltaY = signY == 0 ? Double.POSITIVE_INFINITY : signY / dy;
            deltaZ = signZ == 0 ? Double.POSITIVE_INFINITY : signZ / dz;
            nextX = signX == 0 ? Double.POSITIVE_INFINITY : deltaX * (signX > 0 ? 1 - (fx - x) : fx - x);
            nextY = signY == 0 ? Double.POSITIVE_INFINITY : deltaY * (signY > 0 ? 1 - (fy - y) : fy - y);
            nextZ = signZ == 0 ? Double.POSITIVE_INFINITY : deltaZ * (signZ > 0 ? 1 - (fz - z) : fz - z);
        }

        private Ray advance(GeometrySnapshot geometry, Budget budget) {
            while (true) {
                if (boxes == null) {
                    if (!budget.spend()) return Ray.EXHAUSTED;
                    GeometrySnapshot.Cell cell = geometry.cell(x, y, z);
                    if (cell == null) { budget.unavailable = true; blockedFraction = entryFraction; return Ray.BLOCKED; }
                    if (target != null && x == target.getX() && y == target.getY() && z == target.getZ()) return Ray.CLEAR;
                    boxes = collision ? cell.collision() : cell.sight();
                    box = 0;
                }
                while (box < boxes.size()) {
                    if (!budget.spend()) return Ray.EXHAUSTED;
                    AABB shape = boxes.get(box++);
                    double hit = hitFraction(shape, from, to, x, y, z);
                    if (hit >= 0) { blockedFraction = hit; return Ray.BLOCKED; }
                }
                boxes = null;
                if (nextX > 1 && nextY > 1 && nextZ > 1) return target == null ? Ray.CLEAR : Ray.BLOCKED;
                if (nextX < nextY) {
                    if (nextX < nextZ) { entryFraction = nextX; x += signX; nextX += deltaX; }
                    else { entryFraction = nextZ; z += signZ; nextZ += deltaZ; }
                } else if (nextY < nextZ) { entryFraction = nextY; y += signY; nextY += deltaY; }
                else { entryFraction = nextZ; z += signZ; nextZ += deltaZ; }
            }
        }
    }

    /** Allocation-free slab intersection with cell-local shapes, inclusive at touching edges. */
    private static double hitFraction(AABB shape, Vec3 from, Vec3 to, int x, int y, int z) {
        double near = 0, far = 1;
        for (int axis = 0; axis < 3; axis++) {
            double start = axis == 0 ? from.x - x : axis == 1 ? from.y - y : from.z - z;
            double delta = axis == 0 ? to.x - from.x : axis == 1 ? to.y - from.y : to.z - from.z;
            double min = axis == 0 ? shape.minX : axis == 1 ? shape.minY : shape.minZ;
            double max = axis == 0 ? shape.maxX : axis == 1 ? shape.maxY : shape.maxZ;
            if (delta == 0) { if (start < min || start > max) return -1; }
            else {
                double a = (min - start) / delta, b = (max - start) / delta;
                near = Math.max(near, Math.min(a, b));
                far = Math.min(far, Math.max(a, b));
                if (near > far) return -1;
            }
        }
        return near;
    }

    /**
     * The through-wall exception is exactly each accepted body plus its swept path, expanded by
     * 1/16 block. The explicit segment cap may support falling/elytra/vehicles; it does not increase
     * the margin or reveal a distance bubble. Rebased teleports use no prior body, so no intervening
     * cells are disclosed. Work/shape bounds refuse unsupported paths rather than silently widen.
     */
    private static boolean safety(GeometrySnapshot geometry, SafetyInput input, ConcealmentPolicy policy,
            Map<Long, Map<Integer, BitSet>> bits) {
        if (safetyBounds(input) == null) return false;
        boolean complete = true;
        for (BodyPath path : input.bodies()) {
            AABB body = path.body(), previous = path.previousBody() == null ? body : path.previousBody();
            AABB envelope = body.inflate(SAFETY_MARGIN).minmax(previous.inflate(SAFETY_MARGIN));
            int minX = (int) Math.floor(envelope.minX), minY = (int) Math.floor(envelope.minY), minZ = (int) Math.floor(envelope.minZ);
            int maxX = (int) Math.ceil(envelope.maxX) - 1, maxY = (int) Math.ceil(envelope.maxY) - 1, maxZ = (int) Math.ceil(envelope.maxZ) - 1;
            Vec3 from = previous.getCenter(), to = body.getCenter();
            double halfX = Math.max(body.getXsize(), previous.getXsize()) / 2 + SAFETY_MARGIN;
            double halfY = Math.max(body.getYsize(), previous.getYsize()) / 2 + SAFETY_MARGIN;
            double halfZ = Math.max(body.getZsize(), previous.getZsize()) / 2 + SAFETY_MARGIN;
            AABB expandedUnit = new AABB(-halfX, -halfY, -halfZ, 1 + halfX, 1 + halfY, 1 + halfZ);
            for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
                if (!policy.protects(y) || hitFraction(expandedUnit, from, to, x, y, z) < 0) continue;
                BlockPos pos = new BlockPos(x, y, z);
                if (geometry.cell(pos) == null) complete = false;
                else reveal(bits, pos);
            }
        }
        return complete;
    }

    private static SafetyBounds safetyBounds(SafetyInput input) {
        AABB combined = null;
        long scans = 0;
        for (BodyPath path : input.bodies()) {
            AABB body = path.body(), previous = path.previousBody() == null ? body : path.previousBody();
            if (body.getXsize() > 4 || body.getYsize() > 4 || body.getZsize() > 4
                    || previous.getXsize() > 4 || previous.getYsize() > 4 || previous.getZsize() > 4
                    || previous.getCenter().distanceToSqr(body.getCenter()) > input.maxAcceptedSegment() * input.maxAcceptedSegment()) return null;
            AABB envelope = body.inflate(SAFETY_MARGIN).minmax(previous.inflate(SAFETY_MARGIN));
            long sx = (long) Math.ceil(envelope.maxX) - (long) Math.floor(envelope.minX);
            long sy = (long) Math.ceil(envelope.maxY) - (long) Math.floor(envelope.minY);
            long sz = (long) Math.ceil(envelope.maxZ) - (long) Math.floor(envelope.minZ);
            scans += sx * sy * sz;
            if (scans > MAX_SAFETY_SCAN_CELLS) return null;
            combined = combined == null ? envelope : combined.minmax(envelope);
        }
        BlockPos min = new BlockPos((int) Math.floor(combined.minX), (int) Math.floor(combined.minY), (int) Math.floor(combined.minZ));
        BlockPos max = new BlockPos((int) Math.ceil(combined.maxX) - 1, (int) Math.ceil(combined.maxY) - 1, (int) Math.ceil(combined.maxZ) - 1);
        long sx = (long) max.getX() - min.getX() + 1, sy = (long) max.getY() - min.getY() + 1, sz = (long) max.getZ() - min.getZ() + 1;
        if (sx > 35 || sy > 35 || sz > 35 || sx * sy * sz > GeometrySnapshot.MAX_BLOCKS) return null;
        return new SafetyBounds(min, max, (int) (sx * sy * sz));
    }

    private static void reveal(Map<Long, Map<Integer, BitSet>> bits, BlockPos pos) {
        bits.computeIfAbsent(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4), ignored -> new HashMap<>())
                .computeIfAbsent(pos.getY() >> 4, ignored -> new BitSet(SectionVisibility.BLOCK_COUNT))
                .set(SectionVisibility.index(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15));
    }

    private static void checkRange(int range) {
        if (range < 1 || range > MAX_RANGE) throw new IllegalArgumentException("Visibility range must be in [1, 16]");
    }

    /** eyes/body must be copied from accepted server state; this is not a client camera API. */
    public record Viewpoint(Vec3 eyes, AABB body, AABB previousBody, boolean thirdPerson) {
        public Viewpoint {
            if (eyes == null || body == null || !finite(eyes.x, eyes.y, eyes.z, body.minX, body.minY, body.minZ,
                    body.maxX, body.maxY, body.maxZ) || body.getXsize() <= 0 || body.getYsize() <= 0 || body.getZsize() <= 0
                    || !body.inflate(0.1).contains(eyes)) throw new IllegalArgumentException("Invalid accepted viewpoint");
            if (previousBody != null && (!finite(previousBody.minX, previousBody.minY, previousBody.minZ,
                    previousBody.maxX, previousBody.maxY, previousBody.maxZ) || previousBody.getXsize() <= 0
                    || previousBody.getYsize() <= 0 || previousBody.getZsize() <= 0)) throw new IllegalArgumentException("Invalid prior body");
        }
        private static boolean finite(double... values) {
            for (double value : values) if (!Double.isFinite(value) || Math.abs(value) > 30_000_064) return false;
            return true;
        }
    }

    public record Result(Map<Long, Map<Integer, SectionVisibility>> chunks, boolean complete,
            boolean safetyComplete, int candidates, int rayWork, int snapshotBlocks, int unavailableBlocks) {}
    public record BodyPath(AABB body, AABB previousBody) {
        public BodyPath {
            checkBody(body);
            if (previousBody != null) checkBody(previousBody);
        }
        private static void checkBody(AABB body) {
            if (body == null || !Viewpoint.finite(body.minX, body.minY, body.minZ, body.maxX, body.maxY, body.maxZ)
                    || body.getXsize() <= 0 || body.getYsize() <= 0 || body.getZsize() <= 0)
                throw new IllegalArgumentException("Invalid server-accepted body");
        }
    }
    public record SafetyInput(List<BodyPath> bodies, double maxAcceptedSegment) {
        public SafetyInput {
            bodies = List.copyOf(bodies);
            if (bodies.isEmpty() || bodies.size() > 2 || !Double.isFinite(maxAcceptedSegment)
                    || maxAcceptedSegment < 1 || maxAcceptedSegment > MAX_ACCEPTED_MOVEMENT)
                throw new IllegalArgumentException("Safety requires one or two accepted bodies and an explicit segment cap in [1,24]");
        }
    }
    private record SafetyBounds(BlockPos min, BlockPos max, int blocks) {}
    public record SightResult(boolean clear, boolean complete, int rayWork, int snapshotBlocks) {}
    private enum Ray { CLEAR, BLOCKED, EXHAUSTED }
    private static final class Budget {
        private final int limit;
        private int used;
        private boolean unavailable;
        private final java.util.function.BooleanSupplier hasTime;
        private Budget(int limit) { this(limit, () -> true); }
        private Budget(int limit, java.util.function.BooleanSupplier hasTime) { this.limit = limit; this.hasTime = hasTime; }
        private boolean spend() {
            if (used >= limit || !hasTime.getAsBoolean()) return false;
            if (com.evanchubbuck.blindstone.diagnostics.WorkMetrics.enabled) com.evanchubbuck.blindstone.diagnostics.WorkMetrics.rayChecks++;
            used++;
            return true;
        }
    }
}
