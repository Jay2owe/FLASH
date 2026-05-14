package flash.pipeline.ui.config;

import flash.pipeline.help.SetupHelpCatalog;
import flash.pipeline.objects.ObjectsCounter3DWrapper;
import flash.pipeline.ui.preview.PreviewPairPanel;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterId;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import org.junit.Test;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.image.IndexColorModel;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ClassicalSegmentationStageTest {

    @Test
    public void enteringCreatesRawAndFilteredSources() {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        ClassicalSegmentationStage stage = stage(
                new RecordingThresholdStore("20"),
                new RecordingSizeStore("1-Infinity"),
                adapter);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Objects"));

        assertEquals(1, adapter.rawSourceCreations);
        assertEquals(1, adapter.filteredSourceCreations);
        assertNotNull(stage.thresholdPreviewForTest());
        assertEquals(SetupHelpCatalog.CLASSICAL_OBJECT_SEGMENTATION, stage.helpTopic());
    }

    @Test
    public void variationsButtonPresent_andDisabledWithoutPreview() {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        ClassicalSegmentationStage stage = stage(
                new RecordingThresholdStore("20"),
                new RecordingSizeStore("1-Infinity"),
                adapter);

        JComponent controls = stage.buildControls(context(), new RecordingActions());
        JButton variations = findButton(controls, "Parameter Variations...");

        assertNotNull(variations);
        assertFalse(variations.isEnabled());
        assertEquals("Run/prepare a preview before opening parameter variations.",
                variations.getToolTipText());

        stage.onEnter(context(), new PreviewPairPanel("Original", "Objects"));

        assertTrue(variations.isEnabled());
    }

    @Test
    public void applyCombo_writesFieldsAndTriggersRefresh() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        ClassicalSegmentationStage stage = stage(
                new RecordingThresholdStore("20"),
                new RecordingSizeStore("1-Infinity"),
                adapter);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Objects"));
        adapter.previewRuns = 0;

        stage.applyVariationComboForTest(ParameterCombo.builder()
                .put(ParameterId.THRESHOLD, Double.valueOf(45.0d))
                .put(ParameterId.MIN_SIZE, Integer.valueOf(3))
                .put(ParameterId.MAX_SIZE, Integer.valueOf(4))
                .build());
        waitForPreviewRuns(adapter, 1);

        assertEquals("45", stage.currentThresholdTokenForTest());
        assertEquals("3-4", stage.currentSizeTokenForTest());
        assertEquals(45, adapter.lastThreshold);
    }

    @Test
    public void normalLeftPaneIsThresholdPreviewNotRawSource() {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        ClassicalSegmentationStage stage = stage(
                new RecordingThresholdStore("20"),
                new RecordingSizeStore("1-Infinity"),
                adapter);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Objects"));

        assertEquals("Threshold preview", stage.currentNormalLeftPreviewTitleForTest());
        assertFalse("raw".equals(stage.currentNormalLeftPreviewTitleForTest()));
        assertFalse("filtered".equals(stage.currentNormalLeftPreviewTitleForTest()));
        assertEquals(2, stage.largePreviewPaneCountForTest());
    }

    @Test
    public void normalPreviewHidesSourceModeControls() {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        ClassicalSegmentationStage stage = stage(
                new RecordingThresholdStore("20"),
                new RecordingSizeStore("1-Infinity"),
                adapter);
        PreviewPairPanel pair = new PreviewPairPanel("Original", "Objects");

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), pair);

        assertEquals("Threshold preview", stage.currentNormalLeftPreviewTitleForTest());
        assertFalse(hasVisibleText(pair.previewToolstrip(), "Source:"));
        assertFalse(hasVisibleText(pair.previewToolstrip(), "Raw"));
        assertFalse(hasVisibleText(pair.previewToolstrip(), "Filtered"));
    }

    @Test
    public void largePreviewAddsObjectPaneAfterLabelsExist() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        ClassicalSegmentationStage stage = stage(
                new RecordingThresholdStore("20"),
                new RecordingSizeStore("1-Infinity"),
                adapter);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Objects"));

        assertEquals(2, stage.largePreviewPaneCountForTest());

        stage.runPreviewNowForTest();

        assertEquals(3, stage.largePreviewPaneCountForTest());
    }

    @Test
    public void thresholdEditRerendersWithoutRunningObjectPreview() {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        ClassicalSegmentationStage stage = stage(
                new RecordingThresholdStore("20"),
                new RecordingSizeStore("1-Infinity"),
                adapter);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Objects"));
        adapter.previewRuns = 0;
        ImagePlus firstPreview = stage.thresholdPreviewForTest();

        stage.setThresholdForTest(50.0, 100.0);

        assertTrue(firstPreview != stage.thresholdPreviewForTest());
        assertEquals(0, adapter.previewRuns);
        assertTrue(stage.isObjectPreviewStaleForTest());
        assertTrue(actions.status.contains("Object preview is out of date"));
    }

    @Test
    public void staleObjectStateDoesNotReplaceLiveThresholdPreview() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        ClassicalSegmentationStage stage = stage(
                new RecordingThresholdStore("20"),
                new RecordingSizeStore("1-Infinity"),
                adapter);
        PreviewPairPanel pair = new PreviewPairPanel("Original", "Objects");
        RecordingActions actions = new RecordingActions();

        stage.buildControls(context(), actions);
        stage.onEnter(context(), pair);
        stage.runPreviewNowForTest();

        ImagePlus firstThresholdPreview = stage.thresholdPreviewForTest();
        assertEquals("Threshold preview", stage.currentNormalLeftPreviewTitleForTest());
        assertEquals("Object label preview", actions.adjustedPreview.getTitle());

        stage.setThresholdForTest(80.0, 100.0);

        assertTrue(stage.isObjectPreviewStaleForTest());
        assertTrue(firstThresholdPreview != stage.thresholdPreviewForTest());
        assertEquals("Threshold preview", stage.currentNormalLeftPreviewTitleForTest());
        assertEquals("Object label preview", actions.adjustedPreview.getTitle());
        assertTrue(actions.status.contains("Object preview is out of date"));
    }

    @Test
    public void thresholdEditsMarkStaleButSizeEditsRelabelLive() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        ClassicalSegmentationStage stage = stage(
                new RecordingThresholdStore("20"),
                new RecordingSizeStore("1-Infinity"),
                adapter);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Objects"));
        stage.runPreviewNowForTest();

        assertFalse(stage.isObjectPreviewStaleForTest());

        stage.setThresholdForTest(60.0, 100.0);

        assertTrue(stage.isObjectPreviewStaleForTest());
        assertEquals(1, adapter.previewRuns);

        stage.runPreviewNowForTest();
        assertFalse(stage.isObjectPreviewStaleForTest());

        stage.setMinSizeForTest("3");

        assertFalse(stage.isObjectPreviewStaleForTest());
        assertEquals("Size edits must not execute the object preview",
                2, adapter.previewRuns);
        assertFalse(actions.previewButtonStale);
        assertEquals("Objects: 1 kept; removed 1 small, 0 large. Threshold 60.",
                actions.status);
        assertEquals("Objects: 1 kept; removed 1 small, 0 large",
                stage.sizeCutoffSummaryForTest());
        assertRemovedLabelUsesCutoffColor(actions.adjustedPreview, 1, 0xe53935);
    }

    @Test
    public void objectPreviewUsesCurrentUnsavedThreshold() throws Exception {
        RecordingThresholdStore thresholdStore = new RecordingThresholdStore("20");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        ClassicalSegmentationStage stage = stage(
                thresholdStore,
                new RecordingSizeStore("1-Infinity"),
                adapter);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Objects"));
        stage.setThresholdForTest(64.0, 100.0);

        stage.runPreviewNowForTest();

        assertEquals(64, adapter.lastThreshold);
        assertEquals("20", thresholdStore.token);
    }

    @Test
    public void objectPreviewRunsFullCandidateSetBeforeLiveSizeFiltering() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        ClassicalSegmentationStage stage = stage(
                new RecordingThresholdStore("20"),
                new RecordingSizeStore("3-Infinity"),
                adapter);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Objects"));
        stage.runPreviewNowForTest();

        assertEquals(0, adapter.lastMinSize);
        assertEquals(4, adapter.lastMaxSize);
        assertEquals("Objects: 1 kept; removed 1 small, 0 large. Threshold 20.",
                actions.status);
        assertRemovedLabelUsesCutoffColor(actions.adjustedPreview, 1, 0xe53935);
    }

    @Test
    public void lockInWritesThresholdAndSize() {
        RecordingThresholdStore thresholdStore = new RecordingThresholdStore("20");
        RecordingSizeStore sizeStore = new RecordingSizeStore("1-Infinity");
        ClassicalSegmentationStage stage = stage(
                thresholdStore,
                sizeStore,
                new RecordingPreviewAdapter());
        ConfigQcContext context = context();

        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Objects"));
        stage.setThresholdForTest(42.4, 100.0);
        stage.setMinSizeForTest("4.6");
        stage.setMaxSizeForTest("20.2");

        assertTrue(stage.lockIn(context));

        assertEquals("42", thresholdStore.token);
        assertEquals("5-20", sizeStore.token);
        assertEquals("42", stage.currentThresholdTokenForTest());
        assertEquals("5-20", stage.currentSizeTokenForTest());
    }

    @Test
    public void restartPreservesUnsavedThresholdAndSize() {
        RecordingThresholdStore thresholdStore = new RecordingThresholdStore("20");
        RecordingSizeStore sizeStore = new RecordingSizeStore("1-Infinity");
        ClassicalSegmentationStage stage = stage(
                thresholdStore,
                sizeStore,
                new RecordingPreviewAdapter());
        ConfigQcContext context = context();

        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Objects"));
        stage.setThresholdForTest(55.0, 100.0);
        stage.setMinSizeForTest("8");
        stage.setMaxSizeForTest("30");

        stage.restartStage(context);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Objects"));

        assertEquals("55", stage.currentThresholdTokenForTest());
        assertEquals("8-30", stage.currentSizeTokenForTest());
        assertEquals("20", thresholdStore.token);
        assertEquals("1-Infinity", sizeStore.token);
    }

    private static ClassicalSegmentationStage stage(RecordingThresholdStore thresholdStore,
                                                    RecordingSizeStore sizeStore,
                                                    RecordingPreviewAdapter adapter) {
        return new ClassicalSegmentationStage(thresholdStore, sizeStore, adapter);
    }

    private static ConfigQcContext context() {
        return ConfigQcContext.fromImages(
                null,
                null,
                null,
                Arrays.asList(image("QC image")),
                Arrays.asList("IBA1"),
                0);
    }

    private static ImagePlus image(String title) {
        ImageStack stack = new ImageStack(4, 1);
        ByteProcessor processor = new ByteProcessor(4, 1);
        processor.set(0, 0, 0);
        processor.set(1, 0, 25);
        processor.set(2, 0, 75);
        processor.set(3, 0, 100);
        stack.addSlice(processor);
        return new ImagePlus(title, stack);
    }

    private static boolean hasVisibleText(Container root, String text) {
        if (root == null || text == null) return false;
        for (Component component : root.getComponents()) {
            if (!component.isVisible()) continue;
            String componentText = null;
            if (component instanceof AbstractButton) {
                componentText = ((AbstractButton) component).getText();
            } else if (component instanceof JLabel) {
                componentText = ((JLabel) component).getText();
            }
            if (text.equals(componentText)) {
                return true;
            }
            if (component instanceof Container && hasVisibleText((Container) component, text)) {
                return true;
            }
        }
        return false;
    }

    private static JButton findButton(Container root, String text) {
        if (root == null || text == null) return null;
        for (Component component : root.getComponents()) {
            if (component instanceof JButton
                    && text.equals(((JButton) component).getText())) {
                return (JButton) component;
            }
            if (component instanceof Container) {
                JButton found = findButton((Container) component, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static final class RecordingThresholdStore
            implements ClassicalSegmentationStage.ThresholdStore {
        String token;

        RecordingThresholdStore(String token) {
            this.token = token;
        }

        @Override public String get() {
            return token;
        }

        @Override public void set(String token) {
            this.token = token;
        }
    }

    private static final class RecordingSizeStore
            implements ClassicalSegmentationStage.SizeStore {
        String token;

        RecordingSizeStore(String token) {
            this.token = token;
        }

        @Override public String get() {
            return token;
        }

        @Override public void set(String token) {
            this.token = token;
        }
    }

    private static final class RecordingPreviewAdapter
            implements ClassicalSegmentationStage.PreviewAdapter {
        int rawSourceCreations;
        int filteredSourceCreations;
        int previewRuns;
        int lastThreshold;
        int lastMinSize;
        int lastMaxSize;

        @Override public ImagePlus createRawSource(ConfigQcContext context) {
            rawSourceCreations++;
            ImagePlus source = context.getCurrentImagePlus().duplicate();
            source.setTitle("raw");
            return source;
        }

        @Override public ImagePlus createFilteredSource(ConfigQcContext context) {
            filteredSourceCreations++;
            ImagePlus source = context.getCurrentImagePlus().duplicate();
            source.setTitle("filtered");
            return source;
        }

        @Override public ObjectsCounter3DWrapper.Result runPreview(ImagePlus filteredSource,
                                                                   int threshold,
                                                                   int minSize,
                                                                   int maxSize) {
            previewRuns++;
            lastThreshold = threshold;
            lastMinSize = minSize;
            lastMaxSize = maxSize;
            ByteProcessor labels = new ByteProcessor(2, 1);
            labels.set(0, 0, 1);
            labels.set(1, 0, 2);
            ResultsTable stats = new ResultsTable();
            stats.incrementCounter();
            stats.setValue("Label", 0, 1);
            stats.setValue("Volume (pixel^3)", 0, 2);
            stats.incrementCounter();
            stats.setValue("Label", 1, 2);
            stats.setValue("Volume (pixel^3)", 1, 10);
            return new ObjectsCounter3DWrapper.Result(
                    stats, new ImagePlus("labels", labels), null, true);
        }

        @Override public int countObjects(ObjectsCounter3DWrapper.Result result) {
            return result == null || result.getStatistics() == null
                    ? 0
                    : result.getStatistics().size();
        }

        @Override public void close(ImagePlus image) {
            if (image != null) image.flush();
        }
    }

    private static final class RecordingActions implements ConfigQcActions {
        String status = "";
        ImagePlus adjustedPreview;
        JButton previewButton;
        boolean previewButtonStale;

        @Override public void setStatus(String text) {
            status = text;
        }

        @Override public void markPreviewStale(String text) {
            status = text;
        }

        @Override public void setAdjustedPreview(ImagePlus image, String text) {
            adjustedPreview = image;
            status = text;
        }

        @Override public void registerPreviewButton(JButton button) {
            previewButton = button;
            setPreviewButtonStale(true);
        }

        @Override public void setPreviewButtonStale(boolean stale) {
            previewButtonStale = stale;
            if (previewButton != null) {
                previewButton.setText(stale ? "\u25CF Run Preview" : "Run Preview");
            }
        }

        @Override public void nextImage() {
        }

        @Override public void skipCurrentImage() {
        }

        @Override public void restartStage() {
        }

        @Override public void cancel() {
        }
    }

    private static void assertRemovedLabelUsesCutoffColor(ImagePlus labelImage,
                                                          int label,
                                                          int expectedRgb) {
        assertNotNull(labelImage);
        assertTrue(labelImage.getProcessor().getColorModel() instanceof IndexColorModel);
        IndexColorModel model = (IndexColorModel) labelImage.getProcessor().getColorModel();
        int index = ((Math.max(1, label) - 1) % 255) + 1;
        int actual = (model.getRed(index) << 16)
                | (model.getGreen(index) << 8)
                | model.getBlue(index);
        assertEquals(expectedRgb, actual);
    }

    private static void waitForPreviewRuns(RecordingPreviewAdapter adapter,
                                           int expectedRuns) throws Exception {
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline && adapter.previewRuns < expectedRuns) {
            Thread.sleep(10L);
        }
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
            }
        });
        assertEquals(expectedRuns, adapter.previewRuns);
    }
}
