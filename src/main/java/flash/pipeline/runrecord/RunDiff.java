package flash.pipeline.runrecord;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure diff helper for comparing two run records. */
public final class RunDiff {
    private RunDiff() {
    }

    public static List<DiffEntry> diff(RunRecord parent, RunRecord child) {
        Map<String, Object> left = flatten(parent == null ? null : parent.parameters);
        Map<String, Object> right = flatten(child == null ? null : child.parameters);
        Set<String> keys = new LinkedHashSet<String>();
        keys.addAll(left.keySet());
        keys.addAll(right.keySet());

        List<DiffEntry> out = new ArrayList<DiffEntry>();
        for (String key : keys) {
            boolean inLeft = left.containsKey(key);
            boolean inRight = right.containsKey(key);
            if (inLeft && !inRight) {
                out.add(new DiffEntry(DiffKind.REMOVED, key, left.get(key), null));
            } else if (!inLeft && inRight) {
                out.add(new DiffEntry(DiffKind.ADDED, key, null, right.get(key)));
            } else if (!valuesEqual(left.get(key), right.get(key))) {
                out.add(new DiffEntry(DiffKind.CHANGED, key, left.get(key), right.get(key)));
            }
        }
        return Collections.unmodifiableList(out);
    }

    public enum DiffKind {
        ADDED,
        REMOVED,
        CHANGED
    }

    public static final class DiffEntry {
        private final DiffKind kind;
        private final String path;
        private final Object parentValue;
        private final Object childValue;

        public DiffEntry(DiffKind kind, String path, Object parentValue, Object childValue) {
            this.kind = kind;
            this.path = path == null ? "" : path;
            this.parentValue = parentValue;
            this.childValue = childValue;
        }

        public DiffKind kind() {
            return kind;
        }

        public String path() {
            return path;
        }

        public Object parentValue() {
            return parentValue;
        }

        public Object childValue() {
            return childValue;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> flatten(Object value) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (value instanceof Map<?, ?>) {
            flattenInto(out, "", (Map<Object, Object>) value);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void flattenValue(Map<String, Object> out, String path, Object value) {
        if (value instanceof Map<?, ?>) {
            flattenInto(out, path, (Map<Object, Object>) value);
        } else if (value instanceof List<?>) {
            List<?> list = (List<?>) value;
            if (list.isEmpty()) {
                out.put(path, list);
            } else {
                for (int i = 0; i < list.size(); i++) {
                    flattenValue(out, path + "[" + i + "]", list.get(i));
                }
            }
        } else {
            out.put(path, value);
        }
    }

    private static void flattenInto(Map<String, Object> out, String prefix, Map<Object, Object> map) {
        if (map.isEmpty() && !prefix.isEmpty()) {
            out.put(prefix, map);
            return;
        }
        for (Map.Entry<Object, Object> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            String path = prefix == null || prefix.isEmpty() ? key : prefix + "." + key;
            flattenValue(out, path, entry.getValue());
        }
    }

    private static boolean valuesEqual(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a instanceof Number && b instanceof Number) {
            return numbersEqual((Number) a, (Number) b);
        }
        return a.equals(b);
    }

    private static boolean numbersEqual(Number a, Number b) {
        try {
            BigDecimal left = new BigDecimal(a.toString());
            BigDecimal right = new BigDecimal(b.toString());
            return left.compareTo(right) == 0;
        } catch (NumberFormatException e) {
            return Double.compare(a.doubleValue(), b.doubleValue()) == 0;
        }
    }
}
