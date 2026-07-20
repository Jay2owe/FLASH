package flash.pipeline.spatial;

import ij.IJ;
import ij.ImagePlus;
import ij.process.FloatProcessor;

import java.io.File;

/**
 * Generates 2D Gaussian kernel density estimate (KDE) heatmaps from
 * object centroid coordinates.
 *
 * <p>Produces 32-bit float images with a configurable LUT. Supports
 * automatic bandwidth selection via Scott's rule or a user-specified
 * bandwidth in microns. Optionally saves TIFF and PNG.
 *
 * <p><strong>Boundary and weighting contract.</strong> A Gaussian is evaluated on
 * the square, discrete support extending {@code ceil(3 * bandwidth)} pixels from
 * its rounded centre. The support is clipped to the output image. A sample is
 * admitted only when that clipped support is non-empty; consequently a sample
 * whose entire support lies off-image cannot change any output pixel or the
 * normalisation. Clipped-away Gaussian mass is discarded rather than redistributed
 * over the image. The denominator is the number of admitted samples times the
 * full continuous Gaussian mass ({@code 2*pi*sigma^2}). In the weighted method,
 * each positive finite marker-expression weight scales only its numerator term;
 * it does not enter the denominator. This preserves amplitude semantics: scaling
 * every admitted expression value scales every output pixel by the same factor.
 * In symbols, the rule is
 * {@code sum(weight * exp(-r^2/(2*sigma^2))) / (admittedCount * 2*pi*sigma^2)},
 * with unit weights in the unweighted method. Automatic bandwidth selection uses
 * only eligible samples whose rounded centres are inside the image, so a wholly
 * unsupported outlier cannot alter the selected bandwidth.
 * If no sample support intersects the image, the documented empty result is
 * {@code null} and no pixel buffer is allocated.
 */
public final class DensityHeatmapGenerator {

    private DensityHeatmapGenerator() {}

    /**
     * Generates a KDE heatmap image.
     *
     * @param centroids  2D points [n][2] in micron coordinates
     * @param imgWidth   output image width in pixels
     * @param imgHeight  output image height in pixels
     * @param pixelSize  microns per pixel
     * @param bandwidth  kernel bandwidth in microns (0 or NaN = auto via Scott's rule)
     * @return 32-bit float ImagePlus, or null if no kernel support intersects the image
     */
    public static ImagePlus generate(double[][] centroids, int imgWidth, int imgHeight,
                                     double pixelSize, double bandwidth) {
        if (centroids == null || centroids.length == 0 || !validImageSize(imgWidth, imgHeight)) {
            return null;
        }

        if (!Double.isFinite(pixelSize) || pixelSize <= 0) pixelSize = 1.0;
        return generateInternal(centroids, null, imgWidth, imgHeight,
                pixelSize, bandwidth, "Density_Heatmap");
    }

    /**
     * Generates an intensity-weighted KDE heatmap where each centroid's
     * kernel is scaled by its associated value (e.g., marker expression).
     */
    public static ImagePlus generateWeighted(double[][] centroids, double[] weights,
                                             int imgWidth, int imgHeight,
                                             double pixelSize, double bandwidth) {
        if (centroids == null || centroids.length == 0 || weights == null) return null;
        if (centroids.length != weights.length) return null;
        if (!validImageSize(imgWidth, imgHeight)) return null;

        if (!Double.isFinite(pixelSize) || pixelSize <= 0) pixelSize = 1.0;
        return generateInternal(centroids, weights, imgWidth, imgHeight,
                pixelSize, bandwidth, "Weighted_Density_Heatmap");
    }

    private static ImagePlus generateInternal(double[][] centroids, double[] weights,
                                              int imgWidth, int imgHeight,
                                              double pixelSize, double bandwidth,
                                              String title) {
        double resolvedBandwidth = bandwidth;
        if (!Double.isFinite(resolvedBandwidth) || resolvedBandwidth <= 0) {
            // Centre-in-image samples define the automatic bandwidth. A sample with
            // wholly off-image support must not move the bandwidth and thereby alter
            // otherwise identical in-image pixels.
            resolvedBandwidth = scottsRuleWithinImage(
                    centroids, weights, imgWidth, imgHeight, pixelSize);
            if (!Double.isFinite(resolvedBandwidth) || resolvedBandwidth <= 0) {
                resolvedBandwidth = pixelSize * 10.0;
            }
        }

        double bwPx = resolvedBandwidth / pixelSize;
        if (!Double.isFinite(bwPx) || bwPx <= 0) bwPx = 10.0;
        long radius = kernelRadius(bwPx);

        // Establish admission before allocating the image. This makes an all-off-image
        // request a cheap, explicit empty result even for hostile finite coordinates.
        int admitted = 0;
        for (int i = 0; i < centroids.length; i++) {
            double weight = sampleWeight(weights, i);
            if (!validPoint(centroids[i]) || weight <= 0.0) continue;
            if (clippedSupport(centroids[i], pixelSize, radius, imgWidth, imgHeight) == null) continue;
            admitted++;
        }
        if (admitted == 0) return null;

        float[] pixels = new float[imgWidth * imgHeight];
        double invTwoSigmaSq = 1.0 / (2.0 * bwPx * bwPx);

        for (int i = 0; i < centroids.length; i++) {
            double weight = sampleWeight(weights, i);
            if (!validPoint(centroids[i]) || weight <= 0.0) continue;
            KernelSupport support = clippedSupport(
                    centroids[i], pixelSize, radius, imgWidth, imgHeight);
            if (support == null) continue;

            for (int y = support.yMin; y <= support.yMax; y++) {
                double dy = (double) y - (double) support.centerY;
                for (int x = support.xMin; x <= support.xMax; x++) {
                    double dx = (double) x - (double) support.centerX;
                    double kernel = Math.exp(-(dx * dx + dy * dy) * invTwoSigmaSq);
                    pixels[y * imgWidth + x] += (float) (kernel * weight);
                }
            }
        }

        double norm = admitted * 2.0 * Math.PI * bwPx * bwPx;
        if (norm > 0.0) {
            float floatNorm = (float) norm;
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] /= floatNorm;
            }
        }

        FloatProcessor fp = new FloatProcessor(imgWidth, imgHeight, pixels);
        ImagePlus imp = new ImagePlus(title, fp);
        ij.measure.Calibration cal = imp.getCalibration();
        cal.pixelWidth = pixelSize;
        cal.pixelHeight = pixelSize;
        cal.setUnit("um");
        return imp;
    }

    /**
     * Applies a named LUT to the heatmap image.
     * Supported: "Fire", "Grays", "Cyan", "Green", "Magenta", "Red", "Yellow".
     */
    public static void applyLut(ImagePlus imp, String lutName) {
        if (imp == null || lutName == null) return;
        try {
            IJ.run(imp, lutName, "");
        } catch (Exception e) {
            IJ.run(imp, "Fire", "");
        }
    }

    /**
     * Saves the heatmap as both TIFF and PNG.
     *
     * @param imp     heatmap image
     * @param outDir  output directory
     * @param baseName filename without extension
     */
    public static void saveHeatmap(ImagePlus imp, File outDir, String baseName) {
        if (imp == null || outDir == null) return;
        try {
            flash.pipeline.io.IoUtils.mustMkdirs(outDir);
        } catch (java.io.IOException e) {
            IJ.log("[DensityHeatmapGenerator] could not create " + outDir + ": " + e.getMessage());
            return;
        }

        // Auto-contrast for display
        imp.resetDisplayRange();

        String tiffPath = new File(outDir, baseName + ".tif").getAbsolutePath();
        IJ.saveAsTiff(imp, tiffPath);

        // For PNG, save a flattened RGB copy
        ImagePlus flat = imp.flatten();
        String pngPath = new File(outDir, baseName + ".png").getAbsolutePath();
        IJ.saveAs(flat, "PNG", pngPath);
        flat.close();
        flat.flush();
    }

    /**
     * Scott's rule for bandwidth selection: h = n^(-1/5) * sigma.
     * Uses the mean of X and Y standard deviations.
     */
    static double scottsRule(double[][] centroids) {
        if (centroids.length < 2) return 0;

        double sumX = 0, sumY = 0;
        int n = 0;
        for (double[] pt : centroids) {
            if (!validPoint(pt)) continue;
            sumX += pt[0];
            sumY += pt[1];
            n++;
        }
        if (n < 2) return 0;
        double meanX = sumX / n;
        double meanY = sumY / n;

        double varX = 0, varY = 0;
        for (double[] pt : centroids) {
            if (!validPoint(pt)) continue;
            varX += (pt[0] - meanX) * (pt[0] - meanX);
            varY += (pt[1] - meanY) * (pt[1] - meanY);
        }
        varX /= (n - 1);
        varY /= (n - 1);

        double sigma = (Math.sqrt(varX) + Math.sqrt(varY)) / 2.0;
        return Double.isFinite(sigma) ? Math.pow(n, -0.2) * sigma : 0;
    }

    private static double scottsRuleWithinImage(double[][] centroids, double[] weights,
                                                int imgWidth, int imgHeight,
                                                double pixelSize) {
        int n = 0;
        double meanX = 0.0;
        double meanY = 0.0;
        double m2X = 0.0;
        double m2Y = 0.0;
        for (int i = 0; i < centroids.length; i++) {
            double[] point = centroids[i];
            if (!validPoint(point) || sampleWeight(weights, i) <= 0.0) continue;
            long centerX = roundedPixel(point[0], pixelSize);
            long centerY = roundedPixel(point[1], pixelSize);
            if (centerX < 0L || centerX >= imgWidth || centerY < 0L || centerY >= imgHeight) continue;

            n++;
            double deltaX = point[0] - meanX;
            double deltaY = point[1] - meanY;
            meanX += deltaX / n;
            meanY += deltaY / n;
            m2X += deltaX * (point[0] - meanX);
            m2Y += deltaY * (point[1] - meanY);
        }
        if (n < 2 || !Double.isFinite(m2X) || !Double.isFinite(m2Y)) return 0.0;
        double sigma = (Math.sqrt(Math.max(0.0, m2X / (n - 1)))
                + Math.sqrt(Math.max(0.0, m2Y / (n - 1)))) / 2.0;
        double selected = Math.pow(n, -0.2) * sigma;
        return Double.isFinite(selected) ? selected : 0.0;
    }

    private static boolean validImageSize(int width, int height) {
        if (width <= 0 || height <= 0) return false;
        return (long) width * (long) height <= Integer.MAX_VALUE;
    }

    private static boolean validPoint(double[] point) {
        return point != null && point.length >= 2
                && Double.isFinite(point[0]) && Double.isFinite(point[1]);
    }

    private static double sampleWeight(double[] weights, int index) {
        if (weights == null) return 1.0;
        double weight = weights[index];
        return Double.isFinite(weight) && weight > 0.0 ? weight : 0.0;
    }

    private static KernelSupport clippedSupport(double[] point, double pixelSize, long radius,
                                                int imgWidth, int imgHeight) {
        long centerX = roundedPixel(point[0], pixelSize);
        long centerY = roundedPixel(point[1], pixelSize);

        // These comparisons avoid centre +/- radius overflow for extreme, but finite,
        // coordinates. Once admitted, all following arithmetic is within a few image widths.
        if (!axisIntersects(centerX, radius, imgWidth)
                || !axisIntersects(centerY, radius, imgHeight)) {
            return null;
        }

        int xMin = clippedMinimum(centerX, radius);
        int xMax = clippedMaximum(centerX, radius, imgWidth);
        int yMin = clippedMinimum(centerY, radius);
        int yMax = clippedMaximum(centerY, radius, imgHeight);
        return new KernelSupport(centerX, centerY, xMin, xMax, yMin, yMax);
    }

    private static boolean axisIntersects(long center, long radius, int size) {
        if (center < 0L) return center >= -radius;
        long maximum = size - 1L;
        return center <= maximum || center - maximum <= radius;
    }

    private static int clippedMinimum(long center, long radius) {
        if (center <= 0L || center <= radius) return 0;
        return (int) (center - radius);
    }

    private static int clippedMaximum(long center, long radius, int size) {
        long maximum = size - 1L;
        if (center < 0L) return (int) Math.min(maximum, center + radius);
        if (center >= maximum) return (int) maximum;
        if (radius >= maximum - center) return (int) maximum;
        return (int) (center + radius);
    }

    private static long roundedPixel(double coordinate, double pixelSize) {
        // Math.round saturates infinities produced by finite division at long bounds.
        // Keeping the result as long avoids the wrapping caused by narrowing to int.
        return Math.round(coordinate / pixelSize);
    }

    private static long kernelRadius(double bwPx) {
        double requested = Math.ceil(3.0 * bwPx);
        if (!Double.isFinite(requested) || requested >= Long.MAX_VALUE) return Long.MAX_VALUE;
        return Math.max(1L, (long) requested);
    }

    private static final class KernelSupport {
        private final long centerX;
        private final long centerY;
        private final int xMin;
        private final int xMax;
        private final int yMin;
        private final int yMax;

        private KernelSupport(long centerX, long centerY,
                              int xMin, int xMax, int yMin, int yMax) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.xMin = xMin;
            this.xMax = xMax;
            this.yMin = yMin;
            this.yMax = yMax;
        }
    }
}
