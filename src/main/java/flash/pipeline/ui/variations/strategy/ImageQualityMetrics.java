package flash.pipeline.ui.variations.strategy;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.AutoThresholder;
import ij.process.ImageProcessor;

/**
 * Computes the lightweight image-quality readouts shown under each variations-grid
 * cell for image-producing sweeps (filter, deconvolution): an intensity histogram, a
 * foreground/background SNR, and the background standard deviation. The foreground and
 * background are split at an Otsu threshold over the pooled stack histogram.
 */
public final class ImageQualityMetrics {

    private static final int HISTOGRAM_BINS = 256;

    private ImageQualityMetrics() {
    }

    public static final class Result {
        public final int[] histogram;
        public final double snr;
        public final double bgSigma;

        Result(int[] histogram, double snr, double bgSigma) {
            this.histogram = histogram;
            this.snr = snr;
            this.bgSigma = bgSigma;
        }
    }

    public static Result compute(ImagePlus image) {
        PixelRange range = pixelRange(image);
        int[] histogram = new int[HISTOGRAM_BINS];
        if (!range.hasValues) {
            return new Result(histogram, 0.0d, 0.0d);
        }
        fillHistogram(image, range, histogram);
        int thresholdBin = new AutoThresholder().getThreshold(
                AutoThresholder.Method.Otsu, histogram);
        RunningStats foreground = new RunningStats();
        RunningStats background = new RunningStats();
        accumulateStats(image, range, thresholdBin, foreground, background);
        double bgSigma = background.standardDeviation();
        double snr = bgSigma <= 0.0d ? 0.0d : foreground.mean() / bgSigma;
        return new Result(histogram, snr, bgSigma);
    }

    private static PixelRange pixelRange(ImagePlus image) {
        if (image == null) {
            return new PixelRange(false, 0.0d, 0.0d);
        }
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        ImageStack stack = image.getStack();
        int size = stack == null ? 1 : Math.max(1, stack.getSize());
        for (int slice = 1; slice <= size; slice++) {
            ImageProcessor processor = stack == null
                    ? image.getProcessor()
                    : stack.getProcessor(slice);
            if (processor == null) {
                continue;
            }
            for (int i = 0; i < processor.getPixelCount(); i++) {
                double value = processor.getf(i);
                if (!Double.isFinite(value)) {
                    continue;
                }
                if (value < min) min = value;
                if (value > max) max = value;
            }
        }
        if (!Double.isFinite(min) || !Double.isFinite(max)) {
            return new PixelRange(false, 0.0d, 0.0d);
        }
        return new PixelRange(true, min, max);
    }

    private static void fillHistogram(ImagePlus image, PixelRange range, int[] histogram) {
        ImageStack stack = image.getStack();
        int size = stack == null ? 1 : Math.max(1, stack.getSize());
        for (int slice = 1; slice <= size; slice++) {
            ImageProcessor processor = stack == null
                    ? image.getProcessor()
                    : stack.getProcessor(slice);
            if (processor == null) {
                continue;
            }
            for (int i = 0; i < processor.getPixelCount(); i++) {
                double value = processor.getf(i);
                if (Double.isFinite(value)) {
                    histogram[binFor(value, range)]++;
                }
            }
        }
    }

    private static void accumulateStats(ImagePlus image,
                                        PixelRange range,
                                        int thresholdBin,
                                        RunningStats foreground,
                                        RunningStats background) {
        ImageStack stack = image.getStack();
        int size = stack == null ? 1 : Math.max(1, stack.getSize());
        for (int slice = 1; slice <= size; slice++) {
            ImageProcessor processor = stack == null
                    ? image.getProcessor()
                    : stack.getProcessor(slice);
            if (processor == null) {
                continue;
            }
            for (int i = 0; i < processor.getPixelCount(); i++) {
                double value = processor.getf(i);
                if (!Double.isFinite(value)) {
                    continue;
                }
                if (binFor(value, range) > thresholdBin) {
                    foreground.add(value);
                } else {
                    background.add(value);
                }
            }
        }
    }

    private static int binFor(double value, PixelRange range) {
        if (range.max <= range.min) {
            return 0;
        }
        int bin = (int) Math.floor(((value - range.min) / (range.max - range.min))
                * (HISTOGRAM_BINS - 1));
        if (bin < 0) return 0;
        if (bin >= HISTOGRAM_BINS) return HISTOGRAM_BINS - 1;
        return bin;
    }

    private static final class PixelRange {
        final boolean hasValues;
        final double min;
        final double max;

        PixelRange(boolean hasValues, double min, double max) {
            this.hasValues = hasValues;
            this.min = min;
            this.max = max;
        }
    }

    private static final class RunningStats {
        private int count;
        private double mean;
        private double m2;

        void add(double value) {
            count++;
            double delta = value - mean;
            mean += delta / count;
            double delta2 = value - mean;
            m2 += delta * delta2;
        }

        double mean() {
            return count == 0 ? 0.0d : mean;
        }

        double standardDeviation() {
            return count == 0 ? 0.0d : Math.sqrt(m2 / count);
        }
    }
}
