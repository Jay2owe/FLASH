package flash.pipeline.ui.preview;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import org.junit.Test;

import java.awt.GraphicsEnvironment;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

public class ComparisonPreviewDialogTest {

    @Test
    public void sourceSelectionUsesRadioButtonsAndControlsBothOverlays() {
        assumeFalse(GraphicsEnvironment.isHeadless());

        ComparisonPreviewDialog dialog = new ComparisonPreviewDialog(null);
        try {
            dialog.setSourceChoices(
                    uniformImage("raw", 100),
                    PreviewDisplaySettings.of(0.0, 200.0,
                            PreviewDisplaySettings.LutMode.GREY, "Grays"),
                    uniformImage("filtered", 200),
                    PreviewDisplaySettings.of(0.0, 200.0,
                            PreviewDisplaySettings.LutMode.GREY, "Grays"));
            dialog.setImages(labels("current"), labels("previous"), 1);
            dialog.setOverlaySelectedForTest(true);

            assertTrue(dialog.sourceControlsVisibleForTest());
            assertEquals(2, dialog.sourceRadioButtonCountForTest());
            assertEquals(PreviewPairPanel.SourceMode.FILTERED, dialog.sourceModeForTest());

            ImageProcessor currentFiltered = dialog.currentRenderedProcessorForTest();
            ImageProcessor previousFiltered = dialog.previousRenderedProcessorForTest();
            assertEquals(0xffffff, currentFiltered.getPixel(1, 0) & 0xffffff);
            assertEquals(0xffffff, previousFiltered.getPixel(1, 0) & 0xffffff);

            dialog.setSourceModeForTest(PreviewPairPanel.SourceMode.RAW);

            assertEquals(PreviewPairPanel.SourceMode.RAW, dialog.sourceModeForTest());
            ImageProcessor currentRaw = dialog.currentRenderedProcessorForTest();
            ImageProcessor previousRaw = dialog.previousRenderedProcessorForTest();
            assertEquals(0x808080, currentRaw.getPixel(1, 0) & 0xffffff);
            assertEquals(0x808080, previousRaw.getPixel(1, 0) & 0xffffff);
        } finally {
            dialog.dispose();
        }
    }

    @Test
    public void displayActionButtonsForwardRequests() {
        assumeFalse(GraphicsEnvironment.isHeadless());

        ComparisonPreviewDialog dialog = new ComparisonPreviewDialog(null);
        final AtomicInteger brightnessRequests = new AtomicInteger();
        final AtomicInteger lutRequests = new AtomicInteger();
        try {
            dialog.setDisplayActionListener(new ComparisonPreviewDialog.DisplayActionListener() {
                @Override public void adjustBrightnessContrastRequested() {
                    brightnessRequests.incrementAndGet();
                }

                @Override public void lutToggleRequested() {
                    lutRequests.incrementAndGet();
                }
            });
            dialog.setDisplayActionState(true, true, "Red LUT", "Show red LUT");

            assertTrue(dialog.displayControlsButtonForTest().isVisible());
            assertTrue(dialog.lutToggleButtonForTest().isVisible());
            assertEquals("Red LUT", dialog.lutToggleButtonForTest().getText());

            dialog.displayControlsButtonForTest().doClick();
            dialog.lutToggleButtonForTest().doClick();

            assertEquals(1, brightnessRequests.get());
            assertEquals(1, lutRequests.get());
        } finally {
            dialog.dispose();
        }
    }

    @Test
    public void displayActionStateCanHideBrightnessButKeepLut() {
        assumeFalse(GraphicsEnvironment.isHeadless());

        ComparisonPreviewDialog dialog = new ComparisonPreviewDialog(null);
        try {
            dialog.setDisplayActionState(false, true, "Red LUT", "Show red LUT");

            assertFalse(dialog.displayControlsButtonForTest().isVisible());
            assertFalse(dialog.displayControlsButtonForTest().isEnabled());
            assertTrue(dialog.lutToggleButtonForTest().isVisible());
            assertTrue(dialog.lutToggleButtonForTest().isEnabled());
            assertEquals("Red LUT", dialog.lutToggleButtonForTest().getText());
        } finally {
            dialog.dispose();
        }
    }

    @Test
    public void restoreButtonForwardsRequest() {
        assumeFalse(GraphicsEnvironment.isHeadless());

        ComparisonPreviewDialog dialog = new ComparisonPreviewDialog(null);
        final AtomicInteger restoreRequests = new AtomicInteger();
        try {
            dialog.setRestoreActionListener(new ComparisonPreviewDialog.RestoreActionListener() {
                @Override public void restorePreviousRequested() {
                    restoreRequests.incrementAndGet();
                }
            });
            dialog.setRestoreActionState(true, "Restore previous settings");

            assertTrue(dialog.restorePreviousButtonForTest().isVisible());
            assertTrue(dialog.restorePreviousButtonForTest().isEnabled());

            dialog.restorePreviousButtonForTest().doClick();

            assertEquals(1, restoreRequests.get());

            dialog.setRestoreActionState(false, null);

            assertFalse(dialog.restorePreviousButtonForTest().isVisible());
        } finally {
            dialog.dispose();
        }
    }

    private static ImagePlus uniformImage(String title, int value) {
        ByteProcessor processor = new ByteProcessor(2, 1);
        processor.set(0, 0, value);
        processor.set(1, 0, value);
        return new ImagePlus(title, processor);
    }

    private static ImagePlus labels(String title) {
        ByteProcessor processor = new ByteProcessor(2, 1);
        processor.set(0, 0, 1);
        processor.set(1, 0, 0);
        ImagePlus image = new ImagePlus(title, processor);
        LabelMapStyler.apply(image, 1);
        return image;
    }
}
