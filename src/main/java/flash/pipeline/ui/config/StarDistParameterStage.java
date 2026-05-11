package flash.pipeline.ui.config;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.ui.preview.LabelMapStyler;
import flash.pipeline.ui.preview.PreviewPairPanel;
import ij.ImagePlus;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

public final class StarDistParameterStage implements ConfigQcStage {

    private static final Color HELP_COLOR = new Color(90, 90, 90);

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

        public Parameters(double probabilityThreshold,
                          double nmsThreshold,
                          double linkingMaxDistance,
                          double gapClosingMaxDistance,
                          int maxFrameGap,
                          double areaMin,
                          double areaMax,
                          double qualityMin,
                          double intensityMin) {
            this.probabilityThreshold = probabilityThreshold;
            this.nmsThreshold = nmsThreshold;
            this.linkingMaxDistance = sanitizeNonNegative(linkingMaxDistance);
            this.gapClosingMaxDistance = sanitizeNonNegative(gapClosingMaxDistance);
            this.maxFrameGap = sanitizeFrameGap(maxFrameGap);
            this.areaMin = sanitizeNonNegative(areaMin);
            this.areaMax = areaMax <= 0 ? Double.POSITIVE_INFINITY : areaMax;
            this.qualityMin = sanitizeNonNegative(qualityMin);
            this.intensityMin = sanitizeNonNegative(intensityMin);
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
                    0);
        }
    }

    private static final String STALE_TEXT = "Preview is stale. Press Run StarDist Preview.";
    private static final String EMPTY_TEXT = "Filtered input is ready. Press Run StarDist Preview.";

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
    private SwingWorker<ImagePlus, Void> previewWorker;
    private boolean previewStale = true;
    private boolean updatingFields;
    private boolean showRawSource;
    private int lastObjectCount = -1;

    private JTextField probabilityField;
    private JTextField nmsField;
    private JTextField linkingField;
    private JTextField gapClosingField;
    private JTextField frameGapField;
    private JTextField areaMinField;
    private JTextField areaMaxField;
    private JTextField qualityMinField;
    private JTextField intensityMinField;
    private JButton sourceToggleButton;
    private JButton previewButton;
    private JButton resetButton;
    private JLabel feedbackLabel;
    private JLabel countLabel;

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
        return "StarDist 3D";
    }

    @Override
    public JComponent buildControls(ConfigQcContext context, ConfigQcActions actions) {
        this.actions = actions;
        this.activeContext = context;
        this.savedParameters = restartParameters == null
                ? parseMethod(parameterStore.getMethodToken())
                : restartParameters;

        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setOpaque(false);
        panel.add(buildSummaryPanel(context), BorderLayout.NORTH);
        panel.add(buildFieldsPanel(), BorderLayout.CENTER);
        panel.add(buildActionPanel(), BorderLayout.SOUTH);
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
        refreshSourceToggleButton();
        if (preview != null) {
            preview.clearLargePreviewImages();
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
        } catch (Exception e) {
            closeImages();
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

    void setProbabilityForTest(String value) {
        if (probabilityField != null) probabilityField.setText(value);
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
        JLabel help = new JLabel("Edit parameters, then press Run StarDist Preview to update the label map.");
        help.setForeground(HELP_COLOR);

        channel.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        image.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        help.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        panel.add(channel);
        panel.add(Box.createVerticalStrut(4));
        panel.add(image);
        panel.add(Box.createVerticalStrut(4));
        panel.add(help);
        return panel;
    }

    private JComponent buildFieldsPanel() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        panel.add(section("Detection"));
        probabilityField = addNumberRow(panel, "Probability Threshold", 8,
                "Minimum confidence required for StarDist to accept a detection. Higher values are stricter and may miss dim objects.");
        nmsField = addNumberRow(panel, "NMS Threshold", 8,
                "Controls how much overlapping detections are allowed. Lower values remove more overlaps; higher values allow closer objects.");
        panel.add(Box.createVerticalStrut(8));

        panel.add(section("3D Linking"));
        linkingField = addNumberRow(panel, "Linking Max Distance", 8,
                "Maximum distance used to link detections between adjacent z planes into one 3D object.");
        gapClosingField = addNumberRow(panel, "Gap-Closing Max Distance", 8,
                "Maximum distance used when bridging across a missing z plane. Increase if objects break between slices.");
        frameGapField = addNumberRow(panel, "Max Frame Gap", 8,
                "Number of missing z planes that can be bridged during 3D linking. 0 disables gap closing.");
        panel.add(Box.createVerticalStrut(8));

        panel.add(section("Post-Detection Filters"));
        areaMinField = addNumberRow(panel, "Min Area", 8,
                "Removes detections smaller than this estimated spot area. Raise this to remove small speckles.");
        areaMaxField = addNumberRow(panel, "Max Area (0 = none)", 8,
                "Removes detections larger than this estimated spot area. Use 0 to disable the upper size limit.");
        qualityMinField = addNumberRow(panel, "Min Quality", 8,
                "Removes detections with low StarDist/TrackMate quality scores. Higher values are stricter.");
        intensityMinField = addNumberRow(panel, "Min Mean Intensity", 8,
                "Removes detections with low average signal intensity. Raise this to reject dim background detections.");
        return panel;
    }

    private JLabel section(String text) {
        JLabel label = new JLabel(text);
        label.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        label.setFont(label.getFont().deriveFont(java.awt.Font.BOLD));
        return label;
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

        previewButton = new JButton("Run StarDist Preview");
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
        updatingFields = true;
        try {
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

    private void fieldChanged() {
        if (updatingFields) return;
        markPreviewStale(STALE_TEXT);
    }

    private void resetToSaved() {
        loadFields(savedParameters);
        markPreviewStale(STALE_TEXT);
    }

    private void runPreviewOnWorker() {
        if (previewWorker != null && !previewWorker.isDone()) return;
        if (filteredSource == null) {
            setError("No StarDist input image is available.");
            return;
        }
        final Parameters parameters = collectParameters();
        setPreviewState(PreviewPairPanel.PreviewState.RUNNING, "Running StarDist preview...");
        setButtonsEnabled(false);
        previewWorker = new SwingWorker<ImagePlus, Void>() {
            @Override protected ImagePlus doInBackground() throws Exception {
                return previewAdapter.runPreview(filteredSource, parameters);
            }

            @Override protected void done() {
                try {
                    installLabelPreview(get());
                } catch (Exception e) {
                    setPreviewError("StarDist preview failed: " + e.getMessage());
                } finally {
                    setButtonsEnabled(true);
                }
            }
        };
        previewWorker.execute();
    }

    private void runPreviewNow() throws Exception {
        if (filteredSource == null) {
            throw new IllegalStateException("No StarDist input image is available.");
        }
        setPreviewState(PreviewPairPanel.PreviewState.RUNNING, "Running StarDist preview...");
        installLabelPreview(previewAdapter.runPreview(filteredSource, collectParameters()));
    }

    private void installLabelPreview(ImagePlus labelImage) {
        if (labelImage == null) {
            setPreviewError("StarDist returned no label map.");
            return;
        }
        int count = previewAdapter.countLabels(labelImage);
        labelImage.setTitle("StarDist label preview");
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
                parse(intensityMinField, fallback.intensityMin));
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

    private void setPreviewError(String text) {
        ImagePlus old = labelPreview;
        labelPreview = null;
        previewStale = true;
        lastObjectCount = -1;
        if (countLabel != null) {
            countLabel.setText("Objects detected: not previewed");
        }
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
        if (sourceToggleButton != null) sourceToggleButton.setEnabled(enabled);
        if (previewButton != null) previewButton.setEnabled(enabled);
        if (resetButton != null) resetButton.setEnabled(enabled);
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
        labelPreview = null;
        rawSource = null;
        filteredSource = null;
        lastObjectCount = -1;
        if (label != null && label != filtered && label != raw) previewAdapter.close(label);
        if (filtered != null && filtered != raw) previewAdapter.close(filtered);
        if (raw != null) previewAdapter.close(raw);
    }

    public static Parameters parseMethod(String method) {
        Parameters defaults = Parameters.defaults();
        if (method == null || !method.startsWith("stardist")) {
            return defaults;
        }
        double prob = defaults.probabilityThreshold;
        double nms = defaults.nmsThreshold;
        double linking = defaults.linkingMaxDistance;
        double gapClosing = defaults.gapClosingMaxDistance;
        int frameGap = defaults.maxFrameGap;
        double areaMin = defaults.areaMin;
        double areaMax = defaults.areaMax;
        double quality = defaults.qualityMin;
        double intensity = defaults.intensityMin;

        String[] parts = method.split(":");
        if (parts.length >= 2) prob = parse(parts[1], prob);
        if (parts.length >= 3) nms = parse(parts[2], nms);
        for (int i = 3; i < parts.length; i++) {
            String part = parts[i] == null ? "" : parts[i].trim();
            if (part.startsWith("linking=")) {
                linking = sanitizeNonNegative(parse(part.substring("linking=".length()), linking));
            } else if (part.startsWith("gapClosing=")) {
                gapClosing = sanitizeNonNegative(parse(part.substring("gapClosing=".length()), gapClosing));
            } else if (part.startsWith("frameGap=")) {
                frameGap = sanitizeFrameGap(parse(part.substring("frameGap=".length()), frameGap));
            } else if (part.startsWith("area=")) {
                String[] range = part.substring("area=".length()).split("-", 2);
                if (range.length >= 1) areaMin = sanitizeNonNegative(parse(range[0], areaMin));
                if (range.length >= 2) {
                    areaMax = "Infinity".equalsIgnoreCase(range[1])
                            ? Double.POSITIVE_INFINITY
                            : sanitizeNonNegative(parse(range[1], areaMax));
                }
            } else if (part.startsWith("quality=")) {
                quality = sanitizeNonNegative(parse(part.substring("quality=".length()), quality));
            } else if (part.startsWith("intensity=")) {
                intensity = sanitizeNonNegative(parse(part.substring("intensity=".length()), intensity));
            }
        }
        return new Parameters(prob, nms, linking, gapClosing, frameGap,
                areaMin, areaMax, quality, intensity);
    }

    public static String formatMethod(Parameters parameters) {
        Parameters p = parameters == null ? Parameters.defaults() : parameters;
        StringBuilder method = new StringBuilder();
        method.append("stardist:")
                .append(p.probabilityThreshold)
                .append(":")
                .append(p.nmsThreshold);
        if (p.linkingMaxDistance != BinConfig.DEFAULT_STARDIST_LINKING_MAX_DISTANCE) {
            method.append(":linking=").append(p.linkingMaxDistance);
        }
        if (p.gapClosingMaxDistance != BinConfig.DEFAULT_STARDIST_GAP_CLOSING_MAX_DISTANCE) {
            method.append(":gapClosing=").append(p.gapClosingMaxDistance);
        }
        if (p.maxFrameGap != BinConfig.DEFAULT_STARDIST_MAX_FRAME_GAP) {
            method.append(":frameGap=").append(p.maxFrameGap);
        }
        if (p.areaMin > 0 || Double.isFinite(p.areaMax)) {
            method.append(":area=").append(p.areaMin).append("-")
                    .append(Double.isInfinite(p.areaMax) ? "Infinity" : String.valueOf(p.areaMax));
        }
        if (p.qualityMin > 0) method.append(":quality=").append(p.qualityMin);
        if (p.intensityMin > 0) method.append(":intensity=").append(p.intensityMin);
        return method.toString();
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
}
