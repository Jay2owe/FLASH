package flash.pipeline.ui.preview;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import org.junit.Test;

import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.image.IndexColorModel;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JPanel;
import javax.swing.JSlider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

public class PreviewPairPanelTest {

    @Test
    public void horizontalSlim_arrangesPreviewsOneByTwo_andHidesPerPanelChrome() {
        PreviewPairPanel pair = new PreviewPairPanel("Original", "Adjusted",
                PreviewPairPanel.PreviewLayout.HORIZONTAL_SLIM);

        assertTrue(pair.previewPairContainerForTest().getLayout() instanceof GridLayout);
        GridLayout layout = (GridLayout) pair.previewPairContainerForTest().getLayout();
        assertEquals(1, layout.getRows());
        assertEquals(2, layout.getColumns());
        assertFalse(pair.originalPreviewForTest().metadataHeaderVisibleForTest());
        assertFalse(pair.adjustedPreviewForTest().metadataHeaderVisibleForTest());
        assertFalse(pair.originalPreviewForTest().zRowVisibleForTest());
        assertFalse(pair.adjustedPreviewForTest().zRowVisibleForTest());
        assertNotNull(pair.originalPreviewForTest().slimTitleLabelForTest());
        assertNotNull(pair.adjustedPreviewForTest().slimTitleLabelForTest());
        assertEquals(new Dimension(340, 280),
                pair.originalPreviewForTest().canvasPreferredSizeForTest());
        assertEquals(new Dimension(340, 280),
                pair.adjustedPreviewForTest().canvasPreferredSizeForTest());
        assertEquals(2, pair.originalPreviewForTest().layoutVerticalGapForTest());
        assertEquals(2, pair.adjustedPreviewForTest().layoutVerticalGapForTest());
    }

    @Test
    public void horizontalSlimStartsWithEmptyPreviewStateAndDisabledSharedZ() {
        PreviewPairPanel pair = new PreviewPairPanel("Original", "Adjusted",
                PreviewPairPanel.PreviewLayout.HORIZONTAL_SLIM);

        assertFalse(pair.originalPreviewForTest().hasImageForTest());
        assertFalse(pair.adjustedPreviewForTest().hasImageForTest());
        assertEquals("No image selected.", pair.originalPreviewForTest().titleTextForTest());
        assertEquals("No image selected.", pair.adjustedPreviewForTest().titleTextForTest());
        assertEquals(1, pair.getCurrentZ());
        assertEquals(1, pair.originalZForTest());
        assertEquals(1, pair.adjustedZForTest());
        assertNull(pair.originalPreviewForTest().renderedProcessorForTest());
        assertNull(pair.adjustedPreviewForTest().renderedProcessorForTest());

        JSlider slider = pair.sharedZSliderForTest();
        assertEquals(1, slider.getMinimum());
        assertEquals(1, slider.getMaximum());
        assertEquals(1, slider.getValue());
        assertFalse(slider.isEnabled());
        assertEquals("1 / 1", pair.sharedZTextForTest());
    }

    @Test
    public void sharedZRowSlider_drivesBothPreviews_andLargePreview() {
        PreviewPairPanel pair = new PreviewPairPanel("Original", "Adjusted",
                PreviewPairPanel.PreviewLayout.HORIZONTAL_SLIM);
        pair.setOriginal(stack("original", 5));
        pair.setAdjusted(stack("adjusted", 5));

        JSlider slider = pair.sharedZSliderForTest();
        slider.setValue(4);

        assertEquals(4, pair.getCurrentZ());
        assertEquals(4, pair.originalZForTest());
        assertEquals(4, pair.adjustedZForTest());
        assertEquals("4 / 5", pair.sharedZTextForTest());

        if (!GraphicsEnvironment.isHeadless()) {
            LargePreviewDialog dialog = new LargePreviewDialog(null);
            try {
                pair.setLargePreviewDialogForTest(dialog);
                slider.setValue(2);

                assertEquals(2, dialog.getCurrentZForTest());
            } finally {
                dialog.dispose();
            }
        }
    }

    @Test
    public void sharedZRow_updatesRangeWhenAdjustedImageSliceCountChanges() {
        PreviewPairPanel pair = new PreviewPairPanel("Original", "Adjusted",
                PreviewPairPanel.PreviewLayout.HORIZONTAL_SLIM);
        pair.setOriginal(stack("original", 6));
        pair.setAdjusted(stack("first", 6));

        JSlider slider = pair.sharedZSliderForTest();
        pair.setCurrentZ(5);
        assertEquals(6, slider.getMaximum());
        assertEquals("5 / 6", pair.sharedZTextForTest());

        pair.setAdjusted(stack("second", 3));

        assertEquals(3, pair.getCurrentZ());
        assertEquals(3, slider.getMaximum());
        assertEquals(3, slider.getValue());
        assertEquals("3 / 3", pair.sharedZTextForTest());
    }

    @Test
    public void previewToolstrip_containsLargeBcAndLutButtons() {
        PreviewPairPanel pair = new PreviewPairPanel("Original", "Adjusted",
                PreviewPairPanel.PreviewLayout.HORIZONTAL_SLIM);

        JPanel toolstrip = pair.previewToolstrip();

        assertTrue(toolstrip.isAncestorOf(pair.largeViewButton()));
        assertTrue(toolstrip.isAncestorOf(pair.displayControlsButton()));
        assertTrue(toolstrip.isAncestorOf(pair.lutToggleButton()));
    }

    @Test
    public void lutToggleButtonSwitchesBetweenGreyAndChannelLut() {
        PreviewPairPanel pair = new PreviewPairPanel("Original", "Adjusted");
        pair.setChannelLutName("Red");
        pair.setOriginal(singleSlice("original", 0, 100));

        assertEquals(PreviewDisplaySettings.LutMode.CHANNEL,
                pair.displaySettingsForTest().getLutMode());
        assertEquals("Grey LUT", pair.lutToggleButton().getText());
        IndexColorModel channelModel = (IndexColorModel) pair.originalPreviewForTest()
                .renderedProcessorForTest().getColorModel();
        assertEquals(255, channelModel.getRed(255));
        assertEquals(0, channelModel.getGreen(255));

        pair.lutToggleButton().doClick();

        assertEquals(PreviewDisplaySettings.LutMode.GREY,
                pair.displaySettingsForTest().getLutMode());
        assertEquals("Red LUT", pair.lutToggleButton().getText());
        IndexColorModel greyModel = (IndexColorModel) pair.originalPreviewForTest()
                .renderedProcessorForTest().getColorModel();
        assertEquals(greyModel.getRed(255), greyModel.getGreen(255));
        assertEquals(greyModel.getGreen(255), greyModel.getBlue(255));

        pair.lutToggleButton().doClick();

        assertEquals(PreviewDisplaySettings.LutMode.CHANNEL,
                pair.displaySettingsForTest().getLutMode());
        assertEquals("Grey LUT", pair.lutToggleButton().getText());
    }

    @Test
    public void sourceToggle_notVisibleByDefault_andNotifiesListenerWhenEnabled() {
        PreviewPairPanel pair = new PreviewPairPanel("Original", "Adjusted",
                PreviewPairPanel.PreviewLayout.HORIZONTAL_SLIM);
        pair.previewToolstrip();
        final AtomicReference<PreviewPairPanel.SourceMode> changed =
                new AtomicReference<PreviewPairPanel.SourceMode>();

        assertFalse(pair.sourceToggleVisibleForTest());

        pair.setSourceToggleVisible(true);
        pair.setSourceModeChangeListener(new PreviewPairPanel.SourceModeChangeListener() {
            @Override public void sourceModeChanged(PreviewPairPanel.SourceMode mode) {
                changed.set(mode);
            }
        });
        pair.sourceRawRadioForTest().doClick();

        assertTrue(pair.sourceToggleVisibleForTest());
        assertEquals(PreviewPairPanel.SourceMode.RAW, pair.sourceModeForTest());
        assertEquals(PreviewPairPanel.SourceMode.RAW, changed.get());

        pair.resetStageToolstripState();

        assertFalse(pair.sourceToggleVisibleForTest());
        assertTrue(pair.sourceModeEnabledForTest());
        assertEquals(PreviewPairPanel.SourceMode.FILTERED, pair.sourceModeForTest());
    }

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
    public void objectPreviewKeepsSeparateRawAndFilteredDisplayRanges() {
        PreviewPairPanel pair = new PreviewPairPanel("Filtered", "Objects");
        ImagePlus raw = shortSingleSlice("raw 16-bit", 0, 4096);
        ImagePlus filtered = singleSlice("filtered 8-bit", 0, 255);
        ImagePlus labels = singleSlice("objects", 0, 3);
        LabelMapStyler.apply(labels, 3);

        pair.setOriginal(filtered);
        pair.setLargePreviewImages(raw, filtered, labels);
        pair.setAdjusted(labels);
        pair.setDisplayRangeForTest(0.0, 255.0);

        assertEquals(255.0, pair.displaySettingsForImageForTest(filtered).getDisplayMax(), 0.0001);
        assertFalse(pair.displaySettingsForImageForTest(raw).hasDisplayRange());

        pair.setOriginal(raw);

        assertEquals(4096.0, pair.displaySettingsForTest().getDisplayMax(), 0.0001);
        ImageProcessor rawRendered = pair.originalPreviewForTest().renderedProcessorForTest();
        assertEquals(4096.0, rawRendered.getMax(), 0.0001);
    }

    @Test
    public void displayControlsResetWhenSourceBitDepthChanges() {
        PreviewPairPanel pair = new PreviewPairPanel("Original", "Adjusted");
        ImagePlus filtered = singleSlice("filtered 8-bit", 0, 255);
        ImagePlus raw = shortSingleSlice("raw 16-bit", 0, 4096);

        pair.setOriginal(filtered);
        pair.setDisplayRangeForTest(0.0, 255.0);

        pair.setOriginal(raw);

        assertEquals(4096.0, pair.displaySettingsForTest().getDisplayMax(), 0.0001);
    }

    @Test
    public void normalObjectPreviewCanShowRawOrFilteredOverlay() {
        PreviewPairPanel pair = new PreviewPairPanel("Filtered", "Objects");
        ImagePlus raw = singleSlice("raw", 0, 100);
        ImagePlus filtered = singleSlice("filtered", 0, 100);
        ImagePlus labels = singleSlice("Object labels", 0, 1);
        LabelMapStyler.apply(labels, 1);

        pair.setLargePreviewImages(raw, filtered, labels);
        pair.setAdjusted(labels);

        assertTrue(pair.objectOverlayControlsVisibleForTest());
        assertEquals("Object labels", pair.adjustedImageTitleForTest());

        pair.setObjectOverlaySelectedForTest(true);

        assertEquals("Object overlay | filtered", pair.adjustedImageTitleForTest());

        pair.setObjectOverlaySourceForTest("Raw image");

        assertEquals("Object overlay | raw", pair.adjustedImageTitleForTest());
    }

    @Test
    public void normalObjectOverlayRerendersBackgroundWithDisplayRange() {
        PreviewPairPanel pair = new PreviewPairPanel("Filtered", "Objects");
        ByteProcessor sourceProcessor = new ByteProcessor(3, 1);
        sourceProcessor.set(0, 0, 100);
        sourceProcessor.set(1, 0, 100);
        sourceProcessor.set(2, 0, 200);
        ImagePlus raw = new ImagePlus("raw", sourceProcessor.duplicate());
        ImagePlus filtered = new ImagePlus("filtered", sourceProcessor);

        ByteProcessor labelProcessor = new ByteProcessor(3, 1);
        labelProcessor.set(0, 0, 1);
        labelProcessor.set(1, 0, 0);
        labelProcessor.set(2, 0, 0);
        ImagePlus labels = new ImagePlus("Object labels", labelProcessor);
        LabelMapStyler.apply(labels, 1);

        pair.setLargePreviewImages(raw, filtered, labels);
        pair.setOriginal(filtered);
        pair.setAdjusted(labels);
        pair.setObjectOverlaySelectedForTest(true);
        pair.setDisplayRangeForTest(100.0, 200.0);

        ImageProcessor rendered = pair.adjustedPreviewForTest().renderedProcessorForTest();
        assertEquals(blend(0x000000, LabelMapStyler.rgbForLabel(1), 0.35),
                rendered.getPixel(0, 0) & 0xffffff);
        assertEquals(0x000000, rendered.getPixel(1, 0) & 0xffffff);
    }

    @Test
    public void objectOverlayDefersRerenderWhileDisplayRangeSliderIsAdjusting() {
        PreviewPairPanel pair = new PreviewPairPanel("Filtered", "Objects");
        ByteProcessor sourceProcessor = new ByteProcessor(4, 1);
        sourceProcessor.set(0, 0, 100);
        sourceProcessor.set(1, 0, 100);
        sourceProcessor.set(2, 0, 200);
        sourceProcessor.set(3, 0, 0);
        ImagePlus raw = new ImagePlus("raw", sourceProcessor.duplicate());
        ImagePlus filtered = new ImagePlus("filtered", sourceProcessor);

        ByteProcessor labelProcessor = new ByteProcessor(4, 1);
        labelProcessor.set(0, 0, 1);
        ImagePlus labels = new ImagePlus("Object labels", labelProcessor);
        LabelMapStyler.apply(labels, 1);

        pair.setLargePreviewImages(raw, filtered, labels);
        pair.setOriginal(filtered);
        pair.setAdjusted(labels);
        pair.setObjectOverlaySelectedForTest(true);
        pair.setDisplayRangeForTest(100.0, 200.0);
        assertEquals(0x000000,
                pair.adjustedPreviewForTest().renderedProcessorForTest().getPixel(1, 0) & 0xffffff);

        pair.setDisplayRangeForTest(0.0, 200.0, true);

        ImageProcessor sourceDuringDrag = pair.originalPreviewForTest().renderedProcessorForTest();
        assertEquals(0.0, sourceDuringDrag.getMin(), 0.0001);
        assertEquals(200.0, sourceDuringDrag.getMax(), 0.0001);
        assertEquals(0x000000,
                pair.adjustedPreviewForTest().renderedProcessorForTest().getPixel(1, 0) & 0xffffff);

        pair.setDisplayRangeForTest(0.0, 200.0, false);

        assertEquals(0x808080,
                pair.adjustedPreviewForTest().renderedProcessorForTest().getPixel(1, 0) & 0xffffff);
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

        assertFalse(pair.displayControlsButton().isVisible());
        assertFalse(pair.lutToggleButton().isVisible());
        assertFalse(pair.displaySettingsForTest().hasDisplayRange());
        ImageProcessor originalRendered = pair.originalPreviewForTest().renderedProcessorForTest();
        ImageProcessor adjustedRendered = pair.adjustedPreviewForTest().renderedProcessorForTest();
        assertEquals(0.0, originalRendered.getMin(), 0.0001);
        assertEquals(100.0, originalRendered.getMax(), 0.0001);
        assertEquals(20.0, adjustedRendered.getMin(), 0.0001);
        assertEquals(80.0, adjustedRendered.getMax(), 0.0001);
    }

    @Test
    public void largePreviewDisplayButtonsWorkWhenCompactControlsAreHidden() {
        assumeFalse(GraphicsEnvironment.isHeadless());

        PreviewPairPanel pair = new PreviewPairPanel("Original", "Adjusted");
        LargePreviewDialog dialog = new LargePreviewDialog(null);
        try {
            pair.setChannelLutName("Red");
            pair.setOriginal(singleSlice("original", 0, 100));
            pair.setDisplayControlsAvailable(false);
            pair.setLargePreviewDialogForTest(dialog);

            assertFalse(pair.displayControlsButton().isVisible());
            assertFalse(pair.lutToggleButton().isVisible());
            assertTrue(dialog.displayControlsButtonForTest().isVisible());
            assertTrue(dialog.lutToggleButtonForTest().isVisible());

            dialog.lutToggleButtonForTest().doClick();

            assertEquals(PreviewDisplaySettings.LutMode.GREY,
                    pair.displaySettingsForTest().getLutMode());

            dialog.displayControlsButtonForTest().doClick();

            assertSame(dialog, pair.displayControlsOwnerForTest());
        } finally {
            pair.disposeDisplayControlsDialogForTest();
            dialog.dispose();
        }
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
    public void customLargePreviewSourceChoiceSwitchesFirstPaneOnlyForLargeView() {
        PreviewPairPanel pair = new PreviewPairPanel("Original", "Adjusted");
        ImagePlus original = stack("original source", 4);
        ImagePlus filtered = stack("filtered source", 4);
        ImagePlus threshold = stack("threshold preview", 4);
        ImagePlus labels = stack("objects", 4);

        pair.setOriginal(threshold);
        pair.setAdjusted(labels);
        pair.setLargePreviewSourceChoices(original, filtered);
        pair.setLargePreviewImages(original, threshold, labels);

        assertFalse(pair.sourceToggleVisibleForTest());
        assertEquals("original source", pair.largePreviewFirstImageForTest().getTitle());
        assertEquals(PreviewPairPanel.SourceMode.RAW, pair.largePreviewSourceModeForTest());
        assertEquals("threshold preview", pair.originalPreviewForTest().titleTextForTest());

        pair.setLargePreviewSourceMode(PreviewPairPanel.SourceMode.FILTERED);

        assertFalse(pair.sourceToggleVisibleForTest());
        assertEquals("filtered source", pair.largePreviewFirstImageForTest().getTitle());
        assertEquals(PreviewPairPanel.SourceMode.FILTERED, pair.largePreviewSourceModeForTest());
        assertEquals("threshold preview", pair.originalPreviewForTest().titleTextForTest());
    }

    @Test
    public void compactHeadersHidePerImageMetadataRowsAndUsePanelTitles() {
        PreviewPairPanel pair = new PreviewPairPanel("Original", "Adjusted");

        pair.setCompactPreviewHeaders(true);
        pair.setOriginalPreviewTitle("Original Image - Mouse1_LH_SCN");
        pair.setAdjustedPreviewTitle("Adjusted / output preview");

        assertFalse(pair.originalPreviewMetadataHeaderVisibleForTest());
        assertEquals("Original Image - Mouse1_LH_SCN", pair.originalPreviewTitleForTest());
        assertEquals("Adjusted / output preview", pair.adjustedPreviewTitleForTest());
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

    private static ImagePlus shortSingleSlice(String title, int lowValue, int highValue) {
        ShortProcessor processor = new ShortProcessor(2, 1);
        processor.set(0, 0, lowValue);
        processor.set(1, 0, highValue);
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
