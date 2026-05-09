package flash.pipeline.analyses;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.bin.BinField;
import flash.pipeline.bin.BinSetupDispatcher;
import flash.pipeline.bin.ChannelIdentities;
import flash.pipeline.bin.ChannelIdentitiesIO;
import flash.pipeline.analyses.wizard.ThreeDObjectPreset;
import flash.pipeline.analyses.wizard.ThreeDObjectPresetIO;
import flash.pipeline.analyses.wizard.ThreeDObjectWizard;
import flash.pipeline.analyses.wizard.SpatialAnalysisWizard;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.deconv.DeconvolvedInputResolver;
import flash.pipeline.cellpose.Cellpose3DRunner;
import flash.pipeline.image.AdaptiveParallelism;
import flash.pipeline.image.FilterExecutor;
import flash.pipeline.image.FilterMacroParser;
import flash.pipeline.image.ImageCalcOps;
import flash.pipeline.image.ImageOps;
import flash.pipeline.image.NamedFilterLoader;
import flash.pipeline.image.OrientationOps;
import flash.pipeline.image.ParallelContext;
import flash.pipeline.image.ThresholdOps;
import flash.pipeline.image.WindowManagerLock;
import flash.pipeline.intelligence.ColocalizationMetrics;
import flash.pipeline.intelligence.JunkFileFilter;
import flash.pipeline.intelligence.MetadataDiagnostics;
import flash.pipeline.io.AsyncImageSaver;
import flash.pipeline.io.BoundedImageLoader;
import flash.pipeline.io.CalibrationIO;
import flash.pipeline.io.CsvTableIO;
import flash.pipeline.io.DeferredImageSupplier;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.io.TifCache;
import flash.pipeline.naming.ChannelFilenameCodec;
import flash.pipeline.naming.ImageOrientationResolver;
import flash.pipeline.naming.ImageNameParser;
import flash.pipeline.naming.NameParts;
import flash.pipeline.naming.ResolvedImageMetadata;
import flash.pipeline.objects.ObjectsCounter3DWrapper;
import flash.pipeline.objects.ObjectsCounterOptions;
import flash.pipeline.results.ObjectAnalysisDetailsWriter;
import flash.pipeline.results.ObjectCsvColumnOrder;
import flash.pipeline.stardist.StarDist3DRunner;
import flash.pipeline.results.ResultsTableCleaner;
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.FeatureDependencyGate;
import flash.pipeline.runtime.PluginInstallGuard;
import flash.pipeline.roi.RoiIO;
import flash.pipeline.roi.RoiOps;
import flash.pipeline.zslice.ZSliceOps;

import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.wizard.SetupHelperButton;

import flash.pipeline.objects.CpcUtils;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.plugin.ChannelSplitter;
import ij.io.Opener;

import ij.text.TextWindow;

import java.awt.Frame;
import java.awt.Dimension;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import flash.pipeline.ui.ToggleSwitch;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Partial migration of objectAnalysis().
 *
 * Requires the external plugins used by the macro:
 * - 3D Objects Counter
 * - 3D MultiColoc
 *
 * Reads channel config from the active FLASH configuration folder.
 * Applies per-channel filter macro if present in that configuration folder.
 */
public class ThreeDObjectAnalysis implements Analysis {

    interface SpatialSetupLauncher {
        SpatialAnalysisWizard.DerivedConfig launch(String directory,
                                                   ChannelIdentities identities,
                                                   boolean thresholdsConfiguredUpstream);
    }

    private static final SpatialSetupLauncher DEFAULT_SPATIAL_SETUP_LAUNCHER =
            new SpatialSetupLauncher() {
                @Override
                public SpatialAnalysisWizard.DerivedConfig launch(String directory,
                                                                  ChannelIdentities identities,
                                                                  boolean thresholdsConfiguredUpstream) {
                    try {
                        IJ.log("Spatial Analysis setup requested by 3D Object Analysis.");
                        SpatialAnalysisWizard spatialWizard = new SpatialAnalysisWizard(
                                flash.pipeline.ui.wizard.WizardFlow.MainPanelBinding.NULL,
                                identities,
                                firstSeriesInfoOrNull(directory),
                                calibrationIsAvailable(directory),
                                thresholdsConfiguredUpstream,
                                false);
                        spatialWizard.run();
                        if (!spatialWizard.wasFinished()) {
                            return null;
                        }
                        return spatialWizard.deriveCurrentConfig();
                    } catch (Exception e) {
                        IJ.handleException(e);
                        return null;
                    }
                }
            };

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
    private boolean classicalCentroidFilter = true;
    private boolean doVolumetric = true;
    private boolean doCpc = true;
    private Map<String, Double> markerThresholds = new LinkedHashMap<String, Double>();
    private boolean wizardExtractProcessLength = false;
    private boolean wizardRunSpatial = false;
    private int wizardNuclearMarkerIndex = -1;
    private boolean[] wizardProcessChannels = null;
    private SpatialAnalysisWizard.DerivedConfig wizardSpatialConfig = null;
    private SpatialSetupLauncher spatialSetupLauncher = DEFAULT_SPATIAL_SETUP_LAUNCHER;
    private static final String OBJECT_PRESET_PLACEHOLDER = "(choose preset)";
    private final AtomicBoolean calibrationWritten = new AtomicBoolean(false);
    private boolean useDeconvolvedInput = true;
    private CLIConfig cliConfig = null;

    /** Shared lock for all legacy ImageJ1/plugin paths that touch WindowManager or Prefs. */
    private static final ReentrantLock COUNTER3D_LOCK = WindowManagerLock.LOCK;

    /** Lazy runtime check: is mcib3d-core available for native (thread-safe) object counting? */
    private static volatile Boolean mcib3dAvailable = null;

    /** Heap floor below which the count-once-assign-per-ROI optimisation is disabled (8 GiB). */
    private static final long COUNT_ONCE_HEAP_FLOOR = 8L * 1024 * 1024 * 1024;

    /**
     * Returns true if the count-once-assign-per-ROI optimisation has enough
     * heap headroom to run on this image without OOMing. The optimisation
     * keeps unfiltered+filtered+preDetection+labelMap clones per channel
     * across all ROI iterations, so a 2 GB stack on an 8 GB heap can hold
     * ~28 full-stack copies before GC pressure causes pauses or OOM.
     *
     * Gate: {@code heap >= 8 GiB} AND {@code stackBytes <= heap/16}.
     */
    private static boolean canRunCountOnce(ImagePlus imp) {
        if (imp == null) return false;
        long maxMem = Runtime.getRuntime().maxMemory();
        long stackBytes = estimateStackBytes(imp);
        if (maxMem < COUNT_ONCE_HEAP_FLOOR) {
            IJ.log("  [3D] count-once disabled: heap " + (maxMem / (1024 * 1024))
                    + " MB < 8 GB floor — falling back to per-ROI counting.");
            return false;
        }
        if (stackBytes > maxMem / 16) {
            IJ.log("  [3D] count-once disabled: stack " + (stackBytes / (1024 * 1024))
                    + " MB > heap/16 (" + (maxMem / 16 / (1024 * 1024))
                    + " MB) — falling back to per-ROI counting.");
            return false;
        }
        return true;
    }

    /** Approximate full multi-channel stack byte size for a multi-dim ImagePlus. */
    private static long estimateStackBytes(ImagePlus imp) {
        if (imp == null) return 0;
        long w = imp.getWidth();
        long h = imp.getHeight();
        long c = Math.max(1, imp.getNChannels());
        long z = Math.max(1, imp.getNSlices());
        long t = Math.max(1, imp.getNFrames());
        long bytesPerPixel;
        switch (imp.getBitDepth()) {
            case 8:  bytesPerPixel = 1; break;
            case 16: bytesPerPixel = 2; break;
            case 32: bytesPerPixel = 4; break;
            case 24: bytesPerPixel = 4; break; // RGB stored as int
            default: bytesPerPixel = 4; break;
        }
        return w * h * c * z * t * bytesPerPixel;
    }

    /** In-memory image registry, replaces WindowManager lookups when headless (sequential mode). */
    private final Map<String, ImagePlus> imageRegistry = new LinkedHashMap<String, ImagePlus>();

    /** Thread-local image registry for parallel execution. When set, overrides the instance field. */
    private final ThreadLocal<Map<String, ImagePlus>> threadLocalRegistry = new ThreadLocal<Map<String, ImagePlus>>();

    /** One named ROI zip loaded into memory as uncropped/cropped ROI pairs. */
    private static final class RoiSetData {
        final String name;
        final ij.gui.Roi[] rois;

        RoiSetData(String name, ij.gui.Roi[] rois) {
            this.name = name == null ? "" : name;
            this.rois = rois == null ? new ij.gui.Roi[0] : rois;
        }

        ij.gui.Roi cloneRoi(int index) {
            if (index < 0 || index >= rois.length || rois[index] == null) return null;
            return (ij.gui.Roi) rois[index].clone();
        }
    }

    /** Returns the active registry: thread-local if set, otherwise the instance-level one. */
    private Map<String, ImagePlus> activeRegistry() {
        Map<String, ImagePlus> local = threadLocalRegistry.get();
        return local != null ? local : imageRegistry;
    }

    /** True when running inside a parallel worker with a thread-local image registry. */
    private boolean isParallelRegistryContext() {
        return threadLocalRegistry.get() != null;
    }

    @Override
    public Set<BinField> requiredBinFields() {
        return EnumSet.of(
                BinField.CHANNEL_NAMES,
                BinField.CHANNEL_COLORS,
                BinField.OBJECT_THRESHOLDS,
                BinField.PARTICLE_SIZES,
                BinField.SEGMENTATION_METHODS,
                BinField.FILTER_PRESETS,
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
            this.useDeconvolvedInput = config.isThreeDUseDeconv();
        }
    }

    void setSpatialSetupLauncherForTest(SpatialSetupLauncher launcher) {
        this.spatialSetupLauncher = launcher == null
                ? DEFAULT_SPATIAL_SETUP_LAUNCHER
                : launcher;
    }

    private static boolean gateStarDistFeature(String featureDisplayName) {
        return FeatureDependencyGate.gate(DependencyId.STARDIST_RUNTIME,
                "3D Object Analysis", featureDisplayName)
                && FeatureDependencyGate.gate(DependencyId.TENSORFLOW_NATIVE_RUNTIME,
                "3D Object Analysis", featureDisplayName);
    }

    private static boolean gateCellposeFeature(String featureDisplayName) {
        return FeatureDependencyGate.gate(DependencyId.CELLPOSE_RUNTIME,
                "3D Object Analysis", featureDisplayName);
    }

    private static boolean isMcib3dAvailable() {
        Boolean cached = mcib3dAvailable;
        if (cached != null) {
            return cached.booleanValue();
        }
        synchronized (ThreeDObjectAnalysis.class) {
            if (mcib3dAvailable == null) {
                mcib3dAvailable = Boolean.valueOf(ObjectsCounter3DWrapper.isMcib3dAvailable());
            }
            return mcib3dAvailable.booleanValue();
        }
    }

    private static boolean usesStarDistSegmentation(BinConfig cfg) {
        if (cfg == null) return false;
        for (int i = 0; i < cfg.numChannels(); i++) {
            if (cfg.isStarDist(i)) return true;
        }
        return false;
    }

    private static boolean usesCellposeSegmentation(BinConfig cfg) {
        if (cfg == null) return false;
        for (int i = 0; i < cfg.numChannels(); i++) {
            if (cfg.isCellpose(i)) return true;
        }
        return false;
    }

    private static boolean usesClassicalSegmentation(BinConfig cfg) {
        if (cfg == null) return false;
        for (int i = 0; i < cfg.numChannels(); i++) {
            if (!cfg.usesLabelImageSegmentation(i)) return true;
        }
        return false;
    }

    private static boolean requiresMcib3dMorphometry(BinConfig cfg) {
        if (cfg == null) return false;
        for (int i = 0; i < cfg.numChannels(); i++) {
            if (cfg.usesLabelImageSegmentation(i)) return true;
        }
        return false;
    }

    private ImagePlus prepareCellposeSecondChannelInput(int primaryChannelIndex,
                                                        String primaryChannelName,
                                                        ImagePlus primaryChannel,
                                                        File binDir,
                                                        int companionChannelIndex,
                                                        ImagePlus companionChannel,
                                                        String companionChannelName,
                                                        String companionFilterFilename) {
        if (companionChannelIndex < 0) return null;

        String chTag = " [Ch " + (primaryChannelIndex + 1) + "]";
        if (companionChannelIndex == primaryChannelIndex) {
            IJ.log("WARNING:" + chTag + " Cellpose companion channel cannot be the same as the primary channel. Falling back to single-channel input.");
            return null;
        }
        if (companionChannel == null) {
            IJ.log("WARNING:" + chTag + " Cellpose companion channel C" + (companionChannelIndex + 1)
                    + " is unavailable. Falling back to single-channel input.");
            return null;
        }
        if (primaryChannel == null
                || primaryChannel.getWidth() != companionChannel.getWidth()
                || primaryChannel.getHeight() != companionChannel.getHeight()
                || primaryChannel.getStackSize() != companionChannel.getStackSize()) {
            IJ.log("WARNING:" + chTag + " Cellpose companion channel C" + (companionChannelIndex + 1)
                    + " does not match the primary channel dimensions. Falling back to single-channel input.");
            return null;
        }

        ImagePlus filteredCompanion = ImageOps.duplicateThreadSafe(companionChannel);
        filteredCompanion.setTitle((companionChannelName == null ? "Companion" : companionChannelName) + "_cellpose_companion_input");

        File filterMacro = companionFilterFilename == null ? null : new File(binDir, companionFilterFilename);
        if (filterMacro != null && filterMacro.exists()) {
            try {
                FilterExecutor.runIjmFileThreadSafe(filteredCompanion, filterMacro);
                if (!compactLog) {
                    IJ.log("    -" + chTag + " Cellpose companion filter applied from Ch "
                            + (companionChannelIndex + 1) + ": " + companionFilterFilename);
                }
            } catch (Exception e) {
                IJ.log("    -" + chTag + " Cellpose companion filter error from Ch "
                        + (companionChannelIndex + 1) + ": " + e.getMessage());
            }
        } else {
            String defaultMacro = NamedFilterLoader.loadDefaultFilter();
            if (defaultMacro != null) {
                FilterExecutor.runThreadSafe(filteredCompanion, defaultMacro);
                if (!compactLog) {
                    IJ.log("    -" + chTag + " Default filter applied to Cellpose companion from Ch "
                            + (companionChannelIndex + 1) + " (no " + companionFilterFilename + " found)");
                }
            }
        }

        if (!compactLog) {
            IJ.log("    -" + chTag + " Cellpose companion channel: C" + (companionChannelIndex + 1)
                    + " (" + companionChannelName + ")");
        }
        return filteredCompanion;
    }

    private static ImagePlus[] snapshotCellposeCompanionSources(ImagePlus[] channels,
                                                                int[] companionIndexes,
                                                                String[] channelNames) {
        if (channels == null) return new ImagePlus[0];
        ImagePlus[] snapshots = new ImagePlus[channels.length];
        if (companionIndexes == null) return snapshots;
        for (int companionIndex : companionIndexes) {
            if (companionIndex < 0 || companionIndex >= channels.length) continue;
            if (snapshots[companionIndex] != null || channels[companionIndex] == null) continue;
            snapshots[companionIndex] = ImageOps.duplicateThreadSafe(channels[companionIndex]);
            if (channelNames != null && companionIndex < channelNames.length) {
                snapshots[companionIndex].setTitle(channelNames[companionIndex] + "_cellpose_companion_source");
            }
        }
        return snapshots;
    }

    private static void closeQuietly(ImagePlus[] images) {
        if (images == null) return;
        for (ImagePlus image : images) {
            closeQuietly(image);
        }
    }

    /**
     * Store image in the registry.
     * In parallel mode, callers must explicitly opt into WindowManager visibility
     * via ensureInWindowManager() while holding the global ImageJ lock.
     */
    private void registerImage(String title, ImagePlus imp, boolean needsWindowManager) {
        if (imp == null) return;
        imp.setTitle(title);
        activeRegistry().put(title, imp);
        if (isParallelRegistryContext()) return;
        if (!headless) {
            if (imp.getWindow() == null) imp.show();
        } else if (needsWindowManager) {
            if (imp.getWindow() == null) imp.show();
            if (imp.getWindow() != null) imp.getWindow().setVisible(false);
        }
    }

    /** Retrieve image from registry (no WindowManager dependency). */
    private ImagePlus getRegisteredImage(String title) {
        return activeRegistry().get(title);
    }

    /** Ensure an image is visible in WindowManager (for external plugins). */
    private void ensureInWindowManager(ImagePlus imp) {
        if (imp == null) return;
        if (imp.getWindow() == null) imp.show();
        if ((headless || isParallelRegistryContext()) && imp.getWindow() != null) {
            imp.getWindow().setVisible(false);
        }
    }

    /** Remove image from registry and close it. */
    private void unregisterAndClose(String title) {
        ImagePlus imp = activeRegistry().remove(title);
        if (imp != null) {
            imp.changes = false;
            imp.close();
            imp.flush();
        }
    }

    /** Hide all currently open image windows (for suppressing Counter3D GUI side effects). */
    private static void hideAllImageWindows() {
        if (!COUNTER3D_LOCK.isHeldByCurrentThread()) {
            throw new IllegalStateException(
                "hideAllImageWindows() must be called under COUNTER3D_LOCK");
        }
        int[] ids = WindowManager.getIDList();
        if (ids == null) return;
        for (int id : ids) {
            ImagePlus imp = WindowManager.getImage(id);
            if (imp != null && imp.getWindow() != null) {
                imp.getWindow().setVisible(false);
            }
        }
        // Also hide non-image windows (Results tables etc.) except Log
        Frame[] frames = WindowManager.getNonImageWindows();
        if (frames != null) {
            for (Frame f : frames) {
                if (f != null && !"Log".equals(f.getTitle())) {
                    f.setVisible(false);
                }
            }
        }
    }

    /** Close all images in the active registry. */
    private void clearRegistry() {
        Map<String, ImagePlus> reg = activeRegistry();
        for (ImagePlus imp : reg.values()) {
            if (imp != null) {
                imp.changes = false;
                imp.close();
                imp.flush();
            }
        }
        reg.clear();
    }

    @Override
    public void execute(String directory) {
        if (!FeatureDependencyGate.gate(DependencyId.BIO_FORMATS_RUNTIME,
                "3D Object Analysis", "Bio-Formats image loading")) {
            return;
        }

        BinSetupDispatcher.Outcome outcome = BinSetupDispatcher.ensure(
                directory, "3D Object Analysis", requiredBinFields(),
                benefitsFromRois(), suppressDialogs, cliConfig);
        if (outcome == BinSetupDispatcher.Outcome.CANCELLED) {
            IJ.log("[FLASH] 3D Object Analysis cancelled by user.");
            return;
        }

        BinConfig cfg = BinConfigIO.readPartialFromDirectory(directory);
        if (usesClassicalSegmentation(cfg)
                && !FeatureDependencyGate.gate(DependencyId.OBJECTS_COUNTER_3D,
                "3D Object Analysis", "classical 3D object counting")) {
            return;
        }
        if (usesStarDistSegmentation(cfg) && !gateStarDistFeature("StarDist 3D segmentation")) {
            return;
        }
        if (usesCellposeSegmentation(cfg) && !gateCellposeFeature("Cellpose segmentation")) {
            return;
        }
        if (requiresMcib3dMorphometry(cfg)
                && !FeatureDependencyGate.gate(DependencyId.MCIB3D_CORE,
                "3D Object Analysis",
                "mcib3d-backed 3D morphometry / shape analysis")) {
            return;
        }
        IJ.log("Channels: " + String.join(", ", cfg.channelNames));

        // --- Scan for existing ROI sets ---
        List<File> roiZips;
        try {
            roiZips = RoiIO.listRoiZipFiles(new File(directory));
        } catch (NoClassDefFoundError e) {
            if (PluginInstallGuard.reportMissingInternalClass("3D Object Analysis", e)) return;
            throw e;
        }
        String[] roiSetNames = new String[roiZips.size()];
        for (int r = 0; r < roiZips.size(); r++) {
            String roiSetName = roiZips.get(r).getName()
                    .replace(" ROIs.zip", "")
                    .replace("ROIs.zip", "")
                    .replace(".zip", "")
                    .trim();
            roiSetNames[r] = roiSetName;
        }
        IJ.log("ROI sets found: " + roiZips.size());
        for (String roiSetName : roiSetNames) {
            IJ.log("  - " + roiSetName);
        }
        if (roiZips.isEmpty()) {
            IJ.error("3D Object Analysis", "No ROIs loaded. Run 'Draw and Save ROIs' first (must create 2 ROIs per image).");
            return;
        }

        ChannelIdentities channelIdentities = ChannelIdentitiesIO.read(new File(directory, ".bin"));
        applyCliObjectConfiguration(directory, cfg, channelIdentities);

        // --- Options dialogs with Back navigation ---
        boolean extractProcessLength = wizardExtractProcessLength;
        boolean runSpatial = wizardRunSpatial;
        if (markerThresholds == null) {
            markerThresholds = new LinkedHashMap<String, Double>();
        }
        int nuclearMarkerIndex = wizardNuclearMarkerIndex;
        boolean[] processChannels = wizardProcessChannels == null ? null : Arrays.copyOf(wizardProcessChannels, wizardProcessChannels.length);
        boolean[] selectedRoiSets = new boolean[roiSetNames.length];
        Arrays.fill(selectedRoiSets, true);

        int dialogStep = 0;
        if (suppressDialogs) {
            if (extractProcessLength && processChannels == null) {
                ThreeDObjectWizard.DerivedConfig derived = ThreeDObjectWizard.deriveConfig(
                        cfg, channelIdentities, Collections.<String, Object>emptyMap(), Arrays.asList(roiSetNames));
                nuclearMarkerIndex = derived.nuclearMarkerIndex;
                processChannels = Arrays.copyOf(derived.processChannels, derived.processChannels.length);
            }
        }
        while (!suppressDialogs && dialogStep >= 0) {
            if (dialogStep == 0) {
                PipelineDialog gdOpts = new PipelineDialog("3D Object Analysis Options", PipelineDialog.Phase.ANALYSE);
                gdOpts.addAnalysisHelpHeader("3D Object Analysis", FLASH_Pipeline.IDX_3D_OBJECT);
                final ThreeDObjectDialogBindings objectBindings = new ThreeDObjectDialogBindings();
                addThreeDObjectSetupControls(gdOpts, directory, cfg, channelIdentities,
                        Arrays.asList(roiSetNames), objectBindings,
                        new ThreeDObjectConfigApplier() {
                            @Override
                            public void apply(String selectedPresetName,
                                              ThreeDObjectWizard.DerivedConfig derived) {
                                applyThreeDObjectConfigToDialog(cfg, derived,
                                        objectBindings, selectedPresetName);
                            }
                        });
                gdOpts.addSubHeader("Input");
                objectBindings.useDeconvolvedInputToggle =
                        gdOpts.addToggle("Use deconvolved stacks if available", useDeconvolvedInput);

                gdOpts.addHeader("Colocalization Method");
                objectBindings.doVolumetricToggle =
                        gdOpts.addToggle("Volumetric overlap (%)", doVolumetric);
                gdOpts.addHelpText("Percentage of object voxels overlapping with partner channel.");
                objectBindings.doCpcToggle =
                        gdOpts.addToggle("Centroid coincidence (CPC)", doCpc);
                gdOpts.addHelpText("Whether each object's centroid falls inside a partner object.");

                gdOpts.addHeader("Colocalisation Thresholds");
                for (String chName : cfg.channelNames) {
                    Double prev = markerThresholds.get(chName);
                    objectBindings.thresholdFields.add(gdOpts.addNumericField(
                            chName + " Coloc Threshold (%)",
                            prev != null ? prev : 30.0, 0));
                }

                gdOpts.addHeader("ROI Sets");
                gdOpts.addHelpText("Select which ROI sets will be used for this analysis run.");
                for (int r = 0; r < roiSetNames.length; r++) {
                    gdOpts.addToggle(roiSetNames[r], selectedRoiSets[r]);
                }

                gdOpts.beginAdvancedSection("threeDObject");
                objectBindings.extractProcessLengthToggle =
                        gdOpts.addToggle("Extract Process Length", extractProcessLength);
                gdOpts.addHelpText("Skeletonizes process channels, subtracts the nuclear marker, "
                        + "and measures skeleton length via 3D Objects Counter. "
                        + "When enabled, a follow-up Process Analysis dialog selects the nuclear "
                        + "marker channel and which channels contain processes.");

                objectBindings.runSpatialToggle =
                        gdOpts.addToggle("Run Spatial Distance Analysis", runSpatial);
                gdOpts.addHelpText("Computes inter-marker nearest neighbor distances after "
                        + "3D object counting.");

                objectBindings.classicalCentroidFilterToggle =
                        gdOpts.addToggle("Use Centroid ROI Filtering (Classical)", classicalCentroidFilter);
                gdOpts.addHelpText("Classical channels only. When ON, objects are counted on the "
                        + "full uncropped image and then filtered by centroid position "
                        + "inside the ROI (like StarDist). When OFF, the image is cropped "
                        + "to the ROI before counting.");

                gdOpts.endAdvancedSection();

                if (!gdOpts.showDialog()) {
                    return; // Cancel
                }
                useDeconvolvedInput = gdOpts.getNextBoolean();
                doVolumetric = gdOpts.getNextBoolean();
                doCpc = gdOpts.getNextBoolean();
                markerThresholds.clear();
                for (String chName : cfg.channelNames) {
                    markerThresholds.put(chName, gdOpts.getNextNumber());
                }
                for (int r = 0; r < roiSetNames.length; r++) {
                    selectedRoiSets[r] = gdOpts.getNextBoolean();
                }
                extractProcessLength = gdOpts.getNextBoolean();
                runSpatial = gdOpts.getNextBoolean();
                classicalCentroidFilter = gdOpts.getNextBoolean();
                wizardExtractProcessLength = extractProcessLength;
                wizardRunSpatial = runSpatial;
                nuclearMarkerIndex = wizardNuclearMarkerIndex;
                processChannels = wizardProcessChannels == null ? null : Arrays.copyOf(wizardProcessChannels, wizardProcessChannels.length);

                if (!hasSelectedRoiSets(selectedRoiSets)) {
                    IJ.error("3D Object Analysis", "Select at least one ROI set to analyse.");
                    continue;
                }

                if (extractProcessLength) {
                    dialogStep = 1;
                } else {
                    break;
                }
            } else if (dialogStep == 1) {
                String[] names = cfg.channelNames.toArray(new String[0]);
                if (processChannels == null) {
                    ThreeDObjectWizard.DerivedConfig derived = ThreeDObjectWizard.deriveConfig(
                            cfg, channelIdentities, Collections.<String, Object>emptyMap(), Arrays.asList(roiSetNames));
                    nuclearMarkerIndex = derived.nuclearMarkerIndex;
                    processChannels = Arrays.copyOf(derived.processChannels, derived.processChannels.length);
                }

                PipelineDialog gdPA = new PipelineDialog("Process Analysis", PipelineDialog.Phase.ANALYSE);
                gdPA.enableBackButton();
                gdPA.addAnalysisHelpHeader("3D Object Analysis", FLASH_Pipeline.IDX_3D_OBJECT);
                gdPA.addSubHeader("Nuclear Marker");
                String defaultNuclear = nuclearMarkerIndex >= 0 && nuclearMarkerIndex < names.length
                        ? names[nuclearMarkerIndex] : names[0];
                gdPA.addChoice("Nuclear Marker Channel", names, defaultNuclear);

                gdPA.addHeader("Extract Process Length For");
                gdPA.addHelpText("Select which channels contain processes to measure.");
                for (int pc = 0; pc < names.length; pc++) {
                    gdPA.addToggle(names[pc], processChannels != null && processChannels[pc]);
                }

                if (!gdPA.showDialog()) {
                    if (gdPA.wasBackPressed()) {
                        String nuclearMarkerName = gdPA.getNextChoice();
                        nuclearMarkerIndex = cfg.channelNames.indexOf(nuclearMarkerName);
                        processChannels = new boolean[names.length];
                        for (int pc = 0; pc < names.length; pc++) {
                            processChannels[pc] = gdPA.getNextBoolean();
                        }
                        wizardNuclearMarkerIndex = nuclearMarkerIndex;
                        wizardProcessChannels = Arrays.copyOf(processChannels, processChannels.length);
                        dialogStep = 0; // go back
                        continue;
                    }
                    return; // Cancel
                }

                String nuclearMarkerName = gdPA.getNextChoice();
                nuclearMarkerIndex = cfg.channelNames.indexOf(nuclearMarkerName);
                processChannels = new boolean[names.length];
                for (int pc = 0; pc < names.length; pc++) {
                    processChannels[pc] = gdPA.getNextBoolean();
                }
                wizardNuclearMarkerIndex = nuclearMarkerIndex;
                wizardProcessChannels = Arrays.copyOf(processChannels, processChannels.length);
                break;
            }
        }

        if (!prepareSpatialHandoffBeforeAnalysis(directory, channelIdentities, runSpatial)) {
            return;
        }

        // Preload all ROIs directly from zip files — no RoiManager needed
        IJ.log("ROI set selections:");
        List<RoiSetData> selectedRoiSetData = new ArrayList<RoiSetData>();
        for (int r = 0; r < roiZips.size(); r++) {
            IJ.log("  ROI set '" + roiSetNames[r] + "': " + (selectedRoiSets[r] ? "selected" : "skipped"));
            if (!selectedRoiSets[r]) continue;
            List<ij.gui.Roi> rois;
            try {
                rois = RoiIO.loadRoisFromZip(roiZips.get(r));
            } catch (NoClassDefFoundError e) {
                if (PluginInstallGuard.reportMissingInternalClass("3D Object Analysis", e)) return;
                throw e;
            }
            selectedRoiSetData.add(new RoiSetData(roiSetNames[r], rois.toArray(new ij.gui.Roi[0])));
        }
        RoiSetData[] roiSets = selectedRoiSetData.toArray(new RoiSetData[0]);

        if (roiSets.length == 0) {
            IJ.error("3D Object Analysis", "No ROIs loaded. Run 'Draw and Save ROIs' first (must create 2 ROIs per image).");
            return;
        }

        IJ.log("ROI sets selected for analysis: " + roiSets.length);
        for (RoiSetData roiSet : roiSets) {
            IJ.log("  - " + roiSet.name);
        }

        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        File outDir = objectCsvWriteDir(directory);
        //noinspection ResultOfMethodCallIgnored
        outDir.mkdirs();

        File objectAnalysisDetailsDir = ObjectAnalysisDetailsWriter.analysisDetailsWriteDir(new File(directory));
        //noinspection ResultOfMethodCallIgnored
        objectAnalysisDetailsDir.mkdirs();

        File binDir = activeConfigurationDir(directory);

        File imageAnalysisRoot = layout.objectImageOutputsWriteDir();
        //noinspection ResultOfMethodCallIgnored
        imageAnalysisRoot.mkdirs();

        // Per-channel accumulator tables (macro ultimately writes one CSV per channel)
        Map<String, ij.measure.ResultsTable> channelTables = new LinkedHashMap<>();
        for (String chName : cfg.channelNames) {
            channelTables.put(chName, new ij.measure.ResultsTable());
        }

        final long analysisStartTime = System.currentTimeMillis();

        // Record parameters in QC report
        if (qualityReport != null && qualityReport.isEnabled()) {
            String[] chNames = cfg.channelNames.toArray(new String[0]);
            String[] channelSummaries = new String[chNames.length];
            for (int c = 0; c < chNames.length; c++) {
                channelSummaries[c] = build3DObjectChannelSummary(cfg, c);
            }
            qualityReport.add3DObjectParams(chNames, channelSummaries,
                    extractProcessLength, runSpatial, markerThresholds,
                    cfg.getZSliceConfig().summary());
        }

        // ── Skip-existing pre-scan: check output files BEFORE loading any pixels ──
        if (skipExisting && allOutputCsvsExist(outDir, cfg.channelNames)) {
            IJ.log("All output CSVs already exist — skipping entire 3D Object Analysis.");
            IJ.showProgress(1.0);
            IJ.showStatus("");
            return;
        }

        DeferredImageSupplier supplier;
        int totalImages;
        try {
            supplier = ImageSourceDispatcher.createSupplier(directory);
            totalImages = supplier.getTotalSeries();
            supplier = wrapInputSupplier(directory, supplier);
        } catch (Exception e) {
            IJ.log("3D Object Analysis: " + e.getMessage());
            if (!headless && !suppressDialogs) {
                IJ.showMessage("3D Object Analysis", e.getMessage());
            }
            IJ.showProgress(1.0);
            IJ.showStatus("");
            return;
        }

        if (!validateRoiSets(roiSets, totalImages)) {
            return;
        }

        if (parallelThreads > 1) {
            // ── Parallel processing: bounded producer-consumer queue ──
            List<Integer> indicesToProcess = new ArrayList<Integer>();
            for (int i = 0; i < totalImages; i++) {
                indicesToProcess.add(i);
            }
            // WindowManager-dependent work is serialized separately, so StarDist no longer
            // forces the whole analysis down to a single worker.
            int effectiveLoaders = 1;
            int effectiveWorkers = Math.max(1, parallelThreads - effectiveLoaders);
            int bufferSize = Math.min(4, Math.max(2, effectiveWorkers));
            int safeWorkers = AdaptiveParallelism.computeAndLog(supplier, effectiveWorkers, bufferSize);
            BoundedImageLoader loader = new BoundedImageLoader(supplier, indicesToProcess, bufferSize,
                    effectiveLoaders, useTifCache && !useDeconvolvedInput, directory);
            try {
                List<SeriesMeta> metas = ImageSourceDispatcher.readAllMetadata(directory);
                List<String> names = new ArrayList<String>();
                for (SeriesMeta m : metas) names.add(m.name);
                loader.setSeriesNames(names);
            } catch (Exception ignore) { }
            loader.start();
            IJ.log("Thread split: " + effectiveLoaders + " loaders, " + safeWorkers + " workers");
            compactLog = true;
            processImagesParallel(loader, safeWorkers, directory, cfg, outDir, imageAnalysisRoot, channelTables,
                    roiSets, extractProcessLength, nuclearMarkerIndex, processChannels,
                    analysisStartTime);
            compactLog = false;
        } else {
            compactLog = false;
            // ── Sequential processing: deferred loading with prefetch ──
            processImagesSequential(supplier, totalImages, directory, cfg, outDir, imageAnalysisRoot, channelTables,
                    roiSets, extractProcessLength, nuclearMarkerIndex, processChannels,
                    analysisStartTime);
        }

        IJ.showProgress(1.0);
        IJ.showStatus("");

        // Ensure all coloc columns exist on each channel table
        if (doVolumetric) ensureAllColocColumns(cfg, channelTables);
        if (doCpc) ensureAllCpcColocColumns(cfg, channelTables);

        for (Map.Entry<String, ij.measure.ResultsTable> e : channelTables.entrySet()) {
            try {
                String channelName = e.getKey();
                List<String> keep = buildOrderedObjectColumns(channelName, e.getValue(), cfg,
                        extractProcessLength, processChannels);
                ResultsTableCleaner.keepOnlyColumns(e.getValue(), keep.toArray(new String[0]));

                File out = objectOutputCsv(outDir, channelName);
                CsvTableIO.writeResultsTableCsv(out, e.getValue(), keep);

                // Write macro-style Analysis Details per channel
                int cIndex = cfg.channelNames.indexOf(channelName);
                if (cIndex >= 0) {
                    if (cfg.isStarDist(cIndex)) {
                        ObjectAnalysisDetailsWriter.writeStarDistPerChannel(
                                objectAnalysisDetailsDir,
                                binDir,
                                channelName,
                                cIndex + 1,
                                cfg.getStarDistProbThresh(cIndex),
                                cfg.getStarDistNmsThresh(cIndex),
                                cfg.getStarDistLinkingMaxDistance(cIndex),
                                cfg.getStarDistGapClosingMaxDistance(cIndex),
                                cfg.getStarDistMaxFrameGap(cIndex),
                                cfg.getStarDistAreaMin(cIndex),
                                cfg.getStarDistAreaMax(cIndex),
                                cfg.getStarDistQualityMin(cIndex),
                                cfg.getStarDistIntensityMin(cIndex),
                                cfg.channelNames.toArray(new String[0]),
                                runSpatial,
                                markerThresholds
                        );
                    } else if (cfg.isCellpose(cIndex)) {
                        int cellposeCompanionIndex = cfg.getCellposeSecondChannel(cIndex);
                        String companionChannelName = cellposeCompanionIndex >= 0
                                && cellposeCompanionIndex < cfg.channelNames.size()
                                ? cfg.channelNames.get(cellposeCompanionIndex)
                                : null;
                        ObjectAnalysisDetailsWriter.writeCellposePerChannel(
                                objectAnalysisDetailsDir,
                                binDir,
                                channelName,
                                cIndex + 1,
                                cfg.getCellposeModel(cIndex),
                                cfg.getCellposeDiameter(cIndex),
                                cfg.getCellposeFlowThreshold(cIndex),
                                cfg.getCellposeCellprobThreshold(cIndex),
                                cfg.getCellposeUseGpu(cIndex),
                                companionChannelName,
                                cfg.channelNames.toArray(new String[0]),
                                runSpatial,
                                markerThresholds
                        );
                    } else {
                        String thrTok = cIndex < cfg.channelThresholds.size() ? cfg.channelThresholds.get(cIndex) : "";
                        String sizeTok = cIndex < cfg.channelSizes.size() ? cfg.channelSizes.get(cIndex) : "";
                        ObjectAnalysisDetailsWriter.writePerChannel(
                                objectAnalysisDetailsDir,
                                binDir,
                                channelName,
                                cIndex + 1,
                                thrTok,
                                sizeTok,
                                cfg.channelNames.toArray(new String[0]),
                                runSpatial,
                                markerThresholds
                        );
                    }
                }
            } catch (Exception ex) {
                IJ.log("Warning: failed saving " + e.getKey() + ": " + ex.getMessage());
            }
        }

        // Close all remaining image windows, leaving only the Log visible
        closeAllImagesOnly();

        // Run spatial distance analysis if requested
        if (runSpatial) {
            IJ.log("--- Running Spatial Distance Analysis ---");
            SpatialAnalysis spatialAnalysis = createSpatialAnalysisForRun();
            spatialAnalysis.run(directory);
            IJ.log("Spatial distance analysis complete.");
        }

        AsyncImageSaver.waitForAllWithProgress(parallelThreads);

        // Write QC report with segmentation overlays
        if (qualityReport != null && qualityReport.isEnabled()) {
            qualityReport.write3DObjectQC();
        }

        long totalTime = System.currentTimeMillis() - analysisStartTime;
        IJ.log("__________________________________________________________");
        if (lifOpenTimeMs > 0) {
            IJ.log("3D Object Analysis complete. Total time: " + formatDuration(totalTime)
                    + " (processing) + " + formatDuration(lifOpenTimeMs) + " (lif open)");
        } else {
            IJ.log("3D Object Analysis complete. Total time: " + formatDuration(totalTime));
        }

        if (!suppressDialogs) IJ.showMessage("3D Object Analysis", "Finished.");
    }

    private void ensureStringColumn(ResultsTable t, String col) {
        if (t == null) return;
        if (t.size() == 0) {
            // Don't create a dummy row — the column will be created when real data is added.
            return;
        }
        try {
            t.getStringValue(col, 0);
        } catch (Exception e) {
            t.setValue(col, 0, "");
        }
    }

    // ── Sequential image processing (original behavior) ──

    private boolean hasSelectedRoiSets(boolean[] selectedRoiSets) {
        if (selectedRoiSets == null) return false;
        for (boolean selected : selectedRoiSets) {
            if (selected) return true;
        }
        return false;
    }

    private void addThreeDObjectSetupControls(final PipelineDialog dialog,
                                              final String directory,
                                              final BinConfig cfg,
                                              final ChannelIdentities identities,
                                              final List<String> roiSetNames,
                                              final ThreeDObjectDialogBindings bindings,
                                              final ThreeDObjectConfigApplier applier) {
        final JComboBox<String> presetCombo = new JComboBox<String>(listThreeDObjectPresetNames(directory));
        presetCombo.setMaximumSize(new Dimension(260, 24));
        if (bindings != null) {
            bindings.presetCombo = presetCombo;
        }
        final JButton savePreset = new JButton("Save as preset...");
        savePreset.setToolTipText("Save the current 3D Object Analysis options as a named preset.");
        savePreset.addActionListener(e -> handleSaveThreeDObjectPreset(directory, cfg, bindings));
        JPanel row = SetupHelperButton.createHeaderRow("3D Object Setup", presetCombo, savePreset,
                new SetupHelperButton.WizardLauncher() {
                    @Override public void run() {
                        final ThreeDObjectWizard.DerivedConfig[] selected =
                                new ThreeDObjectWizard.DerivedConfig[1];
                        dialog.runChildWorkflow(new Runnable() {
                            @Override public void run() {
                                selected[0] = runThreeDObjectSetupHelper(
                                        directory, cfg, identities, roiSetNames);
                            }
                        });
                        if (selected[0] != null && applier != null) {
                            applier.apply(null, selected[0]);
                        }
                    }
                });
        presetCombo.addActionListener(e -> {
            if (bindings != null && bindings.programmaticChange) {
                return;
            }
            Object selected = presetCombo.getSelectedItem();
            if (selected != null && !OBJECT_PRESET_PLACEHOLDER.equals(String.valueOf(selected))) {
                ThreeDObjectWizard.DerivedConfig derived = loadThreeDObjectPresetConfig(
                        directory, cfg, identities, String.valueOf(selected));
                if (derived != null && applier != null) {
                    applier.apply(String.valueOf(selected), derived);
                }
            }
        });
        dialog.addComponent(row);
    }

    private void applyThreeDObjectConfigToDialog(BinConfig cfg,
                                                 ThreeDObjectWizard.DerivedConfig derived,
                                                 ThreeDObjectDialogBindings bindings,
                                                 String selectedPresetName) {
        if (derived == null || bindings == null) {
            return;
        }
        applyThreeDObjectDerivedConfig(cfg, derived);
        bindings.programmaticChange = true;
        try {
            if (bindings.presetCombo != null) {
                bindings.presetCombo.setSelectedItem(
                        selectedPresetName == null ? OBJECT_PRESET_PLACEHOLDER : selectedPresetName);
            }
            setToggle(bindings.doVolumetricToggle, doVolumetric);
            setToggle(bindings.doCpcToggle, doCpc);
            setToggle(bindings.extractProcessLengthToggle, wizardExtractProcessLength);
            setToggle(bindings.runSpatialToggle, wizardRunSpatial);
            setToggle(bindings.classicalCentroidFilterToggle, classicalCentroidFilter);
            for (int i = 0; i < bindings.thresholdFields.size() && cfg != null && i < cfg.channelNames.size(); i++) {
                JTextField field = bindings.thresholdFields.get(i);
                Double threshold = markerThresholds.get(cfg.channelNames.get(i));
                if (field != null && threshold != null) {
                    field.setText(numericText(threshold.doubleValue(), 0));
                }
            }
        } finally {
            bindings.programmaticChange = false;
        }
    }

    private void handleSaveThreeDObjectPreset(String directory,
                                              BinConfig cfg,
                                              ThreeDObjectDialogBindings bindings) {
        if (headless || suppressDialogs) return;
        if (bindings == null) {
            IJ.showMessage("3D Object Analysis", "Could not save preset: dialog options are not available.");
            return;
        }
        String name = JOptionPane.showInputDialog(
                bindings.presetCombo,
                "Preset name:",
                "Save 3D Object Preset",
                JOptionPane.PLAIN_MESSAGE);
        if (name == null) {
            return;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            IJ.showMessage("3D Object Analysis", "Preset name cannot be empty.");
            return;
        }
        try {
            ThreeDObjectPreset preset = buildThreeDObjectPresetFromBindings(trimmed, cfg, bindings);
            new ThreeDObjectPresetIO(new File(directory)).save(preset);
            IJ.log("Saved 3D Object preset: " + trimmed);
            refreshThreeDObjectPresetChoice(directory, bindings, preset.getName());
        } catch (IOException e) {
            IJ.showMessage("3D Object Analysis", "Could not save preset: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            IJ.showMessage("3D Object Analysis", "Could not save preset: " + e.getMessage());
        }
    }

    private ThreeDObjectPreset buildThreeDObjectPresetFromBindings(String name,
                                                                   BinConfig cfg,
                                                                   ThreeDObjectDialogBindings bindings) {
        return new ThreeDObjectPreset(
                name,
                "Saved from 3D Object Analysis dialog",
                ThreeDObjectPreset.CURRENT_LIBRARY_VERSION,
                isSelected(bindings.doVolumetricToggle),
                isSelected(bindings.doCpcToggle),
                isSelected(bindings.extractProcessLengthToggle),
                isSelected(bindings.runSpatialToggle),
                isSelected(bindings.classicalCentroidFilterToggle),
                readFirstThreshold(bindings.thresholdFields),
                selectedProcessMarkerNames(cfg),
                selectedNuclearMarkerNames(cfg));
    }

    private void refreshThreeDObjectPresetChoice(String directory,
                                                 ThreeDObjectDialogBindings bindings,
                                                 String selectedName) {
        if (bindings == null || bindings.presetCombo == null) {
            return;
        }
        bindings.programmaticChange = true;
        try {
            bindings.presetCombo.removeAllItems();
            String[] names = listThreeDObjectPresetNames(directory);
            for (String presetName : names) {
                bindings.presetCombo.addItem(presetName);
            }
            bindings.presetCombo.setSelectedItem(selectedName == null
                    ? OBJECT_PRESET_PLACEHOLDER
                    : selectedName);
        } finally {
            bindings.programmaticChange = false;
        }
    }

    private List<String> selectedProcessMarkerNames(BinConfig cfg) {
        List<String> names = new ArrayList<String>();
        if (cfg == null || wizardProcessChannels == null) {
            return names;
        }
        for (int i = 0; i < cfg.channelNames.size() && i < wizardProcessChannels.length; i++) {
            if (wizardProcessChannels[i]) {
                names.add(cfg.channelNames.get(i));
            }
        }
        return names;
    }

    private List<String> selectedNuclearMarkerNames(BinConfig cfg) {
        List<String> names = new ArrayList<String>();
        if (cfg != null && wizardNuclearMarkerIndex >= 0
                && wizardNuclearMarkerIndex < cfg.channelNames.size()) {
            names.add(cfg.channelNames.get(wizardNuclearMarkerIndex));
        }
        return names;
    }

    private String[] listThreeDObjectPresetNames(String directory) {
        List<String> labels = new ArrayList<String>();
        labels.add(OBJECT_PRESET_PLACEHOLDER);
        try {
            List<ThreeDObjectPreset> presets = new ThreeDObjectPresetIO(new File(directory)).listAll();
            for (ThreeDObjectPreset preset : presets) {
                labels.add(preset.getName());
            }
        } catch (IOException e) {
            IJ.log("WARNING: Could not list 3D Object presets: " + e.getMessage());
        }
        return labels.toArray(new String[labels.size()]);
    }

    private ThreeDObjectWizard.DerivedConfig runThreeDObjectSetupHelper(String directory,
                                                                        BinConfig cfg,
                                                                        ChannelIdentities identities,
                                                                        List<String> roiSetNames) {
        try {
            final SpatialAnalysisWizard.DerivedConfig[] spatialConfig =
                    new SpatialAnalysisWizard.DerivedConfig[1];
            ThreeDObjectWizard wizard = new ThreeDObjectWizard(
                    flash.pipeline.ui.wizard.WizardFlow.MainPanelBinding.NULL,
                    cfg,
                    identities,
                    roiSetNames,
                    false,
                    new ThreeDObjectWizard.SpatialWizardLauncher() {
                        @Override public void launch(ThreeDObjectWizard.DerivedConfig config) {
                            IJ.log("Spatial Analysis Setup Helper requested by 3D Object Setup Helper.");
                            SpatialAnalysisWizard spatialWizard = new SpatialAnalysisWizard(
                                    flash.pipeline.ui.wizard.WizardFlow.MainPanelBinding.NULL,
                                    identities,
                                    firstSeriesInfoOrNull(directory),
                                    calibrationIsAvailable(directory),
                                    true,
                                    false);
                            spatialWizard.run();
                            if (!spatialWizard.wasFinished()) {
                                return;
                            }
                            spatialConfig[0] = spatialWizard.deriveCurrentConfig();
                        }
                    });
            ThreeDObjectWizard.DerivedConfig derived = wizard.runAndMaybeLaunchSpatial();
            wizardSpatialConfig = spatialConfig[0];
            return derived;
        } catch (Exception e) {
            IJ.handleException(e);
            return null;
        }
    }

    boolean prepareSpatialHandoffBeforeAnalysis(String directory,
                                                ChannelIdentities identities,
                                                boolean runSpatial) {
        if (!runSpatial) {
            wizardSpatialConfig = null;
            return true;
        }
        if (wizardSpatialConfig != null || suppressDialogs || cliConfig != null) {
            return true;
        }

        SpatialAnalysisWizard.DerivedConfig spatialConfig = spatialSetupLauncher.launch(
                directory,
                identities,
                !markerThresholds.isEmpty());
        if (spatialConfig == null) {
            IJ.log("[FLASH] 3D Object Analysis cancelled because Spatial Analysis setup was cancelled.");
            if (!headless && !suppressDialogs) {
                IJ.showMessage("3D Object Analysis",
                        "Spatial Analysis setup was cancelled.\n3D Object Analysis has not started.");
            }
            return false;
        }
        wizardSpatialConfig = spatialConfig;
        return true;
    }

    SpatialAnalysis createSpatialAnalysisForRun() {
        SpatialAnalysis spatialAnalysis = new SpatialAnalysis();
        spatialAnalysis.setHeadless(headless);
        spatialAnalysis.setSuppressDialogs(suppressDialogs || cliConfig != null);
        spatialAnalysis.setMarkerThresholds(markerThresholds);
        spatialAnalysis.setParallelThreads(parallelThreads);
        spatialAnalysis.setAggressiveMemory(aggressiveMemory);
        spatialAnalysis.setVerboseLogging(verboseLogging);
        spatialAnalysis.setCliConfig(cliConfig);
        if (wizardSpatialConfig != null) {
            spatialAnalysis.setWizardConfig(wizardSpatialConfig);
        }
        return spatialAnalysis;
    }

    private ThreeDObjectWizard.DerivedConfig loadThreeDObjectPresetConfig(String directory,
                                                                         BinConfig cfg,
                                                                         ChannelIdentities identities,
                                                                         String presetName) {
        try {
            ThreeDObjectPreset preset = new ThreeDObjectPresetIO(new File(directory)).load(presetName);
            return ThreeDObjectWizard.fromPreset(cfg, identities, preset);
        } catch (IOException e) {
            IJ.showMessage("3D Object Analysis", "Could not load preset: " + e.getMessage());
            return null;
        }
    }

    private void applyCliObjectConfiguration(String directory, BinConfig cfg, ChannelIdentities identities) {
        if (cliConfig == null || cliConfig.getObject() == null || !cliConfig.getObject().hasConfiguration()) {
            return;
        }
        CLIConfig.ThreeDObjectConfig object = cliConfig.getObject();
        ThreeDObjectWizard.DerivedConfig derived = null;
        if (object.getPresetName() != null && !object.getPresetName().trim().isEmpty()) {
            derived = loadThreeDObjectPresetConfig(directory, cfg, identities, object.getPresetName());
        }
        if (derived == null) {
            derived = ThreeDObjectWizard.deriveConfig(cfg, identities,
                    Collections.<String, Object>emptyMap(), Collections.<String>emptyList());
        }
        if (object.getDoVolumetric() != null) {
            derived.doVolumetric = object.getDoVolumetric().booleanValue();
        }
        if (object.getDoCpc() != null) {
            derived.doCpc = object.getDoCpc().booleanValue();
        }
        if (object.getExtractProcessLength() != null) {
            derived.extractProcessLength = object.getExtractProcessLength().booleanValue();
        }
        if (object.getRunSpatial() != null) {
            derived.runSpatial = object.getRunSpatial().booleanValue();
        }
        if (object.getClassicalCentroidFiltering() != null) {
            derived.classicalCentroidFiltering = object.getClassicalCentroidFiltering().booleanValue();
        }
        if (object.getColocThresholdPercent() != null) {
            derived.thresholdPercent = object.getColocThresholdPercent().doubleValue();
            derived.markerThresholds.clear();
            for (String channelName : cfg.channelNames) {
                derived.markerThresholds.put(channelName, object.getColocThresholdPercent());
            }
        }
        if (object.getNuclearMarkerIndex() != null) {
            derived.nuclearMarkerIndex = object.getNuclearMarkerIndex().intValue();
        }
        applyThreeDObjectDerivedConfig(cfg, derived);
    }

    private void applyThreeDObjectDerivedConfig(BinConfig cfg, ThreeDObjectWizard.DerivedConfig derived) {
        if (derived == null) return;
        doVolumetric = derived.doVolumetric;
        doCpc = derived.doCpc;
        classicalCentroidFilter = derived.classicalCentroidFiltering;
        wizardExtractProcessLength = derived.extractProcessLength;
        wizardRunSpatial = derived.runSpatial;
        wizardNuclearMarkerIndex = derived.nuclearMarkerIndex;
        wizardProcessChannels = Arrays.copyOf(derived.processChannels, derived.processChannels.length);
        markerThresholds = new LinkedHashMap<String, Double>(derived.markerThresholds);
    }

    private static void setToggle(ToggleSwitch toggle, boolean selected) {
        if (toggle != null) {
            toggle.setSelected(selected);
        }
    }

    private static boolean isSelected(ToggleSwitch toggle) {
        return toggle != null && toggle.isSelected();
    }

    private static double readFirstThreshold(List<JTextField> thresholdFields) {
        if (thresholdFields == null || thresholdFields.isEmpty()) {
            return 30.0;
        }
        return readNumericField(thresholdFields.get(0), 30.0, "Colocalisation threshold");
    }

    private static double readNumericField(JTextField field, double fallback, String label) {
        if (field == null) {
            return fallback;
        }
        String text = field.getText();
        if (text == null || text.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be a number.");
        }
    }

    private static String numericText(double value, int decimals) {
        if (decimals <= 0) {
            return String.valueOf((int) Math.round(value));
        }
        return String.format(Locale.US, "%." + decimals + "f", value);
    }

    private interface ThreeDObjectConfigApplier {
        void apply(String selectedPresetName, ThreeDObjectWizard.DerivedConfig derived);
    }

    private static final class ThreeDObjectDialogBindings {
        boolean programmaticChange;
        JComboBox<String> presetCombo;
        ToggleSwitch useDeconvolvedInputToggle;
        ToggleSwitch doVolumetricToggle;
        ToggleSwitch doCpcToggle;
        ToggleSwitch extractProcessLengthToggle;
        ToggleSwitch runSpatialToggle;
        ToggleSwitch classicalCentroidFilterToggle;
        List<JTextField> thresholdFields = new ArrayList<JTextField>();
    }

    private static MetadataDiagnostics.SeriesInfo firstSeriesInfoOrNull(String directory) {
        try {
            List<MetadataDiagnostics.SeriesInfo> infos = MetadataDiagnostics.scanDirectory(directory);
            return infos.isEmpty() ? null : infos.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean calibrationIsAvailable(String directory) {
        CalibrationIO.PixelCalibration cal = CalibrationIO.readFromDirectory(directory);
        return cal != null && cal.isCalibrated();
    }

    private boolean validateRoiSets(RoiSetData[] roiSets, int totalImages) {
        int expected = totalImages * 2;
        IJ.log("Validating ROI sets...");
        for (RoiSetData roiSet : roiSets) {
            int count = roiSet == null || roiSet.rois == null ? 0 : roiSet.rois.length;
            String roiSetName = roiSet == null ? "" : roiSet.name;
            IJ.log("  ROI set '" + roiSetName + "': " + count + " ROIs (expected " + expected + ")");
            if (count != expected) {
                IJ.error("3D Object Analysis",
                        "ROI set '" + roiSetName + "' has " + count
                                + " ROIs but expected " + expected + " (2 per image).\n"
                                + "3D Object Analysis requires exactly 2 ROIs per image for every ROI set.");
                return false;
            }
        }
        IJ.log("  All ROI sets validated.");
        return true;
    }

    private void processRoiSetForImage(
            String directory,
            BinConfig cfg,
            ImagePlus imp,
            File outDir,
            File imageAnalysisRoot,
            Map<String, ij.measure.ResultsTable> channelTables,
            int imageIndex,
            int scnIndex,
            String animalName,
            NameParts parts,
            boolean extractProcessLength,
            int nuclearMarkerIndex,
            boolean[] processChannels,
            RoiSetData roiSet) {

        int roiIdx = imageIndex * 2;
        ij.gui.Roi cropRoi = roiSet == null ? null : roiSet.cloneRoi(roiIdx);
        ij.gui.Roi clearRoi = roiSet == null ? null : roiSet.cloneRoi(roiIdx + 1);
        String roiBase = roiSet == null ? "" : roiSet.name;
        String hemisphere = parts == null ? "" : parts.hemisphere;
        String seriesRegionLabel = parts == null
                ? ""
                : parts.csvRegion();
        String roiLabel = parts == null
                ? (scnIndex > 0 && !roiBase.matches(".*\\d$") ? roiBase + scnIndex : roiBase)
                : parts.analysisRegionLabel(roiBase, scnIndex);

        if (!compactLog) {
            IJ.log("  > ROI set: " + roiBase);
        }
        if (verboseLogging) {
            IJ.log("  [DEBUG] ROI set '" + roiBase + "' indices: " + roiIdx + " and " + (roiIdx + 1));
        }

        try {
            boolean[] channelHasObjects =
                    run3DObjectsCounterPerChannel(directory, cfg, imp, outDir, imageAnalysisRoot, channelTables, scnIndex,
                            animalName, parts,
                            extractProcessLength, nuclearMarkerIndex, processChannels,
                            cropRoi, clearRoi, seriesRegionLabel, roiLabel, roiBase);

            IJ.log("  > Colocalization");
            if (doVolumetric) {
                appendColocColumns(cfg, channelHasObjects, channelTables, scnIndex, animalName,
                        hemisphere, seriesRegionLabel, roiLabel);
            }
            if (doCpc) {
                appendCpcColocColumns(cfg, channelHasObjects, channelTables, scnIndex, animalName,
                        hemisphere, seriesRegionLabel, roiLabel);
            }

            if (extractProcessLength && processChannels != null) {
                processLengthExtractionWithTables(cfg, channelTables, imp, scnIndex, animalName,
                        hemisphere, seriesRegionLabel, roiLabel, processChannels, nuclearMarkerIndex);
            }

            saveObjectsImages(cfg, imageAnalysisRoot, animalName, hemisphere, roiLabel);
        } finally {
            clearRegistry();
        }
    }

    private void processImagesSequential(
            final DeferredImageSupplier supplier, final int totalImages,
            String directory, BinConfig cfg,
            File outDir, File imageAnalysisRoot,
            Map<String, ij.measure.ResultsTable> channelTables,
            RoiSetData[] roiSets,
            boolean extractProcessLength, int nuclearMarkerIndex, boolean[] processChannels,
            long analysisStartTime) {

        ExecutorService prefetcher = Executors.newSingleThreadExecutor();
        Future<ImagePlus> nextImage = null;

        for (int i = 0; i < totalImages; i++) {
            IJ.showStatus("Loading image " + (i + 1) + "/" + totalImages + "...");
            IJ.showProgress(i, totalImages);

            ImagePlus imp;
            if (nextImage != null) {
                try {
                    imp = nextImage.get();
                } catch (Exception e) {
                    IJ.log("ERROR: Failed to load prefetched image " + (i + 1) + ": " + e.getMessage());
                    nextImage = null;
                    continue;
                }
                nextImage = null;
            } else {
                IJ.log("Loading image " + (i + 1) + "/" + totalImages + "...");
                try {
                    imp = supplier.openSeries(i);
                } catch (Exception e) {
                    IJ.log("ERROR: Failed to open image " + (i + 1) + ": " + e.getMessage());
                    continue;
                }
            }
            if (imp == null) continue;
            imp = applyConfiguredZSliceSubset(cfg, i, imp, "3D Object Analysis");

            // Write calibration from the first successfully-loaded image
            if (calibrationWritten.compareAndSet(false, true)) {
                CalibrationIO.writeFromImage(outDir, imp);
            }

            // Start loading next image while processing this one
            if (i + 1 < totalImages) {
                final int nextIdx = i + 1;
                nextImage = prefetcher.submit(new Callable<ImagePlus>() {
                    @Override
                    public ImagePlus call() throws Exception {
                        return supplier.openSeries(nextIdx);
                    }
                });
            }

            String imgTitle = imp.getTitle();
            int scnIndex = i + 1;

            ResolvedImageMetadata metadata = ImageOrientationResolver.resolve(directory, imgTitle, i + 1);
            NameParts parts = metadata.toNameParts();
            String animalName = parts.animal;

            // Note: whole-analysis skip is handled before image loading (see
            // the pre-scan block above).  Per-image skip is not meaningful for
            // 3D Object Analysis because the principal outputs are aggregate
            // per-channel CSVs written once at the end of the run.

            long imageStartTime = verboseLogging ? System.currentTimeMillis() : 0;

            IJ.log("__________________________________________________________");
            IJ.log("Image Stack " + scnIndex + "/" + totalImages + ": " + imgTitle);
            if (i > 0) {
                long elapsed = System.currentTimeMillis() - analysisStartTime;
                long avgPerImage = elapsed / i;
                long remainingMs = avgPerImage * (totalImages - i);
                IJ.log("Estimated time to completion: " + formatDuration(remainingMs));
            }

            if (verboseLogging) {
                IJ.log("  [DEBUG] Parsed: Animal=" + animalName + ", Hemisphere=" + parts.hemisphere
                        + ", Region=" + parts.region);
                IJ.log("  [DEBUG] Image dimensions: " + imp.getWidth() + "x" + imp.getHeight()
                        + "x" + imp.getNSlices() + ", channels=" + imp.getNChannels());
            }

            logOrientationResolution(metadata);
            OrientationOps.applyTransform(imp, metadata);

            // Determine if the count-once-assign-per-ROI optimisation can be used.
            // Requires: all channels use centroid filtering, multiple ROI sets,
            // mcib3d available, AND enough heap headroom for the per-channel
            // full-image clones the optimisation retains across ROI iterations.
            boolean allCentroid = true;
            for (int c = 0; c < cfg.numChannels(); c++) {
                if (!cfg.usesLabelImageSegmentation(c) && !classicalCentroidFilter) {
                    allCentroid = false;
                    break;
                }
            }
            boolean useSharedCounting = allCentroid && roiSets.length > 1
                    && isMcib3dAvailable() && canRunCountOnce(imp);

            try {
                if (useSharedCounting) {
                    // Count all objects on the full image once, then assign per ROI
                    if (!compactLog) IJ.log("  Counting objects on full image (shared across " + roiSets.length + " ROI sets)...");
                    FullImageCountData fullData = countAllChannelsFullImage(directory, cfg, imp, outDir,
                            animalName, parts == null ? "" : parts.hemisphere, null);
                    try {
                        for (RoiSetData roiSet : roiSets) {
                            processRoiSetFromFullCount(fullData, directory, cfg, imp, outDir, imageAnalysisRoot,
                                    channelTables, i, scnIndex, animalName, parts,
                                    extractProcessLength, nuclearMarkerIndex, processChannels, roiSet);
                        }
                    } finally {
                        cleanupFullImageData(fullData);
                    }
                } else {
                    // Original path: count per ROI set
                    for (RoiSetData roiSet : roiSets) {
                        processRoiSetForImage(directory, cfg, imp, outDir, imageAnalysisRoot, channelTables,
                                i, scnIndex, animalName, parts,
                                extractProcessLength, nuclearMarkerIndex, processChannels, roiSet);
                    }
                }

            } catch (Exception ex) {
                IJ.handleException(ex);
            } finally {
                imp.changes = false;
                imp.close();
                imp.flush();
                closeAllNoPrompt();

                long elapsed = System.currentTimeMillis() - analysisStartTime;
                long avgPerImage = elapsed / (i + 1);
                long remainingMs = avgPerImage * (totalImages - (i + 1));
                IJ.showProgress(i + 1, totalImages);
                IJ.showStatus("Processing " + (i + 1) + "/" + totalImages
                        + " (~" + formatDuration(remainingMs) + " remaining)");

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

    // ── Parallel image processing ──

    private void processImagesParallel(
            final BoundedImageLoader loader,
            final int nThreads,
            final String directory, final BinConfig cfg,
            final File outDir, final File imageAnalysisRoot,
            final Map<String, ij.measure.ResultsTable> channelTables,
            final RoiSetData[] roiSets,
            final boolean extractProcessLength, final int nuclearMarkerIndex, final boolean[] processChannels,
            final long analysisStartTime) {

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
                        imp = applyConfiguredZSliceSubset(cfg, idx, imp, "3D Object Analysis");

                        // Write calibration from the first image (thread-safe)
                        if (calibrationWritten.compareAndSet(false, true)) {
                            CalibrationIO.writeFromImage(outDir, imp);
                        }

                        ParallelContext.enterParallel();
                        // Set up thread-local image registry
                        Map<String, ImagePlus> localRegistry = new LinkedHashMap<String, ImagePlus>();
                        threadLocalRegistry.set(localRegistry);

                        // Per-image local channel tables (will be merged after)
                        Map<String, ij.measure.ResultsTable> localChannelTables = new LinkedHashMap<String, ij.measure.ResultsTable>();
                        for (String chName : cfg.channelNames) {
                            localChannelTables.put(chName, new ij.measure.ResultsTable());
                        }

                        try {
                            String imgTitle = imp.getTitle();
                            int scnIndex = idx + 1;

                            ResolvedImageMetadata metadata = ImageOrientationResolver.resolve(
                                    directory, imgTitle, idx + 1);
                            NameParts parts = metadata.toNameParts();
                            String animalName = parts.animal;

                            // Worker start log with short name and channels
                            String workerTag = effectiveThreads > 1
                                    ? "Worker " + workerNum : "Worker";
                            String partLabel = parts.displayLabel();
                            StringBuilder chList = new StringBuilder();
                            for (int ci = 0; ci < cfg.channelNames.size(); ci++) {
                                if (ci > 0) chList.append(" ");
                                chList.append(cfg.channelNames.get(ci));
                            }
                            IJ.log(workerTag + ": processing " + partLabel
                                    + " | " + chList.toString());

                            // Note: whole-analysis skip is handled before image loading.
                            // Per-image skip is not meaningful for 3D because outputs
                            // are aggregate per-channel CSVs written once at the end.

                            long imageStartTime = System.currentTimeMillis();

                            if (!compactLog) {
                                IJ.log("[" + scnIndex + "/" + total + "] Processing: " + imgTitle);
                            }

                            logOrientationResolution(metadata);
                            OrientationOps.applyTransform(imp, metadata);

                            // Check if count-once optimisation applies (incl. heap budget)
                            boolean allCentroid = true;
                            for (int ci2 = 0; ci2 < cfg.numChannels(); ci2++) {
                                if (!cfg.usesLabelImageSegmentation(ci2) && !classicalCentroidFilter) {
                                    allCentroid = false;
                                    break;
                                }
                            }
                            boolean useSharedCounting = allCentroid && roiSets.length > 1
                                    && isMcib3dAvailable() && canRunCountOnce(imp);

                            if (useSharedCounting) {
                                if (!compactLog) IJ.log("  Counting objects on full image (shared across " + roiSets.length + " ROI sets)...");
                                FullImageCountData fullData = countAllChannelsFullImage(directory, cfg, imp, outDir,
                                        animalName, parts == null ? "" : parts.hemisphere, null);
                                try {
                                    for (RoiSetData roiSet : roiSets) {
                                        processRoiSetFromFullCount(fullData, directory, cfg, imp, outDir, imageAnalysisRoot,
                                                localChannelTables, idx, scnIndex, animalName, parts,
                                                extractProcessLength, nuclearMarkerIndex, processChannels, roiSet);
                                    }
                                } finally {
                                    cleanupFullImageData(fullData);
                                }
                            } else {
                                for (RoiSetData roiSet : roiSets) {
                                    processRoiSetForImage(directory, cfg, imp, outDir, imageAnalysisRoot, localChannelTables,
                                            idx, scnIndex, animalName, parts,
                                            extractProcessLength, nuclearMarkerIndex, processChannels, roiSet);
                                }
                            }

                            // Merge local tables into shared channelTables
                            synchronized (channelTables) {
                                for (String chName : cfg.channelNames) {
                                    ij.measure.ResultsTable localT = localChannelTables.get(chName);
                                    ij.measure.ResultsTable sharedT = channelTables.get(chName);
                                    if (localT == null || sharedT == null) continue;
                                    mergeResultsTable(localT, sharedT);
                                }
                            }

                            int done = completed.incrementAndGet();
                            long elapsed = System.currentTimeMillis() - analysisStartTime;
                            IJ.showProgress(done, total);
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
                                IJ.log("[" + scnIndex + "/" + total + "] Complete (" + done + "/" + total
                                        + " done, ~" + formatDuration(rem) + " remaining)");
                                if (verboseLogging) {
                                    long imageElapsed = System.currentTimeMillis() - imageStartTime;
                                    IJ.log("[" + scnIndex + "/" + total + "] Processing time: " + formatDuration(imageElapsed));
                                }
                            }
                        } catch (Exception e) {
                            IJ.log("[" + (idx + 1) + "/" + total + "] ERROR: " + e.getMessage());
                            e.printStackTrace();
                        } finally {
                            // Close image after processing
                            imp.changes = false;
                            imp.close();
                            imp.flush();

                            ParallelContext.exitParallel();
                            // Clear thread-local registry
                            Map<String, ImagePlus> reg = threadLocalRegistry.get();
                            if (reg != null) {
                                for (ImagePlus im : reg.values()) {
                                    if (im != null) {
                                        im.changes = false;
                                        im.close();
                                        im.flush();
                                    }
                                }
                                reg.clear();
                            }
                            threadLocalRegistry.remove();

                            if (aggressiveMemory) {
                                System.gc();
                                IJ.freeMemory();
                            }
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

    /** Merge all rows from source into dest ResultsTable. */
    private void mergeResultsTable(ij.measure.ResultsTable source, ij.measure.ResultsTable dest) {
        if (source == null || source.size() == 0) return;
        String[] headings = source.getHeadings();
        for (int r = 0; r < source.size(); r++) {
            dest.incrementCounter();
            int destRow = dest.size() - 1;
            for (String h : headings) {
                if (h == null) continue;
                // Try string first (for Animal Name, Hemisphere, Region)
                String sVal = null;
                try {
                    sVal = source.getStringValue(h, r);
                } catch (Exception ignored) {
                }
                if (sVal != null && !sVal.isEmpty()) {
                    // Check if it's a pure number string
                    try {
                        double v = Double.parseDouble(sVal);
                        dest.setValue(h, destRow, v);
                    } catch (NumberFormatException nfe) {
                        dest.setValue(h, destRow, sVal);
                    }
                } else {
                    try {
                        double v = source.getValue(h, r);
                        dest.setValue(h, destRow, v);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    /** Process length extraction helper (uses instance channelTables reference). */
    private void processLengthExtraction(BinConfig cfg, Map<String, ij.measure.ResultsTable> channelTables,
                                          ImagePlus imp, int scnIndex, String animalName, NameParts parts,
                                          boolean[] processChannels, int nuclearMarkerIndex) {
        String hemisphere = parts == null ? "" : parts.hemisphere;
        String region = parts == null
                ? ""
                : parts.csvRegion();
        processLengthExtractionWithTables(cfg, channelTables, imp, scnIndex, animalName,
                hemisphere, region, region, processChannels, nuclearMarkerIndex);
    }

    /** Process length extraction with explicit channel tables (for parallel use). */
    private void processLengthExtractionWithTables(BinConfig cfg, Map<String, ij.measure.ResultsTable> channelTables,
                                                    ImagePlus imp, int scnIndex, String animalName,
                                                    String hemisphere, String region, String roiLabel,
                                                    boolean[] processChannels, int nuclearMarkerIndex) {
        if (!compactLog) IJ.log("  > Process Length Extraction");
        Calibration cal = imp.getCalibration();
        double pixelWidth = (cal != null) ? cal.pixelWidth : 1.0;
        double pixelHeight = (cal != null) ? cal.pixelHeight : 1.0;
        double pixelDepth = (cal != null) ? cal.pixelDepth : 1.0;
        // Per-voxel skeleton length scale. For isotropic XY (pixelWidth==pixelHeight==pixelDepth)
        // this is just pixelWidth. For anisotropic stacks, fall back to the geometric mean of
        // all three calibration axes — closer to a direction-agnostic mean voxel step than any
        // single axis would give. TODO: switch to Object3DInt.getMeasure(LIGNE3D) for true
        // step-direction-weighted skeleton length.
        double voxelLengthScale = pixelWidth;
        if (pixelWidth != pixelHeight || pixelWidth != pixelDepth) {
            voxelLengthScale = Math.cbrt(pixelWidth * pixelHeight * pixelDepth);
        }

        for (int c = 0; c < cfg.numChannels(); c++) {
            if (!processChannels[c]) continue;
            String channelName = cfg.channelNames.get(c);
            String processName = "Process Channel " + channelName;
            ImagePlus processImg = getRegisteredImage(processName);
            ImagePlus nuclearImg = getRegisteredImage("Nuclear Marker");
            if (processImg == null || nuclearImg == null) {
                IJ.log("    - Skipped process length for " + channelName + " (missing skeleton images)");
                continue;
            }

            ImagePlus subtracted = ImageCalcOps.subtractStackThreadSafe(processImg, nuclearImg);
            if (subtracted == null) {
                IJ.log("    - Skipped process length for " + channelName + " (subtract failed)");
                continue;
            }
            registerImage(processName + " subtracted", subtracted, true);

            ImagePlus filteredImg = getRegisteredImage(channelName + "_filtered");
            if (filteredImg == null) {
                IJ.log("    - Skipped process length for " + channelName + " (no filtered image)");
                subtracted.changes = false;
                subtracted.close();
                subtracted.flush();
                continue;
            }

            String thrToken = cfg.channelThresholds.get(c);
            Double threshold = tryParseDouble(thrToken);
            if (threshold == null) {
                if ("default".equalsIgnoreCase(thrToken)) {
                    threshold = ThresholdOps.defaultDarkThresholdAtSlice6(filteredImg);
                } else {
                    subtracted.changes = false;
                    subtracted.close();
                    subtracted.flush();
                    continue;
                }
            }

            String sizeToken = cfg.channelSizes.get(c);
            String[] sizeParts = sizeToken.split("-");
            int minSizeVox = ObjectsCounter3DWrapper.parseMinSizeVoxels(
                    sizeParts.length > 0 ? sizeParts[0] : "100", 100);
            int maxSizeVox = ObjectsCounter3DWrapper.parseMaxSizeVoxels(
                    sizeParts.length > 1 ? sizeParts[1] : "Infinity", filteredImg);

            ObjectsCounter3DWrapper ocWrapper = new ObjectsCounter3DWrapper();
            ObjectsCounter3DWrapper.Result procRes;
            if (isMcib3dAvailable()) {
                procRes = ocWrapper.runNative(
                        filteredImg,
                        (int) Math.round(threshold),
                        minSizeVox,
                        maxSizeVox,
                        false,
                        subtracted,  // redirect image for intensity measurement
                        false,       // no objects map needed
                        false        // no masked image needed
                );
            } else {
                COUNTER3D_LOCK.lock();
                try {
                    ensureInWindowManager(subtracted);
                    procRes = ocWrapper.run(
                            filteredImg,
                            (int) Math.round(threshold),
                            minSizeVox,
                            maxSizeVox,
                            false,
                            true,
                            processName + " subtracted",
                            false,
                            false
                    );
                    if (headless) hideAllImageWindows();
                } finally {
                    COUNTER3D_LOCK.unlock();
                }
            }

            ResultsTable procStats = procRes.getStatistics();
            ResultsTable channelTable = channelTables.get(channelName);
            if (procStats != null && procStats.size() > 0 && channelTable != null) {
                double[] lengths = new double[procStats.size()];
                for (int r = 0; r < procStats.size(); r++) {
                    double intDen = procStats.getValue("IntDen", r);
                    double numVoxels = intDen / 255.0;
                    lengths[r] = numVoxels * voxelLengthScale;
                }
                writeLengthValuesForThisImage(channelTable, scnIndex, animalName,
                        hemisphere, region, roiLabel, lengths);
                if (!compactLog) IJ.log("    - Process length for " + channelName + ": " + procStats.size() + " objects measured");
            }

            subtracted.changes = false;
            subtracted.close();
            subtracted.flush();
        }
    }

    /** Save objects images after colocalization. */
    private void saveObjectsImages(BinConfig cfg, File imageAnalysisRoot, String animalName, String hemisphere, String region) {
        String hemiRegion = buildFileSuffix(hemisphere, region, animalName);
        File perAnimal = new File(imageAnalysisRoot, animalName);
        for (int c = 0; c < cfg.numChannels(); c++) {
            String chName = cfg.channelNames.get(c);
            ImagePlus objImg = getRegisteredImage(chName + "_objects");
            if (objImg != null) {
                String safeChName = ChannelFilenameCodec.toSafe(chName);
                String objFileName = safeChName + "_objects" + (hemiRegion.isEmpty() ? "" : "_" + hemiRegion) + ".tif";
                AsyncImageSaver.saveAsTiffAsync(objImg, new File(perAnimal, objFileName).getAbsolutePath());
            }
        }
    }

    /**
     * Build a file suffix from hemisphere/region, falling back to animalName
     * so that non-convention filenames still produce unique output filenames.
     */
    private static String buildFileSuffix(String hemisphere, String region, String animalName) {
        boolean hasH = hemisphere != null && !hemisphere.isEmpty();
        boolean hasR = region != null && !region.isEmpty();
        if (hasH && hasR) return hemisphere + "_" + region;
        if (hasH) return hemisphere;
        if (hasR) return region;
        // Fallback: use animal name (which is the full image title for non-convention files)
        return (animalName != null && !animalName.isEmpty()) ? animalName : "";
    }

    /** Write temporary tables (macro quirk for i > 0). */
    private void writeTempTables(BinConfig cfg, Map<String, ij.measure.ResultsTable> channelTables,
                                  String directory, int i, boolean extractProcessLength, boolean[] processChannels) {
        for (String ch : cfg.channelNames) {
            ij.measure.ResultsTable t = channelTables.get(ch);
            if (t == null) continue;

            List<String> keep = buildOrderedObjectColumns(ch, t, cfg, extractProcessLength, processChannels);
            ResultsTableCleaner.keepOnlyColumns(t, keep.toArray(new String[0]));

            try {
                CsvTableIO.writeResultsTableCsv(objectTempCsv(objectCsvWriteDir(directory), ch, i), t, keep);
            } catch (Exception ignored) {
            }
        }
    }

    private List<String> buildOrderedObjectColumns(String channelName, ResultsTable table, BinConfig cfg,
                                                   boolean extractProcessLength, boolean[] processChannels) {
        LinkedHashSet<String> keep = new LinkedHashSet<String>();

        String[] headings = table != null ? table.getHeadings() : null;
        if (headings != null) {
            for (String heading : headings) {
                if (heading == null || heading.trim().isEmpty()) continue;
                keep.add(heading);
            }
        }

        keep.add("Region");
        keep.add("Hemisphere");
        keep.add("ROI");
        keep.add("Animal Name");
        keep.add("Label");
        keep.add("Volume (micron^3)");
        keep.add("Surface (micron^2)");
        keep.add("IntDen");
        keep.add("Mean");
        keep.add("XM");
        keep.add("YM");
        keep.add("ZM");

        for (String other : cfg.channelNames) {
            if (other == null || other.equals(channelName)) continue;
            if (doVolumetric) {
                keep.add(colocPercentCol(other));
                keep.add(pearsonCol(channelName, other));
                keep.add(mandersM1Col(channelName, other));
                keep.add(mandersM2Col(channelName, other));
                keep.add(costesTaCol(channelName, other));
                keep.add(costesTbCol(channelName, other));
                keep.add(pearsonThresholdedCol(channelName, other));
                keep.add(costesPCol(channelName, other));
                keep.add(volColocCol(channelName, other));
            }
            if (doCpc) {
                keep.add(channelName + "_CPCColoc_" + other);
                keep.add(channelName + "_CPCContains_" + other);
            }
        }

        if (doCpc) {
            keep.add(channelName + "_CPCTargetsHit");
            keep.add(channelName + "_CPCPattern");
        }

        boolean keepLength = false;
        if (extractProcessLength && processChannels != null) {
            int ci = cfg.channelNames.indexOf(channelName);
            keepLength = ci >= 0 && processChannels[ci];
        }
        if (keepLength) {
            keep.add("Length");
        } else {
            keep.remove("Length");
        }

        return ObjectCsvColumnOrder.orderedColumns(channelName, new ArrayList<String>(keep), cfg.channelNames);
    }

    /**
     * Holds the pre-computed filter + threshold results for a single channel,
     * produced by Phase A (parallel) and consumed by Phase B (sequential Counter3D).
     */
    // StarDist + Cellpose now manage concurrency in their runners via GpuConcurrency
    // (narrow StarDist show()/TrackMate/close() lock + shared GPU semaphore).
    // The WindowManagerLock is still used for classical 3DOC via COUNTER3D_LOCK.

    private static class ChannelFilterResult {
        final int channelIndex;
        final String channelName;
        final ImagePlus unfiltered;
        final ImagePlus filtered;
        final Double threshold;      // null means channel should be skipped
        final int minSizeVox;
        final int maxSizeVox;
        final boolean skipped;       // true if threshold was unrecognised
        final String skipReason;     // reason for skipping (logged later)
        final boolean labelImageSegmentation;
        final String segmentationName;
        final ImagePlus labelImage;
        final String segmentationSummary;
        final ImagePlus preDetectionFiltered; // filtered+cropped image before segmentation/threshold (for saving)

        ChannelFilterResult(int channelIndex, String channelName, ImagePlus unfiltered,
                            ImagePlus filtered, Double threshold, int minSizeVox, int maxSizeVox,
                            boolean skipped, String skipReason) {
            this(channelIndex, channelName, unfiltered, filtered, threshold, minSizeVox, maxSizeVox,
                    skipped, skipReason, false, "", null, "", null);
        }

        ChannelFilterResult(int channelIndex, String channelName, ImagePlus unfiltered,
                            ImagePlus filtered, Double threshold, int minSizeVox, int maxSizeVox,
                            boolean skipped, String skipReason,
                            boolean labelImageSegmentation, String segmentationName, ImagePlus labelImage,
                            String segmentationSummary,
                            ImagePlus preDetectionFiltered) {
            this.channelIndex = channelIndex;
            this.channelName = channelName;
            this.unfiltered = unfiltered;
            this.filtered = filtered;
            this.threshold = threshold;
            this.minSizeVox = minSizeVox;
            this.maxSizeVox = maxSizeVox;
            this.skipped = skipped;
            this.skipReason = skipReason;
            this.labelImageSegmentation = labelImageSegmentation;
            this.segmentationName = segmentationName == null ? "" : segmentationName;
            this.labelImage = labelImage;
            this.segmentationSummary = segmentationSummary == null ? "" : segmentationSummary;
            this.preDetectionFiltered = preDetectionFiltered;
        }
    }

    /**
     * Holds per-channel results from counting on the full uncropped image.
     * Used by the count-once-assign-per-ROI optimisation when all channels
     * use centroid filtering (StarDist and/or classicalCentroidFilter).
     */
    private static class FullImageCountData {
        final ChannelFilterResult[] filterResults;
        final ObjectsCounter3DWrapper.Result[] countResults;
        final boolean[] channelHasObjects;
        final int numChannels;

        FullImageCountData(ChannelFilterResult[] filterResults,
                           ObjectsCounter3DWrapper.Result[] countResults,
                           boolean[] channelHasObjects,
                           int numChannels) {
            this.filterResults = filterResults;
            this.countResults = countResults;
            this.channelHasObjects = channelHasObjects;
            this.numChannels = numChannels;
        }
    }

    private boolean[] run3DObjectsCounterPerChannel(
            String directory,
            BinConfig cfg,
            ImagePlus imp,
            File outDir,
            File imageAnalysisRoot,
            Map<String, ij.measure.ResultsTable> channelTables,
            int scnIndex,
            String animalName,
            NameParts parts,
            boolean extractProcessLength,
            int nuclearMarkerIndex,
            boolean[] processChannels,
            ij.gui.Roi cropRoi,
            ij.gui.Roi clearRoi,
            String regionLabel,
            String roiLabel,
            String roiSetName
    ) {
        String hemisphere = parts == null ? "" : parts.hemisphere;
        String region = parts == null ? "" : parts.region;
        ImagePlus[] chans = ChannelSplitter.split(imp);
        if (chans == null || chans.length == 0) {

            IJ.error("No channels found in " + imp.getTitle());
            return new boolean[0];
        }

        int n = Math.min(cfg.numChannels(), chans.length);
        final File binDir = activeConfigurationDir(directory);
        boolean[] channelHasObjects = new boolean[n];

        // ── Phase A: Parallel filter + threshold ──
        // Pre-compute filter and threshold for all channels concurrently.
        // This hides filter latency behind Counter3D execution of other images.

        final ChannelFilterResult[] filterResults = new ChannelFilterResult[n];

        // Capture per-channel config before submitting to threads (all from cfg, which is read-only)
        final String[] channelNames = new String[n];
        final String[] filterFilenames = new String[n];
        final String[] thresholdTokens = new String[n];
        final String[] sizeTokens = new String[n];
        final boolean[] isStarDist = new boolean[n];
        final boolean[] isCellpose = new boolean[n];
        final double[] sdProbThresh = new double[n];
        final double[] sdNmsThresh = new double[n];
        final double[] sdLinkingMaxDistance = new double[n];
        final double[] sdGapClosingMaxDistance = new double[n];
        final int[] sdMaxFrameGap = new int[n];
        final double[] sdAreaMin = new double[n];
        final double[] sdAreaMax = new double[n];
        final double[] sdQualityMin = new double[n];
        final double[] sdIntensityMin = new double[n];
        final String[] cpModel = new String[n];
        final double[] cpDiameter = new double[n];
        final double[] cpFlowThreshold = new double[n];
        final double[] cpCellprobThreshold = new double[n];
        final boolean[] cpUseGpu = new boolean[n];
        final int[] cpSecondChannel = new int[n];
        for (int c = 0; c < n; c++) {
            channelNames[c] = cfg.channelNames.get(c);
            filterFilenames[c] = cfg.filterMacroFilenameForChannelIndex(c);
            thresholdTokens[c] = cfg.channelThresholds.get(c);
            sizeTokens[c] = cfg.channelSizes.get(c);
            isStarDist[c] = cfg.isStarDist(c);
            isCellpose[c] = cfg.isCellpose(c);
            sdProbThresh[c] = cfg.getStarDistProbThresh(c);
            sdNmsThresh[c] = cfg.getStarDistNmsThresh(c);
            sdLinkingMaxDistance[c] = cfg.getStarDistLinkingMaxDistance(c);
            sdGapClosingMaxDistance[c] = cfg.getStarDistGapClosingMaxDistance(c);
            sdMaxFrameGap[c] = cfg.getStarDistMaxFrameGap(c);
            sdAreaMin[c] = cfg.getStarDistAreaMin(c);
            sdAreaMax[c] = cfg.getStarDistAreaMax(c);
            sdQualityMin[c] = cfg.getStarDistQualityMin(c);
            sdIntensityMin[c] = cfg.getStarDistIntensityMin(c);
            cpModel[c] = cfg.getCellposeModel(c);
            cpDiameter[c] = cfg.getCellposeDiameter(c);
            cpFlowThreshold[c] = cfg.getCellposeFlowThreshold(c);
            cpCellprobThreshold[c] = cfg.getCellposeCellprobThreshold(c);
            cpUseGpu[c] = cfg.getCellposeUseGpu(c);
            cpSecondChannel[c] = cfg.getCellposeSecondChannel(c);
        }

        // Use a thread pool for parallel filtering (cap at 4 to avoid memory pressure)
        int filterThreads = Math.min(n, 4);
        final ImagePlus[] cellposeCompanionSources = snapshotCellposeCompanionSources(chans, cpSecondChannel, channelNames);
        if (filterThreads > 1) {
            ExecutorService filterPool = Executors.newFixedThreadPool(filterThreads);
            List<Future<?>> filterFutures = new ArrayList<Future<?>>();

            for (int c = 0; c < n; c++) {
                final int ci = c;
                final ImagePlus ch = chans[c];
                final boolean doExtractProcessLength = extractProcessLength;
                final int nucIdx = nuclearMarkerIndex;
                final boolean[] procChans = processChannels;

                filterFutures.add(filterPool.submit(new Runnable() {
                    @Override
                    public void run() {
                        // Enter ParallelContext so any FilterExecutor calls
                        // inside filterAndThresholdChannel serialise instead
                        // of spawning their own slice pools (avoids
                        // image × channel × slice CPU oversubscription).
                        ParallelContext.enterParallel();
                        try {
                            filterResults[ci] = filterAndThresholdChannel(
                                    ci, channelNames[ci], ch, binDir, filterFilenames[ci],
                                    thresholdTokens[ci], sizeTokens[ci],
                                    doExtractProcessLength, nucIdx, procChans, n,
                                    isStarDist[ci], sdProbThresh[ci], sdNmsThresh[ci],
                                    sdLinkingMaxDistance[ci], sdGapClosingMaxDistance[ci], sdMaxFrameGap[ci],
                                    sdAreaMin[ci], sdAreaMax[ci], sdQualityMin[ci], sdIntensityMin[ci],
                                    isCellpose[ci], cpModel[ci], cpDiameter[ci], cpFlowThreshold[ci],
                                    cpCellprobThreshold[ci], cpUseGpu[ci],
                                    cpSecondChannel[ci],
                                    cpSecondChannel[ci] >= 0 && cpSecondChannel[ci] < cellposeCompanionSources.length ? cellposeCompanionSources[cpSecondChannel[ci]] : null,
                                    cpSecondChannel[ci] >= 0 && cpSecondChannel[ci] < channelNames.length ? channelNames[cpSecondChannel[ci]] : null,
                                    cpSecondChannel[ci] >= 0 && cpSecondChannel[ci] < filterFilenames.length ? filterFilenames[cpSecondChannel[ci]] : null,
                                    cropRoi, clearRoi, roiSetName);
                        } finally {
                            ParallelContext.exitParallel();
                        }
                    }
                }));
            }

            // Wait for all filter tasks to complete
            for (Future<?> f : filterFutures) {
                try {
                    f.get();
                } catch (Exception e) {
                    IJ.log("    WARNING: Filter thread error: " + e.getMessage());
                }
            }
            filterPool.shutdown();
        } else {
            // Single channel — no need for thread pool overhead
            for (int c = 0; c < n; c++) {
                filterResults[c] = filterAndThresholdChannel(
                        c, channelNames[c], chans[c], binDir, filterFilenames[c],
                        thresholdTokens[c], sizeTokens[c],
                        extractProcessLength, nuclearMarkerIndex, processChannels, n,
                        isStarDist[c], sdProbThresh[c], sdNmsThresh[c],
                        sdLinkingMaxDistance[c], sdGapClosingMaxDistance[c], sdMaxFrameGap[c],
                        sdAreaMin[c], sdAreaMax[c], sdQualityMin[c], sdIntensityMin[c],
                        isCellpose[c], cpModel[c], cpDiameter[c], cpFlowThreshold[c],
                        cpCellprobThreshold[c], cpUseGpu[c],
                        cpSecondChannel[c],
                        cpSecondChannel[c] >= 0 && cpSecondChannel[c] < cellposeCompanionSources.length ? cellposeCompanionSources[cpSecondChannel[c]] : null,
                        cpSecondChannel[c] >= 0 && cpSecondChannel[c] < channelNames.length ? channelNames[cpSecondChannel[c]] : null,
                        cpSecondChannel[c] >= 0 && cpSecondChannel[c] < filterFilenames.length ? filterFilenames[cpSecondChannel[c]] : null,
                        cropRoi, clearRoi, roiSetName);
            }
        }

        // ── Phase A.5: Capture QC images for ALL channels before counting ──
        closeQuietly(cellposeCompanionSources);
        // Done here so that a failure during 3D counting doesn't prevent QC capture.
        if (qualityReport != null && qualityReport.isEnabled()) {
            for (int c = 0; c < n; c++) {
                ChannelFilterResult fr = filterResults[c];
                if (fr == null || fr.skipped || fr.filtered == null) continue;
                String lutColor = (c < cfg.channelColors.size()) ? cfg.channelColors.get(c) : "Grays";
                String imgDisplayName = animalName + "_"
                        + (hemisphere != null ? hemisphere : "")
                        + (roiLabel != null ? roiLabel : "");
                qualityReport.addChannelQC(imgDisplayName, fr.channelName, fr.unfiltered, fr.filtered, lutColor);
            }
        }

        // ── Phase B: 3D Object Counting ──
        // Native mcib3d path is fully thread-safe (no global state) — no lock needed.
        // Legacy Counter3D fallback requires COUNTER3D_LOCK due to Prefs/WindowManager.

        ObjectsCounter3DWrapper ocWrapper = new ObjectsCounter3DWrapper();
        boolean useNative = isMcib3dAvailable();
        if (!compactLog) {
            if (useNative && verboseLogging) {
                IJ.log("  [DEBUG] Using native mcib3d object counting (thread-safe, no lock)");
            } else if (!useNative) {
                IJ.log("  [INFO] mcib3d-core not available, using legacy Counter3D (sequential)");
            }
        }

        for (int c = 0; c < n; c++) {
            ChannelFilterResult fr = filterResults[c];
            if (fr == null) {
                if (!compactLog) {
                    IJ.log("  > Channel " + (c + 1) + "/" + n + ": " + channelNames[c]);
                    IJ.log("    - ERROR: filter phase produced no result");
                }
                continue;
            }

            String channelName = fr.channelName;
            if (!compactLog) IJ.log("  > Channel " + (c + 1) + "/" + n + ": " + channelName);

            if (fr.skipped) {
                if (!compactLog) IJ.log("    - Skipped: " + fr.skipReason);
                continue;
            }

            try {
                // Register unfiltered image for downstream use (process length, coloc, etc.)
                // Native path: no WindowManager needed; Legacy path: Counter3D redirect needs it
                registerImage(channelName + "_unfiltered", fr.unfiltered, !useNative);

                // Register filtered image (process length extraction uses it)
                registerImage(channelName + "_filtered", fr.filtered, false);

                // Register skeleton images created during Phase A (stored as properties on filtered image)
                if (extractProcessLength && processChannels != null) {
                    boolean isNuclear = (c == nuclearMarkerIndex);
                    boolean isProcess = processChannels[c];
                    if (isNuclear || isProcess) {
                        String skelName = isNuclear ? "Nuclear Marker" : "Process Channel " + channelName;
                        Object skelObj = fr.filtered.getProperty("_skeleton_" + skelName);
                        if (skelObj instanceof ImagePlus) {
                            registerImage(skelName, (ImagePlus) skelObj, false);
                        }
                    }
                }

                ObjectsCounter3DWrapper.Result res;

                if (fr.labelImageSegmentation) {
                    // ── StarDist path: use pre-computed label image directly ──
                    // The label image preserves instance segmentation (touching nuclei
                    // remain separate), unlike binary → 3D Objects Counter which would
                    // merge them. We go straight to Objects3DIntPopulation from labels.

                    if (useNative) {
                        res = ocWrapper.fromLabelImage(
                                fr.labelImage,
                                fr.unfiltered,   // redirect for intensity measurements
                                true,            // wantObjectsMap
                                true             // wantMaskedImage
                        );
                    } else {
                        // Fallback: convert label image to binary and run legacy Counter3D
                        // (loses instance segmentation but keeps pipeline running)
                        COUNTER3D_LOCK.lock();
                        try {
                            ensureInWindowManager(fr.unfiltered);
                            res = ocWrapper.run(
                                    fr.filtered,         // binary mask
                                    1,                   // threshold=1 (anything > 0 is foreground)
                                    fr.minSizeVox,
                                    fr.maxSizeVox,
                                    false,
                                    true,
                                    channelName + "_unfiltered",
                                    true,
                                    true
                            );
                            if (headless) hideAllImageWindows();
                        } finally {
                            COUNTER3D_LOCK.unlock();
                        }
                    }

                    int objectCount = res.getStatistics() == null ? 0 : res.getStatistics().size();
                    if (!compactLog) {
                        IJ.log("    - [Ch " + (c + 1) + "] " + fr.segmentationSummary
                                + ": " + objectCount + " objects detected");
                        if (verboseLogging) {
                            IJ.log("    [DEBUG] Redirect target: " + channelName + "_unfiltered");
                            IJ.log("    [DEBUG] Objects map present: " + (res.getObjectsMap() != null));
                            IJ.log("    [DEBUG] Masked image present: " + (res.getMaskedImage() != null));
                            IJ.log("    [DEBUG] Counter mode: " + fr.segmentationName
                                    + " label -> " + (useNative ? "mcib3d fromLabelImage" : "legacy Counter3D (binary fallback)"));
                        }
                    }

                    // Clean up the StarDist label image (we have what we need in the Result)
                    if (fr.labelImage != null) {
                        fr.labelImage.changes = false;
                        fr.labelImage.close();
                        fr.labelImage.flush();
                    }

                } else {
                    // ── Classical path: filter + threshold + 3D object counting ──

                    if (!compactLog) {
                        IJ.log("    - Threshold: " + fr.threshold);
                        if (verboseLogging) {
                            IJ.log("    [DEBUG] Threshold token from .bin: '" + thresholdTokens[c] + "' -> resolved: " + fr.threshold);
                        }
                        IJ.log("    - Size range: " + fr.minSizeVox + "-" + (fr.maxSizeVox == Integer.MAX_VALUE ? "Infinity" : fr.maxSizeVox));
                    }

                    boolean excludeOnEdges = false;
                    long counterStart = System.currentTimeMillis();

                    if (useNative) {
                        // Native mcib3d path — thread-safe, no lock needed
                        res = ocWrapper.runNative(
                                fr.filtered,
                                (int) Math.round(fr.threshold),
                                fr.minSizeVox,
                                fr.maxSizeVox,
                                excludeOnEdges,
                                fr.unfiltered,  // redirect image passed directly (no WindowManager)
                                true,           // wantObjectsMap
                                true            // wantMaskedImage
                        );
                    } else {
                        // Legacy Counter3D fallback — requires lock
                        COUNTER3D_LOCK.lock();
                        try {
                            ensureInWindowManager(fr.unfiltered);
                            res = ocWrapper.run(
                                    fr.filtered,
                                    (int) Math.round(fr.threshold),
                                    fr.minSizeVox,
                                    fr.maxSizeVox,
                                    excludeOnEdges,
                                    true,
                                    channelName + "_unfiltered",
                                    true,
                                    true
                            );
                            if (headless) hideAllImageWindows();
                        } finally {
                            COUNTER3D_LOCK.unlock();
                        }
                    }

                    int objectCount = res.getStatistics() == null ? 0 : res.getStatistics().size();
                    long counterMs = System.currentTimeMillis() - counterStart;
                    // Always log object count with channel tag (matches StarDist format)
                    IJ.log("    3DObjectCounter [" + channelName + "]: "
                            + objectCount + " objects detected (" + counterMs + " ms)");
                    if (!compactLog && verboseLogging) {
                        IJ.log("    [DEBUG] Size range (voxels): " + fr.minSizeVox + "-" + fr.maxSizeVox);
                        IJ.log("    [DEBUG] Redirect target: " + channelName + "_unfiltered");
                        IJ.log("    [DEBUG] Objects map present: " + (res.getObjectsMap() != null));
                        IJ.log("    [DEBUG] Masked image present: " + (res.getMaskedImage() != null));
                        IJ.log("    [DEBUG] Counter mode: " + (useNative ? "native mcib3d" : "legacy Counter3D"));
                    }

                    // ROI centroid filter — remove objects whose centroids fall outside the ROI.
                    // In centroid mode: images are still uncropped; filter then crop.
                    // In crop-first mode: images already cropped; adjust ROI to cropped coords.
                    if (res.getObjectsMap() != null && (cropRoi != null || clearRoi != null)) {
                        ij.gui.Roi filterRoi;
                        if (classicalCentroidFilter) {
                            // Uncropped images — ROI in original coordinates
                            filterRoi = (clearRoi != null) ? clearRoi : cropRoi;
                        } else {
                            // Already cropped — shift ROI to cropped coordinate space
                            filterRoi = (clearRoi != null)
                                    ? (ij.gui.Roi) clearRoi.clone()
                                    : (ij.gui.Roi) cropRoi.clone();
                            if (cropRoi != null) {
                                java.awt.Rectangle cropBounds = cropRoi.getBounds();
                                filterRoi.setLocation(
                                        filterRoi.getBounds().x - cropBounds.x,
                                        filterRoi.getBounds().y - cropBounds.y);
                            }
                        }
                        int beforeFilter = objectCount;
                        int removed = filterLabelsByCentroid(res.getObjectsMap(), filterRoi);
                        int afterFilter = beforeFilter - removed;
                        IJ.log("    3DObjectCounter [" + channelName + "] ROI filter: "
                                + beforeFilter + " \u2192 " + afterFilter
                                + " objects (" + removed + " outside " + roiSetName + " ROI removed)");

                        // Centroid mode: now crop everything to ROI bounds
                        if (classicalCentroidFilter) {
                            RoiOps.removeNonRoiThreadSafe(fr.filtered, cropRoi, clearRoi);
                            RoiOps.removeNonRoiThreadSafe(fr.unfiltered, cropRoi, clearRoi);
                            RoiOps.removeNonRoiThreadSafe(res.getObjectsMap(), cropRoi, clearRoi);
                            if (res.getMaskedImage() != null) {
                                RoiOps.removeNonRoiThreadSafe(res.getMaskedImage(), cropRoi, clearRoi);
                            }
                        }

                        // Re-derive statistics from the (now-cropped) label image
                        if ((removed > 0 || classicalCentroidFilter) && useNative) {
                            res = ocWrapper.fromLabelImage(
                                    res.getObjectsMap(),
                                    fr.unfiltered,
                                    true, true);
                        }
                    }
                }

                ImagePlus objects = res.getObjectsMap();
                if (objects != null) {
                    registerImage(channelName + "_objects", objects, false);
                }

                channelHasObjects[c] = res.isFoundObjects() && objects != null;

                appendStatsToChannelTable(res.getStatistics(), channelTables.get(channelName), scnIndex, animalName, hemisphere, regionLabel, roiLabel);

                // Save masked image under the object-analysis image output folder.
                File perAnimal = new File(imageAnalysisRoot, animalName);
                //noinspection ResultOfMethodCallIgnored
                perAnimal.mkdirs();

                // Build suffix: hemisphere_region, or just one, or animal name for non-convention files
                String maskedSuffix = buildFileSuffix(hemisphere, roiLabel, animalName);
                String safeChannelName = ChannelFilenameCodec.toSafe(channelName);
                if (res.getMaskedImage() != null) {
                    AsyncImageSaver.saveAsTiffAsync(res.getMaskedImage(),
                            new File(perAnimal, safeChannelName + "_Masked" + (maskedSuffix.isEmpty() ? "" : "_" + maskedSuffix) + ".tif").getAbsolutePath());
                }

                // Save the pre-detection filtered image
                if (fr.preDetectionFiltered != null) {
                    AsyncImageSaver.saveAsTiffAsync(fr.preDetectionFiltered,
                            new File(perAnimal, safeChannelName + "_Filtered"
                                    + (maskedSuffix.isEmpty() ? "" : "_" + maskedSuffix) + ".tif").getAbsolutePath());
                }

                // Objects image saving is deferred until after colocalization.

            } catch (Throwable t) {
                IJ.log("ERROR in channel " + channelName + ": " + t);
                t.printStackTrace();
                IJ.handleException(t);
                throw t;
            }
        }

        return channelHasObjects;
    }

    /**
     * Count-once optimisation: runs filter + threshold + 3D counting on the full
     * uncropped image for every channel, returning results that can be filtered
     * per-ROI via {@link #processRoiSetFromFullCount}.
     *
     * <p>Skeleton creation is deferred to the per-ROI step so that skeletonisation
     * sees the correct (cropped) boundary conditions.
     */
    private FullImageCountData countAllChannelsFullImage(
            String directory,
            BinConfig cfg,
            ImagePlus imp,
            File outDir,
            String animalName,
            String hemisphere,
            String roiLabel
    ) {
        ImagePlus[] chans = ChannelSplitter.split(imp);
        if (chans == null || chans.length == 0) {
            IJ.log("  [shared count] ERROR: No channels found in " + imp.getTitle());
            return new FullImageCountData(new ChannelFilterResult[0],
                    new ObjectsCounter3DWrapper.Result[0], new boolean[0], 0);
        }

        int n = Math.min(cfg.numChannels(), chans.length);
        final File binDir = activeConfigurationDir(directory);

        // ── Phase A: filter + threshold on full image (no ROI, no skeletons) ──
        final ChannelFilterResult[] filterResults = new ChannelFilterResult[n];

        final String[] channelNames = new String[n];
        final String[] filterFilenames = new String[n];
        final String[] thresholdTokens = new String[n];
        final String[] sizeTokens = new String[n];
        final boolean[] isStarDist = new boolean[n];
        final boolean[] isCellpose = new boolean[n];
        final double[] sdProbThresh = new double[n];
        final double[] sdNmsThresh = new double[n];
        final double[] sdLinkingMaxDistance = new double[n];
        final double[] sdGapClosingMaxDistance = new double[n];
        final int[] sdMaxFrameGap = new int[n];
        final double[] sdAreaMin = new double[n];
        final double[] sdAreaMax = new double[n];
        final double[] sdQualityMin = new double[n];
        final double[] sdIntensityMin = new double[n];
        final String[] cpModel = new String[n];
        final double[] cpDiameter = new double[n];
        final double[] cpFlowThreshold = new double[n];
        final double[] cpCellprobThreshold = new double[n];
        final boolean[] cpUseGpu = new boolean[n];
        final int[] cpSecondChannel = new int[n];
        for (int c = 0; c < n; c++) {
            channelNames[c] = cfg.channelNames.get(c);
            filterFilenames[c] = cfg.filterMacroFilenameForChannelIndex(c);
            thresholdTokens[c] = cfg.channelThresholds.get(c);
            sizeTokens[c] = cfg.channelSizes.get(c);
            isStarDist[c] = cfg.isStarDist(c);
            isCellpose[c] = cfg.isCellpose(c);
            sdProbThresh[c] = cfg.getStarDistProbThresh(c);
            sdNmsThresh[c] = cfg.getStarDistNmsThresh(c);
            sdLinkingMaxDistance[c] = cfg.getStarDistLinkingMaxDistance(c);
            sdGapClosingMaxDistance[c] = cfg.getStarDistGapClosingMaxDistance(c);
            sdMaxFrameGap[c] = cfg.getStarDistMaxFrameGap(c);
            sdAreaMin[c] = cfg.getStarDistAreaMin(c);
            sdAreaMax[c] = cfg.getStarDistAreaMax(c);
            sdQualityMin[c] = cfg.getStarDistQualityMin(c);
            sdIntensityMin[c] = cfg.getStarDistIntensityMin(c);
            cpModel[c] = cfg.getCellposeModel(c);
            cpDiameter[c] = cfg.getCellposeDiameter(c);
            cpFlowThreshold[c] = cfg.getCellposeFlowThreshold(c);
            cpCellprobThreshold[c] = cfg.getCellposeCellprobThreshold(c);
            cpUseGpu[c] = cfg.getCellposeUseGpu(c);
            cpSecondChannel[c] = cfg.getCellposeSecondChannel(c);
        }

        // Parallel filter (same logic as run3DObjectsCounterPerChannel Phase A)
        int filterThreads = Math.min(n, 4);
        final ImagePlus[] cellposeCompanionSources = snapshotCellposeCompanionSources(chans, cpSecondChannel, channelNames);
        if (filterThreads > 1) {
            ExecutorService filterPool = Executors.newFixedThreadPool(filterThreads);
            List<Future<?>> filterFutures = new ArrayList<Future<?>>();
            for (int c = 0; c < n; c++) {
                final int ci = c;
                final ImagePlus ch = chans[c];
                filterFutures.add(filterPool.submit(new Runnable() {
                    @Override
                    public void run() {
                        // Inherit ParallelContext so nested FilterExecutor
                        // slice pools serialise rather than oversubscribing.
                        ParallelContext.enterParallel();
                        try {
                            filterResults[ci] = filterAndThresholdChannel(
                                    ci, channelNames[ci], ch, binDir, filterFilenames[ci],
                                    thresholdTokens[ci], sizeTokens[ci],
                                    false, -1, null, n,  // no skeleton creation
                                    isStarDist[ci], sdProbThresh[ci], sdNmsThresh[ci],
                                    sdLinkingMaxDistance[ci], sdGapClosingMaxDistance[ci], sdMaxFrameGap[ci],
                                    sdAreaMin[ci], sdAreaMax[ci], sdQualityMin[ci], sdIntensityMin[ci],
                                    isCellpose[ci], cpModel[ci], cpDiameter[ci], cpFlowThreshold[ci],
                                    cpCellprobThreshold[ci], cpUseGpu[ci],
                                    cpSecondChannel[ci],
                                    cpSecondChannel[ci] >= 0 && cpSecondChannel[ci] < cellposeCompanionSources.length ? cellposeCompanionSources[cpSecondChannel[ci]] : null,
                                    cpSecondChannel[ci] >= 0 && cpSecondChannel[ci] < channelNames.length ? channelNames[cpSecondChannel[ci]] : null,
                                    cpSecondChannel[ci] >= 0 && cpSecondChannel[ci] < filterFilenames.length ? filterFilenames[cpSecondChannel[ci]] : null,
                                    null, null, null);   // null ROIs = full image
                        } finally {
                            ParallelContext.exitParallel();
                        }
                    }
                }));
            }
            for (Future<?> f : filterFutures) {
                try { f.get(); } catch (Exception e) {
                    IJ.log("    WARNING: Filter thread error: " + e.getMessage());
                }
            }
            filterPool.shutdown();
        } else {
            for (int c = 0; c < n; c++) {
                filterResults[c] = filterAndThresholdChannel(
                        c, channelNames[c], chans[c], binDir, filterFilenames[c],
                        thresholdTokens[c], sizeTokens[c],
                        false, -1, null, n,
                        isStarDist[c], sdProbThresh[c], sdNmsThresh[c],
                        sdLinkingMaxDistance[c], sdGapClosingMaxDistance[c], sdMaxFrameGap[c],
                        sdAreaMin[c], sdAreaMax[c], sdQualityMin[c], sdIntensityMin[c],
                        isCellpose[c], cpModel[c], cpDiameter[c], cpFlowThreshold[c],
                        cpCellprobThreshold[c], cpUseGpu[c],
                        cpSecondChannel[c],
                        cpSecondChannel[c] >= 0 && cpSecondChannel[c] < cellposeCompanionSources.length ? cellposeCompanionSources[cpSecondChannel[c]] : null,
                        cpSecondChannel[c] >= 0 && cpSecondChannel[c] < channelNames.length ? channelNames[cpSecondChannel[c]] : null,
                        cpSecondChannel[c] >= 0 && cpSecondChannel[c] < filterFilenames.length ? filterFilenames[cpSecondChannel[c]] : null,
                        null, null, null);
            }
        }

        // ── QC capture (once, from full-image data) ──
        if (qualityReport != null && qualityReport.isEnabled()) {
            for (int c = 0; c < n; c++) {
                ChannelFilterResult fr = filterResults[c];
                if (fr == null || fr.skipped || fr.filtered == null) continue;
                String lutColor = (c < cfg.channelColors.size()) ? cfg.channelColors.get(c) : "Grays";
                String imgDisplayName = animalName + "_"
                        + (hemisphere != null ? hemisphere : "")
                        + (roiLabel != null ? roiLabel : "");
                qualityReport.addChannelQC(imgDisplayName, fr.channelName, fr.unfiltered, fr.filtered, lutColor);
            }
        }

        // ── Phase B: 3D counting on full image ──
        closeQuietly(cellposeCompanionSources);
        ObjectsCounter3DWrapper ocWrapper = new ObjectsCounter3DWrapper();
        ObjectsCounter3DWrapper.Result[] countResults = new ObjectsCounter3DWrapper.Result[n];
        boolean[] channelHasObjects = new boolean[n];

        for (int c = 0; c < n; c++) {
            ChannelFilterResult fr = filterResults[c];
            if (fr == null || fr.skipped) continue;

            String channelName = fr.channelName;
            if (!compactLog) IJ.log("  > [shared count] Channel " + (c + 1) + "/" + n + ": " + channelName);

            try {
                if (fr.labelImageSegmentation) {
                    countResults[c] = ocWrapper.fromLabelImage(
                            fr.labelImage, fr.unfiltered, true, true);
                    int objectCount = countResults[c].getStatistics() == null
                            ? 0 : countResults[c].getStatistics().size();
                    IJ.log("    3DObjectCounter [" + channelName + "]: "
                            + objectCount + " objects detected (" + fr.segmentationName + ", full image)");
                } else {
                    // Classical: threshold + native counting
                    long counterStart = System.currentTimeMillis();
                    countResults[c] = ocWrapper.runNative(
                            fr.filtered,
                            (int) Math.round(fr.threshold),
                            fr.minSizeVox, fr.maxSizeVox,
                            false,          // excludeOnEdges
                            fr.unfiltered,  // redirect
                            true, true);
                    int objectCount = countResults[c].getStatistics() == null
                            ? 0 : countResults[c].getStatistics().size();
                    long counterMs = System.currentTimeMillis() - counterStart;
                    IJ.log("    3DObjectCounter [" + channelName + "]: "
                            + objectCount + " objects detected (" + counterMs + " ms, full image)");
                }
                channelHasObjects[c] = countResults[c].isFoundObjects()
                        && countResults[c].getObjectsMap() != null;
            } catch (Throwable t) {
                IJ.log("    ERROR counting " + channelName + " on full image: " + t);
                t.printStackTrace();
            }
        }

        return new FullImageCountData(filterResults, countResults, channelHasObjects, n);
    }

    /**
     * Per-ROI assignment using pre-computed full-image counting results.
     * Clones the full-image label images, filters by centroid for this ROI,
     * crops, re-derives statistics, then runs colocalization and saves images.
     */
    private void processRoiSetFromFullCount(
            FullImageCountData fullData,
            String directory,
            BinConfig cfg,
            ImagePlus imp,
            File outDir,
            File imageAnalysisRoot,
            Map<String, ij.measure.ResultsTable> channelTables,
            int imageIndex,
            int scnIndex,
            String animalName,
            NameParts parts,
            boolean extractProcessLength,
            int nuclearMarkerIndex,
            boolean[] processChannels,
            RoiSetData roiSet) {

        int roiIdx = imageIndex * 2;
        ij.gui.Roi cropRoi = roiSet == null ? null : roiSet.cloneRoi(roiIdx);
        ij.gui.Roi clearRoi = roiSet == null ? null : roiSet.cloneRoi(roiIdx + 1);
        String roiBase = roiSet == null ? "" : roiSet.name;
        String hemisphere = parts == null ? "" : parts.hemisphere;
        String seriesRegionLabel = parts == null ? "" : parts.csvRegion();
        String roiLabel = parts == null
                ? (scnIndex > 0 && !roiBase.matches(".*\\d$") ? roiBase + scnIndex : roiBase)
                : parts.analysisRegionLabel(roiBase, scnIndex);

        if (!compactLog) {
            IJ.log("  > Assigning objects to ROI set: " + roiBase);
        }

        try {
            ObjectsCounter3DWrapper ocWrapper = new ObjectsCounter3DWrapper();
            boolean[] channelHasObjects = new boolean[fullData.numChannels];

            for (int c = 0; c < fullData.numChannels; c++) {
                ChannelFilterResult fr = fullData.filterResults[c];
                ObjectsCounter3DWrapper.Result fullRes = fullData.countResults[c];
                if (fr == null || fr.skipped || fullRes == null) continue;

                String channelName = fr.channelName;

                // Clone the full-image label map
                ImagePlus roiLabels = fullRes.getObjectsMap() != null
                        ? ImageOps.duplicateThreadSafe(fullRes.getObjectsMap()) : null;

                // Clone the unfiltered channel
                ImagePlus roiUnfiltered = fr.unfiltered != null
                        ? ImageOps.duplicateThreadSafe(fr.unfiltered) : null;

                // Clone ROIs for thread safety
                ij.gui.Roi localCropRoi = cropRoi != null ? (ij.gui.Roi) cropRoi.clone() : null;
                ij.gui.Roi localClearRoi = clearRoi != null ? (ij.gui.Roi) clearRoi.clone() : null;

                // Centroid filter — remove objects outside this ROI
                if (roiLabels != null && (localCropRoi != null || localClearRoi != null)) {
                    ij.gui.Roi filterRoi = (localClearRoi != null) ? localClearRoi : localCropRoi;
                    int beforeFilter = fullData.channelHasObjects[c]
                            ? (fullRes.getStatistics() != null ? fullRes.getStatistics().size() : 0)
                            : 0;
                    int removed = filterLabelsByCentroid(roiLabels, filterRoi);
                    int afterFilter = beforeFilter - removed;
                    IJ.log("    3DObjectCounter [" + channelName + "] ROI filter: "
                            + beforeFilter + " \u2192 " + afterFilter
                            + " objects (" + removed + " outside " + roiBase + " ROI removed)");
                }

                // Crop label image + unfiltered to ROI bounds
                RoiOps.removeNonRoiThreadSafe(roiLabels, localCropRoi, localClearRoi);
                RoiOps.removeNonRoiThreadSafe(roiUnfiltered, localCropRoi, localClearRoi);

                // Re-derive statistics from the cropped, centroid-filtered label image
                ObjectsCounter3DWrapper.Result roiRes = (roiLabels != null && roiUnfiltered != null)
                        ? ocWrapper.fromLabelImage(roiLabels, roiUnfiltered, true, true)
                        : new ObjectsCounter3DWrapper.Result(null, roiLabels, null, false);

                // Register images for downstream use (coloc, process length, save)
                registerImage(channelName + "_unfiltered", roiUnfiltered, false);

                // Clone + crop filtered image for this ROI
                ImagePlus roiFiltered = fr.filtered != null ? ImageOps.duplicateThreadSafe(fr.filtered) : null;
                RoiOps.removeNonRoiThreadSafe(roiFiltered, localCropRoi, localClearRoi);
                registerImage(channelName + "_filtered", roiFiltered, false);

                ImagePlus roiObjMap = roiRes.getObjectsMap();
                if (roiObjMap != null) {
                    registerImage(channelName + "_objects", roiObjMap, false);
                }

                channelHasObjects[c] = roiRes.isFoundObjects() && roiObjMap != null;

                // Append per-ROI stats to channel table
                appendStatsToChannelTable(roiRes.getStatistics(), channelTables.get(channelName),
                        scnIndex, animalName, hemisphere, seriesRegionLabel, roiLabel);

                // Save masked image per-ROI
                File perAnimal = new File(imageAnalysisRoot, animalName);
                //noinspection ResultOfMethodCallIgnored
                perAnimal.mkdirs();
                String maskedSuffix = buildFileSuffix(hemisphere, roiLabel, animalName);
                String safeChannelName = ChannelFilenameCodec.toSafe(channelName);
                if (roiRes.getMaskedImage() != null) {
                    AsyncImageSaver.saveAsTiffAsync(roiRes.getMaskedImage(),
                            new File(perAnimal, safeChannelName + "_Masked"
                                    + (maskedSuffix.isEmpty() ? "" : "_" + maskedSuffix) + ".tif").getAbsolutePath());
                }

                // Save pre-detection filtered image per-ROI (clone + crop from full)
                if (fr.preDetectionFiltered != null) {
                    ImagePlus roiPreDetection = ImageOps.duplicateThreadSafe(fr.preDetectionFiltered);
                    RoiOps.removeNonRoiThreadSafe(roiPreDetection, localCropRoi, localClearRoi);
                    AsyncImageSaver.saveAsTiffAsync(roiPreDetection,
                            new File(perAnimal, safeChannelName + "_Filtered"
                                    + (maskedSuffix.isEmpty() ? "" : "_" + maskedSuffix) + ".tif").getAbsolutePath());
                }

                // Create skeletons per-ROI (from cropped filtered image, matching current behaviour)
                if (extractProcessLength && processChannels != null && roiFiltered != null) {
                    boolean isNuclear = (c == nuclearMarkerIndex);
                    boolean isProcess = processChannels[c];
                    if (isNuclear || isProcess) {
                        String skelName = isNuclear ? "Nuclear Marker" : "Process Channel " + channelName;
                        ImagePlus skelDup = ImageOps.duplicateThreadSafe(roiFiltered);
                        skelDup.setTitle(skelName);

                        String thrToken = cfg.channelThresholds.get(c);
                        Double skelThr = tryParseDouble(thrToken);
                        if (skelThr == null && "default".equalsIgnoreCase(thrToken)) {
                            ij.ImageStack binStack = skelDup.getStack();
                            for (int s = 1; s <= binStack.getSize(); s++) {
                                ij.process.ImageProcessor ip = binStack.getProcessor(s);
                                ip.setAutoThreshold("Default dark");
                                double lower = ip.getMinThreshold();
                                for (int p = 0; p < ip.getWidth() * ip.getHeight(); p++) {
                                    ip.set(p, ip.get(p) >= (int) lower ? 255 : 0);
                                }
                            }
                            skelDup.updateAndDraw();
                        } else if (skelThr != null) {
                            ij.ImageStack maskStack = skelDup.getStack();
                            int thrInt = (int) Math.round(skelThr);
                            for (int s = 1; s <= maskStack.getSize(); s++) {
                                ij.process.ImageProcessor ip = maskStack.getProcessor(s);
                                for (int p = 0; p < ip.getWidth() * ip.getHeight(); p++) {
                                    ip.set(p, ip.get(p) >= thrInt ? 255 : 0);
                                }
                            }
                            skelDup.updateAndDraw();
                            ij.plugin.filter.Binary binaryPlugin = new ij.plugin.filter.Binary();
                            binaryPlugin.setup("skeletonize", skelDup);
                            ij.ImageStack skelStack = skelDup.getStack();
                            for (int s = 1; s <= skelStack.getSize(); s++) {
                                binaryPlugin.run(skelStack.getProcessor(s));
                            }
                            skelDup.updateAndDraw();
                        }
                        registerImage(skelName, skelDup, false);
                    }
                }
            }

            // Colocalization (using per-ROI label images from registry)
            IJ.log("  > Colocalization");
            if (doVolumetric) {
                appendColocColumns(cfg, channelHasObjects, channelTables, scnIndex, animalName,
                        hemisphere, seriesRegionLabel, roiLabel);
            }
            if (doCpc) {
                appendCpcColocColumns(cfg, channelHasObjects, channelTables, scnIndex, animalName,
                        hemisphere, seriesRegionLabel, roiLabel);
            }

            // Process length extraction
            if (extractProcessLength && processChannels != null) {
                processLengthExtractionWithTables(cfg, channelTables, imp, scnIndex, animalName,
                        hemisphere, seriesRegionLabel, roiLabel, processChannels, nuclearMarkerIndex);
            }

            // Save objects images
            saveObjectsImages(cfg, imageAnalysisRoot, animalName, hemisphere, roiLabel);
        } finally {
            clearRegistry();
        }
    }

    /** Close all images held in FullImageCountData after all ROI sets have been processed. */
    private static void cleanupFullImageData(FullImageCountData data) {
        if (data == null) return;
        for (int c = 0; c < data.numChannels; c++) {
            // Close filter results (unfiltered, filtered, preDetection, labelImage)
            ChannelFilterResult fr = data.filterResults[c];
            if (fr != null) {
                closeQuietly(fr.unfiltered);
                closeQuietly(fr.filtered);
                closeQuietly(fr.preDetectionFiltered);
                closeQuietly(fr.labelImage);
            }
            // Close count results (objectsMap, maskedImage)
            ObjectsCounter3DWrapper.Result res = data.countResults[c];
            if (res != null) {
                closeQuietly(res.getObjectsMap());
                closeQuietly(res.getMaskedImage());
            }
        }
    }

    private static void closeQuietly(ImagePlus imp) {
        if (imp == null) return;
        imp.changes = false;
        imp.close();
        imp.flush();
    }

    /**
     * Phase A helper: filter + threshold a single channel. Thread-safe — does not touch
     * WindowManager, RoiManager, or any shared mutable state. Also creates skeleton
     * duplicates for process length extraction if needed.
     *
     * <p>For StarDist channels, the per-channel filter macro is still applied, but the
     * classical thresholding and 3D Objects Counter detection steps are skipped. StarDist
     * 3D then runs on the filtered stack and produces a label image directly. A binary
     * mask is derived from the label image for compatibility with the rest of the pipeline.
     */
    private ChannelFilterResult filterAndThresholdChannel(
            int c, String channelName, ImagePlus ch,
            File binDir, String filterFilename,
            String thrToken, String sizeToken,
            boolean extractProcessLength, int nuclearMarkerIndex, boolean[] processChannels,
            int totalChannels,
            boolean isStarDist, double starDistProbThresh, double starDistNmsThresh,
            double starDistLinkingMaxDistance, double starDistGapClosingMaxDistance, int starDistMaxFrameGap,
            double starDistAreaMin, double starDistAreaMax,
            double starDistQualityMin, double starDistIntensityMin,
            boolean isCellpose, String cellposeModel, double cellposeDiameter,
            double cellposeFlowThreshold, double cellposeCellprobThreshold, boolean cellposeUseGpu,
            int cellposeSecondChannelIndex, ImagePlus cellposeSecondChannel,
            String cellposeSecondChannelName, String cellposeSecondChannelFilterFilename,
            ij.gui.Roi cropRoi, ij.gui.Roi clearRoi, String roiSetName) {

        // Clone ROIs for thread safety — multiple filter threads share the same
        // cropRoi/clearRoi from the caller. Roi.contains() caches a mask internally,
        // and concurrent calls corrupt this cache, breaking ROI-based object filtering.
        if (cropRoi != null) cropRoi = (ij.gui.Roi) cropRoi.clone();
        if (clearRoi != null) clearRoi = (ij.gui.Roi) clearRoi.clone();

        try {
            ch.setTitle(channelName + "_unfiltered");

            // ── StarDist 3D path: apply filter first, then run StarDist ──
            if (isStarDist) {
                // 1. Apply filter (same as classical path)
                ImagePlus filtered = ImageOps.duplicateThreadSafe(ch);
                filtered.setTitle(channelName + "_stardist_input");
                File filterMacro = new File(binDir, filterFilename);
                if (filterMacro.exists()) {
                    try {
                        FilterExecutor.runIjmFileThreadSafe(filtered, filterMacro);
                        if (!compactLog) IJ.log("    - [Ch " + (c + 1) + "] Filter applied before StarDist: " + filterFilename);
                    } catch (Exception e) {
                        IJ.log("    - [Ch " + (c + 1) + "] Filter error before StarDist: " + e.getMessage());
                    }
                } else {
                    String defaultMacro = NamedFilterLoader.loadDefaultFilter();
                    if (defaultMacro != null) {
                        FilterExecutor.runThreadSafe(filtered, defaultMacro);
                        if (!compactLog) IJ.log("    - [Ch " + (c + 1) + "] Default filter applied before StarDist (no " + filterFilename + " found)");
                    }
                }

                // REGRESSION GUARD: StarDist MUST run BEFORE ROI crop, not after.
                // If you move the crop before StarDist, the zeroed-out region creates a hard
                // black edge that StarDist treats as a real boundary, producing edge artifacts.
                // The correct order: StarDist on full image → filterLabelsByCentroid → crop.
                IJ.log("    - [Ch " + (c + 1) + "] Pre-StarDist: filtered stackSize=" + filtered.getStackSize()
                        + " C=" + filtered.getNChannels() + " Z=" + filtered.getNSlices()
                        + " T=" + filtered.getNFrames() + " (" + filtered.getWidth() + "x" + filtered.getHeight() + ")"
                        + " | input ch stackSize=" + ch.getStackSize());
                // StarDist manages its own narrow lock + GPU semaphore internally
                // (see GpuConcurrency / StarDist3DRunner). No outer lock needed.
                ImagePlus labelImage = StarDist3DRunner.run(filtered, starDistProbThresh, starDistNmsThresh, channelName,
                        starDistLinkingMaxDistance, starDistGapClosingMaxDistance, starDistMaxFrameGap,
                        starDistAreaMin, starDistAreaMax, starDistQualityMin, starDistIntensityMin);

                // 3. Filter labels by centroid — remove objects whose centroids
                // fall outside the tissue ROI (before cropping to bounding box).
                if (labelImage != null && (cropRoi != null || clearRoi != null)) {
                    ij.gui.Roi filterRoi = (clearRoi != null) ? clearRoi : cropRoi;
                    int beforeFilter = StarDist3DRunner.countLabels(labelImage);
                    int removed = filterLabelsByCentroid(labelImage, filterRoi);
                    int afterFilter = beforeFilter - removed;
                    IJ.log("    StarDist [" + channelName + "] ROI filter: " + beforeFilter
                            + " → " + afterFilter + " objects (" + removed + " outside " + roiSetName + " ROI removed)");
                }

                // 4. Crop everything to ROI bounds
                RoiOps.removeNonRoiThreadSafe(filtered, cropRoi, clearRoi);
                RoiOps.removeNonRoiThreadSafe(ch, cropRoi, clearRoi);
                if (labelImage != null) {
                    RoiOps.removeNonRoiThreadSafe(labelImage, cropRoi, clearRoi);
                }

                // Snapshot the filtered+cropped image for saving
                ImagePlus preDetection = ImageOps.duplicateThreadSafe(filtered);

                // Clean up the filtered input
                filtered.changes = false;
                filtered.close();
                filtered.flush();

                if (labelImage == null) {
                    String failureReason = "StarDist 3D segmentation failed";
                    IJ.log("    - [Ch " + (c + 1) + "] WARNING: " + failureReason);
                    return new ChannelFilterResult(c, channelName, ch, null,
                            null, 0, 0, true, failureReason);
                }

                // Copy calibration from the original channel
                if (ch.getCalibration() != null) {
                    labelImage.setCalibration(ch.getCalibration().copy());
                }

                // Convert label image to binary mask for pipeline compatibility
                // (all pixels > 0 become 255, else 0)
                ImagePlus binaryMask = ImageOps.duplicateThreadSafe(labelImage);
                binaryMask.setTitle(channelName + "_filtered");
                ij.ImageStack maskStack = binaryMask.getStack();
                for (int s = 1; s <= maskStack.getSize(); s++) {
                    ij.process.ImageProcessor ip = maskStack.getProcessor(s);
                    for (int p = 0; p < ip.getPixelCount(); p++) {
                        ip.set(p, ip.get(p) > 0 ? 255 : 0);
                    }
                }

                int nObjects = StarDist3DRunner.countLabels(labelImage);
                if (!compactLog) {
                    IJ.log("    - [Ch " + (c + 1) + "] StarDist 3D segmentation (probThresh="
                            + starDistProbThresh + ", nmsThresh=" + starDistNmsThresh
                            + "): " + nObjects + " objects detected");
                }

                // Particle size — still parsed for any downstream use (e.g. process length)
                String[] sizeParts = sizeToken.split("-");
                String minSize = sizeParts.length > 0 ? sizeParts[0] : "100";
                String maxSize = sizeParts.length > 1 ? sizeParts[1] : "Infinity";
                int minSizeVox = ObjectsCounter3DWrapper.parseMinSizeVoxels(minSize, 100);
                int maxSizeVox = ObjectsCounter3DWrapper.parseMaxSizeVoxels(maxSize, binaryMask);

                return new ChannelFilterResult(c, channelName, ch, binaryMask,
                        null, minSizeVox, maxSizeVox, false, null,
                        true, "StarDist 3D", labelImage,
                        "StarDist 3D (probThresh=" + starDistProbThresh + ", nmsThresh=" + starDistNmsThresh + ")",
                        preDetection);
            }

            // ── Classical path: filter + threshold ──
            if (isCellpose) {
                ImagePlus filtered = ImageOps.duplicateThreadSafe(ch);
                filtered.setTitle(channelName + "_cellpose_input");
                File filterMacro = new File(binDir, filterFilename);
                if (filterMacro.exists()) {
                    try {
                        FilterExecutor.runIjmFileThreadSafe(filtered, filterMacro);
                        if (!compactLog) IJ.log("    - [Ch " + (c + 1) + "] Filter applied before Cellpose: " + filterFilename);
                    } catch (Exception e) {
                        IJ.log("    - [Ch " + (c + 1) + "] Filter error before Cellpose: " + e.getMessage());
                    }
                } else {
                    String defaultMacro = NamedFilterLoader.loadDefaultFilter();
                    if (defaultMacro != null) {
                        FilterExecutor.runThreadSafe(filtered, defaultMacro);
                        if (!compactLog) IJ.log("    - [Ch " + (c + 1) + "] Default filter applied before Cellpose (no " + filterFilename + " found)");
                    }
                }

                ImagePlus filteredCompanion = prepareCellposeSecondChannelInput(
                        c, channelName, ch, binDir,
                        cellposeSecondChannelIndex, cellposeSecondChannel,
                        cellposeSecondChannelName, cellposeSecondChannelFilterFilename);

                // Cellpose holds the shared GPU semaphore internally around its Python
                // subprocess call (see GpuConcurrency). It does not touch WindowManager,
                // so no WM lock is required here.
                ImagePlus labelImage = Cellpose3DRunner.run(filtered, filteredCompanion, cellposeModel, cellposeDiameter,
                        cellposeFlowThreshold, cellposeCellprobThreshold, cellposeUseGpu, channelName);

                if (labelImage != null && (cropRoi != null || clearRoi != null)) {
                    ij.gui.Roi filterRoi = (clearRoi != null) ? clearRoi : cropRoi;
                    int beforeFilter = Cellpose3DRunner.countLabels(labelImage);
                    int removed = filterLabelsByCentroid(labelImage, filterRoi);
                    int afterFilter = beforeFilter - removed;
                    IJ.log("    Cellpose [" + channelName + "] ROI filter: " + beforeFilter
                            + " -> " + afterFilter + " objects (" + removed + " outside " + roiSetName + " ROI removed)");
                }

                RoiOps.removeNonRoiThreadSafe(filtered, cropRoi, clearRoi);
                RoiOps.removeNonRoiThreadSafe(ch, cropRoi, clearRoi);
                if (labelImage != null) {
                    RoiOps.removeNonRoiThreadSafe(labelImage, cropRoi, clearRoi);
                }

                ImagePlus preDetection = ImageOps.duplicateThreadSafe(filtered);

                filtered.changes = false;
                filtered.close();
                filtered.flush();
                if (filteredCompanion != null) {
                    filteredCompanion.changes = false;
                    filteredCompanion.close();
                    filteredCompanion.flush();
                }

                if (labelImage == null) {
                    String failureReason = "Cellpose segmentation failed";
                    IJ.log("    - [Ch " + (c + 1) + "] WARNING: " + failureReason);
                    return new ChannelFilterResult(c, channelName, ch, null,
                            null, 0, 0, true, failureReason);
                }

                if (ch.getCalibration() != null) {
                    labelImage.setCalibration(ch.getCalibration().copy());
                }

                ImagePlus binaryMask = ImageOps.duplicateThreadSafe(labelImage);
                binaryMask.setTitle(channelName + "_filtered");
                ij.ImageStack maskStack = binaryMask.getStack();
                for (int s = 1; s <= maskStack.getSize(); s++) {
                    ij.process.ImageProcessor ip = maskStack.getProcessor(s);
                    for (int p = 0; p < ip.getPixelCount(); p++) {
                        ip.set(p, ip.get(p) > 0 ? 255 : 0);
                    }
                }

                int nObjects = Cellpose3DRunner.countLabels(labelImage);
                if (!compactLog) {
                    IJ.log("    - [Ch " + (c + 1) + "] Cellpose segmentation (model="
                            + cellposeModel + ", diameter=" + cellposeDiameter
                            + ", flowThreshold=" + cellposeFlowThreshold
                            + ", cellprobThreshold=" + cellposeCellprobThreshold
                            + ", useGpu=" + cellposeUseGpu
                            + ", companionChannel="
                            + (filteredCompanion != null ? cellposeSecondChannelName : "None")
                            + "): " + nObjects + " objects detected");
                }

                String[] sizeParts = sizeToken.split("-");
                String minSize = sizeParts.length > 0 ? sizeParts[0] : "100";
                String maxSize = sizeParts.length > 1 ? sizeParts[1] : "Infinity";
                int minSizeVox = ObjectsCounter3DWrapper.parseMinSizeVoxels(minSize, 100);
                int maxSizeVox = ObjectsCounter3DWrapper.parseMaxSizeVoxels(maxSize, binaryMask);

                return new ChannelFilterResult(c, channelName, ch, binaryMask,
                        null, minSizeVox, maxSizeVox, false, null,
                        true, "Cellpose", labelImage,
                        "Cellpose (model=" + cellposeModel + ", diameter=" + cellposeDiameter
                                + ", flowThreshold=" + cellposeFlowThreshold
                                + ", cellprobThreshold=" + cellposeCellprobThreshold
                                + ", useGpu=" + cellposeUseGpu
                                + ", companionChannel="
                                + (filteredCompanion != null ? cellposeSecondChannelName : "None") + ")",
                        preDetection);
            }

            ImagePlus filtered = ImageOps.duplicateThreadSafe(ch);
            filtered.setTitle(channelName + "_filtered");

            // Apply per-channel filter macro from the active configuration folder or fallback to bundled default.
            File filterMacro = new File(binDir, filterFilename);
            if (filterMacro.exists()) {
                try {
                    FilterExecutor.runIjmFileThreadSafe(filtered, filterMacro);
                    if (!compactLog) IJ.log("    - [Ch " + (c + 1) + "] Filter applied: " + filterFilename);
                } catch (Exception e) {
                    IJ.log("    - [Ch " + (c + 1) + "] Filter error (thread-safe): " + e.getMessage()); // always log errors
                    // Cannot fall back to IJ.runMacroFile in parallel — it requires macro thread
                    // The thread-safe path should handle all supported filter commands
                }
            } else {
                String defaultMacro = NamedFilterLoader.loadDefaultFilter();
                if (defaultMacro != null) {
                    FilterExecutor.runThreadSafe(filtered, defaultMacro);
                    if (!compactLog) IJ.log("    - [Ch " + (c + 1) + "] Filter applied: bundled default filter (no " + filterFilename + " found)");
                } else {
                    IJ.log("    - [Ch " + (c + 1) + "] WARNING: No filter applied (missing .bin filter and bundled default)"); // always log warnings
                }
            }

            // Crop to ROI (after filter, before threshold) — unless centroid mode
            // defers cropping until after counting in Phase B.
            ImagePlus croppedForSkeleton = null;
            if (classicalCentroidFilter) {
                // Keep filtered/ch uncropped for full-image counting in Phase B.
                // Create a separate cropped copy for skeleton + preDetection.
                if (cropRoi != null || clearRoi != null) {
                    croppedForSkeleton = ImageOps.duplicateThreadSafe(filtered);
                    RoiOps.removeNonRoiThreadSafe(croppedForSkeleton, cropRoi, clearRoi);
                }
            } else {
                RoiOps.removeNonRoiThreadSafe(filtered, cropRoi, clearRoi);
                RoiOps.removeNonRoiThreadSafe(ch, cropRoi, clearRoi);
            }

            // Snapshot the filtered image (cropped region) before thresholding
            ImagePlus preDetection = ImageOps.duplicateThreadSafe(croppedForSkeleton != null ? croppedForSkeleton : filtered);

            // Create skeleton duplicates for process length extraction (before thresholding the main image)
            if (extractProcessLength && processChannels != null) {
                boolean isNuclear = (c == nuclearMarkerIndex);
                boolean isProcess = processChannels[c];
                if (isNuclear || isProcess) {
                    String skelName = isNuclear ? "Nuclear Marker" : "Process Channel " + channelName;
                    ImagePlus skelSource = (croppedForSkeleton != null) ? croppedForSkeleton : filtered;
                    ImagePlus skelDup = ImageOps.duplicateThreadSafe(skelSource);
                    skelDup.setTitle(skelName);

                    Double skelThr = tryParseDouble(thrToken);
                    if (skelThr == null && "default".equalsIgnoreCase(thrToken)) {
                        // Thread-safe make binary (replaces IJ.run "Make Binary")
                        ij.ImageStack binStack = skelDup.getStack();
                        for (int s = 1; s <= binStack.getSize(); s++) {
                            ij.process.ImageProcessor ip = binStack.getProcessor(s);
                            ip.setAutoThreshold("Default dark");
                            double lower = ip.getMinThreshold();
                            for (int p = 0; p < ip.getWidth() * ip.getHeight(); p++) {
                                ip.set(p, ip.get(p) >= (int) lower ? 255 : 0);
                            }
                        }
                        skelDup.updateAndDraw();
                    } else if (skelThr != null) {
                        // Thread-safe threshold + convert to mask
                        ij.ImageStack maskStack = skelDup.getStack();
                        int thrInt = (int) Math.round(skelThr);
                        for (int s = 1; s <= maskStack.getSize(); s++) {
                            ij.process.ImageProcessor ip = maskStack.getProcessor(s);
                            for (int p = 0; p < ip.getWidth() * ip.getHeight(); p++) {
                                ip.set(p, ip.get(p) >= thrInt ? 255 : 0);
                            }
                        }
                        skelDup.updateAndDraw();
                        // Thread-safe skeletonize (replaces IJ.run "Skeletonize")
                        ij.plugin.filter.Binary binaryPlugin = new ij.plugin.filter.Binary();
                        binaryPlugin.setup("skeletonize", skelDup);
                        ij.ImageStack skelStack = skelDup.getStack();
                        for (int s = 1; s <= skelStack.getSize(); s++) {
                            binaryPlugin.run(skelStack.getProcessor(s));
                        }
                        skelDup.updateAndDraw();
                    }
                    // Skeleton images will be registered in Phase B (registerImage is not thread-safe)
                    // Store on the filtered image's property for retrieval
                    filtered.setProperty("_skeleton_" + skelName, skelDup);
                    if (!compactLog) {
                        if (isNuclear) {
                            IJ.log("    - [Ch " + (c + 1) + "] Nuclear Marker skeleton created from " + channelName);
                        } else {
                            IJ.log("    - [Ch " + (c + 1) + "] Process skeleton created for " + channelName);
                        }
                    }
                }
            }

            // Threshold
            Double threshold = tryParseDouble(thrToken);
            if (threshold == null) {
                if ("default".equalsIgnoreCase(thrToken)) {
                    threshold = ThresholdOps.defaultDarkThresholdAtSlice6(filtered);
                } else {
                    return new ChannelFilterResult(c, channelName, ch, filtered,
                            null, 0, 0, true, "unrecognised threshold '" + thrToken + "'");
                }
            }

            // Particle Size (n Voxels)
            String[] sizeParts = sizeToken.split("-");
            String minSize = sizeParts.length > 0 ? sizeParts[0] : "100";
            String maxSize = sizeParts.length > 1 ? sizeParts[1] : "Infinity";

            int minSizeVox = ObjectsCounter3DWrapper.parseMinSizeVoxels(minSize, 100);
            int maxSizeVox = ObjectsCounter3DWrapper.parseMaxSizeVoxels(maxSize, filtered);

            // Clean up the temporary cropped copy (skeleton/preDetection already duplicated from it)
            if (croppedForSkeleton != null) {
                croppedForSkeleton.changes = false;
                croppedForSkeleton.close();
                croppedForSkeleton.flush();
            }

            return new ChannelFilterResult(c, channelName, ch, filtered,
                    threshold, minSizeVox, maxSizeVox, false, null,
                    false, "", null, "", preDetection);

        } catch (Throwable t) {
            String errMsg = t.getClass().getName() + ": " + t.getMessage();
            IJ.log("    - [Ch " + (c + 1) + "] ERROR during filter phase: " + errMsg);
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            for (String line : sw.toString().split("\\r?\\n")) {
                IJ.log("      " + line);
            }
            return new ChannelFilterResult(c, channelName, ch, null,
                    null, 0, 0, true, "filter error: " + errMsg);
        }
    }


    private void appendStatsToChannelTable(ij.measure.ResultsTable rt, ij.measure.ResultsTable channelTable,
                                           int scnIndex, String animalName, String hemisphere, String region, String roiLabel) {
        if (channelTable == null) return;

        // Macro behavior: if no objects, create a 1-row table filled with zeros and SCN/Animal Name.
        if (rt == null || rt.size() == 0) {
            channelTable.incrementCounter();
            int destRow = channelTable.size() - 1;
            channelTable.setValue("Region", destRow, region == null ? "" : region);
            channelTable.setValue("Hemisphere", destRow, hemisphere == null ? "" : hemisphere);
            channelTable.setValue("SCN", destRow, scnIndex);
            channelTable.setValue("ROI", destRow, roiLabel == null ? "" : roiLabel);
            channelTable.setValue("Animal Name", destRow, animalName);
            channelTable.setValue("Volume (micron^3)", destRow, 0);
            channelTable.setValue("Surface (micron^2)", destRow, 0);
            channelTable.setValue("IntDen", destRow, 0);
            channelTable.setValue("Mean", destRow, 0);
            channelTable.setValue("XM", destRow, 0);
            channelTable.setValue("YM", destRow, 0);
            channelTable.setValue("ZM", destRow, 0);
            return;
        }

        // Ensure metadata columns exist (needed for later matching when filling coloc columns)
        // NOTE: ResultsTable is sparse; setting a string value creates the column.
        ensureStringColumn(channelTable, "Hemisphere");
        ensureStringColumn(channelTable, "Region");
        ensureStringColumn(channelTable, "ROI");

        // Copy each row into channelTable, and add macro-style metadata columns
        int rows = rt.size();
        String[] headings = rt.getHeadings();
        for (int r = 0; r < rows; r++) {
            channelTable.incrementCounter();
            int destRow = channelTable.size() - 1;

            channelTable.setValue("Region", destRow, region == null ? "" : region);
            channelTable.setValue("Hemisphere", destRow, hemisphere == null ? "" : hemisphere);
            channelTable.setValue("SCN", destRow, scnIndex);
            channelTable.setValue("ROI", destRow, roiLabel == null ? "" : roiLabel);
            channelTable.setValue("Animal Name", destRow, animalName);

            for (String h : headings) {
                if (h == null) continue;
                if (h.equals("SCN") || h.equals("ROI") || h.equals("Animal Name")
                        || h.equals("Hemisphere") || h.equals("Region")) continue;
                try {
                    double v = rt.getValue(h, r);
                    channelTable.setValue(h, destRow, v);
                } catch (Exception ignored) {
                }
            }
        }

        // No reset needed: rt is passed in (typically a copy made by the wrapper).
    }


    private void saveProducedImagesIfPresent(File outDir, String channelName, String hemisphere, String region) {
        int[] ids = WindowManager.getIDList();
        if (ids == null) return;

        String hemiRegion = buildFileSuffix(hemisphere, region, null);

        for (int id : ids) {
            ImagePlus img = WindowManager.getImage(id);
            if (img == null) continue;
            String title = img.getTitle();
            if (title == null) continue;

            String t = title.toLowerCase(Locale.ROOT);

            // Macro saves:
            //  - <channel>_Masked_<hemi><region>.tif
            //  - later: <channel>_objects.tif
            if (t.contains("masked image")) {
                AsyncImageSaver.saveAsTiffAsync(img, new File(outDir, ChannelFilenameCodec.toSafe(channelName) + "_Masked_" + hemiRegion + ".tif").getAbsolutePath());
            }
            // Macro does not save the objects map here (it saves <channel>_objects.tif later).

        }
    }


    private void appendColocColumns(
            BinConfig cfg,
            boolean[] channelHasObjects,
            Map<String, ij.measure.ResultsTable> channelTables,
            int scnIndex,
            String animalName,
            String hemisphere,
            String region,
            String roiLabel
    ) {
        // Bidirectional colocalization: run 3D MultiColoc once per unordered pair (A,B),
        // then write P1 into A's table and P2 into B's table.

        int n = Math.min(cfg.numChannels(), channelHasObjects != null ? channelHasObjects.length : 0);
        if (n == 0) return;

        // Per-channel log summaries
        List<StringBuilder> logEntries = new ArrayList<StringBuilder>();
        List<Boolean> logFirst = new ArrayList<Boolean>();
        for (int i = 0; i < n; i++) {
            logEntries.add(new StringBuilder());
            logFirst.add(Boolean.TRUE);
        }

        // Zero-fill all coloc columns for channels with no objects before running pairs
        for (int a = 0; a < n; a++) {
            ij.measure.ResultsTable aTable = channelTables.get(cfg.channelNames.get(a));
            boolean aHas = channelHasObjects[a]
                    && getRegisteredImage(cfg.channelNames.get(a) + "_objects") != null
                    && aTable != null;
            for (int b = 0; b < n; b++) {
                if (b == a) continue;
                if (!aHas) {
                    if (aTable != null) {
                        setColocZerosForThisImage(aTable, colocPercentCol(cfg.channelNames.get(b)),
                                scnIndex, animalName, hemisphere, region, roiLabel);
                        setColocZerosForThisImage(aTable, volColocCol(cfg.channelNames.get(a), cfg.channelNames.get(b)),
                                scnIndex, animalName, hemisphere, region, roiLabel);
                    }
                }
            }
        }

        // Run 3D MultiColoc once per unique pair
        for (int a = 0; a < n; a++) {
            String aChannel = cfg.channelNames.get(a);
            String aObjects = aChannel + "_objects";
            ImagePlus aObjImg = getRegisteredImage(aObjects);
            boolean aHas = channelHasObjects[a] && aObjImg != null;
            ij.measure.ResultsTable aTable = channelTables.get(aChannel);

            for (int b = a + 1; b < n; b++) {
                String bChannel = cfg.channelNames.get(b);
                String bObjects = bChannel + "_objects";
                ImagePlus bObjImg = getRegisteredImage(bObjects);
                boolean bHas = channelHasObjects[b] && bObjImg != null;
                ij.measure.ResultsTable bTable = channelTables.get(bChannel);

                String overlapColAB = colocPercentCol(bChannel);
                String overlapColBA = colocPercentCol(aChannel);
                String colocColAB = volColocCol(aChannel, bChannel);
                String colocColBA = volColocCol(bChannel, aChannel);

                if (!aHas || !bHas) {
                    // Zero-fill both directions
                    if (aTable != null) {
                        writeColocMetricsForThisImage(aTable, aChannel, bChannel, null, true,
                                scnIndex, animalName, hemisphere, region, roiLabel);
                        setColocZerosForThisImage(aTable, overlapColAB, scnIndex, animalName, hemisphere, region, roiLabel);
                        setColocZerosForThisImage(aTable, colocColAB, scnIndex, animalName, hemisphere, region, roiLabel);
                    }
                    if (bTable != null) {
                        writeColocMetricsForThisImage(bTable, bChannel, aChannel, null, false,
                                scnIndex, animalName, hemisphere, region, roiLabel);
                        setColocZerosForThisImage(bTable, overlapColBA, scnIndex, animalName, hemisphere, region, roiLabel);
                        setColocZerosForThisImage(bTable, colocColBA, scnIndex, animalName, hemisphere, region, roiLabel);
                    }
                    String emptyChannel = !aHas
                            ? (!bHas ? aChannel + "+" + bChannel : aChannel)
                            : bChannel;
                    appendColocLogEntry(logEntries, logFirst, a, bChannel, "no objects in " + emptyChannel);
                    appendColocLogEntry(logEntries, logFirst, b, aChannel, "no objects in " + emptyChannel);
                    continue;
                }

                ColocalizationMetrics.Result metrics = (aTable != null || bTable != null)
                        ? computeIntensityColocMetrics(aChannel, bChannel)
                        : null;
                if (aTable != null) {
                    writeColocMetricsForThisImage(aTable, aChannel, bChannel, metrics, true,
                            scnIndex, animalName, hemisphere, region, roiLabel);
                }
                if (bTable != null) {
                    writeColocMetricsForThisImage(bTable, bChannel, aChannel, metrics, false,
                            scnIndex, animalName, hemisphere, region, roiLabel);
                }
                if (metrics != null && metrics.randomizationSkipped) {
                    IJ.log("    " + metrics.note + " (" + aChannel + " vs " + bChannel + ")");
                }

                try {
                    // Native mcib3d colocalization — no WindowManager or GUI dependency
                    float[] p1 = null;
                    float[] p2 = null;

                    try {
                        // Create populations to get the canonical iteration order
                        // (matches the order used by buildNativeStatisticsTable).
                        mcib3d.geom2.Objects3DIntPopulation popA =
                                new mcib3d.geom2.Objects3DIntPopulation(mcib3d.image3d.ImageHandler.wrap(aObjImg));
                        mcib3d.geom2.Objects3DIntPopulation popB =
                                new mcib3d.geom2.Objects3DIntPopulation(mcib3d.image3d.ImageHandler.wrap(bObjImg));

                        // Compute overlap directly from label images (pixel-by-pixel).
                        // The mcib3d MeasurePopulationColocalisation API does not return
                        // actual overlap voxels — getValueObject1/2 returns object sizes.
                        float[][] bothP = computeColocFromLabelImages(aObjImg, bObjImg, popA, popB);
                        p1 = bothP[0];
                        p2 = bothP[1];

                    } catch (NoClassDefFoundError e) {
                        // mcib3d not available — fall back to macro-based 3D MultiColoc
                        // Must run twice (A→B and B→A) and take P1 from each,
                        // because P1 is the source image's perspective only.
                        IJ.log("    - WARNING: mcib3d classes not found, falling back to macro 3D MultiColoc");
                        COUNTER3D_LOCK.lock();
                        try {
                            ensureInWindowManager(aObjImg);
                            ensureInWindowManager(bObjImg);

                            // A→B: P1 gives coloc values from A's perspective
                            String logBefore = getLogText();
                            IJ.run("3D MultiColoc", "image_a=" + aObjects + " image_b=" + bObjects);
                            restoreLogText(logBefore);

                            Frame colocFrame = WindowManager.getFrame("Colocalisation");
                            if (colocFrame instanceof TextWindow) {
                                ij.measure.ResultsTable rt = ((TextWindow) colocFrame).getTextPanel().getResultsTable();
                                if (rt != null && rt.size() > 0) {
                                    int p1Index = rt.getColumnIndex("P1");
                                    p1 = (p1Index >= 0) ? rt.getColumn(p1Index) : null;
                                    if (p1 != null && p1.length > 1) p1 = Arrays.copyOf(p1, p1.length - 1);
                                }
                                colocFrame.dispose();
                            }

                            // B→A: P1 gives coloc values from B's perspective
                            logBefore = getLogText();
                            IJ.run("3D MultiColoc", "image_a=" + bObjects + " image_b=" + aObjects);
                            restoreLogText(logBefore);

                            colocFrame = WindowManager.getFrame("Colocalisation");
                            if (colocFrame instanceof TextWindow) {
                                ij.measure.ResultsTable rt = ((TextWindow) colocFrame).getTextPanel().getResultsTable();
                                if (rt != null && rt.size() > 0) {
                                    int p1Index = rt.getColumnIndex("P1");
                                    p2 = (p1Index >= 0) ? rt.getColumn(p1Index) : null;
                                    if (p2 != null && p2.length > 1) p2 = Arrays.copyOf(p2, p2.length - 1);
                                }
                                colocFrame.dispose();
                            }
                        } finally {
                            COUNTER3D_LOCK.unlock();
                        }
                    }

                    // Write continuous overlap into Colocalisation with X and thresholded flags into VolColoc.
                    if (p1 != null && aTable != null) {
                        writeColocValuesForThisImage(aTable, overlapColAB, scnIndex, animalName, hemisphere, region, roiLabel, p1);
                        writeThresholdColocValuesForThisImage(aTable, colocColAB, scnIndex, animalName, hemisphere, region, roiLabel,
                                p1, getColocThreshold(aChannel));
                    } else if (aTable != null) {
                        setColocZerosForThisImage(aTable, overlapColAB, scnIndex, animalName, hemisphere, region, roiLabel);
                        setColocZerosForThisImage(aTable, colocColAB, scnIndex, animalName, hemisphere, region, roiLabel);
                    }

                    if (p2 != null && bTable != null) {
                        writeColocValuesForThisImage(bTable, overlapColBA, scnIndex, animalName, hemisphere, region, roiLabel, p2);
                        writeThresholdColocValuesForThisImage(bTable, colocColBA, scnIndex, animalName, hemisphere, region, roiLabel,
                                p2, getColocThreshold(bChannel));
                    } else if (bTable != null) {
                        setColocZerosForThisImage(bTable, overlapColBA, scnIndex, animalName, hemisphere, region, roiLabel);
                        setColocZerosForThisImage(bTable, colocColBA, scnIndex, animalName, hemisphere, region, roiLabel);
                    }

                    int interactionsAB = countNonZero(p1);
                    int interactionsBA = countNonZero(p2);
                    appendColocLogEntry(logEntries, logFirst, a, bChannel, interactionsAB);
                    appendColocLogEntry(logEntries, logFirst, b, aChannel, interactionsBA);
                } catch (Exception e) {
                    if (aTable != null) {
                        setColocZerosForThisImage(aTable, overlapColAB, scnIndex, animalName, hemisphere, region, roiLabel);
                        setColocZerosForThisImage(aTable, colocColAB, scnIndex, animalName, hemisphere, region, roiLabel);
                    }
                    if (bTable != null) {
                        setColocZerosForThisImage(bTable, overlapColBA, scnIndex, animalName, hemisphere, region, roiLabel);
                        setColocZerosForThisImage(bTable, colocColBA, scnIndex, animalName, hemisphere, region, roiLabel);
                    }
                    appendColocLogEntry(logEntries, logFirst, a, bChannel, -1);
                    appendColocLogEntry(logEntries, logFirst, b, aChannel, -1);
                }
            }
        }

        // Print per-channel log summaries (always, including compact mode —
        // these are one-line-per-channel and provide essential coloc context)
        for (int i = 0; i < n; i++) {
            String ch = cfg.channelNames.get(i);
            if (logEntries.get(i).length() > 0) {
                IJ.log("    - " + ch + ": " + logEntries.get(i).toString());
            }
        }
    }

    /**
     * Removes labels from a label image whose 2D centroid (averaged across Z) falls
     * outside the given ROI. Used to discard objects detected outside the tissue region
     * when StarDist runs on the full uncropped image.
     *
     * @return number of labels removed
     */
    static int filterLabelsByCentroid(ImagePlus labelImage, ij.gui.Roi roi) {
        if (labelImage == null || roi == null) return 0;

        ij.ImageStack stack = labelImage.getStack();
        int nSlices = stack.getSize();

        // Pass 1: accumulate centroid sums per label
        java.util.Map<Integer, long[]> centroids = new java.util.LinkedHashMap<Integer, long[]>();
        for (int s = 1; s <= nSlices; s++) {
            ij.process.ImageProcessor ip = stack.getProcessor(s);
            int nPixels = ip.getPixelCount();
            int w = ip.getWidth();
            for (int i = 0; i < nPixels; i++) {
                int label = ip.get(i);
                if (label == 0) continue;
                long[] acc = centroids.get(label);
                if (acc == null) {
                    acc = new long[3]; // [sumX, sumY, count]
                    centroids.put(label, acc);
                }
                acc[0] += i % w;  // x
                acc[1] += i / w;  // y
                acc[2]++;
            }
        }

        // Determine which labels have centroids outside the ROI
        java.util.Set<Integer> reject = new java.util.HashSet<Integer>();
        for (java.util.Map.Entry<Integer, long[]> entry : centroids.entrySet()) {
            long[] acc = entry.getValue();
            double cx = (double) acc[0] / acc[2];
            double cy = (double) acc[1] / acc[2];
            if (!roi.contains((int) Math.round(cx), (int) Math.round(cy))) {
                reject.add(entry.getKey());
            }
        }

        if (reject.isEmpty()) return 0;

        // Pass 2: zero out rejected labels
        for (int s = 1; s <= nSlices; s++) {
            ij.process.ImageProcessor ip = stack.getProcessor(s);
            int nPixels = ip.getPixelCount();
            for (int i = 0; i < nPixels; i++) {
                if (reject.contains(ip.get(i))) {
                    ip.set(i, 0);
                }
            }
        }

        return reject.size();
    }

    private static int countNonZero(float[] arr) {
        if (arr == null) return 0;
        int count = 0;
        for (float v : arr) if (v > 0) count++;
        return count;
    }

    private void appendColocLogEntry(List<StringBuilder> logEntries, List<Boolean> logFirst, int channelIdx, String otherChannel, int interactions) {
        StringBuilder sb = logEntries.get(channelIdx);
        if (!logFirst.get(channelIdx)) sb.append(", ");
        logFirst.set(channelIdx, Boolean.FALSE);
        if (interactions < 0) {
            sb.append("vs ").append(otherChannel).append(" (failed)");
        } else {
            sb.append("vs ").append(otherChannel).append(" (").append(interactions).append(" interactions)");
        }
    }

    private void appendColocLogEntry(List<StringBuilder> logEntries, List<Boolean> logFirst, int channelIdx, String otherChannel, String reason) {
        StringBuilder sb = logEntries.get(channelIdx);
        if (!logFirst.get(channelIdx)) sb.append(", ");
        logFirst.set(channelIdx, Boolean.FALSE);
        sb.append("vs ").append(otherChannel).append(" (").append(reason).append(")");
    }

    /**
     * Compute bidirectional colocalization by scanning label images pixel-by-pixel.
     * For each A-object, finds the B-partner with maximum overlap and computes
     * overlapVoxels / objectSize * 100. Matches 3D MultiColoc P1 column.
     *
     * Returns float[2][] where [0] = A→B (one value per A-object, in label order)
     * and [1] = B→A (one value per B-object, in label order).
     * Non-colocalising objects get 0.0. Values are always 0-100%.
     *
     * The label order matches Objects3DIntPopulation iteration order because both
     * are derived from the same label image and mcib3d iterates by label discovery.
     */
    // REGRESSION GUARD: Do NOT use mcib3d MeasurePopulationColocalisation or PairObjects3DInt
    // for overlap computation. getValueObject1()/getValueObject2() return object SIZES, not
    // overlap voxels — every pair yields 100%. Pixel-by-pixel scanning is the only reliable method.
    private static float[][] computeColocFromLabelImages(ImagePlus aObjImg, ImagePlus bObjImg,
            mcib3d.geom2.Objects3DIntPopulation popA, mcib3d.geom2.Objects3DIntPopulation popB) {
        ij.ImageStack stackA = aObjImg.getStack();
        ij.ImageStack stackB = bObjImg.getStack();
        int nSlices = stackA.getSize();

        // Count voxels per label and overlap voxels per (A-label, B-label) pair.
        // Key for overlap: pack two ints into a long.
        // Primitive Trove maps avoid per-voxel autoboxing on dense overlap stacks.
        gnu.trove.map.hash.TIntIntHashMap aSizes = new gnu.trove.map.hash.TIntIntHashMap();
        gnu.trove.map.hash.TIntIntHashMap bSizes = new gnu.trove.map.hash.TIntIntHashMap();
        gnu.trove.map.hash.TLongIntHashMap overlapCounts = new gnu.trove.map.hash.TLongIntHashMap();

        for (int s = 1; s <= nSlices; s++) {
            ij.process.ImageProcessor ipA = stackA.getProcessor(s);
            ij.process.ImageProcessor ipB = stackB.getProcessor(s);
            int nPixels = ipA.getPixelCount();
            for (int i = 0; i < nPixels; i++) {
                int a = ipA.get(i);
                int b = ipB.get(i);
                if (a > 0) {
                    aSizes.adjustOrPutValue(a, 1, 1);
                }
                if (b > 0) {
                    bSizes.adjustOrPutValue(b, 1, 1);
                }
                if (a > 0 && b > 0) {
                    long key = ((long) a << 32) | (b & 0xFFFFFFFFL);
                    overlapCounts.adjustOrPutValue(key, 1, 1);
                }
            }
        }

        // For each object, track the maximum overlap percentage with any single partner.
        gnu.trove.map.hash.TIntFloatHashMap aMaxPct = new gnu.trove.map.hash.TIntFloatHashMap();
        gnu.trove.map.hash.TIntFloatHashMap bMaxPct = new gnu.trove.map.hash.TIntFloatHashMap();
        gnu.trove.iterator.TLongIntIterator it = overlapCounts.iterator();
        while (it.hasNext()) {
            it.advance();
            long key = it.key();
            int overlap = it.value();
            int aLabel = (int) (key >>> 32);
            int bLabel = (int) (key & 0xFFFFFFFFL);
            int aSize = aSizes.get(aLabel);
            if (aSize > 0) {
                float pct = (float) ((double) overlap / aSize * 100.0);
                if (pct > aMaxPct.get(aLabel)) aMaxPct.put(aLabel, pct);
            }
            int bSize = bSizes.get(bLabel);
            if (bSize > 0) {
                float pct = (float) ((double) overlap / bSize * 100.0);
                if (pct > bMaxPct.get(bLabel)) bMaxPct.put(bLabel, pct);
            }
        }

        // Build p1 in population iteration order (matches table row order from buildNativeStatisticsTable)
        java.util.List<mcib3d.geom2.Object3DInt> objectsA = popA.getObjects3DInt();
        float[] p1 = new float[objectsA.size()];
        for (int i = 0; i < objectsA.size(); i++) {
            int label = (int) objectsA.get(i).getLabel();
            p1[i] = aMaxPct.get(label);
        }

        // Build p2 in population iteration order
        java.util.List<mcib3d.geom2.Object3DInt> objectsB = popB.getObjects3DInt();
        float[] p2 = new float[objectsB.size()];
        for (int i = 0; i < objectsB.size(); i++) {
            int label = (int) objectsB.get(i).getLabel();
            p2[i] = bMaxPct.get(label);
        }

        return new float[][] { p1, p2 };
    }

    private ColocalizationMetrics.Result computeIntensityColocMetrics(String aChannel, String bChannel) {
        try {
            ImagePlus aImage = getRegisteredImage(aChannel + "_unfiltered");
            ImagePlus bImage = getRegisteredImage(bChannel + "_unfiltered");
            ColocalizationMetrics.Result result = ColocalizationMetrics.compute(aImage, bImage);
            if (result.note != null && result.note.length() > 0
                    && !"missing channel image".equals(result.note)) {
                IJ.log("    [COLOC] " + aChannel + " vs " + bChannel + ": " + result.note);
            }
            return result;
        } catch (Exception e) {
            IJ.log("    [COLOC] intensity metrics failed for " + aChannel + " vs " + bChannel
                    + ": " + e.getMessage());
            return null;
        }
    }

    private void writeColocMetricsForThisImage(ij.measure.ResultsTable table,
                                               String sourceChannel,
                                               String partnerChannel,
                                               ColocalizationMetrics.Result metrics,
                                               boolean sourceIsA,
                                               int scnIndex,
                                               String animalName,
                                               String hemisphere,
                                               String region,
                                               String roiLabel) {
        for (int r = 0; r < table.size(); r++) {
            if (matchesRowMetadata(table, r, scnIndex, animalName, hemisphere, region, roiLabel)) {
                writeColocMetricValues(table, r, sourceChannel, partnerChannel, metrics, sourceIsA);
            }
        }
    }

    private void writeColocMetricValues(ij.measure.ResultsTable table,
                                        int row,
                                        String sourceChannel,
                                        String partnerChannel,
                                        ColocalizationMetrics.Result metrics,
                                        boolean sourceIsA) {
        double pearson = metrics == null ? Double.NaN : metrics.pearson;
        double m1 = metrics == null ? Double.NaN
                : (sourceIsA ? metrics.mandersM1 : metrics.mandersM2);
        double m2 = metrics == null ? Double.NaN
                : (sourceIsA ? metrics.mandersM2 : metrics.mandersM1);
        double ta = metrics == null ? Double.NaN
                : (sourceIsA ? metrics.costesTa : metrics.costesTb);
        double tb = metrics == null ? Double.NaN
                : (sourceIsA ? metrics.costesTb : metrics.costesTa);
        double pearsonT = metrics == null ? Double.NaN : metrics.pearsonThresholded;
        double p = metrics == null ? Double.NaN : metrics.costesP;

        setMetricValue(table, pearsonCol(sourceChannel, partnerChannel), row, pearson);
        setMetricValue(table, mandersM1Col(sourceChannel, partnerChannel), row, m1);
        setMetricValue(table, mandersM2Col(sourceChannel, partnerChannel), row, m2);
        setMetricValue(table, costesTaCol(sourceChannel, partnerChannel), row, ta);
        setMetricValue(table, costesTbCol(sourceChannel, partnerChannel), row, tb);
        setMetricValue(table, pearsonThresholdedCol(sourceChannel, partnerChannel), row, pearsonT);
        setMetricValue(table, costesPCol(sourceChannel, partnerChannel), row, p);
    }

    private void setMetricValue(ij.measure.ResultsTable table, String colName, int row, double value) {
        if (Double.isNaN(value)) {
            table.setValue(colName, row, "NaN");
        } else {
            table.setValue(colName, row, value);
        }
    }

    private void writeColocValuesForThisImage(ij.measure.ResultsTable table, String colName, int scnIndex,
                                              String animalName, String hemisphere, String region, String roiLabel,
                                              float[] values) {
        int written = 0;
        for (int r = 0; r < table.size() && written < values.length; r++) {
            if (matchesRowMetadata(table, r, scnIndex, animalName, hemisphere, region, roiLabel)) {
                table.setValue(colName, r, values[written]);
                written++;
            }
        }
    }

    private void writeThresholdColocValuesForThisImage(ij.measure.ResultsTable table, String colName, int scnIndex,
                                                       String animalName, String hemisphere, String region, String roiLabel,
                                                       float[] values, double threshold) {
        int written = 0;
        for (int r = 0; r < table.size() && written < values.length; r++) {
            if (matchesRowMetadata(table, r, scnIndex, animalName, hemisphere, region, roiLabel)) {
                table.setValue(colName, r, values[written] > threshold ? 1 : 0);
                written++;
            }
        }
    }

    private void setColocZerosForThisImage(ij.measure.ResultsTable table, String colName, int scnIndex,
                                           String animalName, String hemisphere, String region, String roiLabel) {
        for (int r = 0; r < table.size(); r++) {
            if (matchesRowMetadata(table, r, scnIndex, animalName, hemisphere, region, roiLabel)) {
                table.setValue(colName, r, 0);
            }
        }
    }

    private void writeLengthValuesForThisImage(ResultsTable table, int scnIndex, String animalName,
                                               String hemisphere, String region, String roiLabel, double[] lengths) {
        int written = 0;
        for (int r = 0; r < table.size() && written < lengths.length; r++) {
            if (matchesRowMetadata(table, r, scnIndex, animalName, hemisphere, region, roiLabel)) {
                table.setValue("Length", r, lengths[written]);
                written++;
            }
        }
    }

    private boolean matchesRowMetadata(ResultsTable table, int row, int scnIndex, String animalName,
                                       String hemisphere, String region, String roiLabel) {
        double scn = table.getValue("SCN", row);
        String an = table.getStringValue("Animal Name", row);
        String h = table.getStringValue("Hemisphere", row);
        String reg = table.getStringValue("Region", row);
        String roi = "";
        try {
            roi = table.getStringValue("ROI", row);
        } catch (Exception ignored) {
        }

        if ((int) scn != scnIndex) return false;
        if (!animalName.equals(an)) return false;
        if (!safeEq(hemisphere, h)) return false;
        if (!safeEq(region, reg)) return false;
        return roiLabel == null || roiLabel.isEmpty() || safeEq(roiLabel, roi);
    }

    private double getColocThreshold(String source) {
        Double t = markerThresholds.get(source);
        return t != null ? t : 30.0;
    }

    private String build3DObjectChannelSummary(BinConfig cfg, int channelIndex) {
        if (cfg == null || channelIndex < 0 || channelIndex >= cfg.numChannels()) return "";
        if (cfg.isStarDist(channelIndex)) {
            return "Segmentation=StarDist 3D"
                    + ", ProbThresh=" + cfg.getStarDistProbThresh(channelIndex)
                    + ", NmsThresh=" + cfg.getStarDistNmsThresh(channelIndex)
                    + ", LinkingMaxDistance=" + cfg.getStarDistLinkingMaxDistance(channelIndex)
                    + ", GapClosingMaxDistance=" + cfg.getStarDistGapClosingMaxDistance(channelIndex)
                    + ", MaxFrameGap=" + cfg.getStarDistMaxFrameGap(channelIndex)
                    + ", Area=" + cfg.getStarDistAreaMin(channelIndex) + "-"
                    + (Double.isInfinite(cfg.getStarDistAreaMax(channelIndex)) ? "Infinity" : cfg.getStarDistAreaMax(channelIndex))
                    + ", QualityMin=" + cfg.getStarDistQualityMin(channelIndex)
                    + ", IntensityMin=" + cfg.getStarDistIntensityMin(channelIndex);
        }
        if (cfg.isCellpose(channelIndex)) {
            int companionIndex = cfg.getCellposeSecondChannel(channelIndex);
            String companionSummary = companionIndex >= 0 && companionIndex < cfg.channelNames.size()
                    ? cfg.channelNames.get(companionIndex)
                    : "None";
            return "Segmentation=Cellpose"
                    + ", Model=" + cfg.getCellposeModel(channelIndex)
                    + ", Diameter=" + cfg.getCellposeDiameter(channelIndex)
                    + ", FlowThreshold=" + cfg.getCellposeFlowThreshold(channelIndex)
                    + ", CellprobThreshold=" + cfg.getCellposeCellprobThreshold(channelIndex)
                    + ", UseGpu=" + cfg.getCellposeUseGpu(channelIndex)
                    + ", CompanionChannel=" + companionSummary;
        }
        String threshold = channelIndex < cfg.channelThresholds.size() ? cfg.channelThresholds.get(channelIndex) : "";
        String size = channelIndex < cfg.channelSizes.size() ? cfg.channelSizes.get(channelIndex) : "";
        return "Segmentation=Classical, Threshold=" + threshold + ", Size=" + size;
    }

    /** Build canonical percentage overlap column name stored in each channel CSV. */
    private String colocPercentCol(String partner) {
        return "Colocalisation with " + partner;
    }

    private String pearsonCol(String source, String partner) {
        return source + "_Pearson_" + partner;
    }

    private String mandersM1Col(String source, String partner) {
        return source + "_Manders_M1_" + partner;
    }

    private String mandersM2Col(String source, String partner) {
        return source + "_Manders_M2_" + partner;
    }

    private String costesTaCol(String source, String partner) {
        return source + "_Costes_Ta_" + partner;
    }

    private String costesTbCol(String source, String partner) {
        return source + "_Costes_Tb_" + partner;
    }

    private String pearsonThresholdedCol(String source, String partner) {
        return source + "_Pearson_t_" + partner;
    }

    private String costesPCol(String source, String partner) {
        return source + "_Costes_p_" + partner;
    }

    /** Build thresholded volumetric coloc flag column name: SOURCE_VolColocN_PARTNER. */
    private String volColocCol(String source, String partner) {
        int thr = (int) getColocThreshold(source);
        return source + "_VolColoc" + thr + "_" + partner;
    }

    private void ensureAllColocColumns(BinConfig cfg, Map<String, ij.measure.ResultsTable> channelTables) {
        for (String aChannel : cfg.channelNames) {
            ij.measure.ResultsTable t = channelTables.get(aChannel);
            if (t == null) continue;

            for (String bChannel : cfg.channelNames) {
                if (aChannel.equals(bChannel)) continue;
                String overlapCol = colocPercentCol(bChannel);
                String colocCol = volColocCol(aChannel, bChannel);

                // Ensure column exists by setting a value in row 0 if present
                if (t.size() > 0) {
                    try {
                        t.setValue(overlapCol, 0, t.getValue(overlapCol, 0));
                    } catch (Exception e) {
                        t.setValue(overlapCol, 0, 0);
                    }
                    ensureColocMetricColumn(t, pearsonCol(aChannel, bChannel));
                    ensureColocMetricColumn(t, mandersM1Col(aChannel, bChannel));
                    ensureColocMetricColumn(t, mandersM2Col(aChannel, bChannel));
                    ensureColocMetricColumn(t, costesTaCol(aChannel, bChannel));
                    ensureColocMetricColumn(t, costesTbCol(aChannel, bChannel));
                    ensureColocMetricColumn(t, pearsonThresholdedCol(aChannel, bChannel));
                    ensureColocMetricColumn(t, costesPCol(aChannel, bChannel));
                    try {
                        t.setValue(colocCol, 0, t.getValue(colocCol, 0));
                    } catch (Exception e) {
                        t.setValue(colocCol, 0, 0);
                    }
                } else {
                    // IMPORTANT: Do not create a dummy row here.
                    // If there are no objects for a given SCN/channel, appendStatsToChannelTable(...) is
                    // responsible for creating a 1-row (SCN/Animal filled) row of zeros.
                    // Creating rows here causes an extra leading row with SCN=0/Animal Name=0.
                }
            }
        }
    }

    // ── CPC (Centre-Particle Coincidence) colocalization ──────────────

    private void ensureColocMetricColumn(ij.measure.ResultsTable table, String colName) {
        if (table == null || table.size() == 0) return;
        if (table.getColumnIndex(colName) < 0) {
            table.setValue(colName, 0, "NaN");
        }
    }

    private void ensureAllCpcColocColumns(BinConfig cfg, Map<String, ij.measure.ResultsTable> channelTables) {
        for (String src : cfg.channelNames) {
            ij.measure.ResultsTable t = channelTables.get(src);
            if (t == null || t.size() == 0) continue;

            for (String tgt : cfg.channelNames) {
                if (src.equals(tgt)) continue;
                ensureCpcColumn(t, src + "_CPCColoc_" + tgt);

                ensureCpcColumn(t, src + "_CPCContains_" + tgt);
            }
            ensureCpcColumn(t, src + "_CPCTargetsHit");

            try {
                t.getStringValue(src + "_CPCPattern", 0);
            } catch (Exception e) {
                t.setValue(src + "_CPCPattern", 0, "None");
            }
        }
    }

    private static void ensureCpcColumn(ij.measure.ResultsTable t, String colName) {
        try {
            t.setValue(colName, 0, t.getValue(colName, 0));
        } catch (Exception e) {
            t.setValue(colName, 0, 0);
        }
    }

    /**
     * CPC colocalization: for each channel pair, extract objects from label images,
     * test whether each object's centroid falls inside a partner object, and write
     * CPC Coloc/Partner/Contains columns to the channel tables.
     */
    private void appendCpcColocColumns(
            BinConfig cfg,
            boolean[] channelHasObjects,
            Map<String, ij.measure.ResultsTable> channelTables,
            int scnIndex,
            String animalName,
            String hemisphere,
            String region,
            String roiLabel
    ) {
        int n = Math.min(cfg.numChannels(), channelHasObjects != null ? channelHasObjects.length : 0);
        if (n == 0) return;

        // Zero-fill CPC columns for channels with no objects
        for (int a = 0; a < n; a++) {
            ij.measure.ResultsTable aTable = channelTables.get(cfg.channelNames.get(a));
            boolean aHas = channelHasObjects[a]
                    && getRegisteredImage(cfg.channelNames.get(a) + "_objects") != null
                    && aTable != null;
            if (!aHas && aTable != null) {
                for (int b = 0; b < n; b++) {
                    if (b == a) continue;
                    setCpcZerosForThisImage(aTable, cfg.channelNames.get(a), cfg.channelNames.get(b), scnIndex, animalName, hemisphere, region, roiLabel);
                }
            }
        }

        // Run CPC once per unique pair
        for (int a = 0; a < n; a++) {
            String aChannel = cfg.channelNames.get(a);
            ImagePlus aObjImg = getRegisteredImage(aChannel + "_objects");
            boolean aHas = channelHasObjects[a] && aObjImg != null;
            ij.measure.ResultsTable aTable = channelTables.get(aChannel);

            for (int b = a + 1; b < n; b++) {
                String bChannel = cfg.channelNames.get(b);
                ImagePlus bObjImg = getRegisteredImage(bChannel + "_objects");
                boolean bHas = channelHasObjects[b] && bObjImg != null;
                ij.measure.ResultsTable bTable = channelTables.get(bChannel);

                if (!aHas || !bHas) {
                    if (aTable != null) setCpcZerosForThisImage(aTable, aChannel, bChannel, scnIndex, animalName, hemisphere, region, roiLabel);
                    if (bTable != null) setCpcZerosForThisImage(bTable, bChannel, aChannel, scnIndex, animalName, hemisphere, region, roiLabel);
                    String emptyChannel = !aHas
                            ? (!bHas ? aChannel + "+" + bChannel : aChannel)
                            : bChannel;
                    IJ.log("    - CPC: " + aChannel + " vs " + bChannel + " skipped (no objects in " + emptyChannel + ")");
                    continue;
                }

                try {
                    // Extract objects from label images (geometric centroids in pixel coords)
                    List<CpcUtils.ObjectInfo> objectsA = CpcUtils.extractObjects(aObjImg);
                    List<CpcUtils.ObjectInfo> objectsB = CpcUtils.extractObjects(bObjImg);

                    // Forward test: A centroids in B label image
                    List<CpcUtils.ObjectInfo> fwdA = CpcUtils.copyObjects(objectsA);
                    CpcUtils.testCoincidence(fwdA, bObjImg);

                    // Reverse test: B centroids in A label image
                    List<CpcUtils.ObjectInfo> revB = CpcUtils.copyObjects(objectsB);
                    CpcUtils.testCoincidence(revB, aObjImg);

                    // Build label → result maps
                    Map<Integer, CpcUtils.ObjectInfo> fwdMapA = new LinkedHashMap<Integer, CpcUtils.ObjectInfo>();
                    for (CpcUtils.ObjectInfo obj : fwdA) fwdMapA.put(obj.label, obj);

                    Map<Integer, CpcUtils.ObjectInfo> revMapB = new LinkedHashMap<Integer, CpcUtils.ObjectInfo>();
                    for (CpcUtils.ObjectInfo obj : revB) revMapB.put(obj.label, obj);

                    // Containment: count how many B centroids fell inside each A object
                    Map<Integer, Integer> containsCountA = new LinkedHashMap<Integer, Integer>();
                    for (CpcUtils.ObjectInfo obj : revB) {
                        if (obj.partnerLabel > 0) {
                            Integer prev = containsCountA.get(obj.partnerLabel);
                            containsCountA.put(obj.partnerLabel, (prev != null ? prev : 0) + 1);
                        }
                    }

                    // Containment: count how many A centroids fell inside each B object
                    Map<Integer, Integer> containsCountB = new LinkedHashMap<Integer, Integer>();
                    for (CpcUtils.ObjectInfo obj : fwdA) {
                        if (obj.partnerLabel > 0) {
                            Integer prev = containsCountB.get(obj.partnerLabel);
                            containsCountB.put(obj.partnerLabel, (prev != null ? prev : 0) + 1);
                        }
                    }

                    // Write to A's table
                    if (aTable != null) {
                        writeCpcValuesForThisImage(aTable, aChannel, bChannel, fwdMapA, containsCountA,
                                scnIndex, animalName, hemisphere, region, roiLabel);
                    }

                    // Write to B's table
                    if (bTable != null) {
                        writeCpcValuesForThisImage(bTable, bChannel, aChannel, revMapB, containsCountB,
                                scnIndex, animalName, hemisphere, region, roiLabel);
                    }

                    int cpcCountAB = 0;
                    for (CpcUtils.ObjectInfo obj : fwdA) if (obj.isColocalized()) cpcCountAB++;
                    int cpcCountBA = 0;
                    for (CpcUtils.ObjectInfo obj : revB) if (obj.isColocalized()) cpcCountBA++;
                    IJ.log("    - CPC: " + aChannel + " vs " + bChannel
                            + " (" + cpcCountAB + "/" + objectsA.size() + " fwd, "
                            + cpcCountBA + "/" + objectsB.size() + " rev)");

                } catch (Exception e) {
                    IJ.log("    - CPC: " + aChannel + " vs " + bChannel + " FAILED: " + e.getMessage());
                    if (aTable != null) setCpcZerosForThisImage(aTable, aChannel, bChannel, scnIndex, animalName, hemisphere, region, roiLabel);
                    if (bTable != null) setCpcZerosForThisImage(bTable, bChannel, aChannel, scnIndex, animalName, hemisphere, region, roiLabel);
                }
            }
        }

        // Multi-target columns (after all pairwise tests complete)
        appendCpcMultiTargetColumns(cfg, channelTables, scnIndex, animalName, hemisphere, region, roiLabel);
    }

    /**
     * Write CPC results for one channel direction into the table, matched by Label column.
     */
    private void writeCpcValuesForThisImage(
            ij.measure.ResultsTable table, String sourceChannel, String partnerChannel,
            Map<Integer, CpcUtils.ObjectInfo> resultsByLabel,
            Map<Integer, Integer> containsCounts,
            int scnIndex, String animalName, String hemisphere,
            String region, String roiLabel
    ) {
        String colocCol = sourceChannel + "_CPCColoc_" + partnerChannel;
        String containsCol = sourceChannel + "_CPCContains_" + partnerChannel;

        for (int r = 0; r < table.size(); r++) {
            if (!matchesRowMetadata(table, r, scnIndex, animalName, hemisphere, region, roiLabel)) continue;

            int label = (int) table.getValue("Label", r);
            CpcUtils.ObjectInfo info = resultsByLabel.get(label);
            table.setValue(colocCol, r, info != null && info.isColocalized() ? 1 : 0);

            Integer cc = containsCounts.get(label);
            table.setValue(containsCol, r, cc != null ? cc : 0);
        }
    }

    /**
     * Zero-fill CPC columns for a source/partner channel pair.
     */
    private void setCpcZerosForThisImage(ij.measure.ResultsTable table, String sourceChannel,
                                         String partnerChannel,
                                         int scnIndex, String animalName, String hemisphere,
                                         String region, String roiLabel) {
        for (int r = 0; r < table.size(); r++) {
            if (!matchesRowMetadata(table, r, scnIndex, animalName, hemisphere, region, roiLabel)) continue;
            table.setValue(sourceChannel + "_CPCColoc_" + partnerChannel, r, 0);
            table.setValue(sourceChannel + "_CPCContains_" + partnerChannel, r, 0);
        }
    }

    /**
     * After all pairwise CPC tests, compute multi-target columns:
     * CPC Targets Hit = count of partner channels where CPC Coloc = 1
     * CPC Pattern = joined channel names where CPC Coloc = 1 (e.g. "GFAP + NeuN")
     */
    private void appendCpcMultiTargetColumns(
            BinConfig cfg,
            Map<String, ij.measure.ResultsTable> channelTables,
            int scnIndex, String animalName, String hemisphere,
            String region, String roiLabel
    ) {
        for (String aChannel : cfg.channelNames) {
            ij.measure.ResultsTable table = channelTables.get(aChannel);
            if (table == null) continue;

            // Collect partner channel names for this channel
            List<String> partners = new ArrayList<String>();
            for (String other : cfg.channelNames) {
                if (other.equals(aChannel)) continue;
                partners.add(other);
            }

            for (int r = 0; r < table.size(); r++) {
                if (!matchesRowMetadata(table, r, scnIndex, animalName, hemisphere, region, roiLabel)) continue;

                int targetsHit = 0;
                StringBuilder pattern = new StringBuilder();

                for (String partner : partners) {
                    try {
                        int val = (int) table.getValue(aChannel + "_CPCColoc_" + partner, r);
                        if (val > 0) {
                            targetsHit++;
                            if (pattern.length() > 0) pattern.append(" + ");
                            pattern.append(partner);
                        }
                    } catch (Exception ignored) {
                    }
                }

                table.setValue(aChannel + "_CPCTargetsHit", r, targetsHit);
                table.setValue(aChannel + "_CPCPattern", r, pattern.length() > 0 ? pattern.toString() : "None");
            }
        }
    }

    private static String getLogText() {
        Frame logFrame = WindowManager.getFrame("Log");
        if (logFrame instanceof TextWindow) {
            return ((TextWindow) logFrame).getTextPanel().getText();
        }
        return null;
    }

    private static void restoreLogText(String savedText) {
        if (savedText == null) {
            IJ.log("\\Clear");
            return;
        }
        IJ.log("\\Clear");
        for (String line : savedText.split("\n")) {
            IJ.log(line);
        }
    }

    private static int parseInteractionCount(String logBefore, String logAfter) {
        if (logAfter == null) return 0;
        // Find lines added by 3D MultiColoc
        String added = logBefore == null ? logAfter : logAfter.substring(logBefore.length());
        // Look for pattern like "X convergent convergences" or "X interactions" in the added text
        for (String line : added.split("\n")) {
            String trimmed = line.trim().toLowerCase(Locale.ROOT);
            // 3D MultiColoc typically logs "N convergent convergences found"
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s+(convergent|interaction)").matcher(trimmed);
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0;
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

    /**
     * Returns {@code true} when every expected per-channel output CSV
     * already exists in the output directory.  Used by the whole-analysis
     * pre-scan to skip the entire run before loading any pixel data.
     */
    static boolean allOutputCsvsExist(File outDir, List<String> channelNames) {
        for (String chName : channelNames) {
            if (!objectOutputCsv(outDir, chName).exists()) {
                return false;
            }
        }
        return true;
    }

    static File objectCsvWriteDir(String directory) {
        return FlashProjectLayout.forDirectory(directory).objectDataWriteDir();
    }

    static List<File> objectCsvReadDirs(String directory) {
        return FlashProjectLayout.forDirectory(directory).objectDataReadDirs();
    }

    static File objectImageOutputWriteRoot(String directory) {
        return FlashProjectLayout.forDirectory(directory).objectImageOutputsWriteDir();
    }

    static List<File> objectImageOutputReadRoots(String directory) {
        return FlashProjectLayout.forDirectory(directory).objectImageOutputReadDirs();
    }

    static File objectOutputCsv(File outDir, String channelName) {
        return new File(outDir, ChannelFilenameCodec.toSafe(channelName) + ".csv");
    }

    private static File objectTempCsv(File outDir, String channelName, int imageIndex) {
        return new File(outDir, ChannelFilenameCodec.toSafe(channelName) + "temp_" + imageIndex + ".csv");
    }

    private static File activeConfigurationDir(String directory) {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        File existing = layout.existingConfigurationDir();
        return existing == null ? layout.configurationWriteDir() : existing;
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

    private boolean safeEq(String a, String b) {
        String aa = a == null ? "" : a;
        String bb = b == null ? "" : b;
        return aa.equals(bb);
    }

    // Name parsing is handled by ImageNameParser (strict macro port).


    private ImagePlus findOpenImageByExactTitle(String title) {

        int[] ids = WindowManager.getIDList();
        if (ids == null) return null;
        for (int id : ids) {
            ImagePlus imp = WindowManager.getImage(id);
            if (imp != null && title.equals(imp.getTitle())) return imp;
        }
        return null;
    }

    private List<File> listImageFiles(File dir) {
        List<File> out = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) return out;
        File[] files = JunkFileFilter.listCleanFiles(dir);
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(".tif") || n.endsWith(".tiff")) out.add(f);
        }
        return out;
    }

    private Double tryParseDouble(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void closeAllNoPrompt() {
        int[] ids = WindowManager.getIDList();
        if (ids != null) {
            for (int id : ids) {
                ImagePlus imp = WindowManager.getImage(id);
                if (imp != null) imp.changes = false;
            }
        }
        IJ.run("Close All");
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
        // Close non-image windows (Results, etc.) but keep the Log
        Frame[] frames = WindowManager.getNonImageWindows();
        if (frames != null) {
            for (Frame f : frames) {
                if (f != null && !"Log".equals(f.getTitle())) {
                    f.dispose();
                }
            }
        }
    }
}
