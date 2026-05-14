package flash.pipeline.ui.variations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class PrimaryAxisPicker {

    private PrimaryAxisPicker() {
    }

    public static ParameterId pickCountCurveDriver(ParameterSweep sweep) {
        return null;
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
}
