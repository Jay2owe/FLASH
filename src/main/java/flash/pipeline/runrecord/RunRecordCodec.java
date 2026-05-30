package flash.pipeline.runrecord;

import flash.pipeline.ui.wizard.JsonIO;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON codec for {@link RunRecord}. Mirrors
 * {@link flash.pipeline.project.ProjectFileCodec}: unknown keys preserved under
 * {@code extras} on round-trip and strict {@code schemaVersion} enforcement.
 *
 * <p>{@link #encode(RunRecord)} produces compact single-line JSON for JSONL
 * storage (exactly one physical line, no trailing newline).
 * {@link #encodePretty(RunRecord)} produces an indented form for dialogs/tests.
 */
public final class RunRecordCodec {

    private static final String K_SCHEMA_VERSION = "schemaVersion";
    private static final String K_RUN_ID = "runId";
    private static final String K_PARENT_RUN_ID = "parentRunId";
    private static final String K_STARTED_AT = "startedAtMillis";
    private static final String K_FINISHED_AT = "finishedAtMillis";
    private static final String K_STATUS = "status";
    private static final String K_ANALYSIS = "analysis";
    private static final String K_ANALYSIS_INDEX = "analysisIndex";
    private static final String K_ANALYSIS_LABEL = "analysisLabel";
    private static final String K_FLASH_VERSION = "flashVersion";
    private static final String K_FIJI_BUILD = "fijiBuild";
    private static final String K_JDK_VERSION = "jdkVersion";
    private static final String K_OS_NAME = "osName";
    private static final String K_BIOF_VERSION = "biofVersion";
    private static final String K_PROJECT_FILE_HASH = "projectFileHash";
    private static final String K_PROJECT_ROOT = "projectRoot";
    private static final String K_OUTPUT_ROOT = "outputRoot";
    private static final String K_PARAMETERS = "parameters";
    private static final String K_INPUTS = "inputs";
    private static final String K_OUTPUTS = "outputs";
    private static final String K_MESSAGES = "messages";

    private static final String K_PATH = "path";
    private static final String K_SERIES_INDEX = "seriesIndex";
    private static final String K_ANIMAL_ID = "animalId";
    private static final String K_HEMISPHERE = "hemisphere";
    private static final String K_REGION = "region";
    private static final String K_CONDITION = "condition";
    private static final String K_FINGERPRINT_MODE = "fingerprintMode";
    private static final String K_FINGERPRINT = "fingerprint";
    private static final String K_SIZE_BYTES = "sizeBytes";
    private static final String K_LAST_MODIFIED = "lastModifiedMillis";
    private static final String K_DURATION = "durationMillis";

    private static final String K_KIND = "kind";

    private static final String K_LEVEL = "level";
    private static final String K_AT_MILLIS = "atMillis";
    private static final String K_TEXT = "text";

    private RunRecordCodec() {
    }

    /** Compact one-line JSON for JSONL storage; no trailing newline. */
    public static String encode(RunRecord record) {
        return JsonIO.write(toJsonObject(record == null ? new RunRecord() : record));
    }

    /** Indented JSON for human-readable views. Never written into a JSONL file. */
    public static String encodePretty(RunRecord record) {
        return prettyPrint(encode(record));
    }

    public static RunRecord decode(String json) throws IOException {
        return fromJsonObject(JsonIO.parseObject(json));
    }

    public static RunRecord decodeOrNull(String json) {
        try {
            return decode(json);
        } catch (IOException e) {
            return null;
        }
    }

    private static Map<String, Object> toJsonObject(RunRecord record) {
        Map<String, Object> root = JsonIO.object();
        root.put(K_SCHEMA_VERSION, Integer.valueOf(record.schemaVersion));
        root.put(K_RUN_ID, record.runId);
        root.put(K_PARENT_RUN_ID, record.parentRunId);
        root.put(K_STARTED_AT, Long.valueOf(record.startedAtMillis));
        root.put(K_FINISHED_AT, Long.valueOf(record.finishedAtMillis));
        root.put(K_STATUS, record.status);
        root.put(K_ANALYSIS, record.analysis);
        root.put(K_ANALYSIS_INDEX, Integer.valueOf(record.analysisIndex));
        root.put(K_ANALYSIS_LABEL, record.analysisLabel);
        root.put(K_FLASH_VERSION, record.flashVersion);
        root.put(K_FIJI_BUILD, record.fijiBuild);
        root.put(K_JDK_VERSION, record.jdkVersion);
        root.put(K_OS_NAME, record.osName);
        root.put(K_BIOF_VERSION, record.biofVersion);
        root.put(K_PROJECT_FILE_HASH, record.projectFileHash);
        root.put(K_PROJECT_ROOT, record.projectRoot);
        root.put(K_OUTPUT_ROOT, record.outputRoot);
        root.put(K_PARAMETERS, record.parameters == null
                ? JsonIO.object()
                : new LinkedHashMap<String, Object>(record.parameters));
        root.put(K_INPUTS, inputsToJson(record.inputs));
        root.put(K_OUTPUTS, outputsToJson(record.outputs));
        root.put(K_MESSAGES, messagesToJson(record.messages));
        appendUnknown(root, record.extras);
        return root;
    }

    private static RunRecord fromJsonObject(Map<String, Object> root) throws IOException {
        RunRecord record = new RunRecord();
        record.schemaVersion = JsonIO.intValue(root.get(K_SCHEMA_VERSION), -1);
        if (record.schemaVersion != RunRecord.SCHEMA_VERSION) {
            throw new IOException("Unsupported run_record schemaVersion: " + record.schemaVersion);
        }
        record.runId = string(root.get(K_RUN_ID));
        record.parentRunId = string(root.get(K_PARENT_RUN_ID));
        record.startedAtMillis = longValue(root.get(K_STARTED_AT), 0L);
        record.finishedAtMillis = longValue(root.get(K_FINISHED_AT), 0L);
        record.status = stringOr(root.get(K_STATUS), RunRecord.STATUS_OK);
        record.analysis = string(root.get(K_ANALYSIS));
        record.analysisIndex = JsonIO.intValue(root.get(K_ANALYSIS_INDEX), -1);
        record.analysisLabel = string(root.get(K_ANALYSIS_LABEL));
        record.flashVersion = string(root.get(K_FLASH_VERSION));
        record.fijiBuild = string(root.get(K_FIJI_BUILD));
        record.jdkVersion = string(root.get(K_JDK_VERSION));
        record.osName = string(root.get(K_OS_NAME));
        record.biofVersion = string(root.get(K_BIOF_VERSION));
        record.projectFileHash = string(root.get(K_PROJECT_FILE_HASH));
        record.projectRoot = string(root.get(K_PROJECT_ROOT));
        record.outputRoot = string(root.get(K_OUTPUT_ROOT));
        record.parameters = new LinkedHashMap<String, Object>(JsonIO.asObject(root.get(K_PARAMETERS)));
        record.inputs = inputsFromJson(JsonIO.asList(root.get(K_INPUTS)));
        record.outputs = outputsFromJson(JsonIO.asList(root.get(K_OUTPUTS)));
        record.messages = messagesFromJson(JsonIO.asList(root.get(K_MESSAGES)));
        record.extras = extras(root, rootKnownKeys());
        return record;
    }

    private static List<Object> inputsToJson(List<RunRecord.InputItem> inputs) {
        List<Object> rows = new ArrayList<Object>();
        if (inputs == null) {
            return rows;
        }
        for (RunRecord.InputItem item : inputs) {
            if (item == null) {
                continue;
            }
            Map<String, Object> row = JsonIO.object();
            row.put(K_PATH, item.path);
            row.put(K_SERIES_INDEX, Integer.valueOf(item.seriesIndex));
            row.put(K_ANIMAL_ID, item.animalId);
            row.put(K_HEMISPHERE, item.hemisphere);
            row.put(K_REGION, item.region);
            row.put(K_CONDITION, item.condition);
            row.put(K_FINGERPRINT_MODE, item.fingerprintMode);
            row.put(K_FINGERPRINT, item.fingerprint);
            row.put(K_SIZE_BYTES, Long.valueOf(item.sizeBytes));
            row.put(K_LAST_MODIFIED, Long.valueOf(item.lastModifiedMillis));
            row.put(K_STATUS, item.status);
            row.put(K_DURATION, Long.valueOf(item.durationMillis));
            appendUnknown(row, item.extras);
            rows.add(row);
        }
        return rows;
    }

    private static List<RunRecord.InputItem> inputsFromJson(List<Object> values) {
        List<RunRecord.InputItem> inputs = new ArrayList<RunRecord.InputItem>();
        if (values == null) {
            return inputs;
        }
        for (Object value : values) {
            Map<String, Object> row = JsonIO.asObject(value);
            RunRecord.InputItem item = new RunRecord.InputItem();
            item.path = string(row.get(K_PATH));
            item.seriesIndex = JsonIO.intValue(row.get(K_SERIES_INDEX), -1);
            item.animalId = string(row.get(K_ANIMAL_ID));
            item.hemisphere = string(row.get(K_HEMISPHERE));
            item.region = string(row.get(K_REGION));
            item.condition = string(row.get(K_CONDITION));
            item.fingerprintMode = stringOr(row.get(K_FINGERPRINT_MODE), "fast");
            item.fingerprint = string(row.get(K_FINGERPRINT));
            item.sizeBytes = longValue(row.get(K_SIZE_BYTES), -1L);
            item.lastModifiedMillis = longValue(row.get(K_LAST_MODIFIED), -1L);
            item.status = string(row.get(K_STATUS));
            item.durationMillis = longValue(row.get(K_DURATION), 0L);
            item.extras = extras(row, inputKnownKeys());
            inputs.add(item);
        }
        return inputs;
    }

    private static List<Object> outputsToJson(List<RunRecord.OutputItem> outputs) {
        List<Object> rows = new ArrayList<Object>();
        if (outputs == null) {
            return rows;
        }
        for (RunRecord.OutputItem item : outputs) {
            if (item == null) {
                continue;
            }
            Map<String, Object> row = JsonIO.object();
            row.put(K_PATH, item.path);
            row.put(K_KIND, item.kind);
            row.put(K_SIZE_BYTES, Long.valueOf(item.sizeBytes));
            row.put(K_FINGERPRINT, item.fingerprint);
            appendUnknown(row, item.extras);
            rows.add(row);
        }
        return rows;
    }

    private static List<RunRecord.OutputItem> outputsFromJson(List<Object> values) {
        List<RunRecord.OutputItem> outputs = new ArrayList<RunRecord.OutputItem>();
        if (values == null) {
            return outputs;
        }
        for (Object value : values) {
            Map<String, Object> row = JsonIO.asObject(value);
            RunRecord.OutputItem item = new RunRecord.OutputItem();
            item.path = string(row.get(K_PATH));
            item.kind = string(row.get(K_KIND));
            item.sizeBytes = longValue(row.get(K_SIZE_BYTES), -1L);
            item.fingerprint = string(row.get(K_FINGERPRINT));
            item.extras = extras(row, outputKnownKeys());
            outputs.add(item);
        }
        return outputs;
    }

    private static List<Object> messagesToJson(List<RunRecord.Message> messages) {
        List<Object> rows = new ArrayList<Object>();
        if (messages == null) {
            return rows;
        }
        for (RunRecord.Message message : messages) {
            if (message == null) {
                continue;
            }
            Map<String, Object> row = JsonIO.object();
            row.put(K_LEVEL, message.level);
            row.put(K_AT_MILLIS, Long.valueOf(message.atMillis));
            row.put(K_TEXT, message.text);
            rows.add(row);
        }
        return rows;
    }

    private static List<RunRecord.Message> messagesFromJson(List<Object> values) {
        List<RunRecord.Message> messages = new ArrayList<RunRecord.Message>();
        if (values == null) {
            return messages;
        }
        for (Object value : values) {
            Map<String, Object> row = JsonIO.asObject(value);
            RunRecord.Message message = new RunRecord.Message();
            message.level = stringOr(row.get(K_LEVEL), "info");
            message.atMillis = longValue(row.get(K_AT_MILLIS), 0L);
            message.text = string(row.get(K_TEXT));
            messages.add(message);
        }
        return messages;
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

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String stringOr(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
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

    private static Map<String, Boolean> rootKnownKeys() {
        Map<String, Boolean> keys = new LinkedHashMap<String, Boolean>();
        keys.put(K_SCHEMA_VERSION, Boolean.TRUE);
        keys.put(K_RUN_ID, Boolean.TRUE);
        keys.put(K_PARENT_RUN_ID, Boolean.TRUE);
        keys.put(K_STARTED_AT, Boolean.TRUE);
        keys.put(K_FINISHED_AT, Boolean.TRUE);
        keys.put(K_STATUS, Boolean.TRUE);
        keys.put(K_ANALYSIS, Boolean.TRUE);
        keys.put(K_ANALYSIS_INDEX, Boolean.TRUE);
        keys.put(K_ANALYSIS_LABEL, Boolean.TRUE);
        keys.put(K_FLASH_VERSION, Boolean.TRUE);
        keys.put(K_FIJI_BUILD, Boolean.TRUE);
        keys.put(K_JDK_VERSION, Boolean.TRUE);
        keys.put(K_OS_NAME, Boolean.TRUE);
        keys.put(K_BIOF_VERSION, Boolean.TRUE);
        keys.put(K_PROJECT_FILE_HASH, Boolean.TRUE);
        keys.put(K_PROJECT_ROOT, Boolean.TRUE);
        keys.put(K_OUTPUT_ROOT, Boolean.TRUE);
        keys.put(K_PARAMETERS, Boolean.TRUE);
        keys.put(K_INPUTS, Boolean.TRUE);
        keys.put(K_OUTPUTS, Boolean.TRUE);
        keys.put(K_MESSAGES, Boolean.TRUE);
        return keys;
    }

    private static Map<String, Boolean> inputKnownKeys() {
        Map<String, Boolean> keys = new LinkedHashMap<String, Boolean>();
        keys.put(K_PATH, Boolean.TRUE);
        keys.put(K_SERIES_INDEX, Boolean.TRUE);
        keys.put(K_ANIMAL_ID, Boolean.TRUE);
        keys.put(K_HEMISPHERE, Boolean.TRUE);
        keys.put(K_REGION, Boolean.TRUE);
        keys.put(K_CONDITION, Boolean.TRUE);
        keys.put(K_FINGERPRINT_MODE, Boolean.TRUE);
        keys.put(K_FINGERPRINT, Boolean.TRUE);
        keys.put(K_SIZE_BYTES, Boolean.TRUE);
        keys.put(K_LAST_MODIFIED, Boolean.TRUE);
        keys.put(K_STATUS, Boolean.TRUE);
        keys.put(K_DURATION, Boolean.TRUE);
        return keys;
    }

    private static Map<String, Boolean> outputKnownKeys() {
        Map<String, Boolean> keys = new LinkedHashMap<String, Boolean>();
        keys.put(K_PATH, Boolean.TRUE);
        keys.put(K_KIND, Boolean.TRUE);
        keys.put(K_SIZE_BYTES, Boolean.TRUE);
        keys.put(K_FINGERPRINT, Boolean.TRUE);
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
