package flash.pipeline.analyses;

import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.naming.ConditionAssignments;
import flash.pipeline.naming.ConditionAxis;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Stage 05: additive per-axis {@code Condition_<axis>} columns in master tables,
 * keeping the composite {@code Condition} column unchanged for back-compat.
 */
public class AggregationConditionAxisColumnsTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private String twoAxisProject() throws Exception {
        File dir = temp.newFolder("project");
        ConditionAssignments ca = new ConditionAssignments();
        ca.addAxis(ConditionAxis.of("Genotype"));
        ca.addAxis(ConditionAxis.of("Timepoint"));
        ca.put("M1", "genotype", "hAPP");
        ca.put("M1", "timepoint", "WeekFour");
        ca.put("M2", "genotype", "Syn");
        ca.put("M2", "timepoint", "WeekTwo");
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), ca);
        return dir.getAbsolutePath();
    }

    private static Set<String> set(String... values) {
        return new LinkedHashSet<String>(Arrays.asList(values));
    }

    @Test
    public void resolveAxisColumns_multiAxis_returnsPerAxisColumns() throws Exception {
        String dir = twoAxisProject();
        Map<String, String> groupToAnimal = new LinkedHashMap<String, String>();
        groupToAnimal.put("M1", "M1");
        groupToAnimal.put("M2", "M2");

        LinkedHashMap<String, LinkedHashMap<String, String>> cols =
                AggregationConditionSupport.resolveAxisColumns(dir, set("M1", "M2"), groupToAnimal);

        assertEquals(set("Condition_Genotype", "Condition_Timepoint"), cols.keySet());
        assertEquals("hAPP", cols.get("Condition_Genotype").get("M1"));
        assertEquals("WeekTwo", cols.get("Condition_Timepoint").get("M2"));
    }

    @Test
    public void resolveAxisColumns_singleAxis_isEmpty() throws Exception {
        File dir = temp.newFolder("single");
        ConditionAssignments ca = ConditionAssignments.ofLegacy(null);
        ca.put("M1", "condition", "Control");
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), ca);

        Map<String, String> groupToAnimal = new LinkedHashMap<String, String>();
        groupToAnimal.put("M1", "M1");
        LinkedHashMap<String, LinkedHashMap<String, String>> cols =
                AggregationConditionSupport.resolveAxisColumns(
                        dir.getAbsolutePath(), set("M1"), groupToAnimal);
        assertTrue(cols.isEmpty());
    }

    @Test
    public void refreshMaster_insertsAxisColumnsAfterComposite_andIsIdempotent() throws Exception {
        String dir = twoAxisProject();
        File master = new File(temp.getRoot(), "Master_Image Objects.csv");
        writeLines(master,
                "AnimalName,Condition,count",
                "M1,hAPP_WeekFour,5",
                "M2,Syn_WeekTwo,7");

        AggregationConditionSupport.refreshMasterCsvConditions(dir, master);

        List<String> lines = readWhenHeaderContains(master, "Condition_Genotype");
        assertEquals("AnimalName,Condition,Condition_Genotype,Condition_Timepoint,count", lines.get(0));
        assertEquals("M1,hAPP_WeekFour,hAPP,WeekFour,5", lines.get(1));
        assertEquals("M2,Syn_WeekTwo,Syn,WeekTwo,7", lines.get(2));

        // A second refresh must not duplicate the axis columns.
        AggregationConditionSupport.refreshMasterCsvConditions(dir, master);
        List<String> again = readWhenHeaderContains(master, "Condition_Genotype");
        assertEquals("AnimalName,Condition,Condition_Genotype,Condition_Timepoint,count", again.get(0));
        assertEquals(3, again.size());
    }

    @Test
    public void refreshMaster_partialAxisColumns_insertsNewAxisInSchemaOrder() throws Exception {
        // Master already has one axis column; a refresh must insert the missing axis
        // AFTER it (schema order), not before it.
        String dir = twoAxisProject();
        File master = new File(temp.getRoot(), "Master_partial.csv");
        writeLines(master,
                "AnimalName,Condition,Condition_Genotype,count",
                "M1,hAPP_WeekFour,hAPP,5");

        AggregationConditionSupport.refreshMasterCsvConditions(dir, master);

        List<String> lines = readWhenHeaderContains(master, "Condition_Timepoint");
        assertEquals("AnimalName,Condition,Condition_Genotype,Condition_Timepoint,count", lines.get(0));
        assertEquals("M1,hAPP_WeekFour,hAPP,WeekFour,5", lines.get(1));
    }

    @Test
    public void refreshMaster_singleAxis_keepsExactlyOneConditionColumn() throws Exception {
        File dir = temp.newFolder("single2");
        ConditionAssignments ca = ConditionAssignments.ofLegacy(null);
        ca.put("M1", "condition", "Control");
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), ca);

        File master = new File(temp.getRoot(), "Master_single.csv");
        writeLines(master, "AnimalName,count", "M1,5");

        AggregationConditionSupport.refreshMasterCsvConditions(dir.getAbsolutePath(), master);
        List<String> lines = readWhenHeaderContains(master, "Condition");
        assertEquals("AnimalName,Condition,count", lines.get(0));
        assertEquals("M1,Control,5", lines.get(1));
        assertFalse(lines.get(0).contains("Condition_"));
    }

    /**
     * Read the CSV once the atomic replace has settled. Avoids a Windows
     * read-after-atomic-move race where the directory entry briefly still points
     * at the pre-refresh file (the production write is synchronous and correct).
     */
    private static List<String> readWhenHeaderContains(File f, String token) throws Exception {
        List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        for (int i = 0; i < 100 && (lines.isEmpty() || !lines.get(0).contains(token)); i++) {
            Thread.sleep(10);
            lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        }
        return lines;
    }

    private static void writeLines(File f, String... lines) throws Exception {
        PrintWriter pw = new PrintWriter(f, "UTF-8");
        try {
            for (String line : lines) pw.println(line);
        } finally {
            pw.close();
        }
    }
}
