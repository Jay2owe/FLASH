package flash.pipeline.ui.variations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ParameterSweep {

    public enum Method {
        CLASSICAL("Classical"),
        STARDIST("StarDist"),
        CELLPOSE("Cellpose"),
        FILTER("Filter");

        private final String label;

        Method(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final Method method;
    private final Map<ParameterKey, ParameterValueList> valueLists;
    private final CropSpec cropSpec;
    private final String channelName;
    private final String sourceImageHash;
    private final String cacheNamespace;
    private final MacroVariationSet macroVariations;

    public ParameterSweep(Method method,
                          Map<? extends ParameterKey, ParameterValueList> valueLists,
                          CropSpec cropSpec,
                          String channelName,
                          String sourceImageHash) {
        this(method, valueLists, cropSpec, channelName, sourceImageHash, "", null);
    }

    public ParameterSweep(Method method,
                          Map<? extends ParameterKey, ParameterValueList> valueLists,
                          CropSpec cropSpec,
                          String channelName,
                          String sourceImageHash,
                          MacroVariationSet macroVariations) {
        this(method, valueLists, cropSpec, channelName, sourceImageHash, "",
                macroVariations);
    }

    public ParameterSweep(Method method,
                          Map<? extends ParameterKey, ParameterValueList> valueLists,
                          CropSpec cropSpec,
                          String channelName,
                          String sourceImageHash,
                          String cacheNamespace) {
        this(method, valueLists, cropSpec, channelName, sourceImageHash,
                cacheNamespace, null);
    }

    public ParameterSweep(Method method,
                          Map<? extends ParameterKey, ParameterValueList> valueLists,
                          CropSpec cropSpec,
                          String channelName,
                          String sourceImageHash,
                          String cacheNamespace,
                          MacroVariationSet macroVariations) {
        if (method == null) {
            throw new IllegalArgumentException("method must not be null");
        }
        if (valueLists == null) {
            throw new IllegalArgumentException("valueLists must not be null");
        }
        this.method = method;
        this.valueLists = Collections.unmodifiableMap(copyInKeyOrder(valueLists));
        this.cropSpec = cropSpec == null ? CropSpec.full() : cropSpec;
        this.channelName = channelName == null ? "" : channelName;
        this.sourceImageHash = sourceImageHash == null ? "" : sourceImageHash;
        this.cacheNamespace = cacheNamespace == null ? "" : cacheNamespace.trim();
        this.macroVariations = macroVariations;
    }

    public Method method() {
        return method;
    }

    public Method getMethod() {
        return method;
    }

    public Map<ParameterKey, ParameterValueList> valueLists() {
        return valueLists;
    }

    public Map<ParameterKey, ParameterValueList> getValueLists() {
        return valueLists;
    }

    public CropSpec cropSpec() {
        return cropSpec;
    }

    public CropSpec getCropSpec() {
        return cropSpec;
    }

    public String channelName() {
        return channelName;
    }

    public String getChannelName() {
        return channelName;
    }

    public String sourceImageHash() {
        return sourceImageHash;
    }

    public String getSourceImageHash() {
        return sourceImageHash;
    }

    public String cacheNamespace() {
        return cacheNamespace;
    }

    public String getCacheNamespace() {
        return cacheNamespace;
    }

    public MacroVariationSet macroVariations() {
        return macroVariations == null ? MacroVariationSet.none() : macroVariations;
    }

    public MacroVariationSet getMacroVariations() {
        return macroVariations();
    }

    public boolean hasMacroVariationSet() {
        return macroVariations != null;
    }

    public List<ParameterKey> parameterKeys() {
        return new ArrayList<ParameterKey>(valueLists.keySet());
    }

    public List<ParameterId> parameterIds() {
        List<ParameterId> out = new ArrayList<ParameterId>();
        for (ParameterKey key : valueLists.keySet()) {
            if (key instanceof ParameterId) {
                out.add((ParameterId) key);
            }
        }
        return out;
    }

    public long cellCount() {
        long count = 1L;
        for (ParameterValueList values : valueLists.values()) {
            int size = values.size();
            if (size <= 0) {
                return 0L;
            }
            if (count > Long.MAX_VALUE / size) {
                return Long.MAX_VALUE;
            }
            count *= size;
        }
        return count;
    }

    public List<ParameterCombo> combos() {
        long count = cellCount();
        if (count > Integer.MAX_VALUE) {
            throw new IllegalStateException("too many parameter combinations: " + count);
        }
        List<ParameterKey> ids = new ArrayList<ParameterKey>(valueLists.keySet());
        List<ParameterCombo> out = new ArrayList<ParameterCombo>((int) count);
        buildCombos(ids, 0, new LinkedHashMap<ParameterKey, Object>(), out);
        return out;
    }

    public String toCanonicalJson() {
        LinkedHashMap<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("channelName", channelName);
        root.put("cropSpec", canonicalObject(cropSpec.toCanonicalJson()));
        if (!cacheNamespace.isEmpty()) {
            root.put("cacheNamespace", cacheNamespace);
        }
        root.put("method", method.label());
        root.put("sourceImageHash", sourceImageHash);
        if (macroVariations != null) {
            root.put("macroVariations", macroVariations.toCanonicalObject());
        }
        LinkedHashMap<String, Object> values = new LinkedHashMap<String, Object>();
        List<String> keys = new ArrayList<String>();
        Map<String, ParameterValueList> byName = new LinkedHashMap<String, ParameterValueList>();
        for (Map.Entry<ParameterKey, ParameterValueList> entry : valueLists.entrySet()) {
            String key = canonicalJsonKey(entry.getKey());
            keys.add(key);
            byName.put(key, entry.getValue());
        }
        Collections.sort(keys);
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            values.put(key, byName.get(key).values());
        }
        root.put("values", values);
        return CanonicalJson.write(root);
    }

    private static Object canonicalObject(String json) {
        try {
            return flash.pipeline.ui.wizard.JsonIO.parse(json);
        } catch (Exception e) {
            return json;
        }
    }

    private void buildCombos(List<ParameterKey> ids,
                             int index,
                             LinkedHashMap<ParameterKey, Object> current,
                             List<ParameterCombo> out) {
        if (index >= ids.size()) {
            out.add(new ParameterCombo(current));
            return;
        }
        ParameterKey id = ids.get(index);
        ParameterValueList values = valueLists.get(id);
        for (int i = 0; i < values.size(); i++) {
            current.put(id, values.get(i));
            buildCombos(ids, index + 1, current, out);
        }
        current.remove(id);
    }

    private static Map<ParameterKey, ParameterValueList> copyInKeyOrder(
            Map<? extends ParameterKey, ParameterValueList> source) {
        List<ParameterKey> ids = new ArrayList<ParameterKey>(source.keySet());
        Collections.sort(ids, new java.util.Comparator<ParameterKey>() {
            @Override
            public int compare(ParameterKey a, ParameterKey b) {
                return compareStorageKeys(a, b);
            }
        });
        LinkedHashMap<ParameterKey, ParameterValueList> out =
                new LinkedHashMap<ParameterKey, ParameterValueList>();
        for (int i = 0; i < ids.size(); i++) {
            ParameterKey id = ids.get(i);
            if (id == null) {
                throw new IllegalArgumentException("parameter id must not be null");
            }
            ParameterValueList values = source.get(id);
            if (values == null) {
                throw new IllegalArgumentException("value list must not be null for " + id);
            }
            out.put(id, normalizeValueListForKey(id, values));
        }
        return out;
    }

    private static ParameterValueList normalizeValueListForKey(ParameterKey key,
                                                               ParameterValueList values) {
        if (!(key instanceof ParameterId)
                || !((ParameterId) key).isMacroAxis()
                || values == null) {
            return values;
        }
        List<Object> normalized = new ArrayList<Object>();
        for (int i = 0; i < values.size(); i++) {
            normalized.add(MacroToken.tokenString(values.get(i)));
        }
        return new ParameterValueList(normalized);
    }

    private static String canonicalJsonKey(ParameterKey key) {
        if (key instanceof ParameterId) {
            return ((ParameterId) key).name();
        }
        return key == null ? "" : key.stableKey();
    }

    private static int compareStorageKeys(ParameterKey a, ParameterKey b) {
        if (a instanceof ParameterId && b instanceof ParameterId) {
            return ((ParameterId) a).compareTo((ParameterId) b);
        }
        String aKey = a == null ? "" : a.stableKey();
        String bKey = b == null ? "" : b.stableKey();
        int byStableKey = aKey.compareTo(bKey);
        if (byStableKey != 0) {
            return byStableKey;
        }
        return canonicalJsonKey(a).compareTo(canonicalJsonKey(b));
    }
}
