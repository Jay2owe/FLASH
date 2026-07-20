package flash.pipeline.analyses;

import flash.pipeline.io.FlashProjectLayout;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Stage 05 — bounding-box coloc + B-volume columns must survive master aggregation without being
 * mis-classified (no double channel prefix) and the per-channel summary must still build cleanly.
 */
public class MasterAggregationBBColocPassthroughTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void bbColocColumnsAreNeverChannelPrefixed() {
        // Channel-encoded coloc columns must NOT be auto-prefixed (would double the namespace).
        assertFalse(MasterAggregationAnalysis.needsChannelPrefix("GFAP_BBColoc_Iba1"));
        assertFalse(MasterAggregationAnalysis.needsChannelPrefix("GFAP_BBColoc30_Iba1"));
        assertFalse(MasterAggregationAnalysis.needsChannelPrefix("GFAP_BBCPCColoc_Iba1"));
        assertFalse(MasterAggregationAnalysis.needsChannelPrefix("GFAP_BBCPCContains_Iba1"));
        assertFalse(MasterAggregationAnalysis.needsChannelPrefix("GFAP_BBVolColoc_Iba1"));
        assertFalse(MasterAggregationAnalysis.needsChannelPrefix("GFAP_BBVolColocTotal_Iba1"));
        assertFalse(MasterAggregationAnalysis.needsChannelPrefix("GFAP_BBVolColoc30_Iba1"));
        // Channel-agnostic bounding-box geometry behaves like Volume / B-width (not Morph-prefixed).
        assertFalse(MasterAggregationAnalysis.needsChannelPrefix("B-volume (voxels)"));
        assertFalse(MasterAggregationAnalysis.needsChannelPrefix("B-volume (micron^3)"));
    }

    @Test
    public void aggregationRunsCleanlyWhenBBColumnsArePresent() throws Exception {
        // BB coloc + B-volume are per-object columns (like CPC / B-width): they live in the
        // per-object CSV and are NOT rolled up into the per-animal summary. The summary must still
        // build cleanly and must not double-prefix or otherwise mangle the channel namespace.
        File root = temp.newFolder("master-agg-bb");
        File objects = FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesObjectsWriteDir();
        assertTrue(objects.mkdirs());

        String header = "Animal Name,Label,Volume (micron^3),B-volume (voxels),B-volume (micron^3),"
                + "GFAP_BBColoc_Iba1,GFAP_BBColoc30_Iba1,GFAP_BBCPCColoc_Iba1,GFAP_BBCPCContains_Iba1,"
                + "GFAP_BBVolColoc_Iba1,GFAP_BBVolColocTotal_Iba1,GFAP_BBVolColoc30_Iba1";
        File perObjectCsv = new File(objects, "GFAP.csv");
        writeCsv(perObjectCsv, header,
                "Mouse1,1,100,27,27,50,1,1,2,40,60,1\n"
                        + "Mouse1,2,120,64,64,10,0,0,1,5,15,0");

        MasterAggregationAnalysis analysis = new MasterAggregationAnalysis();
        analysis.setSuppressDialogs(true);
        analysis.execute(root.getAbsolutePath());

        List<String> lines = Files.readAllLines(
                FlashProjectLayout.forDirectory(root.getAbsolutePath())
                        .projectSummaryWriteFile("3D Objects.csv").toPath(),
                StandardCharsets.UTF_8);
        assertTrue("master summary should have a header + a data row", lines.size() >= 2);
        String masterHeader = lines.get(0);

        // Standard per-channel aggregates were produced (aggregation completed normally).
        assertTrue(masterHeader.contains("GFAP_Count"));
        assertTrue(masterHeader.contains("GFAP_VolumeMean"));
        // No double channel prefix anywhere (the regression the Morph-prefix guard protects).
        assertFalse("no double channel prefix", masterHeader.contains("GFAP_GFAP"));

        // The per-object columns are preserved verbatim in the per-object CSV (the project-level
        // per-object output), carrying their source+partner identity unchanged.
        List<String> perObjectLines = Files.readAllLines(perObjectCsv.toPath(), StandardCharsets.UTF_8);
        String perObjectHeader = perObjectLines.get(0);
        for (String col : new String[] {
                "B-volume (voxels)", "B-volume (micron^3)",
                "GFAP_BBColoc_Iba1", "GFAP_BBCPCColoc_Iba1", "GFAP_BBVolColocTotal_Iba1" }) {
            assertTrue("per-object CSV keeps " + col, perObjectHeader.contains(col));
        }
    }

    private void writeCsv(File file, String header, String rows) throws Exception {
        PrintWriter pw = new PrintWriter(file, "UTF-8");
        try {
            pw.println(header);
            pw.print(rows);
            if (!rows.endsWith("\n")) pw.println();
        } finally {
            pw.close();
        }
    }
}
