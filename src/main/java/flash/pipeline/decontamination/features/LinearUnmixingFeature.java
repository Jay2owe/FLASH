package flash.pipeline.decontamination.features;

import flash.pipeline.decontamination.CorrectionFeature;
import flash.pipeline.decontamination.CorrectionImageOps;
import flash.pipeline.decontamination.CorrectionPipeline;
import flash.pipeline.decontamination.SpectralDecontaminationConfig;
import ij.ImagePlus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Direct 16-bit linear unmixing for the target channel.
 */
public class LinearUnmixingFeature implements CorrectionPipeline.ExecutableFeature {

    public static final String ID = "linear_unmixing";

    private static final String SETTING_WEIGHT_MODE = "weight_mode";
    private static final String SETTING_FIT_PERCENTILE = "fit_percentile";
    private static final String MANUAL_WEIGHT_PREFIX = "manual_weight.";
    private static final double PIVOT_ABSOLUTE_TOLERANCE = 1e-12;
    private static final double PIVOT_RELATIVE_TOLERANCE = 1e-8;

    private final Set<RequiredChannel> requiredChannels =
            Collections.singleton(RequiredChannel.CONTAMINANT);

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Linear unmixing";
    }

    @Override
    public String getDescription() {
        return "Subtracts selected contaminant channels from the target with manual or fitted weights.";
    }

    @Override
    public InputType getRequiredInputType() {
        return InputType.SOURCE_IMAGE;
    }

    @Override
    public OutputType getOutputType() {
        return OutputType.CORRECTED_IMAGE;
    }

    @Override
    public Set<RequiredChannel> getRequiredChannels() {
        return requiredChannels;
    }

    @Override
    public boolean requiresConditions() {
        return false;
    }

    @Override
    public boolean requiresControls() {
        return false;
    }

    @Override
    public boolean requiresExistingObjectMaps() {
        return false;
    }

    @Override
    public boolean canPreviewCheaply() {
        return true;
    }

    @Override
    public boolean isExpertOnly() {
        return false;
    }

    @Override
    public boolean isThresholdFeature() {
        return false;
    }

    @Override
    public boolean requiresVetoMask() {
        return false;
    }

    @Override
    public void apply(CorrectionPipeline.ExecutionState state) {
        if (state == null) {
            throw new IllegalArgumentException("Execution state is required.");
        }
        SpectralDecontaminationConfig config = state.getConfig();
        if (config == null) {
            throw new IllegalArgumentException("Spectral Decontamination config is required.");
        }

        ImagePlus source = state.getSourceImage();
        CorrectionImageOps.require16Bit(source, "Source");
        int targetChannel = config.getTargetChannelIndex();
        List<Integer> contaminantChannels = CorrectionImageOps.contaminantChannels(config);
        if (targetChannel < 0) {
            throw new IllegalArgumentException("Target channel is not configured.");
        }
        if (contaminantChannels.isEmpty()) {
            throw new IllegalArgumentException("Linear unmixing requires at least one contaminant channel.");
        }

        Settings settings = Settings.from(state.getFeatureSettings(ID));
        FitResult fit = resolveWeights(settings, source, targetChannel, contaminantChannels);

        int planeCount = CorrectionImageOps.planeCount(source);
        short[][] correctedPlanes = new short[planeCount][];
        long clampedLowPixels = 0L;
        long clampedHighPixels = 0L;

        for (int plane = 0; plane < planeCount; plane++) {
            short[] targetPixels = CorrectionImageOps.channelPlanePixels(source, targetChannel, plane);
            short[][] contaminantPixels = new short[contaminantChannels.size()][];
            for (int i = 0; i < contaminantChannels.size(); i++) {
                contaminantPixels[i] = CorrectionImageOps.channelPlanePixels(
                        source,
                        contaminantChannels.get(i).intValue(),
                        plane);
            }

            short[] corrected = new short[targetPixels.length];
            for (int pixel = 0; pixel < targetPixels.length; pixel++) {
                double value = targetPixels[pixel] & 0xffff;
                for (int channel = 0; channel < contaminantPixels.length; channel++) {
                    value -= fit.weights[channel] * (contaminantPixels[channel][pixel] & 0xffff);
                }
                if (value < 0.0) {
                    value = 0.0;
                    clampedLowPixels++;
                } else if (value > 65535.0) {
                    value = 65535.0;
                    clampedHighPixels++;
                }
                corrected[pixel] = (short) Math.round(value);
            }
            correctedPlanes[plane] = corrected;
        }

        state.setCorrectedImage(CorrectionImageOps.createShortImageLike(
                source,
                "linear_unmixed_target",
                correctedPlanes));

        CorrectionPipeline.FeatureSummary summary = new CorrectionPipeline.FeatureSummary(ID, getDisplayName())
                .put("weight_mode", fit.weightMode.getKey())
                .putInt("contaminant_channel_count", contaminantChannels.size())
                .putInt("clamped_low_pixels", clampedLowPixels)
                .putInt("clamped_high_pixels", clampedHighPixels);
        if (fit.weightMode == WeightMode.FITTED) {
            summary.putDouble("fit_percentile", settings.getFitPercentile())
                    .putDouble("fit_quiet_threshold", fit.quietThreshold)
                    .putInt("fit_pixel_count", fit.fitPixelCount);
        }
        for (int i = 0; i < contaminantChannels.size(); i++) {
            int channelIndex = contaminantChannels.get(i).intValue();
            summary.putDouble("weight_" + CorrectionImageOps.channelLabel(channelIndex), fit.weights[i]);
        }
        state.addSummary(summary);
    }

    private static FitResult resolveWeights(Settings settings,
                                            ImagePlus source,
                                            int targetChannel,
                                            List<Integer> contaminantChannels) {
        if (settings.getWeightMode() == WeightMode.MANUAL) {
            double[] weights = new double[contaminantChannels.size()];
            for (int i = 0; i < contaminantChannels.size(); i++) {
                Integer channel = contaminantChannels.get(i);
                Double weight = settings.getManualWeights().get(channel);
                if (weight == null) {
                    throw new IllegalArgumentException(
                            "Manual linear unmixing requires a weight for channel " + (channel.intValue() + 1) + ".");
                }
                weights[i] = sanitizeWeight(weight.doubleValue());
            }
            return new FitResult(settings.getWeightMode(), weights, Double.NaN, 0);
        }
        return fitWeights(settings, source, targetChannel, contaminantChannels);
    }

    private static FitResult fitWeights(Settings settings,
                                        ImagePlus source,
                                        int targetChannel,
                                        List<Integer> contaminantChannels) {
        CorrectionImageOps.Histogram histogram =
                CorrectionImageOps.histogramForChannel(source, targetChannel);
        double quietThreshold = CorrectionImageOps.thresholdForMode(
                histogram,
                CorrectionImageOps.ThresholdMode.PERCENTILE,
                settings.getFitPercentile(),
                0.0);

        int channelCount = contaminantChannels.size();
        double[][] xtx = new double[channelCount][channelCount];
        double[] xty = new double[channelCount];
        int fitPixelCount = 0;

        int planeCount = CorrectionImageOps.planeCount(source);
        double[] x = new double[channelCount];
        for (int plane = 0; plane < planeCount; plane++) {
            short[] targetPixels = CorrectionImageOps.channelPlanePixels(source, targetChannel, plane);
            short[][] contaminantPixels = new short[channelCount][];
            for (int i = 0; i < channelCount; i++) {
                contaminantPixels[i] = CorrectionImageOps.channelPlanePixels(
                        source,
                        contaminantChannels.get(i).intValue(),
                        plane);
            }

            for (int pixel = 0; pixel < targetPixels.length; pixel++) {
                double targetValue = targetPixels[pixel] & 0xffff;
                if (targetValue > quietThreshold) continue;

                boolean informative = false;
                for (int i = 0; i < channelCount; i++) {
                    x[i] = contaminantPixels[i][pixel] & 0xffff;
                    if (x[i] > 0.0) informative = true;
                }
                if (!informative) continue;

                fitPixelCount++;
                for (int row = 0; row < channelCount; row++) {
                    xty[row] += x[row] * targetValue;
                    for (int col = row; col < channelCount; col++) {
                        xtx[row][col] += x[row] * x[col];
                    }
                }
            }
        }

        for (int row = 0; row < channelCount; row++) {
            for (int col = row + 1; col < channelCount; col++) {
                xtx[col][row] = xtx[row][col];
            }
        }

        double[] weights = fitPixelCount <= 0
                ? new double[channelCount]
                : solveLeastSquares(xtx, xty);
        for (int i = 0; i < weights.length; i++) {
            weights[i] = sanitizeWeight(weights[i]);
        }
        return new FitResult(WeightMode.FITTED, weights, quietThreshold, fitPixelCount);
    }

    private static double[] solveLeastSquares(double[][] matrix, double[] rhs) {
        int n = rhs.length;
        double[][] a = new double[n][n];
        double[] b = new double[n];
        double pivotTolerance = pivotTolerance(matrix);
        for (int row = 0; row < n; row++) {
            System.arraycopy(matrix[row], 0, a[row], 0, n);
            b[row] = rhs[row];
        }

        for (int pivot = 0; pivot < n; pivot++) {
            int bestRow = pivot;
            for (int row = pivot + 1; row < n; row++) {
                if (Math.abs(a[row][pivot]) > Math.abs(a[bestRow][pivot])) {
                    bestRow = row;
                }
            }
            if (Math.abs(a[bestRow][pivot]) <= pivotTolerance) {
                return diagonalFallback(matrix, rhs);
            }
            if (bestRow != pivot) {
                double[] tempRow = a[pivot];
                a[pivot] = a[bestRow];
                a[bestRow] = tempRow;
                double tempValue = b[pivot];
                b[pivot] = b[bestRow];
                b[bestRow] = tempValue;
            }

            for (int row = pivot + 1; row < n; row++) {
                double scale = a[row][pivot] / a[pivot][pivot];
                if (scale == 0.0) continue;
                b[row] -= scale * b[pivot];
                for (int col = pivot; col < n; col++) {
                    a[row][col] -= scale * a[pivot][col];
                }
            }
        }

        double[] solution = new double[n];
        for (int row = n - 1; row >= 0; row--) {
            if (Math.abs(a[row][row]) <= pivotTolerance) {
                return diagonalFallback(matrix, rhs);
            }
            double sum = b[row];
            for (int col = row + 1; col < n; col++) {
                sum -= a[row][col] * solution[col];
            }
            solution[row] = sum / a[row][row];
        }
        return solution;
    }

    private static double[] diagonalFallback(double[][] matrix, double[] rhs) {
        double[] solution = new double[rhs.length];
        for (int i = 0; i < rhs.length; i++) {
            double diagonal = matrix[i][i];
            solution[i] = Math.abs(diagonal) <= PIVOT_ABSOLUTE_TOLERANCE ? 0.0 : rhs[i] / diagonal;
        }
        return solution;
    }

    private static double pivotTolerance(double[][] matrix) {
        double maxAbs = 0.0;
        if (matrix != null) {
            for (double[] row : matrix) {
                if (row == null) continue;
                for (double value : row) {
                    double abs = Math.abs(value);
                    if (abs > maxAbs) {
                        maxAbs = abs;
                    }
                }
            }
        }
        return Math.max(PIVOT_ABSOLUTE_TOLERANCE, maxAbs * PIVOT_RELATIVE_TOLERANCE);
    }

    private static double sanitizeWeight(double weight) {
        if (Double.isNaN(weight) || Double.isInfinite(weight)) return 0.0;
        if (weight < 0.0) return 0.0;
        return weight;
    }

    private static final class FitResult {
        private final WeightMode weightMode;
        private final double[] weights;
        private final double quietThreshold;
        private final int fitPixelCount;

        private FitResult(WeightMode weightMode,
                          double[] weights,
                          double quietThreshold,
                          int fitPixelCount) {
            this.weightMode = weightMode;
            this.weights = weights;
            this.quietThreshold = quietThreshold;
            this.fitPixelCount = fitPixelCount;
        }
    }

    public enum WeightMode {
        MANUAL("manual"),
        FITTED("fitted");

        private final String key;

        WeightMode(String key) {
            this.key = key;
        }

        public String getKey() {
            return key;
        }

        public static WeightMode fromKey(String value, WeightMode defaultMode) {
            if (value == null || value.trim().isEmpty()) {
                return defaultMode == null ? FITTED : defaultMode;
            }
            String normalized = value.trim().toLowerCase(Locale.US);
            for (WeightMode mode : values()) {
                if (mode.key.equals(normalized) || mode.name().equalsIgnoreCase(normalized)) {
                    return mode;
                }
            }
            return defaultMode == null ? FITTED : defaultMode;
        }
    }

    public static final class Settings {
        private WeightMode weightMode = WeightMode.FITTED;
        private double fitPercentile = 50.0;
        private final LinkedHashMap<Integer, Double> manualWeights =
                new LinkedHashMap<Integer, Double>();

        public WeightMode getWeightMode() {
            return weightMode;
        }

        public Settings setWeightMode(WeightMode weightMode) {
            this.weightMode = weightMode == null ? WeightMode.FITTED : weightMode;
            return this;
        }

        public double getFitPercentile() {
            return fitPercentile;
        }

        public Settings setFitPercentile(double fitPercentile) {
            this.fitPercentile = fitPercentile;
            return this;
        }

        public Settings setManualWeight(int channelIndex, double weight) {
            manualWeights.put(Integer.valueOf(channelIndex), Double.valueOf(weight));
            return this;
        }

        public Map<Integer, Double> getManualWeights() {
            return Collections.unmodifiableMap(manualWeights);
        }

        public CorrectionPipeline.Settings toPipelineSettings() {
            CorrectionPipeline.Settings raw = new CorrectionPipeline.Settings()
                    .put(SETTING_WEIGHT_MODE, weightMode.getKey())
                    .putDouble(SETTING_FIT_PERCENTILE, fitPercentile);
            for (Map.Entry<Integer, Double> entry : manualWeights.entrySet()) {
                raw.putDouble(MANUAL_WEIGHT_PREFIX + entry.getKey().intValue(),
                        entry.getValue().doubleValue());
            }
            return raw;
        }

        public static Settings from(CorrectionPipeline.Settings raw) {
            Settings settings = new Settings();
            if (raw == null) return settings;
            settings.setWeightMode(WeightMode.fromKey(
                    raw.get(SETTING_WEIGHT_MODE, settings.weightMode.getKey()),
                    settings.weightMode));
            settings.setFitPercentile(raw.getDouble(SETTING_FIT_PERCENTILE, settings.fitPercentile));
            for (Map.Entry<String, String> entry : raw.getValues().entrySet()) {
                String key = entry.getKey();
                if (!key.startsWith(MANUAL_WEIGHT_PREFIX)) continue;
                try {
                    int channelIndex = Integer.parseInt(key.substring(MANUAL_WEIGHT_PREFIX.length()));
                    settings.setManualWeight(channelIndex, Double.parseDouble(entry.getValue()));
                } catch (NumberFormatException ignored) {
                    // Ignore invalid persisted manual weights and fall back to defaults.
                }
            }
            return settings;
        }
    }
}
