package com.evanchubbuck.blindstone.config;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Set;

/** Strict schema: unknown, duplicate, absent, mistyped, and non-integral fields are errors. */
public final class ConfigCodec {
    private ConfigCodec() { }

    public static BlindstoneConfig decode(String json) {
        if (json.length() > 65536) throw invalid("Configuration exceeds 64 KiB");
        try (var reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            JsonObject root = object(read(reader, 0), Set.of("schemaVersion", "dimensions", "protectedMaxY", "concealAir",
                    "protectNonPlayerEntities", "soundPolicy", "replacements", "revealRangeBlocks", "refreshIntervalTicks",
                    "movementSafetyRadiusBlocks", "budgets"));
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid("Trailing data");
            var dimensions = new ArrayList<String>();
            if (!(root.get("dimensions") instanceof JsonArray array)) throw invalid("dimensions must be an array");
            for (var dimension : array) dimensions.add(string(dimension));
            var replacements = object(root.get("replacements"), Set.of("aboveZero", "belowZero"));
            var budget = object(root.get("budgets"), Set.of("maxViewers", "maxTrackedChunksPerViewer", "maxGlobalTrackedChunks",
                    "maxQueuedChunksPerViewer", "maxGlobalQueuedChunks", "maxSnapshotBlocksPerJob", "maxGeometryChecksPerTickPerViewer",
                    "maxGeometryChecksPerTickGlobal", "maxWorkNanosPerTick", "maxMaskBytes", "movementDeliveryDeadlineTicks"));
            return new BlindstoneConfig(integer(root, "schemaVersion"), dimensions, integer(root, "protectedMaxY"),
                    bool(root, "concealAir"), bool(root, "protectNonPlayerEntities"), string(root.get("soundPolicy")),
                    new BlindstoneConfig.Replacements(string(replacements.get("aboveZero")), string(replacements.get("belowZero"))),
                    integer(root, "revealRangeBlocks"), integer(root, "refreshIntervalTicks"), decimal(root, "movementSafetyRadiusBlocks"),
                    new BlindstoneConfig.Budgets(integer(budget, "maxViewers"), integer(budget, "maxTrackedChunksPerViewer"),
                            integer(budget, "maxGlobalTrackedChunks"), integer(budget, "maxQueuedChunksPerViewer"), integer(budget, "maxGlobalQueuedChunks"),
                            integer(budget, "maxSnapshotBlocksPerJob"), integer(budget, "maxGeometryChecksPerTickPerViewer"),
                            integer(budget, "maxGeometryChecksPerTickGlobal"), number(budget, "maxWorkNanosPerTick").longValueExact(),
                            number(budget, "maxMaskBytes").longValueExact(), integer(budget, "movementDeliveryDeadlineTicks")));
        } catch (IOException | ArithmeticException failure) {
            throw invalid("Malformed JSON or numeric value");
        }
    }

    public static String encode(BlindstoneConfig config) {
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(config) + "\n";
    }

    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > 4) throw invalid("JSON nesting exceeds schema");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject result = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (result.has(name)) throw invalid("Duplicate configuration field");
                    result.add(name, read(reader, depth + 1));
                }
                reader.endObject();
                yield result;
            }
            case BEGIN_ARRAY -> {
                JsonArray result = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) {
                    if (result.size() >= 16) throw invalid("Configuration array exceeds schema");
                    result.add(read(reader, depth + 1));
                }
                reader.endArray();
                yield result;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            default -> throw invalid("Unexpected JSON value");
        };
    }

    private static JsonObject object(JsonElement value, Set<String> required) {
        if (!(value instanceof JsonObject object) || !object.keySet().equals(required))
            throw invalid("Configuration fields must exactly match schema version 1");
        return object;
    }
    private static String string(JsonElement value) {
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isString()) throw invalid("Expected a string");
        return primitive.getAsString();
    }
    private static boolean bool(JsonObject object, String field) {
        if (!(object.get(field) instanceof JsonPrimitive primitive) || !primitive.isBoolean()) throw invalid("Expected a boolean");
        return primitive.getAsBoolean();
    }
    private static BigDecimal number(JsonObject object, String field) {
        if (!(object.get(field) instanceof JsonPrimitive primitive) || !primitive.isNumber()) throw invalid("Expected a number");
        return primitive.getAsBigDecimal();
    }
    private static int integer(JsonObject object, String field) { return number(object, field).intValueExact(); }
    private static double decimal(JsonObject object, String field) { return number(object, field).doubleValue(); }
    private static IllegalArgumentException invalid(String reason) { return new IllegalArgumentException("Invalid Blindstone configuration: " + reason); }
}
