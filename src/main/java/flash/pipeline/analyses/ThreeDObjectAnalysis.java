package flash.pipeline.analyses;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.bin.BinField;
import flash.pipeline.bin.BinSetupDispatcher;
import flash.pipeline.bin.ChannelConfigIO;
import flash.pipeline.bin.ChannelIdentities;
import flash.pipeline.analyses.wizard.ThreeDObjectPreset;
import flash.pipeline.analyses.wizard.ThreeDObjectPresetIO;
import flash.pipeline.analyses.wizard.ThreeDObjectSetupConfig;
import flash.pipeline.analyses.wizard.SpatialSetupConfig;
import flash.pipeline.analyses.spatial.DiskLabelImageProvider;
import flash.pipeline.analyses.spatial.InMemoryLabelImageProvider;
import flash.pipeline.analyses.spatial.LabelImageProvider;
import flash.pipeline.analyses.spatial.SectionKey;
import flash.pipeline.atlas.AtlasRegionColumns;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.deconv.DeconvolvedInputResolver;
import flash.pipeline.cellpose.Cellpose3DRunner;
import flash.pipeline.image.AdaptiveParallelism;
import flash.pipeline.image.FilterExecutor;
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
import flash.pipeline.naming.ChannelFilenameCodec;
import flash.pipeline.naming.ImageOrientationResolver;
import flash.pipeline.naming.ImageNameParser;
import flash.pipeline.naming.NameParts;
import flash.pipeline.naming.ResolvedImageMetadata;
import flash.pipeline.objects.ObjectsCounter3DWrapper;
import flash.pipeline.recipes.RecipeReplayModelResolver;
import flash.pipeline.results.ObjectAnalysisDetailsWriter;
import flash.pipeline.results.ObjectCsvColumnOrder;
import flash.pipeline.results.RunIdCsv;
import flash.pipeline.stardist.StarDist3DRunner;
import flash.pipeline.results.ResultsTableCleaner;
import flash.pipeline.runrecord.AnalysisRunContext;
import flash.pipeline.runrecord.LoadedRunParameterApplier;
import flash.pipeline.runrecord.LoadedRunParameters;
import flash.pipeline.runrecord.ParameterSnapshot;
import flash.pipeline.runrecord.RunRecordAware;
import flash.pipeline.runrecord.ui.LoadFromRunButton;
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.FeatureDependencyGate;
import flash.pipeline.runtime.PluginInstallGuard;
import flash.pipeline.roi.RegionMask;
import flash.pipeline.roi.RoiIO;
import flash.pipeline.segmentation.SegmentationMethod;
import flash.pipeline.segmentation.SegmentationRunFailureException;
import flash.pipeline.segmentation.SegmentationTokenParser;
import flash.pipeline.zslice.ZSliceOps;

import flash.pipeline.ui.NextStepLabels;
import flash.pipeline.ui.PipelineDialog;

import flash.pipeline.objects.CpcUtils;
import flash.pipeline.segmentation.EnhancedClassicalParameters;
import flash.pipeline.segmentation.EnhancedClassicalRunner;
import flash.pipeline.segmentation.MorphPredicate;
import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;

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
import java.awt.GraphicsEnvironment;
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
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
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
public class ThreeDObjectAnalysis implements Analysis, RunRecordAware {
    private static final String FULL_IMAGE_ROI_SET_NAME = "Full image";
    private static final Pattern MULTICOLOC_INTERACTION_COUNT_PATTERN =
            Pattern.compile("(\\d+)\\s+(convergent|interaction)");

    enum NoRoiDecision {
        DRAW_ROIS,
        ANALYSE_FULL_IMAGE,
        CANCEL
    }

    enum ExistingObjectDataMode {
        OVERWRITE,
        EXTEND,
        SKIP,
        CANCEL
    }

    interface ExistingObjectDataPrompt {
        ExistingObjectDataMode choose(File outputDir, List<File> existingCsvs);
    }

    interface NoRoiDecisionPrompt {
        NoRoiDecision choose();
    }

    interface RoiDrawingWorkflowLauncher {
        void launch(String directory);
    }

    private static final class RoiSetSelection {
        final List<File> roiZips;
        final String[] roiSetNames;
        final boolean analyseFullImagesWithoutRois;

        private RoiSetSelection(List<File> roiZips, String[] roiSetNames,
                                boolean analyseFullImagesWithoutRois) {
            this.roiZips = roiZips == null
                    ? Collections.<File>emptyList()
                    : new ArrayList<File>(roiZips);
            this.roiSetNames = roiSetNames == null
                    ? new String[0]
                    : Arrays.copyOf(roiSetNames, roiSetNames.length);
            this.analyseFullImagesWithoutRois = analyseFullImagesWithoutRois;
        }

        static RoiSetSelection saved(List<File> roiZips, String[] roiSetNames) {
            return new RoiSetSelection(roiZips, roiSetNames, false);
        }

        static RoiSetSelection fullImages() {
            return new RoiSetSelection(Collections.<File>emptyList(), new String[0], true);
        }

        boolean hasSavedRois() {
            return roiZips != null && !roiZips.isEmpty();
        }
    }

    /** Root for segmentation-related images: label maps, masked images, and filtered inputs. */
    static final class SegmentationImageRoot {
        final File root;

        SegmentationImageRoot(File root) {
            this.root = root;
        }

        static SegmentationImageRoot forDirectory(String directory) {
            FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
            return new SegmentationImageRoot(layout.analysisImagesSegmentationDir());
        }

        File animalDir(String animalName) { return new File(root, animalName); }
    }

    private static final ExistingObjectDataPrompt DEFAULT_EXISTING_OBJECT_DATA_PROMPT =
            new ExistingObjectDataPrompt() {
                @Override
                public ExistingObjectDataMode choose(File outputDir, List<File> existingCsvs) {
                    Object[] options = new Object[]{"Extend Existing Data", "Overwrite Existing Data", "Cancel"};
                    int choice = JOptionPane.showOptionDialog(
                            null,
                            existingObjectDataPromptMessage(outputDir, existingCsvs),
                            "3D Object Analysis - Existing Data Found",
                            JOptionPane.DEFAULT_OPTION,
                            JOptionPane.WARNING_MESSAGE,
                            null,
                            options,
                            options[0]);

                    if (choice == 0) return ExistingObjectDataMode.EXTEND;
                    if (choice == 1) return ExistingObjectDataMode.OVERWRITE;
                    return ExistingObjectDataMode.CANCEL;
                }
            };

    interface SpatialOptionsDialogLauncher {
        SpatialSetupConfig.DerivedConfig launch(String directory,
                                                   List<String> channelNames,
                                                   Map<String, Double> markerThresholds,
                                                   boolean lockVolumetricColoc,
                                                   boolean lockCpcColoc);
    }

    private static final SpatialOptionsDialogLauncher DEFAULT_SPATIAL_OPTIONS_DIALOG_LAUNCHER =
            new SpatialOptionsDialogLauncher() {
                @Override
                public SpatialSetupConfig.DerivedConfig launch(String directory,
                                                                  List<String> channelNames,
                                                                  Map<String, Double> markerThresholds,
                                                                  boolean lockVolumetricColoc,
                                                                  boolean lockCpcColoc) {
                    SpatialAnalysis spatialOptions = new SpatialAnalysis();
                    spatialOptions.setSuppressDialogs(false);
                    spatialOptions.setMarkerThresholds(markerThresholds == null
                            ? new LinkedHashMap<String, Double>()
                            : new LinkedHashMap<String, Double>(markerThresholds));
                    return spatialOptions.showOptionsDialogForChainedRun(
                            directory, channelNames, lockVolumetricColoc, lockCpcColoc,
                            NextStepLabels.RUN_3D_OBJECT_ANALYSIS);
                }
            };

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
    private boolean classicalCentroidFilter = true;
    private boolean doVolumetric = true;
    private boolean doCpc = true;
    private boolean doIntensityColoc = false;
    private Map<String, Double> markerThresholds = new LinkedHashMap<String, Double>();
    private boolean wizardExtractProcessLength = false;
    private boolean wizardRunSpatial = false;
    private int wizardNuclearMarkerIndex = -1;
    private boolean[] wizardProcessChannels = null;
    private SpatialSetupConfig.DerivedConfig wizardSpatialConfig = null;
    private final Map<SectionKey, Map<String, ImagePlus>> retainedLabels =
            new LinkedHashMap<SectionKey, Map<String, ImagePlus>>();
    private SpatialOptionsDialogLauncher spatialOptionsDialogLauncher =
            DEFAULT_SPATIAL_OPTIONS_DIALOG_LAUNCHER;
    private ExistingObjectDataPrompt existingObjectDataPrompt =
            DEFAULT_EXISTING_OBJECT_DATA_PROMPT;
    private Boolean guiDecisionDialogsAvailableForTest = null;
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
    private static final String OBJECT_PRESET_PLACEHOLDER = "(choose preset)";
    private final AtomicBoolean calibrationWritten = new AtomicBoolean(false);
    private boolean useDeconvolvedInput = true;
    private CLIConfig cliConfig = null;
    private AnalysisRunContext runRecordContext = null;

    /** Shared lock for all legacy ImageJ1/plugin paths that touch WindowManager or Prefs. */
    private static final ReentrantLock COUNTER3D_LOCK = WindowManagerLock.LOCK;

    private static final class FatalSegmentationException extends RuntimeException {
        FatalSegmentationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

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
        final boolean fullImage;

        RoiSetData(String name, ij.gui.Roi[] rois) {
            this(name, rois, false);
        }

        private RoiSetData(String name, ij.gui.Roi[] rois, boolean fullImage) {
            this.name = name == null ? "" : name;
            this.rois = rois == null ? new ij.gui.Roi[0] : rois;
            this.fullImage = fullImage;
        }

        static RoiSetData fullImage() {
            return new RoiSetData(FULL_IMAGE_ROI_SET_NAME, new ij.gui.Roi[0], true);
        }

        ij.gui.Roi cloneRoi(int index) {
            if (fullImage) return null;
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

    @Override
    public void setRunRecordContext(AnalysisRunContext context) {
        this.runRecordContext = context;
    }

    void applyPreset(String directory, ThreeDObjectPreset preset) {
        if (preset == null) return;
        BinConfig cfg = loadBinConfig(directory);
        ChannelIdentities identities = ChannelConfigIO.readChannelIdentities(
                FlashProjectLayout.forDirectory(directory).configurationWriteDir());
        ThreeDObjectSetupConfig.DerivedConfig derived =
                ThreeDObjectSetupConfig.fromPreset(cfg, identities, preset);
        applyThreeDObjectDerivedConfig(cfg, derived);
    }

    void setSpatialOptionsDialogLauncherForTest(SpatialOptionsDialogLauncher launcher) {
        this.spatialOptionsDialogLauncher = launcher == null
                ? DEFAULT_SPATIAL_OPTIONS_DIALOG_LAUNCHER
                : launcher;
    }

    void setExistingObjectDataPromptForTest(ExistingObjectDataPrompt prompt) {
        this.existingObjectDataPrompt = prompt == null
                ? DEFAULT_EXISTING_OBJECT_DATA_PROMPT
                : prompt;
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

    void setGuiDecisionDialogsAvailableForTest(Boolean available) {
        this.guiDecisionDialogsAvailableForTest = available;
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

    private static boolean gateObjectsCounterPlusFeature(String featureDisplayName) {
        return FeatureDependencyGate.gate(DependencyId.OBJECTS_COUNTER_3D_PLUS,
                "3D Object Analysis", featureDisplayName)
                && FeatureDependencyGate.gate(DependencyId.MCIB3D_CORE,
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
            SegmentationMethod method = cfg.segmentationMethod(i);
            if (method.isStarDist()) return true;
        }
        return false;
    }

    private static boolean usesCellposeSegmentation(BinConfig cfg) {
        if (cfg == null) return false;
        for (int i = 0; i < cfg.numChannels(); i++) {
            SegmentationMethod method = cfg.segmentationMethod(i);
            if (method.isCellpose()) return true;
        }
        return false;
    }

    private static boolean usesEnhancedClassicalSegmentation(BinConfig cfg) {
        if (cfg == null) return false;
        for (int i = 0; i < cfg.numChannels(); i++) {
            SegmentationMethod method = cfg.segmentationMethod(i);
            if (method.isEnhancedClassical()) return true;
        }
        return false;
    }

    private static boolean usesClassicalSegmentation(BinConfig cfg) {
        if (cfg == null) return false;
        for (int i = 0; i < cfg.numChannels(); i++) {
            SegmentationMethod method = cfg.segmentationMethod(i);
            if (method.isClassical()) return true;
        }
        return false;
    }

    private static boolean requiresMcib3dMorphometry(BinConfig cfg) {
        if (cfg == null) return false;
        for (int i = 0; i < cfg.numChannels(); i++) {
            if (cfg.usesLabelImageSegmentation(i) || cfg.isEnhancedClassical(i)) return true;
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
        clearRetainedSpatialLabels();

        if (!FeatureDependencyGate.gate(DependencyId.BIO_FORMATS_RUNTIME,
                "3D Object Analysis", "Bio-Formats image loading")) {
            recordWarn("3D Object Analysis blocked by missing Bio-Formats runtime dependency.");
            return;
        }

        RoiSetSelection roiSelection = resolveRoiSetsForRun(directory);
        if (roiSelection == null) {
            return;
        }
        List<File> roiZips = roiSelection.roiZips;
        String[] roiSetNames = roiSelection.roiSetNames;
        boolean analyseFullImagesWithoutRois = roiSelection.analyseFullImagesWithoutRois;

        BinSetupDispatcher.Outcome outcome = BinSetupDispatcher.ensure(
                directory, "3D Object Analysis", requiredBinFields(),
                benefitsFromRois(), suppressDialogs, cliConfig);
        if (outcome == BinSetupDispatcher.Outcome.CANCELLED) {
            String message = "[FLASH] 3D Object Analysis cancelled by user.";
            IJ.log(message);
            recordWarn(message);
            return;
        }

        BinConfig cfg = loadBinConfig(directory);
        RecipeReplayModelResolver.validate(new File(directory).toPath(), cfg.segmentationMethods);
        if (usesClassicalSegmentation(cfg)
                && !FeatureDependencyGate.gate(DependencyId.OBJECTS_COUNTER_3D,
                "3D Object Analysis", "classical 3D object counting")) {
            recordWarn("3D Object Analysis blocked by missing 3D Objects Counter dependency.");
            return;
        }
        if (usesEnhancedClassicalSegmentation(cfg)
                && !gateObjectsCounterPlusFeature("Enhanced Classical / 3D Objects Counter+ segmentation")) {
            recordWarn("3D Object Analysis blocked by missing enhanced-classical 3D object dependency.");
            return;
        }
        if (usesStarDistSegmentation(cfg) && !gateStarDistFeature("StarDist 3D segmentation")) {
            recordWarn("3D Object Analysis blocked by missing StarDist 3D dependency.");
            return;
        }
        if (usesCellposeSegmentation(cfg) && !gateCellposeFeature("Cellpose segmentation")) {
            recordWarn("3D Object Analysis blocked by missing Cellpose dependency.");
            return;
        }
        if (requiresMcib3dMorphometry(cfg)
                && !FeatureDependencyGate.gate(DependencyId.MCIB3D_CORE,
                "3D Object Analysis",
                "mcib3d-backed 3D morphometry / shape analysis")) {
            recordWarn("3D Object Analysis blocked by missing mcib3d dependency.");
            return;
        }
        IJ.log("Channels: " + String.join(", ", cfg.channelNames));

        ChannelIdentities channelIdentities = ChannelConfigIO.readChannelIdentities(
                FlashProjectLayout.forDirectory(directory).configurationWriteDir());
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
                ThreeDObjectSetupConfig.DerivedConfig derived = ThreeDObjectSetupConfig.deriveConfig(
                        cfg, channelIdentities, Collections.<String, Object>emptyMap(),
                        analyseFullImagesWithoutRois ? Collections.<String>emptyList() : Arrays.asList(roiSetNames));
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
                        objectBindings,
                        new ThreeDObjectConfigApplier() {
                            @Override
                            public void apply(String selectedPresetName,
                                              ThreeDObjectSetupConfig.DerivedConfig derived) {
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
                objectBindings.doIntensityColocToggle =
                        gdOpts.addToggle("Intensity Colocalization", doIntensityColoc);
                gdOpts.addHelpText("Per-object Pearson and Manders, plus Costes thresholds/significance "
                        + "at both object and image level.");

                gdOpts.addHeader("Colocalisation Thresholds");
                for (String chName : cfg.channelNames) {
                    Double prev = markerThresholds.get(chName);
                    objectBindings.thresholdFields.add(gdOpts.addNumericField(
                            chName + " Coloc Threshold (%)",
                            prev != null ? prev : 30.0, 0));
                }

                if (analyseFullImagesWithoutRois) {
                    gdOpts.addHeader("Analysis Region");
                    gdOpts.addHelpText("No saved ROI sets were found. This run will analyse the full image stack for each image.");
                } else {
                    gdOpts.addHeader("ROI Sets");
                    gdOpts.addHelpText("Select which ROI sets will be used for this analysis run.");
                    for (int r = 0; r < roiSetNames.length; r++) {
                        gdOpts.addToggle(roiSetNames[r], selectedRoiSets[r]);
                    }
                }

                gdOpts.beginAdvancedSection("threeDObject");
                objectBindings.extractProcessLengthToggle =
                        gdOpts.addToggle("Extract Process Length", extractProcessLength);
                gdOpts.addHelpText("Skeletonizes process channels, subtracts the nuclear marker, "
                        + "and measures skeleton length via 3D Objects Counter. "
                        + "When enabled, a follow-up Process Analysis dialog selects the nuclear "
                        + "marker channel and which channels contain processes.");

                objectBindings.runSpatialToggle =
                        gdOpts.addToggle("Run Spatial Analysis", runSpatial);
                gdOpts.addHelpText("Opens the full Spatial Analysis options next, then runs "
                        + "the selected spatial and morphometric outputs after 3D object counting.");

                final Runnable updatePrimaryLabel = new Runnable() {
                    @Override public void run() {
                        gdOpts.setPrimaryButtonText(NextStepLabels.afterThreeDObjectMain(
                                isSelected(objectBindings.extractProcessLengthToggle),
                                isSelected(objectBindings.runSpatialToggle)));
                    }
                };
                objectBindings.primaryLabelUpdater = updatePrimaryLabel;
                objectBindings.extractProcessLengthToggle.addChangeListener(updatePrimaryLabel);
                objectBindings.runSpatialToggle.addChangeListener(updatePrimaryLabel);
                updatePrimaryLabel.run();

                objectBindings.classicalCentroidFilterToggle =
                        gdOpts.addToggle("Use Centroid ROI Filtering (Classical)", classicalCentroidFilter);
                gdOpts.addHelpText("Classical channels only. When ON, objects are counted on the "
                        + "full uncropped image and then filtered by centroid position "
                        + "inside the ROI (like StarDist). When OFF, the image is cropped "
                        + "to the ROI before counting.");

                gdOpts.endAdvancedSection();

                LoadFromRunButton.install(gdOpts, "ThreeDObjectAnalysis", new File(directory),
                        new LoadedRunParameterApplier() {
                            @Override public LoadedRunParameters.Result applyLoadedParameters(
                                    Map<String, Object> parameters) {
                                return ThreeDObjectAnalysis.this.applyLoadedParameters(
                                        parameters, cfg, channelIdentities,
                                        objectBindings);
                            }
                        });

                if (!gdOpts.showDialog()) {
                    return; // Cancel
                }
                useDeconvolvedInput = gdOpts.getNextBoolean();
                doVolumetric = gdOpts.getNextBoolean();
                doCpc = gdOpts.getNextBoolean();
                doIntensityColoc = gdOpts.getNextBoolean();
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

                if (!analyseFullImagesWithoutRois && !hasSelectedRoiSets(selectedRoiSets)) {
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
                    ThreeDObjectSetupConfig.DerivedConfig derived = ThreeDObjectSetupConfig.deriveConfig(
                            cfg, channelIdentities, Collections.<String, Object>emptyMap(),
                            analyseFullImagesWithoutRois ? Collections.<String>emptyList() : Arrays.asList(roiSetNames));
                    nuclearMarkerIndex = derived.nuclearMarkerIndex;
                    processChannels = Arrays.copyOf(derived.processChannels, derived.processChannels.length);
                }

                PipelineDialog gdPA = new PipelineDialog("Process Analysis", PipelineDialog.Phase.ANALYSE);
                gdPA.enableBackButton();
                gdPA.setPrimaryButtonText(NextStepLabels.afterThreeDObjectProcess(runSpatial));
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

        recordThreeDObjectRunParameters(cfg, extractProcessLength, runSpatial);

        ExistingObjectDataMode existingObjectDataMode =
                resolveExistingObjectDataMode(directory, cfg.channelNames);
        if (existingObjectDataMode == ExistingObjectDataMode.CANCEL) {
            String message = "[FLASH] 3D Object Analysis cancelled because existing output handling was cancelled.";
            IJ.log(message);
            recordWarn(message);
            return;
        }
        if (existingObjectDataMode == ExistingObjectDataMode.SKIP) {
            IJ.log("All output CSVs already exist - skipping entire 3D Object Analysis.");
            IJ.showProgress(1.0);
            IJ.showStatus("");
            return;
        }
        boolean extendExistingObjectData =
                existingObjectDataMode == ExistingObjectDataMode.EXTEND;

        if (!prepareSpatialHandoffBeforeAnalysis(directory, cfg.channelNames, runSpatial)) {
            return;
        }

        // Preload all ROIs directly from zip files — no RoiManager needed
        IJ.log("ROI set selections:");
        List<RoiSetData> selectedRoiSetData = new ArrayList<RoiSetData>();
        if (analyseFullImagesWithoutRois) {
            IJ.log("  ROI set '" + FULL_IMAGE_ROI_SET_NAME + "': selected (no ROI restriction)");
            selectedRoiSetData.add(RoiSetData.fullImage());
        } else {
            for (int r = 0; r < roiZips.size(); r++) {
                IJ.log("  ROI set '" + roiSetNames[r] + "': " + (selectedRoiSets[r] ? "selected" : "skipped"));
                if (!selectedRoiSets[r]) continue;
                List<ij.gui.Roi> rois;
                AnalysisRunContext.InputHandle roiInput = recordInputStart(roiZips.get(r), 0);
                long roiStarted = System.currentTimeMillis();
                try {
                    rois = RoiIO.loadRoisFromZip(roiZips.get(r));
                    recordInputEnd(roiInput, "processed", roiStarted);
                } catch (NoClassDefFoundError e) {
                    recordInputEnd(roiInput, "failed", roiStarted);
                    recordError("Failed to load ROI zip " + roiZips.get(r).getAbsolutePath(), e);
                    if (PluginInstallGuard.reportMissingInternalClass("3D Object Analysis", e)) return;
                    throw e;
                }
                selectedRoiSetData.add(new RoiSetData(roiSetNames[r], rois.toArray(new ij.gui.Roi[0])));
            }
        }
        RoiSetData[] roiSets = selectedRoiSetData.toArray(new RoiSetData[0]);

        if (roiSets.length == 0) {
            String message = "No ROIs loaded. Run 'Draw ROIs and Orientate Images' first (must create 2 ROIs per image).";
            IJ.error("3D Object Analysis", message);
            recordWarn(message);
            return;
        }

        IJ.log("ROI sets selected for analysis: " + roiSets.length);
        for (RoiSetData roiSet : roiSets) {
            IJ.log("  - " + roiSetDisplayName(roiSet));
        }

        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        File outDir = objectCsvWriteDir(directory);
        //noinspection ResultOfMethodCallIgnored
        outDir.mkdirs();

        File objectAnalysisDetailsDir = ObjectAnalysisDetailsWriter.analysisDetailsWriteDir(new File(directory));
        //noinspection ResultOfMethodCallIgnored
        objectAnalysisDetailsDir.mkdirs();

        File binDir = activeConfigurationDir(directory);
        ModelCatalog modelCatalog = ModelCatalogIO.read(new File(directory).toPath().toAbsolutePath().normalize());
        try {
            ObjectAnalysisDetailsWriter.writeSegmentationModelsReport(
                    objectAnalysisDetailsDir,
                    modelCatalog,
                    cfg.channelNames,
                    cfg.segmentationMethods);
            File report = ObjectAnalysisDetailsWriter.segmentationModelsReportFile(objectAnalysisDetailsDir);
            if (report.isFile()) {
                recordOutput(report, "txt");
            }
        } catch (Exception e) {
            String message = "Warning: failed writing segmentation model audit details: " + e.getMessage();
            IJ.log(message);
            recordWarn(message);
        }

        SegmentationImageRoot imageRoot = SegmentationImageRoot.forDirectory(directory);
        //noinspection ResultOfMethodCallIgnored
        imageRoot.root.mkdirs();

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

        DeferredImageSupplier supplier;
        int totalImages;
        try {
            supplier = ImageSourceDispatcher.createSupplier(directory);
            totalImages = supplier.getTotalSeries();
            supplier = wrapInputSupplier(directory, supplier);
        } catch (Exception e) {
            String message = "3D Object Analysis: " + e.getMessage();
            IJ.log(message);
            recordWarn(message);
            if (canShowGuiDecisionDialog()) {
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
            } catch (Exception e) {
                String message = "    - WARNING: Could not read series names for 3D object loader progress in "
                        + directory + ": " + e.getMessage();
                IJ.log(message);
                recordWarn(message);
            }
            loader.start();
            IJ.log("Thread split: " + effectiveLoaders + " loaders, " + safeWorkers + " workers");
            compactLog = true;
            processImagesParallel(loader, safeWorkers, directory, cfg, outDir, imageRoot, channelTables,
                    roiSets, extractProcessLength, nuclearMarkerIndex, processChannels,
                    analysisStartTime);
            compactLog = false;
        } else {
            compactLog = false;
            // ── Sequential processing: deferred loading with prefetch ──
            processImagesSequential(supplier, totalImages, directory, cfg, outDir, imageRoot, channelTables,
                    roiSets, extractProcessLength, nuclearMarkerIndex, processChannels,
                    analysisStartTime);
        }

        IJ.showProgress(1.0);
        IJ.showStatus("");

        // Ensure all coloc columns exist on each channel table
        if (doVolumetric) ensureAllColocColumns(cfg, channelTables);
        if (doIntensityColoc) ensureAllIntensityColocColumns(cfg, channelTables);
        if (doCpc) ensureAllCpcColocColumns(cfg, channelTables);

        for (Map.Entry<String, ij.measure.ResultsTable> e : channelTables.entrySet()) {
            try {
                String channelName = e.getKey();
                List<String> keep = buildOrderedObjectColumns(channelName, e.getValue(), cfg,
                        extractProcessLength, processChannels);
                ResultsTableCleaner.keepOnlyColumns(e.getValue(), keep.toArray(new String[0]));

                File out = objectOutputCsv(outDir, channelName);
                writeObjectResultsCsv(directory, out, channelName, e.getValue(), keep,
                        extendExistingObjectData, currentRunId());
                recordOutput(out, "csv");

                // Write macro-style Analysis Details per channel
                int cIndex = cfg.channelNames.indexOf(channelName);
                if (cIndex >= 0) {
                    if (cfg.isStarDist(cIndex)) {
                        ObjectAnalysisDetailsWriter.writeStarDistPerChannel(
                                objectAnalysisDetailsDir,
                                binDir,
                                channelName,
                                cIndex + 1,
                                resolvedModelEntry(modelCatalog, cfg.segmentationMethod(cIndex),
                                        ModelEntry.Engine.STARDIST),
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
                                resolvedModelEntry(modelCatalog, cfg.segmentationMethod(cIndex),
                                        ModelEntry.Engine.CELLPOSE),
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
                        String thrTok = cfg.isEnhancedClassical(cIndex)
                                ? String.valueOf(cfg.getEnhancedClassicalThreshold(cIndex))
                                : (cIndex < cfg.channelThresholds.size() ? cfg.channelThresholds.get(cIndex) : "");
                        String sizeTok = cfg.isEnhancedClassical(cIndex)
                                ? cfg.getEnhancedClassicalMinSize(cIndex) + "-"
                                + (cfg.getEnhancedClassicalMaxSize(cIndex) == Integer.MAX_VALUE
                                ? "Infinity" : String.valueOf(cfg.getEnhancedClassicalMaxSize(cIndex)))
                                : (cIndex < cfg.channelSizes.size() ? cfg.channelSizes.get(cIndex) : "");
                        ObjectAnalysisDetailsWriter.writePerChannel(
                                objectAnalysisDetailsDir,
                                binDir,
                                channelName,
                                cIndex + 1,
                                thrTok,
                                sizeTok,
                                cfg.channelNames.toArray(new String[0]),
                                runSpatial,
                                markerThresholds,
                                null,
                                cfg.segmentationMethod(cIndex)
                        );
                    }
                    recordOutput(new File(objectAnalysisDetailsDir,
                            ObjectAnalysisDetailsWriter.detailsFileName(channelName)), "txt");
                }
            } catch (Exception ex) {
                String message = "Warning: failed saving " + e.getKey() + ": " + ex.getMessage();
                IJ.log(message);
                recordError(message, ex);
            }
        }

        if (runSpatial) {
            AsyncImageSaver.waitForAllWithProgress(parallelThreads);
            IJ.log("--- Running Spatial Distance Analysis ---");
            SpatialAnalysis spatial = createSpatialAnalysisForRun();
            boolean imagesClosed = false;
            try {
                if (spatial.beginPhasedRun(directory)) {
                    LabelImageProvider provider = new InMemoryLabelImageProvider(
                            retainedLabels,
                            new DiskLabelImageProvider(spatial, directory));
                    spatial.runEarlyPhase(directory, provider);
                    closeAllImagesOnly();
                    imagesClosed = true;
                    clearRetainedSpatialLabels();
                    spatial.runLatePhase(directory);
                    IJ.log("Spatial distance analysis complete.");
                } else {
                    closeAllImagesOnly();
                    imagesClosed = true;
                    clearRetainedSpatialLabels();
                }
            } finally {
                spatial.endPhasedRun();
                clearRetainedSpatialLabels();
                if (!imagesClosed) {
                    closeAllImagesOnly();
                }
            }
        } else {
            closeAllImagesOnly();
            clearRetainedSpatialLabels();
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

        // Non-blocking completion signal. A modal "Finished" dialog here would
        // stall unattended batch runs until a human clicked OK.
        IJ.showStatus("3D Object Analysis finished.");
    }

    BinConfig loadBinConfig(String directory) {
        return BinConfigIO.readPartialFromDirectory(directory);
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

    private ExistingObjectDataMode resolveExistingObjectDataMode(String directory,
                                                                 List<String> channelNames) {
        File outDir = objectCsvWriteDir(directory);
        List<File> existingCsvs = existingObjectOutputCsvs(directory, channelNames);
        if (existingCsvs.isEmpty()) {
            return ExistingObjectDataMode.OVERWRITE;
        }

        if (skipExisting && allObjectOutputCsvsExist(directory, channelNames)) {
            return ExistingObjectDataMode.SKIP;
        }
        if (skipExisting) {
            IJ.log("Skip Existing is enabled; existing 3D Object Analysis CSVs will be extended.");
            return ExistingObjectDataMode.EXTEND;
        }

        if (canPromptForExistingObjectData()) {
            ExistingObjectDataMode mode = existingObjectDataPrompt.choose(outDir, existingCsvs);
            return mode == null ? ExistingObjectDataMode.CANCEL : mode;
        }

        IJ.log("Existing 3D Object Analysis CSVs detected; non-interactive run will overwrite them.");
        return ExistingObjectDataMode.OVERWRITE;
    }

    private boolean canPromptForExistingObjectData() {
        return canShowGuiDecisionDialog();
    }

    private boolean canShowGuiDecisionDialog() {
        return canShowGuiDecisionDialog(suppressDialogs, cliConfig,
                GraphicsEnvironment.isHeadless(), imageJUiAvailableForDecisionDialog());
    }

    private boolean imageJUiAvailableForDecisionDialog() {
        return guiDecisionDialogsAvailableForTest == null
                ? IJ.getInstance() != null
                : guiDecisionDialogsAvailableForTest.booleanValue();
    }

    static boolean canShowGuiDecisionDialog(boolean suppressDialogs,
                                            CLIConfig cliConfig,
                                            boolean runtimeHeadless) {
        return canShowGuiDecisionDialog(suppressDialogs, cliConfig, runtimeHeadless,
                IJ.getInstance() != null);
    }

    static boolean canShowGuiDecisionDialog(boolean suppressDialogs,
                                            CLIConfig cliConfig,
                                            boolean runtimeHeadless,
                                            boolean imageJUiAvailable) {
        return !suppressDialogs && cliConfig == null && !runtimeHeadless && imageJUiAvailable;
    }

    private static String existingObjectDataPromptMessage(File outputDir, List<File> existingCsvs) {
        StringBuilder message = new StringBuilder();
        message.append("Existing 3D Object Analysis CSV data was found for this project.");
        if (outputDir != null) {
            message.append("\n\nNew output folder:\n").append(outputDir.getAbsolutePath());
        }
        message.append("\n\nChoose how this run should handle the saved data:\n\n")
                .append("Extend Existing Data: keep the existing rows and append this run's rows.\n")
                .append("Overwrite Existing Data: replace the existing CSVs with this run's rows only.\n\n")
                .append("Existing channel CSVs: ");
        if (existingCsvs == null || existingCsvs.isEmpty()) {
            message.append("none");
        } else {
            int shown = Math.min(existingCsvs.size(), 5);
            for (int i = 0; i < shown; i++) {
                if (i > 0) message.append(", ");
                message.append(existingCsvs.get(i).getName());
            }
            if (existingCsvs.size() > shown) {
                message.append(", ...");
            }
        }
        return message.toString();
    }

    private RoiSetSelection resolveRoiSetsForRun(String directory) {
        RoiSetSelection selection = discoverSavedRoiSets(directory);
        if (selection == null) {
            return null;
        }
        logRoiSets(selection);
        if (selection.hasSavedRois()) {
            return selection;
        }

        NoRoiDecision decision = promptForNoRoiDecision();
        if (decision == NoRoiDecision.DRAW_ROIS) {
            launchRoiDrawingWorkflow(directory);
            selection = discoverSavedRoiSets(directory);
            if (selection == null) {
                return null;
            }
            logRoiSets(selection);
            if (selection.hasSavedRois()) {
                return selection;
            }
            String message = "[FLASH] 3D Object Analysis cancelled because no ROI sets "
                    + "were saved after Draw ROIs and Orientate Images.";
            IJ.log(message);
            recordWarn(message);
            return null;
        }
        if (decision == null || decision == NoRoiDecision.CANCEL) {
            String message = "[FLASH] 3D Object Analysis cancelled because no ROI sets were found.";
            IJ.log(message);
            recordWarn(message);
            return null;
        }

        IJ.log("[FLASH] No ROI sets found. 3D Object Analysis will analyse each full image stack.");
        return RoiSetSelection.fullImages();
    }

    private RoiSetSelection discoverSavedRoiSets(String directory) {
        List<File> roiZips;
        try {
            roiZips = RoiIO.listRoiZipFiles(new File(directory));
        } catch (NoClassDefFoundError e) {
            if (PluginInstallGuard.reportMissingInternalClass("3D Object Analysis", e)) {
                return null;
            }
            throw e;
        }
        String[] roiSetNames = new String[roiZips.size()];
        for (int r = 0; r < roiZips.size(); r++) {
            roiSetNames[r] = roiSetNameForZip(roiZips.get(r));
        }
        return RoiSetSelection.saved(roiZips, roiSetNames);
    }

    private static String roiSetNameForZip(File roiZip) {
        if (roiZip == null) {
            return "";
        }
        return roiZip.getName()
                .replace(" ROIs.zip", "")
                .replace("ROIs.zip", "")
                .replace(".zip", "")
                .trim();
    }

    private static void logRoiSets(RoiSetSelection selection) {
        int count = selection == null || selection.roiZips == null ? 0 : selection.roiZips.size();
        IJ.log("ROI sets found: " + count);
        if (selection == null || selection.roiSetNames == null) {
            return;
        }
        for (String roiSetName : selection.roiSetNames) {
            IJ.log("  - " + roiSetName);
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
                        + "3D Object Analysis can analyse the full image stack for each image, "
                        + "but object counts and measurements will not be restricted to a drawn region.\n\n"
                        + "To restrict analysis to regions of interest, define ROI sets before running this analysis.",
                "3D Object Analysis - No ROI Sets Found",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[0]);

        if (choice == 0) return NoRoiDecision.DRAW_ROIS;
        if (choice == 1) return NoRoiDecision.ANALYSE_FULL_IMAGE;
        return NoRoiDecision.CANCEL;
    }

    private void launchRoiDrawingWorkflow(String directory) {
        roiDrawingWorkflowLauncher.launch(directory);
    }

    private void launchRoiDrawingWorkflowDirect(String directory) {
        IJ.log("[FLASH] Opening Draw ROIs and Orientate Images before 3D Object Analysis.");
        DrawAndSaveROIsAnalysis roiAnalysis = new DrawAndSaveROIsAnalysis();
        roiAnalysis.setSuppressDialogs(false);
        roiAnalysis.setHeadless(false);
        roiAnalysis.setCliConfig(cliConfig);
        roiAnalysis.execute(directory);
    }

    private static String roiSetDisplayName(RoiSetData roiSet) {
        if (roiSet == null || roiSet.fullImage) return FULL_IMAGE_ROI_SET_NAME;
        return roiSet.name;
    }

    private void addThreeDObjectSetupControls(final PipelineDialog dialog,
                                              final String directory,
                                              final BinConfig cfg,
                                              final ChannelIdentities identities,
                                              final ThreeDObjectDialogBindings bindings,
                                              final ThreeDObjectConfigApplier applier) {
        final JComboBox<String> presetCombo = new JComboBox<String>(listThreeDObjectPresetNames(directory));
        if (bindings != null) {
            bindings.presetCombo = presetCombo;
        }
        final JButton savePreset = new JButton("Save as preset...");
        flash.pipeline.ui.FlashIcons.apply(savePreset, flash.pipeline.ui.FlashIcons.save());
        savePreset.setToolTipText("Save the current 3D Object Analysis options as a named preset.");
        savePreset.addActionListener(e -> handleSaveThreeDObjectPreset(directory, cfg, bindings));
        JButton managePreset = new JButton("Manage...");
        managePreset.setToolTipText("Delete saved 3D Object Analysis presets.");
        managePreset.addActionListener(e -> {
            boolean changed = flash.pipeline.ui.config.PresetManagerDialog.manage(
                    presetCombo, new ThreeDObjectPresetIO(new File(directory)), "Manage 3D Object Presets");
            if (changed) {
                refreshThreeDObjectPresetChoice(directory, bindings, OBJECT_PRESET_PLACEHOLDER);
            }
        });
        JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        row.setOpaque(false);
        presetCombo.setMaximumSize(new java.awt.Dimension(260, 24));
        row.add(presetCombo);
        row.add(savePreset);
        row.add(managePreset);
        presetCombo.addActionListener(e -> {
            if (bindings != null && bindings.programmaticChange) {
                return;
            }
            Object selected = presetCombo.getSelectedItem();
            if (selected != null && !OBJECT_PRESET_PLACEHOLDER.equals(String.valueOf(selected))) {
                ThreeDObjectSetupConfig.DerivedConfig derived = loadThreeDObjectPresetConfig(
                        directory, cfg, identities, String.valueOf(selected));
                if (derived != null && applier != null) {
                    applier.apply(String.valueOf(selected), derived);
                }
            }
        });
        dialog.addComponent(row);
    }

    private void applyThreeDObjectConfigToDialog(BinConfig cfg,
                                                 ThreeDObjectSetupConfig.DerivedConfig derived,
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
            setToggle(bindings.doIntensityColocToggle, doIntensityColoc);
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
        if (bindings.primaryLabelUpdater != null) {
            bindings.primaryLabelUpdater.run();
        }
    }

    public LoadedRunParameters.Result applyLoadedParameters(Map<String, Object> parameters) {
        LoadedRunParameters.PresetLoad<ThreeDObjectPreset> load =
                LoadedRunParameters.threeDObjectPreset(parameters);
        applyThreeDObjectDerivedConfig(new BinConfig(), ThreeDObjectSetupConfig.fromPreset(
                new BinConfig(), new ChannelIdentities(Collections.<ChannelIdentities.Entry>emptyList()),
                load.payload));
        LoadedRunParameters.rememberLastResult(load.result);
        return load.result;
    }

    private LoadedRunParameters.Result applyLoadedParameters(Map<String, Object> parameters,
                                                            BinConfig cfg,
                                                            ChannelIdentities identities,
                                                            ThreeDObjectDialogBindings bindings) {
        LoadedRunParameters.PresetLoad<ThreeDObjectPreset> load =
                LoadedRunParameters.threeDObjectPreset(parameters);
        ThreeDObjectSetupConfig.DerivedConfig derived =
                ThreeDObjectSetupConfig.fromPreset(cfg, identities, load.payload);
        applyThreeDObjectConfigToDialog(cfg, derived, bindings, null);
        return load.result;
    }

    private void handleSaveThreeDObjectPreset(String directory,
                                              BinConfig cfg,
                                              ThreeDObjectDialogBindings bindings) {
        if (!canShowGuiDecisionDialog()) return;
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
                isSelected(bindings.doIntensityColocToggle),
                isSelected(bindings.extractProcessLengthToggle),
                isSelected(bindings.runSpatialToggle),
                isSelected(bindings.classicalCentroidFilterToggle),
                readFirstThreshold(bindings.thresholdFields),
                selectedProcessMarkerNames(cfg),
                selectedNuclearMarkerNames(cfg));
    }

    /**
     * Capture the confirmed dialog settings into the run record so a later
     * "Load settings from previous run" can restore them. GUI runs open the
     * record with an empty parameter map; without this the loader has nothing
     * to apply and falls back to defaults. Keys mirror {@link ThreeDObjectPreset#toJsonObject()}
     * so {@code LoadedRunParameters.THREE_D_OBJECT_KEYS} recognises them.
     */
    private void recordThreeDObjectRunParameters(BinConfig cfg,
                                                 boolean extractProcessLength,
                                                 boolean runSpatial) {
        if (runRecordContext == null) {
            return;
        }
        try {
            double colocThresholdPercent = 30.0;
            for (Double value : markerThresholds.values()) {
                if (value != null) {
                    colocThresholdPercent = value.doubleValue();
                    break;
                }
            }
            ThreeDObjectPreset preset = new ThreeDObjectPreset(
                    "GUI 3D Object run",
                    "Captured from the 3D Object Analysis dialog",
                    ThreeDObjectPreset.CURRENT_LIBRARY_VERSION,
                    doVolumetric, doCpc, doIntensityColoc,
                    extractProcessLength, runSpatial, classicalCentroidFilter,
                    colocThresholdPercent,
                    selectedProcessMarkerNames(cfg),
                    selectedNuclearMarkerNames(cfg));
            runRecordContext.recordParameters(ParameterSnapshot.fromAnalysisPresetMap(
                    "ThreeDObjectAnalysis", preset.toJsonObject()));
        } catch (RuntimeException e) {
            IJ.log("[FLASH] Could not capture 3D Object run parameters: " + e.getMessage());
        }
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

    boolean prepareSpatialHandoffBeforeAnalysis(String directory,
                                                List<String> channelNames,
                                                boolean runSpatial) {
        if (!runSpatial) {
            wizardSpatialConfig = null;
            return true;
        }
        // The analysis-level headless flag means "hide image windows", not "skip setup UI".
        // Only suppress the pre-run Spatial options dialog for genuinely non-interactive runs.
        if (wizardSpatialConfig != null || !canShowGuiDecisionDialog()) {
            return true;
        }
        SpatialSetupConfig.DerivedConfig spatialConfig =
                spatialOptionsDialogLauncher.launch(
                        directory, channelNames, markerThresholds, doVolumetric, doCpc);
        if (spatialConfig == null) {
            IJ.log("[FLASH] 3D Object Analysis cancelled because Spatial Analysis options were cancelled.");
            if (canShowGuiDecisionDialog()) {
                IJ.showMessage("3D Object Analysis",
                        "Spatial Analysis options were cancelled.\n3D Object Analysis has not started.");
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
        spatialAnalysis.setVerboseLogging(verboseLogging);
        spatialAnalysis.setCliConfig(cliConfig);
        if (wizardSpatialConfig != null) {
            spatialAnalysis.setWizardConfig(wizardSpatialConfig);
        }
        return spatialAnalysis;
    }

    private ThreeDObjectSetupConfig.DerivedConfig loadThreeDObjectPresetConfig(String directory,
                                                                         BinConfig cfg,
                                                                         ChannelIdentities identities,
                                                                         String presetName) {
        try {
            ThreeDObjectPreset preset = new ThreeDObjectPresetIO(new File(directory)).load(presetName);
            return ThreeDObjectSetupConfig.fromPreset(cfg, identities, preset);
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
        ThreeDObjectSetupConfig.DerivedConfig derived = null;
        if (object.getPresetName() != null && !object.getPresetName().trim().isEmpty()) {
            derived = loadThreeDObjectPresetConfig(directory, cfg, identities, object.getPresetName());
        }
        if (derived == null) {
            derived = ThreeDObjectSetupConfig.deriveConfig(cfg, identities,
                    Collections.<String, Object>emptyMap(), Collections.<String>emptyList());
        }
        if (object.getDoVolumetric() != null) {
            derived.doVolumetric = object.getDoVolumetric().booleanValue();
        }
        if (object.getDoCpc() != null) {
            derived.doCpc = object.getDoCpc().booleanValue();
        }
        if (object.getDoIntensityColoc() != null) {
            derived.doIntensityColoc = object.getDoIntensityColoc().booleanValue();
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

    private void applyThreeDObjectDerivedConfig(BinConfig cfg, ThreeDObjectSetupConfig.DerivedConfig derived) {
        if (derived == null) return;
        doVolumetric = derived.doVolumetric;
        doCpc = derived.doCpc;
        doIntensityColoc = derived.doIntensityColoc;
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
        void apply(String selectedPresetName, ThreeDObjectSetupConfig.DerivedConfig derived);
    }

    private static final class ThreeDObjectDialogBindings {
        boolean programmaticChange;
        JComboBox<String> presetCombo;
        ToggleSwitch useDeconvolvedInputToggle;
        ToggleSwitch doVolumetricToggle;
        ToggleSwitch doCpcToggle;
        ToggleSwitch doIntensityColocToggle;
        ToggleSwitch extractProcessLengthToggle;
        ToggleSwitch runSpatialToggle;
        ToggleSwitch classicalCentroidFilterToggle;
        List<JTextField> thresholdFields = new ArrayList<JTextField>();
        Runnable primaryLabelUpdater;
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
            if (roiSet != null && roiSet.fullImage) {
                IJ.log("  ROI set '" + FULL_IMAGE_ROI_SET_NAME + "': full-image analysis (no ROI pair validation)");
                continue;
            }
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
            SegmentationImageRoot imageRoot,
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
        // Geometry is driven ONLY by the original-coordinate (index-0) ROI.
        // The index-1 "cropped" ROI is a top-left-shifted presentation artifact;
        // feeding it to the centroid filter or crop/mask is the historical bug
        // this RegionMask seam prevents. See RegionMask / RegionMaskTest.
        RegionMask region = roiSet == null ? null : RegionMask.from(roiSet.cloneRoi(roiIdx));
        String roiBase = roiSet == null ? "" : roiSet.name;
        String hemisphere = parts == null ? "" : parts.hemisphere;
        String seriesRegionLabel = parts == null
                ? ""
                : parts.csvRegion();
        String roiLabel = parts == null
                ? (scnIndex > 0 && !endsWithDigit(roiBase) ? roiBase + scnIndex : roiBase)
                : parts.analysisRegionLabel(roiBase, scnIndex);

        if (!compactLog) {
            IJ.log("  > ROI set: " + roiBase);
        }
        if (verboseLogging) {
            IJ.log("  [DEBUG] ROI set '" + roiBase + "' indices: " + roiIdx + " and " + (roiIdx + 1));
        }

        try {
            boolean[] channelHasObjects =
                    run3DObjectsCounterPerChannel(directory, cfg, imp, outDir, imageRoot, channelTables, scnIndex,
                            animalName, parts,
                            extractProcessLength, nuclearMarkerIndex, processChannels,
                            region, seriesRegionLabel, roiLabel, roiBase);

            IJ.log("  > Colocalization");
            if (doVolumetric) {
                appendColocColumns(cfg, channelHasObjects, channelTables, scnIndex, animalName,
                        hemisphere, seriesRegionLabel, roiLabel);
            }
            if (doCpc) {
                appendCpcColocColumns(cfg, channelHasObjects, channelTables, scnIndex, animalName,
                        hemisphere, seriesRegionLabel, roiLabel);
            }
            if (doIntensityColoc) {
                appendIntensityColocColumns(cfg, channelHasObjects, channelTables, scnIndex, animalName,
                        hemisphere, seriesRegionLabel, roiLabel);
            }

            if (extractProcessLength && processChannels != null) {
                processLengthExtractionWithTables(cfg, channelTables, imp, scnIndex, animalName,
                        hemisphere, seriesRegionLabel, roiLabel, processChannels, nuclearMarkerIndex);
            }

            saveObjectsImages(cfg, imageRoot, animalName, hemisphere, roiLabel);
        } finally {
            clearRegistry();
        }
    }

    private void processImagesSequential(
            final DeferredImageSupplier supplier, final int totalImages,
            String directory, BinConfig cfg,
            File outDir, SegmentationImageRoot imageRoot,
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
                    String message = "ERROR: Failed to load prefetched image " + (i + 1) + ": " + e.getMessage();
                    IJ.log(message);
                    recordError(message, e);
                    nextImage = null;
                    continue;
                }
                nextImage = null;
            } else {
                IJ.log("Loading image " + (i + 1) + "/" + totalImages + "...");
                try {
                    imp = supplier.openSeries(i);
                } catch (Exception e) {
                    String message = "ERROR: Failed to open image " + (i + 1) + ": " + e.getMessage();
                    IJ.log(message);
                    recordError(message, e);
                    continue;
                }
            }
            if (imp == null) continue;
            imp = applyConfiguredZSliceSubset(cfg, i, imp, "3D Object Analysis");

            // Write calibration from the first successfully-loaded image
            if (calibrationWritten.compareAndSet(false, true)) {
                CalibrationIO.writeFromImage(outDir, imp);
                recordOutputIfExists(new File(outDir, "calibration.properties"), "properties");
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
                            processRoiSetFromFullCount(fullData, directory, cfg, imp, outDir, imageRoot,
                                    channelTables, i, scnIndex, animalName, parts,
                                    extractProcessLength, nuclearMarkerIndex, processChannels, roiSet);
                        }
                    } finally {
                        cleanupFullImageData(fullData);
                    }
                } else {
                    // Original path: count per ROI set
                    for (RoiSetData roiSet : roiSets) {
                        processRoiSetForImage(directory, cfg, imp, outDir, imageRoot, channelTables,
                                i, scnIndex, animalName, parts,
                                extractProcessLength, nuclearMarkerIndex, processChannels, roiSet);
                    }
                }

            } catch (Exception ex) {
                IJ.handleException(ex);
                recordError("3D Object Analysis failed while processing image " + (i + 1), ex);
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

            }
        }
        prefetcher.shutdown();
    }

    // ── Parallel image processing ──

    private void processImagesParallel(
            final BoundedImageLoader loader,
            final int nThreads,
            final String directory, final BinConfig cfg,
            final File outDir, final SegmentationImageRoot imageRoot,
            final Map<String, ij.measure.ResultsTable> channelTables,
            final RoiSetData[] roiSets,
            final boolean extractProcessLength, final int nuclearMarkerIndex, final boolean[] processChannels,
            final long analysisStartTime) {

        final int total = loader.totalToLoad();
        final AtomicInteger completed = new AtomicInteger(0);
        final List<Throwable> failures =
                Collections.synchronizedList(new ArrayList<Throwable>());
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
                        int scnIndex = idx + 1;
                        String imgTitle = imp == null ? "<null image>" : imp.getTitle();
                        String partLabel = imgTitle;
                        imp = applyConfiguredZSliceSubset(cfg, idx, imp, "3D Object Analysis");
                        imgTitle = imp == null ? imgTitle : imp.getTitle();

                        // Write calibration from the first image (thread-safe)
                        if (calibrationWritten.compareAndSet(false, true)) {
                            CalibrationIO.writeFromImage(outDir, imp);
                            recordOutputIfExists(new File(outDir, "calibration.properties"), "properties");
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
                            ResolvedImageMetadata metadata = ImageOrientationResolver.resolve(
                                    directory, imgTitle, idx + 1);
                            NameParts parts = metadata.toNameParts();
                            String animalName = parts.animal;

                            // Worker start log with short name and channels
                            String workerTag = effectiveThreads > 1
                                    ? "Worker " + workerNum : "Worker";
                            partLabel = parts.displayLabel();
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
                                        processRoiSetFromFullCount(fullData, directory, cfg, imp, outDir, imageRoot,
                                                localChannelTables, idx, scnIndex, animalName, parts,
                                                extractProcessLength, nuclearMarkerIndex, processChannels, roiSet);
                                    }
                                } finally {
                                    cleanupFullImageData(fullData);
                                }
                            } else {
                                for (RoiSetData roiSet : roiSets) {
                                    processRoiSetForImage(directory, cfg, imp, outDir, imageRoot, localChannelTables,
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
                            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                            RuntimeException contextual = new RuntimeException(
                                    "3D Object Analysis failed for image " + scnIndex + "/" + total
                                            + " title='" + imgTitle + "' label='" + partLabel + "': " + msg,
                                    e);
                            failures.add(contextual);
                            IJ.log("[" + scnIndex + "/" + total + "] ERROR: " + contextual.getMessage());
                            recordError(contextual.getMessage(), contextual);
                            int done = completed.incrementAndGet();
                            IJ.showProgress(done, total);
                            IJ.showStatus("Processing " + done + "/" + total + " (failed)");
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
                recordError("Parallel processing error: " + msg, cause);
            }
        }
        pool.shutdown();
        if (!failures.isEmpty()) {
            throw buildParallelFailure("3D Object Analysis failed for "
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

    /** Save objects images (label maps) after colocalization. */
    private void saveObjectsImages(BinConfig cfg, SegmentationImageRoot imageRoot, String animalName, String hemisphere, String region) {
        String hemiRegion = buildFileSuffix(hemisphere, region, animalName);
        File perAnimal = imageRoot.animalDir(animalName);
        for (int c = 0; c < cfg.numChannels(); c++) {
            String chName = cfg.channelNames.get(c);
            ImagePlus objImg = getRegisteredImage(chName + "_objects");
            if (objImg != null) {
                String safeChName = ChannelFilenameCodec.toSafe(chName);
                String objFileName = safeChName + "_objects" + (hemiRegion.isEmpty() ? "" : "_" + hemiRegion) + ".tif";
                File labelOutput = new File(perAnimal, objFileName);
                AsyncImageSaver.saveAsTiffAsync(objImg, labelOutput.getAbsolutePath());
                recordOutput(labelOutput, "tiff");
                retainSpatialLabelIfNeeded(chName, SectionKey.of(animalName, hemiRegion), objImg);
            }
        }
    }

    private void retainSpatialLabelIfNeeded(String channelName, SectionKey section, ImagePlus labelImage) {
        if (!shouldRetainSpatialLabels() || labelImage == null || section == null) {
            return;
        }

        ImagePlus retained = null;
        try {
            retained = ImageOps.duplicateThreadSafe(labelImage);
            if (retained != null) {
                retained.setTitle(labelImage.getTitle());
            }
        } catch (Exception e) {
            IJ.log("  [Spatial] Could not retain in-memory label for "
                    + channelName + " / " + section + ": " + e.getMessage());
            return;
        }

        synchronized (retainedLabels) {
            Map<String, ImagePlus> perChannel = retainedLabels.get(section);
            if (perChannel == null) {
                perChannel = new LinkedHashMap<String, ImagePlus>();
                retainedLabels.put(section, perChannel);
            }
            ImagePlus previous = perChannel.put(channelName, retained);
            if (previous != null && previous != retained) {
                closeQuietly(previous);
            }
        }
    }

    private boolean shouldRetainSpatialLabels() {
        return wizardRunSpatial
                && wizardSpatialConfig != null
                && wizardSpatialConfig.anyEarlyPhaseToggleOn();
    }

    private void clearRetainedSpatialLabels() {
        synchronized (retainedLabels) {
            for (Map<String, ImagePlus> perChannel : retainedLabels.values()) {
                if (perChannel == null) {
                    continue;
                }
                for (ImagePlus image : perChannel.values()) {
                    closeQuietly(image);
                }
            }
            retainedLabels.clear();
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
                CsvTableIO.writeResultsTableCsv(objectTempCsv(objectCsvWriteDir(directory), ch, i), t, keep,
                        currentRunId());
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
        keep.addAll(AtlasRegionColumns.COLUMNS);
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
                keep.add(volColocCol(channelName, other));
            }
            if (doCpc) {
                keep.add(channelName + "_CPCColoc_" + other);
                keep.add(channelName + "_CPCContains_" + other);
            }
            if (doIntensityColoc) {
                keep.add(objPearsonCol(channelName, other));
                keep.add(objMandersM1Col(channelName, other));
                keep.add(objMandersM2Col(channelName, other));
                keep.add(objCostesTaCol(channelName, other));
                keep.add(objCostesTbCol(channelName, other));
                keep.add(objPearsonThresholdedCol(channelName, other));
                keep.add(objCostesPCol(channelName, other));
                keep.add(roiCostesTaCol(channelName, other));
                keep.add(roiCostesTbCol(channelName, other));
                keep.add(roiCostesPCol(channelName, other));
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
        final boolean enhancedClassical;
        final List<MorphPredicate> morphPredicates;

        ChannelFilterResult(int channelIndex, String channelName, ImagePlus unfiltered,
                            ImagePlus filtered, Double threshold, int minSizeVox, int maxSizeVox,
                            boolean skipped, String skipReason) {
            this(channelIndex, channelName, unfiltered, filtered, threshold, minSizeVox, maxSizeVox,
                    skipped, skipReason, false, "", null, "", null, false,
                    Collections.<MorphPredicate>emptyList());
        }

        ChannelFilterResult(int channelIndex, String channelName, ImagePlus unfiltered,
                            ImagePlus filtered, Double threshold, int minSizeVox, int maxSizeVox,
                            boolean skipped, String skipReason,
                            boolean labelImageSegmentation, String segmentationName, ImagePlus labelImage,
                            String segmentationSummary,
                            ImagePlus preDetectionFiltered) {
            this(channelIndex, channelName, unfiltered, filtered, threshold, minSizeVox, maxSizeVox,
                    skipped, skipReason, labelImageSegmentation, segmentationName, labelImage,
                    segmentationSummary, preDetectionFiltered, false,
                    Collections.<MorphPredicate>emptyList());
        }

        ChannelFilterResult(int channelIndex, String channelName, ImagePlus unfiltered,
                            ImagePlus filtered, Double threshold, int minSizeVox, int maxSizeVox,
                            boolean skipped, String skipReason,
                            boolean labelImageSegmentation, String segmentationName, ImagePlus labelImage,
                            String segmentationSummary,
                            ImagePlus preDetectionFiltered,
                            boolean enhancedClassical,
                            List<MorphPredicate> morphPredicates) {
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
            this.enhancedClassical = enhancedClassical;
            this.morphPredicates = morphPredicates == null
                    ? Collections.<MorphPredicate>emptyList()
                    : Collections.unmodifiableList(new ArrayList<MorphPredicate>(morphPredicates));
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
            SegmentationImageRoot imageRoot,
            Map<String, ij.measure.ResultsTable> channelTables,
            int scnIndex,
            String animalName,
            NameParts parts,
            boolean extractProcessLength,
            int nuclearMarkerIndex,
            boolean[] processChannels,
            RegionMask region,
            String regionLabel,
            String roiLabel,
            String roiSetName
    ) {
        String hemisphere = parts == null ? "" : parts.hemisphere;
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
        final SegmentationMethod[] segmentationMethods = new SegmentationMethod[n];
        final boolean[] isEnhancedClassical = new boolean[n];
        @SuppressWarnings("unchecked")
        final List<MorphPredicate>[] enhancedMorphPredicates = new List[n];
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
        final String[] sdModelKey = new String[n];
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
            SegmentationMethod segmentationMethod = cfg.segmentationMethod(c);
            segmentationMethods[c] = segmentationMethod;
            isEnhancedClassical[c] = segmentationMethod.isEnhancedClassical();
            enhancedMorphPredicates[c] = isEnhancedClassical[c]
                    ? SegmentationMethod.morphPredicates(segmentationMethod)
                    : Collections.<MorphPredicate>emptyList();
            if (isEnhancedClassical[c]) {
                thresholdTokens[c] = String.valueOf((int) Math.round(SegmentationMethod.threshold(segmentationMethod)));
                int maxSize = SegmentationMethod.maxSize(segmentationMethod);
                sizeTokens[c] = SegmentationMethod.minSize(segmentationMethod) + "-"
                        + (maxSize == Integer.MAX_VALUE ? "Infinity" : String.valueOf(maxSize));
            }
            isStarDist[c] = cfg.isStarDist(c);
            isCellpose[c] = cfg.isCellpose(c);
            SegmentationMethod cellposeSettingsMethod = segmentationMethod;
            sdProbThresh[c] = cfg.getStarDistProbThresh(c);
            sdNmsThresh[c] = cfg.getStarDistNmsThresh(c);
            sdLinkingMaxDistance[c] = cfg.getStarDistLinkingMaxDistance(c);
            sdGapClosingMaxDistance[c] = cfg.getStarDistGapClosingMaxDistance(c);
            sdMaxFrameGap[c] = cfg.getStarDistMaxFrameGap(c);
            sdAreaMin[c] = cfg.getStarDistAreaMin(c);
            sdAreaMax[c] = cfg.getStarDistAreaMax(c);
            sdQualityMin[c] = cfg.getStarDistQualityMin(c);
            sdIntensityMin[c] = cfg.getStarDistIntensityMin(c);
            sdModelKey[c] = SegmentationMethod.starDistModelKey(cfg.segmentationMethod(c));
            cpModel[c] = SegmentationMethod.cellposeModelKey(cellposeSettingsMethod);
            cpDiameter[c] = SegmentationMethod.cellposeDiameter(cellposeSettingsMethod);
            cpFlowThreshold[c] = SegmentationMethod.cellposeFlow(cellposeSettingsMethod);
            cpCellprobThreshold[c] = SegmentationMethod.cellposeCellprob(cellposeSettingsMethod);
            cpUseGpu[c] = SegmentationMethod.cellposeUseGpu(cellposeSettingsMethod);
            cpSecondChannel[c] = SegmentationMethod.cellposeChan2(cellposeSettingsMethod);
        }

        // Use a thread pool for parallel filtering (cap at 4 to avoid memory pressure)
        int filterThreads = Math.min(n, 4);
        final ImagePlus[] cellposeCompanionSources = snapshotCellposeCompanionSources(chans, cpSecondChannel, channelNames);
        if (filterThreads > 1) {
            ExecutorService filterPool = Executors.newFixedThreadPool(filterThreads);
            List<Future<?>> filterFutures = new ArrayList<Future<?>>();

            try {
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
                                    segmentationMethods[ci],
                                    isEnhancedClassical[ci], enhancedMorphPredicates[ci],
                                    isStarDist[ci], sdProbThresh[ci], sdNmsThresh[ci],
                                    sdLinkingMaxDistance[ci], sdGapClosingMaxDistance[ci], sdMaxFrameGap[ci],
                                    sdAreaMin[ci], sdAreaMax[ci], sdQualityMin[ci], sdIntensityMin[ci],
                                    sdModelKey[ci], new File(directory),
                                    isCellpose[ci], cpModel[ci], cpDiameter[ci], cpFlowThreshold[ci],
                                    cpCellprobThreshold[ci], cpUseGpu[ci],
                                    cpSecondChannel[ci],
                                    cpSecondChannel[ci] >= 0 && cpSecondChannel[ci] < cellposeCompanionSources.length ? cellposeCompanionSources[cpSecondChannel[ci]] : null,
                                    cpSecondChannel[ci] >= 0 && cpSecondChannel[ci] < channelNames.length ? channelNames[cpSecondChannel[ci]] : null,
                                    cpSecondChannel[ci] >= 0 && cpSecondChannel[ci] < filterFilenames.length ? filterFilenames[cpSecondChannel[ci]] : null,
                                    region, roiSetName);
                        } finally {
                            ParallelContext.exitParallel();
                        }
                    }
                }));
            }

            // Wait for all filter tasks to complete
            waitForFilterFutures(filterFutures);
            } finally {
                filterPool.shutdown();
            }
        } else {
            // Single channel — no need for thread pool overhead
            for (int c = 0; c < n; c++) {
                filterResults[c] = filterAndThresholdChannel(
                        c, channelNames[c], chans[c], binDir, filterFilenames[c],
                        thresholdTokens[c], sizeTokens[c],
                        extractProcessLength, nuclearMarkerIndex, processChannels, n,
                        segmentationMethods[c],
                        isEnhancedClassical[c], enhancedMorphPredicates[c],
                        isStarDist[c], sdProbThresh[c], sdNmsThresh[c],
                        sdLinkingMaxDistance[c], sdGapClosingMaxDistance[c], sdMaxFrameGap[c],
                        sdAreaMin[c], sdAreaMax[c], sdQualityMin[c], sdIntensityMin[c],
                        sdModelKey[c], new File(directory),
                        isCellpose[c], cpModel[c], cpDiameter[c], cpFlowThreshold[c],
                        cpCellprobThreshold[c], cpUseGpu[c],
                        cpSecondChannel[c],
                        cpSecondChannel[c] >= 0 && cpSecondChannel[c] < cellposeCompanionSources.length ? cellposeCompanionSources[cpSecondChannel[c]] : null,
                        cpSecondChannel[c] >= 0 && cpSecondChannel[c] < channelNames.length ? channelNames[cpSecondChannel[c]] : null,
                        cpSecondChannel[c] >= 0 && cpSecondChannel[c] < filterFilenames.length ? filterFilenames[cpSecondChannel[c]] : null,
                        region, roiSetName);
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
                                fr.minSizeVox,
                                fr.maxSizeVox,
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
                            IJ.log("    [DEBUG] Threshold token from configuration: '" + thresholdTokens[c] + "' -> resolved: " + fr.threshold);
                        }
                        IJ.log("    - Size range: " + fr.minSizeVox + "-" + (fr.maxSizeVox == Integer.MAX_VALUE ? "Infinity" : fr.maxSizeVox));
                    }

                    boolean excludeOnEdges = false;
                    long counterStart = System.currentTimeMillis();

                    if (fr.enhancedClassical) {
                        ImagePlus enhancedLabels = new EnhancedClassicalRunner().run(
                                fr.filtered,
                                new EnhancedClassicalParameters(
                                        (int) Math.round(fr.threshold),
                                        fr.minSizeVox,
                                        fr.maxSizeVox,
                                        fr.morphPredicates,
                                        fr.unfiltered,
                                        new EnhancedClassicalParameters.WarningSink() {
                                            @Override public void warn(String message) {
                                                IJ.log(message);
                                            }
                                        }));
                        ObjectsCounter3DWrapper.Result recounted = ocWrapper.fromLabelImage(
                                enhancedLabels,
                                fr.unfiltered,
                                0,
                                Integer.MAX_VALUE,
                                true,
                                true);
                        ResultsTable stats = EnhancedClassicalRunner.statsProperty(enhancedLabels);
                        if (stats == null) stats = recounted.getStatistics();
                        ImagePlus objectMap = recounted.getObjectsMap() == null
                                ? enhancedLabels
                                : recounted.getObjectsMap();
                        if (objectMap != null) {
                            objectMap.setProperty(EnhancedClassicalRunner.OBJECT_STATS_PROPERTY, stats);
                        }
                        if (enhancedLabels != null && enhancedLabels != objectMap) {
                            enhancedLabels.changes = false;
                            enhancedLabels.close();
                            enhancedLabels.flush();
                        }
                        res = new ObjectsCounter3DWrapper.Result(
                                stats,
                                objectMap,
                                recounted.getMaskedImage(),
                                stats != null && stats.size() > 0 && objectMap != null);
                    } else if (useNative) {
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
                    IJ.log("    " + (fr.enhancedClassical ? "EnhancedClassical" : "3DObjectCounter")
                            + " [" + channelName + "]: "
                            + objectCount + " objects detected (" + counterMs + " ms)");
                    if (!compactLog && verboseLogging) {
                        IJ.log("    [DEBUG] Size range (voxels): " + fr.minSizeVox + "-" + fr.maxSizeVox);
                        IJ.log("    [DEBUG] Redirect target: " + channelName + "_unfiltered");
                        IJ.log("    [DEBUG] Objects map present: " + (res.getObjectsMap() != null));
                        IJ.log("    [DEBUG] Masked image present: " + (res.getMaskedImage() != null));
                        IJ.log("    [DEBUG] Counter mode: " + (useNative ? "native mcib3d" : "legacy Counter3D"));
                    }

                    // ROI centroid filter (centroid mode only). Images are still uncropped
                    // here, so the filter runs in ORIGINAL image coordinates against the
                    // region. RegionMask is built from index 0 only; the top-left-shifted
                    // index-1 ("cropped") ROI must never drive geometry. In crop-first mode
                    // the image was already cropped+masked to the region in Phase A, so every
                    // surviving object is in-region by construction and no filter is applied.
                    if (classicalCentroidFilter && region != null && res.getObjectsMap() != null) {
                        int beforeFilter = objectCount;
                        int removed = region.filterByCentroid(res.getObjectsMap());
                        int afterFilter = beforeFilter - removed;
                        IJ.log("    3DObjectCounter [" + channelName + "] ROI filter: "
                                + beforeFilter + " \u2192 " + afterFilter
                                + " objects (" + removed + " outside " + roiSetName + " ROI removed)");

                        // Choice (a): keep whole objects — crop to the region bounding
                        // box only (no shape mask). Coordinates become region-relative.
                        region.cropToBounds(fr.filtered);
                        region.cropToBounds(fr.unfiltered);
                        region.cropToBounds(res.getObjectsMap());
                        if (res.getMaskedImage() != null) {
                            region.cropToBounds(res.getMaskedImage());
                        }

                        // Re-derive statistics from the (now-cropped) label image
                        if (useNative) {
                            res = ocWrapper.fromLabelImage(
                                    res.getObjectsMap(),
                                    fr.unfiltered,
                                    fr.minSizeVox,
                                    fr.maxSizeVox,
                                    true, true);
                            if (fr.enhancedClassical) {
                                res = withEnhancedMorphStats(res, fr.unfiltered, fr.morphPredicates);
                            }
                            // QC invariant: cropping relocates objects, it must not
                            // destroy or create them. A mismatch flags a coordinate
                            // problem (e.g. the wrong ROI driving the geometry).
                            int finalCount = res.getStatistics() == null ? 0 : res.getStatistics().size();
                            logRegionCropQc(channelName, roiSetName, afterFilter, finalCount);
                        }
                    }
                }

                ImagePlus objects = res.getObjectsMap();
                if (objects != null) {
                    registerImage(channelName + "_objects", objects, false);
                }

                channelHasObjects[c] = res.isFoundObjects() && objects != null;

                appendStatsToChannelTable(res.getStatistics(), channelTables.get(channelName), scnIndex, animalName, hemisphere, regionLabel, roiLabel);

                // Save segmentation comparison images under Results/Analysis Images/Segmentation/<animal>/.
                File segmentationAnimalDir = imageRoot.animalDir(animalName);
                //noinspection ResultOfMethodCallIgnored
                segmentationAnimalDir.mkdirs();

                // Build suffix: hemisphere_region, or just one, or animal name for non-convention files
                String maskedSuffix = buildFileSuffix(hemisphere, roiLabel, animalName);
                String safeChannelName = ChannelFilenameCodec.toSafe(channelName);
                if (res.getMaskedImage() != null) {
                    File maskedOutput = new File(segmentationAnimalDir, safeChannelName + "_Masked"
                            + (maskedSuffix.isEmpty() ? "" : "_" + maskedSuffix) + ".tif");
                    AsyncImageSaver.saveAsTiffAsync(res.getMaskedImage(),
                            maskedOutput.getAbsolutePath());
                    recordOutput(maskedOutput, "tiff");
                }

                // Save the pre-detection filtered image beside the masked and label-map outputs.
                if (fr.preDetectionFiltered != null) {
                    File filteredOutput = new File(segmentationAnimalDir, safeChannelName + "_Filtered"
                            + (maskedSuffix.isEmpty() ? "" : "_" + maskedSuffix) + ".tif");
                    AsyncImageSaver.saveAsTiffAsync(fr.preDetectionFiltered,
                            filteredOutput.getAbsolutePath());
                    recordOutput(filteredOutput, "tiff");
                }

                // Objects image saving is deferred until after colocalization.

            } catch (Throwable t) {
                IJ.log("ERROR in channel " + channelName
                        + " for image '" + imp.getTitle() + "'"
                        + ", animal='" + animalName + "', region='" + regionLabel
                        + "', ROI='" + roiLabel + "': " + t);
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
        final SegmentationMethod[] segmentationMethods = new SegmentationMethod[n];
        final boolean[] isEnhancedClassical = new boolean[n];
        @SuppressWarnings("unchecked")
        final List<MorphPredicate>[] enhancedMorphPredicates = new List[n];
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
        final String[] sdModelKey = new String[n];
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
            SegmentationMethod segmentationMethod = cfg.segmentationMethod(c);
            segmentationMethods[c] = segmentationMethod;
            isEnhancedClassical[c] = segmentationMethod.isEnhancedClassical();
            enhancedMorphPredicates[c] = isEnhancedClassical[c]
                    ? SegmentationMethod.morphPredicates(segmentationMethod)
                    : Collections.<MorphPredicate>emptyList();
            if (isEnhancedClassical[c]) {
                thresholdTokens[c] = String.valueOf((int) Math.round(SegmentationMethod.threshold(segmentationMethod)));
                int maxSize = SegmentationMethod.maxSize(segmentationMethod);
                sizeTokens[c] = SegmentationMethod.minSize(segmentationMethod) + "-"
                        + (maxSize == Integer.MAX_VALUE ? "Infinity" : String.valueOf(maxSize));
            }
            isStarDist[c] = cfg.isStarDist(c);
            isCellpose[c] = cfg.isCellpose(c);
            SegmentationMethod cellposeSettingsMethod = segmentationMethod;
            sdProbThresh[c] = cfg.getStarDistProbThresh(c);
            sdNmsThresh[c] = cfg.getStarDistNmsThresh(c);
            sdLinkingMaxDistance[c] = cfg.getStarDistLinkingMaxDistance(c);
            sdGapClosingMaxDistance[c] = cfg.getStarDistGapClosingMaxDistance(c);
            sdMaxFrameGap[c] = cfg.getStarDistMaxFrameGap(c);
            sdAreaMin[c] = cfg.getStarDistAreaMin(c);
            sdAreaMax[c] = cfg.getStarDistAreaMax(c);
            sdQualityMin[c] = cfg.getStarDistQualityMin(c);
            sdIntensityMin[c] = cfg.getStarDistIntensityMin(c);
            sdModelKey[c] = SegmentationMethod.starDistModelKey(cfg.segmentationMethod(c));
            cpModel[c] = SegmentationMethod.cellposeModelKey(cellposeSettingsMethod);
            cpDiameter[c] = SegmentationMethod.cellposeDiameter(cellposeSettingsMethod);
            cpFlowThreshold[c] = SegmentationMethod.cellposeFlow(cellposeSettingsMethod);
            cpCellprobThreshold[c] = SegmentationMethod.cellposeCellprob(cellposeSettingsMethod);
            cpUseGpu[c] = SegmentationMethod.cellposeUseGpu(cellposeSettingsMethod);
            cpSecondChannel[c] = SegmentationMethod.cellposeChan2(cellposeSettingsMethod);
        }

        // Parallel filter (same logic as run3DObjectsCounterPerChannel Phase A)
        int filterThreads = Math.min(n, 4);
        final ImagePlus[] cellposeCompanionSources = snapshotCellposeCompanionSources(chans, cpSecondChannel, channelNames);
        if (filterThreads > 1) {
            ExecutorService filterPool = Executors.newFixedThreadPool(filterThreads);
            List<Future<?>> filterFutures = new ArrayList<Future<?>>();
            try {
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
                                    segmentationMethods[ci],
                                    isEnhancedClassical[ci], enhancedMorphPredicates[ci],
                                    isStarDist[ci], sdProbThresh[ci], sdNmsThresh[ci],
                                    sdLinkingMaxDistance[ci], sdGapClosingMaxDistance[ci], sdMaxFrameGap[ci],
                                    sdAreaMin[ci], sdAreaMax[ci], sdQualityMin[ci], sdIntensityMin[ci],
                                    sdModelKey[ci], new File(directory),
                                    isCellpose[ci], cpModel[ci], cpDiameter[ci], cpFlowThreshold[ci],
                                    cpCellprobThreshold[ci], cpUseGpu[ci],
                                    cpSecondChannel[ci],
                                    cpSecondChannel[ci] >= 0 && cpSecondChannel[ci] < cellposeCompanionSources.length ? cellposeCompanionSources[cpSecondChannel[ci]] : null,
                                    cpSecondChannel[ci] >= 0 && cpSecondChannel[ci] < channelNames.length ? channelNames[cpSecondChannel[ci]] : null,
                                    cpSecondChannel[ci] >= 0 && cpSecondChannel[ci] < filterFilenames.length ? filterFilenames[cpSecondChannel[ci]] : null,
                                    null, null);   // null region = full image (no ROI restriction)
                        } finally {
                            ParallelContext.exitParallel();
                        }
                    }
                }));
            }
            waitForFilterFutures(filterFutures);
            } finally {
                filterPool.shutdown();
            }
        } else {
            for (int c = 0; c < n; c++) {
                filterResults[c] = filterAndThresholdChannel(
                        c, channelNames[c], chans[c], binDir, filterFilenames[c],
                        thresholdTokens[c], sizeTokens[c],
                        false, -1, null, n,
                        segmentationMethods[c],
                        isEnhancedClassical[c], enhancedMorphPredicates[c],
                        isStarDist[c], sdProbThresh[c], sdNmsThresh[c],
                        sdLinkingMaxDistance[c], sdGapClosingMaxDistance[c], sdMaxFrameGap[c],
                        sdAreaMin[c], sdAreaMax[c], sdQualityMin[c], sdIntensityMin[c],
                        sdModelKey[c], new File(directory),
                        isCellpose[c], cpModel[c], cpDiameter[c], cpFlowThreshold[c],
                        cpCellprobThreshold[c], cpUseGpu[c],
                        cpSecondChannel[c],
                        cpSecondChannel[c] >= 0 && cpSecondChannel[c] < cellposeCompanionSources.length ? cellposeCompanionSources[cpSecondChannel[c]] : null,
                        cpSecondChannel[c] >= 0 && cpSecondChannel[c] < channelNames.length ? channelNames[cpSecondChannel[c]] : null,
                        cpSecondChannel[c] >= 0 && cpSecondChannel[c] < filterFilenames.length ? filterFilenames[cpSecondChannel[c]] : null,
                        null, null);   // null region = full image (no ROI restriction)
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
                            fr.labelImage, fr.unfiltered,
                            fr.minSizeVox, fr.maxSizeVox,
                            true, true);
                    int objectCount = countResults[c].getStatistics() == null
                            ? 0 : countResults[c].getStatistics().size();
                    IJ.log("    3DObjectCounter [" + channelName + "]: "
                            + objectCount + " objects detected (" + fr.segmentationName + ", full image)");
                } else {
                    // Classical: threshold + native counting
                    long counterStart = System.currentTimeMillis();
                    if (fr.enhancedClassical) {
                        ImagePlus enhancedLabels = new EnhancedClassicalRunner().run(
                                fr.filtered,
                                new EnhancedClassicalParameters(
                                        (int) Math.round(fr.threshold),
                                        fr.minSizeVox,
                                        fr.maxSizeVox,
                                        fr.morphPredicates,
                                        fr.unfiltered,
                                        new EnhancedClassicalParameters.WarningSink() {
                                            @Override public void warn(String message) {
                                                IJ.log(message);
                                            }
                                        }));
                        ObjectsCounter3DWrapper.Result recounted = ocWrapper.fromLabelImage(
                                enhancedLabels, fr.unfiltered, 0, Integer.MAX_VALUE, true, true);
                        ResultsTable stats = EnhancedClassicalRunner.statsProperty(enhancedLabels);
                        if (stats == null) stats = recounted.getStatistics();
                        ImagePlus objectMap = recounted.getObjectsMap() == null
                                ? enhancedLabels
                                : recounted.getObjectsMap();
                        if (objectMap != null) {
                            objectMap.setProperty(EnhancedClassicalRunner.OBJECT_STATS_PROPERTY, stats);
                        }
                        if (enhancedLabels != null && enhancedLabels != objectMap) {
                            enhancedLabels.changes = false;
                            enhancedLabels.close();
                            enhancedLabels.flush();
                        }
                        countResults[c] = new ObjectsCounter3DWrapper.Result(
                                stats,
                                objectMap,
                                recounted.getMaskedImage(),
                                stats != null && stats.size() > 0 && objectMap != null);
                    } else {
                        countResults[c] = ocWrapper.runNative(
                                fr.filtered,
                                (int) Math.round(fr.threshold),
                                fr.minSizeVox, fr.maxSizeVox,
                                false,          // excludeOnEdges
                                fr.unfiltered,  // redirect
                                true, true);
                    }
                    int objectCount = countResults[c].getStatistics() == null
                            ? 0 : countResults[c].getStatistics().size();
                    long counterMs = System.currentTimeMillis() - counterStart;
                    IJ.log("    " + (fr.enhancedClassical ? "EnhancedClassical" : "3DObjectCounter")
                            + " [" + channelName + "]: "
                            + objectCount + " objects detected (" + counterMs + " ms, full image)");
                }
                channelHasObjects[c] = countResults[c].isFoundObjects()
                        && countResults[c].getObjectsMap() != null;
            } catch (Throwable t) {
                IJ.log("    ERROR counting " + channelName
                        + " on full image for animal='" + animalName
                        + "', hemisphere='" + hemisphere + "': " + t);
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
            SegmentationImageRoot imageRoot,
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
        // Geometry is driven ONLY by the original-coordinate (index-0) ROI; the
        // index-1 "cropped" ROI is a top-left-shifted presentation artifact. See RegionMask.
        RegionMask region = roiSet == null ? null : RegionMask.from(roiSet.cloneRoi(roiIdx));
        String roiBase = roiSet == null ? "" : roiSet.name;
        String hemisphere = parts == null ? "" : parts.hemisphere;
        String seriesRegionLabel = parts == null ? "" : parts.csvRegion();
        String roiLabel = parts == null
                ? (scnIndex > 0 && !endsWithDigit(roiBase) ? roiBase + scnIndex : roiBase)
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

                // Centroid filter against the region (index-0 / original coords).
                // This path is centroid-only (useSharedCounting requires allCentroid).
                int afterFilter = -1;
                if (region != null && roiLabels != null) {
                    int beforeFilter = fullData.channelHasObjects[c]
                            ? (fullRes.getStatistics() != null ? fullRes.getStatistics().size() : 0)
                            : 0;
                    int removed = region.filterByCentroid(roiLabels);
                    afterFilter = beforeFilter - removed;
                    IJ.log("    3DObjectCounter [" + channelName + "] ROI filter: "
                            + beforeFilter + " \u2192 " + afterFilter
                            + " objects (" + removed + " outside " + roiBase + " ROI removed)");
                }

                // Crop to the region bounding box ONLY (choice a — keep whole objects);
                // coordinates become region-relative (top-left = 0,0).
                if (region != null) {
                    region.cropToBounds(roiLabels);
                    region.cropToBounds(roiUnfiltered);
                }

                // Re-derive statistics from the cropped, centroid-filtered label image
                ObjectsCounter3DWrapper.Result roiRes = (roiLabels != null && roiUnfiltered != null)
                        ? ocWrapper.fromLabelImage(roiLabels, roiUnfiltered,
                        fr.minSizeVox, fr.maxSizeVox, true, true)
                        : new ObjectsCounter3DWrapper.Result(null, roiLabels, null, false);
                if (fr.enhancedClassical) {
                    roiRes = withEnhancedMorphStats(roiRes, roiUnfiltered, fr.morphPredicates);
                }
                // QC invariant: cropping relocates objects, it must not lose/create them.
                if (afterFilter >= 0) {
                    int finalCount = roiRes.getStatistics() == null ? 0 : roiRes.getStatistics().size();
                    logRegionCropQc(channelName, roiBase, afterFilter, finalCount);
                }

                // Register images for downstream use (coloc, process length, save)
                registerImage(channelName + "_unfiltered", roiUnfiltered, false);

                // Clone + crop filtered image for this ROI
                ImagePlus roiFiltered = fr.filtered != null ? ImageOps.duplicateThreadSafe(fr.filtered) : null;
                if (region != null) region.cropToBounds(roiFiltered);
                registerImage(channelName + "_filtered", roiFiltered, false);

                ImagePlus roiObjMap = roiRes.getObjectsMap();
                if (roiObjMap != null) {
                    registerImage(channelName + "_objects", roiObjMap, false);
                }

                channelHasObjects[c] = roiRes.isFoundObjects() && roiObjMap != null;

                // Append per-ROI stats to channel table
                appendStatsToChannelTable(roiRes.getStatistics(), channelTables.get(channelName),
                        scnIndex, animalName, hemisphere, seriesRegionLabel, roiLabel);

                // Save segmentation comparison images per-ROI under Results/Analysis Images/Segmentation/<animal>/.
                File segmentationAnimalDir = imageRoot.animalDir(animalName);
                //noinspection ResultOfMethodCallIgnored
                segmentationAnimalDir.mkdirs();
                String maskedSuffix = buildFileSuffix(hemisphere, roiLabel, animalName);
                String safeChannelName = ChannelFilenameCodec.toSafe(channelName);
                if (roiRes.getMaskedImage() != null) {
                    File maskedOutput = new File(segmentationAnimalDir, safeChannelName + "_Masked"
                            + (maskedSuffix.isEmpty() ? "" : "_" + maskedSuffix) + ".tif");
                    AsyncImageSaver.saveAsTiffAsync(roiRes.getMaskedImage(),
                            maskedOutput.getAbsolutePath());
                    recordOutput(maskedOutput, "tiff");
                }

                // Save pre-detection filtered image per-ROI beside the masked and label-map outputs.
                if (fr.preDetectionFiltered != null) {
                    ImagePlus roiPreDetection = ImageOps.duplicateThreadSafe(fr.preDetectionFiltered);
                    if (region != null) region.cropToBounds(roiPreDetection);
                    File filteredOutput = new File(segmentationAnimalDir, safeChannelName + "_Filtered"
                            + (maskedSuffix.isEmpty() ? "" : "_" + maskedSuffix) + ".tif");
                    AsyncImageSaver.saveAsTiffAsync(roiPreDetection,
                            filteredOutput.getAbsolutePath());
                    recordOutput(filteredOutput, "tiff");
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
            if (doIntensityColoc) {
                appendIntensityColocColumns(cfg, channelHasObjects, channelTables, scnIndex, animalName,
                        hemisphere, seriesRegionLabel, roiLabel);
            }

            // Process length extraction
            if (extractProcessLength && processChannels != null) {
                processLengthExtractionWithTables(cfg, channelTables, imp, scnIndex, animalName,
                        hemisphere, seriesRegionLabel, roiLabel, processChannels, nuclearMarkerIndex);
            }

            // Save objects images
            saveObjectsImages(cfg, imageRoot, animalName, hemisphere, roiLabel);
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
            SegmentationMethod segmentationMethod,
            boolean isEnhancedClassical, List<MorphPredicate> enhancedMorphPredicates,
            boolean isStarDist, double starDistProbThresh, double starDistNmsThresh,
            double starDistLinkingMaxDistance, double starDistGapClosingMaxDistance, int starDistMaxFrameGap,
            double starDistAreaMin, double starDistAreaMax,
            double starDistQualityMin, double starDistIntensityMin,
            String starDistModelKey, File projectRoot,
            boolean isCellpose, String cellposeModel, double cellposeDiameter,
            double cellposeFlowThreshold, double cellposeCellprobThreshold, boolean cellposeUseGpu,
            int cellposeSecondChannelIndex, ImagePlus cellposeSecondChannel,
            String cellposeSecondChannelName, String cellposeSecondChannelFilterFilename,
            RegionMask region, String roiSetName) {

        // Geometry is driven by RegionMask, built from the original-coordinate
        // (index-0) ROI. RegionMask is thread-safe to share across the parallel
        // filter threads: filterByCentroid clones before Roi.contains() (whose
        // mask cache is not thread-safe) and the crop/mask paths clone internally.
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
                ImagePlus labelImage;
                try {
                    labelImage = StarDist3DRunner.run(filtered, starDistProbThresh, starDistNmsThresh, channelName,
                            starDistLinkingMaxDistance, starDistGapClosingMaxDistance, starDistMaxFrameGap,
                            starDistAreaMin, starDistAreaMax, starDistQualityMin, starDistIntensityMin,
                            starDistModelKey, projectRoot);
                } catch (SegmentationRunFailureException e) {
                    String failureReason = e.getMessage();
                    IJ.log("    - [Ch " + (c + 1) + "] WARNING: " + failureReason);
                    filtered.changes = false;
                    filtered.close();
                    filtered.flush();
                    return new ChannelFilterResult(c, channelName, ch, null,
                            null, 0, 0, true, failureReason);
                }

                // 3. Filter labels by centroid — remove objects whose centroids
                // fall outside the tissue ROI (before cropping to bounding box).
                int afterFilter = -1;
                if (labelImage != null && region != null) {
                    int beforeFilter = StarDist3DRunner.countLabels(labelImage);
                    int removed = region.filterByCentroid(labelImage);
                    afterFilter = beforeFilter - removed;
                    IJ.log("    StarDist [" + channelName + "] ROI filter: " + beforeFilter
                            + " → " + afterFilter + " objects (" + removed + " outside " + roiSetName + " ROI removed)");
                }

                // 4. Crop to the region bounding box ONLY (choice a — keep whole
                // objects). StarDist filters by centroid, so objects are not clipped
                // to the outline; coordinates become region-relative (top-left = 0,0).
                if (region != null) {
                    region.cropToBounds(filtered);
                    region.cropToBounds(ch);
                    if (labelImage != null) {
                        region.cropToBounds(labelImage);
                        if (afterFilter >= 0) {
                            logRegionCropQc(channelName, roiSetName, afterFilter,
                                    RegionMask.countLabels(labelImage));
                        }
                    }
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

                String effectiveSizeToken = sizeToken == null || sizeToken.trim().isEmpty()
                        ? "0-Infinity"
                        : sizeToken;
                String[] sizeParts = effectiveSizeToken.split("-");
                String minSize = sizeParts.length > 0 ? sizeParts[0] : "0";
                String maxSize = sizeParts.length > 1 ? sizeParts[1] : "Infinity";
                int minSizeVox = ObjectsCounter3DWrapper.parseMinSizeVoxels(minSize, 0);
                int maxSizeVox = ObjectsCounter3DWrapper.parseMaxSizeVoxels(maxSize, binaryMask);
                String maxSizeText = maxSize == null ? "" : maxSize.trim();
                boolean maxSizeFinite = !("Infinity".equalsIgnoreCase(maxSizeText)
                        || "inf".equalsIgnoreCase(maxSizeText)
                        || maxSizeText.isEmpty());
                if (!compactLog && (minSizeVox > 0 || maxSizeFinite)) {
                    IJ.log("    - [Ch " + (c + 1) + "] StarDist final 3D voxel volume filter: "
                            + minSizeVox + "-" + (maxSizeFinite ? String.valueOf(maxSizeVox) : "Infinity")
                            + " voxels");
                }

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
                ImagePlus labelImage;
                try {
                    labelImage = Cellpose3DRunner.run(filtered, filteredCompanion, cellposeModel, cellposeDiameter,
                            cellposeFlowThreshold, cellposeCellprobThreshold, cellposeUseGpu, channelName,
                            projectRoot);
                } catch (SegmentationRunFailureException e) {
                    String failureReason = e.getMessage();
                    IJ.log("    - [Ch " + (c + 1) + "] WARNING: " + failureReason);
                    filtered.changes = false;
                    filtered.close();
                    filtered.flush();
                    if (filteredCompanion != null) {
                        filteredCompanion.changes = false;
                        filteredCompanion.close();
                        filteredCompanion.flush();
                    }
                    return new ChannelFilterResult(c, channelName, ch, null,
                            null, 0, 0, true, failureReason);
                }

                int afterFilter = -1;
                if (labelImage != null && region != null) {
                    int beforeFilter = Cellpose3DRunner.countLabels(labelImage);
                    int removed = region.filterByCentroid(labelImage);
                    afterFilter = beforeFilter - removed;
                    IJ.log("    Cellpose [" + channelName + "] ROI filter: " + beforeFilter
                            + " -> " + afterFilter + " objects (" + removed + " outside " + roiSetName + " ROI removed)");
                }

                // Crop to the region bounding box ONLY (choice a — keep whole objects);
                // Cellpose filters by centroid, so objects are not clipped to the outline.
                if (region != null) {
                    region.cropToBounds(filtered);
                    region.cropToBounds(ch);
                    if (labelImage != null) {
                        region.cropToBounds(labelImage);
                        if (afterFilter >= 0) {
                            logRegionCropQc(channelName, roiSetName, afterFilter,
                                    RegionMask.countLabels(labelImage));
                        }
                    }
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
                    IJ.log("    - [Ch " + (c + 1) + "] WARNING: No filter applied (missing saved filter and bundled default)"); // always log warnings
                }
            }

            // Crop to ROI (after filter, before threshold) — unless centroid mode
            // defers cropping until after counting in Phase B.
            ImagePlus croppedForSkeleton = null;
            if (classicalCentroidFilter) {
                // Keep filtered/ch uncropped for full-image counting in Phase B.
                // Create a separate cropped copy for skeleton + preDetection.
                // Choice (a): crop to the bounding box only (no shape mask).
                if (region != null) {
                    croppedForSkeleton = ImageOps.duplicateThreadSafe(filtered);
                    region.cropToBounds(croppedForSkeleton);
                }
            } else {
                // Crop-first mode: crop to the bounding box AND clear outside the
                // traced shape so detection runs on tissue only.
                if (region != null) {
                    region.cropAndMask(filtered);
                    region.cropAndMask(ch);
                }
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
                    false, "", null, "", preDetection,
                    isEnhancedClassical, enhancedMorphPredicates);

        } catch (Throwable t) {
            String errMsg = t.getClass().getName() + ": " + t.getMessage();
            IJ.log("    - [Ch " + (c + 1) + "] ERROR during filter phase for channel '"
                    + channelName + "', image='" + ch.getTitle()
                    + "', segmentation='" + (segmentationMethod == null
                    ? "<unset>" : SegmentationTokenParser.format(segmentationMethod))
                    + "': " + errMsg);
            return new ChannelFilterResult(c, channelName, ch, null,
                    null, 0, 0, true, "filter error: " + errMsg);
        }
    }

    private static void waitForFilterFutures(List<Future<?>> filterFutures) {
        for (Future<?> f : filterFutures) {
            try {
                f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Filter thread interrupted.", e);
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof FatalSegmentationException) {
                    throw (FatalSegmentationException) cause;
                }
                IJ.log("    WARNING: Filter thread error: "
                        + (cause == null ? e.getMessage() : cause.getMessage()));
            }
        }
    }


    private ObjectsCounter3DWrapper.Result withEnhancedMorphStats(ObjectsCounter3DWrapper.Result result,
                                                                  ImagePlus intensityImage,
                                                                  List<MorphPredicate> predicates) {
        if (result == null || predicates == null || predicates.isEmpty()) {
            return result;
        }
        ResultsTable stats = EnhancedClassicalRunner.appendReferencedMorphColumns(
                result.getObjectsMap(),
                intensityImage,
                result.getStatistics(),
                predicates,
                new EnhancedClassicalParameters.WarningSink() {
                    @Override public void warn(String message) {
                        IJ.log(message);
                    }
                });
        ImagePlus objects = result.getObjectsMap();
        if (objects != null) {
            objects.setProperty(EnhancedClassicalRunner.OBJECT_STATS_PROPERTY, stats);
        }
        return new ObjectsCounter3DWrapper.Result(
                stats,
                objects,
                result.getMaskedImage(),
                stats != null && stats.size() > 0 && objects != null);
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
            AtlasRegionColumns.writeTo(channelTable, destRow, region, roiLabel);
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
        for (String column : AtlasRegionColumns.COLUMNS) {
            ensureStringColumn(channelTable, column);
        }

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
            AtlasRegionColumns.writeTo(channelTable, destRow, region, roiLabel);

            for (String h : headings) {
                if (h == null) continue;
                if (h.equals("SCN") || h.equals("ROI") || h.equals("Animal Name")
                        || h.equals("Hemisphere") || h.equals("Region")
                        || AtlasRegionColumns.isAtlasColumn(h)) continue;
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
                File maskedOutput = new File(outDir, ChannelFilenameCodec.toSafe(channelName) + "_Masked_" + hemiRegion + ".tif");
                AsyncImageSaver.saveAsTiffAsync(img, maskedOutput.getAbsolutePath());
                recordOutput(maskedOutput, "tiff");
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
                        setColocZerosForThisImage(aTable, overlapColAB, scnIndex, animalName, hemisphere, region, roiLabel);
                        setColocZerosForThisImage(aTable, colocColAB, scnIndex, animalName, hemisphere, region, roiLabel);
                    }
                    if (bTable != null) {
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
                IJ.log("    - " + ch + " vs: " + logEntries.get(i).toString());
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
        // Canonical implementation lives in RegionMask (the single source of
        // truth for region geometry). Delegated here so existing callers/tests
        // keep working. The roi must be in the same coordinate space as the
        // label image (original coords for a full-image map).
        return RegionMask.filterLabelsByCentroid(labelImage, roi);
    }

    /**
     * QC invariant for the centroid-filter then crop-to-bounds step: cropping to
     * the region bounding box re-bases object coordinates but must NOT lose or
     * create objects (every centroid-kept object lies within the bounding box and
     * connected components survive the crop). A mismatch is the signature of a
     * coordinate problem — historically the top-left-shifted index-1 ROI driving
     * geometry — so it is logged loudly rather than silently producing wrong counts.
     *
     * @param channelName   channel for the log line
     * @param roiSetName    ROI set / region name for the log line
     * @param keptAfterFilter object count after the centroid filter (pre-crop)
     * @param finalCount    object count re-derived from the cropped label image
     */
    private static void logRegionCropQc(String channelName, String roiSetName,
            int keptAfterFilter, int finalCount) {
        if (finalCount == keptAfterFilter) return;
        IJ.log("    [FLASH][QC] WARNING: object count changed across region crop for "
                + channelName + " / " + roiSetName + ": kept " + keptAfterFilter
                + " after centroid filter but re-derived " + finalCount
                + " after cropping. Cropping should relocate objects, not change their"
                + " count — check that the region ROI (index 0, original coordinates)"
                + " matches the label image coordinate space.");
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
            sb.append(otherChannel).append(" (failed)");
        } else {
            sb.append(otherChannel).append(" (").append(interactions).append(" interactions)");
        }
    }

    private void appendColocLogEntry(List<StringBuilder> logEntries, List<Boolean> logFirst, int channelIdx, String otherChannel, String reason) {
        StringBuilder sb = logEntries.get(channelIdx);
        if (!logFirst.get(channelIdx)) sb.append(", ");
        logFirst.set(channelIdx, Boolean.FALSE);
        sb.append(otherChannel).append(" (").append(reason).append(")");
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

    private void appendIntensityColocColumns(
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

        for (int sourceIdx = 0; sourceIdx < n; sourceIdx++) {
            String sourceChannel = cfg.channelNames.get(sourceIdx);
            ij.measure.ResultsTable sourceTable = channelTables.get(sourceChannel);
            if (sourceTable == null) continue;

            ImagePlus sourceLabels = getRegisteredImage(sourceChannel + "_objects");
            ImagePlus sourceImage = getRegisteredImage(sourceChannel + "_unfiltered");
            boolean sourceHasObjects = channelHasObjects[sourceIdx] && sourceLabels != null;

            for (int partnerIdx = 0; partnerIdx < n; partnerIdx++) {
                if (partnerIdx == sourceIdx) continue;
                String partnerChannel = cfg.channelNames.get(partnerIdx);
                ImagePlus partnerImage = getRegisteredImage(partnerChannel + "_unfiltered");

                ColocalizationMetrics.Result roiMetrics =
                        computeIntensityColocMetrics(sourceChannel, partnerChannel);
                Map<Integer, ColocalizationMetrics.Result> objectMetrics = null;
                if (sourceHasObjects && sourceImage != null && partnerImage != null) {
                    objectMetrics = computeObjectIntensityColocMetrics(
                            sourceLabels, sourceImage, partnerImage, sourceChannel, partnerChannel);
                }
                writeIntensityColocForThisImage(sourceTable, sourceChannel, partnerChannel,
                        objectMetrics, roiMetrics, scnIndex, animalName, hemisphere, region, roiLabel);
            }
        }
    }

    private Map<Integer, ColocalizationMetrics.Result> computeObjectIntensityColocMetrics(
            ImagePlus sourceLabels,
            ImagePlus sourceImage,
            ImagePlus partnerImage,
            String sourceChannel,
            String partnerChannel) {
        Map<Integer, ColocalizationMetrics.Result> out =
                new LinkedHashMap<Integer, ColocalizationMetrics.Result>();
        if (!sameDimensions(sourceLabels, sourceImage) || !sameDimensions(sourceLabels, partnerImage)) {
            IJ.log("    [COLOC] per-object intensity metrics skipped for "
                    + sourceChannel + " vs " + partnerChannel + ": dimensions differ");
            return out;
        }

        ij.ImageStack labelStack = sourceLabels.getStack();
        ij.ImageStack sourceStack = sourceImage.getStack();
        ij.ImageStack partnerStack = partnerImage.getStack();
        int depth = Math.max(1, sourceLabels.getStackSize());
        gnu.trove.map.hash.TIntIntHashMap sizes = new gnu.trove.map.hash.TIntIntHashMap();
        for (int z = 1; z <= depth; z++) {
            ij.process.ImageProcessor lp = labelStack.getProcessor(z);
            int nPixels = lp.getPixelCount();
            for (int i = 0; i < nPixels; i++) {
                int label = lp.get(i);
                if (label > 0) {
                    sizes.adjustOrPutValue(label, 1, 1);
                }
            }
        }

        Map<Integer, double[]> sourceByLabel = new LinkedHashMap<Integer, double[]>();
        Map<Integer, double[]> partnerByLabel = new LinkedHashMap<Integer, double[]>();
        gnu.trove.map.hash.TIntIntHashMap offsets = new gnu.trove.map.hash.TIntIntHashMap();
        gnu.trove.iterator.TIntIntIterator sizeIt = sizes.iterator();
        while (sizeIt.hasNext()) {
            sizeIt.advance();
            int label = sizeIt.key();
            int count = sizeIt.value();
            sourceByLabel.put(Integer.valueOf(label), new double[count]);
            partnerByLabel.put(Integer.valueOf(label), new double[count]);
        }

        int width = sourceLabels.getWidth();
        int height = sourceLabels.getHeight();
        for (int z = 1; z <= depth; z++) {
            ij.process.ImageProcessor lp = labelStack.getProcessor(z);
            ij.process.ImageProcessor sp = sourceStack.getProcessor(z);
            ij.process.ImageProcessor pp = partnerStack.getProcessor(z);
            int nPixels = lp.getPixelCount();
            for (int i = 0; i < nPixels; i++) {
                int label = lp.get(i);
                if (label <= 0) continue;
                int offset = offsets.get(label);
                Integer key = Integer.valueOf(label);
                sourceByLabel.get(key)[offset] = sp.getf(i);
                partnerByLabel.get(key)[offset] = pp.getf(i);
                offsets.put(label, offset + 1);
            }
        }

        for (Map.Entry<Integer, double[]> entry : sourceByLabel.entrySet()) {
            Integer label = entry.getKey();
            double[] sourceValues = entry.getValue();
            double[] partnerValues = partnerByLabel.get(label);
            out.put(label, ColocalizationMetrics.compute(
                    sourceValues, partnerValues, Math.max(1, sourceValues.length), 1, 1));
        }
        return out;
    }

    private static boolean sameDimensions(ImagePlus a, ImagePlus b) {
        return a != null && b != null
                && a.getWidth() == b.getWidth()
                && a.getHeight() == b.getHeight()
                && a.getStackSize() == b.getStackSize();
    }

    private void writeIntensityColocForThisImage(ij.measure.ResultsTable table,
                                                 String sourceChannel,
                                                 String partnerChannel,
                                                 Map<Integer, ColocalizationMetrics.Result> objectMetrics,
                                                 ColocalizationMetrics.Result roiMetrics,
                                                 int scnIndex,
                                                 String animalName,
                                                 String hemisphere,
                                                 String region,
                                                 String roiLabel) {
        for (int r = 0; r < table.size(); r++) {
            if (!matchesRowMetadata(table, r, scnIndex, animalName, hemisphere, region, roiLabel)) {
                continue;
            }
            int label = roundedLabel(table, r);
            ColocalizationMetrics.Result object =
                    objectMetrics == null ? null : objectMetrics.get(Integer.valueOf(label));
            writeObjectIntensityMetricValues(table, r, sourceChannel, partnerChannel, object, roiMetrics);
        }
    }

    private static int roundedLabel(ResultsTable table, int row) {
        try {
            return (int) Math.round(table.getValue("Label", row));
        } catch (Exception e) {
            return 0;
        }
    }

    private void writeObjectIntensityMetricValues(ij.measure.ResultsTable table,
                                                  int row,
                                                  String sourceChannel,
                                                  String partnerChannel,
                                                  ColocalizationMetrics.Result object,
                                                  ColocalizationMetrics.Result roi) {
        setMetricValue(table, objPearsonCol(sourceChannel, partnerChannel), row,
                object == null ? Double.NaN : object.pearson);
        setMetricValue(table, objMandersM1Col(sourceChannel, partnerChannel), row,
                object == null ? Double.NaN : object.mandersM1);
        setMetricValue(table, objMandersM2Col(sourceChannel, partnerChannel), row,
                object == null ? Double.NaN : object.mandersM2);
        setMetricValue(table, objCostesTaCol(sourceChannel, partnerChannel), row,
                object == null ? Double.NaN : object.costesTa);
        setMetricValue(table, objCostesTbCol(sourceChannel, partnerChannel), row,
                object == null ? Double.NaN : object.costesTb);
        setMetricValue(table, objPearsonThresholdedCol(sourceChannel, partnerChannel), row,
                object == null ? Double.NaN : object.pearsonThresholded);
        setMetricValue(table, objCostesPCol(sourceChannel, partnerChannel), row,
                object == null ? Double.NaN : object.costesP);
        setMetricValue(table, roiCostesTaCol(sourceChannel, partnerChannel), row,
                roi == null ? Double.NaN : roi.costesTa);
        setMetricValue(table, roiCostesTbCol(sourceChannel, partnerChannel), row,
                roi == null ? Double.NaN : roi.costesTb);
        setMetricValue(table, roiCostesPCol(sourceChannel, partnerChannel), row,
                roi == null ? Double.NaN : roi.costesP);
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
        if (cfg.isEnhancedClassical(channelIndex)) {
            List<MorphPredicate> predicates = cfg.getEnhancedClassicalMorphPredicates(channelIndex);
            StringBuilder morph = new StringBuilder();
            for (int i = 0; i < predicates.size(); i++) {
                if (i > 0) morph.append(",");
                morph.append(predicates.get(i).format());
            }
            return "Segmentation=Enhanced Classical"
                    + ", Threshold=" + cfg.getEnhancedClassicalThreshold(channelIndex)
                    + ", Size=" + cfg.getEnhancedClassicalMinSize(channelIndex)
                    + "-" + (cfg.getEnhancedClassicalMaxSize(channelIndex) == Integer.MAX_VALUE
                    ? "Infinity" : String.valueOf(cfg.getEnhancedClassicalMaxSize(channelIndex)))
                    + ", Morph=" + (morph.length() == 0 ? "None" : morph.toString());
        }
        String threshold = channelIndex < cfg.channelThresholds.size() ? cfg.channelThresholds.get(channelIndex) : "";
        String size = channelIndex < cfg.channelSizes.size() ? cfg.channelSizes.get(channelIndex) : "";
        return "Segmentation=Classical, Threshold=" + threshold + ", Size=" + size;
    }

    private static ModelEntry resolvedModelEntry(ModelCatalog catalog,
                                                 SegmentationMethod method,
                                                 ModelEntry.Engine expectedEngine) {
        if (catalog == null || method == null || expectedEngine == null) {
            return null;
        }
        String modelKey = "";
        if (expectedEngine == ModelEntry.Engine.STARDIST && method.isStarDist()) {
            modelKey = SegmentationMethod.starDistModelKey(method);
        } else if (expectedEngine == ModelEntry.Engine.CELLPOSE && method.isCellpose()) {
            modelKey = SegmentationMethod.cellposeModelKey(method);
        }
        if (modelKey == null || modelKey.trim().isEmpty()) {
            return null;
        }
        Optional<ModelEntry> found = catalog.get(modelKey.trim());
        if (!found.isPresent()) {
            return null;
        }
        ModelEntry entry = found.get();
        return entry.engine == expectedEngine ? entry : null;
    }

    /** Build canonical percentage overlap column name stored in each channel CSV. */
    private String colocPercentCol(String partner) {
        return "Colocalisation with " + partner;
    }

    private String objPearsonCol(String source, String partner) {
        return source + "_ObjPearson_" + partner;
    }

    private String objMandersM1Col(String source, String partner) {
        return source + "_ObjMandersM1_" + partner;
    }

    private String objMandersM2Col(String source, String partner) {
        return source + "_ObjMandersM2_" + partner;
    }

    private String objCostesTaCol(String source, String partner) {
        return source + "_ObjCostesTa_" + partner;
    }

    private String objCostesTbCol(String source, String partner) {
        return source + "_ObjCostesTb_" + partner;
    }

    private String objPearsonThresholdedCol(String source, String partner) {
        return source + "_ObjPearsonT_" + partner;
    }

    private String objCostesPCol(String source, String partner) {
        return source + "_ObjCostesP_" + partner;
    }

    private String roiCostesTaCol(String source, String partner) {
        return source + "_ROICostesTa_" + partner;
    }

    private String roiCostesTbCol(String source, String partner) {
        return source + "_ROICostesTb_" + partner;
    }

    private String roiCostesPCol(String source, String partner) {
        return source + "_ROICostesP_" + partner;
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

    private void ensureAllIntensityColocColumns(BinConfig cfg, Map<String, ij.measure.ResultsTable> channelTables) {
        for (String source : cfg.channelNames) {
            ij.measure.ResultsTable t = channelTables.get(source);
            if (t == null || t.size() == 0) continue;

            for (String partner : cfg.channelNames) {
                if (source.equals(partner)) continue;
                ensureIntensityColocColumn(t, objPearsonCol(source, partner));
                ensureIntensityColocColumn(t, objMandersM1Col(source, partner));
                ensureIntensityColocColumn(t, objMandersM2Col(source, partner));
                ensureIntensityColocColumn(t, objCostesTaCol(source, partner));
                ensureIntensityColocColumn(t, objCostesTbCol(source, partner));
                ensureIntensityColocColumn(t, objPearsonThresholdedCol(source, partner));
                ensureIntensityColocColumn(t, objCostesPCol(source, partner));
                ensureIntensityColocColumn(t, roiCostesTaCol(source, partner));
                ensureIntensityColocColumn(t, roiCostesTbCol(source, partner));
                ensureIntensityColocColumn(t, roiCostesPCol(source, partner));
            }
        }
    }

    private void ensureIntensityColocColumn(ij.measure.ResultsTable table, String colName) {
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
            java.util.regex.Matcher m = MULTICOLOC_INTERACTION_COUNT_PATTERN.matcher(trimmed);
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0;
    }

    private static boolean endsWithDigit(String value) {
        return value != null
                && !value.isEmpty()
                && Character.isDigit(value.charAt(value.length() - 1));
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

    private void recordOutputIfExists(File file, String kind) {
        if (file != null && file.isFile()) {
            recordOutput(file, kind);
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
                File source = null;
                try {
                    source = rawSupplier.getContainerFileForSeries(seriesIndex);
                } catch (Exception ignored) {
                    source = rawSupplier.getContainerFile();
                }
                AnalysisRunContext.InputHandle input = recordInputStart(source, seriesIndex + 1);
                long started = System.currentTimeMillis();
                try {
                // TIFF-folder mode has no sibling deconvolution layout — pass through to parent.
                if (rawSupplier.getMode() == DeferredImageSupplier.Mode.TIFF_FOLDER) {
                    ImagePlus imp = materialized ? rawSupplier.openSeriesMaterialized(seriesIndex) : rawSupplier.openSeries(seriesIndex);
                    recordInputEnd(input, imp == null ? "skipped" : "processed", started);
                    return imp;
                }
                File container = rawSupplier.getContainerFileForSeries(seriesIndex);
                String seriesName = rawSupplier.getSeriesName(seriesIndex);
                String baseName = baseNameForSeries(seriesName, seriesIndex);
                File inputFile = DeconvolvedInputResolver.resolveInput(rootDir, container, baseName, useDeconv);
                if (inputFile != null && !inputFile.equals(container)) {
                    ImagePlus imp = new Opener().openImage(inputFile.getAbsolutePath());
                    if (imp != null) {
                        imp.setTitle(expectedSeriesTitle(container, seriesName, seriesIndex));
                    }
                    recordInputEnd(input, imp == null ? "skipped" : "processed", started);
                    return imp;
                }
                ImagePlus imp = materialized ? rawSupplier.openSeriesMaterialized(seriesIndex) : rawSupplier.openSeries(seriesIndex);
                recordInputEnd(input, imp == null ? "skipped" : "processed", started);
                return imp;
                } catch (Exception e) {
                    recordInputEnd(input, "failed", started);
                    recordError("Failed to open input series " + (seriesIndex + 1), e);
                    throw e;
                }
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

    static boolean allObjectOutputCsvsExist(String directory, List<String> channelNames) {
        if (channelNames == null) {
            return false;
        }
        for (String chName : channelNames) {
            if (existingObjectOutputCsv(directory, chName) == null) {
                return false;
            }
        }
        return true;
    }

    static List<File> existingObjectOutputCsvs(String directory, List<String> channelNames) {
        List<File> existing = new ArrayList<File>();
        Set<String> seenPaths = new LinkedHashSet<String>();
        if (channelNames == null) {
            return existing;
        }
        for (String chName : channelNames) {
            File csv = existingObjectOutputCsv(directory, chName);
            if (csv == null) continue;
            String path = csv.getAbsolutePath();
            if (seenPaths.add(path)) {
                existing.add(csv);
            }
        }
        return existing;
    }

    static File existingObjectOutputCsv(String directory, String channelName) {
        for (File dir : objectCsvReadDirs(directory)) {
            File csv = objectOutputCsv(dir, channelName);
            if (csv.isFile()) {
                return csv;
            }
        }
        return null;
    }

    static void writeObjectResultsCsv(String directory, File outFile, String channelName,
                                      ResultsTable table, List<String> orderedColumns,
                                      boolean extendExistingData) {
        writeObjectResultsCsv(directory, outFile, channelName, table, orderedColumns,
                extendExistingData, "");
    }

    static void writeObjectResultsCsv(String directory, File outFile, String channelName,
                                      ResultsTable table, List<String> orderedColumns,
                                      boolean extendExistingData, String runId) {
        if (extendExistingData) {
            File existing = existingObjectOutputCsv(directory, channelName);
            if (CsvTableIO.appendResultsTableCsv(outFile, existing, channelName,
                    table, orderedColumns, runId)) {
                return;
            }
        }
        CsvTableIO.writeResultsTableCsv(outFile, table, orderedColumns, runId);
    }

    private String currentRunId() {
        return RunIdCsv.runId(runRecordContext);
    }

    static File objectCsvWriteDir(String directory) {
        return FlashProjectLayout.forDirectory(directory).tablesObjectsWriteDir();
    }

    static List<File> objectCsvReadDirs(String directory) {
        return Collections.singletonList(objectCsvWriteDir(directory));
    }

    static File objectImageOutputsRoot(String directory) {
        return FlashProjectLayout.forDirectory(directory).analysisImagesSegmentationDir();
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
