package flash.pipeline.ui.preview;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import org.junit.Test;

import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.image.IndexColorModel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assume.assumeFalse;

public class PreviewPairPanelTest {

    @Test
    public void mainPreviewClampsToSharedSliceRange() {
        PreviewPairPanel pair = new PreviewPairPanel("Original", "Adjusted");
        pair.setOriginal(stack("original", 5));
        pair.setAdjusted(stack("adjusted", 3));

        pair.setCurrentZ(5);

        assertEquals(3, pair.getCurrentZ());
        assertEquals(3, pair.originalZForTest());
        assertEquals(3, pair.adjustedZForTest());
    }

    @Test
    public void settingAdjustedImagePreservesCurrentZWhenPossible() {
        PreviewPairPanel pair = new PreviewPairPanel("Original", "Adjusted");
        pair.setOriginal(stack("original", 5));
        pair.setAdjusted(stack("first", 5));
        pair.setCurrentZ(4);

        pair.setAdjusted(stack("second", 5));

        assertEquals(4, pair.getCurrentZ());
        assertEquals(4, pair.originalZForTest());
        assertEquals(4, pair.adjustedZForTest());
    }

    @Test
    public void settingAdjustedImageClampsCurrentZWhenNeeded() {
        PreviewPairPanel pair = new PreviewPairPanel("Original", "Adjusted");
        pair.setOriginal(stack("original", 6));
        pair.setAdjusted(stack("first", 6));
        pair.setCurrentZ(5);

        pair.setAdjusted(stack("second", 2));

        assertEquals(2, pair.getCurrentZ());
        assertEquals(2, pair.originalZForTest());
        assertEquals(2, pair.adjustedZForTest());
    }

    @Test
    public void settingAdjustedImagePreservesTransientDisplayRange() {
        PreviewPairPanel pair = new PreviewPairPanel("Original", "Adjusted");
        pair.setOriginal(singleSlice("original", 0, 100));
        pair.setDisplayRangeForTest(10.0, 80.0);

        pair.setAdjusted(singleSlice("adjusted", 0, 2));

        assertEquals(10.0, pair.displaySettingsForTest().getDisplayMin(), 0.0001);
        assertEquals(80.0, pair.displaySettingsForTest().getDisplayMax(), 0.0001);
        ImageProcessor originalRendered = pair.originalPreviewForTest().renderedProcessorForTest();
        assertEquals(10.0, originalRendered.getMin(), 0.0001);
        assertEquals(80.0, originalRendered.getMax(), 0.0001);
    }

    @Test
    public void objectPreviewKeepsNativeLabelDisplayWhileSourceUsesDisplaySettings() {
        PreviewPairPanel pair = new PreviewPairPanel("Filtered", "Objects");
        ImagePlus raw = singleSlice("raw", 0, 100);
        ImagePlus filtered = singleSlice("filtered", 0, 100);
        ImagePlus labels = singleSlice("objects", 0, 5);
        LabelMapStyler.apply(labels, 5);

        pair.setChannelLutName("Red");
        pair.setOriginal(filtered);
        pair.setLargePreviewImages(raw, filtered, labels);
        pair.setAdjusted(labels);
        pair.setDisplayRangeForTest(10.0, 80.0);

        ImageProcessor sourceRendered = pair.originalPreviewForTest().renderedProcessorForTest();
        ImageProcessor objectRendered = pair.adjustedPreviewForTest().renderedProcessorForTest();
        assertEquals(10.0, sourceRendered.getMin(), 0.0001);
        assertEquals(80.0, sourceRendered.getMax(), 0.0001);
        IndexColorModel sourceModel = (IndexColorModel) sourceRendered.getColorModel();
        assertEquals(255, sourceModel.getRed(255));
        assertEquals(0, sourceModel.getGreen(255));
        assertEquals(0, sourceModel.getBlue(255));

        assertEquals(0.0, objectRendered.getMin(), 0.0001);
        assertEquals(5.0, objectRendered.getMax(), 0.0001);
        IndexColorModel objectModel = (IndexColorModel) objectRendered.getColorModel();
        int expected = LabelMapStyler.rgbForLabel(5);
        assertEquals((expected >> 16) & 0xff, objectModel.getRed(5));
        assertEquals((expected >> 8) & 0xff, objectModel.getGreen(5));
        assertEquals(expected & 0xff, objectModel.getBlue(5));
    }

    @Test
    public void hiddenDisplayControlsPreserveSeparateImageDisplayRanges() {
        PreviewPairPanel pair = new PreviewPairPanel("Original", "Adjusted");
        ImagePlus original = stack("original", 1);
        ImagePlus adjusted = stack("adjusted", 1);
        original.setDisplayRange(0.0, 100.0);
        adjusted.setDisplayRange(20.0, 80.0);

        pair.setOriginal(original);
        pair.setDisplayRangeForTest(5.0, 50.0);
        pair.setDisplayControlsAvailable(false);
        pair.setAdjusted(adjusted);

        assertFalse(pair.displaySettingsForTest().hasDisplayRange());
        ImageProcessor originalRendered = pair.originalPreviewForTest().renderedProcessorForTest();
        ImageProcessor adjustedRendered = pair.adjustedPreviewForTest().renderedProcessorForTest();
        assertEquals(0.0, originalRendered.getMin(), 0.0001);
        assertEquals(100.0, originalRendered.getMax(), 0.0001);
        assertEquals(20.0, adjustedRendered.getMin(), 0.0001);
        assertEquals(80.0, adjustedRendered.getMax(), 0.0001);
    }

    @Test
    public void resetZReturnsAllPreviewsToFirstSlice() {
        PreviewPairPanel pair = new PreviewPairPanel("Original", "Adjusted");
        pair.setOriginal(stack("original", 5));
        pair.setAdjusted(stack("adjusted", 5));
        pair.setCurrentZ(4);

        pair.resetZ();

        assertEquals(1, pair.getCurrentZ());
        assertEquals(1, pair.originalZForTest());
        assertEquals(1, pair.adjustedZForTest());
    }

    @Test
    public void adjustedStateMapsToUserFacingStatusText() {
        PreviewPairPanel pair = new PreviewPairPanel("Original", "Adjusted");

        pair.setAdjustedState(PreviewPairPanel.PreviewState.STALE, null);
        assertEquals(PreviewPairPanel.PreviewState.STALE, pair.adjustedStateForTest());
        assertEquals("Preview is out of date. Press the preview button to update.",
                pair.adjustedStatusTextForTest());

        pair.setAdjustedState(PreviewPairPanel.PreviewState.RUNNING, "Rendering labels...");
        assertEquals("Rendering labels...", pair.adjustedStatusTextForTest());

        pair.setAdjustedState(PreviewPairPanel.PreviewState.ERROR, "Filter failed");
        assertEquals("Preview failed: Filter failed", pair.adjustedStatusTextForTest());
    }

    @Test
    public void sharedClampUsesMinimumAvailableSliceCount() {
        assertEquals(1, PreviewPairPanel.clampSharedZ(0, 5, 3));
        assertEquals(2, PreviewPairPanel.clampSharedZ(2, 5, 3));
        assertEquals(3, PreviewPairPanel.clampSharedZ(5, 5, 3));
    }

    @Test
    public void customLargePreviewModelCanUseThreeImages() {
        PreviewPairPanel pair = new PreviewPairPanel("Original", "Adjusted");

        pair.setLargePreviewImages(
                stack("raw", 4),
                stack("filtered", 4),
                stack("objects", 4));

        assertEquals(3, pair.largePreviewImageCountForTest());

        pair.clearLargePreviewImages();

        assertEquals(2, pair.largePreviewImageCountForTest());
    }

    @Test
    public void largePreviewUsesCurrentWindowAsOwnerWhenAvailable() {
        assumeFalse(GraphicsEnvironment.isHeadless());

        Frame owner = new Frame("Owner");
        PreviewPairPanel pair = new PreviewPairPanel(null, "Original", "Adjusted");
        try {
            owner.add(pair);

            pair.largeViewButton().doClick();

            assertSame(owner, pair.largePreviewOwnerForTest());
        } finally {
            pair.disposeLargePreviewForTest();
            owner.dispose();
        }
    }

    @Test
    public void displayControlsButtonUsesCurrentWindowAsOwnerWhenAvailable() {
        assumeFalse(GraphicsEnvironment.isHeadless());

        Frame owner = new Frame("Owner");
        PreviewPairPanel pair = new PreviewPairPanel(null, "Original", "Adjusted");
        try {
            owner.add(pair);
            owner.pack();
            owner.setVisible(true);

            pair.displayControlsButton().doClick();

            assertSame(owner, pair.displayControlsOwnerForTest());
        } finally {
            pair.disposeDisplayControlsDialogForTest();
            owner.dispose();
        }
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

    private static ImagePlus singleSlice(String title, int lowValue, int highValue) {
        ByteProcessor processor = new ByteProcessor(2, 1);
        processor.set(0, 0, lowValue);
        processor.set(1, 0, highValue);
        return new ImagePlus(title, processor);
    }
}
