package flash.pipeline.ui.config;

import flash.pipeline.ui.preview.PreviewPairPanel;
import flash.pipeline.ui.preview.ThresholdOverlayRenderer;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.Test;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import java.awt.Component;
import java.awt.Container;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
    public void savedThresholdAboveCurrentImageMaximumIsNotClampedWhenLockedAgain() {
        RecordingThresholdStore store = new RecordingThresholdStore("200");
        ChannelThresholdStage stage = new ChannelThresholdStage(store, new RecordingPreviewAdapter());
        ConfigQcContext context = context();

        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

        assertEquals("200", stage.currentThresholdTokenForTest());
        assertTrue(stage.lockIn(context));

        assertEquals("200", store.objectToken);
        assertEquals("200", store.intensityToken);
    }

    @Test
    public void lockInCanWriteAlgorithmicThresholdToken() {
        RecordingThresholdStore store = new RecordingThresholdStore("default");
        ChannelThresholdStage stage = new ChannelThresholdStage(store, new RecordingPreviewAdapter());
        ConfigQcContext context = context();

        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));
        stage.setAlgorithmThresholdForTest("Otsu", "Dark");

        assertTrue(stage.lockIn(context));

        assertEquals("auto:Otsu:dark", store.objectToken);
        assertEquals("auto:Otsu:dark", store.intensityToken);
        assertEquals("auto:Otsu:dark", stage.currentThresholdTokenForTest());
    }

    @Test
    public void savedMethodAliasReloadsAsAlgorithmicThresholdToken() {
        RecordingThresholdStore store = new RecordingThresholdStore("IsoData");
        ChannelThresholdStage stage = new ChannelThresholdStage(store, new RecordingPreviewAdapter());
        ConfigQcContext context = context();

        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

        assertEquals("auto:IsoData:dark", stage.currentThresholdTokenForTest());
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

    @Test
    public void thresholdStageDoesNotOfferPreviewDisplayAdjustment() {
        ChannelThresholdStage stage = new ChannelThresholdStage(
                new RecordingThresholdStore("default"),
                new RecordingPreviewAdapter());

        assertFalse(stage.showPreviewDisplayControls());
        assertTrue(stage.controlsCanExpand());
    }

    @Test
    public void controlsUseFullThresholdEditorWithoutDuplicatedSummary() {
        ChannelThresholdStage stage = new ChannelThresholdStage(
                new RecordingThresholdStore("default"),
                new RecordingPreviewAdapter());

        JComponent controls = stage.buildControls(context(), new RecordingActions());
        JScrollPane scroll = findFirst(controls, JScrollPane.class);

        assertTrue(hasLabel(controls, "Adjust threshold to isolate the channel of interest."));
        assertFalse(hasLabel(controls, "C1 - IBA1"));
        assertFalse(hasLabel(controls, "Image 1 / 1: QC image"));
        assertFalse(hasLabel(controls, "Adjust the lower threshold; FLASH saves that value for object and intensity measurements."));
        assertNull(scroll);
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

    private static boolean hasLabel(Component component, String text) {
        JLabel label = findLabel(component, text);
        return label != null;
    }

    private static JLabel findLabel(Component component, String text) {
        if (component instanceof JLabel && text.equals(((JLabel) component).getText())) {
            return (JLabel) component;
        }
        if (component instanceof Container) {
            Component[] children = ((Container) component).getComponents();
            for (int i = 0; i < children.length; i++) {
                JLabel found = findLabel(children[i], text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T extends Component> T findFirst(Component component, Class<T> type) {
        if (type.isInstance(component)) {
            return type.cast(component);
        }
        if (component instanceof Container) {
            Component[] children = ((Container) component).getComponents();
            for (int i = 0; i < children.length; i++) {
                T found = findFirst(children[i], type);
                if (found != null) return found;
            }
        }
        return null;
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
