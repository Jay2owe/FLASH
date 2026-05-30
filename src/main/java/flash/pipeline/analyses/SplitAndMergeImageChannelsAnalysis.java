package flash.pipeline.analyses;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.bin.BinField;
import flash.pipeline.bin.BinSetupDispatcher;
import flash.pipeline.bin.ChannelConfigIO;
import flash.pipeline.bin.ChannelIdentities;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.deconv.DeconvolvedInputResolver;
import flash.pipeline.image.AdaptiveParallelism;
import flash.pipeline.image.ImageCalcOps;
import flash.pipeline.image.ImageOps;
import flash.pipeline.image.OrientationOps;
import flash.pipeline.image.ParallelContext;
import flash.pipeline.io.AsyncImageSaver;
import flash.pipeline.io.BoundedImageLoader;
import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.io.DeferredImageSupplier;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.io.IoUtils;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.io.OmeTiffIO;
import flash.pipeline.naming.ChannelFilenameCodec;
import flash.pipeline.naming.ImageOrientationResolver;
import flash.pipeline.naming.ImageNameParser;
import flash.pipeline.naming.NameParts;
import flash.pipeline.naming.ResolvedImageMetadata;
import flash.pipeline.presentation.PresentationTileConfig;
import flash.pipeline.presentation.PresentationTileRecord;
import flash.pipeline.presentation.PresentationTileWriter;
import flash.pipeline.results.AnalysisDetailsWriter;
import flash.pipeline.results.SplitAndMergeDetailsWriter;
import flash.pipeline.runrecord.AnalysisRunContext;
import flash.pipeline.runrecord.RunRecordAware;
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.FeatureDependencyGate;
import flash.pipeline.ui.FlashIcons;
import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.ToggleSwitch;
import flash.pipeline.zslice.ZSliceOps;

import ij.IJ;
import ij.ImagePlus;
import ij.plugin.ContrastEnhancer;
import ij.process.ColorProcessor;
import ij.process.LUT;
import ij.plugin.ChannelSplitter;
import ij.plugin.ZProjector;
import ij.io.Opener;

import ij.WindowManager;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
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
import java.util.concurrent.atomic.AtomicInteger;

public class SplitAndMergeImageChannelsAnalysis implements Analysis, RunRecordAware {

    private boolean headless = false;
    private boolean suppressDialogs = false;
    private boolean verboseLogging = false;
    private boolean skipExisting = false;
    private boolean compactLog = false;
    private int parallelThreads = 1;
    private int loaderThreads = 1;
    private int loaderPercent = 0;
    private boolean useTifCache = false;
    private flash.pipeline.io.ImageCache imageCache = null;
    private flash.pipeline.report.QualityReport qualityReport = null;
    private long lifOpenTimeMs = 0;
    private final long startTimeMillis = System.currentTimeMillis();
    private boolean useDeconvolvedInput = true;
    private CLIConfig cliConfig = null;
    private AnalysisRunContext runRecordContext = null;
    private final List<PresentationTileRecord> presentationTileRecords =
            Collections.synchronizedList(new ArrayList<PresentationTileRecord>());

    @Override
    public Set<BinField> requiredBinFields() {
        return EnumSet.of(
                BinField.CHANNEL_NAMES,
                BinField.CHANNEL_COLORS,
                BinField.DISPLAY_MIN_MAX,
                BinField.Z_SLICE);
    }

    @Override
    public void setHeadless(boolean headless) {
        this.headless = headless;
    }

    @Override
    public void setSuppressDialogs(boolean suppress) {
        this.suppressDialogs = suppress;
    }

    private static void showOrLog(String title, String body) {
        if (GraphicsEnvironment.isHeadless()) {
            IJ.log("[" + title + "] " + body);
        } else {
            IJ.showMessage(title, body);
        }
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
    public void setImageCache(flash.pipeline.io.ImageCache cache) {
        this.imageCache = cache;
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
    public void setQualityReport(flash.pipeline.report.QualityReport report) {
        this.qualityReport = report;
    }

    @Override
    public void setCliConfig(CLIConfig config) {
        this.cliConfig = config;
        if (config != null) {
            this.useDeconvolvedInput = config.isSplitMergeUseDeconv();
        }
    }

    @Override
    public void setRunRecordContext(AnalysisRunContext context) {
        this.runRecordContext = context;
    }

    private static final String METHOD_NONE = "None";
    private static final String METHOD_AUTOMATIC = "Automatic";
    private static final String METHOD_MANUAL = "Manual";
    private static final String METHOD_CUSTOM = "Custom Min-Max Display Ranges";
    private static final String[] PROCESS_METHODS = {METHOD_NONE, METHOD_AUTOMATIC, METHOD_MANUAL, METHOD_CUSTOM};
    private static final String NONE_OPTION = "None";
    private static final double DEFAULT_SATURATION = 0.35;
    private static final Color CHANNEL_GRID_HELP_COLOR = new Color(117, 117, 117);
    private static final int CHANNEL_GRID_ROW_LABEL_WIDTH = 120;
    private static final int CHANNEL_GRID_CELL_WIDTH = 190;
    private static final int CHANNEL_GRID_TEXT_WIDTH = 120;
    private static final int CHANNEL_GRID_SATURATION_WIDTH = 80;
    private static final String METHOD_HELP = "None, Automatic, Manual, or Custom.";
    private static final String DISPLAY_RANGE_HELP = "Min-max, e.g. 0-4095.";
    private static final String SATURATION_HELP = "Automatic only; default 0.35.";

    private static class MainDialogResult {
        String[] processMethodPerCh;
        String[] customMinMaxPerCh;
        double[] saturationsPerCh;
        boolean createMerge;
        boolean saveOmeTiff;
        String additionalMergeSpec;
        boolean subtractBackground;
        int backgroundIndex;
        boolean[] subtractFromChannels;
        boolean useDeconvolvedInput;
        PresentationTileConfig tileConfig;
    }

    static final class ChannelSettingsGrid {
        final JPanel panel;
        final JComboBox<String>[] methodBoxes;
        final JTextField[] displayRangeFields;
        final JTextField[] saturationFields;
        final JLabel[] channelLabels;
        final JLabel[] rowLabels;
        final JLabel[][] helperLabels;

        private ChannelSettingsGrid(JPanel panel,
                                    JComboBox<String>[] methodBoxes,
                                    JTextField[] displayRangeFields,
                                    JTextField[] saturationFields,
                                    JLabel[] channelLabels,
                                    JLabel[] rowLabels,
                                    JLabel[][] helperLabels) {
            this.panel = panel;
            this.methodBoxes = methodBoxes;
            this.displayRangeFields = displayRangeFields;
            this.saturationFields = saturationFields;
            this.channelLabels = channelLabels;
            this.rowLabels = rowLabels;
            this.helperLabels = helperLabels;
        }
    }

    static final class ChannelSettingsSelections {
        final String[] processMethodPerCh;
        final String[] customMinMaxPerCh;
        final double[] saturationsPerCh;

        private ChannelSettingsSelections(String[] processMethodPerCh,
                                          String[] customMinMaxPerCh,
                                          double[] saturationsPerCh) {
            this.processMethodPerCh = processMethodPerCh;
            this.customMinMaxPerCh = customMinMaxPerCh;
            this.saturationsPerCh = saturationsPerCh;
        }
    }

    @Override
    public void execute(String directory) {
        if (!FeatureDependencyGate.gate(DependencyId.BIO_FORMATS_RUNTIME,
                "Split and Merge Image Channels", "Bio-Formats image loading and OME-TIFF writing")) {
            recordWarn("Split and Merge Image Channels requires Bio-Formats image loading and OME-TIFF writing.");
            return;
        }

        BinSetupDispatcher.Outcome outcome = BinSetupDispatcher.ensure(
                directory, "Split & Merge Image Channels", requiredBinFields(),
                benefitsFromRois(), suppressDialogs, cliConfig);
        if (outcome == BinSetupDispatcher.Outcome.CANCELLED) {
            IJ.log("[FLASH] Split & Merge cancelled by user.");
            recordWarn("Split & Merge cancelled by user.");
            return;
        }

        BinConfig binCfg = loadBinConfig(directory);
        String[] channelNames = binCfg.channelNames.toArray(new String[0]);
        String[] channelColors = binCfg.channelColors.toArray(new String[0]);
        String[] binMinMax = binCfg.channelMinMax.isEmpty()
                ? null : binCfg.channelMinMax.toArray(new String[0]);

        if (channelNames == null || channelNames.length == 0) {
            if (headless || suppressDialogs || GraphicsEnvironment.isHeadless()) {
                recordWarn("Split and Merge Image Channels needs channel names from channel_config.json. "
                        + "Run Set Up Configuration before this command.");
                IJ.log("[FLASH] Split & Merge: no channel names found in channel_config.json.");
                return;
            }
            channelNames = promptChannelNames();
        }
        if (channelNames == null) return;

        if (channelColors == null || channelColors.length < channelNames.length) {
            if (headless || suppressDialogs || GraphicsEnvironment.isHeadless()) {
                recordWarn("Split and Merge Image Channels needs channel colors from channel_config.json. "
                        + "Run Set Up Configuration before this command.");
                IJ.log("[FLASH] Split & Merge: no channel colors found in channel_config.json.");
                return;
            }
            channelColors = promptChannelColors(channelNames.length);
        }
        if (channelColors == null) return;

        int nCh = channelNames.length;

        String[] defaultMinMax = new String[nCh];
        for (int i = 0; i < nCh; i++) {
            defaultMinMax[i] = (binMinMax != null && i < binMinMax.length && binMinMax[i] != null)
                    ? binMinMax[i] : NONE_OPTION;
        }

        MainDialogResult mdr = showMainDialog(channelNames, channelColors, defaultMinMax, new File(directory));
        if (mdr == null) return;
        useDeconvolvedInput = mdr.useDeconvolvedInput;
        presentationTileRecords.clear();
        boolean hasManual = hasManualProcessing(mdr.processMethodPerCh);
        enableVisibleWindowsForManualProcessingIfNeeded(hasManual);

        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        File outRoot = layout.presentationImagesDir();
        File tifDir = OmeTiffIO.defaultOutputDir(layout);
        // TODO(results-folder-layout-plan stage 08): consolidate analysis-details
        // routing across analyses under Results/Run Records/analysis_details/.
        File detailsRoot = splitMergeAnalysisDetailsRoot(layout);
        try {
            IoUtils.mustMkdirs(outRoot);
            IoUtils.mustMkdirs(tifDir);
            IoUtils.mustMkdirs(detailsRoot);
        } catch (IOException e) {
            String message = "Could not create Split and Merge output directory: " + e.getMessage();
            IJ.log("[FLASH] " + message);
            recordWarn(message);
            return;
        }

        // Determine effective thread count early so we know which loading strategy to use
        final int effectiveThreads = (hasManual || parallelThreads <= 1) ? 1 : parallelThreads;

        // Both paths use DeferredImageSupplier; parallel path wraps it in BoundedImageLoader
        DeferredImageSupplier supplier = null;
        int totalImages;

        try {
            supplier = ImageSourceDispatcher.createSupplier(directory);
            totalImages = supplier.getTotalSeries();
            supplier = wrapInputSupplier(directory, supplier);
            supplier = wrapRunRecordSupplier(supplier);
        } catch (Exception e) {
            String message = "Split and Merge Image Channels: " + e.getMessage();
            IJ.log(message);
            recordWarn(message);
            if (!suppressDialogs) {
                showOrLog("Split and Merge Image Channels", e.getMessage());
            }
            return;
        }

        IJ.log("==========================================================");
        IJ.log("Split and Merge Image Channels Analysis");
        IJ.log("==========================================================");
        IJ.log("Directory: " + directory);
        IJ.log("Channels: " + String.join(", ", channelNames));
        IJ.log("Total image stacks to process: " + totalImages);
        IJ.log("  ");

        // Log per-channel settings summary
        IJ.log("--- Per-Channel Settings ---");
        for (int ch = 0; ch < nCh; ch++) {
            IJ.log("  " + channelNames[ch] + ": Method=" + mdr.processMethodPerCh[ch]
                    + ", Color=" + channelColors[ch]
                    + (METHOD_AUTOMATIC.equals(mdr.processMethodPerCh[ch])
                        ? ", Saturation=" + mdr.saturationsPerCh[ch] : "")
                    + (METHOD_CUSTOM.equals(mdr.processMethodPerCh[ch])
                        ? ", DisplayRange=" + mdr.customMinMaxPerCh[ch] : ""));
        }
        if (mdr.createMerge) {
            IJ.log("Merge: All channels");
        }
        if (mdr.additionalMergeSpec != null && !mdr.additionalMergeSpec.trim().isEmpty()) {
            IJ.log("Additional merges: " + mdr.additionalMergeSpec);
        }
        if (mdr.subtractBackground) {
            IJ.log("Background subtraction: Channel " + channelNames[mdr.backgroundIndex]
                    + " (index " + (mdr.backgroundIndex + 1) + ")");
            StringBuilder subtractFrom = new StringBuilder();
            for (int ch = 0; ch < nCh; ch++) {
                if (mdr.subtractFromChannels[ch] && ch != mdr.backgroundIndex) {
                    if (subtractFrom.length() > 0) subtractFrom.append(", ");
                    subtractFrom.append(channelNames[ch]);
                }
            }
            IJ.log("  Subtract from: " + subtractFrom.toString());
        }
        IJ.log("  ");

        String[] processingNotes = _SplitMergeNotes.computeProcessingNotes(
                mdr.processMethodPerCh, mdr.customMinMaxPerCh, mdr.saturationsPerCh, nCh);

        // ── Pre-flight skip: build worklist of series that still need work ──
        List<Integer> indicesToProcess;
        List<String> seriesNames = null;
        if (skipExisting) {
            indicesToProcess = buildPreflightWorklist(directory, supplier, totalImages,
                    channelNames, outRoot);
            int skipped = totalImages - indicesToProcess.size();
            if (skipped > 0) {
                IJ.log("Skip Existing: " + skipped + " of " + totalImages
                        + " series already have outputs — scheduling " + indicesToProcess.size());
            }
            if (indicesToProcess.isEmpty()) {
                IJ.log("All " + totalImages + " series already have outputs — nothing to process.");
            }
        } else {
            indicesToProcess = new ArrayList<Integer>();
            for (int ip = 0; ip < totalImages; ip++) {
                indicesToProcess.add(ip);
            }
        }

        // Read series metadata names for loader logging
        try {
            List<SeriesMeta> metas = ImageSourceDispatcher.readAllMetadata(directory);
            seriesNames = new ArrayList<String>();
            for (SeriesMeta m : metas) seriesNames.add(m.name);
        } catch (Exception e) {
            recordWarn("Could not read series names for split/merge loader progress in "
                    + directory + ": " + e.getMessage());
            IJ.log("    - WARNING: Could not read series names for split/merge loader progress in "
                    + directory + ": " + e.getMessage());
        }

        long loopStartTime = System.currentTimeMillis();

        if (!indicesToProcess.isEmpty() && effectiveThreads > 1) {
            // 80% loader threads, 20% worker threads
            int splitLoaders = Math.max(1, (int) Math.round(effectiveThreads * 0.8));
            int splitWorkers = Math.max(1, effectiveThreads - splitLoaders);
            int bufferSize = Math.min(4, Math.max(2, splitWorkers));
            int safeWorkers = AdaptiveParallelism.computeAndLog(supplier, splitWorkers, bufferSize);
            BoundedImageLoader loader = new BoundedImageLoader(supplier, indicesToProcess, bufferSize,
                    splitLoaders, useTifCache && !useDeconvolvedInput, directory);
            if (seriesNames != null) loader.setSeriesNames(seriesNames);
            loader.start();
            IJ.log("Thread split: " + splitLoaders + " loaders, " + safeWorkers + " workers");
            compactLog = true;
            processImagesParallel(loader, directory, binCfg, channelNames, channelColors, outRoot, tifDir, detailsRoot, mdr,
                    safeWorkers, totalImages);
            compactLog = false;
        } else if (!indicesToProcess.isEmpty()) {
            compactLog = false;
            processImagesSequential(supplier, indicesToProcess, totalImages, directory, binCfg, channelNames,
                    channelColors, outRoot, tifDir, detailsRoot, mdr, loopStartTime);
        }

        IJ.showProgress(1.0);
        IJ.showStatus("");

        IJ.log("__________________________________________________________");
        IJ.log("Post-Processing");

        // Saturations are stored display-processing settings, so they follow
        // the active project configuration folder rather than image outputs.
        saveSaturations(directory, channelNames, mdr.processMethodPerCh, mdr.saturationsPerCh);
        IJ.log("  - Saturations saved to FLASH/Config/.settings/Saturations.txt");

        // Sync min-max values back to channel_config.json.
        updateBinMinMax(directory, mdr.processMethodPerCh, mdr.customMinMaxPerCh, nCh);
        IJ.log("  - Min-max display ranges synced to channel_config.json");

        try {
            File detailsFile = AnalysisDetailsWriter.write(
                    detailsRoot,
                    "Process Images",
                    channelNames,
                    channelColors,
                    processingNotes,
                    new File[] {null},
                    new String[] {"N/A"},
                    startTimeMillis
            );
            recordOutput(detailsFile, "txt");
            IJ.log("  - Analysis Details written");
        } catch (Exception e) {
            recordWarn("Failed to write Split & Merge Analysis Details: " + e.getMessage());
            IJ.log("  - WARNING: failed to write Analysis Details: " + e.getMessage());
        }

        try {
            File detailsFile = SplitAndMergeDetailsWriter.write(
                    detailsRoot,
                    channelNames,
                    channelColors,
                    mdr.processMethodPerCh,
                    mdr.saturationsPerCh,
                    mdr.customMinMaxPerCh,
                    mdr.createMerge,
                    mdr.additionalMergeSpec,
                    mdr.subtractBackground,
                    mdr.backgroundIndex,
                    mdr.subtractFromChannels,
                    totalImages,
                    startTimeMillis
            );
            recordOutput(detailsFile, "txt");
            IJ.log("  - SplitAndMerge Details written");
        } catch (Exception e) {
            recordWarn("Failed to write SplitAndMerge Details: " + e.getMessage());
            IJ.log("  - WARNING: failed to write SplitAndMerge Details: " + e.getMessage());
        }

        // Record parameters in QC report
        if (qualityReport != null && qualityReport.isEnabled()) {
            qualityReport.addSplitMergeParams(channelNames, channelColors,
                    mdr.processMethodPerCh, mdr.saturationsPerCh, mdr.customMinMaxPerCh,
                    mdr.createMerge, mdr.additionalMergeSpec, mdr.subtractBackground,
                    mdr.backgroundIndex, mdr.subtractFromChannels,
                    binCfg == null ? "Full stack" : binCfg.getZSliceConfig().summary());
        }

        AsyncImageSaver.waitForAllWithProgress(parallelThreads);
        writePresentationTileOutputs(directory, layout, mdr.tileConfig);
        long totalElapsed = System.currentTimeMillis() - loopStartTime;
        IJ.log("__________________________________________________________");
        IJ.log("Split and Merge Image Channels Analysis complete.");
        if (lifOpenTimeMs > 0) {
            IJ.log("Total time: " + formatDuration(totalElapsed) + " (processing) + "
                    + formatDuration(lifOpenTimeMs) + " (lif open)");
        } else {
            IJ.log("Total time: " + formatDuration(totalElapsed));
        }
        if (indicesToProcess.size() < totalImages) {
            IJ.log("Images processed: " + indicesToProcess.size() + " of " + totalImages
                    + " (" + (totalImages - indicesToProcess.size()) + " skipped — outputs already existed)");
        } else {
            IJ.log("Images processed: " + totalImages);
        }
        IJ.log("==========================================================");

        closeAllWindowsExceptLog();
        if (!suppressDialogs) showOrLog("Split/Merge", "Finished.");
    }

    BinConfig loadBinConfig(String directory) {
        return BinConfigIO.readPartialFromDirectory(directory);
    }

    // ── Pre-flight worklist builder ──

    /**
     * Builds a list of series indices that still need processing.
     * Checks expected output existence using metadata-derived titles to avoid
     * loading pixel data for series whose outputs already exist.
     * <p>
     * Only skips series whose reconstructed title produces a strict
     * {@link NameParts} match; ambiguous/fallback titles stay in the
     * worklist and rely on the in-loop safety-net check.
     */
    static List<Integer> buildPreflightWorklist(String directory,
            DeferredImageSupplier supplier, int totalImages,
            String[] channelNames, File outRoot) {
        List<Integer> work = new ArrayList<Integer>();

        // Read metadata names and .lif filename for title reconstruction
        List<String> metaNames = null;
        String sourceDisplayName = null;
        try {
            List<SeriesMeta> metas = ImageSourceDispatcher.readAllMetadata(directory);
            metaNames = new ArrayList<String>();
            for (SeriesMeta m : metas) metaNames.add(m.name);
            sourceDisplayName = supplier.getContainerDisplayName();
        } catch (Exception e) {
            // Can't read metadata — include all series
            for (int i = 0; i < totalImages; i++) work.add(i);
            return work;
        }

        for (int i = 0; i < totalImages; i++) {
            String metaName = (metaNames != null && i < metaNames.size())
                    ? metaNames.get(i) : null;

            if (metaName == null) {
                work.add(i);
                continue;
            }

            // Reconstruct the Bio-Formats title: "filename.lif - SeriesName"
            String expectedTitle = sourceDisplayName == null || sourceDisplayName.isEmpty()
                    ? metaName
                    : sourceDisplayName + " - " + metaName;
            ResolvedImageMetadata metadata = ImageOrientationResolver.resolve(
                    directory, expectedTitle, i + 1);
            NameParts parts = metadata.toNameParts();

            if (!parts.strictMatch) {
                // Can't safely predict output path — keep in worklist
                work.add(i);
                continue;
            }

            if (splitMergePrimaryChannelOutputExists(directory, outRoot, parts, channelNames[0])) {
                IJ.log("  Pre-skip: series " + (i + 1) + " (" + metaName
                        + ") — output exists");
            } else {
                work.add(i);
            }
        }
        return work;
    }

    // ── Sequential processing (original path) ──

    private void processImagesSequential(final DeferredImageSupplier supplier,
            final List<Integer> schedule, final int totalImages,
            String directory, final BinConfig cfg, String[] channelNames, String[] channelColors,
            File outRoot, File tifDir, File detailsRoot,
            MainDialogResult mdr, long loopStartTime) {
        ExecutorService prefetcher = Executors.newSingleThreadExecutor();
        Future<ImagePlus> nextImage = null;
        final int scheduled = schedule.size();

        for (int wi = 0; wi < scheduled; wi++) {
            final int seriesIdx = schedule.get(wi);
            ImagePlus imp;
            if (nextImage != null) {
                try {
                    imp = nextImage.get();
                } catch (Exception e) {
                    String message = "Failed to load prefetched series "
                            + (seriesIdx + 1) + ": " + e.getMessage();
                    IJ.log("ERROR: " + message);
                    recordError(message, e);
                    nextImage = null;
                    continue;
                }
                nextImage = null;
            } else {
                IJ.showStatus("Loading image " + (wi + 1) + "/" + scheduled + "...");
                IJ.log("Loading series " + (seriesIdx + 1) + " [" + (wi + 1) + "/" + scheduled + "]...");
                try {
                    imp = supplier.openSeries(seriesIdx);
                } catch (Exception e) {
                    String message = "Failed to open series "
                            + (seriesIdx + 1) + ": " + e.getMessage();
                    IJ.log("ERROR: " + message);
                    recordError(message, e);
                    continue;
                }
            }
            if (imp == null) continue;
            imp = applyConfiguredZSliceSubset(cfg, seriesIdx, imp, "Split and Merge");

            // Start loading next scheduled series while processing this one
            if (wi + 1 < scheduled) {
                final int nextSeriesIdx = schedule.get(wi + 1);
                nextImage = prefetcher.submit(new Callable<ImagePlus>() {
                    @Override
                    public ImagePlus call() throws Exception {
                        return supplier.openSeries(nextSeriesIdx);
                    }
                });
            }

            String imgTitle = imp.getTitle();
            ResolvedImageMetadata metadata = ImageOrientationResolver.resolve(
                    directory, imgTitle, seriesIdx + 1);
            NameParts parts = metadata.toNameParts();
            String sourceImageId = presentationSourceImageId(metadata, imgTitle, seriesIdx + 1);

            // Safety-net skip for series that could not be pre-filtered
            // (non-strict title matches or metadata read failure)
            if (skipExisting) {
                if (splitMergePrimaryChannelOutputExists(directory, outRoot, parts, channelNames[0])) {
                    IJ.log("__________________________________________________________");
                    IJ.log("[" + (wi + 1) + "/" + scheduled + "] series " + (seriesIdx + 1) + ": " + imgTitle);
                    IJ.log("  Files exist, skipping (safety-net)...");
                    imp.changes = false;
                    imp.close();
                    imp.flush();
                    continue;
                }
            }

            long imageStartTime = verboseLogging ? System.currentTimeMillis() : 0;

            IJ.log("__________________________________________________________");
            IJ.log("[" + (wi + 1) + "/" + scheduled + "] series " + (seriesIdx + 1) + ": " + imgTitle);
            if (wi > 0) {
                long elapsed = System.currentTimeMillis() - loopStartTime;
                long avgPerImage = elapsed / wi;
                long remainingMs = avgPerImage * (scheduled - wi);
                IJ.log("Estimated time to completion: " + formatDuration(remainingMs));
            }
            IJ.log("  Parsed: Animal=" + parts.animal
                    + ", Hemisphere=" + (parts.hemisphere.isEmpty() ? "N/A" : parts.hemisphere)
                    + ", Region=" + (parts.region.isEmpty() ? "N/A" : parts.region));
            logOrientationResolution(metadata);

            OrientationOps.applyTransform(imp, metadata);

            File perAnimalDir = new File(outRoot, parts.animal);
            try {
                IoUtils.mustMkdirs(perAnimalDir);
            } catch (IOException e) {
                IJ.log("[FLASH] Could not create per-animal directory " + perAnimalDir
                        + ": " + e.getMessage() + " — skipping " + imgTitle);
                imp.changes = false;
                imp.close();
                imp.flush();
                continue;
            }

            processOneImage(imp, channelNames, channelColors, perAnimalDir, tifDir,
                    new File(detailsRoot, parts.animal),
                    mdr.createMerge, mdr.saveOmeTiff, mdr.subtractBackground, mdr.backgroundIndex,
                    mdr.subtractFromChannels, mdr.additionalMergeSpec,
                    mdr.processMethodPerCh, mdr.customMinMaxPerCh, mdr.saturationsPerCh,
                    parts, sourceImageId);

            imp.changes = false;
            imp.close();
            imp.flush();

            IJ.showProgress(wi + 1, scheduled);
            if (wi > 0) {
                long elapsed = System.currentTimeMillis() - loopStartTime;
                long avgPerImage = elapsed / (wi + 1);
                long remainingMs = avgPerImage * (scheduled - (wi + 1));
                IJ.showStatus("Processing " + (wi + 1) + "/" + scheduled + " complete (~" + formatDuration(remainingMs) + " remaining)");
            } else {
                IJ.showStatus("Processing " + (wi + 1) + "/" + scheduled + " complete");
            }

            if (verboseLogging) {
                long imageElapsed = System.currentTimeMillis() - imageStartTime;
                IJ.log("  [DEBUG] Image processing time: " + formatDuration(imageElapsed));
            }

        }
        prefetcher.shutdown();
    }

    // ── Parallel processing ──

    private void processImagesParallel(final BoundedImageLoader loader,
            final String directory,
            final BinConfig cfg,
            final String[] channelNames,
            final String[] channelColors, final File outRoot, final File tifDir, final File detailsRoot,
            final MainDialogResult mdr, final int nThreads,
            final int totalDiscovered) {
        final int scheduled = loader.totalToLoad();
        final AtomicInteger completed = new AtomicInteger(0);
        final long startTime = System.currentTimeMillis();

        ExecutorService pool = Executors.newFixedThreadPool(nThreads);
        List<Future<?>> futures = new ArrayList<Future<?>>();
        final List<Throwable> failures =
                Collections.synchronizedList(new ArrayList<Throwable>());

        for (int t = 0; t < nThreads; t++) {
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
                        imp = applyConfiguredZSliceSubset(cfg, idx, imp, "Split and Merge");

                        ParallelContext.enterParallel();
                        try {
                            String imgTitle = imp.getTitle();
                            ResolvedImageMetadata metadata = ImageOrientationResolver.resolve(
                                    directory, imgTitle, idx + 1);
                            NameParts parts = metadata.toNameParts();
                            String sourceImageId = presentationSourceImageId(metadata, imgTitle, idx + 1);

                            // Worker start log with short name and channels
                            String workerTag = nThreads > 1
                                    ? "Worker " + workerNum : "Worker";
                            String partLabel = (parts.animal != null ? parts.animal : "?")
                                    + (parts.hemisphere != null ? "_" + parts.hemisphere : "")
                                    + (parts.region != null ? "_" + parts.region : "");
                            StringBuilder chList = new StringBuilder();
                            for (int c = 0; c < channelNames.length; c++) {
                                if (c > 0) chList.append(" ");
                                chList.append(channelNames[c]);
                            }
                            IJ.log(workerTag + ": processing " + partLabel
                                    + " | " + chList.toString());

                            // Safety-net skip for series that could not be pre-filtered
                            if (skipExisting) {
                                if (splitMergePrimaryChannelOutputExists(directory, outRoot, parts, channelNames[0])) {
                                    IJ.log("[" + (idx + 1) + "] " + imgTitle + " -- skipped (safety-net)");
                                    int done = completed.incrementAndGet();
                                    IJ.showProgress(done, scheduled);
                                    continue;
                                }
                            }

                            long imageStartTime = System.currentTimeMillis();

                            logOrientationResolution(metadata);
                            OrientationOps.applyTransform(imp, metadata);

                            File perAnimalDir = new File(outRoot, parts.animal);
                            try {
                                IoUtils.mustMkdirs(perAnimalDir);
                            } catch (IOException e) {
                                failures.add(e);
                                IJ.log("[FLASH] Could not create per-animal directory "
                                        + perAnimalDir + ": " + e.getMessage()
                                        + " — failing " + imgTitle);
                                imp.changes = false;
                                imp.close();
                                imp.flush();
                                int done = completed.incrementAndGet();
                                IJ.showProgress(done, scheduled);
                                IJ.showStatus("Processing " + done + "/" + scheduled + " (failed)");
                                continue;
                            }

                            processOneImage(imp, channelNames, channelColors, perAnimalDir, tifDir,
                                    new File(detailsRoot, parts.animal),
                                    mdr.createMerge, mdr.saveOmeTiff, mdr.subtractBackground, mdr.backgroundIndex,
                                    mdr.subtractFromChannels, mdr.additionalMergeSpec,
                                    mdr.processMethodPerCh, mdr.customMinMaxPerCh, mdr.saturationsPerCh,
                                    parts, sourceImageId);

                            int done = completed.incrementAndGet();
                            long elapsed = System.currentTimeMillis() - startTime;
                            IJ.showProgress(done, scheduled);
                            if (done >= nThreads && done < scheduled) {
                                long remainingMs = elapsed * (scheduled - done) / done;
                                IJ.showStatus("Processing " + done + "/" + scheduled + " (~" + formatDuration(remainingMs) + " remaining)");
                            } else {
                                IJ.showStatus("Processing " + done + "/" + scheduled);
                            }

                            // Compact single-line summary for parallel mode
                            long imageElapsed = System.currentTimeMillis() - imageStartTime;
                            String etaStr = "";
                            if (done >= nThreads && done < scheduled) {
                                long remainingMs = elapsed * (scheduled - done) / done;
                                etaStr = " | ETA: " + formatDurationCompact(remainingMs);
                            }
                            IJ.log("[" + done + "/" + scheduled + "] " + partLabel
                                    + " Completed in " + formatDurationCompact(imageElapsed) + etaStr);

                        } catch (Exception e) {
                            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                            RuntimeException contextual = new RuntimeException(
                                    "Split/Merge failed for image " + (idx + 1) + "/" + scheduled
                                            + " title='" + imp.getTitle() + "': " + msg,
                                    e);
                            failures.add(contextual);
                            IJ.log("[" + (idx + 1) + "/" + scheduled + "] ERROR: "
                                    + contextual.getMessage());
                            int done = completed.incrementAndGet();
                            IJ.showProgress(done, scheduled);
                            IJ.showStatus("Processing " + done + "/" + scheduled + " (failed)");
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
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                Throwable cause = e.getCause() == null ? e : e.getCause();
                failures.add(cause);
                String msg = cause.getMessage() != null
                        ? cause.getMessage() : cause.getClass().getSimpleName();
                IJ.log("Parallel processing error: " + msg);
            }
        }
        pool.shutdown();

        if (!failures.isEmpty()) {
            throw buildParallelFailure("Split/Merge failed for "
                    + failures.size() + " image(s)", failures);
        }
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

    // ── Main options dialog (PipelineDialog) ──

    private MainDialogResult showMainDialog(final String[] channelNames, String[] channelColors,
                                            String[] defaultMinMax, File projectRoot) {
        final int nCh = channelNames.length;
        final int autoBackgroundIndex = detectAutofluorescenceChannel(projectRoot, nCh);
        if (headless || suppressDialogs || java.awt.GraphicsEnvironment.isHeadless()) {
            return buildHeadlessDefaults(channelNames, defaultMinMax, autoBackgroundIndex);
        }
        final String[] bgChoices = new String[nCh + 1];
        bgChoices[0] = NONE_OPTION;
        for (int i = 0; i < nCh; i++) {
            bgChoices[i + 1] = channelNames[i] + (i == autoBackgroundIndex ? " (auto-detected)" : "");
        }

        final PipelineDialog pd = new PipelineDialog("Make Presentation Images", PipelineDialog.Phase.SETUP);

        // ── Section: Input ──
        pd.addAnalysisHelpHeader("Make Presentation Images", FLASH_Pipeline.IDX_SPLIT_MERGE);
        pd.addSubHeader("Input");
        final ToggleSwitch useDeconvToggle = pd.addToggle("Use deconvolved stacks if available",
                useDeconvolvedInput);

        // ── Section: Channel Processing ──
        pd.addHeader("Channel Processing");

        final ChannelSettingsGrid channelGrid = buildChannelSettingsGrid(channelNames, defaultMinMax, channelColors);
        pd.addComponent(channelGrid.panel);

        // ── Section: Merge Options ──
        pd.addHeader("Merge Options");
        final ToggleSwitch createMergeToggle = pd.addToggle("Create merge of all channels", true);
        final ToggleSwitch saveOmeTiffToggle = pd.addToggle("Save OME-TIFF composites", false);

        pd.beginAdvancedSection("splitMerge");
        final JTextField additionalMergesField = pd.addStringField(
                "Additional merges (e.g. 1-2 3-4):", "", 15);
        pd.endAdvancedSection();

        final TileOptionsPanel tileOptions = new TileOptionsPanel(defaultTileOrder(channelNames));
        pd.addComponent(tileOptions.panel);
        final BackgroundSubtractionPanel backgroundOptions =
                new BackgroundSubtractionPanel(channelNames, bgChoices, autoBackgroundIndex);
        pd.addComponent(backgroundOptions.panel);
        tileOptions.previewButton.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                PresentationTileConfig previewConfig = tileOptions.buildConfig();
                PresentationTileRecord previewRecord = representativePreviewRecord(projectRoot);
                BufferedImage preview = PresentationTileWriter.renderAnnotationPreview(
                        previewConfig, previewRecord);
                JOptionPane.showMessageDialog(pd.getWindow(), previewComponent(preview),
                        "Annotation Preview", JOptionPane.PLAIN_MESSAGE);
            }
        });

        boolean ok = pd.showDialog();
        if (!ok) return null;

        // Read direct Swing values so compact optional panels do not depend on insertion order.
        final MainDialogResult result = new MainDialogResult();
        result.useDeconvolvedInput = useDeconvToggle.isSelected();

        ChannelSettingsSelections channelSettings = readChannelSettingsGrid(channelGrid);
        result.processMethodPerCh = channelSettings.processMethodPerCh;
        result.saturationsPerCh = channelSettings.saturationsPerCh;
        result.customMinMaxPerCh = channelSettings.customMinMaxPerCh;

        result.createMerge = createMergeToggle.isSelected();
        result.saveOmeTiff = saveOmeTiffToggle.isSelected();
        result.additionalMergeSpec = additionalMergesField.getText() == null
                ? "" : additionalMergesField.getText().trim();
        result.tileConfig = tileOptions.buildConfig();

        result.subtractBackground = backgroundOptions.subtractBackground();
        result.backgroundIndex = backgroundOptions.backgroundIndex();
        if (result.backgroundIndex < 0) result.subtractBackground = false;
        result.subtractFromChannels = backgroundOptions.subtractFromChannels();
        return result;
    }

    private static String[] positionChoices() {
        return new String[]{"Top left", "Top right", "Bottom left", "Bottom right"};
    }

    private static List<String> defaultTileOrder(String[] channelNames) {
        List<String> order = new ArrayList<String>();
        if (channelNames != null) {
            for (String channelName : channelNames) {
                if (channelName != null && !channelName.trim().isEmpty()) {
                    order.add(channelName.trim());
                }
            }
        }
        order.add("Merge");
        return order;
    }

    static PresentationTileConfig buildTileConfigFromControls(
            ToggleSwitch createTileToggle,
            ToggleSwitch annotateOverviewToggle,
            ToggleSwitch annotateIndividualToggle,
            JComboBox<String> tileGroupBox,
            TileOrderPanel tileOrderPanel,
            JTextField tileCellSizeField,
            ToggleSwitch scaleBarToggle,
            JTextField scaleBarLengthField,
            JTextField scaleBarThicknessField,
            JComboBox<String> scaleBarPositionBox,
            JComboBox<String> annotationColorBox,
            JComboBox<String> labelModeBox,
            JTextField customLabelField,
            JTextField labelFontSizeField,
            JComboBox<String> labelPositionBox) {

        boolean annotateIndividual = isSelected(annotateIndividualToggle);
        boolean createTile = isSelected(createTileToggle) || annotateIndividual;
        boolean annotateOverview = isSelected(annotateOverviewToggle) || annotateIndividual;
        PresentationTileConfig.GroupRowsBy groupRowsBy =
                "Condition".equals(selectedText(tileGroupBox))
                        ? PresentationTileConfig.GroupRowsBy.CONDITION
                        : PresentationTileConfig.GroupRowsBy.ANIMAL;
        Color annotationColor = "Black".equals(selectedText(annotationColorBox))
                ? Color.BLACK : Color.WHITE;

        return PresentationTileConfig.builder()
                .createOverviewTile(createTile)
                .annotateOverviewTile(annotateOverview)
                .annotateIndividualImages(annotateIndividual)
                .groupRowsBy(groupRowsBy)
                .channelOrder(tileOrderPanel == null ? Collections.<String>emptyList() : tileOrderPanel.orderedItems())
                .cellSizePx(parseInt(tileCellSizeField, 260))
                .scaleBarEnabled(isSelected(scaleBarToggle))
                .scaleBarLengthUm(parseDouble(scaleBarLengthField, 100.0))
                .scaleBarThicknessPx(parseInt(scaleBarThicknessField, 6))
                .scaleBarPosition(parsePosition(selectedText(scaleBarPositionBox)))
                .annotationColor(annotationColor)
                .labelMode(parseLabelMode(selectedText(labelModeBox)))
                .customLabelTemplate(textValue(customLabelField, "{stain}"))
                .labelFontSizePx(parseInt(labelFontSizeField, 18))
                .labelPosition(parsePosition(selectedText(labelPositionBox)))
                .build();
    }

    private static boolean isSelected(ToggleSwitch toggle) {
        return toggle != null && toggle.isSelected();
    }

    private static String selectedText(JComboBox<String> combo) {
        Object value = combo == null ? null : combo.getSelectedItem();
        return value == null ? "" : value.toString();
    }

    private static String textValue(JTextField field, String fallback) {
        String value = field == null ? null : field.getText();
        if (value == null || value.trim().isEmpty()) return fallback;
        return value.trim();
    }

    private static int parseInt(JTextField field, int fallback) {
        try {
            return Integer.parseInt(textValue(field, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(JTextField field, double fallback) {
        try {
            return Double.parseDouble(textValue(field, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static PresentationTileConfig.Position parsePosition(String value) {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("top right".equals(text)) return PresentationTileConfig.Position.TOP_RIGHT;
        if ("bottom left".equals(text)) return PresentationTileConfig.Position.BOTTOM_LEFT;
        if ("bottom right".equals(text)) return PresentationTileConfig.Position.BOTTOM_RIGHT;
        return PresentationTileConfig.Position.TOP_LEFT;
    }

    private static PresentationTileConfig.LabelMode parseLabelMode(String value) {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("none".equals(text)) return PresentationTileConfig.LabelMode.NONE;
        if ("image name".equals(text)) return PresentationTileConfig.LabelMode.IMAGE_NAME;
        if ("condition + image".equals(text)) return PresentationTileConfig.LabelMode.CONDITION_IMAGE;
        if ("custom text".equals(text)) return PresentationTileConfig.LabelMode.CUSTOM;
        return PresentationTileConfig.LabelMode.STAIN_NAME;
    }

    static final class TileOptionsPanel {
        final JPanel panel;
        final JButton previewButton = new JButton("Preview annotation");
        private final ToggleSwitch createTileToggle = new ToggleSwitch(false);
        private final JComboBox<String> tileGroupBox =
                new JComboBox<String>(new String[]{"Animal", "Condition"});
        private final TileOrderPanel tileOrderPanel;
        private final JTextField tileCellSizeField = compactField("260", 5);
        private final ToggleSwitch annotateOverviewToggle = new ToggleSwitch(true);
        private final ToggleSwitch annotateIndividualToggle = new ToggleSwitch(false);
        private final ToggleSwitch scaleBarToggle = new ToggleSwitch(true);
        private final JTextField scaleBarLengthField = compactField("100", 5);
        private final JTextField scaleBarThicknessField = compactField("6", 4);
        private final JComboBox<String> scaleBarPositionBox =
                new JComboBox<String>(positionChoices());
        private final JComboBox<String> annotationColorBox =
                new JComboBox<String>(new String[]{"White", "Black"});
        private final JComboBox<String> labelModeBox =
                new JComboBox<String>(new String[]{"None", "Stain name", "Image name",
                        "Condition + image", "Custom text"});
        private final JTextField customLabelField = compactField("{stain}", 10);
        private final JTextField labelFontSizeField = compactField("18", 4);
        private final JComboBox<String> labelPositionBox =
                new JComboBox<String>(positionChoices());

        TileOptionsPanel(List<String> defaultOrder) {
            CollapsibleOptionsPanel section = new CollapsibleOptionsPanel("Overview Tile");
            this.panel = section.panel;
            this.tileOrderPanel = new TileOrderPanel(defaultOrder);
            previewButton.setFont(previewButton.getFont().deriveFont(Font.PLAIN, 11f));
            previewButton.setMargin(new Insets(1, 8, 1, 8));
            section.addHeaderComponent(previewButton);
            tileGroupBox.setSelectedItem("Animal");
            scaleBarPositionBox.setSelectedItem("Bottom right");
            annotationColorBox.setSelectedItem("White");
            labelModeBox.setSelectedItem("Stain name");
            labelPositionBox.setSelectedItem("Top left");

            section.add(compactRow(
                    labelledToggle("Create tile", createTileToggle),
                    labelled("Rows by", tileGroupBox),
                    labelled("Cell px", tileCellSizeField)));
            section.add(compactRow(
                    labelledToggle("Annotate tile", annotateOverviewToggle),
                    labelledToggle("Annotated copies", annotateIndividualToggle),
                    labelledToggle("Scale bar", scaleBarToggle)));
            section.add(compactRow(
                    labelled("Bar um", scaleBarLengthField),
                    labelled("Bar px", scaleBarThicknessField),
                    labelled("Bar position", scaleBarPositionBox),
                    labelled("Colour", annotationColorBox)));
            section.add(compactRow(
                    labelled("Label", labelModeBox),
                    labelled("Custom", customLabelField),
                    labelled("Font px", labelFontSizeField),
                    labelled("Label position", labelPositionBox)));
            section.add(tileOrderPanel.panel);

            annotateIndividualToggle.addChangeListener(new Runnable() {
                @Override
                public void run() {
                    boolean forceTileAnnotations = annotateIndividualToggle.isSelected();
                    if (forceTileAnnotations) {
                        createTileToggle.setSelected(true);
                        annotateOverviewToggle.setSelected(true);
                    }
                    createTileToggle.setEnabled(!forceTileAnnotations);
                    annotateOverviewToggle.setEnabled(!forceTileAnnotations);
                }
            });
        }

        PresentationTileConfig buildConfig() {
            return buildTileConfigFromControls(
                    createTileToggle, annotateOverviewToggle, annotateIndividualToggle,
                    tileGroupBox, tileOrderPanel, tileCellSizeField,
                    scaleBarToggle, scaleBarLengthField, scaleBarThicknessField,
                    scaleBarPositionBox, annotationColorBox, labelModeBox,
                    customLabelField, labelFontSizeField, labelPositionBox);
        }
    }

    static final class BackgroundSubtractionPanel {
        final JPanel panel;
        private final ToggleSwitch subtractBgToggle;
        private final JComboBox<String> bgChannelBox;
        private final JButton overrideBackgroundBtn = new JButton("Override");
        private final JLabel subtractFromLabel = new JLabel("Subtract from");
        private final ToggleSwitch[] subtractToggles;
        private final String[] channelNames;
        private final int autoBackgroundIndex;

        BackgroundSubtractionPanel(final String[] channelNames, final String[] bgChoices,
                                   final int autoBackgroundIndex) {
            this.channelNames = channelNames == null ? new String[0] : channelNames;
            this.autoBackgroundIndex = autoBackgroundIndex;
            this.subtractBgToggle = new ToggleSwitch(autoBackgroundIndex >= 0);
            this.bgChannelBox = new JComboBox<String>(bgChoices);
            this.subtractToggles = new ToggleSwitch[this.channelNames.length];

            CollapsibleOptionsPanel section = new CollapsibleOptionsPanel("Background Subtraction");
            this.panel = section.panel;
            bgChannelBox.setSelectedIndex(autoBackgroundIndex >= 0 ? autoBackgroundIndex + 1 : 0);

            section.add(compactRow(
                    labelledToggle("Subtract background", subtractBgToggle),
                    labelled("Background channel", bgChannelBox),
                    overrideBackgroundBtn));

            JPanel subtractRow = compactRow(subtractFromLabel);
            for (int i = 0; i < this.channelNames.length; i++) {
                subtractToggles[i] = new ToggleSwitch(i != autoBackgroundIndex);
                subtractRow.add(labelledToggle(this.channelNames[i], subtractToggles[i]));
            }
            section.add(subtractRow);

            overrideBackgroundBtn.addActionListener(new java.awt.event.ActionListener() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    int selected = bgChannelBox.getSelectedIndex();
                    bgChannelBox.removeAllItems();
                    bgChannelBox.addItem(NONE_OPTION);
                    for (String channelName : BackgroundSubtractionPanel.this.channelNames) {
                        bgChannelBox.addItem(channelName);
                    }
                    bgChannelBox.setSelectedIndex(Math.max(0,
                            Math.min(selected, bgChannelBox.getItemCount() - 1)));
                    overrideBackgroundBtn.setEnabled(false);
                }
            });

            subtractBgToggle.addChangeListener(new Runnable() {
                @Override
                public void run() {
                    updateEnabledState();
                }
            });
            updateEnabledState();
        }

        boolean subtractBackground() {
            return subtractBgToggle.isSelected();
        }

        int backgroundIndex() {
            if (!subtractBackground() || bgChannelBox.getSelectedIndex() <= 0) return -1;
            return bgChannelBox.getSelectedIndex() - 1;
        }

        boolean[] subtractFromChannels() {
            boolean[] out = new boolean[subtractToggles.length];
            for (int i = 0; i < subtractToggles.length; i++) {
                out[i] = subtractToggles[i].isSelected();
            }
            return out;
        }

        private void updateEnabledState() {
            boolean on = subtractBgToggle.isSelected();
            bgChannelBox.setEnabled(on);
            overrideBackgroundBtn.setEnabled(on && autoBackgroundIndex >= 0);
            subtractFromLabel.setEnabled(on);
            if (!on) {
                bgChannelBox.setSelectedIndex(0);
            } else if (bgChannelBox.getSelectedIndex() <= 0 && autoBackgroundIndex >= 0) {
                bgChannelBox.setSelectedIndex(autoBackgroundIndex + 1);
            }
            for (ToggleSwitch toggle : subtractToggles) {
                toggle.setEnabled(on);
            }
        }
    }

    static final class CollapsibleOptionsPanel {
        private static final Color SUBHEADER_COLOR = new Color(78, 93, 101);

        final JPanel panel = new JPanel();
        private final JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        private final JPanel content = new JPanel();
        private final JLabel titleLabel;
        private final String title;

        CollapsibleOptionsPanel(String title) {
            this.title = title == null ? "" : title;
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setOpaque(false);
            panel.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.setBorder(BorderFactory.createEmptyBorder(6, 0, 3, 0));

            header.setOpaque(false);
            header.setAlignmentX(Component.LEFT_ALIGNMENT);
            header.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 0));
            header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            titleLabel = new JLabel(this.title);
            titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 12f));
            titleLabel.setForeground(SUBHEADER_COLOR);
            titleLabel.setIcon(FlashIcons.chevronRight(12, SUBHEADER_COLOR));
            titleLabel.setIconTextGap(5);
            if (titleLabel.getIcon() == null) {
                titleLabel.setText("\u25B8 " + this.title);
            }
            header.add(titleLabel);

            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            content.setOpaque(false);
            content.setVisible(false);
            content.setBorder(BorderFactory.createEmptyBorder(4, 30, 2, 0));

            java.awt.event.MouseAdapter listener = new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    setOpen(!content.isVisible());
                }
            };
            header.addMouseListener(listener);
            titleLabel.addMouseListener(listener);

            panel.add(header);
            panel.add(content);
        }

        void add(JComponent component) {
            component.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(component);
        }

        void addHeaderComponent(JComponent component) {
            if (component == null) return;
            component.setAlignmentX(Component.LEFT_ALIGNMENT);
            header.add(Box.createHorizontalStrut(8));
            header.add(component);
        }

        private void setOpen(boolean open) {
            content.setVisible(open);
            titleLabel.setIcon(open
                    ? FlashIcons.chevronDown(12, SUBHEADER_COLOR)
                    : FlashIcons.chevronRight(12, SUBHEADER_COLOR));
            if (titleLabel.getIcon() == null) {
                titleLabel.setText((open ? "\u25BE " : "\u25B8 ") + title);
            } else {
                titleLabel.setText(title);
            }
            panel.revalidate();
            panel.repaint();
        }
    }

    private static JPanel compactRow(Component... components) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (components != null) {
            for (Component component : components) {
                if (component != null) row.add(component);
            }
        }
        return row;
    }

    private static JPanel labelled(String label, JComponent component) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panel.setOpaque(false);
        JLabel text = new JLabel(label);
        text.setFont(text.getFont().deriveFont(Font.PLAIN, 11f));
        panel.add(text);
        panel.add(component);
        return panel;
    }

    private static JPanel labelledToggle(String label, ToggleSwitch toggle) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panel.setOpaque(false);
        panel.add(toggle);
        JLabel text = new JLabel(label);
        text.setFont(text.getFont().deriveFont(Font.PLAIN, 11f));
        panel.add(text);
        return panel;
    }

    private static JTextField compactField(String value, int columns) {
        JTextField field = new JTextField(value, columns);
        field.setMaximumSize(new Dimension(Math.max(48, columns * 12), 24));
        return field;
    }

    private static JComponent previewComponent(BufferedImage preview) {
        JLabel label = new JLabel(new ImageIcon(preview));
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setVerticalAlignment(SwingConstants.CENTER);
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(Color.DARK_GRAY);
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        panel.add(label, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(preview.getWidth() + 8, preview.getHeight() + 8));
        return panel;
    }

    private static PresentationTileRecord representativePreviewRecord(File projectRoot) {
        if (projectRoot == null) return null;
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(projectRoot.getAbsolutePath());
        File manifest = presentationManifestFile(layout);
        if (manifest.isFile()) {
            try {
                List<PresentationTileRecord> records = PresentationTileWriter.readManifest(manifest);
                for (PresentationTileRecord record : records) {
                    if (record.imageFile() != null && record.imageFile().isFile()) {
                        return record;
                    }
                }
            } catch (IOException e) {
                IJ.log("  - Warning: could not read presentation preview manifest: "
                        + e.getMessage());
            }
        }

        PresentationTileRecord fromPng = representativePreviewRecordFromSavedPng(layout.presentationImagesDir());
        if (fromPng != null) return fromPng;

        try {
            List<SeriesMeta> metas = ImageSourceDispatcher.readAllMetadata(projectRoot.getAbsolutePath());
            for (SeriesMeta meta : metas) {
                if (meta != null && meta.width > 0 && meta.height > 0) {
                    double multiplier = calibrationUnitToMicronMultiplier(meta.unit);
                    double pixelWidthUm = Double.isFinite(multiplier) && multiplier > 0
                            ? meta.pixelWidth * multiplier : Double.NaN;
                    double pixelHeightUm = Double.isFinite(multiplier) && multiplier > 0
                            ? meta.pixelHeight * multiplier : Double.NaN;
                    String animal = ConditionManifestIO.extractAnimalName(meta.name);
                    if (animal == null || animal.trim().isEmpty()) animal = "Animal1";
                    return new PresentationTileRecord(
                            null, animal, "LH", "Region", "DAPI", "DAPI",
                            0, meta.width, meta.height, pixelWidthUm, pixelHeightUm);
                }
            }
        } catch (Exception e) {
            IJ.log("  - Warning: could not read image metadata for annotation preview: "
                    + e.getMessage());
        }

        return null;
    }

    private static PresentationTileRecord representativePreviewRecordFromSavedPng(File imagesRoot) {
        File png = firstPng(imagesRoot, 0);
        if (png == null) return null;
        try {
            BufferedImage image = ImageIO.read(png);
            if (image == null) return null;
            File animalDir = png.getParentFile();
            String animal = animalDir == null ? "Animal1" : animalDir.getName();
            String outputName = ImageNameParser.stripExtension(png.getName());
            if (outputName == null || outputName.trim().isEmpty()) outputName = "DAPI";
            int suffix = outputName.indexOf('_');
            String stain = suffix > 0 ? outputName.substring(0, suffix) : outputName;
            return new PresentationTileRecord(
                    png, animal, "LH", "Region", outputName, stain,
                    0, image.getWidth(), image.getHeight(), Double.NaN, Double.NaN);
        } catch (IOException e) {
            IJ.log("  - Warning: could not read saved image for annotation preview: "
                    + e.getMessage());
            return null;
        }
    }

    private static File firstPng(File dir, int depth) {
        if (dir == null || !dir.isDirectory() || depth > 3) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        java.util.Arrays.sort(files);
        for (File file : files) {
            if (file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".png")) {
                return file;
            }
        }
        for (File file : files) {
            if (file.isDirectory()) {
                File nested = firstPng(file, depth + 1);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    static final class TileOrderPanel {
        final JPanel panel;
        private final DefaultListModel<String> model = new DefaultListModel<String>();
        private final JList<String> list = new JList<String>(model);

        TileOrderPanel(List<String> items) {
            panel = new JPanel(new BorderLayout(8, 4));
            panel.setOpaque(false);
            panel.setBorder(BorderFactory.createEmptyBorder(2, 4, 8, 4));

            JLabel label = new JLabel("Tile column order");
            label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
            panel.add(label, BorderLayout.NORTH);

            if (items != null) {
                for (String item : items) {
                    if (item != null && !item.trim().isEmpty()) model.addElement(item.trim());
                }
            }
            list.setVisibleRowCount(Math.min(6, Math.max(3, model.size())));
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            if (model.size() > 0) list.setSelectedIndex(0);
            JScrollPane scroll = new JScrollPane(list);
            scroll.setPreferredSize(new Dimension(240, 96));
            panel.add(scroll, BorderLayout.CENTER);

            JPanel buttons = new JPanel();
            buttons.setOpaque(false);
            buttons.setLayout(new BoxLayout(buttons, BoxLayout.Y_AXIS));
            JButton up = new JButton("Up");
            JButton down = new JButton("Down");
            up.setAlignmentX(Component.LEFT_ALIGNMENT);
            down.setAlignmentX(Component.LEFT_ALIGNMENT);
            buttons.add(up);
            buttons.add(Box.createVerticalStrut(4));
            buttons.add(down);
            panel.add(buttons, BorderLayout.EAST);

            up.addActionListener(new java.awt.event.ActionListener() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    moveSelected(-1);
                }
            });
            down.addActionListener(new java.awt.event.ActionListener() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    moveSelected(1);
                }
            });
        }

        List<String> orderedItems() {
            List<String> out = new ArrayList<String>();
            for (int i = 0; i < model.getSize(); i++) {
                out.add(model.getElementAt(i));
            }
            return out;
        }

        private void moveSelected(int delta) {
            int index = list.getSelectedIndex();
            int next = index + delta;
            if (index < 0 || next < 0 || next >= model.getSize()) return;
            String value = model.getElementAt(index);
            model.removeElementAt(index);
            model.add(next, value);
            list.setSelectedIndex(next);
        }
    }

    @SuppressWarnings("unchecked")
    static ChannelSettingsGrid buildChannelSettingsGrid(final String[] channelNames, String[] defaultMinMax) {
        return buildChannelSettingsGrid(channelNames, defaultMinMax, null);
    }

    @SuppressWarnings("unchecked")
    static ChannelSettingsGrid buildChannelSettingsGrid(final String[] channelNames, String[] defaultMinMax,
                                                        String[] channelColors) {
        final int nCh = channelNames == null ? 0 : channelNames.length;
        final JComboBox<String>[] methodBoxes = new JComboBox[nCh];
        final JTextField[] displayRangeFields = new JTextField[nCh];
        final JTextField[] saturationFields = new JTextField[nCh];
        final JLabel[] channelLabels = new JLabel[nCh];
        final JLabel[] rowLabels = new JLabel[]{
                createChannelGridRowLabel("Processing Method"),
                createChannelGridRowLabel("Display Ranges"),
                createChannelGridRowLabel("Saturation")
        };
        final JLabel[][] helperLabels = new JLabel[3][nCh];

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(0, 4, 6, 4));

        addChannelGridComponent(panel, Box.createHorizontalStrut(CHANNEL_GRID_ROW_LABEL_WIDTH),
                0, 0, 0.0, GridBagConstraints.NONE, new Insets(0, 0, 4, 8));
        for (int ch = 0; ch < nCh; ch++) {
            JLabel channelLabel = new JLabel(channelNames[ch]);
            channelLabel.setFont(channelLabel.getFont().deriveFont(Font.BOLD, 12f));
            String lutColor = channelColors != null && ch < channelColors.length ? channelColors[ch] : null;
            channelLabel.setForeground(channelHeaderColorForLut(lutColor));
            channelLabels[ch] = channelLabel;
            addChannelGridComponent(panel, channelLabel,
                    ch + 1, 0, 1.0, GridBagConstraints.HORIZONTAL, new Insets(0, 0, 4, 10));
        }

        for (int ch = 0; ch < nCh; ch++) {
            String defaultRange = defaultMinMax != null && ch < defaultMinMax.length
                    ? defaultMinMax[ch] : NONE_OPTION;
            boolean hasCustomValue = hasCustomDisplayRange(defaultRange);
            String defaultMethod = hasCustomValue ? METHOD_CUSTOM : METHOD_AUTOMATIC;

            methodBoxes[ch] = new JComboBox<String>(PROCESS_METHODS);
            methodBoxes[ch].setSelectedItem(defaultMethod);
            methodBoxes[ch].setName("splitMerge.channel." + ch + ".processingMethod");
            methodBoxes[ch].setMaximumSize(new Dimension(CHANNEL_GRID_CELL_WIDTH, 24));
            methodBoxes[ch].setPreferredSize(new Dimension(CHANNEL_GRID_CELL_WIDTH, 24));

            displayRangeFields[ch] = new JTextField(hasCustomValue ? defaultRange : "", 12);
            displayRangeFields[ch].setName("splitMerge.channel." + ch + ".displayRange");
            displayRangeFields[ch].setMaximumSize(new Dimension(CHANNEL_GRID_TEXT_WIDTH, 24));
            displayRangeFields[ch].setPreferredSize(new Dimension(CHANNEL_GRID_TEXT_WIDTH, 24));

            saturationFields[ch] = new JTextField(formatSaturation(DEFAULT_SATURATION), 8);
            saturationFields[ch].setName("splitMerge.channel." + ch + ".saturation");
            saturationFields[ch].setMaximumSize(new Dimension(CHANNEL_GRID_SATURATION_WIDTH, 24));
            saturationFields[ch].setPreferredSize(new Dimension(CHANNEL_GRID_SATURATION_WIDTH, 24));

            final int idx = ch;
            methodBoxes[ch].addItemListener(new ItemListener() {
                @Override
                public void itemStateChanged(ItemEvent e) {
                    if (e.getStateChange() != ItemEvent.SELECTED) return;
                    updateChannelGridEnabledState(methodBoxes[idx],
                            displayRangeFields[idx], saturationFields[idx]);
                }
            });
            updateChannelGridEnabledState(methodBoxes[ch], displayRangeFields[ch], saturationFields[ch]);
        }

        addChannelGridComponent(panel, rowLabels[0],
                0, 1, 0.0, GridBagConstraints.NONE, new Insets(4, 0, 4, 8));
        addChannelGridComponent(panel, rowLabels[1],
                0, 2, 0.0, GridBagConstraints.NONE, new Insets(4, 0, 4, 8));
        addChannelGridComponent(panel, rowLabels[2],
                0, 3, 0.0, GridBagConstraints.NONE, new Insets(4, 0, 0, 8));

        for (int ch = 0; ch < nCh; ch++) {
            helperLabels[0][ch] = createChannelGridHelperLabel(METHOD_HELP);
            helperLabels[1][ch] = createChannelGridHelperLabel(DISPLAY_RANGE_HELP);
            helperLabels[2][ch] = createChannelGridHelperLabel(SATURATION_HELP);

            addChannelGridComponent(panel,
                    createChannelGridCell(methodBoxes[ch], helperLabels[0][ch]),
                    ch + 1, 1, 1.0, GridBagConstraints.HORIZONTAL, new Insets(4, 0, 4, 10));
            addChannelGridComponent(panel,
                    createChannelGridCell(displayRangeFields[ch], helperLabels[1][ch]),
                    ch + 1, 2, 1.0, GridBagConstraints.HORIZONTAL, new Insets(4, 0, 4, 10));
            addChannelGridComponent(panel,
                    createChannelGridCell(saturationFields[ch], helperLabels[2][ch]),
                    ch + 1, 3, 1.0, GridBagConstraints.HORIZONTAL, new Insets(4, 0, 0, 10));
        }

        return new ChannelSettingsGrid(panel, methodBoxes, displayRangeFields,
                saturationFields, channelLabels, rowLabels, helperLabels);
    }

    static ChannelSettingsSelections readChannelSettingsGrid(ChannelSettingsGrid grid) {
        int nCh = grid == null || grid.methodBoxes == null ? 0 : grid.methodBoxes.length;
        String[] processMethodPerCh = new String[nCh];
        String[] customMinMaxPerCh = new String[nCh];
        double[] saturationsPerCh = new double[nCh];

        for (int i = 0; i < nCh; i++) {
            processMethodPerCh[i] = selectedProcessingMethod(grid.methodBoxes[i]);
            customMinMaxPerCh[i] = normalizeDisplayRange(grid.displayRangeFields[i].getText());
            saturationsPerCh[i] = parseSaturationOrDefault(grid.saturationFields[i].getText(), 0.0);
        }

        return new ChannelSettingsSelections(processMethodPerCh, customMinMaxPerCh, saturationsPerCh);
    }

    private static JLabel createChannelGridRowLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
        label.setForeground(Color.DARK_GRAY);
        label.setPreferredSize(new Dimension(CHANNEL_GRID_ROW_LABEL_WIDTH, 24));
        return label;
    }

    private static JLabel createChannelGridHelperLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 10f));
        label.setForeground(CHANNEL_GRID_HELP_COLOR);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setMaximumSize(new Dimension(CHANNEL_GRID_CELL_WIDTH, 28));
        return label;
    }

    private static JPanel createChannelGridCell(JComponent input, JLabel helper) {
        JPanel cell = new JPanel();
        cell.setLayout(new BoxLayout(cell, BoxLayout.Y_AXIS));
        cell.setOpaque(false);
        cell.setAlignmentX(Component.LEFT_ALIGNMENT);
        input.setAlignmentX(Component.LEFT_ALIGNMENT);
        helper.setAlignmentX(Component.LEFT_ALIGNMENT);
        cell.add(input);
        cell.add(Box.createVerticalStrut(2));
        cell.add(helper);
        return cell;
    }

    private static void addChannelGridComponent(JPanel panel, Component component,
                                                int gridx, int gridy, double weightx,
                                                int fill, Insets insets) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = gridx;
        gbc.gridy = gridy;
        gbc.weightx = weightx;
        gbc.fill = fill;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.insets = insets;
        panel.add(component, gbc);
    }

    private static void updateChannelGridEnabledState(JComboBox<String> methodBox,
                                                      JTextField displayRangeField,
                                                      JTextField saturationField) {
        String selected = selectedProcessingMethod(methodBox);
        displayRangeField.setEnabled(METHOD_CUSTOM.equals(selected));
        saturationField.setEnabled(METHOD_AUTOMATIC.equals(selected));
    }

    private static String selectedProcessingMethod(JComboBox<String> methodBox) {
        Object selected = methodBox == null ? null : methodBox.getSelectedItem();
        return selected == null ? METHOD_NONE : selected.toString();
    }

    private static boolean hasCustomDisplayRange(String value) {
        return !NONE_OPTION.equalsIgnoreCase(value) && value != null && !value.trim().isEmpty();
    }

    private static String normalizeDisplayRange(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? NONE_OPTION : trimmed;
    }

    private MainDialogResult buildHeadlessDefaults(String[] channelNames, String[] defaultMinMax,
                                                    int autoBackgroundIndex) {
        int nCh = channelNames.length;
        MainDialogResult result = new MainDialogResult();
        result.processMethodPerCh = new String[nCh];
        result.customMinMaxPerCh = new String[nCh];
        result.saturationsPerCh = new double[nCh];
        for (int i = 0; i < nCh; i++) {
            String defaultRange = defaultMinMax != null && i < defaultMinMax.length
                    ? defaultMinMax[i] : NONE_OPTION;
            boolean hasCustomValue = hasCustomDisplayRange(defaultRange);
            result.processMethodPerCh[i] = hasCustomValue ? METHOD_CUSTOM : METHOD_AUTOMATIC;
            result.customMinMaxPerCh[i] = hasCustomValue ? defaultRange : NONE_OPTION;
            result.saturationsPerCh[i] = DEFAULT_SATURATION;
        }
        result.createMerge = true;
        result.saveOmeTiff = false;
        result.additionalMergeSpec = "";
        result.useDeconvolvedInput = useDeconvolvedInput;
        result.tileConfig = PresentationTileConfig.disabled(defaultTileOrder(channelNames));
        result.subtractBackground = autoBackgroundIndex >= 0;
        result.backgroundIndex = autoBackgroundIndex;
        result.subtractFromChannels = new boolean[nCh];
        for (int i = 0; i < nCh; i++) {
            result.subtractFromChannels[i] = (autoBackgroundIndex >= 0 && i != autoBackgroundIndex);
        }
        return result;
    }

    static int detectAutofluorescenceChannel(File projectRoot, int channelCount) {
        if (projectRoot == null || channelCount <= 0) {
            return -1;
        }
        ChannelIdentities identities = ChannelConfigIO.readChannelIdentities(
                FlashProjectLayout.forDirectory(projectRoot.getAbsolutePath()).configurationWriteDir());
        return detectAutofluorescenceChannel(identities, channelCount);
    }

    static int detectAutofluorescenceChannel(ChannelIdentities identities, int channelCount) {
        if (identities == null || identities.isEmpty()) {
            return -1;
        }
        for (ChannelIdentities.Entry entry : identities.getEntries()) {
            int index = entry.getChannelIndex();
            if (index < 0 || index >= channelCount) {
                continue;
            }
            String marker = entry.getMarkerId() == null ? "" : entry.getMarkerId().toLowerCase(Locale.ROOT);
            if (marker.contains("autofluorescence")) {
                return index;
            }
        }
        return -1;
    }

    private static double parseSaturationOrDefault(String text, double fallback) {
        if (text == null) return fallback;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return fallback;
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String formatSaturation(double value) {
        return String.valueOf(value);
    }

    private static String stringAt(String[] values, int index, String fallback) {
        if (values == null || index < 0 || index >= values.length) return fallback;
        String value = values[index];
        return value == null ? fallback : value;
    }

    private static double doubleAt(double[] values, int index, double fallback) {
        if (values == null || index < 0 || index >= values.length) return fallback;
        return values[index];
    }

    // ── Processing loop ──

    private void processOneImage(
            ImagePlus imp,
            String[] channelNames,
            String[] channelColors,
            File outDir,
            File tifDir,
            File detailsDir,
            boolean createMerge,
            boolean saveOmeTiff,
            boolean subtractBackground,
            int backgroundIndex,
            boolean[] subtractFromChannels,
            String additionalMergeSpec,
            String[] processMethodPerCh,
            String[] customMinMaxPerCh,
            double[] saturationsPerCh,
            NameParts parts,
            String sourceImageId
    ) {
        long splitStart = verboseLogging ? System.currentTimeMillis() : 0;
        ImagePlus[] chans = ChannelSplitter.split(imp);
        if (chans == null || chans.length == 0) {
            IJ.error("No channels found in " + imp.getTitle());
            return;
        }
        if (channelNames == null || channelNames.length == 0) {
            IJ.error("No channel names configured for " + imp.getTitle());
            closeImageArray(chans);
            return;
        }

        int n = Math.min(chans.length, channelNames.length);
        if (!compactLog) IJ.log("  Channels split: " + n + " channels extracted");
        if (!compactLog && verboseLogging) {
            IJ.log("  [DEBUG] Channel split took " + (System.currentTimeMillis() - splitStart) + " ms");
            IJ.log("  [DEBUG] Image dimensions: " + imp.getWidth() + "x" + imp.getHeight() + "x" + imp.getNSlices());
        }
        ImagePlus[] maxProjs = new ImagePlus[n];

        ImagePlus backgroundStack = null;
        if (subtractBackground && backgroundIndex >= 0 && backgroundIndex < n) {
            backgroundStack = ImageOps.duplicateThreadSafe(chans[backgroundIndex]);
            backgroundStack.setTitle("Background");
            if (!compactLog) IJ.log("  Background channel: " + channelNames[backgroundIndex] + " (duplicated for subtraction)");
        }

        String hemiRegion = parts.fileSuffix();
        double pixelWidthUm = calibratedPixelWidthUm(imp, true);
        double pixelHeightUm = calibratedPixelWidthUm(imp, false);

        for (int c = 0; c < n; c++) {
            String method = stringAt(processMethodPerCh, c, METHOD_NONE);
            String customMM = stringAt(customMinMaxPerCh, c, NONE_OPTION);
            double saturation = doubleAt(saturationsPerCh, c, DEFAULT_SATURATION);
            String channelColor = stringAt(channelColors, c, "Grays");

            if (!compactLog) {
                IJ.log("  > Channel " + (c + 1) + "/" + n + ": " + channelNames[c]);
                IJ.log("    - Color: " + channelColor);
                IJ.log("    - Processing method: " + method);
            }

            ImagePlus chStack = chans[c];
            ImagePlus working = chStack;

            if (subtractBackground && backgroundStack != null) {
                boolean doSubtract = c < subtractFromChannels.length && subtractFromChannels[c];
                if (doSubtract && c != backgroundIndex) {
                    if (!compactLog) IJ.log("    - Background subtraction: subtracting " + channelNames[backgroundIndex] + " from " + channelNames[c]);
                    ImagePlus temp = ImageOps.duplicateThreadSafe(chStack);
                    temp.setTitle(channelNames[c]);
                    ImagePlus subtracted = ImageCalcOps.subtractStackThreadSafe(temp, backgroundStack);
                    working = (subtracted != null) ? subtracted : temp;
                    if (!compactLog) IJ.log("    - Background subtraction: " + (subtracted != null ? "success" : "failed, using original"));
                } else if (c == backgroundIndex) {
                    if (!compactLog) IJ.log("    - Background subtraction: skipped (this is the background channel)");
                }
            }

            if (!compactLog) IJ.log("    - Max Z-projection");
            ImagePlus max = ZProjector.run(working, "max");
            max.setTitle(channelNames[c]);

            applyProcessing(max, method, customMM, saturation);

            if (!compactLog && METHOD_CUSTOM.equals(method)) {
                IJ.log("    - Display range applied: " + customMM);
            }

            maxProjs[c] = max;

            ImagePlus maxForPng = ImageOps.duplicateThreadSafe(max);
            applyPseudoColor(maxForPng, channelColor);
            String safeChannel = ChannelFilenameCodec.toSafe(channelNames[c]);
            String singleSaveName = safeChannel + (hemiRegion.isEmpty() ? "" : "_" + hemiRegion) + ".png";
            File singleOut = new File(outDir, singleSaveName);
            AsyncImageSaver.saveAsPngAsync(maxForPng, singleOut.getAbsolutePath());
            recordPresentationImage(singleOut, parts, sourceImageId, channelNames[c], channelNames[c], c,
                    maxForPng.getWidth(), maxForPng.getHeight(), pixelWidthUm, pixelHeightUm);
            if (!compactLog) IJ.log("    - Saved: " + singleSaveName);
            maxForPng.changes = false;
            maxForPng.close();
            maxForPng.flush();

            if (subtractBackground && c == backgroundIndex) {
                ImagePlus rawMax = ZProjector.run(chStack, "max");
                rawMax.setTitle(channelNames[c] + "_Raw");
                applyProcessing(rawMax, method, customMM, saturation);
                ImagePlus rawPng = ImageOps.duplicateThreadSafe(rawMax);
                applyPseudoColor(rawPng, channelColor);
                String rawSaveName = safeChannel + "_Raw" + (hemiRegion.isEmpty() ? "" : "_" + hemiRegion) + ".png";
                File rawOut = new File(outDir, rawSaveName);
                AsyncImageSaver.saveAsPngAsync(rawPng, rawOut.getAbsolutePath());
                recordOutput(rawOut, "png");
                if (!compactLog) IJ.log("    - Saved raw (unsubtracted): " + rawSaveName);
                rawPng.changes = false;
                rawPng.close();
                rawPng.flush();
                rawMax.changes = false;
                rawMax.close();
                rawMax.flush();
            }

            if (working != chStack) {
                working.changes = false;
                working.close();
                working.flush();
            }
        }

        if (backgroundStack != null) {
            backgroundStack.changes = false;
            backgroundStack.close();
            backgroundStack.flush();
        }

        String omeName = parts.displayLabel() + ".ome.tif";

        if (createMerge) {
            if (!compactLog) IJ.log("  > Merge");
            ImagePlus mergedRgb = mergePseudoColorsToRgb(maxProjs, channelColors);
            if (mergedRgb != null) {
                String mergePngName = hemiRegion.isEmpty() ? "Merge.png" : "Merge_" + hemiRegion + ".png";
                File mergeOut = new File(outDir, mergePngName);
                AsyncImageSaver.saveAsPngAsync(mergedRgb, mergeOut.getAbsolutePath());
                recordPresentationImage(mergeOut, parts, sourceImageId, "Merge", "Merge", -1,
                        mergedRgb.getWidth(), mergedRgb.getHeight(), pixelWidthUm, pixelHeightUm);
                if (!compactLog) IJ.log("    - Saved merge PNG: " + mergePngName);
                mergedRgb.changes = false;
                mergedRgb.close();
                mergedRgb.flush();
            }
        }

        if (saveOmeTiff) {
            if (!compactLog) IJ.log("  > OME-TIFF");
            ImagePlus composite = buildCompositeHyperstack(maxProjs, channelNames);
            if (composite != null) {
                try {
                    IoUtils.mustMkdirs(tifDir);
                    File omeOut = new File(tifDir, omeName);
                    OmeTiffIO.saveOmeTiff(composite, omeOut, channelNames, channelColors);
                    recordOutput(omeOut, "ome-tiff");
                    if (!compactLog) IJ.log("    - Saved OME-TIFF: " + omeOut.getAbsolutePath());
                } catch (Exception e) {
                    recordWarn("Failed to write OME-TIFF: " + e.getMessage());
                    IJ.log("    - WARNING: failed to write OME-TIFF: " + e.getMessage());
                } finally {
                    composite.changes = false;
                    composite.close();
                    composite.flush();
                }
            } else {
                recordWarn("Failed to build OME-TIFF composite for " + omeName);
                IJ.log("    - WARNING: failed to build OME-TIFF composite for " + omeName);
            }
        }

        if (additionalMergeSpec != null && !additionalMergeSpec.trim().isEmpty()) {
            if (!compactLog) IJ.log("  > Additional Merges: " + additionalMergeSpec);
            createAdditionalMerges(additionalMergeSpec, maxProjs, channelNames, channelColors,
                    outDir, parts, sourceImageId, pixelWidthUm, pixelHeightUm);
        }

        // Write per-image details file
        writePerImageDetails(detailsDir, imp.getTitle(), parts, channelNames, channelColors,
                processMethodPerCh, customMinMaxPerCh, saturationsPerCh, n,
                createMerge, saveOmeTiff, subtractBackground, backgroundIndex, subtractFromChannels);

        for (ImagePlus mp : maxProjs) {
            if (mp != null) {
                mp.changes = false;
                mp.close();
                mp.flush();
            }
        }
        closeImageArray(chans);
    }

    private static void closeImageArray(ImagePlus[] images) {
        if (images == null) return;
        for (ImagePlus image : images) {
            if (image != null) {
                image.changes = false;
                image.close();
                image.flush();
            }
        }
    }

    private void recordPresentationImage(File imageFile,
                                          NameParts parts,
                                          String sourceImageId,
                                          String outputName,
                                          String stainName,
                                          int channelIndex,
                                         int widthPx,
                                         int heightPx,
                                         double pixelWidthUm,
                                         double pixelHeightUm) {
        if (imageFile == null || parts == null) return;
        recordOutput(imageFile, "png");
        presentationTileRecords.add(new PresentationTileRecord(
                imageFile,
                parts.animal,
                parts.hemisphere,
                parts.region,
                sourceImageId,
                outputName,
                stainName,
                channelIndex,
                widthPx,
                heightPx,
                pixelWidthUm,
                pixelHeightUm));
    }

    private void writePresentationTileOutputs(String directory, FlashProjectLayout layout,
                                              PresentationTileConfig config) {
        if (config == null || (!config.createOverviewTile() && !config.annotateIndividualImages())) {
            return;
        }

        List<PresentationTileRecord> records;
        synchronized (presentationTileRecords) {
            records = new ArrayList<PresentationTileRecord>(presentationTileRecords);
        }
        File manifestFile = presentationManifestFile(layout);
        records = mergeExistingPresentationManifest(manifestFile, records);
        if (records.isEmpty()) {
            IJ.log("  - Presentation overview tile skipped: no saved image records were found.");
            return;
        }

        LinkedHashSet<String> animals = new LinkedHashSet<String>();
        for (PresentationTileRecord record : records) {
            animals.add(record.animal());
        }
        Map<String, String> conditions = ConditionManifestIO.resolveAssignments(directory, animals);

        try {
            PresentationTileWriter.writeRequestedOutputs(
                    layout.presentationAnnotatedDir(),
                    layout.presentationTilesDir(),
                    manifestFile,
                    records, conditions, config);
            recordOutput(manifestFile, "csv");
            if (config.createOverviewTile()) {
                String grouped = config.groupRowsBy() == PresentationTileConfig.GroupRowsBy.CONDITION
                        ? "ByCondition" : "ByAnimal";
                recordOutput(new File(layout.presentationTilesDir(),
                        "Presentation_Overview_" + grouped + ".png"), "png");
            }
        } catch (IOException e) {
            recordWarn("Failed to write presentation overview tile: " + e.getMessage());
            IJ.log("  - WARNING: failed to write presentation overview tile: " + e.getMessage());
        }
    }

    private static List<PresentationTileRecord> mergeExistingPresentationManifest(
            File manifest, List<PresentationTileRecord> currentRecords) {
        LinkedHashMap<String, PresentationTileRecord> merged =
                new LinkedHashMap<String, PresentationTileRecord>();
        if (manifest != null && manifest.isFile()) {
            try {
                List<PresentationTileRecord> existing = PresentationTileWriter.readManifest(manifest);
                for (PresentationTileRecord record : existing) {
                    if (record.imageFile() != null && record.imageFile().isFile()) {
                        merged.put(presentationRecordKey(record), record);
                    }
                }
            } catch (IOException e) {
                IJ.log("  - Warning: could not read previous presentation image manifest: "
                        + e.getMessage());
            }
        }
        if (currentRecords != null) {
            for (PresentationTileRecord record : currentRecords) {
                merged.put(presentationRecordKey(record), record);
            }
        }
        return new ArrayList<PresentationTileRecord>(merged.values());
    }

    private static String presentationRecordKey(PresentationTileRecord record) {
        if (record == null) return "";
        return record.imageKey() + "\n" + record.outputName();
    }

    private static String presentationSourceImageId(ResolvedImageMetadata metadata,
                                                    String imageTitle,
                                                    int seriesIndex) {
        String manifestKey = metadata == null ? "" : trimToEmpty(metadata.imageKey);
        if (!manifestKey.isEmpty()) return manifestKey;

        int normalizedSeries = seriesIndex < 1 ? 1 : seriesIndex;
        String title = trimToEmpty(imageTitle);
        if (title.isEmpty()) {
            return "series:" + normalizedSeries;
        }
        return "series:" + normalizedSeries + "|" + title;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static double calibratedPixelWidthUm(ImagePlus imp, boolean xAxis) {
        if (imp == null || imp.getCalibration() == null) return Double.NaN;
        double value = xAxis ? imp.getCalibration().pixelWidth : imp.getCalibration().pixelHeight;
        if (!Double.isFinite(value) || value <= 0) return Double.NaN;
        double multiplier = calibrationUnitToMicronMultiplier(imp.getCalibration().getUnit());
        if (!Double.isFinite(multiplier) || multiplier <= 0) return Double.NaN;
        return value * multiplier;
    }

    private static double calibrationUnitToMicronMultiplier(String unit) {
        String normalized = unit == null ? "" : unit.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || "pixel".equals(normalized) || "pixels".equals(normalized)) {
            return Double.NaN;
        }
        if ("um".equals(normalized) || "\u00b5m".equals(normalized) || "\u03bcm".equals(normalized)
                || "micron".equals(normalized) || "microns".equals(normalized)
                || "micrometer".equals(normalized) || "micrometers".equals(normalized)) {
            return 1.0;
        }
        if ("nm".equals(normalized) || "nanometer".equals(normalized) || "nanometers".equals(normalized)) {
            return 0.001;
        }
        if ("mm".equals(normalized) || "millimeter".equals(normalized) || "millimeters".equals(normalized)) {
            return 1000.0;
        }
        if ("cm".equals(normalized) || "centimeter".equals(normalized) || "centimeters".equals(normalized)) {
            return 10000.0;
        }
        if ("m".equals(normalized) || "meter".equals(normalized) || "meters".equals(normalized)) {
            return 1000000.0;
        }
        return Double.NaN;
    }

    private void writePerImageDetails(
            File outDir, String imageTitle, NameParts parts,
            String[] channelNames, String[] channelColors,
            String[] processMethodPerCh, String[] customMinMaxPerCh, double[] saturationsPerCh,
            int nCh, boolean createMerge, boolean saveOmeTiff,
            boolean subtractBackground, int backgroundIndex, boolean[] subtractFromChannels) {
        String hemiRegion = parts.fileSuffix();
        String detailsName = ChannelFilenameCodec.toSafe(channelNames[0]) + (hemiRegion.isEmpty() ? "" : "_" + hemiRegion) + "_details.txt";
        File detailsFile = new File(outDir, detailsName);

        try {
            IoUtils.mustMkdirs(outDir);
            try (Writer w = new OutputStreamWriter(new FileOutputStream(detailsFile), StandardCharsets.UTF_8)) {
            w.write("Image: " + imageTitle + "\n");
            w.write("Processed: " + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date()) + "\n");
            w.write("Hemisphere: " + (parts.hemisphere.isEmpty() ? "N/A" : parts.hemisphere) + "\n");
            w.write("Region: " + (parts.region.isEmpty() ? "N/A" : parts.region) + "\n");
            w.write("\n");

            w.write("Channels:\n");
            for (int c = 0; c < nCh; c++) {
                String method = stringAt(processMethodPerCh, c, "None");
                String color = stringAt(channelColors, c, "Grays");
                StringBuilder methodDesc = new StringBuilder();
                if (METHOD_AUTOMATIC.equals(method)) {
                    double sat = doubleAt(saturationsPerCh, c, DEFAULT_SATURATION);
                    methodDesc.append("Automatic (sat=").append(sat).append(")");
                } else if (METHOD_CUSTOM.equals(method)) {
                    String mm = stringAt(customMinMaxPerCh, c, "?");
                    methodDesc.append("Custom Min-Max (").append(mm).append(")");
                } else {
                    methodDesc.append(method);
                }
                w.write("  C" + (c + 1) + " " + channelNames[c] + " — " + color + " — " + methodDesc + "\n");
            }
            w.write("\n");

            w.write("Merge: " + (createMerge ? "all channels" : "none") + "\n");
            w.write("OME-TIFF: " + (saveOmeTiff ? "yes" : "no") + "\n");

            if (subtractBackground && backgroundIndex >= 0 && backgroundIndex < nCh) {
                StringBuilder fromChs = new StringBuilder();
                for (int c = 0; c < nCh; c++) {
                    if (c < subtractFromChannels.length && subtractFromChannels[c] && c != backgroundIndex) {
                        if (fromChs.length() > 0) fromChs.append(", ");
                        fromChs.append(channelNames[c]);
                    }
                }
                w.write("Background Subtraction: " + channelNames[backgroundIndex]
                        + " from " + fromChs + "\n");
            } else {
                w.write("Background Subtraction: none\n");
            }
            }
            recordOutput(detailsFile, "txt");
        } catch (IOException e) {
            recordWarn("Failed to write per-image details " + detailsFile.getName()
                    + ": " + e.getMessage());
            IJ.log("WARNING: failed to write per-image details: " + detailsFile.getName() + " — " + e.getMessage());
        }
    }

    private void applyProcessing(ImagePlus imp, String method, String customMinMax, double saturation) {
        if (method == null) return;

        switch (method) {
            case METHOD_NONE:
                break;
            case METHOD_AUTOMATIC:
                // Thread-safe: ContrastEnhancer operates on the ImagePlus directly
                new ContrastEnhancer().stretchHistogram(imp, saturation);
                if (!compactLog) IJ.log("    - Saturation: " + saturation);
                break;
            case METHOD_MANUAL:
                if (!canRunManualAdjustment(headless, ParallelContext.isNested())) {
                    if (!compactLog) IJ.log("    - Manual adjustment skipped (headless/parallel mode)");
                } else {
                    IJ.run(imp, "Brightness/Contrast...", "");
                    showOrLog("Manual Processing",
                            "Adjust brightness/contrast for " + imp.getTitle() + ", then click OK.");
                    if (!compactLog) IJ.log("    - Manual adjustment applied");
                }
                break;
            case METHOD_CUSTOM:
                if (customMinMax != null && !customMinMax.equalsIgnoreCase(NONE_OPTION)) {
                    String[] mm = customMinMax.split("-");
                    if (mm.length == 2) {
                        try {
                            double min = Double.parseDouble(mm[0]);
                            double max = Double.parseDouble(mm[1]);
                            imp.setDisplayRange(min, max);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                break;
            default:
                break;
        }
    }

    static boolean hasManualProcessing(String[] methods) {
        if (methods == null) return false;
        for (String method : methods) {
            if (METHOD_MANUAL.equals(method)) return true;
        }
        return false;
    }

    static boolean shouldOverrideHideWindowsForManualProcessing(boolean hasManual,
                                                                boolean headless,
                                                                CLIConfig cliConfig,
                                                                boolean runtimeHeadless) {
        return hasManual && headless && cliConfig == null && !runtimeHeadless;
    }

    static boolean canRunManualAdjustment(boolean headless, boolean runningInParallelWorker) {
        return !headless && !runningInParallelWorker;
    }

    private void enableVisibleWindowsForManualProcessingIfNeeded(boolean hasManual) {
        if (shouldOverrideHideWindowsForManualProcessing(
                hasManual, headless, cliConfig, GraphicsEnvironment.isHeadless())) {
            IJ.log("[Make Presentation Images] Manual brightness/contrast needs visible windows; "
                    + "overriding Hide Image Windows for this analysis.");
            headless = false;
        }
    }

    private static ImagePlus buildCompositeHyperstack(ImagePlus[] maxProjs, String[] channelNames) {
        if (maxProjs == null || maxProjs.length == 0) return null;
        int n = maxProjs.length;
        ImagePlus first = null;
        for (ImagePlus mp : maxProjs) {
            if (mp != null) { first = mp; break; }
        }
        if (first == null) return null;

        int w = first.getWidth();
        int h = first.getHeight();
        ij.ImageStack stack = new ij.ImageStack(w, h);

        for (int c = 0; c < n; c++) {
            ImagePlus mp = maxProjs[c];
            String label = (channelNames != null && c < channelNames.length && channelNames[c] != null)
                    ? channelNames[c] : ("C" + (c + 1));
            if (mp == null) {
                stack.addSlice(label, first.getProcessor().createProcessor(w, h));
            } else {
                stack.addSlice(label, mp.getProcessor().duplicate());
            }
        }

        ImagePlus out = new ImagePlus("Composite", stack);
        out.setDimensions(n, 1, 1);
        out.setOpenAsHyperStack(true);
        return out;
    }

    private static ImagePlus mergePseudoColorsToRgb(ImagePlus[] chans, String[] channelColors) {
        if (chans == null || chans.length == 0) return null;

        ImagePlus first = null;
        for (ImagePlus c : chans) {
            if (c != null) { first = c; break; }
        }
        if (first == null) return null;

        int w = first.getWidth();
        int h = first.getHeight();
        int[] rgb = new int[w * h];

        for (int i = 0; i < chans.length; i++) {
            ImagePlus imp = chans[i];
            if (imp == null) continue;
            if (imp.getWidth() != w || imp.getHeight() != h) {
                throw new IllegalArgumentException("Channel " + (i + 1)
                        + " dimensions " + imp.getWidth() + "x" + imp.getHeight()
                        + " do not match expected " + w + "x" + h
                        + " while merging pseudo-colors.");
            }

            String cname = (channelColors != null && i < channelColors.length && channelColors[i] != null)
                    ? channelColors[i] : "gray";
            String c = cname.trim().toLowerCase(Locale.ROOT);

            boolean addR = c.equals("red") || c.equals("magenta") || c.equals("yellow")
                    || c.equals("grey") || c.equals("gray") || c.equals("grays");
            boolean addG = c.equals("green") || c.equals("cyan") || c.equals("yellow")
                    || c.equals("grey") || c.equals("gray") || c.equals("grays");
            boolean addB = c.equals("blue") || c.equals("cyan") || c.equals("magenta")
                    || c.equals("grey") || c.equals("gray") || c.equals("grays");

            ij.process.ImageProcessor ip = imp.getProcessor();
            int bitDepth = imp.getBitDepth();
            double dispMin = ip.getMin();
            double dispMax = ip.getMax();
            double dispRange = dispMax - dispMin;
            if (!(dispRange > 0)) {
                dispMin = 0;
                dispMax = bitDepth == 16 ? 65535 : 255;
                dispRange = dispMax - dispMin;
            }

            for (int p = 0; p < w * h; p++) {
                double raw;
                if (bitDepth == 8) {
                    raw = ip.get(p) & 0xff;
                } else if (bitDepth == 16) {
                    raw = ip.get(p) & 0xffff;
                } else {
                    raw = ip.getf(p);
                }
                double scaled = (raw - dispMin) / dispRange;
                int val;
                if (scaled <= 0) val = 0;
                else if (scaled >= 1) val = 255;
                else val = (int) Math.round(scaled * 255.0);

                int packed = rgb[p];
                int r = (packed >> 16) & 0xff;
                int g = (packed >> 8) & 0xff;
                int b = packed & 0xff;

                if (addR) r = Math.min(255, r + val);
                if (addG) g = Math.min(255, g + val);
                if (addB) b = Math.min(255, b + val);

                rgb[p] = (r << 16) | (g << 8) | b;
            }
        }

        ColorProcessor cp = new ColorProcessor(w, h);
        cp.setPixels(rgb);
        return new ImagePlus("Merge", cp);
    }

    private void createAdditionalMerges(
            String spec,
            ImagePlus[] maxProjs,
            String[] channelNames,
            String[] channelColors,
            File outDir,
            NameParts parts,
            String sourceImageId,
            double pixelWidthUm,
            double pixelHeightUm
    ) {
        String[] groups = spec.trim().split("\\s+");
        for (String g : groups) {
            String[] nums = g.split("-");
            if (nums.length < 2) continue;

            java.util.List<Integer> idxs = new java.util.ArrayList<>();
            StringBuilder mergeNames = new StringBuilder();

            for (String num : nums) {
                int idx;
                try {
                    idx = Integer.parseInt(num.trim()) - 1;
                } catch (NumberFormatException e) {
                    continue;
                }
                if (idx < 0 || idx >= maxProjs.length) continue;
                idxs.add(idx);
                mergeNames.append(ChannelFilenameCodec.toSafe(channelNames[idx])).append(" ");
            }

            if (idxs.size() < 2) continue;
            String namesTrim = mergeNames.toString().trim();
            String hemiRegion = parts.fileSuffix();

            ImagePlus[] sub = new ImagePlus[idxs.size()];
            String[] subColors = new String[idxs.size()];
            for (int i = 0; i < idxs.size(); i++) {
                int idx = idxs.get(i);
                sub[i] = maxProjs[idx];
                subColors[i] = stringAt(channelColors, idx, "Grays");
            }

            ImagePlus mergedRgb = mergePseudoColorsToRgb(sub, subColors);
            if (mergedRgb != null) {
                String mergeSaveName = "Merge(" + namesTrim + ")" + (hemiRegion.isEmpty() ? "" : "_" + hemiRegion) + ".png";
                File mergeOut = new File(outDir, mergeSaveName);
                AsyncImageSaver.saveAsPngAsync(mergedRgb, mergeOut.getAbsolutePath());
                String outputName = "Merge(" + namesTrim + ")";
                recordPresentationImage(mergeOut, parts, sourceImageId, outputName, outputName, -1,
                        mergedRgb.getWidth(), mergedRgb.getHeight(), pixelWidthUm, pixelHeightUm);
                if (!compactLog) IJ.log("    - Saved additional merge: " + mergeSaveName);
                mergedRgb.changes = false;
                mergedRgb.close();
                mergedRgb.flush();
            }
        }
    }

    /**
     * Applies a pseudocolor LUT to the image. Thread-safe: uses direct LUT creation
     * instead of IJ.run() macro commands.
     */
    private static void applyPseudoColor(ImagePlus imp, String colorName) {
        if (colorName == null || imp == null) return;
        String c = colorName.trim().toLowerCase(Locale.ROOT);

        byte[] r = new byte[256], g = new byte[256], b = new byte[256];
        boolean addR = c.equals("red") || c.equals("magenta") || c.equals("yellow")
                || c.equals("grey") || c.equals("gray") || c.equals("grays");
        boolean addG = c.equals("green") || c.equals("cyan") || c.equals("yellow")
                || c.equals("grey") || c.equals("gray") || c.equals("grays");
        boolean addB = c.equals("blue") || c.equals("cyan") || c.equals("magenta")
                || c.equals("grey") || c.equals("gray") || c.equals("grays");

        for (int i = 0; i < 256; i++) {
            if (addR) r[i] = (byte) i;
            if (addG) g[i] = (byte) i;
            if (addB) b[i] = (byte) i;
        }

        LUT lut = new LUT(r, g, b);
        imp.getProcessor().setLut(lut);
    }

    static Color channelHeaderColorForLut(String colorName) {
        if (colorName == null) return Color.DARK_GRAY;
        String c = colorName.trim().toLowerCase(Locale.ROOT);
        if ("red".equals(c)) return Color.RED;
        if ("green".equals(c)) return new Color(0, 128, 0);
        if ("blue".equals(c)) return Color.BLUE;
        if ("cyan".equals(c)) return new Color(0, 128, 128);
        if ("magenta".equals(c)) return Color.MAGENTA;
        if ("yellow".equals(c)) return new Color(153, 119, 0);
        return Color.DARK_GRAY;
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

    private void updateBinMinMax(String directory, String[] processMethodPerCh,
                                 String[] customMinMaxPerCh, int nCh) {
        String[] minMaxValues = new String[nCh];
        for (int i = 0; i < nCh; i++) {
            if (METHOD_CUSTOM.equals(processMethodPerCh[i])
                    && customMinMaxPerCh[i] != null
                    && !NONE_OPTION.equalsIgnoreCase(customMinMaxPerCh[i])) {
                minMaxValues[i] = customMinMaxPerCh[i];
            } else {
                minMaxValues[i] = NONE_OPTION;
            }
        }
        try {
            BinConfigIO.updateMinMax(directory, minMaxValues);
            recordOutput(new File(FlashProjectLayout.forDirectory(directory).configurationWriteDir(),
                    ChannelConfigIO.FILE_NAME), "json");
        } catch (IOException e) {
            recordWarn("Failed to sync min-max to channel_config.json: " + e.getMessage());
            IJ.log("Warning: failed to sync min-max to channel_config.json: " + e.getMessage());
        }
    }

    private void saveSaturations(String directory, String[] channelNames,
                                 String[] processMethodPerCh, double[] saturationsPerCh) {
        File binDir = FlashProjectLayout.forDirectory(directory).configurationWriteDir();
        try {
            IoUtils.mustMkdirs(binDir);
        } catch (IOException e) {
            recordWarn("Could not create configuration directory for saturations: " + e.getMessage());
            IJ.log("[FLASH] Could not create configuration directory: " + e.getMessage()
                    + " — saturations will not be saved.");
            return;
        }
        File satFile = new File(binDir, "Saturations.txt");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(satFile), StandardCharsets.UTF_8)) {
            for (int i = 0; i < channelNames.length; i++) {
                String method = (i < processMethodPerCh.length) ? processMethodPerCh[i] : METHOD_NONE;
                String satValue = METHOD_AUTOMATIC.equals(method)
                        ? String.valueOf(saturationsPerCh[i])
                        : "N/A";
                if (i > 0) w.write("\n");
                w.write(channelNames[i] + " " + satValue);
            }
            w.write("\n");
            recordOutput(satFile, "txt");
        } catch (IOException e) {
            recordWarn("Failed to save saturations: " + e.getMessage());
            IJ.log("Warning: failed to save saturations: " + e.getMessage());
        }
    }

    static File splitMergeImageWriteRoot(String directory) {
        return FlashProjectLayout.forDirectory(directory).presentationImagesDir();
    }

    static File splitMergeOmeTiffWriteRoot(String directory) {
        return FlashProjectLayout.forDirectory(directory).presentationOmeTiffDir();
    }

    static File splitMergeAnalysisDetailsRoot(String directory) {
        return splitMergeAnalysisDetailsRoot(FlashProjectLayout.forDirectory(directory));
    }

    private static File splitMergeAnalysisDetailsRoot(FlashProjectLayout layout) {
        // TODO(results-folder-layout-plan stage 08): unify analysis-details routing
        // across analyses inside Results/Run Records/analysis_details/.
        return new File(layout.analysisDetailsWriteDir(), "Split and Merge");
    }

    private static File presentationManifestFile(FlashProjectLayout layout) {
        return new File(layout.presentationImagesRoot(), "Presentation_Image_Manifest.csv");
    }

    static boolean splitMergePrimaryChannelOutputExists(String directory, File primaryOutRoot,
                                                        NameParts parts, String channelName) {
        if (parts == null || channelName == null) return false;
        String suffix = parts.fileSuffix();
        String pngName = ChannelFilenameCodec.toSafe(channelName)
                + (suffix.isEmpty() ? "" : "_" + suffix) + ".png";
        File check = new File(new File(primaryOutRoot, parts.animal), pngName);
        return check.exists();
    }

    private static void closeAllWindowsExceptLog() {
        // Close all image windows
        int[] ids = WindowManager.getIDList();
        if (ids != null) {
            for (int id : ids) {
                ImagePlus img = WindowManager.getImage(id);
                if (img != null) {
                    img.changes = false;
                    img.close();
                    img.flush();
                }
            }
        }
        // Close all non-image windows except Log
        Frame[] frames = WindowManager.getNonImageWindows();
        if (frames != null) {
            for (Frame frame : frames) {
                if (frame == null) continue;
                String title = frame.getTitle();
                if ("Log".equals(title)) continue;
                frame.dispose();
            }
        }
    }

    private DeferredImageSupplier wrapRunRecordSupplier(final DeferredImageSupplier rawSupplier) {
        if (rawSupplier == null) return null;
        return new DeferredImageSupplier(rawSupplier) {
            @Override
            public ImagePlus openSeries(int seriesIndex) throws Exception {
                return openRecorded(seriesIndex, false, -1);
            }

            @Override
            public ImagePlus openSeriesMaterialized(int seriesIndex) throws Exception {
                return openRecorded(seriesIndex, true, -1);
            }

            @Override
            public ImagePlus openSeriesMaterializedChannel(int seriesIndex, int channelIndex) throws Exception {
                return openRecorded(seriesIndex, true, channelIndex);
            }

            private ImagePlus openRecorded(int seriesIndex, boolean materialized, int channelIndex) throws Exception {
                AnalysisRunContext.InputHandle inputHandle =
                        recordInputStart(sourceFileForSeries(rawSupplier, seriesIndex), seriesIndex);
                long startedMillis = System.currentTimeMillis();
                try {
                    ImagePlus image = channelIndex >= 0
                            ? rawSupplier.openSeriesMaterializedChannel(seriesIndex, channelIndex)
                            : materialized
                            ? rawSupplier.openSeriesMaterialized(seriesIndex)
                            : rawSupplier.openSeries(seriesIndex);
                    recordInputEnd(inputHandle, image == null ? "empty" : "processed", startedMillis);
                    return image;
                } catch (Exception e) {
                    recordInputEnd(inputHandle, "failed", startedMillis);
                    recordError("Failed to open Split & Merge input series " + (seriesIndex + 1), e);
                    throw e;
                } catch (Error e) {
                    recordInputEnd(inputHandle, "failed", startedMillis);
                    recordError("Failed to open Split & Merge input series " + (seriesIndex + 1), e);
                    throw e;
                }
            }
        };
    }

    private static File sourceFileForSeries(DeferredImageSupplier supplier, int seriesIndex) {
        if (supplier == null) return null;
        try {
            return supplier.getContainerFileForSeries(seriesIndex);
        } catch (RuntimeException e) {
            return supplier.getContainerFile();
        }
    }

    private AnalysisRunContext.InputHandle recordInputStart(File source, int seriesIndex) {
        if (runRecordContext == null) {
            return null;
        }
        return runRecordContext.recordInputStart(source, seriesIndex, null);
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

    private String[] promptChannelNames() {
        String s = IJ.getString("Antibody Names (space-separated)", "");
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) {
            IJ.error("No channel names provided.");
            return null;
        }
        return s.split("\\s+");
    }

    private String[] promptChannelColors(int nChannels) {
        String s = IJ.getString("Channel Colors (space-separated)", "");
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) {
            IJ.error("No colors provided.");
            return null;
        }
        String[] arr = s.split("\\s+");
        if (arr.length < nChannels) {
            IJ.error("Provided " + arr.length + " colors; need at least " + nChannels + ".");
            return null;
        }
        return arr;
    }
}
