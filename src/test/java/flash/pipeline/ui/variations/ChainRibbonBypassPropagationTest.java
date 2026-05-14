package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.image.dag.DagIR;
import flash.pipeline.image.dag.IjmToDagLoader;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChainRibbonBypassPropagationTest {

    @Test
    public void bypassedStepBecomesDisabledDagNodeAndIsOmittedFromBody() {
        String macro = "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n"
                + "run(\"Median...\", \"radius=1 stack\");";
        FilterMacroEditorModel.MacroDefinition definition =
                FilterMacroEditorModel.parse(macro);
        ChainRibbon ribbon = new ChainRibbon(definition);
        ribbon.setStepState(0, ChainRibbon.StepState.BYPASSED);

        String rendered = MacroVariationsDialog.renderMacroWithDisabledStepsForTest(
                macro, ribbon.disabledStepIndexes(),
                ChainRibbon.entryLineIndexes(definition));

        DagIR loaded = IjmToDagLoader.load(rendered);
        assertTrue(loaded.lines.get(0).ops.get(0).disabled);
        assertFalse(rendered.contains("run(\"Gaussian Blur...\""));
        assertTrue(rendered.contains("run(\"Median...\", \"radius=1 stack\");"));
    }
}
