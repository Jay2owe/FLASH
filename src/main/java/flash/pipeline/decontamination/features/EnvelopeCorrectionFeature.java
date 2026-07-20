package flash.pipeline.decontamination.features;

import flash.pipeline.decontamination.CorrectionFeature;
import flash.pipeline.decontamination.CorrectionImageOps;
import flash.pipeline.decontamination.CorrectionPipeline;
import flash.pipeline.decontamination.SpectralDecontaminationConfig;
import ij.ImagePlus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Expert-only non-linear cleanup for bright contaminant-driven residual tails.
 */
public class EnvelopeCorrectionFeature implements CorrectionPipeline.ExecutableFeature {

    public static final String ID = "envelope_correction";

    private static final String DOMINANT_CONTAMINANT_PERCENTILE = "dominant_contaminant_percentile";
    private static final String ENVELOPE_PERCENTILE = "envelope_percentile";
    private static final String BIN_COUNT = "bin_count";
    private static final String MIN_BIN_PIXELS = "min_bin_pixels";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Envelope correction";
    }

    @Override
    public String getDescription() {
        return "Fits an upper contaminant envelope to remove residual bright false-signal tails.";
    }

    @Override
    public InputType getRequiredInputType() {
        return InputType.CORRECTED_IMAGE;
    }

    @Override
    public OutputType getOutputType() {
        return OutputType.CORRECTED_IMAGE;
    }

    @Override
    public Set<RequiredChannel> getRequiredChannels() {
        return Collections.unmodifiableSet(EnumSet.of(RequiredChannel.CONTAMINANT));
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
        ImagePlus corrected = state.getCorrectedImage();
        CorrectionImageOps.require16Bit(source, "Source");
        CorrectionImageOps.requireSingleChannel16Bit(corrected, "Corrected");

        List<Integer> contaminantChannels = CorrectionImageOps.contaminantChannels(config);
        if (contaminantChannels.isEmpty()) {
            throw new IllegalArgumentException("Envelope correction requires at least one contaminant channel.");
        }

        Settings settings = Settings.from(state.getFeatureSettings(ID));
        int planeCount = CorrectionImageOps.planeCount(corrected);
        short[][] correctedPlanes = new short[planeCount][];
        double[][] contaminantScores = new double[planeCount][];
        double[] allScores = new double[planeCount * corrected.getWidth() * corrected.getHeight()];
        int scoreIndex = 0;
        double maxScore = 0.0;

        for (int plane = 0; plane < planeCount; plane++) {
            short[] correctedPixels = CorrectionImageOps.singleChannelPlanePixels(corrected, plane);
            short[] copied = Arrays.copyOf(correctedPixels, correctedPixels.length);
            correctedPlanes[plane] = copied;

            double[] scores = new double[correctedPixels.length];
            contaminantScores[plane] = scores;
            for (int channel = 0; channel < contaminantChannels.size(); channel++) {
                short[] contaminantPixels = CorrectionImageOps.channelPlanePixels(
                        source,
                        contaminantChannels.get(channel).intValue(),
                        plane);
                for (int pixel = 0; pixel < scores.length; pixel++) {
                    scores[pixel] += contaminantPixels[pixel] & 0xffff;
                }
            }
            for (int pixel = 0; pixel < scores.length; pixel++) {
                allScores[scoreIndex++] = scores[pixel];
                if (scores[pixel] > maxScore) {
                    maxScore = scores[pixel];
                }
            }
        }

        double dominantThreshold = percentile(allScores, scoreIndex, settings.getDominantContaminantPercentile());
        if (maxScore <= 0.0) {
            state.addSummary(new CorrectionPipeline.FeatureSummary(ID, getDisplayName())
                    .putDouble(DOMINANT_CONTAMINANT_PERCENTILE, settings.getDominantContaminantPercentile())
                    .putDouble("dominant_threshold", dominantThreshold)
                    .putInt("fit_pixel_count", 0)
                    .putInt("warning_count", 1)
                    .put("warning_1", "Envelope correction was skipped because contaminant intensities were all zero."));
            return;
        }

        int binCount = Math.max(4, settings.getBinCount());
        int[] counts = new int[binCount];
        int fitPixelCount = 0;
        int binsWithData = 0;
        for (int plane = 0; plane < planeCount; plane++) {
            short[] correctedPixels = correctedPlanes[plane];
            double[] scores = contaminantScores[plane];
            for (int pixel = 0; pixel < scores.length; pixel++) {
                if (scores[pixel] < dominantThreshold || scores[pixel] <= 0.0) {
                    continue;
                }
                counts[binForScore(scores[pixel], maxScore, binCount)]++;
                fitPixelCount++;
            }
        }

        double[][] perBinCorrectedValues = new double[binCount][];
        for (int bin = 0; bin < binCount; bin++) {
            perBinCorrectedValues[bin] = new double[counts[bin]];
            if (counts[bin] > 0) {
                binsWithData++;
            }
        }

        int[] fills = new int[binCount];
        for (int plane = 0; plane < planeCount; plane++) {
            short[] correctedPixels = correctedPlanes[plane];
            double[] scores = contaminantScores[plane];
            for (int pixel = 0; pixel < scores.length; pixel++) {
                if (scores[pixel] < dominantThreshold || scores[pixel] <= 0.0) {
                    continue;
                }
                int bin = binForScore(scores[pixel], maxScore, binCount);
                perBinCorrectedValues[bin][fills[bin]++] = correctedPixels[pixel] & 0xffff;
            }
        }

        double[] envelope = new double[binCount];
        List<String> warnings = new ArrayList<String>();
        for (int bin = 0; bin < binCount; bin++) {
            if (perBinCorrectedValues[bin].length < settings.getMinBinPixels()) {
                envelope[bin] = bin == 0 ? 0.0 : envelope[bin - 1];
                continue;
            }
            Arrays.sort(perBinCorrectedValues[bin]);
            envelope[bin] = percentile(
                    perBinCorrectedValues[bin],
                    perBinCorrectedValues[bin].length,
                    settings.getEnvelopePercentile());
        }
        enforceMonotonicEnvelope(envelope);

        if (fitPixelCount < settings.getMinBinPixels() * 2) {
            warnings.add("Envelope correction fit pool is small (" + fitPixelCount + " pixels).");
        }
        if (binsWithData < 2) {
            warnings.add("Envelope correction found too few contaminant bins with data.");
        }

        short[][] adjustedPlanes = new short[planeCount][];
        long clampedLowPixels = 0L;
        double maxEnvelopeValue = 0.0;
        for (int bin = 0; bin < envelope.length; bin++) {
            if (envelope[bin] > maxEnvelopeValue) {
                maxEnvelopeValue = envelope[bin];
            }
        }
        for (int plane = 0; plane < planeCount; plane++) {
            short[] correctedPixels = correctedPlanes[plane];
            double[] scores = contaminantScores[plane];
            short[] adjusted = new short[correctedPixels.length];
            for (int pixel = 0; pixel < correctedPixels.length; pixel++) {
                int bin = binForScore(scores[pixel], maxScore, binCount);
                double predicted = interpolateEnvelope(scores[pixel], maxScore, envelope, binCount, bin);
                double value = (correctedPixels[pixel] & 0xffff) - predicted;
                if (value < 0.0) {
                    value = 0.0;
                    clampedLowPixels++;
                } else if (value > 65535.0) {
                    value = 65535.0;
                }
                adjusted[pixel] = (short) Math.round(value);
            }
            adjustedPlanes[plane] = adjusted;
        }

        state.setCorrectedImage(CorrectionImageOps.createShortImageLike(
                corrected,
                "envelope_corrected_target",
                adjustedPlanes));

        CorrectionPipeline.FeatureSummary summary =
                new CorrectionPipeline.FeatureSummary(ID, getDisplayName())
                        .putDouble(DOMINANT_CONTAMINANT_PERCENTILE, settings.getDominantContaminantPercentile())
                        .putDouble("dominant_threshold", dominantThreshold)
                        .putDouble(ENVELOPE_PERCENTILE, settings.getEnvelopePercentile())
                        .putInt(BIN_COUNT, binCount)
                        .putInt(MIN_BIN_PIXELS, settings.getMinBinPixels())
                        .putInt("fit_pixel_count", fitPixelCount)
                        .putInt("bins_with_data", binsWithData)
                        .putDouble("max_envelope_value", maxEnvelopeValue)
                        .putInt("clamped_low_pixels", clampedLowPixels)
                        .putInt("warning_count", warnings.size());
        for (int bin = 0; bin < envelope.length; bin++) {
            summary.putDouble("envelope_bin_" + (bin + 1), envelope[bin]);
        }
        for (int i = 0; i < warnings.size(); i++) {
            summary.put("warning_" + (i + 1), warnings.get(i));
        }
        state.addSummary(summary);
    }

    private static void enforceMonotonicEnvelope(double[] envelope) {
        double last = 0.0;
        for (int i = 0; i < envelope.length; i++) {
            if (envelope[i] < last) {
                envelope[i] = last;
            }
            last = envelope[i];
        }
    }

    private static int binForScore(double score, double maxScore, int binCount) {
        if (maxScore <= 0.0) {
            return 0;
        }
        int bin = (int) Math.floor((score / maxScore) * (double) binCount);
        if (bin < 0) {
            return 0;
        }
        if (bin >= binCount) {
            return binCount - 1;
        }
        return bin;
    }

    private static double interpolateEnvelope(double score,
                                              double maxScore,
                                              double[] envelope,
                                              int binCount,
                                              int fallbackBin) {
        if (envelope.length == 0 || maxScore <= 0.0) {
            return 0.0;
        }
        if (score <= 0.0) {
            return 0.0;
        }
        double scaled = (score / maxScore) * (double) (binCount - 1);
        int lower = (int) Math.floor(scaled);
        int upper = (int) Math.ceil(scaled);
        if (lower < 0 || lower >= envelope.length) {
            lower = fallbackBin;
        }
        if (upper < 0 || upper >= envelope.length) {
            upper = fallbackBin;
        }
        if (lower == upper) {
            return envelope[lower];
        }
        double fraction = scaled - lower;
        return envelope[lower] + fraction * (envelope[upper] - envelope[lower]);
    }

    private static double percentile(double[] values, int length, double percentile) {
        if (values == null || length <= 0) {
            return 0.0;
        }
        double[] copy = Arrays.copyOf(values, length);
        Arrays.sort(copy);
        int index = (int) Math.ceil((clampPercentile(percentile) / 100.0) * length) - 1;
        if (index < 0) {
            index = 0;
        }
        if (index >= copy.length) {
            index = copy.length - 1;
        }
        return copy[index];
    }

    private static double clampPercentile(double percentile) {
        if (Double.isNaN(percentile) || Double.isInfinite(percentile)) {
            return 95.0;
        }
        if (percentile < 0.0) {
            return 0.0;
        }
        if (percentile > 100.0) {
            return 100.0;
        }
        return percentile;
    }

    public static final class Settings {
        private double dominantContaminantPercentile = 90.0;
        private double envelopePercentile = 98.0;
        private int binCount = 16;
        private int minBinPixels = 8;

        public Settings copy() {
            return new Settings()
                    .setDominantContaminantPercentile(dominantContaminantPercentile)
                    .setEnvelopePercentile(envelopePercentile)
                    .setBinCount(binCount)
                    .setMinBinPixels(minBinPixels);
        }

        public double getDominantContaminantPercentile() {
            return dominantContaminantPercentile;
        }

        public Settings setDominantContaminantPercentile(double dominantContaminantPercentile) {
            this.dominantContaminantPercentile = dominantContaminantPercentile;
            return this;
        }

        public double getEnvelopePercentile() {
            return envelopePercentile;
        }

        public Settings setEnvelopePercentile(double envelopePercentile) {
            this.envelopePercentile = envelopePercentile;
            return this;
        }

        public int getBinCount() {
            return binCount;
        }

        public Settings setBinCount(int binCount) {
            this.binCount = Math.max(4, binCount);
            return this;
        }

        public int getMinBinPixels() {
            return minBinPixels;
        }

        public Settings setMinBinPixels(int minBinPixels) {
            this.minBinPixels = Math.max(2, minBinPixels);
            return this;
        }

        public void normalize() {
            dominantContaminantPercentile = clampPercentile(dominantContaminantPercentile);
            envelopePercentile = clampPercentile(envelopePercentile);
            setBinCount(binCount);
            setMinBinPixels(minBinPixels);
        }

        public CorrectionPipeline.Settings toPipelineSettings() {
            normalize();
            return new CorrectionPipeline.Settings()
                    .putDouble(DOMINANT_CONTAMINANT_PERCENTILE, dominantContaminantPercentile)
                    .putDouble(ENVELOPE_PERCENTILE, envelopePercentile)
                    .putInt(BIN_COUNT, binCount)
                    .putInt(MIN_BIN_PIXELS, minBinPixels);
        }

        public static Settings from(CorrectionPipeline.Settings raw) {
            Settings settings = new Settings();
            if (raw == null) {
                return settings;
            }
            settings.setDominantContaminantPercentile(
                    raw.getDouble(DOMINANT_CONTAMINANT_PERCENTILE, settings.dominantContaminantPercentile));
            settings.setEnvelopePercentile(
                    raw.getDouble(ENVELOPE_PERCENTILE, settings.envelopePercentile));
            settings.setBinCount(raw.getInt(BIN_COUNT, settings.binCount));
            settings.setMinBinPixels(raw.getInt(MIN_BIN_PIXELS, settings.minBinPixels));
            settings.normalize();
            return settings;
        }
    }
}
