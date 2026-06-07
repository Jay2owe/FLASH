package flash.pipeline.ui.config;

import flash.pipeline.segmentation.SegmentationTokenCodec;
import flash.pipeline.ui.ToggleSwitch;
import flash.pipeline.ui.preview.PreviewPairPanel;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.Test;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.TitledBorder;
import java.awt.Component;
import java.awt.Container;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class EnhancedClassicalSegmentationStageTest {

    @Test
    public void morphologyControlsAreVisibleAsParameterRowsByDefault() {
        EnhancedClassicalSegmentationStage stage = stage("classical");

        JComponent controls = stage.buildControls(null, new RecordingActions());

        assertFalse(containsComponent(controls, CollapsibleSection.class));
        assertNotNull(findTitledPanel(controls, "Morphology filters"));
        assertTrue(containsLabel(controls, "Morphology:"));
        assertTrue(containsLabel(controls, "Feature"));
        assertTrue(containsLabel(controls, "Rule"));
        assertTrue(containsLabel(controls, "Value"));
        assertNotNull(findComboWithSelectedItem(controls, "sphericity"));
        ToggleSwitch toggle = findMorphologyToggle(controls);
        assertNotNull(toggle);
        assertFalse(toggle.isSelected());
    }

    @Test
    public void storedMorphPredicateLoadsIntoVisibleEnabledRow() {
        String morph = SegmentationTokenCodec.percentEncodeToken("compactness<=0.7");
        EnhancedClassicalSegmentationStage stage = stage(
                "enhanced_classical:thresh=20:minSize=100:maxSize=2147483647:morph=" + morph);

        JComponent controls = stage.buildControls(null, new RecordingActions());

        assertFalse(containsComponent(controls, CollapsibleSection.class));
        assertNotNull(findComboWithSelectedItem(controls, "compactness"));
        assertNotNull(findComboWithSelectedItem(controls, "<="));
        ToggleSwitch toggle = findMorphologyToggle(controls);
        assertNotNull(toggle);
        assertTrue(toggle.isSelected());
    }

    @Test
    public void savedThresholdAboveCurrentImageMaximumIsPreservedInMethodToken() {
        RecordingParameterStore parameterStore = new RecordingParameterStore("enhanced_classical");
        RecordingThresholdStore thresholdStore = new RecordingThresholdStore("200");
        EnhancedClassicalSegmentationStage stage = new EnhancedClassicalSegmentationStage(
                parameterStore,
                thresholdStore,
                new RecordingSizeStore("1-Infinity"),
                new RecordingPreviewAdapter());
        ConfigQcContext context = context();

        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Objects"));

        assertTrue(stage.lockIn(context));

        assertEquals("200", thresholdStore.token);
        assertTrue(parameterStore.token.contains("thresh=200"));
    }

    private static EnhancedClassicalSegmentationStage stage(String methodToken) {
        return new EnhancedClassicalSegmentationStage(
                new RecordingParameterStore(methodToken),
                new RecordingThresholdStore("20"),
                new RecordingSizeStore("100-Infinity"),
                new RecordingPreviewAdapter());
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

    private static boolean containsLabel(Component component, String text) {
        return findLabel(component, text) != null;
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

    private static JPanel findTitledPanel(Component component, String title) {
        if (component instanceof JPanel
                && ((JPanel) component).getBorder() instanceof TitledBorder
                && title.equals(((TitledBorder) ((JPanel) component).getBorder()).getTitle())) {
            return (JPanel) component;
        }
        if (component instanceof Container) {
            Component[] children = ((Container) component).getComponents();
            for (int i = 0; i < children.length; i++) {
                JPanel found = findTitledPanel(children[i], title);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JComboBox<?> findComboWithSelectedItem(Component component, String selected) {
        if (component instanceof JComboBox) {
            JComboBox<?> combo = (JComboBox<?>) component;
            Object value = combo.getSelectedItem();
            if (selected.equals(String.valueOf(value))) {
                return combo;
            }
        }
        if (component instanceof Container) {
            Component[] children = ((Container) component).getComponents();
            for (int i = 0; i < children.length; i++) {
                JComboBox<?> found = findComboWithSelectedItem(children[i], selected);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean containsComponent(Component component, Class<?> type) {
        return findFirst(component, type) != null;
    }

    private static ToggleSwitch findMorphologyToggle(Component component) {
        JPanel morphPanel = findTitledPanel(component, "Morphology filters");
        return morphPanel == null ? null : findFirst(morphPanel, ToggleSwitch.class);
    }

    private static <T> T findFirst(Component component, Class<T> type) {
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

    private static final class RecordingParameterStore
            implements EnhancedClassicalSegmentationStage.ParameterStore {
        String token;

        RecordingParameterStore(String token) {
            this.token = token;
        }

        @Override public String getMethodToken() {
            return token;
        }

        @Override public void save(String methodToken) {
            token = methodToken;
        }
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
            implements EnhancedClassicalSegmentationStage.PreviewAdapter {
        @Override public ImagePlus createRawSource(ConfigQcContext context) {
            return context == null ? null : context.getCurrentImagePlus().duplicate();
        }

        @Override public ImagePlus createFilteredSource(ConfigQcContext context) {
            return context == null ? null : context.getCurrentImagePlus().duplicate();
        }

        @Override public void close(ImagePlus image) {
            if (image != null) image.flush();
        }
    }

    private static final class RecordingActions implements ConfigQcActions {
        @Override public void setStatus(String text) {
        }

        @Override public void markPreviewStale(String text) {
        }

        @Override public void setAdjustedPreview(ImagePlus image, String text) {
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
