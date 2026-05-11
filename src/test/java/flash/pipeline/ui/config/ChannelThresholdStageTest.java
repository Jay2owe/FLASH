package flash.pipeline.ui.config;

import flash.pipeline.ui.preview.PreviewPairPanel;
import flash.pipeline.ui.preview.ThresholdOverlayRenderer;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ChannelThresholdStageTest {

    @Test
    public void lockInWritesLowerThresholdToBothStores() {
        RecordingThresholdStore store = new RecordingThresholdStore("default");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        ChannelThresholdStage stage = new ChannelThresholdStage(store, adapter);
        ConfigQcContext context = context();

        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));
        stage.setThresholdForTest(42.4, 120.0);

        assertTrue(stage.lockIn(context));

        assertEquals("42", store.objectToken);
        assertEquals("42", store.intensityToken);
        assertEquals("42", stage.currentThresholdTokenForTest());
        assertEquals(1, adapter.thresholdSourcesCreated);
    }

    @Test
    public void thresholdChangesUpdateOverlayImmediately() {
        RecordingThresholdStore store = new RecordingThresholdStore("20");
        RecordingActions actions = new RecordingActions();
        ChannelThresholdStage stage = new ChannelThresholdStage(store, new RecordingPreviewAdapter());
        ConfigQcContext context = context();

        stage.buildControls(context, actions);
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));
        stage.setThresholdForTest(50.0, 100.0);

        assertNotNull(actions.adjustedPreview);
        assertEquals("Threshold preview", actions.adjustedPreview.getTitle());
        assertTrue(actions.status.contains("Threshold preview"));

        stage.setPreviewModeForTest(ThresholdOverlayRenderer.MODE_MASK);

        assertNotNull(actions.adjustedPreview);
        assertEquals(255.0, actions.adjustedPreview.getProcessor().getMax(), 0.0001);
    }

    @Test
    public void restartKeepsCurrentEditedThresholdAfterStageRebuild() {
        RecordingThresholdStore store = new RecordingThresholdStore("20");
        ChannelThresholdStage stage = new ChannelThresholdStage(store, new RecordingPreviewAdapter());
        ConfigQcContext context = context();

        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));
        stage.setThresholdForTest(55.0, 100.0);

        stage.restartStage(context);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

        assertEquals("55", stage.currentThresholdTokenForTest());
        assertEquals("20", store.objectToken);
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
        ByteProcessor processor = new ByteProcessor(4, 1);
        processor.set(0, 0, 0);
        processor.set(1, 0, 25);
        processor.set(2, 0, 75);
        processor.set(3, 0, 100);
        return new ImagePlus(title, processor);
    }

    private static final class RecordingThresholdStore implements ChannelThresholdStage.ThresholdStore {
        String objectToken;
        String intensityToken;

        RecordingThresholdStore(String token) {
            objectToken = token;
            intensityToken = token;
        }

        @Override public String get() {
            return objectToken;
        }

        @Override public void set(String token) {
            objectToken = token;
            intensityToken = token;
        }
    }

    private static final class RecordingPreviewAdapter implements ChannelThresholdStage.PreviewAdapter {
        int thresholdSourcesCreated;

        @Override public ImagePlus createRawSource(ConfigQcContext context) {
            ImagePlus raw = context.getCurrentImagePlus().duplicate();
            raw.setTitle("raw");
            return raw;
        }

        @Override public ImagePlus createThresholdSource(ConfigQcContext context) {
            thresholdSourcesCreated++;
            ImagePlus filtered = context.getCurrentImagePlus().duplicate();
            filtered.setTitle("filtered");
            return filtered;
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
