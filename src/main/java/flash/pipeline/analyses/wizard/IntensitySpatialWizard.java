package flash.pipeline.analyses.wizard;

import flash.pipeline.runtime.DependencyFixResult;
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.DependencyRegistry;
import flash.pipeline.runtime.DependencyService;
import flash.pipeline.runtime.DependencySpec;
import flash.pipeline.runtime.DependencyStatus;
import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.ToggleSwitch;
import flash.pipeline.ui.wizard.WizardFlow;

import javax.swing.JButton;
import javax.swing.JLabel;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Intent-led setup helper for optional pixel-level intensity-spatial analyses.
 */
public class IntensitySpatialWizard extends WizardFlow {

    public static final String INTENT_NONE = "Not measuring spatial intensity.";
    public static final String INTENT_DISTRIBUTION = "Is the signal evenly distributed or pocketed?";
    public static final String INTENT_HOTSPOTS = "Where are hotspots without thresholding?";
    public static final String INTENT_DEPTH = "Does signal vary with depth from the ROI boundary?";
    public static final String INTENT_ALIGNMENT = "Are structures aligned or oriented?";
    public static final String INTENT_TEXTURE = "What is the texture / complexity of the signal?";
    public static final String INTENT_CROSS_CHANNEL = "How do two channels relate spatially?";
    public static final String INTENT_ALL = "All of the above (exploratory).";

    private static final String FIELD_INTENT = "intensity.spatial.intent";
    private static final String FIELD_SELECTION_INTENT = "intensity.spatial.selection.intent";
    private static final String FIELD_MIP = "intensity.spatial.mip";
    private static final String FIELD_NATIVE_3D = "intensity.spatial.native3d";
    private static final String FIELD_OVERLAYS = "intensity.spatial.overlays";
    private static final String FIELD_SHELL_WIDTH = "intensity.spatial.shellWidthUm";
    private static final String FIELD_SHELL_COUNT = "intensity.spatial.shellCount";
    private static final String FIELD_TILE_SCALES = "intensity.spatial.tileScalesUm";
    private static final String FIELD_GRANULARITY_SCALES = "intensity.spatial.granularityScalesUm";
    private static final String FIELD_DEPTH_BIN = "intensity.spatial.depthBinWidthUm";
    private static final String FIELD_RIM_DEPTH = "intensity.spatial.rimDepthUm";
    private static final String FIELD_TEXTURE_CLASSES = "intensity.spatial.textureClassCount";
    private static final String FIELD_PERMUTATIONS = "intensity.spatial.permutations";
    private static final String FIELD_SEED = "intensity.spatial.seed";

    private static final String[] INTENT_OPTIONS = {
            INTENT_NONE,
            INTENT_DISTRIBUTION,
            INTENT_HOTSPOTS,
            INTENT_DEPTH,
            INTENT_ALIGNMENT,
            INTENT_TEXTURE,
            INTENT_CROSS_CHANNEL,
            INTENT_ALL
    };

    private static final IntensitySpatialConfig.AnalysisKey[] SELECTION_ANALYSES = {
            IntensitySpatialConfig.AnalysisKey.PATCHINESS,
            IntensitySpatialConfig.AnalysisKey.HOTSPOTSCAN,
            IntensitySpatialConfig.AnalysisKey.NULLMODEL,
            IntensitySpatialConfig.AnalysisKey.GRANULARITY,
            IntensitySpatialConfig.AnalysisKey.DEPTH_PROFILE,
            IntensitySpatialConfig.AnalysisKey.ANISOTROPY,
            IntensitySpatialConfig.AnalysisKey.CROSSMARK,
            IntensitySpatialConfig.AnalysisKey.ENTROPY_MI,
            IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL,
            IntensitySpatialConfig.AnalysisKey.PERIODICITY,
            IntensitySpatialConfig.AnalysisKey.GLCM,
            IntensitySpatialConfig.AnalysisKey.TEXTURECLASS,
            IntensitySpatialConfig.AnalysisKey.SCALEDIVERGENCE
    };

    private static final IntensitySpatialConfig.AnalysisKey[] NATIVE_3D_ANALYSES = {
            IntensitySpatialConfig.AnalysisKey.CROSSMARK_3D,
            IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL_3D,
            IntensitySpatialConfig.AnalysisKey.ANISOTROPY_3D
    };

    private final String[] channelNames;
    private final boolean[] channelBinarization;
    private final int likelyStackDepth;
    private final IntensitySpatialConfig initialConfig;
    private final DependencyActions dependencies;

    public IntensitySpatialWizard(MainPanelBinding panel,
                                  String[] channelNames,
                                  boolean[] channelBinarization,
                                  int likelyStackDepth,
                                  IntensitySpatialConfig initialConfig,
                                  boolean headless) {
        this(panel, channelNames, channelBinarization, likelyStackDepth, initialConfig,
                new ServiceDependencyActions(new DependencyService()), headless);
    }

    IntensitySpatialWizard(MainPanelBinding panel,
                           String[] channelNames,
                           boolean[] channelBinarization,
                           int likelyStackDepth,
                           IntensitySpatialConfig initialConfig,
                           DependencyActions dependencies,
                           boolean headless) {
        super("Intensity Spatial Helper", panel, headless);
        this.channelNames = channelNames == null ? new String[0] : channelNames.clone();
        this.channelBinarization = channelBinarization == null
                ? new boolean[0]
                : channelBinarization.clone();
        this.likelyStackDepth = likelyStackDepth;
        this.initialConfig = initialConfig == null ? IntensitySpatialConfig.disabled() : initialConfig;
        this.dependencies = dependencies == null
                ? new ServiceDependencyActions(new DependencyService())
                : dependencies;
        register(new SpatialIntentScreen());
        register(new SpatialAnalysisSelectionScreen());
        register(new SpatialZModeScreen());
        register(new Spatial3DScreen());
        register(new SpatialReviewScreen());
    }

    public IntensitySpatialConfig deriveCurrentConfig() {
        return deriveConfig(channelNames, channelBinarization, likelyStackDepth, currentAnswers());
    }

    public static IntensitySpatialConfig deriveConfig(String[] channelNames,
                                                      boolean[] channelBinarization,
                                                      int likelyStackDepth,
                                                      Map<String, Object> answers) {
        Map<String, Object> safeAnswers = answers == null
                ? Collections.<String, Object>emptyMap()
                : answers;
        String intent = answerString(safeAnswers, FIELD_INTENT, INTENT_NONE);
        EnumSet<IntensitySpatialConfig.AnalysisKey> analyses = EnumSet.noneOf(IntensitySpatialConfig.AnalysisKey.class);
        if (!INTENT_NONE.equals(intent)) {
            if (hasAnyAnalysisAnswer(safeAnswers)) {
                for (IntensitySpatialConfig.AnalysisKey key : SELECTION_ANALYSES) {
                    if (booleanAnswer(safeAnswers, fieldFor(key), false)) {
                        analyses.add(key);
                    }
                }
            } else {
                analyses.addAll(defaultAnalysesForIntent(intent,
                        safeChannelCount(channelNames, channelBinarization), channelBinarization));
            }
            for (IntensitySpatialConfig.AnalysisKey key : NATIVE_3D_ANALYSES) {
                if (booleanAnswer(safeAnswers, fieldFor(key), false)) {
                    analyses.add(key);
                }
            }
        }

        boolean enabled = !INTENT_NONE.equals(intent) && !analyses.isEmpty();
        boolean mipDefault = likelyStackDepth > 1;
        IntensitySpatialConfig config = IntensitySpatialConfig.builder()
                .enabled(enabled)
                .enabledAnalyses(enabled ? analyses : EnumSet.noneOf(IntensitySpatialConfig.AnalysisKey.class))
                .mipEnabled(enabled && booleanAnswer(safeAnswers, FIELD_MIP, mipDefault))
                .native3dEnabled(enabled && booleanAnswer(safeAnswers, FIELD_NATIVE_3D, false))
                .overlaysEnabled(enabled && booleanAnswer(safeAnswers, FIELD_OVERLAYS, false))
                .shellWidthUm(doubleAnswer(safeAnswers, FIELD_SHELL_WIDTH,
                        IntensitySpatialConfig.DEFAULT_SHELL_WIDTH_UM))
                .shellCount(intAnswer(safeAnswers, FIELD_SHELL_COUNT,
                        IntensitySpatialConfig.DEFAULT_SHELL_COUNT))
                .tileScalesUm(IntensitySpatialConfig.parseDoubleList(
                        answerString(safeAnswers, FIELD_TILE_SCALES,
                                IntensitySpatialConfig.joinDoubles(IntensitySpatialConfig.DEFAULT_TILE_SCALES_UM)),
                        IntensitySpatialConfig.DEFAULT_TILE_SCALES_UM))
                .granularityScalesUm(IntensitySpatialConfig.parseDoubleList(
                        answerString(safeAnswers, FIELD_GRANULARITY_SCALES,
                                IntensitySpatialConfig.joinDoubles(IntensitySpatialConfig.DEFAULT_GRANULARITY_SCALES_UM)),
                        IntensitySpatialConfig.DEFAULT_GRANULARITY_SCALES_UM))
                .depthBinWidthUm(doubleAnswer(safeAnswers, FIELD_DEPTH_BIN,
                        IntensitySpatialConfig.DEFAULT_DEPTH_BIN_WIDTH_UM))
                .rimDepthUm(doubleAnswer(safeAnswers, FIELD_RIM_DEPTH,
                        IntensitySpatialConfig.DEFAULT_RIM_DEPTH_UM))
                .textureClassCount(intAnswer(safeAnswers, FIELD_TEXTURE_CLASSES,
                        IntensitySpatialConfig.DEFAULT_TEXTURE_CLASS_COUNT))
                .permutations(intAnswer(safeAnswers, FIELD_PERMUTATIONS,
                        IntensitySpatialConfig.DEFAULT_PERMUTATIONS))
                .seed(longAnswer(safeAnswers, FIELD_SEED, IntensitySpatialConfig.DEFAULT_SEED))
                .failurePolicy(IntensitySpatialConfig.FailurePolicy.SKIP_FAILED_ANALYSIS)
                .build();

        return config.validateForChannelSetup(
                safeChannelCount(channelNames, channelBinarization),
                channelBinarization,
                likelyStackDepth > 0 ? Integer.valueOf(likelyStackDepth) : null,
                null);
    }

    static DependencyId requiredDependency(IntensitySpatialConfig.AnalysisKey key) {
        if (key == null) return null;
        switch (key) {
            case PATCHINESS:
            case HOTSPOTSCAN:
            case PERIODICITY:
                return DependencyId.IMGLIB2_FFT_RUNTIME;
            case GRANULARITY:
            case DEPTH_PROFILE:
            case TEXTURECLASS:
                return DependencyId.IMGLIB2_ALGORITHM_RUNTIME;
            case CROSSMARK:
            case CROSSMARK_3D:
                return DependencyId.COLOC2_RUNTIME;
            case DISTANCE_SHELL:
                return DependencyId.IMGLIB2_ALGORITHM_RUNTIME;
            case ENTROPY_MI:
                return DependencyId.JTRANSFORMS_RUNTIME;
            case DISTANCE_SHELL_3D:
                return DependencyId.MCIB3D_CORE;
            case ANISOTROPY_3D:
                return DependencyId.ORIENTATIONJ_RUNTIME;
            default:
                return null;
        }
    }

    Availability availabilityFor(IntensitySpatialConfig.AnalysisKey key) {
        if (key == null) return Availability.available();
        if (key.isCrossChannel() && channelNames.length < 2) {
            return Availability.locked("Requires at least two channels.");
        }
        if ((key == IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL
                || key == IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL_3D)
                && !hasAnyBinarizedChannel(channelBinarization)) {
            return Availability.locked("Requires at least one binarized partner channel. Go Back to enable binarization.");
        }
        if (key.isNative3d() && likelyStackDepth < IntensitySpatialConfig.MIN_NATIVE_3D_SLICES) {
            return Availability.locked("Requires native 3D with at least "
                    + IntensitySpatialConfig.MIN_NATIVE_3D_SLICES + " z-slices.");
        }

        DependencyId dependencyId = requiredDependency(key);
        if (dependencyId == null) {
            return Availability.available();
        }
        DependencyStatus status = dependencies.getStatus(dependencyId);
        if (status != null && status.isPresent()) {
            return Availability.available();
        }
        return Availability.missingDependency(dependencyId,
                "Install " + dependencyDisplayName(dependencyId) + " to enable.");
    }

    private String initialIntent() {
        if (initialConfig == null || !initialConfig.isEnabled() || initialConfig.getEnabledAnalyses().isEmpty()) {
            return INTENT_NONE;
        }
        Set<IntensitySpatialConfig.AnalysisKey> analyses = initialConfig.getEnabledAnalyses();
        if (analyses.contains(IntensitySpatialConfig.AnalysisKey.CROSSMARK)
                || analyses.contains(IntensitySpatialConfig.AnalysisKey.ENTROPY_MI)
                || analyses.contains(IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL)) {
            return analyses.size() <= 3 ? INTENT_CROSS_CHANNEL : INTENT_ALL;
        }
        if (analyses.contains(IntensitySpatialConfig.AnalysisKey.HOTSPOTSCAN)) return INTENT_HOTSPOTS;
        if (analyses.contains(IntensitySpatialConfig.AnalysisKey.DEPTH_PROFILE)) return INTENT_DEPTH;
        if (analyses.contains(IntensitySpatialConfig.AnalysisKey.ANISOTROPY)) return INTENT_ALIGNMENT;
        if (analyses.contains(IntensitySpatialConfig.AnalysisKey.GRANULARITY)
                || analyses.contains(IntensitySpatialConfig.AnalysisKey.GLCM)
                || analyses.contains(IntensitySpatialConfig.AnalysisKey.TEXTURECLASS)
                || analyses.contains(IntensitySpatialConfig.AnalysisKey.SCALEDIVERGENCE)) {
            return INTENT_TEXTURE;
        }
        return INTENT_DISTRIBUTION;
    }

    private boolean initialAnalysis(IntensitySpatialConfig.AnalysisKey key) {
        return initialConfig != null && initialConfig.getEnabledAnalyses().contains(key);
    }

    private boolean initialMipDefault() {
        return initialConfig != null && initialConfig.hasConfiguration()
                ? initialConfig.isMipEnabled()
                : likelyStackDepth > 1;
    }

    private boolean initialNative3dDefault() {
        return initialConfig != null
                && initialConfig.isNative3dEnabled()
                && likelyStackDepth >= IntensitySpatialConfig.MIN_NATIVE_3D_SLICES;
    }

    private void seedSelectionFromIntent(AnswerMap answers) {
        String intent = answers.getString(FIELD_INTENT, INTENT_NONE);
        String seededIntent = answers.getString(FIELD_SELECTION_INTENT, "");
        if (intent.equals(seededIntent)) {
            return;
        }
        Set<IntensitySpatialConfig.AnalysisKey> defaults =
                defaultAnalysesForIntent(intent, channelNames.length, channelBinarization);
        for (IntensitySpatialConfig.AnalysisKey key : SELECTION_ANALYSES) {
            answers.put(fieldFor(key), Boolean.valueOf(defaults.contains(key)));
        }
        for (IntensitySpatialConfig.AnalysisKey key : NATIVE_3D_ANALYSES) {
            answers.put(fieldFor(key), Boolean.FALSE);
        }
        answers.put(FIELD_MIP, Boolean.valueOf(likelyStackDepth > 1));
        answers.put(FIELD_NATIVE_3D, Boolean.FALSE);
        answers.put(FIELD_SELECTION_INTENT, intent);
    }

    private ToggleSwitch addAnalysisToggle(final PipelineDialog dialog,
                                           final IntensitySpatialConfig.AnalysisKey key,
                                           String label,
                                           boolean selected) {
        Availability availability = availabilityFor(key);
        ToggleSwitch toggle = dialog.addToggle(label, availability.available && selected);
        if (!availability.available) {
            toggle.setSelected(false);
            toggle.setEnabled(false);
            JLabel help = dialog.addHelpText(availability.reason);
            if (availability.dependencyId != null) {
                final JButton install = dialog.addButton("Install " + dependencyDisplayName(availability.dependencyId));
                install.addActionListener(e -> {
                    DependencyFixResult result = dependencies.runDialogAction(
                            availability.dependencyId, DependencyService.DialogAction.AUTO_FIX);
                    DependencyStatus after = dependencies.getStatus(availability.dependencyId);
                    if (after != null && after.isPresent()) {
                        toggle.setEnabled(true);
                        install.setEnabled(false);
                        help.setText("<html><body width='280'>Dependency installed. You can enable this option now.</body></html>");
                    } else {
                        String message = result == null || result.getMessage().trim().isEmpty()
                                ? "Dependency is still unavailable."
                                : result.getMessage().trim();
                        help.setText("<html><body width='280'>" + html(message)
                                + "<br>Basic intensity setup can continue.</body></html>");
                    }
                });
            }
        }
        return toggle;
    }

    private static Set<IntensitySpatialConfig.AnalysisKey> defaultAnalysesForIntent(
            String intent,
            int channelCount,
            boolean[] channelBinarization) {
        EnumSet<IntensitySpatialConfig.AnalysisKey> out =
                EnumSet.noneOf(IntensitySpatialConfig.AnalysisKey.class);
        if (INTENT_DISTRIBUTION.equals(intent)) {
            out.add(IntensitySpatialConfig.AnalysisKey.PATCHINESS);
            out.add(IntensitySpatialConfig.AnalysisKey.NULLMODEL);
        } else if (INTENT_HOTSPOTS.equals(intent)) {
            out.add(IntensitySpatialConfig.AnalysisKey.HOTSPOTSCAN);
            out.add(IntensitySpatialConfig.AnalysisKey.NULLMODEL);
        } else if (INTENT_DEPTH.equals(intent)) {
            out.add(IntensitySpatialConfig.AnalysisKey.DEPTH_PROFILE);
        } else if (INTENT_ALIGNMENT.equals(intent)) {
            out.add(IntensitySpatialConfig.AnalysisKey.ANISOTROPY);
        } else if (INTENT_TEXTURE.equals(intent)) {
            out.add(IntensitySpatialConfig.AnalysisKey.GRANULARITY);
        } else if (INTENT_CROSS_CHANNEL.equals(intent)) {
            addCrossChannelDefaults(out, channelCount, channelBinarization);
        } else if (INTENT_ALL.equals(intent)) {
            out.add(IntensitySpatialConfig.AnalysisKey.PATCHINESS);
            out.add(IntensitySpatialConfig.AnalysisKey.HOTSPOTSCAN);
            out.add(IntensitySpatialConfig.AnalysisKey.NULLMODEL);
            out.add(IntensitySpatialConfig.AnalysisKey.GRANULARITY);
            out.add(IntensitySpatialConfig.AnalysisKey.DEPTH_PROFILE);
            out.add(IntensitySpatialConfig.AnalysisKey.ANISOTROPY);
            addCrossChannelDefaults(out, channelCount, channelBinarization);
        }
        return Collections.unmodifiableSet(out);
    }

    private static void addCrossChannelDefaults(EnumSet<IntensitySpatialConfig.AnalysisKey> out,
                                                int channelCount,
                                                boolean[] channelBinarization) {
        if (channelCount < 2) return;
        out.add(IntensitySpatialConfig.AnalysisKey.CROSSMARK);
        out.add(IntensitySpatialConfig.AnalysisKey.ENTROPY_MI);
        if (hasAnyBinarizedChannel(channelBinarization)) {
            out.add(IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL);
        }
    }

    private static boolean hasAnyAnalysisAnswer(Map<String, Object> answers) {
        if (answers == null) return false;
        for (IntensitySpatialConfig.AnalysisKey key : SELECTION_ANALYSES) {
            if (answers.containsKey(fieldFor(key))) return true;
        }
        for (IntensitySpatialConfig.AnalysisKey key : NATIVE_3D_ANALYSES) {
            if (answers.containsKey(fieldFor(key))) return true;
        }
        return false;
    }

    private static String fieldFor(IntensitySpatialConfig.AnalysisKey key) {
        return "intensity.spatial.analysis." + key.token();
    }

    private static int safeChannelCount(String[] channelNames, boolean[] channelBinarization) {
        if (channelNames != null && channelNames.length > 0) return channelNames.length;
        return channelBinarization == null ? 0 : channelBinarization.length;
    }

    private static boolean hasAnyBinarizedChannel(boolean[] binarization) {
        if (binarization == null) return false;
        for (boolean value : binarization) {
            if (value) return true;
        }
        return false;
    }

    private static String answerString(Map<String, Object> answers, String key, String fallback) {
        Object value = answers == null ? null : answers.get(key);
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

    private static long longAnswer(Map<String, Object> answers, String key, long fallback) {
        Object value = answers == null ? null : answers.get(key);
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null) return fallback;
        try {
            return Long.parseLong(String.valueOf(value).trim());
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

    private static String labelFor(IntensitySpatialConfig.AnalysisKey key) {
        switch (key) {
            case PATCHINESS: return "Patchiness and lacunarity";
            case HOTSPOTSCAN: return "Hotspot scan";
            case NULLMODEL: return "Permutation null model";
            case GRANULARITY: return "Granularity across scales";
            case DEPTH_PROFILE: return "Depth profile from ROI boundary";
            case ANISOTROPY: return "2D alignment / anisotropy";
            case PERIODICITY: return "Periodicity";
            case GLCM: return "GLCM texture metrics";
            case TEXTURECLASS: return "Texture classes";
            case SCALEDIVERGENCE: return "Scale divergence";
            case CROSSMARK: return "Cross-channel correlation and mark correlation";
            case ENTROPY_MI: return "Cross-channel mutual information";
            case DISTANCE_SHELL: return "Intensity around partner-channel mask shells";
            case CROSSMARK_3D: return "Native 3D cross-channel correlation";
            case DISTANCE_SHELL_3D: return "Native 3D distance shells";
            case ANISOTROPY_3D: return "Native 3D anisotropy";
            default: return key.name();
        }
    }

    private static String dependencyDisplayName(DependencyId id) {
        DependencySpec spec = DependencyRegistry.get(id);
        return spec == null ? id.name() : spec.getDisplayName();
    }

    private static String analysisList(Set<IntensitySpatialConfig.AnalysisKey> analyses) {
        if (analyses == null || analyses.isEmpty()) return "None";
        StringBuilder sb = new StringBuilder();
        for (IntensitySpatialConfig.AnalysisKey key : analyses) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(labelFor(key));
        }
        return sb.toString();
    }

    private static String expectedFiles(IntensitySpatialConfig config, String[] channelNames) {
        if (channelNames == null || channelNames.length == 0) return "Base intensity CSVs.";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < channelNames.length; i++) {
            if (i > 0) sb.append(", ");
            String name = channelNames[i] == null || channelNames[i].trim().isEmpty()
                    ? "Channel " + (i + 1)
                    : channelNames[i].trim();
            sb.append(name).append(".csv");
        }
        if (config != null && config.isEnabled() && config.isMipEnabled()) {
            sb.append("; MIP CSVs for selected spatial analyses");
        }
        if (config != null && config.isEnabled() && config.isNative3dEnabled()) {
            sb.append("; native 3D CSVs for selected 3D analyses");
        }
        return sb.toString();
    }

    private static String costClass(IntensitySpatialConfig config) {
        if (config == null || !config.isEnabled() || config.getEnabledAnalyses().isEmpty()) return "Basic intensity only";
        Set<IntensitySpatialConfig.AnalysisKey> analyses = config.getEnabledAnalyses();
        if (config.isNative3dEnabled()
                || analyses.contains(IntensitySpatialConfig.AnalysisKey.PERIODICITY)
                || analyses.contains(IntensitySpatialConfig.AnalysisKey.TEXTURECLASS)
                || analyses.contains(IntensitySpatialConfig.AnalysisKey.SCALEDIVERGENCE)) {
            return "High";
        }
        if (config.isMipEnabled()
                || analyses.contains(IntensitySpatialConfig.AnalysisKey.CROSSMARK)
                || analyses.contains(IntensitySpatialConfig.AnalysisKey.ENTROPY_MI)
                || analyses.contains(IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL)
                || analyses.contains(IntensitySpatialConfig.AnalysisKey.HOTSPOTSCAN)) {
            return "Medium";
        }
        return "Low";
    }

    private static String dependencySummary(IntensitySpatialConfig config) {
        if (config == null || config.getEnabledAnalyses().isEmpty()) return "No optional spatial dependencies selected.";
        StringBuilder sb = new StringBuilder();
        EnumSet<DependencyId> seen = EnumSet.noneOf(DependencyId.class);
        for (IntensitySpatialConfig.AnalysisKey key : config.getEnabledAnalyses()) {
            DependencyId id = requiredDependency(key);
            if (id == null || !seen.add(id)) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(dependencyDisplayName(id));
        }
        return sb.length() == 0 ? "No optional spatial dependencies selected." : sb.toString();
    }

    private static String html(String value) {
        String safe = value == null ? "" : value;
        safe = safe.replace("&", "&amp;");
        safe = safe.replace("<", "&lt;");
        safe = safe.replace(">", "&gt;");
        return safe.replace("\n", "<br>");
    }

    interface DependencyActions {
        DependencyStatus getStatus(DependencyId id);

        DependencyFixResult runDialogAction(DependencyId id, String actionId);
    }

    static final class Availability {
        final boolean available;
        final String reason;
        final DependencyId dependencyId;

        private Availability(boolean available, String reason, DependencyId dependencyId) {
            this.available = available;
            this.reason = reason == null ? "" : reason;
            this.dependencyId = dependencyId;
        }

        static Availability available() {
            return new Availability(true, "", null);
        }

        static Availability locked(String reason) {
            return new Availability(false, reason, null);
        }

        static Availability missingDependency(DependencyId id, String reason) {
            return new Availability(false, reason, id);
        }
    }

    private static final class ServiceDependencyActions implements DependencyActions {
        private final DependencyService service;

        private ServiceDependencyActions(DependencyService service) {
            this.service = service == null ? new DependencyService() : service;
        }

        public DependencyStatus getStatus(DependencyId id) {
            return service.getStatus(id);
        }

        public DependencyFixResult runDialogAction(DependencyId id, String actionId) {
            return service.runDialogAction(id, actionId);
        }
    }

    private final class SpatialIntentScreen extends Screen {
        private SpatialIntentScreen() {
            super("What spatial intensity question are you asking?");
            String intent = initialIntent();
            defaultAnswer(FIELD_INTENT, intent);
            defaultAnswer(FIELD_SELECTION_INTENT, intent);
        }

        public void build(PipelineDialog dialog, AnswerMap answers) {
            dialog.addHeader("What spatial intensity question are you asking?");
            dialog.addChoice("Spatial intensity question", INTENT_OPTIONS,
                    answers.getString(FIELD_INTENT, initialIntent()));
        }

        public void read(PipelineDialog dialog, AnswerMap answers) {
            answers.put(FIELD_INTENT, dialog.getNextChoice());
        }

        public void writeTo(MainPanelBinding panel, AnswerMap answers) {
            panel.setValue("intensity.spatial.config", deriveConfig(
                    channelNames, channelBinarization, likelyStackDepth, answers));
        }
    }

    private final class SpatialAnalysisSelectionScreen extends Screen {
        private SpatialAnalysisSelectionScreen() {
            super("Choose intensity-spatial analyses");
            for (IntensitySpatialConfig.AnalysisKey key : SELECTION_ANALYSES) {
                defaultAnswer(fieldFor(key), Boolean.valueOf(initialAnalysis(key)));
            }
            defaultAnswer(FIELD_OVERLAYS, Boolean.valueOf(initialConfig.isOverlaysEnabled()));
            defaultAnswer(FIELD_SHELL_WIDTH, Double.valueOf(initialConfig.getShellWidthUm()));
            defaultAnswer(FIELD_SHELL_COUNT, Integer.valueOf(initialConfig.getShellCount()));
            defaultAnswer(FIELD_TILE_SCALES, IntensitySpatialConfig.joinDoubles(initialConfig.getTileScalesUm()));
            defaultAnswer(FIELD_GRANULARITY_SCALES,
                    IntensitySpatialConfig.joinDoubles(initialConfig.getGranularityScalesUm()));
            defaultAnswer(FIELD_DEPTH_BIN, Double.valueOf(initialConfig.getDepthBinWidthUm()));
            defaultAnswer(FIELD_RIM_DEPTH, Double.valueOf(initialConfig.getRimDepthUm()));
            defaultAnswer(FIELD_TEXTURE_CLASSES, Integer.valueOf(initialConfig.getTextureClassCount()));
            defaultAnswer(FIELD_PERMUTATIONS, Integer.valueOf(initialConfig.getPermutations()));
            defaultAnswer(FIELD_SEED, Long.valueOf(initialConfig.getSeed()));
        }

        public boolean isApplicable(AnswerMap prior) {
            return !INTENT_NONE.equals(prior.getString(FIELD_INTENT, INTENT_NONE));
        }

        public void build(PipelineDialog dialog, AnswerMap answers) {
            seedSelectionFromIntent(answers);
            dialog.addHeader("Choose intensity-spatial analyses");
            dialog.addSubHeader("Single-channel distribution");
            addAnalysisToggle(dialog, IntensitySpatialConfig.AnalysisKey.PATCHINESS,
                    labelFor(IntensitySpatialConfig.AnalysisKey.PATCHINESS),
                    answers.getBoolean(fieldFor(IntensitySpatialConfig.AnalysisKey.PATCHINESS), false));
            addAnalysisToggle(dialog, IntensitySpatialConfig.AnalysisKey.HOTSPOTSCAN,
                    labelFor(IntensitySpatialConfig.AnalysisKey.HOTSPOTSCAN),
                    answers.getBoolean(fieldFor(IntensitySpatialConfig.AnalysisKey.HOTSPOTSCAN), false));
            addAnalysisToggle(dialog, IntensitySpatialConfig.AnalysisKey.NULLMODEL,
                    labelFor(IntensitySpatialConfig.AnalysisKey.NULLMODEL),
                    answers.getBoolean(fieldFor(IntensitySpatialConfig.AnalysisKey.NULLMODEL), false));

            dialog.addSubHeader("Depth and structure");
            addAnalysisToggle(dialog, IntensitySpatialConfig.AnalysisKey.GRANULARITY,
                    labelFor(IntensitySpatialConfig.AnalysisKey.GRANULARITY),
                    answers.getBoolean(fieldFor(IntensitySpatialConfig.AnalysisKey.GRANULARITY), false));
            addAnalysisToggle(dialog, IntensitySpatialConfig.AnalysisKey.DEPTH_PROFILE,
                    labelFor(IntensitySpatialConfig.AnalysisKey.DEPTH_PROFILE),
                    answers.getBoolean(fieldFor(IntensitySpatialConfig.AnalysisKey.DEPTH_PROFILE), false));
            addAnalysisToggle(dialog, IntensitySpatialConfig.AnalysisKey.ANISOTROPY,
                    labelFor(IntensitySpatialConfig.AnalysisKey.ANISOTROPY),
                    answers.getBoolean(fieldFor(IntensitySpatialConfig.AnalysisKey.ANISOTROPY), false));

            dialog.addSubHeader("Cross-channel");
            addAnalysisToggle(dialog, IntensitySpatialConfig.AnalysisKey.CROSSMARK,
                    labelFor(IntensitySpatialConfig.AnalysisKey.CROSSMARK),
                    answers.getBoolean(fieldFor(IntensitySpatialConfig.AnalysisKey.CROSSMARK), false));
            addAnalysisToggle(dialog, IntensitySpatialConfig.AnalysisKey.ENTROPY_MI,
                    labelFor(IntensitySpatialConfig.AnalysisKey.ENTROPY_MI),
                    answers.getBoolean(fieldFor(IntensitySpatialConfig.AnalysisKey.ENTROPY_MI), false));
            addAnalysisToggle(dialog, IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL,
                    labelFor(IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL),
                    answers.getBoolean(fieldFor(IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL), false));

            dialog.beginAdvancedSection("intensity.spatial.advanced");
            dialog.addSubHeader("Advanced analysis families");
            addAnalysisToggle(dialog, IntensitySpatialConfig.AnalysisKey.PERIODICITY,
                    labelFor(IntensitySpatialConfig.AnalysisKey.PERIODICITY),
                    answers.getBoolean(fieldFor(IntensitySpatialConfig.AnalysisKey.PERIODICITY), false));
            addAnalysisToggle(dialog, IntensitySpatialConfig.AnalysisKey.GLCM,
                    labelFor(IntensitySpatialConfig.AnalysisKey.GLCM),
                    answers.getBoolean(fieldFor(IntensitySpatialConfig.AnalysisKey.GLCM), false));
            addAnalysisToggle(dialog, IntensitySpatialConfig.AnalysisKey.TEXTURECLASS,
                    labelFor(IntensitySpatialConfig.AnalysisKey.TEXTURECLASS),
                    answers.getBoolean(fieldFor(IntensitySpatialConfig.AnalysisKey.TEXTURECLASS), false));
            addAnalysisToggle(dialog, IntensitySpatialConfig.AnalysisKey.SCALEDIVERGENCE,
                    labelFor(IntensitySpatialConfig.AnalysisKey.SCALEDIVERGENCE),
                    answers.getBoolean(fieldFor(IntensitySpatialConfig.AnalysisKey.SCALEDIVERGENCE), false));

            dialog.addSubHeader("Parameters");
            dialog.addNumericField("Shell width (um)", doubleAnswer(answers, FIELD_SHELL_WIDTH,
                    IntensitySpatialConfig.DEFAULT_SHELL_WIDTH_UM), 1);
            dialog.addNumericField("Shell count", answers.getInt(FIELD_SHELL_COUNT,
                    IntensitySpatialConfig.DEFAULT_SHELL_COUNT), 0);
            dialog.addStringField("Tile scales (um)", answers.getString(FIELD_TILE_SCALES,
                    IntensitySpatialConfig.joinDoubles(IntensitySpatialConfig.DEFAULT_TILE_SCALES_UM)), 18);
            dialog.addStringField("Granularity scales (um)", answers.getString(FIELD_GRANULARITY_SCALES,
                    IntensitySpatialConfig.joinDoubles(IntensitySpatialConfig.DEFAULT_GRANULARITY_SCALES_UM)), 18);
            dialog.addNumericField("Depth bin width (um)", doubleAnswer(answers, FIELD_DEPTH_BIN,
                    IntensitySpatialConfig.DEFAULT_DEPTH_BIN_WIDTH_UM), 1);
            dialog.addNumericField("Rim depth (um)", doubleAnswer(answers, FIELD_RIM_DEPTH,
                    IntensitySpatialConfig.DEFAULT_RIM_DEPTH_UM), 1);
            dialog.addNumericField("Texture classes", answers.getInt(FIELD_TEXTURE_CLASSES,
                    IntensitySpatialConfig.DEFAULT_TEXTURE_CLASS_COUNT), 0);
            dialog.addNumericField("Permutation count", answers.getInt(FIELD_PERMUTATIONS,
                    IntensitySpatialConfig.DEFAULT_PERMUTATIONS), 0);
            dialog.addNumericField("Random seed", longAnswer(answers, FIELD_SEED,
                    IntensitySpatialConfig.DEFAULT_SEED), 0);
            dialog.endAdvancedSection();
        }

        public void read(PipelineDialog dialog, AnswerMap answers) {
            for (IntensitySpatialConfig.AnalysisKey key : SELECTION_ANALYSES) {
                answers.put(fieldFor(key), Boolean.valueOf(dialog.getNextBoolean()));
            }
            answers.put(FIELD_SHELL_WIDTH, Double.valueOf(dialog.getNextNumber()));
            answers.put(FIELD_SHELL_COUNT, Integer.valueOf((int) dialog.getNextNumber()));
            answers.put(FIELD_DEPTH_BIN, Double.valueOf(dialog.getNextNumber()));
            answers.put(FIELD_RIM_DEPTH, Double.valueOf(dialog.getNextNumber()));
            answers.put(FIELD_TEXTURE_CLASSES, Integer.valueOf((int) dialog.getNextNumber()));
            answers.put(FIELD_PERMUTATIONS, Integer.valueOf((int) dialog.getNextNumber()));
            answers.put(FIELD_SEED, Long.valueOf((long) dialog.getNextNumber()));
            answers.put(FIELD_TILE_SCALES, dialog.getNextString());
            answers.put(FIELD_GRANULARITY_SCALES, dialog.getNextString());
            answers.put(FIELD_SELECTION_INTENT, answers.getString(FIELD_INTENT, INTENT_NONE));
        }

        public void writeTo(MainPanelBinding panel, AnswerMap answers) {
        }
    }

    private final class SpatialZModeScreen extends Screen {
        private SpatialZModeScreen() {
            super("Choose z-stack spatial outputs");
            defaultAnswer(FIELD_MIP, Boolean.valueOf(initialMipDefault()));
            defaultAnswer(FIELD_NATIVE_3D, Boolean.valueOf(initialNative3dDefault()));
        }

        public boolean isApplicable(AnswerMap prior) {
            return deriveConfig(channelNames, channelBinarization, likelyStackDepth, prior).isEnabled();
        }

        public void build(PipelineDialog dialog, AnswerMap answers) {
            dialog.addHeader("Choose z-stack spatial outputs");
            ToggleSwitch mip = dialog.addToggle("MIP spatial measurements", answers.getBoolean(FIELD_MIP, likelyStackDepth > 1));
            if (likelyStackDepth <= 1) {
                mip.setSelected(false);
                mip.setEnabled(false);
                dialog.addHelpText("MIP output requires a z-stack with more than one slice.");
            }
            ToggleSwitch native3d = dialog.addToggle("Native 3D spatial measurements",
                    answers.getBoolean(FIELD_NATIVE_3D, false));
            if (likelyStackDepth < IntensitySpatialConfig.MIN_NATIVE_3D_SLICES) {
                native3d.setSelected(false);
                native3d.setEnabled(false);
                dialog.addHelpText("Native 3D output requires at least "
                        + IntensitySpatialConfig.MIN_NATIVE_3D_SLICES + " z-slices.");
            }
            dialog.addToggle("Write visual overlays", answers.getBoolean(FIELD_OVERLAYS, false));
        }

        public void read(PipelineDialog dialog, AnswerMap answers) {
            answers.put(FIELD_MIP, Boolean.valueOf(dialog.getNextBoolean()));
            answers.put(FIELD_NATIVE_3D, Boolean.valueOf(dialog.getNextBoolean()));
            answers.put(FIELD_OVERLAYS, Boolean.valueOf(dialog.getNextBoolean()));
        }

        public void writeTo(MainPanelBinding panel, AnswerMap answers) {
        }
    }

    private final class Spatial3DScreen extends Screen {
        private Spatial3DScreen() {
            super("Choose native 3D analyses");
            for (IntensitySpatialConfig.AnalysisKey key : NATIVE_3D_ANALYSES) {
                defaultAnswer(fieldFor(key), Boolean.valueOf(initialAnalysis(key)));
            }
        }

        public boolean isApplicable(AnswerMap prior) {
            return deriveConfig(channelNames, channelBinarization, likelyStackDepth, prior).isNative3dEnabled();
        }

        public void build(PipelineDialog dialog, AnswerMap answers) {
            dialog.addHeader("Choose native 3D analyses");
            for (IntensitySpatialConfig.AnalysisKey key : NATIVE_3D_ANALYSES) {
                addAnalysisToggle(dialog, key, labelFor(key), answers.getBoolean(fieldFor(key), false));
            }
        }

        public void read(PipelineDialog dialog, AnswerMap answers) {
            for (IntensitySpatialConfig.AnalysisKey key : NATIVE_3D_ANALYSES) {
                answers.put(fieldFor(key), Boolean.valueOf(dialog.getNextBoolean()));
            }
        }

        public void writeTo(MainPanelBinding panel, AnswerMap answers) {
        }
    }

    private final class SpatialReviewScreen extends Screen {
        private SpatialReviewScreen() {
            super("Review intensity-spatial setup");
        }

        public boolean isApplicable(AnswerMap prior) {
            return deriveConfig(channelNames, channelBinarization, likelyStackDepth, prior).isEnabled();
        }

        public void build(PipelineDialog dialog, AnswerMap answers) {
            IntensitySpatialConfig config = deriveConfig(channelNames, channelBinarization, likelyStackDepth, answers);
            dialog.addHeader("Review intensity-spatial setup");
            dialog.addMessage("Selected analyses: " + analysisList(config.getEnabledAnalyses()));
            dialog.addMessage("Expected output files: " + expectedFiles(config, channelNames));
            dialog.addMessage("Dependency gates: " + dependencySummary(config));
            dialog.addMessage("Estimated cost: " + costClass(config));
        }

        public void read(PipelineDialog dialog, AnswerMap answers) {
        }

        public void writeTo(MainPanelBinding panel, AnswerMap answers) {
            panel.setValue("intensity.spatial.config", deriveConfig(
                    channelNames, channelBinarization, likelyStackDepth, answers));
        }
    }
}
