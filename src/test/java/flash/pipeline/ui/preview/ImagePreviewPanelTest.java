package flash.pipeline.ui.preview;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;
import org.junit.Test;

import java.awt.image.IndexColorModel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
    public void singleChannelFramesAreBrowsableAsZPlanes() {
        ImagePreviewPanel panel = new ImagePreviewPanel("Preview");
        panel.setImage(timeStack("time-coded z", 4));

        panel.setCurrentZ(4);

        assertEquals(4, panel.getCurrentZ());
        assertEquals(4, panel.getSliceCount());
        assertTrue(panel.isZSliderEnabledForTest());
        assertEquals(4, panel.renderedProcessorForTest().get(1, 1));
    }

    @Test
    public void statusTextCanShowRunningAndErrorStates() {
        ImagePreviewPanel panel = new ImagePreviewPanel("Preview");

        panel.setStatusText("Generating preview...");

        assertEquals("Generating preview...", panel.statusTextForTest());
    }

    @Test
    public void transientDisplaySettingsAffectRenderedCopyOnly() {
        ImagePlus image = stack("source", 1);
        image.setDisplayRange(0.0, 255.0);
        ImagePreviewPanel panel = new ImagePreviewPanel("Preview");
        panel.setImage(image);

        panel.setDisplaySettings(PreviewDisplaySettings.of(
                20.0, 80.0, PreviewDisplaySettings.LutMode.CHANNEL, "Red"));

        ImageProcessor rendered = panel.renderedProcessorForTest();
        assertEquals(20.0, rendered.getMin(), 0.0001);
        assertEquals(80.0, rendered.getMax(), 0.0001);
        assertEquals(0.0, image.getDisplayRangeMin(), 0.0001);
        assertEquals(255.0, image.getDisplayRangeMax(), 0.0001);

        IndexColorModel model = (IndexColorModel) rendered.getColorModel();
        assertEquals(255, model.getRed(255));
        assertEquals(0, model.getGreen(255));
        assertEquals(0, model.getBlue(255));
    }

    @Test
    public void temporaryLutDoesNotRecolorRgbObjectPreview() {
        ColorProcessor processor = new ColorProcessor(2, 1);
        processor.set(0, 0, 0x00ff00);
        processor.set(1, 0, 0xff0000);
        ImagePreviewPanel panel = new ImagePreviewPanel("Preview");
        panel.setImage(new ImagePlus("rgb", processor));

        panel.setDisplaySettings(PreviewDisplaySettings.of(
                1000.0, 2000.0, PreviewDisplaySettings.LutMode.CHANNEL, "Blue"));

        ImageProcessor rendered = panel.renderedProcessorForTest();
        assertTrue(rendered instanceof ColorProcessor);
        assertEquals(0x00ff00, rendered.getPixel(0, 0) & 0x00ffffff);
        assertEquals(0xff0000, rendered.getPixel(1, 0) & 0x00ffffff);
        assertEquals(0.0, rendered.getMin(), 0.0001);
        assertEquals(255.0, rendered.getMax(), 0.0001);
    }

    @Test
    public void disabledTransientDisplaySettingsUseImageNativeDisplay() {
        ImagePlus labels = labelImage("labels", 5);
        LabelMapStyler.apply(labels, 5);
        ImagePreviewPanel panel = new ImagePreviewPanel("Preview");
        panel.setImage(labels);
        panel.setDisplaySettings(PreviewDisplaySettings.of(
                20.0, 80.0, PreviewDisplaySettings.LutMode.CHANNEL, "Red"));

        panel.setDisplaySettingsEnabled(false);

        ImageProcessor rendered = panel.renderedProcessorForTest();
        assertEquals(0.0, rendered.getMin(), 0.0001);
        assertEquals(5.0, rendered.getMax(), 0.0001);
        IndexColorModel model = (IndexColorModel) rendered.getColorModel();
        int expected = LabelMapStyler.rgbForLabel(5);
        assertEquals((expected >> 16) & 0xff, model.getRed(5));
        assertEquals((expected >> 8) & 0xff, model.getGreen(5));
        assertEquals(expected & 0xff, model.getBlue(5));
    }

    @Test
    public void closedImageDoesNotThrowDuringRender() {
        ImagePlus image = new ClosedImagePlus();
        ImagePreviewPanel panel = new ImagePreviewPanel("Preview");

        panel.setImage(image);

        assertFalse(panel.hasImageForTest());
        assertEquals(1, panel.getSliceCount());
        assertNull(panel.renderedProcessorForTest());
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

    private static ImagePlus timeStack(String title, int frames) {
        ImagePlus image = stack(title, frames);
        image.setDimensions(1, 1, frames);
        return image;
    }

    private static ImagePlus labelImage(String title, int label) {
        ByteProcessor processor = new ByteProcessor(2, 1);
        processor.set(0, 0, 0);
        processor.set(1, 0, label);
        return new ImagePlus(title, processor);
    }

    private static final class ClosedImagePlus extends ImagePlus {
        @Override public ImageStack getStack() {
            throw new IllegalArgumentException("closed");
        }

        @Override public int getStackSize() {
            throw new IllegalArgumentException("closed");
        }
    }
}
