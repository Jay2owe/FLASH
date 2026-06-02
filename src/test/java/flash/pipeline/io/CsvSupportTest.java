package flash.pipeline.io;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
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
    public void joinRow_escapesSpreadsheetFormulaPrefixes() throws Exception {
        String joined = CsvSupport.joinRow(Arrays.asList(
                "=cmd",
                "+cmd",
                "-cmd",
                "@cmd",
                "\tcmd",
                "safe"));

        assertArrayEquals(new String[]{
                "'=cmd",
                "'+cmd",
                "'-cmd",
                "'@cmd",
                "'\tcmd",
                "safe"
        }, CsvSupport.parseRecord(joined));
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

    @Test
    public void writeAtomicallyReplacesFinalFileAndRemovesTemp() throws Exception {
        File csv = temp.newFile("atomic.csv");
        Files.write(csv.toPath(), Arrays.asList("old"), CsvSupport.CHARSET);

        CsvSupport.writeAtomically(csv, new CsvSupport.WriterAction() {
            @Override
            public void write(PrintWriter writer) {
                writer.println("new");
            }
        });

        assertEquals(Arrays.asList("new"), Files.readAllLines(csv.toPath(), CsvSupport.CHARSET));
        assertNoLeftoverTemp(csv);
    }

    private static void assertNoLeftoverTemp(File csv) {
        File[] leftovers = csv.getAbsoluteFile().getParentFile().listFiles((dir, name) ->
                name.startsWith("." + csv.getName() + ".") && name.endsWith(".tmp"));
        assertTrue(leftovers == null || leftovers.length == 0);
    }
}
