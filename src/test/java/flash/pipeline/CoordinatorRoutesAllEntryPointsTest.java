package flash.pipeline;

import flash.pipeline.analyses.Analysis;
import flash.pipeline.bin.BinSetupDispatcher;
import flash.pipeline.execution.AnalysisRunCoordinator;
import flash.pipeline.execution.RunResult;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.runrecord.RunRecord;
import flash.pipeline.runrecord.RunRecordIO;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.concurrent.Callable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CoordinatorRoutesAllEntryPointsTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void guiHelperProducesRunRecordThroughCoordinator() throws Exception {
        File project = temp.newFolder("gui-route");
        BinSetupDispatcher.clearLastFieldSources();
        NoopAnalysis analysis = new NoopAnalysis();

        boolean completed = new FLASH_Pipeline().executeAnalysisSafelyForGui(
                analysis, FLASH_Pipeline.IDX_STATISTICS, project.getAbsolutePath());

        assertTrue(completed);
        RunRecord record = latestRecord(project);
        assertEquals("NoopAnalysis", record.analysis);
        assertEquals("ok", record.status);
        assertEquals(26, record.runId.length());
    }

    @Test
    public void cliPathProducesRunRecordThroughCoordinator() throws Exception {
        File project = temp.newFolder("cli-route");
        RunResult result = runStubThroughCoordinator(project, FLASH_Pipeline.IDX_CREATE_BIN,
                "Set Up Configuration");

        RunRecord record = latestRecord(project);
        assertEquals("ok", result.status);
        assertEquals(26, result.runId.length());
        assertEquals("NoopAnalysis", record.analysis);
        assertEquals("ok", record.status);
        assertEquals(26, record.runId.length());
    }

    @Test
    public void commandPathProducesRunRecordThroughCoordinator() throws Exception {
        File project = temp.newFolder("command-route");
        RunResult result = runStubThroughCoordinator(project, FLASH_Pipeline.IDX_DRAW_ROIS,
                "Draw and Save ROIs");

        RunRecord record = latestRecord(project);
        assertEquals("ok", result.status);
        assertEquals(26, result.runId.length());
        assertEquals("NoopAnalysis", record.analysis);
        assertEquals("ok", record.status);
        assertEquals(26, record.runId.length());
    }

    private static final class NoopAnalysis implements Analysis {
        @Override
        public void execute(String directory) {
        }
    }

    private static RunResult runStubThroughCoordinator(File project, int index, String label) {
        BinSetupDispatcher.clearLastFieldSources();
        final NoopAnalysis analysis = new NoopAnalysis();
        return new AnalysisRunCoordinator().run(analysis, index, label,
                project.getAbsolutePath(), null, null, "", new Callable<Void>() {
                    @Override public Void call() {
                        analysis.execute(project.getAbsolutePath());
                        return null;
                    }
                });
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
        return RunRecordIO.readLatest(latest);
    }
}
