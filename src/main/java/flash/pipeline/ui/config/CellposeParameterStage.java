package flash.pipeline.ui.config;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.cellpose.Cellpose3DRunner;
import flash.pipeline.cellpose.CellposeModel;
import flash.pipeline.cellpose.CellposeRuntime;
import flash.pipeline.click.ClickStore;
import flash.pipeline.click.suggest.CellposeFilterSuggester;
import flash.pipeline.click.suggest.SuggestionContext;
import flash.pipeline.help.SetupHelpCatalog;
import flash.pipeline.help.SetupHelpTopic;
import flash.pipeline.objects.ObjectsCounter3DWrapper;
import flash.pipeline.segmentation.SegmentationMethod;
import flash.pipeline.segmentation.SegmentationTokenParser;
import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;
import flash.pipeline.ui.FlashTheme;
import flash.pipeline.ui.ModelEntryListCellRenderer;
import flash.pipeline.ui.SegmentationModelManagerDialog;
import flash.pipeline.ui.ToggleSwitch;
import flash.pipeline.ui.preview.ObjectSizeFilterPreview;
import flash.pipeline.ui.preview.PreviewPairPanel;
import flash.pipeline.ui.variations.MontageDisplayActionDelegate;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterId;
import flash.pipeline.ui.variations.VariationEngineContext;
import flash.pipeline.ui.variations.VariationsDialog;
import ij.ImagePlus;
import ij.measure.ResultsTable;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Color;
import java.awt.Container;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class CellposeParameterStage implements ConfigQcStage {

    private static final Color HELP_COLOR = FlashTheme.TEXT_HELP;

    public interface ParameterStore {
        String getMethodToken();
        void save(String methodToken);
    }

    public interface SizeStore {
        String get();
        void set(String token);
    }

    public interface PreviewAdapter {
        ImagePlus createRawSource(ConfigQcContext context) throws Exception;
        ImagePlus createFilteredSource(ConfigQcContext context) throws Exception;
        ImagePlus createFilteredCompanionSource(ConfigQcContext context, int channelIndex) throws Exception;
        ImagePlus runPreview(ImagePlus filteredSource, ImagePlus filteredCompanionSource,
                             Parameters parameters) throws Exception;
        int countLabels(ImagePlus labelImage);
        void close(ImagePlus image);
    }

    public interface RuntimeAdapter {
        CellposeRuntime.Status cachedRuntimeStatus();
        CompletableFuture<CellposeRuntime.Status> probeRuntimeAsync();
        boolean nvidiaGpuLikelyAvailable();
        GpuInstallResult installGpuSupport();
    }

    public static final class GpuInstallResult {
        public final boolean success;
        public final String message;
        public final String details;

        public GpuInstallResult(boolean success, String message, String details) {
            this.success = success;
            this.message = message == null ? "" : message;
            this.details = details == null ? "" : details;
        }
    }

    public static final class Parameters {
        public final String modelToken;
        public final int secondChannelIndex;
        public final double diameter;
        public final double flowThreshold;
        public final double cellprobThreshold;
        public final boolean useGpu;
        public final boolean dumpCellprob;

        public Parameters(String modelToken,
                          int secondChannelIndex,
                          double diameter,
                          double flowThreshold,
                          double cellprobThreshold,
                          boolean useGpu) {
            this(modelToken, secondChannelIndex, diameter, flowThreshold,
                    cellprobThreshold, useGpu, false);
        }

        public Parameters(String modelToken,
                          int secondChannelIndex,
                          double diameter,
                          double flowThreshold,
                          double cellprobThreshold,
                          boolean useGpu,
                          boolean dumpCellprob) {
            this.modelToken = normalizeModelKey(modelToken);
            this.secondChannelIndex = sanitizeSecondChannelForKnownModel(this.modelToken, secondChannelIndex);
            this.diameter = sanitizeNonNegative(diameter);
            this.flowThreshold = flowThreshold;
            this.cellprobThreshold = cellprobThreshold;
            this.useGpu = useGpu;
            this.dumpCellprob = dumpCellprob;
        }

        static Parameters defaults(boolean useGpu) {
            return new Parameters(
                    BinConfig.DEFAULT_CELLPOSE_MODEL,
                    -1,
                    BinConfig.DEFAULT_CELLPOSE_DIAMETER,
                    BinConfig.DEFAULT_CELLPOSE_FLOW_THRESHOLD,
                    BinConfig.DEFAULT_CELLPOSE_CELLPROB_THRESHOLD,
                    useGpu);
        }
    }

    private static final String STALE_TEXT = "Preview is out of date. Press Run Preview.";
    private static final String EMPTY_TEXT = "Filtered input is ready. Press Run Preview.";

    private final ParameterStore parameterStore;
    private final SizeStore sizeStore;
    private final PreviewAdapter previewAdapter;
    private final RuntimeAdapter runtimeAdapter;
    private final LinkedHashMap<String, Integer> companionChoices;
    private final int channelCount;
    private final int primaryChannelIndex;
    private final boolean defaultUseGpu;

    private ConfigQcActions actions;
    private PreviewPairPanel preview;
    private ConfigQcContext activeContext;
    private Parameters savedParameters;
    private Parameters restartParameters;
    private ParticleSizeStage.SizeToken savedSize = new ParticleSizeStage.SizeToken("100", "Infinity");
    private ParticleSizeStage.SizeToken restartSize;
    private ImagePlus rawSource;
    private ImagePlus filteredSource;
    private ImagePlus labelPreview;
    private ImagePlus previousLabelPreview;
    private String previousPreviewText = "";
    private Parameters previousSettings;
    private ParticleSizeStage.SizeToken previousSettingsSize;
    private Parameters displayedSettings;
    private ParticleSizeStage.SizeToken displayedSize;
    private ResultsTable objectStats;
    private SwingWorker<ImagePlus, Void> previewWorker;
    private SwingWorker<GpuInstallResult, Void> installWorker;
    private boolean previewStale = true;
    private boolean updatingControls;
    private boolean showRawSource;
    private boolean savedMethodExplicitGpu;
    private boolean gpuEdited;
    private volatile boolean runtimeUiActive;
    private volatile int runtimeProbeRequestId;
    private int lastObjectCount = -1;
    private List<ModelOption> modelOptions = Collections.emptyList();
    private String missingModelKey;
    private String selectedModelKeySnapshot;
    private Parameters pendingDefaultsPrevious;
    private Parameters pendingDefaultsSuggested;

    private JComboBox<ModelOption> modelCombo;
    private JComboBox<ModelOption> missingModelReplacementCombo;
    private JComboBox<String> companionCombo;
    private JTextField diameterField;
    private JTextField flowField;
    private JTextField cellprobField;
    private JTextField sizeMinField;
    private JTextField sizeMaxField;
    private ToggleSwitch gpuSwitch;
    private JButton previewButton;
    private JButton installGpuButton;
    private JButton resetButton;
    private JButton variationsButton;
    private JButton manageModelsButton;
    private JPanel missingNoticeContainer;
    private ClickSuggestPanel suggestPanel;
    private JLabel missingModelNoticeLabel;
    private JLabel defaultsNoticeLabel;
    private JButton defaultsApplyButton;
    private JButton defaultsRevertButton;
    private JLabel modelDescriptionLabel;
    private JLabel companionHelpLabel;
    private JLabel runtimeLabel;
    private ObjectSizeCutoffPanel sizeCutoffPanel;
    private ObjectSizeFilterPreview.Summary sizeSummary;

    public CellposeParameterStage(ParameterStore parameterStore,
                                  PreviewAdapter previewAdapter,
                                  RuntimeAdapter runtimeAdapter,
                                  List<String> channelNames,
                                  int primaryChannelIndex,
                                  boolean defaultUseGpu) {
        this(parameterStore, defaultSizeStore(), previewAdapter, runtimeAdapter,
                channelNames, primaryChannelIndex, defaultUseGpu);
    }

    public CellposeParameterStage(ParameterStore parameterStore,
                                  SizeStore sizeStore,
                                  PreviewAdapter previewAdapter,
                                  RuntimeAdapter runtimeAdapter,
                                  List<String> channelNames,
                                  int primaryChannelIndex,
                                  boolean defaultUseGpu) {
        if (parameterStore == null) {
            throw new IllegalArgumentException("parameterStore must not be null");
        }
        if (sizeStore == null) {
            throw new IllegalArgumentException("sizeStore must not be null");
        }
        if (previewAdapter == null) {
            throw new IllegalArgumentException("previewAdapter must not be null");
        }
        this.parameterStore = parameterStore;
        this.sizeStore = sizeStore;
        this.previewAdapter = previewAdapter;
        this.runtimeAdapter = runtimeAdapter == null ? noopRuntimeAdapter() : runtimeAdapter;
        this.primaryChannelIndex = Math.max(0, primaryChannelIndex);
        this.channelCount = channelNames == null ? 0 : channelNames.size();
        this.defaultUseGpu = defaultUseGpu;
        this.companionChoices = buildCompanionChoices(channelNames, primaryChannelIndex);
        this.savedParameters = parseMethod(
                parameterStore.getMethodToken(),
                defaultUseGpu,
                channelCount,
                primaryChannelIndex);
        this.savedSize = ParticleSizeStage.parseSizeToken(sizeStore.get());
    }

    @Override
    public String title() {
        return "Cellpose";
    }

    @Override
    public SetupHelpTopic helpTopic() {
        return SetupHelpCatalog.CELLPOSE;
    }

    @Override
    public boolean controlsCanExpand() {
        return true;
    }

    @Override
    public JComponent buildControls(ConfigQcContext context, ConfigQcActions actions) {
        this.actions = actions;
        this.activeContext = context;
        String methodToken = parameterStore.getMethodToken();
        this.savedMethodExplicitGpu = hasExplicitGpuOption(methodToken);
        this.gpuEdited = false;
        this.runtimeUiActive = true;
        this.runtimeProbeRequestId++;
        this.savedParameters = restartParameters == null
                ? parseMethod(methodToken,
                defaultUseGpu,
                channelCount,
                primaryChannelIndex)
                : restartParameters;
        this.savedSize = restartSize == null
                ? ParticleSizeStage.parseSizeToken(sizeStore.get())
                : restartSize;
        this.modelOptions = modelOptionsFor(context);
        this.missingModelKey = containsModelKey(modelOptions, savedParameters.modelToken)
                ? null
                : savedParameters.modelToken;

        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(FlashTheme.pad(2, 0, 0, 0));
        missingNoticeContainer = buildMissingModelNoticeContainer();
        panel.add(missingNoticeContainer);
        refreshMissingModelNoticeRow();
        panel.add(buildModelRow());
        panel.add(Box.createVerticalStrut(4));
        panel.add(buildDefaultsRow());
        panel.add(Box.createVerticalStrut(4));
        panel.add(buildDetectionRow());
        panel.add(Box.createVerticalStrut(4));
        panel.add(buildSizeRow());
        panel.add(Box.createVerticalStrut(4));
        sizeCutoffPanel = new ObjectSizeCutoffPanel();
        panel.add(sizeCutoffPanel);
        panel.add(Box.createVerticalStrut(4));
        suggestPanel = buildClickSuggestPanel();
        panel.add(suggestPanel);
        panel.add(Box.createVerticalStrut(4));
        panel.add(buildHintRow());
        panel.add(Box.createVerticalStrut(4));
        panel.add(buildActionRow());
        loadFields(savedParameters);
        loadSizeFields(savedSize);
        refreshSizeCutoffPanelOnly();
        refreshCompanionState();
        markPreviewStale(EMPTY_TEXT);
        return panel;
    }

    @Override
    public void onEnter(ConfigQcContext context, PreviewPairPanel preview) {
        closePreviewWorker();
        closeImages();
        this.activeContext = context;
        this.preview = preview;
        showRawSource = false;
        if (preview != null) {
            preview.clearLargePreviewImages();
            preview.setSourceToggleVisible(true);
            preview.setSourceMode(PreviewPairPanel.SourceMode.FILTERED);
            preview.setSourceModeEnabled(true);
            preview.setComparisonPreviewVisible(true);
            preview.setComparisonRestoreAction(null);
            preview.setSourceModeChangeListener(mode -> {
                showRawSource = mode == PreviewPairPanel.SourceMode.RAW;
                refreshSourceAndOutputPreview();
            });
        }
        if (actions != null) {
            actions.registerPreviewButton(previewButton);
        }
        try {
            rawSource = previewAdapter.createRawSource(context);
            if (rawSource == null) {
                throw new IllegalStateException("No raw Cellpose input image is available.");
            }
            filteredSource = previewAdapter.createFilteredSource(context);
            if (filteredSource == null) {
                throw new IllegalStateException("No filtered Cellpose input image is available.");
            }
            if (preview != null) {
                preview.setOriginal(currentSourceImage());
                preview.setAdjusted(null);
                preview.setAdjustedState(PreviewPairPanel.PreviewState.STALE, EMPTY_TEXT);
            }
            refreshSizeCutoffPanelOnly();
            refreshLargePreviewModel();
            setStatus(EMPTY_TEXT);
            setVariationsButtonReady(true);
        } catch (Exception e) {
            closeImages();
            setVariationsButtonReady(false);
            setError("Could not prepare Cellpose input: " + e.getMessage());
        }
    }

    @Override
    public boolean lockIn(ConfigQcContext context) {
        if (missingModelKey != null) {
            setError("Cannot run segmentation: model missing.");
            return false;
        }
        try {
            Parameters parameters = collectParameters();
            ParticleSizeStage.SizeToken size = collectSizeToken();
            parameterStore.save(formatMethod(parameters));
            sizeStore.set(size.toToken());
            savedParameters = parameters;
            savedSize = size;
            restartParameters = null;
            restartSize = null;
            setStatus("Locked Cellpose parameters.");
            return true;
        } catch (RuntimeException e) {
            setError("Enter valid min and max voxel sizes.");
            return false;
        }
    }

    @Override
    public void skipCurrentImage(ConfigQcContext context) {
        setStatus("Skipped this image; Cellpose parameters are unchanged.");
    }

    @Override
    public void restartStage(ConfigQcContext context) {
        restartParameters = collectParameters();
        try {
            restartSize = collectSizeToken();
        } catch (RuntimeException ignored) {
            // Keep the prior restart value if the current fields are invalid.
        }
        setStatus("Restarting Cellpose review from the first image.");
    }

    @Override
    public void onLeave(ConfigQcContext context) {
        runtimeUiActive = false;
        runtimeProbeRequestId++;
        closePreviewWorker();
        closeInstallWorker();
        if (suggestPanel != null) {
            suggestPanel.dispose();
            suggestPanel = null;
        }
        if (preview != null) {
            preview.setSourceModeChangeListener(null);
            preview.setDisplaySettingsChangeListener(null);
            preview.setObjectSizeGuide(null);
            preview.clearComparisonPreview();
            preview.clearLargePreviewImages();
        }
        closeImages();
        setVariationsButtonReady(false);
        preview = null;
        activeContext = null;
    }

    boolean isPreviewStaleForTest() {
        return previewStale;
    }

    String currentMethodForTest() {
        return formatMethod(collectParameters());
    }

    void setDiameterForTest(String value) {
        if (diameterField != null) diameterField.setText(value);
    }

    void setFlowForTest(String value) {
        if (flowField != null) flowField.setText(value);
    }

    void setCellprobForTest(String value) {
        if (cellprobField != null) cellprobField.setText(value);
    }

    void setModelForTest(String model) {
        selectModelKey(model);
    }

    void setCompanionForTest(String label) {
        if (companionCombo != null) companionCombo.setSelectedItem(label);
    }

    void setUseGpuForTest(boolean useGpu) {
        if (gpuSwitch != null) {
            gpuSwitch.setSelected(useGpu);
            fieldChanged();
        }
    }

    void setSizeMinForTest(String value) {
        if (sizeMinField != null) sizeMinField.setText(value);
    }

    void setSizeMaxForTest(String value) {
        if (sizeMaxField != null) sizeMaxField.setText(value);
    }

    void runPreviewNowForTest() throws Exception {
        runPreviewNow();
    }

    String sizeCutoffSummaryForTest() {
        return sizeCutoffPanel == null ? "" : sizeCutoffPanel.summaryTextForTest();
    }

    void selectRawSourceForTest() {
        setRawSourceVisible(true);
    }

    void selectFilteredSourceForTest() {
        setRawSourceVisible(false);
    }

    void setShowOverlayForTest(boolean showOverlay) {
        if (preview != null) preview.setObjectOverlaySelected(showOverlay);
        refreshSourceAndOutputPreview();
    }

    boolean objectOverlaySelectedForTest() {
        return preview != null && preview.objectOverlaySelected();
    }

    String currentSourceTitleForTest() {
        ImagePlus source = currentSourceImage();
        return source == null ? null : source.getTitle();
    }

    int largePreviewPaneCountForTest() {
        return labelPreview == null ? 2 : 3;
    }

    void applyVariationComboForTest(ParameterCombo combo) {
        applyVariationCombo(combo);
    }

    String modelHintTextForTest() {
        return modelDescriptionLabel == null ? "" : modelDescriptionLabel.getText();
    }

    String companionHintTextForTest() {
        return companionHelpLabel == null ? "" : companionHelpLabel.getText();
    }

    String runtimeHintTextForTest() {
        return runtimeLabel == null ? "" : runtimeLabel.getText();
    }

    boolean installGpuButtonReachableForTest() {
        return installGpuButton != null && installGpuButton.isEnabled();
    }

    List<String> modelKeysForTest() {
        List<String> keys = new ArrayList<String>();
        if (modelCombo == null) return keys;
        for (int i = 0; i < modelCombo.getItemCount(); i++) {
            keys.add(modelCombo.getItemAt(i).entry.modelKey);
        }
        return keys;
    }

    String selectedModelKeyForTest() {
        ModelOption selected = selectedModelOption();
        return selected == null ? null : selected.entry.modelKey;
    }

    boolean manageModelsButtonEnabledForTest() {
        return manageModelsButton != null && manageModelsButton.isEnabled();
    }

    boolean defaultsApplyVisibleForTest() {
        return defaultsApplyButton != null && defaultsApplyButton.isVisible();
    }

    void applyPendingDefaultsForTest() {
        applyPendingDefaults();
    }

    void revertPendingDefaultsForTest() {
        revertPendingDefaults();
    }

    String missingModelNoticeTextForTest() {
        return missingModelNoticeLabel == null ? "" : missingModelNoticeLabel.getText();
    }

    boolean replacementSelectorVisibleForTest() {
        return missingModelReplacementCombo != null && missingModelReplacementCombo.isVisible();
    }

    boolean missingModelNoticeInPanelForTest() {
        return missingNoticeContainer != null
                && missingNoticeContainer.isVisible()
                && missingNoticeContainer.getComponentCount() > 0;
    }

    void refreshModelOptionsFromCatalogForTest() {
        refreshModelOptionsFromCatalog();
    }

    private JComponent buildMissingModelNoticeRow() {
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        row.setBorder(FlashTheme.pad(2, 0, 2, 0));

        missingModelNoticeLabel = new JLabel("Cellpose model '" + missingModelKey
                + "' is not in the catalog. Pick a replacement:");
        missingModelNoticeLabel.setForeground(FlashTheme.WARNING_FG);

        missingModelReplacementCombo = new JComboBox<ModelOption>(
                modelOptions.toArray(new ModelOption[0]));
        missingModelReplacementCombo.setRenderer(new ModelEntryListCellRenderer());
        missingModelReplacementCombo.addActionListener(e -> {
            if (updatingControls) return;
            ModelOption selected = (ModelOption) missingModelReplacementCombo.getSelectedItem();
            if (selected == null) return;
            resolveMissingModel(selected.entry.modelKey);
        });

        GridBagConstraints gbc = rowConstraints();
        row.add(missingModelNoticeLabel, gbc);
        gbc.gridx++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        row.add(missingModelReplacementCombo, gbc);
        return row;
    }

    private JPanel buildMissingModelNoticeContainer() {
        JPanel container = new JPanel();
        container.setOpaque(false);
        container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
        container.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        return container;
    }

    private void refreshMissingModelNoticeRow() {
        if (missingNoticeContainer == null) return;
        missingNoticeContainer.removeAll();
        if (missingModelKey == null) {
            missingModelNoticeLabel = null;
            missingModelReplacementCombo = null;
            missingNoticeContainer.setVisible(false);
        } else {
            missingNoticeContainer.add(buildMissingModelNoticeRow());
            missingNoticeContainer.add(Box.createVerticalStrut(4));
            missingNoticeContainer.setVisible(true);
        }
        missingNoticeContainer.revalidate();
        missingNoticeContainer.repaint();
        Container parent = missingNoticeContainer.getParent();
        if (parent != null) {
            parent.revalidate();
            parent.repaint();
        }
    }

    private JComponent buildModelRow() {
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        row.setBorder(FlashTheme.pad(2, 0, 2, 0));

        modelCombo = new JComboBox<ModelOption>(modelOptions.toArray(new ModelOption[0]));
        modelCombo.setRenderer(new ModelEntryListCellRenderer());
        modelCombo.addActionListener(e -> modelChanged());
        companionCombo = new JComboBox<String>(
                companionChoices.keySet().toArray(new String[0]));
        companionCombo.addActionListener(e -> companionChanged());
        gpuSwitch = new ToggleSwitch(false);
        gpuSwitch.addChangeListener(new Runnable() {
            @Override public void run() {
                if (!updatingControls) gpuEdited = true;
                fieldChanged();
            }
        });
        JLabel gpuLabel = new JLabel("Use GPU");
        gpuLabel.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        gpuLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (gpuSwitch.isEnabled()) {
                    gpuSwitch.setSelected(!gpuSwitch.isSelected());
                }
            }
        });
        installGpuButton = new JButton("Install GPU Support");
        installGpuButton.addActionListener(e -> installGpuSupport());

        GridBagConstraints gbc = rowConstraints();
        row.add(new JLabel("Model"), gbc);
        gbc.gridx++;
        gbc.weightx = 0.25;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        row.add(modelCombo, gbc);
        manageModelsButton = new JButton("Manage models...");
        manageModelsButton.setEnabled(true);
        manageModelsButton.setToolTipText("Open the segmentation model manager.");
        manageModelsButton.addActionListener(e -> openModelManager());
        gbc.gridx++;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        row.add(manageModelsButton, gbc);
        gbc.gridx++;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        row.add(new JLabel("Companion"), gbc);
        gbc.gridx++;
        gbc.weightx = 0.35;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        row.add(companionCombo, gbc);
        gbc.gridx++;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        JPanel gpuPanel = new JPanel();
        gpuPanel.setOpaque(false);
        gpuPanel.setLayout(new BoxLayout(gpuPanel, BoxLayout.X_AXIS));
        gpuPanel.add(gpuSwitch);
        gpuPanel.add(Box.createHorizontalStrut(FlashTheme.SPACE_S));
        gpuPanel.add(gpuLabel);
        row.add(gpuPanel, gbc);
        gbc.gridx++;
        row.add(installGpuButton, gbc);
        gbc.gridx++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        row.add(Box.createHorizontalGlue(), gbc);
        return row;
    }

    private JComponent buildDefaultsRow() {
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        row.setBorder(FlashTheme.pad(2, 0, 2, 0));
        defaultsNoticeLabel = hintLabel(" ");
        defaultsApplyButton = new JButton("Apply");
        defaultsApplyButton.setVisible(false);
        defaultsApplyButton.addActionListener(e -> applyPendingDefaults());
        defaultsRevertButton = new JButton("Revert");
        defaultsRevertButton.setVisible(false);
        defaultsRevertButton.addActionListener(e -> revertPendingDefaults());

        GridBagConstraints gbc = rowConstraints();
        row.add(defaultsNoticeLabel, gbc);
        gbc.gridx++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        row.add(Box.createHorizontalGlue(), gbc);
        gbc.gridx++;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        row.add(defaultsApplyButton, gbc);
        gbc.gridx++;
        row.add(defaultsRevertButton, gbc);
        return row;
    }

    private JComponent buildDetectionRow() {
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        row.setBorder(FlashTheme.pad(2, 0, 2, 0));

        JLabel heading = new JLabel("Detection:");
        heading.setFont(FlashTheme.bodyMedium());
        diameterField = createNumberField(6);
        flowField = createNumberField(5);
        cellprobField = createNumberField(5);

        GridBagConstraints gbc = rowConstraints();
        row.add(heading, gbc);
        gbc.gridx++;
        row.add(new JLabel("Diameter"), gbc);
        gbc.gridx++;
        row.add(diameterField, gbc);
        gbc.gridx++;
        row.add(new JLabel("Flow threshold"), gbc);
        gbc.gridx++;
        row.add(flowField, gbc);
        gbc.gridx++;
        row.add(new JLabel("Cell probability"), gbc);
        gbc.gridx++;
        row.add(cellprobField, gbc);
        gbc.gridx++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        row.add(Box.createHorizontalGlue(), gbc);
        return row;
    }

    private JComponent buildSizeRow() {
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        row.setBorder(FlashTheme.pad(2, 0, 2, 0));

        JLabel heading = new JLabel("Object size:");
        heading.setFont(FlashTheme.bodyMedium());
        sizeMinField = createSizeField(6);
        sizeMaxField = createSizeField(8);

        GridBagConstraints gbc = rowConstraints();
        row.add(heading, gbc);
        gbc.gridx++;
        row.add(new JLabel("Min"), gbc);
        gbc.gridx++;
        row.add(sizeMinField, gbc);
        gbc.gridx++;
        row.add(new JLabel("Max"), gbc);
        gbc.gridx++;
        row.add(sizeMaxField, gbc);
        gbc.gridx++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        row.add(Box.createHorizontalGlue(), gbc);
        return row;
    }

    private ClickSuggestPanel buildClickSuggestPanel() {
        return new ClickSuggestPanel(
                new ClickSuggestPanel.CountsProvider() {
                    @Override public ClickSuggestPanel.Counts counts() {
                        return currentClickCounts();
                    }
                },
                new ClickSuggestPanel.SuggestionProvider() {
                    @Override public ClickSuggestPanel.Suggestion suggest() {
                        return suggestCellposeFilters();
                    }
                },
                new ClickSuggestPanel.ToggleListener() {
                    @Override public void selectedChanged(boolean selected) {
                        markPreviewStale(selected
                                ? "Click suggestions enabled. Next Cellpose preview will keep cell probability data."
                                : STALE_TEXT);
                    }
                });
    }

    private ClickSuggestPanel.Counts currentClickCounts() {
        List<ClickStore.Click> clicks = currentClicks();
        int negative = 0;
        int positive = 0;
        for (int i = 0; i < clicks.size(); i++) {
            ClickStore.Click click = clicks.get(i);
            if (click == null) continue;
            if (click.verdict == ClickStore.Verdict.POSITIVE) positive++;
            else negative++;
        }
        return new ClickSuggestPanel.Counts(negative, positive);
    }

    private List<ClickStore.Click> currentClicks() {
        if (activeContext == null || activeContext.getClickStore() == null) {
            return Collections.emptyList();
        }
        return activeContext.getClickStore().forImageAndChannel(
                activeContext.getCurrentImageDisplayName(),
                activeContext.getChannelNumber());
    }

    private ClickSuggestPanel.Suggestion suggestCellposeFilters() {
        if (labelPreview == null) {
            setStatus("Run Cellpose preview before asking for click-based suggestions.");
            return null;
        }
        List<ClickStore.Click> negatives = new ArrayList<ClickStore.Click>();
        List<ClickStore.Click> positives = new ArrayList<ClickStore.Click>();
        splitClicks(currentClicks(), negatives, positives);
        Parameters parameters = collectParameters();
        Map<String, Double> params = new LinkedHashMap<String, Double>();
        params.put("cellprob_threshold", Double.valueOf(parameters.cellprobThreshold));
        params.put("diameter", Double.valueOf(parameters.diameter));
        CellposeFilterSuggester.CellposeSuggestion suggestion =
                new CellposeFilterSuggester().suggest(new SuggestionContext(
                        filteredSource, labelPreview, cellprobImageForSuggestion(),
                        negatives, positives, params));
        if (suggestion == null || !suggestion.hasSuggestion()) {
            return null;
        }
        List<ClickSuggestPanel.FieldSuggestion> fields =
                new ArrayList<ClickSuggestPanel.FieldSuggestion>();
        if (suggestion.cellprobThreshold != null) {
            fields.add(new ClickSuggestPanel.FieldSuggestion(
                    ClickSuggestPanel.ValueBinding.text("cellprob threshold", cellprobField),
                    formatNumber(suggestion.cellprobThreshold.doubleValue())));
        }
        if (suggestion.diameter != null) {
            fields.add(new ClickSuggestPanel.FieldSuggestion(
                    ClickSuggestPanel.ValueBinding.text("diameter", diameterField),
                    formatNumber(suggestion.diameter.doubleValue())));
        }
        return new ClickSuggestPanel.Suggestion(fields,
                suggestionMessage(fields, suggestion.badRemoved, suggestion.collateralRemoved),
                new Runnable() {
                    @Override public void run() {
                        applyCellposeSuggestion();
                    }
                },
                new Runnable() {
                    @Override public void run() {
                        markPreviewStale(STALE_TEXT);
                    }
                });
    }

    private ImagePlus cellprobImageForSuggestion() {
        if (labelPreview == null) return null;
        try {
            Object property = labelPreview.getProperty(Cellpose3DRunner.CELLPROB_IMAGE_PROPERTY);
            return property instanceof ImagePlus ? (ImagePlus) property : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void applyCellposeSuggestion() {
        try {
            Parameters parameters = collectParameters();
            parameterStore.save(formatMethod(parameters));
            savedParameters = parameters;
            runPreviewOnWorker();
        } catch (RuntimeException e) {
            setError("Could not apply suggested Cellpose parameters.");
        }
    }

    private JComponent buildHintRow() {
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        row.setBorder(FlashTheme.pad(2, 0, 2, 0));

        modelDescriptionLabel = hintLabel(" ");
        companionHelpLabel = hintLabel(" ");
        runtimeLabel = hintLabel(" ");
        refreshRuntimeLabel();

        GridBagConstraints gbc = rowConstraints();
        row.add(modelDescriptionLabel, gbc);
        gbc.gridx++;
        row.add(hintLabel("|"), gbc);
        gbc.gridx++;
        row.add(companionHelpLabel, gbc);
        gbc.gridx++;
        row.add(hintLabel("|"), gbc);
        gbc.gridx++;
        row.add(runtimeLabel, gbc);
        gbc.gridx++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        row.add(Box.createHorizontalGlue(), gbc);
        return row;
    }

    private JComponent buildActionRow() {
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setAlignmentX(JComponent.LEFT_ALIGNMENT);

        previewButton = new JButton("Run Preview");
        flash.pipeline.ui.FlashIcons.apply(previewButton, flash.pipeline.ui.FlashIcons.play());
        previewButton.addActionListener(e -> runPreviewOnWorker());
        resetButton = new JButton("Reset to saved");
        flash.pipeline.ui.FlashIcons.apply(resetButton, flash.pipeline.ui.FlashIcons.refresh());
        resetButton.addActionListener(e -> resetToSaved());

        GridBagConstraints gbc = rowConstraints();
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        row.add(Box.createHorizontalGlue(), gbc);
        gbc.gridx++;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        row.add(previewButton, gbc);
        gbc.gridx++;
        gbc.insets = new Insets(0, 2, 0, 0);
        row.add(resetButton, gbc);
        variationsButton = new JButton("Parameter Variations...");
        variationsButton.addActionListener(e -> openVariationsDialog());
        variationsButton.setEnabled(filteredSource != null);
        variationsButton.setToolTipText("Run/prepare a preview before opening parameter variations.");
        gbc.gridx++;
        row.add(variationsButton, gbc);
        return row;
    }

    private JTextField createNumberField(int columns) {
        JTextField field = new JTextField(columns);
        installFieldListener(field);
        return field;
    }

    private JTextField createSizeField(int columns) {
        JTextField field = new JTextField(columns);
        installSizeFieldListener(field);
        return field;
    }

    private JLabel hintLabel(String text) {
        JLabel label = new JLabel(text == null ? "" : text);
        label.setForeground(HELP_COLOR);
        return label;
    }

    private GridBagConstraints rowConstraints() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.gridx = 0;
        gbc.insets = new Insets(0, 0, 0, 6);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        return gbc;
    }

    private void installFieldListener(JTextField field) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) {
                fieldChanged();
            }

            @Override public void removeUpdate(DocumentEvent e) {
                fieldChanged();
            }

            @Override public void changedUpdate(DocumentEvent e) {
                fieldChanged();
            }
        });
    }

    private void installSizeFieldListener(JTextField field) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) {
                sizeFieldChanged();
            }

            @Override public void removeUpdate(DocumentEvent e) {
                sizeFieldChanged();
            }

            @Override public void changedUpdate(DocumentEvent e) {
                sizeFieldChanged();
            }
        });
    }

    private void loadFields(Parameters parameters) {
        updatingControls = true;
        try {
            selectModelKey(parameters.modelToken);
            selectedModelKeySnapshot = parameters.modelToken;
            companionCombo.setSelectedItem(companionChoiceLabel(companionChoices, parameters.secondChannelIndex));
            diameterField.setText(String.valueOf(parameters.diameter));
            flowField.setText(String.valueOf(parameters.flowThreshold));
            cellprobField.setText(String.valueOf(parameters.cellprobThreshold));
            gpuSwitch.setSelected(parameters.useGpu);
            refreshModelDescriptionLabel();
            refreshCompanionHelpLabel();
            refreshCompanionState();
        } finally {
            updatingControls = false;
        }
    }

    private void loadSizeFields(ParticleSizeStage.SizeToken token) {
        updatingControls = true;
        try {
            ParticleSizeStage.SizeToken safe = token == null
                    ? new ParticleSizeStage.SizeToken("100", "Infinity")
                    : token;
            if (sizeMinField != null) sizeMinField.setText(safe.minText);
            if (sizeMaxField != null) sizeMaxField.setText(safe.maxText);
        } finally {
            updatingControls = false;
        }
    }

    private void showPendingDefaults(Parameters previous, Parameters suggested, String modelName) {
        pendingDefaultsPrevious = copyParameters(previous);
        pendingDefaultsSuggested = copyParameters(suggested);
        highlightDefaultField(diameterField);
        highlightDefaultField(flowField);
        highlightDefaultField(cellprobField);
        if (defaultsNoticeLabel != null) {
            defaultsNoticeLabel.setText("Defaults from "
                    + (modelName == null || modelName.trim().isEmpty()
                    ? suggested.modelToken : modelName)
                    + " are pending.");
        }
        if (defaultsApplyButton != null) defaultsApplyButton.setVisible(true);
        if (defaultsRevertButton != null) defaultsRevertButton.setVisible(true);
        markPreviewStale(STALE_TEXT);
    }

    private void applyPendingDefaults() {
        if (pendingDefaultsSuggested == null) return;
        Parameters applied = copyParameters(pendingDefaultsSuggested);
        savedParameters = applied;
        selectedModelKeySnapshot = applied.modelToken;
        parameterStore.save(formatMethod(applied));
        clearPendingDefaults();
        if (labelPreview != null) {
            runPreviewOnWorker();
        } else {
            markPreviewStale(STALE_TEXT);
        }
    }

    private void revertPendingDefaults() {
        if (pendingDefaultsPrevious == null) return;
        Parameters previous = copyParameters(pendingDefaultsPrevious);
        loadFields(previous);
        selectedModelKeySnapshot = previous.modelToken;
        clearPendingDefaults();
        markPreviewStale(STALE_TEXT);
    }

    private void clearPendingDefaults() {
        pendingDefaultsPrevious = null;
        pendingDefaultsSuggested = null;
        resetDefaultField(diameterField);
        resetDefaultField(flowField);
        resetDefaultField(cellprobField);
        if (defaultsNoticeLabel != null) defaultsNoticeLabel.setText(" ");
        if (defaultsApplyButton != null) defaultsApplyButton.setVisible(false);
        if (defaultsRevertButton != null) defaultsRevertButton.setVisible(false);
    }

    private static boolean defaultsChanged(Parameters previous, Parameters suggested) {
        if (previous == null || suggested == null) return false;
        return previous.diameter != suggested.diameter
                || previous.flowThreshold != suggested.flowThreshold
                || previous.cellprobThreshold != suggested.cellprobThreshold;
    }

    private static void highlightDefaultField(JTextField field) {
        if (field != null) field.setBackground(new Color(255, 246, 170));
    }

    private static void resetDefaultField(JTextField field) {
        if (field != null) field.setBackground(Color.WHITE);
    }

    private void modelChanged() {
        if (!updatingControls) {
            ModelOption selected = selectedModelOption();
            if (selected != null) {
                Parameters current = collectParameters();
                Parameters previous = new Parameters(
                        selectedModelKeySnapshot == null ? current.modelToken : selectedModelKeySnapshot,
                        current.secondChannelIndex,
                        current.diameter,
                        current.flowThreshold,
                        current.cellprobThreshold,
                        current.useGpu,
                        current.dumpCellprob);
                Parameters updated = new Parameters(
                        selected.entry.modelKey,
                        selected.entry.supportsSecondChannel ? current.secondChannelIndex : -1,
                        defaultDouble(selected.entry.defaults.get("diameter"), current.diameter),
                        defaultDouble(selected.entry.defaults.get("flowThreshold"), current.flowThreshold),
                        defaultDouble(selected.entry.defaults.get("cellprobThreshold"), current.cellprobThreshold),
                        current.useGpu);
                loadFields(updated);
                captureCurrentPreviewForComparison();
                if (defaultsChanged(previous, updated)) {
                    showPendingDefaults(previous, updated, selected.entry.name);
                } else {
                    savedParameters = updated;
                    parameterStore.save(formatMethod(updated));
                    selectedModelKeySnapshot = updated.modelToken;
                    if (labelPreview != null) {
                        runPreviewOnWorker();
                    } else {
                        markPreviewStale(STALE_TEXT);
                    }
                }
            }
        }
        refreshModelDescriptionLabel();
        refreshCompanionState();
    }

    private void companionChanged() {
        refreshCompanionHelpLabel();
        fieldChanged();
    }

    private void refreshCompanionState() {
        if (companionCombo == null) return;
        boolean enabled = selectedModelSupportsSecondChannel() && companionCombo.getItemCount() > 1;
        companionCombo.setEnabled(enabled);
        if (!enabled) {
            updatingControls = true;
            try {
                companionCombo.setSelectedItem("None");
            } finally {
                updatingControls = false;
            }
        }
        refreshCompanionHelpLabel();
        Container parent = companionCombo.getParent();
        if (parent != null) {
            parent.revalidate();
            parent.repaint();
        }
    }

    private void refreshModelDescriptionLabel() {
        if (modelDescriptionLabel != null) {
            ModelOption selected = selectedModelOption();
            modelDescriptionLabel.setText(selected == null
                    ? "Model: no Cellpose models are available."
                    : selected.toString() + ": " + selected.description());
        }
    }

    private void refreshCompanionHelpLabel() {
        if (companionHelpLabel == null) return;
        boolean enabled = selectedModelSupportsSecondChannel()
                && companionCombo != null
                && companionCombo.getItemCount() > 1;
        if (!enabled) {
            companionHelpLabel.setText("Companion: not used by selected model.");
            return;
        }
        Object selected = companionCombo == null ? null : companionCombo.getSelectedItem();
        String label = selected == null ? "None" : String.valueOf(selected);
        if ("None".equals(label)) {
            companionHelpLabel.setText("Companion: optional second channel; leave None if unsure.");
        } else {
            companionHelpLabel.setText("Companion: using " + label + " as channel 2.");
        }
    }

    private void refreshRuntimeLabel() {
        if (runtimeLabel == null) {
            return;
        }
        runtimeLabel.setText(runtimeText(runtimeAdapter.cachedRuntimeStatus()));
        final int requestId = ++runtimeProbeRequestId;
        CompletableFuture<CellposeRuntime.Status> future = runtimeAdapter.probeRuntimeAsync();
        if (future == null) {
            return;
        }
        final WeakReference<CellposeParameterStage> stageRef =
                new WeakReference<CellposeParameterStage>(this);
        future.whenCompleteAsync(new RuntimeProbeCallback(stageRef, requestId),
                SwingUtilities::invokeLater);
    }

    private void applyRuntimeProbeResult(int requestId, CellposeRuntime.Status status, Throwable throwable) {
        if (!runtimeUiActive || runtimeProbeRequestId != requestId || runtimeLabel == null) {
            return;
        }
        if (throwable != null) {
            runtimeLabel.setText("Runtime: Cellpose probe failed.");
            return;
        }
        runtimeLabel.setText(runtimeText(status));
        applyRuntimeGpuDefault(status);
    }

    private void applyRuntimeGpuDefault(CellposeRuntime.Status status) {
        if (status == null || !status.ready || gpuSwitch == null
                || gpuEdited || savedMethodExplicitGpu) {
            return;
        }
        if (gpuSwitch.isSelected() == status.gpuAvailable) {
            return;
        }
        updatingControls = true;
        try {
            gpuSwitch.setSelected(status.gpuAvailable);
        } finally {
            updatingControls = false;
        }
        markPreviewStale(STALE_TEXT);
    }

    private static String runtimeText(CellposeRuntime.Status status) {
        if (status == null || status.unknown) {
            return "Runtime: Checking Cellpose...";
        }
        if (status.ready) {
            return "Runtime: Configured runtime: Cellpose "
                    + (status.cellposeVersion.isEmpty() ? "unknown" : status.cellposeVersion)
                    + ", GPU available=" + status.gpuAvailable;
        }
        return status.message == null || status.message.trim().isEmpty()
                ? "Runtime: not checked."
                : "Runtime: " + status.message;
    }

    private void fieldChanged() {
        if (updatingControls) return;
        markPreviewStale(STALE_TEXT);
    }

    private void sizeFieldChanged() {
        if (updatingControls) return;
        captureCurrentPreviewForComparison();
        if (!sizeFieldsReadyForLivePreview()) {
            markPreviewStale(STALE_TEXT);
            return;
        }
        if (!refreshSizeFilterPreview()) {
            markPreviewStale(STALE_TEXT);
        }
    }

    private void resetToSaved() {
        loadFields(savedParameters);
        loadSizeFields(savedSize);
        refreshCompanionState();
        captureCurrentPreviewForComparison();
        if (!refreshSizeFilterPreview()) {
            markPreviewStale(STALE_TEXT);
        }
    }

    private void runPreviewOnWorker() {
        if (previewWorker != null && !previewWorker.isDone()) return;
        if (missingModelKey != null) {
            setError("Cannot run segmentation: model missing.");
            return;
        }
        if (filteredSource == null) {
            setError("No Cellpose input image is available.");
            return;
        }
        final Parameters parameters = collectParameters();
        setPreviewState(PreviewPairPanel.PreviewState.RUNNING, "Running Cellpose preview...");
        setButtonsEnabled(false);
        previewWorker = new SwingWorker<ImagePlus, Void>() {
            @Override protected ImagePlus doInBackground() throws Exception {
                return runPreviewWithCompanion(parameters);
            }

            @Override protected void done() {
                if (isCancelled()) return;
                try {
                    installLabelPreview(get(), parameters);
                } catch (Exception e) {
                    setError("Cellpose preview failed: " + e.getMessage());
                } finally {
                    if (!isCancelled()) setButtonsEnabled(true);
                }
            }
        };
        previewWorker.execute();
    }

    private void runPreviewNow() throws Exception {
        if (missingModelKey != null) {
            throw new IllegalStateException("Cannot run segmentation: model missing.");
        }
        if (filteredSource == null) {
            throw new IllegalStateException("No Cellpose input image is available.");
        }
        Parameters parameters = collectParameters();
        setPreviewState(PreviewPairPanel.PreviewState.RUNNING, "Running Cellpose preview...");
        installLabelPreview(runPreviewWithCompanion(parameters), parameters);
    }

    private ImagePlus runPreviewWithCompanion(Parameters parameters) throws Exception {
        ImagePlus companion = null;
        try {
            if (parameters.secondChannelIndex >= 0) {
                companion = previewAdapter.createFilteredCompanionSource(
                        activeContext, parameters.secondChannelIndex);
            }
            return previewAdapter.runPreview(filteredSource, companion, parameters);
        } finally {
            if (companion != null) previewAdapter.close(companion);
        }
    }

    private void installLabelPreview(ImagePlus labelImage, Parameters settings) {
        if (labelImage == null) {
            setPreviewState(PreviewPairPanel.PreviewState.ERROR, "Cellpose returned no label map.");
            setStatus("Cellpose returned no label map.");
            return;
        }
        int count = previewAdapter.countLabels(labelImage);
        labelImage.setTitle("Cellpose label preview");
        captureCurrentPreviewForComparison();
        ImagePlus old = labelPreview;
        labelPreview = labelImage;
        objectStats = ObjectSizeFilterPreview.statisticsFromLabelMap(labelImage, filteredSource);
        previewStale = false;
        lastObjectCount = count;
        refreshSizeFilterPreview();
        displayedSettings = copyParameters(settings);
        String text = objectCountText();
        setStatus(text);
        if (actions != null) actions.setPreviewButtonStale(false);
        if (old != null && old != labelImage) {
            previewAdapter.close(old);
        }
    }

    private void refreshSourceAndOutputPreview() {
        if (preview != null) {
            preview.setOriginal(currentSourceImage());
        }
        refreshLargePreviewModel();
        if (labelPreview == null) return;

        String text = objectCountText();
        if (previewStale) {
            if (preview != null) {
                preview.setAdjusted(labelPreview);
                preview.setAdjustedState(PreviewPairPanel.PreviewState.STALE, text);
            }
            if (actions != null) {
                actions.markPreviewStale(text);
                actions.setPreviewButtonStale(true);
            }
        } else {
            if (preview != null) {
                preview.setAdjusted(labelPreview);
                preview.setAdjustedState(PreviewPairPanel.PreviewState.READY, text);
            }
            if (actions != null) {
                actions.setAdjustedPreview(labelPreview, text);
                actions.setPreviewButtonStale(false);
            }
        }
    }

    private void refreshLargePreviewModel() {
        if (preview == null) return;
        preview.setLargePreviewImages(rawSource, filteredSource, labelPreview);
        preview.setPreviousComparisonPreview(previousLabelPreview, previousPreviewText);
    }

    private void captureCurrentPreviewForComparison() {
        if (labelPreview == null) return;
        ImagePlus snapshot = PreviewPairPanel.duplicateForComparison(
                labelPreview, "Previous Cellpose preview");
        if (snapshot == null) return;
        ImagePlus old = previousLabelPreview;
        previousLabelPreview = snapshot;
        previousPreviewText = objectCountText();
        previousSettings = copyParameters(displayedSettings);
        previousSettingsSize = normalizedSizeToken(displayedSize);
        if (preview != null) {
            preview.setPreviousComparisonPreview(previousLabelPreview, previousPreviewText);
            updateComparisonRestoreAction();
        }
        if (old != null && old != labelPreview && old != filteredSource && old != rawSource) {
            previewAdapter.close(old);
        }
    }

    private void updateComparisonRestoreAction() {
        if (preview == null) return;
        boolean available = previousSettings != null && previousSettingsSize != null;
        preview.setComparisonRestoreAction(!available
                ? null
                : new Runnable() {
                    @Override public void run() {
                        restorePreviousComparisonSettings();
                    }
                });
    }

    private void restorePreviousComparisonSettings() {
        if (previousSettings == null || previousSettingsSize == null) {
            setStatus("No previous Cellpose settings are available.");
            return;
        }
        loadFields(previousSettings);
        loadSizeFields(previousSettingsSize);
        refreshCompanionState();
        runPreviewOnWorker();
    }

    private void openVariationsDialog() {
        if (filteredSource == null || activeContext == null) {
            setStatus("Wait for the filtered input to finish preparing before opening variations.");
            return;
        }
        VariationEngineContext ctx = VariationEngineContext.forCellpose(
                activeContext.getChannelName(),
                rawSource,
                filteredSource,
                activeContext,
                collectParameters(),
                previewAdapter,
                montageDisplayActionDelegate());
        VariationsDialog dialog = new VariationsDialog(
                SwingUtilities.getWindowAncestor(preview != null ? preview : previewButton),
                ctx,
                this::applyVariationCombo);
        dialog.showDialog();
    }

    private MontageDisplayActionDelegate montageDisplayActionDelegate() {
        if (preview == null) {
            return null;
        }
        return new MontageDisplayActionDelegate() {
            @Override public void adjustBrightnessContrast() {
                preview.requestBrightnessContrastControls();
            }

            @Override public void toggleGreyLut() {
                preview.requestGreyLutToggle();
            }

            @Override public String lutButtonText() {
                return preview.lutToggleButton().getText();
            }

            @Override public String lutButtonTooltip() {
                return preview.lutToggleButton().getToolTipText();
            }
        };
    }

    private void applyVariationCombo(ParameterCombo combo) {
        if (combo == null) return;
        updatingControls = true;
        try {
            Object model = combo.get(ParameterId.MODEL);
            if (model != null && modelCombo != null) {
                selectModelKey(String.valueOf(model));
            }
            setNumberField(diameterField, combo, ParameterId.DIAMETER);
            setNumberField(flowField, combo, ParameterId.FLOW_THRESHOLD);
            setNumberField(cellprobField, combo, ParameterId.CELLPROB_THRESHOLD);
        } finally {
            updatingControls = false;
        }
        refreshCompanionState();
        runPreviewOnWorker();
    }

    private ImagePlus currentSourceImage() {
        return rawSourceSelected() && rawSource != null ? rawSource : filteredSource;
    }

    private boolean rawSourceSelected() {
        return showRawSource;
    }

    private void setRawSourceVisible(boolean showRaw) {
        showRawSource = showRaw;
        if (preview != null) {
            preview.setSourceMode(showRaw
                    ? PreviewPairPanel.SourceMode.RAW
                    : PreviewPairPanel.SourceMode.FILTERED);
        }
        refreshSourceAndOutputPreview();
    }

    private String objectCountText() {
        if (sizeSummary != null && sizeSummary.totalCount > 0) {
            return sizeSummary.statusText();
        }
        return lastObjectCount >= 0
                ? "Objects: " + lastObjectCount + " ready"
                : "Objects: not previewed";
    }

    private ParticleSizeStage.SizeToken collectSizeToken() {
        int min = ObjectsCounter3DWrapper.parseMinSizeVoxels(
                sizeMinField == null ? null : sizeMinField.getText(), 100);
        min = Math.max(0, min);
        String max = normalizeMaxText(sizeMaxField == null ? null : sizeMaxField.getText());
        return new ParticleSizeStage.SizeToken(String.valueOf(min), max);
    }

    private boolean refreshSizeFilterPreview() {
        if (labelPreview == null || objectStats == null) {
            refreshSizeCutoffPanelOnly();
            return false;
        }
        try {
            ParticleSizeStage.SizeToken token = collectSizeToken();
            int minSize = ObjectsCounter3DWrapper.parseMinSizeVoxels(token.minText, 100);
            int maxSize = ObjectsCounter3DWrapper.parseMaxSizeVoxels(token.maxText, filteredSource);
            boolean maxFinite = isFiniteMaxToken(token.maxText);
            sizeSummary = ObjectSizeFilterPreview.summarize(
                    objectStats, filteredSource, minSize, maxSize, maxFinite);
            ObjectSizeFilterPreview.applyClassifiedLut(labelPreview, sizeSummary);
            if (sizeCutoffPanel != null) sizeCutoffPanel.setSummary(sizeSummary);
            applySizeGuideOverlay();
            previewStale = false;
            displayedSize = normalizedSizeToken(token);
            refreshSourceAndOutputPreview();
            setStatus(sizeSummary.statusText());
            if (actions != null) actions.setPreviewButtonStale(false);
            return true;
        } catch (RuntimeException e) {
            setError("Enter valid min and max voxel sizes.");
            return true;
        }
    }

    private void refreshSizeCutoffPanelOnly() {
        if (sizeCutoffPanel == null) return;
        try {
            ParticleSizeStage.SizeToken token = collectSizeToken();
            int minSize = ObjectsCounter3DWrapper.parseMinSizeVoxels(token.minText, 100);
            int maxSize = ObjectsCounter3DWrapper.parseMaxSizeVoxels(token.maxText, filteredSource);
            boolean maxFinite = isFiniteMaxToken(token.maxText);
            sizeSummary = ObjectSizeFilterPreview.summarize(
                    null, filteredSource, minSize, maxSize, maxFinite);
            sizeCutoffPanel.setSummary(sizeSummary);
            applySizeGuideOverlay();
        } catch (RuntimeException e) {
            sizeCutoffPanel.setSummary(null);
            applySizeGuideOverlay(null);
        }
    }

    private void applySizeGuideOverlay() {
        applySizeGuideOverlay(sizeSummary);
    }

    private void applySizeGuideOverlay(ObjectSizeFilterPreview.Summary summary) {
        if (preview != null) {
            preview.setObjectSizeGuide(summary);
        }
    }

    private void installGpuSupport() {
        if (installWorker != null && !installWorker.isDone()) return;
        if (!runtimeAdapter.nvidiaGpuLikelyAvailable()) {
            int warn = JOptionPane.showConfirmDialog(null,
                    "No NVIDIA GPU was detected on this system.\n\n"
                            + "PyTorch CUDA requires a compatible NVIDIA graphics card. "
                            + "Attempting this install may use substantial disk space without enabling GPU support.\n\n"
                            + "Continue anyway?",
                    "NVIDIA GPU Not Detected",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (warn != JOptionPane.YES_OPTION) return;
        } else {
            int confirm = JOptionPane.showConfirmDialog(null,
                    "Installing GPU support downloads PyTorch with CUDA and may take a while.\n\nContinue?",
                    "GPU Install",
                    JOptionPane.YES_NO_OPTION);
            if (confirm != JOptionPane.YES_OPTION) return;
        }
        setStatus("Installing Cellpose GPU support...");
        installGpuButton.setEnabled(false);
        installWorker = new SwingWorker<GpuInstallResult, Void>() {
            @Override protected GpuInstallResult doInBackground() {
                return runtimeAdapter.installGpuSupport();
            }

            @Override protected void done() {
                if (isCancelled()) return;
                try {
                    GpuInstallResult result = get();
                    if (result != null && result.success) {
                        gpuSwitch.setSelected(true);
                        markPreviewStale(STALE_TEXT);
                        setStatus(result.message.isEmpty()
                                ? "Cellpose GPU support installed." : result.message);
                    } else {
                        String message = result == null ? "GPU install failed." : result.message;
                        setError(message + (result == null || result.details.isEmpty()
                                ? "" : " " + result.details));
                    }
                } catch (Exception e) {
                    setError("GPU install failed: " + e.getMessage());
                } finally {
                    if (!isCancelled()) {
                        installGpuButton.setEnabled(true);
                        refreshRuntimeLabel();
                    }
                }
            }
        };
        installWorker.execute();
    }

    private Parameters collectParameters() {
        Parameters fallback = savedParameters == null ? Parameters.defaults(defaultUseGpu) : savedParameters;
        ModelOption model = selectedModelOption();
        String modelKey = model == null ? fallback.modelToken : model.entry.modelKey;
        int secondChannel = model != null && model.entry.supportsSecondChannel
                ? selectedCompanionIndex(companionChoices, companionCombo == null ? null : companionCombo.getSelectedItem())
                : -1;
        return new Parameters(
                modelKey,
                secondChannel,
                parse(diameterField, fallback.diameter),
                parse(flowField, fallback.flowThreshold),
                parse(cellprobField, fallback.cellprobThreshold),
                gpuSwitch == null ? fallback.useGpu : gpuSwitch.isSelected(),
                suggestPanel != null && suggestPanel.isSelected());
    }

    private static Parameters copyParameters(Parameters parameters) {
        if (parameters == null) return null;
        return new Parameters(
                parameters.modelToken,
                parameters.secondChannelIndex,
                parameters.diameter,
                parameters.flowThreshold,
                parameters.cellprobThreshold,
                parameters.useGpu,
                parameters.dumpCellprob);
    }

    private static ParticleSizeStage.SizeToken normalizedSizeToken(ParticleSizeStage.SizeToken token) {
        return token == null ? null : ParticleSizeStage.parseSizeToken(token.toToken());
    }

    private boolean sizeFieldsReadyForLivePreview() {
        return hasText(sizeMinField) && hasText(sizeMaxField);
    }

    private static boolean hasText(JTextField field) {
        return field != null && field.getText() != null && !field.getText().trim().isEmpty();
    }

    private ModelOption selectedModelOption() {
        Object selected = modelCombo == null ? null : modelCombo.getSelectedItem();
        return selected instanceof ModelOption ? (ModelOption) selected : null;
    }

    private boolean selectedModelSupportsSecondChannel() {
        ModelOption selected = selectedModelOption();
        return selected != null && selected.entry.supportsSecondChannel;
    }

    private void markPreviewStale(String text) {
        previewStale = true;
        setPreviewState(PreviewPairPanel.PreviewState.STALE, text);
        if (actions != null) actions.setPreviewButtonStale(true);
    }

    private void setPreviewState(PreviewPairPanel.PreviewState state, String text) {
        if (preview != null) {
            preview.setAdjustedState(state, text);
        }
        if (actions != null) {
            if (state == PreviewPairPanel.PreviewState.STALE) {
                actions.markPreviewStale(text);
                actions.setPreviewButtonStale(true);
            } else {
                actions.setStatus(text);
            }
        }
    }

    private void setStatus(String text) {
        if (actions != null) {
            actions.setStatus(text);
        }
    }

    private void setError(String text) {
        setPreviewState(PreviewPairPanel.PreviewState.ERROR, text);
        setStatus(text);
        if (actions != null) actions.setPreviewButtonStale(true);
    }

    private void setButtonsEnabled(boolean enabled) {
        if (previewButton != null) previewButton.setEnabled(enabled);
        if (installGpuButton != null) installGpuButton.setEnabled(enabled);
        if (resetButton != null) resetButton.setEnabled(enabled);
        if (variationsButton != null) variationsButton.setEnabled(enabled && filteredSource != null);
        if (modelCombo != null) modelCombo.setEnabled(enabled);
        if (companionCombo != null) {
            companionCombo.setEnabled(enabled
                    && selectedModelSupportsSecondChannel()
                    && companionCombo.getItemCount() > 1);
        }
        if (manageModelsButton != null) manageModelsButton.setEnabled(enabled);
        if (diameterField != null) diameterField.setEnabled(enabled);
        if (flowField != null) flowField.setEnabled(enabled);
        if (cellprobField != null) cellprobField.setEnabled(enabled);
        if (sizeMinField != null) sizeMinField.setEnabled(enabled);
        if (sizeMaxField != null) sizeMaxField.setEnabled(enabled);
        if (gpuSwitch != null) gpuSwitch.setEnabled(enabled);
        if (preview != null) {
            preview.setSourceModeEnabled(enabled);
            preview.setObjectOverlayEnabled(enabled);
        }
    }

    private void setVariationsButtonReady(boolean ready) {
        if (variationsButton != null) {
            variationsButton.setEnabled(ready && filteredSource != null);
        }
    }

    private static void setNumberField(JTextField field, ParameterCombo combo, ParameterId id) {
        Object value = combo == null ? null : combo.get(id);
        if (field != null && value instanceof Number) {
            field.setText(String.valueOf(((Number) value).doubleValue()));
        }
    }

    private static void splitClicks(List<ClickStore.Click> clicks,
                                    List<ClickStore.Click> negatives,
                                    List<ClickStore.Click> positives) {
        if (clicks == null) return;
        for (int i = 0; i < clicks.size(); i++) {
            ClickStore.Click click = clicks.get(i);
            if (click == null) continue;
            if (click.verdict == ClickStore.Verdict.POSITIVE) positives.add(click);
            else negatives.add(click);
        }
    }

    private static String suggestionMessage(List<ClickSuggestPanel.FieldSuggestion> fields,
                                            int badRemoved,
                                            int collateralRemoved) {
        StringBuilder sb = new StringBuilder("Suggested ");
        for (int i = 0; i < fields.size(); i++) {
            ClickSuggestPanel.FieldSuggestion field = fields.get(i);
            if (i > 0) sb.append(", ");
            sb.append(field.binding.label).append("=").append(field.value);
        }
        sb.append(". Removes ").append(badRemoved)
                .append(" bad clicks; affects ")
                .append(collateralRemoved)
                .append(" unclicked objects.");
        return sb.toString();
    }

    private static String formatNumber(double value) {
        if (!Double.isFinite(value)) return "0";
        double rounded = Math.rint(value);
        if (Math.abs(value - rounded) < 1.0e-9) {
            return String.valueOf((long) rounded);
        }
        return String.valueOf(value);
    }

    private void closePreviewWorker() {
        if (previewWorker != null && !previewWorker.isDone()) {
            previewWorker.cancel(true);
        }
        previewWorker = null;
    }

    private void closeInstallWorker() {
        if (installWorker != null && !installWorker.isDone()) {
            installWorker.cancel(true);
        }
        installWorker = null;
    }

    private void closeImages() {
        ImagePlus label = labelPreview;
        ImagePlus filtered = filteredSource;
        ImagePlus raw = rawSource;
        ImagePlus previous = previousLabelPreview;
        labelPreview = null;
        previousLabelPreview = null;
        previousPreviewText = "";
        previousSettings = null;
        previousSettingsSize = null;
        displayedSettings = null;
        displayedSize = null;
        objectStats = null;
        sizeSummary = null;
        rawSource = null;
        filteredSource = null;
        lastObjectCount = -1;
        if (previous != null && previous != label && previous != filtered && previous != raw) previewAdapter.close(previous);
        if (label != null && label != filtered && label != raw) previewAdapter.close(label);
        if (filtered != null && filtered != raw) previewAdapter.close(filtered);
        if (raw != null) previewAdapter.close(raw);
    }

    public static Parameters parseMethod(String method) {
        return parseMethod(method, BinConfig.DEFAULT_CELLPOSE_USE_GPU, 0, -1);
    }

    static boolean hasExplicitGpuOption(String method) {
        if (method == null || !method.startsWith("cellpose:")) return false;
        String[] parts = method.split(":");
        for (int i = 1; i < parts.length; i++) {
            if (parts[i] != null && parts[i].trim().startsWith("gpu=")) {
                return true;
            }
        }
        return false;
    }

    public static Parameters parseMethod(String method, boolean fallbackUseGpu,
                                         int channelCount, int primaryChannelIndex) {
        Parameters defaults = Parameters.defaults(fallbackUseGpu);
        if (method == null || !method.startsWith("cellpose:")) {
            return defaults;
        }
        SegmentationMethod parsed = SegmentationTokenParser.parseLenient(method);
        if (!parsed.isCellpose()) return defaults;
        String model = SegmentationMethod.cellposeModelKey(parsed);
        double diameter = SegmentationMethod.cellposeDiameter(parsed);
        double flow = SegmentationMethod.cellposeFlow(parsed);
        double cellprob = SegmentationMethod.cellposeCellprob(parsed);
        boolean useGpu = SegmentationMethod.cellposeUseGpu(parsed);
        int secondChannelIndex = SegmentationMethod.cellposeChan2(parsed);
        if (secondChannelIndex < 0
                || secondChannelIndex == primaryChannelIndex
                || (channelCount > 0 && secondChannelIndex >= channelCount)
                || !supportsSecondChannelForKnownModel(model)) {
            secondChannelIndex = -1;
        }
        return new Parameters(model, secondChannelIndex, diameter, flow, cellprob, useGpu);
    }

    public static String formatMethod(Parameters parameters) {
        Parameters p = parameters == null
                ? Parameters.defaults(BinConfig.DEFAULT_CELLPOSE_USE_GPU)
                : parameters;
        LinkedHashMap<String, String> params = new LinkedHashMap<String, String>();
        params.put("diameter", String.valueOf(p.diameter));
        params.put("flow", String.valueOf(p.flowThreshold));
        params.put("cellprob", String.valueOf(p.cellprobThreshold));
        params.put("gpu", String.valueOf(p.useGpu));
        if (p.secondChannelIndex >= 0) {
            params.put("chan2", String.valueOf(p.secondChannelIndex));
        }
        params.put("model", normalizeModelKey(p.modelToken));
        return SegmentationTokenParser.format(new SegmentationMethod(
                SegmentationMethod.Engine.CELLPOSE, params, ""));
    }

    static LinkedHashMap<String, Integer> buildCompanionChoices(List<String> channelNames,
                                                                int primaryChannelIndex) {
        LinkedHashMap<String, Integer> choices = new LinkedHashMap<String, Integer>();
        choices.put("None", Integer.valueOf(-1));
        if (channelNames == null) return choices;
        for (int i = 0; i < channelNames.size(); i++) {
            if (i == primaryChannelIndex) continue;
            String name = channelNames.get(i);
            choices.put("C" + (i + 1) + " (" + (name == null ? "" : name) + ")", Integer.valueOf(i));
        }
        return choices;
    }

    static String companionChoiceLabel(Map<String, Integer> choices, int secondChannelIndex) {
        if (choices == null || choices.isEmpty()) return "None";
        for (Map.Entry<String, Integer> entry : choices.entrySet()) {
            Integer value = entry.getValue();
            if (value != null && value.intValue() == secondChannelIndex) {
                return entry.getKey();
            }
        }
        return "None";
    }

    static int selectedCompanionIndex(Map<String, Integer> choices, Object selectedItem) {
        if (choices == null || choices.isEmpty() || selectedItem == null) return -1;
        Integer value = choices.get(String.valueOf(selectedItem));
        return value == null ? -1 : value.intValue();
    }

    private static List<ModelOption> modelOptionsFor(ConfigQcContext context) {
        File projectDir = context == null ? null : context.getProjectDirectory();
        File root = projectDir == null ? new File(".") : projectDir;
        ModelCatalog catalog = ModelCatalogIO.read(root.toPath());
        List<ModelEntry> entries = catalog.forEngine(ModelEntry.Engine.CELLPOSE);
        List<ModelEntry> stock = new ArrayList<ModelEntry>();
        List<ModelEntry> user = new ArrayList<ModelEntry>();
        for (ModelEntry entry : entries) {
            if (entry == null) continue;
            if (entry.isStock()) stock.add(entry);
            else user.add(entry);
        }
        Collections.sort(user, new Comparator<ModelEntry>() {
            @Override public int compare(ModelEntry left, ModelEntry right) {
                return labelFor(left).compareToIgnoreCase(labelFor(right));
            }
        });
        List<ModelOption> out = new ArrayList<ModelOption>();
        for (ModelEntry entry : stock) out.add(new ModelOption(entry, false));
        for (int i = 0; i < user.size(); i++) {
            out.add(new ModelOption(user.get(i), i == 0));
        }
        if (out.isEmpty()) {
            out.add(new ModelOption(new ModelEntry(
                    SegmentationMethod.DEFAULT_CELLPOSE_MODEL_KEY,
                    "Cellpose - cyto3",
                    "Recommended first-pass model for irregular whole-cell bodies and glial soma.",
                    ModelEntry.Engine.CELLPOSE,
                    ModelEntry.Source.STOCK_BUILTIN,
                    null,
                    null,
                    "cyto3",
                    null,
                    null,
                    defaultsMap(BinConfig.DEFAULT_CELLPOSE_DIAMETER,
                            BinConfig.DEFAULT_CELLPOSE_FLOW_THRESHOLD,
                            BinConfig.DEFAULT_CELLPOSE_CELLPROB_THRESHOLD),
                    null,
                    true), false));
        }
        return Collections.unmodifiableList(out);
    }

    private static Map<String, Object> defaultsMap(double diameter,
                                                   double flow,
                                                   double cellprob) {
        Map<String, Object> defaults = new LinkedHashMap<String, Object>();
        defaults.put("diameter", Double.valueOf(diameter));
        defaults.put("flowThreshold", Double.valueOf(flow));
        defaults.put("cellprobThreshold", Double.valueOf(cellprob));
        return defaults;
    }

    private void selectModelKey(String modelKey) {
        if (modelCombo == null || modelCombo.getItemCount() == 0) return;
        String key = normalizeModelKey(modelKey);
        int fallbackIndex = 0;
        for (int i = 0; i < modelCombo.getItemCount(); i++) {
            ModelOption option = modelCombo.getItemAt(i);
            if (option != null
                    && SegmentationMethod.DEFAULT_CELLPOSE_MODEL_KEY.equals(option.entry.modelKey)) {
                fallbackIndex = i;
            }
            if (option != null && key.equals(option.entry.modelKey)) {
                modelCombo.setSelectedIndex(i);
                updateModelTooltip(option);
                return;
            }
        }
        modelCombo.setSelectedIndex(fallbackIndex);
        updateModelTooltip((ModelOption) modelCombo.getSelectedItem());
    }

    private void resolveMissingModel(String modelKey) {
        missingModelKey = null;
        selectModelKey(modelKey);
        selectedModelKeySnapshot = modelKey;
        Parameters current = collectParameters();
        parameterStore.save(formatMethod(current));
        savedParameters = current;
        refreshMissingModelNoticeRow();
        setStatus("Replacement model selected.");
        markPreviewStale(STALE_TEXT);
    }

    private void openModelManager() {
        File projectDir = activeContext == null ? null : activeContext.getProjectDirectory();
        File root = projectDir == null ? new File(".") : projectDir;
        SegmentationModelManagerDialog.showManager(
                SwingUtilities.getWindowAncestor(preview != null ? preview : manageModelsButton),
                root.toPath(),
                ModelEntry.Engine.CELLPOSE);
        refreshModelOptionsFromCatalog();
    }

    private void refreshModelOptionsFromCatalog() {
        String selectedKey = selectedModelKeyForTest();
        modelOptions = modelOptionsFor(activeContext);
        if (modelCombo != null) {
            updatingControls = true;
            try {
                modelCombo.removeAllItems();
                for (int i = 0; i < modelOptions.size(); i++) {
                    modelCombo.addItem(modelOptions.get(i));
                }
                selectModelKey(selectedKey);
            } finally {
                updatingControls = false;
            }
        }
        if (!containsModelKey(modelOptions, selectedKey)) {
            missingModelKey = selectedKey;
            setError("Cannot run segmentation: model missing.");
        } else {
            missingModelKey = null;
        }
        refreshMissingModelNoticeRow();
    }

    private void updateModelTooltip(ModelOption option) {
        if (modelCombo != null) {
            modelCombo.setToolTipText(option == null ? null : ModelEntryListCellRenderer.tooltip(option.entry));
        }
    }

    private static boolean containsModelKey(List<ModelOption> options, String modelKey) {
        String key = normalizeModelKey(modelKey);
        if (options == null || key == null) return false;
        for (ModelOption option : options) {
            if (option != null && key.equals(option.entry.modelKey)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeModelKey(String modelKey) {
        return modelKey == null || modelKey.trim().isEmpty()
                ? SegmentationMethod.DEFAULT_CELLPOSE_MODEL_KEY
                : SegmentationMethod.canonicalCellposeModelKey(modelKey);
    }

    private static int sanitizeSecondChannelForKnownModel(String modelKey, int secondChannelIndex) {
        return supportsSecondChannelForKnownModel(modelKey) ? secondChannelIndex : -1;
    }

    private static boolean supportsSecondChannelForKnownModel(String modelKey) {
        java.util.Optional<Boolean> support = CellposeModel.supportsSecondChannelFor(modelKey);
        return !support.isPresent() || support.get().booleanValue();
    }

    private static double defaultDouble(Object value, double fallback) {
        if (value instanceof Number) {
            double parsed = ((Number) value).doubleValue();
            return Double.isFinite(parsed) ? parsed : fallback;
        }
        if (value != null) {
            try {
                double parsed = Double.parseDouble(String.valueOf(value));
                return Double.isFinite(parsed) ? parsed : fallback;
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String labelFor(ModelEntry entry) {
        if (entry == null) return "";
        return entry.name == null || entry.name.trim().isEmpty()
                ? entry.modelKey
                : entry.name.trim();
    }

    private static RuntimeAdapter noopRuntimeAdapter() {
        return new RuntimeAdapter() {
            @Override public CellposeRuntime.Status cachedRuntimeStatus() {
                return CellposeRuntime.Status.unknown();
            }

            @Override public CompletableFuture<CellposeRuntime.Status> probeRuntimeAsync() {
                return CompletableFuture.completedFuture(CellposeRuntime.Status.unknown());
            }

            @Override public boolean nvidiaGpuLikelyAvailable() {
                return false;
            }

            @Override public GpuInstallResult installGpuSupport() {
                return new GpuInstallResult(false, "GPU install is not available here.", "");
            }
        };
    }

    static final class RuntimeProbeCallback
            implements java.util.function.BiConsumer<CellposeRuntime.Status, Throwable> {
        private final WeakReference<CellposeParameterStage> stageRef;
        private final int requestId;

        RuntimeProbeCallback(WeakReference<CellposeParameterStage> stageRef, int requestId) {
            this.stageRef = stageRef;
            this.requestId = requestId;
        }

        @Override public void accept(CellposeRuntime.Status status, Throwable throwable) {
            CellposeParameterStage stage = stageRef == null ? null : stageRef.get();
            if (stage != null) {
                stage.applyRuntimeProbeResult(requestId, status, throwable);
            }
        }
    }

    private static final class ModelOption implements ModelEntryListCellRenderer.EntryAdapter {
        final ModelEntry entry;
        final boolean showUserSeparator;

        ModelOption(ModelEntry entry, boolean showUserSeparator) {
            this.entry = entry;
            this.showUserSeparator = showUserSeparator;
        }

        String description() {
            return entry == null || entry.description == null ? "" : entry.description;
        }

        @Override public String toString() {
            return ModelEntryListCellRenderer.presentation(entry, showUserSeparator).displayText;
        }

        @Override public ModelEntry modelEntry() {
            return entry;
        }

        @Override public boolean showUserSeparator() {
            return showUserSeparator;
        }
    }

    private static double parse(JTextField field, double fallback) {
        if (field == null) return fallback;
        return parse(field.getText(), fallback);
    }

    private static double parse(String value, double fallback) {
        if (value == null) return fallback;
        try {
            double parsed = Double.parseDouble(value.trim());
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double sanitizeNonNegative(double value) {
        return Math.max(0, value);
    }

    private static String normalizeMaxText(String value) {
        if (value == null) return "Infinity";
        String trimmed = value.trim();
        if (trimmed.isEmpty()
                || "infinity".equalsIgnoreCase(trimmed)
                || "inf".equalsIgnoreCase(trimmed)) {
            return "Infinity";
        }
        double parsed = Double.parseDouble(trimmed);
        if (!Double.isFinite(parsed)) return "Infinity";
        return String.valueOf(Math.max(0, (int) Math.round(parsed)));
    }

    private static boolean isFiniteMaxToken(String value) {
        String normalized = normalizeMaxText(value);
        return !"Infinity".equals(normalized);
    }

    private static SizeStore defaultSizeStore() {
        return new SizeStore() {
            @Override public String get() {
                return "0-Infinity";
            }

            @Override public void set(String token) {
            }
        };
    }
}
