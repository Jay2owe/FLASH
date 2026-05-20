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
 * Scale-response granularity spectrum for same-channel intensity images.
 */
public final class GranularityAnalysis implements IntensitySpatialAnalysis {
    public static final String COLUMN_PEAK = "Intensity_GranularityPeak_um";
    public static final String COLUMN_CENTROID = "Intensity_GranularityCentroid_um";

    @Override
    public IntensitySpatialConfig.AnalysisKey key() {
        return IntensitySpatialConfig.AnalysisKey.GRANULARITY;
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
        return Collections.singleton(DependencyId.IMGLIB2_ALGORITHM_RUNTIME);
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
        return 6;
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
        Plane plane = Plane.from(image, context.sliceIndex(), context.roi());
        double[] scales = context.config().getGranularityScalesUm();
        if (plane.count < 4) {
            context.warn("granularity has insufficient valid ROI pixels; returning NaN.");
            putNan(values, scales, suffix);
            return;
        }
        if (plane.variance <= 0.0 || Double.isNaN(plane.variance)) {
            putZeroSpectrum(values, scales, suffix);
            return;
        }

        double[] energies = new double[scales.length];
        double energySum = 0.0;
        for (int i = 0; i < scales.length; i++) {
            int lagX = scalePixels(scales[i], pixelSize(image, true));
            int lagY = scalePixels(scales[i], pixelSize(image, false));
            energies[i] = antiCorrelationEnergy(plane, lagX, lagY);
            energySum += energies[i];
        }

        if (energySum <= 0.0) {
            energySum = 0.0;
            for (int i = 0; i < scales.length; i++) {
                int lagX = scalePixels(scales[i], pixelSize(image, true));
                int lagY = scalePixels(scales[i], pixelSize(image, false));
                energies[i] = haarContrastEnergy(plane, lagX, lagY);
                energySum += energies[i];
            }
        }

        double peak = Double.NaN;
        double centroid = Double.NaN;
        if (energySum > 0.0) {
            double best = -1.0;
            double weighted = 0.0;
            for (int i = 0; i < scales.length; i++) {
                if (energies[i] > best) {
                    best = energies[i];
                    peak = scales[i];
                }
                weighted += scales[i] * energies[i];
            }
            centroid = weighted / energySum;
        }

        values.put(COLUMN_PEAK + suffix, Double.valueOf(peak));
        values.put(COLUMN_CENTROID + suffix, Double.valueOf(centroid));
        for (int i = 0; i < scales.length; i++) {
            values.put("Intensity_GranularityEnergy" + scaleToken(scales[i]) + suffix,
                    Double.valueOf(energies[i]));
        }
    }

    private static void addColumns(List<String> columns,
                                   IntensitySpatialConfig config,
                                   String suffix) {
        columns.add(COLUMN_PEAK + suffix);
        columns.add(COLUMN_CENTROID + suffix);
        double[] scales = config == null
                ? IntensitySpatialConfig.DEFAULT_GRANULARITY_SCALES_UM
                : config.getGranularityScalesUm();
        for (double scale : scales) {
            columns.add("Intensity_GranularityEnergy" + scaleToken(scale) + suffix);
        }
    }

    private static void putNan(LinkedHashMap<String, Double> values, double[] scales, String suffix) {
        values.put(COLUMN_PEAK + suffix, Double.valueOf(Double.NaN));
        values.put(COLUMN_CENTROID + suffix, Double.valueOf(Double.NaN));
        for (double scale : scales) {
            values.put("Intensity_GranularityEnergy" + scaleToken(scale) + suffix,
                    Double.valueOf(Double.NaN));
        }
    }

    private static void putZeroSpectrum(LinkedHashMap<String, Double> values, double[] scales, String suffix) {
        values.put(COLUMN_PEAK + suffix, Double.valueOf(Double.NaN));
        values.put(COLUMN_CENTROID + suffix, Double.valueOf(Double.NaN));
        for (double scale : scales) {
            values.put("Intensity_GranularityEnergy" + scaleToken(scale) + suffix,
                    Double.valueOf(0.0));
        }
    }

    private static double antiCorrelationEnergy(Plane plane, int lagX, int lagY) {
        double horizontal = directionalCorrelation(plane, lagX, 0);
        double vertical = directionalCorrelation(plane, 0, lagY);
        double corr = meanFinite(horizontal, vertical);
        if (Double.isNaN(corr)) return 0.0;
        return Math.max(0.0, -corr);
    }

    private static double haarContrastEnergy(Plane plane, int blockWidth, int blockHeight) {
        int stepX = Math.max(1, blockWidth / 2);
        int stepY = Math.max(1, blockHeight / 2);
        double sum = 0.0;
        int count = 0;
        for (int y = 0; y < plane.height; y += stepY) {
            for (int x = 0; x < plane.width; x += stepX) {
                double center = plane.blockMean(x, y, blockWidth, blockHeight);
                if (Double.isNaN(center)) continue;
                double right = plane.blockMean(x + blockWidth, y, blockWidth, blockHeight);
                if (!Double.isNaN(right)) {
                    double delta = center - right;
                    sum += delta * delta;
                    count++;
                }
                double down = plane.blockMean(x, y + blockHeight, blockWidth, blockHeight);
                if (!Double.isNaN(down)) {
                    double delta = center - down;
                    sum += delta * delta;
                    count++;
                }
            }
        }
        return count == 0 || plane.variance <= 0.0 ? 0.0 : sum / (count * plane.variance);
    }

    private static double directionalCorrelation(Plane plane, int dx, int dy) {
        if (dx == 0 && dy == 0) return Double.NaN;
        int xStart = Math.max(0, -dx);
        int yStart = Math.max(0, -dy);
        int xEnd = Math.min(plane.width, plane.width - dx);
        int yEnd = Math.min(plane.height, plane.height - dy);
        if (xEnd <= xStart || yEnd <= yStart) return Double.NaN;

        int count = 0;
        double sumA = 0.0;
        double sumB = 0.0;
        double sumAA = 0.0;
        double sumBB = 0.0;
        double sumAB = 0.0;
        for (int y = yStart; y < yEnd; y++) {
            for (int x = xStart; x < xEnd; x++) {
                int i = plane.index(x, y);
                int j = plane.index(x + dx, y + dy);
                if (!plane.valid[i] || !plane.valid[j]) continue;
                double a = plane.values[i];
                double b = plane.values[j];
                count++;
                sumA += a;
                sumB += b;
                sumAA += a * a;
                sumBB += b * b;
                sumAB += a * b;
            }
        }
        if (count < 2) return Double.NaN;
        double cov = sumAB - (sumA * sumB / count);
        double varA = sumAA - (sumA * sumA / count);
        double varB = sumBB - (sumB * sumB / count);
        if (varA <= 0.0 || varB <= 0.0) return Double.NaN;
        return cov / Math.sqrt(varA * varB);
    }

    private static double meanFinite(double a, double b) {
        boolean aOk = !Double.isNaN(a) && !Double.isInfinite(a);
        boolean bOk = !Double.isNaN(b) && !Double.isInfinite(b);
        if (aOk && bOk) return (a + b) / 2.0;
        if (aOk) return a;
        if (bOk) return b;
        return Double.NaN;
    }

    private static int scalePixels(double scaleUm, double pixelSizeUm) {
        double pixelSize = pixelSizeUm > 0.0 && !Double.isNaN(pixelSizeUm)
                && !Double.isInfinite(pixelSizeUm) ? pixelSizeUm : 1.0;
        return Math.max(1, (int) Math.round(scaleUm / pixelSize));
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

    private static final class Plane {
        final int width;
        final int height;
        final double[] values;
        final boolean[] valid;
        final double[] integral;
        final int[] countIntegral;
        final int count;
        final double variance;

        private Plane(int width,
                      int height,
                      double[] values,
                      boolean[] valid,
                      double[] integral,
                      int[] countIntegral,
                      int count,
                      double variance) {
            this.width = width;
            this.height = height;
            this.values = values;
            this.valid = valid;
            this.integral = integral;
            this.countIntegral = countIntegral;
            this.count = count;
            this.variance = variance;
        }

        int index(int x, int y) {
            return y * width + x;
        }

        double blockMean(int x, int y, int blockWidth, int blockHeight) {
            int x0 = Math.max(0, x);
            int y0 = Math.max(0, y);
            int x1 = Math.min(width, x + Math.max(1, blockWidth));
            int y1 = Math.min(height, y + Math.max(1, blockHeight));
            if (x1 <= x0 || y1 <= y0) return Double.NaN;
            int count = countSum(x0, y0, x1, y1);
            if (count == 0) return Double.NaN;
            return sum(x0, y0, x1, y1) / count;
        }

        private double sum(int x0, int y0, int x1, int y1) {
            int stride = width + 1;
            int a = y0 * stride + x0;
            int b = y0 * stride + x1;
            int c = y1 * stride + x0;
            int d = y1 * stride + x1;
            return integral[d] - integral[b] - integral[c] + integral[a];
        }

        private int countSum(int x0, int y0, int x1, int y1) {
            int stride = width + 1;
            int a = y0 * stride + x0;
            int b = y0 * stride + x1;
            int c = y1 * stride + x0;
            int d = y1 * stride + x1;
            return countIntegral[d] - countIntegral[b] - countIntegral[c] + countIntegral[a];
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
            int width = bounds.width;
            int height = bounds.height;
            double[] values = new double[width * height];
            boolean[] valid = new boolean[width * height];
            int count = 0;
            double mean = 0.0;
            double m2 = 0.0;
            for (int yy = 0; yy < height; yy++) {
                int y = bounds.y + yy;
                for (int xx = 0; xx < width; xx++) {
                    int x = bounds.x + xx;
                    int index = yy * width + xx;
                    if (roi != null && !roi.contains(x, y)) continue;
                    double value = ip.getf(x, y);
                    if (Double.isNaN(value) || Double.isInfinite(value)) continue;
                    values[index] = value;
                    valid[index] = true;
                    count++;
                    double delta = value - mean;
                    mean += delta / count;
                    double delta2 = value - mean;
                    m2 += delta * delta2;
                }
            }

            double[] integral = new double[(width + 1) * (height + 1)];
            int[] countIntegral = new int[(width + 1) * (height + 1)];
            for (int y = 0; y < height; y++) {
                double rowSum = 0.0;
                int rowCount = 0;
                for (int x = 0; x < width; x++) {
                    int index = y * width + x;
                    if (valid[index]) {
                        rowSum += values[index];
                        rowCount++;
                    }
                    int integralIndex = (y + 1) * (width + 1) + (x + 1);
                    integral[integralIndex] = integral[integralIndex - (width + 1)] + rowSum;
                    countIntegral[integralIndex] = countIntegral[integralIndex - (width + 1)] + rowCount;
                }
            }
            double variance = count < 2 ? 0.0 : m2 / count;
            return new Plane(width, height, values, valid, integral, countIntegral, count, variance);
        }

        private static Plane empty() {
            return new Plane(0, 0, new double[0], new boolean[0], new double[0], new int[0], 0, Double.NaN);
        }
    }
}
