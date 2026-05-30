package flash.pipeline.analyses;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.analyses.wizard.ThreeDObjectPreset;
import flash.pipeline.analyses.wizard.ThreeDObjectPresetIO;
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
        name = "flash.threeDObjectAnalysis",
        label = "FLASH 3D Object Analysis")
public final class ThreeDObjectAnalysisCommand implements Command {

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
        final ThreeDObjectPreset preset = resolvePreset(projectDir);

        final ThreeDObjectAnalysis analysis = new ThreeDObjectAnalysis();
        analysis.setHeadless(headless == null || headless.booleanValue());
        analysis.setSuppressDialogs(true);
        applyCommonOptions(analysis);
        if (preset != null) {
            analysis.applyPreset(projectDir.getAbsolutePath(), preset);
        }

        coordinator().run(analysis, FLASH_Pipeline.IDX_3D_OBJECT, "3D Object Analysis",
                projectDir.getAbsolutePath(), null, commandParameters(preset),
                empty(parentRunId), new Callable<Void>() {
                    @Override public Void call() {
                        analysis.execute(projectDir.getAbsolutePath());
                        return null;
                    }
                });
    }

    private ThreeDObjectPreset resolvePreset(File projectDir) {
        if (hasText(presetJson)) {
            try {
                return ThreeDObjectPreset.fromJson(presetJson);
            } catch (IOException e) {
                throw new IllegalArgumentException("Invalid presetJson for 3D Object Analysis: "
                        + e.getMessage(), e);
            }
        }
        if (hasText(presetName)) {
            try {
                return new ThreeDObjectPresetIO(projectDir).load(presetName.trim());
            } catch (IOException e) {
                throw new IllegalArgumentException("Could not load ThreeDObjectPreset '"
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

    private Map<String, Object> commandParameters(ThreeDObjectPreset preset) {
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
                            "ThreeDObjectAnalysis", preset.toJsonObject()));
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
