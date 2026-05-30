package flash.pipeline.runrecord;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RunRecordIORoundTripTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static RunRecord record(String runId, String analysis, long startedAt) {
        RunRecord record = new RunRecord();
        record.runId = runId;
        record.analysis = analysis;
        record.startedAtMillis = startedAt;
        record.finishedAtMillis = startedAt + 1000L;
        return record;
    }

    @Test
    public void writeSnapshotCreatesMissingParentDirectory() throws Exception {
        File runsDir = new File(temp.getRoot(), "nested/runs");
        assertFalse(runsDir.exists());
        RunRecord record = record("R1", "ThreeDObjectAnalysis", 1_716_912_000_000L);

        RunRecordIO.writeSnapshot(RunRecordIO.runFile(runsDir, record), record);

        assertTrue(runsDir.isDirectory());
    }

    @Test
    public void multipleSnapshotsAppendAndLatestWins() throws Exception {
        File runsDir = temp.newFolder("runs");
        RunRecord record = record("R2", "SpatialAnalysis", 1_716_912_000_000L);
        File file = RunRecordIO.runFile(runsDir, record);

        record.status = "ok";
        RunRecordIO.writeSnapshot(file, record);
        record.status = "warn";
        record.messages.add(new RunRecord.Message("warn", 1L, "halfway"));
        RunRecordIO.writeSnapshot(file, record);
        record.status = "failed";
        RunRecordIO.writeSnapshot(file, record);

        List<RunRecord> all = RunRecordIO.readSnapshots(file);
        assertEquals(3, all.size());
        assertEquals("ok", all.get(0).status);
        assertEquals("warn", all.get(1).status);

        RunRecord latest = RunRecordIO.readLatest(file);
        assertEquals("failed", latest.status);
        assertEquals("R2", latest.runId);
    }

    @Test
    public void runFileNameContainsTimestampAnalysisAndRunId() {
        File runsDir = new File(temp.getRoot(), "runs");
        RunRecord record = record("01HZX3R8K2QABCDEFGHJKMNPQR", "IntensityAnalysisV2", 1_716_912_000_000L);

        File file = RunRecordIO.runFile(runsDir, record);

        String name = file.getName();
        assertTrue(name.endsWith(".jsonl"));
        assertTrue(name.contains("IntensityAnalysisV2"));
        assertTrue(name.contains("01HZX3R8K2QABCDEFGHJKMNPQR"));
    }

    @Test
    public void corruptTrailingLineIsSkippedSoftly() throws Exception {
        File runsDir = temp.newFolder("runs");
        RunRecord record = record("R3", "StatisticalAnalysis", 1_716_912_000_000L);
        File file = RunRecordIO.runFile(runsDir, record);
        RunRecordIO.writeSnapshot(file, record);

        // Simulate a crash mid-write: append a partial/corrupt line.
        Files.write(file.toPath(), "{\"schemaVersion\":1,\"runId\":\"R3\"".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);

        List<RunRecord> all = RunRecordIO.readSnapshots(file);
        assertEquals("Corrupt trailing line must be skipped", 1, all.size());
        RunRecord latest = RunRecordIO.readLatest(file);
        assertEquals("R3", latest.runId);
    }

    @Test
    public void readIndexReturnsSummariesMostRecentFirst() throws Exception {
        File runsDir = temp.newFolder("runs");
        RunRecord older = record("OLD", "SpatialAnalysis", 1_000_000_000_000L);
        RunRecord newer = record("NEW", "ThreeDObjectAnalysis", 2_000_000_000_000L);
        RunRecordIO.writeSnapshot(RunRecordIO.runFile(runsDir, older), older);
        RunRecordIO.writeSnapshot(RunRecordIO.runFile(runsDir, newer), newer);

        List<RunSummary> index = RunRecordIO.readIndex(runsDir);

        assertEquals(2, index.size());
        assertEquals("NEW", index.get(0).runId);
        assertEquals("OLD", index.get(1).runId);
        assertEquals("ThreeDObjectAnalysis", index.get(0).analysis);
    }

    @Test
    public void existsMatchesByRunId() throws Exception {
        File runsDir = temp.newFolder("runs");
        RunRecord record = record("FINDME", "SpatialAnalysis", 1_716_912_000_000L);
        RunRecordIO.writeSnapshot(RunRecordIO.runFile(runsDir, record), record);

        assertTrue(RunRecordIO.exists(runsDir, "FINDME"));
        assertFalse(RunRecordIO.exists(runsDir, "NOPE"));
    }

    @Test
    public void readSnapshotsOnMissingFileIsEmpty() {
        File missing = new File(temp.getRoot(), "runs/does-not-exist.jsonl");
        assertTrue(RunRecordIO.readSnapshots(missing).isEmpty());
        assertNull(RunRecordIO.readLatest(missing));
    }

    @Test
    public void readIndexOnMissingDirIsEmpty() {
        File missing = new File(temp.getRoot(), "no-such-runs-dir");
        assertTrue(RunRecordIO.readIndex(missing).isEmpty());
    }
}
