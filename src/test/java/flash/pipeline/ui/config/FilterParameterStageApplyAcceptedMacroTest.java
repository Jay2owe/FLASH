package flash.pipeline.ui.config;

import flash.pipeline.image.FilterMacroParser.OpType;
import flash.pipeline.image.dag.DagIR;
import flash.pipeline.image.dag.DagLine;
import flash.pipeline.image.dag.DagNode;
import flash.pipeline.image.dag.DagToIjmEmitter;
import flash.pipeline.ui.preview.PreviewPairPanel;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import javax.swing.JButton;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FilterParameterStageApplyAcceptedMacroTest {

    private static final String ONE_STEP_MACRO =
            "run(\"Gaussian Blur...\", \"sigma=1 stack\");\n";
    private static final String TWO_STEP_MACRO =
            "run(\"Gaussian Blur...\", \"sigma=1 stack\");\n"
                    + "run(\"Subtract Background...\", \"rolling=20 stack\");\n";

    @Test
    public void numericAcceptedMacroReconcilesFieldsInPlace() {
        ConfigQcContext context = context();
        FilterParameterStage stage = stage(ONE_STEP_MACRO);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

        stage.simulateApplyAcceptedMacroForTest(
                "run(\"Gaussian Blur...\", \"sigma=2.0 stack\");\n");

        assertTrue(stage.currentMacroForTest().contains("sigma=2.0"));
        assertEquals("2.0", stage.parameterFieldValueForTest("sigma"));
        assertTrue(stage.isDirtyForTest());
        assertTrue(stage.isPreviewStaleForTest());
    }

    @Test
    public void structuralAcceptedMacroRebuildsDisabledRows() {
        ConfigQcContext context = context();
        FilterParameterStage stage = stage(TWO_STEP_MACRO);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

        stage.simulateApplyAcceptedMacroForTest(disabledFirstStepMacro());

        assertEquals(2, stage.sectionCountForTest());
        assertFalse("disabled DAG node should render as a disabled accordion row",
                stage.isSectionBodyEnabledForTest(0));
        assertTrue("disabled nodes in a single DAG line remain linear",
                stage.isLinearForTest());
        assertTrue(stage.isDirtyForTest());
        assertTrue(stage.isPreviewStaleForTest());
    }

    private static String disabledFirstStepMacro() {
        DagNode gaussian = new DagNode("node_1", OpType.GAUSSIAN_BLUR,
                "sigma=2 stack");
        gaussian.disabled = true;
        DagNode background = new DagNode("node_2", OpType.SUBTRACT_BACKGROUND,
                "rolling=20 stack");
        DagLine line = new DagLine("line_A", Arrays.asList(gaussian, background));
        return DagToIjmEmitter.emit(new DagIR(1,
                Collections.singletonList(line),
                Collections.<flash.pipeline.image.dag.Combiner>emptyList(),
                "line_A",
                "native"));
    }

    private static FilterParameterStage stage(String macro) {
        return new FilterParameterStage(
                Arrays.asList("Default", "Custom"),
                new MacroStore(macro),
                new PreviewAdapter(),
                null,
                null);
    }

    private static ConfigQcContext context() {
        return ConfigQcContext.fromImages(
                null,
                null,
                null,
                Collections.singletonList(stack("QC image")),
                Collections.singletonList("IBA1"),
                0);
    }

    private static ImagePlus stack(String title) {
        ImageStack stack = new ImageStack(3, 3);
        ByteProcessor processor = new ByteProcessor(3, 3);
        processor.set(1, 1, 12);
        stack.addSlice(processor);
        return new ImagePlus(title, stack);
    }

    private static final class MacroStore implements FilterParameterStage.MacroStore {
        private final String macro;

        MacroStore(String macro) {
            this.macro = macro;
        }

        @Override public String getInitialPreset() {
            return "Default";
        }

        @Override public String loadInitialMacro() {
            return macro;
        }

        @Override public String loadPresetMacro(String presetName) {
            return macro;
        }

        @Override public void save(String presetName, String macroContent) {
        }

        @Override public void saveAsPreset(String presetName, String macroContent) {
        }
    }

    private static final class PreviewAdapter implements FilterParameterStage.PreviewAdapter {
        @Override public ImagePlus createSource(ConfigQcContext context) {
            return context.getCurrentImagePlus().duplicate();
        }

        @Override public ImagePlus createFilteredPreview(ImagePlus source, String macroContent) {
            return source == null ? null : source.duplicate();
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

        @Override public void registerPreviewButton(JButton button) {
        }

        @Override public void setPreviewButtonStale(boolean stale) {
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
