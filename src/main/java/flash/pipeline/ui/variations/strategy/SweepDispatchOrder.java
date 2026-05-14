package flash.pipeline.ui.variations.strategy;

import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterKey;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.ParameterValueList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class SweepDispatchOrder {

    private SweepDispatchOrder() {
    }

    public static List<ParameterCombo> order(ParameterSweep sweep) {
        if (sweep == null) {
            throw new IllegalArgumentException("sweep must not be null");
        }
        List<ParameterCombo> combos = sweep.combos();
        if (combos.size() <= 1) {
            return combos;
        }
        List<OrderedCombo> ordered = new ArrayList<OrderedCombo>(combos.size());
        for (int i = 0; i < combos.size(); i++) {
            ParameterCombo combo = combos.get(i);
            ordered.add(new OrderedCombo(combo, chebyshevDistance(sweep, combo), i));
        }
        Collections.sort(ordered, new Comparator<OrderedCombo>() {
            @Override
            public int compare(OrderedCombo a, OrderedCombo b) {
                int distance = Integer.compare(a.distance, b.distance);
                if (distance != 0) {
                    return distance;
                }
                return Integer.compare(a.originalIndex, b.originalIndex);
            }
        });
        List<ParameterCombo> out = new ArrayList<ParameterCombo>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            out.add(ordered.get(i).combo);
        }
        return out;
    }

    private static int chebyshevDistance(ParameterSweep sweep, ParameterCombo combo) {
        int distance = 0;
        for (Map.Entry<ParameterKey, ParameterValueList> entry : sweep.valueLists().entrySet()) {
            ParameterValueList values = entry.getValue();
            if (values == null || values.size() <= 1) {
                continue;
            }
            int medianIndex = (values.size() - 1) / 2;
            int valueIndex = values.values().indexOf(combo.get(entry.getKey()));
            if (valueIndex < 0) {
                valueIndex = 0;
            }
            distance = Math.max(distance, Math.abs(valueIndex - medianIndex));
        }
        return distance;
    }

    private static final class OrderedCombo {
        final ParameterCombo combo;
        final int distance;
        final int originalIndex;

        OrderedCombo(ParameterCombo combo, int distance, int originalIndex) {
            this.combo = combo;
            this.distance = distance;
            this.originalIndex = originalIndex;
        }
    }
}
