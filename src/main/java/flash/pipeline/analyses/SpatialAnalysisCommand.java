package flash.pipeline.analyses;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.analyses.wizard.SpatialSetupConfig;
import flash.pipeline.analyses.wizard.SpatialPreset;
import flash.pipeline.analyses.wizard.SpatialPresetIO;
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
        name = "flash.spatialAnalysis",
        label = "FLASH Spatial Analysis")
public final class SpatialAnalysisCommand implements Command {

    @Parameter(required = false)
    private AnalysisRunCoordinator coordinator;

    @Parameter(label = "Project directory", style = "directory")
    private File directory;

    @Parameter(required = false)
    private String presetJson;

    @Parameter(required = false)
    private String presetName;

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
        final SpatialPreset preset = resolvePreset(projectDir);

        final SpatialAnalysis analysis = new SpatialAnalysis();
        analysis.setHeadless(headless == null || headless.booleanValue());
        analysis.setSuppressDialogs(true);
        applyCommonOptions(analysis);
        if (preset != null) {
            analysis.setWizardConfig(SpatialSetupConfig.fromPreset(preset));
        }

        coordinator().run(analysis, FLASH_Pipeline.IDX_SPATIAL, "Spatial Analysis",
                projectDir.getAbsolutePath(), null, commandParameters(preset),
                empty(parentRunId), new Callable<Void>() {
                    @Override public Void call() {
                        analysis.execute(projectDir.getAbsolutePath());
                        return null;
                    }
                });
    }

    private SpatialPreset resolvePreset(File projectDir) {
        if (hasText(presetJson)) {
            try {
                return SpatialPreset.fromJson(presetJson);
            } catch (IOException e) {
                throw new IllegalArgumentException("Invalid presetJson for Spatial Analysis: "
                        + e.getMessage(), e);
            }
        }
        if (hasText(presetName)) {
            try {
                return new SpatialPresetIO(projectDir).load(presetName.trim());
            } catch (IOException e) {
                throw new IllegalArgumentException("Could not load SpatialPreset '"
                        + presetName + "': " + e.getMessage(), e);
            }
        }
        return null;
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

    private Map<String, Object> commandParameters(SpatialPreset preset) {
        Map<String, Object> common = new LinkedHashMap<String, Object>();
        common.put("headless", Boolean.valueOf(headless == null || headless.booleanValue()));
        put(common, "preset_name", presetName);
        put(common, "verbose", verbose);
        put(common, "skip_existing", skipExisting);
        put(common, "threads", parallelThreads);
        put(common, "loader_threads", loaderThreads);
        put(common, "loader_percent", loaderPercent);
        put(common, "use_tif_cache", useTifCache);
        if (preset != null) {
            return ParameterSnapshot.merged(common,
                    ParameterSnapshot.fromAnalysisPresetMap(
                            "SpatialAnalysis", preset.toJsonObject()));
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
