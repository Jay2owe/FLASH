package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.ui.sandbox.FilterAlternatives;
import flash.pipeline.ui.sandbox.FilterAlternatives.SlotRole;

import org.junit.Test;

import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class StepsModeSweepBuildTest {

    @Test
    public void smoothingStepBuildsFilterByScaleCartesianSweep() {
        ParameterSweep sweep = MacroVariationsDialog
                .buildStepsSubstitutionSweepForTest(
                        FilterMacroEditorModel.parse(smoothingMacro()),
                        CropSpec.full(),
                        "DAPI",
                        "source-a",
                        "filter:macro:steps:0",
                        0);

        assertEquals(FilterAlternatives.alternativesFor(SlotRole.SMOOTHING).size()
                * 3L, sweep.cellCount());
        assertTrue(hasValueOnAxis(sweep, SlotSubstitutionKey.Axis.FILTER,
                "Gaussian Blur"));
        assertTrue(hasValueOnAxis(sweep, SlotSubstitutionKey.Axis.SCALE,
                CanonicalScale.MEDIUM.label()));

        SlotSubstitutionCombo decoded =
                SlotSubstitutionCombo.from(sweep.combos().get(0));
        assertNotNull(decoded);
        assertEquals(0, decoded.stepIndex());
    }

    @Test
    public void backgroundRemovalStepHasNoNativePeerAlternatives() {
        try {
            MacroVariationsDialog.buildStepsSubstitutionSweepForTest(
                    FilterMacroEditorModel.parse(backgroundOnlyMacro()),
                    CropSpec.full(),
                    "DAPI",
                    "source-a",
                    "filter:macro:steps:0",
                    0);
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains(
                    "No native alternatives available"));
            return;
        }
        throw new AssertionError("Expected background-only step to be rejected");
    }

    @Test
    public void nonFocusableStepTooltipExplainsMissingAlternatives() {
        ChainRibbon ribbon = new ChainRibbon(FilterMacroEditorModel.parse(
                backgroundOnlyMacro()));
        Map<Integer, String> reasons = new HashMap<Integer, String>();
        reasons.put(Integer.valueOf(0), "No native alternatives available");

        ribbon.setStepsMode(true);
        ribbon.setFocusableStepIndexes(Collections.<Integer>emptySet(), reasons);
        ribbon.clickStepForTest(0, MouseEvent.BUTTON1);

        assertEquals(ChainRibbon.StepState.FIXED, ribbon.getStepState(0));
        assertTrue(ribbon.stepComponentForTest(0).getToolTipText()
                .contains("No native alternatives available"));
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
}
