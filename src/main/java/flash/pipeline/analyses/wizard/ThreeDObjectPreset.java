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
 * Persisted setup for 3D Object Analysis.
 */
public final class ThreeDObjectPreset implements Preset<ThreeDObjectPreset> {

    public static final String CURRENT_LIBRARY_VERSION = "1";

    private final String name;
    private final String description;
    private final String libraryVersion;
    private final boolean doVolumetric;
    private final boolean doCpc;
    private final boolean extractProcessLength;
    private final boolean runSpatial;
    private final boolean classicalCentroidFiltering;
    private final double colocThresholdPercent;
    private final List<String> processMarkerHints;
    private final List<String> nuclearMarkerHints;

    public ThreeDObjectPreset(String name,
                              String description,
                              String libraryVersion,
                              boolean doVolumetric,
                              boolean doCpc,
                              boolean extractProcessLength,
                              boolean runSpatial,
                              boolean classicalCentroidFiltering,
                              double colocThresholdPercent,
                              List<String> processMarkerHints,
                              List<String> nuclearMarkerHints) {
        this.name = requireText("name", name);
        this.description = emptyToNull(description);
        this.libraryVersion = emptyToNull(libraryVersion) == null
                ? CURRENT_LIBRARY_VERSION
                : libraryVersion.trim();
        this.doVolumetric = doVolumetric;
        this.doCpc = doCpc;
        this.extractProcessLength = extractProcessLength;
        this.runSpatial = runSpatial;
        this.classicalCentroidFiltering = classicalCentroidFiltering;
        this.colocThresholdPercent = colocThresholdPercent;
        this.processMarkerHints = immutableStrings(processMarkerHints);
        this.nuclearMarkerHints = immutableStrings(nuclearMarkerHints);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getLibraryVersion() {
        return libraryVersion;
    }

    public ThreeDObjectPreset getPayload() {
        return this;
    }

    public boolean isDoVolumetric() {
        return doVolumetric;
    }

    public boolean isDoCpc() {
        return doCpc;
    }

    public boolean isExtractProcessLength() {
        return extractProcessLength;
    }

    public boolean isRunSpatial() {
        return runSpatial;
    }

    public boolean isClassicalCentroidFiltering() {
        return classicalCentroidFiltering;
    }

    public double getColocThresholdPercent() {
        return colocThresholdPercent;
    }

    public List<String> getProcessMarkerHints() {
        return processMarkerHints;
    }

    public List<String> getNuclearMarkerHints() {
        return nuclearMarkerHints;
    }

    public Map<String, Object> toJsonObject() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("name", name);
        if (description != null) {
            root.put("description", description);
        }
        root.put("libraryVersion", libraryVersion);
        root.put("doVolumetric", Boolean.valueOf(doVolumetric));
        root.put("doCpc", Boolean.valueOf(doCpc));
        root.put("extractProcessLength", Boolean.valueOf(extractProcessLength));
        root.put("runSpatial", Boolean.valueOf(runSpatial));
        root.put("classicalCentroidFiltering", Boolean.valueOf(classicalCentroidFiltering));
        root.put("colocThresholdPercent", Double.valueOf(colocThresholdPercent));
        root.put("processMarkerHints", new ArrayList<String>(processMarkerHints));
        root.put("nuclearMarkerHints", new ArrayList<String>(nuclearMarkerHints));
        return root;
    }

    public String toJson() {
        return JsonIO.write(toJsonObject());
    }

    public static ThreeDObjectPreset fromJson(String json) throws IOException {
        return fromJsonObject(JsonIO.parseObject(json));
    }

    public static ThreeDObjectPreset fromJsonObject(Map<String, Object> root) throws IOException {
        if (root == null) {
            throw new IOException("Preset JSON object is required.");
        }
        return new ThreeDObjectPreset(
                stringOr(root.get("name"), "3D Object Preset"),
                JsonIO.stringValue(root.get("description")),
                stringOr(root.get("libraryVersion"), CURRENT_LIBRARY_VERSION),
                JsonIO.booleanValue(root.get("doVolumetric"), false),
                JsonIO.booleanValue(root.get("doCpc"), false),
                JsonIO.booleanValue(root.get("extractProcessLength"), false),
                JsonIO.booleanValue(root.get("runSpatial"), false),
                JsonIO.booleanValue(root.get("classicalCentroidFiltering"), false),
                doubleValue(root.get("colocThresholdPercent"), 30.0),
                strings(root.get("processMarkerHints")),
                strings(root.get("nuclearMarkerHints")));
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<String>();
        for (Object item : JsonIO.asList(value)) {
            if (item != null) {
                String text = String.valueOf(item).trim();
                if (!text.isEmpty()) {
                    out.add(text);
                }
            }
        }
        return out;
    }

    private static double doubleValue(Object value, double fallback) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
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
}
