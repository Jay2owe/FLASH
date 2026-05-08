package flash.pipeline.analyses;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.bin.ChannelIdentities;
import flash.pipeline.bin.ChannelIdentitiesIO;
import flash.pipeline.analyses.wizard.IntensityPreset;
import flash.pipeline.analyses.wizard.IntensityPresetIO;
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
import flash.pipeline.io.DeferredImageSupplier;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.FlashProjectLayout.AnalysisFolder;
import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.io.IoUtils;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.naming.ChannelFilenameCodec;
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
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Macro-faithful rewrite of intensityAnalysis() (Jamie IHF Pipeline 2), focused on outputs.
 *
 * Output: FLASH/Image Analysis/Image Intensities/<channel>.csv and Analysis Details/<channel>.txt
 */
public class IntensityAnalysisV2 implements Analysis {

    private boolean headless = false;
    private boolean suppressDialogs = false;
    private boolean aggressiveMemory = false;
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

    @Override
    public void setHeadless(boolean headless) {
        this.headless = headless;
    }

    @Override
    public void setSuppressDialogs(boolean suppress) {
        this.suppressDialogs = suppress;
    }

    @Override
    public void setAggressiveMemory(boolean aggressive) {
        this.aggressiveMemory = aggressive;
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

        BinConfig cfg = BinConfigIO.readPartialFromDirectory(directory);
        String[] channelNames = cfg.channelNames.toArray(new String[0]);
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
        File binDir = activeConfigurationDir(directory);
        ChannelIdentities channelIdentities = ChannelIdentitiesIO.read(binDir);

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

        // Output directories
        File saveRoot = intensityWriteRoot(directory);
        try {
            IoUtils.mustMkdirs(saveRoot);
        } catch (IOException e) {
            IJ.log("[FLASH] Could not create ROI Intensities output directory: " + e.getMessage());
            return;
        }
        IJ.log("Output directory: " + saveRoot.getAbsolutePath());

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
                        roiAnalysis ? roiChannelChoice : null);
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
                    cfg.getZSliceConfig().summary());
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
            if (!headless && !suppressDialogs) {
                IJ.showMessage("Intensity Analysis", e.getMessage());
            }
            return;
        }

        IJ.log("Total images: " + totalImages);
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
        final ResultsTable[] totalTables = new ResultsTable[channelNames.length];
        for (int c = 0; c < totalTables.length; c++) totalTables[c] = new ResultsTable();

        // ── Skip-existing pre-scan: check output files BEFORE loading any pixels ──
        if (skipExisting) {
            boolean allCsvsExist = true;
            for (int c = 0; c < channelNames.length; c++) {
                if (!intensityOutputCsv(saveRoot, channelNames[c], roiAnalysis,
                        roiChannelIndex1Based, channelNames).exists()) {
                    allCsvsExist = false;
                    break;
                }
            }
            if (allCsvsExist) {
                IJ.log("All intensity output CSVs already exist — skipping entire Intensity Analysis.");
                IJ.showProgress(1.0);
                IJ.showStatus("");
                return;
            }
        }

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
            } catch (Exception ignore) { }
            loader.start();
            IJ.log("Thread split: " + effectiveLoaders + " loaders, " + safeThreads + " workers");
            compactLog = true;
            processImagesParallel(loader, directory, cfg, safeThreads, channelNames, binarization, thresholds,
                    roiAnalysis, roiZips, roiZipNames, roiZipSelected, roiChannelIndex1Based,
                    filterSources, binDir,
                    basicFilterMacro, saveRoot, totalTables, analysisStartTime, preloadedRoiSets);
            compactLog = false;
        } else {
            compactLog = false;
            processImagesSequential(supplier, totalImages, directory, cfg, channelNames, binarization, thresholds,
                    roiAnalysis, roiZips, roiZipNames, roiZipSelected, roiChannelIndex1Based,
                    filterSources, binDir,
                    basicFilterMacro, saveRoot, totalTables, analysisStartTime, preloadedRoiSets);
        }

        // Export one CSV per channel
        IJ.log("__________________________________________________________");
        IJ.log("Saving results...");
        for (int c = 0; c < channelNames.length; c++) {
            try {
                File outCsv = intensityOutputCsv(saveRoot, channelNames[c], roiAnalysis,
                        roiChannelIndex1Based, channelNames);
                totalTables[c].save(outCsv.getAbsolutePath());
                IJ.log("  Saved: " + outCsv.getName() + " (" + totalTables[c].size() + " rows)");
            } catch (Exception e) {
                IJ.log("  WARNING: failed saving intensity CSV for " + channelNames[c] + ": " + e.getMessage());
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
            String basicFilterMacro, File saveRoot,
            ResultsTable[] totalTables, long analysisStartTime, Roi[][] preloadedRoiSets
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

            // Skip Existing check
            if (skipExisting) {
                boolean allExist = true;
                for (int c = 0; c < channelNames.length; c++) {
                    if (!intensityOutputCsv(saveRoot, channelNames[c], roiAnalysis,
                            roiChannelIndex1Based, channelNames).exists()) {
                        allExist = false;
                        break;
                    }
                }
                if (allExist) {
                    IJ.log("__________________________________________________________");
                    IJ.log("Image Stack " + (idx + 1) + "/" + totalImages + ": " + imp.getTitle());
                    IJ.log("  Files exist, skipping...");
                    imp.changes = false;
                    imp.close();
                    imp.flush();
                    continue;
                }
            }

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

            try {
                ImagePlus[] chans = ChannelSplitter.split(imp);
                int n = Math.min(chans.length, channelNames.length);
                IJ.log("  Channels split: " + n);

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
                                totalTables, idx + 1, roiZipNames[rSet],
                                cfg, filterSources, binDir,
                                basicFilterMacro, activeRoi);
                    }
                } else {
                    runIntensityMeasurementsForThisImage(
                            parts, chans, n,
                            binarization, thresholds,
                            channelNames, -1,
                            totalTables, idx + 1, null,
                            cfg, filterSources, binDir,
                            basicFilterMacro, null);
                }

            } finally {
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

                if (aggressiveMemory) {
                    if (verboseLogging) IJ.log("  [DEBUG] Aggressive memory clearing...");
                    System.gc();
                    IJ.freeMemory();
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
            final String basicFilterMacro, final File saveRoot,
            final ResultsTable[] totalTables, final long analysisStartTime,
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

                            // Skip Existing check
                            if (skipExisting) {
                                boolean allExist = true;
                                for (int c = 0; c < channelNames.length; c++) {
                                    if (!intensityOutputCsv(saveRoot, channelNames[c], roiAnalysis,
                                            roiChannelIndex1Based, channelNames).exists()) {
                                        allExist = false;
                                        break;
                                    }
                                }
                                if (allExist) {
                                    IJ.log("[" + (idx + 1) + "/" + total + "] " + imp.getTitle() + " -- skipped (exists)");
                                    completed.incrementAndGet();
                                    continue;
                                }
                            }

                            long imageStartTime = System.currentTimeMillis();

                            if (!compactLog) {
                                IJ.log("[" + (idx + 1) + "/" + total + "] Processing: " + imp.getTitle());
                            }

                            logOrientationResolution(metadata);
                            OrientationOps.applyTransform(imp, metadata);

                            ImagePlus[] chans = ChannelSplitter.split(imp);
                            int n = Math.min(chans.length, channelNames.length);

                            // Per-image local tables to collect results before merging
                            ResultsTable[] localTables = new ResultsTable[channelNames.length];
                            for (int c = 0; c < localTables.length; c++) localTables[c] = new ResultsTable();

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
                                            localTables, idx + 1, roiZipNames[rSet],
                                            cfg, filterSources, binDir,
                                            basicFilterMacro, activeRoi);
                                }
                            } else {
                                runIntensityMeasurementsForThisImage(
                                        parts, chans, n,
                                        binarization, thresholds,
                                        channelNames, -1,
                                        localTables, idx + 1, null,
                                        cfg, filterSources, binDir,
                                        basicFilterMacro, null);
                            }

                            // Merge local tables into master totalTables
                            synchronized (totalTables) {
                                for (int c = 0; c < channelNames.length; c++) {
                                    ResultsTable local = localTables[c];
                                    ResultsTable master = totalTables[c];
                                    for (int r = 0; r < local.size(); r++) {
                                        master.incrementCounter();
                                        int row = master.size() - 1;
                                        String[] headings = local.getHeadings();
                                        for (int h = 0; h < headings.length; h++) {
                                            String heading = headings[h];
                                            // Try string first for Animal Name
                                            String sv = local.getStringValue(heading, r);
                                            if (sv != null && !sv.isEmpty()) {
                                                master.setValue(heading, row, sv);
                                            } else {
                                                master.setValue(heading, row, local.getValue(heading, r));
                                            }
                                        }
                                    }
                                }
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

                            if (aggressiveMemory) {
                                System.gc();
                                IJ.freeMemory();
                            }
                        } catch (Exception e) {
                            IJ.log("[" + (idx + 1) + "/" + total + "] ERROR: " + e.getMessage());
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
                IJ.log("Parallel processing error: " + e.getMessage());
            }
        }
        pool.shutdown();
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
            ResultsTable[] totalTables,
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
            double maskThreshold;
            try {
                maskThreshold = Double.parseDouble(thresholds[mc]);
            } catch (NumberFormatException ex) {
                if (!compactLog) IJ.log("    - ROI mask: WARN unparseable threshold '"
                        + thresholds[mc] + "' for " + channelNames[mc] + ", using 0");
                maskThreshold = 0.0;
            }
            maskStack = createRoiChannelMask(chans[mc],
                    filterMacroOrPath, isMacroFile, maskThreshold, !compactLog, maskFilterLabel);
        }

        // Parallelize per-channel processing: each channel's filter + threshold + measure
        // is independent. The maskStack is read-only (safe to share), and each channel
        // creates its own duplicate images.
        final int channelCount = n;
        int channelThreads = Math.min(channelCount, Math.max(1, parallelThreads));

        // Per-channel result holders (pre-allocated slots, one per channel — no contention)
        final double[][] allSignalIntDen = new double[channelCount][];
        final double[][] allAreaFrac = new double[channelCount][];
        final double[][] allRawIntDen = new double[channelCount][];

        // Capture effectively-final references for inner class access
        final ImagePlus finalMaskStack = maskStack;
        final Roi finalRoi = roi;
        final NameParts finalParts = parts;
        final boolean finalVerboseLogging = verboseLogging;

        if (channelThreads > 1) {
            // Parallel channel processing
            ExecutorService channelPool = Executors.newFixedThreadPool(channelThreads);
            List<Future<?>> channelFutures = new ArrayList<Future<?>>();

            for (int ci = 0; ci < channelCount; ci++) {
                final int c = ci;
                channelFutures.add(channelPool.submit(new Runnable() {
                    @Override
                    public void run() {
                        processOneChannel(c, channelCount, chans, channelNames,
                                binarization, thresholds,
                                cfg, filterSources, binDir, basicFilterMacro,
                                finalMaskStack, finalRoi, finalParts, finalVerboseLogging,
                                allSignalIntDen, allAreaFrac, allRawIntDen);
                    }
                }));
            }

            // Wait for all channels to complete
            for (Future<?> f : channelFutures) {
                try {
                    f.get();
                } catch (Exception e) {
                    IJ.log("    ERROR in parallel channel processing: " + e.getMessage());
                }
            }
            channelPool.shutdown();
        } else {
            // Sequential channel processing (single thread)
            for (int c = 0; c < channelCount; c++) {
                processOneChannel(c, channelCount, chans, channelNames,
                        binarization, thresholds,
                        cfg, filterSources, binDir, basicFilterMacro,
                        finalMaskStack, finalRoi, finalParts, finalVerboseLogging,
                        allSignalIntDen, allAreaFrac, allRawIntDen);
            }
        }

        // Compute ROI label matching ThreeDObjectAnalysis conventions so metadata
        // columns are identical across analyses (Region, Hemisphere, ROI, Animal Name).
        // scnIndex is folded into roiLabel (e.g. "SCN5") — no separate SCN column.
        String roiBase = roiSetName == null ? "" : roiSetName;
        String roiLabel = parts == null
                ? (scnIndex1Based > 0 && !roiBase.matches(".*\\d$") ? roiBase + scnIndex1Based : roiBase)
                : parts.analysisRegionLabel(roiBase, scnIndex1Based);

        // Merge per-channel results into totalTables in order (single-threaded, no contention)
        for (int c = 0; c < channelCount; c++) {
            if (allSignalIntDen[c] == null) continue; // channel failed
            ResultsTable total = totalTables[c];
            int len = allSignalIntDen[c].length;
            for (int r = 0; r < len; r++) {
                total.incrementCounter();
                int row = total.size() - 1;

                total.setValue("Region", row, parts.csvRegion());
                total.setValue("Hemisphere", row, parts.hemisphere);
                total.setValue("ROI", row, roiLabel);
                total.setValue("Animal Name", row, parts.animal);
                writeMeasurementColumns(total, row, allSignalIntDen[c][r],
                        allAreaFrac[c][r], allRawIntDen[c][r]);
            }
            if (!compactLog) IJ.log("    - Channel " + channelNames[c] + ": " + len + " rows merged to results table");
        }

        if (maskStack != null) {
            maskStack.changes = false;
            maskStack.close();
            maskStack.flush();
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
            NameParts parts, boolean verbose,
            double[][] outSignalIntDen, double[][] outAreaFrac, double[][] outRawIntDen
    ) {
        long chStart = verbose ? System.currentTimeMillis() : 0;
        if (!compactLog) IJ.log("  > Channel " + (c + 1) + "/" + n + ": " + channelNames[c]);

        ImagePlus raw = ImageOps.duplicateThreadSafe(chans[c]);
        raw.setTitle(channelNames[c] + "_raw");

        // Create single filtered copy — used for both measurement and binarization
        ImagePlus filtered = ImageOps.duplicateThreadSafe(chans[c]);
        filtered.setTitle(channelNames[c] + "_filtered");

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

        ImagePlus ch = filtered;

        // Binary duplicate from already-filtered image (no need to re-filter)
        ImagePlus binary = ImageOps.duplicateThreadSafe(filtered);
        binary.setTitle(channelNames[c] + "_binary");

        if (binarization[c]) {
            if (!compactLog) IJ.log("    - Binarization: applying threshold=" + thresholds[c]);
            double thr = Double.parseDouble(thresholds[c]);

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
                and.setTitle(channelNames[c]);
                ch.changes = false;
                ch.close();
                ch.flush();
                ch = and;
                if (!compactLog) IJ.log("    - Binarization AND applied with raw image");
            }
        } else {
            if (!compactLog) IJ.log("    - Binarization: skipped");
        }

        if (maskStack != null) {
            if (!compactLog) IJ.log("    - Applying ROI channel mask");
            ImagePlus masked = applyRoiChannelMask(maskStack, ch);
            if (masked != null) {
                masked.setTitle(channelNames[c]);
                ch.changes = false;
                ch.close();
                ch.flush();
                ch = masked;
                if (!compactLog) IJ.log("    - ROI channel mask applied");
            }
        }

        // Thread-safe measurement using ThreadSafeMeasure
        Roi measureRoi = roi != null ? (Roi) roi.clone() : null;
        ThreadSafeMeasure.SliceResult[] sliceResults = ThreadSafeMeasure.measureAllSlices(ch, raw, measureRoi);
        double[] signalIntDen = new double[sliceResults.length];
        double[] areaFrac = new double[sliceResults.length];
        double[] rawIntDen = new double[sliceResults.length];
        for (int sr = 0; sr < sliceResults.length; sr++) {
            signalIntDen[sr] = sliceResults[sr].intDen;
            areaFrac[sr] = sliceResults[sr].areaFraction;
            rawIntDen[sr] = sliceResults[sr].rawIntDen;
        }
        if (!compactLog) IJ.log("    - Measured signal + raw: " + sliceResults.length + " slices");

        // Store results in pre-allocated array slots (no synchronization needed — one slot per channel)
        outSignalIntDen[c] = signalIntDen;
        outAreaFrac[c] = areaFrac;
        outRawIntDen[c] = rawIntDen;

        if (verbose) {
            IJ.log("    [DEBUG] Channel " + channelNames[c] + " processing time: "
                    + (System.currentTimeMillis() - chStart) + " ms");
            IJ.log("    [DEBUG] Binarization applied: " + binarization[c]
                    + (binarization[c] ? " (threshold=" + thresholds[c] + ")" : ""));
            IJ.log("    [DEBUG] ROI mask applied: " + (roi != null));
        }

        // Clean up channel-local images
        if (binary != null) {
            binary.changes = false;
            binary.close();
            binary.flush();
        }
        raw.changes = false;
        raw.close();
        raw.flush();
        ch.changes = false;
        ch.close();
        ch.flush();
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

        ij.ImageStack roiStack = roiCh.getStack();
        for (int s = 1; s <= roiStack.getSize(); s++) {
            ij.process.ImageProcessor ip = roiStack.getProcessor(s);
            for (int p = 0; p < ip.getWidth() * ip.getHeight(); p++) {
                ip.set(p, ip.getf(p) >= threshold ? 255 : 0);
            }
        }
        if (log) IJ.log("    - ROI mask: filter applied + threshold " + (int) threshold);
        return roiCh;
    }

    static ImagePlus applyRoiChannelMask(ImagePlus maskStack, ImagePlus measurement) {
        if (maskStack == null || measurement == null) return measurement;
        return ImageCalcOps.andStackThreadSafe(maskStack, measurement);
    }

    static void writeMeasurementColumns(ResultsTable table,
                                        int row,
                                        double signalIntDen,
                                        double areaFraction,
                                        double rawIntDen) {
        if (table == null) return;
        table.setValue("IntDen", row, signalIntDen);
        table.setValue("%Area", row, areaFraction);
        table.setValue("RawIntDen", row, rawIntDen);
    }

    static File intensityWriteRoot(String directory) {
        return FlashProjectLayout.forDirectory(directory).analysisWriteDir(AnalysisFolder.INTENSITY);
    }

    static List<File> intensityReadRoots(String directory) {
        return FlashProjectLayout.forDirectory(directory).analysisReadDirs(AnalysisFolder.INTENSITY);
    }

    static File intensityOutputCsv(File saveRoot, String channelName,
                                   boolean roiAnalysis, int roiChannelIndex1Based,
                                   String[] channelNames) {
        String saveName = ChannelFilenameCodec.toSafe(channelName);
        if (roiAnalysis && roiChannelIndex1Based > 0
                && channelNames != null
                && roiChannelIndex1Based <= channelNames.length) {
            saveName = saveName + " in "
                    + ChannelFilenameCodec.toSafe(channelNames[roiChannelIndex1Based - 1])
                    + " ROI";
        }
        return new File(saveRoot, saveName + ".csv");
    }

    private static File activeConfigurationDir(String directory) {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        File existing = layout.existingConfigurationDir();
        return existing == null ? layout.configurationWriteDir() : existing;
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
                if (f != null && !"Log".equals(f.getTitle())) {
                    f.dispose();
                }
            }
        }
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
