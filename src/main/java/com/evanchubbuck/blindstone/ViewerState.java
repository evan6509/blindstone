package com.evanchubbuck.blindstone;

import java.util.HashMap;
import java.util.Map;

/** Server-thread-owned, bounded prototype state. No distance or client request grants visibility. */
public final class ViewerState {
    public enum Phase { UNTRACKED, CONCEALED, REVEAL_QUEUED, REVEALED }

    private final int maxChunks;
    private final int minSection;
    private final int maxSection;
    private final Map<Long, TrackedChunk> chunks = new HashMap<>();

    public ViewerState(int maxChunks, int minSection, int maxSection) {
        if (maxChunks < 1 || maxChunks > 4096 || minSection >= maxSection
                || (long) maxSection - minSection > 64) {
            throw new IllegalArgumentException("Invalid chunk or vertical memory budget");
        }
        this.maxChunks = maxChunks;
        this.minSection = minSection;
        this.maxSection = maxSection;
    }

    /** Saturation refuses tracking; callers must defer safely or disconnect, never send real data. */
    public boolean track(long chunk) {
        if (chunks.containsKey(chunk)) return true;
        if (chunks.size() == maxChunks) return false;
        chunks.put(chunk, new TrackedChunk());
        return true;
    }

    public Phase phase(long chunk) {
        TrackedChunk tracked = chunks.get(chunk);
        if (tracked == null) return Phase.UNTRACKED;
        if (tracked.pending != null) return Phase.REVEAL_QUEUED;
        return tracked.sent.isEmpty() ? Phase.CONCEALED : Phase.REVEALED;
    }

    public SectionVisibility delivered(long chunk, int sectionY) {
        TrackedChunk tracked = chunks.get(chunk);
        return tracked == null ? SectionVisibility.none()
                : tracked.sent.getOrDefault(sectionY, SectionVisibility.none());
    }

    /** Only a future validated geometry evaluator may supply these masks. One pending plan per chunk. */
    public RevealPlan queue(long chunk, Map<Integer, SectionVisibility> authorized) {
        TrackedChunk tracked = chunks.get(chunk);
        if (tracked == null) throw new IllegalStateException("Chunk is untracked");
        Map<Integer, SectionVisibility> snapshot = Map.copyOf(authorized);
        if (snapshot.keySet().stream().anyMatch(y -> y < minSection || y >= maxSection)) {
            throw new IllegalArgumentException("Authorization outside vertical bounds");
        }
        RevealPlan plan = new RevealPlan(chunk, tracked, tracked.revision, snapshot);
        tracked.pending = plan;
        return plan;
    }

    public boolean isCurrent(RevealPlan plan) {
        return chunks.get(plan.chunk) == plan.owner && plan.owner.revision == plan.revision
                && plan.owner.pending == plan;
    }

    /** Call only after successful coherent delivery; queuing does not authorize normal updates. */
    public boolean markDelivered(RevealPlan plan) {
        if (!isCurrent(plan)) return false;
        plan.sections.forEach((y, mask) -> {
            if (mask.count() != 0) plan.owner.sent.merge(y, mask, SectionVisibility::union);
        });
        plan.owner.pending = null;
        return true;
    }

    /** Retain previously delivered knowledge; reject geometry work started before this change. */
    public void geometryChanged(long chunk) {
        TrackedChunk tracked = chunks.get(chunk);
        if (tracked != null) {
            tracked.revision++;
            tracked.pending = null;
        }
    }

    public void untrack(long chunk) { chunks.remove(chunk); }

    /** Disconnect, dimension change, teleport, respawn, and accepted config changes invalidate all work. */
    public void reset() { chunks.clear(); }

    public int trackedCount() { return chunks.size(); }

    public long pendingCount() { return chunks.values().stream().filter(c -> c.pending != null).count(); }

    public static final class RevealPlan {
        private final long chunk;
        private final TrackedChunk owner;
        private final long revision;
        private final Map<Integer, SectionVisibility> sections;

        private RevealPlan(long chunk, TrackedChunk owner, long revision,
                           Map<Integer, SectionVisibility> sections) {
            this.chunk = chunk;
            this.owner = owner;
            this.revision = revision;
            this.sections = sections;
        }

        public Map<Integer, SectionVisibility> sections() { return sections; }
    }

    private static final class TrackedChunk {
        private long revision;
        private RevealPlan pending;
        private final Map<Integer, SectionVisibility> sent = new HashMap<>();
    }
}
