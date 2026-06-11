package flash.pipeline.io;

import flash.pipeline.naming.ConditionAssignments;
import flash.pipeline.naming.ConditionAxis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * CSV/TSV colony-roster round-trip for condition assignments (Stage 15 / D7).
 *
 * <p>Export writes an {@code AnimalName, Condition_&lt;axis&gt;…} template
 * pre-filled with the current project values. Import is forgiving about headers
 * (OMERO-style, case- and separator-insensitive synonyms) and merges by animal;
 * extra roster columns become their own condition axes. Import never silently
 * applies: {@link #validate} reports unmatched, duplicate and conflicting rows so
 * the caller can resolve them.
 */
public final class RosterIO {

    /** Header tokens (normalised) that name the animal/sample column. */
    private static final Set<String> ANIMAL_HEADERS = new HashSet<String>(Arrays.asList(
            "animal", "animalid", "animalname", "animalno", "animalnumber",
            "sample", "samplename", "sampleid", "specimen",
            "mouse", "mouseid", "mousename", "mouseno",
            "subject", "subjectid", "subjectname",
            "id", "name"));

    private RosterIO() {}

    /** Parsed roster: axis schema (column order) + animal&rarr;axisId&rarr;value + duplicate animals. */
    public static final class Roster {
        public final List<ConditionAxis> axes = new ArrayList<ConditionAxis>();
        public final LinkedHashMap<String, Map<String, String>> byAnimal =
                new LinkedHashMap<String, Map<String, String>>();
        public final List<String> duplicateAnimals = new ArrayList<String>();
    }

    /** One roster value that disagrees with a confirmed project value. */
    public static final class Conflict {
        public final String animal;
        public final String axisId;
        public final String projectValue;
        public final String rosterValue;

        public Conflict(String animal, String axisId, String projectValue, String rosterValue) {
            this.animal = animal;
            this.axisId = axisId;
            this.projectValue = projectValue;
            this.rosterValue = rosterValue;
        }
    }

    /** Outcome of validating a roster against the current project (report, don't apply). */
    public static final class Validation {
        public final List<String> unmatched = new ArrayList<String>();
        public final List<String> duplicates = new ArrayList<String>();
        public final List<Conflict> conflicts = new ArrayList<Conflict>();

        public boolean isClean() {
            return unmatched.isEmpty() && duplicates.isEmpty() && conflicts.isEmpty();
        }
    }

    // ── Export ──────────────────────────────────────────────────────────────

    /** Roster template text, current values pre-filled. CSV unless {@code tsv}. */
    public static String export(ConditionAssignments assignments, boolean tsv) {
        StringBuilder out = new StringBuilder();
        List<ConditionAxis> axes = assignments == null ? new ArrayList<ConditionAxis>() : assignments.axes();

        List<String> header = new ArrayList<String>();
        header.add("AnimalName");
        for (ConditionAxis axis : axes) header.add(axis.csvColumnName());
        out.append(joinRow(header, tsv)).append('\n');

        if (assignments != null) {
            for (String animal : assignments.animals()) {
                List<String> row = new ArrayList<String>();
                row.add(animal);
                for (ConditionAxis axis : axes) row.add(assignments.get(animal, axis.id));
                out.append(joinRow(row, tsv)).append('\n');
            }
        }
        return out.toString();
    }

    private static String joinRow(List<String> cells, boolean tsv) {
        if (!tsv) {
            return CsvSupport.joinRow(cells);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append('\t');
            String c = cells.get(i) == null ? "" : cells.get(i).replace('\t', ' ').replace("\n", " ");
            sb.append(c);
        }
        return sb.toString();
    }

    // ── Import ──────────────────────────────────────────────────────────────

    /** Parse roster text (CSV or TSV auto-detected); maps headers via OMERO-style synonyms. */
    public static Roster parse(String text) {
        Roster roster = new Roster();
        if (text == null) return roster;
        String[] lines = text.split("\r\n|\r|\n", -1);
        int h = 0;
        while (h < lines.length && lines[h].trim().isEmpty()) h++;
        if (h >= lines.length) return roster;

        boolean tsv = lines[h].indexOf('\t') >= 0;
        String[] header = splitLine(lines[h], tsv);

        int animalCol = -1;
        ConditionAxis[] axisForCol = new ConditionAxis[header.length];
        Set<String> seenAxisIds = new LinkedHashSet<String>();
        for (int c = 0; c < header.length; c++) {
            String raw = header[c] == null ? "" : header[c].trim();
            if (animalCol < 0 && ANIMAL_HEADERS.contains(normalise(raw))) {
                animalCol = c;
                continue;
            }
            if (raw.isEmpty()) continue;
            ConditionAxis axis = ConditionAxis.of(axisLabelFromHeader(raw));
            if (axis.id.isEmpty()) continue;
            if (!seenAxisIds.add(axis.id)) continue;   // duplicate axis column: first column wins
            axisForCol[c] = axis;
            roster.axes.add(axis);
        }
        if (animalCol < 0) {
            // No recognised animal header — fall back to the first column as the
            // animal id, and drop the bogus condition axis it was misclassified as.
            animalCol = 0;
            if (axisForCol.length > 0 && axisForCol[0] != null) {
                roster.axes.remove(axisForCol[0]);
                axisForCol[0] = null;
            }
        }

        Set<String> seenAnimals = new LinkedHashSet<String>();
        for (int i = h + 1; i < lines.length; i++) {
            if (lines[i].trim().isEmpty()) continue;
            String[] cells = splitLine(lines[i], tsv);
            String animal = animalCol < cells.length ? cells[animalCol].trim() : "";
            if (animal.isEmpty()) continue;
            if (!seenAnimals.add(animal)) {
                if (!roster.duplicateAnimals.contains(animal)) roster.duplicateAnimals.add(animal);
                continue;   // keep the first occurrence
            }
            Map<String, String> values = new LinkedHashMap<String, String>();
            for (int c = 0; c < header.length; c++) {
                ConditionAxis axis = axisForCol[c];
                if (axis == null) continue;
                String value = c < cells.length ? cells[c].trim() : "";
                if (!value.isEmpty()) values.put(axis.id, value);
            }
            roster.byAnimal.put(animal, values);
        }
        return roster;
    }

    private static String[] splitLine(String line, boolean tsv) {
        if (tsv) return line.split("\t", -1);
        try {
            return CsvSupport.parseRecord(line);
        } catch (java.io.IOException e) {
            return line.split(",", -1);
        }
    }

    /** Map a roster header to an axis label, honouring our own {@code Condition_X} export and OMERO synonyms. */
    static String axisLabelFromHeader(String rawHeader) {
        String raw = rawHeader == null ? "" : rawHeader.trim();
        if (raw.length() > "Condition_".length()
                && raw.regionMatches(true, 0, "Condition_", 0, "Condition_".length())) {
            return raw.substring("Condition_".length());
        }
        String norm = normalise(raw);
        if ("group".equals(norm) || "treatment".equals(norm) || "condition".equals(norm)) return "Condition";
        if ("genotype".equals(norm)) return "Genotype";
        if ("timepoint".equals(norm) || "zt".equals(norm) || "ct".equals(norm) || "time".equals(norm)) {
            return "Timepoint";
        }
        if ("sex".equals(norm)) return "Sex";
        return raw;
    }

    private static String normalise(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    // ── Validation ───────────────────────────────────────────────────────────

    /** Report (don't apply) unmatched / duplicate / conflicting roster rows against the project. */
    public static Validation validate(Roster roster, ConditionAssignments project) {
        Validation v = new Validation();
        if (roster == null) return v;
        v.duplicates.addAll(roster.duplicateAnimals);
        Set<String> projectAnimals = project == null ? new LinkedHashSet<String>() : project.animals();
        for (Map.Entry<String, Map<String, String>> e : roster.byAnimal.entrySet()) {
            String animal = e.getKey();
            if (!projectAnimals.contains(animal)) {
                v.unmatched.add(animal);
                continue;
            }
            for (Map.Entry<String, String> av : e.getValue().entrySet()) {
                String projectValue = project.get(animal, av.getKey());
                if (projectValue != null && !projectValue.trim().isEmpty()
                        && !projectValue.trim().equals(av.getValue().trim())) {
                    v.conflicts.add(new Conflict(animal, av.getKey(), projectValue.trim(), av.getValue().trim()));
                }
            }
        }
        return v;
    }
}
