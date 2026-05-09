package flash.pipeline.ui.preview;

import ij.ImagePlus;
import ij.process.ByteProcessor;
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
}
