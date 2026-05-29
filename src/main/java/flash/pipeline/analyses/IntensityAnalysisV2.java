package flash.pipeline.analyses;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.bin.ChannelConfigIO;
import flash.pipeline.bin.ChannelIdentities;
import flash.pipeline.analyses.wizard.IntensityPreset;
import flash.pipeline.analyses.wizard.IntensityPresetIO;
import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import flash.pipeline.analyses.wizard.IntensitySpatialWizard;
import flash.pipeline.analyses.wizard.IntensityWizard;
import flash.pipeline.bin.BinField;
import flash.pipeline.bin.BinSetupDispatcher;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.deconv.DeconvolvedInputResolver;
import flash.pipeline.image.AdaptiveParallelism;
import flash.pipeline.image.FilterExecutor;
import flash.pipeline.image.ImageCalcOps;
import flash.pipeline.image.ImageOps;
import flash.pipeline.image.NamedFilterLoader;
import flash.pipeline.image.OrientationOps;
import flash.pipeline.image.ParallelContext;
import flash.pipeline.image.ThreadSafeMeasure;
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
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.FeatureDependencyGate;
import flash.pipeline.runtime.PluginInstallGuard;
import flash.pipeline.roi.RoiIO;
import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.zslice.ZSliceOps;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.measure.ResultsTable;
import ij.plugin.ChannelSplitter;
import ij.gui.Roi;
import ij.io.Opener;


import javax.swing.JTextField;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Macro-faithful rewrite of intensityAnalysis(), focused on outputs.
 *
 * Output: FLASH/Results/Tables/Intensity/&lt;channel&gt;.csv and intensity-analysis details.
 */
public class IntensityAnalysisV2 implements Analysis {

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
    private final AtomicBoolean stalePluginWarningShown = new AtomicBoolean(false);

    @Override
    public Set<BinField> requiredBinFields() {
        return EnumSet.of(
                BinField.CHANNEL_NAMES,
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

    @Override
    public void execute(String directory) {
        if (!FeatureDependencyGate.gate(DependencyId.BIO_FORMATS_RUNTIME,
                "Fluorescence Intensity Analysis", "Bio-Formats image loading")) {
            return;
        }

        BinSetupDispatcher.Outcome outcome = BinSetupDispatcher.ensure(
                directory, "Intensity Analysis", requiredBinFields(),
                benefitsFromRois(), suppressDialogs, cliConfig);
        if (outcome == BinSetupDispatcher.Outcome.CANCELLED) {
            IJ.log("[FLASH] Intensity Analysis cancelled by user.");
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
            IJ.log("ERROR: Intensity filter resource not found in JAR.");
            IJ.error("Intensity Analysis", "Intensity filter resource missing from plugin JAR.");
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

        // Per-channel filter source: "Bin filter" (loads Cn_Filters.ijm from .bin)
        // or "Basic background and noise removal" (intensity-filter macro). Default Bin.
        String[] filterSources = new String[channelNames.length];
        Arrays.fill(filterSources, "Bin filter");

        String roiChannelChoice = "None";
        int roiChannelIndex1Based = -1;
        applyCliIntensityConfiguration(directory, cfg, channelIdentities, roiSetNameList,
                binarization, thresholds,
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

                gd.addHelpText("Choose a filter source for each channel: "
                        + "the saved named filter loads the per-channel macro saved in the Configuration folder "
                        + "(C1_Filters.ijm, C2_Filters.ijm, ...) — same filter used by "
                        + "3D Object Analysis. \"Basic background and noise removal\" "
                        + "applies a generic Median r=2 + Subtract Background rolling=50.");

                gd.addSubHeader("Analysis Options");
                gd.addToggle("Use deconvolved stacks if available", useDeconvolvedInput);
                gd.addToggle("ROI Analysis", anyRois);
                gd.addToggle("Intensity-spatial analysis", runIntensitySpatial);

                gd.addHeader("Filter Source (per channel)");
                for (int i = 0; i < channelNames.length; i++) {
                    String savedFilterLabel = savedFilterChoiceLabel(i, cfg);
                    String[] filterSourceOptions = new String[]{
                            savedFilterLabel, "Basic background and noise removal"
                    };
                    gd.addChoice(channelNames[i] + " filter source",
                            filterSourceOptions,
                            "Bin filter".equals(filterSources[i])
                                    ? savedFilterLabel
                                    : "Basic background and noise removal");
                    gd.addHelpText(buildFilterSummaryLine(i, cfg, channelNames, filterSources,
                            binarization));
                }

                gd.addHeader("Binarise Signal (per channel)");
                gd.addHelpText("Creates a binary mask from the filtered image at a given threshold, "
                        + "then ANDs it with the raw image.");

                for (int i = 0; i < channelNames.length; i++) {
                    gd.addToggle("Binarise " + channelNames[i], binarization[i]);
                }

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
                gd2.enableBackButton();
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
                            gd2.addStringField(channelNames[i] + " threshold" + hint, thresholds[i], 8);
                        } else {
                            gd2.addMessage(channelNames[i] + " threshold: "
                                    + thresholds[i] + " (from configuration)");
                        }
                    }
                }

                if (roiAnalysis) {
                    gd2.addHeader("Select ROIs to Analyse");
                    if (!roiZips.isEmpty()) {
                        for (int r = 0; r < roiZipNames.length; r++) {
                            gd2.addToggle(roiZipNames[r], roiZipSelected[r]);
                        }
                    }
                    gd2.addHeader("Channel ROI Mask");
                    gd2.addHelpText("The chosen channel's filter and threshold are used to build the mask, "
                            + "which is then ANDed with each measurement channel. "
                            + "The chosen channel must have binarisation enabled.");
                    String[] roiChannels = new String[channelNames.length + 1];
                    roiChannels[0] = "None";
                    System.arraycopy(channelNames, 0, roiChannels, 1, channelNames.length);
                    gd2.addChoice("Channel ROI", roiChannels, roiChannelChoice);
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
            int likelyStackDepth = likelyStackDepthForSpatialWizard(directory, cfg);
            intensitySpatialConfig = intensitySpatialConfig.validateForChannelSetup(
                    channelNames.length,
                    binarization,
                    likelyStackDepth > 0 ? Integer.valueOf(likelyStackDepth) : null,
                    new IntensitySpatialConfig.LockLogger() {
                        public void log(String message) {
                            IJ.log("[FLASH] " + message);
                        }
                    });
        } else {
            if (runIntensitySpatial) {
                IntensitySpatialWizard spatialWizard = IntensitySpatialWizard.analysisChooser(
                        flash.pipeline.ui.wizard.WizardFlow.MainPanelBinding.NULL,
                        channelNames,
                        binarization,
                        likelyStackDepthForSpatialWizard(directory, cfg),
                        intensitySpatialConfig,
                        false);
                spatialWizard.run();
                if (spatialWizard.wasCancelled()) {
                    IJ.log("[FLASH] Intensity-spatial setup cancelled by user.");
                    return;
                }
                intensitySpatialConfig = spatialWizard.deriveCurrentConfig();
            } else {
                intensitySpatialConfig = IntensitySpatialConfig.disabled();
            }
        }

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
            IJ.log("  Intensity-spatial analyses: "
                    + IntensitySpatialConfig.joinAnalysisTokens(intensitySpatialConfig.getEnabledAnalyses()));
            IJ.log("  Intensity-spatial 2D source: "
                    + intensitySpatialConfig.getSpatialSourceMode().token());
            IJ.log("  Intensity-spatial native 3D: " + intensitySpatialConfig.isNative3dEnabled());
        }

        // Output directories
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        File saveRoot = layout.tablesIntensityWriteDir();
        File overlayRoot = layout.analysisImagesIntensityOverlaysDir();
        try {
            IoUtils.mustMkdirs(saveRoot);
        } catch (IOException e) {
            IJ.log("[FLASH] Could not create intensity tables output directory: " + e.getMessage());
            return;
        }
        try {
            IoUtils.mustMkdirs(overlayRoot);
        } catch (IOException e) {
            IJ.log("[FLASH] Could not create intensity overlays output directory: " + e.getMessage());
            return;
        }
        IJ.log("Tables output directory: " + saveRoot.getAbsolutePath());
        IJ.log("Overlay output directory: " + overlayRoot.getAbsolutePath());

        // Write per-channel Analysis Details (mirrors 3D Object Analysis output structure)
        File analysisDetailsDir = IntensityDetailsWriter.analysisDetailsWriteDir(new File(directory));
        try {
            IoUtils.mustMkdirs(analysisDetailsDir);
        } catch (IOException e) {
            IJ.log("[FLASH] Could not create Analysis Details directory: " + e.getMessage());
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
            } catch (Exception e) {
                IJ.log("  WARNING: failed writing intensity details for " + channelNames[c] + ": " + e.getMessage());
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
                try {
                    rois = RoiIO.loadRoisFromZip(roiZips.get(rSet));
                } catch (NoClassDefFoundError e) {
                    if (PluginInstallGuard.reportMissingInternalClass("Intensity Analysis", e)) return;
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
            if (canShowGuiDialog(suppressDialogs, cliConfig, GraphicsEnvironment.isHeadless())) {
                IJ.showMessage("Intensity Analysis", e.getMessage());
            }
            return;
        }

        IJ.log("Total images: " + totalImages);
        int outputStackDepth = likelyStackDepthForSpatialWizard(directory, cfg);
        if (headless) hideAllImageWindows();

        // Strict mode: ensure each selected ROI set has exactly 2 ROIs per image
        if (roiAnalysis) {
            IJ.log("Validating ROI sets...");
            int expected = totalImages * 2;
            for (int rSet = 0; rSet < roiZips.size(); rSet++) {
                if (!roiZipSelected[rSet] || preloadedRoiSets[rSet] == null) continue;
                int count = preloadedRoiSets[rSet].length;
                IJ.log("  ROI set '" + roiZipNames[rSet] + "': " + count + " ROIs (expected " + expected + ")");
                if (count != expected) {
                    IJ.error("Intensity Analysis",
                            "ROI set '" + roiZipNames[rSet] + "' has " + count
                                    + " ROIs but expected " + expected + " (2 per image).\n"
                                    + "Strict mode requires exactly 2 ROIs per image.");
                    return;
                }
            }
            IJ.log("  All ROI sets validated.");
        }

        // Accumulate output tables (one per channel)
        final IntensityOutputPlan outputPlan = buildOutputPlan(saveRoot, channelNames,
                roiAnalysis, roiChannelIndex1Based, intensitySpatialConfig,
                outputStackDepth, skipExisting);
        logOutputPlan(outputPlan);
        if (skipExisting && outputPlan.allSelectedOutputsSkipped()) {
            IJ.log("All selected intensity output CSVs already exist - skipping entire Intensity Analysis.");
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
                IJ.log("    - WARNING: Could not read series names for intensity loader progress in "
                        + directory + ": " + e.getMessage());
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
                boolean merged = CsvTableIO.mergeResultsTableCsv(outCsv, table, orderedColumns);
                if (!merged) {
                    CsvTableIO.writeResultsTableCsv(outCsv, table, orderedColumns);
                }
                IJ.log("  " + (merged ? "Updated existing: " : "Saved: ")
                        + outCsv.getName() + " (" + table.size() + " rows)");
            } catch (Exception e) {
                IJ.log("  WARNING: failed saving intensity CSV for "
                        + key.channelName() + " " + key.mode() + ": " + e.getMessage());
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
        if (!suppressDialogs) IJ.showMessage("Intensity Analysis", "Finished.");
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
                    IJ.log("ERROR: Failed to load prefetched image " + (idx + 1) + ": " + e.getMessage());
                    nextImage = null;
                    continue;
                }
                nextImage = null;
            } else {
                IJ.log("Loading image " + (idx + 1) + "/" + totalImages + "...");
                try {
                    imp = supplier.openSeries(idx);
                } catch (Exception e) {
                    IJ.log("ERROR: Failed to open image " + (idx + 1) + ": " + e.getMessage());
                    continue;
                }
            }
            if (imp == null) continue;
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
                    IJ.log("  WARNING: no processable channels for " + imp.getTitle() + "; skipping image.");
                    continue;
                }

                if (roiAnalysis) {
                    for (int rSet = 0; rSet < roiZips.size(); rSet++) {
                        if (!roiZipSelected[rSet]) continue;

                        IJ.log("  > ROI set: " + roiZipNames[rSet]);
                        int roiIndex = idx * 2;
                        Roi activeRoi;
                        if (preloadedRoiSets[rSet] == null || roiIndex >= preloadedRoiSets[rSet].length) {
                            IJ.error("Intensity Analysis", "ROI set '" + roiZipNames[rSet]
                                    + "' missing ROI index " + roiIndex + " for image " + (idx + 1));
                            return;
                        }
                        activeRoi = (Roi) preloadedRoiSets[rSet][roiIndex].clone();
                        IJ.log("    ROI selected: index " + (idx * 2)
                                + (activeRoi != null ? " (" + activeRoi.getTypeAsString() + ")" : ""));

                        runIntensityMeasurementsForThisImage(
                                parts, chans, n,
                                binarization, thresholds,
                                channelNames, roiChannelIndex1Based,
                                outputPlan, totalTables, idx + 1, roiZipNames[rSet],
                                cfg, filterSources, binDir,
                                basicFilterMacro, activeRoi);
                    }
                } else {
                    runIntensityMeasurementsForThisImage(
                            parts, chans, n,
                            binarization, thresholds,
                            channelNames, -1,
                            outputPlan, totalTables, idx + 1, null,
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
                                    IJ.log("[" + (idx + 1) + "/" + total
                                            + "] WARNING: no processable channels for " + imp.getTitle()
                                            + "; skipping image.");
                                    int done = completed.incrementAndGet();
                                    IJ.showProgress(done, total);
                                    continue;
                                }

                                // Per-image local tables to collect results before merging
                                IntensityOutputTables localTables = outputPlan.newTables();

                                if (roiAnalysis) {
                                    for (int rSet = 0; rSet < roiZips.size(); rSet++) {
                                        if (!roiZipSelected[rSet]) continue;

                                        // Use preloaded ROI array -- no synchronization needed
                                        int roiIndex = idx * 2;
                                        Roi activeRoi;
                                        if (preloadedRois[rSet] == null || roiIndex >= preloadedRois[rSet].length) {
                                            IJ.log("[" + (idx + 1) + "/" + total + "] ERROR: ROI set '"
                                                    + roiZipNames[rSet] + "' missing ROI index " + roiIndex);
                                            break;
                                        }
                                        activeRoi = (Roi) preloadedRois[rSet][roiIndex].clone();

                                        runIntensityMeasurementsForThisImage(
                                                parts, chans, n,
                                                binarization, thresholds,
                                                channelNames, roiChannelIndex1Based,
                                                outputPlan, localTables, idx + 1, roiZipNames[rSet],
                                                cfg, filterSources, binDir,
                                                basicFilterMacro, activeRoi);
                                    }
                                } else {
                                    runIntensityMeasurementsForThisImage(
                                            parts, chans, n,
                                            binarization, thresholds,
                                            channelNames, -1,
                                            outputPlan, localTables, idx + 1, null,
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
                IJ.log("[FLASH] Parallel worker failed outside image scope: "
                        + describeThrowable(cause));
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
            return;
        }
        IJ.log(prefix + describeThrowable(t));
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
            IJ.log("  WARNING: channel count mismatch for " + title
                    + ": image has " + actual + ", configuration has " + configured
                    + "; processing " + n + ".");
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
            final BinConfig cfg,
            final String[] filterSources,
            final File binDir,
            String basicFilterMacro,
            Roi roi
    ) {
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
            double maskThreshold = parseIntensityThreshold(thresholds, mc, channelNames,
                    null, roiSetName);
            maskStack = createRoiChannelMask(chans[mc],
                    filterMacroOrPath, isMacroFile, maskThreshold, !compactLog, maskFilterLabel);
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

        // scnIndex is folded into roiLabel (e.g. "SCN5") -- no separate SCN column.
        String roiBase = roiSetName == null ? "" : roiSetName;
        final String roiLabel = parts == null
                ? (scnIndex1Based > 0 && !endsWithDigit(roiBase) ? roiBase + scnIndex1Based : roiBase)
                : parts.analysisRegionLabel(roiBase, scnIndex1Based);

        // Capture effectively-final references for inner class access
        final ImagePlus finalMaskStack = maskStack;
        final Roi finalRoi = roi;
        final NameParts finalParts = parts;
        final boolean finalVerboseLogging = verboseLogging;
        final String finalRoiLabel = roiLabel;

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
                        finalParts, outputPlan, intensitySpatialConfig,
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
        if (!compactLog) IJ.log("  > Channel " + (c + 1) + "/" + n + ": " + channelNames[c]);

        ImagePlus raw = null;
        ImagePlus binary = null;
        ImagePlus filteredMeasurement = null;
        ImagePlus binarizedRawInMask = null;

        try {
        raw = ImageOps.duplicateThreadSafe(chans[c]);
        raw.setTitle(channelNames[c] + "_raw");

        // Create single filtered copy — used for both measurement and binarization
        ImagePlus filtered = ImageOps.duplicateThreadSafe(chans[c]);
        filtered.setTitle(channelNames[c] + "_filtered");
        filteredMeasurement = filtered;

        // Per-channel filter dispatch:
        //   "Bin filter"  → load Cn_Filters.ijm from .bin/, fall back to basic if missing
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
            double thr = parseIntensityThreshold(thresholds, c, channelNames, parts, roiLabel);

            // Thread-safe binarization
            ij.ImageStack binStack = binary.getStack();
            for (int s = 1; s <= binStack.getSize(); s++) {
                ij.process.ImageProcessor ip = binStack.getProcessor(s);
                for (int p = 0; p < ip.getWidth() * ip.getHeight(); p++) {
                    ip.set(p, ip.getf(p) >= thr ? 255 : 0);
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

        ChannelSpatialResults spatialResults = measureChannelSpatial(
                raw, binarizedRawInMask, channelNames[c], roi, roiLabel,
                parts, outputPlan, spatialConfig);
        outBaseSpatialResults[c] = spatialResults.baseResults;
        outMipSpatialResults[c] = spatialResults.mipResult;
        outNativeSpatialResults[c] = spatialResults.nativeResult;

        if (verbose) {
            IJ.log("    [DEBUG] Channel " + channelNames[c] + " processing time: "
                    + (System.currentTimeMillis() - chStart) + " ms");
            IJ.log("    [DEBUG] Binarization applied: " + binarization[c]
                    + (binarization[c] ? " (threshold=" + thresholds[c] + ")" : ""));
            IJ.log("    [DEBUG] ROI mask applied: " + (roi != null));
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
                                            IntensityOutputPlan outputPlan,
                                            IntensitySpatialConfig spatialConfig,
                                            ImagePlus[] rawImages,
                                            ImagePlus[] binarizedImages,
                                            ImagePlus[] binaryMasks,
                                            IntensitySpatialResult[][] baseResults,
                                            IntensitySpatialResult[] mipResults,
                                            IntensitySpatialResult[] nativeResults) {
        boolean has2d = hasSelected2dCrossChannelAnalysis(spatialConfig);
        boolean hasNative3d = hasSelectedNative3dCrossChannelAnalysis(spatialConfig);
        if ((!has2d && !hasNative3d)
                || channelNames == null || channelCount < 2
                || rawImages == null) {
            return;
        }

        IntensitySpatialRunner runner = IntensitySpatialRunner.standardWithProgress();
        String imageId = parts == null ? "unknown" : parts.displayLabel();
        IJ.log("    - Intensity-spatial cross-channel: " + crossChannelPlanSummary(spatialConfig)
                + " for " + imageId + roiLogSuffix(roiLabel));
        for (int c = 0; c < channelCount; c++) {
            ImagePlus sourceRaw = rawImages[c];
            if (sourceRaw == null) continue;
            String sourceName = channelNames[c];

            IntensitySpatialOutputKey baseKey = outputPlan.baseKeyForChannel(sourceName);
            boolean baseAllowed = outputPlan.shouldPopulate(baseKey)
                    && baseKey != null
                    && !baseKey.isChannelRoiMaskOutput()
                    && sourceAllowsSpatialMode(spatialConfig, IntensitySpatialOutputMode.BASE);
            if (has2d && baseAllowed) {
                int slices = Math.max(1, sourceRaw.getStackSize());
                baseResults[c] = ensureResultArray(baseResults[c], slices);
                for (int p = 0; p < channelCount; p++) {
                    if (p == c || rawImages[p] == null) continue;
                    int pairSlices = Math.min(slices, Math.max(1, rawImages[p].getStackSize()));
                    IJ.log("      Pair " + orderedPairProgress(c, p, channelCount)
                            + " " + sourceName + " -> " + channelNames[p]
                            + " base: " + pairSlices + " slices");
                    for (int s = 1; s <= pairSlices; s++) {
                        IJ.log("        Base slice [" + s + "/" + pairSlices + "]");
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
            if (has2d && outputPlan.shouldPopulate(mipKey)) {
                for (int p = 0; p < channelCount; p++) {
                    if (p == c || rawImages[p] == null) continue;
                    IJ.log("      Pair " + orderedPairProgress(c, p, channelCount)
                            + " " + sourceName + " MIP -> " + channelNames[p] + " MIP");
                    ImagePlus sourceMip = null;
                    ImagePlus sourceBinMip = null;
                    ImagePlus sourceMaskMip = null;
                    ImagePlus partnerMip = null;
                    ImagePlus partnerBinMip = null;
                    ImagePlus partnerMaskMip = null;
                    try {
                        sourceMip = IntensitySpatialRunner.maxIntensityProjection(
                                sourceRaw, sourceName + "_raw_MIP");
                        sourceBinMip = IntensitySpatialRunner.maxIntensityProjection(
                                imageAt(binarizedImages, c), sourceName + "_binarized_MIP");
                        sourceMaskMip = IntensitySpatialRunner.maxIntensityProjection(
                                imageAt(binaryMasks, c), sourceName + "_mask_MIP");
                        partnerMip = IntensitySpatialRunner.maxIntensityProjection(
                                rawImages[p], channelNames[p] + "_raw_MIP");
                        partnerBinMip = IntensitySpatialRunner.maxIntensityProjection(
                                imageAt(binarizedImages, p), channelNames[p] + "_binarized_MIP");
                        partnerMaskMip = IntensitySpatialRunner.maxIntensityProjection(
                                imageAt(binaryMasks, p), channelNames[p] + "_mask_MIP");
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
                    } finally {
                        closeImage(sourceMip);
                        closeImage(sourceBinMip);
                        closeImage(sourceMaskMip);
                        closeImage(partnerMip);
                        closeImage(partnerBinMip);
                        closeImage(partnerMaskMip);
                    }
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
                    IJ.log("      Pair " + orderedPairProgress(c, p, channelCount)
                            + " " + sourceName + " -> " + channelNames[p]
                            + " native 3D: " + pairDepth + " slices");
                    if (pairDepth < IntensitySpatialConfig.MIN_NATIVE_3D_SLICES) {
                        IJ.log("[FLASH] Intensity-spatial native 3D skipped for "
                                + imageId + " source " + sourceName
                                + " partner " + channelNames[p]
                                + ": stack has fewer than "
                                + IntensitySpatialConfig.MIN_NATIVE_3D_SLICES + " slices");
                        continue;
                    }
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
    }

    private static boolean shouldRetainCrossChannelSpatialImages(IntensityOutputPlan outputPlan,
                                                                 IntensitySpatialConfig spatialConfig,
                                                                 String channelName) {
        boolean needs2d = hasSelected2dCrossChannelAnalysis(spatialConfig);
        boolean needsNative3d = hasSelectedNative3dCrossChannelAnalysis(spatialConfig);
        if ((!needs2d && !needsNative3d) || outputPlan == null) {
            return false;
        }
        IntensitySpatialOutputKey baseKey = outputPlan.baseKeyForChannel(channelName);
        if (baseKey == null || baseKey.isChannelRoiMaskOutput()) {
            return false;
        }
        for (IntensitySpatialOutputKey selected : outputPlan.selectedKeys()) {
            if (selected == null || selected.isChannelRoiMaskOutput()) continue;
            if (needs2d
                    && (selected.mode() == IntensitySpatialOutputMode.BASE
                    || selected.mode() == IntensitySpatialOutputMode.MIP)
                    && outputPlan.shouldPopulate(selected)) {
                return true;
            }
            if (needsNative3d
                    && selected.mode() == IntensitySpatialOutputMode.NATIVE_3D
                    && outputPlan.shouldPopulate(selected)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSelected2dCrossChannelAnalysis(IntensitySpatialConfig spatialConfig) {
        if (spatialConfig == null || !spatialConfig.isEnabled()) return false;
        Set<IntensitySpatialConfig.AnalysisKey> analyses = spatialConfig.getEnabledAnalyses();
        return analyses != null
                && (analyses.contains(IntensitySpatialConfig.AnalysisKey.CROSSMARK)
                || analyses.contains(IntensitySpatialConfig.AnalysisKey.ENTROPY_MI)
                || analyses.contains(IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL));
    }

    private static boolean hasSelectedNative3dCrossChannelAnalysis(IntensitySpatialConfig spatialConfig) {
        if (spatialConfig == null || !spatialConfig.isEnabled()) return false;
        Set<IntensitySpatialConfig.AnalysisKey> analyses = spatialConfig.getEnabledAnalyses();
        return analyses != null
                && (analyses.contains(IntensitySpatialConfig.AnalysisKey.CROSSMARK_3D)
                || analyses.contains(IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL_3D));
    }

    private static boolean hasSelected2dSameChannelAnalysis(IntensitySpatialConfig spatialConfig) {
        if (spatialConfig == null || !spatialConfig.isEnabled()) return false;
        Set<IntensitySpatialConfig.AnalysisKey> analyses = spatialConfig.getEnabledAnalyses();
        if (analyses == null) return false;
        for (IntensitySpatialConfig.AnalysisKey key : analyses) {
            if (key != null && !key.isCrossChannel() && !key.isNative3d()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSelectedNative3dSameChannelAnalysis(IntensitySpatialConfig spatialConfig) {
        if (spatialConfig == null || !spatialConfig.isEnabled()) return false;
        Set<IntensitySpatialConfig.AnalysisKey> analyses = spatialConfig.getEnabledAnalyses();
        if (analyses == null) return false;
        for (IntensitySpatialConfig.AnalysisKey key : analyses) {
            if (key != null && !key.isCrossChannel() && key.isNative3d()) {
                return true;
            }
        }
        return false;
    }

    private static String sameChannelPlanSummary(IntensitySpatialConfig spatialConfig,
                                                 boolean include2d,
                                                 boolean includeNative3d) {
        List<String> parts = new ArrayList<String>();
        if (include2d) {
            String tokens = analysisTokens(spatialConfig, false, false);
            if (!tokens.isEmpty()) parts.add(sourceModeLabel(spatialConfig) + " " + tokens);
        }
        if (includeNative3d) {
            String tokens = analysisTokens(spatialConfig, false, true);
            if (!tokens.isEmpty()) parts.add("native 3D " + tokens);
        }
        return parts.isEmpty() ? "no same-channel spatial families selected" : String.join("; ", parts);
    }

    private static String crossChannelPlanSummary(IntensitySpatialConfig spatialConfig) {
        List<String> parts = new ArrayList<String>();
        String twoD = analysisTokens(spatialConfig, true, false);
        if (!twoD.isEmpty()) parts.add(sourceModeLabel(spatialConfig) + " " + twoD);
        String native3d = analysisTokens(spatialConfig, true, true);
        if (!native3d.isEmpty()) parts.add("native 3D " + native3d);
        return parts.isEmpty() ? "no cross-channel spatial families selected" : String.join("; ", parts);
    }

    private static String analysisTokens(IntensitySpatialConfig spatialConfig,
                                         boolean crossChannel,
                                         boolean native3d) {
        if (spatialConfig == null || !spatialConfig.isEnabled()
                || spatialConfig.getEnabledAnalyses() == null) {
            return "";
        }
        List<String> tokens = new ArrayList<String>();
        for (IntensitySpatialConfig.AnalysisKey key : spatialConfig.getEnabledAnalyses()) {
            if (key != null
                    && key.isCrossChannel() == crossChannel
                    && key.isNative3d() == native3d) {
                tokens.add(key.token());
            }
        }
        return String.join(", ", tokens);
    }

    private static String sourceModeLabel(IntensitySpatialConfig spatialConfig) {
        return spatialConfig != null && spatialConfig.isMipEnabled()
                ? "MIP"
                : "full z-stack";
    }

    private static String roiLogSuffix(String roiLabel) {
        return roiLabel == null || roiLabel.trim().isEmpty()
                ? ""
                : " ROI " + roiLabel.trim();
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

    private ChannelSpatialResults measureChannelSpatial(ImagePlus raw,
                                                        ImagePlus binarizedRawInMask,
                                                        String channelName,
                                                        Roi roi,
                                                        String roiLabel,
                                                        NameParts parts,
                                                        IntensityOutputPlan outputPlan,
                                                        IntensitySpatialConfig spatialConfig) {
        if (raw == null || spatialConfig == null || !spatialConfig.isEnabled()
                || spatialConfig.getEnabledAnalyses().isEmpty()) {
            return ChannelSpatialResults.empty();
        }

        boolean hasSame2d = hasSelected2dSameChannelAnalysis(spatialConfig);
        boolean hasSameNative3d = hasSelectedNative3dSameChannelAnalysis(spatialConfig);
        if (!hasSame2d && !hasSameNative3d) {
            return ChannelSpatialResults.empty();
        }

        try {
            IntensitySpatialRunner runner = IntensitySpatialRunner.standardWithProgress();
            String imageId = parts == null ? "unknown" : parts.displayLabel();
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

            IJ.log("    - Intensity-spatial same-channel [" + channelName + "]: "
                    + sameChannelPlanSummary(spatialConfig, hasSame2d && (baseAllowed || mipAllowed),
                    hasSameNative3d && nativeAllowed)
                    + " for " + imageId + roiLogSuffix(roiLabel));

            if (baseAllowed && hasSame2d) {
                int slices = Math.max(1, raw.getStackSize());
                baseResults = new IntensitySpatialResult[slices];
                IJ.log("      Base output: " + slices + " slices");
                for (int s = 1; s <= slices; s++) {
                    IJ.log("        Base slice [" + s + "/" + slices + "]");
                    baseResults[s - 1] = runner.measure(new IntensitySpatialContext(
                            spatialConfig, raw, binarizedRawInMask, s, roi,
                            IntensitySpatialOutputMode.BASE, imageId, channelName, roiLabel, null));
                }
            }

            if (mipAllowed && hasSame2d) {
                ImagePlus rawMip = null;
                ImagePlus binarizedMip = null;
                try {
                    IJ.log("      MIP output: building max-intensity projection");
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
                IJ.log("      Native 3D output: " + stackDepth + " slices");
                if (stackDepth < IntensitySpatialConfig.MIN_NATIVE_3D_SLICES) {
                    IJ.log("[FLASH] Intensity-spatial native 3D skipped for " + imageId
                            + " channel " + channelName + ": stack has fewer than "
                            + IntensitySpatialConfig.MIN_NATIVE_3D_SLICES + " slices");
                } else {
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
                && sourceAllowsSpatialMode(spatialConfig, IntensitySpatialOutputMode.BASE)) {
            int slices = Math.max(1, raw == null ? 1 : raw.getStackSize());
            baseResults = new IntensitySpatialResult[slices];
            Arrays.fill(baseResults, empty);
        }

        IntensitySpatialOutputKey mipKey = outputPlan.keyForChannelMode(
                channelName, IntensitySpatialOutputMode.MIP);
        IntensitySpatialResult mipResult = outputPlan.shouldPopulate(mipKey)
                && sourceAllowsSpatialMode(spatialConfig, IntensitySpatialOutputMode.MIP)
                ? empty
                : null;

        IntensitySpatialOutputKey nativeKey = outputPlan.keyForChannelMode(
                channelName, IntensitySpatialOutputMode.NATIVE_3D);
        IntensitySpatialResult nativeResult = outputPlan.shouldPopulate(nativeKey)
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
                    parseIntensityThreshold(thresholds, c, channelNames, null, null);
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
                parseIntensityThreshold(thresholds, index, channelNames, null, null);
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
            NumberFormatException wrapped = new NumberFormatException(
                    "Invalid intensity threshold for " + thresholdContext(channelName, parts, roiLabel)
                            + ": '" + (raw == null ? "" : raw)
                            + "' (must be a finite number >= 0).");
            wrapped.initCause(e);
            throw wrapped;
        }
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

        if (Double.isNaN(threshold) || Double.isInfinite(threshold) || threshold < 0.0) {
            throw new NumberFormatException("ROI channel mask threshold must be a finite number >= 0: "
                    + threshold);
        }

        int foregroundPixels = 0;
        ij.ImageStack roiStack = roiCh.getStack();
        for (int s = 1; s <= roiStack.getSize(); s++) {
            ij.process.ImageProcessor ip = roiStack.getProcessor(s);
            for (int p = 0; p < ip.getWidth() * ip.getHeight(); p++) {
                boolean foreground = ip.getf(p) >= threshold;
                if (foreground) foregroundPixels++;
                ip.set(p, foreground ? 255 : 0);
            }
        }
        if (log) IJ.log("    - ROI mask: filter applied + threshold " + (int) threshold);
        if (log && foregroundPixels == 0) {
            IJ.log("    - ROI mask: WARNING threshold produced an all-zero mask.");
        }
        return roiCh;
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
        table.setValue("Region", row, parts == null ? "" : parts.csvRegion());
        table.setValue("Hemisphere", row, parts == null ? "" : parts.hemisphere);
        table.setValue("ROI", row, roiLabel == null ? "" : roiLabel);
        table.setValue("Animal Name", row, parts == null ? "" : parts.animal);
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
        writeSpatialPlaceholderColumns(table, row, key, channelNames, binarization, spatialConfig);
        writeSpatialResultColumns(table, row, spatialResult);
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
        ordered.add("Hemisphere");
        ordered.add("ROI");
        ordered.add("Animal Name");

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
        Set<IntensitySpatialConfig.AnalysisKey> analyses = spatialConfig.getEnabledAnalyses();
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
                && hasNonNativeSpatialAnalysis(spatialConfig);
    }

    private static boolean shouldSelectNative3dOutput(IntensitySpatialConfig spatialConfig, int stackDepth) {
        return spatialConfig != null
                && spatialConfig.isNative3dEnabled()
                && (stackDepth < 0 || stackDepth >= IntensitySpatialConfig.MIN_NATIVE_3D_SLICES)
                && hasNativeSpatialAnalysis(spatialConfig);
    }

    private static boolean sourceAllowsSpatialMode(IntensitySpatialConfig spatialConfig,
                                                   IntensitySpatialOutputMode mode) {
        if (spatialConfig == null || mode == null) return false;
        if (mode == IntensitySpatialOutputMode.NATIVE_3D) return true;
        if (mode == IntensitySpatialOutputMode.MIP) return spatialConfig.isMipEnabled();
        return !spatialConfig.isMipEnabled();
    }

    private static boolean hasNonNativeSpatialAnalysis(IntensitySpatialConfig spatialConfig) {
        for (IntensitySpatialConfig.AnalysisKey key : spatialConfig.getEnabledAnalyses()) {
            if (!key.isNative3d()) return true;
        }
        return false;
    }

    private static boolean hasNativeSpatialAnalysis(IntensitySpatialConfig spatialConfig) {
        for (IntensitySpatialConfig.AnalysisKey key : spatialConfig.getEnabledAnalyses()) {
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

        if (analyses.contains(IntensitySpatialConfig.AnalysisKey.CROSSMARK)) {
            addCrossMarkCoreColumns(columns, source, partner);
        }
        if (analyses.contains(IntensitySpatialConfig.AnalysisKey.ENTROPY_MI)) {
            addEntropyMiColumns(columns, source, partner);
        }
        if (analyses.contains(IntensitySpatialConfig.AnalysisKey.CROSSMARK)) {
            addCrossMarkColocColumns(columns, source, partner, sourceBinarized, partnerBinarized);
        }
        if (analyses.contains(IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL)
                && partnerBinarized) {
            addDistanceShellColumns(columns, source, partner, "DistShell",
                    spatialConfig.getShellWidthUm(), spatialConfig.getShellCount());
        }
    }

    private static void addCrossMarkCoreColumns(LinkedHashSet<String> columns,
                                                String source,
                                                String partner) {
        String suffix = "_" + partner;
        columns.add(source + "_Pearson" + suffix);
        columns.add(source + "_CCFPeakDist_um" + suffix);
        columns.add(source + "_CCFPeakAmp" + suffix);
        columns.add(source + "_MarkCorrRadius_um" + suffix);
        columns.add(source + "_MarkCorrStrength" + suffix);
    }

    private static void addEntropyMiColumns(LinkedHashSet<String> columns,
                                            String source,
                                            String partner) {
        String suffix = "_" + partner;
        columns.add(source + "_NMI" + suffix);
        columns.add(source + "_MIPeakRadius_um" + suffix);
        columns.add(source + "_MIPeakStrength" + suffix);
    }

    private static void addCrossMarkColocColumns(LinkedHashSet<String> columns,
                                                 String source,
                                                 String partner,
                                                 boolean sourceBinarized,
                                                 boolean partnerBinarized) {
        String suffix = "_" + partner;
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

    private IntensityWizard.DerivedConfig loadIntensityPresetConfig(String directory,
                                                                    BinConfig cfg,
                                                                    ChannelIdentities identities,
                                                                    List<String> roiSetNames,
                                                                    String presetName) {
        try {
            IntensityPreset preset = new IntensityPresetIO(new File(directory)).load(presetName);
            return IntensityWizard.fromPreset(cfg, identities, preset, roiSetNames);
        } catch (IOException e) {
            IJ.showMessage("Intensity Analysis", "Could not load preset: " + e.getMessage());
            return null;
        }
    }

    private void applyCliIntensityConfiguration(String directory,
                                                BinConfig cfg,
                                                ChannelIdentities identities,
                                                List<String> roiSetNames,
                                                boolean[] binarization,
                                                String[] thresholds,
                                                boolean[] roiZipSelected,
                                                String[] channelNames) {
        cliConfiguredMaskIndex1Based = -1;
        intensitySpatialConfig = IntensitySpatialConfig.disabled();
        if (cliConfig == null || cliConfig.getIntensity() == null || !cliConfig.getIntensity().hasConfiguration()) {
            return;
        }
        CLIConfig.IntensityConfig intensity = cliConfig.getIntensity();
        IntensityWizard.DerivedConfig derived = null;
        if (intensity.getPresetName() != null && !intensity.getPresetName().trim().isEmpty()) {
            derived = loadIntensityPresetConfig(directory, cfg, identities, roiSetNames, intensity.getPresetName());
        }
        if (derived == null) {
            derived = IntensityWizard.deriveConfig(cfg, identities,
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
                thresholds, roiZipSelected, channelNames);
        cliConfiguredMaskIndex1Based = derived.maskChannelIndex + 1;
        IJ.log("[CLI] Intensity Analysis configured using "
                + (intensity.getPresetName() == null ? "explicit intensity.* flags" : "intensity.preset=" + intensity.getPresetName()));
    }

    private static void applyCliIntensityOverrides(IntensityWizard.DerivedConfig derived,
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

    private static void applyIntensityDerivedConfig(IntensityWizard.DerivedConfig derived,
                                                    boolean[] binarization,
                                                    String[] thresholds,
                                                    boolean[] roiZipSelected,
                                                    String[] channelNames) {
        if (derived == null) return;
        for (int c = 0; c < channelNames.length && c < derived.binarization.length; c++) {
            binarization[c] = derived.binarization[c];
            thresholds[c] = derived.thresholds[c];
        }
        for (int r = 0; r < roiZipSelected.length && r < derived.roiSetSelected.length; r++) {
            roiZipSelected[r] = derived.roiSetSelected[r];
        }
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
        String configuredThreshold = configuredNumericThreshold(c, cfg);
        if (needsBinarizationThreshold) {
            if (useBin && configuredThreshold != null) {
                return configuredThreshold + " (from configuration)";
            }
            return "Enter on next dialogue";
        }
        if (configuredThreshold != null) {
            return configuredThreshold + " (from configuration; used if Binarise is enabled)";
        }
        return "not needed unless Binarise is enabled";
    }

    private static String configuredNumericThreshold(int c, BinConfig cfg) {
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
        try {
            double parsed = Double.parseDouble(trimmed);
            return !Double.isNaN(parsed) && !Double.isInfinite(parsed) ? trimmed : null;
        } catch (NumberFormatException e) {
            return null;
        }
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
                    return materialized ? rawSupplier.openSeriesMaterialized(seriesIndex) : rawSupplier.openSeries(seriesIndex);
                }
                File container = rawSupplier.getContainerFile();
                String seriesName = rawSupplier.getSeriesName(seriesIndex);
                String baseName = baseNameForSeries(seriesName, seriesIndex);
                File inputFile = DeconvolvedInputResolver.resolveInput(rootDir, container, baseName, useDeconv);
                if (inputFile != null && !inputFile.equals(container)) {
                    ImagePlus imp = new Opener().openImage(inputFile.getAbsolutePath());
                    if (imp != null) {
                        imp.setTitle(expectedSeriesTitle(container, seriesName, seriesIndex));
                    }
                    return imp;
                }
                return materialized ? rawSupplier.openSeriesMaterialized(seriesIndex) : rawSupplier.openSeries(seriesIndex);
            }
        };
    }

    private static String baseNameForSeries(String seriesName, int seriesIndex) {
        String baseName = ImageNameParser.extractBioFormatsSeriesName(seriesName);
        if (baseName == null || baseName.trim().isEmpty()) {
            return "Series_" + (seriesIndex + 1);
        }
        return baseName.trim();
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
        if (safeTitle.startsWith("FLASH") || safeTitle.startsWith("Repeat Pipeline")) return false;
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
