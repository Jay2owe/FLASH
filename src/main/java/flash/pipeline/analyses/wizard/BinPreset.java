package flash.pipeline.analyses.wizard;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.ui.wizard.JsonIO;
import flash.pipeline.ui.wizard.Preset;
import flash.pipeline.zslice.ZSliceMode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persisted channel configuration preset. The payload mirrors channel_config.json plus
 * filter presets, segmentation methods, z-slice mode, and marker identities.
 */
public final class BinPreset implements Preset<BinConfig> {

    public static final String CURRENT_LIBRARY_VERSION = "1";

    private final String name;
    private final String description;
    private final String libraryVersion;
    private final BinConfig payload;
    private final List<String> markerIds;
    private final List<String> markerShapes;
    private final List<Boolean> markerCrowdingSensitive;

    public BinPreset(String name,
                     String description,
                     String libraryVersion,
                     BinConfig payload,
                     List<String> markerIds,
                     List<String> markerShapes,
                     List<Boolean> markerCrowdingSensitive) {
        this.name = requireText("name", name);
        this.description = emptyToNull(description);
        this.libraryVersion = emptyToNull(libraryVersion) == null
                ? CURRENT_LIBRARY_VERSION
                : libraryVersion.trim();
        this.payload = copyConfig(payload == null ? new BinConfig() : payload);
        int channels = this.payload.numChannels();
        this.markerIds = immutablePaddedStrings(markerIds, channels);
        this.markerShapes = immutablePaddedStrings(markerShapes, channels);
        this.markerCrowdingSensitive = immutablePaddedBooleans(markerCrowdingSensitive, channels);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public BinConfig getPayload() {
        return copyConfig(payload);
    }

    public String getLibraryVersion() {
        return libraryVersion;
    }

    public List<String> getMarkerIds() {
        return markerIds;
    }

    public List<String> getMarkerShapes() {
        return markerShapes;
    }

    public List<Boolean> getMarkerCrowdingSensitive() {
        return markerCrowdingSensitive;
    }

    public Map<String, Object> toJsonObject() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("name", name);
        if (description != null) {
            root.put("description", description);
        }
        root.put("libraryVersion", libraryVersion);
        root.put("zSliceMode", payload.zSliceMode == null ? ZSliceMode.FULL.name() : payload.zSliceMode.name());

        List<Object> channels = new ArrayList<Object>();
        for (int i = 0; i < payload.numChannels(); i++) {
            Map<String, Object> channel = new LinkedHashMap<String, Object>();
            channel.put("name", valueAt(payload.channelNames, i, ""));
            channel.put("color", valueAt(payload.channelColors, i, "Grays"));
            channel.put("objectThreshold", valueAt(payload.channelThresholds, i, "default"));
            channel.put("particleSize", valueAt(payload.channelSizes, i, "100-Infinity"));
            channel.put("displayRange", valueAt(payload.channelMinMax, i, "None"));
            channel.put("intensityThreshold", valueAt(payload.channelIntensityThresholds, i, "default"));
            channel.put("segmentationMethod", valueAt(payload.segmentationMethods, i, "classical"));
            channel.put("filterPreset", valueAt(payload.channelFilterPresets, i, "Default"));
            channel.put("markerId", valueAt(markerIds, i, ""));
            channel.put("shape", valueAt(markerShapes, i, ""));
            channel.put("crowdingSensitive", Boolean.valueOf(valueAt(markerCrowdingSensitive, i, Boolean.FALSE).booleanValue()));
            channels.add(channel);
        }
        root.put("channels", channels);
        return root;
    }

    public String toJson() {
        return JsonIO.write(toJsonObject());
    }

    public static BinPreset fromJson(String json) throws IOException {
        return fromJsonObject(JsonIO.parseObject(json));
    }

    public static BinPreset fromJsonObject(Map<String, Object> root) throws IOException {
        if (root == null) {
            throw new IOException("Preset JSON object is required.");
        }
        BinConfig config = new BinConfig();
        config.zSliceMode = parseZSliceMode(JsonIO.stringValue(root.get("zSliceMode")));
        List<String> markerIds = new ArrayList<String>();
        List<String> markerShapes = new ArrayList<String>();
        List<Boolean> markerCrowdingSensitive = new ArrayList<Boolean>();

        for (Object item : JsonIO.asList(root.get("channels"))) {
            Map<String, Object> channel = JsonIO.asObject(item);
            config.channelNames.add(stringOr(channel.get("name"), "Channel" + (config.channelNames.size() + 1)));
            config.channelColors.add(stringOr(channel.get("color"), "Grays"));
            config.channelThresholds.add(stringOr(channel.get("objectThreshold"), "default"));
            config.channelSizes.add(stringOr(channel.get("particleSize"), "100-Infinity"));
            config.channelMinMax.add(stringOr(channel.get("displayRange"), "None"));
            config.channelIntensityThresholds.add(stringOr(channel.get("intensityThreshold"), "default"));
            config.segmentationMethods.add(stringOr(channel.get("segmentationMethod"), "classical"));
            config.channelFilterPresets.add(stringOr(channel.get("filterPreset"), "Default"));
            markerIds.add(stringOr(channel.get("markerId"), ""));
            markerShapes.add(stringOr(channel.get("shape"), ""));
            markerCrowdingSensitive.add(Boolean.valueOf(JsonIO.booleanValue(channel.get("crowdingSensitive"), false)));
        }

        return new BinPreset(
                stringOr(root.get("name"), "Channel Configuration Preset"),
                JsonIO.stringValue(root.get("description")),
                stringOr(root.get("libraryVersion"), CURRENT_LIBRARY_VERSION),
                config,
                markerIds,
                markerShapes,
                markerCrowdingSensitive);
    }

    public static BinConfig copyConfig(BinConfig source) {
        BinConfig copy = new BinConfig();
        if (source == null) {
            return copy;
        }
        copy.channelNames.addAll(source.channelNames);
        copy.channelColors.addAll(source.channelColors);
        copy.channelThresholds.addAll(source.channelThresholds);
        copy.channelSizes.addAll(source.channelSizes);
        copy.channelMinMax.addAll(source.channelMinMax);
        copy.channelIntensityThresholds.addAll(source.channelIntensityThresholds);
        copy.segmentationMethods.addAll(source.segmentationMethods);
        copy.channelFilterPresets.addAll(source.channelFilterPresets);
        copy.zSliceMode = source.zSliceMode == null ? ZSliceMode.FULL : source.zSliceMode;
        copy.zSliceSelections.putAll(source.zSliceSelections);
        return copy;
    }

    private static ZSliceMode parseZSliceMode(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return ZSliceMode.FULL;
        }
        try {
            return ZSliceMode.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ZSliceMode.FULL;
        }
    }

    private static String requireText(String label, String value) {
        String trimmed = emptyToNull(value);
        if (trimmed == null) {
            throw new IllegalArgumentException(label + " is required.");
        }
        return trimmed;
    }

    private static String emptyToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String stringOr(Object value, String fallback) {
        String text = JsonIO.stringValue(value);
        return text == null || text.trim().isEmpty() ? fallback : text.trim();
    }

    private static List<String> immutablePaddedStrings(List<String> values, int size) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < size; i++) {
            out.add(valueAt(values, i, ""));
        }
        return Collections.unmodifiableList(out);
    }

    private static List<Boolean> immutablePaddedBooleans(List<Boolean> values, int size) {
        List<Boolean> out = new ArrayList<Boolean>();
        for (int i = 0; i < size; i++) {
            out.add(valueAt(values, i, Boolean.FALSE));
        }
        return Collections.unmodifiableList(out);
    }

    private static String valueAt(List<String> values, int index, String fallback) {
        if (values == null || index < 0 || index >= values.size()) return fallback;
        String value = values.get(index);
        return value == null ? fallback : value;
    }

    private static Boolean valueAt(List<Boolean> values, int index, Boolean fallback) {
        if (values == null || index < 0 || index >= values.size()) return fallback;
        Boolean value = values.get(index);
        return value == null ? fallback : value;
    }
}
