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
        CELLPOSE("Cellpose");

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
    private final Map<ParameterId, ParameterValueList> valueLists;
    private final CropSpec cropSpec;
    private final String channelName;
    private final String sourceImageHash;

    public ParameterSweep(Method method,
                          Map<ParameterId, ParameterValueList> valueLists,
                          CropSpec cropSpec,
                          String channelName,
                          String sourceImageHash) {
        if (method == null) {
            throw new IllegalArgumentException("method must not be null");
        }
        if (valueLists == null) {
            throw new IllegalArgumentException("valueLists must not be null");
        }
        this.method = method;
        this.valueLists = Collections.unmodifiableMap(copyInEnumOrder(valueLists));
        this.cropSpec = cropSpec == null ? CropSpec.full() : cropSpec;
        this.channelName = channelName == null ? "" : channelName;
        this.sourceImageHash = sourceImageHash == null ? "" : sourceImageHash;
    }

    public Method method() {
        return method;
    }

    public Method getMethod() {
        return method;
    }

    public Map<ParameterId, ParameterValueList> valueLists() {
        return valueLists;
    }

    public Map<ParameterId, ParameterValueList> getValueLists() {
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

    public List<ParameterId> parameterIds() {
        return new ArrayList<ParameterId>(valueLists.keySet());
    }

    public long cellCount() {
        long count = 1L;
        for (ParameterValueList values : valueLists.values()) {
            count *= values.size();
            if (count < 0L) {
                return Long.MAX_VALUE;
            }
        }
        return count;
    }

    public List<ParameterCombo> combos() {
        long count = cellCount();
        if (count > Integer.MAX_VALUE) {
            throw new IllegalStateException("too many parameter combinations: " + count);
        }
        List<ParameterId> ids = new ArrayList<ParameterId>(valueLists.keySet());
        List<ParameterCombo> out = new ArrayList<ParameterCombo>((int) count);
        buildCombos(ids, 0, new LinkedHashMap<ParameterId, Object>(), out);
        return out;
    }

    public String toCanonicalJson() {
        LinkedHashMap<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("channelName", channelName);
        root.put("cropSpec", canonicalObject(cropSpec.toCanonicalJson()));
        root.put("method", method.label());
        root.put("sourceImageHash", sourceImageHash);
        LinkedHashMap<String, Object> values = new LinkedHashMap<String, Object>();
        List<String> keys = new ArrayList<String>();
        Map<String, ParameterValueList> byName = new LinkedHashMap<String, ParameterValueList>();
        for (Map.Entry<ParameterId, ParameterValueList> entry : valueLists.entrySet()) {
            keys.add(entry.getKey().name());
            byName.put(entry.getKey().name(), entry.getValue());
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

    private void buildCombos(List<ParameterId> ids,
                             int index,
                             LinkedHashMap<ParameterId, Object> current,
                             List<ParameterCombo> out) {
        if (index >= ids.size()) {
            out.add(new ParameterCombo(current));
            return;
        }
        ParameterId id = ids.get(index);
        ParameterValueList values = valueLists.get(id);
        for (int i = 0; i < values.size(); i++) {
            current.put(id, values.get(i));
            buildCombos(ids, index + 1, current, out);
        }
        current.remove(id);
    }

    private static Map<ParameterId, ParameterValueList> copyInEnumOrder(
            Map<ParameterId, ParameterValueList> source) {
        List<ParameterId> ids = new ArrayList<ParameterId>(source.keySet());
        Collections.sort(ids);
        LinkedHashMap<ParameterId, ParameterValueList> out =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        for (int i = 0; i < ids.size(); i++) {
            ParameterId id = ids.get(i);
            if (id == null) {
                throw new IllegalArgumentException("parameter id must not be null");
            }
            ParameterValueList values = source.get(id);
            if (values == null) {
                throw new IllegalArgumentException("value list must not be null for " + id);
            }
            out.put(id, values);
        }
        return out;
    }
}
