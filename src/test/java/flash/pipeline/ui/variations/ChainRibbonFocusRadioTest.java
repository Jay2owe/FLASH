package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.image.dag.DagIR;
import flash.pipeline.image.dag.IjmToDagLoader;

import org.junit.Test;

import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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
    public void sweepToggleModeClickCyclesThroughPlanStates() {
        ChainRibbon ribbon = new ChainRibbon(FilterMacroEditorModel.parse(
                "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n"
                        + "run(\"Median...\", \"radius=1 stack\");"));
        ribbon.setInteractionMode(ChainRibbon.InteractionMode.SWEEP_TOGGLE);

        ribbon.clickStepForTest(0, MouseEvent.BUTTON1);
        assertEquals(ChainRibbon.StepState.SWEPT, ribbon.getStepState(0));
        ribbon.clickStepForTest(0, MouseEvent.BUTTON1);
        assertEquals(ChainRibbon.StepState.BYPASSED, ribbon.getStepState(0));
        ribbon.clickStepForTest(0, MouseEvent.BUTTON1);
        assertEquals(ChainRibbon.StepState.OFF, ribbon.getStepState(0));
        ribbon.clickStepForTest(0, MouseEvent.BUTTON1);
        assertEquals(ChainRibbon.StepState.FIXED, ribbon.getStepState(0));
    }

    @Test
    public void middleClickInSweepToggleSkipsBypassedState() {
        ChainRibbon ribbon = new ChainRibbon(FilterMacroEditorModel.parse(
                "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n"
                        + "run(\"Median...\", \"radius=1 stack\");"));
        ribbon.setInteractionMode(ChainRibbon.InteractionMode.SWEEP_TOGGLE);

        ribbon.clickStepForTest(0, MouseEvent.BUTTON2);
        assertEquals(ChainRibbon.StepState.SWEPT, ribbon.getStepState(0));
        ribbon.clickStepForTest(0, MouseEvent.BUTTON2);
        assertEquals(ChainRibbon.StepState.OFF, ribbon.getStepState(0));
        ribbon.clickStepForTest(0, MouseEvent.BUTTON2);
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

    @Test
    public void switchingToPassiveClearsDisabledStates() {
        ChainRibbon ribbon = new ChainRibbon(FilterMacroEditorModel.parse(
                "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n"
                        + "run(\"Median...\", \"radius=1 stack\");"));
        ribbon.setStepState(0, ChainRibbon.StepState.BYPASSED);
        ribbon.setStepState(1, ChainRibbon.StepState.OFF);

        ribbon.setInteractionMode(ChainRibbon.InteractionMode.PASSIVE);

        assertEquals(ChainRibbon.StepState.FIXED, ribbon.getStepState(0));
        assertEquals(ChainRibbon.StepState.FIXED, ribbon.getStepState(1));
    }

    @Test
    public void disabledStatesDoNotBecomeFocusedFromLeftClick() {
        ChainRibbon ribbon = new ChainRibbon(FilterMacroEditorModel.parse(
                "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n"
                        + "run(\"Median...\", \"radius=1 stack\");\n"
                        + "run(\"Mean...\", \"radius=1 stack\");"));
        ribbon.setInteractionMode(ChainRibbon.InteractionMode.FOCUS);
        ribbon.setStepState(0, ChainRibbon.StepState.BYPASSED);
        ribbon.setStepState(2, ChainRibbon.StepState.OFF);

        ribbon.clickStepForTest(0, MouseEvent.BUTTON1);
        ribbon.clickStepForTest(2, MouseEvent.BUTTON1);
        ribbon.clickStepForTest(1, MouseEvent.BUTTON1);

        assertEquals(ChainRibbon.StepState.BYPASSED, ribbon.getStepState(0));
        assertEquals(ChainRibbon.StepState.FOCUSED, ribbon.getStepState(1));
        assertEquals(ChainRibbon.StepState.OFF, ribbon.getStepState(2));
        assertEquals(1, ribbon.stepIndexesInState(
                ChainRibbon.StepState.FOCUSED).size());
    }

    @Test
    public void disabledChainStatesOmitMatchingLinearMacroRuns() {
        String macro = "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n"
                + "run(\"Median...\", \"radius=1 stack\");\n"
                + "run(\"Mean...\", \"radius=1 stack\");\n";

        String rendered = MacroVariationsDialog.applyDisabledChainStates(macro,
                Arrays.asList(ChainRibbon.StepState.FIXED,
                        ChainRibbon.StepState.BYPASSED,
                        ChainRibbon.StepState.OFF));

        String body = executableBody(rendered);
        assertTrue(body.contains("run(\"Gaussian Blur...\""));
        assertFalse(body.contains("run(\"Median...\""));
        assertFalse(body.contains("run(\"Mean...\""));

        DagIR embedded = IjmToDagLoader.loadEmbeddedDag(rendered);
        assertNotNull(embedded);
        assertFalse(embedded.lines.get(0).ops.get(0).disabled);
        assertTrue(embedded.lines.get(0).ops.get(1).disabled);
        assertTrue(embedded.lines.get(0).ops.get(2).disabled);
    }

    @Test(expected = IllegalStateException.class)
    public void disabledChainStatesRejectBranchedMacro() {
        String macro = "run(\"Duplicate...\", \"title=_dens duplicate\");\n"
                + "run(\"Subtract Background...\", \"rolling=20 stack\");\n"
                + "run(\"Duplicate...\", \"title=_edge duplicate\");\n"
                + "run(\"Variance...\", \"radius=2 stack\");\n"
                + "imageCalculator(\"Add create\", \"_dens\", \"_edge\");\n";

        MacroVariationsDialog.applyDisabledChainStates(macro,
                Arrays.asList(ChainRibbon.StepState.FIXED,
                        ChainRibbon.StepState.BYPASSED));
    }

    @Test
    public void allDisabledChainStepFilterUsesNoMatchSentinel() {
        Set<Integer> disabled = new LinkedHashSet<Integer>();
        disabled.add(Integer.valueOf(0));
        disabled.add(Integer.valueOf(1));

        Set<Integer> filter = MacroVariationsDialog.chainStepFilterForEditor(2,
                Collections.<Integer>emptySet(), disabled);

        assertEquals(1, filter.size());
        assertTrue(filter.contains(Integer.valueOf(
                MacroVariationsDialog.NO_CHAIN_STEP_FILTER_MATCH)));
    }

    private static String executableBody(String macro) {
        StringBuilder sb = new StringBuilder();
        String[] lines = macro.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (!trimmed.startsWith("//")) {
                sb.append(lines[i]).append('\n');
            }
        }
        return sb.toString();
    }
}
