package flash.pipeline.analyses.wizard;

import flash.pipeline.ui.wizard.JsonIO;
import flash.pipeline.ui.wizard.Preset;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persisted setup for Spatial & Morphometric Analysis.
 */
public final class SpatialPreset implements Preset<SpatialPreset> {

    public static final String CURRENT_LIBRARY_VERSION = "1";

    private final String name;
    private final String description;
    private final String libraryVersion;
    private final boolean doDistances;
    private final boolean doSpatialStats;
    private final boolean doVolColoc;
    private final boolean doCpc;
    private final boolean doVoronoi;
    private final boolean doHeatmaps;
    private final boolean doPhenotyping;
    private final boolean do2DMorphology;
    private final boolean do3DMorphology;
    private final boolean doCompositeIndices;
    private final boolean doPopMorphometrics;
    private final boolean doSpatialMorphometrics;
    private final boolean doObjectGLCM;
    private final boolean doObjectFractal;
    private final boolean doObjectTextureClass;
    private final boolean doNative3DTexture;
    private final int textureClassK;
    private final double kdeBandwidth;
    private final String heatmapLut;
    private final int clusterK;
    private final double colocThresholdPercent;

    public SpatialPreset(String name,
                         String description,
                         String libraryVersion,
                         boolean doDistances,
                         boolean doSpatialStats,
                         boolean doVolColoc,
                         boolean doCpc,
                         boolean doVoronoi,
                         boolean doHeatmaps,
                         boolean doPhenotyping,
                         boolean do2DMorphology,
                         boolean do3DMorphology,
                         boolean doCompositeIndices,
                         boolean doPopMorphometrics,
                         boolean doSpatialMorphometrics,
                         double kdeBandwidth,
                         String heatmapLut,
                         int clusterK,
                         double colocThresholdPercent) {
        this(name, description, libraryVersion, doDistances, doSpatialStats, doVolColoc,
                doCpc, doVoronoi, doHeatmaps, doPhenotyping, do2DMorphology,
                do3DMorphology, doCompositeIndices, doPopMorphometrics,
                doSpatialMorphometrics, false, false, false, 4,
                kdeBandwidth, heatmapLut, clusterK, colocThresholdPercent);
    }

    public SpatialPreset(String name,
                         String description,
                         String libraryVersion,
                         boolean doDistances,
                         boolean doSpatialStats,
                         boolean doVolColoc,
                         boolean doCpc,
                         boolean doVoronoi,
                         boolean doHeatmaps,
                         boolean doPhenotyping,
                         boolean do2DMorphology,
                         boolean do3DMorphology,
                         boolean doCompositeIndices,
                         boolean doPopMorphometrics,
                         boolean doSpatialMorphometrics,
                         boolean doObjectGLCM,
                         boolean doObjectFractal,
                         boolean doObjectTextureClass,
                         int textureClassK,
                         double kdeBandwidth,
                         String heatmapLut,
                         int clusterK,
                         double colocThresholdPercent) {
        this(name, description, libraryVersion, doDistances, doSpatialStats, doVolColoc,
                doCpc, doVoronoi, doHeatmaps, doPhenotyping, do2DMorphology,
                do3DMorphology, doCompositeIndices, doPopMorphometrics,
                doSpatialMorphometrics, doObjectGLCM, doObjectFractal,
                doObjectTextureClass, false, textureClassK,
                kdeBandwidth, heatmapLut, clusterK, colocThresholdPercent);
    }

    public SpatialPreset(String name,
                         String description,
                         String libraryVersion,
                         boolean doDistances,
                         boolean doSpatialStats,
                         boolean doVolColoc,
                         boolean doCpc,
                         boolean doVoronoi,
                         boolean doHeatmaps,
                         boolean doPhenotyping,
                         boolean do2DMorphology,
                         boolean do3DMorphology,
                         boolean doCompositeIndices,
                         boolean doPopMorphometrics,
                         boolean doSpatialMorphometrics,
                         boolean doObjectGLCM,
                         boolean doObjectFractal,
                         boolean doObjectTextureClass,
                         boolean doNative3DTexture,
                         int textureClassK,
                         double kdeBandwidth,
                         String heatmapLut,
                         int clusterK,
                         double colocThresholdPercent) {
        this.name = requireText("name", name);
        this.description = emptyToNull(description);
        this.libraryVersion = emptyToNull(libraryVersion) == null
                ? CURRENT_LIBRARY_VERSION
                : libraryVersion.trim();
        this.doDistances = doDistances;
        this.doSpatialStats = doSpatialStats;
        this.doVolColoc = doVolColoc;
        this.doCpc = doCpc;
        this.doVoronoi = doVoronoi;
        this.doHeatmaps = doHeatmaps;
        this.doPhenotyping = doPhenotyping;
        this.do2DMorphology = do2DMorphology;
        this.do3DMorphology = do3DMorphology;
        this.doCompositeIndices = doCompositeIndices;
        this.doPopMorphometrics = doPopMorphometrics;
        this.doSpatialMorphometrics = doSpatialMorphometrics;
        this.doObjectGLCM = doObjectGLCM;
        this.doObjectFractal = doObjectFractal;
        this.doObjectTextureClass = doObjectTextureClass;
        this.doNative3DTexture = doNative3DTexture;
        this.textureClassK = Math.max(2, Math.min(10, textureClassK));
        this.kdeBandwidth = kdeBandwidth;
        this.heatmapLut = emptyToNull(heatmapLut) == null ? "Fire" : heatmapLut.trim();
        this.clusterK = clusterK;
        this.colocThresholdPercent = colocThresholdPercent;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getLibraryVersion() { return libraryVersion; }
    public SpatialPreset getPayload() { return this; }
    public boolean isDoDistances() { return doDistances; }
    public boolean isDoSpatialStats() { return doSpatialStats; }
    public boolean isDoVolColoc() { return doVolColoc; }
    public boolean isDoCpc() { return doCpc; }
    public boolean isDoVoronoi() { return doVoronoi; }
    public boolean isDoHeatmaps() { return doHeatmaps; }
    public boolean isDoPhenotyping() { return doPhenotyping; }
    public boolean isDo2DMorphology() { return do2DMorphology; }
    public boolean isDo3DMorphology() { return do3DMorphology; }
    public boolean isDoCompositeIndices() { return doCompositeIndices; }
    public boolean isDoPopMorphometrics() { return doPopMorphometrics; }
    public boolean isDoSpatialMorphometrics() { return doSpatialMorphometrics; }
    public boolean isDoObjectGLCM() { return doObjectGLCM; }
    public boolean isDoObjectFractal() { return doObjectFractal; }
    public boolean isDoObjectTextureClass() { return doObjectTextureClass; }
    public boolean isDoNative3DTexture() { return doNative3DTexture; }
    public int getTextureClassK() { return textureClassK; }
    public double getKdeBandwidth() { return kdeBandwidth; }
    public String getHeatmapLut() { return heatmapLut; }
    public int getClusterK() { return clusterK; }
    public double getColocThresholdPercent() { return colocThresholdPercent; }

    public Map<String, Object> toJsonObject() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("name", name);
        if (description != null) root.put("description", description);
        root.put("libraryVersion", libraryVersion);
        root.put("doDistances", Boolean.valueOf(doDistances));
        root.put("doSpatialStats", Boolean.valueOf(doSpatialStats));
        root.put("doVolColoc", Boolean.valueOf(doVolColoc));
        root.put("doCpc", Boolean.valueOf(doCpc));
        root.put("doVoronoi", Boolean.valueOf(doVoronoi));
        root.put("doHeatmaps", Boolean.valueOf(doHeatmaps));
        root.put("doPhenotyping", Boolean.valueOf(doPhenotyping));
        root.put("do2DMorphology", Boolean.valueOf(do2DMorphology));
        root.put("do3DMorphology", Boolean.valueOf(do3DMorphology));
        root.put("doCompositeIndices", Boolean.valueOf(doCompositeIndices));
        root.put("doPopMorphometrics", Boolean.valueOf(doPopMorphometrics));
        root.put("doSpatialMorphometrics", Boolean.valueOf(doSpatialMorphometrics));
        root.put("doObjectGLCM", Boolean.valueOf(doObjectGLCM));
        root.put("doObjectFractal", Boolean.valueOf(doObjectFractal));
        root.put("doObjectTextureClass", Boolean.valueOf(doObjectTextureClass));
        root.put("doNative3DTexture", Boolean.valueOf(doNative3DTexture));
        root.put("textureClassK", Integer.valueOf(textureClassK));
        root.put("kdeBandwidth", Double.valueOf(kdeBandwidth));
        root.put("heatmapLut", heatmapLut);
        root.put("clusterK", Integer.valueOf(clusterK));
        root.put("colocThresholdPercent", Double.valueOf(colocThresholdPercent));
        return root;
    }

    public String toJson() {
        return JsonIO.write(toJsonObject());
    }

    public static SpatialPreset fromJson(String json) throws IOException {
        return fromJsonObject(JsonIO.parseObject(json));
    }

    public static SpatialPreset fromJsonObject(Map<String, Object> root) throws IOException {
        if (root == null) throw new IOException("Preset JSON object is required.");
        return new SpatialPreset(
                stringOr(root.get("name"), "Spatial Preset"),
                JsonIO.stringValue(root.get("description")),
                stringOr(root.get("libraryVersion"), CURRENT_LIBRARY_VERSION),
                JsonIO.booleanValue(root.get("doDistances"), false),
                JsonIO.booleanValue(root.get("doSpatialStats"), false),
                JsonIO.booleanValue(root.get("doVolColoc"), false),
                JsonIO.booleanValue(root.get("doCpc"), false),
                JsonIO.booleanValue(root.get("doVoronoi"), false),
                JsonIO.booleanValue(root.get("doHeatmaps"), false),
                JsonIO.booleanValue(root.get("doPhenotyping"), false),
                JsonIO.booleanValue(root.get("do2DMorphology"), false),
                JsonIO.booleanValue(root.get("do3DMorphology"), false),
                JsonIO.booleanValue(root.get("doCompositeIndices"), false),
                JsonIO.booleanValue(root.get("doPopMorphometrics"), false),
                JsonIO.booleanValue(root.get("doSpatialMorphometrics"), false),
                JsonIO.booleanValue(root.get("doObjectGLCM"), false),
                JsonIO.booleanValue(root.get("doObjectFractal"), false),
                JsonIO.booleanValue(root.get("doObjectTextureClass"), false),
                JsonIO.booleanValue(root.get("doNative3DTexture"), false),
                JsonIO.intValue(root.get("textureClassK"), 4),
                doubleValue(root.get("kdeBandwidth"), 0.0),
                stringOr(root.get("heatmapLut"), "Fire"),
                JsonIO.intValue(root.get("clusterK"), 0),
                doubleValue(root.get("colocThresholdPercent"), 30.0));
    }

    private static double doubleValue(Object value, double fallback) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value == null) return fallback;
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String requireText(String label, String value) {
        String trimmed = emptyToNull(value);
        if (trimmed == null) throw new IllegalArgumentException(label + " is required.");
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
