package flash.pipeline.runrecord;

import flash.pipeline.io.FlashProjectLayout;
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
import static org.junit.Assert.assertNotNull;

public class LoadFromRunButtonHeadlessTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @After
    public void resetSelectionProvider() {
        LoadFromRunButton.setSelectionProviderForTests(null);
    }

    @Test
    public void simulatedClickAppliesChosenRunParameters() throws Exception {
        File projectRoot = temp.newFolder("project");
        RunRecord record = new RunRecord();
        record.runId = "run-headless";
        record.startedAtMillis = System.currentTimeMillis();
        record.finishedAtMillis = record.startedAtMillis + 1L;
        record.status = RunRecord.STATUS_OK;
        record.analysis = "IntensityAnalysisV2";
        record.analysisLabel = "IntensityAnalysisV2";
        record.flashVersion = EnvironmentSnapshot.flashVersion();
        record.projectRoot = projectRoot.getAbsolutePath();
        record.parameters = new LinkedHashMap<String, Object>();
        record.parameters.put("defaultMode", "Whole ROI mean (all pixels, including background)");
        record.parameters.put("thresholds", new LinkedHashMap<String, Object>());
        File runDir = FlashProjectLayout.forDirectory(projectRoot.getAbsolutePath()).runJsonlWriteDir();
        RunRecordIO.writeSnapshot(RunRecordIO.runFile(runDir, record), record);

        final AtomicReference<Map<String, Object>> applied =
                new AtomicReference<Map<String, Object>>();
        LoadFromRunButton.setSelectionProviderForTests(
                new LoadFromRunButton.RunSelectionProvider() {
                    @Override public RunRecord choose(Component owner,
                                                      String analysisKey,
                                                      File root,
                                                      List<RunSummary> summaries) {
                        assertEquals("IntensityAnalysisV2", analysisKey);
                        assertEquals(1, summaries.size());
                        return RunRecordIO.readLatest(summaries.get(0).recordFile);
                    }
                });

        JButton button = LoadFromRunButton.create("IntensityAnalysisV2", projectRoot,
                new LoadedRunParameterApplier() {
                    @Override public LoadedRunParameters.Result applyLoadedParameters(
                            Map<String, Object> parameters) {
                        applied.set(parameters);
                        LoadedRunParameters.PresetLoad<?> loaded =
                                LoadedRunParameters.intensityPreset(parameters);
                        LoadedRunParameters.rememberLastResult(loaded.result);
                        return loaded.result;
                    }
                });

        button.doClick();

        assertNotNull(applied.get());
        assertEquals("Whole ROI mean (all pixels, including background)",
                applied.get().get("defaultMode"));
    }
}
