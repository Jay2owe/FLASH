package flash.pipeline.intensity.spatial;

import ij.measure.ResultsTable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Column/value bundle returned by one or more intensity-spatial analyses.
 */
public final class IntensitySpatialResult {
    private final LinkedHashMap<String, Double> values;

    public IntensitySpatialResult(Map<String, Double> values) {
        this.values = new LinkedHashMap<String, Double>();
        if (values != null) {
            for (Map.Entry<String, Double> entry : values.entrySet()) {
                if (entry.getKey() != null && !entry.getKey().trim().isEmpty()) {
                    this.values.put(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    public static IntensitySpatialResult empty() {
        return new IntensitySpatialResult(Collections.<String, Double>emptyMap());
    }

    public static IntensitySpatialResult nanFor(List<String> columns) {
        LinkedHashMap<String, Double> out = new LinkedHashMap<String, Double>();
        if (columns != null) {
            for (String column : columns) {
                out.put(column, Double.valueOf(Double.NaN));
            }
        }
        return new IntensitySpatialResult(out);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public Map<String, Double> values() {
        return Collections.unmodifiableMap(values);
    }

    public double value(String column) {
        Double value = values.get(column);
        return value == null ? Double.NaN : value.doubleValue();
    }

    public IntensitySpatialResult plus(IntensitySpatialResult other) {
        if (other == null || other.values.isEmpty()) return this;
        LinkedHashMap<String, Double> merged = new LinkedHashMap<String, Double>(values);
        merged.putAll(other.values);
        return new IntensitySpatialResult(merged);
    }

    public void writeTo(ResultsTable table, int row) {
        if (table == null) return;
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            Double value = entry.getValue();
            table.setValue(entry.getKey(), row, value == null ? Double.NaN : value.doubleValue());
        }
    }
}
