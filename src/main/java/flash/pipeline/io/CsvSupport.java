package flash.pipeline.io;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared CSV parsing and writing support for pipeline-generated files.
 */
public final class CsvSupport {

    public static final Charset CHARSET = StandardCharsets.UTF_8;
    public static final long DEFAULT_MAX_CSV_BYTES = 16L * 1024L * 1024L * 1024L;
    public static final long DEFAULT_MAX_CSV_RECORDS = 50_000_000L;
    public static final int DEFAULT_MAX_RECORD_CHARACTERS = 16 * 1024 * 1024;
    public static final int DEFAULT_MAX_FIELDS_PER_RECORD = 100_000;

    private static final ReadLimits DEFAULT_READ_LIMITS = new ReadLimits(
            DEFAULT_MAX_CSV_BYTES,
            DEFAULT_MAX_CSV_RECORDS,
            DEFAULT_MAX_RECORD_CHARACTERS);

    private static final PublicationOperations DEFAULT_PUBLICATION_OPERATIONS =
            new PublicationOperations() {
                @Override
                public PrintWriter openWriter(File file) throws IOException {
                    return newWriter(file);
                }

                @Override
                public void validate(File file) throws IOException {
                    validateCsv(file);
                }

                @Override
                public void replace(Path source, Path target) throws IOException {
                    IoUtils.moveReplacing(source, target);
                }
            };

    private static volatile PublicationOperations publicationOperations =
            DEFAULT_PUBLICATION_OPERATIONS;

    private CsvSupport() {}

    public static RecordReader openRecordReader(File file) throws IOException {
        return openRecordReader(file, DEFAULT_READ_LIMITS);
    }

    public static RecordReader openRecordReader(File file, ReadLimits limits) throws IOException {
        return new RecordReader(file, limits);
    }

    public static ReadLimits defaultReadLimits() {
        return DEFAULT_READ_LIMITS;
    }

    public static PrintWriter newWriter(File file) throws IOException {
        return new PrintWriter(Files.newBufferedWriter(file.toPath(), CHARSET));
    }

    public static void writeAtomically(File file, WriterAction action) throws IOException {
        if (file == null) throw new IOException("CSV output file is null");
        if (action == null) throw new IOException("CSV writer action is null for "
                + file.getAbsolutePath());
        File target = file.getAbsoluteFile();
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            Files.createDirectories(parent.toPath());
        }
        File temp = File.createTempFile("." + target.getName() + ".", ".tmp", parent);
        boolean published = false;
        Throwable primaryFailure = null;
        try {
            PublicationOperations operations = publicationOperations;
            PrintWriter writer = operations.openWriter(temp);
            try {
                action.write(writer);
                writer.flush();
                if (writer.checkError()) {
                    throw new IOException("Failed while writing temporary CSV: " + temp.getAbsolutePath());
                }
            } finally {
                writer.close();
            }

            forceFile(temp.toPath());
            operations.validate(temp);
            long stagedBytes = Files.size(temp.toPath());
            operations.replace(temp.toPath(), target.toPath());
            verifyCommittedFile(target.toPath(), stagedBytes);
            published = true;
        } catch (IOException failure) {
            primaryFailure = failure;
            throw failure;
        } catch (RuntimeException failure) {
            primaryFailure = failure;
            throw failure;
        } catch (Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            if (!published) {
                try {
                    Files.deleteIfExists(temp.toPath());
                } catch (IOException cleanupFailure) {
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(cleanupFailure);
                    } else {
                        throw cleanupFailure;
                    }
                }
            }
        }
    }

    private static void forceFile(Path file) throws IOException {
        FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE);
        try {
            channel.force(true);
        } finally {
            channel.close();
        }
    }

    private static void verifyCommittedFile(Path target, long expectedBytes) throws IOException {
        if (!Files.isRegularFile(target)) {
            throw new IOException("Committed CSV is not a regular file: " + target);
        }
        long actualBytes = Files.size(target);
        if (actualBytes != expectedBytes) {
            throw new IOException("Committed CSV size changed for " + target
                    + ": expected " + expectedBytes + " bytes but found " + actualBytes);
        }
    }

    private static void validateCsv(File file) throws IOException {
        if (!file.isFile()) {
            throw new IOException("Temporary CSV is not a regular file: "
                    + file.getAbsolutePath());
        }
        if (file.length() <= 0L) {
            throw new IOException("Temporary CSV is empty: " + file.getAbsolutePath());
        }

        RecordReader reader = openRecordReader(file);
        long records = 0L;
        try {
            Record record;
            while ((record = reader.readRecord()) != null) {
                parseRecord(record.text);
                records++;
            }
        } finally {
            reader.close();
        }
        if (records == 0) {
            throw new IOException("Temporary CSV has no readable records: "
                    + file.getAbsolutePath());
        }
    }

    /** Narrow file-operation seam used by deterministic publication-fault tests. */
    interface PublicationOperations {
        PrintWriter openWriter(File file) throws IOException;

        void validate(File file) throws IOException;

        void replace(Path source, Path target) throws IOException;
    }

    static PublicationOperations defaultPublicationOperations() {
        return DEFAULT_PUBLICATION_OPERATIONS;
    }

    static void setPublicationOperationsForTest(PublicationOperations operations) {
        if (operations == null) {
            throw new IllegalArgumentException("publication operations must not be null");
        }
        publicationOperations = operations;
    }

    static void resetPublicationOperationsForTest() {
        publicationOperations = DEFAULT_PUBLICATION_OPERATIONS;
    }

    public interface WriterAction {
        void write(PrintWriter writer) throws IOException;
    }

    public static boolean isBlankRecord(String record) {
        return record == null || record.trim().isEmpty();
    }

    public static String[] parseRecord(String record) throws IOException {
        if (record == null) {
            throw new IOException("CSV record is null");
        }
        if (record.length() > DEFAULT_MAX_RECORD_CHARACTERS) {
            throw new IOException("CSV record exceeds the "
                    + DEFAULT_MAX_RECORD_CHARACTERS + " character limit");
        }
        List<String> fields = new ArrayList<String>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean afterClosingQuote = false;

        for (int i = 0; i < record.length(); i++) {
            char ch = record.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < record.length() && record.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                        afterClosingQuote = true;
                    }
                } else {
                    field.append(ch);
                }
                continue;
            }

            if (afterClosingQuote) {
                if (ch == ',') {
                    addField(fields, field);
                    field.setLength(0);
                    afterClosingQuote = false;
                } else if (!Character.isWhitespace(ch)) {
                    throw new IOException("Unexpected character '" + ch
                            + "' after closing quote at column " + (i + 1)
                            + " in record: " + preview(record));
                }
                continue;
            }

            if (ch == ',') {
                addField(fields, field);
                field.setLength(0);
            } else if (ch == '"') {
                if (field.length() != 0) {
                    throw new IOException("Unexpected quote inside unquoted field at column "
                            + (i + 1) + " in record: " + preview(record));
                }
                inQuotes = true;
            } else {
                field.append(ch);
            }
        }

        if (inQuotes) {
            throw new IOException("Unterminated quoted field in record: " + preview(record));
        }

        addField(fields, field);
        return fields.toArray(new String[0]);
    }

    private static void addField(List<String> fields, StringBuilder field) throws IOException {
        if (fields.size() >= DEFAULT_MAX_FIELDS_PER_RECORD) {
            throw new IOException("CSV record exceeds the "
                    + DEFAULT_MAX_FIELDS_PER_RECORD + " field limit");
        }
        fields.add(field.toString());
    }

    /**
     * Serialize one text cell for CSV and make spreadsheet-active prefixes inert.
     * Numeric cells that must retain numeric spreadsheet semantics must be emitted
     * from their numeric representation instead of passing through this method.
     */
    public static String escapeField(String value) {
        String text = spreadsheetSafeFieldValue(value);
        boolean needsQuotes = text.indexOf(',') >= 0
                || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0
                || text.indexOf('\r') >= 0
                || startsOrEndsWithWhitespace(text);
        if (!needsQuotes) return text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    public static String joinRow(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(escapeField(values.get(i)));
        }
        return sb.toString();
    }

    private static String preview(String value) {
        if (value == null) {
            return "<null>";
        }
        StringBuilder flat = new StringBuilder(124);
        boolean truncated = false;
        for (int i = 0; i < value.length(); i++) {
            String part;
            char ch = value.charAt(i);
            if (ch == '\r') {
                part = "\\r";
            } else if (ch == '\n') {
                part = "\\n";
            } else {
                part = String.valueOf(ch);
            }
            if (flat.length() + part.length() > 120) {
                truncated = true;
                break;
            }
            flat.append(part);
        }
        return "'" + flat + (truncated ? "...'" : "'");
    }

    /**
     * Prefix text with an apostrophe when a spreadsheet could treat it as a formula.
     * The active characters are {@code =}, {@code +}, {@code -}, {@code @}, tab,
     * carriage return, and line feed. Leading Unicode whitespace and control/format
     * characters are skipped when looking for an active formula character because
     * spreadsheet importers may discard them.
     */
    public static String spreadsheetSafeFieldValue(String value) {
        String text = value == null ? "" : value;
        if (text.isEmpty()) return text;

        for (int offset = 0; offset < text.length();) {
            int current = text.codePointAt(offset);
            if (current == '=' || current == '+' || current == '-' || current == '@'
                    || current == '\t' || current == '\r' || current == '\n') {
                return "'" + text;
            }
            if (!isLeadingSpreadsheetWhitespaceOrControl(current)) {
                break;
            }
            offset += Character.charCount(current);
        }
        return text;
    }

    private static boolean isLeadingSpreadsheetWhitespaceOrControl(int value) {
        return Character.isWhitespace(value)
                || Character.isSpaceChar(value)
                || Character.isISOControl(value)
                || Character.getType(value) == Character.FORMAT;
    }

    private static boolean startsOrEndsWithWhitespace(String text) {
        if (text.isEmpty()) return false;
        return Character.isWhitespace(text.charAt(0))
                || Character.isWhitespace(text.charAt(text.length() - 1));
    }

    /** Explicit limits for one streamed CSV input. */
    public static final class ReadLimits {
        public final long maxBytes;
        public final long maxRecords;
        public final int maxRecordCharacters;

        public ReadLimits(long maxBytes, long maxRecords, int maxRecordCharacters) {
            if (maxBytes <= 0L) {
                throw new IllegalArgumentException("maxBytes must be positive");
            }
            if (maxRecords <= 0L) {
                throw new IllegalArgumentException("maxRecords must be positive");
            }
            if (maxRecordCharacters <= 0) {
                throw new IllegalArgumentException("maxRecordCharacters must be positive");
            }
            this.maxBytes = maxBytes;
            this.maxRecords = maxRecords;
            this.maxRecordCharacters = maxRecordCharacters;
        }
    }

    public static final class Record {
        public final String text;
        public final int startLineNumber;
        public final int endLineNumber;

        private Record(String text, int startLineNumber, int endLineNumber) {
            this.text = text;
            this.startLineNumber = startLineNumber;
            this.endLineNumber = endLineNumber;
        }
    }

    public static final class RecordReader implements Closeable {
        private final BufferedReader reader;
        private final BoundedInputStream input;
        private final String sourceName;
        private final ReadLimits limits;
        private int nextLineNumber = 1;
        private int pendingCharacter = -1;
        private long recordsRead;
        private int maximumRecordCharactersObserved;

        private RecordReader(File file, ReadLimits limits) throws IOException {
            if (file == null) {
                throw new IOException("CSV input file is null");
            }
            if (limits == null) {
                throw new IOException("CSV read limits are null for " + file.getAbsolutePath());
            }
            Path path = file.toPath();
            long declaredBytes = Files.size(path);
            if (declaredBytes > limits.maxBytes) {
                throw new IOException("CSV input " + file.getAbsolutePath() + " is "
                        + declaredBytes + " bytes, exceeding the explicit "
                        + limits.maxBytes + " byte limit");
            }
            this.input = new BoundedInputStream(Files.newInputStream(path),
                    limits.maxBytes, file.getAbsolutePath());
            this.reader = new BufferedReader(new InputStreamReader(input,
                    CHARSET.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)));
            this.sourceName = file.getName();
            this.limits = limits;
        }

        public Record readRecord() throws IOException {
            int character = readCharacter();
            if (character < 0) return null;
            if (recordsRead >= limits.maxRecords) {
                throw new IOException("CSV input " + sourceName + " exceeds the explicit "
                        + limits.maxRecords + " logical-record limit");
            }

            int startLine = nextLineNumber;
            StringBuilder record = new StringBuilder(Math.min(256,
                    limits.maxRecordCharacters));
            boolean inQuotes = false;

            while (character >= 0) {
                char ch = (char) character;
                if (ch == '"') {
                    appendBounded(record, ch, startLine);
                    if (inQuotes) {
                        int next = readCharacter();
                        if (next == '"') {
                            appendBounded(record, '"', startLine);
                            character = readCharacter();
                            continue;
                        }
                        inQuotes = false;
                        character = next;
                        continue;
                    }
                    inQuotes = true;
                    character = readCharacter();
                    continue;
                }

                if (ch == '\r' || ch == '\n') {
                    boolean crlf = false;
                    if (ch == '\r') {
                        int next = readCharacter();
                        if (next == '\n') {
                            crlf = true;
                        } else {
                            pendingCharacter = next;
                        }
                    }
                    if (inQuotes) {
                        appendBounded(record, ch, startLine);
                        if (crlf) {
                            appendBounded(record, '\n', startLine);
                        }
                        nextLineNumber++;
                        character = readCharacter();
                        continue;
                    }
                    nextLineNumber++;
                    return completedRecord(record, startLine, nextLineNumber - 1);
                }

                appendBounded(record, ch, startLine);
                character = readCharacter();
            }

            if (inQuotes) {
                throw new IOException("Malformed CSV in " + sourceName
                        + " starting at line " + startLine
                        + ": unterminated quoted field");
            }
            return completedRecord(record, startLine, nextLineNumber);
        }

        public long getRecordsRead() {
            return recordsRead;
        }

        public long getBytesRead() {
            return input.getBytesRead();
        }

        public int getMaximumRecordCharactersObserved() {
            return maximumRecordCharactersObserved;
        }

        private int readCharacter() throws IOException {
            if (pendingCharacter >= 0) {
                int character = pendingCharacter;
                pendingCharacter = -1;
                return character;
            }
            return reader.read();
        }

        private void appendBounded(StringBuilder record, char character,
                                   int startLine) throws IOException {
            if (record.length() >= limits.maxRecordCharacters) {
                throw new IOException("CSV logical record in " + sourceName
                        + " starting at line " + startLine + " exceeds the explicit "
                        + limits.maxRecordCharacters + " character limit");
            }
            record.append(character);
            maximumRecordCharactersObserved = Math.max(
                    maximumRecordCharactersObserved, record.length());
        }

        private Record completedRecord(StringBuilder record, int startLine, int endLine) {
            recordsRead++;
            maximumRecordCharactersObserved = Math.max(
                    maximumRecordCharactersObserved, record.length());
            return new Record(record.toString(), startLine, endLine);
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private final long maxBytes;
        private final String sourceName;
        private long bytesRead;

        private BoundedInputStream(InputStream input, long maxBytes, String sourceName) {
            super(input);
            this.maxBytes = maxBytes;
            this.sourceName = sourceName;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                countBytes(1L);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            long remaining = maxBytes - bytesRead;
            int boundedLength = remaining >= length
                    ? length
                    : (int) Math.max(1L, remaining + 1L);
            int count = super.read(buffer, offset, boundedLength);
            if (count > 0) {
                countBytes(count);
            }
            return count;
        }

        private void countBytes(long count) throws IOException {
            if (count > maxBytes - bytesRead) {
                throw new IOException("CSV input " + sourceName
                        + " grew beyond the explicit " + maxBytes + " byte limit while reading");
            }
            bytesRead += count;
        }

        private long getBytesRead() {
            return bytesRead;
        }
    }
}
