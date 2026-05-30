package flash.pipeline.results;

import flash.pipeline.io.CsvSupport;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class RunIdCsvTest {

    @Test
    public void appendRunIdPreservesOrderEscapingAndRowLength() throws Exception {
        List<String> header = RunIdCsv.appendRunIdHeader(Arrays.asList("Animal", "Note", "Value"));
        List<String> row = RunIdCsv.appendRunIdRow(Arrays.asList(
                "Mouse, \"Alpha\"",
                "Line1\nLine2",
                "=kept-safe"),
                "R1,\"quoted\"");

        String[] parsedHeader = CsvSupport.parseRecord(CsvSupport.joinRow(header));
        String[] parsedRow = CsvSupport.parseRecord(CsvSupport.joinRow(row));

        assertArrayEquals(new String[] {"Animal", "Note", "Value", "run_id"}, parsedHeader);
        assertEquals(parsedHeader.length, parsedRow.length);
        assertEquals("Mouse, \"Alpha\"", parsedRow[0]);
        assertEquals("Line1\nLine2", parsedRow[1]);
        assertEquals("'=kept-safe", parsedRow[2]);
        assertEquals("R1,\"quoted\"", parsedRow[3]);
    }

    @Test
    public void appendSourceAndRunIdKeepsRunIdLast() {
        List<String> header = RunIdCsv.appendSourceAndRunIdHeader(Arrays.asList(
                "AnimalName", "run_id", "Metric"));
        List<String> row = RunIdCsv.appendSourceAndRunIdRow(Arrays.asList("Mouse1", "12"),
                "R1", "R2");

        assertEquals(Arrays.asList("AnimalName", "Metric", "source_run_id", "run_id"), header);
        assertEquals(Arrays.asList("Mouse1", "12", "R1", "R2"), row);
    }
}
