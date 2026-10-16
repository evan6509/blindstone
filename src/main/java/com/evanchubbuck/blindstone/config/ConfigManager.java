package com.evanchubbuck.blindstone.config;

import com.evanchubbuck.blindstone.config.BlindstoneConfig.EffectiveConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;

/** Server-thread publication with complete validation and invalidation before replacing the active configuration. */
public final class ConfigManager {
    private final Path file;
    private volatile EffectiveConfig current;
    private long generation;

    public ConfigManager(Path file) { this.file = Objects.requireNonNull(file).toAbsolutePath(); }

    public EffectiveConfig loadInitial(ServerLevel overworld) throws IOException {
        checkThread(overworld);
        if (current != null) throw new IllegalStateException("Configuration is already initialized");
        if (!Files.exists(file)) saveDefaultsAtomically();
        var candidate = read(overworld);
        current = candidate;
        generation++;
        return candidate;
    }

    public EffectiveConfig reload(ServerLevel overworld, Consumer<EffectiveConfig> invalidateBeforePublish) throws IOException {
        checkThread(overworld);
        current();
        var candidate = read(overworld);
        Objects.requireNonNull(invalidateBeforePublish).accept(candidate);
        current = candidate;
        generation++;
        return candidate;
    }

    public EffectiveConfig current() {
        var value = current;
        if (value == null) throw new IllegalStateException("Blindstone configuration has not initialized");
        return value;
    }

    public long generation() { return generation; }

    private EffectiveConfig read(ServerLevel overworld) throws IOException {
        if (Files.size(file) > 65536) throw new IllegalArgumentException("Invalid Blindstone configuration: file exceeds 64 KiB");
        return ConfigCodec.decode(Files.readString(file, StandardCharsets.UTF_8))
                .forWorldBounds(overworld.getMinY(), overworld.getMinY() + overworld.getHeight());
    }

    private void saveDefaultsAtomically() throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".blindstone-", ".json.tmp");
        try {
            Files.writeString(temporary, ConfigCodec.encode(BlindstoneConfig.defaults()), StandardCharsets.UTF_8);
            // Failure to support an atomic move is a startup error, never a partial-file fallback.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void checkThread(ServerLevel overworld) {
        if (!overworld.getServer().isSameThread()) throw new IllegalStateException("Configuration publication must run on the owning server thread");
        if (!overworld.dimension().equals(net.minecraft.world.level.Level.OVERWORLD))
            throw new IllegalArgumentException("Blindstone configuration must use the Overworld bounds");
    }
}
