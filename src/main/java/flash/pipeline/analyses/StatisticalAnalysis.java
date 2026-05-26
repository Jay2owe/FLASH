package flash.pipeline.analyses;

import ij.IJ;
import flash.pipeline.analyses.wizard.AggregationConfig;
import flash.pipeline.analyses.wizard.StatisticsPreset;
import flash.pipeline.analyses.wizard.StatisticsPresetIO;
import flash.pipeline.analyses.wizard.StatisticsWizard;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.io.CsvSupport;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.IoUtils;
import flash.pipeline.stats.MetricStatisticsEngine;
import flash.pipeline.stats.StatisticRow;
import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.wizard.ConditionManifestPanel;
import flash.pipeline.ui.wizard.SetupHelperButton;
import flash.pipeline.ui.wizard.WizardFlow;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Performs statistical testing on the aggregated master data produced by
 * {@link MasterAggregationAnalysis}.
 * <p>
 * For each numeric metric column the analysis:
 * <ol>
 *   <li>Tests normality per condition group (D'Agostino-Pearson omnibus)</li>
 *   <li>Runs a global test (ANOVA / Welch's t / Kruskal-Wallis / Mann-Whitney)</li>
 *   <li>Performs pairwise post-hoc comparisons with Bonferroni correction</li>
 * </ol>
 * Results are written to
 * {@code FLASH/Results/Tables/Project Summary/Statistics.csv}.
 * <p>
 * Parametric tests (Welch's t, ANOVA) are backed by Apache Commons Math.
 * Compatible with Java 8.
 */
public class StatisticalAnalysis implements Analysis {

    private boolean headless = false;
    private boolean suppressDialogs = false;
    private StatisticsConfig statisticsConfig = new StatisticsConfig();
    private CLIConfig cliConfig = null;

    @Override
    public void setHeadless(boolean headless) {
        this.headless = headless;
    }

    @Override
    public void setSuppressDialogs(boolean suppress) {
        this.suppressDialogs = suppress;
    }

    /**
     * Sets the routing configuration for tests. {@code null} resets to defaults
     * (unpaired, automatic distribution detection, Bonferroni post-hoc).
     */
    public void setStatisticsConfig(StatisticsConfig config) {
        this.statisticsConfig = config == null ? new StatisticsConfig() : config;
    }

    public StatisticsConfig getStatisticsConfig() {
        return statisticsConfig;
    }

    @Override
    public void setCliConfig(CLIConfig config) {
        this.cliConfig = config;
        if (config == null || config.getStats() == null) {
            return;
        }
        CLIConfig.StatsConfig src = config.getStats();
        if (!src.hasConfiguration()) {
            return;
        }
        StatisticsConfig resolved = resolveCliStatisticsConfig(config, src);
        if (resolved != null) {
            this.statisticsConfig = resolved;
        }
    }

    private static StatisticsConfig resolveCliStatisticsConfig(CLIConfig config,
                                                               CLIConfig.StatsConfig src) {
        StatisticsConfig target = new StatisticsConfig();
        String presetName = src.getPresetName();
        if (presetName != null && !presetName.trim().isEmpty()) {
            try {
                File projectRoot = config.getDirectory() == null
                        ? new File(System.getProperty("user.dir", "."))
                        : new File(config.getDirectory());
                StatisticsPreset preset = new StatisticsPresetIO(projectRoot)
                        .load(presetName.trim());
                target = preset.toConfig();
            } catch (IOException e) {
                IJ.log("[CLI] Warning: Could not load stats.preset '"
                        + presetName + "': " + e.getMessage());
            }
        }
        if (src.getPairedMode() != null) {
            target.pairedMode = src.getPairedMode();
        }
        if (src.getDistMode() != null) {
            target.distributionMode = src.getDistMode();
        }
        if (src.getPostHoc() != null) {
            target.postHocMethod = src.getPostHoc();
        }
        if (src.getMetrics() != null && !src.getMetrics().isEmpty()) {
            target.metricFilter = new ArrayList<String>(src.getMetrics());
        }
        return target;
    }

    // ================================================================
    //  Analysis interface
    // ================================================================

    @Override
    public void execute(String directory) {
        IJ.log("=== Statistical Analysis ===");

        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);

        // 1. Load master CSVs
        File objectsCsv = existingProjectSummaryFile(layout, FlashProjectLayout.MASTER_OBJECTS_FILENAME);
        File intensitiesCsv = existingProjectSummaryFile(layout, FlashProjectLayout.MASTER_INTENSITIES_FILENAME);

        if (objectsCsv == null && intensitiesCsv == null) {
            notifyUser("Statistical Analysis",
                    "No master CSV files found in FLASH/Results/Tables/Project Summary/.\n"
                            + "Run Master Data Aggregation first.");
            return;
        }

        CsvData objData = objectsCsv != null ? parseMasterCsv(objectsCsv) : null;
        CsvData intData = intensitiesCsv != null ? parseMasterCsv(intensitiesCsv) : null;

        // Collect all unique animal names (preserving order)
        Set<String> allAnimals = new LinkedHashSet<String>();
        if (objData != null) allAnimals.addAll(objData.animals);
        if (intData != null) allAnimals.addAll(intData.animals);

        if (allAnimals.isEmpty()) {
            notifyUser("Statistical Analysis", "No animal data found in master CSVs.");
            return;
        }

        // 2. Resolve condition assignments — interactive or unattended
        Map<String, String> animalToCondition = resolveConditionAssignments(directory, allAnimals);
        if (animalToCondition == null) {
            IJ.log("Statistical analysis cancelled by user.");
            return;
        }

        // Build ordered condition list
        List<String> conditionOrder = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        for (String animal : allAnimals) {
            String cond = animalToCondition.get(animal);
            if (cond != null && !cond.isEmpty() && seen.add(cond)) {
                conditionOrder.add(cond);
            }
        }

        if (conditionOrder.size() < 2) {
            String msg = "At least 2 conditions are required for statistical testing. Found: "
                    + conditionOrder.size() + ".";
            if (headless || suppressDialogs) {
                msg += " Edit FLASH/Results/Tables/Project Summary/Conditions.csv to assign conditions.";
            }
            notifyUser("Statistical Analysis", msg);
            return;
        }

        // 3. Merge data from both CSVs
        LinkedHashMap<String, Map<String, Double>> mergedData =
                new LinkedHashMap<String, Map<String, Double>>();
        List<String> metricColumns = new ArrayList<String>();

        if (objData != null) {
            for (String col : objData.columns) {
                if (isMetricColumn(col) && !metricColumns.contains(col)) {
                    metricColumns.add(col);
                }
            }
        }
        if (intData != null) {
            for (String col : intData.columns) {
                if (isMetricColumn(col) && !metricColumns.contains(col)) {
                    metricColumns.add(col);
                }
            }
        }

        for (String animal : allAnimals) {
            Map<String, Double> row = new LinkedHashMap<String, Double>();
            if (objData != null && objData.data.containsKey(animal)) {
                row.putAll(objData.data.get(animal));
            }
            if (intData != null && intData.data.containsKey(animal)) {
                row.putAll(intData.data.get(animal));
            }
            mergedData.put(animal, row);
        }

        if (statisticsConfig != null
                && statisticsConfig.metricFilter != null
                && !statisticsConfig.metricFilter.isEmpty()) {
            Set<String> allowed = new LinkedHashSet<String>(statisticsConfig.metricFilter);
            List<String> filtered = new ArrayList<String>();
            for (String col : metricColumns) {
                if (allowed.contains(col)) filtered.add(col);
            }
            if (filtered.isEmpty()) {
                IJ.log("Warning: stats.metrics filter matched no columns; testing all metrics.");
            } else {
                metricColumns = filtered;
            }
        }

        // 4. Run statistical tests for each metric
        List<StatisticRow> results = new ArrayList<StatisticRow>();
        int tested = 0;
        int skipped = 0;

        for (String metric : metricColumns) {
            // Build per-condition value arrays
            LinkedHashMap<String, List<Double>> groups =
                    new LinkedHashMap<String, List<Double>>();
            for (String cond : conditionOrder) {
                groups.put(cond, new ArrayList<Double>());
            }

            for (String animal : allAnimals) {
                String cond = animalToCondition.get(animal);
                if (cond == null || !groups.containsKey(cond)) continue;
                Map<String, Double> row = mergedData.get(animal);
                if (row == null) continue;
                Double val = row.get(metric);
                if (val != null && !Double.isNaN(val) && !Double.isInfinite(val)) {
                    groups.get(cond).add(val);
                }
            }

            // Check minimum group size (n >= 3 for every group)
            boolean tooSmall = false;
            StringBuilder skipReason = new StringBuilder();
            for (Map.Entry<String, List<Double>> ge : groups.entrySet()) {
                if (ge.getValue().size() < 3) {
                    tooSmall = true;
                    if (skipReason.length() > 0) skipReason.append("; ");
                    skipReason.append(ge.getKey()).append(" n=").append(ge.getValue().size());
                }
            }
            if (tooSmall) {
                skipped++;
                results.add(MetricStatisticsEngine.skippedRow(metric, skipReason.toString()));
                continue;
            }

            tested++;
            try {
                results.addAll(MetricStatisticsEngine.analyseMetric(
                        metric, conditionOrder, groups, statisticsConfig));
            } catch (IllegalArgumentException pairingMismatch) {
                tested--;
                skipped++;
                results.add(MetricStatisticsEngine.skippedRow(metric,
                        "paired pairs unequal: " + pairingMismatch.getMessage()));
            }
        }

        // 5. Write output CSV
        File outFile = layout.projectSummaryWriteFile(FlashProjectLayout.STATISTICS_FILENAME);
        writeStatisticsCsv(outFile, results);

        IJ.log("Statistical analysis complete: " + tested + " metrics tested, "
                + skipped + " skipped (insufficient group size).");
        if (!headless && !suppressDialogs) {
            IJ.showMessage("Statistical Analysis",
                    "Complete.\n" + tested + " metrics tested.\n"
                    + "Saved: " + outFile.getName());
        }
    }

    // (Statistical computation delegated to MetricStatisticsEngine)

    // ================================================================
    //  Condition resolution — interactive or unattended
    // ================================================================

    /**
     * Resolves condition assignments. In headless/suppressDialogs mode the
     * manifest (or auto-detection) is used directly. In interactive GUI mode
     * the editable table dialog is shown, and accepted edits are persisted
     * back to {@code Conditions.csv} for future CLI runs.
     */
    Map<String, String> resolveConditionAssignments(String directory, Set<String> animals) {
        Map<String, String> resolved = ConditionManifestIO.resolveAssignments(directory, animals);

        if (headless || suppressDialogs) {
            IJ.log("Using " + (ConditionManifestIO.getExistingFile(directory) != null
                    ? "manifest" : "auto-detected") + " condition assignments (unattended mode).");
            return resolved;
        }

        return showConditionDialog(directory, animals, resolved);
    }

    private Map<String, String> showConditionDialog(final String directory,
                                                     final Set<String> animals,
                                                     final Map<String, String> prefill) {
        if (GraphicsEnvironment.isHeadless()) {
            return prefill;
        }
        final ConditionManifestPanel manifest = new ConditionManifestPanel(animals, prefill);

        PipelineDialog pd = new PipelineDialog("Statistical Analysis \u2014 Condition Assignment", PipelineDialog.Phase.EXPORT);
        pd.addComponent(buildStatisticsHelperRow(pd, directory, manifest));
        pd.addComponent(manifest.getComponent());

        if (!pd.showDialog()) return null;

        LinkedHashMap<String, String> assignments = manifest.collectAssignments();
        try {
            ConditionManifestIO.saveAssignments(directory, assignments);
        } catch (Exception e) {
            IJ.log("Warning: could not save condition assignments: " + e.getMessage());
        }
        return assignments;
    }

    private JComponent buildStatisticsHelperRow(final PipelineDialog parentDialog,
                                                final String directory,
                                                final ConditionManifestPanel manifest) {
        final JComboBox<String> presetCombo =
                new JComboBox<String>(listStatisticsPresetNames(directory));
        presetCombo.setMaximumSize(new Dimension(260, 24));
        presetCombo.setToolTipText(
                "Apply a saved Statistics preset to populate paired/distribution/post-hoc choices.");

        final JButton savePreset = new JButton("Save as preset...");
        flash.pipeline.ui.FlashIcons.apply(savePreset, flash.pipeline.ui.FlashIcons.save());
        savePreset.setToolTipText("Save the current statistics configuration as a named preset.");
        savePreset.addActionListener(e -> handleSaveAsPreset(directory, presetCombo));

        SetupHelperButton.WizardLauncher launcher = new SetupHelperButton.WizardLauncher() {
            @Override
            public void run() {
                parentDialog.runChildWorkflow(new Runnable() {
                    @Override public void run() {
                        runStatisticsHelper(directory, manifest);
                    }
                });
            }
        };
        JPanel row = SetupHelperButton.createHeaderRow(
                "Statistics", presetCombo, savePreset, launcher);
        applyHelperButtonTooltip(row,
                "Configures hypothesis tests; takes effect when you click OK on this dialog.");

        presetCombo.addActionListener(e -> {
            Object selected = presetCombo.getSelectedItem();
            if (selected != null && !"(choose preset)".equals(String.valueOf(selected))) {
                applyPresetByName(directory, String.valueOf(selected));
            }
        });
        return row;
    }

    private static void applyHelperButtonTooltip(JComponent root, String tooltip) {
        if (root == null) return;
        Component[] children = root.getComponents();
        for (Component child : children) {
            if (child instanceof JButton) {
                JButton button = (JButton) child;
                String text = button.getText();
                if (text != null && text.contains("Helper")) {
                    button.setToolTipText(tooltip);
                }
            }
            if (child instanceof JComponent) {
                applyHelperButtonTooltip((JComponent) child, tooltip);
            }
        }
    }

    private String[] listStatisticsPresetNames(String directory) {
        List<String> labels = new ArrayList<String>();
        labels.add("(choose preset)");
        try {
            List<StatisticsPreset> presets =
                    new StatisticsPresetIO(new File(directory)).listAll();
            for (StatisticsPreset preset : presets) {
                labels.add(preset.getName());
            }
        } catch (IOException e) {
            IJ.log("WARNING: Could not list Statistics presets: " + e.getMessage());
        }
        return labels.toArray(new String[labels.size()]);
    }

    private void runStatisticsHelper(String directory, ConditionManifestPanel manifest) {
        if (headless || suppressDialogs) return;
        try {
            LinkedHashMap<String, String> currentAssignments = manifest.collectAssignments();
            List<Integer> groupSizes = computeGroupSizes(currentAssignments);
            int conditionCount = countConditions(currentAssignments);
            List<String> availableMetrics = readAvailableMetricColumns(directory);

            StatisticsWizard wizard = new StatisticsWizard(
                    WizardFlow.MainPanelBinding.NULL,
                    new AggregationConfig(),
                    availableMetrics,
                    conditionCount,
                    groupSizes,
                    false);
            wizard.run();
            if (!wizard.wasFinished()) {
                return;
            }
            StatisticsConfig derived = wizard.deriveCurrentConfig();
            if (derived != null) {
                this.statisticsConfig = derived;
                logHelperApplied("wizard", derived);
            }
        } catch (Exception e) {
            IJ.handleException(e);
        }
    }

    private void applyPresetByName(String directory, String presetName) {
        try {
            StatisticsPreset preset = new StatisticsPresetIO(new File(directory)).load(presetName);
            this.statisticsConfig = preset.toConfig();
            logHelperApplied("preset '" + presetName + "'", this.statisticsConfig);
        } catch (IOException e) {
            IJ.showMessage("Statistical Analysis",
                    "Could not load Statistics preset '" + presetName + "': " + e.getMessage());
        }
    }

    private void handleSaveAsPreset(String directory, JComboBox<String> presetCombo) {
        if (headless || suppressDialogs) return;
        String defaultName = "My Statistics Preset";
        String name = JOptionPane.showInputDialog(null,
                "Preset name:",
                "Save Statistics Preset",
                JOptionPane.PLAIN_MESSAGE);
        if (name == null) return;
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            trimmed = defaultName;
        }
        StatisticsConfig cfg = this.statisticsConfig == null
                ? new StatisticsConfig() : this.statisticsConfig;
        StatisticsPreset preset = new StatisticsPreset(
                trimmed,
                "Saved from Statistical Analysis dialog",
                cfg.pairedMode,
                cfg.distributionMode,
                cfg.postHocMethod,
                cfg.metricFilter);
        try {
            new StatisticsPresetIO(new File(directory)).save(preset);
            IJ.log("Saved Statistics preset: " + trimmed);
            String[] refreshed = listStatisticsPresetNames(directory);
            presetCombo.removeAllItems();
            for (String label : refreshed) {
                presetCombo.addItem(label);
            }
            presetCombo.setSelectedItem(trimmed);
        } catch (IOException e) {
            IJ.showMessage("Statistical Analysis",
                    "Could not save preset: " + e.getMessage());
        }
    }

    private void logHelperApplied(String source, StatisticsConfig cfg) {
        StringBuilder sb = new StringBuilder();
        sb.append("Helper applied (").append(source).append("): paired=")
          .append(cfg.pairedMode == null ? "OFF" : cfg.pairedMode.name())
          .append(", distribution=")
          .append(cfg.distributionMode == null ? "AUTO" : cfg.distributionMode.name())
          .append(", posthoc=")
          .append(cfg.postHocMethod == null ? "BONFERRONI" : cfg.postHocMethod.name());
        if (cfg.metricFilter != null && !cfg.metricFilter.isEmpty()) {
            sb.append(", metrics=").append(cfg.metricFilter);
        }
        IJ.log(sb.toString());
    }

    private static int countConditions(Map<String, String> assignments) {
        if (assignments == null) return 0;
        Set<String> distinct = new LinkedHashSet<String>();
        for (String cond : assignments.values()) {
            if (cond != null && !cond.trim().isEmpty()) distinct.add(cond.trim());
        }
        return distinct.size();
    }

    private static List<Integer> computeGroupSizes(Map<String, String> assignments) {
        if (assignments == null) return new ArrayList<Integer>();
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (String cond : assignments.values()) {
            if (cond == null || cond.trim().isEmpty()) continue;
            String key = cond.trim();
            Integer current = counts.get(key);
            counts.put(key, Integer.valueOf(current == null ? 1 : current.intValue() + 1));
        }
        return new ArrayList<Integer>(counts.values());
    }

    private static List<String> readAvailableMetricColumns(String directory) {
        List<String> out = new ArrayList<String>();
        File csv = existingProjectSummaryFile(FlashProjectLayout.forDirectory(directory),
                FlashProjectLayout.MASTER_OBJECTS_FILENAME);
        if (csv == null) {
            return out;
        }
        try {
            CsvSupport.RecordReader reader = CsvSupport.openRecordReader(csv);
            try {
                CsvSupport.Record header = reader.readRecord();
                if (header == null) return out;
                String[] cols = CsvSupport.parseRecord(header.text);
                for (String col : cols) {
                    if (col == null) continue;
                    String trimmed = col.trim();
                    if (trimmed.isEmpty()) continue;
                    if (isMetricColumn(trimmed)) {
                        out.add(trimmed);
                    }
                }
            } finally {
                reader.close();
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    /**
     * Logs the message always. Shows a modal dialog only when not headless
     * and not suppressing dialogs.
     */
    private void notifyUser(String title, String message) {
        IJ.log(message.replace('\n', ' '));
        if (!headless && !suppressDialogs) {
            IJ.showMessage(title, message);
        }
    }

    private static File existingProjectSummaryFile(FlashProjectLayout layout, String fileName) {
        File file = layout.projectSummaryWriteFile(fileName);
        return file.isFile() ? file : null;
    }

    // ================================================================
    //  CSV output
    // ================================================================

    private void writeStatisticsCsv(File outFile, List<StatisticRow> results) {
        try {
            File parent = outFile.getParentFile();
            if (parent != null) {
                IoUtils.mustMkdirs(parent);
            }
            PrintWriter pw = CsvSupport.newWriter(outFile);
            try {
                pw.println(CsvSupport.joinRow(Arrays.asList(
                        "Metric",
                        "Test",
                        "Statistic",
                        "p-value",
                        "Significant",
                        "NormalityResult",
                        "Group1",
                        "Group2",
                        "PairwiseTest",
                        "PairwiseStatistic",
                        "PairwisePValue",
                        "CorrectedPValue",
                        "Significance",
                        "Notes",
                        "Paired",
                        "PostHocMethod")));

                for (StatisticRow r : results) {
                    List<String> row = new ArrayList<String>();
                    row.add(r.metric);
                    row.add(r.test);
                    row.add(Double.isNaN(r.statistic) ? "" : fmtStat(r.statistic));
                    row.add(Double.isNaN(r.pValue) ? "" : fmtP(r.pValue));
                    row.add(r.significant);
                    row.add(r.normalityResult);
                    row.add(r.group1);
                    row.add(r.group2);
                    row.add(r.pairwiseTest);
                    row.add(Double.isNaN(r.pairwiseStatistic) ? "" : fmtStat(r.pairwiseStatistic));
                    row.add(Double.isNaN(r.pairwisePValue) ? "" : fmtP(r.pairwisePValue));
                    row.add(Double.isNaN(r.correctedPValue) ? "" : fmtP(r.correctedPValue));
                    row.add(r.significance);
                    row.add(r.notes);
                    row.add("Skipped".equals(r.test) ? "" : (r.paired ? "Yes" : "No"));
                    row.add(r.postHocMethod == null ? "" : r.postHocMethod);
                    pw.println(CsvSupport.joinRow(row));
                }
            } finally {
                pw.close();
            }
            IJ.log("Saved: " + outFile.getAbsolutePath());
        } catch (IOException e) {
            IJ.log("Error writing " + outFile.getName() + ": " + e.getMessage());
        }
    }

    // ================================================================
    //  CSV parsing (same pattern as MasterAggregationAnalysis)
    // ================================================================

    private static class CsvData {
        List<String> columns = new ArrayList<String>();
        List<String> animals = new ArrayList<String>();
        LinkedHashMap<String, Map<String, Double>> data =
                new LinkedHashMap<String, Map<String, Double>>();
    }

    private CsvData parseMasterCsv(File csvFile) {
        CsvData result = new CsvData();
        try {
            CsvSupport.RecordReader csv = CsvSupport.openRecordReader(csvFile);
            try {
                CsvSupport.Record headerRecord = csv.readRecord();
                if (headerRecord == null) return result;
                String[] header = CsvSupport.parseRecord(headerRecord.text);
                for (String h : header) {
                    result.columns.add(h.trim());
                }

                int animalIdx = result.columns.indexOf("AnimalName");
                if (animalIdx < 0) {
                    IJ.log("  'AnimalName' column not found in " + csvFile.getName());
                    return result;
                }

                CsvSupport.Record record;
                while ((record = csv.readRecord()) != null) {
                    if (CsvSupport.isBlankRecord(record.text)) continue;
                    String[] row = CsvSupport.parseRecord(record.text);

                    String animal = safeGet(row, animalIdx).trim();
                    if (animal.isEmpty()) continue;
                    result.animals.add(animal);

                    Map<String, Double> rowData = new LinkedHashMap<String, Double>();
                    for (int c = 0; c < result.columns.size(); c++) {
                        if (c == animalIdx) continue;
                        String val = safeGet(row, c).trim();
                        if (!val.isEmpty()) {
                            try {
                                double d = Double.parseDouble(val);
                                if (!Double.isNaN(d) && !Double.isInfinite(d)) {
                                    rowData.put(result.columns.get(c), d);
                                }
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                    result.data.put(animal, rowData);
                }
            } finally {
                csv.close();
            }
        } catch (IOException e) {
            IJ.log("Error reading " + csvFile.getName() + ": " + e.getMessage());
        }
        return result;
    }

    // ================================================================
    //  Helpers
    // ================================================================

    private static boolean isMetricColumn(String col) {
        if (col == null) return false;
        if (col.equals("AnimalName")) return false;
        if (col.equals("numSections")) return false;
        if (col.contains("numSections")) return false;
        return true;
    }

    private static String safeGet(String[] arr, int idx) {
        if (idx < 0 || idx >= arr.length) return "";
        return arr[idx];
    }

    private static String fmtStat(double val) {
        if (Double.isNaN(val) || Double.isInfinite(val)) return "";
        return String.format(Locale.US, "%.6f", val);
    }

    private static String fmtP(double val) {
        if (Double.isNaN(val) || Double.isInfinite(val)) return "";
        if (val < 0.0001) return String.format(Locale.US, "%.2e", val);
        return String.format(Locale.US, "%.6f", val);
    }
}
