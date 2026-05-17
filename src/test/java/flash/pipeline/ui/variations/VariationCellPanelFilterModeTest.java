package flash.pipeline.ui.variations;

import ij.ImagePlus;
import ij.process.ByteProcessor;

import org.junit.Test;

import javax.swing.SwingUtilities;
import java.util.Arrays;

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

    @Test
    public void filterResultDisplaysSweptParameterValuesAboveMetrics() throws Exception {
        final FilterParameterId radius = new FilterParameterId(
                0, 0, 0, "Median...", "radius");
        final FilterParameterId rolling = new FilterParameterId(
                0, 1, 0, "Subtract Background...", "rolling");
        final ParameterCombo combo = ParameterCombo.builder()
                .put(radius, Integer.valueOf(2))
                .put(rolling, Integer.valueOf(50))
                .build();
        final ImagePlus source = image("source", 3);
        final ImagePlus filtered = image("filtered", 9);
        final VariationCellPanel cell = new VariationCellPanel(combo, source, null, null);
        final VariationResult result = VariationResult.filterSuccess(combo, filtered,
                12L, new int[256], 6.8d, 14.3d);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                cell.setFooterParameterKeys(Arrays.<ParameterKey>asList(radius, rolling));
                cell.setResult(result);
            }
        });

        assertArrayEquals(new String[] {
                "Median radius=2, Subtract Background rolling=50",
                "SNR 6.8",
                "bg \u03c3 14"
        }, cell.footerLinesForTest());
    }

    private static ImagePlus image(String title, int value) {
        ByteProcessor processor = new ByteProcessor(4, 4);
        processor.setValue(value);
        processor.fill();
        return new ImagePlus(title, processor);
    }
}
