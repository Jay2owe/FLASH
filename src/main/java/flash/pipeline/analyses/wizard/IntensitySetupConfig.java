package flash.pipeline.analyses.wizard;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.ChannelIdentities;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Configuration derivation for Intensity Analysis.
 *
 * <p>Holds the measurement-mode constants and the static derivation logic that
 * turns a {@link BinConfig}, channel identities, saved presets, or raw answer
 * maps into a {@link DerivedConfig}. This is pure configuration logic with no
 * UI; it was extracted from the former interactive Intensity setup wizard.
 */
public final class IntensitySetupConfig {

    public static final String MODE_WHOLE_ROI_MEAN = "Whole ROI mean (all pixels, including background)";
    public static final String MODE_THRESHOLD_MEAN = "Only bright pixels above threshold (puncta / cells)";
    /** Legacy preset string. Loaders normalize this to {@link #MODE_THRESHOLD_MEAN}. */
    private static final String LEGACY_MODE_AREA_FRACTION = "Fraction of ROI covered (% positive area)";
    public static final String MASK_NONE = "No";

    /** Per-channel filter-source tokens shared with the Intensity dialog. */
    public static final String FILTER_BIN = "Bin filter";
    public static final String FILTER_BASIC = "Basic background and noise removal";

    private IntensitySetupConfig() {
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
        out.spatialConfig = spatialConfigFromAnswers(safeAnswers)
                .validateForChannelSetup(safeCfg.numChannels(), out.binarization);
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
            out.filterSources[c] = filterSourceForPreset(safePreset, safeCfg, c);
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
        out.spatialConfig = safePreset.getSpatial()
                .validateForChannelSetup(safeCfg.numChannels(), out.binarization);
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

    private static String filterSourceForPreset(IntensityPreset preset, BinConfig cfg, int channelIndex) {
        String value = preset.getFilterSources().get(String.valueOf(channelIndex + 1));
        if (value == null) {
            value = preset.getFilterSources().get(nameAt(cfg, channelIndex));
        }
        return FILTER_BASIC.equals(value) ? FILTER_BASIC : FILTER_BIN;
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

    private static IntensitySpatialConfig spatialConfigFromAnswers(Map<String, Object> answers) {
        if (answers == null) return IntensitySpatialConfig.disabled();
        Object value = answers.get("spatial.config");
        if (value == null) value = answers.get("intensity.spatial.config");
        if (value instanceof IntensitySpatialConfig) {
            return (IntensitySpatialConfig) value;
        }
        try {
            return IntensitySpatialConfig.fromJsonObject(value);
        } catch (Exception e) {
            return IntensitySpatialConfig.disabled();
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public static final class DerivedConfig {
        public final String[] measurementModes;
        public final boolean[] binarization;
        public final String[] thresholds;
        public final String[] filterSources;
        public int maskChannelIndex = -1;
        public String maskChannelChoice = MASK_NONE;
        public final boolean[] roiSetSelected;
        public IntensitySpatialConfig spatialConfig = IntensitySpatialConfig.disabled();

        private DerivedConfig(int channels, int roiSets) {
            this.measurementModes = new String[Math.max(0, channels)];
            this.binarization = new boolean[Math.max(0, channels)];
            this.thresholds = new String[Math.max(0, channels)];
            this.filterSources = new String[Math.max(0, channels)];
            Arrays.fill(this.filterSources, FILTER_BIN);
            this.roiSetSelected = new boolean[Math.max(0, roiSets)];
        }
    }
}
