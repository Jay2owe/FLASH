package flash.pipeline.intelligence;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Deterministic Pearson, Manders, and Costes colocalization metrics for paired
 * single-channel image volumes.
 */
public final class ColocalizationMetrics {

    private static final int RANDOMIZATION_ITERATIONS = 100;
    private static final int RANDOMIZATION_BLOCK_WIDTH = 5;
    private static final int RANDOMIZATION_BLOCK_HEIGHT = 5;
    private static final int RANDOMIZATION_BLOCK_DEPTH = 1;
    private static final long RANDOMIZATION_VOXEL_LIMIT = 50_000_000L;
    private static final long RANDOMIZATION_SEED = 20260422L;
    private static final double COSTES_R_TOLERANCE = 0.01;
    private static final int COSTES_MIN_SEARCH_ITERATIONS = 3;
    private static final int COSTES_MAX_SEARCH_ITERATIONS = 32;

    private ColocalizationMetrics() {}

    public static final class Result {
        public final double pearson;
        public final double mandersM1;
        public final double mandersM2;
        public final double costesTa;
        public final double costesTb;
        public final double pearsonThresholded;
        public final double costesP;
        public final boolean randomizationSkipped;
        public final String note;

        private Result(double pearson,
                       double mandersM1,
                       double mandersM2,
                       double costesTa,
                       double costesTb,
                       double pearsonThresholded,
                       double costesP,
                       boolean randomizationSkipped,
                       String note) {
            this.pearson = pearson;
            this.mandersM1 = mandersM1;
            this.mandersM2 = mandersM2;
            this.costesTa = costesTa;
            this.costesTb = costesTb;
            this.pearsonThresholded = pearsonThresholded;
            this.costesP = costesP;
            this.randomizationSkipped = randomizationSkipped;
            this.note = note == null ? "" : note;
        }
    }

    public static Result compute(ImagePlus channelA, ImagePlus channelB) {
        if (channelA == null || channelB == null) {
            return emptyResult("missing channel image");
        }
        if (channelA.getWidth() != channelB.getWidth()
                || channelA.getHeight() != channelB.getHeight()
                || channelA.getStackSize() != channelB.getStackSize()) {
            return emptyResult("channel dimensions differ");
        }

        int width = channelA.getWidth();
        int height = channelA.getHeight();
        int depth = Math.max(1, channelA.getStackSize());
        long count = (long) width * (long) height * (long) depth;
        if (count > Integer.MAX_VALUE) {
            return emptyResult("volume too large for in-memory coloc metrics");
        }

        double[] a = extract(channelA, (int) count);
        double[] b = extract(channelB, (int) count);
        return compute(a, b, width, height, depth);
    }

    public static Result compute(double[] channelA, double[] channelB,
                                 int width, int height, int depth) {
        if (channelA == null || channelB == null || channelA.length != channelB.length) {
            throw new IllegalArgumentException("Channel arrays must be non-null and equal length.");
        }
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw new IllegalArgumentException("Image dimensions must be positive.");
        }
        long expected = (long) width * (long) height * (long) depth;
        if (expected != channelA.length) {
            throw new IllegalArgumentException("Image dimensions do not match channel array length.");
        }
        if (channelA.length == 0) {
            return emptyResult("empty channel arrays");
        }

        BasicStats stats = scan(channelA, channelB);
        Thresholds thresholds = costesThresholds(channelA, channelB, stats);
        double mandersM1 = mandersM1(channelA, channelB, thresholds.tb);
        double mandersM2 = mandersM2(channelA, channelB, thresholds.ta);
        double pearsonThresholded = pearsonAbove(channelA, channelB, thresholds.ta, thresholds.tb);
        Randomization randomization = costesRandomizationPValue(
                channelA, channelB, width, height, depth, stats.pearson);

        return new Result(
                stats.pearson,
                mandersM1,
                mandersM2,
                thresholds.ta,
                thresholds.tb,
                pearsonThresholded,
                randomization.pValue,
                randomization.skipped,
                randomization.note);
    }

    private static Result emptyResult(String note) {
        return new Result(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, false, note);
    }

    private static double[] extract(ImagePlus image, int count) {
        double[] values = new double[count];
        ImageStack stack = image.getStack();
        int width = image.getWidth();
        int height = image.getHeight();
        int planeSize = width * height;
        int depth = Math.max(1, image.getStackSize());
        int offset = 0;
        for (int z = 1; z <= depth; z++) {
            ImageProcessor ip = stack.getProcessor(z);
            for (int p = 0; p < planeSize; p++) {
                values[offset + p] = ip.getf(p);
            }
            offset += planeSize;
        }
        return values;
    }

    private static BasicStats scan(double[] a, double[] b) {
        PearsonAccumulator acc = new PearsonAccumulator();
        double minA = Double.POSITIVE_INFINITY;
        double maxA = Double.NEGATIVE_INFINITY;
        double minB = Double.POSITIVE_INFINITY;
        double maxB = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < a.length; i++) {
            double av = a[i];
            double bv = b[i];
            acc.add(av, bv);
            if (av < minA) minA = av;
            if (av > maxA) maxA = av;
            if (bv < minB) minB = bv;
            if (bv > maxB) maxB = bv;
        }
        return new BasicStats(minA, maxA, minB, maxB, acc);
    }

    private static Thresholds costesThresholds(double[] a, double[] b, BasicStats stats) {
        if (!aboveTolerance(stats.pearson) || stats.varA <= 0.0 || stats.count < 3) {
            return new Thresholds(stats.minA, stats.minB);
        }

        double slope = stats.covariance / stats.varA;
        double intercept = stats.meanB - slope * stats.meanA;
        if (Double.isNaN(slope) || Double.isInfinite(slope)
                || Double.isNaN(intercept) || Double.isInfinite(intercept)) {
            return new Thresholds(stats.minA, stats.minB);
        }

        double low = stats.minA;
        double high = stats.maxA;
        if (high <= low) {
            return new Thresholds(stats.minA, stats.minB);
        }

        double lowTb = clamp(slope * low + intercept, stats.minB, stats.maxB);
        double highTb = clamp(slope * high + intercept, stats.minB, stats.maxB);
        double lowR = pearsonBelow(a, b, low, lowTb);
        double highR = pearsonBelow(a, b, high, highTb);
        if (aboveTolerance(lowR) || !aboveTolerance(highR)) {
            return new Thresholds(stats.minA, stats.minB);
        }

        double below = low;
        double above = high;
        boolean foundBelowTolerance = !aboveTolerance(lowR);
        for (int i = 0; i < COSTES_MAX_SEARCH_ITERATIONS; i++) {
            double mid = (below + above) / 2.0;
            double midTb = clamp(slope * mid + intercept, stats.minB, stats.maxB);
            double midR = pearsonBelow(a, b, mid, midTb);
            if (aboveTolerance(midR)) {
                above = mid;
            } else {
                below = mid;
                foundBelowTolerance = true;
            }
            if (i + 1 >= COSTES_MIN_SEARCH_ITERATIONS
                    && Math.abs(above - below) <= 1.0e-6) {
                break;
            }
        }

        if (!foundBelowTolerance) {
            return new Thresholds(stats.minA, stats.minB);
        }
        double ta = above;
        double tb = clamp(slope * ta + intercept, stats.minB, stats.maxB);
        return new Thresholds(ta, tb);
    }

    private static boolean aboveTolerance(double r) {
        return !Double.isNaN(r) && !Double.isInfinite(r) && r > COSTES_R_TOLERANCE;
    }

    private static double mandersM1(double[] a, double[] b, double tb) {
        double total = 0.0;
        double coloc = 0.0;
        for (int i = 0; i < a.length; i++) {
            total += a[i];
            if (b[i] > tb) coloc += a[i];
        }
        return total == 0.0 ? Double.NaN : coloc / total;
    }

    private static double mandersM2(double[] a, double[] b, double ta) {
        double total = 0.0;
        double coloc = 0.0;
        for (int i = 0; i < b.length; i++) {
            total += b[i];
            if (a[i] > ta) coloc += b[i];
        }
        return total == 0.0 ? Double.NaN : coloc / total;
    }

    private static double pearsonBelow(double[] a, double[] b, double ta, double tb) {
        PearsonAccumulator acc = new PearsonAccumulator();
        for (int i = 0; i < a.length; i++) {
            if (a[i] <= ta && b[i] <= tb) {
                acc.add(a[i], b[i]);
            }
        }
        return acc.pearson();
    }

    private static double pearsonAbove(double[] a, double[] b, double ta, double tb) {
        PearsonAccumulator acc = new PearsonAccumulator();
        for (int i = 0; i < a.length; i++) {
            if (a[i] > ta && b[i] > tb) {
                acc.add(a[i], b[i]);
            }
        }
        return acc.pearson();
    }

    private static Randomization costesRandomizationPValue(double[] a, double[] b,
                                                           int width, int height, int depth,
                                                           double observedPearson) {
        if (a.length > RANDOMIZATION_VOXEL_LIMIT) {
            return new Randomization(Double.NaN, true,
                    "coloc-randomization-skipped: volume > 50M voxels");
        }
        if (Double.isNaN(observedPearson) || Double.isInfinite(observedPearson)) {
            return new Randomization(Double.NaN, false, "");
        }

        List<List<Block>> blockGroups = buildBlockGroups(width, height, depth);
        Random random = new Random(RANDOMIZATION_SEED);
        int greaterOrEqual = 0;
        for (int i = 0; i < RANDOMIZATION_ITERATIONS; i++) {
            double randomizedPearson = shuffledBlockPearson(a, b, width, height, blockGroups, random);
            if (!Double.isNaN(randomizedPearson) && randomizedPearson >= observedPearson) {
                greaterOrEqual++;
            }
        }
        double p = (greaterOrEqual + 1.0) / (RANDOMIZATION_ITERATIONS + 1.0);
        return new Randomization(p, false, "");
    }

    private static List<List<Block>> buildBlockGroups(int width, int height, int depth) {
        Map<String, List<Block>> byShape = new LinkedHashMap<String, List<Block>>();
        for (int z = 0; z < depth; z += RANDOMIZATION_BLOCK_DEPTH) {
            int blockDepth = Math.min(RANDOMIZATION_BLOCK_DEPTH, depth - z);
            for (int y = 0; y < height; y += RANDOMIZATION_BLOCK_HEIGHT) {
                int blockHeight = Math.min(RANDOMIZATION_BLOCK_HEIGHT, height - y);
                for (int x = 0; x < width; x += RANDOMIZATION_BLOCK_WIDTH) {
                    int blockWidth = Math.min(RANDOMIZATION_BLOCK_WIDTH, width - x);
                    String key = blockWidth + "x" + blockHeight + "x" + blockDepth;
                    List<Block> blocks = byShape.get(key);
                    if (blocks == null) {
                        blocks = new ArrayList<Block>();
                        byShape.put(key, blocks);
                    }
                    blocks.add(new Block(x, y, z, blockWidth, blockHeight, blockDepth));
                }
            }
        }
        return new ArrayList<List<Block>>(byShape.values());
    }

    private static double shuffledBlockPearson(double[] a, double[] b,
                                               int width, int height,
                                               List<List<Block>> blockGroups,
                                               Random random) {
        PearsonAccumulator acc = new PearsonAccumulator();
        for (List<Block> blocks : blockGroups) {
            int[] order = shuffledOrder(blocks.size(), random);
            for (int destIndex = 0; destIndex < blocks.size(); destIndex++) {
                Block dest = blocks.get(destIndex);
                Block src = blocks.get(order[destIndex]);
                addShuffledBlock(acc, a, b, width, height, src, dest);
            }
        }
        return acc.pearson();
    }

    private static int[] shuffledOrder(int size, Random random) {
        int[] order = new int[size];
        for (int i = 0; i < size; i++) {
            order[i] = i;
        }
        for (int i = size - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int tmp = order[i];
            order[i] = order[j];
            order[j] = tmp;
        }
        return order;
    }

    private static void addShuffledBlock(PearsonAccumulator acc,
                                         double[] a, double[] b,
                                         int width, int height,
                                         Block src, Block dest) {
        for (int dz = 0; dz < dest.depth; dz++) {
            for (int dy = 0; dy < dest.height; dy++) {
                for (int dx = 0; dx < dest.width; dx++) {
                    int srcIndex = index(width, height, src.x + dx, src.y + dy, src.z + dz);
                    int destIndex = index(width, height, dest.x + dx, dest.y + dy, dest.z + dz);
                    acc.add(a[srcIndex], b[destIndex]);
                }
            }
        }
    }

    private static int index(int width, int height, int x, int y, int z) {
        return (z * height + y) * width + x;
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private static final class BasicStats {
        final long count;
        final double minA;
        final double maxA;
        final double minB;
        final double maxB;
        final double meanA;
        final double meanB;
        final double varA;
        final double covariance;
        final double pearson;

        BasicStats(double minA, double maxA, double minB, double maxB,
                   PearsonAccumulator acc) {
            this.count = acc.count;
            this.minA = minA;
            this.maxA = maxA;
            this.minB = minB;
            this.maxB = maxB;
            this.meanA = acc.meanA();
            this.meanB = acc.meanB();
            this.varA = acc.varA();
            this.covariance = acc.covariance();
            this.pearson = acc.pearson();
        }
    }

    private static final class Thresholds {
        final double ta;
        final double tb;

        Thresholds(double ta, double tb) {
            this.ta = ta;
            this.tb = tb;
        }
    }

    private static final class Randomization {
        final double pValue;
        final boolean skipped;
        final String note;

        Randomization(double pValue, boolean skipped, String note) {
            this.pValue = pValue;
            this.skipped = skipped;
            this.note = note == null ? "" : note;
        }
    }

    private static final class Block {
        final int x;
        final int y;
        final int z;
        final int width;
        final int height;
        final int depth;

        Block(int x, int y, int z, int width, int height, int depth) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.width = width;
            this.height = height;
            this.depth = depth;
        }
    }

    private static final class PearsonAccumulator {
        long count = 0L;
        double sumA = 0.0;
        double sumB = 0.0;
        double sumA2 = 0.0;
        double sumB2 = 0.0;
        double sumAB = 0.0;

        void add(double a, double b) {
            count++;
            sumA += a;
            sumB += b;
            sumA2 += a * a;
            sumB2 += b * b;
            sumAB += a * b;
        }

        double meanA() {
            return count == 0L ? Double.NaN : sumA / (double) count;
        }

        double meanB() {
            return count == 0L ? Double.NaN : sumB / (double) count;
        }

        double varA() {
            if (count < 2L) return Double.NaN;
            return sumA2 - (sumA * sumA) / (double) count;
        }

        double varB() {
            if (count < 2L) return Double.NaN;
            return sumB2 - (sumB * sumB) / (double) count;
        }

        double covariance() {
            if (count < 2L) return Double.NaN;
            return sumAB - (sumA * sumB) / (double) count;
        }

        double pearson() {
            if (count < 2L) return Double.NaN;
            double varA = varA();
            double varB = varB();
            if (varA <= 0.0 || varB <= 0.0 || Double.isNaN(varA) || Double.isNaN(varB)) {
                return Double.NaN;
            }
            double r = covariance() / Math.sqrt(varA * varB);
            if (r > 1.0 && r < 1.0 + 1.0e-12) return 1.0;
            if (r < -1.0 && r > -1.0 - 1.0e-12) return -1.0;
            return r;
        }
    }
}
