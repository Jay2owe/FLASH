package flash.pipeline.recipes;

import flash.pipeline.ui.wizard.JsonIO;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static flash.pipeline.FLASH_Pipeline.IDX_3D_OBJECT;
import static flash.pipeline.FLASH_Pipeline.IDX_AGGREGATION;
import static flash.pipeline.FLASH_Pipeline.IDX_CREATE_BIN;
import static flash.pipeline.FLASH_Pipeline.IDX_DECONVOLUTION;
import static flash.pipeline.FLASH_Pipeline.IDX_DRAW_ROIS;
import static flash.pipeline.FLASH_Pipeline.IDX_EXCEL_EXPORT;
import static flash.pipeline.FLASH_Pipeline.IDX_INTENSITY;
import static flash.pipeline.FLASH_Pipeline.IDX_LINE_DISTANCE;
import static flash.pipeline.FLASH_Pipeline.IDX_SPATIAL;
import static flash.pipeline.FLASH_Pipeline.IDX_SPECTRAL_DECONTAMINATION;
import static flash.pipeline.FLASH_Pipeline.IDX_SPLIT_MERGE;
import static flash.pipeline.FLASH_Pipeline.IDX_STATISTICS;

/**
 * Top-level recipe that selects a set of pipeline analyses.
 */
public final class PipelineRecipe {

    public static final String CURRENT_FLASH_VERSION = "4.0.0";

    public static final Map<String, Integer> KEY_TO_IDX;
    public static final Map<Integer, String> IDX_TO_KEY;

    static {
        Map<String, Integer> keys = new HashMap<String, Integer>();
        keys.put("CreateBin", Integer.valueOf(IDX_CREATE_BIN));
        keys.put("DrawROIs", Integer.valueOf(IDX_DRAW_ROIS));
        keys.put("Deconvolution", Integer.valueOf(IDX_DECONVOLUTION));
        keys.put("SplitMerge", Integer.valueOf(IDX_SPLIT_MERGE));
        keys.put("ThreeDObject", Integer.valueOf(IDX_3D_OBJECT));
        keys.put("Spatial", Integer.valueOf(IDX_SPATIAL));
        keys.put("LineDistance", Integer.valueOf(IDX_LINE_DISTANCE));
        keys.put("Intensity", Integer.valueOf(IDX_INTENSITY));
        keys.put("Aggregation", Integer.valueOf(IDX_AGGREGATION));
        keys.put("Statistics", Integer.valueOf(IDX_STATISTICS));
        keys.put("Excel", Integer.valueOf(IDX_EXCEL_EXPORT));
        keys.put("Spectral", Integer.valueOf(IDX_SPECTRAL_DECONTAMINATION));
        KEY_TO_IDX = Collections.unmodifiableMap(keys);

        Map<Integer, String> indexes = new HashMap<Integer, String>();
        indexes.put(Integer.valueOf(IDX_CREATE_BIN), "CreateBin");
        indexes.put(Integer.valueOf(IDX_DRAW_ROIS), "DrawROIs");
        indexes.put(Integer.valueOf(IDX_DECONVOLUTION), "Deconvolution");
        indexes.put(Integer.valueOf(IDX_SPLIT_MERGE), "SplitMerge");
        indexes.put(Integer.valueOf(IDX_3D_OBJECT), "ThreeDObject");
        indexes.put(Integer.valueOf(IDX_SPATIAL), "Spatial");
        indexes.put(Integer.valueOf(IDX_LINE_DISTANCE), "LineDistance");
        indexes.put(Integer.valueOf(IDX_INTENSITY), "Intensity");
        indexes.put(Integer.valueOf(IDX_AGGREGATION), "Aggregation");
        indexes.put(Integer.valueOf(IDX_STATISTICS), "Statistics");
        indexes.put(Integer.valueOf(IDX_EXCEL_EXPORT), "Excel");
        indexes.put(Integer.valueOf(IDX_SPECTRAL_DECONTAMINATION), "Spectral");
        IDX_TO_KEY = Collections.unmodifiableMap(indexes);
    }

    private final String name;
    private final String description;
    private final String flashVersion;
    private final List<String> analyses;
    private final Map<String, String> modulePresets;

    public PipelineRecipe(String name, String description, String flashVersion,
                          List<String> analyses, Map<String, String> modulePresets) {
        this.name = requireText("name", name);
        this.description = emptyToNull(description);
        this.flashVersion = emptyToNull(flashVersion) == null ? CURRENT_FLASH_VERSION : flashVersion.trim();
        this.analyses = immutableStrings(analyses);
        this.modulePresets = immutableStringMap(modulePresets);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getFlashVersion() {
        return flashVersion;
    }

    public List<String> getAnalyses() {
        return analyses;
    }

    public Map<String, String> getModulePresets() {
        return modulePresets;
    }

    public List<String> unknownAnalysisKeys() {
        List<String> unknown = new ArrayList<String>();
        for (String key : analyses) {
            if (!KEY_TO_IDX.containsKey(key)) {
                unknown.add(key);
            }
        }
        return unknown;
    }

    public Map<String, Object> toJsonObject() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("name", name);
        if (description != null) {
            root.put("description", description);
        }
        root.put("flashVersion", flashVersion);
        root.put("analyses", new ArrayList<String>(analyses));
        root.put("modulePresets", new LinkedHashMap<String, String>(modulePresets));
        return root;
    }

    public String toJson() {
        return JsonIO.write(toJsonObject());
    }

    public static PipelineRecipe fromJson(String json) throws IOException {
        return fromJsonObject(JsonIO.parseObject(json));
    }

    public static PipelineRecipe fromJsonObject(Map<String, Object> root) throws IOException {
        if (root == null) {
            throw new IOException("Recipe JSON object is required.");
        }
        return new PipelineRecipe(
                stringOr(root.get("name"), "Pipeline Recipe"),
                JsonIO.stringValue(root.get("description")),
                stringOr(root.get("flashVersion"), CURRENT_FLASH_VERSION),
                strings(root.get("analyses")),
                stringMap(JsonIO.asObject(root.get("modulePresets"))));
    }

    public static PipelineRecipe fromSelections(String name, String description, boolean[] selections) {
        List<String> keys = new ArrayList<String>();
        if (selections != null) {
            for (int i = 0; i < selections.length; i++) {
                if (!selections[i]) {
                    continue;
                }
                String key = IDX_TO_KEY.get(Integer.valueOf(i));
                if (key != null) {
                    keys.add(key);
                }
            }
        }
        return new PipelineRecipe(name, description, CURRENT_FLASH_VERSION,
                keys, Collections.<String, String>emptyMap());
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<String>();
        for (Object item : JsonIO.asList(value)) {
            String text = JsonIO.stringValue(item);
            if (text != null && !text.trim().isEmpty()) {
                out.add(text.trim());
            }
        }
        return out;
    }

    private static Map<String, String> stringMap(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String value = JsonIO.stringValue(entry.getValue());
            if (value != null && !value.trim().isEmpty()) {
                out.put(entry.getKey(), value.trim());
            }
        }
        return out;
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

    private static List<String> immutableStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                out.add(value.trim());
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static Map<String, String> immutableStringMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getKey().trim().isEmpty()) {
                continue;
            }
            String value = entry.getValue();
            if (value != null && !value.trim().isEmpty()) {
                out.put(entry.getKey().trim(), value.trim());
            }
        }
        return Collections.unmodifiableMap(out);
    }
}
