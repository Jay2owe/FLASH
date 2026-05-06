package flash.pipeline.analyses.wizard;

import flash.pipeline.ui.wizard.JsonIO;
import flash.pipeline.ui.wizard.Preset;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persisted Split & Merge main-dialog settings.
 */
public final class SplitMergePreset implements Preset<SplitMergePreset> {

    public static final String CURRENT_LIBRARY_VERSION = "1";

    private final String name;
    private final String description;
    private final String libraryVersion;
    private final List<String> processMethods;
    private final List<String> customMinMax;
    private final List<Double> saturations;
    private final boolean createMerge;
    private final boolean saveOmeTiff;
    private final String additionalMergeSpec;
    private final boolean subtractBackground;
    private final int backgroundIndex;
    private final List<Boolean> subtractFromChannels;

    public SplitMergePreset(String name,
                            String description,
                            List<String> processMethods,
                            List<String> customMinMax,
                            List<Double> saturations,
                            boolean createMerge,
                            boolean saveOmeTiff,
                            String additionalMergeSpec,
                            boolean subtractBackground,
                            int backgroundIndex,
                            List<Boolean> subtractFromChannels) {
        this.name = requireText("name", name);
        this.description = emptyToNull(description);
        this.libraryVersion = CURRENT_LIBRARY_VERSION;
        this.processMethods = immutableStrings(processMethods);
        this.customMinMax = immutableStrings(customMinMax);
        this.saturations = immutableDoubles(saturations);
        this.createMerge = createMerge;
        this.saveOmeTiff = saveOmeTiff;
        this.additionalMergeSpec = additionalMergeSpec == null ? "" : additionalMergeSpec.trim();
        this.subtractBackground = subtractBackground;
        this.backgroundIndex = backgroundIndex;
        this.subtractFromChannels = immutableBooleans(subtractFromChannels);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String getLibraryVersion() {
        return libraryVersion;
    }

    public List<String> getProcessMethods() {
        return processMethods;
    }

    public List<String> getCustomMinMax() {
        return customMinMax;
    }

    public List<Double> getSaturations() {
        return saturations;
    }

    public boolean isCreateMerge() {
        return createMerge;
    }

    public boolean isSaveOmeTiff() {
        return saveOmeTiff;
    }

    public String getAdditionalMergeSpec() {
        return additionalMergeSpec;
    }

    public boolean isSubtractBackground() {
        return subtractBackground;
    }

    /**
     * Zero-based background index. A negative value means "use auto-detection if available".
     */
    public int getBackgroundIndex() {
        return backgroundIndex;
    }

    public List<Boolean> getSubtractFromChannels() {
        return subtractFromChannels;
    }

    public String methodForChannel(int index, String fallback) {
        return stringForChannel(processMethods, index, fallback);
    }

    public String customMinMaxForChannel(int index, String fallback) {
        return stringForChannel(customMinMax, index, fallback);
    }

    public double saturationForChannel(int index, double fallback) {
        if (saturations.isEmpty()) {
            return fallback;
        }
        int chosen = saturations.size() == 1 ? 0 : index;
        return chosen >= 0 && chosen < saturations.size()
                ? saturations.get(chosen).doubleValue()
                : fallback;
    }

    public boolean subtractFromChannel(int index, boolean fallback) {
        if (subtractFromChannels.isEmpty()) {
            return fallback;
        }
        int chosen = subtractFromChannels.size() == 1 ? 0 : index;
        return chosen >= 0 && chosen < subtractFromChannels.size()
                ? subtractFromChannels.get(chosen).booleanValue()
                : fallback;
    }

    @Override
    public SplitMergePreset getPayload() {
        return this;
    }

    @Override
    public Map<String, Object> toJsonObject() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("name", name);
        if (description != null) {
            root.put("description", description);
        }
        root.put("libraryVersion", libraryVersion);
        root.put("processMethods", new ArrayList<String>(processMethods));
        root.put("customMinMax", new ArrayList<String>(customMinMax));
        root.put("saturations", new ArrayList<Double>(saturations));
        root.put("createMerge", Boolean.valueOf(createMerge));
        root.put("saveOmeTiff", Boolean.valueOf(saveOmeTiff));
        root.put("additionalMergeSpec", additionalMergeSpec);
        root.put("subtractBackground", Boolean.valueOf(subtractBackground));
        root.put("backgroundIndex", Integer.valueOf(backgroundIndex));
        root.put("subtractFromChannels", new ArrayList<Boolean>(subtractFromChannels));
        return root;
    }

    public String toJson() {
        return JsonIO.write(toJsonObject());
    }

    public static SplitMergePreset fromJson(String json) throws IOException {
        return fromJsonObject(JsonIO.parseObject(json));
    }

    public static SplitMergePreset fromJsonObject(Map<String, Object> root) throws IOException {
        if (root == null) {
            throw new IOException("Preset JSON object is required.");
        }
        return new SplitMergePreset(
                stringOr(root.get("name"), "Split Merge Preset"),
                JsonIO.stringValue(root.get("description")),
                strings(root.get("processMethods")),
                strings(root.get("customMinMax")),
                doubles(root.get("saturations")),
                JsonIO.booleanValue(root.get("createMerge"), true),
                JsonIO.booleanValue(root.get("saveOmeTiff"), false),
                stringOr(root.get("additionalMergeSpec"), ""),
                JsonIO.booleanValue(root.get("subtractBackground"), false),
                JsonIO.intValue(root.get("backgroundIndex"), -1),
                booleans(root.get("subtractFromChannels")));
    }

    private static String stringForChannel(List<String> values, int index, String fallback) {
        if (values.isEmpty()) {
            return fallback;
        }
        int chosen = values.size() == 1 ? 0 : index;
        if (chosen < 0 || chosen >= values.size()) {
            return fallback;
        }
        String value = values.get(chosen);
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<String>();
        for (Object item : JsonIO.asList(value)) {
            String text = JsonIO.stringValue(item);
            if (text != null) {
                out.add(text.trim());
            }
        }
        return out;
    }

    private static List<Double> doubles(Object value) {
        List<Double> out = new ArrayList<Double>();
        for (Object item : JsonIO.asList(value)) {
            if (item instanceof Number) {
                out.add(Double.valueOf(((Number) item).doubleValue()));
            } else if (item != null) {
                try {
                    out.add(Double.valueOf(Double.parseDouble(String.valueOf(item).trim())));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return out;
    }

    private static List<Boolean> booleans(Object value) {
        List<Boolean> out = new ArrayList<Boolean>();
        for (Object item : JsonIO.asList(value)) {
            out.add(Boolean.valueOf(JsonIO.booleanValue(item, false)));
        }
        return out;
    }

    private static List<String> immutableStrings(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<String>(values == null
                ? Collections.<String>emptyList()
                : values));
    }

    private static List<Double> immutableDoubles(List<Double> values) {
        return Collections.unmodifiableList(new ArrayList<Double>(values == null
                ? Collections.<Double>emptyList()
                : values));
    }

    private static List<Boolean> immutableBooleans(List<Boolean> values) {
        return Collections.unmodifiableList(new ArrayList<Boolean>(values == null
                ? Collections.<Boolean>emptyList()
                : values));
    }

    private static String requireText(String label, String value) {
        String trimmed = emptyToNull(value);
        if (trimmed == null) {
            throw new IllegalArgumentException(label + " is required.");
        }
        return trimmed;
    }

    private static String emptyToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String stringOr(Object value, String fallback) {
        String text = JsonIO.stringValue(value);
        return text == null || text.trim().isEmpty() ? fallback : text.trim();
    }
}
