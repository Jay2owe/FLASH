package flash.pipeline.ui.config;

import flash.pipeline.help.SetupHelpCatalog;
import flash.pipeline.help.SetupHelpTopic;
import flash.pipeline.objects.ObjectsCounter3DWrapper;
import flash.pipeline.ui.FlashTheme;
import flash.pipeline.ui.preview.ObjectSizeFilterPreview;
import flash.pipeline.ui.preview.PreviewPairPanel;
import flash.pipeline.ui.preview.ThresholdControlPanel;
import flash.pipeline.ui.preview.ThresholdOverlayRenderer;
import flash.pipeline.ui.variations.MontageDisplayActionDelegate;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterId;
import flash.pipeline.ui.variations.VariationEngineContext;
import flash.pipeline.ui.variations.VariationsDialog;
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
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public final class ClassicalSegmentationStage implements ConfigQcStage {

    public interface ThresholdStore {
        String get();
        void set(String token);
    }

    public interface SizeStore {
        String get();
        void set(String token);
    }

    public interface PreviewAdapter {
        ImagePlus createRawSource(ConfigQcContext context) throws Exception;
        ImagePlus createFilteredSource(ConfigQcContext context) throws Exception;
        ObjectsCounter3DWrapper.Result runPreview(ImagePlus filteredSource,
                                                  int threshold,
                                                  int minSize,
                                                  int maxSize) throws Exception;
        int countObjects(ObjectsCounter3DWrapper.Result result);
        void close(ImagePlus image);
    }

    private static final String EMPTY_TEXT = "Threshold preview is ready. Press Run Object Preview.";
    private static final String STALE_TEXT = "Object preview is out of date. Press Run Object Preview.";

    private final ThresholdStore thresholdStore;
    private final SizeStore sizeStore;
    private final PreviewAdapter previewAdapter;

    private ConfigQcActions actions;
    private PreviewPairPanel preview;
    private ConfigQcContext activeContext;
    private ThresholdControlPanel thresholdControl;
    private ParticleSizeStage.SizeToken savedSize = new ParticleSizeStage.SizeToken("100", "Infinity");
    private ParticleSizeStage.SizeToken restartSize;
    private ImagePlus rawSource;
    private ImagePlus filteredSource;
    private ImagePlus thresholdPreview;
    private ImagePlus labelPreview;
    private ImagePlus previousLabelPreview;
    private String previousPreviewText = "";
    private ParticleSizeStage.SizeToken previousSettingsSize;
    private String previousSettingsThresholdToken;
    private ParticleSizeStage.SizeToken displayedSize;
    private String displayedThresholdToken;
    private ResultsTable objectStats;
    private SwingWorker<ObjectsCounter3DWrapper.Result, Void> previewWorker;
    private Double restartLowerThreshold;
    private Double restartUpperThreshold;
    private boolean objectPreviewStale = true;
    private boolean updatingFields;
    private int lastObjectCount = -1;
    private int previewedMinSize = -1;
    private int previewedMaxSize = -1;

    private JTextField minField;
    private JTextField maxField;
    private JButton previewButton;
    private JButton resetButton;
    private JButton variationsButton;
    private JLabel feedbackLabel;
    private ObjectSizeCutoffPanel sizeCutoffPanel;
    private ObjectSizeFilterPreview.Summary sizeSummary;

    public ClassicalSegmentationStage(ThresholdStore thresholdStore,
                                      SizeStore sizeStore,
                                      PreviewAdapter previewAdapter) {
        if (thresholdStore == null) {
            throw new IllegalArgumentException("thresholdStore must not be null");
        }
        if (sizeStore == null) {
            throw new IllegalArgumentException("sizeStore must not be null");
        }
        if (previewAdapter == null) {
            throw new IllegalArgumentException("previewAdapter must not be null");
        }
        this.thresholdStore = thresholdStore;
        this.sizeStore = sizeStore;
        this.previewAdapter = previewAdapter;
    }

    @Override
    public String title() {
        return "Classical Segmentation";
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
        this.savedSize = restartSize == null
                ? ParticleSizeStage.parseSizeToken(sizeStore.get())
                : restartSize;

        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(FlashTheme.pad(2, 0, 0, 0));

        thresholdControl = new ThresholdControlPanel();
        thresholdControl.setMethod("Default");
        thresholdControl.setBackgroundMode("Dark");
        thresholdControl.setPreviewMode(ThresholdOverlayRenderer.MODE_RED_OVERLAY);
        thresholdControl.setPreviewSelectorVisible(false);
        thresholdControl.setSetButtonVisible(false);
        thresholdControl.setListener(new ThresholdControlPanel.Listener() {
            @Override public void thresholdChanged(double lower, double upper, boolean adjusting) {
                updateThresholdPreview(true);
            }

            @Override public void autoRequested(String method, String background) {
                updateThresholdPreview(true);
            }

            @Override public void resetRequested() {
                updateThresholdPreview(true);
            }

            @Override public void setRequested() {
            }
        });
        panel.add(thresholdControl);
        panel.add(Box.createVerticalStrut(4));
        panel.add(buildObjectRow());
        panel.add(Box.createVerticalStrut(4));
        sizeCutoffPanel = new ObjectSizeCutoffPanel();
        panel.add(sizeCutoffPanel);
        panel.add(Box.createVerticalStrut(4));

        feedbackLabel = new JLabel(" ");
        feedbackLabel.setForeground(FlashTheme.TEXT_HELP);
        feedbackLabel.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        panel.add(feedbackLabel);

        loadSizeFields(savedSize);
        refreshSizeCutoffPanelOnly();
        markObjectPreviewStale(EMPTY_TEXT);
        return panel;
    }

    @Override
    public void onEnter(ConfigQcContext context, PreviewPairPanel preview) {
        closePreviewWorker();
        closeImages();
        this.activeContext = context;
        this.preview = preview;
        if (preview != null) {
            preview.clearLargePreviewImages();
            preview.setSourceModeChangeListener(null);
            preview.setOriginalPreviewTitle("Threshold preview");
            preview.setAdjustedPreviewTitle("Object preview");
            preview.setSourceToggleVisible(false);
            preview.setSourceMode(PreviewPairPanel.SourceMode.FILTERED);
            preview.setSourceModeEnabled(true);
            preview.setObjectOverlaySelected(false);
            preview.setObjectOverlayEnabled(true);
            preview.setComparisonPreviewVisible(true);
            preview.setComparisonRestoreAction(null);
        }
        if (actions != null) {
            actions.registerPreviewButton(previewButton);
        }
        try {
            rawSource = previewAdapter.createRawSource(context);
            if (rawSource == null) {
                throw new IllegalStateException("No raw Classical input image is available.");
            }
            filteredSource = previewAdapter.createFilteredSource(context);
            if (filteredSource == null) {
                throw new IllegalStateException("No filtered Classical input image is available.");
            }
            if (preview != null) {
                preview.setLargePreviewSourceChoices(rawSource, filteredSource);
            }
            if (thresholdControl != null) {
                thresholdControl.setImage(filteredSource);
                applySavedOrAutoThreshold();
            }
            if (preview != null) {
                preview.setAdjusted(null);
                preview.setAdjustedState(PreviewPairPanel.PreviewState.STALE, EMPTY_TEXT);
            }
            updateThresholdPreview(false);
            refreshSizeCutoffPanelOnly();
            markObjectPreviewStale(EMPTY_TEXT);
            setVariationsButtonReady(true);
        } catch (Exception e) {
            closeImages();
            setVariationsButtonReady(false);
            setError("Could not prepare Classical segmentation preview: " + e.getMessage());
        }
    }

    @Override
    public boolean lockIn(ConfigQcContext context) {
        if (thresholdControl == null || filteredSource == null) {
            setError("No threshold preview is available.");
            return false;
        }
        try {
            ParticleSizeStage.SizeToken size = collectSizeToken();
            validateSizeToken(size);
            String threshold = currentThresholdToken();
            thresholdStore.set(threshold);
            sizeStore.set(size.toToken());
            savedSize = size;
            restartLowerThreshold = null;
            restartUpperThreshold = null;
            restartSize = null;
            setStatus("Locked Classical segmentation: threshold " + threshold
                    + ", sizes " + size.toToken() + ".");
            return true;
        } catch (RuntimeException e) {
            setError("Enter valid min and max voxel sizes.");
            return false;
        }
    }

    @Override
    public void skipCurrentImage(ConfigQcContext context) {
        setStatus("Skipped this image; saved Classical segmentation settings are unchanged.");
    }

    @Override
    public void restartStage(ConfigQcContext context) {
        if (thresholdControl != null) {
            restartLowerThreshold = Double.valueOf(thresholdControl.getLowerThreshold());
            restartUpperThreshold = Double.valueOf(thresholdControl.getUpperThreshold());
        }
        try {
            restartSize = collectSizeToken();
        } catch (RuntimeException ignored) {
            // Keep the prior restart value if the current fields are invalid.
        }
        setStatus("Restarting Classical segmentation review from the first image.");
    }

    @Override
    public void onLeave(ConfigQcContext context) {
        closePreviewWorker();
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

    boolean isObjectPreviewStaleForTest() {
        return objectPreviewStale;
    }

    String currentThresholdTokenForTest() {
        return currentThresholdToken();
    }

    String currentSizeTokenForTest() {
        return collectSizeToken().toToken();
    }

    String currentNormalLeftPreviewTitleForTest() {
        return thresholdPreview == null ? null : thresholdPreview.getTitle();
    }

    ImagePlus thresholdPreviewForTest() {
        return thresholdPreview;
    }

    int largePreviewPaneCountForTest() {
        return labelPreview == null ? 2 : 3;
    }

    void setThresholdForTest(double lower, double upper) {
        if (thresholdControl != null) {
            thresholdControl.setThreshold(lower, upper);
            updateThresholdPreview(true);
        }
    }

    void setMinSizeForTest(String value) {
        setTextForTest(minField, value);
    }

    void setMaxSizeForTest(String value) {
        setTextForTest(maxField, value);
    }

    void runPreviewNowForTest() throws Exception {
        runPreviewNow();
    }

    void restorePreviousComparisonSettingsForTest() {
        restorePreviousComparisonSettings(false);
    }

    void applyVariationComboForTest(ParameterCombo combo) {
        applyVariationCombo(combo);
    }

    String sizeCutoffSummaryForTest() {
        return sizeCutoffPanel == null ? "" : sizeCutoffPanel.summaryTextForTest();
    }

    private JComponent buildObjectRow() {
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        row.setBorder(BorderFactory.createTitledBorder("Objects"));

        minField = new JTextField(6);
        maxField = new JTextField(8);
        installFieldListener(minField);
        installFieldListener(maxField);

        previewButton = new JButton("Run Object Preview");
        flash.pipeline.ui.FlashIcons.apply(previewButton, flash.pipeline.ui.FlashIcons.play());
        previewButton.addActionListener(e -> runPreviewOnWorker());
        resetButton = new JButton("Reset sizes");
        flash.pipeline.ui.FlashIcons.apply(resetButton, flash.pipeline.ui.FlashIcons.refresh());
        resetButton.addActionListener(e -> resetSizesToSaved());

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.gridx = 0;
        gbc.insets = new Insets(0, 0, 0, 6);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0.0;

        JLabel heading = new JLabel("Particle sizes (voxels)");
        Font font = heading.getFont();
        if (font != null) heading.setFont(font.deriveFont(Font.BOLD));
        row.add(heading, gbc);
        gbc.gridx++;
        row.add(new JLabel("Min"), gbc);
        gbc.gridx++;
        row.add(minField, gbc);
        gbc.gridx++;
        row.add(new JLabel("Max"), gbc);
        gbc.gridx++;
        row.add(maxField, gbc);
        gbc.gridx++;
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

    private void installFieldListener(JTextField field) {
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

    private void loadSizeFields(ParticleSizeStage.SizeToken token) {
        updatingFields = true;
        try {
            ParticleSizeStage.SizeToken safe = token == null
                    ? new ParticleSizeStage.SizeToken("100", "Infinity")
                    : token;
            if (minField != null) minField.setText(safe.minText);
            if (maxField != null) maxField.setText(safe.maxText);
        } finally {
            updatingFields = false;
        }
    }

    private void sizeFieldChanged() {
        if (updatingFields) return;
        captureCurrentPreviewForComparison();
        if (!refreshSizeFilterPreview()) {
            markObjectPreviewStale(STALE_TEXT);
        }
    }

    private void resetSizesToSaved() {
        loadSizeFields(savedSize);
        captureCurrentPreviewForComparison();
        if (!refreshSizeFilterPreview()) {
            markObjectPreviewStale(STALE_TEXT);
        }
    }

    private void applySavedOrAutoThreshold() {
        if (thresholdControl == null || filteredSource == null) return;
        double upper = imageMaximum(filteredSource);
        if (restartLowerThreshold != null
                && restartUpperThreshold != null
                && Double.isFinite(restartLowerThreshold.doubleValue())
                && Double.isFinite(restartUpperThreshold.doubleValue())) {
            thresholdControl.setThreshold(restartLowerThreshold.doubleValue(),
                    restartUpperThreshold.doubleValue());
            return;
        }
        String token = ChannelThresholdStage.normalizeThresholdToken(thresholdStore.get());
        if (ChannelThresholdStage.isNumericThresholdToken(token)) {
            try {
                thresholdControl.setThreshold(Double.parseDouble(token), upper);
                return;
            } catch (NumberFormatException ignored) {
                // Fall through to automatic suggestion.
            }
        }
        double auto = ChannelThresholdStage.defaultDarkThreshold(filteredSource);
        if (Double.isFinite(auto)) {
            thresholdControl.setThreshold(auto, upper);
        }
    }

    private void updateThresholdPreview(boolean markStale) {
        if (filteredSource == null || thresholdControl == null) return;
        ImagePlus next = ThresholdOverlayRenderer.render(
                filteredSource,
                thresholdControl.getLowerThreshold(),
                thresholdControl.getUpperThreshold(),
                ThresholdOverlayRenderer.MODE_RED_OVERLAY);
        if (next == null) return;
        next.setTitle("Threshold preview");
        ImagePlus old = thresholdPreview;
        thresholdPreview = next;
        if (preview != null) {
            preview.setOriginal(thresholdPreview);
        }
        refreshLargePreviewModel();
        if (old != null && old != thresholdPreview) {
            previewAdapter.close(old);
        }
        if (markStale) {
            markObjectPreviewStale(thresholdStaleText());
        } else {
            setStatus(thresholdStaleText());
        }
    }

    private void runPreviewOnWorker() {
        if (previewWorker != null && !previewWorker.isDone()) return;
        if (filteredSource == null || thresholdControl == null) {
            setError("No Classical segmentation input image is available.");
            return;
        }
        final int threshold;
        final int previewMinSize;
        final int previewMaxSize;
        final ParticleSizeStage.SizeToken token;
        final ImagePlus previewSource = filteredSource;
        try {
            token = collectSizeToken();
            threshold = currentThresholdValue();
            validateSizeToken(token);
            previewMinSize = minSizeVoxels(token);
            previewMaxSize = maxSizeVoxels(token);
        } catch (RuntimeException e) {
            setError("Enter valid min and max voxel sizes.");
            return;
        }
        setPreviewState(PreviewPairPanel.PreviewState.RUNNING, "Running object preview...");
        setButtonsEnabled(false);
        if (actions != null) actions.setPreviewButtonRunning(true);
        previewWorker = new SwingWorker<ObjectsCounter3DWrapper.Result, Void>() {
            @Override protected ObjectsCounter3DWrapper.Result doInBackground() throws Exception {
                return previewAdapter.runPreview(previewSource, threshold, previewMinSize, previewMaxSize);
            }

            @Override protected void done() {
                if (isCancelled()) return;
                try {
                    installObjectPreview(get(), token, previewMinSize, previewMaxSize, threshold);
                } catch (Exception e) {
                    setError("Object preview failed: " + e.getMessage());
                } finally {
                    if (!isCancelled()) {
                        setButtonsEnabled(true);
                        if (actions != null) actions.setPreviewButtonRunning(false);
                    }
                }
            }
        };
        previewWorker.execute();
    }

    private void runPreviewNow() throws Exception {
        if (filteredSource == null || thresholdControl == null) {
            throw new IllegalStateException("No Classical segmentation input image is available.");
        }
        ParticleSizeStage.SizeToken token = collectSizeToken();
        int threshold = currentThresholdValue();
        validateSizeToken(token);
        int minSize = minSizeVoxels(token);
        int maxSize = maxSizeVoxels(token);
        setPreviewState(PreviewPairPanel.PreviewState.RUNNING, "Running object preview...");
        installObjectPreview(previewAdapter.runPreview(filteredSource, threshold,
                minSize, maxSize), token, minSize, maxSize, threshold);
    }

    private void validateSizeToken(ParticleSizeStage.SizeToken token) {
        ParticleSizeStage.validateSizeToken(token, filteredSource);
    }

    private int minSizeVoxels(ParticleSizeStage.SizeToken token) {
        return ObjectsCounter3DWrapper.parseMinSizeVoxels(
                token == null ? null : token.minText, 100);
    }

    private int maxSizeVoxels(ParticleSizeStage.SizeToken token) {
        return ObjectsCounter3DWrapper.parseMaxSizeVoxels(
                token == null ? null : token.maxText, filteredSource);
    }

    private void installObjectPreview(ObjectsCounter3DWrapper.Result result,
                                      ParticleSizeStage.SizeToken runSize,
                                      int runMinSize,
                                      int runMaxSize,
                                      int runThreshold) {
        int count = previewAdapter.countObjects(result);
        ImagePlus labelImage = result == null ? null : result.getObjectsMap();
        if (labelImage == null) {
            labelImage = emptyLabelMapLike(filteredSource);
        }
        if (labelImage == null) {
            setError("Object preview returned no label map.");
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
        previewedMinSize = Math.max(0, runMinSize);
        previewedMaxSize = Math.max(previewedMinSize, runMaxSize);
        displayedThresholdToken = String.valueOf(runThreshold);
        objectPreviewStale = false;
        lastObjectCount = count;
        refreshSizeFilterPreview();
        displayedSize = normalizedSizeToken(runSize);
        String text = objectCountText();
        refreshObjectPreview(text, PreviewPairPanel.PreviewState.READY);
        setStatus(text);
        if (actions != null) actions.setPreviewButtonStale(false);
        closeOldPreviewImage(old);
    }

    private void refreshObjectPreview(String text, PreviewPairPanel.PreviewState state) {
        refreshLargePreviewModel();
        if (labelPreview == null) return;
        if (preview != null) {
            preview.setAdjusted(labelPreview);
            preview.setAdjustedState(state, text);
        }
        if (actions != null) {
            if (state == PreviewPairPanel.PreviewState.STALE) {
                actions.markPreviewStale(text);
                actions.setPreviewButtonStale(true);
            } else if (state == PreviewPairPanel.PreviewState.READY) {
                actions.setAdjustedPreview(labelPreview, text);
                actions.setPreviewButtonStale(false);
            } else {
                actions.setStatus(text);
            }
        }
    }

    private void markObjectPreviewStale(String text) {
        objectPreviewStale = true;
        String safeText = text == null || text.trim().isEmpty() ? thresholdStaleText() : text;
        setFeedbackText(safeText);
        if (labelPreview != null) {
            refreshObjectPreview(safeText, PreviewPairPanel.PreviewState.STALE);
        } else {
            setPreviewState(PreviewPairPanel.PreviewState.STALE, safeText);
        }
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

    private void refreshLargePreviewModel() {
        if (preview == null) return;
        preview.setLargePreviewSourceChoices(rawSource, filteredSource);
        preview.setLargePreviewImages(rawSource, thresholdPreview, labelPreview);
        preview.setPreviousComparisonPreview(previousLabelPreview, previousPreviewText);
    }

    private void captureCurrentPreviewForComparison() {
        if (labelPreview == null) return;
        ImagePlus snapshot = PreviewPairPanel.duplicateForComparison(
                labelPreview, "Previous object preview");
        if (snapshot == null) return;
        ImagePlus old = previousLabelPreview;
        previousLabelPreview = snapshot;
        previousPreviewText = objectCountText();
        previousSettingsSize = normalizedSizeToken(displayedSize);
        previousSettingsThresholdToken = displayedThresholdToken;
        if (preview != null) {
            preview.setPreviousComparisonPreview(previousLabelPreview, previousPreviewText);
            updateComparisonRestoreAction();
        }
        closeOldPreviewImage(old);
    }

    private void updateComparisonRestoreAction() {
        if (preview == null) return;
        boolean available = previousSettingsSize != null
                && previousSettingsThresholdToken != null;
        preview.setComparisonRestoreAction(!available
                ? null
                : new Runnable() {
                    @Override public void run() {
                        restorePreviousComparisonSettings(true);
                    }
                });
    }

    private void restorePreviousComparisonSettings(boolean runPreview) {
        if (previousSettingsSize == null || previousSettingsThresholdToken == null) {
            setStatus("No previous Classical segmentation settings are available.");
            return;
        }
        loadSizeFields(previousSettingsSize);
        if (thresholdControl != null) {
            try {
                double lower = Double.parseDouble(previousSettingsThresholdToken);
                thresholdControl.setThreshold(lower, imageMaximum(filteredSource));
                updateThresholdPreview(true);
            } catch (NumberFormatException e) {
                setError("Could not restore the previous threshold.");
                return;
            }
        }
        if (runPreview) {
            runPreviewOnWorker();
        }
    }

    private void openVariationsDialog() {
        if (filteredSource == null || activeContext == null) {
            setStatus("Wait for the filtered input to finish preparing before opening variations.");
            return;
        }
        final ParameterCombo base;
        try {
            ParticleSizeStage.SizeToken token = collectSizeToken();
            base = ParameterCombo.builder()
                    .put(ParameterId.THRESHOLD, Integer.valueOf(currentThresholdValue()))
                    .put(ParameterId.MIN_SIZE, Integer.valueOf(ObjectsCounter3DWrapper
                            .parseMinSizeVoxels(token.minText, 100)))
                    .put(ParameterId.MAX_SIZE, Integer.valueOf(ObjectsCounter3DWrapper
                            .parseMaxSizeVoxels(token.maxText, filteredSource)))
                    .build();
        } catch (RuntimeException e) {
            setError("Enter valid min and max voxel sizes before opening variations.");
            return;
        }
        VariationEngineContext ctx = VariationEngineContext.forClassical(
                activeContext.getChannelName(),
                rawSource,
                filteredSource,
                activeContext,
                base,
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
        Number threshold = numberValue(combo, ParameterId.THRESHOLD);
        Number minSize = numberValue(combo, ParameterId.MIN_SIZE);
        Number maxSize = numberValue(combo, ParameterId.MAX_SIZE);
        if (threshold != null && thresholdControl != null) {
            thresholdControl.setThreshold(threshold.doubleValue(), imageMaximum(filteredSource));
        }
        updatingFields = true;
        try {
            if (minSize != null && minField != null) {
                minField.setText(String.valueOf(nonNegativeInt(minSize)));
            }
            if (maxSize != null && maxField != null) {
                int max = nonNegativeInt(maxSize);
                maxField.setText(max == Integer.MAX_VALUE ? "Infinity" : String.valueOf(max));
            }
        } finally {
            updatingFields = false;
        }
        runPreviewOnWorker();
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

    private ParticleSizeStage.SizeToken collectSizeToken() {
        int min = ObjectsCounter3DWrapper.parseMinSizeVoxels(
                minField == null ? null : minField.getText(), 100);
        min = Math.max(0, min);
        String max = normalizeMaxText(maxField == null ? null : maxField.getText());
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
            if (!canRelabelFromCurrentPreview(minSize, maxSize)) {
                return false;
            }
            objectPreviewStale = false;
            displayedSize = normalizedSizeToken(token);
            String text = objectCountText();
            refreshObjectPreview(text, PreviewPairPanel.PreviewState.READY);
            setStatus(text);
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

    private String objectCountText() {
        String prefix;
        if (sizeSummary != null && sizeSummary.totalCount > 0) {
            prefix = sizeSummary.statusText();
        } else if (lastObjectCount >= 0) {
            prefix = "Objects: " + lastObjectCount + " ready";
        } else {
            prefix = "Objects: not previewed";
        }
        return prefix + ". Threshold " + currentThresholdToken() + ".";
    }

    private String currentThresholdToken() {
        return thresholdControl == null
                ? ""
                : ChannelThresholdStage.formatThreshold(thresholdControl.getLowerThreshold());
    }

    private int currentThresholdValue() {
        return Integer.parseInt(currentThresholdToken());
    }

    private String thresholdStaleText() {
        return "Threshold " + currentThresholdToken() + ". Object preview is out of date.";
    }

    private void setStatus(String text) {
        setFeedbackText(text);
        if (actions != null) {
            actions.setStatus(text);
        }
    }

    private void setFeedbackText(String text) {
        if (feedbackLabel != null) {
            feedbackLabel.setText(text == null || text.trim().isEmpty() ? " " : text);
        }
    }

    private void setError(String text) {
        setPreviewState(PreviewPairPanel.PreviewState.ERROR, text);
        setStatus(text);
        if (actions != null) actions.setPreviewButtonStale(true);
    }

    private void setButtonsEnabled(boolean enabled) {
        if (previewButton != null) previewButton.setEnabled(enabled);
        if (resetButton != null) resetButton.setEnabled(enabled);
        if (variationsButton != null) variationsButton.setEnabled(enabled && filteredSource != null);
        if (minField != null) minField.setEnabled(enabled);
        if (maxField != null) maxField.setEnabled(enabled);
        if (thresholdControl != null) thresholdControl.setEnabled(enabled);
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

    private void closePreviewWorker() {
        if (previewWorker != null && !previewWorker.isDone()) {
            previewWorker.cancel(true);
        }
        previewWorker = null;
    }

    private void closeImages() {
        ImagePlus raw = rawSource;
        ImagePlus filtered = filteredSource;
        ImagePlus threshold = thresholdPreview;
        ImagePlus label = labelPreview;
        ImagePlus previous = previousLabelPreview;
        rawSource = null;
        filteredSource = null;
        thresholdPreview = null;
        labelPreview = null;
        previousLabelPreview = null;
        previousPreviewText = "";
        previousSettingsSize = null;
        previousSettingsThresholdToken = null;
        displayedSize = null;
        displayedThresholdToken = null;
        objectStats = null;
        sizeSummary = null;
        lastObjectCount = -1;
        previewedMinSize = -1;
        previewedMaxSize = -1;
        Set<ImagePlus> closed = Collections.newSetFromMap(new IdentityHashMap<ImagePlus, Boolean>());
        closeUnique(previous, closed);
        closeUnique(label, closed);
        closeUnique(threshold, closed);
        closeUnique(filtered, closed);
        closeUnique(raw, closed);
    }

    private void closeOldPreviewImage(ImagePlus image) {
        if (image == null
                || image == rawSource
                || image == filteredSource
                || image == thresholdPreview
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

    private static void setTextForTest(JTextField field, String value) {
        if (field != null) field.setText(value);
    }

    private static double imageMaximum(ImagePlus image) {
        if (image == null) return 255.0;
        ImageProcessor processor = image.getProcessor();
        if (processor == null) return 255.0;
        double max = processor.getMax();
        return Double.isFinite(max) ? max : 255.0;
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

    private static Number numberValue(ParameterCombo combo, ParameterId id) {
        Object value = combo == null ? null : combo.get(id);
        return value instanceof Number ? (Number) value : null;
    }

    private static int nonNegativeInt(Number value) {
        if (value == null) return 0;
        return Math.max(0, (int) Math.round(value.doubleValue()));
    }

    private boolean canRelabelFromCurrentPreview(int minSize, int maxSize) {
        if (previewedMinSize < 0 || previewedMaxSize < 0) return false;
        int safeMin = Math.max(0, minSize);
        int safeMax = Math.max(safeMin, maxSize);
        return safeMin >= previewedMinSize && safeMax <= previewedMaxSize;
    }

    private static ParticleSizeStage.SizeToken normalizedSizeToken(ParticleSizeStage.SizeToken token) {
        return token == null ? null : ParticleSizeStage.parseSizeToken(token.toToken());
    }
}
