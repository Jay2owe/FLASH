package flash.pipeline.export;

import flash.pipeline.ui.wizard.JsonIO;
import flash.pipeline.ui.wizard.Preset;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Persisted configuration for {@link ExcelSummaryExportAnalysis}.
 * <p>
 * Mirrors {@link flash.pipeline.analyses.wizard.AggregationPreset} in
 * shape. Excel export has no wizard; presets are the only configuration path
 * exposed on the main panel.
 */
public final class ExcelExportPreset implements Preset<ExcelExportPreset> {

    public static final String CURRENT_LIBRARY_VERSION = "1";

    /** How per-metric sheets render their values. */
    public enum MetricSheetDetail {
        RAW_VALUES,
        SUMMARY_STATISTICS,
        BOTH;

        public String token() {
            if (this == SUMMARY_STATISTICS) return "summary_statistics";
            if (this == BOTH) return "both";
            return "raw_values";
        }

        public static MetricSheetDetail parse(String value, MetricSheetDetail fallback) {
            if (value == null) return fallback;
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) return fallback;
            if ("raw_values".equals(normalized) || "raw".equals(normalized)
                    || "values".equals(normalized)) {
                return RAW_VALUES;
            }
            if ("summary_statistics".equals(normalized) || "summary".equals(normalized)
                    || "stats".equals(normalized)) {
                return SUMMARY_STATISTICS;
            }
            if ("both".equals(normalized) || "summary_and_raw".equals(normalized)
                    || "summary+raw".equals(normalized)) {
                return BOTH;
            }
            return fallback;
        }
    }

    /** How the statistics sheet highlights significant rows. */
    public enum SignificanceHighlight {
        OFF,
        YELLOW,
        P_GRADIENT;

        public String token() {
            if (this == OFF) return "off";
            if (this == P_GRADIENT) return "p_gradient";
            return "yellow";
        }

        public static SignificanceHighlight parse(String value, SignificanceHighlight fallback) {
            if (value == null) return fallback;
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) return fallback;
            if ("off".equals(normalized) || "none".equals(normalized) || "false".equals(normalized)) {
                return OFF;
            }
            if ("yellow".equals(normalized) || "on".equals(normalized) || "true".equals(normalized)) {
                return YELLOW;
            }
            if ("p_gradient".equals(normalized) || "gradient".equals(normalized)
                    || "heatmap".equals(normalized)) {
                return P_GRADIENT;
            }
            return fallback;
        }
    }

    /** Header styling used across all sheets. */
    public enum HeaderStyle {
        STANDARD,
        FIGURE_READY;

        public String token() {
            return this == FIGURE_READY ? "figure_ready" : "standard";
        }

        public static HeaderStyle parse(String value, HeaderStyle fallback) {
            if (value == null) return fallback;
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) return fallback;
            if ("figure_ready".equals(normalized) || "figure".equals(normalized)
                    || "manuscript".equals(normalized)) {
                return FIGURE_READY;
            }
            if ("standard".equals(normalized) || "default".equals(normalized)) {
                return STANDARD;
            }
            return fallback;
        }
    }

    private final String name;
    private final String description;
    private final String libraryVersion;
    private final boolean includeExperimentalConditionsSheet;
    private final boolean includeDataSummarySheet;
    private final boolean includePerMetricSheets;
    private final boolean includeStatisticsSheet;
    private final MetricSheetDetail metricSheetDetail;
    private final boolean includeMethodsAppendix;
    private final SignificanceHighlight significanceHighlight;
    private final HeaderStyle headerStyle;
    private final boolean significanceStars;

    public ExcelExportPreset(String name,
                             String description,
                             boolean includeExperimentalConditionsSheet,
                             boolean includeDataSummarySheet,
                             boolean includePerMetricSheets,
                             boolean includeStatisticsSheet,
                             MetricSheetDetail metricSheetDetail,
                             boolean includeMethodsAppendix,
                             SignificanceHighlight significanceHighlight,
                             HeaderStyle headerStyle,
                             boolean significanceStars) {
        this.name = requireText("name", name);
        this.description = emptyToNull(description);
        this.libraryVersion = CURRENT_LIBRARY_VERSION;
        this.includeExperimentalConditionsSheet = includeExperimentalConditionsSheet;
        this.includeDataSummarySheet = includeDataSummarySheet;
        this.includePerMetricSheets = includePerMetricSheets;
        this.includeStatisticsSheet = includeStatisticsSheet;
        this.metricSheetDetail = metricSheetDetail == null
                ? MetricSheetDetail.RAW_VALUES : metricSheetDetail;
        this.includeMethodsAppendix = includeMethodsAppendix;
        this.significanceHighlight = significanceHighlight == null
                ? SignificanceHighlight.YELLOW : significanceHighlight;
        this.headerStyle = headerStyle == null ? HeaderStyle.STANDARD : headerStyle;
        this.significanceStars = significanceStars;
    }

    /** Stock exploratory default matching the original hard-coded behavior. */
    public static ExcelExportPreset exploratoryDefault() {
        return new ExcelExportPreset(
                "Exploratory (default - all sheets, raw values)",
                "All sheets; per-metric sheets show raw values; yellow highlight for p < 0.05.",
                true, true, true, true,
                MetricSheetDetail.RAW_VALUES,
                false,
                SignificanceHighlight.YELLOW,
                HeaderStyle.STANDARD,
                false);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public String getLibraryVersion() {
        return libraryVersion;
    }

    public boolean isIncludeExperimentalConditionsSheet() {
        return includeExperimentalConditionsSheet;
    }

    public boolean isIncludeDataSummarySheet() {
        return includeDataSummarySheet;
    }

    public boolean isIncludePerMetricSheets() {
        return includePerMetricSheets;
    }

    public boolean isIncludeStatisticsSheet() {
        return includeStatisticsSheet;
    }

    public MetricSheetDetail getMetricSheetDetail() {
        return metricSheetDetail;
    }

    public boolean isIncludeMethodsAppendix() {
        return includeMethodsAppendix;
    }

    public SignificanceHighlight getSignificanceHighlight() {
        return significanceHighlight;
    }

    public HeaderStyle getHeaderStyle() {
        return headerStyle;
    }

    public boolean isSignificanceStars() {
        return significanceStars;
    }

    @Override
    public ExcelExportPreset getPayload() {
        return this;
    }

    /**
     * Returns a new preset with one field replaced. Used by CLI overrides.
     */
    public ExcelExportPreset withField(String field, String value) {
        if (field == null || value == null) return this;
        String key = field.trim().toLowerCase(Locale.ROOT);
        boolean b;
        switch (key) {
            case "conditions_sheet":
            case "experimental_conditions_sheet":
                b = JsonIO.booleanValue(value, includeExperimentalConditionsSheet);
                return copyWith(b, includeDataSummarySheet, includePerMetricSheets,
                        includeStatisticsSheet, metricSheetDetail, includeMethodsAppendix,
                        significanceHighlight, headerStyle, significanceStars);
            case "data_summary_sheet":
                b = JsonIO.booleanValue(value, includeDataSummarySheet);
                return copyWith(includeExperimentalConditionsSheet, b, includePerMetricSheets,
                        includeStatisticsSheet, metricSheetDetail, includeMethodsAppendix,
                        significanceHighlight, headerStyle, significanceStars);
            case "per_metric_sheets":
            case "metric_sheets":
                b = JsonIO.booleanValue(value, includePerMetricSheets);
                return copyWith(includeExperimentalConditionsSheet, includeDataSummarySheet, b,
                        includeStatisticsSheet, metricSheetDetail, includeMethodsAppendix,
                        significanceHighlight, headerStyle, significanceStars);
            case "stats_sheet":
            case "statistics_sheet":
                b = JsonIO.booleanValue(value, includeStatisticsSheet);
                return copyWith(includeExperimentalConditionsSheet, includeDataSummarySheet,
                        includePerMetricSheets, b, metricSheetDetail, includeMethodsAppendix,
                        significanceHighlight, headerStyle, significanceStars);
            case "metric_detail":
            case "metric_sheet_detail":
                return copyWith(includeExperimentalConditionsSheet, includeDataSummarySheet,
                        includePerMetricSheets, includeStatisticsSheet,
                        MetricSheetDetail.parse(value, metricSheetDetail),
                        includeMethodsAppendix, significanceHighlight, headerStyle,
                        significanceStars);
            case "methods_appendix":
            case "methods":
                b = JsonIO.booleanValue(value, includeMethodsAppendix);
                return copyWith(includeExperimentalConditionsSheet, includeDataSummarySheet,
                        includePerMetricSheets, includeStatisticsSheet, metricSheetDetail,
                        b, significanceHighlight, headerStyle, significanceStars);
            case "significance_highlight":
            case "highlight":
                return copyWith(includeExperimentalConditionsSheet, includeDataSummarySheet,
                        includePerMetricSheets, includeStatisticsSheet, metricSheetDetail,
                        includeMethodsAppendix,
                        SignificanceHighlight.parse(value, significanceHighlight),
                        headerStyle, significanceStars);
            case "header_style":
                return copyWith(includeExperimentalConditionsSheet, includeDataSummarySheet,
                        includePerMetricSheets, includeStatisticsSheet, metricSheetDetail,
                        includeMethodsAppendix, significanceHighlight,
                        HeaderStyle.parse(value, headerStyle), significanceStars);
            case "significance_stars":
            case "stars":
                b = JsonIO.booleanValue(value, significanceStars);
                return copyWith(includeExperimentalConditionsSheet, includeDataSummarySheet,
                        includePerMetricSheets, includeStatisticsSheet, metricSheetDetail,
                        includeMethodsAppendix, significanceHighlight, headerStyle, b);
            default:
                return this;
        }
    }

    private ExcelExportPreset copyWith(boolean conditionsSheet,
                                       boolean dataSummarySheet,
                                       boolean perMetricSheets,
                                       boolean statisticsSheet,
                                       MetricSheetDetail detail,
                                       boolean methodsAppendix,
                                       SignificanceHighlight highlight,
                                       HeaderStyle style,
                                       boolean stars) {
        return new ExcelExportPreset(name, description, conditionsSheet, dataSummarySheet,
                perMetricSheets, statisticsSheet, detail, methodsAppendix, highlight, style, stars);
    }

    @Override
    public Map<String, Object> toJsonObject() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("name", name);
        if (description != null) {
            root.put("description", description);
        }
        root.put("libraryVersion", libraryVersion);
        root.put("includeExperimentalConditionsSheet", Boolean.valueOf(includeExperimentalConditionsSheet));
        root.put("includeDataSummarySheet", Boolean.valueOf(includeDataSummarySheet));
        root.put("includePerMetricSheets", Boolean.valueOf(includePerMetricSheets));
        root.put("includeStatisticsSheet", Boolean.valueOf(includeStatisticsSheet));
        root.put("metricSheetDetail", metricSheetDetail.token());
        root.put("includeMethodsAppendix", Boolean.valueOf(includeMethodsAppendix));
        root.put("significanceHighlight", significanceHighlight.token());
        root.put("headerStyle", headerStyle.token());
        root.put("significanceStars", Boolean.valueOf(significanceStars));
        return root;
    }

    public String toJson() {
        return JsonIO.write(toJsonObject());
    }

    public static ExcelExportPreset fromJson(String json) throws IOException {
        return fromJsonObject(JsonIO.parseObject(json));
    }

    public static ExcelExportPreset fromJsonObject(Map<String, Object> root) throws IOException {
        if (root == null) {
            throw new IOException("Preset JSON object is required.");
        }
        return new ExcelExportPreset(
                stringOr(root.get("name"), "Excel Preset"),
                JsonIO.stringValue(root.get("description")),
                JsonIO.booleanValue(root.get("includeExperimentalConditionsSheet"), true),
                JsonIO.booleanValue(root.get("includeDataSummarySheet"), true),
                JsonIO.booleanValue(root.get("includePerMetricSheets"), true),
                JsonIO.booleanValue(root.get("includeStatisticsSheet"), true),
                MetricSheetDetail.parse(JsonIO.stringValue(root.get("metricSheetDetail")),
                        MetricSheetDetail.RAW_VALUES),
                JsonIO.booleanValue(root.get("includeMethodsAppendix"), false),
                SignificanceHighlight.parse(JsonIO.stringValue(root.get("significanceHighlight")),
                        SignificanceHighlight.YELLOW),
                HeaderStyle.parse(JsonIO.stringValue(root.get("headerStyle")),
                        HeaderStyle.STANDARD),
                JsonIO.booleanValue(root.get("significanceStars"), false));
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
}
