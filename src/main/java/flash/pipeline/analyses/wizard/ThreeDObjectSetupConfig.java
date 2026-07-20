package flash.pipeline.analyses.wizard;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.ChannelIdentities;
import flash.pipeline.objects.OipConfig;

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
            out.bbThresholds.put(channelName, Double.valueOf(out.bbThresholdPercent));
            out.clusterTargets.put(channelName, Boolean.FALSE);
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
        out.doBBOverlap = safePreset.isDoBBOverlap();
        out.doBBCpc = safePreset.isDoBBCpc();
        out.doBBVol = safePreset.isDoBBVol();
        out.doRadialProfile = safePreset.isDoRadialProfile();
        out.doMarginalProfile = safePreset.isDoMarginalProfile();
        out.doPrincipalAxisProfile = safePreset.isDoPrincipalAxisProfile();
        out.doAngularProfile = safePreset.isDoAngularProfile();
        out.doShellColoc = safePreset.isDoShellColoc();
        out.doWithinBoxCorr = safePreset.isDoWithinBoxCorr();
        out.oipGenerateFigures = safePreset.isOipGenerateFigures();
        out.oipRegion = parseOipRegion(safePreset.getOipRegion());
        out.oipIntensityNorm = parseOipIntensityNorm(safePreset.getOipIntensityNorm());
        out.oipRadialBins = safePreset.getOipRadialBins();
        out.oipAngularBins = safePreset.getOipAngularBins();
        out.oipShells = safePreset.getOipShells();
        out.oipResampleN = safePreset.getOipResampleN();
        out.oipBoxPadPct = safePreset.getOipBoxPadPct();
        out.oipRingThresholdPct = safePreset.getOipRingThresholdPct();
        out.extractProcessLength = safePreset.isExtractProcessLength();
        out.runSpatial = safePreset.isRunSpatial();
        out.classicalCentroidFiltering = safePreset.isClassicalCentroidFiltering();
        validateConfiguredChannelNames(safeCfg);
        if (safePreset.hasBoundChannelSettings()) {
            applyBoundChannelSettings(safeCfg, safeIdentities, safePreset, out);
        } else {
            applyTemplateChannelDefaults(safeCfg, safeIdentities, safePreset, out);
        }
        for (int i = 0; i < safeCfg.numChannels(); i++) {
            for (int j = i + 1; j < safeCfg.numChannels(); j++) {
                out.primaryPairs.add(pairKey(i, j));
            }
        }
        return out;
    }

    /**
     * Capture all channel-local controls using the same durable identity contract consumed by
     * {@link #fromPreset(BinConfig, ChannelIdentities, ThreeDObjectPreset)}.
     */
    public static Map<String, ThreeDObjectPreset.ChannelSetting> captureChannelSettings(
            BinConfig cfg,
            ChannelIdentities identities,
            Map<String, Double> markerThresholds,
            Map<String, Double> bbThresholds,
            boolean[] processChannels,
            int nuclearMarkerIndex,
            String overlapMarkerChannel,
            Map<String, Boolean> overlapTargets) {
        BinConfig safeCfg = cfg == null ? new BinConfig() : cfg;
        ChannelIdentities safeIdentities = identities == null
                ? new ChannelIdentities(null) : identities;
        String markerChannel = safe(overlapMarkerChannel).trim();
        boolean markerSelected = !markerChannel.isEmpty() && !"None".equals(markerChannel);
        if (processChannels != null && processChannels.length != safeCfg.numChannels()) {
            throw new IllegalArgumentException(
                    "Process-channel selections do not match the configured channel count.");
        }
        if (nuclearMarkerIndex < -1 || nuclearMarkerIndex >= safeCfg.numChannels()) {
            throw new IllegalArgumentException(
                    "Nuclear marker index is outside the configured channels.");
        }
        if (markerSelected && !safeCfg.channelNames.contains(markerChannel)) {
            throw new IllegalArgumentException(
                    "Overlap-count marker channel '" + markerChannel + "' is not configured.");
        }
        Map<String, ThreeDObjectPreset.ChannelSetting> out =
                new LinkedHashMap<String, ThreeDObjectPreset.ChannelSetting>();
        Set<String> channelNames = new LinkedHashSet<String>();
        for (int i = 0; i < safeCfg.numChannels(); i++) {
            String channelName = safeCfg.channelNames.get(i);
            if (!channelNames.add(safe(channelName).trim().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(
                        "Configured channel names collide: '" + channelName + "'.");
            }
            String markerId = markerIdForChannel(safeIdentities, i);
            String key = ThreeDObjectPreset.channelIdentityKey(channelName, markerId);
            if (out.containsKey(key)) {
                throw new IllegalArgumentException(
                        "Channels have an ambiguous durable identity '" + key + "'.");
            }
            Double coloc = markerThresholds == null ? null : markerThresholds.get(channelName);
            Double bbColoc = bbThresholds == null ? null : bbThresholds.get(channelName);
            if (coloc == null || bbColoc == null) {
                throw new IllegalArgumentException(
                        "Both threshold families are required for channel '" + channelName + "'.");
            }
            boolean process = processChannels != null && i < processChannels.length
                    && processChannels[i];
            boolean nuclear = i == nuclearMarkerIndex;
            boolean overlapMarker = markerSelected && channelName.equals(markerChannel);
            boolean overlapTarget = markerSelected && overlapTargets != null
                    && Boolean.TRUE.equals(overlapTargets.get(channelName))
                    && !overlapMarker;
            ThreeDObjectPreset.ChannelSetting setting = new ThreeDObjectPreset.ChannelSetting(
                    channelName, markerId,
                    coloc.doubleValue(), bbColoc.doubleValue(),
                    process, nuclear, overlapMarker, overlapTarget);
            out.put(key, setting);
        }
        return out;
    }

    private static void applyBoundChannelSettings(BinConfig cfg,
                                                  ChannelIdentities identities,
                                                  ThreeDObjectPreset preset,
                                                  DerivedConfig out) {
        Map<String, Integer> currentIndexes = new LinkedHashMap<String, Integer>();
        Set<String> channelNames = new LinkedHashSet<String>();
        for (int i = 0; i < cfg.numChannels(); i++) {
            String channelName = cfg.channelNames.get(i);
            if (!channelNames.add(safe(channelName).trim().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(
                        "Configured channel names collide: '" + channelName + "'.");
            }
            String markerId = markerIdForChannel(identities, i);
            String key = ThreeDObjectPreset.channelIdentityKey(channelName, markerId);
            Integer previous = currentIndexes.put(key, Integer.valueOf(i));
            if (previous != null) {
                throw new IllegalArgumentException(
                        "Current channels " + (previous.intValue() + 1) + " and " + (i + 1)
                                + " have the same durable identity '" + key + "'.");
            }
        }
        Map<String, ThreeDObjectPreset.ChannelSetting> saved = preset.getChannelSettings();
        if (!currentIndexes.keySet().equals(saved.keySet())) {
            Set<String> missing = new LinkedHashSet<String>(currentIndexes.keySet());
            missing.removeAll(saved.keySet());
            Set<String> unavailable = new LinkedHashSet<String>(saved.keySet());
            unavailable.removeAll(currentIndexes.keySet());
            throw new IllegalArgumentException(
                    "Preset channel identities do not match this project. Missing settings: "
                            + missing + "; unavailable saved identities: " + unavailable + ".");
        }
        boolean first = true;
        for (Map.Entry<String, Integer> entry : currentIndexes.entrySet()) {
            int index = entry.getValue().intValue();
            String channelName = cfg.channelNames.get(index);
            ThreeDObjectPreset.ChannelSetting setting = saved.get(entry.getKey());
            out.markerThresholds.put(channelName,
                    Double.valueOf(setting.getColocThresholdPercent()));
            out.bbThresholds.put(channelName,
                    Double.valueOf(setting.getBBColocThresholdPercent()));
            out.processChannels[index] = setting.isProcessChannel();
            if (setting.isNuclearMarker()) out.nuclearMarkerIndex = index;
            if (setting.isOverlapMarker()) out.clusterMarkerChannel = channelName;
            out.clusterTargets.put(channelName, Boolean.valueOf(setting.isOverlapTarget()));
            if (first) {
                out.thresholdPercent = setting.getColocThresholdPercent();
                out.bbThresholdPercent = setting.getBBColocThresholdPercent();
                first = false;
            }
        }
    }

    private static void applyTemplateChannelDefaults(BinConfig cfg,
                                                     ChannelIdentities identities,
                                                     ThreeDObjectPreset preset,
                                                     DerivedConfig out) {
        out.thresholdPercent = preset.getColocThresholdPercent();
        out.bbThresholdPercent = preset.getBBColocThresholdPercent();
        for (String channelName : cfg.channelNames) {
            out.markerThresholds.put(channelName, Double.valueOf(out.thresholdPercent));
            out.bbThresholds.put(channelName, Double.valueOf(out.bbThresholdPercent));
            out.clusterTargets.put(channelName, Boolean.FALSE);
        }
        out.nuclearMarkerIndex = detectMarkerHintIndex(identities,
                preset.getNuclearMarkerHints(), detectNuclearMarkerIndex(identities, cfg.numChannels()));
        for (int c = 0; c < cfg.numChannels(); c++) {
            ChannelIdentities.Entry entry = identities.findByChannelIndex(c);
            out.processChannels[c] = preset.isExtractProcessLength()
                    && (matchesAnyHint(entry, preset.getProcessMarkerHints()) || isProcessChannel(entry));
        }
    }

    private static String markerIdForChannel(ChannelIdentities identities, int channelIndex) {
        String markerId = "";
        boolean found = false;
        if (identities != null) {
            for (ChannelIdentities.Entry entry : identities.getEntries()) {
                if (entry.getChannelIndex() != channelIndex) continue;
                if (found) {
                    throw new IllegalArgumentException(
                            "Channel " + (channelIndex + 1)
                                    + " has more than one marker identity.");
                }
                markerId = entry.getMarkerId();
                found = true;
            }
        }
        return markerId;
    }

    private static void validateConfiguredChannelNames(BinConfig cfg) {
        Set<String> names = new LinkedHashSet<String>();
        for (String channelName : cfg.channelNames) {
            String normalized = safe(channelName).trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("Configured channel names must not be blank.");
            }
            if (!names.add(normalized)) {
                throw new IllegalArgumentException(
                        "Configured channel names collide: '" + channelName + "'.");
            }
        }
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

    private static OipConfig.Region parseOipRegion(String value) {
        String normalized = safe(value).toLowerCase(Locale.ROOT)
                .replace("_", "").replace("-", "").replace(" ", "");
        if ("objectvoxels".equals(normalized) || "objectonly".equals(normalized)
                || "onlyobject".equals(normalized)) {
            return OipConfig.Region.OBJECT_VOXELS;
        }
        return OipConfig.Region.WHOLE_BOX;
    }

    private static OipConfig.IntensityNorm parseOipIntensityNorm(String value) {
        String normalized = safe(value).toLowerCase(Locale.ROOT)
                .replace("_", "").replace("-", "").replace(" ", "");
        if ("dividebymean".equals(normalized) || "mean".equals(normalized)) {
            return OipConfig.IntensityNorm.DIVIDE_BY_MEAN;
        }
        if ("zscore".equals(normalized) || "z".equals(normalized)) {
            return OipConfig.IntensityNorm.ZSCORE;
        }
        return OipConfig.IntensityNorm.PER_OBJECT_MINMAX;
    }

    public static final class DerivedConfig {
        public boolean doVolumetric;
        public boolean doCpc;
        public boolean doIntensityColoc;
        public boolean doBBOverlap;
        public boolean doBBCpc;
        public boolean doBBVol;
        public boolean doRadialProfile = ThreeDObjectPreset.DEFAULT_DO_RADIAL_PROFILE;
        public boolean doMarginalProfile = ThreeDObjectPreset.DEFAULT_DO_MARGINAL_PROFILE;
        public boolean doPrincipalAxisProfile = ThreeDObjectPreset.DEFAULT_DO_PRINCIPAL_AXIS_PROFILE;
        public boolean doAngularProfile = ThreeDObjectPreset.DEFAULT_DO_ANGULAR_PROFILE;
        public boolean doShellColoc = ThreeDObjectPreset.DEFAULT_DO_SHELL_COLOC;
        public boolean doWithinBoxCorr = ThreeDObjectPreset.DEFAULT_DO_WITHIN_BOX_CORR;
        public OipConfig.Region oipRegion = OipConfig.Region.WHOLE_BOX;
        public OipConfig.IntensityNorm oipIntensityNorm = OipConfig.IntensityNorm.PER_OBJECT_MINMAX;
        public int oipRadialBins = ThreeDObjectPreset.DEFAULT_OIP_RADIAL_BINS;
        public int oipAngularBins = ThreeDObjectPreset.DEFAULT_OIP_ANGULAR_BINS;
        public int oipShells = ThreeDObjectPreset.DEFAULT_OIP_SHELLS;
        public int oipResampleN = ThreeDObjectPreset.DEFAULT_OIP_RESAMPLE_N;
        public double oipBoxPadPct = ThreeDObjectPreset.DEFAULT_OIP_BOX_PAD_PCT;
        public double oipRingThresholdPct = ThreeDObjectPreset.DEFAULT_OIP_RING_THRESHOLD_PCT;
        public boolean oipGenerateFigures = ThreeDObjectPreset.DEFAULT_OIP_GENERATE_FIGURES;
        public boolean extractProcessLength;
        public boolean runSpatial;
        public boolean classicalCentroidFiltering;
        public double thresholdPercent;
        public double bbThresholdPercent = 30.0;
        public int nuclearMarkerIndex = -1;
        public final boolean[] processChannels;
        public final Map<String, Double> markerThresholds = new LinkedHashMap<String, Double>();
        public final Map<String, Double> bbThresholds = new LinkedHashMap<String, Double>();
        public String clusterMarkerChannel = "None";
        public final Map<String, Boolean> clusterTargets = new LinkedHashMap<String, Boolean>();
        public final Set<String> primaryPairs = new LinkedHashSet<String>();

        private DerivedConfig(int channels) {
            this.processChannels = new boolean[Math.max(0, channels)];
        }
    }
}
