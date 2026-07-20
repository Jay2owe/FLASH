package flash.pipeline.decontamination;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Direct 16-bit image helpers for Spectral Decontamination features.
 */
public final class CorrectionImageOps {

    private static final int MAX_16BIT_VALUE = 65535;

    public enum ThresholdMode {
        FIXED("fixed"),
        MEDIAN("median"),
        PERCENTILE("percentile");

        private final String key;

        ThresholdMode(String key) {
            this.key = key;
        }

        public String getKey() {
            return key;
        }

        public static ThresholdMode fromKey(String value, ThresholdMode defaultMode) {
            if (value == null || value.trim().isEmpty()) {
                return defaultMode == null ? PERCENTILE : defaultMode;
            }
            String normalized = value.trim().toLowerCase(Locale.US);
            for (ThresholdMode mode : values()) {
                if (mode.key.equals(normalized) || mode.name().equalsIgnoreCase(normalized)) {
                    return mode;
                }
            }
            return defaultMode == null ? PERCENTILE : defaultMode;
        }
    }

    public static final class Histogram {
        private final int[] counts;
        private final long totalPixels;

        private Histogram(int[] counts, long totalPixels) {
            this.counts = counts;
            this.totalPixels = totalPixels;
        }

        public int[] getCounts() {
            return counts;
        }

        public long getTotalPixels() {
            return totalPixels;
        }
    }

    private CorrectionImageOps() {
    }

    public static void require16Bit(ImagePlus image, String label) {
        if (image == null) {
            throw new IllegalArgumentException(label + " image is required.");
        }
        if (image.getBitDepth() != 16) {
            throw new IllegalArgumentException(label + " image must be 16-bit.");
        }
    }

    public static void requireSingleChannel16Bit(ImagePlus image, String label) {
        require16Bit(image, label);
        if (Math.max(1, image.getNChannels()) != 1) {
            throw new IllegalArgumentException(label + " image must have exactly one channel.");
        }
    }

    public static void requireSingleChannelMask(ImagePlus image, String label) {
        if (image == null) {
            throw new IllegalArgumentException(label + " image is required.");
        }
        if (image.getBitDepth() != 8) {
            throw new IllegalArgumentException(label + " image must be 8-bit.");
        }
        if (Math.max(1, image.getNChannels()) != 1) {
            throw new IllegalArgumentException(label + " image must have exactly one channel.");
        }
    }

    public static int planeCount(ImagePlus image) {
        if (image == null) return 0;
        return Math.max(1, image.getNSlices()) * Math.max(1, image.getNFrames());
    }

    public static short[] channelPlanePixels(ImagePlus image, int channelIndex, int planeIndex) {
        require16Bit(image, "Source");
        int channelCount = Math.max(1, image.getNChannels());
        if (channelIndex < 0 || channelIndex >= channelCount) {
            throw new IllegalArgumentException("Channel index is outside the image: " + channelIndex);
        }
        int stackIndex = stackIndex(image, channelIndex, planeIndex);
        Object pixels = image.getStack().getProcessor(stackIndex).getPixels();
        if (!(pixels instanceof short[])) {
            throw new IllegalArgumentException("Expected 16-bit pixels for channel " + (channelIndex + 1));
        }
        return (short[]) pixels;
    }

    public static short[] singleChannelPlanePixels(ImagePlus image, int planeIndex) {
        requireSingleChannel16Bit(image, "Corrected");
        return channelPlanePixels(image, 0, planeIndex);
    }

    public static byte[] singleChannelMaskPlanePixels(ImagePlus image, int planeIndex) {
        requireSingleChannelMask(image, "Mask");
        int stackIndex = stackIndex(image, 0, planeIndex);
        Object pixels = image.getStack().getProcessor(stackIndex).getPixels();
        if (!(pixels instanceof byte[])) {
            throw new IllegalArgumentException("Expected 8-bit mask pixels.");
        }
        return (byte[]) pixels;
    }

    public static Histogram histogramForChannel(ImagePlus image, int channelIndex) {
        require16Bit(image, "Source");
        int[] histogram = new int[MAX_16BIT_VALUE + 1];
        long total = 0L;
        int planes = planeCount(image);
        for (int plane = 0; plane < planes; plane++) {
            short[] pixels = channelPlanePixels(image, channelIndex, plane);
            for (short pixel : pixels) {
                histogram[pixel & 0xffff]++;
                total++;
            }
        }
        return new Histogram(histogram, total);
    }

    public static Histogram histogramForSingleChannel(ImagePlus image) {
        requireSingleChannel16Bit(image, "Corrected");
        return histogramForChannel(image, 0);
    }

    public static double thresholdForMode(Histogram histogram,
                                          ThresholdMode mode,
                                          double percentile,
                                          double fixedValue) {
        ThresholdMode resolvedMode = mode == null ? ThresholdMode.PERCENTILE : mode;
        if (resolvedMode == ThresholdMode.FIXED) {
            return clampTo16Bit(fixedValue);
        }
        if (resolvedMode == ThresholdMode.MEDIAN) {
            return median(histogram);
        }
        return percentile(histogram, percentile);
    }

    public static double percentile(Histogram histogram, double percentile) {
        if (histogram == null || histogram.getTotalPixels() <= 0) return 0.0;
        double clampedPercentile = Math.max(0.0, Math.min(100.0, percentile));
        long rank = (long) Math.ceil((clampedPercentile / 100.0) * histogram.getTotalPixels()) - 1L;
        if (rank < 0L) rank = 0L;
        return intensityAtRank(histogram, rank);
    }

    public static double median(Histogram histogram) {
        if (histogram == null || histogram.getTotalPixels() <= 0) return 0.0;
        long leftRank = (histogram.getTotalPixels() - 1L) / 2L;
        long rightRank = histogram.getTotalPixels() / 2L;
        int left = intensityAtRank(histogram, leftRank);
        int right = intensityAtRank(histogram, rightRank);
        return (left + right) / 2.0;
    }

    public static ImagePlus createShortImageLike(ImagePlus template, String title, short[][] planes) {
        require16Bit(template, "Template");
        validatePlanes(template, planes);
        int width = template.getWidth();
        int height = template.getHeight();
        ImageStack stack = new ImageStack(width, height);
        for (short[] plane : planes) {
            if (plane == null || plane.length != width * height) {
                throw new IllegalArgumentException("Short image plane has the wrong size.");
            }
            stack.addSlice(new ShortProcessor(width, height, plane, null));
        }
        ImagePlus out = new ImagePlus(title == null ? "corrected" : title, stack);
        configureSingleChannelLike(template, out);
        return out;
    }

    public static ImagePlus createMaskImageLike(ImagePlus template, String title, byte[][] planes) {
        if (template == null) {
            throw new IllegalArgumentException("Template image is required.");
        }
        validatePlanes(template, planes);
        int width = template.getWidth();
        int height = template.getHeight();
        ImageStack stack = new ImageStack(width, height);
        for (byte[] plane : planes) {
            if (plane == null || plane.length != width * height) {
                throw new IllegalArgumentException("Mask image plane has the wrong size.");
            }
            stack.addSlice(new ByteProcessor(width, height, plane, null));
        }
        ImagePlus out = new ImagePlus(title == null ? "mask" : title, stack);
        configureSingleChannelLike(template, out);
        return out;
    }

    public static ImagePlus createFloatImageLike(ImagePlus template, String title, float[][] planes) {
        if (template == null) {
            throw new IllegalArgumentException("Template image is required.");
        }
        validatePlanes(template, planes);
        int width = template.getWidth();
        int height = template.getHeight();
        ImageStack stack = new ImageStack(width, height);
        for (float[] plane : planes) {
            if (plane == null || plane.length != width * height) {
                throw new IllegalArgumentException("Float image plane has the wrong size.");
            }
            stack.addSlice(new FloatProcessor(width, height, plane, null));
        }
        ImagePlus out = new ImagePlus(title == null ? "parameter_map" : title, stack);
        configureSingleChannelLike(template, out);
        return out;
    }

    public static List<Integer> contaminantChannels(SpectralDecontaminationConfig config) {
        List<Integer> out = new ArrayList<Integer>();
        if (config == null) return out;
        LinkedHashSet<Integer> unique = new LinkedHashSet<Integer>();
        for (Integer index : config.getBleedThroughChannelIndexes()) {
            if (index != null && index.intValue() >= 0 && index.intValue() != config.getTargetChannelIndex()) {
                unique.add(index);
            }
        }
        for (Integer index : config.getAutofluorescenceChannelIndexes()) {
            if (index != null && index.intValue() >= 0 && index.intValue() != config.getTargetChannelIndex()) {
                unique.add(index);
            }
        }
        out.addAll(unique);
        return out;
    }

    public static String channelLabel(int channelIndex) {
        return "channel_" + (channelIndex + 1);
    }

    private static void validatePlanes(ImagePlus template, Object[] planes) {
        if (template == null) {
            throw new IllegalArgumentException("Template image is required.");
        }
        if (planes == null || planes.length != planeCount(template)) {
            throw new IllegalArgumentException("Plane count does not match the template image.");
        }
    }

    private static void configureSingleChannelLike(ImagePlus template, ImagePlus out) {
        int slices = Math.max(1, template.getNSlices());
        int frames = Math.max(1, template.getNFrames());
        out.setDimensions(1, slices, frames);
        if (slices > 1 || frames > 1) {
            out.setOpenAsHyperStack(true);
        }
        Calibration calibration = template.getCalibration();
        if (calibration != null) {
            out.setCalibration(calibration.copy());
        }
    }

    private static int stackIndex(ImagePlus image, int channelIndex, int planeIndex) {
        int planes = planeCount(image);
        if (planeIndex < 0 || planeIndex >= planes) {
            throw new IllegalArgumentException("Plane index is outside the image: " + planeIndex);
        }
        int slices = Math.max(1, image.getNSlices());
        int z = (planeIndex % slices) + 1;
        int t = (planeIndex / slices) + 1;
        return image.getStackIndex(channelIndex + 1, z, t);
    }

    private static int intensityAtRank(Histogram histogram, long rank) {
        long cumulative = 0L;
        int[] counts = histogram.getCounts();
        for (int intensity = 0; intensity < counts.length; intensity++) {
            cumulative += counts[intensity];
            if (cumulative > rank) {
                return intensity;
            }
        }
        return MAX_16BIT_VALUE;
    }

    private static double clampTo16Bit(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0.0;
        if (value < 0.0) return 0.0;
        if (value > MAX_16BIT_VALUE) return MAX_16BIT_VALUE;
        return value;
    }
}
