package flash.pipeline.project;

import flash.pipeline.naming.ConditionAxis;
import flash.pipeline.ui.wizard.JsonIO;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON codec for {@link ProjectFile}. Mirrors
 * {@link flash.pipeline.bin.ChannelConfigCodec}: pretty-printed output,
 * unknown keys preserved under {@code extras} on round-trip, strict
 * schemaVersion enforcement.
 */
public final class ProjectFileCodec {
    private static final int SCHEMA_VERSION = 1;

    private static final String K_COMMENT = "_comment";
    private static final String K_SCHEMA_VERSION = "schemaVersion";
    private static final String K_WRITER_ID = "writerId";
    private static final String K_WRITTEN_AT_MILLIS = "writtenAtMillis";
    private static final String K_NAME = "name";
    private static final String K_OUTPUT_ROOT = "outputRoot";
    private static final String K_ITEMS = "items";

    private static final String K_PATH = "path";
    private static final String K_SERIES = "series";
    private static final String K_INCLUDE = "include";
    private static final String K_ANIMAL_ID = "animalId";
    private static final String K_HEMISPHERE = "hemisphere";
    private static final String K_REGION = "region";
    private static final String K_CONDITION = "condition";
    private static final String K_NOTES = "notes";
    private static final String K_SERIES_META = "seriesMeta";
    private static final String K_INDEX = "index";
    private static final String K_CONDITIONS = "conditions";
    private static final String K_CONDITION_AXES = "conditionAxes";

    private ProjectFileCodec() {
    }

    public static String encode(ProjectFile project) {
        return prettyPrint(JsonIO.write(toJsonObject(project == null ? new ProjectFile() : project)));
    }

    public static ProjectFile decode(String json) throws IOException {
        return fromJsonObject(JsonIO.parseObject(json));
    }

    public static ProjectFile decodeOrNull(String json) {
        try {
            return decode(json);
        } catch (IOException e) {
            return null;
        }
    }

    private static Map<String, Object> toJsonObject(ProjectFile project) {
        Map<String, Object> root = JsonIO.object();
        appendComment(root, project.extras);
        root.put(K_SCHEMA_VERSION, Integer.valueOf(project.schemaVersion));
        root.put(K_WRITER_ID, project.writerId);
        root.put(K_WRITTEN_AT_MILLIS, Long.valueOf(project.writtenAtMillis));
        root.put(K_NAME, project.name);
        root.put(K_OUTPUT_ROOT, project.outputRoot);
        root.put(K_ITEMS, itemsToJson(project.items));
        if (project.conditionAxes != null && !project.conditionAxes.isEmpty()) {
            root.put(K_CONDITION_AXES, axesToJson(project.conditionAxes));
        }
        appendUnknown(root, project.extras);
        return root;
    }

    private static ProjectFile fromJsonObject(Map<String, Object> root) throws IOException {
        ProjectFile project = new ProjectFile();
        project.schemaVersion = JsonIO.intValue(root.get(K_SCHEMA_VERSION), -1);
        if (project.schemaVersion != SCHEMA_VERSION) {
            throw new IOException("Unsupported project_file schemaVersion: " + project.schemaVersion);
        }

        project.writerId = JsonIO.stringValue(root.get(K_WRITER_ID));
        project.writtenAtMillis = longValue(root.get(K_WRITTEN_AT_MILLIS), 0L);
        project.name = JsonIO.stringValue(root.get(K_NAME));
        project.outputRoot = JsonIO.stringValue(root.get(K_OUTPUT_ROOT));
        project.items = itemsFromJson(JsonIO.asList(root.get(K_ITEMS)));
        project.conditionAxes = axesFromJson(JsonIO.asList(root.get(K_CONDITION_AXES)));
        project.extras = extras(root, rootKnownKeys());
        return project;
    }

    private static List<Object> itemsToJson(List<ProjectFile.Item> items) {
        List<Object> rows = new ArrayList<Object>();
        if (items == null) {
            return rows;
        }
        for (ProjectFile.Item item : items) {
            if (item == null) {
                continue;
            }
            Map<String, Object> row = JsonIO.object();
            appendComment(row, item.extras);
            row.put(K_PATH, item.path);
            row.put(K_SERIES, seriesToJson(item.series));
            row.put(K_INCLUDE, Boolean.valueOf(item.include));
            row.put(K_ANIMAL_ID, item.animalId);
            row.put(K_HEMISPHERE, item.hemisphere);
            row.put(K_REGION, item.region);
            row.put(K_CONDITION, item.condition);
            if (item.conditions != null && !item.conditions.isEmpty()) {
                row.put(K_CONDITIONS, conditionsToJson(item.conditions));
            }
            row.put(K_NOTES, item.notes);
            if (item.seriesMeta != null && !item.seriesMeta.isEmpty()) {
                row.put(K_SERIES_META, seriesMetaToJson(item.seriesMeta));
            }
            appendUnknown(row, item.extras);
            rows.add(row);
        }
        return rows;
    }

    private static List<ProjectFile.Item> itemsFromJson(List<Object> values) {
        List<ProjectFile.Item> items = new ArrayList<ProjectFile.Item>();
        if (values == null) {
            return items;
        }
        for (Object value : values) {
            Map<String, Object> row = JsonIO.asObject(value);
            ProjectFile.Item item = new ProjectFile.Item();
            item.path = JsonIO.stringValue(row.get(K_PATH));
            item.series = seriesFromJson(JsonIO.asList(row.get(K_SERIES)));
            item.include = JsonIO.booleanValue(row.get(K_INCLUDE), true);
            item.animalId = JsonIO.stringValue(row.get(K_ANIMAL_ID));
            item.hemisphere = JsonIO.stringValue(row.get(K_HEMISPHERE));
            item.region = JsonIO.stringValue(row.get(K_REGION));
            item.condition = JsonIO.stringValue(row.get(K_CONDITION));
            item.conditions = conditionsFromJson(row.get(K_CONDITIONS));
            item.notes = JsonIO.stringValue(row.get(K_NOTES));
            item.seriesMeta = seriesMetaFromJson(JsonIO.asList(row.get(K_SERIES_META)));
            item.extras = extras(row, itemKnownKeys());
            items.add(item);
        }
        return items;
    }

    private static List<Object> seriesMetaToJson(List<ProjectFile.SeriesItem> seriesMeta) {
        List<Object> out = new ArrayList<Object>();
        if (seriesMeta == null) {
            return out;
        }
        for (ProjectFile.SeriesItem series : seriesMeta) {
            if (series == null) {
                continue;
            }
            Map<String, Object> row = JsonIO.object();
            appendComment(row, series.extras);
            row.put(K_INDEX, Integer.valueOf(series.index));
            row.put(K_INCLUDE, Boolean.valueOf(series.include));
            row.put(K_NAME, series.name);
            row.put(K_ANIMAL_ID, series.animalId);
            row.put(K_HEMISPHERE, series.hemisphere);
            row.put(K_REGION, series.region);
            row.put(K_CONDITION, series.condition);
            if (series.conditions != null && !series.conditions.isEmpty()) {
                row.put(K_CONDITIONS, conditionsToJson(series.conditions));
            }
            row.put(K_NOTES, series.notes);
            appendUnknown(row, series.extras);
            out.add(row);
        }
        return out;
    }

    private static List<ProjectFile.SeriesItem> seriesMetaFromJson(List<Object> values) {
        List<ProjectFile.SeriesItem> out = new ArrayList<ProjectFile.SeriesItem>();
        if (values == null) {
            return out;
        }
        for (Object value : values) {
            Map<String, Object> row = JsonIO.asObject(value);
            ProjectFile.SeriesItem series = new ProjectFile.SeriesItem();
            series.index = JsonIO.intValue(row.get(K_INDEX), 0);
            series.include = JsonIO.booleanValue(row.get(K_INCLUDE), true);
            series.name = JsonIO.stringValue(row.get(K_NAME));
            series.animalId = JsonIO.stringValue(row.get(K_ANIMAL_ID));
            series.hemisphere = JsonIO.stringValue(row.get(K_HEMISPHERE));
            series.region = JsonIO.stringValue(row.get(K_REGION));
            series.condition = JsonIO.stringValue(row.get(K_CONDITION));
            series.conditions = conditionsFromJson(row.get(K_CONDITIONS));
            series.notes = JsonIO.stringValue(row.get(K_NOTES));
            series.extras = extras(row, seriesItemKnownKeys());
            out.add(series);
        }
        return out;
    }

    private static List<Object> seriesToJson(List<Integer> series) {
        List<Object> out = new ArrayList<Object>();
        if (series == null) {
            return out;
        }
        for (Integer s : series) {
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    private static List<Integer> seriesFromJson(List<Object> values) {
        List<Integer> out = new ArrayList<Integer>();
        if (values == null) {
            return out;
        }
        for (Object value : values) {
            if (value instanceof Number) {
                out.add(Integer.valueOf(((Number) value).intValue()));
            } else if (value != null) {
                try {
                    out.add(Integer.valueOf(String.valueOf(value).trim()));
                } catch (NumberFormatException ignored) {
                    // skip non-integer series tokens silently — extras path handles unknown shapes
                }
            }
        }
        return out;
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static List<Object> axesToJson(List<ConditionAxis> axes) {
        List<Object> out = new ArrayList<Object>();
        if (axes == null) return out;
        for (ConditionAxis axis : axes) {
            if (axis == null) continue;
            Map<String, Object> o = JsonIO.object();
            o.put("id", axis.id);
            o.put("label", axis.label);
            o.put("order", Integer.valueOf(axis.order));
            out.add(o);
        }
        return out;
    }

    private static List<ConditionAxis> axesFromJson(List<Object> values) {
        List<ConditionAxis> out = new ArrayList<ConditionAxis>();
        if (values == null) return out;
        for (Object value : values) {
            if (value == null) continue;
            Map<String, Object> o = JsonIO.asObject(value);
            if (o == null) continue;
            String id = JsonIO.stringValue(o.get("id"));
            String label = JsonIO.stringValue(o.get("label"));
            int order = JsonIO.intValue(o.get("order"), out.size());
            ConditionAxis axis = new ConditionAxis(id, label, order);
            if (!axis.id.isEmpty()) out.add(axis);
        }
        return out;
    }

    private static Map<String, Object> conditionsToJson(Map<String, String> conditions) {
        Map<String, Object> obj = JsonIO.object();
        if (conditions == null) return obj;
        for (Map.Entry<String, String> entry : conditions.entrySet()) {
            if (entry.getKey() == null) continue;
            String value = entry.getValue();
            if (value != null && !value.trim().isEmpty()) {
                obj.put(entry.getKey(), value);
            }
        }
        return obj;
    }

    private static Map<String, String> conditionsFromJson(Object value) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (value == null) return out;
        Map<String, Object> obj = JsonIO.asObject(value);
        if (obj == null) return out;
        for (Map.Entry<String, Object> entry : obj.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            out.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        return out;
    }

    private static Map<String, Object> extras(Map<String, Object> source, Map<String, Boolean> knownKeys) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (source == null) {
            return out;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (!knownKeys.containsKey(entry.getKey())) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    private static void appendUnknown(Map<String, Object> target, Map<String, Object> extras) {
        if (extras == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : extras.entrySet()) {
            if (!target.containsKey(entry.getKey())) {
                target.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private static void appendComment(Map<String, Object> target, Map<String, Object> extras) {
        if (extras != null && extras.containsKey(K_COMMENT)) {
            target.put(K_COMMENT, extras.get(K_COMMENT));
        }
    }

    private static Map<String, Boolean> rootKnownKeys() {
        Map<String, Boolean> keys = new LinkedHashMap<String, Boolean>();
        keys.put(K_SCHEMA_VERSION, Boolean.TRUE);
        keys.put(K_WRITER_ID, Boolean.TRUE);
        keys.put(K_WRITTEN_AT_MILLIS, Boolean.TRUE);
        keys.put(K_NAME, Boolean.TRUE);
        keys.put(K_OUTPUT_ROOT, Boolean.TRUE);
        keys.put(K_ITEMS, Boolean.TRUE);
        keys.put(K_CONDITION_AXES, Boolean.TRUE);
        return keys;
    }

    private static Map<String, Boolean> itemKnownKeys() {
        Map<String, Boolean> keys = new LinkedHashMap<String, Boolean>();
        keys.put(K_PATH, Boolean.TRUE);
        keys.put(K_SERIES, Boolean.TRUE);
        keys.put(K_INCLUDE, Boolean.TRUE);
        keys.put(K_ANIMAL_ID, Boolean.TRUE);
        keys.put(K_HEMISPHERE, Boolean.TRUE);
        keys.put(K_REGION, Boolean.TRUE);
        keys.put(K_CONDITION, Boolean.TRUE);
        keys.put(K_CONDITIONS, Boolean.TRUE);
        keys.put(K_NOTES, Boolean.TRUE);
        keys.put(K_SERIES_META, Boolean.TRUE);
        return keys;
    }

    private static Map<String, Boolean> seriesItemKnownKeys() {
        Map<String, Boolean> keys = new LinkedHashMap<String, Boolean>();
        keys.put(K_INDEX, Boolean.TRUE);
        keys.put(K_INCLUDE, Boolean.TRUE);
        keys.put(K_NAME, Boolean.TRUE);
        keys.put(K_ANIMAL_ID, Boolean.TRUE);
        keys.put(K_HEMISPHERE, Boolean.TRUE);
        keys.put(K_REGION, Boolean.TRUE);
        keys.put(K_CONDITION, Boolean.TRUE);
        keys.put(K_CONDITIONS, Boolean.TRUE);
        keys.put(K_NOTES, Boolean.TRUE);
        return keys;
    }

    private static String prettyPrint(String json) {
        StringBuilder out = new StringBuilder();
        int indent = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (inString) {
                out.append(ch);
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            switch (ch) {
                case '"':
                    inString = true;
                    out.append(ch);
                    break;
                case '{':
                case '[':
                    if (i + 1 < json.length()
                            && ((ch == '{' && json.charAt(i + 1) == '}')
                            || (ch == '[' && json.charAt(i + 1) == ']'))) {
                        out.append(ch).append(json.charAt(i + 1));
                        i++;
                        break;
                    }
                    out.append(ch);
                    indent++;
                    newline(out, indent);
                    break;
                case '}':
                case ']':
                    indent--;
                    newline(out, indent);
                    out.append(ch);
                    break;
                case ',':
                    out.append(ch);
                    newline(out, indent);
                    break;
                case ':':
                    out.append(": ");
                    break;
                default:
                    out.append(ch);
                    break;
            }
        }
        return out.toString();
    }

    private static void newline(StringBuilder out, int indent) {
        out.append('\n');
        for (int i = 0; i < indent; i++) {
            out.append("  ");
        }
    }
}
