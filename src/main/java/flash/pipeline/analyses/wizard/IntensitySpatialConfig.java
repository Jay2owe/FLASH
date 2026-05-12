package flash.pipeline.analyses.wizard;

import flash.pipeline.ui.wizard.JsonIO;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Serializable intensity-spatial selections shared by GUI, presets, and CLI.
 */
public final class IntensitySpatialConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final double DEFAULT_SHELL_WIDTH_UM = 10.0;
    public static final int DEFAULT_SHELL_COUNT = 5;
    public static final double[] DEFAULT_TILE_SCALES_UM = {50.0, 100.0, 250.0};
    public static final double[] DEFAULT_GRANULARITY_SCALES_UM = {2.0, 4.0, 8.0, 16.0, 32.0, 64.0};
    public static final double DEFAULT_DEPTH_BIN_WIDTH_UM = 10.0;
    public static final double DEFAULT_RIM_DEPTH_UM = 10.0;
    public static final int DEFAULT_TEXTURE_CLASS_COUNT = 4;
    public static final int DEFAULT_PERMUTATIONS = 199;
    public static final long DEFAULT_SEED = 1L;
    public static final int MIN_NATIVE_3D_SLICES = 5;

    public enum FailurePolicy {
        SKIP_FAILED_ANALYSIS;

        public static FailurePolicy parse(String raw) {
            if (raw == null || raw.trim().isEmpty()) return SKIP_FAILED_ANALYSIS;
            String normalized = normalizeToken(raw);
            if ("skipfailedanalysis".equals(normalized) || "skipfailed".equals(normalized)) {
                return SKIP_FAILED_ANALYSIS;
            }
            return SKIP_FAILED_ANALYSIS;
        }
    }

    public enum AnalysisKey {
        PATCHINESS("patchiness", false, false),
        HOTSPOTSCAN("hotspots", false, false),
        NULLMODEL("nullmodel", false, false),
        GRANULARITY("granularity", false, false),
        DEPTH_PROFILE("depth", false, false),
        ANISOTROPY("anisotropy", false, false),
        PERIODICITY("periodicity", false, false),
        GLCM("glcm", false, false),
        TEXTURECLASS("textureclass", false, false),
        SCALEDIVERGENCE("scaledivergence", false, false),
        CROSSMARK("crossmark", true, false),
        ENTROPY_MI("mi", true, false),
        DISTANCE_SHELL("distance_shell", true, false),
        CROSSMARK_3D("crossmark_3d", true, true),
        DISTANCE_SHELL_3D("distance_shell_3d", true, true),
        ANISOTROPY_3D("anisotropy_3d", false, true);

        private final String token;
        private final boolean crossChannel;
        private final boolean native3d;

        AnalysisKey(String token, boolean crossChannel, boolean native3d) {
            this.token = token;
            this.crossChannel = crossChannel;
            this.native3d = native3d;
        }

        public String token() {
            return token;
        }

        public boolean isCrossChannel() {
            return crossChannel;
        }

        public boolean isNative3d() {
            return native3d;
        }

        public static AnalysisKey parse(String raw) {
            String normalized = normalizeToken(raw);
            if (normalized.isEmpty()) return null;
            if ("hotspotscan".equals(normalized) || "hotspot".equals(normalized)) return HOTSPOTSCAN;
            if ("depthprofile".equals(normalized) || "depth".equals(normalized)) return DEPTH_PROFILE;
            if ("textureclass".equals(normalized) || "textureclasses".equals(normalized)) return TEXTURECLASS;
            if ("scaledivergence".equals(normalized) || "multifractal".equals(normalized)) return SCALEDIVERGENCE;
            if ("entropymi".equals(normalized) || "mutualinformation".equals(normalized)
                    || "mi".equals(normalized)) return ENTROPY_MI;
            if ("distanceshell".equals(normalized) || "shell".equals(normalized)) return DISTANCE_SHELL;
            if ("crossmark3d".equals(normalized)) return CROSSMARK_3D;
            if ("distanceshell3d".equals(normalized)) return DISTANCE_SHELL_3D;
            if ("anisotropy3d".equals(normalized)) return ANISOTROPY_3D;
            for (AnalysisKey key : values()) {
                if (normalizeToken(key.name()).equals(normalized)
                        || normalizeToken(key.token).equals(normalized)) {
                    return key;
                }
            }
            return null;
        }
    }

    public interface LockLogger {
        void log(String message);
    }

    private final boolean enabled;
    private final Set<AnalysisKey> enabledAnalyses;
    private final boolean mipEnabled;
    private final boolean native3dEnabled;
    private final boolean overlaysEnabled;
    private final double shellWidthUm;
    private final int shellCount;
    private final double[] tileScalesUm;
    private final double[] granularityScalesUm;
    private final double depthBinWidthUm;
    private final double rimDepthUm;
    private final int textureClassCount;
    private final int permutations;
    private final long seed;
    private final FailurePolicy failurePolicy;

    private IntensitySpatialConfig(Builder builder) {
        this.enabled = builder.enabled;
        this.enabledAnalyses = immutableCopy(builder.enabledAnalyses);
        this.mipEnabled = builder.mipEnabled;
        this.native3dEnabled = builder.native3dEnabled;
        this.overlaysEnabled = builder.overlaysEnabled;
        this.shellWidthUm = positive(builder.shellWidthUm, DEFAULT_SHELL_WIDTH_UM);
        this.shellCount = positive(builder.shellCount, DEFAULT_SHELL_COUNT);
        this.tileScalesUm = sanitizeDoubles(builder.tileScalesUm, DEFAULT_TILE_SCALES_UM);
        this.granularityScalesUm = sanitizeDoubles(builder.granularityScalesUm, DEFAULT_GRANULARITY_SCALES_UM);
        this.depthBinWidthUm = positive(builder.depthBinWidthUm, DEFAULT_DEPTH_BIN_WIDTH_UM);
        this.rimDepthUm = positive(builder.rimDepthUm, DEFAULT_RIM_DEPTH_UM);
        this.textureClassCount = positive(builder.textureClassCount, DEFAULT_TEXTURE_CLASS_COUNT);
        this.permutations = nonNegative(builder.permutations, DEFAULT_PERMUTATIONS);
        this.seed = builder.seed;
        this.failurePolicy = builder.failurePolicy == null
                ? FailurePolicy.SKIP_FAILED_ANALYSIS
                : builder.failurePolicy;
    }

    public static IntensitySpatialConfig disabled() {
        return builder().enabled(false).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(IntensitySpatialConfig base) {
        return new Builder(base);
    }

    public boolean isEnabled() { return enabled; }
    public Set<AnalysisKey> getEnabledAnalyses() { return enabledAnalyses; }
    public boolean isMipEnabled() { return mipEnabled; }
    public boolean isNative3dEnabled() { return native3dEnabled; }
    public boolean isOverlaysEnabled() { return overlaysEnabled; }
    public double getShellWidthUm() { return shellWidthUm; }
    public int getShellCount() { return shellCount; }
    public double[] getTileScalesUm() { return tileScalesUm.clone(); }
    public double[] getGranularityScalesUm() { return granularityScalesUm.clone(); }
    public double getDepthBinWidthUm() { return depthBinWidthUm; }
    public double getRimDepthUm() { return rimDepthUm; }
    public int getTextureClassCount() { return textureClassCount; }
    public int getPermutations() { return permutations; }
    public long getSeed() { return seed; }
    public FailurePolicy getFailurePolicy() { return failurePolicy; }

    public boolean hasConfiguration() {
        return enabled
                || !enabledAnalyses.isEmpty()
                || mipEnabled
                || native3dEnabled
                || overlaysEnabled
                || Double.compare(shellWidthUm, DEFAULT_SHELL_WIDTH_UM) != 0
                || shellCount != DEFAULT_SHELL_COUNT
                || !Arrays.equals(tileScalesUm, DEFAULT_TILE_SCALES_UM)
                || !Arrays.equals(granularityScalesUm, DEFAULT_GRANULARITY_SCALES_UM)
                || Double.compare(depthBinWidthUm, DEFAULT_DEPTH_BIN_WIDTH_UM) != 0
                || Double.compare(rimDepthUm, DEFAULT_RIM_DEPTH_UM) != 0
                || textureClassCount != DEFAULT_TEXTURE_CLASS_COUNT
                || permutations != DEFAULT_PERMUTATIONS
                || seed != DEFAULT_SEED
                || failurePolicy != FailurePolicy.SKIP_FAILED_ANALYSIS;
    }

    public IntensitySpatialConfig validateForChannelSetup(int channelCount,
                                                          boolean[] channelBinarization) {
        return validateForChannelSetup(channelCount, channelBinarization, null, null);
    }

    public IntensitySpatialConfig validateForChannelSetup(int channelCount,
                                                          boolean[] channelBinarization,
                                                          Integer actualSliceCount,
                                                          LockLogger logger) {
        EnumSet<AnalysisKey> adjusted = mutableCopy(enabledAnalyses);
        int safeChannelCount = Math.max(0, channelCount);
        if (safeChannelCount < 2 && removeCrossChannel(adjusted)) {
            log(logger, "Intensity-spatial cross-channel analyses require at least two channels; selections were cleared.");
        }

        if (!hasAnyBinarizedChannel(channelBinarization)
                && (adjusted.remove(AnalysisKey.DISTANCE_SHELL)
                | adjusted.remove(AnalysisKey.DISTANCE_SHELL_3D))) {
            log(logger, "Intensity-spatial distance-shell analyses require a binarized partner channel; selections were cleared.");
        }

        boolean native3d = native3dEnabled;
        if (!native3d && removeNative3d(adjusted)) {
            log(logger, "Intensity-spatial native 3D analyses require native 3D output to be selected; selections were cleared.");
        }
        if (actualSliceCount != null && actualSliceCount.intValue() < MIN_NATIVE_3D_SLICES) {
            boolean removedNative3dAnalyses = removeNative3d(adjusted);
            if (native3d || removedNative3dAnalyses) {
                log(logger, "Intensity-spatial native 3D analyses require at least "
                        + MIN_NATIVE_3D_SLICES + " slices; selections were cleared.");
            }
            native3d = false;
        }

        return builder(this)
                .enabledAnalyses(adjusted)
                .native3dEnabled(native3d)
                .build();
    }

    public Map<String, Object> toJsonObject() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("enabled", Boolean.valueOf(enabled));
        root.put("analyses", analysisTokenList(enabledAnalyses));
        root.put("mip", Boolean.valueOf(mipEnabled));
        root.put("native3d", Boolean.valueOf(native3dEnabled));
        root.put("overlays", Boolean.valueOf(overlaysEnabled));
        root.put("shellWidthUm", Double.valueOf(shellWidthUm));
        root.put("shellCount", Integer.valueOf(shellCount));
        root.put("tileScalesUm", doubleList(tileScalesUm));
        root.put("granularityScalesUm", doubleList(granularityScalesUm));
        root.put("depthBinWidthUm", Double.valueOf(depthBinWidthUm));
        root.put("rimDepthUm", Double.valueOf(rimDepthUm));
        root.put("textureClassCount", Integer.valueOf(textureClassCount));
        root.put("permutations", Integer.valueOf(permutations));
        root.put("seed", Long.valueOf(seed));
        root.put("failurePolicy", failurePolicy.name());
        return root;
    }

    public static IntensitySpatialConfig fromJsonObject(Object value) throws IOException {
        Map<String, Object> root = JsonIO.asObject(value);
        if (root.isEmpty()) {
            return disabled();
        }
        Set<AnalysisKey> analyses = parseAnalysisSet(root.get("analyses"));
        boolean enabled = JsonIO.booleanValue(first(root, "enabled", "spatial"), !analyses.isEmpty());
        return builder()
                .enabled(enabled)
                .enabledAnalyses(analyses)
                .mipEnabled(JsonIO.booleanValue(first(root, "mip", "mipEnabled"), false))
                .native3dEnabled(JsonIO.booleanValue(first(root, "native3d", "native3D", "native3dEnabled"), false))
                .overlaysEnabled(JsonIO.booleanValue(first(root, "overlays", "overlaysEnabled"), false))
                .shellWidthUm(doubleValue(first(root, "shellWidthUm", "shell_width_um"), DEFAULT_SHELL_WIDTH_UM))
                .shellCount(JsonIO.intValue(first(root, "shellCount", "shell_count"), DEFAULT_SHELL_COUNT))
                .tileScalesUm(doubleArray(first(root, "tileScalesUm", "tile_um"), DEFAULT_TILE_SCALES_UM))
                .granularityScalesUm(doubleArray(first(root, "granularityScalesUm", "granularity_um"),
                        DEFAULT_GRANULARITY_SCALES_UM))
                .depthBinWidthUm(doubleValue(first(root, "depthBinWidthUm", "depth_bin_um"),
                        DEFAULT_DEPTH_BIN_WIDTH_UM))
                .rimDepthUm(doubleValue(first(root, "rimDepthUm", "rim_depth_um"), DEFAULT_RIM_DEPTH_UM))
                .textureClassCount(JsonIO.intValue(first(root, "textureClassCount", "texture_k"),
                        DEFAULT_TEXTURE_CLASS_COUNT))
                .permutations(JsonIO.intValue(root.get("permutations"), DEFAULT_PERMUTATIONS))
                .seed(longValue(root.get("seed"), DEFAULT_SEED))
                .failurePolicy(FailurePolicy.parse(JsonIO.stringValue(first(root, "failurePolicy", "failure_policy"))))
                .build();
    }

    public static Set<AnalysisKey> parseAnalysisList(String raw) {
        return parseAnalysisSet(raw);
    }

    public static String joinAnalysisTokens(Set<AnalysisKey> analyses) {
        StringBuilder sb = new StringBuilder();
        for (AnalysisKey key : orderedCopy(analyses)) {
            if (sb.length() > 0) sb.append(',');
            sb.append(key.token());
        }
        return sb.toString();
    }

    public static String joinDoubles(double[] values) {
        if (values == null || values.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(formatDouble(values[i]));
        }
        return sb.toString();
    }

    public static double[] parseDoubleList(String raw, double[] fallback) {
        return doubleArray(raw, fallback);
    }

    private static Set<AnalysisKey> parseAnalysisSet(Object raw) {
        EnumSet<AnalysisKey> out = EnumSet.noneOf(AnalysisKey.class);
        if (raw instanceof Iterable) {
            for (Object item : (Iterable<?>) raw) {
                AnalysisKey key = AnalysisKey.parse(JsonIO.stringValue(item));
                if (key != null) out.add(key);
            }
            return immutableCopy(out);
        }
        String text = JsonIO.stringValue(raw);
        if (text == null || text.trim().isEmpty()) return immutableCopy(out);
        String[] parts = text.split(",");
        for (String part : parts) {
            AnalysisKey key = AnalysisKey.parse(part);
            if (key != null) out.add(key);
        }
        return immutableCopy(out);
    }

    private static List<String> analysisTokenList(Set<AnalysisKey> analyses) {
        List<String> out = new ArrayList<String>();
        for (AnalysisKey key : orderedCopy(analyses)) {
            out.add(key.token());
        }
        return out;
    }

    private static List<Double> doubleList(double[] values) {
        List<Double> out = new ArrayList<Double>();
        if (values != null) {
            for (double value : values) {
                out.add(Double.valueOf(value));
            }
        }
        return out;
    }

    private static Object first(Map<String, Object> root, String... keys) {
        for (String key : keys) {
            if (root.containsKey(key)) return root.get(key);
        }
        return null;
    }

    private static double[] doubleArray(Object raw, double[] fallback) {
        if (raw instanceof Iterable) {
            List<Double> values = new ArrayList<Double>();
            for (Object item : (Iterable<?>) raw) {
                double value = doubleValue(item, Double.NaN);
                if (!Double.isNaN(value) && !Double.isInfinite(value) && value > 0.0) {
                    values.add(Double.valueOf(value));
                }
            }
            if (!values.isEmpty()) {
                double[] out = new double[values.size()];
                for (int i = 0; i < values.size(); i++) out[i] = values.get(i).doubleValue();
                return out;
            }
            return fallback.clone();
        }
        String text = JsonIO.stringValue(raw);
        if (text == null || text.trim().isEmpty()) return fallback.clone();
        String[] parts = text.split(",");
        List<Double> values = new ArrayList<Double>();
        for (String part : parts) {
            double value = doubleValue(part, Double.NaN);
            if (!Double.isNaN(value) && !Double.isInfinite(value) && value > 0.0) {
                values.add(Double.valueOf(value));
            }
        }
        if (values.isEmpty()) return fallback.clone();
        double[] out = new double[values.size()];
        for (int i = 0; i < values.size(); i++) out[i] = values.get(i).doubleValue();
        return out;
    }

    private static double doubleValue(Object raw, double fallback) {
        if (raw instanceof Number) return ((Number) raw).doubleValue();
        String text = JsonIO.stringValue(raw);
        if (text == null || text.trim().isEmpty()) return fallback;
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long longValue(Object raw, long fallback) {
        if (raw instanceof Number) return ((Number) raw).longValue();
        String text = JsonIO.stringValue(raw);
        if (text == null || text.trim().isEmpty()) return fallback;
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean hasAnyBinarizedChannel(boolean[] channelBinarization) {
        if (channelBinarization == null) return false;
        for (boolean value : channelBinarization) {
            if (value) return true;
        }
        return false;
    }

    private static boolean removeCrossChannel(EnumSet<AnalysisKey> analyses) {
        boolean changed = false;
        for (AnalysisKey key : AnalysisKey.values()) {
            if (key.isCrossChannel()) {
                changed = analyses.remove(key) || changed;
            }
        }
        return changed;
    }

    private static boolean removeNative3d(EnumSet<AnalysisKey> analyses) {
        boolean changed = false;
        for (AnalysisKey key : AnalysisKey.values()) {
            if (key.isNative3d()) {
                changed = analyses.remove(key) || changed;
            }
        }
        return changed;
    }

    private static void log(LockLogger logger, String message) {
        if (logger != null) logger.log(message);
    }

    private static double positive(double value, double fallback) {
        return Double.isNaN(value) || Double.isInfinite(value) || value <= 0.0 ? fallback : value;
    }

    private static int positive(int value, int fallback) {
        return value <= 0 ? fallback : value;
    }

    private static int nonNegative(int value, int fallback) {
        return value < 0 ? fallback : value;
    }

    private static double[] sanitizeDoubles(double[] values, double[] fallback) {
        if (values == null || values.length == 0) return fallback.clone();
        List<Double> out = new ArrayList<Double>();
        for (double value : values) {
            if (!Double.isNaN(value) && !Double.isInfinite(value) && value > 0.0) {
                out.add(Double.valueOf(value));
            }
        }
        if (out.isEmpty()) return fallback.clone();
        double[] result = new double[out.size()];
        for (int i = 0; i < out.size(); i++) result[i] = out.get(i).doubleValue();
        return result;
    }

    private static Set<AnalysisKey> immutableCopy(Set<AnalysisKey> values) {
        return Collections.unmodifiableSet(orderedCopy(values));
    }

    private static EnumSet<AnalysisKey> mutableCopy(Set<AnalysisKey> values) {
        EnumSet<AnalysisKey> out = EnumSet.noneOf(AnalysisKey.class);
        if (values != null) out.addAll(values);
        return out;
    }

    private static EnumSet<AnalysisKey> orderedCopy(Set<AnalysisKey> values) {
        EnumSet<AnalysisKey> out = EnumSet.noneOf(AnalysisKey.class);
        if (values != null) out.addAll(values);
        return out;
    }

    private static String normalizeToken(String raw) {
        if (raw == null) return "";
        return raw.trim().toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "");
    }

    private static String formatDouble(double value) {
        if (Double.compare(value, Math.rint(value)) == 0) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.6f", value);
    }

    public static final class Builder {
        private boolean enabled = false;
        private Set<AnalysisKey> enabledAnalyses = EnumSet.noneOf(AnalysisKey.class);
        private boolean mipEnabled = false;
        private boolean native3dEnabled = false;
        private boolean overlaysEnabled = false;
        private double shellWidthUm = DEFAULT_SHELL_WIDTH_UM;
        private int shellCount = DEFAULT_SHELL_COUNT;
        private double[] tileScalesUm = DEFAULT_TILE_SCALES_UM.clone();
        private double[] granularityScalesUm = DEFAULT_GRANULARITY_SCALES_UM.clone();
        private double depthBinWidthUm = DEFAULT_DEPTH_BIN_WIDTH_UM;
        private double rimDepthUm = DEFAULT_RIM_DEPTH_UM;
        private int textureClassCount = DEFAULT_TEXTURE_CLASS_COUNT;
        private int permutations = DEFAULT_PERMUTATIONS;
        private long seed = DEFAULT_SEED;
        private FailurePolicy failurePolicy = FailurePolicy.SKIP_FAILED_ANALYSIS;

        private Builder() {
        }

        private Builder(IntensitySpatialConfig base) {
            if (base == null) return;
            this.enabled = base.enabled;
            this.enabledAnalyses = mutableCopy(base.enabledAnalyses);
            this.mipEnabled = base.mipEnabled;
            this.native3dEnabled = base.native3dEnabled;
            this.overlaysEnabled = base.overlaysEnabled;
            this.shellWidthUm = base.shellWidthUm;
            this.shellCount = base.shellCount;
            this.tileScalesUm = base.tileScalesUm.clone();
            this.granularityScalesUm = base.granularityScalesUm.clone();
            this.depthBinWidthUm = base.depthBinWidthUm;
            this.rimDepthUm = base.rimDepthUm;
            this.textureClassCount = base.textureClassCount;
            this.permutations = base.permutations;
            this.seed = base.seed;
            this.failurePolicy = base.failurePolicy;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder enabledAnalyses(Set<AnalysisKey> enabledAnalyses) {
            this.enabledAnalyses = mutableCopy(enabledAnalyses);
            return this;
        }

        public Builder addAnalysis(AnalysisKey key) {
            if (key != null) this.enabledAnalyses.add(key);
            return this;
        }

        public Builder mipEnabled(boolean mipEnabled) {
            this.mipEnabled = mipEnabled;
            return this;
        }

        public Builder native3dEnabled(boolean native3dEnabled) {
            this.native3dEnabled = native3dEnabled;
            return this;
        }

        public Builder overlaysEnabled(boolean overlaysEnabled) {
            this.overlaysEnabled = overlaysEnabled;
            return this;
        }

        public Builder shellWidthUm(double shellWidthUm) {
            this.shellWidthUm = shellWidthUm;
            return this;
        }

        public Builder shellCount(int shellCount) {
            this.shellCount = shellCount;
            return this;
        }

        public Builder tileScalesUm(double[] tileScalesUm) {
            this.tileScalesUm = tileScalesUm == null ? null : tileScalesUm.clone();
            return this;
        }

        public Builder granularityScalesUm(double[] granularityScalesUm) {
            this.granularityScalesUm = granularityScalesUm == null ? null : granularityScalesUm.clone();
            return this;
        }

        public Builder depthBinWidthUm(double depthBinWidthUm) {
            this.depthBinWidthUm = depthBinWidthUm;
            return this;
        }

        public Builder rimDepthUm(double rimDepthUm) {
            this.rimDepthUm = rimDepthUm;
            return this;
        }

        public Builder textureClassCount(int textureClassCount) {
            this.textureClassCount = textureClassCount;
            return this;
        }

        public Builder permutations(int permutations) {
            this.permutations = permutations;
            return this;
        }

        public Builder seed(long seed) {
            this.seed = seed;
            return this;
        }

        public Builder failurePolicy(FailurePolicy failurePolicy) {
            this.failurePolicy = failurePolicy;
            return this;
        }

        public IntensitySpatialConfig build() {
            return new IntensitySpatialConfig(this);
        }
    }
}
