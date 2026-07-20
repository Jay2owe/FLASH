package flash.pipeline.ui.variations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ParameterValueList {

    private final List<Object> values;

    public ParameterValueList(List<?> values) {
        if (values == null) {
            throw new IllegalArgumentException("values must not be null");
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
        List<Object> copy = new ArrayList<Object>(values.size());
        for (int i = 0; i < values.size(); i++) {
            copy.add(normalizeValue(values.get(i)));
        }
        this.values = Collections.unmodifiableList(copy);
    }

    public static ParameterValueList of(List<?> values) {
        return new ParameterValueList(values);
    }

    public static ParameterValueList ofDoubles(double... values) {
        List<Object> out = new ArrayList<Object>();
        if (values != null) {
            for (int i = 0; i < values.length; i++) {
                out.add(Double.valueOf(values[i]));
            }
        }
        return new ParameterValueList(out);
    }

    public static ParameterValueList ofInts(int... values) {
        List<Object> out = new ArrayList<Object>();
        if (values != null) {
            for (int i = 0; i < values.length; i++) {
                out.add(Integer.valueOf(values[i]));
            }
        }
        return new ParameterValueList(out);
    }

    public static ParameterValueList ofStrings(String... values) {
        List<Object> out = new ArrayList<Object>();
        if (values != null) {
            for (int i = 0; i < values.length; i++) {
                out.add(values[i]);
            }
        }
        return new ParameterValueList(out);
    }

    public List<Object> values() {
        return values;
    }

    public List<Object> getValues() {
        return values;
    }

    public Object get(int index) {
        return values.get(index);
    }

    public int size() {
        return values.size();
    }

    public String toCanonicalJson() {
        return CanonicalJson.write(values);
    }

    static Object normalizeValue(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("parameter values must not contain null");
        }
        if (value instanceof String) {
            return value;
        }
        if (value instanceof Integer) {
            return value;
        }
        if (value instanceof Short || value instanceof Byte) {
            return Integer.valueOf(((Number) value).intValue());
        }
        if (value instanceof Long) {
            long longValue = ((Long) value).longValue();
            if (longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE) {
                return Integer.valueOf((int) longValue);
            }
            return value;
        }
        if (value instanceof Float || value instanceof Double) {
            double doubleValue = ((Number) value).doubleValue();
            if (Double.isNaN(doubleValue) || Double.isInfinite(doubleValue)) {
                throw new IllegalArgumentException("parameter numeric values must be finite");
            }
            return Double.valueOf(doubleValue);
        }
        throw new IllegalArgumentException("unsupported parameter value type: "
                + value.getClass().getName());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ParameterValueList)) return false;
        ParameterValueList other = (ParameterValueList) obj;
        return values.equals(other.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
