package flash.pipeline.ui.variations;

import ij.ImagePlus;
import ij.process.ByteProcessor;

import org.junit.Test;

import javax.swing.SwingUtilities;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

public class VariationCellPanelFilterModeTest {

    @Test
    public void filterResultDisplaysFilteredImageAndMetricFooter() throws Exception {
        final ParameterCombo combo = ParameterCombo.builder().build();
        final ImagePlus source = image("source", 3);
        final ImagePlus filtered = image("filtered", 9);
        final VariationCellPanel cell = new VariationCellPanel(combo, source, null, null);
        final VariationResult result = VariationResult.filterSuccess(combo, filtered,
                12L, new int[256], 6.8d, 14.3d);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                cell.setResult(result);
            }
        });

        assertArrayEquals(new String[] { "SNR 6.8", "bg \u03c3 14" },
                cell.footerLinesForTest());
        assertSame(filtered, cell.currentPreviewImageForTest());
        assertFalse(cell.hasCachedLabel());
    }

    private static ImagePlus image(String title, int value) {
        ByteProcessor processor = new ByteProcessor(4, 4);
        processor.setValue(value);
        processor.fill();
        return new ImagePlus(title, processor);
    }
}
