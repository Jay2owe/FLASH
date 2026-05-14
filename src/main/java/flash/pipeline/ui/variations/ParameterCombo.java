package flash.pipeline.ui.variations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ParameterCombo {

    private final Map<ParameterId, Object> values;

    public ParameterCombo(Map<ParameterId, ?> values) {
        if (values == null) {
            throw new IllegalArgumentException("values must not be null");
        }
        this.values = Collections.unmodifiableMap(copyInEnumOrder(values));
    }

    public static Builder builder() {
        return new Builder();
    }

    public Object get(ParameterId id) {
        return values.get(id);
    }

    public boolean contains(ParameterId id) {
        return values.containsKey(id);
    }

    public Map<ParameterId, Object> values() {
        return values;
    }

    public Map<ParameterId, Object> getValues() {
        return values;
    }

    public String toCanonicalJson() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        List<ParameterId> ids = new ArrayList<ParameterId>(values.keySet());
        Collections.sort(ids, new Comparator<ParameterId>() {
            @Override
            public int compare(ParameterId a, ParameterId b) {
                return a.name().compareTo(b.name());
            }
        });
        for (int i = 0; i < ids.size(); i++) {
            ParameterId id = ids.get(i);
            out.put(id.name(), values.get(id));
        }
        return CanonicalJson.write(out);
    }

    private static Map<ParameterId, Object> copyInEnumOrder(Map<ParameterId, ?> source) {
        List<ParameterId> ids = new ArrayList<ParameterId>(source.keySet());
        Collections.sort(ids);
        LinkedHashMap<ParameterId, Object> out = new LinkedHashMap<ParameterId, Object>();
        for (int i = 0; i < ids.size(); i++) {
            ParameterId id = ids.get(i);
            if (id == null) {
                throw new IllegalArgumentException("parameter id must not be null");
            }
            out.put(id, ParameterValueList.normalizeValue(source.get(id)));
        }
        return out;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ParameterCombo)) return false;
        ParameterCombo other = (ParameterCombo) obj;
        return values.equals(other.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return toCanonicalJson();
    }

    public static final class Builder {
        private final LinkedHashMap<ParameterId, Object> values =
                new LinkedHashMap<ParameterId, Object>();

        private Builder() {
        }

        public Builder put(ParameterId id, Object value) {
            if (id == null) {
                throw new IllegalArgumentException("id must not be null");
            }
            values.put(id, ParameterValueList.normalizeValue(value));
            return this;
        }

        public ParameterCombo build() {
            return new ParameterCombo(values);
        }
    }
}
