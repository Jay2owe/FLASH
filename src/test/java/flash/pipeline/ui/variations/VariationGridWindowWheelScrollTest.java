package flash.pipeline.ui.variations;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Assume;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class VariationGridWindowWheelScrollTest {

    @Test
    public void mouseWheelMovesSlider() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                VariationGridWindow window = new VariationGridWindow(
                        null, "FLASH variations", cells(4, 5));
                try {
                    assertEquals(1, window.zSliderForTest().getValue());
                    assertEquals("1 / 5",
                            window.zSliceLabelForTest().getText());
                    MouseWheelEvent event = new MouseWheelEvent(
                            window.gridPanelForTest(),
                            MouseWheelEvent.MOUSE_WHEEL,
                            System.currentTimeMillis(),
                            0,
                            10,
                            10,
                            0,
                            false,
                            MouseWheelEvent.WHEEL_UNIT_SCROLL,
                            1,
                            1);
                    MouseWheelListener[] listeners =
                            window.gridPanelForTest().getMouseWheelListeners();
                    for (int i = 0; i < listeners.length; i++) {
                        listeners[i].mouseWheelMoved(event);
                    }
                    assertEquals(2, window.zSliderForTest().getValue());
                    assertEquals("2 / 5",
                            window.zSliceLabelForTest().getText());
                } finally {
                    window.dispose();
                }
            }
        });
    }

    private static List<VariationCellPanel> cells(int count, int slices) {
        List<VariationCellPanel> cells = new ArrayList<VariationCellPanel>();
        for (int i = 0; i < count; i++) {
            VariationCellPanel cell = new VariationCellPanel(
                    ParameterCombo.builder().build(), null, null, null);
            cell.preview().setImage(stack(slices));
            cells.add(cell);
        }
        return cells;
    }

    private static ImagePlus stack(int slices) {
        ImageStack stack = new ImageStack(8, 8);
        for (int z = 0; z < slices; z++) {
            ByteProcessor processor = new ByteProcessor(8, 8);
            processor.setValue(z + 1);
            processor.fill();
            stack.addSlice("z" + (z + 1), processor);
        }
        ImagePlus image = new ImagePlus("stack-" + slices, stack);
        image.setDimensions(1, slices, 1);
        return image;
    }
}
