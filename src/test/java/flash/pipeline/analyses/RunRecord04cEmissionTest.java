package flash.pipeline.analyses;

import flash.pipeline.execution.AnalysisRunCoordinator;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.runrecord.RunRecord;
import flash.pipeline.runrecord.RunRecordIO;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class RunRecord04cEmissionTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void threeDObjectCoordinatorRunEmitsWarnOrFailedRecordOnEmptyProject() throws Exception {
        File project = temp.newFolder("three-d-object-runrecord");
        final ThreeDObjectAnalysis analysis = new ThreeDObjectAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);

        RunRecord record = runAndRead(project, analysis, 4, "3D Object Analysis");

        assertEquals("ThreeDObjectAnalysis", record.analysis);
        assertWarnOrFailed(record);
    }

    @Test
    public void spatialCoordinatorRunEmitsWarnOrFailedRecordOnEmptyProject() throws Exception {
        File project = temp.newFolder("spatial-runrecord");
        final SpatialAnalysis analysis = new SpatialAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);

        RunRecord record = runAndRead(project, analysis, 5, "Spatial Analysis");

        assertEquals("SpatialAnalysis", record.analysis);
        assertWarnOrFailed(record);
    }

    @Test
    public void lineDistanceCoordinatorRunEmitsHeadlessCommandWarningRecord() throws Exception {
        File project = temp.newFolder("line-distance-runrecord");
        final LineDistanceAnalysis analysis = new LineDistanceAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setCommandMode(true);

        RunRecord record = runAndRead(project, analysis, 6, "Line Distance Analysis");

        assertEquals("LineDistanceAnalysis", record.analysis);
        assertEquals("warn", record.status);
        assertHasMessageContaining(record, "cannot draw new lines");
        assertNotNull(record.inputs);
        assertNotNull(record.outputs);
    }

    @Test
    public void intensityCoordinatorRunEmitsWarnOrFailedRecordOnEmptyProject() throws Exception {
        File project = temp.newFolder("intensity-runrecord");
        final IntensityAnalysisV2 analysis = new IntensityAnalysisV2();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);

        RunRecord record = runAndRead(project, analysis, 7, "Fluorescence Intensity Analysis");

        assertEquals("IntensityAnalysisV2", record.analysis);
        assertWarnOrFailed(record);
    }

    private static RunRecord runAndRead(final File project,
                                        final Analysis analysis,
                                        int index,
                                        String label) {
        try {
            new AnalysisRunCoordinator().run(analysis, index, label,
                    project.getAbsolutePath(), null, null, "", new Callable<Void>() {
                        @Override public Void call() {
                            analysis.execute(project.getAbsolutePath());
                            return null;
                        }
                    });
        } catch (RuntimeException expectedForEmptySyntheticProject) {
            // Empty synthetic projects legitimately fail before user data exists.
        }
        return latestRecord(project);
    }

    private static void assertWarnOrFailed(RunRecord record) {
        assertTrue("real analysis on empty project should warn or fail",
                "warn".equals(record.status) || "failed".equals(record.status));
        assertNotNull(record.messages);
        assertTrue("expected at least one run-record message", !record.messages.isEmpty());
        assertNotNull(record.inputs);
        assertNotNull(record.outputs);
    }

    private static void assertHasMessageContaining(RunRecord record, String fragment) {
        assertNotNull(record.messages);
        for (RunRecord.Message message : record.messages) {
            if (message.text != null && message.text.contains(fragment)) {
                return;
            }
        }
        throw new AssertionError("expected run-record message containing: " + fragment);
    }

    private static RunRecord latestRecord(File project) {
        File runsDir = FlashProjectLayout.forDirectory(project.getAbsolutePath()).runJsonlWriteDir();
        File[] files = runsDir.listFiles();
        assertNotNull("runs dir should exist", files);
        File latest = null;
        for (File f : files) {
            if (f.getName().endsWith(RunRecordIO.EXTENSION)) {
                latest = f;
            }
        }
        assertNotNull("run record should exist", latest);
        List<RunRecord> records = RunRecordIO.readSnapshots(latest);
        assertTrue(!records.isEmpty());
        return records.get(records.size() - 1);
    }
}
