package flash.pipeline.ui.variations.strategy;

import flash.pipeline.ui.variations.CropSpec;
import flash.pipeline.ui.variations.ParameterId;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.ParameterValueList;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StarDistFastNmsEligibilityTest {

    @Test
    public void probAndNmsSweepIsRejectedUntilParityIsEnabled() {
        ParameterSweep sweep = starDistSweep(ParameterId.PROB_THRESH,
                ParameterId.NMS_THRESH);

        assertFalse(StarDistFastNms.canHandle(sweep));
        assertFalse(StarDistFastNms.canHandle(sweep, false));
        assertTrue(StarDistFastNms.canHandle(sweep, true));
    }

    @Test
    public void linkingFieldsForceSlowPathEvenWhenParityIsEnabled() {
        assertFalse(StarDistFastNms.canHandle(
                starDistSweep(ParameterId.LINKING_MAX), true));
        assertFalse(StarDistFastNms.canHandle(
                starDistSweep(ParameterId.GAP_CLOSING_MAX), true));
        assertFalse(StarDistFastNms.canHandle(
                starDistSweep(ParameterId.FRAME_GAP), true));
    }

    @Test
    public void postFilterFieldsForceSlowPathEvenWhenParityIsEnabled() {
        assertFalse(StarDistFastNms.canHandle(
                starDistSweep(ParameterId.AREA_MIN), true));
        assertFalse(StarDistFastNms.canHandle(
                starDistSweep(ParameterId.AREA_MAX), true));
        assertFalse(StarDistFastNms.canHandle(
                starDistSweep(ParameterId.QUALITY_MIN), true));
        assertFalse(StarDistFastNms.canHandle(
                starDistSweep(ParameterId.INTENSITY_MIN), true));
    }

    @Test
    public void nonStarDistOrUnknownParametersAreRejected() {
        Map<ParameterId, ParameterValueList> classical =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        classical.put(ParameterId.THRESHOLD, ParameterValueList.ofInts(10, 20));
        assertFalse(StarDistFastNms.canHandle(new ParameterSweep(
                ParameterSweep.Method.CLASSICAL,
                classical,
                CropSpec.full(),
                "DAPI",
                "hash"), true));

        assertFalse(StarDistFastNms.canHandle(
                starDistSweep(ParameterId.DIAMETER), true));
    }

    private static ParameterSweep starDistSweep(ParameterId variedId) {
        return starDistSweep(new ParameterId[] {variedId});
    }

    private static ParameterSweep starDistSweep(ParameterId firstVariedId,
                                               ParameterId secondVariedId) {
        return starDistSweep(new ParameterId[] {firstVariedId, secondVariedId});
    }

    private static ParameterSweep starDistSweep(ParameterId[] variedIds) {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.PROB_THRESH,
                valuesFor(ParameterId.PROB_THRESH, variedIds, 0.3d, 0.5d));
        values.put(ParameterId.NMS_THRESH,
                valuesFor(ParameterId.NMS_THRESH, variedIds, 0.3d, 0.5d));
        values.put(ParameterId.LINKING_MAX,
                valuesFor(ParameterId.LINKING_MAX, variedIds, 12.0d, 18.0d));
        values.put(ParameterId.GAP_CLOSING_MAX,
                valuesFor(ParameterId.GAP_CLOSING_MAX, variedIds, 12.0d, 18.0d));
        values.put(ParameterId.FRAME_GAP,
                contains(variedIds, ParameterId.FRAME_GAP)
                        ? ParameterValueList.ofInts(1, 2)
                        : ParameterValueList.ofInts(1));
        values.put(ParameterId.AREA_MIN,
                valuesFor(ParameterId.AREA_MIN, variedIds, 0.0d, 5.0d));
        values.put(ParameterId.AREA_MAX,
                valuesFor(ParameterId.AREA_MAX, variedIds, 100.0d, 200.0d));
        values.put(ParameterId.QUALITY_MIN,
                valuesFor(ParameterId.QUALITY_MIN, variedIds, 0.0d, 0.3d));
        values.put(ParameterId.INTENSITY_MIN,
                valuesFor(ParameterId.INTENSITY_MIN, variedIds, 0.0d, 50.0d));
        if (contains(variedIds, ParameterId.DIAMETER)) {
            values.put(ParameterId.DIAMETER, ParameterValueList.ofDoubles(20.0d, 30.0d));
        }
        return new ParameterSweep(ParameterSweep.Method.STARDIST,
                values,
                CropSpec.full(),
                "DAPI",
                "hash");
    }

    private static ParameterValueList valuesFor(ParameterId id,
                                                ParameterId[] variedIds,
                                                double first,
                                                double second) {
        if (contains(variedIds, id)) {
            return ParameterValueList.ofDoubles(first, second);
        }
        return ParameterValueList.ofDoubles(first);
    }

    private static boolean contains(ParameterId[] ids, ParameterId target) {
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] == target) {
                return true;
            }
        }
        return false;
    }
}
