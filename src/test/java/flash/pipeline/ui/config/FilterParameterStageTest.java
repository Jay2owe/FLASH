package flash.pipeline.ui.config;

import flash.pipeline.ui.preview.PreviewPairPanel;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FilterParameterStageTest {

    @Test
    public void textFieldEditMarksPreviewStaleWithoutRunningFilter() {
        RecordingMacroStore store = new RecordingMacroStore(
                "Default",
                "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n");
        RecordingPreviewAdapter previewAdapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"), store, previewAdapter, null, null);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));
        previewAdapter.previewRuns = 0;

        stage.setParameterForTest("sigma", "4");

        assertTrue(stage.isPreviewStaleForTest());
        assertTrue(actions.status.contains("Preview"));
        assertEquals("Field edits must not execute the filter preview",
                0, previewAdapter.previewRuns);
    }

    @Test
    public void previewRunsOnlyWhenExplicitlyRequested() throws Exception {
        RecordingMacroStore store = new RecordingMacroStore(
                "Default",
                "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n");
        RecordingPreviewAdapter previewAdapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"), store, previewAdapter, null, null);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));

        assertEquals(0, previewAdapter.previewRuns);

        stage.runPreviewNowForTest();

        assertEquals(1, previewAdapter.previewRuns);
        assertFalse(stage.isPreviewStaleForTest());
        assertEquals("Filter preview complete.", actions.status);
        assertTrue(actions.adjustedPreviewSet);
    }

    @Test
    public void lockInWritesEditedMacroOnlyAfterLockIn() {
        RecordingMacroStore store = new RecordingMacroStore(
                "Default",
                "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n");
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"),
                store,
                new RecordingPreviewAdapter(),
                null,
                null);
        ConfigQcContext context = context();

        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));
        stage.setParameterForTest("sigma", "5");

        assertEquals("", store.savedMacro);

        assertTrue(stage.lockIn(context));

        assertEquals("Default", store.savedPreset);
        assertTrue(store.savedMacro.contains("sigma=5"));
    }

    private static ConfigQcContext context() {
        return ConfigQcContext.fromImages(
                null,
                null,
                null,
                Arrays.asList(stack("QC image")),
                Arrays.asList("IBA1"),
                0);
    }

    private static ImagePlus stack(String title) {
        ImageStack stack = new ImageStack(3, 3);
        ByteProcessor processor = new ByteProcessor(3, 3);
        processor.set(1, 1, 12);
        stack.addSlice(processor);
        return new ImagePlus(title, stack);
    }

    private static final class RecordingMacroStore implements FilterParameterStage.MacroStore {
        final String initialPreset;
        final String macro;
        String savedPreset = "";
        String savedMacro = "";

        RecordingMacroStore(String initialPreset, String macro) {
            this.initialPreset = initialPreset;
            this.macro = macro;
        }

        @Override public String getInitialPreset() {
            return initialPreset;
        }

        @Override public String loadInitialMacro() {
            return macro;
        }

        @Override public String loadPresetMacro(String presetName) {
            return macro;
        }

        @Override public void save(String presetName, String macroContent) {
            savedPreset = presetName;
            savedMacro = macroContent;
        }
    }

    private static final class RecordingPreviewAdapter implements FilterParameterStage.PreviewAdapter {
        int previewRuns;

        @Override public ImagePlus createSource(ConfigQcContext context) {
            return context.getCurrentImagePlus().duplicate();
        }

        @Override public ImagePlus createFilteredPreview(ImagePlus source, String macroContent) {
            previewRuns++;
            ImagePlus preview = source.duplicate();
            preview.setTitle("preview");
            return preview;
        }

        @Override public void close(ImagePlus image) {
            if (image != null) image.flush();
        }
    }

    private static final class RecordingActions implements ConfigQcActions {
        String status = "";
        boolean adjustedPreviewSet;

        @Override public void setStatus(String text) {
            status = text;
        }

        @Override public void markPreviewStale(String text) {
            status = text;
        }

        @Override public void setAdjustedPreview(ImagePlus image, String text) {
            adjustedPreviewSet = image != null;
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
