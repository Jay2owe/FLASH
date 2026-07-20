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
import java.util.Locale;
import java.util.Set;

/**
 * ROI-boundary depth profile metrics for same-channel intensity images.
 */
public final class DepthProfileAnalysis implements IntensitySpatialAnalysis {
    private static final int DEPTH_BIN_COUNT = 3;
    private static final double INF = 1e30;

    @Override
    public IntensitySpatialConfig.AnalysisKey key() {
        return IntensitySpatialConfig.AnalysisKey.DEPTH_PROFILE;
    }

    @Override
    public AnalysisValidity validity() {
        return AnalysisValidity.EITHER_VALID;
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
        java.util.ArrayList<String> columns = new java.util.ArrayList<String>();
        addColumns(columns, config, "");
        if (binarizedPartner) {
            addColumns(columns, config, "_binarized");
        }
        return columns;
    }

    @Override
    public int estimatedCost() {
        return 4;
    }

    @Override
    public IntensitySpatialResult measure(IntensitySpatialContext context) {
        LinkedHashMap<String, Double> values = new LinkedHashMap<String, Double>();
        measureInto(values, context, context.image(), "");
        if (context.hasBinarizedImage()) {
            measureInto(values, context, context.binarizedImage(), "_binarized");
        }
        return new IntensitySpatialResult(values);
    }

    private static void measureInto(LinkedHashMap<String, Double> values,
                                    IntensitySpatialContext context,
                                    ImagePlus image,
                                    String suffix) {
        Samples samples = Samples.from(image, context.sliceIndex(), context.roi());
        double binWidth = depthBinWidth(context.config());
        if (samples.count == 0) {
            context.warn("depth profile has no valid ROI pixels; returning NaN.");
            putNan(values, binWidth, suffix);
            return;
        }

        for (int i = 0; i < DEPTH_BIN_COUNT; i++) {
            double low = i * binWidth;
            double high = (i + 1) * binWidth;
            values.put(depthBinColumn(low, high, suffix),
                    Double.valueOf(samples.meanInDepthRange(low, high)));
        }
        values.put("Intensity_DepthSlope" + suffix, Double.valueOf(samples.depthSlope()));
        values.put("Intensity_DepthPeak_um" + suffix, Double.valueOf(samples.peakDepth(binWidth)));
        double rimDepth = rimDepth(context.config());
        values.put("Intensity_RimCoreRatio" + suffix, Double.valueOf(samples.rimCoreRatio(rimDepth)));
        values.put("Intensity_EdgeCouplingIdx" + suffix, Double.valueOf(samples.edgeCouplingIndex(rimDepth)));
    }

    private static void addColumns(List<String> columns,
                                   IntensitySpatialConfig config,
                                   String suffix) {
        double binWidth = depthBinWidth(config);
        for (int i = 0; i < DEPTH_BIN_COUNT; i++) {
            columns.add(depthBinColumn(i * binWidth, (i + 1) * binWidth, suffix));
        }
        columns.add("Intensity_DepthSlope" + suffix);
        columns.add("Intensity_DepthPeak_um" + suffix);
        columns.add("Intensity_RimCoreRatio" + suffix);
        columns.add("Intensity_EdgeCouplingIdx" + suffix);
    }

    private static void putNan(LinkedHashMap<String, Double> values,
                               double binWidth,
                               String suffix) {
        for (int i = 0; i < DEPTH_BIN_COUNT; i++) {
            values.put(depthBinColumn(i * binWidth, (i + 1) * binWidth, suffix),
                    Double.valueOf(Double.NaN));
        }
        values.put("Intensity_DepthSlope" + suffix, Double.valueOf(Double.NaN));
        values.put("Intensity_DepthPeak_um" + suffix, Double.valueOf(Double.NaN));
        values.put("Intensity_RimCoreRatio" + suffix, Double.valueOf(Double.NaN));
        values.put("Intensity_EdgeCouplingIdx" + suffix, Double.valueOf(Double.NaN));
    }

    private static String depthBinColumn(double low, double high, String suffix) {
        return "Intensity_DepthBin" + scaleToken(low) + "to" + scaleToken(high) + suffix;
    }

    private static double depthBinWidth(IntensitySpatialConfig config) {
        double value = config == null
                ? IntensitySpatialConfig.DEFAULT_DEPTH_BIN_WIDTH_UM
                : config.getDepthBinWidthUm();
        return positive(value, IntensitySpatialConfig.DEFAULT_DEPTH_BIN_WIDTH_UM);
    }

    private static double rimDepth(IntensitySpatialConfig config) {
        double value = config == null
                ? IntensitySpatialConfig.DEFAULT_RIM_DEPTH_UM
                : config.getRimDepthUm();
        return positive(value, IntensitySpatialConfig.DEFAULT_RIM_DEPTH_UM);
    }

    private static double positive(double value, double fallback) {
        return value > 0.0 && !Double.isNaN(value) && !Double.isInfinite(value) ? value : fallback;
    }

    private static double pixelSize(ImagePlus image, boolean xAxis) {
        return CalibrationUtil.pixelSizeUm(image,
                xAxis ? CalibrationUtil.Axis.X : CalibrationUtil.Axis.Y);
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

    private static String scaleToken(double value) {
        if (Double.compare(value, Math.rint(value)) == 0) {
            return String.valueOf((long) value);
        }
        String text = String.format(Locale.ROOT, "%.6f", value);
        while (text.contains(".") && text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        return text.replace('.', 'p');
    }

    private static double[] distanceFromBoundary(boolean[] inside,
                                                 int width,
                                                 int height,
                                                 double pixelWidth,
                                                 double pixelHeight) {
        double[] f = new double[inside.length];
        for (int i = 0; i < inside.length; i++) {
            f[i] = inside[i] ? INF : 0.0;
        }

        double[] rowTmp = new double[width];
        double[] rowOut = new double[width];
        double[] horizontal = new double[f.length];
        for (int y = 0; y < height; y++) {
            System.arraycopy(f, y * width, rowTmp, 0, width);
            transform1D(rowTmp, rowOut, width, pixelWidth);
            System.arraycopy(rowOut, 0, horizontal, y * width, width);
        }

        double[] colTmp = new double[height];
        double[] colOut = new double[height];
        double[] out = new double[f.length];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                colTmp[y] = horizontal[y * width + x];
            }
            transform1D(colTmp, colOut, height, pixelHeight);
            for (int y = 0; y < height; y++) {
                out[y * width + x] = Math.sqrt(Math.max(0.0, colOut[y]));
            }
        }
        return out;
    }

    private static void transform1D(double[] f, double[] d, int n, double spacing) {
        double weight = spacing * spacing;
        int[] v = new int[n];
        double[] z = new double[n + 1];
        int k = -1;
        for (int q = 0; q < n; q++) {
            if (f[q] >= INF * 0.5) continue;
            if (k < 0) {
                k = 0;
                v[0] = q;
                z[0] = Double.NEGATIVE_INFINITY;
                z[1] = Double.POSITIVE_INFINITY;
                continue;
            }
            double s = intersection(f, v[k], q, weight);
            while (s <= z[k]) {
                k--;
                if (k < 0) break;
                s = intersection(f, v[k], q, weight);
            }
            if (k < 0) {
                k = 0;
                v[0] = q;
                z[0] = Double.NEGATIVE_INFINITY;
                z[1] = Double.POSITIVE_INFINITY;
            } else {
                k++;
                v[k] = q;
                z[k] = s;
                z[k + 1] = Double.POSITIVE_INFINITY;
            }
        }
        if (k < 0) {
            java.util.Arrays.fill(d, INF);
            return;
        }
        k = 0;
        for (int q = 0; q < n; q++) {
            while (z[k + 1] < q) {
                k++;
            }
            double delta = q - v[k];
            d[q] = f[v[k]] + weight * delta * delta;
        }
    }

    private static double intersection(double[] f, int p, int q, double weight) {
        double numerator = (f[q] + weight * q * q) - (f[p] + weight * p * p);
        return numerator / (2.0 * weight * (q - p));
    }

    private static final class Samples {
        final double[] depths;
        final double[] values;
        final int count;
        final double mean;
        final double maxDepth;

        private Samples(double[] depths, double[] values, int count, double mean, double maxDepth) {
            this.depths = depths;
            this.values = values;
            this.count = count;
            this.mean = mean;
            this.maxDepth = maxDepth;
        }

        double meanInDepthRange(double low, double high) {
            double sum = 0.0;
            int n = 0;
            for (int i = 0; i < count; i++) {
                double depth = depths[i];
                if (depth >= low && depth < high) {
                    sum += values[i];
                    n++;
                }
            }
            return n == 0 ? Double.NaN : sum / n;
        }

        double depthSlope() {
            if (count < 2) return Double.NaN;
            double meanDepth = 0.0;
            for (int i = 0; i < count; i++) {
                meanDepth += depths[i];
            }
            meanDepth /= count;
            double cov = 0.0;
            double var = 0.0;
            for (int i = 0; i < count; i++) {
                double dd = depths[i] - meanDepth;
                cov += dd * (values[i] - mean);
                var += dd * dd;
            }
            return var <= 0.0 ? Double.NaN : cov / var;
        }

        double peakDepth(double binWidth) {
            if (count == 0 || maxDepth < 0.0) return Double.NaN;
            int bins = Math.max(1, (int) Math.floor(maxDepth / binWidth) + 1);
            double[] sums = new double[bins];
            int[] counts = new int[bins];
            for (int i = 0; i < count; i++) {
                int bin = Math.min(bins - 1, (int) Math.floor(depths[i] / binWidth));
                sums[bin] += values[i];
                counts[bin]++;
            }
            double bestMean = Double.NEGATIVE_INFINITY;
            int bestBin = -1;
            for (int i = 0; i < bins; i++) {
                if (counts[i] == 0) continue;
                double binMean = sums[i] / counts[i];
                if (binMean > bestMean) {
                    bestMean = binMean;
                    bestBin = i;
                }
            }
            return bestBin < 0 ? Double.NaN : (bestBin + 0.5) * binWidth;
        }

        double rimCoreRatio(double rimDepth) {
            MeanPair pair = rimCoreMeans(rimDepth);
            if (Double.isNaN(pair.rim) || Double.isNaN(pair.core) || pair.core <= 0.0) {
                return Double.NaN;
            }
            return pair.rim / pair.core;
        }

        double edgeCouplingIndex(double rimDepth) {
            MeanPair pair = rimCoreMeans(rimDepth);
            if (Double.isNaN(pair.rim) || Double.isNaN(pair.core) || mean <= 0.0) {
                return Double.NaN;
            }
            return (pair.rim - pair.core) / mean;
        }

        private MeanPair rimCoreMeans(double rimDepth) {
            double rimSum = 0.0;
            double coreSum = 0.0;
            int rimCount = 0;
            int coreCount = 0;
            for (int i = 0; i < count; i++) {
                if (depths[i] <= rimDepth) {
                    rimSum += values[i];
                    rimCount++;
                } else {
                    coreSum += values[i];
                    coreCount++;
                }
            }
            return new MeanPair(
                    rimCount == 0 ? Double.NaN : rimSum / rimCount,
                    coreCount == 0 ? Double.NaN : coreSum / coreCount);
        }

        static Samples from(ImagePlus image, int slice, Roi roi) {
            if (image == null || image.getStackSize() < slice) {
                return empty();
            }
            Rectangle bounds = clippedBounds(image, roi);
            if (bounds.width <= 0 || bounds.height <= 0) {
                return empty();
            }
            ImageProcessor ip = image.getStack().getProcessor(slice);
            int paddedWidth = bounds.width + 2;
            int paddedHeight = bounds.height + 2;
            boolean[] inside = new boolean[paddedWidth * paddedHeight];
            for (int yy = 0; yy < bounds.height; yy++) {
                int y = bounds.y + yy;
                for (int xx = 0; xx < bounds.width; xx++) {
                    int x = bounds.x + xx;
                    if (roi == null || roi.contains(x, y)) {
                        inside[(yy + 1) * paddedWidth + (xx + 1)] = true;
                    }
                }
            }

            double pixelWidth = pixelSize(image, true);
            double pixelHeight = pixelSize(image, false);
            double[] distance = distanceFromBoundary(inside, paddedWidth, paddedHeight, pixelWidth, pixelHeight);
            double boundaryOffset = 0.5 * Math.min(pixelWidth, pixelHeight);
            double[] depths = new double[bounds.width * bounds.height];
            double[] values = new double[depths.length];
            int count = 0;
            double sum = 0.0;
            double maxDepth = Double.NaN;
            for (int yy = 0; yy < bounds.height; yy++) {
                int y = bounds.y + yy;
                for (int xx = 0; xx < bounds.width; xx++) {
                    int x = bounds.x + xx;
                    if (roi != null && !roi.contains(x, y)) continue;
                    double value = ip.getf(x, y);
                    if (Double.isNaN(value) || Double.isInfinite(value)) continue;
                    double depth = Math.max(0.0,
                            distance[(yy + 1) * paddedWidth + (xx + 1)] - boundaryOffset);
                    depths[count] = depth;
                    values[count] = value;
                    count++;
                    sum += value;
                    maxDepth = Double.isNaN(maxDepth) ? depth : Math.max(maxDepth, depth);
                }
            }
            return new Samples(
                    java.util.Arrays.copyOf(depths, count),
                    java.util.Arrays.copyOf(values, count),
                    count,
                    count == 0 ? Double.NaN : sum / count,
                    count == 0 ? Double.NaN : maxDepth);
        }

        private static Samples empty() {
            return new Samples(new double[0], new double[0], 0, Double.NaN, Double.NaN);
        }
    }

    private static final class MeanPair {
        final double rim;
        final double core;

        MeanPair(double rim, double core) {
            this.rim = rim;
            this.core = core;
        }
    }
}
