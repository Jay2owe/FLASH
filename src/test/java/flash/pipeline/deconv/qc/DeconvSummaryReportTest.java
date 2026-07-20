package flash.pipeline.deconv.qc;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DeconvSummaryReportTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void appendRowsAndFooterTotalsMatch() throws Exception {
        File root = temp.newFolder("summary-report");
        DeconvSummaryReport report = new DeconvSummaryReport(root);

        report.appendRow(new DeconvSummaryReport.Row(
                "img_a", "C1", "DL2", "RL", 15, 0.01, "Gibson-Lanni",
                "64x64x16", 100L, 64.0, false, Arrays.asList("nyquistUnder")));
        report.appendRow(new DeconvSummaryReport.Row(
                "img_a", "C2", "DL2", "RL", 15, 0.01, "Gibson-Lanni",
                "64x64x16", 110L, 65.0, true, Arrays.asList("riInferred", "engineFallback")));
        report.appendRow(new DeconvSummaryReport.Row(
                "img_b", "C1", "DL2", "RL", 15, 0.01, "Gibson-Lanni",
                "64x64x16", 120L, 66.0, false, null));
        report.finish(1_500L);

        List<String> lines = Files.readAllLines(report.getReportFile().toPath(), StandardCharsets.UTF_8);
        assertEquals("image\tchannel\tengine\talgorithm\titerations\tregularization\tpsfModel\tsizeXYZ\telapsedMs\tpeakRamMB\tcacheHit\twarnings", lines.get(0));
        assertTrue(lines.get(1).startsWith("img_a\tC1\tDL2\tRL\t15\t0.010000"));
        assertTrue(lines.get(2).contains("\ttrue\t"));
        assertTrue(lines.get(2).endsWith("riInferred,engineFallback"));
        assertTrue(lines.get(4).contains("images=2"));
        assertTrue(lines.get(4).contains("channels=3"));
        assertTrue(lines.get(4).contains("cacheHits=1"));
        assertTrue(lines.get(4).contains("warnings=3"));
        assertTrue(lines.get(4).contains("totalTimeS=1.500"));
    }
}
