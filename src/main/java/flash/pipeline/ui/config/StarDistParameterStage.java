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
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

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
    private JButton previewButton;
    private JButton resetButton;

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

        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
        createParameterFields();
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

    private void createParameterFields() {
        probabilityField = createNumberField(5);
        nmsField = createNumberField(5);
        linkingField = createNumberField(5);
        gapClosingField = createNumberField(5);
        frameGapField = createNumberField(4);
        areaMinField = createNumberField(5);
        areaMaxField = createNumberField(5);
        qualityMinField = createNumberField(5);
        intensityMinField = createNumberField(5);
    }

    private JTextField createNumberField(int columns) {
        JTextField field = new JTextField(columns);
        installFieldListener(field);
        return field;
    }

    private JComponent buildGroupRow(String headingText, String[] labels, JTextField[] fields) {
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        row.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.insets = new Insets(0, 0, 0, 6);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        JLabel heading = new JLabel(headingText);
        Font font = heading.getFont();
        if (font != null) heading.setFont(font.deriveFont(Font.BOLD));
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
        row.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.insets = new Insets(0, 0, 0, 6);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0;
        JLabel heading = new JLabel("Filters:");
        Font font = heading.getFont();
        if (font != null) heading.setFont(font.deriveFont(Font.BOLD));
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
        previewButton.addActionListener(e -> runPreviewOnWorker());
        gbc.gridx++;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        row.add(previewButton, gbc);

        resetButton = new JButton("Reset to saved");
        resetButton.addActionListener(e -> resetToSaved());
        gbc.gridx++;
        gbc.insets = new Insets(0, 2, 0, 0);
        row.add(resetButton, gbc);
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

    private static void setTextForTest(JTextField field, String value) {
        if (field != null) field.setText(value);
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
