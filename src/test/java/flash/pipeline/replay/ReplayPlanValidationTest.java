package flash.pipeline.replay;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.runrecord.InputFingerprinter;
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
    public void fingerprintDriftWarnsButDoesNotBlock() throws Exception {
        File input = write("input.tif", "before");
        RunRecord parent = parentRecord(temp.newFolder("project"), input);
        Files.write(input.toPath(), "after".getBytes(StandardCharsets.UTF_8));

        ReplayPlan plan = Replay.plan(parent);

        assertEquals(ReplayPlan.Status.WARN, plan.status());
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

    private RunRecord parentRecord(File projectRoot, File input) {
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
