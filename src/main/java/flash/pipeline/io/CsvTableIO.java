package flash.pipeline.io;

import ij.IJ;
import ij.measure.ResultsTable;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CSV I/O utilities for FLASH.
 *
 * <p>Provides loading and writing of per-channel object CSV files, along
 * with helper methods for CSV parsing and formatting.
 */
public final class CsvTableIO {

    private CsvTableIO() {}

    // ============================================================ Data Classes

    /**
     * Holds all data for a single channel's CSV: header columns, raw row
     * values, column index lookup, and dynamically-added distance columns.
     */
    public static class ChannelData {
        public final String name;
        /** Ordered list of column headers (grows as new columns are appended). */
        public final List<String> header;
        /** Each row is a list of field values, parallel to {@link #header}. */
        public final List<List<String>> rows;
        /** Column-name to index lookup. */
        public final Map<String, Integer> colIdx;

        public ChannelData(String name, List<String> header, List<List<String>> rows,
                    Map<String, Integer> colIdx) {
            this.name = name;
            this.header = header;
            this.rows = rows;
            this.colIdx = colIdx;
        }

        /** Append a new column to the header and every row (default empty). */
        public void addColumn(String colName) {
            if (colIdx.containsKey(colName)) return; // already exists
            int idx = header.size();
            header.add(colName);
            colIdx.put(colName, idx);
            for (List<String> row : rows) {
                row.add("");
            }
        }

        /** Set a cell value by row index and column name. */
        public void set(int rowIdx, String colName, String value) {
            Integer ci = colIdx.get(colName);
            if (ci == null) return;
            rows.get(rowIdx).set(ci, value);
        }

        /** Get a cell value by row index and column name. */
        public String get(int rowIdx, String colName) {
            Integer ci = colIdx.get(colName);
            if (ci == null || ci >= rows.get(rowIdx).size()) return "";
            return rows.get(rowIdx).get(ci);
        }

        /** Get a cell value as double. Returns 0.0 for missing/unparseable. */
        public double getDouble(int rowIdx, String colName) {
            return parseDoubleSafe(get(rowIdx, colName));
        }
    }

    // ============================================================ CSV I/O

    /**
     * Loads a channel CSV into a {@link ChannelData} structure.
     */
    public static ChannelData loadChannelCsv(File csvFile, String channelName) {
        try {
            CsvSupport.RecordReader csv = CsvSupport.openRecordReader(csvFile);
            try {
                CsvSupport.Record headerRecord = csv.readRecord();
                if (headerRecord == null) {
                    IJ.log("  Empty CSV, skipping: " + csvFile.getName());
                    return null;
                }

                String[] headerArr = CsvSupport.parseRecord(headerRecord.text);
                List<String> header = new ArrayList<String>();
                Map<String, Integer> colIdx = new HashMap<String, Integer>();
                for (int i = 0; i < headerArr.length; i++) {
                    String h = headerArr[i].trim();
                    header.add(h);
                    colIdx.put(h, i);
                }

                List<List<String>> rows = new ArrayList<List<String>>();
                CsvSupport.Record record;
                while ((record = csv.readRecord()) != null) {
                    if (CsvSupport.isBlankRecord(record.text)) continue;
                    String[] fields = CsvSupport.parseRecord(record.text);
                    List<String> row = new ArrayList<String>();
                    for (int i = 0; i < header.size(); i++) {
                        row.add(i < fields.length ? fields[i] : "");
                    }
                    rows.add(row);
                }

                return new ChannelData(channelName, header, rows, colIdx);
            } finally {
                csv.close();
            }
        } catch (IOException e) {
            IJ.log("  Error reading " + csvFile.getName() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Writes a {@link ChannelData} structure back to CSV, preserving all
     * original columns plus any newly appended distance columns.
     */
    public static void writeChannelCsv(File outFile, ChannelData cd) {
        try {
            CsvSupport.writeAtomically(outFile, new CsvSupport.WriterAction() {
                @Override
                public void write(PrintWriter pw) {
                    pw.println(joinCsv(cd.header));
                    for (List<String> row : cd.rows) {
                        pw.println(joinCsv(row));
                    }
                }
            });
        } catch (IOException e) {
            IJ.log("  Error writing " + outFile.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    // ============================================================ Helpers

    /**
     * Parses a single CSV line, handling quoted fields.
     */
    public static String[] parseCsvLine(String line) {
        try {
            return CsvSupport.parseRecord(line);
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed CSV record for input "
                    + previewCsvInput(line) + ": " + e.getMessage(), e);
        }
    }

    private static String previewCsvInput(String value) {
        if (value == null) {
            return "<null>";
        }
        String flat = value.replace("\r", "\\r").replace("\n", "\\n");
        return flat.length() <= 120 ? "'" + flat + "'" : "'" + flat.substring(0, 120) + "...'";
    }

    /**
     * Writes a {@link ResultsTable} to CSV using the provided column order.
     */
    public static void writeResultsTableCsv(File outFile, ResultsTable table, List<String> orderedColumns) {
        try {
            CsvSupport.writeAtomically(outFile, new CsvSupport.WriterAction() {
                @Override
                public void write(PrintWriter pw) {
                    pw.println(joinCsv(orderedColumns));
                    if (table == null) return;
                    for (int row = 0; row < table.size(); row++) {
                        List<String> values = new ArrayList<String>(orderedColumns.size());
                        for (String col : orderedColumns) {
                            values.add(resultsTableValue(table, col, row));
                        }
                        pw.println(joinCsv(values));
                    }
                }
            });
        } catch (IOException e) {
            IJ.log("  Error writing " + outFile.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    /**
     * Writes a ResultsTable while preserving an existing same-row CSV and
     * appending or refreshing the supplied columns by row index.
     *
     * @return true when an existing CSV was merged, false when the caller
     * should fall back to a normal overwrite.
     */
    public static boolean mergeResultsTableCsv(File outFile, ResultsTable table, List<String> orderedColumns) {
        if (outFile == null || table == null || orderedColumns == null || !outFile.exists()) {
            return false;
        }
        ChannelData existing = loadChannelCsv(outFile, outFile.getName());
        if (existing == null || existing.rows.size() != table.size()) {
            return false;
        }
        for (String column : orderedColumns) {
            if (column != null && !column.trim().isEmpty()) {
                existing.addColumn(column);
            }
        }
        for (int row = 0; row < table.size(); row++) {
            for (String column : orderedColumns) {
                if (column == null || column.trim().isEmpty()) continue;
                existing.set(row, column, resultsTableValue(table, column, row));
            }
        }
        writeChannelCsv(outFile, existing);
        return true;
    }

    /**
     * Writes a ResultsTable by appending new rows to an existing channel CSV,
     * preserving manual columns already present in the file.
     *
     * @return true when an existing CSV was extended, false when the caller
     * should fall back to a normal overwrite.
     */
    public static boolean appendResultsTableCsv(File outFile, File existingFile, String channelName,
                                                ResultsTable table, List<String> orderedColumns) {
        if (outFile == null || table == null || orderedColumns == null
                || existingFile == null || !existingFile.isFile()) {
            return false;
        }
        ChannelData existing = loadChannelCsv(existingFile, channelName);
        if (existing == null) {
            return false;
        }
        for (String column : orderedColumns) {
            if (column != null && !column.trim().isEmpty()) {
                existing.addColumn(column);
            }
        }
        for (int row = 0; row < table.size(); row++) {
            List<String> values = new ArrayList<String>(existing.header.size());
            for (String column : existing.header) {
                if (orderedColumns.contains(column)) {
                    values.add(resultsTableValue(table, column, row));
                } else {
                    values.add("");
                }
            }
            existing.rows.add(values);
        }
        writeChannelCsv(outFile, existing);
        return true;
    }

    /**
     * Parses a string as a double, returning 0.0 for null, empty, or
     * unparseable values.
     */
    public static double parseDoubleSafe(String s) {
        if (s == null || s.trim().isEmpty()) return 0.0;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * Formats a distance value for CSV output.  Uses 6 decimal places for
     * normal values.  Outputs "Inf" for infinity/NaN.
     */
    public static String formatDist(double val) {
        if (Double.isNaN(val) || Double.isInfinite(val) || val == Double.MAX_VALUE) {
            return "Inf";
        }
        return String.format(Locale.US, "%.6f", val);
    }

    /**
     * Joins a list of strings into a CSV line, quoting fields that contain
     * commas.
     */
    public static String joinCsv(List<String> parts) {
        return CsvSupport.joinRow(parts);
    }

    private static String resultsTableValue(ResultsTable table, String col, int row) {
        try {
            String stringValue = table.getStringValue(col, row);
            if (stringValue != null) {
                return stringValue;
            }
        } catch (Exception ignored) {
        }

        try {
            double value = table.getValue(col, row);
            if (!Double.isNaN(value)) {
                return Double.toString(value);
            }
            if (table.getColumnIndex(col) >= 0) {
                return "NaN";
            }
        } catch (Exception ignored) {
        }
        return "";
    }
}
