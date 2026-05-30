package flash.pipeline.runrecord;

import java.io.File;

/**
 * Lightweight header-only view of a {@link RunRecord}, enough to render a row
 * in the runs browser without parsing the full record. Produced by
 * {@link RunRecordIO#readIndex(File)} from the latest snapshot in each file.
 */
public final class RunSummary {

    public final String runId;
    public final String parentRunId;
    public final long startedAtMillis;
    public final long finishedAtMillis;
    public final String status;
    public final String analysis;
    public final int analysisIndex;
    public final String analysisLabel;
    public final String flashVersion;
    public final String projectFileHash;
    public final String projectRoot;
    public final String outputRoot;
    public final int inputCount;
    public final File recordFile;

    public RunSummary(String runId,
                      String parentRunId,
                      long startedAtMillis,
                      long finishedAtMillis,
                      String status,
                      String analysis,
                      int analysisIndex,
                      String analysisLabel,
                      String flashVersion,
                      String projectFileHash,
                      String projectRoot,
                      String outputRoot,
                      int inputCount,
                      File recordFile) {
        this.runId = runId == null ? "" : runId;
        this.parentRunId = parentRunId == null ? "" : parentRunId;
        this.startedAtMillis = startedAtMillis;
        this.finishedAtMillis = finishedAtMillis;
        this.status = status == null ? "" : status;
        this.analysis = analysis == null ? "" : analysis;
        this.analysisIndex = analysisIndex;
        this.analysisLabel = analysisLabel == null ? "" : analysisLabel;
        this.flashVersion = flashVersion == null ? "" : flashVersion;
        this.projectFileHash = projectFileHash == null ? "" : projectFileHash;
        this.projectRoot = projectRoot == null ? "" : projectRoot;
        this.outputRoot = outputRoot == null ? "" : outputRoot;
        this.inputCount = inputCount;
        this.recordFile = recordFile;
    }

    /** Build a summary from a full record and the file it was read from. */
    public static RunSummary of(RunRecord record, File recordFile) {
        if (record == null) {
            return null;
        }
        return new RunSummary(
                record.runId,
                record.parentRunId,
                record.startedAtMillis,
                record.finishedAtMillis,
                record.status,
                record.analysis,
                record.analysisIndex,
                record.analysisLabel,
                record.flashVersion,
                record.projectFileHash,
                record.projectRoot,
                record.outputRoot,
                record.inputs == null ? 0 : record.inputs.size(),
                recordFile);
    }

    /** Elapsed run duration in milliseconds, or -1 if not finished. */
    public long durationMillis() {
        if (finishedAtMillis <= 0L || startedAtMillis <= 0L || finishedAtMillis < startedAtMillis) {
            return -1L;
        }
        return finishedAtMillis - startedAtMillis;
    }
}
