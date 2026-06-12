package flash.pipeline.progress;

import ij.IJ;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thread-safe progress reporter for analyses where several workers emit nested
 * image/channel/section events at once.
 */
public final class AnalysisProgressReporter {

    public interface Sink {
        void log(String message);
        void status(String message);
        void progress(int done, int total);
    }

    public interface Recorder {
        void recordProgressSnapshot(Map<String, Object> snapshot);
    }

    interface Clock {
        long nowMillis();
    }

    public static final class WorkHandle {
        private final String id;

        private WorkHandle(String id) {
            this.id = id;
        }
    }

    private static final Sink IJ_SINK = new Sink() {
        @Override public void log(String message) {
            IJ.log(message);
        }

        @Override public void status(String message) {
            IJ.showStatus(message);
        }

        @Override public void progress(int done, int total) {
            IJ.showProgress(done, total);
        }
    };

    private static final Sink NOOP_SINK = new Sink() {
        @Override public void log(String message) {
        }

        @Override public void status(String message) {
        }

        @Override public void progress(int done, int total) {
        }
    };

    private static final Recorder NOOP_RECORDER = new Recorder() {
        @Override public void recordProgressSnapshot(Map<String, Object> snapshot) {
        }
    };

    private static final Clock SYSTEM_CLOCK = new Clock() {
        @Override public long nowMillis() {
            return System.currentTimeMillis();
        }
    };

    private static final long DEFAULT_STATUS_INTERVAL_MS = 1000L;
    private static final long DEFAULT_LOG_INTERVAL_MS = 30000L;
    private static final int MAX_ACTIVE_LINES = 6;

    private final Object lock = new Object();
    private final String analysisName;
    private final Sink sink;
    private final Recorder recorder;
    private final Clock clock;
    private final long statusIntervalMillis;
    private final long logIntervalMillis;
    private final boolean enabled;
    private final long startedMillis;
    private final LinkedHashMap<String, ActiveTask> activeTasks =
            new LinkedHashMap<String, ActiveTask>();

    private String phase = "";
    private int totalUnits;
    private int startedUnits;
    private int completedUnits;
    private int skippedUnits;
    private int failedUnits;
    private int lastPublishedDone = -1;
    private long lastStatusMillis;
    private long lastLogMillis;
    private boolean finished;
    private int nextId;

    public static AnalysisProgressReporter create(String analysisName,
                                                  int totalUnits,
                                                  Sink sink,
                                                  Recorder recorder) {
        return new AnalysisProgressReporter(analysisName, totalUnits,
                sink, recorder, SYSTEM_CLOCK, DEFAULT_STATUS_INTERVAL_MS,
                DEFAULT_LOG_INTERVAL_MS, true);
    }

    public static AnalysisProgressReporter disabled() {
        return new AnalysisProgressReporter("", 0, NOOP_SINK, NOOP_RECORDER,
                SYSTEM_CLOCK, Long.MAX_VALUE, Long.MAX_VALUE, false);
    }

    public static Sink imageJSink() {
        return IJ_SINK;
    }

    static AnalysisProgressReporter createForTest(String analysisName,
                                                  int totalUnits,
                                                  Sink sink,
                                                  Recorder recorder,
                                                  Clock clock,
                                                  long statusIntervalMillis,
                                                  long logIntervalMillis) {
        return new AnalysisProgressReporter(analysisName, totalUnits,
                sink, recorder, clock, statusIntervalMillis, logIntervalMillis,
                true);
    }

    private AnalysisProgressReporter(String analysisName,
                                     int totalUnits,
                                     Sink sink,
                                     Recorder recorder,
                                     Clock clock,
                                     long statusIntervalMillis,
                                     long logIntervalMillis,
                                     boolean enabled) {
        this.analysisName = safe(analysisName);
        this.totalUnits = Math.max(0, totalUnits);
        this.sink = sink == null ? NOOP_SINK : sink;
        this.recorder = recorder == null ? NOOP_RECORDER : recorder;
        this.clock = clock == null ? SYSTEM_CLOCK : clock;
        this.statusIntervalMillis = Math.max(0L, statusIntervalMillis);
        this.logIntervalMillis = Math.max(0L, logIntervalMillis);
        this.enabled = enabled;
        this.startedMillis = this.clock.nowMillis();
    }

    public void setPhase(String phase) {
        if (!enabled) return;
        synchronized (lock) {
            this.phase = safe(phase);
            publishLocked(true, true, null);
        }
    }

    public void addTotalUnits(int units) {
        if (!enabled || units <= 0) return;
        synchronized (lock) {
            totalUnits += units;
            publishLocked(false, false, null);
        }
    }

    public WorkHandle begin(String label) {
        return begin(label, "");
    }

    public WorkHandle begin(String label, String detail) {
        if (!enabled) return null;
        synchronized (lock) {
            String id = "task-" + (++nextId);
            activeTasks.put(id, new ActiveTask(id, safe(label), safe(detail),
                    Thread.currentThread().getName(), clock.nowMillis()));
            startedUnits++;
            publishLocked(false, false, null);
            return new WorkHandle(id);
        }
    }

    public void update(WorkHandle handle, String detail) {
        if (!enabled || handle == null) return;
        synchronized (lock) {
            ActiveTask task = activeTasks.get(handle.id);
            if (task != null) {
                task.detail = safe(detail);
                publishLocked(false, false, null);
            }
        }
    }

    public void complete(WorkHandle handle, String summary) {
        finishTask(handle, summary, "done");
    }

    public void skip(WorkHandle handle, String summary) {
        finishTask(handle, summary, "skipped");
    }

    public void fail(WorkHandle handle, String summary) {
        finishTask(handle, summary, "failed");
    }

    public void finish(String summary) {
        if (!enabled) return;
        synchronized (lock) {
            finished = true;
            if (!activeTasks.isEmpty()) {
                failedUnits += activeTasks.size();
            }
            activeTasks.clear();
            publishLocked(true, true, summary);
        }
    }

    public Map<String, Object> snapshot() {
        synchronized (lock) {
            return snapshotLocked(clock.nowMillis());
        }
    }

    private void finishTask(WorkHandle handle, String summary, String outcome) {
        if (!enabled || handle == null) return;
        synchronized (lock) {
            ActiveTask task = activeTasks.remove(handle.id);
            if ("failed".equals(outcome)) {
                failedUnits++;
            } else if ("skipped".equals(outcome)) {
                skippedUnits++;
            } else {
                completedUnits++;
            }
            String line = summaryLine(task, summary, outcome);
            publishLocked(true, false, line);
        }
    }

    private void publishLocked(boolean forceLog, boolean forceStatus, String logLine) {
        long now = clock.nowMillis();
        int done = doneUnitsLocked();
        if (totalUnits > 0) {
            sink.progress(Math.min(done, totalUnits), totalUnits);
        }

        boolean shouldStatus = forceStatus
                || done != lastPublishedDone
                || now - lastStatusMillis >= statusIntervalMillis;
        if (shouldStatus) {
            sink.status(statusLineLocked(now));
            lastStatusMillis = now;
            lastPublishedDone = done;
        }

        if (logLine != null && !logLine.isEmpty()) {
            sink.log(logLine);
        }

        boolean shouldLog = forceLog || now - lastLogMillis >= logIntervalMillis;
        if (shouldLog) {
            sink.log(statusLineLocked(now));
            for (String activeLine : activeLinesLocked(now)) {
                sink.log("  active: " + activeLine);
            }
            lastLogMillis = now;
            recorder.recordProgressSnapshot(snapshotLocked(now));
        } else if (shouldStatus) {
            recorder.recordProgressSnapshot(snapshotLocked(now));
        }
    }

    private String summaryLine(ActiveTask task, String summary, String outcome) {
        String label = summary == null || summary.trim().isEmpty()
                ? (task == null ? "" : task.label)
                : summary.trim();
        int done = doneUnitsLocked();
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(analysisName).append("] ");
        sb.append(outcome).append(" ");
        if (totalUnits > 0) {
            sb.append(done).append("/").append(totalUnits).append(": ");
        }
        sb.append(label);
        if (task != null) {
            long elapsed = Math.max(0L, clock.nowMillis() - task.startedMillis);
            sb.append(" (").append(formatDurationCompact(elapsed)).append(")");
        }
        return sb.toString();
    }

    private String statusLineLocked(long now) {
        StringBuilder sb = new StringBuilder();
        sb.append(analysisName);
        if (!phase.isEmpty()) {
            sb.append(" | ").append(phase);
        }
        int done = doneUnitsLocked();
        if (totalUnits > 0) {
            int pct = (int) Math.floor((double) Math.min(done, totalUnits) * 100.0
                    / (double) totalUnits);
            sb.append(" | ").append(pct).append("%");
            sb.append(" | ").append(done).append("/").append(totalUnits).append(" done");
        } else {
            sb.append(" | ").append(done).append(" done");
        }
        if (!activeTasks.isEmpty()) {
            sb.append(" | active ").append(activeTasks.size());
        }
        String eta = etaLocked(now);
        if (!eta.isEmpty()) {
            sb.append(" | ETA ").append(eta);
        }
        if (failedUnits > 0) {
            sb.append(" | failed ").append(failedUnits);
        }
        if (finished) {
            sb.append(" | finished");
        }
        return sb.toString();
    }

    private List<String> activeLinesLocked(long now) {
        List<String> out = new ArrayList<String>();
        int count = 0;
        for (ActiveTask task : activeTasks.values()) {
            if (count >= MAX_ACTIVE_LINES) {
                out.add("+" + (activeTasks.size() - count) + " more");
                break;
            }
            StringBuilder sb = new StringBuilder();
            sb.append(task.label);
            if (!task.detail.isEmpty()) {
                sb.append(" | ").append(task.detail);
            }
            sb.append(" | ").append(formatDurationCompact(now - task.startedMillis));
            if (!task.worker.isEmpty()) {
                sb.append(" | ").append(task.worker);
            }
            out.add(sb.toString());
            count++;
        }
        return out;
    }

    private Map<String, Object> snapshotLocked(long now) {
        Map<String, Object> snap = new LinkedHashMap<String, Object>();
        snap.put("analysis", analysisName);
        snap.put("phase", phase);
        snap.put("startedAtMillis", Long.valueOf(startedMillis));
        snap.put("atMillis", Long.valueOf(now));
        snap.put("elapsedMillis", Long.valueOf(Math.max(0L, now - startedMillis)));
        snap.put("totalUnits", Integer.valueOf(totalUnits));
        snap.put("startedUnits", Integer.valueOf(startedUnits));
        snap.put("completedUnits", Integer.valueOf(completedUnits));
        snap.put("skippedUnits", Integer.valueOf(skippedUnits));
        snap.put("failedUnits", Integer.valueOf(failedUnits));
        snap.put("doneUnits", Integer.valueOf(doneUnitsLocked()));
        snap.put("activeUnits", Integer.valueOf(activeTasks.size()));
        snap.put("status", statusLineLocked(now));
        snap.put("active", new ArrayList<String>(activeLinesLocked(now)));
        snap.put("finished", Boolean.valueOf(finished));
        return snap;
    }

    private int doneUnitsLocked() {
        return completedUnits + skippedUnits + failedUnits;
    }

    private String etaLocked(long now) {
        int done = doneUnitsLocked();
        if (done <= 0 || totalUnits <= 0 || done >= totalUnits) {
            return "";
        }
        long elapsed = Math.max(1L, now - startedMillis);
        long remaining = elapsed * (long) (totalUnits - done) / (long) done;
        return formatDurationCompact(remaining);
    }

    private static String formatDurationCompact(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;
        if (hours > 0L) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0L) {
            return minutes + "m " + secs + "s";
        }
        return secs + "s";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class ActiveTask {
        final String id;
        final String label;
        final String worker;
        final long startedMillis;
        String detail;

        ActiveTask(String id, String label, String detail, String worker, long startedMillis) {
            this.id = id;
            this.label = label;
            this.detail = detail;
            this.worker = worker == null ? "" : worker;
            this.startedMillis = startedMillis;
        }
    }
}
