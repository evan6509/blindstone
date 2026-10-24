package com.evanchubbuck.blindstone;

import com.evanchubbuck.blindstone.runtime.BlindstoneRuntime;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.buffer.Unpooled;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.LoggerFactory;

/** Actual transformed send/defer/reconstruct/publish pipeline with decoded vanilla output. */
public final class PlayableGameTests {
    @GameTest
    public void deterministicProductionBenchmark(GameTestHelper helper) throws Exception {
        var results = new ArrayList<Map<String, Object>>();
        try (var settings = new Settings(helper); var terrain = new Terrain(helper.getLevel())) {
            var origin = center(helper, 30);
            for (int y=-3;y<=4;y++) for(int z=-4;z<=4;z++) for(int x=-4;x<=4;x++)
                terrain.put(origin.offset(x,y,z), (Math.abs(x)<=2 && Math.abs(z)<=2 && y>=0 && y<=2
                        ? Blocks.AIR : Blocks.STONE).defaultBlockState());
            for (boolean underground : List.of(false, true)) for (int count : List.of(1,2,4,8)) {
                var viewers = new ArrayList<Viewer>();
                try {
                    for(int i=0;i<count;i++) {
                        var v = new Viewer(helper, origin.atY(underground ? 30 : 65)); viewers.add(v);
                        v.connection.send(v.source());
                    }
                    for(int i=0;i<512;i++) benchmarkTick(helper,settings.runtime,viewers);
                    for(var v:viewers) helper.assertTrue(!v.chunks().isEmpty() && v.connection.isConnected(), "Benchmark must settle delivered viewers");
                    com.evanchubbuck.blindstone.diagnostics.WorkMetrics.enabled=true;
                    for(String event:List.of("stationary","movement","edit")) {
                        com.evanchubbuck.blindstone.diagnostics.WorkMetrics.reset();
                        int before=viewers.stream().mapToInt(v->v.history.size()).sum();
                        if(event.equals("movement")) for(var v:viewers) v.player.setPos(v.player.getX()+0.5,v.player.getY(),v.player.getZ());
                        if(event.equals("edit")) terrain.put(origin.east(), Blocks.TORCH.defaultBlockState());
                        long[] times=new long[64];
                        for(int i=0;i<times.length;i++) {
                            long start=System.nanoTime(); benchmarkTick(helper,settings.runtime,viewers); times[i]=System.nanoTime()-start;
                        }
                        Arrays.sort(times);
                        Map<String,Object> row=new LinkedHashMap<>();
                        row.put("underground",underground);row.put("viewers",count);row.put("event",event);
                        row.putAll(com.evanchubbuck.blindstone.diagnostics.WorkMetrics.snapshot());
                        row.put("packets",viewers.stream().mapToInt(v->v.history.size()).sum()-before);
                        row.put("p50_ns",times[31]);row.put("p95_ns",times[60]);row.put("p99_ns",times[63]);row.put("max_ns",times[63]);
                        results.add(row);
                        if(event.equals("edit")) terrain.put(origin.east(),Blocks.AIR.defaultBlockState());
                        for(int i=0;i<128;i++) benchmarkTick(helper,settings.runtime,viewers);
                    }
                } finally {com.evanchubbuck.blindstone.diagnostics.WorkMetrics.enabled=false;for(var v:viewers)v.close();}
            }
        }
        var output=Path.of("benchmark.json");
        Files.writeString(output,new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(results));
        LoggerFactory.getLogger("blindstone-benchmark").info("Benchmark results: {}",output.toAbsolutePath());
        for(var row:results) if(row.get("event").equals("stationary")) {
            helper.assertTrue(((Number)row.get("safetyCaptures")).longValue()==0
                    && ((Number)row.get("captures")).longValue()==0 && ((Number)row.get("rayChecks")).longValue()==0
                    && ((Number)row.get("chunkFilters")).longValue()==0 && ((Number)row.get("lightJobs")).longValue()==0,
                    "Settled viewers must perform zero repeated geometry/safety captures, rays, chunk filters or light jobs");
        }
        helper.succeed();
    }

    private static void benchmarkTick(GameTestHelper helper, BlindstoneRuntime runtime,List<Viewer> viewers) {
        runtime.beginTick();runtime.refresh(helper.getLevel());for(var v:viewers)v.pump();
    }

    @GameTest
    public void stationaryEntitySightSurvivesTicksAndRejectsLocalEdits(GameTestHelper helper) throws Exception {
        try(var settings=new Settings(helper);var terrain=new Terrain(helper.getLevel())) {
            var room=room(helper,terrain);
            try(var viewer=new Viewer(helper,room.inside())) {
                viewer.connection.send(viewer.source());
                for(int i=0;i<512;i++)benchmarkTick(helper,settings.runtime,List.of(viewer));
                var entity=net.minecraft.world.entity.EntityTypes.ARMOR_STAND.create(helper.getLevel(),net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                try {
                    entity.setPos(viewer.player.getX()+2,viewer.player.getY(),viewer.player.getZ());
                    helper.assertTrue(settings.runtime.entityMayBeSeen(viewer.player,entity),"Visible authorized entity fixture must be allowed");
                    com.evanchubbuck.blindstone.diagnostics.WorkMetrics.enabled=true;
                    com.evanchubbuck.blindstone.diagnostics.WorkMetrics.reset();
                    for(int i=0;i<16;i++) {
                        settings.runtime.beginTick();
                        helper.assertTrue(settings.runtime.entityMayBeSeen(viewer.player,entity),"Stationary entity remains visible");
                    }
                    helper.assertTrue(com.evanchubbuck.blindstone.diagnostics.WorkMetrics.captures==0
                            &&com.evanchubbuck.blindstone.diagnostics.WorkMetrics.rayChecks==0,"Stationary entity sight must reuse geometry across ticks");
                    settings.runtime.chunksChanged(helper.getLevel(),viewer.player.chunkPosition());
                    com.evanchubbuck.blindstone.diagnostics.WorkMetrics.reset();
                    settings.runtime.entityMayBeSeen(viewer.player,entity);
                    helper.assertTrue(com.evanchubbuck.blindstone.diagnostics.WorkMetrics.captures>0,"Chunk lifecycle must invalidate cached entity sight");
                    for(int y=0;y<=2;y++) terrain.put(viewer.player.blockPosition().east().above(y),Blocks.STONE.defaultBlockState());
                    helper.assertTrue(!settings.runtime.entityMayBeSeen(viewer.player,entity),"A local opaque edit must revoke cached entity sight");
                } finally {com.evanchubbuck.blindstone.diagnostics.WorkMetrics.enabled=false;entity.discard();}
            }
        }
        helper.succeed();
    }

    @GameTest
    @SuppressWarnings("unchecked")
    public void localEditsRetainDistantUnsentPlans(GameTestHelper helper) throws Exception {
        try(var settings=new Settings(helper);var viewer=new Viewer(helper,surface(helper))) {
            viewer.connection.send(viewer.source());
            Object session=session(viewer);
            var field=session.getClass().getDeclaredField("desired");field.setAccessible(true);
            var desired=(Map<Long,Map<Integer,SectionVisibility>>)field.get(session);
            var local=viewer.player.chunkPosition();var remote=new ChunkPos(local.x()+10,local.z());
            var bits=new BitSet(4096);bits.set(0);
            var mask=Map.of(1,SectionVisibility.from(bits));
            desired.put(local.pack(),mask);desired.put(remote.pack(),mask);
            settings.runtime.geometryChanged(helper.getLevel(),new BlockPos(local.x()*16,20,local.z()*16));
            helper.assertTrue(desired.containsKey(remote.pack()),"A local edit must preserve distant unsent plans");
            helper.assertTrue(!desired.containsKey(local.pack()),"A local edit must revoke nearby unsent authorization");
        }
        helper.succeed();
    }

    @GameTest
    public void surfaceYLayersConcealShaftsGlassFoliageAndSealedRooms(GameTestHelper helper) throws Exception {
        try (var settings = new Settings(helper); var terrain = new Terrain(helper.getLevel())) {
            var ground = center(helper, 55);
            exteriorYard(terrain, ground);
            terrain.put(ground.above(10),Blocks.GLASS.defaultBlockState());
            terrain.put(ground.above(12),Blocks.OAK_LEAVES.defaultBlockState());
            var chest=ground.above(2);
            terrain.put(chest,Blocks.CHEST.defaultBlockState());
            terrain.put(chest.east(),Blocks.TORCH.defaultBlockState());
            try (var viewer=new Viewer(helper,ground.atY(65))) {
                viewer.connection.send(viewer.source());
                drive(helper,settings.runtime,List.of(viewer),()->!viewer.chunks().isEmpty(),"protected-shaft");
                for(int i=0;i<128;i++)benchmarkTick(helper,settings.runtime,List.of(viewer));
                var packet=viewer.chunks().getLast();
                var expected=ChunkFilter.snapshot(helper.getLevel().getChunkAt(ground),settings.runtime.configuration().current().policy(),Map.of());
                helper.assertTrue(Arrays.equals(chunkBytes(helper.getLevel(),packet.chunkData()),chunkBytes(helper.getLevel(),expected)),
                        "Delivered Y-layer palettes/counts/air/heightmaps/tags must match uniform replacement beneath a surface viewer, including open/glass/foliage shafts");
                helper.assertTrue(block(helper.getLevel(),packet,ground.above(10)).is(Blocks.GLASS)
                        && block(helper.getLevel(),packet,ground.above(12)).is(Blocks.OAK_LEAVES),"Above-cutoff terrain must remain real");
                helper.assertTrue(!tags(packet).contains(chest) && light(helper.getLevel(),packet.lightData(),chest.east(),false)==0,
                        "Hidden block entities and light sources must remain concealed");
                viewer.connection.send(viewer.source());
                int before=viewer.chunks().size();
                drive(helper,settings.runtime,List.of(viewer),()->viewer.chunks().size()>before,"protected-shaft-resend");
                helper.assertTrue(Arrays.equals(chunkBytes(helper.getLevel(),viewer.chunks().getLast().chunkData()),chunkBytes(helper.getLevel(),expected)),"Resends must preserve concealment");
            }
        }
        helper.succeed();
    }

    private static byte[] chunkBytes(ServerLevel level,net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData data) {
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),level.registryAccess());
        try {net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData.STREAM_CODEC.encode(buffer,data);byte[] bytes=new byte[buffer.readableBytes()];buffer.getBytes(0,bytes);return bytes;}
        finally {buffer.release();}
    }

    private static void exteriorYard(Terrain terrain, BlockPos ground) {
        for (int z = -3; z <= 3; z++) for (int x = -3; x <= 3; x++) {
            terrain.put(ground.offset(x, 0, z), Blocks.GRASS_BLOCK.defaultBlockState());
            for (int y = 1; y <= 16; y++) terrain.put(ground.offset(x, y, z), Blocks.AIR.defaultBlockState());
        }
    }

    private static long geometrySamples(BlindstoneRuntime runtime) {
        return JsonParser.parseString(runtime.diagnostics().sanitizedReport(runtime.configuration().current(), runtime.configuration().generation()))
                .getAsJsonObject().getAsJsonObject("timings").getAsJsonObject("GEOMETRY").get("samples").getAsLong();
    }

    @GameTest
    public void deferredBatchKeepsOrderCallbacksSkyAndAuthorizedTorch(GameTestHelper helper) throws Exception {
        try (var settings = new Settings(helper); var terrain = new Terrain(helper.getLevel());
                var viewer = new Viewer(helper, surface(helper))) {
            BlockPos torch = viewer.player.blockPosition().offset(1, 1, 0);
            terrain.put(torch.below(), Blocks.STONE.defaultBlockState());
            terrain.put(torch, Blocks.TORCH.defaultBlockState());
            var source = viewer.source();
            var completed = new AtomicInteger();
            var successful = new AtomicBoolean();
            viewer.connection.send(ClientboundChunkBatchStartPacket.INSTANCE);
            viewer.connection.send(source, future -> { completed.incrementAndGet(); successful.set(future.isSuccess()); });
            viewer.connection.send(new ClientboundChunkBatchFinishedPacket(1));
            viewer.pump();
            helper.assertTrue(viewer.history.isEmpty() && completed.get() == 0,
                    "A deferred vanilla chunk batch and callback must stay pending until coherent publication");
            drive(helper, settings.runtime, List.of(viewer), () -> completed.get() == 1 && !viewer.chunks().isEmpty(), "surface-batch");
            helper.assertTrue(successful.get(), "Publication must complete the original chunk callback successfully exactly once");
            int start = indexOf(viewer.history, ClientboundChunkBatchStartPacket.class);
            int chunk = indexOf(viewer.history, ClientboundLevelChunkWithLightPacket.class);
            int finish = indexOf(viewer.history, ClientboundChunkBatchFinishedPacket.class);
            helper.assertTrue(start >= 0 && start < chunk && chunk < finish
                            && ((ClientboundChunkBatchFinishedPacket) viewer.history.get(finish)).batchSize() == 1,
                    "The start, delivered chunk and accurate finished count must retain vanilla batch order");
            var packet = viewer.chunks().getFirst();
            helper.assertTrue(block(helper.getLevel(), packet, torch).is(Blocks.TORCH), "Authorized surface torch must remain real");
            helper.assertTrue(light(helper.getLevel(), packet.lightData(), torch, false) == Blocks.TORCH.defaultBlockState().getLightEmission(),
                    "Actual pipeline output must carry reconstructed torch light, not constant darkness");
            helper.assertTrue(light(helper.getLevel(), packet.lightData(), viewer.player.blockPosition().above(5), true) > 0,
                    "Actual pipeline output must retain ordinary daylight above protection");
            assertHeightmap(helper, packet, torch);
        }
        helper.succeed();
    }

    @GameTest
    public void bodySafetyThenSightPublishCoherentChunkBlockEntitiesAndHeightmaps(GameTestHelper helper) throws Exception {
        try (var settings = new Settings(helper); var terrain = new Terrain(helper.getLevel())) {
            var room = room(helper, terrain);
            try (var viewer = new Viewer(helper, room.inside())) {
                viewer.connection.send(viewer.source());
                drive(helper, settings.runtime, List.of(viewer), () -> !viewer.chunks().isEmpty(), "initial-body-safety");
                var first = viewer.chunks().getFirst();
                helper.assertTrue(block(helper.getLevel(), first, viewer.player.blockPosition()).isAir(),
                        "The very first client chunk must contain accepted body air instead of a persistent fake wall");
                helper.assertTrue(block(helper.getLevel(), first, room.chest()).is(Blocks.STONE) && !tags(first).contains(room.chest()),
                        "Initial body safety must not authorize a neighboring chest before sight delivery");
                assertHeightmap(helper, first, room.chest());
                drive(helper, settings.runtime, List.of(viewer), () -> viewer.chunks().stream().anyMatch(packet ->
                        block(helper.getLevel(), packet, room.chest()).is(Blocks.CHEST) && tags(packet).contains(room.chest())
                                && light(helper.getLevel(), packet.lightData(), room.torch(), false) > 0), "visible-room-resend");
                var last = viewer.chunks().getLast();
                helper.assertTrue(block(helper.getLevel(), last, room.chest()).is(Blocks.CHEST) && tags(last).contains(room.chest()),
                        "Sight must publish matching real chest state and block-entity data in a coherent full resend");
                helper.assertTrue(block(helper.getLevel(), last, room.torch()).is(Blocks.TORCH), "Visible torch geometry must match its reconstructed light");
                assertHeightmap(helper, last, room.chest());
                helper.assertTrue(helper.getLevel().getBlockState(room.chest()).is(Blocks.CHEST)
                                && helper.getLevel().getBlockEntity(room.chest()) != null,
                        "Outgoing reconstruction must leave the real chest and block entity intact");
            }
        }
        helper.succeed();
    }

    @GameTest
    public void independentViewersReceiveDifferentSealedRoomGeometryAndLight(GameTestHelper helper) throws Exception {
        try (var settings = new Settings(helper); var terrain = new Terrain(helper.getLevel())) {
            var room = room(helper, terrain);
            try (var outside = new Viewer(helper, room.outside()); var inside = new Viewer(helper, room.inside())) {
                var source = inside.source();
                byte[] original = encode(helper.getLevel(), source);
                outside.connection.send(source);
                inside.connection.send(source);
                drive(helper, settings.runtime, List.of(outside, inside), () -> !outside.chunks().isEmpty()
                        && inside.chunks().stream().anyMatch(packet -> block(helper.getLevel(), packet, room.chest()).is(Blocks.CHEST)
                            && tags(packet).contains(room.chest())), "independent-sealed-room");
                for (var packet : outside.chunks()) {
                    helper.assertTrue(block(helper.getLevel(), packet, room.chest()).is(Blocks.STONE)
                                    && !tags(packet).contains(room.chest())
                                    && light(helper.getLevel(), packet.lightData(), room.torch(), false) == 0,
                            "A second viewer must retain concealed room geometry, absent chest tags and no hidden torch light");
                }
                helper.assertTrue(Arrays.equals(original, encode(helper.getLevel(), source)),
                        "The shared original chunk packet must remain byte-identical after both viewer pipelines");
            }
        }
        helper.succeed();
    }

    @GameTest
    public void geometryChangedBeforePreparationCannotPublishCapturedOldBlockOrLight(GameTestHelper helper) throws Exception {
        try (var settings = new Settings(helper); var terrain = new Terrain(helper.getLevel());
                var viewer = new Viewer(helper, surface(helper))) {
            BlockPos torch = viewer.player.blockPosition().offset(1, 1, 0);
            terrain.put(torch.below(), Blocks.STONE.defaultBlockState());
            terrain.put(torch, Blocks.TORCH.defaultBlockState());
            var old = viewer.source();
            var completed = new AtomicInteger();
            viewer.connection.send(old, future -> completed.incrementAndGet());
            viewer.pump();
            helper.assertTrue(completed.get() == 0 && viewer.chunks().isEmpty(), "The old snapshot must still be deferred");
            terrain.put(torch, Blocks.SOUL_TORCH.defaultBlockState());
            drive(helper, settings.runtime, List.of(viewer), () -> completed.get() == 1 && !viewer.chunks().isEmpty(), "geometry-before-publication");
            helper.assertTrue(block(helper.getLevel(), old, torch).is(Blocks.TORCH), "The input fixture must actually contain stale torch data");
            for (var packet : viewer.chunks()) {
                helper.assertTrue(block(helper.getLevel(), packet, torch).is(Blocks.SOUL_TORCH)
                                && light(helper.getLevel(), packet.lightData(), torch, false) == Blocks.SOUL_TORCH.defaultBlockState().getLightEmission(),
                        "A pending old packet must be rebuilt from the current authorized block and matching light");
            }
        }
        helper.succeed();
    }

    @GameTest
    public void reloadInvalidatesPendingGenerationWithoutLosingCompletion(GameTestHelper helper) throws Exception {
        try (var settings = new Settings(helper); var terrain = new Terrain(helper.getLevel());
                var viewer = new Viewer(helper, surface(helper))) {
            BlockPos torch = viewer.player.blockPosition().offset(1, 1, 0);
            terrain.put(torch.below(), Blocks.STONE.defaultBlockState());
            terrain.put(torch, Blocks.TORCH.defaultBlockState());
            var completions = new AtomicInteger();
            var success = new AtomicBoolean();
            viewer.connection.send(viewer.source(), future -> { completions.incrementAndGet(); success.set(future.isSuccess()); });
            viewer.pump();
            helper.assertTrue(completions.get() == 0 && viewer.chunks().isEmpty(), "Reload fixture must begin with a real queued delivery");
            settings.runtime.reload();
            terrain.put(torch, Blocks.SOUL_TORCH.defaultBlockState());
            viewer.pump();
            var replacementCompletions = new AtomicInteger();
            boolean canceled = completions.get() == 1 && !success.get();
            if (canceled) viewer.connection.send(viewer.source(), future -> {
                helper.assertTrue(future.isSuccess(), "Fresh replacement delivery must succeed after a canceled generation");
                replacementCompletions.incrementAndGet();
            });
            drive(helper, settings.runtime, List.of(viewer), () -> completions.get() == 1 && !viewer.chunks().isEmpty()
                    && (!canceled || replacementCompletions.get() == 1), "reload-pending-generation");
            helper.assertTrue(completions.get() == 1, "A stale queued frame's callback must complete exactly once, including explicit cancellation");
            for (var packet : viewer.chunks()) helper.assertTrue(block(helper.getLevel(), packet, torch).is(Blocks.SOUL_TORCH)
                            && light(helper.getLevel(), packet.lightData(), torch, false) == Blocks.SOUL_TORCH.defaultBlockState().getLightEmission(),
                    "Reload must never publish the previous generation's real block/light snapshot");
        }
        helper.succeed();
    }

    @GameTest
    public void closedDoorRevokesAuthorizedButUnsentRoomBeforeInitialPublication(GameTestHelper helper) throws Exception {
        try (var settings = new Settings(helper); var terrain = new Terrain(helper.getLevel())) {
            var room = room(helper, terrain);
            var barrier = room.inside().offset(0, 0, -3);
            terrain.put(barrier, Blocks.AIR.defaultBlockState());
            terrain.put(barrier.above(), Blocks.AIR.defaultBlockState());
            try (var viewer = new Viewer(helper, room.outside())) {
                // Establish a session without delivering terrain. Seed exactly the old sight
                // authorization to isolate the compute-versus-publication invalidation boundary.
                viewer.connection.send(new ClientboundCustomPayloadPacket(new BrandPayload("blindstone-fixture")));
                viewer.pump();
                Object session = session(viewer);
                var desiredField = session.getClass().getDeclaredField("desired");
                desiredField.setAccessible(true);
                @SuppressWarnings("unchecked")
                var desired = (Map<Long, Map<Integer, SectionVisibility>>) desiredField.get(session);
                var bits = new BitSet();
                bits.set(SectionVisibility.index(room.chest().getX() & 15, room.chest().getY() & 15, room.chest().getZ() & 15));
                long key = ChunkPos.pack(room.chest());
                desired.put(key, Map.of(room.chest().getY() >> 4, SectionVisibility.from(bits)));
                var source = viewer.source();
                viewer.connection.send(source);
                viewer.pump();
                helper.assertTrue(viewer.chunks().isEmpty() && pendingContains(session, key, room.chest()),
                        "Fixture must really queue the previously authorized chest before delivering any terrain");
                terrain.put(barrier, Blocks.STONE.defaultBlockState());
                terrain.put(barrier.above(), Blocks.STONE.defaultBlockState());
                drive(helper, settings.runtime, List.of(viewer), () -> !viewer.chunks().isEmpty(), "closed-door-revokes-unsent-room");
                for (var packet : viewer.chunks()) helper.assertTrue(block(helper.getLevel(), packet, room.chest()).is(Blocks.STONE)
                                && !tags(packet).contains(room.chest()),
                        "Closing the barrier must revoke unsent room authorization before old chunk/light work can publish it");
                helper.assertTrue(block(helper.getLevel(), source, room.chest()).is(Blocks.CHEST),
                        "Fixture's original packet must still hold the real chest that the protected boundary concealed");
            }
        }
        helper.succeed();
    }

    private static Object session(Viewer viewer) throws Exception {
        var field = BlindstoneRuntime.class.getDeclaredField("viewers");
        field.setAccessible(true);
        Object value = ((Map<?, ?>) field.get(viewer.runtime)).get(viewer.player.getUUID());
        if (value == null) throw new IllegalStateException("Fixture did not create its viewer session");
        return value;
    }

    private static boolean pendingContains(Object session, long chunk, BlockPos position) throws Exception {
        var field = session.getClass().getDeclaredField("pending");
        field.setAccessible(true);
        Object pending = ((Map<?, ?>) field.get(session)).get(chunk);
        if (pending == null) return false;
        var masksField = pending.getClass().getDeclaredField("masks");
        masksField.setAccessible(true);
        var masks = (Map<?, ?>) masksField.get(pending);
        Object mask = masks.get(position.getY() >> 4);
        return mask instanceof SectionVisibility visible
                && visible.isRevealed(position.getX() & 15, position.getY() & 15, position.getZ() & 15);
    }

    private static void drive(GameTestHelper helper, BlindstoneRuntime runtime, List<Viewer> viewers, BooleanSupplier complete, String label) {
        long started = System.nanoTime(), maximum = 0;
        int steps = 0;
        while (!complete.getAsBoolean() && steps < 400) {
            long step = System.nanoTime();
            runtime.beginTick();
            runtime.refresh(helper.getLevel());
            for (var viewer : viewers) {
                viewer.pump();
                helper.assertTrue(viewer.connection.isConnected(), "Deferred integration fixture must remain connected: " + runtime.inspect(viewer.player));
            }
            maximum = Math.max(maximum, System.nanoTime() - step);
            steps++;
        }
        helper.assertTrue(complete.getAsBoolean(), "Deferred delivery did not complete within 400 manually advanced runtime steps: " + label);
        LoggerFactory.getLogger("blindstone-playable-tests").info(
                "Fixture {}: manual runtime steps={}, measured wall ms={}, largest step ms={}. These are synthetic stepping measurements, not real server/client reveal latency.",
                label, steps, (System.nanoTime() - started) / 1000000.0, maximum / 1000000.0);
    }

    private static BlockPos center(GameTestHelper helper, int y) {
        var absolute = helper.absolutePos(BlockPos.ZERO);
        return new BlockPos((absolute.getX() >> 4) * 16 + 8, y, (absolute.getZ() >> 4) * 16 + 8);
    }
    private static BlockPos surface(GameTestHelper helper) { return center(helper, 65); }
    private record Room(BlockPos inside, BlockPos outside, BlockPos chest, BlockPos torch) { }
    private static Room room(GameTestHelper helper, Terrain terrain) {
        var center = center(helper, 20);
        for (int y = -1; y <= 3; y++) for (int z = -3; z <= 3; z++) for (int x = -3; x <= 3; x++) {
            boolean wall = y == -1 || y == 3 || x == -3 || x == 3 || z == -3 || z == 3;
            terrain.put(center.offset(x, y, z), (wall ? Blocks.STONE : Blocks.AIR).defaultBlockState());
        }
        var chest = center.offset(0, 1, -1);
        var torch = center.offset(1, 1, 0);
        terrain.put(chest, Blocks.CHEST.defaultBlockState());
        terrain.put(torch.below(), Blocks.STONE.defaultBlockState());
        terrain.put(torch, Blocks.TORCH.defaultBlockState());
        return new Room(center, center.offset(0, 0, -4), chest, torch);
    }

    private static List<BlockPos> tags(ClientboundLevelChunkWithLightPacket packet) {
        List<BlockPos> positions = new ArrayList<>();
        packet.chunkData().forEachBlockEntityTag(packet.x(), packet.z(), (pos, type, data) -> positions.add(pos.immutable()));
        return positions;
    }
    private static BlockState block(ServerLevel level, ClientboundLevelChunkWithLightPacket packet, BlockPos pos) {
        var data = packet.chunkData().getReadBuffer();
        try {
            for (int i = 0; i < level.getSectionsCount(); i++) {
                var section = new LevelChunkSection(level.palettedContainerFactory());
                section.read(data);
                if (i == level.getSectionIndex(pos.getY())) return section.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
            }
            throw new IllegalArgumentException("Fixture position is outside packet section bounds");
        } finally { data.release(); }
    }
    private static int light(ServerLevel level, ClientboundLightUpdatePacketData packet, BlockPos pos, boolean sky) {
        int index = (pos.getY() >> 4) - (level.getMinSectionY() - 1);
        var mask = sky ? packet.skyYMask() : packet.blockYMask();
        if (!mask.get(index)) return 0;
        int entry = mask.get(0, index).cardinality();
        var updates = sky ? packet.skyUpdates() : packet.blockUpdates();
        return new DataLayer(updates.get(entry)).get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }
    private static void assertHeightmap(GameTestHelper helper, ClientboundLevelChunkWithLightPacket packet, BlockPos column) {
        var raw = packet.chunkData().getHeightmaps().get(Heightmap.Types.WORLD_SURFACE);
        helper.assertTrue(raw != null, "Coherent chunk must include the vanilla client world-surface heightmap");
        int top = helper.getLevel().getMinY();
        var data = packet.chunkData().getReadBuffer();
        try {
            for (int i = 0; i < helper.getLevel().getSectionsCount(); i++) {
                var section = new LevelChunkSection(helper.getLevel().palettedContainerFactory()); section.read(data);
                for (int y = 0; y < 16; y++) if (!section.getBlockState(column.getX() & 15, y, column.getZ() & 15).isAir())
                    top = helper.getLevel().getMinY() + i * 16 + y + 1;
            }
        } finally { data.release(); }
        var source = helper.getLevel().getChunkAt(column);
        var map = new Heightmap(source, Heightmap.Types.WORLD_SURFACE); map.setRawData(source, Heightmap.Types.WORLD_SURFACE, raw);
        helper.assertTrue(map.getFirstAvailable(column.getX() & 15, column.getZ() & 15) == top,
                "Published heightmap must describe the actual filtered section representation");
    }
    private static int indexOf(List<Object> packets, Class<?> type) {
        for (int i = 0; i < packets.size(); i++) if (type.isInstance(packets.get(i))) return i;
        return -1;
    }
    private static byte[] encode(ServerLevel level, ClientboundLevelChunkWithLightPacket packet) {
        var data = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        try {
            ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(data, packet);
            byte[] bytes = new byte[data.readableBytes()]; data.readBytes(bytes); return bytes;
        } finally { data.release(); }
    }

    private static final class Settings implements AutoCloseable {
        final BlindstoneRuntime runtime;
        final Path file;
        final String original;
        Settings(GameTestHelper helper) throws Exception {
            runtime = BlindstoneRuntime.get(helper.getLevel().getServer());
            if (runtime == null) throw new IllegalStateException("Runtime lifecycle was not initialized");
            file = FabricLoader.getInstance().getConfigDir().resolve("blindstone.json"); original = Files.readString(file);
            var json = JsonParser.parseString(original).getAsJsonObject();
            json.addProperty("revealRangeBlocks", 4);
            json.getAsJsonObject("budgets").addProperty("maxWorkNanosPerTick", 50000000);
            Files.writeString(file, json.toString());
            try { runtime.reload(); }
            catch (Exception failure) { Files.writeString(file, original); throw failure; }
        }
        @Override public void close() throws Exception { Files.writeString(file, original); runtime.reload(); }
    }

    private static final class Terrain implements AutoCloseable {
        final ServerLevel level;
        final Map<BlockPos, BlockState> original = new LinkedHashMap<>();
        Terrain(ServerLevel level) { this.level = level; }
        void put(BlockPos pos, BlockState state) {
            var key = pos.immutable();
            if (!original.containsKey(key)) {
                if (level.getBlockEntity(key) != null) throw new IllegalStateException("Fixture refuses to overwrite an existing block entity");
                original.put(key, level.getBlockState(key));
            }
            level.setBlock(key, state, 2);
        }
        @Override public void close() {
            var positions = new ArrayList<>(original.keySet()); Collections.reverse(positions);
            for (var pos : positions) level.setBlock(pos, original.get(pos), 2);
        }
    }

    private static final class Viewer implements AutoCloseable {
        final ServerPlayer player;
        final Connection connection;
        final EmbeddedChannel channel;
        final BlindstoneRuntime runtime;
        final List<Object> history = new ArrayList<>();
        Viewer(GameTestHelper helper, BlockPos pos) throws Exception {
            var server = helper.getLevel().getServer(); runtime = BlindstoneRuntime.get(server);
            helper.getLevel().getChunkAt(pos);
            var profile = new GameProfile(UUID.randomUUID(), "blindstone_playable");
            player = new ServerPlayer(server, helper.getLevel(), profile, ClientInformation.createDefault());
            player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            connection = new Connection(PacketFlow.SERVERBOUND); channel = new EmbeddedChannel(connection);
            var listener = new ServerGamePacketListenerImpl(server, connection, player, CommonListenerCookie.createInitial(profile, false));
            var field = Connection.class.getDeclaredField("packetListener"); field.setAccessible(true); field.set(connection, listener);
        }
        ClientboundLevelChunkWithLightPacket source() {
            var chunk = player.level().getChunkAt(player.blockPosition());
            return new ClientboundLevelChunkWithLightPacket(chunk, player.level().getLightEngine(), null, null);
        }
        void pump() { channel.runPendingTasks(); Object packet; while ((packet = channel.readOutbound()) != null) history.add(packet); }
        List<ClientboundLevelChunkWithLightPacket> chunks() {
            return history.stream().filter(ClientboundLevelChunkWithLightPacket.class::isInstance).map(ClientboundLevelChunkWithLightPacket.class::cast).toList();
        }
        @Override public void close() { runtime.remove(player); player.discard(); channel.runPendingTasks(); channel.finishAndReleaseAll(); }
    }
}
