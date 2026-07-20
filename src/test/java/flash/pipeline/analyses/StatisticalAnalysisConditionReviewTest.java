package flash.pipeline.analyses;

import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.io.CsvSupport;
import flash.pipeline.io.FlashProjectLayout;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.PrintWriter;
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
 * Stage 05 statistics behaviour: ordered condition collection plus unattended
 * single-condition handling (the GUI re-prompt path terminates without hanging).
 */
public class StatisticalAnalysisConditionReviewTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void orderedConditionsDedupesAndPreservesOrder() {
        Set<String> animals = new LinkedHashSet<String>(Arrays.asList("A1", "A2", "A3", "A4"));
        Map<String, String> conditions = new LinkedHashMap<String, String>();
        conditions.put("A1", "Treated");
        conditions.put("A2", "Control");
        conditions.put("A3", "Treated");
        conditions.put("A4", "");

        List<String> ordered = StatisticalAnalysis.orderedConditions(animals, conditions);
        assertEquals(Arrays.asList("Treated", "Control"), ordered);
    }

    @Test
    public void singleConditionUnattendedDoesNotWriteStatistics() throws Exception {
        File dir = masterProject("collapsed",
                "A1", "Control", "A2", "Control", "B1", "Control", "B2", "Control");

        runHeadless(dir);

        assertFalse("single-condition unattended run must not produce Statistics.csv",
                statisticsFile(dir).isFile());
    }

    @Test
    public void twoConditionsUnattendedWritesStatistics() throws Exception {
        File dir = masterProject("two-groups",
                "A1", "Control", "A2", "Control", "B1", "Treated", "B2", "Treated");

        runHeadless(dir);

        assertTrue("two-condition unattended run should produce Statistics.csv",
                statisticsFile(dir).isFile());
    }

    private File masterProject(String name, String... animalCondition) throws Exception {
        File dir = temp.newFolder(name);
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(dir.getAbsolutePath());
        File summaryDir = layout.tablesProjectSummaryWriteDir();
        assertTrue(summaryDir.isDirectory() || summaryDir.mkdirs());

        // Master carries a (stale) Condition column that statistics must ignore as
        // a metric; condition grouping comes from Conditions.csv.
        StringBuilder rows = new StringBuilder();
        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        double value = 10.0;
        for (int i = 0; i + 1 < animalCondition.length; i += 2) {
            String animal = animalCondition[i];
            conditions.put(animal, animalCondition[i + 1]);
            rows.append(animal).append(",Stale,").append(value).append('\n');
            value += 3.0;
        }
        writeRaw(new File(summaryDir, FlashProjectLayout.MASTER_OBJECTS_FILENAME),
                "AnimalName,Condition,GFAP_Count\n" + rows);
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), conditions);
        return dir;
    }

    private static void runHeadless(File dir) {
        StatisticalAnalysis analysis = new StatisticalAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.execute(dir.getAbsolutePath());
    }

    private static File statisticsFile(File dir) {
        return FlashProjectLayout.forDirectory(dir.getAbsolutePath())
                .projectSummaryWriteFile(FlashProjectLayout.STATISTICS_FILENAME);
    }

    private static void writeRaw(File file, String content) throws Exception {
        PrintWriter pw = CsvSupport.newWriter(file);
        try {
            pw.print(content);
        } finally {
            pw.close();
        }
    }
}
