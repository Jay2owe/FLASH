package flash.pipeline.export;

import ij.IJ;
import flash.pipeline.analyses.AggregationConditionSupport;
import flash.pipeline.analyses.Analysis;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.io.CsvSupport;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.IoUtils;
import flash.pipeline.io.ResultAnimalScanner;
import flash.pipeline.naming.ChannelFilenameCodec;
import flash.pipeline.naming.ConditionAssignments;
import flash.pipeline.naming.ConditionAxis;
import flash.pipeline.results.RunIdCsv;
import flash.pipeline.results.StartHereWriter;
import flash.pipeline.runrecord.AnalysisRunContext;
import flash.pipeline.runrecord.RunRecordAware;

import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.ToggleSwitch;
import flash.pipeline.ui.wizard.ConditionReviewSupport;

import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Reads master CSV(s) produced by {@code MasterAggregationAnalysis} and
 * exports a formatted Excel workbook. Behavior is driven by an
 * {@link ExcelExportPreset}: which sheets to include, how per-metric sheets
 * render, how the statistics sheet highlights significant rows, header style,
 * and the optional methods appendix.
 */
public class ExcelSummaryExportAnalysis implements Analysis, RunRecordAware {

    private boolean headless = false;
    private boolean suppressDialogs = false;
    private AnalysisRunContext runRecordContext = null;

    private ExcelExportPreset preset = ExcelExportPreset.exploratoryDefault();
    private boolean configFromCli = false;

    // TAG parsing for Analysis Details files
    private static final Pattern TAG_RE = Pattern.compile(
            "<(?<tag>[^>]+)>\\s*(?<body>.*?)\\s*</\\k<tag>>", Pattern.DOTALL);
    private static final Pattern TEXTURE_FEATURE_METRIC_RE = Pattern.compile(
            "^(?:(.+)_)?(MorphTexture_F(?:3D)?\\d+)(?:Mean)?(?:_permm3)?$");

    @Override
    public void setHeadless(boolean headless) {
        this.headless = headless;
    }

    @Override
    public void setSuppressDialogs(boolean suppress) {
        this.suppressDialogs = suppress;
    }

    @Override
    public void setRunRecordContext(AnalysisRunContext context) {
        this.runRecordContext = context;
    }

    @Override
    public void setCliConfig(CLIConfig config) {
        if (config == null || config.getExcel() == null) {
            return;
        }
        CLIConfig.ExcelConfig src = config.getExcel();
        ExcelExportPreset base = ExcelExportPreset.exploratoryDefault();

        if (src.getPresetName() != null && !src.getPresetName().trim().isEmpty()) {
            try {
                File projectRoot = config.getDirectory() == null
                        ? new File(System.getProperty("user.dir", "."))
                        : new File(config.getDirectory());
                base = new ExcelExportPresetIO(projectRoot).load(src.getPresetName().trim());
            } catch (IOException e) {
                IJ.log("[CLI] Warning: Could not load excel.preset '"
                        + src.getPresetName() + "': " + e.getMessage());
            }
        }
        for (Map.Entry<String, String> override : src.getFieldOverrides().entrySet()) {
            base = base.withField(override.getKey(), override.getValue());
        }
        if (src.getIncludeTextureFeatures() != null) {
            base = base.withField("texture_features",
                    Boolean.toString(src.getIncludeTextureFeatures().booleanValue()));
        }
        this.preset = base;
        this.configFromCli = true;
    }

    public ExcelExportPreset getPreset() {
        return preset;
    }

    public void setPreset(ExcelExportPreset preset) {
        this.preset = preset == null ? ExcelExportPreset.exploratoryDefault() : preset;
        this.configFromCli = false;
    }

    @Override
    public void execute(String directory) {
        IJ.log("=== Excel Summary Export ===");

        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);

        if (canShowGuiDialog(suppressDialogs, configFromCli, GraphicsEnvironment.isHeadless())) {
            ExcelExportPreset chosen = showConfigDialog(new File(directory), preset);
            if (chosen == null) {
                IJ.log("Excel Summary Export cancelled by user.");
                return;
            }
            preset = chosen;
        }
        if (preset == null) {
            preset = ExcelExportPreset.exploratoryDefault();
        }
        IJ.log("Excel preset: " + preset.getName());

        File objectsCsv = existingProjectSummaryFile(layout, FlashProjectLayout.MASTER_OBJECTS_FILENAME);
        File intensitiesCsv = existingProjectSummaryFile(layout, FlashProjectLayout.MASTER_INTENSITIES_FILENAME);

        if (objectsCsv == null && intensitiesCsv == null) {
            notifyUser("Excel Summary Export",
                    "No master CSV files found in FLASH/Results/Tables/Project Summary/.\n"
                            + "Run Master Data Aggregation first.");
            return;
        }

        CsvData objData = objectsCsv != null ? parseMasterCsv(objectsCsv) : null;
        CsvData intData = intensitiesCsv != null ? parseMasterCsv(intensitiesCsv) : null;

        Set<String> allAnimals = new LinkedHashSet<String>();
        if (objData != null) allAnimals.addAll(objData.animals);
        if (intData != null) allAnimals.addAll(intData.animals);

        if (allAnimals.isEmpty()) {
            notifyUser("Excel Summary Export", "No animal data found in master CSVs.");
            return;
        }

        Map<String, String> animalToCondition = autoDetectConditions(directory, allAnimals);
        // N-axis model for the per-axis condition columns; composite values match
        // animalToCondition so grouping stays consistent. Single-axis -> one column.
        ConditionAssignments conditionModel =
                ConditionManifestIO.resolveAssignmentsModel(directory, allAnimals);

        List<String> conditionOrder = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        for (String animal : allAnimals) {
            String cond = animalToCondition.get(animal);
            if (cond != null && !cond.isEmpty() && seen.add(cond)) {
                conditionOrder.add(cond);
            }
        }
        IJ.log("Condition assignments:");
        for (String cond : conditionOrder) {
            StringBuilder animals = new StringBuilder();
            for (String animal : allAnimals) {
                if (cond.equals(animalToCondition.get(animal))) {
                    if (animals.length() > 0) animals.append(", ");
                    animals.append(animal);
                }
            }
            IJ.log("  " + cond + ": " + animals.toString());
        }

        List<String> metricColumns = new ArrayList<String>();
        if (objData != null) {
            for (String col : objData.columns) {
                if (isMetricColumn(col)) metricColumns.add(col);
            }
        }
        if (intData != null) {
            for (String col : intData.columns) {
                if (isMetricColumn(col)) metricColumns.add(col);
            }
        }

        LinkedHashMap<String, Map<String, Double>> mergedData = new LinkedHashMap<String, Map<String, Double>>();
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

        Map<String, Map<String, String>> detailsPerMarker = loadAllAnalysisDetails(directory);

        File outFile = layout.summaryWorkbookWriteFile();
        File statisticsCsv = existingProjectSummaryFile(layout, FlashProjectLayout.STATISTICS_FILENAME);
        try {
            writeExcel(outFile, statisticsCsv, conditionOrder, animalToCondition, conditionModel,
                    allAnimals, metricColumns, mergedData, detailsPerMarker);
            StartHereWriter.write(layout);
            IJ.log("Excel saved: " + outFile.getAbsolutePath());
        } catch (Exception e) {
            IJ.log("Error writing Excel: " + e.getMessage());
            recordError("Error writing Excel", e);
            if (canShowGuiDialog(suppressDialogs, configFromCli, GraphicsEnvironment.isHeadless())) {
                IJ.showMessage("Excel Summary Export", "Error: " + e.getMessage());
            }
        }
    }

    private void notifyUser(String title, String message) {
        IJ.log(message.replace('\n', ' '));
        recordWarn(message.replace('\n', ' '));
        if (canShowGuiDialog(suppressDialogs, configFromCli, GraphicsEnvironment.isHeadless())) {
            IJ.showMessage(title, message);
        }
    }

    static boolean canShowGuiDialog(boolean suppressDialogs,
                                    boolean configFromCli,
                                    boolean runtimeHeadless) {
        return !suppressDialogs && !configFromCli && !runtimeHeadless;
    }

    private static File existingProjectSummaryFile(FlashProjectLayout layout, String fileName) {
        File file = layout.projectSummaryWriteFile(fileName);
        return file.isFile() ? file : null;
    }

    private boolean isMetricColumn(String col) {
        if (col == null) return false;
        if (RunIdCsv.RUN_ID_COLUMN.equals(col.trim())
                || RunIdCsv.SOURCE_RUN_ID_COLUMN.equals(col.trim())) {
            return false;
        }
        return !col.equals("AnimalName")
                && !col.equals("Condition")
                && !col.equals("numSections")
                && !col.equals("numZSlices")
                && (ExcelNameMap.convert(col) != null
                    || includeTextureFeatureColumn(col));
    }

    private boolean includeTextureFeatureColumn(String col) {
        return preset != null
                && preset.isIncludeTextureFeatures()
                && TEXTURE_FEATURE_METRIC_RE.matcher(col).matches();
    }

    private String[] metricNameDesc(String col) {
        String[] mapped = ExcelNameMap.convert(col);
        if (mapped != null) {
            return mapped;
        }
        if (includeTextureFeatureColumn(col)) {
            String label = textureFeatureLabel(col);
            return new String[] {
                    label,
                    "Raw MorphTexture feature-vector aggregate column: " + col + "."
            };
        }
        return null;
    }

    private static String textureFeatureLabel(String col) {
        Matcher matcher = TEXTURE_FEATURE_METRIC_RE.matcher(col);
        if (!matcher.matches()) {
            return col;
        }
        return matcher.group(2);
    }

    private static String textureFeatureMarker(String col) {
        Matcher matcher = TEXTURE_FEATURE_METRIC_RE.matcher(col);
        if (!matcher.matches()) {
            return null;
        }
        return matcher.group(1);
    }

    /**
     * Main-panel configuration dialog. Shows only:
     * <ul>
     *   <li>Preset dropdown populated from the FLASH preset directory.</li>
     *   <li>{@code [Save as preset...]} button.</li>
     *   <li>Read-only preview label listing which sheets the preset will emit.</li>
     * </ul>
     * Returns the chosen preset on OK, or {@code null} on Cancel.
     */
    public ExcelExportPreset showConfigDialog(File projectRoot, ExcelExportPreset current) {
        if (GraphicsEnvironment.isHeadless()) {
            return current;
        }
        final ExcelExportPresetIO presetIO = new ExcelExportPresetIO(
                projectRoot == null ? new File(".") : projectRoot);
        final ExcelExportPreset[] selected = { current == null
                ? ExcelExportPreset.exploratoryDefault() : current };

        final PipelineDialog pd = new PipelineDialog("Excel Summary Export", PipelineDialog.Phase.EXPORT);
        pd.setWorkflowTracker(excelWorkflow(projectRoot), 0);
        pd.addHeader("Preset");

        // Custom row: Preset combo + Save-as-preset button (kept out of the
        // combos retrieval list so retrieval order stays clean).
        final JComboBox<String> presetCombo = new JComboBox<String>();
        populatePresetChoice(presetCombo, presetIO, selected[0].getName());
        final JButton saveButton = new JButton("Save as preset...");
        flash.pipeline.ui.FlashIcons.apply(saveButton, flash.pipeline.ui.FlashIcons.save());
        JPanel presetRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        presetRow.setOpaque(false);
        presetRow.add(new JLabel("Preset:"));
        presetRow.add(presetCombo);
        presetRow.add(saveButton);
        pd.addComponent(presetRow);

        final JLabel previewLabel = pd.addMessage(buildPreviewHtml(selected[0]));

        addConditionReviewRow(pd, projectRoot == null ? null : projectRoot.getAbsolutePath());

        // Advanced section: live form rows that mutate the chosen preset
        // before Save. Keeps the dropdown as the only Basic field.
        final boolean[] suppressAdvanced = { false };
        pd.beginAdvancedSection("excelExport");
        final ToggleSwitch condToggle = pd.addToggle(
                "Include 'Experimental Conditions' sheet",
                selected[0].isIncludeExperimentalConditionsSheet());
        final ToggleSwitch summaryToggle = pd.addToggle(
                "Include 'Data Summary' sheet",
                selected[0].isIncludeDataSummarySheet());
        final ToggleSwitch perMetricToggle = pd.addToggle(
                "Include per-metric sheets",
                selected[0].isIncludePerMetricSheets());
        final ToggleSwitch statsSheetToggle = pd.addToggle(
                "Include 'Statistics' sheet",
                selected[0].isIncludeStatisticsSheet());

        final String highlightOff = "Off";
        final String highlightYellow = "Yellow";
        final String highlightGradient = "P-gradient";
        final String[] highlightOptions = { highlightOff, highlightYellow, highlightGradient };
        final JComboBox<String> highlightCombo = pd.addChoice(
                "Statistics highlight",
                highlightOptions,
                highlightLabel(selected[0].getSignificanceHighlight(),
                        highlightOff, highlightYellow, highlightGradient));

        final ToggleSwitch starsToggle = pd.addToggle(
                "Significance stars (e.g. *, **, ***)",
                selected[0].isSignificanceStars());
        final ToggleSwitch methodsToggle = pd.addToggle(
                "Methods appendix sheet",
                selected[0].isIncludeMethodsAppendix());
        final ToggleSwitch textureFeaturesToggle = pd.addToggle(
                "Include raw object texture feature-vector columns",
                selected[0].isIncludeTextureFeatures());
        pd.endAdvancedSection();

        condToggle.addChangeListener(new Runnable() {
            @Override public void run() {
                if (suppressAdvanced[0]) return;
                selected[0] = selected[0].withField("conditions_sheet",
                        Boolean.toString(condToggle.isSelected()));
                previewLabel.setText(buildPreviewHtml(selected[0]));
            }
        });
        summaryToggle.addChangeListener(new Runnable() {
            @Override public void run() {
                if (suppressAdvanced[0]) return;
                selected[0] = selected[0].withField("data_summary_sheet",
                        Boolean.toString(summaryToggle.isSelected()));
                previewLabel.setText(buildPreviewHtml(selected[0]));
            }
        });
        perMetricToggle.addChangeListener(new Runnable() {
            @Override public void run() {
                if (suppressAdvanced[0]) return;
                selected[0] = selected[0].withField("per_metric_sheets",
                        Boolean.toString(perMetricToggle.isSelected()));
                previewLabel.setText(buildPreviewHtml(selected[0]));
            }
        });
        statsSheetToggle.addChangeListener(new Runnable() {
            @Override public void run() {
                if (suppressAdvanced[0]) return;
                selected[0] = selected[0].withField("stats_sheet",
                        Boolean.toString(statsSheetToggle.isSelected()));
                previewLabel.setText(buildPreviewHtml(selected[0]));
            }
        });
        highlightCombo.addActionListener(e -> {
            if (suppressAdvanced[0]) return;
            String val = (String) highlightCombo.getSelectedItem();
            String token;
            if (highlightYellow.equals(val)) token = "yellow";
            else if (highlightGradient.equals(val)) token = "p_gradient";
            else token = "off";
            selected[0] = selected[0].withField("highlight", token);
            previewLabel.setText(buildPreviewHtml(selected[0]));
        });
        starsToggle.addChangeListener(new Runnable() {
            @Override public void run() {
                if (suppressAdvanced[0]) return;
                selected[0] = selected[0].withField("stars",
                        Boolean.toString(starsToggle.isSelected()));
                previewLabel.setText(buildPreviewHtml(selected[0]));
            }
        });
        methodsToggle.addChangeListener(new Runnable() {
            @Override public void run() {
                if (suppressAdvanced[0]) return;
                selected[0] = selected[0].withField("methods_appendix",
                        Boolean.toString(methodsToggle.isSelected()));
                previewLabel.setText(buildPreviewHtml(selected[0]));
            }
        });
        textureFeaturesToggle.addChangeListener(new Runnable() {
            @Override public void run() {
                if (suppressAdvanced[0]) return;
                selected[0] = selected[0].withField("texture_features",
                        Boolean.toString(textureFeaturesToggle.isSelected()));
                previewLabel.setText(buildPreviewHtml(selected[0]));
            }
        });

        presetCombo.addActionListener(e -> {
            Object item = presetCombo.getSelectedItem();
            if (item == null) return;
            String name = item.toString();
            if (name.isEmpty()) return;
            try {
                ExcelExportPreset loaded = presetIO.load(name);
                selected[0] = loaded;
                previewLabel.setText(buildPreviewHtml(loaded));
                suppressAdvanced[0] = true;
                try {
                    condToggle.setSelected(loaded.isIncludeExperimentalConditionsSheet());
                    summaryToggle.setSelected(loaded.isIncludeDataSummarySheet());
                    perMetricToggle.setSelected(loaded.isIncludePerMetricSheets());
                    statsSheetToggle.setSelected(loaded.isIncludeStatisticsSheet());
                    highlightCombo.setSelectedItem(highlightLabel(
                            loaded.getSignificanceHighlight(),
                            highlightOff, highlightYellow, highlightGradient));
                    starsToggle.setSelected(loaded.isSignificanceStars());
                    methodsToggle.setSelected(loaded.isIncludeMethodsAppendix());
                    textureFeaturesToggle.setSelected(loaded.isIncludeTextureFeatures());
                } finally {
                    suppressAdvanced[0] = false;
                }
            } catch (IOException ex) {
                IJ.log("Could not load preset '" + name + "': " + ex.getMessage());
            }
        });

        saveButton.addActionListener(e -> {
            String proposed = (String) JOptionPane.showInputDialog(
                    null,
                    "Name for saved preset:",
                    "Save Excel Preset",
                    JOptionPane.PLAIN_MESSAGE,
                    null, null,
                    selected[0].getName());
            if (proposed == null || proposed.trim().isEmpty()) return;
            ExcelExportPreset source = selected[0];
            ExcelExportPreset toSave = new ExcelExportPreset(
                    proposed.trim(), source.getDescription(),
                    source.isIncludeExperimentalConditionsSheet(),
                    source.isIncludeDataSummarySheet(),
                    source.isIncludePerMetricSheets(),
                    source.isIncludeStatisticsSheet(),
                    source.getMetricSheetDetail(),
                    source.isIncludeMethodsAppendix(),
                    source.getSignificanceHighlight(),
                    source.getHeaderStyle(),
                    source.isSignificanceStars(),
                    source.isIncludeTextureFeatures());
            try {
                presetIO.save(toSave);
                selected[0] = toSave;
                populatePresetChoice(presetCombo, presetIO, toSave.getName());
                previewLabel.setText(buildPreviewHtml(toSave));
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(null,
                        "Could not save preset: " + ex.getMessage(),
                        "Save Excel Preset", JOptionPane.ERROR_MESSAGE);
            }
        });

        boolean accepted = pd.showDialog();
        return accepted ? selected[0] : null;
    }

    private static void populatePresetChoice(JComboBox<String> combo,
                                             ExcelExportPresetIO presetIO,
                                             String selectedName) {
        combo.removeAllItems();
        String match = null;
        try {
            for (ExcelExportPreset preset : presetIO.listAll()) {
                combo.addItem(preset.getName());
                if (selectedName != null && selectedName.equals(preset.getName())) {
                    match = preset.getName();
                }
            }
        } catch (IOException e) {
            IJ.log("Could not list excel presets: " + e.getMessage());
        }
        if (match != null) {
            combo.setSelectedItem(match);
        } else if (combo.getItemCount() > 0) {
            combo.setSelectedIndex(0);
        }
    }

    private static String highlightLabel(ExcelExportPreset.SignificanceHighlight value,
                                         String offLabel,
                                         String yellowLabel,
                                         String gradientLabel) {
        if (value == ExcelExportPreset.SignificanceHighlight.YELLOW) return yellowLabel;
        if (value == ExcelExportPreset.SignificanceHighlight.P_GRADIENT) return gradientLabel;
        return offLabel;
    }

    static String buildPreviewHtml(ExcelExportPreset preset) {
        StringBuilder sheets = new StringBuilder();
        if (preset.isIncludeExperimentalConditionsSheet()) sheets.append("Experimental Conditions, ");
        if (preset.isIncludeDataSummarySheet()) sheets.append("Data Summary, ");
        if (preset.isIncludePerMetricSheets()) {
            sheets.append("Per-metric (");
            sheets.append(preset.getMetricSheetDetail().token());
            sheets.append("), ");
        }
        if (preset.isIncludeStatisticsSheet()) {
            sheets.append("Statistics");
            if (preset.isSignificanceStars()) sheets.append(" [stars]");
            sheets.append(", ");
        }
        if (preset.isIncludeMethodsAppendix()) sheets.append("Methods Appendix, ");
        String trimmed = sheets.length() == 0 ? "(no sheets selected)" : sheets.substring(0, sheets.length() - 2);
        String detail = "Highlight: " + preset.getSignificanceHighlight().token()
                + " | Headers: " + preset.getHeaderStyle().token()
                + " | Texture vectors: "
                + (preset.isIncludeTextureFeatures() ? "included" : "hidden");
        return "<html><body style='width:360px'><b>Sheets:</b> " + trimmed
                + "<br/><i>" + detail + "</i></body></html>";
    }

    // ---- Condition resolution ---------------------------------------------

    private void addConditionReviewRow(PipelineDialog pd, final String directory) {
        pd.addHeader("Conditions");
        LinkedHashSet<String> animals = ResultAnimalScanner.collect(directory);
        String status = animals.isEmpty()
                ? "No animals found yet — run Master Data Aggregation first."
                : ConditionReviewSupport.evaluate(directory, animals).summary();
        pd.addMessage(status);

        JButton reviewButton = new JButton("Review conditions...");
        reviewButton.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                LinkedHashSet<String> current = ResultAnimalScanner.collect(directory);
                if (current.isEmpty()) {
                    IJ.showMessage("Review conditions",
                            "No animals found yet. Run Master Data Aggregation first.");
                    return;
                }
                ConditionReviewSupport.Options options = new ConditionReviewSupport.Options();
                options.title = "Excel Summary Export — Condition Assignment";
                options.primaryButtonText = "Save conditions";
                options.workflowSteps = new String[]{"Setup", "Conditions", "Export"};
                options.workflowActiveIndex = 1;
                LinkedHashMap<String, String> reviewed =
                        ConditionReviewSupport.reviewAndSave(null, directory, current, options);
                if (reviewed != null) {
                    pd.setWorkflowTracker(excelWorkflow(new File(directory)), 0);
                }
            }
        });
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.setOpaque(false);
        row.add(reviewButton);
        pd.addComponent(row);
    }

    private boolean needsConditionReview(File projectRoot) {
        if (projectRoot == null) {
            return false;
        }
        String directory = projectRoot.getAbsolutePath();
        LinkedHashSet<String> animals = ResultAnimalScanner.collect(directory);
        return !animals.isEmpty()
                && ConditionReviewSupport.evaluate(directory, animals).needsReview();
    }

    private String[] excelWorkflow(File projectRoot) {
        return needsConditionReview(projectRoot)
                ? new String[]{"Setup", "Conditions", "Export"}
                : new String[]{"Setup", "Export"};
    }

    private Map<String, String> autoDetectConditions(String directory, Set<String> animals) {
        boolean gui = canShowGuiDialog(suppressDialogs, configFromCli, GraphicsEnvironment.isHeadless());
        ConditionReviewSupport.Health health = ConditionReviewSupport.evaluate(directory, animals);

        if (gui && health.needsReview()) {
            ConditionReviewSupport.Options options = new ConditionReviewSupport.Options();
            options.title = "Excel Summary Export — Condition Assignment";
            options.primaryButtonText = "Save conditions";
            options.workflowSteps = new String[]{"Setup", "Conditions", "Export"};
            options.workflowActiveIndex = 1;
            LinkedHashMap<String, String> reviewed =
                    ConditionReviewSupport.reviewAndSave(null, directory, animals, options);
            if (reviewed != null) {
                offerMasterRefreshIfStale(directory, animals);
            }
        } else if (!gui) {
            // Unattended: never block, but make the condition state auditable.
            logConditionHealth(health);
        }

        Map<String, String> assignments = ConditionManifestIO.resolveAssignments(directory, animals);
        IJ.log("  Resolved conditions: " + assignments.values());
        return assignments;
    }

    private static void logConditionHealth(ConditionReviewSupport.Health health) {
        if (!health.needsReview()) {
            return;
        }
        IJ.log("  [conditions] " + health.summary());
        for (String message : health.messages) {
            IJ.log("    - " + message);
        }
    }

    /**
     * When master-table condition labels are stale relative to the current
     * manifest, offer to reapply current conditions to the master tables so the
     * workbook and the master CSVs agree. GUI-only; a no-op otherwise.
     */
    private void offerMasterRefreshIfStale(String directory, Set<String> animals) {
        if (!masterConditionLabelsStale(directory, animals)) {
            return;
        }
        String[] options = {"Apply current conditions to master tables", "Continue with current conditions"};
        int choice = JOptionPane.showOptionDialog(
                null,
                "<html><body style='width:360px'>Conditions changed since these master tables"
                        + " were written.<br><br>Statistics and Excel can use the current"
                        + " Conditions.csv, but the master table Condition column is stale.</body></html>",
                "Conditions changed",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[0]);
        if (choice == 0) {
            AggregationConditionSupport.RefreshSummary summary =
                    AggregationConditionSupport.refreshAllMasterTables(directory);
            IJ.log("[Excel] Refreshed conditions in " + summary.filesUpdated + " master table(s).");
        }
    }

    /**
     * True when any master table carries a Condition value that differs from the
     * current manifest, or lacks a Condition column entirely.
     */
    private boolean masterConditionLabelsStale(String directory, Set<String> animals) {
        Map<String, String> current = ConditionManifestIO.resolveAssignments(directory, animals);
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        for (String name : new String[]{FlashProjectLayout.MASTER_OBJECTS_FILENAME,
                FlashProjectLayout.MASTER_INTENSITIES_FILENAME}) {
            File master = existingProjectSummaryFile(layout, name);
            if (master == null) continue;
            if (masterCsvConditionDiffers(master, current)) {
                return true;
            }
        }
        return false;
    }

    private boolean masterCsvConditionDiffers(File master, Map<String, String> current) {
        try {
            CsvSupport.RecordReader reader = CsvSupport.openRecordReader(master);
            try {
                CsvSupport.Record headerRecord = reader.readRecord();
                if (headerRecord == null) return false;
                String[] header = CsvSupport.parseRecord(headerRecord.text);
                int animalIdx = -1;
                int condIdx = -1;
                for (int i = 0; i < header.length; i++) {
                    String col = header[i] == null ? "" : header[i].trim();
                    if (col.equals("AnimalName")) animalIdx = i;
                    else if (col.equals("Condition")) condIdx = i;
                }
                if (animalIdx < 0) return false;
                if (condIdx < 0) return true; // old format, no Condition column yet
                CsvSupport.Record record;
                while ((record = reader.readRecord()) != null) {
                    if (CsvSupport.isBlankRecord(record.text)) continue;
                    String[] row = CsvSupport.parseRecord(record.text);
                    String animal = animalIdx < row.length ? row[animalIdx].trim() : "";
                    if (animal.isEmpty()) continue;
                    String stored = condIdx < row.length ? row[condIdx].trim() : "";
                    String expected = current.get(animal);
                    if (expected != null && !expected.equals(stored)) {
                        return true;
                    }
                }
            } finally {
                reader.close();
            }
        } catch (Exception ignored) {
            // Unreadable master: do not block the export over a stale-check failure.
        }
        return false;
    }

    // ---- CSV Parsing ------------------------------------------------------

    private static class CsvData {
        List<String> columns = new ArrayList<String>();
        List<String> animals = new ArrayList<String>();
        LinkedHashMap<String, Map<String, Double>> data = new LinkedHashMap<String, Map<String, Double>>();
    }

    private CsvData parseMasterCsv(File csvFile) {
        CsvData result = new CsvData();
        int unparseable = 0;
        AnalysisRunContext.InputHandle inputHandle = recordInputStart(csvFile);
        long inputStarted = System.currentTimeMillis();
        String inputStatus = "processed";
        try (CsvSupport.RecordReader csv = CsvSupport.openRecordReader(csvFile)) {
            CsvSupport.Record headerRecord = csv.readRecord();
            if (headerRecord == null) {
                inputStatus = "skipped";
                return result;
            }
            String[] header = CsvSupport.parseRecord(headerRecord.text);
            for (String h : header) {
                result.columns.add(h.trim());
            }

            int animalIdx = result.columns.indexOf("AnimalName");
            if (animalIdx < 0) {
                IJ.log("  'AnimalName' column not found in " + csvFile.getName());
                recordWarn("'AnimalName' column not found in " + csvFile.getName());
                inputStatus = "skipped";
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
                    if (isRunLineageColumn(result.columns.get(c))) continue;
                    String val = safeGet(row, c).trim();
                    if (!val.isEmpty()) {
                        try {
                            rowData.put(result.columns.get(c), Double.parseDouble(val));
                        } catch (NumberFormatException nfe) {
                            unparseable++;
                        }
                    }
                }
                result.data.put(animal, rowData);
            }
        } catch (IOException e) {
            IJ.log("Error reading " + csvFile.getName() + ": " + e.getMessage());
            inputStatus = "failed";
            recordError("Error reading " + csvFile.getName(), e);
        } finally {
            recordInputEnd(inputHandle, inputStatus, inputStarted);
        }
        if (unparseable > 0) {
            IJ.log("[Excel] Skipped " + unparseable
                    + " unparseable cells in " + csvFile.getName());
            recordWarn("[Excel] Skipped " + unparseable
                    + " unparseable cells in " + csvFile.getName());
        }
        return result;
    }

    // ---- Analysis Details loading -----------------------------------------

    private Map<String, Map<String, String>> loadAllAnalysisDetails(String directory) {
        Map<String, Map<String, String>> result = new LinkedHashMap<String, Map<String, String>>();
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);

        loadDetailsFromDir(layout.analysisDetailsWriteDir(), result);

        return result;
    }

    private void loadDetailsFromDir(File detailsDir,
                                    Map<String, Map<String, String>> out) {
        if (!detailsDir.isDirectory()) return;

        File[] txtFiles = detailsDir.listFiles(new java.io.FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.toLowerCase(Locale.ROOT).endsWith(".txt");
            }
        });

        if (txtFiles == null) return;
        Arrays.sort(txtFiles, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return String.CASE_INSENSITIVE_ORDER.compare(a.getName(), b.getName());
            }
        });

        for (File txt : txtFiles) {
            String safeName = txt.getName();
            if (safeName.toLowerCase(Locale.ROOT).endsWith(".txt")) {
                safeName = safeName.substring(0, safeName.length() - 4);
            }

            String analysisType;
            String channelPart;
            if (safeName.startsWith(flash.pipeline.results.ObjectAnalysisDetailsWriter.FILENAME_PREFIX)) {
                analysisType = "Object";
                channelPart = safeName.substring(
                        flash.pipeline.results.ObjectAnalysisDetailsWriter.FILENAME_PREFIX.length());
                if (channelPart.equals("segmentation_models")) continue;
            } else if (safeName.startsWith(flash.pipeline.results.IntensityDetailsWriter.FILENAME_PREFIX)) {
                analysisType = "ROI";
                channelPart = safeName.substring(
                        flash.pipeline.results.IntensityDetailsWriter.FILENAME_PREFIX.length());
            } else {
                continue;
            }
            String marker = ChannelFilenameCodec.toRaw(channelPart);

            String key = marker + "|" + analysisType;
            if (out.containsKey(key)) {
                continue;
            }
            AnalysisRunContext.InputHandle inputHandle = recordInputStart(txt);
            long inputStarted = System.currentTimeMillis();
            try {
                List<String> lines = Files.readAllLines(txt.toPath(), StandardCharsets.UTF_8);
                String text = join(lines, "\n");
                Map<String, String> blocks = new LinkedHashMap<String, String>();

                Matcher m = TAG_RE.matcher(text);
                while (m.find()) {
                    blocks.put(m.group("tag").trim(), m.group("body").trim());
                }
                blocks.put("_analysisType", analysisType);
                blocks.put("_sourceFile", txt.getName());
                out.put(key, blocks);
                recordInputEnd(inputHandle, "processed", inputStarted);
            } catch (IOException e) {
                IJ.log("  Error reading details: " + txt.getName());
                recordInputEnd(inputHandle, "failed", inputStarted);
                recordError("Error reading details: " + txt.getName(), e);
            }
        }
    }

    // ---- Excel Writing ----------------------------------------------------

    private void writeExcel(File outFile,
                            File statisticsCsv,
                            List<String> conditionOrder,
                            Map<String, String> animalToCondition,
                            ConditionAssignments conditionModel,
                            Set<String> allAnimals,
                            List<String> metricColumns,
                            LinkedHashMap<String, Map<String, Double>> mergedData,
                            Map<String, Map<String, String>> detailsPerMarker) throws Exception {

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles styles = createStyles(wb, preset.getHeaderStyle());
            Set<String> usedSheetNames = new HashSet<String>();

            if (preset.isIncludeExperimentalConditionsSheet()) {
                writeConditionsSheet(wb, conditionOrder, animalToCondition, conditionModel,
                        allAnimals, styles, usedSheetNames);
            }

            if (preset.isIncludeDataSummarySheet()) {
                writeDataSummarySheet(wb, metricColumns, detailsPerMarker,
                        styles, usedSheetNames);
            }

            if (preset.isIncludePerMetricSheets()) {
                for (String col : metricColumns) {
                    String[] nameDesc = metricNameDesc(col);
                    if (nameDesc == null) continue;
                    writePerMetricSheet(wb, col, nameDesc, conditionOrder, animalToCondition,
                            allAnimals, mergedData, styles, usedSheetNames);
                }
            }

            if (preset.isIncludeStatisticsSheet() && statisticsCsv != null && statisticsCsv.exists()) {
                writeStatisticsSheet(wb, statisticsCsv, styles, usedSheetNames);
            }

            if (preset.isIncludeMethodsAppendix()) {
                writeMethodsAppendixSheet(wb, detailsPerMarker, styles, usedSheetNames);
            }

            if (wb.getNumberOfSheets() == 0) {
                Sheet empty = wb.createSheet(ExcelNameMap.safeSheetName("Summary", usedSheetNames));
                Row row = empty.createRow(0);
                setTextCellValue(row.createCell(0),
                        "No sheets were selected by preset '" + preset.getName() + "'.");
            }

            File temp = tempFileFor(outFile);
            boolean moved = false;
            try {
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(temp)) {
                    wb.write(fos);
                }
                moveAtomically(temp.toPath(), outFile.toPath());
                moved = true;
                recordOutput(outFile, "xlsx");
            } finally {
                if (!moved) {
                    Files.deleteIfExists(temp.toPath());
                }
            }
        }
    }

    private static File tempFileFor(File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null) IoUtils.mustMkdirs(parent);
        return File.createTempFile(tempPrefix(target), ".tmp",
                parent == null ? new File(".") : parent);
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        // Atomic move with retry/backoff for transient locks (cloud-sync, AV).
        // No in-place fallback: workbooks can be large, never read into memory.
        IoUtils.moveReplacing(source, target);
    }

    private static String tempPrefix(File target) {
        String name = target == null ? "excel" : target.getName();
        String clean = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return clean.length() < 3 ? "tmp" + clean : clean;
    }

    // ---- Style helpers ----------------------------------------------------

    private static final class Styles {
        final CellStyle headerStyle;
        final CellStyle cellStyle;
        final CellStyle centerStyle;
        final CellStyle wrapStyle;
        final CellStyle smallStyle;
        final CellStyle descStyle;
        final CellStyle mergeStyle;
        final CellStyle summaryLabelStyle;
        final CellStyle highlightStyle;
        final CellStyle boldHighlightStyle;

        Styles(CellStyle headerStyle, CellStyle cellStyle, CellStyle centerStyle,
               CellStyle wrapStyle, CellStyle smallStyle, CellStyle descStyle,
               CellStyle mergeStyle, CellStyle summaryLabelStyle,
               CellStyle highlightStyle, CellStyle boldHighlightStyle) {
            this.headerStyle = headerStyle;
            this.cellStyle = cellStyle;
            this.centerStyle = centerStyle;
            this.wrapStyle = wrapStyle;
            this.smallStyle = smallStyle;
            this.descStyle = descStyle;
            this.mergeStyle = mergeStyle;
            this.summaryLabelStyle = summaryLabelStyle;
            this.highlightStyle = highlightStyle;
            this.boldHighlightStyle = boldHighlightStyle;
        }
    }

    private Styles createStyles(XSSFWorkbook wb, ExcelExportPreset.HeaderStyle headerStyleMode) {
        boolean figureReady = headerStyleMode == ExcelExportPreset.HeaderStyle.FIGURE_READY;
        BorderStyle headerBorder = figureReady ? BorderStyle.MEDIUM : BorderStyle.THIN;

        XSSFCellStyle headerStyle = wb.createCellStyle();
        XSSFFont headerFont = wb.createFont();
        headerFont.setBold(true);
        if (figureReady) {
            headerFont.setFontHeightInPoints((short) 12);
        }
        headerStyle.setFont(headerFont);
        if (figureReady) {
            headerStyle.setFillForegroundColor(new XSSFColor(new byte[] {(byte) 0xC8, (byte) 0xC8, (byte) 0xC8}, null));
        } else {
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        }
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setBorderBottom(headerBorder);
        headerStyle.setBorderTop(headerBorder);
        headerStyle.setBorderLeft(headerBorder);
        headerStyle.setBorderRight(headerBorder);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);

        CellStyle cellStyle = wb.createCellStyle();
        cellStyle.setBorderBottom(BorderStyle.THIN);
        cellStyle.setBorderTop(BorderStyle.THIN);
        cellStyle.setBorderLeft(BorderStyle.THIN);
        cellStyle.setBorderRight(BorderStyle.THIN);
        cellStyle.setVerticalAlignment(VerticalAlignment.CENTER);

        CellStyle centerStyle = wb.createCellStyle();
        centerStyle.cloneStyleFrom(cellStyle);
        centerStyle.setAlignment(HorizontalAlignment.CENTER);

        CellStyle wrapStyle = wb.createCellStyle();
        wrapStyle.cloneStyleFrom(cellStyle);
        wrapStyle.setWrapText(true);

        CellStyle smallStyle = wb.createCellStyle();
        smallStyle.cloneStyleFrom(cellStyle);
        smallStyle.setWrapText(true);
        XSSFFont smallFont = wb.createFont();
        smallFont.setFontHeightInPoints((short) 7);
        smallStyle.setFont(smallFont);

        CellStyle descStyle = wb.createCellStyle();
        descStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        descStyle.setWrapText(true);
        XSSFFont descFont = wb.createFont();
        descFont.setItalic(true);
        descStyle.setFont(descFont);

        CellStyle mergeStyle = wb.createCellStyle();
        mergeStyle.setBorderBottom(BorderStyle.THIN);
        mergeStyle.setBorderTop(BorderStyle.THIN);
        mergeStyle.setBorderLeft(BorderStyle.THIN);
        mergeStyle.setBorderRight(BorderStyle.THIN);
        mergeStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        mergeStyle.setAlignment(HorizontalAlignment.CENTER);

        CellStyle summaryLabelStyle = wb.createCellStyle();
        summaryLabelStyle.cloneStyleFrom(cellStyle);
        summaryLabelStyle.setAlignment(HorizontalAlignment.RIGHT);
        XSSFFont summaryFont = wb.createFont();
        summaryFont.setItalic(true);
        summaryLabelStyle.setFont(summaryFont);

        CellStyle highlightStyle = wb.createCellStyle();
        highlightStyle.cloneStyleFrom(cellStyle);
        highlightStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        highlightStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle boldHighlightStyle = wb.createCellStyle();
        boldHighlightStyle.cloneStyleFrom(highlightStyle);
        XSSFFont boldFont = wb.createFont();
        boldFont.setBold(true);
        boldHighlightStyle.setFont(boldFont);
        boldHighlightStyle.setAlignment(HorizontalAlignment.CENTER);

        return new Styles(headerStyle, cellStyle, centerStyle, wrapStyle, smallStyle,
                descStyle, mergeStyle, summaryLabelStyle, highlightStyle, boldHighlightStyle);
    }

    // ---- Sheet writers ----------------------------------------------------

    private void writeConditionsSheet(Workbook wb,
                                      List<String> conditionOrder,
                                      Map<String, String> animalToCondition,
                                      ConditionAssignments conditionModel,
                                      Set<String> allAnimals,
                                      Styles styles,
                                      Set<String> usedSheetNames) {

        String sheetName = ExcelNameMap.safeSheetName("Experimental Conditions", usedSheetNames);
        Sheet sheet = wb.createSheet(sheetName);

        // One column per condition axis. A single-axis project keeps the exact
        // legacy "Condition" header and layout; a multi-axis project splits the
        // composite into a column per axis (e.g. Genotype, Timepoint).
        List<ConditionAxis> axes = conditionModel == null ? null : conditionModel.axes();
        boolean perAxis = axes != null && axes.size() > 1;

        List<String> headerLabels = new ArrayList<String>();
        if (perAxis) {
            for (ConditionAxis axis : axes) {
                headerLabels.add(axis.label == null || axis.label.trim().isEmpty()
                        ? axis.id : axis.label);
            }
        } else {
            headerLabels.add("Condition");
        }
        headerLabels.add("Animals");
        headerLabels.add(RunIdCsv.RUN_ID_COLUMN);

        Row headerRow = sheet.createRow(0);
        for (int c = 0; c < headerLabels.size(); c++) {
            Cell cell = headerRow.createCell(c);
            cell.setCellValue(headerLabels.get(c));
            cell.setCellStyle(styles.headerStyle);
        }

        int animalsCol = headerLabels.size() - 2;
        int runCol = headerLabels.size() - 1;

        int rowIdx = 1;
        for (String cond : conditionOrder) {
            List<String> animalsInCond = new ArrayList<String>();
            for (String animal : allAnimals) {
                if (cond.equals(animalToCondition.get(animal))) {
                    animalsInCond.add(animal);
                }
            }

            Row row = sheet.createRow(rowIdx++);
            if (perAxis) {
                // Per-axis values are shared by every animal in a composite group;
                // read them off the first member (composite is a function of axes).
                String sample = animalsInCond.isEmpty() ? null : animalsInCond.get(0);
                for (int a = 0; a < axes.size(); a++) {
                    Cell axisCell = row.createCell(a);
                    String value = sample == null ? "" : conditionModel.get(sample, axes.get(a).id);
                    setTextCellValue(axisCell, value);
                    axisCell.setCellStyle(styles.centerStyle);
                }
            } else {
                Cell condCell = row.createCell(0);
                setTextCellValue(condCell, cond);
                condCell.setCellStyle(styles.centerStyle);
            }

            Cell animalsCell = row.createCell(animalsCol);
            setTextCellValue(animalsCell, join(animalsInCond, ", "));
            animalsCell.setCellStyle(styles.cellStyle);

            Cell runCell = row.createCell(runCol);
            setTextCellValue(runCell, currentRunId());
            runCell.setCellStyle(styles.cellStyle);
        }

        if (perAxis) {
            for (int a = 0; a < axes.size(); a++) {
                sheet.setColumnWidth(a, 18 * 256);
            }
        } else {
            sheet.setColumnWidth(0, 20 * 256);
        }
        sheet.setColumnWidth(animalsCol, 60 * 256);
        sheet.setColumnWidth(runCol, 28 * 256);
    }

    private void writeDataSummarySheet(Workbook wb,
                                       List<String> metricColumns,
                                       Map<String, Map<String, String>> detailsPerMarker,
                                       Styles styles,
                                       Set<String> usedSheetNames) {

        String sheetName = ExcelNameMap.safeSheetName("Data Summary", usedSheetNames);
        Sheet sheet = wb.createSheet(sheetName);

        CellStyle tipStyle = wb.createCellStyle();
        tipStyle.setWrapText(true);
        tipStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        XSSFFont tipFont = ((XSSFWorkbook) wb).createFont();
        tipFont.setItalic(true);
        tipFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
        tipStyle.setFont(tipFont);

        Row tipRow = sheet.createRow(0);
        Cell tipCell = tipRow.createCell(0);
        tipCell.setCellValue("Tip: Use View → Navigation (Ctrl+F5) to search for specific columns across all sheets.");
        tipCell.setCellStyle(tipStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 5));
        tipRow.setHeightInPoints(32);

        Row headerRow = sheet.createRow(1);
        String[] headers = {"Marker", "Analysis", "Data", "Filter Macro", "Analysis Macro",
                RunIdCsv.RUN_ID_COLUMN};
        for (int c = 0; c < headers.length; c++) {
            Cell cell = headerRow.createCell(c);
            cell.setCellValue(headers[c]);
            cell.setCellStyle(styles.headerStyle);
        }

        LinkedHashMap<String, List<String>> markerBuckets = new LinkedHashMap<String, List<String>>();
        for (String col : metricColumns) {
            String marker = ExcelNameMap.extractMarker(col);
            if (marker == null && includeTextureFeatureColumn(col)) {
                marker = textureFeatureMarker(col);
            }
            if (marker == null) continue;
            String analysis = ExcelNameMap.isRoiColumn(col) ? "ROI" : "Object";
            String key = marker + "|" + analysis;

            List<String> labels = markerBuckets.get(key);
            if (labels == null) {
                labels = new ArrayList<String>();
                markerBuckets.put(key, labels);
            }

            String[] nameDesc = metricNameDesc(col);
            if (nameDesc != null) labels.add(nameDesc[0]);
        }

        int rowIdx = 2;
        String lastMarker = "";
        for (Map.Entry<String, List<String>> entry : markerBuckets.entrySet()) {
            String[] keyParts = entry.getKey().split("\\|", 2);
            String marker = keyParts[0];
            String analysis = keyParts[1];
            List<String> labels = entry.getValue();

            Row row = sheet.createRow(rowIdx++);

            Cell markerCell = row.createCell(0);
            if (!marker.equals(lastMarker)) {
                setTextCellValue(markerCell, marker);
                lastMarker = marker;
            }
            markerCell.setCellStyle(styles.mergeStyle);

            Cell analysisCell = row.createCell(1);
            setTextCellValue(analysisCell, analysis);
            analysisCell.setCellStyle(styles.centerStyle);

            Cell dataCell = row.createCell(2);
            setTextCellValue(dataCell, join(labels, ", "));
            dataCell.setCellStyle(styles.wrapStyle);

            Map<String, String> details = detailsPerMarker.get(entry.getKey());
            String filterMacro = "";
            String analysisMacro = "";
            if (details != null) {
                filterMacro = nullSafe(details.get("Filter Macro"));
                if (filterMacro.isEmpty()) {
                    filterMacro = nullSafe(details.get("Macro"));
                }
                analysisMacro = nullSafe(details.get("Analysis Macro"));
            }

            Cell filterCell = row.createCell(3);
            setTextCellValue(filterCell, filterMacro);
            filterCell.setCellStyle(styles.smallStyle);

            Cell analysisMacroCell = row.createCell(4);
            setTextCellValue(analysisMacroCell, analysisMacro);
            analysisMacroCell.setCellStyle(styles.smallStyle);

            Cell runCell = row.createCell(5);
            setTextCellValue(runCell, currentRunId());
            runCell.setCellStyle(styles.cellStyle);
        }

        sheet.setColumnWidth(0, 16 * 256);
        sheet.setColumnWidth(1, 10 * 256);
        sheet.setColumnWidth(2, 40 * 256);
        sheet.setColumnWidth(3, 50 * 256);
        sheet.setColumnWidth(4, 50 * 256);
        sheet.setColumnWidth(5, 28 * 256);
    }

    private void writePerMetricSheet(Workbook wb,
                                     String col,
                                     String[] nameDesc,
                                     List<String> conditionOrder,
                                     Map<String, String> animalToCondition,
                                     Set<String> allAnimals,
                                     LinkedHashMap<String, Map<String, Double>> mergedData,
                                     Styles styles,
                                     Set<String> usedSheetNames) {
        String sheetName = ExcelNameMap.safeSheetName(nameDesc[0], usedSheetNames);
        Sheet sheet = wb.createSheet(sheetName);

        List<List<Double>> conditionValues = new ArrayList<List<Double>>();
        for (String cond : conditionOrder) {
            List<Double> vals = new ArrayList<Double>();
            for (String animal : allAnimals) {
                if (cond.equals(animalToCondition.get(animal))) {
                    Map<String, Double> row = mergedData.get(animal);
                    if (row != null && row.containsKey(col)) {
                        vals.add(row.get(col));
                    }
                }
            }
            conditionValues.add(vals);
        }

        Row headerRow = sheet.createRow(0);
        for (int c = 0; c < conditionOrder.size(); c++) {
            Cell cell = headerRow.createCell(c);
            setTextCellValue(cell, conditionOrder.get(c));
            cell.setCellStyle(styles.headerStyle);
            sheet.setColumnWidth(c, Math.max(conditionOrder.get(c).length() + 4, 12) * 256);
        }

        ExcelExportPreset.MetricSheetDetail mode = preset.getMetricSheetDetail();
        int nextRow = 1;
        if (mode == ExcelExportPreset.MetricSheetDetail.SUMMARY_STATISTICS
                || mode == ExcelExportPreset.MetricSheetDetail.BOTH) {
            nextRow = writeSummaryBlock(sheet, nextRow, conditionValues, styles);
            nextRow += 1;
        }

        if (mode == ExcelExportPreset.MetricSheetDetail.RAW_VALUES
                || mode == ExcelExportPreset.MetricSheetDetail.BOTH) {
            int maxRows = 0;
            for (List<Double> vals : conditionValues) {
                maxRows = Math.max(maxRows, vals.size());
            }
            for (int r = 0; r < maxRows; r++) {
                Row dataRow = sheet.createRow(nextRow + r);
                for (int c = 0; c < conditionValues.size(); c++) {
                    List<Double> vals = conditionValues.get(c);
                    if (r < vals.size()) {
                        Cell cell = dataRow.createCell(c);
                        cell.setCellValue(vals.get(r));
                        cell.setCellStyle(styles.cellStyle);
                    }
                }
            }
            nextRow += maxRows;
        }

        String desc = nameDesc[1];
        if (desc != null && !desc.isEmpty() && conditionOrder.size() > 0) {
            int descRowIdx = nextRow + 1;
            Row descRow = sheet.createRow(descRowIdx);
            Cell descCell = descRow.createCell(0);
            setTextCellValue(descCell, desc);
            descCell.setCellStyle(styles.descStyle);
            if (conditionOrder.size() > 1) {
                sheet.addMergedRegion(new CellRangeAddress(
                        descRowIdx, descRowIdx, 0, conditionOrder.size() - 1));
            }
            int nLines = Math.max(countChar(desc, '\n') + 1, desc.length() / 80 + 1);
            descRow.setHeightInPoints(18 * nLines);
        }
    }

    /**
     * Writes a five-row summary block (n / mean / SEM / median / IQR) starting
     * at {@code startRow}. Returns the next free row index.
     */
    private int writeSummaryBlock(Sheet sheet, int startRow,
                                  List<List<Double>> conditionValues, Styles styles) {
        String[] labels = {"n", "mean", "SEM", "median", "IQR"};
        for (int r = 0; r < labels.length; r++) {
            Row row = sheet.createRow(startRow + r);
            for (int c = 0; c < conditionValues.size(); c++) {
                ExcelMetricSummariser.Summary summary = ExcelMetricSummariser.summarise(conditionValues.get(c));
                Cell cell = row.createCell(c);
                if (summary.isEmpty()) {
                    cell.setCellStyle(styles.cellStyle);
                    continue;
                }
                double value;
                switch (r) {
                    case 0: value = summary.count; break;
                    case 1: value = summary.mean; break;
                    case 2: value = summary.sem; break;
                    case 3: value = summary.median; break;
                    case 4: default: value = summary.iqr(); break;
                }
                if (Double.isNaN(value)) {
                    cell.setCellStyle(styles.cellStyle);
                } else {
                    cell.setCellValue(value);
                    cell.setCellStyle(r == 0 ? styles.centerStyle : styles.cellStyle);
                }
            }
            Cell labelCell = row.createCell(Math.max(1, conditionValues.size()));
            setTextCellValue(labelCell, labels[r]);
            labelCell.setCellStyle(styles.summaryLabelStyle);
        }
        return startRow + labels.length;
    }

    private void writeStatisticsSheet(XSSFWorkbook wb, File statisticsCsv,
                                      Styles styles, Set<String> usedSheetNames) {
        List<String[]> rows = new ArrayList<String[]>();
        String[] csvHeaders = null;

        AnalysisRunContext.InputHandle inputHandle = recordInputStart(statisticsCsv);
        long inputStarted = System.currentTimeMillis();
        String inputStatus = "processed";
        try (CsvSupport.RecordReader csv = CsvSupport.openRecordReader(statisticsCsv)) {
            CsvSupport.Record headerRecord = csv.readRecord();
            if (headerRecord == null) {
                inputStatus = "skipped";
                return;
            }
            csvHeaders = CsvSupport.parseRecord(headerRecord.text);

            CsvSupport.Record record;
            while ((record = csv.readRecord()) != null) {
                if (CsvSupport.isBlankRecord(record.text)) continue;
                rows.add(CsvSupport.parseRecord(record.text));
            }
        } catch (IOException e) {
            IJ.log("Error reading statistics CSV: " + e.getMessage());
            inputStatus = "failed";
            recordError("Error reading statistics CSV", e);
            return;
        } finally {
            recordInputEnd(inputHandle, inputStatus, inputStarted);
        }

        if (csvHeaders == null || rows.isEmpty()) return;

        String sheetName = ExcelNameMap.safeSheetName("Statistics", usedSheetNames);
        Sheet sheet = wb.createSheet(sheetName);

        int pValueIdx = -1;
        int significantIdx = -1;
        for (int c = 0; c < csvHeaders.length; c++) {
            String h = csvHeaders[c].trim();
            if (h.equalsIgnoreCase("p-value") || h.equalsIgnoreCase("pvalue")
                    || h.equalsIgnoreCase("p_value")) {
                pValueIdx = c;
            }
            if (h.equalsIgnoreCase("Significant")) {
                significantIdx = c;
            }
        }

        boolean stars = preset.isSignificanceStars();
        int starsColumn = stars ? csvHeaders.length : -1;

        Row headerRow = sheet.createRow(0);
        for (int c = 0; c < csvHeaders.length; c++) {
            Cell cell = headerRow.createCell(c);
            setTextCellValue(cell, csvHeaders[c].trim());
            cell.setCellStyle(styles.headerStyle);
        }
        if (stars) {
            Cell cell = headerRow.createCell(starsColumn);
            setTextCellValue(cell, "Stars");
            cell.setCellStyle(styles.headerStyle);
        }

        Map<Integer, CellStyle> gradientCache = new HashMap<Integer, CellStyle>();
        int unparseable = 0;

        for (int r = 0; r < rows.size(); r++) {
            String[] rowData = rows.get(r);
            Row row = sheet.createRow(r + 1);

            double pValue = Double.NaN;
            if (pValueIdx >= 0 && pValueIdx < rowData.length) {
                pValue = parseDoubleOrNaN(rowData[pValueIdx]);
            }
            boolean isSignificant = (!Double.isNaN(pValue) && pValue < 0.05);
            if (!isSignificant && significantIdx >= 0 && significantIdx < rowData.length) {
                if (rowData[significantIdx].trim().contains("*")) {
                    isSignificant = true;
                }
            }

            ExcelExportPreset.SignificanceHighlight highlightMode = preset.getSignificanceHighlight();
            CellStyle gradientStyle = null;
            if (highlightMode == ExcelExportPreset.SignificanceHighlight.P_GRADIENT
                    && !Double.isNaN(pValue)) {
                gradientStyle = gradientStyleFor(wb, styles, pValue, gradientCache);
            }

            for (int c = 0; c < rowData.length; c++) {
                Cell cell = row.createCell(c);
                String val = rowData[c].trim();

                boolean written = false;
                if (!val.isEmpty()) {
                    try {
                        double numVal = Double.parseDouble(val);
                        cell.setCellValue(numVal);
                        written = true;
                    } catch (NumberFormatException nfe) {
                        unparseable++;
                    }
                }
                if (!written) {
                    setTextCellValue(cell, val);
                }

                CellStyle baseCentered = (c == significantIdx || c == pValueIdx)
                        ? styles.centerStyle : styles.cellStyle;
                if (highlightMode == ExcelExportPreset.SignificanceHighlight.YELLOW && isSignificant) {
                    if (c == significantIdx && val.contains("*")) {
                        cell.setCellStyle(styles.boldHighlightStyle);
                    } else {
                        cell.setCellStyle(styles.highlightStyle);
                    }
                } else if (highlightMode == ExcelExportPreset.SignificanceHighlight.P_GRADIENT
                        && gradientStyle != null) {
                    cell.setCellStyle(gradientStyle);
                } else {
                    cell.setCellStyle(baseCentered);
                }
            }

            if (stars) {
                Cell starCell = row.createCell(starsColumn);
                String starText = starsFor(pValue);
                setTextCellValue(starCell, starText);
                starCell.setCellStyle(styles.centerStyle);
            }
        }

        for (int c = 0; c < csvHeaders.length; c++) {
            int width = Math.max(csvHeaders[c].trim().length() + 4, 14) * 256;
            if (width > 50 * 256) width = 50 * 256;
            sheet.setColumnWidth(c, width);
        }
        if (stars) {
            sheet.setColumnWidth(starsColumn, 10 * 256);
        }

        if (unparseable > 0) {
            IJ.log("[Excel] Skipped " + unparseable
                    + " unparseable cells in " + statisticsCsv.getName());
            recordWarn("[Excel] Skipped " + unparseable
                    + " unparseable cells in " + statisticsCsv.getName());
        }
    }

    private void writeMethodsAppendixSheet(Workbook wb,
                                           Map<String, Map<String, String>> detailsPerMarker,
                                           Styles styles,
                                           Set<String> usedSheetNames) {
        String sheetName = ExcelNameMap.safeSheetName("Methods Appendix", usedSheetNames);
        Sheet sheet = wb.createSheet(sheetName);

        Row headerRow = sheet.createRow(0);
        String[] headers = {"Marker", "Analysis Type", "Source File", "Section", "Content",
                RunIdCsv.RUN_ID_COLUMN};
        for (int c = 0; c < headers.length; c++) {
            Cell cell = headerRow.createCell(c);
            cell.setCellValue(headers[c]);
            cell.setCellStyle(styles.headerStyle);
        }

        int rowIdx = 1;
        if (detailsPerMarker.isEmpty()) {
            Row empty = sheet.createRow(rowIdx);
            Cell cell = empty.createCell(0);
            setTextCellValue(cell, "No Analysis Details files were found.");
            cell.setCellStyle(styles.descStyle);
            Cell runCell = empty.createCell(5);
            setTextCellValue(runCell, currentRunId());
            runCell.setCellStyle(styles.cellStyle);
        } else {
            for (Map.Entry<String, Map<String, String>> entry : detailsPerMarker.entrySet()) {
                String[] keyParts = entry.getKey().split("\\|", 2);
                String marker = keyParts[0];
                String analysisType = keyParts.length > 1 ? keyParts[1] : "";
                Map<String, String> details = entry.getValue();
                String sourceFile = nullSafe(details.get("_sourceFile"));
                List<String> keys = new ArrayList<String>(details.keySet());
                // Stable ordering: drop private underscore-prefixed keys.
                for (String key : keys) {
                    if (key.startsWith("_")) continue;
                    Row row = sheet.createRow(rowIdx++);
                    Cell markerCell = row.createCell(0);
                    setTextCellValue(markerCell, marker);
                    markerCell.setCellStyle(styles.mergeStyle);

                    Cell analysisCell = row.createCell(1);
                    setTextCellValue(analysisCell, analysisType);
                    analysisCell.setCellStyle(styles.centerStyle);

                    Cell sourceCell = row.createCell(2);
                    setTextCellValue(sourceCell, sourceFile);
                    sourceCell.setCellStyle(styles.cellStyle);

                    Cell sectionCell = row.createCell(3);
                    setTextCellValue(sectionCell, key);
                    sectionCell.setCellStyle(styles.cellStyle);

                    Cell contentCell = row.createCell(4);
                    setTextCellValue(contentCell, nullSafe(details.get(key)));
                    contentCell.setCellStyle(styles.smallStyle);

                    Cell runCell = row.createCell(5);
                    setTextCellValue(runCell, currentRunId());
                    runCell.setCellStyle(styles.cellStyle);
                }
            }
        }

        sheet.setColumnWidth(0, 16 * 256);
        sheet.setColumnWidth(1, 14 * 256);
        sheet.setColumnWidth(2, 32 * 256);
        sheet.setColumnWidth(3, 20 * 256);
        sheet.setColumnWidth(4, 80 * 256);
        sheet.setColumnWidth(5, 28 * 256);
    }

    // ---- Styling helpers --------------------------------------------------

    private static double parseDoubleOrNaN(String text) {
        if (text == null) return Double.NaN;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return Double.NaN;
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /**
     * Returns {@code *}/{@code **}/{@code ***}/{@code ****} based on
     * conventional p-value thresholds. Empty string for non-significant or
     * unparseable p-values.
     */
    static String starsFor(double pValue) {
        if (Double.isNaN(pValue)) return "";
        if (pValue < 0.0001) return "****";
        if (pValue < 0.001) return "***";
        if (pValue < 0.01) return "**";
        if (pValue < 0.05) return "*";
        return "ns";
    }

    /**
     * Computes the red-gradient fill colour for a p-value on a log scale.
     * p=1.0 returns pure white (255,255,255); p<=0.001 returns deep red.
     */
    static byte[] pGradientRgb(double pValue) {
        if (Double.isNaN(pValue) || pValue >= 1.0) {
            return new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        }
        double bounded = Math.max(pValue, 1e-6);
        double logRatio = Math.log10(bounded) / Math.log10(0.001);
        double intensity = Math.min(1.0, Math.max(0.0, logRatio));
        int red = 255;
        int greenBlue = (int) Math.round(255.0 - intensity * (255.0 - 30.0));
        if (greenBlue < 0) greenBlue = 0;
        if (greenBlue > 255) greenBlue = 255;
        return new byte[] {(byte) red, (byte) greenBlue, (byte) greenBlue};
    }

    private static CellStyle gradientStyleFor(XSSFWorkbook wb, Styles styles, double pValue,
                                              Map<Integer, CellStyle> cache) {
        byte[] rgb = pGradientRgb(pValue);
        int key = ((rgb[0] & 0xFF) << 16) | ((rgb[1] & 0xFF) << 8) | (rgb[2] & 0xFF);
        CellStyle existing = cache.get(Integer.valueOf(key));
        if (existing != null) return existing;
        XSSFCellStyle style = (XSSFCellStyle) wb.createCellStyle();
        style.cloneStyleFrom(styles.cellStyle);
        style.setFillForegroundColor(new XSSFColor(rgb, null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        cache.put(Integer.valueOf(key), style);
        return style;
    }

    // ---- Helpers ----------------------------------------------------------

    private AnalysisRunContext.InputHandle recordInputStart(File file) {
        if (runRecordContext == null || file == null) {
            return null;
        }
        return runRecordContext.recordInputStart(file, -1, null);
    }

    private void recordInputEnd(AnalysisRunContext.InputHandle inputHandle,
                                String status,
                                long startedAtMillis) {
        if (runRecordContext != null && inputHandle != null) {
            runRecordContext.recordInputEnd(inputHandle, status,
                    Math.max(0L, System.currentTimeMillis() - startedAtMillis));
        }
    }

    private void recordOutput(File file, String kind) {
        if (runRecordContext != null && file != null) {
            runRecordContext.recordOutput(file, kind);
        }
    }

    private String currentRunId() {
        return RunIdCsv.runId(runRecordContext);
    }

    private void recordWarn(String message) {
        if (runRecordContext != null) {
            runRecordContext.warn(message);
        }
    }

    private void recordError(String message, Throwable t) {
        if (runRecordContext != null) {
            runRecordContext.error(message, t);
        }
    }

    private static String safeGet(String[] arr, int idx) {
        if (idx < 0 || idx >= arr.length) return "";
        return arr[idx];
    }

    private static boolean isRunLineageColumn(String column) {
        if (column == null) return false;
        String cleaned = column.trim();
        return RunIdCsv.RUN_ID_COLUMN.equals(cleaned)
                || RunIdCsv.SOURCE_RUN_ID_COLUMN.equals(cleaned);
    }

    private static String join(List<String> parts, String delimiter) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(delimiter);
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) count++;
        }
        return count;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static void setTextCellValue(Cell cell, String value) {
        cell.setCellValue(excelSafeText(value));
    }

    static String excelSafeText(String value) {
        if (value == null || value.isEmpty()) return value == null ? "" : value;
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@'
                || first == '\t' || first == '\r' || first == '\n') {
            return "'" + value;
        }
        return value;
    }
}
