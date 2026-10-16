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
    private static final int MAX_SAFETY_BLOCKS = 96;
    private static final double[][] SAMPLES = {
        {0.5, 0.5, 0.5}, {0.001, 0.5, 0.5}, {0.999, 0.5, 0.5},
        {0.5, 0.001, 0.5}, {0.5, 0.999, 0.5}, {0.5, 0.5, 0.001}, {0.5, 0.5, 0.999}
    };
    private static final Vec3[] CAMERA_OFFSETS = {
        new Vec3(4, 0, 0), new Vec3(-4, 0, 0), new Vec3(0, 4, 0),
        new Vec3(0, -4, 0), new Vec3(0, 0, 4), new Vec3(0, 0, -4)
    };

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
        Map<Long, LevelChunk> loaded = new HashMap<>();
        for (int z = min.getZ() >> 4; z <= max.getZ() >> 4; z++) for (int x = min.getX() >> 4; x <= max.getX() >> 4; x++) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
            if (chunk != null) loaded.put(ChunkPos.pack(x, z), chunk);
        }
        // getBlockState is called only after the predicate established a loaded chunk.
        return GeometrySnapshot.capture(level, min, max, pos -> level.isInsideBuildHeight(pos)
                && loaded.containsKey(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4)));
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
        checkPoints(from, to);
        if (maxRayWork < 1 || maxRayWork > 20_000_000) throw new IllegalArgumentException("Point sight work budget outside fixed bounds");
        Budget budget = new Budget(maxRayWork);
        Ray status = new RayCursor(from, to, null, false).advance(geometry, budget);
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
        return createJob(geometry, view, policy, range).step(maxCandidates, maxRayWork);
    }

    public static Job createJob(GeometrySnapshot geometry, Viewpoint view, ConcealmentPolicy policy, int range) {
        checkRange(range);
        return new Job(geometry, view, policy, range);
    }

    /** Independently refresh this fixed body envelope each tick while an older sight job progresses. */
    public static Result evaluateSafety(GeometrySnapshot geometry, Viewpoint view, ConcealmentPolicy policy) {
        Map<Long, Map<Integer, BitSet>> bits = new HashMap<>();
        boolean complete = safety(geometry, view, policy, bits);
        return result(geometry, bits, complete, complete, 0, 0);
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
        private final Map<Long, Map<Integer, BitSet>> safetyBits = new HashMap<>();
        private final boolean safetyComplete;
        private boolean unavailable;
        private int cameraOffset;
        private int cameraCorner;
        private int candidate;
        private int origin;
        private int sample;
        private RayCursor ray;

        private Job(GeometrySnapshot geometry, Viewpoint view, ConcealmentPolicy policy, int range) {
            this.geometry = geometry;
            this.view = view;
            safetyComplete = safety(geometry, view, policy, safetyBits);
            origins.add(view.eyes());
            cameraOffset = view.thirdPerson() ? 0 : CAMERA_OFFSETS.length;
            BlockPos center = BlockPos.containing(view.eyes());
            for (int y = -range; y <= range; y++) for (int z = -range; z <= range; z++) for (int x = -range; x <= range; x++) {
                BlockPos pos = center.offset(x, y, z);
                if (policy.protects(pos.getY()) && Vec3.atCenterOf(pos).distanceToSqr(view.eyes()) <= range * range)
                    candidates.add(pos);
            }
            candidates.sort(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(view.eyes())));
        }

        public boolean done() { return cameraOffset == CAMERA_OFFSETS.length && candidate == candidates.size(); }
        public int completedCandidates() { return candidate; }
        public int totalCandidates() { return candidates.size(); }
        public boolean intersectsChanged(BlockPos pos) { return geometry.intersectsChanged(pos); }

        public Result step(int maxCandidates, int maxRayWork) {
            if (maxCandidates < 1 || maxCandidates > 100_000 || maxRayWork < 1 || maxRayWork > 20_000_000)
                throw new IllegalArgumentException("Visibility budgets outside fixed bounds");
            Budget budget = new Budget(maxRayWork);
            Map<Long, Map<Integer, BitSet>> bits = new HashMap<>();
            safetyBits.forEach((chunk, sections) -> {
                Map<Integer, BitSet> copy = new HashMap<>();
                sections.forEach((section, mask) -> copy.put(section, (BitSet) mask.clone()));
                bits.put(chunk, copy);
            });
            // Admit every explicit camera only after all nine clearance rays have completed.
            while (cameraOffset < CAMERA_OFFSETS.length) {
                Vec3 camera = view.eyes().add(CAMERA_OFFSETS[cameraOffset]);
                if (ray == null) {
                    Vec3 corner = cameraCorner == 8 ? Vec3.ZERO : new Vec3(
                            (cameraCorner & 1) == 0 ? -CAMERA_HALF_WIDTH : CAMERA_HALF_WIDTH,
                            (cameraCorner & 2) == 0 ? -CAMERA_HALF_WIDTH : CAMERA_HALF_WIDTH,
                            (cameraCorner & 4) == 0 ? -CAMERA_HALF_WIDTH : CAMERA_HALF_WIDTH);
                    ray = new RayCursor(view.eyes().add(corner), camera.add(corner), null, true);
                }
                Ray status = ray.advance(geometry, budget);
                if (status == Ray.EXHAUSTED) break;
                ray = null;
                if (status == Ray.BLOCKED) { cameraOffset++; cameraCorner = 0; }
                else if (++cameraCorner == 9) { origins.add(camera); cameraOffset++; cameraCorner = 0; }
            }
            int checked = 0;
            while (cameraOffset == CAMERA_OFFSETS.length && candidate < candidates.size() && checked < maxCandidates) {
                BlockPos pos = candidates.get(candidate);
                if (geometry.cell(pos) == null) {
                    budget.unavailable = true;
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
            return result(geometry, bits, done() && !unavailable && safetyComplete, safetyComplete, checked, budget.used);
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
                    BlockPos pos = new BlockPos(x, y, z);
                    GeometrySnapshot.Cell cell = geometry.cell(pos);
                    if (cell == null) { budget.unavailable = true; return Ray.BLOCKED; }
                    if (pos.equals(target)) return Ray.CLEAR;
                    boxes = collision ? cell.collision() : cell.sight();
                    box = 0;
                }
                while (box < boxes.size()) {
                    if (!budget.spend()) return Ray.EXHAUSTED;
                    AABB shape = boxes.get(box++);
                    if (shape.contains(from) || shape.clip(from, to).isPresent()) return Ray.BLOCKED;
                }
                boxes = null;
                if (nextX > 1 && nextY > 1 && nextZ > 1) return target == null ? Ray.CLEAR : Ray.BLOCKED;
                if (nextX < nextY) {
                    if (nextX < nextZ) { x += signX; nextX += deltaX; }
                    else { z += signZ; nextZ += deltaZ; }
                } else if (nextY < nextZ) { y += signY; nextY += deltaY; }
                else { z += signZ; nextZ += deltaZ; }
            }
        }
    }

    /**
     * The only through-wall exception: cells intersecting the accepted body expanded by exactly
     * 1/16 block, plus that body's swept path for an accepted segment <=1.5 blocks. This can expose
     * a neighboring cell when a body nearly touches its wall. No surrounding radius is revealed.
     * Larger supplied bodies or longer segments fail closed; mounted vehicle extents require a
     * runtime policy, because the supplied player body alone cannot describe them. Rebase teleports
     * and deliver destination safety before permitting movement, rather than enlarge this envelope.
     */
    private static boolean safety(GeometrySnapshot geometry, Viewpoint view, ConcealmentPolicy policy,
            Map<Long, Map<Integer, BitSet>> bits) {
        AABB body = view.body();
        if (body.getXsize() > 1 || body.getYsize() > 2 || body.getZsize() > 1) return false;
        AABB previous = view.previousBody() == null ? body : view.previousBody();
        if (previous.getXsize() > 1 || previous.getYsize() > 2 || previous.getZsize() > 1
                || previous.getCenter().distanceToSqr(body.getCenter()) > MAX_MOVEMENT_SEGMENT * MAX_MOVEMENT_SEGMENT)
            return false;
        AABB envelope = body.inflate(SAFETY_MARGIN).minmax(previous.inflate(SAFETY_MARGIN));
        int minX = (int) Math.floor(envelope.minX), minY = (int) Math.floor(envelope.minY), minZ = (int) Math.floor(envelope.minZ);
        int maxX = (int) Math.ceil(envelope.maxX) - 1, maxY = (int) Math.ceil(envelope.maxY) - 1, maxZ = (int) Math.ceil(envelope.maxZ) - 1;
        long cells = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (cells > MAX_SAFETY_BLOCKS) return false;
        boolean complete = true;
        for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
            BlockPos pos = new BlockPos(x, y, z);
            // Minkowski expansion tests the swept body, avoiding the diagonal union's corner cells.
            AABB sweptCell = new AABB(pos).inflate(Math.max(body.getXsize(), previous.getXsize()) / 2 + SAFETY_MARGIN,
                    Math.max(body.getYsize(), previous.getYsize()) / 2 + SAFETY_MARGIN,
                    Math.max(body.getZsize(), previous.getZsize()) / 2 + SAFETY_MARGIN);
            if (!sweptCell.contains(body.getCenter()) && !sweptCell.contains(previous.getCenter())
                    && sweptCell.clip(previous.getCenter(), body.getCenter()).isEmpty()) continue;
            if (policy.protects(y)) {
                if (geometry.cell(pos) == null) complete = false;
                else reveal(bits, pos);
            }
        }
        return complete;
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
    public record SightResult(boolean clear, boolean complete, int rayWork, int snapshotBlocks) {}
    private enum Ray { CLEAR, BLOCKED, EXHAUSTED }
    private static final class Budget {
        private final int limit;
        private int used;
        private boolean unavailable;
        private Budget(int limit) { this.limit = limit; }
        private boolean spend() {
            if (used >= limit) return false;
            used++;
            return true;
        }
    }
}
