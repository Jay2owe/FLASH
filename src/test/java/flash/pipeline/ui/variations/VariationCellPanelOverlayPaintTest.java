package flash.pipeline.ui.variations;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

import org.junit.Test;

import javax.swing.SwingUtilities;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

public class VariationCellPanelOverlayPaintTest {

    @Test
    public void otsuOverlayRendersRedTintedForeground() throws Exception {
        final ParameterCombo combo = ParameterCombo.builder().build();
        final ImagePlus source = image();
        final ImagePlus filtered = image();
        final VariationResult result = VariationResult.filterSuccess(combo,
                filtered, 8L, new int[256], 4.0d, 2.0d);
        final VariationCellPanel cell = new VariationCellPanel(combo, source,
                null, null);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                cell.setOverlayMode(VariationCellPanel.OverlayMode.OTSU_MASK);
                cell.setResult(result);
            }
        });

        ImagePlus rendered = cell.currentPreviewImageForTest();
        assertNotSame(filtered, rendered);
        assertTrue("Otsu threshold should exclude the background peak.",
                cell.cachedOtsuLowerForTest() > 20.0d);

        ImageProcessor processor = rendered.getProcessor();
        int foreground = processor.get(16, 16);
        assertTrue(redTinted(foreground));
        assertFalse(redTinted(processor.get(1, 1)));
    }

    private static boolean redTinted(int rgb) {
        int r = (rgb >> 16) & 0xff;
        int g = (rgb >> 8) & 0xff;
        int b = rgb & 0xff;
        return r > g && r > b && r > 160;
    }

    private static ImagePlus image() {
        ByteProcessor processor = new ByteProcessor(32, 32);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                processor.set(x, y,
                        x >= 10 && x < 24 && y >= 10 && y < 24 ? 220 : 20);
            }
        }
        return new ImagePlus("filtered", processor);
    }
}
