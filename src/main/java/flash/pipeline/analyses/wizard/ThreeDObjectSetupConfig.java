package flash.pipeline.analyses.wizard;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.ChannelIdentities;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Derives 3D Object Analysis options from saved presets and intent answers.
 * The interactive setup helper has been removed; this class keeps the reusable
 * configuration model that preset IO, the analysis apply logic, and tests
 * depend on.
 */
public final class ThreeDObjectSetupConfig {

    public static final String STRICTNESS_LOOSE = "Loose (10%)";
    public static final String STRICTNESS_STANDARD = "Standard (30%)";
    public static final String STRICTNESS_STRICT = "Strict (60%)";

    private ThreeDObjectSetupConfig() {
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
        out.doIntensityColoc = booleanAnswer(answers, "intent.intensityColoc", false);
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
                false, false, false, true, 30.0, null, null)
                : preset;
        DerivedConfig out = new DerivedConfig(safeCfg.numChannels());
        out.doVolumetric = safePreset.isDoVolumetric();
        out.doCpc = safePreset.isDoCpc();
        out.doIntensityColoc = safePreset.isDoIntensityColoc();
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
        public boolean doIntensityColoc;
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
}
