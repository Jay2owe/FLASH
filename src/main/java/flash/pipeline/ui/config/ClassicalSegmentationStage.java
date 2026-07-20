package flash.pipeline.ui.config;

import flash.pipeline.help.SetupHelpCatalog;
import flash.pipeline.help.SetupHelpTopic;
import flash.pipeline.image.ThresholdOps;
import flash.pipeline.objects.ObjectsCounter3DWrapper;
import flash.pipeline.runrecord.LoadedRunParameters;
import flash.pipeline.ui.Debouncer;
import flash.pipeline.ui.FlashTheme;
import flash.pipeline.ui.ToggleSwitch;
import flash.pipeline.ui.preview.ObjectSizeFilterPreview;
import flash.pipeline.ui.preview.PreviewPairPanel;
import flash.pipeline.ui.preview.ThresholdControlPanel;
import flash.pipeline.ui.preview.ThresholdOverlayRenderer;
import flash.pipeline.ui.variations.MontageDisplayActionDelegate;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterId;
import flash.pipeline.ui.variations.VariationEngineContext;
import flash.pipeline.ui.variations.VariationsDialog;
import ij.IJ;
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
import java.awt.Cursor;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
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
    private final Set<ImagePlus> retainedPreviewCleanup = Collections.newSetFromMap(
            new IdentityHashMap<ImagePlus, Boolean>());
    private final PreviewInputLeaseRegistry previewInputLeases =
            new PreviewInputLeaseRegistry();
    private PreviewWorkerExecutor previewWorkerExecutor = PreviewWorkerExecutor.DEFAULT;
    private String previousPreviewText = "";
    private ParticleSizeStage.SizeToken previousSettingsSize;
    private String previousSettingsThresholdToken;
    private ParticleSizeStage.SizeToken displayedSize;
    private String displayedThresholdToken;
    private ResultsTable objectStats;
    private volatile SwingWorker<ObjectsCounter3DWrapper.Result, Void> previewWorker;
    private volatile Runnable previewWorkerPreStartCompletion;
    private volatile long previewEpoch;
    private volatile boolean previewSessionActive;
    private volatile Throwable previewWorkerCompletionFailure;
    private volatile boolean previewWorkerCompletionHandled;
    private volatile boolean previewWorkerFailureObservedInterrupt;
    private volatile boolean previewWorkerCompletionObservedInterrupt;
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
    private ToggleSwitch showRemovedObjectsSwitch;
    private Debouncer sizeDebouncer;
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

        if (sizeDebouncer != null) {
            sizeDebouncer.cancel();
        }
        sizeDebouncer = new Debouncer(250, new Runnable() {
            @Override public void run() {
                sizeFieldChanged();
            }
        });

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
    public boolean supportsLoadedParameters() {
        return true;
    }

    @Override
    public LoadedRunParameters.Result applyLoadedParameters(Map<String, Object> parameters) {
        int channel = activeContext == null ? 0 : activeContext.getChannelIndex();
        LoadedRunParameters.ValueLoad<String> threshold =
                LoadedRunParameters.objectThreshold(parameters, channel);
        LoadedRunParameters.ValueLoad<ParticleSizeStage.SizeToken> size =
                LoadedRunParameters.particleSize(parameters, channel);
        if (threshold.value != null && threshold.value.trim().length() > 0
                && !"default".equalsIgnoreCase(threshold.value.trim())) {
            String token = threshold.value.trim();
            thresholdStore.set(token);
            if (thresholdControl != null) {
                if (ThresholdOps.isAutoThresholdToken(token)) {
                    thresholdControl.setAutoThresholdToken(token);
                } else {
                    try {
                        double lower = Double.parseDouble(token);
                        thresholdControl.setThresholdPreservingRange(lower,
                                upperThresholdFor(lower, filteredSource));
                    } catch (NumberFormatException ignored) {
                        // Keep the stored token; invalid legacy values are skipped by the UI.
                    }
                }
            }
        }
        if (size.value != null) {
            sizeStore.set(size.value.toToken());
            loadSizeFields(size.value);
            refreshSizeCutoffPanelOnly();
        }
        markObjectPreviewStale("Loaded Classical segmentation settings. Press Run Object Preview.");
        return LoadedRunParameters.Result.merge(threshold.result, size.result);
    }

    @Override
    public void onEnter(ConfigQcContext context, PreviewPairPanel preview) {
        previewSessionActive = false;
        closePreviewWorker();
        closeImages();
        this.activeContext = context;
        this.preview = preview;
        previewSessionActive = true;
        if (preview != null) {
            preview.clearLargePreviewImages();
            preview.setSourceModeChangeListener(null);
            preview.setOriginalPreviewTitle("Threshold preview");
            preview.setAdjustedPreviewTitle("Object preview");
            preview.setSourceToggleVisible(false);
            preview.setSourceMode(PreviewPairPanel.SourceMode.FILTERED);
            preview.setSourceModeEnabled(true);
            preview.setShowRemovedObjects(showRemovedObjectsSwitch != null
                    && showRemovedObjectsSwitch.isSelected());
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
            setStatus("Locked Classical segmentation: threshold " + ThresholdOps.describeToken(threshold)
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
        closePreviewWorker();
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
        previewSessionActive = false;
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

    ImagePlus labelPreviewForTest() {
        return labelPreview;
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

    void setAlgorithmThresholdForTest(String method, String background) {
        if (thresholdControl != null) {
            thresholdControl.setAutoThresholdToken(ThresholdOps.formatAutoToken(method, background));
            updateThresholdPreview(true);
        }
    }

    void setMinSizeForTest(String value) {
        setTextForTest(minField, value);
        flushSizeDebounceForTest();
    }

    void setMaxSizeForTest(String value) {
        setTextForTest(maxField, value);
        flushSizeDebounceForTest();
    }

    void flushSizeDebounceForTest() {
        if (sizeDebouncer != null) sizeDebouncer.flushNow();
    }

    void runPreviewNowForTest() throws Exception {
        runPreviewNow();
    }

    void runPreviewOnWorkerForTest() {
        runPreviewOnWorker();
    }

    void setPreviewWorkerExecutorForTest(PreviewWorkerExecutor executor) {
        previewWorkerExecutor = executor == null ? PreviewWorkerExecutor.DEFAULT : executor;
    }

    boolean previewWorkerActiveForTest() {
        return previewWorker != null;
    }

    Throwable previewWorkerCompletionFailureForTest() {
        return previewWorkerCompletionFailure;
    }

    boolean previewWorkerCompletionHandledForTest() {
        return previewWorkerCompletionHandled;
    }

    boolean previewWorkerFailureObservedInterruptForTest() {
        return previewWorkerFailureObservedInterrupt;
    }

    boolean previewWorkerCompletionObservedInterruptForTest() {
        return previewWorkerCompletionObservedInterrupt;
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
        showRemovedObjectsSwitch = new ToggleSwitch(false);
        showRemovedObjectsSwitch.addChangeListener(new Runnable() {
            @Override public void run() {
                if (preview != null) {
                    preview.setShowRemovedObjects(showRemovedObjectsSwitch.isSelected());
                }
            }
        });
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
        row.add(showRemovedObjectsSwitch, gbc);
        JLabel showRemovedLabel = new JLabel("Show removed objects");
        showRemovedLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        showRemovedLabel.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (showRemovedObjectsSwitch != null && showRemovedObjectsSwitch.isEnabled()) {
                    showRemovedObjectsSwitch.setSelected(!showRemovedObjectsSwitch.isSelected());
                }
            }
        });
        gbc.gridx++;
        row.add(showRemovedLabel, gbc);
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
            sizeFieldChanged();
        }
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
        if (!refreshSizeFilterPreview()) {
            markObjectPreviewStale(STALE_TEXT);
        }
    }

    private void resetSizesToSaved() {
        loadSizeFields(savedSize);
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
            thresholdControl.setThresholdPreservingRange(restartLowerThreshold.doubleValue(),
                    Math.max(imageMaximum(filteredSource),
                            Math.max(restartLowerThreshold.doubleValue(),
                                    restartUpperThreshold.doubleValue())));
            return;
        }
        String token = ChannelThresholdStage.normalizeThresholdToken(thresholdStore.get());
        if (ThresholdOps.isAutoThresholdToken(token)) {
            thresholdControl.setAutoThresholdToken(token);
            return;
        }
        if (ChannelThresholdStage.isNumericThresholdToken(token)) {
            try {
                double lower = Double.parseDouble(token);
                thresholdControl.setThresholdPreservingRange(lower, upperThresholdFor(lower, filteredSource));
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
        closeOldPreviewImage(old);
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
        final ConfigQcContext previewContext = activeContext;
        final Set<ImagePlus> borrowedPreviewImages = borrowedPreviewImagesSnapshot();
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
        final PreviewInputLeaseRegistry.Lease inputLease =
                previewInputLeases.acquire(previewSource);
        final long requestEpoch = ++previewEpoch;
        final PreviewWorkerHandoff<ObjectsCounter3DWrapper.Result> handoff =
                new PreviewWorkerHandoff<ObjectsCounter3DWrapper.Result>();
        previewWorkerCompletionFailure = null;
        previewWorkerCompletionHandled = false;
        previewWorkerFailureObservedInterrupt = false;
        previewWorkerCompletionObservedInterrupt = false;
        previewWorker = new SwingWorker<ObjectsCounter3DWrapper.Result, Void>() {
            @Override protected ObjectsCounter3DWrapper.Result doInBackground() throws Exception {
                if (!handoff.tryStart()) return null;
                try {
                    ObjectsCounter3DWrapper.Result result = previewAdapter.runPreview(
                            previewSource, threshold, previewMinSize, previewMaxSize);
                    PreviewInputLeaseRegistry.Reservation reservation =
                            previewInputLeases.reserve(
                                    result == null ? null : result.getMaskedImage(),
                                    result == null ? null : result.getObjectsMap());
                    handoff.setResult(result, reservation);
                    return result;
                } catch (Throwable failure) {
                    restoreInterruptIfNeeded(failure);
                    previewWorkerFailureObservedInterrupt =
                            Thread.currentThread().isInterrupted();
                    handoff.setFailure(failure);
                    throwPreviewFailure(failure);
                    return null;
                } finally {
                    final SwingWorker<ObjectsCounter3DWrapper.Result, Void> completedWorker = this;
                    handoff.markPhysicallyFinished(new Runnable() {
                        @Override public void run() {
                            completePreviewWorker(completedWorker, handoff, requestEpoch,
                                    previewContext, previewSource, borrowedPreviewImages,
                                    inputLease, token, previewMinSize, previewMaxSize, threshold);
                        }
                    });
                }
            }

            @Override protected void done() {
                handoff.finishBeforeStart(null);
                completePreviewWorker(this, handoff, requestEpoch, previewContext,
                        previewSource, borrowedPreviewImages, inputLease, token, previewMinSize,
                        previewMaxSize, threshold);
            }
        };
        final SwingWorker<ObjectsCounter3DWrapper.Result, Void> startedWorker = previewWorker;
        previewWorkerPreStartCompletion = new Runnable() {
            @Override public void run() {
                if (handoff.finishBeforeStart(null)) {
                    completePreviewWorker(startedWorker, handoff, requestEpoch, previewContext,
                            previewSource, borrowedPreviewImages, inputLease, token,
                            previewMinSize, previewMaxSize, threshold);
                }
            }
        };
        try {
            previewWorkerExecutor.execute(startedWorker);
        } catch (Throwable executeFailure) {
            if (handoff.finishBeforeStart(executeFailure)) {
                completePreviewWorker(startedWorker, handoff, requestEpoch, previewContext,
                        previewSource, borrowedPreviewImages, inputLease, token, previewMinSize,
                        previewMaxSize, threshold);
            } else {
                throwPreviewFailure(executeFailure);
            }
        }
    }

    private void completePreviewWorker(
            SwingWorker<ObjectsCounter3DWrapper.Result, Void> worker,
            PreviewWorkerHandoff<ObjectsCounter3DWrapper.Result> handoff,
            long requestEpoch,
            ConfigQcContext previewContext,
            ImagePlus previewSource,
            Set<ImagePlus> borrowedPreviewImages,
            PreviewInputLeaseRegistry.Lease inputLease,
            ParticleSizeStage.SizeToken token,
            int previewMinSize,
            int previewMaxSize,
            int threshold) {
        if (!handoff.claimPhysicalCompletion()) return;
        boolean completionInterruptedOnEntry = Thread.interrupted();
        boolean current = !worker.isCancelled() && isCurrentPreviewRequest(
                worker, requestEpoch, previewContext, previewSource);
        PreviewWorkerHandoff.PublishedResult<ObjectsCounter3DWrapper.Result> published =
                handoff.takeResult();
        ObjectsCounter3DWrapper.Result result = published == null ? null : published.value;
        PreviewInputLeaseRegistry.Reservation resultReservation =
                published == null ? null : published.reservation;
        Throwable failure = previewFailureCause(handoff.takeFailure());
        Set<ImagePlus> attempted = Collections.newSetFromMap(
                new IdentityHashMap<ImagePlus, Boolean>());
        try {
            if (failure == null && current) {
                installObjectPreview(result, token, previewMinSize, previewMaxSize, threshold);
            } else if (!current) {
                failure = closePreviewResult(
                        result, attempted, failure, borrowedPreviewImages);
            }
        } catch (Throwable completionFailure) {
            failure = mergePreviewFailures(
                    failure, previewFailureCause(completionFailure));
        }
        try {
            if (resultReservation != null) {
                ImagePlus[] pendingResultClose = current
                        ? resultReservation.transferTo(borrowedPreviewImagesSnapshot())
                        : resultReservation.release();
                for (ImagePlus pending : pendingResultClose) {
                    failure = closeUniqueUnpublished(pending, attempted, failure);
                }
            }
        } catch (Throwable reservationFailure) {
            failure = mergePreviewFailures(failure, reservationFailure);
        }
        try {
            ImagePlus pendingInputClose = current
                    ? inputLease.transferTo(borrowedPreviewImagesSnapshot())
                    : inputLease.release();
            if (pendingInputClose != null) {
                failure = closeUniqueUnpublished(
                        pendingInputClose, attempted, failure);
            }
        } catch (Throwable leaseFailure) {
            failure = mergePreviewFailures(failure, leaseFailure);
        }
        failure = retryRetainedPreviewCleanup(failure);
        previewWorkerCompletionFailure = failure;
        try {
            if (failure != null) {
                if (isVmFatal(failure)) {
                    throwPreviewFailure(failure);
                }
                if (current) {
                    reportPreviewFailure(failure);
                } else {
                    logStalePreviewFailure(failure);
                }
            }
        } finally {
            if (completionInterruptedOnEntry) {
                Thread.currentThread().interrupt();
            } else {
                Thread.interrupted();
            }
            previewWorkerCompletionObservedInterrupt =
                    Thread.currentThread().isInterrupted();
            previewWorkerCompletionHandled = true;
            if (previewWorker == worker) {
                previewWorker = null;
                previewWorkerPreStartCompletion = null;
            }
            if (current) {
                setButtonsEnabled(true);
                if (actions != null) actions.setPreviewButtonRunning(false);
            }
        }
    }

    private void runPreviewNow() throws Exception {
        boolean restoreRunInterrupt = Thread.currentThread().isInterrupted();
        try {
            if (filteredSource == null || thresholdControl == null) {
                throw new IllegalStateException(
                        "No Classical segmentation input image is available.");
            }
            ParticleSizeStage.SizeToken token = collectSizeToken();
            int threshold = currentThresholdValue();
            validateSizeToken(token);
            int minSize = minSizeVoxels(token);
            int maxSize = maxSizeVoxels(token);
            if (restoreRunInterrupt) Thread.currentThread().interrupt();
            setPreviewStatePreservingInterrupt(PreviewPairPanel.PreviewState.RUNNING,
                    "Running object preview...");
            installObjectPreview(previewAdapter.runPreview(filteredSource, threshold,
                    minSize, maxSize), token, minSize, maxSize, threshold);
        } finally {
            if (restoreRunInterrupt) Thread.currentThread().interrupt();
        }
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
        boolean restoreInstallInterrupt = Thread.interrupted();
        try {
        Set<ImagePlus> attempted = Collections.newSetFromMap(
                new IdentityHashMap<ImagePlus, Boolean>());
        ImagePlus labelImage = null;
        boolean committed = false;
        try {
            int count = previewAdapter.countObjects(result);
            labelImage = result == null ? null : result.getObjectsMap();
            if (labelImage == null) {
                labelImage = emptyLabelMapLike(filteredSource);
            }
            if (labelImage == null) {
                setError("Object preview returned no label map.");
                Throwable closeFailure = closePreviewResult(result, attempted, null);
                if (closeFailure != null) throwPreviewFailure(closeFailure);
                return;
            }
            removeRetainedPreviewCleanup(labelImage);
            ImagePlus masked = result == null ? null : result.getMaskedImage();
            if (masked != null && masked != labelImage) {
                Throwable closeFailure = closeUniqueUnpublished(masked, attempted, null);
                if (closeFailure != null) throwPreviewFailure(closeFailure);
            }
            labelImage.setTitle(count > 0
                    ? "Object label preview"
                    : "Object label preview (no objects)");

            captureCurrentPreviewForComparison();
            ImagePlus old = labelPreview;
            labelPreview = labelImage;
            attempted.add(labelImage);
            retainOldPreviewImage(old);
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
            committed = true;
            throwRetainedPreviewCleanupFailure();
        } catch (Throwable primaryFailure) {
            Throwable outcome = primaryFailure;
            if (!committed) {
                if (labelPreview == labelImage) {
                    labelPreview = null;
                    objectStats = null;
                    sizeSummary = null;
                    displayedSize = null;
                    lastObjectCount = -1;
                    objectPreviewStale = true;
                }
                attempted.remove(labelImage);
                outcome = closeUniqueUnpublished(labelImage, attempted, outcome);
            }
            outcome = closePreviewResult(result, attempted, outcome);
            restoreInterruptIfNeeded(outcome);
            throwPreviewFailure(outcome);
        }
        } finally {
            if (restoreInstallInterrupt) Thread.currentThread().interrupt();
        }
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

    private void setPreviewStatePreservingInterrupt(PreviewPairPanel.PreviewState state,
                                                     String text) {
        boolean restoreInterrupt = Thread.interrupted();
        try {
            setPreviewState(state, text);
        } finally {
            if (restoreInterrupt) Thread.currentThread().interrupt();
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
        ImagePlus snapshot = preview == null
                ? PreviewPairPanel.duplicateForComparison(labelPreview, "Previous object preview")
                : preview.duplicateCurrentObjectPreviewForComparison("Previous object preview");
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
                thresholdControl.setThresholdPreservingRange(lower,
                        upperThresholdFor(lower, filteredSource));
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
            double lower = threshold.doubleValue();
            thresholdControl.setThresholdPreservingRange(lower, upperThresholdFor(lower, filteredSource));
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
            if (sizeCutoffPanel != null) sizeCutoffPanel.setSummary(sizeSummary);
            applySizeGuideOverlay();
            if (!canRelabelFromCurrentPreview(minSize, maxSize)) {
                return false;
            }
            if (preview != null) {
                preview.setObjectFilterPreview(labelPreview, sizeSummary.removedLabels(),
                        sizeSummary, lastObjectCount);
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
                : (thresholdControl.isAlgorithmThresholdSelected()
                ? thresholdControl.getAutoThresholdToken()
                : ChannelThresholdStage.formatThreshold(thresholdControl.getLowerThreshold()));
    }

    private int currentThresholdValue() {
        return thresholdControl == null
                ? 0
                : Math.max(0, (int) Math.round(thresholdControl.getLowerThreshold()));
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
        if (showRemovedObjectsSwitch != null) showRemovedObjectsSwitch.setEnabled(enabled);
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
        previewEpoch++;
        SwingWorker<ObjectsCounter3DWrapper.Result, Void> worker = previewWorker;
        Runnable preStartCompletion = previewWorkerPreStartCompletion;
        previewWorker = null;
        previewWorkerPreStartCompletion = null;
        if (worker != null && !worker.isDone()) {
            if (preStartCompletion != null) preStartCompletion.run();
            worker.cancel(true);
        }
    }

    private boolean isCurrentPreviewRequest(
            SwingWorker<ObjectsCounter3DWrapper.Result, Void> worker,
            long epoch,
            ConfigQcContext context,
            ImagePlus source) {
        return previewSessionActive
                && previewEpoch == epoch
                && previewWorker == worker
                && activeContext == context
                && filteredSource == source;
    }

    private Throwable closePreviewResult(ObjectsCounter3DWrapper.Result result,
                                         Set<ImagePlus> attempted,
                                         Throwable primaryFailure) {
        return closePreviewResult(result, attempted, primaryFailure, null);
    }

    private Throwable closePreviewResult(ObjectsCounter3DWrapper.Result result,
                                         Set<ImagePlus> attempted,
                                         Throwable primaryFailure,
                                         Set<ImagePlus> borrowedPreviewImages) {
        if (result == null) return primaryFailure;
        Throwable outcome = closeUniqueUnpublished(
                result.getMaskedImage(), attempted, primaryFailure, borrowedPreviewImages);
        return closeUniqueUnpublished(
                result.getObjectsMap(), attempted, outcome, borrowedPreviewImages);
    }

    private Throwable closeUniqueUnpublished(ImagePlus image,
                                             Set<ImagePlus> attempted,
                                             Throwable primaryFailure) {
        return closeUniqueUnpublished(image, attempted, primaryFailure, null);
    }

    private Throwable closeUniqueUnpublished(ImagePlus image,
                                             Set<ImagePlus> attempted,
                                             Throwable primaryFailure,
                                             Set<ImagePlus> borrowedPreviewImages) {
        if (image == null || isBorrowedPreviewImage(image)
                || (borrowedPreviewImages != null && borrowedPreviewImages.contains(image))) {
            return primaryFailure;
        }
        if (previewInputLeases.deferClose(image)) return primaryFailure;
        if (!attempted.add(image)) return primaryFailure;
        try {
            previewAdapter.close(image);
            removeRetainedPreviewCleanup(image);
        } catch (Throwable cleanupFailure) {
            retainPreviewCleanup(image);
            return mergePreviewFailures(primaryFailure, cleanupFailure);
        }
        return primaryFailure;
    }

    private boolean isBorrowedPreviewImage(ImagePlus image) {
        return image == rawSource
                || image == filteredSource
                || image == thresholdPreview
                || image == labelPreview
                || image == previousLabelPreview;
    }

    private Set<ImagePlus> borrowedPreviewImagesSnapshot() {
        Set<ImagePlus> borrowed = Collections.newSetFromMap(
                new IdentityHashMap<ImagePlus, Boolean>());
        borrowed.add(rawSource);
        borrowed.add(filteredSource);
        borrowed.add(thresholdPreview);
        borrowed.add(labelPreview);
        borrowed.add(previousLabelPreview);
        borrowed.remove(null);
        return borrowed;
    }

    private static Throwable mergePreviewFailures(Throwable primary, Throwable cleanup) {
        if (primary == null) return cleanup;
        if (cleanup == null || cleanup == primary) return primary;
        if (isVmFatal(cleanup) && !isVmFatal(primary)) {
            addSuppressedIfDistinct(cleanup, primary);
            return cleanup;
        }
        addSuppressedIfDistinct(primary, cleanup);
        return primary;
    }

    private static void addSuppressedIfDistinct(Throwable primary, Throwable secondary) {
        if (primary != null && secondary != null && primary != secondary) {
            for (Throwable existing : primary.getSuppressed()) {
                if (existing == secondary) return;
            }
            primary.addSuppressed(secondary);
        }
    }

    private static Throwable previewFailureCause(Throwable failure) {
        return failure instanceof java.util.concurrent.ExecutionException
                && failure.getCause() != null
                ? failure.getCause()
                : failure;
    }

    private static void restoreInterruptIfNeeded(Throwable failure) {
        if (containsInterruption(failure, new HashSet<Throwable>())) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean containsInterruption(Throwable failure, Set<Throwable> visited) {
        if (failure == null || !visited.add(failure)) return false;
        if (failure instanceof InterruptedException
                || failure instanceof java.io.InterruptedIOException) return true;
        if (containsInterruption(failure.getCause(), visited)) return true;
        for (Throwable suppressed : failure.getSuppressed()) {
            if (containsInterruption(suppressed, visited)) return true;
        }
        return false;
    }

    private static boolean isVmFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    private void reportPreviewFailure(Throwable primaryFailure) {
        try {
            setError("Object preview failed: " + primaryFailure.getMessage());
        } catch (Throwable reportingFailure) {
            Throwable outcome = mergePreviewFailures(primaryFailure, reportingFailure);
            restoreInterruptIfNeeded(outcome);
            throwPreviewFailure(outcome);
        }
    }

    private void logStalePreviewFailure(Throwable failure) {
        try {
            IJ.log("Classical preview stopped after cancellation: " + failure);
        } catch (ThreadDeath fatal) {
            throw fatal;
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Logging must never turn a cancelled nonfatal worker into an EDT failure.
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwPreviewFailure(Throwable failure) throws T {
        throw (T) failure;
    }

    private void retainOldPreviewImage(ImagePlus image) {
        if (image == null || isBorrowedPreviewImage(image)) return;
        retainPreviewCleanup(image);
    }

    private void retainPreviewCleanup(ImagePlus image) {
        if (image == null) return;
        if (previewInputLeases.deferClose(image)) return;
        synchronized (retainedPreviewCleanup) {
            retainedPreviewCleanup.add(image);
        }
    }

    private void removeRetainedPreviewCleanup(ImagePlus image) {
        if (image == null) return;
        synchronized (retainedPreviewCleanup) {
            retainedPreviewCleanup.remove(image);
        }
    }

    private Throwable retryRetainedPreviewCleanup(Throwable primaryFailure) {
        Throwable outcome = primaryFailure;
        boolean restoreInterrupt = Thread.interrupted();
        try {
            synchronized (retainedPreviewCleanup) {
                ImagePlus[] pending = retainedPreviewCleanup.toArray(
                        new ImagePlus[retainedPreviewCleanup.size()]);
                for (ImagePlus image : pending) {
                    if (isBorrowedPreviewImage(image)) continue;
                    if (previewInputLeases.deferClose(image)) {
                        retainedPreviewCleanup.remove(image);
                        continue;
                    }
                    try {
                        previewAdapter.close(image);
                        retainedPreviewCleanup.remove(image);
                    } catch (Throwable cleanupFailure) {
                        outcome = mergePreviewFailures(outcome, cleanupFailure);
                    }
                }
            }
        } finally {
            if (restoreInterrupt) Thread.currentThread().interrupt();
        }
        return outcome;
    }

    private void throwRetainedPreviewCleanupFailure() {
        Throwable failure = retryRetainedPreviewCleanup(null);
        if (failure != null) {
            restoreInterruptIfNeeded(failure);
            throwPreviewFailure(failure);
        }
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
        retainPreviewCleanup(previous);
        retainPreviewCleanup(label);
        retainPreviewCleanup(threshold);
        retainPreviewCleanup(filtered);
        retainPreviewCleanup(raw);
        throwRetainedPreviewCleanupFailure();
    }

    private void closeOldPreviewImage(ImagePlus image) {
        retainOldPreviewImage(image);
        throwRetainedPreviewCleanupFailure();
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

    private static double upperThresholdFor(double lower, ImagePlus image) {
        double upper = imageMaximum(image);
        return Double.isFinite(lower) ? Math.max(upper, lower) : upper;
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
