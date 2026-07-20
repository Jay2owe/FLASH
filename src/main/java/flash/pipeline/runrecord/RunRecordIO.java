package flash.pipeline.runrecord;

import flash.pipeline.io.FlashProjectLayout;
import ij.IJ;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * JSON Lines read/write for {@link RunRecord} under
 * {@code FlashProjectLayout.runJsonlWriteDir()}. Each physical line is one
 * complete snapshot of the same logical run; the last decodable line is the
 * current record and earlier lines are progress history kept for crash
 * recovery. Corrupt trailing partial lines are skipped with a warning so a
 * mid-write crash never makes the whole file unreadable.
 */
public final class RunRecordIO {

    public static final String EXTENSION = ".jsonl";

    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private RunRecordIO() {
    }

    /**
     * Resolve the JSONL file for a record:
     * {@code <YYYYMMDD-HHMMSS>_<analysis>_<runId>.jsonl}.
     */
    public static File runFile(File runJsonlDir, RunRecord record) {
        if (runJsonlDir == null) {
            throw new IllegalArgumentException("runJsonlDir must not be null");
        }
        RunRecord safe = record == null ? new RunRecord() : record;
        long started = safe.startedAtMillis > 0L ? safe.startedAtMillis : 0L;
        String stamp = FILE_TIMESTAMP.format(Instant.ofEpochMilli(started));
        String analysis = sanitize(safe.analysis);
        String runId = sanitize(safe.runId);
        return new File(runJsonlDir, stamp + "_" + analysis + "_" + runId + EXTENSION);
    }

    /** Append one complete snapshot line, creating the parent directory if needed. */
    public static void writeSnapshot(File jsonlFile, RunRecord record) throws IOException {
        if (jsonlFile == null) {
            throw new IOException("writeSnapshot: target file is null");
        }
        Path path = jsonlFile.toPath();
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String line = RunRecordCodec.encode(record) + "\n";
        Files.write(path, line.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    /** All complete snapshots in the file, in write order. Missing file → empty. */
    public static List<RunRecord> readSnapshots(File jsonlFile) {
        List<RunRecord> snapshots = new ArrayList<RunRecord>();
        if (jsonlFile == null || !jsonlFile.isFile()) {
            return snapshots;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(jsonlFile.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            IJ.log("[FLASH] Could not read run record " + jsonlFile.getAbsolutePath() + ": " + e.getMessage());
            return snapshots;
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i) == null ? "" : lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            RunRecord record = RunRecordCodec.decodeOrNull(line);
            if (record != null) {
                snapshots.add(record);
            } else {
                IJ.log("[FLASH] Skipping corrupt run-record line " + (i + 1)
                        + " in " + jsonlFile.getAbsolutePath());
            }
        }
        return snapshots;
    }

    /** Last complete snapshot, or null if none decode. */
    public static RunRecord readLatest(File jsonlFile) {
        List<RunRecord> snapshots = readSnapshots(jsonlFile);
        return snapshots.isEmpty() ? null : snapshots.get(snapshots.size() - 1);
    }

    /**
     * Index every {@code *.jsonl} record in the directory as a {@link RunSummary},
     * most-recent first. Missing directory → empty list.
     */
    public static List<RunSummary> readIndex(File runJsonlDir) {
        List<RunSummary> summaries = new ArrayList<RunSummary>();
        collectIndex(runJsonlDir, summaries);
        collectReplayIndexes(runJsonlDir, summaries);
        Collections.sort(summaries, new Comparator<RunSummary>() {
            @Override
            public int compare(RunSummary a, RunSummary b) {
                return Long.compare(b.startedAtMillis, a.startedAtMillis);
            }
        });
        return summaries;
    }

    private static void collectIndex(File runJsonlDir, List<RunSummary> summaries) {
        if (runJsonlDir == null || !runJsonlDir.isDirectory()) {
            return;
        }
        File[] files = runJsonlDir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file == null || !file.isFile() || !file.getName().endsWith(EXTENSION)) {
                continue;
            }
            RunSummary summary = RunSummary.of(readLatest(file), file);
            if (summary != null) {
                summaries.add(summary);
            }
        }
    }

    private static void collectReplayIndexes(File runJsonlDir, List<RunSummary> summaries) {
        if (runJsonlDir == null || summaries == null) {
            return;
        }
        File runRecordsRoot = runJsonlDir.getParentFile();
        File replaysDir = runRecordsRoot == null ? null
                : new File(runRecordsRoot, FlashProjectLayout.REPLAY_WORKSPACES_DIR);
        collectReplayIndexesRecursive(replaysDir, summaries);
    }

    private static void collectReplayIndexesRecursive(File replaysDir, List<RunSummary> summaries) {
        if (replaysDir == null || !replaysDir.isDirectory()) {
            return;
        }
        File[] children = replaysDir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child == null || !child.isDirectory()) {
                continue;
            }
            FlashProjectLayout layout = FlashProjectLayout.forDirectory(child.getAbsolutePath());
            collectIndex(layout.runJsonlWriteDir(), summaries);
            collectReplayIndexesRecursive(layout.replayWorkspacesWriteDir(), summaries);
        }
    }

    /** True when a record with the given runId exists in the directory. */
    public static boolean exists(File runJsonlDir, String runId) {
        if (runJsonlDir == null || runId == null || runId.trim().isEmpty()
                || !runJsonlDir.isDirectory()) {
            return false;
        }
        File[] files = runJsonlDir.listFiles();
        if (files == null) {
            return false;
        }
        String needle = sanitize(runId.trim());
        for (File file : files) {
            if (file != null && file.isFile()
                    && file.getName().endsWith(EXTENSION)
                    && file.getName().contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String sanitize(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "unknown";
        }
        return value.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
