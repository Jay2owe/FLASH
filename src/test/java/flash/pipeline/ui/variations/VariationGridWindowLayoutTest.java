package flash.pipeline.ui.variations;

import org.junit.Assume;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class VariationGridWindowLayoutTest {

    @Test
    public void gridDimensionsAreSquareish() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        assertGrid(4, 2, 2);
        assertGrid(6, 2, 3);
        assertGrid(9, 3, 3);
        assertGrid(16, 4, 4);
    }

    private static void assertGrid(final int cells,
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
