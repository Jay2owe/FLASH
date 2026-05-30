package flash.pipeline.analyses;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.analyses.wizard.BinPreset;
import flash.pipeline.cli.CLIArgumentParser;
import flash.pipeline.cli.CLIConfig;
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
        name = "flash.createBinFileAnalysis",
        label = "FLASH Set Up Configuration")
public final class CreateBinFileAnalysisCommand implements Command {

    @Parameter(required = false)
    private AnalysisRunCoordinator coordinator;

    @Parameter(label = "Project directory", style = "directory")
    private File directory;

    @Parameter(required = false)
    private String presetJson;

    @Parameter(required = false)
    private String binPreset;

    @Parameter(required = false)
    private String channelNames;

    @Parameter(required = false)
    private String channelColors;

    @Parameter(required = false)
    private String objectThresholds;

    @Parameter(required = false)
    private String particleSizes;

    @Parameter(required = false)
    private String displayMinMax;

    @Parameter(required = false)
    private String intensityThresholds;

    @Parameter(required = false)
    private String segmentationMethods;

    @Parameter(required = false)
    private String filterPresets;

    @Parameter(required = false)
    private String zSliceMode;

    @Parameter(required = false)
    private Boolean headless;

    @Parameter(required = false)
    private Boolean parallel;

    @Parameter(required = false)
    private Integer parallelThreads;

    @Parameter(required = false)
    private Integer loaderThreads;

    @Parameter(required = false)
    private Integer loaderPercent;

    @Parameter(required = false)
    private Boolean verbose;

    @Parameter(required = false)
    private Boolean skipExisting;

    @Parameter(required = false)
    private Boolean useTifCache;

    @Parameter(required = false)
    private Integer gpuPermits;

    @Parameter(required = false)
    private String parentRunId;

    @Override
    public void run() {
        final File projectDir = requireDirectory(directory);
        final BinPreset preset = parsePresetJson(presetJson);
        final CLIConfig cliConfig = hasBinConfiguration() ? parseCliConfig(projectDir) : null;

        final CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setCommandPreset(preset);
        analysis.setCliConfig(cliConfig);
        applyCommonOptions(analysis);

        coordinator().run(analysis, FLASH_Pipeline.IDX_CREATE_BIN, "Set Up Configuration",
                projectDir.getAbsolutePath(), cliConfig, commandParameters(preset, cliConfig),
                empty(parentRunId), new Callable<Void>() {
                    @Override public Void call() {
                        analysis.execute(projectDir.getAbsolutePath());
                        return null;
                    }
                });
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

    private boolean hasBinConfiguration() {
        return hasText(binPreset)
                || hasText(channelNames)
                || hasText(channelColors)
                || hasText(objectThresholds)
                || hasText(particleSizes)
                || hasText(displayMinMax)
                || hasText(intensityThresholds)
                || hasText(segmentationMethods)
                || hasText(filterPresets)
                || hasText(zSliceMode);
    }

    private CLIConfig parseCliConfig(File projectDir) {
        StringBuilder options = new StringBuilder();
        appendValue(options, "dir", projectDir.getAbsolutePath());
        appendValue(options, "analysisIndex", String.valueOf(FLASH_Pipeline.IDX_CREATE_BIN));
        appendValue(options, "headless", "true");
        appendValue(options, "no_aggregate", "true");
        appendValue(options, "no_qc", "true");
        if (parallel != null) appendValue(options, "parallel", String.valueOf(parallel.booleanValue()));
        if (parallelThreads != null) appendValue(options, "threads", String.valueOf(parallelThreads.intValue()));
        if (loaderThreads != null) appendValue(options, "loader_threads", String.valueOf(loaderThreads.intValue()));
        if (loaderPercent != null) appendValue(options, "loader_percent", String.valueOf(loaderPercent.intValue()));
        if (gpuPermits != null) appendValue(options, "gpu_permits", String.valueOf(gpuPermits.intValue()));
        if (verbose != null && verbose.booleanValue()) appendFlag(options, "verbose");
        if (useTifCache != null && useTifCache.booleanValue()) appendFlag(options, "tif_cache");
        if (skipExisting != null) appendValue(options, "overwrite", skipExisting.booleanValue() ? "skip" : "auto");
        appendValue(options, "bin.preset", binPreset);
        appendValue(options, "channel_names", channelNames);
        appendValue(options, "channel_colors", channelColors);
        appendValue(options, "object_thresholds", objectThresholds);
        appendValue(options, "particle_sizes", particleSizes);
        appendValue(options, "display_min_max", displayMinMax);
        appendValue(options, "intensity_thresholds", intensityThresholds);
        appendValue(options, "segmentation_methods", segmentationMethods);
        appendValue(options, "filter_presets", filterPresets);
        appendValue(options, "z_slice_mode", zSliceMode);

        CLIConfig parsed = CLIArgumentParser.parse(options.toString());
        if (parsed == null) {
            throw new IllegalArgumentException("Could not parse FLASH Set Up Configuration command parameters.");
        }
        return parsed;
    }

    private Map<String, Object> commandParameters(BinPreset preset, CLIConfig cliConfig) {
        Map<String, Object> common = new LinkedHashMap<String, Object>();
        put(common, "headless", Boolean.TRUE);
        put(common, "parallel", parallel);
        put(common, "threads", parallelThreads);
        put(common, "loader_threads", loaderThreads);
        put(common, "loader_percent", loaderPercent);
        put(common, "gpu_permits", gpuPermits);
        put(common, "verbose", verbose);
        put(common, "skip_existing", skipExisting);
        put(common, "use_tif_cache", useTifCache);
        put(common, "bin_preset", binPreset);
        put(common, "channel_names", channelNames);
        put(common, "channel_colors", channelColors);
        put(common, "object_thresholds", objectThresholds);
        put(common, "particle_sizes", particleSizes);
        put(common, "display_min_max", displayMinMax);
        put(common, "intensity_thresholds", intensityThresholds);
        put(common, "segmentation_methods", segmentationMethods);
        put(common, "filter_presets", filterPresets);
        put(common, "z_slice_mode", zSliceMode);
        if (preset != null) {
            return ParameterSnapshot.merged(common,
                    ParameterSnapshot.fromAnalysisPresetMap("CreateBinFileAnalysis", preset.toJsonObject()));
        }
        if (cliConfig != null) {
            return ParameterSnapshot.merged(ParameterSnapshot.fromCliConfig(cliConfig), common);
        }
        return common;
    }

    private static void appendFlag(StringBuilder options, String key) {
        if (options.length() > 0) options.append(' ');
        options.append(key);
    }

    private static void appendValue(StringBuilder options, String key, String value) {
        if (!hasText(value)) return;
        if (options.length() > 0) options.append(' ');
        options.append(key).append("=[").append(escapeBracketed(value)).append(']');
    }

    private static String escapeBracketed(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("]", "\\]");
    }

    private static BinPreset parsePresetJson(String json) {
        if (!hasText(json)) return null;
        try {
            return BinPreset.fromJson(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid presetJson for Set Up Configuration: "
                    + e.getMessage(), e);
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
