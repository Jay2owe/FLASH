package flash.pipeline.ui.config;

import flash.pipeline.help.SetupHelpCatalog;
import flash.pipeline.objects.ObjectsCounter3DWrapper;
import flash.pipeline.ui.preview.PreviewPairPanel;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import org.junit.Test;

import javax.swing.JButton;
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
    public void thresholdAndSizeEditsMarkObjectPreviewStale() throws Exception {
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

        assertTrue(stage.isObjectPreviewStaleForTest());
        assertEquals("Size edits must not execute the object preview",
                2, adapter.previewRuns);
        assertTrue(actions.previewButtonStale);
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
            stats.incrementCounter();
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
}
