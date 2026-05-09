package flash.pipeline.ui.config;

import flash.pipeline.objects.ObjectsCounter3DWrapper;
import flash.pipeline.ui.preview.LabelMapStyler;
import flash.pipeline.ui.preview.ObjectOverlayRenderer;
import flash.pipeline.ui.preview.PreviewPairPanel;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public final class ParticleSizeStage implements ConfigQcStage {

    public interface SizeStore {
        String get();
        void set(String token);
    }

    public interface PreviewAdapter {
        ImagePlus createRawSource(ConfigQcContext context) throws Exception;
        ImagePlus createFilteredSource(ConfigQcContext context) throws Exception;
        int resolveThreshold(ImagePlus filteredSource, ConfigQcContext context) throws Exception;
        ObjectsCounter3DWrapper.Result runPreview(ImagePlus filteredSource,
                                                  int threshold,
                                                  int minSize,
                                                  int maxSize) throws Exception;
        int countObjects(ObjectsCounter3DWrapper.Result result);
        void close(ImagePlus image);
    }

    public static final class SizeToken {
        public final String minText;
        public final String maxText;

        public SizeToken(String minText, String maxText) {
            this.minText = firstNonBlank(minText, "100");
            this.maxText = firstNonBlank(maxText, "Infinity");
        }

        public String toToken() {
            return minText + "-" + maxText;
        }
    }

    private static final String STALE_TEXT = "Preview is stale. Press Run Object Preview.";
    private static final String EMPTY_TEXT = "Filtered input is ready. Press Run Object Preview.";

    private final SizeStore sizeStore;
    private final PreviewAdapter previewAdapter;

    private ConfigQcActions actions;
    private PreviewPairPanel preview;
    private ConfigQcContext activeContext;
    private SizeToken savedSize = new SizeToken("100", "Infinity");
    private ImagePlus rawSource;
    private ImagePlus filteredSource;
    private ImagePlus labelPreview;
    private ImagePlus labelDisplayPreview;
    private ImagePlus overlayPreview;
    private SwingWorker<ObjectsCounter3DWrapper.Result, Void> previewWorker;
    private boolean previewStale = true;
    private boolean updatingFields;
    private Integer thresholdValue;
    private int lastObjectCount = -1;

    private JTextField minField;
    private JTextField maxField;
    private JButton previewButton;
    private JButton resetButton;
    private JButton sourceToggleButton;
    private JCheckBox overlayCheck;
    private JLabel thresholdLabel;
    private JLabel countLabel;
    private JLabel feedbackLabel;
    private boolean showRawSource;

    public ParticleSizeStage(SizeStore sizeStore, PreviewAdapter previewAdapter) {
        if (sizeStore == null) {
            throw new IllegalArgumentException("sizeStore must not be null");
        }
        if (previewAdapter == null) {
            throw new IllegalArgumentException("previewAdapter must not be null");
        }
        this.sizeStore = sizeStore;
        this.previewAdapter = previewAdapter;
    }

    @Override
    public String title() {
        return "Particle Size";
    }

    @Override
    public JComponent buildControls(ConfigQcContext context, ConfigQcActions actions) {
        this.actions = actions;
        this.activeContext = context;
        this.savedSize = parseSizeToken(sizeStore.get());

        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setOpaque(false);
        panel.add(buildSummaryPanel(context), BorderLayout.NORTH);
        panel.add(buildFieldsPanel(), BorderLayout.CENTER);
        panel.add(buildActionPanel(), BorderLayout.SOUTH);
        loadFields(savedSize);
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
                throw new IllegalStateException("No raw particle-size input image is available.");
            }
            filteredSource = previewAdapter.createFilteredSource(context);
            if (filteredSource == null) {
                throw new IllegalStateException("No filtered particle-size input image is available.");
            }
            thresholdValue = Integer.valueOf(previewAdapter.resolveThreshold(filteredSource, context));
            refreshThresholdLabel();
            if (preview != null) {
                preview.setOriginal(currentSourceImage());
                preview.setAdjusted(null);
                preview.setAdjustedState(PreviewPairPanel.PreviewState.STALE, EMPTY_TEXT);
            }
            refreshLargePreviewModel();
            setStatus(EMPTY_TEXT);
        } catch (Exception e) {
            closeImages();
            thresholdValue = null;
            refreshThresholdLabel();
            setError("Could not prepare particle-size preview: " + e.getMessage());
        }
    }

    @Override
    public boolean lockIn(ConfigQcContext context) {
        try {
            SizeToken token = collectSizeToken();
            sizeStore.set(token.toToken());
            savedSize = token;
            setStatus("Locked particle sizes: " + token.toToken() + ".");
            return true;
        } catch (RuntimeException e) {
            setError("Enter valid min and max voxel sizes.");
            return false;
        }
    }

    @Override
    public void skipCurrentImage(ConfigQcContext context) {
        setStatus("Skipped this image; saved particle sizes are unchanged.");
    }

    @Override
    public void restartStage(ConfigQcContext context) {
        setStatus("Restarting particle-size review from the first image.");
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

    String currentSizeTokenForTest() {
        return collectSizeToken().toToken();
    }

    int thresholdForTest() {
        return thresholdValue == null ? -1 : thresholdValue.intValue();
    }

    void setMinSizeForTest(String value) {
        if (minField != null) minField.setText(value);
    }

    void setMaxSizeForTest(String value) {
        if (maxField != null) maxField.setText(value);
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
        if (overlayCheck != null) overlayCheck.setSelected(showOverlay);
        refreshSourceAndOutputPreview();
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
        JLabel help = new JLabel("Edit size limits, then press Run Object Preview to update the label map.");
        help.setForeground(new Color(90, 90, 90));
        thresholdLabel = new JLabel("Threshold used: not resolved");
        thresholdLabel.setForeground(new Color(90, 90, 90));

        channel.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        image.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        help.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        thresholdLabel.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        panel.add(channel);
        panel.add(Box.createVerticalStrut(4));
        panel.add(image);
        panel.add(Box.createVerticalStrut(4));
        panel.add(help);
        panel.add(Box.createVerticalStrut(4));
        panel.add(thresholdLabel);
        return panel;
    }

    private JComponent buildFieldsPanel() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Particle Sizes (n Voxels)"),
                BorderFactory.createEmptyBorder(6, 6, 6, 6)));
        minField = addSizeRow(panel, "Min Size", 8);
        maxField = addSizeRow(panel, "Max Size", 10);
        return panel;
    }

    private JTextField addSizeRow(JPanel parent, String labelText, int columns) {
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
        return field;
    }

    private JComponent buildActionPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setOpaque(false);

        JPanel controlsAndButtons = new JPanel();
        controlsAndButtons.setOpaque(false);
        controlsAndButtons.setLayout(new BoxLayout(controlsAndButtons, BoxLayout.Y_AXIS));

        JPanel viewControls = new JPanel();
        viewControls.setOpaque(false);
        viewControls.setLayout(new BoxLayout(viewControls, BoxLayout.X_AXIS));
        viewControls.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        sourceToggleButton = new JButton("Show Raw Image");
        sourceToggleButton.addActionListener(e -> setRawSourceVisible(!showRawSource));
        viewControls.add(sourceToggleButton);
        viewControls.add(Box.createHorizontalStrut(10));
        overlayCheck = new JCheckBox("Show overlay");
        overlayCheck.setOpaque(false);
        overlayCheck.addActionListener(e -> refreshSourceAndOutputPreview());
        viewControls.add(overlayCheck);
        viewControls.add(Box.createHorizontalGlue());

        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        previewButton = new JButton("Run Object Preview");
        previewButton.addActionListener(e -> runPreviewOnWorker());
        buttons.add(previewButton);
        buttons.add(Box.createHorizontalStrut(6));

        resetButton = new JButton("Reset to saved");
        resetButton.addActionListener(e -> resetToSaved());
        buttons.add(resetButton);
        buttons.add(Box.createHorizontalGlue());

        controlsAndButtons.add(viewControls);
        controlsAndButtons.add(Box.createVerticalStrut(6));
        controlsAndButtons.add(buttons);

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

        panel.add(controlsAndButtons, BorderLayout.NORTH);
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

    private void loadFields(SizeToken token) {
        updatingFields = true;
        try {
            SizeToken safe = token == null ? new SizeToken("100", "Infinity") : token;
            if (minField != null) minField.setText(safe.minText);
            if (maxField != null) maxField.setText(safe.maxText);
        } finally {
            updatingFields = false;
        }
    }

    private void fieldChanged() {
        if (updatingFields) return;
        markPreviewStale(STALE_TEXT);
    }

    private void resetToSaved() {
        loadFields(savedSize);
        markPreviewStale(STALE_TEXT);
    }

    private void runPreviewOnWorker() {
        if (previewWorker != null && !previewWorker.isDone()) return;
        if (filteredSource == null || thresholdValue == null) {
            setError("No particle-size input image is available.");
            return;
        }
        final SizeToken token;
        final int minSize;
        final int maxSize;
        try {
            token = collectSizeToken();
            minSize = ObjectsCounter3DWrapper.parseMinSizeVoxels(token.minText, 100);
            maxSize = ObjectsCounter3DWrapper.parseMaxSizeVoxels(token.maxText, filteredSource);
        } catch (RuntimeException e) {
            setError("Enter valid min and max voxel sizes.");
            return;
        }
        setPreviewState(PreviewPairPanel.PreviewState.RUNNING, "Running object preview...");
        setButtonsEnabled(false);
        previewWorker = new SwingWorker<ObjectsCounter3DWrapper.Result, Void>() {
            @Override protected ObjectsCounter3DWrapper.Result doInBackground() throws Exception {
                return previewAdapter.runPreview(filteredSource, thresholdValue.intValue(), minSize, maxSize);
            }

            @Override protected void done() {
                try {
                    installObjectPreview(get());
                } catch (Exception e) {
                    setError("Object preview failed: " + e.getMessage());
                } finally {
                    setButtonsEnabled(true);
                }
            }
        };
        previewWorker.execute();
    }

    private void runPreviewNow() throws Exception {
        if (filteredSource == null || thresholdValue == null) {
            throw new IllegalStateException("No particle-size input image is available.");
        }
        SizeToken token = collectSizeToken();
        int minSize = ObjectsCounter3DWrapper.parseMinSizeVoxels(token.minText, 100);
        int maxSize = ObjectsCounter3DWrapper.parseMaxSizeVoxels(token.maxText, filteredSource);
        setPreviewState(PreviewPairPanel.PreviewState.RUNNING, "Running object preview...");
        installObjectPreview(previewAdapter.runPreview(
                filteredSource, thresholdValue.intValue(), minSize, maxSize));
    }

    private void installObjectPreview(ObjectsCounter3DWrapper.Result result) {
        int count = previewAdapter.countObjects(result);
        ImagePlus labelImage = result == null ? null : result.getObjectsMap();
        if (labelImage == null) {
            labelImage = emptyLabelMapLike(filteredSource);
        }
        if (labelImage == null) {
            setPreviewState(PreviewPairPanel.PreviewState.ERROR, "Object preview returned no label map.");
            setStatus("Object preview returned no label map.");
            return;
        }
        if (result != null && result.getMaskedImage() != null) {
            previewAdapter.close(result.getMaskedImage());
        }
        labelImage.setTitle(count > 0 ? "Object label preview" : "Object label preview (no objects)");
        LabelMapStyler.apply(labelImage, count);

        ImagePlus old = labelPreview;
        ImagePlus oldDisplay = labelDisplayPreview;
        ImagePlus oldOverlay = overlayPreview;
        labelPreview = labelImage;
        labelDisplayPreview = ObjectOverlayRenderer.renderLabelMap(labelImage, count);
        if (labelDisplayPreview == null) {
            labelDisplayPreview = labelImage;
        }
        overlayPreview = null;
        lastObjectCount = count;
        previewStale = false;
        String text = "Objects detected: " + count;
        if (countLabel != null) countLabel.setText(text);
        refreshSourceAndOutputPreview();
        setStatus(text);
        closeOldPreviewImage(old);
        closeOldPreviewImage(oldDisplay);
        closeOldPreviewImage(oldOverlay);
    }

    private void refreshSourceAndOutputPreview() {
        if (preview != null) {
            preview.setOriginal(currentSourceImage());
        }
        refreshLargePreviewModel();
        if (labelPreview == null) return;

        ImagePlus adjusted = adjustedPreviewImageForCurrentView();
        String text = objectCountText();
        if (previewStale) {
            if (preview != null) {
                preview.setAdjusted(adjusted);
                preview.setAdjustedState(PreviewPairPanel.PreviewState.STALE, text);
            }
            if (actions != null) {
                actions.markPreviewStale(text);
            }
        } else if (actions != null) {
            actions.setAdjustedPreview(adjusted, text);
        } else if (preview != null) {
            preview.setAdjusted(adjusted);
            preview.setAdjustedState(PreviewPairPanel.PreviewState.READY, text);
        }
    }

    private ImagePlus adjustedPreviewImageForCurrentView() {
        if (overlaySelected()) {
            ImagePlus source = currentSourceImage();
            if (source == null || labelPreview == null) return labelDisplayPreview;
            ImagePlus oldOverlay = overlayPreview;
            overlayPreview = ObjectOverlayRenderer.renderOverlay(source, labelPreview);
            closeOldPreviewImage(oldOverlay);
            if (overlayPreview != null) return overlayPreview;
        }
        return labelDisplayPreview == null ? labelPreview : labelDisplayPreview;
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
        refreshSourceToggleButton();
        refreshSourceAndOutputPreview();
    }

    private void refreshSourceToggleButton() {
        if (sourceToggleButton != null) {
            sourceToggleButton.setText(showRawSource ? "Show Filtered Image" : "Show Raw Image");
        }
    }

    private boolean overlaySelected() {
        return overlayCheck != null && overlayCheck.isSelected();
    }

    private String objectCountText() {
        return lastObjectCount >= 0
                ? "Objects detected: " + lastObjectCount
                : "Objects detected: not previewed";
    }

    private ImagePlus emptyLabelMapLike(ImagePlus source) {
        if (source == null || source.getStack() == null) return null;
        ImagePlus empty = source.duplicate();
        ImageStack stack = empty.getStack();
        for (int i = 1; i <= stack.size(); i++) {
            ImageProcessor processor = stack.getProcessor(i);
            if (processor != null) {
                processor.setValue(0.0);
                processor.fill();
            }
        }
        return empty;
    }

    private SizeToken collectSizeToken() {
        int min = ObjectsCounter3DWrapper.parseMinSizeVoxels(
                minField == null ? null : minField.getText(), 100);
        min = Math.max(0, min);
        String max = normalizeMaxText(maxField == null ? null : maxField.getText());
        return new SizeToken(String.valueOf(min), max);
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
        if (previewButton != null) previewButton.setEnabled(enabled);
        if (resetButton != null) resetButton.setEnabled(enabled);
        if (minField != null) minField.setEnabled(enabled);
        if (maxField != null) maxField.setEnabled(enabled);
        if (sourceToggleButton != null) sourceToggleButton.setEnabled(enabled);
        if (overlayCheck != null) overlayCheck.setEnabled(enabled);
    }

    private void refreshThresholdLabel() {
        if (thresholdLabel != null) {
            thresholdLabel.setText(thresholdValue == null
                    ? "Threshold used: not resolved"
                    : "Threshold used: " + thresholdValue);
        }
    }

    private void closePreviewWorker() {
        if (previewWorker != null && !previewWorker.isDone()) {
            previewWorker.cancel(true);
        }
        previewWorker = null;
    }

    private void closeImages() {
        ImagePlus raw = rawSource;
        ImagePlus filtered = filteredSource;
        ImagePlus label = labelPreview;
        ImagePlus display = labelDisplayPreview;
        ImagePlus overlay = overlayPreview;
        rawSource = null;
        filteredSource = null;
        labelPreview = null;
        labelDisplayPreview = null;
        overlayPreview = null;
        lastObjectCount = -1;
        Set<ImagePlus> closed = Collections.newSetFromMap(new IdentityHashMap<ImagePlus, Boolean>());
        closeUnique(overlay, closed);
        closeUnique(display, closed);
        closeUnique(label, closed);
        closeUnique(filtered, closed);
        closeUnique(raw, closed);
    }

    private void closeOldPreviewImage(ImagePlus image) {
        if (image == null
                || image == rawSource
                || image == filteredSource
                || image == labelPreview
                || image == labelDisplayPreview
                || image == overlayPreview) {
            return;
        }
        previewAdapter.close(image);
    }

    private void closeUnique(ImagePlus image, Set<ImagePlus> closed) {
        if (image == null || closed.contains(image)) return;
        closed.add(image);
        previewAdapter.close(image);
    }

    static SizeToken parseSizeToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return new SizeToken("100", "Infinity");
        }
        String[] parts = token.trim().split("-", 2);
        if (parts.length != 2) {
            return new SizeToken("100", "Infinity");
        }
        String min = "100";
        try {
            min = String.valueOf(Math.max(0,
                    ObjectsCounter3DWrapper.parseMinSizeVoxels(parts[0], 100)));
        } catch (RuntimeException ignored) {
            min = "100";
        }
        String max;
        try {
            max = normalizeMaxText(parts[1]);
        } catch (RuntimeException ignored) {
            max = "Infinity";
        }
        return new SizeToken(min, max);
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

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
