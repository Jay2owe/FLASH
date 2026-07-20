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
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
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

    private static final class ResultCollector
            implements AnalysisRunCoordinator.TerminalResultEmitter {
        final List<RunResult> results = new ArrayList<RunResult>();

        @Override
        public void emit(RunResult result) {
            results.add(result);
        }

        RunResult onlyResult() {
            assertEquals("one terminal result must be emitted", 1, results.size());
            return results.get(0);
        }
    }

    private AnalysisRunCoordinator newCoordinator(final BinSetupDispatcher.Outcome outcome) {
        return newCoordinator(outcome, "");
    }

    private AnalysisRunCoordinator newCoordinator(final BinSetupDispatcher.Outcome outcome,
                                                  final String reason) {
        AnalysisRunCoordinator coordinator = new AnalysisRunCoordinator();
        coordinator.setWriteLegacyAuditForTests(false);
        coordinator.setBinOutcomeProviderForTests(new AnalysisRunCoordinator.BinOutcomeProvider() {
            @Override
            public BinSetupDispatcher.Outcome lastOutcome() {
                return outcome;
            }

            @Override
            public String lastReason() {
                return reason;
            }
        });
        return coordinator;
    }

    private ResultCollector collectResults(AnalysisRunCoordinator coordinator) {
        ResultCollector collector = new ResultCollector();
        coordinator.setTerminalResultEmitterForTests(collector);
        return collector;
    }

    private File runsDir(File projectRoot) {
        return FlashProjectLayout.forDirectory(projectRoot.getAbsolutePath()).runJsonlWriteDir();
    }

    @Test
    public void recordsThrownFailureAndRethrows() throws Exception {
        File project = temp.newFolder("project");
        AnalysisRunCoordinator coordinator = newCoordinator(BinSetupDispatcher.Outcome.COMPLETED);
        ResultCollector collector = collectResults(coordinator);

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
            assertSame("the original runtime failure must propagate", boom, e);
        }

        RunResult result = collector.onlyResult();
        assertEquals(RunResult.TerminalState.FAILED, result.terminalState);
        assertSame(boom, result.cause);
        assertFalse(result.bodyCompleted());
        assertFalse(result.isSuccess());
        assertTrue(result.recordPublished());

        List<RunRecord> records = RunRecordIO.readSnapshots(latestRecordFile(project));
        assertTrue(!records.isEmpty());
        assertEquals("failed", records.get(records.size() - 1).status);
    }

    @Test
    public void recordsSetupCancellationAsCancelled() throws Exception {
        File project = temp.newFolder("project");
        AnalysisRunCoordinator coordinator = newCoordinator(
                BinSetupDispatcher.Outcome.CANCELLED, "User cancelled channel setup.");
        ResultCollector collector = collectResults(coordinator);

        RunResult result = coordinator.run(new NoopAnalysis(), 0, "Set Up Configuration",
                project.getAbsolutePath(), null, null, "", new Callable<Void>() {
                    @Override
                    public Void call() {
                        return null;
                    }
                });

        assertSame(result, collector.onlyResult());
        assertEquals(RunResult.TerminalState.CANCELLED, result.terminalState);
        assertEquals(RunResult.STATUS_CANCELLED, result.status);
        assertEquals("User cancelled channel setup.", result.reason);
        assertFalse(result.bodyCompleted());
        assertFalse(result.isSuccess());
        assertTrue(result.recordPublished());
        RunRecord record = RunRecordIO.readLatest(latestRecordFile(project));
        assertEquals("warn", record.status);
    }

    @Test
    public void recordsMissingSetupAsBlockedWithActionableReason() throws Exception {
        File project = temp.newFolder("project");
        AnalysisRunCoordinator coordinator = newCoordinator(
                BinSetupDispatcher.Outcome.BLOCKED,
                "Cannot run Spatial: missing parameter `channel_names`.");
        ResultCollector collector = collectResults(coordinator);

        RunResult result = coordinator.run(new NoopAnalysis(), 5, "Spatial",
                project.getAbsolutePath(), null, null, "", new Callable<Void>() {
                    @Override
                    public Void call() {
                        return null;
                    }
                });

        assertSame(result, collector.onlyResult());
        assertEquals(RunResult.TerminalState.BLOCKED, result.terminalState);
        assertEquals(RunResult.STATUS_BLOCKED, result.status);
        assertTrue(result.reason.contains("channel_names"));
        assertFalse(result.bodyCompleted());
        assertFalse(result.isSuccess());
        assertTrue(result.recordPublished());
    }

    @Test
    public void guiCancellationWithoutOutputsDiscardsRunRecord() throws Exception {
        File project = temp.newFolder("project");
        AnalysisRunCoordinator coordinator = newCoordinator(BinSetupDispatcher.Outcome.COMPLETED);
        ResultCollector collector = collectResults(coordinator);
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
            assertSame(result, collector.onlyResult());
            assertEquals(RunResult.TerminalState.CANCELLED, result.terminalState);
            assertFalse(result.bodyCompleted());
            assertFalse(result.isSuccess());
            assertEquals(RunResult.PublicationState.NOT_REQUIRED, result.publicationState);
            assertEquals("", result.runId);
            assertNull(result.recordFile);
            assertTrue("cancelled setup should not create run records",
                    !runsDir(project).exists());
        } finally {
            scope.close();
        }
    }

    @Test
    public void guiCancellationAfterOutputsKeepsRecordButIsNotCompletion() throws Exception {
        File project = temp.newFolder("project");
        final File output = temp.newFile("partial-output.txt");
        AnalysisRunCoordinator coordinator = newCoordinator(BinSetupDispatcher.Outcome.COMPLETED);
        final RecordingAnalysis analysis = new RecordingAnalysis();
        ResultCollector collector = collectResults(coordinator);
        AnalysisCancellation.Scope scope = AnalysisCancellation.openGuiAnalysisScope();
        try {
            RunResult result = coordinator.run(analysis, 4, "3D Object Analysis",
                    project.getAbsolutePath(), null, null, "", new Callable<Void>() {
                        @Override
                        public Void call() {
                            analysis.current.recordOutput(output, "txt");
                            AnalysisCancellation.markDialogCancelRequested();
                            return null;
                        }
                    });

            assertSame(result, collector.onlyResult());
            assertEquals(RunResult.STATUS_CANCELLED, result.status);
            assertEquals(RunResult.TerminalState.CANCELLED, result.terminalState);
            assertFalse(result.bodyCompleted());
            assertFalse(result.isSuccess());
            assertTrue(result.recordPublished());
            RunRecord record = RunRecordIO.readLatest(latestRecordFile(project));
            assertEquals("warn", record.status);
            assertEquals(1, record.outputs.size());
            assertTrue(record.messages.get(record.messages.size() - 1).text
                    .contains("cancelled"));
        } finally {
            scope.close();
        }
    }

    @Test
    public void successfulRunRecordsOk() throws Exception {
        File project = temp.newFolder("project");
        AnalysisRunCoordinator coordinator = newCoordinator(BinSetupDispatcher.Outcome.COMPLETED);
        ResultCollector collector = collectResults(coordinator);

        RunResult result = coordinator.run(new NoopAnalysis(), 5, "Spatial",
                project.getAbsolutePath(), null, null, "", new Callable<Void>() {
                    @Override
                    public Void call() {
                        return null;
                    }
                });

        assertEquals("ok", result.status);
        assertSame(result, collector.onlyResult());
        assertEquals(RunResult.TerminalState.COMPLETED, result.terminalState);
        assertTrue(result.bodyCompleted());
        assertTrue(result.isSuccess());
        assertTrue(result.recordPublished());
        assertEquals(26, result.runId.length());
    }

    @Test
    public void recoverableWarningAfterBodyCompletionIsCompletedWithWarnings() throws Exception {
        File project = temp.newFolder("project");
        AnalysisRunCoordinator coordinator = newCoordinator(BinSetupDispatcher.Outcome.COMPLETED);
        ResultCollector collector = collectResults(coordinator);
        final RecordingAnalysis analysis = new RecordingAnalysis();

        RunResult result = coordinator.run(analysis, 5, "Spatial",
                project.getAbsolutePath(), null, null, "", new Callable<Void>() {
                    @Override
                    public Void call() {
                        analysis.current.warn("Optional preview could not be written.");
                        return null;
                    }
                });

        assertSame(result, collector.onlyResult());
        assertEquals(RunResult.TerminalState.COMPLETED_WITH_WARNINGS,
                result.terminalState);
        assertEquals(RunResult.STATUS_WARN, result.status);
        assertTrue(result.bodyCompleted());
        assertTrue(result.isSuccess());
        assertTrue(result.hasWarnings());
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

    @Test
    public void publicationFailureCannotProduceSuccessfulResult() throws Exception {
        File project = temp.newFolder("project");
        AnalysisRunCoordinator coordinator = newCoordinator(BinSetupDispatcher.Outcome.COMPLETED);
        ResultCollector collector = collectResults(coordinator);
        final IOException publicationFailure = new IOException("disk full");
        coordinator.setRecordPublicationVerifierForTests(
                new AnalysisRunCoordinator.RecordPublicationVerifier() {
                    @Override
                    public void verify(String expectedRunId, File recordFile) throws Exception {
                        assertTrue(recordFile.delete());
                        throw publicationFailure;
                    }
                });

        RuntimeException thrown = null;
        try {
            coordinator.run(new NoopAnalysis(), 5, "Spatial", project.getAbsolutePath(),
                    null, null, "", new Callable<Void>() {
                        @Override
                        public Void call() {
                            return null;
                        }
                    });
            fail("required publication failure must propagate");
        } catch (RuntimeException expected) {
            thrown = expected;
        }

        RunResult result = collector.onlyResult();
        assertSame(thrown, result.cause);
        assertSame(publicationFailure, thrown.getCause());
        assertEquals(RunResult.TerminalState.FAILED, result.terminalState);
        assertEquals(RunResult.PublicationState.FAILED, result.publicationState);
        assertNull("an unacknowledged record must not be advertised", result.recordFile);
        assertNotNull(result.intendedRecordFile);
        assertFalse(result.bodyCompleted());
        assertFalse(result.isSuccess());
        assertFalse(result.recordPublished());
    }

    @Test
    public void bodyFailureRemainsPrimaryWhenPublicationAlsoFails() throws Exception {
        File project = temp.newFolder("project");
        AnalysisRunCoordinator coordinator = newCoordinator(BinSetupDispatcher.Outcome.COMPLETED);
        ResultCollector collector = collectResults(coordinator);
        final RuntimeException bodyFailure = new RuntimeException("body failed");
        final IOException publicationFailure = new IOException("record failed");
        coordinator.setRecordPublicationVerifierForTests(
                new AnalysisRunCoordinator.RecordPublicationVerifier() {
                    @Override
                    public void verify(String expectedRunId, File recordFile) throws Exception {
                        throw publicationFailure;
                    }
                });

        try {
            coordinator.run(new NoopAnalysis(), 5, "Spatial", project.getAbsolutePath(),
                    null, null, "", new Callable<Void>() {
                        @Override
                        public Void call() {
                            throw bodyFailure;
                        }
                    });
            fail("body failure must propagate");
        } catch (RuntimeException expected) {
            assertSame(bodyFailure, expected);
        }

        RunResult result = collector.onlyResult();
        assertSame(bodyFailure, result.cause);
        assertEquals(1, bodyFailure.getSuppressed().length);
        assertSame(publicationFailure, bodyFailure.getSuppressed()[0]);
        assertEquals(RunResult.PublicationState.FAILED, result.publicationState);
    }

    @Test
    public void resultConstructionRejectsContradictoryStateAndPublicationTruth()
            throws Exception {
        final File record = temp.newFile("record.jsonl");

        try {
            new RunResult(RunResult.TerminalState.COMPLETED,
                    RunResult.PublicationState.FAILED, "run-1", null, record,
                    false, "", new IOException("failed"));
            fail("completed result must not claim publication failure");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("publication failure"));
        }

        try {
            RunResult.blocked("run-2", record, "", false);
            fail("blocked result must name an actionable reason");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("actionable reason"));
        }

        try {
            new RunResult(RunResult.TerminalState.FAILED,
                    RunResult.PublicationState.NOT_REQUIRED, "", record, null,
                    false, "", new IOException("failed"));
            fail("unacknowledged record must not be exposed");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("recordFile"));
        }
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
