package flash.pipeline.ui.variations.state;

import flash.pipeline.ui.variations.CropSpec;
import flash.pipeline.ui.variations.FilterParameterId;
import flash.pipeline.ui.variations.MacroToken;
import flash.pipeline.ui.variations.MacroVariation;
import flash.pipeline.ui.variations.MacroVariationSet;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterId;
import flash.pipeline.ui.variations.ParameterKey;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.ParameterValueList;
import flash.pipeline.ui.wizard.JsonIO;

import java.awt.Rectangle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class VariationStateStore {

    private static final String FILE_NAME = "variations_state.json";

    private final Path stateFile;
    private VariationState activeState;

    public VariationStateStore(Path binFolder) {
        this.stateFile = binFolder == null ? null : binFolder.resolve(FILE_NAME);
    }

    public synchronized Optional<VariationState> load() {
        return load(null);
    }

    public synchronized Optional<VariationState> load(ParameterSweep activeSweep) {
        if (stateFile == null || !Files.isRegularFile(stateFile)) {
            return Optional.empty();
        }
        try {
            String json = new String(Files.readAllBytes(stateFile), StandardCharsets.UTF_8);
            VariationState state = fromJson(JsonIO.parseObject(json))
                    .validatedForResume(activeSweep);
            activeState = state;
            return Optional.of(state);
        } catch (Exception ignored) {
            activeState = null;
            return Optional.empty();
        }
    }

    public synchronized void save(VariationState state) {
        activeState = state;
        writeActive();
    }

    public synchronized void clear() {
        activeState = null;
        if (stateFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(stateFile);
        } catch (IOException ignored) {
            // A stale resume file should not interrupt the preview UI.
        }
    }

    public synchronized void recordCompletion(ParameterCombo combo,
                                              String labelCacheKey,
                                              int nObjects,
                                              long durationMs) {
        if (activeState == null) {
            return;
        }
        String comboId = VariationState.comboIdFor(activeState.sweep(), combo);
        if (comboId == null) {
            return;
        }
        VariationState.CompletedCell cell = new VariationState.CompletedCell(
                comboId, labelCacheKey, nObjects, durationMs);
        activeState = activeState.withCompletion(cell, Instant.now().toString());
        writeActive();
    }

    public synchronized void recordCompletion(ParameterSweep sweep,
                                              ParameterCombo combo,
                                              String labelCacheKey,
                                              int nObjects,
                                              long durationMs) {
        if (activeState == null && sweep != null) {
            activeState = VariationState.started(sweep);
        }
        recordCompletion(combo, labelCacheKey, nObjects, durationMs);
    }

    public synchronized VariationState activeStateForTest() {
        return activeState;
    }

    public Path stateFileForTest() {
        return stateFile;
    }

    private void writeActive() {
        if (stateFile == null || activeState == null) {
            return;
        }
        try {
            Path parent = stateFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = stateFile.resolveSibling(stateFile.getFileName().toString() + ".tmp");
            Files.write(temp, (JsonIO.write(toJsonObject(activeState)) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            // Retry/backoff move, then in-place rewrite if the destination stays
            // locked against rename (Windows + Dropbox/OneDrive). Safe: small JSON.
            flash.pipeline.io.IoUtils.commitReplacingSmallFile(temp, stateFile);
        } catch (IOException ignored) {
            // Resume state is best-effort and must not break parameter previews.
        }
    }

    private static Map<String, Object> toJsonObject(VariationState state) {
        LinkedHashMap<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("version", Integer.valueOf(VariationState.CURRENT_VERSION));
        root.put("image_hash", state.imageHash());
        root.put("channel", state.channel());
        root.put("method", state.methodLabel());
        root.put("cache_namespace", state.sweep().cacheNamespace());
        root.put("sweep", sweepObject(state.sweep()));
        if (state.sweep().hasMacroVariationSet()) {
            root.put("macro_variations",
                    state.sweep().macroVariations().toCanonicalObject());
        }
        root.put("crop", cropObject(state.sweep().cropSpec()));
        root.put("completed", completedList(state.completed()));
        root.put("started_at", state.startedAt());
        root.put("updated_at", state.updatedAt());
        return root;
    }

    private static Map<String, Object> sweepObject(ParameterSweep sweep) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        for (Map.Entry<ParameterKey, ParameterValueList> entry
                : sweep.valueLists().entrySet()) {
            out.put(entry.getKey().stableKey(),
                    new ArrayList<Object>(entry.getValue().values()));
        }
        return out;
    }

    private static Map<String, Object> cropObject(CropSpec crop) {
        CropSpec safeCrop = crop == null ? CropSpec.full() : crop;
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("mode", safeCrop.mode().name());
        Rectangle bounds = safeCrop.bounds();
        if (bounds == null) {
            out.put("bounds", null);
        } else {
            LinkedHashMap<String, Object> boundsJson =
                    new LinkedHashMap<String, Object>();
            boundsJson.put("x", Integer.valueOf(bounds.x));
            boundsJson.put("y", Integer.valueOf(bounds.y));
            boundsJson.put("width", Integer.valueOf(bounds.width));
            boundsJson.put("height", Integer.valueOf(bounds.height));
            out.put("bounds", boundsJson);
        }
        return out;
    }

    private static List<Object> completedList(List<VariationState.CompletedCell> completed) {
        List<Object> out = new ArrayList<Object>();
        if (completed == null) {
            return out;
        }
        for (int i = 0; i < completed.size(); i++) {
            VariationState.CompletedCell cell = completed.get(i);
            LinkedHashMap<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("combo_id", cell.comboId());
            row.put("label_cache_key", cell.labelCacheKey());
            row.put("n_objects", Integer.valueOf(cell.nObjects()));
            row.put("duration_ms", Long.valueOf(cell.durationMs()));
            out.add(row);
        }
        return out;
    }

    private static VariationState fromJson(Map<String, Object> root) throws IOException {
        int version = JsonIO.intValue(root.get("version"), -1);
        if (version != VariationState.CURRENT_VERSION) {
            throw new IOException("Unsupported variation state version.");
        }
        String imageHash = requiredString(root, "image_hash");
        String channel = requiredString(root, "channel");
        ParameterSweep.Method method = parseMethod(requiredString(root, "method"));
        Map<ParameterKey, ParameterValueList> values =
                parseSweep(JsonIO.asObject(root.get("sweep")));
        CropSpec crop = parseCrop(JsonIO.asObject(root.get("crop")));
        String cacheNamespace = JsonIO.stringValue(root.get("cache_namespace"));
        MacroVariationSet macroVariations =
                parseMacroVariations(JsonIO.asObject(root.get("macro_variations")));
        ParameterSweep sweep = new ParameterSweep(method, values, crop, channel, imageHash,
                cacheNamespace == null ? "" : cacheNamespace,
                macroVariations);
        List<VariationState.CompletedCell> completed =
                parseCompleted(JsonIO.asList(root.get("completed")));
        return new VariationState(version, sweep, completed,
                requiredString(root, "started_at"),
                requiredString(root, "updated_at"));
    }

    private static Map<ParameterKey, ParameterValueList> parseSweep(
            Map<String, Object> sweepJson) throws IOException {
        if (sweepJson == null || sweepJson.isEmpty()) {
            throw new IOException("Variation sweep is missing.");
        }
        LinkedHashMap<ParameterKey, ParameterValueList> values =
                new LinkedHashMap<ParameterKey, ParameterValueList>();
        for (Map.Entry<String, Object> entry : sweepJson.entrySet()) {
            ParameterKey key = parameterKey(entry.getKey());
            if (key == null) {
                continue;
            }
            List<Object> rawValues = JsonIO.asList(entry.getValue());
            if (rawValues.isEmpty()) {
                throw new IOException("Variation sweep has an empty value list.");
            }
            values.put(key, new ParameterValueList(rawValues));
        }
        if (values.isEmpty()) {
            throw new IOException("Variation sweep has no known parameters.");
        }
        return values;
    }

    private static MacroVariationSet parseMacroVariations(
            Map<String, Object> macroJson) {
        if (macroJson == null || macroJson.isEmpty()) {
            return null;
        }
        List<Object> rows = JsonIO.asList(macroJson.get("variations"));
        List<MacroVariation> variations = new ArrayList<MacroVariation>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = JsonIO.asObject(rows.get(i));
            String token = JsonIO.stringValue(row.get("token"));
            if (token == null || token.trim().isEmpty()) {
                continue;
            }
            if (!MacroToken.NONE_VALUE.equals(token.trim())
                    && !MacroToken.isMacroToken(token)) {
                continue;
            }
            variations.add(MacroVariation.identityOnly(
                    token,
                    JsonIO.stringValue(row.get("displayName")),
                    JsonIO.stringValue(row.get("sourceKind")),
                    JsonIO.stringValue(row.get("sourceName")),
                    JsonIO.stringValue(row.get("normalizedScriptHash"))));
        }
        return new MacroVariationSet(variations);
    }

    private static CropSpec parseCrop(Map<String, Object> cropJson) throws IOException {
        if (cropJson == null || cropJson.isEmpty()) {
            return CropSpec.full();
        }
        String modeText = JsonIO.stringValue(cropJson.get("mode"));
        CropSpec.Mode mode;
        try {
            mode = CropSpec.Mode.valueOf(modeText == null ? "" : modeText.trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("Unknown variation crop mode.", e);
        }
        if (mode == CropSpec.Mode.FULL) {
            return CropSpec.full();
        }
        if (mode == CropSpec.Mode.CENTRE_256) {
            return CropSpec.centre256();
        }
        Map<String, Object> bounds = JsonIO.asObject(cropJson.get("bounds"));
        if (bounds.isEmpty()) {
            throw new IOException("Custom crop bounds are missing.");
        }
        return CropSpec.custom(new Rectangle(
                JsonIO.intValue(bounds.get("x"), 0),
                JsonIO.intValue(bounds.get("y"), 0),
                JsonIO.intValue(bounds.get("width"), 0),
                JsonIO.intValue(bounds.get("height"), 0)));
    }

    private static List<VariationState.CompletedCell> parseCompleted(List<Object> rows) {
        List<VariationState.CompletedCell> out =
                new ArrayList<VariationState.CompletedCell>();
        if (rows == null) {
            return out;
        }
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = JsonIO.asObject(rows.get(i));
            String comboId = JsonIO.stringValue(row.get("combo_id"));
            String cacheKey = JsonIO.stringValue(row.get("label_cache_key"));
            if (comboId == null || comboId.trim().isEmpty()
                    || cacheKey == null || cacheKey.trim().isEmpty()) {
                continue;
            }
            out.add(new VariationState.CompletedCell(comboId,
                    cacheKey,
                    JsonIO.intValue(row.get("n_objects"), 0),
                    longValue(row.get("duration_ms"), 0L)));
        }
        return out;
    }

    private static ParameterSweep.Method parseMethod(String value) throws IOException {
        for (ParameterSweep.Method method : ParameterSweep.Method.values()) {
            if (method.label().equals(value) || method.name().equals(value)) {
                return method;
            }
        }
        throw new IOException("Unknown variation method.");
    }

    private static String requiredString(Map<String, Object> root, String key)
            throws IOException {
        String value = JsonIO.stringValue(root.get(key));
        if (value == null) {
            throw new IOException("Missing required variation state key: " + key);
        }
        return value;
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static ParameterKey parameterKey(String key) {
        if (key == null) {
            return null;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("filter.")) {
            return FilterParameterId.parseStableKey(key);
        }
        return ParameterId.fromStableKey(key);
    }
}
