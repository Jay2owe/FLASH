package flash.pipeline;

import flash.pipeline.analyses.Analysis;
import flash.pipeline.analyses.CreateBinFileAnalysis;
import flash.pipeline.analyses.DeconvolutionAnalysis;
import flash.pipeline.analyses.DrawAndSaveROIsAnalysis;
import flash.pipeline.analyses.IntensityAnalysisV2;
import flash.pipeline.analyses.LineDistanceAnalysis;
import flash.pipeline.analyses.MasterAggregationAnalysis;
import flash.pipeline.analyses.RepresentativeFigureAnalysis;
import flash.pipeline.analyses.SpatialAnalysis;
import flash.pipeline.analyses.SplitAndMergeImageChannelsAnalysis;
import flash.pipeline.analyses.StatisticalAnalysis;
import flash.pipeline.analyses.ThreeDObjectAnalysis;
import flash.pipeline.bin.BinSetupDispatcher;
import flash.pipeline.cellpose.CellposeRuntime;
import flash.pipeline.cli.CLIArgumentParser;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.decontamination.SpectralDecontaminationAnalysis;
import flash.pipeline.execution.AnalysisCancellation;
import flash.pipeline.execution.AnalysisRegistry;
import flash.pipeline.execution.AnalysisRunCoordinator;
import flash.pipeline.execution.RunResult;
import flash.pipeline.help.AnalysisHelpCatalog;
import flash.pipeline.help.AnalysisHelpDialog;
import flash.pipeline.help.AnalysisHelpTopic;
import flash.pipeline.help.HelpDialog;
import flash.pipeline.ui.HelpButton;
import flash.pipeline.image.GpuConcurrency;
import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.ToggleSwitch;

import flash.pipeline.io.ImageCache;
import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.io.ProjectStatusStore;
import flash.pipeline.io.ResultAnimalScanner;
import flash.pipeline.ui.wizard.ConditionReviewSupport;
import flash.pipeline.intelligence.DiagnosticsDialog;
import flash.pipeline.intelligence.AnalysisStatus;
import flash.pipeline.intelligence.AnalysisStatusScanner;
import flash.pipeline.intelligence.PostRunSummary;
import flash.pipeline.intelligence.PreFlightChecks;
import flash.pipeline.recipes.PipelineRecipe;
import flash.pipeline.recipes.PipelineRecipeIO;
import flash.pipeline.report.QualityReport;
import flash.pipeline.results.StartHereWriter;
import flash.pipeline.runtime.BioFormatsRuntime;
import flash.pipeline.runtime.DependencyFixPlan;
import flash.pipeline.runtime.DependencyFixResult;
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.DependencyRegistry;
import flash.pipeline.runtime.DependencyService;
import flash.pipeline.runtime.DependencySpec;
import flash.pipeline.runtime.DependencyStatus;
import flash.pipeline.runtime.FeatureDependencyGate;
import flash.pipeline.runtime.ImageJRestartHelper;
import flash.pipeline.runtime.PluginInstallGuard;

import flash.pipeline.project.ProjectBuilderDialog;
import flash.pipeline.project.ProjectFile;
import flash.pipeline.project.ProjectHomeDialog;
import flash.pipeline.project.ProjectService;
import flash.pipeline.project.RecentProject;
import flash.pipeline.project.RecentProjectsStore;
import flash.pipeline.runrecord.LoadedRunParameters;

import ij.IJ;
import ij.Macro;
import ij.plugin.PlugIn;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;


public class FLASH_Pipeline implements PlugIn {

    private String directory = null;
    private boolean cliInvocation = false;
    private boolean headlessMode = true;
    private boolean verboseLogging = false;
    private boolean autoAggregate = true;
    /** Per-batch override set by the picker condition preflight; never persisted. */
    private boolean skipAutoAggregateForBatch = false;
    private boolean parallelProcessing = true;
    private int parallelThreadCount = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    private int loaderThreadCount = 1;
    private int loaderPercent = 50;
    private boolean useTifCache = false;
    private String overwriteBehavior = "Auto-Overwrite";
    private boolean generateQcReport = true;
    private boolean fastReopen = false;
    /** GPU inference permit override; 0 = auto-detect (see {@link GpuConcurrency}). */
    private int gpuPermits = 0;
    private static final Color SAVE_RECIPE_BG = new Color(232, 245, 253);
    private static final Color SAVE_RECIPE_FG = new Color(15, 87, 140);
    private static final Color SAVE_RECIPE_BORDER = new Color(71, 145, 196);

    private ImageCache imageCache = null;
    private final DependencyService dependencyService = new DependencyService();
    private boolean dependencyRestartPending = false;
    private String dependencyRestartMessage = "";
    private CLIConfig cliConfig = null;
    private boolean lastGuiAnalysisCancelled = false;
    private boolean statusIconResourceWarningLogged = false;

    private final String[] analyses = {
            "Set Up Configuration",
            "Draw ROIs and Orientate Images",
            "3D Deconvolution",
            "Make Presentation Images",
            "3D Object Analysis",
            "Spatial Analysis",
            "Line Distance Analysis",
            "Fluorescence Intensity Analysis",
            // UI clarity pass intentionally flips the old Aggregation label to plain English.
            "Combine results per condition / animal",
            "Statistical Analysis",
            "Excel Summary Export",
            "Spectral Decontamination (Experimental)",
            "Make Representative Image Figure"
    };

    private static final String[] DESCRIPTIONS = {
            "Set up channel names, thresholds, and size filters for this experiment.",
            "Draw ROIs with rotate/flip controls; saved image transforms are reused on later runs.",
            "Sharpen Z-stacks using a theoretical PSF before segmentation.",
            "Split image channels, apply LUTs and display ranges, then create composite PNGs and/or Tifs for presentation.",
            "Count cells in 3D, measure size/shape, and check colocalisation.",
            "Re-run spatial / nearest-neighbour analysis from existing object CSVs.",
            "Distance from each object to a drawn anatomical landmark.",
            "Per-region fluorescence intensity (mean, area-fraction, thresholded).",
            "Aggregates all per-image CSVs into master summary tables (Master Data Aggregation).",
            "Group comparisons - t-tests, ANOVA, Tukey / Dunn's post-hoc.",
            "Make a publication-ready .xlsx workbook from the master CSVs.",
            "Remove channel bleed-through / autofluorescence (placeholder).",
            "Choose representative image series per condition and export a tiled PNG figure."
    };

    /** Analysis indices. Keep in sync with {@link #analyses}. */
    public static final int IDX_CREATE_BIN = 0;
    public static final int IDX_DRAW_ROIS = 1;
    public static final int IDX_DECONVOLUTION = 2;
    public static final int IDX_SPLIT_MERGE = 3;
    public static final int IDX_3D_OBJECT = 4;
    public static final int IDX_SPATIAL = 5;
    public static final int IDX_LINE_DISTANCE = 6;
    public static final int IDX_INTENSITY = 7;
    public static final int IDX_AGGREGATION = 8;
    public static final int IDX_STATISTICS = 9;
    public static final int IDX_EXCEL_EXPORT = 10;
    public static final int IDX_SPECTRAL_DECONTAMINATION = 11;
    public static final int IDX_REPRESENTATIVE_FIGURE = 12;

    private static final int[] VISIBLE_ANALYSIS_ORDER = {
            IDX_CREATE_BIN,
            IDX_DRAW_ROIS,
            IDX_DECONVOLUTION,
            IDX_SPECTRAL_DECONTAMINATION,
            IDX_SPLIT_MERGE,
            IDX_REPRESENTATIVE_FIGURE,
            IDX_INTENSITY,
            IDX_3D_OBJECT,
            IDX_SPATIAL,
            IDX_AGGREGATION,
            IDX_STATISTICS,
            IDX_EXCEL_EXPORT
    };

    /** Test hook for the visible main-dialog analysis rows. */
    public static int[] visibleAnalysisOrderForTests() {
        return Arrays.copyOf(VISIBLE_ANALYSIS_ORDER, VISIBLE_ANALYSIS_ORDER.length);
    }

    private static final String EXCEL_EXPORT_ANALYSIS_CLASS =
            "flash.pipeline.export.ExcelSummaryExportAnalysis";

    private final Map<Integer, Analysis> analysisMap = new HashMap<>();

    @Override
    public void run(String arg) {
        cliInvocation = false;
        cliConfig = null;
        fastReopen = false;
        configureFeatureDependencyGate();
        PluginInstallGuard.auditPinnedRuntimeJarsOnStartup(
                DependencyId.STARDIST_RUNTIME,
                DependencyId.TENSORFLOW_NATIVE_RUNTIME);

        // Kick off the GPU probe as early as possible so its ~200 ms cost
        // overlaps with the directory picker / dependency check, not the
        // first DL inference.
        GpuConcurrency.initAsync();
        CellposeRuntime.probeAsync();

        // ── CLI / Batch mode detection ──
        String macroOptions = Macro.getOptions();
        if (CLIArgumentParser.hasCliOptions(macroOptions)) {
            runCli(macroOptions);
            return;
        }

        showStartupDependencyWarningIfNeeded();

        if (directory == null) {
            if (GraphicsEnvironment.isHeadless()) {
                IJ.log("[FLASH] No project chosen, plugin cancelled.");
                return;
            }
            ProjectLaunchSelection picked = chooseProjectFromHome(
                    null, RecentProjectsStore.resolveStoreDir());
            if (picked == null || picked.outputRoot == null) {
                IJ.showMessage("Error", "No project chosen, plugin cancelled.");
                return;
            }
            // The plugin treats `directory` as the project output root from this
            // point onward; source files come from the project's item list.
            directory = picked.outputRoot.getAbsolutePath();
            fastReopen = picked.fastReopen;
        }

        // L-10: if this folder already contains a prior run's output dirs,
        // confirm that a re-run is intended before doing anything else.
        if (!PreFlightChecks.confirmProceedOnOutputFolder(directory, fastReopen)) {
            return;
        }
        if (!confirmInputSourceAndWarn(true)) {
            return;
        }

        initAnalyses();

        while (true) {
            boolean[] selections = showAnalysisDialog();
            if (selections == null) break;

            int selectedCount = 0;
            for (boolean sel : selections) if (sel) selectedCount++;
            if (selectedCount == 0) {
                continue;
            }

            // Silent pre-flight guards. Fire once per batch invocation.
            if (!runPreFlightGuardsSafely(directory)) continue;

            // Fresh QC report for this run — prevents state leaking across runs
            QualityReport qualityReport = createQualityReportForRun(
                    directory, generateQcReport, headlessMode, parallelProcessing,
                    parallelThreadCount, verboseLogging, overwriteBehavior);

            boolean suppressDialogs = selectedCount > 1;

            GpuConcurrency.logEffectivePermits();

            boolean[] completedAnalyses = new boolean[selections.length];
            int completedCount = 0;
            boolean runCancelled = false;
            boolean runHadFailure = false;
            for (int i = 0; i < selections.length; i++) {
                if (!selections[i]) continue;

                Analysis analysis = analysisMap.get(i);
                if (analysis != null) {
                    configureAnalysis(analysis, i, suppressDialogs, qualityReport);
                    BinSetupDispatcher.clearLastFieldSources();
                    boolean completed = executeAnalysisSafelyForGui(analysis, i, directory);
                    if (lastGuiAnalysisCancelled) {
                        runCancelled = true;
                        break;
                    }
                    completedAnalyses[i] = completed;
                    if (completed) {
                        completedCount++;
                    } else {
                        runHadFailure = true;
                    }
                } else {
                    runHadFailure = true;
                    IJ.showMessage("Analysis not implemented");
                }
            }

            if (runCancelled || completedCount == 0) {
                continue;
            }

            // Auto-trigger aggregation after 3D Object or Intensity analyses
            boolean ran3D = selections[IDX_3D_OBJECT] && completedAnalyses[IDX_3D_OBJECT];
            boolean ranSpatial = selections[IDX_SPATIAL] && completedAnalyses[IDX_SPATIAL];
            boolean ranIntensity = selections[IDX_INTENSITY] && completedAnalyses[IDX_INTENSITY];
            boolean manuallyRanAgg = selections[IDX_AGGREGATION];
            boolean wantsAutoAggregation = autoAggregate && !skipAutoAggregateForBatch
                    && (ran3D || ranSpatial || ranIntensity) && !manuallyRanAgg;
            if (wantsAutoAggregation && !confirmAutoAggregationConditions()) {
                IJ.log("[FLASH] Auto aggregation skipped: condition assignments left unreviewed.");
                wantsAutoAggregation = false;
            }
            if (wantsAutoAggregation) {
                IJ.log("Auto-running Master Data Aggregation...");
                Analysis aggAnalysis = analysisMap.get(IDX_AGGREGATION);
                if (aggAnalysis != null) {
                    configureAnalysis(aggAnalysis, IDX_AGGREGATION, true, qualityReport);
                    BinSetupDispatcher.clearLastFieldSources();
                    boolean aggregationCompleted = executeAnalysisSafelyForGui(
                            aggAnalysis, IDX_AGGREGATION, directory);
                    if (lastGuiAnalysisCancelled) {
                        continue;
                    }
                    if (aggregationCompleted) {
                        completedCount++;
                    } else {
                        runHadFailure = true;
                    }
                } else {
                    runHadFailure = true;
                    IJ.log("[FLASH] Auto aggregation skipped: analysis not implemented.");
                }
            }

            // Post-run summary, informational only
            runGuiStepSafely("Post-run summary", new Runnable() {
                @Override public void run() {
                    PostRunSummary.writeIfPossible(directory);
                }
            });
            runGuiStepSafely("START_HERE index", new Runnable() {
                @Override public void run() {
                    writeStartHereIfPossible(directory);
                }
            });
            if (!runHadFailure) {
                runGuiStepSafely("Project recipe save", new Runnable() {
                    @Override public void run() {
                        saveProjectRecipe(selections);
                    }
                });
            } else {
                IJ.log("[FLASH] Last-run recipe not updated because one or more analyses failed.");
            }

        }

        releaseImageCache();
        IJ.log("Pipeline finished.");
    }

    private ProjectLaunchSelection chooseProjectFromHome(Window owner, File pluginsDir) {
        return routeHomeChoice(ProjectHomeDialog.open(owner, pluginsDir), owner, pluginsDir,
                new ProjectBuilderOpener() {
                    @Override public ProjectBuilderDialog.Result open(Window builderOwner,
                                                                       File builderPluginsDir,
                                                                       File suggestedSourceFolder) {
                        return ProjectBuilderDialog.open(builderOwner, builderPluginsDir,
                                suggestedSourceFolder);
                    }
                });
    }

    static ProjectLaunchSelection routeHomeChoice(ProjectHomeDialog.Choice choice,
                                                  Window owner,
                                                  File pluginsDir,
                                                  ProjectBuilderOpener builderOpener) {
        if (choice == null || choice.action == ProjectHomeDialog.Choice.Action.CANCEL) {
            return null;
        }
        switch (choice.action) {
            case OPEN_EXISTING:
                return openExistingHomeProject(choice.projectJson, pluginsDir);
            case NEW_PROJECT:
                return selectionFromBuilder(openBuilder(builderOpener, owner, pluginsDir, null));
            case BROWSE_FOLDER:
                return routeBrowsedFolder(choice.folder, owner, pluginsDir, builderOpener);
            case EDIT_EXISTING:
                return selectionFromBuilder(openBuilder(builderOpener, owner, pluginsDir,
                        outputRootForProjectJson(choice.projectJson)));
            case CANCEL:
            default:
                return null;
        }
    }

    private static ProjectLaunchSelection routeBrowsedFolder(File folder,
                                                             Window owner,
                                                             File pluginsDir,
                                                             ProjectBuilderOpener builderOpener) {
        ProjectService.ProjectKind kind = ProjectService.classify(folder);
        if (kind == ProjectService.ProjectKind.VALID_FLASH) {
            File projectJson = ProjectService.resolveProjectJson(folder);
            if (projectJson != null) {
                return openExistingHomeProject(projectJson, pluginsDir);
            }
        }
        return selectionFromBuilder(openBuilder(builderOpener, owner, pluginsDir, folder));
    }

    private static ProjectBuilderDialog.Result openBuilder(ProjectBuilderOpener opener,
                                                           Window owner,
                                                           File pluginsDir,
                                                           File suggestedSourceFolder) {
        return opener == null ? null : opener.open(owner, pluginsDir, suggestedSourceFolder);
    }

    private static ProjectLaunchSelection selectionFromBuilder(ProjectBuilderDialog.Result picked) {
        if (picked == null || picked.outputRoot == null) {
            return null;
        }
        return new ProjectLaunchSelection(picked.outputRoot.getAbsoluteFile(), false);
    }

    private static ProjectLaunchSelection openExistingHomeProject(File projectJson,
                                                                 File pluginsDir) {
        File resolvedProjectJson = ProjectService.resolveProjectJson(projectJson);
        if (resolvedProjectJson == null) {
            return null;
        }
        // Fast reopen deliberately loads the existing project and skips the
        // builder save path, avoiding redundant project.json/Conditions.csv rewrites.
        ProjectFile project = ProjectService.load(resolvedProjectJson);
        if (project == null) {
            return null;
        }
        File outputRoot = outputRootFor(project, resolvedProjectJson);
        if (outputRoot == null) {
            return null;
        }
        rememberHomeOpenedProject(pluginsDir, project, resolvedProjectJson, outputRoot);
        return new ProjectLaunchSelection(outputRoot, true);
    }

    private static File outputRootForProjectJson(File projectJson) {
        File resolvedProjectJson = ProjectService.resolveProjectJson(projectJson);
        if (resolvedProjectJson == null) {
            resolvedProjectJson = projectJson;
        }
        ProjectFile project = ProjectService.load(resolvedProjectJson);
        File outputRoot = outputRootFor(project, resolvedProjectJson);
        return outputRoot == null ? null : outputRoot.getAbsoluteFile();
    }

    private static File outputRootFor(ProjectFile project, File projectJson) {
        String outputRoot = project == null ? null : project.outputRoot;
        if (outputRoot != null && !outputRoot.trim().isEmpty()) {
            return new File(outputRoot.trim()).getAbsoluteFile();
        }
        File settingsDir = projectJson == null ? null : projectJson.getParentFile();
        File fallback = FlashProjectLayout.projectRootForConfigurationDir(settingsDir);
        return fallback == null ? null : fallback.getAbsoluteFile();
    }

    private static void rememberHomeOpenedProject(File pluginsDir, ProjectFile project,
                                                  File projectJson, File outputRoot) {
        if (pluginsDir == null || projectJson == null) {
            return;
        }
        try {
            RecentProjectsStore.recordOpened(pluginsDir,
                    new RecentProject(recentProjectName(project, outputRoot),
                            projectJson.getAbsolutePath(),
                            System.currentTimeMillis()));
        } catch (IOException ex) {
            IJ.log("[FLASH] Could not update recent projects: " + ex.getMessage());
        }
    }

    private static String recentProjectName(ProjectFile project, File outputRoot) {
        String name = project == null ? null : project.name;
        if (name != null && !name.trim().isEmpty()) {
            return name.trim();
        }
        return outputRoot == null ? "" : outputRoot.getName();
    }

    interface ProjectBuilderOpener {
        ProjectBuilderDialog.Result open(Window owner, File pluginsDir, File suggestedSourceFolder);
    }

    static final class ProjectLaunchSelection {
        final File outputRoot;
        final boolean fastReopen;

        ProjectLaunchSelection(File outputRoot, boolean fastReopen) {
            this.outputRoot = outputRoot;
            this.fastReopen = fastReopen;
        }
    }

    /**
     * CLI / headless batch execution path.
     * Skips all dialogs and runs analyses based on parsed macro options.
     */
    private void runCli(String macroOptions) {
        cliInvocation = true;
        configureFeatureDependencyGate();
        CLIConfig cfg = CLIArgumentParser.parse(macroOptions);
        if (cfg == null) {
            IJ.log("[CLI] " + CLIArgumentParser.usage());
            return;
        }
        cliConfig = cfg;

        directory = cfg.getDirectory();
        headlessMode = cfg.isHeadless();
        parallelProcessing = cfg.isParallel();
        parallelThreadCount = cfg.getThreads();
        verboseLogging = cfg.isVerbose();
        overwriteBehavior = cfg.getOverwriteBehavior();
        autoAggregate = cfg.isAutoAggregate();
        generateQcReport = cfg.isQcReport();
        useTifCache = cfg.isTifCache();
        loaderThreadCount = cfg.getLoaderThreads();
        loaderPercent = cfg.getLoaderPercent();
        gpuPermits = cfg.getGpuPermits();
        GpuConcurrency.setUserOverride(gpuPermits);

        File runDir = new File(directory);
        if (!runDir.isDirectory()) {
            IJ.log("[CLI FATAL] Directory does not exist or is not a directory: " + directory);
            return;
        }

        boolean[] selections = cfg.getSelectedAnalyses();

        // Check if any analyses were selected
        boolean anySelected = false;
        for (boolean s : selections) {
            if (s) { anySelected = true; break; }
        }
        if (!anySelected) {
            IJ.log("[CLI] No analyses selected. Use run_deconv, run_3d, run_intensity, run_repfig, etc. or analyses=2,4,7,12");
            IJ.log("[CLI] " + CLIArgumentParser.usage());
            return;
        }
        if (!confirmInputSourceAndWarn(false)) {
            return;
        }

        IJ.log("===== FLASH - The Pipeline for Fluorescence Automated Spatial Histology (CLI Mode) =====");
        IJ.log("Directory: " + directory);
        StringBuilder sb = new StringBuilder("Analyses: ");
        for (int i = 0; i < selections.length; i++) {
            if (selections[i]) sb.append(analyses[i]).append(", ");
        }
        IJ.log(sb.toString().replaceAll(", $", ""));
        IJ.log("Headless: " + headlessMode + "  |  Parallel: " + parallelProcessing
                + " (" + parallelThreadCount + " threads)");
        IJ.log("Overwrite: " + overwriteBehavior + "  |  QC Report: " + generateQcReport);
        IJ.log("============================================");

        initAnalyses();

        // Fresh QC report for this CLI invocation
        QualityReport qualityReport = createQualityReportForRun(
                directory, generateQcReport, headlessMode, parallelProcessing,
                parallelThreadCount, verboseLogging, overwriteBehavior);

        GpuConcurrency.logEffectivePermits();

        List<String> failedAnalyses = new ArrayList<>();
        boolean[] completedAnalyses = new boolean[selections.length];

        // Execute selected analyses — CLI always suppresses dialogs
        for (int i = 0; i < selections.length; i++) {
            if (!selections[i]) continue;

            Analysis analysis = analysisMap.get(i);
            if (analysis != null) {
                IJ.log("[CLI] Running: " + analyses[i]);
                configureAnalysis(analysis, i, true, qualityReport);
                BinSetupDispatcher.clearLastFieldSources();
                try {
                    final Analysis selectedAnalysis = analysis;
                    final String runDirectory = directory;
                    new AnalysisRunCoordinator().run(selectedAnalysis, i, analyses[i],
                            runDirectory, cliConfig, null, "", new Callable<Void>() {
                                @Override public Void call() {
                                    selectedAnalysis.execute(runDirectory);
                                    return null;
                                }
                            });
                    if (BinSetupDispatcher.getLastOutcome() == BinSetupDispatcher.Outcome.CANCELLED) {
                        IJ.log("[CLI] " + analyses[i] + " cancelled during setup.");
                        failedAnalyses.add(analyses[i]);
                    } else {
                        completedAnalyses[i] = true;
                    }
                } catch (Throwable t) {
                    IJ.handleException(t);
                    IJ.log("[CLI] " + analyses[i] + " FAILED: " + t.getMessage());
                    failedAnalyses.add(analyses[i]);
                } finally {
                    resetBioFormatsWindowless();
                }
            } else {
                IJ.log("[CLI] Warning: Analysis not implemented for index " + i);
                failedAnalyses.add("index " + i);
            }
        }

        // Auto-trigger aggregation — configured through the same path
        boolean ran3D = selections[IDX_3D_OBJECT] && completedAnalyses[IDX_3D_OBJECT];
        boolean ranSpatial = selections[IDX_SPATIAL] && completedAnalyses[IDX_SPATIAL];
        boolean ranIntensity = selections[IDX_INTENSITY] && completedAnalyses[IDX_INTENSITY];
        boolean manuallyRanAgg = selections[IDX_AGGREGATION];
        if (autoAggregate && (ran3D || ranSpatial || ranIntensity) && !manuallyRanAgg) {
            IJ.log("[CLI] Auto-running Master Data Aggregation...");
            Analysis aggAnalysis = analysisMap.get(IDX_AGGREGATION);
            if (aggAnalysis != null) {
                configureAnalysis(aggAnalysis, IDX_AGGREGATION, true, qualityReport);
                BinSetupDispatcher.clearLastFieldSources();
                try {
                    final Analysis aggregationAnalysis = aggAnalysis;
                    final String runDirectory = directory;
                    new AnalysisRunCoordinator().run(aggregationAnalysis, IDX_AGGREGATION,
                            analyses[IDX_AGGREGATION], runDirectory, cliConfig, null, "",
                            new Callable<Void>() {
                                @Override public Void call() {
                                    aggregationAnalysis.execute(runDirectory);
                                    return null;
                                }
                            });
                    if (BinSetupDispatcher.getLastOutcome() == BinSetupDispatcher.Outcome.CANCELLED) {
                        IJ.log("[CLI] " + analyses[IDX_AGGREGATION] + " (auto) cancelled during setup.");
                        failedAnalyses.add(analyses[IDX_AGGREGATION] + " (auto)");
                    }
                } catch (Throwable t) {
                    IJ.handleException(t);
                    IJ.log("[CLI] " + analyses[IDX_AGGREGATION] + " (auto) FAILED: " + t.getMessage());
                    failedAnalyses.add(analyses[IDX_AGGREGATION] + " (auto)");
                }
            } else {
                IJ.log("[CLI] Auto aggregation skipped: analysis not implemented.");
                failedAnalyses.add(analyses[IDX_AGGREGATION] + " (auto)");
            }
        }

        // Post-run summary, informational only
        PostRunSummary.writeIfPossible(directory);
        writeStartHereIfPossible(directory);

        writeCliStatus(runDir, failedAnalyses.isEmpty(), failedAnalyses, null);
        releaseImageCache();
        IJ.log("[CLI] Pipeline finished. Open FLASH/Results/START_HERE.html to review outputs.");
    }

    /**
     * Replay-only CLI entry. It deliberately executes only the selected
     * analysis rows from the macro options; auto-aggregation is recorded as its
     * own run and should be replayed from that run record when needed.
     */
    public List<RunResult> runReplayCli(String macroOptions,
                                        String parentRunId,
                                        Map<String, Object> replayParameters) {
        cliInvocation = true;
        configureFeatureDependencyGate();
        CLIConfig cfg = CLIArgumentParser.parse(macroOptions);
        if (cfg == null) {
            IJ.log("[Replay] Could not parse replay CLI options.");
            return new ArrayList<RunResult>();
        }
        cliConfig = cfg;

        directory = cfg.getDirectory();
        headlessMode = cfg.isHeadless();
        parallelProcessing = cfg.isParallel();
        parallelThreadCount = cfg.getThreads();
        verboseLogging = cfg.isVerbose();
        overwriteBehavior = cfg.getOverwriteBehavior();
        autoAggregate = false;
        generateQcReport = cfg.isQcReport();
        useTifCache = cfg.isTifCache();
        loaderThreadCount = cfg.getLoaderThreads();
        loaderPercent = cfg.getLoaderPercent();
        gpuPermits = cfg.getGpuPermits();
        GpuConcurrency.setUserOverride(gpuPermits);

        File runDir = new File(directory);
        if (!runDir.isDirectory()) {
            IJ.log("[Replay] Directory does not exist or is not a directory: " + directory);
            return new ArrayList<RunResult>();
        }
        if (!confirmInputSourceAndWarn(false)) {
            return new ArrayList<RunResult>();
        }

        initAnalyses();
        QualityReport qualityReport = createQualityReportForRun(
                directory, generateQcReport, headlessMode, parallelProcessing,
                parallelThreadCount, verboseLogging, overwriteBehavior);

        List<RunResult> results = new ArrayList<RunResult>();
        boolean[] selections = cfg.getSelectedAnalyses();
        for (int i = 0; i < selections.length; i++) {
            if (!selections[i]) {
                continue;
            }
            Analysis analysis = analysisMap.get(i);
            if (analysis == null) {
                IJ.log("[Replay] Analysis not implemented for index " + i);
                continue;
            }
            AnalysisRegistry registry = AnalysisRegistry.forIndex(i);
            if (registry == null) {
                IJ.log("[Replay] Analysis index is not registered: " + i);
                continue;
            }
            IJ.log("[Replay] Running: " + registry.label());
            configureAnalysis(analysis, i, true, qualityReport);
            LoadedRunParameters.Result adapterResult =
                    registry.applyReplayParameters(analysis, replayParameters);
            if (adapterResult.hasIgnoredKeys()) {
                IJ.log("[Replay] Ignored replay parameter keys for "
                        + registry.analysisKey() + ": " + adapterResult.getIgnoredKeys());
            }
            BinSetupDispatcher.clearLastFieldSources();
            try {
                final Analysis selectedAnalysis = analysis;
                final String runDirectory = directory;
                RunResult result = new AnalysisRunCoordinator().run(selectedAnalysis, i, registry.label(),
                        runDirectory, cliConfig, replayParameters, parentRunId, new Callable<Void>() {
                            @Override public Void call() {
                                selectedAnalysis.execute(runDirectory);
                                return null;
                            }
                        });
                results.add(result);
            } finally {
                resetBioFormatsWindowless();
            }
        }

        PostRunSummary.writeIfPossible(directory);
        writeStartHereIfPossible(directory);
        releaseImageCache();
        return results;
    }

    private static void writeStartHereIfPossible(String directory) {
        if (directory == null || directory.trim().isEmpty()) return;
        try {
            StartHereWriter.write(FlashProjectLayout.forDirectory(directory));
        } catch (IOException e) {
            IJ.log("[FLASH] Could not write START_HERE.html: " + e.getMessage());
        }
    }

    static void writeCliStatus(File directory, boolean ok,
                               List<String> failed, String reason) {
        try {
            ProjectStatusStore.writeCliStatus(directory, ok, failed, reason);
        } catch (IOException ioe) {
            IJ.log("[CLI] could not write CLI status: " + ioe.getMessage());
        }
    }

    private boolean confirmInputSourceAndWarn(boolean interactive) {
        try {
            ImageSourceDispatcher.detectMode(directory);
            ImageSourceDispatcher.maybeWarnUncalibrated(directory);
            return true;
        } catch (IllegalArgumentException e) {
            String message = "No compatible input source was found:\n\n"
                    + e.getMessage();
            if (interactive) {
                IJ.showMessage("FLASH", message);
            } else {
                IJ.log("[CLI] " + message.replace('\n', ' '));
            }
            return false;
        }
    }

    private boolean[] showAnalysisDialog() {
        if (cliInvocation || GraphicsEnvironment.isHeadless()) {
            return null;
        }
        while (true) {
            PipelineDialog pd = new PipelineDialog("FLASH - The Pipeline for Fluorescence Automated Spatial Histology");
            pd.setPrimaryButtonText("Run");
            final int[] statusRowsByAnalysis = new int[analyses.length];
            Arrays.fill(statusRowsByAnalysis, -1);
            final int[] nextStatusRow = new int[]{0};
            final boolean[] statusRowsReady = new boolean[]{false};
            @SuppressWarnings("unchecked")
            final Map<Integer, AnalysisStatus>[] pendingStatuses = new Map[1];
            final AnalysisStatusScanner[] pendingScanner = new AnalysisStatusScanner[1];
            final Icon pendingIcon = loadStatusIcon("status_pending.png");
            startAnalysisStatusScan(directory, pd, statusRowsByAnalysis, statusRowsReady,
                    pendingStatuses, pendingScanner);

            addRecipeWarningPanel(pd);

            final ToggleSwitch[] togglesByAnalysis = new ToggleSwitch[analyses.length];
            pd.setNorthSlot(buildQuickStartPanel(pd, togglesByAnalysis,
                    statusRowsByAnalysis, statusRowsReady, pendingStatuses, pendingScanner));

            addAnalysisSection(pd, "Setup", new int[]{
                    IDX_CREATE_BIN,
                    IDX_DRAW_ROIS
            }, pendingIcon, statusRowsByAnalysis, nextStatusRow, togglesByAnalysis);

            addAnalysisSection(pd, "Image Preparation", new int[]{
                    IDX_DECONVOLUTION,
                    IDX_SPECTRAL_DECONTAMINATION
            }, pendingIcon, statusRowsByAnalysis, nextStatusRow, togglesByAnalysis);

            addAnalysisSection(pd, "Display", new int[]{
                    IDX_SPLIT_MERGE,
                    IDX_REPRESENTATIVE_FIGURE
            }, pendingIcon, statusRowsByAnalysis, nextStatusRow, togglesByAnalysis);

            addAnalysisSection(pd, "Image Analysis", new int[]{
                    IDX_INTENSITY,
                    IDX_3D_OBJECT,
                    IDX_SPATIAL
            }, pendingIcon, statusRowsByAnalysis, nextStatusRow, togglesByAnalysis);

            addAnalysisSection(pd, "Results and Validation", new int[]{
                    IDX_AGGREGATION,
                    IDX_STATISTICS,
                    IDX_EXCEL_EXPORT
            }, pendingIcon, statusRowsByAnalysis, nextStatusRow, togglesByAnalysis);
            statusRowsReady[0] = true;
            if (pendingStatuses[0] != null && pendingScanner[0] != null) {
                applyAnalysisStatuses(pd, statusRowsByAnalysis, pendingStatuses[0], pendingScanner[0]);
                pendingStatuses[0] = null;
                pendingScanner[0] = null;
            }

            JButton checkBtn = pd.addFooterButton("Check my data");
            checkBtn.addActionListener(e -> {
                if (directory == null) {
                    IJ.showMessage("Check my data", "Pick a directory first.");
                    return;
                }
                pd.closeWithAction("check_my_data");
            });

            JButton optionsBtn = pd.addFooterButton("Options");
            optionsBtn.addActionListener(e -> pd.runChildWorkflow(new Runnable() {
                @Override public void run() {
                    showOptionsDialog();
                }
            }));

            JButton depsBtn = pd.addFooterButton(dependencyButtonLabel(false));
            depsBtn.setToolTipText("Checking dependency status...");
            startDependencyBadgeRefresh(pd, depsBtn);
            depsBtn.addActionListener(e -> pd.closeWithAction("dependencies"));

            if (!pd.showDialog()) {
                if ("check_my_data".equals(pd.getActionCommand())) {
                    new DiagnosticsDialog(directory).openBlocking();
                    continue;
                }
                if ("dependencies".equals(pd.getActionCommand())) {
                    showDependenciesDialog();
                    continue;
                }
                if ("change_project".equals(pd.getActionCommand())) {
                    switchProjectFromMainDialog(null);
                    continue;
                }
                if ("edit_project_setup".equals(pd.getActionCommand())) {
                    editCurrentProjectSetupFromMainDialog(null);
                    continue;
                }
                return null;
            }

            boolean[] results = new boolean[analyses.length];
            for (int i = 0; i < VISIBLE_ANALYSIS_ORDER.length; i++) {
                results[VISIBLE_ANALYSIS_ORDER[i]] = pd.getNextBoolean();
            }

            ConditionPreflightOutcome outcome = promptConditionPreflight(results);
            if (outcome == ConditionPreflightOutcome.CANCEL) {
                continue; // back to the picker so the user can review or change selection
            }
            skipAutoAggregateForBatch = (outcome == ConditionPreflightOutcome.SKIP_AUTO_AGGREGATION);
            return results;
        }
    }

    enum ConditionPreflightOutcome { PROCEED, SKIP_AUTO_AGGREGATION, CANCEL }

    /**
     * True when at least one selected workflow consumes condition groups, so the
     * user should get a chance to review assignments before the run starts. Auto
     * aggregation from object/spatial/intensity counts because it produces
     * condition-grouped master tables even when aggregation was not selected.
     */
    static boolean selectionNeedsConditionPreflight(boolean[] selections, boolean autoAggregate) {
        if (selections == null) return false;
        if (isSelected(selections, IDX_AGGREGATION)) return true;
        if (isSelected(selections, IDX_STATISTICS)) return true;
        if (isSelected(selections, IDX_EXCEL_EXPORT)) return true;
        if (isSelected(selections, IDX_REPRESENTATIVE_FIGURE)) return true;
        if (autoAggregate
                && (isSelected(selections, IDX_3D_OBJECT)
                    || isSelected(selections, IDX_SPATIAL)
                    || isSelected(selections, IDX_INTENSITY))) {
            return true;
        }
        return false;
    }

    private static boolean isSelected(boolean[] selections, int idx) {
        return selections != null && idx >= 0 && idx < selections.length && selections[idx];
    }

    /** True when only post-run auto aggregation (no explicit consumer) triggers the preflight. */
    private boolean isAutoAggregationOnlyTrigger(boolean[] selections) {
        boolean explicit = isSelected(selections, IDX_AGGREGATION)
                || isSelected(selections, IDX_STATISTICS)
                || isSelected(selections, IDX_EXCEL_EXPORT)
                || isSelected(selections, IDX_REPRESENTATIVE_FIGURE);
        if (explicit) return false;
        return autoAggregate
                && (isSelected(selections, IDX_3D_OBJECT)
                    || isSelected(selections, IDX_SPATIAL)
                    || isSelected(selections, IDX_INTENSITY));
    }

    /**
     * Picker-time gate. Returns {@link ConditionPreflightOutcome#PROCEED} when
     * the selection is not condition-sensitive or no animals exist yet, otherwise
     * lets the user review, skip auto-aggregation, continue anyway, or cancel.
     */
    private ConditionPreflightOutcome promptConditionPreflight(boolean[] selections) {
        if (!selectionNeedsConditionPreflight(selections, autoAggregate)) {
            return ConditionPreflightOutcome.PROCEED;
        }
        boolean autoAggregationOnly = isAutoAggregationOnlyTrigger(selections);
        while (true) {
            LinkedHashSet<String> animals = ResultAnimalScanner.collect(directory);
            if (animals.isEmpty()) {
                // Nothing to review yet; the post-run guard re-checks once results exist.
                return ConditionPreflightOutcome.PROCEED;
            }
            ConditionReviewSupport.Health health =
                    ConditionReviewSupport.evaluate(directory, animals);
            if (!health.needsReview()) {
                return ConditionPreflightOutcome.PROCEED;
            }

            List<String> opts = new ArrayList<String>();
            opts.add("Review conditions...");
            if (autoAggregationOnly) opts.add("Skip auto-aggregation");
            opts.add("Continue anyway");
            opts.add("Cancel run");

            int choice = JOptionPane.showOptionDialog(
                    null,
                    new JLabel(conditionPreflightMessage(health)),
                    "Review conditions before running",
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.WARNING_MESSAGE,
                    null,
                    opts.toArray(),
                    opts.get(0));
            String picked = choice < 0 ? "Cancel run" : opts.get(choice);
            if ("Review conditions...".equals(picked)) {
                reviewConditionsFromPicker(animals);
                continue; // re-evaluate with the freshly saved assignments
            }
            if ("Skip auto-aggregation".equals(picked)) {
                return ConditionPreflightOutcome.SKIP_AUTO_AGGREGATION;
            }
            if ("Continue anyway".equals(picked)) {
                return ConditionPreflightOutcome.PROCEED;
            }
            return ConditionPreflightOutcome.CANCEL;
        }
    }

    private static String conditionPreflightMessage(ConditionReviewSupport.Health health) {
        StringBuilder sb = new StringBuilder("<html><body style='width:380px'>");
        sb.append("<b>Condition assignments need review</b><br>");
        for (String message : health.messages) {
            sb.append(message).append("<br>");
        }
        sb.append("<br>FLASH uses these groups for aggregation, statistics, Excel sheets,"
                + " and representative figures.</body></html>");
        return sb.toString();
    }

    private String conditionStatusSummary() {
        if (directory == null || directory.trim().isEmpty()) {
            return "Pick a directory first.";
        }
        LinkedHashSet<String> animals = ResultAnimalScanner.collect(directory);
        if (animals.isEmpty()) {
            return "No animals found yet - run an analysis or set up the project.";
        }
        return ConditionReviewSupport.evaluate(directory, animals).summary();
    }

    private void updateConditionSummaryLabel(JLabel label) {
        String summary = conditionStatusSummary();
        label.setText(summary);
        label.setToolTipText(summary);
    }

    private void updateDirectorySummaryLabel(JLabel label) {
        String fullPath = directory == null ? "" : directory;
        label.setText(compactDirectoryPathForDisplay(fullPath));
        label.setToolTipText(fullPath.trim().isEmpty() ? "No project selected" : fullPath);
    }

    private void reviewConditionsFromPicker(Set<String> animals) {
        if (animals == null || animals.isEmpty()) {
            IJ.showMessage("Review conditions",
                    "No animals were found yet. Run an analysis or set up the project first.");
            return;
        }
        ConditionReviewSupport.Options options = new ConditionReviewSupport.Options();
        options.title = "Review condition assignments";
        options.primaryButtonText = "Save conditions";
        ConditionReviewSupport.reviewAndSave(null, directory, animals, options);
    }

    private boolean switchProjectFromMainDialog(Window owner) {
        ProjectLaunchSelection picked = chooseProjectFromHome(owner,
                RecentProjectsStore.resolveStoreDir());
        return applyMainDialogProjectSelection(picked);
    }

    private boolean editCurrentProjectSetupFromMainDialog(Window owner) {
        if (!confirmProjectSetupEditIfResultsExist()) {
            return false;
        }
        ProjectBuilderDialog.Result picked = ProjectBuilderDialog.open(
                owner,
                RecentProjectsStore.resolveStoreDir(),
                directory == null ? null : new java.io.File(directory));
        return applyMainDialogProjectSelection(selectionFromBuilder(picked));
    }

    private boolean applyMainDialogProjectSelection(ProjectLaunchSelection picked) {
        if (picked == null || picked.outputRoot == null) {
            return false;
        }
        String previousDirectory = directory;
        boolean previousFastReopen = fastReopen;
        String nextDirectory = picked.outputRoot.getAbsolutePath();
        if (!sameDirectoryPath(previousDirectory, nextDirectory)
                && !PreFlightChecks.confirmProceedOnOutputFolder(nextDirectory, picked.fastReopen)) {
            return false;
        }
        directory = nextDirectory;
        fastReopen = picked.fastReopen;
        if (!confirmInputSourceAndWarn(true)) {
            directory = previousDirectory;
            fastReopen = previousFastReopen;
            return false;
        }
        resetProjectScopedRuntimeState();
        return true;
    }

    private void resetProjectScopedRuntimeState() {
        skipAutoAggregateForBatch = false;
        releaseImageCache();
        initAnalyses();
    }

    private static boolean sameDirectoryPath(String first, String second) {
        if (first == null || second == null) {
            return false;
        }
        try {
            return new File(first).getCanonicalFile().equals(new File(second).getCanonicalFile());
        } catch (IOException e) {
            return new File(first).getAbsoluteFile().equals(new File(second).getAbsoluteFile());
        }
    }

    /**
     * Warn before opening the full project setup editor when analysis results
     * already exist, because animal/hemisphere/region/source/series edits there
     * can invalidate those results. Returns {@code true} to proceed.
     */
    private boolean confirmProjectSetupEditIfResultsExist() {
        if (!projectHasAnalysisResults(directory)) {
            return true;
        }
        String[] options = {"Edit project setup", "Cancel"};
        int choice = JOptionPane.showOptionDialog(
                null,
                "<html><body style='width:380px'>Project setup edits can change animal IDs,"
                        + " hemispheres, regions, source files, or series selection. Existing"
                        + " analysis CSVs are not rewritten automatically.<br><br>If you change"
                        + " these fields, rerun affected analyses before aggregation.<br><br>"
                        + "To only change experimental groups, use \"Review conditions...\""
                        + " instead.</body></html>",
                "Edit project setup?",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[1]);
        return choice == 0;
    }

    private static boolean projectHasAnalysisResults(String dir) {
        if (dir == null || dir.trim().isEmpty()) return false;
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(dir);
        java.io.File[] resultDirs = {
                layout.tablesObjectsWriteDir(),
                layout.tablesIntensityWriteDir(),
                layout.tablesSpatialWriteDir(),
                layout.tablesMorphometryWriteDir(),
                layout.tablesLineDistanceWriteDir()
        };
        for (java.io.File d : resultDirs) {
            if (directoryHasCsv(d)) return true;
        }
        java.io.File summary = layout.tablesProjectSummaryWriteDir();
        if (summary != null) {
            if (new java.io.File(summary, FlashProjectLayout.MASTER_OBJECTS_FILENAME).isFile()) return true;
            if (new java.io.File(summary, FlashProjectLayout.MASTER_INTENSITIES_FILENAME).isFile()) return true;
        }
        return false;
    }

    private static boolean directoryHasCsv(java.io.File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        java.io.File[] csvs = dir.listFiles(new java.io.FilenameFilter() {
            @Override
            public boolean accept(java.io.File d, String name) {
                return name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(".csv");
            }
        });
        return csvs != null && csvs.length > 0;
    }

    /**
     * Post-run guard for auto aggregation: the result set is only complete after
     * the analyses ran, so re-check condition health here. Returns {@code true}
     * to proceed with auto aggregation, {@code false} to skip it for this batch.
     */
    private boolean confirmAutoAggregationConditions() {
        if (GraphicsEnvironment.isHeadless()) return true;
        LinkedHashSet<String> animals = ResultAnimalScanner.collect(directory);
        if (animals.isEmpty()) return true;
        ConditionReviewSupport.Health health =
                ConditionReviewSupport.evaluate(directory, animals);
        if (!health.needsReview()) return true;

        while (true) {
            String[] opts = {"Review conditions...", "Skip auto-aggregation", "Continue anyway"};
            StringBuilder sb = new StringBuilder("<html><body style='width:360px'>");
            sb.append("<b>Before auto-aggregation</b><br>");
            sb.append("FLASH is about to create master summary tables.<br>");
            for (String message : health.messages) {
                sb.append(message).append("<br>");
            }
            sb.append("</body></html>");
            int choice = JOptionPane.showOptionDialog(
                    null, new JLabel(sb.toString()), "Conditions need review",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                    null, opts, opts[0]);
            if (choice == 0) {
                reviewConditionsFromPicker(animals);
                animals = ResultAnimalScanner.collect(directory);
                health = ConditionReviewSupport.evaluate(directory, animals);
                if (!health.needsReview()) return true;
                continue;
            }
            if (choice == 1) return false; // skip auto-aggregation
            return true; // continue anyway / dialog closed
        }
    }

    private JPanel buildQuickStartPanel(final PipelineDialog pd,
                                        final ToggleSwitch[] togglesByAnalysis,
                                        final int[] statusRowsByAnalysis,
                                        final boolean[] statusRowsReady,
                                        final Map<Integer, AnalysisStatus>[] pendingStatuses,
                                        final AnalysisStatusScanner[] pendingScanner) {
        JPanel panel = new JPanel(new java.awt.BorderLayout(18, 0));
        panel.setBackground(new Color(245, 245, 245));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 20, 6, 20));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel quickColumn = new JPanel();
        quickColumn.setLayout(new BoxLayout(quickColumn, BoxLayout.Y_AXIS));
        quickColumn.setOpaque(false);
        quickColumn.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel headerRow = new JPanel();
        headerRow.setLayout(new BoxLayout(headerRow, BoxLayout.X_AXIS));
        headerRow.setOpaque(false);
        headerRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        headerRow.add(topPanelHeaderLabel("Quick start"));
        headerRow.add(Box.createHorizontalStrut(6));

        final JButton helpBtn = HelpButton.question("Open FLASH help and workflow advice.");
        helpBtn.addActionListener(e -> {
            helpBtn.setEnabled(false);
            try {
                new HelpDialog(directory, analyses, DESCRIPTIONS, togglesByAnalysis)
                        .open(pd.getWindow());
            } finally {
                helpBtn.setEnabled(true);
            }
        });
        headerRow.add(helpBtn);
        headerRow.add(Box.createHorizontalGlue());
        quickColumn.add(headerRow);
        quickColumn.add(Box.createVerticalStrut(5));

        final JLabel recipeCaption = new JLabel(
                "<html><body width='300'>Pick a recipe or tick analyses individually.</body></html>");
        recipeCaption.setFont(recipeCaption.getFont().deriveFont(Font.PLAIN, 11f));
        recipeCaption.setForeground(new Color(33, 33, 33));
        recipeCaption.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton standardRecipeBtn = new JButton("Standard 3D + Intensity");
        JButton quickCountRecipeBtn = new JButton("Quick cell count");
        JButton presentationRecipeBtn = new JButton("Presentation");
        JButton fastPresentableResultsBtn = new JButton("Fast Presentable Results");
        JButton fullRecipeBtn = new JButton("Full pipeline");
        JButton lastRunRecipeBtn = new JButton("Last run");
        JButton customRecipeBtn = new JButton("Clear Recipe");
        JButton saveRecipeBtn = new JButton("Save selection as recipe...");
        setRecipeTooltip(standardRecipeBtn, "standard-3d-intensity");
        setRecipeTooltip(quickCountRecipeBtn, "quick-cell-count");
        setRecipeTooltip(presentationRecipeBtn, "presentation");
        setRecipeTooltip(fastPresentableResultsBtn, "fast-presentable-results");
        setRecipeTooltip(fullRecipeBtn, "full-pipeline");
        lastRunRecipeBtn.setToolTipText("Tick the same analyses as the last successful run for this project.");
        customRecipeBtn.setToolTipText("Clear all recipe selections.");
        styleSaveRecipeButton(saveRecipeBtn);
        saveRecipeBtn.setToolTipText("Save the currently ticked analyses as a reusable recipe.");

        JPanel buttonRows = new JPanel();
        buttonRows.setLayout(new BoxLayout(buttonRows, BoxLayout.Y_AXIS));
        buttonRows.setOpaque(false);
        buttonRows.setAlignmentX(Component.LEFT_ALIGNMENT);
        buttonRows.add(recipeButtonRow(standardRecipeBtn, quickCountRecipeBtn));
        buttonRows.add(Box.createVerticalStrut(2));
        buttonRows.add(recipeButtonRow(fullRecipeBtn, presentationRecipeBtn));
        buttonRows.add(Box.createVerticalStrut(2));
        buttonRows.add(recipeButtonRow(fastPresentableResultsBtn));
        buttonRows.add(Box.createVerticalStrut(2));
        buttonRows.add(recipeButtonRow(saveRecipeBtn));
        buttonRows.add(Box.createVerticalStrut(2));
        buttonRows.add(recipeButtonRow(lastRunRecipeBtn, customRecipeBtn));

        standardRecipeBtn.addActionListener(e -> applyRecipe(togglesByAnalysis, recipeCaption, "standard-3d-intensity"));
        quickCountRecipeBtn.addActionListener(e -> applyRecipe(togglesByAnalysis, recipeCaption, "quick-cell-count"));
        presentationRecipeBtn.addActionListener(e -> applyRecipe(togglesByAnalysis, recipeCaption, "presentation"));
        fastPresentableResultsBtn.addActionListener(
                e -> applyRecipe(togglesByAnalysis, recipeCaption, "fast-presentable-results"));
        fullRecipeBtn.addActionListener(e -> applyRecipe(togglesByAnalysis, recipeCaption, "full-pipeline"));
        lastRunRecipeBtn.addActionListener(e -> applyLastRunRecipe(togglesByAnalysis, recipeCaption));
        customRecipeBtn.addActionListener(e -> {
            for (int i = 0; i < togglesByAnalysis.length; i++) {
                if (togglesByAnalysis[i] != null) {
                    togglesByAnalysis[i].setSelected(false);
                }
            }
            recipeCaption.setText("<html><body width='300'>Recipe cleared.</body></html>");
        });
        saveRecipeBtn.addActionListener(e -> saveCurrentSelectionAsRecipe(pd, togglesByAnalysis));

        quickColumn.add(buttonRows);
        quickColumn.add(Box.createVerticalStrut(3));
        quickColumn.add(recipeCaption);

        panel.add(quickColumn, java.awt.BorderLayout.WEST);
        panel.add(buildProjectSummaryPanel(pd, statusRowsByAnalysis, statusRowsReady,
                pendingStatuses, pendingScanner), java.awt.BorderLayout.CENTER);

        return panel;
    }

    @SuppressWarnings("unchecked")
    private JPanel buildQuickStartPanel(final PipelineDialog pd, final ToggleSwitch[] togglesByAnalysis) {
        final int[] statusRowsByAnalysis = new int[analyses.length];
        Arrays.fill(statusRowsByAnalysis, -1);
        final boolean[] statusRowsReady = new boolean[]{false};
        final Map<Integer, AnalysisStatus>[] pendingStatuses = new Map[1];
        final AnalysisStatusScanner[] pendingScanner = new AnalysisStatusScanner[1];
        return buildQuickStartPanel(pd, togglesByAnalysis, statusRowsByAnalysis, statusRowsReady,
                pendingStatuses, pendingScanner);
    }

    private JPanel buildProjectSummaryPanel(final PipelineDialog pd,
                                            final int[] statusRowsByAnalysis,
                                            final boolean[] statusRowsReady,
                                            final Map<Integer, AnalysisStatus>[] pendingStatuses,
                                            final AnalysisStatusScanner[] pendingScanner) {
        JPanel summaryColumn = new JPanel();
        summaryColumn.setLayout(new BoxLayout(summaryColumn, BoxLayout.Y_AXIS));
        summaryColumn.setOpaque(false);
        summaryColumn.setAlignmentX(Component.LEFT_ALIGNMENT);

        final JLabel directoryLabel = topPanelValueLabel("");
        updateDirectorySummaryLabel(directoryLabel);
        final JLabel conditionsLabel = topPanelValueLabel("");
        updateConditionSummaryLabel(conditionsLabel);

        JButton changeBtn = new JButton("Change project...");
        changeBtn.setFocusPainted(false);
        changeBtn.addActionListener(e -> {
            pd.closeWithAction("change_project");
        });

        JButton editSetupBtn = new JButton("Edit setup...");
        editSetupBtn.setFocusPainted(false);
        editSetupBtn.addActionListener(e -> {
            pd.closeWithAction("edit_project_setup");
        });

        JButton reviewBtn = new JButton("Review conditions...");
        reviewBtn.setFocusPainted(false);
        reviewBtn.addActionListener(e -> {
            if (directory == null) {
                IJ.showMessage("Review conditions", "Pick a directory first.");
                return;
            }
            reviewConditionsFromPicker(ResultAnimalScanner.collect(directory));
            updateConditionSummaryLabel(conditionsLabel);
        });

        summaryColumn.add(topPanelHeaderLabel("Current Project"));
        summaryColumn.add(Box.createVerticalStrut(5));
        summaryColumn.add(directoryLabel);
        summaryColumn.add(Box.createVerticalStrut(4));
        summaryColumn.add(recipeButtonRow(changeBtn, editSetupBtn));
        summaryColumn.add(Box.createVerticalStrut(9));
        summaryColumn.add(topPanelHeaderLabel("Conditions"));
        summaryColumn.add(Box.createVerticalStrut(5));
        summaryColumn.add(conditionsLabel);
        summaryColumn.add(Box.createVerticalStrut(4));
        summaryColumn.add(recipeButtonRow(reviewBtn));
        summaryColumn.add(Box.createVerticalGlue());
        return summaryColumn;
    }

    private JLabel topPanelHeaderLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
        label.setForeground(new Color(55, 71, 79));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private JLabel topPanelValueLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
        label.setForeground(new Color(33, 33, 33));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setMaximumSize(new java.awt.Dimension(320, 18));
        return label;
    }

    static String compactDirectoryPathForDisplay(String path) {
        return compactDirectoryPathForDisplay(path, 46);
    }

    static String compactDirectoryPathForDisplay(String path, int maxChars) {
        if (path == null || path.trim().isEmpty()) {
            return "No project selected";
        }
        String trimmed = path.trim();
        if (maxChars <= 0 || trimmed.length() <= maxChars) {
            return trimmed;
        }

        String separator = trimmed.indexOf('\\') >= 0 ? "\\" : "/";
        String[] rawParts = trimmed.split("[\\\\/]+");
        List<String> parts = new ArrayList<String>();
        for (String part : rawParts) {
            if (part != null && !part.isEmpty()) {
                parts.add(part);
            }
        }
        if (parts.isEmpty()) {
            return trimmed.substring(Math.max(0, trimmed.length() - maxChars));
        }

        String prefix = "..." + separator;
        String tail = parts.get(parts.size() - 1);
        for (int i = parts.size() - 2; i >= 0; i--) {
            String candidate = parts.get(i) + separator + tail;
            if ((prefix + candidate).length() > maxChars) {
                break;
            }
            tail = candidate;
        }

        String compact = prefix + tail;
        if (compact.length() <= maxChars) {
            return compact;
        }
        int availableTailChars = maxChars - prefix.length();
        if (availableTailChars <= 0) {
            return trimmed.substring(Math.max(0, trimmed.length() - maxChars));
        }
        return prefix + tail.substring(Math.max(0, tail.length() - availableTailChars));
    }

    private JPanel recipeButtonRow(JButton first) {
        JPanel row = leftAlignedButtonRow();
        row.add(first);
        row.add(Box.createHorizontalGlue());
        return row;
    }

    private JPanel recipeButtonRow(JButton first, JButton second) {
        return recipeButtonRow(first, second, null);
    }

    private JPanel recipeButtonRow(JButton first, JButton second, JButton third) {
        JPanel row = leftAlignedButtonRow();
        row.add(first);
        row.add(Box.createHorizontalStrut(6));
        row.add(second);
        if (third != null) {
            row.add(Box.createHorizontalStrut(6));
            row.add(third);
        }
        row.add(Box.createHorizontalGlue());
        return row;
    }

    private JPanel leftAlignedButtonRow() {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        return row;
    }

    private void styleSaveRecipeButton(JButton button) {
        styleSecondaryButton(button, 3, 10);
    }

    private void styleCompactSecondaryButton(JButton button) {
        styleSecondaryButton(button, 2, 8);
    }

    private void styleSecondaryButton(JButton button, int verticalPadding, int horizontalPadding) {
        button.setBackground(SAVE_RECIPE_BG);
        button.setForeground(SAVE_RECIPE_FG);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(SAVE_RECIPE_BORDER),
                BorderFactory.createEmptyBorder(verticalPadding, horizontalPadding,
                        verticalPadding, horizontalPadding)));
    }

    private void addAnalysisSection(PipelineDialog pd, String heading, int[] analysisIndices,
                                    Icon pendingIcon, int[] statusRowsByAnalysis, int[] nextStatusRow,
                                    ToggleSwitch[] togglesByAnalysis) {
        final ToggleSwitch sectionToggle = pd.addHeaderToggle(heading, false);
        final List<ToggleSwitch> childToggles = new java.util.ArrayList<ToggleSwitch>();
        final boolean[] updating = new boolean[]{false};
        for (int i = 0; i < analysisIndices.length; i++) {
            final ToggleSwitch child = addAnalysisToggle(pd, analysisIndices[i],
                    pendingIcon, statusRowsByAnalysis, nextStatusRow);
            if (togglesByAnalysis != null && analysisIndices[i] >= 0
                    && analysisIndices[i] < togglesByAnalysis.length) {
                togglesByAnalysis[analysisIndices[i]] = child;
            }
            childToggles.add(child);
            child.addChangeListener(new Runnable() {
                @Override public void run() {
                    if (updating[0]) return;
                    boolean allSelected = true;
                    for (ToggleSwitch toggle : childToggles) {
                        if (!toggle.isSelected()) {
                            allSelected = false;
                            break;
                        }
                    }
                    updating[0] = true;
                    sectionToggle.setSelected(allSelected);
                    updating[0] = false;
                }
            });
        }
        sectionToggle.addChangeListener(new Runnable() {
            @Override public void run() {
                if (updating[0]) return;
                updating[0] = true;
                boolean selected = sectionToggle.isSelected();
                for (ToggleSwitch toggle : childToggles) {
                    toggle.setSelected(selected);
                }
                updating[0] = false;
            }
        });
    }

    private ToggleSwitch addAnalysisToggle(PipelineDialog pd, int analysisIndex,
                                           Icon pendingIcon, int[] statusRowsByAnalysis,
                                           int[] nextStatusRow) {
        JLabel statusIcon = new JLabel(pendingIcon);
        statusIcon.setToolTipText("Scanning...");
        int rowIndex = nextStatusRow[0]++;
        statusRowsByAnalysis[analysisIndex] = rowIndex;
        final JButton help = HelpButton.question("About " + analyses[analysisIndex]);
        help.addActionListener(e -> openAnalysisHelp(pd, analysisIndex));
        ToggleSwitch toggle = pd.addToggleWithStatus(analyses[analysisIndex], false, statusIcon, help);
        pd.addHelpText(DESCRIPTIONS[analysisIndex]);
        return toggle;
    }

    private void openAnalysisHelp(PipelineDialog parent, int analysisIndex) {
        AnalysisHelpTopic topic = resolveAnalysisHelpTopic(analysisIndex);
        if (topic == null) {
            JOptionPane.showMessageDialog(parent == null ? null : parent.getWindow(),
                    "Focused help is not available for this analysis yet.",
                    "Analysis Help",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        AnalysisHelpDialog.show(parent == null ? null : parent.getWindow(), topic);
    }

    private static AnalysisHelpTopic resolveAnalysisHelpTopic(int analysisIndex) {
        return AnalysisHelpCatalog.forAnalysis(analysisIndex);
    }

    /** Test hook for the topic that a visible row help button will open. */
    public static AnalysisHelpTopic analysisHelpTopicForTests(int analysisIndex) {
        return resolveAnalysisHelpTopic(analysisIndex);
    }

    /** Test hook for matching catalog topic titles to main-dialog labels. */
    public String analysisLabelForTests(int analysisIndex) {
        return analyses[analysisIndex];
    }

    private void applyRecipe(ToggleSwitch[] togglesByAnalysis, JLabel caption, String recipeId) {
        try {
            PipelineRecipe recipe = PipelineRecipeIO.loadFromResources(recipeId);
            for (int i = 0; i < togglesByAnalysis.length; i++) {
                if (togglesByAnalysis[i] != null) {
                    togglesByAnalysis[i].setSelected(false);
                }
            }

            int tickedCount = 0;
            for (String key : recipe.getAnalyses()) {
            Integer idx = PipelineRecipe.KEY_TO_IDX.get(key);
            if (idx == null || idx.intValue() < 0 || idx.intValue() >= togglesByAnalysis.length) {
                continue;
            }
            if (!isVisibleAnalysisIndex(idx.intValue())) {
                continue;
            }
            ToggleSwitch toggle = togglesByAnalysis[idx.intValue()];
            if (toggle == null) {
                continue;
                }
                toggle.setSelected(true);
                tickedCount++;
            }
            if (tickedCount == 0) {
                caption.setText("<html><body width='280'>Recipe did not match any visible analyses.</body></html>");
                return;
            }
            List<String> unknown = recipe.unknownAnalysisKeys();
            StringBuilder message = new StringBuilder("Applied recipe: ");
            message.append(recipe.getName()).append(".");
            if (!unknown.isEmpty()) {
                message.append(" Unknown recipe keys ignored: ").append(unknown).append(".");
            }
            caption.setText("<html><body width='280'>" + htmlText(message.toString()) + "</body></html>");
        } catch (IOException e) {
            caption.setText("<html><body width='280'>Could not load recipe: "
                    + htmlText(e.getMessage()) + "</body></html>");
            IJ.log("[FLASH] Could not load pipeline recipe '" + recipeId + "': " + e.getMessage());
        }
    }

    private void applyLastRunRecipe(ToggleSwitch[] togglesByAnalysis, JLabel caption) {
        if (directory == null) {
            caption.setText("<html><body width='280'>Pick a project before loading the last run.</body></html>");
            return;
        }
        try {
            boolean[] selections = readLastRunRecipeSelections(directory, analyses.length);
            if (selections == null) {
                caption.setText("<html><body width='280'>No successful previous run was found for this project.</body></html>");
                return;
            }
            int tickedCount = applySelectionsToToggles(togglesByAnalysis, selections);
            if (tickedCount == 0) {
                caption.setText("<html><body width='280'>Last run did not match any visible analyses.</body></html>");
                return;
            }
            caption.setText("<html><body width='280'>Applied last successful run.</body></html>");
        } catch (IOException e) {
            caption.setText("<html><body width='280'>Could not load last run: "
                    + htmlText(e.getMessage()) + "</body></html>");
            IJ.log("[FLASH] Could not load last-run recipe: " + e.getMessage());
        }
    }

    private void setRecipeTooltip(JButton button, String recipeId) {
        try {
            PipelineRecipe recipe = PipelineRecipeIO.loadFromResources(recipeId);
            button.setToolTipText("<html><body width='280'>"
                    + htmlText(buildRecipeSelectionSummary(recipe, analyses)) + "</body></html>");
        } catch (IOException e) {
            button.setToolTipText("Could not load recipe preview: " + e.getMessage());
        }
    }

    static String buildRecipeSelectionSummary(PipelineRecipe recipe, String[] analysisLabels) {
        if (recipe == null) {
            return "Recipe did not match any visible analyses.";
        }
        StringBuilder ticked = new StringBuilder("This will tick: ");
        int tickedCount = 0;
        for (String key : recipe.getAnalyses()) {
            Integer idx = PipelineRecipe.KEY_TO_IDX.get(key);
            if (idx == null || idx.intValue() < 0 || idx.intValue() >= analysisLabels.length) {
                continue;
            }
            if (!isVisibleAnalysisIndex(idx.intValue())) {
                continue;
            }
            if (tickedCount > 0) {
                ticked.append(", ");
            }
            ticked.append(analysisLabels[idx.intValue()]);
            tickedCount++;
        }
        if (tickedCount == 0) {
            return "Recipe did not match any visible analyses.";
        }
        List<String> unknown = recipe.unknownAnalysisKeys();
        if (!unknown.isEmpty()) {
            ticked.append(". Unknown recipe keys ignored: ").append(unknown);
        } else {
            ticked.append(".");
        }
        return ticked.toString();
    }

    private static boolean isVisibleAnalysisIndex(int analysisIndex) {
        for (int visible : VISIBLE_ANALYSIS_ORDER) {
            if (visible == analysisIndex) return true;
        }
        return false;
    }

    private void saveCurrentSelectionAsRecipe(PipelineDialog pd, ToggleSwitch[] togglesByAnalysis) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        String name = JOptionPane.showInputDialog(null, "Recipe name:");
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        try {
            PipelineRecipe recipe = PipelineRecipe.fromSelections(name.trim(), "User saved recipe",
                    selectionsFromToggles(togglesByAnalysis));
            File saved = PipelineRecipeIO.saveToUserDir(recipe);
            pd.setTransientStatus("Saved recipe: " + saved.getAbsolutePath());
        } catch (IOException e) {
            pd.setTransientStatus("Could not save recipe: " + e.getMessage());
            IJ.log("[FLASH] Could not save pipeline recipe: " + e.getMessage());
        }
    }

    private void addRecipeWarningPanel(PipelineDialog pd) {
        List<String> unknown = collectRecipeWarnings();
        if (unknown.isEmpty()) {
            return;
        }
        JPanel warn = new JPanel();
        warn.setLayout(new BoxLayout(warn, BoxLayout.X_AXIS));
        warn.setBackground(new Color(255, 243, 205));
        warn.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        warn.add(new JLabel("<html><body width='300'>Recipe references unknown analyses: "
                + htmlText(unknown.toString()) + ". Known analyses were substituted automatically.</body></html>"));
        pd.addComponent(warn);
    }

    private List<String> collectRecipeWarnings() {
        List<String> unknown = new java.util.ArrayList<String>();
        collectRecipeWarnings("standard-3d-intensity", unknown);
        collectRecipeWarnings("quick-cell-count", unknown);
        collectRecipeWarnings("presentation", unknown);
        collectRecipeWarnings("fast-presentable-results", unknown);
        collectRecipeWarnings("full-pipeline", unknown);
        return unknown;
    }

    private void collectRecipeWarnings(String recipeId, List<String> unknown) {
        try {
            PipelineRecipe recipe = PipelineRecipeIO.loadFromResources(recipeId);
            for (String key : recipe.unknownAnalysisKeys()) {
                String entry = recipeId + ": " + key;
                if (!unknown.contains(entry)) {
                    unknown.add(entry);
                }
            }
        } catch (IOException e) {
            // A built-in recipe FILE that will not load is a packaging/classpath
            // problem (e.g. running a stale plugin jar), NOT a "recipe references
            // unknown analyses" condition. Log it instead of surfacing it in the
            // alarming warning panel, which previously mislabelled the failure.
            IJ.log("[FLASH] Could not load built-in pipeline recipe '" + recipeId
                    + "': " + e.getMessage());
        }
    }

    private void saveProjectRecipe(boolean[] selections) {
        try {
            PipelineRecipe recipe = PipelineRecipe.fromSelections("last-run", "Last successful run", selections);
            ProjectStatusStore.writeLastRunRecipe(directory, recipe.toJsonObject());
        } catch (IOException e) {
            IJ.log("[FLASH] Warning: could not save project pipeline recipe: " + e.getMessage());
        }
    }

    static boolean[] readLastRunRecipeSelections(String projectDirectory, int length) throws IOException {
        Map<String, Object> recipe = ProjectStatusStore.readLastRunRecipe(projectDirectory);
        if (recipe == null) {
            return null;
        }
        return PipelineRecipe.fromJsonObject(recipe).toSelections(length);
    }

    static int applySelectionsToToggles(ToggleSwitch[] togglesByAnalysis, boolean[] selections) {
        if (togglesByAnalysis == null || selections == null) {
            return 0;
        }
        int applied = 0;
        for (int i = 0; i < togglesByAnalysis.length; i++) {
            ToggleSwitch toggle = togglesByAnalysis[i];
            if (toggle == null) {
                continue;
            }
            boolean selected = i < selections.length && selections[i];
            toggle.setSelected(selected);
            if (selected) {
                applied++;
            }
        }
        return applied;
    }

    private boolean[] selectionsFromToggles(ToggleSwitch[] togglesByAnalysis) {
        boolean[] selections = new boolean[analyses.length];
        if (togglesByAnalysis == null) {
            return selections;
        }
        for (int i = 0; i < togglesByAnalysis.length && i < selections.length; i++) {
            selections[i] = togglesByAnalysis[i] != null && togglesByAnalysis[i].isSelected();
        }
        return selections;
    }

    private void startAnalysisStatusScan(final String scanDirectory,
                                         final PipelineDialog pd,
                                         final int[] statusRowsByAnalysis,
                                         final boolean[] statusRowsReady,
                                         final Map<Integer, AnalysisStatus>[] pendingStatuses,
                                         final AnalysisStatusScanner[] pendingScanner) {
        final AnalysisStatusScanner scanner = new AnalysisStatusScanner();
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                final Map<Integer, AnalysisStatus> statuses = scanner.scan(new File(scanDirectory));
                SwingUtilities.invokeLater(new Runnable() {
                    @Override public void run() {
                        if (scanDirectory == null || !scanDirectory.equals(directory)) {
                            return;
                        }
                        if (!statusRowsReady[0]) {
                            pendingStatuses[0] = statuses;
                            pendingScanner[0] = scanner;
                            return;
                        }
                        applyAnalysisStatuses(pd, statusRowsByAnalysis, statuses, scanner);
                    }
                });
            }
        }, "FLASH status scanner");
        thread.setDaemon(true);
        thread.start();
    }

    private void applyAnalysisStatuses(PipelineDialog pd,
                                       int[] statusRowsByAnalysis,
                                       Map<Integer, AnalysisStatus> statuses,
                                       AnalysisStatusScanner scanner) {
        if (pd == null || statuses == null || scanner == null) return;
        for (int i = 0; i < analyses.length; i++) {
            int row = statusRowsByAnalysis[i];
            if (row < 0) continue;
            AnalysisStatus status = statuses.get(Integer.valueOf(i));
            if (status == null) status = AnalysisStatus.NOT_STARTED;
            pd.updateRowIcon(row, statusIconFor(status), scanner.tooltipFor(i));
        }
    }

    private Icon statusIconFor(AnalysisStatus status) {
        if (status == AnalysisStatus.DONE) return loadStatusIcon("status_done.png");
        if (status == AnalysisStatus.STALE) return loadStatusIcon("status_stale.png");
        return loadStatusIcon("status_pending.png");
    }

    private Icon loadStatusIcon(String resourceName) {
        if (cliInvocation || GraphicsEnvironment.isHeadless()) return null;
        URL url = getClass().getResource("/icons/" + resourceName);
        if (url == null) {
            // Fallback for unusual classloader nesting: the thread context
            // loader sees the plugin jar in some Fiji/SciJava launch paths.
            ClassLoader ctx = Thread.currentThread().getContextClassLoader();
            if (ctx != null) {
                url = ctx.getResource("icons/" + resourceName);
            }
        }
        if (url == null) {
            if (!statusIconResourceWarningLogged) {
                statusIconResourceWarningLogged = true;
                IJ.log("[FLASH] Status badge icons not found on the running plugin"
                        + " classpath (/icons/" + resourceName + "). Green/amber"
                        + " status ticks will not render even when analyses are"
                        + " complete. This usually means Fiji is running an older"
                        + " FLASH jar than the one on disk; redeploy and restart Fiji.");
            }
            return null;
        }
        return new ImageIcon(url);
    }

    /**
     * Resets Bio-Formats windowless mode to false so that drag-and-drop
     * imports show the normal Bio-Formats dialog after the pipeline finishes.
     */
    private static void resetBioFormatsWindowless() {
        BioFormatsRuntime.resetWindowlessModeIfTouched();
    }

    boolean executeAnalysisSafelyForGui(Analysis analysis, int index, String runDirectory) {
        if (analysis == null) return false;
        lastGuiAnalysisCancelled = false;
        String label = analysisLabel(index);
        AnalysisCancellation.Scope cancelScope = AnalysisCancellation.openGuiAnalysisScope();
        try {
            final Analysis selectedAnalysis = analysis;
            final String selectedDirectory = runDirectory;
            RunResult result = new AnalysisRunCoordinator().run(selectedAnalysis, index, label,
                    selectedDirectory, cliConfig, null, "", new Callable<Void>() {
                        @Override public Void call() {
                            selectedAnalysis.execute(selectedDirectory);
                            return null;
                        }
                    });
            if (RunResult.STATUS_CANCELLED.equals(result.status)) {
                lastGuiAnalysisCancelled = true;
                return false;
            }
            return BinSetupDispatcher.getLastOutcome() != BinSetupDispatcher.Outcome.CANCELLED;
        } catch (Throwable t) {
            rethrowControlThrowable(t);
            reportGuiStepFailure(label, t);
            return false;
        } finally {
            cancelScope.close();
            resetBioFormatsWindowless();
        }
    }

    void runGuiStepSafely(String label, Runnable step) {
        if (step == null) return;
        try {
            step.run();
        } catch (Throwable t) {
            rethrowControlThrowable(t);
            reportGuiStepFailure(label, t);
        }
    }

    private String analysisLabel(int index) {
        if (index >= 0 && index < analyses.length) return analyses[index];
        return "Analysis";
    }

    private void reportGuiStepFailure(String label, Throwable t) {
        String context = label == null || label.trim().isEmpty() ? "FLASH" : label.trim();
        if (t instanceof NoClassDefFoundError
                && PluginInstallGuard.reportMissingInternalClass(context, (NoClassDefFoundError) t)) {
            return;
        }
        String message = describeThrowable(t);
        IJ.log("[FLASH] " + context + " FAILED: " + message);
        try {
            IJ.handleException(t);
        } catch (Throwable ignored) {
            // Keep the GUI runner alive even if ImageJ's exception handler fails.
        }
        if (!GraphicsEnvironment.isHeadless()) {
            IJ.showMessage("FLASH",
                    context + " failed.\n\n"
                    + message + "\n\n"
                    + "FLASH will return to the main analysis window.");
        }
    }

    private static String describeThrowable(Throwable t) {
        if (t == null) return "Unknown error";
        String type = t.getClass().getSimpleName();
        String message = t.getMessage();
        if (message == null || message.trim().isEmpty()) return type;
        return type + ": " + message.trim();
    }

    private static void rethrowControlThrowable(Throwable t) {
        if (t instanceof ThreadDeath) {
            throw (ThreadDeath) t;
        }
    }

    private void configureFeatureDependencyGate() {
        FeatureDependencyGate.configure(dependencyService, new FeatureDependencyGate.DependenciesDialogOpener() {
            @Override
            public void openDependenciesDialog() {
                showDependenciesDialog();
            }
        });
    }

    private void showOptionsDialog() {
        PipelineDialog opts = new PipelineDialog("Pipeline Options");

        opts.addHeader("Display Settings");
        opts.addToggle("Hide Image Windows", headlessMode);
        opts.addHelpText("When enabled, image windows are not displayed during batch "
                + "processing. Improves performance and reduces visual clutter.");
        opts.addHelpText("Note: Headless Mode is not fully supported for 3D Object Analysis "
                + "due to external dependencies. Image windows may briefly appear during 3D processing.");
        opts.addToggle("Always show advanced options", ij.Prefs.get("flash.advanced.global", false));
        opts.addHelpText("When enabled, every module's advanced options panel opens by default. "
                + "Otherwise each module remembers its own setting.");

        opts.addHeader("Performance");
        final ToggleSwitch parallelToggle = opts.addToggle("Parallel Processing", parallelProcessing);
        opts.addHelpText("Process multiple images simultaneously using " + Runtime.getRuntime().availableProcessors()
                + " available CPU cores. Dramatically reduces batch processing time for large datasets.");
        final JTextField threadCountField = opts.addNumericField("Thread Count", parallelThreadCount, 0);
        threadCountField.setEnabled(parallelProcessing);
        parallelToggle.addChangeListener(() -> threadCountField.setEnabled(parallelToggle.isSelected()));
        opts.addHelpText("Number of images to process in parallel (recommended: "
                + Math.max(1, Runtime.getRuntime().availableProcessors() / 2) + " for this machine, max: "
                + Runtime.getRuntime().availableProcessors() + ").");
        final JTextField gpuPermitsField = opts.addNumericField(
                "GPU Inference Permits (0 = auto-detect)", gpuPermits, 0);
        int autoNow = GpuConcurrency.getAutoDetectedPermits();
        String autoText = autoNow > 0 ? String.valueOf(autoNow) : "probing…";
        gpuPermitsField.setToolTipText("Auto-detected: " + autoText
                + ". Caps simultaneous StarDist + Cellpose GPU inferences. 0 uses the auto value.");
        opts.addHelpText("Concurrent GPU inference workers (StarDist + Cellpose share one GPU). "
                + "Auto-detected from VRAM, heap, and system RAM. Override here only if you have "
                + "a reason. Auto value: " + autoText + ".");
        opts.addToggle("Cache Images as TIFs", useTifCache);
        opts.addHelpText("Save raw images as individual TIF files on first load. "
                + "Subsequent analyses load from TIFs instead of re-parsing the .lif file (much faster).");

        opts.addHeader("Logging");
        opts.addToggle("Verbose Logging / Debug Mode", verboseLogging);
        opts.addHelpText("Prints detailed step-by-step information during processing, "
                + "including ROI indices, threshold values, and sub-step timing.");

        opts.addHeader("File Handling");
        opts.addChoice("File Overwrite Behavior",
                new String[]{"Auto-Overwrite", "Skip Existing"}, overwriteBehavior);
        opts.addHelpText("Auto-Overwrite: always regenerate output files. "
                + "Skip Existing: skip images whose output files already exist.");

        opts.addHeader("Export");
        opts.addToggle("Auto-Save Aggregated Summaries", autoAggregate);
        opts.addHelpText("When enabled, master summary CSVs are automatically generated "
                + "in FLASH/Results/Tables/Project Summary after running 3D Object or Intensity analyses. "
                + "Disable to skip automatic aggregation.");

        opts.addHeader("Reporting");
        opts.addToggle("Generate QC Report", generateQcReport);
        opts.addHelpText("Generate an HTML quality control report documenting analysis parameters "
                + "and segmentation overlays for reproducibility verification.");

        if (opts.showDialog()) {
            headlessMode = opts.getNextBoolean();
            boolean newAdvancedGlobal = opts.getNextBoolean();
            ij.Prefs.set("flash.advanced.global", newAdvancedGlobal);
            ij.Prefs.savePreferences();
            parallelProcessing = opts.getNextBoolean();
            int maxThreads = Runtime.getRuntime().availableProcessors();
            parallelThreadCount = Math.max(1, Math.min((int) opts.getNextNumber(), maxThreads));
            gpuPermits = Math.max(0, (int) opts.getNextNumber());
            GpuConcurrency.setUserOverride(gpuPermits);
            useTifCache = opts.getNextBoolean();
            verboseLogging = opts.getNextBoolean();
            overwriteBehavior = opts.getNextChoice();
            autoAggregate = opts.getNextBoolean();
            generateQcReport = opts.getNextBoolean();
        }
    }

    private void showStartupDependencyWarningIfNeeded() {
        if (cliInvocation || GraphicsEnvironment.isHeadless()) {
            return;
        }

        while (true) {
            if (dependencyRestartPending) {
                showDependencyRestartPrompt();
                return;
            }

            List<DependencyService.DialogRow> rows = refreshDependencyAttentionRows();
            if (rows.isEmpty()) {
                return;
            }

            DependencyFixPlan fixPlan = dependencyService.planFixAll();
            PipelineDialog pd = new PipelineDialog("FLASH Dependencies Need Attention");
            pd.setDefaultButtonsVisible(false);
            pd.addHeader("Missing Dependencies");
            pd.addMessage("FLASH found missing or conflicting dependencies. Fix these before running affected analyses to avoid failed runs.");
            pd.addSpacer(4);

            int shown = Math.min(rows.size(), 8);
            for (int i = 0; i < shown; i++) {
                DependencyService.DialogRow row = rows.get(i);
                pd.addMessage(row.getSpec().getDisplayName() + " - " + row.getStatusLabel());
                String detail = firstDependencyDetailLine(row.getStatusDetail());
                if (!detail.isEmpty()) {
                    pd.addHelpText(detail);
                }
            }
            if (rows.size() > shown) {
                pd.addHelpText((rows.size() - shown) + " more dependency issue(s) are listed in Dependencies.");
            }

            JButton autoFixBtn = pd.addFooterButton(buildAutoFixAllLabel(fixPlan));
            autoFixBtn.setEnabled(!fixPlan.getDependenciesToFix().isEmpty());
            if (fixPlan.getDependenciesToFix().isEmpty()) {
                autoFixBtn.setToolTipText("No missing dependency currently has an in-app auto-fix.");
            }
            autoFixBtn.addActionListener(e -> pd.closeWithAction("auto_fix_all"));

            JButton openBtn = pd.addFooterButton("Open Dependencies");
            openBtn.addActionListener(e -> pd.closeWithAction("open_dependencies"));
            JButton continueBtn = pd.addFooterButton("Continue Anyway");
            continueBtn.addActionListener(e -> pd.closeWithAction("continue_anyway"));

            pd.showDialog();
            String action = pd.getActionCommand();
            if ("auto_fix_all".equals(action)) {
                DependencyRepairOutcome outcome = runAutoFixAll(fixPlan);
                if (outcome.shouldPauseForRestart()) {
                    markDependencyRestartPending(outcome.getMessage());
                    showDependencyRestartPrompt();
                    return;
                }
                continue;
            }
            if ("open_dependencies".equals(action)) {
                showDependenciesDialog();
                if (dependencyRestartPending) {
                    return;
                }
                continue;
            }
            return;
        }
    }

    private List<DependencyService.DialogRow> refreshDependencyAttentionRows() {
        try {
            dependencyService.refreshStatuses();
            return dependencyService.getDialogRowsNeedingAttention();
        } catch (RuntimeException e) {
            IJ.log("[FLASH] Could not refresh dependency warning status: " + e.getMessage());
            return new ArrayList<DependencyService.DialogRow>();
        }
    }

    private void startDependencyBadgeRefresh(final PipelineDialog pd, final JButton depsBtn) {
        if (pd == null || depsBtn == null) {
            return;
        }
        final Color defaultForeground = depsBtn.getForeground();
        final boolean[] openedOrClosed = new boolean[]{false};
        final java.util.concurrent.atomic.AtomicInteger refreshGeneration =
                new java.util.concurrent.atomic.AtomicInteger();
        pd.getWindow().addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowOpened(java.awt.event.WindowEvent e) {
                openedOrClosed[0] = true;
            }

            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                openedOrClosed[0] = true;
            }
        });

        refreshDependencyBadgeAsync(pd, depsBtn, defaultForeground, openedOrClosed, refreshGeneration);
        CellposeRuntime.probeAsync().whenComplete(
                new java.util.function.BiConsumer<CellposeRuntime.Status, Throwable>() {
                    @Override public void accept(CellposeRuntime.Status status, Throwable throwable) {
                        refreshDependencyBadgeAsync(
                                pd, depsBtn, defaultForeground, openedOrClosed, refreshGeneration);
                    }
                });
    }

    private void refreshDependencyBadgeAsync(final PipelineDialog pd,
                                             final JButton depsBtn,
                                             final Color defaultForeground,
                                             final boolean[] openedOrClosed,
                                             final java.util.concurrent.atomic.AtomicInteger refreshGeneration) {
        final int generation = refreshGeneration.incrementAndGet();
        new javax.swing.SwingWorker<List<DependencyService.DialogRow>, Void>() {
            @Override protected List<DependencyService.DialogRow> doInBackground() {
                return refreshDependencyAttentionRows();
            }

            @Override protected void done() {
                if (generation != refreshGeneration.get()) {
                    return;
                }
                if (openedOrClosed[0] && !pd.getWindow().isDisplayable()) {
                    return;
                }
                List<DependencyService.DialogRow> rows;
                try {
                    rows = get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (java.util.concurrent.ExecutionException e) {
                    IJ.log("[FLASH] Could not update dependency badge: " + e.getMessage());
                    return;
                }
                boolean hasDependencyIssues = rows != null && !rows.isEmpty();
                depsBtn.setText(dependencyButtonLabel(hasDependencyIssues));
                depsBtn.setForeground(hasDependencyIssues ? new Color(183, 28, 28) : defaultForeground);
                depsBtn.setToolTipText(dependencyAttentionTooltip(rows));
                depsBtn.revalidate();
                depsBtn.repaint();
            }
        }.execute();
    }

    static String dependencyButtonLabel(boolean hasDependencyIssues) {
        return hasDependencyIssues ? "Dependencies !" : "Dependencies";
    }

    private static String dependencyAttentionTooltip(List<DependencyService.DialogRow> rows) {
        int count = rows == null ? 0 : rows.size();
        if (count <= 0) {
            return "Open dependency checks and installers.";
        }
        return count + " dependency issue(s) need attention before affected analyses will run.";
    }

    private static String firstDependencyDetailLine(String detail) {
        if (detail == null) {
            return "";
        }
        String trimmed = detail.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        int newline = trimmed.indexOf('\n');
        if (newline >= 0) {
            return trimmed.substring(0, newline).trim();
        }
        return trimmed;
    }

    private void showDependenciesDialog() {
        if (dependencyRestartPending) {
            showDependencyRestartPrompt();
            return;
        }

        while (true) {
            dependencyService.refreshStatuses();
            List<DependencyService.DialogRow> rows = dependencyService.getDialogRows();
            DependencyFixPlan fixPlan = dependencyService.planFixAll();

            PipelineDialog pd = new PipelineDialog("Pipeline Dependencies");
            pd.setDefaultButtonsVisible(false);
            pd.addMessage("Each row comes from the runtime registry. Missing items only block the features listed on that row.");
            pd.addSpacer(4);

            String lastSection = "";
            for (DependencyService.DialogRow row : rows) {
                String sectionLabel = row.getSectionLabel();
                if (sectionLabel != null && !sectionLabel.isEmpty() && !sectionLabel.equals(lastSection)) {
                    pd.addHeader(sectionLabel);
                    lastSection = sectionLabel;
                } else if (sectionLabel == null || sectionLabel.isEmpty()) {
                    lastSection = "";
                }
                pd.addComponent(createDependencyRowPanel(pd, row));
            }

            JButton autoFixAllBtn = pd.addFooterButton(buildAutoFixAllLabel(fixPlan));
            autoFixAllBtn.addActionListener(e -> pd.closeWithAction("auto_fix_all"));
            JButton refreshBtn = pd.addFooterButton("Refresh");
            refreshBtn.addActionListener(e -> pd.closeWithAction("refresh"));
            JButton closeBtn = pd.addFooterButton("Close");
            closeBtn.addActionListener(e -> pd.closeWithAction("close"));

            pd.showDialog();

            String action = pd.getActionCommand();
            if ("refresh".equals(action)) {
                dependencyService.invalidateStatusCache();
                continue;
            }
            if ("auto_fix_all".equals(action)) {
                DependencyRepairOutcome outcome = runAutoFixAll(fixPlan);
                if (outcome.shouldPauseForRestart()) {
                    markDependencyRestartPending(outcome.getMessage());
                    showDependencyRestartPrompt();
                    return;
                }
                continue;
            }
            if (action != null && action.startsWith("row:")) {
                DependencyRepairOutcome outcome = handleDependencyRowAction(action);
                if (outcome.shouldPauseForRestart()) {
                    markDependencyRestartPending(outcome.getMessage());
                    showDependencyRestartPrompt();
                    return;
                }
                continue;
            }
            return;
        }
    }

    private JPanel createDependencyRowPanel(final PipelineDialog pd, final DependencyService.DialogRow row) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setOpaque(true);
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(220, 220, 220)),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));

        JLabel title = new JLabel(row.getSpec().getDisplayName());
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 12f));
        card.add(title);
        card.add(Box.createVerticalStrut(4));

        JLabel statusLabel = createDependencyTextLabel("Status: " + row.getStatusLabel(), 11f, Font.BOLD);
        statusLabel.setForeground(dependencyStatusColor(row.getStatus()));
        card.add(statusLabel);

        if (!row.getStatusDetail().isEmpty()) {
            card.add(Box.createVerticalStrut(2));
            card.add(createDependencyTextLabel("Details: " + row.getStatusDetail(), 11f, Font.PLAIN));
        }

        card.add(Box.createVerticalStrut(4));
        card.add(createDependencyTextLabel(row.getBlockedLabel(), 11f, Font.PLAIN));
        card.add(Box.createVerticalStrut(2));
        card.add(createDependencyTextLabel(row.getExplanation(), 11f, Font.PLAIN));
        card.add(Box.createVerticalStrut(2));
        card.add(createDependencyTextLabel(row.getRestartLabel(), 11f, Font.PLAIN));

        if (!row.getActionNote().isEmpty()) {
            card.add(Box.createVerticalStrut(4));
            JLabel note = createDependencyTextLabel(row.getActionNote(), 11f, Font.PLAIN);
            note.setForeground(new Color(141, 60, 0));
            card.add(note);
        }

        if (!row.getActions().isEmpty()) {
            JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            buttonRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            buttonRow.setOpaque(false);
            for (DependencyService.DialogAction action : row.getActions()) {
                JButton button = new JButton(action.getLabel());
                button.addActionListener(e -> pd.closeWithAction(
                        buildDependencyRowActionCommand(row.getSpec().getId(), action.getActionId())));
                buttonRow.add(button);
            }
            card.add(Box.createVerticalStrut(6));
            card.add(buttonRow);
        }

        return card;
    }

    private DependencyRepairOutcome handleDependencyRowAction(String actionCommand) {
        String[] parts = actionCommand.split(":", 3);
        if (parts.length != 3) {
            IJ.showMessage("Pipeline Dependencies", "Could not parse dependency action: " + actionCommand);
            return DependencyRepairOutcome.none();
        }

        DependencyId dependencyId;
        try {
            dependencyId = DependencyId.valueOf(parts[1]);
        } catch (IllegalArgumentException e) {
            IJ.showMessage("Pipeline Dependencies", "Unknown dependency: " + parts[1]);
            return DependencyRepairOutcome.none();
        }

        String actionId = parts[2];
        DependencyFixResult result = dependencyService.runDialogAction(dependencyId, actionId);
        dependencyService.invalidateStatusCache();

        if (!DependencyService.DialogAction.VERIFY.equals(actionId) || !result.isSuccess()) {
            DependencySpec spec = DependencyRegistry.get(dependencyId);
            String dependencyName = spec == null ? dependencyId.name() : spec.getDisplayName();
            StringBuilder sb = new StringBuilder();
            sb.append("Dependency: ").append(dependencyName).append("\n");
            sb.append("Result: ").append(result.isSuccess() ? "Success" : "Incomplete").append("\n");
            sb.append("Restart Fiji after repair: ")
                    .append(result.isRestartRequired() ? "required" : "not required");
            if (result.getMessage() != null && !result.getMessage().trim().isEmpty()) {
                sb.append("\n\n").append(result.getMessage().trim());
            }
            DependencyRepairOutcome outcome = DependencyRepairOutcome.fromResult(result, sb.toString());
            if (outcome.shouldPauseForRestart()) {
                return outcome;
            }
            showScrollableMessage(
                    "Pipeline Dependencies - " + dependencyName,
                    sb.toString(),
                    result.isSuccess() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
            return outcome;
        }

        return DependencyRepairOutcome.fromResult(result, "");
    }

    private DependencyRepairOutcome runAutoFixAll(DependencyFixPlan plan) {
        if (!confirmAutoFixAll(plan)) {
            return DependencyRepairOutcome.none();
        }
        if (plan.getDependenciesToFix().isEmpty()) {
            return DependencyRepairOutcome.none();
        }

        List<DependencyFixResult> results = new java.util.ArrayList<DependencyFixResult>();
        boolean restartRequired = false;
        boolean successfulRestartRepair = false;
        boolean allSucceeded = true;
        for (DependencySpec spec : plan.getDependenciesToFix()) {
            DependencyFixResult result = dependencyService.runDialogAction(
                    spec.getId(), DependencyService.DialogAction.AUTO_FIX);
            results.add(result);
            restartRequired = restartRequired || result.isRestartRequired();
            successfulRestartRepair = successfulRestartRepair
                    || (result.isSuccess() && result.isRestartRequired());
            allSucceeded = allSucceeded && result.isSuccess();
        }
        dependencyService.invalidateStatusCache();

        String outcome;
        if (allSucceeded) {
            outcome = restartRequired ? "Success - restart required" : "Success";
        } else {
            outcome = restartRequired ? "Partial - restart still required for completed fixes" : "Partial";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Outcome: ").append(outcome).append("\n");
        sb.append("Planned download size: ").append(formatApproxSizeOrNone(plan.getTotalApproxDownloadBytes())).append("\n");
        sb.append("Restart Fiji after repair: ").append(restartRequired ? "required" : "not required");
        for (DependencyFixResult result : results) {
            DependencySpec spec = DependencyRegistry.get(result.getDependencyId());
            String name = spec == null ? result.getDependencyId().name() : spec.getDisplayName();
            sb.append("\n\n").append(name).append(": ")
                    .append(result.isSuccess() ? "SUCCESS" : "INCOMPLETE");
            if (result.getMessage() != null && !result.getMessage().trim().isEmpty()) {
                sb.append("\n").append(result.getMessage().trim());
            }
        }
        appendSkippedDependencies(sb, "Skipped healthy", plan.getAlreadySatisfied());
        appendSkippedDependencies(sb, "Not fixable in-app", plan.getBlockedDependencies());

        DependencyRepairOutcome repairOutcome = new DependencyRepairOutcome(
                restartRequired,
                successfulRestartRepair,
                sb.toString());
        if (repairOutcome.shouldPauseForRestart()) {
            return repairOutcome;
        }

        showScrollableMessage(
                "Pipeline Dependencies - Auto-Fix All",
                sb.toString(),
                allSucceeded ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
        return repairOutcome;
    }

    private void markDependencyRestartPending(String detailMessage) {
        dependencyRestartPending = true;
        dependencyRestartMessage = detailMessage == null ? "" : detailMessage.trim();
    }

    private void showDependencyRestartPrompt() {
        if (cliInvocation || GraphicsEnvironment.isHeadless()) {
            return;
        }

        PipelineDialog dialog = new PipelineDialog("Restart ImageJ/Fiji");
        dialog.setDefaultButtonsVisible(false);
        dialog.addHeader("Restart Required");
        dialog.addMessage("Dependency repair finished, but ImageJ/Fiji must restart before the new files are loaded.");
        dialog.addHelpText("The Dependencies window will show this restart prompt instead of re-running Auto-Fix for the repaired dependencies in this session.");

        if (dependencyRestartMessage != null && !dependencyRestartMessage.trim().isEmpty()) {
            dialog.addComponent(createRestartDetailBox(dependencyRestartMessage.trim()));
        }

        boolean canRestart = ImageJRestartHelper.canRestartAutomatically();
        if (!canRestart) {
            dialog.addHelpText("Automatic restart is unavailable because FLASH could not find the ImageJ/Fiji launcher. Close and reopen ImageJ/Fiji manually.");
        }

        JButton restartButton = dialog.addFooterButton("Restart ImageJ/Fiji");
        restartButton.setEnabled(canRestart);
        if (!canRestart) {
            restartButton.setToolTipText("Close and reopen ImageJ/Fiji manually.");
        }
        restartButton.addActionListener(e -> dialog.closeWithAction("restart_imagej"));

        JButton laterButton = dialog.addFooterButton("Later");
        laterButton.addActionListener(e -> dialog.closeWithAction("later"));

        dialog.showDialog();
        if (!"restart_imagej".equals(dialog.getActionCommand())) {
            return;
        }

        ImageJRestartHelper.RestartResult result = ImageJRestartHelper.launchRestartHelper();
        if (!result.isSuccess()) {
            showScrollableMessage(
                    "Restart ImageJ/Fiji",
                    result.getMessage(),
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        IJ.log("[FLASH] " + result.getMessage());
        IJ.showStatus("Restarting ImageJ/Fiji...");
        IJ.run("Quit");
    }

    private static JScrollPane createRestartDetailBox(String detail) {
        JTextArea area = new JTextArea(detail == null ? "" : detail);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setCaretPosition(0);
        area.setBackground(new Color(245, 245, 245));
        area.setFont(area.getFont().deriveFont(Font.PLAIN, 11f));

        JScrollPane scrollPane = new JScrollPane(area);
        scrollPane.setPreferredSize(new java.awt.Dimension(640, 160));
        scrollPane.setAlignmentX(Component.LEFT_ALIGNMENT);
        return scrollPane;
    }

    private static final class DependencyRepairOutcome {
        private static final DependencyRepairOutcome NONE =
                new DependencyRepairOutcome(false, false, "");

        private final boolean restartRequired;
        private final boolean successfulRestartRepair;
        private final String message;

        private DependencyRepairOutcome(boolean restartRequired,
                                        boolean successfulRestartRepair,
                                        String message) {
            this.restartRequired = restartRequired;
            this.successfulRestartRepair = successfulRestartRepair;
            this.message = message == null ? "" : message.trim();
        }

        static DependencyRepairOutcome none() {
            return NONE;
        }

        static DependencyRepairOutcome fromResult(DependencyFixResult result, String message) {
            if (result == null) {
                return NONE;
            }
            return new DependencyRepairOutcome(
                    result.isRestartRequired(),
                    result.isSuccess() && result.isRestartRequired(),
                    message);
        }

        boolean shouldPauseForRestart() {
            return restartRequired && successfulRestartRepair;
        }

        String getMessage() {
            return message;
        }
    }

    private boolean confirmAutoFixAll(DependencyFixPlan plan) {
        PipelineDialog confirm = new PipelineDialog("Pipeline Dependencies - Auto-Fix All");
        confirm.addHeader("Will Fix");
        if (plan.getDependenciesToFix().isEmpty()) {
            confirm.addMessage("Nothing fixable currently needs repair.");
        } else {
            for (DependencySpec spec : plan.getDependenciesToFix()) {
                confirm.addMessage(spec.getDisplayName() + " - " + defaultAutoFixLabel(spec));
            }
        }

        confirm.addHelpText("Total download: " + formatApproxSizeOrNone(plan.getTotalApproxDownloadBytes()));
        confirm.addHelpText("Restart Fiji after repair: " + (plan.isRestartRequired() ? "required." : "not required."));
        if (planContains(plan, DependencyId.CELLPOSE_RUNTIME)) {
            confirm.addHelpText("Auto-Fix All uses the Cellpose CPU install "
                    + DependencyRegistry.formatApproxSize(DependencyRegistry.CELLPOSE_CPU_RUNTIME_BYTES)
                    + ". Use the Cellpose row's GPU button for the GPU install "
                    + DependencyRegistry.formatApproxSize(DependencyRegistry.CELLPOSE_GPU_RUNTIME_BYTES) + ".");
        }

        if (!plan.getAlreadySatisfied().isEmpty()) {
            confirm.addHeader("Will Skip");
            for (DependencySpec spec : plan.getAlreadySatisfied()) {
                confirm.addMessage(spec.getDisplayName() + " - already healthy.");
            }
        }

        if (!plan.getBlockedDependencies().isEmpty()) {
            confirm.addHeader("Not Fixable In-App");
            for (DependencySpec spec : plan.getBlockedDependencies()) {
                String reason = spec.getNonFixableReason() == null || spec.getNonFixableReason().trim().isEmpty()
                        ? "Not fixable in-app - see README."
                        : "Not fixable in-app - see README. " + spec.getNonFixableReason().trim();
                confirm.addMessage(spec.getDisplayName() + " - " + reason);
            }
        }

        return confirm.showDialog();
    }

    private static String buildAutoFixAllLabel(DependencyFixPlan plan) {
        String size = DependencyRegistry.formatApproxSize(plan.getTotalApproxDownloadBytes());
        return size.isEmpty() ? "Auto-Fix All" : "Auto-Fix All " + size;
    }

    private static void appendSkippedDependencies(StringBuilder sb, String header, List<DependencySpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return;
        }
        sb.append("\n\n").append(header).append(":");
        for (DependencySpec spec : specs) {
            if (spec == null) {
                continue;
            }
            sb.append("\n- ").append(spec.getDisplayName());
            if ("Not fixable in-app".equals(header)) {
                String reason = spec.getNonFixableReason();
                if (reason != null && !reason.trim().isEmpty()) {
                    sb.append(": ").append(reason.trim());
                }
            }
        }
    }

    private static boolean planContains(DependencyFixPlan plan, DependencyId id) {
        if (plan == null || id == null) {
            return false;
        }
        for (DependencySpec spec : plan.getDependenciesToFix()) {
            if (spec != null && id == spec.getId()) {
                return true;
            }
        }
        return false;
    }

    private static String defaultAutoFixLabel(DependencySpec spec) {
        if (spec == null) {
            return "Auto-Fix";
        }
        if (!spec.getInstallOptions().isEmpty()) {
            return spec.getInstallOptions().get(0).formatButtonLabel();
        }
        String label = spec.formatButtonLabel(DependencyStatus.missing("missing"));
        return (label == null || label.trim().isEmpty()) ? "Auto-Fix" : label.trim();
    }

    private static String buildDependencyRowActionCommand(DependencyId id, String actionId) {
        return "row:" + id.name() + ":" + actionId;
    }

    private static JLabel createDependencyTextLabel(String text, float fontSize, int fontStyle) {
        JLabel label = new JLabel("<html><body width='560'>"
                + htmlText(text) + "</body></html>");
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setFont(label.getFont().deriveFont(fontStyle, fontSize));
        return label;
    }

    private static Color dependencyStatusColor(DependencyStatus status) {
        if (status == null) {
            return new Color(79, 79, 79);
        }
        if (status.isPresent()) {
            return new Color(46, 125, 50);
        }
        if (status.isError()) {
            return new Color(183, 28, 28);
        }
        return new Color(156, 101, 0);
    }

    private static void showScrollableMessage(String title, String message, int messageType) {
        JTextArea area = new JTextArea(message == null ? "" : message);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setCaretPosition(0);
        area.setBackground(new Color(245, 245, 245));
        area.setFont(area.getFont().deriveFont(Font.PLAIN, 12f));

        JScrollPane scrollPane = new JScrollPane(area);
        scrollPane.setPreferredSize(new java.awt.Dimension(640, 300));
        JOptionPane.showMessageDialog(null, scrollPane, title, messageType);
    }

    /** Creates a fresh QualityReport for one pipeline run. */
    static QualityReport createQualityReportForRun(String dir, boolean enabled,
                                                    boolean headless, boolean parallel,
                                                    int threadCount, boolean verboseLogging,
                                                    String overwriteBehavior) {
        QualityReport report = new QualityReport();
        report.setEnabled(enabled);
        report.setDirectory(dir);
        report.setGlobalSettings(headless, parallel, threadCount,
                verboseLogging, overwriteBehavior);
        return report;
    }

    /**
     * Applies all common run options to an analysis instance.
     * Both GUI and CLI paths route through this method so configuration
     * is never duplicated or accidentally omitted.
     */
    void configureAnalysis(Analysis analysis, int index,
                           boolean suppressDialogs,
                           QualityReport qualityReport) {
        boolean analysisHeadless = headlessMode;
        if (!cliInvocation
                && (analysis.requiresHeadedMode()
                || index == IDX_SPECTRAL_DECONTAMINATION)) {
            analysisHeadless = false;
        }
        FeatureDependencyGate.setUiMode(isDependencyGateUnattended());
        analysis.setHeadless(analysisHeadless);
        analysis.setVerboseLogging(verboseLogging);
        analysis.setSkipExisting("Skip Existing".equals(overwriteBehavior));
        analysis.setParallelThreads(parallelProcessing ? parallelThreadCount : 1);
        analysis.setLoaderThreads(parallelProcessing ? loaderThreadCount : 1);
        analysis.setLoaderPercent(parallelProcessing ? loaderPercent : 0);
        analysis.setUseTifCache(useTifCache);
        analysis.setQualityReport(qualityReport);
        analysis.setSuppressDialogs(suppressDialogs);
        analysis.setCliConfig(cliConfig);
        if (index == IDX_SPLIT_MERGE || index == IDX_3D_OBJECT || index == IDX_INTENSITY
                || index == IDX_SPECTRAL_DECONTAMINATION) {
            ImageCache cache = getImageCacheOrReport();
            if (cache != null) {
                analysis.setImageCache(cache);
            }
        }
    }

    void setCliInvocation(boolean cliInvocation) {
        this.cliInvocation = cliInvocation;
    }

    private ImageCache getImageCacheOrReport() {
        if (imageCache != null) return imageCache;

        try {
            imageCache = new ImageCache();
            return imageCache;
        } catch (NoClassDefFoundError e) {
            PluginInstallGuard.reportMissingInternalClass("FLASH", e);
            return null;
        }
    }

    private void releaseImageCache() {
        if (imageCache == null) return;
        imageCache.release();
        imageCache = null;
    }

    private void initAnalyses() {
        analysisMap.clear();
        analysisMap.put(IDX_CREATE_BIN, new CreateBinFileAnalysis());
        analysisMap.put(IDX_DRAW_ROIS, new DrawAndSaveROIsAnalysis());
        analysisMap.put(IDX_DECONVOLUTION, new DeconvolutionAnalysis());
        analysisMap.put(IDX_SPLIT_MERGE, new SplitAndMergeImageChannelsAnalysis());
        analysisMap.put(IDX_3D_OBJECT, new ThreeDObjectAnalysis());
        analysisMap.put(IDX_SPATIAL, new SpatialAnalysis());
        analysisMap.put(IDX_LINE_DISTANCE, new LineDistanceAnalysis());
        analysisMap.put(IDX_INTENSITY, new IntensityAnalysisV2());
        analysisMap.put(IDX_AGGREGATION, new MasterAggregationAnalysis());
        analysisMap.put(IDX_STATISTICS, new StatisticalAnalysis());
        analysisMap.put(IDX_EXCEL_EXPORT, createExcelExportAnalysis());
        analysisMap.put(IDX_SPECTRAL_DECONTAMINATION, new SpectralDecontaminationAnalysis());
        analysisMap.put(IDX_REPRESENTATIVE_FIGURE, new RepresentativeFigureAnalysis());
    }

    private Analysis createExcelExportAnalysis() {
        return new Analysis() {
            private boolean headless = false;
            private boolean suppressDialogs = false;
            private flash.pipeline.cli.CLIConfig cliConfig = null;

            @Override
            public void setHeadless(boolean headless) {
                this.headless = headless;
            }

            @Override
            public void setSuppressDialogs(boolean suppress) {
                this.suppressDialogs = suppress;
            }

            @Override
            public void setCliConfig(flash.pipeline.cli.CLIConfig config) {
                this.cliConfig = config;
            }

            @Override
            public void execute(String directory) {
                if (!FeatureDependencyGate.gate(DependencyId.APACHE_POI_RUNTIME,
                        "Excel Summary Export", "Apache POI .xlsx workbook writing")) {
                    return;
                }
                try {
                    Class<?> clazz = Class.forName(EXCEL_EXPORT_ANALYSIS_CLASS);
                    Analysis delegate = (Analysis) clazz.getDeclaredConstructor().newInstance();
                    delegate.setHeadless(headless);
                    delegate.setSuppressDialogs(suppressDialogs);
                    delegate.setCliConfig(cliConfig);
                    delegate.execute(directory);
                } catch (ReflectiveOperationException | LinkageError e) {
                    if (!FeatureDependencyGate.gate(DependencyId.APACHE_POI_RUNTIME,
                            "Excel Summary Export", "Apache POI .xlsx workbook writing")) {
                        return;
                    }
                    IJ.handleException(e);
                }
            }
        };
    }

    private boolean isDependencyGateUnattended() {
        return cliInvocation || GraphicsEnvironment.isHeadless();
    }

    private static String formatApproxSizeOrNone(long bytes) {
        String formatted = DependencyRegistry.formatApproxSize(bytes);
        return formatted.isEmpty() ? "none" : formatted;
    }

    private static String htmlText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }
        String escaped = text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        return escaped.replace("\n", "<br>");
    }

    /**
     * Silent pre-flight guards. Runs once per batch invocation, before analyses.
     * Returns true if the batch should proceed. P-11 write-permission is the
     * only hard block; the rest are warnings the user can override.
     */
    private boolean runPreFlightGuardsSafely(String dir) {
        try {
            return runPreFlightGuards(dir);
        } catch (NoClassDefFoundError e) {
            if (PluginInstallGuard.reportMissingInternalClass("FLASH pre-flight checks", e)) {
                return false;
            }
            throw e;
        }
    }

    private boolean runPreFlightGuards(String dir) {
        // P-11: write-permission is a hard block.
        String writeErr = PreFlightChecks.checkWritePermission(dir);
        if (writeErr != null) {
            IJ.showMessage("FLASH",
                    "Cannot proceed — output folder is not writable:\n\n" + writeErr
                    + "\n\nPick a different folder or fix permissions.");
            return false;
        }

        PreFlightChecks.DirectoryFileScan fileScan = PreFlightChecks.scanCleanFiles(dir);

        // L-08: truncated files. Warn and let user decide.
        List<File> truncated = PreFlightChecks.findTruncatedImages(fileScan);
        if (!truncated.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append(truncated.size()).append(" file(s) look truncated or incomplete:\n\n");
            int shown = Math.min(5, truncated.size());
            for (int i = 0; i < shown; i++) {
                sb.append("    ").append(truncated.get(i).getName()).append("\n");
            }
            if (truncated.size() > shown) {
                sb.append("    ... and ").append(truncated.size() - shown).append(" more\n");
            }
            sb.append("\nThese may cause errors during analysis. Continue anyway?");
            int choice = JOptionPane.showConfirmDialog(null, sb.toString(),
                    "Truncated files detected",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) return false;
        }

        // P-10: disk space warning (non-blocking, log only).
        PreFlightChecks.DiskSpaceResult ds = PreFlightChecks.checkDiskSpace(fileScan);
        if (ds.warn) {
            IJ.log("[PreFlight] Disk space low — free: "
                    + PreFlightChecks.humanBytes(ds.freeBytes)
                    + ", estimated output: "
                    + PreFlightChecks.humanBytes(ds.estimatedOutputBytes)
                    + (ds.projectScoped ? " (selected project sources)" : "")
                    + (ds.likelyInsufficient ? " (likely insufficient)" : ""));
        }

        // P-13: filename issues (non-blocking, log only).
        List<PreFlightChecks.PathIssue> pathIssues = PreFlightChecks.findPathIssues(fileScan);
        if (!pathIssues.isEmpty()) {
            IJ.log("[PreFlight] " + pathIssues.size() + " filename issue(s):");
            int shown = Math.min(10, pathIssues.size());
            for (int i = 0; i < shown; i++) {
                PreFlightChecks.PathIssue issue = pathIssues.get(i);
                IJ.log("    " + issue.file.getName() + " -- " + issue.reason);
            }
            if (pathIssues.size() > shown) {
                IJ.log("    ... and " + (pathIssues.size() - shown) + " more");
            }
        }

        return true;
    }
}
