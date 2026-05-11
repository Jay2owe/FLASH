package flash.pipeline.ui.preview;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import org.junit.Test;

import java.awt.GraphicsEnvironment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

public class LargePreviewDialogTest {

    @Test
    public void objectPaneCanShowFilteredOrRawOverlay() {
        assumeFalse(GraphicsEnvironment.isHeadless());

        LargePreviewDialog dialog = new LargePreviewDialog(null);
        try {
            dialog.setImages(
                    image("raw"),
                    image("filtered"),
                    labels("Object labels"),
                    1);

            assertTrue(dialog.overlayControlsVisibleForTest());
            assertEquals("Object labels", dialog.extraPreviewTitleForTest());

            dialog.setOverlaySelectedForTest(true);

            assertEquals("Object overlay | filtered", dialog.extraPreviewTitleForTest());

            dialog.setOverlaySourceForTest("Raw image");

            assertEquals("Object overlay | raw", dialog.extraPreviewTitleForTest());
        } finally {
            dialog.dispose();
        }
    }

    @Test
    public void objectOverlayUsesDisplayRangeForUnderlyingSource() {
        assumeFalse(GraphicsEnvironment.isHeadless());

        LargePreviewDialog dialog = new LargePreviewDialog(null);
        try {
            dialog.setImages(
                    uniformImage("raw", 100),
                    uniformImage("filtered", 100),
                    labels("Object labels"),
                    1);

            dialog.setOverlaySelectedForTest(true);
            dialog.setDisplaySettings(PreviewDisplaySettings.of(
                    100.0,
                    200.0,
                    PreviewDisplaySettings.LutMode.GREY,
                    "Grays"));

            ImageProcessor rendered = dialog.extraPreviewRenderedProcessorForTest();
            assertEquals(blend(0x000000, LabelMapStyler.rgbForLabel(1), 0.35),
                    rendered.getPixel(0, 0) & 0xffffff);
            assertEquals(0x000000, rendered.getPixel(1, 0) & 0xffffff);
        } finally {
            dialog.dispose();
        }
    }

    @Test
    public void overlayControlsStayHiddenWithoutObjectMap() {
        assumeFalse(GraphicsEnvironment.isHeadless());

        LargePreviewDialog dialog = new LargePreviewDialog(null);
        try {
            dialog.setImages(image("raw"), image("filtered"), 1);

            assertFalse(dialog.overlayControlsVisibleForTest());
        } finally {
            dialog.dispose();
        }
    }

    private static ImagePlus image(String title) {
        ByteProcessor processor = new ByteProcessor(2, 1);
        processor.set(0, 0, 10);
        processor.set(1, 0, 100);
        return new ImagePlus(title, processor);
    }

    private static ImagePlus labels(String title) {
        ByteProcessor processor = new ByteProcessor(2, 1);
        processor.set(0, 0, 1);
        processor.set(1, 0, 0);
        return new ImagePlus(title, processor);
    }

    private static ImagePlus uniformImage(String title, int value) {
        ByteProcessor processor = new ByteProcessor(2, 1);
        processor.set(0, 0, value);
        processor.set(1, 0, value);
        return new ImagePlus(title, processor);
    }

    private static int blend(int base, int overlay, double alpha) {
        int br = (base >> 16) & 0xff;
        int bg = (base >> 8) & 0xff;
        int bb = base & 0xff;
        int or = (overlay >> 16) & 0xff;
        int og = (overlay >> 8) & 0xff;
        int ob = overlay & 0xff;
        int r = (int) Math.round(br * (1.0 - alpha) + or * alpha);
        int g = (int) Math.round(bg * (1.0 - alpha) + og * alpha);
        int b = (int) Math.round(bb * (1.0 - alpha) + ob * alpha);
        return (r << 16) | (g << 8) | b;
    }
}
