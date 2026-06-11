package flash.pipeline.analyses.wizard;

import flash.pipeline.bin.ChannelIdentities;
import flash.pipeline.intelligence.MetadataDiagnostics;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Derives Spatial &amp; Morphometric Analysis options from saved presets and
 * intent answers. The interactive setup helper has been removed; this class
 * keeps the reusable configuration model that preset IO, the analysis apply
 * logic, the CLI, and tests depend on.
 */
public final class SpatialSetupConfig {

    public static final String SPATIAL_NONE = "Not measuring spatial distribution";
    public static final String SPATIAL_DISTANCE = "How close are cells to each other?";
    public static final String SPATIAL_CLUSTERED = "Are cells clustered, dispersed, or random?";
    public static final String SPATIAL_COLOC = "Are cells contacting / colocalizing with another channel?";
    public static final String SPATIAL_TERRITORY = "What territory does each cell occupy?";
    public static final String SPATIAL_HOTSPOTS = "Where are the hotspots?";
    public static final String SPATIAL_PHENOTYPES = "Where are the hotspots and phenotypes?";
    public static final String SPATIAL_ALL = "All of the above (exploratory)";

    public static final String MORPH_NONE = "Not measuring morphology";
    public static final String MORPH_2D = "Simple shape (area / circularity / aspect ratio)";
    public static final String MORPH_3D = "3D shape (sphericity / elongation / Feret diameter)";
    public static final String MORPH_COMPLEX = "Cell-level complexity score (RI / Sholl / skeleton)";
    public static final String MORPH_POPULATION = "Population-scale scoring (CMS / SMSD / IMDI)";
    public static final String MORPH_TERRITORY = "Territory x morphology (TDR / FEV_Mag)";
    public static final String MORPH_ALL = "Everything (exploratory)";

    public static final String TEXTURE_NONE = "Not measuring object texture";
    public static final String TEXTURE_GLCM = "Object texture (GLCM)";
    public static final String TEXTURE_FRACTAL = "Object complexity (fractal + lacunarity)";
    public static final String TEXTURE_CLASS = "Object texture classes (auto-discover sub-populations)";
    public static final String TEXTURE_ALL = "All object texture features (exploratory)";

    public static final String RIPLEY_CALIBRATION_WARNING =
            "Ripley's K/L/G needs calibration data. Run calibration first to enable this option.";
    public static final String Z_STACK_WARNING = "requires z-stack with more slices";

    private SpatialSetupConfig() {
    }

    public static DerivedConfig deriveConfig(ChannelIdentities identities,
                                             MetadataDiagnostics.SeriesInfo seriesInfo,
                                             boolean hasCalibration,
                                             Map<String, Object> answers) {
        ChannelIdentities safeIdentities = identities == null ? new ChannelIdentities(null) : identities;
        Map<String, Object> safeAnswers = answers == null
                ? new LinkedHashMap<String, Object>()
                : answers;
        DerivedConfig out = new DerivedConfig();

        String spatial = answerString(safeAnswers, "spatial.question",
                defaultSpatialQuestion(safeIdentities, hasCalibration));
        if (!isSpatialOptionEnabled(spatial, hasCalibration)) {
            spatial = SPATIAL_NONE;
        }
        applySpatialQuestion(out, spatial);

        String morph = answerString(safeAnswers, "morph.question",
                defaultMorphologyQuestion(safeIdentities, seriesInfo));
        if (!isMorphologyOptionEnabled(morph, seriesInfo)) {
            morph = MORPH_NONE;
        }
        applyMorphologyQuestion(out, morph);

        String texture = canonicalTextureQuestion(answerString(safeAnswers, "texture.question", TEXTURE_NONE));
        applyTextureQuestion(out, texture);
        int requestedTextureK = intAnswer(safeAnswers, "texture.k", 4);
        out.textureClassK = Math.max(2, Math.min(10, requestedTextureK));
        out.doNative3DTexture = booleanAnswer(safeAnswers, "spatial.texture.native3d", false);

        out.heatmapLut = answerString(safeAnswers, "heatmap.lut", "Fire");
        boolean autoBandwidth = booleanAnswer(safeAnswers, "heatmap.autoBandwidth", true);
        out.kdeBandwidth = autoBandwidth ? 0.0 : doubleAnswer(safeAnswers, "heatmap.bandwidth", 0.0);
        boolean autoK = booleanAnswer(safeAnswers, "heatmap.autoK", true);
        out.clusterK = autoK ? 0 : intAnswer(safeAnswers, "heatmap.clusterK", 0);
        if (out.clusterK < 0) out.clusterK = 0;
        if (out.clusterK > 0) out.clusterK = Math.max(2, Math.min(10, out.clusterK));
        out.colocThresholdPercent = thresholdFor(answerString(safeAnswers,
                "coloc.threshold", "Standard (30%)"));

        enforceDependencies(out, seriesInfo, hasCalibration);
        return out;
    }

    public static DerivedConfig fromPreset(SpatialPreset preset) {
        DerivedConfig out = new DerivedConfig();
        if (preset == null) {
            return out;
        }
        out.doDistances = preset.isDoDistances();
        out.doSpatialStats = preset.isDoSpatialStats();
        out.doVolColoc = preset.isDoVolColoc();
        out.doCpc = preset.isDoCpc();
        out.doVoronoi = preset.isDoVoronoi();
        out.doHeatmaps = preset.isDoHeatmaps();
        out.doPhenotyping = preset.isDoPhenotyping();
        out.do2DMorphology = preset.isDo2DMorphology();
        out.do3DMorphology = preset.isDo3DMorphology();
        out.doCompositeIndices = preset.isDoCompositeIndices();
        out.doPopMorphometrics = preset.isDoPopMorphometrics();
        out.doSpatialMorphometrics = preset.isDoSpatialMorphometrics();
        out.doObjectGLCM = preset.isDoObjectGLCM();
        out.doObjectFractal = preset.isDoObjectFractal();
        out.doObjectTextureClass = preset.isDoObjectTextureClass();
        out.doObjectTextureClassFractions = preset.isDoObjectTextureClassFractions();
        out.doNative3DTexture = preset.isDoNative3DTexture();
        out.textureClassK = preset.getTextureClassK();
        out.kdeBandwidth = preset.getKdeBandwidth();
        out.heatmapLut = preset.getHeatmapLut();
        out.clusterK = preset.getClusterK();
        out.colocThresholdPercent = preset.getColocThresholdPercent();
        enforceDependencies(out, null, true);
        return out;
    }

    public static boolean isSpatialOptionEnabled(String option, boolean hasCalibration) {
        return !SPATIAL_CLUSTERED.equals(option) || hasCalibration;
    }

    public static String spatialOptionWarning(String option, boolean hasCalibration) {
        return isSpatialOptionEnabled(option, hasCalibration) ? "" : RIPLEY_CALIBRATION_WARNING;
    }

    public static boolean isMorphologyOptionEnabled(String option, MetadataDiagnostics.SeriesInfo info) {
        if (!requiresZStack(option)) {
            return true;
        }
        return info == null || info.sizeZ <= 0 || info.sizeZ >= 5;
    }

    public static String morphologyOptionWarning(String option, MetadataDiagnostics.SeriesInfo info) {
        return isMorphologyOptionEnabled(option, info) ? "" : "(" + Z_STACK_WARNING + ")";
    }

    public static boolean containsMicroglia(ChannelIdentities identities) {
        return containsMarker(identities, "microglia");
    }

    public static boolean containsAmyloidPlaque(ChannelIdentities identities) {
        if (identities == null) return false;
        for (ChannelIdentities.Entry entry : identities.getEntries()) {
            String marker = safe(entry.getMarkerId()).toLowerCase(Locale.ROOT);
            if (marker.contains("amyloid") || marker.contains("plaque") || marker.contains("abeta")) {
                return true;
            }
        }
        return false;
    }

    private static String defaultSpatialQuestion(ChannelIdentities identities, boolean hasCalibration) {
        return containsAmyloidPlaque(identities) ? SPATIAL_COLOC : SPATIAL_NONE;
    }

    private static String defaultMorphologyQuestion(ChannelIdentities identities,
                                                    MetadataDiagnostics.SeriesInfo info) {
        if (containsMicroglia(identities) && isMorphologyOptionEnabled(MORPH_3D, info)) {
            return MORPH_3D;
        }
        return MORPH_NONE;
    }

    private static void applySpatialQuestion(DerivedConfig out, String option) {
        if (SPATIAL_DISTANCE.equals(option)) {
            out.doDistances = true;
        } else if (SPATIAL_CLUSTERED.equals(option)) {
            out.doDistances = true;
            out.doSpatialStats = true;
        } else if (SPATIAL_COLOC.equals(option)) {
            out.doDistances = true;
            out.doVolColoc = true;
            out.doCpc = true;
        } else if (SPATIAL_TERRITORY.equals(option)) {
            out.doDistances = true;
            out.doVoronoi = true;
        } else if (SPATIAL_HOTSPOTS.equals(option)) {
            out.doHeatmaps = true;
        } else if (SPATIAL_PHENOTYPES.equals(option)) {
            out.doHeatmaps = true;
            out.doPhenotyping = true;
        } else if (SPATIAL_ALL.equals(option)) {
            out.doDistances = true;
            out.doVolColoc = true;
            out.doCpc = true;
            out.doVoronoi = true;
            out.doHeatmaps = true;
            out.doPhenotyping = true;
        }
    }

    private static void applyMorphologyQuestion(DerivedConfig out, String option) {
        if (MORPH_2D.equals(option)) {
            out.do2DMorphology = true;
        } else if (MORPH_3D.equals(option)) {
            out.do3DMorphology = true;
        } else if (MORPH_COMPLEX.equals(option)) {
            out.do3DMorphology = true;
            out.doCompositeIndices = true;
        } else if (MORPH_POPULATION.equals(option)) {
            out.do3DMorphology = true;
            out.doCompositeIndices = true;
            out.doPopMorphometrics = true;
        } else if (MORPH_TERRITORY.equals(option)) {
            out.do3DMorphology = true;
            out.doSpatialMorphometrics = true;
            out.doVoronoi = true;
        } else if (MORPH_ALL.equals(option)) {
            out.do2DMorphology = true;
            out.do3DMorphology = true;
            out.doCompositeIndices = true;
            out.doPopMorphometrics = true;
            out.doSpatialMorphometrics = true;
        }
    }

    private static void applyTextureQuestion(DerivedConfig out, String option) {
        option = canonicalTextureQuestion(option);
        if (TEXTURE_GLCM.equals(option)) {
            out.doObjectGLCM = true;
        } else if (TEXTURE_FRACTAL.equals(option)) {
            out.doObjectFractal = true;
        } else if (TEXTURE_CLASS.equals(option)) {
            out.doObjectTextureClass = true;
        } else if (TEXTURE_ALL.equals(option)) {
            out.doObjectGLCM = true;
            out.doObjectFractal = true;
            out.doObjectTextureClass = true;
        }
    }

    private static String canonicalTextureQuestion(String option) {
        if (option == null) return TEXTURE_NONE;
        if (textureGlcmDisplayLabel().equals(option)) return TEXTURE_GLCM;
        if (textureFractalDisplayLabel().equals(option)) return TEXTURE_FRACTAL;
        if (textureClassDisplayLabel().equals(option)) return TEXTURE_CLASS;
        if (textureAllDisplayLabel().equals(option)) return TEXTURE_ALL;
        return option;
    }

    private static String textureGlcmDisplayLabel() {
        return TEXTURE_GLCM + " (slow)";
    }

    private static String textureFractalDisplayLabel() {
        return TEXTURE_FRACTAL + " (slow)";
    }

    private static String textureClassDisplayLabel() {
        return TEXTURE_CLASS + " (slow)";
    }

    private static String textureAllDisplayLabel() {
        return TEXTURE_ALL + " (slow)";
    }

    public static void enforceDependencies(DerivedConfig out,
                                           MetadataDiagnostics.SeriesInfo info,
                                           boolean hasCalibration) {
        if (out == null) return;
        if (!hasCalibration) {
            out.doSpatialStats = false;
        }
        if (out.doPopMorphometrics) {
            out.doCompositeIndices = true;
        }
        if (out.doCompositeIndices || out.doSpatialMorphometrics) {
            out.do3DMorphology = true;
        }
        if (info != null && info.sizeZ > 0 && info.sizeZ < 5) {
            out.do3DMorphology = false;
            out.doCompositeIndices = false;
            out.doPopMorphometrics = false;
            out.doSpatialMorphometrics = false;
        }
    }

    private static boolean requiresZStack(String option) {
        return MORPH_3D.equals(option)
                || MORPH_COMPLEX.equals(option)
                || MORPH_POPULATION.equals(option)
                || MORPH_TERRITORY.equals(option)
                || MORPH_ALL.equals(option);
    }

    private static boolean containsMarker(ChannelIdentities identities, String token) {
        if (identities == null || token == null) return false;
        String needle = token.toLowerCase(Locale.ROOT);
        for (ChannelIdentities.Entry entry : identities.getEntries()) {
            if (safe(entry.getMarkerId()).toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static double thresholdFor(String strictness) {
        String value = safe(strictness).toLowerCase(Locale.ROOT);
        if (value.contains("loose")) return 10.0;
        if (value.contains("strict")) return 60.0;
        return 30.0;
    }

    private static String answerString(Map<String, Object> answers, String key, String fallback) {
        if (answers == null) return fallback;
        Object value = answers.get(key);
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private static boolean booleanAnswer(Map<String, Object> answers, String key, boolean fallback) {
        if (answers == null || !answers.containsKey(key)) return fallback;
        Object value = answers.get(key);
        if (value instanceof Boolean) return ((Boolean) value).booleanValue();
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static int intAnswer(Map<String, Object> answers, String key, int fallback) {
        Object value = answers == null ? null : answers.get(key);
        if (value instanceof Number) return ((Number) value).intValue();
        if (value == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double doubleAnswer(Map<String, Object> answers, String key, double fallback) {
        Object value = answers == null ? null : answers.get(key);
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value == null) return fallback;
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public static final class DerivedConfig {
        public boolean doDistances;
        public boolean doLineDistance;
        public boolean doSpatialStats;
        public boolean doVolColoc;
        public boolean doCpc;
        public boolean doBBOverlap;
        public boolean doBBCpc;
        public boolean doBBVol;
        public boolean doVoronoi;
        public boolean doHeatmaps;
        public boolean doPhenotyping;
        public boolean do2DMorphology;
        public boolean do3DMorphology;
        public boolean doCompositeIndices;
        public boolean doPopMorphometrics;
        public boolean doSpatialMorphometrics;
        public boolean doObjectGLCM;
        public boolean doObjectFractal;
        public boolean doObjectTextureClass;
        public boolean doObjectTextureClassFractions;
        public boolean doNative3DTexture;
        public boolean forceRerun;
        public double kdeBandwidth = 0.0;
        public String heatmapLut = "Fire";
        public int clusterK = 0;
        public int textureClassK = 4;
        public double colocThresholdPercent = 30.0;
        public double bbColocThresholdPercent = 30.0;
        public final Map<String, Double> markerThresholds = new LinkedHashMap<String, Double>();
        public final Map<String, Double> bbThresholds = new LinkedHashMap<String, Double>();

        public boolean anyEarlyPhaseToggleOn() {
            return forceRerun || doCpc || doHeatmaps || do2DMorphology || do3DMorphology
                    || doObjectGLCM || doObjectFractal
                    || doObjectTextureClass || doNative3DTexture;
        }
    }
}
