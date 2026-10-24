package com.evanchubbuck.blindstone;

import com.evanchubbuck.blindstone.visibility.GeometrySnapshot;
import com.evanchubbuck.blindstone.visibility.VisibilityEvaluator;
import com.evanchubbuck.blindstone.runtime.BlindstoneRuntime;
import com.mojang.authlib.GameProfile;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Geometry tests run with 26.3 vanilla shapes; they do not establish live-client movement safety. */
public final class VisibilityGameTests {
    private static final ConcealmentPolicy POLICY = new ConcealmentPolicy(-16, 16);

    @GameTest
    public void sealedRoomPrunesDisconnectedDefaultRangeCandidates(GameTestHelper helper) {
        var world=new HashMap<BlockPos,BlockState>();
        for(int y=-2;y<=4;y++)for(int z=-4;z<=4;z++)for(int x=-4;x<=4;x++)
            if(y<0 || y>2 || Math.abs(x)>2 || Math.abs(z)>2)world.put(new BlockPos(x,y,z),Blocks.STONE.defaultBlockState());
        var geometry=GeometrySnapshot.capture(source(world),new BlockPos(-17,-16,-17),new BlockPos(17,18,17),pos->true);
        var policy=new ConcealmentPolicy(-32,64);
        var job=VisibilityEvaluator.createJob(geometry,viewpoint(true),policy,16,Map.of());
        check(helper,job.totalCandidates()<=200,"Sealed room must omit disconnected air and buried terrain before sorting/ray work: "+job.totalCandidates());
        var result=job.step(32768,1_000_000);
        check(helper,job.done() && result.rayWork()<20000,"Sealed room must finish within a small deterministic ray bound");
        check(helper,revealed(result,new BlockPos(3,1,0)),"Visible wall remains revealed");
        check(helper,!revealed(result,new BlockPos(6,1,0)),"Disconnected exterior stays hidden");
        helper.succeed();
    }

    @GameTest
    public void sealedAdjacentRoomStaysHiddenEvenWithThirdPersonOffsets(GameTestHelper helper) {
        var world = wall(Blocks.STONE.defaultBlockState());
        var result = evaluate(snapshot(world), viewpoint(true), 6);
        check(helper, revealed(result, new BlockPos(2, 1, 0)), "First visible wall must be revealed");
        check(helper, !revealed(result, new BlockPos(3, 1, 0)), "A sealed adjacent room cannot be revealed through its wall");
        check(helper, !revealed(result, new BlockPos(4, 1, 0)), "Camera offsets cannot be placed across the wall");
        check(helper, revealed(result, new BlockPos(1, 1, 0)), "Accessible cave air in front of the wall must be revealed");
        helper.succeed();
    }

    @GameTest
    public void visibilityCoversEveryDirectionWithoutTrustingPlayerLook(GameTestHelper helper) {
        var result = evaluate(snapshot(new HashMap<>()), viewpoint(false), 6);
        for (BlockPos pos : new BlockPos[] {new BlockPos(5, 1, 0), new BlockPos(-5, 1, 0),
                new BlockPos(0, 1, 5), new BlockPos(0, 1, -5), new BlockPos(0, 6, 0), new BlockPos(0, -3, 0)}) {
            check(helper, revealed(result, pos), "A visible point in each direction must be considered: " + pos);
        }
        check(helper, !revealed(result, new BlockPos(7, 1, 0)), "Seeing cave air must not reveal the rest of a cave or chunk");
        check(helper, result.complete(), "An open loaded fixture must complete with ample budget");
        helper.succeed();
    }

    @GameTest
    public void glassAndWaterTransmitSightWhileLavaBlocksIt(GameTestHelper helper) {
        BlockPos behind = new BlockPos(4, 1, 0);
        check(helper, revealed(evaluate(snapshot(wall(Blocks.GLASS.defaultBlockState())), viewpoint(false), 6), behind),
                "Transparent glass must not behave like an opaque wall");
        check(helper, revealed(evaluate(snapshot(wall(Blocks.GLASS_PANE.defaultBlockState())), viewpoint(false), 6), behind),
                "Glass panes transmit sight but retain separate collision geometry");
        check(helper, revealed(evaluate(snapshot(wall(Blocks.WATER.defaultBlockState())), viewpoint(false), 6), behind),
                "Water volume is transparent for this explicit visibility policy");
        check(helper, !revealed(evaluate(snapshot(wall(Blocks.LAVA.defaultBlockState())), viewpoint(false), 6), behind),
                "An opaque lava volume must stop sight at its surface");
        helper.succeed();
    }

    @GameTest
    public void opaqueTraversableAndMovingBlocksCannotBecomeTransparent(GameTestHelper helper) {
        BlockPos behind = new BlockPos(4, 1, 0);
        var powder = evaluate(snapshot(wall(Blocks.POWDER_SNOW.defaultBlockState())), viewpoint(true), 6);
        check(helper, !revealed(powder, behind), "Powder snow must block sight and camera offsets despite empty vanilla visual/collision shapes");
        var moving = evaluate(snapshot(wall(Blocks.MOVING_PISTON.defaultBlockState())), viewpoint(true), 6);
        check(helper, !revealed(moving, behind), "Missing animated piston block entities must never turn a wall into transparent air");
        var isolated = new HashMap<BlockPos, BlockState>();
        // The ray crosses the possible extension cell at Z0, never the placeholder cell at Z1.
        isolated.put(new BlockPos(3, 1, 1), Blocks.MOVING_PISTON.defaultBlockState());
        var conservative = evaluate(snapshot(isolated), viewpoint(false), 6);
        check(helper, !revealed(conservative, new BlockPos(5, 1, 0)),
                "Conservative animated geometry must include the adjacent cell into which the piston can extend");
        helper.succeed();
    }

    @GameTest
    public void transparentWallStillConstrainsThirdPersonCamera(GameTestHelper helper) {
        // Only a camera at X4 can see through the narrow opening in the perpendicular stone wall.
        // Its path crosses glass: sight may cross that glass, but the camera body cannot.
        var world = wall(Blocks.GLASS.defaultBlockState());
        for (int y = -10; y <= 10; y++) for (int x = -10; x <= 10; x++) {
            if (x != 4) world.put(new BlockPos(x, y, 2), Blocks.STONE.defaultBlockState());
        }
        check(helper, !revealed(evaluate(snapshot(world), viewpoint(true), 6), new BlockPos(4, 1, 4)),
                "A transparent glass wall cannot authorize a camera on its opposite side");
        for (int y = -10; y <= 10; y++) for (int z = -10; z <= 10; z++) {
            BlockPos pos = new BlockPos(2, y, z);
            if (world.get(pos) != null && world.get(pos).is(Blocks.GLASS)) world.remove(pos);
        }
        check(helper, revealed(evaluate(snapshot(world), viewpoint(true), 6), new BlockPos(4, 1, 4)),
                "The same clear-path camera position is permitted when the glass obstruction is removed");
        helper.succeed();
    }

    @GameTest
    public void closedAndOpenDoorsUseActualVoxelShape(GameTestHelper helper) {
        var world = wall(Blocks.STONE.defaultBlockState());
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
        world.put(new BlockPos(2, 0, 0), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        world.put(new BlockPos(2, 1, 0), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        check(helper, !revealed(evaluate(snapshot(world), viewpoint(false), 6), new BlockPos(4, 1, 0)),
                "A closed door blocks sight through its plane");
        world.put(new BlockPos(2, 0, 0), world.get(new BlockPos(2, 0, 0)).setValue(DoorBlock.OPEN, true));
        world.put(new BlockPos(2, 1, 0), world.get(new BlockPos(2, 1, 0)).setValue(DoorBlock.OPEN, true));
        check(helper, revealed(evaluate(snapshot(world), viewpoint(false), 6), new BlockPos(4, 1, 0)),
                "An opened door must permit the actual clear ray");
        helper.succeed();
    }

    @GameTest
    public void partialSlabLeavesItsRealOpeningVisible(GameTestHelper helper) {
        var world = wall(Blocks.STONE.defaultBlockState());
        world.put(new BlockPos(2, 1, 0), Blocks.STONE_SLAB.defaultBlockState());
        check(helper, revealed(evaluate(snapshot(world), viewpoint(false), 6), new BlockPos(4, 1, 0)),
                "A bottom slab must not occlude air above its actual half-block shape");
        check(helper, !revealed(evaluate(snapshot(world), viewpoint(false), 6), new BlockPos(4, -2, 0)),
                "A partial opening does not authorize unrelated cells behind solid parts of the wall");
        helper.succeed();
    }

    @GameTest
    public void snapshotAndNestedResultAreImmutable(GameTestHelper helper) {
        var world = wall(Blocks.STONE.defaultBlockState());
        var frozen = snapshot(world);
        world.clear();
        var result = evaluate(frozen, viewpoint(false), 6);
        check(helper, !revealed(result, new BlockPos(4, 1, 0)), "Later live-world edits cannot mutate an existing geometry snapshot");
        boolean immutable = false;
        try { result.chunks().values().iterator().next().clear(); }
        catch (UnsupportedOperationException expected) { immutable = true; }
        check(helper, immutable, "Nested section maps must be immutable");
        immutable = false;
        try { result.chunks().clear(); }
        catch (UnsupportedOperationException expected) { immutable = true; }
        check(helper, immutable, "Chunk map must be immutable");
        helper.succeed();
    }

    @GameTest
    public void budgetsStopWithoutOpeningHiddenOrDistantCells(GameTestHelper helper) {
        var geometry = snapshot(wall(Blocks.STONE.defaultBlockState()));
        var result = VisibilityEvaluator.evaluate(geometry, viewpoint(false), POLICY, 6, 10_000, 1);
        check(helper, !result.complete() && result.rayWork() <= 1, "Ray work must stop exactly at its configured ceiling");
        check(helper, result.safetyComplete(), "Bounded body safety is independent of optional sight budget");
        check(helper, !revealed(result, new BlockPos(4, 1, 0)), "Budget exhaustion must not reveal a sealed room");
        check(helper, !revealed(result, new BlockPos(-4, 1, 0)), "Budget exhaustion must not silently reveal distant cells");
        result = VisibilityEvaluator.evaluate(geometry, viewpoint(false), POLICY, 6, 1, 1_000_000);
        check(helper, !result.complete() && result.candidates() == 1, "Candidate count must stop exactly at its budget");
        helper.succeed();
    }

    @GameTest
    public void resumableJobsFinishOuterRangeAndMatchUnboundedVisibility(GameTestHelper helper) {
        var geometry = snapshot(wall(Blocks.STONE.defaultBlockState()));
        var view = viewpoint(true);
        var expected = evaluate(geometry, view, 4);
        var job = VisibilityEvaluator.createJob(geometry, view, POLICY, 4);
        Map<Long, Map<Integer, SectionVisibility>> delivered = new HashMap<>();
        int steps = 0;
        VisibilityEvaluator.Result last = null;
        while (!job.done() && steps++ < 20_000) {
            last = job.step(3, 17);
            check(helper, last.rayWork() <= 17 && last.candidates() <= 3, "Every resumed step must obey both independent budgets");
            merge(delivered, last.chunks());
        }
        check(helper, job.done() && steps > 1 && last != null && last.complete(), "Small bounded slices must eventually finish the entire fixed job");
        check(helper, job.completedCandidates() == job.totalCandidates(), "No candidate can be lost when a slice ends within its rays");
        for (int y = -4; y <= 5; y++) for (int z = -4; z <= 4; z++) for (int x = -4; x <= 4; x++) {
            BlockPos pos = new BlockPos(x, y, z);
            check(helper, revealed(delivered, pos) == revealed(expected, pos), "Sliced visibility must equal an uninterrupted evaluation at " + pos);
        }
        check(helper, revealed(delivered, new BlockPos(-3, 1, 0)), "Repeated small budgets must eventually reach visible cells beyond their first nearest prefix");
        check(helper, !revealed(delivered, new BlockPos(3, 1, 0)), "Resumption must never widen sight across the sealed wall");
        helper.succeed();
    }

    @GameTest
    public void aSingleWorkUnitCanResumeWithinCameraAndShapeTests(GameTestHelper helper) {
        var job = VisibilityEvaluator.createJob(snapshot(wall(Blocks.STONE.defaultBlockState())), viewpoint(true), POLICY, 2);
        Map<Long, Map<Integer, SectionVisibility>> delivered = new HashMap<>();
        int steps = 0;
        while (!job.done() && steps++ < 10_000) {
            var result = job.step(1, 1);
            check(helper, result.rayWork() <= 1, "A resumed voxel or shape-box test cannot borrow future work budget");
            merge(delivered, result.chunks());
        }
        check(helper, job.done() && steps > 100, "Even camera rays and opaque box intersections must progress with one work unit");
        check(helper, revealed(delivered, new BlockPos(1, 1, 0)), "Tiny work slices must eventually publish justified cave air");
        helper.succeed();
    }

    @GameTest
    public void entityPointSightUsesTheSameOpaqueFluidAndTraversableBlockPolicy(GameTestHelper helper) {
        Vec3 from = viewpoint(false).eyes(), to = new Vec3(4.5, 1.5, 0.5);
        for (BlockState opaque : new BlockState[] {Blocks.POWDER_SNOW.defaultBlockState(), Blocks.LAVA.defaultBlockState()}) {
            var sight = VisibilityEvaluator.clearSight(snapshot(wall(opaque)), from, to, 256);
            check(helper, sight.complete() && !sight.clear(), "Previously authorized entity cells still require current sight through opaque " + opaque);
        }
        for (BlockState transparent : new BlockState[] {Blocks.WATER.defaultBlockState(), Blocks.GLASS.defaultBlockState()}) {
            check(helper, VisibilityEvaluator.clearSight(snapshot(wall(transparent)), from, to, 256).clear(),
                    "Explicit transparent geometry must permit legitimate point sight through " + transparent);
        }
        var limited = VisibilityEvaluator.clearSight(snapshot(new HashMap<>()), from, to, 1);
        check(helper, !limited.clear() && !limited.complete() && limited.rayWork() <= 1,
                "An entity point ray cannot use exhaustion as permission to reveal");
        check(helper, VisibilityEvaluator.sightSnapshotBlockBound(from, to) < 100,
                "A short horizontal entity sight path must allocate only its narrow box and halo");
        helper.succeed();
    }

    @GameTest
    public void remoteWorldChangesRetainRuntimeJobWhileLocalGeometryRejectsIt(GameTestHelper helper) throws ReflectiveOperationException {
        var server = helper.getLevel().getServer();
        var runtime = BlindstoneRuntime.get(server);
        check(helper, runtime != null, "Lifecycle must initialize the closed-admission runtime");
        var player = new ServerPlayer(server, helper.getLevel(), new GameProfile(UUID.randomUUID(), "blindstone_geometry"), ClientInformation.createDefault());
        // A synthetic accepted viewer needs no socket: this test exercises job invalidation only.
        var createSession = BlindstoneRuntime.class.getDeclaredMethod("session", ServerPlayer.class);
        createSession.setAccessible(true);
        Object session = createSession.invoke(runtime, player);
        var jobField = session.getClass().getDeclaredField("job");
        jobField.setAccessible(true);
        try {
            var job = VisibilityEvaluator.createJob(snapshot(wall(Blocks.STONE.defaultBlockState())), viewpoint(false), POLICY, 4);
            job.step(1, 1);
            int before = job.completedCandidates();
            jobField.set(session, job);
            runtime.geometryChanged(helper.getLevel(), new BlockPos(1000, 1, 1000));
            check(helper, jobField.get(session) == job && job.completedCandidates() == before,
                    "Unrelated remote fluids/redstone must preserve the exact in-flight job and its progress");
            for (int slice = 0; slice < 16 && job.completedCandidates() <= before; slice++) job.step(1, 1);
            check(helper, job.completedCandidates() > before, "The retained job must continue beyond its prior nearest prefix");
            check(helper, job.intersectsChanged(new BlockPos(11, 1, 0)), "Neighbor-dependent shapes include a one-cell change halo outside the frozen bounds");
            check(helper, !job.intersectsChanged(new ChunkPos(100, 100)) && job.intersectsChanged(new ChunkPos(-1, 0)),
                    "Remote chunk generation must preserve jobs while negative-coordinate chunks intersecting the captured halo invalidate them");
            runtime.geometryChanged(helper.getLevel(), new BlockPos(2, 1, 0));
            check(helper, jobField.get(session) == null, "A door/wall change inside captured geometry must reject the stale in-flight job");
            jobField.set(session, job);
            runtime.reset(player);
            check(helper, jobField.get(session) == null, "An accepted teleport/respawn reset cannot reuse a job from the prior origin");
        } finally {
            runtime.remove(player);
            player.discard();
        }
        helper.succeed();
    }

    @GameTest
    public void fastFallingAndElytraPathsKeepTheirThinExplicitSafetyEnvelope(GameTestHelper helper) {
        AABB fallen = new AABB(0.2, -8, 0.2, 0.8, -6.2, 0.8);
        var fall = new VisibilityEvaluator.SafetyInput(java.util.List.of(new VisibilityEvaluator.BodyPath(fallen, fallen.move(0, 8, 0))), 16);
        var available = GeometrySnapshot.availability(new BlockPos(-1, -9, -1), new BlockPos(10, 4, 1), pos -> true);
        var result = VisibilityEvaluator.evaluateSafety(available, fall, POLICY);
        check(helper, result.safetyComplete() && revealed(result, new BlockPos(0, -4, 0)),
                "An accepted eight-block fall must restore its swept body path");
        check(helper, !revealed(result, new BlockPos(2, -4, 0)), "Fast falling cannot reveal a neighboring room through a distance bubble");
        AABB gliding = new AABB(8.2, 1, 0.2, 8.8, 1.6, 0.8);
        var flight = new VisibilityEvaluator.SafetyInput(java.util.List.of(new VisibilityEvaluator.BodyPath(gliding, gliding.move(-8, 0, 0))), 16);
        result = VisibilityEvaluator.evaluateSafety(available, flight, POLICY);
        check(helper, result.safetyComplete() && revealed(result, new BlockPos(4, 1, 0)),
                "Accepted fast gliding must restore the traveled body corridor");
        check(helper, !revealed(result, new BlockPos(4, 1, 1)), "Flight safety remains body-width rather than an expanded through-wall radius");
        var capped = new VisibilityEvaluator.SafetyInput(flight.bodies(), 2);
        check(helper, VisibilityEvaluator.safetySnapshotBlockBound(capped) == Integer.MAX_VALUE
                && !VisibilityEvaluator.evaluateSafety(available, capped, POLICY).safetyComplete(),
                "Movement beyond the administrator's explicit accepted cap must be rejected before capture");
        helper.succeed();
    }

    @GameTest
    public void boatAndMinecartSafetyIncludesBothVanillaVehicleAndRiderBodies(GameTestHelper helper) {
        var available = GeometrySnapshot.availability(new BlockPos(-2, -1, -2), new BlockPos(11, 4, 2), pos -> true);
        for (var type : java.util.List.of(EntityTypes.OAK_BOAT, EntityTypes.MINECART)) {
            AABB vehicle = type.getDimensions().makeBoundingBox(new Vec3(8.5, 0, 0.5));
            AABB rider = new AABB(8.2, 0.6, 0.2, 8.8, 2.4, 0.8);
            var input = new VisibilityEvaluator.SafetyInput(java.util.List.of(
                    new VisibilityEvaluator.BodyPath(rider, rider.move(-8, 0, 0)),
                    new VisibilityEvaluator.BodyPath(vehicle, vehicle.move(-8, 0, 0))), 16);
            var result = VisibilityEvaluator.evaluateSafety(available, input, POLICY);
            check(helper, result.safetyComplete(), "Vanilla vehicle dimensions must fit the explicit body safety bounds: " + type);
            check(helper, revealed(result, new BlockPos(4, 0, -1)), "The vehicle's actual width must restore cells outside the rider's narrow body");
            check(helper, revealed(result, new BlockPos(4, 2, 0)), "The rider's upper body must also remain unobstructed");
            check(helper, !revealed(result, new BlockPos(4, 1, 2)), "A mounted path must not reveal neighboring sealed rooms beyond both bodies");
        }
        helper.succeed();
    }

    @GameTest
    public void rebasedTeleportSafetyCopiesOnlyDestinationAndCannotAuthorizeSight(GameTestHelper helper) {
        AABB destination = new AABB(128.2, 0, 0.2, 128.8, 1.8, 0.8);
        var input = VisibilityEvaluator.rebaseSafety(java.util.List.of(destination), 16);
        var available = GeometrySnapshot.availability(new BlockPos(128, -1, 0), new BlockPos(128, 1, 0), pos -> true);
        var result = VisibilityEvaluator.evaluateSafety(available, input, POLICY);
        check(helper, result.safetyComplete() && revealed(result, new BlockPos(128, 1, 0)), "Accepted teleport rebase restores destination body cells");
        check(helper, !revealed(result, new BlockPos(64, 1, 0)) && !revealed(result, new BlockPos(0, 1, 0)),
                "Teleport rebasing must never disclose the intervening corridor or old origin");
        check(helper, VisibilityEvaluator.safetySnapshotBlockBound(input) <= 8,
                "Stationary destination safety must copy only a handful of availability cells");
        boolean rejected = false;
        try { VisibilityEvaluator.clearSight(available, new Vec3(128.5, 1.5, 0.5), new Vec3(128.5, 1.6, 0.5), 256); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(helper, rejected, "Availability-only body snapshots cannot accidentally turn opaque walls into transparent sight geometry");
        helper.succeed();
    }

    @GameTest
    public void thirdPersonUsesAllTwentySixDirectionsAndClipsCamerasBeforeWalls(GameTestHelper helper) {
        var clearGeometry = snapshot(new HashMap<>());
        var clear = VisibilityEvaluator.createJob(clearGeometry, viewpoint(true), POLICY, 1);
        clear.step(10_000, 1_000_000);
        check(helper, clear.done() && clear.cameraOrigins().size() == 27, "Open geometry must admit the eye plus all 26 axis/diagonal camera directions");
        check(helper, clear.cameraOrigins().stream().anyMatch(pos -> Math.abs(pos.x - 0.5) > 1
                && Math.abs(pos.y - 1.62) > 1 && Math.abs(pos.z - 0.5) > 1), "Three-axis diagonal third-person positions must be evaluated");
        var wallGeometry = snapshot(wall(Blocks.STONE.defaultBlockState()));
        var clipped = VisibilityEvaluator.createJob(wallGeometry, viewpoint(true), POLICY, 4);
        clipped.step(10_000, 1_000_000);
        Vec3 eyes = viewpoint(true).eyes();
        check(helper, clipped.cameraOrigins().stream().anyMatch(pos -> pos.x > 1.5 && pos.x < 2
                && Math.abs(pos.y - eyes.y) < 1.0E-8 && Math.abs(pos.z - eyes.z) < 1.0E-8),
                "A camera near a wall should clip inward to its clear path instead of disappearing entirely");
        for (Vec3 camera : clipped.cameraOrigins()) {
            check(helper, camera.x < 2 && VisibilityEvaluator.clearCameraPath(wallGeometry, eyes, camera, 256).clear(),
                    "Every admitted clipped camera must retain a collision- and sight-clear path from accepted eyes");
        }
        helper.succeed();
    }

    @GameTest
    public void animatedStorageLidsConservativelyOccludeTheirNeighboringCell(GameTestHelper helper) {
        for (BlockState state : new BlockState[] {Blocks.CHEST.defaultBlockState(), Blocks.SHULKER_BOX.defaultBlockState()}) {
            Map<BlockPos, BlockState> world = new HashMap<>();
            world.put(new BlockPos(3, 0, 0), state);
            check(helper, !VisibilityEvaluator.clearSight(snapshot(world), viewpoint(false).eyes(), new Vec3(5.5, 1.5, 0.5), 256).clear(),
                    "A potentially animated storage lid cannot leak through the neighboring cell above its owner: " + state);
        }
        helper.succeed();
    }

    @GameTest
    public void unloadedCellsAreOpaqueAndNeverTreatedAsAir(GameTestHelper helper) {
        var source = source(new HashMap<>());
        var geometry = GeometrySnapshot.capture(source, new BlockPos(-10, -10, -10), new BlockPos(10, 10, 10), pos -> pos.getX() < 2);
        var result = evaluate(geometry, viewpoint(false), 6);
        check(helper, !result.complete() && result.unavailableBlocks() > 0, "Missing geometry must be explicitly incomplete");
        check(helper, !revealed(result, new BlockPos(2, 1, 0)) && !revealed(result, new BlockPos(3, 1, 0)),
                "Missing chunks must never authorize guessed real air or anything behind them");
        helper.succeed();
    }

    @GameTest
    public void negativeCoordinatesAndExclusivePolicyBoundsAreExact(GameTestHelper helper) {
        var geometry = snapshot(new HashMap<>());
        var view = new VisibilityEvaluator.Viewpoint(new Vec3(-0.5, -0.38, -0.5),
                new AABB(-0.8, -2, -0.8, -0.2, -0.2, -0.2), null, false);
        var result = evaluate(geometry, view, 6);
        check(helper, revealed(result, new BlockPos(-2, -2, -2)), "Negative chunk and section keys must retain local coordinate bits");
        var limited = VisibilityEvaluator.evaluate(geometry, view, new ConcealmentPolicy(0, 16), 6, 10_000, 1_000_000);
        check(helper, !revealed(limited, new BlockPos(-1, -1, -1)), "Blocks below protection bounds do not enter authorization masks");
        check(helper, revealed(limited, new BlockPos(-1, 0, -1)), "Inclusive minimum protection bound is evaluated");
        var upperView = new VisibilityEvaluator.Viewpoint(new Vec3(0.5, 16.62, 0.5),
                new AABB(0.2, 15, 0.2, 0.8, 16.8, 0.8), null, false);
        var upperGeometry = GeometrySnapshot.capture(source(new HashMap<>()), new BlockPos(-5, 5, -5), new BlockPos(5, 25, 5), pos -> true);
        var upper = VisibilityEvaluator.evaluate(upperGeometry, upperView, POLICY, 3, 10_000, 1_000_000);
        check(helper, revealed(upper, new BlockPos(0, 15, 0)) && !revealed(upper, new BlockPos(0, 16, 0)),
                "Exclusive maximum is respected even inside the safety body");
        helper.succeed();
    }

    @GameTest
    public void invalidGeometryAndBudgetBoundsAreRejectedBeforeAllocation(GameTestHelper helper) {
        boolean rejected = false;
        try { GeometrySnapshot.capture(source(new HashMap<>()), BlockPos.ZERO, new BlockPos(1000, 1000, 1000), pos -> true); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(helper, rejected, "A geometry capture cannot allocate beyond the fixed snapshot bound");
        rejected = false;
        try { VisibilityEvaluator.evaluate(snapshot(new HashMap<>()), viewpoint(false), POLICY, 17, 10_000, 1_000_000); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(helper, rejected, "The hard range bound cannot be bypassed through the generic API");
        rejected = false;
        try { VisibilityEvaluator.evaluate(snapshot(new HashMap<>()), viewpoint(false), POLICY, 6, 0, 1_000_000); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(helper, rejected, "Invalid budgets are rejected instead of treated as unlimited");
        rejected = false;
        try { new VisibilityEvaluator.Viewpoint(new Vec3(Double.NaN, 1, 0), viewpoint(false).body(), null, false); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(helper, rejected, "Nonfinite accepted eye coordinates cannot enter block-coordinate arithmetic");
        rejected = false;
        try { new VisibilityEvaluator.Viewpoint(viewpoint(false).eyes(), viewpoint(false).body(), new AABB(0, 0, 0, 0, 0, 0), false); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(helper, rejected, "An invalid prior body cannot authorize a swept path");
        helper.succeed();
    }

    @GameTest
    public void movementSafetyHasFixedMarginAndRejectsLongSegments(GameTestHelper helper) {
        var world = wall(Blocks.STONE.defaultBlockState());
        AABB body = new AABB(1.34, 0, 0.2, 1.94, 1.8, 0.8);
        var view = new VisibilityEvaluator.Viewpoint(new Vec3(1.64, 1.62, 0.5), body, null, false);
        var result = VisibilityEvaluator.evaluate(snapshot(world), view, POLICY, 1, 1, 1);
        check(helper, result.safetyComplete() && revealed(result, new BlockPos(2, 1, 0)),
                "Exactly the expanded body may disclose a touched adjacent wall cell");
        check(helper, !revealed(result, new BlockPos(3, 1, 0)), "Safety must not become a through-wall radius");
        var moved = new VisibilityEvaluator.Viewpoint(view.eyes(), body, body.move(-1, 0, 0), false);
        result = VisibilityEvaluator.evaluate(snapshot(world), moved, POLICY, 1, 1, 1);
        check(helper, result.safetyComplete() && revealed(result, new BlockPos(0, 1, 0)),
                "Accepted short movement restores exactly the swept body path");
        var tooFar = new VisibilityEvaluator.Viewpoint(view.eyes(), body, body.move(-2, 0, 0), false);
        result = VisibilityEvaluator.evaluate(snapshot(world), tooFar, POLICY, 1, 1, 1);
        check(helper, !result.safetyComplete(), "Travel beyond the fixed movement segment must require runtime intervention");
        check(helper, !revealed(result, new BlockPos(-1, 1, 0)), "Long travel never silently increases the safety envelope");
        helper.succeed();
    }

    private static VisibilityEvaluator.Viewpoint viewpoint(boolean thirdPerson) {
        return new VisibilityEvaluator.Viewpoint(new Vec3(0.5, 1.62, 0.5), new AABB(0.2, 0, 0.2, 0.8, 1.8, 0.8), null, thirdPerson);
    }

    private static VisibilityEvaluator.Result evaluate(GeometrySnapshot geometry, VisibilityEvaluator.Viewpoint view, int range) {
        return VisibilityEvaluator.evaluate(geometry, view, POLICY, range, 10_000, 1_000_000);
    }

    private static Map<BlockPos, BlockState> wall(BlockState state) {
        Map<BlockPos, BlockState> world = new HashMap<>();
        for (int y = -10; y <= 10; y++) for (int z = -10; z <= 10; z++) world.put(new BlockPos(2, y, z), state);
        return world;
    }

    private static GeometrySnapshot snapshot(Map<BlockPos, BlockState> world) {
        return GeometrySnapshot.capture(source(world), new BlockPos(-10, -10, -10), new BlockPos(10, 10, 10), pos -> true);
    }

    private static BlockGetter source(Map<BlockPos, BlockState> world) {
        return new BlockGetter() {
            @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
            @Override public int getHeight() { return 64; }
            @Override public int getMinY() { return -32; }
            @Override public BlockState getBlockState(BlockPos pos) { return world.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
            @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        };
    }

    private static boolean revealed(VisibilityEvaluator.Result result, BlockPos pos) {
        return revealed(result.chunks(), pos);
    }

    private static boolean revealed(Map<Long, Map<Integer, SectionVisibility>> chunks, BlockPos pos) {
        return chunks.getOrDefault(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4), Map.of())
                .getOrDefault(pos.getY() >> 4, SectionVisibility.none()).isRevealed(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    private static void merge(Map<Long, Map<Integer, SectionVisibility>> into, Map<Long, Map<Integer, SectionVisibility>> more) {
        more.forEach((chunk, sections) -> sections.forEach((section, mask) -> into.computeIfAbsent(chunk, ignored -> new HashMap<>())
                .merge(section, mask, SectionVisibility::union)));
    }

    private static void check(GameTestHelper helper, boolean condition, String message) {
        helper.assertTrue(condition, message);
    }
}
