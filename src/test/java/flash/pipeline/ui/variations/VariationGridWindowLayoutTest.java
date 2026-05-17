package flash.pipeline.ui.variations;

import org.junit.Assume;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

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
}
