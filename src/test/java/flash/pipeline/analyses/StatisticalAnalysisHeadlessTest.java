package flash.pipeline.analyses;

import flash.pipeline.cli.CLIArgumentParser;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.analyses.wizard.StatisticsPreset;
import flash.pipeline.analyses.wizard.StatisticsPresetIO;
import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.io.CsvSupport;
import flash.pipeline.io.FlashProjectLayout;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Tests that {@link StatisticalAnalysis} can complete in headless mode
 * without requiring Swing dialogs.
 */
public class StatisticalAnalysisHeadlessTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void hideImageWindowsFlagAloneIsNotUnattendedMode() {
        assertTrue(StatisticalAnalysis.canShowGuiDialog(false, null, false));
        assertFalse(StatisticalAnalysis.isUnattendedMode(false, null, false));

        assertFalse(StatisticalAnalysis.canShowGuiDialog(true, null, false));
        assertFalse(StatisticalAnalysis.canShowGuiDialog(false, new CLIConfig(), false));
        assertFalse(StatisticalAnalysis.canShowGuiDialog(false, null, true));
    }

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

    @Test
    public void headless_excludesNumZSlicesFromMetricTesting() throws Exception {
        File dir = temp.newFolder("stats-z-count");
        File exportDir = aggregationDir(dir);

        writeMasterCsv(new File(exportDir, FlashProjectLayout.MASTER_INTENSITIES_FILENAME),
                new String[]{"AnimalName", "numZSlices", "SignalMean"},
                new String[][]{
                        {"Ctl1", "3", "10"},
                        {"Ctl2", "3", "12"},
                        {"Ctl3", "3", "11"},
                        {"Tx1", "3", "30"},
                        {"Tx2", "3", "32"},
                        {"Tx3", "3", "31"}
                });

        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        conditions.put("Ctl1", "Control");
        conditions.put("Ctl2", "Control");
        conditions.put("Ctl3", "Control");
        conditions.put("Tx1", "Treatment");
        conditions.put("Tx2", "Treatment");
        conditions.put("Tx3", "Treatment");
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), conditions);

        StatisticalAnalysis analysis = new StatisticalAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.execute(dir.getAbsolutePath());

        List<Map<String, String>> stats = readCsv(statisticsFile(dir));
        assertNotNull(findRow(stats, "Metric", "SignalMean"));
        assertEquals(0, countRows(stats, "Metric", "numZSlices"));
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
        analysis.setSuppressDialogs(true);

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
                        {"M1_LH", "10"}, {"M1_RH", "12"},
                        {"M2_LH", "11"}, {"M2_RH", "13"},
                        {"M3_LH", "12"}, {"M3_RH", "14"}
                });

        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        for (String animal : new String[]{"M1", "M2", "M3"}) {
            conditions.put(animal + "_LH", "LH");
            conditions.put(animal + "_RH", "RH");
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

    @Test
    public void headless_pairedCli_alignsPairsByAnimalIdNotRowOrder() throws Exception {
        File dir = temp.newFolder("paired-row-order");
        File exportDir = aggregationDir(dir);

        writeMasterCsv(new File(exportDir, FlashProjectLayout.MASTER_OBJECTS_FILENAME),
                new String[]{"AnimalName", "SignalMean"},
                new String[][]{
                        {"M2-RH", "20"},
                        {"M1-RH", "10"},
                        {"M3-RH", "30"},
                        {"M1-LH", "11"},
                        {"M2-LH", "22"},
                        {"M3-LH", "33"}
                });

        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        conditions.put("M2-RH", "RH");
        conditions.put("M1-RH", "RH");
        conditions.put("M3-RH", "RH");
        conditions.put("M1-LH", "LH");
        conditions.put("M2-LH", "LH");
        conditions.put("M3-LH", "LH");
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), conditions);

        CLIConfig cli = CLIArgumentParser.parse(
                "dir=[" + dir.getAbsolutePath() + "] stats.paired=hemisphere stats.distribution=parametric");
        assertNotNull(cli);

        StatisticalAnalysis analysis = new StatisticalAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setCliConfig(cli);
        analysis.execute(dir.getAbsolutePath());

        List<Map<String, String>> stats = readCsv(statisticsFile(dir));
        Map<String, String> global = stats.get(0);
        assertEquals("Paired t-test", global.get("Test"));
        assertEquals("Yes", global.get("Paired"));
        assertEquals("3", global.get("TotalNAnimals"));
        assertEquals(-3.464102, csvDouble(global.get("Statistic")), 1e-6);
        assertEquals(2.0, csvDouble(global.get("EffectSize")), 1e-6);
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

    @Test
    public void headless_nestedRowsUseAnimalsAsNAndWriteSuperPlot() throws Exception {
        File dir = temp.newFolder("nested-low-n");
        File exportDir = aggregationDir(dir);

        writeMasterCsv(new File(exportDir, FlashProjectLayout.MASTER_OBJECTS_FILENAME),
                new String[]{"AnimalName", "SignalMean"},
                new String[][]{
                        {"Ctl1-LH", "10"}, {"Ctl1-RH", "14"},
                        {"Ctl2-LH", "12"}, {"Ctl2-RH", "16"},
                        {"Tx1-LH", "30"}, {"Tx1-RH", "34"},
                        {"Tx2-LH", "32"}, {"Tx2-RH", "36"}
                });

        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        conditions.put("Ctl1", "Control");
        conditions.put("Ctl2", "Control");
        conditions.put("Tx1", "Treatment");
        conditions.put("Tx2", "Treatment");
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), conditions);

        StatisticalAnalysis analysis = new StatisticalAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.execute(dir.getAbsolutePath());

        List<Map<String, String>> stats = readCsv(statisticsFile(dir));
        assertEquals("Skipped", stats.get(0).get("Test"));
        assertTrue("Skip reason should report animal n, not nested-row n: " + stats.get(0).get("Notes"),
                stats.get(0).get("Notes").contains("Control n=2"));

        List<Map<String, String>> superPlot = readCsv(superPlotFile(dir));
        assertEquals("One row per animal-metric is expected", 4, superPlot.size());
        Map<String, String> ctl1 = findRow(superPlot, "AnimalName", "Ctl1");
        assertEquals("12.000000", ctl1.get("Value"));
        assertEquals("2", ctl1.get("NestedRowCount"));
        assertEquals("No", ctl1.get("IncludedInTest"));
        assertEquals("Animal", ctl1.get("InferentialUnit"));
    }

    @Test
    public void headless_rowLevelManifestStillCollapsesNestedHemisphereRows() throws Exception {
        File dir = temp.newFolder("nested-row-manifest");
        File exportDir = aggregationDir(dir);

        writeMasterCsv(new File(exportDir, FlashProjectLayout.MASTER_OBJECTS_FILENAME),
                new String[]{"AnimalName", "SignalMean"},
                new String[][]{
                        {"Ctl1-LH", "9"}, {"Ctl1-RH", "11"},
                        {"Ctl2-LH", "11"}, {"Ctl2-RH", "13"},
                        {"Ctl3-LH", "13"}, {"Ctl3-RH", "15"},
                        {"Tx1-LH", "19"}, {"Tx1-RH", "21"},
                        {"Tx2-LH", "21"}, {"Tx2-RH", "23"},
                        {"Tx3-LH", "23"}, {"Tx3-RH", "25"}
                });

        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        for (String animal : new String[]{"Ctl1", "Ctl2", "Ctl3"}) {
            conditions.put(animal + "-LH", "Control");
            conditions.put(animal + "-RH", "Control");
        }
        for (String animal : new String[]{"Tx1", "Tx2", "Tx3"}) {
            conditions.put(animal + "-LH", "Treatment");
            conditions.put(animal + "-RH", "Treatment");
        }
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), conditions);

        StatisticsConfig config = new StatisticsConfig();
        config.distributionMode = StatisticsConfig.DistributionMode.ASSUME_NORMAL;

        StatisticalAnalysis analysis = new StatisticalAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setStatisticsConfig(config);
        analysis.execute(dir.getAbsolutePath());

        Map<String, String> global = readCsv(statisticsFile(dir)).get(0);
        assertEquals("3", global.get("Group1NAnimals"));
        assertEquals("3", global.get("Group2NAnimals"));
        assertEquals("6", global.get("TotalNAnimals"));
        assertEquals(10.0, csvDouble(global.get("EffectSize")), 1e-6);
    }

    @Test
    public void headless_explicitSumAggregationOverridesMetricNameHeuristic() throws Exception {
        File dir = temp.newFolder("explicit-sum-aggregation");
        File exportDir = aggregationDir(dir);

        writeMasterCsv(new File(exportDir, FlashProjectLayout.MASTER_OBJECTS_FILENAME),
                new String[]{"AnimalName", "ObjectsDetected"},
                new String[][]{
                        {"Ctl1-LH", "1"}, {"Ctl1-RH", "2"},
                        {"Ctl2-LH", "2"}, {"Ctl2-RH", "3"},
                        {"Ctl3-LH", "3"}, {"Ctl3-RH", "4"},
                        {"Tx1-LH", "4"}, {"Tx1-RH", "5"},
                        {"Tx2-LH", "7"}, {"Tx2-RH", "8"},
                        {"Tx3-LH", "10"}, {"Tx3-RH", "11"}
                });

        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        conditions.put("Ctl1", "Control");
        conditions.put("Ctl2", "Control");
        conditions.put("Ctl3", "Control");
        conditions.put("Tx1", "Treatment");
        conditions.put("Tx2", "Treatment");
        conditions.put("Tx3", "Treatment");
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), conditions);

        CLIConfig cli = CLIArgumentParser.parse(
                "dir=[" + dir.getAbsolutePath() + "] stats.distribution=parametric "
                        + "stats.sum_metrics=ObjectsDetected");
        assertNotNull(cli);

        StatisticalAnalysis analysis = new StatisticalAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setCliConfig(cli);
        analysis.execute(dir.getAbsolutePath());

        Map<String, String> global = readCsv(statisticsFile(dir)).get(0);
        assertEquals(5.0, csvDouble(global.get("Group1Mean")), 1e-6);
        assertEquals(15.0, csvDouble(global.get("Group2Mean")), 1e-6);
        assertEquals(10.0, csvDouble(global.get("EffectSize")), 1e-6);

        Map<String, String> ctl1 = findRow(readCsv(superPlotFile(dir)),
                "AnimalName", "Ctl1");
        assertEquals("3.000000", ctl1.get("Value"));
        assertEquals("2", ctl1.get("NestedRowCount"));
    }

    @Test
    public void headless_cliAggregationOverridesMergeWithPresetOverrides() throws Exception {
        File dir = temp.newFolder("aggregation-preset-merge");
        File exportDir = aggregationDir(dir);

        writeMasterCsv(new File(exportDir, FlashProjectLayout.MASTER_OBJECTS_FILENAME),
                new String[]{"AnimalName", "ObjectsDetected", "CellCount"},
                new String[][]{
                        {"Ctl1-LH", "1", "1"}, {"Ctl1-RH", "2", "3"},
                        {"Ctl2-LH", "2", "2"}, {"Ctl2-RH", "3", "4"},
                        {"Ctl3-LH", "3", "3"}, {"Ctl3-RH", "4", "5"},
                        {"Tx1-LH", "4", "5"}, {"Tx1-RH", "5", "7"},
                        {"Tx2-LH", "7", "6"}, {"Tx2-RH", "8", "8"},
                        {"Tx3-LH", "10", "7"}, {"Tx3-RH", "11", "9"}
                });

        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        conditions.put("Ctl1", "Control");
        conditions.put("Ctl2", "Control");
        conditions.put("Ctl3", "Control");
        conditions.put("Tx1", "Treatment");
        conditions.put("Tx2", "Treatment");
        conditions.put("Tx3", "Treatment");
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), conditions);

        LinkedHashMap<String, StatisticsConfig.MetricAggregation> presetAggregation =
                new LinkedHashMap<String, StatisticsConfig.MetricAggregation>();
        presetAggregation.put("ObjectsDetected", StatisticsConfig.MetricAggregation.SUM);
        new StatisticsPresetIO(dir).save(new StatisticsPreset(
                "Aggregation Preset",
                "test",
                StatisticsConfig.PairedMode.OFF,
                StatisticsConfig.DistributionMode.AUTO,
                StatisticsConfig.PostHocMethod.BONFERRONI,
                null,
                presetAggregation));

        CLIConfig cli = CLIArgumentParser.parse(
                "dir=[" + dir.getAbsolutePath() + "] stats.preset=[Aggregation Preset] "
                        + "stats.distribution=parametric stats.mean_metrics=cellcount");
        assertNotNull(cli);

        StatisticalAnalysis analysis = new StatisticalAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setCliConfig(cli);
        analysis.execute(dir.getAbsolutePath());

        List<Map<String, String>> stats = readCsv(statisticsFile(dir));
        Map<String, String> objects = findRow(stats, "Metric", "ObjectsDetected");
        Map<String, String> cellCount = findRow(stats, "Metric", "CellCount");
        assertEquals(10.0, csvDouble(objects.get("EffectSize")), 1e-6);
        assertEquals(4.0, csvDouble(cellCount.get("EffectSize")), 1e-6);
    }

    @Test
    public void headless_rowLevelRegionManifestCollapsesRepeatedRegionSuffixes() throws Exception {
        File dir = temp.newFolder("nested-region-manifest");
        File exportDir = aggregationDir(dir);

        writeMasterCsv(new File(exportDir, FlashProjectLayout.MASTER_OBJECTS_FILENAME),
                new String[]{"AnimalName", "SignalMean"},
                new String[][]{
                        {"Ctl1-Cortex", "10"}, {"Ctl1-Hippocampus", "14"},
                        {"Ctl2-Cortex", "12"}, {"Ctl2-Hippocampus", "16"},
                        {"Ctl3-Cortex", "14"}, {"Ctl3-Hippocampus", "18"},
                        {"Tx1-Cortex", "30"}, {"Tx1-Hippocampus", "34"},
                        {"Tx2-Cortex", "32"}, {"Tx2-Hippocampus", "36"},
                        {"Tx3-Cortex", "34"}, {"Tx3-Hippocampus", "38"}
                });

        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        for (String animal : new String[]{"Ctl1", "Ctl2", "Ctl3"}) {
            conditions.put(animal + "-Cortex", "Control");
            conditions.put(animal + "-Hippocampus", "Control");
        }
        for (String animal : new String[]{"Tx1", "Tx2", "Tx3"}) {
            conditions.put(animal + "-Cortex", "Treatment");
            conditions.put(animal + "-Hippocampus", "Treatment");
        }
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), conditions);

        StatisticsConfig config = new StatisticsConfig();
        config.distributionMode = StatisticsConfig.DistributionMode.ASSUME_NORMAL;

        StatisticalAnalysis analysis = new StatisticalAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setStatisticsConfig(config);
        analysis.execute(dir.getAbsolutePath());

        Map<String, String> global = readCsv(statisticsFile(dir)).get(0);
        assertEquals("3", global.get("Group1NAnimals"));
        assertEquals("3", global.get("Group2NAnimals"));
        assertEquals("6", global.get("TotalNAnimals"));
        assertEquals(20.0, csvDouble(global.get("EffectSize")), 1e-6);

        List<Map<String, String>> superPlot = readCsv(superPlotFile(dir));
        assertEquals(6, superPlot.size());
        Map<String, String> ctl1 = findRow(superPlot, "AnimalName", "Ctl1");
        assertEquals("12.000000", ctl1.get("Value"));
        assertEquals("2", ctl1.get("NestedRowCount"));
    }

    @Test
    public void headless_hyphenatedAnimalIdsAreNotCollapsedByNumericSuffix() throws Exception {
        File dir = temp.newFolder("hyphen-animals");
        File exportDir = aggregationDir(dir);

        writeMasterCsv(new File(exportDir, FlashProjectLayout.MASTER_OBJECTS_FILENAME),
                new String[]{"AnimalName", "SignalMean"},
                new String[][]{
                        {"Mouse-1", "10"}, {"Mouse-2", "12"}, {"Mouse-3", "14"},
                        {"Rat-1", "20"}, {"Rat-2", "22"}, {"Rat-3", "24"}
                });

        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        conditions.put("Mouse-1", "Control");
        conditions.put("Mouse-2", "Control");
        conditions.put("Mouse-3", "Control");
        conditions.put("Rat-1", "Treatment");
        conditions.put("Rat-2", "Treatment");
        conditions.put("Rat-3", "Treatment");
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), conditions);

        StatisticsConfig config = new StatisticsConfig();
        config.distributionMode = StatisticsConfig.DistributionMode.ASSUME_NORMAL;

        StatisticalAnalysis analysis = new StatisticalAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setStatisticsConfig(config);
        analysis.execute(dir.getAbsolutePath());

        Map<String, String> global = readCsv(statisticsFile(dir)).get(0);
        assertEquals("3", global.get("Group1NAnimals"));
        assertEquals("3", global.get("Group2NAnimals"));
        assertEquals(6, readCsv(superPlotFile(dir)).size());
    }

    @Test
    public void headless_hyphenatedAnimalIdsAreNotCollapsedByRepeatedNumericSuffix() throws Exception {
        File dir = temp.newFolder("hyphen-animals-two-digit");
        File exportDir = aggregationDir(dir);

        writeMasterCsv(new File(exportDir, FlashProjectLayout.MASTER_OBJECTS_FILENAME),
                new String[]{"AnimalName", "SignalMean"},
                new String[][]{
                        {"Mouse-01", "10"}, {"Mouse-02", "12"}, {"Mouse-03", "14"},
                        {"Rat-01", "20"}, {"Rat-02", "22"}, {"Rat-03", "24"}
                });

        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        conditions.put("Mouse-01", "Control");
        conditions.put("Mouse-02", "Control");
        conditions.put("Mouse-03", "Control");
        conditions.put("Rat-01", "Treatment");
        conditions.put("Rat-02", "Treatment");
        conditions.put("Rat-03", "Treatment");
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), conditions);

        StatisticsConfig config = new StatisticsConfig();
        config.distributionMode = StatisticsConfig.DistributionMode.ASSUME_NORMAL;

        StatisticalAnalysis analysis = new StatisticalAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setStatisticsConfig(config);
        analysis.execute(dir.getAbsolutePath());

        Map<String, String> global = readCsv(statisticsFile(dir)).get(0);
        assertEquals("3", global.get("Group1NAnimals"));
        assertEquals("3", global.get("Group2NAnimals"));
        assertEquals(6, readCsv(superPlotFile(dir)).size());
    }

    @Test
    public void headless_pairedRegionAlignsRepeatedRegionSuffixesByAnimalId() throws Exception {
        File dir = temp.newFolder("paired-region-row-order");
        File exportDir = aggregationDir(dir);

        writeMasterCsv(new File(exportDir, FlashProjectLayout.MASTER_OBJECTS_FILENAME),
                new String[]{"AnimalName", "SignalMean"},
                new String[][]{
                        {"M2-Hippocampus", "20"},
                        {"M1-Hippocampus", "10"},
                        {"M3-Hippocampus", "30"},
                        {"M1-Cortex", "11"},
                        {"M2-Cortex", "22"},
                        {"M3-Cortex", "33"}
                });

        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        conditions.put("M2-Hippocampus", "Hippocampus");
        conditions.put("M1-Hippocampus", "Hippocampus");
        conditions.put("M3-Hippocampus", "Hippocampus");
        conditions.put("M1-Cortex", "Cortex");
        conditions.put("M2-Cortex", "Cortex");
        conditions.put("M3-Cortex", "Cortex");
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), conditions);

        CLIConfig cli = CLIArgumentParser.parse(
                "dir=[" + dir.getAbsolutePath() + "] stats.paired=region stats.distribution=parametric");
        assertNotNull(cli);

        StatisticalAnalysis analysis = new StatisticalAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setCliConfig(cli);
        analysis.execute(dir.getAbsolutePath());

        Map<String, String> global = readCsv(statisticsFile(dir)).get(0);
        assertEquals("Paired t-test", global.get("Test"));
        assertEquals("Yes", global.get("Paired"));
        assertEquals("3", global.get("TotalNAnimals"));
        assertEquals(-3.464102, csvDouble(global.get("Statistic")), 1e-6);
        assertEquals(2.0, csvDouble(global.get("EffectSize")), 1e-6);

        List<Map<String, String>> superPlot = readCsv(superPlotFile(dir));
        assertEquals(6, superPlot.size());
        assertEquals(2, countRows(superPlot, "AnimalName", "M1"));
        Map<String, String> m1Hippocampus = findRow(superPlot,
                "AnimalName", "M1", "Condition", "Hippocampus");
        Map<String, String> m1Cortex = findRow(superPlot,
                "AnimalName", "M1", "Condition", "Cortex");
        assertEquals("10.000000", m1Hippocampus.get("Value"));
        assertEquals("11.000000", m1Cortex.get("Value"));
    }

    @Test
    public void headless_pairedRegionMarksIncompleteSubjectsExcludedInSuperPlot() throws Exception {
        File dir = temp.newFolder("paired-region-incomplete");
        File exportDir = aggregationDir(dir);

        writeMasterCsv(new File(exportDir, FlashProjectLayout.MASTER_OBJECTS_FILENAME),
                new String[]{"AnimalName", "SignalMean"},
                new String[][]{
                        {"M2-Hippocampus", "20"},
                        {"M1-Hippocampus", "10"},
                        {"M3-Hippocampus", "30"},
                        {"M4-Hippocampus", "40"},
                        {"M1-Cortex", "11"},
                        {"M2-Cortex", "22"},
                        {"M3-Cortex", "33"}
                });

        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        conditions.put("M2-Hippocampus", "Hippocampus");
        conditions.put("M1-Hippocampus", "Hippocampus");
        conditions.put("M3-Hippocampus", "Hippocampus");
        conditions.put("M4-Hippocampus", "Hippocampus");
        conditions.put("M1-Cortex", "Cortex");
        conditions.put("M2-Cortex", "Cortex");
        conditions.put("M3-Cortex", "Cortex");
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), conditions);

        CLIConfig cli = CLIArgumentParser.parse(
                "dir=[" + dir.getAbsolutePath() + "] stats.paired=region stats.distribution=parametric");
        assertNotNull(cli);

        StatisticalAnalysis analysis = new StatisticalAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setCliConfig(cli);
        analysis.execute(dir.getAbsolutePath());

        Map<String, String> global = readCsv(statisticsFile(dir)).get(0);
        assertEquals("Paired t-test", global.get("Test"));
        assertEquals("3", global.get("TotalNAnimals"));

        List<Map<String, String>> superPlot = readCsv(superPlotFile(dir));
        assertEquals(7, superPlot.size());
        assertEquals("Yes", findRow(superPlot,
                "AnimalName", "M1", "Condition", "Hippocampus").get("IncludedInTest"));
        assertEquals("Yes", findRow(superPlot,
                "AnimalName", "M1", "Condition", "Cortex").get("IncludedInTest"));
        assertEquals("No", findRow(superPlot,
                "AnimalName", "M4", "Condition", "Hippocampus").get("IncludedInTest"));
    }

    @Test
    public void headless_pairedHemisphereSumsDuplicateCountRowsBySubjectCondition() throws Exception {
        File dir = temp.newFolder("paired-duplicate-counts");
        File exportDir = aggregationDir(dir);

        writeMasterCsv(new File(exportDir, FlashProjectLayout.MASTER_OBJECTS_FILENAME),
                new String[]{"AnimalName", "CellCount"},
                new String[][]{
                        {"M1_LH_A", "1"}, {"M1_LH_B", "2"},
                        {"M1_RH_A", "4"}, {"M1_RH_B", "5"},
                        {"M2_LH_A", "2"}, {"M2_LH_B", "3"},
                        {"M2_RH_A", "7"}, {"M2_RH_B", "8"},
                        {"M3_LH_A", "3"}, {"M3_LH_B", "4"},
                        {"M3_RH_A", "10"}, {"M3_RH_B", "11"}
                });

        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        for (String row : new String[]{"M1_LH_A", "M1_LH_B",
                "M2_LH_A", "M2_LH_B", "M3_LH_A", "M3_LH_B"}) {
            conditions.put(row, "LH");
        }
        for (String row : new String[]{"M1_RH_A", "M1_RH_B",
                "M2_RH_A", "M2_RH_B", "M3_RH_A", "M3_RH_B"}) {
            conditions.put(row, "RH");
        }
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), conditions);

        CLIConfig cli = CLIArgumentParser.parse(
                "dir=[" + dir.getAbsolutePath() + "] stats.paired=hemisphere stats.distribution=parametric");
        assertNotNull(cli);

        StatisticalAnalysis analysis = new StatisticalAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setCliConfig(cli);
        analysis.execute(dir.getAbsolutePath());

        Map<String, String> global = readCsv(statisticsFile(dir)).get(0);
        assertEquals("Paired t-test", global.get("Test"));
        assertEquals("3", global.get("TotalNAnimals"));
        assertEquals(5.0, csvDouble(global.get("Group1Mean")), 1e-6);
        assertEquals(15.0, csvDouble(global.get("Group2Mean")), 1e-6);
        assertEquals(10.0, csvDouble(global.get("EffectSize")), 1e-6);

        List<Map<String, String>> superPlot = readCsv(superPlotFile(dir));
        Map<String, String> m1Lh = findRow(superPlot, "AnimalName", "M1", "Condition", "LH");
        Map<String, String> m1Rh = findRow(superPlot, "AnimalName", "M1", "Condition", "RH");
        assertEquals("3.000000", m1Lh.get("Value"));
        assertEquals("9.000000", m1Rh.get("Value"));
        assertEquals("2", m1Lh.get("NestedRowCount"));
        assertEquals("2", m1Rh.get("NestedRowCount"));
    }

    @Test
    public void headless_pairedRegionDoesNotPairSingleLetterHyphenAnimalIds() throws Exception {
        File dir = temp.newFolder("paired-region-animal-ids");
        File exportDir = aggregationDir(dir);

        writeMasterCsv(new File(exportDir, FlashProjectLayout.MASTER_OBJECTS_FILENAME),
                new String[]{"AnimalName", "SignalMean"},
                new String[][]{
                        {"Mouse-A", "10"}, {"Rat-A", "30"}, {"Hamster-A", "50"},
                        {"Mouse-B", "20"}, {"Rat-B", "40"}, {"Hamster-B", "60"}
                });

        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        conditions.put("Mouse-A", "RegionA");
        conditions.put("Rat-A", "RegionA");
        conditions.put("Hamster-A", "RegionA");
        conditions.put("Mouse-B", "RegionB");
        conditions.put("Rat-B", "RegionB");
        conditions.put("Hamster-B", "RegionB");
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), conditions);

        CLIConfig cli = CLIArgumentParser.parse(
                "dir=[" + dir.getAbsolutePath() + "] stats.paired=region stats.distribution=parametric");
        assertNotNull(cli);

        StatisticalAnalysis analysis = new StatisticalAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setCliConfig(cli);
        analysis.execute(dir.getAbsolutePath());

        Map<String, String> global = readCsv(statisticsFile(dir)).get(0);
        assertEquals("Skipped", global.get("Test"));
        assertTrue(global.get("Notes").contains("RegionA n=0"));
        assertTrue(global.get("Notes").contains("RegionB n=0"));
    }

    @Test
    public void headless_reportsAnimalNMeanDifferenceAndCi() throws Exception {
        File dir = temp.newFolder("nested-effect");
        File exportDir = aggregationDir(dir);

        writeMasterCsv(new File(exportDir, FlashProjectLayout.MASTER_OBJECTS_FILENAME),
                new String[]{"AnimalName", "SignalMean"},
                new String[][]{
                        {"Ctl1-LH", "9"}, {"Ctl1-RH", "11"},
                        {"Ctl2-LH", "11"}, {"Ctl2-RH", "13"},
                        {"Ctl3-LH", "13"}, {"Ctl3-RH", "15"},
                        {"Tx1-LH", "19"}, {"Tx1-RH", "21"},
                        {"Tx2-LH", "21"}, {"Tx2-RH", "23"},
                        {"Tx3-LH", "23"}, {"Tx3-RH", "25"}
                });

        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        conditions.put("Ctl1", "Control");
        conditions.put("Ctl2", "Control");
        conditions.put("Ctl3", "Control");
        conditions.put("Tx1", "Treatment");
        conditions.put("Tx2", "Treatment");
        conditions.put("Tx3", "Treatment");
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), conditions);

        StatisticsConfig config = new StatisticsConfig();
        config.distributionMode = StatisticsConfig.DistributionMode.ASSUME_NORMAL;

        StatisticalAnalysis analysis = new StatisticalAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setStatisticsConfig(config);
        analysis.execute(dir.getAbsolutePath());

        List<Map<String, String>> stats = readCsv(statisticsFile(dir));
        Map<String, String> global = stats.get(0);
        assertEquals("Animal", global.get("InferentialUnit"));
        assertEquals("3", global.get("Group1NAnimals"));
        assertEquals("3", global.get("Group2NAnimals"));
        assertEquals("6", global.get("TotalNAnimals"));
        assertEquals("MeanDifference_Group2MinusGroup1", global.get("EffectSizeType"));
        assertEquals(10.0, csvDouble(global.get("EffectSize")), 1e-6);
        assertFalse("CI low should be written", global.get("EffectCI95Low").isEmpty());
        assertFalse("CI high should be written", global.get("EffectCI95High").isEmpty());
    }

    private static File aggregationDir(File root) {
        File dir = FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesProjectSummaryWriteDir();
        assertTrue(dir.isDirectory() || dir.mkdirs());
        return dir;
    }

    private static File statisticsFile(File root) {
        return FlashProjectLayout.forDirectory(root.getAbsolutePath())
                .projectSummaryWriteFile(FlashProjectLayout.STATISTICS_FILENAME);
    }

    private static File superPlotFile(File root) {
        return FlashProjectLayout.forDirectory(root.getAbsolutePath())
                .projectSummaryWriteFile(StatisticalAnalysis.SUPERPLOT_FILENAME);
    }

    private static List<Map<String, String>> readCsv(File file) throws Exception {
        List<Map<String, String>> rows = new ArrayList<Map<String, String>>();
        CsvSupport.RecordReader reader = CsvSupport.openRecordReader(file);
        try {
            CsvSupport.Record headerRecord = reader.readRecord();
            assertNotNull("CSV header missing for " + file, headerRecord);
            String[] header = CsvSupport.parseRecord(headerRecord.text);
            CsvSupport.Record record;
            while ((record = reader.readRecord()) != null) {
                if (CsvSupport.isBlankRecord(record.text)) continue;
                String[] values = CsvSupport.parseRecord(record.text);
                Map<String, String> row = new HashMap<String, String>();
                for (int i = 0; i < header.length; i++) {
                    row.put(header[i], i < values.length ? values[i] : "");
                }
                rows.add(row);
            }
        } finally {
            reader.close();
        }
        return rows;
    }

    private static Map<String, String> findRow(List<Map<String, String>> rows,
                                               String column,
                                               String value) {
        for (Map<String, String> row : rows) {
            if (value.equals(row.get(column))) {
                return row;
            }
        }
        fail("No row with " + column + "=" + value + " in " + rows);
        return null;
    }

    private static Map<String, String> findRow(List<Map<String, String>> rows,
                                               String column1,
                                               String value1,
                                               String column2,
                                               String value2) {
        for (Map<String, String> row : rows) {
            if (value1.equals(row.get(column1)) && value2.equals(row.get(column2))) {
                return row;
            }
        }
        fail("No row with " + column1 + "=" + value1
                + " and " + column2 + "=" + value2 + " in " + rows);
        return null;
    }

    private static int countRows(List<Map<String, String>> rows,
                                 String column,
                                 String value) {
        int count = 0;
        for (Map<String, String> row : rows) {
            if (value.equals(row.get(column))) count++;
        }
        return count;
    }

    private static double csvDouble(String value) {
        if (value != null && value.startsWith("'")) {
            value = value.substring(1);
        }
        return Double.parseDouble(value);
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
