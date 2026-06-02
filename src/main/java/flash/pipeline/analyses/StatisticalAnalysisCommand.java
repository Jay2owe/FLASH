package flash.pipeline.analyses;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.analyses.wizard.StatisticsPreset;
import flash.pipeline.analyses.wizard.StatisticsPresetIO;
import flash.pipeline.execution.AnalysisRunCoordinator;
import flash.pipeline.runrecord.ParameterSnapshot;

import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Plugin(type = Command.class, headless = true, visible = false,
        name = "flash.statisticalAnalysis",
        label = "FLASH Statistical Analysis")
public final class StatisticalAnalysisCommand implements Command {

    @Parameter(required = false)
    private AnalysisRunCoordinator coordinator;

    @Parameter(label = "Project directory", style = "directory")
    private File directory;

    @Parameter(required = false)
    private String presetJson;

    @Parameter(required = false)
    private String presetName;

    @Parameter(required = false)
    private String pairedMode;

    @Parameter(required = false)
    private String distributionMode;

    @Parameter(required = false)
    private String postHocMethod;

    @Parameter(required = false)
    private String metricFilter;

    @Parameter(required = false)
    private String metricAggregation;

    @Parameter(required = false)
    private String sumMetrics;

    @Parameter(required = false)
    private String meanMetrics;

    @Parameter(required = false)
    private Boolean headless;

    @Parameter(required = false)
    private Boolean verbose;

    @Parameter(required = false)
    private String parentRunId;

    @Override
    public void run() {
        final File projectDir = requireDirectory(directory);
        final StatisticsPreset preset = resolvePreset(projectDir);
        final StatisticsConfig config = resolveConfig(preset);

        final StatisticalAnalysis analysis = new StatisticalAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setStatisticsConfig(config);
        if (verbose != null) {
            analysis.setVerboseLogging(verbose.booleanValue());
        }

        coordinator().run(analysis, FLASH_Pipeline.IDX_STATISTICS,
                "Statistical Analysis", projectDir.getAbsolutePath(),
                null, commandParameters(preset, config), empty(parentRunId),
                new Callable<Void>() {
                    @Override public Void call() {
                        analysis.execute(projectDir.getAbsolutePath());
                        return null;
                    }
                });
    }

    private StatisticsPreset resolvePreset(File projectDir) {
        if (hasText(presetJson)) {
            try {
                return StatisticsPreset.fromJson(presetJson);
            } catch (IOException e) {
                throw new IllegalArgumentException("Invalid presetJson for Statistical Analysis: "
                        + e.getMessage(), e);
            }
        }
        if (hasText(presetName)) {
            try {
                return new StatisticsPresetIO(projectDir).load(presetName.trim());
            } catch (IOException e) {
                throw new IllegalArgumentException("Could not load StatisticsPreset '"
                        + presetName + "': " + e.getMessage(), e);
            }
        }
        return null;
    }

    private StatisticsConfig resolveConfig(StatisticsPreset preset) {
        StatisticsConfig config = preset == null ? new StatisticsConfig() : preset.toConfig();
        if (hasText(pairedMode)) {
            config.pairedMode = StatisticsConfig.PairedMode.parse(
                    pairedMode, config.pairedMode);
        }
        if (hasText(distributionMode)) {
            config.distributionMode = StatisticsConfig.DistributionMode.parse(
                    distributionMode, config.distributionMode);
        }
        if (hasText(postHocMethod)) {
            config.postHocMethod = StatisticsConfig.PostHocMethod.parse(
                    postHocMethod, config.postHocMethod);
        }
        if (hasText(metricFilter)) {
            config.metricFilter = splitMetricFilter(metricFilter);
        }
        if (hasText(metricAggregation)) {
            parseMetricAggregationPairs(metricAggregation, config);
        }
        if (hasText(sumMetrics)) {
            parseMetricAggregationList(sumMetrics,
                    StatisticsConfig.MetricAggregation.SUM, config);
        }
        if (hasText(meanMetrics)) {
            parseMetricAggregationList(meanMetrics,
                    StatisticsConfig.MetricAggregation.MEAN, config);
        }
        return config;
    }

    private AnalysisRunCoordinator coordinator() {
        return coordinator == null ? new AnalysisRunCoordinator() : coordinator;
    }

    private Map<String, Object> commandParameters(StatisticsPreset preset,
                                                  StatisticsConfig config) {
        Map<String, Object> common = new LinkedHashMap<String, Object>();
        common.put("headless", Boolean.TRUE);
        put(common, "preset_name", presetName);
        put(common, "paired_mode", pairedMode);
        put(common, "distribution_mode", distributionMode);
        put(common, "post_hoc_method", postHocMethod);
        put(common, "metric_filter", metricFilter);
        put(common, "metric_aggregation", metricAggregation);
        put(common, "sum_metrics", sumMetrics);
        put(common, "mean_metrics", meanMetrics);
        put(common, "verbose", verbose);
        if (config != null) {
            common.put("effective_paired_mode", config.pairedMode.name());
            common.put("effective_distribution_mode", config.distributionMode.name());
            common.put("effective_post_hoc_method", config.postHocMethod.name());
            if (config.metricFilter != null) {
                common.put("effective_metric_filter", new ArrayList<String>(config.metricFilter));
            }
            if (config.metricAggregationOverrides != null
                    && !config.metricAggregationOverrides.isEmpty()) {
                common.put("effective_metric_aggregation",
                        new LinkedHashMap<String, StatisticsConfig.MetricAggregation>(
                                config.metricAggregationOverrides));
            }
        }
        if (preset != null) {
            return ParameterSnapshot.merged(common,
                    ParameterSnapshot.fromAnalysisPresetMap(
                            "StatisticalAnalysis", preset.toJsonObject()));
        }
        return common;
    }

    private static List<String> splitMetricFilter(String value) {
        List<String> out = new ArrayList<String>();
        if (value == null) return out;
        String[] parts = value.split(",");
        for (String part : parts) {
            if (part == null) continue;
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    private static void parseMetricAggregationPairs(String value, StatisticsConfig config) {
        if (value == null || config == null) return;
        String[] parts = value.split(",");
        for (String part : parts) {
            if (part == null) continue;
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            int sep = trimmed.lastIndexOf(':');
            if (sep < 0) sep = trimmed.lastIndexOf('=');
            if (sep <= 0 || sep + 1 >= trimmed.length()) continue;
            config.putMetricAggregationOverride(trimmed.substring(0, sep),
                    StatisticsConfig.MetricAggregation.parse(trimmed.substring(sep + 1),
                            StatisticsConfig.MetricAggregation.AUTO));
        }
    }

    private static void parseMetricAggregationList(String value,
                                                   StatisticsConfig.MetricAggregation aggregation,
                                                   StatisticsConfig config) {
        if (value == null || aggregation == null || config == null) return;
        String[] parts = value.split(",");
        for (String part : parts) {
            if (part == null) continue;
            config.putMetricAggregationOverride(part, aggregation);
        }
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
