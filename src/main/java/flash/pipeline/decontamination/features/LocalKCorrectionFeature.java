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
 * Fits local autofluorescence ratios in sliding 2D windows.
 */
public class LocalKCorrectionFeature implements CorrectionPipeline.ExecutableFeature {

    public static final String ID = "local_k_correction";

    private static final String WINDOW_RADIUS = "window_radius";
    private static final String QUIET_TARGET_PERCENTILE = "quiet_target_percentile";
    private static final String MIN_WINDOW_FIT_PIXELS = "min_window_fit_pixels";
    private static final String WRITE_PARAMETER_MAPS = "write_parameter_maps";
    private static final double PIVOT_ABSOLUTE_TOLERANCE = 1e-12;
    private static final double PIVOT_RELATIVE_TOLERANCE = 1e-8;

    private final Set<RequiredChannel> requiredChannels =
            Collections.singleton(RequiredChannel.AUTOFLUORESCENCE);

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Local k correction";
    }

    @Override
    public String getDescription() {
        return "Fits autofluorescence subtraction locally so different tissue regions can use different ratios.";
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
        return false;
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
            throw new IllegalArgumentException("Local k correction requires at least one autofluorescence channel.");
        }

        Settings settings = Settings.from(state.getFeatureSettings(ID));
        GlobalRatioCorrectionFeature.FitResult globalFit =
                GlobalRatioCorrectionFeature.fitGlobalCoefficients(
                        source,
                        targetChannel,
                        autofluorescenceChannels,
                        settings.getQuietTargetPercentile());

        int width = source.getWidth();
        int height = source.getHeight();
        int planeCount = CorrectionImageOps.planeCount(source);
        int channelCount = autofluorescenceChannels.size();
        int radius = Math.max(1, settings.getWindowRadius());
        int minWindowFitPixels = Math.max(3, settings.getMinWindowFitPixels());

        short[][] correctedPlanes = new short[planeCount][];
        float[][][] coefficientMapPlanes = settings.isWriteParameterMaps()
                ? new float[channelCount][planeCount][]
                : null;

        double[] coefficientSums = new double[channelCount];
        double[] coefficientMinimums = new double[channelCount];
        double[] coefficientMaximums = new double[channelCount];
        for (int i = 0; i < channelCount; i++) {
            coefficientMinimums[i] = Double.POSITIVE_INFINITY;
            coefficientMaximums[i] = Double.NEGATIVE_INFINITY;
        }

        long totalPixels = 0L;
        long localFitPixels = 0L;
        long fallbackPixels = 0L;
        long clampedLowPixels = 0L;
        long clampedHighPixels = 0L;

        for (int plane = 0; plane < planeCount; plane++) {
            short[] targetPixels = CorrectionImageOps.channelPlanePixels(source, targetChannel, plane);
            short[][] autoPixels = GlobalRatioCorrectionFeature.loadAutofluorescencePixels(
                    source,
                    autofluorescenceChannels,
                    plane);

            byte[] quietMask = buildQuietMask(targetPixels, autoPixels, globalFit.quietTargetThreshold);
            double[] quietCountIntegral = buildCountIntegral(quietMask, width, height);
            double[][] targetAutoIntegrals = buildTargetAutoIntegrals(
                    quietMask,
                    targetPixels,
                    autoPixels,
                    width,
                    height);
            double[][] autoAutoIntegrals = buildAutoAutoIntegrals(
                    quietMask,
                    autoPixels,
                    width,
                    height);

            short[] corrected = new short[targetPixels.length];
            float[][] planeMaps = settings.isWriteParameterMaps() ? new float[channelCount][] : null;
            if (planeMaps != null) {
                for (int channel = 0; channel < channelCount; channel++) {
                    planeMaps[channel] = new float[targetPixels.length];
                }
            }

            double[] coefficients = new double[channelCount];
            double[][] matrix = channelCount > 1 ? new double[channelCount][channelCount] : null;
            double[] rhs = channelCount > 1 ? new double[channelCount] : null;
            double[][] workMatrix = channelCount > 1 ? new double[channelCount][channelCount] : null;
            double[] workRhs = channelCount > 1 ? new double[channelCount] : null;

            for (int y = 0; y < height; y++) {
                int yStart = Math.max(0, y - radius);
                int yEnd = Math.min(height - 1, y + radius);
                for (int x = 0; x < width; x++) {
                    int xStart = Math.max(0, x - radius);
                    int xEnd = Math.min(width - 1, x + radius);
                    int pixel = y * width + x;

                    boolean usedLocalFit;
                    if (channelCount == 1) {
                        usedLocalFit = resolveSingleChannelCoefficient(
                                quietCountIntegral,
                                targetAutoIntegrals[0],
                                autoAutoIntegrals[0],
                                width,
                                xStart,
                                yStart,
                                xEnd,
                                yEnd,
                                minWindowFitPixels,
                                globalFit.coefficients[0],
                                coefficients);
                    } else {
                        usedLocalFit = resolveLocalCoefficients(
                                quietCountIntegral,
                                targetAutoIntegrals,
                                autoAutoIntegrals,
                                width,
                                channelCount,
                                xStart,
                                yStart,
                                xEnd,
                                yEnd,
                                minWindowFitPixels,
                                globalFit.coefficients,
                                coefficients,
                                matrix,
                                rhs,
                                workMatrix,
                                workRhs);
                    }

                    if (usedLocalFit) {
                        localFitPixels++;
                    } else {
                        fallbackPixels++;
                    }

                    totalPixels++;
                    double correctedValue = targetPixels[pixel] & 0xffff;
                    for (int channel = 0; channel < channelCount; channel++) {
                        correctedValue -= coefficients[channel] * (autoPixels[channel][pixel] & 0xffff);
                        coefficientSums[channel] += coefficients[channel];
                        if (coefficients[channel] < coefficientMinimums[channel]) {
                            coefficientMinimums[channel] = coefficients[channel];
                        }
                        if (coefficients[channel] > coefficientMaximums[channel]) {
                            coefficientMaximums[channel] = coefficients[channel];
                        }
                        if (planeMaps != null) {
                            planeMaps[channel][pixel] = (float) coefficients[channel];
                        }
                    }

                    if (correctedValue < 0.0) {
                        correctedValue = 0.0;
                        clampedLowPixels++;
                    } else if (correctedValue > 65535.0) {
                        correctedValue = 65535.0;
                        clampedHighPixels++;
                    }
                    corrected[pixel] = (short) Math.round(correctedValue);
                }
            }

            correctedPlanes[plane] = corrected;
            if (planeMaps != null) {
                for (int channel = 0; channel < channelCount; channel++) {
                    coefficientMapPlanes[channel][plane] = planeMaps[channel];
                }
            }
        }

        state.setCorrectedImage(CorrectionImageOps.createShortImageLike(
                source,
                "local_k_corrected_target",
                correctedPlanes));

        if (coefficientMapPlanes != null) {
            for (int channel = 0; channel < channelCount; channel++) {
                int channelIndex = autofluorescenceChannels.get(channel).intValue();
                state.putParameterMap(
                        parameterMapKeyForChannel(channelIndex),
                        CorrectionImageOps.createFloatImageLike(
                                source,
                                parameterMapKeyForChannel(channelIndex),
                                coefficientMapPlanes[channel]));
            }
        }

        CorrectionPipeline.FeatureSummary summary =
                new CorrectionPipeline.FeatureSummary(ID, getDisplayName())
                        .putInt(WINDOW_RADIUS, radius)
                        .putInt("window_diameter", radius * 2 + 1)
                        .putDouble(QUIET_TARGET_PERCENTILE, settings.getQuietTargetPercentile())
                        .putDouble("quiet_target_threshold", globalFit.quietTargetThreshold)
                        .putInt("fit_pixel_count", globalFit.fitPixelCount)
                        .putInt("saturated_pixels_excluded", globalFit.saturatedPixelCount)
                        .putInt("target_bright_pixels_excluded", globalFit.targetBrightPixelCount)
                        .putInt(MIN_WINDOW_FIT_PIXELS, minWindowFitPixels)
                        .putInt("local_fit_pixels", localFitPixels)
                        .putInt("fallback_pixels", fallbackPixels)
                        .put("parameter_maps_written", Boolean.toString(settings.isWriteParameterMaps()))
                        .putInt("clamped_low_pixels", clampedLowPixels)
                        .putInt("clamped_high_pixels", clampedHighPixels)
                        .putInt("autofluorescence_channel_count", channelCount);
        for (int channel = 0; channel < channelCount; channel++) {
            String label = CorrectionImageOps.channelLabel(autofluorescenceChannels.get(channel).intValue());
            double meanCoefficient = totalPixels <= 0L ? 0.0 : coefficientSums[channel] / (double) totalPixels;
            summary.putDouble("mean_coefficient_" + label, meanCoefficient);
            summary.putDouble("global_k_" + label, globalFit.coefficients[channel]);
            summary.putDouble("min_local_k_" + label,
                    coefficientMinimums[channel] == Double.POSITIVE_INFINITY ? 0.0 : coefficientMinimums[channel]);
            summary.putDouble("max_local_k_" + label,
                    coefficientMaximums[channel] == Double.NEGATIVE_INFINITY ? 0.0 : coefficientMaximums[channel]);
        }
        state.addSummary(summary);
    }

    public static String parameterMapKeyForChannel(int channelIndex) {
        return "local_k_coefficient_" + CorrectionImageOps.channelLabel(channelIndex);
    }

    private static byte[] buildQuietMask(short[] targetPixels,
                                         short[][] autoPixels,
                                         double quietTargetThreshold) {
        byte[] quietMask = new byte[targetPixels.length];
        for (int pixel = 0; pixel < targetPixels.length; pixel++) {
            double targetValue = targetPixels[pixel] & 0xffff;
            boolean quiet = targetValue < 65535.0
                    && !GlobalRatioCorrectionFeature.isSaturated(autoPixels, pixel)
                    && targetValue <= quietTargetThreshold;
            quietMask[pixel] = quiet ? (byte) 1 : (byte) 0;
        }
        return quietMask;
    }

    private static double[] buildCountIntegral(byte[] mask, int width, int height) {
        double[] integral = new double[(width + 1) * (height + 1)];
        int stride = width + 1;
        for (int y = 0; y < height; y++) {
            double rowSum = 0.0;
            int rowOffset = y * width;
            int integralRow = (y + 1) * stride;
            int integralAbove = y * stride;
            for (int x = 0; x < width; x++) {
                rowSum += mask[rowOffset + x] & 0xff;
                integral[integralRow + x + 1] = integral[integralAbove + x + 1] + rowSum;
            }
        }
        return integral;
    }

    private static double[][] buildTargetAutoIntegrals(byte[] quietMask,
                                                       short[] targetPixels,
                                                       short[][] autoPixels,
                                                       int width,
                                                       int height) {
        double[][] integrals = new double[autoPixels.length][];
        for (int channel = 0; channel < autoPixels.length; channel++) {
            integrals[channel] = buildProductIntegral(
                    quietMask,
                    targetPixels,
                    autoPixels[channel],
                    width,
                    height);
        }
        return integrals;
    }

    private static double[][] buildAutoAutoIntegrals(byte[] quietMask,
                                                     short[][] autoPixels,
                                                     int width,
                                                     int height) {
        int channelCount = autoPixels.length;
        int pairCount = pairCount(channelCount);
        double[][] integrals = new double[pairCount][];
        int pair = 0;
        for (int row = 0; row < channelCount; row++) {
            for (int col = row; col < channelCount; col++) {
                integrals[pair++] = buildProductIntegral(
                        quietMask,
                        autoPixels[row],
                        autoPixels[col],
                        width,
                        height);
            }
        }
        return integrals;
    }

    private static double[] buildProductIntegral(byte[] mask,
                                                 short[] left,
                                                 short[] right,
                                                 int width,
                                                 int height) {
        double[] integral = new double[(width + 1) * (height + 1)];
        int stride = width + 1;
        for (int y = 0; y < height; y++) {
            double rowSum = 0.0;
            int rowOffset = y * width;
            int integralRow = (y + 1) * stride;
            int integralAbove = y * stride;
            for (int x = 0; x < width; x++) {
                int pixel = rowOffset + x;
                double term = (mask[pixel] & 0xff) == 0
                        ? 0.0
                        : (left[pixel] & 0xffff) * (right[pixel] & 0xffff);
                rowSum += term;
                integral[integralRow + x + 1] = integral[integralAbove + x + 1] + rowSum;
            }
        }
        return integral;
    }

    private static boolean resolveSingleChannelCoefficient(double[] quietCountIntegral,
                                                           double[] targetAutoIntegral,
                                                           double[] autoAutoIntegral,
                                                           int width,
                                                           int xStart,
                                                           int yStart,
                                                           int xEnd,
                                                           int yEnd,
                                                           int minWindowFitPixels,
                                                           double globalCoefficient,
                                                           double[] coefficients) {
        double localCount = rectangleSum(quietCountIntegral, width, xStart, yStart, xEnd, yEnd);
        if (localCount < minWindowFitPixels) {
            coefficients[0] = globalCoefficient;
            return false;
        }
        double autoAuto = rectangleSum(autoAutoIntegral, width, xStart, yStart, xEnd, yEnd);
        if (Math.abs(autoAuto) < 1e-12) {
            coefficients[0] = globalCoefficient;
            return false;
        }
        double targetAuto = rectangleSum(targetAutoIntegral, width, xStart, yStart, xEnd, yEnd);
        coefficients[0] = GlobalRatioCorrectionFeature.sanitizeCoefficient(targetAuto / autoAuto);
        return true;
    }

    private static boolean resolveLocalCoefficients(double[] quietCountIntegral,
                                                    double[][] targetAutoIntegrals,
                                                    double[][] autoAutoIntegrals,
                                                    int width,
                                                    int channelCount,
                                                    int xStart,
                                                    int yStart,
                                                    int xEnd,
                                                    int yEnd,
                                                    int minWindowFitPixels,
                                                    double[] globalCoefficients,
                                                    double[] coefficients,
                                                    double[][] matrix,
                                                    double[] rhs,
                                                    double[][] workMatrix,
                                                    double[] workRhs) {
        double localCount = rectangleSum(quietCountIntegral, width, xStart, yStart, xEnd, yEnd);
        if (localCount < minWindowFitPixels) {
            copy(globalCoefficients, coefficients);
            return false;
        }

        for (int channel = 0; channel < channelCount; channel++) {
            rhs[channel] = rectangleSum(targetAutoIntegrals[channel], width, xStart, yStart, xEnd, yEnd);
        }
        int pair = 0;
        boolean informative = false;
        for (int row = 0; row < channelCount; row++) {
            for (int col = row; col < channelCount; col++) {
                double value = rectangleSum(autoAutoIntegrals[pair++], width, xStart, yStart, xEnd, yEnd);
                matrix[row][col] = value;
                matrix[col][row] = value;
                if (row == col && Math.abs(value) >= 1e-12) {
                    informative = true;
                }
            }
        }
        if (!informative) {
            copy(globalCoefficients, coefficients);
            return false;
        }

        boolean solved = solveLeastSquares(matrix, rhs, coefficients, workMatrix, workRhs);
        if (!solved) {
            copy(globalCoefficients, coefficients);
            return false;
        }
        for (int i = 0; i < coefficients.length; i++) {
            coefficients[i] = GlobalRatioCorrectionFeature.sanitizeCoefficient(coefficients[i]);
        }
        return true;
    }

    private static boolean solveLeastSquares(double[][] matrix,
                                             double[] rhs,
                                             double[] output,
                                             double[][] workMatrix,
                                             double[] workRhs) {
        int n = rhs.length;
        double pivotTolerance = pivotTolerance(matrix);
        for (int row = 0; row < n; row++) {
            System.arraycopy(matrix[row], 0, workMatrix[row], 0, n);
            workRhs[row] = rhs[row];
        }

        for (int pivot = 0; pivot < n; pivot++) {
            int bestRow = pivot;
            for (int row = pivot + 1; row < n; row++) {
                if (Math.abs(workMatrix[row][pivot]) > Math.abs(workMatrix[bestRow][pivot])) {
                    bestRow = row;
                }
            }
            if (Math.abs(workMatrix[bestRow][pivot]) <= pivotTolerance) {
                return diagonalFallback(matrix, rhs, output);
            }
            if (bestRow != pivot) {
                double[] tempRow = workMatrix[pivot];
                workMatrix[pivot] = workMatrix[bestRow];
                workMatrix[bestRow] = tempRow;
                double tempValue = workRhs[pivot];
                workRhs[pivot] = workRhs[bestRow];
                workRhs[bestRow] = tempValue;
            }
            for (int row = pivot + 1; row < n; row++) {
                double scale = workMatrix[row][pivot] / workMatrix[pivot][pivot];
                if (scale == 0.0) {
                    continue;
                }
                workRhs[row] -= scale * workRhs[pivot];
                for (int col = pivot; col < n; col++) {
                    workMatrix[row][col] -= scale * workMatrix[pivot][col];
                }
            }
        }

        for (int row = n - 1; row >= 0; row--) {
            if (Math.abs(workMatrix[row][row]) <= pivotTolerance) {
                return diagonalFallback(matrix, rhs, output);
            }
            double sum = workRhs[row];
            for (int col = row + 1; col < n; col++) {
                sum -= workMatrix[row][col] * output[col];
            }
            output[row] = sum / workMatrix[row][row];
        }
        return true;
    }

    private static boolean diagonalFallback(double[][] matrix, double[] rhs, double[] output) {
        boolean informative = false;
        for (int i = 0; i < rhs.length; i++) {
            double diagonal = matrix[i][i];
            if (Math.abs(diagonal) <= PIVOT_ABSOLUTE_TOLERANCE) {
                output[i] = 0.0;
            } else {
                output[i] = rhs[i] / diagonal;
                informative = true;
            }
        }
        return informative;
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

    private static int pairCount(int channelCount) {
        return (channelCount * (channelCount + 1)) / 2;
    }

    private static double rectangleSum(double[] integral,
                                       int width,
                                       int xStart,
                                       int yStart,
                                       int xEnd,
                                       int yEnd) {
        int stride = width + 1;
        int left = xStart;
        int right = xEnd + 1;
        int top = yStart;
        int bottom = yEnd + 1;
        return integral[bottom * stride + right]
                - integral[top * stride + right]
                - integral[bottom * stride + left]
                + integral[top * stride + left];
    }

    private static void copy(double[] source, double[] destination) {
        System.arraycopy(source, 0, destination, 0, source.length);
    }

    public static final class Settings {
        private int windowRadius = 2;
        private double quietTargetPercentile = 85.0;
        private int minWindowFitPixels = 9;
        private boolean writeParameterMaps = false;

        public int getWindowRadius() {
            return windowRadius;
        }

        public Settings setWindowRadius(int windowRadius) {
            this.windowRadius = windowRadius;
            return this;
        }

        public double getQuietTargetPercentile() {
            return quietTargetPercentile;
        }

        public Settings setQuietTargetPercentile(double quietTargetPercentile) {
            this.quietTargetPercentile = quietTargetPercentile;
            return this;
        }

        public int getMinWindowFitPixels() {
            return minWindowFitPixels;
        }

        public Settings setMinWindowFitPixels(int minWindowFitPixels) {
            this.minWindowFitPixels = minWindowFitPixels;
            return this;
        }

        public boolean isWriteParameterMaps() {
            return writeParameterMaps;
        }

        public Settings setWriteParameterMaps(boolean writeParameterMaps) {
            this.writeParameterMaps = writeParameterMaps;
            return this;
        }

        public CorrectionPipeline.Settings toPipelineSettings() {
            return new CorrectionPipeline.Settings()
                    .putInt(WINDOW_RADIUS, windowRadius)
                    .putDouble(QUIET_TARGET_PERCENTILE, quietTargetPercentile)
                    .putInt(MIN_WINDOW_FIT_PIXELS, minWindowFitPixels)
                    .put(WRITE_PARAMETER_MAPS, Boolean.toString(writeParameterMaps));
        }

        public static Settings from(CorrectionPipeline.Settings raw) {
            Settings settings = new Settings();
            if (raw == null) {
                return settings;
            }
            settings.setWindowRadius(raw.getInt(WINDOW_RADIUS, settings.windowRadius));
            settings.setQuietTargetPercentile(
                    raw.getDouble(QUIET_TARGET_PERCENTILE, settings.quietTargetPercentile));
            settings.setMinWindowFitPixels(
                    raw.getInt(MIN_WINDOW_FIT_PIXELS, settings.minWindowFitPixels));
            settings.setWriteParameterMaps(Boolean.parseBoolean(
                    raw.get(WRITE_PARAMETER_MAPS, Boolean.toString(settings.writeParameterMaps))));
            return settings;
        }
    }
}
