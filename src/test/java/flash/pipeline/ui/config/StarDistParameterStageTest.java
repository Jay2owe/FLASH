package flash.pipeline.ui.config;

import flash.pipeline.ui.preview.PreviewPairPanel;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class StarDistParameterStageTest {

    @Test
    public void parsesAndRendersMethodToken() {
        String token = "stardist:0.7:0.2:linking=15.0:gapClosing=16.0:"
                + "frameGap=2:area=3.0-99.0:quality=0.8:intensity=22.0";

        StarDistParameterStage.Parameters params = StarDistParameterStage.parseMethod(token);

        assertEquals(token, StarDistParameterStage.formatMethod(params));
    }

    @Test
    public void textFieldEditMarksPreviewStaleWithoutRunningPreview() {
        RecordingStore store = new RecordingStore("stardist:0.5:0.4");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        StarDistParameterStage stage = new StarDistParameterStage(store, adapter);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));
        adapter.previewRuns = 0;

        stage.setProbabilityForTest("0.9");

        assertTrue(stage.isPreviewStaleForTest());
        assertTrue(actions.status.contains("Preview"));
        assertEquals("Field edits must not execute StarDist preview",
                0, adapter.previewRuns);
    }

    @Test
    public void controlsIncludeParameterHelperText() {
        StarDistParameterStage stage = new StarDistParameterStage(
                new RecordingStore("stardist:0.5:0.4"),
                new RecordingPreviewAdapter());

        JComponent controls = stage.buildControls(context(), new RecordingActions());

        assertContainsText(controls, "Minimum confidence required for StarDist to accept a detection.");
        assertContainsText(controls, "Number of missing z planes that can be bridged during 3D linking.");
        assertContainsText(controls, "Use 0 to disable the upper size limit.");
    }

    @Test
    public void previewRunsOnlyWhenExplicitlyRequested() throws Exception {
        RecordingStore store = new RecordingStore("stardist:0.5:0.4");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        StarDistParameterStage stage = new StarDistParameterStage(store, adapter);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));

        assertEquals(0, adapter.previewRuns);

        stage.runPreviewNowForTest();

        assertEquals(1, adapter.previewRuns);
        assertFalse(stage.isPreviewStaleForTest());
        assertNotNull(actions.adjustedPreview);
        assertEquals("Objects detected: 3", actions.status);
        assertEquals(3, stage.largePreviewPaneCountForTest());
    }

    @Test
    public void failedPreviewClearsOldOutputSoSourceStackStaysBrowsable() throws Exception {
        RecordingStore store = new RecordingStore("stardist:0.5:0.4");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        ConfigQcContext context = context(13);
        PreviewPairPanel pair = new PreviewPairPanel("Original", "Adjusted");
        RecordingActions actions = new RecordingActions(pair);
        StarDistParameterStage stage = new StarDistParameterStage(store, adapter);

        stage.buildControls(context, actions);
        stage.onEnter(context, pair);
        stage.runPreviewNowForTest();
        pair.setCurrentZ(13);
        assertEquals("The one-slice object map constrains the paired preview while it is installed.",
                1, pair.getCurrentZ());

        adapter.returnNullPreview = true;
        stage.runPreviewNowForTest();
        pair.setCurrentZ(13);

        assertEquals(13, pair.getCurrentZ());
        assertTrue(stage.isPreviewStaleForTest());
        assertEquals("StarDist returned no label map.", actions.status);
    }

    @Test
    public void sourceToggleSwapsRawAndFilteredWithoutRunningPreview() {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        StarDistParameterStage stage = new StarDistParameterStage(
                new RecordingStore("stardist:0.5:0.4"), adapter);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));
        adapter.previewRuns = 0;

        assertEquals(1, adapter.rawSourceCreations);
        assertEquals(1, adapter.filteredSourceCreations);
        assertTrue(stage.currentSourceTitleForTest().startsWith("filtered"));
        assertEquals(2, stage.largePreviewPaneCountForTest());

        stage.selectRawSourceForTest();

        assertTrue(stage.currentSourceTitleForTest().startsWith("raw"));
        assertEquals(0, adapter.previewRuns);
    }

    @Test
    public void restartKeepsCurrentEditedParametersAfterStageRebuild() {
        RecordingStore store = new RecordingStore("stardist:0.5:0.4");
        StarDistParameterStage stage = new StarDistParameterStage(
                store, new RecordingPreviewAdapter());
        ConfigQcContext context = context();

        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));
        stage.setProbabilityForTest("0.92");

        stage.restartStage(context);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

        assertTrue(stage.currentMethodForTest().startsWith("stardist:0.92:0.4"));
        assertEquals("stardist:0.5:0.4", store.token);
    }

    private static ConfigQcContext context() {
        return context(1);
    }

    private static ConfigQcContext context(int slices) {
        return ConfigQcContext.fromImages(
                null,
                null,
                null,
                Arrays.asList(image("QC image", slices)),
                Arrays.asList("IBA1"),
                0);
    }

    private static ImagePlus image(String title) {
        return image(title, 1);
    }

    private static ImagePlus image(String title, int slices) {
        ImageStack stack = new ImageStack(3, 3);
        for (int i = 0; i < Math.max(1, slices); i++) {
            ByteProcessor processor = new ByteProcessor(3, 3);
            processor.set(1, 1, 12 + i);
            stack.addSlice(processor);
        }
        return new ImagePlus(title, stack);
    }

    private static void assertContainsText(Component root, String expected) {
        assertTrue("Missing helper text: " + expected, containsText(root, expected));
    }

    private static boolean containsText(Component component, String expected) {
        String text = null;
        if (component instanceof JLabel) {
            text = ((JLabel) component).getText();
        } else if (component instanceof JTextComponent) {
            text = ((JTextComponent) component).getText();
        }
        if (text != null && text.contains(expected)) {
            return true;
        }
        if (component instanceof Container) {
            Component[] children = ((Container) component).getComponents();
            for (int i = 0; i < children.length; i++) {
                if (containsText(children[i], expected)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final class RecordingStore implements StarDistParameterStage.ParameterStore {
        String token;

        RecordingStore(String token) {
            this.token = token;
        }

        @Override public String getMethodToken() {
            return token;
        }

        @Override public void save(String methodToken) {
            token = methodToken;
        }
    }

    private static final class RecordingPreviewAdapter implements StarDistParameterStage.PreviewAdapter {
        int rawSourceCreations;
        int filteredSourceCreations;
        int previewRuns;
        boolean returnNullPreview;

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

        @Override public ImagePlus runPreview(ImagePlus filteredSource,
                                              StarDistParameterStage.Parameters parameters) {
            previewRuns++;
            if (returnNullPreview) {
                return null;
            }
            ByteProcessor processor = new ByteProcessor(2, 1);
            processor.set(0, 0, 1);
            processor.set(1, 0, 3);
            return new ImagePlus("labels", processor);
        }

        @Override public int countLabels(ImagePlus labelImage) {
            return (int) labelImage.getProcessor().getStats().max;
        }

        @Override public void close(ImagePlus image) {
            if (image != null) image.flush();
        }
    }

    private static final class RecordingActions implements ConfigQcActions {
        String status = "";
        ImagePlus adjustedPreview;
        PreviewPairPanel pair;

        RecordingActions() {
        }

        RecordingActions(PreviewPairPanel pair) {
            this.pair = pair;
        }

        @Override public void setStatus(String text) {
            status = text;
        }

        @Override public void markPreviewStale(String text) {
            status = text;
        }

        @Override public void setAdjustedPreview(ImagePlus image, String text) {
            adjustedPreview = image;
            status = text;
            if (pair != null) {
                pair.setAdjusted(image);
                pair.setAdjustedState(PreviewPairPanel.PreviewState.READY, text);
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
