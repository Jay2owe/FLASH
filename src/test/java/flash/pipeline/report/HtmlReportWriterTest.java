package flash.pipeline.report;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Tests that HtmlReportWriter faithfully renders the report instance
 * it receives — proving directory-switch and repeated-run isolation
 * at the rendering layer.
 */
public class HtmlReportWriterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void writtenHtml_containsProjectDirFromReport() throws IOException {
        QualityReport report = new QualityReport();
        report.setEnabled(true);
        report.setDirectory("/test/project/alpha");
        report.setGlobalSettings(false, true, 4, false, "Auto-Overwrite");

        File htmlFile = new File(tmp.getRoot(), "QC_Report.html");
        HtmlReportWriter.write(htmlFile, report);

        String html = new String(Files.readAllBytes(htmlFile.toPath()), StandardCharsets.UTF_8);
        assertTrue("HTML must contain the report's project directory",
                html.contains("/test/project/alpha"));
    }

    @Test
    public void secondRun_htmlDoesNotContainFirstRunDirectory() throws IOException {
        // Simulate run A
        QualityReport runA = new QualityReport();
        runA.setEnabled(true);
        runA.setDirectory("/data/run_alpha");
        runA.setGlobalSettings(false, true, 4, false, "Auto-Overwrite");
        Map<String, String> p1 = new LinkedHashMap<String, String>();
        p1.put("method", "Automatic");
        runA.addSection("Split and Merge", p1);

        File htmlFileA = new File(tmp.newFolder("runA"), "QC_Report.html");
        HtmlReportWriter.write(htmlFileA, runA);

        // Simulate run B with a different directory
        QualityReport runB = new QualityReport();
        runB.setEnabled(true);
        runB.setDirectory("/data/run_beta");
        runB.setGlobalSettings(true, false, 1, false, "Skip Existing");
        Map<String, String> p2 = new LinkedHashMap<String, String>();
        p2.put("filter", "Median");
        runB.addSection("Intensity Analysis", p2);

        File htmlFileB = new File(tmp.newFolder("runB"), "QC_Report.html");
        HtmlReportWriter.write(htmlFileB, runB);

        String htmlB = new String(Files.readAllBytes(htmlFileB.toPath()), StandardCharsets.UTF_8);

        // Run B's HTML must NOT contain run A's directory or section
        assertFalse("Run B HTML must not contain run A directory",
                htmlB.contains("/data/run_alpha"));
        assertFalse("Run B HTML must not contain run A section name",
                htmlB.contains("Split and Merge"));

        // Run B's HTML must contain its own data
        assertTrue(htmlB.contains("/data/run_beta"));
        assertTrue(htmlB.contains("Intensity Analysis"));
    }

    @Test
    public void emptyReport_producesValidHtml() throws IOException {
        QualityReport report = new QualityReport();
        report.setEnabled(true);
        report.setDirectory("/empty");
        report.setGlobalSettings(false, false, 1, false, "Auto-Overwrite");

        File htmlFile = new File(tmp.getRoot(), "empty_report.html");
        HtmlReportWriter.write(htmlFile, report);

        String html = new String(Files.readAllBytes(htmlFile.toPath()), StandardCharsets.UTF_8);
        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.contains("</html>"));
        assertTrue(html.contains("/empty"));
    }

    @Test
    public void spectralPreviewSection_rendersGalleryAndMetadata() throws IOException {
        QualityReport report = new QualityReport();
        report.setEnabled(true);
        report.setDirectory("/spectral");
        report.setGlobalSettings(false, true, 2, false, "Auto-Overwrite");

        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("Goal", "Create cleaned mask");
        params.put("Config ID", "sha256:test");
        report.addSpectralDecontaminationSection(params, Arrays.asList(
                new QualityReport.SpectralPreviewQC(
                        "Control",
                        "control",
                        "typical",
                        "Series 1",
                        solidImage(0xff0000),
                        solidImage(0x00ff00),
                        solidImage(0x0000ff),
                        Arrays.asList("Target-positive volume: 12 voxels"),
                        Arrays.asList("Channel 2: 0.25"),
                        Arrays.asList("Fit pool is small"))));

        File htmlFile = new File(tmp.getRoot(), "spectral_report.html");
        HtmlReportWriter.write(htmlFile, report);

        String html = new String(Files.readAllBytes(htmlFile.toPath()), StandardCharsets.UTF_8);
        assertTrue(html.contains("Spectral Preview Gallery"));
        assertTrue(html.contains("Create cleaned mask"));
        assertTrue(html.contains("sha256:test"));
        assertTrue(html.contains("Target-positive volume: 12 voxels"));
        assertTrue(html.contains("Fit pool is small"));
        assertTrue(html.contains("data:image/jpeg;base64,"));
    }

    private static BufferedImage solidImage(int rgb) {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }
}
