package flash.pipeline.results;

import flash.pipeline.io.CsvSupport;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Streaming CSV append helper for macro-like "extendTable" behavior. */
public final class CsvAppend {

    private static final String RECORD_SEPARATOR = System.lineSeparator();
    private static final long RECORD_SEPARATOR_UTF8_BYTES =
            utf8Length(RECORD_SEPARATOR);

    private CsvAppend() {}

    /**
     * Appends logical records from {@code srcCsv} to {@code destCsv}. Headers
     * must match, except that one side may omit the trailing {@code run_id}
     * column. Publication replaces the destination only after the complete
     * staged CSV has been reopened and validated.
     */
    public static void append(File destCsv, File srcCsv) throws Exception {
        appendWithMetrics(destCsv, srcCsv, CsvSupport.defaultReadLimits());
    }

    static AppendMetrics appendWithMetrics(File destCsv, File srcCsv,
                                           CsvSupport.ReadLimits limits) throws Exception {
        AppendMetrics metrics = new AppendMetrics();
        if (destCsv == null || srcCsv == null || !srcCsv.exists()) {
            return metrics;
        }
        if (limits == null) {
            throw new IOException("CSV append read limits are null");
        }
        if (Files.isRegularFile(srcCsv.toPath()) && Files.size(srcCsv.toPath()) == 0L) {
            return metrics;
        }

        final File destination = destCsv;
        final File source = srcCsv;
        final CsvSupport.ReadLimits readLimits = limits;
        final AppendMetrics appendMetrics = metrics;
        CsvSupport.writeAtomically(destination, new CsvSupport.WriterAction() {
            @Override
            public void write(PrintWriter writer) throws IOException {
                streamAppend(writer, destination, source, readLimits, appendMetrics);
            }
        });
        return metrics;
    }

    private static void streamAppend(PrintWriter writer, File destination, File source,
                                     CsvSupport.ReadLimits limits, AppendMetrics metrics)
            throws IOException {
        CsvSupport.RecordReader sourceReader = CsvSupport.openRecordReader(source, limits);
        try {
            CsvSupport.Record sourceHeaderRecord = readRecord(sourceReader, metrics);
            if (sourceHeaderRecord == null) {
                return;
            }

            if (!destination.isFile() || Files.size(destination.toPath()) == 0L) {
                writeRecord(writer, sourceHeaderRecord.text, metrics, limits);
                copyRemainingRecords(sourceReader, writer, metrics, limits);
                return;
            }

            CsvSupport.RecordReader destinationReader =
                    CsvSupport.openRecordReader(destination, limits);
            try {
                CsvSupport.Record destinationHeaderRecord =
                        readRecord(destinationReader, metrics);
                if (destinationHeaderRecord == null) {
                    writeRecord(writer, sourceHeaderRecord.text, metrics, limits);
                    copyRemainingRecords(sourceReader, writer, metrics, limits);
                    return;
                }

                String[] destinationHeader =
                        CsvSupport.parseRecord(destinationHeaderRecord.text);
                String[] sourceHeader = CsvSupport.parseRecord(sourceHeaderRecord.text);
                boolean migrateRunId = false;
                if (!headersMatch(destinationHeader, sourceHeader)) {
                    if (runIdCompatible(destinationHeader, sourceHeader)) {
                        migrateRunId = true;
                    } else {
                        throw new IOException("CSV headers do not match: "
                                + destination.getName() + " has "
                                + Arrays.toString(destinationHeader) + ", "
                                + source.getName() + " has "
                                + Arrays.toString(sourceHeader));
                    }
                }

                if (migrateRunId) {
                    writeRecord(writer, transformedHeader(destinationHeader), metrics, limits);
                    transformRemainingRecords(destinationReader, destinationHeader,
                            writer, metrics, limits);
                    transformRemainingRecords(sourceReader, sourceHeader,
                            writer, metrics, limits);
                } else {
                    writeRecord(writer, destinationHeaderRecord.text, metrics, limits);
                    copyRemainingRecords(destinationReader, writer, metrics, limits);
                    copyRemainingRecords(sourceReader, writer, metrics, limits);
                }
            } finally {
                destinationReader.close();
            }
        } finally {
            sourceReader.close();
        }
    }

    private static void copyRemainingRecords(CsvSupport.RecordReader reader,
                                             PrintWriter writer,
                                             AppendMetrics metrics,
                                             CsvSupport.ReadLimits limits) throws IOException {
        CsvSupport.Record record;
        while ((record = readRecord(reader, metrics)) != null) {
            writeRecord(writer, record.text, metrics, limits);
        }
    }

    private static void transformRemainingRecords(CsvSupport.RecordReader reader,
                                                  String[] header,
                                                  PrintWriter writer,
                                                  AppendMetrics metrics,
                                                  CsvSupport.ReadLimits limits) throws IOException {
        CsvSupport.Record record;
        int runIdIndex = runIdIndex(header);
        while ((record = readRecord(reader, metrics)) != null) {
            String[] fields = CsvSupport.parseRecord(record.text);
            List<String> row = new ArrayList<String>();
            for (int column = 0; column < header.length; column++) {
                if (column == runIdIndex) {
                    continue;
                }
                row.add(column < fields.length ? fields[column] : "");
            }
            String runId = runIdIndex >= 0 && runIdIndex < fields.length
                    ? fields[runIdIndex] : "";
            writeRecord(writer, CsvSupport.joinRow(
                    RunIdCsv.appendRunIdRow(row, runId)), metrics, limits);
        }
    }

    private static String transformedHeader(String[] header) {
        return CsvSupport.joinRow(
                RunIdCsv.appendRunIdHeader(Arrays.asList(header)));
    }

    private static CsvSupport.Record readRecord(CsvSupport.RecordReader reader,
                                                AppendMetrics metrics) throws IOException {
        CsvSupport.Record record = reader.readRecord();
        if (record != null) {
            metrics.logicalRecordsRead++;
            metrics.maximumRecordCharacters = Math.max(
                    metrics.maximumRecordCharacters, record.text.length());
        }
        return record;
    }

    private static void writeRecord(PrintWriter writer, String record,
                                    AppendMetrics metrics,
                                    CsvSupport.ReadLimits limits) throws IOException {
        metrics.reserveOutput(record, limits);
        writer.println(record);
    }

    private static long utf8Length(CharSequence text) {
        long bytes = 0L;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch <= 0x7f) {
                bytes++;
            } else if (ch <= 0x7ff) {
                bytes += 2L;
            } else if (Character.isHighSurrogate(ch)
                    && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                bytes += 4L;
                i++;
            } else if (Character.isSurrogate(ch)) {
                // OutputStreamWriter's UTF-8 encoder replaces an unpaired
                // surrogate with a one-byte question mark by default.
                bytes++;
            } else {
                bytes += 3L;
            }
        }
        return bytes;
    }

    private static boolean runIdCompatible(String[] destinationHeader,
                                           String[] sourceHeader) {
        List<String> destination = withoutRunId(destinationHeader);
        List<String> source = withoutRunId(sourceHeader);
        if (destination.size() != source.size()) {
            return false;
        }
        for (int i = 0; i < destination.size(); i++) {
            if (!destination.get(i).equals(source.get(i))) {
                return false;
            }
        }
        return hasRunId(destinationHeader) || hasRunId(sourceHeader);
    }

    private static List<String> withoutRunId(String[] header) {
        List<String> result = new ArrayList<String>();
        if (header == null) {
            return result;
        }
        for (String column : header) {
            String cleaned = column == null ? "" : column.trim();
            if (!RunIdCsv.RUN_ID_COLUMN.equals(cleaned)) {
                result.add(cleaned);
            }
        }
        return result;
    }

    private static boolean hasRunId(String[] header) {
        return runIdIndex(header) >= 0;
    }

    private static int runIdIndex(String[] header) {
        if (header == null) {
            return -1;
        }
        for (int i = 0; i < header.length; i++) {
            String column = header[i] == null ? "" : header[i].trim();
            if (RunIdCsv.RUN_ID_COLUMN.equals(column)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean headersMatch(String[] destinationHeader, String[] sourceHeader) {
        if (destinationHeader == null || sourceHeader == null
                || destinationHeader.length != sourceHeader.length) {
            return false;
        }
        for (int i = 0; i < destinationHeader.length; i++) {
            String left = destinationHeader[i] == null ? "" : destinationHeader[i].trim();
            String right = sourceHeader[i] == null ? "" : sourceHeader[i].trim();
            if (!left.equals(right)) {
                return false;
            }
        }
        return true;
    }

    /** Package-visible bounded-memory evidence for the append regression tests. */
    static final class AppendMetrics {
        private long logicalRecordsRead;
        private long logicalRecordsWritten;
        private long stagedUtf8Bytes;
        private int maximumRecordCharacters;

        private void reserveOutput(String record, CsvSupport.ReadLimits limits)
                throws IOException {
            if (record.length() > limits.maxRecordCharacters) {
                throw new IOException("Staged CSV logical record exceeds the explicit "
                        + limits.maxRecordCharacters + " character limit after transformation");
            }
            if (logicalRecordsWritten >= limits.maxRecords) {
                throw new IOException("Staged CSV exceeds the explicit "
                        + limits.maxRecords + " logical-record limit");
            }
            long recordBytes = utf8Length(record) + RECORD_SEPARATOR_UTF8_BYTES;
            if (recordBytes > limits.maxBytes - stagedUtf8Bytes) {
                throw new IOException("Staged CSV exceeds the explicit "
                        + limits.maxBytes + " byte limit");
            }
            stagedUtf8Bytes += recordBytes;
            logicalRecordsWritten++;
            maximumRecordCharacters = Math.max(
                    maximumRecordCharacters, record.length());
        }

        long getLogicalRecordsRead() {
            return logicalRecordsRead;
        }

        long getLogicalRecordsWritten() {
            return logicalRecordsWritten;
        }

        long getStagedUtf8Bytes() {
            return stagedUtf8Bytes;
        }

        int getMaximumRecordCharacters() {
            return maximumRecordCharacters;
        }
    }
}
