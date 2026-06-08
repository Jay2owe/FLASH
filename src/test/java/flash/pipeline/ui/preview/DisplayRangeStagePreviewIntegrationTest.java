package flash.pipeline.ui.preview;

import flash.pipeline.ui.config.ConfigQcActions;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.DisplayRangeStage;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import org.junit.Test;

import javax.swing.JComponent;
import java.awt.Component;
import java.awt.Container;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class DisplayRangeStagePreviewIntegrationTest {

    @Test
    public void sliderEditsUpdateRenderedPreviewWhenPreviewBrightnessControlsHiddenButLutVisible() {
        DisplayRangeStage stage = new DisplayRangeStage(
                new RecordingRangeStore("10-90"),
                new DuplicatePreviewAdapter());
        ConfigQcContext context = context();
        PreviewPairPanel pair = new PreviewPairPanel("Original", "Adjusted");
        pair.setDisplayControlsAvailable(stage.showPreviewDisplayControls(), stage.showPreviewLutToggle());
        pair.resetStageToolstripState();
        pair.setOriginal(context.getCurrentImagePlus());
        pair.setAdjusted(null);

        JComponent controls = stage.buildControls(context, new PairActions(pair));
        MinMaxControlPanel control = findFirst(controls, MinMaxControlPanel.class);
        assertNotNull(control);

        stage.onEnter(context, pair);

        assertFalse(pair.displaySettingsForTest().hasDisplayRange());
        assertRenderedRange(pair, 10.0, 90.0);

        editSliderField(control.minimumSliderForTest(), "20");
        assertRenderedRange(pair, 20.0, 90.0);

        editSliderField(control.maximumSliderForTest(), "70");
        assertRenderedRange(pair, 20.0, 70.0);
    }

    private static void editSliderField(FijiStyleRangeSliderPanel slider, String value) {
        slider.valueFieldForTest().setText(value);
        slider.valueFieldForTest().postActionEvent();
    }

    private static void assertRenderedRange(PreviewPairPanel pair, double min, double max) {
        ImageProcessor rendered = pair.adjustedPreviewForTest().renderedProcessorForTest();
        assertNotNull(rendered);
        assertEquals(min, rendered.getMin(), 0.0001);
        assertEquals(max, rendered.getMax(), 0.0001);
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
        private String token;

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

    private static final class DuplicatePreviewAdapter implements DisplayRangeStage.PreviewAdapter {
        @Override public ImagePlus createSource(ConfigQcContext context) {
            return context.getCurrentImagePlus().duplicate();
        }

        @Override public void close(ImagePlus image) {
            if (image != null) image.flush();
        }
    }

    private static final class PairActions implements ConfigQcActions {
        private final PreviewPairPanel pair;

        PairActions(PreviewPairPanel pair) {
            this.pair = pair;
        }

        @Override public void setStatus(String text) {
        }

        @Override public void markPreviewStale(String text) {
            pair.setAdjustedState(PreviewPairPanel.PreviewState.STALE, text);
        }

        @Override public void setAdjustedPreview(ImagePlus image, String text) {
            pair.setAdjusted(image);
            pair.setAdjustedState(PreviewPairPanel.PreviewState.READY, text);
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
