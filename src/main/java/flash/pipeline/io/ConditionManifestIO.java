package flash.pipeline.io;

import ij.IJ;
import flash.pipeline.naming.ConditionNameParser;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persists auto-detected animal -> condition assignments so downstream
 * analyses can reuse a stable condition map across sessions.
 */
public final class ConditionManifestIO {

    public static final String FILE_NAME = FlashProjectLayout.CONDITIONS_FILENAME;
    public static final String LEGACY_FILE_NAME = FlashProjectLayout.LEGACY_CONDITIONS_FILENAME;

    private ConditionManifestIO() {}

    public static File getFile(String directory) {
        return FlashProjectLayout.forDirectory(directory).conditionManifestWriteFile();
    }

    public static File getReadFile(String directory) {
        File existing = getExistingFile(directory);
        return existing == null ? getFile(directory) : existing;
    }

    public static File getExistingFile(String directory) {
        List<File> candidates = FlashProjectLayout.forDirectory(directory).conditionManifestReadFiles();
        for (File candidate : candidates) {
            if (candidate.isFile()) return candidate;
        }
        return null;
    }

    /**
     * Creates the condition manifest once per project directory.
     * <p>
     * If the manifest already exists it is left untouched.
     */
    public static void ensureExists(String directory) {
        if (directory == null || directory.trim().isEmpty()) return;

        File manifest = getFile(directory);
        if (getExistingFile(directory) != null) return;

        File exportDir = manifest.getParentFile();
        if (exportDir != null && !exportDir.isDirectory()) {
            try {
                IoUtils.mustMkdirs(exportDir);
            } catch (IOException e) {
                IJ.log("[ConditionManifestIO] could not create " + exportDir + ": " + e.getMessage());
                return;
            }
        }

        File sourceDir = new File(directory);
        List<File> containers = ImageSourceDispatcher.listContainers(sourceDir);
        if (containers.size() > 1) {
            IJ.log("Condition manifest not created: multiple container files found in "
                    + sourceDir.getName() + ". Choose a single input source before auto-generating "
                    + FILE_NAME + ".");
            return;
        }

        List<SeriesMeta> metas;
        try {
            metas = ImageSourceDispatcher.readAllMetadata(directory);
        } catch (Exception e) {
            IJ.log("Condition manifest not created: " + e.getMessage());
            return;
        }

        try {
            List<String> seriesNames = new ArrayList<String>();
            for (SeriesMeta meta : metas) {
                if (meta == null || meta.name == null || meta.name.trim().isEmpty()) continue;
                seriesNames.add(meta.name);
            }

            LinkedHashMap<String, String> assignments = buildAssignmentsFromSeriesNames(seriesNames);
            if (assignments.isEmpty()) {
                IJ.log("Condition manifest not created: no parseable series names found in "
                        + new File(directory).getName());
                return;
            }

            write(manifest, assignments);
            IJ.log("Condition manifest saved: " + manifest.getAbsolutePath());
            IJ.log("  Parsed " + seriesNames.size() + " series into " + assignments.size()
                    + " animal assignments.");
            IJ.log("  Conditions: " + new LinkedHashSet<String>(assignments.values()));
        } catch (Exception e) {
            IJ.log("Warning: could not create condition manifest: " + e.getMessage());
        }
    }

    /**
     * Returns condition assignments for the given animals.
     * <p>
     * Saved assignments in {@code Conditions.csv} are preferred.
     * Legacy auto-generated manifests that collapsed multiple suffix conditions
     * into a single prefix (for example {@code hAPP}) are upgraded in place to
     * preserve the newer suffix-aware detection (for example
     * {@code hAPPWeek2/hAPPWeek4/hAPPWeek8}).
     * Missing animals fall back to direct auto-detection from the animal name.
     */
    public static LinkedHashMap<String, String> resolveAssignments(String directory, Set<String> animals) {
        ensureExists(directory);

        LinkedHashMap<String, String> persisted = readIfExists(directory);
        persisted = upgradeLegacyCollapsedAssignments(directory, persisted);
        LinkedHashMap<String, String> resolved = new LinkedHashMap<String, String>();

        int fromManifest = 0;
        for (String animal : animals) {
            String condition = persisted.get(animal);
            if (condition != null && !condition.trim().isEmpty()) {
                resolved.put(animal, condition.trim());
                fromManifest++;
            } else {
                resolved.put(animal, ConditionNameParser.detectCondition(animal));
            }
        }

        if (!persisted.isEmpty()) {
            IJ.log("  Loaded condition manifest: " + getFile(directory).getName());
            if (fromManifest < animals.size()) {
                IJ.log("  Falling back to auto-detection for " + (animals.size() - fromManifest)
                        + " animals missing from the manifest.");
            }
        }

        return resolved;
    }

    private static LinkedHashMap<String, String> upgradeLegacyCollapsedAssignments(
            String directory,
            LinkedHashMap<String, String> persisted) {
        if (persisted == null || persisted.isEmpty()) return persisted;

        Map<String, List<String>> animalsByPersistedCondition =
                new LinkedHashMap<String, List<String>>();
        for (Map.Entry<String, String> entry : persisted.entrySet()) {
            String animal = trimToEmpty(entry.getKey());
            String condition = trimToEmpty(entry.getValue());
            if (animal.isEmpty() || condition.isEmpty()) continue;

            List<String> group = animalsByPersistedCondition.get(condition);
            if (group == null) {
                group = new ArrayList<String>();
                animalsByPersistedCondition.put(condition, group);
            }
            group.add(animal);
        }

        LinkedHashMap<String, String> upgraded = new LinkedHashMap<String, String>(persisted);
        List<String> upgradedAnimals = new ArrayList<String>();

        for (Map.Entry<String, List<String>> groupEntry : animalsByPersistedCondition.entrySet()) {
            String persistedCondition = groupEntry.getKey();
            List<String> animals = groupEntry.getValue();

            LinkedHashSet<String> detectedConditions = new LinkedHashSet<String>();
            boolean legacyCollapsedGroup = animals.size() > 1;
            for (String animal : animals) {
                String detectedCondition = trimToEmpty(ConditionNameParser.detectCondition(animal));
                if (!detectedCondition.isEmpty()) {
                    detectedConditions.add(detectedCondition);
                }

                if (!isLegacyCollapsedAssignment(animal, persistedCondition, detectedCondition)) {
                    legacyCollapsedGroup = false;
                    break;
                }
            }

            if (!legacyCollapsedGroup || detectedConditions.size() < 2) {
                continue;
            }

            for (String animal : animals) {
                String detectedCondition = trimToEmpty(ConditionNameParser.detectCondition(animal));
                if (!detectedCondition.isEmpty() && !persistedCondition.equals(detectedCondition)) {
                    upgraded.put(animal, detectedCondition);
                    upgradedAnimals.add(animal);
                }
            }
        }

        if (upgradedAnimals.isEmpty()) {
            return persisted;
        }

        File manifest = getFile(directory);
        try {
            File parent = manifest.getParentFile();
            if (parent != null) {
                IoUtils.mustMkdirs(parent);
            }
            write(manifest, upgraded);
            IJ.log("  Upgraded legacy condition manifest entries to preserve suffix conditions: "
                    + upgradedAnimals);
        } catch (IOException e) {
            IJ.log("Warning: could not rewrite upgraded condition manifest: " + e.getMessage());
        }

        return upgraded;
    }

    private static boolean isLegacyCollapsedAssignment(String animal,
                                                       String persistedCondition,
                                                       String detectedCondition) {
        if (animal == null || persistedCondition == null || detectedCondition == null) return false;

        String animalName = animal.trim();
        String saved = persistedCondition.trim();
        String detected = detectedCondition.trim();
        if (animalName.isEmpty() || saved.isEmpty() || detected.isEmpty()) return false;
        if (saved.equals(detected)) return false;
        if (!saved.equals(legacyPrefix(animalName))) return false;
        if (!detected.startsWith(saved) || detected.length() <= saved.length()) return false;

        for (int i = saved.length(); i < detected.length(); i++) {
            if (Character.isLetter(detected.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static String legacyPrefix(String animalName) {
        StringBuilder prefix = new StringBuilder();
        for (char c : animalName.toCharArray()) {
            if (Character.isLetter(c) || c == '-' || c == '_') {
                prefix.append(c);
            } else {
                break;
            }
        }
        return prefix.length() > 0 ? prefix.toString() : animalName;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    static LinkedHashMap<String, String> buildAssignmentsFromSeriesNames(List<String> seriesNames) {
        LinkedHashMap<String, String> assignments = new LinkedHashMap<String, String>();
        if (seriesNames == null) return assignments;

        for (String seriesName : seriesNames) {
            String animal = extractAnimalName(seriesName);
            if (animal.isEmpty() || assignments.containsKey(animal)) continue;
            assignments.put(animal, ConditionNameParser.detectCondition(animal));
        }

        return assignments;
    }

    static LinkedHashMap<String, String> readIfExists(String directory) {
        File manifest = getExistingFile(directory);
        if (manifest == null) return new LinkedHashMap<String, String>();

        try {
            return read(manifest);
        } catch (IOException e) {
            IJ.log("Warning: could not read condition manifest: " + e.getMessage());
            return new LinkedHashMap<String, String>();
        }
    }

    public static LinkedHashMap<String, String> readAssignmentsIfExists(String directory) {
        return readIfExists(directory);
    }

    static LinkedHashMap<String, String> read(File manifest) throws IOException {
        LinkedHashMap<String, String> assignments = new LinkedHashMap<String, String>();
        CsvSupport.RecordReader csv = CsvSupport.openRecordReader(manifest);
        try {
            CsvSupport.Record header = csv.readRecord();
            if (header == null) return assignments;

            CsvSupport.Record record;
            while ((record = csv.readRecord()) != null) {
                if (CsvSupport.isBlankRecord(record.text)) continue;
                String[] row = CsvSupport.parseRecord(record.text);
                String animal = row.length > 0 ? row[0].trim() : "";
                String condition = row.length > 1 ? row[1].trim() : "";
                if (animal.isEmpty() || condition.isEmpty()) continue;
                assignments.put(animal, condition);
            }
        } finally {
            csv.close();
        }
        return assignments;
    }

    static void write(File manifest, Map<String, String> assignments) throws IOException {
        CsvSupport.writeAtomically(manifest, new CsvSupport.WriterAction() {
            @Override
            public void write(PrintWriter pw) {
                pw.println(CsvSupport.joinRow(java.util.Arrays.asList("AnimalName", "Condition")));
                for (Map.Entry<String, String> entry : assignments.entrySet()) {
                    pw.println(CsvSupport.joinRow(java.util.Arrays.asList(entry.getKey(), entry.getValue())));
                }
            }
        });
    }

    /**
     * Persists condition assignments to {@code FLASH/Results Export/Conditions.csv}.
     * Trims keys and values and drops blank rows so the file stays clean.
     */
    public static void saveAssignments(String directory, Map<String, String> assignments) throws IOException {
        File manifest = getFile(directory);
        File exportDir = manifest.getParentFile();
        if (exportDir != null && !exportDir.isDirectory()) {
            IoUtils.mustMkdirs(exportDir);
        }

        LinkedHashMap<String, String> cleaned = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : assignments.entrySet()) {
            String animal = trimToEmpty(entry.getKey());
            String condition = trimToEmpty(entry.getValue());
            if (!animal.isEmpty() && !condition.isEmpty()) {
                cleaned.put(animal, condition);
            }
        }

        write(manifest, cleaned);
        IJ.log("Condition assignments saved: " + manifest.getAbsolutePath()
                + " (" + cleaned.size() + " entries)");
    }

    public static String extractAnimalName(String seriesName) {
        if (seriesName == null) return "";

        String trimmed = seriesName.trim();
        if (trimmed.isEmpty()) return "";

        String rhs = trimmed;
        int titledSep = trimmed.lastIndexOf(" - ");
        if (titledSep >= 0 && titledSep + 3 < trimmed.length()) {
            rhs = trimmed.substring(titledSep + 3).trim();
        }

        String[] tokens = rhs.split("_");
        if (tokens.length >= 2) {
            String animal = tokens[0].trim();
            String hemi = tokens[1].trim();
            if (!animal.isEmpty()
                    && ("LH".equalsIgnoreCase(hemi) || "RH".equalsIgnoreCase(hemi))) {
                return animal;
            }
        }

        return rhs;
    }

}
