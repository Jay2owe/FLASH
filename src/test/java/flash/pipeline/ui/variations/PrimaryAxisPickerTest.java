package flash.pipeline.ui.variations;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public class PrimaryAxisPickerTest {

    @Test
    public void macroOnlySweepHasNoCountCurveDriver() {
        ParameterSweep sweep = sweep(ParameterSweep.Method.CLASSICAL,
                ParameterId.MACRO, ParameterValueList.ofStrings(
                        macro("Blur").token(), macro("Median").token()));

        assertNull(PrimaryAxisPicker.pickCountCurveDriver(sweep));
        assertFalse(PrimaryAxisPicker.sweptNumericAxes(sweep)
                .contains(ParameterId.MACRO));
    }

    @Test
    public void macroPlusNumericSweepUsesNumericCountCurveDriver() {
        ParameterSweep sweep = sweep(ParameterSweep.Method.CLASSICAL,
                ParameterId.THRESHOLD, ParameterValueList.ofDoubles(10, 20, 30),
                ParameterId.MACRO, ParameterValueList.ofStrings(
                        macro("Blur").token(), macro("Median").token()));

        assertEquals(ParameterId.THRESHOLD,
                PrimaryAxisPicker.pickCountCurveDriver(sweep));
    }

    @Test
    public void macroPlusModelSweepHasNoCountCurveDriver() {
        ParameterSweep sweep = sweep(ParameterSweep.Method.CELLPOSE,
                ParameterId.MODEL, ParameterValueList.ofStrings("cyto3", "nuclei"),
                ParameterId.MACRO, ParameterValueList.ofStrings(
                        macro("Blur").token(), macro("Median").token()));

        assertNull(PrimaryAxisPicker.pickCountCurveDriver(sweep));
    }

    private static ParameterSweep sweep(ParameterSweep.Method method,
                                        Object... pairs) {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put((ParameterId) pairs[i], (ParameterValueList) pairs[i + 1]);
        }
        return new ParameterSweep(method, values, CropSpec.full(),
                "DAPI", "primary-axis-picker-test");
    }

    private static MacroVariation macro(String name) {
        return MacroVariation.pasted(name,
                "run(\"Gaussian Blur...\", \"sigma=1 stack\");\n// " + name);
    }
}
