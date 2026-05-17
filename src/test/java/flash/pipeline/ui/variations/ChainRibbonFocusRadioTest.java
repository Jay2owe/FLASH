package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterMacroEditorModel;

import org.junit.Test;

import java.awt.event.MouseEvent;

import static org.junit.Assert.assertEquals;

public class ChainRibbonFocusRadioTest {

    @Test
    public void focusModeClickUsesRadioSemantics() {
        ChainRibbon ribbon = new ChainRibbon(FilterMacroEditorModel.parse(
                "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n"
                        + "run(\"Median...\", \"radius=1 stack\");\n"
                        + "run(\"Mean...\", \"radius=1 stack\");"));
        ribbon.setInteractionMode(ChainRibbon.InteractionMode.FOCUS);

        ribbon.clickStepForTest(0, MouseEvent.BUTTON1);
        assertEquals(ChainRibbon.StepState.FOCUSED, ribbon.getStepState(0));

        ribbon.clickStepForTest(1, MouseEvent.BUTTON1);

        assertEquals(ChainRibbon.StepState.FIXED, ribbon.getStepState(0));
        assertEquals(ChainRibbon.StepState.FOCUSED, ribbon.getStepState(1));
        assertEquals(1, ribbon.stepIndexesInState(
                ChainRibbon.StepState.FOCUSED).size());
    }

    @Test
    public void clicksInPassiveModeDoNothing() {
        ChainRibbon ribbon = new ChainRibbon(FilterMacroEditorModel.parse(
                "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n"
                        + "run(\"Median...\", \"radius=1 stack\");"));
        ribbon.setInteractionMode(ChainRibbon.InteractionMode.PASSIVE);

        ribbon.clickStepForTest(0, MouseEvent.BUTTON1);
        ribbon.clickStepForTest(1, MouseEvent.BUTTON1);

        assertEquals(ChainRibbon.StepState.FIXED, ribbon.getStepState(0));
        assertEquals(ChainRibbon.StepState.FIXED, ribbon.getStepState(1));
    }

    @Test
    public void sweepToggleModeClickTogglesFixedAndSwept() {
        ChainRibbon ribbon = new ChainRibbon(FilterMacroEditorModel.parse(
                "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n"
                        + "run(\"Median...\", \"radius=1 stack\");"));
        ribbon.setInteractionMode(ChainRibbon.InteractionMode.SWEEP_TOGGLE);

        ribbon.clickStepForTest(0, MouseEvent.BUTTON1);
        assertEquals(ChainRibbon.StepState.SWEPT, ribbon.getStepState(0));
        ribbon.clickStepForTest(0, MouseEvent.BUTTON1);
        assertEquals(ChainRibbon.StepState.FIXED, ribbon.getStepState(0));
    }

    @Test
    public void switchingFromSweepToFocusClearsSweptStates() {
        ChainRibbon ribbon = new ChainRibbon(FilterMacroEditorModel.parse(
                "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n"
                        + "run(\"Median...\", \"radius=1 stack\");"));
        ribbon.setInteractionMode(ChainRibbon.InteractionMode.SWEEP_TOGGLE);
        ribbon.setStepState(0, ChainRibbon.StepState.SWEPT);
        ribbon.setStepState(1, ChainRibbon.StepState.SWEPT);

        ribbon.setInteractionMode(ChainRibbon.InteractionMode.FOCUS);

        assertEquals(ChainRibbon.StepState.FIXED, ribbon.getStepState(0));
        assertEquals(ChainRibbon.StepState.FIXED, ribbon.getStepState(1));
    }
}
