package flash.pipeline.ui.variations;

import flash.pipeline.ui.wizard.JsonIO;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CanonicalJson {

    private CanonicalJson() {
    }

    public static LinkedHashMap<String, Object> object() {
        return new LinkedHashMap<String, Object>();
    }

    public static List<Object> list() {
        return new ArrayList<Object>();
    }

    public static String write(Object value) {
        return JsonIO.write(canonicalize(value));
    }

    @SuppressWarnings("unchecked")
    private static Object canonicalize(Object value) {
        if (value instanceof Map<?, ?>) {
            Map<?, ?> source = (Map<?, ?>) value;
            List<Map.Entry<?, ?>> entries = new ArrayList<Map.Entry<?, ?>>(source.entrySet());
            Collections.sort(entries, new Comparator<Map.Entry<?, ?>>() {
                @Override
                public int compare(Map.Entry<?, ?> a, Map.Entry<?, ?> b) {
                    return String.valueOf(a.getKey()).compareTo(String.valueOf(b.getKey()));
                }
            });
            LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
            for (int i = 0; i < entries.size(); i++) {
                Map.Entry<?, ?> entry = entries.get(i);
                out.put(String.valueOf(entry.getKey()), canonicalize(entry.getValue()));
            }
            return out;
        }
        if (value instanceof List<?>) {
            List<?> source = (List<?>) value;
            List<Object> out = new ArrayList<Object>(source.size());
            for (int i = 0; i < source.size(); i++) {
                out.add(canonicalize(source.get(i)));
            }
            return out;
        }
        return value;
    }
}
