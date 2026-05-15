package flash.pipeline.ui.config;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.help.SetupHelpCatalog;
import flash.pipeline.help.SetupHelpTopic;
import flash.pipeline.segmentation.SegmentationMethod;
import flash.pipeline.segmentation.StarDistLinkingParams;
import flash.pipeline.segmentation.StarDistPostFilters;
import flash.pipeline.segmentation.SegmentationTokenParser;
import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;
import flash.pipeline.stardist.StarDist3DRunner;
import flash.pipeline.ui.FlashTheme;
import flash.pipeline.ui.preview.ObjectSizeFilterPreview;
import flash.pipeline.ui.preview.PreviewPairPanel;
import flash.pipeline.ui.variations.MontageDisplayActionDelegate;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterId;
import flash.pipeline.ui.variations.VariationEngineContext;
import flash.pipeline.ui.variations.VariationsDialog;
import ij.IJ;
import ij.ImagePlus;
import ij.measure.ResultsTable;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Component;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StarDistParameterStage implements ConfigQcStage {

    public interface ParameterStore {
        String getMethodToken();
        void save(String methodToken);
    }

    public interface PreviewAdapter {
        ImagePlus createRawSource(ConfigQcContext context) throws Exception;
        ImagePlus createFilteredSource(ConfigQcContext context) throws Exception;
        ImagePlus runPreview(ImagePlus filteredSource, Parameters parameters) throws Exception;
        int countLabels(ImagePlus labelImage);
        void close(ImagePlus image);
    }

    public static final class Parameters {
        public final double probabilityThreshold;
        public final double nmsThreshold;
        public final double linkingMaxDistance;
        public final double gapClosingMaxDistance;
        public final int maxFrameGap;
        public final double areaMin;
        public final double areaMax;
        public final double qualityMin;
        public final double intensityMin;
        public final String modelKey;

        public Parameters(double probabilityThreshold,
                          double nmsThreshold,
                          double linkingMaxDistance,
                          double gapClosingMaxDistance,
                          int maxFrameGap,
                          double areaMin,
                          double areaMax,
                          double qualityMin,
                          double intensityMin) {
            this(probabilityThreshold, nmsThreshold, linkingMaxDistance, gapClosingMaxDistance,
                    maxFrameGap, areaMin, areaMax, qualityMin, intensityMin,
                    SegmentationMethod.DEFAULT_STARDIST_MODEL_KEY);
        }

        public Parameters(double probabilityThreshold,
                          double nmsThreshold,
                          double linkingMaxDistance,
                          double gapClosingMaxDistance,
                          int maxFrameGap,
                          double areaMin,
                          double areaMax,
                          double qualityMin,
                          double intensityMin,
                          String modelKey) {
            this.probabilityThreshold = probabilityThreshold;
            this.nmsThreshold = nmsThreshold;
            this.linkingMaxDistance = sanitizeNonNegative(linkingMaxDistance);
            this.gapClosingMaxDistance = sanitizeNonNegative(gapClosingMaxDistance);
            this.maxFrameGap = sanitizeFrameGap(maxFrameGap);
            this.areaMin = sanitizeNonNegative(areaMin);
            this.areaMax = areaMax <= 0 ? Double.POSITIVE_INFINITY : areaMax;
            this.qualityMin = sanitizeNonNegative(qualityMin);
            this.intensityMin = sanitizeNonNegative(intensityMin);
            this.modelKey = normalizeModelKey(modelKey);
        }

        static Parameters defaults() {
            return new Parameters(
                    BinConfig.DEFAULT_STARDIST_PROB_THRESH,
                    BinConfig.DEFAULT_STARDIST_NMS_THRESH,
                    BinConfig.DEFAULT_STARDIST_LINKING_MAX_DISTANCE,
                    BinConfig.DEFAULT_STARDIST_GAP_CLOSING_MAX_DISTANCE,
                    BinConfig.DEFAULT_STARDIST_MAX_FRAME_GAP,
                    0,
                    Double.POSITIVE_INFINITY,
                    0,
                    0,
                    SegmentationMethod.DEFAULT_STARDIST_MODEL_KEY);
        }
    }

    private static final String STALE_TEXT = "Preview is out of date. Press Run Preview.";
    private static final String EMPTY_TEXT = "Filtered input is ready. Press Run Preview.";

    private final ParameterStore parameterStore;
    private final PreviewAdapter previewAdapter;

    private ConfigQcActions actions;
    private PreviewPairPanel preview;
    private ConfigQcContext activeContext;
    private Parameters savedParameters = Parameters.defaults();
    private Parameters restartParameters;
    private ImagePlus rawSource;
    private ImagePlus filteredSource;
    private ImagePlus labelPreview;
    private ImagePlus previousLabelPreview;
    private String previousPreviewText = "";
    private Parameters previousSettings;
    private Parameters displayedSettings;
    private ResultsTable objectStats;
    private SwingWorker<ImagePlus, Void> previewWorker;
    private boolean previewStale = true;
    private boolean updatingFields;
    private boolean showRawSource;
    private int lastObjectCount = -1;
    private List<ModelOption> modelOptions = Collections.emptyList();

    private JComboBox<ModelOption> modelCombo;
    private JButton manageModelsButton;
    private JTextField probabilityField;
    private JTextField nmsField;
    private JTextField linkingField;
    private JTextField gapClosingField;
    private JTextField frameGapField;
    private JTextField areaMinField;
    private JTextField areaMaxField;
    private JTextField qualityMinField;
    private JTextField intensityMinField;
    private JButton previewButton;
    private JButton resetButton;
    private JButton variationsButton;
    private ObjectFilterSummary objectFilterSummary;

    public StarDistParameterStage(ParameterStore parameterStore, PreviewAdapter previewAdapter) {
        if (parameterStore == null) {
            throw new IllegalArgumentException("parameterStore must not be null");
        }
        if (previewAdapter == null) {
            throw new IllegalArgumentException("previewAdapter must not be null");
        }
        this.parameterStore = parameterStore;
        this.previewAdapter = previewAdapter;
    }

    @Override
    public String title() {
        return "StarDist";
    }

    @Override
    public SetupHelpTopic helpTopic() {
        return SetupHelpCatalog.STARDIST;
    }

    @Override
    public boolean controlsCanExpand() {
        return true;
    }

    @Override
    public JComponent buildControls(ConfigQcContext context, ConfigQcActions actions) {
        this.actions = actions;
        this.activeContext = context;
        this.savedParameters = restartParameters == null
                ? parseMethod(parameterStore.getMethodToken())
                : restartParameters;
        this.modelOptions = modelOptionsFor(context);

        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(FlashTheme.pad(2, 0, 0, 0));
        createParameterFields();
        panel.add(buildModelRow());
        panel.add(Box.createVerticalStrut(4));
        panel.add(buildGroupRow("Detection:", new String[]{"Probability", "NMS"},
                new JTextField[]{probabilityField, nmsField}));
        panel.add(Box.createVerticalStrut(4));
        panel.add(buildGroupRow("Linking:", new String[]{"Distance", "Gap distance", "Frame gap"},
                new JTextField[]{linkingField, gapClosingField, frameGapField}));
        panel.add(Box.createVerticalStrut(4));
        panel.add(buildFilterActionRow());
        loadFields(savedParameters);
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
                throw new IllegalStateException("No raw StarDist input image is available.");
            }
            filteredSource = previewAdapter.createFilteredSource(context);
            if (filteredSource == null) {
                throw new IllegalStateException("No filtered StarDist input image is available.");
            }
            if (preview != null) {
                preview.setOriginal(currentSourceImage());
                preview.setAdjusted(null);
                preview.setAdjustedState(PreviewPairPanel.PreviewState.STALE, EMPTY_TEXT);
            }
            refreshLargePreviewModel();
            setStatus(EMPTY_TEXT);
            setVariationsButtonReady(true);
        } catch (Exception e) {
            closeImages();
            setVariationsButtonReady(false);
            setError("Could not prepare StarDist input: " + e.getMessage());
        }
    }

    @Override
    public boolean lockIn(ConfigQcContext context) {
        Parameters parameters = collectParameters();
        parameterStore.save(formatMethod(parameters));
        savedParameters = parameters;
        restartParameters = null;
        setStatus("Locked StarDist parameters.");
        return true;
    }

    @Override
    public void skipCurrentImage(ConfigQcContext context) {
        setStatus("Skipped this image; StarDist parameters are unchanged.");
    }

    @Override
    public void restartStage(ConfigQcContext context) {
        restartParameters = collectParameters();
        setStatus("Restarting StarDist review from the first image.");
    }

    @Override
    public void onLeave(ConfigQcContext context) {
        closePreviewWorker();
        if (preview != null) {
            preview.setSourceModeChangeListener(null);
            preview.setDisplaySettingsChangeListener(null);
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

    void setProbabilityForTest(String value) {
        setTextForTest(probabilityField, value);
    }

    void setNmsForTest(String value) {
        setTextForTest(nmsField, value);
    }

    void setLinkingForTest(String value) {
        setTextForTest(linkingField, value);
    }

    void setGapClosingForTest(String value) {
        setTextForTest(gapClosingField, value);
    }

    void setFrameGapForTest(String value) {
        setTextForTest(frameGapField, value);
    }

    void setAreaMinForTest(String value) {
        setTextForTest(areaMinField, value);
    }

    void setAreaMaxForTest(String value) {
        setTextForTest(areaMaxField, value);
    }

    void setQualityMinForTest(String value) {
        setTextForTest(qualityMinField, value);
    }

    void setIntensityMinForTest(String value) {
        setTextForTest(intensityMinField, value);
    }

    void runPreviewNowForTest() throws Exception {
        runPreviewNow();
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

    void selectModelForTest(String modelKey) {
        selectModelKey(modelKey);
    }

    String selectedModelKeyForTest() {
        ModelOption selected = selectedModelOption();
        return selected == null ? null : selected.entry.modelKey;
    }

    List<String> modelKeysForTest() {
        List<String> keys = new ArrayList<String>();
        for (int i = 0; i < modelCombo.getItemCount(); i++) {
            keys.add(modelCombo.getItemAt(i).entry.modelKey);
        }
        return keys;
    }

    boolean manageModelsButtonEnabledForTest() {
        return manageModelsButton != null && manageModelsButton.isEnabled();
    }

    private void createParameterFields() {
        probabilityField = createNumberField(5);
        nmsField = createNumberField(5);
        linkingField = createNumberField(5);
        gapClosingField = createNumberField(5);
        frameGapField = createNumberField(4);
        areaMinField = createPostDetectionFilterField(5);
        areaMaxField = createPostDetectionFilterField(5);
        qualityMinField = createPostDetectionFilterField(5);
        intensityMinField = createPostDetectionFilterField(5);
    }

    private JTextField createNumberField(int columns) {
        JTextField field = new JTextField(columns);
        installFieldListener(field);
        return field;
    }

    private JTextField createPostDetectionFilterField(int columns) {
        JTextField field = new JTextField(columns);
        installPostDetectionFilterListener(field);
        return field;
    }

    private JComponent buildModelRow() {
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        row.setBorder(FlashTheme.pad(2, 0, 2, 0));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.insets = new Insets(0, 0, 0, 6);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0;
        JLabel heading = new JLabel("Model:");
        heading.setFont(FlashTheme.bodyMedium());
        row.add(heading, gbc);

        modelCombo = new JComboBox<ModelOption>(modelOptions.toArray(new ModelOption[0]));
        modelCombo.setRenderer(new ModelOptionRenderer());
        modelCombo.addActionListener(e -> modelSelectionChanged());
        updatingFields = true;
        try {
            selectModelKey(savedParameters.modelKey);
        } finally {
            updatingFields = false;
        }
        gbc.gridx++;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        row.add(modelCombo, gbc);

        manageModelsButton = new JButton("Manage models...");
        manageModelsButton.setEnabled(false);
        manageModelsButton.setToolTipText("Model manager is added in a later stage.");
        gbc.gridx++;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0.0;
        row.add(manageModelsButton, gbc);
        return row;
    }

    private JComponent buildGroupRow(String headingText, String[] labels, JTextField[] fields) {
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        row.setBorder(FlashTheme.pad(2, 0, 2, 0));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.insets = new Insets(0, 0, 0, 6);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        JLabel heading = new JLabel(headingText);
        heading.setFont(FlashTheme.bodyMedium());
        row.add(heading, gbc);

        for (int i = 0; i < labels.length; i++) {
            gbc.gridx++;
            row.add(new JLabel(labels[i]), gbc);
            gbc.gridx++;
            row.add(fields[i], gbc);
        }
        gbc.gridx++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        row.add(Box.createHorizontalGlue(), gbc);
        return row;
    }

    private JComponent buildFilterActionRow() {
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        row.setBorder(FlashTheme.pad(2, 0, 2, 0));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.insets = new Insets(0, 0, 0, 6);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0;
        JLabel heading = new JLabel("Filters:");
        heading.setFont(FlashTheme.bodyMedium());
        row.add(heading, gbc);

        gbc.gridx++;
        row.add(new JLabel("Area min"), gbc);
        gbc.gridx++;
        row.add(areaMinField, gbc);
        gbc.gridx++;
        row.add(new JLabel("Area max"), gbc);
        gbc.gridx++;
        row.add(areaMaxField, gbc);
        gbc.gridx++;
        row.add(new JLabel("Quality min"), gbc);
        gbc.gridx++;
        row.add(qualityMinField, gbc);
        gbc.gridx++;
        row.add(new JLabel("Intensity min"), gbc);
        gbc.gridx++;
        row.add(intensityMinField, gbc);
        gbc.gridx++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        row.add(Box.createHorizontalGlue(), gbc);

        previewButton = new JButton("Run Preview");
        flash.pipeline.ui.FlashIcons.apply(previewButton, flash.pipeline.ui.FlashIcons.play());
        previewButton.addActionListener(e -> runPreviewOnWorker());
        gbc.gridx++;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        row.add(previewButton, gbc);

        resetButton = new JButton("Reset to saved");
        flash.pipeline.ui.FlashIcons.apply(resetButton, flash.pipeline.ui.FlashIcons.refresh());
        resetButton.addActionListener(e -> resetToSaved());
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

    private void installPostDetectionFilterListener(JTextField field) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) {
                postDetectionFilterFieldChanged();
            }

            @Override public void removeUpdate(DocumentEvent e) {
                postDetectionFilterFieldChanged();
            }

            @Override public void changedUpdate(DocumentEvent e) {
                postDetectionFilterFieldChanged();
            }
        });
    }

    private void loadFields(Parameters parameters) {
        updatingFields = true;
        try {
            selectModelKey(parameters.modelKey);
            probabilityField.setText(String.valueOf(parameters.probabilityThreshold));
            nmsField.setText(String.valueOf(parameters.nmsThreshold));
            linkingField.setText(String.valueOf(parameters.linkingMaxDistance));
            gapClosingField.setText(String.valueOf(parameters.gapClosingMaxDistance));
            frameGapField.setText(String.valueOf(parameters.maxFrameGap));
            areaMinField.setText(String.valueOf(parameters.areaMin));
            areaMaxField.setText(Double.isInfinite(parameters.areaMax)
                    ? "0" : String.valueOf(parameters.areaMax));
            qualityMinField.setText(String.valueOf(parameters.qualityMin));
            intensityMinField.setText(String.valueOf(parameters.intensityMin));
        } finally {
            updatingFields = false;
        }
    }

    private void modelSelectionChanged() {
        if (updatingFields) return;
        ModelOption selected = selectedModelOption();
        if (selected == null) return;
        Parameters current = collectParameters();
        Parameters updated = new Parameters(
                defaultDouble(selected.entry.defaults.get("probThresh"), current.probabilityThreshold),
                defaultDouble(selected.entry.defaults.get("nmsThresh"), current.nmsThreshold),
                current.linkingMaxDistance,
                current.gapClosingMaxDistance,
                current.maxFrameGap,
                current.areaMin,
                current.areaMax,
                current.qualityMin,
                current.intensityMin,
                selected.entry.modelKey);
        loadFields(updated);
        savedParameters = updated;
        parameterStore.save(formatMethod(updated));
        warnIfAdvancedRgbSelection(selected.entry);
        captureCurrentPreviewForComparison();
        if (labelPreview != null) {
            runPreviewOnWorker();
        } else {
            markPreviewStale(STALE_TEXT);
        }
    }

    private void selectModelKey(String modelKey) {
        if (modelCombo == null || modelCombo.getItemCount() == 0) return;
        String key = normalizeModelKey(modelKey);
        int fallbackIndex = 0;
        for (int i = 0; i < modelCombo.getItemCount(); i++) {
            ModelOption option = modelCombo.getItemAt(i);
            if (option != null && SegmentationMethod.DEFAULT_STARDIST_MODEL_KEY.equals(option.entry.modelKey)) {
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

    private ModelOption selectedModelOption() {
        Object selected = modelCombo == null ? null : modelCombo.getSelectedItem();
        return selected instanceof ModelOption ? (ModelOption) selected : null;
    }

    private void updateModelTooltip(ModelOption option) {
        if (modelCombo == null) return;
        modelCombo.setToolTipText(option == null ? null : option.tooltip());
    }

    private void warnIfAdvancedRgbSelection(ModelEntry entry) {
        if (entry == null || !metadataBoolean(entry, "advanced")) return;
        if (!metadataBoolean(entry, "rgbOnly")) return;
        if (activeContext != null && currentImageLooksRgb(activeContext.getCurrentImagePlus())) return;
        String channel = activeContext == null ? "" : activeContext.getChannelLabel();
        IJ.log("WARNING: StarDist model '" + entry.name + "' is marked advanced/RGB"
                + (channel == null || channel.trim().isEmpty() ? "" : " for " + channel)
                + ". FLASH usually previews one grayscale channel at a time, so this model may be incompatible.");
    }

    private void fieldChanged() {
        if (updatingFields) return;
        markPreviewStale(STALE_TEXT);
    }

    private void postDetectionFilterFieldChanged() {
        if (updatingFields) return;
        captureCurrentPreviewForComparison();
        if (!refreshObjectFilterPreview()) {
            markPreviewStale(STALE_TEXT);
        }
    }

    private void resetToSaved() {
        loadFields(savedParameters);
        captureCurrentPreviewForComparison();
        if (!refreshObjectFilterPreview()) {
            markPreviewStale(STALE_TEXT);
        }
    }

    private void runPreviewOnWorker() {
        if (previewWorker != null && !previewWorker.isDone()) return;
        if (filteredSource == null) {
            setError("No StarDist input image is available.");
            return;
        }
        final Parameters parameters = collectParameters();
        final Parameters runParameters = previewRunParameters(parameters);
        setPreviewState(PreviewPairPanel.PreviewState.RUNNING, "Running StarDist preview...");
        setButtonsEnabled(false);
        previewWorker = new SwingWorker<ImagePlus, Void>() {
            @Override protected ImagePlus doInBackground() throws Exception {
                return previewAdapter.runPreview(filteredSource, runParameters);
            }

            @Override protected void done() {
                if (isCancelled()) return;
                try {
                    installLabelPreview(get(), parameters);
                } catch (Exception e) {
                    setPreviewError("StarDist preview failed: " + e.getMessage());
                } finally {
                    if (!isCancelled()) setButtonsEnabled(true);
                }
            }
        };
        previewWorker.execute();
    }

    private void runPreviewNow() throws Exception {
        if (filteredSource == null) {
            throw new IllegalStateException("No StarDist input image is available.");
        }
        Parameters parameters = collectParameters();
        setPreviewState(PreviewPairPanel.PreviewState.RUNNING, "Running StarDist preview...");
        installLabelPreview(previewAdapter.runPreview(filteredSource,
                previewRunParameters(parameters)), parameters);
    }

    private void installLabelPreview(ImagePlus labelImage, Parameters settings) {
        if (labelImage == null) {
            setPreviewError("StarDist returned no label map.");
            return;
        }
        int count = previewAdapter.countLabels(labelImage);
        labelImage.setTitle("StarDist label preview");
        captureCurrentPreviewForComparison();
        ImagePlus old = labelPreview;
        labelPreview = labelImage;
        objectStats = objectStatsForLabelPreview(labelImage);
        previewStale = false;
        lastObjectCount = count;
        boolean ready = refreshObjectFilterPreview();
        displayedSettings = copyParameters(settings);
        if (ready) {
            String text = objectCountText();
            setStatus(text);
            if (actions != null) actions.setPreviewButtonStale(false);
        } else {
            markPreviewStale(STALE_TEXT);
        }
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
                labelPreview, "Previous StarDist preview");
        if (snapshot == null) return;
        ImagePlus old = previousLabelPreview;
        previousLabelPreview = snapshot;
        previousPreviewText = objectCountText();
        previousSettings = copyParameters(displayedSettings);
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
        preview.setComparisonRestoreAction(previousSettings == null
                ? null
                : new Runnable() {
                    @Override public void run() {
                        restorePreviousComparisonSettings();
                    }
                });
    }

    private void restorePreviousComparisonSettings() {
        if (previousSettings == null) {
            setStatus("No previous StarDist settings are available.");
            return;
        }
        loadFields(previousSettings);
        runPreviewOnWorker();
    }

    private void openVariationsDialog() {
        if (filteredSource == null || activeContext == null) {
            setStatus("Wait for the filtered input to finish preparing before opening variations.");
            return;
        }
        VariationEngineContext ctx = VariationEngineContext.forStarDist(
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
        updatingFields = true;
        try {
            setNumberField(probabilityField, combo, ParameterId.PROB_THRESH);
            setNumberField(nmsField, combo, ParameterId.NMS_THRESH);
            setNumberField(linkingField, combo, ParameterId.LINKING_MAX);
            setNumberField(gapClosingField, combo, ParameterId.GAP_CLOSING_MAX);
            setIntegerField(frameGapField, combo, ParameterId.FRAME_GAP);
            setNumberField(areaMinField, combo, ParameterId.AREA_MIN);
            setNumberField(areaMaxField, combo, ParameterId.AREA_MAX);
            setNumberField(qualityMinField, combo, ParameterId.QUALITY_MIN);
            setNumberField(intensityMinField, combo, ParameterId.INTENSITY_MIN);
        } finally {
            updatingFields = false;
        }
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
        if (objectFilterSummary != null && objectFilterSummary.totalCount > 0) {
            return objectFilterSummary.statusText();
        }
        return lastObjectCount >= 0
                ? "Objects: " + lastObjectCount + " ready"
                : "Objects: not previewed";
    }

    private boolean refreshObjectFilterPreview() {
        if (labelPreview == null || objectStats == null) {
            return false;
        }
        try {
            Parameters parameters = collectParameters();
            Map<Integer, ObjectSizeFilterPreview.Classification> starDistClasses =
                    starDistFilterClassifications(objectStats, parameters);
            if (starDistClasses == null) {
                return false;
            }
            ObjectSizeFilterPreview.Summary allObjectsSummary = ObjectSizeFilterPreview.summarize(
                    objectStats, filteredSource, 0, 0, false);
            objectFilterSummary = summarizeObjectFilters(objectStats, starDistClasses);
            ObjectSizeFilterPreview.applyClassifiedLut(labelPreview, allObjectsSummary, starDistClasses);
            previewStale = false;
            displayedSettings = copyParameters(parameters);
            refreshSourceAndOutputPreview();
            setStatus(objectFilterSummary.statusText());
            if (actions != null) actions.setPreviewButtonStale(false);
            return true;
        } catch (RuntimeException e) {
            setError("Enter valid StarDist parameters.");
            return true;
        }
    }

    private ResultsTable objectStatsForLabelPreview(ImagePlus labelImage) {
        ResultsTable stats = ObjectSizeFilterPreview.statisticsFromLabelMap(labelImage, filteredSource);
        Object property = labelImage == null ? null
                : labelImage.getProperty(StarDist3DRunner.OBJECT_STATS_PROPERTY);
        if (!(property instanceof ResultsTable)) return stats;
        ResultsTable starDistStats = (ResultsTable) property;
        copyStarDistMetricColumn(starDistStats, stats, StarDist3DRunner.STATS_AREA_MEAN);
        copyStarDistMetricColumn(starDistStats, stats, StarDist3DRunner.STATS_QUALITY_MEAN);
        copyStarDistMetricColumn(starDistStats, stats, StarDist3DRunner.STATS_INTENSITY_MEAN);
        return stats;
    }

    private static void copyStarDistMetricColumn(ResultsTable source,
                                                 ResultsTable target,
                                                 String column) {
        if (!hasColumn(source, column) || source == null || target == null) return;
        Map<Integer, Integer> targetRows = rowsByLabel(target);
        for (int row = 0; row < source.size(); row++) {
            int label = labelForStatsRow(source, row);
            Integer targetRow = targetRows.get(Integer.valueOf(label));
            if (targetRow == null) continue;
            double value = metric(source, column, row);
            if (Double.isFinite(value)) {
                target.setValue(column, targetRow.intValue(), value);
            }
        }
    }

    private Map<Integer, ObjectSizeFilterPreview.Classification> starDistFilterClassifications(
            ResultsTable stats,
            Parameters parameters) {
        Map<Integer, ObjectSizeFilterPreview.Classification> classes =
                new HashMap<Integer, ObjectSizeFilterPreview.Classification>();
        if (stats == null || stats.size() == 0 || parameters == null) return classes;
        boolean areaActive = parameters.areaMin > 0 || Double.isFinite(parameters.areaMax);
        boolean qualityActive = parameters.qualityMin > 0;
        boolean intensityActive = parameters.intensityMin > 0;
        if (!areaActive && !qualityActive && !intensityActive) return classes;
        if ((areaActive && !hasColumn(stats, StarDist3DRunner.STATS_AREA_MEAN))
                || (qualityActive && !hasColumn(stats, StarDist3DRunner.STATS_QUALITY_MEAN))
                || (intensityActive && !hasColumn(stats, StarDist3DRunner.STATS_INTENSITY_MEAN))) {
            return null;
        }
        for (int row = 0; row < stats.size(); row++) {
            int label = labelForStatsRow(stats, row);
            ObjectSizeFilterPreview.Classification classification =
                    ObjectSizeFilterPreview.Classification.KEPT;
            double area = metric(stats, StarDist3DRunner.STATS_AREA_MEAN, row);
            if (Double.isFinite(area)) {
                if (parameters.areaMin > 0 && area < parameters.areaMin) {
                    classification = ObjectSizeFilterPreview.Classification.BELOW_MIN;
                } else if (Double.isFinite(parameters.areaMax) && area > parameters.areaMax) {
                    classification = ObjectSizeFilterPreview.Classification.ABOVE_MAX;
                }
            }
            double quality = metric(stats, StarDist3DRunner.STATS_QUALITY_MEAN, row);
            if (classification == ObjectSizeFilterPreview.Classification.KEPT
                    && parameters.qualityMin > 0
                    && Double.isFinite(quality)
                    && quality < parameters.qualityMin) {
                classification = ObjectSizeFilterPreview.Classification.BELOW_MIN;
            }
            double intensity = metric(stats, StarDist3DRunner.STATS_INTENSITY_MEAN, row);
            if (classification == ObjectSizeFilterPreview.Classification.KEPT
                    && parameters.intensityMin > 0
                    && Double.isFinite(intensity)
                    && intensity < parameters.intensityMin) {
                classification = ObjectSizeFilterPreview.Classification.BELOW_MIN;
            }
            if (classification != ObjectSizeFilterPreview.Classification.KEPT) {
                classes.put(Integer.valueOf(label), classification);
            }
        }
        return classes;
    }

    private static ObjectFilterSummary summarizeObjectFilters(
            ResultsTable stats,
            Map<Integer, ObjectSizeFilterPreview.Classification> starDistClasses) {
        int total = stats == null ? 0 : stats.size();
        int starDist = starDistClasses == null ? 0 : starDistClasses.size();
        int kept = Math.max(0, total - starDist);
        return new ObjectFilterSummary(total, kept, starDist);
    }

    private static Map<Integer, Integer> rowsByLabel(ResultsTable stats) {
        Map<Integer, Integer> rows = new HashMap<Integer, Integer>();
        if (stats == null) return rows;
        for (int row = 0; row < stats.size(); row++) {
            rows.put(Integer.valueOf(labelForStatsRow(stats, row)), Integer.valueOf(row));
        }
        return rows;
    }

    private static int labelForStatsRow(ResultsTable stats, int row) {
        try {
            double label = stats.getValue("Label", row);
            if (Double.isFinite(label) && label > 0) return (int) Math.round(label);
        } catch (RuntimeException ignored) {
            // Fall through to row order.
        }
        return row + 1;
    }

    private static boolean hasColumn(ResultsTable stats, String column) {
        if (stats == null || column == null) return false;
        String[] headings = stats.getHeadings();
        if (headings == null) return false;
        for (int i = 0; i < headings.length; i++) {
            if (column.equals(headings[i])) return true;
        }
        return false;
    }

    private static double metric(ResultsTable stats, String column, int row) {
        try {
            double value = stats.getValue(column, row);
            return Double.isFinite(value) ? value : Double.NaN;
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private Parameters collectParameters() {
        Parameters fallback = savedParameters == null ? Parameters.defaults() : savedParameters;
        double rawAreaMax = parse(areaMaxField, Double.isInfinite(fallback.areaMax) ? 0 : fallback.areaMax);
        return new Parameters(
                parse(probabilityField, fallback.probabilityThreshold),
                parse(nmsField, fallback.nmsThreshold),
                parse(linkingField, fallback.linkingMaxDistance),
                parse(gapClosingField, fallback.gapClosingMaxDistance),
                sanitizeFrameGap(parse(frameGapField, fallback.maxFrameGap)),
                parse(areaMinField, fallback.areaMin),
                rawAreaMax <= 0 ? Double.POSITIVE_INFINITY : rawAreaMax,
                parse(qualityMinField, fallback.qualityMin),
                parse(intensityMinField, fallback.intensityMin),
                selectedModelKey());
    }

    private static Parameters previewRunParameters(Parameters parameters) {
        Parameters p = parameters == null ? Parameters.defaults() : parameters;
        return new Parameters(
                p.probabilityThreshold,
                p.nmsThreshold,
                p.linkingMaxDistance,
                p.gapClosingMaxDistance,
                p.maxFrameGap,
                0,
                Double.POSITIVE_INFINITY,
                0,
                0,
                p.modelKey);
    }

    private static Parameters copyParameters(Parameters parameters) {
        if (parameters == null) return null;
        return new Parameters(
                parameters.probabilityThreshold,
                parameters.nmsThreshold,
                parameters.linkingMaxDistance,
                parameters.gapClosingMaxDistance,
                parameters.maxFrameGap,
                parameters.areaMin,
                parameters.areaMax,
                parameters.qualityMin,
                parameters.intensityMin,
                parameters.modelKey);
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

    private void setPreviewError(String text) {
        ImagePlus old = labelPreview;
        labelPreview = null;
        objectStats = null;
        objectFilterSummary = null;
        previewStale = true;
        lastObjectCount = -1;
        if (preview != null) {
            preview.setOriginal(currentSourceImage());
            preview.setAdjusted(null);
            refreshLargePreviewModel();
        }
        if (old != null) {
            previewAdapter.close(old);
        }
        setError(text);
    }

    private void setButtonsEnabled(boolean enabled) {
        if (previewButton != null) previewButton.setEnabled(enabled);
        if (resetButton != null) resetButton.setEnabled(enabled);
        if (variationsButton != null) variationsButton.setEnabled(enabled && filteredSource != null);
        if (modelCombo != null) modelCombo.setEnabled(enabled);
        if (manageModelsButton != null) manageModelsButton.setEnabled(false);
        if (probabilityField != null) probabilityField.setEnabled(enabled);
        if (nmsField != null) nmsField.setEnabled(enabled);
        if (linkingField != null) linkingField.setEnabled(enabled);
        if (gapClosingField != null) gapClosingField.setEnabled(enabled);
        if (frameGapField != null) frameGapField.setEnabled(enabled);
        if (areaMinField != null) areaMinField.setEnabled(enabled);
        if (areaMaxField != null) areaMaxField.setEnabled(enabled);
        if (qualityMinField != null) qualityMinField.setEnabled(enabled);
        if (intensityMinField != null) intensityMinField.setEnabled(enabled);
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

    private static void setTextForTest(JTextField field, String value) {
        if (field != null) field.setText(value);
    }

    private static void setNumberField(JTextField field, ParameterCombo combo, ParameterId id) {
        Object value = combo == null ? null : combo.get(id);
        if (field != null && value instanceof Number) {
            field.setText(String.valueOf(((Number) value).doubleValue()));
        }
    }

    private static void setIntegerField(JTextField field, ParameterCombo combo, ParameterId id) {
        Object value = combo == null ? null : combo.get(id);
        if (field != null && value instanceof Number) {
            field.setText(String.valueOf(sanitizeFrameGap(((Number) value).doubleValue())));
        }
    }

    private static List<ModelOption> modelOptionsFor(ConfigQcContext context) {
        File projectDir = context == null ? null : context.getProjectDirectory();
        File root = projectDir == null ? new File(".") : projectDir;
        ModelCatalog catalog = ModelCatalogIO.read(root.toPath());
        List<ModelEntry> entries = catalog.forEngine(ModelEntry.Engine.STARDIST);
        List<ModelEntry> stock = new ArrayList<ModelEntry>();
        List<ModelEntry> user = new ArrayList<ModelEntry>();
        for (ModelEntry entry : entries) {
            if (entry == null) continue;
            if (entry.isStock()) stock.add(entry);
            else user.add(entry);
        }
        Comparator<ModelEntry> byName = new Comparator<ModelEntry>() {
            @Override public int compare(ModelEntry left, ModelEntry right) {
                return labelFor(left).compareToIgnoreCase(labelFor(right));
            }
        };
        Collections.sort(stock, byName);
        Collections.sort(user, byName);

        List<ModelOption> out = new ArrayList<ModelOption>();
        for (ModelEntry entry : stock) out.add(new ModelOption(entry));
        for (ModelEntry entry : user) out.add(new ModelOption(entry));
        if (out.isEmpty()) {
            out.add(new ModelOption(new ModelEntry(
                    SegmentationMethod.DEFAULT_STARDIST_MODEL_KEY,
                    "StarDist - Versatile fluorescent nuclei",
                    "General 2D fluorescent nuclei model from the StarDist Fiji plugin.",
                    ModelEntry.Engine.STARDIST,
                    ModelEntry.Source.STOCK_RESOURCE,
                    null,
                    "models/2D/dsb2018_heavy_augment.zip",
                    null,
                    "Versatile (fluorescent nuclei)",
                    null,
                    defaultsMap(BinConfig.DEFAULT_STARDIST_PROB_THRESH,
                            BinConfig.DEFAULT_STARDIST_NMS_THRESH),
                    null,
                    false)));
        }
        return Collections.unmodifiableList(out);
    }

    private static Map<String, Object> defaultsMap(double prob, double nms) {
        Map<String, Object> defaults = new LinkedHashMap<String, Object>();
        defaults.put("probThresh", Double.valueOf(prob));
        defaults.put("nmsThresh", Double.valueOf(nms));
        return defaults;
    }

    private static String selectedModelKey(JComboBox<ModelOption> combo) {
        Object selected = combo == null ? null : combo.getSelectedItem();
        return selected instanceof ModelOption
                ? ((ModelOption) selected).entry.modelKey
                : SegmentationMethod.DEFAULT_STARDIST_MODEL_KEY;
    }

    private String selectedModelKey() {
        return selectedModelKey(modelCombo);
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

    private static boolean metadataBoolean(ModelEntry entry, String key) {
        if (entry == null || key == null) return false;
        Object value = entry.metadata.get(key);
        if (value instanceof Boolean) return ((Boolean) value).booleanValue();
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static boolean currentImageLooksRgb(ImagePlus image) {
        if (image == null) return false;
        return image.getType() == ImagePlus.COLOR_RGB || image.getNChannels() >= 3;
    }

    private void closePreviewWorker() {
        if (previewWorker != null && !previewWorker.isDone()) {
            previewWorker.cancel(true);
        }
        previewWorker = null;
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
        displayedSettings = null;
        objectStats = null;
        objectFilterSummary = null;
        rawSource = null;
        filteredSource = null;
        lastObjectCount = -1;
        if (previous != null && previous != label && previous != filtered && previous != raw) previewAdapter.close(previous);
        if (label != null && label != filtered && label != raw) previewAdapter.close(label);
        if (filtered != null && filtered != raw) previewAdapter.close(filtered);
        if (raw != null) previewAdapter.close(raw);
    }

    public static Parameters parseMethod(String method) {
        Parameters defaults = Parameters.defaults();
        if (method == null || !method.startsWith("stardist")) {
            return defaults;
        }
        SegmentationMethod parsed = SegmentationTokenParser.parseLenient(method);
        if (!parsed.isStarDist()) return defaults;
        StarDistLinkingParams linking = SegmentationMethod.starDistLinking(parsed);
        StarDistPostFilters filters = SegmentationMethod.starDistPostFilters(parsed);
        return new Parameters(
                SegmentationMethod.starDistProb(parsed),
                SegmentationMethod.starDistNms(parsed),
                linking.linkingMaxDistance,
                linking.gapClosingMaxDistance,
                linking.maxFrameGap,
                filters.areaMin, filters.areaMax, filters.qualityMin, filters.intensityMin,
                SegmentationMethod.starDistModelKey(parsed));
    }

    public static String formatMethod(Parameters parameters) {
        Parameters p = parameters == null ? Parameters.defaults() : parameters;
        LinkedHashMap<String, String> params = new LinkedHashMap<String, String>();
        params.put("prob", String.valueOf(p.probabilityThreshold));
        params.put("nms", String.valueOf(p.nmsThreshold));
        if (p.linkingMaxDistance != BinConfig.DEFAULT_STARDIST_LINKING_MAX_DISTANCE) {
            params.put("linking", String.valueOf(p.linkingMaxDistance));
        }
        if (p.gapClosingMaxDistance != BinConfig.DEFAULT_STARDIST_GAP_CLOSING_MAX_DISTANCE) {
            params.put("gapClosing", String.valueOf(p.gapClosingMaxDistance));
        }
        if (p.maxFrameGap != BinConfig.DEFAULT_STARDIST_MAX_FRAME_GAP) {
            params.put("frameGap", String.valueOf(p.maxFrameGap));
        }
        if (p.areaMin > 0 || Double.isFinite(p.areaMax)) {
            params.put("area", String.valueOf(p.areaMin) + "-"
                    + (Double.isInfinite(p.areaMax) ? "Infinity" : String.valueOf(p.areaMax)));
        }
        if (p.qualityMin > 0) params.put("quality", String.valueOf(p.qualityMin));
        if (p.intensityMin > 0) params.put("intensity", String.valueOf(p.intensityMin));
        params.put("model", normalizeModelKey(p.modelKey));
        return SegmentationTokenParser.format(new SegmentationMethod(
                SegmentationMethod.Engine.STARDIST, params, ""));
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

    private static double sanitizeNonNegative(double value) {
        return Math.max(0, value);
    }

    private static int sanitizeFrameGap(double value) {
        return Math.max(0, (int) Math.round(value));
    }

    private static String normalizeModelKey(String modelKey) {
        return modelKey == null || modelKey.trim().isEmpty()
                ? SegmentationMethod.DEFAULT_STARDIST_MODEL_KEY
                : modelKey.trim();
    }

    private static String labelFor(ModelEntry entry) {
        if (entry == null) return "";
        String label = entry.name == null || entry.name.trim().isEmpty()
                ? entry.modelKey
                : entry.name.trim();
        if (metadataBoolean(entry, "advanced") && metadataBoolean(entry, "rgbOnly")) {
            label += " (advanced - RGB)";
        }
        return label;
    }

    private static final class ModelOption {
        final ModelEntry entry;

        ModelOption(ModelEntry entry) {
            this.entry = entry;
        }

        String tooltip() {
            if (metadataBoolean(entry, "advanced") && metadataBoolean(entry, "rgbOnly")) {
                return "Advanced RGB model. FLASH previews one grayscale channel at a time, so use only with RGB-aware configuration.";
            }
            return entry == null ? null : entry.description;
        }

        @Override public String toString() {
            return labelFor(entry);
        }
    }

    private static final class ModelOptionRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof ModelOption) {
                ModelOption option = (ModelOption) value;
                setText(option.toString());
                if (list != null) {
                    list.setToolTipText(option.tooltip());
                }
            }
            return this;
        }
    }

    private static final class ObjectFilterSummary {
        final int totalCount;
        final int keptCount;
        final int starDistRemovedCount;

        ObjectFilterSummary(int totalCount,
                            int keptCount,
                            int starDistRemovedCount) {
            this.totalCount = totalCount;
            this.keptCount = keptCount;
            this.starDistRemovedCount = starDistRemovedCount;
        }

        String statusText() {
            if (totalCount <= 0) {
                return "Objects: not previewed";
            }
            if (starDistRemovedCount <= 0) {
                return "Objects: " + keptCount + " ready";
            }
            return "Objects: " + keptCount + " kept; removed "
                    + starDistRemovedCount + " by StarDist filters";
        }
    }
}
