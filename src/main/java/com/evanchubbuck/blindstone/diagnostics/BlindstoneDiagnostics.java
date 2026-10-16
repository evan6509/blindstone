package com.evanchubbuck.blindstone.diagnostics;

import com.evanchubbuck.blindstone.config.BlindstoneConfig.EffectiveConfig;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Local aggregate diagnostics. Deliberately accepts no identities, positions, seeds, payloads, or exception messages. */
public final class BlindstoneDiagnostics {
    public enum Activation { DEVELOPMENT_ONLY, REFUSED, ENFORCING, DISABLED_BY_CONSOLE }
    public enum PacketPath { CHUNK_DATA, BLOCK_UPDATES, BLOCK_ENTITIES, LIGHTING, ENTITY_TRACKING,
        ENTITY_REFERENCES, SECONDARY_EVENTS, COMPOUND_PACKETS, SOUNDS, BUNDLES, DIRECT_CONNECTION }
    public enum Subsystem { CONFIGURATION, GEOMETRY, CHUNK_FILTER, LIGHTING, BLOCK_UPDATES, BLOCK_ENTITIES,
        ENTITIES, SECONDARY_EVENTS, PACKET_GATE, LIFECYCLE, ADMISSION }
    public enum Failure { INVALID_CONFIGURATION, RELOAD_REJECTED, SERIALIZATION_FAILED, STALE_WORK,
        QUEUE_SATURATION, MOVEMENT_DEADLINE, MISSING_HOOK, UNSUPPORTED_PATH, INTERNAL_ERROR }
    public enum Counter { FILTERED_PACKETS, DROPPED_PACKETS, STALE_JOBS, OVERLOADS, DISCONNECTED_VIEWERS, CONFIG_RELOADS }
    public enum Timing { GEOMETRY, CHUNK_SNAPSHOT, PACKET_FILTER, TICK_WORK }

    public record Versions(String mod, String minecraft, String loader, String fabricApi) {
        public Versions {
            version(mod); version(minecraft); version(loader); version(fabricApi);
        }
        private static void version(String value) {
            if (value == null || !value.matches("[0-9][A-Za-z0-9.+_-]{0,63}"))
                throw new IllegalArgumentException("Diagnostics versions must be bounded version identifiers");
        }
    }

    private final Versions versions;
    private final EnumMap<PacketPath, Evidence> paths = new EnumMap<>(PacketPath.class);
    private final EnumMap<Subsystem, EnumMap<Failure, Long>> failures = new EnumMap<>(Subsystem.class);
    private final EnumMap<Counter, Long> counters = new EnumMap<>(Counter.class);
    private final EnumMap<Timing, TimingAggregate> timings = new EnumMap<>(Timing.class);
    private Activation activation = Activation.DEVELOPMENT_ONLY;
    private int activeViewers;
    private int trackedChunks;
    private int backlog;

    public BlindstoneDiagnostics(Versions versions) {
        this.versions = Objects.requireNonNull(versions);
        for (var path : PacketPath.values()) paths.put(path, new Evidence());
        for (var counter : Counter.values()) counters.put(counter, 0L);
        for (var timing : Timing.values()) timings.put(timing, new TimingAggregate());
    }

    public synchronized void activation(Activation value) { activation = Objects.requireNonNull(value); }
    public synchronized void counts(int viewers, int chunks, int pendingJobs) {
        if (viewers < 0 || chunks < 0 || pendingJobs < 0) throw new IllegalArgumentException("Diagnostics counts cannot be negative");
        activeViewers = viewers; trackedChunks = chunks; backlog = pendingJobs;
    }
    public synchronized void hookLoaded(PacketPath path) { paths.get(path).hookLoaded = true; }
    public synchronized void enforcement(PacketPath path, boolean active) { paths.get(path).enforcementActive = active; }
    public synchronized void observed(PacketPath path, boolean filtered) {
        var value = paths.get(path);
        value.observed = add(value.observed, 1);
        if (filtered) value.filtered = add(value.filtered, 1);
    }
    public synchronized void automatedTestPassed(PacketPath path) { paths.get(path).automatedTestPassed = true; }
    public synchronized void liveTestPassed(PacketPath path) { paths.get(path).liveTestPassed = true; }
    public synchronized void failed(Subsystem subsystem, Failure failure) {
        var values = failures.computeIfAbsent(subsystem, ignored -> new EnumMap<>(Failure.class));
        values.put(failure, add(values.getOrDefault(failure, 0L), 1));
    }
    public synchronized void increment(Counter counter) { counters.put(counter, add(counters.get(counter), 1)); }
    public synchronized void timing(Timing timing, long nanos) {
        if (nanos < 0) throw new IllegalArgumentException("Timing cannot be negative");
        var value = timings.get(timing);
        value.samples = add(value.samples, 1);
        value.totalNanos = add(value.totalNanos, nanos);
        value.maxNanos = Math.max(value.maxNanos, nanos);
    }

    public synchronized String status(EffectiveConfig config) {
        return "Blindstone " + activation + "; player admission " + admissionState()
                + "; " + config.settings().dimensions().getFirst() + " Y[" + config.policy().minY() + "," + config.policy().maxY() + ")"
                + "; viewers=" + activeViewers + "; tracked chunks=" + trackedChunks + "; backlog=" + backlog
                + "; recorded failures=" + failureCount() + ". Versions: mod " + versions.mod() + " / Minecraft " + versions.minecraft()
                + " / Loader " + versions.loader() + " / Fabric API " + versions.fabricApi() + ".";
    }

    public synchronized String verify() {
        StringBuilder result = new StringBuilder("Hook loading alone is not protection evidence. ");
        for (var entry : paths.entrySet()) {
            var evidence = entry.getValue();
            result.append(entry.getKey()).append(": loaded=").append(evidence.hookLoaded)
                    .append(", observed=").append(evidence.observed).append(", filtered=").append(evidence.filtered)
                    .append(", enforced=").append(evidence.enforcementActive).append(", automatedTest=")
                    .append(evidence.automatedTestPassed).append(", liveTest=").append(evidence.liveTestPassed).append("; ");
        }
        return result.toString();
    }

    public synchronized String sanitizedReport(EffectiveConfig config, long configurationGeneration) {
        if (configurationGeneration < 0) throw new IllegalArgumentException("Configuration generation cannot be negative");
        JsonObject report = new JsonObject();
        report.addProperty("reportSchema", 1);
        report.addProperty("activation", activation.name());
        report.addProperty("admission", admissionState());
        report.addProperty("evidenceNotice", "Hook loading, observation, enforcement, automated tests, and live tests are independent evidence.");
        var gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        report.add("versions", gson.toJsonTree(versions));
        report.add("configuration", gson.toJsonTree(config.settings()));
        report.addProperty("effectiveMinY", config.policy().minY());
        report.addProperty("effectiveMaxYExclusive", config.policy().maxY());
        report.addProperty("configurationGeneration", configurationGeneration);
        report.addProperty("activeViewers", activeViewers);
        report.addProperty("trackedChunks", trackedChunks);
        report.addProperty("backlog", backlog);
        report.add("coverage", gson.toJsonTree(paths));
        report.add("failureCounts", gson.toJsonTree(failures));
        report.add("counters", gson.toJsonTree(counters));
        report.add("timings", gson.toJsonTree(timings));
        return gson.toJson(report) + "\n";
    }

    /** Writes only the sanitized aggregate; report destinations are never included inside the report. */
    public void writeReport(Path destination, EffectiveConfig config, long configurationGeneration) throws IOException {
        String contents = sanitizedReport(config, configurationGeneration);
        Path absolute = destination.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path temporary = Files.createTempFile(absolute.getParent(), ".blindstone-report-", ".tmp");
        try {
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private String admissionState() {
        return switch (activation) {
            case ENFORCING -> "OPEN";
            case DISABLED_BY_CONSOLE -> "UNFILTERED_BY_CONSOLE";
            default -> "DENIED";
        };
    }

    private long failureCount() {
        long count = 0;
        for (Map<Failure, Long> values : failures.values()) for (long value : values.values()) count = add(count, value);
        return count;
    }
    private static long add(long a, long b) { return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b; }
    private static final class Evidence {
        boolean hookLoaded;
        long observed;
        long filtered;
        boolean enforcementActive;
        boolean automatedTestPassed;
        boolean liveTestPassed;
    }
    private static final class TimingAggregate { long samples; long totalNanos; long maxNanos; }
}
