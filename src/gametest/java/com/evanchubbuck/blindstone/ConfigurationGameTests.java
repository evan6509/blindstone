package com.evanchubbuck.blindstone;

import com.evanchubbuck.blindstone.config.BlindstoneConfig;
import com.evanchubbuck.blindstone.config.ConfigCodec;
import com.evanchubbuck.blindstone.config.ConfigManager;
import com.evanchubbuck.blindstone.diagnostics.BlindstoneDiagnostics;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Real server-thread configuration lifecycle tests; diagnostics remain aggregate-only. */
public final class ConfigurationGameTests {
    @GameTest
    public void configurationRoundTripsAndUsesActualWorldMinimum(GameTestHelper helper) {
        var defaults = BlindstoneConfig.defaults();
        helper.assertTrue(ConfigCodec.decode(ConfigCodec.encode(defaults)).equals(defaults), "All defaults must round-trip exactly");
        var effective = defaults.forWorldBounds(helper.getLevel().getMinY(), helper.getLevel().getMinY() + helper.getLevel().getHeight());
        helper.assertTrue(effective.policy().minY() == helper.getLevel().getMinY() && effective.policy().maxY() == 64,
                "Protection must use the actual world minimum and exclusive configured cutoff");
        var mutable = new ArrayList<>(List.of(BlindstoneConfig.OVERWORLD));
        var config = new BlindstoneConfig(1, mutable, 64, true, true, "preserve_audible", defaults.replacements(), 8, 1, 0.0625, defaults.budgets());
        mutable.clear();
        helper.assertTrue(config.dimensions().equals(List.of(BlindstoneConfig.OVERWORLD)), "Mutable dimension inputs must not escape");
        fails(helper, () -> config.dimensions().clear(), UnsupportedOperationException.class);
        helper.succeed();
    }

    @GameTest
    public void strictConfigurationRejectsUnknownMissingDuplicateAndMistypedFields(GameTestHelper helper) {
        String json = ConfigCodec.encode(BlindstoneConfig.defaults());
        fails(helper, () -> ConfigCodec.decode(json.replace("\"schemaVersion\": 1", "\"schemaVersion\": 1, \"fallback\": true")), IllegalArgumentException.class);
        fails(helper, () -> ConfigCodec.decode(json.replace("\"schemaVersion\": 1,", "")), IllegalArgumentException.class);
        fails(helper, () -> ConfigCodec.decode(json.replace("\"schemaVersion\": 1", "\"schemaVersion\": 1, \"schemaVersion\": 1")), IllegalArgumentException.class);
        fails(helper, () -> ConfigCodec.decode(json.replace("\"maxViewers\": 128", "\"maxViewers\": 128, \"maxViewers\": 128")), IllegalArgumentException.class);
        fails(helper, () -> ConfigCodec.decode(json.replace("\"refreshIntervalTicks\": 1", "\"refreshIntervalTicks\": 2.5")), IllegalArgumentException.class);
        fails(helper, () -> ConfigCodec.decode(json.replace("\"concealAir\": true", "\"concealAir\": \"true\"")), IllegalArgumentException.class);
        fails(helper, () -> ConfigCodec.decode(json + " {}"), IllegalArgumentException.class);
        fails(helper, () -> ConfigCodec.decode("/* not JSON */" + json), IllegalArgumentException.class);
        helper.succeed();
    }

    @GameTest
    public void invalidProtectionAndReplacementChoicesCannotRelaxConcealment(GameTestHelper helper) {
        String json = ConfigCodec.encode(BlindstoneConfig.defaults());
        fails(helper, () -> ConfigCodec.decode(json.replace("minecraft:overworld", "minecraft:the_nether")), IllegalArgumentException.class);
        fails(helper, () -> ConfigCodec.decode(json.replace("\"protectedMaxY\": 64", "\"protectedMaxY\": 63")), IllegalArgumentException.class);
        fails(helper, () -> ConfigCodec.decode(json.replace("\"concealAir\": true", "\"concealAir\": false")), IllegalArgumentException.class);
        fails(helper, () -> ConfigCodec.decode(json.replace("minecraft:stone", "minecraft:glowstone")), IllegalArgumentException.class);
        fails(helper, () -> ConfigCodec.decode(json.replace("minecraft:deepslate", "minecraft:chest")), IllegalArgumentException.class);
        fails(helper, () -> ConfigCodec.decode(json.replace("minecraft:stone", "unknown:block")), IllegalArgumentException.class);
        fails(helper, () -> ConfigCodec.decode(json.replace("\"maxMaskBytes\": 67108864", "\"maxMaskBytes\": 1024")).forWorldBounds(-64, 320), IllegalArgumentException.class);
        fails(helper, () -> BlindstoneConfig.defaults().forWorldBounds(64, 320), IllegalArgumentException.class);
        fails(helper, () -> BlindstoneConfig.defaults().forWorldBounds(-64, 48), IllegalArgumentException.class);
        helper.succeed();
    }

    @GameTest
    public void boundedConfigurationRejectsImpossibleAndUnboundedWork(GameTestHelper helper) {
        String json = ConfigCodec.encode(BlindstoneConfig.defaults());
        for (String invalid : List.of(
                json.replace("\"maxViewers\": 128", "\"maxViewers\": 0"),
                json.replace("\"maxQueuedChunksPerViewer\": 128", "\"maxQueuedChunksPerViewer\": 1025"),
                json.replace("\"maxGeometryChecksPerTickGlobal\": 262144", "\"maxGeometryChecksPerTickGlobal\": 8191"),
                json.replace("\"movementDeliveryDeadlineTicks\": 20", "\"movementDeliveryDeadlineTicks\": 0"),
                json.replace("\"movementSafetyRadiusBlocks\": 0.0625", "\"movementSafetyRadiusBlocks\": 3.0"),
                json.replace("\"maxWorkNanosPerTick\": " + BlindstoneConfig.defaults().budgets().maxWorkNanosPerTick(), "\"maxWorkNanosPerTick\": 999999999999999999999999"))) {
            fails(helper, () -> ConfigCodec.decode(invalid), IllegalArgumentException.class);
        }
        helper.succeed();
    }

    @GameTest
    public void configurationReloadValidatesBeforeInvalidationAndPublishesAfterIt(GameTestHelper helper) throws Exception {
        Path directory = Files.createTempDirectory("blindstone-config-test-");
        Path file = directory.resolve("blindstone.json");
        try {
            var manager = new ConfigManager(file);
            var original = manager.loadInitial(helper.getLevel());
            helper.assertTrue(Files.exists(file) && manager.generation() == 1, "Initial configuration must create a complete defaults file");
            String defaults = Files.readString(file);
            Files.writeString(file, defaults.replace("\"concealAir\": true", "\"concealAir\": false"));
            boolean[] invalidated = {false};
            fails(helper, () -> manager.reload(helper.getLevel(), candidate -> invalidated[0] = true), IllegalArgumentException.class);
            helper.assertTrue(!invalidated[0] && manager.current() == original && manager.generation() == 1,
                    "Invalid reload must retain current configuration without invalidating active work");
            Files.writeString(file, defaults.replace("\"protectedMaxY\": 64", "\"protectedMaxY\": 48"));
            var replacement = manager.reload(helper.getLevel(), candidate -> {
                helper.assertTrue(manager.current() == original && candidate.policy().maxY() == 48,
                        "Stale work must invalidate before a fully validated replacement is published");
                invalidated[0] = true;
            });
            helper.assertTrue(invalidated[0] && replacement == manager.current() && manager.generation() == 2,
                    "Complete valid replacement must publish once");
            fails(helper, () -> manager.reload(helper.getLevel(), candidate -> { throw new IllegalStateException("invalidation failed"); }), IllegalStateException.class);
            helper.assertTrue(manager.current() == replacement && manager.generation() == 2,
                    "Failure to invalidate must retain the last published configuration");
            try (var paths = Files.list(directory)) {
                helper.assertTrue(paths.count() == 1, "Atomic defaults creation must clean temporary files");
            }
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(directory); }
        helper.succeed();
    }

    @GameTest
    public void configurationPublicationRejectsBackgroundWorldAccess(GameTestHelper helper) throws Exception {
        Path directory = Files.createTempDirectory("blindstone-config-thread-test-");
        Path file = directory.resolve("blindstone.json");
        try {
            var manager = new ConfigManager(file);
            var failure = new AtomicReference<Throwable>();
            Thread worker = Thread.startVirtualThread(() -> {
                try { manager.loadInitial(helper.getLevel()); }
                catch (Throwable caught) { failure.set(caught); }
            });
            worker.join(5000);
            helper.assertTrue(!worker.isAlive() && failure.get() instanceof IllegalStateException && !Files.exists(file),
                    "Background publication must fail before touching world bounds or writing defaults");
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(directory); }
        helper.succeed();
    }

    @GameTest
    public void diagnosticEvidenceDoesNotPromoteHookLoadingToEnforcementOrTesting(GameTestHelper helper) {
        var diagnostics = diagnostics();
        diagnostics.hookLoaded(BlindstoneDiagnostics.PacketPath.CHUNK_DATA);
        var report = JsonParser.parseString(diagnostics.sanitizedReport(BlindstoneConfig.defaults().forWorldBounds(-64, 320), 1)).getAsJsonObject();
        var evidence = report.getAsJsonObject("coverage").getAsJsonObject("CHUNK_DATA");
        helper.assertTrue(evidence.get("hookLoaded").getAsBoolean() && evidence.get("observed").getAsLong() == 0
                        && evidence.get("filtered").getAsLong() == 0 && !evidence.get("enforcementActive").getAsBoolean()
                        && !evidence.get("automatedTestPassed").getAsBoolean() && !evidence.get("liveTestPassed").getAsBoolean(),
                "Loaded hooks must remain explicitly unobserved, unenforced, and untested");
        helper.assertTrue(report.get("admission").getAsString().equals("DENIED"), "Prototype diagnostics must keep admission denied");
        helper.succeed();
    }

    @GameTest
    public void localDiagnosticReportContainsOnlySanitizedAggregateFields(GameTestHelper helper) throws Exception {
        Path directory = Files.createTempDirectory("blindstone-player-secret-report-test-");
        Path destination = directory.resolve("private-player-identity.json");
        try {
            var diagnostics = diagnostics();
            diagnostics.counts(2, 12, 3);
            diagnostics.failed(BlindstoneDiagnostics.Subsystem.CHUNK_FILTER, BlindstoneDiagnostics.Failure.SERIALIZATION_FAILED);
            diagnostics.increment(BlindstoneDiagnostics.Counter.DROPPED_PACKETS);
            diagnostics.timing(BlindstoneDiagnostics.Timing.PACKET_FILTER, 100);
            var config = BlindstoneConfig.defaults().forWorldBounds(-64, 320);
            diagnostics.writeReport(destination, config, 1);
            String contents = Files.readString(destination);
            var report = JsonParser.parseString(contents).getAsJsonObject();
            helper.assertTrue(report.keySet().equals(Set.of("reportSchema", "activation", "admission", "evidenceNotice", "versions", "configuration",
                    "effectiveMinY", "effectiveMaxYExclusive", "configurationGeneration", "activeViewers", "trackedChunks", "backlog",
                    "coverage", "failureCounts", "counters", "timings")), "Reports must expose only the explicit aggregate schema");
            helper.assertTrue(!contents.contains("player-secret") && !contents.contains("private-player-identity") && !contents.contains("UUID")
                            && !contents.contains("BlockPos") && !contents.contains("seed") && !contents.contains("exception"),
                    "Reports must omit file paths, player identifiers, positions, seeds and arbitrary error text");
            helper.assertTrue(report.getAsJsonObject("failureCounts").getAsJsonObject("CHUNK_FILTER").get("SERIALIZATION_FAILED").getAsInt() == 1,
                    "Sanitized failures must retain typed subsystem and count");
            helper.assertTrue(diagnostics.status(config).contains("backlog=3") && diagnostics.status(config).contains("Y[-64,64)"),
                    "Status must show effective bounds and queue state");
        } finally { Files.deleteIfExists(destination); Files.deleteIfExists(directory); }
        helper.succeed();
    }

    private static BlindstoneDiagnostics diagnostics() {
        return new BlindstoneDiagnostics(new BlindstoneDiagnostics.Versions("0.1.0", "26.3", "0.19.5", "0.163.0+26.3"));
    }

    private static void fails(GameTestHelper helper, ThrowingRunnable action, Class<? extends Throwable> type) {
        Throwable failure = null;
        try { action.run(); } catch (Throwable caught) { failure = caught; }
        helper.assertTrue(type.isInstance(failure), "Operation must fail with " + type.getSimpleName());
    }
    @FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }
}
