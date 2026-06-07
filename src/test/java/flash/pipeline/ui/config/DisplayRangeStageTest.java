package flash.pipeline.ui.config;

import flash.pipeline.ui.preview.HistogramPanel;
import flash.pipeline.ui.preview.PreviewPairPanel;
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
    public void savedRangeAboveCurrentImageMaximumIsNotClampedWhenLockedAgain() {
        RecordingRangeStore store = new RecordingRangeStore("10-200");
        RecordingActions actions = new RecordingActions();
        DisplayRangeStage stage = new DisplayRangeStage(store, new RecordingPreviewAdapter());
        ConfigQcContext context = context();

        stage.buildControls(context, actions);
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

        assertEquals("10-200", stage.currentRangeTokenForTest());
        assertNotNull(actions.adjustedPreview);
        assertEquals(10.0, actions.adjustedPreview.getDisplayRangeMin(), 0.0001);
        assertEquals(200.0, actions.adjustedPreview.getDisplayRangeMax(), 0.0001);

        assertTrue(stage.lockIn(context));

        assertEquals("10-200", store.token);
    }

    @Test
    public void restartKeepsCurrentEditedRangeAfterStageRebuild() {
        RecordingRangeStore store = new RecordingRangeStore("10-90");
        DisplayRangeStage stage = new DisplayRangeStage(store, new RecordingPreviewAdapter());
        ConfigQcContext context = context();

        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));
        stage.setRangeForTest(22.0, 77.0);

        stage.restartStage(context);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

        assertEquals("22-77", stage.currentRangeTokenForTest());
        assertEquals("10-90", store.token);
    }

    @Test
    public void displayRangeStageDoesNotOfferPreviewDisplayAdjustment() {
        DisplayRangeStage stage = new DisplayRangeStage(
                new RecordingRangeStore("None"),
                new RecordingPreviewAdapter());

        assertFalse(stage.showPreviewDisplayControls());
        assertTrue(stage.showPreviewLutToggle());
        assertTrue(stage.controlsCanExpand());
    }

    @Test
    public void controlsUseFullRangeEditorWithoutDuplicatedSummary() {
        DisplayRangeStage stage = new DisplayRangeStage(
                new RecordingRangeStore("None"),
                new RecordingPreviewAdapter());

        JComponent controls = stage.buildControls(context(), new RecordingActions());
        JScrollPane scroll = findFirst(controls, JScrollPane.class);
        HistogramPanel histogram = findFirst(controls, HistogramPanel.class);

        assertTrue(hasLabel(controls, "Adjust min/max on the channel projection."));
        assertFalse(hasLabel(controls, "C1 - IBA1"));
        assertFalse(hasLabel(controls, "Image 1 / 1: QC image"));
        assertFalse(hasLabel(controls, "Adjust the displayed min/max range on the channel projection."));
        assertNull(scroll);
        assertNotNull(histogram);
        assertTrue("histogram preferred height was " + histogram.getPreferredSize().height,
                histogram.getPreferredSize().height <= 64);
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
