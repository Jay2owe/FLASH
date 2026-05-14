package flash.pipeline.ui.variations;

import org.junit.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.OptionalInt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CountCurveScalingTest {

    @Test
    public void plateauBandUsesLowAlphaYellow() {
        CountCurveStrip strip = new CountCurveStrip(
                new double[] { 0, 1, 2, 3 },
                new double[] { 20, 18, 18, 18 },
                OptionalInt.of(2),
                new int[] { 1, 3 });
        strip.setSize(500, 120);

        BufferedImage image = new BufferedImage(500, 120,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            strip.paint(g);
        } finally {
            g.dispose();
        }

        int plateau = image.getRGB(250, 32);
        assertTrue("Expected plateau alpha around 33.",
                alpha(plateau) >= 28 && alpha(plateau) <= 38);
        assertTrue(red(plateau) > 200);
        assertTrue(green(plateau) > 180);
        assertTrue(blue(plateau) < 120);
    }

    @Test
    public void miniCurvesShareYMaximum() {
        CountCurveMini first = new CountCurveMini(
                new double[] { 0, 1, 2 },
                new double[] { 3, 6, 9 },
                OptionalInt.empty(),
                null,
                25.0d);
        CountCurveMini second = new CountCurveMini(
                new double[] { 0, 1, 2 },
                new double[] { 1, 2, 4 },
                OptionalInt.empty(),
                null,
                25.0d);

        assertEquals(25.0d, first.yMax(), 0.0001d);
        assertEquals(25.0d, second.yMax(), 0.0001d);
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
