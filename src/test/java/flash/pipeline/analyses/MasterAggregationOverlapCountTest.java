package flash.pipeline.analyses;

import flash.pipeline.io.FlashProjectLayout;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Marker-centroid object counting ("resolve fused objects") columns must aggregate correctly into
 * the per-animal master summary: {@code *_OverlapCount_*} as a count (Total + Mean) and the
 * {@code *_HasMarker_*} / {@code *_IsCluster_*} flags as binary (Count + %), with no double prefix.
 */
public class MasterAggregationOverlapCountTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void overlapCountColumnsAreNeverChannelPrefixed() {
        assertFalse(MasterAggregationAnalysis.needsChannelPrefix("GFAP_OverlapCount_DAPI"));
        assertFalse(MasterAggregationAnalysis.needsChannelPrefix("GFAP_HasMarker_DAPI"));
        assertFalse(MasterAggregationAnalysis.needsChannelPrefix("GFAP_IsCluster_DAPI"));
    }

    @Test
    public void overlapCountAggregatesAsCountAndFlags() throws Exception {
        File root = temp.newFolder("master-agg-overlap");
        File objects = FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesObjectsWriteDir();
        assertTrue(objects.mkdirs());

        // GFAP object 1 is a fused cluster of 3 DAPI nuclei; object 2 has no nucleus inside.
        String header = "Animal Name,Label,Volume (micron^3),"
                + "GFAP_OverlapCount_DAPI,GFAP_HasMarker_DAPI,GFAP_IsCluster_DAPI";
        File perObjectCsv = new File(objects, "GFAP.csv");
        writeCsv(perObjectCsv, header,
                "Mouse1,1,100,3,1,1\n"
                        + "Mouse1,2,120,0,0,0");

        MasterAggregationAnalysis analysis = new MasterAggregationAnalysis();
        analysis.setSuppressDialogs(true);
        analysis.execute(root.getAbsolutePath());

        List<String> lines = Files.readAllLines(
                FlashProjectLayout.forDirectory(root.getAbsolutePath())
                        .projectSummaryWriteFile("3D Objects.csv").toPath(),
                StandardCharsets.UTF_8);
        assertTrue("master summary should have a header + a data row", lines.size() >= 2);

        Map<String, String> row = rowByColumn(lines.get(0), lines.get(1));
        assertFalse("no double channel prefix", lines.get(0).contains("GFAP_GFAP"));

        // Count-like: Total = corrected object count (3 nuclei across the channel), Mean = per object.
        assertEquals(3.0, parse(row, "GFAP_OverlapCount_DAPITotal"), 1e-9);
        assertEquals(1.5, parse(row, "GFAP_OverlapCount_DAPIMean"), 1e-9);

        // Binary flags: one of two objects has a marker / is a fused cluster -> Count 1, 50%.
        assertEquals(1.0, parse(row, "GFAP_HasMarker_DAPICount"), 1e-9);
        assertEquals(50.0, parse(row, "GFAP_HasMarker_DAPI%"), 1e-9);
        assertEquals(1.0, parse(row, "GFAP_IsCluster_DAPICount"), 1e-9);
        assertEquals(50.0, parse(row, "GFAP_IsCluster_DAPI%"), 1e-9);
    }

    private static double parse(Map<String, String> row, String column) {
        assertTrue("master summary missing column " + column, row.containsKey(column));
        return Double.parseDouble(row.get(column).trim());
    }

    private static Map<String, String> rowByColumn(String headerLine, String dataLine) {
        String[] cols = headerLine.split(",", -1);
        String[] vals = dataLine.split(",", -1);
        Map<String, String> map = new HashMap<String, String>();
        for (int i = 0; i < cols.length && i < vals.length; i++) {
            map.put(cols[i].trim(), vals[i]);
        }
        return map;
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
