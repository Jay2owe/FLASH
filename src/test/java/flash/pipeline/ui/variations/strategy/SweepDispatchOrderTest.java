package flash.pipeline.ui.variations.strategy;

import flash.pipeline.ui.variations.CropSpec;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterId;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.ParameterValueList;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class SweepDispatchOrderTest {

    @Test
    public void oneDimensionalSweepStartsAtMedianThenRadiatesOut() {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.THRESHOLD, ParameterValueList.ofInts(10, 20, 30, 40, 50));
        values.put(ParameterId.MIN_SIZE, ParameterValueList.ofInts(1));
        values.put(ParameterId.MAX_SIZE, ParameterValueList.ofInts(1000));
        ParameterSweep sweep = new ParameterSweep(ParameterSweep.Method.CLASSICAL,
                values, CropSpec.full(), "DAPI", "hash");

        List<ParameterCombo> ordered = SweepDispatchOrder.order(sweep);

        assertEquals(Integer.valueOf(30), ordered.get(0).get(ParameterId.THRESHOLD));
        assertEquals(Integer.valueOf(20), ordered.get(1).get(ParameterId.THRESHOLD));
        assertEquals(Integer.valueOf(40), ordered.get(2).get(ParameterId.THRESHOLD));
        assertEquals(Integer.valueOf(10), ordered.get(3).get(ParameterId.THRESHOLD));
        assertEquals(Integer.valueOf(50), ordered.get(4).get(ParameterId.THRESHOLD));
    }

    @Test
    public void twoDimensionalSweepStartsAtCentreCell() {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.THRESHOLD, ParameterValueList.ofInts(1, 2, 3));
        values.put(ParameterId.MIN_SIZE, ParameterValueList.ofInts(10, 20, 30));
        values.put(ParameterId.MAX_SIZE, ParameterValueList.ofInts(1000));
        ParameterSweep sweep = new ParameterSweep(ParameterSweep.Method.CLASSICAL,
                values, CropSpec.full(), "DAPI", "hash");

        List<ParameterCombo> ordered = SweepDispatchOrder.order(sweep);

        assertEquals(Integer.valueOf(2), ordered.get(0).get(ParameterId.THRESHOLD));
        assertEquals(Integer.valueOf(20), ordered.get(0).get(ParameterId.MIN_SIZE));
        assertEquals(9, ordered.size());
    }
}
