package flash.pipeline.ui.variations;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Assume;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VariationGridWindowLayoutTest {

    @Test
    public void gridDimensionsAreSquareish() {
        assertGridDimensions(1, 1, 1);
        assertGridDimensions(2, 1, 2);
        assertGridDimensions(4, 2, 2);
        assertGridDimensions(5, 2, 3);
        assertGridDimensions(9, 3, 3);
        assertGridDimensions(16, 4, 4);
    }

    @Test
    public void cellsPreferSquareOverlayTileSize() {
        VariationCellPanel cell = new VariationCellPanel(
                ParameterCombo.builder().build(), null, null, null);
        assertEquals(new Dimension(260, 260), cell.getPreferredSize());
    }

    @Test
    public void gridWindowUsesSquareishDimensions() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        assertWindowGrid(4, 2, 2);
        assertWindowGrid(6, 2, 3);
        assertWindowGrid(9, 3, 3);
        assertWindowGrid(16, 4, 4);
    }

    @Test
    public void gridWindowCountsBaselineCell() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                List<VariationCellPanel> cells = cells(3);
                cells.add(0, VariationCellPanel.baseline(image()));
                VariationGridWindow window = new VariationGridWindow(
                        null, "FLASH variations", cells);
                try {
                    // The baseline cell carries a source crop, so the grid is
                    // sized to the image aspect for the live monitor; assert the
                    // baseline is counted and the tiles cover every cell rather
                    // than a fixed (monitor-dependent) row/column split.
                    GridLayout layout =
                            (GridLayout) window.gridPanelForTest().getLayout();
                    int rows = layout.getRows();
                    int cols = layout.getColumns();
                    assertEquals(4, window.cellsForTest().size());
                    assertTrue(rows >= 1 && cols >= 1);
                    assertTrue(rows * cols >= 4);
                    assertEquals(rows * cols,
                            window.gridPanelForTest().getComponentCount());
                } finally {
                    window.dispose();
                }
            }
        });
    }

    @Test
    public void optimalGridStacksWideImagesAndSplitsTallImages() {
        // A wide image (2 tiles, 4:1) on a square area stacks vertically...
        int[] wide = VariationGridWindow.optimalGrid(2, 1200, 1200, 4.0, 2, 2);
        assertEquals(2, wide[0]);
        assertEquals(1, wide[1]);
        // ...while a tall image (2 tiles, 1:4) splits side-by-side.
        int[] tall = VariationGridWindow.optimalGrid(2, 1200, 1200, 0.25, 2, 2);
        assertEquals(1, tall[0]);
        assertEquals(2, tall[1]);
    }

    @Test
    public void optimalGridFallsBackToSquareWhenInputsUnusable() {
        int[] noAspect = VariationGridWindow.optimalGrid(9, 1200, 1200, 0.0, 2, 2);
        assertArrayEquals(VariationGridWindow.gridDimensions(9), noAspect);
        int[] noArea = VariationGridWindow.optimalGrid(9, 0, 0, 1.0, 2, 2);
        assertArrayEquals(VariationGridWindow.gridDimensions(9), noArea);
    }

    private static void assertGridDimensions(int cells, int expectedRows,
                                             int expectedCols) {
        int[] dimensions = VariationGridWindow.gridDimensions(cells);
        assertEquals(expectedRows, dimensions[0]);
        assertEquals(expectedCols, dimensions[1]);
    }

    private static void assertWindowGrid(final int cells,
                                         final int expectedRows,
                                         final int expectedCols) throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                VariationGridWindow window = new VariationGridWindow(
                        null, "FLASH variations", cells(cells));
                try {
                    GridLayout layout =
                            (GridLayout) window.gridPanelForTest().getLayout();
                    assertEquals(expectedRows, layout.getRows());
                    assertEquals(expectedCols, layout.getColumns());
                } finally {
                    window.dispose();
                }
            }
        });
    }

    private static List<VariationCellPanel> cells(int count) {
        List<VariationCellPanel> cells = new ArrayList<VariationCellPanel>();
        for (int i = 0; i < count; i++) {
            cells.add(new VariationCellPanel(
                    ParameterCombo.builder().build(), null, null, null));
        }
        return cells;
    }

    private static ImagePlus image() {
        ImageStack stack = new ImageStack(4, 4);
        ByteProcessor processor = new ByteProcessor(4, 4);
        processor.setValue(7);
        processor.fill();
        stack.addSlice("z1", processor);
        return new ImagePlus("source", stack);
    }
}
