package flash.pipeline.io;

import ij.measure.ResultsTable;
import flash.pipeline.results.ObjectCsvColumnOrder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CsvTableIOTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void formatDist_usesDotDecimalUnderGermanLocale() {
        Locale original = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        try {
            assertEquals("1.500000", CsvTableIO.formatDist(1.5));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    public void formatDist_preservesInfSentinelUnderGermanLocale() {
        Locale original = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        try {
            assertEquals("Inf", CsvTableIO.formatDist(Double.NaN));
            assertEquals("Inf", CsvTableIO.formatDist(Double.POSITIVE_INFINITY));
            assertEquals("Inf", CsvTableIO.formatDist(Double.MAX_VALUE));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    public void writeAndLoadChannelCsv_roundTripsQuotedFieldsAndTrailingEmpty() throws Exception {
        List<String> header = new ArrayList<String>(Arrays.asList("Name", "Note", "Comment", "EmptyTail"));
        Map<String, Integer> colIdx = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < header.size(); i++) {
            colIdx.put(header.get(i), i);
        }

        List<List<String>> rows = new ArrayList<List<String>>();
        rows.add(new ArrayList<String>(Arrays.asList(
                "Animal, \"Alpha\"",
                "He said \"hello\"",
                "Line1\nLine2",
                "")));

        CsvTableIO.ChannelData channel = new CsvTableIO.ChannelData("C1", header, rows, colIdx);
        File csv = temp.newFile("channel.csv");
        CsvTableIO.writeChannelCsv(csv, channel);

        CsvTableIO.ChannelData loaded = CsvTableIO.loadChannelCsv(csv, "C1");
        assertNotNull(loaded);
        assertArrayEquals(header.toArray(new String[0]), loaded.header.toArray(new String[0]));
        assertEquals("Animal, \"Alpha\"", loaded.get(0, "Name"));
        assertEquals("He said \"hello\"", loaded.get(0, "Note"));
        assertEquals("Line1\nLine2", loaded.get(0, "Comment"));
        assertEquals("", loaded.get(0, "EmptyTail"));
    }

    @Test
    public void writeResultsTableCsv_honorsOrderedObjectColumns() throws Exception {
        ResultsTable table = new ResultsTable();
        table.incrementCounter();
        table.setValue("XM_um", 0, 10);
        table.setValue("Colocalisation with B", 0, 12);
        table.setValue("A_Costes_p_B", 0, 0.04);
        table.setValue("A_Pearson_B", 0, 0.7);
        table.setValue("A_Manders_M1_B", 0, 0.8);
        table.setValue("A_Manders_M2_B", 0, 0.6);
        table.setValue("A_Costes_Ta_B", 0, 21);
        table.setValue("A_Costes_Tb_B", 0, 18);
        table.setValue("A_Pearson_t_B", 0, 0.5);
        table.setValue("Label", 0, 7);
        table.setValue("Animal Name", 0, "Mouse1");
        table.setValue("Volume (micron^3)", 0, 101);
        table.setValue("Region", 0, "SCN");
        table.setValue("Atlas Key", 0, "allen_mouse_25um");
        table.setValue("Region ID", 0, "286");
        table.setValue("Region Acronym", 0, "SCH");
        table.setValue("Region Name", 0, "Suprachiasmatic nucleus");
        table.setValue("Hemisphere", 0, "RH");
        table.setValue("ROI", 0, "SCN1");
        table.setValue("XM", 0, 1);
        table.setValue("YM", 0, 2);
        table.setValue("ZM", 0, 3);
        table.setValue("Mean", 0, 4);

        List<String> ordered = ObjectCsvColumnOrder.orderedColumns("A",
                Arrays.asList(table.getHeadings()), Arrays.asList("A", "B"));

        File csv = temp.newFile("ordered-results.csv");
        CsvTableIO.writeResultsTableCsv(csv, table, ordered);

        CsvTableIO.ChannelData loaded = CsvTableIO.loadChannelCsv(csv, "A");
        assertNotNull(loaded);
        assertEquals(Arrays.asList(
                "Region",
                "Atlas Key",
                "Region ID",
                "Region Acronym",
                "Region Name",
                "Hemisphere",
                "ROI",
                "Animal Name",
                "Label",
                "Volume (micron^3)",
                "Mean",
                "XM",
                "YM",
                "ZM",
                "XM_um",
                "Colocalisation with B",
                "A_Pearson_B",
                "A_Manders_M1_B",
                "A_Manders_M2_B",
                "A_Costes_Ta_B",
                "A_Costes_Tb_B",
                "A_Pearson_t_B",
                "A_Costes_p_B",
                "run_id"
        ), loaded.header);
        assertEquals("Mouse1", loaded.get(0, "Animal Name"));
        assertEquals("7", loaded.get(0, "Label"));
        assertEquals("10", loaded.get(0, "XM_um"));
        assertEquals("12", loaded.get(0, "Colocalisation with B"));
        assertEquals(0.7, Double.parseDouble(loaded.get(0, "A_Pearson_B")), 0.0001);
        assertEquals(0.04, Double.parseDouble(loaded.get(0, "A_Costes_p_B")), 0.0001);
    }

    @Test
    public void objectIntensityColocColumnsUseCompactMetricNamesAndStableOrder() {
        List<String> ordered = ObjectCsvColumnOrder.orderedColumns("A", Arrays.asList(
                "A_CPCContains_B",
                "A_ROICostesP_B",
                "A_ObjMandersM2_B",
                "A_ObjPearsonT_B",
                "A_VolColoc30_B",
                "A_ObjCostesTa_B",
                "A_ObjPearson_B",
                "Colocalisation with B",
                "A_ROICostesTa_B",
                "A_ObjCostesP_B",
                "A_CPCColoc_B",
                "A_ObjMandersM1_B",
                "A_ObjCostesTb_B",
                "A_ROICostesTb_B"
        ), Arrays.asList("A", "B"));

        assertEquals(Arrays.asList(
                "Colocalisation with B",
                "A_ObjPearson_B",
                "A_ObjMandersM1_B",
                "A_ObjMandersM2_B",
                "A_ObjCostesTa_B",
                "A_ObjCostesTb_B",
                "A_ObjPearsonT_B",
                "A_ObjCostesP_B",
                "A_ROICostesTa_B",
                "A_ROICostesTb_B",
                "A_ROICostesP_B",
                "A_VolColoc30_B",
                "A_CPCColoc_B",
                "A_CPCContains_B"
        ), ordered);
    }

    @Test
    public void mergeResultsTableCsvAddsColumnsWithoutDroppingExistingOnes() throws Exception {
        File csv = temp.newFile("merge-results.csv");
        List<String> header = new ArrayList<String>(Arrays.asList("Region", "IntDen", "ManualNote"));
        Map<String, Integer> colIdx = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < header.size(); i++) {
            colIdx.put(header.get(i), i);
        }
        List<List<String>> rows = new ArrayList<List<String>>();
        rows.add(new ArrayList<String>(Arrays.asList("SCN", "10", "keep")));
        CsvTableIO.writeChannelCsv(csv, new CsvTableIO.ChannelData("DAPI", header, rows, colIdx));

        ResultsTable table = new ResultsTable();
        table.incrementCounter();
        table.setValue("Region", 0, "SCN");
        table.setValue("IntDen", 0, 12);
        table.setValue("Intensity_PatchinessCV50", 0, 0.25);

        assertTrue(CsvTableIO.mergeResultsTableCsv(csv, table,
                Arrays.asList("Region", "IntDen", "Intensity_PatchinessCV50")));

        CsvTableIO.ChannelData loaded = CsvTableIO.loadChannelCsv(csv, "DAPI");
        assertNotNull(loaded);
        assertEquals(Arrays.asList("Region", "IntDen", "ManualNote", "Intensity_PatchinessCV50", "run_id"),
                loaded.header);
        assertEquals("keep", loaded.get(0, "ManualNote"));
        assertEquals("12", loaded.get(0, "IntDen"));
        assertEquals(0.25, Double.parseDouble(loaded.get(0, "Intensity_PatchinessCV50")), 0.0001);
    }

    @Test
    public void mergeResultsTableCsvRefusesDifferentRowCount() throws Exception {
        File csv = temp.newFile("merge-row-count.csv");
        List<String> header = new ArrayList<String>(Arrays.asList("Region", "IntDen"));
        Map<String, Integer> colIdx = new LinkedHashMap<String, Integer>();
        colIdx.put("Region", Integer.valueOf(0));
        colIdx.put("IntDen", Integer.valueOf(1));
        List<List<String>> rows = new ArrayList<List<String>>();
        rows.add(new ArrayList<String>(Arrays.asList("SCN", "10")));
        CsvTableIO.writeChannelCsv(csv, new CsvTableIO.ChannelData("DAPI", header, rows, colIdx));

        ResultsTable table = new ResultsTable();
        table.incrementCounter();
        table.setValue("Region", 0, "SCN");
        table.incrementCounter();
        table.setValue("Region", 1, "SCN");

        assertFalse(CsvTableIO.mergeResultsTableCsv(csv, table, Arrays.asList("Region")));
    }
}
