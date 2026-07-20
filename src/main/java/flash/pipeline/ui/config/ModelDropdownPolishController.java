package flash.pipeline.ui.config;

import flash.pipeline.segmentation.catalog.ModelEntry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ModelDropdownPolishController {
    public static final class DefaultsState {
        public final boolean pending;
        public final String modelKey;
        public final Map<String, Double> previousValues;
        public final Map<String, Double> suggestedValues;

        DefaultsState(boolean pending,
                      String modelKey,
                      Map<String, Double> previousValues,
                      Map<String, Double> suggestedValues) {
            this.pending = pending;
            this.modelKey = modelKey == null ? "" : modelKey;
            this.previousValues = immutable(previousValues);
            this.suggestedValues = immutable(suggestedValues);
        }

        public Map<String, Double> applyValues() {
            return suggestedValues;
        }

        public Map<String, Double> revertValues() {
            return previousValues;
        }
    }

    public DefaultsState select(ModelEntry entry, Map<String, Double> currentValues) {
        Map<String, Double> previous = copy(currentValues);
        Map<String, Double> suggested = copy(currentValues);
        if (entry != null) {
            for (Map.Entry<String, Object> item : entry.defaults.entrySet()) {
                Double parsed = doubleValue(item.getValue());
                if (parsed != null) {
                    suggested.put(item.getKey(), parsed);
                }
            }
        }
        boolean changed = !suggested.equals(previous);
        return new DefaultsState(changed,
                entry == null ? "" : entry.modelKey,
                previous, suggested);
    }

    private static Map<String, Double> copy(Map<String, Double> values) {
        LinkedHashMap<String, Double> out = new LinkedHashMap<String, Double>();
        if (values != null) {
            for (Map.Entry<String, Double> entry : values.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    out.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return out;
    }

    private static Map<String, Double> immutable(Map<String, Double> values) {
        return Collections.unmodifiableMap(copy(values));
    }

    private static Double doubleValue(Object value) {
        if (value instanceof Number) {
            double parsed = ((Number) value).doubleValue();
            return Double.isFinite(parsed) ? Double.valueOf(parsed) : null;
        }
        if (value != null) {
            try {
                double parsed = Double.parseDouble(String.valueOf(value));
                return Double.isFinite(parsed) ? Double.valueOf(parsed) : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
