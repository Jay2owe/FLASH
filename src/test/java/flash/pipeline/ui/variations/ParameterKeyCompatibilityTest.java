package flash.pipeline.ui.variations;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ParameterKeyCompatibilityTest {

    @Test
    public void parameterIdSweepStillBuildsExpectedCombos() {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.MAX_SIZE, ParameterValueList.ofInts(100));
        values.put(ParameterId.MIN_SIZE, ParameterValueList.ofInts(10, 20));
        values.put(ParameterId.THRESHOLD, ParameterValueList.ofInts(1, 2, 3));
        ParameterSweep sweep = new ParameterSweep(ParameterSweep.Method.CLASSICAL,
                values, CropSpec.full(), "DAPI", "abc");

        List<ParameterCombo> combos = sweep.combos();

        assertEquals(6L, sweep.cellCount());
        assertEquals(6, combos.size());
        assertEquals(Integer.valueOf(1), combos.get(0).get(ParameterId.THRESHOLD));
        assertEquals(Integer.valueOf(10), combos.get(0).get(ParameterId.MIN_SIZE));
        assertEquals(Integer.valueOf(100), combos.get(0).get(ParameterId.MAX_SIZE));
    }

    @Test
    public void filterParameterIdsProduceCartesianProductAndStableJsonKeys() {
        FilterParameterId sigma =
                new FilterParameterId(0, 0, 0, "Gaussian Blur", "sigma");
        FilterParameterId rolling =
                new FilterParameterId(0, 1, 0, "Subtract Background", "rolling");
        Map<ParameterKey, ParameterValueList> values =
                new LinkedHashMap<ParameterKey, ParameterValueList>();
        values.put(rolling, ParameterValueList.ofDoubles(4.0d, 5.0d));
        values.put(sigma, ParameterValueList.ofDoubles(1.0d, 2.0d, 3.0d));
        ParameterSweep sweep = new ParameterSweep(ParameterSweep.Method.FILTER,
                values, CropSpec.full(), "DAPI", "abc", "filter:test");

        List<ParameterCombo> combos = sweep.combos();
        String json = sweep.toCanonicalJson();

        assertEquals(6L, sweep.cellCount());
        assertEquals(6, combos.size());
        assertTrue(json.contains("\"filter.0.0.0.sigma\""));
        assertTrue(json.contains("\"filter.0.1.0.rolling\""));
    }
}
