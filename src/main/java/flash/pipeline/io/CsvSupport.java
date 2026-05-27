package flash.pipeline.io;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared CSV parsing and writing support for pipeline-generated files.
 */
public final class CsvSupport {

    public static final Charset CHARSET = StandardCharsets.UTF_8;

    private CsvSupport() {}

    public static RecordReader openRecordReader(File file) throws IOException {
        return new RecordReader(file);
    }

    public static PrintWriter newWriter(File file) throws IOException {
        return new PrintWriter(Files.newBufferedWriter(file.toPath(), CHARSET));
    }

    public static void writeAtomically(File file, WriterAction action) throws IOException {
        if (file == null) throw new IOException("CSV output file is null");
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory()) {
            Files.createDirectories(parent.toPath());
        }
        File temp = File.createTempFile("." + file.getName() + ".", ".tmp", parent);
        boolean complete = false;
        try {
            PrintWriter writer = newWriter(temp);
            try {
                action.write(writer);
                if (writer.checkError()) {
                    throw new IOException("Failed while writing temporary CSV: " + temp.getAbsolutePath());
                }
            } finally {
                writer.close();
            }
            try {
                Files.move(temp.toPath(), file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicMoveFailed) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            complete = true;
        } finally {
            if (!complete) {
                Files.deleteIfExists(temp.toPath());
            }
        }
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
                    fields.add(field.toString());
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
                fields.add(field.toString());
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

        fields.add(field.toString());
        return fields.toArray(new String[0]);
    }

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
        String flat = value.replace("\r", "\\r").replace("\n", "\\n");
        return flat.length() <= 120 ? "'" + flat + "'" : "'" + flat.substring(0, 120) + "...'";
    }

    public static String spreadsheetSafeFieldValue(String value) {
        String text = value == null ? "" : value;
        if (text.isEmpty()) return text;
        char first = text.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@'
                || first == '\t' || first == '\r' || first == '\n') {
            return "'" + text;
        }
        return text;
    }

    private static boolean startsOrEndsWithWhitespace(String text) {
        if (text.isEmpty()) return false;
        return Character.isWhitespace(text.charAt(0))
                || Character.isWhitespace(text.charAt(text.length() - 1));
    }

    private static boolean hasOpenQuotedField(CharSequence text) {
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            }
        }
        return inQuotes;
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
        private final String sourceName;
        private int nextLineNumber = 1;

        private RecordReader(File file) throws IOException {
            this.reader = Files.newBufferedReader(file.toPath(), CHARSET);
            this.sourceName = file.getName();
        }

        public Record readRecord() throws IOException {
            String line = reader.readLine();
            if (line == null) return null;

            int startLine = nextLineNumber;
            nextLineNumber++;

            StringBuilder record = new StringBuilder(line);
            while (hasOpenQuotedField(record)) {
                String continuation = reader.readLine();
                if (continuation == null) {
                    throw new IOException("Malformed CSV in " + sourceName
                            + " starting at line " + startLine
                            + ": unterminated quoted field");
                }
                record.append('\n').append(continuation);
                nextLineNumber++;
            }

            return new Record(record.toString(), startLine, nextLineNumber - 1);
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }
}
