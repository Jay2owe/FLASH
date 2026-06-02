package flash.pipeline.ui.preview;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import org.junit.Test;

import java.awt.GraphicsEnvironment;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
    public void externallyDrivenOverlayControlsForwardToggleAndSource() {
        assumeFalse(GraphicsEnvironment.isHeadless());

        LargePreviewDialog dialog = new LargePreviewDialog(null);
        AtomicReference<Boolean> toggled = new AtomicReference<Boolean>();
        AtomicReference<Boolean> rawSource = new AtomicReference<Boolean>();
        try {
            ImagePlus labels = labels("Object labels");
            dialog.setOverlayChoiceListener(new LargePreviewDialog.OverlayChoiceListener() {
                @Override public void overlayToggleChanged(boolean selected) {
                    toggled.set(selected);
                }

                @Override public void overlaySourceChanged(boolean raw) {
                    rawSource.set(raw);
                }
            });
            dialog.setImages(image("raw"), image("filtered"), labels, labels, 1);

            // An upstream renderer takes ownership of the overlay controls.
            dialog.setExternalOverlayState(true, true, false, true, false);

            assertTrue(dialog.overlayControlsVisibleForTest());
            assertTrue(dialog.overlayCheckEnabledForTest());

            dialog.clickOverlayCheckForTest();
            assertEquals(Boolean.TRUE, toggled.get());

            dialog.selectOverlaySourceFromUiForTest("Raw image");
            assertEquals(Boolean.TRUE, rawSource.get());

            // Releasing external control restores the dialog's own enable logic.
            dialog.setExternalOverlayState(false, false, false, false, false);
            assertTrue(dialog.overlayCheckEnabledForTest());
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

    @Test
    public void sourceChoiceIsVisibleOnlyWhenLargeViewProvidesOriginalAndFilteredSources() {
        assumeFalse(GraphicsEnvironment.isHeadless());

        LargePreviewDialog dialog = new LargePreviewDialog(null);
        AtomicReference<PreviewPairPanel.SourceMode> changed =
                new AtomicReference<PreviewPairPanel.SourceMode>();
        try {
            dialog.setSourceChoiceListener(new LargePreviewDialog.SourceChoiceListener() {
                @Override public void sourceChoiceChanged(PreviewPairPanel.SourceMode mode) {
                    changed.set(mode);
                }
            });
            dialog.setSourceChoices(
                    image("original source"),
                    PreviewDisplaySettings.defaultFor("Grays"),
                    image("filtered source"),
                    PreviewDisplaySettings.defaultFor("Grays"),
                    PreviewPairPanel.SourceMode.RAW);

            assertTrue(dialog.sourceControlsVisibleForTest());
            assertEquals("Original", dialog.selectedSourceChoiceForTest());

            dialog.setSourceChoiceForTest("Filtered");

            assertEquals(PreviewPairPanel.SourceMode.FILTERED, changed.get());
            assertEquals("Filtered", dialog.selectedSourceChoiceForTest());

            dialog.clearSourceChoices();

            assertFalse(dialog.sourceControlsVisibleForTest());
        } finally {
            dialog.dispose();
        }
    }

    @Test
    public void displayActionButtonsForwardRequests() {
        assumeFalse(GraphicsEnvironment.isHeadless());

        LargePreviewDialog dialog = new LargePreviewDialog(null);
        final AtomicInteger brightnessRequests = new AtomicInteger();
        final AtomicInteger lutRequests = new AtomicInteger();
        try {
            dialog.setDisplayActionListener(new LargePreviewDialog.DisplayActionListener() {
                @Override public void adjustBrightnessContrastRequested() {
                    brightnessRequests.incrementAndGet();
                }

                @Override public void lutToggleRequested() {
                    lutRequests.incrementAndGet();
                }
            });
            dialog.setDisplayActionState("Red LUT", "Show red LUT");

            assertTrue(dialog.displayControlsButtonForTest().isVisible());
            assertTrue(dialog.displayControlsButtonForTest().isEnabled());
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
