package flash.pipeline.runrecord;

import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.project.ProjectFile;
import ij.IJ;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Try-with-resources wrapper around one analysis execution. Collects the
 * environment and parameters at {@link #open}, streams per-input/per-output
 * items during the run (fingerprinting them on a bounded background thread so
 * the analysis never blocks on hashing), and writes a finalised
 * {@link RunRecord} JSONL snapshot at {@link #close} regardless of how the run
 * ends.
 *
 * <p>All mutating methods are safe to call from multiple analysis threads.
 */
public final class AnalysisRunContext implements AutoCloseable {

    private static final long FINGERPRINT_TIMEOUT_MS = 5000L;
    private static final String K_PROGRESS_LATEST = "progressLatest";
    private static final String K_PROGRESS_SNAPSHOTS = "progressSnapshots";
    private static final int MAX_PROGRESS_SNAPSHOTS = 200;

    private final RunRecord record;
    private final File runFile;
    private final InputFingerprinter.FingerprintMode fingerprintMode;
    private final ExecutorService fingerprintExecutor;
    private final Object lock = new Object();
    private boolean closed;

    /** Opaque handle to a specific recorded input, so repeats of the same file stay distinct. */
    public static final class InputHandle {
        private final RunRecord.InputItem item;

        private InputHandle(RunRecord.InputItem item) {
            this.item = item;
        }
    }

    private AnalysisRunContext(RunRecord record, File runFile,
                              InputFingerprinter.FingerprintMode mode) {
        this.record = record;
        this.runFile = runFile;
        this.fingerprintMode = mode;
        this.fingerprintExecutor = Executors.newSingleThreadExecutor(daemonFactory(record.runId));
    }

    public static AnalysisRunContext open(String analysisKey,
                                          int analysisIndex,
                                          String analysisLabel,
                                          String projectRoot,
                                          ProjectFile project,
                                          Map<String, Object> parameters,
                                          String parentRunId) {
        RunRecord record = new RunRecord();
        record.runId = Ulid.next();
        record.parentRunId = parentRunId == null ? "" : parentRunId;
        record.startedAtMillis = System.currentTimeMillis();
        record.status = RunRecord.STATUS_OK;
        record.analysis = analysisKey == null ? "" : analysisKey;
        record.analysisIndex = analysisIndex;
        record.analysisLabel = analysisLabel == null ? "" : analysisLabel;
        record.flashVersion = EnvironmentSnapshot.flashVersion();
        record.fijiBuild = EnvironmentSnapshot.fijiBuild();
        record.jdkVersion = EnvironmentSnapshot.jdkVersion();
        record.osName = EnvironmentSnapshot.osName();
        record.biofVersion = EnvironmentSnapshot.biofVersion();
        record.projectFileHash = ProjectFileHasher.hash(project);
        record.projectRoot = absolute(projectRoot);
        record.outputRoot = resolveOutputRoot(projectRoot, project);
        if (parameters != null) {
            record.parameters = new LinkedHashMap<String, Object>(parameters);
        }

        File runsDir = FlashProjectLayout.forDirectory(safeDirectory(projectRoot)).runJsonlWriteDir();
        File runFile = RunRecordIO.runFile(runsDir, record);
        return new AnalysisRunContext(record, runFile, InputFingerprinter.FingerprintMode.FAST);
    }

    /** ULID for this run, exposed for the {@code run_id} CSV column (phase 05). */
    public String runId() {
        return record.runId;
    }

    /** Resolved status at the current moment ("ok" | "warn" | "failed"). */
    public String status() {
        synchronized (lock) {
            return record.status;
        }
    }

    /** Path of the JSONL file this run writes to. */
    public File recordFile() {
        return runFile;
    }

    /** True once the analysis has recorded an output file. */
    public boolean hasRecordedOutputs() {
        synchronized (lock) {
            return !record.outputs.isEmpty();
        }
    }

    public InputHandle recordInputStart(File source, int seriesIndex, ProjectFile.Item item) {
        RunRecord.InputItem input = new RunRecord.InputItem();
        input.path = source == null ? "" : source.getAbsolutePath();
        input.seriesIndex = seriesIndex;
        if (item != null) {
            input.animalId = nz(item.animalId);
            input.hemisphere = nz(item.hemisphere);
            input.region = nz(item.region);
            input.condition = nz(item.condition);
        }
        input.fingerprintMode = fingerprintMode.token;
        input.status = "processing";
        synchronized (lock) {
            record.inputs.add(input);
        }
        if (source != null) {
            submitFingerprint(input, source, true);
        }
        return new InputHandle(input);
    }

    public void recordInputEnd(InputHandle handle, String status, long durationMillis) {
        if (handle == null) {
            return;
        }
        synchronized (lock) {
            handle.item.status = status == null ? "" : status;
            handle.item.durationMillis = durationMillis;
        }
    }

    public void recordOutput(File output, String kind) {
        RunRecord.OutputItem out = new RunRecord.OutputItem();
        out.path = output == null ? "" : output.getAbsolutePath();
        out.kind = kind == null ? "" : kind;
        synchronized (lock) {
            record.outputs.add(out);
        }
        if (output != null) {
            submitOutputFingerprint(out, output);
        }
    }

    /**
     * Merge the analysis's chosen settings into this run's {@code parameters}
     * map so a later "Load settings from previous run" can restore them.
     *
     * <p>Interactive GUI runs open the context with an empty parameter map (the
     * settings are not known until the dialog is confirmed). Each
     * {@link RunRecordAware} analysis calls this from within its run, once the
     * settings are finalised, passing its preset's own JSON object
     * ({@code preset.toJsonObject()}) so the keys match what
     * {@code LoadedRunParameters} recognises. Later keys override earlier ones,
     * mirroring {@link ParameterSnapshot#merged}.
     */
    public void recordParameters(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return;
        }
        synchronized (lock) {
            if (closed) {
                return;
            }
            record.parameters.putAll(params);
        }
    }

    public void recordProgressSnapshot(Map<String, Object> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return;
        }
        synchronized (lock) {
            if (closed) {
                return;
            }
            Map<String, Object> copy = new LinkedHashMap<String, Object>(snapshot);
            record.extras.put(K_PROGRESS_LATEST, copy);
            Object existing = record.extras.get(K_PROGRESS_SNAPSHOTS);
            List<Object> snapshots;
            if (existing instanceof List) {
                snapshots = new ArrayList<Object>((List<?>) existing);
            } else {
                snapshots = new ArrayList<Object>();
            }
            snapshots.add(copy);
            while (snapshots.size() > MAX_PROGRESS_SNAPSHOTS) {
                snapshots.remove(0);
            }
            record.extras.put(K_PROGRESS_SNAPSHOTS, snapshots);
        }
    }

    public void warn(String message) {
        synchronized (lock) {
            addMessageLocked("warn", message);
            if (RunRecord.STATUS_OK.equals(record.status)) {
                record.status = RunRecord.STATUS_WARN;
            }
        }
    }

    public void info(String message) {
        synchronized (lock) {
            addMessageLocked("info", message);
        }
    }

    public void error(String message, Throwable t) {
        String text = message == null ? "" : message;
        if (t != null) {
            text = text.isEmpty() ? String.valueOf(t) : text + ": " + t;
        }
        synchronized (lock) {
            addMessageLocked("error", text);
            record.status = RunRecord.STATUS_FAILED;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        // Drain fingerprint tasks WITHOUT holding the lock, so in-flight tasks
        // can acquire it to publish their results.
        fingerprintExecutor.shutdown();
        try {
            if (!fingerprintExecutor.awaitTermination(FINGERPRINT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                fingerprintExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            fingerprintExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        synchronized (lock) {
            for (RunRecord.InputItem input : record.inputs) {
                if (input.fingerprint == null) {
                    input.fingerprint = "";
                }
                if (input.fingerprint.isEmpty() && input.path != null && !input.path.isEmpty()) {
                    addMessageLocked("warn", "No fingerprint captured for input " + input.path);
                }
            }
            record.finishedAtMillis = System.currentTimeMillis();
            try {
                RunRecordIO.writeSnapshot(runFile, record);
            } catch (IOException e) {
                IJ.log("[FLASH] Could not write run record "
                        + runFile.getAbsolutePath() + ": " + e.getMessage());
            }
        }
    }

    /** Close without writing a run-record snapshot. Used for pre-run GUI cancel. */
    public void discard() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        fingerprintExecutor.shutdownNow();
    }

    private void submitFingerprint(final RunRecord.InputItem input, final File source,
                                   final boolean useConfiguredMode) {
        synchronized (lock) {
            if (closed) {
                return;
            }
        }
        try {
            fingerprintExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    InputFingerprinter.FingerprintResult result = useConfiguredMode
                            ? InputFingerprinter.fingerprint(source, fingerprintMode)
                            : InputFingerprinter.fastFingerprint(source);
                    synchronized (lock) {
                        input.fingerprint = result.value;
                        input.sizeBytes = result.sizeBytes;
                        input.lastModifiedMillis = result.lastModifiedMillis;
                        if (!result.warning.isEmpty()) {
                            addMessageLocked("warn", result.warning);
                        }
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Context already closing; the close() sweep records the missing fingerprint.
        }
    }

    private void submitOutputFingerprint(final RunRecord.OutputItem output, final File file) {
        synchronized (lock) {
            if (closed) {
                return;
            }
        }
        try {
            fingerprintExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    InputFingerprinter.FingerprintResult result = InputFingerprinter.fastFingerprint(file);
                    synchronized (lock) {
                        output.fingerprint = result.value;
                        output.sizeBytes = result.sizeBytes;
                        if (!result.warning.isEmpty()) {
                            addMessageLocked("warn", result.warning);
                        }
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Context already closing; output fingerprint best-effort.
        }
    }

    /** Caller must hold {@link #lock}. */
    private void addMessageLocked(String level, String text) {
        record.messages.add(new RunRecord.Message(level, System.currentTimeMillis(),
                text == null ? "" : text));
    }

    private static String resolveOutputRoot(String projectRoot, ProjectFile project) {
        if (project != null && project.outputRoot != null && !project.outputRoot.trim().isEmpty()) {
            return new File(project.outputRoot).getAbsolutePath();
        }
        return absolute(projectRoot);
    }

    private static String absolute(String path) {
        return path == null || path.trim().isEmpty() ? "" : new File(path).getAbsolutePath();
    }

    private static String safeDirectory(String projectRoot) {
        return projectRoot == null || projectRoot.trim().isEmpty()
                ? new File(".").getAbsolutePath()
                : projectRoot;
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static ThreadFactory daemonFactory(final String runId) {
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "flash-runrecord-fp-" + runId);
                thread.setDaemon(true);
                return thread;
            }
        };
    }
}
