package flash.pipeline.presentation;

import java.awt.image.BufferedImage;

/**
 * Resizes display-rendered panels for assembled figures. Shrinking averages
 * every source pixel by the area it covers, so small bright structures keep
 * their true share of light instead of appearing or vanishing depending on
 * which pixel a bilinear sample happens to hit. Enlarging repeats pixels,
 * so no intermediate intensities are invented.
 */
public final class AreaResample {

    private AreaResample() {}

    /**
     * Draws {@code image} into the given box after resizing it to exactly the
     * device pixels the box covers under the current transform.
     */
    public static void drawFitted(java.awt.Graphics2D g, BufferedImage image,
                                  int x, int y, int width, int height) {
        java.awt.geom.AffineTransform transform = g.getTransform();
        int deviceWidth = Math.max(1, (int) Math.round(
                width * Math.abs(transform.getScaleX())));
        int deviceHeight = Math.max(1, (int) Math.round(
                height * Math.abs(transform.getScaleY())));
        g.drawImage(toSize(image, deviceWidth, deviceHeight), x, y, width, height, null);
    }

    public static BufferedImage toSize(BufferedImage source, int width, int height) {
        if (source == null) throw new IllegalArgumentException("source is null");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("target size must be positive");
        }
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        int[] pixels = source.getRGB(0, 0, sourceWidth, sourceHeight, null, 0,
                sourceWidth);
        if (sourceWidth == width && sourceHeight == height) {
            BufferedImage copy = new BufferedImage(width, height,
                    BufferedImage.TYPE_INT_RGB);
            copy.setRGB(0, 0, width, height, pixels, 0, width);
            return copy;
        }
        Axis xAxis = Axis.of(sourceWidth, width);
        Axis yAxis = Axis.of(sourceHeight, height);

        // Horizontal pass: sourceHeight rows of `width` weighted sums per channel.
        float[] red = new float[width * sourceHeight];
        float[] green = new float[red.length];
        float[] blue = new float[red.length];
        for (int y = 0; y < sourceHeight; y++) {
            int rowStart = y * sourceWidth;
            int outStart = y * width;
            for (int x = 0; x < width; x++) {
                float r = 0f;
                float g = 0f;
                float b = 0f;
                int start = xAxis.start[x];
                float[] weights = xAxis.weights[x];
                for (int k = 0; k < weights.length; k++) {
                    int argb = pixels[rowStart + start + k];
                    float w = weights[k];
                    r += ((argb >> 16) & 0xFF) * w;
                    g += ((argb >> 8) & 0xFF) * w;
                    b += (argb & 0xFF) * w;
                }
                red[outStart + x] = r;
                green[outStart + x] = g;
                blue[outStart + x] = b;
            }
        }

        int[] out = new int[width * height];
        for (int y = 0; y < height; y++) {
            int start = yAxis.start[y];
            float[] weights = yAxis.weights[y];
            for (int x = 0; x < width; x++) {
                float r = 0f;
                float g = 0f;
                float b = 0f;
                for (int k = 0; k < weights.length; k++) {
                    int index = (start + k) * width + x;
                    float w = weights[k];
                    r += red[index] * w;
                    g += green[index] * w;
                    b += blue[index] * w;
                }
                out[y * width + x] = (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
            }
        }
        BufferedImage image = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, width, height, out, 0, width);
        return image;
    }

    private static int clamp(float value) {
        int rounded = Math.round(value);
        return rounded < 0 ? 0 : rounded > 255 ? 255 : rounded;
    }

    /** Source span and normalised coverage weights for every output pixel on one axis. */
    private static final class Axis {
        final int[] start;
        final float[][] weights;

        private Axis(int[] start, float[][] weights) {
            this.start = start;
            this.weights = weights;
        }

        static Axis of(int sourceLength, int targetLength) {
            int[] start = new int[targetLength];
            float[][] weights = new float[targetLength][];
            double scale = sourceLength / (double) targetLength;
            for (int i = 0; i < targetLength; i++) {
                if (scale <= 1.0) {
                    int nearest = (int) Math.floor((i + 0.5) * scale);
                    start[i] = Math.min(sourceLength - 1, Math.max(0, nearest));
                    weights[i] = new float[] { 1f };
                    continue;
                }
                double from = i * scale;
                double to = Math.min(sourceLength, (i + 1) * scale);
                int first = (int) Math.floor(from);
                int last = Math.min(sourceLength - 1, (int) Math.ceil(to) - 1);
                float[] w = new float[last - first + 1];
                for (int s = first; s <= last; s++) {
                    double overlap = Math.min(to, s + 1.0) - Math.max(from, s);
                    w[s - first] = (float) (Math.max(0.0, overlap) / (to - from));
                }
                start[i] = first;
                weights[i] = w;
            }
            return new Axis(start, weights);
        }
    }
}
