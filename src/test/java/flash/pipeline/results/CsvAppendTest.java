package flash.pipeline.results;

import flash.pipeline.io.CsvSupport;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CsvAppendTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void append_rejectsMismatchedHeadersWithoutChangingDestination() throws Exception {
        File dest = temp.newFile("dest.csv");
        File src = temp.newFile("src.csv");
        Files.write(dest.toPath(), Arrays.asList("Animal,ROI,Area", "A,1,10"),
                StandardCharsets.UTF_8);
        Files.write(src.toPath(), Arrays.asList("Animal,Area", "B,20"),
                StandardCharsets.UTF_8);

        try {
            CsvAppend.append(dest, src);
            fail("Expected header mismatch to fail");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("CSV headers do not match"));
        }

        assertEquals(Arrays.asList("Animal,ROI,Area", "A,1,10"),
                Files.readAllLines(dest.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    public void append_copiesSourceWhenDestinationIsEmpty() throws Exception {
        File dest = temp.newFile("dest-empty.csv");
        File src = temp.newFile("src.csv");
        Files.write(src.toPath(), Arrays.asList("Animal,ROI", "A,1"),
                StandardCharsets.UTF_8);

        CsvAppend.append(dest, src);

        assertEquals(Arrays.asList("Animal,ROI", "A,1"),
                Files.readAllLines(dest.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    public void append_migratesRunIdOnLogicalMultilineRecords() throws Exception {
        File dest = temp.newFile("dest-multiline.csv");
        File src = temp.newFile("src-multiline.csv");
        Files.write(dest.toPath(), (
                "Animal,Note,run_id\r\n"
                        + "A,\"first\r\nline \"\"quoted\"\"\",run-a\r\n")
                .getBytes(StandardCharsets.UTF_8));
        Files.write(src.toPath(), (
                "Animal,Note\r\n"
                        + "B,\"second\nline \"\"quoted\"\"\"\r\n")
                .getBytes(StandardCharsets.UTF_8));

        CsvAppend.append(dest, src);

        List<String[]> records = readRecords(dest);
        assertEquals(3, records.size());
        assertArrayEquals(new String[]{"Animal", "Note", "run_id"}, records.get(0));
        assertArrayEquals(new String[]{"A", "first\r\nline \"quoted\"", "run-a"},
                records.get(1));
        assertArrayEquals(new String[]{"B", "second\nline \"quoted\"", ""},
                records.get(2));
    }

    @Test
    public void append_streamsLargeInputWithOneLogicalRecordBoundedAtATime() throws Exception {
        final int rows = 25_000;
        File dest = temp.newFile("dest-large.csv");
        File src = temp.newFile("src-large.csv");
        writeRows(dest, 1, 0);
        writeRows(src, rows, 1);

        CsvSupport.ReadLimits limits = new CsvSupport.ReadLimits(
                8L * 1024L * 1024L, rows + 10L, 128);
        CsvAppend.AppendMetrics metrics =
                CsvAppend.appendWithMetrics(dest, src, limits);

        assertEquals(rows + 3L, metrics.getLogicalRecordsRead());
        assertEquals(rows + 2L, metrics.getLogicalRecordsWritten());
        assertTrue("Append must retain at most a bounded logical record, not the whole file",
                metrics.getMaximumRecordCharacters() < 64);
        assertTrue(metrics.getStagedUtf8Bytes() <= limits.maxBytes);
        assertEquals(rows + 2L, countRecords(dest));
    }

    @Test
    public void append_budgetFailurePreservesExactPriorBytesAndLeaksNoTemp() throws Exception {
        File dest = temp.newFile("dest-budget.csv");
        File src = temp.newFile("src-budget.csv");
        byte[] prior = "A,B\r\nold,\"last\r\ngood\"\r\n".getBytes(StandardCharsets.UTF_8);
        Files.write(dest.toPath(), prior);
        Files.write(src.toPath(), (
                "A,B\nnew,\"this logical record is deliberately too long\"\n")
                .getBytes(StandardCharsets.UTF_8));

        try {
            CsvAppend.appendWithMetrics(dest, src,
                    new CsvSupport.ReadLimits(1024L, 10L, 24));
            fail("Expected the logical-record budget to reject the append");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("character limit"));
        }

        assertArrayEquals(prior, Files.readAllBytes(dest.toPath()));
        assertNoLeftoverTemp(dest);
    }

    @Test
    public void append_combinedOutputRecordBudgetPreservesExactPriorBytes() throws Exception {
        File dest = temp.newFile("dest-record-total.csv");
        File src = temp.newFile("src-record-total.csv");
        byte[] prior = "A\nold-1\nold-2\n".getBytes(StandardCharsets.UTF_8);
        Files.write(dest.toPath(), prior);
        Files.write(src.toPath(), "A\nnew-1\nnew-2\n".getBytes(StandardCharsets.UTF_8));

        try {
            CsvAppend.appendWithMetrics(dest, src,
                    new CsvSupport.ReadLimits(1024L, 4L, 64));
            fail("Expected combined staged record budget to reject the append");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Staged CSV"));
            assertTrue(expected.getMessage().contains("logical-record limit"));
        }

        assertArrayEquals(prior, Files.readAllBytes(dest.toPath()));
        assertNoLeftoverTemp(dest);
    }

    @Test
    public void append_combinedOutputByteBudgetPreservesExactPriorBytes() throws Exception {
        File dest = temp.newFile("dest-byte-total.csv");
        File src = temp.newFile("src-byte-total.csv");
        byte[] prior = "A\n12345678901234567890\n".getBytes(StandardCharsets.UTF_8);
        byte[] source = "A\nabcdefghijklmnopqrst\n".getBytes(StandardCharsets.UTF_8);
        assertTrue(prior.length < 30);
        assertTrue(source.length < 30);
        Files.write(dest.toPath(), prior);
        Files.write(src.toPath(), source);

        try {
            CsvAppend.appendWithMetrics(dest, src,
                    new CsvSupport.ReadLimits(30L, 10L, 64));
            fail("Expected combined staged byte budget to reject the append");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Staged CSV"));
            assertTrue(expected.getMessage().contains("byte limit"));
        }

        assertArrayEquals(prior, Files.readAllBytes(dest.toPath()));
        assertNoLeftoverTemp(dest);
    }

    @Test
    public void append_runIdMigrationExpansionHonorsOutputRecordBudget() throws Exception {
        File dest = temp.newFile("dest-migration-budget.csv");
        File src = temp.newFile("src-migration-budget.csv");
        byte[] prior = "A,run_id\nold,r\n".getBytes(StandardCharsets.UTF_8);
        Files.write(dest.toPath(), prior);
        Files.write(src.toPath(), "A\n 1234567890\n".getBytes(StandardCharsets.UTF_8));

        try {
            CsvAppend.appendWithMetrics(dest, src,
                    new CsvSupport.ReadLimits(1024L, 10L, 12));
            fail("Expected transformed record budget to reject expanded quoting");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("after transformation"));
            assertTrue(expected.getMessage().contains("character limit"));
        }

        assertArrayEquals(prior, Files.readAllBytes(dest.toPath()));
        assertNoLeftoverTemp(dest);
    }

    @Test
    public void append_reopenValidationFailurePreservesExactPriorBytesAndLeaksNoTemp()
            throws Exception {
        File dest = temp.newFile("dest-invalid.csv");
        File src = temp.newFile("src-invalid.csv");
        byte[] prior = "A,B\r\nold,7\r\n".getBytes(StandardCharsets.UTF_8);
        Files.write(dest.toPath(), prior);
        Files.write(src.toPath(), "A,B\nnew,\"bad\"suffix\n".getBytes(StandardCharsets.UTF_8));

        try {
            CsvAppend.append(dest, src);
            fail("Expected staged CSV validation to reject malformed source data");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Unexpected character"));
        }

        assertArrayEquals(prior, Files.readAllBytes(dest.toPath()));
        assertNoLeftoverTemp(dest);
    }

    private static void writeRows(File file, int rows, int offset) throws IOException {
        PrintWriter writer = CsvSupport.newWriter(file);
        try {
            writer.println("Animal,Value");
            for (int i = 0; i < rows; i++) {
                writer.println("A" + (i + offset) + "," + (i + offset));
            }
            if (writer.checkError()) {
                throw new IOException("Failed to create CSV fixture " + file);
            }
        } finally {
            writer.close();
        }
    }

    private static long countRecords(File file) throws IOException {
        long records = 0L;
        CsvSupport.RecordReader reader = CsvSupport.openRecordReader(file);
        try {
            while (reader.readRecord() != null) {
                records++;
            }
        } finally {
            reader.close();
        }
        return records;
    }

    private static List<String[]> readRecords(File file) throws IOException {
        List<String[]> records = new ArrayList<String[]>();
        CsvSupport.RecordReader reader = CsvSupport.openRecordReader(file);
        try {
            CsvSupport.Record record;
            while ((record = reader.readRecord()) != null) {
                records.add(CsvSupport.parseRecord(record.text));
            }
        } finally {
            reader.close();
        }
        return records;
    }

    private static void assertNoLeftoverTemp(File csv) {
        File[] leftovers = csv.getAbsoluteFile().getParentFile().listFiles((dir, name) ->
                name.startsWith("." + csv.getName() + ".") && name.endsWith(".tmp"));
        assertTrue(leftovers == null || leftovers.length == 0);
    }
}
