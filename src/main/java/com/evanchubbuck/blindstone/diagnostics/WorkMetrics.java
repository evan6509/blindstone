package com.evanchubbuck.blindstone.diagnostics;

/** Opt-in, server-thread benchmark counters. Disabled during ordinary operation. */
public final class WorkMetrics {
    private WorkMetrics() { }
    public static boolean enabled;
    public static long captures, cells, safetyCaptures, rayChecks, chunkFilters, partialSections, lightJobs, queued, sectionWrites, lightOperations;
    public static void reset() { captures=cells=safetyCaptures=rayChecks=chunkFilters=partialSections=lightJobs=queued=sectionWrites=lightOperations=0; }
    public static java.util.Map<String, Long> snapshot() {
        return java.util.Map.of("captures",captures,"cells",cells,"safetyCaptures",safetyCaptures,
                "rayChecks",rayChecks,"chunkFilters",chunkFilters,"partialSections",partialSections,
                "lightJobs",lightJobs,"queued",queued,"sectionWrites",sectionWrites,"lightOperations",lightOperations);
    }
}
