package flash.pipeline.zslice;

import flash.pipeline.io.CsvSupport;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.LinkedHashMap;

/**
 * Reads and writes the optional per-series Z-slice selection file.
 */
public final class ZSliceConfigIO {
    public static final String FILE_NAME = "ZSlice_Selections.csv";

    private ZSliceConfigIO() {}

    public static String modeLine(ZSliceMode mode) {
        return (mode == null ? ZSliceMode.FULL : mode).configToken;
    }

    public static ZSliceMode parseModeLine(String line) {
        return ZSliceMode.fromConfigToken(line);
    }

    public static LinkedHashMap<Integer, ZSliceSelection> readSelections(File binFolder) throws IOException {
        LinkedHashMap<Integer, ZSliceSelection> selections = new LinkedHashMap<Integer, ZSliceSelection>();
        if (binFolder == null) return selections;

        File csvFile = new File(binFolder, FILE_NAME);
        if (!csvFile.isFile()) return selections;

        CsvSupport.RecordReader csv = CsvSupport.openRecordReader(csvFile);
        try {
            CsvSupport.Record header = csv.readRecord();
            if (header == null) return selections;

            CsvSupport.Record record;
            while ((record = csv.readRecord()) != null) {
                if (CsvSupport.isBlankRecord(record.text)) continue;
                String[] fields = CsvSupport.parseRecord(record.text);
                if (fields.length < 5) {
                    throw new IOException(FILE_NAME + " row " + record.startLineNumber + " has too few columns");
                }

                Integer seriesIndex = tryParseInt(fields[0]);
                String seriesName = fields[1];
                Integer totalSlices = tryParseInt(fields[2]);
                Integer startSlice = tryParseInt(fields[3]);
                Integer endSlice = tryParseInt(fields[4]);
                if (seriesIndex == null || totalSlices == null || startSlice == null || endSlice == null) {
                    throw new IOException(FILE_NAME + " row " + record.startLineNumber
                            + " contains invalid numeric values");
                }

                ZSliceSelection selection = new ZSliceSelection(
                        seriesIndex,
                        seriesName,
                        totalSlices,
                        new ZSliceRange(startSlice, endSlice));
                selections.put(seriesIndex, selection);
            }
        } finally {
            csv.close();
        }
        return selections;
    }

    public static void writeSelections(File binFolder, ZSliceConfig config) throws IOException {
        if (binFolder == null) return;

        File csvFile = new File(binFolder, FILE_NAME);
        if (config == null || !config.usesSubset()) {
            Files.deleteIfExists(csvFile.toPath());
            return;
        }

        PrintWriter pw = CsvSupport.newWriter(csvFile);
        try {
            pw.println(CsvSupport.joinRow(java.util.Arrays.asList(
                    "SeriesIndex",
                    "SeriesName",
                    "TotalSlices",
                    "StartSlice",
                    "EndSlice",
                    "SliceCount",
                    "SelectionMode")));
            for (ZSliceSelection selection : config.orderedSelections()) {
                if (selection == null) continue;
                pw.println(CsvSupport.joinRow(java.util.Arrays.asList(
                        String.valueOf(selection.seriesIndex),
                        selection.seriesName,
                        String.valueOf(selection.totalSlices),
                        String.valueOf(selection.range.startSlice),
                        String.valueOf(selection.range.endSlice),
                        String.valueOf(selection.sliceCount()),
                        config.mode.name()
                )));
            }
        } finally {
            pw.close();
        }
    }

    public static String signature(ZSliceConfig config) {
        if (config == null || config.mode == ZSliceMode.FULL || config.selections.isEmpty()) {
            return ZSliceMode.FULL.configToken;
        }
        StringBuilder sb = new StringBuilder(config.mode.configToken);
        for (ZSliceSelection selection : config.orderedSelections()) {
            if (selection == null) continue;
            sb.append('|')
                    .append(selection.seriesIndex)
                    .append(':')
                    .append(selection.range.toToken());
        }
        return sb.toString();
    }

    private static Integer tryParseInt(String token) {
        if (token == null || token.trim().isEmpty()) return null;
        try {
            return Integer.parseInt(token.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
