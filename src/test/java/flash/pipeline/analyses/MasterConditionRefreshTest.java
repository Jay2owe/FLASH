package flash.pipeline.analyses;

import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.io.FlashProjectLayout;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Verifies post-hoc refresh of the Condition column in existing master tables
 * for both old (no Condition column) and new (Condition present) schemas.
 */
public class MasterConditionRefreshTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void oldMasterGainsConditionAfterAnimalNamePreservingColumns() throws Exception {
        File root = temp.newFolder("refresh-old");
        saveConditions(root, "Mouse01", "Control", "Mouse02", "Treated");
        File master = master(root, "3D Objects.csv",
                "AnimalName,numSections,DAPI_Count,run_id",
                "Mouse01,2,100,abc",
                "Mouse02,3,80,def");

        AggregationConditionSupport.RefreshResult result =
                AggregationConditionSupport.refreshMasterCsvConditions(root.getAbsolutePath(), master);

        assertTrue(result.rewritten);
        assertEquals(2, result.rowsUpdated);
        assertEquals(0, result.rowsUnresolved);

        List<String> lines = read(master);
        assertEquals("AnimalName,Condition,numSections,DAPI_Count,run_id", lines.get(0));
        assertEquals("Mouse01,Control,2,100,abc", rowFor(lines, "Mouse01"));
        assertEquals("Mouse02,Treated,3,80,def", rowFor(lines, "Mouse02"));
    }

    @Test
    public void newMasterUpdatesConditionWithoutDuplicatingColumn() throws Exception {
        File root = temp.newFolder("refresh-new");
        saveConditions(root, "Mouse01", "Control", "Mouse02", "Treated");
        File master = master(root, "3D Objects.csv",
                "AnimalName,Condition,numSections,DAPI_Count",
                "Mouse01,StaleLabel,2,100",
                "Mouse02,StaleLabel,3,80");

        AggregationConditionSupport.refreshMasterCsvConditions(root.getAbsolutePath(), master);

        List<String> lines = read(master);
        assertEquals("AnimalName,Condition,numSections,DAPI_Count", lines.get(0));
        assertEquals(1, countOccurrences(lines.get(0), "Condition"));
        assertEquals("Mouse01,Control,2,100", rowFor(lines, "Mouse01"));
        assertEquals("Mouse02,Treated,3,80", rowFor(lines, "Mouse02"));
    }

    @Test
    public void groupedRowsResolveParentConditionViaSnapshot() throws Exception {
        File root = temp.newFolder("refresh-grouped");
        saveConditions(root, "Mouse01", "Control");
        snapshot(root,
                "RowName,ParentAnimal,Condition,run_id",
                "Mouse01-LH,Mouse01,Control,run1",
                "Mouse01-RH,Mouse01,Control,run1");
        File master = master(root, "3D Objects.csv",
                "AnimalName,numSections,Count",
                "Mouse01-LH,1,5",
                "Mouse01-RH,1,6");

        AggregationConditionSupport.refreshMasterCsvConditions(root.getAbsolutePath(), master);

        List<String> lines = read(master);
        assertEquals("Mouse01-LH,Control,1,5", rowFor(lines, "Mouse01-LH"));
        assertEquals("Mouse01-RH,Control,1,6", rowFor(lines, "Mouse01-RH"));
    }

    @Test
    public void unresolvableRowGetsBlankConditionAndIsCounted() throws Exception {
        File root = temp.newFolder("refresh-blank");
        saveConditions(root, "Mouse01", "Control");
        // A blank AnimalName has no resolvable parent -> blank condition.
        File master = master(root, "3D Objects.csv",
                "AnimalName,numSections,Count",
                "Mouse01,1,5",
                ",1,6");

        AggregationConditionSupport.RefreshResult result =
                AggregationConditionSupport.refreshMasterCsvConditions(root.getAbsolutePath(), master);

        assertEquals(1, result.rowsUpdated);
        assertEquals(1, result.rowsUnresolved);
        List<String> lines = read(master);
        assertEquals("Mouse01,Control,1,5", rowFor(lines, "Mouse01"));
    }

    @Test
    public void missingAnimalNameColumnIsSkipped() throws Exception {
        File root = temp.newFolder("refresh-noanimal");
        File master = master(root, "3D Objects.csv",
                "Foo,Bar",
                "1,2");
        AggregationConditionSupport.RefreshResult result =
                AggregationConditionSupport.refreshMasterCsvConditions(root.getAbsolutePath(), master);
        assertFalse(result.rewritten);
        // Unchanged on skip.
        assertEquals("Foo,Bar", read(master).get(0));
    }

    @Test
    public void refreshAllMasterTablesUpdatesEveryKnownMaster() throws Exception {
        File root = temp.newFolder("refresh-all");
        saveConditions(root, "Mouse01", "Control");
        master(root, "3D Objects.csv", "AnimalName,Count", "Mouse01,5");
        master(root, "Image Intensities.csv", "AnimalName,Mean", "Mouse01,10");

        AggregationConditionSupport.RefreshSummary summary =
                new MasterAggregationAnalysis().refreshExistingMasterConditions(root.getAbsolutePath());

        assertTrue(summary.anyUpdated());
        assertEquals(2, summary.filesUpdated);
    }

    // --- helpers -----------------------------------------------------------

    private static FlashProjectLayout layout(File root) {
        return FlashProjectLayout.forDirectory(root.getAbsolutePath());
    }

    private static void saveConditions(File root, String... animalCondition) throws Exception {
        LinkedHashMap<String, String> map = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < animalCondition.length; i += 2) {
            map.put(animalCondition[i], animalCondition[i + 1]);
        }
        ConditionManifestIO.saveAssignments(root.getAbsolutePath(), map);
    }

    private File master(File root, String name, String... lines) throws Exception {
        File dir = layout(root).tablesProjectSummaryWriteDir();
        dir.mkdirs();
        File file = new File(dir, name);
        writeLines(file, lines);
        return file;
    }

    private void snapshot(File root, String... lines) throws Exception {
        File dir = layout(root).analysisDetailsWriteDir();
        dir.mkdirs();
        writeLines(new File(dir, AggregationConditionSupport.SNAPSHOT_FILENAME), lines);
    }

    private static void writeLines(File file, String... lines) throws Exception {
        PrintWriter pw = new PrintWriter(file, "UTF-8");
        try {
            for (String line : lines) {
                pw.println(line);
            }
        } finally {
            pw.close();
        }
    }

    private static List<String> read(File file) throws Exception {
        return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
    }

    private static String rowFor(List<String> lines, String animal) {
        for (int i = 1; i < lines.size(); i++) {
            String[] cells = lines.get(i).split(",", -1);
            if (cells.length > 0 && cells[0].equals(animal)) {
                return lines.get(i);
            }
        }
        return null;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
