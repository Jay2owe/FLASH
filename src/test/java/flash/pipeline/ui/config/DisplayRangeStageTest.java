package flash.pipeline.ui.config;

import flash.pipeline.ui.preview.PreviewPairPanel;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DisplayRangeStageTest {

    @Test
    public void rangeChangesUpdateAdjustedPreviewImmediately() {
        RecordingRangeStore store = new RecordingRangeStore("10-90");
        RecordingActions actions = new RecordingActions();
        DisplayRangeStage stage = new DisplayRangeStage(store, new RecordingPreviewAdapter());
        ConfigQcContext context = context();

        stage.buildControls(context, actions);
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

        stage.setRangeForTest(20.0, 80.0);

        assertNotNull(actions.adjustedPreview);
        assertEquals(20.0, actions.adjustedPreview.getDisplayRangeMin(), 0.0001);
        assertEquals(80.0, actions.adjustedPreview.getDisplayRangeMax(), 0.0001);
        assertTrue(actions.status.contains("Display range preview"));
    }

    @Test
    public void lockInWritesMinMaxToken() {
        RecordingRangeStore store = new RecordingRangeStore("None");
        DisplayRangeStage stage = new DisplayRangeStage(store, new RecordingPreviewAdapter());
        ConfigQcContext context = context();

        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));
        stage.setRangeForTest(12.2, 87.7);

        assertTrue(stage.lockIn(context));

        assertEquals("12-88", store.token);
        assertEquals("12-88", stage.currentRangeTokenForTest());
    }

    @Test
    public void displayRangeStageDoesNotOfferPreviewDisplayAdjustment() {
        DisplayRangeStage stage = new DisplayRangeStage(
                new RecordingRangeStore("None"),
                new RecordingPreviewAdapter());

        assertFalse(stage.showPreviewDisplayControls());
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

    private static final class RecordingRangeStore implements DisplayRangeStage.RangeStore {
        String token;

        RecordingRangeStore(String token) {
            this.token = token;
        }

        @Override public String get() {
            return token;
        }

        @Override public void set(String token) {
            this.token = token;
        }
    }

    private static final class RecordingPreviewAdapter implements DisplayRangeStage.PreviewAdapter {
        @Override public ImagePlus createSource(ConfigQcContext context) {
            return context.getCurrentImagePlus().duplicate();
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
