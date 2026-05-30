package flash.pipeline.ui.config;

import flash.pipeline.help.SetupHelpCatalog;
import flash.pipeline.help.SetupHelpTopic;
import flash.pipeline.objects.ObjectsCounter3DWrapper;
import flash.pipeline.ui.Debouncer;
import flash.pipeline.ui.FlashTheme;
import flash.pipeline.ui.ToggleSwitch;
import flash.pipeline.ui.preview.ObjectSizeFilterPreview;
import flash.pipeline.ui.preview.PreviewPairPanel;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ImageProcessor;

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
import java.awt.Cursor;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
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

    private static final String STALE_TEXT = "Preview is out of date. Press Run Preview.";
    private static final String EMPTY_TEXT = "Filtered input is ready. Press Run Preview.";

    private final SizeStore sizeStore;
    private final PreviewAdapter previewAdapter;

    private ConfigQcActions actions;
    private PreviewPairPanel preview;
    private ConfigQcContext activeContext;
    private SizeToken savedSize = new SizeToken("100", "Infinity");
    private SizeToken restartSize;
    private ImagePlus rawSource;
    private ImagePlus filteredSource;
    private ImagePlus labelPreview;
    private ImagePlus previousLabelPreview;
    private String previousPreviewText = "";
    private SizeToken previousSettingsSize;
    private SizeToken displayedSize;
    private ResultsTable objectStats;
    private SwingWorker<ObjectsCounter3DWrapper.Result, Void> previewWorker;
    private boolean previewStale = true;
    private boolean updatingFields;
    private Integer thresholdValue;
    private int lastObjectCount = -1;

    private JTextField minField;
    private JTextField maxField;
    private JButton previewButton;
    private JButton resetButton;
    private ToggleSwitch showRemovedObjectsSwitch;
    private JLabel thresholdLabel;
    private ObjectSizeCutoffPanel sizeCutoffPanel;
    private ObjectSizeFilterPreview.Summary sizeSummary;
    private boolean showRawSource;
    private Debouncer sizeDebouncer;

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
    public SetupHelpTopic helpTopic() {
        return SetupHelpCatalog.CLASSICAL_OBJECT_SEGMENTATION;
    }

    @Override
    public boolean controlsCanExpand() {
        return true;
    }

    @Override
    public JComponent buildControls(ConfigQcContext context, ConfigQcActions actions) {
        this.actions = actions;
        this.activeContext = context;
        this.savedSize = restartSize == null ? parseSizeToken(sizeStore.get()) : restartSize;

        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(FlashTheme.pad(2, 0, 0, 0));
        if (sizeDebouncer != null) {
            sizeDebouncer.cancel();
        }
        sizeDebouncer = new Debouncer(250, new Runnable() {
            @Override public void run() {
                fieldChanged();
            }
        });
        panel.add(buildSizeRow());
        panel.add(Box.createVerticalStrut(4));
        sizeCutoffPanel = new ObjectSizeCutoffPanel();
        panel.add(sizeCutoffPanel);
        panel.add(Box.createVerticalStrut(4));
        panel.add(buildActionRow());
        loadFields(savedSize);
        refreshSizeCutoffPanelOnly();
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
            preview.setShowRemovedObjects(showRemovedObjectsSwitch != null
                    && showRemovedObjectsSwitch.isSelected());
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
                throw new IllegalStateException("No raw particle-size input image is available.");
            }
            filteredSource = previewAdapter.createFilteredSource(context);
            if (filteredSource == null) {
                throw new IllegalStateException("No filtered particle-size input image is available.");
            }
            thresholdValue = Integer.valueOf(previewAdapter.resolveThreshold(filteredSource, context));
            refreshThresholdLabel();
            refreshSizeCutoffPanelOnly();
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
            validateSizeToken(token, filteredSource);
            sizeStore.set(token.toToken());
            savedSize = token;
            restartSize = null;
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
        try {
            restartSize = collectSizeToken();
        } catch (RuntimeException ignored) {
            // Keep the prior restart value if the current fields are invalid.
        }
        setStatus("Restarting particle-size review from the first image.");
    }

    @Override
    public void onLeave(ConfigQcContext context) {
        closePreviewWorker();
        if (sizeDebouncer != null) {
            sizeDebouncer.cancel();
        }
        if (preview != null) {
            preview.setSourceModeChangeListener(null);
            preview.setDisplaySettingsChangeListener(null);
            preview.setObjectSizeGuide(null);
            preview.clearComparisonPreview();
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
        flushSizeDebounceForTest();
    }

    void setMaxSizeForTest(String value) {
        if (maxField != null) maxField.setText(value);
        flushSizeDebounceForTest();
    }

    void flushSizeDebounceForTest() {
        if (sizeDebouncer != null) sizeDebouncer.flushNow();
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

    private JComponent buildSizeRow() {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(JComponent.LEFT_ALIGNMENT);

        JLabel heading = new JLabel("Particle sizes (voxels)");
        Font font = heading.getFont();
        if (font != null) heading.setFont(font.deriveFont(Font.BOLD));
        row.add(heading);
        row.add(Box.createHorizontalStrut(16));
        row.add(new JLabel("Min"));
        row.add(Box.createHorizontalStrut(4));
        minField = new JTextField(6);
        installFieldListener(minField);
        row.add(minField);
        row.add(Box.createHorizontalStrut(12));
        row.add(new JLabel("Max"));
        row.add(Box.createHorizontalStrut(4));
        maxField = new JTextField(8);
        installFieldListener(maxField);
        row.add(maxField);
        row.add(Box.createHorizontalGlue());
        thresholdLabel = new JLabel("Threshold used: not resolved");
        thresholdLabel.setForeground(FlashTheme.TEXT_HELP);
        row.add(thresholdLabel);
        return row;
    }

    private JComponent buildActionRow() {
        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        buttons.add(Box.createHorizontalGlue());
        previewButton = new JButton("Run Preview");
        flash.pipeline.ui.FlashIcons.apply(previewButton, flash.pipeline.ui.FlashIcons.play());
        previewButton.addActionListener(e -> runPreviewOnWorker());
        buttons.add(previewButton);
        buttons.add(Box.createHorizontalStrut(8));

        showRemovedObjectsSwitch = new ToggleSwitch(false);
        showRemovedObjectsSwitch.addChangeListener(new Runnable() {
            @Override public void run() {
                if (preview != null) {
                    preview.setShowRemovedObjects(showRemovedObjectsSwitch.isSelected());
                }
            }
        });
        buttons.add(showRemovedObjectsSwitch);
        buttons.add(Box.createHorizontalStrut(4));
        JLabel showRemovedLabel = new JLabel("Show removed objects");
        showRemovedLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        showRemovedLabel.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (showRemovedObjectsSwitch != null && showRemovedObjectsSwitch.isEnabled()) {
                    showRemovedObjectsSwitch.setSelected(!showRemovedObjectsSwitch.isSelected());
                }
            }
        });
        buttons.add(showRemovedLabel);
        buttons.add(Box.createHorizontalStrut(8));

        resetButton = new JButton("Reset to saved");
        flash.pipeline.ui.FlashIcons.apply(resetButton, flash.pipeline.ui.FlashIcons.refresh());
        resetButton.addActionListener(e -> resetToSaved());
        buttons.add(resetButton);
        return buttons;
    }

    private void installFieldListener(JTextField field) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) {
                scheduleSizeFilterRefresh();
            }

            @Override public void removeUpdate(DocumentEvent e) {
                scheduleSizeFilterRefresh();
            }

            @Override public void changedUpdate(DocumentEvent e) {
                scheduleSizeFilterRefresh();
            }
        });
    }

    private void scheduleSizeFilterRefresh() {
        if (updatingFields) return;
        if (sizeDebouncer != null) {
            sizeDebouncer.trigger();
        } else {
            fieldChanged();
        }
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
        if (!sizeFieldsReadyForLivePreview()) {
            markPreviewStale(STALE_TEXT);
            return;
        }
        if (!refreshSizeFilterPreview()) {
            markPreviewStale(STALE_TEXT);
        }
    }

    private void resetToSaved() {
        loadFields(savedSize);
        if (!refreshSizeFilterPreview()) {
            markPreviewStale(STALE_TEXT);
        }
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
        final ImagePlus previewSource = filteredSource;
        try {
            token = collectSizeToken();
            validateSizeToken(token, filteredSource);
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
                return previewAdapter.runPreview(previewSource, thresholdValue.intValue(), minSize, maxSize);
            }

            @Override protected void done() {
                if (isCancelled()) return;
                try {
                    installObjectPreview(get(), token);
                } catch (Exception e) {
                    setError("Object preview failed: " + e.getMessage());
                } finally {
                    if (!isCancelled()) setButtonsEnabled(true);
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
        validateSizeToken(token, filteredSource);
        int minSize = ObjectsCounter3DWrapper.parseMinSizeVoxels(token.minText, 100);
        int maxSize = ObjectsCounter3DWrapper.parseMaxSizeVoxels(token.maxText, filteredSource);
        setPreviewState(PreviewPairPanel.PreviewState.RUNNING, "Running object preview...");
        installObjectPreview(previewAdapter.runPreview(
                filteredSource, thresholdValue.intValue(), minSize, maxSize), token);
    }

    private void installObjectPreview(ObjectsCounter3DWrapper.Result result, SizeToken runSize) {
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
        captureCurrentPreviewForComparison();
        ImagePlus old = labelPreview;
        labelPreview = labelImage;
        objectStats = result == null ? null : result.getStatistics();
        lastObjectCount = count;
        previewStale = false;
        refreshSizeFilterPreview();
        displayedSize = normalizedSizeToken(runSize);
        String text = objectCountText();
        setStatus(text);
        if (actions != null) actions.setPreviewButtonStale(false);
        closeOldPreviewImage(old);
    }

    private void refreshSourceAndOutputPreview() {
        if (preview != null) {
            preview.setOriginal(currentSourceImage());
        }
        refreshLargePreviewModel();
        if (labelPreview == null) return;

        ImagePlus adjusted = labelPreview;
        String text = objectCountText();
        if (previewStale) {
            if (preview != null) {
                preview.setAdjusted(adjusted);
                preview.setAdjustedState(PreviewPairPanel.PreviewState.STALE, text);
            }
            if (actions != null) {
                actions.markPreviewStale(text);
                actions.setPreviewButtonStale(true);
            }
        } else {
            if (preview != null) {
                preview.setAdjusted(adjusted);
                preview.setAdjustedState(PreviewPairPanel.PreviewState.READY, text);
            }
            if (actions != null) {
                actions.setAdjustedPreview(adjusted, text);
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
        ImagePlus snapshot = preview == null
                ? PreviewPairPanel.duplicateForComparison(labelPreview, "Previous object preview")
                : preview.duplicateCurrentObjectPreviewForComparison("Previous object preview");
        if (snapshot == null) return;
        ImagePlus old = previousLabelPreview;
        previousLabelPreview = snapshot;
        previousPreviewText = objectCountText();
        previousSettingsSize = normalizedSizeToken(displayedSize);
        if (preview != null) {
            preview.setPreviousComparisonPreview(previousLabelPreview, previousPreviewText);
            updateComparisonRestoreAction();
        }
        closeOldPreviewImage(old);
    }

    private void updateComparisonRestoreAction() {
        if (preview == null) return;
        preview.setComparisonRestoreAction(previousSettingsSize == null
                ? null
                : new Runnable() {
                    @Override public void run() {
                        restorePreviousComparisonSettings();
                    }
                });
    }

    private void restorePreviousComparisonSettings() {
        if (previousSettingsSize == null) {
            setStatus("No previous particle-size settings are available.");
            return;
        }
        loadFields(previousSettingsSize);
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

    static void validateSizeToken(SizeToken token, ImagePlus source) {
        SizeToken safe = token == null ? new SizeToken("100", "Infinity") : token;
        int minSize = ObjectsCounter3DWrapper.parseMinSizeVoxels(safe.minText, 100);
        int maxSize = ObjectsCounter3DWrapper.parseMaxSizeVoxels(safe.maxText, source);
        if (isFiniteMaxToken(safe.maxText) && maxSize <= minSize) {
            throw new IllegalArgumentException(
                    "Maximum object size must be greater than minimum object size.");
        }
    }

    private boolean refreshSizeFilterPreview() {
        if (labelPreview == null || objectStats == null) {
            refreshSizeCutoffPanelOnly();
            return false;
        }
        try {
            SizeToken token = collectSizeToken();
            validateSizeToken(token, filteredSource);
            int minSize = ObjectsCounter3DWrapper.parseMinSizeVoxels(token.minText, 100);
            int maxSize = ObjectsCounter3DWrapper.parseMaxSizeVoxels(token.maxText, filteredSource);
            boolean maxFinite = isFiniteMaxToken(token.maxText);
            sizeSummary = ObjectSizeFilterPreview.summarize(
                    objectStats, filteredSource, minSize, maxSize, maxFinite);
            if (sizeCutoffPanel != null) sizeCutoffPanel.setSummary(sizeSummary);
            applySizeGuideOverlay();
            if (preview != null) {
                preview.setObjectFilterPreview(
                        labelPreview,
                        sizeSummary.removedLabels(),
                        sizeSummary,
                        lastObjectCount);
            }
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
            SizeToken token = collectSizeToken();
            validateSizeToken(token, filteredSource);
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
    }

    private void setButtonsEnabled(boolean enabled) {
        if (previewButton != null) previewButton.setEnabled(enabled);
        if (resetButton != null) resetButton.setEnabled(enabled);
        if (showRemovedObjectsSwitch != null) showRemovedObjectsSwitch.setEnabled(enabled);
        if (minField != null) minField.setEnabled(enabled);
        if (maxField != null) maxField.setEnabled(enabled);
        if (preview != null) {
            preview.setSourceModeEnabled(enabled);
            preview.setObjectOverlayEnabled(enabled);
        }
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
        ImagePlus previous = previousLabelPreview;
        rawSource = null;
        filteredSource = null;
        labelPreview = null;
        previousLabelPreview = null;
        previousPreviewText = "";
        previousSettingsSize = null;
        displayedSize = null;
        objectStats = null;
        sizeSummary = null;
        lastObjectCount = -1;
        Set<ImagePlus> closed = Collections.newSetFromMap(new IdentityHashMap<ImagePlus, Boolean>());
        closeUnique(previous, closed);
        closeUnique(label, closed);
        closeUnique(filtered, closed);
        closeUnique(raw, closed);
    }

    private void closeOldPreviewImage(ImagePlus image) {
        if (image == null
                || image == rawSource
                || image == filteredSource
                || image == labelPreview) {
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

    private static SizeToken normalizedSizeToken(SizeToken token) {
        return token == null ? null : parseSizeToken(token.toToken());
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

    private boolean sizeFieldsReadyForLivePreview() {
        return hasText(minField) && hasText(maxField);
    }

    private static boolean hasText(JTextField field) {
        return field != null && field.getText() != null && !field.getText().trim().isEmpty();
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
