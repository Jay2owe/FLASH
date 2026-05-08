package flash.pipeline.analyses.wizard;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.ChannelIdentities;
import flash.pipeline.marker.MarkerLibrary;
import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.wizard.WizardFlow;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Intent-led setup helper for 3D Object Analysis.
 */
public class ThreeDObjectWizard extends WizardFlow {

    public static final String STRICTNESS_LOOSE = "Loose (10%)";
    public static final String STRICTNESS_STANDARD = "Standard (30%)";
    public static final String STRICTNESS_STRICT = "Strict (60%)";

    private final BinConfig binConfig;
    private final ChannelIdentities identities;
    private final List<String> roiSetNames;
    private final SpatialWizardLauncher spatialLauncher;

    public interface SpatialWizardLauncher {
        void launch(DerivedConfig config);
    }

    public ThreeDObjectWizard(MainPanelBinding panel,
                              BinConfig binConfig,
                              ChannelIdentities identities,
                              List<String> roiSetNames,
                              boolean headless) {
        this(panel, binConfig, identities, roiSetNames, headless, null);
    }

    public ThreeDObjectWizard(MainPanelBinding panel,
                              BinConfig binConfig,
                              ChannelIdentities identities,
                              List<String> roiSetNames,
                              boolean headless,
                              SpatialWizardLauncher spatialLauncher) {
        super("3D Object Setup Helper", panel, headless);
        this.binConfig = binConfig == null ? new BinConfig() : binConfig;
        this.identities = identities == null ? new ChannelIdentities(null) : identities;
        this.roiSetNames = roiSetNames == null ? Collections.<String>emptyList() : new ArrayList<String>(roiSetNames);
        this.spatialLauncher = spatialLauncher;
        register(new IntentScreen());
        register(new PairScreen());
        register(new StrictnessScreen());
        register(new ProcessScreen());
        register(new CentroidFilterScreen());
    }

    public DerivedConfig runAndMaybeLaunchSpatial() {
        run();
        if (!wasFinished()) {
            return null;
        }
        DerivedConfig config = deriveCurrentConfig();
        if (config.runSpatial && spatialLauncher != null) {
            spatialLauncher.launch(config);
        }
        return config;
    }

    public DerivedConfig deriveCurrentConfig() {
        return deriveConfig(binConfig, identities, currentAnswers(), roiSetNames);
    }

    public static DerivedConfig deriveConfig(BinConfig cfg,
                                             ChannelIdentities identities,
                                             Map<String, Object> answers,
                                             List<String> roiSetNames) {
        BinConfig safeCfg = cfg == null ? new BinConfig() : cfg;
        ChannelIdentities safeIdentities = identities == null ? new ChannelIdentities(null) : identities;
        DerivedConfig out = new DerivedConfig(safeCfg.numChannels());

        boolean coloc = booleanAnswer(answers, "intent.coloc", false);
        boolean process = booleanAnswer(answers, "intent.process", false);
        boolean spatial = booleanAnswer(answers, "intent.spatial", false);
        out.doVolumetric = coloc;
        out.doCpc = coloc;
        out.extractProcessLength = process;
        out.runSpatial = spatial;
        out.thresholdPercent = thresholdForStrictness(answerString(answers, "coloc.strictness",
                defaultStrictness(safeIdentities)));
        for (String channelName : safeCfg.channelNames) {
            out.markerThresholds.put(channelName, Double.valueOf(out.thresholdPercent));
        }

        for (int i = 0; i < safeCfg.numChannels(); i++) {
            for (int j = i + 1; j < safeCfg.numChannels(); j++) {
                String pair = pairKey(i, j);
                if (booleanAnswer(answers, "pair." + pair, true)) {
                    out.primaryPairs.add(pair);
                }
            }
        }

        out.nuclearMarkerIndex = intAnswer(answers, "process.nuclearMarkerIndex",
                detectNuclearMarkerIndex(safeIdentities, safeCfg.numChannels()));
        for (int c = 0; c < safeCfg.numChannels(); c++) {
            out.processChannels[c] = booleanAnswer(answers, "process.channel" + (c + 1),
                    isProcessChannel(safeIdentities.findByChannelIndex(c)));
        }

        out.classicalCentroidFiltering = booleanAnswer(answers, "centroid.classical", true);
        return out;
    }

    public static DerivedConfig fromPreset(BinConfig cfg,
                                           ChannelIdentities identities,
                                           ThreeDObjectPreset preset) {
        BinConfig safeCfg = cfg == null ? new BinConfig() : cfg;
        ChannelIdentities safeIdentities = identities == null ? new ChannelIdentities(null) : identities;
        ThreeDObjectPreset safePreset = preset == null
                ? new ThreeDObjectPreset("Count only", null, "1", false, false,
                false, false, true, 30.0, null, null)
                : preset;
        DerivedConfig out = new DerivedConfig(safeCfg.numChannels());
        out.doVolumetric = safePreset.isDoVolumetric();
        out.doCpc = safePreset.isDoCpc();
        out.extractProcessLength = safePreset.isExtractProcessLength();
        out.runSpatial = safePreset.isRunSpatial();
        out.classicalCentroidFiltering = safePreset.isClassicalCentroidFiltering();
        out.thresholdPercent = safePreset.getColocThresholdPercent();
        for (String channelName : safeCfg.channelNames) {
            out.markerThresholds.put(channelName, Double.valueOf(out.thresholdPercent));
        }
        for (int i = 0; i < safeCfg.numChannels(); i++) {
            for (int j = i + 1; j < safeCfg.numChannels(); j++) {
                out.primaryPairs.add(pairKey(i, j));
            }
        }
        out.nuclearMarkerIndex = detectMarkerHintIndex(safeIdentities,
                safePreset.getNuclearMarkerHints(), detectNuclearMarkerIndex(safeIdentities, safeCfg.numChannels()));
        for (int c = 0; c < safeCfg.numChannels(); c++) {
            ChannelIdentities.Entry entry = safeIdentities.findByChannelIndex(c);
            out.processChannels[c] = safePreset.isExtractProcessLength()
                    && (matchesAnyHint(entry, safePreset.getProcessMarkerHints()) || isProcessChannel(entry));
        }
        return out;
    }

    public static boolean isAmyloidPresent(ChannelIdentities identities) {
        if (identities == null) return false;
        for (ChannelIdentities.Entry entry : identities.getEntries()) {
            String marker = safe(entry.getMarkerId()).toLowerCase(Locale.ROOT);
            if (marker.contains("amyloid") || marker.contains("abeta") || marker.contains("plaque")) {
                return true;
            }
        }
        return false;
    }

    public static boolean isProcessChannel(ChannelIdentities.Entry entry) {
        if (entry == null) return false;
        String marker = safe(entry.getMarkerId()).toLowerCase(Locale.ROOT);
        String shape = safe(entry.getShape()).toLowerCase(Locale.ROOT);
        return marker.contains("microglia")
                || marker.contains("astro")
                || marker.contains("neuron")
                || shape.contains("complex");
    }

    public static int detectNuclearMarkerIndex(ChannelIdentities identities, int channels) {
        if (identities != null) {
            for (ChannelIdentities.Entry entry : identities.getEntries()) {
                String marker = safe(entry.getMarkerId()).toLowerCase(Locale.ROOT);
                if (marker.contains("nuclei") || marker.contains("dapi") || marker.contains("hoechst")) {
                    return entry.getChannelIndex();
                }
            }
        }
        return channels > 0 ? 0 : -1;
    }

    private static String defaultStrictness(ChannelIdentities identities) {
        return isAmyloidPresent(identities) ? STRICTNESS_LOOSE : STRICTNESS_STANDARD;
    }

    private static double thresholdForStrictness(String strictness) {
        String value = safe(strictness).toLowerCase(Locale.ROOT);
        if (value.contains("loose")) return 10.0;
        if (value.contains("strict")) return 60.0;
        return 30.0;
    }

    private static boolean hasClassicalRoiSet(List<String> roiSetNames) {
        if (roiSetNames == null) return false;
        for (String name : roiSetNames) {
            if (safe(name).toLowerCase(Locale.ROOT).contains("_classical")) {
                return true;
            }
        }
        return false;
    }

    private static int detectMarkerHintIndex(ChannelIdentities identities, List<String> hints, int fallback) {
        if (identities == null || hints == null || hints.isEmpty()) return fallback;
        for (ChannelIdentities.Entry entry : identities.getEntries()) {
            if (matchesAnyHint(entry, hints)) {
                return entry.getChannelIndex();
            }
        }
        return fallback;
    }

    private static boolean matchesAnyHint(ChannelIdentities.Entry entry, List<String> hints) {
        if (entry == null || hints == null) return false;
        String marker = safe(entry.getMarkerId()).toLowerCase(Locale.ROOT);
        for (String hint : hints) {
            String normalized = safe(hint).toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty() && marker.contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static String pairKey(int firstIndex, int secondIndex) {
        return (firstIndex + 1) + "-" + (secondIndex + 1);
    }

    private static String pairLabel(BinConfig cfg, int firstIndex, int secondIndex) {
        return nameAt(cfg, firstIndex) + " + " + nameAt(cfg, secondIndex);
    }

    private static String nameAt(BinConfig cfg, int index) {
        if (cfg != null && index >= 0 && index < cfg.channelNames.size()) {
            return cfg.channelNames.get(index);
        }
        return "Channel " + (index + 1);
    }

    private static String[] channelNames(BinConfig cfg) {
        String[] out = new String[cfg.numChannels()];
        for (int i = 0; i < out.length; i++) {
            out[i] = nameAt(cfg, i);
        }
        return out;
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
        String raw = answerString(answers, key, null);
        if (raw == null) return fallback;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public static final class DerivedConfig {
        public boolean doVolumetric;
        public boolean doCpc;
        public boolean extractProcessLength;
        public boolean runSpatial;
        public boolean classicalCentroidFiltering;
        public double thresholdPercent;
        public int nuclearMarkerIndex = -1;
        public final boolean[] processChannels;
        public final Map<String, Double> markerThresholds = new LinkedHashMap<String, Double>();
        public final Set<String> primaryPairs = new LinkedHashSet<String>();

        private DerivedConfig(int channels) {
            this.processChannels = new boolean[Math.max(0, channels)];
        }
    }

    private final class IntentScreen extends Screen {
        private IntentScreen() {
            super("What are you trying to measure?");
            defaultAnswer("intent.count", Boolean.TRUE);
            defaultAnswer("intent.coloc", Boolean.FALSE);
            defaultAnswer("intent.process", Boolean.FALSE);
            defaultAnswer("intent.spatial", Boolean.FALSE);
        }

        public void build(PipelineDialog dialog, AnswerMap answers) {
            dialog.addHeader("What are you trying to measure?");
            dialog.addToggle("Count objects per channel", true).setEnabled(false);
            dialog.addToggle("Colocalization between channels", answers.getBoolean("intent.coloc", false));
            dialog.addToggle("Process length", answers.getBoolean("intent.process", false));
            dialog.addToggle("Spatial distribution / nearest-neighbour / morphology (opens a second helper)",
                    answers.getBoolean("intent.spatial", false));
        }

        public void read(PipelineDialog dialog, AnswerMap answers) {
            dialog.getNextBoolean();
            answers.put("intent.coloc", Boolean.valueOf(dialog.getNextBoolean()));
            answers.put("intent.process", Boolean.valueOf(dialog.getNextBoolean()));
            answers.put("intent.spatial", Boolean.valueOf(dialog.getNextBoolean()));
        }

        public void writeTo(MainPanelBinding panel, AnswerMap answers) {
            DerivedConfig config = deriveCurrentConfig();
            panel.setValue("threeD.config", config);
        }
    }

    private final class PairScreen extends Screen {
        private PairScreen() {
            super("Which channel pairs should colocalization focus on?");
        }

        public boolean isApplicable(AnswerMap prior) {
            return prior.getBoolean("intent.coloc", false) && binConfig.numChannels() > 2;
        }

        public void build(PipelineDialog dialog, AnswerMap answers) {
            dialog.addHeader("Which channel pairs should colocalization focus on?");
            dialog.addMessage("The analysis always runs every pair. These choices flag the pair(s) you care about for reporting.");
            for (int i = 0; i < binConfig.numChannels(); i++) {
                for (int j = i + 1; j < binConfig.numChannels(); j++) {
                    String key = pairKey(i, j);
                    dialog.addToggle(pairLabel(binConfig, i, j), answers.getBoolean("pair." + key, true));
                }
            }
        }

        public void read(PipelineDialog dialog, AnswerMap answers) {
            for (int i = 0; i < binConfig.numChannels(); i++) {
                for (int j = i + 1; j < binConfig.numChannels(); j++) {
                    answers.put("pair." + pairKey(i, j), Boolean.valueOf(dialog.getNextBoolean()));
                }
            }
        }

        public void writeTo(MainPanelBinding panel, AnswerMap answers) {
        }
    }

    private final class StrictnessScreen extends Screen {
        private StrictnessScreen() {
            super("How much overlap counts as colocalized?");
            defaultAnswer("coloc.strictness", defaultStrictness(identities));
        }

        public boolean isApplicable(AnswerMap prior) {
            return prior.getBoolean("intent.coloc", false);
        }

        public void build(PipelineDialog dialog, AnswerMap answers) {
            dialog.addHeader("How much overlap counts as colocalized?");
            dialog.addChoice("Overlap threshold",
                    new String[]{STRICTNESS_LOOSE, STRICTNESS_STANDARD, STRICTNESS_STRICT},
                    answers.getString("coloc.strictness", defaultStrictness(identities)));
        }

        public void read(PipelineDialog dialog, AnswerMap answers) {
            answers.put("coloc.strictness", dialog.getNextChoice());
        }

        public void writeTo(MainPanelBinding panel, AnswerMap answers) {
        }
    }

    private final class ProcessScreen extends Screen {
        private ProcessScreen() {
            super("Which channels have branching / processes to measure?");
            defaultAnswer("process.nuclearMarkerIndex",
                    Integer.valueOf(detectNuclearMarkerIndex(identities, binConfig.numChannels())));
            for (int c = 0; c < binConfig.numChannels(); c++) {
                defaultAnswer("process.channel" + (c + 1),
                        Boolean.valueOf(isProcessChannel(identities.findByChannelIndex(c))));
            }
        }

        public boolean isApplicable(AnswerMap prior) {
            return prior.getBoolean("intent.process", false);
        }

        public void build(PipelineDialog dialog, AnswerMap answers) {
            dialog.addHeader("Which channels have branching / processes to measure?");
            String[] names = channelNames(binConfig);
            int nuclearIndex = answers.getInt("process.nuclearMarkerIndex",
                    detectNuclearMarkerIndex(identities, binConfig.numChannels()));
            String defaultNuclear = nuclearIndex >= 0 && nuclearIndex < names.length ? names[nuclearIndex] : (names.length == 0 ? "" : names[0]);
            dialog.addChoice("Nuclear marker for process attribution", names, defaultNuclear);
            for (int c = 0; c < binConfig.numChannels(); c++) {
                dialog.addToggle(nameAt(binConfig, c), answers.getBoolean("process.channel" + (c + 1),
                        isProcessChannel(identities.findByChannelIndex(c))));
            }
        }

        public void read(PipelineDialog dialog, AnswerMap answers) {
            String nuclear = dialog.getNextChoice();
            answers.put("process.nuclearMarkerIndex",
                    Integer.valueOf(Arrays.asList(channelNames(binConfig)).indexOf(nuclear)));
            for (int c = 0; c < binConfig.numChannels(); c++) {
                answers.put("process.channel" + (c + 1), Boolean.valueOf(dialog.getNextBoolean()));
            }
        }

        public void writeTo(MainPanelBinding panel, AnswerMap answers) {
        }
    }

    private final class CentroidFilterScreen extends Screen {
        private CentroidFilterScreen() {
            super("Count only objects whose centroid falls inside the ROI?");
            defaultAnswer("centroid.classical", Boolean.TRUE);
        }

        public boolean isApplicable(AnswerMap prior) {
            return hasClassicalRoiSet(roiSetNames);
        }

        public void build(PipelineDialog dialog, AnswerMap answers) {
            dialog.addHeader("Count only objects whose centroid falls inside the ROI?");
            dialog.addChoice("Classical centroid filtering",
                    new String[]{"No (count all objects touching the ROI)", "Yes (classical mode)"},
                    answers.getBoolean("centroid.classical", true)
                            ? "Yes (classical mode)"
                            : "No (count all objects touching the ROI)");
        }

        public void read(PipelineDialog dialog, AnswerMap answers) {
            answers.put("centroid.classical",
                    Boolean.valueOf(dialog.getNextChoice().startsWith("Yes")));
        }

        public void writeTo(MainPanelBinding panel, AnswerMap answers) {
        }
    }
}
