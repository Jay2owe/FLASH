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

    private static final String K_SCHEMA_VERSION = "schemaVersion";
    private static final String K_WRITER_ID = "writerId";
    private static final String K_WRITTEN_AT_MILLIS = "writtenAtMillis";
    private static final String K_CHANNELS = "channels";
    private static final String K_Z_SLICE_MODE = "zSliceMode";
    private static final String K_Z_SLICE_SELECTIONS = "zSliceSelections";
    private static final String K_CLICK_CAPTURE_USED = "clickCaptureUsed";

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

    private static Map<String, Object> toJsonObject(ChannelConfig cfg) {
        Map<String, Object> root = JsonIO.object();
        root.put(K_SCHEMA_VERSION, Integer.valueOf(cfg.schemaVersion));
        root.put(K_WRITER_ID, cfg.writerId);
        root.put(K_WRITTEN_AT_MILLIS, Long.valueOf(cfg.writtenAtMillis));
        root.put(K_CHANNELS, channelsToJson(cfg.channels));
        root.put(K_Z_SLICE_MODE, (cfg.zSliceMode == null ? ZSliceMode.FULL : cfg.zSliceMode).name());
        root.put(K_Z_SLICE_SELECTIONS, zSliceSelectionsToJson(cfg.zSliceSelections));
        root.put(K_CLICK_CAPTURE_USED, Boolean.valueOf(cfg.clickCaptureUsed));
        appendUnknown(root, cfg.extras);
        return root;
    }

    private static ChannelConfig fromJsonObject(Map<String, Object> root) throws IOException {
        ChannelConfig cfg = new ChannelConfig();
        cfg.schemaVersion = JsonIO.intValue(root.get(K_SCHEMA_VERSION), -1);
        if (cfg.schemaVersion != SCHEMA_VERSION) {
            throw new IOException("Unsupported channel_config schemaVersion: " + cfg.schemaVersion);
        }

        cfg.writerId = JsonIO.stringValue(root.get(K_WRITER_ID));
        cfg.writtenAtMillis = longValue(root.get(K_WRITTEN_AT_MILLIS), 0L);
        cfg.channels = channelsFromJson(JsonIO.asList(root.get(K_CHANNELS)));
        cfg.zSliceMode = ZSliceMode.fromConfigToken(JsonIO.stringValue(root.get(K_Z_SLICE_MODE)));
        cfg.zSliceSelections = zSliceSelectionsFromJson(JsonIO.asObject(root.get(K_Z_SLICE_SELECTIONS)));
        cfg.clickCaptureUsed = JsonIO.booleanValue(root.get(K_CLICK_CAPTURE_USED), false);
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

    private static Map<String, Boolean> rootKnownKeys() {
        Map<String, Boolean> keys = new LinkedHashMap<String, Boolean>();
        keys.put(K_SCHEMA_VERSION, Boolean.TRUE);
        keys.put(K_WRITER_ID, Boolean.TRUE);
        keys.put(K_WRITTEN_AT_MILLIS, Boolean.TRUE);
        keys.put(K_CHANNELS, Boolean.TRUE);
        keys.put(K_Z_SLICE_MODE, Boolean.TRUE);
        keys.put(K_Z_SLICE_SELECTIONS, Boolean.TRUE);
        keys.put(K_CLICK_CAPTURE_USED, Boolean.TRUE);
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
        keys.put(K_STATUS, Boolean.TRUE);
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
