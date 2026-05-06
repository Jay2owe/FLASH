package flash.pipeline.io;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

public class CsvSupportTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void joinRowAndParseRecord_roundTripCommaQuoteTrailingEmptyAndWhitespace() throws Exception {
        List<String> row = Arrays.asList(
                "plain",
                "with,comma",
                "He said \"hi\"",
                "",
                " tail ");

        String joined = CsvSupport.joinRow(row);

        assertArrayEquals(row.toArray(new String[0]), CsvSupport.parseRecord(joined));
    }

    @Test
    public void recordReader_roundTripsQuotedMultilineField() throws Exception {
        File csv = temp.newFile("multiline.csv");
        PrintWriter pw = CsvSupport.newWriter(csv);
        try {
            pw.println(CsvSupport.joinRow(Arrays.asList("A", "B")));
            pw.println(CsvSupport.joinRow(Arrays.asList("1", "two\nlines")));
        } finally {
            pw.close();
        }

        CsvSupport.RecordReader reader = CsvSupport.openRecordReader(csv);
        try {
            CsvSupport.Record header = reader.readRecord();
            CsvSupport.Record row = reader.readRecord();
            assertArrayEquals(new String[]{"A", "B"}, CsvSupport.parseRecord(header.text));
            assertArrayEquals(new String[]{"1", "two\nlines"}, CsvSupport.parseRecord(row.text));
        } finally {
            reader.close();
        }
    }

    @Test
    public void parseRecord_rejectsMalformedQuoteSequences() {
        try {
            CsvSupport.parseRecord("\"bad\"quote");
            throw new AssertionError("Expected malformed CSV to throw");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Unexpected character"));
        }
    }
}
