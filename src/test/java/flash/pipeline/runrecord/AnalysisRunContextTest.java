package flash.pipeline.runrecord;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

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
    public void openWritesNothingUntilClose() throws Exception {
        AnalysisRunContext context = open(projectRoot());
        assertFalse("record file must not exist before close", context.recordFile().exists());
        context.close();
        assertTrue("record file appears after close", context.recordFile().exists());
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
    public void closeIsIdempotent() throws Exception {
        AnalysisRunContext context = open(projectRoot());
        context.close();
        context.close();
        assertEquals("second close must not append a second snapshot",
                1, RunRecordIO.readSnapshots(context.recordFile()).size());
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
}
