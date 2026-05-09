package flash.pipeline.ui.config;

import flash.pipeline.objects.ObjectsCounter3DWrapper;
import flash.pipeline.ui.preview.PreviewPairPanel;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ParticleSizeStageTest {

    @Test
    public void parsesSizeTokensForFields() {
        ParticleSizeStage.SizeToken token =
                ParticleSizeStage.parseSizeToken("12.4-99.6");

        assertEquals("12", token.minText);
        assertEquals("100", token.maxText);
        assertEquals("12-100", token.toToken());

        ParticleSizeStage.SizeToken infinity =
                ParticleSizeStage.parseSizeToken("25-inf");
        assertEquals("25-Infinity", infinity.toToken());

        assertEquals("100-Infinity",
                ParticleSizeStage.parseSizeToken("not-a-range").toToken());
    }

    @Test
    public void textFieldEditMarksPreviewStaleWithoutRunningObjectPreview() {
        RecordingStore store = new RecordingStore("1-Infinity");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        ParticleSizeStage stage = new ParticleSizeStage(store, adapter);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));
        adapter.previewRuns = 0;

        stage.setMinSizeForTest("2");

        assertTrue(stage.isPreviewStaleForTest());
        assertTrue(actions.status.contains("Preview"));
        assertEquals("Field edits must not execute the object preview",
                0, adapter.previewRuns);
    }

    @Test
    public void previewRunsOnlyWhenExplicitlyRequested() throws Exception {
        RecordingStore store = new RecordingStore("1-Infinity");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        ParticleSizeStage stage = new ParticleSizeStage(store, adapter);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));

        assertEquals(0, adapter.previewRuns);
        assertEquals(42, stage.thresholdForTest());

        stage.runPreviewNowForTest();

        assertEquals(1, adapter.previewRuns);
        assertEquals(42, adapter.lastThreshold);
        assertEquals(1, adapter.lastMinSize);
        assertFalse(stage.isPreviewStaleForTest());
        assertNotNull(actions.adjustedPreview);
        assertEquals("Objects detected: 2", actions.status);
    }

    @Test
    public void onEnterCreatesRawAndFilteredSourcesAndDefaultsToFiltered() {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        ParticleSizeStage stage = new ParticleSizeStage(
                new RecordingStore("1-Infinity"), adapter);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));

        assertEquals(1, adapter.rawSourceCreations);
        assertEquals(1, adapter.filteredSourceCreations);
        assertTrue(stage.currentSourceTitleForTest().startsWith("filtered"));
        assertEquals(2, stage.largePreviewPaneCountForTest());
    }

    @Test
    public void sourceSwitchingDoesNotRunObjectPreviewBeforeLabelsExist() {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        ParticleSizeStage stage = new ParticleSizeStage(
                new RecordingStore("1-Infinity"), adapter);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));
        adapter.previewRuns = 0;

        stage.selectRawSourceForTest();

        assertTrue(stage.currentSourceTitleForTest().startsWith("raw"));
        assertEquals(0, adapter.previewRuns);
        assertEquals(null, actions.adjustedPreview);
    }

    @Test
    public void overlayToggleRendersObjectsOverSelectedSource() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        ParticleSizeStage stage = new ParticleSizeStage(
                new RecordingStore("1-Infinity"), adapter);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));
        stage.runPreviewNowForTest();

        assertEquals("Object label preview", actions.adjustedPreview.getTitle());
        assertEquals(3, stage.largePreviewPaneCountForTest());

        stage.setShowOverlayForTest(true);

        assertTrue(actions.adjustedPreview.getTitle().startsWith("Object overlay | filtered"));

        stage.selectRawSourceForTest();

        assertTrue(actions.adjustedPreview.getTitle().startsWith("Object overlay | raw"));
        assertEquals("Objects detected: 2", actions.status);
    }

    @Test
    public void lockInWritesNormalizedSizeToken() {
        RecordingStore store = new RecordingStore("1-Infinity");
        ParticleSizeStage stage = new ParticleSizeStage(store, new RecordingPreviewAdapter());
        ConfigQcContext context = context();

        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));
        stage.setMinSizeForTest("4.6");
        stage.setMaxSizeForTest("20.2");

        assertTrue(stage.lockIn(context));

        assertEquals("5-20", store.token);
        assertEquals("5-20", stage.currentSizeTokenForTest());
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
        ImageStack stack = new ImageStack(3, 3);
        ByteProcessor processor = new ByteProcessor(3, 3);
        processor.set(1, 1, 12);
        stack.addSlice(processor);
        return new ImagePlus(title, stack);
    }

    private static final class RecordingStore implements ParticleSizeStage.SizeStore {
        String token;

        RecordingStore(String token) {
            this.token = token;
        }

        @Override public String get() {
            return token;
        }

        @Override public void set(String token) {
            this.token = token;
        }
    }

    private static final class RecordingPreviewAdapter implements ParticleSizeStage.PreviewAdapter {
        int rawSourceCreations;
        int filteredSourceCreations;
        int previewRuns;
        int lastThreshold;
        int lastMinSize;

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

        @Override public int resolveThreshold(ImagePlus filteredSource, ConfigQcContext context) {
            return 42;
        }

        @Override public ObjectsCounter3DWrapper.Result runPreview(ImagePlus filteredSource,
                                                                   int threshold,
                                                                   int minSize,
                                                                   int maxSize) {
            previewRuns++;
            lastThreshold = threshold;
            lastMinSize = minSize;
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
