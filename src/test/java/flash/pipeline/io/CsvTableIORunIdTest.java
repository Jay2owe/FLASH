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
}
