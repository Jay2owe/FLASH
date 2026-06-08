package flash.pipeline.io;

import flash.pipeline.naming.ConditionAssignments;
import flash.pipeline.naming.ConditionAxis;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
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

    private static String readAll(File f) throws Exception {
        Scanner sc = new Scanner(f, "UTF-8").useDelimiter("\\A");
        try {
            return sc.hasNext() ? sc.next() : "";
        } finally {
            sc.close();
        }
    }
}
