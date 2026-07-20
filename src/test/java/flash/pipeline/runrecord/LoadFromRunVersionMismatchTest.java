package flash.pipeline.runrecord;

import flash.pipeline.runrecord.ui.LoadFromRunButton;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.JButton;
import java.awt.Component;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LoadFromRunVersionMismatchTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @After
    public void resetSelectionProvider() {
        LoadFromRunButton.setSelectionProviderForTests(null);
    }

    @Test
    public void versionMismatchStillAppliesKnownKeysAndReportsIgnoredKeys() throws Exception {
        File projectRoot = temp.newFolder("project");
        final RunRecord record = runRecord(projectRoot, "ThreeDObjectAnalysis");
        record.flashVersion = "0.0.0-test";
        record.parameters.put("doVolumetric", Boolean.TRUE);
        record.parameters.put("futureRenamedKey", "ignored");
        write(record);

        final AtomicReference<LoadedRunParameters.Result> resultRef =
                new AtomicReference<LoadedRunParameters.Result>();
        LoadFromRunButton.setSelectionProviderForTests(
                new LoadFromRunButton.RunSelectionProvider() {
                    @Override public RunRecord choose(Component owner,
                                                      String analysisKey,
                                                      File root,
                                                      List<RunSummary> summaries) {
                        assertEquals("ThreeDObjectAnalysis", analysisKey);
                        assertEquals(1, summaries.size());
                        return record;
                    }
                });

        JButton button = LoadFromRunButton.create("ThreeDObjectAnalysis", projectRoot,
                new LoadedRunParameterApplier() {
                    @Override public LoadedRunParameters.Result applyLoadedParameters(
                            Map<String, Object> parameters) {
                        LoadedRunParameters.PresetLoad<?> loaded =
                                LoadedRunParameters.threeDObjectPreset(parameters);
                        resultRef.set(loaded.result);
                        LoadedRunParameters.rememberLastResult(loaded.result);
                        return loaded.result;
                    }
                });

        button.doClick();

        LoadedRunParameters.Result result = resultRef.get();
        assertTrue(result.getAppliedKeys().contains("doVolumetric"));
        assertTrue(result.getIgnoredKeys().contains("futureRenamedKey"));
    }

    private static RunRecord runRecord(File projectRoot, String analysis) {
        RunRecord record = new RunRecord();
        record.runId = "run-version";
        record.startedAtMillis = System.currentTimeMillis();
        record.finishedAtMillis = record.startedAtMillis + 1L;
        record.status = RunRecord.STATUS_OK;
        record.analysis = analysis;
        record.analysisLabel = analysis;
        record.flashVersion = EnvironmentSnapshot.flashVersion();
        record.projectRoot = projectRoot.getAbsolutePath();
        record.parameters = new LinkedHashMap<String, Object>();
        return record;
    }

    private static void write(RunRecord record) throws Exception {
        File dir = flash.pipeline.io.FlashProjectLayout
                .forDirectory(record.projectRoot)
                .runJsonlWriteDir();
        RunRecordIO.writeSnapshot(RunRecordIO.runFile(dir, record), record);
    }
}
