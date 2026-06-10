package flash.pipeline.io;

import flash.pipeline.naming.ConditionAssignments;
import flash.pipeline.naming.ConditionAxis;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Stage 15: roster CSV/TSV import + validation. */
public class RosterImportTest {

    @Test
    public void headerSynonyms_matchAnimalAndAxisColumns() {
        RosterIO.Roster r = RosterIO.parse(
                "animal_id,Genotype,Timepoint\n"
                        + "M1,hAPP,WeekFour\n"
                        + "M2,Syn,WeekTwo\n");

        assertEquals(2, r.axes.size());
        assertEquals("genotype", r.axes.get(0).id);
        assertEquals("timepoint", r.axes.get(1).id);
        assertEquals("hAPP", r.byAnimal.get("M1").get("genotype"));
        assertEquals("WeekFour", r.byAnimal.get("M1").get("timepoint"));
        assertEquals("Syn", r.byAnimal.get("M2").get("genotype"));
    }

    @Test
    public void groupAndTreatmentHeadersMapToConditionAxis() {
        RosterIO.Roster r = RosterIO.parse("Sample,Group\nM1,Control\n");
        assertEquals(1, r.axes.size());
        assertEquals("condition", r.axes.get(0).id);
        assertEquals("Control", r.byAnimal.get("M1").get("condition"));
    }

    @Test
    public void conditionPrefixHeaderFromOwnExport() {
        RosterIO.Roster r = RosterIO.parse("AnimalName,Condition_Genotype\nM1,WT\n");
        assertEquals("genotype", r.axes.get(0).id);
        assertEquals("WT", r.byAnimal.get("M1").get("genotype"));
    }

    @Test
    public void duplicateAnimalsDetected_firstKept() {
        RosterIO.Roster r = RosterIO.parse("Animal,Group\nM1,WT\nM1,KO\nM2,WT\n");
        assertTrue(r.duplicateAnimals.contains("M1"));
        assertEquals("WT", r.byAnimal.get("M1").get("condition"));   // first occurrence kept
    }

    @Test
    public void tsvParsesToo() {
        RosterIO.Roster r = RosterIO.parse("AnimalID\tGenotype\nM1\thAPP\n");
        assertEquals("hAPP", r.byAnimal.get("M1").get("genotype"));
    }

    @Test
    public void validate_reportsUnmatchedDuplicateConflict() {
        ConditionAssignments project = new ConditionAssignments();
        project.addAxis(ConditionAxis.of("Genotype"));
        project.put("M1", "genotype", "hAPP");
        project.put("M2", "genotype", "Syn");

        RosterIO.Roster roster = RosterIO.parse(
                "Animal,Genotype\n"
                        + "M1,Syn\n"     // conflict: project says hAPP
                        + "M9,WT\n"      // unmatched: not in project
                        + "M2,Syn\n"
                        + "M2,Syn\n");   // duplicate M2

        RosterIO.Validation v = RosterIO.validate(roster, project);
        assertFalse(v.isClean());
        assertTrue(v.unmatched.contains("M9"));
        assertTrue(v.duplicates.contains("M2"));
        assertEquals(1, v.conflicts.size());
        assertEquals("M1", v.conflicts.get(0).animal);
        assertEquals("hAPP", v.conflicts.get(0).projectValue);
        assertEquals("Syn", v.conflicts.get(0).rosterValue);
    }

    @Test
    public void exportThenParse_roundTripsMultiAxis() {
        ConditionAssignments ca = new ConditionAssignments();
        ca.addAxis(ConditionAxis.of("Genotype"));
        ca.addAxis(ConditionAxis.of("Timepoint"));
        ca.put("M1", "genotype", "hAPP");
        ca.put("M1", "timepoint", "WeekFour");

        String csv = RosterIO.export(ca, false);
        RosterIO.Roster back = RosterIO.parse(csv);

        assertEquals(2, back.axes.size());
        assertEquals("hAPP", back.byAnimal.get("M1").get("genotype"));
        assertEquals("WeekFour", back.byAnimal.get("M1").get("timepoint"));
    }
}
