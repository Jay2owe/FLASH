package flash.pipeline.ui.variations;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class ParameterSweepTest {

    @Test
    public void combosBuildCartesianProductInDeterministicOrder() {
        ParameterSweep sweep = sweepWithScrambledInputOrder();

        List<ParameterCombo> combos = sweep.combos();

        assertEquals(6L, sweep.cellCount());
        assertEquals(6, combos.size());
        assertEquals(Integer.valueOf(1), combos.get(0).get(ParameterId.THRESHOLD));
        assertEquals(Integer.valueOf(10), combos.get(0).get(ParameterId.MIN_SIZE));
        assertEquals(Integer.valueOf(100), combos.get(0).get(ParameterId.MAX_SIZE));
        assertEquals(Integer.valueOf(1), combos.get(1).get(ParameterId.THRESHOLD));
        assertEquals(Integer.valueOf(20), combos.get(1).get(ParameterId.MIN_SIZE));
        assertEquals(Integer.valueOf(2), combos.get(2).get(ParameterId.THRESHOLD));
        assertEquals(Integer.valueOf(3), combos.get(5).get(ParameterId.THRESHOLD));
        assertEquals(Integer.valueOf(20), combos.get(5).get(ParameterId.MIN_SIZE));
    }

    @Test
    public void combosDoNotDependOnMapInsertionOrder() {
        ParameterSweep first = sweepWithScrambledInputOrder();

        Map<ParameterId, ParameterValueList> values = new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.THRESHOLD, ParameterValueList.ofInts(1, 2, 3));
        values.put(ParameterId.MIN_SIZE, ParameterValueList.ofInts(10, 20));
        values.put(ParameterId.MAX_SIZE, ParameterValueList.ofInts(100));
        ParameterSweep second = new ParameterSweep(ParameterSweep.Method.CLASSICAL,
                values, CropSpec.full(), "DAPI", "abc");

        assertEquals(first.combos(), second.combos());
    }

    private static ParameterSweep sweepWithScrambledInputOrder() {
        Map<ParameterId, ParameterValueList> values = new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.MAX_SIZE, ParameterValueList.ofInts(100));
        values.put(ParameterId.MIN_SIZE, ParameterValueList.ofInts(10, 20));
        values.put(ParameterId.THRESHOLD, ParameterValueList.ofInts(1, 2, 3));
        return new ParameterSweep(ParameterSweep.Method.CLASSICAL,
                values, CropSpec.full(), "DAPI", "abc");
    }
}
