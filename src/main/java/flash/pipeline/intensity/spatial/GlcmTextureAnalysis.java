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
 * 32-level gray-level co-occurrence matrix texture metrics at 4 angles.
 */
public final class GlcmTextureAnalysis implements IntensitySpatialAnalysis {
    public static final String COLUMN_CONTRAST = "Intensity_GLCMContrast";
    public static final String COLUMN_ENTROPY = "Intensity_GLCMEntropy";
    public static final String COLUMN_HOMOGENEITY = "Intensity_GLCMHomogeneity";
    public static final String COLUMN_ASM = "Intensity_GLCMASM";
    public static final String COLUMN_CORRELATION = "Intensity_GLCMCorrelation";

    private static final int LEVELS = 32;
    private static final int[][] OFFSETS = {
            {1, 0},
            {0, 1},
            {1, 1},
            {-1, 1}
    };

    @Override
    public IntensitySpatialConfig.AnalysisKey key() {
        return IntensitySpatialConfig.AnalysisKey.GLCM;
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
        return java.util.Arrays.asList(COLUMN_CONTRAST, COLUMN_ENTROPY,
                COLUMN_HOMOGENEITY, COLUMN_ASM, COLUMN_CORRELATION);
    }

    @Override
    public int estimatedCost() {
        return 3;
    }

    @Override
    public IntensitySpatialResult measure(IntensitySpatialContext context) {
        Plane plane = Plane.from(context.image(), context.sliceIndex(), context.roi());
        LinkedHashMap<String, Double> values = new LinkedHashMap<String, Double>();
        if (plane.count < 2 || plane.width < 2 || plane.height < 2) {
            context.warn("GLCM texture has insufficient valid ROI pixels; returning NaN.");
            putNan(values);
            return new IntensitySpatialResult(values);
        }
        if (plane.max <= plane.min) {
            values.put(COLUMN_CONTRAST, Double.valueOf(0.0));
            values.put(COLUMN_ENTROPY, Double.valueOf(0.0));
            values.put(COLUMN_HOMOGENEITY, Double.valueOf(1.0));
            values.put(COLUMN_ASM, Double.valueOf(1.0));
            values.put(COLUMN_CORRELATION, Double.valueOf(1.0));
            return new IntensitySpatialResult(values);
        }

        Metrics metrics = Metrics.from(plane);
        if (metrics.pairCount == 0) {
            context.warn("GLCM texture has no valid neighbor pairs; returning NaN.");
            putNan(values);
            return new IntensitySpatialResult(values);
        }

        values.put(COLUMN_CONTRAST, Double.valueOf(metrics.contrast));
        values.put(COLUMN_ENTROPY, Double.valueOf(metrics.entropy));
        values.put(COLUMN_HOMOGENEITY, Double.valueOf(metrics.homogeneity));
        values.put(COLUMN_ASM, Double.valueOf(metrics.asm));
        values.put(COLUMN_CORRELATION, Double.valueOf(metrics.correlation));
        return new IntensitySpatialResult(values);
    }

    private static void putNan(LinkedHashMap<String, Double> values) {
        values.put(COLUMN_CONTRAST, Double.valueOf(Double.NaN));
        values.put(COLUMN_ENTROPY, Double.valueOf(Double.NaN));
        values.put(COLUMN_HOMOGENEITY, Double.valueOf(Double.NaN));
        values.put(COLUMN_ASM, Double.valueOf(Double.NaN));
        values.put(COLUMN_CORRELATION, Double.valueOf(Double.NaN));
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

    private static final class Metrics {
        final int pairCount;
        final double contrast;
        final double entropy;
        final double homogeneity;
        final double asm;
        final double correlation;

        private Metrics(int pairCount,
                        double contrast,
                        double entropy,
                        double homogeneity,
                        double asm,
                        double correlation) {
            this.pairCount = pairCount;
            this.contrast = contrast;
            this.entropy = entropy;
            this.homogeneity = homogeneity;
            this.asm = asm;
            this.correlation = correlation;
        }

        static Metrics from(Plane plane) {
            double[][] matrix = new double[LEVELS][LEVELS];
            int pairCount = 0;
            for (int[] offset : OFFSETS) {
                int dx = offset[0];
                int dy = offset[1];
                int x0 = Math.max(0, -dx);
                int x1 = Math.min(plane.width, plane.width - dx);
                int y0 = Math.max(0, -dy);
                int y1 = Math.min(plane.height, plane.height - dy);
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int aIndex = y * plane.width + x;
                        int bIndex = (y + dy) * plane.width + (x + dx);
                        if (!plane.valid[aIndex] || !plane.valid[bIndex]) continue;
                        int a = quantize(plane.values[aIndex], plane.min, plane.max);
                        int b = quantize(plane.values[bIndex], plane.min, plane.max);
                        matrix[a][b] += 1.0;
                        matrix[b][a] += 1.0;
                        pairCount += 2;
                    }
                }
            }
            if (pairCount == 0) {
                return new Metrics(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
            }

            double contrast = 0.0;
            double entropy = 0.0;
            double homogeneity = 0.0;
            double asm = 0.0;
            double meanI = 0.0;
            double meanJ = 0.0;
            for (int i = 0; i < LEVELS; i++) {
                for (int j = 0; j < LEVELS; j++) {
                    double p = matrix[i][j] / pairCount;
                    if (p <= 0.0) continue;
                    double delta = i - j;
                    contrast += delta * delta * p;
                    entropy -= p * Math.log(p);
                    homogeneity += p / (1.0 + Math.abs(delta));
                    asm += p * p;
                    meanI += i * p;
                    meanJ += j * p;
                }
            }
            entropy /= Math.log(LEVELS * LEVELS);

            double varI = 0.0;
            double varJ = 0.0;
            double covariance = 0.0;
            for (int i = 0; i < LEVELS; i++) {
                for (int j = 0; j < LEVELS; j++) {
                    double p = matrix[i][j] / pairCount;
                    if (p <= 0.0) continue;
                    double di = i - meanI;
                    double dj = j - meanJ;
                    varI += di * di * p;
                    varJ += dj * dj * p;
                    covariance += di * dj * p;
                }
            }
            double correlation = varI <= 0.0 || varJ <= 0.0
                    ? 1.0
                    : covariance / Math.sqrt(varI * varJ);
            return new Metrics(pairCount, contrast, entropy, homogeneity, asm, correlation);
        }

        private static int quantize(double value, double min, double max) {
            double scaled = (value - min) / (max - min);
            int level = (int) Math.floor(scaled * LEVELS);
            if (level < 0) return 0;
            if (level >= LEVELS) return LEVELS - 1;
            return level;
        }
    }

    private static final class Plane {
        final int width;
        final int height;
        final double[] values;
        final boolean[] valid;
        final int count;
        final double min;
        final double max;

        private Plane(int width,
                      int height,
                      double[] values,
                      boolean[] valid,
                      int count,
                      double min,
                      double max) {
            this.width = width;
            this.height = height;
            this.values = values;
            this.valid = valid;
            this.count = count;
            this.min = min;
            this.max = max;
        }

        static Plane from(ImagePlus image, int slice, Roi roi) {
            if (image == null || image.getStackSize() < slice) {
                return empty();
            }
            Rectangle bounds = clippedBounds(image, roi);
            if (bounds.width <= 0 || bounds.height <= 0) {
                return empty();
            }
            ImageProcessor ip = image.getStack().getProcessor(slice);
            double[] values = new double[bounds.width * bounds.height];
            boolean[] valid = new boolean[values.length];
            int count = 0;
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (int yy = 0; yy < bounds.height; yy++) {
                int y = bounds.y + yy;
                for (int xx = 0; xx < bounds.width; xx++) {
                    int x = bounds.x + xx;
                    int index = yy * bounds.width + xx;
                    if (roi != null && !roi.contains(x, y)) continue;
                    double value = ip.getf(x, y);
                    if (Double.isNaN(value) || Double.isInfinite(value)) continue;
                    values[index] = value;
                    valid[index] = true;
                    count++;
                    if (value < min) min = value;
                    if (value > max) max = value;
                }
            }
            if (count == 0) {
                min = Double.NaN;
                max = Double.NaN;
            }
            return new Plane(bounds.width, bounds.height, values, valid, count, min, max);
        }

        private static Plane empty() {
            return new Plane(0, 0, new double[0], new boolean[0], 0, Double.NaN, Double.NaN);
        }
    }
}
