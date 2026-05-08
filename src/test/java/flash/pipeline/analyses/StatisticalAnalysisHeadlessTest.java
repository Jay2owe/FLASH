package flash.pipeline.analyses;

import flash.pipeline.cli.CLIArgumentParser;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.io.FlashProjectLayout;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

import static org.junit.Assert.*;

/**
 * Tests that {@link StatisticalAnalysis} can complete in headless mode
 * without requiring Swing dialogs.
 */
public class StatisticalAnalysisHeadlessTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    /**
     * Headless execution with a pre-existing manifest and master CSV
     * should write Statistics.csv without any Swing interaction.
     */
    @Test
    public void headless_withManifestAndData_writesStatistics() throws Exception {
        File dir = temp.newFolder("project");
        File exportDir = aggregationDir(dir);

        // Write a master objects CSV with at least 3 animals per condition
        writeMasterCsv(new File(exportDir, FlashProjectLayout.MASTER_OBJECTS_FILENAME),
                new String[]{"AnimalName", "Count_A", "Count_B"},
                new String[][]{
                        {"Syn1", "10", "20"},
                        {"Syn2", "12", "22"},
                        {"Syn3", "11", "21"},
                        {"hAPP1", "30", "40"},
                        {"hAPP2", "32", "42"},
                        {"hAPP3", "31", "41"}
                });

        // Write condition manifest
        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        conditions.put("Syn1", "Syn");
        conditions.put("Syn2", "Syn");
        conditions.put("Syn3", "Syn");
        conditions.put("hAPP1", "hAPP");
        conditions.put("hAPP2", "hAPP");
        conditions.put("hAPP3", "hAPP");
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), conditions);

        // Run in headless mode
        StatisticalAnalysis analysis = new StatisticalAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.execute(dir.getAbsolutePath());

        File statsFile = statisticsFile(dir);
        assertTrue("Statistics.csv should be created", statsFile.exists());
        assertTrue("Statistics file should not be empty", statsFile.length() > 0);
    }

    /**
     * Headless with fewer than 2 conditions should abort cleanly
     * without opening any dialog and without leaving a partial output.
     */
    @Test
    public void headless_singleCondition_abortsCleanly() throws Exception {
        File dir = temp.newFolder("project");
        File exportDir = aggregationDir(dir);

        writeMasterCsv(new File(exportDir, FlashProjectLayout.MASTER_OBJECTS_FILENAME),
                new String[]{"AnimalName", "Count"},
                new String[][]{
                        {"Syn1", "10"},
                        {"Syn2", "12"},
                        {"Syn3", "11"}
                });

        // All in one condition
        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        conditions.put("Syn1", "Syn");
        conditions.put("Syn2", "Syn");
        conditions.put("Syn3", "Syn");
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), conditions);

        StatisticalAnalysis analysis = new StatisticalAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.execute(dir.getAbsolutePath());

        File statsFile = statisticsFile(dir);
        assertFalse("Should not produce partial statistics with <2 conditions",
                statsFile.exists());
    }

    /**
     * Headless with no manifest falls back to auto-detection and still
     * works when at least 2 conditions are resolved.
     */
    @Test
    public void headless_noManifest_usesAutoDetection() throws Exception {
        File dir = temp.newFolder("project");
        File exportDir = aggregationDir(dir);

        // Animal names that auto-detect to different conditions
        writeMasterCsv(new File(exportDir, FlashProjectLayout.MASTER_OBJECTS_FILENAME),
                new String[]{"AnimalName", "Count"},
                new String[][]{
                        {"Syn1Week2", "10"},
                        {"Syn2Week2", "12"},
                        {"Syn3Week2", "11"},
                        {"hAPP1Week2", "30"},
                        {"hAPP2Week2", "32"},
                        {"hAPP3Week2", "31"}
                });

        // No manifest file — should auto-detect
        StatisticalAnalysis analysis = new StatisticalAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.execute(dir.getAbsolutePath());

        File statsFile = statisticsFile(dir);
        assertTrue("Auto-detected conditions should produce statistics",
                statsFile.exists());
    }

    @Test
    public void headless_legacyMasterCsv_readsFromImageJExportsAndWritesNewStatistics() throws Exception {
        File dir = temp.newFolder("legacy-project");
        File exportDir = new File(dir, "ImageJ Exports");
        assertTrue(exportDir.mkdirs());

        writeMasterCsv(new File(exportDir, "Project_Master_Objects.csv"),
                new String[]{"AnimalName", "Count"},
                new String[][]{
                        {"Syn1", "10"},
                        {"Syn2", "12"},
                        {"Syn3", "11"},
                        {"hAPP1", "30"},
                        {"hAPP2", "32"},
                        {"hAPP3", "31"}
                });

        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        conditions.put("Syn1", "Syn");
        conditions.put("Syn2", "Syn");
        conditions.put("Syn3", "Syn");
        conditions.put("hAPP1", "hAPP");
        conditions.put("hAPP2", "hAPP");
        conditions.put("hAPP3", "hAPP");
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), conditions);

        StatisticalAnalysis analysis = new StatisticalAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.execute(dir.getAbsolutePath());

        assertTrue("Statistics should be written to the new statistics folder",
                statisticsFile(dir).exists());
        assertFalse("Legacy statistics output should not be written",
                new File(exportDir, FlashProjectLayout.LEGACY_STATISTICS_FILENAME).exists());
        assertFalse("New statistics output should not be written outside FLASH",
                new File(exportDir, FlashProjectLayout.STATISTICS_FILENAME).exists());
    }

    /**
     * resolveConditionAssignments in headless mode returns assignments
     * without opening any Swing dialog.
     */
    @Test
    public void resolveConditionAssignments_headless_returnsManifest() throws Exception {
        File dir = temp.newFolder("project");
        File exportDir = aggregationDir(dir);

        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        conditions.put("Syn1", "Control");
        conditions.put("hAPP1", "Treatment");
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), conditions);

        StatisticalAnalysis analysis = new StatisticalAnalysis();
        analysis.setHeadless(true);

        LinkedHashSet<String> animals = new LinkedHashSet<String>();
        animals.add("Syn1");
        animals.add("hAPP1");

        java.util.Map<String, String> result =
                analysis.resolveConditionAssignments(dir.getAbsolutePath(), animals);

        assertNotNull("Should return assignments in headless mode", result);
        assertEquals("Control", result.get("Syn1"));
        assertEquals("Treatment", result.get("hAPP1"));
    }

    /**
     * Headless run with {@code stats.paired=hemisphere} should mark pairwise
     * rows as {@code Paired=Yes}. The engine documents that Tukey/Dunn's
     * fall back to Bonferroni inside the paired branch, so this test only
     * verifies the paired flag plumbing.
     */
    @Test
    public void headless_pairedCli_writesPairedYesColumn() throws Exception {
        File dir = temp.newFolder("project");
        File exportDir = aggregationDir(dir);

        writeMasterCsv(new File(exportDir, FlashProjectLayout.MASTER_OBJECTS_FILENAME),
                new String[]{"AnimalName", "Count_A"},
                new String[][]{
                        {"A1_LH", "10"}, {"A1_RH", "12"},
                        {"A2_LH", "11"}, {"A2_RH", "13"},
                        {"A3_LH", "12"}, {"A3_RH", "14"},
                        {"B1_LH", "20"}, {"B1_RH", "22"},
                        {"B2_LH", "21"}, {"B2_RH", "23"},
                        {"B3_LH", "22"}, {"B3_RH", "24"}
                });

        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        for (String prefix : new String[]{"A1", "A2", "A3"}) {
            conditions.put(prefix + "_LH", "Cond1");
            conditions.put(prefix + "_RH", "Cond1");
        }
        for (String prefix : new String[]{"B1", "B2", "B3"}) {
            conditions.put(prefix + "_LH", "Cond2");
            conditions.put(prefix + "_RH", "Cond2");
        }
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), conditions);

        CLIConfig cli = CLIArgumentParser.parse(
                "dir=[" + dir.getAbsolutePath() + "] stats.paired=hemisphere");
        assertNotNull(cli);

        StatisticalAnalysis analysis = new StatisticalAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setCliConfig(cli);
        analysis.execute(dir.getAbsolutePath());

        File statsFile = statisticsFile(dir);
        assertTrue("Statistics.csv should be created", statsFile.exists());

        boolean sawPairedYes = false;
        BufferedReader reader = new BufferedReader(new FileReader(statsFile));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("Metric,")) continue;
                String[] cells = line.split(",");
                String pairedCol = cells.length > 14 ? cells[14] : "";
                if ("Yes".equalsIgnoreCase(pairedCol)) sawPairedYes = true;
            }
        } finally {
            reader.close();
        }
        assertTrue("Paired=Yes column should appear in output", sawPairedYes);
    }

    /**
     * Headless run with {@code stats.posthoc=dunns} on 3+ unpaired conditions
     * should label pairwise rows with {@code PostHocMethod=Dunn's}.
     */
    @Test
    public void headless_dunnsCli_writesDunnsPostHocColumn() throws Exception {
        File dir = temp.newFolder("project");
        File exportDir = aggregationDir(dir);

        writeMasterCsv(new File(exportDir, FlashProjectLayout.MASTER_OBJECTS_FILENAME),
                new String[]{"AnimalName", "Count_A"},
                new String[][]{
                        {"A1", "10"}, {"A2", "11"}, {"A3", "12"},
                        {"B1", "20"}, {"B2", "21"}, {"B3", "22"},
                        {"C1", "30"}, {"C2", "31"}, {"C3", "32"}
                });

        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        conditions.put("A1", "Cond1"); conditions.put("A2", "Cond1"); conditions.put("A3", "Cond1");
        conditions.put("B1", "Cond2"); conditions.put("B2", "Cond2"); conditions.put("B3", "Cond2");
        conditions.put("C1", "Cond3"); conditions.put("C2", "Cond3"); conditions.put("C3", "Cond3");
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), conditions);

        CLIConfig cli = CLIArgumentParser.parse(
                "dir=[" + dir.getAbsolutePath() + "] stats.posthoc=dunns");
        assertNotNull(cli);

        StatisticalAnalysis analysis = new StatisticalAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setCliConfig(cli);
        analysis.execute(dir.getAbsolutePath());

        File statsFile = statisticsFile(dir);
        assertTrue("Statistics.csv should be created", statsFile.exists());

        boolean sawDunns = false;
        BufferedReader reader = new BufferedReader(new FileReader(statsFile));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("Metric,")) continue;
                String[] cells = line.split(",");
                String postHocCol = cells.length > 15 ? cells[15] : "";
                if (postHocCol.contains("Dunn")) sawDunns = true;
            }
        } finally {
            reader.close();
        }
        assertTrue("Dunn's post-hoc rows should appear in output", sawDunns);
    }

    private static File aggregationDir(File root) {
        File dir = FlashProjectLayout.forDirectory(root.getAbsolutePath()).aggregationWriteDir();
        assertTrue(dir.isDirectory() || dir.mkdirs());
        return dir;
    }

    private static File statisticsFile(File root) {
        return FlashProjectLayout.forDirectory(root.getAbsolutePath())
                .statisticsWriteFile(FlashProjectLayout.STATISTICS_FILENAME);
    }

    private static void writeMasterCsv(File file, String[] headers, String[][] rows)
            throws Exception {
        PrintWriter pw = new PrintWriter(file);
        try {
            StringBuilder header = new StringBuilder();
            for (int i = 0; i < headers.length; i++) {
                if (i > 0) header.append(",");
                header.append(headers[i]);
            }
            pw.println(header);
            for (String[] row : rows) {
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < row.length; i++) {
                    if (i > 0) line.append(",");
                    line.append(row[i]);
                }
                pw.println(line);
            }
        } finally {
            pw.close();
        }
    }
}
