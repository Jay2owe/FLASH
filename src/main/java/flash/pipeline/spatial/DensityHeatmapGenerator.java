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
     * @return 32-bit float ImagePlus, or null if no points
     */
    public static ImagePlus generate(double[][] centroids, int imgWidth, int imgHeight,
                                     double pixelSize, double bandwidth) {
        if (centroids == null || centroids.length == 0 || imgWidth <= 0 || imgHeight <= 0) {
            return null;
        }

        if (pixelSize <= 0) pixelSize = 1.0;

        // Auto bandwidth via Scott's rule: h = n^(-1/5) * sigma
        if (bandwidth <= 0 || Double.isNaN(bandwidth)) {
            bandwidth = scottsRule(centroids);
            if (bandwidth <= 0) bandwidth = pixelSize * 10; // fallback
        }

        double bwPx = bandwidth / pixelSize;
        int kernelRadius = (int) Math.ceil(3.0 * bwPx);

        float[] pixels = new float[imgWidth * imgHeight];

        // Stamp Gaussian kernel at each centroid
        for (double[] pt : centroids) {
            int cx = (int) Math.round(pt[0] / pixelSize);
            int cy = (int) Math.round(pt[1] / pixelSize);

            int yMin = Math.max(0, cy - kernelRadius);
            int yMax = Math.min(imgHeight - 1, cy + kernelRadius);
            int xMin = Math.max(0, cx - kernelRadius);
            int xMax = Math.min(imgWidth - 1, cx + kernelRadius);

            double invTwoSigmaSq = 1.0 / (2.0 * bwPx * bwPx);
            for (int y = yMin; y <= yMax; y++) {
                double dy = y - cy;
                for (int x = xMin; x <= xMax; x++) {
                    double dx = x - cx;
                    double weight = Math.exp(-(dx * dx + dy * dy) * invTwoSigmaSq);
                    pixels[y * imgWidth + x] += (float) weight;
                }
            }
        }

        // Normalize by n and kernel normalization constant
        double norm = centroids.length * 2.0 * Math.PI * bwPx * bwPx;
        if (norm > 0) {
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] /= (float) norm;
            }
        }

        FloatProcessor fp = new FloatProcessor(imgWidth, imgHeight, pixels);
        ImagePlus imp = new ImagePlus("Density_Heatmap", fp);

        // Set calibration
        ij.measure.Calibration cal = imp.getCalibration();
        cal.pixelWidth = pixelSize;
        cal.pixelHeight = pixelSize;
        cal.setUnit("um");

        return imp;
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

        if (pixelSize <= 0) pixelSize = 1.0;
        if (bandwidth <= 0 || Double.isNaN(bandwidth)) {
            bandwidth = scottsRule(centroids);
            if (bandwidth <= 0) bandwidth = pixelSize * 10;
        }

        double bwPx = bandwidth / pixelSize;
        int kernelRadius = (int) Math.ceil(3.0 * bwPx);
        float[] pixels = new float[imgWidth * imgHeight];

        for (int p = 0; p < centroids.length; p++) {
            double w = weights[p];
            if (Double.isNaN(w) || w <= 0) continue;

            int cx = (int) Math.round(centroids[p][0] / pixelSize);
            int cy = (int) Math.round(centroids[p][1] / pixelSize);

            int yMin = Math.max(0, cy - kernelRadius);
            int yMax = Math.min(imgHeight - 1, cy + kernelRadius);
            int xMin = Math.max(0, cx - kernelRadius);
            int xMax = Math.min(imgWidth - 1, cx + kernelRadius);

            double invTwoSigmaSq = 1.0 / (2.0 * bwPx * bwPx);
            for (int y = yMin; y <= yMax; y++) {
                double dy = y - cy;
                for (int x = xMin; x <= xMax; x++) {
                    double dx = x - cx;
                    double kernel = Math.exp(-(dx * dx + dy * dy) * invTwoSigmaSq);
                    pixels[y * imgWidth + x] += (float) (kernel * w);
                }
            }
        }

        double norm = centroids.length * 2.0 * Math.PI * bwPx * bwPx;
        if (norm > 0) {
            for (int i = 0; i < pixels.length; i++) pixels[i] /= (float) norm;
        }

        FloatProcessor fp = new FloatProcessor(imgWidth, imgHeight, pixels);
        ImagePlus imp = new ImagePlus("Weighted_Density_Heatmap", fp);
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
        for (double[] pt : centroids) { sumX += pt[0]; sumY += pt[1]; }
        double meanX = sumX / centroids.length;
        double meanY = sumY / centroids.length;

        double varX = 0, varY = 0;
        for (double[] pt : centroids) {
            varX += (pt[0] - meanX) * (pt[0] - meanX);
            varY += (pt[1] - meanY) * (pt[1] - meanY);
        }
        varX /= (centroids.length - 1);
        varY /= (centroids.length - 1);

        double sigma = (Math.sqrt(varX) + Math.sqrt(varY)) / 2.0;
        return Math.pow(centroids.length, -0.2) * sigma;
    }
}
