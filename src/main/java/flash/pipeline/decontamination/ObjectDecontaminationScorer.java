package flash.pipeline.decontamination;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Measures spectral contamination evidence for existing object label maps.
 */
public final class ObjectDecontaminationScorer {

    private ObjectDecontaminationScorer() {
    }

    public static ScoreResult score(ImagePlus labelMap,
                                    ImagePlus sourceImage,
                                    SpectralDecontaminationConfig config,
                                    ImagePlus correctedTargetImage,
                                    Settings settings) {
        if (labelMap == null) {
            throw new IllegalArgumentException("Object label image is required.");
        }
        CorrectionImageOps.require16Bit(sourceImage, "Source");
        if (config == null) {
            throw new IllegalArgumentException("Spectral Decontamination config is required.");
        }
        validateLabelMap(labelMap, sourceImage);
        if (correctedTargetImage != null) {
            CorrectionImageOps.requireSingleChannel16Bit(correctedTargetImage, "Corrected target");
            validateSingleChannelImage(correctedTargetImage, sourceImage, "Corrected target");
        }

        Settings resolved = settings == null ? new Settings() : settings.copy();
        List<Integer> bleedChannels = validChannels(
                config.getBleedThroughChannelIndexes(),
                config.getTargetChannelIndex(),
                Math.max(1, sourceImage.getNChannels()));
        List<Integer> autofluorescenceChannels = validChannels(
                config.getAutofluorescenceChannelIndexes(),
                config.getTargetChannelIndex(),
                Math.max(1, sourceImage.getNChannels()));
        List<Integer> contaminantChannels = new ArrayList<Integer>();
        contaminantChannels.addAll(bleedChannels);
        for (Integer channel : autofluorescenceChannels) {
            if (!contaminantChannels.contains(channel)) {
                contaminantChannels.add(channel);
            }
        }

        double[] bleedThresholds = highThresholds(sourceImage, bleedChannels, resolved);
        double[] autofluorescenceThresholds =
                highThresholds(sourceImage, autofluorescenceChannels, resolved);

        LinkedHashMap<Integer, Accumulator> accumulators =
                new LinkedHashMap<Integer, Accumulator>();
        int planeCount = CorrectionImageOps.planeCount(sourceImage);
        for (int plane = 0; plane < planeCount; plane++) {
            ImageProcessor labels = labelProcessor(labelMap, plane);
            short[] targetPixels = CorrectionImageOps.channelPlanePixels(
                    sourceImage,
                    config.getTargetChannelIndex(),
                    plane);
            short[][] bleedPixels = channelPixels(sourceImage, bleedChannels, plane);
            short[][] autofluorescencePixels =
                    channelPixels(sourceImage, autofluorescenceChannels, plane);
            short[] correctedPixels = correctedTargetImage == null
                    ? null
                    : CorrectionImageOps.singleChannelPlanePixels(correctedTargetImage, plane);

            for (int pixel = 0; pixel < targetPixels.length; pixel++) {
                int label = labels.get(pixel);
                if (label <= 0) {
                    continue;
                }
                Accumulator accumulator = accumulators.get(Integer.valueOf(label));
                if (accumulator == null) {
                    accumulator = new Accumulator(
                            label,
                            bleedChannels.size(),
                            autofluorescenceChannels.size(),
                            correctedPixels != null);
                    accumulators.put(Integer.valueOf(label), accumulator);
                }

                int targetValue = targetPixels[pixel] & 0xffff;
                accumulator.addTarget(targetValue);
                boolean highBleedPixel = false;
                for (int i = 0; i < bleedPixels.length; i++) {
                    int value = bleedPixels[i][pixel] & 0xffff;
                    accumulator.addBleed(i, value);
                    if (value >= bleedThresholds[i]) {
                        highBleedPixel = true;
                    }
                }
                if (highBleedPixel) {
                    accumulator.markHighBleed();
                }

                boolean highAutofluorescencePixel = false;
                for (int i = 0; i < autofluorescencePixels.length; i++) {
                    int value = autofluorescencePixels[i][pixel] & 0xffff;
                    accumulator.addAutofluorescence(i, value);
                    if (value >= autofluorescenceThresholds[i]) {
                        highAutofluorescencePixel = true;
                    }
                }
                if (highAutofluorescencePixel) {
                    accumulator.markHighAutofluorescence();
                }
                if (correctedPixels != null) {
                    accumulator.addCorrected(correctedPixels[pixel] & 0xffff);
                }
            }
        }

        List<ObjectScore> scores = new ArrayList<ObjectScore>();
        for (Accumulator accumulator : accumulators.values()) {
            scores.add(accumulator.toScore(resolved));
        }
        ImagePlus cleaned = createCleanedObjectMap(labelMap, scores);
        return new ScoreResult(scores, cleaned);
    }

    public static ImagePlus createCleanedObjectMap(ImagePlus labelMap, List<ObjectScore> scores) {
        if (labelMap == null) {
            throw new IllegalArgumentException("Object label image is required.");
        }
        ImagePlus cleaned = duplicateStack(labelMap, "cleaned_objects");
        cleaned.setTitle("cleaned_objects");

        Set<Integer> rejectedLabels = new HashSet<Integer>();
        if (scores != null) {
            for (ObjectScore score : scores) {
                if (score != null && !score.isKeepObject()) {
                    rejectedLabels.add(Integer.valueOf(score.getObjectId()));
                }
            }
        }
        if (rejectedLabels.isEmpty()) {
            return cleaned;
        }

        ImageStack stack = cleaned.getStack();
        for (int slice = 1; slice <= stack.getSize(); slice++) {
            ImageProcessor processor = stack.getProcessor(slice);
            int size = processor.getWidth() * processor.getHeight();
            for (int pixel = 0; pixel < size; pixel++) {
                if (rejectedLabels.contains(Integer.valueOf(processor.get(pixel)))) {
                    processor.set(pixel, 0);
                }
            }
        }
        return cleaned;
    }

    private static ImagePlus duplicateStack(ImagePlus image, String title) {
        ImageStack sourceStack = image.getStack();
        ImageStack copyStack = new ImageStack(image.getWidth(), image.getHeight());
        for (int slice = 1; slice <= sourceStack.getSize(); slice++) {
            copyStack.addSlice(sourceStack.getProcessor(slice).duplicate());
        }
        ImagePlus copy = new ImagePlus(title, copyStack);
        copy.setDimensions(
                Math.max(1, image.getNChannels()),
                Math.max(1, image.getNSlices()),
                Math.max(1, image.getNFrames()));
        if (copy.getNChannels() > 1 || copy.getNSlices() > 1 || copy.getNFrames() > 1) {
            copy.setOpenAsHyperStack(true);
        }
        if (image.getCalibration() != null) {
            copy.setCalibration(image.getCalibration().copy());
        }
        return copy;
    }

    private static void validateLabelMap(ImagePlus labelMap, ImagePlus sourceImage) {
        if (Math.max(1, labelMap.getNChannels()) != 1) {
            throw new IllegalArgumentException("Object label image must have exactly one channel.");
        }
        if (labelMap.getWidth() != sourceImage.getWidth()
                || labelMap.getHeight() != sourceImage.getHeight()) {
            throw new IllegalArgumentException("Object label image dimensions do not match the source image.");
        }
        int labelPlanes = Math.max(1, labelMap.getNSlices()) * Math.max(1, labelMap.getNFrames());
        int sourcePlanes = CorrectionImageOps.planeCount(sourceImage);
        if (labelPlanes != sourcePlanes) {
            throw new IllegalArgumentException("Object label image plane count does not match the source image.");
        }
    }

    private static void validateSingleChannelImage(ImagePlus image, ImagePlus sourceImage, String label) {
        if (image.getWidth() != sourceImage.getWidth()
                || image.getHeight() != sourceImage.getHeight()) {
            throw new IllegalArgumentException(label + " dimensions do not match the source image.");
        }
        if (CorrectionImageOps.planeCount(image) != CorrectionImageOps.planeCount(sourceImage)) {
            throw new IllegalArgumentException(label + " plane count does not match the source image.");
        }
    }

    private static List<Integer> validChannels(List<Integer> channels, int targetChannel, int channelCount) {
        List<Integer> out = new ArrayList<Integer>();
        if (channels == null) {
            return out;
        }
        for (Integer channel : channels) {
            if (channel == null) {
                continue;
            }
            int index = channel.intValue();
            if (index >= 0 && index < channelCount && index != targetChannel
                    && !out.contains(Integer.valueOf(index))) {
                out.add(Integer.valueOf(index));
            }
        }
        return out;
    }

    private static double[] highThresholds(ImagePlus sourceImage,
                                           List<Integer> channels,
                                           Settings settings) {
        double[] thresholds = new double[channels == null ? 0 : channels.size()];
        for (int i = 0; i < thresholds.length; i++) {
            thresholds[i] = CorrectionImageOps.percentile(
                    CorrectionImageOps.histogramForChannel(sourceImage, channels.get(i).intValue()),
                    settings.getHighSignalPercentile());
        }
        return thresholds;
    }

    private static short[][] channelPixels(ImagePlus sourceImage, List<Integer> channels, int plane) {
        short[][] pixels = new short[channels == null ? 0 : channels.size()][];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = CorrectionImageOps.channelPlanePixels(
                    sourceImage,
                    channels.get(i).intValue(),
                    plane);
        }
        return pixels;
    }

    private static ImageProcessor labelProcessor(ImagePlus labelMap, int plane) {
        int slices = Math.max(1, labelMap.getNSlices());
        int z = (plane % slices) + 1;
        int t = (plane / slices) + 1;
        return labelMap.getStack().getProcessor(labelMap.getStackIndex(1, z, t));
    }

    private static double maxMean(double[] sums, long count) {
        if (sums == null || sums.length == 0 || count <= 0L) {
            return Double.NaN;
        }
        double max = Double.NaN;
        for (double sum : sums) {
            double mean = sum / (double) count;
            if (Double.isNaN(max) || mean > max) {
                max = mean;
            }
        }
        return max;
    }

    private static double ratio(double numerator, double denominator) {
        if (Double.isNaN(denominator)) {
            return Double.NaN;
        }
        return numerator / Math.max(1.0, denominator);
    }

    private static double fraction(long numerator, long denominator) {
        return denominator <= 0L ? 0.0 : (double) numerator / (double) denominator;
    }

    private static double contaminationScore(double targetToMaxContaminantRatio,
                                             double highBleedOverlapFraction,
                                             double highAutofluorescenceOverlapFraction,
                                             double correctedRetentionFraction,
                                             Settings settings) {
        double score = 0.0;
        if (!Double.isNaN(targetToMaxContaminantRatio)
                && settings.getMinTargetToMaxContaminantRatio() > 0.0) {
            double ratioEvidence = (settings.getMinTargetToMaxContaminantRatio()
                    - targetToMaxContaminantRatio)
                    / settings.getMinTargetToMaxContaminantRatio();
            score = Math.max(score, clamp01(ratioEvidence));
        }
        score = Math.max(score, clamp01(highBleedOverlapFraction));
        score = Math.max(score, clamp01(highAutofluorescenceOverlapFraction));
        if (!Double.isNaN(correctedRetentionFraction)
                && settings.getMinCorrectedRetentionFraction() > 0.0) {
            double correctedEvidence = (settings.getMinCorrectedRetentionFraction()
                    - correctedRetentionFraction)
                    / settings.getMinCorrectedRetentionFraction();
            score = Math.max(score, clamp01(correctedEvidence));
        }
        return score;
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }

    private static String joinReasons(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < reasons.size(); i++) {
            if (i > 0) {
                sb.append(';');
            }
            sb.append(reasons.get(i));
        }
        return sb.toString();
    }

    private static final class Accumulator {
        private final int label;
        private final double[] bleedSums;
        private final double[] autofluorescenceSums;
        private final IntList targetValues = new IntList();
        private final IntList correctedValues;

        private long voxelCount = 0L;
        private double targetSum = 0.0;
        private double correctedSum = 0.0;
        private long highBleedCount = 0L;
        private long highAutofluorescenceCount = 0L;

        private Accumulator(int label,
                            int bleedChannelCount,
                            int autofluorescenceChannelCount,
                            boolean hasCorrectedTarget) {
            this.label = label;
            this.bleedSums = new double[bleedChannelCount];
            this.autofluorescenceSums = new double[autofluorescenceChannelCount];
            this.correctedValues = hasCorrectedTarget ? new IntList() : null;
        }

        private void addTarget(int value) {
            voxelCount++;
            targetSum += value;
            targetValues.add(value);
        }

        private void addBleed(int index, int value) {
            bleedSums[index] += value;
        }

        private void addAutofluorescence(int index, int value) {
            autofluorescenceSums[index] += value;
        }

        private void markHighBleed() {
            highBleedCount++;
        }

        private void markHighAutofluorescence() {
            highAutofluorescenceCount++;
        }

        private void addCorrected(int value) {
            correctedSum += value;
            correctedValues.add(value);
        }

        private ObjectScore toScore(Settings settings) {
            double targetMean = voxelCount <= 0L ? 0.0 : targetSum / (double) voxelCount;
            double targetP99 = targetValues.percentile(99.0);
            double maxBleedMean = maxMean(bleedSums, voxelCount);
            double maxAutofluorescenceMean = maxMean(autofluorescenceSums, voxelCount);
            double maxContaminantMean = Math.max(
                    Double.isNaN(maxBleedMean) ? 0.0 : maxBleedMean,
                    Double.isNaN(maxAutofluorescenceMean) ? 0.0 : maxAutofluorescenceMean);
            if (bleedSums.length == 0 && autofluorescenceSums.length == 0) {
                maxContaminantMean = Double.NaN;
            }

            double targetToMaxContaminantRatio = ratio(targetMean, maxContaminantMean);
            double targetToBleedThroughRatio = ratio(targetMean, maxBleedMean);
            double targetToAutofluorescenceRatio = ratio(targetMean, maxAutofluorescenceMean);
            double highBleedOverlapFraction = bleedSums.length == 0
                    ? 0.0
                    : fraction(highBleedCount, voxelCount);
            double highAutofluorescenceOverlapFraction = autofluorescenceSums.length == 0
                    ? 0.0
                    : fraction(highAutofluorescenceCount, voxelCount);
            double brightTailScore = targetMean <= 0.0
                    ? 0.0
                    : Math.max(0.0, (targetP99 - targetMean) / Math.max(1.0, targetMean));

            double correctedMean = correctedValues == null || voxelCount <= 0L
                    ? Double.NaN
                    : correctedSum / (double) voxelCount;
            double correctedP99 = correctedValues == null ? Double.NaN : correctedValues.percentile(99.0);
            double correctedRetentionFraction = correctedValues == null || targetMean <= 0.0
                    ? Double.NaN
                    : correctedMean / Math.max(1.0, targetMean);

            List<String> reasons = new ArrayList<String>();
            if (!Double.isNaN(targetToMaxContaminantRatio)
                    && targetToMaxContaminantRatio < settings.getMinTargetToMaxContaminantRatio()) {
                reasons.add("low_target_to_contaminant_ratio");
            }
            if (bleedSums.length > 0
                    && highBleedOverlapFraction >= settings.getMaxHighBleedOverlapFraction()) {
                reasons.add("high_bleed_through_overlap");
            }
            if (autofluorescenceSums.length > 0
                    && highAutofluorescenceOverlapFraction
                    >= settings.getMaxHighAutofluorescenceOverlapFraction()) {
                reasons.add("high_autofluorescence_overlap");
            }
            if (!Double.isNaN(correctedRetentionFraction)
                    && correctedRetentionFraction < settings.getMinCorrectedRetentionFraction()) {
                reasons.add("low_corrected_target_retention");
            }

            double contaminationScore = contaminationScore(
                    targetToMaxContaminantRatio,
                    highBleedOverlapFraction,
                    highAutofluorescenceOverlapFraction,
                    correctedRetentionFraction,
                    settings);
            return new ObjectScore(
                    label,
                    voxelCount,
                    targetMean,
                    targetP99,
                    maxContaminantMean,
                    targetToMaxContaminantRatio,
                    maxBleedMean,
                    targetToBleedThroughRatio,
                    maxAutofluorescenceMean,
                    targetToAutofluorescenceRatio,
                    highAutofluorescenceOverlapFraction,
                    highBleedOverlapFraction,
                    brightTailScore,
                    correctedMean,
                    correctedP99,
                    correctedRetentionFraction,
                    contaminationScore,
                    reasons.isEmpty(),
                    joinReasons(reasons));
        }
    }

    public static final class Settings {
        private double highSignalPercentile = 99.0;
        private double minTargetToMaxContaminantRatio = 1.0;
        private double maxHighAutofluorescenceOverlapFraction = 0.50;
        private double maxHighBleedOverlapFraction = 0.50;
        private double minCorrectedRetentionFraction = 0.25;

        public double getHighSignalPercentile() {
            return highSignalPercentile;
        }

        public Settings setHighSignalPercentile(double highSignalPercentile) {
            if (Double.isNaN(highSignalPercentile) || Double.isInfinite(highSignalPercentile)) {
                this.highSignalPercentile = 99.0;
            } else {
                this.highSignalPercentile = Math.max(0.0, Math.min(100.0, highSignalPercentile));
            }
            return this;
        }

        public double getMinTargetToMaxContaminantRatio() {
            return minTargetToMaxContaminantRatio;
        }

        public Settings setMinTargetToMaxContaminantRatio(double minTargetToMaxContaminantRatio) {
            this.minTargetToMaxContaminantRatio = sanitizeNonNegative(
                    minTargetToMaxContaminantRatio,
                    1.0);
            return this;
        }

        public double getMaxHighAutofluorescenceOverlapFraction() {
            return maxHighAutofluorescenceOverlapFraction;
        }

        public Settings setMaxHighAutofluorescenceOverlapFraction(double value) {
            this.maxHighAutofluorescenceOverlapFraction = clampFraction(value, 0.50);
            return this;
        }

        public double getMaxHighBleedOverlapFraction() {
            return maxHighBleedOverlapFraction;
        }

        public Settings setMaxHighBleedOverlapFraction(double value) {
            this.maxHighBleedOverlapFraction = clampFraction(value, 0.50);
            return this;
        }

        public double getMinCorrectedRetentionFraction() {
            return minCorrectedRetentionFraction;
        }

        public Settings setMinCorrectedRetentionFraction(double value) {
            this.minCorrectedRetentionFraction = clampFraction(value, 0.25);
            return this;
        }

        public Settings copy() {
            Settings copy = new Settings();
            copy.highSignalPercentile = highSignalPercentile;
            copy.minTargetToMaxContaminantRatio = minTargetToMaxContaminantRatio;
            copy.maxHighAutofluorescenceOverlapFraction = maxHighAutofluorescenceOverlapFraction;
            copy.maxHighBleedOverlapFraction = maxHighBleedOverlapFraction;
            copy.minCorrectedRetentionFraction = minCorrectedRetentionFraction;
            return copy;
        }

        private static double sanitizeNonNegative(double value, double defaultValue) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return defaultValue;
            }
            return Math.max(0.0, value);
        }

        private static double clampFraction(double value, double defaultValue) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return defaultValue;
            }
            if (value < 0.0) {
                return 0.0;
            }
            if (value > 1.0) {
                return 1.0;
            }
            return value;
        }
    }

    public static final class ScoreResult {
        private final List<ObjectScore> scores;
        private final ImagePlus cleanedObjectMap;

        private ScoreResult(List<ObjectScore> scores, ImagePlus cleanedObjectMap) {
            this.scores = scores == null
                    ? new ArrayList<ObjectScore>()
                    : new ArrayList<ObjectScore>(scores);
            this.cleanedObjectMap = cleanedObjectMap;
        }

        public List<ObjectScore> getScores() {
            return Collections.unmodifiableList(scores);
        }

        public ImagePlus getCleanedObjectMap() {
            return cleanedObjectMap;
        }

        public int getKeptCount() {
            int count = 0;
            for (ObjectScore score : scores) {
                if (score != null && score.isKeepObject()) {
                    count++;
                }
            }
            return count;
        }

        public int getRejectedCount() {
            return scores.size() - getKeptCount();
        }
    }

    public static final class ObjectScore {
        private final int objectId;
        private final long voxelCount;
        private final double targetMean;
        private final double targetP99;
        private final double maxContaminantMean;
        private final double targetToMaxContaminantRatio;
        private final double maxBleedThroughMean;
        private final double targetToBleedThroughRatio;
        private final double maxAutofluorescenceMean;
        private final double targetToAutofluorescenceRatio;
        private final double highAutofluorescenceOverlapFraction;
        private final double highBleedThroughOverlapFraction;
        private final double brightTailScore;
        private final double correctedTargetMean;
        private final double correctedTargetP99;
        private final double correctedTargetRetentionFraction;
        private final double contaminationScore;
        private final boolean keepObject;
        private final String rejectReason;

        private ObjectScore(int objectId,
                            long voxelCount,
                            double targetMean,
                            double targetP99,
                            double maxContaminantMean,
                            double targetToMaxContaminantRatio,
                            double maxBleedThroughMean,
                            double targetToBleedThroughRatio,
                            double maxAutofluorescenceMean,
                            double targetToAutofluorescenceRatio,
                            double highAutofluorescenceOverlapFraction,
                            double highBleedThroughOverlapFraction,
                            double brightTailScore,
                            double correctedTargetMean,
                            double correctedTargetP99,
                            double correctedTargetRetentionFraction,
                            double contaminationScore,
                            boolean keepObject,
                            String rejectReason) {
            this.objectId = objectId;
            this.voxelCount = voxelCount;
            this.targetMean = targetMean;
            this.targetP99 = targetP99;
            this.maxContaminantMean = maxContaminantMean;
            this.targetToMaxContaminantRatio = targetToMaxContaminantRatio;
            this.maxBleedThroughMean = maxBleedThroughMean;
            this.targetToBleedThroughRatio = targetToBleedThroughRatio;
            this.maxAutofluorescenceMean = maxAutofluorescenceMean;
            this.targetToAutofluorescenceRatio = targetToAutofluorescenceRatio;
            this.highAutofluorescenceOverlapFraction = highAutofluorescenceOverlapFraction;
            this.highBleedThroughOverlapFraction = highBleedThroughOverlapFraction;
            this.brightTailScore = brightTailScore;
            this.correctedTargetMean = correctedTargetMean;
            this.correctedTargetP99 = correctedTargetP99;
            this.correctedTargetRetentionFraction = correctedTargetRetentionFraction;
            this.contaminationScore = contaminationScore;
            this.keepObject = keepObject;
            this.rejectReason = rejectReason == null ? "" : rejectReason.trim().toLowerCase(Locale.US);
        }

        public int getObjectId() {
            return objectId;
        }

        public long getVoxelCount() {
            return voxelCount;
        }

        public double getTargetMean() {
            return targetMean;
        }

        public double getTargetP99() {
            return targetP99;
        }

        public double getMaxContaminantMean() {
            return maxContaminantMean;
        }

        public double getTargetToMaxContaminantRatio() {
            return targetToMaxContaminantRatio;
        }

        public double getMaxBleedThroughMean() {
            return maxBleedThroughMean;
        }

        public double getTargetToBleedThroughRatio() {
            return targetToBleedThroughRatio;
        }

        public double getMaxAutofluorescenceMean() {
            return maxAutofluorescenceMean;
        }

        public double getTargetToAutofluorescenceRatio() {
            return targetToAutofluorescenceRatio;
        }

        public double getHighAutofluorescenceOverlapFraction() {
            return highAutofluorescenceOverlapFraction;
        }

        public double getHighBleedThroughOverlapFraction() {
            return highBleedThroughOverlapFraction;
        }

        public double getBrightTailScore() {
            return brightTailScore;
        }

        public double getCorrectedTargetMean() {
            return correctedTargetMean;
        }

        public double getCorrectedTargetP99() {
            return correctedTargetP99;
        }

        public double getCorrectedTargetRetentionFraction() {
            return correctedTargetRetentionFraction;
        }

        public double getContaminationScore() {
            return contaminationScore;
        }

        public boolean isKeepObject() {
            return keepObject;
        }

        public String getRejectReason() {
            return rejectReason;
        }
    }

    private static final class IntList {
        private int[] values = new int[32];
        private int size = 0;

        private void add(int value) {
            if (size >= values.length) {
                int[] expanded = new int[values.length * 2];
                System.arraycopy(values, 0, expanded, 0, values.length);
                values = expanded;
            }
            values[size++] = value;
        }

        private double percentile(double percentile) {
            if (size <= 0) {
                return 0.0;
            }
            int[] copy = new int[size];
            System.arraycopy(values, 0, copy, 0, size);
            java.util.Arrays.sort(copy);
            double clamped = Math.max(0.0, Math.min(100.0, percentile));
            int index = (int) Math.ceil((clamped / 100.0) * size) - 1;
            if (index < 0) {
                index = 0;
            }
            if (index >= size) {
                index = size - 1;
            }
            return copy[index];
        }
    }
}
