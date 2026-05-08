package flash.pipeline;

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
                false, true, 4, false, false, "Auto-Overwrite");
        QualityReport b = FLASH_Pipeline.createQualityReportForRun(
                tmp.getRoot().getAbsolutePath(), true,
                false, true, 4, false, false, "Auto-Overwrite");

        assertNotSame("Each call must return a new instance", a, b);
        assertTrue(a.getSections().isEmpty());
        assertTrue(b.getSections().isEmpty());
    }

    @Test
    public void createQualityReportForRun_appliesSettings() {
        QualityReport report = FLASH_Pipeline.createQualityReportForRun(
                "/my/dir", true,
                true, true, 8, true, true, "Skip Existing");

        assertTrue(report.isEnabled());
        assertEquals("/my/dir", report.getProjectDir());
        assertTrue(report.isHeadless());
        assertTrue(report.isParallel());
        assertEquals(8, report.getThreadCount());
        assertTrue(report.isAggressiveMemory());
        assertTrue(report.isVerboseLogging());
        assertEquals("Skip Existing", report.getOverwriteBehavior());
    }

    @Test
    public void createQualityReportForRun_cleansStaleOverlays() throws IOException {
        // Create stale report artifacts
        File reportDir = new File(tmp.getRoot(), "FLASH/Reports/Quality Report");
        File overlayDir = new File(reportDir, "overlays");
        assertTrue(overlayDir.mkdirs());

        File staleOverlay = new File(overlayDir, "old_image_DAPI_overlay.tif");
        writeStubFile(staleOverlay);
        assertTrue(staleOverlay.exists());

        File staleHtml = new File(reportDir, "QC_Report.html");
        writeStubFile(staleHtml);
        assertTrue(staleHtml.exists());

        // Create a fresh report — should clean the stale artifacts
        FLASH_Pipeline.createQualityReportForRun(
                tmp.getRoot().getAbsolutePath(), true,
                false, false, 1, false, false, "Auto-Overwrite");

        assertFalse("Stale overlay must be removed", staleOverlay.exists());
        assertFalse("Stale HTML must be removed", staleHtml.exists());
    }

    @Test
    public void createQualityReportForRun_cleansLegacyReportArtifacts() throws IOException {
        File reportDir = new File(tmp.getRoot(), "Quality_Report");
        File overlayDir = new File(reportDir, "overlays");
        assertTrue(overlayDir.mkdirs());

        File staleOverlay = new File(overlayDir, "old_overlay.tif");
        File staleHtml = new File(reportDir, "QC_Report.html");
        writeStubFile(staleOverlay);
        writeStubFile(staleHtml);

        FLASH_Pipeline.createQualityReportForRun(
                tmp.getRoot().getAbsolutePath(), true,
                false, false, 1, false, false, "Auto-Overwrite");

        assertFalse("Legacy stale overlay must be removed", staleOverlay.exists());
        assertFalse("Legacy stale HTML must be removed", staleHtml.exists());
    }

    @Test
    public void createQualityReportForRun_skipsCleanupWhenDisabled() throws IOException {
        // Create report artifacts
        File reportDir = new File(tmp.getRoot(), "FLASH/Reports/Quality Report");
        File overlayDir = new File(reportDir, "overlays");
        assertTrue(overlayDir.mkdirs());

        File existingOverlay = new File(overlayDir, "keep_me.tif");
        writeStubFile(existingOverlay);

        // Create with QC disabled — should NOT clean
        FLASH_Pipeline.createQualityReportForRun(
                tmp.getRoot().getAbsolutePath(), false,
                false, false, 1, false, false, "Auto-Overwrite");

        assertTrue("Overlays must survive when QC is disabled", existingOverlay.exists());
    }

    @Test
    public void createQualityReportForRun_toleratesMissingReportDir() {
        // No report directory exists — should not throw
        QualityReport report = FLASH_Pipeline.createQualityReportForRun(
                tmp.getRoot().getAbsolutePath(), true,
                false, false, 1, false, false, "Auto-Overwrite");
        assertNotNull(report);
    }

    @Test
    public void writeCliStatus_usesFlashStatusFolder() throws IOException {
        FLASH_Pipeline.writeCliStatus(tmp.getRoot(), false,
                Collections.singletonList("3D Object Analysis"), "failed");

        File status = new File(tmp.getRoot(), "FLASH/Status/.settings/cli_status.txt");
        assertTrue(status.isFile());
        assertFalse(new File(tmp.getRoot(), ".cli_status").exists());

        String text = new String(Files.readAllBytes(status.toPath()), StandardCharsets.UTF_8);
        assertTrue(text.contains("ok=false"));
        assertTrue(text.contains("reason=failed"));
        assertTrue(text.contains("failed=3D Object Analysis"));
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
