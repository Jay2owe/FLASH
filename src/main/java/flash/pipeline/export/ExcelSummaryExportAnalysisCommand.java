package flash.pipeline.export;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.execution.AnalysisRunCoordinator;
import flash.pipeline.runrecord.ParameterSnapshot;

import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Plugin(type = Command.class, headless = true, visible = false,
        name = "flash.excelSummaryExportAnalysis",
        label = "FLASH Excel Summary Export")
public final class ExcelSummaryExportAnalysisCommand implements Command {

    @Parameter(required = false)
    private AnalysisRunCoordinator coordinator;

    @Parameter(label = "Project directory", style = "directory")
    private File directory;

    @Parameter(required = false)
    private String presetJson;

    @Parameter(required = false)
    private String presetName;

    @Parameter(required = false)
    private Boolean includeExperimentalConditionsSheet;

    @Parameter(required = false)
    private Boolean includeDataSummarySheet;

    @Parameter(required = false)
    private Boolean includePerMetricSheets;

    @Parameter(required = false)
    private Boolean includeStatisticsSheet;

    @Parameter(required = false)
    private String metricSheetDetail;

    @Parameter(required = false)
    private Boolean includeMethodsAppendix;

    @Parameter(required = false)
    private String significanceHighlight;

    @Parameter(required = false)
    private String headerStyle;

    @Parameter(required = false)
    private Boolean significanceStars;

    @Parameter(required = false)
    private Boolean includeTextureFeatures;

    @Parameter(required = false)
    private Boolean headless;

    @Parameter(required = false)
    private String parentRunId;

    @Override
    public void run() {
        final File projectDir = requireDirectory(directory);
        final ExcelExportPreset basePreset = resolvePreset(projectDir);
        final ExcelExportPreset effectivePreset = applyOverrides(basePreset);

        final ExcelSummaryExportAnalysis analysis = new ExcelSummaryExportAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setPreset(effectivePreset);

        coordinator().run(analysis, FLASH_Pipeline.IDX_EXCEL_EXPORT,
                "Excel Summary Export", projectDir.getAbsolutePath(),
                null, commandParameters(basePreset, effectivePreset),
                empty(parentRunId), new Callable<Void>() {
                    @Override public Void call() {
                        analysis.execute(projectDir.getAbsolutePath());
                        return null;
                    }
                });
    }

    private ExcelExportPreset resolvePreset(File projectDir) {
        if (hasText(presetJson)) {
            try {
                return ExcelExportPreset.fromJson(presetJson);
            } catch (IOException e) {
                throw new IllegalArgumentException("Invalid presetJson for Excel Summary Export: "
                        + e.getMessage(), e);
            }
        }
        if (hasText(presetName)) {
            try {
                return new ExcelExportPresetIO(projectDir).load(presetName.trim());
            } catch (IOException e) {
                throw new IllegalArgumentException("Could not load ExcelExportPreset '"
                        + presetName + "': " + e.getMessage(), e);
            }
        }
        return ExcelExportPreset.exploratoryDefault();
    }

    private ExcelExportPreset applyOverrides(ExcelExportPreset preset) {
        ExcelExportPreset out = preset == null
                ? ExcelExportPreset.exploratoryDefault() : preset;
        if (includeExperimentalConditionsSheet != null) {
            out = out.withField("conditions_sheet",
                    Boolean.toString(includeExperimentalConditionsSheet.booleanValue()));
        }
        if (includeDataSummarySheet != null) {
            out = out.withField("data_summary_sheet",
                    Boolean.toString(includeDataSummarySheet.booleanValue()));
        }
        if (includePerMetricSheets != null) {
            out = out.withField("per_metric_sheets",
                    Boolean.toString(includePerMetricSheets.booleanValue()));
        }
        if (includeStatisticsSheet != null) {
            out = out.withField("statistics_sheet",
                    Boolean.toString(includeStatisticsSheet.booleanValue()));
        }
        if (hasText(metricSheetDetail)) {
            out = out.withField("metric_sheet_detail", metricSheetDetail);
        }
        if (includeMethodsAppendix != null) {
            out = out.withField("methods_appendix",
                    Boolean.toString(includeMethodsAppendix.booleanValue()));
        }
        if (hasText(significanceHighlight)) {
            out = out.withField("significance_highlight", significanceHighlight);
        }
        if (hasText(headerStyle)) {
            out = out.withField("header_style", headerStyle);
        }
        if (significanceStars != null) {
            out = out.withField("significance_stars",
                    Boolean.toString(significanceStars.booleanValue()));
        }
        if (includeTextureFeatures != null) {
            out = out.withField("texture_features",
                    Boolean.toString(includeTextureFeatures.booleanValue()));
        }
        return out;
    }

    private AnalysisRunCoordinator coordinator() {
        return coordinator == null ? new AnalysisRunCoordinator() : coordinator;
    }

    private Map<String, Object> commandParameters(ExcelExportPreset basePreset,
                                                  ExcelExportPreset effectivePreset) {
        Map<String, Object> common = new LinkedHashMap<String, Object>();
        common.put("headless", Boolean.TRUE);
        put(common, "preset_name", presetName);
        put(common, "include_experimental_conditions_sheet", includeExperimentalConditionsSheet);
        put(common, "include_data_summary_sheet", includeDataSummarySheet);
        put(common, "include_per_metric_sheets", includePerMetricSheets);
        put(common, "include_statistics_sheet", includeStatisticsSheet);
        put(common, "metric_sheet_detail", metricSheetDetail);
        put(common, "include_methods_appendix", includeMethodsAppendix);
        put(common, "significance_highlight", significanceHighlight);
        put(common, "header_style", headerStyle);
        put(common, "significance_stars", significanceStars);
        put(common, "include_texture_features", includeTextureFeatures);
        if (effectivePreset != null) {
            common.put("effective_preset_name", effectivePreset.getName());
        }
        if (basePreset != null) {
            return ParameterSnapshot.merged(common,
                    ParameterSnapshot.fromAnalysisPresetMap(
                            "ExcelSummaryExportAnalysis", basePreset.toJsonObject()));
        }
        return common;
    }

    private static File requireDirectory(File dir) {
        if (dir == null) {
            throw new IllegalArgumentException("Project directory is required.");
        }
        return dir;
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String empty(String value) {
        return value == null ? "" : value;
    }
}
