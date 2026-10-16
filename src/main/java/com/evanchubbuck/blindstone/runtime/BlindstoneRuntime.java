package com.evanchubbuck.blindstone.runtime;

import com.evanchubbuck.blindstone.SectionVisibility;
import com.evanchubbuck.blindstone.ViewerState;
import com.evanchubbuck.blindstone.config.BlindstoneConfig;
import com.evanchubbuck.blindstone.config.ConfigManager;
import com.evanchubbuck.blindstone.diagnostics.BlindstoneDiagnostics;
import com.evanchubbuck.blindstone.network.PacketFilter;
import com.evanchubbuck.blindstone.visibility.VisibilityEvaluator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
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

/** Server-thread runtime for closed-admission integration testing. No production activation switch. */
public final class BlindstoneRuntime {
    /** Experimental per-viewer bound; saturation disconnects instead of retaining an unbounded entity-id set. */
    public static final int MAX_KNOWN_ENTITIES = 16384;
    private static final Map<MinecraftServer, BlindstoneRuntime> SERVERS = new ConcurrentHashMap<>();
    private final MinecraftServer server;
    private final ConfigManager configuration;
    private final Map<UUID, Session> viewers = new HashMap<>();
    private final BlindstoneDiagnostics diagnostics;
    private boolean failed;
    private boolean publishing;
    private long tick;
    private long workSpentNanos;
    private long refreshStartedNanos;
    private int rayWorkRemaining;
    private int nextViewer;

    public BlindstoneRuntime(MinecraftServer server, Path configFile) throws IOException {
        this.server = server;
        configuration = new ConfigManager(configFile);
        configuration.loadInitial(server.overworld());
        var loader = FabricLoader.getInstance();
        diagnostics = new BlindstoneDiagnostics(new BlindstoneDiagnostics.Versions(
                version(loader, "blindstone"), version(loader, "minecraft"), version(loader, "fabricloader"), version(loader, "fabric-api")));
        diagnostics.activation(BlindstoneDiagnostics.Activation.DEVELOPMENT_ONLY);
        // Mandatory mixin loading is not evidence that any outgoing category was observed or enforced.
    }

    private static String version(FabricLoader loader, String id) {
        return loader.getModContainer(id).orElseThrow().getMetadata().getVersion().getFriendlyString();
    }

    public static void start(MinecraftServer server) {
        try {
            SERVERS.put(server, new BlindstoneRuntime(server, FabricLoader.getInstance().getConfigDir().resolve("blindstone.json")));
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("Blindstone configuration/startup failed; player admission remains blocked", failure);
        }
    }

    public static BlindstoneRuntime get(MinecraftServer server) { return SERVERS.get(server); }
    public static void stop(MinecraftServer server) { SERVERS.remove(server); }
    public BlindstoneDiagnostics diagnostics() { return diagnostics; }
    public ConfigManager configuration() { return configuration; }

    private void checkThread() {
        if (!server.isSameThread()) throw new IllegalStateException("Blindstone runtime requires the owning server thread");
    }

    private boolean protectedWorld(ServerPlayer player) { return player.level().dimension().equals(Level.OVERWORLD); }

    private Session session(ServerPlayer player) {
        checkThread();
        Session existing = viewers.get(player.getUUID());
        if (existing != null && existing.player == player && existing.level == player.level()) return existing;
        Set<Long> retainedChunks = existing != null && existing.level == player.level() ? existing.state.trackedChunks() : Set.of();
        Set<Integer> retainedEntities = existing != null && existing.level == player.level() ? Set.copyOf(existing.announced) : Set.of();
        if (existing != null) existing.state.reset();
        if (existing != null) viewers.remove(player.getUUID());
        var config = configuration.current();
        if (viewers.size() >= config.settings().budgets().maxViewers())
            throw overload(BlindstoneDiagnostics.Subsystem.ADMISSION, "Viewer budget exhausted");
        Session created = new Session(player, config);
        created.lastSafeDelivery = tick;
        for (long chunk : retainedChunks) {
            if (!created.state.track(chunk)) throw new IllegalStateException("Unable to retain bounded chunk subscriptions after respawn");
        }
        created.announced.addAll(retainedEntities);
        viewers.put(player.getUUID(), created);
        return created;
    }

    /** All listener/direct Connection sends meet this boundary before the event-loop hop. */
    public Packet<?> filter(ServerPlayer player, Packet<?> packet) {
        checkThread();
        if (!protectedWorld(player)) {
            viewers.remove(player.getUUID());
            return packet;
        }
        if (failed) throw new IllegalStateException("Blindstone runtime is unavailable");
        Session session = session(player);
        var path = path(packet);
        long started = System.nanoTime();
        try {
            if (path != null) requireWorkBudget();
            bookkeeping(session, packet);
            Packet<?> safe = PacketFilter.filter(packet, new Context(session));
            diagnostics.hookLoaded(BlindstoneDiagnostics.PacketPath.DIRECT_CONNECTION);
            diagnostics.observed(BlindstoneDiagnostics.PacketPath.DIRECT_CONNECTION, safe != packet);
            diagnostics.enforcement(BlindstoneDiagnostics.PacketPath.DIRECT_CONNECTION, true);
            if (path != null) {
                diagnostics.hookLoaded(path);
                diagnostics.observed(path, safe != packet);
                diagnostics.enforcement(path, true);
            }
            if (safe == null) diagnostics.increment(BlindstoneDiagnostics.Counter.DROPPED_PACKETS);
            else if (safe != packet) diagnostics.increment(BlindstoneDiagnostics.Counter.FILTERED_PACKETS);
            return safe;
        } finally {
            long elapsed = System.nanoTime() - started;
            diagnostics.timing(BlindstoneDiagnostics.Timing.PACKET_FILTER, elapsed);
            // A refresh already includes its nested synchronous block/update sends.
            if (refreshStartedNanos == 0) workSpentNanos = saturatedAdd(workSpentNanos, elapsed);
            updateCounts();
        }
    }

    /** Walk in original child order once; PacketFilter owns the independent recursive rewriting pass. */
    private void bookkeeping(Session session, Packet<?> packet) {
        if (packet instanceof ClientboundBundlePacket bundle) {
            for (var child : bundle.subPackets()) bookkeeping(session, child);
        } else if (packet instanceof ClientboundRespawnPacket || packet instanceof ClientboundPlayerPositionPacket
                || packet instanceof ClientboundStartConfigurationPacket) {
            reset(session.player);
        } else if (packet instanceof ClientboundForgetLevelChunkPacket forget) {
            session.state.untrack(forget.pos().pack());
            session.job = null;
            session.dirty = true;
        } else if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            track(session, ChunkPos.pack(chunk.x(), chunk.z()));
        }
    }

    private void track(Session session, long chunk) {
        if (session.state.phase(chunk) != ViewerState.Phase.UNTRACKED) return;
        var config = configuration.current();
        int globalChunks = viewers.values().stream().mapToInt(s -> s.state.trackedCount()).sum();
        long bytes = (globalChunks + 1L) * config.maskBytesPerTrackedChunk();
        if (globalChunks >= config.settings().budgets().maxGlobalTrackedChunks()
                || bytes > config.settings().budgets().maxMaskBytes() || !session.state.track(chunk)) {
            throw overload(BlindstoneDiagnostics.Subsystem.LIFECYCLE, "Tracking/mask budget exhausted");
        }
        session.dirty = true;
        session.job = null;
    }

    /** Called once at START_SERVER_TICK so one global budget spans all worlds/viewers. */
    public void beginTick() {
        checkThread();
        if (tick != 0) diagnostics.timing(BlindstoneDiagnostics.Timing.TICK_WORK, workSpentNanos);
        tick++;
        workSpentNanos = 0;
        rayWorkRemaining = configuration.current().settings().budgets().maxGeometryChecksPerTickGlobal();
        viewers.values().removeIf(session -> session.player.isRemoved() || !session.player.connection.isAcceptingMessages());
        for (Session session : viewers.values()) session.rayWorkUsed = 0;
        updateCounts();
    }

    /** Run before entity tracker updates; publish block authorization before new entity pairing. */
    public void refresh(ServerLevel level) {
        checkThread();
        if (!level.dimension().equals(Level.OVERWORLD) || publishing || failed) return;
        var config = configuration.current();
        var settings = config.settings();
        List<Session> sessions = List.copyOf(viewers.values());
        int start = sessions.isEmpty() ? 0 : Math.floorMod(nextViewer, sessions.size());
        nextViewer = sessions.isEmpty() ? 0 : (start + 1) % sessions.size();
        for (int index = 0; index < sessions.size(); index++) {
            Session session = sessions.get((start + index) % sessions.size());
            if (session.level != level) continue;
            if (tick - session.lastRefresh < settings.refreshIntervalTicks() && !session.dirty) continue;
            boolean inBudget = hasWorkBudget();
            if (!inBudget) {
                diagnostics.increment(BlindstoneDiagnostics.Counter.OVERLOADS);
                if (tick - session.lastSafeDelivery > settings.budgets().movementDeliveryDeadlineTicks()) {
                    diagnostics.failed(BlindstoneDiagnostics.Subsystem.GEOMETRY, BlindstoneDiagnostics.Failure.MOVEMENT_DEADLINE);
                    disconnect(session.player, "Blindstone cannot maintain movement-safe delivery within its work budget.");
                }
                continue;
            }
            try {
                refresh(session, config);
            } catch (RuntimeFailure failure) {
                diagnostics.failed(failure.subsystem, failure.failure);
                disconnect(session.player, "Blindstone cannot maintain safe delivery within its configured budgets.");
            } catch (RuntimeException failure) {
                diagnostics.failed(BlindstoneDiagnostics.Subsystem.GEOMETRY, BlindstoneDiagnostics.Failure.INTERNAL_ERROR);
                disconnect(session.player, "Blindstone could not maintain a safe filtered view; reconnect after the server is repaired.");
            }
        }
        updateCounts();
    }

    private void refresh(Session session, BlindstoneConfig.EffectiveConfig config) {
        var settings = config.settings();
        long started = System.nanoTime();
        refreshStartedNanos = started;
        try {
            if (session.player.isPassenger()) throw new RuntimeFailure(BlindstoneDiagnostics.Subsystem.GEOMETRY,
                    BlindstoneDiagnostics.Failure.UNSUPPORTED_PATH, "Mounted movement has no validated vehicle-body safety envelope");
            int snapshotBlocks = VisibilityEvaluator.snapshotBlockBound(settings.revealRangeBlocks());
            if (snapshotBlocks > settings.budgets().maxSnapshotBlocksPerJob())
                throw overload(BlindstoneDiagnostics.Subsystem.GEOMETRY, "Snapshot budget exhausted before capture");
            var view = new VisibilityEvaluator.Viewpoint(session.player.getEyePosition(), session.player.getBoundingBox(), session.previousBody, true);
            var geometry = VisibilityEvaluator.capture(session.level, view, settings.revealRangeBlocks());
            requireWorkBudget();
            var safety = VisibilityEvaluator.evaluateSafety(geometry, view, config.policy());
            if (!safety.safetyComplete()) throw new RuntimeFailure(BlindstoneDiagnostics.Subsystem.GEOMETRY,
                    BlindstoneDiagnostics.Failure.UNSUPPORTED_PATH, "Accepted movement exceeds the fixed safety envelope");
            if (session.job != null && (session.jobOrigin.distanceToSqr(view.eyes()) >= 1
                    || session.jobBody.getXsize() != view.body().getXsize() || session.jobBody.getYsize() != view.body().getYsize()
                    || session.jobBody.getZsize() != view.body().getZsize())) session.job = null;
            if (session.job == null && globalPendingWork() < settings.budgets().maxGlobalQueuedChunks()) {
                session.job = VisibilityEvaluator.createJob(geometry, view, config.policy(), settings.revealRangeBlocks());
                session.jobOrigin = view.eyes();
                session.jobBody = view.body();
            }
            VisibilityEvaluator.Result sight = null;
            int remaining = Math.min(rayWorkRemaining, settings.budgets().maxGeometryChecksPerTickPerViewer() - session.rayWorkUsed);
            if (session.job != null && !session.job.done() && remaining > 0) {
                sight = session.job.step(Math.min(8192, remaining), remaining);
                rayWorkRemaining -= sight.rayWork();
                session.rayWorkUsed += sight.rayWork();
            }
            diagnostics.timing(BlindstoneDiagnostics.Timing.GEOMETRY, System.nanoTime() - started);
            requireWorkBudget();
            Map<Long, Map<Integer, SectionVisibility>> masks = sight == null ? safety.chunks() : mergeMasks(safety.chunks(), sight.chunks());
            for (var chunk : masks.entrySet()) {
                long key = chunk.getKey();
                if (session.state.phase(key) == ViewerState.Phase.UNTRACKED) continue;
                if (!addsKnowledge(session.state, key, chunk.getValue())) continue;
                requireWorkBudget();
                long viewerPending = Math.max(session.job != null && !session.job.done() ? 1 : 0, session.state.pendingCount() + 1);
                long globalPending = globalPendingWork() - pendingWork(session) + viewerPending;
                if (viewerPending > settings.budgets().maxQueuedChunksPerViewer()
                        || globalPending > settings.budgets().maxGlobalQueuedChunks())
                    throw overload(BlindstoneDiagnostics.Subsystem.GEOMETRY, "Reveal queue budget exhausted");
                var plan = session.state.queue(key, chunk.getValue());
                if (!session.state.isCurrent(plan)) continue;
                // Block updates first, then initial block-entity data. Every send is filtered again at Connection.
                publishing = true;
                try {
                    publish(session, key, plan);
                    if (!session.player.connection.isAcceptingMessages()) throw new IllegalStateException("Reveal connection closed before publication completed");
                    if (!session.state.markDelivered(plan)) throw new IllegalStateException("Stale publication");
                } finally {
                    publishing = false;
                    session.publishingMasks = Map.of();
                }
            }
            if (!bodySafetyDelivered(session)) throw new RuntimeFailure(BlindstoneDiagnostics.Subsystem.GEOMETRY,
                    BlindstoneDiagnostics.Failure.MOVEMENT_DEADLINE, "Current body safety has not reached the client");
            session.previousBody = session.player.getBoundingBox();
            session.lastRefresh = tick;
            session.lastSafeDelivery = tick;
            session.dirty = session.job == null || !session.job.done();
        } finally {
            workSpentNanos = saturatedAdd(workSpentNanos, System.nanoTime() - started);
            refreshStartedNanos = 0;
        }
    }

    private static boolean addsKnowledge(ViewerState state, long chunk, Map<Integer, SectionVisibility> masks) {
        return masks.entrySet().stream().anyMatch(e -> state.delivered(chunk, e.getKey()).union(e.getValue()).count()
                > state.delivered(chunk, e.getKey()).count());
    }

    private static Map<Long, Map<Integer, SectionVisibility>> mergeMasks(Map<Long, Map<Integer, SectionVisibility>> first,
            Map<Long, Map<Integer, SectionVisibility>> second) {
        Map<Long, Map<Integer, SectionVisibility>> result = new HashMap<>();
        first.forEach((chunk, sections) -> result.put(chunk, new HashMap<>(sections)));
        second.forEach((chunk, sections) -> {
            var target = result.computeIfAbsent(chunk, ignored -> new HashMap<>());
            sections.forEach((section, mask) -> target.merge(section, mask, SectionVisibility::union));
        });
        return result;
    }

    /** A retained viewer job and its synchronous publication are one work item, never two queued copies. */
    private static long pendingWork(Session session) {
        return Math.max(session.job != null && !session.job.done() ? 1 : 0, session.state.pendingCount());
    }

    private long globalPendingWork() { return viewers.values().stream().mapToLong(BlindstoneRuntime::pendingWork).sum(); }

    private boolean bodySafetyDelivered(Session session) {
        AABB body = session.player.getBoundingBox().inflate(VisibilityEvaluator.SAFETY_MARGIN);
        for (int y = (int) Math.floor(body.minY); y < Math.ceil(body.maxY); y++)
            for (int z = (int) Math.floor(body.minZ); z < Math.ceil(body.maxZ); z++)
                for (int x = (int) Math.floor(body.minX); x < Math.ceil(body.maxX); x++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (configuration.current().policy().protects(y) && !authorized(session, pos)) return false;
                }
        return true;
    }

    private boolean hasWorkBudget() {
        long active = refreshStartedNanos == 0 ? 0 : System.nanoTime() - refreshStartedNanos;
        return saturatedAdd(workSpentNanos, active) < configuration.current().settings().budgets().maxWorkNanosPerTick();
    }

    private void requireWorkBudget() {
        if (!hasWorkBudget()) throw overload(BlindstoneDiagnostics.Subsystem.GEOMETRY, "Tick work budget exhausted");
    }

    /** One global round-robin tracker slot; actual point-ray work is charged separately below. */
    public boolean tryTrackingStep() {
        checkThread();
        if (failed || !hasWorkBudget() || rayWorkRemaining <= 0) return false;
        rayWorkRemaining--;
        return true;
    }

    public void recordTrackingWork(long nanos) {
        checkThread();
        if (nanos < 0) throw new IllegalArgumentException("Negative tracking work duration");
        workSpentNanos = saturatedAdd(workSpentNanos, nanos);
    }

    private RuntimeFailure overload(BlindstoneDiagnostics.Subsystem subsystem, String reason) {
        diagnostics.increment(BlindstoneDiagnostics.Counter.OVERLOADS);
        return new RuntimeFailure(subsystem, BlindstoneDiagnostics.Failure.QUEUE_SATURATION, reason);
    }

    private void updateCounts() {
        diagnostics.counts(viewers.size(), viewers.values().stream().mapToInt(s -> s.state.trackedCount()).sum(),
                (int) globalPendingWork());
    }

    private static long saturatedAdd(long a, long b) { return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b; }

    private void publish(Session session, long key, ViewerState.RevealPlan plan) {
        ChunkPos chunkPos = ChunkPos.unpack(key);
        var chunk = session.level.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z());
        if (chunk == null) throw new IllegalStateException("Reveal chunk unavailable");
        // Authorization for this synchronous delivery only; ordinary queued plans never grant access.
        session.publishingChunk = key;
        session.publishingMasks = plan.sections();
        plan.sections().forEach((sectionY, mask) -> {
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                if (!mask.isRevealed(x, y, z) || session.state.delivered(key, sectionY).isRevealed(x, y, z)) continue;
                if (!session.player.connection.isAcceptingMessages()) throw new IllegalStateException("Reveal connection closed during publication");
                requireWorkBudget();
                BlockPos pos = new BlockPos(chunkPos.x() * 16 + x, sectionY * 16 + y, chunkPos.z() * 16 + z);
                session.player.connection.send(new ClientboundBlockUpdatePacket(pos, chunk.getBlockState(pos)));
            }
        });
        chunk.getBlockEntities().forEach((pos, entity) -> {
            var mask = plan.sections().get(pos.getY() >> 4);
            if (mask != null && mask.isRevealed(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15)
                    && !session.state.delivered(key, pos.getY() >> 4).isRevealed(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15)) {
                var update = entity.getUpdatePacket();
                if (update != null) {
                    requireWorkBudget();
                    session.player.connection.send(update);
                }
            }
        });
    }

    public boolean entityMayBeSeen(ServerPlayer player, Entity entity) {
        checkThread();
        if (entity instanceof Player || !protectedWorld(player)) return true;
        var policy = configuration.current().policy();
        if (!policy.protects(entity.blockPosition().getY())) return true;
        var session = session(player);
        if (!authorized(session, entity.blockPosition())) return false;
        if (player.distanceToSqr(entity) > configuration.current().settings().revealRangeBlocks()
                * configuration.current().settings().revealRangeBlocks()) return false;
        Vec3 eyes = player.getEyePosition();
        Vec3 target = entity.getBoundingBox().getCenter();
        var budgets = configuration.current().settings().budgets();
        int remaining = Math.min(rayWorkRemaining, budgets.maxGeometryChecksPerTickPerViewer() - session.rayWorkUsed);
        if (failed || !hasWorkBudget() || remaining <= 0) return false;
        int snapshotLimit = Math.min(4096, budgets.maxSnapshotBlocksPerJob());
        if (VisibilityEvaluator.sightSnapshotBlockBound(eyes, target) > snapshotLimit) return false;
        // A bounded point box avoids an entire reveal-radius capture for each entity pairing.
        var geometry = VisibilityEvaluator.captureSight(session.level, eyes, target, snapshotLimit);
        var sight = VisibilityEvaluator.clearSight(geometry, eyes, target, Math.min(256, remaining));
        rayWorkRemaining -= sight.rayWork();
        session.rayWorkUsed += sight.rayWork();
        return sight.clear();
    }

    private boolean authorized(Session session, BlockPos pos) {
        if (!configuration.current().policy().protects(pos.getY())) return true;
        long key = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
        var delivered = session.state.delivered(key, pos.getY() >> 4);
        boolean sent = delivered.isRevealed(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        if (sent || !publishing || key != session.publishingChunk) return sent;
        return session.publishingMasks.getOrDefault(pos.getY() >> 4, SectionVisibility.none())
                .isRevealed(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    public void geometryChanged(ServerLevel level, BlockPos pos) {
        checkThread();
        for (Session session : viewers.values()) if (session.level == level) {
            boolean jobAffected = session.job != null && session.job.intersectsChanged(pos);
            AABB bodyPath = session.player.getBoundingBox().inflate(VisibilityEvaluator.SAFETY_MARGIN + 1);
            if (session.previousBody != null) bodyPath = bodyPath.minmax(session.previousBody.inflate(VisibilityEvaluator.SAFETY_MARGIN + 1));
            boolean safetyAffected = bodyPath.intersects(new AABB(pos));
            if (jobAffected) {
                for (long chunk : session.state.trackedChunks()) session.state.geometryChanged(chunk);
                session.job = null;
            } else {
                // Revisions for changed cells/neighbor-dependent shapes still invalidate local pending sends.
                for (int z = (pos.getZ() - 1) >> 4; z <= (pos.getZ() + 1) >> 4; z++)
                    for (int x = (pos.getX() - 1) >> 4; x <= (pos.getX() + 1) >> 4; x++)
                        session.state.geometryChanged(ChunkPos.pack(x, z));
            }
            if (jobAffected || safetyAffected) session.dirty = true;
        }
    }

    public void reset(ServerPlayer player) {
        checkThread();
        var session = viewers.get(player.getUUID());
        if (session != null) {
            // Vanilla may retain already delivered chunks during a same-dimension teleport.
            // Keep subscriptions while replacing every ticket owner and authorization mask.
            Set<Long> tracked = session.state.trackedChunks();
            session.state.reset();
            for (long chunk : tracked) {
                if (!session.state.track(chunk)) throw new IllegalStateException("Unable to retain bounded chunk subscriptions");
            }
            // Retain the client's known ids until vanilla tracking sends their removals. Fresh
            // masks already withhold updates; clearing ids here would suppress those removals.
            session.publishingMasks = Map.of();
            session.job = null;
            session.previousBody = null;
            session.lastSafeDelivery = tick;
            session.dirty = true;
        }
        updateCounts();
    }

    public void remove(ServerPlayer player) { checkThread(); viewers.remove(player.getUUID()); updateCounts(); }

    /** Explicit systemic failure latches protection unavailable until restart; no automatic real-data fallback. */
    public void failSystemically(BlindstoneDiagnostics.Subsystem subsystem, BlindstoneDiagnostics.Failure failure) {
        checkThread();
        failed = true;
        diagnostics.activation(BlindstoneDiagnostics.Activation.REFUSED);
        diagnostics.failed(subsystem, failure);
        for (Session session : List.copyOf(viewers.values()))
            disconnect(session.player, "Blindstone protection is unavailable; protected-world admission requires server repair.");
    }

    public void reload() throws IOException {
        checkThread();
        try {
            configuration.reload(server.overworld(), candidate -> {
                if (!viewers.isEmpty()) throw new IllegalArgumentException("Online configuration changes await coherent client refresh support; disconnect all viewers first");
            });
            diagnostics.increment(BlindstoneDiagnostics.Counter.CONFIG_RELOADS);
        } catch (IOException | RuntimeException failure) {
            diagnostics.failed(BlindstoneDiagnostics.Subsystem.CONFIGURATION, BlindstoneDiagnostics.Failure.RELOAD_REJECTED);
            throw failure;
        }
    }

    public String inspect(ServerPlayer player) {
        checkThread();
        Session session = viewers.get(player.getUUID());
        return session == null ? "Viewer is untracked; bypass OFF."
                : "Tracked chunks=" + session.state.trackedCount() + "; queued=" + session.state.pendingCount()
                + "; visibility candidates=" + (session.job == null ? "not scheduled" : session.job.completedCandidates() + "/" + session.job.totalCandidates())
                + "; announced entities=" + session.announced.size() + "; bypass OFF; development admission remains CLOSED.";
    }

    public void disconnect(ServerPlayer player, String reason) {
        viewers.remove(player.getUUID());
        updateCounts();
        diagnostics.increment(BlindstoneDiagnostics.Counter.DISCONNECTED_VIEWERS);
        // Close transport after a reason packet without synchronously mutating ChunkMap while
        // its entity iterator may be invoking this failure boundary.
        var connection = ((com.evanchubbuck.blindstone.mixin.ServerCommonListenerAccess) player.connection).blindstone$connection();
        Component message = Component.literal(reason);
        connection.send(new net.minecraft.network.protocol.common.ClientboundDisconnectPacket(message),
                future -> connection.disconnect(message));
    }

    private static BlindstoneDiagnostics.PacketPath path(Packet<?> packet) {
        if (packet instanceof ClientboundLevelChunkWithLightPacket) return BlindstoneDiagnostics.PacketPath.CHUNK_DATA;
        if (packet instanceof ClientboundLightUpdatePacket) return BlindstoneDiagnostics.PacketPath.LIGHTING;
        if (packet instanceof ClientboundBlockEntityDataPacket) return BlindstoneDiagnostics.PacketPath.BLOCK_ENTITIES;
        if (packet instanceof ClientboundBlockUpdatePacket || packet instanceof ClientboundSectionBlocksUpdatePacket)
            return BlindstoneDiagnostics.PacketPath.BLOCK_UPDATES;
        if (packet instanceof ClientboundBundlePacket) return BlindstoneDiagnostics.PacketPath.BUNDLES;
        if (packet instanceof ClientboundExplodePacket) return BlindstoneDiagnostics.PacketPath.COMPOUND_PACKETS;
        if (packet instanceof ClientboundSoundPacket || packet instanceof ClientboundSoundEntityPacket)
            return BlindstoneDiagnostics.PacketPath.SOUNDS;
        if (packet instanceof ClientboundSetPassengersPacket || packet instanceof ClientboundSetEntityLinkPacket
                || packet instanceof ClientboundTakeItemEntityPacket || packet instanceof ClientboundDamageEventPacket
                || packet instanceof ClientboundSetEntityDataPacket) return BlindstoneDiagnostics.PacketPath.ENTITY_REFERENCES;
        if (packet instanceof ClientboundAddEntityPacket || packet instanceof ClientboundRemoveEntitiesPacket
                || packet instanceof ClientboundMoveEntityPacket || packet instanceof ClientboundEntityPositionSyncPacket
                || packet instanceof ClientboundTeleportEntityPacket || packet instanceof ClientboundMoveMinecartPacket
                || packet instanceof ClientboundSetEquipmentPacket || packet instanceof ClientboundSetEntityMotionPacket
                || packet instanceof ClientboundUpdateAttributesPacket || packet instanceof ClientboundUpdateMobEffectPacket
                || packet instanceof ClientboundRemoveMobEffectPacket || packet instanceof ClientboundEntityEventPacket
                || packet instanceof ClientboundRotateHeadPacket || packet instanceof ClientboundAnimatePacket
                || packet instanceof ClientboundHurtAnimationPacket || packet instanceof ClientboundProjectilePowerPacket
                || packet instanceof ClientboundSwingAnimationPacket || packet instanceof ClientboundSetCameraPacket
                || packet instanceof ClientboundMountScreenOpenPacket || packet instanceof ClientboundPlayerCombatKillPacket)
            return BlindstoneDiagnostics.PacketPath.ENTITY_TRACKING;
        if (packet instanceof ClientboundBlockEventPacket || packet instanceof ClientboundAddTransientBlockPacket
                || packet instanceof ClientboundBlockDestructionPacket || packet instanceof ClientboundLevelEventPacket
                || packet instanceof ClientboundOpenSignEditorPacket || packet instanceof ClientboundLevelParticlesPacket)
            return BlindstoneDiagnostics.PacketPath.SECONDARY_EVENTS;
        // Chat, commands, keepalives and other permitted non-world packets are not secondary-path evidence.
        return null;
    }

    private final class Context implements PacketFilter.Context {
        private final Session session;
        private Context(Session session) { this.session = session; }
        public ServerLevel level() { return session.level; }
        public com.evanchubbuck.blindstone.ConcealmentPolicy policy() { return configuration.current().policy(); }
        public boolean isAuthorized(BlockPos pos) { return authorized(session, pos); }
        public boolean isEntityVisible(int id) {
            if (id == session.player.getId()) return true;
            Entity entity = session.level.getEntity(id);
            return entity instanceof Player || session.announced.contains(id) && entity != null && entityMayBeSeen(session.player, entity);
        }
        public boolean maySpawnEntity(int id) {
            Entity entity = session.level.getEntity(id);
            return entity != null && entityMayBeSeen(session.player, entity);
        }
        public void entitySpawned(int id) {
            if (!session.announced.contains(id) && session.announced.size() >= MAX_KNOWN_ENTITIES)
                throw overload(BlindstoneDiagnostics.Subsystem.ENTITIES, "Known-entity memory budget exhausted");
            session.announced.add(id);
        }
        public void entityRemoved(int id) { session.announced.remove(id); }
        public boolean isPlayerEntity(int id) { return id == session.player.getId() || session.level.getEntity(id) instanceof Player; }
        public boolean isEntityKnown(int id) { return id == session.player.getId() || session.level.getEntity(id) instanceof Player || session.announced.contains(id); }
        public Map<Integer, SectionVisibility> masks(ChunkPos chunk) { return session.state.deliveredSections(chunk.pack()); }
        public Vec3 viewerPosition() { return session.player.position(); }
    }

    private static final class Session {
        private final ServerPlayer player;
        private final ServerLevel level;
        private final ViewerState state;
        private final Set<Integer> announced = new HashSet<>();
        private AABB previousBody;
        private long lastRefresh = -100;
        private long lastSafeDelivery;
        private int rayWorkUsed;
        private VisibilityEvaluator.Job job;
        private Vec3 jobOrigin;
        private AABB jobBody;
        private boolean dirty = true;
        private long publishingChunk;
        private Map<Integer, SectionVisibility> publishingMasks = Map.of();
        private Session(ServerPlayer player, BlindstoneConfig.EffectiveConfig config) {
            this.player = player;
            level = player.level();
            state = new ViewerState(config.settings().budgets().maxTrackedChunksPerViewer(), config.policy().minY() >> 4,
                    config.policy().maxY() >> 4);
        }
    }

    private static final class RuntimeFailure extends IllegalStateException {
        private final BlindstoneDiagnostics.Subsystem subsystem;
        private final BlindstoneDiagnostics.Failure failure;
        private RuntimeFailure(BlindstoneDiagnostics.Subsystem subsystem, BlindstoneDiagnostics.Failure failure, String reason) {
            super(reason);
            this.subsystem = subsystem;
            this.failure = failure;
        }
    }
}
