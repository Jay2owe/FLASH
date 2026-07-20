package flash.pipeline.replay;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.project.ProjectFile;
import flash.pipeline.project.ProjectFileIO;
import flash.pipeline.runrecord.AnalysisRunContext;
import flash.pipeline.runrecord.InputFingerprinter;
import flash.pipeline.runrecord.RunRecord;
import flash.pipeline.runrecord.RunRecordIO;
import flash.pipeline.runrecord.RunSummary;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ReplayPopulatesParentRunIdTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @After
    public void resetRunner() {
        Replay.setRunnerForTests(null);
    }

    @Test
    public void replayChildRecordPointsAtParentRun() throws Exception {
        File projectRoot = temp.newFolder("project");
        File input = writeInput();
        writeProject(projectRoot, input);
        RunRecord parent = parentRecord(projectRoot, input);
        ReplayPlan plan = Replay.plan(parent);
        Replay.setRunnerForTests(new Replay.Runner() {
            @Override
            public void run(ReplayPlan plan, String options) throws Exception {
                FlashProjectLayout layout = FlashProjectLayout.forDirectory(
                        plan.replayRoot().getAbsolutePath());
                AnalysisRunContext context = AnalysisRunContext.open(
                        plan.analysis().analysisKey(),
                        plan.analysis().analysisIndex(),
                        plan.analysis().label(),
                        plan.replayRoot().getAbsolutePath(),
                        ProjectFileIO.read(layout.configurationWriteDir()),
                        plan.parent().parameters,
                        plan.parent().runId);
                context.close();
            }
        });

        Replay.execute(plan, null);

        FlashProjectLayout replayLayout = FlashProjectLayout.forDirectory(
                plan.replayRoot().getAbsolutePath());
        List<RunSummary> summaries = RunRecordIO.readIndex(replayLayout.runJsonlWriteDir());
        RunRecord child = RunRecordIO.readLatest(summaries.get(0).recordFile);
        assertEquals(parent.runId, child.parentRunId);
    }

    private RunRecord parentRecord(File projectRoot, File input) {
        RunRecord record = new RunRecord();
        record.runId = "PARENT03";
        record.analysis = "ThreeDObjectAnalysis";
        record.analysisIndex = FLASH_Pipeline.IDX_3D_OBJECT;
        record.analysisLabel = "3D Object Analysis";
        record.flashVersion = "4.0.0";
        record.projectRoot = projectRoot.getAbsolutePath();
        record.outputRoot = projectRoot.getAbsolutePath();
        record.parameters = new LinkedHashMap<String, Object>();
        record.parameters.put("doVolumetric", Boolean.TRUE);

        RunRecord.InputItem item = new RunRecord.InputItem();
        item.path = input.getAbsolutePath();
        InputFingerprinter.FingerprintResult fp = InputFingerprinter.fastFingerprint(input);
        item.fingerprint = fp.value;
        item.fingerprintMode = fp.mode;
        record.inputs.add(item);
        return record;
    }

    private void writeProject(File projectRoot, File input) throws Exception {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(projectRoot.getAbsolutePath());
        ProjectFile project = new ProjectFile();
        project.name = "Replay Parent Test";
        project.outputRoot = projectRoot.getAbsolutePath();
        ProjectFile.Item item = new ProjectFile.Item();
        item.path = input.getAbsolutePath();
        project.items.add(item);
        ProjectFileIO.write(layout.configurationWriteDir(), project);
    }

    private File writeInput() throws Exception {
        File input = new File(temp.getRoot(), "input.tif");
        Files.write(input.toPath(), "image".getBytes(StandardCharsets.UTF_8));
        return input;
    }
}
