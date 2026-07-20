package flash.pipeline.io;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.Locale;
import java.util.LinkedHashSet;

/**
 * Cheap, EDT-safe collection of the animal names that condition review cares
 * about. Reads only the small per-image object/intensity result CSVs (no
 * Bio-Formats / container parsing), preferring real result rows and falling
 * back to the saved condition manifest keys when no result tables exist yet.
 *
 * <p>Used by lightweight condition status banners (analysis picker, diagnostics)
 * that must not block the UI thread with heavy source reads.
 */
public final class ResultAnimalScanner {

    private ResultAnimalScanner() {}

    /** Header used by per-image result CSVs; master tables use {@code AnimalName}. */
    private static final String[] ANIMAL_HEADERS = {"Animal Name", "AnimalName"};

    public static LinkedHashSet<String> collect(String directory) {
        LinkedHashSet<String> animals = new LinkedHashSet<String>();
        if (directory == null || directory.trim().isEmpty()) {
            return animals;
        }

        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        addAnimalsFromDir(animals, layout.tablesObjectsWriteDir());
        addAnimalsFromDir(animals, layout.tablesIntensityWriteDir());
        addAnimalsFromDir(animals, layout.tablesMorphometryWriteDir());
        addAnimalsFromDir(animals, layout.tablesLineDistanceWriteDir());

        if (animals.isEmpty()) {
            // No result tables yet: the saved/auto-generated manifest is the best
            // cheap proxy for the project's animal roster.
            animals.addAll(ConditionManifestIO.readAssignmentsIfExists(directory).keySet());
        }
        return animals;
    }

    private static void addAnimalsFromDir(LinkedHashSet<String> animals, File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles(resultCsvFilter());
        if (files == null) return;
        for (File csv : files) {
            addAnimalsFromCsv(animals, csv);
        }
    }

    private static void addAnimalsFromCsv(LinkedHashSet<String> animals, File csvFile) {
        if (csvFile == null || !csvFile.isFile()) return;
        try {
            CsvSupport.RecordReader csv = CsvSupport.openRecordReader(csvFile);
            try {
                CsvSupport.Record headerRecord = csv.readRecord();
                if (headerRecord == null) return;
                String[] header = CsvSupport.parseRecord(headerRecord.text);
                int animalCol = animalColumn(header);
                if (animalCol < 0) return;

                CsvSupport.Record record;
                while ((record = csv.readRecord()) != null) {
                    if (CsvSupport.isBlankRecord(record.text)) continue;
                    String[] row = CsvSupport.parseRecord(record.text);
                    if (animalCol >= row.length) continue;
                    String animal = row[animalCol] == null ? "" : row[animalCol].trim();
                    if (!animal.isEmpty()) {
                        animals.add(animal);
                    }
                }
            } finally {
                csv.close();
            }
        } catch (IOException ignored) {
            // A single unreadable result CSV should not break status reporting.
        }
    }

    private static int animalColumn(String[] header) {
        if (header == null) return -1;
        for (int i = 0; i < header.length; i++) {
            String col = header[i] == null ? "" : header[i].trim();
            for (String candidate : ANIMAL_HEADERS) {
                if (candidate.equals(col)) return i;
            }
        }
        return -1;
    }

    private static FilenameFilter resultCsvFilter() {
        return new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".csv")) return false;
                if (lower.contains("temp_")) return false;
                if (lower.contains("details")) return false;
                if (lower.contains("calibration")) return false;
                return true;
            }
        };
    }
}
