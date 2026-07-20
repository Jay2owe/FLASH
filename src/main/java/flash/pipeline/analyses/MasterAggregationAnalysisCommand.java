package flash.pipeline.analyses;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.analyses.wizard.AggregationConfig;
import flash.pipeline.analyses.wizard.AggregationPreset;
import flash.pipeline.analyses.wizard.AggregationPresetIO;
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
        name = "flash.masterAggregationAnalysis",
        label = "FLASH Master Data Aggregation")
public final class MasterAggregationAnalysisCommand implements Command {

    @Parameter(required = false)
    private AnalysisRunCoordinator coordinator;

    @Parameter(label = "Project directory", style = "directory")
    private File directory;

    @Parameter(required = false)
    private String presetJson;

    @Parameter(required = false)
    private String presetName;

    @Parameter(required = false)
    private String granularity;

    @Parameter(required = false)
    private String outputMode;

    @Parameter(required = false)
    private Boolean headless;

    @Parameter(required = false)
    private Boolean verbose;

    @Parameter(required = false)
    private Boolean skipExisting;

    @Parameter(required = false)
    private Integer parallelThreads;

    @Parameter(required = false)
    private Integer loaderThreads;

    @Parameter(required = false)
    private Integer loaderPercent;

    @Parameter(required = false)
    private Boolean useTifCache;

    @Parameter(required = false)
    private String parentRunId;

    @Override
    public void run() {
        final File projectDir = requireDirectory(directory);
        final AggregationPreset preset = resolvePreset(projectDir);
        final AggregationConfig config = resolveConfig(preset);

        final MasterAggregationAnalysis analysis = new MasterAggregationAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setAggregationConfig(config);
        applyCommonOptions(analysis);

        coordinator().run(analysis, FLASH_Pipeline.IDX_AGGREGATION,
                "Combine results per condition / animal", projectDir.getAbsolutePath(),
                null, commandParameters(preset, config), empty(parentRunId),
                new Callable<Void>() {
                    @Override public Void call() {
                        analysis.execute(projectDir.getAbsolutePath());
                        return null;
                    }
                });
    }

    private AggregationPreset resolvePreset(File projectDir) {
        if (hasText(presetJson)) {
            try {
                return AggregationPreset.fromJson(presetJson);
            } catch (IOException e) {
                throw new IllegalArgumentException("Invalid presetJson for Master Data Aggregation: "
                        + e.getMessage(), e);
            }
        }
        if (hasText(presetName)) {
            try {
                return new AggregationPresetIO(projectDir).load(presetName.trim());
            } catch (IOException e) {
                throw new IllegalArgumentException("Could not load AggregationPreset '"
                        + presetName + "': " + e.getMessage(), e);
            }
        }
        return null;
    }

    private AggregationConfig resolveConfig(AggregationPreset preset) {
        AggregationConfig config = new AggregationConfig();
        if (preset != null) {
            config.applyPreset(preset);
        }
        if (hasText(granularity)) {
            config.setGranularity(AggregationConfig.Granularity.parse(
                    granularity, config.getGranularity()));
        }
        if (hasText(outputMode)) {
            config.setOutputMode(AggregationConfig.OutputMode.parse(
                    outputMode, config.getOutputMode()));
        }
        return config;
    }

    private AnalysisRunCoordinator coordinator() {
        return coordinator == null ? new AnalysisRunCoordinator() : coordinator;
    }

    private void applyCommonOptions(Analysis analysis) {
        if (verbose != null) analysis.setVerboseLogging(verbose.booleanValue());
        if (skipExisting != null) analysis.setSkipExisting(skipExisting.booleanValue());
        if (parallelThreads != null) analysis.setParallelThreads(parallelThreads.intValue());
        if (loaderThreads != null) analysis.setLoaderThreads(loaderThreads.intValue());
        if (loaderPercent != null) analysis.setLoaderPercent(loaderPercent.intValue());
        if (useTifCache != null) analysis.setUseTifCache(useTifCache.booleanValue());
    }

    private Map<String, Object> commandParameters(AggregationPreset preset,
                                                  AggregationConfig config) {
        Map<String, Object> common = new LinkedHashMap<String, Object>();
        common.put("headless", Boolean.TRUE);
        put(common, "preset_name", presetName);
        put(common, "granularity", granularity);
        put(common, "output_mode", outputMode);
        put(common, "verbose", verbose);
        put(common, "skip_existing", skipExisting);
        put(common, "threads", parallelThreads);
        put(common, "loader_threads", loaderThreads);
        put(common, "loader_percent", loaderPercent);
        put(common, "use_tif_cache", useTifCache);
        if (config != null) {
            common.put("effective_granularity", config.getGranularity().token());
            common.put("effective_output_mode", config.getOutputMode().token());
        }
        if (preset != null) {
            return ParameterSnapshot.merged(common,
                    ParameterSnapshot.fromAnalysisPresetMap(
                            "MasterAggregationAnalysis", preset.toJsonObject()));
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
