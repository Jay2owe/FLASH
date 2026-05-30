package flash.pipeline.analyses;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.execution.AnalysisRunCoordinator;

import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Plugin(type = Command.class, headless = true, visible = false,
        name = "flash.lineDistanceAnalysis",
        label = "FLASH Line Distance Analysis")
public final class LineDistanceAnalysisCommand implements Command {

    @Parameter(required = false)
    private AnalysisRunCoordinator coordinator;

    @Parameter(label = "Project directory", style = "directory")
    private File directory;

    @Parameter(required = false)
    private String lineSets;

    @Parameter(required = false)
    private Boolean verbose;

    @Parameter(required = false)
    private Boolean skipExisting;

    @Parameter(required = false)
    private String parentRunId;

    @Override
    public void run() {
        final File projectDir = requireDirectory(directory);

        final LineDistanceAnalysis analysis = new LineDistanceAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setCommandMode(true);
        analysis.setCommandLineSets(parseList(lineSets));
        if (verbose != null) analysis.setVerboseLogging(verbose.booleanValue());
        if (skipExisting != null) analysis.setSkipExisting(skipExisting.booleanValue());

        coordinator().run(analysis, FLASH_Pipeline.IDX_LINE_DISTANCE, "Line Distance Analysis",
                projectDir.getAbsolutePath(), null, commandParameters(),
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

    private Map<String, Object> commandParameters() {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("headless", Boolean.TRUE);
        params.put("command_mode", Boolean.TRUE);
        put(params, "line_sets", lineSets);
        put(params, "verbose", verbose);
        put(params, "skip_existing", skipExisting);
        return params;
    }

    private static List<String> parseList(String csv) {
        List<String> out = new ArrayList<String>();
        if (csv == null || csv.trim().isEmpty()) {
            return out;
        }
        String[] parts = csv.split(",");
        for (String part : parts) {
            String trimmed = part == null ? "" : part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
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

    private static String empty(String value) {
        return value == null ? "" : value;
    }
}
