package flash.pipeline.analyses;

import flash.pipeline.bin.BinField;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.ImageCache;
import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.naming.ImageNameParser;
import flash.pipeline.project.ProjectFile;
import flash.pipeline.project.ProjectFileIO;
import flash.pipeline.representative.ConditionLayoutChooser;
import flash.pipeline.representative.RepresentativeFigureConfig;
import flash.pipeline.representative.RepresentativeFigureWriter;
import flash.pipeline.representative.RepresentativePreviewRenderer;
import flash.pipeline.representative.RepresentativeRangeStage;
import flash.pipeline.representative.RepresentativeSelection;
import flash.pipeline.representative.RepresentativeSelectionPanel;
import flash.pipeline.representative.RepresentativeStatLoader;
import flash.pipeline.representative.RepresentativeStatTable;
import flash.pipeline.representative.RepresentativeStatistic;
import flash.pipeline.representative.RepresentativeSeries;
import flash.pipeline.report.QualityReport;
import flash.pipeline.results.RepresentativeFigureDetailsWriter;
import flash.pipeline.runrecord.AnalysisRunContext;
import flash.pipeline.runrecord.LoadedRunParameters;
import flash.pipeline.runrecord.RunRecordAware;
import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.wizard.ConditionManifestPanel;
import ij.IJ;

import javax.swing.JComboBox;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scaffold analysis for building a representative image figure.
 */
public class RepresentativeFigureAnalysis implements Analysis, RunRecordAware {
    private final RepresentativeFigureConfig config = new RepresentativeFigureConfig();

    private boolean headless = true;
    private int parallelThreads = 1;
    private ImageCache imageCache = null;
    private boolean useTifCache = false;
    private boolean suppressDialogs = false;
    private AnalysisRunContext runRecordContext = null;
    private ConditionReviewDialog conditionReviewDialog =
            new ConditionReviewDialog() {
                @Override
                public LinkedHashMap<String, String> show(
                        String directory,
                        Set<String> animals,
                        Map<String, String> prefill,
                        String title) {
                    return ConditionManifestPanel.showDialog(
                            directory, animals, prefill, title);
                }
            };

    @Override
    public void execute(String directory) {
        loadRememberedProjectParameters(directory);

        if (headless || suppressDialogs || GraphicsEnvironment.isHeadless()) {
            config.statistic = RepresentativeStatistic.NONE;
            config.existingResult = null;
            config.statTable = new RepresentativeStatTable();
            IJ.log("[Representative Figure] Headed mode is required for representative image selection.");
            return;
        }

        try {
            StatisticChoice choice = showStatisticChooser(directory);
            if (choice == null) {
                IJ.log("[Representative Figure] Statistic chooser cancelled.");
                return;
            }
            if (!reviewConditionAssignments(directory)) {
                IJ.log("[Representative Figure] Condition assignment review cancelled.");
                return;
            }

            config.statistic = choice.statistic;
            config.existingResult = choice.existingResult;
            config.statTable = RepresentativeStatLoader.load(
                    directory, choice.statistic, choice.existingResult, parallelThreads);

            IJ.log("[Representative Figure] Loaded statistic source: "
                    + choice.statistic.label() + " (" + statTableSummary(config.statTable) + ").");

            List<RepresentativeSeries> previewSeries = RepresentativePreviewRenderer.render(
                    directory, config, imageCache, parallelThreads, useTifCache);
            if (previewSeries.isEmpty()) {
                IJ.log("[Representative Figure] No image series were available for representative selection.");
                IJ.error("Representative Figure",
                        "No image series were available for representative selection.");
                return;
            }

            RepresentativeSelection selection = showSelectionGrid(previewSeries);
            if (selection == null) {
                IJ.log("[Representative Figure] Representative selection cancelled.");
                return;
            }

            config.selection = selection;
            IJ.log("[Representative Figure] Locked representatives for "
                    + selection.size() + " condition"
                    + (selection.size() == 1 ? "" : "s") + ".");

            BinConfig setupConfig = BinConfigIO.readPartialFromDirectory(directory);
            DisplayRangeChoice rangeChoice = chooseDisplayRangeMode(setupConfig, selection);
            if (rangeChoice == null) {
                IJ.log("[Representative Figure] Display-range setup cancelled.");
                return;
            }
            if (rangeChoice.adjustNow) {
                RepresentativeRangeStage rangeStage = new RepresentativeRangeStage();
                if (!rangeStage.run(directory, config, setupConfig, imageCache, useTifCache)) {
                    IJ.log("[Representative Figure] Display-range adjustment cancelled.");
                    return;
                }
                IJ.log("[Representative Figure] Locked custom display ranges for "
                        + config.customDisplayRangesByChannel.size() + " channel"
                        + (config.customDisplayRangesByChannel.size() == 1 ? "" : "s") + ".");
            } else if (rangeChoice.useSetupRanges) {
                config.clearCustomDisplayRanges();
                IJ.log("[Representative Figure] Using display ranges from Set Up Configuration.");
            } else {
                IJ.log("[Representative Figure] Using remembered representative display ranges.");
            }

            ConditionLayoutChooser.Result layoutResult =
                    new ConditionLayoutChooser().show(config);
            if (layoutResult == null) {
                IJ.log("[Representative Figure] Condition layout chooser cancelled.");
                return;
            }
            config.layout = layoutResult.layout();
            config.tileConfig = layoutResult.tileConfig();
            IJ.log("[Representative Figure] Locked layout with "
                    + config.layout.rowCount() + " row"
                    + (config.layout.rowCount() == 1 ? "" : "s")
                    + " and " + config.layout.conditionCount() + " condition"
                    + (config.layout.conditionCount() == 1 ? "" : "s") + ".");
            File output = new RepresentativeFigureWriter().write(
                    directory, config, imageCache, parallelThreads, useTifCache);
            IJ.log("[Representative Figure] Representative figure written: "
                    + output.getAbsolutePath());
            persistCompletedRun(directory, setupConfig, output);
        } catch (Exception e) {
            IJ.log("[Representative Figure] Could not prepare representative selection: "
                    + e.getMessage());
            IJ.error("Representative Figure",
                    "Could not prepare representative selection:\n" + e.getMessage());
        }
    }

    @Override
    public Set<BinField> requiredBinFields() {
        return EnumSet.noneOf(BinField.class);
    }

    @Override
    public boolean benefitsFromRois() {
        return false;
    }

    @Override
    public boolean requiresHeadedMode() {
        return true;
    }

    @Override
    public void setHeadless(boolean headless) {
        this.headless = headless;
    }

    @Override
    public void setVerboseLogging(boolean verbose) {
    }

    @Override
    public void setSkipExisting(boolean skip) {
    }

    @Override
    public void setParallelThreads(int threads) {
        this.parallelThreads = Math.max(1, threads);
    }

    @Override
    public void setImageCache(ImageCache cache) {
        this.imageCache = cache;
    }

    @Override
    public void setLoaderThreads(int threads) {
    }

    @Override
    public void setLoaderPercent(int percent) {
    }

    @Override
    public void setUseTifCache(boolean use) {
        this.useTifCache = use;
    }

    @Override
    public void setQualityReport(QualityReport report) {
    }

    @Override
    public void setSuppressDialogs(boolean suppress) {
        this.suppressDialogs = suppress;
    }

    @Override
    public void setCliConfig(CLIConfig config) {
    }

    @Override
    public void setRunRecordContext(AnalysisRunContext context) {
        this.runRecordContext = context;
    }

    public LoadedRunParameters.Result applyLoadedParameters(Map<String, Object> parameters) {
        Map<String, Object> block = representativeFigureBlock(parameters);
        if (!block.isEmpty()) {
            config.applyMap(block);
        }
        LoadedRunParameters.Result result = LoadedRunParameters.resultForKnownKeys(
                parameters, Collections.singleton(RepresentativeFigureConfig.PROJECT_EXTRA_KEY));
        LoadedRunParameters.rememberLastResult(result);
        return result;
    }

    RepresentativeFigureConfig configForTests() {
        return config;
    }

    void setConditionReviewDialogForTests(ConditionReviewDialog dialog) {
        conditionReviewDialog = dialog;
    }

    void persistCompletedRun(String directory, BinConfig setupConfig, File output) throws Exception {
        Map<String, Object> representative = config.toMap();
        representative.put("lastOutputPng", output == null ? "" : output.getAbsolutePath());
        Map<String, Object> parameters = new LinkedHashMap<String, Object>();
        parameters.put(RepresentativeFigureConfig.PROJECT_EXTRA_KEY, representative);

        File projectJson = writeProjectExtras(directory, representative);
        File details = RepresentativeFigureDetailsWriter.write(
                new File(directory), config, setupConfig, output);

        if (runRecordContext != null) {
            recordSelectedInputs();
            runRecordContext.recordParameters(parameters);
            if (output != null) {
                runRecordContext.recordOutput(output, "png");
            }
            if (details != null) {
                runRecordContext.recordOutput(details, "txt");
            }
            if (projectJson != null) {
                runRecordContext.info("Representative figure settings saved to "
                        + projectJson.getAbsolutePath());
            }
            if (output != null) {
                runRecordContext.info("Representative figure written to "
                        + output.getAbsolutePath());
            }
        }
    }

    private void loadRememberedProjectParameters(String directory) {
        if (directory == null || directory.trim().isEmpty()) {
            return;
        }
        try {
            ProjectFile project = ProjectFileIO.read(
                    FlashProjectLayout.forDirectory(directory).configurationWriteDir());
            if (project != null && project.extras != null
                    && project.extras.containsKey(RepresentativeFigureConfig.PROJECT_EXTRA_KEY)) {
                applyLoadedParameters(project.extras);
            }
        } catch (RuntimeException e) {
            IJ.log("[Representative Figure] Could not load remembered figure settings: "
                    + e.getMessage());
        }
    }

    private File writeProjectExtras(String directory,
                                    Map<String, Object> representative) throws IOException {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        File settingsDir = layout.configurationWriteDir();
        boolean existingFile = ProjectFileIO.exists(settingsDir);
        ProjectFile project = ProjectFileIO.read(settingsDir);
        if (project == null && existingFile) {
            throw new IOException("Cannot preserve existing project.json; file could not be read.");
        }
        if (project == null) {
            project = new ProjectFile();
            project.name = layout.projectRoot().getName();
            project.outputRoot = layout.projectRoot().getAbsolutePath();
        }
        project.writerId = "FLASH";
        project.writtenAtMillis = System.currentTimeMillis();
        if (project.extras == null) {
            project.extras = new LinkedHashMap<String, Object>();
        }
        project.extras.put(RepresentativeFigureConfig.PROJECT_EXTRA_KEY, representative);
        ProjectFileIO.write(settingsDir, project);
        return new File(settingsDir, ProjectFileIO.FILE_NAME);
    }

    private void recordSelectedInputs() {
        if (runRecordContext == null || config.selection == null) {
            return;
        }
        for (RepresentativeSeries series : config.selection.series()) {
            if (series == null) {
                continue;
            }
            ProjectFile.Item item = new ProjectFile.Item();
            item.animalId = series.animal();
            item.hemisphere = series.hemisphere();
            item.region = series.region();
            item.condition = series.condition();
            AnalysisRunContext.InputHandle input =
                    runRecordContext.recordInputStart(
                            series.sourcePath(), series.seriesIndex(), item);
            runRecordContext.recordInputEnd(input, "processed", 0L);
        }
    }

    private static Map<String, Object> representativeFigureBlock(
            Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return Collections.emptyMap();
        }
        Object block = parameters.get(RepresentativeFigureConfig.PROJECT_EXTRA_KEY);
        if (block instanceof Map<?, ?>) {
            return stringObjectMap(block);
        }
        if (parameters.containsKey("lockedSeries")
                || parameters.containsKey("conditionNames")
                || parameters.containsKey("customDisplayRanges")) {
            return stringObjectMap(parameters);
        }
        return Collections.emptyMap();
    }

    private static Map<String, Object> stringObjectMap(Object value) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (!(value instanceof Map<?, ?>)) {
            return out;
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private StatisticChoice showStatisticChooser(String directory) {
        List<RepresentativeStatLoader.ExistingResultOption> existingOptions =
                RepresentativeStatLoader.discoverExistingResultOptions(directory);
        LinkedHashMap<String, RepresentativeStatLoader.ExistingResultOption> existingByLabel =
                new LinkedHashMap<String, RepresentativeStatLoader.ExistingResultOption>();
        String[] existingLabels;
        if (existingOptions.isEmpty()) {
            existingLabels = new String[]{RepresentativeStatLoader.NO_EXISTING_RESULT_OPTION};
        } else {
            existingLabels = new String[existingOptions.size()];
            for (int i = 0; i < existingOptions.size(); i++) {
                RepresentativeStatLoader.ExistingResultOption option = existingOptions.get(i);
                existingLabels[i] = option.label;
                existingByLabel.put(option.label, option);
            }
        }
        String defaultExistingLabel = defaultExistingResultLabel(existingLabels, existingOptions);

        PipelineDialog dialog = new PipelineDialog(
                "Representative Image Figure - Statistic", PipelineDialog.Phase.EXPORT);
        dialog.addHeader("Guiding Statistic");
        dialog.addMessage("Choose how FLASH should compute the statistic shown beside images during representative selection.");
        final JComboBox<String> statisticChoice = dialog.addChoice("Statistic source",
                RepresentativeStatistic.labels(),
                config.statistic == null ? RepresentativeStatistic.QUICK.label() : config.statistic.label());
        final JComboBox<String> existingChoice = dialog.addChoice(
                "Existing result column", existingLabels, defaultExistingLabel);
        final boolean hasExistingOptions = !existingOptions.isEmpty();
        updateExistingResultChoiceEnabled(
                statisticChoice, existingChoice, hasExistingOptions);
        statisticChoice.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                updateExistingResultChoiceEnabled(
                        statisticChoice, existingChoice, hasExistingOptions);
            }
        });
        dialog.addHelpText("Existing result is only used when Statistic source is set to Existing result.");
        dialog.setPrimaryButtonText("Continue");

        if (!dialog.showDialog()) {
            return null;
        }

        RepresentativeStatistic statistic =
                RepresentativeStatistic.fromLabel(dialog.getNextChoice());
        String existingLabel = dialog.getNextChoice();
        RepresentativeStatLoader.ExistingResultOption existing = existingByLabel.get(existingLabel);
        if (statistic == RepresentativeStatistic.EXISTING_RESULT && existing == null) {
            throw new IllegalStateException("No numeric existing result column is available.");
        }
        return new StatisticChoice(statistic, existing);
    }

    static void updateExistingResultChoiceEnabled(JComboBox<String> statisticChoice,
                                                  JComboBox<String> existingChoice,
                                                  boolean hasExistingOptions) {
        if (existingChoice == null) {
            return;
        }
        RepresentativeStatistic statistic = RepresentativeStatistic.fromLabel(
                statisticChoice == null || statisticChoice.getSelectedItem() == null
                        ? null
                        : statisticChoice.getSelectedItem().toString());
        existingChoice.setEnabled(
                hasExistingOptions && statistic == RepresentativeStatistic.EXISTING_RESULT);
    }

    private String defaultExistingResultLabel(
            String[] labels,
            List<RepresentativeStatLoader.ExistingResultOption> options) {
        String fallback = labels == null || labels.length == 0
                ? RepresentativeStatLoader.NO_EXISTING_RESULT_OPTION
                : labels[0];
        if (config.existingResult == null || options == null || options.isEmpty()) {
            return fallback;
        }
        for (RepresentativeStatLoader.ExistingResultOption option : options) {
            if (option == null) {
                continue;
            }
            if (sameExistingResult(option, config.existingResult)) {
                return option.label;
            }
        }
        return fallback;
    }

    private static boolean sameExistingResult(
            RepresentativeStatLoader.ExistingResultOption left,
            RepresentativeStatLoader.ExistingResultOption right) {
        if (left == null || right == null) {
            return false;
        }
        return clean(left.columnName).equalsIgnoreCase(clean(right.columnName))
                && clean(left.relativePath).equalsIgnoreCase(clean(right.relativePath));
    }

    private DisplayRangeChoice chooseDisplayRangeMode(BinConfig setupConfig,
                                                      RepresentativeSelection selection) {
        boolean hasRememberedRanges = hasCompleteRememberedRanges(selection);
        boolean hasSetupRanges =
                RepresentativeRangeStage.hasCompleteSetupRanges(setupConfig, selection);
        if (!hasSetupRanges && !hasRememberedRanges) {
            return DisplayRangeChoice.adjustNow();
        }

        PipelineDialog dialog = new PipelineDialog(
                "Representative Image Figure - Display Ranges", PipelineDialog.Phase.EXPORT);
        dialog.addHeader("Display Ranges");
        dialog.addMessage("Choose whether to keep the display ranges from Set Up Configuration or adjust them for this representative figure.");
        String[] choices = hasRememberedRanges
                ? (hasSetupRanges
                        ? new String[]{DisplayRangeChoice.USE_REMEMBERED_LABEL,
                                DisplayRangeChoice.USE_SETUP_LABEL,
                                DisplayRangeChoice.ADJUST_NOW_LABEL}
                        : new String[]{DisplayRangeChoice.USE_REMEMBERED_LABEL,
                                DisplayRangeChoice.ADJUST_NOW_LABEL})
                : new String[]{DisplayRangeChoice.USE_SETUP_LABEL,
                        DisplayRangeChoice.ADJUST_NOW_LABEL};
        dialog.addChoice("Display range setup",
                choices,
                hasRememberedRanges
                        ? DisplayRangeChoice.USE_REMEMBERED_LABEL
                        : DisplayRangeChoice.USE_SETUP_LABEL);
        dialog.setPrimaryButtonText("Continue");

        if (!dialog.showDialog()) {
            return null;
        }
        String choice = dialog.getNextChoice();
        if (DisplayRangeChoice.ADJUST_NOW_LABEL.equals(choice)) {
            return DisplayRangeChoice.adjustNow();
        }
        if (DisplayRangeChoice.USE_REMEMBERED_LABEL.equals(choice)) {
            return DisplayRangeChoice.useRemembered();
        }
        return DisplayRangeChoice.useSetup();
    }

    private boolean hasCompleteRememberedRanges(RepresentativeSelection selection) {
        if (selection == null || config.customDisplayRangesByChannel.isEmpty()) {
            return false;
        }
        boolean sawChannel = false;
        for (RepresentativeSeries series : selection.series()) {
            if (series == null) {
                continue;
            }
            for (RepresentativeSeries.ChannelThumbnail thumbnail : series.channelThumbnails()) {
                if (thumbnail == null) {
                    continue;
                }
                sawChannel = true;
                String token = config.customDisplayRangeForChannel(thumbnail.channelIndex());
                if (!isValidRangeToken(token)) {
                    return false;
                }
            }
        }
        return sawChannel;
    }

    private static boolean isValidRangeToken(String token) {
        String text = clean(token);
        if (text.isEmpty() || "none".equalsIgnoreCase(text)) {
            return false;
        }
        int dash = text.indexOf('-');
        if (dash <= 0 || dash >= text.length() - 1) {
            return false;
        }
        try {
            double min = Double.parseDouble(text.substring(0, dash).trim());
            double max = Double.parseDouble(text.substring(dash + 1).trim());
            return Double.isFinite(min) && Double.isFinite(max) && max > min;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String statTableSummary(RepresentativeStatTable table) {
        if (table == null || table.isEmpty()) {
            return "no statistic values";
        }
        return table.rowCount() + " series, " + table.channelNames().size() + " channel"
                + (table.channelNames().size() == 1 ? "" : "s");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    boolean reviewConditionAssignments(String directory) throws Exception {
        return reviewConditionAssignments(
                directory, ImageSourceDispatcher.readAllMetadata(directory));
    }

    boolean reviewConditionAssignments(String directory, List<SeriesMeta> metas)
            throws Exception {
        LinkedHashSet<String> animals = conditionReviewAnimals(metas);
        if (animals.isEmpty()) {
            IJ.log("[Representative Figure] No source animals were available for condition review.");
            return true;
        }
        LinkedHashMap<String, String> prefill =
                ConditionManifestIO.resolveAssignments(directory, animals);
        LinkedHashMap<String, String> reviewed = conditionReviewDialog == null
                ? null
                : conditionReviewDialog.show(
                        directory,
                        animals,
                        prefill,
                        "Representative Figure - Condition Assignment");
        return reviewed != null;
    }

    static LinkedHashSet<String> conditionReviewAnimals(List<SeriesMeta> metas) {
        LinkedHashSet<String> animals = new LinkedHashSet<String>();
        if (metas == null) {
            return animals;
        }
        for (SeriesMeta meta : metas) {
            if (meta == null || ImageNameParser.isPreviewSeriesName(meta.name)) {
                continue;
            }
            String animal = representativeAnimalName(meta);
            if (!animal.isEmpty()) {
                animals.add(animal);
            }
        }
        return animals;
    }

    private static String representativeAnimalName(SeriesMeta meta) {
        String animal = ConditionManifestIO.extractAnimalName(
                meta == null ? null : meta.name);
        if (!clean(animal).isEmpty()) {
            return clean(animal);
        }
        return "Series" + (meta == null ? 0 : meta.index + 1);
    }

    private RepresentativeSelection showSelectionGrid(List<RepresentativeSeries> previewSeries) {
        PipelineDialog dialog = new PipelineDialog(
                "Representative Image Figure - Select Images", PipelineDialog.Phase.EXPORT);
        dialog.addHeader("Select Representatives");
        final RepresentativeSelectionPanel selectionPanel =
                new RepresentativeSelectionPanel(previewSeries,
                        config.statistic, config.statTable, config.selection);
        dialog.addComponent(selectionPanel);
        dialog.setPrimaryButtonText("Lock in");
        dialog.setPrimaryButtonEnabled(selectionPanel.hasCompleteSelection());
        selectionPanel.addSelectionListener(
                new RepresentativeSelectionPanel.SelectionListener() {
                    @Override
                    public void selectionChanged(
                            RepresentativeSelectionPanel.SelectionEvent event) {
                        dialog.setPrimaryButtonEnabled(event.isComplete());
                    }
                });

        try {
            if (!dialog.showDialog()) {
                return null;
            }
            return selectionPanel.createSelection();
        } finally {
            selectionPanel.dispose();
        }
    }

    interface ConditionReviewDialog {
        LinkedHashMap<String, String> show(String directory,
                                           Set<String> animals,
                                           Map<String, String> prefill,
                                           String title);
    }

    private static final class StatisticChoice {
        final RepresentativeStatistic statistic;
        final RepresentativeStatLoader.ExistingResultOption existingResult;

        StatisticChoice(RepresentativeStatistic statistic,
                        RepresentativeStatLoader.ExistingResultOption existingResult) {
            this.statistic = statistic == null ? RepresentativeStatistic.QUICK : statistic;
            this.existingResult = existingResult;
        }
    }

    private static final class DisplayRangeChoice {
        static final String USE_REMEMBERED_LABEL = "Use remembered representative ranges";
        static final String USE_SETUP_LABEL = "Use display ranges already set up";
        static final String ADJUST_NOW_LABEL = "Adjust now";

        final boolean adjustNow;
        final boolean useSetupRanges;

        private DisplayRangeChoice(boolean adjustNow, boolean useSetupRanges) {
            this.adjustNow = adjustNow;
            this.useSetupRanges = useSetupRanges;
        }

        static DisplayRangeChoice useSetup() {
            return new DisplayRangeChoice(false, true);
        }

        static DisplayRangeChoice useRemembered() {
            return new DisplayRangeChoice(false, false);
        }

        static DisplayRangeChoice adjustNow() {
            return new DisplayRangeChoice(true, false);
        }
    }
}
