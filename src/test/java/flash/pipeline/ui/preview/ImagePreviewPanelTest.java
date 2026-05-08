package flash.pipeline.ui.preview;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ImagePreviewPanelTest {

    @Test
    public void emptyPanelUsesSafeDefaults() {
        ImagePreviewPanel panel = new ImagePreviewPanel("Preview");

        assertFalse(panel.hasImageForTest());
        assertEquals(1, panel.getCurrentZ());
        assertEquals(1, panel.getSliceCount());
        assertFalse(panel.isZSliderEnabledForTest());
        assertEquals("No image selected.", panel.titleTextForTest());
    }

    @Test
    public void setImagePreservesAndClampsCurrentZ() {
        ImagePreviewPanel panel = new ImagePreviewPanel("Preview");
        panel.setImage(stack("five", 5));
        panel.setCurrentZ(4);

        panel.setImage(stack("two", 2));

        assertTrue(panel.hasImageForTest());
        assertEquals(2, panel.getCurrentZ());
        assertEquals(2, panel.getSliceCount());
        assertTrue(panel.isZSliderEnabledForTest());
        assertEquals("two", panel.titleTextForTest());
    }

    @Test
    public void singleSliceImageDisablesZSlider() {
        ImagePreviewPanel panel = new ImagePreviewPanel("Preview");

        panel.setImage(stack("single", 1));
        panel.setCurrentZ(9);

        assertEquals(1, panel.getCurrentZ());
        assertEquals(1, panel.getSliceCount());
        assertFalse(panel.isZSliderEnabledForTest());
    }

    @Test
    public void statusTextCanShowRunningAndErrorStates() {
        ImagePreviewPanel panel = new ImagePreviewPanel("Preview");

        panel.setStatusText("Generating preview...");

        assertEquals("Generating preview...", panel.statusTextForTest());
    }

    private static ImagePlus stack(String title, int slices) {
        ImageStack stack = new ImageStack(3, 3);
        for (int i = 0; i < slices; i++) {
            ByteProcessor processor = new ByteProcessor(3, 3);
            processor.set(1, 1, i + 1);
            stack.addSlice(processor);
        }
        return new ImagePlus(title, stack);
    }
}
