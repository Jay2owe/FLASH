package flash.pipeline.decontamination;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.cli.CLIArgumentParser;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.decontamination.wizard.SpectralDecontamPreset;
import flash.pipeline.decontamination.wizard.SpectralDecontamPresetIO;
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
        name = "flash.spectralDecontaminationAnalysis",
        label = "FLASH Spectral Decontamination")
public final class SpectralDecontaminationAnalysisCommand implements Command {

    @Parameter(required = false)
    private AnalysisRunCoordinator coordinator;

    @Parameter(label = "Project directory", style = "directory")
    private File directory;

    @Parameter(required = false)
    private String presetJson;

    @Parameter(required = false)
    private String spectralPreset;

    @Parameter(required = false)
    private String goal;

    @Parameter(required = false)
    private Integer targetChannelIndex;

    @Parameter(required = false)
    private String bleedThroughChannels;

    @Parameter(required = false)
    private String autofluorescenceChannels;

    @Parameter(required = false)
    private String contaminationType;

    @Parameter(required = false)
    private String calibration;

    @Parameter(required = false)
    private String strength;

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
        final SpectralDecontamPreset jsonPreset = parsePresetJson(presetJson);
        final SpectralDecontamPreset namedPreset = loadNamedPreset(projectDir, spectralPreset);
        final SpectralDecontamPreset effectivePreset = jsonPreset == null ? namedPreset : jsonPreset;
        final CLIConfig cliConfig = parseCliConfig(projectDir);

        final SpectralDecontaminationAnalysis analysis = new SpectralDecontaminationAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setCliConfig(cliConfig);
        analysis.setCommandPreset(effectivePreset);
        applyCommonOptions(analysis);

        coordinator().run(analysis, FLASH_Pipeline.IDX_SPECTRAL_DECONTAMINATION,
                "Spectral Decontamination (Experimental)",
                projectDir.getAbsolutePath(), cliConfig, commandParameters(effectivePreset, cliConfig),
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

    private void applyCommonOptions(flash.pipeline.analyses.Analysis analysis) {
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
        appendFlag(options, "run_spectral_decontamination");
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
        appendValue(options, "spectral.preset", spectralPreset);
        appendValue(options, "spectral.goal", goal);
        if (targetChannelIndex != null) {
            appendValue(options, "spectral.target", String.valueOf(targetChannelIndex.intValue()));
        }
        appendValue(options, "spectral.bleedthrough", bleedThroughChannels);
        appendValue(options, "spectral.autofluorescence", autofluorescenceChannels);
        appendValue(options, "spectral.contamination_type", contaminationType);
        appendValue(options, "spectral.calibration", calibration);
        appendValue(options, "spectral.strength", strength);

        CLIConfig parsed = CLIArgumentParser.parse(options.toString());
        if (parsed == null) {
            throw new IllegalArgumentException("Could not parse FLASH Spectral Decontamination command parameters.");
        }
        return parsed;
    }

    private Map<String, Object> commandParameters(SpectralDecontamPreset preset, CLIConfig cliConfig) {
        Map<String, Object> common = new LinkedHashMap<String, Object>();
        common.put("headless", Boolean.TRUE);
        put(common, "parallel", parallel);
        put(common, "threads", parallelThreads);
        put(common, "loader_threads", loaderThreads);
        put(common, "loader_percent", loaderPercent);
        put(common, "verbose", verbose);
        put(common, "skip_existing", skipExisting);
        put(common, "use_tif_cache", useTifCache);
        put(common, "spectral_preset", spectralPreset);
        put(common, "goal", goal);
        put(common, "target_channel_index", targetChannelIndex);
        put(common, "bleed_through_channels", bleedThroughChannels);
        put(common, "autofluorescence_channels", autofluorescenceChannels);
        put(common, "contamination_type", contaminationType);
        put(common, "calibration", calibration);
        put(common, "strength", strength);
        if (preset != null) {
            return ParameterSnapshot.merged(common,
                    ParameterSnapshot.fromAnalysisPresetMap("SpectralDecontaminationAnalysis", preset.toJsonObject()));
        }
        return ParameterSnapshot.merged(ParameterSnapshot.fromCliConfig(cliConfig), common);
    }

    private static SpectralDecontamPreset parsePresetJson(String json) {
        if (!hasText(json)) return null;
        try {
            return SpectralDecontamPreset.fromJson(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid presetJson for Spectral Decontamination: "
                    + e.getMessage(), e);
        }
    }

    private static SpectralDecontamPreset loadNamedPreset(File projectDir, String name) {
        if (!hasText(name)) return null;
        try {
            return new SpectralDecontamPresetIO(projectDir).load(name);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not load SpectralDecontamPreset '" + name + "': "
                    + e.getMessage(), e);
        }
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
