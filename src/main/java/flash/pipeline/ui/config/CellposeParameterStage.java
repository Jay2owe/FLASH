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
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
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

    private static final String STALE_TEXT = "Preview is stale. Press Run Cellpose Preview.";
    private static final String EMPTY_TEXT = "Filtered input is ready. Press Run Cellpose Preview.";

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
    private JButton sourceToggleButton;
    private JButton previewButton;
    private JButton installGpuButton;
    private JButton resetButton;
    private JLabel modelDescriptionLabel;
    private JComponent companionHelpLabel;
    private JLabel runtimeLabel;
    private JLabel countLabel;
    private JLabel feedbackLabel;

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
    public JComponent buildControls(ConfigQcContext context, ConfigQcActions actions) {
        this.actions = actions;
        this.activeContext = context;
        this.savedParameters = restartParameters == null
                ? parseMethod(parameterStore.getMethodToken(),
                defaultUseGpu,
                channelCount,
                primaryChannelIndex)
                : restartParameters;

        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setOpaque(false);
        panel.add(buildSummaryPanel(context), BorderLayout.NORTH);
        panel.add(buildFieldsPanel(), BorderLayout.CENTER);
        panel.add(buildActionPanel(), BorderLayout.SOUTH);
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
        refreshSourceToggleButton();
        if (preview != null) {
            preview.clearLargePreviewImages();
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

    void setModelForTest(String model) {
        if (modelCombo != null) modelCombo.setSelectedItem(CellposeModel.fromToken(model).displayName());
    }

    void setCompanionForTest(String label) {
        if (companionCombo != null) companionCombo.setSelectedItem(label);
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

    String currentSourceTitleForTest() {
        ImagePlus source = currentSourceImage();
        return source == null ? null : source.getTitle();
    }

    int largePreviewPaneCountForTest() {
        return labelPreview == null ? 2 : 3;
    }

    private JComponent buildSummaryPanel(ConfigQcContext context) {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        JLabel channel = new JLabel(context == null ? "Channel" : context.getChannelLabel());
        JLabel image = new JLabel(context == null ? "Image" : context.getImageProgressText()
                + ": " + context.getCurrentImageDisplayName());
        JLabel help = new JLabel("Edit parameters, then press Run Cellpose Preview to update the label map.");
        help.setForeground(HELP_COLOR);

        runtimeLabel = new JLabel(runtimeAdapter.runtimeSummary());
        runtimeLabel.setForeground(HELP_COLOR);

        channel.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        image.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        help.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        runtimeLabel.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        panel.add(channel);
        panel.add(Box.createVerticalStrut(4));
        panel.add(image);
        panel.add(Box.createVerticalStrut(4));
        panel.add(help);
        panel.add(Box.createVerticalStrut(4));
        panel.add(runtimeLabel);
        return panel;
    }

    private JComponent buildFieldsPanel() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        panel.add(section("Built-in Model"));
        modelCombo = new JComboBox<String>(CellposeModel.displayNames());
        modelCombo.addActionListener(e -> modelChanged());
        panel.add(comboRow("Model", modelCombo));
        panel.add(helperText("Select the trained Cellpose model. Start with cyto3 for cell bodies, or nuclei for rounded nuclear objects."));
        modelDescriptionLabel = new JLabel(" ");
        modelDescriptionLabel.setForeground(HELP_COLOR);
        modelDescriptionLabel.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        panel.add(modelDescriptionLabel);
        panel.add(Box.createVerticalStrut(4));

        companionCombo = new JComboBox<String>(
                companionChoices.keySet().toArray(new String[0]));
        companionCombo.addActionListener(e -> fieldChanged());
        panel.add(comboRow("Companion Channel", companionCombo));
        companionHelpLabel = helperText("Optional second channel used by cyto models for guidance, usually a nuclear marker. Leave blank if unsure.");
        panel.add(companionHelpLabel);
        panel.add(Box.createVerticalStrut(8));

        panel.add(section("Detection"));
        diameterField = addNumberRow(panel, "Expected Diameter", 8,
                "Approximate object diameter in image units. Use 0 to let Cellpose estimate it automatically.");
        flowField = addNumberRow(panel, "Flow Threshold", 8,
                "Controls how consistent object shapes must be. Higher values are stricter and can remove poor masks.");
        cellprobField = addNumberRow(panel, "Cell Probability", 8,
                "Minimum Cellpose object probability. Higher values reduce false positives but may miss weak objects.");
        gpuCheckBox = new JCheckBox("Use GPU");
        gpuCheckBox.setOpaque(false);
        gpuCheckBox.addActionListener(e -> fieldChanged());
        gpuCheckBox.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        panel.add(gpuCheckBox);
        panel.add(helperText("Runs Cellpose on the GPU when available. Faster for large images, but requires working GPU support."));
        return panel;
    }

    private JLabel section(String text) {
        JLabel label = new JLabel(text);
        label.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        label.setFont(label.getFont().deriveFont(java.awt.Font.BOLD));
        return label;
    }

    private JComponent comboRow(String labelText, JComboBox<String> combo) {
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        row.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.insets = new Insets(0, 0, 0, 6);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 0;
        gbc.weightx = 0.35;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        row.add(new JLabel(labelText), gbc);
        gbc.gridx = 1;
        gbc.weightx = 0.65;
        row.add(combo, gbc);
        return row;
    }

    private JTextField addNumberRow(JPanel parent, String labelText, int columns, String helpText) {
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        row.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.insets = new Insets(0, 0, 0, 6);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        row.add(new JLabel(labelText), gbc);

        JTextField field = new JTextField(columns);
        installFieldListener(field);
        gbc.gridx = 1;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        row.add(field, gbc);

        parent.add(row);
        parent.add(helperText(helpText));
        return field;
    }

    private JComponent helperText(String text) {
        JTextArea helper = new JTextArea(text == null ? "" : text);
        helper.setOpaque(false);
        helper.setEditable(false);
        helper.setFocusable(false);
        helper.setLineWrap(true);
        helper.setWrapStyleWord(true);
        helper.setColumns(36);
        helper.setForeground(HELP_COLOR);
        helper.setFont(new JLabel().getFont());
        helper.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        helper.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        return helper;
    }

    private JComponent buildActionPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setOpaque(false);

        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));

        sourceToggleButton = new JButton("Show Raw Image");
        sourceToggleButton.addActionListener(e -> setRawSourceVisible(!showRawSource));
        buttons.add(sourceToggleButton);
        buttons.add(Box.createHorizontalStrut(6));

        installGpuButton = new JButton("Install GPU Support");
        installGpuButton.addActionListener(e -> installGpuSupport());
        buttons.add(installGpuButton);
        buttons.add(Box.createHorizontalStrut(6));

        previewButton = new JButton("Run Cellpose Preview");
        previewButton.addActionListener(e -> runPreviewOnWorker());
        buttons.add(previewButton);
        buttons.add(Box.createHorizontalStrut(6));

        resetButton = new JButton("Reset to saved");
        resetButton.addActionListener(e -> resetToSaved());
        buttons.add(resetButton);
        buttons.add(Box.createHorizontalGlue());

        JPanel feedback = new JPanel();
        feedback.setOpaque(false);
        feedback.setLayout(new BoxLayout(feedback, BoxLayout.Y_AXIS));
        countLabel = new JLabel("Objects detected: not previewed");
        countLabel.setForeground(new Color(90, 90, 90));
        feedbackLabel = new JLabel(" ");
        feedbackLabel.setForeground(new Color(90, 90, 90));
        countLabel.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        feedbackLabel.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        feedback.add(countLabel);
        feedback.add(Box.createVerticalStrut(2));
        feedback.add(feedbackLabel);

        panel.add(buttons, BorderLayout.NORTH);
        panel.add(feedback, BorderLayout.CENTER);
        return panel;
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
            modelDescriptionLabel.setText(CellposeModel.fromToken(parameters.modelToken).description());
        } finally {
            updatingControls = false;
        }
    }

    private void modelChanged() {
        CellposeModel model = currentModel();
        if (modelDescriptionLabel != null) {
            modelDescriptionLabel.setText(model.description());
        }
        refreshCompanionState();
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
        if (companionHelpLabel != null) {
            companionHelpLabel.setVisible(enabled);
        }
        Container parent = companionCombo.getParent();
        if (parent != null) {
            parent.revalidate();
            parent.repaint();
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
        String text = "Objects detected: " + count;
        if (countLabel != null) countLabel.setText(text);
        refreshSourceAndOutputPreview();
        setStatus(text);
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
            }
        } else if (actions != null) {
            actions.setAdjustedPreview(labelPreview, text);
        } else if (preview != null) {
            preview.setAdjusted(labelPreview);
            preview.setAdjustedState(PreviewPairPanel.PreviewState.READY, text);
        }
    }

    private void refreshLargePreviewModel() {
        if (preview == null) return;
        preview.setLargePreviewImages(rawSource, filteredSource, labelPreview);
    }

    private ImagePlus currentSourceImage() {
        return showRawSource && rawSource != null ? rawSource : filteredSource;
    }

    private void setRawSourceVisible(boolean showRaw) {
        showRawSource = showRaw;
        refreshSourceToggleButton();
        refreshSourceAndOutputPreview();
    }

    private void refreshSourceToggleButton() {
        if (sourceToggleButton != null) {
            sourceToggleButton.setText(showRawSource ? "Show Filtered Image" : "Show Raw Image");
        }
    }

    private String objectCountText() {
        return lastObjectCount >= 0
                ? "Objects detected: " + lastObjectCount
                : "Objects detected: not previewed";
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
                    if (runtimeLabel != null) {
                        runtimeLabel.setText(runtimeAdapter.runtimeSummary());
                    }
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
    }

    private void setPreviewState(PreviewPairPanel.PreviewState state, String text) {
        if (preview != null) {
            preview.setAdjustedState(state, text);
        }
        if (actions != null) {
            if (state == PreviewPairPanel.PreviewState.STALE) {
                actions.markPreviewStale(text);
            } else {
                actions.setStatus(text);
            }
        }
        if (feedbackLabel != null) {
            feedbackLabel.setText(text == null || text.trim().isEmpty() ? " " : text);
        }
    }

    private void setStatus(String text) {
        if (feedbackLabel != null) {
            feedbackLabel.setText(text == null || text.trim().isEmpty() ? " " : text);
        }
        if (actions != null) {
            actions.setStatus(text);
        }
    }

    private void setError(String text) {
        setPreviewState(PreviewPairPanel.PreviewState.ERROR, text);
        setStatus(text);
    }

    private void setButtonsEnabled(boolean enabled) {
        if (sourceToggleButton != null) sourceToggleButton.setEnabled(enabled);
        if (previewButton != null) previewButton.setEnabled(enabled);
        if (installGpuButton != null) installGpuButton.setEnabled(enabled);
        if (resetButton != null) resetButton.setEnabled(enabled);
        if (modelCombo != null) modelCombo.setEnabled(enabled);
        if (companionCombo != null) companionCombo.setEnabled(enabled && currentModel().supportsSecondChannel());
        if (diameterField != null) diameterField.setEnabled(enabled);
        if (flowField != null) flowField.setEnabled(enabled);
        if (cellprobField != null) cellprobField.setEnabled(enabled);
        if (gpuCheckBox != null) gpuCheckBox.setEnabled(enabled);
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
