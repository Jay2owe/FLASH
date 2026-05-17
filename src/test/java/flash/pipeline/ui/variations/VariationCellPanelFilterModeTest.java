package flash.pipeline.ui.variations;

import ij.ImagePlus;
import ij.process.ByteProcessor;

import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

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
        assertEquals("SNR 6.8", cell.filterSnrTextForTest());
        assertEquals("bg \u03c3 14", cell.filterBgSigmaTextForTest());
        assertFalse(cell.filterChipVisibleForTest());
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
        assertTrue(cell.filterChipVisibleForTest());
        assertEquals("Median radius=2, Subtract Background rolling=50",
                cell.filterChipTextForTest());
    }

    @Test
    public void pickPillAppearsForAcceptEnabledCellBeforeHover() throws Exception {
        final ParameterCombo combo = ParameterCombo.builder().build();
        final ImagePlus source = image("source", 3);
        final ImagePlus filtered = image("filtered", 9);
        final VariationCellPanel cell = new VariationCellPanel(combo, source,
                null, null);
        final VariationResult result = VariationResult.filterSuccess(combo,
                filtered, 12L, new int[256], 6.8d, 14.3d);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                cell.setResult(result);
                assertTrue(cell.isPickPillVisibleForTest());
                mouseEnter(cell);
            }
        });

        assertTrue(cell.isPickPillVisibleForTest());
        assertTrue(cell.getToolTipText().contains("Click to pick this combo"));
    }

    private static void mouseEnter(VariationCellPanel cell) {
        MouseEvent event = new MouseEvent(cell, MouseEvent.MOUSE_ENTERED,
                System.currentTimeMillis(), 0, 4, 4, 0, false);
        MouseListener[] listeners = cell.getMouseListeners();
        for (int i = 0; i < listeners.length; i++) {
            listeners[i].mouseEntered(event);
        }
    }

    private static ImagePlus image(String title, int value) {
        ByteProcessor processor = new ByteProcessor(4, 4);
        processor.setValue(value);
        processor.fill();
        return new ImagePlus(title, processor);
    }
}
