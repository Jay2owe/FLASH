package flash.pipeline.analyses;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.bin.BinField;
import flash.pipeline.bin.BinSetupDispatcher;
import flash.pipeline.bin.ChannelIdentities;
import flash.pipeline.bin.ChannelIdentitiesIO;
import flash.pipeline.analyses.wizard.SplitMergePreset;
import flash.pipeline.analyses.wizard.SplitMergePresetIO;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.deconv.DeconvolvedInputResolver;
import flash.pipeline.image.AdaptiveParallelism;
import flash.pipeline.image.ImageCalcOps;
import flash.pipeline.image.OrientationOps;
import flash.pipeline.image.ParallelContext;
import flash.pipeline.image.ProcessingNotes;
import flash.pipeline.io.AsyncImageSaver;
import flash.pipeline.io.BoundedImageLoader;
import flash.pipeline.io.DeferredImageSupplier;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.FlashProjectLayout.AnalysisFolder;
import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.io.IoUtils;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.io.OmeTiffIO;
import flash.pipeline.naming.ChannelFilenameCodec;
import flash.pipeline.naming.ImageOrientationResolver;
import flash.pipeline.naming.ImageNameParser;
import flash.pipeline.naming.NameParts;
import flash.pipeline.naming.ResolvedImageMetadata;
import flash.pipeline.results.AnalysisDetailsWriter;
import flash.pipeline.results.SplitAndMergeDetailsWriter;
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.FeatureDependencyGate;
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

import javax.swing.*;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public class SplitAndMergeImageChannelsAnalysis implements Analysis {

    private boolean headless = false;
    private boolean suppressDialogs = false;
    private boolean aggressiveMemory = false;
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

    private static final String METHOD_NONE = "None";
    private static final String METHOD_AUTOMATIC = "Automatic";
    private static final String METHOD_MANUAL = "Manual";
    private static final String METHOD_CUSTOM = "Custom Min-Max Display Ranges";
    private static final String[] PROCESS_METHODS = {METHOD_NONE, METHOD_AUTOMATIC, METHOD_MANUAL, METHOD_CUSTOM};
    private static final String NONE_OPTION = "None";

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
    }

    @Override
    public void execute(String directory) {
        if (!FeatureDependencyGate.gate(DependencyId.BIO_FORMATS_RUNTIME, "Split and Merge Image Channels")) {
            return;
        }

        BinSetupDispatcher.Outcome outcome = BinSetupDispatcher.ensure(
                directory, "Split & Merge Image Channels", requiredBinFields(),
                benefitsFromRois(), suppressDialogs, cliConfig);
        if (outcome == BinSetupDispatcher.Outcome.CANCELLED) {
            IJ.log("[FLASH] Split & Merge cancelled by user.");
            return;
        }

        BinConfig binCfg = BinConfigIO.readPartialFromDirectory(directory);
        String[] channelNames = binCfg.channelNames.toArray(new String[0]);
        String[] channelColors = binCfg.channelColors.toArray(new String[0]);
        String[] binMinMax = binCfg.channelMinMax.isEmpty()
                ? null : binCfg.channelMinMax.toArray(new String[0]);

        if (channelNames == null || channelNames.length == 0) {
            channelNames = promptChannelNames();
        }
        if (channelNames == null) return;

        if (channelColors == null || channelColors.length < channelNames.length) {
            channelColors = promptChannelColors(channelNames.length);
        }
        if (channelColors == null) return;

        int nCh = channelNames.length;

        String[] defaultMinMax = new String[nCh];
        for (int i = 0; i < nCh; i++) {
            defaultMinMax[i] = (binMinMax != null && i < binMinMax.length && binMinMax[i] != null)
                    ? binMinMax[i] : NONE_OPTION;
        }

        MainDialogResult mdr = showMainDialog(channelNames, defaultMinMax, new File(directory));
        if (mdr == null) return;
        useDeconvolvedInput = mdr.useDeconvolvedInput;

        File splitMergeRoot = splitMergeWriteRoot(directory);
        File outRoot = splitMergeImageWriteRoot(directory);
        File tifDir = splitMergeOmeTiffWriteRoot(directory);
        File detailsRoot = splitMergeDetailsWriteRoot(directory);
        try {
            IoUtils.mustMkdirs(splitMergeRoot);
            IoUtils.mustMkdirs(outRoot);
            IoUtils.mustMkdirs(tifDir);
            IoUtils.mustMkdirs(detailsRoot);
        } catch (IOException e) {
            IJ.log("[FLASH] Could not create Split and Merge output directory: " + e.getMessage());
            return;
        }

        // Determine effective thread count early so we know which loading strategy to use
        boolean hasManual = false;
        for (String m : mdr.processMethodPerCh) {
            if (METHOD_MANUAL.equals(m)) { hasManual = true; break; }
        }
        final int effectiveThreads = (hasManual || parallelThreads <= 1) ? 1 : parallelThreads;

        // Both paths use DeferredImageSupplier; parallel path wraps it in BoundedImageLoader
        DeferredImageSupplier supplier = null;
        int totalImages;

        try {
            supplier = ImageSourceDispatcher.createSupplier(directory);
            totalImages = supplier.getTotalSeries();
            supplier = wrapInputSupplier(directory, supplier);
        } catch (Exception e) {
            IJ.log("Split and Merge Image Channels: " + e.getMessage());
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
        } catch (Exception ignore) { }

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
        IJ.log("  - Saturations saved to FLASH/00 - Configuration/Saturations.txt");

        // Sync min-max values back to Channel_Data.txt line 5
        updateBinMinMax(directory, mdr.processMethodPerCh, mdr.customMinMaxPerCh, nCh);
        IJ.log("  - Min-max display ranges synced to Channel_Data.txt");

        try {
            AnalysisDetailsWriter.write(
                    splitMergeRoot,
                    "Process Images",
                    channelNames,
                    channelColors,
                    processingNotes,
                    new File[] {null},
                    new String[] {"N/A"},
                    startTimeMillis
            );
            IJ.log("  - Analysis Details written");
        } catch (Exception e) {
            IJ.log("  - WARNING: failed to write Analysis Details: " + e.getMessage());
        }

        try {
            SplitAndMergeDetailsWriter.write(
                    splitMergeRoot,
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
            IJ.log("  - SplitAndMerge Details written");
        } catch (Exception e) {
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
        String lifFileName = null;
        try {
            List<SeriesMeta> metas = ImageSourceDispatcher.readAllMetadata(directory);
            metaNames = new ArrayList<String>();
            for (SeriesMeta m : metas) metaNames.add(m.name);
            lifFileName = supplier.getLifFile().getName();
        } catch (Exception e) {
            // Can't read metadata — include all series
            for (int i = 0; i < totalImages; i++) work.add(i);
            return work;
        }

        for (int i = 0; i < totalImages; i++) {
            String metaName = (metaNames != null && i < metaNames.size())
                    ? metaNames.get(i) : null;

            if (metaName == null || lifFileName == null) {
                work.add(i);
                continue;
            }

            // Reconstruct the Bio-Formats title: "filename.lif - SeriesName"
            String expectedTitle = lifFileName + " - " + metaName;
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
                    IJ.log("ERROR: Failed to load prefetched series " + (seriesIdx + 1) + ": " + e.getMessage());
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
                    IJ.log("ERROR: Failed to open series " + (seriesIdx + 1) + ": " + e.getMessage());
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
                    mdr.processMethodPerCh, mdr.customMinMaxPerCh, mdr.saturationsPerCh, parts);

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

            if (aggressiveMemory) {
                if (verboseLogging) IJ.log("  [DEBUG] Aggressive memory clearing...");
                System.gc();
                IJ.freeMemory();
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
                                IJ.log("[FLASH] Could not create per-animal directory "
                                        + perAnimalDir + ": " + e.getMessage()
                                        + " — skipping " + imgTitle);
                                imp.changes = false;
                                imp.close();
                                imp.flush();
                                int done = completed.incrementAndGet();
                                IJ.showProgress(done, scheduled);
                                continue;
                            }

                            processOneImage(imp, channelNames, channelColors, perAnimalDir, tifDir,
                                    new File(detailsRoot, parts.animal),
                                    mdr.createMerge, mdr.saveOmeTiff, mdr.subtractBackground, mdr.backgroundIndex,
                                    mdr.subtractFromChannels, mdr.additionalMergeSpec,
                                    mdr.processMethodPerCh, mdr.customMinMaxPerCh, mdr.saturationsPerCh,
                                    parts);

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

                            if (aggressiveMemory) {
                                System.gc();
                                IJ.freeMemory();
                            }
                        } catch (Exception e) {
                            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                            IJ.log("[" + (idx + 1) + "/" + scheduled + "] ERROR: " + msg);
                            e.printStackTrace();
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

    // ── Main options dialog (PipelineDialog) ──

    private MainDialogResult showMainDialog(final String[] channelNames, String[] defaultMinMax, File projectRoot) {
        final int nCh = channelNames.length;
        final int autoBackgroundIndex = detectAutofluorescenceChannel(projectRoot, nCh);
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return buildHeadlessDefaults(channelNames, defaultMinMax, autoBackgroundIndex);
        }
        final SplitMergePresetIO presetIO = new SplitMergePresetIO(
                projectRoot == null ? new File(".") : projectRoot);

        final String[] bgChoices = new String[nCh + 1];
        bgChoices[0] = NONE_OPTION;
        for (int i = 0; i < nCh; i++) {
            bgChoices[i + 1] = channelNames[i] + (i == autoBackgroundIndex ? " (auto-detected)" : "");
        }

        final PipelineDialog pd = new PipelineDialog("Split and Merge Image Channels", PipelineDialog.Phase.SETUP);

        // ── Section: Input ──
        pd.addHeader("Input");
        final ToggleSwitch useDeconvToggle = pd.addToggle("Use deconvolved stacks if available",
                useDeconvolvedInput);

        // Custom row: Preset combo + Save-as-preset button (kept out of the
        // combos retrieval list so retrieval order stays clean).
        final JComboBox<String> presetCombo = new JComboBox<String>();
        populatePresetChoice(presetCombo, presetIO);
        final JButton savePresetBtn = new JButton("Save as preset...");
        JPanel presetRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        presetRow.setOpaque(false);
        presetRow.add(new JLabel("Preset:"));
        presetRow.add(presetCombo);
        presetRow.add(savePresetBtn);
        pd.addComponent(presetRow);

        // ── Section: Channel Processing ──
        pd.addHeader("Channel Processing");

        @SuppressWarnings("unchecked")
        final JComboBox<String>[] methodBoxes = new JComboBox[nCh];
        final JTextField[] satFields = new JTextField[nCh];
        final JTextField[] mmFields = new JTextField[nCh];

        for (int ch = 0; ch < nCh; ch++) {
            pd.addSubHeader(channelNames[ch]);

            boolean hasCustomValue = !NONE_OPTION.equalsIgnoreCase(defaultMinMax[ch])
                    && defaultMinMax[ch] != null && !defaultMinMax[ch].trim().isEmpty();
            String defaultMethod = hasCustomValue ? METHOD_CUSTOM : METHOD_AUTOMATIC;

            methodBoxes[ch] = pd.addChoice("Processing Method:", PROCESS_METHODS, defaultMethod);
            satFields[ch] = pd.addNumericField("Saturation:", 0.35, 2);
            satFields[ch].setEnabled(!hasCustomValue);
            String defaultVal = NONE_OPTION.equalsIgnoreCase(defaultMinMax[ch]) ? "" : defaultMinMax[ch];
            mmFields[ch] = pd.addStringField("Display Range:", defaultVal, 12);
            mmFields[ch].setEnabled(hasCustomValue);

            final int idx = ch;
            methodBoxes[ch].addItemListener(new ItemListener() {
                @Override
                public void itemStateChanged(ItemEvent e) {
                    if (e.getStateChange() != ItemEvent.SELECTED) return;
                    String sel = (String) methodBoxes[idx].getSelectedItem();
                    boolean isAuto = METHOD_AUTOMATIC.equals(sel);
                    boolean isCustom = METHOD_CUSTOM.equals(sel);
                    satFields[idx].setEnabled(isAuto);
                    mmFields[idx].setEnabled(isCustom);
                }
            });
        }

        // ── Section: Merge Options ──
        pd.addHeader("Merge Options");
        final ToggleSwitch createMergeToggle = pd.addToggle("Create merge of all channels", true);
        final ToggleSwitch saveOmeTiffToggle = pd.addToggle("Save OME-TIFF composites", false);

        pd.beginAdvancedSection("splitMerge");
        final JTextField additionalMergesField = pd.addStringField(
                "Additional merges (e.g. 1-2 3-4):", "", 15);

        // ── Section: Background Subtraction ──
        pd.addHeader("Background Subtraction");
        final ToggleSwitch subtractBgToggle = pd.addToggle("Subtract background channel",
                autoBackgroundIndex >= 0);
        final JComboBox<String> bgChannelBox = pd.addChoice("Background Channel:", bgChoices,
                bgChoices[autoBackgroundIndex >= 0 ? autoBackgroundIndex + 1 : 0]);
        bgChannelBox.setEnabled(autoBackgroundIndex >= 0);
        final JButton overrideBackgroundBtn = pd.addButton("Override");
        overrideBackgroundBtn.setEnabled(autoBackgroundIndex >= 0);

        final JLabel subtractFromLabel = pd.addMessage("Subtract from:");
        subtractFromLabel.setEnabled(autoBackgroundIndex >= 0);

        final ToggleSwitch[] subtractToggles = new ToggleSwitch[nCh];
        for (int i = 0; i < nCh; i++) {
            subtractToggles[i] = pd.addToggle(channelNames[i], i != autoBackgroundIndex);
            subtractToggles[i].setEnabled(autoBackgroundIndex >= 0);
        }
        pd.endAdvancedSection();

        overrideBackgroundBtn.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                int selected = bgChannelBox.getSelectedIndex();
                bgChannelBox.removeAllItems();
                bgChannelBox.addItem(NONE_OPTION);
                for (String channelName : channelNames) {
                    bgChannelBox.addItem(channelName);
                }
                bgChannelBox.setSelectedIndex(selected);
                overrideBackgroundBtn.setEnabled(false);
            }
        });

        subtractBgToggle.addChangeListener(new Runnable() {
            @Override
            public void run() {
                boolean on = subtractBgToggle.isSelected();
                bgChannelBox.setEnabled(on);
                overrideBackgroundBtn.setEnabled(on && autoBackgroundIndex >= 0);
                subtractFromLabel.setEnabled(on);
                if (!on) {
                    bgChannelBox.setSelectedIndex(0);
                } else if (bgChannelBox.getSelectedIndex() <= 0 && autoBackgroundIndex >= 0) {
                    bgChannelBox.setSelectedIndex(autoBackgroundIndex + 1);
                }
                for (int i = 0; i < nCh; i++) {
                    subtractToggles[i].setEnabled(on);
                }
            }
        });

        presetCombo.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                Object selected = presetCombo.getSelectedItem();
                if (selected == null || "Choose preset...".equals(String.valueOf(selected))) {
                    return;
                }
                try {
                    SplitMergePreset preset = presetIO.load(String.valueOf(selected));
                    applyPresetToDialog(preset, autoBackgroundIndex, methodBoxes, satFields, mmFields,
                            createMergeToggle, saveOmeTiffToggle, additionalMergesField,
                            subtractBgToggle, bgChannelBox, subtractToggles);
                } catch (IOException ex) {
                    IJ.log("WARNING: Could not load Split & Merge preset '" + selected + "': " + ex.getMessage());
                    JOptionPane.showMessageDialog(null,
                            "Could not load preset: " + ex.getMessage(),
                            "Split and Merge Image Channels",
                            JOptionPane.WARNING_MESSAGE);
                }
            }
        });

        savePresetBtn.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                String name = JOptionPane.showInputDialog(null, "Preset name:",
                        "Save Split & Merge Preset", JOptionPane.PLAIN_MESSAGE);
                if (name == null || name.trim().isEmpty()) {
                    return;
                }
                try {
                    presetIO.save(buildPresetFromDialog(name.trim(), methodBoxes, satFields, mmFields,
                            createMergeToggle, saveOmeTiffToggle, additionalMergesField,
                            subtractBgToggle, bgChannelBox, subtractToggles));
                    populatePresetChoice(presetCombo, presetIO);
                    presetCombo.setSelectedItem(name.trim());
                } catch (IOException ex) {
                    IJ.log("WARNING: Could not save Split & Merge preset '" + name + "': " + ex.getMessage());
                    JOptionPane.showMessageDialog(null,
                            "Could not save preset: " + ex.getMessage(),
                            "Split and Merge Image Channels",
                            JOptionPane.WARNING_MESSAGE);
                }
            }
        });

        boolean ok = pd.showDialog();
        if (!ok) return null;

        // ── Sequential retrieval ── add-order per type:
        //   toggles:       useDeconv, createMerge, saveOmeTiff, subtractBg, subtractToggles[0..nCh-1]
        //   numericFields: satFields[0..nCh-1]
        //   textFields:    mmFields[0..nCh-1], additionalMergesField
        //   combos:        methodBoxes[0..nCh-1], bgChannelBox
        final MainDialogResult result = new MainDialogResult();
        result.useDeconvolvedInput = pd.getNextBoolean();

        result.processMethodPerCh = new String[nCh];
        for (int i = 0; i < nCh; i++) {
            result.processMethodPerCh[i] = pd.getNextChoice();
        }

        result.saturationsPerCh = new double[nCh];
        for (int i = 0; i < nCh; i++) {
            result.saturationsPerCh[i] = pd.getNextNumber();
        }

        result.customMinMaxPerCh = new String[nCh];
        for (int i = 0; i < nCh; i++) {
            String mmVal = pd.getNextString().trim();
            result.customMinMaxPerCh[i] = mmVal.isEmpty() ? NONE_OPTION : mmVal;
        }

        result.createMerge = pd.getNextBoolean();
        result.saveOmeTiff = pd.getNextBoolean();
        result.additionalMergeSpec = pd.getNextString().trim();

        result.subtractBackground = pd.getNextBoolean();
        // Advance combo index past bgChannelBox; index lookup uses the live ref.
        pd.getNextChoice();
        result.backgroundIndex = -1;
        if (result.subtractBackground && bgChannelBox.getSelectedIndex() > 0) {
            result.backgroundIndex = bgChannelBox.getSelectedIndex() - 1;
        }
        if (result.backgroundIndex < 0) {
            result.subtractBackground = false;
        }

        result.subtractFromChannels = new boolean[nCh];
        for (int i = 0; i < nCh; i++) {
            result.subtractFromChannels[i] = pd.getNextBoolean();
        }
        return result;
    }

    private MainDialogResult buildHeadlessDefaults(String[] channelNames, String[] defaultMinMax,
                                                    int autoBackgroundIndex) {
        int nCh = channelNames.length;
        MainDialogResult result = new MainDialogResult();
        result.processMethodPerCh = new String[nCh];
        result.customMinMaxPerCh = new String[nCh];
        result.saturationsPerCh = new double[nCh];
        for (int i = 0; i < nCh; i++) {
            boolean hasCustomValue = !NONE_OPTION.equalsIgnoreCase(defaultMinMax[i])
                    && defaultMinMax[i] != null && !defaultMinMax[i].trim().isEmpty();
            result.processMethodPerCh[i] = hasCustomValue ? METHOD_CUSTOM : METHOD_AUTOMATIC;
            result.customMinMaxPerCh[i] = hasCustomValue ? defaultMinMax[i] : NONE_OPTION;
            result.saturationsPerCh[i] = 0.35;
        }
        result.createMerge = true;
        result.saveOmeTiff = false;
        result.additionalMergeSpec = "";
        result.useDeconvolvedInput = useDeconvolvedInput;
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
        ChannelIdentities identities = ChannelIdentitiesIO.read(new File(projectRoot, ".bin"));
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

    private static void populatePresetChoice(JComboBox<String> combo, SplitMergePresetIO presetIO) {
        Object previous = combo.getSelectedItem();
        combo.removeAllItems();
        combo.addItem("Choose preset...");
        try {
            List<SplitMergePreset> presets = presetIO.listAll();
            for (SplitMergePreset preset : presets) {
                combo.addItem(preset.getName());
            }
            if (previous != null) {
                combo.setSelectedItem(previous);
            }
        } catch (IOException e) {
            IJ.log("WARNING: Could not list Split & Merge presets: " + e.getMessage());
        }
    }

    private static void applyPresetToDialog(SplitMergePreset preset,
                                            int autoBackgroundIndex,
                                            JComboBox<String>[] methodBoxes,
                                            JTextField[] satFields,
                                            JTextField[] mmFields,
                                            ToggleSwitch createMergeToggle,
                                            ToggleSwitch saveOmeTiffToggle,
                                            JTextField additionalMergesField,
                                            ToggleSwitch subtractBgToggle,
                                            JComboBox<String> bgChannelBox,
                                            ToggleSwitch[] subtractToggles) {
        if (preset == null) {
            return;
        }
        for (int i = 0; i < methodBoxes.length; i++) {
            methodBoxes[i].setSelectedItem(preset.methodForChannel(i, (String) methodBoxes[i].getSelectedItem()));
            String mm = preset.customMinMaxForChannel(i, mmFields[i].getText());
            mmFields[i].setText(NONE_OPTION.equalsIgnoreCase(mm) ? "" : mm);
            double currentSat = parseSaturationOrDefault(satFields[i].getText(), 0.35);
            double nextSat = preset.saturationForChannel(i, currentSat);
            satFields[i].setText(formatSaturation(nextSat));
        }
        createMergeToggle.setSelected(preset.isCreateMerge());
        saveOmeTiffToggle.setSelected(preset.isSaveOmeTiff());
        additionalMergesField.setText(preset.getAdditionalMergeSpec());

        int backgroundIndex = preset.getBackgroundIndex() >= 0
                ? preset.getBackgroundIndex()
                : autoBackgroundIndex;
        boolean hasBackground = preset.isSubtractBackground()
                && backgroundIndex >= 0
                && backgroundIndex < subtractToggles.length;
        subtractBgToggle.setSelected(hasBackground);
        bgChannelBox.setSelectedIndex(hasBackground ? backgroundIndex + 1 : 0);
        for (int i = 0; i < subtractToggles.length; i++) {
            boolean fallback = hasBackground && i != backgroundIndex;
            subtractToggles[i].setSelected(i == backgroundIndex ? false : preset.subtractFromChannel(i, fallback));
        }
    }

    private static SplitMergePreset buildPresetFromDialog(String name,
                                                          JComboBox<String>[] methodBoxes,
                                                          JTextField[] satFields,
                                                          JTextField[] mmFields,
                                                          ToggleSwitch createMergeToggle,
                                                          ToggleSwitch saveOmeTiffToggle,
                                                          JTextField additionalMergesField,
                                                          ToggleSwitch subtractBgToggle,
                                                          JComboBox<String> bgChannelBox,
                                                          ToggleSwitch[] subtractToggles) {
        List<String> methods = new ArrayList<String>();
        List<String> customRanges = new ArrayList<String>();
        List<Double> saturations = new ArrayList<Double>();
        List<Boolean> subtractFrom = new ArrayList<Boolean>();
        for (int i = 0; i < methodBoxes.length; i++) {
            methods.add((String) methodBoxes[i].getSelectedItem());
            String mm = mmFields[i].getText() == null ? "" : mmFields[i].getText().trim();
            customRanges.add(mm.isEmpty() ? NONE_OPTION : mm);
            saturations.add(Double.valueOf(parseSaturationOrDefault(satFields[i].getText(), 0.35)));
            subtractFrom.add(Boolean.valueOf(subtractToggles[i].isSelected()));
        }
        int backgroundIndex = bgChannelBox.getSelectedIndex() > 0 ? bgChannelBox.getSelectedIndex() - 1 : -1;
        return new SplitMergePreset(name,
                "Saved from Split and Merge Image Channels.",
                methods,
                customRanges,
                saturations,
                createMergeToggle.isSelected(),
                saveOmeTiffToggle.isSelected(),
                additionalMergesField.getText(),
                subtractBgToggle.isSelected() && backgroundIndex >= 0,
                backgroundIndex,
                subtractFrom);
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
            NameParts parts
    ) {
        long splitStart = verboseLogging ? System.currentTimeMillis() : 0;
        ImagePlus[] chans = ChannelSplitter.split(imp);
        if (chans == null || chans.length == 0) {
            IJ.error("No channels found in " + imp.getTitle());
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
            backgroundStack = chans[backgroundIndex].duplicate();
            backgroundStack.setTitle("Background");
            if (!compactLog) IJ.log("  Background channel: " + channelNames[backgroundIndex] + " (duplicated for subtraction)");
        }

        String hemiRegion = parts.fileSuffix();

        for (int c = 0; c < n; c++) {
            String method = c < processMethodPerCh.length ? processMethodPerCh[c] : METHOD_NONE;
            String customMM = c < customMinMaxPerCh.length ? customMinMaxPerCh[c] : NONE_OPTION;
            double saturation = c < saturationsPerCh.length ? saturationsPerCh[c] : 0.35;

            if (!compactLog) {
                IJ.log("  > Channel " + (c + 1) + "/" + n + ": " + channelNames[c]);
                IJ.log("    - Color: " + channelColors[c]);
                IJ.log("    - Processing method: " + method);
            }

            ImagePlus chStack = chans[c];
            ImagePlus working = chStack;

            if (subtractBackground && backgroundStack != null) {
                boolean doSubtract = c < subtractFromChannels.length && subtractFromChannels[c];
                if (doSubtract && c != backgroundIndex) {
                    if (!compactLog) IJ.log("    - Background subtraction: subtracting " + channelNames[backgroundIndex] + " from " + channelNames[c]);
                    ImagePlus temp = chStack.duplicate();
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

            ImagePlus maxForPng = max.duplicate();
            applyPseudoColor(maxForPng, channelColors[c]);
            String safeChannel = ChannelFilenameCodec.toSafe(channelNames[c]);
            String singleSaveName = safeChannel + (hemiRegion.isEmpty() ? "" : "_" + hemiRegion) + ".png";
            AsyncImageSaver.saveAsPngAsync(maxForPng, new File(outDir, singleSaveName).getAbsolutePath());
            if (!compactLog) IJ.log("    - Saved: " + singleSaveName);
            maxForPng.changes = false;
            maxForPng.close();
            maxForPng.flush();

            if (subtractBackground && c == backgroundIndex) {
                ImagePlus rawMax = ZProjector.run(chStack, "max");
                rawMax.setTitle(channelNames[c] + "_Raw");
                applyProcessing(rawMax, method, customMM, saturation);
                ImagePlus rawPng = rawMax.duplicate();
                applyPseudoColor(rawPng, channelColors[c]);
                String rawSaveName = safeChannel + "_Raw" + (hemiRegion.isEmpty() ? "" : "_" + hemiRegion) + ".png";
                AsyncImageSaver.saveAsPngAsync(rawPng, new File(outDir, rawSaveName).getAbsolutePath());
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
                AsyncImageSaver.saveAsPngAsync(mergedRgb, new File(outDir, mergePngName).getAbsolutePath());
                if (!compactLog) IJ.log("    - Saved merge PNG: " + mergePngName);
                mergedRgb.changes = false;
                mergedRgb.close();
                mergedRgb.flush();
            }

            if (saveOmeTiff) {
                ImagePlus composite = buildCompositeHyperstack(maxProjs, channelNames);
                if (composite != null) {
                    try {
                        File omeOut = new File(tifDir, omeName);
                        OmeTiffIO.saveOmeTiff(composite, omeOut, channelNames, channelColors);
                        if (!compactLog) IJ.log("    - Saved OME-TIFF: " + omeName);
                    } catch (Exception e) {
                        IJ.log("    - WARNING: failed to write OME-TIFF: " + e.getMessage());
                    } finally {
                        composite.changes = false;
                        composite.close();
                        composite.flush();
                    }
                }
            }
        }

        if (additionalMergeSpec != null && !additionalMergeSpec.trim().isEmpty()) {
            if (!compactLog) IJ.log("  > Additional Merges: " + additionalMergeSpec);
            createAdditionalMerges(additionalMergeSpec, maxProjs, channelNames, channelColors, outDir, parts);
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
            w.write("Processed: " + new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()) + "\n");
            w.write("Hemisphere: " + (parts.hemisphere.isEmpty() ? "N/A" : parts.hemisphere) + "\n");
            w.write("Region: " + (parts.region.isEmpty() ? "N/A" : parts.region) + "\n");
            w.write("\n");

            w.write("Channels:\n");
            for (int c = 0; c < nCh; c++) {
                String method = c < processMethodPerCh.length ? processMethodPerCh[c] : "None";
                String color = c < channelColors.length ? channelColors[c] : "?";
                StringBuilder methodDesc = new StringBuilder();
                if (METHOD_AUTOMATIC.equals(method)) {
                    double sat = c < saturationsPerCh.length ? saturationsPerCh[c] : 0.35;
                    methodDesc.append("Automatic (sat=").append(sat).append(")");
                } else if (METHOD_CUSTOM.equals(method)) {
                    String mm = c < customMinMaxPerCh.length ? customMinMaxPerCh[c] : "?";
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
        } catch (IOException e) {
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
                if (headless || parallelThreads > 1) {
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
                throw new IllegalArgumentException("All channels must have same dimensions");
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

            for (int p = 0; p < w * h; p++) {
                int val;
                if (bitDepth == 8) {
                    val = ip.get(p) & 0xff;
                } else if (bitDepth == 16) {
                    val = (ip.get(p) & 0xffff) >>> 8;
                } else {
                    val = (int) Math.round(ip.getf(p));
                    if (val < 0) val = 0;
                    if (val > 255) val = 255;
                }

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
            NameParts parts
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
                subColors[i] = channelColors[idx];
            }

            ImagePlus mergedRgb = mergePseudoColorsToRgb(sub, subColors);
            if (mergedRgb != null) {
                String mergeSaveName = "Merge(" + namesTrim + ")" + (hemiRegion.isEmpty() ? "" : "_" + hemiRegion) + ".png";
                AsyncImageSaver.saveAsPngAsync(mergedRgb, new File(outDir, mergeSaveName).getAbsolutePath());
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
        } catch (IOException e) {
            IJ.log("Warning: failed to sync min-max to Channel_Data.txt: " + e.getMessage());
        }
    }

    private void saveSaturations(String directory, String[] channelNames,
                                 String[] processMethodPerCh, double[] saturationsPerCh) {
        File binDir = FlashProjectLayout.forDirectory(directory).configurationWriteDir();
        try {
            IoUtils.mustMkdirs(binDir);
        } catch (IOException e) {
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
        } catch (IOException e) {
            IJ.log("Warning: failed to save saturations: " + e.getMessage());
        }
    }

    static File splitMergeWriteRoot(String directory) {
        return FlashProjectLayout.forDirectory(directory).analysisWriteDir(AnalysisFolder.SPLIT_MERGE);
    }

    static File splitMergeImageWriteRoot(String directory) {
        return new File(splitMergeWriteRoot(directory), "Images");
    }

    static File splitMergeOmeTiffWriteRoot(String directory) {
        return new File(splitMergeWriteRoot(directory), "OME-TIFF");
    }

    static File splitMergeDetailsWriteRoot(String directory) {
        return new File(splitMergeWriteRoot(directory), "Analysis Details");
    }

    static boolean splitMergePrimaryChannelOutputExists(String directory, File primaryOutRoot,
                                                        NameParts parts, String channelName) {
        if (parts == null || channelName == null) return false;
        String suffix = parts.fileSuffix();
        String pngName = ChannelFilenameCodec.toSafe(channelName)
                + (suffix.isEmpty() ? "" : "_" + suffix) + ".png";
        List<File> roots = splitMergeImageReadRoots(directory, primaryOutRoot);
        for (int i = 0; i < roots.size(); i++) {
            File check = new File(new File(roots.get(i), parts.animal), pngName);
            if (check.exists()) return true;
        }
        return false;
    }

    private static List<File> splitMergeImageReadRoots(String directory, File primaryOutRoot) {
        List<File> roots = new ArrayList<File>();
        addUniqueRoot(roots, primaryOutRoot);
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        addUniqueRoot(roots, new File(layout.analysisWriteDir(AnalysisFolder.SPLIT_MERGE), "Images"));
        List<File> legacyDirs = layout.analysisLegacyDirs(AnalysisFolder.SPLIT_MERGE);
        for (int i = 0; i < legacyDirs.size(); i++) {
            addUniqueRoot(roots, legacyDirs.get(i));
        }
        return roots;
    }

    private static void addUniqueRoot(List<File> roots, File root) {
        if (root == null) return;
        String path = root.getAbsolutePath();
        for (int i = 0; i < roots.size(); i++) {
            if (roots.get(i).getAbsolutePath().equals(path)) return;
        }
        roots.add(root);
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
