package flash.pipeline.ui.preview;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.awt.Frame;
import java.awt.GraphicsEnvironment;

import static org.junit.Assert.assertEquals;
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
