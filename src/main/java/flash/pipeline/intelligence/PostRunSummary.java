package flash.pipeline.intelligence;

import ij.IJ;
import flash.pipeline.io.CsvTableIO;
import flash.pipeline.io.FlashProjectLayout;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Post-run summary writer. Runs after aggregation and writes
 * {@code ihf-summary.txt} beside the active aggregation master CSVs with
 * informational findings:
 *
 *   R-01 Per-animal count variance
 *   R-07 Re-run diff
 *   R-08 Zero-count sentinel
 *   R-09 Hemisphere completeness
 *
 * It also persists a JSON snapshot to {@code FLASH/Status/summary_history.json} so future
 * runs in the same folder can compare settings and summary-table values.
 */
public final class PostRunSummary {

    private PostRunSummary() {}

    public static void writeIfPossible(String directory) {
        if (directory == null || directory.isEmpty()) return;

        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        File objectsMaster = firstExistingFile(layout.aggregationReadFiles(
                FlashProjectLayout.MASTER_OBJECTS_FILENAME,
                FlashProjectLayout.LEGACY_MASTER_OBJECTS_FILENAME));
        File intensitiesMaster = firstExistingFile(layout.aggregationReadFiles(
                FlashProjectLayout.MASTER_INTENSITIES_FILENAME,
                FlashProjectLayout.LEGACY_MASTER_INTENSITIES_FILENAME));
        File exportDir = masterDirectory(objectsMaster, intensitiesMaster);
        if (exportDir == null) return;

        try {
            SummaryHistoryStore.Snapshot previous = SummaryHistoryStore.load(directory);
            SummaryHistoryStore.Snapshot current = SummaryHistoryStore.capture(directory);

            List<String> lines = new ArrayList<String>();
            lines.add("FLASH — post-run summary");
            lines.add("Folder: " + directory);
            lines.add("Time:   " + new Date());
            lines.add("");

            if (objectsMaster.isFile()) {
                List<String[]> rows = readCsv(objectsMaster);
                if (rows.size() >= 2) {
                    String[] header = rows.get(0);
                    int animalCol = findCol(header, "Animal Name", "Animal", "AnimalName");
                    int regionCol = findCol(header, "Region");
                    List<Integer> countCols = findCountColumns(header);
                    if (animalCol >= 0 && !countCols.isEmpty()) {
                        renderPerAnimalVariance(rows, animalCol, countCols, header, lines);
                        lines.addAll(SummaryHistoryStore.diffLines(previous, current));
                        renderZeroCountSentinel(rows, animalCol, regionCol, countCols, header, lines);
                        renderHemisphereCompleteness(rows, animalCol, regionCol, lines);
                    } else {
                        lines.addAll(SummaryHistoryStore.diffLines(previous, current));
                    }
                } else {
                    lines.addAll(SummaryHistoryStore.diffLines(previous, current));
                }
            } else {
                lines.addAll(SummaryHistoryStore.diffLines(previous, current));
            }

            File out = new File(exportDir, "ihf-summary.txt");
            Files.write(out.toPath(), lines, StandardCharsets.UTF_8);
            SummaryHistoryStore.save(directory, current);
            IJ.log("Post-run summary written: " + out.getAbsolutePath());
            IJ.log("Summary history written: "
                    + SummaryHistoryStore.historyWriteFile(directory).getAbsolutePath());
        } catch (IOException e) {
            IJ.log("Post-run summary: " + e.getMessage());
        }
    }

    private static File masterDirectory(File objectsMaster, File intensitiesMaster) {
        if (objectsMaster != null && objectsMaster.isFile()) return objectsMaster.getParentFile();
        if (intensitiesMaster != null && intensitiesMaster.isFile()) return intensitiesMaster.getParentFile();
        return null;
    }

    private static File firstExistingFile(List<File> files) {
        if (files == null) return null;
        for (int i = 0; i < files.size(); i++) {
            File file = files.get(i);
            if (file != null && file.isFile()) return file;
        }
        return null;
    }

    private static void renderPerAnimalVariance(List<String[]> rows, int animalCol,
                                                List<Integer> countCols, String[] header,
                                                List<String> lines) {
        lines.add("—— R-01 Per-animal variance ——");
        Map<String, Map<Integer, List<Double>>> perAnimal = new LinkedHashMap<String, Map<Integer, List<Double>>>();
        for (int r = 1; r < rows.size(); r++) {
            String[] row = rows.get(r);
            String animal = safeGet(row, animalCol);
            if (animal.isEmpty()) continue;
            Map<Integer, List<Double>> byCh = perAnimal.get(animal);
            if (byCh == null) {
                byCh = new LinkedHashMap<Integer, List<Double>>();
                perAnimal.put(animal, byCh);
            }
            for (int c : countCols) {
                Double v = parseDouble(safeGet(row, c));
                if (v == null) continue;
                List<Double> vs = byCh.get(c);
                if (vs == null) {
                    vs = new ArrayList<Double>();
                    byCh.put(c, vs);
                }
                vs.add(v);
            }
        }

        int flagged = 0;
        for (Map.Entry<String, Map<Integer, List<Double>>> entry : perAnimal.entrySet()) {
            for (Map.Entry<Integer, List<Double>> metricEntry : entry.getValue().entrySet()) {
                List<Double> values = metricEntry.getValue();
                if (values.size() < 3) continue;
                double mean = mean(values);
                double sd = stdev(values, mean);
                double cv = mean > 1e-6 ? sd / mean : 0.0;
                if (cv > 0.6) {
                    lines.add("  WARN  " + entry.getKey() + " / " + header[metricEntry.getKey()]
                            + " : CV " + pct(cv) + "% across " + values.size()
                            + " sections (" + range(values) + ")");
                    flagged++;
                }
            }
        }
        if (flagged == 0) lines.add("  OK    no animals with high section-to-section variance");
        lines.add("");
    }

    private static void renderZeroCountSentinel(List<String[]> rows, int animalCol, int regionCol,
                                                List<Integer> countCols, String[] header,
                                                List<String> lines) {
        lines.add("—— R-08 Zero-count sentinel ——");
        int flagged = 0;
        for (int c : countCols) {
            List<Double> allVals = new ArrayList<Double>();
            for (int r = 1; r < rows.size(); r++) {
                Double v = parseDouble(safeGet(rows.get(r), c));
                if (v != null) allVals.add(v);
            }
            if (allVals.size() < 4) continue;
            double median = median(allVals);
            if (median < 5) continue;
            for (int r = 1; r < rows.size(); r++) {
                Double v = parseDouble(safeGet(rows.get(r), c));
                if (v == null || v.doubleValue() != 0.0) continue;
                String animal = safeGet(rows.get(r), animalCol);
                String region = regionCol >= 0 ? safeGet(rows.get(r), regionCol) : "";
                lines.add("  WARN  zero count in " + header[c] + " for " + animal
                        + (region.isEmpty() ? "" : " / " + region)
                        + " (cohort median " + fmt(median, 1) + ")");
                flagged++;
            }
        }
        if (flagged == 0) lines.add("  OK    no zero-count rows where cohort has signal");
        lines.add("");
    }

    private static void renderHemisphereCompleteness(List<String[]> rows, int animalCol,
                                                     int regionCol, List<String> lines) {
        lines.add("—— R-09 Hemisphere completeness ——");
        if (animalCol < 0) {
            lines.add("  INFO  no Animal column.");
            lines.add("");
            return;
        }

        Map<String, Set<String>> hemisByBase = new LinkedHashMap<String, Set<String>>();
        int hemisphereHits = 0;
        for (int r = 1; r < rows.size(); r++) {
            String animal = safeGet(rows.get(r), animalCol);
            String hemis = "";
            String base = animal;
            if (animal.endsWith("_LH") || animal.endsWith("_RH")) {
                hemis = animal.substring(animal.length() - 2);
                base = animal.substring(0, animal.length() - 3);
                hemisphereHits++;
            }
            if (hemis.isEmpty()) continue;
            Set<String> set = hemisByBase.get(base);
            if (set == null) {
                set = new TreeSet<String>();
                hemisByBase.put(base, set);
            }
            set.add(hemis);
        }

        if (hemisphereHits == 0) {
            lines.add("  INFO  filenames don't use LH/RH suffixes — skipping this check.");
            lines.add("");
            return;
        }

        int asymmetric = 0;
        for (Map.Entry<String, Set<String>> entry : hemisByBase.entrySet()) {
            if (!entry.getValue().contains("LH") || !entry.getValue().contains("RH")) {
                lines.add("  WARN  " + entry.getKey() + " has only: " + entry.getValue());
                asymmetric++;
            }
        }
        if (asymmetric == 0) lines.add("  OK    all animals have both hemispheres.");
        lines.add("");
    }

    private static List<String[]> readCsv(File file) throws IOException {
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        List<String[]> rows = new ArrayList<String[]>();
        for (String line : lines) {
            rows.add(CsvTableIO.parseCsvLine(line));
        }
        return rows;
    }

    private static int findCol(String[] header, String... names) {
        for (String name : names) {
            for (int i = 0; i < header.length; i++) {
                if (header[i] != null && header[i].trim().equalsIgnoreCase(name)) return i;
            }
        }
        return -1;
    }

    private static List<Integer> findCountColumns(String[] header) {
        List<Integer> out = new ArrayList<Integer>();
        for (int i = 0; i < header.length; i++) {
            String h = header[i] == null ? "" : header[i].toLowerCase(Locale.ROOT);
            if (h.contains("count") || h.contains("objects") || h.contains("total")) {
                out.add(i);
            }
        }
        return out;
    }

    private static String safeGet(String[] row, int idx) {
        if (row == null || idx < 0 || idx >= row.length) return "";
        return row[idx] == null ? "" : row[idx].trim();
    }

    private static Double parseDouble(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double mean(List<Double> values) {
        double sum = 0;
        for (double value : values) sum += value;
        return values.isEmpty() ? 0.0 : sum / values.size();
    }

    private static double stdev(List<Double> values, double mean) {
        if (values.size() < 2) return 0.0;
        double sum = 0;
        for (double value : values) {
            sum += (value - mean) * (value - mean);
        }
        return Math.sqrt(sum / (values.size() - 1));
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) return 0.0;
        List<Double> sorted = new ArrayList<Double>(values);
        java.util.Collections.sort(sorted);
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 0) {
            return (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
        }
        return sorted.get(mid);
    }

    private static String range(List<Double> values) {
        if (values.isEmpty()) return "n=0";
        double min = values.get(0);
        double max = values.get(0);
        for (double value : values) {
            if (value < min) min = value;
            if (value > max) max = value;
        }
        return "min=" + fmt(min, 1) + ", max=" + fmt(max, 1);
    }

    private static String pct(double value) {
        return String.format(Locale.ROOT, "%.1f", value * 100.0);
    }

    private static String fmt(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }
}
