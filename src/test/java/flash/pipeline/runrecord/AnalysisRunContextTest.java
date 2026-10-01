package flash.pipeline.runrecord;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import flash.pipeline.io.FlashProjectLayout;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AnalysisRunContextTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File projectRoot() throws Exception {
        return temp.newFolder("project");
    }

    private File realFile(String name) throws Exception {
        File f = new File(temp.getRoot(), name);
        Files.write(f.toPath(), ("data-" + name).getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private static AnalysisRunContext open(File projectRoot) {
        return AnalysisRunContext.open("ThreeDObjectAnalysis", 4, "3D Object Analysis",
                projectRoot.getAbsolutePath(), null, new LinkedHashMap<String, Object>(), "");
    }

    @Test
    public void openRetainsRunningCheckpointBeforeClose() throws Exception {
        AnalysisRunContext context = open(projectRoot());
        assertTrue("record checkpoint must exist before execution", context.recordFile().exists());
        RunRecord checkpoint = RunRecordIO.readLatest(context.recordFile());
        assertEquals(RunRecord.STATUS_RUNNING, checkpoint.status);
        assertEquals(0L, checkpoint.finishedAtMillis);
        context.close();
        RunRecord completed = RunRecordIO.readLatest(context.recordFile());
        assertEquals(RunRecord.STATUS_OK, completed.status);
        assertTrue(completed.finishedAtMillis > 0L);
    }

    @Test
    public void closeLeavesNoTempFile() throws Exception {
        AnalysisRunContext context = open(projectRoot());
        context.close();
        File runsDir = context.recordFile().getParentFile();
        File[] files = runsDir.listFiles();
        assertNotNull(files);
        for (File f : files) {
            assertFalse("no .tmp leftover: " + f.getName(), f.getName().endsWith(".tmp"));
        }
    }

    @Test
    public void inputsAndOutputsAppearInRecord() throws Exception {
        File projectRoot = projectRoot();
        File input = realFile("in.lif");
        File output = realFile("out.csv");
        AnalysisRunContext context = open(projectRoot);

        AnalysisRunContext.InputHandle handle = context.recordInputStart(input, 1, null);
        context.recordInputEnd(handle, "processed", 1234L);
        context.recordOutput(output, "csv");
        context.close();

        RunRecord record = RunRecordIO.readLatest(context.recordFile());
        assertNotNull(record);
        assertEquals(1, record.inputs.size());
        assertEquals(input.getAbsolutePath(), record.inputs.get(0).path);
        assertEquals(1, record.inputs.get(0).seriesIndex);
        assertEquals("processed", record.inputs.get(0).status);
        assertEquals(1234L, record.inputs.get(0).durationMillis);
        assertTrue("fast fingerprint captured", !record.inputs.get(0).fingerprint.isEmpty());
        assertEquals(1, record.outputs.size());
        assertEquals("csv", record.outputs.get(0).kind);
    }

    @Test
    public void errorMarksStatusFailed() throws Exception {
        AnalysisRunContext context = open(projectRoot());
        context.error("boom", new RuntimeException("kaboom"));
        context.close();

        RunRecord record = RunRecordIO.readLatest(context.recordFile());
        assertEquals("failed", record.status);
        boolean found = false;
        for (RunRecord.Message m : record.messages) {
            if ("error".equals(m.level) && m.text.contains("boom")) {
                found = true;
            }
        }
        assertTrue("error message captured", found);
    }

    @Test
    public void warnMarksStatusWarn() throws Exception {
        AnalysisRunContext context = open(projectRoot());
        context.warn("one image skipped");
        context.close();

        RunRecord record = RunRecordIO.readLatest(context.recordFile());
        assertEquals("warn", record.status);
    }

    @Test
    public void missingInputFingerprintMarksStatusWarn() throws Exception {
        AnalysisRunContext context = open(projectRoot());
        context.recordInputStart(new File(temp.getRoot(), "missing-input.tif"), 0, null);
        context.close();

        RunRecord record = RunRecordIO.readLatest(context.recordFile());
        assertEquals("warn", record.status);
        assertEquals("", record.inputs.get(0).fingerprint);
        assertTrue(record.messages.stream().anyMatch(m -> "warn".equals(m.level)));
    }

    @Test
    public void missingOutputFingerprintMarksStatusWarnWithoutMaskingFailure() throws Exception {
        File root = projectRoot();
        AnalysisRunContext context = open(root);
        context.recordOutput(new File(temp.getRoot(), "missing-output.csv"), "csv");
        context.close();
        assertEquals("warn", RunRecordIO.readLatest(context.recordFile()).status);

        AnalysisRunContext failed = open(root);
        failed.error("measurement failed", new RuntimeException("measurement failed"));
        failed.recordOutput(new File(temp.getRoot(), "missing-failed-output.csv"), "csv");
        failed.close();
        assertEquals("failed", RunRecordIO.readLatest(failed.recordFile()).status);
    }

    @Test
    public void closeIsIdempotent() throws Exception {
        AnalysisRunContext context = open(projectRoot());
        context.close();
        context.close();
        assertEquals("second close must not append a second snapshot",
                2, RunRecordIO.readSnapshots(context.recordFile()).size());
    }

    @Test
    public void nullProjectWritesEmptyHash() throws Exception {
        AnalysisRunContext context = AnalysisRunContext.open("SpatialAnalysis", 5, "Spatial",
                projectRoot().getAbsolutePath(), null, null, "");
        context.close();

        RunRecord record = RunRecordIO.readLatest(context.recordFile());
        assertEquals("", record.projectFileHash);
    }

    @Test
    public void runIdIsUlidShapedAndSortable() throws Exception {
        File projectRoot = projectRoot();
        AnalysisRunContext first = open(projectRoot);
        AnalysisRunContext second = open(projectRoot);
        try {
            assertEquals(26, first.runId().length());
            assertEquals(26, second.runId().length());
            assertTrue("later run id sorts after earlier",
                    second.runId().compareTo(first.runId()) > 0);
        } finally {
            first.close();
            second.close();
        }
    }

    @Test
    public void parametersRoundTripIntoRecord() throws Exception {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("skipExisting", Boolean.TRUE);
        params.put("note", "hello");
        AnalysisRunContext context = AnalysisRunContext.open("IntensityAnalysisV2", 7, "Intensity",
                projectRoot().getAbsolutePath(), null, params, "");
        context.close();

        RunRecord record = RunRecordIO.readLatest(context.recordFile());
        assertEquals(Boolean.TRUE, record.parameters.get("skipExisting"));
        assertEquals("hello", record.parameters.get("note"));
    }

    @Test
    public void recordParametersMergesAfterOpenAndPersists() throws Exception {
        // Interactive GUI runs open with an empty parameter map and capture the
        // confirmed settings during the run via recordParameters().
        AnalysisRunContext context = open(projectRoot());
        Map<String, Object> first = new LinkedHashMap<String, Object>();
        first.put("doVolumetric", Boolean.TRUE);
        first.put("name", "GUI 3D Object run");
        context.recordParameters(first);
        // Later keys override earlier ones; null/empty maps are no-ops.
        Map<String, Object> override = new LinkedHashMap<String, Object>();
        override.put("doVolumetric", Boolean.FALSE);
        context.recordParameters(override);
        context.recordParameters(null);
        context.recordParameters(new LinkedHashMap<String, Object>());
        context.close();

        RunRecord record = RunRecordIO.readLatest(context.recordFile());
        assertEquals(Boolean.FALSE, record.parameters.get("doVolumetric"));
        assertEquals("GUI 3D Object run", record.parameters.get("name"));
    }

    @Test
    public void recordParametersAfterCloseIsIgnored() throws Exception {
        AnalysisRunContext context = open(projectRoot());
        context.close();
        Map<String, Object> late = new LinkedHashMap<String, Object>();
        late.put("late", Boolean.TRUE);
        context.recordParameters(late);

        RunRecord record = RunRecordIO.readLatest(context.recordFile());
        assertFalse("parameters recorded after close must be ignored",
                record.parameters.containsKey("late"));
    }

    @Test
    public void checkpointAtInputStartIncludesConfirmedSettingsAndConfiguration() throws Exception {
        File root = projectRoot();
        AnalysisRunContext context = open(root);
        File config = new File(FlashProjectLayout.forDirectory(root.getAbsolutePath())
                .configurationWriteDir(), "channel-settings.json");
        Files.createDirectories(config.getParentFile().toPath());
        Files.write(config.toPath(), "settings chosen during setup".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> chosen = new LinkedHashMap<String, Object>();
        chosen.put("minimumSize", 25);
        context.recordParameters(chosen);
        AnalysisRunContext.InputHandle input = context.recordInputStart(realFile("setup.tif"), 1, null);

        RunRecord checkpoint = RunRecordIO.readLatest(context.recordFile());
        assertEquals(RunRecord.STATUS_RUNNING, checkpoint.status);
        assertEquals(25, ((Number) checkpoint.parameters.get("minimumSize")).intValue());
        assertEquals(ConfigurationSnapshot.capture(root), checkpoint.extras.get(ConfigurationSnapshot.EXTRA_KEY));
        assertEquals(1, checkpoint.inputs.size());
        context.recordInputEnd(input, "processed", 50);
        assertEquals("processed", RunRecordIO.readLatest(context.recordFile()).inputs.get(0).status);
        context.close();
    }

    @Test
    public void outputCheckpointSurvivesBeforeTerminalCloseAndCapturesNoInputConfiguration() throws Exception {
        File root = projectRoot();
        AnalysisRunContext context = open(root);
        context.recordOutput(realFile("interrupted.csv"), "csv");
        RunRecord checkpoint = RunRecordIO.readLatest(context.recordFile());
        assertEquals(RunRecord.STATUS_RUNNING, checkpoint.status);
        assertEquals(1, checkpoint.outputs.size());
        assertEquals(ConfigurationSnapshot.capture(root), checkpoint.extras.get(ConfigurationSnapshot.EXTRA_KEY));
        context.close();
    }

    @Test
    public void noInputRunStillCapturesConfigurationAtClose() throws Exception {
        File root = projectRoot();
        AnalysisRunContext context = open(root);
        context.close();
        assertEquals(ConfigurationSnapshot.capture(root), RunRecordIO.readLatest(context.recordFile())
                .extras.get(ConfigurationSnapshot.EXTRA_KEY));
    }

    @Test
    public void discardRemovesOnlyItsOwnRunningRecord() throws Exception {
        File root = projectRoot();
        AnalysisRunContext retained = open(root);
        retained.close();
        AnalysisRunContext cancelled = open(root);
        File cancelledRecord = cancelled.recordFile();
        cancelled.discard();
        cancelled.discard();
        cancelled.close();
        assertFalse(cancelledRecord.exists());
        assertTrue(retained.recordFile().isFile());
        assertEquals(RunRecord.STATUS_OK, RunRecordIO.readLatest(retained.recordFile()).status);
    }
}
