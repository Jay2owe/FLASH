package flash.pipeline.analyses;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.execution.AnalysisRunCoordinator;

import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Plugin(type = Command.class, headless = true, visible = false,
        name = "flash.drawAndSaveROIsAnalysis",
        label = "FLASH Draw and Save ROIs")
public final class DrawAndSaveROIsAnalysisCommand implements Command {

    @Parameter(required = false)
    private AnalysisRunCoordinator coordinator;

    @Parameter(label = "Project directory", style = "directory")
    private File directory;

    @Parameter(required = false)
    private String presetJson;

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
        final DrawAndSaveROIsAnalysis analysis = new DrawAndSaveROIsAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setCommandMode(true);
        applyCommonOptions(analysis);

        coordinator().run(analysis, FLASH_Pipeline.IDX_DRAW_ROIS, "Draw and Save ROIs",
                projectDir.getAbsolutePath(), null, commandParameters(), empty(parentRunId),
                new Callable<Void>() {
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

    private Map<String, Object> commandParameters() {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("headless", Boolean.TRUE);
        put(params, "parallel", parallel);
        put(params, "threads", parallelThreads);
        put(params, "loader_threads", loaderThreads);
        put(params, "loader_percent", loaderPercent);
        put(params, "verbose", verbose);
        put(params, "skip_existing", skipExisting);
        put(params, "use_tif_cache", useTifCache);
        put(params, "preset_json_supplied", Boolean.valueOf(hasText(presetJson)));
        return params;
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
