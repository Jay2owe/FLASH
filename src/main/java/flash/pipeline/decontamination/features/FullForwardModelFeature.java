package flash.pipeline.decontamination.features;

import flash.pipeline.decontamination.CorrectionFeature;
import flash.pipeline.decontamination.CorrectionImageOps;
import flash.pipeline.decontamination.CorrectionPipeline;
import flash.pipeline.decontamination.SpectralDecontaminationConfig;
import ij.ImagePlus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Expert-only forward model that combines local autofluorescence correction,
 * bleed-through source purification, and per-image bleed-through subtraction.
 */
public class FullForwardModelFeature implements CorrectionPipeline.ExecutableFeature {

    public static final String ID = "full_forward_model";

    private static final String WINDOW_RADIUS = "window_radius";
    private static final String QUIET_TARGET_PERCENTILE = "quiet_target_percentile";
    private static final String SOURCE_QUIET_PERCENTILE = "source_quiet_percentile";
    private static final String SOURCE_BRIGHT_PERCENTILE = "source_bright_percentile";
    private static final String MIN_LOCAL_FIT_PIXELS = "min_local_fit_pixels";
    private static final String MIN_BLEED_FIT_PIXELS = "min_bleed_fit_pixels";
    private static final String WRITE_PARAMETER_MAPS = "write_parameter_maps";

    private static final double MAX_16BIT_VALUE = 65535.0;
    private static final int WARNING_FIT_POOL_PIXELS = 24;
    private static final double WARNING_FALLBACK_FRACTION = 0.50;
    private static final double WARNING_SPLIT_ABS_DELTA = 0.15;
    private static final double WARNING_SPLIT_RELATIVE_DELTA = 0.60;
    private static final double PIVOT_ABSOLUTE_TOLERANCE = 1e-12;
    private static final double PIVOT_RELATIVE_TOLERANCE = 1e-8;

    private final Set<RequiredChannel> requiredChannels =
            Collections.unmodifiableSet(EnumSet.of(
                    RequiredChannel.BLEED_THROUGH,
                    RequiredChannel.AUTOFLUORESCENCE));

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Full forward model";
    }

    @Override
    public String getDescription() {
        return "Combines local autofluorescence correction, bleed-through source purification, and per-image bleed-through subtraction.";
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
        return true;
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
        List<Integer> bleedThroughChannels = config.getBleedThroughChannelIndexes();
        if (targetChannel < 0) {
            throw new IllegalArgumentException("Target channel is not configured.");
        }
        if (autofluorescenceChannels == null || autofluorescenceChannels.isEmpty()) {
            throw new IllegalArgumentException("Full forward model requires at least one autofluorescence channel.");
        }
        if (bleedThroughChannels == null || bleedThroughChannels.isEmpty()) {
            throw new IllegalArgumentException("Full forward model requires at least one bleed-through channel.");
        }

        Settings settings = Settings.from(state.getFeatureSettings(ID));
        byte[][] saturationMasks = buildSaturationMasks(
                source,
                targetChannel,
                bleedThroughChannels,
                autofluorescenceChannels);

        TargetCorrectionResult targetCorrection = correctTargetAutofluorescence(
                source,
                targetChannel,
                autofluorescenceChannels,
                saturationMasks,
                settings);

        int planeCount = CorrectionImageOps.planeCount(source);
        int bleedCount = bleedThroughChannels.size();
        short[][][] purifiedSourcePlanes = new short[bleedCount][planeCount][];
        List<SourcePurificationResult> sourcePurifications =
                new ArrayList<SourcePurificationResult>(bleedCount);
        for (int i = 0; i < bleedCount; i++) {
            int bleedChannel = bleedThroughChannels.get(i).intValue();
            SourcePurificationResult purification = purifyBleedSource(
                    source,
                    bleedChannel,
                    autofluorescenceChannels,
                    saturationMasks,
                    settings);
            sourcePurifications.add(purification);
            purifiedSourcePlanes[i] = purification.purifiedPlanes;
        }

        BleedFitResult bleedFit = fitBleedCoefficients(
                targetCorrection.correctedPlanes,
                purifiedSourcePlanes,
                saturationMasks,
                settings);

        short[][] correctedPlanes = subtractBleedContribution(
                targetCorrection.correctedPlanes,
                purifiedSourcePlanes,
                bleedFit.coefficients);
        state.setCorrectedImage(CorrectionImageOps.createShortImageLike(
                source,
                "full_forward_model_corrected_target",
                correctedPlanes));

        if (settings.isWriteParameterMaps() && targetCorrection.coefficientMapPlanes != null) {
            for (int channel = 0; channel < autofluorescenceChannels.size(); channel++) {
                int channelIndex = autofluorescenceChannels.get(channel).intValue();
                state.putParameterMap(
                        parameterMapKeyForChannel(channelIndex),
                        CorrectionImageOps.createFloatImageLike(
                                source,
                                parameterMapKeyForChannel(channelIndex),
                                targetCorrection.coefficientMapPlanes[channel]));
            }
        }

        List<String> warnings = collectWarnings(targetCorrection, sourcePurifications, bleedFit);
        state.addSummary(buildSummary(
                targetChannel,
                autofluorescenceChannels,
                bleedThroughChannels,
                settings,
                targetCorrection,
                sourcePurifications,
                bleedFit,
                warnings));
    }

    public static String parameterMapKeyForChannel(int channelIndex) {
        return "full_model_auto_coefficient_" + CorrectionImageOps.channelLabel(channelIndex);
    }

    private static CorrectionPipeline.FeatureSummary buildSummary(
            int targetChannel,
            List<Integer> autofluorescenceChannels,
            List<Integer> bleedThroughChannels,
            Settings settings,
            TargetCorrectionResult targetCorrection,
            List<SourcePurificationResult> sourcePurifications,
            BleedFitResult bleedFit,
            List<String> warnings) {
        CorrectionPipeline.FeatureSummary summary =
                new CorrectionPipeline.FeatureSummary(ID, "Full forward model")
                        .putInt(WINDOW_RADIUS, settings.getWindowRadius())
                        .putDouble(QUIET_TARGET_PERCENTILE, settings.getQuietTargetPercentile())
                        .putDouble("target_quiet_threshold", targetCorrection.fit.quietThreshold)
                        .putInt("target_fit_pixel_count", targetCorrection.fit.fitPixelCount)
                        .putInt("target_saturated_pixels_excluded", targetCorrection.fit.saturatedPixelCount)
                        .putInt("target_bright_pixels_excluded", targetCorrection.fit.brightPixelCount)
                        .putInt(MIN_LOCAL_FIT_PIXELS, settings.getMinLocalFitPixels())
                        .putInt("local_fit_pixels", targetCorrection.localFitPixels)
                        .putInt("fallback_pixels", targetCorrection.fallbackPixels)
                        .putDouble("fallback_fraction", targetCorrection.totalPixels <= 0L
                                ? 0.0
                                : (double) targetCorrection.fallbackPixels / (double) targetCorrection.totalPixels)
                        .putInt("clamped_low_pixels", targetCorrection.clampedLowPixels)
                        .putInt("clamped_high_pixels", targetCorrection.clampedHighPixels)
                        .putInt("autofluorescence_channel_count", autofluorescenceChannels.size())
                        .putInt("bleed_through_channel_count", bleedThroughChannels.size())
                        .putDouble(SOURCE_QUIET_PERCENTILE, settings.getSourceQuietPercentile())
                        .putDouble(SOURCE_BRIGHT_PERCENTILE, settings.getSourceBrightPercentile())
                        .putInt(MIN_BLEED_FIT_PIXELS, settings.getMinBleedFitPixels())
                        .putDouble("bleed_fit_target_threshold", bleedFit.targetThreshold)
                        .putInt("bleed_fit_pixel_count", bleedFit.fitPixelCount)
                        .putInt("bleed_split_pool_a_pixels", bleedFit.splitPoolAPixels)
                        .putInt("bleed_split_pool_b_pixels", bleedFit.splitPoolBPixels)
                        .putDouble("bleed_split_max_abs_delta", bleedFit.maxSplitAbsDelta)
                        .putDouble("bleed_split_max_relative_delta", bleedFit.maxSplitRelativeDelta)
                        .put("parameter_maps_written", Boolean.toString(settings.isWriteParameterMaps()));

        for (int channel = 0; channel < autofluorescenceChannels.size(); channel++) {
            String autoLabel = CorrectionImageOps.channelLabel(autofluorescenceChannels.get(channel).intValue());
            double meanLocalCoefficient = targetCorrection.totalPixels <= 0L
                    ? 0.0
                    : targetCorrection.coefficientSums[channel] / (double) targetCorrection.totalPixels;
            summary.putDouble("target_global_auto_coefficient_" + autoLabel, targetCorrection.fit.coefficients[channel]);
            summary.putDouble("target_mean_local_auto_coefficient_" + autoLabel, meanLocalCoefficient);
            summary.putDouble("target_min_local_auto_coefficient_" + autoLabel,
                    targetCorrection.coefficientMinimums[channel] == Double.POSITIVE_INFINITY
                            ? 0.0
                            : targetCorrection.coefficientMinimums[channel]);
            summary.putDouble("target_max_local_auto_coefficient_" + autoLabel,
                    targetCorrection.coefficientMaximums[channel] == Double.NEGATIVE_INFINITY
                            ? 0.0
                            : targetCorrection.coefficientMaximums[channel]);
        }

        for (int sourceIndex = 0; sourceIndex < sourcePurifications.size(); sourceIndex++) {
            SourcePurificationResult purification = sourcePurifications.get(sourceIndex);
            String sourceLabel = CorrectionImageOps.channelLabel(bleedThroughChannels.get(sourceIndex).intValue());
            summary.putDouble("source_quiet_threshold_" + sourceLabel, purification.fit.quietThreshold);
            summary.putInt("source_fit_pixel_count_" + sourceLabel, purification.fit.fitPixelCount);
            summary.putInt("source_saturated_pixels_excluded_" + sourceLabel, purification.fit.saturatedPixelCount);
            summary.putInt("source_bright_pixels_excluded_" + sourceLabel, purification.fit.brightPixelCount);
            summary.putInt("source_clamped_low_pixels_" + sourceLabel, purification.clampedLowPixels);
            summary.putInt("source_clamped_high_pixels_" + sourceLabel, purification.clampedHighPixels);
            for (int autoIndex = 0; autoIndex < autofluorescenceChannels.size(); autoIndex++) {
                String autoLabel = CorrectionImageOps.channelLabel(autofluorescenceChannels.get(autoIndex).intValue());
                summary.putDouble("source_purification_coefficient_" + sourceLabel + "_" + autoLabel,
                        purification.fit.coefficients[autoIndex]);
            }
        }

        for (int sourceIndex = 0; sourceIndex < bleedThroughChannels.size(); sourceIndex++) {
            String sourceLabel = CorrectionImageOps.channelLabel(bleedThroughChannels.get(sourceIndex).intValue());
            summary.putDouble("bleed_fit_threshold_" + sourceLabel, bleedFit.sourceThresholds[sourceIndex]);
            summary.putDouble("bleed_coefficient_" + sourceLabel, bleedFit.coefficients[sourceIndex]);
        }

        summary.putDouble("target_channel_index", targetChannel + 1);
        summary.putInt("warning_count", warnings.size());
        for (int i = 0; i < warnings.size(); i++) {
            summary.put("warning_" + (i + 1), warnings.get(i));
        }
        return summary;
    }

    private static List<String> collectWarnings(TargetCorrectionResult targetCorrection,
                                                List<SourcePurificationResult> sourcePurifications,
                                                BleedFitResult bleedFit) {
        List<String> warnings = new ArrayList<String>();
        if (targetCorrection.fit.fitPixelCount < WARNING_FIT_POOL_PIXELS) {
            warnings.add("Target autofluorescence fit pool is small (" + targetCorrection.fit.fitPixelCount
                    + " pixels).");
        }
        if (targetCorrection.totalPixels > 0L
                && (double) targetCorrection.fallbackPixels / (double) targetCorrection.totalPixels
                > WARNING_FALLBACK_FRACTION) {
            warnings.add("Local autofluorescence fallback was used for more than 50% of pixels.");
        }
        for (SourcePurificationResult purification : sourcePurifications) {
            if (purification.fit.fitPixelCount < WARNING_FIT_POOL_PIXELS) {
                warnings.add("Bleed-through source purification pool is small for "
                        + CorrectionImageOps.channelLabel(purification.sourceChannel) + " ("
                        + purification.fit.fitPixelCount + " pixels).");
            }
        }
        if (bleedFit.fitPixelCount < WARNING_FIT_POOL_PIXELS) {
            warnings.add("Bleed-through coefficient pool is small (" + bleedFit.fitPixelCount + " pixels).");
        }
        if (bleedFit.maxSplitAbsDelta > WARNING_SPLIT_ABS_DELTA
                && bleedFit.maxSplitRelativeDelta > WARNING_SPLIT_RELATIVE_DELTA) {
            warnings.add("Bleed-through coefficients vary strongly across split fit pools.");
        }
        return warnings;
    }

    private static short[][] subtractBleedContribution(short[][] targetCorrectedPlanes,
                                                       short[][][] purifiedSourcePlanes,
                                                       double[] coefficients) {
        short[][] correctedPlanes = new short[targetCorrectedPlanes.length][];
        for (int plane = 0; plane < targetCorrectedPlanes.length; plane++) {
            short[] corrected = new short[targetCorrectedPlanes[plane].length];
            for (int pixel = 0; pixel < corrected.length; pixel++) {
                double value = targetCorrectedPlanes[plane][pixel] & 0xffff;
                for (int source = 0; source < purifiedSourcePlanes.length; source++) {
                    value -= coefficients[source] * (purifiedSourcePlanes[source][plane][pixel] & 0xffff);
                }
                corrected[pixel] = (short) Math.round(clamp16Bit(value));
            }
            correctedPlanes[plane] = corrected;
        }
        return correctedPlanes;
    }

    private static BleedFitResult fitBleedCoefficients(short[][] targetCorrectedPlanes,
                                                       short[][][] purifiedSourcePlanes,
                                                       byte[][] saturationMasks,
                                                       Settings settings) {
        int sourceCount = purifiedSourcePlanes.length;
        double[] sourceThresholds = new double[sourceCount];
        for (int source = 0; source < sourceCount; source++) {
            sourceThresholds[source] = percentileForShortPlanes(
                    purifiedSourcePlanes[source],
                    settings.getSourceBrightPercentile());
        }
        double targetThreshold = percentileForShortPlanes(
                targetCorrectedPlanes,
                settings.getQuietTargetPercentile());

        double[][] xtx = new double[sourceCount][sourceCount];
        double[] xty = new double[sourceCount];
        double[][] xtxA = new double[sourceCount][sourceCount];
        double[] xtyA = new double[sourceCount];
        double[][] xtxB = new double[sourceCount][sourceCount];
        double[] xtyB = new double[sourceCount];
        double[] sample = new double[sourceCount];
        int fitPixelCount = 0;
        int splitPoolAPixels = 0;
        int splitPoolBPixels = 0;

        for (int plane = 0; plane < targetCorrectedPlanes.length; plane++) {
            short[] targetPixels = targetCorrectedPlanes[plane];
            for (int pixel = 0; pixel < targetPixels.length; pixel++) {
                if ((saturationMasks[plane][pixel] & 0xff) != 0) {
                    continue;
                }
                double targetValue = targetPixels[pixel] & 0xffff;
                if (targetValue > targetThreshold) {
                    continue;
                }

                boolean sourceBright = false;
                boolean informative = false;
                for (int source = 0; source < sourceCount; source++) {
                    double value = purifiedSourcePlanes[source][plane][pixel] & 0xffff;
                    sample[source] = value;
                    if (value > 0.0) {
                        informative = true;
                    }
                    if (value >= sourceThresholds[source]) {
                        sourceBright = true;
                    }
                }
                if (!informative || !sourceBright) {
                    continue;
                }

                accumulateLeastSquares(xtx, xty, sample, targetValue);
                if ((fitPixelCount & 1) == 0) {
                    accumulateLeastSquares(xtxA, xtyA, sample, targetValue);
                    splitPoolAPixels++;
                } else {
                    accumulateLeastSquares(xtxB, xtyB, sample, targetValue);
                    splitPoolBPixels++;
                }
                fitPixelCount++;
            }
        }

        double[] coefficients = fitPixelCount < Math.max(3, settings.getMinBleedFitPixels())
                ? new double[sourceCount]
                : solveAndSanitize(xtx, xty);
        double[] splitA = splitPoolAPixels < Math.max(2, settings.getMinBleedFitPixels() / 2)
                ? new double[sourceCount]
                : solveAndSanitize(xtxA, xtyA);
        double[] splitB = splitPoolBPixels < Math.max(2, settings.getMinBleedFitPixels() / 2)
                ? new double[sourceCount]
                : solveAndSanitize(xtxB, xtyB);

        double maxSplitAbsDelta = 0.0;
        double maxSplitRelativeDelta = 0.0;
        for (int i = 0; i < coefficients.length; i++) {
            double delta = Math.abs(splitA[i] - splitB[i]);
            maxSplitAbsDelta = Math.max(maxSplitAbsDelta, delta);
            double denominator = Math.max(0.05, Math.abs(coefficients[i]));
            maxSplitRelativeDelta = Math.max(maxSplitRelativeDelta, delta / denominator);
        }

        return new BleedFitResult(
                coefficients,
                sourceThresholds,
                targetThreshold,
                fitPixelCount,
                splitPoolAPixels,
                splitPoolBPixels,
                maxSplitAbsDelta,
                maxSplitRelativeDelta);
    }

    private static void accumulateLeastSquares(double[][] matrix,
                                               double[] rhs,
                                               double[] sample,
                                               double targetValue) {
        for (int row = 0; row < sample.length; row++) {
            rhs[row] += sample[row] * targetValue;
            for (int col = row; col < sample.length; col++) {
                matrix[row][col] += sample[row] * sample[col];
                if (row != col) {
                    matrix[col][row] = matrix[row][col];
                }
            }
        }
    }

    private static double[] solveAndSanitize(double[][] matrix, double[] rhs) {
        double[] coefficients = GlobalRatioCorrectionFeature.solveLeastSquares(matrix, rhs);
        for (int i = 0; i < coefficients.length; i++) {
            coefficients[i] = GlobalRatioCorrectionFeature.sanitizeCoefficient(coefficients[i]);
        }
        return coefficients;
    }

    private static SourcePurificationResult purifyBleedSource(ImagePlus source,
                                                              int bleedChannel,
                                                              List<Integer> autofluorescenceChannels,
                                                              byte[][] saturationMasks,
                                                              Settings settings) {
        GlobalFitResult fit = fitAutofluorescenceCoefficients(
                source,
                bleedChannel,
                autofluorescenceChannels,
                settings.getSourceQuietPercentile(),
                saturationMasks);
        int planeCount = CorrectionImageOps.planeCount(source);
        short[][] purifiedPlanes = new short[planeCount][];
        long clampedLowPixels = 0L;
        long clampedHighPixels = 0L;
        for (int plane = 0; plane < planeCount; plane++) {
            short[] sourcePixels = CorrectionImageOps.channelPlanePixels(source, bleedChannel, plane);
            short[][] autoPixels = GlobalRatioCorrectionFeature.loadAutofluorescencePixels(
                    source,
                    autofluorescenceChannels,
                    plane);
            short[] purified = new short[sourcePixels.length];
            for (int pixel = 0; pixel < sourcePixels.length; pixel++) {
                double value = sourcePixels[pixel] & 0xffff;
                for (int channel = 0; channel < autoPixels.length; channel++) {
                    value -= fit.coefficients[channel] * (autoPixels[channel][pixel] & 0xffff);
                }
                if (value < 0.0) {
                    value = 0.0;
                    clampedLowPixels++;
                } else if (value > MAX_16BIT_VALUE) {
                    value = MAX_16BIT_VALUE;
                    clampedHighPixels++;
                }
                purified[pixel] = (short) Math.round(value);
            }
            purifiedPlanes[plane] = purified;
        }
        return new SourcePurificationResult(
                bleedChannel,
                fit,
                purifiedPlanes,
                clampedLowPixels,
                clampedHighPixels);
    }

    private static TargetCorrectionResult correctTargetAutofluorescence(ImagePlus source,
                                                                        int targetChannel,
                                                                        List<Integer> autofluorescenceChannels,
                                                                        byte[][] saturationMasks,
                                                                        Settings settings) {
        GlobalFitResult fit = fitAutofluorescenceCoefficients(
                source,
                targetChannel,
                autofluorescenceChannels,
                settings.getQuietTargetPercentile(),
                saturationMasks);

        int width = source.getWidth();
        int height = source.getHeight();
        int planeCount = CorrectionImageOps.planeCount(source);
        int channelCount = autofluorescenceChannels.size();
        int radius = Math.max(1, settings.getWindowRadius());
        int minLocalFitPixels = Math.max(3, settings.getMinLocalFitPixels());

        short[][] correctedPlanes = new short[planeCount][];
        float[][][] coefficientMapPlanes = settings.isWriteParameterMaps()
                ? new float[channelCount][planeCount][]
                : null;
        double[] coefficientSums = new double[channelCount];
        double[] coefficientMinimums = new double[channelCount];
        double[] coefficientMaximums = new double[channelCount];
        for (int channel = 0; channel < channelCount; channel++) {
            coefficientMinimums[channel] = Double.POSITIVE_INFINITY;
            coefficientMaximums[channel] = Double.NEGATIVE_INFINITY;
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
            byte[] quietMask = buildQuietMask(targetPixels, saturationMasks[plane], fit.quietThreshold);
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
            float[][] planeMaps = coefficientMapPlanes == null ? null : new float[channelCount][];
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
                                minLocalFitPixels,
                                fit.coefficients[0],
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
                                minLocalFitPixels,
                                fit.coefficients,
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
                    } else if (correctedValue > MAX_16BIT_VALUE) {
                        correctedValue = MAX_16BIT_VALUE;
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

        return new TargetCorrectionResult(
                fit,
                correctedPlanes,
                coefficientMapPlanes,
                coefficientSums,
                coefficientMinimums,
                coefficientMaximums,
                totalPixels,
                localFitPixels,
                fallbackPixels,
                clampedLowPixels,
                clampedHighPixels);
    }

    private static GlobalFitResult fitAutofluorescenceCoefficients(ImagePlus source,
                                                                   int dependentChannel,
                                                                   List<Integer> autofluorescenceChannels,
                                                                   double quietPercentile,
                                                                   byte[][] saturationMasks) {
        double quietThreshold = CorrectionImageOps.thresholdForMode(
                CorrectionImageOps.histogramForChannel(source, dependentChannel),
                CorrectionImageOps.ThresholdMode.PERCENTILE,
                quietPercentile,
                0.0);

        int channelCount = autofluorescenceChannels.size();
        double[][] xtx = new double[channelCount][channelCount];
        double[] xty = new double[channelCount];
        double[] sample = new double[channelCount];
        int fitPixelCount = 0;
        int saturatedPixelCount = 0;
        int brightPixelCount = 0;

        int planeCount = CorrectionImageOps.planeCount(source);
        for (int plane = 0; plane < planeCount; plane++) {
            short[] targetPixels = CorrectionImageOps.channelPlanePixels(source, dependentChannel, plane);
            short[][] autoPixels = GlobalRatioCorrectionFeature.loadAutofluorescencePixels(
                    source,
                    autofluorescenceChannels,
                    plane);
            for (int pixel = 0; pixel < targetPixels.length; pixel++) {
                if ((saturationMasks[plane][pixel] & 0xff) != 0) {
                    saturatedPixelCount++;
                    continue;
                }
                double dependentValue = targetPixels[pixel] & 0xffff;
                if (dependentValue > quietThreshold) {
                    brightPixelCount++;
                    continue;
                }

                boolean informative = false;
                for (int channel = 0; channel < channelCount; channel++) {
                    if (autofluorescenceChannels.get(channel).intValue() == dependentChannel) {
                        sample[channel] = 0.0;
                        continue;
                    }
                    sample[channel] = autoPixels[channel][pixel] & 0xffff;
                    if (sample[channel] > 0.0) {
                        informative = true;
                    }
                }
                if (!informative) {
                    continue;
                }

                fitPixelCount++;
                accumulateLeastSquares(xtx, xty, sample, dependentValue);
            }
        }

        double[] coefficients = fitPixelCount <= 0
                ? new double[channelCount]
                : solveAndSanitize(xtx, xty);
        return new GlobalFitResult(
                coefficients,
                quietThreshold,
                fitPixelCount,
                saturatedPixelCount,
                brightPixelCount);
    }

    private static byte[][] buildSaturationMasks(ImagePlus source,
                                                 int targetChannel,
                                                 List<Integer> bleedThroughChannels,
                                                 List<Integer> autofluorescenceChannels) {
        int planeCount = CorrectionImageOps.planeCount(source);
        byte[][] masks = new byte[planeCount][];
        for (int plane = 0; plane < planeCount; plane++) {
            short[] targetPixels = CorrectionImageOps.channelPlanePixels(source, targetChannel, plane);
            List<short[]> others = new ArrayList<short[]>();
            for (Integer channel : bleedThroughChannels) {
                if (channel != null) {
                    others.add(CorrectionImageOps.channelPlanePixels(source, channel.intValue(), plane));
                }
            }
            for (Integer channel : autofluorescenceChannels) {
                if (channel != null) {
                    others.add(CorrectionImageOps.channelPlanePixels(source, channel.intValue(), plane));
                }
            }
            byte[] mask = new byte[targetPixels.length];
            for (int pixel = 0; pixel < targetPixels.length; pixel++) {
                boolean saturated = (targetPixels[pixel] & 0xffff) >= MAX_16BIT_VALUE;
                for (int i = 0; !saturated && i < others.size(); i++) {
                    saturated = (others.get(i)[pixel] & 0xffff) >= MAX_16BIT_VALUE;
                }
                mask[pixel] = saturated ? (byte) 1 : (byte) 0;
            }
            masks[plane] = mask;
        }
        return masks;
    }

    private static byte[] buildQuietMask(short[] targetPixels,
                                         byte[] saturationMask,
                                         double quietTargetThreshold) {
        byte[] quietMask = new byte[targetPixels.length];
        for (int pixel = 0; pixel < targetPixels.length; pixel++) {
            boolean quiet = (saturationMask[pixel] & 0xff) == 0
                    && (targetPixels[pixel] & 0xffff) <= quietTargetThreshold;
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

    private static double percentileForShortPlanes(short[][] planes, double percentile) {
        int[] histogram = new int[65536];
        long total = 0L;
        for (int plane = 0; plane < planes.length; plane++) {
            short[] pixels = planes[plane];
            if (pixels == null) {
                continue;
            }
            for (int i = 0; i < pixels.length; i++) {
                histogram[pixels[i] & 0xffff]++;
                total++;
            }
        }
        if (total <= 0L) {
            return 0.0;
        }
        double clampedPercentile = percentile;
        if (Double.isNaN(clampedPercentile) || Double.isInfinite(clampedPercentile)) {
            clampedPercentile = 50.0;
        }
        if (clampedPercentile < 0.0) {
            clampedPercentile = 0.0;
        }
        if (clampedPercentile > 100.0) {
            clampedPercentile = 100.0;
        }
        long rank = (long) Math.ceil((clampedPercentile / 100.0) * total) - 1L;
        if (rank < 0L) {
            rank = 0L;
        }
        long cumulative = 0L;
        for (int intensity = 0; intensity < histogram.length; intensity++) {
            cumulative += histogram[intensity];
            if (cumulative > rank) {
                return intensity;
            }
        }
        return 65535.0;
    }

    private static double clamp16Bit(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        if (value < 0.0) {
            return 0.0;
        }
        if (value > MAX_16BIT_VALUE) {
            return MAX_16BIT_VALUE;
        }
        return value;
    }

    private static final class GlobalFitResult {
        final double[] coefficients;
        final double quietThreshold;
        final int fitPixelCount;
        final int saturatedPixelCount;
        final int brightPixelCount;

        private GlobalFitResult(double[] coefficients,
                                double quietThreshold,
                                int fitPixelCount,
                                int saturatedPixelCount,
                                int brightPixelCount) {
            this.coefficients = coefficients;
            this.quietThreshold = quietThreshold;
            this.fitPixelCount = fitPixelCount;
            this.saturatedPixelCount = saturatedPixelCount;
            this.brightPixelCount = brightPixelCount;
        }
    }

    private static final class TargetCorrectionResult {
        final GlobalFitResult fit;
        final short[][] correctedPlanes;
        final float[][][] coefficientMapPlanes;
        final double[] coefficientSums;
        final double[] coefficientMinimums;
        final double[] coefficientMaximums;
        final long totalPixels;
        final long localFitPixels;
        final long fallbackPixels;
        final long clampedLowPixels;
        final long clampedHighPixels;

        private TargetCorrectionResult(GlobalFitResult fit,
                                       short[][] correctedPlanes,
                                       float[][][] coefficientMapPlanes,
                                       double[] coefficientSums,
                                       double[] coefficientMinimums,
                                       double[] coefficientMaximums,
                                       long totalPixels,
                                       long localFitPixels,
                                       long fallbackPixels,
                                       long clampedLowPixels,
                                       long clampedHighPixels) {
            this.fit = fit;
            this.correctedPlanes = correctedPlanes;
            this.coefficientMapPlanes = coefficientMapPlanes;
            this.coefficientSums = coefficientSums;
            this.coefficientMinimums = coefficientMinimums;
            this.coefficientMaximums = coefficientMaximums;
            this.totalPixels = totalPixels;
            this.localFitPixels = localFitPixels;
            this.fallbackPixels = fallbackPixels;
            this.clampedLowPixels = clampedLowPixels;
            this.clampedHighPixels = clampedHighPixels;
        }
    }

    private static final class SourcePurificationResult {
        final int sourceChannel;
        final GlobalFitResult fit;
        final short[][] purifiedPlanes;
        final long clampedLowPixels;
        final long clampedHighPixels;

        private SourcePurificationResult(int sourceChannel,
                                         GlobalFitResult fit,
                                         short[][] purifiedPlanes,
                                         long clampedLowPixels,
                                         long clampedHighPixels) {
            this.sourceChannel = sourceChannel;
            this.fit = fit;
            this.purifiedPlanes = purifiedPlanes;
            this.clampedLowPixels = clampedLowPixels;
            this.clampedHighPixels = clampedHighPixels;
        }
    }

    private static final class BleedFitResult {
        final double[] coefficients;
        final double[] sourceThresholds;
        final double targetThreshold;
        final int fitPixelCount;
        final int splitPoolAPixels;
        final int splitPoolBPixels;
        final double maxSplitAbsDelta;
        final double maxSplitRelativeDelta;

        private BleedFitResult(double[] coefficients,
                               double[] sourceThresholds,
                               double targetThreshold,
                               int fitPixelCount,
                               int splitPoolAPixels,
                               int splitPoolBPixels,
                               double maxSplitAbsDelta,
                               double maxSplitRelativeDelta) {
            this.coefficients = coefficients;
            this.sourceThresholds = sourceThresholds;
            this.targetThreshold = targetThreshold;
            this.fitPixelCount = fitPixelCount;
            this.splitPoolAPixels = splitPoolAPixels;
            this.splitPoolBPixels = splitPoolBPixels;
            this.maxSplitAbsDelta = maxSplitAbsDelta;
            this.maxSplitRelativeDelta = maxSplitRelativeDelta;
        }
    }

    public static final class Settings {
        private int windowRadius = 2;
        private double quietTargetPercentile = 85.0;
        private double sourceQuietPercentile = 85.0;
        private double sourceBrightPercentile = 90.0;
        private int minLocalFitPixels = 9;
        private int minBleedFitPixels = 12;
        private boolean writeParameterMaps = false;

        public Settings copy() {
            return new Settings()
                    .setWindowRadius(windowRadius)
                    .setQuietTargetPercentile(quietTargetPercentile)
                    .setSourceQuietPercentile(sourceQuietPercentile)
                    .setSourceBrightPercentile(sourceBrightPercentile)
                    .setMinLocalFitPixels(minLocalFitPixels)
                    .setMinBleedFitPixels(minBleedFitPixels)
                    .setWriteParameterMaps(writeParameterMaps);
        }

        public int getWindowRadius() {
            return windowRadius;
        }

        public Settings setWindowRadius(int windowRadius) {
            this.windowRadius = Math.max(1, windowRadius);
            return this;
        }

        public double getQuietTargetPercentile() {
            return quietTargetPercentile;
        }

        public Settings setQuietTargetPercentile(double quietTargetPercentile) {
            this.quietTargetPercentile = quietTargetPercentile;
            return this;
        }

        public double getSourceQuietPercentile() {
            return sourceQuietPercentile;
        }

        public Settings setSourceQuietPercentile(double sourceQuietPercentile) {
            this.sourceQuietPercentile = sourceQuietPercentile;
            return this;
        }

        public double getSourceBrightPercentile() {
            return sourceBrightPercentile;
        }

        public Settings setSourceBrightPercentile(double sourceBrightPercentile) {
            this.sourceBrightPercentile = sourceBrightPercentile;
            return this;
        }

        public int getMinLocalFitPixels() {
            return minLocalFitPixels;
        }

        public Settings setMinLocalFitPixels(int minLocalFitPixels) {
            this.minLocalFitPixels = Math.max(3, minLocalFitPixels);
            return this;
        }

        public int getMinBleedFitPixels() {
            return minBleedFitPixels;
        }

        public Settings setMinBleedFitPixels(int minBleedFitPixels) {
            this.minBleedFitPixels = Math.max(3, minBleedFitPixels);
            return this;
        }

        public boolean isWriteParameterMaps() {
            return writeParameterMaps;
        }

        public Settings setWriteParameterMaps(boolean writeParameterMaps) {
            this.writeParameterMaps = writeParameterMaps;
            return this;
        }

        public void normalize() {
            setWindowRadius(windowRadius);
            quietTargetPercentile = clampPercentile(quietTargetPercentile);
            sourceQuietPercentile = clampPercentile(sourceQuietPercentile);
            sourceBrightPercentile = clampPercentile(sourceBrightPercentile);
            setMinLocalFitPixels(minLocalFitPixels);
            setMinBleedFitPixels(minBleedFitPixels);
        }

        public CorrectionPipeline.Settings toPipelineSettings() {
            normalize();
            return new CorrectionPipeline.Settings()
                    .putInt(WINDOW_RADIUS, windowRadius)
                    .putDouble(QUIET_TARGET_PERCENTILE, quietTargetPercentile)
                    .putDouble(SOURCE_QUIET_PERCENTILE, sourceQuietPercentile)
                    .putDouble(SOURCE_BRIGHT_PERCENTILE, sourceBrightPercentile)
                    .putInt(MIN_LOCAL_FIT_PIXELS, minLocalFitPixels)
                    .putInt(MIN_BLEED_FIT_PIXELS, minBleedFitPixels)
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
            settings.setSourceQuietPercentile(
                    raw.getDouble(SOURCE_QUIET_PERCENTILE, settings.sourceQuietPercentile));
            settings.setSourceBrightPercentile(
                    raw.getDouble(SOURCE_BRIGHT_PERCENTILE, settings.sourceBrightPercentile));
            settings.setMinLocalFitPixels(raw.getInt(MIN_LOCAL_FIT_PIXELS, settings.minLocalFitPixels));
            settings.setMinBleedFitPixels(raw.getInt(MIN_BLEED_FIT_PIXELS, settings.minBleedFitPixels));
            settings.setWriteParameterMaps(Boolean.parseBoolean(
                    raw.get(WRITE_PARAMETER_MAPS, Boolean.toString(settings.writeParameterMaps))));
            settings.normalize();
            return settings;
        }

        private static double clampPercentile(double value) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return 85.0;
            }
            if (value < 0.0) {
                return 0.0;
            }
            if (value > 100.0) {
                return 100.0;
            }
            return value;
        }
    }
}
