package flash.pipeline.ui.preview;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.AutoThresholder;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;

public final class ThresholdOverlayRenderer {

    public static final String MODE_RED_OVERLAY = "Red overlay";
    public static final String MODE_MASK = "Mask";
    public static final String MODE_FILTERED = "Filtered";

    private static final int RED = 0xff3030;
    private static final double OVERLAY_ALPHA = 0.62;
    private static final int OTSU_HISTOGRAM_BINS = 256;

    private ThresholdOverlayRenderer() {
    }

    public static ImagePlus render(ImagePlus source, double lower, double upper, String mode) {
        if (source == null) return null;
        String safeMode = mode == null ? MODE_RED_OVERLAY : mode.trim();
        if (MODE_FILTERED.equalsIgnoreCase(safeMode)) {
            ImagePlus filtered = source.duplicate();
            filtered.setTitle("Threshold filtered input");
            return filtered;
        }
        if (MODE_MASK.equalsIgnoreCase(safeMode)
                || "Black and white".equalsIgnoreCase(safeMode)) {
            return renderMask(source, lower, upper);
        }
        return renderRedOverlay(source, lower, upper);
    }

    public static ImagePlus renderOtsuRedOverlay(ImagePlus source) {
        OtsuThreshold threshold = otsuThreshold(source);
        if (!threshold.hasValues()) return null;
        return render(source, threshold.getLower(), threshold.getUpper(), MODE_RED_OVERLAY);
    }

    public static OtsuThreshold otsuThreshold(ImagePlus source) {
        Histogram histogram = histogramFor(source);
        if (!histogram.hasValues()) {
            return OtsuThreshold.empty();
        }
        int thresholdBin = new AutoThresholder().getThreshold(
                AutoThresholder.Method.Otsu, histogram.counts);
        return new OtsuThreshold(
                histogram.foregroundLowerFor(thresholdBin),
                histogram.max,
                true);
    }

    private static ImagePlus renderMask(ImagePlus source, double lower, double upper) {
        ImageStack in = source.getStack();
        if (in == null || in.getSize() < 1) return null;
        ImageStack out = new ImageStack(source.getWidth(), source.getHeight());
        for (int i = 1; i <= in.getSize(); i++) {
            ImageProcessor ip = in.getProcessor(i);
            ByteProcessor mask = new ByteProcessor(ip.getWidth(), ip.getHeight());
            for (int y = 0; y < ip.getHeight(); y++) {
                for (int x = 0; x < ip.getWidth(); x++) {
                    mask.set(x, y, isForeground(ip.getPixelValue(x, y), lower, upper) ? 255 : 0);
                }
            }
            out.addSlice(in.getSliceLabel(i), mask);
        }
        ImagePlus result = new ImagePlus("Threshold mask", out);
        copyDimensions(source, result);
        result.setDisplayRange(0, 255);
        return result;
    }

    private static ImagePlus renderRedOverlay(ImagePlus source, double lower, double upper) {
        ImageStack in = source.getStack();
        if (in == null || in.getSize() < 1) return null;
        ImageStack out = new ImageStack(source.getWidth(), source.getHeight());
        double displayMin = source.getDisplayRangeMin();
        double displayMax = source.getDisplayRangeMax();
        if (!(displayMax > displayMin)) {
            displayMin = Double.NaN;
            displayMax = Double.NaN;
        }

        for (int i = 1; i <= in.getSize(); i++) {
            ImageProcessor ip = in.getProcessor(i);
            double min = Double.isFinite(displayMin) ? displayMin : ip.getMin();
            double max = Double.isFinite(displayMax) ? displayMax : ip.getMax();
            if (!(max > min)) {
                max = min + 1.0;
            }

            int width = ip.getWidth();
            int height = ip.getHeight();
            int[] pixels = new int[width * height];
            int p = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    double value = ip.getPixelValue(x, y);
                    int gray = scaledByte(value, min, max);
                    int base = (gray << 16) | (gray << 8) | gray;
                    pixels[p++] = isForeground(value, lower, upper)
                            ? blend(base, RED, OVERLAY_ALPHA)
                            : base;
                }
            }
            ColorProcessor cp = new ColorProcessor(width, height);
            cp.setPixels(pixels);
            out.addSlice(in.getSliceLabel(i), cp);
        }
        ImagePlus result = new ImagePlus("Threshold red overlay", out);
        copyDimensions(source, result);
        return result;
    }

    private static boolean isForeground(double value, double lower, double upper) {
        if (!Double.isFinite(value)) return false;
        double lo = Math.min(lower, upper);
        double hi = Math.max(lower, upper);
        return value >= lo && value <= hi;
    }

    private static int scaledByte(double value, double min, double max) {
        if (!Double.isFinite(value)) return 0;
        double scaled = (value - min) / (max - min);
        if (scaled < 0.0) return 0;
        if (scaled > 1.0) return 255;
        return (int) Math.round(scaled * 255.0);
    }

    private static int blend(int base, int overlay, double alpha) {
        double a = Math.max(0.0, Math.min(1.0, alpha));
        int br = (base >> 16) & 0xff;
        int bg = (base >> 8) & 0xff;
        int bb = base & 0xff;
        int or = (overlay >> 16) & 0xff;
        int og = (overlay >> 8) & 0xff;
        int ob = overlay & 0xff;
        int r = (int) Math.round(br * (1.0 - a) + or * a);
        int g = (int) Math.round(bg * (1.0 - a) + og * a);
        int b = (int) Math.round(bb * (1.0 - a) + ob * a);
        return (r << 16) | (g << 8) | b;
    }

    private static void copyDimensions(ImagePlus source, ImagePlus result) {
        result.setCalibration(source.getCalibration());
        int channels = 1;
        int slices = Math.max(1, source.getNSlices());
        int frames = Math.max(1, source.getNFrames());
        if (result.getStackSize() == channels * slices * frames) {
            result.setDimensions(channels, slices, frames);
        }
        result.setOpenAsHyperStack(source.isHyperStack());
    }

    private static Histogram histogramFor(ImagePlus image) {
        if (image == null) {
            return Histogram.empty();
        }
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        boolean integral = true;
        ImageStack stack = image.getStack();
        int size = stack == null ? 1 : Math.max(1, stack.getSize());
        for (int slice = 1; slice <= size; slice++) {
            ImageProcessor processor = stack == null
                    ? image.getProcessor()
                    : stack.getProcessor(slice);
            if (processor == null) {
                continue;
            }
            for (int y = 0; y < processor.getHeight(); y++) {
                for (int x = 0; x < processor.getWidth(); x++) {
                    double value = processor.getPixelValue(x, y);
                    if (!Double.isFinite(value)) {
                        continue;
                    }
                    if (value < min) min = value;
                    if (value > max) max = value;
                    if (Math.abs(value - Math.rint(value)) > 0.000001d) {
                        integral = false;
                    }
                }
            }
        }
        if (!Double.isFinite(min) || !Double.isFinite(max)) {
            return Histogram.empty();
        }
        boolean direct = integral && min >= 0.0d && max <= 65535.0d;
        int bins = direct
                ? (max <= 255.0d ? 256 : 65536)
                : OTSU_HISTOGRAM_BINS;
        int[] counts = new int[bins];
        if (max <= min) {
            int bin = direct ? (int) Math.round(min) : 0;
            counts[Math.max(0, Math.min(counts.length - 1, bin))] = pixelCount(image);
            return new Histogram(counts, min, max, direct, true);
        }
        for (int slice = 1; slice <= size; slice++) {
            ImageProcessor processor = stack == null
                    ? image.getProcessor()
                    : stack.getProcessor(slice);
            if (processor == null) {
                continue;
            }
            for (int y = 0; y < processor.getHeight(); y++) {
                for (int x = 0; x < processor.getWidth(); x++) {
                    double value = processor.getPixelValue(x, y);
                    if (!Double.isFinite(value)) {
                        continue;
                    }
                    int bin = direct
                            ? (int) Math.round(value)
                            : (int) Math.floor(((value - min) / (max - min))
                            * (bins - 1));
                    counts[Math.max(0, Math.min(counts.length - 1, bin))]++;
                }
            }
        }
        return new Histogram(counts, min, max, direct, true);
    }

    private static int pixelCount(ImagePlus image) {
        if (image == null) {
            return 0;
        }
        int width = Math.max(1, image.getWidth());
        int height = Math.max(1, image.getHeight());
        int slices = Math.max(1, image.getStackSize());
        return width * height * slices;
    }

    public static final class OtsuThreshold {
        private final double lower;
        private final double upper;
        private final boolean hasValues;

        private OtsuThreshold(double lower, double upper, boolean hasValues) {
            this.lower = lower;
            this.upper = upper;
            this.hasValues = hasValues;
        }

        public static OtsuThreshold empty() {
            return new OtsuThreshold(Double.NaN, Double.NaN, false);
        }

        public boolean hasValues() {
            return hasValues;
        }

        public double getLower() {
            return lower;
        }

        public double getUpper() {
            return upper;
        }
    }

    private static final class Histogram {
        final int[] counts;
        final double min;
        final double max;
        final boolean direct;
        final boolean hasValues;

        Histogram(int[] counts, double min, double max, boolean direct,
                  boolean hasValues) {
            this.counts = counts == null ? new int[0] : counts;
            this.min = min;
            this.max = max;
            this.direct = direct;
            this.hasValues = hasValues;
        }

        static Histogram empty() {
            return new Histogram(new int[OTSU_HISTOGRAM_BINS], 0.0d, 0.0d,
                    false, false);
        }

        boolean hasValues() {
            return hasValues && counts.length > 0;
        }

        double foregroundLowerFor(int thresholdBin) {
            if (max <= min) {
                return max;
            }
            int clamped = Math.max(0, Math.min(counts.length - 1, thresholdBin));
            int next = Math.min(counts.length - 1, clamped + 1);
            return valueFor(next);
        }

        private double valueFor(int bin) {
            int clamped = Math.max(0, Math.min(counts.length - 1, bin));
            if (max <= min) {
                return min;
            }
            if (direct) {
                return clamped;
            }
            return min + ((double) clamped / (double) Math.max(1, counts.length - 1))
                    * (max - min);
        }
    }
}
