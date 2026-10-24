package com.evanchubbuck.blindstone.config;

import com.evanchubbuck.blindstone.ConcealmentPolicy;
import java.util.List;

/** Immutable, fully validated configuration. Numeric defaults are private-alpha budgets, not measured SMP capacity. */
public record BlindstoneConfig(int schemaVersion, List<String> dimensions, int protectedMaxY,
        boolean concealAir, boolean protectNonPlayerEntities, String soundPolicy,
        Replacements replacements, int revealRangeBlocks, int refreshIntervalTicks,
        double movementSafetyRadiusBlocks, Budgets budgets, Ore ore, Ore netherOre) {
    public BlindstoneConfig(int schemaVersion, List<String> dimensions, int protectedMaxY,
            boolean concealAir, boolean protectNonPlayerEntities, String soundPolicy, Replacements replacements,
            int revealRangeBlocks, int refreshIntervalTicks, double movementSafetyRadiusBlocks, Budgets budgets) {
        this(schemaVersion, dimensions, protectedMaxY, concealAir, protectNonPlayerEntities, soundPolicy, replacements,
                revealRangeBlocks, refreshIntervalTicks, movementSafetyRadiusBlocks, budgets, Ore.defaults(), Ore.netherDefaults());
    }
    public static final String OVERWORLD = "minecraft:overworld";

    public BlindstoneConfig {
        if (schemaVersion != 1 && schemaVersion != 2) throw invalid("schemaVersion must be 1 or 2");
        dimensions = List.copyOf(dimensions);
        if (!dimensions.equals(List.of(OVERWORLD))) throw invalid("dimensions must contain only minecraft:overworld");
        if ((protectedMaxY & 15) != 0 || protectedMaxY < -2032 || protectedMaxY > 2048)
            throw invalid("protectedMaxY must be section-aligned and inside [-2032,2048]");
        if (!concealAir) throw invalid("concealAir is required; disabling protection is not a configuration fallback");
        if (!protectNonPlayerEntities) throw invalid("protectNonPlayerEntities is required for the normal preset");
        if (!"preserve_audible".equals(soundPolicy)) throw invalid("soundPolicy must be preserve_audible");
        if (ore == null || netherOre == null) throw invalid("ore is required");
        if (replacements == null || budgets == null) throw invalid("replacements and budgets are required");
        range("revealRangeBlocks", revealRangeBlocks, 4, 16);
        range("refreshIntervalTicks", refreshIntervalTicks, 1, 20);
        if (movementSafetyRadiusBlocks != 0.0625)
            throw invalid("movementSafetyRadiusBlocks must be exactly 0.0625 for the tested expanded-body envelope");
        if (budgets.movementDeliveryDeadlineTicks() < refreshIntervalTicks)
            throw invalid("movementDeliveryDeadlineTicks must be at least refreshIntervalTicks");
    }

    public static BlindstoneConfig defaults() {
        return new BlindstoneConfig(2, List.of(OVERWORLD), 320, true, true, "preserve_audible",
                new Replacements("minecraft:stone", "minecraft:deepslate"), 16, 1, 0.0625,
                new Budgets(128, 1024, 8192, 128, 8192, 262144, 65536, 262144, 5000000, 67108864, 20));
    }

    /** The lower bound always follows the actual world's minimum; callers must capture it on its owning thread. */
    public EffectiveConfig forWorldBounds(int worldMinY, int worldMaxY) {
        if ((worldMinY & 15) != 0 || (worldMaxY & 15) != 0 || worldMinY >= worldMaxY)
            throw invalid("World bounds must be ordered and section-aligned");
        if (protectedMaxY <= worldMinY || protectedMaxY > worldMaxY)
            throw invalid("protectedMaxY must exceed the world minimum and not exceed the world maximum");
        var policy = new ConcealmentPolicy(worldMinY, protectedMaxY, schemaVersion == 2 ? 64 : protectedMaxY, ore.resolve());
        long maskBytesPerChunk = ((long) protectedMaxY - worldMinY) / 16 * 1024;
        if (budgets.maxMaskBytes() < maskBytesPerChunk)
            throw invalid("maxMaskBytes cannot hold one tracked chunk's delivered and pending masks");
        return new EffectiveConfig(this, policy, maskBytesPerChunk);
    }

    public ConcealmentPolicy policyFor(net.minecraft.server.level.ServerLevel level) {
        if (level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD))
            return forWorldBounds(level.getMinY(), level.getMaxY() + 1).policy();
        if (level.dimension().equals(net.minecraft.world.level.Level.NETHER))
            return new ConcealmentPolicy(level.getMinY(), level.getMaxY() + 1, level.getMinY(), netherOre.resolve(), false);
        throw invalid("Unsupported dimension");
    }

    /** Schema-2 files without this object migrate to these explicit defaults. */
    public record Ore(boolean enabled, int maxBlockHeight, int updateRadius, List<String> targets) {
        public Ore {
            targets = List.copyOf(targets);
            range("ore.maxBlockHeight", maxBlockHeight, -2032, 2048);
            range("ore.updateRadius", updateRadius, 0, 4);
            if (targets.isEmpty() || targets.size() > 64) throw invalid("ore.targets must contain 1–64 rules");
            for (String rule : targets) {
                if (!rule.matches("!?[#]?[a-z0-9_.-]+:[a-z0-9_./-]+")) throw invalid("Malformed ore target rule: " + rule);
            }
        }
        public static Ore defaults() {
            return new Ore(true, 256, 2, List.of("#c:ores", "#minecraft:coal_ores", "#minecraft:copper_ores",
                    "#minecraft:iron_ores", "#minecraft:gold_ores", "#minecraft:redstone_ores",
                    "#minecraft:lapis_ores", "#minecraft:diamond_ores", "#minecraft:emerald_ores",
                    "minecraft:raw_copper_block", "minecraft:raw_iron_block", "minecraft:raw_gold_block"));
        }
        public static Ore netherDefaults() {
            return new Ore(true, 128, 2, List.of("minecraft:ancient_debris", "minecraft:nether_quartz_ore",
                    "minecraft:nether_gold_ore", "minecraft:gilded_blackstone", "minecraft:gold_block"));
        }
        public com.evanchubbuck.blindstone.OrePolicy resolve() {
            var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
            var result = new java.util.HashSet<net.minecraft.world.level.block.Block>();
            for (String rule : targets) {
                boolean remove = rule.startsWith("!");
                String value = remove ? rule.substring(1) : rule;
                var matches = new java.util.HashSet<net.minecraft.world.level.block.Block>();
                if (value.startsWith("#")) {
                    var id = net.minecraft.resources.Identifier.parse(value.substring(1));
                    var tag = registry.getTags().filter(t -> t.key().location().equals(id)).findFirst()
                            .orElseThrow(() -> invalid("Unknown ore tag: " + value));
                    tag.forEach(holder -> matches.add(holder.value()));
                } else {
                    matches.add(registry.getOptional(net.minecraft.resources.Identifier.parse(value))
                            .orElseThrow(() -> invalid("Unknown ore block: " + value)));
                }
                for (var block : matches) if (block.defaultBlockState().isAir()) throw invalid("Air is not an ore target");
                if (remove) result.removeAll(matches); else result.addAll(matches);
            }
            return enabled ? new com.evanchubbuck.blindstone.OrePolicy(maxBlockHeight, result)
                    : com.evanchubbuck.blindstone.OrePolicy.disabled();
        }
    }

    /** Custom replacements remain unavailable until runtime block-property validation is integrated. */
    public record Replacements(String aboveZero, String belowZero) {
        public Replacements {
            if (!"minecraft:stone".equals(aboveZero) || !"minecraft:deepslate".equals(belowZero))
                throw invalid("Only validated fixed replacements minecraft:stone / minecraft:deepslate are supported");
        }
    }

    public record Budgets(int maxViewers, int maxTrackedChunksPerViewer, int maxGlobalTrackedChunks,
            int maxQueuedChunksPerViewer, int maxGlobalQueuedChunks, int maxSnapshotBlocksPerJob,
            int maxGeometryChecksPerTickPerViewer, int maxGeometryChecksPerTickGlobal,
            long maxWorkNanosPerTick, long maxMaskBytes, int movementDeliveryDeadlineTicks) {
        public Budgets {
            range("maxViewers", maxViewers, 1, 1024);
            range("maxTrackedChunksPerViewer", maxTrackedChunksPerViewer, 1, 4096);
            range("maxGlobalTrackedChunks", maxGlobalTrackedChunks, 1, 65536);
            range("maxQueuedChunksPerViewer", maxQueuedChunksPerViewer, 1, maxTrackedChunksPerViewer);
            range("maxGlobalQueuedChunks", maxGlobalQueuedChunks, 1, maxGlobalTrackedChunks);
            range("maxSnapshotBlocksPerJob", maxSnapshotBlocksPerJob, 4096, 2097152);
            range("maxGeometryChecksPerTickPerViewer", maxGeometryChecksPerTickPerViewer, 1, 1048576);
            range("maxGeometryChecksPerTickGlobal", maxGeometryChecksPerTickGlobal, maxGeometryChecksPerTickPerViewer, 16777216);
            range("maxWorkNanosPerTick", maxWorkNanosPerTick, 100000, 50000000);
            range("maxMaskBytes", maxMaskBytes, 1024, 1073741824);
            range("movementDeliveryDeadlineTicks", movementDeliveryDeadlineTicks, 1, 100);
        }
    }

    public record EffectiveConfig(BlindstoneConfig settings, ConcealmentPolicy policy, long maskBytesPerTrackedChunk) { }

    private static void range(String key, long value, long min, long max) {
        if (value < min || value > max) throw invalid(key + " must be inside [" + min + "," + max + "]");
    }

    private static IllegalArgumentException invalid(String reason) { return new IllegalArgumentException("Invalid Blindstone configuration: " + reason); }
}
