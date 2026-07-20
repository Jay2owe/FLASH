package flash.pipeline.bin;

import flash.pipeline.ui.wizard.JsonIO;
import flash.pipeline.zslice.ZSliceMode;
import flash.pipeline.zslice.ZSliceRange;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ChannelConfigCodec {
    private static final int SCHEMA_VERSION = 1;

    private static final String K_COMMENT = "_comment";
    private static final String K_SCHEMA_VERSION = "schemaVersion";
    private static final String K_WRITER_ID = "writerId";
    private static final String K_WRITTEN_AT_MILLIS = "writtenAtMillis";
    private static final String K_CHANNELS = "channels";
    private static final String K_Z_SLICE_MODE = "zSliceMode";
    private static final String K_Z_SLICE_SELECTIONS = "zSliceSelections";
    private static final String K_CLICK_CAPTURE_USED = "clickCaptureUsed";
    private static final String K_COMPLETE = "complete";

    private static final String K_INDEX = "index";
    private static final String K_NAME = "name";
    private static final String K_COLOR = "color";
    private static final String K_MARKER_ID = "markerId";
    private static final String K_MARKER_SHAPE = "markerShape";
    private static final String K_MARKER_CROWDING_SENSITIVE = "markerCrowdingSensitive";
    private static final String K_THRESHOLD = "threshold";
    private static final String K_SIZE = "size";
    private static final String K_MINMAX = "minmax";
    private static final String K_INTENSITY_THRESHOLD = "intensityThreshold";
    private static final String K_SEGMENTATION_METHOD = "segmentationMethod";
    private static final String K_FILTER_PRESET = "filterPreset";
    private static final String K_STATUS = "status";
    private static final String K_START_SLICE = "startSlice";
    private static final String K_END_SLICE = "endSlice";

    // Deconvolution (per-channel) — lenient raw primitives, additive, schema v1.
    private static final String K_DECONV_ENGINE_KEY = "deconvEngineKey";
    private static final String K_DECONV_ALGORITHM = "deconvAlgorithm";
    private static final String K_DECONV_PSF_MODEL = "deconvPsfModel";
    private static final String K_DECONV_ITERATIONS = "deconvIterations";
    private static final String K_DECONV_REGULARIZATION = "deconvRegularization";
    private static final String K_EMISSION_WAVELENGTH_NM = "emissionWavelengthNm";
    private static final String K_ROUTE_ANALYSIS = "routeAnalysis";
    private static final String K_ROUTE_DISPLAY = "routeDisplay";

    // Deconvolution shared optics (root) — scope/sample level only.
    private static final String K_DECONV_OPTICS = "deconvOptics";
    private static final String K_OPTICS_NA = "na";
    private static final String K_OPTICS_IMMERSION_RI = "immersionRi";
    private static final String K_OPTICS_SAMPLE_RI = "sampleRi";
    private static final String K_OPTICS_SCOPE_MODALITY = "scopeModality";
    private static final String K_OPTICS_PINHOLE_AIRY_UNITS = "pinholeAiryUnits";

    private ChannelConfigCodec() {
    }

    public static String encode(ChannelConfig cfg) {
        return prettyPrint(JsonIO.write(toJsonObject(cfg == null ? new ChannelConfig() : cfg)));
    }

    public static ChannelConfig decode(String json) throws IOException {
        return fromJsonObject(JsonIO.parseObject(json));
    }

    public static ChannelConfig decodeOrNull(String json) {
        try {
            return decode(json);
        } catch (IOException e) {
            return null;
        }
    }

    /** The schema version this build writes and can read up to. */
    public static int schemaVersion() {
        return SCHEMA_VERSION;
    }

    /**
     * Read only the {@code schemaVersion} field without decoding the rest.
     * Returns -1 when the text is not a parseable JSON object or the field is
     * missing/invalid. Used to tell a newer-version file apart from a corrupt
     * one before attempting a full decode.
     */
    public static int peekSchemaVersion(String json) {
        try {
            return JsonIO.intValue(JsonIO.parseObject(json).get(K_SCHEMA_VERSION), -1);
        } catch (IOException e) {
            return -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static Map<String, Object> toJsonObject(ChannelConfig cfg) {
        Map<String, Object> root = JsonIO.object();
        appendComment(root, cfg.extras);
        root.put(K_SCHEMA_VERSION, Integer.valueOf(cfg.schemaVersion));
        root.put(K_WRITER_ID, cfg.writerId);
        root.put(K_WRITTEN_AT_MILLIS, Long.valueOf(cfg.writtenAtMillis));
        root.put(K_CHANNELS, channelsToJson(cfg.channels));
        root.put(K_Z_SLICE_MODE, (cfg.zSliceMode == null ? ZSliceMode.FULL : cfg.zSliceMode).name());
        root.put(K_Z_SLICE_SELECTIONS, zSliceSelectionsToJson(cfg.zSliceSelections));
        root.put(K_CLICK_CAPTURE_USED, Boolean.valueOf(cfg.clickCaptureUsed));
        // Write the completeness flag only when set, so files predating it stay
        // byte-stable on a read/write cycle (absent => per-property fallback).
        if (cfg.complete != null) {
            root.put(K_COMPLETE, cfg.complete);
        }
        // Write the shared optics block only when present, so non-deconv configs stay byte-stable
        // on a read/write cycle.
        if (cfg.deconvOptics != null) {
            root.put(K_DECONV_OPTICS, deconvOpticsToJson(cfg.deconvOptics));
        }
        appendUnknown(root, cfg.extras);
        return root;
    }

    private static ChannelConfig fromJsonObject(Map<String, Object> root) throws IOException {
        ChannelConfig cfg = new ChannelConfig();
        int onDisk = JsonIO.intValue(root.get(K_SCHEMA_VERSION), -1);
        if (onDisk < 1) {
            throw new IOException("Unsupported channel_config schemaVersion: " + onDisk);
        }
        if (onDisk > SCHEMA_VERSION) {
            // Made by a newer FLASH than this build understands. Surface a typed
            // signal so callers can warn and refuse to overwrite, instead of
            // treating it as blank (which would wipe the user's newer project).
            throw new NewerSchemaException(onDisk, SCHEMA_VERSION);
        }
        if (onDisk < SCHEMA_VERSION) {
            // Older on-disk shape: bring the parsed map up to the current schema
            // with ordered, additive migration steps before extracting fields.
            root = ChannelConfigMigrations.upgrade(root, onDisk);
        }
        cfg.originalSchemaVersion = onDisk;
        cfg.migrated = onDisk < SCHEMA_VERSION;
        cfg.schemaVersion = SCHEMA_VERSION;

        cfg.writerId = JsonIO.stringValue(root.get(K_WRITER_ID));
        cfg.writtenAtMillis = longValue(root.get(K_WRITTEN_AT_MILLIS), 0L);
        cfg.channels = channelsFromJson(JsonIO.asList(root.get(K_CHANNELS)));
        cfg.zSliceMode = ZSliceMode.fromConfigToken(JsonIO.stringValue(root.get(K_Z_SLICE_MODE)));
        cfg.zSliceSelections = zSliceSelectionsFromJson(JsonIO.asObject(root.get(K_Z_SLICE_SELECTIONS)));
        cfg.clickCaptureUsed = JsonIO.booleanValue(root.get(K_CLICK_CAPTURE_USED), false);
        cfg.complete = booleanOrNull(root.get(K_COMPLETE));
        cfg.deconvOptics = deconvOpticsFromJson(root.get(K_DECONV_OPTICS));
        cfg.extras = extras(root, rootKnownKeys());
        return cfg;
    }

    private static List<Object> channelsToJson(List<ChannelConfig.Channel> channels) {
        List<Object> rows = new ArrayList<Object>();
        if (channels == null) {
            return rows;
        }
        for (ChannelConfig.Channel channel : channels) {
            if (channel == null) {
                continue;
            }
            Map<String, Object> row = JsonIO.object();
            appendComment(row, channel.extras);
            row.put(K_INDEX, Integer.valueOf(channel.index));
            row.put(K_NAME, channel.name);
            row.put(K_COLOR, channel.color);
            row.put(K_MARKER_ID, channel.markerId);
            row.put(K_MARKER_SHAPE, channel.markerShape);
            row.put(K_MARKER_CROWDING_SENSITIVE, Boolean.valueOf(channel.markerCrowdingSensitive));
            row.put(K_THRESHOLD, channel.threshold);
            row.put(K_SIZE, channel.size);
            row.put(K_MINMAX, channel.minmax);
            row.put(K_INTENSITY_THRESHOLD, channel.intensityThreshold);
            row.put(K_SEGMENTATION_METHOD, channel.segmentationMethod);
            row.put(K_FILTER_PRESET, channel.filterPreset);
            // Deconv fields written only when set, so non-deconv channels stay byte-stable.
            putIfNotNull(row, K_DECONV_ENGINE_KEY, channel.deconvEngineKey);
            putIfNotNull(row, K_DECONV_ALGORITHM, channel.deconvAlgorithm);
            putIfNotNull(row, K_DECONV_PSF_MODEL, channel.deconvPsfModel);
            putIfNotNull(row, K_DECONV_ITERATIONS, channel.deconvIterations);
            putIfNotNull(row, K_DECONV_REGULARIZATION, channel.deconvRegularization);
            putIfNotNull(row, K_EMISSION_WAVELENGTH_NM, channel.emissionWavelengthNm);
            putIfNotNull(row, K_ROUTE_ANALYSIS, channel.routeAnalysis);
            putIfNotNull(row, K_ROUTE_DISPLAY, channel.routeDisplay);
            row.put(K_STATUS, statusToJson(channel.status));
            appendUnknown(row, channel.extras);
            rows.add(row);
        }
        return rows;
    }

    private static List<ChannelConfig.Channel> channelsFromJson(List<Object> values) {
        List<ChannelConfig.Channel> channels = new ArrayList<ChannelConfig.Channel>();
        for (Object value : values) {
            Map<String, Object> row = JsonIO.asObject(value);
            ChannelConfig.Channel channel = new ChannelConfig.Channel();
            channel.index = JsonIO.intValue(row.get(K_INDEX), 0);
            channel.name = JsonIO.stringValue(row.get(K_NAME));
            channel.color = JsonIO.stringValue(row.get(K_COLOR));
            channel.markerId = JsonIO.stringValue(row.get(K_MARKER_ID));
            channel.markerShape = JsonIO.stringValue(row.get(K_MARKER_SHAPE));
            channel.markerCrowdingSensitive = JsonIO.booleanValue(row.get(K_MARKER_CROWDING_SENSITIVE), false);
            channel.threshold = JsonIO.stringValue(row.get(K_THRESHOLD));
            channel.size = JsonIO.stringValue(row.get(K_SIZE));
            channel.minmax = JsonIO.stringValue(row.get(K_MINMAX));
            channel.intensityThreshold = JsonIO.stringValue(row.get(K_INTENSITY_THRESHOLD));
            channel.segmentationMethod = JsonIO.stringValue(row.get(K_SEGMENTATION_METHOD));
            channel.filterPreset = JsonIO.stringValue(row.get(K_FILTER_PRESET));
            channel.deconvEngineKey = JsonIO.stringValue(row.get(K_DECONV_ENGINE_KEY));
            channel.deconvAlgorithm = JsonIO.stringValue(row.get(K_DECONV_ALGORITHM));
            channel.deconvPsfModel = JsonIO.stringValue(row.get(K_DECONV_PSF_MODEL));
            channel.deconvIterations = JsonIO.intOrNull(row.get(K_DECONV_ITERATIONS));
            channel.deconvRegularization = JsonIO.doubleOrNull(row.get(K_DECONV_REGULARIZATION));
            channel.emissionWavelengthNm = JsonIO.doubleOrNull(row.get(K_EMISSION_WAVELENGTH_NM));
            channel.routeAnalysis = JsonIO.stringValue(row.get(K_ROUTE_ANALYSIS));
            channel.routeDisplay = JsonIO.stringValue(row.get(K_ROUTE_DISPLAY));
            channel.status = statusFromJson(JsonIO.asObject(row.get(K_STATUS)));
            channel.extras = extras(row, channelKnownKeys());
            channels.add(channel);
        }
        return channels;
    }

    private static Map<String, Object> statusToJson(Map<String, ChannelConfig.PropertyStatus> status) {
        Map<String, Object> out = JsonIO.object();
        if (status == null) {
            return out;
        }
        for (Map.Entry<String, ChannelConfig.PropertyStatus> entry : status.entrySet()) {
            out.put(entry.getKey(), statusToken(entry.getValue()));
        }
        return out;
    }

    private static Map<String, ChannelConfig.PropertyStatus> statusFromJson(Map<String, Object> raw) {
        Map<String, ChannelConfig.PropertyStatus> out =
                new LinkedHashMap<String, ChannelConfig.PropertyStatus>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            out.put(entry.getKey(), propertyStatus(JsonIO.stringValue(entry.getValue())));
        }
        return out;
    }

    private static Map<String, Object> zSliceSelectionsToJson(Map<String, ZSliceRange> selections) {
        Map<String, Object> out = JsonIO.object();
        if (selections == null) {
            return out;
        }
        for (Map.Entry<String, ZSliceRange> entry : selections.entrySet()) {
            ZSliceRange range = entry.getValue();
            if (range == null) {
                out.put(entry.getKey(), null);
            } else {
                Map<String, Object> row = JsonIO.object();
                row.put(K_START_SLICE, Integer.valueOf(range.startSlice));
                row.put(K_END_SLICE, Integer.valueOf(range.endSlice));
                out.put(entry.getKey(), row);
            }
        }
        return out;
    }

    private static Map<String, ZSliceRange> zSliceSelectionsFromJson(Map<String, Object> raw) {
        Map<String, ZSliceRange> out = new LinkedHashMap<String, ZSliceRange>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                out.put(entry.getKey(), null);
                continue;
            }
            Map<String, Object> row = JsonIO.asObject(value);
            int start = JsonIO.intValue(row.get(K_START_SLICE), 0);
            int end = JsonIO.intValue(row.get(K_END_SLICE), 0);
            if (start > 0 && end >= start) {
                out.put(entry.getKey(), new ZSliceRange(start, end));
            }
        }
        return out;
    }

    private static String statusToken(ChannelConfig.PropertyStatus status) {
        ChannelConfig.PropertyStatus safe = status == null
                ? ChannelConfig.PropertyStatus.PENDING
                : status;
        return safe.name().toLowerCase(Locale.ROOT);
    }

    private static ChannelConfig.PropertyStatus propertyStatus(String token) {
        if (token == null) {
            return ChannelConfig.PropertyStatus.PENDING;
        }
        String normalized = token.trim().toUpperCase(Locale.ROOT);
        for (ChannelConfig.PropertyStatus status : ChannelConfig.PropertyStatus.values()) {
            if (status.name().equals(normalized)) {
                return status;
            }
        }
        return ChannelConfig.PropertyStatus.PENDING;
    }

    private static Boolean booleanOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.valueOf(Boolean.parseBoolean(String.valueOf(value).trim()));
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

    private static Map<String, Object> extras(Map<String, Object> source, Map<String, Boolean> knownKeys) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
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
        keys.put(K_CHANNELS, Boolean.TRUE);
        keys.put(K_Z_SLICE_MODE, Boolean.TRUE);
        keys.put(K_Z_SLICE_SELECTIONS, Boolean.TRUE);
        keys.put(K_CLICK_CAPTURE_USED, Boolean.TRUE);
        keys.put(K_COMPLETE, Boolean.TRUE);
        keys.put(K_DECONV_OPTICS, Boolean.TRUE);
        return keys;
    }

    private static Map<String, Boolean> channelKnownKeys() {
        Map<String, Boolean> keys = new LinkedHashMap<String, Boolean>();
        keys.put(K_INDEX, Boolean.TRUE);
        keys.put(K_NAME, Boolean.TRUE);
        keys.put(K_COLOR, Boolean.TRUE);
        keys.put(K_MARKER_ID, Boolean.TRUE);
        keys.put(K_MARKER_SHAPE, Boolean.TRUE);
        keys.put(K_MARKER_CROWDING_SENSITIVE, Boolean.TRUE);
        keys.put(K_THRESHOLD, Boolean.TRUE);
        keys.put(K_SIZE, Boolean.TRUE);
        keys.put(K_MINMAX, Boolean.TRUE);
        keys.put(K_INTENSITY_THRESHOLD, Boolean.TRUE);
        keys.put(K_SEGMENTATION_METHOD, Boolean.TRUE);
        keys.put(K_FILTER_PRESET, Boolean.TRUE);
        keys.put(K_DECONV_ENGINE_KEY, Boolean.TRUE);
        keys.put(K_DECONV_ALGORITHM, Boolean.TRUE);
        keys.put(K_DECONV_PSF_MODEL, Boolean.TRUE);
        keys.put(K_DECONV_ITERATIONS, Boolean.TRUE);
        keys.put(K_DECONV_REGULARIZATION, Boolean.TRUE);
        keys.put(K_EMISSION_WAVELENGTH_NM, Boolean.TRUE);
        keys.put(K_ROUTE_ANALYSIS, Boolean.TRUE);
        keys.put(K_ROUTE_DISPLAY, Boolean.TRUE);
        keys.put(K_STATUS, Boolean.TRUE);
        return keys;
    }

    private static Map<String, Object> deconvOpticsToJson(ChannelConfig.DeconvOptics optics) {
        Map<String, Object> out = JsonIO.object();
        out.put(K_OPTICS_NA, optics.na);
        out.put(K_OPTICS_IMMERSION_RI, optics.immersionRi);
        out.put(K_OPTICS_SAMPLE_RI, optics.sampleRi);
        out.put(K_OPTICS_SCOPE_MODALITY, optics.scopeModality);
        out.put(K_OPTICS_PINHOLE_AIRY_UNITS, optics.pinholeAiryUnits);
        return out;
    }

    private static ChannelConfig.DeconvOptics deconvOpticsFromJson(Object value) {
        if (!(value instanceof Map)) {
            return null;
        }
        Map<String, Object> row = JsonIO.asObject(value);
        ChannelConfig.DeconvOptics optics = new ChannelConfig.DeconvOptics();
        optics.na = JsonIO.doubleOrNull(row.get(K_OPTICS_NA));
        optics.immersionRi = JsonIO.doubleOrNull(row.get(K_OPTICS_IMMERSION_RI));
        optics.sampleRi = JsonIO.doubleOrNull(row.get(K_OPTICS_SAMPLE_RI));
        optics.scopeModality = JsonIO.stringValue(row.get(K_OPTICS_SCOPE_MODALITY));
        optics.pinholeAiryUnits = JsonIO.doubleOrNull(row.get(K_OPTICS_PINHOLE_AIRY_UNITS));
        return optics;
    }

    private static void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
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
