package flash.pipeline.representative;

import flash.pipeline.presentation.PresentationTileConfig;

import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable configuration holder for the representative image figure workflow.
 * Later representative-image-figure stages add concrete fields here.
 */
public class RepresentativeFigureConfig {
    public static final String PROJECT_EXTRA_KEY = "representativeFigure";

    private static final int SCHEMA_VERSION = 1;

    public RepresentativeStatistic statistic = RepresentativeStatistic.QUICK;
    public RepresentativeStatLoader.ExistingResultOption existingResult = null;
    public RepresentativeStatTable statTable = new RepresentativeStatTable();
    public RepresentativeSelection selection = null;
    public RepresentativeLayout layout = null;
    public PresentationTileConfig tileConfig = null;
    public final Map<Integer, String> customDisplayRangesByChannel =
            new LinkedHashMap<Integer, String>();

    public String customDisplayRangeForChannel(int channelIndex) {
        if (channelIndex < 0) return null;
        return customDisplayRangesByChannel.get(Integer.valueOf(channelIndex));
    }

    public void setCustomDisplayRangeForChannel(int channelIndex, String token) {
        if (channelIndex < 0) return;
        Integer key = Integer.valueOf(channelIndex);
        if (token == null || token.trim().isEmpty()) {
            customDisplayRangesByChannel.remove(key);
        } else {
            customDisplayRangesByChannel.put(key, token.trim());
        }
    }

    public void clearCustomDisplayRanges() {
        customDisplayRangesByChannel.clear();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("schemaVersion", Integer.valueOf(SCHEMA_VERSION));
        root.put("statistic", statistic == null
                ? RepresentativeStatistic.QUICK.name()
                : statistic.name());
        root.put("statisticLabel", statistic == null
                ? RepresentativeStatistic.QUICK.label()
                : statistic.label());
        if (existingResult != null) {
            root.put("existingResult", existingResultToMap(existingResult));
        }
        if (selection != null) {
            root.put("conditionNames", new ArrayList<String>(selection.conditionNames()));
            root.put("lockedSeries", selectionToList(selection));
        }
        root.put("customDisplayRanges", displayRangesToList());
        if (layout != null) {
            root.put("layout", layoutToMap(layout));
        }
        if (tileConfig != null) {
            root.put("tileConfig", tileConfigToMap(tileConfig));
        }
        return root;
    }

    public void applyMap(Map<String, Object> values) {
        copyFrom(fromMap(values));
    }

    public void copyFrom(RepresentativeFigureConfig other) {
        if (other == null) {
            return;
        }
        statistic = other.statistic;
        existingResult = other.existingResult;
        statTable = other.statTable == null ? new RepresentativeStatTable() : other.statTable;
        selection = other.selection;
        layout = other.layout;
        tileConfig = other.tileConfig;
        customDisplayRangesByChannel.clear();
        customDisplayRangesByChannel.putAll(other.customDisplayRangesByChannel);
    }

    public static RepresentativeFigureConfig fromMap(Map<String, Object> values) {
        RepresentativeFigureConfig config = new RepresentativeFigureConfig();
        if (values == null || values.isEmpty()) {
            return config;
        }
        config.statistic = RepresentativeStatistic.fromLabel(
                string(firstNonNull(values.get("statistic"), values.get("statisticLabel"))));
        config.existingResult = existingResultFromMap(asObject(values.get("existingResult")));
        config.selection = selectionFrom(values);
        displayRangesFrom(values, config.customDisplayRangesByChannel);
        config.layout = layoutFromMap(asObject(values.get("layout")));
        if (config.layout == null && config.selection != null) {
            config.layout = RepresentativeLayout.allInOneRow(config.selection.conditionNames());
        }
        config.tileConfig = tileConfigFromMap(asObject(values.get("tileConfig")));
        if (config.tileConfig == null && config.selection != null) {
            config.tileConfig = ConditionLayoutChooser.defaultTileConfig(
                    ConditionLayoutChooser.defaultTileOrder(config.selection));
        }
        return config;
    }

    private static Map<String, Object> existingResultToMap(
            RepresentativeStatLoader.ExistingResultOption existing) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("columnName", existing.columnName);
        out.put("relativePath", existing.relativePath);
        out.put("label", existing.label);
        out.put("path", existing.file == null ? "" : existing.file.getAbsolutePath());
        return out;
    }

    private static RepresentativeStatLoader.ExistingResultOption existingResultFromMap(
            Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        String column = string(map.get("columnName"));
        if (column.isEmpty()) {
            column = string(map.get("sourceColumn"));
        }
        if (column.isEmpty()) {
            return null;
        }
        String relative = string(map.get("relativePath"));
        String path = string(map.get("path"));
        File file = path.isEmpty() ? null : new File(path);
        return new RepresentativeStatLoader.ExistingResultOption(file, column, relative);
    }

    private static List<Object> selectionToList(RepresentativeSelection selection) {
        List<Object> out = new ArrayList<Object>();
        for (Map.Entry<String, RepresentativeSeries> entry : selection.asMap().entrySet()) {
            RepresentativeSeries series = entry.getValue();
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("condition", entry.getKey());
            row.put("role", "representative");
            row.put("seriesId", series.id());
            row.put("seriesIndex", Integer.valueOf(series.seriesIndex()));
            row.put("seriesNumber", Integer.valueOf(series.seriesNumber()));
            row.put("seriesName", series.seriesName());
            row.put("animal", series.animal());
            row.put("hemisphere", series.hemisphere());
            row.put("region", series.region());
            row.put("sourcePath", series.sourcePath() == null
                    ? "" : series.sourcePath().getAbsolutePath());
            row.put("channels", thumbnailsToList(series.channelThumbnails()));
            out.add(row);
        }
        return out;
    }

    private static List<Object> thumbnailsToList(
            List<RepresentativeSeries.ChannelThumbnail> thumbnails) {
        List<Object> out = new ArrayList<Object>();
        if (thumbnails == null) {
            return out;
        }
        for (RepresentativeSeries.ChannelThumbnail thumbnail : thumbnails) {
            if (thumbnail == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("channelIndex", Integer.valueOf(thumbnail.channelIndex()));
            row.put("channelName", thumbnail.channelName());
            row.put("cachePath", thumbnail.cacheFile() == null
                    ? "" : thumbnail.cacheFile().getAbsolutePath());
            out.add(row);
        }
        return out;
    }

    private static RepresentativeSelection selectionFrom(Map<String, Object> values) {
        List<String> conditionNames = strings(values.get("conditionNames"));
        List<Object> rows = asList(values.get("lockedSeries"));
        LinkedHashMap<String, RepresentativeSeries> selected =
                new LinkedHashMap<String, RepresentativeSeries>();
        for (Object rowValue : rows) {
            Map<String, Object> row = asObject(rowValue);
            if (row.isEmpty()) {
                continue;
            }
            String condition = RepresentativeSelection.conditionLabel(string(row.get("condition")));
            if (!conditionNames.contains(condition)) {
                conditionNames.add(condition);
            }
            RepresentativeSeries series = seriesFromMap(row, condition);
            if (series != null) {
                selected.put(condition, series);
            }
        }
        if (conditionNames.isEmpty() || selected.isEmpty()) {
            return null;
        }
        try {
            return new RepresentativeSelection(conditionNames, selected);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static RepresentativeSeries seriesFromMap(Map<String, Object> row,
                                                      String condition) {
        int seriesIndex = intValue(row.get("seriesIndex"), -1);
        int seriesNumber = intValue(row.get("seriesNumber"), seriesIndex + 1);
        String id = string(row.get("seriesId"));
        if (id.isEmpty()) {
            id = seriesIndex >= 0
                    ? RepresentativeStatTable.seriesIdForIndex(seriesIndex)
                    : condition;
        }
        String sourcePath = string(row.get("sourcePath"));
        return new RepresentativeSeries(
                id,
                seriesIndex,
                seriesNumber,
                string(row.get("seriesName")),
                string(row.get("animal")),
                condition,
                string(row.get("hemisphere")),
                string(row.get("region")),
                sourcePath.isEmpty() ? null : new File(sourcePath),
                thumbnailsFromList(asList(row.get("channels"))),
                null,
                null,
                RepresentativeSeries.PreviewSource.GENERATED,
                false);
    }

    private static List<RepresentativeSeries.ChannelThumbnail> thumbnailsFromList(
            List<Object> rows) {
        List<RepresentativeSeries.ChannelThumbnail> out =
                new ArrayList<RepresentativeSeries.ChannelThumbnail>();
        for (Object value : rows) {
            Map<String, Object> row = asObject(value);
            if (row.isEmpty()) {
                continue;
            }
            String cachePath = string(row.get("cachePath"));
            out.add(new RepresentativeSeries.ChannelThumbnail(
                    intValue(row.get("channelIndex"), out.size()),
                    string(row.get("channelName")),
                    null,
                    cachePath.isEmpty() ? null : new File(cachePath)));
        }
        return out;
    }

    private List<Object> displayRangesToList() {
        List<Object> out = new ArrayList<Object>();
        for (Map.Entry<Integer, String> entry : customDisplayRangesByChannel.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("channelIndex", entry.getKey());
            row.put("range", entry.getValue());
            out.add(row);
        }
        return out;
    }

    private static void displayRangesFrom(Map<String, Object> values,
                                          Map<Integer, String> target) {
        target.clear();
        for (Object value : asList(values.get("customDisplayRanges"))) {
            Map<String, Object> row = asObject(value);
            if (row.isEmpty()) {
                continue;
            }
            int channelIndex = intValue(row.get("channelIndex"), -1);
            String range = string(row.get("range"));
            if (channelIndex >= 0 && !range.isEmpty()) {
                target.put(Integer.valueOf(channelIndex), range);
            }
        }

        Map<String, Object> legacy = asObject(values.get("customDisplayRangesByChannel"));
        for (Map.Entry<String, Object> entry : legacy.entrySet()) {
            int channelIndex = intValue(entry.getKey(), -1);
            String range = string(entry.getValue());
            if (channelIndex >= 0 && !range.isEmpty()) {
                target.put(Integer.valueOf(channelIndex), range);
            }
        }
    }

    private static Map<String, Object> layoutToMap(RepresentativeLayout layout) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("axisAssignment", "condition_rows");
        out.put("rowCount", Integer.valueOf(layout.rowCount()));
        out.put("columnCount", Integer.valueOf(layout.maxColumnCount()));
        out.put("rows", rowsToJson(layout.rows()));
        return out;
    }

    private static List<Object> rowsToJson(List<List<String>> rows) {
        List<Object> out = new ArrayList<Object>();
        if (rows == null) {
            return out;
        }
        for (List<String> row : rows) {
            out.add(new ArrayList<String>(row == null
                    ? Collections.<String>emptyList()
                    : row));
        }
        return out;
    }

    private static RepresentativeLayout layoutFromMap(Map<String, Object> map) {
        List<List<String>> rows = new ArrayList<List<String>>();
        for (Object rowValue : asList(map.get("rows"))) {
            rows.add(strings(rowValue));
        }
        if (rows.isEmpty()) {
            return null;
        }
        try {
            return new RepresentativeLayout(rows);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Map<String, Object> tileConfigToMap(PresentationTileConfig config) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("createOverviewTile", Boolean.valueOf(config.createOverviewTile()));
        out.put("annotateOverviewTile", Boolean.valueOf(config.annotateOverviewTile()));
        out.put("annotateIndividualImages", Boolean.valueOf(config.annotateIndividualImages()));
        out.put("groupRowsBy", config.groupRowsBy().name());
        out.put("channelOrder", new ArrayList<String>(config.channelOrder()));
        out.put("cellSizePx", Integer.valueOf(config.cellSizePx()));
        out.put("scaleBarEnabled", Boolean.valueOf(config.scaleBarEnabled()));
        out.put("scaleBarLengthUm", Double.valueOf(config.scaleBarLengthUm()));
        out.put("scaleBarThicknessPx", Integer.valueOf(config.scaleBarThicknessPx()));
        out.put("scaleBarPosition", config.scaleBarPosition().name());
        out.put("annotationColorRgb", Integer.valueOf(config.annotationColor().getRGB()));
        out.put("labelMode", config.labelMode().name());
        out.put("customLabelTemplate", config.customLabelTemplate());
        out.put("labelFontSizePx", Integer.valueOf(config.labelFontSizePx()));
        out.put("labelPosition", config.labelPosition().name());
        return out;
    }

    private static PresentationTileConfig tileConfigFromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        return PresentationTileConfig.builder()
                .createOverviewTile(booleanValue(map.get("createOverviewTile"), true))
                .annotateOverviewTile(booleanValue(map.get("annotateOverviewTile"), true))
                .annotateIndividualImages(booleanValue(map.get("annotateIndividualImages"), false))
                .groupRowsBy(enumValue(PresentationTileConfig.GroupRowsBy.class,
                        map.get("groupRowsBy"), PresentationTileConfig.GroupRowsBy.CONDITION))
                .channelOrder(strings(map.get("channelOrder")))
                .cellSizePx(intValue(map.get("cellSizePx"), 260))
                .scaleBarEnabled(booleanValue(map.get("scaleBarEnabled"), true))
                .scaleBarLengthUm(doubleValue(map.get("scaleBarLengthUm"), 100.0))
                .scaleBarThicknessPx(intValue(map.get("scaleBarThicknessPx"), 6))
                .scaleBarPosition(enumValue(PresentationTileConfig.Position.class,
                        map.get("scaleBarPosition"), PresentationTileConfig.Position.BOTTOM_RIGHT))
                .annotationColor(colorValue(map.get("annotationColorRgb"), Color.WHITE))
                .labelMode(enumValue(PresentationTileConfig.LabelMode.class,
                        map.get("labelMode"), PresentationTileConfig.LabelMode.STAIN_NAME))
                .customLabelTemplate(string(map.get("customLabelTemplate")))
                .labelFontSizePx(intValue(map.get("labelFontSizePx"), 18))
                .labelPosition(enumValue(PresentationTileConfig.Position.class,
                        map.get("labelPosition"), PresentationTileConfig.Position.TOP_LEFT))
                .build();
    }

    private static Object firstNonNull(Object first, Object second) {
        return first == null ? second : first;
    }

    private static Map<String, Object> asObject(Object value) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (!(value instanceof Map<?, ?>)) {
            return out;
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private static List<Object> asList(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        if (value instanceof List<?>) {
            List<Object> out = new ArrayList<Object>();
            out.addAll((List<?>) value);
            return out;
        }
        if (value.getClass().isArray()) {
            int n = java.lang.reflect.Array.getLength(value);
            List<Object> out = new ArrayList<Object>();
            for (int i = 0; i < n; i++) {
                out.add(java.lang.reflect.Array.get(value, i));
            }
            return out;
        }
        return Collections.singletonList(value);
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<String>();
        for (Object item : asList(value)) {
            String text = string(item);
            if (!text.isEmpty()) {
                out.add(text);
            }
        }
        return out;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(string(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double doubleValue(Object value, double fallback) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(string(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        String text = string(value);
        if ("true".equalsIgnoreCase(text)) return true;
        if ("false".equalsIgnoreCase(text)) return false;
        return fallback;
    }

    private static Color colorValue(Object value, Color fallback) {
        if (value instanceof Number) {
            return new Color(((Number) value).intValue(), true);
        }
        String text = string(value);
        if (text.isEmpty()) {
            return fallback;
        }
        try {
            return new Color(Integer.parseInt(text), true);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, Object value, T fallback) {
        String text = string(value);
        if (text.isEmpty()) {
            return fallback;
        }
        for (T candidate : type.getEnumConstants()) {
            if (candidate.name().equalsIgnoreCase(text)) {
                return candidate;
            }
        }
        return fallback;
    }
}
