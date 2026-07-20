package flash.pipeline.intensity.spatial;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.process.FloatProcessor;

/**
 * Deterministic synthetic images for final intensity-spatial validation tests.
 */
final class IntensitySpatialSyntheticFixtures {
    private IntensitySpatialSyntheticFixtures() {
    }

    static ImagePlus uniformImage(int width, int height, float value) {
        float[] pixels = new float[width * height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = value;
        }
        return image("uniform", width, height, pixels);
    }

    static ImagePlus checkerboardImage(int width, int height, int tileSize,
                                       float lowValue, float highValue) {
        float[] pixels = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean high = ((x / tileSize) + (y / tileSize)) % 2 == 0;
                pixels[y * width + x] = high ? highValue : lowValue;
            }
        }
        return image("checkerboard", width, height, pixels);
    }

    static ImagePlus gaussianHotspotImage(int width, int height,
                                          double centerX, double centerY,
                                          double sigma, double baseline,
                                          double peakValue) {
        float[] pixels = new float[width * height];
        double twoSigmaSq = 2.0 * sigma * sigma;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double dx = x - centerX;
                double dy = y - centerY;
                double value = baseline + peakValue * Math.exp(-(dx * dx + dy * dy) / twoSigmaSq);
                pixels[y * width + x] = (float) value;
            }
        }
        return image("hotspot", width, height, pixels);
    }

    static ImagePlus boundaryRimImage(int width, int height, int rimWidth,
                                      float rimValue, float coreValue) {
        float[] pixels = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int depth = Math.min(Math.min(x, y), Math.min(width - 1 - x, height - 1 - y));
                pixels[y * width + x] = depth < rimWidth ? rimValue : coreValue;
            }
        }
        return image("rim", width, height, pixels);
    }

    static ImagePlus boundaryGradientImage(int width, int height,
                                           float edgeValue, float slopePerPixel) {
        float[] pixels = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int depth = Math.min(Math.min(x, y), Math.min(width - 1 - x, height - 1 - y));
                pixels[y * width + x] = edgeValue + slopePerPixel * depth;
            }
        }
        return image("boundary-gradient", width, height, pixels);
    }

    static ImagePlus stripeImage(int width, int height,
                                 double orientationDegrees,
                                 double periodPixels) {
        float[] pixels = new float[width * height];
        double orientation = Math.toRadians(orientationDegrees);
        double normal = Math.PI / 2.0 - orientation;
        double cx = (width - 1) / 2.0;
        double cy = (height - 1) / 2.0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double coordinate = (x - cx) * Math.cos(normal) + (y - cy) * Math.sin(normal);
                pixels[y * width + x] = (float) (100.0
                        + 80.0 * Math.sin(2.0 * Math.PI * coordinate / periodPixels));
            }
        }
        return image("stripes", width, height, pixels);
    }

    static PairFixture colocatedPair(int width, int height) {
        ImagePlus source = gradientImage("source-coloc", width, height, false);
        ImagePlus partner = gradientImage("partner-coloc", width, height, false);
        ImagePlus sourceMask = thresholdMask("source-mask", source, 80.0);
        ImagePlus partnerMask = thresholdMask("partner-mask", partner, 80.0);
        return new PairFixture(source, partner, sourceMask, partnerMask);
    }

    static PairFixture antiCorrelatedPair(int width, int height) {
        ImagePlus source = gradientImage("source-anti", width, height, false);
        ImagePlus partner = gradientImage("partner-anti", width, height, true);
        ImagePlus sourceMask = thresholdMask("source-mask", source, 80.0);
        ImagePlus partnerMask = thresholdMask("partner-mask", partner, 80.0);
        return new PairFixture(source, partner, sourceMask, partnerMask);
    }

    static PairFixture shellGradientPair(int width, int height) {
        float[] source = new float[width * height];
        float[] partner = new float[width * height];
        float[] mask = new float[width * height];
        double cx = (width - 1) / 2.0;
        double cy = (height - 1) / 2.0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double radius = Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy));
                int index = y * width + x;
                source[index] = (float) (20.0 + radius * 4.0);
                partner[index] = radius <= 4.0 ? 255.0f : 0.0f;
                mask[index] = radius <= 4.0 ? 255.0f : 0.0f;
            }
        }
        return new PairFixture(
                image("shell-source", width, height, source),
                image("shell-partner", width, height, partner),
                null,
                image("shell-mask", width, height, mask));
    }

    static ImagePlus stackImage(String title, int width, int height, int depth,
                                SliceValue value) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            float[] pixels = new float[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y * width + x] = (float) value.at(x, y, z);
                }
            }
            stack.addSlice(new FloatProcessor(width, height, pixels, null));
        }
        return calibrate(new ImagePlus(title, stack));
    }

    static Roi outsideRoi() {
        return new Roi(1000, 1000, 3, 3);
    }

    static ImagePlus image(String title, int width, int height, float[] pixels) {
        return calibrate(new ImagePlus(title, new FloatProcessor(width, height, pixels, null)));
    }

    private static ImagePlus gradientImage(String title, int width, int height, boolean inverse) {
        float[] pixels = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float value = (float) (10.0 + x * 3.0 + y * 2.0);
                pixels[y * width + x] = inverse ? 255.0f - value : value;
            }
        }
        return image(title, width, height, pixels);
    }

    private static ImagePlus thresholdMask(String title, ImagePlus source, double threshold) {
        int width = source.getWidth();
        int height = source.getHeight();
        float[] pixels = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = source.getProcessor().getf(x, y) >= threshold ? 255.0f : 0.0f;
            }
        }
        return image(title, width, height, pixels);
    }

    private static ImagePlus calibrate(ImagePlus image) {
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 1.0;
        calibration.pixelHeight = 1.0;
        calibration.pixelDepth = 1.0;
        calibration.setUnit("um");
        image.setCalibration(calibration);
        return image;
    }

    interface SliceValue {
        double at(int x, int y, int z);
    }

    static final class PairFixture {
        final ImagePlus source;
        final ImagePlus partner;
        final ImagePlus sourceMask;
        final ImagePlus partnerMask;

        PairFixture(ImagePlus source, ImagePlus partner,
                    ImagePlus sourceMask, ImagePlus partnerMask) {
            this.source = source;
            this.partner = partner;
            this.sourceMask = sourceMask;
            this.partnerMask = partnerMask;
        }
    }
}
