package flash.pipeline.analyses;

import flash.pipeline.io.CsvTableIO;
import flash.pipeline.results.CsvAppend;
import flash.pipeline.results.RunIdCsv;
import ij.measure.ResultsTable;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class DrawAndSaveROIsAnalysisRunIdCsvTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void resultsTableSaveAndAppendAddsRunIdToLegacyDestination() throws Exception {
        File dest = temp.newFile("SCN ROI Properties.csv");
        Files.write(dest.toPath(), Arrays.asList(
                "Animal Name,Area",
                "Mouse1,10"),
                StandardCharsets.UTF_8);

        ResultsTable table = new ResultsTable();
        table.incrementCounter();
        table.setValue("Animal Name", 0, "Mouse2");
        table.setValue("Area", 0, 20);
        table.setValue(RunIdCsv.RUN_ID_COLUMN, 0, "R-DRAW");
        File tmp = temp.newFile("SCN ROI Properties.tmp.csv");
        table.save(tmp.getAbsolutePath());

        CsvAppend.append(dest, tmp);

        CsvTableIO.ChannelData loaded = CsvTableIO.loadChannelCsv(dest, "roi");
        assertNotNull(loaded);
        assertEquals(RunIdCsv.RUN_ID_COLUMN, loaded.header.get(loaded.header.size() - 1));
        assertEquals("", loaded.get(0, RunIdCsv.RUN_ID_COLUMN));
        assertEquals("R-DRAW", loaded.get(1, RunIdCsv.RUN_ID_COLUMN));
    }
}
