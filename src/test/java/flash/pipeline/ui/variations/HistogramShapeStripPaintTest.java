package flash.pipeline.ui.variations;

import flash.pipeline.ui.variations.analysis.HistogramShapeStability;

import org.junit.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class HistogramShapeStripPaintTest {

    @Test
    public void paintsPlateauBandAndWinnerMarker() {
        FilterParameterId sigma =
                new FilterParameterId(0, 0, 0, "Gaussian Blur", "sigma");
        List<VariationResult> results = new ArrayList<VariationResult>();
        results.add(result(sigma, 0.0d, migratingHistogram(0)));
        results.add(result(sigma, 1.0d, migratingHistogram(80)));
        results.add(result(sigma, 2.0d, migratingHistogram(160)));
        results.add(result(sigma, 3.0d, migratingHistogram(200)));
        results.add(result(sigma, 4.0d, migratingHistogram(200)));
        results.add(result(sigma, 5.0d, migratingHistogram(200)));
        HistogramShapeStability.Result stable =
                HistogramShapeStability.detect(results, sigma);
        assertTrue(stable.hasPlateau());

        HistogramShapeStrip strip = new HistogramShapeStrip();
        strip.setResult(stable);
        strip.setSize(240, 58);
        BufferedImage image = new BufferedImage(240, 58,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        strip.paint(g);
        g.dispose();

        assertNotNull(strip.resultForTest());
        Color background = sample(image, 2, 30);
        Color plateau = sample(image, 193, 30);
        assertTrue("plateau band should tint the dark background",
                plateau.getRed() > background.getRed()
                        && plateau.getGreen() > background.getGreen());

        assertTrue("winner marker should be yellow",
                hasYellowNear(image, 176, 18, 9));
    }

    @Test
    public void noPlateauResultKeepsStripHidden() {
        FilterParameterId sigma =
                new FilterParameterId(0, 0, 0, "Gaussian Blur", "sigma");
        int[] peaks = { 10, 200, 30, 220, 40, 180 };
        List<VariationResult> results = new ArrayList<VariationResult>();
        for (int i = 0; i < peaks.length; i++) {
            results.add(result(sigma, i, migratingHistogram(peaks[i])));
        }
        HistogramShapeStability.Result unstable =
                HistogramShapeStability.detect(results, sigma);
        assertFalse(unstable.hasPlateau());

        HistogramShapeStrip strip = new HistogramShapeStrip();
        strip.setResult(unstable);

        assertFalse(strip.isVisible());
    }

    private static VariationResult result(ParameterKey axis,
                                          double value,
                                          int[] histogram) {
        Map<ParameterKey, Object> values =
                new LinkedHashMap<ParameterKey, Object>();
        values.put(axis, Double.valueOf(value));
        ParameterCombo combo = new ParameterCombo(values);
        return VariationResult.filterSuccess(combo, null, 1L,
                histogram, 1.0d, 1.0d);
    }

    private static int[] migratingHistogram(int startBin) {
        int[] histogram = new int[256];
        int start = Math.max(0, Math.min(250, startBin));
        for (int i = 0; i < 6; i++) {
            histogram[start + i] = 100;
        }
        return histogram;
    }

    private static Color sample(BufferedImage image, int x, int y) {
        return new Color(image.getRGB(x, y), true);
    }

    private static boolean hasYellowNear(BufferedImage image,
                                         int centerX,
                                         int centerY,
                                         int radius) {
        int minX = Math.max(0, centerX - radius);
        int maxX = Math.min(image.getWidth() - 1, centerX + radius);
        int minY = Math.max(0, centerY - radius);
        int maxY = Math.min(image.getHeight() - 1, centerY + radius);
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                Color color = sample(image, x, y);
                if (color.getRed() > 200
                        && color.getGreen() > 180
                        && color.getBlue() < 120) {
                    return true;
                }
            }
        }
        return false;
    }
}
