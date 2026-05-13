package flash.pipeline.intensity.spatial;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import flash.pipeline.runtime.DependencyId;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.process.ImageProcessor;

import java.awt.Rectangle;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Same-channel tile variation, Gini, and excess lacunarity metrics.
 */
public final class PatchinessAnalysis implements IntensitySpatialAnalysis {
    @Override
    public IntensitySpatialConfig.AnalysisKey key() {
        return IntensitySpatialConfig.AnalysisKey.PATCHINESS;
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
        LinkedHashMap<String, Double> out = new LinkedHashMap<String, Double>();
        addColumns(out, config, "");
        if (binarizedPartner) {
            addColumns(out, config, "_binarized");
        }
        return new java.util.ArrayList<String>(out.keySet());
    }

    @Override
    public int estimatedCost() {
        return 2;
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
        double[] scales = context.config().getTileScalesUm();
        PixelValues pixels = PixelValues.from(image, context.sliceIndex(), context.roi());
        if (pixels.count == 0) {
            context.warn("patchiness has no valid ROI pixels; returning NaN.");
            for (String column : columnsFor(context.config(), suffix)) {
                values.put(column, Double.valueOf(Double.NaN));
            }
            return;
        }

        for (double scale : scales) {
            values.put("Intensity_PatchinessCV" + scaleToken(scale) + suffix,
                    Double.valueOf(tileCv(image, context.sliceIndex(), context.roi(), scale)));
        }
        values.put("Intensity_PatchinessGini" + suffix, Double.valueOf(gini(pixels.values)));
        for (double scale : scales) {
            values.put("Intensity_Lacunarity" + scaleToken(scale) + suffix,
                    Double.valueOf(tileExcessLacunarity(image, context.sliceIndex(), context.roi(), scale)));
        }
    }

    private static void addColumns(LinkedHashMap<String, Double> out,
                                   IntensitySpatialConfig config,
                                   String suffix) {
        for (String column : columnsFor(config, suffix)) {
            out.put(column, Double.valueOf(Double.NaN));
        }
    }

    private static List<String> columnsFor(IntensitySpatialConfig config, String suffix) {
        java.util.ArrayList<String> columns = new java.util.ArrayList<String>();
        double[] scales = config == null
                ? IntensitySpatialConfig.DEFAULT_TILE_SCALES_UM
                : config.getTileScalesUm();
        for (double scale : scales) {
            columns.add("Intensity_PatchinessCV" + scaleToken(scale) + suffix);
        }
        columns.add("Intensity_PatchinessGini" + suffix);
        for (double scale : scales) {
            columns.add("Intensity_Lacunarity" + scaleToken(scale) + suffix);
        }
        return columns;
    }

    private static double tileCv(ImagePlus image, int slice, Roi roi, double scaleUm) {
        double[] means = tileMeans(image, slice, roi, scaleUm);
        if (means.length == 0) return Double.NaN;
        double mean = mean(means);
        if (mean <= 0.0 || Double.isNaN(mean) || Double.isInfinite(mean)) return Double.NaN;
        return Math.sqrt(variance(means, mean)) / mean;
    }

    private static double tileExcessLacunarity(ImagePlus image, int slice, Roi roi, double scaleUm) {
        double[] means = tileMeans(image, slice, roi, scaleUm);
        if (means.length == 0) return Double.NaN;
        double mean = mean(means);
        if (mean <= 0.0 || Double.isNaN(mean) || Double.isInfinite(mean)) return Double.NaN;
        return variance(means, mean) / (mean * mean);
    }

    private static double[] tileMeans(ImagePlus image, int slice, Roi roi, double scaleUm) {
        if (image == null || image.getStackSize() < slice) return new double[0];
        ImageProcessor ip = image.getStack().getProcessor(slice);
        Rectangle bounds = clippedBounds(image, roi);
        if (bounds.width <= 0 || bounds.height <= 0) return new double[0];

        int tileWidth = tilePixels(scaleUm, pixelSize(image, true));
        int tileHeight = tilePixels(scaleUm, pixelSize(image, false));
        int tilesX = Math.max(1, (bounds.width + tileWidth - 1) / tileWidth);
        int tilesY = Math.max(1, (bounds.height + tileHeight - 1) / tileHeight);
        double[] sums = new double[tilesX * tilesY];
        int[] counts = new int[tilesX * tilesY];

        for (int y = bounds.y; y < bounds.y + bounds.height; y++) {
            for (int x = bounds.x; x < bounds.x + bounds.width; x++) {
                if (roi != null && !roi.contains(x, y)) continue;
                double value = ip.getf(x, y);
                if (Double.isNaN(value) || Double.isInfinite(value)) continue;
                int tx = Math.min(tilesX - 1, (x - bounds.x) / tileWidth);
                int ty = Math.min(tilesY - 1, (y - bounds.y) / tileHeight);
                int index = ty * tilesX + tx;
                sums[index] += value;
                counts[index]++;
            }
        }

        int count = 0;
        for (int tileCount : counts) {
            if (tileCount > 0) count++;
        }
        double[] means = new double[count];
        int out = 0;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > 0) {
                means[out++] = sums[i] / counts[i];
            }
        }
        return means;
    }

    private static double gini(double[] values) {
        if (values == null || values.length == 0) return Double.NaN;
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        double sum = 0.0;
        double weighted = 0.0;
        for (int i = 0; i < sorted.length; i++) {
            double value = sorted[i];
            sum += value;
            weighted += (i + 1.0) * value;
        }
        if (sum <= 0.0) return Double.NaN;
        double n = sorted.length;
        double result = (2.0 * weighted) / (n * sum) - (n + 1.0) / n;
        return result < 0.0 && result > -1e-12 ? 0.0 : result;
    }

    private static double mean(double[] values) {
        if (values == null || values.length == 0) return Double.NaN;
        double sum = 0.0;
        for (double value : values) sum += value;
        return sum / values.length;
    }

    private static double variance(double[] values, double mean) {
        if (values == null || values.length == 0) return Double.NaN;
        double sumSq = 0.0;
        for (double value : values) {
            double delta = value - mean;
            sumSq += delta * delta;
        }
        return sumSq / values.length;
    }

    private static int tilePixels(double scaleUm, double pixelSizeUm) {
        double pixelSize = pixelSizeUm > 0.0 && !Double.isNaN(pixelSizeUm)
                && !Double.isInfinite(pixelSizeUm) ? pixelSizeUm : 1.0;
        return Math.max(1, (int) Math.round(scaleUm / pixelSize));
    }

    private static double pixelSize(ImagePlus image, boolean xAxis) {
        Calibration cal = image == null ? null : image.getCalibration();
        double value = cal == null ? 1.0 : (xAxis ? cal.pixelWidth : cal.pixelHeight);
        return value > 0.0 && !Double.isNaN(value) && !Double.isInfinite(value) ? value : 1.0;
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

    private static final class PixelValues {
        final double[] values;
        final int count;

        private PixelValues(double[] values, int count) {
            this.values = values;
            this.count = count;
        }

        static PixelValues from(ImagePlus image, int slice, Roi roi) {
            if (image == null || image.getStackSize() < slice) {
                return new PixelValues(new double[0], 0);
            }
            ImageProcessor ip = image.getStack().getProcessor(slice);
            Rectangle bounds = clippedBounds(image, roi);
            double[] tmp = new double[Math.max(0, bounds.width * bounds.height)];
            int count = 0;
            for (int y = bounds.y; y < bounds.y + bounds.height; y++) {
                for (int x = bounds.x; x < bounds.x + bounds.width; x++) {
                    if (roi != null && !roi.contains(x, y)) continue;
                    double value = ip.getf(x, y);
                    if (Double.isNaN(value) || Double.isInfinite(value)) continue;
                    tmp[count++] = value;
                }
            }
            return new PixelValues(Arrays.copyOf(tmp, count), count);
        }
    }
}
