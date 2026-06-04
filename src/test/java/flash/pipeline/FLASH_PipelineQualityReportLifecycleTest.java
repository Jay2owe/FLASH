package flash.pipeline;

import flash.pipeline.io.ProjectStatusStore;
import flash.pipeline.report.QualityReport;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Tests the run-scoped QualityReport factory and cleanup logic
 * extracted from FLASH_Pipeline.
 */
public class FLASH_PipelineQualityReportLifecycleTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void createQualityReportForRun_returnsFreshInstance() {
        QualityReport a = FLASH_Pipeline.createQualityReportForRun(
                tmp.getRoot().getAbsolutePath(), true,
                false, true, 4, false, "Auto-Overwrite");
        QualityReport b = FLASH_Pipeline.createQualityReportForRun(
                tmp.getRoot().getAbsolutePath(), true,
                false, true, 4, false, "Auto-Overwrite");

        assertNotSame("Each call must return a new instance", a, b);
        assertTrue(a.getSections().isEmpty());
        assertTrue(b.getSections().isEmpty());
    }

    @Test
    public void createQualityReportForRun_appliesSettings() {
        QualityReport report = FLASH_Pipeline.createQualityReportForRun(
                "/my/dir", true,
                true, true, 8, true, "Skip Existing");

        assertTrue(report.isEnabled());
        assertEquals("/my/dir", report.getProjectDir());
        assertTrue(report.isHeadless());
        assertTrue(report.isParallel());
        assertEquals(8, report.getThreadCount());
        assertTrue(report.isVerboseLogging());
        assertEquals("Skip Existing", report.getOverwriteBehavior());
    }

    @Test
    public void qualityReportFirstWriteCleansStaleOverlays() throws IOException {
        File reportDir = new File(tmp.getRoot(), "FLASH/Results/QC");
        File overlayDir = new File(reportDir, "overlays");
        assertTrue(overlayDir.mkdirs());

        File staleOverlay = new File(overlayDir, "old_image_DAPI_overlay.tif");
        writeStubFile(staleOverlay);
        assertTrue(staleOverlay.exists());

        File staleHtml = new File(reportDir, "QC_Report.html");
        writeStubFile(staleHtml);
        assertTrue(staleHtml.exists());

        QualityReport report = FLASH_Pipeline.createQualityReportForRun(
                tmp.getRoot().getAbsolutePath(), true,
                false, false, 1, false, "Auto-Overwrite");

        assertTrue("Creating a run report must not delete files before the run starts",
                staleOverlay.exists());
        assertTrue(staleHtml.exists());

        report.addGenericAnalysis("Test Analysis", 1L);

        assertFalse("Stale overlay must be removed", staleOverlay.exists());
        assertTrue("QC HTML should be recreated by the first report write", staleHtml.isFile());
        assertFalse("Stale HTML content must be replaced",
                new String(Files.readAllBytes(staleHtml.toPath()), StandardCharsets.UTF_8)
                        .contains("stub"));
    }

    @Test
    public void createQualityReportForRun_skipsCleanupWhenDisabled() throws IOException {
        File reportDir = new File(tmp.getRoot(), "FLASH/Results/QC");
        File overlayDir = new File(reportDir, "overlays");
        assertTrue(overlayDir.mkdirs());

        File existingOverlay = new File(overlayDir, "keep_me.tif");
        writeStubFile(existingOverlay);

        FLASH_Pipeline.createQualityReportForRun(
                tmp.getRoot().getAbsolutePath(), false,
                false, false, 1, false, "Auto-Overwrite");

        assertTrue("Overlays must survive when QC is disabled", existingOverlay.exists());
    }

    @Test
    public void createQualityReportForRun_toleratesMissingReportDir() {
        // No report directory exists — should not throw
        QualityReport report = FLASH_Pipeline.createQualityReportForRun(
                tmp.getRoot().getAbsolutePath(), true,
                false, false, 1, false, "Auto-Overwrite");
        assertNotNull(report);
    }

    @Test
    public void writeCliStatus_usesProjectStatusJson() throws IOException {
        FLASH_Pipeline.writeCliStatus(tmp.getRoot(), false,
                Collections.singletonList("3D Object Analysis"), "failed");

        File status = ProjectStatusStore.statusFile(tmp.getRoot());
        assertTrue(status.isFile());
        assertFalse(new File(tmp.getRoot(), ".cli_status").exists());
        assertFalse(new File(tmp.getRoot(), "FLASH/Status").exists());

        @SuppressWarnings("unchecked")
        Map<String, Object> cliStatus = (Map<String, Object>)
                ProjectStatusStore.load(tmp.getRoot()).get(ProjectStatusStore.SECTION_CLI_STATUS);
        assertEquals(Boolean.FALSE, cliStatus.get("ok"));
        assertEquals("failed", cliStatus.get("reason"));
        assertEquals(Collections.singletonList("3D Object Analysis"), cliStatus.get("failed"));
    }

    private static void writeStubFile(File f) throws IOException {
        FileWriter fw = new FileWriter(f);
        try {
            fw.write("stub");
        } finally {
            fw.close();
        }
    }
}
