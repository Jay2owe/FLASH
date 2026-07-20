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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReplayWritesNewOutputFolderTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @After
    public void resetRunner() {
        Replay.setRunnerForTests(null);
    }

    @Test
    public void replayUsesFreshFolderAndLeavesOriginalUntouched() throws Exception {
        File projectRoot = temp.newFolder("project");
        File sentinel = new File(projectRoot, "original.txt");
        Files.write(sentinel.toPath(), "keep".getBytes(StandardCharsets.UTF_8));
        File input = writeInput("input.tif");
        writeProject(projectRoot, input);
        RunRecord parent = parentRecord(projectRoot, input);
        ReplayPlan plan = Replay.plan(parent);
        final String[] macroOptions = new String[1];
        Replay.setRunnerForTests(fakeRunner(macroOptions));

        Replay.execute(plan, null);

        assertTrue(plan.replayRoot().isDirectory());
        assertFalse(projectRoot.getCanonicalFile().equals(plan.replayRoot().getCanonicalFile()));
        assertEquals("keep", new String(Files.readAllBytes(sentinel.toPath()), StandardCharsets.UTF_8));
        assertTrue("macro options should target the replay root",
                macroOptions[0].contains(plan.replayRoot().getAbsolutePath().replace('\\', '/')));

        ProjectFile replayProject = ProjectFileIO.read(
                FlashProjectLayout.forDirectory(plan.replayRoot().getAbsolutePath()).configurationWriteDir());
        assertEquals(plan.replayRoot().getAbsolutePath(), replayProject.outputRoot);
    }

    @Test
    public void parentIndexSeesChildFromNestedReplayDirectory() throws Exception {
        File projectRoot = temp.newFolder("project");
        File input = writeInput("input.tif");
        writeProject(projectRoot, input);
        RunRecord parent = parentRecord(projectRoot, input);
        ReplayPlan plan = Replay.plan(parent);
        Replay.setRunnerForTests(fakeRunner(new String[1]));

        Replay.execute(plan, null);

        FlashProjectLayout parentLayout = FlashProjectLayout.forDirectory(projectRoot.getAbsolutePath());
        List<RunSummary> summaries = RunRecordIO.readIndex(parentLayout.runJsonlWriteDir());
        boolean found = false;
        for (RunSummary summary : summaries) {
            if (parent.runId.equals(summary.parentRunId)) {
                found = true;
            }
        }
        assertTrue("parent browser index should include nested replay child", found);
    }

    private Replay.Runner fakeRunner(final String[] macroOptions) {
        return new Replay.Runner() {
            @Override
            public void run(ReplayPlan plan, String options) throws Exception {
                macroOptions[0] = options;
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
        };
    }

    private RunRecord parentRecord(File projectRoot, File input) {
        RunRecord record = new RunRecord();
        record.runId = "PARENT02";
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
        project.name = "Replay Test";
        project.outputRoot = projectRoot.getAbsolutePath();
        ProjectFile.Item item = new ProjectFile.Item();
        item.path = input.getAbsolutePath();
        project.items.add(item);
        ProjectFileIO.write(layout.configurationWriteDir(), project);
    }

    private File writeInput(String name) throws Exception {
        File input = new File(temp.getRoot(), name);
        Files.write(input.toPath(), "image".getBytes(StandardCharsets.UTF_8));
        return input;
    }
}
