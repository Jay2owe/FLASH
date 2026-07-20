package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.FilterParameterStage;
import flash.pipeline.ui.sandbox.FilterAlternatives;
import flash.pipeline.ui.sandbox.FilterAlternatives.SlotRole;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.io.File;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class StepsModeSweepBuildTest {

    @Test
    public void swapEditorBuildsBaselinePlusTickedAlternatives() {
        StepSwapEditor editor = new StepSwapEditor(context(smoothingMacro()));
        editor.setFocusedStepIndex(0);
        // Baseline (Gaussian Blur) is auto-ticked; add Median and Mean
        editor.setTickedForTest("Median", "Mean");

        ParameterSweep sweep = editor.currentSweep(CropSpec.full());

        assertEquals(3L, sweep.cellCount());
        assertTrue(hasValueOnAxis(sweep, SlotSubstitutionKey.Axis.FILTER,
                "Gaussian Blur"));
        assertTrue(hasValueOnAxis(sweep, SlotSubstitutionKey.Axis.FILTER, "Median"));
        assertTrue(hasValueOnAxis(sweep, SlotSubstitutionKey.Axis.FILTER, "Mean"));

        SlotSubstitutionCombo decoded =
                SlotSubstitutionCombo.from(sweep.combos().get(0));
        assertNotNull(decoded);
        assertEquals(0, decoded.stepIndex());
    }

    @Test
    public void swapEditorRefusesEmptyAlternativeSelection() {
        StepSwapEditor editor = new StepSwapEditor(context(smoothingMacro()));
        editor.setFocusedStepIndex(0);
        editor.setTickedForTest();

        try {
            editor.currentSweep(CropSpec.full());
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("alternative"));
            return;
        }
        throw new AssertionError("Expected an empty-alternatives sweep to be rejected");
    }

    @Test
    public void backgroundRemovalStepReportsNoAlternativesAvailable() {
        StepSwapEditor editor = new StepSwapEditor(context(backgroundOnlyMacro()));
        editor.setFocusedStepIndex(0);

        assertEquals(0, editor.checkboxCountForTest());
    }

    @Test
    public void smoothingRoleStillExposesItsAlternativeCatalog() {
        // Sanity: the alternative catalog used by the swap editor is the same
        // one consumed by the chain ribbon focus logic.
        assertTrue(FilterAlternatives.hasUsefulAlternatives(SlotRole.SMOOTHING));
    }

    private static boolean hasValueOnAxis(ParameterSweep sweep,
                                          SlotSubstitutionKey.Axis axis,
                                          String expected) {
        for (Map.Entry<ParameterKey, ParameterValueList> entry
                : sweep.valueLists().entrySet()) {
            if (!(entry.getKey() instanceof SlotSubstitutionKey)) {
                continue;
            }
            SlotSubstitutionKey key = (SlotSubstitutionKey) entry.getKey();
            if (key.axis() != axis) {
                continue;
            }
            return entry.getValue().values().contains(expected);
        }
        return false;
    }

    private static String smoothingMacro() {
        return "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n"
                + "run(\"Subtract Background...\", \"rolling=20 stack\");\n"
                + "run(\"Median...\", \"radius=2 stack\");";
    }

    private static String backgroundOnlyMacro() {
        return "run(\"Subtract Background...\", \"rolling=20 stack\");";
    }

    private static FilterVariationEngineContext context(String macroText) {
        ImagePlus source = image();
        FilterMacroEditorModel.MacroDefinition macro =
                FilterMacroEditorModel.parse(macroText);
        ConfigQcContext config = ConfigQcContext.fromImages(new File("."),
                new File("target/steps-mode-sweep-build-test-bin"),
                null, Collections.singletonList(source),
                Collections.singletonList("DAPI"), 0);
        return new FilterVariationEngineContext(macro, source, CropSpec.full(),
                "DAPI", config, new StubPreviewAdapter());
    }

    private static ImagePlus image() {
        ByteProcessor processor = new ByteProcessor(8, 8);
        processor.setValue(1);
        processor.fill();
        return new ImagePlus("source", processor);
    }

    private static final class StubPreviewAdapter
            implements FilterParameterStage.PreviewAdapter {
        @Override public ImagePlus createSource(ConfigQcContext context) {
            return image();
        }

        @Override public ImagePlus createFilteredPreview(ImagePlus source,
                                                         String macroContent) {
            return source == null ? null : source.duplicate();
        }

        @Override public void close(ImagePlus image) {
        }
    }
}
