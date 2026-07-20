package flash.pipeline.execution;

import java.io.File;

/** Truthful terminal outcome returned or emitted by {@link AnalysisRunCoordinator}. */
public final class RunResult {

    /** Mutually exclusive states for one analysis invocation. */
    public enum TerminalState {
        COMPLETED,
        COMPLETED_WITH_WARNINGS,
        BLOCKED,
        CANCELLED,
        FAILED
    }

    /** Whether the mandatory terminal run record was durably acknowledged. */
    public enum PublicationState {
        NOT_REQUIRED,
        PUBLISHED,
        FAILED
    }

    public static final String STATUS_OK = "ok";
    public static final String STATUS_WARN = "warn";
    public static final String STATUS_BLOCKED = "blocked";
    public static final String STATUS_CANCELLED = "cancelled";
    public static final String STATUS_FAILED = "failed";

    public final String runId;
    public final String status;
    public final File recordFile;
    public final File intendedRecordFile;
    public final TerminalState terminalState;
    public final PublicationState publicationState;
    public final boolean warningsPresent;
    public final String reason;
    public final Throwable cause;

    /**
     * Construct a terminal result while enforcing the publication and state contract.
     * Prefer the named factories at normal call sites.
     */
    public RunResult(TerminalState terminalState,
                     PublicationState publicationState,
                     String runId,
                     File recordFile,
                     File intendedRecordFile,
                     boolean warningsPresent,
                     String reason,
                     Throwable cause) {
        if (terminalState == null) {
            throw new IllegalArgumentException("terminalState must not be null");
        }
        if (publicationState == null) {
            throw new IllegalArgumentException("publicationState must not be null");
        }
        String safeRunId = runId == null ? "" : runId.trim();
        String safeReason = reason == null ? "" : reason.trim();
        if (publicationState == PublicationState.PUBLISHED) {
            if (safeRunId.isEmpty() || recordFile == null) {
                throw new IllegalArgumentException(
                        "published results require a run ID and acknowledged record file");
            }
            if (intendedRecordFile != null && !recordFile.equals(intendedRecordFile)) {
                throw new IllegalArgumentException(
                        "a published record cannot name a different intended target");
            }
        } else if (recordFile != null) {
            throw new IllegalArgumentException(
                    "recordFile may be exposed only after publication is acknowledged");
        }
        if (publicationState == PublicationState.NOT_REQUIRED
                && (!safeRunId.isEmpty() || intendedRecordFile != null)) {
            throw new IllegalArgumentException(
                    "unrecorded results cannot claim a run ID or intended record file");
        }
        if (publicationState == PublicationState.FAILED) {
            if (terminalState != TerminalState.FAILED) {
                throw new IllegalArgumentException(
                        "publication failure requires a failed terminal state");
            }
            if (safeRunId.isEmpty() || intendedRecordFile == null) {
                throw new IllegalArgumentException(
                        "publication failure requires the run ID and intended target");
            }
        }

        if ((terminalState == TerminalState.COMPLETED
                || terminalState == TerminalState.COMPLETED_WITH_WARNINGS
                || terminalState == TerminalState.BLOCKED)
                && publicationState != PublicationState.PUBLISHED) {
            throw new IllegalArgumentException(
                    terminalState + " requires an acknowledged terminal run record");
        }
        if (terminalState == TerminalState.FAILED
                && publicationState == PublicationState.NOT_REQUIRED) {
            throw new IllegalArgumentException(
                    "failed results require a published record or explicit publication failure");
        }
        if (terminalState == TerminalState.COMPLETED && warningsPresent) {
            throw new IllegalArgumentException("completed results cannot contain warnings");
        }
        if (terminalState == TerminalState.COMPLETED_WITH_WARNINGS && !warningsPresent) {
            throw new IllegalArgumentException(
                    "completed-with-warnings requires at least one warning");
        }
        if (terminalState == TerminalState.BLOCKED && safeReason.isEmpty()) {
            throw new IllegalArgumentException("blocked results require an actionable reason");
        }
        if (terminalState == TerminalState.FAILED && cause == null) {
            throw new IllegalArgumentException("failed results require their cause");
        }
        if (terminalState != TerminalState.FAILED && cause != null) {
            throw new IllegalArgumentException("only failed results may carry a cause");
        }

        this.runId = safeRunId;
        this.terminalState = terminalState;
        this.publicationState = publicationState;
        this.status = legacyStatus(terminalState);
        this.recordFile = recordFile;
        this.intendedRecordFile = intendedRecordFile == null ? recordFile : intendedRecordFile;
        this.warningsPresent = warningsPresent;
        this.reason = safeReason;
        this.cause = cause;
    }

    public static RunResult completed(String runId, File recordFile) {
        return published(TerminalState.COMPLETED, runId, recordFile, false, "", null);
    }

    public static RunResult completedWithWarnings(String runId, File recordFile) {
        return published(TerminalState.COMPLETED_WITH_WARNINGS, runId, recordFile,
                true, "", null);
    }

    public static RunResult blocked(String runId, File recordFile, String reason,
                                    boolean warningsPresent) {
        return published(TerminalState.BLOCKED, runId, recordFile,
                warningsPresent, reason, null);
    }

    public static RunResult cancelled(String runId, File recordFile, String reason,
                                      boolean warningsPresent) {
        return published(TerminalState.CANCELLED, runId, recordFile,
                warningsPresent, reason, null);
    }

    public static RunResult cancelledWithoutRecord(String reason) {
        return new RunResult(TerminalState.CANCELLED, PublicationState.NOT_REQUIRED,
                "", null, null, false, reason, null);
    }

    public static RunResult failed(String runId, File recordFile, Throwable cause,
                                   boolean warningsPresent) {
        return published(TerminalState.FAILED, runId, recordFile,
                warningsPresent, "", cause);
    }

    public static RunResult publicationFailed(String runId, File intendedRecordFile,
                                              Throwable cause, boolean warningsPresent) {
        return new RunResult(TerminalState.FAILED, PublicationState.FAILED,
                runId, null, intendedRecordFile, warningsPresent, "", cause);
    }

    /** True only when the analysis body completed and mandatory publication succeeded. */
    public boolean bodyCompleted() {
        return terminalState == TerminalState.COMPLETED
                || terminalState == TerminalState.COMPLETED_WITH_WARNINGS;
    }

    /** True only for completed outcomes, including a completed run with recoverable warnings. */
    public boolean isSuccess() {
        return bodyCompleted();
    }

    public boolean recordPublished() {
        return publicationState == PublicationState.PUBLISHED;
    }

    public boolean hasWarnings() {
        return warningsPresent;
    }

    private static RunResult published(TerminalState state,
                                       String runId,
                                       File recordFile,
                                       boolean warningsPresent,
                                       String reason,
                                       Throwable cause) {
        return new RunResult(state, PublicationState.PUBLISHED, runId,
                recordFile, recordFile, warningsPresent, reason, cause);
    }

    private static String legacyStatus(TerminalState state) {
        switch (state) {
            case COMPLETED:
                return STATUS_OK;
            case COMPLETED_WITH_WARNINGS:
                return STATUS_WARN;
            case BLOCKED:
                return STATUS_BLOCKED;
            case CANCELLED:
                return STATUS_CANCELLED;
            case FAILED:
            default:
                return STATUS_FAILED;
        }
    }
}
