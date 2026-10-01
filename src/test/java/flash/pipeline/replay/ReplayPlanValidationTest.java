package flash.pipeline.replay;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.project.ProjectFile;
import flash.pipeline.project.ProjectFileIO;
import flash.pipeline.runrecord.ProjectFileHasher;
import flash.pipeline.runrecord.InputFingerprinter;
import flash.pipeline.runrecord.ConfigurationSnapshot;
import flash.pipeline.runrecord.RunRecord;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ReplayPlanValidationTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void missingInputBlocksReplay() throws Exception {
        RunRecord parent = parentRecord(temp.newFolder("project"),
                new File(temp.getRoot(), "missing.tif"));

        ReplayPlan plan = Replay.plan(parent);

        assertEquals(ReplayPlan.Status.BLOCKED, plan.status());
        assertContains(plan.messages(), "Input file is missing");
    }

    @Test
    public void fingerprintDriftBlocksVerbatimReplay() throws Exception {
        File input = write("input.tif", "before");
        RunRecord parent = parentRecord(temp.newFolder("project"), input);
        Files.write(input.toPath(), "after".getBytes(StandardCharsets.UTF_8));

        ReplayPlan plan = Replay.plan(parent);

        assertEquals(ReplayPlan.Status.BLOCKED, plan.status());
        assertContains(plan.messages(), "fingerprint drifted");
    }

    @Test
    public void removedAnalysisBlocksReplay() throws Exception {
        File input = write("input.tif", "data");
        RunRecord parent = parentRecord(temp.newFolder("project"), input);
        parent.analysis = "DeletedAnalysis";
        parent.analysisIndex = 99;

        ReplayPlan plan = Replay.plan(parent);

        assertEquals(ReplayPlan.Status.BLOCKED, plan.status());
        assertContains(plan.messages(), "not registered");
    }

    @Test
    public void allGoodPlanIsReady() throws Exception {
        File input = write("input.tif", "data");
        RunRecord parent = parentRecord(temp.newFolder("project"), input);

        ReplayPlan plan = Replay.plan(parent);

        assertEquals(ReplayPlan.Status.READY, plan.status());
        assertTrue(plan.replayRoot().getName().contains("_replay_of_" + parent.runId));
    }

    @Test
    public void unchangedFullFingerprintIsReady() throws Exception {
        File input = write("full-input.tif", "data");
        RunRecord parent = parentRecord(temp.newFolder("full-project"), input);
        parent.inputs.get(0).fingerprintMode = "full";
        parent.inputs.get(0).fingerprint = InputFingerprinter.fullFingerprint(input).value;

        assertEquals(ReplayPlan.Status.READY, Replay.plan(parent).status());
    }

    @Test
    public void sameVersionWithDifferentCodeCannotClaimVerbatimReplay() throws Exception {
        RunRecord parent = parentRecord(temp.newFolder("different-build"), write("build.tif", "data"));
        parent.extras.put("flashArtifactFingerprint", "different-compiled-code");
        ReplayPlan plan = Replay.plan(parent);
        assertEquals(ReplayPlan.Status.BLOCKED, plan.status());
        assertContains(plan.messages(), "FLASH code changed");
    }

    @Test
    public void missingCompiledCodeIdentityBlocksVerbatimReplay() throws Exception {
        RunRecord parent = parentRecord(temp.newFolder("unidentified-build"), write("unknown-build.tif", "data"));
        parent.extras.remove("flashArtifactFingerprint");
        ReplayPlan plan = Replay.plan(parent);
        assertEquals(ReplayPlan.Status.BLOCKED, plan.status());
        assertContains(plan.messages(), "no loaded FLASH code fingerprint");
    }

    @Test
    public void fullFingerprintDetectsTailDriftWithUnchangedSizeAndTimestamp() throws Exception {
        File input = temp.newFile("full-tail.tif");
        byte[] bytes = new byte[128 * 1024];
        Files.write(input.toPath(), bytes);
        RunRecord parent = parentRecord(temp.newFolder("tail-project"), input);
        RunRecord.InputItem item = parent.inputs.get(0);
        item.fingerprintMode = "full";
        item.fingerprint = InputFingerprinter.fullFingerprint(input).value;
        long modified = input.lastModified();
        bytes[bytes.length - 1] = 1;
        Files.write(input.toPath(), bytes);
        Files.setLastModifiedTime(input.toPath(), java.nio.file.attribute.FileTime.fromMillis(modified));

        ReplayPlan plan = Replay.plan(parent);
        assertEquals(ReplayPlan.Status.BLOCKED, plan.status());
        assertContains(plan.messages(), "fingerprint drifted");
    }

    @Test
    public void unknownFingerprintModeBlocksReplay() throws Exception {
        File input = write("unknown-mode.tif", "data");
        RunRecord parent = parentRecord(temp.newFolder("unknown-project"), input);
        parent.inputs.get(0).fingerprintMode = "unsupported";

        ReplayPlan plan = Replay.plan(parent);
        assertEquals(ReplayPlan.Status.BLOCKED, plan.status());
        assertContains(plan.messages(), "Unsupported input fingerprint mode");
    }

    @Test
    public void historicalRecordWithoutConfigurationIdentityBlocksReplay() throws Exception {
        RunRecord parent = parentRecord(temp.newFolder("historical-project"), write("historical.tif", "data"));
        parent.extras.remove(ConfigurationSnapshot.EXTRA_KEY);
        ReplayPlan plan = Replay.plan(parent);
        assertEquals(ReplayPlan.Status.BLOCKED, plan.status());
        assertContains(plan.messages(), "no configuration and ROI fingerprints");
    }

    @Test
    public void missingInputFingerprintBlocksReplay() throws Exception {
        RunRecord parent = parentRecord(temp.newFolder("unverified-project"), write("unverified.tif", "data"));
        parent.inputs.get(0).fingerprint = "";
        ReplayPlan plan = Replay.plan(parent);
        assertEquals(ReplayPlan.Status.BLOCKED, plan.status());
        assertContains(plan.messages(), "no recorded fingerprint");
    }

    @Test
    public void blankRecordedInputCannotBypassInputVerification() throws Exception {
        RunRecord parent = parentRecord(temp.newFolder("blank-input"), write("blank-input.tif", "data"));
        parent.inputs.get(0).path = "";
        ReplayPlan plan = Replay.plan(parent);
        assertEquals(ReplayPlan.Status.BLOCKED, plan.status());
        assertContains(plan.messages(), "no source path");
    }

    @Test
    public void absentRecordedInputsBlockReplay() throws Exception {
        RunRecord parent = parentRecord(temp.newFolder("unrecorded-input-project"),
                write("unrecorded-input.tif", "data"));
        parent.inputs.clear();
        ReplayPlan plan = Replay.plan(parent);
        assertEquals(ReplayPlan.Status.BLOCKED, plan.status());
        assertContains(plan.messages(), "no recorded inputs");
    }

    @Test
    public void runningCheckpointBlocksReplay() throws Exception {
        RunRecord parent = parentRecord(temp.newFolder("running-project"), write("running.tif", "data"));
        parent.status = RunRecord.STATUS_RUNNING;
        ReplayPlan plan = Replay.plan(parent);
        assertEquals(ReplayPlan.Status.BLOCKED, plan.status());
        assertContains(plan.messages(), "no terminal record");
    }

    @Test
    public void changedProjectSelectionBlocksVerbatimReplay() throws Exception {
        File root = temp.newFolder("manifest-project");
        File input = write("manifest-input.tif", "data");
        RunRecord parent = parentRecord(root, input);
        ProjectFile project = new ProjectFile();
        project.outputRoot = root.getAbsolutePath();
        ProjectFile.Item item = new ProjectFile.Item();
        item.path = input.getAbsolutePath();
        item.include = true;
        project.items.add(item);
        parent.projectFileHash = ProjectFileHasher.hash(project);
        File settings = FlashProjectLayout.forDirectory(root.getAbsolutePath()).configurationWriteDir();
        ProjectFileIO.write(settings, project);
        parent.extras.put(ConfigurationSnapshot.EXTRA_KEY, ConfigurationSnapshot.capture(root));
        assertEquals(ReplayPlan.Status.READY, Replay.plan(parent).status());

        item.include = false;
        ProjectFileIO.write(settings, project);
        ReplayPlan plan = Replay.plan(parent);
        assertEquals(ReplayPlan.Status.BLOCKED, plan.status());
        assertContains(plan.messages(), "Project manifest changed");
    }

    @Test
    public void missingRecordedProjectManifestBlocksReplay() throws Exception {
        File root = temp.newFolder("missing-manifest-project");
        RunRecord parent = parentRecord(root, write("missing-manifest-input.tif", "data"));
        parent.projectFileHash = ProjectFileHasher.hash(new ProjectFile());

        ReplayPlan plan = Replay.plan(parent);
        assertEquals(ReplayPlan.Status.BLOCKED, plan.status());
        assertContains(plan.messages(), "recorded project manifest is missing or unreadable");
    }

    private RunRecord parentRecord(File projectRoot, File input) throws Exception {
        RunRecord record = new RunRecord();
        record.runId = "PARENT01";
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
        item.sizeBytes = fp.sizeBytes;
        item.lastModifiedMillis = fp.lastModifiedMillis;
        record.inputs.add(item);
        return record;
    }

    private File write(String name, String text) throws Exception {
        File file = new File(temp.getRoot(), name);
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static void assertContains(Iterable<String> messages, String needle) {
        for (String message : messages) {
            if (message.contains(needle)) {
                return;
            }
        }
        org.junit.Assert.fail("Missing message containing " + needle);
    }
}
