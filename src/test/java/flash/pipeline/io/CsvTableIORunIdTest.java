package flash.pipeline.io;

import flash.pipeline.results.RunIdCsv;
import ij.measure.ResultsTable;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CsvTableIORunIdTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void writeResultsTableCsvAppendsRunIdLast() throws Exception {
        ResultsTable table = new ResultsTable();
        table.incrementCounter();
        table.setValue("Animal Name", 0, "Mouse1");
        table.setValue("Metric", 0, 42);

        File csv = temp.newFile("table.csv");
        CsvTableIO.writeResultsTableCsv(csv, table,
                Arrays.asList("Animal Name", "Metric"), "R-WRITE");

        CsvTableIO.ChannelData loaded = CsvTableIO.loadChannelCsv(csv, "table");
        assertNotNull(loaded);
        assertEquals(Arrays.asList("Animal Name", "Metric", RunIdCsv.RUN_ID_COLUMN), loaded.header);
        assertEquals("R-WRITE", loaded.get(0, RunIdCsv.RUN_ID_COLUMN));
    }

    @Test
    public void writeResultsTableCsvWithoutRunContextWritesEmptyRunId() throws Exception {
        ResultsTable table = new ResultsTable();
        table.incrementCounter();
        table.setValue("Animal Name", 0, "Mouse1");

        File csv = temp.newFile("legacy.csv");
        CsvTableIO.writeResultsTableCsv(csv, table, Arrays.asList("Animal Name"));

        CsvTableIO.ChannelData loaded = CsvTableIO.loadChannelCsv(csv, "legacy");
        assertNotNull(loaded);
        assertEquals(RunIdCsv.RUN_ID_COLUMN, loaded.header.get(loaded.header.size() - 1));
        assertEquals("", loaded.get(0, RunIdCsv.RUN_ID_COLUMN));
    }

    @Test
    public void mergeResultsTableCsvAppendsRunIdToExistingRows() throws Exception {
        File csv = temp.newFile("merge.csv");
        List<String> header = new ArrayList<String>(Arrays.asList("Animal Name", "ManualNote"));
        Map<String, Integer> colIdx = new LinkedHashMap<String, Integer>();
        colIdx.put("Animal Name", Integer.valueOf(0));
        colIdx.put("ManualNote", Integer.valueOf(1));
        List<List<String>> rows = new ArrayList<List<String>>();
        rows.add(new ArrayList<String>(Arrays.asList("Mouse1", "keep")));
        CsvTableIO.writeChannelCsv(csv, new CsvTableIO.ChannelData("DAPI", header, rows, colIdx));

        ResultsTable table = new ResultsTable();
        table.incrementCounter();
        table.setValue("Animal Name", 0, "Mouse1");
        table.setValue("Metric", 0, 12);

        assertTrue(CsvTableIO.mergeResultsTableCsv(csv, table,
                Arrays.asList("Animal Name", "Metric"), "R-MERGE"));

        CsvTableIO.ChannelData loaded = CsvTableIO.loadChannelCsv(csv, "DAPI");
        assertNotNull(loaded);
        assertEquals(Arrays.asList("Animal Name", "ManualNote", "Metric", RunIdCsv.RUN_ID_COLUMN),
                loaded.header);
        assertEquals("keep", loaded.get(0, "ManualNote"));
        assertEquals("R-MERGE", loaded.get(0, RunIdCsv.RUN_ID_COLUMN));
    }

    @Test
    public void appendRetainsPriorRunIdsAndStampsOnlyAddedRows() throws Exception {
        File csv = temp.newFile("append.csv");
        ResultsTable first = measurement("Mouse1", 10);
        List<String> columns = Arrays.asList("Animal Name", "Metric");
        CsvTableIO.writeResultsTableCsv(csv, first, columns, "R-FIRST");
        assertTrue(CsvTableIO.appendResultsTableCsv(csv, csv, "DAPI",
                measurement("Mouse2", 20), columns, "R-SECOND"));
        assertTrue(CsvTableIO.appendResultsTableCsv(csv, csv, "DAPI",
                measurement("Mouse3", 30), columns, "R-THIRD"));

        CsvTableIO.ChannelData loaded = CsvTableIO.loadChannelCsv(csv, "DAPI");
        assertNotNull(loaded);
        assertEquals("R-FIRST", loaded.get(0, RunIdCsv.RUN_ID_COLUMN));
        assertEquals("R-SECOND", loaded.get(1, RunIdCsv.RUN_ID_COLUMN));
        assertEquals("R-THIRD", loaded.get(2, RunIdCsv.RUN_ID_COLUMN));
        assertEquals(10.0, Double.parseDouble(loaded.get(0, "Metric")), 0.0);
        assertEquals(RunIdCsv.RUN_ID_COLUMN, loaded.header.get(loaded.header.size() - 1));
    }

    @Test
    public void appendDoesNotAttributeLegacyRowsToNewRun() throws Exception {
        File csv = temp.newFile("legacy-append.csv");
        List<String> columns = Arrays.asList("Animal Name", "Metric");
        CsvTableIO.writeResultsTableCsv(csv, measurement("Mouse1", 10), columns);
        assertTrue(CsvTableIO.appendResultsTableCsv(csv, csv, "DAPI",
                measurement("Mouse2", 20), columns, "R-NEW"));

        CsvTableIO.ChannelData loaded = CsvTableIO.loadChannelCsv(csv, "DAPI");
        assertNotNull(loaded);
        assertEquals("", loaded.get(0, RunIdCsv.RUN_ID_COLUMN));
        assertEquals("R-NEW", loaded.get(1, RunIdCsv.RUN_ID_COLUMN));
    }

    @Test
    public void repeatedMergesRetainDeterministicSourceRunLineage() throws Exception {
        File csv = temp.newFile("lineage.csv");
        List<String> columns = Arrays.asList("Animal Name", "Metric");
        CsvTableIO.writeResultsTableCsv(csv, measurement("Mouse1", 10), columns, "R-FIRST");
        ResultsTable spatial = new ResultsTable();
        spatial.incrementCounter();
        spatial.setValue("Distance", 0, 5);
        assertTrue(CsvTableIO.mergeResultsTableCsv(csv, spatial,
                Arrays.asList("Distance"), "R-SECOND"));
        assertTrue(CsvTableIO.mergeResultsTableCsv(csv, spatial,
                Arrays.asList("Distance"), "R-THIRD"));
        assertTrue(CsvTableIO.mergeResultsTableCsv(csv, spatial,
                Arrays.asList("Distance"), "R-THIRD"));

        CsvTableIO.ChannelData loaded = CsvTableIO.loadChannelCsv(csv, "DAPI");
        assertNotNull(loaded);
        assertEquals(10.0, Double.parseDouble(loaded.get(0, "Metric")), 0.0);
        assertEquals("R-FIRST;R-SECOND;R-THIRD", loaded.get(0, RunIdCsv.SOURCE_RUN_ID_COLUMN));
        assertEquals("R-THIRD", loaded.get(0, RunIdCsv.RUN_ID_COLUMN));
    }

    private static ResultsTable measurement(String animal, double value) {
        ResultsTable table = new ResultsTable();
        table.incrementCounter();
        table.setValue("Animal Name", 0, animal);
        table.setValue("Metric", 0, value);
        return table;
    }
}
