package flash.pipeline.replay;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.project.ProjectFile;
import flash.pipeline.project.ProjectFileIO;
import flash.pipeline.runrecord.AnalysisRunContext;
import flash.pipeline.runrecord.ConfigurationSnapshot;
import flash.pipeline.runrecord.InputFingerprinter;
import flash.pipeline.runrecord.RunRecord;
import flash.pipeline.runrecord.RunRecordIO;
import flash.pipeline.runrecord.RunSummary;
import flash.pipeline.roi.RoiIO;
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

    @Test
    public void replayCopiesVerifiedPresetsRegionSelectionsAndIdentityTables() throws Exception {
        File projectRoot = temp.newFolder("verified-project");
        File input = writeInput("verified-input.tif");
        writeProject(projectRoot, input);
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(projectRoot.getAbsolutePath());
        File[] selectedFiles = {
                new File(layout.presetsRoot(), "analysis/preset.json"),
                new File(RoiIO.roiSetWriteDir(projectRoot), "sample_ROIs.zip"),
                layout.projectSummaryWriteFile(FlashProjectLayout.CONDITIONS_FILENAME),
                layout.projectSummaryWriteFile(FlashProjectLayout.ORIENTATION_MANIFEST_FILENAME)
        };
        for (File selected : selectedFiles) {
            Files.createDirectories(selected.getParentFile().toPath());
            Files.write(selected.toPath(), selected.getName().getBytes(StandardCharsets.UTF_8));
        }
        RunRecord parent = parentRecord(projectRoot, input);
        ReplayPlan plan = Replay.plan(parent);
        Replay.setRunnerForTests(fakeRunner(new String[1]));
        Replay.execute(plan, null);
        for (File selected : selectedFiles) {
            String relative = projectRoot.toPath().relativize(selected.toPath()).toString();
            File copied = new File(plan.replayRoot(), relative);
            assertTrue("required replay setting missing: " + relative, copied.isFile());
            assertEquals(InputFingerprinter.fullFingerprint(selected).value,
                    InputFingerprinter.fullFingerprint(copied).value);
        }
    }

    @Test
    public void executeRechecksInputsAfterPlanningBeforeRunnerStarts() throws Exception {
        File root = temp.newFolder("late-drift-project");
        File input = writeInput("late-drift.tif");
        writeProject(root, input);
        ReplayPlan plan = Replay.plan(parentRecord(root, input));
        Files.write(input.toPath(), "changed pixels".getBytes(StandardCharsets.UTF_8));
        final boolean[] ran = {false};
        Replay.setRunnerForTests((ignored, options) -> ran[0] = true);
        try {
            Replay.execute(plan, null);
            org.junit.Assert.fail("late input drift must block verbatim execution");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("fingerprint drifted"));
        }
        assertFalse(ran[0]);
        assertFalse(plan.replayRoot().exists());
    }

    @Test
    public void executeRechecksConfigurationAfterPlanningBeforeRunnerStarts() throws Exception {
        File root = temp.newFolder("late-config-project");
        File input = writeInput("late-config.tif");
        writeProject(root, input);
        ReplayPlan plan = Replay.plan(parentRecord(root, input));
        File changed = new File(FlashProjectLayout.forDirectory(root.getAbsolutePath())
                .configurationWriteDir(), "new-settings.json");
        Files.write(changed.toPath(), "changed settings".getBytes(StandardCharsets.UTF_8));
        final boolean[] ran = {false};
        Replay.setRunnerForTests((ignored, options) -> ran[0] = true);
        try {
            Replay.execute(plan, null);
            org.junit.Assert.fail("late configuration drift must block verbatim execution");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Configuration or ROI files changed"));
        }
        assertFalse(ran[0]);
        assertFalse(plan.replayRoot().exists());
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

    private RunRecord parentRecord(File projectRoot, File input) throws Exception {
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
        record.extras.put(ConfigurationSnapshot.EXTRA_KEY, ConfigurationSnapshot.capture(projectRoot));
        record.extras.put("flashArtifactFingerprint", flash.pipeline.runrecord.EnvironmentSnapshot.flashArtifactFingerprint());

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
