package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterMacroEditorModel;

import org.junit.Test;

import java.awt.event.MouseEvent;

import static org.junit.Assert.assertEquals;

public class ChainRibbonFocusRadioTest {

    @Test
    public void stepsModeFocusUsesRadioSemantics() {
        ChainRibbon ribbon = new ChainRibbon(FilterMacroEditorModel.parse(
                "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n"
                        + "run(\"Median...\", \"radius=1 stack\");\n"
                        + "run(\"Mean...\", \"radius=1 stack\");"));
        ribbon.setStepsMode(true);

        ribbon.clickStepForTest(0, MouseEvent.BUTTON1);
        assertEquals(ChainRibbon.StepState.FOCUSED, ribbon.getStepState(0));

        ribbon.clickStepForTest(1, MouseEvent.BUTTON1);

        assertEquals(ChainRibbon.StepState.FIXED, ribbon.getStepState(0));
        assertEquals(ChainRibbon.StepState.FOCUSED, ribbon.getStepState(1));
        assertEquals(1, ribbon.stepIndexesInState(
                ChainRibbon.StepState.FOCUSED).size());
    }

    @Test
    public void bypassedAndOffStepsAreNotFocusedByClick() {
        ChainRibbon ribbon = new ChainRibbon(FilterMacroEditorModel.parse(
                "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n"
                        + "run(\"Median...\", \"radius=1 stack\");"));
        ribbon.setStepsMode(true);
        ribbon.setStepState(0, ChainRibbon.StepState.BYPASSED);
        ribbon.setStepState(1, ChainRibbon.StepState.OFF);

        ribbon.clickStepForTest(0, MouseEvent.BUTTON1);
        ribbon.clickStepForTest(1, MouseEvent.BUTTON1);

        assertEquals(ChainRibbon.StepState.BYPASSED, ribbon.getStepState(0));
        assertEquals(ChainRibbon.StepState.OFF, ribbon.getStepState(1));
        assertEquals(0, ribbon.stepIndexesInState(
                ChainRibbon.StepState.FOCUSED).size());
    }
}
