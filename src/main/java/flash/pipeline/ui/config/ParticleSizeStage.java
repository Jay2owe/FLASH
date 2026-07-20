package flash.pipeline.ui.config;

import flash.pipeline.help.SetupHelpCatalog;
import flash.pipeline.help.SetupHelpTopic;
import flash.pipeline.objects.ObjectsCounter3DWrapper;
import flash.pipeline.ui.Debouncer;
import flash.pipeline.ui.FlashTheme;
import flash.pipeline.ui.ToggleSwitch;
import flash.pipeline.ui.preview.ObjectSizeFilterPreview;
import flash.pipeline.ui.preview.PreviewPairPanel;
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
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.HashSet;
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
    private final Set<ImagePlus> retainedPreviewCleanup = Collections.newSetFromMap(
            new IdentityHashMap<ImagePlus, Boolean>());
    private final PreviewInputLeaseRegistry previewInputLeases =
            new PreviewInputLeaseRegistry();
    private PreviewWorkerExecutor previewWorkerExecutor = PreviewWorkerExecutor.DEFAULT;
    private String previousPreviewText = "";
    private SizeToken previousSettingsSize;
    private SizeToken displayedSize;
    private ResultsTable objectStats;
    private volatile SwingWorker<ObjectsCounter3DWrapper.Result, Void> previewWorker;
    private volatile Runnable previewWorkerPreStartCompletion;
    private volatile long previewEpoch;
    private volatile boolean previewSessionActive;
    private volatile Throwable previewWorkerCompletionFailure;
    private volatile boolean previewWorkerCompletionHandled;
    private volatile boolean previewWorkerFailureObservedInterrupt;
    private volatile boolean previewWorkerCompletionObservedInterrupt;
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
    private JLabel sizeValidationLabel;
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
        sizeValidationLabel = new JLabel(" ");
        sizeValidationLabel.setForeground(FlashTheme.TEXT_HELP);
        sizeValidationLabel.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        panel.add(sizeValidationLabel);
        panel.add(Box.createVerticalStrut(4));
        sizeCutoffPanel = new ObjectSizeCutoffPanel();
        panel.add(sizeCutoffPanel);
        panel.add(Box.createVerticalStrut(4));
        panel.add(buildActionRow());
        loadFields(savedSize);
        updateSizeValidationState();
        refreshSizeCutoffPanelOnly();
        markPreviewStale(EMPTY_TEXT);
        return panel;
    }

    @Override
    public void onEnter(ConfigQcContext context, PreviewPairPanel preview) {
        previewSessionActive = false;
        closePreviewWorker();
        closeImages();
        this.activeContext = context;
        this.preview = preview;
        previewSessionActive = true;
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
            updateSizeValidationState();
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
        closePreviewWorker();
        try {
            restartSize = collectSizeToken();
        } catch (RuntimeException ignored) {
            // Keep the prior restart value if the current fields are invalid.
        }
        setStatus("Restarting particle-size review from the first image.");
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

    void setThresholdForTest(int threshold) {
        thresholdValue = Integer.valueOf(threshold);
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

    ImagePlus labelPreviewForTest() {
        return labelPreview;
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
        if (!updateSizeValidationState()) {
            markPreviewStale("Use min and max voxel sizes, for example 100-Infinity.");
            return;
        }
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
        updateSizeValidationState();
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
        final ConfigQcContext previewContext = activeContext;
        final Set<ImagePlus> borrowedPreviewImages = borrowedPreviewImagesSnapshot();
        final int previewThreshold = thresholdValue.intValue();
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
                            previewSource, previewThreshold, minSize, maxSize);
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
                                    inputLease, token);
                        }
                    });
                }
            }

            @Override protected void done() {
                handoff.finishBeforeStart(null);
                completePreviewWorker(this, handoff, requestEpoch, previewContext,
                        previewSource, borrowedPreviewImages, inputLease, token);
            }
        };
        final SwingWorker<ObjectsCounter3DWrapper.Result, Void> startedWorker = previewWorker;
        previewWorkerPreStartCompletion = new Runnable() {
            @Override public void run() {
                if (handoff.finishBeforeStart(null)) {
                    completePreviewWorker(startedWorker, handoff, requestEpoch, previewContext,
                            previewSource, borrowedPreviewImages, inputLease, token);
                }
            }
        };
        try {
            previewWorkerExecutor.execute(startedWorker);
        } catch (Throwable executeFailure) {
            if (handoff.finishBeforeStart(executeFailure)) {
                completePreviewWorker(startedWorker, handoff, requestEpoch, previewContext,
                        previewSource, borrowedPreviewImages, inputLease, token);
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
            SizeToken token) {
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
                installObjectPreview(result, token);
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
            if (current) setButtonsEnabled(true);
        }
    }

    private void runPreviewNow() throws Exception {
        boolean restoreRunInterrupt = Thread.currentThread().isInterrupted();
        try {
            if (filteredSource == null || thresholdValue == null) {
                throw new IllegalStateException("No particle-size input image is available.");
            }
            SizeToken token = collectSizeToken();
            validateSizeToken(token, filteredSource);
            int minSize = ObjectsCounter3DWrapper.parseMinSizeVoxels(token.minText, 100);
            int maxSize = ObjectsCounter3DWrapper.parseMaxSizeVoxels(
                    token.maxText, filteredSource);
            if (restoreRunInterrupt) Thread.currentThread().interrupt();
            setPreviewStatePreservingInterrupt(PreviewPairPanel.PreviewState.RUNNING,
                    "Running object preview...");
            installObjectPreview(previewAdapter.runPreview(
                    filteredSource, thresholdValue.intValue(), minSize, maxSize), token);
        } finally {
            if (restoreRunInterrupt) Thread.currentThread().interrupt();
        }
    }

    private void installObjectPreview(ObjectsCounter3DWrapper.Result result, SizeToken runSize) {
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
                setPreviewState(PreviewPairPanel.PreviewState.ERROR,
                        "Object preview returned no label map.");
                setStatus("Object preview returned no label map.");
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
            lastObjectCount = count;
            previewStale = false;
            refreshSizeFilterPreview();
            displayedSize = normalizedSizeToken(runSize);
            String text = objectCountText();
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
                    previewStale = true;
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

    private void setPreviewStatePreservingInterrupt(PreviewPairPanel.PreviewState state,
                                                     String text) {
        boolean restoreInterrupt = Thread.interrupted();
        try {
            setPreviewState(state, text);
        } finally {
            if (restoreInterrupt) Thread.currentThread().interrupt();
        }
    }

    private void setStatus(String text) {
        if (actions != null) {
            actions.setStatus(text);
        }
    }

    private boolean updateSizeValidationState() {
        boolean valid = isValidSizeFields(
                minField == null ? null : minField.getText(),
                maxField == null ? null : maxField.getText(),
                filteredSource);
        if (sizeValidationLabel != null) {
            sizeValidationLabel.setText(valid ? " " : "Use min and max voxel sizes, for example 100-Infinity.");
        }
        if (actions != null) {
            actions.setPrimaryButtonEnabled(valid);
        }
        return valid;
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
                || image == labelPreview
                || image == previousLabelPreview;
    }

    private Set<ImagePlus> borrowedPreviewImagesSnapshot() {
        Set<ImagePlus> borrowed = Collections.newSetFromMap(
                new IdentityHashMap<ImagePlus, Boolean>());
        borrowed.add(rawSource);
        borrowed.add(filteredSource);
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
            IJ.log("Particle-size preview stopped after cancellation: " + failure);
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
        retainPreviewCleanup(previous);
        retainPreviewCleanup(label);
        retainPreviewCleanup(filtered);
        retainPreviewCleanup(raw);
        throwRetainedPreviewCleanupFailure();
    }

    private void closeOldPreviewImage(ImagePlus image) {
        retainOldPreviewImage(image);
        throwRetainedPreviewCleanupFailure();
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

    public static boolean isValidSizeRangeToken(String token) {
        if (token == null) return false;
        String[] parts = token.trim().split("-", 2);
        return parts.length == 2 && isValidSizeFields(parts[0], parts[1], null);
    }

    public static boolean isValidSizeFields(String minText, String maxText, ImagePlus source) {
        try {
            int min = Math.max(0, ObjectsCounter3DWrapper.parseMinSizeVoxels(minText, 100));
            String max = normalizeMaxText(maxText);
            int maxSize = ObjectsCounter3DWrapper.parseMaxSizeVoxels(max, source);
            return !isFiniteMaxToken(max) || maxSize > min;
        } catch (RuntimeException e) {
            return false;
        }
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
