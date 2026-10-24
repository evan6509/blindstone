package com.evanchubbuck.blindstone.network;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;

/** Per-viewer cooperative jobs and generation-keyed cache sharing one explicit global memory cap. */
public final class LightScheduler {
    /** Pass the same instance to every viewer scheduler to enforce a global rather than per-viewer cap. */
    public static final class MemoryBudget {
        private final long maxBytes;
        private long usedBytes;
        public MemoryBudget(long maxBytes) {
            if (maxBytes < 262144 || maxBytes > 1_073_741_824L) throw new IllegalArgumentException("Invalid global light memory budget");
            this.maxBytes = maxBytes;
        }
        public synchronized long usedBytes() { return usedBytes; }
        public long maxBytes() { return maxBytes; }
        private synchronized boolean reserve(long bytes) {
            if (bytes < 0 || bytes > maxBytes - usedBytes) return false;
            usedBytes += bytes;
            return true;
        }
        private synchronized void release(long bytes) {
            usedBytes -= bytes;
            if (usedBytes < 0) throw new IllegalStateException("Invalid light memory accounting");
        }
    }

    private record Pending(ChunkPos chunk, long generation, FilteredLight.Job job, long bytes) { }
    private record Ready(long generation, ClientboundLightUpdatePacketData light, long bytes) { }
    private final int maxJobs;
    private final int maxReady;
    private final MemoryBudget memory;
    private final Map<Long, Pending> jobs = new LinkedHashMap<>();
    private final Map<Long, Ready> ready = new LinkedHashMap<>(16, 0.75F, true);
    private long completedJobs;
    private long completedOperations;
    private long completedNanos;
    private int lastCompletedCells;

    public LightScheduler(int maxJobs, int maxReady, MemoryBudget memory) {
        if (maxJobs < 1 || maxJobs > 64 || maxReady < 1 || maxReady > 4096 || memory == null)
            throw new IllegalArgumentException("Invalid light scheduler bounds");
        this.maxJobs = maxJobs;
        this.maxReady = maxReady;
        this.memory = memory;
    }

    /** False means bounded capacity is full; caller should keep delivery queued and retry. */
    public boolean request(ChunkPos chunk, PacketFilter.Context frozenContext, long generation) {
        if (!frozenContext.level().getServer().isSameThread()) throw new IllegalStateException("Light requests require the owning server thread");
        long key = chunk.pack();
        var cached = ready.get(key);
        if (cached != null && cached.generation() == generation) return true;
        var pending = jobs.get(key);
        if (pending != null && pending.generation() == generation) return true;
        remove(key);
        if (jobs.size() >= maxJobs) return false;
        long bytes = FilteredLight.estimatedJobBytes(frozenContext);
        while (!memory.reserve(bytes)) {
            if (!evictReady()) return false;
        }
        try {
            if (com.evanchubbuck.blindstone.diagnostics.WorkMetrics.enabled) com.evanchubbuck.blindstone.diagnostics.WorkMetrics.lightJobs++;
            jobs.put(key, new Pending(chunk, generation, FilteredLight.begin(chunk, frozenContext), bytes));
        } catch (RuntimeException | Error failure) {
            memory.release(bytes);
            throw failure;
        }
        return true;
    }

    /** Fair round-robin slices; time and work budgets cover capture, propagation and packet assembly. */
    public int step(int maxOperations, long maxNanos) {
        if (maxOperations < 1 || maxNanos < 1) throw new IllegalArgumentException("Positive light scheduler slice required");
        long start = System.nanoTime();
        int consumed = 0;
        while (!jobs.isEmpty() && consumed < maxOperations) {
            long remaining = maxNanos - (System.nanoTime() - start);
            if (remaining <= 0) break;
            var entry = jobs.entrySet().iterator().next();
            long key = entry.getKey();
            var pending = entry.getValue();
            jobs.remove(key);
            boolean released = false;
            try {
                consumed += pending.job().step(Math.min(4096, maxOperations - consumed), remaining);
                if (pending.job().isDone()) {
                    completedJobs++;
                    completedOperations += pending.job().totalOperations();
                    completedNanos += pending.job().totalNanos();
                    lastCompletedCells = pending.job().cellCount();
                    memory.release(pending.bytes());
                    released = true;
                    cache(key, pending.generation(), pending.job().result());
                } else jobs.put(key, pending);
            } catch (RuntimeException | Error failure) {
                if (!released) memory.release(pending.bytes());
                throw failure;
            }
        }
        if (com.evanchubbuck.blindstone.diagnostics.WorkMetrics.enabled)
            com.evanchubbuck.blindstone.diagnostics.WorkMetrics.lightOperations += consumed;
        return consumed;
    }

    public @Nullable ClientboundLightUpdatePacketData getReady(ChunkPos chunk, long generation) {
        var value = ready.get(chunk.pack());
        return value != null && value.generation() == generation ? value.light() : null;
    }

    /** Every center whose 15-block halo touches this changed chunk is invalidated. */
    public void invalidate(ChunkPos changed) {
        for (int z = changed.z() - 1; z <= changed.z() + 1; z++)
            for (int x = changed.x() - 1; x <= changed.x() + 1; x++) remove(ChunkPos.pack(x, z));
    }

    public void forget(ChunkPos chunk) { remove(chunk.pack()); }
    public void reset() {
        jobs.values().forEach(job -> memory.release(job.bytes()));
        ready.values().forEach(value -> memory.release(value.bytes()));
        jobs.clear();
        ready.clear();
    }
    public int pendingCount() { return jobs.size(); }
    public int readyCount() { return ready.size(); }
    public long completedJobs() { return completedJobs; }
    public long completedOperations() { return completedOperations; }
    public long completedNanos() { return completedNanos; }
    public int lastCompletedCells() { return lastCompletedCells; }

    private void cache(long key, long generation, ClientboundLightUpdatePacketData light) {
        long bytes = 4096L + (long) (light.skyUpdates().size() + light.blockUpdates().size()) * 2048;
        while (ready.size() >= maxReady) evictReady();
        while (!memory.reserve(bytes)) {
            if (!evictReady()) throw new FilteredLight.BudgetExceededException("Global light cache memory exhausted");
        }
        ready.put(key, new Ready(generation, light, bytes));
    }
    private boolean evictReady() {
        var iterator = ready.entrySet().iterator();
        if (!iterator.hasNext()) return false;
        var value = iterator.next().getValue();
        iterator.remove();
        memory.release(value.bytes());
        return true;
    }
    private void remove(long key) {
        var pending = jobs.remove(key);
        if (pending != null) memory.release(pending.bytes());
        var cached = ready.remove(key);
        if (cached != null) memory.release(cached.bytes());
    }
    private static boolean near(ChunkPos a, ChunkPos b) {
        return Math.abs((long) a.x() - b.x()) <= 1 && Math.abs((long) a.z() - b.z()) <= 1;
    }
}
