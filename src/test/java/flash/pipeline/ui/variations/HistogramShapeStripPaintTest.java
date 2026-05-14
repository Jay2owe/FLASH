package flash.pipeline.ui.variations;

import org.junit.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertTrue;

public class HistogramShapeStripPaintTest {

    @Test
    public void paintsPlateauBandAndWinnerMarker() {
        HistogramShapeStrip strip = new HistogramShapeStrip(
                new double[] { 0, 1, 2, 3, 4 },
                new int[][] {
                        histogram(0),
                        histogram(80),
                        histogram(200),
                        histogram(200),
                        histogram(200)
                },
                new int[] { 2, 4 },
                3);
        strip.setSize(500, 112);

        BufferedImage image = new BufferedImage(500, 112,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            strip.paint(g);
        } finally {
            g.dispose();
        }

        int plateau = image.getRGB(220, 22);
        assertTrue("Expected yellow plateau alpha at sampled pixel.",
                alpha(plateau) >= 28 && alpha(plateau) <= 38);
        assertTrue(red(plateau) > 200);
        assertTrue(green(plateau) > 180);
        assertTrue(blue(plateau) < 120);

        assertTrue("Expected an opaque yellow winner marker near tile 3.",
                hasOpaqueYellowPixel(image, 300, 390, 6, 30));
    }

    private static int[] histogram(int startBin) {
        int[] histogram = new int[256];
        int start = Math.max(0, Math.min(250, startBin));
        for (int i = 0; i < 6; i++) {
            histogram[start + i] = 100;
        }
        return histogram;
    }

    private static boolean hasOpaqueYellowPixel(BufferedImage image,
                                                int minX,
                                                int maxX,
                                                int minY,
                                                int maxY) {
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                int argb = image.getRGB(x, y);
                if (alpha(argb) > 180
                        && red(argb) > 200
                        && green(argb) > 180
                        && blue(argb) < 120) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int alpha(int argb) {
        return (argb >>> 24) & 0xff;
    }

    private static int red(int argb) {
        return (argb >>> 16) & 0xff;
    }

    private static int green(int argb) {
        return (argb >>> 8) & 0xff;
    }

    private static int blue(int argb) {
        return argb & 0xff;
    }
}
