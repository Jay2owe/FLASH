package flash.pipeline.decontamination.features;

import flash.pipeline.decontamination.CorrectionFeature;
import flash.pipeline.decontamination.CorrectionImageOps;
import flash.pipeline.decontamination.CorrectionPipeline;
import flash.pipeline.decontamination.SpectralDecontaminationConfig;
import ij.ImagePlus;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Fits one image-level autofluorescence ratio and subtracts it from the target.
 */
public class GlobalRatioCorrectionFeature implements CorrectionPipeline.ExecutableFeature {

    public static final String ID = "global_ratio_correction";

    private static final String QUIET_TARGET_PERCENTILE = "quiet_target_percentile";
    private static final double SATURATION_VALUE = 65535.0;

    private final Set<RequiredChannel> requiredChannels =
            Collections.singleton(RequiredChannel.AUTOFLUORESCENCE);

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Global ratio correction";
    }

    @Override
    public String getDescription() {
        return "Fits one image-level autofluorescence ratio before subtracting it from the target.";
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
        List<Integer> autofluorescenceChannels = config.getAutofluorescenceChannelIndexes();
        if (targetChannel < 0) {
            throw new IllegalArgumentException("Target channel is not configured.");
        }
        if (autofluorescenceChannels == null || autofluorescenceChannels.isEmpty()) {
            throw new IllegalArgumentException("Global ratio correction requires at least one autofluorescence channel.");
        }

        Settings settings = Settings.from(state.getFeatureSettings(ID));
        FitResult fit = fitGlobalCoefficients(
                source,
                targetChannel,
                autofluorescenceChannels,
                settings.getQuietTargetPercentile());

        int planeCount = CorrectionImageOps.planeCount(source);
        short[][] correctedPlanes = new short[planeCount][];
        long clampedLowPixels = 0L;
        long clampedHighPixels = 0L;

        for (int plane = 0; plane < planeCount; plane++) {
            short[] targetPixels = CorrectionImageOps.channelPlanePixels(source, targetChannel, plane);
            short[][] autoPixels = loadAutofluorescencePixels(source, autofluorescenceChannels, plane);
            short[] corrected = new short[targetPixels.length];
            for (int pixel = 0; pixel < targetPixels.length; pixel++) {
                double value = targetPixels[pixel] & 0xffff;
                for (int channel = 0; channel < autoPixels.length; channel++) {
                    value -= fit.coefficients[channel] * (autoPixels[channel][pixel] & 0xffff);
                }
                if (value < 0.0) {
                    value = 0.0;
                    clampedLowPixels++;
                } else if (value > SATURATION_VALUE) {
                    value = SATURATION_VALUE;
                    clampedHighPixels++;
                }
                corrected[pixel] = (short) Math.round(value);
            }
            correctedPlanes[plane] = corrected;
        }

        state.setCorrectedImage(CorrectionImageOps.createShortImageLike(
                source,
                "global_ratio_corrected_target",
                correctedPlanes));

        CorrectionPipeline.FeatureSummary summary =
                new CorrectionPipeline.FeatureSummary(ID, getDisplayName())
                        .putDouble(QUIET_TARGET_PERCENTILE, settings.getQuietTargetPercentile())
                        .putDouble("quiet_target_threshold", fit.quietTargetThreshold)
                        .putInt("fit_pixel_count", fit.fitPixelCount)
                        .putInt("saturated_pixels_excluded", fit.saturatedPixelCount)
                        .putInt("target_bright_pixels_excluded", fit.targetBrightPixelCount)
                        .putInt("clamped_low_pixels", clampedLowPixels)
                        .putInt("clamped_high_pixels", clampedHighPixels)
                        .putInt("autofluorescence_channel_count", autofluorescenceChannels.size());
        for (int i = 0; i < autofluorescenceChannels.size(); i++) {
            summary.putDouble("coefficient_" + CorrectionImageOps.channelLabel(
                    autofluorescenceChannels.get(i).intValue()), fit.coefficients[i]);
        }
        state.addSummary(summary);
    }

    static FitResult fitGlobalCoefficients(ImagePlus source,
                                           int targetChannel,
                                           List<Integer> autofluorescenceChannels,
                                           double quietTargetPercentile) {
        if (source == null) {
            throw new IllegalArgumentException("Source image is required.");
        }
        if (autofluorescenceChannels == null || autofluorescenceChannels.isEmpty()) {
            throw new IllegalArgumentException("At least one autofluorescence channel is required.");
        }

        double quietThreshold = CorrectionImageOps.thresholdForMode(
                CorrectionImageOps.histogramForChannel(source, targetChannel),
                CorrectionImageOps.ThresholdMode.PERCENTILE,
                quietTargetPercentile,
                0.0);

        int channelCount = autofluorescenceChannels.size();
        double[][] xtx = new double[channelCount][channelCount];
        double[] xty = new double[channelCount];
        double[] sample = new double[channelCount];
        int fitPixelCount = 0;
        int saturatedPixelCount = 0;
        int targetBrightPixelCount = 0;

        int planeCount = CorrectionImageOps.planeCount(source);
        for (int plane = 0; plane < planeCount; plane++) {
            short[] targetPixels = CorrectionImageOps.channelPlanePixels(source, targetChannel, plane);
            short[][] autoPixels = loadAutofluorescencePixels(source, autofluorescenceChannels, plane);

            for (int pixel = 0; pixel < targetPixels.length; pixel++) {
                double targetValue = targetPixels[pixel] & 0xffff;
                if (targetValue >= SATURATION_VALUE || isSaturated(autoPixels, pixel)) {
                    saturatedPixelCount++;
                    continue;
                }
                if (targetValue > quietThreshold) {
                    targetBrightPixelCount++;
                    continue;
                }

                boolean informative = false;
                for (int channel = 0; channel < channelCount; channel++) {
                    sample[channel] = autoPixels[channel][pixel] & 0xffff;
                    if (sample[channel] > 0.0) {
                        informative = true;
                    }
                }
                if (!informative) {
                    continue;
                }

                fitPixelCount++;
                for (int row = 0; row < channelCount; row++) {
                    xty[row] += sample[row] * targetValue;
                    for (int col = row; col < channelCount; col++) {
                        xtx[row][col] += sample[row] * sample[col];
                    }
                }
            }
        }

        symmetrize(xtx);
        double[] coefficients = fitPixelCount <= 0
                ? new double[channelCount]
                : solveLeastSquares(xtx, xty);
        for (int i = 0; i < coefficients.length; i++) {
            coefficients[i] = sanitizeCoefficient(coefficients[i]);
        }
        return new FitResult(coefficients, quietThreshold, fitPixelCount,
                saturatedPixelCount, targetBrightPixelCount);
    }

    static short[][] loadAutofluorescencePixels(ImagePlus source,
                                                List<Integer> autofluorescenceChannels,
                                                int plane) {
        short[][] autoPixels = new short[autofluorescenceChannels.size()][];
        for (int i = 0; i < autofluorescenceChannels.size(); i++) {
            autoPixels[i] = CorrectionImageOps.channelPlanePixels(
                    source,
                    autofluorescenceChannels.get(i).intValue(),
                    plane);
        }
        return autoPixels;
    }

    static boolean isSaturated(short[][] autoPixels, int pixel) {
        if (autoPixels == null) {
            return false;
        }
        for (short[] pixels : autoPixels) {
            if (pixels != null && (pixels[pixel] & 0xffff) >= SATURATION_VALUE) {
                return true;
            }
        }
        return false;
    }

    static double sanitizeCoefficient(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        if (value < 0.0) {
            return 0.0;
        }
        return value;
    }

    static double[] solveLeastSquares(double[][] matrix, double[] rhs) {
        int n = rhs.length;
        double[][] a = new double[n][n];
        double[] b = new double[n];
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
            if (Math.abs(a[bestRow][pivot]) < 1e-12) {
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
                if (scale == 0.0) {
                    continue;
                }
                b[row] -= scale * b[pivot];
                for (int col = pivot; col < n; col++) {
                    a[row][col] -= scale * a[pivot][col];
                }
            }
        }

        double[] solution = new double[n];
        for (int row = n - 1; row >= 0; row--) {
            if (Math.abs(a[row][row]) < 1e-12) {
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
            solution[i] = Math.abs(diagonal) < 1e-12 ? 0.0 : rhs[i] / diagonal;
        }
        return solution;
    }

    private static void symmetrize(double[][] matrix) {
        for (int row = 0; row < matrix.length; row++) {
            for (int col = row + 1; col < matrix[row].length; col++) {
                matrix[col][row] = matrix[row][col];
            }
        }
    }

    static final class FitResult {
        final double[] coefficients;
        final double quietTargetThreshold;
        final int fitPixelCount;
        final int saturatedPixelCount;
        final int targetBrightPixelCount;

        FitResult(double[] coefficients,
                  double quietTargetThreshold,
                  int fitPixelCount,
                  int saturatedPixelCount,
                  int targetBrightPixelCount) {
            this.coefficients = coefficients;
            this.quietTargetThreshold = quietTargetThreshold;
            this.fitPixelCount = fitPixelCount;
            this.saturatedPixelCount = saturatedPixelCount;
            this.targetBrightPixelCount = targetBrightPixelCount;
        }
    }

    public static final class Settings {
        private double quietTargetPercentile = 85.0;

        public double getQuietTargetPercentile() {
            return quietTargetPercentile;
        }

        public Settings setQuietTargetPercentile(double quietTargetPercentile) {
            this.quietTargetPercentile = quietTargetPercentile;
            return this;
        }

        public CorrectionPipeline.Settings toPipelineSettings() {
            return new CorrectionPipeline.Settings()
                    .putDouble(QUIET_TARGET_PERCENTILE, quietTargetPercentile);
        }

        public static Settings from(CorrectionPipeline.Settings raw) {
            Settings settings = new Settings();
            if (raw == null) {
                return settings;
            }
            settings.setQuietTargetPercentile(
                    raw.getDouble(QUIET_TARGET_PERCENTILE, settings.quietTargetPercentile));
            return settings;
        }
    }
}
