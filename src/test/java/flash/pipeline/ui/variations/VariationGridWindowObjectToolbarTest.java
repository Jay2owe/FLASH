package flash.pipeline.ui.variations;

import org.junit.Assume;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VariationGridWindowObjectToolbarTest {

    @Test
    public void objectModeShowsOverlayControlsAndHidesOtsu() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                VariationGridWindow window = new VariationGridWindow(
                        null, "FLASH variations", cells(2), true);
                try {
                    assertTrue(window.toolBarForTest()
                            .isAncestorOf(window.objectOverlayCheckBoxForTest()));
                    assertTrue(window.toolBarForTest()
                            .isAncestorOf(window.objectOverlaySourceChoiceForTest()));
                    assertTrue(window.toolBarForTest()
                            .isAncestorOf(window.lutToggleButtonForTest()));
                    assertTrue(window.toolBarForTest()
                            .isAncestorOf(window.brightnessButtonForTest()));
                    // The Otsu control belongs to filter variations only.
                    assertFalse(window.toolBarForTest()
                            .isAncestorOf(window.otsuOverlayCheckBoxForTest()));
                    // Overlay is on by default; its source picker follows it.
                    assertTrue(window.isObjectOverlaySelected());
                } finally {
                    window.dispose();
                }
            }
        });
    }

    @Test
    public void filterModeStillShowsOtsuAndHidesObjectControls() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                VariationGridWindow window = new VariationGridWindow(
                        null, "FLASH variations", cells(2));
                try {
                    assertTrue(window.toolBarForTest()
                            .isAncestorOf(window.otsuOverlayCheckBoxForTest()));
                    assertFalse(window.toolBarForTest()
                            .isAncestorOf(window.objectOverlayCheckBoxForTest()));
                    assertFalse(window.toolBarForTest()
                            .isAncestorOf(window.brightnessButtonForTest()));
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
