package flash.pipeline.decontamination;

import flash.pipeline.io.FlashProjectLayout;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads and writes the per-dataset Spectral Decontamination config.
 */
public class SpectralDecontaminationConfigIO {

    public static final String CONFIG_FILENAME = "Spectral_Decontamination_Config.json";

    private SpectralDecontaminationConfigIO() {
    }

    public static File configFile(String directory) {
        return configWriteFile(directory);
    }

    public static boolean exists(String directory) {
        return configReadFile(directory).isFile();
    }

    public static SpectralDecontaminationConfig readOrDefault(String directory, int channelCount) throws IOException {
        File file = configReadFile(directory);
        if (!file.isFile()) {
            return SpectralDecontaminationConfig.defaults(channelCount);
        }
        return read(file);
    }

    public static SpectralDecontaminationConfig readFromDirectory(String directory) throws IOException {
        File file = configReadFile(directory);
        if (!file.isFile()) {
            throw new FileNotFoundException(file.getAbsolutePath());
        }
        return read(file);
    }

    public static SpectralDecontaminationConfig read(File file) throws IOException {
        String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setVersion(readInt(json, "version", SpectralDecontaminationConfig.CURRENT_VERSION));
        config.setGoal(SpectralDecontaminationConfig.Goal.fromKeyOrLabel(readString(json, "goal",
                SpectralDecontaminationConfig.Goal.CREATE_CLEANED_IMAGE.getKey())));
        config.setConditionSource(SpectralDecontaminationConfig.ConditionSource.fromKeyOrLabel(
                readString(json, "conditionSource",
                        SpectralDecontaminationConfig.ConditionSource.USE_EXISTING_CONDITION_FILE.getKey())));
        config.setTargetChannelIndex(readInt(json, "targetChannelIndex", 0));
        config.setBleedThroughChannelIndexes(readIntArray(json, "bleedThroughChannelIndexes"));
        config.setAutofluorescenceChannelIndexes(readIntArray(json, "autofluorescenceChannelIndexes"));
        config.setExcludedChannelIndexes(readIntArray(json, "excludedChannelIndexes"));
        config.setControlConditionNames(readStringArray(json, "controlConditionNames"));
        config.setExperimentalConditionNames(readStringArray(json, "experimentalConditionNames"));
        CorrectionPipeline pipeline = new CorrectionPipeline();
        pipeline.setPresetId(readString(json, "correctionPresetId", CorrectionPipeline.CUSTOM_PRESET_ID));
        pipeline.setExpertMode(readBoolean(json, "expertMode", false));
        pipeline.setFeatureIds(readStringArray(json, "correctionFeatureIds"));
        config.setCorrectionPipeline(pipeline);
        config.setFeatureSettings(readFeatureSettings(json));
        return config;
    }

    public static void writeToDirectory(String directory, SpectralDecontaminationConfig config) throws IOException {
        File file = configWriteFile(directory);
        File configFolder = file.getParentFile();
        if (!configFolder.isDirectory() && !configFolder.mkdirs()) {
            throw new IOException("Could not create " + configFolder.getAbsolutePath());
        }
        write(file, config);
    }

    private static File configWriteFile(String directory) {
        return new File(FlashProjectLayout.forDirectory(directory).configurationWriteDir(), CONFIG_FILENAME);
    }

    private static File configReadFile(String directory) {
        return configWriteFile(directory);
    }

    public static void write(File file, SpectralDecontaminationConfig config) throws IOException {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        AtomicFileWriter.writeUtf8(file, new AtomicFileWriter.WriterAction() {
            @Override
            public void write(Writer writer) throws IOException {
                writer.write(toJson(config));
            }
        });
    }

    static String toJson(SpectralDecontaminationConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"version\": ").append(config.getVersion()).append(",\n");
        sb.append("  \"goal\": \"").append(escape(config.getGoal().getKey())).append("\",\n");
        sb.append("  \"conditionSource\": \"")
                .append(escape(config.getConditionSource().getKey())).append("\",\n");
        sb.append("  \"targetChannelIndex\": ").append(config.getTargetChannelIndex()).append(",\n");
        sb.append("  \"bleedThroughChannelIndexes\": ")
                .append(intArrayJson(config.getBleedThroughChannelIndexes())).append(",\n");
        sb.append("  \"autofluorescenceChannelIndexes\": ")
                .append(intArrayJson(config.getAutofluorescenceChannelIndexes())).append(",\n");
        sb.append("  \"excludedChannelIndexes\": ")
                .append(intArrayJson(config.getExcludedChannelIndexes())).append(",\n");
        sb.append("  \"controlConditionNames\": ")
                .append(stringArrayJson(config.getControlConditionNames())).append(",\n");
        sb.append("  \"experimentalConditionNames\": ")
                .append(stringArrayJson(config.getExperimentalConditionNames())).append(",\n");
        sb.append("  \"correctionPresetId\": \"")
                .append(escape(config.getCorrectionPipeline().getPresetId())).append("\",\n");
        sb.append("  \"expertMode\": ").append(config.getCorrectionPipeline().isExpertMode()).append(",\n");
        sb.append("  \"correctionFeatureIds\": ")
                .append(stringArrayJson(config.getCorrectionPipeline().getFeatureIds())).append(",\n");
        sb.append("  \"featureSettings\": ")
                .append(featureSettingsJson(config.getFeatureSettingsById())).append("\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static int readInt(String json, String key, int defaultValue) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) return defaultValue;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String readString(String json, String key, String defaultValue) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) return defaultValue;
        return unescape(matcher.group(1));
    }

    private static boolean readBoolean(String json, String key, boolean defaultValue) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(true|false)",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) return defaultValue;
        return Boolean.parseBoolean(matcher.group(1));
    }

    private static List<Integer> readIntArray(String json, String key) throws IOException {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(json);
        List<Integer> values = new ArrayList<Integer>();
        if (!matcher.find()) return values;
        String body = matcher.group(1).trim();
        if (body.isEmpty()) return values;
        String[] tokens = body.split(",");
        for (String token : tokens) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;
            try {
                values.add(Integer.valueOf(Integer.parseInt(trimmed)));
            } catch (NumberFormatException e) {
                throw new IOException("Invalid integer in " + key + ": " + trimmed, e);
            }
        }
        return values;
    }

    private static List<String> readStringArray(String json, String key) throws IOException {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(json);
        List<String> values = new ArrayList<String>();
        if (!matcher.find()) return values;
        String body = matcher.group(1).trim();
        if (body.isEmpty()) return values;

        int index = 0;
        while (index < body.length()) {
            while (index < body.length() && Character.isWhitespace(body.charAt(index))) index++;
            if (index >= body.length()) break;
            if (body.charAt(index) != '"') {
                throw new IOException("Invalid string in " + key + ": expected quoted value");
            }
            index++;
            StringBuilder sb = new StringBuilder();
            boolean escaped = false;
            while (index < body.length()) {
                char c = body.charAt(index++);
                if (escaped) {
                    if (c == 'n') sb.append('\n');
                    else if (c == 'r') sb.append('\r');
                    else if (c == 't') sb.append('\t');
                    else sb.append(c);
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
            }
            values.add(sb.toString());
            while (index < body.length() && Character.isWhitespace(body.charAt(index))) index++;
            if (index < body.length()) {
                if (body.charAt(index) != ',') {
                    throw new IOException("Invalid string array in " + key + ": expected comma");
                }
                index++;
            }
        }
        return values;
    }

    private static LinkedHashMap<String, CorrectionPipeline.Settings> readFeatureSettings(String json)
            throws IOException {
        LinkedHashMap<String, CorrectionPipeline.Settings> settingsByFeature =
                new LinkedHashMap<String, CorrectionPipeline.Settings>();
        String body = readObjectBody(json, "featureSettings");
        if (body == null || body.trim().isEmpty()) {
            return settingsByFeature;
        }

        int index = 0;
        while (index < body.length()) {
            index = skipWhitespaceAndCommas(body, index);
            if (index >= body.length()) break;
            if (body.charAt(index) != '"') {
                throw new IOException("Invalid featureSettings object: expected feature id");
            }
            ParsedString featureId = parseJsonString(body, index);
            index = skipWhitespace(body, featureId.nextIndex);
            if (index >= body.length() || body.charAt(index) != ':') {
                throw new IOException("Invalid featureSettings object: expected ':' after feature id");
            }
            index = skipWhitespace(body, index + 1);
            if (index >= body.length() || body.charAt(index) != '{') {
                throw new IOException("Invalid featureSettings object: expected settings object");
            }
            int end = matchingBrace(body, index);
            CorrectionPipeline.Settings settings = readSettingsObject(body.substring(index + 1, end));
            settingsByFeature.put(featureId.value.trim().toLowerCase(Locale.ROOT), settings);
            index = end + 1;
        }
        return settingsByFeature;
    }

    private static CorrectionPipeline.Settings readSettingsObject(String body) throws IOException {
        CorrectionPipeline.Settings settings = new CorrectionPipeline.Settings();
        int index = 0;
        while (index < body.length()) {
            index = skipWhitespaceAndCommas(body, index);
            if (index >= body.length()) break;
            if (body.charAt(index) != '"') {
                throw new IOException("Invalid feature setting: expected key");
            }
            ParsedString key = parseJsonString(body, index);
            index = skipWhitespace(body, key.nextIndex);
            if (index >= body.length() || body.charAt(index) != ':') {
                throw new IOException("Invalid feature setting: expected ':' after key");
            }
            index = skipWhitespace(body, index + 1);
            ParsedValue value = parseJsonValue(body, index);
            settings.put(key.value, value.value);
            index = value.nextIndex;
        }
        return settings;
    }

    private static String readObjectBody(String json, String key) throws IOException {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\{");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) return null;
        int openBrace = matcher.end() - 1;
        int closeBrace = matchingBrace(json, openBrace);
        return json.substring(openBrace + 1, closeBrace);
    }

    private static int matchingBrace(String text, int openBrace) throws IOException {
        if (text == null || openBrace < 0 || openBrace >= text.length() || text.charAt(openBrace) != '{') {
            throw new IOException("Invalid JSON object: expected opening brace");
        }
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = openBrace; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        throw new IOException("Invalid JSON object: missing closing brace");
    }

    private static int skipWhitespaceAndCommas(String text, int index) {
        int i = skipWhitespace(text, index);
        while (i < text.length() && text.charAt(i) == ',') {
            i = skipWhitespace(text, i + 1);
        }
        return i;
    }

    private static int skipWhitespace(String text, int index) {
        int i = index;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static ParsedString parseJsonString(String text, int index) throws IOException {
        if (index >= text.length() || text.charAt(index) != '"') {
            throw new IOException("Invalid JSON string");
        }
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        int i = index + 1;
        while (i < text.length()) {
            char c = text.charAt(i++);
            if (escaped) {
                if (c == 'n') sb.append('\n');
                else if (c == 'r') sb.append('\r');
                else if (c == 't') sb.append('\t');
                else sb.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return new ParsedString(sb.toString(), i);
            } else {
                sb.append(c);
            }
        }
        throw new IOException("Invalid JSON string: missing closing quote");
    }

    private static ParsedValue parseJsonValue(String text, int index) throws IOException {
        if (index >= text.length()) {
            return new ParsedValue("", index);
        }
        if (text.charAt(index) == '"') {
            ParsedString parsed = parseJsonString(text, index);
            return new ParsedValue(parsed.value, parsed.nextIndex);
        }
        int i = index;
        while (i < text.length() && text.charAt(i) != ',') {
            i++;
        }
        return new ParsedValue(text.substring(index, i).trim(), i);
    }

    private static String intArrayJson(List<Integer> values) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(values.get(i).intValue());
        }
        sb.append("]");
        return sb.toString();
    }

    private static String stringArrayJson(List<String> values) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"").append(escape(values.get(i))).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String featureSettingsJson(Map<String, CorrectionPipeline.Settings> settingsByFeatureId) {
        if (settingsByFeatureId == null || settingsByFeatureId.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        int featureIndex = 0;
        for (Map.Entry<String, CorrectionPipeline.Settings> featureEntry : settingsByFeatureId.entrySet()) {
            if (featureEntry.getKey() == null || featureEntry.getKey().trim().isEmpty()
                    || featureEntry.getValue() == null || featureEntry.getValue().getValues().isEmpty()) {
                continue;
            }
            if (featureIndex > 0) sb.append(",");
            sb.append("\n    \"").append(escape(featureEntry.getKey())).append("\": {");
            int settingIndex = 0;
            for (Map.Entry<String, String> settingEntry : featureEntry.getValue().getValues().entrySet()) {
                if (settingEntry.getKey() == null || settingEntry.getKey().trim().isEmpty()) continue;
                if (settingIndex > 0) sb.append(",");
                sb.append("\n      \"").append(escape(settingEntry.getKey())).append("\": \"")
                        .append(escape(settingEntry.getValue())).append("\"");
                settingIndex++;
            }
            sb.append("\n    }");
            featureIndex++;
        }
        if (featureIndex > 0) {
            sb.append("\n  ");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escape(String value) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '"') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String unescape(String value) {
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                if (c == 'n') sb.append('\n');
                else if (c == 'r') sb.append('\r');
                else if (c == 't') sb.append('\t');
                else sb.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else {
                sb.append(c);
            }
        }
        if (escaped) sb.append('\\');
        return sb.toString();
    }

    private static final class ParsedString {
        final String value;
        final int nextIndex;

        ParsedString(String value, int nextIndex) {
            this.value = value;
            this.nextIndex = nextIndex;
        }
    }

    private static final class ParsedValue {
        final String value;
        final int nextIndex;

        ParsedValue(String value, int nextIndex) {
            this.value = value;
            this.nextIndex = nextIndex;
        }
    }
}
