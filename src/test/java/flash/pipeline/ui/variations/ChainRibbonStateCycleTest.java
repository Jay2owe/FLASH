package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterMacroEditorModel;

import org.junit.Test;

import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ChainRibbonStateCycleTest {

    @Test
    public void numericStepCyclesThroughAllStatesAndNotifies() {
        ChainRibbon ribbon = new ChainRibbon(FilterMacroEditorModel.parse(
                "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n"
                        + "run(\"Median...\", \"radius=1 stack\");"));
        final List<ChainRibbon.StepState> events =
                new ArrayList<ChainRibbon.StepState>();
        ribbon.addListener(new ChainRibbon.Listener() {
            @Override public void stepStateChanged(int stepIndex,
                                                   ChainRibbon.StepState newState) {
                assertEquals(0, stepIndex);
                events.add(newState);
            }
        });

        ribbon.clickStepForTest(0, MouseEvent.BUTTON1);
        assertEquals(ChainRibbon.StepState.SWEPT, ribbon.getStepState(0));
        ribbon.clickStepForTest(0, MouseEvent.BUTTON1);
        assertEquals(ChainRibbon.StepState.BYPASSED, ribbon.getStepState(0));
        ribbon.clickStepForTest(0, MouseEvent.BUTTON1);
        assertEquals(ChainRibbon.StepState.OFF, ribbon.getStepState(0));
        ribbon.clickStepForTest(0, MouseEvent.BUTTON1);
        assertEquals(ChainRibbon.StepState.FIXED, ribbon.getStepState(0));

        assertEquals(4, events.size());
        assertEquals(ChainRibbon.StepState.SWEPT, events.get(0));
        assertEquals(ChainRibbon.StepState.BYPASSED, events.get(1));
        assertEquals(ChainRibbon.StepState.OFF, events.get(2));
        assertEquals(ChainRibbon.StepState.FIXED, events.get(3));
    }

    @Test
    public void nonNumericStepSkipsSweptState() {
        ChainRibbon ribbon = new ChainRibbon(FilterMacroEditorModel.parse(
                "run(\"Auto Local Threshold\", \"method=Bernsen white stack\");"));

        ribbon.clickStepForTest(0, MouseEvent.BUTTON1);

        assertEquals(ChainRibbon.StepState.BYPASSED, ribbon.getStepState(0));
    }
}
