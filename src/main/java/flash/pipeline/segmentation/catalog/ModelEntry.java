package flash.pipeline.segmentation.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable description of a segmentation model known to a FLASH project.
 */
public final class ModelEntry {
    public enum Engine {
        STARDIST("stardist"),
        CELLPOSE("cellpose"),
        SMILE_RF("smile_rf");

        private final String jsonValue;

        Engine(String jsonValue) {
            this.jsonValue = jsonValue;
        }

        public String jsonValue() {
            return jsonValue;
        }

        public static Engine fromJson(String value) {
            if (value != null) {
                String normalized = value.trim().toLowerCase(Locale.ROOT);
                for (Engine engine : values()) {
                    if (engine.jsonValue.equals(normalized)) {
                        return engine;
                    }
                }
            }
            throw new IllegalArgumentException("Unknown model engine: " + value);
        }
    }

    public enum Source {
        STOCK_RESOURCE("stock_resource"),
        STOCK_BUILTIN("stock_builtin"),
        USER_IMPORTED("user_imported"),
        USER_TRAINED("user_trained");

        private final String jsonValue;

        Source(String jsonValue) {
            this.jsonValue = jsonValue;
        }

        public String jsonValue() {
            return jsonValue;
        }

        public static Source fromJson(String value) {
            if (value != null) {
                String normalized = value.trim().toLowerCase(Locale.ROOT);
                for (Source source : values()) {
                    if (source.jsonValue.equals(normalized)) {
                        return source;
                    }
                }
            }
            throw new IllegalArgumentException("Unknown model source: " + value);
        }
    }

    public final String modelKey;
    public final String name;
    public final String description;
    public final Engine engine;
    public final Source source;
    public final Optional<String> filePath;
    public final Optional<String> resourcePath;
    public final Optional<String> pretrainedModel;
    public final Optional<String> fijiModelChoice;
    public final Optional<String> base;
    public final Map<String, Object> defaults;
    public final Map<String, Object> metadata;
    public final boolean supportsSecondChannel;

    public ModelEntry(String modelKey,
                      String name,
                      String description,
                      Engine engine,
                      Source source,
                      String filePath,
                      String resourcePath,
                      String pretrainedModel,
                      String fijiModelChoice,
                      String base,
                      Map<String, Object> defaults,
                      Map<String, Object> metadata,
                      boolean supportsSecondChannel) {
        this.modelKey = modelKey;
        this.name = name;
        this.description = description;
        this.engine = engine;
        this.source = source;
        this.filePath = optionalString(filePath);
        this.resourcePath = optionalString(resourcePath);
        this.pretrainedModel = optionalString(pretrainedModel);
        this.fijiModelChoice = optionalString(fijiModelChoice);
        this.base = optionalString(base);
        this.defaults = freezeMap(defaults);
        this.metadata = freezeMap(metadata);
        this.supportsSecondChannel = supportsSecondChannel;
    }

    public boolean isStock() {
        return source == Source.STOCK_RESOURCE || source == Source.STOCK_BUILTIN;
    }

    public ModelEntry withFilePath(String newFilePath) {
        return new ModelEntry(modelKey, name, description, engine, source,
                newFilePath, value(resourcePath), value(pretrainedModel),
                value(fijiModelChoice), value(base), defaults, metadata,
                supportsSecondChannel);
    }

    public Map<String, Object> toJsonObject() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        putIfPresent(out, "modelKey", modelKey);
        putIfPresent(out, "name", name);
        putIfPresent(out, "description", description);
        if (engine != null) out.put("engine", engine.jsonValue());
        if (source != null) out.put("source", source.jsonValue());
        putIfPresent(out, "filePath", value(filePath));
        putIfPresent(out, "resourcePath", value(resourcePath));
        putIfPresent(out, "pretrainedModel", value(pretrainedModel));
        putIfPresent(out, "fijiModelChoice", value(fijiModelChoice));
        putIfPresent(out, "base", value(base));
        if (!defaults.isEmpty()) out.put("defaults", defaults);
        if (!metadata.isEmpty()) out.put("metadata", metadata);
        out.put("supportsSecondChannel", Boolean.valueOf(supportsSecondChannel));
        return out;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ModelEntry)) return false;
        ModelEntry that = (ModelEntry) other;
        return Objects.equals(modelKey, that.modelKey)
                && Objects.equals(name, that.name)
                && Objects.equals(description, that.description)
                && engine == that.engine
                && source == that.source
                && Objects.equals(filePath, that.filePath)
                && Objects.equals(resourcePath, that.resourcePath)
                && Objects.equals(pretrainedModel, that.pretrainedModel)
                && Objects.equals(fijiModelChoice, that.fijiModelChoice)
                && Objects.equals(base, that.base)
                && Objects.equals(defaults, that.defaults)
                && Objects.equals(metadata, that.metadata)
                && supportsSecondChannel == that.supportsSecondChannel;
    }

    @Override
    public int hashCode() {
        return Objects.hash(modelKey, name, description, engine, source, filePath,
                resourcePath, pretrainedModel, fijiModelChoice, base, defaults,
                metadata, Boolean.valueOf(supportsSecondChannel));
    }

    @Override
    public String toString() {
        return "ModelEntry{" + modelKey + ", " + engine + ", " + source + "}";
    }

    private static Optional<String> optionalString(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    private static String value(Optional<String> value) {
        return value.isPresent() ? value.get() : null;
    }

    private static void putIfPresent(Map<String, Object> out, String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            out.put(key, value);
        }
    }

    private static Map<String, Object> freezeMap(Map<String, Object> input) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (input != null) {
            for (Map.Entry<String, Object> entry : input.entrySet()) {
                if (entry.getKey() != null) {
                    out.put(entry.getKey(), freezeValue(entry.getValue()));
                }
            }
        }
        return Collections.unmodifiableMap(out);
    }

    @SuppressWarnings("unchecked")
    private static Object freezeValue(Object value) {
        if (value instanceof Map) {
            LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() != null) {
                    out.put(String.valueOf(entry.getKey()), freezeValue(entry.getValue()));
                }
            }
            return Collections.unmodifiableMap(out);
        }
        if (value instanceof List) {
            List<Object> out = new ArrayList<Object>();
            for (Object item : (List<Object>) value) {
                out.add(freezeValue(item));
            }
            return Collections.unmodifiableList(out);
        }
        return value;
    }
}
