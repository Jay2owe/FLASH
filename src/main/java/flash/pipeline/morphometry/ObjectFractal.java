package flash.pipeline.morphometry;

import java.util.ArrayList;
import java.util.List;

/**
 * Box-counting fractal dimension and gliding-box lacunarity for one 2D object mask.
 */
public final class ObjectFractal {
    public static final int[] DEFAULT_RADII_PX = {1, 2, 4, 8, 16, 32, 64};

    private ObjectFractal() {
    }

    public static final class Result {
        public final double fractalDim;
        /**
         * Coefficient of determination for the box-counting fit. With few radii this is a
         * coarse quality flag; downstream code should also inspect {@link #reliable}.
         */
        public final double fractalDim_R2;
        public final double lacunarityMean;
        public final double lacunaritySpread;
        public final boolean valid;
        public final boolean reliable;

        private Result(double fractalDim,
                       double fractalDim_R2,
                       double lacunarityMean,
                       double lacunaritySpread,
                       boolean valid,
                       boolean reliable) {
            this.fractalDim = fractalDim;
            this.fractalDim_R2 = fractalDim_R2;
            this.lacunarityMean = lacunarityMean;
            this.lacunaritySpread = lacunaritySpread;
            this.valid = valid;
            this.reliable = reliable;
        }
    }

    public static Result compute(ObjectPatch patch) {
        return compute(patch, DEFAULT_RADII_PX);
    }

    public static Result compute(ObjectPatch patch, int[] radiiPx) {
        if (patch == null) {
            throw new IllegalArgumentException("patch must not be null");
        }
        if (radiiPx == null || radiiPx.length == 0) {
            throw new IllegalArgumentException("radiiPx must not be empty");
        }

        Bounds bounds = findBounds(patch);
        if (!bounds.present || bounds.width() < 8 || bounds.height() < 8) {
            return invalid();
        }

        int pad = (int) Math.ceil(Math.max(bounds.width(), bounds.height()) / 4.0);
        int paddedWidth = bounds.width() + 2 * pad;
        int paddedHeight = bounds.height() + 2 * pad;
        byte[] binary = new byte[paddedWidth * paddedHeight];
        for (int y = bounds.minY; y <= bounds.maxY; y++) {
            for (int x = bounds.minX; x <= bounds.maxX; x++) {
                int source = y * patch.width + x;
                if (patch.mask[source] == 0) continue;
                int targetX = x - bounds.minX + pad;
                int targetY = y - bounds.minY + pad;
                binary[targetY * paddedWidth + targetX] = 1;
            }
        }

        List<Double> logInvR = new ArrayList<Double>();
        List<Double> logN = new ArrayList<Double>();
        List<Double> lacunarities = new ArrayList<Double>();
        int originX = pad;
        int originY = pad;
        int maxObjectSide = Math.max(bounds.width(), bounds.height());
        int minPaddedSide = Math.min(paddedWidth, paddedHeight);

        for (int i = 0; i < radiiPx.length; i++) {
            int r = radiiPx[i];
            if (r <= 0) continue;
            if (r <= maxObjectSide) {
                int count = countOccupiedBoxes(binary, paddedWidth, paddedHeight, r, originX, originY);
                if (count > 0) {
                    logInvR.add(Double.valueOf(Math.log(1.0 / r)));
                    logN.add(Double.valueOf(Math.log(count)));
                }
            }
            if (r <= minPaddedSide) {
                double lacunarity = lacunarity(binary, paddedWidth, paddedHeight, r);
                if (isFinite(lacunarity)) {
                    lacunarities.add(Double.valueOf(lacunarity));
                }
            }
        }

        if (logInvR.size() < 3) {
            return invalid();
        }

        Regression regression = regress(logInvR, logN);
        double lacMean = mean(lacunarities);
        double lacSpread = sampleSpread(lacunarities, lacMean);
        boolean valid = isFinite(regression.slope) && isFinite(regression.r2);
        boolean reliable = valid && regression.r2 >= 0.9;
        return new Result(regression.slope, regression.r2, lacMean, lacSpread, valid, reliable);
    }

    private static int countOccupiedBoxes(byte[] binary, int width, int height, int r,
                                          int originX, int originY) {
        int count = 0;
        int startX = Math.floorMod(originX, r);
        int startY = Math.floorMod(originY, r);
        for (int y = startY; y < height; y += r) {
            for (int x = startX; x < width; x += r) {
                if (boxHasForeground(binary, width, height, x, y, r)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean boxHasForeground(byte[] binary, int width, int height,
                                            int x0, int y0, int r) {
        int yMax = Math.min(height, y0 + r);
        int xMax = Math.min(width, x0 + r);
        for (int y = y0; y < yMax; y++) {
            for (int x = x0; x < xMax; x++) {
                if (binary[y * width + x] != 0) return true;
            }
        }
        return false;
    }

    private static double lacunarity(byte[] binary, int width, int height, int r) {
        int[] integral = integral(binary, width, height);
        int n = 0;
        double sum = 0.0;
        double sumSq = 0.0;
        for (int y = 0; y <= height - r; y++) {
            for (int x = 0; x <= width - r; x++) {
                int mass = rectSum(integral, width + 1, x, y, x + r, y + r);
                sum += mass;
                sumSq += mass * mass;
                n++;
            }
        }
        if (n == 0) return Double.NaN;
        double mean = sum / n;
        if (mean <= 0.0) return Double.NaN;
        double variance = Math.max(0.0, sumSq / n - mean * mean);
        return variance / (mean * mean) + 1.0;
    }

    private static int[] integral(byte[] binary, int width, int height) {
        int[] integral = new int[(width + 1) * (height + 1)];
        int stride = width + 1;
        for (int y = 1; y <= height; y++) {
            int row = 0;
            for (int x = 1; x <= width; x++) {
                row += binary[(y - 1) * width + (x - 1)] != 0 ? 1 : 0;
                integral[y * stride + x] = integral[(y - 1) * stride + x] + row;
            }
        }
        return integral;
    }

    private static int rectSum(int[] integral, int stride, int x0, int y0, int x1, int y1) {
        return integral[y1 * stride + x1]
                - integral[y0 * stride + x1]
                - integral[y1 * stride + x0]
                + integral[y0 * stride + x0];
    }

    private static Regression regress(List<Double> x, List<Double> y) {
        int n = x.size();
        double meanX = mean(x);
        double meanY = mean(y);
        double sxx = 0.0;
        double sxy = 0.0;
        double syy = 0.0;
        for (int i = 0; i < n; i++) {
            double dx = x.get(i).doubleValue() - meanX;
            double dy = y.get(i).doubleValue() - meanY;
            sxx += dx * dx;
            sxy += dx * dy;
            syy += dy * dy;
        }
        if (sxx <= 0.0 || syy <= 0.0) {
            return new Regression(Double.NaN, Double.NaN);
        }
        double slope = sxy / sxx;
        double sse = 0.0;
        double intercept = meanY - slope * meanX;
        for (int i = 0; i < n; i++) {
            double predicted = intercept + slope * x.get(i).doubleValue();
            double residual = y.get(i).doubleValue() - predicted;
            sse += residual * residual;
        }
        double r2 = 1.0 - sse / syy;
        if (r2 < 0.0 && r2 > -1.0e-12) r2 = 0.0;
        if (r2 > 1.0 && r2 < 1.0 + 1.0e-12) r2 = 1.0;
        return new Regression(slope, r2);
    }

    private static Bounds findBounds(ObjectPatch patch) {
        int minX = patch.width;
        int minY = patch.height;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < patch.height; y++) {
            for (int x = 0; x < patch.width; x++) {
                if (patch.mask[y * patch.width + x] == 0) continue;
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
            }
        }
        return new Bounds(minX, minY, maxX, maxY, maxX >= minX && maxY >= minY);
    }

    private static double mean(List<Double> values) {
        if (values == null || values.isEmpty()) return Double.NaN;
        double sum = 0.0;
        for (int i = 0; i < values.size(); i++) {
            sum += values.get(i).doubleValue();
        }
        return sum / values.size();
    }

    private static double sampleSpread(List<Double> values, double mean) {
        if (values == null || values.isEmpty() || !isFinite(mean)) return Double.NaN;
        double sumSq = 0.0;
        for (int i = 0; i < values.size(); i++) {
            double delta = values.get(i).doubleValue() - mean;
            sumSq += delta * delta;
        }
        return Math.sqrt(sumSq / values.size());
    }

    private static Result invalid() {
        return new Result(Double.NaN, Double.NaN, Double.NaN, Double.NaN, false, false);
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static final class Bounds {
        final int minX;
        final int minY;
        final int maxX;
        final int maxY;
        final boolean present;

        private Bounds(int minX, int minY, int maxX, int maxY, boolean present) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
            this.present = present;
        }

        int width() {
            return maxX - minX + 1;
        }

        int height() {
            return maxY - minY + 1;
        }
    }

    private static final class Regression {
        final double slope;
        final double r2;

        private Regression(double slope, double r2) {
            this.slope = slope;
            this.r2 = r2;
        }
    }
}
