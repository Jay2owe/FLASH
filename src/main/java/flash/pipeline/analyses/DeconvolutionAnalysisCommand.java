package flash.pipeline.analyses;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.cli.CLIArgumentParser;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.deconv.wizard.DeconvPreset;
import flash.pipeline.deconv.wizard.DeconvPresetIO;
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
        name = "flash.deconvolutionAnalysis",
        label = "FLASH 3D Deconvolution")
public final class DeconvolutionAnalysisCommand implements Command {

    @Parameter(required = false)
    private AnalysisRunCoordinator coordinator;

    @Parameter(label = "Project directory", style = "directory")
    private File directory;

    @Parameter(required = false)
    private String presetJson;

    @Parameter(required = false)
    private String deconvPreset;

    @Parameter(required = false)
    private String engine;

    @Parameter(required = false)
    private String algorithm;

    @Parameter(required = false)
    private String psfModel;

    @Parameter(required = false)
    private Integer iterations;

    @Parameter(required = false)
    private Double regularization;

    @Parameter(required = false)
    private String scopeModality;

    @Parameter(required = false)
    private Double pinholeAU;

    @Parameter(required = false)
    private Double sampleRI;

    @Parameter(required = false)
    private String mountingMedium;

    @Parameter(required = false)
    private String channels;

    @Parameter(required = false)
    private Boolean strictNyquist;

    @Parameter(required = false)
    private Boolean useCache;

    @Parameter(required = false)
    private Boolean skipPreview;

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
        final DeconvPreset jsonPreset = parsePresetJson(presetJson);
        final DeconvPreset namedPreset = loadNamedPreset(projectDir, deconvPreset);
        final DeconvPreset effectivePreset = jsonPreset == null ? namedPreset : jsonPreset;
        final CLIConfig cliConfig = parseCliConfig(projectDir, effectivePreset);

        final DeconvolutionAnalysis analysis = new DeconvolutionAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setCliConfig(cliConfig);
        applyCommonOptions(analysis);

        coordinator().run(analysis, FLASH_Pipeline.IDX_DECONVOLUTION, "3D Deconvolution",
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

    private void applyCommonOptions(Analysis analysis) {
        if (verbose != null) analysis.setVerboseLogging(verbose.booleanValue());
        if (skipExisting != null) analysis.setSkipExisting(skipExisting.booleanValue());
        if (parallelThreads != null) analysis.setParallelThreads(parallelThreads.intValue());
        if (loaderThreads != null) analysis.setLoaderThreads(loaderThreads.intValue());
        if (loaderPercent != null) analysis.setLoaderPercent(loaderPercent.intValue());
        if (useTifCache != null) analysis.setUseTifCache(useTifCache.booleanValue());
    }

    private CLIConfig parseCliConfig(File projectDir, DeconvPreset preset) {
        StringBuilder options = new StringBuilder();
        appendValue(options, "dir", projectDir.getAbsolutePath());
        appendFlag(options, "run_deconv");
        appendValue(options, "headless", "true");
        appendValue(options, "no_aggregate", "true");
        appendValue(options, "no_qc", "true");
        appendValue(options, "deconv.enabled", "true");
        if (parallel != null) appendValue(options, "parallel", String.valueOf(parallel.booleanValue()));
        if (parallelThreads != null) appendValue(options, "threads", String.valueOf(parallelThreads.intValue()));
        if (loaderThreads != null) appendValue(options, "loader_threads", String.valueOf(loaderThreads.intValue()));
        if (loaderPercent != null) appendValue(options, "loader_percent", String.valueOf(loaderPercent.intValue()));
        if (verbose != null && verbose.booleanValue()) appendFlag(options, "verbose");
        if (useTifCache != null && useTifCache.booleanValue()) appendFlag(options, "tif_cache");
        if (skipExisting != null) appendValue(options, "overwrite", skipExisting.booleanValue() ? "skip" : "auto");

        if (preset != null) {
            appendValue(options, "deconv.engine", preset.getEngineKey());
            appendValue(options, "deconv.algorithm", preset.getAlgorithm().name());
            appendValue(options, "deconv.psf", preset.getPsfModel().name());
            appendValue(options, "deconv.iterations", String.valueOf(preset.getIterations()));
            appendValue(options, "deconv.regularization", String.valueOf(preset.getRegularization()));
            appendValue(options, "deconv.scopeModality", preset.getScopeModality().name());
            if (preset.getPinholeAU() != null) {
                appendValue(options, "deconv.pinholeAU", String.valueOf(preset.getPinholeAU()));
            }
            if (preset.getSampleRI() != null) {
                appendValue(options, "deconv.sampleRI", String.valueOf(preset.getSampleRI()));
            }
        }
        if (hasText(deconvPreset) && presetJson == null) appendValue(options, "deconv.preset", deconvPreset);
        appendValue(options, "deconv.engine", engine);
        appendValue(options, "deconv.algorithm", algorithm);
        appendValue(options, "deconv.psf", psfModel);
        if (iterations != null) appendValue(options, "deconv.iterations", String.valueOf(iterations.intValue()));
        if (regularization != null) appendValue(options, "deconv.regularization", String.valueOf(regularization.doubleValue()));
        appendValue(options, "deconv.scopeModality", scopeModality);
        if (pinholeAU != null) appendValue(options, "deconv.pinholeAU", String.valueOf(pinholeAU.doubleValue()));
        if (sampleRI != null) appendValue(options, "deconv.sampleRI", String.valueOf(sampleRI.doubleValue()));
        appendValue(options, "deconv.mountingMedium", mountingMedium);
        appendValue(options, "deconv.channels", channels);
        if (strictNyquist != null) appendValue(options, "deconv.strictNyquist", String.valueOf(strictNyquist.booleanValue()));
        if (useCache != null) appendValue(options, "deconv.useCache", String.valueOf(useCache.booleanValue()));
        appendValue(options, "deconv.skipPreview", String.valueOf(skipPreview == null || skipPreview.booleanValue()));

        CLIConfig parsed = CLIArgumentParser.parse(options.toString());
        if (parsed == null) {
            throw new IllegalArgumentException("Could not parse FLASH 3D Deconvolution command parameters.");
        }
        return parsed;
    }

    private Map<String, Object> commandParameters(DeconvPreset preset, CLIConfig cliConfig) {
        Map<String, Object> common = new LinkedHashMap<String, Object>();
        common.put("headless", Boolean.TRUE);
        put(common, "parallel", parallel);
        put(common, "threads", parallelThreads);
        put(common, "loader_threads", loaderThreads);
        put(common, "loader_percent", loaderPercent);
        put(common, "verbose", verbose);
        put(common, "skip_existing", skipExisting);
        put(common, "use_tif_cache", useTifCache);
        put(common, "deconv_preset", deconvPreset);
        put(common, "engine", engine);
        put(common, "algorithm", algorithm);
        put(common, "psf_model", psfModel);
        put(common, "iterations", iterations);
        put(common, "regularization", regularization);
        put(common, "scope_modality", scopeModality);
        put(common, "pinhole_au", pinholeAU);
        put(common, "sample_ri", sampleRI);
        put(common, "mounting_medium", mountingMedium);
        put(common, "channels", channels);
        put(common, "strict_nyquist", strictNyquist);
        put(common, "use_cache", useCache);
        put(common, "skip_preview", skipPreview == null ? Boolean.TRUE : skipPreview);
        if (preset != null) {
            return ParameterSnapshot.merged(common,
                    ParameterSnapshot.fromAnalysisPresetMap("DeconvolutionAnalysis", preset.toJsonObject()));
        }
        return ParameterSnapshot.merged(ParameterSnapshot.fromCliConfig(cliConfig), common);
    }

    private static DeconvPreset parsePresetJson(String json) {
        if (!hasText(json)) return null;
        try {
            return DeconvPreset.fromJson(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid presetJson for 3D Deconvolution: "
                    + e.getMessage(), e);
        }
    }

    private static DeconvPreset loadNamedPreset(File projectDir, String name) {
        if (!hasText(name)) return null;
        try {
            return new DeconvPresetIO(projectDir).load(name);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not load DeconvPreset '" + name + "': "
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
