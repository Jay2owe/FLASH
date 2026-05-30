package flash.pipeline.analyses;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.cli.CLIArgumentParser;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.execution.AnalysisRunCoordinator;
import flash.pipeline.runrecord.ParameterSnapshot;

import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Plugin(type = Command.class, headless = true, visible = false,
        name = "flash.splitAndMergeImageChannelsAnalysis",
        label = "FLASH Make Presentation Images")
public final class SplitAndMergeImageChannelsAnalysisCommand implements Command {

    @Parameter(required = false)
    private AnalysisRunCoordinator coordinator;

    @Parameter(label = "Project directory", style = "directory")
    private File directory;

    @Parameter(required = false)
    private String presetJson;

    @Parameter(required = false)
    private Boolean useDeconvolvedInput;

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
    private String parentRunId;

    @Override
    public void run() {
        final File projectDir = requireDirectory(directory);
        final CLIConfig cliConfig = parseCliConfig(projectDir);

        final SplitAndMergeImageChannelsAnalysis analysis =
                new SplitAndMergeImageChannelsAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setCliConfig(cliConfig);
        applyCommonOptions(analysis);

        coordinator().run(analysis, FLASH_Pipeline.IDX_SPLIT_MERGE, "Make Presentation Images",
                projectDir.getAbsolutePath(), cliConfig, commandParameters(projectDir, cliConfig),
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

    private CLIConfig parseCliConfig(File projectDir) {
        StringBuilder options = new StringBuilder();
        appendValue(options, "dir", projectDir.getAbsolutePath());
        appendFlag(options, "run_split");
        appendValue(options, "headless", "true");
        appendValue(options, "no_aggregate", "true");
        appendValue(options, "no_qc", "true");
        if (parallel != null) appendValue(options, "parallel", String.valueOf(parallel.booleanValue()));
        if (parallelThreads != null) appendValue(options, "threads", String.valueOf(parallelThreads.intValue()));
        if (loaderThreads != null) appendValue(options, "loader_threads", String.valueOf(loaderThreads.intValue()));
        if (loaderPercent != null) appendValue(options, "loader_percent", String.valueOf(loaderPercent.intValue()));
        if (verbose != null && verbose.booleanValue()) appendFlag(options, "verbose");
        if (useTifCache != null && useTifCache.booleanValue()) appendFlag(options, "tif_cache");
        if (skipExisting != null) appendValue(options, "overwrite", skipExisting.booleanValue() ? "skip" : "auto");
        if (useDeconvolvedInput != null) {
            appendValue(options, "splitmerge.useDeconv", String.valueOf(useDeconvolvedInput.booleanValue()));
        }
        CLIConfig parsed = CLIArgumentParser.parse(options.toString());
        if (parsed == null) {
            throw new IllegalArgumentException("Could not parse FLASH Make Presentation Images command parameters.");
        }
        return parsed;
    }

    private Map<String, Object> commandParameters(File projectDir, CLIConfig cliConfig) {
        Map<String, Object> common = new LinkedHashMap<String, Object>();
        common.put("headless", Boolean.TRUE);
        put(common, "parallel", parallel);
        put(common, "threads", parallelThreads);
        put(common, "loader_threads", loaderThreads);
        put(common, "loader_percent", loaderPercent);
        put(common, "verbose", verbose);
        put(common, "skip_existing", skipExisting);
        put(common, "use_tif_cache", useTifCache);
        put(common, "use_deconvolved_input", useDeconvolvedInput);
        put(common, "preset_json_supplied", Boolean.valueOf(hasText(presetJson)));

        BinConfig config = BinConfigIO.readPartialFromDirectory(projectDir.getAbsolutePath());
        return ParameterSnapshot.merged(
                ParameterSnapshot.fromCliConfig(cliConfig),
                ParameterSnapshot.fromBinConfig(config),
                common);
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
