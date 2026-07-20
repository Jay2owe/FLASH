package flash.pipeline.analyses.wizard;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.ui.wizard.JsonIO;
import flash.pipeline.ui.wizard.Preset;
import flash.pipeline.zslice.ZSliceMode;
import flash.pipeline.zslice.ZSliceRange;
import flash.pipeline.zslice.ZSliceSelection;

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
    public static final int CURRENT_SCHEMA_VERSION = 2;
    private static final int LEGACY_SCHEMA_VERSION = 1;

    private final String name;
    private final String description;
    private final String libraryVersion;
    private final int schemaVersion;
    private final BinConfig payload;
    private final List<String> markerIds;
    private final List<String> markerShapes;
    private final List<Boolean> markerCrowdingSensitive;
    private final boolean zSliceReviewRequired;
    private final String zSliceReviewWarning;

    public BinPreset(String name,
                     String description,
                     String libraryVersion,
                     BinConfig payload,
                     List<String> markerIds,
                     List<String> markerShapes,
                     List<Boolean> markerCrowdingSensitive) {
        this(name, description, libraryVersion, payload, markerIds, markerShapes,
                markerCrowdingSensitive, CURRENT_SCHEMA_VERSION, false, null);
    }

    private BinPreset(String name,
                      String description,
                      String libraryVersion,
                      BinConfig payload,
                      List<String> markerIds,
                      List<String> markerShapes,
                      List<Boolean> markerCrowdingSensitive,
                      int schemaVersion,
                      boolean zSliceReviewRequired,
                      String zSliceReviewWarning) {
        this.name = requireText("name", name);
        this.description = emptyToNull(description);
        this.libraryVersion = emptyToNull(libraryVersion) == null
                ? CURRENT_LIBRARY_VERSION
                : libraryVersion.trim();
        this.schemaVersion = schemaVersion;
        this.payload = copyConfig(payload == null ? new BinConfig() : payload);
        if (!zSliceReviewRequired) {
            validateCurrentZSlicePayload(this.payload);
        }
        int channels = this.payload.numChannels();
        this.markerIds = immutablePaddedStrings(markerIds, channels);
        this.markerShapes = immutablePaddedStrings(markerShapes, channels);
        this.markerCrowdingSensitive = immutablePaddedBooleans(markerCrowdingSensitive, channels);
        this.zSliceReviewRequired = zSliceReviewRequired;
        this.zSliceReviewWarning = emptyToNull(zSliceReviewWarning);
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

    public int getSchemaVersion() {
        return schemaVersion;
    }

    /** True when a legacy subset mode had no ranges and must not be applied automatically. */
    public boolean requiresZSliceReview() {
        return zSliceReviewRequired;
    }

    public String getZSliceReviewWarning() {
        return zSliceReviewWarning;
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
        if (zSliceReviewRequired) {
            throw new IllegalStateException(zSliceReviewWarning == null
                    ? "Legacy z-slice selection requires review before saving."
                    : zSliceReviewWarning);
        }
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("name", name);
        if (description != null) {
            root.put("description", description);
        }
        root.put("libraryVersion", libraryVersion);
        root.put("schemaVersion", Integer.valueOf(CURRENT_SCHEMA_VERSION));
        root.put("zSliceMode", payload.zSliceMode == null ? ZSliceMode.FULL.name() : payload.zSliceMode.name());
        root.put("zSliceSelections", zSliceSelectionsToJson(payload));

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
        boolean schemaMissing = !root.containsKey("schemaVersion");
        int schemaVersion = schemaMissing
                ? LEGACY_SCHEMA_VERSION
                : strictInteger(root.get("schemaVersion"), "schemaVersion");
        if (schemaVersion > CURRENT_SCHEMA_VERSION) {
            throw new IOException("Unsupported newer channel preset schemaVersion "
                    + schemaVersion + "; this FLASH build supports up to "
                    + CURRENT_SCHEMA_VERSION + ".");
        }
        if (schemaVersion < LEGACY_SCHEMA_VERSION) {
            throw new IOException("Unsupported channel preset schemaVersion " + schemaVersion + ".");
        }
        boolean legacy = schemaVersion < CURRENT_SCHEMA_VERSION;
        config.zSliceMode = parseZSliceMode(
                root.get("zSliceMode"), legacy, "zSliceMode");
        if (legacy) {
            if (root.containsKey("zSliceSelections")) {
                throw new IOException("Legacy channel preset schemaVersion " + schemaVersion
                        + " must contain only zSliceMode; zSliceSelections requires schemaVersion "
                        + CURRENT_SCHEMA_VERSION + ".");
            }
        } else {
            readCurrentZSliceSelections(root, config);
        }
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

        boolean reviewRequired = legacy && config.zSliceMode.usesSubset();
        String reviewWarning = reviewRequired
                ? "Legacy channel preset uses z-slice mode " + config.zSliceMode.name()
                + " but contains no per-series ranges. Review and select the z-slices again; "
                + "FLASH will not invent a full-stack selection."
                : null;
        try {
            return new BinPreset(
                    stringOr(root.get("name"), "Channel Configuration Preset"),
                    JsonIO.stringValue(root.get("description")),
                    stringOr(root.get("libraryVersion"), CURRENT_LIBRARY_VERSION),
                    config,
                    markerIds,
                    markerShapes,
                    markerCrowdingSensitive,
                    schemaVersion,
                    reviewRequired,
                    reviewWarning);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid channel preset z-slice payload: " + e.getMessage(), e);
        }
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

    private static ZSliceMode parseZSliceMode(Object value, boolean legacy,
                                               String field) throws IOException {
        if (value == null) {
            if (legacy) return ZSliceMode.FULL;
            throw new IOException("Channel preset field '" + field + "' is required.");
        }
        if (!(value instanceof String)) {
            throw new IOException("Channel preset field '" + field + "' must be a string.");
        }
        String raw = ((String) value).trim();
        if (raw.isEmpty()) {
            throw new IOException("Channel preset field '" + field + "' must not be blank.");
        }
        try {
            return ZSliceMode.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IOException("Channel preset field '" + field
                    + "' has unsupported value '" + raw + "'.", e);
        }
    }

    private static void readCurrentZSliceSelections(Map<String, Object> root,
                                                    BinConfig config) throws IOException {
        Object rawSelections = root.get("zSliceSelections");
        if (!(rawSelections instanceof List)) {
            throw new IOException("Channel preset field 'zSliceSelections' must be an array.");
        }
        List<Object> rows = JsonIO.asList(rawSelections);
        for (int i = 0; i < rows.size(); i++) {
            Object rawRow = rows.get(i);
            if (!(rawRow instanceof Map)) {
                throw new IOException("Channel preset zSliceSelections[" + i + "] must be an object.");
            }
            Map<String, Object> row = JsonIO.asObject(rawRow);
            int seriesIndex = strictInteger(row.get("seriesIndex"),
                    "zSliceSelections[" + i + "].seriesIndex");
            int totalSlices = strictInteger(row.get("totalSlices"),
                    "zSliceSelections[" + i + "].totalSlices");
            int startSlice = strictInteger(row.get("startSlice"),
                    "zSliceSelections[" + i + "].startSlice");
            int endSlice = strictInteger(row.get("endSlice"),
                    "zSliceSelections[" + i + "].endSlice");
            Object rawName = row.get("displayName");
            if (!(rawName instanceof String) || ((String) rawName).trim().isEmpty()) {
                throw new IOException("Channel preset field 'zSliceSelections[" + i
                        + "].displayName' must be a nonblank string.");
            }
            if (config.zSliceSelections.containsKey(Integer.valueOf(seriesIndex))) {
                throw new IOException("Channel preset has duplicate z-slice seriesIndex "
                        + seriesIndex + ".");
            }
            try {
                ZSliceRange range = new ZSliceRange(startSlice, endSlice);
                config.zSliceSelections.put(Integer.valueOf(seriesIndex),
                        new ZSliceSelection(seriesIndex, ((String) rawName).trim(),
                                totalSlices, range));
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid channel preset zSliceSelections[" + i
                        + "]: " + e.getMessage(), e);
            }
        }
    }

    private static List<Object> zSliceSelectionsToJson(BinConfig config) {
        List<Integer> indexes = new ArrayList<Integer>(config.zSliceSelections.keySet());
        Collections.sort(indexes);
        List<Object> rows = new ArrayList<Object>();
        for (Integer index : indexes) {
            ZSliceSelection selection = config.zSliceSelections.get(index);
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("seriesIndex", Integer.valueOf(selection.seriesIndex));
            row.put("displayName", selection.seriesName.trim());
            row.put("totalSlices", Integer.valueOf(selection.totalSlices));
            row.put("startSlice", Integer.valueOf(selection.range.startSlice));
            row.put("endSlice", Integer.valueOf(selection.range.endSlice));
            rows.add(row);
        }
        return rows;
    }

    private static int strictInteger(Object value, String field) throws IOException {
        if (!(value instanceof Number)) {
            throw new IOException("Channel preset field '" + field + "' must be an integer.");
        }
        Number number = (Number) value;
        double asDouble = number.doubleValue();
        long asLong = number.longValue();
        if (!Double.isFinite(asDouble) || asDouble != (double) asLong
                || asLong < Integer.MIN_VALUE || asLong > Integer.MAX_VALUE) {
            throw new IOException("Channel preset field '" + field + "' must be an integer.");
        }
        return (int) asLong;
    }

    private static void validateCurrentZSlicePayload(BinConfig config) {
        ZSliceMode mode = config.zSliceMode == null ? ZSliceMode.FULL : config.zSliceMode;
        if (mode == ZSliceMode.FULL) {
            if (!config.zSliceSelections.isEmpty()) {
                throw new IllegalArgumentException("FULL mode must not contain z-slice selections.");
            }
            return;
        }
        if (config.zSliceSelections.isEmpty()) {
            throw new IllegalArgumentException(mode.name()
                    + " mode requires complete per-series z-slice selections.");
        }
        Integer expectedCount = null;
        ZSliceRange expectedRange = null;
        for (Map.Entry<Integer, ZSliceSelection> entry : config.zSliceSelections.entrySet()) {
            Integer key = entry.getKey();
            ZSliceSelection selection = entry.getValue();
            if (key == null || key.intValue() < 0 || selection == null) {
                throw new IllegalArgumentException("Z-slice selections require nonnegative indexes and values.");
            }
            if (selection.seriesIndex != key.intValue()) {
                throw new IllegalArgumentException("Z-slice map index " + key
                        + " does not match selection index " + selection.seriesIndex + ".");
            }
            if (selection.seriesName == null || selection.seriesName.trim().isEmpty()) {
                throw new IllegalArgumentException("Z-slice series " + key
                        + " requires a stable display name.");
            }
            if (selection.range == null || !selection.range.isValidFor(selection.totalSlices)) {
                throw new IllegalArgumentException("Z-slice series " + key
                        + " has a range outside its saved total slice count.");
            }
            if (mode == ZSliceMode.SAME_COUNT) {
                if (expectedCount == null) {
                    expectedCount = Integer.valueOf(selection.range.count());
                } else if (expectedCount.intValue() != selection.range.count()) {
                    throw new IllegalArgumentException("SAME_COUNT selections must have equal slice counts.");
                }
            } else if (mode == ZSliceMode.SAME_ABSOLUTE) {
                if (expectedRange == null) {
                    expectedRange = selection.range;
                } else if (!expectedRange.equals(selection.range)) {
                    throw new IllegalArgumentException("SAME_ABSOLUTE selections must use the same range.");
                }
            }
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
