package flash.pipeline.ui.wizard;

import flash.pipeline.intelligence.MiniJson;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java 8 compatible JSON helper used by wizard preset and identity files.
 */
public final class JsonIO {

    private JsonIO() {
    }

    public static String write(Object value) {
        return MiniJson.write(value);
    }

    public static Object parse(String json) throws IOException {
        return MiniJson.parse(json);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) throws IOException {
        Object parsed = parse(json);
        if (!(parsed instanceof Map)) {
            throw new IOException("JSON root must be an object.");
        }
        return (Map<String, Object>) parsed;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : Collections.<Object>emptyList();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.<String, Object>emptyMap();
    }

    public static Map<String, Object> object() {
        return new LinkedHashMap<String, Object>();
    }

    public static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public static boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        if (value == null) {
            return fallback;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    public static int intValue(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
