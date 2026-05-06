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
 * Builds a veto mask where corrected target and autofluorescence remain positively correlated nearby.
 */
public class LocalCorrelationVetoFeature implements CorrectionPipeline.ExecutableFeature {

    public static final String ID = "local_correlation_veto";

    private static final String WINDOW_RADIUS = "window_radius";
    private static final String CORRELATION_THRESHOLD = "correlation_threshold";
    private static final String MIN_WINDOW_PIXELS = "min_window_pixels";
    private static final String WRITE_PARAMETER_MAP = "write_parameter_map";

    private final Set<RequiredChannel> requiredChannels =
            Collections.singleton(RequiredChannel.AUTOFLUORESCENCE);

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Local correlation veto";
    }

    @Override
    public String getDescription() {
        return "Builds a veto mask where corrected target intensity still rises and falls with autofluorescence.";
    }

    @Override
    public InputType getRequiredInputType() {
        return InputType.CORRECTED_IMAGE;
    }

    @Override
    public OutputType getOutputType() {
        return OutputType.VETO_MASK;
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
        ImagePlus corrected = state.getCorrectedImage();
        CorrectionImageOps.require16Bit(source, "Source");
        CorrectionImageOps.requireSingleChannel16Bit(corrected, "Corrected");

        List<Integer> autofluorescenceChannels = config.getAutofluorescenceChannelIndexes();
        if (autofluorescenceChannels == null || autofluorescenceChannels.isEmpty()) {
            throw new IllegalArgumentException("Local correlation veto requires at least one autofluorescence channel.");
        }

        Settings settings = Settings.from(state.getFeatureSettings(ID));
        int radius = Math.max(1, settings.getWindowRadius());
        int minWindowPixels = Math.max(3, settings.getMinWindowPixels());
        double threshold = settings.getCorrelationThreshold();

        int width = corrected.getWidth();
        int height = corrected.getHeight();
        int planeCount = CorrectionImageOps.planeCount(corrected);
        byte[][] vetoPlanes = new byte[planeCount][];
        float[][] correlationMapPlanes = settings.isWriteParameterMap()
                ? new float[planeCount][]
                : null;

        long totalPixels = 0L;
        long vetoPixels = 0L;
        double sumMaxCorrelation = 0.0;
        double peakCorrelation = 0.0;

        for (int plane = 0; plane < planeCount; plane++) {
            short[] correctedPixels = CorrectionImageOps.singleChannelPlanePixels(corrected, plane);
            short[] targetPixels = CorrectionImageOps.channelPlanePixels(
                    source,
                    config.getTargetChannelIndex(),
                    plane);
            short[][] autoPixels = GlobalRatioCorrectionFeature.loadAutofluorescencePixels(
                    source,
                    autofluorescenceChannels,
                    plane);

            float[] maxCorrelation = new float[correctedPixels.length];
            for (int channel = 0; channel < autoPixels.length; channel++) {
                byte[] validMask = buildValidMask(targetPixels, autoPixels[channel]);
                double[] countIntegral = buildCountIntegral(validMask, width, height);
                double[] xIntegral = buildValueIntegral(validMask, correctedPixels, width, height);
                double[] yIntegral = buildValueIntegral(validMask, autoPixels[channel], width, height);
                double[] xxIntegral = buildProductIntegral(validMask, correctedPixels, correctedPixels, width, height);
                double[] yyIntegral = buildProductIntegral(validMask, autoPixels[channel], autoPixels[channel], width, height);
                double[] xyIntegral = buildProductIntegral(validMask, correctedPixels, autoPixels[channel], width, height);

                for (int y = 0; y < height; y++) {
                    int yStart = Math.max(0, y - radius);
                    int yEnd = Math.min(height - 1, y + radius);
                    for (int x = 0; x < width; x++) {
                        int xStart = Math.max(0, x - radius);
                        int xEnd = Math.min(width - 1, x + radius);
                        int pixel = y * width + x;

                        double count = rectangleSum(countIntegral, width, xStart, yStart, xEnd, yEnd);
                        if (count < minWindowPixels) {
                            continue;
                        }
                        double sumX = rectangleSum(xIntegral, width, xStart, yStart, xEnd, yEnd);
                        double sumY = rectangleSum(yIntegral, width, xStart, yStart, xEnd, yEnd);
                        double sumXX = rectangleSum(xxIntegral, width, xStart, yStart, xEnd, yEnd);
                        double sumYY = rectangleSum(yyIntegral, width, xStart, yStart, xEnd, yEnd);
                        double sumXY = rectangleSum(xyIntegral, width, xStart, yStart, xEnd, yEnd);

                        double numerator = count * sumXY - sumX * sumY;
                        double xVariance = count * sumXX - sumX * sumX;
                        double yVariance = count * sumYY - sumY * sumY;
                        if (xVariance <= 1e-12 || yVariance <= 1e-12) {
                            continue;
                        }
                        double correlation = numerator / Math.sqrt(xVariance * yVariance);
                        if (Double.isNaN(correlation) || Double.isInfinite(correlation) || correlation <= 0.0) {
                            continue;
                        }
                        if (correlation > 1.0) {
                            correlation = 1.0;
                        }
                        if (correlation > maxCorrelation[pixel]) {
                            maxCorrelation[pixel] = (float) correlation;
                        }
                    }
                }
            }

            byte[] veto = new byte[correctedPixels.length];
            for (int pixel = 0; pixel < maxCorrelation.length; pixel++) {
                totalPixels++;
                double correlation = maxCorrelation[pixel];
                sumMaxCorrelation += correlation;
                if (correlation > peakCorrelation) {
                    peakCorrelation = correlation;
                }
                if (correlation >= threshold) {
                    veto[pixel] = (byte) 255;
                    vetoPixels++;
                }
            }
            vetoPlanes[plane] = veto;
            if (correlationMapPlanes != null) {
                correlationMapPlanes[plane] = maxCorrelation;
            }
        }

        ImagePlus vetoMask = CorrectionImageOps.createMaskImageLike(
                corrected,
                "local_correlation_veto_mask",
                vetoPlanes);
        ImagePlus existingVeto = state.getVetoMaskImage();
        if (existingVeto != null) {
            vetoMask = mergeVetoMasks(existingVeto, vetoMask);
        }
        state.setVetoMaskImage(vetoMask);

        if (correlationMapPlanes != null) {
            state.putParameterMap(
                    "local_correlation_max_map",
                    CorrectionImageOps.createFloatImageLike(
                            corrected,
                            "local_correlation_max_map",
                            correlationMapPlanes));
        }

        state.addSummary(new CorrectionPipeline.FeatureSummary(ID, getDisplayName())
                .putInt(WINDOW_RADIUS, radius)
                .putDouble(CORRELATION_THRESHOLD, threshold)
                .putInt(MIN_WINDOW_PIXELS, minWindowPixels)
                .put("parameter_maps_written", Boolean.toString(settings.isWriteParameterMap()))
                .putInt("veto_pixels", vetoPixels)
                .putInt("total_pixels", totalPixels)
                .putDouble("veto_fraction", totalPixels <= 0L ? 0.0 : (double) vetoPixels / (double) totalPixels)
                .putDouble("mean_max_correlation", totalPixels <= 0L ? 0.0 : sumMaxCorrelation / (double) totalPixels)
                .putDouble("peak_correlation", peakCorrelation));
    }

    private static ImagePlus mergeVetoMasks(ImagePlus existingVeto, ImagePlus newVeto) {
        CorrectionImageOps.requireSingleChannelMask(existingVeto, "Existing veto");
        CorrectionImageOps.requireSingleChannelMask(newVeto, "New veto");
        if (existingVeto.getWidth() != newVeto.getWidth()
                || existingVeto.getHeight() != newVeto.getHeight()
                || CorrectionImageOps.planeCount(existingVeto) != CorrectionImageOps.planeCount(newVeto)) {
            throw new IllegalArgumentException("Existing veto mask dimensions do not match the new veto mask.");
        }

        int planeCount = CorrectionImageOps.planeCount(newVeto);
        byte[][] mergedPlanes = new byte[planeCount][];
        for (int plane = 0; plane < planeCount; plane++) {
            byte[] existingPixels = CorrectionImageOps.singleChannelMaskPlanePixels(existingVeto, plane);
            byte[] newPixels = CorrectionImageOps.singleChannelMaskPlanePixels(newVeto, plane);
            byte[] merged = new byte[newPixels.length];
            for (int pixel = 0; pixel < newPixels.length; pixel++) {
                if ((existingPixels[pixel] & 0xff) != 0 || (newPixels[pixel] & 0xff) != 0) {
                    merged[pixel] = (byte) 255;
                }
            }
            mergedPlanes[plane] = merged;
        }
        return CorrectionImageOps.createMaskImageLike(newVeto, "merged_veto_mask", mergedPlanes);
    }

    private static byte[] buildValidMask(short[] targetPixels, short[] autoPixels) {
        byte[] validMask = new byte[targetPixels.length];
        for (int pixel = 0; pixel < targetPixels.length; pixel++) {
            boolean valid = (targetPixels[pixel] & 0xffff) < 65535
                    && (autoPixels[pixel] & 0xffff) < 65535;
            validMask[pixel] = valid ? (byte) 1 : (byte) 0;
        }
        return validMask;
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

    private static double[] buildValueIntegral(byte[] mask, short[] values, int width, int height) {
        double[] integral = new double[(width + 1) * (height + 1)];
        int stride = width + 1;
        for (int y = 0; y < height; y++) {
            double rowSum = 0.0;
            int rowOffset = y * width;
            int integralRow = (y + 1) * stride;
            int integralAbove = y * stride;
            for (int x = 0; x < width; x++) {
                int pixel = rowOffset + x;
                rowSum += (mask[pixel] & 0xff) == 0 ? 0.0 : (values[pixel] & 0xffff);
                integral[integralRow + x + 1] = integral[integralAbove + x + 1] + rowSum;
            }
        }
        return integral;
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

    public static final class Settings {
        private int windowRadius = 2;
        private double correlationThreshold = 0.6;
        private int minWindowPixels = 9;
        private boolean writeParameterMap = false;

        public int getWindowRadius() {
            return windowRadius;
        }

        public Settings setWindowRadius(int windowRadius) {
            this.windowRadius = windowRadius;
            return this;
        }

        public double getCorrelationThreshold() {
            return correlationThreshold;
        }

        public Settings setCorrelationThreshold(double correlationThreshold) {
            this.correlationThreshold = correlationThreshold;
            return this;
        }

        public int getMinWindowPixels() {
            return minWindowPixels;
        }

        public Settings setMinWindowPixels(int minWindowPixels) {
            this.minWindowPixels = minWindowPixels;
            return this;
        }

        public boolean isWriteParameterMap() {
            return writeParameterMap;
        }

        public Settings setWriteParameterMap(boolean writeParameterMap) {
            this.writeParameterMap = writeParameterMap;
            return this;
        }

        public CorrectionPipeline.Settings toPipelineSettings() {
            return new CorrectionPipeline.Settings()
                    .putInt(WINDOW_RADIUS, windowRadius)
                    .putDouble(CORRELATION_THRESHOLD, correlationThreshold)
                    .putInt(MIN_WINDOW_PIXELS, minWindowPixels)
                    .put(WRITE_PARAMETER_MAP, Boolean.toString(writeParameterMap));
        }

        public static Settings from(CorrectionPipeline.Settings raw) {
            Settings settings = new Settings();
            if (raw == null) {
                return settings;
            }
            settings.setWindowRadius(raw.getInt(WINDOW_RADIUS, settings.windowRadius));
            settings.setCorrelationThreshold(
                    raw.getDouble(CORRELATION_THRESHOLD, settings.correlationThreshold));
            settings.setMinWindowPixels(raw.getInt(MIN_WINDOW_PIXELS, settings.minWindowPixels));
            settings.setWriteParameterMap(Boolean.parseBoolean(
                    raw.get(WRITE_PARAMETER_MAP, Boolean.toString(settings.writeParameterMap))));
            return settings;
        }
    }
}
