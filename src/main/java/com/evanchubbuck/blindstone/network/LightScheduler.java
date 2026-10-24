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
        private final Map<SharedKey, Shared> shared = new java.util.HashMap<>();
        private final java.util.LinkedHashMap<SharedKey,Shared> idle=new java.util.LinkedHashMap<>();
        private long idleBytes;
        private int schedulers;
        private boolean evictIdle() {
            var iterator=idle.entrySet().iterator();
            if(!iterator.hasNext()) return false;
            var value=iterator.next().getValue();iterator.remove();shared.remove(value.key,value);
            idleBytes-=value.bytes;usedBytes-=value.bytes;return true;
        }
        private void retain(Shared value) {
            idle.put(value.key,value);idleBytes+=value.bytes;
            while(idle.size()>64 || idleBytes>16L*1024*1024) evictIdle();
        }
        private void activate(Shared value) {
            if(idle.remove(value.key)!=null) idleBytes-=value.bytes;
        }
        private final Map<net.minecraft.world.level.chunk.LevelChunk, Long> revisions = new java.util.WeakHashMap<>();
        public MemoryBudget(long maxBytes) {
            if (maxBytes < 262144 || maxBytes > 1_073_741_824L) throw new IllegalArgumentException("Invalid global light memory budget");
            this.maxBytes = maxBytes;
        }
        public synchronized long usedBytes() { return usedBytes; }
        public long maxBytes() { return maxBytes; }
        private synchronized boolean reserve(long bytes) {
            if(bytes<0) return false;
            while(bytes>maxBytes-usedBytes) if(!evictIdle()) return false;
            usedBytes += bytes;
            return true;
        }
        private synchronized void release(long bytes) {
            usedBytes -= bytes;
            if (usedBytes < 0) throw new IllegalStateException("Invalid light memory accounting");
        }
    }

    private record SharedKey(net.minecraft.server.level.ServerLevel level,
            com.evanchubbuck.blindstone.ConcealmentPolicy policy, ChunkPos center,
            java.util.List<Object> terrain,
            Map<Long,Map<Integer,com.evanchubbuck.blindstone.SectionVisibility>> masks) { }
    private static final class Shared {
        final SharedKey key;
        FilteredLight.Job job;
        long bytes;
        int owners;
        ClientboundLightUpdatePacketData light;
        Shared(SharedKey key, FilteredLight.Job job, long bytes) { this.key=key;this.job=job;this.bytes=bytes; }
    }
    private record Pending(ChunkPos chunk, long generation, Shared shared) { }
    private record Ready(long generation, Shared shared) { }

    private SharedKey sharedKey(ChunkPos center, PacketFilter.Context context) {
        var terrain=new java.util.ArrayList<Object>();
        Map<Long,Map<Integer,com.evanchubbuck.blindstone.SectionVisibility>> masks=new java.util.HashMap<>();
        for(int z=center.z()-1;z<=center.z()+1;z++) for(int x=center.x()-1;x<=center.x()+1;x++) {
            long key=ChunkPos.pack(x,z);
            var source=context.level().getChunkSource().getChunkNow(x,z);
            terrain.add(source);
            terrain.add(source==null?0L:memory.revisions.getOrDefault(source,0L));
            var nonempty=new java.util.HashMap<Integer,com.evanchubbuck.blindstone.SectionVisibility>();
            context.masks(new ChunkPos(x,z)).forEach((y,m)->{if(m.count()!=0)nonempty.put(y,m);});
            masks.put(key,Map.copyOf(nonempty));
        }
        // Terrain horizons depend on one further chunk outside the light halo.
        for(int z=center.z()-2;z<=center.z()+2;z++) for(int x=center.x()-2;x<=center.x()+2;x++) {
            if(Math.abs(z-center.z())<2 && Math.abs(x-center.x())<2) continue;
            var source=context.level().getChunkSource().getChunkNow(x,z);
            terrain.add(source);terrain.add(source==null?0L:memory.revisions.getOrDefault(source,0L));
        }
        return new SharedKey(context.level(),context.policy(),center,java.util.Collections.unmodifiableList(terrain),Map.copyOf(masks));
    }
    private void release(Shared shared) {
        if(--shared.owners==0) {
            if(shared.light!=null) memory.retain(shared);
            else {memory.shared.remove(shared.key,shared);memory.release(shared.bytes);}
        }
    }
    private net.minecraft.server.level.ServerLevel level;
    private boolean registered;
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
        if(level!=null && level!=frozenContext.level()) throw new IllegalArgumentException("Scheduler cannot cross worlds");
        level=frozenContext.level();
        if(!registered) {registered=true;memory.schedulers++;}
        long key = chunk.pack();
        var cached = ready.get(key);
        if (cached != null && cached.generation() == generation) return true;
        var pending = jobs.get(key);
        if (pending != null && pending.generation() == generation) return true;
        remove(key);
        if (jobs.size() >= maxJobs) return false;
        var sharedKey=sharedKey(chunk,frozenContext);
        var shared=memory.shared.get(sharedKey);
        if(shared==null) {
            long bytes=FilteredLight.estimatedJobBytes(chunk,frozenContext);
            while(!memory.reserve(bytes)) if(!evictReady()) return false;
            try {
                shared=new Shared(sharedKey,FilteredLight.begin(chunk,frozenContext),bytes);
                memory.shared.put(sharedKey,shared);
                if(com.evanchubbuck.blindstone.diagnostics.WorkMetrics.enabled)
                    com.evanchubbuck.blindstone.diagnostics.WorkMetrics.lightJobs++;
            } catch(RuntimeException|Error failure) {memory.release(bytes);throw failure;}
        }
        memory.activate(shared);
        shared.owners++;
        jobs.put(key,new Pending(chunk,generation,shared));
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
                var shared=pending.shared();
                if(shared.light==null) consumed += shared.job.step(Math.min(4096, maxOperations - consumed), remaining);
                if (shared.light!=null || shared.job.isDone()) {
                    completedJobs++;
                    if(shared.light==null) {
                        completedOperations += shared.job.totalOperations();
                        completedNanos += shared.job.totalNanos();
                        lastCompletedCells = shared.job.cellCount();
                        shared.light=shared.job.result();
                        shared.job=null;
                        long bytes=8192L+(long)(shared.light.skyUpdates().size()+shared.light.blockUpdates().size())*2048;
                        for(var masks:shared.key.masks().values()) bytes+=masks.size()*600L;
                        memory.release(shared.bytes);
                        shared.bytes=0;
                        while(!memory.reserve(bytes)) if(!evictReady()) throw new FilteredLight.BudgetExceededException("Global light cache memory exhausted");
                        shared.bytes=bytes;
                    }
                    released = true;
                    cache(key, pending.generation(), shared);
                } else jobs.put(key, pending);
            } catch (RuntimeException | Error failure) {
                if (!released) release(pending.shared());
                throw failure;
            }
        }
        if (com.evanchubbuck.blindstone.diagnostics.WorkMetrics.enabled)
            com.evanchubbuck.blindstone.diagnostics.WorkMetrics.lightOperations += consumed;
        return consumed;
    }

    public @Nullable ClientboundLightUpdatePacketData getReady(ChunkPos chunk, long generation) {
        var value = ready.get(chunk.pack());
        return value != null && value.generation() == generation ? value.shared().light : null;
    }

    public @Nullable ClientboundLightUpdatePacketData peekReady(ChunkPos chunk) {
        var value=ready.get(chunk.pack());return value==null?null:value.shared().light;
    }

    public void revise(ChunkPos changed) {
        if(level!=null) {
            var source=level.getChunkSource().getChunkNow(changed.x(),changed.z());
            if(source!=null) memory.revisions.merge(source,1L,Long::sum);
        }
    }

    /** Every center whose 15-block halo touches this changed chunk is invalidated. */
    public void invalidate(ChunkPos changed) {
        revise(changed);
        var idleIterator=memory.idle.entrySet().iterator();
        while(idleIterator.hasNext()) {
            var shared=idleIterator.next().getValue();
            if(Math.abs(shared.key.center().x()-changed.x())<=2 && Math.abs(shared.key.center().z()-changed.z())<=2) {
                idleIterator.remove();memory.shared.remove(shared.key,shared);memory.idleBytes-=shared.bytes;memory.release(shared.bytes);
            }
        }
        for (int z = changed.z() - 2; z <= changed.z() + 2; z++)
            for (int x = changed.x() - 2; x <= changed.x() + 2; x++) {
                long key=ChunkPos.pack(x,z);
                var old=ready.get(key);
                remove(key);
                if(old!=null && old.shared().owners==0 && memory.idle.remove(old.shared().key)!=null) {
                    var shared=old.shared();memory.idleBytes-=shared.bytes;memory.shared.remove(shared.key,shared);memory.release(shared.bytes);
                }
            }
    }

    public void forget(ChunkPos chunk) { remove(chunk.pack()); }
    public void reset() {
        jobs.values().forEach(job -> release(job.shared()));
        ready.values().forEach(value -> release(value.shared()));
        jobs.clear();
        ready.clear();
        if(registered) {
            registered=false;
            if(--memory.schedulers==0) while(memory.evictIdle()) { }
        }
    }
    public int pendingCount() { return jobs.size(); }
    public int readyCount() { return ready.size(); }
    public long completedJobs() { return completedJobs; }
    public long completedOperations() { return completedOperations; }
    public long completedNanos() { return completedNanos; }
    public int lastCompletedCells() { return lastCompletedCells; }

    private void cache(long key, long generation, Shared shared) {
        while(ready.size()>=maxReady) evictReady();
        ready.put(key,new Ready(generation,shared));
    }
    private boolean evictReady() {
        var iterator = ready.entrySet().iterator();
        if (!iterator.hasNext()) return false;
        var value = iterator.next().getValue();
        iterator.remove();
        release(value.shared());
        return true;
    }
    private void remove(long key) {
        var pending = jobs.remove(key);
        if (pending != null) release(pending.shared());
        var cached = ready.remove(key);
        if (cached != null) release(cached.shared());
    }
    private static boolean near(ChunkPos a, ChunkPos b) {
        return Math.abs((long) a.x() - b.x()) <= 1 && Math.abs((long) a.z() - b.z()) <= 1;
    }
}
