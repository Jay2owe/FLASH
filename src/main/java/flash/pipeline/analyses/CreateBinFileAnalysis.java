package flash.pipeline.analyses;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.bin.BinField;
import flash.pipeline.bin.BinMacroIndex;
import flash.pipeline.bin.ChannelConfig;
import flash.pipeline.bin.ChannelConfigIO;
import flash.pipeline.cellpose.Cellpose3DRunner;
import flash.pipeline.cellpose.CellposeModel;
import flash.pipeline.cellpose.CellposeRuntime;
import flash.pipeline.analyses.wizard.BinPreset;
import flash.pipeline.analyses.wizard.BinPresetIO;
import flash.pipeline.analyses.wizard.ChannelSetupSupport;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.image.FilterExecutor;
import flash.pipeline.image.ImageOps;
import flash.pipeline.image.WindowManagerLock;
import flash.pipeline.image.NamedFilterLoader;
import flash.pipeline.image.dag.DagIRSerializer;
import flash.pipeline.intelligence.AnalysisStatusScanner;
import flash.pipeline.intelligence.EmptySliceSuggester;
import flash.pipeline.intelligence.MetadataDiagnostics;
import flash.pipeline.io.DeferredImageSupplier;
import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.io.LifIO;
import flash.pipeline.io.OrientationManifestIO;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.marker.MarkerLibrary;
import flash.pipeline.runrecord.AnalysisRunContext;
import flash.pipeline.runrecord.LoadedRunParameterApplier;
import flash.pipeline.runrecord.LoadedRunParameters;
import flash.pipeline.runrecord.ParameterSnapshot;
import flash.pipeline.runrecord.RunRecordAware;
import flash.pipeline.runrecord.ui.LoadFromRunButton;
import flash.pipeline.help.SetupHelpCatalog;
import flash.pipeline.help.SetupHelpTopic;
import flash.pipeline.naming.ConditionNameParser;
import flash.pipeline.naming.ImageOrientationResolver;
import flash.pipeline.naming.ImageNameParser;
import flash.pipeline.naming.NameParts;
import flash.pipeline.naming.OrientationManifestRow;
import flash.pipeline.naming.ResolvedImageMetadata;
import flash.pipeline.objects.ObjectsCounter3DWrapper;
import flash.pipeline.orientation.OrientationImageIdentity;
import flash.pipeline.qc.QcMinMaxPerConditionSelector;
import flash.pipeline.qc.QcSelectionChannel;
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.FeatureDependencyGate;
import flash.pipeline.segmentation.SegmentationMethod;
import flash.pipeline.segmentation.SegmentationTokenParser;
import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;
import flash.pipeline.stardist.StarDist3DRunner;
import flash.pipeline.stardist.StarDistDetector;
import flash.pipeline.ui.CustomFilterContinueDialog;
import flash.pipeline.ui.CustomFilterEntryDialog;
import flash.pipeline.ui.CancelConfirmationDialog;
import flash.pipeline.ui.NextStepLabels;
import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.ToggleSwitch;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.ConfigQcActions;
import flash.pipeline.ui.config.ConfigQcDialog;
import flash.pipeline.ui.config.ConfigQcResult;
import flash.pipeline.ui.config.ConfigQcStage;
import flash.pipeline.ui.config.ConfigReviewPanel;
import flash.pipeline.ui.config.SaveAsPresetPopover;
import flash.pipeline.ui.config.ChannelThresholdStage;
import flash.pipeline.ui.config.ClassicalSegmentationStage;
import flash.pipeline.ui.config.CellposeParameterStage;
import flash.pipeline.ui.config.DisplayRangeStage;
import flash.pipeline.ui.config.EnhancedClassicalSegmentationStage;
import flash.pipeline.ui.config.FilterParameterStage;
import flash.pipeline.ui.config.ParticleSizeStage;
import flash.pipeline.ui.config.SegmentationMethodStage;
import flash.pipeline.ui.config.StarDistParameterStage;
import flash.pipeline.ui.config.TrainedRfSummaryStage;
import flash.pipeline.ui.config.ZSliceSelectionStage;
import flash.pipeline.ui.sandbox.SandboxDialog;
import flash.pipeline.ui.wizard.MarkerAutoComplete;
import flash.pipeline.ui.wizard.ResumePromptDialog;
import flash.pipeline.zslice.ZSliceConfig;
import flash.pipeline.zslice.ZSliceMode;
import flash.pipeline.zslice.ZSliceOps;
import flash.pipeline.zslice.ZSliceRange;
import flash.pipeline.zslice.ZSliceSelection;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Macro;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.gui.NonBlockingGenericDialog;
import ij.gui.WaitForUserDialog;
// Intentional on interactive QC paths only; worker code must use ImageOps because Duplicator is not thread-safe.
import ij.plugin.Duplicator;
import ij.plugin.ZProjector;
import ij.process.ImageProcessor;

import javax.swing.JButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.filechooser.FileNameExtensionFilter;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Dialog;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.Window;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Migration of the macro createBinFile().
 *
 * Creates Directory/FLASH/Config/.settings and writes:
 * - channel_config.json (names, colors, object thresholds, sizes, minmax, intensity thresholds)
 * - C1_Filters.ijm ... Cn_Filters.ijm (from preset templates)
 * - defaultFilter.ijm
 *
 * Interactive quality-check workflow (Custom mode, per-channel, per-step):
 * - Step 1: Set Filter and Parameters — edit detected key=value values in C*_Filters.ijm
 * - Step 2: Custom Min-Max Display Ranges — B&C on Max Projection
 * - Step 3: Channel Threshold — after the per-channel filter
 *           (single user input populates both the object-threshold and
 *           intensity-threshold lists in BinConfig)
 * - Step 4: Particle Sizes (n Voxels) — 3D Objects Counter preview
 *
 * Values persist between images (threshold, display range, particle size),
 * including across restarts (circular persistence).
 */
public class CreateBinFileAnalysis implements Analysis, RunRecordAware {

    private static final String[] FILTER_PRESETS;
    private static final String[] BRIGHTNESS_CONTRAST_WINDOW_TITLES =
            new String[]{"B&C", "Brightness/Contrast"};
    private static final String[] THRESHOLD_WINDOW_TITLES =
            new String[]{"Threshold", "Threshold..."};
    private static final String ACTION_SKIP_CURRENT_IMAGE = "skip_current_image";
    private static final String REVIEW_ACTION_SAVE = "save";
    private static final String REVIEW_ACTION_SAVED = "saved";
    private static final String REVIEW_ACTION_BACK = "back";
    private static final String REVIEW_ACTION_CANCEL = "cancel";
    private static final String REVIEW_EDIT_PREFIX = "edit:";
    private static final String COPY_CONFIG_ACTION = "copied_config";
    private static final String CUSTOM_FILTER_PRESET_DIR = FlashProjectLayout.CUSTOM_FILTER_PRESET_DIR;
    private static final String LOADING_FILTERS_OPTION = "Loading filters...";
    private static final String FILTER_OPTIONS_REFRESH_TOKEN_PROPERTY =
            "flash.filterPresetOptionsRefreshToken";
    private static final String FILTER_OPTIONS_UPDATING_PROPERTY =
            "flash.filterPresetOptionsUpdating";
    private static final String FILTER_OPTIONS_WAS_DISPLAYABLE_PROPERTY =
            "flash.filterPresetOptionsWasDisplayable";
    private static final String EXTRA_LAST_STEP_INDEX = "lastStepIndex";
    private static final String EXTRA_LAST_STEP_LABEL = "lastStepLabel";
    private static final String EXTRA_CUSTOM_SETTINGS = "customSettings";
    private static final String SAVED_STATUS_PREFIX = "Saved \u2713 ";
    private static final List<String> CHANNEL_CONFIG_PROPERTIES;
    static {
        String[] bundled = NamedFilterLoader.FILTER_NAMES;
        FILTER_PRESETS = new String[bundled.length + 1]; // bundled + Custom
        System.arraycopy(bundled, 0, FILTER_PRESETS, 0, bundled.length);
        FILTER_PRESETS[bundled.length] = "Custom";

        List<String> props = new ArrayList<String>();
        props.add(ChannelConfig.P_NAME);
        props.add(ChannelConfig.P_COLOR);
        props.add(ChannelConfig.P_MARKER);
        props.add(ChannelConfig.P_THRESHOLD);
        props.add(ChannelConfig.P_SIZE);
        props.add(ChannelConfig.P_MINMAX);
        props.add(ChannelConfig.P_INTENSITY);
        props.add(ChannelConfig.P_SEGMENTATION);
        props.add(ChannelConfig.P_FILTER);
        CHANNEL_CONFIG_PROPERTIES = Collections.unmodifiableList(props);
    }

    private static final Map<String, String> FILTER_DESCRIPTIONS = new HashMap<String, String>();
    static {
        FILTER_DESCRIPTIONS.put("Default",
                "Standard median and background subtraction.");
        FILTER_DESCRIPTIONS.put("Punctate Signal / High Background",
                "Enhances distinct, punctate signals that are spread out, increasing the signal-to-noise ratio against a high diffuse background.");
        FILTER_DESCRIPTIONS.put("Ramified Cells (Microglia/Astrocytes)",
                "Captures signal from cells with complex morphologies, such as microglia and astrocytes, preserving delicate projections and thin spines.");
        FILTER_DESCRIPTIONS.put("Clustered Small",
                "Improves the separation and distinction of highly concentrated, overlapping small proteins or markers.");
        FILTER_DESCRIPTIONS.put("Clustered Large",
                "Improves the separation and distinction of highly concentrated, overlapping large objects or cellular structures.");
        FILTER_DESCRIPTIONS.put("Overlapping Cellular Marker",
                "Enhances boundary definition and distinction for dense, highly overlapping cellular stains.");
        FILTER_DESCRIPTIONS.put("Puncta Resolve",
                "Resolves cells from punctate signal and dim cytoplasm using a combination of autolocal thresholds and edge detection.");
        FILTER_DESCRIPTIONS.put("Diffuse Object",
                "3D bandpass filter for faint, diffuse staining (e.g. Caspase-3, low-expressing reporters). Uses Difference of Gaussians to isolate object-scale signal from noise and background autofluorescence, then applies fixed thresholding and 3D object detection.");
        FILTER_DESCRIPTIONS.put("Custom",
                "Applies a user-provided custom .ijm macro.");
    }

    private static final String[] COLOR_OPTIONS = {
            "Grays", "Red", "Green", "Blue", "Cyan", "Magenta", "Yellow"
    };
    private static final int CHANNEL_IDENTITY_ROW_LABEL_WIDTH = 100;
    private static final int CHANNEL_IDENTITY_CELL_WIDTH = 160;
    private static final int CHANNEL_IDENTITY_NAME_WIDTH = 150;
    private static final String MARKER_CATEGORY_REPORTER_PROTEIN = "reporter_protein";
    private static final String CHANNEL_IDENTITY_LUT_SYNC_SUPPRESSED_PROPERTY =
            "flash.channelIdentityLutSyncSuppressed";
    private static final String SEGMENTATION_CLASSICAL = "Classical";
    private static final String SEGMENTATION_ENHANCED_CLASSICAL = "Enhanced Classical";
    private static final String SEGMENTATION_STARDIST = "StarDist 3D";
    private static final String SEGMENTATION_CELLPOSE = "Cellpose";
    private static final String SEGMENTATION_TRAINED_RF_PREFIX = "Trained RF: ";
    private static final String[] SEGMENTATION_OPTIONS = {
            SEGMENTATION_CLASSICAL, SEGMENTATION_ENHANCED_CLASSICAL, SEGMENTATION_STARDIST, SEGMENTATION_CELLPOSE
    };

    private static final String INTENSITY_FILTER =
            "run(\"Median...\", \"radius=2 stack\");\n" +
            "run(\"Subtract Background...\", \"rolling=50 stack\");\n";

    private static final String QC_SELECTION_MODE_MANUAL = "Manually select images";
    private static final String QC_SELECTION_MODE_RANDOM = "Randomly select images";
    private static final String QC_SELECTION_MODE_MIN_MAX_OVERALL = "Min and max overall";
    private static final String QC_SELECTION_MODE_MIN_MAX_CONDITION = "Min and max per condition";
    private static final String Z_SLICE_SCOPE_LABEL = "Restrict analysis to selected z-slices";
    private static final int SETTINGS_FILTER_PARAMETERS = 0;
    private static final int SETTINGS_MIN_MAX = 1;
    private static final int SETTINGS_ROI_INTENSITY_THRESHOLD = 2;
    private static final int SETTINGS_OBJECT_THRESHOLD = 3;
    private static final int SETTINGS_OBJECT_SIZE_FILTER = 4;
    private static final int SETTINGS_SEGMENTATION_METHOD = 5;
    private static final int SETTINGS_SLOT_COUNT = 6;

    /** Tracks whether the last dialog dismissal was via Back (for inter-method signaling). */
    private boolean lastWasBack = false;
    private boolean headless = false;
    private boolean suppressDialogs = false;
    private CLIConfig cliConfig = null;
    private BinPreset commandPreset = null;
    private AnalysisRunContext runRecordContext = null;
    private int parallelThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    private int loaderThreads = 1;
    private int loaderPercent = 0;
    private final Set<String> customFilterPromptsHandled = new HashSet<String>();
    private final Map<String, String> customFilterDemotions = new HashMap<String, String>();
    private BinConfig settingsStatusReference = null;
    private List<String> activeEmbeddedStagePath = Collections.emptyList();
    private int activeEmbeddedStagePathIndex = -1;
    private File activeWizardBinFolder = null;
    private BinUserConfig activeWizardCfg = null;
    private boolean[][] activeWizardCustomSettings = null;
    private int activeWizardStep = 1;
    private String activeWizardLabel = "";
    private long lastWizardSavedAtMillis = 0L;

    private interface ValidationCheck {
        boolean isValid();
    }
    private CancelConfirmationDialog.Choice lastWizardCancelChoice = null;
    private boolean suppressNextWizardCancelMessage = false;
    private final ConfigQcContext.FilteredStackCache setupFilteredStackCache =
            new ConfigQcContext.FilteredStackCache();

    private static boolean gateStarDistFeature(String featureDisplayName) {
        return FeatureDependencyGate.gate(DependencyId.STARDIST_RUNTIME,
                "Set Up Configuration", featureDisplayName)
                && FeatureDependencyGate.gate(DependencyId.TENSORFLOW_NATIVE_RUNTIME,
                "Set Up Configuration", featureDisplayName);
    }

    private static boolean gateCellposeFeature(String featureDisplayName) {
        return FeatureDependencyGate.gate(DependencyId.CELLPOSE_RUNTIME,
                "Set Up Configuration", featureDisplayName);
    }

    private static boolean gateBioFormatsFeature(String featureDisplayName) {
        return FeatureDependencyGate.gate(DependencyId.BIO_FORMATS_RUNTIME,
                "Set Up Configuration", featureDisplayName);
    }

    private static boolean gateObjectsCounterFeature(String featureDisplayName) {
        return FeatureDependencyGate.gate(DependencyId.OBJECTS_COUNTER_3D,
                "Set Up Configuration", featureDisplayName);
    }

    private static boolean gateObjectsCounterPlusFeature(String featureDisplayName) {
        return FeatureDependencyGate.gate(DependencyId.OBJECTS_COUNTER_3D_PLUS,
                "Set Up Configuration", featureDisplayName)
                && FeatureDependencyGate.gate(DependencyId.MCIB3D_CORE,
                "Set Up Configuration", featureDisplayName);
    }

    @Override
    public void setHeadless(boolean headless) {
        this.headless = headless;
    }

    @Override
    public boolean requiresHeadedMode() {
        return true;
    }

    @Override
    public void setSuppressDialogs(boolean suppress) {
        this.suppressDialogs = suppress;
    }

    @Override
    public void setCliConfig(CLIConfig config) {
        this.cliConfig = config;
    }

    public void setCommandPreset(BinPreset preset) {
        this.commandPreset = preset;
    }

    @Override
    public void setRunRecordContext(AnalysisRunContext context) {
        this.runRecordContext = context;
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
    public void execute(String directory) {
        try {
            setupFilteredStackCache.clear();
            executeInternal(directory);
        } finally {
            setupFilteredStackCache.clear();
            closeAllImageWindows();
        }
    }

    public void executeFiltered(String directory, Set<BinField> include) {
        EnumSet<BinField> fields = normalizeBinFieldFilter(include);
        if (fields.containsAll(BinField.all())) {
            execute(directory);
            return;
        }

        try {
            setupFilteredStackCache.clear();
            executeFilteredInternal(directory, fields);
        } finally {
            setupFilteredStackCache.clear();
            closeAllImageWindows();
        }
    }

    private static EnumSet<BinField> normalizeBinFieldFilter(Set<BinField> include) {
        if (include == null) {
            return EnumSet.allOf(BinField.class);
        }
        if (include.isEmpty()) {
            return EnumSet.noneOf(BinField.class);
        }
        return EnumSet.copyOf(include);
    }

    private void executeFilteredInternal(String directory, EnumSet<BinField> fields) {
        File binFolder = configurationWriteDir(directory);
        customFilterPromptsHandled.clear();
        customFilterDemotions.clear();

        try {
            if (!binFolder.isDirectory() && !binFolder.mkdirs() && !binFolder.isDirectory()) {
                IJ.error("Set Up Configuration", "Failed to create: " + binFolder.getAbsolutePath());
                return;
            }

            ChannelConfig rawExisting = ChannelConfigIO.read(channelConfigSettingsDir(binFolder));
            BinConfig existing = BinConfigIO.readPartialFromDirectory(directory);
            BinUserConfig cfg = fromBinConfig(existing);
            BinUserConfig original = fromRawChannelConfig(rawExisting, existing);
            if (cfg.names.isEmpty() && requiresChannelContext(fields)) {
                fields.add(BinField.CHANNEL_NAMES);
            }

            if ((fields.contains(BinField.CHANNEL_NAMES) || fields.contains(BinField.CHANNEL_COLORS))
                    && !collectFilteredChannelIdentity(directory, binFolder, existing, cfg, fields)) {
                return;
            }
            padConfigToChannelCount(cfg, cfg.names.size());
            ensureConfigHasChannels(cfg);
            if (fields.contains(BinField.FILTER_PRESETS)) {
                writeDefaultFilter(binFolder);
                writeChannelFilters(binFolder, cfg);
            }
            if (fields.contains(BinField.Z_SLICE)
                    && !showFilteredZSlicePage(directory, binFolder, cfg)) {
                return;
            }
            if (requiresQcPages(fields)) {
                BinConfig previousStatusReference = settingsStatusReference;
                settingsStatusReference = existing;
                try {
                    if (!showFilteredQcPages(directory, binFolder, cfg, fields)) {
                        return;
                    }
                } finally {
                    settingsStatusReference = previousStatusReference;
                }
            }

            restoreExcludedFields(cfg, original, fields);
            writeFilteredChannelConfig(binFolder, cfg, fields);
        } catch (Exception e) {
            recordError("Set Up Configuration filtered update failed", e);
            IJ.handleException(e);
        }
    }

    private static boolean requiresChannelContext(Set<BinField> fields) {
        if (fields == null) return false;
        for (BinField field : fields) {
            if (field != BinField.Z_SLICE) return true;
        }
        return false;
    }

    private static boolean requiresQcPages(Set<BinField> fields) {
        return fields != null
                && (fields.contains(BinField.DISPLAY_MIN_MAX)
                || fields.contains(BinField.OBJECT_THRESHOLDS)
                || fields.contains(BinField.INTENSITY_THRESHOLDS)
                || fields.contains(BinField.PARTICLE_SIZES)
                || fields.contains(BinField.SEGMENTATION_METHODS)
                || fields.contains(BinField.FILTER_PRESETS));
    }

    protected boolean canShowFilteredDialogs() {
        return !GraphicsEnvironment.isHeadless()
                && IJ.getInstance() != null
                && !suppressDialogs
                && Macro.getOptions() == null;
    }

    protected boolean embeddedConfigQcUiAvailable() {
        return !GraphicsEnvironment.isHeadless();
    }

    protected ConfigQcResult showEmbeddedConfigQcDialog(ConfigQcContext context,
                                                        List<ConfigQcStage> stages) {
        attachSavedStatus(context);
        final List<String> dialogStagePath = activeEmbeddedStagePath;
        final int dialogStagePathIndex = activeEmbeddedStagePathIndex;
        if (!SwingUtilities.isEventDispatchThread()) {
            final ConfigQcContext dialogContext = context;
            final List<ConfigQcStage> dialogStages = stages;
            final ConfigQcResult[] result = new ConfigQcResult[]{ConfigQcResult.CANCEL};
            try {
                SwingUtilities.invokeAndWait(new Runnable() {
                    @Override public void run() {
                        result[0] = showEmbeddedConfigQcDialogOnEventThread(
                                dialogContext, dialogStages, dialogStagePath, dialogStagePathIndex);
                    }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ConfigQcResult.CANCEL;
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw embeddedDialogFailure(e.getCause());
            }
            return result[0] == null ? ConfigQcResult.CANCEL : result[0];
        }
        return showEmbeddedConfigQcDialogOnEventThread(
                context, stages, dialogStagePath, dialogStagePathIndex);
    }

    protected ConfigQcResult showEmbeddedConfigQcDialog(ConfigQcContext context,
                                                        List<ConfigQcStage> stages,
                                                        List<String> stagePath,
                                                        int activeStagePathIndex) {
        List<String> previousPath = activeEmbeddedStagePath;
        int previousIndex = activeEmbeddedStagePathIndex;
        activeEmbeddedStagePath = stagePath == null
                ? Collections.<String>emptyList()
                : new ArrayList<String>(stagePath);
        activeEmbeddedStagePathIndex = activeStagePathIndex;
        try {
            return showEmbeddedConfigQcDialog(context, stages);
        } finally {
            activeEmbeddedStagePath = previousPath;
            activeEmbeddedStagePathIndex = previousIndex;
        }
    }

    protected List<String> currentEmbeddedStagePath() {
        return activeEmbeddedStagePath;
    }

    protected int currentEmbeddedStagePathIndex() {
        return activeEmbeddedStagePathIndex;
    }

    private ConfigQcResult showEmbeddedConfigQcDialogOnEventThread(ConfigQcContext context,
                                                                   List<ConfigQcStage> stages,
                                                                   List<String> stagePath,
                                                                   int activeStagePathIndex) {
        String logLabel = embeddedConfigQcDialogLogLabel(context, stages);
        IJ.log("Set Up QC dialog opening: " + logLabel);
        ConfigQcDialog dialog = ConfigQcDialog.createModeless(
                null, context, stages, stagePath, activeStagePathIndex);
        ConfigQcResult result = dialog.showDialog();
        IJ.log("Set Up QC dialog closed: " + logLabel + " -> " + configQcResultLabel(result));
        return result;
    }

    private static String embeddedConfigQcDialogLogLabel(ConfigQcContext context,
                                                         List<ConfigQcStage> stages) {
        StringBuilder sb = new StringBuilder();
        sb.append(stageTitleSummary(stages));
        if (context != null) {
            sb.append(" | C").append(context.getChannelNumber());
            String channelName = context.getChannelName();
            if (channelName != null && !channelName.trim().isEmpty()) {
                sb.append(" (").append(channelName.trim()).append(")");
            }
            sb.append(" | ").append(context.getImageCount()).append(" QC image")
                    .append(context.getImageCount() == 1 ? "" : "s");
        }
        return sb.toString();
    }

    private static String stageTitleSummary(List<ConfigQcStage> stages) {
        if (stages == null || stages.isEmpty()) {
            return "No stages";
        }
        List<String> titles = new ArrayList<String>();
        for (ConfigQcStage stage : stages) {
            if (stage == null) continue;
            String title;
            try {
                title = stage.title();
            } catch (RuntimeException e) {
                title = stage.getClass().getSimpleName();
            }
            if (title != null && !title.trim().isEmpty()) {
                titles.add(title.trim());
            }
        }
        if (titles.isEmpty()) {
            return stages.size() + " stage" + (stages.size() == 1 ? "" : "s");
        }
        return String.join(" -> ", titles);
    }

    private static String configQcResultLabel(ConfigQcResult result) {
        return result == null ? "null" : result.name();
    }

    private void attachSavedStatus(ConfigQcContext context) {
        if (context == null) return;
        String status = savedStatusText(lastWizardSavedAtMillis);
        context.putAttribute(ConfigQcDialog.SAVED_STATUS_ATTRIBUTE,
                hasText(status) ? status : null);
    }

    private static RuntimeException embeddedDialogFailure(Throwable cause) {
        if (cause instanceof RuntimeException) {
            return (RuntimeException) cause;
        }
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        return new IllegalStateException("Could not show configuration QC dialog.", cause);
    }

    /**
     * Collects channel names + LUT colours for the partial setup path using the
     * same combined Channel Identity page as the full Set Up Configuration
     * wizard, so the partial flow runs the exact same step the user already
     * knows. The collected identity is folded into {@code cfg}, and
     * CHANNEL_NAMES + CHANNEL_COLORS are added to {@code fields} so the values
     * are persisted rather than restored away by {@link #restoreExcludedFields}.
     *
     * @return false if the user cancelled the identity page.
     */
    protected boolean collectFilteredChannelIdentity(String directory, File binFolder,
                                                     BinConfig existing, BinUserConfig cfg,
                                                     EnumSet<BinField> fields) {
        BinConfig identityExisting = (existing != null && existing.hasChannelNames()) ? existing : null;
        BinUserConfig draft = cfg.names.isEmpty() ? null : cfg;
        BinUserConfig identity = collectBinConfigFromUser(directory, binFolder, identityExisting, draft);
        if (identity == null) {
            return false;
        }
        cfg.names.clear();
        cfg.names.addAll(identity.names);
        cfg.colors.clear();
        cfg.colors.addAll(identity.colors);
        int channelCount = cfg.names.size();
        padConfigToChannelCount(cfg, channelCount);
        trimConfigToChannelCount(cfg, channelCount);
        fields.add(BinField.CHANNEL_NAMES);
        fields.add(BinField.CHANNEL_COLORS);
        return true;
    }

    protected boolean showFilteredSegmentationMethodsPage(String directory, File binFolder,
                                                          BinUserConfig cfg) {
        if (!canShowFilteredDialogs()) return false;
        ensureConfigHasChannels(cfg);
        PipelineDialog dialog = new PipelineDialog("Set Up Configuration - Segmentation Methods");
        installWizardCancelHook(dialog);
        dialog.addAnalysisHelpHeader("Set Up Configuration", FLASH_Pipeline.IDX_CREATE_BIN);
        dialog.addSetupHelpSubHeader("Segmentation Methods", SetupHelpCatalog.SEGMENTATION_METHOD);
        boolean starDistAvailable = StarDistDetector.isAvailable();
        final CellposeRuntime.Status[] cellposeStatus =
                new CellposeRuntime.Status[]{CellposeRuntime.cachedStatus()};
        boolean cellposeReady = cellposeReadyOrUnknown(cellposeStatus[0]);
        final ModelCatalog modelCatalog = readModelCatalog(projectRootForConfigurationDir(binFolder));
        final List<JComboBox<String>> segmentationCombos = new ArrayList<JComboBox<String>>();
        final List<JLabel> segmentationLabels = new ArrayList<JLabel>();
        for (int i = 0; i < cfg.names.size(); i++) {
            String currentMethod = i < cfg.segmentationMethods.size()
                    ? cfg.segmentationMethods.get(i)
                    : "classical";
            String defSegmentation = i < cfg.segmentationMethods.size()
                    ? segmentationChoiceForDialogDefault(
                            currentMethod, starDistAvailable, cellposeReady, modelCatalog)
                    : SEGMENTATION_CLASSICAL;
            JComboBox<String> segmentationCombo = dialog.addChoice(
                    "C" + (i + 1) + " (" + cfg.names.get(i) + ")",
                    segmentationOptionsForMethod(currentMethod, modelCatalog), defSegmentation);
            final JLabel segmentationDesc = dialog.addHelpText(
                    segmentationDescriptionFor(defSegmentation, starDistAvailable, cellposeStatus[0]));
            segmentationCombos.add(segmentationCombo);
            segmentationLabels.add(segmentationDesc);
            segmentationCombo.addItemListener(new java.awt.event.ItemListener() {
                @Override public void itemStateChanged(java.awt.event.ItemEvent e) {
                    if (e.getStateChange() == java.awt.event.ItemEvent.SELECTED) {
                        segmentationDesc.setText(segmentationDescriptionFor(
                                (String) e.getItem(), starDistAvailable, cellposeStatus[0]));
                    }
                }
            });
        }
        CellposeRuntime.probeAsync().whenCompleteAsync(
                new java.util.function.BiConsumer<CellposeRuntime.Status, Throwable>() {
                    @Override public void accept(CellposeRuntime.Status status, Throwable throwable) {
                        if (throwable != null || status == null) return;
                        cellposeStatus[0] = status;
                        for (int i = 0; i < segmentationLabels.size(); i++) {
                            JComboBox<String> combo = segmentationCombos.get(i);
                            JLabel label = segmentationLabels.get(i);
                            Object selected = combo == null ? null : combo.getSelectedItem();
                            label.setText(segmentationDescriptionFor(
                                    selected == null ? SEGMENTATION_CLASSICAL : String.valueOf(selected),
                                    starDistAvailable,
                                    status));
                        }
                    }
                },
                SwingUtilities::invokeLater);
        if (!dialog.showDialog()) return false;

        List<String> selections = new ArrayList<String>();
        for (int i = 0; i < cfg.names.size(); i++) {
            selections.add(dialog.getNextChoice());
        }
        if (selectionsContain(selections, SEGMENTATION_STARDIST)
                && !gateStarDistFeature("StarDist 3D segmentation")) {
            return false;
        }
        boolean preferCellposeGpu = BinConfig.DEFAULT_CELLPOSE_USE_GPU;
        if (selectionsContain(selections, SEGMENTATION_CELLPOSE)) {
            if (!gateCellposeFeature("Cellpose segmentation")) {
                return false;
            }
            CellposeRuntime.Status readyStatus = CellposeRuntime.probeConfigured();
            preferCellposeGpu = readyStatus.ready ? readyStatus.gpuAvailable : BinConfig.DEFAULT_CELLPOSE_USE_GPU;
        }
        for (int i = 0; i < cfg.names.size(); i++) {
            String existingMethod = i < cfg.segmentationMethods.size() ? cfg.segmentationMethods.get(i) : null;
            applySegmentationSelection(cfg.segmentationMethods, i, selections.get(i), existingMethod, preferCellposeGpu);
            normalizeNewStarDistSizeDefault(cfg, i, existingMethod);
        }
        return true;
    }

    protected boolean showFilteredZSlicePage(String directory, File binFolder,
                                             BinUserConfig cfg) {
        if (!canShowFilteredDialogs()) return false;
        Boolean accepted = showAnalysisScopeDialog(cfg, false);
        if (accepted == null) return false;
        if (cfg.usesZSliceSubset()) {
            String result = interactiveZSliceSubsetQC(directory, cfg);
            return "done".equals(result);
        }
        return true;
    }

    protected boolean showFilteredQcPages(String directory, File binFolder,
                                          BinUserConfig cfg, Set<BinField> fields) {
        if (!canShowFilteredDialogs()) return false;
        boolean doMinMax = fields.contains(BinField.DISPLAY_MIN_MAX);
        boolean doObjectThreshold = fields.contains(BinField.OBJECT_THRESHOLDS);
        boolean doIntensityThreshold = fields.contains(BinField.INTENSITY_THRESHOLDS);
        boolean doParticleSize = fields.contains(BinField.PARTICLE_SIZES);
        boolean doSegmentationMethod = fields.contains(BinField.SEGMENTATION_METHODS);
        boolean doFilterParameters = fields.contains(BinField.FILTER_PRESETS);
        // Partial setup auto-selects the settings this analysis needs for every
        // channel instead of prompting with the Settings Mode toggle screen.
        boolean[][] customSettings = autoSelectedFilteredSettings(cfg,
                doFilterParameters, doMinMax, doObjectThreshold, doIntensityThreshold,
                doParticleSize, doSegmentationMethod);

        boolean[][] qcSettings = qcSelectionSettings(customSettings, cfg);
        if (anyTrue(qcSettings)) {
            QcImageOpenResult qcOpenResult =
                    openImagesForQC(directory, binFolder, cfg,
                            qcSettings);
            showQcOpenMessageIfPresent(qcOpenResult);
            if (qcOpenResult.isCancel()) return false;
            if (qcOpenResult.isReady()) {
                String qcResult = interactiveQC(qcOpenResult.images, cfg, binFolder, qcSettings);
                cleanupImages(qcOpenResult.images);
                return "done".equals(qcResult) || "skip".equals(qcResult);
            }
        }

        for (int i = 0; i < cfg.names.size(); i++) {
            if (doMinMax && !customSettings[SETTINGS_MIN_MAX][i]) cfg.minmax.set(i, "None");
            boolean roiIntensitySelected = customSettings[SETTINGS_ROI_INTENSITY_THRESHOLD][i];
            boolean objectThresholdSelected = customSettings[SETTINGS_OBJECT_THRESHOLD][i];
            if (doIntensityThreshold && !roiIntensitySelected) cfg.intensityThresholds.set(i, "default");
            if (doObjectThreshold && !objectThresholdSelected) cfg.objectThresholds.set(i, "default");
            if (doParticleSize && !customSettings[SETTINGS_OBJECT_SIZE_FILTER][i]) cfg.sizes.set(i, "100-Infinity");
        }
        return true;
    }

    /**
     * Builds the per-channel settings matrix the Settings Mode fork would
     * produce with every requested setting toggled ON for every channel. The
     * partial setup path auto-selects the settings the analysis needs instead of
     * prompting, mirroring {@link #readGranularCustomForkSelections} with each
     * requested fork toggle on.
     */
    boolean[][] autoSelectedFilteredSettings(BinUserConfig cfg,
                                             boolean doFilterParameters,
                                             boolean doMinMax,
                                             boolean doObjectThreshold,
                                             boolean doIntensityThreshold,
                                             boolean doParticleSize,
                                             boolean doSegmentationMethod) {
        int n = cfg == null ? 0 : cfg.names.size();
        boolean[][] result = new boolean[SETTINGS_SLOT_COUNT][n];
        boolean objectAnalysis = doSegmentationMethod || doParticleSize;
        for (int i = 0; i < n; i++) {
            String method = cfg != null && i < cfg.segmentationMethods.size()
                    ? safe(cfg.segmentationMethods.get(i)) : "classical";
            boolean classical = !method.startsWith("stardist") && !method.startsWith("cellpose");
            if (doFilterParameters) result[SETTINGS_FILTER_PARAMETERS][i] = true;
            if (doMinMax) result[SETTINGS_MIN_MAX][i] = true;
            if (doIntensityThreshold) {
                result[SETTINGS_ROI_INTENSITY_THRESHOLD][i] = true;
            }
            if (doObjectThreshold && classical) {
                result[SETTINGS_OBJECT_THRESHOLD][i] = true;
            }
            if (objectAnalysis) {
                result[SETTINGS_SEGMENTATION_METHOD][i] = doSegmentationMethod;
                result[SETTINGS_OBJECT_THRESHOLD][i] = true;
                result[SETTINGS_OBJECT_SIZE_FILTER][i] = true;
            }
        }
        return result;
    }

    private static void restoreExcludedFields(BinUserConfig cfg, BinUserConfig original,
                                              Set<BinField> fields) {
        if (cfg == null || original == null || fields == null) return;
        if (!fields.contains(BinField.CHANNEL_NAMES)) replaceListIfPresent(cfg.names, original.names);
        if (!fields.contains(BinField.CHANNEL_COLORS)) replaceListIfPresent(cfg.colors, original.colors);
        boolean preserveObjectQcDependencies = fields.contains(BinField.SEGMENTATION_METHODS);
        if (!fields.contains(BinField.OBJECT_THRESHOLDS) && !preserveObjectQcDependencies) replaceListIfPresent(cfg.objectThresholds, original.objectThresholds);
        if (!fields.contains(BinField.PARTICLE_SIZES) && !preserveObjectQcDependencies) replaceListIfPresent(cfg.sizes, original.sizes);
        if (!fields.contains(BinField.DISPLAY_MIN_MAX)) replaceListIfPresent(cfg.minmax, original.minmax);
        if (!fields.contains(BinField.INTENSITY_THRESHOLDS)) replaceListIfPresent(cfg.intensityThresholds, original.intensityThresholds);
        if (!fields.contains(BinField.SEGMENTATION_METHODS)) replaceListIfPresent(cfg.segmentationMethods, original.segmentationMethods);
        if (!fields.contains(BinField.FILTER_PRESETS)) replaceListIfPresent(cfg.filterPresets, original.filterPresets);
        if (!fields.contains(BinField.Z_SLICE)) {
            cfg.zSliceMode = original.zSliceMode;
            cfg.zSliceSelections.clear();
            cfg.zSliceSelections.putAll(original.zSliceSelections);
        }
    }

    private void writeFilteredChannelConfig(File binFolder, BinUserConfig cfg,
                                            Set<BinField> fields) throws IOException {
        File settingsDir = channelConfigSettingsDir(binFolder);
        ChannelConfig existing = ChannelConfigIO.read(settingsDir);
        ChannelConfig cc = mergeIntoExisting(
                binFolder, cfg, null, -1, "Partial setup",
                -1, null, ChannelConfig.PropertyStatus.CONFIGURED);
        applyPartialFieldStatuses(cc, existing, fields);
        cc.complete = null;
        cc.complete = Boolean.valueOf(ChannelConfigIO.isComplete(cc));
        ChannelConfigIO.write(settingsDir, cc);
        recordOutput(new File(settingsDir, ChannelConfigIO.FILE_NAME), "json");
    }

    private static void applyPartialFieldStatuses(ChannelConfig cfg, ChannelConfig existing,
                                                  Set<BinField> fields) {
        if (cfg == null || cfg.channels == null) return;
        Set<String> requested = requestedChannelProperties(fields);
        boolean sameChannelCount = existing != null
                && existing.channels != null
                && existing.channels.size() == cfg.channels.size();
        for (int i = 0; i < cfg.channels.size(); i++) {
            ChannelConfig.Channel channel = cfg.channels.get(i);
            ChannelConfig.Channel old = sameChannelCount ? channelAt(existing, i) : null;
            for (String key : CHANNEL_CONFIG_PROPERTIES) {
                ChannelConfig.PropertyStatus status = requested.contains(key)
                        ? configuredStatusAfterPartial(old, key)
                        : previousOrPendingStatus(old, key);
                setStatus(channel, key, status);
            }
        }
    }

    private static Set<String> requestedChannelProperties(Set<BinField> fields) {
        Set<String> out = new HashSet<String>();
        if (fields == null) return out;
        for (BinField field : fields) {
            switch (field) {
                case CHANNEL_NAMES:
                    out.add(ChannelConfig.P_NAME);
                    out.add(ChannelConfig.P_MARKER);
                    break;
                case CHANNEL_COLORS:
                    out.add(ChannelConfig.P_COLOR);
                    break;
                case OBJECT_THRESHOLDS:
                    out.add(ChannelConfig.P_THRESHOLD);
                    break;
                case PARTICLE_SIZES:
                    out.add(ChannelConfig.P_SIZE);
                    break;
                case DISPLAY_MIN_MAX:
                    out.add(ChannelConfig.P_MINMAX);
                    break;
                case INTENSITY_THRESHOLDS:
                    out.add(ChannelConfig.P_INTENSITY);
                    break;
                case SEGMENTATION_METHODS:
                    out.add(ChannelConfig.P_SEGMENTATION);
                    break;
                case FILTER_PRESETS:
                    out.add(ChannelConfig.P_FILTER);
                    break;
                case Z_SLICE:
                default:
                    break;
            }
        }
        return out;
    }

    private static ChannelConfig.PropertyStatus configuredStatusAfterPartial(
            ChannelConfig.Channel old, String propertyKey) {
        return previousOrPendingStatus(old, propertyKey) == ChannelConfig.PropertyStatus.COMMITTED
                ? ChannelConfig.PropertyStatus.COMMITTED
                : ChannelConfig.PropertyStatus.CONFIGURED;
    }

    private static ChannelConfig.PropertyStatus previousOrPendingStatus(
            ChannelConfig.Channel old, String propertyKey) {
        return old == null
                ? ChannelConfig.PropertyStatus.PENDING
                : old.statusOf(propertyKey);
    }

    private static void replaceListIfPresent(List<String> target, List<String> source) {
        if (source == null || source.isEmpty()) return;
        target.clear();
        target.addAll(source);
    }

    private static List<String> copyList(List<String> source) {
        return source == null ? new ArrayList<String>() : new ArrayList<String>(source);
    }

    private static void replaceList(List<String> target, List<String> source) {
        if (target == null) return;
        target.clear();
        if (source != null) target.addAll(source);
    }

    private static void padConfigToChannelCount(BinUserConfig cfg, int channelCount) {
        if (cfg == null) return;
        while (cfg.names.size() < channelCount) cfg.names.add("Channel" + (cfg.names.size() + 1));
        while (cfg.colors.size() < channelCount) cfg.colors.add("Grays");
        while (cfg.objectThresholds.size() < channelCount) cfg.objectThresholds.add("default");
        while (cfg.sizes.size() < channelCount) cfg.sizes.add("100-Infinity");
        while (cfg.minmax.size() < channelCount) cfg.minmax.add("None");
        while (cfg.filterPresets.size() < channelCount) cfg.filterPresets.add("Default");
        while (cfg.intensityThresholds.size() < channelCount) cfg.intensityThresholds.add("default");
        while (cfg.segmentationMethods.size() < channelCount) cfg.segmentationMethods.add("classical");
        while (cfg.markerIds.size() < channelCount) cfg.markerIds.add("");
        while (cfg.markerShapes.size() < channelCount) cfg.markerShapes.add("");
        while (cfg.markerCrowdingSensitive.size() < channelCount) cfg.markerCrowdingSensitive.add(Boolean.FALSE);
    }

    private static BinUserConfig normalizedConfigForChannelCount(BinConfig existing,
                                                                  BinUserConfig draft,
                                                                  int channelCount) {
        BinUserConfig cfg = draft == null ? fromBinConfig(existing) : copyBinUserConfig(draft);
        padConfigToChannelCount(cfg, channelCount);
        trimConfigToChannelCount(cfg, channelCount);
        return cfg;
    }

    static BinUserConfig copyBinUserConfig(BinUserConfig source) {
        if (source == null) return null;
        BinUserConfig copy = new BinUserConfig(
                new ArrayList<String>(source.names),
                new ArrayList<String>(source.colors),
                new ArrayList<String>(source.objectThresholds),
                new ArrayList<String>(source.sizes),
                new ArrayList<String>(source.minmax),
                new ArrayList<String>(source.filterPresets),
                new ArrayList<String>(source.intensityThresholds));
        copy.segmentationMethods.clear();
        copy.segmentationMethods.addAll(source.segmentationMethods);
        copy.markerIds.clear();
        copy.markerIds.addAll(source.markerIds);
        copy.markerShapes.clear();
        copy.markerShapes.addAll(source.markerShapes);
        copy.markerCrowdingSensitive.clear();
        copy.markerCrowdingSensitive.addAll(source.markerCrowdingSensitive);
        copy.zSliceMode = source.zSliceMode == null ? ZSliceMode.FULL : source.zSliceMode;
        copy.zSliceSelections.putAll(source.zSliceSelections);
        return copy;
    }

    private static void trimConfigToChannelCount(BinUserConfig cfg, int channelCount) {
        if (cfg == null) return;
        trimList(cfg.names, channelCount);
        trimList(cfg.colors, channelCount);
        trimList(cfg.objectThresholds, channelCount);
        trimList(cfg.sizes, channelCount);
        trimList(cfg.minmax, channelCount);
        trimList(cfg.filterPresets, channelCount);
        trimList(cfg.intensityThresholds, channelCount);
        trimList(cfg.segmentationMethods, channelCount);
        trimList(cfg.markerIds, channelCount);
        trimList(cfg.markerShapes, channelCount);
        trimList(cfg.markerCrowdingSensitive, channelCount);
    }

    private static <T> void trimList(List<T> values, int size) {
        while (values.size() > size) {
            values.remove(values.size() - 1);
        }
    }

    private void executeInternal(String directory) {
        File binFolder = configurationWriteDir(directory);
        customFilterPromptsHandled.clear();
        customFilterDemotions.clear();

        if (shouldRunHeadlessPreset()) {
            try {
                runHeadlessCreateBin(directory, binFolder);
            } catch (Exception e) {
                recordError("Set Up Configuration headless preset failed", e);
                IJ.handleException(e);
            }
            return;
        }

        // ── Override existing configuration ──────────────────────────────
        if (headless || GraphicsEnvironment.isHeadless()) {
            if (cliConfig != null) {
                throw new IllegalStateException("Cannot run Set Up Configuration headless "
                        + "without bin.* CLI parameters. Pass bin.preset=... or bin.channelN_* "
                        + "options, or run setup interactively first.");
            }
            String message = "Set Up Configuration is headed-only without CLI/preset "
                    + "parameters; command/headless invocation cannot drive the interactive "
                    + "wizard. Provide presetJson, bin.preset, or bin.channelN_* options, "
                    + "or run setup in the FLASH GUI.";
            IJ.log("[FLASH] " + message + " Skipping.");
            recordWarn(message);
            return;
        }

        boolean overrideMode = false;
        boolean overrideAll = false;
        boolean overrideMinMax = false;
        boolean overrideThresholds = false;
        boolean overrideParticleSize = false;
        boolean overrideSegmentationMethod = false;
        boolean overrideFilterParameters = false;
        boolean overrideZSliceSelection = false;
        BinConfig existingCfg = null;

        if (FlashProjectLayout.forDirectory(directory).existingConfigurationDir() != null) {
            ChannelConfigIO.ReadState existingState = ChannelConfigIO.readResult(binFolder).state;
            if (existingState == ChannelConfigIO.ReadState.NEWER_VERSION) {
                IJ.showMessage("Set Up Configuration",
                        "This project's setup file was made by a newer version of FLASH.\n"
                        + "Update FLASH to open this project. Your configuration was left "
                        + "untouched.");
                return;
            }
            if (existingState == ChannelConfigIO.ReadState.CORRUPT
                    && ChannelConfigIO.readBackup(binFolder) == null) {
                // Damaged file and no recoverable backup: keep a copy instead of
                // silently overwriting it, then start a fresh configuration. (When
                // a good .bak exists, the recovery-aware read below loads it and
                // the normal override flow continues with the recovered config.)
                ChannelConfigIO.backupThenDelete(binFolder);
                IJ.showMessage("Set Up Configuration",
                        "This project's setup file looked damaged and could not be read.\n"
                        + "A copy was kept as channel_config.corrupt-....json.\n"
                        + "Starting a new configuration.");
                try {
                    handleFullCreation(directory, binFolder, null);
                } catch (Exception e) {
                    recordError("Set Up Configuration failed", e);
                    IJ.handleException(e);
                }
                return;
            }
            try {
                existingCfg = BinConfigIO.readFromDirectory(directory);
            } catch (IOException e) {
                // Configuration exists but is malformed — offer full override
            }

            PipelineDialog ovr = setupAnalysisDialog("Set Up Configuration");
            ovr.setPrimaryButtonText(NextStepLabels.SETUP);
            ovr.addAnalysisHelpHeader("Set Up Configuration", FLASH_Pipeline.IDX_CREATE_BIN);
            ovr.addSubHeader("Existing Configuration Found");
            ovr.addMessage("A saved configuration was found.");
            ovr.addHeader("Override Options", flash.pipeline.ui.FlashIcons.section("settings"));

            ovr.addSubHeader("Full Reset");
            ToggleSwitch allToggle = ovr.addToggle("Override ALL settings (start from scratch)", false);

            ovr.addHeader("Channel Identity & Processing", flash.pipeline.ui.FlashIcons.section("tags"));
            ovr.addSetupHelpSubHeader("Set Filter and Parameters", SetupHelpCatalog.FILTER_PARAMETERS);
            ToggleSwitch fhToggle = ovr.addToggle("Set Filter and Parameters", false);

            ovr.addHeader("Image Display", flash.pipeline.ui.FlashIcons.section("sun"));
            ovr.addSetupHelpSubHeader("Display Ranges", SetupHelpCatalog.DISPLAY_RANGE);
            ToggleSwitch mmToggle = ovr.addToggle("Override Custom Min-Max Display Ranges", false);

            ovr.addHeader("ROI / Intensity Analysis", flash.pipeline.ui.FlashIcons.section("ruler"));
            ovr.addSetupHelpSubHeader("Channel Thresholds", SetupHelpCatalog.CHANNEL_THRESHOLD);
            ToggleSwitch thToggle = ovr.addToggle("Override Channel Thresholds", false);

            ovr.addHeader("Object Analysis", flash.pipeline.ui.FlashIcons.section("microscope"));
            ovr.addSetupHelpSubHeader("Segmentation Method", SetupHelpCatalog.SEGMENTATION_METHOD);
            ToggleSwitch segToggle = ovr.addToggle("Override Segmentation Method", false);
            ovr.addSetupHelpSubHeader("Classical Object Size Filter",
                    SetupHelpCatalog.CLASSICAL_OBJECT_SEGMENTATION);
            ToggleSwitch szToggle = ovr.addToggle("Override Particle Sizes (n Voxels)", false);

            ovr.addHeader("Z-Stack Scope", flash.pipeline.ui.FlashIcons.section("stack"));
            ovr.addSetupHelpSubHeader("Z-Slice Subset", SetupHelpCatalog.Z_SLICE_SUBSET);
            ToggleSwitch zSliceToggle = ovr.addToggle("Override z-slice subset selection", false);

            allToggle.addChangeListener(new Runnable() {
                @Override public void run() {
                    boolean on = allToggle.isSelected();
                    mmToggle.setEnabled(!on);
                    thToggle.setEnabled(!on);
                    szToggle.setEnabled(!on);
                    segToggle.setEnabled(!on);
                    fhToggle.setEnabled(!on);
                    zSliceToggle.setEnabled(!on);
                    if (on) {
                        mmToggle.setSelected(false);
                        thToggle.setSelected(false);
                        szToggle.setSelected(false);
                        segToggle.setSelected(false);
                        fhToggle.setSelected(false);
                        zSliceToggle.setSelected(false);
                    }
                }
            });

            if (!ovr.showDialog()) {
                return;
            }

            overrideAll = ovr.getNextBoolean();
            overrideFilterParameters = ovr.getNextBoolean();
            overrideMinMax = ovr.getNextBoolean();
            overrideThresholds = ovr.getNextBoolean();
            overrideSegmentationMethod = ovr.getNextBoolean();
            overrideParticleSize = ovr.getNextBoolean();
            overrideZSliceSelection = ovr.getNextBoolean();

            if (!overrideAll && !overrideMinMax && !overrideThresholds
                    && !overrideParticleSize && !overrideSegmentationMethod
                    && !overrideFilterParameters && !overrideZSliceSelection) {
                IJ.showMessage("Set Up Configuration", "No override selected. Nothing to do.");
                return;
            }
            overrideMode = true;
        }

        // ── Fresh creation: confirm ─────────────────────────────────────
        if (!overrideMode) {
            PipelineDialog confirm = setupAnalysisDialog("Set Up Configuration");
            confirm.setPrimaryButtonText(NextStepLabels.CHANNEL_SETUP);
            confirm.addAnalysisHelpHeader("Set Up Configuration", FLASH_Pipeline.IDX_CREATE_BIN);
            confirm.addSubHeader("New Configuration");
            confirm.addMessage("No Configuration folder detected. Click Next: Channel setup to create one.");
            if (!confirm.showDialog()) return;
            if (!binFolder.isDirectory() && !binFolder.mkdirs() && !binFolder.isDirectory()) {
                IJ.error("Set Up Configuration", "Failed to create: " + binFolder.getAbsolutePath());
                return;
            }
        }

        try {
            if (overrideMode && !overrideAll) {
                handleSelectiveOverride(directory, binFolder, existingCfg,
                        overrideMinMax, overrideThresholds, overrideParticleSize,
                        overrideSegmentationMethod, overrideFilterParameters,
                        overrideZSliceSelection);
            } else {
                handleFullCreation(directory, binFolder, overrideAll ? existingCfg : null);
            }
        } catch (Exception e) {
            recordError("Set Up Configuration failed", e);
            IJ.handleException(e);
        }
    }

    private static PipelineDialog setupAnalysisDialog(String title) {
        PipelineDialog dialog = new PipelineDialog(title, PipelineDialog.Phase.SETUP);
        dialog.setBreadcrumb(null, null);
        return dialog;
    }

    private boolean shouldRunHeadlessPreset() {
        return (headless || GraphicsEnvironment.isHeadless())
                && (commandPreset != null
                || (cliConfig != null
                && cliConfig.getBin() != null
                && cliConfig.getBin().hasConfiguration()));
    }

    private File configurationWriteDir(String directory) {
        return FlashProjectLayout.forDirectory(directory).configurationWriteDir();
    }

    private void runHeadlessCreateBin(String directory, File binFolder) throws IOException {
        if (!binFolder.isDirectory() && !binFolder.mkdirs() && !binFolder.isDirectory()) {
            throw new IOException("Failed to create " + binFolder.getAbsolutePath());
        }
        writeDefaultFilter(binFolder);

        BinPreset preset = commandPreset;
        CLIConfig.CreateBinConfig binCli = cliConfig == null ? null : cliConfig.getBin();
        if (preset == null && binCli != null
                && binCli.getPresetName() != null && !binCli.getPresetName().trim().isEmpty()) {
            preset = new BinPresetIO(new File(directory)).load(binCli.getPresetName());
        }

        BinConfig source = preset == null ? new BinConfig() : preset.getPayload();
        if (binCli != null) {
            applyCliBinOverrides(source, binCli);
        }
        BinUserConfig cfg = fromBinConfig(source);
        if (preset != null) {
            cfg.markerIds.clear();
            cfg.markerShapes.clear();
            cfg.markerCrowdingSensitive.clear();
            cfg.markerIds.addAll(preset.getMarkerIds());
            cfg.markerShapes.addAll(preset.getMarkerShapes());
            cfg.markerCrowdingSensitive.addAll(preset.getMarkerCrowdingSensitive());
        }
        ensureConfigHasChannels(cfg);

        writeChannelFilters(binFolder, cfg);
        writeBinConfigFiles(binFolder, cfg);
        IJ.log("[CLI] Set Up Configuration wrote the Configuration folder using "
                + (preset == null ? "explicit bin.* flags" : "preset=" + preset.getName()));
    }

    private static void applyCliBinOverrides(BinConfig config, CLIConfig.CreateBinConfig binCli) {
        if (config == null || binCli == null) return;
        applyIndexed(config.channelNames, binCli.getNames(), "Channel");
        applyIndexed(config.channelColors, binCli.getColors(), "Grays");
        applyIndexed(config.channelThresholds, binCli.getObjectThresholds(), "default");
        applyIndexed(config.channelSizes, binCli.getSizes(), "100-Infinity");
        applyIndexed(config.channelMinMax, binCli.getMinmax(), "None");
        applyIndexed(config.channelIntensityThresholds, binCli.getIntensityThresholds(), "default");
        applyIndexed(config.channelFilterPresets, binCli.getFilterPresets(), "Default");
        applyIndexed(config.segmentationMethods, binCli.getSegmentationMethods(), "classical");
    }

    private static void applyIndexed(List<String> target, Map<Integer, String> overrides, String fallback) {
        if (target == null || overrides == null) return;
        for (Map.Entry<Integer, String> entry : overrides.entrySet()) {
            if (entry.getKey() == null || entry.getKey().intValue() < 0) continue;
            int index = entry.getKey().intValue();
            while (target.size() <= index) {
                target.add("Channel".equals(fallback) ? "Channel" + (target.size() + 1) : fallback);
            }
            target.set(index, entry.getValue());
        }
    }

    // ── Full creation flow (with Back navigation) ───────────────────────

    void handleFullCreation(String directory, File binFolder, BinConfig existing) throws IOException {
        writeDefaultFilter(binFolder);

        BinUserConfig cfg = null;
        boolean[][] customSettings = null;

        int step = 1; // 1=collectConfig, 2=analysisScope, 3=granularFork, 4=zSliceQC, 5=channelQC, 6=review
        boolean editingFromReview = false;
        boolean reviewDependencyRun = false;
        int lastStepBeforeReview = 5;
        boolean resumeQcFromSavedProgress = false;
        WizardResumeState resume = readWizardResumeState(directory, binFolder);
        if (resume != null) {
            int choice = showResumePrompt(resume);
            if (choice == 2) return;
            if (choice == 1) {
                File settingsDir = channelConfigSettingsDir(binFolder);
                ChannelConfigIO.backupThenDelete(settingsDir);
                deleteRecursively(new File(settingsDir, ".draft"));
            } else {
                cfg = resume.cfg;
                customSettings = resume.customSettings;
                step = Math.max(1, Math.min(6, resume.stepIndex));
                resumeQcFromSavedProgress = step == 5;
            }
        }
        lastWizardSavedAtMillis = readSavedAtMillis(binFolder);

        try {
        while (step >= 1 && step <= 6) {
            setWizardCancelContext(binFolder, cfg, customSettings, step, wizardStepLabel(step));
            switch (step) {
                case 1: {
                    int previousChannelCount = cfg == null || cfg.names == null ? 0 : cfg.names.size();
                    cfg = collectBinConfigFromUser(directory, binFolder, existing, cfg);
                    if (cfg == null) {
                        if (!shouldExitAfterWizardCancel(binFolder, cfg, customSettings, step, wizardStepLabel(step))) {
                            break;
                        }
                        if (!hasFiles(binFolder)) deleteRecursively(binFolder);
                        showWizardCancelledMessageIfNeeded();
                        return;
                    }
                    int currentChannelCount = cfg.names == null ? 0 : cfg.names.size();
                    if (editingFromReview && previousChannelCount != currentChannelCount) {
                        customSettings = settingsMatrixForChannelCount(customSettings, currentChannelCount);
                    }
                    writeChannelFilters(binFolder, cfg);
                    rememberSavedAt(persistIncremental(binFolder, cfg, customSettings,
                            1, wizardStepLabel(1), -1, null));
                    if (editingFromReview && !reviewDependencyRun) {
                        if (previousChannelCount != currentChannelCount) {
                            reviewDependencyRun = true;
                            step = 2;
                        } else {
                            lastStepBeforeReview = 1;
                            step = 6;
                        }
                    } else {
                        step = 2;
                    }
                    break;
                }
                case 2: {
                    Boolean scopeAccepted = showAnalysisScopeDialog(cfg, true);
                    if (scopeAccepted == null) {
                        if (lastWasBack) {
                            if (editingFromReview) reviewDependencyRun = true;
                            step = 1;
                            break;
                        }
                        if (!shouldExitAfterWizardCancel(binFolder, cfg, customSettings, step, wizardStepLabel(step))) {
                            break;
                        }
                        if (!hasFiles(binFolder)) deleteRecursively(binFolder);
                        showWizardCancelledMessageIfNeeded();
                        return;
                    }
                    rememberSavedAt(persistIncremental(binFolder, cfg, customSettings,
                            2, wizardStepLabel(2), -1, null));
                    if (editingFromReview && !reviewDependencyRun) {
                        lastStepBeforeReview = 2;
                        step = cfg.usesZSliceSubset() ? 4 : 6;
                    } else {
                        step = 3;
                    }
                    break;
                }
                case 3: {
                    customSettings = settingsMatrixForChannelCount(customSettings, cfg.names.size());
                    setWizardCancelContext(binFolder, cfg, customSettings, step, wizardStepLabel(step));
                    BinConfig previousStatusReference = settingsStatusReference;
                    settingsStatusReference = existing;
                    boolean[][] selectedSettings;
                    try {
                        selectedSettings = showGranularCustomFork(
                                cfg, true, true, true, true, true, customSettings);
                    } finally {
                        settingsStatusReference = previousStatusReference;
                    }
                    if (selectedSettings == null) {
                        if (lastWasBack) {
                            if (editingFromReview) reviewDependencyRun = true;
                            step = 2;
                            break;
                        }
                        if (!shouldExitAfterWizardCancel(binFolder, cfg, customSettings, step, wizardStepLabel(step))) {
                            break;
                        }
                        if (!hasFiles(binFolder)) deleteRecursively(binFolder);
                        showWizardCancelledMessageIfNeeded();
                        return;
                    }
                    customSettings = selectedSettings;
                    rememberSavedAt(persistIncremental(binFolder, cfg, customSettings,
                            3, wizardStepLabel(3), -1, null));
                    if (editingFromReview && !reviewDependencyRun) {
                        lastStepBeforeReview = 3;
                        step = needsQcImages(customSettings, cfg) ? 5 : 6;
                    } else {
                        step = cfg.usesZSliceSubset() ? 4
                                : (needsQcImages(customSettings, cfg) ? 5 : 6);
                        if (step == 6) lastStepBeforeReview = 3;
                    }
                    break;
                }
                case 4: {
                    String zSliceResult = interactiveZSliceSubsetQC(directory, cfg);
                    if ("back".equals(zSliceResult)) {
                        if (editingFromReview) reviewDependencyRun = true;
                        step = 3;
                        break;
                    }
                    if ("cancel".equals(zSliceResult)) {
                        if (!shouldExitAfterWizardCancel(binFolder, cfg, customSettings, step, wizardStepLabel(step))) {
                            break;
                        }
                        if (!hasFiles(binFolder)) deleteRecursively(binFolder);
                        showWizardCancelledMessageIfNeeded();
                        return;
                    }
                    rememberSavedAt(persistIncremental(binFolder, cfg, customSettings,
                            4, wizardStepLabel(4), -1, null));
                    if (editingFromReview && !reviewDependencyRun) {
                        lastStepBeforeReview = 4;
                    }
                    step = needsQcImages(customSettings, cfg) ? 5 : 6;
                    if (step == 6) lastStepBeforeReview = 4;
                    break;
                }
                case 5: {
                    boolean[][] qcSettings = qcSelectionSettings(customSettings, cfg);
                    QcImageOpenResult qcOpenResult =
                            openImagesForQC(directory, binFolder, cfg,
                                    qcSettings);
                    showQcOpenMessageIfPresent(qcOpenResult);
                    if (qcOpenResult.isCancel()) {
                        if (!shouldExitAfterWizardCancel(binFolder, cfg, customSettings, step, wizardStepLabel(step))) {
                            break;
                        }
                        if (!hasFiles(binFolder)) deleteRecursively(binFolder);
                        if (!hasText(qcOpenResult.message)) {
                            showWizardCancelledMessageIfNeeded();
                        }
                        return;
                    }
                    if (qcOpenResult.isReady()) {
                        boolean useSavedQcProgress = resumeQcFromSavedProgress;
                        String qcResult = anyTrue(qcSettings)
                                ? interactiveQC(qcOpenResult.images, cfg, binFolder, qcSettings,
                                        useSavedQcProgress)
                                : "done";
                        cleanupImages(qcOpenResult.images);
                        if ("back".equals(qcResult)) {
                            resumeQcFromSavedProgress = false;
                            if (editingFromReview) reviewDependencyRun = true;
                            step = 3;
                            break;
                        }
                        if ("cancel".equals(qcResult)) {
                            if (!shouldExitAfterWizardCancel(binFolder, cfg, customSettings, step, wizardStepLabel(step))) {
                                break;
                            }
                            if (!hasFiles(binFolder)) deleteRecursively(binFolder);
                            showWizardCancelledMessageIfNeeded();
                            return;
                        }
                        // "done" or "skip" → proceed to save
                    }
                    rememberSavedAt(persistIncremental(binFolder, cfg, customSettings,
                            5, wizardStepLabel(5), -1, null));
                    resumeQcFromSavedProgress = false;
                    lastStepBeforeReview = 5;
                    step = 6;
                    break;
                }
                case 6: {
                    reviewDependencyRun = false;
                    String reviewAction = showReviewDialog(binFolder, cfg, customSettings);
                    if (REVIEW_ACTION_SAVE.equals(reviewAction)) {
                        persistCommit(binFolder, cfg, customSettings);
                        if (!shouldSkipReviewUi()) {
                            IJ.showMessage("Set Up Configuration", "Created configuration in:\n" + binFolder.getAbsolutePath());
                        }
                        return;
                    }
                    if (REVIEW_ACTION_SAVED.equals(reviewAction)) {
                        return;
                    }
                    if (REVIEW_ACTION_BACK.equals(reviewAction)) {
                        editingFromReview = true;
                        step = Math.max(1, Math.min(5, lastStepBeforeReview));
                        break;
                    }
                    int editStep = parseReviewEditStep(reviewAction);
                    if (editStep >= 1 && editStep <= 5) {
                        editingFromReview = true;
                        step = editStep;
                        break;
                    }
                    if (!shouldExitAfterWizardCancel(binFolder, cfg, customSettings, step, wizardStepLabel(step))) {
                        break;
                    }
                    if (!hasFiles(binFolder)) deleteRecursively(binFolder);
                    showWizardCancelledMessageIfNeeded();
                    return;
                }
            }
        }
        } finally {
            clearWizardCancelContext();
        }
    }

    // ── Selective override flow (with Back navigation) ──────────────────

    protected int showResumePrompt(WizardResumeState draft) {
        ResumePromptDialog.Choice choice = ResumePromptDialog.show(
                null,
                draft == null ? Collections.<String>emptyList() : draft.progressLines,
                draft == null ? 0L : draft.lastUpdatedMillis);
        if (choice == ResumePromptDialog.Choice.START_OVER) return 1;
        if (choice == ResumePromptDialog.Choice.CANCEL) return 2;
        return 0;
    }

    protected CancelConfirmationDialog.Choice showCancelConfirmation(Window owner, String stepLabel,
                                                                     List<String> progressLines,
                                                                     String draftPath) {
        return CancelConfirmationDialog.show(owner, stepLabel, progressLines, draftPath);
    }

    private void setWizardCancelContext(File binFolder, BinUserConfig cfg,
                                       boolean[][] customSettings, int step, String label) {
        activeWizardBinFolder = binFolder;
        activeWizardCfg = cfg;
        activeWizardCustomSettings = customSettings;
        activeWizardStep = step;
        activeWizardLabel = label == null ? "" : label;
        lastWizardCancelChoice = null;
        suppressNextWizardCancelMessage = false;
    }

    private void clearWizardCancelContext() {
        activeWizardBinFolder = null;
        activeWizardCfg = null;
        activeWizardCustomSettings = null;
        activeWizardStep = 1;
        activeWizardLabel = "";
        lastWizardCancelChoice = null;
        suppressNextWizardCancelMessage = false;
    }

    private void installWizardCancelHook(final PipelineDialog dialog) {
        if (dialog == null || activeWizardBinFolder == null) return;
        dialog.setCancelConfirmation(new java.util.function.Supplier<Boolean>() {
            @Override public Boolean get() {
                return Boolean.valueOf(handleCancelRequest(
                        dialog.getWindow(),
                        activeWizardBinFolder,
                        activeWizardCfg,
                        activeWizardCustomSettings,
                        activeWizardStep,
                        activeWizardLabel));
            }
        });
    }

    private boolean shouldExitAfterWizardCancel(File binFolder, BinUserConfig cfg,
                                                boolean[][] customSettings, int step, String label) {
        if (lastWizardCancelChoice == CancelConfirmationDialog.Choice.SAVE_AND_EXIT
                || lastWizardCancelChoice == CancelConfirmationDialog.Choice.DISCARD_AND_EXIT) {
            return consumeWizardCancelExitChoice();
        }
        if (!handleCancelRequest(null, binFolder, cfg, customSettings, step, label)) {
            return false;
        }
        return consumeWizardCancelExitChoice();
    }

    private boolean consumeWizardCancelExitChoice() {
        suppressNextWizardCancelMessage =
                lastWizardCancelChoice == CancelConfirmationDialog.Choice.SAVE_AND_EXIT;
        lastWizardCancelChoice = null;
        return true;
    }

    private void showWizardCancelledMessageIfNeeded() {
        if (shouldShowWizardCancelMessage()) {
            IJ.showMessage("Set Up Configuration", "Cancelled.");
        }
    }

    private boolean shouldShowWizardCancelMessage() {
        boolean show = !suppressNextWizardCancelMessage;
        suppressNextWizardCancelMessage = false;
        return show;
    }

    private boolean handleCancelRequest(File binFolder, BinUserConfig cfg,
                                        boolean[][] customSettings, int step, String label) {
        return handleCancelRequest(null, binFolder, cfg, customSettings, step, label);
    }

    private boolean handleCancelRequest(Window owner, File binFolder, BinUserConfig cfg,
                                        boolean[][] customSettings, int step, String label) {
        File settingsDir = channelConfigSettingsDir(binFolder);
        File configFile = new File(settingsDir, ChannelConfigIO.FILE_NAME);
        ChannelConfig current = ChannelConfigIO.read(settingsDir);
        CancelConfirmationDialog.Choice choice = showCancelConfirmation(
                owner,
                "Step " + step + " of 6 - " + label,
                current == null
                        ? buildProgressLines(cfg, customSettings, step)
                        : buildProgressLines(current, step),
                configFile.getAbsolutePath());
        lastWizardCancelChoice = choice;
        if (choice == CancelConfirmationDialog.Choice.SAVE_AND_EXIT) {
            return true;
        }
        if (choice == CancelConfirmationDialog.Choice.DISCARD_AND_EXIT) {
            // Keep a recoverable .corrupt-*.json copy instead of erasing the only
            // record of the user's work.
            ChannelConfigIO.backupThenDelete(settingsDir);
            return true;
        }
        return false;
    }

    private File channelConfigSettingsDir(File binFolder) {
        if (binFolder != null && FlashProjectLayout.SETTINGS_DIR.equals(binFolder.getName())) {
            return binFolder;
        }
        return FlashProjectLayout.settingsDir(binFolder);
    }

    protected static final class WizardResumeState {
        final BinUserConfig cfg;
        final boolean[][] customSettings;
        final int stepIndex;
        final String stepLabel;
        final List<String> progressLines;
        final long lastUpdatedMillis;

        WizardResumeState(BinUserConfig cfg, boolean[][] customSettings,
                          int stepIndex, String stepLabel, List<String> progressLines,
                          long lastUpdatedMillis) {
            this.cfg = cfg;
            this.customSettings = copySettings(customSettings);
            this.stepIndex = stepIndex;
            this.stepLabel = stepLabel == null ? "" : stepLabel;
            this.progressLines = progressLines == null
                    ? Collections.<String>emptyList()
                    : new ArrayList<String>(progressLines);
            this.lastUpdatedMillis = lastUpdatedMillis;
        }
    }

    WizardResumeState readWizardResumeState(File binFolder) {
        return readWizardResumeState(null, binFolder);
    }

    WizardResumeState readWizardResumeState(String directory, File binFolder) {
        ChannelConfig channelConfig = ChannelConfigIO.read(channelConfigSettingsDir(binFolder));
        if (channelConfig == null || channelConfig.channels == null || channelConfig.channels.isEmpty()
                || ChannelConfigIO.isComplete(channelConfig)) {
            // Nothing to resume: absent, empty, or already finished. Routing through
            // isComplete keeps the resume gate and the downstream gate in agreement.
            return null;
        }
        int stepIndex = readStepIndex(channelConfig);
        List<String> progressLines = buildProgressLines(channelConfig, stepIndex);
        int currentChannelCount = detectCurrentChannelCount(directory);
        int savedChannelCount = channelConfig.channels.size();
        if (currentChannelCount > 0 && savedChannelCount > 0 && currentChannelCount != savedChannelCount) {
            progressLines = new ArrayList<String>(progressLines);
            progressLines.add(0, "! Current image metadata reports " + currentChannelCount
                    + " channels; saved progress has " + savedChannelCount
                    + ". Choose Start Over to change channel count.");
        }
        return new WizardResumeState(
                binUserConfigFromChannelConfig(channelConfig),
                customSettingsFromExtras(channelConfig.extras),
                stepIndex,
                stringExtra(channelConfig.extras, EXTRA_LAST_STEP_LABEL, wizardStepLabel(stepIndex)),
                progressLines,
                channelConfig.writtenAtMillis);
    }

    protected int detectCurrentChannelCount(String directory) {
        if (directory == null || directory.trim().isEmpty()) {
            return 0;
        }
        try {
            MetadataDiagnostics.SeriesInfo seriesInfo = ChannelSetupSupport.firstSeriesInfo(directory);
            return seriesInfo == null ? 0 : seriesInfo.sizeC;
        } catch (RuntimeException e) {
            IJ.log("[FLASH] Could not inspect current channel count for resume prompt: " + e.getMessage());
            return 0;
        }
    }

    private long persistIncremental(File binFolder, BinUserConfig user, boolean[][] customSettings,
                                    int stepIndex, String stepLabel,
                                    int channelIndex, String propertyKey) {
        return persistIncrementalProperties(binFolder, user, customSettings,
                stepIndex, stepLabel, channelIndex, propertyKey);
    }

    private long persistIncrementalProperties(File binFolder, BinUserConfig user, boolean[][] customSettings,
                                              int stepIndex, String stepLabel,
                                              int channelIndex, String... propertyKeys) {
        try {
            File settingsDir = channelConfigSettingsDir(binFolder);
            ChannelConfig existing = ChannelConfigIO.read(settingsDir);
            ChannelConfig cc = mergeIntoExisting(
                    binFolder, user, customSettings, stepIndex, stepLabel,
                    -1, null, ChannelConfig.PropertyStatus.CONFIGURED);
            if (channelIndex >= 0 && channelIndex < cc.channels.size() && propertyKeys != null) {
                ChannelConfig.Channel channel = cc.channels.get(channelIndex);
                for (int i = 0; i < propertyKeys.length; i++) {
                    markPropertyCommit(channel, propertyKeys[i], ChannelConfig.PropertyStatus.CONFIGURED);
                }
            }
            preservePendingValues(cc, existing);
            ChannelConfigIO.write(settingsDir, cc);
            ChannelConfig persisted = ChannelConfigIO.read(settingsDir);
            return persisted == null ? cc.writtenAtMillis : persisted.writtenAtMillis;
        } catch (IOException e) {
            IJ.log("FLASH: incremental config write failed: " + e.getMessage());
            return 0L;
        }
    }

    private void persistCommit(File binFolder, BinUserConfig user, boolean[][] customSettings) {
        try {
            writeFinalChannelConfig(binFolder, user, customSettings);
        } catch (IOException e) {
            IJ.log("FLASH: final config write failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private String showReviewDialog(File binFolder, BinUserConfig cfg, boolean[][] customSettings) {
        if (shouldSkipReviewUi()) {
            persistCommit(binFolder, cfg, customSettings);
            return REVIEW_ACTION_SAVED;
        }
        if (SwingUtilities.isEventDispatchThread()) {
            return showReviewDialogOnEventThread(binFolder, cfg, customSettings);
        }
        final String[] result = new String[]{REVIEW_ACTION_CANCEL};
        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    result[0] = showReviewDialogOnEventThread(binFolder, cfg, customSettings);
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return REVIEW_ACTION_CANCEL;
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw reviewDialogFailure(e.getCause());
        }
        return result[0] == null ? REVIEW_ACTION_CANCEL : result[0];
    }

    private String showReviewDialogOnEventThread(File binFolder, BinUserConfig cfg,
                                                 boolean[][] customSettings) {
        final PipelineDialog dialog = setupAnalysisDialog("Review configuration");
        installWizardCancelHook(dialog);
        dialog.enableBackButton();
        dialog.setPrimaryButtonText("Save configuration");
        dialog.addAnalysisHelpHeader("Set Up Configuration", FLASH_Pipeline.IDX_CREATE_BIN);
        dialog.addHeader("Review configuration");
        addSavedStatusMessage(dialog);
        dialog.addMessage("Check the setup before it is saved.");

        ChannelConfig reviewConfig = ChannelConfigIO.fromBinUserConfig(cfg);
        ConfigReviewPanel review = new ConfigReviewPanel(
                ConfigReviewPanel.ReviewModel.from(reviewConfig, customSettings));
        review.setStepEditListener(new ConfigReviewPanel.StepEditListener() {
            @Override public void editStep(int stepIndex) {
                dialog.closeWithAction(REVIEW_EDIT_PREFIX + stepIndex);
            }
        });
        dialog.addComponent(review);
        JButton savePresetButton = dialog.addFooterButton("Save as preset");
        savePresetButton.addActionListener(e -> {
            String status = saveCurrentConfigAsPreset(dialog.getWindow(), binFolder, cfg);
            if (hasText(status)) {
                dialog.setTransientStatus(status);
            }
        });

        if (dialog.showDialog()) {
            return REVIEW_ACTION_SAVE;
        }
        if (dialog.wasBackPressed()) return REVIEW_ACTION_BACK;
        String action = dialog.getActionCommand();
        return action != null && action.startsWith(REVIEW_EDIT_PREFIX)
                ? action
                : REVIEW_ACTION_CANCEL;
    }

    private boolean shouldSkipReviewUi() {
        return headless || suppressDialogs || GraphicsEnvironment.isHeadless();
    }

    private String saveCurrentConfigAsPreset(Window owner, File binFolder, BinUserConfig cfg) {
        if (shouldSkipReviewUi()) {
            return null;
        }
        String suggestedName = suggestedPresetName(cfg);
        String name = SaveAsPresetPopover.prompt(owner, suggestedName);
        if (!hasText(name)) {
            return null;
        }
        File projectRoot = projectRootForConfigurationDir(binFolder);
        if (projectRoot == null) {
            return "Could not save preset: project folder was not found.";
        }
        BinPresetIO io = new BinPresetIO(projectRoot);
        try {
            if (presetExists(io, name) && !confirmPresetOverwrite(owner, name)) {
                return null;
            }
            io.save(binPresetFromUserConfig(name, "Saved from Set Up Configuration review.", cfg));
            return "Saved preset \"" + name + "\".";
        } catch (IOException e) {
            IJ.log("[FLASH] Could not save channel configuration preset: " + e.getMessage());
            return "Could not save preset: " + e.getMessage();
        } catch (RuntimeException e) {
            IJ.log("[FLASH] Could not save channel configuration preset: " + e.getMessage());
            return "Could not save preset: " + e.getMessage();
        }
    }

    private boolean presetExists(BinPresetIO io, String name) throws IOException {
        try {
            io.load(name);
            return true;
        } catch (java.io.FileNotFoundException e) {
            return false;
        }
    }

    protected boolean confirmPresetOverwrite(Window owner, String name) {
        if (shouldSkipReviewUi()) {
            return false;
        }
        PipelineDialog dialog = new PipelineDialog(owner, "Overwrite preset");
        dialog.addHeader("Overwrite preset");
        dialog.addMessage("A preset named \"" + escapeHtmlText(name)
                + "\" already exists. Replace it?");
        dialog.setPrimaryButtonText("Overwrite");
        return dialog.showDialog();
    }

    private static String suggestedPresetName(BinUserConfig cfg) {
        if (cfg == null || cfg.names == null || cfg.names.isEmpty()) {
            return "Channel configuration";
        }
        List<String> tokens = new ArrayList<String>();
        for (int i = 0; i < cfg.names.size() && tokens.size() < 3; i++) {
            String name = cfg.names.get(i);
            if (hasText(name)) {
                tokens.add(name.trim());
            }
        }
        return tokens.isEmpty()
                ? "Channel configuration"
                : joinStrings(tokens, " + ");
    }

    public static BinPreset binPresetFromUserConfig(String name, String description, BinUserConfig cfg) {
        return new BinPreset(
                name,
                description,
                BinPreset.CURRENT_LIBRARY_VERSION,
                toPresetBinConfig(cfg),
                cfg == null ? Collections.<String>emptyList() : cfg.markerIds,
                cfg == null ? Collections.<String>emptyList() : cfg.markerShapes,
                cfg == null ? Collections.<Boolean>emptyList() : cfg.markerCrowdingSensitive);
    }

    private static RuntimeException reviewDialogFailure(Throwable cause) {
        if (cause instanceof RuntimeException) {
            return (RuntimeException) cause;
        }
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        return new IllegalStateException("Could not show configuration review.", cause);
    }

    private static int parseReviewEditStep(String action) {
        if (action == null || !action.startsWith(REVIEW_EDIT_PREFIX)) return -1;
        try {
            return Integer.parseInt(action.substring(REVIEW_EDIT_PREFIX.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void rememberSavedAt(long millis) {
        if (millis > 0) {
            lastWizardSavedAtMillis = millis;
        }
    }

    private long readSavedAtMillis(File binFolder) {
        ChannelConfig persisted = ChannelConfigIO.read(channelConfigSettingsDir(binFolder));
        return persisted == null ? 0L : persisted.writtenAtMillis;
    }

    static String savedStatusText(long millis) {
        return millis <= 0 ? "" : SAVED_STATUS_PREFIX + formatSavedTime(millis);
    }

    static String formatSavedTime(long millis) {
        return new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(millis));
    }

    private ChannelConfig mergeIntoExisting(File binFolder, BinUserConfig user, boolean[][] customSettings,
                                            int stepIndex, String stepLabel,
                                            int channelIndex, String propertyKey,
                                            ChannelConfig.PropertyStatus status) {
        File settingsDir = channelConfigSettingsDir(binFolder);
        ChannelConfig existing = ChannelConfigIO.read(settingsDir);
        ChannelConfig next = ChannelConfigIO.fromBinUserConfig(user);
        next.writerId = "FLASH";
        next.writtenAtMillis = System.currentTimeMillis();
        if (existing != null) {
            next.extras.putAll(existing.extras);
            next.clickCaptureUsed = existing.clickCaptureUsed;
        }
        if (new File(settingsDir, "Clicks.json").isFile()) {
            next.clickCaptureUsed = true;
        }
        next.extras.put(EXTRA_LAST_STEP_INDEX, Integer.valueOf(stepIndex));
        next.extras.put(EXTRA_LAST_STEP_LABEL, stepLabel == null ? "" : stepLabel);
        if (customSettings != null) {
            next.extras.put(EXTRA_CUSTOM_SETTINGS, customSettingsToJson(customSettings));
        }

        boolean sameChannelCount = existing != null
                && existing.channels != null
                && existing.channels.size() == next.channels.size();
        for (int i = 0; i < next.channels.size(); i++) {
            ChannelConfig.Channel channel = next.channels.get(i);
            ChannelConfig.Channel old = sameChannelCount ? channelAt(existing, i) : null;
            channel.status.clear();
            if (old != null) {
                channel.extras.putAll(old.extras);
                channel.status.putAll(old.status);
            }
            seedMissingStatuses(channel);
        }

        if (stepIndex >= 1) {
            for (ChannelConfig.Channel channel : next.channels) {
                setStatus(channel, ChannelConfig.P_NAME, ChannelConfig.PropertyStatus.CONFIGURED);
                setStatus(channel, ChannelConfig.P_COLOR, ChannelConfig.PropertyStatus.CONFIGURED);
                setStatus(channel, ChannelConfig.P_MARKER, ChannelConfig.PropertyStatus.CONFIGURED);
            }
        }
        if (channelIndex >= 0 && channelIndex < next.channels.size() && propertyKey != null) {
            markPropertyCommit(next.channels.get(channelIndex), propertyKey, status);
        }
        return next;
    }

    private static void markPropertyCommit(ChannelConfig.Channel channel, String propertyKey,
                                           ChannelConfig.PropertyStatus status) {
        setStatus(channel, propertyKey, status);
    }

    private static void markAllKnown(ChannelConfig.Channel channel, ChannelConfig.PropertyStatus status) {
        for (String key : CHANNEL_CONFIG_PROPERTIES) {
            setStatus(channel, key, status);
        }
    }

    private static void seedMissingStatuses(ChannelConfig.Channel channel) {
        for (String key : CHANNEL_CONFIG_PROPERTIES) {
            if (!channel.status.containsKey(key)) {
                channel.status.put(key, ChannelConfig.PropertyStatus.PENDING);
            }
        }
    }

    private static void setStatus(ChannelConfig.Channel channel, String propertyKey,
                                  ChannelConfig.PropertyStatus status) {
        if (channel != null && propertyKey != null && status != null) {
            channel.status.put(propertyKey, status);
        }
    }

    private static ChannelConfig.Channel channelAt(ChannelConfig cfg, int index) {
        if (cfg == null || cfg.channels == null || index < 0 || index >= cfg.channels.size()) {
            return null;
        }
        return cfg.channels.get(index);
    }

    private static BinUserConfig binUserConfigFromChannelConfig(ChannelConfig cfg) {
        List<String> names = new ArrayList<String>();
        List<String> colors = new ArrayList<String>();
        List<String> thresholds = new ArrayList<String>();
        List<String> sizes = new ArrayList<String>();
        List<String> minmax = new ArrayList<String>();
        List<String> filters = new ArrayList<String>();
        List<String> intensity = new ArrayList<String>();
        List<String> segmentation = new ArrayList<String>();
        List<String> markerIds = new ArrayList<String>();
        List<String> markerShapes = new ArrayList<String>();
        List<Boolean> crowding = new ArrayList<Boolean>();
        for (int i = 0; cfg != null && cfg.channels != null && i < cfg.channels.size(); i++) {
            ChannelConfig.Channel ch = cfg.channels.get(i);
            names.add(value(ch == null ? null : ch.name, "Channel" + (i + 1)));
            colors.add(value(ch == null ? null : ch.color, "Grays"));
            thresholds.add(value(ch == null ? null : ch.threshold, "default"));
            sizes.add(value(ch == null ? null : ch.size, "100-Infinity"));
            minmax.add(value(ch == null ? null : ch.minmax, "None"));
            filters.add(value(ch == null ? null : ch.filterPreset, "Default"));
            intensity.add(value(ch == null ? null : ch.intensityThreshold, "default"));
            segmentation.add(value(ch == null ? null : ch.segmentationMethod, "classical"));
            markerIds.add(ch == null ? "" : value(ch.markerId, ""));
            markerShapes.add(ch == null ? "" : value(ch.markerShape, ""));
            crowding.add(Boolean.valueOf(ch != null && ch.markerCrowdingSensitive));
        }
        BinUserConfig out = new BinUserConfig(names, colors, thresholds, sizes, minmax, filters, intensity);
        out.segmentationMethods.clear();
        out.segmentationMethods.addAll(segmentation);
        out.markerIds.clear();
        out.markerIds.addAll(markerIds);
        out.markerShapes.clear();
        out.markerShapes.addAll(markerShapes);
        out.markerCrowdingSensitive.clear();
        out.markerCrowdingSensitive.addAll(crowding);
        out.zSliceMode = cfg == null || cfg.zSliceMode == null ? ZSliceMode.FULL : cfg.zSliceMode;
        if (cfg != null && cfg.zSliceSelections != null) {
            for (Map.Entry<String, ZSliceRange> entry : cfg.zSliceSelections.entrySet()) {
                Integer seriesIndex = parseInteger(entry.getKey());
                ZSliceRange range = entry.getValue();
                if (seriesIndex != null && range != null) {
                    out.zSliceSelections.put(seriesIndex,
                            new ZSliceSelection(seriesIndex.intValue(), "", range.endSlice, range));
                }
            }
        }
        return out;
    }

    private static int readStepIndex(ChannelConfig cfg) {
        return Math.max(1, Math.min(6, intExtra(cfg == null ? null : cfg.extras, EXTRA_LAST_STEP_INDEX, 1)));
    }

    private static List<Object> customSettingsToJson(boolean[][] settings) {
        List<Object> rows = new ArrayList<Object>();
        for (int r = 0; settings != null && r < settings.length; r++) {
            List<Object> row = new ArrayList<Object>();
            for (int c = 0; settings[r] != null && c < settings[r].length; c++) {
                row.add(Boolean.valueOf(settings[r][c]));
            }
            rows.add(row);
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private static boolean[][] customSettingsFromExtras(Map<String, Object> extras) {
        Object raw = extras == null ? null : extras.get(EXTRA_CUSTOM_SETTINGS);
        if (!(raw instanceof List)) return null;
        List<Object> rows = (List<Object>) raw;
        boolean[][] out = new boolean[rows.size()][];
        for (int r = 0; r < rows.size(); r++) {
            Object rowRaw = rows.get(r);
            if (!(rowRaw instanceof List)) {
                out[r] = new boolean[0];
                continue;
            }
            List<Object> row = (List<Object>) rowRaw;
            out[r] = new boolean[row.size()];
            for (int c = 0; c < row.size(); c++) {
                Object value = row.get(c);
                out[r][c] = value instanceof Boolean
                        ? ((Boolean) value).booleanValue()
                        : Boolean.parseBoolean(String.valueOf(value));
            }
        }
        return out;
    }

    private static int intExtra(Map<String, Object> extras, String key, int fallback) {
        Object value = extras == null ? null : extras.get(key);
        if (value instanceof Number) return ((Number) value).intValue();
        if (value == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String stringExtra(Map<String, Object> extras, String key, String fallback) {
        Object value = extras == null ? null : extras.get(key);
        String text = value == null ? null : String.valueOf(value);
        return hasText(text) ? text : fallback;
    }

    private static Integer parseInteger(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String value(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static boolean[][] copySettings(boolean[][] source) {
        if (source == null) return null;
        boolean[][] copy = new boolean[source.length][];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i] == null ? null : source[i].clone();
        }
        return copy;
    }

    private List<String> buildProgressLines(BinUserConfig cfg, boolean[][] customSettings, int step) {
        List<String> lines = new ArrayList<String>();
        lines.add(progressLine(step, 1, "Channel names and markers", cfg == null ? 0 : cfg.names.size(), 0));
        lines.add(progressLine(step, 2, "Analysis scope", 0, 0));
        lines.add(progressLine(step, 3, "Settings mode", countSelected(customSettings), totalSettings(customSettings)));
        int zDone = cfg == null ? 0 : cfg.zSliceSelections.size();
        lines.add(progressLine(step, 4, "Z-slice ranges", zDone, 0));
        lines.add(progressLine(step, 5, "Quality check", countFilledQcValues(cfg), Math.max(0, cfg == null ? 0 : cfg.names.size())));
        lines.add(progressLine(step, 6, "Review configuration", 0, 0));
        return lines;
    }

    private static List<String> buildProgressLines(ChannelConfig cfg, int step) {
        List<String> lines = new ArrayList<String>();
        int channels = cfg == null || cfg.channels == null ? 0 : cfg.channels.size();
        lines.add(progressLine(step, 1, "Channel names and markers",
                countConfigured(cfg, ChannelConfig.P_NAME)
                        + countConfigured(cfg, ChannelConfig.P_COLOR)
                        + countConfigured(cfg, ChannelConfig.P_MARKER),
                channels * 3));
        lines.add(progressLine(step, 2, "Analysis scope", 0, 0));
        lines.add(progressLine(step, 3, "Settings mode", 0, 0));
        int zDone = cfg == null || cfg.zSliceSelections == null ? 0 : cfg.zSliceSelections.size();
        lines.add(progressLine(step, 4, "Z-slice ranges", zDone, 0));
        int qcDone = countConfigured(cfg, ChannelConfig.P_FILTER)
                + countConfigured(cfg, ChannelConfig.P_MINMAX)
                + countConfigured(cfg, ChannelConfig.P_THRESHOLD)
                + countConfigured(cfg, ChannelConfig.P_INTENSITY)
                + countConfigured(cfg, ChannelConfig.P_SIZE)
                + countConfigured(cfg, ChannelConfig.P_SEGMENTATION);
        lines.add(progressLine(step, 5, "Quality check", qcDone, channels * 6));
        lines.add(progressLine(step, 6, "Review configuration", 0, 0));
        return lines;
    }

    private static int countConfigured(ChannelConfig cfg, String propertyKey) {
        if (cfg == null || cfg.channels == null) return 0;
        int count = 0;
        for (ChannelConfig.Channel channel : cfg.channels) {
            ChannelConfig.PropertyStatus status = channel == null
                    ? ChannelConfig.PropertyStatus.PENDING
                    : channel.statusOf(propertyKey);
            if (status == ChannelConfig.PropertyStatus.CONFIGURED
                    || status == ChannelConfig.PropertyStatus.COMMITTED) {
                count++;
            }
        }
        return count;
    }

    private static String progressLine(int currentStep, int lineStep, String label, int done, int total) {
        String status = currentStep > lineStep ? "Done" : (currentStep == lineStep ? "Current" : "Pending");
        String detail = total > 0 ? " (" + done + " of " + total + ")" : (done > 0 ? " (" + done + ")" : "");
        return status + " - " + label + detail + " (Step " + lineStep + ")";
    }

    private static int countSelected(boolean[][] customSettings) {
        if (customSettings == null) return 0;
        int count = 0;
        for (int r = 0; r < customSettings.length; r++) {
            if (customSettings[r] == null) continue;
            for (int c = 0; c < customSettings[r].length; c++) {
                if (customSettings[r][c]) count++;
            }
        }
        return count;
    }

    private static int totalSettings(boolean[][] customSettings) {
        if (customSettings == null) return 0;
        int count = 0;
        for (int r = 0; r < customSettings.length; r++) {
            count += customSettings[r] == null ? 0 : customSettings[r].length;
        }
        return count;
    }

    private static int countFilledQcValues(BinUserConfig cfg) {
        if (cfg == null) return 0;
        int count = 0;
        count += presentValues(cfg.minmax, "None");
        count += presentValues(cfg.objectThresholds, "default");
        count += presentValues(cfg.intensityThresholds, "default");
        count += presentValues(cfg.sizes, "100-Infinity");
        return count;
    }

    private static int presentValues(List<String> values, String defaultValue) {
        if (values == null) return 0;
        int count = 0;
        for (int i = 0; i < values.size(); i++) {
            String value = values.get(i);
            if (hasText(value) && !value.equals(defaultValue)) count++;
        }
        return count;
    }

    private static String wizardStepLabel(int step) {
        switch (step) {
            case 1: return "Channel Identity";
            case 2: return "Analysis Scope";
            case 3: return "Settings Mode";
            case 4: return "Z-slice QC";
            case 5: return "Quality Check";
            case 6: return "Review Configuration";
            default: return "Set Up Configuration";
        }
    }

    private void handleSelectiveOverride(String directory, File binFolder, BinConfig existing,
                                         boolean doMinMax, boolean doThresholds,
                                         boolean doParticleSize, boolean doSegmentationMethod,
                                         boolean doFilterParameters,
                                         boolean doZSliceSelection) throws IOException {
        if (existing == null) {
            IJ.error("Set Up Configuration", "Cannot read existing config. Use 'Override ALL' instead.");
            return;
        }
        lastWizardSavedAtMillis = readSavedAtMillis(binFolder);

        int n = existing.numChannels();
        List<String> names = new ArrayList<>(existing.channelNames);
        List<String> colors = new ArrayList<>(existing.channelColors);
        List<String> objThresholds = new ArrayList<>(existing.channelThresholds);
        List<String> sizes = new ArrayList<>(existing.channelSizes);
        List<String> minmax = new ArrayList<>(existing.channelMinMax);
        List<String> intThresholds = new ArrayList<>(existing.channelIntensityThresholds);
        List<String> filterPresets = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            filterPresets.add(i < existing.channelFilterPresets.size()
                    ? existing.channelFilterPresets.get(i)
                    : "Default");
        }

        BinUserConfig cfg = new BinUserConfig(names, colors, objThresholds, sizes, minmax, filterPresets, intThresholds);
        // Carry forward existing segmentation methods
        for (int i = 0; i < n; i++) {
            if (i < existing.segmentationMethods.size()) {
                cfg.segmentationMethods.set(i, existing.segmentationMethods.get(i));
            }
        }
        cfg.zSliceMode = existing.zSliceMode == null ? ZSliceMode.FULL : existing.zSliceMode;
        cfg.zSliceSelections.putAll(existing.zSliceSelections);
        boolean needsQC = doFilterParameters || doMinMax || doThresholds
                || doParticleSize || doSegmentationMethod;

        int step = doZSliceSelection ? 2 : (needsQC ? 3 : 4);
        boolean[][] customSettings = null;

        while (step >= 2 && step <= 4) {
            switch (step) {
                case 2: { // Analysis scope / z-slice enablement
                    Boolean scopeAccepted = showAnalysisScopeDialog(cfg, false);
                    if (scopeAccepted == null) {
                        return;
                    }
                    if (!needsQC && cfg.usesZSliceSubset()) {
                        String zSliceResult = interactiveZSliceSubsetQC(directory, cfg);
                        if ("back".equals(zSliceResult)) {
                            return;
                        }
                        if ("cancel".equals(zSliceResult)) return;
                    }
                    step = (needsQC || needsQcImages(customSettings, cfg)) ? 3 : 4;
                    break;
                }
                case 3: { // Granular fork + QC
                    if (needsQC) {
                        customSettings = settingsMatrixForChannelCount(customSettings, cfg.names.size());
                        BinConfig previousStatusReference = settingsStatusReference;
                        settingsStatusReference = existing;
                        boolean[][] selectedSettings;
                        try {
                            selectedSettings = showGranularCustomFork(cfg,
                                    doFilterParameters, doMinMax,
                                    doThresholds,
                                    doParticleSize,
                                    doSegmentationMethod,
                                    customSettings);
                        } finally {
                            settingsStatusReference = previousStatusReference;
                        }
                        if (selectedSettings == null) {
                            if (lastWasBack) {
                                if (doZSliceSelection) { step = 2; break; }
                            }
                            return;
                        }
                        customSettings = selectedSettings;
                    } else {
                        customSettings = emptyCustomSettings(cfg.names.size());
                    }

                    if (doZSliceSelection && cfg.usesZSliceSubset()) {
                        String zSliceResult = interactiveZSliceSubsetQC(directory, cfg);
                        if ("back".equals(zSliceResult)) {
                            if (doZSliceSelection) { step = 2; break; }
                            continue;
                        }
                        if ("cancel".equals(zSliceResult)) return;
                    }

                    boolean[][] qcSettings = qcSelectionSettings(customSettings, cfg);
                    if (anyTrue(qcSettings)) {
                        QcImageOpenResult qcOpenResult =
                                openImagesForQC(directory, binFolder, cfg,
                                        qcSettings);
                        showQcOpenMessageIfPresent(qcOpenResult);
                        if (qcOpenResult.isCancel()) return;
                        if (qcOpenResult.isReady()) {
                            String qcResult = anyTrue(qcSettings)
                                    ? interactiveQC(qcOpenResult.images, cfg, binFolder, qcSettings)
                                    : "done";
                            cleanupImages(qcOpenResult.images);
                            if ("back".equals(qcResult)) continue; // re-show granular fork
                            if ("cancel".equals(qcResult)) return;
                        }
                    }

                    // Reset defaults for non-custom channels
                    for (int i = 0; i < n; i++) {
                        if (doMinMax && !customSettings[SETTINGS_MIN_MAX][i]) cfg.minmax.set(i, "None");
                        boolean roiIntensitySelected = customSettings[SETTINGS_ROI_INTENSITY_THRESHOLD][i];
                        boolean objectThresholdSelected = customSettings[SETTINGS_OBJECT_THRESHOLD][i];
                        if (doThresholds && !roiIntensitySelected && !objectThresholdSelected) {
                            cfg.intensityThresholds.set(i, "default");
                            cfg.objectThresholds.set(i, "default");
                        }
                        if (doParticleSize && !customSettings[SETTINGS_OBJECT_SIZE_FILTER][i]) cfg.sizes.set(i, "100-Infinity");
                    }
                    step = 4;
                    break;
                }
                case 4: {
                    writeBinConfigFiles(binFolder, cfg);
                    IJ.showMessage("Set Up Configuration", "Settings updated in:\n" + binFolder.getAbsolutePath());
                    return;
                }
            }
        }
    }

    // ── Granular Default vs Custom fork (with Back) ─────────────────────

    /**
     * Shows a per-step, per-channel settings dialog.
     * Object-analysis channels are selected once, then the segmentation
     * method chosen during QC determines which method-specific stage appears.
     * @return boolean[6][nChannels]: [0]=filter params, [1]=minMax,
     *         [2]=channel thresholds (drives the unified threshold QC),
     *         [3]=channel thresholds for classical channels (mirrors [2]) /
     *             AI params for stardist/cellpose,
     *         [4]=object size filter (classical) / AI params (stardist/cellpose),
     *         [5]=segmentation method.
     *         null if cancelled or Back pressed (check lastWasBack).
     */
    private boolean[][] showGranularCustomFork(List<String> channelNames, List<String> segmentationMethods,
                                               boolean showFilterParameters, boolean showMinMax,
                                               boolean showThreshold, boolean showParticleSize) {
        return showGranularCustomFork(channelNames, segmentationMethods,
                showFilterParameters, showMinMax, showThreshold, showParticleSize, false, null,
                settingsStatusReference, false);
    }

    private boolean[][] showGranularCustomFork(BinUserConfig cfg,
                                               boolean showFilterParameters, boolean showMinMax,
                                               boolean showThreshold, boolean showParticleSize,
                                               boolean[][] initialSettings) {
        return showGranularCustomFork(cfg, showFilterParameters, showMinMax, showThreshold,
                showParticleSize, false, initialSettings);
    }

    protected boolean[][] showGranularCustomFork(BinUserConfig cfg,
                                                 boolean showFilterParameters, boolean showMinMax,
                                                 boolean showThreshold, boolean showParticleSize,
                                                 boolean showSegmentationMethod,
                                                 boolean[][] initialSettings) {
        BinUserConfig safe = cfg == null
                ? new BinUserConfig(new ArrayList<String>(), new ArrayList<String>(),
                new ArrayList<String>(), new ArrayList<String>(), new ArrayList<String>(),
                new ArrayList<String>(), new ArrayList<String>())
                : cfg;
        return showGranularCustomFork(safe.names, safe.segmentationMethods,
                showFilterParameters, showMinMax, showThreshold, showParticleSize,
                showSegmentationMethod,
                initialSettings, settingsStatusReference, safe.usesZSliceSubset());
    }

    private boolean[][] showGranularCustomFork(List<String> channelNames, List<String> segmentationMethods,
                                               boolean showFilterParameters, boolean showMinMax,
                                               boolean showThreshold, boolean showParticleSize,
                                               boolean[][] initialSettings) {
        return showGranularCustomFork(channelNames, segmentationMethods,
                showFilterParameters, showMinMax, showThreshold, showParticleSize, false,
                initialSettings, settingsStatusReference, false);
    }

    private boolean[][] showGranularCustomFork(List<String> channelNames, List<String> segmentationMethods,
                                               boolean showFilterParameters, boolean showMinMax,
                                               boolean showThreshold, boolean showParticleSize,
                                               boolean showSegmentationMethod,
                                               boolean[][] initialSettings,
                                               BinConfig statusConfig,
                                               boolean usesZSliceSubset) {
        PipelineDialog fork = new PipelineDialog("Settings Mode");
        installWizardCancelHook(fork);
        fork.enableBackButton();
        fork.addSetupHelpHeader("Settings Mode", SetupHelpCatalog.SETTINGS_MODE);
        addSavedStatusMessage(fork);
        fork.addMessage("Toggle ON the settings you want to adjust interactively per channel.");

        int n = channelNames == null ? 0 : channelNames.size();
        boolean[] isStarDist = new boolean[n];
        boolean[] isCellpose = new boolean[n];
        for (int i = 0; i < n; i++) {
            String method = segmentationMethods != null && i < segmentationMethods.size()
                    ? safe(segmentationMethods.get(i))
                    : "classical";
            isStarDist[i] = method.startsWith("stardist");
            isCellpose[i] = method.startsWith("cellpose");
        }
        int[] allChannels = channelIndexes(n);
        int[] classicalChannels = matchingChannelIndexes(isStarDist, isCellpose, false, false);

        SettingsDataStatus filterStatus = settingsDataStatusForFields(statusConfig,
                allChannels, BinField.FILTER_PRESETS);
        SettingsDataStatus minMaxStatus = settingsDataStatusForFields(statusConfig,
                allChannels, BinField.DISPLAY_MIN_MAX);
        SettingsDataStatus thresholdStatus = settingsDataStatusForFields(statusConfig,
                allChannels, BinField.OBJECT_THRESHOLDS, BinField.INTENSITY_THRESHOLDS);
        SettingsDataStatus objectSizeStatus = settingsDataStatusForFields(statusConfig,
                classicalChannels, BinField.PARTICLE_SIZES);
        SettingsDataStatus segmentationMethodStatus = settingsDataStatusForFields(statusConfig,
                allChannels, BinField.SEGMENTATION_METHODS);
        boolean showObjectAnalysisSelection = showSegmentationMethod || showParticleSize;
        SettingsDataStatus objectAnalysisStatus = combineDisplayedSettingsDataStatuses(
                showSegmentationMethod ? segmentationMethodStatus : null,
                showParticleSize ? objectSizeStatus : null);
        SettingsDataStatus allStatus = combineDisplayedSettingsDataStatuses(
                showFilterParameters ? filterStatus : null,
                showMinMax ? minMaxStatus : null,
                showThreshold ? thresholdStatus : null,
                showObjectAnalysisSelection ? objectAnalysisStatus : null);

        SettingsModeTickAllGroup allSettingsGroup = new SettingsModeTickAllGroup(
                fork.addHeaderToggleWithStatus("All Settings Mode Options", false,
                        settingsDataStatusLabel(allStatus, "All settings mode options")));
        fork.addHelpText("Status icons match the main screen: tick = full saved data, ! = partial saved data, blank = none saved.");

        if (showFilterParameters) {
            fork.addHeader("Channel Identity & Processing", flash.pipeline.ui.FlashIcons.section("tags"));
            SettingsModeTickAllGroup filterGroup = addSettingsModeTickAllGroup(fork,
                    "Set Filter and Parameters", filterStatus);
            fork.addHelpText("Choose a filter preset, preview it on the current Z-stack, and adjust detected key=value parameters per channel.");
            for (int i = 0; i < n; i++) {
                addSettingsModeToggle(fork, "C" + (i + 1) + " (" + channelName(channelNames, i) + ")",
                        settingSelected(initialSettings, SETTINGS_FILTER_PARAMETERS, i),
                        settingsDataStatusForFields(statusConfig, channelIndex(i), BinField.FILTER_PRESETS),
                        filterGroup, allSettingsGroup);
            }
        }
        if (showMinMax) {
            fork.addHeader("Image Display", flash.pipeline.ui.FlashIcons.section("sun"));
            SettingsModeTickAllGroup minMaxGroup = addSettingsModeTickAllGroup(fork,
                    "Display Ranges", minMaxStatus);
            fork.addHelpText("Min-Max display ranges via B&C on a max projection.");
            for (int i = 0; i < n; i++) {
                addSettingsModeToggle(fork, "C" + (i + 1) + " (" + channelName(channelNames, i) + ")",
                        settingSelected(initialSettings, SETTINGS_MIN_MAX, i),
                        settingsDataStatusForFields(statusConfig, channelIndex(i), BinField.DISPLAY_MIN_MAX),
                        minMaxGroup, allSettingsGroup);
            }
        }
        if (showThreshold) {
            fork.addHeader("ROI / Intensity Analysis", flash.pipeline.ui.FlashIcons.section("ruler"));
            SettingsModeTickAllGroup thresholdGroup = addSettingsModeTickAllGroup(fork,
                    "Channel Thresholds", thresholdStatus);
            fork.addHelpText("Set the channel threshold (after the per-channel filter). The same value feeds both classical object detection and ROI intensity measurements.");
            for (int i = 0; i < n; i++) {
                boolean selected = settingSelected(initialSettings, SETTINGS_ROI_INTENSITY_THRESHOLD, i)
                        || (!isStarDist[i] && !isCellpose[i]
                        && settingSelected(initialSettings, SETTINGS_OBJECT_THRESHOLD, i));
                addSettingsModeToggle(fork, "C" + (i + 1) + " (" + channelName(channelNames, i) + ")",
                        selected,
                        settingsDataStatusForFields(statusConfig, channelIndex(i),
                                BinField.OBJECT_THRESHOLDS, BinField.INTENSITY_THRESHOLDS),
                        thresholdGroup, allSettingsGroup);
            }
        }
        if (showObjectAnalysisSelection) {
            fork.addHeader("Object Analysis", flash.pipeline.ui.FlashIcons.section("microscope"));
            SettingsModeTickAllGroup segmentationMethodGroup = addSettingsModeTickAllGroup(fork,
                    "Segmentation Method", objectAnalysisStatus);
            fork.addHelpText("Choose which channels should open object segmentation setup. The segmentation method is chosen in that preview stage, then FLASH shows the matching Classical, StarDist, or Cellpose controls.");
            for (int i = 0; i < n; i++) {
                addSettingsModeToggle(fork, "C" + (i + 1) + " (" + channelName(channelNames, i) + ")",
                        settingSelected(initialSettings, SETTINGS_SEGMENTATION_METHOD, i)
                                || settingSelected(initialSettings, SETTINGS_OBJECT_SIZE_FILTER, i),
                        objectAnalysisChannelStatus(statusConfig, channelIndex(i),
                                showSegmentationMethod, showParticleSize),
                        segmentationMethodGroup, allSettingsGroup);
            }
        }

        // Name the screen this choice actually leads to: z-slice selection when a
        // subset was requested, else quality-check images when any setting is
        // toggled on, else straight to review. Recomputes as toggles change.
        final SettingsModeTickAllGroup nextLabelGroup = allSettingsGroup;
        Runnable refreshNextStepLabel = new Runnable() {
            @Override public void run() {
                fork.setPrimaryButtonText(NextStepLabels.afterSettingsMode(
                        usesZSliceSubset, nextLabelGroup.anySelected()));
            }
        };
        allSettingsGroup.addSelectionListener(refreshNextStepLabel);
        refreshNextStepLabel.run();

        if (!fork.showDialog()) {
            lastWasBack = fork.wasBackPressed();
            if (lastWasBack && initialSettings != null) {
                copySettings(readGranularCustomForkSelections(fork, n, showFilterParameters, showMinMax,
                        showThreshold, showParticleSize, showSegmentationMethod, isStarDist, isCellpose),
                        initialSettings);
            }
            return null;
        }
        lastWasBack = false;

        return readGranularCustomForkSelections(fork, n, showFilterParameters, showMinMax,
                showThreshold, showParticleSize, showSegmentationMethod, isStarDist, isCellpose);
    }

    private boolean[][] readGranularCustomForkSelections(PipelineDialog fork,
                                                         int n,
                                                         boolean showFilterParameters,
                                                         boolean showMinMax,
                                                         boolean showThreshold,
                                                         boolean showParticleSize,
                                                         boolean showSegmentationMethod,
                                                         boolean[] isStarDist,
                                                         boolean[] isCellpose) {
        boolean[][] result = new boolean[SETTINGS_SLOT_COUNT][n];
        boolean showObjectAnalysisSelection = showSegmentationMethod || showParticleSize;

        if (showFilterParameters) {
            for (int i = 0; i < n; i++) result[SETTINGS_FILTER_PARAMETERS][i] = fork.getNextBoolean();
        }
        if (showMinMax) {
            for (int i = 0; i < n; i++) result[SETTINGS_MIN_MAX][i] = fork.getNextBoolean();
        }
        if (showThreshold) {
            // One unified channel-threshold toggle per channel drives both threshold lists.
            // For classical channels we route the same boolean into the object-threshold slot
            // so the existing object-threshold QC step (now the unified threshold QC) runs.
            for (int i = 0; i < n; i++) {
                boolean on = fork.getNextBoolean();
                result[SETTINGS_ROI_INTENSITY_THRESHOLD][i] = on;
                if (!isStarDist[i] && !isCellpose[i]) {
                    result[SETTINGS_OBJECT_THRESHOLD][i] = on;
                }
            }
        }
        if (showObjectAnalysisSelection) {
            for (int i = 0; i < n; i++) {
                boolean on = fork.getNextBoolean();
                if (on) {
                    result[SETTINGS_SEGMENTATION_METHOD][i] = showSegmentationMethod;
                    result[SETTINGS_OBJECT_THRESHOLD][i] = true;
                    result[SETTINGS_OBJECT_SIZE_FILTER][i] = true;
                }
            }
        }
        return result;
    }

    private SettingsDataStatus objectAnalysisChannelStatus(BinConfig statusConfig, int[] channel,
                                                           boolean includeSegmentationMethod,
                                                           boolean includeParticleSize) {
        if (includeSegmentationMethod && includeParticleSize) {
            return settingsDataStatusForFields(statusConfig, channel,
                    BinField.SEGMENTATION_METHODS, BinField.PARTICLE_SIZES);
        }
        if (includeSegmentationMethod) {
            return settingsDataStatusForFields(statusConfig, channel, BinField.SEGMENTATION_METHODS);
        }
        if (includeParticleSize) {
            return settingsDataStatusForFields(statusConfig, channel, BinField.PARTICLE_SIZES);
        }
        return SettingsDataStatus.NONE;
    }

    private static boolean settingSelected(boolean[][] settings, int slot, int channelIndex) {
        return settings != null
                && slot >= 0
                && slot < settings.length
                && settings[slot] != null
                && channelIndex >= 0
                && channelIndex < settings[slot].length
                && settings[slot][channelIndex];
    }

    private static void copySettings(boolean[][] source, boolean[][] target) {
        if (source == null || target == null) return;
        for (int row = 0; row < source.length && row < target.length; row++) {
            if (source[row] == null || target[row] == null) continue;
            for (int col = 0; col < source[row].length && col < target[row].length; col++) {
                target[row][col] = source[row][col];
            }
        }
    }

    enum SettingsDataStatus {
        NONE,
        PARTIAL,
        FULL
    }

    private SettingsModeTickAllGroup addSettingsModeTickAllGroup(PipelineDialog dialog, String label) {
        return new SettingsModeTickAllGroup(dialog.addSubHeaderToggle(label, false));
    }

    private SettingsModeTickAllGroup addSettingsModeTickAllGroup(PipelineDialog dialog, String label,
                                                                 SettingsDataStatus status) {
        return new SettingsModeTickAllGroup(dialog.addSubHeaderToggleWithStatus(label, false,
                settingsDataStatusLabel(status, label)));
    }

    private ToggleSwitch addSettingsModeToggle(PipelineDialog dialog, String label, boolean selected,
                                               SettingsModeTickAllGroup... groups) {
        ToggleSwitch toggle = dialog.addToggle(label, selected);
        attachSettingsModeGroups(toggle, groups);
        return toggle;
    }

    private ToggleSwitch addSettingsModeToggle(PipelineDialog dialog, String label, boolean selected,
                                               SettingsDataStatus status,
                                               SettingsModeTickAllGroup... groups) {
        ToggleSwitch toggle = dialog.addToggleWithStatus(label, selected,
                settingsDataStatusLabel(status, label));
        attachSettingsModeGroups(toggle, groups);
        return toggle;
    }

    private static void attachSettingsModeGroups(ToggleSwitch toggle,
                                                 SettingsModeTickAllGroup... groups) {
        if (groups != null) {
            for (int i = 0; i < groups.length; i++) {
                if (groups[i] != null) {
                    groups[i].add(toggle);
                }
            }
        }
    }

    private JLabel settingsDataStatusLabel(SettingsDataStatus status, String label) {
        JLabel statusLabel = new JLabel(settingsDataStatusIcon(status));
        statusLabel.setToolTipText(settingsDataTooltip(status, label));
        return statusLabel;
    }

    private Icon settingsDataStatusIcon(SettingsDataStatus status) {
        if (status == SettingsDataStatus.FULL) return loadStatusIcon("status_done.png");
        if (status == SettingsDataStatus.PARTIAL) return loadStatusIcon("status_stale.png");
        return loadStatusIcon("status_pending.png");
    }

    private Icon loadStatusIcon(String resourceName) {
        if (GraphicsEnvironment.isHeadless()) return null;
        URL url = getClass().getResource("/icons/" + resourceName);
        return url == null ? null : new ImageIcon(url);
    }

    private static String settingsDataTooltip(SettingsDataStatus status, String label) {
        String target = hasText(label) ? label : "This step";
        if (status == SettingsDataStatus.FULL) {
            return target + ": full saved data for all relevant channels";
        }
        if (status == SettingsDataStatus.PARTIAL) {
            return target + ": partial saved data; some values are missing";
        }
        return target + ": no saved data found";
    }

    static SettingsDataStatus settingsDataStatusForFields(BinConfig cfg,
                                                          int[] channelIndexes,
                                                          BinField... fields) {
        if (fields == null || fields.length == 0) return SettingsDataStatus.NONE;
        int expected = 0;
        int present = 0;
        int[] channels = channelIndexes == null ? new int[0] : channelIndexes;
        for (int f = 0; f < fields.length; f++) {
            BinField field = fields[f];
            if (field == null) continue;
            if (field == BinField.Z_SLICE) {
                expected++;
                if (cfg != null && cfg.hasZSliceConfig()) present++;
                continue;
            }
            List<String> values = fieldValues(cfg, field);
            expected += channels.length;
            present += presentValueCount(values, channels);
        }
        return dataStatusFromCounts(present, expected);
    }

    private static SettingsDataStatus settingsDataStatusForEngine(BinConfig cfg,
                                                                  int[] channelIndexes,
                                                                  String enginePrefix) {
        int[] channels = channelIndexes == null ? new int[0] : channelIndexes;
        int expected = channels.length;
        int present = 0;
        if (cfg != null && enginePrefix != null) {
            for (int i = 0; i < channels.length; i++) {
                int channel = channels[i];
                if (channel >= 0 && channel < cfg.segmentationMethods.size()
                        && safe(cfg.segmentationMethods.get(channel)).startsWith(enginePrefix)) {
                    present++;
                }
            }
        }
        return dataStatusFromCounts(present, expected);
    }

    static SettingsDataStatus combineSettingsDataStatuses(SettingsDataStatus... statuses) {
        boolean sawStatus = false;
        boolean sawFull = false;
        boolean sawPartial = false;
        boolean sawNone = false;
        if (statuses != null) {
            for (int i = 0; i < statuses.length; i++) {
                SettingsDataStatus status = statuses[i];
                if (status == null) continue;
                sawStatus = true;
                if (status == SettingsDataStatus.FULL) sawFull = true;
                else if (status == SettingsDataStatus.PARTIAL) sawPartial = true;
                else sawNone = true;
            }
        }
        if (!sawStatus) return SettingsDataStatus.NONE;
        if (sawPartial) return SettingsDataStatus.PARTIAL;
        if (sawFull && sawNone) return SettingsDataStatus.PARTIAL;
        return sawFull ? SettingsDataStatus.FULL : SettingsDataStatus.NONE;
    }

    private static SettingsDataStatus combineDisplayedSettingsDataStatuses(SettingsDataStatus... statuses) {
        return combineSettingsDataStatuses(statuses);
    }

    private static SettingsDataStatus dataStatusFromCounts(int present, int expected) {
        if (expected <= 0 || present <= 0) return SettingsDataStatus.NONE;
        if (present >= expected) return SettingsDataStatus.FULL;
        return SettingsDataStatus.PARTIAL;
    }

    private static int presentValueCount(List<String> values, int[] channelIndexes) {
        if (values == null || channelIndexes == null) return 0;
        int present = 0;
        for (int i = 0; i < channelIndexes.length; i++) {
            int channel = channelIndexes[i];
            if (channel >= 0 && channel < values.size() && hasText(values.get(channel))) {
                present++;
            }
        }
        return present;
    }

    private static List<String> fieldValues(BinConfig cfg, BinField field) {
        if (cfg == null || field == null) return Collections.emptyList();
        switch (field) {
            case CHANNEL_NAMES:
                return cfg.channelNames;
            case CHANNEL_COLORS:
                return cfg.channelColors;
            case OBJECT_THRESHOLDS:
                return cfg.channelThresholds;
            case PARTICLE_SIZES:
                return cfg.channelSizes;
            case DISPLAY_MIN_MAX:
                return cfg.channelMinMax;
            case INTENSITY_THRESHOLDS:
                return cfg.channelIntensityThresholds;
            case SEGMENTATION_METHODS:
                return cfg.segmentationMethods;
            case FILTER_PRESETS:
                return cfg.channelFilterPresets;
            default:
                return Collections.emptyList();
        }
    }

    private static int[] channelIndexes(int count) {
        int safeCount = Math.max(0, count);
        int[] out = new int[safeCount];
        for (int i = 0; i < safeCount; i++) out[i] = i;
        return out;
    }

    private static int[] channelIndex(int index) {
        return index < 0 ? new int[0] : new int[]{index};
    }

    private static int[] matchingChannelIndexes(boolean[] isStarDist, boolean[] isCellpose,
                                                boolean wantStarDist, boolean wantCellpose) {
        int n = isStarDist == null ? 0 : isStarDist.length;
        List<Integer> indexes = new ArrayList<Integer>();
        for (int i = 0; i < n; i++) {
            boolean starDist = isStarDist[i];
            boolean cellpose = isCellpose != null && i < isCellpose.length && isCellpose[i];
            boolean match = wantStarDist ? starDist
                    : (wantCellpose ? cellpose : (!starDist && !cellpose));
            if (match) indexes.add(Integer.valueOf(i));
        }
        int[] out = new int[indexes.size()];
        for (int i = 0; i < indexes.size(); i++) out[i] = indexes.get(i).intValue();
        return out;
    }

    private static String channelName(List<String> channelNames, int channelIndex) {
        if (channelNames != null && channelIndex >= 0 && channelIndex < channelNames.size()
                && hasText(channelNames.get(channelIndex))) {
            return channelNames.get(channelIndex);
        }
        return "Channel" + (channelIndex + 1);
    }

    static final class SettingsModeTickAllGroup {
        private final ToggleSwitch tickAll;
        private final List<ToggleSwitch> toggles = new ArrayList<ToggleSwitch>();
        private boolean updating;

        SettingsModeTickAllGroup(ToggleSwitch tickAll) {
            this.tickAll = tickAll;
            if (this.tickAll != null) {
                this.tickAll.addChangeListener(new Runnable() {
                    @Override public void run() {
                        applyTickAllSelection();
                    }
                });
            }
        }

        void add(ToggleSwitch toggle) {
            if (toggle == null) return;
            toggles.add(toggle);
            toggle.addChangeListener(new Runnable() {
                @Override public void run() {
                    syncTickAllSelection();
                }
            });
            syncTickAllSelection();
        }

        boolean allSelected() {
            if (toggles.isEmpty()) return false;
            for (int i = 0; i < toggles.size(); i++) {
                if (!toggles.get(i).isSelected()) return false;
            }
            return true;
        }

        boolean anySelected() {
            for (int i = 0; i < toggles.size(); i++) {
                if (toggles.get(i).isSelected()) return true;
            }
            return false;
        }

        /** Fires {@code callback} whenever any toggle added so far changes. */
        void addSelectionListener(final Runnable callback) {
            if (callback == null) return;
            for (int i = 0; i < toggles.size(); i++) {
                toggles.get(i).addChangeListener(callback);
            }
        }

        private void applyTickAllSelection() {
            if (updating || tickAll == null) return;
            updating = true;
            try {
                boolean selected = tickAll.isSelected();
                for (int i = 0; i < toggles.size(); i++) {
                    toggles.get(i).setSelected(selected);
                }
            } finally {
                updating = false;
            }
        }

        private void syncTickAllSelection() {
            if (updating || tickAll == null) return;
            updating = true;
            try {
                tickAll.setSelected(allSelected());
            } finally {
                updating = false;
            }
        }
    }

    // ── User config collection (with internal Back navigation) ──────────

    public static class BinUserConfig {
        public final List<String> names;
        public final List<String> colors;
        public final List<String> objectThresholds;
        public final List<String> sizes;
        public final List<String> minmax;
        public final List<String> filterPresets;
        public final List<String> intensityThresholds;
        public final List<String> segmentationMethods;
        public final List<String> markerIds;
        public final List<String> markerShapes;
        public final List<Boolean> markerCrowdingSensitive;
        public ZSliceMode zSliceMode;
        public final LinkedHashMap<Integer, ZSliceSelection> zSliceSelections;

        public BinUserConfig(List<String> names, List<String> colors,
                             List<String> objectThresholds, List<String> sizes,
                             List<String> minmax, List<String> filterPresets,
                             List<String> intensityThresholds) {
            this.names = names;
            this.colors = colors;
            this.objectThresholds = objectThresholds;
            this.sizes = sizes;
            this.minmax = minmax;
            this.filterPresets = filterPresets;
            this.intensityThresholds = intensityThresholds;
            this.segmentationMethods = new ArrayList<String>();
            this.markerIds = new ArrayList<String>();
            this.markerShapes = new ArrayList<String>();
            this.markerCrowdingSensitive = new ArrayList<Boolean>();
            this.zSliceMode = ZSliceMode.FULL;
            this.zSliceSelections = new LinkedHashMap<Integer, ZSliceSelection>();
            for (int i = 0; i < names.size(); i++) {
                segmentationMethods.add("classical");
                markerIds.add("");
                markerShapes.add("");
                markerCrowdingSensitive.add(Boolean.FALSE);
            }
        }

        public boolean usesZSliceSubset() {
            return zSliceMode != null && zSliceMode.usesSubset();
        }

        public ZSliceConfig getZSliceConfig() {
            return new ZSliceConfig(zSliceMode, zSliceSelections);
        }
    }

    private static BinUserConfig fromBinConfig(BinConfig source) {
        BinConfig safe = source == null ? new BinConfig() : source;
        int n = maxListSize(safe.channelNames, safe.channelColors, safe.channelThresholds,
                safe.channelSizes, safe.channelMinMax, safe.channelIntensityThresholds,
                safe.channelFilterPresets, safe.segmentationMethods);
        List<String> names = paddedCopy(safe.channelNames, n, "Channel");
        List<String> colors = paddedCopy(safe.channelColors, n, "Grays");
        List<String> thresholds = paddedCopy(safe.channelThresholds, n, "default");
        List<String> sizes = paddedCopy(safe.channelSizes, n, "100-Infinity");
        List<String> minmax = paddedCopy(safe.channelMinMax, n, "None");
        List<String> filters = paddedCopy(safe.channelFilterPresets, n, "Default");
        List<String> intensity = paddedCopy(safe.channelIntensityThresholds, n, "default");
        BinUserConfig cfg = new BinUserConfig(names, colors, thresholds, sizes, minmax, filters, intensity);
        cfg.segmentationMethods.clear();
        cfg.segmentationMethods.addAll(paddedCopy(safe.segmentationMethods, n, "classical"));
        cfg.zSliceMode = safe.zSliceMode == null ? ZSliceMode.FULL : safe.zSliceMode;
        cfg.zSliceSelections.putAll(safe.zSliceSelections);
        return cfg;
    }

    private static BinUserConfig fromRawChannelConfig(ChannelConfig source, BinConfig fallback) {
        if (source == null || source.channels == null || source.channels.isEmpty()) {
            return fromBinConfig(fallback);
        }
        List<String> names = new ArrayList<String>();
        List<String> colors = new ArrayList<String>();
        List<String> thresholds = new ArrayList<String>();
        List<String> sizes = new ArrayList<String>();
        List<String> minmax = new ArrayList<String>();
        List<String> filters = new ArrayList<String>();
        List<String> intensity = new ArrayList<String>();
        List<String> segmentation = new ArrayList<String>();
        List<String> markerIds = new ArrayList<String>();
        List<String> markerShapes = new ArrayList<String>();
        List<Boolean> crowding = new ArrayList<Boolean>();
        for (int i = 0; i < source.channels.size(); i++) {
            ChannelConfig.Channel channel = source.channels.get(i);
            names.add(value(channel == null ? null : channel.name, "Channel" + (i + 1)));
            colors.add(value(channel == null ? null : channel.color, "Grays"));
            thresholds.add(value(channel == null ? null : channel.threshold, "default"));
            sizes.add(value(channel == null ? null : channel.size, "100-Infinity"));
            minmax.add(value(channel == null ? null : channel.minmax, "None"));
            filters.add(value(channel == null ? null : channel.filterPreset, "Default"));
            intensity.add(value(channel == null ? null : channel.intensityThreshold, "default"));
            segmentation.add(value(channel == null ? null : channel.segmentationMethod, "classical"));
            markerIds.add(value(channel == null ? null : channel.markerId, ""));
            markerShapes.add(value(channel == null ? null : channel.markerShape, ""));
            crowding.add(Boolean.valueOf(channel != null && channel.markerCrowdingSensitive));
        }
        BinUserConfig cfg = new BinUserConfig(names, colors, thresholds, sizes, minmax, filters, intensity);
        cfg.segmentationMethods.clear();
        cfg.segmentationMethods.addAll(segmentation);
        cfg.markerIds.clear();
        cfg.markerIds.addAll(markerIds);
        cfg.markerShapes.clear();
        cfg.markerShapes.addAll(markerShapes);
        cfg.markerCrowdingSensitive.clear();
        cfg.markerCrowdingSensitive.addAll(crowding);
        cfg.zSliceMode = source.zSliceMode == null ? ZSliceMode.FULL : source.zSliceMode;
        if (source.zSliceSelections != null) {
            for (Map.Entry<String, ZSliceRange> entry : source.zSliceSelections.entrySet()) {
                Integer seriesIndex = parseInteger(entry.getKey());
                ZSliceRange range = entry.getValue();
                if (seriesIndex != null && range != null) {
                    cfg.zSliceSelections.put(seriesIndex,
                            new ZSliceSelection(seriesIndex.intValue(), "", range.endSlice, range));
                }
            }
        }
        return cfg;
    }

    private static void ensureConfigHasChannels(BinUserConfig cfg) {
        if (cfg == null || !cfg.names.isEmpty()) return;
        cfg.names.add("Channel1");
        cfg.colors.add("Grays");
        cfg.objectThresholds.add("default");
        cfg.sizes.add("100-Infinity");
        cfg.minmax.add("None");
        cfg.filterPresets.add("Default");
        cfg.intensityThresholds.add("default");
        cfg.segmentationMethods.add("classical");
        cfg.markerIds.add("");
        cfg.markerShapes.add("");
        cfg.markerCrowdingSensitive.add(Boolean.FALSE);
    }

    @SafeVarargs
    private static int maxListSize(List<String>... lists) {
        int max = 0;
        if (lists == null) return max;
        for (List<String> list : lists) {
            if (list != null && list.size() > max) max = list.size();
        }
        return max;
    }

    private static List<String> paddedCopy(List<String> source, int size, String fallback) {
        List<String> copy = new ArrayList<String>();
        for (int i = 0; i < size; i++) {
            if (source != null && i < source.size() && source.get(i) != null && !source.get(i).trim().isEmpty()) {
                copy.add(source.get(i));
            } else if ("Channel".equals(fallback)) {
                copy.add("Channel" + (i + 1));
            } else {
                copy.add(fallback);
            }
        }
        return copy;
    }

    private static final class QcImageSelection {
        final int seriesIndex;
        final String seriesName;
        final ImagePlus image;
        final String reviewLabel;
        final String warning;
        final Map<Integer, Integer> minMaxOrderByChannelNumber;
        final Map<Integer, String> minMaxReviewLabelByChannelNumber;

        QcImageSelection(int seriesIndex, String seriesName, ImagePlus image) {
            this(seriesIndex, seriesName, image, "", "");
        }

        QcImageSelection(int seriesIndex, String seriesName, ImagePlus image,
                         String reviewLabel, String warning) {
            this(seriesIndex, seriesName, image, reviewLabel, warning, null);
        }

        QcImageSelection(int seriesIndex, String seriesName, ImagePlus image,
                         String reviewLabel, String warning,
                         Map<Integer, Integer> minMaxOrderByChannelNumber) {
            this(seriesIndex, seriesName, image, reviewLabel, warning,
                    minMaxOrderByChannelNumber, null);
        }

        QcImageSelection(int seriesIndex, String seriesName, ImagePlus image,
                         String reviewLabel, String warning,
                         Map<Integer, Integer> minMaxOrderByChannelNumber,
                         Map<Integer, String> minMaxReviewLabelByChannelNumber) {
            this.seriesIndex = seriesIndex;
            this.seriesName = seriesName == null ? "" : seriesName;
            this.image = image;
            this.reviewLabel = reviewLabel == null ? "" : reviewLabel.trim();
            this.warning = warning == null ? "" : warning.trim();
            this.minMaxOrderByChannelNumber = minMaxOrderByChannelNumber == null
                    ? new LinkedHashMap<Integer, Integer>()
                    : new LinkedHashMap<Integer, Integer>(minMaxOrderByChannelNumber);
            this.minMaxReviewLabelByChannelNumber = minMaxReviewLabelByChannelNumber == null
                    ? new LinkedHashMap<Integer, String>()
                    : new LinkedHashMap<Integer, String>(minMaxReviewLabelByChannelNumber);
        }

        int minMaxOrderForChannel(int channelNumber) {
            Integer order = minMaxOrderByChannelNumber.get(Integer.valueOf(channelNumber));
            return order == null ? Integer.MAX_VALUE : order.intValue();
        }

        boolean hasMinMaxOrderForChannel(int channelNumber) {
            return minMaxOrderByChannelNumber.containsKey(Integer.valueOf(channelNumber));
        }

        String reviewLabelForChannel(int channelNumber) {
            String channelLabel = minMaxReviewLabelByChannelNumber.get(Integer.valueOf(channelNumber));
            return channelLabel == null || channelLabel.trim().isEmpty()
                    ? reviewLabel
                    : channelLabel.trim();
        }
    }

    enum QcOpenStatus { READY, CANCEL, SKIP }

    static final class QcSourceReference {
        final File sourceFile;
        final String sourceLabel;

        private QcSourceReference(File sourceFile, String sourceLabel) {
            this.sourceFile = sourceFile;
            this.sourceLabel = hasText(sourceLabel)
                    ? sourceLabel.trim()
                    : (sourceFile == null ? "" : sourceFile.getName());
        }

        static QcSourceReference of(File sourceFile, String sourceLabel) {
            return new QcSourceReference(sourceFile, sourceLabel);
        }
    }

    static final class QcOpenPreparation {
        final QcOpenStatus status;
        final File lifFile;
        final File sourceFile;
        final String sourceLabel;
        final List<Integer> selectedSeriesIndexes;
        final String message;

        private QcOpenPreparation(QcOpenStatus status, File lifFile,
                                  String sourceLabel,
                                  List<Integer> selectedSeriesIndexes, String message) {
            this.status = status;
            this.lifFile = lifFile;
            this.sourceFile = lifFile;
            this.sourceLabel = hasText(sourceLabel)
                    ? sourceLabel.trim()
                    : (lifFile == null ? "" : lifFile.getName());
            this.selectedSeriesIndexes = selectedSeriesIndexes == null
                    ? new ArrayList<Integer>()
                    : new ArrayList<Integer>(selectedSeriesIndexes);
            this.message = message == null ? "" : message;
        }

        static QcOpenPreparation ready(File lifFile, List<Integer> selectedSeriesIndexes) {
            return ready(lifFile, selectedSeriesIndexes,
                    lifFile == null ? "" : lifFile.getName());
        }

        static QcOpenPreparation ready(File sourceFile, List<Integer> selectedSeriesIndexes,
                                       String sourceLabel) {
            return new QcOpenPreparation(QcOpenStatus.READY, sourceFile, sourceLabel,
                    selectedSeriesIndexes, "");
        }

        static QcOpenPreparation cancel(String message) {
            return new QcOpenPreparation(QcOpenStatus.CANCEL, null, "", null, message);
        }

        static QcOpenPreparation skip(String message) {
            return new QcOpenPreparation(QcOpenStatus.SKIP, null, "", null, message);
        }

        boolean isReady() {
            return status == QcOpenStatus.READY;
        }
    }

    static final class QcImageOpenResult {
        final QcOpenStatus status;
        final List<QcImageSelection> images;
        final String message;

        private QcImageOpenResult(QcOpenStatus status, List<QcImageSelection> images, String message) {
            this.status = status;
            this.images = images == null ? new ArrayList<QcImageSelection>() : images;
            this.message = message == null ? "" : message;
        }

        static QcImageOpenResult ready(List<QcImageSelection> images, String message) {
            return new QcImageOpenResult(QcOpenStatus.READY, images, message);
        }

        static QcImageOpenResult cancel(String message) {
            return new QcImageOpenResult(QcOpenStatus.CANCEL, null, message);
        }

        static QcImageOpenResult skip(String message) {
            return new QcImageOpenResult(QcOpenStatus.SKIP, null, message);
        }

        static QcImageOpenResult fromPreparation(QcOpenPreparation preparation, String fallbackMessage) {
            String message = hasText(preparation == null ? null : preparation.message)
                    ? preparation.message
                    : fallbackMessage;
            if (preparation == null) {
                return cancel(message);
            }
            if (preparation.status == QcOpenStatus.SKIP) {
                return skip(message);
            }
            if (preparation.status == QcOpenStatus.CANCEL) {
                return cancel(message);
            }
            return ready(new ArrayList<QcImageSelection>(), message);
        }

        boolean isReady() {
            return status == QcOpenStatus.READY;
        }

        boolean isCancel() {
            return status == QcOpenStatus.CANCEL;
        }

        boolean isSkip() {
            return status == QcOpenStatus.SKIP;
        }
    }

    private static final class ThresholdConfirmationResult {
        final String token;
        final boolean back;
        final boolean canceled;
        final boolean skipCurrentImage;

        ThresholdConfirmationResult(String token, boolean back, boolean canceled) {
            this(token, back, canceled, false);
        }

        ThresholdConfirmationResult(String token, boolean back, boolean canceled, boolean skipCurrentImage) {
            this.token = token;
            this.back = back;
            this.canceled = canceled;
            this.skipCurrentImage = skipCurrentImage;
        }
    }

    private static final class FilterFieldBinding {
        final FilterMacroEditorModel.Parameter parameter;
        final java.awt.TextField field;

        FilterFieldBinding(FilterMacroEditorModel.Parameter parameter, java.awt.TextField field) {
            this.parameter = parameter;
            this.field = field;
        }
    }

    protected BinUserConfig collectBinConfigFromUser(String directory, File binFolder, BinConfig existing) {
        return collectBinConfigFromUser(directory, binFolder, existing, null);
    }

    protected BinUserConfig collectBinConfigFromUser(String directory, File binFolder,
                                                    BinConfig existing, BinUserConfig draft) {
        BinUserConfig draftConfig = copyBinUserConfig(draft);
        MetadataDiagnostics.SeriesInfo seriesInfo = existing == null && draftConfig == null
                ? ChannelSetupSupport.firstSeriesInfo(directory)
                : null;
        int n;
        if (draftConfig != null && !draftConfig.names.isEmpty()) {
            n = draftConfig.names.size();
        } else if (existing != null) {
            n = existing.numChannels();
        } else if (seriesInfo != null && seriesInfo.sizeC > 0) {
            n = seriesInfo.sizeC;
        } else {
            PipelineDialog gdCount = setupAnalysisDialog("Set Up Configuration");
            installWizardCancelHook(gdCount);
            gdCount.setPrimaryButtonText(NextStepLabels.NAME_CHANNELS);
            gdCount.addAnalysisHelpHeader("Set Up Configuration", FLASH_Pipeline.IDX_CREATE_BIN);
            gdCount.addSetupHelpSubHeader("Channel Setup", SetupHelpCatalog.CHANNEL_IDENTITY);
            addSavedStatusMessage(gdCount);
            final JTextField channelCountField = gdCount.addNumericField("Number of channels", 3, 0);
            JLabel channelCountHint = gdCount.addHelpText("");
            bindValidation(gdCount, channelCountField, new ValidationCheck() {
                @Override public boolean isValid() {
                    return isValidChannelCountToken(channelCountField.getText());
                }
            }, channelCountHint, "Enter at least 1 channel.");
            final BinUserConfig[] loadedFromRun = new BinUserConfig[1];
            final BinUserConfig[] copiedConfig = new BinUserConfig[1];
            installLoadFromRunButton(gdCount, directory, loadedFromRun, new Runnable() {
                @Override public void run() {
                    gdCount.closeWithAction("loaded_run");
                }
            });
            installCopyFromPresetOrProjectButton(gdCount, binFolder, 0, copiedConfig, new Runnable() {
                @Override public void run() {
                    gdCount.closeWithAction(COPY_CONFIG_ACTION);
                }
            });
            if (!gdCount.showDialog()) {
                if (COPY_CONFIG_ACTION.equals(gdCount.getActionCommand()) && copiedConfig[0] != null) {
                    draftConfig = copiedConfig[0];
                    n = Math.max(1, draftConfig.names.size());
                } else if ("loaded_run".equals(gdCount.getActionCommand()) && loadedFromRun[0] != null) {
                    draftConfig = loadedFromRun[0];
                    n = Math.max(1, draftConfig.names.size());
                } else {
                    return null;
                }
            } else {
                n = (int) gdCount.getNextNumber();
                if (n <= 0) {
                    IJ.error("Set Up Configuration", "Must have at least 1 channel.");
                    return null;
                }
            }
        }

        // Channel identity dialog — Back returns to channel count
        while (true) {
            if (draftConfig != null) {
                padConfigToChannelCount(draftConfig, n);
                trimConfigToChannelCount(draftConfig, n);
            }
            final BinUserConfig dialogDefaults = normalizedConfigForChannelCount(existing, draftConfig, n);
            activeWizardCfg = dialogDefaults;
            PipelineDialog pd = setupAnalysisDialog("Set Up Configuration - Channel Identity");
            installWizardCancelHook(pd);
            pd.setModal(false);
            pd.setPrimaryButtonText(NextStepLabels.ANALYSIS_SCOPE);
            if (existing == null) pd.enableBackButton();
            pd.addSetupHelpHeader("Channel Identity", SetupHelpCatalog.CHANNEL_IDENTITY);
            addSavedStatusMessage(pd);
            final int channelCount = n;
            final BinSetupBindings binBindings = new BinSetupBindings(channelCount);
            pd.addMessage("Assign each channel's display name and LUT.");
            ChannelIdentityGrid identityGrid = buildChannelIdentityGrid(dialogDefaults,
                    false, false, null);
            bindChannelIdentityGrid(binBindings, identityGrid);
            pd.addComponent(identityGrid.panel);
            JLabel channelNameHint = pd.addHelpText("");
            bindChannelNameValidation(pd, binBindings.nameFields, channelNameHint);
            final BinUserConfig[] loadedFromRun = new BinUserConfig[1];
            final BinUserConfig[] copiedConfig = new BinUserConfig[1];
            installLoadFromRunButton(pd, directory, loadedFromRun, new Runnable() {
                @Override public void run() {
                    if (loadedFromRun[0] != null) {
                        applyLoadedConfigToIdentityBindings(loadedFromRun[0], binBindings);
                    }
                    pd.closeWithAction("loaded_run");
                }
            });
            installCopyFromPresetOrProjectButton(pd, binFolder, channelCount, copiedConfig, new Runnable() {
                @Override public void run() {
                    if (copiedConfig[0] != null) {
                        applyLoadedConfigToIdentityBindings(copiedConfig[0], binBindings);
                    }
                }
            });
            if (!pd.showDialog()) {
                if (COPY_CONFIG_ACTION.equals(pd.getActionCommand()) && copiedConfig[0] != null) {
                    return copiedConfig[0];
                }
                if ("loaded_run".equals(pd.getActionCommand()) && loadedFromRun[0] != null) {
                    return loadedFromRun[0];
                }
                if (pd.wasBackPressed() && existing == null) {
                    draftConfig = buildBinUserConfigFromDialog(channelCount, existing,
                            dialogDefaults, binBindings);
                    activeWizardCfg = draftConfig;
                    // Go back to channel count
                    PipelineDialog gdCount2 = new PipelineDialog("Set Up Configuration");
                    installWizardCancelHook(gdCount2);
                    gdCount2.setPrimaryButtonText(NextStepLabels.NAME_CHANNELS);
                    gdCount2.addAnalysisHelpHeader("Set Up Configuration", FLASH_Pipeline.IDX_CREATE_BIN);
                    gdCount2.addSetupHelpSubHeader("Channel Setup", SetupHelpCatalog.CHANNEL_IDENTITY);
                    addSavedStatusMessage(gdCount2);
                    final JTextField channelCountField2 = gdCount2.addNumericField("Number of channels", n, 0);
                    JLabel channelCountHint2 = gdCount2.addHelpText("");
                    bindValidation(gdCount2, channelCountField2, new ValidationCheck() {
                        @Override public boolean isValid() {
                            return isValidChannelCountToken(channelCountField2.getText());
                        }
                    }, channelCountHint2, "Enter at least 1 channel.");
                    final BinUserConfig[] copiedConfig2 = new BinUserConfig[1];
                    installCopyFromPresetOrProjectButton(gdCount2, binFolder, 0, copiedConfig2, new Runnable() {
                        @Override public void run() {
                            gdCount2.closeWithAction(COPY_CONFIG_ACTION);
                        }
                    });
                    if (!gdCount2.showDialog()) {
                        if (COPY_CONFIG_ACTION.equals(gdCount2.getActionCommand()) && copiedConfig2[0] != null) {
                            draftConfig = copiedConfig2[0];
                            n = Math.max(1, draftConfig.names.size());
                            continue;
                        }
                        return null;
                    }
                    n = (int) gdCount2.getNextNumber();
                    if (n <= 0) {
                        IJ.error("Set Up Configuration", "Must have at least 1 channel.");
                        return null;
                    }
                    padConfigToChannelCount(draftConfig, n);
                    trimConfigToChannelCount(draftConfig, n);
                    continue; // re-show channel identity with new n
                }
                return null;
            }

            BinUserConfig userCfg = buildBinUserConfigFromDialog(n, existing,
                    dialogDefaults, binBindings);
            applyHandledCustomDemotions(binFolder, userCfg);
            return userCfg;
        }
    }

    public LoadedRunParameters.Result applyLoadedParameters(Map<String, Object> parameters) {
        LoadedRunParameters.PresetLoad<BinConfig> loaded = LoadedRunParameters.binConfig(parameters);
        activeWizardCfg = fromBinConfig(loaded.payload);
        LoadedRunParameters.rememberLastResult(loaded.result);
        return loaded.result;
    }

    private LoadedRunParameters.Result applyLoadedParameters(Map<String, Object> parameters,
                                                            BinUserConfig[] target) {
        LoadedRunParameters.PresetLoad<BinConfig> loaded = LoadedRunParameters.binConfig(parameters);
        BinUserConfig cfg = fromBinConfig(loaded.payload);
        if (target != null && target.length > 0) {
            target[0] = cfg;
        }
        activeWizardCfg = copyBinUserConfig(cfg);
        LoadedRunParameters.rememberLastResult(loaded.result);
        return loaded.result;
    }

    private void installLoadFromRunButton(PipelineDialog dialog,
                                          String directory,
                                          final BinUserConfig[] target) {
        installLoadFromRunButton(dialog, directory, target, null);
    }

    private void installLoadFromRunButton(PipelineDialog dialog,
                                          String directory,
                                          final BinUserConfig[] target,
                                          final Runnable afterApply) {
        LoadFromRunButton.install(dialog, "CreateBinFileAnalysis", new File(directory),
                new LoadedRunParameterApplier() {
                    @Override public LoadedRunParameters.Result applyLoadedParameters(
                            Map<String, Object> parameters) {
                        LoadedRunParameters.Result result =
                                CreateBinFileAnalysis.this.applyLoadedParameters(parameters, target);
                        if (afterApply != null) {
                            afterApply.run();
                        }
                        return result;
                    }
                });
    }

    private void installCopyFromPresetOrProjectButton(final PipelineDialog dialog,
                                                      final File binFolder,
                                                      final int currentChannelCount,
                                                      final BinUserConfig[] target,
                                                      final Runnable afterApply) {
        if (dialog == null || shouldSkipImportUi()) {
            return;
        }
        JButton copyButton = dialog.addFooterButton("Copy from preset or project...");
        copyButton.addActionListener(e -> dialog.runChildWorkflow(new Runnable() {
            @Override public void run() {
                BinUserConfig copied = copyConfigFromPresetOrProject(
                        dialog.getWindow(),
                        projectRootForConfigurationDir(binFolder),
                        currentChannelCount);
                if (copied == null) {
                    return;
                }
                if (target != null && target.length > 0) {
                    target[0] = copied;
                }
                activeWizardCfg = copyBinUserConfig(copied);
                if (afterApply != null) {
                    afterApply.run();
                }
            }
        }));
    }

    private boolean shouldSkipImportUi() {
        return headless || suppressDialogs || GraphicsEnvironment.isHeadless();
    }

    private BinUserConfig copyConfigFromPresetOrProject(Window owner, File projectRoot,
                                                        int currentChannelCount) {
        if (shouldSkipImportUi()) {
            return null;
        }
        ImportSourceSelection selection = chooseImportSource(owner, projectRoot);
        if (selection == null) {
            return null;
        }
        ChannelConfig importedConfig;
        if (selection.preset != null) {
            importedConfig = channelConfigFromPreset(selection.preset);
        } else {
            importedConfig = readImportedChannelConfig(selection.file);
            if (importedConfig == null) {
                showImportReadFailure(owner, selection.file);
                return null;
            }
        }
        BinUserConfig imported = binUserConfigFromChannelConfig(importedConfig);
        return adjustImportedConfigForChannelCount(owner, imported, currentChannelCount);
    }

    private ImportSourceSelection chooseImportSource(Window owner, File projectRoot) {
        if (shouldSkipImportUi()) {
            return null;
        }
        List<BinPreset> presets = Collections.emptyList();
        File safeProjectRoot = projectRoot == null ? new File(".") : projectRoot;
        try {
            presets = new BinPresetIO(safeProjectRoot).listAll();
        } catch (IOException e) {
            IJ.log("[FLASH] Could not list channel configuration presets: " + e.getMessage());
        }

        List<String> labels = new ArrayList<String>();
        for (int i = 0; i < presets.size(); i++) {
            labels.add("Preset: " + presets.get(i).getName());
        }
        labels.add("Browse for another project...");

        PipelineDialog picker = new PipelineDialog(owner, "Copy setup");
        picker.addHeader("Copy setup");
        picker.addMessage("Choose a saved preset or browse to another project's channel_config.json.");
        final JComboBox<String> sourceChoice = picker.addChoice(
                "Source",
                labels.toArray(new String[labels.size()]),
                labels.get(0));
        picker.setPrimaryButtonText("Copy");
        if (!picker.showDialog()) {
            return null;
        }
        int index = sourceChoice.getSelectedIndex();
        if (index >= 0 && index < presets.size()) {
            return ImportSourceSelection.forPreset(presets.get(index));
        }
        File selected = chooseProjectConfigFile(owner, safeProjectRoot);
        return selected == null ? null : ImportSourceSelection.forFile(selected);
    }

    private File chooseProjectConfigFile(Window owner, File projectRoot) {
        if (shouldSkipImportUi()) {
            return null;
        }
        JFileChooser chooser = new JFileChooser(projectRoot == null ? new File(".") : projectRoot);
        chooser.setDialogTitle("Copy channel configuration");
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setAcceptAllFileFilterUsed(true);
        chooser.addChoosableFileFilter(new FileNameExtensionFilter(
                "FLASH channel config (channel_config.json)", "json"));
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        return chooser.getSelectedFile();
    }

    private BinUserConfig adjustImportedConfigForChannelCount(Window owner, BinUserConfig imported,
                                                              int currentChannelCount) {
        if (imported == null) {
            return null;
        }
        int importedChannelCount = imported.names == null ? 0 : imported.names.size();
        if (currentChannelCount <= 0 || importedChannelCount == currentChannelCount) {
            return imported;
        }
        ImportChannelCountAction action = showImportChannelCountMismatch(
                owner, importedChannelCount, currentChannelCount);
        if (action == ImportChannelCountAction.CANCEL) {
            return null;
        }
        BinUserConfig adjusted = copyBinUserConfig(imported);
        if (action == ImportChannelCountAction.TRUNCATE) {
            trimConfigToChannelCount(adjusted, currentChannelCount);
        } else if (action == ImportChannelCountAction.PAD) {
            padConfigToChannelCount(adjusted, currentChannelCount);
        }
        return adjusted;
    }

    protected ImportChannelCountAction showImportChannelCountMismatch(Window owner,
                                                                      int importedChannelCount,
                                                                      int currentChannelCount) {
        if (shouldSkipImportUi()) {
            return ImportChannelCountAction.CANCEL;
        }
        PipelineDialog dialog = new PipelineDialog(owner, "Channel count mismatch");
        dialog.addHeader("Channel count mismatch");
        dialog.addMessage("The copied setup has " + importedChannelCount
                + " channels, but the current image has " + currentChannelCount
                + ". Choose how to adapt it before copying.");
        dialog.setDefaultButtonsVisible(false);
        JButton cancel = dialog.addRightFooterButton("Cancel");
        cancel.addActionListener(e -> dialog.closeWithAction("cancel"));
        if (importedChannelCount > currentChannelCount) {
            JButton truncate = dialog.addRightFooterButton("Truncate to " + currentChannelCount);
            truncate.addActionListener(e -> dialog.closeWithAction("truncate"));
        } else {
            JButton pad = dialog.addRightFooterButton("Pad with defaults");
            pad.addActionListener(e -> dialog.closeWithAction("pad"));
        }
        dialog.showDialog();
        String action = dialog.getActionCommand();
        if ("truncate".equals(action)) {
            return ImportChannelCountAction.TRUNCATE;
        }
        if ("pad".equals(action)) {
            return ImportChannelCountAction.PAD;
        }
        return ImportChannelCountAction.CANCEL;
    }

    private void showImportReadFailure(Window owner, File selected) {
        String reason = "No readable channel_config.json was found for the selected project or file.";
        String source = selected == null ? "" : "\nSource: " + selected.getAbsolutePath();
        showImportMessage(owner, "Copy setup", reason + source);
    }

    private void showImportMessage(Window owner, String title, String message) {
        if (shouldSkipImportUi()) {
            IJ.log("[FLASH] " + message);
            return;
        }
        PipelineDialog dialog = new PipelineDialog(owner, title);
        dialog.addHeader(title);
        dialog.addMessage(escapeHtmlText(message).replace("\n", "<br>"));
        dialog.showDialog();
    }

    private static ChannelConfig readImportedChannelConfig(File selected) {
        File settingsDir = resolveImportedSettingsDir(selected);
        return settingsDir == null ? null : ChannelConfigIO.read(settingsDir);
    }

    public static BinUserConfig importBinUserConfigFromSettingsDir(File selected) {
        ChannelConfig cfg = readImportedChannelConfig(selected);
        return cfg == null ? null : binUserConfigFromChannelConfig(cfg);
    }

    private static File resolveImportedSettingsDir(File selected) {
        if (selected == null) {
            return null;
        }
        if (selected.isFile()) {
            return ChannelConfigIO.FILE_NAME.equals(selected.getName())
                    ? selected.getParentFile()
                    : null;
        }
        if (!selected.isDirectory()) {
            return null;
        }
        File directConfig = new File(selected, ChannelConfigIO.FILE_NAME);
        if (directConfig.isFile()) {
            return selected;
        }
        File settingsChild = FlashProjectLayout.settingsDir(selected);
        if (new File(settingsChild, ChannelConfigIO.FILE_NAME).isFile()) {
            return settingsChild;
        }
        File projectSettings = FlashProjectLayout.forDirectory(selected.getPath()).configurationWriteDir();
        return new File(projectSettings, ChannelConfigIO.FILE_NAME).isFile() ? projectSettings : null;
    }

    private static ChannelConfig channelConfigFromPreset(BinPreset preset) {
        if (preset == null) {
            return null;
        }
        ChannelConfig cfg = ChannelConfigIO.fromBinConfig(preset.getPayload());
        for (int i = 0; cfg.channels != null && i < cfg.channels.size(); i++) {
            ChannelConfig.Channel channel = cfg.channels.get(i);
            if (channel == null) {
                continue;
            }
            channel.markerId = valueAt(preset.getMarkerIds(), i, "");
            channel.markerShape = valueAt(preset.getMarkerShapes(), i, "");
            channel.markerCrowdingSensitive =
                    valueAt(preset.getMarkerCrowdingSensitive(), i, Boolean.FALSE).booleanValue();
        }
        return cfg;
    }

    public static BinUserConfig binUserConfigFromPreset(BinPreset preset) {
        ChannelConfig cfg = channelConfigFromPreset(preset);
        return cfg == null ? null : binUserConfigFromChannelConfig(cfg);
    }

    protected enum ImportChannelCountAction {
        CANCEL,
        TRUNCATE,
        PAD
    }

    private static final class ImportSourceSelection {
        final BinPreset preset;
        final File file;

        private ImportSourceSelection(BinPreset preset, File file) {
            this.preset = preset;
            this.file = file;
        }

        static ImportSourceSelection forPreset(BinPreset preset) {
            return new ImportSourceSelection(preset, null);
        }

        static ImportSourceSelection forFile(File file) {
            return new ImportSourceSelection(null, file);
        }
    }

    private void addSavedStatusMessage(PipelineDialog dialog) {
        if (dialog == null) return;
        String status = savedStatusText(lastWizardSavedAtMillis);
        dialog.addHelpText(hasText(status) ? status : "");
    }

    private static void bindValidation(PipelineDialog dialog,
                                       JTextField field,
                                       final ValidationCheck check,
                                       JLabel hintLabel,
                                       String hintText) {
        PipelineDialog.bindValidation(dialog, field, new java.util.function.Predicate<String>() {
            @Override public boolean test(String ignored) {
                return check != null && check.isValid();
            }
        }, hintLabel, hintText);
    }

    private static void bindValidation(PipelineDialog dialog,
                                       JTextField[] fields,
                                       final ValidationCheck check,
                                       JLabel hintLabel,
                                       String hintText) {
        if (fields == null) return;
        for (int i = 0; i < fields.length; i++) {
            if (fields[i] != null) {
                bindValidation(dialog, fields[i], check, hintLabel, hintText);
            }
        }
    }

    private static void bindChannelNameValidation(final PipelineDialog dialog,
                                                  final JTextField[] fields,
                                                  JLabel hintLabel) {
        if (fields == null) return;
        for (int i = 0; i < fields.length; i++) {
            JTextField field = fields[i];
            if (field == null) continue;
            bindValidation(dialog, field, new ValidationCheck() {
                @Override public boolean isValid() {
                    return areValidChannelNames(fields);
                }
            }, hintLabel, "Enter a name for every channel.");
        }
    }

    static boolean areValidChannelNames(JTextField[] fields) {
        if (fields == null || fields.length == 0) return false;
        for (int i = 0; i < fields.length; i++) {
            if (fields[i] == null || !isValidChannelName(fields[i].getText())) {
                return false;
            }
        }
        return true;
    }

    static boolean isValidChannelName(String value) {
        return hasText(value);
    }

    static boolean isValidChannelCountToken(String value) {
        try {
            double parsed = Double.parseDouble(value == null ? "" : value.trim());
            return !Double.isNaN(parsed) && !Double.isInfinite(parsed) && parsed > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static boolean areValidNumericFields(JTextField[] fields) {
        if (fields == null || fields.length == 0) return false;
        for (int i = 0; i < fields.length; i++) {
            if (fields[i] == null || !isValidNumericToken(fields[i].getText())) {
                return false;
            }
        }
        return true;
    }

    private static void applyLoadedConfigToIdentityBindings(BinUserConfig cfg,
                                                           BinSetupBindings bindings) {
        if (cfg == null || bindings == null) {
            return;
        }
        int count = Math.min(bindings.nameFields.length, cfg.names.size());
        for (int i = 0; i < count; i++) {
            JTextField nameField = bindings.nameFields[i];
            if (nameField != null) {
                nameField.putClientProperty(CHANNEL_IDENTITY_LUT_SYNC_SUPPRESSED_PROPERTY, Boolean.TRUE);
            }
            try {
                if (nameField != null) {
                    nameField.setText(valueAt(cfg.names, i, "Channel" + (i + 1)));
                }
                if (bindings.colorCombos[i] != null) {
                    selectComboItem(bindings.colorCombos[i], toLutName(valueAt(cfg.colors, i, "Grays")));
                }
            } finally {
                if (nameField != null) {
                    nameField.putClientProperty(CHANNEL_IDENTITY_LUT_SYNC_SUPPRESSED_PROPERTY, Boolean.FALSE);
                }
            }
        }
        bindings.appliedConfig = cfg;
    }

    // ── Image loading for QC ────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    static ChannelIdentityGrid buildChannelIdentityGrid(BinUserConfig defaults,
                                                        boolean starDistAvailable,
                                                        boolean cellposeReady,
                                                        CellposeRuntime.Status cellposeStatus) {
        final int nCh = defaults == null ? 0 : defaults.names.size();
        final JTextField[] nameFields = new JTextField[nCh];
        final JComboBox<String>[] lutCombos = new JComboBox[nCh];
        final JComboBox<String>[] segmentationCombos = new JComboBox[nCh];
        final MarkerLibrary markerLibrary = loadChannelIdentityMarkerLibrary();
        final JLabel[] rowLabels = new JLabel[]{
                createChannelIdentityGridRowLabel("Channel name"),
                createChannelIdentityGridRowLabel("LUT")
        };

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(0, 2, 2, 2));

        addChannelIdentityGridComponent(panel, Box.createHorizontalStrut(CHANNEL_IDENTITY_ROW_LABEL_WIDTH),
                0, 0, 0.0, GridBagConstraints.NONE, new Insets(0, 0, 2, 6));
        for (int ch = 0; ch < nCh; ch++) {
            JLabel channelLabel = new JLabel("Channel " + (ch + 1));
            channelLabel.setFont(channelLabel.getFont().deriveFont(Font.BOLD, 12f));
            channelLabel.setForeground(Color.DARK_GRAY);
            addChannelIdentityGridComponent(panel, channelLabel,
                    ch + 1, 0, 1.0, GridBagConstraints.HORIZONTAL, new Insets(0, 0, 2, 8));
        }

        addChannelIdentityGridComponent(panel, rowLabels[0],
                0, 1, 0.0, GridBagConstraints.NONE, new Insets(2, 0, 2, 6));
        addChannelIdentityGridComponent(panel, rowLabels[1],
                0, 2, 0.0, GridBagConstraints.NONE, new Insets(2, 0, 2, 6));

        for (int ch = 0; ch < nCh; ch++) {
            String defName = valueAt(defaults.names, ch, "Channel" + (ch + 1));
            String defColor = toLutName(valueAt(defaults.colors, ch, "Grays"));

            nameFields[ch] = new JTextField(defName, 14);
            nameFields[ch].setName("createBin.channel." + ch + ".name");
            constrainChannelIdentityInput(nameFields[ch], CHANNEL_IDENTITY_NAME_WIDTH);
            MarkerAutoComplete.attach(nameFields[ch], markerLibrary);

            lutCombos[ch] = new JComboBox<String>(COLOR_OPTIONS);
            lutCombos[ch].setName("createBin.channel." + ch + ".lut");
            selectComboItem(lutCombos[ch], defColor);
            constrainChannelIdentityInput(lutCombos[ch], CHANNEL_IDENTITY_CELL_WIDTH);
            installReporterProteinLutSync(nameFields[ch], lutCombos[ch], markerLibrary);

            addChannelIdentityGridComponent(panel,
                    createChannelIdentityGridCell(nameFields[ch]),
                    ch + 1, 1, 1.0, GridBagConstraints.HORIZONTAL, new Insets(2, 0, 2, 8));
            addChannelIdentityGridComponent(panel,
                    createChannelIdentityGridCell(lutCombos[ch]),
                    ch + 1, 2, 1.0, GridBagConstraints.HORIZONTAL, new Insets(2, 0, 2, 8));
        }

        return new ChannelIdentityGrid(panel, nameFields, lutCombos, segmentationCombos, rowLabels);
    }

    private static MarkerLibrary loadChannelIdentityMarkerLibrary() {
        try {
            return MarkerLibrary.loadBundled();
        } catch (IOException e) {
            IJ.log("WARNING: Could not load bundled marker library for channel identity: " + e.getMessage());
            return null;
        }
    }

    private static void installReporterProteinLutSync(final JTextField nameField,
                                                      final JComboBox<String> lutCombo,
                                                      final MarkerLibrary markerLibrary) {
        if (nameField == null || lutCombo == null || markerLibrary == null) {
            return;
        }
        nameField.getDocument().addDocumentListener(new DocumentListener() {
            private String lutBeforeAuto;
            private String autoAppliedLut;

            @Override public void insertUpdate(DocumentEvent e) { sync(); }
            @Override public void removeUpdate(DocumentEvent e) { sync(); }
            @Override public void changedUpdate(DocumentEvent e) { sync(); }

            private void sync() {
                if (Boolean.TRUE.equals(nameField.getClientProperty(
                        CHANNEL_IDENTITY_LUT_SYNC_SUPPRESSED_PROPERTY))) {
                    resetAutoState();
                    return;
                }
                MarkerLibrary.Entry entry = markerLibrary.exactMatch(nameField.getText());
                if (!isReporterProteinMarker(entry) || !hasText(entry.getConventionalLUT())) {
                    clearAutoAppliedLut();
                    return;
                }
                String suggestedLut = toLutName(entry.getConventionalLUT());
                String currentLut = comboText(lutCombo, "Grays");
                if (autoAppliedLut == null || !autoAppliedLut.equals(currentLut)) {
                    lutBeforeAuto = currentLut;
                }
                selectComboItem(lutCombo, suggestedLut);
                autoAppliedLut = suggestedLut;
            }

            private void clearAutoAppliedLut() {
                if (autoAppliedLut != null && autoAppliedLut.equals(comboText(lutCombo, "Grays"))) {
                    selectComboItem(lutCombo, lutBeforeAuto == null ? "Grays" : lutBeforeAuto);
                }
                resetAutoState();
            }

            private void resetAutoState() {
                autoAppliedLut = null;
                lutBeforeAuto = null;
            }
        });
    }

    private static boolean isReporterProteinMarker(MarkerLibrary.Entry entry) {
        if (entry == null) {
            return false;
        }
        List<String> categories = entry.getAllCategories();
        for (int i = 0; i < categories.size(); i++) {
            if (MARKER_CATEGORY_REPORTER_PROTEIN.equals(categories.get(i))) {
                return true;
            }
        }
        return false;
    }

    private static void bindChannelIdentityGrid(BinSetupBindings bindings,
                                                ChannelIdentityGrid grid) {
        if (bindings == null || grid == null) {
            return;
        }
        for (int i = 0; i < bindings.nameFields.length; i++) {
            bindings.nameFields[i] = i < grid.nameFields.length ? grid.nameFields[i] : null;
            bindings.colorCombos[i] = i < grid.lutCombos.length ? grid.lutCombos[i] : null;
            bindings.segmentationCombos[i] = i < grid.segmentationCombos.length ? grid.segmentationCombos[i] : null;
        }
    }

    private static JLabel createChannelIdentityGridRowLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
        label.setForeground(Color.DARK_GRAY);
        label.setPreferredSize(new Dimension(CHANNEL_IDENTITY_ROW_LABEL_WIDTH, 24));
        return label;
    }

    private static JPanel createChannelIdentityGridCell(JComponent input) {
        JPanel cell = new JPanel();
        cell.setLayout(new BoxLayout(cell, BoxLayout.Y_AXIS));
        cell.setOpaque(false);
        cell.setAlignmentX(Component.LEFT_ALIGNMENT);
        input.setAlignmentX(Component.LEFT_ALIGNMENT);
        cell.add(input);
        return cell;
    }

    private static void constrainChannelIdentityInput(JComponent input, int width) {
        Dimension size = new Dimension(width, 24);
        input.setPreferredSize(size);
        input.setMaximumSize(size);
    }

    private static void addChannelIdentityGridComponent(JPanel panel, Component component,
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

    private static String plainSegmentationHelp(String selection,
                                                boolean starDistAvailable,
                                                CellposeRuntime.Status cellposeStatus) {
        String normalized = selection == null ? SEGMENTATION_CLASSICAL : selection.trim();
        if (SEGMENTATION_STARDIST.equals(normalized)) {
            return starDistAvailable
                    ? "Best for compact touching nuclei or soma."
                    : "Unavailable: " + StarDistDetector.getAvailabilityMessage();
        }
        if (SEGMENTATION_CELLPOSE.equals(normalized)) {
            if (cellposeStatus != null && !cellposeStatus.summary().isEmpty()) {
                return cellposeStatus.summary();
            }
            return "Flexible whole-cell or irregular object segmentation.";
        }
        if (SEGMENTATION_ENHANCED_CLASSICAL.equals(normalized)) {
            return "Classical 3D object detection plus optional morphology filters.";
        }
        if (isTrainedRfDisplayChoice(normalized)) {
            return "Saved trained Random Forest model from this project's catalog.";
        }
        return "Classical filter and threshold segmentation.";
    }

    private BinUserConfig buildBinUserConfigFromDialog(int channelCount,
                                                       BinConfig existing,
                                                       BinSetupBindings bindings) {
        return buildBinUserConfigFromDialog(channelCount, existing, null, bindings);
    }

    private BinUserConfig buildBinUserConfigFromDialog(int channelCount,
                                                       BinConfig existing,
                                                       BinUserConfig draft,
                                                       BinSetupBindings bindings) {
        return buildBinUserConfigFromDialog(channelCount, existing, draft, bindings,
                BinConfig.DEFAULT_CELLPOSE_USE_GPU);
    }

    private BinUserConfig buildBinUserConfigFromDialog(int channelCount,
                                                       BinConfig existing,
                                                       BinUserConfig draft,
                                                       BinSetupBindings bindings,
                                                       boolean preferCellposeGpu) {
        BinUserConfig existingUser = normalizedConfigForChannelCount(existing, draft, channelCount);
        BinUserConfig applied = bindings == null ? null : bindings.appliedConfig;
        List<String> names = new ArrayList<String>();
        List<String> colors = new ArrayList<String>();
        List<String> objThresholds = new ArrayList<String>();
        List<String> sizes = new ArrayList<String>();
        List<String> minmax = new ArrayList<String>();
        List<String> filterPresets = new ArrayList<String>();
        List<String> intThresholds = new ArrayList<String>();
        BinUserConfig sourceForHiddenFields = applied == null
                ? existingUser
                : normalizedConfigForChannelCount(null, applied, channelCount);

        for (int i = 0; i < channelCount; i++) {
            names.add(textFromField(bindings == null ? null : bindings.nameFields[i],
                    valueAt(sourceForHiddenFields.names, i, "Channel" + (i + 1))));
            colors.add(comboText(bindings == null ? null : bindings.colorCombos[i],
                    valueAt(sourceForHiddenFields.colors, i, "Grays")));
            String filterFallback = valueAt(sourceForHiddenFields.filterPresets, i, FILTER_PRESETS[0]);
            filterPresets.add(safeFilterPresetSelection(
                    comboText(bindings == null ? null : bindings.filterCombos[i], filterFallback),
                    filterFallback));
            objThresholds.add(valueAt(sourceForHiddenFields.objectThresholds, i, "default"));
            sizes.add(valueAt(sourceForHiddenFields.sizes, i, "100-Infinity"));
            minmax.add(valueAt(sourceForHiddenFields.minmax, i, "None"));
            intThresholds.add(valueAt(sourceForHiddenFields.intensityThresholds, i, "default"));
        }

        BinUserConfig cfg = new BinUserConfig(names, colors, objThresholds, sizes,
                minmax, filterPresets, intThresholds);
        cfg.segmentationMethods.clear();
        for (int i = 0; i < channelCount; i++) {
            String currentMethod = valueAt(sourceForHiddenFields.segmentationMethods, i, "classical");
            String selection = comboText(bindings == null ? null : bindings.segmentationCombos[i],
                    segmentationChoiceForMethod(currentMethod));
            cfg.segmentationMethods.add(currentMethod);
            applySegmentationSelection(cfg.segmentationMethods, i, selection, currentMethod,
                    preferCellposeGpu);
            normalizeNewStarDistSizeDefault(cfg, i, currentMethod);
        }

        cfg.markerIds.clear();
        cfg.markerShapes.clear();
        cfg.markerCrowdingSensitive.clear();
        for (int i = 0; i < channelCount; i++) {
            cfg.markerIds.add(valueAt(sourceForHiddenFields.markerIds, i, ""));
            cfg.markerShapes.add(valueAt(sourceForHiddenFields.markerShapes, i, ""));
            cfg.markerCrowdingSensitive.add(valueAt(sourceForHiddenFields.markerCrowdingSensitive, i, Boolean.FALSE));
        }
        cfg.zSliceMode = sourceForHiddenFields.zSliceMode == null ? ZSliceMode.FULL : sourceForHiddenFields.zSliceMode;
        cfg.zSliceSelections.putAll(sourceForHiddenFields.zSliceSelections);
        return cfg;
    }

    private static BinConfig toPresetBinConfig(BinUserConfig cfg) {
        BinConfig binCfg = new BinConfig();
        if (cfg == null) {
            return binCfg;
        }
        binCfg.channelNames.addAll(cfg.names);
        binCfg.channelColors.addAll(cfg.colors);
        binCfg.channelThresholds.addAll(cfg.objectThresholds);
        binCfg.channelSizes.addAll(cfg.sizes);
        binCfg.channelMinMax.addAll(cfg.minmax);
        binCfg.channelFilterPresets.addAll(cfg.filterPresets);
        binCfg.channelIntensityThresholds.addAll(cfg.intensityThresholds);
        binCfg.segmentationMethods.addAll(cfg.segmentationMethods);
        binCfg.zSliceMode = cfg.zSliceMode == null ? ZSliceMode.FULL : cfg.zSliceMode;
        binCfg.zSliceSelections.putAll(cfg.zSliceSelections);
        return binCfg;
    }

    private static String textFromField(JTextField field, String fallback) {
        if (field == null) {
            return fallback;
        }
        String text = field.getText();
        return text == null || text.trim().isEmpty() ? fallback : text.trim();
    }

    private static String comboText(JComboBox<String> combo, String fallback) {
        if (combo == null || combo.getSelectedItem() == null) {
            return fallback;
        }
        String text = String.valueOf(combo.getSelectedItem()).trim();
        return text.isEmpty() ? fallback : text;
    }

    private static void selectComboItem(JComboBox<String> combo, String value) {
        ensureComboItem(combo, value);
        combo.setSelectedItem(value);
    }

    private static void ensureComboItem(JComboBox<String> combo, String value) {
        if (combo == null || value == null) {
            return;
        }
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (value.equals(combo.getItemAt(i))) {
                return;
            }
        }
        combo.addItem(value);
    }

    private static String valueAt(List<String> values, int index, String fallback) {
        if (values == null || index < 0 || index >= values.size()) return fallback;
        String value = values.get(index);
        return value == null ? fallback : value;
    }

    private static Boolean valueAt(List<Boolean> values, int index, Boolean fallback) {
        if (values == null || index < 0 || index >= values.size()) return fallback;
        Boolean value = values.get(index);
        return value == null ? fallback : value;
    }

    private static final class BinSetupBindings {
        boolean programmaticChange;
        JComboBox<String> presetCombo;
        final JTextField[] nameFields;
        final JComboBox<String>[] colorCombos;
        final JComboBox<String>[] filterCombos;
        final JComboBox<String>[] segmentationCombos;
        BinUserConfig appliedConfig;

        @SuppressWarnings("unchecked")
        BinSetupBindings(int channelCount) {
            int count = Math.max(0, channelCount);
            this.nameFields = new JTextField[count];
            this.colorCombos = new JComboBox[count];
            this.filterCombos = new JComboBox[count];
            this.segmentationCombos = new JComboBox[count];
        }
    }

    static final class ChannelIdentityGrid {
        final JPanel panel;
        final JTextField[] nameFields;
        final JComboBox<String>[] lutCombos;
        final JComboBox<String>[] segmentationCombos;
        final JLabel[] rowLabels;

        ChannelIdentityGrid(JPanel panel,
                            JTextField[] nameFields,
                            JComboBox<String>[] lutCombos,
                            JComboBox<String>[] segmentationCombos,
                            JLabel[] rowLabels) {
            this.panel = panel;
            this.nameFields = nameFields;
            this.lutCombos = lutCombos;
            this.segmentationCombos = segmentationCombos;
            this.rowLabels = rowLabels;
        }
    }

    private void installFilterDescriptionUpdater(final JComboBox<String> filterCombo,
                                                 final JLabel filterDesc) {
        filterCombo.addActionListener(new java.awt.event.ActionListener() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (isFilterComboUpdating(filterCombo)) return;
                final String selected = (String) filterCombo.getSelectedItem();
                filterDesc.setText(filterDescriptionFor(selected));
            }
        });
    }

    private String[] filterPresetOptions(File binFolder, String selectedPreset) {
        return filterPresetOptionsWithSaved(Collections.<String>emptyList(), selectedPreset, null);
    }

    private String[] filterPresetOptionsWithSaved(List<String> savedPresets,
                                                  String selectedPreset,
                                                  String currentSelection) {
        List<String> options = new ArrayList<String>();
        for (int i = 0; i < NamedFilterLoader.FILTER_NAMES.length; i++) {
            addUniqueOption(options, NamedFilterLoader.FILTER_NAMES[i]);
        }
        if (savedPresets != null) {
            for (int i = 0; i < savedPresets.size(); i++) {
                addUniqueOption(options, savedPresets.get(i));
            }
        }
        addUniqueOption(options, "Custom");
        if (selectedPreset != null && selectedPreset.trim().length() > 0) {
            addUniqueOption(options, selectedPreset.trim());
        }
        if (currentSelection != null && currentSelection.trim().length() > 0) {
            addUniqueOption(options, currentSelection.trim());
        }
        return options.toArray(new String[options.size()]);
    }

    private void addUniqueOption(List<String> options, String value) {
        if (value == null || value.trim().length() == 0) return;
        String trimmed = value.trim();
        if (isLoadingFilterOption(trimmed)) return;
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).equalsIgnoreCase(trimmed)) return;
        }
        options.add(trimmed);
    }

    private void installAsyncFilterPresetOptions(final File binFolder,
                                                 final JComboBox<String> combo,
                                                 final JLabel filterDesc,
                                                 final String selectedPreset) {
        if (combo == null) return;
        final Object token = new Object();
        combo.putClientProperty(FILTER_OPTIONS_REFRESH_TOKEN_PROPERTY, token);
        combo.addHierarchyListener(new java.awt.event.HierarchyListener() {
            @Override public void hierarchyChanged(java.awt.event.HierarchyEvent e) {
                if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.DISPLAYABILITY_CHANGED) != 0
                        && combo.isDisplayable()) {
                    combo.putClientProperty(FILTER_OPTIONS_WAS_DISPLAYABLE_PROPERTY, Boolean.TRUE);
                }
            }
        });
        BinMacroIndex.savedCustomFilterPresetNamesAsync(binFolder)
                .whenComplete(new java.util.function.BiConsumer<List<String>, Throwable>() {
                    @Override public void accept(final List<String> savedPresets, final Throwable error) {
                        if (error != null) {
                            IJ.log("Warning: could not scan saved custom filter presets: "
                                    + error.getMessage());
                            return;
                        }
                        SwingUtilities.invokeLater(new Runnable() {
                            @Override public void run() {
                                if (combo.getClientProperty(FILTER_OPTIONS_REFRESH_TOKEN_PROPERTY) != token) {
                                    return;
                                }
                                if (Boolean.TRUE.equals(combo.getClientProperty(FILTER_OPTIONS_WAS_DISPLAYABLE_PROPERTY))
                                        && !combo.isDisplayable()) {
                                    return;
                                }
                                String currentSelection = safeFilterPresetSelection(
                                        selectedComboValue(combo), selectedPreset);
                                String[] options = filterPresetOptionsWithSaved(
                                        savedPresets, selectedPreset, currentSelection);
                                replaceFilterPresetModel(combo, options, currentSelection, selectedPreset);
                                if (filterDesc != null) {
                                    filterDesc.setText(filterDescriptionFor(selectedComboValue(combo)));
                                }
                            }
                        });
                    }
                });
    }

    private void installAsyncFilterPresetOptions(final File binFolder,
                                                 final FilterParameterStage stage,
                                                 final String selectedPreset) {
        if (stage == null) return;
        final int generation = stage.beginPresetOptionsRefresh();
        BinMacroIndex.savedCustomFilterPresetNamesAsync(binFolder)
                .whenComplete(new java.util.function.BiConsumer<List<String>, Throwable>() {
                    @Override public void accept(final List<String> savedPresets, final Throwable error) {
                        if (error != null) {
                            IJ.log("Warning: could not scan saved custom filter presets: "
                                    + error.getMessage());
                            return;
                        }
                        final List<String> options = filterPresetOptionListWithSaved(
                                savedPresets, selectedPreset, null);
                        stage.refreshPresetOptionsIfCurrent(generation, options, selectedPreset);
                    }
                });
    }

    private void replaceFilterPresetModel(JComboBox<String> combo, String[] options,
                                          String selectedPreset, String fallbackPreset) {
        String selection = safeFilterPresetSelection(selectedPreset, fallbackPreset);
        if (!containsOption(options, selection)) {
            selection = safeFilterPresetSelection(fallbackPreset, "Custom");
        }
        setFilterComboUpdating(combo, true);
        try {
            combo.setModel(new DefaultComboBoxModel<String>(options));
            combo.setSelectedItem(selection);
        } finally {
            setFilterComboUpdating(combo, false);
        }
    }

    private boolean containsOption(String[] options, String value) {
        if (options == null || value == null) return false;
        for (int i = 0; i < options.length; i++) {
            if (value.equalsIgnoreCase(options[i])) return true;
        }
        return false;
    }

    private String selectedComboValue(JComboBox<String> combo) {
        return combo == null || combo.getSelectedItem() == null
                ? null
                : String.valueOf(combo.getSelectedItem());
    }

    private void setFilterComboUpdating(JComboBox<String> combo, boolean updating) {
        if (combo != null) {
            combo.putClientProperty(FILTER_OPTIONS_UPDATING_PROPERTY, Boolean.valueOf(updating));
        }
    }

    private boolean isFilterComboUpdating(JComboBox<String> combo) {
        return combo != null && Boolean.TRUE.equals(combo.getClientProperty(FILTER_OPTIONS_UPDATING_PROPERTY));
    }

    private static String safeFilterPresetSelection(String value, String fallback) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() > 0 && !isLoadingFilterOption(trimmed)) {
            return trimmed;
        }
        String safeFallback = fallback == null ? "" : fallback.trim();
        if (safeFallback.length() > 0 && !isLoadingFilterOption(safeFallback)) {
            return safeFallback;
        }
        return "Custom";
    }

    private static boolean isLoadingFilterOption(String value) {
        return value != null && LOADING_FILTERS_OPTION.equalsIgnoreCase(value.trim());
    }

    private BinUserConfig buildCustomFilterPromptConfig(int channelCount, int channelIndex,
                                                        String channelName, String channelColor) {
        List<String> names = new ArrayList<String>();
        List<String> colors = new ArrayList<String>();
        List<String> objThresholds = new ArrayList<String>();
        List<String> sizes = new ArrayList<String>();
        List<String> minmax = new ArrayList<String>();
        List<String> filterPresets = new ArrayList<String>();
        List<String> intThresholds = new ArrayList<String>();
        for (int i = 0; i < channelCount; i++) {
            names.add(i == channelIndex && channelName != null && !channelName.trim().isEmpty()
                    ? channelName.trim()
                    : "Channel" + (i + 1));
            colors.add(i == channelIndex && channelColor != null && !channelColor.trim().isEmpty()
                    ? channelColor
                    : "Grays");
            objThresholds.add("default");
            sizes.add("100-Infinity");
            minmax.add("None");
            filterPresets.add(i == channelIndex ? "Custom" : "Default");
            intThresholds.add("default");
        }
        return new BinUserConfig(names, colors, objThresholds, sizes, minmax, filterPresets, intThresholds);
    }

    private boolean canShowCustomFilterDialog() {
        return !suppressDialogs && !GraphicsEnvironment.isHeadless();
    }

    private File customFilterPresetDirectory(File binFolder) {
        return layoutForBinFolder(binFolder).customFilterPresetWriteDir();
    }

    private String loadSavedCustomFilterPreset(File binFolder, String presetName) {
        File file = resolveSavedCustomFilterPresetFile(binFolder, presetName, false);
        if (file == null || !file.isFile()) return null;
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            IJ.log("Warning: could not read saved custom filter preset '" + presetName + "': " + e.getMessage());
            return null;
        }
    }

    private void saveCustomFilterPreset(File binFolder, String presetName, String macroContent) throws IOException {
        if (presetName == null || presetName.trim().length() == 0 || macroContent == null) return;
        File dir = customFilterPresetDirectory(binFolder);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Could not create custom filter preset directory: " + dir.getAbsolutePath());
        }
        File target = resolveSavedCustomFilterPresetFile(binFolder, presetName, true);
        if (target == null) throw new IOException("Invalid custom filter preset name: " + presetName);
        Files.write(target.toPath(), macroContent.getBytes(StandardCharsets.UTF_8));
        BinMacroIndex.invalidate(binFolder);
    }

    private File resolveSavedCustomFilterPresetFile(File binFolder, String presetName, boolean forWrite) {
        if (binFolder == null || presetName == null || presetName.trim().length() == 0) return null;
        String fileName = sanitizeCustomFilterPresetName(presetName) + ".ijm";
        if (forWrite) {
            return resolveCustomFilterPresetFile(customFilterPresetDirectory(binFolder), fileName, true);
        }
        List<File> dirs = layoutForBinFolder(binFolder).customFilterPresetReadDirs();
        for (int i = 0; i < dirs.size(); i++) {
            File target = resolveCustomFilterPresetFile(dirs.get(i), fileName, false);
            if (target != null) return target;
        }
        return null;
    }

    private File resolveCustomFilterPresetFile(File dir, String fileName, boolean forWrite) {
        if (dir == null || fileName == null || fileName.trim().length() == 0) return null;
        File target = new File(dir, fileName);
        try {
            File canonicalDir = dir.getCanonicalFile();
            File canonicalTarget = target.getCanonicalFile();
            String dirPath = canonicalDir.getPath();
            String targetPath = canonicalTarget.getPath();
            if (!targetPath.equals(dirPath) && !targetPath.startsWith(dirPath + File.separator)) return null;
            if (!forWrite && !canonicalTarget.isFile()) return null;
            return canonicalTarget;
        } catch (IOException e) {
            return null;
        }
    }

    private FlashProjectLayout layoutForBinFolder(File binFolder) {
        return FlashProjectLayout.forDirectory(projectRootForBinFolder(binFolder).getPath());
    }

    private File projectRootForBinFolder(File binFolder) {
        if (binFolder == null) return new File(".");
        File parent = binFolder.getParentFile();
        String folderName = binFolder.getName();
        if (FlashProjectLayout.SETTINGS_DIR.equals(folderName)
                && parent != null
                && FlashProjectLayout.CONFIGURATION_DIR.equals(parent.getName())
                && parent.getParentFile() != null
                && FlashProjectLayout.FLASH_DIR.equals(parent.getParentFile().getName())
                && parent.getParentFile().getParentFile() != null) {
            return parent.getParentFile().getParentFile();
        }
        if (FlashProjectLayout.CONFIGURATION_DIR.equals(folderName)
                && parent != null
                && FlashProjectLayout.FLASH_DIR.equals(parent.getName())
                && parent.getParentFile() != null) {
            return parent.getParentFile();
        }
        return binFolder;
    }

    private String sanitizeCustomFilterPresetName(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        String sanitized = trimmed.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]+", "_").trim();
        while (sanitized.endsWith(".")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1).trim();
        }
        return sanitized.length() == 0 ? "Custom Filter" : sanitized;
    }

    private boolean isReservedFilterPresetName(String presetName) {
        if (presetName == null) return true;
        if ("Custom".equalsIgnoreCase(presetName.trim())) return true;
        for (int i = 0; i < NamedFilterLoader.FILTER_NAMES.length; i++) {
            if (NamedFilterLoader.FILTER_NAMES[i].equalsIgnoreCase(presetName.trim())) return true;
        }
        return false;
    }

    private String promptForCustomFilterPresetName(String channelLabel, String defaultName) {
        return promptForCustomFilterPresetName(null, channelLabel, defaultName);
    }

    private String promptForCustomFilterPresetName(Window owner, String channelLabel, String defaultName) {
        String value = sanitizeCustomFilterPresetName(defaultName);
        while (true) {
            PipelineDialog dialog = owner == null
                    ? new PipelineDialog("Save Custom Filter Preset")
                    : new PipelineDialog(owner, "Save Custom Filter Preset");
            installWizardCancelHook(dialog);
            dialog.addHeader("Save Filter Preset");
            dialog.addMessage("Name this custom filter so it appears in the Filter Preset list on later runs.");
            dialog.addStringField("Preset name", value, 28);
            if (!dialog.showDialog()) return null;
            String entered = sanitizeCustomFilterPresetName(dialog.getNextString());
            if (entered.length() == 0) {
                showOwnedMessage(owner, "Save Custom Filter Preset", "Enter a preset name.");
                value = channelLabel == null ? "Custom Filter" : channelLabel + " Filter";
                continue;
            }
            if (isReservedFilterPresetName(entered)) {
                showOwnedMessage(owner, "Save Custom Filter Preset",
                        "That name is already used by a built-in filter. Choose a different name.");
                value = entered;
                continue;
            }
            return entered;
        }
    }

    private static void showOwnedMessage(Window owner, String title, String message) {
        if (GraphicsEnvironment.isHeadless()) {
            IJ.log(title + ": " + message);
            return;
        }
        javax.swing.JOptionPane.showMessageDialog(owner, message, title,
                javax.swing.JOptionPane.INFORMATION_MESSAGE);
    }

    private String customFilterKey(File binFolder, int channelIndex) {
        String root = binFolder == null ? "" : binFolder.getAbsolutePath();
        return root + "#C" + (channelIndex + 1);
    }

    private void rememberHandledCustomFilter(File binFolder, int channelIndex, BinUserConfig cfg) {
        String key = customFilterKey(binFolder, channelIndex);
        customFilterPromptsHandled.add(key);
        if (cfg != null && channelIndex < cfg.filterPresets.size()) {
            String preset = cfg.filterPresets.get(channelIndex);
            if (preset != null && !"Custom".equals(preset)) {
                customFilterDemotions.put(key, preset);
            }
        }
    }

    private boolean shouldSkipHandledCustomFilterWrite(File binFolder, int channelIndex, String preset) {
        String key = customFilterKey(binFolder, channelIndex);
        if (!customFilterPromptsHandled.contains(key)) return false;
        if ("Custom".equals(preset)) return true;
        String demoted = customFilterDemotions.get(key);
        return demoted != null && demoted.equals(preset);
    }

    private void applyHandledCustomDemotions(File binFolder, BinUserConfig cfg) {
        if (cfg == null) return;
        for (int i = 0; i < cfg.filterPresets.size(); i++) {
            if (!"Custom".equals(cfg.filterPresets.get(i))) continue;
            String demoted = customFilterDemotions.get(customFilterKey(binFolder, i));
            if (demoted != null) {
                cfg.filterPresets.set(i, demoted);
            }
        }
    }

    protected Boolean showAnalysisScopeDialog(BinUserConfig cfg, boolean allowBack) {
        lastWasBack = false;
        PipelineDialog pd = setupAnalysisDialog("Set Up Configuration - Analysis Scope");
        installWizardCancelHook(pd);
        pd.setPrimaryButtonText(NextStepLabels.SETTINGS_MODE);
        if (allowBack) pd.enableBackButton();
        pd.addSetupHelpHeader("Analysis Scope", SetupHelpCatalog.ANALYSIS_SCOPE);
        addSavedStatusMessage(pd);
        pd.addMessage("Choose whether the analysis should use the full z-stack or a contiguous z-slice subset.");
        pd.addToggle(Z_SLICE_SCOPE_LABEL, cfg != null && cfg.usesZSliceSubset());
        pd.addHelpText("Default OFF analyses the full stack. When ON, every image series will be reviewed before the other QC stages.");
        if (!pd.showDialog()) {
            lastWasBack = pd.wasBackPressed();
            if (lastWasBack) {
                applyAnalysisScopeSelection(cfg, pd.getNextBoolean());
            }
            return null;
        }

        boolean enabled = pd.getNextBoolean();
        applyAnalysisScopeSelection(cfg, enabled);
        return enabled;
    }

    private void applyAnalysisScopeSelection(BinUserConfig cfg, boolean enabled) {
        if (cfg != null) {
            cfg.zSliceMode = enabled
                    ? (cfg.zSliceMode != null && cfg.zSliceMode.usesSubset() ? cfg.zSliceMode : ZSliceMode.PER_IMAGE)
                    : ZSliceMode.FULL;
            if (!enabled) {
                cfg.zSliceSelections.clear();
            }
        }
    }

    static QcOpenPreparation resolveQcLifFile(String directory) {
        if (!gateBioFormatsFeature("Set Up Configuration quality-check image loading")) {
            return QcOpenPreparation.cancel("");
        }
        try {
            QcSourceReference source = resolveQcSourceReference(directory);
            return QcOpenPreparation.ready(
                    source.sourceFile,
                    Collections.<Integer>emptyList(),
                    source.sourceLabel);
        } catch (IllegalArgumentException e) {
            return QcOpenPreparation.cancel("Cannot run quality check: " + e.getMessage());
        }
    }

    private static QcSourceReference resolveQcSourceReference(String directory) {
        File dir = new File(directory);
        boolean hasProjectManifest = ImageSourceDispatcher.hasProjectManifest(directory);
        List<File> projectContainers = ImageSourceDispatcher.projectContainerFiles(directory);
        if (!projectContainers.isEmpty()) {
            if (projectContainers.size() == 1) {
                File source = projectContainers.get(0);
                return QcSourceReference.of(source, source.getName());
            }
            throw new IllegalArgumentException(
                    "Project contains multiple container files. "
                            + "Set Up Configuration quality check currently supports one container at a time.");
        }
        List<File> projectTiffs = ImageSourceDispatcher.projectTiffFiles(directory);
        if (!projectTiffs.isEmpty()) {
            return QcSourceReference.of(projectTiffs.get(0), sourceLabelForTiffs(dir, projectTiffs));
        }
        if (hasProjectManifest) {
            throw new IllegalArgumentException(
                    "Project does not contain an included image source for quality check.");
        }

        ImageSourceDispatcher.SourceMode mode = ImageSourceDispatcher.detectMode(directory);
        if (mode == ImageSourceDispatcher.SourceMode.CONTAINER) {
            File source = ImageSourceDispatcher.selectContainer(dir);
            return QcSourceReference.of(source, source.getName());
        }
        if (mode == ImageSourceDispatcher.SourceMode.TIFF_INPUT_SUBFOLDER) {
            List<File> tiffs = ImageSourceDispatcher.listTiffs(new File(dir, "input"));
            if (!tiffs.isEmpty()) {
                return QcSourceReference.of(tiffs.get(0), "input");
            }
        }
        if (mode == ImageSourceDispatcher.SourceMode.TIFF_LOOSE) {
            List<File> tiffs = ImageSourceDispatcher.listTiffs(dir);
            if (!tiffs.isEmpty()) {
                return QcSourceReference.of(tiffs.get(0), sourceLabelForTiffs(dir, tiffs));
            }
        }
        throw new IllegalArgumentException(
                "No compatible image source found in: " + dir.getAbsolutePath());
    }

    private static File resolveQcContainerFile(String directory) {
        QcSourceReference source = resolveQcSourceReference(directory);
        if (source.sourceFile != null && isContainerSourceName(source.sourceFile.getName())) {
            return source.sourceFile;
        }
        throw new IllegalArgumentException(
                "Resolved source is not a container file: "
                        + (source.sourceFile == null ? "(none)" : source.sourceFile.getName()));
    }

    private static boolean isContainerSourceName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : ImageSourceDispatcher.CONTAINER_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    private static String sourceLabelForTiffs(File dir, List<File> tiffs) {
        if (dir != null && hasText(dir.getName())) {
            return dir.getName();
        }
        if (tiffs != null && !tiffs.isEmpty() && tiffs.get(0) != null) {
            File parent = tiffs.get(0).getParentFile();
            if (parent != null && hasText(parent.getName())) {
                return parent.getName();
            }
            return tiffs.get(0).getName();
        }
        return "TIFF input";
    }

    private static String sourceDisplayLabel(DeferredImageSupplier supplier, String fallback) {
        if (supplier != null && hasText(supplier.getContainerDisplayName())) {
            return supplier.getContainerDisplayName();
        }
        return hasText(fallback) ? fallback.trim() : "image source";
    }

    static QcOpenPreparation prepareQcImageOpen(String directory,
                                                List<Integer> selectedSeriesIndexes,
                                                boolean selectionCanceled) {
        QcOpenPreparation sourceResolution = resolveQcLifFile(directory);
        if (!sourceResolution.isReady()) return sourceResolution;
        return prepareQcImageOpen(sourceResolution.sourceFile, selectedSeriesIndexes,
                selectionCanceled, sourceResolution.sourceLabel);
    }

    static QcOpenPreparation prepareQcImageOpen(File lifFile,
                                                List<Integer> selectedSeriesIndexes,
                                                boolean selectionCanceled) {
        return prepareQcImageOpen(lifFile, selectedSeriesIndexes, selectionCanceled,
                lifFile == null ? "" : lifFile.getName());
    }

    private static QcOpenPreparation prepareQcImageOpen(File sourceFile,
                                                        List<Integer> selectedSeriesIndexes,
                                                        boolean selectionCanceled,
                                                        String sourceLabel) {
        if (!gateBioFormatsFeature("Set Up Configuration quality-check image loading")) {
            return QcOpenPreparation.cancel("");
        }
        if (selectionCanceled) {
            return QcOpenPreparation.cancel("");
        }

        List<Integer> orderedIndexes = new ArrayList<Integer>();
        if (selectedSeriesIndexes != null) {
            LinkedHashSet<Integer> unique = new LinkedHashSet<Integer>();
            for (Integer selected : selectedSeriesIndexes) {
                if (selected != null) unique.add(selected);
            }
            orderedIndexes.addAll(unique);
        }
        if (orderedIndexes.isEmpty()) {
            return QcOpenPreparation.skip(
                    "No images were selected for quality check.\n"
                            + "Settings will be saved without visual verification.");
        }
        return QcOpenPreparation.ready(sourceFile, orderedIndexes, sourceLabel);
    }

    protected QcImageOpenResult openImagesForQC(String directory, File binFolder,
                                                BinUserConfig cfg, boolean[][] customSettings) {
        QcOpenPreparation sourceResolution = resolveQcLifFile(directory);
        if (!sourceResolution.isReady()) {
            return QcImageOpenResult.fromPreparation(sourceResolution, "");
        }

        File sourceFile = sourceResolution.sourceFile;
        String sourceLabel = sourceResolution.sourceLabel;
        boolean projectBackedQc = ImageSourceDispatcher.hasProjectManifest(directory);

        DeferredImageSupplier qcSupplier;
        List<SeriesMeta> qcSeriesMetas;
        try {
            qcSupplier = ImageSourceDispatcher.createSupplier(directory);
            sourceLabel = sourceDisplayLabel(qcSupplier, sourceLabel);
            qcSeriesMetas = filterSelectableSeries(
                    sourceLabel, ImageSourceDispatcher.readAllMetadata(directory));
        } catch (Exception e) {
            IJ.log("Warning: could not read series count: " + e.getMessage());
            if (projectBackedQc || sourceFile == null
                    || !isContainerSourceName(sourceFile.getName())) {
                return QcImageOpenResult.cancel(
                        "Cannot run quality check: source metadata could not be read: "
                                + e.getMessage());
            }
            try {
                qcSupplier = new DeferredImageSupplier(sourceFile);
                sourceLabel = sourceDisplayLabel(qcSupplier, sourceLabel);
                qcSeriesMetas = filterSelectableSeries(
                        sourceLabel, LifIO.readAllSeriesMetadata(sourceFile));
            } catch (Exception fallback) {
                IJ.log("Warning: direct container metadata fallback also failed: " + fallback.getMessage());
                qcSupplier = null;
                qcSeriesMetas = new ArrayList<SeriesMeta>();
            }
        }
        int totalSeries = qcSeriesMetas.size();
        IJ.log("Set Up QC image selection: " + sourceLabel
                + " has " + totalSeries + " selectable image series.");

        PipelineDialog pd = new PipelineDialog("Quality Check - Image Selection");
        installWizardCancelHook(pd);
        pd.addSetupHelpHeader("Select Images for Quality Check", SetupHelpCatalog.QC_IMAGE_SELECTION);
        pd.addMessage("Source: " + sourceLabel + "  (" + totalSeries + " image series found)");
        JComboBox<String> modeChoice = pd.addChoice("Selection mode",
                new String[]{QC_SELECTION_MODE_MANUAL, QC_SELECTION_MODE_RANDOM,
                        QC_SELECTION_MODE_MIN_MAX_OVERALL, QC_SELECTION_MODE_MIN_MAX_CONDITION},
                QC_SELECTION_MODE_RANDOM);
        javax.swing.JTextField randomCountField =
                pd.addNumericField("Number of random images", Math.min(3, totalSeries), 0);
        Container randomCountRow = randomCountField.getParent();
        ToggleSwitch recomputeToggle = pd.addToggle("Recompute cached min/max selection", false);
        Container recomputeRow = recomputeToggle.getParent();
        JLabel minMaxHelp = pd.addHelpText("Only used for min/max modes. Overall opens one global MAX and one global MIN image; per-condition opens MAX then MIN for each condition.");

        Runnable updateSelectionFields = new Runnable() {
            @Override
            public void run() {
                String selectedMode = (String) modeChoice.getSelectedItem();
                boolean isRandom = QC_SELECTION_MODE_RANDOM.equals(selectedMode);
                boolean isMinMax = isMinMaxSelectionMode(selectedMode);

                setComponentTreeEnabled(randomCountRow, isRandom);
                randomCountField.setEditable(isRandom);
                setComponentTreeEnabled(recomputeRow, isMinMax);
                setComponentTreeEnabled(minMaxHelp, isMinMax);

                // Name the screen this mode actually leads to next.
                String nextLabel = NextStepLabels.QC_STAGES;
                if (QC_SELECTION_MODE_MANUAL.equals(selectedMode)) {
                    nextLabel = NextStepLabels.QC_PICK_IMAGES;
                } else if (isMinMax && requiresMinMaxConditionMetadataReview(selectedMode)) {
                    nextLabel = NextStepLabels.QC_METADATA;
                }
                pd.setPrimaryButtonText(nextLabel);
            }
        };
        modeChoice.addItemListener(new java.awt.event.ItemListener() {
            @Override
            public void itemStateChanged(java.awt.event.ItemEvent e) {
                if (e.getStateChange() == java.awt.event.ItemEvent.SELECTED) {
                    updateSelectionFields.run();
                }
            }
        });
        updateSelectionFields.run();

        if (!pd.showDialog()) return QcImageOpenResult.cancel("");

        String mode = pd.getNextChoice();
        int randomCount = (int) pd.getNextNumber();
        boolean recomputeMinMax = pd.getNextBoolean();
        IJ.log("Set Up QC image selection mode: " + mode
                + (QC_SELECTION_MODE_RANDOM.equals(mode)
                ? " (" + randomCount + " random image"
                        + (randomCount == 1 ? "" : "s") + " requested)"
                : ""));

        try {
            List<Integer> selectedSeriesIndexes;
            Map<Integer, QcMinMaxPerConditionSelector.SelectedSeries> minMaxSelectionBySeries =
                    new LinkedHashMap<Integer, QcMinMaxPerConditionSelector.SelectedSeries>();
            Map<Integer, Map<Integer, QcMinMaxPerConditionSelector.SelectedSeries>>
                    minMaxSelectionBySeriesAndChannel =
                    new LinkedHashMap<Integer, Map<Integer, QcMinMaxPerConditionSelector.SelectedSeries>>();
            Map<Integer, Map<Integer, Integer>> minMaxOrderBySeriesAndChannel =
                    new LinkedHashMap<Integer, Map<Integer, Integer>>();
            boolean selectionCanceled = false;
            String resultMessage = "";
            if (QC_SELECTION_MODE_MANUAL.equals(mode)) {
                selectedSeriesIndexes = showManualQcSeriesSelection(sourceLabel, qcSeriesMetas);
                selectionCanceled = selectedSeriesIndexes == null;
            } else if (isMinMaxSelectionMode(mode)) {
                List<QcSelectionChannel> qcChannels = buildQcSelectionChannels(cfg, customSettings);
                if (qcChannels.isEmpty()) {
                    return QcImageOpenResult.cancel(
                            "Cannot run quality check: No QC-selected channels were found for min/max assessment.");
                }
                IJ.log("Set Up QC min/max selection: assessing " + qcChannels.size()
                        + " channel" + (qcChannels.size() == 1 ? "" : "s")
                        + " with " + totalSeries + " candidate image series.");
                Map<Integer, QcMinMaxPerConditionSelector.MetadataAssignment> reviewedMetadata =
                        Collections.<Integer, QcMinMaxPerConditionSelector.MetadataAssignment>emptyMap();
                boolean metadataChanged = false;
                if (requiresMinMaxConditionMetadataReview(mode)) {
                    MetadataReviewResult metadataReview =
                            showMinMaxConditionMetadataReview(directory, sourceLabel, qcSeriesMetas);
                    if (metadataReview == null) {
                        return QcImageOpenResult.cancel("Min/max metadata review cancelled.");
                    }
                    reviewedMetadata = metadataReview.assignmentsBySeries;
                    metadataChanged = metadataReview.changed;
                }
                int selectorThreads = Math.max(parallelThreads, loaderThreads);
                if (selectorThreads <= 1 && loaderPercent > 0) {
                    selectorThreads = Math.max(2, parallelThreads);
                }
                QcMinMaxPerConditionSelector.SelectionResult selection;
                if (QC_SELECTION_MODE_MIN_MAX_OVERALL.equals(mode)) {
                    selection = QcMinMaxPerConditionSelector.selectMinMaxOverall(
                            directory, binFolder, sourceFile, qcSupplier, qcSeriesMetas,
                            qcChannels, cfg.getZSliceConfig(),
                            recomputeMinMax || metadataChanged,
                            selectorThreads,
                            reviewedMetadata);
                } else {
                    selection = QcMinMaxPerConditionSelector.selectMinMaxPerCondition(
                            directory, binFolder, sourceFile, qcSupplier, qcSeriesMetas,
                            qcChannels, cfg.getZSliceConfig(),
                            recomputeMinMax || metadataChanged,
                            selectorThreads,
                            reviewedMetadata);
                }
                resultMessage = selection.message;
                selectedSeriesIndexes = selection.selectedSeriesIndexes;
                IJ.log("Set Up QC min/max selection complete: "
                        + selectedSeriesIndexes.size() + " selected image"
                        + (selectedSeriesIndexes.size() == 1 ? "" : "s")
                        + " (" + summarizeSeriesIndexes(selectedSeriesIndexes) + ").");
                minMaxSelectionBySeries = selectedSeriesByIndex(selection.selectedSeries);
                minMaxSelectionBySeriesAndChannel =
                        selectedSeriesBySeriesAndChannel(selection.selectedSeriesByChannelNumber);
                minMaxOrderBySeriesAndChannel =
                        selectedSeriesOrderBySeriesAndChannel(selection.selectedSeriesByChannelNumber);
            } else {
                selectedSeriesIndexes = chooseRandomSeriesIndexes(qcSeriesMetas, randomCount);
                IJ.log("Set Up QC random selection complete: "
                        + selectedSeriesIndexes.size() + " selected image"
                        + (selectedSeriesIndexes.size() == 1 ? "" : "s")
                        + " (" + summarizeSeriesIndexes(selectedSeriesIndexes) + ").");
            }

            if (!selectionCanceled
                    && (selectedSeriesIndexes == null || selectedSeriesIndexes.isEmpty())
                    && hasText(resultMessage)) {
                return QcImageOpenResult.skip(resultMessage);
            }

            QcOpenPreparation preparation =
                    prepareQcImageOpen(sourceFile, selectedSeriesIndexes,
                            selectionCanceled, sourceLabel);
            if (!preparation.isReady()) {
                return QcImageOpenResult.fromPreparation(preparation, resultMessage);
            }

            IJ.log("Set Up QC opening " + preparation.selectedSeriesIndexes.size()
                    + " selected image series from " + preparation.sourceLabel
                    + ": " + summarizeSeriesIndexes(preparation.selectedSeriesIndexes));
            List<QcImageSelection> images =
                    openQcSelections(qcSupplier, preparation.sourceFile,
                            preparation.selectedSeriesIndexes, cfg,
                            minMaxSelectionBySeries,
                            minMaxSelectionBySeriesAndChannel,
                            minMaxOrderBySeriesAndChannel);
            if (images.size() != preparation.selectedSeriesIndexes.size()) {
                cleanupImages(images);
                return QcImageOpenResult.cancel(
                        "Cannot run quality check: Failed to open one or more selected image series from "
                                + preparation.sourceLabel + ".");
            }
            IJ.log("Set Up QC selected images ready: " + images.size()
                    + " image" + (images.size() == 1 ? "" : "s") + " opened.");
            return QcImageOpenResult.ready(images, resultMessage);
        } catch (Exception e) {
            return QcImageOpenResult.cancel("Cannot run quality check: " + e.getMessage());
        }
    }

    private MetadataReviewResult showMinMaxConditionMetadataReview(
            String directory,
            String sourceLabel,
            List<SeriesMeta> metas) throws Exception {
        List<MetadataReviewRow> rows = buildMetadataReviewRows(directory, sourceLabel, metas);
        if (rows.isEmpty()) {
            return new MetadataReviewResult(
                    new LinkedHashMap<Integer, QcMinMaxPerConditionSelector.MetadataAssignment>(),
                    false);
        }

        DefaultTableModel model = new DefaultTableModel(
                metadataReviewTableData(rows),
                new String[]{"Image / Series", "Animal", "Condition"}) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 1 || column == 2;
            }
        };
        JTable table = new JTable(model);
        table.setRowHeight(24);
        table.getColumnModel().getColumn(0).setPreferredWidth(320);
        table.getColumnModel().getColumn(1).setPreferredWidth(160);
        table.getColumnModel().getColumn(2).setPreferredWidth(160);
        table.setDefaultRenderer(Object.class, new MetadataReviewRenderer());

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(680, Math.min(420, 60 + rows.size() * 25)));

        PipelineDialog dialog = new PipelineDialog(
                "Min/Max QC Metadata Review", PipelineDialog.Phase.SETUP);
        installWizardCancelHook(dialog);
        dialog.setPrimaryButtonText(NextStepLabels.QC_STAGES);
        dialog.addHeader("Animal and Condition Review");
        dialog.addMessage("Min/max QC uses these labels to describe selected images. "
                + "Correct any guessed animal names or condition groups before the scan starts.");
        dialog.addComponent(scroll);
        if (!dialog.showDialog()) {
            return null;
        }

        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }

        boolean changed = false;
        LinkedHashMap<Integer, QcMinMaxPerConditionSelector.MetadataAssignment> assignmentsBySeries =
                new LinkedHashMap<Integer, QcMinMaxPerConditionSelector.MetadataAssignment>();
        for (int i = 0; i < rows.size(); i++) {
            MetadataReviewRow row = rows.get(i);
            row.animalName = stringValue(model.getValueAt(i, 1));
            row.conditionName = stringValue(model.getValueAt(i, 2));
            if (!sameText(row.originalAnimalName, row.animalName)
                    || !sameText(row.originalConditionName, row.conditionName)) {
                changed = true;
            }
            assignmentsBySeries.put(Integer.valueOf(row.seriesIndex),
                    new QcMinMaxPerConditionSelector.MetadataAssignment(
                            row.animalName, row.conditionName));
        }

        saveReviewedMetadata(directory, rows);
        return new MetadataReviewResult(assignmentsBySeries, changed);
    }

    private List<MetadataReviewRow> buildMetadataReviewRows(
            String directory,
            String sourceLabel,
            List<SeriesMeta> metas) throws Exception {
        List<MetadataReviewRow> rows = new ArrayList<MetadataReviewRow>();
        if (metas == null || metas.isEmpty()) return rows;

        OrientationImageIdentity.SourceContext source =
                OrientationImageIdentity.SourceContext.resolve(directory);
        LinkedHashMap<String, OrientationManifestRow> savedByKey =
                OrientationManifestIO.readByImageKeyIfExists(directory);
        Map<Integer, OrientationManifestRow> savedBySeries =
                savedOrientationRowsBySeries(directory, source);
        LinkedHashSet<String> animals = new LinkedHashSet<String>();

        for (SeriesMeta meta : metas) {
            if (meta == null) continue;
            String originalName = metadataOriginalName(sourceLabel, meta);
            OrientationImageIdentity identity = source.identityFor(meta.index, originalName);
            OrientationManifestRow saved = savedByKey.get(identity.imageKey);
            if (saved == null) {
                saved = savedBySeries.get(Integer.valueOf(meta.index + 1));
            }

            ResolvedImageMetadata resolved =
                    ImageOrientationResolver.resolve(directory, originalName, meta.index + 1);
            NameParts parsed = ImageNameParser.parse(originalName);
            String animal = firstNonBlank(
                    saved == null ? "" : saved.animalName,
                    resolved == null ? "" : resolved.animalName,
                    ConditionManifestIO.extractAnimalName(originalName),
                    "Series " + (meta.index + 1));

            MetadataReviewRow row = new MetadataReviewRow();
            row.seriesIndex = meta.index;
            row.seriesLabel = seriesDisplayLabel(sourceLabel, meta);
            row.imageKey = identity.imageKey;
            row.sourceFile = identity.sourceFile;
            row.oneBasedSeriesIndex = identity.seriesIndex;
            row.originalName = identity.originalName;
            row.displayName = identity.displayName;
            row.animalName = animal;
            row.originalAnimalName = animal;
            row.hemisphere = saved == null
                    ? OrientationManifestRow.Hemisphere.fromCsv(firstNonBlank(
                            resolved == null ? "" : resolved.hemisphere,
                            parsed == null ? "" : parsed.hemisphere))
                    : saved.hemisphere;
            row.region = firstNonBlank(
                    saved == null ? "" : saved.region,
                    resolved == null ? "" : resolved.region,
                    parsed == null ? "" : parsed.region);
            row.rotateDegrees = saved == null
                    ? (resolved == null ? OrientationManifestRow.RotationDegrees.DEG_0
                            : resolved.rotateDegrees)
                    : saved.rotateDegrees;
            row.flipHorizontal = saved == null
                    ? resolved != null && resolved.flipHorizontal
                    : saved.flipHorizontal;
            row.flipVertical = saved == null
                    ? resolved != null && resolved.flipVertical
                    : saved.flipVertical;
            row.viewPolicy = saved == null
                    ? (resolved == null ? OrientationManifestRow.ViewPolicy.MANUAL_ONLY
                            : resolved.viewPolicy)
                    : saved.viewPolicy;
            row.existingImageKey = saved == null ? "" : saved.imageKey;
            row.notes = firstNonBlank(saved == null ? "" : saved.notes,
                    "Reviewed during Set Up Configuration min/max QC");
            rows.add(row);
            if (!animal.trim().isEmpty()) {
                animals.add(animal);
            }
        }

        Map<String, String> conditions = ConditionManifestIO.resolveAssignments(directory, animals);
        for (MetadataReviewRow row : rows) {
            String condition = firstNonBlank(
                    conditions.get(row.animalName),
                    ConditionNameParser.detectCondition(row.animalName),
                    row.animalName);
            row.conditionName = condition;
            row.originalConditionName = condition;
        }
        return rows;
    }

    private Map<Integer, OrientationManifestRow> savedOrientationRowsBySeries(
            String directory,
            OrientationImageIdentity.SourceContext source) {
        Map<Integer, OrientationManifestRow> bySeries =
                new LinkedHashMap<Integer, OrientationManifestRow>();
        for (OrientationManifestRow row : OrientationManifestIO.readIfExists(directory)) {
            if (row == null) continue;
            String expectedSource = source == null
                    ? ""
                    : source.sourceFileFor(row.seriesIndex - 1, row.originalName);
            if (!expectedSource.isEmpty() && !expectedSource.equals(row.sourceFile)) continue;
            bySeries.put(Integer.valueOf(row.seriesIndex), row);
        }
        return bySeries;
    }

    private Object[][] metadataReviewTableData(List<MetadataReviewRow> rows) {
        Object[][] data = new Object[rows.size()][3];
        for (int i = 0; i < rows.size(); i++) {
            MetadataReviewRow row = rows.get(i);
            data[i][0] = row.seriesLabel;
            data[i][1] = row.animalName;
            data[i][2] = row.conditionName;
        }
        return data;
    }

    private void saveReviewedMetadata(String directory, List<MetadataReviewRow> rows)
            throws IOException {
        LinkedHashMap<String, String> existingConditionAssignments =
                ConditionManifestIO.readAssignmentsIfExists(directory);
        LinkedHashMap<String, String> conditionAssignments =
                new LinkedHashMap<String, String>(existingConditionAssignments);
        for (MetadataReviewRow row : rows) {
            String animal = stringValue(row.animalName);
            String condition = stringValue(row.conditionName);
            if (!animal.isEmpty() && !condition.isEmpty()) {
                conditionAssignments.put(animal, condition);
            }
        }
        if (!conditionAssignments.equals(existingConditionAssignments)) {
            ConditionManifestIO.saveAssignments(directory, conditionAssignments);
        }

        List<OrientationManifestRow> existingRows =
                OrientationManifestIO.readIfExists(directory);
        LinkedHashMap<String, OrientationManifestRow> byKey =
                OrientationManifestIO.indexByImageKey(existingRows);
        boolean orientationChanged = false;
        for (MetadataReviewRow row : rows) {
            String animal = stringValue(row.animalName);
            if (animal.isEmpty()) continue;
            if (!row.existingImageKey.isEmpty()
                    && !row.existingImageKey.equals(row.imageKey)) {
                if (byKey.remove(row.existingImageKey) != null) {
                    orientationChanged = true;
                }
            }
            OrientationManifestRow next = new OrientationManifestRow(
                    row.imageKey,
                    row.sourceFile,
                    row.oneBasedSeriesIndex,
                    row.originalName,
                    row.displayName,
                    animal,
                    row.hemisphere,
                    row.region,
                    row.rotateDegrees,
                    row.flipHorizontal,
                    row.flipVertical,
                    row.viewPolicy,
                    OrientationManifestRow.DecisionSource.MANUAL,
                    OrientationManifestRow.ConfirmationState.YES,
                    "Reviewed during Set Up Configuration min/max QC");
            OrientationManifestRow previous = byKey.get(row.imageKey);
            if (!sameOrientationRow(previous, next)) {
                byKey.put(row.imageKey, next);
                orientationChanged = true;
            }
        }
        if (orientationChanged) {
            OrientationManifestIO.saveRows(
                    directory,
                    new ArrayList<OrientationManifestRow>(byKey.values()));
        }
    }

    private static String metadataOriginalName(File lifFile, SeriesMeta meta) {
        return metadataOriginalName(lifFile == null ? "" : stringValue(lifFile.getName()), meta);
    }

    private static String metadataOriginalName(String sourceLabel, SeriesMeta meta) {
        if (meta == null) return "";
        if (meta.name != null && !meta.name.trim().isEmpty()) {
            String name = meta.name.trim();
            if (name.indexOf(" - ") >= 0) {
                return name;
            }
            String source = stringValue(sourceLabel);
            return source.isEmpty() ? name : source + " - " + name;
        }
        return seriesDisplayLabelText(sourceLabel, meta);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            String trimmed = stringValue(value);
            if (!trimmed.isEmpty()) return trimmed;
        }
        return "";
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static boolean sameText(String left, String right) {
        return stringValue(left).equals(stringValue(right));
    }

    private static boolean sameOrientationRow(OrientationManifestRow left,
                                              OrientationManifestRow right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        return sameText(left.imageKey, right.imageKey)
                && sameText(left.sourceFile, right.sourceFile)
                && left.seriesIndex == right.seriesIndex
                && sameText(left.originalName, right.originalName)
                && sameText(left.displayName, right.displayName)
                && sameText(left.animalName, right.animalName)
                && left.hemisphere == right.hemisphere
                && sameText(left.region, right.region)
                && left.rotateDegrees == right.rotateDegrees
                && left.flipHorizontal == right.flipHorizontal
                && left.flipVertical == right.flipVertical
                && left.viewPolicy == right.viewPolicy
                && left.confirmed == right.confirmed;
    }

    private static final class MetadataReviewResult {
        final LinkedHashMap<Integer, QcMinMaxPerConditionSelector.MetadataAssignment> assignmentsBySeries;
        final boolean changed;

        MetadataReviewResult(
                LinkedHashMap<Integer, QcMinMaxPerConditionSelector.MetadataAssignment> assignmentsBySeries,
                boolean changed) {
            this.assignmentsBySeries = assignmentsBySeries == null
                    ? new LinkedHashMap<Integer, QcMinMaxPerConditionSelector.MetadataAssignment>()
                    : assignmentsBySeries;
            this.changed = changed;
        }
    }

    private static final class MetadataReviewRow {
        int seriesIndex;
        String seriesLabel;
        String imageKey;
        String sourceFile;
        int oneBasedSeriesIndex;
        String originalName;
        String displayName;
        String existingImageKey;
        String originalAnimalName;
        String originalConditionName;
        String animalName;
        String conditionName;
        OrientationManifestRow.Hemisphere hemisphere;
        String region;
        OrientationManifestRow.RotationDegrees rotateDegrees;
        boolean flipHorizontal;
        boolean flipVertical;
        OrientationManifestRow.ViewPolicy viewPolicy;
        String notes;
    }

    private static final class MetadataReviewRenderer extends DefaultTableCellRenderer {
        private final Color warning = new Color(255, 230, 230);

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            Component c = super.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column);
            Object animal = table.getModel().getValueAt(row, 1);
            Object condition = table.getModel().getValueAt(row, 2);
            boolean incomplete = stringValue(animal).isEmpty()
                    || stringValue(condition).isEmpty();
            if (!isSelected) {
                c.setBackground(incomplete ? warning : Color.WHITE);
            }
            return c;
        }
    }

    private List<QcImageSelection> openQcSelections(File lifFile, List<Integer> selectedSeriesIndexes,
                                                    BinUserConfig cfg) throws Exception {
        return openQcSelections(lifFile, selectedSeriesIndexes, cfg,
                Collections.<Integer, QcMinMaxPerConditionSelector.SelectedSeries>emptyMap(),
                Collections.<Integer, Map<Integer, Integer>>emptyMap());
    }

    private List<QcImageSelection> openQcSelections(
            File lifFile,
            List<Integer> selectedSeriesIndexes,
            BinUserConfig cfg,
            Map<Integer, QcMinMaxPerConditionSelector.SelectedSeries> minMaxSelectionBySeries) throws Exception {
        return openQcSelections(lifFile, selectedSeriesIndexes, cfg,
                minMaxSelectionBySeries,
                Collections.<Integer, Map<Integer, Integer>>emptyMap());
    }

    private List<QcImageSelection> openQcSelections(
            File lifFile,
            List<Integer> selectedSeriesIndexes,
            BinUserConfig cfg,
            Map<Integer, QcMinMaxPerConditionSelector.SelectedSeries> minMaxSelectionBySeries,
            Map<Integer, Map<Integer, Integer>> minMaxOrderBySeriesAndChannel) throws Exception {
        return openQcSelections(new DeferredImageSupplier(lifFile), lifFile,
                selectedSeriesIndexes, cfg, minMaxSelectionBySeries,
                Collections.<Integer, Map<Integer, QcMinMaxPerConditionSelector.SelectedSeries>>emptyMap(),
                minMaxOrderBySeriesAndChannel);
    }

    private List<QcImageSelection> openQcSelections(
            DeferredImageSupplier supplier,
            File lifFile,
            List<Integer> selectedSeriesIndexes,
            BinUserConfig cfg,
            Map<Integer, QcMinMaxPerConditionSelector.SelectedSeries> minMaxSelectionBySeries,
            Map<Integer, Map<Integer, QcMinMaxPerConditionSelector.SelectedSeries>> minMaxSelectionBySeriesAndChannel,
            Map<Integer, Map<Integer, Integer>> minMaxOrderBySeriesAndChannel) throws Exception {
        List<Integer> orderedIndexes = new ArrayList<Integer>();
        if (selectedSeriesIndexes != null) {
            LinkedHashSet<Integer> unique = new LinkedHashSet<Integer>();
            for (Integer selected : selectedSeriesIndexes) {
                if (selected != null) unique.add(selected);
            }
            orderedIndexes.addAll(unique);
        }
        if (orderedIndexes.isEmpty()) return new ArrayList<QcImageSelection>();

        List<QcImageSelection> selections = new ArrayList<QcImageSelection>();
        if (supplier == null) {
            supplier = new DeferredImageSupplier(lifFile);
        }
        for (int i = 0; i < orderedIndexes.size(); i++) {
            int seriesIndex = orderedIndexes.get(i);
            long started = System.currentTimeMillis();
            IJ.log("Set Up QC opening image " + (i + 1) + "/" + orderedIndexes.size()
                    + ": Series " + (seriesIndex + 1));
            ImagePlus imp = supplier.openSeries(seriesIndex);
            if (imp == null) {
                IJ.log("Set Up QC warning: Series " + (seriesIndex + 1)
                        + " returned no image.");
                continue;
            }
            String warning = qcZSliceWarning(cfg, seriesIndex, imp);
            if (hasText(warning)) {
                IJ.log("Set Up QC " + warning);
            }
            ImagePlus finalImp = applyQcZSliceSubset(cfg, seriesIndex, imp);
            if (finalImp != imp) {
                IJ.log("Set Up QC applied saved z-slice subset to Series "
                        + (seriesIndex + 1) + ": " + imageShape(finalImp));
            }
            QcMinMaxPerConditionSelector.SelectedSeries selected =
                    minMaxSelectionBySeries == null
                            ? null
                            : minMaxSelectionBySeries.get(Integer.valueOf(seriesIndex));
            Map<Integer, Integer> orderByChannel =
                    minMaxOrderBySeriesAndChannel == null
                            ? null
                            : minMaxOrderBySeriesAndChannel.get(Integer.valueOf(seriesIndex));
            Map<Integer, QcMinMaxPerConditionSelector.SelectedSeries> selectedByChannel =
                    minMaxSelectionBySeriesAndChannel == null
                            ? null
                            : minMaxSelectionBySeriesAndChannel.get(Integer.valueOf(seriesIndex));
            selections.add(new QcImageSelection(
                    seriesIndex,
                    finalImp == null ? "" : finalImp.getTitle(),
                    finalImp,
                    minMaxReviewLabel(selected),
                    warning,
                    orderByChannel,
                    minMaxReviewLabelsByChannel(selectedByChannel)));
            IJ.log("Set Up QC image ready " + (i + 1) + "/" + orderedIndexes.size()
                    + ": Series " + (seriesIndex + 1) + " "
                    + imageShape(finalImp) + " in "
                    + (System.currentTimeMillis() - started) + " ms.");
        }
        return selections;
    }

    private List<QcImageSelection> openQcSelections(
            DeferredImageSupplier supplier,
            File lifFile,
            List<Integer> selectedSeriesIndexes,
            BinUserConfig cfg,
            Map<Integer, QcMinMaxPerConditionSelector.SelectedSeries> minMaxSelectionBySeries) throws Exception {
        return openQcSelections(supplier, lifFile, selectedSeriesIndexes, cfg,
                minMaxSelectionBySeries,
                Collections.<Integer, Map<Integer, QcMinMaxPerConditionSelector.SelectedSeries>>emptyMap(),
                Collections.<Integer, Map<Integer, Integer>>emptyMap());
    }

    private ImagePlus applyQcZSliceSubset(BinUserConfig cfg, int seriesIndex, ImagePlus imp) {
        if (imp == null || cfg == null || !cfg.usesZSliceSubset()) return imp;
        ImagePlus subset = ZSliceOps.applyConfiguredRange(imp, toBinConfig(cfg), seriesIndex, "Set Up Configuration QC");
        if (subset != null && subset != imp) {
            closeImageQuietly(imp);
        }
        return subset;
    }

    private String qcZSliceWarning(BinUserConfig cfg, int seriesIndex, ImagePlus imp) {
        if (imp == null || cfg == null || !cfg.usesZSliceSubset()) return "";
        ZSliceSelection selection = cfg.zSliceSelections.get(Integer.valueOf(seriesIndex));
        int actualSlices = Math.max(1, imp.getNSlices());
        String label = "Z-slice warning: ";
        if (selection == null || selection.range == null) {
            return label + "no saved range for this image; showing the full stack.";
        }
        if (!selection.range.isValidFor(actualSlices)) {
            return label + "saved range " + selection.range.toToken()
                    + " does not fit this image (" + actualSlices
                    + " slice" + (actualSlices == 1 ? "" : "s") + "); showing the full stack.";
        }
        return "";
    }

    private List<Integer> chooseRandomSeriesIndexes(List<SeriesMeta> selectableMetas, int count) {
        List<Integer> indices = new ArrayList<Integer>();
        if (selectableMetas != null) {
            for (SeriesMeta meta : selectableMetas) {
                if (meta != null) indices.add(meta.index);
            }
        }
        Collections.shuffle(indices);
        int toKeep = Math.min(Math.max(0, count), indices.size());
        List<Integer> selected = new ArrayList<Integer>(indices.subList(0, toKeep));
        Collections.sort(selected);
        return selected;
    }

    private List<Integer> showManualQcSeriesSelection(String sourceLabel, List<SeriesMeta> metas) {
        PipelineDialog pd = new PipelineDialog("Quality Check - Manual Selection");
        installWizardCancelHook(pd);
        pd.setPrimaryButtonText(NextStepLabels.QC_STAGES);
        pd.addHeader("Manual QC Image Selection");
        pd.addMessage("Select the image series to open for QC.");
        ToggleSwitch[] toggles = new ToggleSwitch[metas.size()];
        for (int i = 0; i < metas.size(); i++) {
            SeriesMeta meta = metas.get(i);
            String name = seriesDisplayLabel(sourceLabel, meta);
            toggles[i] = pd.addToggle((i + 1) + ". " + name + " (" + meta.nSlices + " z)", i == 0);
        }
        if (!pd.showDialog()) return null;

        List<Integer> selected = new ArrayList<Integer>();
        for (int i = 0; i < toggles.length; i++) {
            if (pd.getNextBoolean()) selected.add(metas.get(i).index);
        }
        return selected;
    }

    private List<SeriesMeta> filterSelectableSeries(String sourceLabel, List<SeriesMeta> metas) {
        List<SeriesMeta> filtered = new ArrayList<SeriesMeta>();
        if (metas == null) return filtered;

        for (SeriesMeta meta : metas) {
            if (meta == null) continue;
            if (ImageNameParser.isPreviewSeriesName(meta.name)) {
                IJ.log("Skipping preview/thumbnail series: " + seriesDisplayLabel(sourceLabel, meta));
                continue;
            }
            filtered.add(meta);
        }
        return filtered;
    }

    private String seriesDisplayLabel(File lifFile, SeriesMeta meta) {
        return seriesDisplayLabelText(lifFile, meta);
    }

    private String seriesDisplayLabel(String sourceLabel, SeriesMeta meta) {
        return seriesDisplayLabelText(sourceLabel, meta);
    }

    static String seriesDisplayLabelText(File lifFile, SeriesMeta meta) {
        return seriesDisplayLabelText(lifFile == null ? "" : lifFile.getName(), meta);
    }

    static String seriesDisplayLabelText(String sourceLabel, SeriesMeta meta) {
        if (meta == null) return "Series";
        String fallback = "Series " + (meta.index + 1);
        String seriesName = (meta.name == null || meta.name.trim().isEmpty()) ? fallback : meta.name.trim();
        return ImageNameParser.buildMultiSeriesDisplayLabel(
                sourceLabel == null ? "" : sourceLabel,
                seriesName);
    }

    private static String summarizeSeriesIndexes(List<Integer> seriesIndexes) {
        if (seriesIndexes == null || seriesIndexes.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(seriesIndexes.size(), 12);
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(", ");
            Integer index = seriesIndexes.get(i);
            sb.append("Series ").append(index == null ? "?" : String.valueOf(index.intValue() + 1));
        }
        if (seriesIndexes.size() > limit) {
            sb.append(", ... +").append(seriesIndexes.size() - limit).append(" more");
        }
        return sb.toString();
    }

    private static String imageShape(ImagePlus image) {
        if (image == null) return "(no image)";
        return image.getWidth() + "x" + image.getHeight()
                + " C" + Math.max(1, image.getNChannels())
                + " Z" + Math.max(1, image.getNSlices())
                + " T" + Math.max(1, image.getNFrames());
    }

    private static String sourceTitle(ImagePlus image) {
        if (image == null) return "no source";
        String title = image.getTitle();
        return title == null || title.trim().isEmpty() ? "untitled source" : title.trim();
    }

    private Map<Integer, QcMinMaxPerConditionSelector.SelectedSeries> selectedSeriesByIndex(
            List<QcMinMaxPerConditionSelector.SelectedSeries> selectedSeries) {
        LinkedHashMap<Integer, QcMinMaxPerConditionSelector.SelectedSeries> byIndex =
                new LinkedHashMap<Integer, QcMinMaxPerConditionSelector.SelectedSeries>();
        if (selectedSeries == null) return byIndex;
        for (QcMinMaxPerConditionSelector.SelectedSeries selected : selectedSeries) {
            if (selected != null && selected.seriesIndex >= 0) {
                byIndex.put(Integer.valueOf(selected.seriesIndex), selected);
            }
        }
        return byIndex;
    }

    private Map<Integer, Map<Integer, QcMinMaxPerConditionSelector.SelectedSeries>>
            selectedSeriesBySeriesAndChannel(
            Map<Integer, List<QcMinMaxPerConditionSelector.SelectedSeries>> selectedSeriesByChannelNumber) {
        LinkedHashMap<Integer, Map<Integer, QcMinMaxPerConditionSelector.SelectedSeries>> bySeries =
                new LinkedHashMap<Integer, Map<Integer, QcMinMaxPerConditionSelector.SelectedSeries>>();
        if (selectedSeriesByChannelNumber == null) return bySeries;
        for (Map.Entry<Integer, List<QcMinMaxPerConditionSelector.SelectedSeries>> entry
                : selectedSeriesByChannelNumber.entrySet()) {
            if (entry == null || entry.getKey() == null) continue;
            Integer channelNumber = entry.getKey();
            List<QcMinMaxPerConditionSelector.SelectedSeries> selectedSeries = entry.getValue();
            if (selectedSeries == null) continue;
            for (QcMinMaxPerConditionSelector.SelectedSeries selected : selectedSeries) {
                if (selected == null || selected.seriesIndex < 0) continue;
                Integer seriesIndex = Integer.valueOf(selected.seriesIndex);
                Map<Integer, QcMinMaxPerConditionSelector.SelectedSeries> byChannel =
                        bySeries.get(seriesIndex);
                if (byChannel == null) {
                    byChannel = new LinkedHashMap<Integer, QcMinMaxPerConditionSelector.SelectedSeries>();
                    bySeries.put(seriesIndex, byChannel);
                }
                byChannel.put(channelNumber, selected);
            }
        }
        return bySeries;
    }

    private Map<Integer, String> minMaxReviewLabelsByChannel(
            Map<Integer, QcMinMaxPerConditionSelector.SelectedSeries> selectedByChannel) {
        LinkedHashMap<Integer, String> labels = new LinkedHashMap<Integer, String>();
        if (selectedByChannel == null) return labels;
        for (Map.Entry<Integer, QcMinMaxPerConditionSelector.SelectedSeries> entry
                : selectedByChannel.entrySet()) {
            if (entry == null || entry.getKey() == null) continue;
            labels.put(entry.getKey(), minMaxReviewLabel(entry.getValue()));
        }
        return labels;
    }

    private Map<Integer, Map<Integer, Integer>> selectedSeriesOrderBySeriesAndChannel(
            Map<Integer, List<QcMinMaxPerConditionSelector.SelectedSeries>> selectedSeriesByChannelNumber) {
        LinkedHashMap<Integer, Map<Integer, Integer>> bySeries =
                new LinkedHashMap<Integer, Map<Integer, Integer>>();
        if (selectedSeriesByChannelNumber == null) return bySeries;
        for (Map.Entry<Integer, List<QcMinMaxPerConditionSelector.SelectedSeries>> entry
                : selectedSeriesByChannelNumber.entrySet()) {
            if (entry == null || entry.getKey() == null) continue;
            Integer channelNumber = entry.getKey();
            List<QcMinMaxPerConditionSelector.SelectedSeries> selectedSeries = entry.getValue();
            if (selectedSeries == null) continue;
            for (int i = 0; i < selectedSeries.size(); i++) {
                QcMinMaxPerConditionSelector.SelectedSeries selected = selectedSeries.get(i);
                if (selected == null || selected.seriesIndex < 0) continue;
                Integer seriesIndex = Integer.valueOf(selected.seriesIndex);
                Map<Integer, Integer> orderByChannel = bySeries.get(seriesIndex);
                if (orderByChannel == null) {
                    orderByChannel = new LinkedHashMap<Integer, Integer>();
                    bySeries.put(seriesIndex, orderByChannel);
                }
                orderByChannel.put(channelNumber, Integer.valueOf(i));
            }
        }
        return bySeries;
    }

    private String minMaxReviewLabel(QcMinMaxPerConditionSelector.SelectedSeries selected) {
        if (selected == null) return "";
        String role = minMaxRoleLabel(selected.selectedRole);
        if (isOverallMinMaxRole(selected.selectedRole)) return role;
        String condition = selected.conditionName == null ? "" : selected.conditionName.trim();
        if (!condition.isEmpty() && !role.isEmpty()) return condition + " - " + role;
        if (!role.isEmpty()) return role;
        return condition;
    }

    private String minMaxRoleLabel(String role) {
        String value = role == null ? "" : role.trim();
        if ("OVERALL_MIN_MAX".equals(value)) return "Overall MIN/MAX";
        if ("OVERALL_MIN".equals(value)) return "Overall MIN";
        if ("OVERALL_MAX".equals(value)) return "Overall MAX";
        if ("MIN_MAX".equals(value)) return "MIN/MAX";
        if ("MIN".equals(value)) return "MIN";
        if ("MAX".equals(value)) return "MAX";
        return value;
    }

    private static boolean isMinMaxSelectionMode(String mode) {
        return QC_SELECTION_MODE_MIN_MAX_OVERALL.equals(mode)
                || QC_SELECTION_MODE_MIN_MAX_CONDITION.equals(mode);
    }

    private static boolean requiresMinMaxConditionMetadataReview(String mode) {
        return QC_SELECTION_MODE_MIN_MAX_CONDITION.equals(mode);
    }

    private static boolean isOverallMinMaxRole(String role) {
        String value = role == null ? "" : role.trim();
        return "OVERALL_MIN_MAX".equals(value)
                || "OVERALL_MIN".equals(value)
                || "OVERALL_MAX".equals(value);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String joinStrings(List<String> values, String delimiter) {
        StringBuilder out = new StringBuilder();
        String sep = delimiter == null ? "" : delimiter;
        for (int i = 0; values != null && i < values.size(); i++) {
            String value = values.get(i);
            if (!hasText(value)) {
                continue;
            }
            if (out.length() > 0) {
                out.append(sep);
            }
            out.append(value.trim());
        }
        return out.toString();
    }

    private void showQcOpenMessageIfPresent(QcImageOpenResult result) {
        if (result != null && hasText(result.message)) {
            IJ.showMessage("Set Up Configuration", result.message);
        }
    }

    private void setComponentTreeEnabled(Component component, boolean enabled) {
        if (component == null) return;
        component.setEnabled(enabled);
        if (component instanceof Container) {
            Component[] children = ((Container) component).getComponents();
            for (int i = 0; i < children.length; i++) {
                setComponentTreeEnabled(children[i], enabled);
            }
        }
    }

    private boolean hasPendingCustomFilters(BinUserConfig cfg) {
        if (cfg == null || cfg.filterPresets == null) return false;
        for (int i = 0; i < cfg.filterPresets.size(); i++) {
            if (isCustomFilterPlaceholder(cfg, i)) return true;
        }
        return false;
    }

    private static boolean isCustomFilterPlaceholder(BinUserConfig cfg, int channelIndex) {
        return cfg != null
                && cfg.filterPresets != null
                && channelIndex >= 0
                && channelIndex < cfg.filterPresets.size()
                && "Custom".equals(cfg.filterPresets.get(channelIndex));
    }

    private boolean needsQcImages(boolean[][] customSettings, BinUserConfig cfg) {
        return anyTrue(qcSelectionSettings(customSettings, cfg));
    }

    private boolean[][] emptyCustomSettings(int channelCount) {
        return new boolean[SETTINGS_SLOT_COUNT][Math.max(0, channelCount)];
    }

    private boolean[][] settingsMatrixForChannelCount(boolean[][] settings, int channelCount) {
        boolean[][] resized = emptyCustomSettings(channelCount);
        copySettings(settings, resized);
        return resized;
    }

    private boolean[][] qcSelectionSettings(boolean[][] customSettings, BinUserConfig cfg) {
        int nChannels = cfg == null || cfg.names == null ? 0 : cfg.names.size();
        boolean[][] result = emptyCustomSettings(nChannels);
        if (customSettings != null) {
            int slots = Math.min(customSettings.length, SETTINGS_SLOT_COUNT);
            for (int slot = 0; slot < slots; slot++) {
                int n = customSettings[slot] == null ? 0 : Math.min(customSettings[slot].length, nChannels);
                for (int ch = 0; ch < n; ch++) {
                    result[slot][ch] = customSettings[slot][ch];
                }
            }
        }
        if (cfg != null && cfg.filterPresets != null) {
            int n = Math.min(cfg.filterPresets.size(), nChannels);
            for (int ch = 0; ch < n; ch++) {
                if (isCustomFilterPlaceholder(cfg, ch)) {
                    result[SETTINGS_FILTER_PARAMETERS][ch] = true;
                }
            }
        }
        return result;
    }

    private List<QcSelectionChannel> buildQcSelectionChannels(BinUserConfig cfg, boolean[][] customSettings) {
        List<QcSelectionChannel> qcChannels = new ArrayList<QcSelectionChannel>();
        if (cfg == null || customSettings == null || customSettings.length < SETTINGS_SLOT_COUNT) return qcChannels;

        int nChannels = cfg.names == null ? 0 : cfg.names.size();
        for (int ch = 0; ch < nChannels; ch++) {
            boolean useFilterParameters = ch < customSettings[SETTINGS_FILTER_PARAMETERS].length
                    && customSettings[SETTINGS_FILTER_PARAMETERS][ch];
            boolean useMinMax = ch < customSettings[SETTINGS_MIN_MAX].length
                    && customSettings[SETTINGS_MIN_MAX][ch];
            boolean useThreshold = (ch < customSettings[SETTINGS_ROI_INTENSITY_THRESHOLD].length
                    && customSettings[SETTINGS_ROI_INTENSITY_THRESHOLD][ch])
                    || (ch < customSettings[SETTINGS_OBJECT_THRESHOLD].length
                    && customSettings[SETTINGS_OBJECT_THRESHOLD][ch]);
            boolean useParticleSize = ch < customSettings[SETTINGS_OBJECT_SIZE_FILTER].length
                    && customSettings[SETTINGS_OBJECT_SIZE_FILTER][ch];
            boolean useSegmentationMethod = ch < customSettings[SETTINGS_SEGMENTATION_METHOD].length
                    && customSettings[SETTINGS_SEGMENTATION_METHOD][ch];
            if (useSegmentationMethod) {
                useThreshold = true;
                useParticleSize = true;
            }
            if (!useFilterParameters && !useMinMax && !useThreshold && !useParticleSize) continue;

            qcChannels.add(new QcSelectionChannel(
                    ch,
                    cfg.names.get(ch),
                    useMinMax,
                    useThreshold,
                    useParticleSize));
        }
        return qcChannels;
    }

    // ── Interactive Quality Check ───────────────────────────────────────

    /**
     * Interactive QC. Each setting is independently toggled per channel.
     * customSettings[0] = filter params, [1] = min-max,
     * [2] = ROI intensity thresholds, [3] = object thresholds,
     * [4] = object size filter, [5] = segmentation method.
     * All values persist between images within a channel (including across restarts).
     *
     * @return "done", "cancel", "back", or "skip"
     */
    private String interactivePendingCustomFilters(List<QcImageSelection> images, BinUserConfig cfg,
                                                   File binFolder) throws IOException {
        if (!hasPendingCustomFilters(cfg)) return "done";
        if (images == null || images.isEmpty()) {
            IJ.showMessage("Custom Filter",
                    "Custom filters need at least one selected QC image so the filter can be built and previewed.");
            return "cancel";
        }

        for (int ch = 0; ch < cfg.filterPresets.size(); ch++) {
            if (!"Custom".equals(cfg.filterPresets.get(ch))) continue;
            String result = interactiveCustomFilterForChannel(images, cfg, binFolder, ch);
            if ("cancel".equals(result)) return "cancel";
        }
        return "done";
    }

    private String interactiveCustomFilterForChannel(List<QcImageSelection> images, BinUserConfig cfg,
                                                     File binFolder, int channelIndex) throws IOException {
        while (true) {
            boolean applied = promptAndApplyCustomFilter(binFolder, cfg, channelIndex, false, images);
            if (!applied) return "cancel";
            rememberHandledCustomFilter(binFolder, channelIndex, cfg);

            String savedToast = "Saved C" + (channelIndex + 1)
                    + " custom filter — applied to next QC step.";
            String previewResult = previewCustomFilterOnQcImages(images, cfg, binFolder, channelIndex, savedToast);
            if ("adjust".equals(previewResult)) continue;
            if ("cancel".equals(previewResult)) return "cancel";
            return "done";
        }
    }

    private String previewCustomFilterOnQcImages(List<QcImageSelection> images, BinUserConfig cfg,
                                                 File binFolder, int channelIndex, String savedToast) {
        int channelNum = channelIndex + 1;
        String chLabel = "C" + channelNum + " (" + cfg.names.get(channelIndex) + ")";
        String macro = resolveFilterContent(binFolder, cfg, channelIndex);
        if (macro == null || macro.trim().isEmpty()) {
            IJ.showMessage("Custom Filter Preview",
                    "No custom filter macro could be loaded for " + chLabel + ".");
            return "adjust";
        }

        int imgIdx = 0;
        boolean toastShown = false;
        while (imgIdx < images.size()) {
            QcImageSelection imageSelection = images.get(imgIdx);
            ImagePlus source = duplicateQcChannel(imageSelection, channelIndex,
                    cfg.names.size(), cfg.colors.get(channelIndex),
                    "Custom Filter Source | " + chLabel, false);
            ImagePlus preview = null;
            try {
                if (source == null) {
                    IJ.showMessage("Custom Filter Preview",
                            "Could not open " + chLabel + " from " + imageSelection.seriesName + ".");
                    return "cancel";
                }
                preview = renderFilterParameterPreview(source, macro,
                        cfg.colors.get(channelIndex), chLabel, imageSelection.seriesName);
                String toastForThisImage = toastShown ? null : savedToast;
                String action = showCustomFilterPreviewAction(
                        "Custom Filter Preview - " + chLabel,
                        "Image " + (imgIdx + 1) + "/" + images.size() + ": " + imageSelection.seriesName,
                        imgIdx, images.size(), preview, toastForThisImage);
                toastShown = true;
                if ("cancel".equals(action)) return "cancel";
                if ("adjust".equals(action)) return "adjust";
                if (ACTION_SKIP_CURRENT_IMAGE.equals(action)) {
                    imgIdx++;
                    continue;
                }
                if ("apply".equals(action)) return "done";
                if ("restart".equals(action)) {
                    imgIdx = 0;
                    continue;
                }
                imgIdx++;
            } finally {
                closeImageQuietly(preview);
                closeImageQuietly(source);
            }
        }
        return "done";
    }

    private String showCustomFilterPreviewAction(String title, String summary, int imgIdx, int totalImages,
                                                 ImagePlus previewImage, String savedToast) {
        PipelineDialog pd = new PipelineDialog(title);
        installWizardCancelHook(pd);
        pd.addMessage(summary);
        pd.addSpacer(8);

        String[] actions;
        if (imgIdx >= totalImages - 1) {
            pd.addMessage("This was the last QC image. Apply this filter, adjust it, or restart preview from the first image.");
            actions = new String[]{"Apply to all images", "Adjust filter", "Restart from first image"};
        } else {
            pd.addMessage("Continue previewing, apply this filter to all images, adjust it, or restart from the first image.");
            actions = new String[]{"Continue to next image", "Apply to all images", "Adjust filter", "Restart from first image"};
        }
        pd.addChoice("Action", actions, actions[0]);
        if (savedToast != null && !savedToast.isEmpty()) {
            pd.setTransientStatus(savedToast);
        }
        addSkipCurrentImageButton(pd);
        positionPipelineDialogBesideImage(pd, previewImage);
        if (!pd.showDialog()) {
            return isSkipCurrentImageAction(pd) ? ACTION_SKIP_CURRENT_IMAGE : "cancel";
        }

        String choice = pd.getNextChoice();
        if (choice.contains("Adjust")) return "adjust";
        if (choice.contains("Restart")) return "restart";
        if (choice.contains("Apply")) return "apply";
        return "continue";
    }

    private void positionPipelineDialogBesideImage(PipelineDialog dialog, ImagePlus imp) {
        if (dialog == null || imp == null || imp.getWindow() == null) return;
        java.awt.Window imgWin = imp.getWindow();
        dialog.setLocation(imgWin.getX() + imgWin.getWidth() + 20, imgWin.getY());
    }

    private ImagePlus duplicateQcChannel(QcImageSelection imageSelection, int channelIndex,
                                         int expectedChannels, String colorName,
                                         String titlePrefix, boolean show) {
        if (imageSelection == null || imageSelection.image == null) return null;
        ImagePlus imp = imageSelection.image;
        int channelNum = channelIndex + 1;
        ImagePlus dup = duplicateChannelForSetupPreview(imp, channelNum, expectedChannels);
        dup.setTitle(titlePrefix + " | " + imageSelection.seriesName);
        if (show) {
            dup.show();
            IJ.run(dup, toLutName(colorName), "");
            positionImageLeft(dup);
            if (dup.getWindow() != null) WindowManager.setCurrentWindow(dup.getWindow());
        }
        return dup;
    }

    private BinConfig toBinConfig(BinUserConfig cfg) {
        BinConfig binCfg = new BinConfig();
        if (cfg != null) {
            binCfg.zSliceMode = cfg.zSliceMode == null ? ZSliceMode.FULL : cfg.zSliceMode;
            binCfg.zSliceSelections.putAll(cfg.zSliceSelections);
        }
        return binCfg;
    }

    protected String interactiveZSliceSubsetQC(String directory, BinUserConfig cfg) {
        if (cfg == null || !cfg.usesZSliceSubset()) return "done";
        if (!gateBioFormatsFeature("Set Up Configuration z-slice subset preview")) {
            return "back";
        }

        String sourceLabel;
        try {
            sourceLabel = resolveQcSourceReference(directory).sourceLabel;
        } catch (IllegalArgumentException e) {
            IJ.showMessage("Set Up Configuration",
                    "Cannot run z-slice selection: " + e.getMessage());
            return "cancel";
        }

        try {
            DeferredImageSupplier supplier = ImageSourceDispatcher.createSupplier(directory);
            sourceLabel = sourceDisplayLabel(supplier, sourceLabel);
            List<SeriesMeta> metas = filterSelectableSeries(
                    sourceLabel, ImageSourceDispatcher.readAllMetadata(directory));
            if (metas.isEmpty()) {
                cfg.zSliceMode = ZSliceMode.FULL;
                cfg.zSliceSelections.clear();
                return "done";
            }

            if (useEmbeddedZSliceSelectionStage()) {
                return runEmbeddedZSliceSelection(directory, sourceLabel, supplier, cfg, metas);
            }

            int totalSeries = metas.size();
            ZSliceRange lastAcceptedRange = null;
            Set<Integer> batchAppliedMetaIndices = new HashSet<Integer>();

            int idx = 0;
            while (idx < totalSeries) {
                SeriesMeta meta = metas.get(idx);

                // Skip past any series that were batch-applied in an earlier
                // "Apply to compatible" action. Restart clears the skip set so
                // a full do-over still walks every image.
                if (batchAppliedMetaIndices.contains(meta.index)) {
                    idx++;
                    continue;
                }

                ImagePlus imp = supplier.openSeries(meta.index);
                if (imp == null) {
                    IJ.showMessage("Set Up Configuration",
                            "Failed to open " + seriesDisplayLabel(sourceLabel, meta) + " for z-slice selection.");
                    return "cancel";
                }

                imp.show();
                positionImageLeft(imp);

                ZSliceDefault zSliceDefault = defaultZSliceSelection(cfg, meta, lastAcceptedRange, imp);
                String defaultToken = zSliceDefault.token;
                String suggestionHint = zSliceDefault.hint;
                while (true) {
                    PipelineDialog pd = new PipelineDialog("Set Up Configuration - Z-Slice Subset");
                    installWizardCancelHook(pd);
                    pd.enableBackButton();
                    pd.setPrimaryButtonText("Lock in");
                    pd.setModal(false);
                    pd.addAnalysisHelpHeader("Set Up Configuration", FLASH_Pipeline.IDX_CREATE_BIN);
                    pd.addSetupHelpSubHeader("Z-Slice Subset", SetupHelpCatalog.Z_SLICE_SUBSET);
                    pd.addMessage("Image " + (idx + 1) + "/" + totalSeries + ": "
                            + seriesDisplayLabel(sourceLabel, meta));
                    pd.addMessage("Total z-slices: " + meta.nSlices);
                    pd.addHelpText("Review the stack and enter a contiguous inclusive range such as 11-30.");
                    pd.addHelpText("If no per-image suggestion is available, the previous accepted range is remembered for the next image.");
                    JTextField zRangeField = pd.addStringField("Slices to keep", defaultToken, 16);
                    if (hasText(suggestionHint)) {
                        zRangeField.setToolTipText(suggestionHint);
                        pd.addHelpText(suggestionHint);
                    }

                    String[] actions = idx >= totalSeries - 1
                            ? new String[]{"Accept selection", "Restart from first image"}
                            : new String[]{"Next image", "Restart from first image", "Apply current range to all remaining images"};
                    pd.addChoice("Action", actions, actions[0]);
                    addSkipCurrentImageButton(pd);

                    if (!pd.showDialog()) {
                        closeImageQuietly(imp);
                        if (isSkipCurrentImageAction(pd)) {
                            idx++;
                            break;
                        }
                        return pd.wasBackPressed() ? "back" : "cancel";
                    }

                    String token = pd.getNextString();
                    String action = pd.getNextChoice();
                    ZSliceRange range = ZSliceRange.parse(token);
                    if (range == null || !range.isValidFor(Math.max(1, meta.nSlices))) {
                        IJ.showMessage("Set Up Configuration",
                                "Enter a valid contiguous z-slice range within 1-" + Math.max(1, meta.nSlices)
                                        + " (for example 11-30).");
                        defaultToken = token;
                        suggestionHint = "";
                        continue;
                    }

                    // Save the current image's selection up-front — every action path
                    // keeps it. Apply branches may then also write remaining images.
                    cfg.zSliceSelections.put(meta.index,
                            new ZSliceSelection(meta.index, meta.name, Math.max(1, meta.nSlices), range));
                    lastAcceptedRange = range;

                    if (action.contains("Apply")) {
                        RangeCompatibility compat = computeRangeCompatibility(metas, idx + 1, range);
                        if (compat.isEmptyRemaining() || compat.isAllCompatible()) {
                            // All remaining series (if any) fit → apply to every one
                            // and exit the loop, matching the existing fast path.
                            for (int j = idx + 1; j < totalSeries; j++) {
                                SeriesMeta remaining = metas.get(j);
                                cfg.zSliceSelections.put(remaining.index,
                                        new ZSliceSelection(remaining.index, remaining.name,
                                                Math.max(1, remaining.nSlices), range));
                                batchAppliedMetaIndices.add(remaining.index);
                            }
                            idx = totalSeries;
                        } else if (compat.isAllIncompatible()) {
                            // No remaining series can accept this range — reject outright.
                            IJ.showMessage("Set Up Configuration",
                                    "The range " + range.toToken()
                                            + " does not fit any remaining image series:\n"
                                            + formatIncompatibleList(compat.incompatibleMetas)
                                            + "\n\nChoose a smaller range or continue image-by-image.");
                            defaultToken = range.toToken();
                            suggestionHint = "";
                            continue;
                        } else {
                            // Partial fit — let the user choose how to proceed.
                            PartialApplyChoice choice = promptPartialApplyDecision(range, compat);
                            if (choice == PartialApplyChoice.CANCEL) {
                                defaultToken = range.toToken();
                                suggestionHint = "";
                                continue;
                            }
                            if (choice == PartialApplyChoice.CONTINUE_MANUAL) {
                                idx++;
                            } else {
                                // APPLY_TO_COMPATIBLE: write the range into every
                                // compatible remaining series, mark them as batch-applied,
                                // and jump the cursor to the first outlier.
                                for (Integer compatiblePos : compat.compatiblePositions) {
                                    SeriesMeta remaining = metas.get(compatiblePos);
                                    cfg.zSliceSelections.put(remaining.index,
                                            new ZSliceSelection(remaining.index, remaining.name,
                                                    Math.max(1, remaining.nSlices), range));
                                    batchAppliedMetaIndices.add(remaining.index);
                                }
                                idx = compat.firstIncompatiblePosition;
                            }
                        }
                    } else if (action.contains("Restart")) {
                        batchAppliedMetaIndices.clear();
                        idx = 0;
                    } else {
                        idx++;
                    }

                    closeImageQuietly(imp);
                    break;
                }
            }

            return finalizeZSliceSelections(cfg);
        } catch (Exception e) {
            IJ.showMessage("Set Up Configuration",
                    "Error while selecting z-slices: " + e.getMessage());
            return "cancel";
        }
    }

    private boolean useEmbeddedZSliceSelectionStage() {
        // Config QC stays in the owned embedded widget; the legacy native
        // code path below is retained only as disabled rollback reference.
        return true;
    }

    private String runEmbeddedZSliceSelection(String directory, String sourceLabel,
                                              DeferredImageSupplier supplier,
                                              BinUserConfig cfg, List<SeriesMeta> metas) throws Exception {
        if (!embeddedConfigQcUiAvailable()) {
            return "cancel";
        }
        ConfigQcContext context = new ConfigQcContext(
                new File(directory),
                null,
                cfg,
                zSliceContextImages(sourceLabel, metas),
                cfg.names,
                0,
                setupFilteredStackCache);
        ZSliceSelectionStage stage = createZSliceSelectionStage(supplier, metas, cfg);
        ConfigQcResult result = showEmbeddedConfigQcDialog(
                context, Collections.<ConfigQcStage>singletonList(stage));
        if (result == ConfigQcResult.DONE || result == ConfigQcResult.SKIP_CURRENT_IMAGE) {
            return finalizeZSliceSelections(cfg);
        }
        if (result == ConfigQcResult.BACK) {
            return "back";
        }
        return "cancel";
    }

    ZSliceSelectionStage createZSliceSelectionStage(final DeferredImageSupplier supplier,
                                                   List<SeriesMeta> metas,
                                                   final BinUserConfig cfg) {
        return new ZSliceSelectionStage(
                metas,
                new ZSliceSelectionStage.SelectionStore() {
                    @Override public ZSliceSelection get(int seriesIndex) {
                        return cfg.zSliceSelections.get(Integer.valueOf(seriesIndex));
                    }

                    @Override public void put(ZSliceSelection selection) {
                        if (selection != null) {
                            cfg.zSliceSelections.put(Integer.valueOf(selection.seriesIndex), selection);
                        }
                    }
                },
                new ZSliceSelectionStage.ImageOpener() {
                    @Override public ImagePlus open(SeriesMeta meta) throws Exception {
                        return supplier.openSeries(meta.index);
                    }

                    @Override public void close(ImagePlus image) {
                        closeImageQuietly(image);
                    }
                },
                new ZSliceSelectionStage.PartialApplyHandler() {
                    @Override public ZSliceSelectionStage.PartialApplyChoice choose(
                            ZSliceRange range,
                            List<SeriesMeta> compatibleMetas,
                            List<SeriesMeta> incompatibleMetas) {
                        return promptEmbeddedPartialApplyDecision(
                                range, compatibleMetas, incompatibleMetas);
                    }
                });
    }

    private ZSliceSelectionStage.PartialApplyChoice promptEmbeddedPartialApplyDecision(
            ZSliceRange range, List<SeriesMeta> compatibleMetas, List<SeriesMeta> incompatibleMetas) {
        int compatibleCount = compatibleMetas == null ? 0 : compatibleMetas.size();
        int incompatibleCount = incompatibleMetas == null ? 0 : incompatibleMetas.size();
        int remainingCount = compatibleCount + incompatibleCount;

        String applyLabel = "Apply to the " + compatibleCount
                + " compatible images, handle the " + incompatibleCount + " outlier"
                + (incompatibleCount == 1 ? "" : "s") + " manually";
        String manualLabel = "Continue manually on all " + remainingCount + " remaining images";

        PipelineDialog dlg = new PipelineDialog("Set Up Configuration - Range Does Not Fit All Remaining");
        installWizardCancelHook(dlg);
        dlg.addSetupHelpHeader("Range Does Not Fit Every Remaining Image",
                SetupHelpCatalog.Z_SLICE_PARTIAL_APPLY);
        dlg.addMessage("The range " + range.toToken() + " fits " + compatibleCount
                + " of " + remainingCount + " remaining image" + (remainingCount == 1 ? "" : "s") + ".");
        dlg.addMessage("These remaining images cannot accept the range:");
        dlg.addMessage(formatIncompatibleListHtml(incompatibleMetas));
        dlg.addChoice("Action", new String[]{applyLabel, manualLabel}, applyLabel);
        dlg.addHelpText(
                "<b>Apply to compatible</b>: writes the range to every compatible image now, "
                + "then pauses on the first outlier so you can pick a range for it manually.<br>"
                + "<b>Continue manually</b>: keeps the current image's range, leaves the "
                + "remaining images untouched, and advances image-by-image.<br>"
                + "Cancel returns to the z-slice widget so you can pick a different range.");

        if (!dlg.showDialog()) return ZSliceSelectionStage.PartialApplyChoice.CANCEL;
        String picked = dlg.getNextChoice();
        if (picked != null && picked.equals(applyLabel)) {
            return ZSliceSelectionStage.PartialApplyChoice.APPLY_TO_COMPATIBLE;
        }
        return ZSliceSelectionStage.PartialApplyChoice.CONTINUE_MANUAL;
    }

    static List<ConfigQcContext.ConfigQcImage> zSliceContextImages(File lifFile, List<SeriesMeta> metas) {
        return zSliceContextImages(lifFile == null ? "" : lifFile.getName(), metas);
    }

    static List<ConfigQcContext.ConfigQcImage> zSliceContextImages(String sourceLabel, List<SeriesMeta> metas) {
        List<ConfigQcContext.ConfigQcImage> images = new ArrayList<ConfigQcContext.ConfigQcImage>();
        if (metas != null) {
            for (int i = 0; i < metas.size(); i++) {
                SeriesMeta meta = metas.get(i);
                if (meta == null) continue;
                images.add(new ConfigQcContext.ConfigQcImage(
                        meta.index, seriesDisplayLabelText(sourceLabel, meta), null));
            }
        }
        return images;
    }

    private ZSliceDefault defaultZSliceSelection(BinUserConfig cfg, SeriesMeta meta,
                                                ZSliceRange lastAcceptedRange, ImagePlus imp) {
        int totalSlices = Math.max(1, meta.nSlices);
        if (cfg != null) {
            ZSliceSelection existing = cfg.zSliceSelections.get(meta.index);
            if (existing != null && existing.range != null && !existing.isFullStack()) {
                return new ZSliceDefault(existing.range.toToken(), "");
            }
        }

        EmptySliceSuggester.Suggestion suggestion = EmptySliceSuggester.suggest(imp);
        if (suggestion != null
                && suggestion.range != null
                && suggestion.range.isValidFor(totalSlices)) {
            return new ZSliceDefault(
                    suggestion.range.toToken(),
                    suggestion.trimsSlices() ? suggestion.tooltip() : "");
        }

        if (cfg != null) {
            ZSliceSelection existing = cfg.zSliceSelections.get(meta.index);
            if (existing != null && existing.range != null) {
                return new ZSliceDefault(existing.range.toToken(), "");
            }
        }
        if (lastAcceptedRange != null && lastAcceptedRange.isValidFor(totalSlices)) {
            return new ZSliceDefault(lastAcceptedRange.toToken(), "");
        }
        return new ZSliceDefault(ZSliceRange.fullStack(totalSlices).toToken(), "");
    }

    /**
     * Classifies the remaining series (from {@code startIndex} onward) by whether
     * they can accept the supplied z-slice range. Positions are indices into
     * {@code metas}; incompatible metas are kept for user-facing messages.
     */
    private RangeCompatibility computeRangeCompatibility(
            List<SeriesMeta> metas, int startIndex, ZSliceRange range) {
        List<Integer> compatiblePositions = new ArrayList<Integer>();
        List<SeriesMeta> incompatibleMetas = new ArrayList<SeriesMeta>();
        int firstIncompatible = -1;
        if (metas != null && range != null) {
            for (int i = startIndex; i < metas.size(); i++) {
                SeriesMeta meta = metas.get(i);
                if (meta == null) continue;
                if (range.isValidFor(Math.max(1, meta.nSlices))) {
                    compatiblePositions.add(i);
                } else {
                    incompatibleMetas.add(meta);
                    if (firstIncompatible < 0) firstIncompatible = i;
                }
            }
        }
        return new RangeCompatibility(compatiblePositions, incompatibleMetas, firstIncompatible);
    }

    /** Plain-text bullet list of incompatible series for {@link IJ#showMessage} dialogs. */
    private String formatIncompatibleList(List<SeriesMeta> incompatibleMetas) {
        return formatIncompatibleListImpl(incompatibleMetas, "\n", false);
    }

    /** HTML bullet list variant for {@link PipelineDialog#addMessage} (which wraps in &lt;html&gt;). */
    private String formatIncompatibleListHtml(List<SeriesMeta> incompatibleMetas) {
        return formatIncompatibleListImpl(incompatibleMetas, "<br>", true);
    }

    private String formatIncompatibleListImpl(
            List<SeriesMeta> incompatibleMetas, String lineBreak, boolean escapeHtml) {
        if (incompatibleMetas == null || incompatibleMetas.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(incompatibleMetas.size(), 8);
        for (int i = 0; i < limit; i++) {
            SeriesMeta meta = incompatibleMetas.get(i);
            if (meta == null) continue;
            String name = meta.name == null || meta.name.trim().isEmpty()
                    ? "Series " + (meta.index + 1)
                    : meta.name;
            if (escapeHtml) {
                name = escapeHtmlText(name);
            }
            sb.append("  • ").append(name)
                    .append(" — only ").append(Math.max(1, meta.nSlices)).append(" slices");
            if (i < limit - 1) sb.append(lineBreak);
        }
        if (incompatibleMetas.size() > limit) {
            sb.append(lineBreak).append("  … and ")
                    .append(incompatibleMetas.size() - limit).append(" more");
        }
        return sb.toString();
    }

    static String escapeHtmlText(String text) {
        if (text == null || text.isEmpty()) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * Shown when the chosen range fits some but not all remaining series.
     * Returns the user's decision, or {@link PartialApplyChoice#CANCEL} if
     * they dismissed the dialog.
     */
    private PartialApplyChoice promptPartialApplyDecision(ZSliceRange range, RangeCompatibility compat) {
        int compatibleCount = compat.compatiblePositions.size();
        int incompatibleCount = compat.incompatibleMetas.size();
        int remainingCount = compatibleCount + incompatibleCount;

        String applyLabel = "Apply to the " + compatibleCount
                + " compatible images, handle the " + incompatibleCount + " outlier"
                + (incompatibleCount == 1 ? "" : "s") + " manually";
        String manualLabel = "Continue manually on all " + remainingCount + " remaining images";

        PipelineDialog dlg = new PipelineDialog("Set Up Configuration - Range Does Not Fit All Remaining");
        installWizardCancelHook(dlg);
        dlg.addHeader("Range Does Not Fit Every Remaining Image");
        dlg.addMessage("The range " + range.toToken() + " fits " + compatibleCount
                + " of " + remainingCount + " remaining image" + (remainingCount == 1 ? "" : "s") + ".");
        dlg.addMessage("These remaining images cannot accept the range:");
        dlg.addMessage(formatIncompatibleListHtml(compat.incompatibleMetas));
        dlg.addChoice("Action", new String[]{applyLabel, manualLabel}, applyLabel);
        dlg.addHelpText(
                "<b>Apply to compatible</b>: writes the range to every compatible image now, "
                + "then pauses on the first outlier so you can pick a range for it manually.<br>"
                + "<b>Continue manually</b>: keeps the current image's range, leaves the "
                + "remaining images untouched, and advances image-by-image.<br>"
                + "Cancel returns to the range dialog so you can pick a different range.");

        if (!dlg.showDialog()) return PartialApplyChoice.CANCEL;
        String picked = dlg.getNextChoice();
        if (picked != null && picked.equals(applyLabel)) return PartialApplyChoice.APPLY_TO_COMPATIBLE;
        return PartialApplyChoice.CONTINUE_MANUAL;
    }

    private enum PartialApplyChoice { APPLY_TO_COMPATIBLE, CONTINUE_MANUAL, CANCEL }

    private static final class ZSliceDefault {
        final String token;
        final String hint;

        ZSliceDefault(String token, String hint) {
            this.token = token == null ? "" : token;
            this.hint = hint == null ? "" : hint;
        }
    }

    /** Result of classifying remaining series against a proposed z-slice range. */
    private static final class RangeCompatibility {
        final List<Integer> compatiblePositions;
        final List<SeriesMeta> incompatibleMetas;
        final int firstIncompatiblePosition;

        RangeCompatibility(List<Integer> compatiblePositions,
                           List<SeriesMeta> incompatibleMetas,
                           int firstIncompatiblePosition) {
            this.compatiblePositions = compatiblePositions;
            this.incompatibleMetas = incompatibleMetas;
            this.firstIncompatiblePosition = firstIncompatiblePosition;
        }

        boolean isEmptyRemaining() {
            return compatiblePositions.isEmpty() && incompatibleMetas.isEmpty();
        }

        boolean isAllCompatible() {
            return !compatiblePositions.isEmpty() && incompatibleMetas.isEmpty();
        }

        boolean isAllIncompatible() {
            return compatiblePositions.isEmpty() && !incompatibleMetas.isEmpty();
        }
    }

    private String finalizeZSliceSelections(BinUserConfig cfg) {
        if (cfg == null) return "done";
        if (cfg.zSliceSelections.isEmpty()) {
            cfg.zSliceMode = ZSliceMode.FULL;
            return "done";
        }
        if (allSelectionsAreFullStack(cfg)) {
            cfg.zSliceMode = ZSliceMode.FULL;
            cfg.zSliceSelections.clear();
            return "done";
        }
        if (allSelectionsUseSameRange(cfg)) {
            cfg.zSliceMode = ZSliceMode.SAME_ABSOLUTE;
            return "done";
        }
        if (cfg.zSliceSelections.size() < 2) {
            cfg.zSliceMode = ZSliceMode.PER_IMAGE;
            return "done";
        }

        int targetCount = smallestSelectedSliceCount(cfg);
        ZSliceRange suggestedAbsoluteRange = suggestHighestOverlapWindow(cfg, targetCount);
        int suggestedCoverage = overlapCoverage(cfg, suggestedAbsoluteRange);
        boolean absoluteAvailable = suggestedAbsoluteRange != null && canApplyAbsoluteRangeToAll(cfg, suggestedAbsoluteRange);

        PipelineDialog pd = new PipelineDialog("Set Up Configuration - Finalise Z-Slice Subset");
        installWizardCancelHook(pd);
        pd.addSetupHelpHeader("Finalise Z-Slice Subset", SetupHelpCatalog.Z_SLICE_FINALISE);
        pd.addMessage("Review how the saved z-slice selections should be applied across the dataset.");
        pd.addMessage("Suggested shared slice count: " + targetCount);
        if (suggestedAbsoluteRange != null) {
            String msg = "Suggested shared slice window: " + suggestedAbsoluteRange.toToken()
                    + " (highest overlap in " + suggestedCoverage + "/" + cfg.zSliceSelections.size() + " images)";
            if (!absoluteAvailable) {
                msg += ". This window is not valid for every image, so only same-count mode is available.";
            }
            pd.addMessage(msg);
        }
        pd.addChoice("Apply as",
                new String[]{"Keep customised slices per image", "Use the same number of slices per image"},
                cfg.zSliceMode == ZSliceMode.SAME_COUNT || cfg.zSliceMode == ZSliceMode.SAME_ABSOLUTE
                        ? "Use the same number of slices per image"
                        : "Keep customised slices per image");
        if (!pd.showDialog()) return "cancel";

        String finalMode = pd.getNextChoice();
        if (!finalMode.contains("same number")) {
            cfg.zSliceMode = ZSliceMode.PER_IMAGE;
            return "done";
        }

        String centreLabel = "Centre within each image's selected range";
        String topLabel = "Top-aligned — keep the first " + targetCount + " slices of each selected range";
        String bottomLabel = "Bottom-aligned — keep the last " + targetCount + " slices of each selected range";
        String absoluteLabel = absoluteAvailable
                ? "Shared absolute window " + suggestedAbsoluteRange.toToken()
                        + " — every image analyses the identical physical slices"
                : null;

        List<String> strategies = new ArrayList<String>();
        strategies.add(centreLabel);
        strategies.add(topLabel);
        strategies.add(bottomLabel);
        if (absoluteLabel != null) {
            strategies.add(absoluteLabel);
        }

        PipelineDialog sameCountDialog = new PipelineDialog("Set Up Configuration - Same Slice Count");
        installWizardCancelHook(sameCountDialog);
        sameCountDialog.addSetupHelpHeader("Same Slice Count", SetupHelpCatalog.Z_SLICE_SAME_COUNT);
        sameCountDialog.addMessage("Each image will keep " + targetCount
                + " slices, based on the smallest selected range.");
        sameCountDialog.addChoice("Positioning strategy",
                strategies.toArray(new String[0]), strategies.get(0));
        sameCountDialog.addHelpText(
                "<b>Centre</b>: trims equally from the top and bottom of each image's range. "
                + "Preserves per-image Z alignment; symmetric.<br>"
                + "<b>Top-aligned</b>: keeps the first slices of each range. "
                + "Use when signal sits near the start of each selected window.<br>"
                + "<b>Bottom-aligned</b>: keeps the last slices of each range. "
                + "Use when signal sits near the end of each selected window."
                + (absoluteLabel != null
                        ? "<br><b>Shared absolute window</b>: every image analyses the exact same physical slices. "
                                + "Highest comparability, only available when the window fits all selections."
                        : ""));
        if (!sameCountDialog.showDialog()) return "cancel";

        String strategy = sameCountDialog.getNextChoice();
        if (absoluteAvailable && strategy.equals(absoluteLabel)) {
            applySameAbsoluteSelections(cfg, suggestedAbsoluteRange);
            cfg.zSliceMode = ZSliceMode.SAME_ABSOLUTE;
        } else {
            SameCountAlignment alignment;
            if (strategy.equals(topLabel)) {
                alignment = SameCountAlignment.TOP;
            } else if (strategy.equals(bottomLabel)) {
                alignment = SameCountAlignment.BOTTOM;
            } else {
                alignment = SameCountAlignment.CENTRE;
            }
            applySameCountSelections(cfg, targetCount, alignment);
            cfg.zSliceMode = ZSliceMode.SAME_COUNT;
        }

        if (allSelectionsAreFullStack(cfg)) {
            cfg.zSliceMode = ZSliceMode.FULL;
            cfg.zSliceSelections.clear();
        }
        return "done";
    }

    private boolean allSelectionsAreFullStack(BinUserConfig cfg) {
        if (cfg == null || cfg.zSliceSelections.isEmpty()) return true;
        for (ZSliceSelection selection : cfg.zSliceSelections.values()) {
            if (selection != null && !selection.isFullStack()) {
                return false;
            }
        }
        return true;
    }

    private boolean allSelectionsUseSameRange(BinUserConfig cfg) {
        if (cfg == null || cfg.zSliceSelections.size() < 2) return false;
        ZSliceRange firstRange = null;
        for (ZSliceSelection selection : cfg.zSliceSelections.values()) {
            if (selection == null || selection.range == null) return false;
            if (firstRange == null) {
                firstRange = selection.range;
            } else if (!firstRange.equals(selection.range)) {
                return false;
            }
        }
        return firstRange != null;
    }

    private int smallestSelectedSliceCount(BinUserConfig cfg) {
        int minCount = Integer.MAX_VALUE;
        for (ZSliceSelection selection : cfg.zSliceSelections.values()) {
            if (selection == null) continue;
            minCount = Math.min(minCount, selection.sliceCount());
        }
        return minCount == Integer.MAX_VALUE ? 1 : minCount;
    }

    private ZSliceRange suggestHighestOverlapWindow(BinUserConfig cfg, int targetCount) {
        if (cfg == null || cfg.zSliceSelections.isEmpty() || targetCount < 1) return null;
        int maxEnd = 0;
        for (ZSliceSelection selection : cfg.zSliceSelections.values()) {
            if (selection == null) continue;
            maxEnd = Math.max(maxEnd, selection.range.endSlice);
        }
        if (maxEnd < targetCount) return null;

        ZSliceRange bestRange = null;
        int bestCoverage = -1;
        for (int start = 1; start <= (maxEnd - targetCount + 1); start++) {
            ZSliceRange candidate = new ZSliceRange(start, start + targetCount - 1);
            int coverage = overlapCoverage(cfg, candidate);
            if (coverage > bestCoverage) {
                bestCoverage = coverage;
                bestRange = candidate;
            }
        }
        return bestRange;
    }

    private int overlapCoverage(BinUserConfig cfg, ZSliceRange candidate) {
        if (cfg == null || candidate == null) return 0;
        int coverage = 0;
        for (ZSliceSelection selection : cfg.zSliceSelections.values()) {
            if (selection == null) continue;
            if (selection.range.startSlice <= candidate.startSlice
                    && selection.range.endSlice >= candidate.endSlice) {
                coverage++;
            }
        }
        return coverage;
    }

    private boolean canApplyAbsoluteRangeToAll(BinUserConfig cfg, ZSliceRange range) {
        if (cfg == null || range == null) return false;
        for (ZSliceSelection selection : cfg.zSliceSelections.values()) {
            if (selection == null) continue;
            if (!range.isValidFor(selection.totalSlices)) return false;
            if (selection.range.startSlice > range.startSlice || selection.range.endSlice < range.endSlice) {
                return false;
            }
        }
        return true;
    }

    private enum SameCountAlignment { CENTRE, TOP, BOTTOM }

    private void applySameCountSelections(BinUserConfig cfg, int targetCount, SameCountAlignment alignment) {
        LinkedHashMap<Integer, ZSliceSelection> updated = new LinkedHashMap<Integer, ZSliceSelection>();
        for (Map.Entry<Integer, ZSliceSelection> entry : cfg.zSliceSelections.entrySet()) {
            ZSliceSelection selection = entry.getValue();
            if (selection == null) continue;
            ZSliceRange trimmed = trimRangeToCount(selection.range, targetCount, alignment);
            updated.put(entry.getKey(),
                    new ZSliceSelection(selection.seriesIndex, selection.seriesName, selection.totalSlices, trimmed));
        }
        cfg.zSliceSelections.clear();
        cfg.zSliceSelections.putAll(updated);
    }

    private void applySameAbsoluteSelections(BinUserConfig cfg, ZSliceRange absoluteRange) {
        LinkedHashMap<Integer, ZSliceSelection> updated = new LinkedHashMap<Integer, ZSliceSelection>();
        for (Map.Entry<Integer, ZSliceSelection> entry : cfg.zSliceSelections.entrySet()) {
            ZSliceSelection selection = entry.getValue();
            if (selection == null) continue;
            updated.put(entry.getKey(),
                    new ZSliceSelection(selection.seriesIndex, selection.seriesName, selection.totalSlices, absoluteRange));
        }
        cfg.zSliceSelections.clear();
        cfg.zSliceSelections.putAll(updated);
    }

    /**
     * Trims {@code range} down to {@code targetCount} slices using the supplied alignment.
     * <ul>
     *   <li>CENTRE — trim equally from both ends, preserving the middle of the selection.</li>
     *   <li>TOP    — keep the first {@code targetCount} slices of the range.</li>
     *   <li>BOTTOM — keep the last {@code targetCount} slices of the range.</li>
     * </ul>
     * Returns {@code range} unchanged when it already has {@code targetCount} or fewer slices.
     */
    private ZSliceRange trimRangeToCount(ZSliceRange range, int targetCount, SameCountAlignment alignment) {
        if (range == null || targetCount < 1 || range.count() <= targetCount) return range;
        SameCountAlignment safeAlignment = alignment == null ? SameCountAlignment.CENTRE : alignment;
        int start;
        int end;
        switch (safeAlignment) {
            case TOP:
                start = range.startSlice;
                end = start + targetCount - 1;
                break;
            case BOTTOM:
                end = range.endSlice;
                start = end - targetCount + 1;
                break;
            case CENTRE:
            default:
                int extraSlices = range.count() - targetCount;
                start = range.startSlice + (extraSlices / 2);
                end = start + targetCount - 1;
                if (end > range.endSlice) {
                    end = range.endSlice;
                    start = end - targetCount + 1;
                }
                break;
        }
        return new ZSliceRange(start, end);
    }

    protected String interactiveQC(List<QcImageSelection> images, BinUserConfig cfg, File binFolder,
                                   boolean[][] customSettings) {
        return interactiveQC(images, cfg, binFolder, customSettings, false);
    }

    private String interactiveQC(List<QcImageSelection> images, BinUserConfig cfg, File binFolder,
                                 boolean[][] customSettings, boolean resumeConfiguredSteps) {
        if (useSequentialEmbeddedQcWorkflow()) {
            return interactiveSequentialEmbeddedQC(images, cfg, binFolder, customSettings,
                    resumeConfiguredSteps);
        }

        int nChannels = cfg.names.size();
        boolean[] customFilterParameters = customSettings[SETTINGS_FILTER_PARAMETERS];
        boolean[] customMinMax = customSettings[SETTINGS_MIN_MAX];
        boolean[] customRoiIntensityThreshold = customSettings[SETTINGS_ROI_INTENSITY_THRESHOLD];
        boolean[] customObjectThreshold = customSettings[SETTINGS_OBJECT_THRESHOLD];
        boolean[] customObjectSizeFilter = customSettings[SETTINGS_OBJECT_SIZE_FILTER];

        for (int ch = 0; ch < nChannels; ch++) {
            boolean doFilter = customFilterParameters[ch] || isCustomFilterPlaceholder(cfg, ch);
            boolean doMM = customMinMax[ch];
            boolean doRoiIntensityTh = customRoiIntensityThreshold[ch];
            boolean doObjTh = customObjectThreshold[ch];
            boolean doSz = customObjectSizeFilter[ch];
            if (!doFilter && !doMM && !doRoiIntensityTh && !doObjTh && !doSz) continue;

            int channelNum = ch + 1;
            String chLabel = "C" + channelNum + " (" + cfg.names.get(ch) + ")";

            // ── Step 1: Custom Min-Max Display Ranges (Max Projection) ──
            if (doFilter) {
                String filterResult = interactiveFilterParameterQC(images, cfg, binFolder, ch);
                if ("cancel".equals(filterResult)) return "cancel";
                if ("back".equals(filterResult)) return "back";
            }

            if (doMM) {
                String displayRangeResult = interactiveDisplayRangeQC(images, cfg, binFolder, ch);
                if ("cancel".equals(displayRangeResult)) return "cancel";
                if ("back".equals(displayRangeResult)) return "back";
            }

            // ── StarDist 3D branch: replaces threshold + particle size steps ──
            if (doObjTh || doSz) {
                String objectQcResult = interactiveSegmentationObjectQC(images, cfg, binFolder, ch, false);
                if ("cancel".equals(objectQcResult)) return "cancel";
                if ("back".equals(objectQcResult)) return "back";

                String currentMethod = ch < cfg.segmentationMethods.size()
                        ? safe(cfg.segmentationMethods.get(ch))
                        : "classical";
                boolean currentAi = currentMethod.startsWith("stardist")
                        || currentMethod.startsWith("cellpose");
                if (doRoiIntensityTh && currentAi) {
                    String thresholdResult = interactiveChannelThresholdQC(images, cfg, binFolder, ch);
                    if ("cancel".equals(thresholdResult)) return "cancel";
                    if ("back".equals(thresholdResult)) return "back";
                }
                continue;
            }

            boolean isStarDist = cfg.segmentationMethods.get(ch).startsWith("stardist");
            boolean isCellpose = cfg.segmentationMethods.get(ch).startsWith("cellpose");
            if (isStarDist && (doObjTh || doSz)) {
                if (useEmbeddedStarDistParameterStage()) {
                    String starDistResult = runEmbeddedStarDistParameterQC(images, cfg, binFolder, ch);
                    if ("cancel".equals(starDistResult)) return "cancel";
                    if ("back".equals(starDistResult)) return "back";
                    if (!doRoiIntensityTh) continue;
                } else {
                if (!gateStarDistFeature("StarDist 3D preview")) {
                    return "back";
                }
                // Parse existing StarDist parameters from segmentation method
                double probThresh = BinConfig.DEFAULT_STARDIST_PROB_THRESH;
                double nmsThresh = BinConfig.DEFAULT_STARDIST_NMS_THRESH;
                double linkingMaxDistance = BinConfig.DEFAULT_STARDIST_LINKING_MAX_DISTANCE;
                double gapClosingMaxDistance = BinConfig.DEFAULT_STARDIST_GAP_CLOSING_MAX_DISTANCE;
                int maxFrameGap = BinConfig.DEFAULT_STARDIST_MAX_FRAME_GAP;
                double areaMin = 0;
                double areaMax = Double.POSITIVE_INFINITY;
                double qualityMin = 0;
                double intensityMin = 0;
                String existingMethod = cfg.segmentationMethods.get(ch);
                if (existingMethod.startsWith("stardist:")) {
                    String[] sdParts = existingMethod.split(":");
                    String savedContext = "channel " + (ch + 1) + " StarDist ";
                    if (sdParts.length >= 2) {
                        probThresh = parseSavedSegmentationDouble(sdParts[1], probThresh,
                                savedContext + "probability threshold");
                    }
                    if (sdParts.length >= 3) {
                        nmsThresh = parseSavedSegmentationDouble(sdParts[2], nmsThresh,
                                savedContext + "NMS threshold");
                    }
                    // Parse key=value pairs (tracking params + post-detection filters).
                    for (int p = 3; p < sdParts.length; p++) {
                        if (sdParts[p].startsWith("linking=")) {
                            linkingMaxDistance = parseSavedSegmentationNonNegative(
                                    sdParts[p].substring(8), linkingMaxDistance,
                                    savedContext + "linking distance");
                        } else if (sdParts[p].startsWith("gapClosing=")) {
                            gapClosingMaxDistance = parseSavedSegmentationNonNegative(
                                    sdParts[p].substring(11), gapClosingMaxDistance,
                                    savedContext + "gap-closing distance");
                        } else if (sdParts[p].startsWith("frameGap=")) {
                            maxFrameGap = parseSavedSegmentationFrameGap(
                                    sdParts[p].substring(9), maxFrameGap,
                                    savedContext + "frame gap");
                        } else if (sdParts[p].startsWith("area=")) {
                            String val = sdParts[p].substring(5);
                            String[] range = val.split("-", 2);
                            areaMin = parseSavedSegmentationDouble(range[0], areaMin,
                                    savedContext + "minimum area");
                            if (range.length > 1) {
                                areaMax = "Infinity".equalsIgnoreCase(range[1])
                                        ? Double.POSITIVE_INFINITY
                                        : parseSavedSegmentationDouble(range[1], areaMax,
                                        savedContext + "maximum area");
                            }
                        } else if (sdParts[p].startsWith("quality=")) {
                            qualityMin = parseSavedSegmentationDouble(sdParts[p].substring(8),
                                    qualityMin, savedContext + "minimum quality");
                        } else if (sdParts[p].startsWith("intensity=")) {
                            intensityMin = parseSavedSegmentationDouble(sdParts[p].substring(10),
                                    intensityMin, savedContext + "minimum intensity");
                        }
                    }
                }

                // sdParams:
                // [0]=prob, [1]=nms, [2]=linkingMaxDistance, [3]=gapClosingMaxDistance, [4]=maxFrameGap
                // [5]=areaMin, [6]=areaMax, [7]=qualityMin, [8]=intensityMin
                final double[] sdParams = new double[]{
                        probThresh, nmsThresh, linkingMaxDistance, gapClosingMaxDistance, maxFrameGap,
                        areaMin, areaMax, qualityMin, intensityMin
                };
                int imgIdx = 0;
                while (imgIdx < images.size()) {
                    QcImageSelection imageSelection = images.get(imgIdx);
                    ImagePlus imp = imageSelection.image;
                    ImagePlus chDup = new Duplicator().run(imp, channelNum, channelNum,
                            1, imp.getNSlices(), 1, imp.getNFrames());

                    // Show the filtered channel as an automatic preview
                    String sdFilterContent = resolveFilterContent(binFolder, cfg, ch);
                    ImagePlus filteredPreview = chDup.duplicate();
                    filteredPreview.setTitle("StarDist QC | Filtered | " + chLabel + " | " + imp.getTitle());
                    if (sdFilterContent != null) {
                        FilterExecutor.runThreadSafe(filteredPreview, sdFilterContent);
                    }
                    filteredPreview.show();
                    IJ.run(filteredPreview, toLutName(cfg.colors.get(ch)), "");
                    positionImageLeft(filteredPreview);

                    PipelineDialog sdDialog = new PipelineDialog(
                            "StarDist 3D Parameters \u2014 C" + channelNum + " (" + cfg.names.get(ch) + ")");
                    installWizardCancelHook(sdDialog);
                    sdDialog.setModal(false);
                    sdDialog.enableBackButton();
                    sdDialog.setPrimaryButtonText("Lock in");
                    sdDialog.addAnalysisHelpHeader("Set Up Configuration", FLASH_Pipeline.IDX_CREATE_BIN);
                    sdDialog.addSetupHelpSubHeader("Detection", SetupHelpCatalog.STARDIST);
                    sdDialog.addMessage("Image " + (imgIdx + 1) + "/" + images.size() + ": " + imp.getTitle());
                    sdDialog.addMessage("The filtered channel is shown on the left (" + cfg.filterPresets.get(ch) + " filter).");
                    final JTextField probField = sdDialog.addNumericField("Probability Threshold", sdParams[0], 2);
                    sdDialog.addHelpText("Probability Threshold: minimum StarDist confidence required to keep a detection. Higher values reject weaker objects and usually return fewer detections.");
                    final JTextField nmsField = sdDialog.addNumericField("NMS Threshold", sdParams[1], 2);
                    sdDialog.addHelpText("NMS Threshold: overlap tolerance when two detections compete for the same object. Lower values suppress nearby duplicates more aggressively; higher values allow more overlap in crowded regions.");

                    sdDialog.addSpacer(8);
                    sdDialog.addHeader("3D Linking");
                    final JTextField linkingField = sdDialog.addNumericField("Linking Max Distance", sdParams[2], 1);
                    sdDialog.addHelpText("Linking Max Distance: maximum centroid movement allowed between neighbouring Z-slices when building one 3D object. Increase only if the same object shifts noticeably slice to slice.");
                    final JTextField gapClosingField = sdDialog.addNumericField("Gap-Closing Max Distance", sdParams[3], 1);
                    sdDialog.addHelpText("Gap-Closing Max Distance: maximum centroid movement allowed when StarDist has to reconnect an object across a missing slice.");
                    final JTextField frameGapField = sdDialog.addNumericField("Max Frame Gap", sdParams[4], 0);
                    sdDialog.addHelpText("Max Frame Gap: number of missing Z-slices that may be skipped during linking. 0 means detections must appear in adjacent slices only.");

                    sdDialog.addSpacer(8);
                    sdDialog.addHeader("Post-Detection Filters");
                    sdDialog.addHelpText("Filter detected objects by their properties. Set to 0 to disable a filter.");
                    final JTextField areaMinField = sdDialog.addNumericField("Min Area", sdParams[5], 1);
                    sdDialog.addHelpText("Min Area: removes tiny linked objects after segmentation. Increase this to discard specks and obvious debris.");
                    final JTextField areaMaxField = sdDialog.addNumericField("Max Area (0 = no limit)", Double.isInfinite(sdParams[6]) ? 0 : sdParams[6], 1);
                    sdDialog.addHelpText("Max Area: removes abnormally large merged objects. Leave at 0 to disable the upper size limit.");
                    final JTextField qualityMinField = sdDialog.addNumericField("Min Quality", sdParams[7], 2);
                    sdDialog.addHelpText("Min Quality: removes detections with low StarDist/TrackMate quality scores. Higher values are stricter.");
                    final JTextField intensityMinField = sdDialog.addNumericField("Min Mean Intensity", sdParams[8], 1);
                    sdDialog.addHelpText("Min Mean Intensity: removes dim objects based on their mean signal in this channel. Increase it to suppress weak background detections.");
                    final JTextField[] starDistNumericFields = new JTextField[]{
                            probField, nmsField, linkingField, gapClosingField, frameGapField,
                            areaMinField, areaMaxField, qualityMinField, intensityMinField
                    };
                    JLabel starDistNumericHint = sdDialog.addHelpText("");
                    bindValidation(sdDialog, starDistNumericFields, new ValidationCheck() {
                        @Override public boolean isValid() {
                            return areValidNumericFields(starDistNumericFields);
                        }
                    }, starDistNumericHint, "Enter numeric values for all StarDist parameters.");

                    // Preview button — runs StarDist on the filtered image with current params
                    JButton previewBtn = sdDialog.addFooterButton("Run StarDist Preview");
                    addSkipCurrentImageButton(sdDialog);
                    final ImagePlus previewSource = chDup;
                    final String previewFilterContent = sdFilterContent;
                    final ImagePlus previewAnchor = filteredPreview;
                    final String previewChannelName = cfg.names.get(ch);
                    previewBtn.addActionListener(new java.awt.event.ActionListener() {
                        @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                            if (!previewBtn.isEnabled()) return;

                            final double previewProb = parsePreviewNumber(probField, sdParams[0]);
                            final double previewNms = parsePreviewNumber(nmsField, sdParams[1]);
                            final double previewLinkingMaxDistance = sanitizeNonNegative(parsePreviewNumber(linkingField, sdParams[2]));
                            final double previewGapClosingMaxDistance = sanitizeNonNegative(parsePreviewNumber(gapClosingField, sdParams[3]));
                            final int previewMaxFrameGap = sanitizeFrameGap(parsePreviewNumber(frameGapField, sdParams[4]));
                            final double previewAreaMin = parsePreviewNumber(areaMinField, sdParams[5]);
                            final double previewAreaMaxRaw = parsePreviewNumber(areaMaxField,
                                    Double.isInfinite(sdParams[6]) ? 0 : sdParams[6]);
                            final double previewAreaMax = previewAreaMaxRaw <= 0
                                    ? Double.POSITIVE_INFINITY : previewAreaMaxRaw;
                            final double previewQualityMin = parsePreviewNumber(qualityMinField, sdParams[7]);
                            final double previewIntensityMin = parsePreviewNumber(intensityMinField, sdParams[8]);

                            closePreviewImagesByPrefix("StarDist 3D Preview");
                            previewBtn.setEnabled(false);
                            previewBtn.setText("Running...");

                            Thread previewThread = new Thread(new Runnable() {
                                @Override public void run() {
                                    ImagePlus previewDup = null;
                                    try {
                                        previewDup = previewSource.duplicate();
                                        previewDup.setTitle("StarDist 3D Preview");
                                        if (previewFilterContent != null) {
                                            FilterExecutor.runThreadSafe(previewDup, previewFilterContent);
                                        }

                                        ImagePlus labelImg = StarDist3DRunner.run(previewDup,
                                                previewProb, previewNms, null,
                                                previewLinkingMaxDistance, previewGapClosingMaxDistance, previewMaxFrameGap,
                                                previewAreaMin, previewAreaMax,
                                                previewQualityMin, previewIntensityMin,
                                                SegmentationMethod.starDistModelKey(
                                                        SegmentationTokenParser.parseLenient(existingMethod)),
                                                projectRootForConfigurationDir(binFolder));

                                        final ImagePlus previewLabel = labelImg;
                                        SwingUtilities.invokeLater(new Runnable() {
                                            @Override public void run() {
                                                if (previewLabel != null) {
                                                    int nObjects = StarDist3DRunner.countLabels(previewLabel);
                                                    showSegmentationPreviewLabel(
                                                            previewLabel,
                                                            "StarDist 3D Preview \u2014 Label Image",
                                                            previewAnchor,
                                                            nObjects);
                                                    IJ.log("  StarDist 3D Preview: " + nObjects + " objects detected");
                                                } else {
                                                    IJ.showMessage("StarDist 3D Preview", "StarDist 3D returned no result.");
                                                }
                                            }
                                        });
                                    } catch (final Exception ex) {
                                        IJ.log("WARNING: StarDist 3D preview failed for channel='"
                                                + previewChannelName + "', source='"
                                                + previewSource.getTitle() + "', probThresh="
                                                + previewProb + ", nmsThresh=" + previewNms
                                                + ": " + exceptionSummary(ex));
                                        SwingUtilities.invokeLater(new Runnable() {
                                            @Override public void run() {
                                                IJ.showMessage("StarDist 3D Preview",
                                                        "Preview failed for channel " + previewChannelName
                                                                + ".\nCheck the log for details.");
                                            }
                                        });
                                    } finally {
                                        if (previewDup != null) {
                                            previewDup.changes = false;
                                            previewDup.close();
                                            previewDup.flush();
                                        }
                                        SwingUtilities.invokeLater(new Runnable() {
                                            @Override public void run() {
                                                previewBtn.setEnabled(true);
                                                previewBtn.setText("Run StarDist Preview");
                                            }
                                        });
                                    }
                                }
                            }, "stardist-preview");
                            previewThread.setDaemon(true);
                            previewThread.start();
                        }
                    });

                    if (!sdDialog.showDialog()) {
                        filteredPreview.changes = false;
                        filteredPreview.close();
                        filteredPreview.flush();
                        chDup.changes = false;
                        chDup.close();
                        chDup.flush();
                        closePreviewImagesByPrefix("StarDist 3D Preview");
                        if (isSkipCurrentImageAction(sdDialog)) {
                            imgIdx++;
                            continue;
                        }
                        if (sdDialog.wasBackPressed()) {
                            sdParams[0] = sdDialog.getNextNumber();
                            sdParams[1] = sdDialog.getNextNumber();
                            sdParams[2] = sanitizeNonNegative(sdDialog.getNextNumber());
                            sdParams[3] = sanitizeNonNegative(sdDialog.getNextNumber());
                            sdParams[4] = sanitizeFrameGap(sdDialog.getNextNumber());
                            sdParams[5] = sdDialog.getNextNumber();
                            double rawAreaMax = sdDialog.getNextNumber();
                            sdParams[6] = rawAreaMax <= 0 ? Double.POSITIVE_INFINITY : rawAreaMax;
                            sdParams[7] = sdDialog.getNextNumber();
                            sdParams[8] = sdDialog.getNextNumber();
                            continue;
                        }
                        return "cancel";
                    }

                    sdParams[0] = sdDialog.getNextNumber();
                    sdParams[1] = sdDialog.getNextNumber();
                    sdParams[2] = sanitizeNonNegative(sdDialog.getNextNumber()); // linkingMaxDistance
                    sdParams[3] = sanitizeNonNegative(sdDialog.getNextNumber()); // gapClosingMaxDistance
                    sdParams[4] = sanitizeFrameGap(sdDialog.getNextNumber()); // maxFrameGap
                    sdParams[5] = sdDialog.getNextNumber(); // areaMin
                    double rawAreaMax = sdDialog.getNextNumber(); // areaMax (0 = no limit)
                    sdParams[6] = rawAreaMax <= 0 ? Double.POSITIVE_INFINITY : rawAreaMax;
                    sdParams[7] = sdDialog.getNextNumber(); // qualityMin
                    sdParams[8] = sdDialog.getNextNumber(); // intensityMin

                    // Clean up preview images
                    filteredPreview.changes = false;
                    filteredPreview.close();
                    filteredPreview.flush();
                    chDup.changes = false;
                    chDup.close();
                    chDup.flush();

                    // Close any lingering StarDist preview label images
                    closePreviewImagesByPrefix("StarDist 3D Preview");

                    // Build summary for continue/restart dialog
                    StringBuilder summary = new StringBuilder();
                    summary.append("Prob: ").append(sdParams[0]).append("  NMS: ").append(sdParams[1]);
                    summary.append("\nLinking: ").append(sdParams[2])
                            .append("  Gap-closing: ").append(sdParams[3])
                            .append("  Frame gap: ").append((int) sdParams[4]);
                    if (sdParams[5] > 0 || Double.isFinite(sdParams[6])) {
                        summary.append("\nArea: ").append(sdParams[5]).append(" - ")
                                .append(Double.isInfinite(sdParams[6]) ? "no limit" : sdParams[6]);
                    }
                    if (sdParams[7] > 0) summary.append("\nMin Quality: ").append(sdParams[7]);
                    if (sdParams[8] > 0) summary.append("\nMin Intensity: ").append(sdParams[8]);

                    String action = showContinueRestartDialog(
                            "StarDist 3D Parameters \u2014 " + chLabel,
                            summary.toString(),
                            imgIdx, images.size());
                    if ("cancel".equals(action)) return "cancel";
                    if ("skip".equals(action)) break;
                    if ("restart".equals(action)) { imgIdx = 0; continue; }
                    imgIdx++;
                }

                // Store StarDist parameters with filter key-value pairs
                StringBuilder sdMethod = new StringBuilder();
                sdMethod.append("stardist:").append(sdParams[0]).append(":").append(sdParams[1]);
                if (sdParams[2] != BinConfig.DEFAULT_STARDIST_LINKING_MAX_DISTANCE) {
                    sdMethod.append(":linking=").append(sdParams[2]);
                }
                if (sdParams[3] != BinConfig.DEFAULT_STARDIST_GAP_CLOSING_MAX_DISTANCE) {
                    sdMethod.append(":gapClosing=").append(sdParams[3]);
                }
                if ((int) sdParams[4] != BinConfig.DEFAULT_STARDIST_MAX_FRAME_GAP) {
                    sdMethod.append(":frameGap=").append((int) sdParams[4]);
                }
                if (sdParams[5] > 0 || Double.isFinite(sdParams[6])) {
                    sdMethod.append(":area=").append(sdParams[5]).append("-")
                            .append(Double.isInfinite(sdParams[6]) ? "Infinity" : String.valueOf(sdParams[6]));
                }
                if (sdParams[7] > 0) sdMethod.append(":quality=").append(sdParams[7]);
                if (sdParams[8] > 0) sdMethod.append(":intensity=").append(sdParams[8]);
                cfg.segmentationMethods.set(ch, sdMethod.toString());
                cfg.objectThresholds.set(ch, "default");
                cfg.sizes.set(ch, "0-Infinity");

                // If the user did not also opt into the channel-threshold step,
                // skip the unified Channel Threshold QC for this stardist channel.
                if (!doRoiIntensityTh) continue;
                }
            }

            if (isCellpose && (doObjTh || doSz)) {
                if (useEmbeddedCellposeParameterStage()) {
                    String cellposeResult = runEmbeddedCellposeParameterQC(images, cfg, binFolder, ch);
                    if ("cancel".equals(cellposeResult)) return "cancel";
                    if ("back".equals(cellposeResult)) return "back";
                    if (!doRoiIntensityTh) continue;
                } else {
                if (!gateCellposeFeature("Cellpose segmentation")) {
                    return "back";
                }
                final CellposeRuntime.Status[] runtimeStatus =
                        new CellposeRuntime.Status[]{CellposeRuntime.cachedStatus()};
                String existingMethod = cfg.segmentationMethods.get(ch);
                String modelToken = BinConfig.DEFAULT_CELLPOSE_MODEL;
                double diameter = BinConfig.DEFAULT_CELLPOSE_DIAMETER;
                double flowThreshold = BinConfig.DEFAULT_CELLPOSE_FLOW_THRESHOLD;
                double cellprobThreshold = BinConfig.DEFAULT_CELLPOSE_CELLPROB_THRESHOLD;
                boolean useGpu = runtimeStatus[0].ready ? runtimeStatus[0].gpuAvailable : BinConfig.DEFAULT_CELLPOSE_USE_GPU;
                int secondChannelIndex = -1;
                if (existingMethod.startsWith("cellpose:")) {
                    String[] cpParts = existingMethod.split(":");
                    String savedContext = "channel " + (ch + 1) + " Cellpose ";
                    if (cpParts.length >= 2) {
                        diameter = parseSavedSegmentationDouble(cpParts[1], diameter,
                                savedContext + "diameter");
                    }
                    if (cpParts.length >= 3 && cpParts[2] != null && !cpParts[2].trim().isEmpty()) {
                        modelToken = cpParts[2].trim();
                    }
                    if (cpParts.length >= 4) {
                        flowThreshold = parseSavedSegmentationDouble(cpParts[3], flowThreshold,
                                savedContext + "flow threshold");
                    }
                    if (cpParts.length >= 5) {
                        cellprobThreshold = parseSavedSegmentationDouble(cpParts[4], cellprobThreshold,
                                savedContext + "cell probability threshold");
                    }
                    for (int p = 5; p < cpParts.length; p++) {
                        if (cpParts[p].startsWith("gpu=")) {
                            useGpu = !"false".equalsIgnoreCase(cpParts[p].substring(4));
                        }
                    }
                    secondChannelIndex = parseCellposeSecondChannel(existingMethod);
                }
                if (secondChannelIndex < 0 || secondChannelIndex >= cfg.names.size() || secondChannelIndex == ch) {
                    secondChannelIndex = -1;
                }

                final double[] cpNumericParams = new double[]{diameter, flowThreshold, cellprobThreshold};
                final boolean[] cpGpuParam = new boolean[]{useGpu};
                final String[] cpModelParam = new String[]{CellposeModel.fromToken(modelToken).displayName()};
                final int[] cpSecondChannelParam = new int[]{secondChannelIndex};
                final LinkedHashMap<String, Integer> companionChoices = buildCellposeCompanionChoices(cfg.names, ch);
                int imgIdx = 0;
                while (imgIdx < images.size()) {
                    QcImageSelection imageSelection = images.get(imgIdx);
                    ImagePlus imp = imageSelection.image;
                    ImagePlus chDup = new Duplicator().run(imp, channelNum, channelNum,
                            1, imp.getNSlices(), 1, imp.getNFrames());

                    String cpFilterContent = resolveFilterContent(binFolder, cfg, ch);
                    ImagePlus filteredPreview = chDup.duplicate();
                    filteredPreview.setTitle("Cellpose QC | Filtered | " + chLabel + " | " + imp.getTitle());
                    if (cpFilterContent != null) {
                        FilterExecutor.runThreadSafe(filteredPreview, cpFilterContent);
                    }
                    filteredPreview.show();
                    IJ.run(filteredPreview, toLutName(cfg.colors.get(ch)), "");
                    positionImageLeft(filteredPreview);

                    PipelineDialog cpDialog = new PipelineDialog(
                            "Cellpose Parameters \u2014 C" + channelNum + " (" + cfg.names.get(ch) + ")");
                    installWizardCancelHook(cpDialog);
                    cpDialog.setModal(false);
                    cpDialog.enableBackButton();
                    cpDialog.setPrimaryButtonText("Lock in");
                    cpDialog.addAnalysisHelpHeader("Set Up Configuration", FLASH_Pipeline.IDX_CREATE_BIN);
                    cpDialog.addSetupHelpSubHeader("Built-in Model", SetupHelpCatalog.CELLPOSE);
                    cpDialog.addMessage("Image " + (imgIdx + 1) + "/" + images.size() + ": " + imp.getTitle());
                    cpDialog.addMessage("The filtered channel is shown on the left (" + cfg.filterPresets.get(ch) + " filter).");
                    JComboBox<String> modelChoice = cpDialog.addChoice(
                            "Cellpose Model", CellposeModel.displayNames(), cpModelParam[0]);
                    final JLabel modelDesc = cpDialog.addHelpText(
                            CellposeModel.fromToken(cpModelParam[0]).description());
                    JComboBox<String> companionChoice = cpDialog.addChoice(
                            "Companion Channel",
                            companionChoices.keySet().toArray(new String[0]),
                            cellposeCompanionChoiceLabel(companionChoices, cpSecondChannelParam[0]));
                    final JLabel companionHelp = cpDialog.addHelpText(
                            "Optional nuclei guidance for cyto/cyto2/cyto3. The selected companion channel is filtered with its own preset before preview and full analysis.");
                    modelChoice.addItemListener(new java.awt.event.ItemListener() {
                        @Override public void itemStateChanged(java.awt.event.ItemEvent e) {
                            if (e.getStateChange() == java.awt.event.ItemEvent.SELECTED) {
                                modelDesc.setText(CellposeModel.fromToken((String) e.getItem()).description());
                                updateCellposeCompanionVisibility(modelChoice, companionChoice, companionHelp);
                            }
                        }
                    });
                    updateCellposeCompanionVisibility(modelChoice, companionChoice, companionHelp);

                    cpDialog.addSpacer(8);
                    cpDialog.addHeader("Detection");
                    final JTextField diameterField = cpDialog.addNumericField("Expected Diameter", cpNumericParams[0], 1);
                    cpDialog.addHelpText("Expected Diameter: approximate object diameter. Use a fixed value for reproducibility; use 0 only if you want Cellpose to estimate size automatically.");
                    final JTextField flowField = cpDialog.addNumericField("Flow Threshold", cpNumericParams[1], 2);
                    cpDialog.addHelpText("Flow Threshold: flow-error QC cutoff. Set 0 to disable this QC step entirely; above 0, larger values are more permissive and reject fewer masks.");
                    final JTextField cellprobField = cpDialog.addNumericField("Cell Probability Threshold", cpNumericParams[2], 2);
                    cpDialog.addHelpText("Cell Probability Threshold: minimum Cellpose mask probability. Higher values return fewer, more confident objects; lower values return more objects, including weaker ones.");
                    final JTextField[] cellposeNumericFields = new JTextField[]{
                            diameterField, flowField, cellprobField
                    };
                    JLabel cellposeNumericHint = cpDialog.addHelpText("");
                    bindValidation(cpDialog, cellposeNumericFields, new ValidationCheck() {
                        @Override public boolean isValid() {
                            return areValidNumericFields(cellposeNumericFields);
                        }
                    }, cellposeNumericHint, "Enter numeric values for all Cellpose parameters.");
                    ToggleSwitch gpuToggle = cpDialog.addToggle("Use GPU", cpGpuParam[0]);
                    cpDialog.addHelpText("Use GPU: runs Cellpose on a detected compatible GPU if one is available. Turn this off to force CPU. This switch does not install GPU support by itself.");
                    JButton installGpuBtn = cpDialog.addButton("Install GPU Support");
                    installGpuBtn.addActionListener(e -> {
                        if (!CellposeRuntime.isNvidiaGpuLikelyAvailable()) {
                            int warn = javax.swing.JOptionPane.showConfirmDialog(null,
                                    "No NVIDIA GPU was detected on this system (nvidia-smi is missing or failed).\n\n"
                                    + "PyTorch CUDA requires a compatible NVIDIA graphics card to function. Installing it on a system with only AMD/Intel graphics will waste ~2.5 GB of disk space and will NOT enable GPU support.\n\n"
                                    + "Are you sure you want to attempt the installation anyway?",
                                    "NVIDIA GPU Not Detected", javax.swing.JOptionPane.YES_NO_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE);
                            if (warn != javax.swing.JOptionPane.YES_OPTION) {
                                return;
                            }
                        } else {
                            int confirm = javax.swing.JOptionPane.showConfirmDialog(null,
                                    "Installing GPU support requires downloading PyTorch with CUDA, which is approximately 2.5 GB in size.\n\nDo you want to proceed?",
                                    "GPU Install", javax.swing.JOptionPane.YES_NO_OPTION);
                            if (confirm != javax.swing.JOptionPane.YES_OPTION) {
                                return;
                            }
                        }

                        installGpuBtn.setEnabled(false);
                        installGpuBtn.setText("Installing...");
                        new Thread(() -> {
                            CellposeRuntime.InstallResult res = CellposeRuntime.installManagedGpu();
                            javax.swing.SwingUtilities.invokeLater(() -> {
                                installGpuBtn.setEnabled(true);
                                installGpuBtn.setText("Install GPU Support");
                                if (res.success) {
                                    gpuToggle.setSelected(true);
                                    cpGpuParam[0] = true;
                                    IJ.showMessage("GPU Install", "GPU support installed successfully.");
                                } else {
                                    IJ.showMessage("GPU Install Failed", res.message + "\n\n" + res.details);
                                }
                            });
                        }).start();
                    });
                    cpDialog.addHelpText("Diameter uses the image calibration units when available; otherwise it is in pixels.");
                    final JLabel runtimeHelp = cpDialog.addHelpText(cellposeRuntimeSummary(runtimeStatus[0]));
                    CellposeRuntime.probeAsync().whenCompleteAsync(
                            new java.util.function.BiConsumer<CellposeRuntime.Status, Throwable>() {
                                @Override public void accept(CellposeRuntime.Status status, Throwable throwable) {
                                    if (throwable == null && status != null) {
                                        runtimeStatus[0] = status;
                                        runtimeHelp.setText(cellposeRuntimeSummary(status));
                                    }
                                }
                            },
                            SwingUtilities::invokeLater);

                    JButton previewBtn = cpDialog.addFooterButton("Run Cellpose Preview");
                    addSkipCurrentImageButton(cpDialog);
                    final ImagePlus previewSource = chDup;
                    final String previewFilterContent = cpFilterContent;
                    final String previewChannelName = cfg.names.get(ch);
                    final ToggleSwitch previewGpuToggle = gpuToggle;
                    final ImagePlus previewAnchor = filteredPreview;
                    previewBtn.addActionListener(new java.awt.event.ActionListener() {
                        @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                            if (!previewBtn.isEnabled()) return;

                            final CellposeModel previewModelConfig = CellposeModel.fromToken((String) modelChoice.getSelectedItem());
                            final String previewModel = previewModelConfig.token();
                            final double previewDiameter = sanitizeNonNegative(parsePreviewNumber(diameterField, cpNumericParams[0]));
                            final double previewFlow = parsePreviewNumber(flowField, cpNumericParams[1]);
                            final double previewCellprob = parsePreviewNumber(cellprobField, cpNumericParams[2]);
                            final boolean previewUseGpu = previewGpuToggle.isSelected();
                            final int previewSecondChannelIndex = previewModelConfig.supportsSecondChannel()
                                    ? selectedCellposeCompanionIndex(companionChoices, companionChoice.getSelectedItem())
                                    : -1;
                            final String previewCompanionFilterContent =
                                    previewSecondChannelIndex >= 0 && previewSecondChannelIndex < cfg.names.size()
                                            ? resolveFilterContent(binFolder, cfg, previewSecondChannelIndex)
                                            : null;

                            closePreviewImagesByPrefix("Cellpose Preview");
                            previewBtn.setEnabled(false);
                            previewBtn.setText("Running...");

                            Thread previewThread = new Thread(new Runnable() {
                                @Override public void run() {
                                    ImagePlus previewDup = null;
                                    ImagePlus previewCompanionDup = null;
                                    try {
                                        previewDup = previewSource.duplicate();
                                        previewDup.setTitle("Cellpose Preview");
                                        if (previewFilterContent != null) {
                                            FilterExecutor.runThreadSafe(previewDup, previewFilterContent);
                                        }

                                        if (previewSecondChannelIndex >= 0 && previewSecondChannelIndex < cfg.names.size()) {
                                            previewCompanionDup = new Duplicator().run(imp,
                                                    previewSecondChannelIndex + 1, previewSecondChannelIndex + 1,
                                                    1, imp.getNSlices(), 1, imp.getNFrames());
                                            if (previewCompanionDup != null) {
                                                previewCompanionDup.setTitle("Cellpose Preview Companion");
                                                if (previewCompanionFilterContent != null) {
                                                    FilterExecutor.runThreadSafe(previewCompanionDup, previewCompanionFilterContent);
                                                }
                                            }
                                        }

                                        ImagePlus labelImg = Cellpose3DRunner.run(previewDup, previewCompanionDup, previewModel,
                                                previewDiameter, previewFlow, previewCellprob,
                                                previewUseGpu, previewChannelName,
                                                projectRootForConfigurationDir(binFolder));

                                        final ImagePlus previewLabel = labelImg;
                                        SwingUtilities.invokeLater(new Runnable() {
                                            @Override public void run() {
                                                if (previewLabel != null) {
                                                    int nObjects = Cellpose3DRunner.countLabels(previewLabel);
                                                    showSegmentationPreviewLabel(
                                                            previewLabel,
                                                            "Cellpose Preview \u2014 Label Image",
                                                            previewAnchor,
                                                            nObjects);
                                                    IJ.log("  Cellpose Preview: " + nObjects + " objects detected");
                                                } else {
                                                    IJ.showMessage("Cellpose Preview", "Cellpose returned no result.");
                                                }
                                            }
                                        });
                                    } catch (final Exception ex) {
                                        IJ.log("WARNING: Cellpose preview failed for channel='"
                                                + previewChannelName + "', source='"
                                                + previewSource.getTitle() + "', model='"
                                                + previewModel + "', diameter=" + previewDiameter
                                                + ", flowThreshold=" + previewFlow
                                                + ", cellprobThreshold=" + previewCellprob
                                                + ", gpu=" + previewUseGpu + ": "
                                                + exceptionSummary(ex));
                                        SwingUtilities.invokeLater(new Runnable() {
                                            @Override public void run() {
                                                IJ.showMessage("Cellpose Preview",
                                                        "Preview failed for channel " + previewChannelName
                                                                + ".\nCheck the log for details.");
                                            }
                                        });
                                    } finally {
                                        if (previewDup != null) {
                                            previewDup.changes = false;
                                            previewDup.close();
                                            previewDup.flush();
                                        }
                                        if (previewCompanionDup != null) {
                                            previewCompanionDup.changes = false;
                                            previewCompanionDup.close();
                                            previewCompanionDup.flush();
                                        }
                                        SwingUtilities.invokeLater(new Runnable() {
                                            @Override public void run() {
                                                previewBtn.setEnabled(true);
                                                previewBtn.setText("Run Cellpose Preview");
                                            }
                                        });
                                    }
                                }
                            }, "cellpose-preview");
                            previewThread.setDaemon(true);
                            previewThread.start();
                        }
                    });

                    if (!cpDialog.showDialog()) {
                        filteredPreview.changes = false;
                        filteredPreview.close();
                        filteredPreview.flush();
                        chDup.changes = false;
                        chDup.close();
                        chDup.flush();
                        closePreviewImagesByPrefix("Cellpose Preview");
                        if (isSkipCurrentImageAction(cpDialog)) {
                            imgIdx++;
                            continue;
                        }
                        if (cpDialog.wasBackPressed()) {
                            cpModelParam[0] = cpDialog.getNextChoice();
                            cpSecondChannelParam[0] = selectedCellposeCompanionIndex(companionChoices, cpDialog.getNextChoice());
                            cpNumericParams[0] = sanitizeNonNegative(cpDialog.getNextNumber());
                            cpNumericParams[1] = cpDialog.getNextNumber();
                            cpNumericParams[2] = cpDialog.getNextNumber();
                            cpGpuParam[0] = cpDialog.getNextBoolean();
                            continue;
                        }
                        return "cancel";
                    }

                    cpModelParam[0] = cpDialog.getNextChoice();
                    cpSecondChannelParam[0] = selectedCellposeCompanionIndex(companionChoices, cpDialog.getNextChoice());
                    cpNumericParams[0] = sanitizeNonNegative(cpDialog.getNextNumber());
                    cpNumericParams[1] = cpDialog.getNextNumber();
                    cpNumericParams[2] = cpDialog.getNextNumber();
                    cpGpuParam[0] = cpDialog.getNextBoolean();

                    filteredPreview.changes = false;
                    filteredPreview.close();
                    filteredPreview.flush();
                    chDup.changes = false;
                    chDup.close();
                    chDup.flush();

                    closePreviewImagesByPrefix("Cellpose Preview");

                    String companionSummary = CellposeModel.fromToken(cpModelParam[0]).supportsSecondChannel()
                            ? cellposeCompanionChoiceLabel(companionChoices, cpSecondChannelParam[0])
                            : "None";
                    String summary = "Model: " + cpModelParam[0]
                            + "\nCompanion channel: " + companionSummary
                            + "\nDiameter: " + cpNumericParams[0]
                            + "\nFlow threshold: " + cpNumericParams[1]
                            + "\nCell probability threshold: " + cpNumericParams[2]
                            + "\nUse GPU: " + cpGpuParam[0];
                    String action = showContinueRestartDialog(
                            "Cellpose Parameters \u2014 " + chLabel,
                            summary,
                            imgIdx, images.size());
                    if ("cancel".equals(action)) return "cancel";
                    if ("skip".equals(action)) break;
                    if ("restart".equals(action)) { imgIdx = 0; continue; }
                    imgIdx++;
                }

                cfg.segmentationMethods.set(ch,
                        buildCellposeMethod(
                                cpNumericParams[0],
                                cpModelParam[0],
                                cpNumericParams[1],
                                cpNumericParams[2],
                                cpGpuParam[0],
                                cpSecondChannelParam[0]));
                cfg.objectThresholds.set(ch, "default");
                cfg.sizes.set(ch, "100-Infinity");
                // If the user did not also opt into the channel-threshold step,
                // skip the unified Channel Threshold QC for this cellpose channel.
                if (!doRoiIntensityTh) continue;
                }
            }

            // \u2500\u2500 Step 3: Channel Threshold QC \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
            // Single threshold input per channel; populates both
            // cfg.objectThresholds and
            // cfg.intensityThresholds (line 6) so the on-disk 6-line
            // format and downstream analyses see the same value.
            if (doRoiIntensityTh) {
                String thresholdResult = interactiveChannelThresholdQC(images, cfg, binFolder, ch);
                if ("cancel".equals(thresholdResult)) return "cancel";
                if ("back".equals(thresholdResult)) return "back";
            }

            // Particle size QC (below) only applies to classical channels.
            if (isStarDist || isCellpose) continue;

            // ── Step 4: Particle Size with 3D Objects Counter preview ──
            if (doSz) {
                if (useEmbeddedParticleSizeStage()) {
                    String sizeResult = interactiveParticleSizeQC(images, cfg, binFolder, ch);
                    if ("cancel".equals(sizeResult)) return "cancel";
                    if ("back".equals(sizeResult)) return "back";
                } else {
                if (!gateObjectsCounterFeature("3D Objects Counter preview")) {
                    return "back";
                }
                int imgIdx = 0;
                while (imgIdx < images.size()) {
                    boolean imageAccepted = false;
                    boolean skipCurrentImage = false;
                    while (!imageAccepted) {
                        QcImageSelection imageSelection = images.get(imgIdx);
                        ImagePlus imp = imageSelection.image;
                        ImagePlus dup = new Duplicator().run(imp, channelNum, channelNum,
                                1, imp.getNSlices(), 1, imp.getNFrames());
                        dup.setTitle("Particle Size QC | " + chLabel + " | " + imp.getTitle());

                        // Apply custom filter
                        String filterContent = resolveFilterContent(binFolder, cfg, ch);
                        if (filterContent != null) FilterExecutor.runThreadSafe(dup, filterContent);

                        // Determine object threshold
                        String objThresh = cfg.objectThresholds.get(ch);
                        int threshValue;
                        if (!"default".equalsIgnoreCase(objThresh)) {
                            try {
                                threshValue = Integer.parseInt(objThresh);
                            } catch (NumberFormatException e) {
                                threshValue = autoThreshold(dup);
                            }
                        } else {
                            threshValue = autoThreshold(dup);
                        }

                        // Parse current size range
                        double[] curSize = parseSizeRange(cfg.sizes.get(ch));
                        int minSize = curSize != null ? (int) curSize[0] : 100;
                        int maxSize = (curSize != null && !Double.isInfinite(curSize[1]))
                                ? (int) curSize[1] : 99999999;

                        dup.show();
                        IJ.run(dup, toLutName(cfg.colors.get(ch)), "");
                        positionImageLeft(dup);
                        IJ.selectWindow(dup.getTitle());

                        ObjectsCounter3DWrapper.Result preview = runObjectsCounterPreview(
                                dup,
                                threshValue,
                                minSize,
                                maxSize,
                                "Particle size preview input | " + chLabel,
                                "Object label preview | " + chLabel);
                        ImagePlus objectsMap = preview == null ? null : preview.getObjectsMap();
                        if (objectsMap != null) {
                            // Objects maps are label images: pixel values are object IDs,
                            // so they should use a categorical label LUT, not the channel LUT.
                            applyLabelMapLut(objectsMap);
                            objectsMap.show();
                            positionImageLeft(objectsMap);
                        }

                        String sizeLabel = minSize + "-" + (maxSize >= 99999999 ? "Infinity" : String.valueOf(maxSize));
                        String particleAction = showLockInSkipDialog(
                                "Particle Size QC \u2014 " + chLabel,
                                "Review Particle Size Preview",
                                new String[]{
                                        "Image " + (imgIdx + 1) + "/" + images.size() + ": " + imp.getTitle(),
                                        "3D Objects Counter has been run with threshold " + threshValue
                                                + " and particle sizes " + sizeLabel + ".",
                                        "Review the label map, then click Lock in to set the size range or skip this image."
                                },
                                objectsMap != null ? objectsMap : dup);

                        // Cleanup preview images
                        if (objectsMap != null) { objectsMap.changes = false; objectsMap.close(); objectsMap.flush(); }
                        closeResultsWindows();
                        dup.changes = false;
                        dup.close();
                        dup.flush();

                        if (ACTION_SKIP_CURRENT_IMAGE.equals(particleAction)) {
                            skipCurrentImage = true;
                            imageAccepted = true;
                            continue;
                        }
                        if ("cancel".equals(particleAction)) return "cancel";

                        // Confirmation: particle size
                        PipelineDialog gdSize = new PipelineDialog("Particle Sizes \u2014 " + chLabel);
                        installWizardCancelHook(gdSize);
                        gdSize.enableBackButton();
                        gdSize.setPrimaryButtonText("Lock in");
                        gdSize.addAnalysisHelpHeader("Set Up Configuration", FLASH_Pipeline.IDX_CREATE_BIN);
                        gdSize.addSetupHelpSubHeader("Particle Sizes (n Voxels)",
                                SetupHelpCatalog.CLASSICAL_OBJECT_SEGMENTATION);
                        final JTextField minSizeField = gdSize.addNumericField("Min Size (n Voxels)", minSize, 0);
                        String maxStr = maxSize >= 99999999 ? "Infinity" : String.valueOf(maxSize);
                        final JTextField maxSizeField = gdSize.addStringField("Max Size (n Voxels)", maxStr, 15);
                        JLabel sizeHint = gdSize.addHelpText("");
                        ValidationCheck sizeValidation = new ValidationCheck() {
                            @Override public boolean isValid() {
                                return ParticleSizeStage.isValidSizeFields(
                                        minSizeField.getText(), maxSizeField.getText(), null);
                            }
                        };
                        bindValidation(gdSize, minSizeField, sizeValidation, sizeHint,
                                "Use min and max voxel sizes, for example 100-Infinity.");
                        bindValidation(gdSize, maxSizeField, sizeValidation, sizeHint,
                                "Use min and max voxel sizes, for example 100-Infinity.");
                        addSkipCurrentImageButton(gdSize);
                        if (!gdSize.showDialog()) {
                            if (isSkipCurrentImageAction(gdSize)) {
                                skipCurrentImage = true;
                                imageAccepted = true;
                                continue;
                            }
                            if (gdSize.wasBackPressed()) {
                                int newMinSize = (int) gdSize.getNextNumber();
                                String newMaxStr = gdSize.getNextString().trim();
                                cfg.sizes.set(ch, newMinSize + "-" + newMaxStr);
                                continue; // re-show image with 3D OC
                            }
                            return "cancel";
                        }

                        int newMinSize = (int) gdSize.getNextNumber();
                        String newMaxStr = gdSize.getNextString().trim();
                        cfg.sizes.set(ch, newMinSize + "-" + newMaxStr);
                        imageAccepted = true;
                    }

                    if (skipCurrentImage) {
                        imgIdx++;
                        continue;
                    }

                    String action = showContinueRestartDialog(
                            "Particle Size QC \u2014 " + chLabel,
                            "Particle Sizes (n Voxels): " + cfg.sizes.get(ch),
                            imgIdx, images.size());
                    if ("cancel".equals(action)) return "cancel";
                    if ("skip".equals(action)) break;
                    if ("restart".equals(action)) { imgIdx = 0; continue; }
                    imgIdx++;
                }
                }
            }
        }
        return "done";
    }

    private boolean useSequentialEmbeddedQcWorkflow() {
        return true;
    }

    private String interactiveSequentialEmbeddedQC(List<QcImageSelection> images, BinUserConfig cfg,
                                                   File binFolder, boolean[][] customSettings,
                                                   boolean resumeConfiguredSteps) {
        List<InteractiveQcStep> steps = buildInteractiveQcSteps(cfg, customSettings);
        int plannedStepCount = steps.size();
        IJ.log("Set Up QC workflow: " + plannedStepCount + " stage"
                + (plannedStepCount == 1 ? "" : "s")
                + " planned across " + (images == null ? 0 : images.size())
                + " selected image" + ((images == null || images.size() == 1) ? "" : "s") + ".");
        if (resumeConfiguredSteps) {
            steps = remainingInteractiveQcSteps(steps,
                    ChannelConfigIO.read(channelConfigSettingsDir(binFolder)), cfg);
            IJ.log("Set Up QC workflow resume: " + steps.size() + " of "
                    + plannedStepCount + " stage"
                    + (plannedStepCount == 1 ? "" : "s") + " still need setup.");
        }
        int stepIndex = 0;
        while (stepIndex < steps.size()) {
            InteractiveQcStep step = steps.get(stepIndex);
            List<String> stagePath = interactiveStagePathForChannel(steps, step.channelIndex);
            int activeStagePathIndex = interactiveStagePathIndex(steps, step);
            String stepLabel = interactiveQcStepLogLabel(cfg, step);
            IJ.log("Set Up QC stage " + (stepIndex + 1) + "/" + steps.size()
                    + " starting: " + stepLabel);
            String result = runInteractiveQcStep(
                    images, cfg, binFolder, step, stagePath, activeStagePathIndex);
            IJ.log("Set Up QC stage " + (stepIndex + 1) + "/" + steps.size()
                    + " result: " + stepLabel + " -> " + result);
            if ("cancel".equals(result)) {
                return "cancel";
            }
            if ("back".equals(result)) {
                if (stepIndex <= 0) {
                    return "back";
                }
                stepIndex--;
                continue;
            }
            IJ.log("Set Up QC stage " + (stepIndex + 1) + "/" + steps.size()
                    + " saving: " + stepLabel);
            persistInteractiveQcStep(binFolder, cfg, customSettings, step);
            IJ.log("Set Up QC stage " + (stepIndex + 1) + "/" + steps.size()
                    + " saved: " + stepLabel);
            stepIndex++;
        }
        IJ.log("Set Up QC workflow complete.");
        return "done";
    }

    private void persistInteractiveQcStep(File binFolder, BinUserConfig cfg,
                                          boolean[][] customSettings, InteractiveQcStep step) {
        if (step == null) return;
        switch (step.stage) {
            case FILTER_PARAMETERS:
                rememberSavedAt(persistIncremental(binFolder, cfg, customSettings,
                        5, wizardStepLabel(5), step.channelIndex, ChannelConfig.P_FILTER));
                break;
            case DISPLAY_RANGE:
                rememberSavedAt(persistIncremental(binFolder, cfg, customSettings,
                        5, wizardStepLabel(5), step.channelIndex, ChannelConfig.P_MINMAX));
                break;
            case CHANNEL_THRESHOLD:
                rememberSavedAt(persistChannelThresholdIncrementalWithoutObject(
                        binFolder, cfg, customSettings, step.channelIndex));
                break;
            case PARTICLE_SIZE:
                rememberSavedAt(persistIncremental(binFolder, cfg, customSettings,
                        5, wizardStepLabel(5), step.channelIndex, ChannelConfig.P_SIZE));
                break;
            case SEGMENTATION_OBJECT:
            case STARDIST_PARAMETERS:
            case CELLPOSE_PARAMETERS:
                if (step.includeAiChannelThreshold) {
                    rememberSavedAt(persistIncrementalProperties(binFolder, cfg, customSettings,
                            5, wizardStepLabel(5), step.channelIndex,
                            ChannelConfig.P_SEGMENTATION, ChannelConfig.P_THRESHOLD,
                            ChannelConfig.P_SIZE, ChannelConfig.P_INTENSITY));
                } else {
                    rememberSavedAt(persistSegmentationObjectIncrementalWithoutIntensity(
                            binFolder, cfg, customSettings, step.channelIndex));
                }
                break;
            default:
                break;
        }
    }

    private static void preservePendingValues(ChannelConfig next, ChannelConfig existing) {
        if (next == null || next.channels == null || existing == null || existing.channels == null) {
            return;
        }
        for (int i = 0; i < next.channels.size(); i++) {
            ChannelConfig.Channel channel = next.channels.get(i);
            ChannelConfig.Channel old = channelAt(existing, i);
            if (channel == null || old == null) continue;
            if (channel.statusOf(ChannelConfig.P_NAME) == ChannelConfig.PropertyStatus.PENDING) {
                channel.name = old.name;
            }
            if (channel.statusOf(ChannelConfig.P_COLOR) == ChannelConfig.PropertyStatus.PENDING) {
                channel.color = old.color;
            }
            if (channel.statusOf(ChannelConfig.P_MARKER) == ChannelConfig.PropertyStatus.PENDING) {
                channel.markerId = old.markerId;
                channel.markerShape = old.markerShape;
                channel.markerCrowdingSensitive = old.markerCrowdingSensitive;
            }
            if (channel.statusOf(ChannelConfig.P_THRESHOLD) == ChannelConfig.PropertyStatus.PENDING) {
                channel.threshold = old.threshold;
            }
            if (channel.statusOf(ChannelConfig.P_SIZE) == ChannelConfig.PropertyStatus.PENDING) {
                channel.size = old.size;
            }
            if (channel.statusOf(ChannelConfig.P_MINMAX) == ChannelConfig.PropertyStatus.PENDING) {
                channel.minmax = old.minmax;
            }
            if (channel.statusOf(ChannelConfig.P_INTENSITY) == ChannelConfig.PropertyStatus.PENDING) {
                channel.intensityThreshold = old.intensityThreshold;
            }
            if (channel.statusOf(ChannelConfig.P_SEGMENTATION) == ChannelConfig.PropertyStatus.PENDING) {
                channel.segmentationMethod = old.segmentationMethod;
            }
            if (channel.statusOf(ChannelConfig.P_FILTER) == ChannelConfig.PropertyStatus.PENDING) {
                channel.filterPreset = old.filterPreset;
            }
        }
    }

    private long persistSegmentationObjectIncrementalWithoutIntensity(
            File binFolder, BinUserConfig cfg, boolean[][] customSettings, int channelIndex) {
        List<String> draftIntensity = copyList(cfg.intensityThresholds);
        restorePersistedIntensityThresholds(binFolder, cfg);
        try {
            return persistIncrementalProperties(binFolder, cfg, customSettings,
                    5, wizardStepLabel(5), channelIndex,
                    ChannelConfig.P_SEGMENTATION, ChannelConfig.P_THRESHOLD,
                    ChannelConfig.P_SIZE);
        } finally {
            replaceList(cfg.intensityThresholds, draftIntensity);
        }
    }

    private void restorePersistedIntensityThresholds(File binFolder, BinUserConfig cfg) {
        ChannelConfig existing = ChannelConfigIO.read(channelConfigSettingsDir(binFolder));
        if (cfg == null || cfg.intensityThresholds == null) return;
        for (int i = 0; i < cfg.intensityThresholds.size(); i++) {
            ChannelConfig.Channel channel = channelAt(existing, i);
            if (channel != null) {
                cfg.intensityThresholds.set(i, value(channel.intensityThreshold, "default"));
            }
        }
    }

    private long persistChannelThresholdIncrementalWithoutObject(
            File binFolder, BinUserConfig cfg, boolean[][] customSettings, int channelIndex) {
        List<String> draftObjectThresholds = copyList(cfg.objectThresholds);
        restorePersistedObjectThresholds(binFolder, cfg);
        try {
            return persistIncrementalProperties(binFolder, cfg, customSettings,
                    5, wizardStepLabel(5), channelIndex,
                    ChannelConfig.P_INTENSITY);
        } finally {
            replaceList(cfg.objectThresholds, draftObjectThresholds);
        }
    }

    private void restorePersistedObjectThresholds(File binFolder, BinUserConfig cfg) {
        ChannelConfig existing = ChannelConfigIO.read(channelConfigSettingsDir(binFolder));
        if (cfg == null || cfg.objectThresholds == null) return;
        for (int i = 0; i < cfg.objectThresholds.size(); i++) {
            ChannelConfig.Channel channel = channelAt(existing, i);
            if (channel != null) {
                cfg.objectThresholds.set(i, value(channel.threshold, "default"));
            }
        }
    }

    private List<InteractiveQcStep> buildInteractiveQcSteps(BinUserConfig cfg,
                                                            boolean[][] customSettings) {
        List<InteractiveQcStep> steps = new ArrayList<InteractiveQcStep>();
        if (cfg == null || customSettings == null) {
            return steps;
        }
        int nChannels = cfg.names.size();
        for (int ch = 0; ch < nChannels; ch++) {
            boolean doMM = settingSelected(customSettings, SETTINGS_MIN_MAX, ch);
            if (doMM) {
                steps.add(new InteractiveQcStep(InteractiveQcStage.DISPLAY_RANGE, ch));
            }
            boolean doFilter = settingSelected(customSettings, SETTINGS_FILTER_PARAMETERS, ch)
                    || isCustomFilterPlaceholder(cfg, ch);
            if (doFilter) {
                steps.add(new InteractiveQcStep(InteractiveQcStage.FILTER_PARAMETERS, ch));
            }
            boolean doRoiIntensityTh = settingSelected(customSettings, SETTINGS_ROI_INTENSITY_THRESHOLD, ch);
            boolean doObjTh = settingSelected(customSettings, SETTINGS_OBJECT_THRESHOLD, ch);
            boolean doSz = settingSelected(customSettings, SETTINGS_OBJECT_SIZE_FILTER, ch);
            boolean doSegmentationMethod = settingSelected(customSettings, SETTINGS_SEGMENTATION_METHOD, ch);
            if (doSegmentationMethod || doObjTh || doSz) {
                steps.add(new InteractiveQcStep(
                        InteractiveQcStage.SEGMENTATION_OBJECT,
                        ch,
                        doRoiIntensityTh));
            } else if (doRoiIntensityTh) {
                steps.add(new InteractiveQcStep(InteractiveQcStage.CHANNEL_THRESHOLD, ch));
            }
        }
        return steps;
    }

    private List<InteractiveQcStep> remainingInteractiveQcSteps(List<InteractiveQcStep> steps,
                                                                ChannelConfig progress,
                                                                BinUserConfig cfg) {
        if (steps == null || steps.isEmpty() || progress == null || progress.channels == null) {
            return steps;
        }
        List<InteractiveQcStep> remaining = new ArrayList<InteractiveQcStep>();
        for (int i = 0; i < steps.size(); i++) {
            InteractiveQcStep step = steps.get(i);
            if (!isInteractiveQcStepConfigured(progress, cfg, step)) {
                remaining.add(step);
            }
        }
        return remaining;
    }

    private boolean isInteractiveQcStepConfigured(ChannelConfig progress,
                                                  BinUserConfig cfg,
                                                  InteractiveQcStep step) {
        if (step == null) {
            return false;
        }
        ChannelConfig.Channel channel = channelAt(progress, step.channelIndex);
        if (channel == null) {
            return false;
        }
        switch (step.stage) {
            case FILTER_PARAMETERS:
                return allConfigured(channel, ChannelConfig.P_FILTER);
            case DISPLAY_RANGE:
                return allConfigured(channel, ChannelConfig.P_MINMAX);
            case CHANNEL_THRESHOLD:
                return allConfigured(channel, ChannelConfig.P_INTENSITY);
            case PARTICLE_SIZE:
                return allConfigured(channel, ChannelConfig.P_SIZE);
            case SEGMENTATION_OBJECT:
            case STARDIST_PARAMETERS:
            case CELLPOSE_PARAMETERS:
                if (step.includeAiChannelThreshold) {
                    return allConfigured(channel,
                            ChannelConfig.P_SEGMENTATION,
                            ChannelConfig.P_THRESHOLD,
                            ChannelConfig.P_SIZE,
                            ChannelConfig.P_INTENSITY);
                }
                return allConfigured(channel,
                        ChannelConfig.P_SEGMENTATION,
                        ChannelConfig.P_THRESHOLD,
                        ChannelConfig.P_SIZE);
            default:
                return false;
        }
    }

    private static boolean allConfigured(ChannelConfig.Channel channel, String... propertyKeys) {
        if (channel == null || propertyKeys == null || propertyKeys.length == 0) {
            return false;
        }
        for (int i = 0; i < propertyKeys.length; i++) {
            if (!isConfigured(channel, propertyKeys[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean isConfigured(ChannelConfig.Channel channel, String propertyKey) {
        ChannelConfig.PropertyStatus status = channel == null
                ? ChannelConfig.PropertyStatus.PENDING
                : channel.statusOf(propertyKey);
        return status == ChannelConfig.PropertyStatus.CONFIGURED
                || status == ChannelConfig.PropertyStatus.COMMITTED;
    }

    private String runInteractiveQcStep(List<QcImageSelection> images, BinUserConfig cfg,
                                        File binFolder, InteractiveQcStep step) {
        switch (step.stage) {
            case FILTER_PARAMETERS:
                return interactiveFilterParameterQC(images, cfg, binFolder, step.channelIndex);
            case DISPLAY_RANGE:
                return interactiveDisplayRangeQC(images, cfg, binFolder, step.channelIndex);
            case SEGMENTATION_OBJECT:
                return interactiveSegmentationObjectQC(
                        images, cfg, binFolder, step.channelIndex, step.includeAiChannelThreshold);
            case STARDIST_PARAMETERS:
                return runEmbeddedStarDistParameterQC(images, cfg, binFolder, step.channelIndex);
            case CELLPOSE_PARAMETERS:
                return runEmbeddedCellposeParameterQC(images, cfg, binFolder, step.channelIndex);
            case CHANNEL_THRESHOLD:
                return interactiveChannelThresholdQC(images, cfg, binFolder, step.channelIndex, false);
            case PARTICLE_SIZE:
                return interactiveParticleSizeQC(images, cfg, binFolder, step.channelIndex);
            default:
                return "cancel";
        }
    }

    private String runInteractiveQcStep(List<QcImageSelection> images, BinUserConfig cfg,
                                        File binFolder, InteractiveQcStep step,
                                        List<String> stagePath, int activeStagePathIndex) {
        List<String> previousPath = activeEmbeddedStagePath;
        int previousIndex = activeEmbeddedStagePathIndex;
        activeEmbeddedStagePath = stagePath == null
                ? Collections.<String>emptyList()
                : new ArrayList<String>(stagePath);
        activeEmbeddedStagePathIndex = activeStagePathIndex;
        try {
            return runInteractiveQcStep(images, cfg, binFolder, step);
        } finally {
            activeEmbeddedStagePath = previousPath;
            activeEmbeddedStagePathIndex = previousIndex;
        }
    }

    private static List<String> interactiveStagePathForChannel(List<InteractiveQcStep> steps,
                                                               int channelIndex) {
        List<String> path = new ArrayList<String>();
        if (steps == null) {
            return path;
        }
        for (int i = 0; i < steps.size(); i++) {
            InteractiveQcStep candidate = steps.get(i);
            if (candidate != null && candidate.channelIndex == channelIndex) {
                path.add(interactiveStageLabel(candidate.stage));
            }
        }
        return path;
    }

    private static int interactiveStagePathIndex(List<InteractiveQcStep> steps,
                                                 InteractiveQcStep activeStep) {
        if (steps == null || activeStep == null) {
            return -1;
        }
        int indexForChannel = 0;
        for (int i = 0; i < steps.size(); i++) {
            InteractiveQcStep candidate = steps.get(i);
            if (candidate == null || candidate.channelIndex != activeStep.channelIndex) {
                continue;
            }
            if (candidate == activeStep) {
                return indexForChannel;
            }
            indexForChannel++;
        }
        return -1;
    }

    private static String interactiveStageLabel(InteractiveQcStage stage) {
        if (stage == null) {
            return "";
        }
        switch (stage) {
            case DISPLAY_RANGE:
                return "Display";
            case FILTER_PARAMETERS:
                return "Filter";
            case SEGMENTATION_OBJECT:
                return "Object Segmentation";
            case CHANNEL_THRESHOLD:
                return "Threshold";
            case PARTICLE_SIZE:
                return "Particle Size";
            case STARDIST_PARAMETERS:
                return "StarDist";
            case CELLPOSE_PARAMETERS:
                return "Cellpose";
            default:
                return stage.name();
        }
    }

    private static String interactiveQcStepLogLabel(BinUserConfig cfg,
                                                    InteractiveQcStep step) {
        if (step == null) return "unknown stage";
        StringBuilder sb = new StringBuilder();
        sb.append(interactiveStageLabel(step.stage));
        sb.append(" | ").append(qcChannelLabel(cfg, step.channelIndex));
        if (step.includeAiChannelThreshold) {
            sb.append(" | includes channel threshold");
        }
        return sb.toString();
    }

    private static String qcChannelLabel(BinUserConfig cfg, int channelIndex) {
        StringBuilder sb = new StringBuilder();
        sb.append("C").append(channelIndex + 1);
        if (cfg != null && cfg.names != null
                && channelIndex >= 0 && channelIndex < cfg.names.size()) {
            String name = safe(cfg.names.get(channelIndex)).trim();
            if (!name.isEmpty()) {
                sb.append(" (").append(name).append(")");
            }
        }
        return sb.toString();
    }

    private enum InteractiveQcStage {
        FILTER_PARAMETERS,
        DISPLAY_RANGE,
        SEGMENTATION_OBJECT,
        STARDIST_PARAMETERS,
        CELLPOSE_PARAMETERS,
        CHANNEL_THRESHOLD,
        PARTICLE_SIZE
    }

    private static final class InteractiveQcStep {
        private final InteractiveQcStage stage;
        private final int channelIndex;
        private final boolean includeAiChannelThreshold;

        private InteractiveQcStep(InteractiveQcStage stage, int channelIndex) {
            this(stage, channelIndex, false);
        }

        private InteractiveQcStep(InteractiveQcStage stage, int channelIndex,
                                  boolean includeAiChannelThreshold) {
            this.stage = stage;
            this.channelIndex = channelIndex;
            this.includeAiChannelThreshold = includeAiChannelThreshold;
        }
    }

    private String interactiveParticleSizeQC(List<QcImageSelection> images, BinUserConfig cfg,
                                             File binFolder, int channelIndex) {
        if (!gateObjectsCounterFeature("3D Objects Counter preview")) {
            return "back";
        }
        if (!embeddedConfigQcUiAvailable()) {
            return "cancel";
        }
        ConfigQcContext context = new ConfigQcContext(
                projectRootForConfigurationDir(binFolder),
                binFolder,
                cfg,
                filterParameterContextImages(images, channelIndex),
                cfg.names,
                channelIndex,
                setupFilteredStackCache);
        ParticleSizeStage stage = createParticleSizeStage(cfg, binFolder, channelIndex);
        ConfigQcResult result = showEmbeddedConfigQcDialog(
                context, Collections.<ConfigQcStage>singletonList(stage));
        if (result == ConfigQcResult.DONE || result == ConfigQcResult.SKIP_CURRENT_IMAGE) {
            return "continue";
        }
        if (result == ConfigQcResult.BACK) {
            return "back";
        }
        return "cancel";
    }

    private boolean useEmbeddedParticleSizeStage() {
        // Config QC stays in the owned embedded widget; legacy 3D Objects
        // Counter windows must not be selected by normal setup routing.
        return true;
    }

    ParticleSizeStage createParticleSizeStage(final BinUserConfig cfg,
                                              final File binFolder,
                                              final int channelIndex) {
        final int channelNum = channelIndex + 1;
        final String chLabel = "C" + channelNum + " (" + cfg.names.get(channelIndex) + ")";
        return new ParticleSizeStage(
                new ParticleSizeStage.SizeStore() {
                    @Override public String get() {
                        return cfg.sizes.get(channelIndex);
                    }

                    @Override public void set(String token) {
                        cfg.sizes.set(channelIndex, token);
                    }
                },
                new ParticleSizeStage.PreviewAdapter() {
                    @Override public ImagePlus createRawSource(ConfigQcContext context) {
                        ImagePlus source = duplicateCurrentChannel(context, channelNum);
                        if (source == null) return null;
                        source.setTitle("Particle size raw input | " + chLabel + " | "
                                + (context == null ? "" : context.getCurrentImageDisplayName()));
                        return applyPreviewLut(source, channelColor(cfg, channelIndex));
                    }

                    @Override public ImagePlus createFilteredSource(ConfigQcContext context) {
                        return createFilteredSetupSource(context, cfg, binFolder, channelIndex,
                                "Particle size filtered input");
                    }

                    @Override public int resolveThreshold(ImagePlus filteredSource,
                                                          ConfigQcContext context) {
                        String token = cfg.objectThresholds.get(channelIndex);
                        if (token != null && !"default".equalsIgnoreCase(token.trim())) {
                            try {
                                return (int) Math.round(Double.parseDouble(token.trim()));
                            } catch (NumberFormatException ignored) {
                                // Fall back to the same automatic threshold used by legacy QC.
                            }
                        }
                        return autoThreshold(filteredSource);
                    }

                    @Override public ObjectsCounter3DWrapper.Result runPreview(ImagePlus filteredSource,
                                                                               int threshold,
                                                                               int minSize,
                                                                               int maxSize) {
                        return runObjectsCounterPreview(
                                filteredSource,
                                threshold,
                                minSize,
                                maxSize,
                                "Particle size preview input | " + chLabel,
                                "Object label preview | " + chLabel);
                    }

                    @Override public int countObjects(ObjectsCounter3DWrapper.Result result) {
                        if (result == null || result.getStatistics() == null) return 0;
                        return result.getStatistics().size();
                    }

                    @Override public void close(ImagePlus image) {
                        closeImageQuietly(image);
                    }
                });
    }

    ClassicalSegmentationStage createClassicalSegmentationStage(final BinUserConfig cfg,
                                                                final File binFolder,
                                                                final int channelIndex) {
        return createClassicalSegmentationStage(cfg, binFolder, channelIndex, true);
    }

    private ClassicalSegmentationStage createClassicalSegmentationStage(final BinUserConfig cfg,
                                                                        final File binFolder,
                                                                        final int channelIndex,
                                                                        final boolean mirrorIntensityThreshold) {
        final int channelNum = channelIndex + 1;
        final String chLabel = "C" + channelNum + " (" + cfg.names.get(channelIndex) + ")";
        return new ClassicalSegmentationStage(
                new ClassicalSegmentationStage.ThresholdStore() {
                    @Override public String get() {
                        return cfg.objectThresholds.get(channelIndex);
                    }

                    @Override public void set(String token) {
                        cfg.objectThresholds.set(channelIndex, token);
                        if (mirrorIntensityThreshold) {
                            cfg.intensityThresholds.set(channelIndex, token);
                        }
                    }
                },
                new ClassicalSegmentationStage.SizeStore() {
                    @Override public String get() {
                        return cfg.sizes.get(channelIndex);
                    }

                    @Override public void set(String token) {
                        cfg.sizes.set(channelIndex, token);
                    }
                },
                new ClassicalSegmentationStage.PreviewAdapter() {
                    @Override public ImagePlus createRawSource(ConfigQcContext context) {
                        ImagePlus source = duplicateCurrentChannel(context, channelNum);
                        if (source == null) return null;
                        source.setTitle("Classical segmentation raw input | " + chLabel + " | "
                                + (context == null ? "" : context.getCurrentImageDisplayName()));
                        return applyPreviewLut(source, channelColor(cfg, channelIndex));
                    }

                    @Override public ImagePlus createFilteredSource(ConfigQcContext context) {
                        return createFilteredSetupSource(context, cfg, binFolder, channelIndex,
                                "Classical segmentation filtered input");
                    }

                    @Override public ObjectsCounter3DWrapper.Result runPreview(ImagePlus filteredSource,
                                                                               int threshold,
                                                                               int minSize,
                                                                               int maxSize) {
                        return runObjectsCounterPreview(
                                filteredSource,
                                threshold,
                                minSize,
                                maxSize,
                                "Classical object preview input | " + chLabel,
                                "Object label preview | " + chLabel);
                    }

                    @Override public int countObjects(ObjectsCounter3DWrapper.Result result) {
                        return countObjectsInPreview(result);
                    }

                    @Override public void close(ImagePlus image) {
                        closeImageQuietly(image);
                    }
                });
    }

    EnhancedClassicalSegmentationStage createEnhancedClassicalSegmentationStage(final BinUserConfig cfg,
                                                                                final File binFolder,
                                                                                final int channelIndex) {
        return createEnhancedClassicalSegmentationStage(cfg, binFolder, channelIndex, true);
    }

    private EnhancedClassicalSegmentationStage createEnhancedClassicalSegmentationStage(final BinUserConfig cfg,
                                                                                       final File binFolder,
                                                                                       final int channelIndex,
                                                                                       final boolean mirrorIntensityThreshold) {
        final int channelNum = channelIndex + 1;
        final String chLabel = "C" + channelNum + " (" + cfg.names.get(channelIndex) + ")";
        return new EnhancedClassicalSegmentationStage(
                new EnhancedClassicalSegmentationStage.ParameterStore() {
                    @Override public String getMethodToken() {
                        return cfg.segmentationMethods.get(channelIndex);
                    }

                    @Override public void save(String methodToken) {
                        cfg.segmentationMethods.set(channelIndex, methodToken);
                    }
                },
                new ClassicalSegmentationStage.ThresholdStore() {
                    @Override public String get() {
                        return enhancedThresholdOrFallback(cfg, channelIndex);
                    }

                    @Override public void set(String token) {
                        cfg.objectThresholds.set(channelIndex, token);
                        if (mirrorIntensityThreshold) {
                            cfg.intensityThresholds.set(channelIndex, token);
                        }
                    }
                },
                new ClassicalSegmentationStage.SizeStore() {
                    @Override public String get() {
                        return enhancedSizeOrFallback(cfg, channelIndex);
                    }

                    @Override public void set(String token) {
                        cfg.sizes.set(channelIndex, token);
                    }
                },
                new EnhancedClassicalSegmentationStage.PreviewAdapter() {
                    @Override public ImagePlus createRawSource(ConfigQcContext context) {
                        ImagePlus source = duplicateCurrentChannel(context, channelNum);
                        if (source == null) return null;
                        source.setTitle("Enhanced Classical raw input | " + chLabel + " | "
                                + (context == null ? "" : context.getCurrentImageDisplayName()));
                        return applyPreviewLut(source, channelColor(cfg, channelIndex));
                    }

                    @Override public ImagePlus createFilteredSource(ConfigQcContext context) {
                        if (!gateObjectsCounterPlusFeature("Enhanced Classical / 3D Objects Counter+ preview")) {
                            return null;
                        }
                        return createFilteredSetupSource(context, cfg, binFolder, channelIndex,
                                "Enhanced Classical filtered input");
                    }

                    @Override public void close(ImagePlus image) {
                        closeImageQuietly(image);
                    }
                });
    }

    private ObjectsCounter3DWrapper.Result runObjectsCounterPreview(ImagePlus filteredSource,
                                                                    int threshold,
                                                                    int minSize,
                                                                    int maxSize,
                                                                    String inputTitle,
                                                                    String mapTitle) {
        if (filteredSource == null) return null;
        ImagePlus input = ImageOps.duplicateThreadSafe(filteredSource);
        input.setTitle(inputTitle);
        long started = System.currentTimeMillis();
        IJ.log("Set Up QC object preview running: " + inputTitle
                + " | threshold=" + threshold
                + " | size=" + minSize + "-" + (maxSize == Integer.MAX_VALUE ? "Infinity" : String.valueOf(maxSize)));
        try {
            ObjectsCounter3DWrapper wrapper = new ObjectsCounter3DWrapper();
            ObjectsCounter3DWrapper.Result result;
            if (ObjectsCounter3DWrapper.isMcib3dAvailable()) {
                result = wrapper.runNative(
                        input,
                        threshold,
                        minSize,
                        maxSize,
                        false,
                        null,
                        true,
                        false);
            } else {
                WindowManagerLock.LOCK.lock();
                try {
                    result = wrapper.run(
                            input,
                            threshold,
                            minSize,
                            maxSize,
                            false,
                            false,
                            true,
                            false);
                    result = detachVisibleCounterPreview(result);
                    closeResultsWindows();
                } finally {
                    closeResultsWindows();
                    WindowManagerLock.LOCK.unlock();
                }
            }
            ImagePlus map = result == null ? null : result.getObjectsMap();
            if (map != null) {
                map.setTitle(mapTitle);
            }
            IJ.log("Set Up QC object preview complete: " + inputTitle
                    + " | objects=" + countObjectsInPreview(result)
                    + " | " + (System.currentTimeMillis() - started) + " ms.");
            return result;
        } finally {
            closeImageQuietly(input);
        }
    }

    private static int countObjectsInPreview(ObjectsCounter3DWrapper.Result result) {
        if (result == null || result.getStatistics() == null) return 0;
        return result.getStatistics().size();
    }

    private ObjectsCounter3DWrapper.Result detachVisibleCounterPreview(
            ObjectsCounter3DWrapper.Result result) {
        if (result == null) return null;
        ImagePlus map = result.getObjectsMap();
        ImagePlus masked = result.getMaskedImage();
        boolean changed = false;
        if (map != null && map.getWindow() != null) {
            ImagePlus detached = map.duplicate();
            detached.setTitle(map.getTitle());
            closeImageQuietly(map);
            map = detached;
            changed = true;
            IJ.log("Warning: legacy 3D Objects Counter opened an objects-map window during config QC; closing it.");
        }
        if (masked != null && masked.getWindow() != null) {
            closeImageQuietly(masked);
            masked = null;
            changed = true;
            IJ.log("Warning: legacy 3D Objects Counter opened a masked-image window during config QC; closing it.");
        }
        return changed
                ? new ObjectsCounter3DWrapper.Result(
                result.getStatistics(), map, masked, result.isFoundObjects())
                : result;
    }

    private String interactiveDisplayRangeQC(List<QcImageSelection> images, BinUserConfig cfg,
                                             File binFolder, int channelIndex) {
        return runEmbeddedDisplayRangeQC(images, cfg, binFolder, channelIndex);
    }

    private String runEmbeddedDisplayRangeQC(List<QcImageSelection> images, BinUserConfig cfg,
                                             File binFolder, int channelIndex) {
        if (!embeddedConfigQcUiAvailable()) {
            return "cancel";
        }
        ConfigQcContext context = new ConfigQcContext(
                projectRootForConfigurationDir(binFolder),
                binFolder,
                cfg,
                filterParameterContextImages(images, channelIndex),
                cfg.names,
                channelIndex,
                setupFilteredStackCache);
        DisplayRangeStage stage = createDisplayRangeStage(cfg, channelIndex);
        ConfigQcResult result = showEmbeddedConfigQcDialog(
                context, Collections.<ConfigQcStage>singletonList(stage));
        if (result == ConfigQcResult.DONE || result == ConfigQcResult.SKIP_CURRENT_IMAGE) {
            return "continue";
        }
        if (result == ConfigQcResult.BACK) {
            return "back";
        }
        return "cancel";
    }

    DisplayRangeStage createDisplayRangeStage(final BinUserConfig cfg, final int channelIndex) {
        final int channelNum = channelIndex + 1;
        final String chLabel = "C" + channelNum + " (" + cfg.names.get(channelIndex) + ")";
        return new DisplayRangeStage(
                new DisplayRangeStage.RangeStore() {
                    @Override public String get() {
                        return cfg.minmax.get(channelIndex);
                    }

                    @Override public void set(String token) {
                        cfg.minmax.set(channelIndex, token);
                    }
                },
                new DisplayRangeStage.PreviewAdapter() {
                    @Override public ImagePlus createSource(ConfigQcContext context) {
                        ImagePlus chStack = duplicateCurrentChannel(context, channelNum);
                        if (chStack == null) return null;
                        ImagePlus projection = ZProjector.run(chStack, "max");
                        closeImageQuietly(chStack);
                        projection.setTitle("Display range source | " + chLabel + " | "
                                + (context == null ? "" : context.getCurrentImageDisplayName()));
                        return applyPreviewLut(projection, channelColor(cfg, channelIndex));
                    }

                    @Override public void close(ImagePlus image) {
                        closeImageQuietly(image);
                    }
                });
    }

    private String legacyInteractiveDisplayRangeQC(List<QcImageSelection> images, BinUserConfig cfg,
                                                   int channelIndex) {
        // Disabled legacy native ImageJ-window flow. Config QC routing calls
        // runEmbeddedDisplayRangeQC(...) directly.
        int channelNum = channelIndex + 1;
        String chLabel = "C" + channelNum + " (" + cfg.names.get(channelIndex) + ")";
        double[] range = parseMinMax(cfg.minmax.get(channelIndex));
        int imgIdx = 0;
        while (imgIdx < images.size()) {
            closeQcToolWindows();
            QcImageSelection imageSelection = images.get(imgIdx);
            ImagePlus imp = imageSelection.image;
            ImagePlus chStack = new Duplicator().run(imp, channelNum, channelNum,
                    1, imp.getNSlices(), 1, imp.getNFrames());
            ImagePlus dup = ZProjector.run(chStack, "max");
            chStack.changes = false;
            chStack.close();
            chStack.flush();
            dup.setTitle("Custom Min-Max Display Ranges | " + chLabel + " | " + imp.getTitle());

            if (range != null) dup.setDisplayRange(range[0], range[1]);
            dup.show();
            IJ.run(dup, toLutName(cfg.colors.get(channelIndex)), "");
            positionImageLeft(dup);
            IJ.run(dup, "Brightness/Contrast...", "");
            positionToolWindowNextToImage(BRIGHTNESS_CONTRAST_WINDOW_TITLES);

            String mmAction = showLockInSkipDialog(
                    "Custom Min-Max Display Ranges \u2014 " + chLabel,
                    "Adjust Display Range",
                    new String[]{
                            "Image " + (imgIdx + 1) + "/" + images.size() + ": " + imp.getTitle(),
                            "Adjust the Brightness/Contrast on this max projection.",
                            "Click Lock in to save these values for the channel, or skip this image to leave the saved range unchanged."
                    },
                    dup);
            if (ACTION_SKIP_CURRENT_IMAGE.equals(mmAction)) {
                dup.changes = false;
                dup.close();
                dup.flush();
                closeToolWindows(BRIGHTNESS_CONTRAST_WINDOW_TITLES);
                imgIdx++;
                continue;
            }
            if ("cancel".equals(mmAction)) {
                dup.changes = false;
                dup.close();
                dup.flush();
                closeToolWindows(BRIGHTNESS_CONTRAST_WINDOW_TITLES);
                return "cancel";
            }

            double newMin = dup.getDisplayRangeMin();
            double newMax = dup.getDisplayRangeMax();
            cfg.minmax.set(channelIndex, (int) newMin + "-" + (int) newMax);
            range = new double[]{newMin, newMax};

            dup.changes = false;
            dup.close();
            dup.flush();
            closeToolWindows(BRIGHTNESS_CONTRAST_WINDOW_TITLES);

            String action = showContinueRestartDialog(
                    "Custom Min-Max Display Ranges \u2014 " + chLabel,
                    "Locked: " + (int) newMin + " - " + (int) newMax,
                    imgIdx, images.size());
            if ("cancel".equals(action)) return "cancel";
            if ("skip".equals(action)) break;
            if ("restart".equals(action)) { imgIdx = 0; continue; }
            imgIdx++;
        }
        return "continue";
    }

    private String interactiveChannelThresholdQC(List<QcImageSelection> images, BinUserConfig cfg,
                                                 File binFolder, int channelIndex) {
        return interactiveChannelThresholdQC(images, cfg, binFolder, channelIndex, true);
    }

    private String interactiveChannelThresholdQC(List<QcImageSelection> images, BinUserConfig cfg,
                                                 File binFolder, int channelIndex,
                                                 boolean mirrorObjectThreshold) {
        return runEmbeddedChannelThresholdQC(images, cfg, binFolder, channelIndex, mirrorObjectThreshold);
    }

    private String runEmbeddedChannelThresholdQC(List<QcImageSelection> images, BinUserConfig cfg,
                                                File binFolder, int channelIndex,
                                                boolean mirrorObjectThreshold) {
        if (!embeddedConfigQcUiAvailable()) {
            return "cancel";
        }
        ConfigQcContext context = new ConfigQcContext(
                projectRootForConfigurationDir(binFolder),
                binFolder,
                cfg,
                filterParameterContextImages(images, channelIndex),
                cfg.names,
                channelIndex,
                setupFilteredStackCache);
        ChannelThresholdStage stage = createChannelThresholdStage(
                cfg, binFolder, channelIndex, mirrorObjectThreshold);
        ConfigQcResult result = showEmbeddedConfigQcDialog(
                context, Collections.<ConfigQcStage>singletonList(stage));
        if (result == ConfigQcResult.DONE || result == ConfigQcResult.SKIP_CURRENT_IMAGE) {
            return "continue";
        }
        if (result == ConfigQcResult.BACK) {
            return "back";
        }
        return "cancel";
    }

    ChannelThresholdStage createChannelThresholdStage(final BinUserConfig cfg, final File binFolder,
                                                      final int channelIndex) {
        return createChannelThresholdStage(cfg, binFolder, channelIndex, true);
    }

    private ChannelThresholdStage createChannelThresholdStage(final BinUserConfig cfg, final File binFolder,
                                                             final int channelIndex,
                                                             final boolean mirrorObjectThreshold) {
        final int channelNum = channelIndex + 1;
        final String chLabel = "C" + channelNum + " (" + cfg.names.get(channelIndex) + ")";
        return new ChannelThresholdStage(
                new ChannelThresholdStage.ThresholdStore() {
                    @Override public String get() {
                        return mirrorObjectThreshold
                                ? cfg.objectThresholds.get(channelIndex)
                                : cfg.intensityThresholds.get(channelIndex);
                    }

                    @Override public void set(String token) {
                        if (mirrorObjectThreshold) {
                            cfg.objectThresholds.set(channelIndex, token);
                        }
                        cfg.intensityThresholds.set(channelIndex, token);
                    }
                },
                new ChannelThresholdStage.PreviewAdapter() {
                    @Override public ImagePlus createRawSource(ConfigQcContext context) {
                        ImagePlus raw = duplicateCurrentChannel(context, channelNum);
                        if (raw == null) return null;
                        raw.setTitle("Threshold raw source | " + chLabel + " | "
                                + (context == null ? "" : context.getCurrentImageDisplayName()));
                        return applyPreviewLut(raw, channelColor(cfg, channelIndex));
                    }

                    @Override public ImagePlus createThresholdSource(ConfigQcContext context) {
                        return createFilteredSetupSource(context, cfg, binFolder, channelIndex,
                                "Threshold input");
                    }

                    @Override public void close(ImagePlus image) {
                        closeImageQuietly(image);
                    }
                });
    }

    private ImagePlus duplicateCurrentChannel(ConfigQcContext context, int channelNum) {
        ImagePlus imp = context == null ? null : context.getCurrentImagePlus();
        if (imp == null) return null;
        int expectedChannels = context == null || context.getChannelNames() == null
                ? 0
                : context.getChannelNames().size();
        return duplicateChannelForSetupPreview(imp, channelNum, expectedChannels);
    }

    private ImagePlus duplicateChannelForSetupPreview(ImagePlus source,
                                                      int channelNum,
                                                      int expectedChannels) {
        if (source == null) return null;
        int requested = Math.max(1, channelNum);
        int reportedChannels = Math.max(1, source.getNChannels());
        int configuredChannels = Math.max(0, expectedChannels);
        if (configuredChannels > reportedChannels
                && canExtractInterleavedConfiguredChannel(source, requested, configuredChannels)) {
            return duplicateInterleavedConfiguredChannel(source, requested, configuredChannels);
        }

        if (reportedChannels >= requested) {
            ImagePlus duplicate = new Duplicator().run(source, requested, requested,
                    1, Math.max(1, source.getNSlices()),
                    1, Math.max(1, source.getNFrames()));
            if (duplicate != null) {
                return duplicate;
            }
        }

        if (canExtractInterleavedConfiguredChannel(source, requested, configuredChannels)) {
            return duplicateInterleavedConfiguredChannel(source, requested, configuredChannels);
        }

        if (requested == 1 && reportedChannels == 1) {
            return source.duplicate();
        }

        throw new IllegalStateException("Cannot extract C" + requested
                + " from " + safe(source.getTitle())
                + ": image reports " + reportedChannels
                + " channel(s), configured setup has " + configuredChannels + ".");
    }

    private boolean canExtractInterleavedConfiguredChannel(ImagePlus source,
                                                          int channelNum,
                                                          int configuredChannels) {
        return configuredChannels >= channelNum
                && source != null
                && source.getStack() != null
                && source.getStackSize() >= configuredChannels
                && source.getStackSize() % configuredChannels == 0;
    }

    private ImagePlus duplicateInterleavedConfiguredChannel(ImagePlus source,
                                                           int channelNum,
                                                           int configuredChannels) {
        ImageStack in = source.getStack();
        if (in == null) return null;
        int totalPlanes = Math.max(1, source.getStackSize());
        int frames = 1;
        int reportedFrames = Math.max(1, source.getNFrames());
        if (reportedFrames > 1
                && totalPlanes % (configuredChannels * reportedFrames) == 0) {
            frames = reportedFrames;
        }
        int zSlices = Math.max(1, totalPlanes / (configuredChannels * frames));
        ImageStack out = new ImageStack(source.getWidth(), source.getHeight());
        for (int t = 0; t < frames; t++) {
            for (int z = 0; z < zSlices; z++) {
                int sourceIndex = (t * configuredChannels * zSlices)
                        + (z * configuredChannels)
                        + channelNum;
                ImageProcessor processor = in.getProcessor(sourceIndex);
                if (processor == null) {
                    throw new IllegalStateException("Cannot extract C" + channelNum
                            + " from " + safe(source.getTitle())
                            + ": source plane " + sourceIndex + " is empty.");
                }
                out.addSlice(in.getSliceLabel(sourceIndex), processor.duplicate());
            }
        }
        ImagePlus duplicate = new ImagePlus(source.getTitle(), out);
        duplicate.setCalibration(source.getCalibration());
        duplicate.setDimensions(1, zSlices, frames);
        duplicate.setOpenAsHyperStack(frames > 1);
        return duplicate;
    }

    private String legacyInteractiveChannelThresholdQC(List<QcImageSelection> images, BinUserConfig cfg,
                                                       File binFolder, int channelIndex) {
        // Disabled legacy native ImageJ-window flow. Config QC routing calls
        // runEmbeddedChannelThresholdQC(...) directly.
        int channelNum = channelIndex + 1;
        String chLabel = "C" + channelNum + " (" + cfg.names.get(channelIndex) + ")";
        int imgIdx2 = 0;
        while (imgIdx2 < images.size()) {
            boolean imageAccepted = false;
            boolean skipCurrentImage = false;
            while (!imageAccepted) {
                closeQcToolWindows();
                QcImageSelection imageSelection = images.get(imgIdx2);
                ImagePlus imp = imageSelection.image;
                ImagePlus rawDup = new Duplicator().run(imp, channelNum, channelNum,
                        1, imp.getNSlices(), 1, imp.getNFrames());
                rawDup.setTitle("Channel Threshold QC | " + chLabel + " | " + imp.getTitle());

                String filterContent = resolveFilterContent(binFolder, cfg, channelIndex);
                ImagePlus thresholdPreview = prepareChannelThresholdPreview(rawDup, filterContent);
                thresholdPreview.setTitle("Channel Threshold QC | " + chLabel + " | " + imp.getTitle());
                thresholdPreview.show();
                IJ.run(thresholdPreview, toLutName(cfg.colors.get(channelIndex)), "");
                positionImageLeft(thresholdPreview);

                // Open Threshold dialog first, then apply persisted value
                IJ.run(thresholdPreview, "Threshold...", "");
                positionToolWindowNextToImage(THRESHOLD_WINDOW_TITLES);
                String curThresh = cfg.objectThresholds.get(channelIndex);
                if (!"default".equalsIgnoreCase(curThresh)) {
                    try {
                        double t = Double.parseDouble(curThresh);
                        double imageMax = thresholdPreview.getProcessor().getMax();
                        IJ.setThreshold(thresholdPreview, t, Math.max(t, imageMax));
                        thresholdPreview.updateAndDraw();
                    } catch (NumberFormatException e) {
                        IJ.log("    - WARNING: Invalid saved object threshold for " + chLabel
                                + "='" + curThresh + "'; leaving ImageJ threshold unchanged.");
                    }
                }

                String thresholdAction = showThresholdAdjustmentDialog(
                        "Channel Threshold QC \u2014 " + chLabel,
                        imp.getTitle(),
                        cfg.filterPresets.get(channelIndex),
                        curThresh,
                        imgIdx2,
                        images.size(),
                        thresholdPreview);

                Double readThresh = null;
                int suggestedAutoThresh = 1;
                if (!ACTION_SKIP_CURRENT_IMAGE.equals(thresholdAction)
                        && !"cancel".equals(thresholdAction)) {
                    readThresh = readThresholdFromImage(thresholdPreview);
                    suggestedAutoThresh = autoThreshold(thresholdPreview);
                }
                closeChannelThresholdPreviewImages(rawDup, thresholdPreview);
                closeToolWindows(THRESHOLD_WINDOW_TITLES);

                if (ACTION_SKIP_CURRENT_IMAGE.equals(thresholdAction)) {
                    skipCurrentImage = true;
                    imageAccepted = true;
                    continue;
                }
                if ("cancel".equals(thresholdAction)) return "cancel";

                ThresholdConfirmationResult confirm = promptForThresholdToken(
                        "Channel Threshold \u2014 " + chLabel,
                        "Confirm Channel Threshold",
                        "Channel Threshold (left/min value, 'default' or numeric)",
                        readThresh,
                        cfg.objectThresholds.get(channelIndex),
                        suggestedAutoThresh);
                if (confirm.back) continue;
                if (confirm.skipCurrentImage) {
                    skipCurrentImage = true;
                    imageAccepted = true;
                    continue;
                }
                if (confirm.canceled) return "cancel";

                cfg.objectThresholds.set(channelIndex, confirm.token);
                cfg.intensityThresholds.set(channelIndex, confirm.token);
                imageAccepted = true;
            }

            if (skipCurrentImage) {
                imgIdx2++;
                continue;
            }

            String action = showContinueRestartDialog(
                    "Channel Threshold QC \u2014 " + chLabel,
                    "Locked: " + cfg.objectThresholds.get(channelIndex),
                    imgIdx2, images.size());
            if ("cancel".equals(action)) return "cancel";
            if ("skip".equals(action)) break;
            if ("restart".equals(action)) { imgIdx2 = 0; continue; }
            imgIdx2++;
        }
        return "continue";
    }

    private boolean useEmbeddedStarDistParameterStage() {
        // Config QC stays in the owned embedded widget; legacy label-image
        // windows must not be selected by normal setup routing.
        return true;
    }

    private String interactiveSegmentationObjectQC(List<QcImageSelection> images,
                                                   BinUserConfig cfg,
                                                   File binFolder,
                                                   int channelIndex,
                                                   boolean includeAiChannelThreshold) {
        if (!embeddedConfigQcUiAvailable()) {
            return "cancel";
        }
        ensureConfigHasChannels(cfg);
        final SegmentationMethodStore methodStore =
                new SegmentationMethodStore(cfg, channelIndex);
        ConfigQcContext context = new ConfigQcContext(
                projectRootForConfigurationDir(binFolder),
                binFolder,
                cfg,
                filterParameterContextImages(images, channelIndex),
                cfg.names,
                channelIndex,
                setupFilteredStackCache);
        List<ConfigQcStage> stages = createSegmentationObjectQcStages(
                cfg, binFolder, channelIndex, includeAiChannelThreshold, methodStore);

        ConfigQcResult result = showEmbeddedConfigQcDialog(context, stages);
        if (result == ConfigQcResult.DONE || result == ConfigQcResult.SKIP_CURRENT_IMAGE) {
            if (isStarDistSegmentation(cfg, channelIndex)) {
                if (!includeAiChannelThreshold) {
                    cfg.objectThresholds.set(channelIndex, "default");
                }
            } else if (isCellposeSegmentation(cfg, channelIndex)) {
                cfg.sizes.set(channelIndex, "100-Infinity");
                if (!includeAiChannelThreshold) {
                    cfg.objectThresholds.set(channelIndex, "default");
                }
            }
            return "continue";
        }
        if (result == ConfigQcResult.BACK) {
            return "back";
        }
        return "cancel";
    }

    List<ConfigQcStage> createSegmentationObjectQcStagesForTest(BinUserConfig cfg,
                                                                File binFolder,
                                                                int channelIndex,
                                                                boolean includeAiChannelThreshold) {
        ensureConfigHasChannels(cfg);
        return createSegmentationObjectQcStages(
                cfg, binFolder, channelIndex, includeAiChannelThreshold,
                new SegmentationMethodStore(cfg, channelIndex));
    }

    private List<ConfigQcStage> createSegmentationObjectQcStages(final BinUserConfig cfg,
                                                                 final File binFolder,
                                                                 final int channelIndex,
                                                                 boolean includeAiChannelThreshold,
                                                                 final SegmentationMethodStage.MethodStore methodStore) {
        List<ConfigQcStage> stages = new ArrayList<ConfigQcStage>();
        stages.add(new SegmentationMethodStage(methodStore));
        stages.add(new ConditionalConfigQcStage(
                new TrainedRfSummaryStage(methodStore),
                new StagePredicate() {
                    @Override public boolean isApplicable() {
                        return isTrainedRfSegmentation(cfg, channelIndex);
                    }
                }));
        stages.add(new ConditionalConfigQcStage(
                withSegmentationMethodSwitcher(createClassicalSegmentationStage(
                        cfg, binFolder, channelIndex, includeAiChannelThreshold), methodStore),
                new StagePredicate() {
                    @Override public boolean isApplicable() {
                        return isClassicalSegmentation(cfg, channelIndex);
                    }
                }));
        stages.add(new ConditionalConfigQcStage(
                withSegmentationMethodSwitcher(createEnhancedClassicalSegmentationStage(
                        cfg, binFolder, channelIndex, includeAiChannelThreshold), methodStore),
                new StagePredicate() {
                    @Override public boolean isApplicable() {
                        return isEnhancedClassicalSegmentation(cfg, channelIndex);
                    }
                }));
        stages.add(new ConditionalConfigQcStage(
                withSegmentationMethodSwitcher(createStarDistParameterStage(cfg, binFolder, channelIndex),
                        methodStore),
                new StagePredicate() {
                    @Override public boolean isApplicable() {
                        return isStarDistSegmentation(cfg, channelIndex);
                    }
                }));
        stages.add(new ConditionalConfigQcStage(
                withSegmentationMethodSwitcher(createCellposeParameterStage(cfg, binFolder, channelIndex),
                        methodStore),
                new StagePredicate() {
                    @Override public boolean isApplicable() {
                        return isCellposeSegmentation(cfg, channelIndex);
                    }
                }));
        if (includeAiChannelThreshold) {
            stages.add(new ConditionalConfigQcStage(
                    withSegmentationMethodSwitcher(createChannelThresholdStage(cfg, binFolder, channelIndex),
                            methodStore),
                    new StagePredicate() {
                        @Override public boolean isApplicable() {
                            return isStarDistSegmentation(cfg, channelIndex)
                                    || isCellposeSegmentation(cfg, channelIndex);
                        }
                    }));
        }
        return stages;
    }

    private ConfigQcStage withSegmentationMethodSwitcher(ConfigQcStage delegate,
                                                         SegmentationMethodStage.MethodStore methodStore) {
        return new SegmentationMethodSwitchingStage(delegate, methodStore);
    }

    private static String[] splitSizeToken(String token) {
        String value = token == null ? "" : token.trim();
        String[] parts = value.split("-", 2);
        String min = parts.length > 0 && parts[0] != null && !parts[0].trim().isEmpty()
                ? parts[0].trim()
                : "100";
        String max = parts.length > 1 && parts[1] != null && !parts[1].trim().isEmpty()
                ? parts[1].trim()
                : "Infinity";
        return new String[] {min, max};
    }

    private static int parseIntegerLike(String token, int fallback) {
        if (token == null) return fallback;
        try {
            double parsed = Double.parseDouble(token.trim());
            if (!Double.isFinite(parsed)) return fallback;
            if (parsed <= 0) return 0;
            if (parsed >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
            return (int) Math.round(parsed);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseMaxSizeToken(String token) {
        if (token == null || token.trim().isEmpty()
                || "Infinity".equalsIgnoreCase(token.trim())
                || "Inf".equalsIgnoreCase(token.trim())) {
            return Integer.MAX_VALUE;
        }
        return parseIntegerLike(token, Integer.MAX_VALUE);
    }

    private String runEmbeddedStarDistParameterQC(List<QcImageSelection> images, BinUserConfig cfg,
                                                  File binFolder, int channelIndex) {
        if (!gateStarDistFeature("StarDist 3D preview")) {
            return "back";
        }
        if (!embeddedConfigQcUiAvailable()) {
            return "cancel";
        }
        ConfigQcContext context = new ConfigQcContext(
                projectRootForConfigurationDir(binFolder),
                binFolder,
                cfg,
                filterParameterContextImages(images, channelIndex),
                cfg.names,
                channelIndex,
                setupFilteredStackCache);
        StarDistParameterStage stage = createStarDistParameterStage(cfg, binFolder, channelIndex);
        ConfigQcResult result = showEmbeddedConfigQcDialog(
                context, Collections.<ConfigQcStage>singletonList(stage));
        if (result == ConfigQcResult.DONE || result == ConfigQcResult.SKIP_CURRENT_IMAGE) {
            cfg.objectThresholds.set(channelIndex, "default");
            return "continue";
        }
        if (result == ConfigQcResult.BACK) {
            return "back";
        }
        return "cancel";
    }

    StarDistParameterStage createStarDistParameterStage(final BinUserConfig cfg,
                                                        final File binFolder,
                                                        final int channelIndex) {
        final int channelNum = channelIndex + 1;
        final String chLabel = "C" + channelNum + " (" + cfg.names.get(channelIndex) + ")";
        return new StarDistParameterStage(
                new StarDistParameterStage.ParameterStore() {
                    @Override public String getMethodToken() {
                        return cfg.segmentationMethods.get(channelIndex);
                    }

                    @Override public void save(String methodToken) {
                        cfg.segmentationMethods.set(channelIndex, methodToken);
                    }
                },
                new StarDistParameterStage.SizeStore() {
                    @Override public String get() {
                        return cfg.sizes.get(channelIndex);
                    }

                    @Override public void set(String token) {
                        cfg.sizes.set(channelIndex, token);
                    }
                },
                new StarDistParameterStage.PreviewAdapter() {
                    @Override public ImagePlus createRawSource(ConfigQcContext context) {
                        return createRawAiSource(context, cfg, channelIndex,
                                "StarDist raw input");
                    }

                    @Override public ImagePlus createFilteredSource(ConfigQcContext context) {
                        return createFilteredAiSource(context, cfg, binFolder, channelIndex,
                                "StarDist filtered input");
                    }

                    @Override public ImagePlus runPreview(ImagePlus filteredSource,
                                                          StarDistParameterStage.Parameters parameters) {
                        if (filteredSource == null) return null;
                        ImagePlus input = filteredSource.duplicate();
                        input.setTitle("StarDist preview input | " + chLabel);
                        long started = System.currentTimeMillis();
                        IJ.log("Set Up QC StarDist preview running: " + chLabel
                                + " | " + sourceTitle(filteredSource)
                                + " | prob=" + parameters.probabilityThreshold
                                + " | nms=" + parameters.nmsThreshold);
                        try {
                            ImagePlus result = StarDist3DRunner.run(input,
                                    parameters.probabilityThreshold,
                                    parameters.nmsThreshold,
                                    cfg.names.get(channelIndex),
                                    parameters.linkingMaxDistance,
                                    parameters.gapClosingMaxDistance,
                                    parameters.maxFrameGap,
                                    parameters.areaMin,
                                    parameters.areaMax,
                                    parameters.qualityMin,
                                    parameters.intensityMin,
                                    parameters.modelKey,
                                    projectRootForConfigurationDir(binFolder));
                            IJ.log("Set Up QC StarDist preview complete: " + chLabel
                                    + " | objects=" + StarDist3DRunner.countLabels(result)
                                    + " | " + (System.currentTimeMillis() - started) + " ms.");
                            return result;
                        } finally {
                            closeImageQuietly(input);
                        }
                    }

                    @Override public int countLabels(ImagePlus labelImage) {
                        return labelImage == null ? 0 : StarDist3DRunner.countLabels(labelImage);
                    }

                    @Override public void close(ImagePlus image) {
                        closeImageQuietly(image);
                    }
                });
    }

    private boolean useEmbeddedCellposeParameterStage() {
        // Config QC stays in the owned embedded widget; legacy label-image
        // windows must not be selected by normal setup routing.
        return true;
    }

    private String runEmbeddedCellposeParameterQC(List<QcImageSelection> images, BinUserConfig cfg,
                                                  File binFolder, int channelIndex) {
        if (!gateCellposeFeature("Cellpose segmentation")) {
            return "back";
        }
        if (!embeddedConfigQcUiAvailable()) {
            return "cancel";
        }
        ConfigQcContext context = new ConfigQcContext(
                projectRootForConfigurationDir(binFolder),
                binFolder,
                cfg,
                filterParameterContextImages(images, channelIndex),
                cfg.names,
                channelIndex,
                setupFilteredStackCache);
        CellposeParameterStage stage = createCellposeParameterStage(cfg, binFolder, channelIndex);
        ConfigQcResult result = showEmbeddedConfigQcDialog(
                context, Collections.<ConfigQcStage>singletonList(stage));
        if (result == ConfigQcResult.DONE || result == ConfigQcResult.SKIP_CURRENT_IMAGE) {
            cfg.objectThresholds.set(channelIndex, "default");
            return "continue";
        }
        if (result == ConfigQcResult.BACK) {
            return "back";
        }
        return "cancel";
    }

    CellposeParameterStage createCellposeParameterStage(final BinUserConfig cfg,
                                                        final File binFolder,
                                                        final int channelIndex) {
        final int channelNum = channelIndex + 1;
        final String chLabel = "C" + channelNum + " (" + cfg.names.get(channelIndex) + ")";
        CellposeRuntime.Status runtimeStatus = CellposeRuntime.cachedStatus();
        CellposeRuntime.probeAsync();
        final boolean defaultUseGpu = runtimeStatus.ready
                ? runtimeStatus.gpuAvailable
                : BinConfig.DEFAULT_CELLPOSE_USE_GPU;
        return new CellposeParameterStage(
                new CellposeParameterStage.ParameterStore() {
                    @Override public String getMethodToken() {
                        return cfg.segmentationMethods.get(channelIndex);
                    }

                    @Override public void save(String methodToken) {
                        cfg.segmentationMethods.set(channelIndex, methodToken);
                    }
                },
                new CellposeParameterStage.SizeStore() {
                    @Override public String get() {
                        return cfg.sizes.get(channelIndex);
                    }

                    @Override public void set(String token) {
                        cfg.sizes.set(channelIndex, token);
                    }
                },
                new CellposeParameterStage.PreviewAdapter() {
                    @Override public ImagePlus createRawSource(ConfigQcContext context) {
                        return createRawAiSource(context, cfg, channelIndex,
                                "Cellpose raw input");
                    }

                    @Override public ImagePlus createFilteredSource(ConfigQcContext context) {
                        return createFilteredAiSource(context, cfg, binFolder, channelIndex,
                                "Cellpose filtered input");
                    }

                    @Override public ImagePlus createFilteredCompanionSource(ConfigQcContext context,
                                                                             int companionChannelIndex) {
                        return createFilteredAiSource(context, cfg, binFolder, companionChannelIndex,
                                "Cellpose companion input");
                    }

                    @Override public ImagePlus runPreview(ImagePlus filteredSource,
                                                          ImagePlus filteredCompanionSource,
                                                          CellposeParameterStage.Parameters parameters) {
                        if (filteredSource == null) return null;
                        ImagePlus input = filteredSource.duplicate();
                        ImagePlus companion = filteredCompanionSource == null
                                ? null : filteredCompanionSource.duplicate();
                        input.setTitle("Cellpose preview input | " + chLabel);
                        if (companion != null) {
                            companion.setTitle("Cellpose preview companion | " + chLabel);
                        }
                        long started = System.currentTimeMillis();
                        IJ.log("Set Up QC Cellpose preview running: " + chLabel
                                + " | " + sourceTitle(filteredSource)
                                + " | model=" + parameters.modelToken
                                + " | diameter=" + parameters.diameter
                                + " | gpu=" + parameters.useGpu);
                        try {
                            ImagePlus result = Cellpose3DRunner.run(input, companion,
                                    parameters.modelToken,
                                    parameters.diameter,
                                    parameters.flowThreshold,
                                    parameters.cellprobThreshold,
                                    parameters.useGpu,
                                    cfg.names.get(channelIndex),
                                    projectRootForConfigurationDir(binFolder),
                                    parameters.dumpCellprob);
                            IJ.log("Set Up QC Cellpose preview complete: " + chLabel
                                    + " | objects=" + Cellpose3DRunner.countLabels(result)
                                    + " | " + (System.currentTimeMillis() - started) + " ms.");
                            return result;
                        } finally {
                            closeImageQuietly(input);
                            closeImageQuietly(companion);
                        }
                    }

                    @Override public int countLabels(ImagePlus labelImage) {
                        return labelImage == null ? 0 : Cellpose3DRunner.countLabels(labelImage);
                    }

                    @Override public void close(ImagePlus image) {
                        closeImageQuietly(image);
                    }
                },
                new CellposeParameterStage.RuntimeAdapter() {
                    @Override public CellposeRuntime.Status cachedRuntimeStatus() {
                        return CellposeRuntime.cachedStatus();
                    }

                    @Override public java.util.concurrent.CompletableFuture<CellposeRuntime.Status> probeRuntimeAsync() {
                        return CellposeRuntime.probeAsync();
                    }

                    @Override public boolean nvidiaGpuLikelyAvailable() {
                        return CellposeRuntime.isNvidiaGpuLikelyAvailable();
                    }

                    @Override public CellposeParameterStage.GpuInstallResult installGpuSupport() {
                        CellposeRuntime.InstallResult result = CellposeRuntime.installManagedGpu();
                        return new CellposeParameterStage.GpuInstallResult(
                                result.success,
                                result.message,
                                result.details);
                    }
                },
                cfg.names,
                channelIndex,
                defaultUseGpu);
    }

    private ImagePlus createRawAiSource(ConfigQcContext context, BinUserConfig cfg,
                                        int channelIndex, String titlePrefix) {
        int channelNum = channelIndex + 1;
        String chLabel = "C" + channelNum + " (" + cfg.names.get(channelIndex) + ")";
        ImagePlus source = duplicateCurrentChannel(context, channelNum);
        if (source == null) return null;
        source.setTitle(titlePrefix + " | " + chLabel + " | "
                + (context == null ? "" : context.getCurrentImageDisplayName()));
        return applyPreviewLut(source, channelColor(cfg, channelIndex));
    }

    private ImagePlus createFilteredAiSource(ConfigQcContext context, BinUserConfig cfg, File binFolder,
                                             int channelIndex, String titlePrefix) {
        return createFilteredSetupSource(context, cfg, binFolder, channelIndex, titlePrefix);
    }

    private ImagePlus createFilteredSetupSource(ConfigQcContext context, BinUserConfig cfg, File binFolder,
                                                int channelIndex, String titlePrefix) {
        int channelNum = channelIndex + 1;
        String chLabel = "C" + channelNum + " (" + cfg.names.get(channelIndex) + ")";
        String filterContent = resolveFilterContent(binFolder, cfg, channelIndex);
        ImagePlus source = null;
        boolean cacheHit = false;
        if (context != null && hasText(filterContent)) {
            source = context.duplicateFilteredStackForCurrentImage(channelIndex, filterContent);
            cacheHit = source != null;
        }
        if (source == null) {
            source = duplicateCurrentChannel(context, channelNum);
            if (source == null) return null;
        }
        if (hasText(filterContent) && !cacheHit) {
            long started = System.currentTimeMillis();
            IJ.log("Set Up QC filter applying: " + chLabel
                    + " | " + (context == null ? "" : context.getCurrentImageDisplayName())
                    + " | preset " + safe(valueAt(cfg.filterPresets, channelIndex, "")));
            FilterExecutor.runThreadSafe(source, filterContent);
            if (context != null) {
                context.cacheFilteredStackForCurrentImage(channelIndex, filterContent, source);
            }
            IJ.log("Set Up QC filter complete: " + chLabel + " in "
                    + (System.currentTimeMillis() - started) + " ms.");
        }
        source.setTitle(titlePrefix + " | " + chLabel + " | "
                + (context == null ? "" : context.getCurrentImageDisplayName()));
        return applyPreviewLut(source, channelColor(cfg, channelIndex));
    }

    private String interactiveFilterParameterQC(List<QcImageSelection> images, BinUserConfig cfg,
                                                File binFolder, int channelIndex) {
        return runEmbeddedFilterParameterQC(images, cfg, binFolder, channelIndex);
    }

    private String runEmbeddedFilterParameterQC(List<QcImageSelection> images, BinUserConfig cfg,
                                                File binFolder, int channelIndex) {
        if (!embeddedConfigQcUiAvailable()) {
            return "cancel";
        }
        ConfigQcContext context = new ConfigQcContext(
                projectRootForConfigurationDir(binFolder),
                binFolder,
                cfg,
                filterParameterContextImages(images, channelIndex),
                cfg.names,
                channelIndex,
                setupFilteredStackCache);
        FilterParameterStage stage = createFilterParameterStage(images, cfg, binFolder, channelIndex);
        ConfigQcResult result = showEmbeddedConfigQcDialog(
                context, Collections.<ConfigQcStage>singletonList(stage));
        if (result == ConfigQcResult.DONE || result == ConfigQcResult.SKIP_CURRENT_IMAGE) {
            return "continue";
        }
        if (result == ConfigQcResult.BACK) {
            return "back";
        }
        return "cancel";
    }

    private List<ConfigQcContext.ConfigQcImage> filterParameterContextImages(
            List<QcImageSelection> images,
            int channelIndex) {
        List<ConfigQcContext.ConfigQcImage> contextImages =
                new ArrayList<ConfigQcContext.ConfigQcImage>();
        int channelNumber = channelIndex + 1;
        List<QcImageSelection> orderedImages = orderQcSelectionsForChannel(images, channelIndex);
        for (int i = 0; i < orderedImages.size(); i++) {
            QcImageSelection selection = orderedImages.get(i);
            if (selection == null) continue;
            contextImages.add(new ConfigQcContext.ConfigQcImage(
                    selection.seriesIndex,
                    selection.seriesName,
                    selection.image,
                    selection.reviewLabelForChannel(channelNumber),
                    selection.warning));
        }
        return contextImages;
    }

    private List<QcImageSelection> orderQcSelectionsForChannel(List<QcImageSelection> images,
                                                               int channelIndex) {
        List<QcImageSelection> ordered = new ArrayList<QcImageSelection>();
        if (images == null) return ordered;
        final int channelNumber = channelIndex + 1;
        boolean hasMinMaxSelectionOrder = false;
        for (int i = 0; i < images.size(); i++) {
            QcImageSelection selection = images.get(i);
            if (selection != null && !selection.minMaxOrderByChannelNumber.isEmpty()) {
                hasMinMaxSelectionOrder = true;
                break;
            }
        }
        for (int i = 0; i < images.size(); i++) {
            QcImageSelection selection = images.get(i);
            if (selection == null) continue;
            if (hasMinMaxSelectionOrder && !selection.hasMinMaxOrderForChannel(channelNumber)) {
                continue;
            }
            ordered.add(selection);
        }
        Collections.sort(ordered, new Comparator<QcImageSelection>() {
            @Override public int compare(QcImageSelection left, QcImageSelection right) {
                int leftOrder = left == null ? Integer.MAX_VALUE
                        : left.minMaxOrderForChannel(channelNumber);
                int rightOrder = right == null ? Integer.MAX_VALUE
                        : right.minMaxOrderForChannel(channelNumber);
                return Integer.compare(leftOrder, rightOrder);
            }
        });
        return ordered;
    }

    FilterParameterStage createFilterParameterStage(final List<QcImageSelection> images,
                                                    final BinUserConfig cfg,
                                                    final File binFolder,
                                                    final int channelIndex) {
        final int channelNum = channelIndex + 1;
        final String chLabel = "C" + channelNum + " (" + cfg.names.get(channelIndex) + ")";
        final List<QcImageSelection> channelImages =
                orderQcSelectionsForChannel(images, channelIndex);
        FilterParameterStage stage = new FilterParameterStage(
                filterPresetOptionList(binFolder, cfg.filterPresets.get(channelIndex)),
                new FilterParameterStage.MacroStore() {
                    @Override public String getInitialPreset() {
                        return cfg.filterPresets.get(channelIndex);
                    }

                    @Override public String loadInitialMacro() {
                        return resolveFilterContent(binFolder, cfg, channelIndex);
                    }

                    @Override public String loadPresetMacro(String presetName) {
                        if ("Custom".equals(presetName)) {
                            return resolveFilterContent(binFolder, cfg, channelIndex);
                        }
                        return getFilterPresetContent(binFolder, presetName);
                    }

                    @Override public void save(String presetName, String macroContent) throws Exception {
                        String safePreset = safeFilterPresetSelection(
                                presetName, cfg.filterPresets.get(channelIndex));
                        cfg.filterPresets.set(channelIndex, safePreset);
                        Path filterPath = binFolder.toPath().resolve(
                                "C" + channelNum + "_Filters.ijm");
                        Files.write(filterPath, safe(macroContent).getBytes(StandardCharsets.UTF_8));
                    }

                    @Override public void saveAsPreset(String presetName, String macroContent) throws Exception {
                        if (presetName == null || presetName.trim().isEmpty()) {
                            throw new IllegalArgumentException("Preset name must not be empty.");
                        }
                        saveCustomFilterPreset(binFolder, presetName.trim(), safe(macroContent));
                    }
                },
                new FilterParameterStage.PreviewAdapter() {
                    @Override public ImagePlus createSource(ConfigQcContext context) {
                        ImagePlus source = duplicateCurrentChannel(context, channelNum);
                        if (source == null) return null;
                        source.setTitle("Filter source | " + chLabel + " | "
                                + (context == null ? "" : context.getCurrentImageDisplayName()));
                        return applyPreviewLut(source, channelColor(cfg, channelIndex));
                    }

                    @Override public ImagePlus createFilteredPreview(ImagePlus source, String macroContent) {
                        if (source == null) return null;
                        ImagePlus filtered = source.duplicate();
                        if (macroContent != null && !macroContent.trim().isEmpty()) {
                            FilterExecutor.runThreadSafe(filtered, macroContent);
                        }
                        filtered.setTitle("Filter preview | " + chLabel + " | " + source.getTitle());
                        return applyPreviewLut(filtered, channelColor(cfg, channelIndex));
                    }

                    @Override public void close(ImagePlus image) {
                        closeImageQuietly(image);
                    }
                },
                new FilterParameterStage.CustomFilterBuilder() {
                    @Override public FilterParameterStage.CustomFilterResult open(
                            ConfigQcContext context, String currentPreset, String currentMacro) throws Exception {
                        return open(context, currentPreset, currentMacro, null);
                    }

                    @Override public FilterParameterStage.CustomFilterResult open(
                            ConfigQcContext context, String currentPreset, String currentMacro,
                            Window owner) throws Exception {
                        return openCustomFilterBuilderFromFilterStage(
                                owner, channelImages, cfg, binFolder, channelIndex, chLabel, currentMacro);
                    }
                },
                new FilterParameterStage.PresetDescriptionProvider() {
                    @Override public String describe(String presetName) {
                        return filterDescriptionFor(presetName);
                    }
                });
        installAsyncFilterPresetOptions(binFolder, stage, cfg.filterPresets.get(channelIndex));
        return stage;
    }

    private List<String> filterPresetOptionList(File binFolder, String selectedPreset) {
        String[] values = filterPresetOptions(binFolder, selectedPreset);
        List<String> options = new ArrayList<String>();
        for (int i = 0; i < values.length; i++) {
            options.add(values[i]);
        }
        return options;
    }

    private List<String> filterPresetOptionListWithSaved(List<String> savedPresets,
                                                         String selectedPreset,
                                                         String currentSelection) {
        String[] values = filterPresetOptionsWithSaved(savedPresets, selectedPreset, currentSelection);
        List<String> options = new ArrayList<String>();
        for (int i = 0; i < values.length; i++) {
            options.add(values[i]);
        }
        return options;
    }

    private FilterParameterStage.CustomFilterResult openCustomFilterBuilderFromFilterStage(
            final Window owner,
            final List<QcImageSelection> images,
            final BinUserConfig cfg,
            final File binFolder,
            final int channelIndex,
            final String chLabel,
            final String seedMacro) throws Exception {
        if (!canShowCustomFilterDialog()) {
            return new FilterParameterStage.CustomFilterResult(false, null, null);
        }
        final ImagePlus[] sampleHolder = new ImagePlus[1];
        flash.pipeline.ui.RecorderDialog.SampleSupplier sampleSupplier =
                createQcCustomFilterSampleSupplier(images, cfg, channelIndex, chLabel, sampleHolder);
        try {
            CustomFilterEntryDialog.Result result = CustomFilterEntryDialog.show(
                    owner,
                    chLabel,
                    createQcCustomFilterPreviewHandler(images, cfg, channelIndex, chLabel),
                    createQcSandboxHandler(owner, binFolder, cfg, channelIndex, chLabel, images,
                            seedMacro != null && seedMacro.trim().length() > 0),
                    sampleSupplier,
                    seedMacro);
            boolean applied = applyCustomFilterEntryResult(owner, binFolder, cfg, channelIndex, result, false);
            if (!applied) {
                return new FilterParameterStage.CustomFilterResult(false, null, null);
            }
            rememberHandledCustomFilter(binFolder, channelIndex, cfg);
            return new FilterParameterStage.CustomFilterResult(
                    true,
                    cfg.filterPresets.get(channelIndex),
                    resolveFilterContent(binFolder, cfg, channelIndex));
        } finally {
            closeImageQuietly(sampleHolder[0]);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String exceptionSummary(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName()
                + (message == null || message.trim().isEmpty() ? "" : ": " + message.trim());
    }

    private String legacyInteractiveFilterParameterQC(List<QcImageSelection> images, BinUserConfig cfg,
                                                      File binFolder, int channelIndex) {
        // Disabled legacy native ImageJ-window flow. Config QC routing calls
        // runEmbeddedFilterParameterQC(...) directly.
        int channelNum = channelIndex + 1;
        String chLabel = "C" + channelNum + " (" + cfg.names.get(channelIndex) + ")";
        String currentMacro = resolveFilterContent(binFolder, cfg, channelIndex);
        if (currentMacro == null || currentMacro.trim().isEmpty()) {
            IJ.showMessage("Set Filter and Parameters",
                    "No filter macro could be loaded for " + chLabel + ".\nUsing the current preset without parameter editing.");
            return "continue";
        }

        FilterMacroEditorModel.MacroDefinition initialDefinition = FilterMacroEditorModel.parse(currentMacro);
        if (!initialDefinition.hasEditableParameters()) {
            IJ.showMessage("Set Filter and Parameters",
                    "No editable key=value parameters were detected in the filter macro for " + chLabel + ".\nUsing the current .ijm file as-is.");
            return "continue";
        }

        Path filterPath = binFolder.toPath().resolve("C" + channelNum + "_Filters.ijm");
        int imgIdx = 0;
        while (imgIdx < images.size()) {
            QcImageSelection imageSelection = images.get(imgIdx);
            ImagePlus imp = imageSelection.image;
            ImagePlus source = new Duplicator().run(imp, channelNum, channelNum,
                    1, imp.getNSlices(), 1, imp.getNFrames());
            FilterMacroEditorModel.MacroDefinition definition = FilterMacroEditorModel.parse(currentMacro);
            final ImagePlus[] previewHolder = new ImagePlus[]{
                    renderFilterParameterPreview(source, currentMacro, cfg.colors.get(channelIndex), chLabel, imp.getTitle())
            };

            GenericDialog dialog = new NonBlockingGenericDialog("Set Filter and Parameters - " + chLabel);
            dialog.setOKLabel("Lock in");
            dialog.addMessage("Image " + (imgIdx + 1) + "/" + images.size() + ": " + imp.getTitle());
            dialog.addMessage("Scroll through the filtered Z-stack on the left. Adjust any parameters if needed, click Apply Preview to rerun the filter on this image, then click Lock in when satisfied.");
            List<FilterFieldBinding> bindings = addFilterParameterEditor(dialog, definition);
            addFilterPreviewButton(dialog, bindings, definition, previewHolder, source,
                    cfg.colors.get(channelIndex), chLabel, imp.getTitle());
            final boolean[] skipCurrentImage = new boolean[]{false};
            addGenericDialogSkipCurrentButton(dialog, skipCurrentImage);
            positionDialogBesideImage(dialog, previewHolder[0]);
            dialog.showDialog();
            if (skipCurrentImage[0]) {
                closeImageQuietly(previewHolder[0]);
                closeImageQuietly(source);
                imgIdx++;
                continue;
            }
            if (dialog.wasCanceled()) {
                closeImageQuietly(previewHolder[0]);
                closeImageQuietly(source);
                return "cancel";
            }

            syncFilterFieldBindings(bindings);
            currentMacro = definition.render();
            try {
                Files.write(filterPath, currentMacro.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                closeImageQuietly(previewHolder[0]);
                closeImageQuietly(source);
                IJ.showMessage("Set Filter and Parameters",
                        "Could not save the updated filter macro for " + chLabel + ":\n" + e.getMessage());
                return "cancel";
            }

            closeImageQuietly(previewHolder[0]);
            closeImageQuietly(source);

            String summary = definition.summarizeValues(8);
            if (summary == null || summary.trim().isEmpty()) {
                summary = "Updated " + definition.editableParameterCount() + " filter parameters.";
            } else {
                summary = "Updated filter parameters:\n" + summary;
            }
            String action = showContinueRestartDialog(
                    "Set Filter and Parameters - " + chLabel,
                    summary,
                    imgIdx, images.size());
            if ("cancel".equals(action)) return "cancel";
            if ("skip".equals(action)) break;
            if ("restart".equals(action)) { imgIdx = 0; continue; }
            imgIdx++;
        }
        return "continue";
    }

    private List<FilterFieldBinding> addFilterParameterEditor(GenericDialog dialog,
                                                              FilterMacroEditorModel.MacroDefinition definition) {
        List<FilterFieldBinding> bindings = new ArrayList<FilterFieldBinding>();
        List<FilterMacroEditorModel.Section> sections = definition.getSections();
        java.awt.Font headerFont = new java.awt.Font("SansSerif", java.awt.Font.BOLD, 12);
        for (int i = 0; i < sections.size(); i++) {
            FilterMacroEditorModel.Section section = sections.get(i);
            dialog.setInsets(i == 0 ? 12 : 10, 0, 0);
            dialog.addMessage(section.headerText(), headerFont);
            List<FilterMacroEditorModel.Entry> entries = section.entries;
            for (int j = 0; j < entries.size(); j++) {
                dialog.setInsets(0, 0, 0);
                dialog.addPanel(buildFilterParameterRow(entries.get(j), bindings));
            }
        }
        return bindings;
    }

    private java.awt.Panel buildFilterParameterRow(FilterMacroEditorModel.Entry entry,
                                                   List<FilterFieldBinding> bindings) {
        java.awt.Panel row = new java.awt.Panel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 2));
        row.add(new java.awt.Label(entry.label));

        List<FilterMacroEditorModel.Parameter> parameters = entry.parameters;
        for (int i = 0; i < parameters.size(); i++) {
            FilterMacroEditorModel.Parameter parameter = parameters.get(i);
            row.add(new java.awt.Label(parameter.key));

            int columns = Math.max(4, Math.min(10, parameter.getValue().length() + 1));
            java.awt.TextField field = new java.awt.TextField(parameter.getValue(), columns);
            row.add(field);
            bindings.add(new FilterFieldBinding(parameter, field));
        }
        return row;
    }

    private void addFilterPreviewButton(GenericDialog dialog,
                                        List<FilterFieldBinding> bindings,
                                        FilterMacroEditorModel.MacroDefinition definition,
                                        ImagePlus[] previewHolder,
                                        ImagePlus source,
                                        String colorName,
                                        String chLabel,
                                        String imageTitle) {
        java.awt.Panel previewPanel = new java.awt.Panel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        java.awt.Button previewButton = new java.awt.Button("Apply Preview");
        previewButton.addActionListener(new java.awt.event.ActionListener() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                try {
                    syncFilterFieldBindings(bindings);
                    previewHolder[0] = renderFilterParameterPreview(
                            source, definition.render(), colorName, chLabel, imageTitle, previewHolder[0]);
                } catch (Exception ex) {
                    IJ.showMessage("Set Filter and Parameters",
                            "Preview failed for " + chLabel + ":\n" + ex.getMessage());
                }
            }
        });
        previewPanel.add(previewButton);
        dialog.setInsets(10, 0, 0);
        dialog.addPanel(previewPanel);
    }

    private void addGenericDialogSkipCurrentButton(final GenericDialog dialog,
                                                   final boolean[] skipCurrentImage) {
        if (dialog == null || skipCurrentImage == null || skipCurrentImage.length == 0) return;
        java.awt.Panel panel = new java.awt.Panel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        java.awt.Button skip = new java.awt.Button("Skip Current Image");
        skip.addActionListener(e -> {
            skipCurrentImage[0] = true;
            dialog.dispose();
        });
        panel.add(skip);
        dialog.setInsets(10, 0, 0);
        dialog.addPanel(panel);
    }

    private void syncFilterFieldBindings(List<FilterFieldBinding> bindings) {
        for (int i = 0; i < bindings.size(); i++) {
            FilterFieldBinding binding = bindings.get(i);
            binding.parameter.setValue(binding.field.getText().trim());
        }
    }

    private void positionDialogBesideImage(GenericDialog dialog, ImagePlus imp) {
        if (dialog == null) return;
        if (imp != null && imp.getWindow() != null) {
            java.awt.Window imgWin = imp.getWindow();
            int x = imgWin.getX() + imgWin.getWidth() + 20;
            int y = imgWin.getY();
            dialog.setLocation(x, y);
            return;
        }
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        dialog.setLocation(Math.max(50, screen.width - 420), Math.min(50, screen.height / 6));
    }

    ImagePlus prepareChannelThresholdPreview(ImagePlus rawDuplicate, String filterContent) {
        if (rawDuplicate == null) return null;
        if (filterContent == null || filterContent.trim().isEmpty()) return rawDuplicate;
        ImagePlus filtered = runChannelThresholdFilter(rawDuplicate, filterContent);
        return filtered != null ? filtered : rawDuplicate;
    }

    protected ImagePlus runChannelThresholdFilter(ImagePlus rawDuplicate, String filterContent) {
        FilterExecutor.runThreadSafe(rawDuplicate, filterContent);
        return rawDuplicate;
    }

    private void closeChannelThresholdPreviewImages(ImagePlus rawDuplicate, ImagePlus thresholdPreview) {
        closeImageQuietly(thresholdPreview);
        if (rawDuplicate != thresholdPreview) {
            closeImageQuietly(rawDuplicate);
        }
    }

    private ImagePlus renderFilterParameterPreview(ImagePlus source, String macroContent,
                                                   String colorName, String chLabel, String imageTitle) {
        return renderFilterParameterPreview(source, macroContent, colorName, chLabel, imageTitle, null);
    }

    private ImagePlus renderFilterParameterPreview(ImagePlus source, String macroContent,
                                                   String colorName, String chLabel, String imageTitle,
                                                   ImagePlus existingPreview) {
        closeImageQuietly(existingPreview);
        ImagePlus preview = source.duplicate();
        if (macroContent != null && !macroContent.trim().isEmpty()) {
            FilterExecutor.runThreadSafe(preview, macroContent);
        }
        preview.setTitle("Filter Preview | " + chLabel + " | " + imageTitle);
        preview.show();
        allowWindowThroughModalBlock(preview.getWindow());
        IJ.run(preview, toLutName(colorName), "");
        positionImageLeft(preview);
        return preview;
    }

    private ImagePlus renderDagPreview(ImagePlus preview, ImagePlus existingPreview,
                                       String colorName, String chLabel, String imageTitle) {
        if (existingPreview != null && existingPreview != preview) {
            closeImageQuietly(existingPreview);
        }
        if (preview == null) return null;
        preview.setTitle("Filter Preview | " + chLabel + " | " + imageTitle);
        return applyPreviewLut(preview, colorName);
    }

    private ImagePlus applyPreviewLut(ImagePlus image, String colorName) {
        if (image == null) return null;
        try {
            IJ.run(image, toLutName(colorName), "");
        } catch (RuntimeException e) {
            IJ.log("WARNING: could not apply preview LUT: " + e.getMessage());
        }
        image.updateAndDraw();
        return image;
    }

    private static String channelColor(BinUserConfig cfg, int channelIndex) {
        if (cfg == null || channelIndex < 0 || channelIndex >= cfg.colors.size()) return "Grays";
        String color = cfg.colors.get(channelIndex);
        return color == null || color.trim().isEmpty() ? "Grays" : color;
    }

    private void closeImageQuietly(ImagePlus imp) {
        if (imp == null) return;
        imp.changes = false;
        if (imp.getWindow() != null) {
            imp.close();
        }
        imp.flush();
    }

    private void closePreviewImagesByPrefix(String titlePrefix) {
        if (titlePrefix == null || titlePrefix.trim().isEmpty()) return;
        int[] openIds = WindowManager.getIDList();
        if (openIds == null) return;
        for (int id : openIds) {
            ImagePlus openImg = WindowManager.getImage(id);
            if (openImg != null && openImg.getTitle().startsWith(titlePrefix)) {
                closeImageQuietly(openImg);
            }
        }
    }

    private static void allowWindowThroughModalBlock(Window window) {
        if (window == null) return;
        try {
            window.setModalExclusionType(Dialog.ModalExclusionType.APPLICATION_EXCLUDE);
        } catch (RuntimeException ignored) {
            // Best effort: preview images still open normally where modal exclusion is unavailable.
        }
    }

    private void showSegmentationPreviewLabel(ImagePlus labelImage,
                                              String title,
                                              ImagePlus anchorImage,
                                              int objectCount) {
        if (labelImage == null) return;
        labelImage.setTitle(title);
        labelImage.show();
        applyLabelMapLut(labelImage, Math.max(1, objectCount));
        positionImageRightOf(anchorImage, labelImage);
        labelImage.updateAndDraw();
    }

    private void applyLabelMapLut(ImagePlus labelImage) {
        applyLabelMapLut(labelImage, maxDisplayValue(labelImage));
    }

    private void applyLabelMapLut(ImagePlus labelImage, double maxLabelValue) {
        if (labelImage == null) return;
        // Label maps encode object IDs, not channel intensity, so use a
        // categorical label LUT instead of the configured channel colour.
        labelImage.setDisplayRange(0, Math.max(1, maxLabelValue));
        try {
            IJ.run(labelImage, "glasbey_on_dark", "");
        } catch (Exception ignored) {
            // Best-effort only. The preview is still useful in grayscale.
        }
        labelImage.updateAndDraw();
    }

    private double maxDisplayValue(ImagePlus imp) {
        if (imp == null) return 1;
        double max = 0;
        if (imp.getStack() != null && imp.getStackSize() > 0) {
            for (int i = 1; i <= imp.getStackSize(); i++) {
                ImageProcessor processor = imp.getStack().getProcessor(i);
                if (processor != null) max = Math.max(max, processor.getMax());
            }
        } else if (imp.getProcessor() != null) {
            max = imp.getProcessor().getMax();
        }
        return isValidThresholdValue(max) ? max : 1;
    }

    // ── Continue/Restart/Skip dialog ────────────────────────────────────

    private void addSkipCurrentImageButton(final PipelineDialog dialog) {
        if (dialog == null) return;
        JButton skip = dialog.addFooterButton("Skip Current Image");
        skip.addActionListener(e -> dialog.closeWithAction(ACTION_SKIP_CURRENT_IMAGE));
    }

    private boolean isSkipCurrentImageAction(PipelineDialog dialog) {
        return dialog != null && ACTION_SKIP_CURRENT_IMAGE.equals(dialog.getActionCommand());
    }

    private String showLockInSkipDialog(String title, String header, String[] messages, ImagePlus anchorImage) {
        PipelineDialog dialog = new PipelineDialog(title);
        installWizardCancelHook(dialog);
        dialog.setModal(false);
        dialog.setPrimaryButtonText("Lock in");
        if (header != null && !header.trim().isEmpty()) {
            dialog.addHeader(header);
        }
        if (messages != null) {
            for (String message : messages) {
                if (message != null && !message.trim().isEmpty()) {
                    dialog.addMessage(message);
                }
            }
        }
        addSkipCurrentImageButton(dialog);
        positionPipelineDialogBesideImage(dialog, anchorImage);
        if (!dialog.showDialog()) {
            return isSkipCurrentImageAction(dialog) ? ACTION_SKIP_CURRENT_IMAGE : "cancel";
        }
        return "continue";
    }

    private String showThresholdAdjustmentDialog(String title,
                                                 String imageTitle,
                                                 String filterPreset,
                                                 String currentThresholdToken,
                                                 int imgIdx,
                                                 int totalImages,
        ImagePlus anchorImage) {
        PipelineDialog dialog = new PipelineDialog(title);
        installWizardCancelHook(dialog);
        dialog.setModal(false);
        dialog.setPrimaryButtonText("Lock in");
        dialog.addHeader("Adjust Channel Threshold");
        dialog.addMessage("Image " + (imgIdx + 1) + "/" + totalImages + ": " + imageTitle);
        dialog.addMessage("Current saved threshold: " + thresholdTokenDisplay(currentThresholdToken));
        dialog.addSpacer(6);
        dialog.addMessage("The per-channel filter (" + filterPreset + ") has been applied.");
        dialog.addMessage("This single threshold is shared by classical object detection and ROI intensity measurements.");
        dialog.addMessage("Adjust the left/minimum Threshold slider, then click Lock in. FLASH saves the left/minimum value; the right/maximum slider can stay at the image maximum.");
        dialog.addMessage("Skip Current Image leaves the saved threshold unchanged and moves to the next image.");
        addSkipCurrentImageButton(dialog);
        positionPipelineDialogBesideImage(dialog, anchorImage);
        if (!dialog.showDialog()) {
            return isSkipCurrentImageAction(dialog) ? ACTION_SKIP_CURRENT_IMAGE : "cancel";
        }
        return "continue";
    }

    private String showContinueRestartDialog(String title, String summary, int imgIdx, int totalImages) {
        PipelineDialog pd = new PipelineDialog(title);
        installWizardCancelHook(pd);
        pd.addMessage(summary);
        pd.addSpacer(8);

        String[] actions;
        if (imgIdx >= totalImages - 1) {
            pd.addMessage("This was the last image. Accept values, restart, or skip remaining images?");
            actions = new String[]{"Accept and continue", "Restart from first image", "Skip Remaining Images"};
        } else {
            pd.addMessage("Proceed to next image, restart, or skip remaining images?");
            actions = new String[]{"Continue to next image", "Restart from first image", "Skip Remaining Images"};
        }
        pd.addChoice("Action", actions, actions[0]);
        if (!pd.showDialog()) return "cancel";

        String choice = pd.getNextChoice();
        if (choice.contains("Restart")) return "restart";
        if (choice.contains("Skip")) return "skip";
        return "continue";
    }

    // ── Auto-threshold helper ───────────────────────────────────────────

    private int autoThreshold(ImagePlus imp) {
        IJ.setAutoThreshold(imp, "Default dark");
        int thresh = (int) imp.getProcessor().getMinThreshold();
        IJ.resetThreshold(imp);
        return thresh > 0 ? thresh : 1;
    }

    private ThresholdConfirmationResult promptForThresholdToken(String dialogTitle,
                                                                String header,
                                                                String fieldLabel,
                                                                Double readThreshold,
                                                                String persistedToken,
                                                                int suggestedAutoThreshold) {
        String fieldValue = defaultThresholdFieldValue(readThreshold, persistedToken);
        while (true) {
            PipelineDialog dialog = new PipelineDialog(dialogTitle);
            installWizardCancelHook(dialog);
            dialog.enableBackButton();
            dialog.addAnalysisHelpHeader("Set Up Configuration", FLASH_Pipeline.IDX_CREATE_BIN);
            dialog.addSetupHelpSubHeader(header, SetupHelpCatalog.CHANNEL_THRESHOLD);
            dialog.addMessage("Current saved threshold: " + thresholdTokenDisplay(persistedToken));
            if (readThreshold != null) {
                dialog.addMessage("Read from image left/min slider: " + formatThresholdValue(readThreshold));
            } else {
                dialog.addMessage("Threshold could not be read from the image.");
                dialog.addHelpText("Enter a numeric threshold manually. Type 'default' only if you want automatic thresholding.");
                dialog.addHelpText("Suggested automatic threshold: " + suggestedAutoThreshold);
            }
            final JTextField thresholdField = dialog.addStringField(fieldLabel, fieldValue, 20);
            JLabel thresholdHint = dialog.addHelpText("");
            bindValidation(dialog, thresholdField, new ValidationCheck() {
                @Override public boolean isValid() {
                    return isValidThresholdToken(normalizeThresholdToken(thresholdField.getText()));
                }
            }, thresholdHint, "Enter 'default' or a numeric threshold.");
            JButton skip = dialog.addFooterButton("Skip Current Image");
            skip.addActionListener(e -> dialog.closeWithAction(ACTION_SKIP_CURRENT_IMAGE));
            if (!dialog.showDialog()) {
                if (ACTION_SKIP_CURRENT_IMAGE.equals(dialog.getActionCommand())) {
                    return new ThresholdConfirmationResult(null, false, false, true);
                }
                if (dialog.wasBackPressed()) return new ThresholdConfirmationResult(null, true, false);
                return new ThresholdConfirmationResult(null, false, true);
            }

            String token = normalizeThresholdToken(dialog.getNextString());
            if (isValidThresholdToken(token)) {
                return new ThresholdConfirmationResult(token, false, false);
            }

            IJ.showMessage(dialogTitle, "Enter 'default' or a numeric threshold.");
            fieldValue = token;
        }
    }

    private String thresholdTokenDisplay(String token) {
        String normalized = normalizeThresholdToken(token);
        return normalized.isEmpty() ? "not set" : normalized;
    }

    private String defaultThresholdFieldValue(Double readThreshold, String persistedToken) {
        if (readThreshold != null) return formatThresholdValue(readThreshold);
        String normalizedPersisted = normalizeThresholdToken(persistedToken);
        if (isNumericThresholdToken(normalizedPersisted)) return normalizedPersisted;
        return "";
    }

    private Double readThresholdFromImage(ImagePlus imp) {
        if (imp == null) return null;
        Double current = sanitizeThresholdValue(imp.getProcessor());
        if (current != null) return current;
        if (imp.getStack() == null || imp.getStackSize() <= 0) return null;
        int slice = imp.getCurrentSlice();
        if (slice < 1) slice = 1;
        if (slice > imp.getStackSize()) slice = imp.getStackSize();
        return sanitizeThresholdValue(imp.getStack().getProcessor(slice));
    }

    private Double sanitizeThresholdValue(ImageProcessor processor) {
        if (processor == null) return null;
        // ImageJ's left slider is the minimum threshold. The setup UI tells
        // users to adjust this side, so this is the value persisted to config.
        double threshold = processor.getMinThreshold();
        if (!isValidThresholdValue(threshold)) return null;
        return threshold;
    }

    private boolean isValidThresholdValue(double threshold) {
        return !Double.isNaN(threshold)
                && !Double.isInfinite(threshold)
                && threshold != ImageProcessor.NO_THRESHOLD;
    }

    static boolean isValidThresholdToken(String token) {
        return "default".equalsIgnoreCase(token) || isNumericThresholdToken(token);
    }

    static boolean isNumericThresholdToken(String token) {
        if (token == null || token.trim().isEmpty()) return false;
        try {
            double parsed = Double.parseDouble(token.trim());
            return !Double.isNaN(parsed) && !Double.isInfinite(parsed);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static String normalizeThresholdToken(String token) {
        if (token == null) return "";
        String trimmed = token.trim();
        if ("default".equalsIgnoreCase(trimmed)) return "default";
        return trimmed;
    }

    private String formatThresholdValue(double threshold) {
        return String.valueOf((int) Math.round(threshold));
    }

    // ── Find 3D OC objects map ──────────────────────────────────────────

    private ImagePlus findObjectsMap() {
        int[] ids = WindowManager.getIDList();
        if (ids != null) {
            for (int id : ids) {
                ImagePlus imp = WindowManager.getImage(id);
                if (imp != null && imp.getTitle().startsWith("Objects map")) return imp;
            }
        }
        return null;
    }

    // ── Close Results/Statistics windows from 3D OC ─────────────────────

    private void closeResultsWindows() {
        java.awt.Frame[] frames = WindowManager.getNonImageWindows();
        if (frames != null) {
            for (java.awt.Frame f : frames) {
                String title = f.getTitle();
                if (title != null && !title.equals("Log")
                        && (title.startsWith("Results") || title.startsWith("Statistics")
                        || title.startsWith("Summary"))) {
                    f.dispose();
                }
            }
        }
    }

    // ── Apply intensity filter ──────────────────────────────────────────

    private void applyIntensityFilter(ImagePlus imp) {
        FilterExecutor.runMacroString(imp, INTENSITY_FILTER);
    }

    // ── Filter resolution ─────────────────────────────────────────────

    /**
     * Resolves the filter macro content for a channel: first checks saved configuration macros,
     * then falls back to the bundled preset from JAR resources.
     */
    private String resolveFilterContent(File binFolder, BinUserConfig cfg, int channelIndex) {
        File saved = new File(binFolder, "C" + (channelIndex + 1) + "_Filters.ijm");
        if (saved.exists()) {
            try {
                return new String(Files.readAllBytes(saved.toPath()), StandardCharsets.UTF_8);
            } catch (IOException e) {
                IJ.log("Warning: could not read " + saved.getName() + ": " + e.getMessage());
            }
        }
        return getFilterPresetContent(binFolder, cfg.filterPresets.get(channelIndex));
    }

    private String getFilterPresetContent(String presetName) {
        return NamedFilterLoader.loadFilterContent(presetName);
    }

    private String getFilterPresetContent(File binFolder, String presetName) {
        String bundled = NamedFilterLoader.loadFilterContent(presetName);
        if (bundled != null) return bundled;
        return loadSavedCustomFilterPreset(binFolder, presetName);
    }

    // ── File writing ────────────────────────────────────────────────────

    private void writeDefaultFilter(File binFolder) throws IOException {
        Path p = binFolder.toPath().resolve("defaultFilter.ijm");
        String content = NamedFilterLoader.loadDefaultFilter();
        if (content != null) {
            Files.write(p, content.getBytes(StandardCharsets.UTF_8));
            recordOutput(p.toFile(), "ijm");
        }
    }

    protected void writeBinConfigFiles(File binFolder, BinUserConfig cfg) throws IOException {
        writeFinalChannelConfig(binFolder, cfg, null);
    }

    private void writeFinalChannelConfig(File binFolder, BinUserConfig cfg,
                                         boolean[][] customSettings) throws IOException {
        ChannelConfig cc = mergeIntoExisting(
                binFolder, cfg, customSettings, 6, wizardStepLabel(6),
                -1, null, ChannelConfig.PropertyStatus.COMMITTED);
        for (int i = 0; i < cc.channels.size(); i++) {
            ChannelConfig.Channel channel = cc.channels.get(i);
            if (cfg != null && cfg.filterPresets != null && i < cfg.filterPresets.size()) {
                channel.filterPreset = safeFilterPresetSelection(cfg.filterPresets.get(i), "Custom");
            }
            markAllKnown(channel, ChannelConfig.PropertyStatus.COMMITTED);
        }
        cc.complete = Boolean.TRUE;   // single, crash-proof "configuration finished" signal
        ChannelConfigIO.write(channelConfigSettingsDir(binFolder), cc);
        recordOutput(new File(channelConfigSettingsDir(binFolder), ChannelConfigIO.FILE_NAME), "json");
        recordCreateBinRunParameters(cc);
        File projectRoot = projectRootForConfigurationDir(binFolder);
        if (projectRoot != null) {
            AnalysisStatusScanner.writeSidecar(projectRoot,
                    AnalysisStatusScanner.CREATE_BIN_ID,
                    AnalysisStatusScanner.estimateImageCount(projectRoot.getAbsolutePath()));
        }
    }

    /**
     * Capture the confirmed channel configuration into the active run record so
     * "Load settings from previous run" can restore it. Without this, an
     * interactive Set Up Configuration run records an empty parameter map and
     * the loader falls back to defaults. {@code cc} here is the final committed
     * configuration ({@code complete=TRUE}, all channels COMMITTED) that was just
     * written to {@code channel_config.json}; its flat keys round-trip through
     * {@link LoadedRunParameters} on load.
     */
    private void recordCreateBinRunParameters(ChannelConfig cc) {
        if (runRecordContext == null || cc == null) {
            return;
        }
        try {
            runRecordContext.recordParameters(ParameterSnapshot.fromChannelConfig(cc));
        } catch (RuntimeException e) {
            IJ.log("[FLASH] Could not capture Set Up Configuration run parameters: " + e.getMessage());
        }
    }

    private File projectRootForConfigurationDir(File configDir) {
        return FlashProjectLayout.projectRootForConfigurationDir(configDir);
    }

    private static ModelCatalog readModelCatalog(File projectRoot) {
        if (projectRoot == null) return null;
        try {
            return ModelCatalogIO.read(projectRoot.toPath());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void writeChannelFilters(File binFolder, BinUserConfig cfg) throws IOException {
        for (int c = 0; c < cfg.names.size(); c++) {
            String preset = safeFilterPresetSelection(cfg.filterPresets.get(c), "Custom");
            if (shouldSkipHandledCustomFilterWrite(binFolder, c, preset)) continue;
            Path p = binFolder.toPath().resolve("C" + (c + 1) + "_Filters.ijm");
            String content;
            if ("Custom".equals(preset)) {
                content = canShowCustomFilterDialog() ? null : getFilterPresetContent("Default");
            } else {
                content = getFilterPresetContent(binFolder, preset);
            }
            if (content != null) {
                Files.write(p, content.getBytes(StandardCharsets.UTF_8));
                recordOutput(p.toFile(), "ijm");
            }
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

    private boolean promptAndApplyCustomFilter(File binFolder, BinUserConfig cfg, int channelIndex,
                                               boolean writeConfigOnDemote) throws IOException {
        return promptAndApplyCustomFilter(binFolder, cfg, channelIndex, writeConfigOnDemote, null);
    }

    private boolean promptAndApplyCustomFilter(File binFolder, BinUserConfig cfg, int channelIndex,
                                               boolean writeConfigOnDemote,
                                               List<QcImageSelection> qcImages) throws IOException {
        if (cfg == null || channelIndex < 0 || channelIndex >= cfg.names.size()) return false;
        if (!canShowCustomFilterDialog()) return false;
        String chLabel = "C" + (channelIndex + 1) + " (" + cfg.names.get(channelIndex) + ")";

        // When this channel already has a custom filter, offer Continue / Adjust /
        // Start over before falling through to the three-tile authoring chooser.
        String existing = resolveFilterContent(binFolder, cfg, channelIndex);
        boolean hasExisting = existing != null && !existing.trim().isEmpty();
        boolean seedExistingAuthoringTools = hasExisting;
        if (hasExisting && !GraphicsEnvironment.isHeadless()) {
            CustomFilterContinueDialog.Action choice = CustomFilterContinueDialog.show(chLabel);
            if (choice == CustomFilterContinueDialog.Action.CANCEL) return false;
            if (choice == CustomFilterContinueDialog.Action.CONTINUE_BUILD) {
                return continueInBuilder(binFolder, cfg, channelIndex, writeConfigOnDemote, qcImages, chLabel);
            }
            if (choice == CustomFilterContinueDialog.Action.ADJUST_PARAMS) {
                if (qcImages == null || qcImages.isEmpty()) {
                    IJ.showMessage("Custom Filter",
                            "Adjusting parameters needs at least one selected QC image.");
                    return false;
                }
                String r = interactiveFilterParameterQC(qcImages, cfg, binFolder, channelIndex);
                if ("cancel".equals(r)) return false;
                rememberHandledCustomFilter(binFolder, channelIndex, cfg);
                return true;
            }
            // START_OVER falls through to the three-tile chooser below, but
            // starts those authoring tools fresh rather than extending the
            // existing custom macro.
            seedExistingAuthoringTools = false;
        }

        final ImagePlus[] sampleHolder = new ImagePlus[1];
        flash.pipeline.ui.RecorderDialog.SampleSupplier sampleSupplier = qcImages == null
                ? createCustomFilterSampleSupplier()
                : createQcCustomFilterSampleSupplier(qcImages, cfg, channelIndex, chLabel, sampleHolder);
        try {
            CustomFilterEntryDialog.Result result = CustomFilterEntryDialog.show(
                    chLabel,
                    qcImages == null
                            ? createCustomFilterPreviewHandler(cfg, channelIndex, chLabel)
                            : createQcCustomFilterPreviewHandler(qcImages, cfg, channelIndex, chLabel),
                    qcImages == null
                            ? createSandboxHandler(binFolder, cfg, channelIndex, chLabel, seedExistingAuthoringTools)
                            : createQcSandboxHandler(binFolder, cfg, channelIndex, chLabel, qcImages,
                                    seedExistingAuthoringTools),
                    sampleSupplier,
                    seedExistingAuthoringTools ? existing : null);
            boolean applied = applyCustomFilterEntryResult(binFolder, cfg, channelIndex, result, writeConfigOnDemote);
            if (applied) {
                rememberHandledCustomFilter(binFolder, channelIndex, cfg);
            }
            return applied;
        } finally {
            closeImageQuietly(sampleHolder[0]);
        }
    }

    /**
     * "Continue building" route from {@link CustomFilterContinueDialog}: opens
     * the visual builder directly with the existing macro pre-loaded, skipping
     * the three-tile authoring chooser.
     */
    private boolean continueInBuilder(File binFolder, BinUserConfig cfg, int channelIndex,
                                      boolean writeConfigOnDemote,
                                      List<QcImageSelection> qcImages,
                                      String chLabel) throws IOException {
        CustomFilterEntryDialog.SandboxHandler handler = qcImages == null
                ? createSandboxHandler(binFolder, cfg, channelIndex, chLabel)
                : createQcSandboxHandler(binFolder, cfg, channelIndex, chLabel, qcImages);
        CustomFilterEntryDialog.Result result = handler.openSandbox();
        boolean applied = applyCustomFilterEntryResult(binFolder, cfg, channelIndex, result, writeConfigOnDemote);
        if (applied) {
            rememberHandledCustomFilter(binFolder, channelIndex, cfg);
        }
        return applied;
    }

    private CustomFilterEntryDialog.PreviewHandler createCustomFilterPreviewHandler(final BinUserConfig cfg,
                                                                                   final int channelIndex,
                                                                                   final String chLabel) {
        return new CustomFilterEntryDialog.PreviewHandler() {
            private ImagePlus source;
            private ImagePlus preview;

            @Override public void preview(String macroContent) {
                if (source == null) {
                    ImagePlus current = WindowManager.getCurrentImage();
                    if (current == null) {
                        IJ.showMessage("Import Filter Macro",
                                "No open image is available for preview.\nOpen a sample image, then click Preview again.");
                        return;
                    }
                    int channelNum = channelIndex + 1;
                    source = duplicateChannelForSetupPreview(current, channelNum, cfg.names.size());
                }
                String color = channelIndex < cfg.colors.size() ? cfg.colors.get(channelIndex) : "Grays";
                String imageTitle = source.getTitle() == null ? "Preview" : source.getTitle();
                preview = renderFilterParameterPreview(source, macroContent, color, chLabel, imageTitle, preview);
            }

            @Override public void cleanup() {
                closeImageQuietly(preview);
                closeImageQuietly(source);
                preview = null;
                source = null;
            }
        };
    }

    private CustomFilterEntryDialog.PreviewHandler createQcCustomFilterPreviewHandler(
            final List<QcImageSelection> images,
            final BinUserConfig cfg,
            final int channelIndex,
            final String chLabel) {
        return new CustomFilterEntryDialog.PreviewHandler() {
            private ImagePlus source;
            private ImagePlus preview;

            @Override public void preview(String macroContent) {
                if (source == null) {
                    if (images == null || images.isEmpty()) {
                        IJ.showMessage("Import Filter Macro",
                                "No QC image is available for preview.");
                        return;
                    }
                    source = duplicateQcChannel(images.get(0), channelIndex,
                            cfg.names.size(), cfg.colors.get(channelIndex),
                            "Custom Filter Source | " + chLabel, false);
                    if (source == null) {
                        IJ.showMessage("Import Filter Macro",
                                "No QC image is available for preview.");
                        return;
                    }
                }
                String imageTitle = source.getTitle() == null ? "Preview" : source.getTitle();
                preview = renderFilterParameterPreview(source, macroContent,
                        cfg.colors.get(channelIndex), chLabel, imageTitle, preview);
            }

            @Override public void cleanup() {
                closeImageQuietly(preview);
                closeImageQuietly(source);
                preview = null;
                source = null;
            }
        };
    }

    private CustomFilterEntryDialog.SandboxHandler createSandboxHandler(final File binFolder,
                                                                        final BinUserConfig cfg,
                                                                        final int channelIndex,
                                                                        final String chLabel) {
        return createSandboxHandler(binFolder, cfg, channelIndex, chLabel, true);
    }

    private CustomFilterEntryDialog.SandboxHandler createSandboxHandler(final File binFolder,
                                                                        final BinUserConfig cfg,
                                                                        final int channelIndex,
                                                                        final String chLabel,
                                                                        final boolean startFromExisting) {
        return new CustomFilterEntryDialog.SandboxHandler() {
            @Override public CustomFilterEntryDialog.Result openSandbox() {
                String seedMacro = startFromExisting ? resolveFilterContent(binFolder, cfg, channelIndex) : null;
                SandboxDialog.Result result = SandboxDialog.show(
                        chLabel, binFolder, channelIndex, seedMacro,
                        createSandboxPreviewHandler(cfg, channelIndex, chLabel));
                if (result == null || result.dag == null) return CustomFilterEntryDialog.Result.cancel();
                return CustomFilterEntryDialog.Result.sandbox(result.dag, result.ijmFallback);
            }
        };
    }

    private CustomFilterEntryDialog.SandboxHandler createQcSandboxHandler(final File binFolder,
                                                                          final BinUserConfig cfg,
                                                                          final int channelIndex,
                                                                          final String chLabel,
                                                                          final List<QcImageSelection> images) {
        return createQcSandboxHandler(null, binFolder, cfg, channelIndex, chLabel, images, true);
    }

    private CustomFilterEntryDialog.SandboxHandler createQcSandboxHandler(final Window owner,
                                                                          final File binFolder,
                                                                          final BinUserConfig cfg,
                                                                          final int channelIndex,
                                                                          final String chLabel,
                                                                          final List<QcImageSelection> images,
                                                                          final boolean startFromExisting) {
        return createQcSandboxHandlerInternal(owner, binFolder, cfg, channelIndex, chLabel, images,
                startFromExisting);
    }

    private CustomFilterEntryDialog.SandboxHandler createQcSandboxHandler(final File binFolder,
                                                                          final BinUserConfig cfg,
                                                                          final int channelIndex,
                                                                          final String chLabel,
                                                                          final List<QcImageSelection> images,
                                                                          final boolean startFromExisting) {
        return createQcSandboxHandlerInternal(null, binFolder, cfg, channelIndex, chLabel, images,
                startFromExisting);
    }

    private CustomFilterEntryDialog.SandboxHandler createQcSandboxHandlerInternal(final Window owner,
                                                                                  final File binFolder,
                                                                                  final BinUserConfig cfg,
                                                                                  final int channelIndex,
                                                                                  final String chLabel,
                                                                                  final List<QcImageSelection> images,
                                                                                  final boolean startFromExisting) {
        return new CustomFilterEntryDialog.SandboxHandler() {
            @Override public CustomFilterEntryDialog.Result openSandbox() {
                String seedMacro = startFromExisting ? resolveFilterContent(binFolder, cfg, channelIndex) : null;
                if (seedMacro == null || seedMacro.trim().isEmpty()) {
                    seedMacro = getFilterPresetContent("Default");
                }
                SandboxDialog.Result result = SandboxDialog.show(
                        owner, chLabel, binFolder, channelIndex, seedMacro,
                        createQcSandboxPreviewHandler(cfg, channelIndex, chLabel, images));
                if (result == null || result.dag == null) return CustomFilterEntryDialog.Result.cancel();
                return CustomFilterEntryDialog.Result.sandbox(result.dag, result.ijmFallback);
            }
        };
    }

    private SandboxDialog.PreviewHandler createSandboxPreviewHandler(final BinUserConfig cfg,
                                                                     final int channelIndex,
                                                                     final String chLabel) {
        return new SandboxDialog.PreviewHandler() {
            private ImagePlus displaySource;

            @Override public ImagePlus createSource() {
                ImagePlus current = WindowManager.getCurrentImage();
                if (current == null) return null;
                return duplicateChannelForSetupPreview(current, channelIndex + 1, cfg.names.size());
            }

            @Override public ImagePlus getSourceForDisplay() {
                if (displaySource == null) {
                    displaySource = createSource();
                    if (displaySource != null) {
                        String imageTitle = displaySource.getTitle() == null
                                ? "Source"
                                : displaySource.getTitle();
                        displaySource.setTitle("Sandbox Source | " + chLabel + " | " + imageTitle);
                        applyPreviewLut(displaySource, channelColor(cfg, channelIndex));
                    }
                }
                return displaySource;
            }

            @Override public ImagePlus showPreview(ImagePlus result, ImagePlus existingPreview) {
                String color = channelColor(cfg, channelIndex);
                String imageTitle = result == null || result.getTitle() == null ? "Preview" : result.getTitle();
                return renderDagPreview(result, existingPreview, color, chLabel, imageTitle);
            }

            @Override public void close(ImagePlus imp) {
                if (imp == displaySource) {
                    displaySource = null;
                }
                closeImageQuietly(imp);
            }
        };
    }

    private SandboxDialog.PreviewHandler createQcSandboxPreviewHandler(final BinUserConfig cfg,
                                                                       final int channelIndex,
                                                                       final String chLabel,
                                                                       final List<QcImageSelection> images) {
        return new SandboxDialog.PreviewHandler() {
            private ImagePlus displaySource;

            @Override public ImagePlus createSource() {
                if (images == null || images.isEmpty()) return null;
                return duplicateQcChannel(images.get(0), channelIndex,
                        cfg.names.size(), channelColor(cfg, channelIndex),
                        "Sandbox Source | " + chLabel, false);
            }

            @Override public ImagePlus getSourceForDisplay() {
                if (displaySource == null) {
                    displaySource = createSource();
                    applyPreviewLut(displaySource, channelColor(cfg, channelIndex));
                }
                return displaySource;
            }

            @Override public ImagePlus showPreview(ImagePlus result, ImagePlus existingPreview) {
                String color = channelColor(cfg, channelIndex);
                String imageTitle = result == null || result.getTitle() == null ? "Preview" : result.getTitle();
                return renderDagPreview(result, existingPreview, color, chLabel, imageTitle);
            }

            @Override public void close(ImagePlus imp) {
                if (imp == displaySource) {
                    displaySource = null;
                }
                closeImageQuietly(imp);
            }
        };
    }

    private flash.pipeline.ui.RecorderDialog.SampleSupplier createCustomFilterSampleSupplier() {
        return new flash.pipeline.ui.RecorderDialog.SampleSupplier() {
            @Override public ImagePlus openSample() {
                return WindowManager.getCurrentImage();
            }
        };
    }

    private flash.pipeline.ui.RecorderDialog.SampleSupplier createQcCustomFilterSampleSupplier(
            final List<QcImageSelection> images,
            final BinUserConfig cfg,
            final int channelIndex,
            final String chLabel,
            final ImagePlus[] sampleHolder) {
        return new flash.pipeline.ui.RecorderDialog.SampleSupplier() {
            @Override public ImagePlus openSample() {
                if (images == null || images.isEmpty()) return null;
                closeImageQuietly(sampleHolder[0]);
                sampleHolder[0] = duplicateQcChannel(images.get(0), channelIndex,
                        cfg.names.size(), channelColor(cfg, channelIndex),
                        "Custom Filter Sample | " + chLabel, false);
                return sampleHolder[0];
            }

            @Override public void afterSampleShown(ImagePlus sample) {
                if (sample == null) return;
                IJ.run(sample, toLutName(channelColor(cfg, channelIndex)), "");
                positionImageLeft(sample);
                if (sample.getWindow() != null) {
                    WindowManager.setCurrentWindow(sample.getWindow());
                }
            }
        };
    }

    private void applyCustomFilterEntryResult(File binFolder, BinUserConfig cfg, int channelIndex,
                                              CustomFilterEntryDialog.Result result) throws IOException {
        applyCustomFilterEntryResult(null, binFolder, cfg, channelIndex, result, true);
    }

    private boolean applyCustomFilterEntryResult(File binFolder, BinUserConfig cfg, int channelIndex,
                                                 CustomFilterEntryDialog.Result result,
                                                 boolean writeConfigOnDemote) throws IOException {
        return applyCustomFilterEntryResult(null, binFolder, cfg, channelIndex, result, writeConfigOnDemote);
    }

    private boolean applyCustomFilterEntryResult(Window owner, File binFolder, BinUserConfig cfg, int channelIndex,
                                                 CustomFilterEntryDialog.Result result,
                                                 boolean writeConfigOnDemote) throws IOException {
        if (result == null) return false;

        Path p = binFolder.toPath().resolve("C" + (channelIndex + 1) + "_Filters.ijm");
        String savedMacro = null;
        if (result.choice == CustomFilterEntryDialog.Choice.IMPORT
                || result.choice == CustomFilterEntryDialog.Choice.RECORD) {
            if (result.macroContent == null) return false;
            Files.write(p, result.macroContent.getBytes(StandardCharsets.UTF_8));
            savedMacro = result.macroContent;
        } else if (result.choice == CustomFilterEntryDialog.Choice.SANDBOX) {
            if (result.dag == null || result.ijmFallback == null) return false;
            Path dagPath = binFolder.toPath().resolve("C" + (channelIndex + 1) + "_Sandbox.dag.json");
            Files.write(dagPath, DagIRSerializer.toJson(result.dag).getBytes(StandardCharsets.UTF_8));
            Files.write(p, result.ijmFallback.getBytes(StandardCharsets.UTF_8));
            savedMacro = result.ijmFallback;
            String matchedPreset = confirmSandboxPresetDemotion(result.ijmFallback);
            if (matchedPreset != null) {
                cfg.filterPresets.set(channelIndex, matchedPreset);
                if (writeConfigOnDemote) writeBinConfigFiles(binFolder, cfg);
                IJ.log("Sandbox filter for C" + (channelIndex + 1)
                        + " matches bundled preset '" + matchedPreset + "'. "
                        + (writeConfigOnDemote
                        ? "Storing that preset in channel_config.json."
                        : "That preset will be stored when the configuration is saved."));
            }
            if (matchedPreset == null) {
                saveNamedCustomFilterPreset(owner, binFolder, cfg, channelIndex, savedMacro);
            }
            return true;
        } else {
            return false;
        }

        if (result.demotedPreset != null) {
            cfg.filterPresets.set(channelIndex, result.demotedPreset);
            if (writeConfigOnDemote) writeBinConfigFiles(binFolder, cfg);
            String source = result.choice == CustomFilterEntryDialog.Choice.RECORD ? "recorded" : "imported";
            IJ.log("Custom filter for C" + (channelIndex + 1)
                    + " matches bundled preset '" + result.demotedPreset
                    + "'. " + (writeConfigOnDemote
                    ? "Storing that preset in channel_config.json"
                    : "That preset will be stored when the configuration is saved")
                    + " while preserving the " + source + " C" + (channelIndex + 1)
                    + "_Filters.ijm file.");
        } else {
            saveNamedCustomFilterPreset(owner, binFolder, cfg, channelIndex, savedMacro);
        }
        return true;
    }

    private void saveNamedCustomFilterPreset(File binFolder, BinUserConfig cfg, int channelIndex,
                                             String macroContent) throws IOException {
        saveNamedCustomFilterPreset(null, binFolder, cfg, channelIndex, macroContent);
    }

    private void saveNamedCustomFilterPreset(Window owner, File binFolder, BinUserConfig cfg, int channelIndex,
                                             String macroContent) throws IOException {
        if (macroContent == null || cfg == null || channelIndex < 0 || channelIndex >= cfg.names.size()) return;
        String channelName = cfg.names.get(channelIndex);
        String defaultName = channelName == null || channelName.trim().length() == 0
                ? "C" + (channelIndex + 1) + " Custom Filter"
                : channelName.trim() + " Filter";
        String presetName = promptForCustomFilterPresetName(owner,
                "C" + (channelIndex + 1) + " (" + cfg.names.get(channelIndex) + ")", defaultName);
        if (presetName == null) return;
        saveCustomFilterPreset(binFolder, presetName, macroContent);
        cfg.filterPresets.set(channelIndex, presetName);
        IJ.log("Saved custom filter preset '" + presetName + "' for C" + (channelIndex + 1) + ".");
    }

    private String confirmSandboxPresetDemotion(String ijmFallback) {
        if (ijmFallback == null) return null;
        if (GraphicsEnvironment.isHeadless()) return null;
        if (suppressDialogs) return null;
        flash.pipeline.ui.PresetMatcher.Match match =
                flash.pipeline.ui.PresetMatcher.match(ijmFallback);
        if (match == null) return null;
        boolean ok = IJ.showMessageWithCancel("Sandbox Filter",
                "The emitted Sandbox macro matches bundled preset '" + match.presetName
                        + "'.\nStore this channel as that preset instead of Custom?");
        return ok ? match.presetName : null;
    }

    // ── Parsing helpers ─────────────────────────────────────────────────

    static boolean isValidDisplayRangeToken(String token) {
        return "None".equalsIgnoreCase(token == null ? "" : token.trim()) || parseMinMax(token) != null;
    }

    static boolean isValidSizeRangeToken(String token) {
        return parseSizeRange(token) != null;
    }

    static boolean isValidNumericToken(String token) {
        if (token == null || token.trim().isEmpty()) return false;
        try {
            double parsed = Double.parseDouble(token.trim());
            return !Double.isNaN(parsed) && !Double.isInfinite(parsed);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static double[] parseMinMax(String token) {
        if (token == null || "None".equalsIgnoreCase(token)) return null;
        String[] parts = token.split("-");
        if (parts.length != 2) return null;
        try {
            return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static double[] parseSizeRange(String token) {
        if (token == null) return null;
        String[] parts = token.split("-");
        if (parts.length != 2) return null;
        try {
            double min = Double.parseDouble(parts[0]);
            double max = "Infinity".equalsIgnoreCase(parts[1])
                    ? Double.POSITIVE_INFINITY : Double.parseDouble(parts[1]);
            return new double[]{min, max};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── Image positioning ────────────────────────────────────────────────

    private void positionImageLeft(ImagePlus imp) {
        if (imp == null || imp.getWindow() == null) return;
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        imp.getWindow().setLocation(10, Math.min(50, screen.height / 6));
    }

    private void positionImageRightOf(ImagePlus anchor, ImagePlus imp) {
        if (imp == null || imp.getWindow() == null) return;
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        if (anchor != null && anchor.getWindow() != null) {
            java.awt.Window anchorWindow = anchor.getWindow();
            java.awt.Window imageWindow = imp.getWindow();
            int x = anchorWindow.getX() + anchorWindow.getWidth() + 20;
            int maxX = Math.max(10, screen.width - imageWindow.getWidth() - 20);
            int maxY = Math.max(0, screen.height - imageWindow.getHeight() - 40);
            imp.getWindow().setLocation(
                    Math.max(10, Math.min(x, maxX)),
                    Math.max(0, Math.min(anchorWindow.getY(), maxY)));
            return;
        }
        imp.getWindow().setLocation(
                Math.max(10, screen.width - imp.getWindow().getWidth() - 30),
                Math.min(50, screen.height / 6));
    }

    /**
     * Positions a tool window (e.g. "B&C", "Threshold") adjacent to the active image window,
     * anchored below the WaitForUserDialog slot (which sits at the image's top-right).
     * Falls back to screen top-right if no image window is available.
     */
    private void positionToolWindowNextToImage(String... toolWindowTitles) {
        java.awt.Frame frame = findToolWindow(toolWindowTitles);
        if (frame == null) return;
        ImagePlus activeImp = WindowManager.getCurrentImage();
        if (activeImp != null && activeImp.getWindow() != null) {
            java.awt.Window imgWin = activeImp.getWindow();
            int x = imgWin.getX() + imgWin.getWidth() + 10;
            // Reserve ~200px for the WaitForUserDialog above; tool goes below that
            int y = imgWin.getY() + 210;
            frame.setLocation(x, y);
        } else {
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            frame.setLocation(screen.width - frame.getWidth() - 20, 260);
        }
    }

    /**
     * Shows a WaitForUserDialog positioned adjacent to the active image window's right edge,
     * directly above the named tool window, forming a vertical column.
     */
    private void showDialogBesideImage(WaitForUserDialog dialog, String... toolWindowTitles) {
        ImagePlus activeImp = WindowManager.getCurrentImage();
        if (activeImp != null && activeImp.getWindow() != null) {
            java.awt.Window imgWin = activeImp.getWindow();
            int x = imgWin.getX() + imgWin.getWidth() + 10;
            int y = imgWin.getY();
            dialog.setLocation(x, y);
        } else {
            java.awt.Frame toolFrame = findToolWindow(toolWindowTitles);
            int x = 600;
            int y;
            if (toolFrame != null) {
                x = toolFrame.getX();
                y = toolFrame.getY() - 210;
            } else {
                y = 50;
            }
            dialog.setLocation(x, Math.max(y, 0));
        }
        dialog.show();
    }

    /**
     * Closes a floating ImageJ tool window by title (e.g. "B&C", "Threshold").
     */
    private void closeToolWindows(String... toolWindowTitles) {
        java.awt.Frame[] frames = WindowManager.getNonImageWindows();
        if (frames == null) return;
        for (java.awt.Frame frame : frames) {
            if (frame == null) continue;
            if (matchesToolWindowTitle(frame.getTitle(), toolWindowTitles)) {
                frame.dispose();
            }
        }
    }

    private void closeQcToolWindows() {
        closeToolWindows(BRIGHTNESS_CONTRAST_WINDOW_TITLES);
        closeToolWindows(THRESHOLD_WINDOW_TITLES);
    }

    private java.awt.Frame findToolWindow(String... toolWindowTitles) {
        java.awt.Frame[] frames = WindowManager.getNonImageWindows();
        if (frames == null) return null;
        for (java.awt.Frame frame : frames) {
            if (frame == null) continue;
            if (matchesToolWindowTitle(frame.getTitle(), toolWindowTitles)) return frame;
        }
        return null;
    }

    private boolean matchesToolWindowTitle(String actualTitle, String... candidateTitles) {
        if (actualTitle == null || candidateTitles == null) return false;
        String normalizedActual = actualTitle.trim();
        for (String candidate : candidateTitles) {
            if (candidate == null) continue;
            String normalizedCandidate = candidate.trim();
            if (normalizedActual.equalsIgnoreCase(normalizedCandidate)
                    || normalizedActual.startsWith(normalizedCandidate + " ")) {
                return true;
            }
        }
        return false;
    }

    // ── Filter description helper ────────────────────────────────────────

    private static String segmentationDescriptionFor(String selection,
                                                     boolean starDistAvailable,
                                                     CellposeRuntime.Status cellposeStatus) {
        String normalized = selection == null ? SEGMENTATION_CLASSICAL : selection.trim();
        if (SEGMENTATION_STARDIST.equals(normalized)) {
            StringBuilder sb = new StringBuilder();
            sb.append("Best for round-ish nuclei or soma that touch each other and need instance separation.<br>");
            sb.append("Drawbacks: less suitable for irregular cytoplasmic or ramified shapes.");
            if (starDistAvailable) {
                sb.append("<br>Use when objects are compact enough for a star-convex outline model.");
            } else {
                sb.append("<br>Currently unavailable: ").append(StarDistDetector.getAvailabilityMessage());
            }
            return "<html><body style='width:280px;'>" + sb + "</body></html>";
        }
        if (SEGMENTATION_CELLPOSE.equals(normalized)) {
            StringBuilder sb = new StringBuilder();
            sb.append("Best when you need a more flexible whole-cell or irregular object segmentation than classical thresholding or StarDist.<br>");
            sb.append("Runs on the per-channel filtered stack, just like StarDist.<br>");
            sb.append("Drawbacks: requires a separate Python Cellpose install, and only cyto/cyto2/cyto3 can use an optional companion channel.");
            if (cellposeStatus != null) {
                sb.append("<br>").append(cellposeStatus.ready ? cellposeStatus.summary() : cellposeStatus.message);
            }
            return "<html><body style='width:280px;'>" + sb + "</body></html>";
        }
        if (SEGMENTATION_ENHANCED_CLASSICAL.equals(normalized)) {
            return "<html><body style='width:280px;'>"
                    + "Best when thresholding finds likely objects but shape or intensity filters are needed to remove debris.<br>"
                    + "Uses the same 3D Objects Counter detection as Classical, then filters labels by mcib3d measurements."
                    + "</body></html>";
        }
        if (isTrainedRfDisplayChoice(normalized)) {
            return "<html><body style='width:280px;'>"
                    + "Saved trained Random Forest model from this project's catalog.<br>"
                    + "Confirming this choice keeps the exact trained_rf token already saved for the channel."
                    + "</body></html>";
        }

        return "<html><body style='width:280px;'>"
                + "Best for bright, well-separated objects where filtering plus thresholding is enough.<br>"
                + "Use when objects are separated clearly enough that a single binary mask is a faithful outline.<br>"
                + "Drawbacks: touching objects merge easily and results are sensitive to threshold choice."
                + "</body></html>";
    }

    private static boolean cellposeReadyOrUnknown(CellposeRuntime.Status status) {
        return status != null && (status.ready || status.unknown);
    }

    private static String cellposeRuntimeSummary(CellposeRuntime.Status status) {
        if (status == null || status.unknown) {
            return "Checking Cellpose...";
        }
        if (status.ready) {
            return "Configured runtime: Cellpose "
                    + (status.cellposeVersion.isEmpty() ? "unknown" : status.cellposeVersion)
                    + ", GPU available=" + status.gpuAvailable;
        }
        return status.message == null || status.message.trim().isEmpty()
                ? "Cellpose is not configured yet."
                : status.message;
    }

    private static String segmentationChoiceForMethod(String method) {
        return segmentationChoiceForMethod(method, null);
    }

    private static String segmentationChoiceForMethod(String method, ModelCatalog modelCatalog) {
        if (method != null) {
            if (method.startsWith("enhanced_classical")) return SEGMENTATION_ENHANCED_CLASSICAL;
            if (method.startsWith("stardist")) return SEGMENTATION_STARDIST;
            if (method.startsWith("cellpose")) return SEGMENTATION_CELLPOSE;
            if (isTrainedRfToken(method)) return trainedRfChoiceForMethod(method, modelCatalog);
        }
        return SEGMENTATION_CLASSICAL;
    }

    static String segmentationChoiceForDialogDefault(String method,
                                                     boolean starDistAvailable,
                                                     boolean cellposeReady) {
        return segmentationChoiceForDialogDefault(method, starDistAvailable, cellposeReady, null);
    }

    static String segmentationChoiceForDialogDefault(String method,
                                                     boolean starDistAvailable,
                                                     boolean cellposeReady,
                                                     ModelCatalog modelCatalog) {
        String choice = segmentationChoiceForMethod(method, modelCatalog);
        if (SEGMENTATION_STARDIST.equals(choice) && !starDistAvailable) {
            return SEGMENTATION_CLASSICAL;
        }
        if (SEGMENTATION_CELLPOSE.equals(choice) && !cellposeReady) {
            return SEGMENTATION_CLASSICAL;
        }
        return choice;
    }

    private static String[] segmentationOptionsForMethod(String method, ModelCatalog modelCatalog) {
        String trainedRfChoice = isTrainedRfToken(method)
                ? trainedRfChoiceForMethod(method, modelCatalog)
                : null;
        if (trainedRfChoice == null || trainedRfChoice.trim().isEmpty()) {
            return SEGMENTATION_OPTIONS;
        }
        String[] options = new String[SEGMENTATION_OPTIONS.length + 1];
        System.arraycopy(SEGMENTATION_OPTIONS, 0, options, 0, SEGMENTATION_OPTIONS.length);
        options[SEGMENTATION_OPTIONS.length] = trainedRfChoice;
        return options;
    }

    private static boolean isTrainedRfToken(String method) {
        return method != null && method.trim().toLowerCase(Locale.ROOT).startsWith("trained_rf:");
    }

    private static boolean isTrainedRfDisplayChoice(String selection) {
        return selection != null && selection.trim().startsWith(SEGMENTATION_TRAINED_RF_PREFIX);
    }

    private static String trainedRfChoiceForMethod(String method, ModelCatalog modelCatalog) {
        String modelKey = trainedRfModelKey(method);
        String label = modelKey;
        if (modelCatalog != null && modelKey != null && !modelKey.trim().isEmpty()) {
            Optional<ModelEntry> entry = modelCatalog.get(modelKey.trim());
            if (entry.isPresent() && entry.get().name != null
                    && !entry.get().name.trim().isEmpty()) {
                label = entry.get().name.trim();
            }
        }
        if (label == null || label.trim().isEmpty()) {
            label = "Unknown model";
        }
        return SEGMENTATION_TRAINED_RF_PREFIX + label.trim();
    }

    private static String trainedRfModelKey(String method) {
        try {
            return SegmentationMethod.trainedRfModelKey(SegmentationTokenParser.parse(method));
        } catch (IllegalArgumentException e) {
            String token = method == null ? "" : method.trim();
            String[] parts = token.split(":", 3);
            return parts.length >= 2 ? parts[1].trim() : "";
        }
    }

    private static String defaultCellposeMethod(boolean preferGpu) {
        return CellposeParameterStage.formatMethod(new CellposeParameterStage.Parameters(
                BinConfig.DEFAULT_CELLPOSE_MODEL,
                -1,
                BinConfig.DEFAULT_CELLPOSE_DIAMETER,
                BinConfig.DEFAULT_CELLPOSE_FLOW_THRESHOLD,
                BinConfig.DEFAULT_CELLPOSE_CELLPROB_THRESHOLD,
                preferGpu));
    }

    private static String enhancedThresholdOrFallback(BinUserConfig cfg, int channelIndex) {
        String fallback = cfg != null && channelIndex >= 0 && channelIndex < cfg.objectThresholds.size()
                ? cfg.objectThresholds.get(channelIndex)
                : "0";
        if (cfg == null || channelIndex < 0 || channelIndex >= cfg.segmentationMethods.size()) {
            return fallback;
        }
        SegmentationMethod method = SegmentationTokenParser.parseLenient(cfg.segmentationMethods.get(channelIndex));
        if (!method.isEnhancedClassical()) return fallback;
        return String.valueOf((int) Math.round(SegmentationMethod.threshold(method)));
    }

    private static String enhancedSizeOrFallback(BinUserConfig cfg, int channelIndex) {
        String fallback = cfg != null && channelIndex >= 0 && channelIndex < cfg.sizes.size()
                ? cfg.sizes.get(channelIndex)
                : "100-Infinity";
        if (cfg == null || channelIndex < 0 || channelIndex >= cfg.segmentationMethods.size()) {
            return fallback;
        }
        SegmentationMethod method = SegmentationTokenParser.parseLenient(cfg.segmentationMethods.get(channelIndex));
        if (!method.isEnhancedClassical()) return fallback;
        int max = SegmentationMethod.maxSize(method);
        return SegmentationMethod.minSize(method) + "-"
                + (max == Integer.MAX_VALUE ? "Infinity" : String.valueOf(max));
    }

    private static int parseCellposeSecondChannel(String method) {
        if (method == null || !method.startsWith("cellpose:")) return -1;
        return SegmentationMethod.cellposeChan2(SegmentationTokenParser.parseLenient(method));
    }

    private static String buildCellposeMethod(double diameter,
                                              String modelSelection,
                                              double flowThreshold,
                                              double cellprobThreshold,
                                              boolean useGpu,
                                              int secondChannelIndex) {
        return CellposeParameterStage.formatMethod(new CellposeParameterStage.Parameters(
                SegmentationMethod.canonicalCellposeModelKey(modelSelection),
                secondChannelIndex,
                diameter,
                flowThreshold,
                cellprobThreshold,
                useGpu));
    }

    private static void normalizeNewStarDistSizeDefault(BinUserConfig cfg,
                                                        int channelIndex,
                                                        String previousMethod) {
        if (cfg == null || channelIndex < 0 || channelIndex >= cfg.segmentationMethods.size()) {
            return;
        }
        String currentMethod = cfg.segmentationMethods.get(channelIndex);
        if (!safe(currentMethod).startsWith("stardist")
                || safe(previousMethod).startsWith("stardist")) {
            return;
        }
        while (cfg.sizes.size() <= channelIndex) {
            cfg.sizes.add("100-Infinity");
        }
        if (isGenericParticleSizeDefault(cfg.sizes.get(channelIndex))) {
            cfg.sizes.set(channelIndex, "0-Infinity");
        }
    }

    private static boolean isGenericParticleSizeDefault(String token) {
        return token == null
                || token.trim().isEmpty()
                || "100-Infinity".equalsIgnoreCase(token.trim())
                || "100-inf".equalsIgnoreCase(token.trim());
    }

    private static LinkedHashMap<String, Integer> buildCellposeCompanionChoices(List<String> channelNames,
                                                                                int primaryChannelIndex) {
        LinkedHashMap<String, Integer> choices = new LinkedHashMap<String, Integer>();
        choices.put("None", Integer.valueOf(-1));
        if (channelNames == null) return choices;
        for (int i = 0; i < channelNames.size(); i++) {
            if (i == primaryChannelIndex) continue;
            String channelName = channelNames.get(i);
            choices.put("C" + (i + 1) + " (" + channelName + ")", Integer.valueOf(i));
        }
        return choices;
    }

    private static String cellposeCompanionChoiceLabel(Map<String, Integer> choices, int secondChannelIndex) {
        if (choices == null || choices.isEmpty()) return "None";
        for (Map.Entry<String, Integer> entry : choices.entrySet()) {
            Integer value = entry.getValue();
            if (value != null && value.intValue() == secondChannelIndex) {
                return entry.getKey();
            }
        }
        return "None";
    }

    private static int selectedCellposeCompanionIndex(Map<String, Integer> choices, Object selectedItem) {
        if (choices == null || choices.isEmpty() || selectedItem == null) return -1;
        Integer value = choices.get(String.valueOf(selectedItem));
        return value == null ? -1 : value.intValue();
    }

    private static void updateCellposeCompanionVisibility(JComboBox<String> modelChoice,
                                                          JComboBox<String> companionChoice,
                                                          JLabel companionHelp) {
        if (modelChoice == null || companionChoice == null) return;
        CellposeModel model = CellposeModel.fromToken((String) modelChoice.getSelectedItem());
        boolean hasCompanionOptions = companionChoice.getItemCount() > 1;
        boolean visible = model.supportsSecondChannel() && hasCompanionOptions;
        Container row = companionChoice.getParent();
        if (row != null) {
            row.setVisible(visible);
            Container parent = row.getParent();
            if (parent != null) {
                parent.revalidate();
                parent.repaint();
            }
        }
        if (companionHelp != null) {
            companionHelp.setVisible(visible);
        }
    }

    private static void applySegmentationSelection(List<String> segmentationMethods,
                                                   int channelIndex,
                                                   String selection,
                                                   String existingMethod,
                                                   boolean preferCellposeGpu) {
        String selected = selection == null ? "" : selection.trim();
        if (isTrainedRfToken(selected)) {
            segmentationMethods.set(channelIndex, selected);
            return;
        }
        if (isTrainedRfToken(existingMethod) && isTrainedRfDisplayChoice(selected)) {
            segmentationMethods.set(channelIndex, existingMethod);
            return;
        }
        if (SEGMENTATION_ENHANCED_CLASSICAL.equals(selected)) {
            if (existingMethod != null && existingMethod.startsWith("enhanced_classical:")) {
                segmentationMethods.set(channelIndex, existingMethod);
            } else {
                segmentationMethods.set(channelIndex,
                        "enhanced_classical:thresh=0:minSize=100:maxSize=" + Integer.MAX_VALUE);
            }
            return;
        }
        if (SEGMENTATION_STARDIST.equals(selected)) {
            if (existingMethod != null && existingMethod.startsWith("stardist:")) {
                segmentationMethods.set(channelIndex, existingMethod);
            } else {
                segmentationMethods.set(channelIndex, "stardist:0.5:0.4");
            }
            return;
        }
        if (SEGMENTATION_CELLPOSE.equals(selected)) {
            if (existingMethod != null && existingMethod.startsWith("cellpose:")) {
                segmentationMethods.set(channelIndex, existingMethod);
            } else {
                segmentationMethods.set(channelIndex, defaultCellposeMethod(preferCellposeGpu));
            }
            return;
        }
        segmentationMethods.set(channelIndex, "classical");
    }

    private static boolean isClassicalSegmentation(BinUserConfig cfg, int channelIndex) {
        return !isEnhancedClassicalSegmentation(cfg, channelIndex)
                && !isStarDistSegmentation(cfg, channelIndex)
                && !isCellposeSegmentation(cfg, channelIndex)
                && !isTrainedRfSegmentation(cfg, channelIndex);
    }

    private static boolean isEnhancedClassicalSegmentation(BinUserConfig cfg, int channelIndex) {
        if (cfg == null || channelIndex < 0 || channelIndex >= cfg.segmentationMethods.size()) return false;
        return safe(cfg.segmentationMethods.get(channelIndex)).startsWith("enhanced_classical");
    }

    private static boolean isStarDistSegmentation(BinUserConfig cfg, int channelIndex) {
        if (cfg == null || channelIndex < 0 || channelIndex >= cfg.segmentationMethods.size()) return false;
        return safe(cfg.segmentationMethods.get(channelIndex)).startsWith("stardist");
    }

    private static boolean isCellposeSegmentation(BinUserConfig cfg, int channelIndex) {
        if (cfg == null || channelIndex < 0 || channelIndex >= cfg.segmentationMethods.size()) return false;
        return safe(cfg.segmentationMethods.get(channelIndex)).startsWith("cellpose");
    }

    private static boolean isTrainedRfSegmentation(BinUserConfig cfg, int channelIndex) {
        if (cfg == null || channelIndex < 0 || channelIndex >= cfg.segmentationMethods.size()) return false;
        return safe(cfg.segmentationMethods.get(channelIndex)).startsWith("trained_rf:");
    }

    private interface StagePredicate {
        boolean isApplicable();
    }

    private static final class ConditionalConfigQcStage implements ConfigQcStage {
        private final ConfigQcStage delegate;
        private final StagePredicate predicate;

        ConditionalConfigQcStage(ConfigQcStage delegate, StagePredicate predicate) {
            this.delegate = delegate;
            this.predicate = predicate;
        }

        @Override public String key() {
            return delegate == null ? ConfigQcStage.super.key() : delegate.key();
        }

        @Override public String title() {
            return delegate == null ? "" : delegate.title();
        }

        @Override public SetupHelpTopic helpTopic() {
            return delegate == null ? null : delegate.helpTopic();
        }

        @Override public boolean isApplicable(ConfigQcContext context) {
            return (predicate == null || predicate.isApplicable())
                    && delegate != null
                    && delegate.isApplicable(context);
        }

        @Override public boolean showPreviewDisplayControls() {
            return delegate == null || delegate.showPreviewDisplayControls();
        }

        @Override public boolean controlsCanExpand() {
            return delegate != null && delegate.controlsCanExpand();
        }

        @Override public void onEnter(ConfigQcContext context, flash.pipeline.ui.preview.PreviewPairPanel preview) {
            if (delegate != null) delegate.onEnter(context, preview);
        }

        @Override public JComponent buildControls(ConfigQcContext context, ConfigQcActions actions) {
            return delegate == null ? null : delegate.buildControls(context, actions);
        }

        @Override public boolean lockIn(ConfigQcContext context) {
            return delegate != null && delegate.lockIn(context);
        }

        @Override public void skipCurrentImage(ConfigQcContext context) {
            if (delegate != null) delegate.skipCurrentImage(context);
        }

        @Override public void skipCurrentStage(ConfigQcContext context) {
            if (delegate != null) delegate.skipCurrentStage(context);
        }

        @Override public void restartStage(ConfigQcContext context) {
            if (delegate != null) delegate.restartStage(context);
        }

        @Override public void previousImage(ConfigQcContext context) {
            if (delegate != null) delegate.previousImage(context);
        }

        @Override public void onLeave(ConfigQcContext context) {
            if (delegate != null) delegate.onLeave(context);
        }
    }

    private static final class SegmentationMethodSwitchingStage implements ConfigQcStage {
        private final ConfigQcStage delegate;
        private final SegmentationMethodStage.MethodStore methodStore;

        SegmentationMethodSwitchingStage(ConfigQcStage delegate,
                                         SegmentationMethodStage.MethodStore methodStore) {
            this.delegate = delegate;
            this.methodStore = methodStore;
        }

        @Override public String key() {
            return delegate == null ? ConfigQcStage.super.key() : delegate.key();
        }

        @Override public String title() {
            return delegate == null ? "" : delegate.title();
        }

        @Override public SetupHelpTopic helpTopic() {
            if (delegate instanceof ClassicalSegmentationStage) {
                return SetupHelpCatalog.CLASSICAL_OBJECT_SEGMENTATION;
            }
            if (delegate instanceof EnhancedClassicalSegmentationStage) {
                return SetupHelpCatalog.CLASSICAL_OBJECT_SEGMENTATION;
            }
            if (delegate instanceof ChannelThresholdStage) {
                String choice = methodStore == null ? "" : methodStore.getChoice();
                if (SegmentationMethodStage.CLASSICAL.equals(choice)) {
                    return SetupHelpCatalog.CLASSICAL_OBJECT_SEGMENTATION;
                }
                return SetupHelpCatalog.CHANNEL_THRESHOLD;
            }
            if (delegate instanceof ParticleSizeStage) {
                return SetupHelpCatalog.CLASSICAL_OBJECT_SEGMENTATION;
            }
            if (delegate instanceof StarDistParameterStage) {
                return SetupHelpCatalog.STARDIST;
            }
            if (delegate instanceof CellposeParameterStage) {
                return SetupHelpCatalog.CELLPOSE;
            }
            return delegate == null ? null : delegate.helpTopic();
        }

        @Override public boolean isApplicable(ConfigQcContext context) {
            return delegate != null && delegate.isApplicable(context);
        }

        @Override public boolean showPreviewDisplayControls() {
            return delegate == null || delegate.showPreviewDisplayControls();
        }

        @Override public boolean controlsCanExpand() {
            return delegate != null && delegate.controlsCanExpand();
        }

        @Override public void onEnter(ConfigQcContext context, flash.pipeline.ui.preview.PreviewPairPanel preview) {
            if (delegate != null) delegate.onEnter(context, preview);
        }

        @Override public JComponent buildControls(ConfigQcContext context, ConfigQcActions actions) {
            JPanel panel = new JPanel(new BorderLayout(0, 8));
            panel.setOpaque(false);
            panel.add(SegmentationMethodStage.buildChangeMethodPanel(methodStore, actions),
                    BorderLayout.NORTH);
            JComponent controls = delegate == null ? null : delegate.buildControls(context, actions);
            if (controls != null) {
                panel.add(controls, BorderLayout.CENTER);
            }
            return panel;
        }

        @Override public boolean lockIn(ConfigQcContext context) {
            return delegate != null && delegate.lockIn(context);
        }

        @Override public void skipCurrentImage(ConfigQcContext context) {
            if (delegate != null) delegate.skipCurrentImage(context);
        }

        @Override public void skipCurrentStage(ConfigQcContext context) {
            if (delegate != null) delegate.skipCurrentStage(context);
        }

        @Override public void restartStage(ConfigQcContext context) {
            if (delegate != null) delegate.restartStage(context);
        }

        @Override public void previousImage(ConfigQcContext context) {
            if (delegate != null) delegate.previousImage(context);
        }

        @Override public void onLeave(ConfigQcContext context) {
            if (delegate != null) delegate.onLeave(context);
        }
    }

    private final class SegmentationMethodStore implements SegmentationMethodStage.MethodStore {
        private final BinUserConfig cfg;
        private final int channelIndex;
        private final Map<String, String> rememberedTokens = new LinkedHashMap<String, String>();

        SegmentationMethodStore(BinUserConfig cfg, int channelIndex) {
            this.cfg = cfg;
            this.channelIndex = channelIndex;
            rememberCurrentToken();
        }

        @Override public String getChoice() {
            return SegmentationMethodStage.choiceForMethodToken(currentToken());
        }

        @Override public String getMethodToken() {
            return currentToken();
        }

        @Override public void setMethodToken(String methodToken) {
            String token = methodToken == null || methodToken.trim().isEmpty()
                    ? "classical"
                    : methodToken.trim();
            while (cfg.segmentationMethods.size() <= channelIndex) {
                cfg.segmentationMethods.add("classical");
            }
            cfg.segmentationMethods.set(channelIndex, token);
            rememberCurrentToken();
        }

        @Override public boolean selectChoice(String choice) {
            String normalized = choice == null ? SEGMENTATION_CLASSICAL : choice.trim();
            rememberCurrentToken();
            boolean preferCellposeGpu = BinConfig.DEFAULT_CELLPOSE_USE_GPU;
            if (SEGMENTATION_STARDIST.equals(normalized)
                    && !gateStarDistFeature("StarDist 3D segmentation")) {
                return false;
            }
            if (SEGMENTATION_CELLPOSE.equals(normalized)) {
                if (!gateCellposeFeature("Cellpose segmentation")) {
                    return false;
                }
                CellposeRuntime.Status readyStatus = CellposeRuntime.probeConfigured();
                preferCellposeGpu = readyStatus.ready
                        ? readyStatus.gpuAvailable
                        : BinConfig.DEFAULT_CELLPOSE_USE_GPU;
            }

            String remembered = rememberedTokens.get(normalized);
            if (remembered != null && remembered.trim().length() > 0) {
                cfg.segmentationMethods.set(channelIndex, remembered);
            } else {
                String existingMethod = currentToken();
                applySegmentationSelection(cfg.segmentationMethods, channelIndex,
                        normalized, existingMethod, preferCellposeGpu);
                normalizeNewStarDistSizeDefault(cfg, channelIndex, existingMethod);
            }
            rememberCurrentToken();
            return true;
        }

        private void rememberCurrentToken() {
            String token = currentToken();
            rememberedTokens.put(SegmentationMethodStage.choiceForMethodToken(token), token);
        }

        private String currentToken() {
            if (cfg == null || channelIndex < 0) return "classical";
            while (cfg.segmentationMethods.size() <= channelIndex) {
                cfg.segmentationMethods.add("classical");
            }
            String token = cfg.segmentationMethods.get(channelIndex);
            return token == null || token.trim().isEmpty() ? "classical" : token;
        }
    }

    private static boolean selectionsContain(List<String> selections, String selection) {
        if (selections == null || selection == null) return false;
        for (String candidate : selections) {
            if (selection.equals(candidate)) return true;
        }
        return false;
    }

    private static String filterDescriptionFor(String presetName) {
        String desc = FILTER_DESCRIPTIONS.get(presetName);
        if (desc == null && presetName != null && presetName.trim().length() > 0) {
            desc = "User-saved custom filter macro.";
        }
        if (desc == null) desc = "";
        return "<html><body style='width:280px;'>" + desc + "</body></html>";
    }

    // ── LUT name normalization ──────────────────────────────────────────

    private static String toLutName(String color) {
        if (color == null) return "Grays";
        switch (color.trim().toUpperCase(Locale.ROOT)) {
            case "RED":     return "Red";
            case "GREEN":   return "Green";
            case "BLUE":    return "Blue";
            case "CYAN":    return "Cyan";
            case "MAGENTA": return "Magenta";
            case "YELLOW":  return "Yellow";
            case "GRAYS":
            default:        return "Grays";
        }
    }

    // ── Cleanup ─────────────────────────────────────────────────────────

    private static double parsePreviewNumber(JTextField field, double fallback) {
        if (field == null) return fallback;
        try {
            return Double.parseDouble(field.getText().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseSavedSegmentationDouble(String raw, double fallback, String context) {
        if (raw == null) {
            IJ.log("    - WARNING: Missing saved segmentation value for " + context
                    + "; using " + fallback + ".");
            return fallback;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            IJ.log("    - WARNING: Invalid saved segmentation value for " + context
                    + "='" + raw + "'; using " + fallback + ".");
            return fallback;
        }
    }

    private static double parseSavedSegmentationNonNegative(String raw, double fallback, String context) {
        return sanitizeNonNegative(parseSavedSegmentationDouble(raw, fallback, context));
    }

    private static int parseSavedSegmentationFrameGap(String raw, int fallback, String context) {
        return sanitizeFrameGap(parseSavedSegmentationDouble(raw, fallback, context));
    }

    private static double sanitizeNonNegative(double value) {
        return Math.max(0, value);
    }

    private static int sanitizeFrameGap(double value) {
        return Math.max(0, (int) Math.round(value));
    }

    private void cleanupImages(List<QcImageSelection> images) {
        closeQcToolWindows();
        for (QcImageSelection selection : images) {
            ImagePlus imp = selection == null ? null : selection.image;
            if (imp != null) {
                imp.changes = false;
                imp.close();
                imp.flush();
            }
        }
    }

    private void closeAllImageWindows() {
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
    }

    private boolean anyTrue(boolean[][] arr) {
        for (boolean[] row : arr)
            for (boolean b : row)
                if (b) return true;
        return false;
    }

    private boolean hasFiles(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        File[] files = dir.listFiles();
        return files != null && files.length > 0;
    }

    private void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursively(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
