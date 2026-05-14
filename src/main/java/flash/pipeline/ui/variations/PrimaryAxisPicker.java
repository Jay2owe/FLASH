package flash.pipeline.ui.variations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class PrimaryAxisPicker {

    private static final ParameterId[] STARDIST_COUNT_DRIVER_PRIORITY = {
            ParameterId.PROB_THRESH,
            ParameterId.NMS_THRESH,
            ParameterId.LINKING_MAX,
            ParameterId.GAP_CLOSING_MAX,
            ParameterId.AREA_MIN,
            ParameterId.AREA_MAX,
            ParameterId.QUALITY_MIN,
            ParameterId.INTENSITY_MIN,
            ParameterId.FRAME_GAP
    };

    private static final ParameterId[] CELLPOSE_COUNT_DRIVER_PRIORITY = {
            ParameterId.CELLPROB_THRESHOLD,
            ParameterId.DIAMETER,
            ParameterId.FLOW_THRESHOLD
    };

    private static final ParameterId[] CLASSICAL_COUNT_DRIVER_PRIORITY = {
            ParameterId.THRESHOLD,
            ParameterId.MIN_SIZE,
            ParameterId.MAX_SIZE
    };

    private PrimaryAxisPicker() {
    }

    public static ParameterId pickCountCurveDriver(ParameterSweep sweep) {
        List<ParameterId> swept = sweptNumericAxes(sweep);
        if (sweep == null || swept.isEmpty()) {
            return null;
        }
        ParameterId[] priority;
        switch (sweep.method()) {
            case STARDIST:
                priority = STARDIST_COUNT_DRIVER_PRIORITY;
                break;
            case CELLPOSE:
                priority = CELLPOSE_COUNT_DRIVER_PRIORITY;
                break;
            case CLASSICAL:
                priority = CLASSICAL_COUNT_DRIVER_PRIORITY;
                break;
            default:
                return null;
        }
        return orderableDriver(firstSwept(swept, priority));
    }

    public static List<ParameterId> sweptNumericAxes(ParameterSweep sweep) {
        List<ParameterId> axes = new ArrayList<ParameterId>();
        if (sweep == null) {
            return axes;
        }
        for (Map.Entry<ParameterKey, ParameterValueList> entry
                : sweep.valueLists().entrySet()) {
            ParameterKey key = entry.getKey();
            if (!(key instanceof ParameterId)) {
                continue;
            }
            ParameterId id = (ParameterId) key;
            ParameterValueList values = entry.getValue();
            // MODEL and MACRO are categorical: orderable() excludes them from
            // count-curve and STABLE COUNT eligibility even when swept.
            if (!id.orderable()
                    || values == null || values.size() <= 1
                    || !allNumeric(values)) {
                continue;
            }
            axes.add(id);
        }
        return axes;
    }

    private static boolean allNumeric(ParameterValueList values) {
        for (int i = 0; i < values.size(); i++) {
            if (!(values.get(i) instanceof Number)) {
                return false;
            }
        }
        return true;
    }

    private static ParameterId firstSwept(List<ParameterId> swept,
                                          ParameterId[] priority) {
        for (int i = 0; i < priority.length; i++) {
            ParameterId id = priority[i];
            if (swept.contains(id)) {
                return id;
            }
        }
        return null;
    }

    private static ParameterId orderableDriver(ParameterId driver) {
        return driver != null && driver.orderable() ? driver : null;
    }
}
