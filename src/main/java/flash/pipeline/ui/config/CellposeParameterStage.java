package flash.pipeline.ui.config;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.cellpose.CellposeModel;
import flash.pipeline.ui.preview.LabelMapStyler;
import flash.pipeline.ui.preview.PreviewPairPanel;
import ij.ImagePlus;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Color;
import java.awt.Container;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CellposeParameterStage implements ConfigQcStage {

    private static final Color HELP_COLOR = new Color(90, 90, 90);

    public interface ParameterStore {
        String getMethodToken();
        void save(String methodToken);
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
        String runtimeSummary();
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

        public Parameters(String modelToken,
                          int secondChannelIndex,
                          double diameter,
                          double flowThreshold,
                          double cellprobThreshold,
                          boolean useGpu) {
            CellposeModel model = CellposeModel.fromToken(modelToken);
            this.modelToken = model.token();
            this.secondChannelIndex = model.supportsSecondChannel() ? secondChannelIndex : -1;
            this.diameter = sanitizeNonNegative(diameter);
            this.flowThreshold = flowThreshold;
            this.cellprobThreshold = cellprobThreshold;
            this.useGpu = useGpu;
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
    private ImagePlus rawSource;
    private ImagePlus filteredSource;
    private ImagePlus labelPreview;
    private SwingWorker<ImagePlus, Void> previewWorker;
    private SwingWorker<GpuInstallResult, Void> installWorker;
    private boolean previewStale = true;
    private boolean updatingControls;
    private boolean showRawSource;
    private int lastObjectCount = -1;

    private JComboBox<String> modelCombo;
    private JComboBox<String> companionCombo;
    private JTextField diameterField;
    private JTextField flowField;
    private JTextField cellprobField;
    private JCheckBox gpuCheckBox;
    private JButton previewButton;
    private JButton installGpuButton;
    private JButton resetButton;
    private JLabel modelDescriptionLabel;
    private JLabel companionHelpLabel;
    private JLabel runtimeLabel;

    public CellposeParameterStage(ParameterStore parameterStore,
                                  PreviewAdapter previewAdapter,
                                  RuntimeAdapter runtimeAdapter,
                                  List<String> channelNames,
                                  int primaryChannelIndex,
                                  boolean defaultUseGpu) {
        if (parameterStore == null) {
            throw new IllegalArgumentException("parameterStore must not be null");
        }
        if (previewAdapter == null) {
            throw new IllegalArgumentException("previewAdapter must not be null");
        }
        this.parameterStore = parameterStore;
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
    }

    @Override
    public String title() {
        return "Cellpose";
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
                ? parseMethod(parameterStore.getMethodToken(),
                defaultUseGpu,
                channelCount,
                primaryChannelIndex)
                : restartParameters;

        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
        panel.add(buildModelRow());
        panel.add(Box.createVerticalStrut(4));
        panel.add(buildDetectionRow());
        panel.add(Box.createVerticalStrut(4));
        panel.add(buildHintRow());
        panel.add(Box.createVerticalStrut(4));
        panel.add(buildActionRow());
        loadFields(savedParameters);
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
            refreshLargePreviewModel();
            setStatus(EMPTY_TEXT);
        } catch (Exception e) {
            closeImages();
            setError("Could not prepare Cellpose input: " + e.getMessage());
        }
    }

    @Override
    public boolean lockIn(ConfigQcContext context) {
        Parameters parameters = collectParameters();
        parameterStore.save(formatMethod(parameters));
        savedParameters = parameters;
        restartParameters = null;
        setStatus("Locked Cellpose parameters.");
        return true;
    }

    @Override
    public void skipCurrentImage(ConfigQcContext context) {
        setStatus("Skipped this image; Cellpose parameters are unchanged.");
    }

    @Override
    public void restartStage(ConfigQcContext context) {
        restartParameters = collectParameters();
        setStatus("Restarting Cellpose review from the first image.");
    }

    @Override
    public void onLeave(ConfigQcContext context) {
        closePreviewWorker();
        closeInstallWorker();
        if (preview != null) {
            preview.setSourceModeChangeListener(null);
            preview.setDisplaySettingsChangeListener(null);
            preview.clearLargePreviewImages();
        }
        closeImages();
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
        if (modelCombo != null) modelCombo.setSelectedItem(CellposeModel.fromToken(model).displayName());
    }

    void setCompanionForTest(String label) {
        if (companionCombo != null) companionCombo.setSelectedItem(label);
    }

    void setUseGpuForTest(boolean useGpu) {
        if (gpuCheckBox != null) {
            gpuCheckBox.setSelected(useGpu);
            fieldChanged();
        }
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

    private JComponent buildModelRow() {
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        row.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));

        modelCombo = new JComboBox<String>(CellposeModel.displayNames());
        modelCombo.addActionListener(e -> modelChanged());
        companionCombo = new JComboBox<String>(
                companionChoices.keySet().toArray(new String[0]));
        companionCombo.addActionListener(e -> companionChanged());
        gpuCheckBox = new JCheckBox("Use GPU");
        gpuCheckBox.setOpaque(false);
        gpuCheckBox.addActionListener(e -> fieldChanged());
        installGpuButton = new JButton("Install GPU Support");
        installGpuButton.addActionListener(e -> installGpuSupport());

        GridBagConstraints gbc = rowConstraints();
        row.add(new JLabel("Model"), gbc);
        gbc.gridx++;
        gbc.weightx = 0.25;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        row.add(modelCombo, gbc);
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
        row.add(gpuCheckBox, gbc);
        gbc.gridx++;
        row.add(installGpuButton, gbc);
        gbc.gridx++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        row.add(Box.createHorizontalGlue(), gbc);
        return row;
    }

    private JComponent buildDetectionRow() {
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        row.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));

        JLabel heading = new JLabel("Detection:");
        Font font = heading.getFont();
        if (font != null) heading.setFont(font.deriveFont(Font.BOLD));
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

    private JComponent buildHintRow() {
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        row.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));

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
        previewButton.addActionListener(e -> runPreviewOnWorker());
        resetButton = new JButton("Reset to saved");
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
        return row;
    }

    private JTextField createNumberField(int columns) {
        JTextField field = new JTextField(columns);
        installFieldListener(field);
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

    private void loadFields(Parameters parameters) {
        updatingControls = true;
        try {
            modelCombo.setSelectedItem(CellposeModel.fromToken(parameters.modelToken).displayName());
            companionCombo.setSelectedItem(companionChoiceLabel(companionChoices, parameters.secondChannelIndex));
            diameterField.setText(String.valueOf(parameters.diameter));
            flowField.setText(String.valueOf(parameters.flowThreshold));
            cellprobField.setText(String.valueOf(parameters.cellprobThreshold));
            gpuCheckBox.setSelected(parameters.useGpu);
            refreshModelDescriptionLabel();
            refreshCompanionHelpLabel();
        } finally {
            updatingControls = false;
        }
    }

    private void modelChanged() {
        refreshModelDescriptionLabel();
        refreshCompanionState();
        fieldChanged();
    }

    private void companionChanged() {
        refreshCompanionHelpLabel();
        fieldChanged();
    }

    private void refreshCompanionState() {
        if (companionCombo == null) return;
        boolean enabled = currentModel().supportsSecondChannel() && companionCombo.getItemCount() > 1;
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
            CellposeModel model = currentModel();
            modelDescriptionLabel.setText(model.displayName() + ": " + model.description());
        }
    }

    private void refreshCompanionHelpLabel() {
        if (companionHelpLabel == null) return;
        CellposeModel model = currentModel();
        boolean enabled = model.supportsSecondChannel()
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
        if (runtimeLabel != null) {
            String summary = runtimeAdapter.runtimeSummary();
            runtimeLabel.setText(summary == null || summary.trim().isEmpty()
                    ? "Runtime: not checked."
                    : "Runtime: " + summary);
        }
    }

    private void fieldChanged() {
        if (updatingControls) return;
        markPreviewStale(STALE_TEXT);
    }

    private void resetToSaved() {
        loadFields(savedParameters);
        refreshCompanionState();
        markPreviewStale(STALE_TEXT);
    }

    private void runPreviewOnWorker() {
        if (previewWorker != null && !previewWorker.isDone()) return;
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
                try {
                    installLabelPreview(get());
                } catch (Exception e) {
                    setError("Cellpose preview failed: " + e.getMessage());
                } finally {
                    setButtonsEnabled(true);
                }
            }
        };
        previewWorker.execute();
    }

    private void runPreviewNow() throws Exception {
        if (filteredSource == null) {
            throw new IllegalStateException("No Cellpose input image is available.");
        }
        setPreviewState(PreviewPairPanel.PreviewState.RUNNING, "Running Cellpose preview...");
        installLabelPreview(runPreviewWithCompanion(collectParameters()));
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

    private void installLabelPreview(ImagePlus labelImage) {
        if (labelImage == null) {
            setPreviewState(PreviewPairPanel.PreviewState.ERROR, "Cellpose returned no label map.");
            setStatus("Cellpose returned no label map.");
            return;
        }
        int count = previewAdapter.countLabels(labelImage);
        labelImage.setTitle("Cellpose label preview");
        LabelMapStyler.apply(labelImage, count);
        ImagePlus old = labelPreview;
        labelPreview = labelImage;
        previewStale = false;
        lastObjectCount = count;
        String text = "Objects: " + count + " ready";
        refreshSourceAndOutputPreview();
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
        return lastObjectCount >= 0
                ? "Objects: " + lastObjectCount + " ready"
                : "Objects: not previewed";
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
                try {
                    GpuInstallResult result = get();
                    if (result != null && result.success) {
                        gpuCheckBox.setSelected(true);
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
                    installGpuButton.setEnabled(true);
                    refreshRuntimeLabel();
                }
            }
        };
        installWorker.execute();
    }

    private Parameters collectParameters() {
        Parameters fallback = savedParameters == null ? Parameters.defaults(defaultUseGpu) : savedParameters;
        CellposeModel model = currentModel();
        int secondChannel = model.supportsSecondChannel()
                ? selectedCompanionIndex(companionChoices, companionCombo == null ? null : companionCombo.getSelectedItem())
                : -1;
        return new Parameters(
                model.token(),
                secondChannel,
                parse(diameterField, fallback.diameter),
                parse(flowField, fallback.flowThreshold),
                parse(cellprobField, fallback.cellprobThreshold),
                gpuCheckBox == null ? fallback.useGpu : gpuCheckBox.isSelected());
    }

    private CellposeModel currentModel() {
        Object selected = modelCombo == null ? null : modelCombo.getSelectedItem();
        return CellposeModel.fromToken(selected == null ? null : String.valueOf(selected));
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
        if (modelCombo != null) modelCombo.setEnabled(enabled);
        if (companionCombo != null) {
            companionCombo.setEnabled(enabled
                    && currentModel().supportsSecondChannel()
                    && companionCombo.getItemCount() > 1);
        }
        if (diameterField != null) diameterField.setEnabled(enabled);
        if (flowField != null) flowField.setEnabled(enabled);
        if (cellprobField != null) cellprobField.setEnabled(enabled);
        if (gpuCheckBox != null) gpuCheckBox.setEnabled(enabled);
        if (preview != null) {
            preview.setSourceModeEnabled(enabled);
            preview.setObjectOverlayEnabled(enabled);
        }
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
        labelPreview = null;
        rawSource = null;
        filteredSource = null;
        lastObjectCount = -1;
        if (label != null && label != filtered && label != raw) previewAdapter.close(label);
        if (filtered != null && filtered != raw) previewAdapter.close(filtered);
        if (raw != null) previewAdapter.close(raw);
    }

    public static Parameters parseMethod(String method) {
        return parseMethod(method, BinConfig.DEFAULT_CELLPOSE_USE_GPU, 0, -1);
    }

    public static Parameters parseMethod(String method, boolean fallbackUseGpu,
                                         int channelCount, int primaryChannelIndex) {
        Parameters defaults = Parameters.defaults(fallbackUseGpu);
        if (method == null || !method.startsWith("cellpose:")) {
            return defaults;
        }
        String model = defaults.modelToken;
        double diameter = defaults.diameter;
        double flow = defaults.flowThreshold;
        double cellprob = defaults.cellprobThreshold;
        boolean useGpu = defaults.useGpu;
        int secondChannelIndex = -1;

        String[] parts = method.split(":");
        if (parts.length >= 2) diameter = parse(parts[1], diameter);
        if (parts.length >= 3 && parts[2] != null && !parts[2].trim().isEmpty()) {
            model = parts[2].trim();
        }
        if (parts.length >= 4) flow = parse(parts[3], flow);
        if (parts.length >= 5) cellprob = parse(parts[4], cellprob);
        for (int i = 5; i < parts.length; i++) {
            String part = parts[i] == null ? "" : parts[i].trim();
            if (part.startsWith("gpu=")) {
                useGpu = !"false".equalsIgnoreCase(part.substring("gpu=".length()));
            } else if (part.startsWith("chan2=")) {
                secondChannelIndex = parseInt(part.substring("chan2=".length()), -1);
            }
        }
        if (secondChannelIndex < 0
                || secondChannelIndex == primaryChannelIndex
                || (channelCount > 0 && secondChannelIndex >= channelCount)
                || !CellposeModel.fromToken(model).supportsSecondChannel()) {
            secondChannelIndex = -1;
        }
        return new Parameters(model, secondChannelIndex, diameter, flow, cellprob, useGpu);
    }

    public static String formatMethod(Parameters parameters) {
        Parameters p = parameters == null
                ? Parameters.defaults(BinConfig.DEFAULT_CELLPOSE_USE_GPU)
                : parameters;
        CellposeModel model = CellposeModel.fromToken(p.modelToken);
        StringBuilder method = new StringBuilder();
        method.append("cellpose:")
                .append(p.diameter)
                .append(":")
                .append(model.token())
                .append(":")
                .append(p.flowThreshold)
                .append(":")
                .append(p.cellprobThreshold)
                .append(":gpu=")
                .append(p.useGpu);
        if (model.supportsSecondChannel() && p.secondChannelIndex >= 0) {
            method.append(":chan2=").append(p.secondChannelIndex);
        }
        return method.toString();
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

    private static RuntimeAdapter noopRuntimeAdapter() {
        return new RuntimeAdapter() {
            @Override public String runtimeSummary() {
                return "";
            }

            @Override public boolean nvidiaGpuLikelyAvailable() {
                return false;
            }

            @Override public GpuInstallResult installGpuSupport() {
                return new GpuInstallResult(false, "GPU install is not available here.", "");
            }
        };
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
}
