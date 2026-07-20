package flash.pipeline.ui.variations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ParameterCombo {

    private final Map<ParameterKey, Object> values;

    public ParameterCombo(Map<? extends ParameterKey, ?> values) {
        if (values == null) {
            throw new IllegalArgumentException("values must not be null");
        }
        this.values = Collections.unmodifiableMap(copyInKeyOrder(values));
    }

    public static Builder builder() {
        return new Builder();
    }

    public Object get(ParameterKey id) {
        return values.get(id);
    }

    public Object get(ParameterId id) {
        return values.get(id);
    }

    public boolean contains(ParameterKey id) {
        return values.containsKey(id);
    }

    public boolean contains(ParameterId id) {
        return values.containsKey(id);
    }

    public Map<ParameterKey, Object> values() {
        return values;
    }

    public Map<ParameterKey, Object> getValues() {
        return values;
    }

    public String toCanonicalJson() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        List<ParameterKey> ids = new ArrayList<ParameterKey>(values.keySet());
        Collections.sort(ids, new Comparator<ParameterKey>() {
            @Override
            public int compare(ParameterKey a, ParameterKey b) {
                return compareStorageKeys(a, b);
            }
        });
        for (int i = 0; i < ids.size(); i++) {
            ParameterKey id = ids.get(i);
            out.put(canonicalJsonKey(id), values.get(id));
        }
        return CanonicalJson.write(out);
    }

    private static Map<ParameterKey, Object> copyInKeyOrder(Map<? extends ParameterKey, ?> source) {
        List<ParameterKey> ids = new ArrayList<ParameterKey>(source.keySet());
        Collections.sort(ids, new Comparator<ParameterKey>() {
            @Override
            public int compare(ParameterKey a, ParameterKey b) {
                return compareKeys(a, b);
            }
        });
        LinkedHashMap<ParameterKey, Object> out = new LinkedHashMap<ParameterKey, Object>();
        for (int i = 0; i < ids.size(); i++) {
            ParameterKey id = ids.get(i);
            if (id == null) {
                throw new IllegalArgumentException("parameter id must not be null");
            }
            out.put(id, normalizeValueForKey(id, source.get(id)));
        }
        return out;
    }

    private static int compareKeys(ParameterKey a, ParameterKey b) {
        String aKey = a == null ? "" : a.stableKey();
        String bKey = b == null ? "" : b.stableKey();
        int byStableKey = aKey.compareTo(bKey);
        if (byStableKey != 0) {
            return byStableKey;
        }
        return canonicalJsonKey(a).compareTo(canonicalJsonKey(b));
    }

    private static int compareStorageKeys(ParameterKey a, ParameterKey b) {
        if (a instanceof ParameterId && b instanceof ParameterId) {
            return ((ParameterId) a).compareTo((ParameterId) b);
        }
        return compareKeys(a, b);
    }

    private static String canonicalJsonKey(ParameterKey key) {
        if (key instanceof ParameterId) {
            return ((ParameterId) key).name();
        }
        return key == null ? "" : key.stableKey();
    }

    private static Object normalizeValueForKey(ParameterKey key, Object value) {
        if (key instanceof ParameterId && ((ParameterId) key).isMacroAxis()) {
            return MacroToken.tokenString(value);
        }
        return ParameterValueList.normalizeValue(value);
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
        private final LinkedHashMap<ParameterKey, Object> values =
                new LinkedHashMap<ParameterKey, Object>();

        private Builder() {
        }

        public Builder put(ParameterKey id, Object value) {
            if (id == null) {
                throw new IllegalArgumentException("id must not be null");
            }
            values.put(id, normalizeValueForKey(id, value));
            return this;
        }

        public Builder put(ParameterId id, Object value) {
            if (id == null) {
                throw new IllegalArgumentException("id must not be null");
            }
            return put((ParameterKey) id, value);
        }

        public ParameterCombo build() {
            return new ParameterCombo(values);
        }
    }
}
