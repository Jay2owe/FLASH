package flash.pipeline.execution;

import flash.pipeline.analyses.Analysis;
import flash.pipeline.bin.BinSetupDispatcher;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.runrecord.AnalysisRunContext;
import flash.pipeline.runrecord.RunRecord;
import flash.pipeline.runrecord.RunRecordAware;
import flash.pipeline.runrecord.RunRecordIO;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AnalysisRunCoordinatorTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static final class NoopAnalysis implements Analysis {
        @Override
        public void execute(String directory) {
        }
    }

    private static final class RecordingAnalysis implements Analysis, RunRecordAware {
        AnalysisRunContext current;
        AnalysisRunContext seenDuringBody;

        @Override
        public void execute(String directory) {
        }

        @Override
        public void setRunRecordContext(AnalysisRunContext context) {
            this.current = context;
        }
    }

    private AnalysisRunCoordinator newCoordinator(final BinSetupDispatcher.Outcome outcome) {
        AnalysisRunCoordinator coordinator = new AnalysisRunCoordinator();
        coordinator.setWriteLegacyAuditForTests(false);
        coordinator.setBinOutcomeProviderForTests(new AnalysisRunCoordinator.BinOutcomeProvider() {
            @Override
            public BinSetupDispatcher.Outcome lastOutcome() {
                return outcome;
            }
        });
        return coordinator;
    }

    private File runsDir(File projectRoot) {
        return FlashProjectLayout.forDirectory(projectRoot.getAbsolutePath()).runJsonlWriteDir();
    }

    @Test
    public void recordsThrownFailureAndRethrows() throws Exception {
        File project = temp.newFolder("project");
        AnalysisRunCoordinator coordinator = newCoordinator(BinSetupDispatcher.Outcome.COMPLETED);

        RuntimeException boom = new RuntimeException("kaboom");
        try {
            coordinator.run(new NoopAnalysis(), 4, "3D Object Analysis", project.getAbsolutePath(),
                    null, null, "", new Callable<Void>() {
                        @Override
                        public Void call() {
                            throw boom;
                        }
                    });
            fail("expected the original exception to propagate");
        } catch (RuntimeException e) {
            assertEquals("kaboom", e.getMessage());
        }

        List<RunRecord> records = RunRecordIO.readSnapshots(latestRecordFile(project));
        assertTrue(!records.isEmpty());
        assertEquals("failed", records.get(records.size() - 1).status);
    }

    @Test
    public void recordsCancellationAsWarn() throws Exception {
        File project = temp.newFolder("project");
        AnalysisRunCoordinator coordinator = newCoordinator(BinSetupDispatcher.Outcome.CANCELLED);

        RunResult result = coordinator.run(new NoopAnalysis(), 0, "Set Up Configuration",
                project.getAbsolutePath(), null, null, "", new Callable<Void>() {
                    @Override
                    public Void call() {
                        return null;
                    }
                });

        assertEquals("warn", result.status);
        RunRecord record = RunRecordIO.readLatest(latestRecordFile(project));
        assertEquals("warn", record.status);
    }

    @Test
    public void guiCancellationWithoutOutputsDiscardsRunRecord() throws Exception {
        File project = temp.newFolder("project");
        AnalysisRunCoordinator coordinator = newCoordinator(BinSetupDispatcher.Outcome.COMPLETED);
        AnalysisCancellation.Scope scope = AnalysisCancellation.openGuiAnalysisScope();
        try {
            RunResult result = coordinator.run(new NoopAnalysis(), 0, "Set Up Configuration",
                    project.getAbsolutePath(), null, null, "", new Callable<Void>() {
                        @Override
                        public Void call() {
                            AnalysisCancellation.markDialogCancelRequested();
                            return null;
                        }
                    });

            assertEquals(RunResult.STATUS_CANCELLED, result.status);
            assertEquals("", result.runId);
            assertNull(result.recordFile);
            assertTrue("cancelled setup should not create run records",
                    !runsDir(project).exists());
        } finally {
            scope.close();
        }
    }

    @Test
    public void successfulRunRecordsOk() throws Exception {
        File project = temp.newFolder("project");
        AnalysisRunCoordinator coordinator = newCoordinator(BinSetupDispatcher.Outcome.COMPLETED);

        RunResult result = coordinator.run(new NoopAnalysis(), 5, "Spatial",
                project.getAbsolutePath(), null, null, "", new Callable<Void>() {
                    @Override
                    public Void call() {
                        return null;
                    }
                });

        assertEquals("ok", result.status);
        assertEquals(26, result.runId.length());
    }

    @Test
    public void runRecordAwareContextSetDuringBodyAndClearedAfter() throws Exception {
        File project = temp.newFolder("project");
        AnalysisRunCoordinator coordinator = newCoordinator(BinSetupDispatcher.Outcome.COMPLETED);
        final RecordingAnalysis analysis = new RecordingAnalysis();

        coordinator.run(analysis, 5, "Spatial", project.getAbsolutePath(), null, null, "",
                new Callable<Void>() {
                    @Override
                    public Void call() {
                        analysis.seenDuringBody = analysis.current;
                        return null;
                    }
                });

        assertNotNull("context must be set during execution", analysis.seenDuringBody);
        assertNull("context must be cleared after execution", analysis.current);
    }

    private File latestRecordFile(File project) {
        File dir = runsDir(project);
        File[] files = dir.listFiles();
        assertNotNull("runs dir should exist", files);
        File latest = null;
        for (File f : files) {
            if (f.getName().endsWith(RunRecordIO.EXTENSION)) {
                latest = f;
            }
        }
        assertNotNull("a run record file should exist", latest);
        return latest;
    }
}
