package flash.pipeline.ui.variations;

import org.junit.Assume;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VariationGridWindowProgressTest {

    @Test
    public void setCompletedCountUpdatesProgressAndStatus() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                VariationGridWindow window = new VariationGridWindow(
                        null, "FLASH variations", cells(4));
                try {
                    window.setCompletedCount(2, 4, 0);
                    assertEquals(2, window.progressBarForTest().getValue());
                    assertEquals("Slice 1 / 1  |  Variants: 4  |  2/4 complete",
                            window.statusLabelForTest().getText());
                } finally {
                    window.dispose();
                }
            }
        });
    }

    @Test
    public void pickSelectedButtonCanBeEnabledAndClicked() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                VariationGridWindow window = new VariationGridWindow(
                        null, "FLASH variations", cells(1));
                try {
                    final boolean[] fired = new boolean[] { false };
                    window.setPickSelectedEnabled(true);
                    assertTrue(window.pickSelectedButtonForTest().isEnabled());
                    window.attachPickSelectedActionListener(new ActionListener() {
                        @Override public void actionPerformed(ActionEvent e) {
                            fired[0] = true;
                        }
                    });

                    window.pickSelectedButtonForTest().doClick();

                    assertTrue(fired[0]);
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
