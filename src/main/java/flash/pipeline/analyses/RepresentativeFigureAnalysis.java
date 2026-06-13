package flash.pipeline.analyses;

import flash.pipeline.bin.BinField;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.image.DisplayRangeSetting;
import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.ImageCache;
import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.naming.ImageNameParser;
import flash.pipeline.presentation.PresentationTileConfig;
import flash.pipeline.project.ProjectFile;
import flash.pipeline.project.ProjectFileIO;
import flash.pipeline.qc.QcMinMaxPerConditionSelector;
import flash.pipeline.qc.QcSelectionCandidate;
import flash.pipeline.representative.ConditionLayoutChooser;
import flash.pipeline.representative.RepresentativeFigureConfig;
import flash.pipeline.representative.RepresentativeLayout;
import flash.pipeline.representative.RepresentativeFigureWriter;
import flash.pipeline.representative.RepresentativePreviewRenderer;
import flash.pipeline.representative.RepresentativeRangeStage;
import flash.pipeline.representative.RepresentativeSelection;
import flash.pipeline.representative.RepresentativeSelectionPanel;
import flash.pipeline.representative.RepresentativeStatLoader;
import flash.pipeline.representative.RepresentativeStatTable;
import flash.pipeline.representative.RepresentativeStatistic;
import flash.pipeline.representative.RepresentativeStatisticChoicePanel;
import flash.pipeline.representative.RepresentativeSeries;
import flash.pipeline.report.QualityReport;
import flash.pipeline.results.RepresentativeFigureDetailsWriter;
import flash.pipeline.runrecord.AnalysisRunContext;
import flash.pipeline.runrecord.LoadedRunParameters;
import flash.pipeline.runrecord.RunRecordAware;
import flash.pipeline.ui.CardChoice;
import flash.pipeline.ui.FlashTheme;
import flash.pipeline.ui.NextStepLabels;
import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.wizard.ConditionManifestPanel;
import ij.IJ;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
    private CLIConfig cliConfig = null;
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
                            null, directory, animals, prefill, title,
                            NextStepLabels.SELECT_REPRESENTATIVES, null,
                            representativeWorkflow(), 1);
                }
            };

    @Override
    public void execute(String directory) {
        loadRememberedProjectParameters(directory);

        if (headless || suppressDialogs || GraphicsEnvironment.isHeadless()) {
            executeHeadless(directory);
            return;
        }

        try {
            StatisticChoice choice = showStatisticChooser(directory);
            if (choice == null) {
                IJ.log("[Representative Figure] Statistic chooser cancelled.");
                return;
            }
            String rememberedSaveName = config.saveName();
            boolean savedNamedFigure =
                    hasSavedNamedProjectParameters(directory, choice.saveName);
            boolean loadedNamed = savedNamedFigure
                    && loadNamedProjectParametersIfPresent(directory, choice.saveName);
            if (startsFreshNamedFigure(
                    rememberedSaveName, choice.saveName, savedNamedFigure)) {
                config.clearFigureSpecificState();
            }
            config.saveName = choice.saveName;
            applyCliSaveNameOverride();
            List<SeriesMeta> metas = loadSourceMetadata(directory);
            LinkedHashSet<String> reviewAnimals = conditionReviewAnimals(metas);
            IJ.log("[Representative Figure] Reviewing condition assignments for "
                    + reviewAnimals.size() + " animal"
                    + (reviewAnimals.size() == 1 ? "" : "s") + ".");
            if (!reviewConditionAssignments(directory, metas)) {
                IJ.log("[Representative Figure] Condition assignment review cancelled.");
                return;
            }

            if (!loadedNamed || choice.statisticChanged) {
                config.statistic = choice.statistic;
                config.existingResult = choice.existingResult;
            }
            RepresentativeStatistic statistic = config.statistic == null
                    ? RepresentativeStatistic.QUICK
                    : config.statistic;
            RepresentativeStatLoader.ExistingResultOption existingResult = config.existingResult;
            IJ.log("[Representative Figure] Loading statistic source: "
                    + statistic.label() + ".");
            long statStart = System.currentTimeMillis();
            config.statTable = RepresentativeStatLoader.load(
                    directory, statistic, existingResult, parallelThreads, metas);

            IJ.log("[Representative Figure] Loaded statistic source: "
                    + statistic.label() + " (" + statTableSummary(config.statTable)
                    + ") in " + elapsed(statStart) + ".");

            IJ.log("[Representative Figure] Building representative candidate list.");
            List<QcSelectionCandidate> candidates =
                    QcMinMaxPerConditionSelector.buildCandidates(directory, metas);
            IJ.log("[Representative Figure] Candidate list ready: "
                    + candidates.size() + " image series.");
            long previewStart = System.currentTimeMillis();
            List<RepresentativeSeries> previewSeries = RepresentativePreviewRenderer.render(
                    directory, config, imageCache, parallelThreads, useTifCache, metas, candidates);
            if (previewSeries.isEmpty()) {
                IJ.log("[Representative Figure] No image series were available for representative selection.");
                IJ.error("Representative Figure",
                        "No image series were available for representative selection.");
                return;
            }
            IJ.log("[Representative Figure] Representative preview grid ready in "
                    + elapsed(previewStart) + ".");

            IJ.log("[Representative Figure] Opening representative selection dialog.");
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
                IJ.log("[Representative Figure] Opening display-range adjustment workflow.");
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

            IJ.log("[Representative Figure] Opening condition layout chooser.");
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
            applyCliTileOverrides();
            long writeStart = System.currentTimeMillis();
            File output = new RepresentativeFigureWriter().write(
                    directory, config, imageCache, parallelThreads, useTifCache, metas);
            IJ.log("[Representative Figure] Representative figure written: "
                    + output.getAbsolutePath()
                    + " (" + elapsed(writeStart) + ").");
            persistCompletedRun(directory, setupConfig, output);
        } catch (Exception e) {
            IJ.log("[Representative Figure] Could not prepare representative selection: "
                    + e.getMessage());
            IJ.error("Representative Figure",
                    "Could not prepare representative selection:\n" + e.getMessage());
        }
    }

    /**
     * Headless path. Representative selection still requires headed mode, but
     * when a complete selection, layout, and tile config were remembered from a
     * prior interactive run, the figure is re-rendered (optionally restyled via
     * {@code repfig.*} macro overrides) without any dialogs.
     */
    private void executeHeadless(String directory) {
        if (config.selection != null && config.selection.isComplete()
                && config.layout != null && config.tileConfig != null) {
            try {
                applyCliTileOverrides();
                List<SeriesMeta> metas = loadSourceMetadata(directory);
                BinConfig setupConfig = BinConfigIO.readPartialFromDirectory(directory);
                long writeStart = System.currentTimeMillis();
                File output = new RepresentativeFigureWriter().write(
                        directory, config, imageCache, parallelThreads, useTifCache, metas);
                IJ.log("[Representative Figure] Re-rendered saved representative figure: "
                        + output.getAbsolutePath()
                        + " (" + elapsed(writeStart) + ").");
                persistCompletedRun(directory, setupConfig, output);
            } catch (Exception e) {
                IJ.log("[Representative Figure] Headless re-render failed: " + e.getMessage());
                IJ.error("Representative Figure",
                        "Headless re-render failed:\n" + e.getMessage());
            }
            return;
        }

        config.statistic = RepresentativeStatistic.NONE;
        config.existingResult = null;
        config.statTable = new RepresentativeStatTable();
        IJ.log("[Representative Figure] Headless representative figure requires a saved "
                + "selection from a prior interactive run; none found.");
    }

    /**
     * Applies any {@code repfig.*} CLI overrides to the tile config (and the
     * row layout for {@code repfig.rows}). No-op when no overrides are set.
     * Called once just before each {@code write(...)} so it works in both the
     * headed and headless paths.
     */
    void applyCliTileOverrides() {
        if (cliConfig == null || cliConfig.getRepfig() == null
                || !cliConfig.getRepfig().hasConfiguration()) {
            return;
        }
        CLIConfig.RepfigConfig repfig = cliConfig.getRepfig();
        applyCliSaveNameOverride();
        config.tileConfig = repfig.applyTo(config.tileConfig);
        if (repfig.getRows() != null && config.layout != null) {
            RepresentativeLayout reshaped = reshapeLayoutRows(
                    config.layout, repfig.getRows().intValue());
            if (reshaped != null) {
                config.layout = reshaped;
            }
        }
    }

    private void applyCliSaveNameOverride() {
        String saveName = cliSaveName();
        if (!saveName.isEmpty()) {
            config.saveName = saveName;
        }
    }

    private String cliSaveName() {
        if (cliConfig == null || cliConfig.getRepfig() == null) {
            return "";
        }
        return RepresentativeFigureConfig.normalizeSaveName(
                cliConfig.getRepfig().getSaveName());
    }

    /**
     * Distributes the flattened conditions of {@code layout} into {@code rows}
     * rows, balancing row sizes. Returns null if reshaping is not possible.
     */
    static RepresentativeLayout reshapeLayoutRows(RepresentativeLayout layout, int rows) {
        if (layout == null || rows < 1) {
            return null;
        }
        List<String> conditions = new ArrayList<String>(layout.flattenedConditions());
        if (conditions.isEmpty()) {
            return null;
        }
        int rowCount = Math.min(rows, conditions.size());
        List<List<String>> grid = new ArrayList<List<String>>();
        for (int i = 0; i < rowCount; i++) {
            grid.add(new ArrayList<String>());
        }
        int base = conditions.size() / rowCount;
        int remainder = conditions.size() % rowCount;
        int index = 0;
        for (int r = 0; r < rowCount; r++) {
            int size = base + (r < remainder ? 1 : 0);
            for (int c = 0; c < size; c++) {
                grid.get(r).add(conditions.get(index++));
            }
        }
        try {
            return new RepresentativeLayout(grid);
        } catch (IllegalArgumentException e) {
            return null;
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
        this.cliConfig = config;
    }

    @Override
    public void setRunRecordContext(AnalysisRunContext context) {
        this.runRecordContext = context;
    }

    public LoadedRunParameters.Result applyLoadedParameters(Map<String, Object> parameters) {
        Map<String, Object> block = representativeFigureBlock(parameters, cliSaveName());
        if (!block.isEmpty()) {
            config.applyMap(block);
        }
        LinkedHashSet<String> knownKeys = new LinkedHashSet<String>();
        knownKeys.add(RepresentativeFigureConfig.PROJECT_EXTRA_KEY);
        knownKeys.add(RepresentativeFigureConfig.PROJECT_COLLECTION_KEY);
        LoadedRunParameters.Result result = LoadedRunParameters.resultForKnownKeys(
                parameters, knownKeys);
        LoadedRunParameters.rememberLastResult(result);
        return result;
    }

    RepresentativeFigureConfig configForTests() {
        return config;
    }

    void setConditionReviewDialogForTests(ConditionReviewDialog dialog) {
        conditionReviewDialog = dialog;
    }

    private List<SeriesMeta> loadSourceMetadata(String directory) throws Exception {
        long start = System.currentTimeMillis();
        IJ.log("[Representative Figure] Reading source image metadata.");
        List<SeriesMeta> metas = ImageSourceDispatcher.readAllMetadata(directory);
        int usable = usableSeriesCount(metas);
        IJ.log("[Representative Figure] Source metadata ready: "
                + usable + " usable image series"
                + (metas == null ? "" : " (" + metas.size() + " metadata series)")
                + " in " + elapsed(start) + ".");
        return metas;
    }

    private static int usableSeriesCount(List<SeriesMeta> metas) {
        int count = 0;
        if (metas == null) {
            return count;
        }
        for (SeriesMeta meta : metas) {
            if (meta != null && !ImageNameParser.isPreviewSeriesName(meta.name)) {
                count++;
            }
        }
        return count;
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
            if (project != null && project.extras != null) {
                Map<String, Object> block = representativeFigureBlock(
                        project.extras, cliSaveName());
                if (!block.isEmpty()) {
                    config.applyMap(block);
                    applyCliSaveNameOverride();
                }
            }
        } catch (RuntimeException e) {
            IJ.log("[Representative Figure] Could not load remembered figure settings: "
                    + e.getMessage());
        }
    }

    private boolean loadNamedProjectParametersIfPresent(String directory, String saveName) {
        String cleanName = RepresentativeFigureConfig.normalizeSaveName(saveName);
        if (directory == null || directory.trim().isEmpty() || cleanName.isEmpty()) {
            return false;
        }
        try {
            ProjectFile project = ProjectFileIO.read(
                    FlashProjectLayout.forDirectory(directory).configurationWriteDir());
            Map<String, Object> block = project == null || project.extras == null
                    ? Collections.<String, Object>emptyMap()
                    : representativeFigureBlock(project.extras, cleanName);
            if (!block.isEmpty()) {
                config.applyMap(block);
                config.saveName = cleanName;
                return true;
            }
        } catch (RuntimeException e) {
            IJ.log("[Representative Figure] Could not load named figure settings for '"
                    + cleanName + "': " + e.getMessage());
        }
        return false;
    }

    private boolean hasSavedNamedProjectParameters(String directory, String saveName) {
        String cleanName = RepresentativeFigureConfig.normalizeSaveName(saveName);
        if (directory == null || directory.trim().isEmpty() || cleanName.isEmpty()) {
            return false;
        }
        try {
            ProjectFile project = ProjectFileIO.read(
                    FlashProjectLayout.forDirectory(directory).configurationWriteDir());
            if (project == null || project.extras == null) {
                return false;
            }
            if (!representativeFigureBySaveName(
                    project.extras.get(RepresentativeFigureConfig.PROJECT_COLLECTION_KEY),
                    cleanName).isEmpty()) {
                return true;
            }
            Map<String, Object> current = stringObjectMap(
                    project.extras.get(RepresentativeFigureConfig.PROJECT_EXTRA_KEY));
            return !current.isEmpty() && sameSaveName(current.get("saveName"), cleanName);
        } catch (RuntimeException e) {
            IJ.log("[Representative Figure] Could not check named figure settings for '"
                    + cleanName + "': " + e.getMessage());
            return false;
        }
    }

    static boolean startsFreshNamedFigure(String rememberedSaveName,
                                          String requestedSaveName,
                                          boolean savedNamedFigure) {
        String cleanRequested =
                RepresentativeFigureConfig.normalizeSaveName(requestedSaveName);
        return !cleanRequested.isEmpty()
                && !savedNamedFigure
                && !sameSaveName(rememberedSaveName, cleanRequested);
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
        if (config.hasSaveName()) {
            project.extras.put(RepresentativeFigureConfig.PROJECT_COLLECTION_KEY,
                    upsertRepresentativeFigure(project.extras.get(
                            RepresentativeFigureConfig.PROJECT_COLLECTION_KEY),
                            representative));
        }
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
            Map<String, Object> parameters,
            String requestedSaveName) {
        if (parameters == null || parameters.isEmpty()) {
            return Collections.emptyMap();
        }
        String cleanRequested = RepresentativeFigureConfig.normalizeSaveName(requestedSaveName);
        if (!cleanRequested.isEmpty()) {
            Map<String, Object> named = representativeFigureBySaveName(
                    parameters.get(RepresentativeFigureConfig.PROJECT_COLLECTION_KEY),
                    cleanRequested);
            if (!named.isEmpty()) {
                return named;
            }
            Map<String, Object> current = stringObjectMap(
                    parameters.get(RepresentativeFigureConfig.PROJECT_EXTRA_KEY));
            if (!current.isEmpty() && sameSaveName(
                    current.get("saveName"), cleanRequested)) {
                return current;
            }
            if (representativeFigureList(
                    parameters.get(RepresentativeFigureConfig.PROJECT_COLLECTION_KEY)).isEmpty()
                    && !current.isEmpty()) {
                current.put("saveName", cleanRequested);
                return current;
            }
            return Collections.emptyMap();
        }
        Object block = parameters.get(RepresentativeFigureConfig.PROJECT_EXTRA_KEY);
        if (block instanceof Map<?, ?>) {
            return stringObjectMap(block);
        }
        List<Map<String, Object>> figures = representativeFigureList(
                parameters.get(RepresentativeFigureConfig.PROJECT_COLLECTION_KEY));
        if (!figures.isEmpty()) {
            return figures.get(figures.size() - 1);
        }
        if (parameters.containsKey("lockedSeries")
                || parameters.containsKey("conditionNames")
                || parameters.containsKey("customDisplayRanges")) {
            return stringObjectMap(parameters);
        }
        return Collections.emptyMap();
    }

    private static Map<String, Object> representativeFigureBySaveName(
            Object value,
            String saveName) {
        for (Map<String, Object> figure : representativeFigureList(value)) {
            if (sameSaveName(figure.get("saveName"), saveName)) {
                return figure;
            }
        }
        return Collections.emptyMap();
    }

    private static List<Map<String, Object>> representativeFigureList(Object value) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (value instanceof List<?>) {
            for (Object item : (List<?>) value) {
                Map<String, Object> figure = stringObjectMap(item);
                if (!figure.isEmpty()) {
                    out.add(figure);
                }
            }
            return out;
        }
        Map<String, Object> object = stringObjectMap(value);
        if (object.isEmpty()) {
            return out;
        }
        if (looksLikeRepresentativeFigure(object)) {
            out.add(object);
            return out;
        }
        for (Map.Entry<String, Object> entry : object.entrySet()) {
            Map<String, Object> figure = stringObjectMap(entry.getValue());
            if (!figure.isEmpty()) {
                if (!figure.containsKey("saveName")) {
                    figure.put("saveName", entry.getKey());
                }
                out.add(figure);
            }
        }
        return out;
    }

    private static boolean looksLikeRepresentativeFigure(Map<String, Object> object) {
        return object != null
                && (object.containsKey("lockedSeries")
                || object.containsKey("conditionNames")
                || object.containsKey("statistic")
                || object.containsKey("tileConfig")
                || object.containsKey("layout"));
    }

    private static List<Object> upsertRepresentativeFigure(Object existing,
                                                           Map<String, Object> representative) {
        List<Object> out = new ArrayList<Object>();
        String saveName = RepresentativeFigureConfig.normalizeSaveName(
                representative == null ? null : representative.get("saveName"));
        boolean replaced = false;
        for (Map<String, Object> figure : representativeFigureList(existing)) {
            if (!saveName.isEmpty() && sameSaveName(figure.get("saveName"), saveName)) {
                if (representative != null) {
                    out.add(new LinkedHashMap<String, Object>(representative));
                }
                replaced = true;
            } else {
                out.add(new LinkedHashMap<String, Object>(figure));
            }
        }
        if (!replaced && representative != null && !representative.isEmpty()) {
            out.add(new LinkedHashMap<String, Object>(representative));
        }
        return out;
    }

    private static boolean sameSaveName(Object left, Object right) {
        String leftKey = saveNameKey(left);
        String rightKey = saveNameKey(right);
        return !leftKey.isEmpty() && leftKey.equals(rightKey);
    }

    private static String saveNameKey(Object value) {
        String safe = RepresentativeFigureConfig.safeSaveName(value);
        if (safe.isEmpty()) {
            safe = RepresentativeFigureConfig.normalizeSaveName(value);
        }
        return safe.toLowerCase(Locale.ROOT);
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
        RepresentativeStatistic initialStatistic = config.statistic == null
                ? RepresentativeStatistic.QUICK
                : config.statistic;
        String defaultExistingLabel = defaultExistingResultLabel(existingLabels, existingOptions);

        PipelineDialog dialog = new PipelineDialog(
                "Representative Image Figure - Statistic", PipelineDialog.Phase.EXPORT);
        dialog.setWorkflowTracker(new String[]{"Statistic", "Conditions", "Select Images",
                "Display Ranges", "Layout", "Build"}, 0);
        dialog.addHeader("Guiding Statistic");
        final boolean hasExistingOptions = !existingOptions.isEmpty();
        final RepresentativeStatisticChoicePanel statisticPanel =
                new RepresentativeStatisticChoicePanel(
                        initialStatistic,
                        existingLabels,
                        defaultExistingLabel,
                        hasExistingOptions);
        dialog.addComponent(statisticPanel);
        dialog.addStringField("Save name", config.saveName(), 24);
        dialog.addHelpText("Optional save name keeps separate project.json settings and output files for this representative figure.");
        dialog.setPrimaryButtonText(NextStepLabels.ASSIGN_CONDITIONS);

        if (!dialog.showDialog()) {
            return null;
        }

        RepresentativeStatistic statistic = statisticPanel.getSelectedStatistic();
        String existingLabel = statisticPanel.getSelectedExistingLabel();
        String saveName = RepresentativeFigureConfig.normalizeSaveName(dialog.getNextString());
        RepresentativeStatLoader.ExistingResultOption existing = existingByLabel.get(existingLabel);
        if (statistic == RepresentativeStatistic.EXISTING_RESULT && existing == null) {
            throw new IllegalStateException("No numeric existing result column is available.");
        }
        return new StatisticChoice(statistic, existing, saveName,
                statisticSelectionChanged(
                        initialStatistic, defaultExistingLabel, statistic, existingLabel));
    }

    static boolean statisticSelectionChanged(
            RepresentativeStatistic initialStatistic,
            String initialExistingLabel,
            RepresentativeStatistic selectedStatistic,
            String selectedExistingLabel) {
        RepresentativeStatistic initial = initialStatistic == null
                ? RepresentativeStatistic.QUICK
                : initialStatistic;
        RepresentativeStatistic selected = selectedStatistic == null
                ? RepresentativeStatistic.QUICK
                : selectedStatistic;
        if (initial != selected) {
            return true;
        }
        if (selected == RepresentativeStatistic.EXISTING_RESULT) {
            return !clean(initialExistingLabel).equals(clean(selectedExistingLabel));
        }
        return false;
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
        dialog.setWorkflowTracker(new String[]{"Statistic", "Conditions", "Select Images",
                "Display Ranges", "Layout", "Build"}, 3);
        dialog.addHeader("Display Ranges");
        JLabel intro = new JLabel("<html><body width='500'>Choose how the figure should get its display ranges. "
                + "Measurements are unchanged.</body></html>");
        intro.setFont(FlashTheme.body());
        intro.setForeground(FlashTheme.TEXT_PRIMARY);
        intro.setBorder(FlashTheme.pad(0, 2, 4, 2));
        dialog.addComponent(intro);
        final JComboBox<String> displayRangeChoice = dialog.addCardChoice(
                "Display range setup",
                displayRangeCardOptions(hasRememberedRanges, hasSetupRanges),
                defaultDisplayRangeChoiceValue(hasRememberedRanges, hasSetupRanges));
        final DisplayRangeDetailsPanel detailsPanel =
                new DisplayRangeDetailsPanel();
        dialog.addComponent(detailsPanel);
        final Runnable refreshChoiceDetails = new Runnable() {
            @Override public void run() {
                Object selected = displayRangeChoice.getSelectedItem();
                String choice = selected == null ? null : selected.toString();
                boolean adjustNow = DisplayRangeChoice.ADJUST_NOW_LABEL.equals(choice);
                dialog.setPrimaryButtonText(
                        NextStepLabels.afterRepresentativeDisplayRangeChoice(adjustNow));
                detailsPanel.setChoice(choice);
            }
        };
        displayRangeChoice.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                refreshChoiceDetails.run();
            }
        });
        refreshChoiceDetails.run();

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

    static String[] displayRangeChoiceValues(boolean hasRememberedRanges,
                                             boolean hasSetupRanges) {
        if (hasRememberedRanges) {
            if (hasSetupRanges) {
                return new String[]{DisplayRangeChoice.USE_REMEMBERED_LABEL,
                        DisplayRangeChoice.USE_SETUP_LABEL,
                        DisplayRangeChoice.ADJUST_NOW_LABEL};
            }
            return new String[]{DisplayRangeChoice.USE_REMEMBERED_LABEL,
                    DisplayRangeChoice.ADJUST_NOW_LABEL};
        }
        if (hasSetupRanges) {
            return new String[]{DisplayRangeChoice.USE_SETUP_LABEL,
                    DisplayRangeChoice.ADJUST_NOW_LABEL};
        }
        return new String[]{DisplayRangeChoice.ADJUST_NOW_LABEL};
    }

    static String defaultDisplayRangeChoiceValue(boolean hasRememberedRanges,
                                                 boolean hasSetupRanges) {
        if (hasRememberedRanges) {
            return DisplayRangeChoice.USE_REMEMBERED_LABEL;
        }
        if (hasSetupRanges) {
            return DisplayRangeChoice.USE_SETUP_LABEL;
        }
        return DisplayRangeChoice.ADJUST_NOW_LABEL;
    }

    private static CardChoice.Option[] displayRangeCardOptions(
            boolean hasRememberedRanges,
            boolean hasSetupRanges) {
        String[] values = displayRangeChoiceValues(hasRememberedRanges,
                hasSetupRanges);
        CardChoice.Option[] options = new CardChoice.Option[values.length];
        for (int i = 0; i < values.length; i++) {
            options[i] = displayRangeCardOption(values[i]);
        }
        return options;
    }

    private static CardChoice.Option displayRangeCardOption(String value) {
        if (DisplayRangeChoice.USE_REMEMBERED_LABEL.equals(value)) {
            return new CardChoice.Option(value,
                    "Saved figure",
                    "Use saved figure ranges",
                    "save",
                    "Saved");
        }
        if (DisplayRangeChoice.USE_SETUP_LABEL.equals(value)) {
            return new CardChoice.Option(value,
                    "Set Up config",
                    "Use configured ranges",
                    "settings",
                    "Consistent");
        }
        return new CardChoice.Option(DisplayRangeChoice.ADJUST_NOW_LABEL,
                "Adjust now",
                "Tune selected images",
                "sliders",
                "Preview");
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
        return DisplayRangeSetting.parse(token).isConfigured();
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

    private static String elapsed(long startMillis) {
        long elapsed = Math.max(0L, System.currentTimeMillis() - startMillis);
        if (elapsed < 1000L) {
            return elapsed + " ms";
        }
        return String.format(java.util.Locale.ROOT, "%.1f s", elapsed / 1000.0);
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
        dialog.setWorkflowTracker(new String[]{"Statistic", "Conditions", "Select Images",
                "Display Ranges", "Layout", "Build"}, 2);
        dialog.addHeader("Select Representatives");
        final RepresentativeSelectionPanel selectionPanel =
                new RepresentativeSelectionPanel(previewSeries,
                        config.statistic, config.statTable, config.selection);
        dialog.addComponent(selectionPanel);
        dialog.setPrimaryButtonText(NextStepLabels.DISPLAY_RANGES);
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

    private static String[] representativeWorkflow() {
        return new String[]{"Statistic", "Conditions", "Select Images",
                "Display Ranges", "Layout", "Build"};
    }

    private static final class StatisticChoice {
        final RepresentativeStatistic statistic;
        final RepresentativeStatLoader.ExistingResultOption existingResult;
        final String saveName;
        final boolean statisticChanged;

        StatisticChoice(RepresentativeStatistic statistic,
                        RepresentativeStatLoader.ExistingResultOption existingResult,
                        String saveName,
                        boolean statisticChanged) {
            this.statistic = statistic == null ? RepresentativeStatistic.QUICK : statistic;
            this.existingResult = existingResult;
            this.saveName = RepresentativeFigureConfig.normalizeSaveName(saveName);
            this.statisticChanged = statisticChanged;
        }
    }

    private static final class DisplayRangeDetailsPanel extends JPanel {
        private static final int WIDTH = 518;
        private static final int HEIGHT = 86;

        private final JLabel bodyLabel = new JLabel();
        private final JLabel scopeLabel = new JLabel();

        DisplayRangeDetailsPanel() {
            setLayout(new BorderLayout(0, 4));
            setOpaque(true);
            setBackground(FlashTheme.SURFACE_RAISED);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setMaximumSize(new Dimension(WIDTH, HEIGHT));
            setPreferredSize(new Dimension(WIDTH, HEIGHT));
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(FlashTheme.BORDER, 1, true),
                    FlashTheme.pad(9, 12, 9, 12)));

            JLabel title = new JLabel("Next step");
            title.setFont(FlashTheme.h2());
            title.setForeground(FlashTheme.TEXT_HEADER);
            add(title, BorderLayout.NORTH);

            JPanel text = new JPanel();
            text.setOpaque(false);
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));

            bodyLabel.setFont(FlashTheme.body());
            bodyLabel.setForeground(FlashTheme.TEXT_PRIMARY);
            bodyLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            text.add(bodyLabel);

            scopeLabel.setFont(FlashTheme.caption());
            scopeLabel.setForeground(FlashTheme.TEXT_MUTED);
            scopeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            text.add(scopeLabel);

            add(text, BorderLayout.CENTER);
        }

        void setChoice(String choice) {
            bodyLabel.setText(displayRangeNextStepText(choice));
            scopeLabel.setText(displayRangeScopeText(choice));
        }
    }

    static String displayRangeNextStepText(String choice) {
        if (DisplayRangeChoice.ADJUST_NOW_LABEL.equals(choice)) {
            return "Open the display-range editor for the selected representative images.";
        }
        if (DisplayRangeChoice.USE_REMEMBERED_LABEL.equals(choice)) {
            return "Use the saved representative display ranges and go straight to Layout.";
        }
        return "Use the display ranges from Set Up Configuration and go straight to Layout.";
    }

    static String displayRangeScopeText(String choice) {
        if (DisplayRangeChoice.ADJUST_NOW_LABEL.equals(choice)) {
            return "Use this when the figure needs channel-specific tuning before layout.";
        }
        if (DisplayRangeChoice.USE_REMEMBERED_LABEL.equals(choice)) {
            return "Affects the figure PNG only; measurements and source images are unchanged.";
        }
        return "Good when the figure should match existing presentation-ready images.";
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
