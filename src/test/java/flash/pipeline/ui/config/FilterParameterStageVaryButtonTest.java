package flash.pipeline.ui.config;

import flash.pipeline.image.FilterMacroParser.OpType;
import flash.pipeline.image.NamedFilterLoader;
import flash.pipeline.image.dag.Combiner;
import flash.pipeline.image.dag.CombinerOp;
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

public class FilterParameterStageVaryButtonTest {

    private static final String THREE_STEP_MACRO =
            "run(\"Gaussian Blur...\", \"sigma=1 stack\");\n"
                    + "run(\"Subtract Background...\", \"rolling=20 stack\");\n"
                    + "run(\"Median...\", \"radius=2 stack\");\n";

    @Test
    public void linearMacroWithSourceEnablesVaryButton() {
        ConfigQcContext context = context();
        FilterParameterStage stage = stage(THREE_STEP_MACRO);

        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

        assertTrue(stage.isLinearForTest());
        assertTrue(stage.isVaryButtonEnabledForTest());
    }

    @Test
    public void branchedMacroDisablesVaryButtonWithTooltip() {
        ConfigQcContext context = context();
        FilterParameterStage stage = stage(branchedMacro());

        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

        assertFalse(stage.isLinearForTest());
        assertFalse(stage.isVaryButtonEnabledForTest());
        assertEquals("Use Custom macro... to vary branched pipelines",
                stage.varyButtonTooltipForTest());
    }

    /**
     * Integration guard (docs/filter-branch-robustness): the ACTUAL bundled
     * compound presets — loaded as raw text WITHOUT an {@code @ihf-dag} header,
     * i.e. through the legacy classifier rather than {@code loadEmbeddedDag} —
     * must classify as branched and disable Vary. This exercises the exact path
     * the user hit when the crash occurred; {@link #branchedMacro()} above goes
     * through the embedded-DAG path instead.
     */
    @Test
    public void bundledCompoundPresetsClassifyBranchedAndDisableVary() {
        for (String preset : new String[]{"Puncta Resolve", "Diffuse Object"}) {
            String macro = NamedFilterLoader.loadFilterContent(preset);
            FilterParameterStage stage = stage(macro);

            stage.buildControls(context(), new RecordingActions());
            stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));

            assertFalse(preset + " must classify as branched", stage.isLinearForTest());
            assertFalse(preset + " must disable Vary", stage.isVaryButtonEnabledForTest());
        }
    }

    @Test
    public void linearMacroWithoutSourceDisablesVaryButtonWithTooltip() {
        ConfigQcContext context = context();
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"),
                new MacroStore(THREE_STEP_MACRO),
                new NoSourcePreviewAdapter(),
                null,
                null);

        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

        assertTrue(stage.isLinearForTest());
        assertFalse(stage.isVaryButtonEnabledForTest());
        assertEquals("No source image is available for parameter variations.",
                stage.varyButtonTooltipForTest());
    }

    private static FilterParameterStage stage(String macro) {
        return new FilterParameterStage(
                Arrays.asList("Default", "Custom"),
                new MacroStore(macro),
                new PreviewAdapter(),
                null,
                null);
    }

    private static String branchedMacro() {
        DagLine lineA = new DagLine("line_A",
                Collections.singletonList(new DagNode("node_1",
                        OpType.GAUSSIAN_BLUR, "sigma=1 stack")));
        DagLine lineB = new DagLine("line_B",
                Collections.singletonList(new DagNode("node_2",
                        OpType.MEDIAN, "radius=2 stack")));
        Combiner combiner = new Combiner("combiner_1", CombinerOp.AND,
                Arrays.asList("line_A", "line_B"));
        return DagToIjmEmitter.emit(new DagIR(1,
                Arrays.asList(lineA, lineB),
                Collections.singletonList(combiner),
                "combiner_1",
                "native"));
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

    private static final class NoSourcePreviewAdapter
            implements FilterParameterStage.PreviewAdapter {
        @Override public ImagePlus createSource(ConfigQcContext context) {
            return null;
        }

        @Override public ImagePlus createFilteredPreview(ImagePlus source,
                                                         String macroContent) {
            return null;
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
