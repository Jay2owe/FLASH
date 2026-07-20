package flash.pipeline.intelligence;

import flash.pipeline.zslice.ZSliceRange;

import ij.ImagePlus;
import ij.ImageStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Suggests a Z-range that skips stack-end slices whose brightness looks like
 * the stack noise floor.
 */
public final class EmptySliceSuggester {

    public static final int MIN_SLICES = 6;

    private static final int MAX_SAMPLES_PER_PLANE = 4096;
    private static final double NOISE_TOLERANCE_FRACTION = 0.05;

    private EmptySliceSuggester() {
    }

    public static Suggestion suggest(ImagePlus image) {
        if (image == null) return null;
        ImageStack stack = image.getStack();
        int zCount = Math.max(1, image.getNSlices());
        if (stack == null || zCount < MIN_SLICES) return null;

        int channels = Math.max(1, image.getNChannels());
        int frames = Math.max(1, image.getNFrames());
        double[] zStatistics = new double[zCount];
        for (int z = 1; z <= zCount; z++) {
            double zStatistic = Double.NEGATIVE_INFINITY;
            boolean foundPlane = false;
            for (int t = 1; t <= frames; t++) {
                for (int c = 1; c <= channels; c++) {
                    int stackIndex = image.getStackIndex(c, z, t);
                    if (stackIndex < 1 || stackIndex > stack.getSize()) continue;
                    double planeStatistic = sampledMean(stack.getPixels(stackIndex));
                    if (Double.isNaN(planeStatistic)) continue;
                    zStatistic = Math.max(zStatistic, planeStatistic);
                    foundPlane = true;
                }
            }
            if (!foundPlane) return null;
            zStatistics[z - 1] = zStatistic;
        }
        return suggestFromStatistics(zStatistics);
    }

    public static Suggestion suggest(ImageStack stack) {
        if (stack == null || stack.getSize() < MIN_SLICES) return null;

        double[] zStatistics = new double[stack.getSize()];
        for (int i = 0; i < stack.getSize(); i++) {
            double statistic = sampledMean(stack.getPixels(i + 1));
            if (Double.isNaN(statistic)) return null;
            zStatistics[i] = statistic;
        }
        return suggestFromStatistics(zStatistics);
    }

    private static Suggestion suggestFromStatistics(double[] zStatistics) {
        if (zStatistics == null || zStatistics.length < MIN_SLICES) return null;

        int totalSlices = zStatistics.length;
        ZSliceRange fullRange = ZSliceRange.fullStack(totalSlices);
        double darkest = Double.POSITIVE_INFINITY;
        double brightest = Double.NEGATIVE_INFINITY;
        for (double statistic : zStatistics) {
            if (Double.isNaN(statistic) || Double.isInfinite(statistic)) return null;
            darkest = Math.min(darkest, statistic);
            brightest = Math.max(brightest, statistic);
        }

        double spread = brightest - darkest;
        if (!(spread > 0.0)) {
            return new Suggestion(fullRange, Collections.<Integer>emptyList(), totalSlices);
        }

        double noiseCeiling = darkest + spread * NOISE_TOLERANCE_FRACTION;
        boolean[] pureNoise = new boolean[totalSlices];
        List<Integer> pureNoiseSlices = new ArrayList<Integer>();
        int firstSignal = -1;
        int lastSignal = -1;
        for (int i = 0; i < totalSlices; i++) {
            pureNoise[i] = zStatistics[i] <= noiseCeiling;
            if (pureNoise[i]) {
                pureNoiseSlices.add(Integer.valueOf(i + 1));
            } else {
                if (firstSignal < 0) firstSignal = i + 1;
                lastSignal = i + 1;
            }
        }

        if (firstSignal < 0) {
            return new Suggestion(fullRange, Collections.<Integer>emptyList(), totalSlices);
        }
        return new Suggestion(
                new ZSliceRange(firstSignal, lastSignal),
                pureNoiseSlices,
                totalSlices);
    }

    private static double sampledMean(Object pixels) {
        if (pixels instanceof byte[]) return sampledMean((byte[]) pixels);
        if (pixels instanceof short[]) return sampledMean((short[]) pixels);
        if (pixels instanceof float[]) return sampledMean((float[]) pixels);
        if (pixels instanceof int[]) return sampledMean((int[]) pixels);
        return Double.NaN;
    }

    private static double sampledMean(byte[] pixels) {
        if (pixels == null || pixels.length == 0) return Double.NaN;
        int stride = sampleStride(pixels.length);
        double sum = 0.0;
        int count = 0;
        // WHY: a bounded stride-sampled mean separates empty from signal slices without scanning every pixel.
        for (int i = 0; i < pixels.length; i += stride) {
            sum += pixels[i] & 0xff;
            count++;
        }
        return count == 0 ? Double.NaN : sum / count;
    }

    private static double sampledMean(short[] pixels) {
        if (pixels == null || pixels.length == 0) return Double.NaN;
        int stride = sampleStride(pixels.length);
        double sum = 0.0;
        int count = 0;
        // WHY: a bounded stride-sampled mean separates empty from signal slices without scanning every pixel.
        for (int i = 0; i < pixels.length; i += stride) {
            sum += pixels[i] & 0xffff;
            count++;
        }
        return count == 0 ? Double.NaN : sum / count;
    }

    private static double sampledMean(float[] pixels) {
        if (pixels == null || pixels.length == 0) return Double.NaN;
        int stride = sampleStride(pixels.length);
        double sum = 0.0;
        int count = 0;
        // WHY: a bounded stride-sampled mean separates empty from signal slices without scanning every pixel.
        for (int i = 0; i < pixels.length; i += stride) {
            float value = pixels[i];
            if (Float.isNaN(value) || Float.isInfinite(value)) continue;
            sum += value;
            count++;
        }
        return count == 0 ? Double.NaN : sum / count;
    }

    private static double sampledMean(int[] pixels) {
        if (pixels == null || pixels.length == 0) return Double.NaN;
        int stride = sampleStride(pixels.length);
        double sum = 0.0;
        int count = 0;
        // WHY: a bounded stride-sampled mean separates empty from signal slices without scanning every pixel.
        for (int i = 0; i < pixels.length; i += stride) {
            int value = pixels[i];
            int red = (value >> 16) & 0xff;
            int green = (value >> 8) & 0xff;
            int blue = value & 0xff;
            sum += (red + green + blue) / 3.0;
            count++;
        }
        return count == 0 ? Double.NaN : sum / count;
    }

    private static int sampleStride(int pixelCount) {
        return Math.max(1, pixelCount / MAX_SAMPLES_PER_PLANE);
    }

    public static final class Suggestion {
        public final ZSliceRange range;
        public final List<Integer> pureNoiseSlices;
        public final int totalSlices;

        Suggestion(ZSliceRange range, List<Integer> pureNoiseSlices, int totalSlices) {
            this.range = range;
            this.pureNoiseSlices = Collections.unmodifiableList(
                    new ArrayList<Integer>(pureNoiseSlices));
            this.totalSlices = totalSlices;
        }

        public boolean trimsSlices() {
            return range != null && !range.coversFullStack(totalSlices);
        }

        public String tooltip() {
            if (!trimsSlices()) return "";
            List<Integer> trimmed = trimmedNoiseSlices();
            if (trimmed.isEmpty()) return "";
            return formatSliceList(trimmed) + " look like pure noise - override if needed.";
        }

        private List<Integer> trimmedNoiseSlices() {
            List<Integer> trimmed = new ArrayList<Integer>();
            for (Integer slice : pureNoiseSlices) {
                if (slice == null) continue;
                int value = slice.intValue();
                if (value < range.startSlice || value > range.endSlice) {
                    trimmed.add(slice);
                }
            }
            return trimmed;
        }

        private static String formatSliceList(List<Integer> slices) {
            List<String> groups = new ArrayList<String>();
            int i = 0;
            while (i < slices.size()) {
                int start = slices.get(i).intValue();
                int end = start;
                while (i + 1 < slices.size()
                        && slices.get(i + 1).intValue() == end + 1) {
                    i++;
                    end = slices.get(i).intValue();
                }
                groups.add(start == end ? String.valueOf(start) : start + "-" + end);
                i++;
            }
            String prefix = slices.size() == 1 ? "Slice " : "Slices ";
            return prefix + joinGroups(groups);
        }

        private static String joinGroups(List<String> groups) {
            if (groups.isEmpty()) return "";
            if (groups.size() == 1) return groups.get(0);
            if (groups.size() == 2) return groups.get(0) + " and " + groups.get(1);

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < groups.size(); i++) {
                if (i > 0) {
                    sb.append(i == groups.size() - 1 ? " and " : ", ");
                }
                sb.append(groups.get(i));
            }
            return sb.toString();
        }
    }
}
