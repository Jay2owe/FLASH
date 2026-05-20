package flash.pipeline.morphometry;

/**
 * Mask-restricted 2D Grey-Level Co-occurrence Matrix features for one object.
 */
public final class ObjectTextureGLCM {
    public static final int DEFAULT_LEVELS = 32;
    public static final int DEFAULT_DISTANCE = 1;

    private static final double LOG_2 = Math.log(2.0);
    private static final int[][] OFFSETS = {
            {1, 0},
            {1, -1},
            {0, -1},
            {-1, -1}
    };

    private ObjectTextureGLCM() {
    }

    public static final class Result {
        public final double contrast;
        public final double asm;
        public final double correlation;
        public final double entropy;
        public final double homogeneity;
        public final boolean valid;
        public final boolean reliable;
        public final int coOccurrencePairs;

        private Result(double contrast,
                       double asm,
                       double correlation,
                       double entropy,
                       double homogeneity,
                       boolean valid,
                       boolean reliable,
                       int coOccurrencePairs) {
            this.contrast = contrast;
            this.asm = asm;
            this.correlation = correlation;
            this.entropy = entropy;
            this.homogeneity = homogeneity;
            this.valid = valid;
            this.reliable = reliable;
            this.coOccurrencePairs = coOccurrencePairs;
        }
    }

    public static Result compute(ObjectPatch patch) {
        return compute(patch, DEFAULT_LEVELS, DEFAULT_DISTANCE);
    }

    public static Result compute(ObjectPatch patch, int levels, int distance) {
        if (patch == null) {
            throw new IllegalArgumentException("patch must not be null");
        }
        if (levels < 2) {
            throw new IllegalArgumentException("levels must be at least 2");
        }
        if (distance < 1) {
            throw new IllegalArgumentException("distance must be positive");
        }

        int[] quantized = new int[patch.intensity.length];
        for (int i = 0; i < quantized.length; i++) quantized[i] = -1;

        Range range = intensityRange(patch);
        if (range.count == 0) {
            return invalid(0);
        }

        boolean[] occupied = new boolean[levels];
        int occupiedLevels = 0;
        double span = range.max - range.min;
        for (int i = 0; i < patch.intensity.length; i++) {
            if (patch.mask[i] == 0 || !isFinite(patch.intensity[i])) continue;
            int q;
            if (span <= 0.0) {
                q = 0;
            } else {
                double unit = (patch.intensity[i] - range.min) / span;
                q = (int) Math.floor(unit * levels);
                if (q < 0) q = 0;
                if (q >= levels) q = levels - 1;
            }
            quantized[i] = q;
            if (!occupied[q]) {
                occupied[q] = true;
                occupiedLevels++;
            }
        }

        double[][] matrix = new double[levels][levels];
        int pairs = buildMatrix(patch, quantized, matrix, distance);
        if (pairs < 16) {
            return invalid(pairs);
        }

        double total = 2.0 * pairs;
        double[] row = new double[levels];
        double[] col = new double[levels];
        for (int i = 0; i < levels; i++) {
            for (int j = 0; j < levels; j++) {
                double p = matrix[i][j] / total;
                row[i] += p;
                col[j] += p;
            }
        }

        double meanI = 0.0;
        double meanJ = 0.0;
        for (int i = 0; i < levels; i++) {
            meanI += i * row[i];
            meanJ += i * col[i];
        }

        double varI = 0.0;
        double varJ = 0.0;
        for (int i = 0; i < levels; i++) {
            double di = i - meanI;
            double dj = i - meanJ;
            varI += di * di * row[i];
            varJ += dj * dj * col[i];
        }

        double contrast = 0.0;
        double asm = 0.0;
        double entropy = 0.0;
        double homogeneity = 0.0;
        double corrNumerator = 0.0;
        for (int i = 0; i < levels; i++) {
            for (int j = 0; j < levels; j++) {
                double p = matrix[i][j] / total;
                if (p <= 0.0) continue;
                int delta = i - j;
                contrast += delta * delta * p;
                asm += p * p;
                entropy -= p * (Math.log(p) / LOG_2);
                homogeneity += p / (1.0 + delta * delta);
                corrNumerator += (i - meanI) * (j - meanJ) * p;
            }
        }

        double sigma = Math.sqrt(varI * varJ);
        boolean hasCorrelation = sigma > 0.0 && isFinite(sigma);
        double correlation = hasCorrelation ? corrNumerator / sigma : Double.NaN;
        boolean valid = true;
        boolean reliable = occupiedLevels >= 2 && hasCorrelation;
        return new Result(contrast, asm, correlation, entropy, homogeneity,
                valid, reliable, pairs);
    }

    private static int buildMatrix(ObjectPatch patch, int[] quantized, double[][] matrix, int distance) {
        int pairs = 0;
        for (int o = 0; o < OFFSETS.length; o++) {
            int dx = OFFSETS[o][0] * distance;
            int dy = OFFSETS[o][1] * distance;
            for (int y = 0; y < patch.height; y++) {
                int yy = y + dy;
                if (yy < 0 || yy >= patch.height) continue;
                for (int x = 0; x < patch.width; x++) {
                    int xx = x + dx;
                    if (xx < 0 || xx >= patch.width) continue;
                    int aIndex = y * patch.width + x;
                    int bIndex = yy * patch.width + xx;
                    int a = quantized[aIndex];
                    int b = quantized[bIndex];
                    if (a < 0 || b < 0) continue;
                    matrix[a][b] += 1.0;
                    matrix[b][a] += 1.0;
                    pairs++;
                }
            }
        }
        return pairs;
    }

    private static Result invalid(int pairs) {
        return new Result(Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, false, false, pairs);
    }

    private static Range intensityRange(ObjectPatch patch) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        int count = 0;
        for (int i = 0; i < patch.intensity.length; i++) {
            if (patch.mask[i] == 0) continue;
            float value = patch.intensity[i];
            if (!isFinite(value)) continue;
            if (value < min) min = value;
            if (value > max) max = value;
            count++;
        }
        return new Range(min, max, count);
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static final class Range {
        final double min;
        final double max;
        final int count;

        private Range(double min, double max, int count) {
            this.min = min;
            this.max = max;
            this.count = count;
        }
    }
}
