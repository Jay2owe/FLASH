package flash.pipeline.analyses.wizard;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.ChannelIdentities;
import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.ToggleSwitch;
import flash.pipeline.ui.wizard.WizardFlow;

import javax.swing.JComboBox;
import javax.swing.JTextField;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Intent-led setup helper for Intensity Analysis.
 */
public class IntensityWizard extends WizardFlow {

    public static final String MODE_WHOLE_ROI_MEAN = "Whole ROI mean (all pixels, including background)";
    public static final String MODE_THRESHOLD_MEAN = "Only bright pixels above threshold (puncta / cells)";
    /** Legacy preset string. Loaders normalize this to {@link #MODE_THRESHOLD_MEAN}. */
    private static final String LEGACY_MODE_AREA_FRACTION = "Fraction of ROI covered (% positive area)";
    public static final String MASK_NONE = "No";

    private static final String[] MODE_OPTIONS = {
            MODE_WHOLE_ROI_MEAN,
            MODE_THRESHOLD_MEAN
    };

    private final BinConfig binConfig;
    private final ChannelIdentities identities;
    private final List<String> roiSetNames;

    public IntensityWizard(MainPanelBinding panel,
                           BinConfig binConfig,
                           ChannelIdentities identities,
                           List<String> roiSetNames,
                           boolean headless) {
        super("Intensity Helper", panel, headless);
        this.binConfig = binConfig == null ? new BinConfig() : binConfig;
        this.identities = identities == null ? new ChannelIdentities(null) : identities;
        this.roiSetNames = roiSetNames == null
                ? Collections.<String>emptyList()
                : new ArrayList<String>(roiSetNames);
        register(new ModeScreen());
        register(new MaskScreen());
        register(new RoiSetScreen());
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
        Map<String, Object> safeAnswers = answers == null
                ? new LinkedHashMap<String, Object>()
                : answers;
        List<String> safeRoiSets = roiSetNames == null
                ? Collections.<String>emptyList()
                : roiSetNames;

        DerivedConfig out = new DerivedConfig(safeCfg.numChannels(), safeRoiSets.size());
        for (int c = 0; c < safeCfg.numChannels(); c++) {
            ChannelIdentities.Entry identity = safeIdentities.findByChannelIndex(c);
            String mode = normalizeMode(answerString(safeAnswers, "mode." + (c + 1),
                    defaultMode(identity)));
            out.measurementModes[c] = mode;
            out.binarization[c] = !MODE_WHOLE_ROI_MEAN.equals(mode);
            out.thresholds[c] = answerString(safeAnswers, "threshold." + (c + 1),
                    thresholdAt(safeCfg, c));
        }

        String maskChoice = answerString(safeAnswers, "mask.choice", MASK_NONE);
        out.maskChannelChoice = maskChoice;
        out.maskChannelIndex = channelIndexByName(safeCfg, maskChoice);
        if (out.maskChannelIndex < 0) {
            out.maskChannelChoice = MASK_NONE;
        }

        for (int r = 0; r < safeRoiSets.size(); r++) {
            out.roiSetSelected[r] = booleanAnswer(safeAnswers, "roi." + r, true);
        }
        return out;
    }

    public static DerivedConfig fromPreset(BinConfig cfg,
                                           ChannelIdentities identities,
                                           IntensityPreset preset,
                                           List<String> roiSetNames) {
        BinConfig safeCfg = cfg == null ? new BinConfig() : cfg;
        ChannelIdentities safeIdentities = identities == null ? new ChannelIdentities(null) : identities;
        List<String> safeRoiSets = roiSetNames == null
                ? Collections.<String>emptyList()
                : roiSetNames;
        IntensityPreset safePreset = preset == null
                ? new IntensityPreset("ROI mean", null, "1", "roi_mean",
                MODE_WHOLE_ROI_MEAN, null, null, null, null)
                : preset;
        DerivedConfig out = new DerivedConfig(safeCfg.numChannels(), safeRoiSets.size());

        int maskIndex = findChannelByHint(safeCfg, safeIdentities, safePreset.getMaskChannelHint());
        if ("neun_restricted".equalsIgnoreCase(safePreset.getStrategy()) && maskIndex < 0) {
            maskIndex = findChannelByHint(safeCfg, safeIdentities, "neun");
        }
        out.maskChannelIndex = maskIndex;
        out.maskChannelChoice = maskIndex >= 0 ? nameAt(safeCfg, maskIndex) : MASK_NONE;

        for (int c = 0; c < safeCfg.numChannels(); c++) {
            ChannelIdentities.Entry identity = safeIdentities.findByChannelIndex(c);
            out.measurementModes[c] = modeForPreset(safePreset, safeCfg, identity, c);
            out.binarization[c] = !MODE_WHOLE_ROI_MEAN.equals(out.measurementModes[c]);
            out.thresholds[c] = thresholdForPreset(safePreset, safeCfg, c);
        }

        boolean hasRoiHint = safePreset.getRoiSetNameHints() != null && !safePreset.getRoiSetNameHints().isEmpty();
        boolean anySelected = false;
        for (int r = 0; r < safeRoiSets.size(); r++) {
            out.roiSetSelected[r] = !hasRoiHint || matchesAnyHint(safeRoiSets.get(r), safePreset.getRoiSetNameHints());
            anySelected = anySelected || out.roiSetSelected[r];
        }
        if (hasRoiHint && !anySelected) {
            Arrays.fill(out.roiSetSelected, true);
        }
        return out;
    }

    public static boolean isNuclearOrAutofluorescence(ChannelIdentities.Entry entry) {
        String marker = markerText(entry);
        return marker.contains("nuclei")
                || marker.contains("dapi")
                || marker.contains("hoechst")
                || marker.contains("autofluorescence");
    }

    public static boolean isCellularSignal(ChannelIdentities.Entry entry) {
        String marker = markerText(entry);
        return marker.contains("microglia")
                || marker.contains("astro")
                || marker.contains("gfap")
                || marker.contains("neuron")
                || marker.contains("neun");
    }

    public static boolean isPunctaOrPlaqueSignal(ChannelIdentities.Entry entry) {
        String marker = markerText(entry);
        String shape = entry == null ? "" : safe(entry.getShape()).toLowerCase(Locale.ROOT);
        return marker.contains("synapse")
                || marker.contains("synaptic")
                || marker.contains("synaptophysin")
                || marker.contains("amyloid")
                || marker.contains("abeta")
                || marker.contains("plaque")
                || shape.contains("puncta");
    }

    private static String defaultMode(ChannelIdentities.Entry entry) {
        return isPunctaOrPlaqueSignal(entry) ? MODE_THRESHOLD_MEAN : MODE_WHOLE_ROI_MEAN;
    }

    private static String modeForPreset(IntensityPreset preset,
                                        BinConfig cfg,
                                        ChannelIdentities.Entry identity,
                                        int channelIndex) {
        String byNumber = preset.getChannelModes().get(String.valueOf(channelIndex + 1));
        if (byNumber == null) {
            byNumber = preset.getChannelModes().get(nameAt(cfg, channelIndex));
        }
        String normalized = normalizeMode(byNumber);
        if (normalized != null && isKnownMode(normalized)) {
            return normalized;
        }
        String strategy = safe(preset.getStrategy()).toLowerCase(Locale.ROOT);
        if ("threshold_puncta".equals(strategy)) {
            return isPunctaOrPlaqueSignal(identity) ? MODE_THRESHOLD_MEAN : MODE_WHOLE_ROI_MEAN;
        }
        if ("area_fraction".equals(strategy)) {
            // Legacy strategy: kept binarization on; output now always carries %Area.
            return MODE_THRESHOLD_MEAN;
        }
        String fallback = normalizeMode(preset.getDefaultMode());
        return isKnownMode(fallback) ? fallback : MODE_WHOLE_ROI_MEAN;
    }

    private static String normalizeMode(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        if (LEGACY_MODE_AREA_FRACTION.equals(trimmed)) {
            return MODE_THRESHOLD_MEAN;
        }
        return trimmed;
    }

    private static String thresholdForPreset(IntensityPreset preset, BinConfig cfg, int channelIndex) {
        String byNumber = preset.getThresholds().get(String.valueOf(channelIndex + 1));
        if (byNumber == null) {
            byNumber = preset.getThresholds().get(nameAt(cfg, channelIndex));
        }
        return byNumber == null ? thresholdAt(cfg, channelIndex) : byNumber;
    }

    private static boolean isKnownMode(String mode) {
        return MODE_WHOLE_ROI_MEAN.equals(mode)
                || MODE_THRESHOLD_MEAN.equals(mode);
    }

    private static int findChannelByHint(BinConfig cfg, ChannelIdentities identities, String hint) {
        String needle = safe(hint).toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return -1;
        for (int c = 0; c < cfg.numChannels(); c++) {
            String text = nameAt(cfg, c).toLowerCase(Locale.ROOT) + " "
                    + markerText(identities.findByChannelIndex(c));
            if (text.contains(needle)) {
                return c;
            }
        }
        return -1;
    }

    private static boolean matchesAnyHint(String value, List<String> hints) {
        String text = safe(value).toLowerCase(Locale.ROOT);
        for (String hint : hints) {
            String needle = safe(hint).toLowerCase(Locale.ROOT);
            if (!needle.isEmpty() && text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static int channelIndexByName(BinConfig cfg, String name) {
        for (int i = 0; i < cfg.numChannels(); i++) {
            if (nameAt(cfg, i).equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private static String thresholdAt(BinConfig cfg, int channelIndex) {
        String value = channelIndex >= 0 && channelIndex < cfg.channelIntensityThresholds.size()
                ? cfg.channelIntensityThresholds.get(channelIndex)
                : null;
        if (value == null || value.trim().isEmpty() || "default".equalsIgnoreCase(value.trim())) {
            return "0";
        }
        return value.trim();
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

    private static String markerText(ChannelIdentities.Entry entry) {
        return entry == null ? "" : safe(entry.getMarkerId()).toLowerCase(Locale.ROOT);
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

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public static final class DerivedConfig {
        public final String[] measurementModes;
        public final boolean[] binarization;
        public final String[] thresholds;
        public int maskChannelIndex = -1;
        public String maskChannelChoice = MASK_NONE;
        public final boolean[] roiSetSelected;

        private DerivedConfig(int channels, int roiSets) {
            this.measurementModes = new String[Math.max(0, channels)];
            this.binarization = new boolean[Math.max(0, channels)];
            this.thresholds = new String[Math.max(0, channels)];
            this.roiSetSelected = new boolean[Math.max(0, roiSets)];
        }
    }

    private final class ModeScreen extends Screen {
        private ModeScreen() {
            super("For each channel, what are you measuring?");
            for (int c = 0; c < binConfig.numChannels(); c++) {
                defaultAnswer("mode." + (c + 1), defaultMode(identities.findByChannelIndex(c)));
                defaultAnswer("threshold." + (c + 1), thresholdAt(binConfig, c));
            }
        }

        public boolean isApplicable(AnswerMap prior) {
            return binConfig.numChannels() > 0;
        }

        public void build(PipelineDialog dialog, AnswerMap answers) {
            dialog.addHeader("For each channel, what are you measuring?");
            for (int c = 0; c < binConfig.numChannels(); c++) {
                dialog.addHeader(nameAt(binConfig, c));
                JComboBox<String> choice = dialog.addChoice("Measurement mode", MODE_OPTIONS,
                        answers.getString("mode." + (c + 1), defaultMode(identities.findByChannelIndex(c))));
                final JTextField threshold = dialog.addStringField("Threshold (auto-detected from configuration)",
                        answers.getString("threshold." + (c + 1), thresholdAt(binConfig, c)), 8);
                final ToggleSwitch override = dialog.addToggle("Override threshold", false);
                boolean needsThreshold = !MODE_WHOLE_ROI_MEAN.equals(choice.getSelectedItem());
                override.setEnabled(needsThreshold);
                threshold.setEnabled(needsThreshold && override.isSelected());
                choice.addActionListener(e -> {
                    boolean selectedModeNeedsThreshold = !MODE_WHOLE_ROI_MEAN.equals(choice.getSelectedItem());
                    override.setEnabled(selectedModeNeedsThreshold);
                    if (!selectedModeNeedsThreshold) {
                        override.setSelected(false);
                    }
                    threshold.setEnabled(selectedModeNeedsThreshold && override.isSelected());
                });
                override.addChangeListener(new Runnable() {
                    public void run() {
                        threshold.setEnabled(override.isEnabled() && override.isSelected());
                    }
                });
            }
        }

        public void read(PipelineDialog dialog, AnswerMap answers) {
            for (int c = 0; c < binConfig.numChannels(); c++) {
                String mode = dialog.getNextChoice();
                String threshold = dialog.getNextString();
                dialog.getNextBoolean();
                answers.put("mode." + (c + 1), mode);
                answers.put("threshold." + (c + 1), threshold);
            }
        }

        public void writeTo(MainPanelBinding panel, AnswerMap answers) {
            panel.setValue("intensity.config", deriveCurrentConfig());
        }
    }

    private final class MaskScreen extends Screen {
        private MaskScreen() {
            super("Do you want to measure only inside another channel's positive area?");
            defaultAnswer("mask.choice", MASK_NONE);
        }

        public void build(PipelineDialog dialog, AnswerMap answers) {
            dialog.addHeader("Do you want to measure only inside another channel's positive area?");
            String[] choices = maskChoices();
            dialog.addChoice("Channel ROI Mask", choices, answers.getString("mask.choice", MASK_NONE));
        }

        public void read(PipelineDialog dialog, AnswerMap answers) {
            answers.put("mask.choice", dialog.getNextChoice());
        }

        public void writeTo(MainPanelBinding panel, AnswerMap answers) {
        }

        private String[] maskChoices() {
            List<String> out = new ArrayList<String>();
            out.add(MASK_NONE);
            for (String name : channelNames(binConfig)) {
                out.add(name);
            }
            return out.toArray(new String[out.size()]);
        }
    }

    private final class RoiSetScreen extends Screen {
        private RoiSetScreen() {
            super("Which ROI sets should intensity be measured inside?");
            for (int r = 0; r < roiSetNames.size(); r++) {
                defaultAnswer("roi." + r, Boolean.TRUE);
            }
        }

        public boolean isApplicable(AnswerMap prior) {
            return roiSetNames.size() > 1;
        }

        public void build(PipelineDialog dialog, AnswerMap answers) {
            dialog.addHeader("Which ROI sets should intensity be measured inside?");
            for (int r = 0; r < roiSetNames.size(); r++) {
                dialog.addToggle(roiSetNames.get(r), answers.getBoolean("roi." + r, true));
            }
        }

        public void read(PipelineDialog dialog, AnswerMap answers) {
            boolean any = false;
            for (int r = 0; r < roiSetNames.size(); r++) {
                boolean selected = dialog.getNextBoolean();
                answers.put("roi." + r, Boolean.valueOf(selected));
                any = any || selected;
            }
            if (!any && !roiSetNames.isEmpty()) {
                answers.put("roi.0", Boolean.TRUE);
            }
        }

        public void writeTo(MainPanelBinding panel, AnswerMap answers) {
        }
    }
}
