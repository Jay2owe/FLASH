package flash.pipeline.analyses.wizard;

import flash.pipeline.bin.ChannelIdentities;
import flash.pipeline.intelligence.MetadataDiagnostics;
import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.ToggleSwitch;
import flash.pipeline.ui.wizard.WizardFlow;

import javax.swing.JTextField;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Intent-led setup helper for Spatial & Morphometric Analysis.
 */
public class SpatialAnalysisWizard extends WizardFlow {

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
    public static final String MORPH_COMPLEX = "Cell-level complexity score (RI / SRI / PB / MP / VSD)";
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

    private static final String[] LUTS = {"Fire", "Grays", "Cyan", "Green", "Magenta", "Red"};
    private static final String[] THRESHOLDS = {"Loose (10%)", "Standard (30%)", "Strict (60%)"};

    private final ChannelIdentities identities;
    private final MetadataDiagnostics.SeriesInfo seriesInfo;
    private final boolean hasCalibration;
    private final boolean thresholdsConfiguredUpstream;

    public SpatialAnalysisWizard(MainPanelBinding panel,
                                 ChannelIdentities identities,
                                 MetadataDiagnostics.SeriesInfo seriesInfo,
                                 boolean hasCalibration,
                                 boolean thresholdsConfiguredUpstream,
                                 boolean headless) {
        super("Spatial & Morphometry Helper", panel, headless);
        this.identities = identities == null ? new ChannelIdentities(null) : identities;
        this.seriesInfo = seriesInfo;
        this.hasCalibration = hasCalibration;
        this.thresholdsConfiguredUpstream = thresholdsConfiguredUpstream;
        register(new SpatialQuestionScreen());
        register(new MorphometryQuestionScreen());
        register(new TextureQuestionScreen());
        register(new HeatmapTuningScreen());
        register(new ThresholdScreen());
    }

    public DerivedConfig deriveCurrentConfig() {
        return deriveConfig(identities, seriesInfo, hasCalibration, currentAnswers());
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

        String texture = answerString(safeAnswers, "texture.question", TEXTURE_NONE);
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

    private static String[] enabledSpatialOptions(boolean hasCalibration) {
        String[] all = new String[]{
                SPATIAL_NONE, SPATIAL_DISTANCE, SPATIAL_CLUSTERED, SPATIAL_COLOC,
                SPATIAL_TERRITORY, SPATIAL_HOTSPOTS, SPATIAL_PHENOTYPES, SPATIAL_ALL
        };
        List<String> enabled = new ArrayList<String>();
        for (String option : all) {
            if (isSpatialOptionEnabled(option, hasCalibration)) {
                enabled.add(option);
            }
        }
        return enabled.toArray(new String[enabled.size()]);
    }

    private static String[] enabledMorphologyOptions(MetadataDiagnostics.SeriesInfo info) {
        String[] all = new String[]{
                MORPH_NONE, MORPH_2D, MORPH_3D, MORPH_COMPLEX,
                MORPH_POPULATION, MORPH_TERRITORY, MORPH_ALL
        };
        List<String> enabled = new ArrayList<String>();
        for (String option : all) {
            if (isMorphologyOptionEnabled(option, info)) {
                enabled.add(option);
            }
        }
        return enabled.toArray(new String[enabled.size()]);
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
        public boolean doNative3DTexture;
        public boolean forceRerun;
        public double kdeBandwidth = 0.0;
        public String heatmapLut = "Fire";
        public int clusterK = 0;
        public int textureClassK = 4;
        public double colocThresholdPercent = 30.0;
        public final Map<String, Double> markerThresholds = new LinkedHashMap<String, Double>();

        public boolean anyEarlyPhaseToggleOn() {
            return forceRerun || doCpc || doHeatmaps || do2DMorphology || do3DMorphology
                    || doObjectGLCM || doObjectFractal
                    || doObjectTextureClass || doNative3DTexture;
        }
    }

    private final class SpatialQuestionScreen extends Screen {
        private SpatialQuestionScreen() {
            super("What spatial question are you asking?");
            defaultAnswer("spatial.question", defaultSpatialQuestion(identities, hasCalibration));
        }

        public void build(PipelineDialog dialog, AnswerMap answers) {
            dialog.addHeader("What spatial question are you asking?");
            String selected = answers.getString("spatial.question", defaultSpatialQuestion(identities, hasCalibration));
            if (!isSpatialOptionEnabled(selected, hasCalibration)) {
                selected = SPATIAL_NONE;
            }
            dialog.addChoice("Spatial question", enabledSpatialOptions(hasCalibration), selected);
            if (!hasCalibration) {
                dialog.addMessage(RIPLEY_CALIBRATION_WARNING);
            }
        }

        public void read(PipelineDialog dialog, AnswerMap answers) {
            String selected = dialog.getNextChoice();
            answers.put("spatial.question", selected);
        }

        public void writeTo(MainPanelBinding panel, AnswerMap answers) {
            panel.setValue("spatial.config", deriveCurrentConfig());
        }
    }

    private final class MorphometryQuestionScreen extends Screen {
        private MorphometryQuestionScreen() {
            super("What morphology question are you asking?");
            defaultAnswer("morph.question", defaultMorphologyQuestion(identities, seriesInfo));
        }

        public void build(PipelineDialog dialog, AnswerMap answers) {
            dialog.addHeader("What morphology question are you asking?");
            String selected = answers.getString("morph.question", defaultMorphologyQuestion(identities, seriesInfo));
            if (!isMorphologyOptionEnabled(selected, seriesInfo)) {
                selected = MORPH_NONE;
            }
            dialog.addChoice("Morphology question", enabledMorphologyOptions(seriesInfo), selected);
            if (seriesInfo != null && seriesInfo.sizeZ > 0 && seriesInfo.sizeZ < 5) {
                dialog.addMessage("(requires z-stack with more slices)");
            }
        }

        public void read(PipelineDialog dialog, AnswerMap answers) {
            String selected = dialog.getNextChoice();
            answers.put("morph.question", selected);
        }

        public void writeTo(MainPanelBinding panel, AnswerMap answers) {
        }
    }

    private final class TextureQuestionScreen extends Screen {
        private TextureQuestionScreen() {
            super("What object texture / complexity question are you asking?");
            defaultAnswer("texture.question", TEXTURE_NONE);
            defaultAnswer("texture.k", Integer.valueOf(4));
            defaultAnswer("spatial.texture.native3d", Boolean.FALSE);
        }

        public void build(PipelineDialog dialog, AnswerMap answers) {
            dialog.addHeader("What object texture / complexity question are you asking?");
            String selected = answers.getString("texture.question", TEXTURE_NONE);
            dialog.addChoice("Texture question",
                    new String[]{TEXTURE_NONE, TEXTURE_GLCM, TEXTURE_FRACTAL,
                            TEXTURE_CLASS, TEXTURE_ALL},
                    selected);
            dialog.beginAdvancedSection("spatial.texture.advanced");
            dialog.addToggle("Native-3D texture (GLCM + texture classes)",
                    answers.getBoolean("spatial.texture.native3d", false));
            dialog.addNumericField("Texture classes (k)", answers.getInt("texture.k", 4), 0);
            dialog.endAdvancedSection();
        }

        public void read(PipelineDialog dialog, AnswerMap answers) {
            answers.put("texture.question", dialog.getNextChoice());
            answers.put("spatial.texture.native3d", Boolean.valueOf(dialog.getNextBoolean()));
            answers.put("texture.k", Integer.valueOf((int) dialog.getNextNumber()));
        }

        public void writeTo(MainPanelBinding panel, AnswerMap answers) {
        }
    }

    private final class HeatmapTuningScreen extends Screen {
        private HeatmapTuningScreen() {
            super("Hotspot / phenotyping tuning");
            defaultAnswer("heatmap.lut", "Fire");
            defaultAnswer("heatmap.autoBandwidth", Boolean.TRUE);
            defaultAnswer("heatmap.bandwidth", Double.valueOf(0.0));
            defaultAnswer("heatmap.autoK", Boolean.TRUE);
            defaultAnswer("heatmap.clusterK", Integer.valueOf(0));
        }

        public boolean isApplicable(AnswerMap prior) {
            DerivedConfig config = deriveConfig(identities, seriesInfo, hasCalibration, prior);
            return config.doHeatmaps || config.doPhenotyping;
        }

        public void build(PipelineDialog dialog, AnswerMap answers) {
            dialog.addHeader("Hotspot / phenotyping tuning");
            dialog.addChoice("Heatmap color scheme", LUTS, answers.getString("heatmap.lut", "Fire"));
            ToggleSwitch autoBandwidth = dialog.addToggle("Auto-detect KDE bandwidth?",
                    answers.getBoolean("heatmap.autoBandwidth", true));
            JTextField bandwidth = dialog.addNumericField("KDE bandwidth (um)", answers.getInt("heatmap.bandwidth", 0), 1);
            bandwidth.setEnabled(!autoBandwidth.isSelected());
            autoBandwidth.addChangeListener(new Runnable() {
                public void run() {
                    bandwidth.setEnabled(!autoBandwidth.isSelected());
                }
            });
            ToggleSwitch autoK = dialog.addToggle("Auto-detect cluster count?",
                    answers.getBoolean("heatmap.autoK", true));
            JTextField k = dialog.addNumericField("Clusters (k)", answers.getInt("heatmap.clusterK", 0), 0);
            k.setEnabled(!autoK.isSelected());
            autoK.addChangeListener(new Runnable() {
                public void run() {
                    k.setEnabled(!autoK.isSelected());
                }
            });
        }

        public void read(PipelineDialog dialog, AnswerMap answers) {
            answers.put("heatmap.lut", dialog.getNextChoice());
            answers.put("heatmap.autoBandwidth", Boolean.valueOf(dialog.getNextBoolean()));
            answers.put("heatmap.bandwidth", Double.valueOf(dialog.getNextNumber()));
            answers.put("heatmap.autoK", Boolean.valueOf(dialog.getNextBoolean()));
            answers.put("heatmap.clusterK", Integer.valueOf((int) dialog.getNextNumber()));
        }

        public void writeTo(MainPanelBinding panel, AnswerMap answers) {
        }
    }

    private final class ThresholdScreen extends Screen {
        private ThresholdScreen() {
            super("How much overlap counts as colocalized?");
            defaultAnswer("coloc.threshold", "Standard (30%)");
        }

        public boolean isApplicable(AnswerMap prior) {
            if (thresholdsConfiguredUpstream) {
                return false;
            }
            DerivedConfig config = deriveConfig(identities, seriesInfo, hasCalibration, prior);
            return config.doVolColoc || config.doCpc;
        }

        public void build(PipelineDialog dialog, AnswerMap answers) {
            dialog.addHeader("How much overlap counts as colocalized?");
            dialog.addChoice("Colocalization threshold", THRESHOLDS,
                    answers.getString("coloc.threshold", "Standard (30%)"));
        }

        public void read(PipelineDialog dialog, AnswerMap answers) {
            answers.put("coloc.threshold", dialog.getNextChoice());
        }

        public void writeTo(MainPanelBinding panel, AnswerMap answers) {
        }
    }
}
