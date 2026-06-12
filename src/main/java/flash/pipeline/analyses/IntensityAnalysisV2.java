package flash.pipeline.analyses;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.bin.ChannelConfigIO;
import flash.pipeline.bin.ChannelIdentities;
import flash.pipeline.analyses.wizard.IntensityPreset;
import flash.pipeline.analyses.wizard.IntensityPresetIO;
import flash.pipeline.analyses.wizard.IntensitySetupConfig;
import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import flash.pipeline.analyses.wizard.IntensitySpatialPreset;
import flash.pipeline.analyses.wizard.IntensitySpatialPresetIO;
import flash.pipeline.bin.BinField;
import flash.pipeline.bin.BinSetupDispatcher;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.atlas.AtlasRegionColumns;
import flash.pipeline.deconv.DeconvolvedInputResolver;
import flash.pipeline.image.AdaptiveParallelism;
import flash.pipeline.image.FilterExecutor;
import flash.pipeline.image.ImageCalcOps;
import flash.pipeline.image.ImageOps;
import flash.pipeline.image.NamedFilterLoader;
import flash.pipeline.image.OrientationOps;
import flash.pipeline.image.ParallelContext;
import flash.pipeline.image.ThreadSafeMeasure;
import flash.pipeline.image.ThresholdOps;
import flash.pipeline.io.BoundedImageLoader;
import flash.pipeline.intensity.spatial.IntensitySpatialContext;
import flash.pipeline.intensity.spatial.IntensitySpatialOutputKey;
import flash.pipeline.intensity.spatial.IntensitySpatialOutputMode;
import flash.pipeline.intensity.spatial.IntensitySpatialPairContext;
import flash.pipeline.intensity.spatial.IntensitySpatialResult;
import flash.pipeline.intensity.spatial.IntensitySpatialRunner;
import flash.pipeline.io.CsvTableIO;
import flash.pipeline.io.DeferredImageSupplier;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.io.IoUtils;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.naming.ImageOrientationResolver;
import flash.pipeline.naming.ImageNameParser;
import flash.pipeline.naming.NameParts;
import flash.pipeline.naming.ResolvedImageMetadata;
import flash.pipeline.results.IntensityDetailsWriter;
import flash.pipeline.results.RunIdCsv;
import flash.pipeline.runrecord.AnalysisRunContext;
import flash.pipeline.runrecord.LoadedRunParameterApplier;
import flash.pipeline.runrecord.LoadedRunParameters;
import flash.pipeline.runrecord.ParameterSnapshot;
import flash.pipeline.runrecord.RunRecordAware;
import flash.pipeline.runrecord.ui.LoadFromRunButton;
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.FeatureDependencyGate;
import flash.pipeline.runtime.PluginInstallGuard;
import flash.pipeline.orientation.OrientationImageIdentity;
import flash.pipeline.roi.RoiIO;
import flash.pipeline.roi.RoiSetImageBinding;
import flash.pipeline.roi.RoiSetValidator;
import flash.pipeline.ui.NextStepLabels;
import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.ToggleSwitch;
import flash.pipeline.zslice.ZSliceOps;
import flash.pipeline.zslice.ZSliceSelection;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.measure.ResultsTable;
import ij.plugin.ChannelSplitter;
import ij.gui.Roi;
import ij.io.Opener;


import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Macro-faithful rewrite of intensityAnalysis(), focused on outputs.
 *
 * Output: FLASH/Results/Tables/Intensity/&lt;channel&gt;.csv and intensity-analysis details.
 */
public class IntensityAnalysisV2 implements Analysis, RunRecordAware {

    enum NoRoiDecision {
        DRAW_ROIS,
        ANALYSE_FULL_IMAGE,
        CANCEL
    }

    interface NoRoiDecisionPrompt {
        NoRoiDecision choose();
    }

    interface RoiDrawingWorkflowLauncher {
        void launch(String directory);
    }

    private boolean headless = false;
    private boolean suppressDialogs = false;
    private boolean verboseLogging = false;
    private boolean skipExisting = false;
    private int parallelThreads = 1;
    private int loaderThreads = 1;
    private int loaderPercent = 0;
    private boolean useTifCache = false;
    private flash.pipeline.io.ImageCache imageCache = null;
    private flash.pipeline.report.QualityReport qualityReport = null;
    private long lifOpenTimeMs = 0;
    private boolean compactLog = false;
    private boolean useDeconvolvedInput = true;
    private CLIConfig cliConfig = null;
    private int cliConfiguredMaskIndex1Based = -1;
    private IntensitySpatialConfig intensitySpatialConfig = IntensitySpatialConfig.disabled();
    private IntensitySetupConfig.DerivedConfig configuredOptions = null;
    private AnalysisRunContext runRecordContext = null;
    /** Per-image durable-identity binding token (index = series index); null where unresolved. */
    private String[] imageBindTokens = null;
    private NoRoiDecisionPrompt noRoiDecisionPrompt = new NoRoiDecisionPrompt() {
        @Override
        public NoRoiDecision choose() {
            return showNoRoiDecisionDialog();
        }
    };
    private RoiDrawingWorkflowLauncher roiDrawingWorkflowLauncher =
            new RoiDrawingWorkflowLauncher() {
                @Override
                public void launch(String directory) {
                    launchRoiDrawingWorkflowDirect(directory);
                }
            };
    private final AtomicBoolean stalePluginWarningShown = new AtomicBoolean(false);

    private static final String INTENSITY_PRESET_PLACEHOLDER = "(choose preset)";
    private static final String INTENSITY_SPATIAL_PRESET_PLACEHOLDER = "(choose preset)";

    /** Live references to the primary-dialog controls, used by the preset row. */
    private static final class IntensityPresetBindings {
        boolean programmaticChange;
        JComboBox<String> presetCombo;
        ToggleSwitch useDeconvToggle;
        ToggleSwitch roiAnalysisToggle;
        ToggleSwitch intensitySpatialToggle;
        List<ToggleSwitch> binarizeToggles;
        List<JComboBox<String>> filterSourceChoices;
        Runnable primaryLabelUpdater;
    }

    /** Live references to the nested Intensity-Spatial dialog controls. */
    private static final class IntensitySpatialPresetBindings {
        boolean programmaticChange;
        JComboBox<String> presetCombo;
        ToggleSwitch overlaysToggle;
        javax.swing.JLabel willRunLabel;
        // One independent toggle map per output mode (BASE = per-slice).
        final Map<IntensitySpatialOutputMode, Map<IntensitySpatialConfig.AnalysisKey, ToggleSwitch>> modeToggles =
                new LinkedHashMap<IntensitySpatialOutputMode, Map<IntensitySpatialConfig.AnalysisKey, ToggleSwitch>>();
        JTextField shellWidthField;
        JTextField shellCountField;
        JTextField tileScalesField;
        JTextField granularityScalesField;
        JTextField depthBinWidthField;
        JTextField rimDepthField;
        JTextField textureClassCountField;
        JTextField permutationsField;
        JTextField costesPermutationsField;
        javax.swing.JSlider costesPermutationsSlider;
        javax.swing.JLabel costesPermutationsLabel;
        JTextField seedField;

        Map<IntensitySpatialConfig.AnalysisKey, ToggleSwitch> toggles(IntensitySpatialOutputMode mode) {
            Map<IntensitySpatialConfig.AnalysisKey, ToggleSwitch> map = modeToggles.get(mode);
            if (map == null) {
                map = new LinkedHashMap<IntensitySpatialConfig.AnalysisKey, ToggleSwitch>();
                modeToggles.put(mode, map);
            }
            return map;
        }
    }

    interface IntensitySpatialOptionsDialogLauncher {
        IntensitySpatialConfig launch(String directory,
                                      IntensitySpatialConfig currentConfig,
                                      String[] channelNames,
                                      boolean[] binarization,
                                      Integer likelyStackDepth);

        default IntensitySpatialConfig launch(String directory,
                                              IntensitySpatialConfig currentConfig,
                                              String[] channelNames,
                                              boolean[] binarization,
                                              Integer likelyStackDepth,
                                              String[] workflowSteps,
                                              int workflowActiveIndex) {
            return launch(directory, currentConfig, channelNames, binarization, likelyStackDepth);
        }
    }

    private static final IntensitySpatialConfig.AnalysisKey[] GUI_SAME_CHANNEL_2D_ANALYSES = {
            IntensitySpatialConfig.AnalysisKey.PATCHINESS,
            IntensitySpatialConfig.AnalysisKey.HOTSPOTSCAN,
            IntensitySpatialConfig.AnalysisKey.NULLMODEL,
            IntensitySpatialConfig.AnalysisKey.GRANULARITY,
            IntensitySpatialConfig.AnalysisKey.DEPTH_PROFILE,
            IntensitySpatialConfig.AnalysisKey.ANISOTROPY,
            IntensitySpatialConfig.AnalysisKey.PERIODICITY,
            IntensitySpatialConfig.AnalysisKey.GLCM,
            IntensitySpatialConfig.AnalysisKey.TEXTURECLASS,
            IntensitySpatialConfig.AnalysisKey.SCALEDIVERGENCE
    };
    private static final IntensitySpatialConfig.AnalysisKey[] GUI_CROSS_CHANNEL_2D_ANALYSES = {
            IntensitySpatialConfig.AnalysisKey.CROSSCORR_FAST,
            IntensitySpatialConfig.AnalysisKey.CROSSMARK,
            IntensitySpatialConfig.AnalysisKey.ENTROPY_MI,
            IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL
    };
    private static final IntensitySpatialConfig.AnalysisKey[] GUI_NATIVE_3D_SAME_CHANNEL_ANALYSES = {
            IntensitySpatialConfig.AnalysisKey.ANISOTROPY_3D
    };
    private static final IntensitySpatialConfig.AnalysisKey[] GUI_NATIVE_3D_CROSS_CHANNEL_ANALYSES = {
            IntensitySpatialConfig.AnalysisKey.CROSSMARK_3D,
            IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL_3D
    };
    // Default-on per mode: analyses that are fast (estimatedCost <= 3) AND well suited to that mode.
    private static final Set<IntensitySpatialConfig.AnalysisKey> DEFAULT_PERSLICE_ANALYSES =
            Collections.unmodifiableSet(EnumSet.of(
                    IntensitySpatialConfig.AnalysisKey.NULLMODEL,
                    IntensitySpatialConfig.AnalysisKey.GLCM));
    private static final Set<IntensitySpatialConfig.AnalysisKey> DEFAULT_MIP_ANALYSES =
            Collections.unmodifiableSet(EnumSet.of(
                    IntensitySpatialConfig.AnalysisKey.PATCHINESS,
                    IntensitySpatialConfig.AnalysisKey.GLCM));
    private static final Set<IntensitySpatialConfig.AnalysisKey> DEFAULT_NATIVE_3D_ANALYSES =
            Collections.unmodifiableSet(EnumSet.noneOf(IntensitySpatialConfig.AnalysisKey.class));

    private static final IntensitySpatialOptionsDialogLauncher DEFAULT_INTENSITY_SPATIAL_OPTIONS_DIALOG_LAUNCHER =
            new IntensitySpatialOptionsDialogLauncher() {
                @Override
                public IntensitySpatialConfig launch(String directory,
                                                     IntensitySpatialConfig currentConfig,
                                                     String[] channelNames,
                                                     boolean[] binarization,
                                                     Integer likelyStackDepth) {
                    return launch(directory, currentConfig, channelNames, binarization, likelyStackDepth,
                            new String[]{"Setup", "Intensity-Spatial", "Run"}, 1);
                }

                @Override
                public IntensitySpatialConfig launch(String directory,
                                                     IntensitySpatialConfig currentConfig,
                                                     String[] channelNames,
                                                     boolean[] binarization,
                                                     Integer likelyStackDepth,
                                                     String[] workflowSteps,
                                                     int workflowActiveIndex) {
                    return showIntensitySpatialOptionsDialog(
                            directory, currentConfig, channelNames, binarization, likelyStackDepth,
                            workflowSteps, workflowActiveIndex);
                }
            };

    private IntensitySpatialOptionsDialogLauncher intensitySpatialOptionsDialogLauncher =
            DEFAULT_INTENSITY_SPATIAL_OPTIONS_DIALOG_LAUNCHER;

    @Override
    public Set<BinField> requiredBinFields() {
        return EnumSet.of(
                BinField.CHANNEL_NAMES,
                BinField.FILTER_PRESETS,
                BinField.INTENSITY_THRESHOLDS,
                BinField.Z_SLICE);
    }

    @Override
    public boolean benefitsFromRois() {
        return true;
    }

    IntensitySpatialConfig getIntensitySpatialConfigForTest() {
        return intensitySpatialConfig;
    }

    @Override
    public void setHeadless(boolean headless) {
        this.headless = headless;
    }

    @Override
    public void setSuppressDialogs(boolean suppress) {
        this.suppressDialogs = suppress;
    }

    @Override
    public void setVerboseLogging(boolean verbose) {
        this.verboseLogging = verbose;
    }

    @Override
    public void setSkipExisting(boolean skip) {
        this.skipExisting = skip;
    }

    @Override
    public void setParallelThreads(int threads) {
        this.parallelThreads = Math.max(1, threads);
    }

    @Override
    public void setLoaderThreads(int threads) {
        this.loaderThreads = Math.max(1, threads);
    }

    @Override
    public void setLoaderPercent(int percent) {
        this.loaderPercent = Math.max(0, Math.min(percent, 100));
    }

    @Override
    public void setUseTifCache(boolean use) {
        this.useTifCache = use;
    }

    @Override
    public void setImageCache(flash.pipeline.io.ImageCache cache) {
        this.imageCache = cache;
    }

    @Override
    public void setQualityReport(flash.pipeline.report.QualityReport report) {
        this.qualityReport = report;
    }

    @Override
    public void setCliConfig(CLIConfig config) {
        this.cliConfig = config;
        if (config != null) {
            this.useDeconvolvedInput = config.isIntensityV2UseDeconv();
        }
    }

    void setWizardConfig(IntensitySetupConfig.DerivedConfig config) {
        this.configuredOptions = config;
    }

    void applyPreset(String directory, IntensityPreset preset) {
        BinConfig cfg = loadBinConfig(directory);
        List<File> roiZips = RoiIO.listRoiZipFiles(new File(directory));
        List<String> roiSetNames = new ArrayList<String>();
        for (File roiZip : roiZips) {
            if (roiZip == null) continue;
            String name = roiZip.getName();
            name = name.replace(" ROIs.zip", "").replace("ROIs.zip", "").trim();
            roiSetNames.add(name);
        }
        File binDir = FlashProjectLayout.forDirectory(directory).configurationWriteDir();
        ChannelIdentities identities = ChannelConfigIO.readChannelIdentities(binDir);
        setWizardConfig(IntensitySetupConfig.fromPreset(cfg, identities, preset, roiSetNames));
    }

    void setIntensitySpatialOptionsDialogLauncherForTest(
            IntensitySpatialOptionsDialogLauncher launcher) {
        this.intensitySpatialOptionsDialogLauncher = launcher == null
                ? DEFAULT_INTENSITY_SPATIAL_OPTIONS_DIALOG_LAUNCHER
                : launcher;
    }

    void setNoRoiDecisionPromptForTest(NoRoiDecisionPrompt prompt) {
        this.noRoiDecisionPrompt = prompt == null
                ? new NoRoiDecisionPrompt() {
                    @Override
                    public NoRoiDecision choose() {
                        return showNoRoiDecisionDialog();
                    }
                }
                : prompt;
    }

    void setRoiDrawingWorkflowLauncherForTest(RoiDrawingWorkflowLauncher launcher) {
        this.roiDrawingWorkflowLauncher = launcher == null
                ? new RoiDrawingWorkflowLauncher() {
                    @Override
                    public void launch(String directory) {
                        launchRoiDrawingWorkflowDirect(directory);
                    }
                }
                : launcher;
    }

    @Override
    public void setRunRecordContext(AnalysisRunContext context) {
        this.runRecordContext = context;
    }

    @Override
    public void execute(String directory) {
        if (!FeatureDependencyGate.gate(DependencyId.BIO_FORMATS_RUNTIME,
                "Fluorescence Intensity Analysis", "Bio-Formats image loading")) {
            recordWarn("Fluorescence Intensity Analysis blocked by missing Bio-Formats runtime dependency.");
            return;
        }

        if (!resolveRoiSetsBeforeConfiguration(directory)) {
            return;
        }

        BinSetupDispatcher.Outcome outcome = BinSetupDispatcher.ensure(
                directory, "Intensity Analysis", requiredBinFields(),
                benefitsFromRois(), suppressDialogs, cliConfig);
        if (outcome == BinSetupDispatcher.Outcome.CANCELLED) {
            String message = "[FLASH] Intensity Analysis cancelled by user.";
            IJ.log(message);
            recordWarn(message);
            return;
        }

        BinConfig cfg = loadBinConfig(directory);
        String[] channelNames = cfg.channelNames.toArray(new String[0]);
        if (safeChannels(channelNames).isEmpty()) {
            String message = "Intensity Analysis requires at least one configured channel name.";
            IJ.log("ERROR: " + message);
            if (canShowGuiDialog(suppressDialogs, cliConfig, GraphicsEnvironment.isHeadless())) {
                IJ.error("Intensity Analysis", message);
            }
            recordWarn(message);
            return;
        }
        IJ.log("==========================================================");
        IJ.log("FLUORESCENCE INTENSITY ANALYSIS");
        IJ.log("==========================================================");
        IJ.log("Directory: " + directory);
        IJ.log("Channels: " + String.join(", ", channelNames));

        // Load intensity-specific filter (Median r=2 + Subtract Background rolling=50)
        // This is distinct from the 3D Object Analysis default filter
        String basicFilterMacro = NamedFilterLoader.loadIntensityFilter();
        if (basicFilterMacro == null) {
            String message = "Intensity filter resource not found in JAR.";
            IJ.log("ERROR: " + message);
            IJ.error("Intensity Analysis", "Intensity filter resource missing from plugin JAR.");
            recordWarn(message);
            return;
        }
        IJ.log("Intensity filter: loaded from plugin resources (intensityFilter.ijm)");

        // ROI zips
        List<File> roiZips;
        try {
            roiZips = RoiIO.listRoiZipFiles(new File(directory));
        } catch (NoClassDefFoundError e) {
            if (PluginInstallGuard.reportMissingInternalClass("Intensity Analysis", e)) return;
            throw e;
        }
        String[] roiZipNames = new String[roiZips.size()];
        for (int i = 0; i < roiZips.size(); i++) {
            String n = roiZips.get(i).getName();
            n = n.replace(" ROIs.zip", "").replace("ROIs.zip", "").trim();
            roiZipNames[i] = n;
        }

        boolean anyRois = !roiZips.isEmpty();
        IJ.log("ROI sets found: " + roiZips.size());
        for (int i = 0; i < roiZips.size(); i++) {
            IJ.log("  - " + roiZipNames[i]);
        }
        List<String> roiSetNameList = Arrays.asList(roiZipNames);
        File binDir = FlashProjectLayout.forDirectory(directory).configurationWriteDir();
        ChannelIdentities channelIdentities = ChannelConfigIO.readChannelIdentities(binDir);

        // --- Dialogs with Back navigation ---
        boolean roiAnalysis = false;
        boolean[] binarization = new boolean[channelNames.length];
        boolean anyBinarize = false;

        String[] thresholds = new String[channelNames.length];
        for (int i = 0; i < channelNames.length; i++) {
            String binThr = (i < cfg.channelIntensityThresholds.size())
                    ? cfg.channelIntensityThresholds.get(i) : "default";
            if (binThr != null && !"default".equalsIgnoreCase(binThr) && !binThr.trim().isEmpty()) {
                thresholds[i] = binThr;
            } else {
                thresholds[i] = "0";
            }
        }

        boolean[] roiZipSelected = new boolean[roiZips.size()];
        Arrays.fill(roiZipSelected, true);

        // Per-channel filter source: "Bin filter" (loads saved Cn_Filters.ijm)
        // or "Basic background and noise removal" (intensity-filter macro). Default Bin.
        String[] filterSources = new String[channelNames.length];
        Arrays.fill(filterSources, IntensitySetupConfig.FILTER_BIN);

        cliConfiguredMaskIndex1Based = -1;
        intensitySpatialConfig = IntensitySpatialConfig.disabled();
        if (configuredOptions != null) {
            applyIntensityDerivedConfig(configuredOptions, binarization,
                    thresholds, filterSources, roiZipSelected, channelNames);
            intensitySpatialConfig = configuredOptions.spatialConfig == null
                    ? IntensitySpatialConfig.disabled()
                    : configuredOptions.spatialConfig;
            cliConfiguredMaskIndex1Based = configuredOptions.maskChannelIndex + 1;
        }

        String roiChannelChoice = "None";
        int roiChannelIndex1Based = -1;
        applyCliIntensityConfiguration(directory, cfg, channelIdentities, roiSetNameList,
                binarization, thresholds, filterSources,
                roiZipSelected, channelNames);
        roiChannelIndex1Based = cliConfiguredMaskIndex1Based;
        if (roiChannelIndex1Based > 0 && roiChannelIndex1Based <= channelNames.length) {
            roiChannelChoice = channelNames[roiChannelIndex1Based - 1];
        }
        anyBinarize = anyTrue(binarization);
        boolean runIntensitySpatial = intensitySpatialConfig != null && intensitySpatialConfig.isEnabled();

        int intensityStep = 0;
        while (!suppressDialogs && intensityStep >= 0) {
            if (intensityStep == 0) {
                // --- Primary dialog ---
                PipelineDialog gd = new PipelineDialog("Fluorescence Intensity Analysis", PipelineDialog.Phase.ANALYSE);
                gd.addAnalysisHelpHeader("Fluorescence Intensity Analysis", FLASH_Pipeline.IDX_INTENSITY);

                final IntensityPresetBindings pb = new IntensityPresetBindings();
                addIntensitySetupControls(gd, directory, cfg, channelIdentities, roiSetNameList,
                        binarization, thresholds, filterSources, roiZipSelected, channelNames,
                        anyRois, pb);

                gd.addSubHeader("Analysis Options");
                final ToggleSwitch useDeconvToggle =
                        gd.addToggle("Use deconvolved stacks if available", useDeconvolvedInput);
                pb.useDeconvToggle = useDeconvToggle;
                final ToggleSwitch roiAnalysisToggle = gd.addToggle("ROI Analysis", anyRois);
                pb.roiAnalysisToggle = roiAnalysisToggle;
                final ToggleSwitch intensitySpatialToggle =
                        gd.addToggle("Intensity-spatial analysis", runIntensitySpatial);
                pb.intensitySpatialToggle = intensitySpatialToggle;

                gd.addHeader("Filter Source (per channel)");
                final List<JComboBox<String>> filterSourceChoices = new ArrayList<JComboBox<String>>();
                pb.filterSourceChoices = filterSourceChoices;
                for (int i = 0; i < channelNames.length; i++) {
                    String savedFilterLabel = savedFilterChoiceLabel(i, cfg);
                    String[] filterSourceOptions = new String[]{
                            savedFilterLabel, "Basic background and noise removal"
                    };
                    JComboBox<String> filterChoice = gd.addChoice(channelNames[i] + " filter source",
                            filterSourceOptions,
                            "Bin filter".equals(filterSources[i])
                                    ? savedFilterLabel
                                    : "Basic background and noise removal");
                    filterSourceChoices.add(filterChoice);
                    gd.addHelpText(buildFilterSummaryLine(i, cfg, channelNames, filterSources,
                            binarization));
                }

                gd.addHeader("Binarise Signal (per channel)");
                gd.addHelpText("Creates a binary mask from the filtered image at a given threshold, "
                        + "then ANDs it with the raw image.");

                final List<ToggleSwitch> binarizeToggles = new ArrayList<ToggleSwitch>();
                for (int i = 0; i < channelNames.length; i++) {
                    binarizeToggles.add(gd.addToggle("Binarise " + channelNames[i], binarization[i]));
                }
                pb.binarizeToggles = binarizeToggles;

                final Runnable updatePrimaryLabel = new Runnable() {
                    @Override public void run() {
                        boolean needsRoiThresholds = anyToggleSelected(binarizeToggles)
                                || (anyRois && isSelected(roiAnalysisToggle));
                        boolean spatialSelected = isSelected(intensitySpatialToggle);
                        gd.setPrimaryButtonText(NextStepLabels.afterIntensityMain(
                                anyToggleSelected(binarizeToggles),
                                anyRois && isSelected(roiAnalysisToggle),
                                spatialSelected));
                        gd.setWorkflowTracker(
                                intensityWorkflow(needsRoiThresholds, spatialSelected), 0);
                    }
                };
                pb.primaryLabelUpdater = updatePrimaryLabel;
                roiAnalysisToggle.addChangeListener(updatePrimaryLabel);
                intensitySpatialToggle.addChangeListener(updatePrimaryLabel);
                for (ToggleSwitch toggle : binarizeToggles) {
                    toggle.addChangeListener(updatePrimaryLabel);
                }
                updatePrimaryLabel.run();

                LoadFromRunButton.install(gd, "IntensityAnalysisV2", new File(directory),
                        new LoadedRunParameterApplier() {
                            @Override public LoadedRunParameters.Result applyLoadedParameters(
                                    Map<String, Object> parameters) {
                                LoadedRunParameters.PresetLoad<IntensityPreset> load =
                                        LoadedRunParameters.intensityPreset(parameters);
                                IntensitySetupConfig.DerivedConfig derived = IntensitySetupConfig.fromPreset(
                                        cfg, channelIdentities, load.payload, roiSetNameList);
                                applyIntensityPresetToStep0(derived, pb, binarization, thresholds,
                                        filterSources, roiZipSelected, channelNames, cfg, anyRois);
                                return load.result;
                            }
                        });

                if (!gd.showDialog()) {
                    return;
                }

                useDeconvolvedInput = gd.getNextBoolean();
                roiAnalysis = anyRois && gd.getNextBoolean();
                runIntensitySpatial = gd.getNextBoolean();
                if (cliConfiguredMaskIndex1Based > 0 && cliConfiguredMaskIndex1Based <= channelNames.length) {
                    roiChannelIndex1Based = cliConfiguredMaskIndex1Based;
                    roiChannelChoice = channelNames[roiChannelIndex1Based - 1];
                }

                // Filter source choices (added before the binarise toggles, so read first)
                for (int i = 0; i < channelNames.length; i++) {
                    String selectedFilterSource = gd.getNextChoice();
                    filterSources[i] = "Basic background and noise removal".equals(selectedFilterSource)
                            ? "Basic background and noise removal"
                            : "Bin filter";
                }

                anyBinarize = false;
                for (int i = 0; i < channelNames.length; i++) {
                    binarization[i] = gd.getNextBoolean();
                    anyBinarize |= binarization[i];
                }

                if (anyBinarize || roiAnalysis) {
                    intensityStep = 1; // advance to secondary dialog
                } else {
                    break; // done with dialogs
                }
            } else if (intensityStep == 1) {
                // --- Secondary dialog ---
                PipelineDialog gd2 = new PipelineDialog("ROI and Threshold Settings", PipelineDialog.Phase.ANALYSE);
                gd2.setWorkflowTracker(intensityWorkflow(true, runIntensitySpatial), 1);
                gd2.enableBackButton();
                gd2.setPrimaryButtonText(NextStepLabels.afterIntensityRoiThreshold(runIntensitySpatial));
                gd2.addAnalysisHelpHeader("Fluorescence Intensity Analysis", FLASH_Pipeline.IDX_INTENSITY);

                // Decide which channels need user threshold input. A channel needs
                // input only if binarisation is on AND either the user picked the
                // basic filter (so the bin threshold no longer matches the filtered
                // image) or the bin has no usable threshold stored yet.
                boolean[] needsThresholdInput = new boolean[channelNames.length];
                for (int i = 0; i < channelNames.length; i++) {
                    if (!binarization[i]) {
                        needsThresholdInput[i] = false;
                        continue;
                    }
                    String binThr = (i < cfg.channelIntensityThresholds.size())
                            ? cfg.channelIntensityThresholds.get(i) : "default";
                    boolean haveBinThr = binThr != null
                            && !"default".equalsIgnoreCase(binThr)
                            && !binThr.trim().isEmpty();
                    needsThresholdInput[i] = !"Bin filter".equals(filterSources[i]) || !haveBinThr;
                }

                final Map<Integer, JTextField> thresholdFieldsByChannel =
                        new LinkedHashMap<Integer, JTextField>();
                if (anyBinarize) {
                    gd2.addHeader("Channel Thresholds");
                    gd2.addHelpText("Only binarised channels need a threshold. "
                            + "Enter the lower threshold only where this dialog shows an input field; "
                            + "pixels below that value will be masked out.");
                    for (int i = 0; i < channelNames.length; i++) {
                        if (!binarization[i]) continue;
                        if (needsThresholdInput[i]) {
                            String hint = "Bin filter".equals(filterSources[i])
                                    ? " (" + savedChannelFilterName(i, cfg) + " - no threshold stored in the configuration)"
                                    : " (Basic filter - set threshold for filtered image)";
                            thresholdFieldsByChannel.put(Integer.valueOf(i),
                                    gd2.addStringField(channelNames[i] + " threshold" + hint, thresholds[i], 8));
                        } else {
                            gd2.addMessage(channelNames[i] + " threshold: "
                                    + thresholds[i] + " (from configuration)");
                        }
                    }
                }

                if (roiAnalysis) {
                    gd2.addHeader("Select ROIs to Analyse");
                    final List<ToggleSwitch> roiToggles = new ArrayList<ToggleSwitch>();
                    if (!roiZips.isEmpty()) {
                        for (int r = 0; r < roiZipNames.length; r++) {
                            roiToggles.add(gd2.addToggle(roiZipNames[r], roiZipSelected[r]));
                        }
                    }
                    gd2.addHeader("Channel ROI Mask");
                    gd2.addHelpText("The chosen channel's filter and threshold are used to build the mask, "
                            + "which is then ANDed with each measurement channel. "
                            + "The chosen channel must have binarisation enabled.");
                    String[] roiChannels = new String[channelNames.length + 1];
                    roiChannels[0] = "None";
                    System.arraycopy(channelNames, 0, roiChannels, 1, channelNames.length);
                    final JComboBox<String> roiChannelCombo =
                            gd2.addChoice("Channel ROI", roiChannels, roiChannelChoice);
                    LoadFromRunButton.install(gd2, "IntensityAnalysisV2", new File(directory),
                            new LoadedRunParameterApplier() {
                                @Override public LoadedRunParameters.Result applyLoadedParameters(
                                        Map<String, Object> parameters) {
                                    LoadedRunParameters.PresetLoad<IntensityPreset> load =
                                            LoadedRunParameters.intensityPreset(parameters);
                                    IntensitySetupConfig.DerivedConfig derived = IntensitySetupConfig.fromPreset(
                                            cfg, channelIdentities, load.payload, roiSetNameList);
                                    applyIntensityDerivedConfig(derived, binarization, thresholds,
                                            filterSources, roiZipSelected, channelNames);
                                    intensitySpatialConfig = derived.spatialConfig == null
                                            ? IntensitySpatialConfig.disabled()
                                            : derived.spatialConfig;
                                    cliConfiguredMaskIndex1Based = derived.maskChannelIndex + 1;
                                    for (Map.Entry<Integer, JTextField> entry
                                            : thresholdFieldsByChannel.entrySet()) {
                                        int channel = entry.getKey().intValue();
                                        if (channel >= 0 && channel < thresholds.length) {
                                            entry.getValue().setText(thresholds[channel]);
                                        }
                                    }
                                    for (int i = 0; i < roiToggles.size()
                                            && i < roiZipSelected.length; i++) {
                                        setToggle(roiToggles.get(i), roiZipSelected[i]);
                                    }
                                    roiChannelCombo.setSelectedItem(derived.maskChannelChoice);
                                    return load.result;
                                }
                            });
                }

                if (!gd2.showDialog()) {
                    if (gd2.wasBackPressed()) {
                        if (anyBinarize) {
                            for (int i = 0; i < channelNames.length; i++) {
                                if (binarization[i] && needsThresholdInput[i]) {
                                    thresholds[i] = gd2.getNextString();
                                }
                            }
                        }
                        if (roiAnalysis) {
                            if (!roiZips.isEmpty()) {
                                for (int r = 0; r < roiZipSelected.length; r++) {
                                    roiZipSelected[r] = gd2.getNextBoolean();
                                }
                            }
                            roiChannelChoice = gd2.getNextChoice();
                            roiChannelIndex1Based = -1;
                            if (!"None".equals(roiChannelChoice)) {
                                for (int i = 0; i < channelNames.length; i++) {
                                    if (channelNames[i].equals(roiChannelChoice)) {
                                        roiChannelIndex1Based = i + 1;
                                    }
                                }
                            }
                        }
                        intensityStep = 0; // go back to primary dialog
                        continue;
                    }
                    return; // Cancel
                }

                if (anyBinarize) {
                    for (int i = 0; i < channelNames.length; i++) {
                        if (binarization[i] && needsThresholdInput[i]) {
                            thresholds[i] = gd2.getNextString();
                        }
                    }
                }
                if (roiAnalysis) {
                    if (!roiZips.isEmpty()) {
                        for (int r = 0; r < roiZipSelected.length; r++) {
                            roiZipSelected[r] = gd2.getNextBoolean();
                        }
                    }
                    roiChannelChoice = gd2.getNextChoice();
                    roiChannelIndex1Based = -1;
                    if (!"None".equals(roiChannelChoice)) {
                        for (int i = 0; i < channelNames.length; i++) {
                            if (channelNames[i].equals(roiChannelChoice)) {
                                roiChannelIndex1Based = i + 1;
                            }
                        }
                    }

                    // Block-with-message: the ROI mask channel must have
                    // binarisation enabled, since the mask is built from that
                    // channel's filter + threshold. Don't auto-enable -
                    // re-show dialog 1 so the user can flip the toggle.
                    if (roiChannelIndex1Based > 0
                            && !binarization[roiChannelIndex1Based - 1]) {
                        IJ.error("Fluorescence Intensity Analysis",
                                "Channel ROI Mask is set to '" + roiChannelChoice + "',\n"
                                + "but binarisation is not enabled for that channel.\n\n"
                                + "Either enable binarisation for " + roiChannelChoice
                                + " in the previous step, or pick a different ROI mask channel.");
                        intensityStep = 0; // re-show primary dialog
                        continue;
                    }
                }
                break; // done with dialogs
            }
        }
        if (suppressDialogs) {
            roiAnalysis = anyRois;
            anyBinarize = anyTrue(binarization);
        }
        if (!prepareIntensitySpatialOptionsBeforeAnalysis(
                directory, cfg, channelNames, binarization, runIntensitySpatial,
                anyBinarize || roiAnalysis)) {
            recordWarn("Intensity Analysis cancelled because intensity-spatial options were cancelled.");
            return;
        }

        // Capture the finalized GUI settings into the run record so a later
        // "Load settings from previous run" can restore them. No-op for
        // CLI/headless/scripted paths (runRecordContext == null), which already
        // record parameters via IntensityAnalysisV2Command.
        recordIntensityRunParameters(channelNames, binarization, thresholds,
                roiAnalysis, roiChannelIndex1Based, roiZipNames, roiZipSelected,
                intensitySpatialConfig);

        String validationError = validateIntensityConfiguration(
                binarization, thresholds, channelNames, roiAnalysis, roiChannelIndex1Based);
        if (validationError != null) {
            IJ.log("ERROR: " + validationError);
            if (canShowGuiDialog(suppressDialogs, cliConfig, GraphicsEnvironment.isHeadless())) {
                IJ.error("Fluorescence Intensity Analysis", validationError);
            }
            return;
        }

        // Log user selections
        IJ.log("__________________________________________________________");
        IJ.log("User selections:");
        IJ.log("  ROI Analysis: " + roiAnalysis);
        for (int i = 0; i < channelNames.length; i++) {
            String filterLabel = "Bin filter".equals(filterSources[i])
                    ? savedFilterChoiceLabel(i, cfg)
                    : "Basic background and noise removal";
            IJ.log("  Filter " + channelNames[i] + ": " + filterLabel);
        }
        for (int i = 0; i < channelNames.length; i++) {
            IJ.log("  Binarise " + channelNames[i] + ": " + binarization[i]
                    + (binarization[i] ? " (threshold=" + thresholds[i] + ")" : ""));
        }
        if (roiAnalysis) {
            IJ.log("  Channel ROI Mask: " + roiChannelChoice);
            for (int r = 0; r < roiZipNames.length; r++) {
                IJ.log("  ROI set '" + roiZipNames[r] + "': " + (roiZipSelected[r] ? "selected" : "skipped"));
            }
        }
        IJ.log("  Intensity-spatial: " + intensitySpatialConfig.isEnabled());
        if (intensitySpatialConfig.isEnabled()) {
            IJ.log("  Intensity-spatial per-slice: "
                    + IntensitySpatialConfig.joinAnalysisTokens(intensitySpatialConfig.getEnabledPerSlice()));
            IJ.log("  Intensity-spatial MIP: "
                    + IntensitySpatialConfig.joinAnalysisTokens(intensitySpatialConfig.getEnabledMip()));
            IJ.log("  Intensity-spatial native 3D: "
                    + IntensitySpatialConfig.joinAnalysisTokens(intensitySpatialConfig.getEnabled3D()));
        }

        // Output directories
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        File saveRoot = layout.tablesIntensityWriteDir();
        File overlayRoot = layout.analysisImagesIntensityOverlaysDir();
        try {
            IoUtils.mustMkdirs(saveRoot);
        } catch (IOException e) {
            String message = "[FLASH] Could not create intensity tables output directory: " + e.getMessage();
            IJ.log(message);
            recordError(message, e);
            return;
        }
        try {
            IoUtils.mustMkdirs(overlayRoot);
        } catch (IOException e) {
            String message = "[FLASH] Could not create intensity overlays output directory: " + e.getMessage();
            IJ.log(message);
            recordError(message, e);
            return;
        }
        IJ.log("Tables output directory: " + saveRoot.getAbsolutePath());
        IJ.log("Overlay output directory: " + overlayRoot.getAbsolutePath());

        // Write per-channel Analysis Details (mirrors 3D Object Analysis output structure)
        File analysisDetailsDir = IntensityDetailsWriter.analysisDetailsWriteDir(new File(directory));
        try {
            IoUtils.mustMkdirs(analysisDetailsDir);
        } catch (IOException e) {
            String message = "[FLASH] Could not create Analysis Details directory: " + e.getMessage();
            IJ.log(message);
            recordError(message, e);
            return;
        }

        for (int c = 0; c < channelNames.length; c++) {
            try {
                String filterSourceLabel = buildDetailsFilterSourceLabel(c, cfg, filterSources, binDir);
                String actualMacroText = resolveDetailsFilterMacroText(c, cfg, filterSources,
                        binDir, basicFilterMacro);
                IntensityDetailsWriter.writePerChannel(analysisDetailsDir, binDir, channelNames[c], c + 1,
                        true, filterSourceLabel, actualMacroText, binarization[c], thresholds[c],
                        roiAnalysis ? roiChannelChoice : null,
                        intensitySpatialConfig,
                        cfg.getZSliceConfig().summary(),
                        overlayRoot.getAbsolutePath(),
                        "Optional intensity-spatial dependencies are checked per family at run time.",
                        "Failures are logged with image/channel/ROI/analysis context and written as NaN columns.");
                IJ.log("  Analysis details written for: " + channelNames[c]);
                recordOutput(new File(analysisDetailsDir,
                        IntensityDetailsWriter.detailsFileName(channelNames[c])), "txt");
            } catch (Exception e) {
                String message = "Failed writing intensity details for " + channelNames[c];
                IJ.log("  WARNING: " + message + ": " + e.getMessage());
                recordError(message, e);
            }
        }

        // Record parameters in QC report
        if (qualityReport != null && qualityReport.isEnabled()) {
            StringBuilder filterSummary = new StringBuilder();
            for (int i = 0; i < channelNames.length; i++) {
                if (i > 0) filterSummary.append("; ");
                filterSummary.append(channelNames[i]).append(": ");
                filterSummary.append("Bin filter".equals(filterSources[i])
                        ? savedFilterChoiceLabel(i, cfg)
                        : "Basic background and noise removal");
            }
            qualityReport.addIntensityParams(channelNames, binarization, thresholds,
                    roiAnalysis, roiChannelChoice,
                    filterSummary.toString(),
                    cfg.getZSliceConfig().summary(),
                    intensitySpatialConfig,
                    "Missing optional dependencies are logged and affected spatial metrics are written as NaN.");
        }

        // Preload all ROI sets directly from zip files — no RoiManager needed
        final Roi[][] preloadedRoiSets;
        if (roiAnalysis) {
            preloadedRoiSets = new Roi[roiZips.size()][];
            for (int rSet = 0; rSet < roiZips.size(); rSet++) {
                if (!roiZipSelected[rSet]) continue;
                java.util.List<Roi> rois;
                AnalysisRunContext.InputHandle roiInput = recordInputStart(roiZips.get(rSet), 0);
                long roiStarted = System.currentTimeMillis();
                try {
                    rois = RoiIO.loadRoisFromZip(roiZips.get(rSet));
                    recordInputEnd(roiInput, rois == null || rois.isEmpty() ? "skipped" : "processed", roiStarted);
                } catch (NoClassDefFoundError e) {
                    recordInputEnd(roiInput, "failed", roiStarted);
                    recordError("Failed to load ROI zip " + roiZips.get(rSet).getAbsolutePath(), e);
                    if (PluginInstallGuard.reportMissingInternalClass("Intensity Analysis", e)) return;
                    throw e;
                } catch (RuntimeException e) {
                    recordInputEnd(roiInput, "failed", roiStarted);
                    recordError("Failed to load ROI zip " + roiZips.get(rSet).getAbsolutePath(), e);
                    throw e;
                }
                preloadedRoiSets[rSet] = rois.toArray(new Roi[0]);
            }
        } else {
            preloadedRoiSets = new Roi[0][];
        }

        // Open images via Bio-Formats (.lif)
        IJ.log("__________________________________________________________");
        IJ.log("Opening images...");
        IJ.showStatus("Opening .lif file...");
        IJ.showProgress(0);

        // Both paths use DeferredImageSupplier; parallel path wraps it in BoundedImageLoader
        DeferredImageSupplier supplier = null;
        int totalImages;

        try {
            supplier = ImageSourceDispatcher.createSupplier(directory);
            totalImages = supplier.getTotalSeries();
            supplier = wrapInputSupplier(directory, supplier);
        } catch (Exception e) {
            IJ.log("Intensity Analysis: " + e.getMessage());
            recordError("Intensity Analysis could not create image supplier", e);
            if (canShowGuiDialog(suppressDialogs, cliConfig, GraphicsEnvironment.isHeadless())) {
                IJ.showMessage("Intensity Analysis", e.getMessage());
            }
            return;
        }

        IJ.log("Total images: " + totalImages);
        int outputStackDepth = likelyStackDepthForSpatialWizard(directory, cfg);
        if (headless) hideAllImageWindows();

        // Region-scoped ROIs cover an image subset and bind by durable image identity, so
        // precompute each image's binding token and validate structure (paired drawn +
        // cropped per token) rather than a fixed 2-per-image count.
        if (roiAnalysis) {
            imageBindTokens = new String[totalImages];
            for (int t = 0; t < totalImages; t++) {
                try {
                    String ik = OrientationImageIdentity.fromProjectSeries(
                            directory, t, supplier.getSeriesName(t)).imageKey;
                    imageBindTokens[t] = (ik != null && !ik.trim().isEmpty())
                            ? RoiSetImageBinding.token(ik) : null;
                } catch (Exception ex) {
                    imageBindTokens[t] = null;
                    IJ.log("  [FLASH] No durable identity for image " + (t + 1) + ": " + ex.getMessage());
                }
            }
            IJ.log("Validating ROI sets (identity-based, subset coverage allowed)...");
            for (int rSet = 0; rSet < roiZips.size(); rSet++) {
                if (!roiZipSelected[rSet] || preloadedRoiSets[rSet] == null) continue;
                java.util.List<Roi> roiList = java.util.Arrays.asList(preloadedRoiSets[rSet]);
                try {
                    RoiSetValidator.validateStructural(roiList);
                } catch (Exception ve) {
                    IJ.log("  WARNING: ROI set '" + roiZipNames[rSet]
                            + "' failed structural validation (" + ve.getMessage()
                            + "); deselecting it. Redraw it with Draw ROIs.");
                    recordWarn("ROI set '" + roiZipNames[rSet] + "' invalid: " + ve.getMessage());
                    roiZipSelected[rSet] = false;
                    continue;
                }
                int pairs = RoiSetImageBinding.indexByToken(roiList).size();
                IJ.log("  ROI set '" + roiZipNames[rSet] + "': " + preloadedRoiSets[rSet].length
                        + " ROIs covering " + pairs + " image(s).");
            }
            IJ.log("  ROI sets validated.");

            boolean anySelected = false;
            for (int rSet = 0; rSet < roiZips.size(); rSet++) {
                if (roiZipSelected[rSet]) { anySelected = true; break; }
            }
            if (!anySelected) {
                String message = "No valid ROI sets remain after validation; nothing to analyse.";
                IJ.log("ERROR: " + message);
                if (canShowGuiDialog(suppressDialogs, cliConfig, GraphicsEnvironment.isHeadless())) {
                    IJ.error("Intensity Analysis", message);
                }
                recordWarn(message);
                return;
            }
            // Warn when a selected zip overlaps none of the current images (likely an identity
            // mismatch): otherwise it would produce no measurements with no explanation.
            java.util.Set<String> currentTokens = new java.util.HashSet<String>();
            for (String tk : imageBindTokens) {
                if (tk != null) currentTokens.add(tk);
            }
            for (int rSet = 0; rSet < roiZips.size(); rSet++) {
                if (!roiZipSelected[rSet] || preloadedRoiSets[rSet] == null) continue;
                boolean overlaps = false;
                for (String tk : RoiSetImageBinding.indexByToken(
                        java.util.Arrays.asList(preloadedRoiSets[rSet])).keySet()) {
                    if (currentTokens.contains(tk)) { overlaps = true; break; }
                }
                if (!overlaps) {
                    IJ.log("  WARNING: ROI set '" + roiZipNames[rSet]
                            + "' covers none of the current images (no token overlap); it will "
                            + "produce no measurements.");
                    recordWarn("ROI set '" + roiZipNames[rSet]
                            + "' has no token overlap with the current images.");
                }
            }
        }

        // Accumulate output tables (one per channel)
        final IntensityOutputPlan outputPlan = buildOutputPlan(saveRoot, channelNames,
                roiAnalysis, roiChannelIndex1Based, intensitySpatialConfig,
                outputStackDepth, skipExisting);
        logOutputPlan(outputPlan);
        if (skipExisting && outputPlan.allSelectedOutputsSkipped()) {
            String message = "All selected intensity output CSVs already exist - skipping entire Intensity Analysis.";
            IJ.log(message);
            recordWarn(message);
            IJ.showProgress(1.0);
            IJ.showStatus("");
            return;
        }

        final IntensityOutputTables totalTables = outputPlan.newTables();

        long analysisStartTime = System.currentTimeMillis();
        IJ.log("__________________________________________________________");
        IJ.log("Beginning intensity measurements...");
        if (parallelThreads > 1) {
            IJ.log("Parallel mode: " + parallelThreads + " threads");
        }

        if (parallelThreads > 1) {
            List<Integer> indicesToProcess = new ArrayList<Integer>();
            for (int ip = 0; ip < totalImages; ip++) {
                indicesToProcess.add(ip);
            }
            // 80% loader threads, 20% worker threads
            int effectiveLoaders = Math.max(1, (int) Math.round(parallelThreads * 0.8));
            int effectiveWorkers = Math.max(1, parallelThreads - effectiveLoaders);
            int bufferSize = Math.min(4, Math.max(2, effectiveWorkers));
            int safeThreads = AdaptiveParallelism.computeAndLog(supplier, effectiveWorkers, bufferSize);
            BoundedImageLoader loader = new BoundedImageLoader(supplier, indicesToProcess, bufferSize,
                    effectiveLoaders, useTifCache && !useDeconvolvedInput, directory);
            try {
                List<SeriesMeta> metas = ImageSourceDispatcher.readAllMetadata(directory);
                List<String> names = new ArrayList<String>();
                for (SeriesMeta m : metas) names.add(m.name);
                loader.setSeriesNames(names);
            } catch (Exception e) {
                String message = "Could not read series names for intensity loader progress in "
                        + directory + ": " + e.getMessage();
                IJ.log("    - WARNING: " + message);
                recordWarn(message);
            }
            loader.start();
            IJ.log("Thread split: " + effectiveLoaders + " loaders, " + safeThreads + " workers");
            compactLog = true;
            processImagesParallel(loader, directory, cfg, safeThreads, channelNames, binarization, thresholds,
                    roiAnalysis, roiZips, roiZipNames, roiZipSelected, roiChannelIndex1Based,
                    filterSources, binDir,
                    basicFilterMacro, outputPlan, totalTables, analysisStartTime, preloadedRoiSets);
            compactLog = false;
        } else {
            compactLog = false;
            processImagesSequential(supplier, totalImages, directory, cfg, channelNames, binarization, thresholds,
                    roiAnalysis, roiZips, roiZipNames, roiZipSelected, roiChannelIndex1Based,
                    filterSources, binDir,
                    basicFilterMacro, outputPlan, totalTables, analysisStartTime, preloadedRoiSets);
        }

        // Export selected CSVs.
        IJ.log("__________________________________________________________");
        IJ.log("Saving results...");
        for (IntensitySpatialOutputKey key : outputPlan.selectedKeys()) {
            if (outputPlan.isSkipped(key)) {
                IJ.log("  Skipped existing: " + outputPlan.fileFor(key).getName());
                continue;
            }
            try {
                File outCsv = outputPlan.fileFor(key);
                ResultsTable table = totalTables.table(key);
                List<String> orderedColumns = buildOrderedIntensityColumns(key, table,
                        channelNames, intensitySpatialConfig, binarization);
                boolean merged = CsvTableIO.mergeResultsTableCsv(outCsv, table, orderedColumns,
                        currentRunId());
                if (!merged) {
                    CsvTableIO.writeResultsTableCsv(outCsv, table, orderedColumns, currentRunId());
                }
                recordOutput(outCsv, "csv");
                IJ.log("  " + (merged ? "Updated existing: " : "Saved: ")
                        + outCsv.getName() + " (" + table.size() + " rows)");
            } catch (Exception e) {
                String message = "Failed saving intensity CSV for "
                        + key.channelName() + " " + key.mode();
                IJ.log("  WARNING: " + message + ": " + e.getMessage());
                recordError(message, e);
            }
        }

        // Close all remaining image windows, leaving only the Log visible
        closeAllImagesOnly();

        IJ.showProgress(1.0);
        IJ.showStatus("");

        long totalTime = System.currentTimeMillis() - analysisStartTime;
        IJ.log("__________________________________________________________");
        if (lifOpenTimeMs > 0) {
            IJ.log("Intensity Analysis complete. Total time: " + formatDuration(totalTime) + " (processing) + "
                    + formatDuration(lifOpenTimeMs) + " (lif open)");
        } else {
            IJ.log("Intensity Analysis complete. Total time: " + formatDuration(totalTime));
        }
        // Non-blocking completion signal. A modal "Finished" dialog here would
        // stall unattended batch runs until a human clicked OK.
        IJ.showStatus("Intensity Analysis finished.");
    }

    BinConfig loadBinConfig(String directory) {
        return BinConfigIO.readPartialFromDirectory(directory);
    }

    // ── Sequential processing (original path, with thread-safe ops) ──

    private void processImagesSequential(
            final DeferredImageSupplier supplier, final int totalImages,
            final String directory,
            final BinConfig cfg,
            String[] channelNames,
            boolean[] binarization, String[] thresholds,
            boolean roiAnalysis, List<File> roiZips, String[] roiZipNames,
            boolean[] roiZipSelected, int roiChannelIndex1Based,
            String[] filterSources, File binDir,
            String basicFilterMacro, IntensityOutputPlan outputPlan,
            IntensityOutputTables totalTables, long analysisStartTime, Roi[][] preloadedRoiSets
    ) {
        ExecutorService prefetcher = Executors.newSingleThreadExecutor();
        Future<ImagePlus> nextImage = null;

        for (int idx = 0; idx < totalImages; idx++) {
            IJ.showStatus("Loading image " + (idx + 1) + "/" + totalImages + "...");
            IJ.showProgress(idx, totalImages);

            ImagePlus imp;
            if (nextImage != null) {
                try {
                    imp = nextImage.get();
                } catch (Exception e) {
                    String message = "Failed to load prefetched image " + (idx + 1);
                    IJ.log("ERROR: " + message + ": " + e.getMessage());
                    recordError(message, e);
                    nextImage = null;
                    continue;
                }
                nextImage = null;
            } else {
                IJ.log("Loading image " + (idx + 1) + "/" + totalImages + "...");
                try {
                    imp = supplier.openSeries(idx);
                } catch (Exception e) {
                    String message = "Failed to open image " + (idx + 1);
                    IJ.log("ERROR: " + message + ": " + e.getMessage());
                    recordError(message, e);
                    continue;
                }
            }
            if (imp == null) continue;
            int firstZSlice = firstOriginalZSlice(cfg, idx, imp);
            imp = applyConfiguredZSliceSubset(cfg, idx, imp, "Intensity Analysis");

            // Start loading next image while processing this one
            if (idx + 1 < totalImages) {
                final int nextIdx = idx + 1;
                nextImage = prefetcher.submit(new Callable<ImagePlus>() {
                    @Override
                    public ImagePlus call() throws Exception {
                        return supplier.openSeries(nextIdx);
                    }
                });
            }

            ResolvedImageMetadata metadata = ImageOrientationResolver.resolve(
                    directory, imp.getTitle(), idx + 1);
            NameParts parts = metadata.toNameParts();

            long imageStartTime = verboseLogging ? System.currentTimeMillis() : 0;

            IJ.log("__________________________________________________________");
            IJ.log("Image Stack " + (idx + 1) + "/" + totalImages + ": " + imp.getTitle());
            IJ.log("  Animal: " + parts.animal + " | Hemisphere: " + parts.hemisphere);
            if (idx > 0) {
                long elapsed = System.currentTimeMillis() - analysisStartTime;
                long avgPerImage = elapsed / idx;
                long remainingMs = avgPerImage * (totalImages - idx);
                IJ.log("  Estimated time to completion: " + formatDuration(remainingMs));
            }

            if (verboseLogging) {
                IJ.log("  [DEBUG] Parsed: Animal=" + parts.animal + ", Hemisphere=" + parts.hemisphere);
                IJ.log("  [DEBUG] Image dimensions: " + imp.getWidth() + "x" + imp.getHeight()
                        + "x" + imp.getNSlices() + ", channels=" + imp.getNChannels());
            }

            logOrientationResolution(metadata);
            OrientationOps.applyTransform(imp, metadata);

            ImagePlus[] chans = null;
            try {
                chans = ChannelSplitter.split(imp);
                int n = resolveProcessableChannelCount(imp, chans, channelNames, idx);
                IJ.log("  Channels split: " + n);
                if (n <= 0) {
                    String message = "No processable channels for " + imp.getTitle() + "; skipping image.";
                    IJ.log("  WARNING: " + message);
                    recordWarn(message);
                    continue;
                }

                if (roiAnalysis) {
                    for (int rSet = 0; rSet < roiZips.size(); rSet++) {
                        if (!roiZipSelected[rSet]) continue;

                        IJ.log("  > ROI set: " + roiZipNames[rSet]);
                        String bindToken = (imageBindTokens != null && idx < imageBindTokens.length)
                                ? imageBindTokens[idx] : null;
                        Roi boundRoi = findDrawnRoiByToken(preloadedRoiSets[rSet], bindToken);
                        if (boundRoi == null) {
                            IJ.log("    ROI set '" + roiZipNames[rSet] + "' does not cover image "
                                    + (idx + 1) + "; skipping for this region.");
                            continue;
                        }
                        Roi activeRoi = (Roi) boundRoi.clone();
                        IJ.log("    ROI bound by image identity"
                                + (activeRoi != null ? " (" + activeRoi.getTypeAsString() + ")" : ""));

                        runIntensityMeasurementsForThisImage(
                                parts, chans, n,
                                binarization, thresholds,
                                channelNames, roiChannelIndex1Based,
                                outputPlan, totalTables, idx + 1, roiZipNames[rSet],
                                totalImages, firstZSlice,
                                cfg, filterSources, binDir,
                                basicFilterMacro, activeRoi);
                    }
                } else {
                    runIntensityMeasurementsForThisImage(
                            parts, chans, n,
                            binarization, thresholds,
                            channelNames, -1,
                            outputPlan, totalTables, idx + 1, null,
                            totalImages, firstZSlice,
                            cfg, filterSources, binDir,
                            basicFilterMacro, null);
                }

            } finally {
                closeImages(chans);
                imp.changes = false;
                imp.close();
                imp.flush();

                // Update progress bar
                int done = idx + 1;
                IJ.showProgress(done, totalImages);
                if (done < totalImages) {
                    long elapsed = System.currentTimeMillis() - analysisStartTime;
                    long avgPerImage = elapsed / done;
                    long remainingMs = avgPerImage * (totalImages - done);
                    IJ.showStatus("Processing " + done + "/" + totalImages
                            + " (~" + formatDuration(remainingMs) + " remaining)");
                }

                if (verboseLogging) {
                    long imageElapsed = System.currentTimeMillis() - imageStartTime;
                    IJ.log("  [DEBUG] Image processing time: " + formatDuration(imageElapsed));
                }

            }
        }
        prefetcher.shutdown();
    }

    // ── Parallel processing ──

    private void processImagesParallel(
            final BoundedImageLoader loader,
            final String directory,
            final BinConfig cfg,
            final int nThreads,
            final String[] channelNames,
            final boolean[] binarization, final String[] thresholds,
            final boolean roiAnalysis, final List<File> roiZips, final String[] roiZipNames,
            final boolean[] roiZipSelected, final int roiChannelIndex1Based,
            final String[] filterSources, final File binDir,
            final String basicFilterMacro, final IntensityOutputPlan outputPlan,
            final IntensityOutputTables totalTables, final long analysisStartTime,
            final Roi[][] preloadedRois
    ) {
        final int total = loader.totalToLoad();
        final AtomicInteger completed = new AtomicInteger(0);
        int effectiveThreads = Math.min(nThreads, total);

        ExecutorService pool = Executors.newFixedThreadPool(effectiveThreads);
        List<Future<?>> futures = new ArrayList<Future<?>>();

        for (int t = 0; t < effectiveThreads; t++) {
            final int workerNum = t + 1;
            futures.add(pool.submit(new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        BoundedImageLoader.IndexedImage indexed;
                        try {
                            indexed = loader.take();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        if (indexed == null) break; // no more images

                        ImagePlus imp = indexed.image;
                        int idx = indexed.index;
                        int firstZSlice = firstOriginalZSlice(cfg, idx, imp);
                        imp = applyConfiguredZSliceSubset(cfg, idx, imp, "Intensity Analysis");

                        ParallelContext.enterParallel();
                        try {
                            ResolvedImageMetadata metadata = ImageOrientationResolver.resolve(
                                    directory, imp.getTitle(), idx + 1);
                            NameParts parts = metadata.toNameParts();

                            // Worker start log with short name and channels
                            String workerTag = effectiveThreads > 1
                                    ? "Worker " + workerNum : "Worker";
                            String partLabel = parts.displayLabel();
                            StringBuilder chList = new StringBuilder();
                            for (int c = 0; c < channelNames.length; c++) {
                                if (c > 0) chList.append(" ");
                                chList.append(channelNames[c]);
                            }
                            IJ.log(workerTag + ": processing " + partLabel
                                    + " | " + chList.toString());

                            long imageStartTime = System.currentTimeMillis();

                            if (!compactLog) {
                                IJ.log("[" + (idx + 1) + "/" + total + "] Processing: " + imp.getTitle());
                            }

                            logOrientationResolution(metadata);
                            OrientationOps.applyTransform(imp, metadata);

                            ImagePlus[] chans = null;
                            try {
                                chans = ChannelSplitter.split(imp);
                                int n = resolveProcessableChannelCount(imp, chans, channelNames, idx);
                                if (n <= 0) {
                                    String message = "No processable channels for " + imp.getTitle()
                                            + "; skipping image.";
                                    IJ.log("[" + (idx + 1) + "/" + total + "] WARNING: " + message);
                                    recordWarn(message);
                                    int done = completed.incrementAndGet();
                                    IJ.showProgress(done, total);
                                    continue;
                                }

                                // Per-image local tables to collect results before merging
                                IntensityOutputTables localTables = outputPlan.newTables();

                                if (roiAnalysis) {
                                    for (int rSet = 0; rSet < roiZips.size(); rSet++) {
                                        if (!roiZipSelected[rSet]) continue;

                                        // Bind ROI by durable image identity token (subset coverage allowed)
                                        String bindToken = (imageBindTokens != null && idx < imageBindTokens.length)
                                                ? imageBindTokens[idx] : null;
                                        Roi boundRoi = findDrawnRoiByToken(preloadedRois[rSet], bindToken);
                                        if (boundRoi == null) {
                                            IJ.log("[" + (idx + 1) + "/" + total + "] ROI set '"
                                                    + roiZipNames[rSet] + "' does not cover this image; skipping.");
                                            continue;
                                        }
                                        Roi activeRoi = (Roi) boundRoi.clone();

                                        runIntensityMeasurementsForThisImage(
                                                parts, chans, n,
                                                binarization, thresholds,
                                                channelNames, roiChannelIndex1Based,
                                                outputPlan, localTables, idx + 1, roiZipNames[rSet],
                                                total, firstZSlice,
                                                cfg, filterSources, binDir,
                                                basicFilterMacro, activeRoi);
                                    }
                                } else {
                                    runIntensityMeasurementsForThisImage(
                                            parts, chans, n,
                                            binarization, thresholds,
                                            channelNames, -1,
                                            outputPlan, localTables, idx + 1, null,
                                            total, firstZSlice,
                                            cfg, filterSources, binDir,
                                            basicFilterMacro, null);
                                }

                                // Merge local tables into master totalTables
                                synchronized (totalTables) {
                                    totalTables.mergeFrom(localTables);
                                }
                            } finally {
                                closeImages(chans);
                            }

                            int done = completed.incrementAndGet();
                            long elapsed = System.currentTimeMillis() - analysisStartTime;
                            IJ.showProgress(done, total);
                            // Only show time estimate after first full wave of threads completes
                            if (done >= nThreads && done < total) {
                                long remainingMs = elapsed * (total - done) / done;
                                IJ.showStatus("Processing " + done + "/" + total
                                        + " (~" + formatDuration(remainingMs) + " remaining)");
                            } else {
                                IJ.showStatus("Processing " + done + "/" + total);
                            }

                            if (compactLog) {
                                long imageElapsed = System.currentTimeMillis() - imageStartTime;
                                String etaStr = "";
                                if (done >= nThreads && done < total) {
                                    long remainingMs = elapsed * (total - done) / done;
                                    etaStr = " | ETA: " + formatDurationCompact(remainingMs);
                                }
                                IJ.log("[" + done + "/" + total + "] " + partLabel
                                        + " Completed in " + formatDurationCompact(imageElapsed) + etaStr);
                            } else {
                                long rem = done > 0 ? elapsed * (total - done) / done : 0;
                                IJ.log("[" + (idx + 1) + "/" + total + "] Complete (" + done + "/" + total
                                        + " done, ~" + formatDuration(rem) + " remaining)");
                            }

                        } catch (Throwable t) {
                            rethrowIfFatal(t);
                            handleParallelImageThrowable(idx, total, t);
                        } finally {
                            imp.changes = false;
                            imp.close();
                            imp.flush();
                            ParallelContext.exitParallel();
                        }
                    }
                }
            }));
        }

        // Wait for all workers to complete
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                rethrowIfFatal(cause);
                String message = "Parallel worker failed outside image scope: "
                        + describeThrowable(cause);
                IJ.log("[FLASH] " + message);
                recordError(message, cause);
            }
        }
        pool.shutdown();
    }

    private void handleParallelImageThrowable(int zeroBasedIndex, int total, Throwable t) {
        String prefix = "[" + (zeroBasedIndex + 1) + "/" + total + "] ERROR: ";
        if (t instanceof NoClassDefFoundError
                && stalePluginWarningShown.compareAndSet(false, true)
                && PluginInstallGuard.reportMissingInternalClass(
                "Fluorescence Intensity Analysis", (NoClassDefFoundError) t)) {
            IJ.log(prefix + "stale or partial FLASH plugin JAR; see message above.");
            recordError("Stale or partial FLASH plugin JAR while processing intensity image "
                    + (zeroBasedIndex + 1), t);
            return;
        }
        IJ.log(prefix + describeThrowable(t));
        recordError("Intensity Analysis failed while processing image " + (zeroBasedIndex + 1), t);
    }

    private static String describeThrowable(Throwable t) {
        if (t == null) return "unknown";
        String message = t.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return t.getClass().getSimpleName();
        }
        return t.getClass().getSimpleName() + ": " + message.trim();
    }

    private static RuntimeException buildParallelFailure(String message, List<Throwable> failures) {
        RuntimeException combined = new RuntimeException(message);
        synchronized (failures) {
            for (Throwable failure : failures) {
                if (failure != null) {
                    combined.addSuppressed(failure);
                }
            }
        }
        return combined;
    }

    private int resolveProcessableChannelCount(ImagePlus imp,
                                               ImagePlus[] chans,
                                               String[] channelNames,
                                               int zeroBasedImageIndex) {
        int actual = chans == null ? 0 : chans.length;
        int configured = channelNames == null ? 0 : channelNames.length;
        int n = Math.min(actual, configured);
        if (actual != configured) {
            String title = imp == null ? "image " + (zeroBasedImageIndex + 1) : imp.getTitle();
            String message = "Channel count mismatch for " + title
                    + ": image has " + actual + ", configuration has " + configured
                    + "; processing " + n + ".";
            IJ.log("  WARNING: " + message);
            recordWarn(message);
        }
        return n;
    }

    // ── Per-image measurement (thread-safe) ──

    private void runIntensityMeasurementsForThisImage(
            NameParts parts,
            ImagePlus[] chans,
            int n,
            boolean[] binarization,
            String[] thresholds,
            String[] channelNames,
            int roiChannelIndex1Based,
            IntensityOutputPlan outputPlan,
            IntensityOutputTables totalTables,
            int scnIndex1Based,
            String roiSetName,
            int totalImages,
            int firstZSlice,
            final BinConfig cfg,
            final String[] filterSources,
            final File binDir,
            String basicFilterMacro,
            Roi roi
    ) {
        String roiBase = roiSetName == null ? "" : roiSetName;
        final String roiLabel = parts == null
                ? (scnIndex1Based > 0 && !endsWithDigit(roiBase) ? roiBase + scnIndex1Based : roiBase)
                : parts.analysisRegionLabel(roiBase, scnIndex1Based);
        final String imageProgressLabel = imageProgressLabel(scnIndex1Based, totalImages);
        final String imageIdWithProgress = imageIdWithProgress(parts, imageProgressLabel);
        final String imageStepContext = imageIdWithProgress + roiLogSuffix(roiLabel);

        logProgressStep(imageStepContext, "starting intensity measurements ("
                + n + " " + plural(n, "channel") + ")");

        // Optional ROI-channel mask stack creation. The mask is built from
        // the chosen channel's resolved filter (bin or basic) and the user's
        // explicit threshold for that channel - never an auto-threshold.
        ImagePlus maskStack = null;
        if (roiChannelIndex1Based > n) {
            String maskName = roiChannelIndex1Based > 0
                    && channelNames != null
                    && roiChannelIndex1Based <= channelNames.length
                    ? channelNames[roiChannelIndex1Based - 1]
                    : "index " + roiChannelIndex1Based;
            throw new IllegalStateException("Channel ROI mask '" + maskName
                    + "' is not present in image "
                    + (parts == null ? "unknown" : parts.displayLabel())
                    + roiLogSuffix(roiSetName) + ".");
        }
        if (roiChannelIndex1Based > 0 && roiChannelIndex1Based <= n) {
            int mc = roiChannelIndex1Based - 1;
            logProgressStep(imageStepContext, "building ROI channel mask from " + channelNames[mc]);
            if (!compactLog) IJ.log("    Creating ROI channel mask from: " + channelNames[mc]);
            boolean useBinFilterForMask = filterSources != null
                    && mc < filterSources.length
                    && "Bin filter".equals(filterSources[mc]);
            String filterMacroOrPath;
            boolean isMacroFile;
            if (useBinFilterForMask) {
                File macroFile = new File(binDir, cfg.filterMacroFilenameForChannelIndex(mc));
                if (macroFile.exists()) {
                    filterMacroOrPath = macroFile.getAbsolutePath();
                    isMacroFile = true;
                } else {
                    if (!compactLog) IJ.log("    - ROI mask: WARN " + macroFile.getName()
                            + " missing; falling back to basic filter");
                    filterMacroOrPath = basicFilterMacro;
                    isMacroFile = false;
                }
            } else {
                filterMacroOrPath = basicFilterMacro;
                isMacroFile = false;
            }
            String maskFilterLabel = useBinFilterForMask
                    ? savedFilterChoiceLabel(mc, cfg)
                    : "Basic background and noise removal";
            maskStack = createRoiChannelMask(chans[mc],
                    filterMacroOrPath, isMacroFile, thresholdToken(thresholds, mc),
                    !compactLog, maskFilterLabel);
        }

        // Parallelize per-channel processing: each channel's filter + threshold + measure
        // is independent. The maskStack is read-only (safe to share), and each channel
        // creates its own duplicate images.
        final int channelCount = n;
        int channelThreads = Math.min(channelCount, Math.max(1, parallelThreads));

        // Per-channel result holders (pre-allocated slots, one per channel — no contention)
        final double[][] allIntDenFilteredFullRoi = new double[channelCount][];
        final double[][] allAreaFractionFilteredFullRoi = new double[channelCount][];
        final double[][] allIntDenUnfilteredFullRoi = new double[channelCount][];
        final double[][] allIntDenBinarizedRawInMask = new double[channelCount][];
        final double[][] allAreaFractionBinarized = new double[channelCount][];
        final IntensitySpatialResult[][] allBaseSpatialResults = new IntensitySpatialResult[channelCount][];
        final IntensitySpatialResult[] allMipSpatialResults = new IntensitySpatialResult[channelCount];
        final IntensitySpatialResult[] allNativeSpatialResults = new IntensitySpatialResult[channelCount];
        final ImagePlus[] allRawSpatialImages = new ImagePlus[channelCount];
        final ImagePlus[] allBinarizedSpatialImages = new ImagePlus[channelCount];
        final ImagePlus[] allBinarySpatialMasks = new ImagePlus[channelCount];

        // Capture effectively-final references for inner class access
        final ImagePlus finalMaskStack = maskStack;
        final Roi finalRoi = roi;
        final NameParts finalParts = parts;
        final boolean finalVerboseLogging = verboseLogging;
        final String finalRoiLabel = roiLabel;
        final String finalImageProgressLabel = imageProgressLabel;
        final String finalImageStepContext = imageStepContext;

        try {
            if (channelThreads > 1) {
                // Parallel channel processing
                ExecutorService channelPool = Executors.newFixedThreadPool(channelThreads);
                List<Future<?>> channelFutures = new ArrayList<Future<?>>();
                List<Throwable> channelFailures =
                        Collections.synchronizedList(new ArrayList<Throwable>());

                try {
                    for (int ci = 0; ci < channelCount; ci++) {
                        final int c = ci;
                        channelFutures.add(channelPool.submit(new Runnable() {
                            @Override
                            public void run() {
                                processOneChannel(c, channelCount, chans, channelNames,
                                        binarization, thresholds,
                                        cfg, filterSources, binDir, basicFilterMacro,
                                        finalMaskStack, finalRoi, finalParts, finalRoiLabel,
                                        finalImageProgressLabel, finalImageStepContext,
                                        outputPlan, intensitySpatialConfig, finalVerboseLogging,
                                        allIntDenFilteredFullRoi, allAreaFractionFilteredFullRoi,
                                        allIntDenUnfilteredFullRoi, allIntDenBinarizedRawInMask,
                                        allAreaFractionBinarized,
                                        allBaseSpatialResults, allMipSpatialResults, allNativeSpatialResults,
                                        allRawSpatialImages, allBinarizedSpatialImages,
                                        allBinarySpatialMasks);
                            }
                        }));
                    }

                    // Wait for all channels to complete
                    for (Future<?> f : channelFutures) {
                        try {
                            f.get();
                        } catch (Exception e) {
                            if (e instanceof InterruptedException) {
                                Thread.currentThread().interrupt();
                            }
                            Throwable cause = e.getCause() == null ? e : e.getCause();
                            rethrowIfFatal(cause);
                            channelFailures.add(cause);
                            IJ.log("    ERROR in parallel channel processing: "
                                    + describeThrowable(cause));
                        }
                    }
                } finally {
                    channelPool.shutdown();
                }

                if (!channelFailures.isEmpty()) {
                    throw buildParallelFailure("Intensity analysis failed for "
                            + channelFailures.size() + " channel(s)", channelFailures);
                }
            } else {
                // Sequential channel processing (single thread)
                for (int c = 0; c < channelCount; c++) {
                    processOneChannel(c, channelCount, chans, channelNames,
                            binarization, thresholds,
                            cfg, filterSources, binDir, basicFilterMacro,
                            finalMaskStack, finalRoi, finalParts, finalRoiLabel,
                            finalImageProgressLabel, finalImageStepContext,
                            outputPlan, intensitySpatialConfig, finalVerboseLogging,
                            allIntDenFilteredFullRoi, allAreaFractionFilteredFullRoi,
                            allIntDenUnfilteredFullRoi, allIntDenBinarizedRawInMask,
                            allAreaFractionBinarized,
                            allBaseSpatialResults, allMipSpatialResults, allNativeSpatialResults,
                            allRawSpatialImages, allBinarizedSpatialImages,
                            allBinarySpatialMasks);
                }
            }

            try {
                measureCrossChannelSpatial(channelNames, channelCount, finalRoi, finalRoiLabel,
                        finalParts, finalImageProgressLabel, finalImageStepContext,
                        outputPlan, intensitySpatialConfig,
                        allRawSpatialImages, allBinarizedSpatialImages, allBinarySpatialMasks,
                        allBaseSpatialResults, allMipSpatialResults, allNativeSpatialResults);
            } catch (Throwable t) {
                rethrowIfFatal(t);
                IJ.log("[FLASH] Intensity-spatial cross-channel skipped for "
                        + (finalParts == null ? "unknown" : finalParts.displayLabel())
                        + roiLogSuffix(finalRoiLabel)
                        + ": runtime class/dependency problem: " + safeThrowableMessage(t));
            }

            // Merge per-channel results into totalTables in order (single-threaded, no contention)
            for (int c = 0; c < channelCount; c++) {
                if (allIntDenFilteredFullRoi[c] == null) {
                    throw new IllegalStateException("Intensity analysis produced no results for channel "
                            + channelNames[c]);
                }
                IntensitySpatialOutputKey baseKey = outputPlan.baseKeyForChannel(channelNames[c]);
                int len = allIntDenFilteredFullRoi[c].length;
                if (outputPlan.shouldPopulate(baseKey)) {
                    ResultsTable total = totalTables.table(baseKey);
                    for (int r = 0; r < len; r++) {
                        total.incrementCounter();
                        int row = total.size() - 1;

                        writeMetadataColumns(total, row, parts, roiLabel);
                        writeZColumn(total, row, firstZSlice + r);
                        writeMeasurementColumns(total, row,
                                allIntDenFilteredFullRoi[c][r],
                                allAreaFractionFilteredFullRoi[c][r],
                                allIntDenUnfilteredFullRoi[c][r],
                                binarization[c],
                                allIntDenBinarizedRawInMask[c][r],
                                allAreaFractionBinarized[c][r]);
                        writeSpatialPlaceholderColumns(total, row, baseKey, channelNames,
                                binarization, intensitySpatialConfig);
                        writeSpatialResultColumns(total, row, allBaseSpatialResults[c], r);
                    }
                }
                appendSpatialModeRow(outputPlan, totalTables, parts, roiLabel, channelNames[c],
                        IntensitySpatialOutputMode.MIP, channelNames, binarization,
                        intensitySpatialConfig, allMipSpatialResults[c]);
                appendSpatialModeRow(outputPlan, totalTables, parts, roiLabel, channelNames[c],
                        IntensitySpatialOutputMode.NATIVE_3D, channelNames, binarization,
                        intensitySpatialConfig, allNativeSpatialResults[c]);
                if (!compactLog) IJ.log("    - Channel " + channelNames[c] + ": " + len + " rows merged to results table");
            }

        } finally {
            closeImages(allRawSpatialImages);
            closeImages(allBinarizedSpatialImages);
            closeImages(allBinarySpatialMasks);
            if (maskStack != null) {
                maskStack.changes = false;
                maskStack.close();
                maskStack.flush();
            }
        }
    }

    /**
     * Process a single channel: duplicate, filter, optional binarization, optional mask,
     * measure, and store results into the pre-allocated arrays.
     * Thread-safe: each invocation works on its own image duplicates.
     */
    private void processOneChannel(
            int c, int n,
            ImagePlus[] chans, String[] channelNames,
            boolean[] binarization, String[] thresholds,
            BinConfig cfg, String[] filterSources, File binDir,
            String basicFilterMacro,
            ImagePlus maskStack, Roi roi,
            NameParts parts, String roiLabel,
            String imageProgressLabel,
            String imageStepContext,
            IntensityOutputPlan outputPlan,
            IntensitySpatialConfig spatialConfig,
            boolean verbose,
            double[][] outIntDenFilteredFullRoi,
            double[][] outAreaFractionFilteredFullRoi,
            double[][] outIntDenUnfilteredFullRoi,
            double[][] outIntDenBinarizedRawInMask,
            double[][] outAreaFractionBinarized,
            IntensitySpatialResult[][] outBaseSpatialResults,
            IntensitySpatialResult[] outMipSpatialResults,
            IntensitySpatialResult[] outNativeSpatialResults,
            ImagePlus[] outRawSpatialImages,
            ImagePlus[] outBinarizedSpatialImages,
            ImagePlus[] outBinarySpatialMasks
    ) {
        long chStart = verbose ? System.currentTimeMillis() : 0;
        String channelProgress = "channel " + (c + 1) + "/" + n + " " + channelNames[c];
        boolean logChannelProgress = !compactLog || isSpatialEnabled(spatialConfig);
        if (logChannelProgress) {
            logProgressStep(imageStepContext, channelProgress + " started");
        } else {
            showProgressStep(imageStepContext, channelProgress + " started");
        }
        if (!compactLog) IJ.log("  > Channel " + (c + 1) + "/" + n + ": " + channelNames[c]);

        ImagePlus raw = null;
        ImagePlus binary = null;
        ImagePlus filteredMeasurement = null;
        ImagePlus binarizedRawInMask = null;

        try {
        showProgressStep(imageStepContext, channelProgress + " filtering");
        raw = ImageOps.duplicateThreadSafe(chans[c]);
        raw.setTitle(channelNames[c] + "_raw");

        // Create single filtered copy — used for both measurement and binarization
        ImagePlus filtered = ImageOps.duplicateThreadSafe(chans[c]);
        filtered.setTitle(channelNames[c] + "_filtered");
        filteredMeasurement = filtered;

        // Per-channel filter dispatch:
        //   "Bin filter"  -> load saved Cn_Filters.ijm, fall back to basic if missing
        //   "Basic ..."   → run basic intensity filter macro
        boolean useBinFilter = filterSources != null
                && c < filterSources.length
                && "Bin filter".equals(filterSources[c]);
        if (useBinFilter) {
            String filterFilename = cfg.filterMacroFilenameForChannelIndex(c);
            File macroFile = new File(binDir, filterFilename);
            if (macroFile.exists()) {
                FilterExecutor.runIjmFileThreadSafe(filtered, macroFile);
                if (!compactLog) IJ.log("    - Filter applied: " + savedFilterChoiceLabel(c, cfg));
            } else {
                if (!compactLog) IJ.log("    - WARN: " + macroFile.getName()
                        + " missing; falling back to basic filter");
                FilterExecutor.runThreadSafe(filtered, basicFilterMacro);
                if (!compactLog) IJ.log("    - Basic background and noise removal applied");
            }
        } else {
            FilterExecutor.runThreadSafe(filtered, basicFilterMacro);
            if (!compactLog) IJ.log("    - Basic background and noise removal applied");
        }

        // Binary duplicate from already-filtered image (no need to re-filter)
        binary = ImageOps.duplicateThreadSafe(filtered);
        binary.setTitle(channelNames[c] + "_binary");

        if (binarization[c]) {
            if (!compactLog) IJ.log("    - Binarization: applying threshold=" + thresholds[c]);
            String thresholdToken = thresholdToken(thresholds, c);

            // Thread-safe binarization
            if (isAlgorithmicIntensityThreshold(thresholdToken)) {
                if (!ThresholdOps.applyStackThresholdInPlace(binary, thresholdToken, false)) {
                    throw invalidIntensityThreshold(thresholdToken, c, channelNames, parts, roiLabel);
                }
            } else {
                double thr = parseIntensityThreshold(thresholds, c, channelNames, parts, roiLabel);
                ij.ImageStack binStack = binary.getStack();
                for (int s = 1; s <= binStack.getSize(); s++) {
                    ij.process.ImageProcessor ip = binStack.getProcessor(s);
                    for (int p = 0; p < ip.getWidth() * ip.getHeight(); p++) {
                        ip.set(p, ip.getf(p) >= thr ? 255 : 0);
                    }
                }
            }

            ImagePlus and = ImageCalcOps.andStackThreadSafe(binary, raw);
            if (and != null) {
                and.setTitle(channelNames[c] + "_binarized");
                binarizedRawInMask = and;
                if (!compactLog) IJ.log("    - Binarization AND applied with raw image");
            }
        } else {
            if (!compactLog) IJ.log("    - Binarization: skipped");
        }

        if (maskStack != null) {
            if (!compactLog) IJ.log("    - Applying ROI channel mask");
            ImagePlus maskedFiltered = applyRoiChannelMask(maskStack, filteredMeasurement);
            if (maskedFiltered != null) {
                maskedFiltered.setTitle(channelNames[c] + "_filtered");
                closeImage(filteredMeasurement);
                filteredMeasurement = maskedFiltered;
                if (binarizedRawInMask != null) {
                    ImagePlus maskedBinarized = applyRoiChannelMask(maskStack, binarizedRawInMask);
                    if (maskedBinarized != null) {
                        maskedBinarized.setTitle(channelNames[c] + "_binarized");
                        closeImage(binarizedRawInMask);
                        binarizedRawInMask = maskedBinarized;
                    }
                }
                if (!compactLog) IJ.log("    - ROI channel mask applied");
            }
        }

        // Thread-safe measurement using ThreadSafeMeasure
        showProgressStep(imageStepContext, channelProgress + " measuring intensity");
        Roi measureRoi = roi != null ? (Roi) roi.clone() : null;
        ThreadSafeMeasure.SliceResult[] sliceResults = ThreadSafeMeasure.measureAllSlices(
                filteredMeasurement, raw, binarizedRawInMask, measureRoi);
        double[] intDenFilteredFullRoi = new double[sliceResults.length];
        double[] areaFractionFilteredFullRoi = new double[sliceResults.length];
        double[] intDenUnfilteredFullRoi = new double[sliceResults.length];
        double[] intDenBinarizedRawInMask = new double[sliceResults.length];
        double[] areaFractionBinarized = new double[sliceResults.length];
        for (int sr = 0; sr < sliceResults.length; sr++) {
            intDenFilteredFullRoi[sr] = finiteOrNaN(sliceResults[sr].intDenFilteredFullRoi);
            areaFractionFilteredFullRoi[sr] = finiteOrNaN(sliceResults[sr].areaFractionFilteredFullRoi);
            intDenUnfilteredFullRoi[sr] = finiteOrNaN(sliceResults[sr].intDenUnfilteredFullRoi);
            intDenBinarizedRawInMask[sr] = finiteOrNaN(sliceResults[sr].intDenBinarizedRawInMask);
            areaFractionBinarized[sr] = finiteOrNaN(sliceResults[sr].areaFractionBinarized);
        }
        if (!compactLog) IJ.log("    - Measured signal + raw: " + sliceResults.length + " slices");

        // Store results in pre-allocated array slots (no synchronization needed — one slot per channel)
        outIntDenFilteredFullRoi[c] = intDenFilteredFullRoi;
        outAreaFractionFilteredFullRoi[c] = areaFractionFilteredFullRoi;
        outIntDenUnfilteredFullRoi[c] = intDenUnfilteredFullRoi;
        outIntDenBinarizedRawInMask[c] = intDenBinarizedRawInMask;
        outAreaFractionBinarized[c] = areaFractionBinarized;

        if (shouldRetainCrossChannelSpatialImages(outputPlan, spatialConfig, channelNames[c])) {
            outRawSpatialImages[c] = ImageOps.duplicateThreadSafe(raw);
            if (binarizedRawInMask != null) {
                outBinarizedSpatialImages[c] = ImageOps.duplicateThreadSafe(binarizedRawInMask);
            }
            if (binarization[c]) {
                outBinarySpatialMasks[c] = ImageOps.duplicateThreadSafe(binary);
            }
        }

        boolean hasSameChannelSpatial = hasSelectedSameChannelAnalysis(spatialConfig);
        if (hasSameChannelSpatial) {
            logProgressStep(imageStepContext, channelProgress
                    + " starting same-channel intensity-spatial");
        }
        ChannelSpatialResults spatialResults = measureChannelSpatial(
                raw, binarizedRawInMask, channelNames[c], roi, roiLabel,
                parts, imageProgressLabel, imageStepContext, outputPlan, spatialConfig);
        outBaseSpatialResults[c] = spatialResults.baseResults;
        outMipSpatialResults[c] = spatialResults.mipResult;
        outNativeSpatialResults[c] = spatialResults.nativeResult;
        if (hasSameChannelSpatial) {
            logProgressStep(imageStepContext, channelProgress
                    + " same-channel intensity-spatial complete");
        }

        if (verbose) {
            IJ.log("    [DEBUG] Channel " + channelNames[c] + " processing time: "
                    + (System.currentTimeMillis() - chStart) + " ms");
            IJ.log("    [DEBUG] Binarization applied: " + binarization[c]
                    + (binarization[c] ? " (threshold=" + thresholds[c] + ")" : ""));
            IJ.log("    [DEBUG] ROI mask applied: " + (roi != null));
        }

        if (logChannelProgress) {
            logProgressStep(imageStepContext, channelProgress
                    + " complete (" + sliceResults.length + " " + plural(sliceResults.length, "slice") + ")");
        }

        } finally {
            closeImage(binary);
            closeImage(raw);
            closeImage(filteredMeasurement);
            closeImage(binarizedRawInMask);
        }
    }

    private void measureCrossChannelSpatial(String[] channelNames,
                                            int channelCount,
                                            Roi roi,
                                            String roiLabel,
                                            NameParts parts,
                                            String imageProgressLabel,
                                            String imageStepContext,
                                            IntensityOutputPlan outputPlan,
                                            IntensitySpatialConfig spatialConfig,
                                            ImagePlus[] rawImages,
                                            ImagePlus[] binarizedImages,
                                            ImagePlus[] binaryMasks,
                                            IntensitySpatialResult[][] baseResults,
                                            IntensitySpatialResult[] mipResults,
                                            IntensitySpatialResult[] nativeResults) {
        boolean hasBase2d = hasSelected2dCrossChannelAnalysis(spatialConfig,
                IntensitySpatialOutputMode.BASE);
        boolean hasMip2d = hasSelected2dCrossChannelAnalysis(spatialConfig,
                IntensitySpatialOutputMode.MIP);
        boolean hasNative3d = hasSelectedNative3dCrossChannelAnalysis(spatialConfig);
        if ((!hasBase2d && !hasMip2d && !hasNative3d)
                || channelNames == null || channelCount < 2
                || rawImages == null) {
            return;
        }

        IntensitySpatialRunner runner = IntensitySpatialRunner.standardWithProgress();
        String imageId = imageIdWithProgress(parts, imageProgressLabel);
        logProgressStep(imageStepContext, "intensity-spatial cross-channel: "
                + crossChannelPlanSummary(spatialConfig));
        CrossChannelMipCache mipCache = null;
        try {
        for (int c = 0; c < channelCount; c++) {
            ImagePlus sourceRaw = rawImages[c];
            if (sourceRaw == null) continue;
            String sourceName = channelNames[c];

            IntensitySpatialOutputKey baseKey = outputPlan.baseKeyForChannel(sourceName);
            boolean baseAllowed = outputPlan.shouldPopulate(baseKey)
                    && baseKey != null
                    && !baseKey.isChannelRoiMaskOutput()
                    && sourceAllowsSpatialMode(spatialConfig, IntensitySpatialOutputMode.BASE);
            if (baseAllowed && hasBase2d) {
                int slices = Math.max(1, sourceRaw.getStackSize());
                baseResults[c] = ensureResultArray(baseResults[c], slices);
                for (int p = 0; p < channelCount; p++) {
                    if (p == c || rawImages[p] == null) continue;
                    int pairSlices = Math.min(slices, Math.max(1, rawImages[p].getStackSize()));
                    String pairProgress = "pair " + orderedPairProgress(c, p, channelCount)
                            + " " + sourceName + " -> " + channelNames[p];
                    logProgressStep(imageStepContext, pairProgress
                            + " base: " + pairSlices + " " + plural(pairSlices, "slice"));
                    for (int s = 1; s <= pairSlices; s++) {
                        logProgressStep(imageStepContext, pairProgress
                                + " base slice " + s + "/" + pairSlices);
                        IntensitySpatialResult result = runner.measurePair(new IntensitySpatialPairContext(
                                spatialConfig,
                                sourceRaw, imageAt(binarizedImages, c), imageAt(binaryMasks, c),
                                rawImages[p], imageAt(binarizedImages, p), imageAt(binaryMasks, p),
                                s, roi, IntensitySpatialOutputMode.BASE, imageId,
                                sourceName, channelNames[p], roiLabel, null));
                        baseResults[c][s - 1] = mergeSpatialResults(baseResults[c][s - 1], result);
                    }
                }
            }

            IntensitySpatialOutputKey mipKey = outputPlan.keyForChannelMode(sourceName,
                    IntensitySpatialOutputMode.MIP);
            if (hasMip2d && outputPlan.shouldPopulate(mipKey)) {
                try {
                    if (mipCache == null) {
                        logProgressStep(imageStepContext,
                                "MIP: building cross-channel projections once per channel");
                        mipCache = CrossChannelMipCache.build(
                                channelNames, channelCount, rawImages, binarizedImages, binaryMasks);
                    }
                    ImagePlus sourceMip = mipCache.raw(c);
                    ImagePlus sourceBinMip = mipCache.binarized(c);
                    ImagePlus sourceMaskMip = mipCache.mask(c);
                    if (sourceMip == null) continue;
                    for (int p = 0; p < channelCount; p++) {
                        if (p == c || rawImages[p] == null) continue;
                        String pairProgress = "pair " + orderedPairProgress(c, p, channelCount)
                                + " " + sourceName + " MIP -> " + channelNames[p] + " MIP";
                        logProgressStep(imageStepContext, pairProgress);
                        try {
                            ImagePlus partnerMip = mipCache.raw(p);
                            ImagePlus partnerBinMip = mipCache.binarized(p);
                            ImagePlus partnerMaskMip = mipCache.mask(p);
                            IntensitySpatialResult result = runner.measurePair(new IntensitySpatialPairContext(
                                    spatialConfig,
                                    sourceMip, sourceBinMip, sourceMaskMip,
                                    partnerMip, partnerBinMip, partnerMaskMip,
                                    1, roi, IntensitySpatialOutputMode.MIP, imageId,
                                    sourceName, channelNames[p], roiLabel, null));
                            mipResults[c] = mergeSpatialResults(mipResults[c], result);
                        } catch (RuntimeException ex) {
                            IJ.log("[FLASH] Intensity-spatial cross-channel MIP skipped for "
                                    + imageId + " source " + sourceName
                                    + " partner " + channelNames[p] + ": " + ex.getMessage());
                        }
                    }
                } catch (RuntimeException ex) {
                    IJ.log("[FLASH] Intensity-spatial cross-channel MIP skipped for "
                            + imageId + " source " + sourceName
                            + ": " + ex.getMessage());
                }
            }

            IntensitySpatialOutputKey nativeKey = outputPlan.keyForChannelMode(sourceName,
                    IntensitySpatialOutputMode.NATIVE_3D);
            if (hasNative3d && outputPlan.shouldPopulate(nativeKey)) {
                int sourceDepth = Math.max(1, sourceRaw.getStackSize());
                if (sourceDepth < IntensitySpatialConfig.MIN_NATIVE_3D_SLICES) {
                    IJ.log("[FLASH] Intensity-spatial native 3D skipped for "
                            + imageId + " source " + sourceName
                            + ": stack has fewer than "
                            + IntensitySpatialConfig.MIN_NATIVE_3D_SLICES + " slices");
                    continue;
                }
                for (int p = 0; p < channelCount; p++) {
                    if (p == c || rawImages[p] == null) continue;
                    int pairDepth = Math.min(sourceDepth, Math.max(1, rawImages[p].getStackSize()));
                    String pairProgress = "pair " + orderedPairProgress(c, p, channelCount)
                            + " " + sourceName + " -> " + channelNames[p];
                    logProgressStep(imageStepContext, pairProgress
                            + " native 3D: " + pairDepth + " " + plural(pairDepth, "slice"));
                    if (pairDepth < IntensitySpatialConfig.MIN_NATIVE_3D_SLICES) {
                        IJ.log("[FLASH] Intensity-spatial native 3D skipped for "
                                + imageId + " source " + sourceName
                                + " partner " + channelNames[p]
                                + ": stack has fewer than "
                                + IntensitySpatialConfig.MIN_NATIVE_3D_SLICES + " slices");
                        continue;
                    }
                    showProgressStep(imageStepContext, pairProgress + " native 3D");
                    IntensitySpatialResult result = runner.measurePair(new IntensitySpatialPairContext(
                            spatialConfig,
                            sourceRaw, imageAt(binarizedImages, c), imageAt(binaryMasks, c),
                            rawImages[p], imageAt(binarizedImages, p), imageAt(binaryMasks, p),
                            1, roi, IntensitySpatialOutputMode.NATIVE_3D, imageId,
                            sourceName, channelNames[p], roiLabel, null));
                    nativeResults[c] = mergeSpatialResults(nativeResults[c], result);
                }
            }
        }
        } finally {
            if (mipCache != null) {
                mipCache.close();
            }
        }
    }

    private static boolean shouldRetainCrossChannelSpatialImages(IntensityOutputPlan outputPlan,
                                                                  IntensitySpatialConfig spatialConfig,
                                                                  String channelName) {
        boolean needsNative3d = hasSelectedNative3dCrossChannelAnalysis(spatialConfig);
        if (outputPlan == null
                || (!hasSelected2dCrossChannelAnalysis(spatialConfig, IntensitySpatialOutputMode.BASE)
                && !hasSelected2dCrossChannelAnalysis(spatialConfig, IntensitySpatialOutputMode.MIP)
                && !needsNative3d)) {
            return false;
        }
        IntensitySpatialOutputKey baseKey = outputPlan.baseKeyForChannel(channelName);
        if (baseKey == null || baseKey.isChannelRoiMaskOutput()) {
            return false;
        }
        for (IntensitySpatialOutputKey selected : outputPlan.selectedKeys()) {
            if (selected == null || selected.isChannelRoiMaskOutput()) continue;
            if ((selected.mode() == IntensitySpatialOutputMode.BASE
                    || selected.mode() == IntensitySpatialOutputMode.MIP)
                    && containsCrossChannel2d(spatialConfig.enabledFor(selected.mode()))
                    && outputPlan.shouldPopulate(selected)) {
                return true;
            }
            if (selected.mode() == IntensitySpatialOutputMode.NATIVE_3D
                    && containsCrossChannelNative3d(spatialConfig.enabledFor(selected.mode()))
                    && outputPlan.shouldPopulate(selected)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSpatialEnabled(IntensitySpatialConfig spatialConfig) {
        return spatialConfig != null
                && spatialConfig.isEnabled()
                && spatialConfig.getEnabledAnalyses() != null
                && !spatialConfig.getEnabledAnalyses().isEmpty();
    }

    private static boolean hasSelected2dCrossChannelAnalysis(IntensitySpatialConfig spatialConfig,
                                                             IntensitySpatialOutputMode mode) {
        if (spatialConfig == null || !spatialConfig.isEnabled()) return false;
        return containsCrossChannel2d(spatialConfig.enabledFor(mode));
    }

    private static boolean hasSelectedNative3dCrossChannelAnalysis(IntensitySpatialConfig spatialConfig) {
        if (spatialConfig == null || !spatialConfig.isEnabled()) return false;
        return containsCrossChannelNative3d(spatialConfig.enabledFor(IntensitySpatialOutputMode.NATIVE_3D));
    }

    /** Same-channel (non-cross, non-native) 2D analyses present in a single mode's selection. */
    private static boolean containsSameChannel2d(Set<IntensitySpatialConfig.AnalysisKey> analyses) {
        if (analyses == null) return false;
        for (IntensitySpatialConfig.AnalysisKey key : analyses) {
            if (key != null && !key.isCrossChannel() && !key.isNative3d()) return true;
        }
        return false;
    }

    /** Cross-channel 2D analyses present in a single mode's selection. */
    private static boolean containsCrossChannel2d(Set<IntensitySpatialConfig.AnalysisKey> analyses) {
        return analyses != null
                && (analyses.contains(IntensitySpatialConfig.AnalysisKey.CROSSCORR_FAST)
                || analyses.contains(IntensitySpatialConfig.AnalysisKey.CROSSMARK)
                || analyses.contains(IntensitySpatialConfig.AnalysisKey.ENTROPY_MI)
                || analyses.contains(IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL));
    }

    private static boolean containsSameChannelNative3d(Set<IntensitySpatialConfig.AnalysisKey> analyses) {
        if (analyses == null) return false;
        for (IntensitySpatialConfig.AnalysisKey key : analyses) {
            if (key != null && !key.isCrossChannel() && key.isNative3d()) return true;
        }
        return false;
    }

    private static boolean containsCrossChannelNative3d(Set<IntensitySpatialConfig.AnalysisKey> analyses) {
        return analyses != null
                && (analyses.contains(IntensitySpatialConfig.AnalysisKey.CROSSMARK_3D)
                || analyses.contains(IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL_3D));
    }

    private static boolean hasSelectedSameChannelAnalysis(IntensitySpatialConfig spatialConfig) {
        if (spatialConfig == null || !spatialConfig.isEnabled()) return false;
        return containsSameChannel2d(spatialConfig.enabledFor(IntensitySpatialOutputMode.BASE))
                || containsSameChannel2d(spatialConfig.enabledFor(IntensitySpatialOutputMode.MIP))
                || containsSameChannelNative3d(spatialConfig.enabledFor(IntensitySpatialOutputMode.NATIVE_3D));
    }

    private static String sameChannelPlanSummary(IntensitySpatialConfig spatialConfig,
                                                 boolean includeBase,
                                                 boolean includeMip,
                                                 boolean includeNative3d) {
        List<String> parts = new ArrayList<String>();
        if (includeBase) {
            String tokens = analysisTokens(spatialConfig, IntensitySpatialOutputMode.BASE, false, false);
            if (!tokens.isEmpty()) parts.add("per-slice " + tokens);
        }
        if (includeMip) {
            String tokens = analysisTokens(spatialConfig, IntensitySpatialOutputMode.MIP, false, false);
            if (!tokens.isEmpty()) parts.add("MIP " + tokens);
        }
        if (includeNative3d) {
            String tokens = analysisTokens(spatialConfig, IntensitySpatialOutputMode.NATIVE_3D, false, true);
            if (!tokens.isEmpty()) parts.add("native 3D " + tokens);
        }
        return parts.isEmpty() ? "no same-channel spatial families selected" : String.join("; ", parts);
    }

    private static String crossChannelPlanSummary(IntensitySpatialConfig spatialConfig) {
        List<String> parts = new ArrayList<String>();
        String base = analysisTokens(spatialConfig, IntensitySpatialOutputMode.BASE, true, false);
        if (!base.isEmpty()) parts.add("per-slice " + base);
        String mip = analysisTokens(spatialConfig, IntensitySpatialOutputMode.MIP, true, false);
        if (!mip.isEmpty()) parts.add("MIP " + mip);
        String native3d = analysisTokens(spatialConfig, IntensitySpatialOutputMode.NATIVE_3D, true, true);
        if (!native3d.isEmpty()) parts.add("native 3D " + native3d);
        return parts.isEmpty() ? "no cross-channel spatial families selected" : String.join("; ", parts);
    }

    private static String analysisTokens(IntensitySpatialConfig spatialConfig,
                                         IntensitySpatialOutputMode mode,
                                         boolean crossChannel,
                                         boolean native3d) {
        if (spatialConfig == null || !spatialConfig.isEnabled() || mode == null) {
            return "";
        }
        Set<IntensitySpatialConfig.AnalysisKey> analyses = spatialConfig.enabledFor(mode);
        if (analyses == null) return "";
        List<String> tokens = new ArrayList<String>();
        for (IntensitySpatialConfig.AnalysisKey key : analyses) {
            if (key != null
                    && key.isCrossChannel() == crossChannel
                    && key.isNative3d() == native3d) {
                tokens.add(key.token());
            }
        }
        return String.join(", ", tokens);
    }

    private static String roiLogSuffix(String roiLabel) {
        return roiLabel == null || roiLabel.trim().isEmpty()
                ? ""
                : " ROI " + roiLabel.trim();
    }

    private static String imageProgressLabel(int imageIndex1Based, int totalImages) {
        int safeIndex = Math.max(1, imageIndex1Based);
        if (totalImages > 0) {
            return "image " + safeIndex + "/" + Math.max(safeIndex, totalImages);
        }
        return "image " + safeIndex;
    }

    private static String imageIdWithProgress(NameParts parts, String imageProgressLabel) {
        String imageId = parts == null ? "unknown" : parts.displayLabel();
        String progress = imageProgressLabel == null || imageProgressLabel.trim().isEmpty()
                ? "image"
                : imageProgressLabel.trim();
        return progress + " " + imageId;
    }

    private static void logProgressStep(String imageStepContext, String step) {
        IJ.log("    - " + imageStepContext + ": " + step);
        showProgressStep(imageStepContext, step);
    }

    private static void showProgressStep(String imageStepContext, String step) {
        IJ.showStatus("Intensity " + imageStepContext + ": " + step);
    }

    private static String plural(int count, String singular) {
        return count == 1 ? singular : singular + "s";
    }

    private static String orderedPairProgress(int sourceIndex,
                                              int partnerIndex,
                                              int channelCount) {
        int safeChannelCount = Math.max(0, channelCount);
        int total = safeChannelCount * Math.max(0, safeChannelCount - 1);
        if (sourceIndex < 0 || partnerIndex < 0 || sourceIndex == partnerIndex
                || safeChannelCount < 2) {
            return "[?/" + Math.max(0, total) + "]";
        }
        int pairNumber = sourceIndex * (safeChannelCount - 1)
                + (partnerIndex < sourceIndex ? partnerIndex + 1 : partnerIndex);
        return "[" + pairNumber + "/" + total + "]";
    }

    private static IntensitySpatialResult[] ensureResultArray(IntensitySpatialResult[] results,
                                                              int length) {
        if (results == null) return new IntensitySpatialResult[length];
        if (results.length >= length) return results;
        return Arrays.copyOf(results, length);
    }

    private static IntensitySpatialResult mergeSpatialResults(IntensitySpatialResult existing,
                                                              IntensitySpatialResult addition) {
        if (addition == null || addition.isEmpty()) return existing;
        return existing == null ? addition : existing.plus(addition);
    }

    private static ImagePlus imageAt(ImagePlus[] images, int index) {
        return images == null || index < 0 || index >= images.length ? null : images[index];
    }

    private static final class CrossChannelMipCache {
        private final ImagePlus[] raw;
        private final ImagePlus[] binarized;
        private final ImagePlus[] mask;

        private CrossChannelMipCache(int channelCount) {
            int size = Math.max(0, channelCount);
            this.raw = new ImagePlus[size];
            this.binarized = new ImagePlus[size];
            this.mask = new ImagePlus[size];
        }

        static CrossChannelMipCache build(String[] channelNames,
                                          int channelCount,
                                          ImagePlus[] rawImages,
                                          ImagePlus[] binarizedImages,
                                          ImagePlus[] binaryMasks) {
            CrossChannelMipCache cache = new CrossChannelMipCache(channelCount);
            try {
                for (int i = 0; i < channelCount; i++) {
                    ImagePlus rawImage = imageAt(rawImages, i);
                    if (rawImage == null) continue;
                    String channelName = channelNameAt(channelNames, i);
                    cache.raw[i] = IntensitySpatialRunner.maxIntensityProjection(
                            rawImage, channelName + "_raw_MIP");
                    cache.binarized[i] = IntensitySpatialRunner.maxIntensityProjection(
                            imageAt(binarizedImages, i), channelName + "_binarized_MIP");
                    cache.mask[i] = IntensitySpatialRunner.maxIntensityProjection(
                            imageAt(binaryMasks, i), channelName + "_mask_MIP");
                }
                return cache;
            } catch (RuntimeException ex) {
                cache.close();
                throw ex;
            }
        }

        ImagePlus raw(int index) {
            return imageAt(raw, index);
        }

        ImagePlus binarized(int index) {
            return imageAt(binarized, index);
        }

        ImagePlus mask(int index) {
            return imageAt(mask, index);
        }

        void close() {
            closeImages(raw);
            closeImages(binarized);
            closeImages(mask);
        }

        private static String channelNameAt(String[] channelNames, int index) {
            if (channelNames == null || index < 0 || index >= channelNames.length
                    || channelNames[index] == null || channelNames[index].trim().isEmpty()) {
                return "channel" + (index + 1);
            }
            return channelNames[index];
        }
    }

    private ChannelSpatialResults measureChannelSpatial(ImagePlus raw,
                                                        ImagePlus binarizedRawInMask,
                                                        String channelName,
                                                        Roi roi,
                                                        String roiLabel,
                                                        NameParts parts,
                                                        String imageProgressLabel,
                                                        String imageStepContext,
                                                        IntensityOutputPlan outputPlan,
                                                        IntensitySpatialConfig spatialConfig) {
        if (raw == null || spatialConfig == null || !spatialConfig.isEnabled()
                || spatialConfig.getEnabledAnalyses().isEmpty()) {
            return ChannelSpatialResults.empty();
        }

        boolean hasBaseSame2d = containsSameChannel2d(
                spatialConfig.enabledFor(IntensitySpatialOutputMode.BASE));
        boolean hasMipSame2d = containsSameChannel2d(
                spatialConfig.enabledFor(IntensitySpatialOutputMode.MIP));
        boolean hasSameNative3d = containsSameChannelNative3d(
                spatialConfig.enabledFor(IntensitySpatialOutputMode.NATIVE_3D));
        if (!hasBaseSame2d && !hasMipSame2d && !hasSameNative3d) {
            return ChannelSpatialResults.empty();
        }

        try {
            IntensitySpatialRunner runner = IntensitySpatialRunner.standardWithProgress();
            String imageId = imageIdWithProgress(parts, imageProgressLabel);
            IntensitySpatialResult[] baseResults = null;
            IntensitySpatialResult mipResult = null;
            IntensitySpatialResult nativeResult = null;

            IntensitySpatialOutputKey baseKey = outputPlan.baseKeyForChannel(channelName);
            boolean baseAllowed = outputPlan.shouldPopulate(baseKey)
                    && baseKey != null
                    && !baseKey.isChannelRoiMaskOutput()
                    && sourceAllowsSpatialMode(spatialConfig, IntensitySpatialOutputMode.BASE);
            IntensitySpatialOutputKey mipKey = outputPlan.keyForChannelMode(channelName,
                    IntensitySpatialOutputMode.MIP);
            boolean mipAllowed = outputPlan.shouldPopulate(mipKey)
                    && sourceAllowsSpatialMode(spatialConfig, IntensitySpatialOutputMode.MIP);
            IntensitySpatialOutputKey nativeKey = outputPlan.keyForChannelMode(channelName,
                    IntensitySpatialOutputMode.NATIVE_3D);
            boolean nativeAllowed = outputPlan.shouldPopulate(nativeKey);

            logProgressStep(imageStepContext, "intensity-spatial same-channel [" + channelName + "]: "
                    + sameChannelPlanSummary(spatialConfig, hasBaseSame2d && baseAllowed,
                    hasMipSame2d && mipAllowed, hasSameNative3d && nativeAllowed));

            if (baseAllowed && hasBaseSame2d) {
                int slices = Math.max(1, raw.getStackSize());
                baseResults = new IntensitySpatialResult[slices];
                logProgressStep(imageStepContext, "same-channel " + channelName
                        + " base output: " + slices + " " + plural(slices, "slice"));
                for (int s = 1; s <= slices; s++) {
                    logProgressStep(imageStepContext, "same-channel " + channelName
                            + " base slice " + s + "/" + slices);
                    baseResults[s - 1] = runner.measure(new IntensitySpatialContext(
                            spatialConfig, raw, binarizedRawInMask, s, roi,
                            IntensitySpatialOutputMode.BASE, imageId, channelName, roiLabel, null));
                }
            }

            if (mipAllowed && hasMipSame2d) {
                ImagePlus rawMip = null;
                ImagePlus binarizedMip = null;
                try {
                    logProgressStep(imageStepContext, "same-channel " + channelName
                            + " MIP: building max-intensity projection");
                    rawMip = IntensitySpatialRunner.maxIntensityProjection(raw,
                            channelName + "_raw_MIP");
                    if (binarizedRawInMask != null) {
                        binarizedMip = IntensitySpatialRunner.maxIntensityProjection(
                                binarizedRawInMask, channelName + "_binarized_MIP");
                    }
                    mipResult = runner.measure(new IntensitySpatialContext(
                            spatialConfig, rawMip, binarizedMip, 1, roi,
                            IntensitySpatialOutputMode.MIP, imageId, channelName, roiLabel, null));
                } catch (RuntimeException ex) {
                    IJ.log("[FLASH] Intensity-spatial MIP skipped for " + imageId
                            + " channel " + channelName + ": " + ex.getMessage());
                } finally {
                    closeImage(rawMip);
                    closeImage(binarizedMip);
                }
            }

            if (nativeAllowed && hasSameNative3d) {
                int stackDepth = Math.max(1, raw.getStackSize());
                logProgressStep(imageStepContext, "same-channel " + channelName
                        + " native 3D output: " + stackDepth + " " + plural(stackDepth, "slice"));
                if (stackDepth < IntensitySpatialConfig.MIN_NATIVE_3D_SLICES) {
                    IJ.log("[FLASH] Intensity-spatial native 3D skipped for " + imageId
                            + " channel " + channelName + ": stack has fewer than "
                            + IntensitySpatialConfig.MIN_NATIVE_3D_SLICES + " slices");
                } else {
                    showProgressStep(imageStepContext, "same-channel " + channelName + " native 3D");
                    nativeResult = runner.measure(new IntensitySpatialContext(
                            spatialConfig, raw, binarizedRawInMask, 1, roi,
                            IntensitySpatialOutputMode.NATIVE_3D, imageId, channelName, roiLabel, null));
                }
            }

            return new ChannelSpatialResults(baseResults, mipResult, nativeResult);
        } catch (Throwable t) {
            rethrowIfFatal(t);
            String imageId = parts == null ? "unknown" : parts.displayLabel();
            IJ.log("[FLASH] Intensity-spatial same-channel skipped for "
                    + imageId + " channel " + channelName + roiLogSuffix(roiLabel)
                    + ": runtime class/dependency problem: " + safeThrowableMessage(t));
            return failedChannelSpatialResults(raw, channelName, outputPlan, spatialConfig);
        }
    }

    private static ChannelSpatialResults failedChannelSpatialResults(
            ImagePlus raw,
            String channelName,
            IntensityOutputPlan outputPlan,
            IntensitySpatialConfig spatialConfig) {
        if (outputPlan == null || spatialConfig == null || !spatialConfig.isEnabled()) {
            return ChannelSpatialResults.empty();
        }
        IntensitySpatialResult empty = IntensitySpatialResult.empty();
        IntensitySpatialResult[] baseResults = null;
        IntensitySpatialOutputKey baseKey = outputPlan.baseKeyForChannel(channelName);
        if (outputPlan.shouldPopulate(baseKey)
                && baseKey != null
                && !baseKey.isChannelRoiMaskOutput()
                && containsSameChannel2d(spatialConfig.enabledFor(IntensitySpatialOutputMode.BASE))) {
            int slices = Math.max(1, raw == null ? 1 : raw.getStackSize());
            baseResults = new IntensitySpatialResult[slices];
            Arrays.fill(baseResults, empty);
        }

        IntensitySpatialOutputKey mipKey = outputPlan.keyForChannelMode(
                channelName, IntensitySpatialOutputMode.MIP);
        IntensitySpatialResult mipResult = outputPlan.shouldPopulate(mipKey)
                && containsSameChannel2d(spatialConfig.enabledFor(IntensitySpatialOutputMode.MIP))
                ? empty
                : null;

        IntensitySpatialOutputKey nativeKey = outputPlan.keyForChannelMode(
                channelName, IntensitySpatialOutputMode.NATIVE_3D);
        IntensitySpatialResult nativeResult = outputPlan.shouldPopulate(nativeKey)
                && containsSameChannelNative3d(spatialConfig.enabledFor(IntensitySpatialOutputMode.NATIVE_3D))
                ? empty
                : null;

        return new ChannelSpatialResults(baseResults, mipResult, nativeResult);
    }

    private static String safeThrowableMessage(Throwable t) {
        if (t == null) return "unknown";
        String message = t.getMessage();
        return message == null || message.trim().isEmpty()
                ? t.getClass().getSimpleName()
                : message.trim();
    }

    private static void rethrowIfFatal(Throwable t) {
        if (t instanceof ThreadDeath) {
            throw (ThreadDeath) t;
        }
    }

    private static String validateIntensityConfiguration(boolean[] binarization,
                                                        String[] thresholds,
                                                        String[] channelNames,
                                                        boolean roiAnalysis,
                                                        int roiChannelIndex1Based) {
        if (binarization == null || channelNames == null) {
            return "Intensity configuration is incomplete.";
        }
        for (int c = 0; c < channelNames.length && c < binarization.length; c++) {
            if (binarization[c]) {
                try {
                    if (!isAlgorithmicIntensityThreshold(thresholdToken(thresholds, c))) {
                        parseIntensityThreshold(thresholds, c, channelNames, null, null);
                    }
                } catch (NumberFormatException e) {
                    return e.getMessage();
                }
            }
        }
        if (roiAnalysis && roiChannelIndex1Based > 0) {
            int index = roiChannelIndex1Based - 1;
            if (index < 0 || index >= channelNames.length) {
                return "Channel ROI mask index " + roiChannelIndex1Based
                        + " is outside the configured channel list.";
            }
            if (index >= binarization.length || !binarization[index]) {
                return "Channel ROI mask is set to '" + channelNames[index]
                        + "', but binarisation is not enabled for that channel.";
            }
            try {
                if (!isAlgorithmicIntensityThreshold(thresholdToken(thresholds, index))) {
                    parseIntensityThreshold(thresholds, index, channelNames, null, null);
                }
            } catch (NumberFormatException e) {
                return e.getMessage();
            }
        }
        return null;
    }

    private static double parseIntensityThreshold(String[] thresholds,
                                                  int channelIndex,
                                                  String[] channelNames,
                                                  NameParts parts,
                                                  String roiLabel) {
        String channelName = channelIndex >= 0
                && channelNames != null
                && channelIndex < channelNames.length
                ? channelNames[channelIndex]
                : "channel " + (channelIndex + 1);
        String raw = channelIndex >= 0
                && thresholds != null
                && channelIndex < thresholds.length
                ? thresholds[channelIndex]
                : null;
        try {
            double parsed = Double.parseDouble(raw == null ? "" : raw.trim());
            if (Double.isNaN(parsed) || Double.isInfinite(parsed) || parsed < 0.0) {
                throw new NumberFormatException("not finite and non-negative");
            }
            return parsed;
        } catch (NumberFormatException e) {
            NumberFormatException wrapped = invalidIntensityThreshold(
                    raw, channelIndex, channelNames, parts, roiLabel);
            wrapped.initCause(e);
            throw wrapped;
        }
    }

    private static NumberFormatException invalidIntensityThreshold(String raw,
                                                                   int channelIndex,
                                                                   String[] channelNames,
                                                                   NameParts parts,
                                                                   String roiLabel) {
        String channelName = channelIndex >= 0
                && channelNames != null
                && channelIndex < channelNames.length
                ? channelNames[channelIndex]
                : "channel " + (channelIndex + 1);
        return new NumberFormatException(
                "Invalid intensity threshold for " + thresholdContext(channelName, parts, roiLabel)
                        + ": '" + (raw == null ? "" : raw)
                        + "' (use a finite number >= 0 or auto:Method:dark).");
    }

    private static String thresholdToken(String[] thresholds, int channelIndex) {
        return channelIndex >= 0
                && thresholds != null
                && channelIndex < thresholds.length
                ? thresholds[channelIndex]
                : null;
    }

    private static boolean isAlgorithmicIntensityThreshold(String token) {
        return ThresholdOps.autoThresholdSpecForToken(token, false) != null;
    }

    private static String thresholdContext(String channelName, NameParts parts, String roiLabel) {
        StringBuilder sb = new StringBuilder();
        sb.append("channel '").append(channelName == null ? "" : channelName).append("'");
        if (parts != null) {
            sb.append(" on image '").append(parts.displayLabel()).append("'");
        }
        if (roiLabel != null && !roiLabel.trim().isEmpty()) {
            sb.append(" ROI '").append(roiLabel.trim()).append("'");
        }
        return sb.toString();
    }

    private static double finiteOrNaN(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? Double.NaN : value;
    }

    /**
     * Build the ROI-channel mask using the chosen channel's resolved filter and
     * the explicit threshold value the user picked for that channel. The bin
     * filter takes a macro file; the basic filter takes the macro source text.
     */
    static ImagePlus createRoiChannelMask(ImagePlus source,
                                          String filterMacroOrPath,
                                          boolean isMacroFile,
                                          double threshold,
                                          boolean log) {
        return createRoiChannelMask(source, filterMacroOrPath, isMacroFile, threshold, log, null);
    }

    static ImagePlus createRoiChannelMask(ImagePlus source,
                                          String filterMacroOrPath,
                                          boolean isMacroFile,
                                          double threshold,
                                          boolean log,
                                          String filterLabel) {
        return createRoiChannelMask(source, filterMacroOrPath, isMacroFile,
                String.valueOf(threshold), log, filterLabel);
    }

    static ImagePlus createRoiChannelMask(ImagePlus source,
                                          String filterMacroOrPath,
                                          boolean isMacroFile,
                                          String thresholdToken,
                                          boolean log,
                                          String filterLabel) {
        if (source == null) return null;
        ImagePlus roiCh = ImageOps.duplicateThreadSafe(source);
        roiCh.setTitle("Mask");
        if (isMacroFile) {
            File macroFile = new File(filterMacroOrPath);
            if (macroFile.exists()) {
                FilterExecutor.runIjmFileThreadSafe(roiCh, macroFile);
                String label = filterLabel == null || filterLabel.trim().isEmpty()
                        ? macroFile.getName()
                        : filterLabel;
                if (log) IJ.log("    - ROI mask: filter applied (" + label + ")");
            } else {
                if (log) IJ.log("    - ROI mask: WARN saved filter file missing (" + macroFile.getName() + ")");
            }
        } else if (filterMacroOrPath != null && !filterMacroOrPath.trim().isEmpty()) {
            FilterExecutor.runThreadSafe(roiCh, filterMacroOrPath);
            if (log) IJ.log("    - ROI mask: basic background and noise removal applied");
        }

        int foregroundPixels = 0;
        if (isAlgorithmicIntensityThreshold(thresholdToken)) {
            if (!ThresholdOps.applyStackThresholdInPlace(roiCh, thresholdToken, false)) {
                throw new NumberFormatException("ROI channel mask threshold could not be resolved: "
                        + (thresholdToken == null ? "" : thresholdToken));
            }
            foregroundPixels = countForegroundPixels(roiCh);
        } else {
            double threshold = parseFiniteNonNegativeThreshold(thresholdToken,
                    "ROI channel mask threshold");
            ij.ImageStack roiStack = roiCh.getStack();
            for (int s = 1; s <= roiStack.getSize(); s++) {
                ij.process.ImageProcessor ip = roiStack.getProcessor(s);
                for (int p = 0; p < ip.getWidth() * ip.getHeight(); p++) {
                    boolean foreground = ip.getf(p) >= threshold;
                    if (foreground) foregroundPixels++;
                    ip.set(p, foreground ? 255 : 0);
                }
            }
        }
        if (log) IJ.log("    - ROI mask: filter applied + threshold "
                + thresholdLogText(thresholdToken));
        if (log && foregroundPixels == 0) {
            IJ.log("    - ROI mask: WARNING threshold produced an all-zero mask.");
        }
        return roiCh;
    }

    private static double parseFiniteNonNegativeThreshold(String token, String label) {
        try {
            double threshold = Double.parseDouble(token == null ? "" : token.trim());
            if (Double.isNaN(threshold) || Double.isInfinite(threshold) || threshold < 0.0) {
                throw new NumberFormatException("not finite and non-negative");
            }
            return threshold;
        } catch (NumberFormatException e) {
            NumberFormatException wrapped = new NumberFormatException(
                    label + " must be a finite number >= 0 or auto:Method:dark: "
                            + (token == null ? "" : token));
            wrapped.initCause(e);
            throw wrapped;
        }
    }

    private static int countForegroundPixels(ImagePlus image) {
        if (image == null || image.getStack() == null) return 0;
        int count = 0;
        ij.ImageStack stack = image.getStack();
        for (int s = 1; s <= stack.getSize(); s++) {
            ij.process.ImageProcessor ip = stack.getProcessor(s);
            for (int p = 0; p < ip.getPixelCount(); p++) {
                if (ip.getf(p) > 0) count++;
            }
        }
        return count;
    }

    private static String thresholdLogText(String token) {
        return isAlgorithmicIntensityThreshold(token)
                ? ThresholdOps.describeToken(token)
                : String.valueOf((int) parseFiniteNonNegativeThreshold(token, "threshold"));
    }

    static ImagePlus applyRoiChannelMask(ImagePlus maskStack, ImagePlus measurement) {
        if (maskStack == null || measurement == null) return measurement;
        return ImageCalcOps.andStackThreadSafe(maskStack, measurement);
    }

    private static void closeImage(ImagePlus image) {
        if (image == null) return;
        image.changes = false;
        image.close();
        image.flush();
    }

    private static void closeImages(ImagePlus[] images) {
        if (images == null) return;
        for (int i = 0; i < images.length; i++) {
            closeImage(images[i]);
            images[i] = null;
        }
    }

    private static void writeMetadataColumns(ResultsTable table, int row,
                                             NameParts parts, String roiLabel) {
        if (table == null) return;
        String region = parts == null ? "" : parts.csvRegion();
        table.setValue("Region", row, region);
        table.setValue("Hemisphere", row, parts == null ? "" : parts.hemisphere);
        table.setValue("ROI", row, roiLabel == null ? "" : roiLabel);
        table.setValue("Animal Name", row, parts == null ? "" : parts.animal);
        AtlasRegionColumns.writeTo(table, row, region, roiLabel);
    }

    static void writeZColumn(ResultsTable table, int row, int zSlice) {
        if (table == null) return;
        table.setValue("z", row, zSlice);
    }

    static void writeZColumn(ResultsTable table, int row, String zLabel) {
        if (table == null) return;
        table.setValue("z", row, zLabel == null ? "" : zLabel);
    }

    private void appendSpatialModeRow(IntensityOutputPlan outputPlan,
                                      IntensityOutputTables totalTables,
                                      NameParts parts,
                                      String roiLabel,
                                      String channelName,
                                      IntensitySpatialOutputMode mode,
                                      String[] channelNames,
                                      boolean[] binarization,
                                      IntensitySpatialConfig spatialConfig,
                                      IntensitySpatialResult spatialResult) {
        IntensitySpatialOutputKey key = outputPlan.keyForChannelMode(channelName, mode);
        if (!outputPlan.shouldPopulate(key)) return;
        if (mode == IntensitySpatialOutputMode.NATIVE_3D && spatialResult == null) return;
        ResultsTable table = totalTables.table(key);
        table.incrementCounter();
        int row = table.size() - 1;
        writeMetadataColumns(table, row, parts, roiLabel);
        writeZColumn(table, row, zLabelForMode(mode));
        writeSpatialPlaceholderColumns(table, row, key, channelNames, binarization, spatialConfig);
        writeSpatialResultColumns(table, row, spatialResult);
    }

    private static String zLabelForMode(IntensitySpatialOutputMode mode) {
        if (mode == IntensitySpatialOutputMode.MIP) return "MIP";
        if (mode == IntensitySpatialOutputMode.NATIVE_3D) return "3D";
        return "";
    }

    private static void writeSpatialResultColumns(ResultsTable table,
                                                  int row,
                                                  IntensitySpatialResult[] results,
                                                  int resultIndex) {
        if (results == null || resultIndex < 0 || resultIndex >= results.length) return;
        writeSpatialResultColumns(table, row, results[resultIndex]);
    }

    private static void writeSpatialResultColumns(ResultsTable table,
                                                  int row,
                                                  IntensitySpatialResult result) {
        if (result != null) {
            result.writeTo(table, row);
        }
    }

    private static void writeSpatialPlaceholderColumns(ResultsTable table,
                                                       int row,
                                                       IntensitySpatialOutputKey key,
                                                       String[] channelNames,
                                                       boolean[] binarization,
                                                       IntensitySpatialConfig spatialConfig) {
        for (String column : spatialColumnsForKey(key, channelNames, spatialConfig, binarization)) {
            table.setValue(column, row, Double.NaN);
        }
    }

    static void writeMeasurementColumns(ResultsTable table,
                                        int row,
                                        double intDen,
                                        double areaFraction,
                                        double intDenUnfiltered,
                                        boolean binarized,
                                        double intDenBinarized,
                                        double areaFractionBinarized) {
        if (table == null) return;
        table.setValue("IntDen", row, intDen);
        if (binarized) {
            table.setValue("IntDen_binarized", row, intDenBinarized);
        }
        table.setValue("%Area", row, areaFraction);
        if (binarized) {
            table.setValue("%Area_binarized", row, areaFractionBinarized);
        }
        table.setValue("IntDen_Unfiltered", row, intDenUnfiltered);
    }

    static IntensityOutputPlan buildOutputPlan(File saveRoot,
                                               String[] channelNames,
                                               boolean roiAnalysis,
                                               int roiChannelIndex1Based,
                                               IntensitySpatialConfig spatialConfig,
                                               int stackDepth,
                                               boolean skipExisting) {
        List<IntensitySpatialOutputKey> selected = new ArrayList<IntensitySpatialOutputKey>();
        Map<IntensitySpatialOutputKey, File> files =
                new LinkedHashMap<IntensitySpatialOutputKey, File>();
        Map<String, IntensitySpatialOutputKey> baseByChannel =
                new HashMap<String, IntensitySpatialOutputKey>();
        Map<String, IntensitySpatialOutputKey> mipByChannel =
                new HashMap<String, IntensitySpatialOutputKey>();
        Map<String, IntensitySpatialOutputKey> nativeByChannel =
                new HashMap<String, IntensitySpatialOutputKey>();

        String roiMaskName = null;
        if (roiAnalysis && roiChannelIndex1Based > 0
                && channelNames != null
                && roiChannelIndex1Based <= channelNames.length) {
            roiMaskName = channelNames[roiChannelIndex1Based - 1];
        }

        for (String channelName : safeChannels(channelNames)) {
            IntensitySpatialOutputKey base = roiMaskName == null
                    ? IntensitySpatialOutputKey.base(channelName)
                    : IntensitySpatialOutputKey.roiMaskBase(channelName, roiMaskName);
            addOutputKey(selected, files, base, saveRoot);
            baseByChannel.put(channelName, base);
        }

        boolean allowSpatialOutputs = roiMaskName == null
                && spatialConfig != null
                && spatialConfig.isEnabled()
                && !spatialConfig.getEnabledAnalyses().isEmpty();
        if (allowSpatialOutputs && shouldSelectMipOutput(spatialConfig, stackDepth)) {
            for (String channelName : safeChannels(channelNames)) {
                IntensitySpatialOutputKey key = IntensitySpatialOutputKey.of(
                        channelName, IntensitySpatialOutputMode.MIP);
                addOutputKey(selected, files, key, saveRoot);
                mipByChannel.put(channelName, key);
            }
        }
        if (allowSpatialOutputs && shouldSelectNative3dOutput(spatialConfig, stackDepth)) {
            for (String channelName : safeChannels(channelNames)) {
                IntensitySpatialOutputKey key = IntensitySpatialOutputKey.of(
                        channelName, IntensitySpatialOutputMode.NATIVE_3D);
                addOutputKey(selected, files, key, saveRoot);
                nativeByChannel.put(channelName, key);
            }
        }

        Set<IntensitySpatialOutputKey> skipped =
                new HashSet<IntensitySpatialOutputKey>();
        if (skipExisting) {
            for (IntensitySpatialOutputKey key : selected) {
                File file = files.get(key);
                if (file != null && file.exists()) {
                    skipped.add(key);
                }
            }
        }

        return new IntensityOutputPlan(selected, files, skipped,
                baseByChannel, mipByChannel, nativeByChannel);
    }

    static List<String> buildOrderedIntensityColumns(IntensitySpatialOutputKey key,
                                                     ResultsTable table,
                                                     String[] channelNames,
                                                     IntensitySpatialConfig spatialConfig,
                                                     boolean[] binarization) {
        LinkedHashSet<String> existing = new LinkedHashSet<String>();
        if (table != null && table.getHeadings() != null) {
            for (String heading : table.getHeadings()) {
                if (heading != null && !heading.trim().isEmpty()) {
                    existing.add(heading);
                }
            }
        }

        LinkedHashSet<String> ordered = new LinkedHashSet<String>();
        ordered.add("Region");
        ordered.addAll(AtlasRegionColumns.COLUMNS);
        ordered.add("Hemisphere");
        ordered.add("ROI");
        ordered.add("Animal Name");
        ordered.add("z");

        if (key == null || key.mode() == IntensitySpatialOutputMode.BASE) {
            ordered.add("IntDen");
            if (existing.contains("IntDen_binarized") || isChannelBinarized(key, channelNames, binarization)) {
                ordered.add("IntDen_binarized");
            }
            ordered.add("%Area");
            if (existing.contains("%Area_binarized") || isChannelBinarized(key, channelNames, binarization)) {
                ordered.add("%Area_binarized");
            }
            ordered.add("IntDen_Unfiltered");
        }

        ordered.addAll(spatialColumnsForKey(key, channelNames, spatialConfig, binarization));
        ordered.addAll(existing);
        return new ArrayList<String>(ordered);
    }

    static List<String> spatialColumnsForKey(IntensitySpatialOutputKey key,
                                             String[] channelNames,
                                             IntensitySpatialConfig spatialConfig,
                                             boolean[] binarization) {
        LinkedHashSet<String> columns = new LinkedHashSet<String>();
        if (key == null || key.isChannelRoiMaskOutput()
                || spatialConfig == null || !spatialConfig.isEnabled()
                || !sourceAllowsSpatialMode(spatialConfig, key.mode())) {
            return new ArrayList<String>(columns);
        }
        Set<IntensitySpatialConfig.AnalysisKey> analyses = spatialConfig.enabledFor(key.mode());
        if (analyses == null || analyses.isEmpty()) {
            return new ArrayList<String>(columns);
        }

        for (IntensitySpatialConfig.AnalysisKey analysis : IntensitySpatialConfig.AnalysisKey.values()) {
            if (!analyses.contains(analysis) || analysis.isCrossChannel()
                    || !analysisBelongsInMode(analysis, key.mode())) {
                continue;
            }
            LinkedHashSet<String> analysisColumns = new LinkedHashSet<String>();
            addSameChannelColumns(analysisColumns, analysis, spatialConfig);
            columns.addAll(analysisColumns);
            if (isChannelBinarized(key, channelNames, binarization)
                    && emitsBinarizedSameChannelColumns(analysis)) {
                for (String column : analysisColumns) {
                    columns.add(column + "_binarized");
                }
            }
        }

        for (String partner : safeChannels(channelNames)) {
            if (partner.equals(key.channelName())) continue;
            addCrossChannelColumnsForPartner(columns, analyses, key, partner,
                    channelNames, spatialConfig, binarization);
        }
        return new ArrayList<String>(columns);
    }

    private static boolean shouldSelectMipOutput(IntensitySpatialConfig spatialConfig, int stackDepth) {
        return spatialConfig != null
                && spatialConfig.isMipEnabled()
                && stackDepth != 1
                && hasNonNativeSpatialAnalysis(spatialConfig.enabledFor(IntensitySpatialOutputMode.MIP));
    }

    private static boolean shouldSelectNative3dOutput(IntensitySpatialConfig spatialConfig, int stackDepth) {
        return spatialConfig != null
                && spatialConfig.isNative3dEnabled()
                && (stackDepth < 0 || stackDepth >= IntensitySpatialConfig.MIN_NATIVE_3D_SLICES)
                && hasNativeSpatialAnalysis(spatialConfig.enabledFor(IntensitySpatialOutputMode.NATIVE_3D));
    }

    private static boolean sourceAllowsSpatialMode(IntensitySpatialConfig spatialConfig,
                                                   IntensitySpatialOutputMode mode) {
        return spatialConfig != null && mode != null
                && !spatialConfig.enabledFor(mode).isEmpty();
    }

    private static boolean hasNonNativeSpatialAnalysis(Set<IntensitySpatialConfig.AnalysisKey> analyses) {
        if (analyses == null) return false;
        for (IntensitySpatialConfig.AnalysisKey key : analyses) {
            if (!key.isNative3d()) return true;
        }
        return false;
    }

    private static boolean hasNativeSpatialAnalysis(Set<IntensitySpatialConfig.AnalysisKey> analyses) {
        if (analyses == null) return false;
        for (IntensitySpatialConfig.AnalysisKey key : analyses) {
            if (key.isNative3d()) return true;
        }
        return false;
    }

    private static boolean analysisBelongsInMode(IntensitySpatialConfig.AnalysisKey analysis,
                                                 IntensitySpatialOutputMode mode) {
        if (mode == IntensitySpatialOutputMode.NATIVE_3D) {
            return analysis.isNative3d();
        }
        return !analysis.isNative3d();
    }

    private static boolean emitsBinarizedSameChannelColumns(IntensitySpatialConfig.AnalysisKey analysis) {
        return analysis == IntensitySpatialConfig.AnalysisKey.PATCHINESS
                || analysis == IntensitySpatialConfig.AnalysisKey.HOTSPOTSCAN
                || analysis == IntensitySpatialConfig.AnalysisKey.GRANULARITY;
    }

    private static void addSameChannelColumns(LinkedHashSet<String> columns,
                                              IntensitySpatialConfig.AnalysisKey analysis,
                                              IntensitySpatialConfig spatialConfig) {
        switch (analysis) {
            case PATCHINESS:
                for (double scale : spatialConfig.getTileScalesUm()) {
                    columns.add("Intensity_PatchinessCV" + scaleToken(scale));
                }
                columns.add("Intensity_PatchinessGini");
                for (double scale : spatialConfig.getTileScalesUm()) {
                    columns.add("Intensity_Lacunarity" + scaleToken(scale));
                }
                break;
            case HOTSPOTSCAN:
                columns.add("Intensity_HotspotFraction");
                columns.add("Intensity_HotspotMoransI");
                columns.add("Intensity_HotspotP");
                break;
            case NULLMODEL:
                columns.add("Intensity_NullModelP");
                columns.add("Intensity_NullModelZ");
                columns.add("Intensity_NullModelPass");
                break;
            case GRANULARITY:
                columns.add("Intensity_GranularityPeak_um");
                columns.add("Intensity_GranularityCentroid_um");
                for (double scale : spatialConfig.getGranularityScalesUm()) {
                    columns.add("Intensity_GranularityEnergy" + scaleToken(scale));
                }
                break;
            case DEPTH_PROFILE:
                addDepthColumns(columns, spatialConfig.getDepthBinWidthUm());
                columns.add("Intensity_DepthSlope");
                columns.add("Intensity_DepthPeak_um");
                columns.add("Intensity_RimCoreRatio");
                columns.add("Intensity_EdgeCouplingIdx");
                break;
            case ANISOTROPY:
                columns.add("Intensity_AnisotropyCoherency");
                columns.add("Intensity_AnisotropyAngle_deg");
                columns.add("Intensity_AnisotropyEntropy");
                break;
            case PERIODICITY:
                columns.add("Intensity_PeriodicityWavelength_um");
                columns.add("Intensity_PeriodicityAngle_deg");
                columns.add("Intensity_PeriodicityStripiness");
                columns.add("Intensity_PeriodicityPeakPower");
                break;
            case GLCM:
                columns.add("Intensity_GLCMContrast");
                columns.add("Intensity_GLCMEntropy");
                columns.add("Intensity_GLCMHomogeneity");
                columns.add("Intensity_GLCMASM");
                columns.add("Intensity_GLCMCorrelation");
                break;
            case TEXTURECLASS:
                for (int i = 1; i <= spatialConfig.getTextureClassCount(); i++) {
                    columns.add("Intensity_TextureClass" + i + "_fraction");
                }
                break;
            case SCALEDIVERGENCE:
                columns.add("Intensity_MultifractalDeltaAlpha");
                columns.add("Intensity_MultifractalAsymmetry");
                break;
            case ANISOTROPY_3D:
                columns.add("Intensity_Anisotropy3DCoherency");
                columns.add("Intensity_Anisotropy3DAngle_deg");
                columns.add("Intensity_Anisotropy3DEntropy");
                break;
            default:
                break;
        }
    }

    private static void addCrossChannelColumnsForPartner(LinkedHashSet<String> columns,
                                                         Set<IntensitySpatialConfig.AnalysisKey> analyses,
                                                         IntensitySpatialOutputKey key,
                                                         String partner,
                                                         String[] channelNames,
                                                         IntensitySpatialConfig spatialConfig,
                                                         boolean[] binarization) {
        String source = key.channelName();
        boolean sourceBinarized = isChannelBinarized(key, channelNames, binarization);
        boolean partnerBinarized = isChannelNameBinarized(partner, channelNames, binarization);
        if (key.mode() == IntensitySpatialOutputMode.NATIVE_3D) {
            if (analyses.contains(IntensitySpatialConfig.AnalysisKey.CROSSMARK_3D)) {
                addCrossChannel3dColumns(columns, IntensitySpatialConfig.AnalysisKey.CROSSMARK_3D,
                        source, partner, sourceBinarized, partnerBinarized, spatialConfig);
            }
            if (analyses.contains(IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL_3D)
                    && partnerBinarized) {
                addCrossChannel3dColumns(columns, IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL_3D,
                        source, partner, sourceBinarized, partnerBinarized, spatialConfig);
            }
            return;
        }

        if (analyses.contains(IntensitySpatialConfig.AnalysisKey.CROSSCORR_FAST)
                || analyses.contains(IntensitySpatialConfig.AnalysisKey.CROSSMARK)) {
            addFastCrossCorrelationColumns(columns, source, partner);
        }
        if (analyses.contains(IntensitySpatialConfig.AnalysisKey.ENTROPY_MI)) {
            addEntropyMiColumns(columns, source, partner);
        }
        if (analyses.contains(IntensitySpatialConfig.AnalysisKey.CROSSMARK)) {
            addCrossMarkFullColumns(columns, source, partner, sourceBinarized, partnerBinarized);
        }
        if (analyses.contains(IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL)
                && partnerBinarized) {
            addDistanceShellColumns(columns, source, partner, "DistShell",
                    spatialConfig.getShellWidthUm(), spatialConfig.getShellCount());
        }
    }

    private static void addFastCrossCorrelationColumns(LinkedHashSet<String> columns,
                                                       String source,
                                                       String partner) {
        String suffix = "_" + partner;
        columns.add(source + "_Pearson" + suffix);
        columns.add(source + "_CCFPeakDist_um" + suffix);
        columns.add(source + "_CCFPeakAmp" + suffix);
    }

    private static void addEntropyMiColumns(LinkedHashSet<String> columns,
                                            String source,
                                            String partner) {
        String suffix = "_" + partner;
        columns.add(source + "_NMI" + suffix);
        columns.add(source + "_MIPeakRadius_um" + suffix);
        columns.add(source + "_MIPeakStrength" + suffix);
    }

    private static void addCrossMarkFullColumns(LinkedHashSet<String> columns,
                                                String source,
                                                String partner,
                                                boolean sourceBinarized,
                                                boolean partnerBinarized) {
        String suffix = "_" + partner;
        columns.add(source + "_MarkCorrRadius_um" + suffix);
        columns.add(source + "_MarkCorrStrength" + suffix);
        columns.add(source + "_CostesP" + suffix);
        columns.add(source + "_CostesTa" + suffix);
        columns.add(source + "_CostesTb" + suffix);
        if (sourceBinarized && partnerBinarized) {
            columns.add(source + "_CostesP" + suffix + "_binarized");
        }
        columns.add(source + "_MandersM1" + suffix);
        columns.add(source + "_MandersM2" + suffix);
        if (sourceBinarized && partnerBinarized) {
            columns.add(source + "_MandersM1" + suffix + "_binarized");
            columns.add(source + "_MandersM2" + suffix + "_binarized");
        }
    }

    private static void addCrossChannel3dColumns(LinkedHashSet<String> columns,
                                                  IntensitySpatialConfig.AnalysisKey analysis,
                                                  String source,
                                                  String partner,
                                                  boolean sourceBinarized,
                                                  boolean partnerBinarized,
                                                  IntensitySpatialConfig spatialConfig) {
        String suffix = "_" + partner;
        switch (analysis) {
            case CROSSMARK_3D:
                columns.add(source + "_Pearson3D" + suffix);
                columns.add(source + "_CostesP3D" + suffix);
                columns.add(source + "_CostesTa3D" + suffix);
                columns.add(source + "_CostesTb3D" + suffix);
                if (sourceBinarized && partnerBinarized) {
                    columns.add(source + "_CostesP3D" + suffix + "_binarized");
                }
                columns.add(source + "_MandersM13D" + suffix);
                columns.add(source + "_MandersM23D" + suffix);
                if (sourceBinarized && partnerBinarized) {
                    columns.add(source + "_MandersM13D" + suffix + "_binarized");
                    columns.add(source + "_MandersM23D" + suffix + "_binarized");
                }
                break;
            case DISTANCE_SHELL_3D:
                addDistanceShellColumns(columns, source, partner, "DistShell3D",
                        spatialConfig.getShellWidthUm(), spatialConfig.getShellCount());
                break;
            default:
                break;
        }
    }

    private static void addDepthColumns(LinkedHashSet<String> columns, double binWidthUm) {
        for (int i = 0; i < 3; i++) {
            double start = i * binWidthUm;
            double end = (i + 1) * binWidthUm;
            columns.add("Intensity_DepthBin" + scaleToken(start) + "to" + scaleToken(end));
        }
    }

    private static void addDistanceShellColumns(LinkedHashSet<String> columns,
                                                String source,
                                                String partner,
                                                String token,
                                                double shellWidthUm,
                                                int shellCount) {
        for (int i = 0; i < shellCount; i++) {
            double start = i * shellWidthUm;
            double end = (i + 1) * shellWidthUm;
            columns.add(source + "_" + token + scaleToken(start) + "to"
                    + scaleToken(end) + "_" + partner);
        }
        columns.add(source + "_" + token + "Slope_" + partner);
        columns.add(source + "_" + token + "AUC_" + partner);
    }

    private static String scaleToken(double value) {
        if (Double.compare(value, Math.rint(value)) == 0) {
            return String.valueOf((long) value);
        }
        String text = String.format(Locale.ROOT, "%.6f", value);
        while (text.contains(".") && text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        return text.replace('.', 'p');
    }

    private static List<String> safeChannels(String[] channelNames) {
        if (channelNames == null || channelNames.length == 0) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>();
        for (String channelName : channelNames) {
            if (channelName != null && !channelName.trim().isEmpty()) {
                out.add(channelName);
            }
        }
        return out;
    }

    private static boolean isChannelBinarized(IntensitySpatialOutputKey key,
                                              String[] channelNames,
                                              boolean[] binarization) {
        if (key == null || channelNames == null || binarization == null) return false;
        for (int i = 0; i < channelNames.length && i < binarization.length; i++) {
            if (key.channelName().equals(channelNames[i])) {
                return binarization[i];
            }
        }
        return false;
    }

    private static boolean isChannelNameBinarized(String channelName,
                                                  String[] channelNames,
                                                  boolean[] binarization) {
        if (channelName == null || channelNames == null || binarization == null) return false;
        for (int i = 0; i < channelNames.length && i < binarization.length; i++) {
            if (channelName.equals(channelNames[i])) {
                return binarization[i];
            }
        }
        return false;
    }

    private static void addOutputKey(List<IntensitySpatialOutputKey> selected,
                                     Map<IntensitySpatialOutputKey, File> files,
                                     IntensitySpatialOutputKey key,
                                     File saveRoot) {
        selected.add(key);
        files.put(key, key.csvFile(saveRoot));
    }

    private static void logOutputPlan(IntensityOutputPlan outputPlan) {
        IJ.log("Selected intensity outputs: " + outputPlan.selectedKeys().size());
        for (IntensitySpatialOutputKey key : outputPlan.selectedKeys()) {
            IJ.log("  - " + outputPlan.fileFor(key).getName()
                    + (outputPlan.isSkipped(key) ? " (exists; read-only)" : ""));
        }
    }

    private static final class ChannelSpatialResults {
        final IntensitySpatialResult[] baseResults;
        final IntensitySpatialResult mipResult;
        final IntensitySpatialResult nativeResult;

        ChannelSpatialResults(IntensitySpatialResult[] baseResults,
                              IntensitySpatialResult mipResult,
                              IntensitySpatialResult nativeResult) {
            this.baseResults = baseResults;
            this.mipResult = mipResult;
            this.nativeResult = nativeResult;
        }

        static ChannelSpatialResults empty() {
            return new ChannelSpatialResults(null, null, null);
        }
    }

    static final class IntensityOutputPlan {
        private final List<IntensitySpatialOutputKey> selectedKeys;
        private final Map<IntensitySpatialOutputKey, File> files;
        private final Set<IntensitySpatialOutputKey> skippedKeys;
        private final Map<String, IntensitySpatialOutputKey> baseByChannel;
        private final Map<String, IntensitySpatialOutputKey> mipByChannel;
        private final Map<String, IntensitySpatialOutputKey> nativeByChannel;

        private IntensityOutputPlan(List<IntensitySpatialOutputKey> selectedKeys,
                                    Map<IntensitySpatialOutputKey, File> files,
                                    Set<IntensitySpatialOutputKey> skippedKeys,
                                    Map<String, IntensitySpatialOutputKey> baseByChannel,
                                    Map<String, IntensitySpatialOutputKey> mipByChannel,
                                    Map<String, IntensitySpatialOutputKey> nativeByChannel) {
            this.selectedKeys = Collections.unmodifiableList(new ArrayList<IntensitySpatialOutputKey>(selectedKeys));
            this.files = Collections.unmodifiableMap(new LinkedHashMap<IntensitySpatialOutputKey, File>(files));
            this.skippedKeys = Collections.unmodifiableSet(new HashSet<IntensitySpatialOutputKey>(skippedKeys));
            this.baseByChannel = Collections.unmodifiableMap(new HashMap<String, IntensitySpatialOutputKey>(baseByChannel));
            this.mipByChannel = Collections.unmodifiableMap(new HashMap<String, IntensitySpatialOutputKey>(mipByChannel));
            this.nativeByChannel = Collections.unmodifiableMap(new HashMap<String, IntensitySpatialOutputKey>(nativeByChannel));
        }

        List<IntensitySpatialOutputKey> selectedKeys() {
            return selectedKeys;
        }

        Set<IntensitySpatialOutputKey> skippedKeys() {
            return skippedKeys;
        }

        File fileFor(IntensitySpatialOutputKey key) {
            return files.get(key);
        }

        boolean isSkipped(IntensitySpatialOutputKey key) {
            return skippedKeys.contains(key);
        }

        boolean shouldPopulate(IntensitySpatialOutputKey key) {
            return key != null && files.containsKey(key) && !skippedKeys.contains(key);
        }

        boolean allSelectedOutputsSkipped() {
            return !selectedKeys.isEmpty() && skippedKeys.containsAll(selectedKeys);
        }

        IntensitySpatialOutputKey baseKeyForChannel(String channelName) {
            return baseByChannel.get(channelName);
        }

        IntensitySpatialOutputKey keyForChannelMode(String channelName,
                                                    IntensitySpatialOutputMode mode) {
            if (mode == IntensitySpatialOutputMode.BASE) {
                return baseByChannel.get(channelName);
            }
            if (mode == IntensitySpatialOutputMode.MIP) {
                return mipByChannel.get(channelName);
            }
            if (mode == IntensitySpatialOutputMode.NATIVE_3D) {
                return nativeByChannel.get(channelName);
            }
            return null;
        }

        IntensityOutputTables newTables() {
            return new IntensityOutputTables(selectedKeys);
        }
    }

    static final class IntensityOutputTables {
        private final Map<IntensitySpatialOutputKey, ResultsTable> tables =
                new LinkedHashMap<IntensitySpatialOutputKey, ResultsTable>();

        private IntensityOutputTables(List<IntensitySpatialOutputKey> selectedKeys) {
            for (IntensitySpatialOutputKey key : selectedKeys) {
                tables.put(key, new ResultsTable());
            }
        }

        ResultsTable table(IntensitySpatialOutputKey key) {
            ResultsTable table = tables.get(key);
            if (table == null) {
                table = new ResultsTable();
                tables.put(key, table);
            }
            return table;
        }

        void mergeFrom(IntensityOutputTables other) {
            if (other == null) return;
            for (Map.Entry<IntensitySpatialOutputKey, ResultsTable> entry : other.tables.entrySet()) {
                copyRows(entry.getValue(), table(entry.getKey()));
            }
        }

        private static void copyRows(ResultsTable source, ResultsTable destination) {
            if (source == null || destination == null) return;
            String[] headings = source.getHeadings();
            if (headings == null) return;
            for (int r = 0; r < source.size(); r++) {
                destination.incrementCounter();
                int row = destination.size() - 1;
                for (String heading : headings) {
                    String stringValue = source.getStringValue(heading, r);
                    if (stringValue != null && !stringValue.isEmpty()) {
                        destination.setValue(heading, row, stringValue);
                    } else {
                        destination.setValue(heading, row, source.getValue(heading, r));
                    }
                }
            }
        }
    }

    static File intensityOutputCsv(File saveRoot, String channelName,
                                   boolean roiAnalysis, int roiChannelIndex1Based,
                                   String[] channelNames) {
        String roiMaskName = null;
        if (roiAnalysis && roiChannelIndex1Based > 0
                && channelNames != null
                && roiChannelIndex1Based <= channelNames.length) {
            roiMaskName = channelNames[roiChannelIndex1Based - 1];
        }
        return new IntensitySpatialOutputKey(channelName,
                IntensitySpatialOutputMode.BASE, roiMaskName).csvFile(saveRoot);
    }

    static File intensityOutputCsv(File saveRoot, IntensitySpatialOutputKey key) {
        return key.csvFile(saveRoot);
    }

    static File intensityOutputCsv(File saveRoot, String channelName,
                                   IntensitySpatialOutputMode mode) {
        return new IntensitySpatialOutputKey(channelName, mode, null).csvFile(saveRoot);
    }

    boolean prepareIntensitySpatialOptionsBeforeAnalysis(String directory,
                                                         BinConfig cfg,
                                                         String[] channelNames,
                                                         boolean[] binarization,
                                                         boolean runIntensitySpatial) {
        return prepareIntensitySpatialOptionsBeforeAnalysis(
                directory, cfg, channelNames, binarization, runIntensitySpatial, false);
    }

    boolean prepareIntensitySpatialOptionsBeforeAnalysis(String directory,
                                                         BinConfig cfg,
                                                         String[] channelNames,
                                                         boolean[] binarization,
                                                         boolean runIntensitySpatial,
                                                         boolean roiThresholdStepVisited) {
        int likelyStackDepth = likelyStackDepthForSpatialWizard(directory, cfg);
        Integer stackDepth = likelyStackDepth > 0 ? Integer.valueOf(likelyStackDepth) : null;
        if (!runIntensitySpatial) {
            intensitySpatialConfig = IntensitySpatialConfig.disabled();
            return true;
        }

        IntensitySpatialConfig current = intensitySpatialConfig == null
                ? IntensitySpatialConfig.disabled()
                : intensitySpatialConfig;
        String[] safeChannelNames = channelNames == null
                ? new String[0]
                : Arrays.copyOf(channelNames, channelNames.length);
        boolean[] safeBinarization = binarization == null
                ? new boolean[0]
                : Arrays.copyOf(binarization, binarization.length);

        if (!canLaunchIntensitySpatialOptionsDialog()) {
            intensitySpatialConfig = validateIntensitySpatialConfigForRun(
                    current, safeChannelNames, safeBinarization, stackDepth);
            return true;
        }

        String[] workflow = intensityWorkflow(roiThresholdStepVisited, true);
        IntensitySpatialConfig selected = intensitySpatialOptionsDialogLauncher.launch(
                directory, current, safeChannelNames, safeBinarization, stackDepth,
                workflow, workflowStepIndex(workflow, "Intensity-Spatial"));
        if (selected == null) {
            IJ.log("[FLASH] Intensity Analysis cancelled because intensity-spatial options were cancelled.");
            return false;
        }
        intensitySpatialConfig = validateIntensitySpatialConfigForRun(
                selected, safeChannelNames, safeBinarization, stackDepth);
        return true;
    }

    private boolean canLaunchIntensitySpatialOptionsDialog() {
        return !suppressDialogs && cliConfig == null && !GraphicsEnvironment.isHeadless();
    }

    private static IntensitySpatialConfig validateIntensitySpatialConfigForRun(
            IntensitySpatialConfig config,
            String[] channelNames,
            boolean[] binarization,
            Integer stackDepth) {
        IntensitySpatialConfig safe = config == null
                ? IntensitySpatialConfig.disabled()
                : config;
        int channelCount = channelNames == null ? 0 : channelNames.length;
        return safe.validateForChannelSetup(
                channelCount,
                binarization,
                stackDepth,
                new IntensitySpatialConfig.LockLogger() {
                    public void log(String message) {
                        IJ.log("[FLASH] " + message);
                    }
                });
    }

    private static IntensitySpatialConfig showIntensitySpatialOptionsDialog(
            String directory,
            IntensitySpatialConfig currentConfig,
            String[] channelNames,
            boolean[] binarization,
            Integer likelyStackDepth) {
        return showIntensitySpatialOptionsDialog(
                directory, currentConfig, channelNames, binarization, likelyStackDepth,
                new String[]{"Setup", "Intensity-Spatial", "Run"}, 1);
    }

    private static IntensitySpatialConfig showIntensitySpatialOptionsDialog(
            String directory,
            IntensitySpatialConfig currentConfig,
            String[] channelNames,
            boolean[] binarization,
            Integer likelyStackDepth,
            String[] workflowSteps,
            int workflowActiveIndex) {
        IntensitySpatialConfig base = currentConfig == null
                ? IntensitySpatialConfig.disabled()
                : currentConfig;
        String[] safeChannelNames = channelNames == null
                ? new String[0]
                : Arrays.copyOf(channelNames, channelNames.length);
        boolean[] safeBinarization = binarization == null
                ? new boolean[0]
                : Arrays.copyOf(binarization, binarization.length);
        final boolean hasBinarizedChannel = anyTrue(safeBinarization);
        final boolean native3dAvailable = likelyStackDepth == null
                || likelyStackDepth.intValue() >= IntensitySpatialConfig.MIN_NATIVE_3D_SLICES;
        final boolean singleSliceOnly = likelyStackDepth != null && likelyStackDepth.intValue() <= 1;
        final boolean baseHasSelection = base.anyEnabled();
        final int channelCount = safeChannelNames.length;

        while (true) {
            PipelineDialog dialog = new PipelineDialog("Intensity-Spatial Analysis",
                    PipelineDialog.Phase.ANALYSE);
            dialog.setWorkflowTracker(workflowSteps, workflowActiveIndex);
            dialog.setPrimaryButtonText(NextStepLabels.RUN_INTENSITY_ANALYSIS);
            dialog.addAnalysisHelpHeader("Intensity-Spatial Analysis", FLASH_Pipeline.IDX_INTENSITY);
            dialog.addMessage("Pick spatial metrics per mode. Each tab runs independently.");
            final IntensitySpatialPresetBindings bindings = new IntensitySpatialPresetBindings();
            addIntensitySpatialPresetControls(dialog, directory, bindings, channelCount,
                    safeBinarization, likelyStackDepth);

            final Runnable updateSummary = new Runnable() {
                @Override public void run() {
                    updateSpatialWillRun(bindings);
                    updateCostesPermutationControlState(bindings);
                }
            };

            javax.swing.JTabbedPane tabs = new javax.swing.JTabbedPane();
            tabs.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            tabs.addTab("Per-Slice", buildSpatialModeTab(IntensitySpatialOutputMode.BASE, bindings,
                    GUI_SAME_CHANNEL_2D_ANALYSES, GUI_CROSS_CHANNEL_2D_ANALYSES,
                    baseHasSelection ? base.getEnabledPerSlice() : DEFAULT_PERSLICE_ANALYSES,
                    DEFAULT_PERSLICE_ANALYSES, channelCount, hasBinarizedChannel,
                    native3dAvailable, singleSliceOnly, updateSummary));
            tabs.addTab("MIP", buildSpatialModeTab(IntensitySpatialOutputMode.MIP, bindings,
                    GUI_SAME_CHANNEL_2D_ANALYSES, GUI_CROSS_CHANNEL_2D_ANALYSES,
                    baseHasSelection ? base.getEnabledMip() : DEFAULT_MIP_ANALYSES,
                    DEFAULT_MIP_ANALYSES, channelCount, hasBinarizedChannel,
                    native3dAvailable, singleSliceOnly, updateSummary));
            tabs.addTab("3D", buildSpatialModeTab(IntensitySpatialOutputMode.NATIVE_3D, bindings,
                    GUI_NATIVE_3D_SAME_CHANNEL_ANALYSES, GUI_NATIVE_3D_CROSS_CHANNEL_ANALYSES,
                    baseHasSelection ? base.getEnabled3D() : DEFAULT_NATIVE_3D_ANALYSES,
                    DEFAULT_NATIVE_3D_ANALYSES, channelCount, hasBinarizedChannel,
                    native3dAvailable, singleSliceOnly, updateSummary));
            tabs.setPreferredSize(new java.awt.Dimension(470, 340));
            tabs.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 380));
            dialog.addComponent(tabs);

            bindings.overlaysToggle =
                    dialog.addToggle("Write verification overlays", base.isOverlaysEnabled());

            dialog.addHeader("Parameters");
            bindings.shellWidthField =
                    dialog.addNumericField("Distance shell width (um)", base.getShellWidthUm(), 1);
            bindings.shellCountField =
                    dialog.addNumericField("Distance shell count", base.getShellCount(), 0);
            bindings.tileScalesField = dialog.addStringField("Tile scales (um, comma-separated)",
                    IntensitySpatialConfig.joinDoubles(base.getTileScalesUm()), 28);
            bindings.granularityScalesField = dialog.addStringField("Granularity scales (um, comma-separated)",
                    IntensitySpatialConfig.joinDoubles(base.getGranularityScalesUm()), 28);
            bindings.depthBinWidthField =
                    dialog.addNumericField("Depth bin width (um)", base.getDepthBinWidthUm(), 1);
            bindings.rimDepthField =
                    dialog.addNumericField("Rim depth (um)", base.getRimDepthUm(), 1);
            bindings.textureClassCountField =
                    dialog.addNumericField("Texture class count", base.getTextureClassCount(), 0);
            bindings.permutationsField =
                    dialog.addNumericField("Hotspot permutations", base.getPermutations(), 0);
            addCostesPermutationSlider(dialog, bindings, base.getCostesPermutations());
            bindings.seedField =
                    dialog.addStringField("Random seed", String.valueOf(base.getSeed()), 12);

            bindings.willRunLabel = new javax.swing.JLabel(" ");
            bindings.willRunLabel.setFont(bindings.willRunLabel.getFont()
                    .deriveFont(java.awt.Font.BOLD, 11f));
            bindings.willRunLabel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            dialog.addComponent(bindings.willRunLabel);
            updateSummary.run();

            if (!dialog.showDialog()) {
                return null;
            }

            IntensitySpatialConfig result = buildIntensitySpatialConfigFromBindings(bindings);
            if (!result.anyEnabled()) {
                IJ.error("Intensity-Spatial Analysis",
                        "Select at least one metric in a tab, or turn the main toggle off.");
                continue;
            }
            return result;
        }
    }

    private static javax.swing.JComponent buildSpatialModeTab(
            final IntensitySpatialOutputMode mode,
            final IntensitySpatialPresetBindings bindings,
            IntensitySpatialConfig.AnalysisKey[] sameChannel,
            IntensitySpatialConfig.AnalysisKey[] crossChannel,
            Set<IntensitySpatialConfig.AnalysisKey> initialOn,
            final Set<IntensitySpatialConfig.AnalysisKey> recommended,
            int channelCount,
            boolean hasBinarizedChannel,
            boolean native3dAvailable,
            boolean singleSliceOnly,
            final Runnable onChange) {
        final Map<IntensitySpatialConfig.AnalysisKey, ToggleSwitch> map = bindings.toggles(mode);
        JPanel panel = new JPanel();
        panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
        panel.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 10, 8, 10));
        panel.setOpaque(false);

        javax.swing.JLabel blurb = new javax.swing.JLabel(
                spatialModeBlurb(mode, native3dAvailable, singleSliceOnly));
        blurb.setFont(blurb.getFont().deriveFont(java.awt.Font.ITALIC, 11f));
        blurb.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        panel.add(blurb);
        panel.add(javax.swing.Box.createVerticalStrut(6));

        JButton recommend = new JButton("Recommended");
        JButton clear = new JButton("Clear");
        JPanel btnRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        btnRow.setOpaque(false);
        btnRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        btnRow.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 30));
        btnRow.add(recommend);
        btnRow.add(clear);
        panel.add(btnRow);

        panel.add(spatialSectionHeader("Same-Channel Metrics"));
        for (IntensitySpatialConfig.AnalysisKey key : sameChannel) {
            boolean available = spatialAnalysisAvailableInMode(key, mode, channelCount,
                    hasBinarizedChannel, native3dAvailable, singleSliceOnly);
            addSpatialToggleRow(panel, map, key, initialOn.contains(key), available, onChange);
        }

        panel.add(spatialSectionHeader("Cross-Channel Metrics"));
        if (channelCount < 2) {
            panel.add(spatialHelpRow("Cross-channel metrics require at least two channels."));
        }
        if (!hasBinarizedChannel) {
            panel.add(spatialHelpRow("Distance-shell metrics require at least one binarised channel."));
        }
        for (IntensitySpatialConfig.AnalysisKey key : crossChannel) {
            boolean available = spatialAnalysisAvailableInMode(key, mode, channelCount,
                    hasBinarizedChannel, native3dAvailable, singleSliceOnly);
            addSpatialToggleRow(panel, map, key, initialOn.contains(key), available, onChange);
        }

        recommend.addActionListener(new java.awt.event.ActionListener() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                for (Map.Entry<IntensitySpatialConfig.AnalysisKey, ToggleSwitch> entry : map.entrySet()) {
                    ToggleSwitch toggle = entry.getValue();
                    if (toggle != null && toggle.isEnabled()) {
                        toggle.setSelected(recommended.contains(entry.getKey()));
                    }
                }
                if (onChange != null) onChange.run();
            }
        });
        clear.addActionListener(new java.awt.event.ActionListener() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                for (ToggleSwitch toggle : map.values()) {
                    if (toggle != null && toggle.isEnabled()) toggle.setSelected(false);
                }
                if (onChange != null) onChange.run();
            }
        });

        JPanel north = new JPanel(new java.awt.BorderLayout());
        north.setOpaque(false);
        north.add(panel, java.awt.BorderLayout.NORTH);
        javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(north,
                javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(12);
        return scroll;
    }

    private static void addSpatialToggleRow(JPanel panel,
                                            Map<IntensitySpatialConfig.AnalysisKey, ToggleSwitch> map,
                                            IntensitySpatialConfig.AnalysisKey key,
                                            boolean defaultOn,
                                            boolean available,
                                            Runnable onChange) {
        ToggleSwitch toggle = new ToggleSwitch(available && defaultOn);
        if (!available) {
            toggle.setSelected(false);
            toggle.setEnabled(false);
        }
        if (onChange != null) toggle.addChangeListener(onChange);
        map.put(key, toggle);

        JPanel row = new JPanel();
        row.setLayout(new javax.swing.BoxLayout(row, javax.swing.BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        javax.swing.JLabel lbl = new javax.swing.JLabel(intensitySpatialAnalysisLabel(key));
        lbl.setFont(lbl.getFont().deriveFont(java.awt.Font.PLAIN, 12f));
        if (!available) lbl.setEnabled(false);
        row.add(lbl);
        row.add(javax.swing.Box.createHorizontalGlue());
        row.add(toggle);
        row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 26));
        panel.add(row);
        panel.add(javax.swing.Box.createVerticalStrut(3));
    }

    private static javax.swing.JComponent spatialSectionHeader(String text) {
        javax.swing.JLabel header = new javax.swing.JLabel(text);
        header.setFont(header.getFont().deriveFont(java.awt.Font.BOLD, 12f));
        header.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        header.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 0, 2, 0));
        return header;
    }

    private static javax.swing.JComponent spatialHelpRow(String text) {
        javax.swing.JLabel help = new javax.swing.JLabel(text);
        help.setFont(help.getFont().deriveFont(java.awt.Font.ITALIC, 10f));
        help.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        help.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 12, 2, 0));
        return help;
    }

    private static String spatialModeBlurb(IntensitySpatialOutputMode mode,
                                           boolean native3dAvailable,
                                           boolean singleSliceOnly) {
        if (mode == IntensitySpatialOutputMode.MIP) {
            return singleSliceOnly
                    ? "MIP needs a z-stack with more than one slice (unavailable for this image)."
                    : "Collapses the stack to its brightest pixels, then measures once. Best for sparse, bright structures.";
        }
        if (mode == IntensitySpatialOutputMode.NATIVE_3D) {
            return native3dAvailable
                    ? "True volumetric measurement across the z-stack. Slower; off by default."
                    : "Native 3D needs at least " + IntensitySpatialConfig.MIN_NATIVE_3D_SLICES
                            + " z-slices (unavailable for this image).";
        }
        return "Measures each z-slice on its own. Best for dense or depth-varying signal.";
    }

    private static boolean spatialAnalysisAvailableInMode(IntensitySpatialConfig.AnalysisKey key,
                                                          IntensitySpatialOutputMode mode,
                                                          int channelCount,
                                                          boolean hasBinarizedChannel,
                                                          boolean native3dAvailable,
                                                          boolean singleSliceOnly) {
        if (!isIntensitySpatialAnalysisAvailable(key, channelCount, hasBinarizedChannel, native3dAvailable)) {
            return false;
        }
        return !(mode == IntensitySpatialOutputMode.MIP && singleSliceOnly);
    }

    private static void updateSpatialWillRun(IntensitySpatialPresetBindings bindings) {
        if (bindings == null || bindings.willRunLabel == null) return;
        int perSlice = countSelectedToggles(bindings.toggles(IntensitySpatialOutputMode.BASE));
        int mip = countSelectedToggles(bindings.toggles(IntensitySpatialOutputMode.MIP));
        int native3d = countSelectedToggles(bindings.toggles(IntensitySpatialOutputMode.NATIVE_3D));
        bindings.willRunLabel.setText("Will run:   Per-Slice " + perSlice
                + "   -   MIP " + mip + "   -   3D " + native3d + "   metrics");
    }

    private static void addCostesPermutationSlider(PipelineDialog dialog,
                                                   final IntensitySpatialPresetBindings bindings,
                                                   int initialValue) {
        final int initial = clampCostesPermutations(initialValue);
        final javax.swing.JLabel label = new javax.swing.JLabel("Costes permutations");
        final javax.swing.JSlider slider = new javax.swing.JSlider(0,
                IntensitySpatialConfig.MAX_COSTES_PERMUTATIONS, initial);
        final JTextField field = new JTextField(String.valueOf(initial), 4);
        slider.setMajorTickSpacing(50);
        slider.setMinorTickSpacing(10);
        slider.setPaintTicks(true);
        slider.setSnapToTicks(false);
        slider.addChangeListener(e -> field.setText(String.valueOf(slider.getValue())));
        field.addActionListener(e -> syncCostesFieldToSlider(field, slider));
        field.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) {
                syncCostesFieldToSlider(field, slider);
            }
        });

        JPanel row = new JPanel();
        row.setLayout(new javax.swing.BoxLayout(row, javax.swing.BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        row.add(label);
        row.add(javax.swing.Box.createHorizontalStrut(8));
        row.add(slider);
        row.add(javax.swing.Box.createHorizontalStrut(6));
        field.setMaximumSize(new java.awt.Dimension(52, 24));
        row.add(field);
        row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 32));

        if (bindings != null) {
            bindings.costesPermutationsLabel = label;
            bindings.costesPermutationsSlider = slider;
            bindings.costesPermutationsField = field;
        }
        dialog.addComponent(row);
    }

    private static void updateCostesPermutationControlState(IntensitySpatialPresetBindings bindings) {
        if (bindings == null) return;
        boolean enabled = hasSelectedFullCrossMark(bindings);
        if (bindings.costesPermutationsLabel != null) {
            bindings.costesPermutationsLabel.setEnabled(enabled);
        }
        if (bindings.costesPermutationsSlider != null) {
            bindings.costesPermutationsSlider.setEnabled(enabled);
        }
        if (bindings.costesPermutationsField != null) {
            bindings.costesPermutationsField.setEnabled(enabled);
        }
    }

    private static boolean hasSelectedFullCrossMark(IntensitySpatialPresetBindings bindings) {
        return isSelectedInMode(bindings, IntensitySpatialOutputMode.BASE,
                IntensitySpatialConfig.AnalysisKey.CROSSMARK)
                || isSelectedInMode(bindings, IntensitySpatialOutputMode.MIP,
                IntensitySpatialConfig.AnalysisKey.CROSSMARK)
                || isSelectedInMode(bindings, IntensitySpatialOutputMode.NATIVE_3D,
                IntensitySpatialConfig.AnalysisKey.CROSSMARK_3D);
    }

    private static boolean isSelectedInMode(IntensitySpatialPresetBindings bindings,
                                            IntensitySpatialOutputMode mode,
                                            IntensitySpatialConfig.AnalysisKey key) {
        if (bindings == null || mode == null || key == null) return false;
        ToggleSwitch toggle = bindings.toggles(mode).get(key);
        return toggle != null && toggle.isEnabled() && toggle.isSelected();
    }

    private static void syncCostesFieldToSlider(JTextField field, javax.swing.JSlider slider) {
        if (field == null || slider == null) return;
        int value = clampCostesPermutations(nonNegativeIntOrDefault(doubleFieldValue(field),
                IntensitySpatialConfig.DEFAULT_COSTES_PERMUTATIONS));
        slider.setValue(value);
        field.setText(String.valueOf(value));
    }

    private static int clampCostesPermutations(int value) {
        return Math.max(0, Math.min(IntensitySpatialConfig.MAX_COSTES_PERMUTATIONS, value));
    }

    private static int countSelectedToggles(Map<IntensitySpatialConfig.AnalysisKey, ToggleSwitch> map) {
        int count = 0;
        if (map != null) {
            for (ToggleSwitch toggle : map.values()) {
                if (toggle != null && toggle.isEnabled() && toggle.isSelected()) count++;
            }
        }
        return count;
    }

    private static void addIntensitySpatialPresetControls(
            final PipelineDialog dialog,
            final String directory,
            final IntensitySpatialPresetBindings bindings,
            final int channelCount,
            final boolean[] binarization,
            final Integer likelyStackDepth) {
        final JComboBox<String> presetCombo =
                new JComboBox<String>(listIntensitySpatialPresetNames(directory));
        presetCombo.setMaximumSize(new java.awt.Dimension(260, 24));
        if (bindings != null) {
            bindings.presetCombo = presetCombo;
        }
        JButton savePreset = new JButton("Save as preset...");
        flash.pipeline.ui.FlashIcons.apply(savePreset, flash.pipeline.ui.FlashIcons.save());
        savePreset.setToolTipText("Save the current Intensity-Spatial options as a named preset.");
        savePreset.addActionListener(e -> handleSaveIntensitySpatialPreset(directory, bindings));
        JButton managePreset = new JButton("Manage...");
        managePreset.setToolTipText("Delete saved Intensity-Spatial presets.");
        managePreset.addActionListener(e -> {
            boolean changed = flash.pipeline.ui.config.PresetManagerDialog.manage(
                    presetCombo, new IntensitySpatialPresetIO(projectRootForDirectory(directory)),
                    "Manage Intensity-Spatial Presets");
            if (changed) {
                refreshIntensitySpatialPresetChoice(directory, bindings,
                        INTENSITY_SPATIAL_PRESET_PLACEHOLDER);
            }
        });
        JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        row.setOpaque(false);
        row.add(presetCombo);
        row.add(savePreset);
        row.add(managePreset);
        presetCombo.addActionListener(e -> {
            if (bindings != null && bindings.programmaticChange) {
                return;
            }
            Object selected = presetCombo.getSelectedItem();
            if (selected != null
                    && !INTENSITY_SPATIAL_PRESET_PLACEHOLDER.equals(String.valueOf(selected))) {
                IntensitySpatialConfig config = loadIntensitySpatialPresetConfig(
                        directory, String.valueOf(selected));
                if (config != null) {
                    applyIntensitySpatialConfigToDialog(config, bindings, channelCount,
                            binarization, likelyStackDepth, String.valueOf(selected));
                }
            }
        });
        dialog.addComponent(row);
    }

    private static String[] listIntensitySpatialPresetNames(String directory) {
        List<String> labels = new ArrayList<String>();
        labels.add(INTENSITY_SPATIAL_PRESET_PLACEHOLDER);
        try {
            List<IntensitySpatialPreset> presets =
                    new IntensitySpatialPresetIO(projectRootForDirectory(directory)).listAll();
            for (IntensitySpatialPreset preset : presets) {
                labels.add(preset.getName());
            }
        } catch (IOException e) {
            IJ.log("WARNING: Could not list Intensity-Spatial presets: " + e.getMessage());
        }
        return labels.toArray(new String[labels.size()]);
    }

    private static void refreshIntensitySpatialPresetChoice(
            String directory,
            IntensitySpatialPresetBindings bindings,
            String selectedName) {
        if (bindings == null || bindings.presetCombo == null) {
            return;
        }
        bindings.programmaticChange = true;
        try {
            bindings.presetCombo.removeAllItems();
            for (String presetName : listIntensitySpatialPresetNames(directory)) {
                bindings.presetCombo.addItem(presetName);
            }
            bindings.presetCombo.setSelectedItem(selectedName == null
                    ? INTENSITY_SPATIAL_PRESET_PLACEHOLDER
                    : selectedName);
        } finally {
            bindings.programmaticChange = false;
        }
    }

    private static IntensitySpatialConfig loadIntensitySpatialPresetConfig(
            String directory,
            String presetName) {
        try {
            IntensitySpatialPreset preset =
                    new IntensitySpatialPresetIO(projectRootForDirectory(directory)).load(presetName);
            return preset.getConfig();
        } catch (IOException e) {
            IJ.showMessage("Intensity-Spatial Analysis", "Could not load preset: " + e.getMessage());
            return null;
        }
    }

    private static void handleSaveIntensitySpatialPreset(
            String directory,
            IntensitySpatialPresetBindings bindings) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        if (bindings == null) {
            IJ.showMessage("Intensity-Spatial Analysis",
                    "Could not save preset: dialog options are not available.");
            return;
        }
        String name = JOptionPane.showInputDialog(
                bindings.presetCombo,
                "Preset name:",
                "Save Intensity-Spatial Preset",
                JOptionPane.PLAIN_MESSAGE);
        if (name == null) {
            return;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            IJ.showMessage("Intensity-Spatial Analysis", "Preset name cannot be empty.");
            return;
        }
        try {
            IntensitySpatialConfig config = buildIntensitySpatialConfigFromBindings(bindings);
            if (config.getEnabledAnalyses().isEmpty()) {
                IJ.showMessage("Intensity-Spatial Analysis",
                        "Select at least one metric before saving a preset.");
                return;
            }
            IntensitySpatialPreset preset = new IntensitySpatialPreset(
                    trimmed,
                    "Saved from Intensity-Spatial Analysis dialog",
                    IntensitySpatialPreset.CURRENT_LIBRARY_VERSION,
                    config);
            new IntensitySpatialPresetIO(projectRootForDirectory(directory)).save(preset);
            IJ.log("Saved Intensity-Spatial preset: " + trimmed);
            refreshIntensitySpatialPresetChoice(directory, bindings, preset.getName());
        } catch (IOException e) {
            IJ.showMessage("Intensity-Spatial Analysis",
                    "Could not save preset: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            IJ.showMessage("Intensity-Spatial Analysis",
                    "Could not save preset: " + e.getMessage());
        }
    }

    private static void applyIntensitySpatialConfigToDialog(
            IntensitySpatialConfig config,
            IntensitySpatialPresetBindings bindings,
            int channelCount,
            boolean[] binarization,
            Integer likelyStackDepth,
            String selectedPresetName) {
        if (config == null || bindings == null) {
            return;
        }
        final String presetLabel = selectedPresetName == null ? "selected preset" : selectedPresetName;
        IntensitySpatialConfig validated = config.validateForChannelSetup(
                channelCount, binarization, likelyStackDepth,
                new IntensitySpatialConfig.LockLogger() {
                    public void log(String message) {
                        IJ.log("[FLASH] Intensity-Spatial preset " + presetLabel + ": " + message);
                    }
                });
        bindings.programmaticChange = true;
        try {
            if (bindings.presetCombo != null) {
                bindings.presetCombo.setSelectedItem(selectedPresetName == null
                        ? INTENSITY_SPATIAL_PRESET_PLACEHOLDER
                        : selectedPresetName);
            }
            applyModeToggles(bindings, IntensitySpatialOutputMode.BASE, validated.getEnabledPerSlice());
            applyModeToggles(bindings, IntensitySpatialOutputMode.MIP, validated.getEnabledMip());
            applyModeToggles(bindings, IntensitySpatialOutputMode.NATIVE_3D, validated.getEnabled3D());
            setToggle(bindings.overlaysToggle, validated.isOverlaysEnabled());
            setFieldText(bindings.shellWidthField,
                    numericText(validated.getShellWidthUm(), 1));
            setFieldText(bindings.shellCountField,
                    numericText(validated.getShellCount(), 0));
            setFieldText(bindings.tileScalesField,
                    IntensitySpatialConfig.joinDoubles(validated.getTileScalesUm()));
            setFieldText(bindings.granularityScalesField,
                    IntensitySpatialConfig.joinDoubles(validated.getGranularityScalesUm()));
            setFieldText(bindings.depthBinWidthField,
                    numericText(validated.getDepthBinWidthUm(), 1));
            setFieldText(bindings.rimDepthField,
                    numericText(validated.getRimDepthUm(), 1));
            setFieldText(bindings.textureClassCountField,
                    numericText(validated.getTextureClassCount(), 0));
            setFieldText(bindings.permutationsField,
                    numericText(validated.getPermutations(), 0));
            setCostesPermutationControlValue(bindings, validated.getCostesPermutations());
            setFieldText(bindings.seedField, String.valueOf(validated.getSeed()));
            updateSpatialWillRun(bindings);
            updateCostesPermutationControlState(bindings);
        } finally {
            bindings.programmaticChange = false;
        }
    }

    private static void applyModeToggles(IntensitySpatialPresetBindings bindings,
                                         IntensitySpatialOutputMode mode,
                                         Set<IntensitySpatialConfig.AnalysisKey> on) {
        if (bindings == null) return;
        for (Map.Entry<IntensitySpatialConfig.AnalysisKey, ToggleSwitch> entry
                : bindings.toggles(mode).entrySet()) {
            ToggleSwitch toggle = entry.getValue();
            boolean selected = on != null && on.contains(entry.getKey());
            setToggle(toggle, selected && (toggle == null || toggle.isEnabled()));
        }
    }

    private static EnumSet<IntensitySpatialConfig.AnalysisKey> readSelectedToggles(
            IntensitySpatialPresetBindings bindings, IntensitySpatialOutputMode mode) {
        EnumSet<IntensitySpatialConfig.AnalysisKey> selected =
                EnumSet.noneOf(IntensitySpatialConfig.AnalysisKey.class);
        if (bindings != null) {
            for (Map.Entry<IntensitySpatialConfig.AnalysisKey, ToggleSwitch> entry
                    : bindings.toggles(mode).entrySet()) {
                ToggleSwitch toggle = entry.getValue();
                if (toggle != null && toggle.isEnabled() && toggle.isSelected()) {
                    selected.add(entry.getKey());
                }
            }
        }
        return selected;
    }

    private static IntensitySpatialConfig buildIntensitySpatialConfigFromBindings(
            IntensitySpatialPresetBindings bindings) {
        return IntensitySpatialConfig.builder()
                .enabled(true)
                .enabledPerSlice(readSelectedToggles(bindings, IntensitySpatialOutputMode.BASE))
                .enabledMip(readSelectedToggles(bindings, IntensitySpatialOutputMode.MIP))
                .enabled3D(readSelectedToggles(bindings, IntensitySpatialOutputMode.NATIVE_3D))
                .overlaysEnabled(bindings != null && isSelected(bindings.overlaysToggle))
                .shellWidthUm(positiveDoubleFieldOrDefault(
                        bindings == null ? null : bindings.shellWidthField,
                        IntensitySpatialConfig.DEFAULT_SHELL_WIDTH_UM))
                .shellCount(positiveIntFieldOrDefault(
                        bindings == null ? null : bindings.shellCountField,
                        IntensitySpatialConfig.DEFAULT_SHELL_COUNT))
                .tileScalesUm(IntensitySpatialConfig.parseDoubleList(
                        fieldText(bindings == null ? null : bindings.tileScalesField),
                        IntensitySpatialConfig.DEFAULT_TILE_SCALES_UM))
                .granularityScalesUm(IntensitySpatialConfig.parseDoubleList(
                        fieldText(bindings == null ? null : bindings.granularityScalesField),
                        IntensitySpatialConfig.DEFAULT_GRANULARITY_SCALES_UM))
                .depthBinWidthUm(positiveDoubleFieldOrDefault(
                        bindings == null ? null : bindings.depthBinWidthField,
                        IntensitySpatialConfig.DEFAULT_DEPTH_BIN_WIDTH_UM))
                .rimDepthUm(positiveDoubleFieldOrDefault(
                        bindings == null ? null : bindings.rimDepthField,
                        IntensitySpatialConfig.DEFAULT_RIM_DEPTH_UM))
                .textureClassCount(positiveIntFieldOrDefault(
                        bindings == null ? null : bindings.textureClassCountField,
                        IntensitySpatialConfig.DEFAULT_TEXTURE_CLASS_COUNT))
                .permutations(nonNegativeIntFieldOrDefault(
                        bindings == null ? null : bindings.permutationsField,
                        IntensitySpatialConfig.DEFAULT_PERMUTATIONS))
                .costesPermutations(clampCostesPermutations(nonNegativeIntFieldOrDefault(
                        bindings == null ? null : bindings.costesPermutationsField,
                        IntensitySpatialConfig.DEFAULT_COSTES_PERMUTATIONS)))
                .seed(longOrDefault(fieldText(bindings == null ? null : bindings.seedField),
                        IntensitySpatialConfig.DEFAULT_SEED))
                .build();
    }

    private static void setCostesPermutationControlValue(IntensitySpatialPresetBindings bindings,
                                                         int value) {
        if (bindings == null) return;
        int clamped = clampCostesPermutations(value);
        setFieldText(bindings.costesPermutationsField, String.valueOf(clamped));
        if (bindings.costesPermutationsSlider != null) {
            bindings.costesPermutationsSlider.setValue(clamped);
        }
    }

    private static File projectRootForDirectory(String directory) {
        return directory == null || directory.trim().isEmpty() ? new File(".") : new File(directory);
    }

    private static void setFieldText(JTextField field, String value) {
        if (field != null) {
            field.setText(value == null ? "" : value);
        }
    }

    private static String fieldText(JTextField field) {
        return field == null || field.getText() == null ? "" : field.getText().trim();
    }

    private static double positiveDoubleFieldOrDefault(JTextField field, double fallback) {
        return positiveDoubleOrDefault(doubleFieldValue(field), fallback);
    }

    private static int positiveIntFieldOrDefault(JTextField field, int fallback) {
        return positiveIntOrDefault(doubleFieldValue(field), fallback);
    }

    private static int nonNegativeIntFieldOrDefault(JTextField field, int fallback) {
        return nonNegativeIntOrDefault(doubleFieldValue(field), fallback);
    }

    private static double doubleFieldValue(JTextField field) {
        String text = fieldText(field);
        if (text.isEmpty()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static String numericText(double value, int decimals) {
        if (decimals <= 0) {
            return String.valueOf((int) Math.round(value));
        }
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    private static boolean isIntensitySpatialAnalysisAvailable(
            IntensitySpatialConfig.AnalysisKey key,
            int channelCount,
            boolean hasBinarizedChannel,
            boolean native3dAvailable) {
        if (key == null) return false;
        if (key.isNative3d() && !native3dAvailable) return false;
        if (key.isCrossChannel() && channelCount < 2) return false;
        return (key != IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL
                && key != IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL_3D)
                || hasBinarizedChannel;
    }

    private static String intensitySpatialAnalysisLabel(IntensitySpatialConfig.AnalysisKey key) {
        if (key == IntensitySpatialConfig.AnalysisKey.PATCHINESS) return "Patchiness / tile variation";
        if (key == IntensitySpatialConfig.AnalysisKey.HOTSPOTSCAN) return "Hotspot scan";
        if (key == IntensitySpatialConfig.AnalysisKey.NULLMODEL) return "Random null model";
        if (key == IntensitySpatialConfig.AnalysisKey.GRANULARITY) return "Granularity scale";
        if (key == IntensitySpatialConfig.AnalysisKey.DEPTH_PROFILE) return "Depth profile / rim-core";
        if (key == IntensitySpatialConfig.AnalysisKey.ANISOTROPY) return "2D anisotropy";
        if (key == IntensitySpatialConfig.AnalysisKey.PERIODICITY) return "Periodicity";
        if (key == IntensitySpatialConfig.AnalysisKey.GLCM) return "GLCM texture";
        if (key == IntensitySpatialConfig.AnalysisKey.TEXTURECLASS) return "Texture classes";
        if (key == IntensitySpatialConfig.AnalysisKey.SCALEDIVERGENCE) return "Scale divergence";
        if (key == IntensitySpatialConfig.AnalysisKey.CROSSCORR_FAST) return "Fast cross-channel correlation";
        if (key == IntensitySpatialConfig.AnalysisKey.CROSSMARK) return "Full CrossMark / Coloc2";
        if (key == IntensitySpatialConfig.AnalysisKey.ENTROPY_MI) return "Cross-channel mutual information";
        if (key == IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL) return "Distance shells around binarised partner";
        if (key == IntensitySpatialConfig.AnalysisKey.ANISOTROPY_3D) return "Native 3D anisotropy";
        if (key == IntensitySpatialConfig.AnalysisKey.CROSSMARK_3D) return "Native 3D cross-mark";
        if (key == IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL_3D) return "Native 3D distance shells";
        return key.name();
    }

    private static double positiveDoubleOrDefault(double value, double fallback) {
        return Double.isNaN(value) || Double.isInfinite(value) || value <= 0.0
                ? fallback
                : value;
    }

    private static int positiveIntOrDefault(double value, int fallback) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return fallback;
        int parsed = (int) Math.round(value);
        return parsed > 0 ? parsed : fallback;
    }

    private static int nonNegativeIntOrDefault(double value, int fallback) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return fallback;
        int parsed = (int) Math.round(value);
        return parsed >= 0 ? parsed : fallback;
    }

    private static long longOrDefault(String raw, long fallback) {
        if (raw == null || raw.trim().isEmpty()) return fallback;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int likelyStackDepthForSpatialWizard(String directory, BinConfig cfg) {
        int configuredDepth = configuredZSliceDepth(cfg);
        if (configuredDepth > 0) {
            return configuredDepth;
        }
        try {
            List<SeriesMeta> metas = ImageSourceDispatcher.readAllMetadata(directory);
            if (metas != null && !metas.isEmpty()) {
                return Math.max(1, metas.get(0).nSlices);
            }
        } catch (Exception e) {
            IJ.log("[FLASH] Could not inspect z-stack depth for intensity-spatial setup: " + e.getMessage());
        }
        return -1;
    }

    private static int configuredZSliceDepth(BinConfig cfg) {
        if (cfg == null || !cfg.usesZSliceSubset() || cfg.zSliceSelections.isEmpty()) {
            return -1;
        }
        int minDepth = Integer.MAX_VALUE;
        for (flash.pipeline.zslice.ZSliceSelection selection : cfg.zSliceSelections.values()) {
            if (selection != null && selection.range != null) {
                minDepth = Math.min(minDepth, selection.range.count());
            }
        }
        return minDepth == Integer.MAX_VALUE ? -1 : minDepth;
    }

    private IntensitySetupConfig.DerivedConfig loadIntensityPresetConfig(String directory,
                                                                    BinConfig cfg,
                                                                    ChannelIdentities identities,
                                                                    List<String> roiSetNames,
                                                                    String presetName) {
        try {
            IntensityPreset preset = new IntensityPresetIO(new File(directory)).load(presetName);
            return IntensitySetupConfig.fromPreset(cfg, identities, preset, roiSetNames);
        } catch (IOException e) {
            IJ.showMessage("Intensity Analysis", "Could not load preset: " + e.getMessage());
            return null;
        }
    }

    /**
     * Writes a derived configuration (from a preset or a loaded run) into the
     * primary dialog: the backing arrays plus the live toggles/choices. The
     * secondary dialog reads the same backing arrays when it is built, so
     * thresholds, ROI selection and the mask channel follow automatically.
     */
    private void applyIntensityPresetToStep0(IntensitySetupConfig.DerivedConfig derived,
                                             IntensityPresetBindings bindings,
                                             boolean[] binarization,
                                             String[] thresholds,
                                             String[] filterSources,
                                             boolean[] roiZipSelected,
                                             String[] channelNames,
                                             BinConfig cfg,
                                             boolean anyRois) {
        if (derived == null || bindings == null) {
            return;
        }
        applyIntensityDerivedConfig(derived, binarization, thresholds, filterSources,
                roiZipSelected, channelNames);
        intensitySpatialConfig = derived.spatialConfig == null
                ? IntensitySpatialConfig.disabled()
                : derived.spatialConfig;
        cliConfiguredMaskIndex1Based = derived.maskChannelIndex + 1;
        setToggle(bindings.useDeconvToggle, useDeconvolvedInput);
        setToggle(bindings.roiAnalysisToggle, anyRois && anySelected(roiZipSelected));
        setToggle(bindings.intensitySpatialToggle, intensitySpatialConfig.hasConfiguration());
        if (bindings.binarizeToggles != null) {
            for (int i = 0; i < bindings.binarizeToggles.size() && i < binarization.length; i++) {
                setToggle(bindings.binarizeToggles.get(i), binarization[i]);
            }
        }
        if (bindings.filterSourceChoices != null) {
            for (int i = 0; i < bindings.filterSourceChoices.size() && i < filterSources.length; i++) {
                bindings.filterSourceChoices.get(i).setSelectedItem(
                        IntensitySetupConfig.FILTER_BASIC.equals(filterSources[i])
                                ? IntensitySetupConfig.FILTER_BASIC
                                : savedFilterChoiceLabel(i, cfg));
            }
        }
        if (bindings.primaryLabelUpdater != null) {
            bindings.primaryLabelUpdater.run();
        }
    }

    /**
     * Builds the preset row (chooser + Save + Manage) shown at the top of the
     * primary Intensity dialog. Mirrors the Spatial and 3D Object analyses:
     * selecting a saved/stock preset loads it and writes the selections into the
     * live dialog controls via {@link #applyIntensityPresetToStep0}.
     */
    private void addIntensitySetupControls(final PipelineDialog dialog,
                                           final String directory,
                                           final BinConfig cfg,
                                           final ChannelIdentities identities,
                                           final List<String> roiSetNames,
                                           final boolean[] binarization,
                                           final String[] thresholds,
                                           final String[] filterSources,
                                           final boolean[] roiZipSelected,
                                           final String[] channelNames,
                                           final boolean anyRois,
                                           final IntensityPresetBindings bindings) {
        final JComboBox<String> presetCombo =
                new JComboBox<String>(listIntensityPresetNames(directory));
        presetCombo.setMaximumSize(new java.awt.Dimension(260, 24));
        if (bindings != null) {
            bindings.presetCombo = presetCombo;
        }
        JButton savePreset = new JButton("Save as preset...");
        flash.pipeline.ui.FlashIcons.apply(savePreset, flash.pipeline.ui.FlashIcons.save());
        savePreset.setToolTipText("Save the current Fluorescence Intensity options as a named preset.");
        savePreset.addActionListener(e -> handleSaveIntensityPreset(directory, channelNames,
                roiSetNames, binarization, thresholds, filterSources, roiZipSelected, bindings));
        JButton managePreset = new JButton("Manage...");
        managePreset.setToolTipText("Delete saved Fluorescence Intensity presets.");
        managePreset.addActionListener(e -> {
            boolean changed = flash.pipeline.ui.config.PresetManagerDialog.manage(
                    presetCombo, new IntensityPresetIO(new File(directory)), "Manage Intensity Presets");
            if (changed) {
                refreshIntensityPresetChoice(directory, bindings, INTENSITY_PRESET_PLACEHOLDER);
            }
        });
        JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        row.setOpaque(false);
        row.add(presetCombo);
        row.add(savePreset);
        row.add(managePreset);
        presetCombo.addActionListener(e -> {
            if (bindings != null && bindings.programmaticChange) {
                return;
            }
            Object selected = presetCombo.getSelectedItem();
            if (selected != null && !INTENSITY_PRESET_PLACEHOLDER.equals(String.valueOf(selected))) {
                IntensitySetupConfig.DerivedConfig derived = loadIntensityPresetConfig(
                        directory, cfg, identities, roiSetNames, String.valueOf(selected));
                if (derived != null) {
                    applyIntensityPresetToStep0(derived, bindings, binarization, thresholds,
                            filterSources, roiZipSelected, channelNames, cfg, anyRois);
                }
            }
        });
        dialog.addComponent(row);
    }

    private String[] listIntensityPresetNames(String directory) {
        List<String> labels = new ArrayList<String>();
        labels.add(INTENSITY_PRESET_PLACEHOLDER);
        try {
            List<IntensityPreset> presets = new IntensityPresetIO(new File(directory)).listAll();
            for (IntensityPreset preset : presets) {
                labels.add(preset.getName());
            }
        } catch (IOException e) {
            IJ.log("WARNING: Could not list Intensity presets: " + e.getMessage());
        }
        return labels.toArray(new String[labels.size()]);
    }

    private void refreshIntensityPresetChoice(String directory,
                                              IntensityPresetBindings bindings,
                                              String selectedName) {
        if (bindings == null || bindings.presetCombo == null) {
            return;
        }
        bindings.programmaticChange = true;
        try {
            bindings.presetCombo.removeAllItems();
            for (String presetName : listIntensityPresetNames(directory)) {
                bindings.presetCombo.addItem(presetName);
            }
            bindings.presetCombo.setSelectedItem(selectedName == null
                    ? INTENSITY_PRESET_PLACEHOLDER
                    : selectedName);
        } finally {
            bindings.programmaticChange = false;
        }
    }

    private void handleSaveIntensityPreset(String directory,
                                           String[] channelNames,
                                           List<String> roiSetNames,
                                           boolean[] binarization,
                                           String[] thresholds,
                                           String[] filterSources,
                                           boolean[] roiZipSelected,
                                           IntensityPresetBindings bindings) {
        if (headless || suppressDialogs) {
            return;
        }
        if (bindings == null) {
            IJ.showMessage("Intensity Analysis", "Could not save preset: dialog options are not available.");
            return;
        }
        String name = JOptionPane.showInputDialog(
                bindings.presetCombo,
                "Preset name:",
                "Save Intensity Preset",
                JOptionPane.PLAIN_MESSAGE);
        if (name == null) {
            return;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            IJ.showMessage("Intensity Analysis", "Preset name cannot be empty.");
            return;
        }
        try {
            IntensityPreset preset = buildIntensityPresetFromBindings(trimmed, channelNames,
                    roiSetNames, binarization, thresholds, filterSources, roiZipSelected, bindings);
            new IntensityPresetIO(new File(directory)).save(preset);
            IJ.log("Saved Intensity preset: " + trimmed);
            refreshIntensityPresetChoice(directory, bindings, preset.getName());
        } catch (IOException e) {
            IJ.showMessage("Intensity Analysis", "Could not save preset: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            IJ.showMessage("Intensity Analysis", "Could not save preset: " + e.getMessage());
        }
    }

    private IntensityPreset buildIntensityPresetFromBindings(String name,
                                                             String[] channelNames,
                                                             List<String> roiSetNames,
                                                             boolean[] binarization,
                                                             String[] thresholds,
                                                             String[] filterSources,
                                                             boolean[] roiZipSelected,
                                                             IntensityPresetBindings bindings) {
        Map<String, String> channelModes = new LinkedHashMap<String, String>();
        Map<String, String> thresholdMap = new LinkedHashMap<String, String>();
        Map<String, String> filterSourceMap = new LinkedHashMap<String, String>();
        for (int c = 0; c < channelNames.length; c++) {
            boolean binarize = bindings != null && bindings.binarizeToggles != null
                    && c < bindings.binarizeToggles.size()
                    ? isSelected(bindings.binarizeToggles.get(c))
                    : c < binarization.length && binarization[c];
            channelModes.put(channelNames[c], binarize
                    ? IntensitySetupConfig.MODE_THRESHOLD_MEAN
                    : IntensitySetupConfig.MODE_WHOLE_ROI_MEAN);
            if (c < thresholds.length && thresholds[c] != null && !thresholds[c].trim().isEmpty()) {
                thresholdMap.put(channelNames[c], thresholds[c].trim());
            }
            filterSourceMap.put(channelNames[c], selectedFilterSource(bindings, c, filterSources));
        }

        ToggleSwitch roiToggle = bindings == null ? null : bindings.roiAnalysisToggle;
        String maskHint = null;
        if (isSelected(roiToggle)
                && cliConfiguredMaskIndex1Based > 0
                && cliConfiguredMaskIndex1Based <= channelNames.length) {
            maskHint = channelNames[cliConfiguredMaskIndex1Based - 1];
        }

        List<String> roiHints = new ArrayList<String>();
        if (isSelected(roiToggle) && roiSetNames != null) {
            for (int i = 0; i < roiSetNames.size() && i < roiZipSelected.length; i++) {
                if (roiZipSelected[i]) {
                    roiHints.add(roiSetNames.get(i));
                }
            }
        }

        return new IntensityPreset(
                name,
                "Saved from Intensity Analysis dialog",
                IntensityPreset.CURRENT_LIBRARY_VERSION,
                "custom",
                IntensitySetupConfig.MODE_WHOLE_ROI_MEAN,
                channelModes,
                thresholdMap,
                maskHint,
                roiHints,
                intensitySpatialConfig == null
                        ? IntensitySpatialConfig.disabled()
                        : intensitySpatialConfig,
                filterSourceMap);
    }

    private static String selectedFilterSource(IntensityPresetBindings bindings,
                                                int channelIndex,
                                                String[] filterSources) {
        if (bindings != null && bindings.filterSourceChoices != null
                && channelIndex < bindings.filterSourceChoices.size()) {
            Object selected = bindings.filterSourceChoices.get(channelIndex).getSelectedItem();
            return IntensitySetupConfig.FILTER_BASIC.equals(selected)
                    ? IntensitySetupConfig.FILTER_BASIC
                    : IntensitySetupConfig.FILTER_BIN;
        }
        if (filterSources != null && channelIndex < filterSources.length) {
            return IntensitySetupConfig.FILTER_BASIC.equals(filterSources[channelIndex])
                    ? IntensitySetupConfig.FILTER_BASIC
                    : IntensitySetupConfig.FILTER_BIN;
        }
        return IntensitySetupConfig.FILTER_BIN;
    }

    private static boolean isSelected(ToggleSwitch toggle) {
        return toggle != null && toggle.isSelected();
    }

    private static String[] intensityWorkflow(boolean roiThresholds, boolean spatial) {
        List<String> steps = new ArrayList<String>();
        steps.add("Setup");
        if (roiThresholds) {
            steps.add("ROI & Thresholds");
        }
        if (spatial) {
            steps.add("Intensity-Spatial");
        }
        steps.add("Run");
        return steps.toArray(new String[steps.size()]);
    }

    private static int workflowStepIndex(String[] workflow, String stepName) {
        if (workflow == null || stepName == null) {
            return 0;
        }
        for (int i = 0; i < workflow.length; i++) {
            if (stepName.equals(workflow[i])) {
                return i;
            }
        }
        return 0;
    }

    private void applyCliIntensityConfiguration(String directory,
                                                BinConfig cfg,
                                                ChannelIdentities identities,
                                                List<String> roiSetNames,
                                                boolean[] binarization,
                                                String[] thresholds,
                                                String[] filterSources,
                                                boolean[] roiZipSelected,
                                                String[] channelNames) {
        if (cliConfig == null || cliConfig.getIntensity() == null || !cliConfig.getIntensity().hasConfiguration()) {
            return;
        }
        cliConfiguredMaskIndex1Based = -1;
        intensitySpatialConfig = IntensitySpatialConfig.disabled();
        CLIConfig.IntensityConfig intensity = cliConfig.getIntensity();
        IntensitySetupConfig.DerivedConfig derived = null;
        if (intensity.getPresetName() != null && !intensity.getPresetName().trim().isEmpty()) {
            derived = loadIntensityPresetConfig(directory, cfg, identities, roiSetNames, intensity.getPresetName());
        }
        if (derived == null) {
            derived = IntensitySetupConfig.deriveConfig(cfg, identities,
                    Collections.<String, Object>emptyMap(), roiSetNames);
        }
        applyCliIntensityOverrides(derived, intensity);
        derived.spatialConfig = intensity.mergeSpatialConfig(derived.spatialConfig,
                derived.binarization.length, derived.binarization,
                new IntensitySpatialConfig.LockLogger() {
                    public void log(String message) {
                        IJ.log("[CLI] " + message);
                    }
                });
        intensitySpatialConfig = derived.spatialConfig;
        applyIntensityDerivedConfig(derived, binarization,
                thresholds, filterSources, roiZipSelected, channelNames);
        cliConfiguredMaskIndex1Based = derived.maskChannelIndex + 1;
        IJ.log("[CLI] Intensity Analysis configured using "
                + (intensity.getPresetName() == null ? "explicit intensity.* flags" : "intensity.preset=" + intensity.getPresetName()));
    }

    private static void applyCliIntensityOverrides(IntensitySetupConfig.DerivedConfig derived,
                                                   CLIConfig.IntensityConfig intensity) {
        if (derived == null || intensity == null) return;
        for (Map.Entry<Integer, String> entry : intensity.getThresholds().entrySet()) {
            if (entry.getKey() == null) continue;
            int index = entry.getKey().intValue();
            if (index >= 0 && index < derived.thresholds.length) {
                derived.thresholds[index] = entry.getValue();
            }
        }
    }

    private static void applyIntensityDerivedConfig(IntensitySetupConfig.DerivedConfig derived,
                                                    boolean[] binarization,
                                                    String[] thresholds,
                                                    String[] filterSources,
                                                    boolean[] roiZipSelected,
                                                    String[] channelNames) {
        if (derived == null) return;
        for (int c = 0; c < channelNames.length && c < derived.binarization.length; c++) {
            binarization[c] = derived.binarization[c];
            thresholds[c] = derived.thresholds[c];
            if (filterSources != null && c < filterSources.length
                    && derived.filterSources != null && c < derived.filterSources.length) {
                filterSources[c] = derived.filterSources[c];
            }
        }
        for (int r = 0; r < roiZipSelected.length && r < derived.roiSetSelected.length; r++) {
            roiZipSelected[r] = derived.roiSetSelected[r];
        }
    }

    public LoadedRunParameters.Result applyLoadedParameters(Map<String, Object> parameters) {
        LoadedRunParameters.PresetLoad<IntensityPreset> load =
                LoadedRunParameters.intensityPreset(parameters);
        configuredOptions = IntensitySetupConfig.fromPreset(
                new BinConfig(),
                new ChannelIdentities(null),
                load.payload,
                Collections.<String>emptyList());
        LoadedRunParameters.rememberLastResult(load.result);
        return load.result;
    }

    private static void setToggle(ToggleSwitch toggle, boolean selected) {
        if (toggle != null) {
            toggle.setSelected(selected);
        }
    }

    private static boolean anyToggleSelected(List<ToggleSwitch> toggles) {
        if (toggles == null) return false;
        for (ToggleSwitch toggle : toggles) {
            if (isSelected(toggle)) return true;
        }
        return false;
    }

    private static boolean anySelected(boolean[] values) {
        if (values == null) return false;
        for (boolean value : values) {
            if (value) return true;
        }
        return false;
    }

    /**
     * Builds the inline summary line shown under each filter-source dropdown.
     * Reflects the resolved filter (Bin vs Basic) and whether a threshold
     * will be handled in the next dialog for the channel at index {@code c}.
     */
    private static String buildFilterSummaryLine(int c, BinConfig cfg,
                                                 String[] channelNames,
                                                 String[] filterSources,
                                                 boolean[] binarization) {
        boolean useBin = "Bin filter".equals(filterSources[c]);
        String filterText = useBin
                ? savedFilterChoiceLabel(c, cfg)
                : "Basic background and noise removal";
        boolean needsBinarizationThreshold = binarization != null
                && c < binarization.length
                && binarization[c];
        String thrText = describeConfiguredThreshold(c, cfg, useBin, needsBinarizationThreshold);
        return channelNames[c] + ":   Filter: " + filterText
                + "<br>Threshold: " + thrText;
    }

    private static String describeConfiguredThreshold(int c, BinConfig cfg, boolean useBin,
                                                      boolean needsBinarizationThreshold) {
        String configuredThreshold = configuredThresholdToken(c, cfg);
        if (needsBinarizationThreshold) {
            if (useBin && configuredThreshold != null) {
                return thresholdDisplayText(configuredThreshold) + " (from configuration)";
            }
            return "Enter on next dialogue";
        }
        if (configuredThreshold != null) {
            return thresholdDisplayText(configuredThreshold)
                    + " (from configuration; used if Binarise is enabled)";
        }
        return "not needed unless Binarise is enabled";
    }

    private static String configuredThresholdToken(int c, BinConfig cfg) {
        if (cfg == null || c < 0 || c >= cfg.channelIntensityThresholds.size()) {
            return null;
        }
        String threshold = cfg.channelIntensityThresholds.get(c);
        if (threshold == null) {
            return null;
        }
        String trimmed = threshold.trim();
        if (trimmed.isEmpty() || "default".equalsIgnoreCase(trimmed)) {
            return null;
        }
        if (isAlgorithmicIntensityThreshold(trimmed)) {
            return trimmed;
        }
        try {
            double parsed = Double.parseDouble(trimmed);
            return !Double.isNaN(parsed) && !Double.isInfinite(parsed) ? trimmed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String thresholdDisplayText(String token) {
        return isAlgorithmicIntensityThreshold(token)
                ? ThresholdOps.describeToken(token)
                : token;
    }

    private static String savedFilterChoiceLabel(int c, BinConfig cfg) {
        return savedChannelFilterName(c, cfg) + " (" + cfg.filterMacroFilenameForChannelIndex(c) + ")";
    }

    private static String savedChannelFilterName(int c, BinConfig cfg) {
        String preset = (c < cfg.channelFilterPresets.size())
                ? cfg.channelFilterPresets.get(c) : "Default";
        if (preset == null || preset.trim().isEmpty()) {
            return "Default";
        }
        return preset.trim();
    }

    private static String buildDetailsFilterSourceLabel(int c, BinConfig cfg,
                                                        String[] filterSources,
                                                        File binDir) {
        boolean useBin = filterSources != null
                && c < filterSources.length
                && "Bin filter".equals(filterSources[c]);
        if (!useBin) {
            return "Basic background and noise removal";
        }

        String filename = cfg.filterMacroFilenameForChannelIndex(c);
        String preset = (c < cfg.channelFilterPresets.size())
                ? cfg.channelFilterPresets.get(c) : "Default";
        File macroFile = new File(binDir, filename);
        if (macroFile.exists()) {
            return preset + " (" + filename + ")";
        }
        return "Basic background and noise removal (fallback; missing " + filename + ")";
    }

    private static String resolveDetailsFilterMacroText(int c, BinConfig cfg,
                                                        String[] filterSources,
                                                        File binDir,
                                                        String basicFilterMacro) throws IOException {
        boolean useBin = filterSources != null
                && c < filterSources.length
                && "Bin filter".equals(filterSources[c]);
        if (useBin) {
            File macroFile = new File(binDir, cfg.filterMacroFilenameForChannelIndex(c));
            if (macroFile.exists()) {
                return ensureTrailingNewline(readUtf8(macroFile));
            }
        }
        return ensureTrailingNewline(basicFilterMacro);
    }

    private static String readUtf8(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String ensureTrailingNewline(String text) {
        if (text == null || text.isEmpty()) return "";
        return text.endsWith("\n") ? text : text + "\n";
    }

    private static boolean anyTrue(boolean[] values) {
        if (values == null) return false;
        for (boolean value : values) {
            if (value) return true;
        }
        return false;
    }

    private static boolean endsWithDigit(String value) {
        return value != null
                && !value.isEmpty()
                && Character.isDigit(value.charAt(value.length() - 1));
    }

    private DeferredImageSupplier wrapInputSupplier(String directory, final DeferredImageSupplier rawSupplier) throws Exception {
        if (rawSupplier == null) return null;

        final boolean useDeconv = useDeconvolvedInput;
        final File rootDir = new File(directory);
        return new DeferredImageSupplier(rawSupplier) {
            @Override
            public ImagePlus openSeries(int seriesIndex) throws Exception {
                return openResolved(seriesIndex, false);
            }

            @Override
            public ImagePlus openSeriesMaterialized(int seriesIndex) throws Exception {
                return openResolved(seriesIndex, true);
            }

            private ImagePlus openResolved(int seriesIndex, boolean materialized) throws Exception {
                // TIFF-folder mode has no sibling deconvolution layout — pass through to parent.
                if (rawSupplier.getMode() == DeferredImageSupplier.Mode.TIFF_FOLDER) {
                    return openRawSeriesRecorded(rawSupplier, seriesIndex, materialized);
                }
                File container = rawSupplier.getContainerFileForSeries(seriesIndex);
                String seriesName = rawSupplier.getSeriesName(seriesIndex);
                String baseName = baseNameForSeries(seriesName, seriesIndex);
                File inputFile = DeconvolvedInputResolver.resolveInput(rootDir, container, baseName, useDeconv);
                if (inputFile != null && !inputFile.equals(container)) {
                    AnalysisRunContext.InputHandle input = recordInputStart(inputFile, seriesIndex + 1);
                    long started = System.currentTimeMillis();
                    try {
                        ImagePlus imp = new Opener().openImage(inputFile.getAbsolutePath());
                        if (imp != null) {
                            imp.setTitle(expectedSeriesTitle(container, seriesName, seriesIndex));
                        }
                        recordInputEnd(input, imp == null ? "skipped" : "processed", started);
                        return imp;
                    } catch (RuntimeException e) {
                        recordInputEnd(input, "failed", started);
                        recordError("Failed to open deconvolved input series " + (seriesIndex + 1), e);
                        throw e;
                    }
                }
                return openRawSeriesRecorded(rawSupplier, seriesIndex, materialized);
            }
        };
    }

    private ImagePlus openRawSeriesRecorded(DeferredImageSupplier supplier,
                                            int seriesIndex,
                                            boolean materialized) throws Exception {
        File source = supplier.getContainerFileForSeries(seriesIndex);
        AnalysisRunContext.InputHandle input = recordInputStart(source, seriesIndex + 1);
        long started = System.currentTimeMillis();
        try {
            ImagePlus imp = materialized
                    ? supplier.openSeriesMaterialized(seriesIndex)
                    : supplier.openSeries(seriesIndex);
            recordInputEnd(input, imp == null ? "skipped" : "processed", started);
            return imp;
        } catch (Exception e) {
            recordInputEnd(input, "failed", started);
            recordError("Failed to open input series " + (seriesIndex + 1), e);
            throw e;
        }
    }

    private AnalysisRunContext.InputHandle recordInputStart(File file, int seriesIndex) {
        return runRecordContext == null ? null : runRecordContext.recordInputStart(file, seriesIndex, null);
    }

    /**
     * Capture the finalized interactive Intensity settings into the run record so
     * a later "Load settings from previous run" can restore them. The map mirrors
     * {@link IntensityPreset#toJsonObject()} so {@code LoadedRunParameters.INTENSITY_KEYS}
     * recognises every key. No-op when there is no run context (CLI/headless paths
     * record parameters via {@link IntensityAnalysisV2Command}).
     */
    private void recordIntensityRunParameters(String[] channelNames,
                                              boolean[] binarization,
                                              String[] thresholds,
                                              boolean roiAnalysis,
                                              int roiChannelIndex1Based,
                                              String[] roiZipNames,
                                              boolean[] roiZipSelected,
                                              IntensitySpatialConfig spatialConfig) {
        if (runRecordContext == null) {
            return;
        }
        try {
            Map<String, String> channelModes = new LinkedHashMap<String, String>();
            Map<String, String> thresholdMap = new LinkedHashMap<String, String>();
            for (int c = 0; c < channelNames.length; c++) {
                boolean binarize = c < binarization.length && binarization[c];
                channelModes.put(String.valueOf(c + 1),
                        binarize ? IntensitySetupConfig.MODE_THRESHOLD_MEAN
                                : IntensitySetupConfig.MODE_WHOLE_ROI_MEAN);
                String threshold = c < thresholds.length ? thresholds[c] : "0";
                thresholdMap.put(String.valueOf(c + 1),
                        threshold == null ? "0" : threshold);
            }

            String maskChannelHint = null;
            if (roiAnalysis && roiChannelIndex1Based > 0
                    && roiChannelIndex1Based <= channelNames.length) {
                maskChannelHint = channelNames[roiChannelIndex1Based - 1];
            }

            List<String> roiSetNameHints = new ArrayList<String>();
            if (roiAnalysis && roiZipNames != null) {
                for (int r = 0; r < roiZipNames.length; r++) {
                    boolean selected = roiZipSelected != null
                            && r < roiZipSelected.length && roiZipSelected[r];
                    if (selected && roiZipNames[r] != null) {
                        roiSetNameHints.add(roiZipNames[r]);
                    }
                }
            }

            IntensityPreset preset = new IntensityPreset(
                    "GUI Intensity run",
                    "Captured from the Fluorescence Intensity Analysis dialog",
                    IntensityPreset.CURRENT_LIBRARY_VERSION,
                    "custom",
                    IntensitySetupConfig.MODE_WHOLE_ROI_MEAN,
                    channelModes,
                    thresholdMap,
                    maskChannelHint,
                    roiSetNameHints,
                    spatialConfig == null ? IntensitySpatialConfig.disabled() : spatialConfig);
            runRecordContext.recordParameters(ParameterSnapshot.fromAnalysisPresetMap(
                    "IntensityAnalysisV2", preset.toJsonObject()));
        } catch (RuntimeException e) {
            IJ.log("[FLASH] Could not capture Intensity run parameters: " + e.getMessage());
        }
    }

    private void recordInputEnd(AnalysisRunContext.InputHandle inputHandle,
                                String status,
                                long startedMillis) {
        if (runRecordContext != null && inputHandle != null) {
            runRecordContext.recordInputEnd(inputHandle, status,
                    Math.max(0L, System.currentTimeMillis() - startedMillis));
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

    private static String baseNameForSeries(String seriesName, int seriesIndex) {
        String baseName = ImageNameParser.extractBioFormatsSeriesName(seriesName);
        if (baseName == null || baseName.trim().isEmpty()) {
            return "Series_" + (seriesIndex + 1);
        }
        return baseName.trim();
    }

    /** Find the drawn (uncropped) ROI in a zip whose binding token matches the image. */
    private static Roi findDrawnRoiByToken(Roi[] rois, String token) {
        if (rois == null || token == null) return null;
        for (Roi r : rois) {
            if (r == null) continue;
            String name = r.getName();
            if (!RoiSetImageBinding.isCropped(name)
                    && token.equals(RoiSetImageBinding.tokenOf(name))) {
                return r;
            }
        }
        return null;
    }

    private static String expectedSeriesTitle(File lifFile, String seriesName, int seriesIndex) {
        String normalized = ImageNameParser.extractBioFormatsSeriesName(seriesName);
        if (normalized == null || normalized.trim().isEmpty()) {
            normalized = "Series_" + (seriesIndex + 1);
        }
        String container = lifFile == null ? "" : lifFile.getName();
        return container.isEmpty() ? normalized : container + " - " + normalized;
    }

    /** Hides all image windows and non-Log frames (for headless mode). */
    private static void hideAllImageWindows() {
        int[] ids = WindowManager.getIDList();
        if (ids != null) {
            for (int id : ids) {
                ImagePlus imp = WindowManager.getImage(id);
                if (imp != null && imp.getWindow() != null) {
                    imp.getWindow().setVisible(false);
                }
            }
        }
        Frame[] frames = WindowManager.getNonImageWindows();
        if (frames != null) {
            for (Frame f : frames) {
                if (f != null && !"Log".equals(f.getTitle())) {
                    f.setVisible(false);
                }
            }
        }
    }

    static boolean canShowGuiDialog(boolean suppressDialogs,
                                    CLIConfig cliConfig,
                                    boolean runtimeHeadless) {
        return !suppressDialogs && cliConfig == null && !runtimeHeadless;
    }

    private boolean resolveRoiSetsBeforeConfiguration(String directory) {
        List<File> roiZips = discoverSavedRoiSets(directory);
        if (roiZips == null) {
            return false;
        }
        if (!roiZips.isEmpty()) {
            return true;
        }

        NoRoiDecision decision = promptForNoRoiDecision();
        if (decision == NoRoiDecision.DRAW_ROIS) {
            launchRoiDrawingWorkflow(directory);
            roiZips = discoverSavedRoiSets(directory);
            if (roiZips == null) {
                return false;
            }
            if (!roiZips.isEmpty()) {
                return true;
            }
            String message = "[FLASH] Intensity Analysis cancelled because no ROI sets "
                    + "were saved after Draw ROIs and Orientate Images.";
            IJ.log(message);
            recordWarn(message);
            return false;
        }
        if (decision == null || decision == NoRoiDecision.CANCEL) {
            String message = "[FLASH] Intensity Analysis cancelled because no ROI sets were found.";
            IJ.log(message);
            recordWarn(message);
            return false;
        }

        IJ.log("[FLASH] No ROI sets found. Intensity Analysis will measure full images.");
        return true;
    }

    private List<File> discoverSavedRoiSets(String directory) {
        try {
            return RoiIO.listRoiZipFiles(new File(directory));
        } catch (NoClassDefFoundError e) {
            if (PluginInstallGuard.reportMissingInternalClass("Intensity Analysis", e)) {
                return null;
            }
            throw e;
        }
    }

    private NoRoiDecision promptForNoRoiDecision() {
        return noRoiDecisionPrompt.choose();
    }

    private NoRoiDecision showNoRoiDecisionDialog() {
        if (!canShowGuiDecisionDialog()) {
            return NoRoiDecision.ANALYSE_FULL_IMAGE;
        }

        Object[] options = new Object[]{"Define ROI Sets", "Analyse Full Images"};
        int choice = JOptionPane.showOptionDialog(
                null,
                "No saved ROI sets were found for this project.\n\n"
                        + "Fluorescence Intensity Analysis can measure the full image for each image, "
                        + "but intensity values will not be restricted to a drawn region.\n\n"
                        + "To restrict measurements to regions of interest, define ROI sets before running this analysis.",
                "Fluorescence Intensity Analysis - No ROI Sets Found",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[0]);

        if (choice == 0) return NoRoiDecision.DRAW_ROIS;
        if (choice == 1) return NoRoiDecision.ANALYSE_FULL_IMAGE;
        return NoRoiDecision.CANCEL;
    }

    private boolean canShowGuiDecisionDialog() {
        return !suppressDialogs
                && cliConfig == null
                && !GraphicsEnvironment.isHeadless()
                && IJ.getInstance() != null;
    }

    private void launchRoiDrawingWorkflow(String directory) {
        roiDrawingWorkflowLauncher.launch(directory);
    }

    private void launchRoiDrawingWorkflowDirect(String directory) {
        IJ.log("[FLASH] Opening Draw ROIs and Orientate Images before Intensity Analysis.");
        DrawAndSaveROIsAnalysis roiAnalysis = new DrawAndSaveROIsAnalysis();
        roiAnalysis.setSuppressDialogs(false);
        roiAnalysis.setHeadless(false);
        roiAnalysis.setCliConfig(cliConfig);
        roiAnalysis.execute(directory);
    }

    /** Closes all image windows and non-Log text windows, leaving the Log window visible. */
    private void closeAllImagesOnly() {
        int[] ids = WindowManager.getIDList();
        if (ids != null) {
            for (int id : ids) {
                ImagePlus imp = WindowManager.getImage(id);
                if (imp != null) {
                    imp.changes = false;
                    imp.close();
                    imp.flush();
                }
            }
        }
        Frame[] frames = WindowManager.getNonImageWindows();
        if (frames != null) {
            for (Frame f : frames) {
                if (f != null && shouldCloseIntensityNonImageFrame(
                        f.getTitle(), f.getClass().getName())) {
                    f.dispose();
                }
            }
        }
    }

    static boolean shouldCloseIntensityNonImageFrame(String title, String className) {
        String safeTitle = title == null ? "" : title.trim();
        String safeClass = className == null ? "" : className.trim();
        if ("Log".equals(safeTitle)) return false;
        if ("ImageJ".equals(safeTitle) || "Fiji".equals(safeTitle)) return false;
        if (safeTitle.startsWith("FLASH")) return false;
        if ("ij.ImageJ".equals(safeClass)) return false;
        return "ij.text.TextWindow".equals(safeClass);
    }

    private ImagePlus applyConfiguredZSliceSubset(BinConfig cfg, int seriesIndex, ImagePlus imp, String contextLabel) {
        if (imp == null || cfg == null || !cfg.usesZSliceSubset()) return imp;
        ImagePlus subset = ZSliceOps.applyConfiguredRange(imp, cfg, seriesIndex, contextLabel);
        if (subset != null && subset != imp) {
            imp.changes = false;
            imp.close();
            imp.flush();
        }
        return subset;
    }

    private static int firstOriginalZSlice(BinConfig cfg, int seriesIndex, ImagePlus imp) {
        if (imp == null || cfg == null || !cfg.usesZSliceSubset()) return 1;
        ZSliceSelection selection = cfg.getZSliceSelection(seriesIndex);
        if (selection == null || selection.range == null) return 1;
        int actualSlices = Math.max(1, imp.getNSlices());
        if (!selection.range.isValidFor(actualSlices)) return 1;
        return selection.range.startSlice;
    }

    private static String formatDuration(long ms) {
        long seconds = ms / 1000;
        if (seconds < 60) return seconds + " Seconds";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + " Minutes";
        long hours = minutes / 60;
        return hours + " Hours";
    }

    private static void logOrientationResolution(ResolvedImageMetadata metadata) {
        IJ.log("  Orientation source: " + metadata.sourceLabel());
        IJ.log(metadata.hasTransform()
                ? "  Orientation transform applied."
                : "  Orientation transform skipped.");
    }

    private static String formatDurationCompact(long ms) {
        long seconds = ms / 1000;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        long remSec = seconds % 60;
        if (minutes < 60) return minutes + "m " + remSec + "s";
        long hours = minutes / 60;
        long remMin = minutes % 60;
        return hours + "h " + remMin + "m";
    }
}
