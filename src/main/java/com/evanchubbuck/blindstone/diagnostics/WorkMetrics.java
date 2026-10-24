package com.evanchubbuck.blindstone.diagnostics;

/** Opt-in, server-thread benchmark counters. Disabled during ordinary operation. */
public final class WorkMetrics {
    private WorkMetrics() { }
    public static boolean enabled;
    public static final long[] lightPhaseNanos=new long[9];
    public static long captures, cells, safetyCaptures, rayChecks, chunkFilters, partialSections, lightJobs, queued, sectionWrites, lightOperations, boundaryScans, boundaryCacheHits, boundaryInvalidations, boundaryNanos, boundaryCellReads, boundaryClassifications, geometryClassifications;
    public static long chargedPacketNanos, sightSteps, sightStepRays, maxSightStepRays, sightBuildNanos, sightStepNanos;
    public static void reset() { java.util.Arrays.fill(lightPhaseNanos,0); chargedPacketNanos=sightSteps=sightStepRays=maxSightStepRays=sightBuildNanos=sightStepNanos=0; captures=cells=safetyCaptures=rayChecks=chunkFilters=partialSections=lightJobs=queued=sectionWrites=lightOperations=boundaryScans=boundaryCacheHits=boundaryInvalidations=boundaryNanos=boundaryCellReads=boundaryClassifications=geometryClassifications=0; }
    public static java.util.Map<String, Long> snapshot() {
        var result = new java.util.LinkedHashMap<String,Long>();
        result.put("charged_packet_ns",chargedPacketNanos);
        result.put("sightSteps",sightSteps); result.put("sightStepRays",sightStepRays); result.put("maxSightStepRays",maxSightStepRays);
        result.put("sight_build_ns",sightBuildNanos); result.put("sight_step_ns",sightStepNanos);
        result.put("captures",captures); result.put("cells",cells); result.put("safetyCaptures",safetyCaptures);
        result.put("rayChecks",rayChecks); result.put("chunkFilters",chunkFilters); result.put("partialSections",partialSections);
        result.put("lightJobs",lightJobs); result.put("queued",queued); result.put("sectionWrites",sectionWrites);
        result.put("lightOperations",lightOperations); result.put("boundaryScans",boundaryScans);
        result.put("boundaryClassifications",boundaryClassifications);
        result.put("geometryClassifications",geometryClassifications);
        result.put("boundary_ns",boundaryNanos); result.put("boundaryCellReads",boundaryCellReads);
        result.put("boundaryCacheHits",boundaryCacheHits); result.put("boundaryInvalidations",boundaryInvalidations);
        String[] phases={"capture","boundary","initialize","skySeed","skyFlood","blockSeed","blockFlood","pack","done"};
        for(int i=0;i<phases.length;i++) result.put("light_"+phases[i]+"_ns",lightPhaseNanos[i]);
        return result;
    }
}
