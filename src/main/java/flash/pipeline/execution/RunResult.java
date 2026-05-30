package flash.pipeline.execution;

import java.io.File;

/** Outcome handle returned by {@link AnalysisRunCoordinator#run}. */
public final class RunResult {

    public final String runId;
    public final String status;
    public final File recordFile;

    public RunResult(String runId, String status, File recordFile) {
        this.runId = runId == null ? "" : runId;
        this.status = status == null ? "" : status;
        this.recordFile = recordFile;
    }
}
