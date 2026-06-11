package flash.pipeline.io;

import flash.pipeline.naming.ConditionAssignments;
import flash.pipeline.naming.ConditionAxis;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Scanner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Multi-axis read/write + legacy back-compat for {@link ConditionManifestIO}. */
public class ConditionManifestIOMultiAxisTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void twoAxisRoundTrip() throws Exception {
        ConditionAssignments ca = new ConditionAssignments();
        ca.addAxis(ConditionAxis.of("Genotype"));
        ca.addAxis(ConditionAxis.of("Timepoint"));
        ca.put("M14", "genotype", "hAPP");
        ca.put("M14", "timepoint", "WeekFour");
        ca.put("M15", "genotype", "Syn");
        ca.put("M15", "timepoint", "WeekTwo");

        File f = temp.newFile("Conditions.csv");
        ConditionManifestIO.writeAssignments(f, ca);
        ConditionAssignments back = ConditionManifestIO.readAssignments(f);

        assertEquals(2, back.axes().size());
        assertEquals("hAPP", back.get("M14", "genotype"));
        assertEquals("WeekFour", back.get("M14", "timepoint"));
        assertEquals("Syn", back.get("M15", "genotype"));
        assertEquals("WeekTwo", back.get("M15", "timepoint"));
    }

    @Test
    public void multiAxisHeaderUsesConditionPrefix() throws Exception {
        ConditionAssignments ca = new ConditionAssignments();
        ca.addAxis(ConditionAxis.of("Genotype"));
        ca.addAxis(ConditionAxis.of("Timepoint"));
        ca.put("M1", "genotype", "WT");

        File f = temp.newFile("c.csv");
        ConditionManifestIO.writeAssignments(f, ca);
        String text = readAll(f);
        assertTrue(text.contains("Condition_Genotype"));
        assertTrue(text.contains("Condition_Timepoint"));
    }

    @Test
    public void legacyReadReturnsCompositeForMultiAxisManifest() throws Exception {
        ConditionAssignments ca = new ConditionAssignments();
        ca.addAxis(ConditionAxis.of("Genotype"));
        ca.addAxis(ConditionAxis.of("Timepoint"));
        ca.put("M1", "genotype", "hAPP");
        ca.put("M1", "timepoint", "WeekFour");

        File f = temp.newFile("legacy-view.csv");
        ConditionManifestIO.writeAssignments(f, ca);

        Map<String, String> back = ConditionManifestIO.read(f);
        assertEquals("hAPP_WeekFour", back.get("M1"));
    }

    @Test
    public void legacyFileReadsAsSingleConditionAxis() throws Exception {
        File f = temp.newFile("legacy.csv");
        PrintWriter pw = new PrintWriter(new FileWriter(f));
        pw.println("AnimalName,Condition");
        pw.println("M14,WT");
        pw.println("M15,KO");
        pw.close();

        ConditionAssignments back = ConditionManifestIO.readAssignments(f);
        assertEquals(1, back.axes().size());
        assertEquals("condition", back.axes().get(0).id);
        assertEquals("WT", back.get("M14", "condition"));
        assertEquals("KO", back.get("M15", "condition"));
    }

    @Test
    public void singleDefaultAxisWritesLegacyHeader() throws Exception {
        ConditionAssignments ca = new ConditionAssignments();
        ca.addAxis(ConditionAxis.of("Condition"));
        ca.put("M14", "condition", "WT");

        File f = temp.newFile("single.csv");
        ConditionManifestIO.writeAssignments(f, ca);
        String text = readAll(f);
        assertTrue(text.contains("AnimalName"));
        assertFalse(text.contains("Condition_Condition"));   // not double-prefixed

        ConditionAssignments back = ConditionManifestIO.readAssignments(f);
        assertEquals("WT", back.get("M14", "condition"));
    }

    @Test
    public void resolveAssignmentsModel_readsPerAxisAndMatchesCompositeForMissing() throws Exception {
        File dir = temp.newFolder("project");

        ConditionAssignments persisted = new ConditionAssignments();
        persisted.addAxis(ConditionAxis.of("Genotype"));
        persisted.addAxis(ConditionAxis.of("Timepoint"));
        persisted.put("M14", "genotype", "hAPP");
        persisted.put("M14", "timepoint", "WeekFour");
        ConditionManifestIO.writeAssignments(
                ConditionManifestIO.getFile(dir.getAbsolutePath()), persisted);

        ConditionAssignments model = ConditionManifestIO.resolveAssignmentsModel(
                dir.getAbsolutePath(),
                new LinkedHashSet<String>(Arrays.asList("M14", "M15")));

        // per-axis values for the persisted animal
        assertEquals("hAPP", model.get("M14", "genotype"));
        assertEquals("WeekFour", model.get("M14", "timepoint"));
        // missing animal still resolves (fallback stored on the primary axis)
        assertFalse(model.composite("M15", "_").isEmpty());
    }

    @Test
    public void resolveAssignmentsModel_singleAxisMatchesLegacyResolve() throws Exception {
        File dir = temp.newFolder("legacy-project");

        java.util.LinkedHashMap<String, String> legacy = new java.util.LinkedHashMap<String, String>();
        legacy.put("Syn1WeekTwo", "SynWeekTwo");
        ConditionManifestIO.write(ConditionManifestIO.getFile(dir.getAbsolutePath()), legacy);

        LinkedHashSet<String> animals =
                new LinkedHashSet<String>(Arrays.asList("Syn1WeekTwo", "hAPP2WeekEight"));

        Map<String, String> composite =
                ConditionManifestIO.resolveAssignments(dir.getAbsolutePath(), animals);
        ConditionAssignments model =
                ConditionManifestIO.resolveAssignmentsModel(dir.getAbsolutePath(), animals);

        // single-axis project: model composite reproduces the legacy resolve exactly
        for (String animal : animals) {
            assertEquals(composite.get(animal), model.composite(animal, "_"));
        }
    }

    @Test
    public void saveAssignmentsPreservingMultiAxis_doesNotClobberPerAxisFile() throws Exception {
        File dir = temp.newFolder("multi");

        ConditionAssignments multi = new ConditionAssignments();
        multi.addAxis(ConditionAxis.of("Genotype"));
        multi.addAxis(ConditionAxis.of("Timepoint"));
        multi.put("M1", "genotype", "hAPP");
        multi.put("M1", "timepoint", "WeekFour");
        ConditionManifestIO.writeAssignments(ConditionManifestIO.getFile(dir.getAbsolutePath()), multi);

        java.util.LinkedHashMap<String, String> composite = new java.util.LinkedHashMap<String, String>();
        composite.put("M1", "hAPP_WeekFour");
        boolean written = ConditionManifestIO.saveAssignmentsPreservingMultiAxis(
                dir.getAbsolutePath(), composite);

        assertFalse("multi-axis manifest must be preserved", written);
        // per-axis columns survive
        ConditionAssignments back =
                ConditionManifestIO.readAssignments(ConditionManifestIO.getFile(dir.getAbsolutePath()));
        assertEquals(2, back.axes().size());
        assertEquals("hAPP", back.get("M1", "genotype"));
        assertEquals("WeekFour", back.get("M1", "timepoint"));
    }

    @Test
    public void saveAssignmentsPreservingMultiAxis_writesWhenSingleAxis() throws Exception {
        File dir = temp.newFolder("single");

        java.util.LinkedHashMap<String, String> composite = new java.util.LinkedHashMap<String, String>();
        composite.put("M1", "WT");
        boolean written = ConditionManifestIO.saveAssignmentsPreservingMultiAxis(
                dir.getAbsolutePath(), composite);

        assertTrue("single-axis project should persist edits", written);
        Map<String, String> back =
                ConditionManifestIO.read(ConditionManifestIO.getFile(dir.getAbsolutePath()));
        assertEquals("WT", back.get("M1"));
    }

    @Test
    public void axisLabelWithWhitespaceRoundTripsId() throws Exception {
        ConditionAssignments ca = new ConditionAssignments();
        ca.addAxis(ConditionAxis.of("Time Point"));   // id "time_point"
        ca.put("M1", "time_point", "WeekFour");

        File f = temp.newFile("ws.csv");
        ConditionManifestIO.writeAssignments(f, ca);
        ConditionAssignments back = ConditionManifestIO.readAssignments(f);

        // the whitespace label round-trips to the same axis id (Condition_Time_Point)
        assertEquals(1, back.axes().size());
        assertEquals("time_point", back.axes().get(0).id);
        assertEquals("WeekFour", back.get("M1", "time_point"));
    }

    @Test
    public void saveAssignmentsPreservingMultiAxis_preservesSingleNonConditionAxis() throws Exception {
        File dir = temp.newFolder("single-geno");
        ConditionAssignments geno = new ConditionAssignments();
        geno.addAxis(ConditionAxis.of("Genotype"));   // one axis, but NOT the legacy "condition"
        geno.put("M1", "genotype", "hAPP");
        ConditionManifestIO.writeAssignments(ConditionManifestIO.getFile(dir.getAbsolutePath()), geno);

        java.util.LinkedHashMap<String, String> composite = new java.util.LinkedHashMap<String, String>();
        composite.put("M1", "WT");
        boolean written = ConditionManifestIO.saveAssignmentsPreservingMultiAxis(
                dir.getAbsolutePath(), composite);

        assertFalse("a single non-condition axis must be preserved", written);
        ConditionAssignments back =
                ConditionManifestIO.readAssignments(ConditionManifestIO.getFile(dir.getAbsolutePath()));
        assertEquals(1, back.axes().size());
        assertEquals("genotype", back.axes().get(0).id);
        assertEquals("hAPP", back.get("M1", "genotype"));
    }

    private static String readAll(File f) throws Exception {
        Scanner sc = new Scanner(f, "UTF-8").useDelimiter("\\A");
        try {
            return sc.hasNext() ? sc.next() : "";
        } finally {
            sc.close();
        }
    }
}
