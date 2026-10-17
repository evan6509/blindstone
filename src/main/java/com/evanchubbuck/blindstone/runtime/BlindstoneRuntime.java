package com.evanchubbuck.blindstone.runtime;

import com.evanchubbuck.blindstone.ChunkFilter;
import com.evanchubbuck.blindstone.ConcealmentPolicy;
import com.evanchubbuck.blindstone.SectionVisibility;
import com.evanchubbuck.blindstone.ViewerState;
import com.evanchubbuck.blindstone.config.BlindstoneConfig;
import com.evanchubbuck.blindstone.config.ConfigManager;
import com.evanchubbuck.blindstone.diagnostics.BlindstoneDiagnostics;
import com.evanchubbuck.blindstone.network.LightScheduler;
import com.evanchubbuck.blindstone.network.PacketFilter;
import com.evanchubbuck.blindstone.visibility.VisibilityEvaluator;
import com.evanchubbuck.blindstone.visibility.SurfaceVisibility;
import io.netty.channel.ChannelFutureListener;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Per-viewer filtered delivery. World capture and publication stay on the owning server thread. */
public final class BlindstoneRuntime {
    public static final int MAX_KNOWN_ENTITIES = 16384;
    private static final Map<MinecraftServer, BlindstoneRuntime> SERVERS = new ConcurrentHashMap<>();
    private final MinecraftServer server;
    private final ConfigManager configuration;
    private final Map<UUID, Session> viewers = new LinkedHashMap<>();
    private final BlindstoneDiagnostics diagnostics;
    private final SurfaceVisibility surfaces;
    private final LightScheduler.MemoryBudget lightMemory = new LightScheduler.MemoryBudget(128L * 1024 * 1024);
    private boolean failed;
    private boolean enabled = true;
    private boolean publishing;
    private long tick;
    private long workSpentNanos;
    private int rayWorkRemaining;
    private int nextViewer;

    public BlindstoneRuntime(MinecraftServer server, Path file) throws IOException {
        this.server = server;
        configuration = new ConfigManager(file);
        configuration.loadInitial(server.overworld());
        surfaces = new SurfaceVisibility(server.overworld());
        var loader = FabricLoader.getInstance();
        diagnostics = new BlindstoneDiagnostics(new BlindstoneDiagnostics.Versions(version(loader,"blindstone"),
                version(loader,"minecraft"),version(loader,"fabricloader"),version(loader,"fabric-api")));
        diagnostics.activation(BlindstoneDiagnostics.Activation.ENFORCING);
    }
    private static String version(FabricLoader loader,String id) {
        return loader.getModContainer(id).orElseThrow().getMetadata().getVersion().getFriendlyString();
    }
    public static void start(MinecraftServer server) {
        try { SERVERS.put(server,new BlindstoneRuntime(server,FabricLoader.getInstance().getConfigDir().resolve("blindstone.json"))); }
        catch (IOException|RuntimeException error) { throw new IllegalStateException("Blindstone cannot start safely; configuration or hooks failed",error); }
    }
    public static BlindstoneRuntime get(MinecraftServer server) { return SERVERS.get(server); }
    public static void stop(MinecraftServer server) {
        var runtime=SERVERS.remove(server);
        if(runtime!=null) runtime.viewers.values().forEach(Session::clearLighting);
    }
    public BlindstoneDiagnostics diagnostics() { return diagnostics; }
    public ConfigManager configuration() { return configuration; }
    public boolean admitsPlayers() { return !enabled||!failed; }
    public boolean enabled() { return enabled; }
    private void checkThread() { if(!server.isSameThread()) throw new IllegalStateException("Blindstone requires the owning server thread"); }
    private boolean protectedWorld(ServerPlayer player) { return enabled&&player.level().dimension().equals(Level.OVERWORLD); }
    private Connection connection(ServerPlayer p) {
        return ((com.evanchubbuck.blindstone.mixin.ServerCommonListenerAccess)p.connection).blindstone$connection();
    }
    private Session session(ServerPlayer player) {
        checkThread();
        var old=viewers.get(player.getUUID());
        if(old!=null&&old.player==player&&old.level==player.level()) return old;
        Set<Long> retained=old!=null&&old.level==player.level()?old.state.trackedChunks():Set.of();
        Set<Long> client=old!=null&&old.level==player.level()?Set.copyOf(old.clientChunks):Set.of();
        Set<Integer> known=old!=null&&old.level==player.level()?Set.copyOf(old.announced):Set.of();
        if(old!=null) { old.clearLighting(); viewers.remove(player.getUUID()); }
        var config=configuration.current();
        if(viewers.size()>=config.settings().budgets().maxViewers()) throw new IllegalStateException("Blindstone viewer budget exhausted");
        var created=new Session(player,config);
        created.lights=new LightScheduler(16,128,lightMemory);
        created.lastSafeDelivery=tick;
        retained.forEach(created.state::track);
        created.clientChunks.addAll(client);
        created.announced.addAll(known);
        viewers.put(player.getUUID(),created);
        retained.forEach(key->queueChunk(created,key));
        return created;
    }

    /** Returns true when the runtime owns deferred completion/publication of this frame. */
    public boolean intercept(ServerPlayer player,Packet<?> packet,ChannelFutureListener callback,boolean flush) {
        checkThread();
        if(!protectedWorld(player)) { remove(player); return false; }
        if(failed) throw new IllegalStateException("Blindstone is unavailable");
        var s=session(player);
        if(s.trustedPacket==packet) return false;
        if(packet instanceof ClientboundChunkBatchStartPacket) {
            if(s.openBatch!=null) throw new IllegalStateException("Nested vanilla chunk batch");
            s.openBatch=new Batch(true,tick);
            s.openBatch.frames.add(new Frame(packet,callback,flush));
            return true;
        }
        if(packet instanceof ClientboundChunkBatchFinishedPacket finish) {
            if(s.openBatch==null) throw new IllegalStateException("Chunk batch finish without start");
            if(finish.batchSize()!=s.openBatch.chunkCount) throw new IllegalStateException("Chunk batch count mismatch");
            s.openBatch.frames.add(new Frame(packet,callback,flush));
            s.batches.add(s.openBatch); s.openBatch=null;
            return true;
        }
        if(packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            long key=ChunkPos.pack(chunk.x(),chunk.z());
            track(s,key);
            // Include the accepted destination body before its very first chunk is frozen for light.
            collectSafety(s,false);
            queueChunk(s,key);
            if(s.openBatch!=null) { s.openBatch.frames.add(new Frame(packet,callback,flush)); s.openBatch.chunkCount++; }
            else { var batch=new Batch(false,tick); batch.frames.add(new Frame(packet,callback,flush)); batch.chunkCount=1; s.batches.add(batch); }
            checkQueues(s);
            return true;
        }
        if(packet instanceof ClientboundLightUpdatePacket update) {
            // Engine light below the safe public boundary is never copied. Authorized geometry changes
            // already invalidate filtered light; only safe upper-boundary engine changes add work here.
            int firstSafe=(configuration.current().policy().maxY()+16-s.level.getMinY())/16+1;
            var data=update.lightData();
            if(data.skyYMask().nextSetBit(firstSafe)>=0||data.blockYMask().nextSetBit(firstSafe)>=0
                    ||data.emptySkyYMask().nextSetBit(firstSafe)>=0||data.emptyBlockYMask().nextSetBit(firstSafe)>=0)
                invalidateLight(s,new ChunkPos(update.x(),update.z()));
            if(flush) connection(player).flushChannel();
            complete(s,callback,null);
            return true;
        }
        if(packet instanceof ClientboundBundlePacket bundle && containsChunk(bundle)) {
            var batch=new Batch(false,tick);
            batch.frames.add(new Frame(packet,callback,flush));
            collectChunkKeys(packet).forEach(key->track(s,key));
            collectSafety(s,false);
            collectChunkKeys(packet).forEach(key->queueChunk(s,key));
            s.batches.add(batch);checkQueues(s);return true;
        }
        return false;
    }
    private static boolean containsChunk(Packet<?> packet) { return !collectChunkKeys(packet).isEmpty(); }
    private static Set<Long> collectChunkKeys(Packet<?> packet) {
        Set<Long> keys=new HashSet<>();
        if(packet instanceof ClientboundLevelChunkWithLightPacket chunk) keys.add(ChunkPos.pack(chunk.x(),chunk.z()));
        else if(packet instanceof ClientboundBundlePacket bundle) for(var p:bundle.subPackets()) keys.addAll(collectChunkKeys(p));
        return keys;
    }
    private void checkQueues(Session s) {
        int count=s.batches.stream().mapToInt(b->b.frames.size()).sum()+(s.openBatch==null?0:s.openBatch.frames.size());
        if(count>configuration.current().settings().budgets().maxQueuedChunksPerViewer()+32)
            throw new IllegalStateException("Blindstone deferred delivery queue exhausted");
    }
    public boolean chunkBacklogged(ServerPlayer player) {
        var s=viewers.get(player.getUUID());
        return protectedWorld(player)&&s!=null&&(!s.batches.isEmpty()||s.openBatch!=null);
    }

    public Packet<?> filter(ServerPlayer player,Packet<?> packet) {
        checkThread();
        if(!protectedWorld(player)) { remove(player);return packet; }
        if(failed) throw new IllegalStateException("Blindstone is unavailable");
        var s=session(player);
        if(s.trustedPacket==packet) return packet;
        if(packet instanceof ClientboundRespawnPacket||packet instanceof ClientboundPlayerPositionPacket||packet instanceof ClientboundStartConfigurationPacket) reset(player);
        if(packet instanceof ClientboundForgetLevelChunkPacket forget) forget(s,forget.pos().pack());
        if(packet instanceof ClientboundBlockEntityDataPacket entity) {
            var pending=s.pending.get(ChunkPos.pack(entity.getPos()));
            if(pending!=null) pending.prepared=null;
        }
        long start=System.nanoTime();
        try {
            var safe=PacketFilter.filter(packet,new Context(s,null));
            var path=path(packet);
            diagnostics.hookLoaded(BlindstoneDiagnostics.PacketPath.DIRECT_CONNECTION);
            diagnostics.observed(BlindstoneDiagnostics.PacketPath.DIRECT_CONNECTION,safe!=packet);
            diagnostics.enforcement(BlindstoneDiagnostics.PacketPath.DIRECT_CONNECTION,true);
            if(path!=null) {diagnostics.hookLoaded(path);diagnostics.observed(path,safe!=packet);diagnostics.enforcement(path,true);}
            if(safe==null) diagnostics.increment(BlindstoneDiagnostics.Counter.DROPPED_PACKETS);
            else if(safe!=packet) diagnostics.increment(BlindstoneDiagnostics.Counter.FILTERED_PACKETS);
            return safe;
        } finally { diagnostics.timing(BlindstoneDiagnostics.Timing.PACKET_FILTER,System.nanoTime()-start); }
    }
    private void track(Session s,long key) {
        if(s.state.phase(key)!=ViewerState.Phase.UNTRACKED) return;
        var config=configuration.current();
        long global=viewers.values().stream().mapToLong(v->v.state.trackedCount()).sum();
        if(global>=config.settings().budgets().maxGlobalTrackedChunks()||(global+1)*config.maskBytesPerTrackedChunk()>config.settings().budgets().maxMaskBytes()||!s.state.track(key))
            throw new IllegalStateException("Blindstone tracking memory budget exhausted");
        if(s.job!=null&&s.job.intersectsChanged(ChunkPos.unpack(key)))s.job=null;
        s.dirty=true;
    }
    private void queueChunk(Session s,long key) {
        if(s.state.phase(key)==ViewerState.Phase.UNTRACKED) return;
        if(s.pending.containsKey(key)) return;
        if(s.pending.size()>=configuration.current().settings().budgets().maxQueuedChunksPerViewer()) return;
        Map<Integer,SectionVisibility> target=union(s.state.deliveredSections(key),s.desired.getOrDefault(key,Map.of()));
        long queued=viewers.values().stream().mapToLong(v->v.pending.size()).sum();
        if(queued>=configuration.current().settings().budgets().maxGlobalQueuedChunks()) return;
        s.pending.put(key,new Pending(key,target,++s.generation,tick));
    }
    private void forget(Session s,long key) {
        s.state.untrack(key);s.clientChunks.remove(key);s.pending.remove(key);s.desired.remove(key);
        if(s.lights!=null) s.lights.forget(ChunkPos.unpack(key));
        s.job=null;s.dirty=true;
    }

    public void beginTick() {
        checkThread();
        if(tick!=0) diagnostics.timing(BlindstoneDiagnostics.Timing.TICK_WORK,workSpentNanos);
        tick++;workSpentNanos=0;rayWorkRemaining=configuration.current().settings().budgets().maxGeometryChecksPerTickGlobal();
        for(var s:List.copyOf(viewers.values())) {
            if(s.player.isRemoved()||!s.player.connection.isAcceptingMessages()) remove(s.player);
            else {s.rayWorkUsed=0;s.entityVisibility.clear();}
        }
        updateCounts();
    }
    public void refresh(ServerLevel level) {
        checkThread();
        if(!enabled||failed||!level.dimension().equals(Level.OVERWORLD)||publishing) return;
        var sessions=new ArrayList<>(viewers.values());
        if(sessions.isEmpty()) return;
        int start=Math.floorMod(nextViewer++,sessions.size());
        for(int i=0;i<sessions.size();i++) {
            var s=sessions.get((start+i)%sessions.size());
            if(s.level!=level) continue;
            try {
                long safetyStart=System.nanoTime();
                collectSafety(s,true);
                workSpentNanos+=System.nanoTime()-safetyStart;
                if(hasWorkBudget()) refreshSight(s);
                if(hasWorkBudget()) prepareLighting(s);
                publishReady(s);
                if(!s.clientChunks.contains(s.player.chunkPosition().pack())) {
                    if(tick-s.lastSafeDelivery>200) throw new IllegalStateException("Initial protected chunks could not be prepared within ten seconds");
                } else if(!bodySafetyDelivered(s)) {
                    if(tick-s.lastSafeDelivery>configuration.current().settings().budgets().movementDeliveryDeadlineTicks())
                        throw new IllegalStateException("Movement-safe view could not be delivered before deadline");
                } else s.lastSafeDelivery=tick;
            } catch(RuntimeException error) {
                diagnostics.failed(BlindstoneDiagnostics.Subsystem.PACKET_GATE,BlindstoneDiagnostics.Failure.INTERNAL_ERROR);
                org.slf4j.LoggerFactory.getLogger("blindstone").error("Filtered delivery failed in {}",error.getClass().getSimpleName(),error);
                disconnect(s.player,"Blindstone could not safely prepare your world view: "+error.getMessage());
            }
        }
        updateCounts();
    }
    private VisibilityEvaluator.SafetyInput safetyInput(Session s) {
        var paths=new ArrayList<VisibilityEvaluator.BodyPath>();
        paths.add(new VisibilityEvaluator.BodyPath(s.player.getBoundingBox(),s.previousBody));
        Entity vehicle=s.player.getRootVehicle();
        if(vehicle!=s.player) paths.add(new VisibilityEvaluator.BodyPath(vehicle.getBoundingBox(),
                vehicle.getId()==s.previousVehicleId?s.previousVehicle:null));
        return new VisibilityEvaluator.SafetyInput(paths,VisibilityEvaluator.DEFAULT_ACCEPTED_MOVEMENT);
    }
    private void collectSafety(Session s,boolean publish) {
        var input=safetyInput(s);
        int bound=VisibilityEvaluator.safetySnapshotBlockBound(input);
        if(bound>8192) throw new IllegalStateException("Accepted movement safety exceeds the explicit 16-block/body budget");
        var snapshot=VisibilityEvaluator.captureSafety(s.level,input,8192);
        var result=VisibilityEvaluator.evaluateSafety(snapshot,input,configuration.current().policy());
        if(!result.safetyComplete()) throw new IllegalStateException("Movement destination chunks are not available for safe delivery");
        result.chunks().forEach((key,masks)-> {
            if(s.state.phase(key)==ViewerState.Phase.UNTRACKED)return;
            var underground=undergroundMasks(s,key,masks);
            if(underground.isEmpty())return;
            s.desired.merge(key,underground,BlindstoneRuntime::union);
            if(publish&&s.clientChunks.contains(key)) publishSafety(s,key,underground);
        });
        if(publish) {
            s.previousBody=s.player.getBoundingBox();
            var vehicle=s.player.getRootVehicle();
            s.previousVehicle=vehicle==s.player?null:vehicle.getBoundingBox();
            s.previousVehicleId=vehicle==s.player?-1:vehicle.getId();
        }
    }
    private void publishSafety(Session s,long key,Map<Integer,SectionVisibility> masks) {
        if(!addsKnowledge(s.state,key,masks)) return;
        var chunk=s.level.getChunkSource().getChunkNow(ChunkPos.getX(key),ChunkPos.getZ(key));
        if(chunk==null) throw new IllegalStateException("Movement-safe chunk vanished");
        var plan=s.state.queue(key,masks);
        s.publishingMasks=Map.of(key,union(s.state.deliveredSections(key),masks));publishing=true;
        try {
            for(var entry:masks.entrySet()) for(int y=0;y<16;y++) for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
                if(!entry.getValue().isRevealed(x,y,z)||s.state.delivered(key,entry.getKey()).isRevealed(x,y,z)) continue;
                var pos=new BlockPos(ChunkPos.getX(key)*16+x,entry.getKey()*16+y,ChunkPos.getZ(key)*16+z);
                s.player.connection.send(new ClientboundBlockUpdatePacket(pos,chunk.getBlockState(pos)));
                var be=chunk.getBlockEntities().get(pos);
                if(be!=null&&be.getUpdatePacket()!=null) s.player.connection.send(be.getUpdatePacket());
            }
            if(!s.player.connection.isAcceptingMessages()||!s.state.markDelivered(plan)) throw new IllegalStateException("Movement publication failed");
        } finally {publishing=false;s.publishingMasks=Map.of();}
        queueChunk(s,key);
    }
    private void refreshSight(Session s) {
        var config=configuration.current();var settings=config.settings();
        // The exterior is already present in initial chunks. No protected sight candidate can
        // exist this high; avoid capturing a 35-cubed geometry snapshot while flying overhead.
        if(s.player.getEyeY()-settings.revealRangeBlocks()>=config.policy().maxY()) {
            s.job=null;s.lastRefresh=tick;s.dirty=false;return;
        }
        if(tick-s.lastRefresh<settings.refreshIntervalTicks()&&!s.dirty) return;
        long start=System.nanoTime();
        try {
            var view=new VisibilityEvaluator.Viewpoint(s.player.getEyePosition(),s.player.getBoundingBox(),null,true);
            if(s.job!=null&&(s.jobOrigin.distanceToSqr(view.eyes())>=4||s.jobBody.getXsize()!=view.body().getXsize()||s.jobBody.getYsize()!=view.body().getYsize())) s.job=null;
            if(s.job==null) {
                int bound=VisibilityEvaluator.snapshotBlockBound(settings.revealRangeBlocks());
                if(bound>settings.budgets().maxSnapshotBlocksPerJob()) throw new IllegalStateException("Visibility snapshot exceeds configuration budget");
                var geometry=VisibilityEvaluator.capture(s.level,view,settings.revealRangeBlocks());
                Map<Long,Map<Integer,SectionVisibility>> known=new HashMap<>();
                int radius=settings.revealRangeBlocks()+1;
                int minX=(int)Math.floor(view.eyes().x-radius)>>4,maxX=(int)Math.floor(view.eyes().x+radius)>>4;
                int minZ=(int)Math.floor(view.eyes().z-radius)>>4,maxZ=(int)Math.floor(view.eyes().z+radius)>>4;
                for(int z=minZ;z<=maxZ;z++)for(int x=minX;x<=maxX;x++) {
                    long key=ChunkPos.pack(x,z);
                    known.put(key,union(s.state.deliveredSections(key),surfaces.masks(new ChunkPos(x,z),config.policy())));
                }
                s.job=VisibilityEvaluator.createJob(geometry,view,config.policy(),settings.revealRangeBlocks(),known);
                s.jobOrigin=view.eyes();s.jobBody=view.body();
            }
            int remaining=Math.min(rayWorkRemaining,settings.budgets().maxGeometryChecksPerTickPerViewer()-s.rayWorkUsed);
            if(remaining>0&&!s.job.done()) {
                var sight=s.job.step(32768,remaining);
                rayWorkRemaining-=sight.rayWork();s.rayWorkUsed+=sight.rayWork();
                sight.chunks().forEach((key,masks)->{
                    if(s.state.phase(key)!=ViewerState.Phase.UNTRACKED) {
                        var underground=undergroundMasks(s,key,masks);
                        if(underground.isEmpty())return;
                        s.desired.merge(key,underground,BlindstoneRuntime::union);
                        if(addsKnowledge(s.state,key,underground)) queueChunk(s,key);
                    }
                });
            }
            s.lastRefresh=tick;s.dirty=!s.job.done();
        } finally {long cost=System.nanoTime()-start;workSpentNanos+=cost;diagnostics.timing(BlindstoneDiagnostics.Timing.GEOMETRY,cost);}
    }
    private void prepareLighting(Session s) {
        long start=System.nanoTime();
        var priorities=new ArrayList<>(s.pending.values());
        priorities.sort(Comparator.comparingInt(p->ChunkPos.unpack(p.key).distanceSquared(s.player.chunkPosition())));
        int requested=0;
        for(var pending:priorities) {
            if(pending.prepared!=null) continue;
            if(!hasWorkBudget(start)) break;
            var context=frozen(s,pending);
            if(!s.lights.request(ChunkPos.unpack(pending.key),context,pending.generation)) break;
            if(++requested>=16) break;
        }
        long left=Math.max(1,configuration.current().settings().budgets().maxWorkNanosPerTick()-workSpentNanos-(System.nanoTime()-start));
        s.lights.step(2_000_000,Math.min(left,5_000_000));
        for(var pending:priorities) {
            if(pending.prepared!=null||!hasWorkBudget(start)) continue;
            var light=s.lights.getReady(ChunkPos.unpack(pending.key),pending.generation);
            if(light==null) continue;
            var chunk=s.level.getChunkSource().getChunkNow(ChunkPos.getX(pending.key),ChunkPos.getZ(pending.key));
            if(chunk==null) {forget(s,pending.key);continue;}
            var masks=union(pending.masks,s.state.deliveredSections(pending.key));
            pending.prepared=new ClientboundLevelChunkWithLightPacket(ChunkPos.getX(pending.key),ChunkPos.getZ(pending.key),
                    ChunkFilter.snapshot(chunk,configuration.current().policy(),union(masks,contextMasks(s,pending))),light);
            pending.publishedMasks=masks;
            pending.preparedTick=tick;
        }
        workSpentNanos+=System.nanoTime()-start;
    }
    private PacketFilter.Context frozen(Session s,Pending pending) {
        if(pending.context!=null) return pending.context;
        Map<Long,Map<Integer,SectionVisibility>> masks=new HashMap<>();
        var center=ChunkPos.unpack(pending.key);
        for(int z=center.z()-1;z<=center.z()+1;z++) for(int x=center.x()-1;x<=center.x()+1;x++) {
            long key=ChunkPos.pack(x,z);
            masks.put(key,union(key==pending.key?pending.masks:s.state.deliveredSections(key),
                    surfaces.masks(new ChunkPos(x,z),configuration.current().policy())));
        }
        pending.context=new FrozenContext(s.level,configuration.current().policy(),Map.copyOf(masks));
        return pending.context;
    }
    private Map<Integer,SectionVisibility> contextMasks(Session s,Pending p) {
        return frozen(s,p).masks(ChunkPos.unpack(p.key));
    }
    private void publishReady(Session s) {
        while(!s.batches.isEmpty()) {
            var batch=s.batches.peek();
            if(!ready(s,batch)) break;
            sendBatch(s,batch);s.batches.remove();
        }
        // Coalesced resends update heightmaps, blocks, block entities and light in one vanilla packet.
        Set<Long> held=new HashSet<>();
        for(var batch:s.batches) for(var frame:batch.frames) held.addAll(collectChunkKeys(frame.packet));
        for(var p:List.copyOf(s.pending.values())) if(p.prepared!=null&&!held.contains(p.key)&&s.clientChunks.contains(p.key)) {
            sendPreparedChunk(s,p,null,true);finishChunk(s,p);
        }
    }
    private boolean ready(Session s,Batch batch) {
        for(var frame:batch.frames) for(long key:collectChunkKeys(frame.packet)) {
            if(s.state.phase(key)==ViewerState.Phase.UNTRACKED) continue;
            var p=s.pending.get(key);if(p==null||p.prepared==null) return false;
        }
        return true;
    }
    private void sendBatch(Session s,Batch batch) {
        var outputs=new ArrayList<Frame>();var committed=new LinkedHashMap<Long,Pending>();int count=0;
        for(var frame:batch.frames) {
            if(frame.packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
                var p=s.pending.get(ChunkPos.pack(chunk.x(),chunk.z()));
                if(p==null) {complete(s,frame.callback,null);continue;}
                // Safety may grow while lighting runs; never re-conceal a body cell already delivered.
                prepareBodySuperset(s,p);
                outputs.add(new Frame(p.prepared,frame.callback,frame.flush));committed.put(p.key,p);count++;
            } else if(frame.packet instanceof ClientboundChunkBatchFinishedPacket) outputs.add(new Frame(new ClientboundChunkBatchFinishedPacket(count),frame.callback,frame.flush));
            else if(frame.packet instanceof ClientboundBundlePacket) {
                var safe=prepareBundle(s,frame.packet,committed);
                if(safe!=null) outputs.add(new Frame(safe,frame.callback,frame.flush));else complete(s,frame.callback,null);
            } else outputs.add(frame);
        }
        if(batch.originalBatch&&count==0) {
            if(batch.frames.stream().anyMatch(Frame::flush)) connection(s.player).flushChannel();
            outputs.forEach(frame->complete(s,frame.callback,null));
            s.player.connection.chunkSender.onChunkBatchReceivedByClient(9);
            return;
        }
        s.publishingMasks=new HashMap<>();committed.forEach((key,p)->s.publishingMasks.put(key,p.publishedMasks));publishing=true;
        try {for(var frame:outputs) sendTrusted(s,frame.packet,frame.callback,frame.flush);}
        finally {publishing=false;s.publishingMasks=Map.of();}
        if(!s.player.connection.isAcceptingMessages()) throw new IllegalStateException("Deferred chunk connection closed");
        connection(s.player).flushChannel();
        committed.values().forEach(p->finishChunk(s,p));
    }
    @SuppressWarnings("unchecked")
    private Packet<?> prepareBundle(Session s,Packet<?> packet,Map<Long,Pending> committed) {
        if(packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            var p=s.pending.get(ChunkPos.pack(chunk.x(),chunk.z()));if(p==null)return null;
            prepareBodySuperset(s,p);committed.put(p.key,p);return p.prepared;
        }
        if(packet instanceof ClientboundBundlePacket bundle) {
            var children=new ArrayList<Packet<? super ClientGamePacketListener>>();
            for(var child:bundle.subPackets()) {var safe=prepareBundle(s,child,committed);if(safe!=null)children.add((Packet<? super ClientGamePacketListener>)safe);}
            return children.isEmpty()?null:new ClientboundBundlePacket(children);
        }
        return PacketFilter.filter(packet,new Context(s,null));
    }
    private void prepareBodySuperset(Session s,Pending p) {
        var masks=union(p.publishedMasks,s.state.deliveredSections(p.key));
        if(!sameMasks(masks,p.publishedMasks)) {
            var chunk=s.level.getChunkSource().getChunkNow(ChunkPos.getX(p.key),ChunkPos.getZ(p.key));
            if(chunk==null) throw new IllegalStateException("Prepared chunk disappeared");
            p.prepared=new ClientboundLevelChunkWithLightPacket(ChunkPos.getX(p.key),ChunkPos.getZ(p.key),
                    ChunkFilter.snapshot(chunk,configuration.current().policy(),union(masks,contextMasks(s,p))),p.prepared.lightData());p.publishedMasks=masks;
        }
    }
    private void sendPreparedChunk(Session s,Pending p,ChannelFutureListener cb,boolean flush) {
        prepareBodySuperset(s,p);s.publishingMasks=Map.of(p.key,p.publishedMasks);publishing=true;
        try {sendTrusted(s,p.prepared,cb,flush);} finally {publishing=false;s.publishingMasks=Map.of();}
    }
    private void sendTrusted(Session s,Packet<?> safe,ChannelFutureListener cb,boolean flush) {
        if(!connection(s.player).isConnected()) throw new IllegalStateException("Protected connection closed before publication");
        s.trustedPacket=safe;
        try {connection(s.player).send(safe,cb,flush);}
        finally {s.trustedPacket=null;}
    }
    private void finishChunk(Session s,Pending p) {
        boolean knowledge=addsKnowledge(s.state,p.key,p.publishedMasks);
        var plan=s.state.queue(p.key,p.publishedMasks);
        if(!s.state.markDelivered(plan)) throw new IllegalStateException("Chunk publication was invalidated");
        s.clientChunks.add(p.key);s.pending.remove(p.key,p);s.entityVisibility.clear();
        diagnostics.hookLoaded(BlindstoneDiagnostics.PacketPath.CHUNK_DATA);diagnostics.observed(BlindstoneDiagnostics.PacketPath.CHUNK_DATA,true);
        diagnostics.enforcement(BlindstoneDiagnostics.PacketPath.CHUNK_DATA,true);diagnostics.observed(BlindstoneDiagnostics.PacketPath.LIGHTING,true);
        diagnostics.enforcement(BlindstoneDiagnostics.PacketPath.LIGHTING,true);
        if(knowledge) for(int z=ChunkPos.getZ(p.key)-1;z<=ChunkPos.getZ(p.key)+1;z++) for(int x=ChunkPos.getX(p.key)-1;x<=ChunkPos.getX(p.key)+1;x++) {
            long key=ChunkPos.pack(x,z);if(key==p.key||!s.clientChunks.contains(key))continue;
            var q=s.pending.remove(key);s.lights.forget(new ChunkPos(x,z));queueChunk(s,key);
        }
        if(addsKnowledge(s.state,p.key,s.desired.getOrDefault(p.key,Map.of()))) queueChunk(s,p.key);
    }
    private void complete(Session s,ChannelFutureListener cb,Throwable error) {
        if(cb==null)return;
        ((PacketCompletion)connection(s.player)).blindstone$complete(cb,error);
    }
    private void invalidateLight(Session s,ChunkPos changed) {
        s.lights.invalidate(changed);
        for(int z=changed.z()-1;z<=changed.z()+1;z++)for(int x=changed.x()-1;x<=changed.x()+1;x++) {
            long key=ChunkPos.pack(x,z);if(s.state.phase(key)==ViewerState.Phase.UNTRACKED)continue;
            var old=s.pending.remove(key);queueChunk(s,key);
        }
    }
    public void geometryChanged(ServerLevel level,BlockPos pos) {
        checkThread();
        if(level!=server.overworld())return;
        surfaces.invalidate(pos);
        boolean exteriorChanged=!viewers.isEmpty()&&surfaces.isExposed(pos,configuration.current().policy());
        for(var s:viewers.values()) if(s.level==level) {
            s.entityVisibility.clear();
            revokeUndelivered(s,ChunkPos.containing(pos));
            if(s.job!=null&&(s.job.intersectsChanged(pos)||exteriorChanged&&s.job.intersectsChanged(ChunkPos.containing(pos)))) {s.job=null;s.dirty=true;}
            s.state.geometryChanged(ChunkPos.pack(pos));
            boolean exposed=exteriorChanged||!configuration.current().policy().protects(pos.getY())||authorized(s,pos);
            if(!exposed) {
                for(var p:s.pending.values()) {
                    var c=p.context;
                    if(c!=null&&c.masks(ChunkPos.containing(pos)).getOrDefault(pos.getY()>>4,SectionVisibility.none()).isRevealed(pos.getX()&15,pos.getY()&15,pos.getZ()&15)) {exposed=true;break;}
                }
            }
            if(exposed&&s.lights!=null) {
                if(exteriorChanged) invalidateExteriorLight(s,pos);
                else invalidateLight(s,ChunkPos.containing(pos));
            }
        }
    }
    private void invalidateExteriorLight(Session s,BlockPos changed) {
        var chunk=ChunkPos.containing(changed);
        invalidateLight(s,chunk);
        if((changed.getX()&15)==0)invalidateLight(s,new ChunkPos(chunk.x()-1,chunk.z()));
        if((changed.getX()&15)==15)invalidateLight(s,new ChunkPos(chunk.x()+1,chunk.z()));
        if((changed.getZ()&15)==0)invalidateLight(s,new ChunkPos(chunk.x(),chunk.z()-1));
        if((changed.getZ()&15)==15)invalidateLight(s,new ChunkPos(chunk.x(),chunk.z()+1));
    }
    private void invalidateExteriorLight(Session s,ChunkPos changed) {
        // An exterior column can also expose a face in the next chunk. Those faces have their
        // own 15-block lighting halos; invalidate both generations before any pending publication.
        invalidateLight(s,changed);
        invalidateLight(s,new ChunkPos(changed.x()-1,changed.z()));
        invalidateLight(s,new ChunkPos(changed.x()+1,changed.z()));
        invalidateLight(s,new ChunkPos(changed.x(),changed.z()-1));
        invalidateLight(s,new ChunkPos(changed.x(),changed.z()+1));
    }
    /** Unsent authorization is revoked when a nearby occluder can change; sent knowledge is retained. */
    private void revokeUndelivered(Session s,ChunkPos changed) {
        boolean affected=s.desired.entrySet().stream().anyMatch(e->addsKnowledge(s.state,e.getKey(),e.getValue())
                &&Math.abs((long)ChunkPos.getX(e.getKey())-changed.x())<=2
                &&Math.abs((long)ChunkPos.getZ(e.getKey())-changed.z())<=2);
        if(!affected) return;
        var pending=List.copyOf(s.pending.keySet());
        s.desired.clear();s.reprepareLighting();s.job=null;s.dirty=true;
        pending.forEach(key->queueChunk(s,key));
    }
    private void requeueTracked(Session s) {
        // Preserve initial vanilla batches before using remaining capacity for retained client chunks.
        for(var b:s.batches)for(var f:b.frames)collectChunkKeys(f.packet).forEach(key->queueChunk(s,key));
        if(s.openBatch!=null)for(var f:s.openBatch.frames)collectChunkKeys(f.packet).forEach(key->queueChunk(s,key));
        s.state.trackedChunks().stream().sorted(Comparator.comparingInt(key->ChunkPos.unpack(key).distanceSquared(s.player.chunkPosition())))
                .forEach(key->queueChunk(s,key));
    }
    public void chunksChanged(ServerLevel level,ChunkPos chunk) {
        checkThread();if(level!=server.overworld())return;
        surfaces.invalidate(chunk);
        for(var s:viewers.values()) if(s.level==level&&s.lights!=null) {
            revokeUndelivered(s,chunk);
            if(s.job!=null&&s.job.intersectsChanged(chunk)) {s.job=null;s.dirty=true;}
            invalidateExteriorLight(s,chunk);
        }
    }
    public boolean entityMayBeSeen(ServerPlayer player,Entity entity) {
        checkThread();if(entity instanceof Player||!protectedWorld(player))return true;
        if(entity.level()!=player.level())return false;
        var s=session(player);if(!s.clientChunks.contains(entity.chunkPosition().pack()))return false;
        var policy=configuration.current().policy();if(!policy.protects(entity.blockPosition().getY()))return true;
        if(!authorized(s,entity.blockPosition()))return false;
        double range=configuration.current().settings().revealRangeBlocks()+4;
        if(player.distanceToSqr(entity)>range*range)return false;
        Vec3 eyes=player.getEyePosition();var box=entity.getBoundingBox();
        var cached=s.entityVisibility.get(entity.getId());
        if(cached!=null&&cached.eyes.equals(eyes)&&cached.box.equals(box))return cached.visible;
        long started=System.nanoTime();
        boolean visible=false;
        try {
            visible=entitySight(s,eyes,box);
            if(s.entityVisibility.size()<MAX_KNOWN_ENTITIES)s.entityVisibility.put(entity.getId(),new EntitySight(eyes,box,visible));
            return visible;
        } finally {workSpentNanos+=System.nanoTime()-started;}
    }
    private boolean entitySight(Session s,Vec3 eyes,AABB box) {
        var targets=List.of(box.getCenter(),new Vec3((box.minX+box.maxX)/2,box.maxY-0.01,(box.minZ+box.maxZ)/2));
        for(var target:targets) {
            int bound=VisibilityEvaluator.sightSnapshotBlockBound(eyes,target);if(bound>4096)continue;
            int remaining=Math.min(512,Math.min(rayWorkRemaining,
                    configuration.current().settings().budgets().maxGeometryChecksPerTickPerViewer()-s.rayWorkUsed));
            if(remaining<=0||!hasWorkBudget())return false;
            var geometry=VisibilityEvaluator.captureSight(s.level,eyes,target,4096);
            var sight=VisibilityEvaluator.clearSight(geometry,eyes,target,remaining);
            rayWorkRemaining-=sight.rayWork();s.rayWorkUsed+=sight.rayWork();
            if(sight.clear())return true;
        }
        return false;
    }
    private boolean authorized(Session s,BlockPos pos) {
        if(!configuration.current().policy().protects(pos.getY()))return true;
        long key=ChunkPos.pack(pos);
        if(s.clientChunks.contains(key)&&surfaces.isExposed(pos,configuration.current().policy()))return true;
        var mask=s.state.delivered(key,pos.getY()>>4);
        if(publishing)mask=mask.union(s.publishingMasks.getOrDefault(key,Map.of()).getOrDefault(pos.getY()>>4,SectionVisibility.none()));
        return mask.isRevealed(pos.getX()&15,pos.getY()&15,pos.getZ()&15);
    }
    private boolean bodySafetyDelivered(Session s) {
        var input=safetyInput(s);var geometry=VisibilityEvaluator.captureSafety(s.level,input,8192);
        var safety=VisibilityEvaluator.evaluateSafety(geometry,input,configuration.current().policy());
        return safety.safetyComplete()&&safety.chunks().entrySet().stream().allMatch(e->!addsKnowledge(s.state,e.getKey(),undergroundMasks(s,e.getKey(),e.getValue())));
    }
    private Map<Integer,SectionVisibility> undergroundMasks(Session s,long key,Map<Integer,SectionVisibility> masks) {
        var surface=surfaces.masks(ChunkPos.unpack(key),configuration.current().policy());
        Map<Integer,SectionVisibility> out=new HashMap<>();
        masks.forEach((y,mask)->{
            var hidden=mask.subtract(surface.getOrDefault(y,SectionVisibility.none()));
            if(hidden.count()!=0)out.put(y,hidden);
        });
        return Map.copyOf(out);
    }
    public void reset(ServerPlayer player) {
        checkThread();var s=viewers.get(player.getUUID());if(s==null)return;
        var tracked=s.state.trackedChunks();s.state.reset();tracked.forEach(s.state::track);
        s.reprepareLighting();s.entityVisibility.clear();s.desired.clear();s.previousBody=null;s.previousVehicle=null;s.previousVehicleId=-1;s.job=null;s.dirty=true;s.lastSafeDelivery=tick;
        // Vanilla retains same-dimension chunks; queue complete destination-independent filtered refreshes.
        requeueTracked(s);updateCounts();
    }
    public void remove(ServerPlayer player) {
        checkThread();var s=viewers.remove(player.getUUID());if(s!=null)s.clearLighting();updateCounts();
    }
    public void failSystemically(BlindstoneDiagnostics.Subsystem subsystem,BlindstoneDiagnostics.Failure failure) {
        checkThread();failed=true;diagnostics.activation(BlindstoneDiagnostics.Activation.REFUSED);diagnostics.failed(subsystem,failure);
        for(var s:List.copyOf(viewers.values()))disconnect(s.player,"Blindstone protection is unavailable; server repair is required.");
    }
    public void reload() throws IOException {
        checkThread();
        try {
            configuration.reload(server.overworld(),candidate->{
                if(!candidate.policy().equals(configuration.current().policy())&&!viewers.isEmpty())
                    throw new IllegalArgumentException("Changing protection bounds requires all viewers to disconnect first");
                if(viewers.values().stream().anyMatch(s->s.state.trackedCount()>candidate.settings().budgets().maxTrackedChunksPerViewer()))
                    throw new IllegalArgumentException("New tracking budget cannot contain active viewers");
                for(var s:viewers.values()){s.reprepareLighting();s.job=null;s.previousBody=null;s.previousVehicle=null;s.previousVehicleId=-1;s.dirty=true;}
            });
            for(var s:viewers.values())requeueTracked(s);
            diagnostics.increment(BlindstoneDiagnostics.Counter.CONFIG_RELOADS);
        }catch(IOException|RuntimeException error){diagnostics.failed(BlindstoneDiagnostics.Subsystem.CONFIGURATION,BlindstoneDiagnostics.Failure.RELOAD_REJECTED);throw error;}
    }
    public void disable() {
        checkThread();enabled=false;diagnostics.activation(BlindstoneDiagnostics.Activation.DISABLED_BY_CONSOLE);
        org.slf4j.LoggerFactory.getLogger("blindstone").warn("Blindstone manually DISABLED from console: real world information will resume on reconnect.");
        for(var s:List.copyOf(viewers.values()))disconnect(s.player,"Blindstone was disabled by the server console. Reconnect to receive the real world.");
    }
    public String inspect(ServerPlayer player) {
        var s=viewers.get(player.getUUID());return s==null?"Viewer untracked; bypass OFF.":"Tracked chunks="+s.state.trackedCount()+"; queued="+s.pending.size()
                +"; delivered chunks="+s.clientChunks.size()+"; visibility candidates="+(s.job==null?"not scheduled":s.job.completedCandidates()+"/"+s.job.totalCandidates())
                +"; known entities="+s.announced.size()+"; light jobs="+(s.lights==null?0:s.lights.pendingCount())+"; bypass OFF.";
    }
    public void disconnect(ServerPlayer player,String reason) {
        remove(player);diagnostics.increment(BlindstoneDiagnostics.Counter.DISCONNECTED_VIEWERS);
        var c=connection(player);var message=Component.literal(reason);
        c.send(new net.minecraft.network.protocol.common.ClientboundDisconnectPacket(message),future->c.disconnect(message));
    }
    private boolean hasWorkBudget(long started){return workSpentNanos+System.nanoTime()-started<configuration.current().settings().budgets().maxWorkNanosPerTick();}
    private boolean hasWorkBudget(){return workSpentNanos<configuration.current().settings().budgets().maxWorkNanosPerTick();}
    public boolean tryTrackingStep(){checkThread();if(failed||!enabled||!hasWorkBudget()||rayWorkRemaining<=0)return false;rayWorkRemaining--;return true;}
    public void recordTrackingWork(long nanos){checkThread();if(nanos<0)throw new IllegalArgumentException("Negative tracking work");workSpentNanos+=nanos;}
    private void updateCounts(){diagnostics.counts(viewers.size(),viewers.values().stream().mapToInt(s->s.state.trackedCount()).sum(),viewers.values().stream().mapToInt(s->s.pending.size()).sum());}
    private static Map<Integer,SectionVisibility> union(Map<Integer,SectionVisibility> a,Map<Integer,SectionVisibility> b){
        var out=new HashMap<>(a);b.forEach((y,m)->out.merge(y,m,SectionVisibility::union));return Map.copyOf(out);
    }
    private static boolean sameMasks(Map<Integer,SectionVisibility>a,Map<Integer,SectionVisibility>b){
        return a.entrySet().stream().allMatch(e->e.getValue().union(b.getOrDefault(e.getKey(),SectionVisibility.none())).count()==e.getValue().count())
                &&b.entrySet().stream().allMatch(e->e.getValue().union(a.getOrDefault(e.getKey(),SectionVisibility.none())).count()==e.getValue().count());
    }
    private static boolean addsKnowledge(ViewerState state,long key,Map<Integer,SectionVisibility> masks){
        return masks.entrySet().stream().anyMatch(e->state.delivered(key,e.getKey()).union(e.getValue()).count()>state.delivered(key,e.getKey()).count());
    }
    private static BlindstoneDiagnostics.PacketPath path(Packet<?> p){
        if(p instanceof ClientboundLevelChunkWithLightPacket)return BlindstoneDiagnostics.PacketPath.CHUNK_DATA;
        if(p instanceof ClientboundBlockUpdatePacket||p instanceof ClientboundSectionBlocksUpdatePacket)return BlindstoneDiagnostics.PacketPath.BLOCK_UPDATES;
        if(p instanceof ClientboundBlockEntityDataPacket)return BlindstoneDiagnostics.PacketPath.BLOCK_ENTITIES;
        if(p instanceof ClientboundBundlePacket)return BlindstoneDiagnostics.PacketPath.BUNDLES;
        if(p instanceof ClientboundLightUpdatePacket)return BlindstoneDiagnostics.PacketPath.LIGHTING;
        return null;
    }
    private final class Context implements PacketFilter.Context {
        final Session s;final Map<Long,Map<Integer,SectionVisibility>> frozen;
        Context(Session s,Map<Long,Map<Integer,SectionVisibility>> frozen){this.s=s;this.frozen=frozen;}
        public ServerLevel level(){return s.level;}
        public ConcealmentPolicy policy(){return configuration.current().policy();}
        public boolean isAuthorized(BlockPos pos){return authorized(s,pos);}
        public boolean isEntityVisible(int id){if(id==s.player.getId())return true;var e=s.level.getEntity(id);return e instanceof Player||s.announced.contains(id)&&e!=null&&entityMayBeSeen(s.player,e);}
        public boolean maySpawnEntity(int id){var e=s.level.getEntity(id);return e!=null&&entityMayBeSeen(s.player,e);}
        public boolean isEntityKnown(int id){return id==s.player.getId()||s.level.getEntity(id)instanceof Player||s.announced.contains(id);}
        public boolean isPlayerEntity(int id){return id==s.player.getId()||s.level.getEntity(id)instanceof Player;}
        public void entitySpawned(int id){if(s.announced.size()>=MAX_KNOWN_ENTITIES&&!s.announced.contains(id))throw new IllegalStateException("Entity memory budget exhausted");s.announced.add(id);}
        public void entityRemoved(int id){s.announced.remove(id);}
        public Map<Integer,SectionVisibility> masks(ChunkPos chunk){return frozen==null?union(s.state.deliveredSections(chunk.pack()),surfaces.masks(chunk,policy())):frozen.getOrDefault(chunk.pack(),Map.of());}
        public Vec3 viewerPosition(){return s.player.position();}
        public ClientboundLightUpdatePacketData light(ChunkPos chunk){var p=s.pending.get(chunk.pack());var light=p==null?null:s.lights.getReady(chunk,p.generation);if(light==null)throw new IllegalStateException("Filtered lighting is not ready");return light;}
    }
    private record FrozenContext(ServerLevel level,ConcealmentPolicy policy,Map<Long,Map<Integer,SectionVisibility>> views) implements PacketFilter.Context {
        public boolean isAuthorized(BlockPos pos){return !policy.protects(pos.getY())||masks(ChunkPos.containing(pos)).getOrDefault(pos.getY()>>4,SectionVisibility.none()).isRevealed(pos.getX()&15,pos.getY()&15,pos.getZ()&15);}
        public boolean isEntityVisible(int id){return false;}
        public Map<Integer,SectionVisibility> masks(ChunkPos chunk){return views.getOrDefault(chunk.pack(),Map.of());}
    }
    private static final class Session {
        final ServerPlayer player;final ServerLevel level;final ViewerState state;
        final Map<Integer,EntitySight> entityVisibility=new HashMap<>();
        final Set<Integer> announced=new HashSet<>();final Set<Long> clientChunks=new HashSet<>();
        final Map<Long,Map<Integer,SectionVisibility>> desired=new HashMap<>();
        final Map<Long,Pending> pending=new LinkedHashMap<>();final ArrayDeque<Batch>batches=new ArrayDeque<>();
        LightScheduler lights;Batch openBatch;Packet<?> trustedPacket;long generation;
        int previousVehicleId=-1;AABB previousBody,previousVehicle;VisibilityEvaluator.Job job;Vec3 jobOrigin;AABB jobBody;
        long lastRefresh=-100,lastSafeDelivery;int rayWorkUsed;boolean dirty=true;
        Map<Long,Map<Integer,SectionVisibility>> publishingMasks=Map.of();
        Session(ServerPlayer player,BlindstoneConfig.EffectiveConfig config){this.player=player;level=player.level();state=new ViewerState(config.settings().budgets().maxTrackedChunksPerViewer(),config.policy().minY()>>4,config.policy().maxY()>>4);}
        void reprepareLighting(){if(lights!=null)lights.reset();pending.clear();publishingMasks=Map.of();}
        void clearLighting(){
            var connection=player.connection==null?null:((com.evanchubbuck.blindstone.mixin.ServerCommonListenerAccess)player.connection).blindstone$connection();
            var failure=new IllegalStateException("Blindstone delivery session ended before publication");
            for(var batch:batches) for(var frame:batch.frames) if(frame.callback!=null)
                ((PacketCompletion)connection).blindstone$complete(frame.callback,failure);
            if(openBatch!=null) for(var frame:openBatch.frames) if(frame.callback!=null)
                ((PacketCompletion)connection).blindstone$complete(frame.callback,failure);
            reprepareLighting();batches.clear();openBatch=null;
        }
    }
    private static final class Pending {
        final long key,generation,queuedTick;final Map<Integer,SectionVisibility>masks;
        PacketFilter.Context context;ClientboundLevelChunkWithLightPacket prepared;Map<Integer,SectionVisibility>publishedMasks=Map.of();long preparedTick;
        Pending(long key,Map<Integer,SectionVisibility>masks,long generation,long tick){this.key=key;this.masks=masks;this.generation=generation;queuedTick=tick;}
    }
    private static final class Batch {
        final boolean originalBatch;final long tick;final List<Frame>frames=new ArrayList<>();int chunkCount;
        Batch(boolean originalBatch,long tick){this.originalBatch=originalBatch;this.tick=tick;}
    }
    private record EntitySight(Vec3 eyes,AABB box,boolean visible){}
    private record Frame(Packet<?> packet,ChannelFutureListener callback,boolean flush){}
}
