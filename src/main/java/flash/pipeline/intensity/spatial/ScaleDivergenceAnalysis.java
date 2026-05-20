package flash.pipeline.intensity.spatial;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import flash.pipeline.runtime.DependencyId;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.process.ImageProcessor;

import java.awt.Rectangle;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Bounded multifractal-style scale divergence summaries for raw intensity.
 */
public final class ScaleDivergenceAnalysis implements IntensitySpatialAnalysis {
    public static final String COLUMN_DELTA_ALPHA = "Intensity_MultifractalDeltaAlpha";
    public static final String COLUMN_ASYMMETRY = "Intensity_MultifractalAsymmetry";

    private static final int MAX_SIDE = 128;
    private static final double EPS = 1e-12;

    @Override
    public IntensitySpatialConfig.AnalysisKey key() {
        return IntensitySpatialConfig.AnalysisKey.SCALEDIVERGENCE;
    }

    @Override
    public AnalysisValidity validity() {
        return AnalysisValidity.RAW_ONLY;
    }

    @Override
    public EnumSet<IntensitySpatialOutputMode> outputModes() {
        return EnumSet.of(IntensitySpatialOutputMode.BASE, IntensitySpatialOutputMode.MIP);
    }

    @Override
    public Set<DependencyId> dependencyIds() {
        return Collections.emptySet();
    }

    @Override
    public List<String> columns(IntensitySpatialConfig config, boolean binarizedPartner) {
        return java.util.Arrays.asList(COLUMN_DELTA_ALPHA, COLUMN_ASYMMETRY);
    }

    @Override
    public int estimatedCost() {
        return 5;
    }

    @Override
    public IntensitySpatialResult measure(IntensitySpatialContext context) {
        Plane plane = Plane.from(context.image(), context.sliceIndex(), context.roi());
        LinkedHashMap<String, Double> values = new LinkedHashMap<String, Double>();
        if (plane.count < 4 || plane.width < 2 || plane.height < 2 || plane.totalMass <= 0.0) {
            context.warn("scale divergence has insufficient positive raw signal; returning NaN.");
            putNan(values);
            return new IntensitySpatialResult(values);
        }

        double alphaNegative = alpha(plane, -2.0);
        double alphaZero = alpha(plane, 0.0);
        double alphaPositive = alpha(plane, 2.0);
        if (Double.isNaN(alphaNegative) || Double.isNaN(alphaZero) || Double.isNaN(alphaPositive)) {
            putNan(values);
            return new IntensitySpatialResult(values);
        }

        double deltaAlpha = Math.abs(alphaNegative - alphaPositive);
        double asymmetry = deltaAlpha <= EPS
                ? 0.0
                : ((alphaNegative + alphaPositive) - 2.0 * alphaZero) / deltaAlpha;
        if (!Double.isNaN(asymmetry) && !Double.isInfinite(asymmetry)) {
            asymmetry = Math.max(-1.0, Math.min(1.0, asymmetry));
        }

        values.put(COLUMN_DELTA_ALPHA, Double.valueOf(deltaAlpha));
        values.put(COLUMN_ASYMMETRY, Double.valueOf(asymmetry));
        return new IntensitySpatialResult(values);
    }

    private static void putNan(LinkedHashMap<String, Double> values) {
        values.put(COLUMN_DELTA_ALPHA, Double.valueOf(Double.NaN));
        values.put(COLUMN_ASYMMETRY, Double.valueOf(Double.NaN));
    }

    private static double alpha(Plane plane, double q) {
        int maxScale = Math.max(1, Math.min(plane.width, plane.height) / 2);
        double[] xs = new double[16];
        double[] ys = new double[16];
        int n = 0;
        for (int scale = 1; scale <= maxScale && n < xs.length; scale *= 2) {
            BoxProbabilities probabilities = BoxProbabilities.from(plane, scale);
            if (probabilities.count < 1) continue;
            double weightedLogP = weightedLogProbability(probabilities.values, probabilities.count, q);
            if (Double.isNaN(weightedLogP) || Double.isInfinite(weightedLogP)) continue;
            xs[n] = Math.log(scale);
            ys[n] = weightedLogP;
            n++;
        }
        if (n < 2) return Double.NaN;
        return slope(xs, ys, n);
    }

    private static double weightedLogProbability(double[] probabilities, int count, double q) {
        if (count <= 0) return Double.NaN;
        double denominator = 0.0;
        double[] weights = new double[count];
        if (q == 0.0) {
            for (int i = 0; i < count; i++) {
                weights[i] = 1.0 / count;
            }
        } else {
            for (int i = 0; i < count; i++) {
                double p = Math.max(EPS, probabilities[i]);
                double weight = Math.pow(p, q);
                if (Double.isNaN(weight) || Double.isInfinite(weight)) continue;
                weights[i] = weight;
                denominator += weight;
            }
            if (denominator <= 0.0) return Double.NaN;
            for (int i = 0; i < count; i++) {
                weights[i] /= denominator;
            }
        }

        double out = 0.0;
        for (int i = 0; i < count; i++) {
            out += weights[i] * Math.log(Math.max(EPS, probabilities[i]));
        }
        return out;
    }

    private static double slope(double[] xs, double[] ys, int n) {
        double meanX = 0.0;
        double meanY = 0.0;
        for (int i = 0; i < n; i++) {
            meanX += xs[i];
            meanY += ys[i];
        }
        meanX /= n;
        meanY /= n;
        double cov = 0.0;
        double var = 0.0;
        for (int i = 0; i < n; i++) {
            double dx = xs[i] - meanX;
            cov += dx * (ys[i] - meanY);
            var += dx * dx;
        }
        return var <= 0.0 ? Double.NaN : cov / var;
    }

    private static Rectangle clippedBounds(ImagePlus image, Roi roi) {
        int width = image == null ? 0 : image.getWidth();
        int height = image == null ? 0 : image.getHeight();
        Rectangle raw = roi == null ? new Rectangle(0, 0, width, height) : roi.getBounds();
        int x0 = Math.max(0, raw.x);
        int y0 = Math.max(0, raw.y);
        int x1 = Math.min(width, raw.x + raw.width);
        int y1 = Math.min(height, raw.y + raw.height);
        return new Rectangle(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
    }

    private static final class BoxProbabilities {
        final double[] values;
        final int count;

        private BoxProbabilities(double[] values, int count) {
            this.values = values;
            this.count = count;
        }

        static BoxProbabilities from(Plane plane, int scale) {
            int boxesX = Math.max(1, (plane.width + scale - 1) / scale);
            int boxesY = Math.max(1, (plane.height + scale - 1) / scale);
            double[] probabilities = new double[boxesX * boxesY];
            int count = 0;
            for (int by = 0; by < boxesY; by++) {
                int y0 = by * scale;
                int y1 = Math.min(plane.height, y0 + scale);
                for (int bx = 0; bx < boxesX; bx++) {
                    int x0 = bx * scale;
                    int x1 = Math.min(plane.width, x0 + scale);
                    double mass = 0.0;
                    for (int y = y0; y < y1; y++) {
                        for (int x = x0; x < x1; x++) {
                            int index = y * plane.width + x;
                            if (!plane.valid[index]) continue;
                            mass += plane.mass[index];
                        }
                    }
                    if (mass <= 0.0) continue;
                    probabilities[count++] = mass / plane.totalMass;
                }
            }
            return new BoxProbabilities(probabilities, count);
        }
    }

    private static final class Plane {
        final int width;
        final int height;
        final double[] mass;
        final boolean[] valid;
        final int count;
        final double totalMass;

        private Plane(int width,
                      int height,
                      double[] mass,
                      boolean[] valid,
                      int count,
                      double totalMass) {
            this.width = width;
            this.height = height;
            this.mass = mass;
            this.valid = valid;
            this.count = count;
            this.totalMass = totalMass;
        }

        static Plane from(ImagePlus image, int slice, Roi roi) {
            if (image == null || image.getStackSize() < slice) {
                return empty();
            }
            Rectangle bounds = clippedBounds(image, roi);
            if (bounds.width <= 0 || bounds.height <= 0) {
                return empty();
            }
            int stepX = Math.max(1, (bounds.width + MAX_SIDE - 1) / MAX_SIDE);
            int stepY = Math.max(1, (bounds.height + MAX_SIDE - 1) / MAX_SIDE);
            int width = Math.max(1, (bounds.width + stepX - 1) / stepX);
            int height = Math.max(1, (bounds.height + stepY - 1) / stepY);
            double[] raw = new double[width * height];
            boolean[] valid = new boolean[raw.length];
            ImageProcessor ip = image.getStack().getProcessor(slice);
            int count = 0;
            double min = Double.POSITIVE_INFINITY;

            for (int yy = 0; yy < height; yy++) {
                int y0 = bounds.y + yy * stepY;
                int y1 = Math.min(bounds.y + bounds.height, y0 + stepY);
                for (int xx = 0; xx < width; xx++) {
                    int x0 = bounds.x + xx * stepX;
                    int x1 = Math.min(bounds.x + bounds.width, x0 + stepX);
                    double sum = 0.0;
                    int n = 0;
                    for (int y = y0; y < y1; y++) {
                        for (int x = x0; x < x1; x++) {
                            if (roi != null && !roi.contains(x, y)) continue;
                            double value = ip.getf(x, y);
                            if (Double.isNaN(value) || Double.isInfinite(value)) continue;
                            sum += value;
                            n++;
                        }
                    }
                    if (n == 0) continue;
                    double blockMean = sum / n;
                    int index = yy * width + xx;
                    raw[index] = blockMean;
                    valid[index] = true;
                    count++;
                    if (blockMean < min) min = blockMean;
                }
            }

            double offset = min < 0.0 && !Double.isInfinite(min) ? -min : 0.0;
            double totalMass = 0.0;
            double[] mass = new double[raw.length];
            for (int i = 0; i < raw.length; i++) {
                if (!valid[i]) continue;
                double value = raw[i] + offset;
                if (value <= 0.0 || Double.isNaN(value) || Double.isInfinite(value)) continue;
                mass[i] = value;
                totalMass += value;
            }
            return new Plane(width, height, mass, valid, count, totalMass);
        }

        private static Plane empty() {
            return new Plane(0, 0, new double[0], new boolean[0], 0, 0.0);
        }
    }
}
