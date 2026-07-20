package flash.pipeline.io;

import flash.pipeline.naming.OrientationManifestRow;
import flash.pipeline.project.ProjectFileIO;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Reads and writes the optional project image-orientation manifest.
 */
public final class OrientationManifestIO {

    public static final String FILE_NAME = FlashProjectLayout.ORIENTATION_MANIFEST_FILENAME;

    private static final List<String> HEADER = Arrays.asList(
            "ImageKey",
            "SourceFile",
            "SeriesIndex",
            "OriginalName",
            "DisplayName",
            "AnimalName",
            "Hemisphere",
            "Region",
            "RotateDegrees",
            "FlipHorizontal",
            "FlipVertical",
            "ViewPolicy",
            "DecisionSource",
            "Confirmed",
            "Notes");

    private OrientationManifestIO() {}

    public static File getFile(String directory) {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(
                orientationProjectRoot(directory).getAbsolutePath());
        return layout.projectSummaryWriteFile(FILE_NAME);
    }

    static File orientationProjectRoot(String directory) {
        if (directory == null || directory.trim().isEmpty()) {
            throw new IllegalArgumentException("Project directory must not be blank.");
        }

        File selected = new File(directory);
        if (ProjectFileIO.FILE_NAME.equalsIgnoreCase(selected.getName())) {
            File parent = selected.getParentFile();
            if (parent == null) {
                return selected;
            }
            File root = FlashProjectLayout.projectRootForConfigurationDir(parent);
            return root == null ? parent : root;
        }

        if (selected.isFile()) {
            selected = selected.getParentFile();
        }

        File root = FlashProjectLayout.projectRootForConfigurationDir(selected);
        if (root != null) {
            return root;
        }

        if (selected != null
                && FlashProjectLayout.FLASH_DIR.equals(selected.getName())
                && selected.getParentFile() != null) {
            return selected.getParentFile();
        }

        return selected;
    }

    public static File getExistingFile(String directory) {
        File candidate = getFile(directory);
        return candidate.isFile() ? candidate : null;
    }

    public static List<OrientationManifestRow> readIfExists(String directory) {
        File manifest = getExistingFile(directory);
        if (manifest == null || !manifest.isFile()) return new ArrayList<OrientationManifestRow>();

        try {
            return read(manifest);
        } catch (IOException e) {
            return new ArrayList<OrientationManifestRow>();
        }
    }

    public static LinkedHashMap<String, OrientationManifestRow> readByImageKeyIfExists(String directory) {
        return indexByImageKey(readIfExists(directory));
    }

    public static List<OrientationManifestRow> read(File manifest) throws IOException {
        List<OrientationManifestRow> rows = new ArrayList<OrientationManifestRow>();
        CsvSupport.RecordReader csv = CsvSupport.openRecordReader(manifest);
        try {
            CsvSupport.Record header = csv.readRecord();
            if (header == null) return rows;

            CsvSupport.Record record;
            while ((record = csv.readRecord()) != null) {
                if (CsvSupport.isBlankRecord(record.text)) continue;
                String[] fields = CsvSupport.parseRecord(record.text);
                OrientationManifestRow row = parseRow(fields);
                if (row != null) {
                    rows.add(row);
                }
            }
        } finally {
            csv.close();
        }
        return rows;
    }

    public static void saveRows(String directory, List<OrientationManifestRow> rows) throws IOException {
        File manifest = getFile(directory);
        write(manifest, rows);
    }

    public static void write(File manifest, List<OrientationManifestRow> rows) throws IOException {
        File exportDir = manifest.getParentFile();
        if (exportDir != null && !exportDir.isDirectory()) {
            IoUtils.mustMkdirs(exportDir);
        }

        CsvSupport.writeAtomically(manifest, new CsvSupport.WriterAction() {
            @Override
            public void write(PrintWriter pw) {
                pw.println(CsvSupport.joinRow(HEADER));
                if (rows == null) return;
                for (OrientationManifestRow row : rows) {
                    if (row == null || row.imageKey.isEmpty()) continue;
                    pw.println(CsvSupport.joinRow(toFields(row)));
                }
            }
        });
    }

    public static LinkedHashMap<String, OrientationManifestRow> indexByImageKey(
            List<OrientationManifestRow> rows) {
        LinkedHashMap<String, OrientationManifestRow> byKey =
                new LinkedHashMap<String, OrientationManifestRow>();
        if (rows == null) return byKey;

        for (OrientationManifestRow row : rows) {
            if (row == null || row.imageKey.isEmpty()) continue;
            byKey.put(row.imageKey, row);
        }
        return byKey;
    }

    private static OrientationManifestRow parseRow(String[] fields) {
        String imageKey = field(fields, 0);
        if (imageKey.isEmpty()) return null;

        return new OrientationManifestRow(
                imageKey,
                field(fields, 1),
                parseSeriesIndex(field(fields, 2)),
                field(fields, 3),
                field(fields, 4),
                field(fields, 5),
                OrientationManifestRow.Hemisphere.fromCsv(field(fields, 6)),
                field(fields, 7),
                OrientationManifestRow.RotationDegrees.fromCsv(field(fields, 8)),
                OrientationManifestRow.parseYesNo(field(fields, 9)),
                OrientationManifestRow.parseYesNo(field(fields, 10)),
                OrientationManifestRow.ViewPolicy.fromCsv(field(fields, 11)),
                OrientationManifestRow.DecisionSource.fromCsv(field(fields, 12)),
                OrientationManifestRow.ConfirmationState.fromCsv(field(fields, 13)),
                field(fields, 14));
    }

    private static List<String> toFields(OrientationManifestRow row) {
        return Arrays.asList(
                row.imageKey,
                row.sourceFile,
                String.valueOf(row.seriesIndex),
                row.originalName,
                row.displayName,
                row.animalName,
                row.hemisphere.toCsv(),
                row.region,
                row.rotateDegrees.toCsv(),
                OrientationManifestRow.yesNo(row.flipHorizontal),
                OrientationManifestRow.yesNo(row.flipVertical),
                row.viewPolicy.toCsv(),
                row.decisionSource.toCsv(),
                row.confirmed.toCsv(),
                row.notes);
    }

    private static int parseSeriesIndex(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed < 1 ? 1 : parsed;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String field(String[] fields, int index) {
        if (fields == null || index < 0 || index >= fields.length || fields[index] == null) {
            return "";
        }
        return fields[index].trim();
    }
}
