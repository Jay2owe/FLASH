package flash.pipeline.ui.variations;

import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.preview.ComparisonPreviewDialog;
import flash.pipeline.ui.preview.PreviewDisplaySettings;
import flash.pipeline.ui.preview.VariationComparisonPreview;
import flash.pipeline.ui.variations.analysis.IouStability;
import flash.pipeline.ui.variations.analysis.KneeDetector;
import flash.pipeline.ui.variations.state.VariationState;
import flash.pipeline.ui.variations.state.VariationStateStore;
import flash.pipeline.ui.variations.strategy.CellposeOneShot;
import flash.pipeline.ui.variations.strategy.CellposePersistent;
import flash.pipeline.ui.variations.strategy.ClassicalSweep;
import flash.pipeline.ui.variations.strategy.StarDistFastNms;
import flash.pipeline.ui.variations.strategy.StarDistPerCell;
import flash.pipeline.ui.variations.strategy.VariationStrategyChooser;

import ij.ImagePlus;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.AbstractAction;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.Rectangle;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.awt.event.KeyEvent;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class VariationsDialog extends PipelineDialog {

    private final VariationEngineContext context;
    private final Consumer<ParameterCombo> onAccept;
    private final ParameterSweepEditor editor;
    private final VariationStateStore stateStore;
    private final JLabel cellsLabel = new JLabel("Cells: 1");
    private final JLabel suggestionLabel = new JLabel("Most stable: pending");
    private final JLabel statusLabel = new JLabel("Idle");
    private final JLabel strategyLabel = new JLabel(" ");
    private final ProgressSliverPanel progressSliver = new ProgressSliverPanel();
    private final JButton suggestButton = new JButton("Suggest ranges from image");
    private final JRadioButton fullCrop = new JRadioButton("full image");
    private final JRadioButton centreCrop = new JRadioButton("centered 256 x 256");
    private final JRadioButton customCrop = new JRadioButton("custom...");
    private final Map<String, VariationCellPanel> cellsByCombo =
            new HashMap<String, VariationCellPanel>();
    private final Map<String, Integer> cellIndexesByCombo =
            new HashMap<String, Integer>();
    private final List<VariationCellPanel> cells = new ArrayList<VariationCellPanel>();
    private final List<VariationResult> resultsByCell =
            new ArrayList<VariationResult>();
    private final VariationComparisonSelection comparisonSelection;

    private VariationExecutor executor;
    private ParameterSweep currentSweep;
    private VariationState resumeState;
    private ComparisonPreviewDialog comparisonDialog;
    private VariationGridWindow gridWindow;
    private CropSpec currentCropSpec = CropSpec.centre256();
    private boolean suppressCropEvents;
    private int completedCount;
    private int failedCount;
    private long runStartedAtMs;
    private VariationStrategy strategyForTest;
    private String stableCountStatus = "";
    private String stableMasksStatus = "";

    public VariationsDialog(Window owner,
                            VariationEngineContext context,
                            Consumer<ParameterCombo> onAccept) {
        super(owner, "Parameter Variations");
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context;
        this.onAccept = onAccept;
        this.editor = new ParameterSweepEditor(context);
        this.stateStore = new VariationStateStore(context.binFolder() == null
                ? null
                : context.binFolder().toPath());
        this.comparisonSelection = new VariationComparisonSelection(
                new Consumer<String>() {
                    @Override public void accept(String status) {
                        setStatusTextNow(status);
                    }
                },
                new VariationComparisonSelection.Opener() {
                    @Override public void openComparison(VariationCellPanel left,
                                                         VariationCellPanel right) {
                        VariationsDialog.this.openComparison(left, right);
                    }
                });
        setDefaultButtonsVisible(false);
        buildUi();
        installCompareCancelKey();
        installWindowCleanup();
        refreshCellEstimate();
        offerResumeIfAvailable();
    }

    public void start() {
        if (SwingUtilities.isEventDispatchThread()) {
            startOnEdt();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    startOnEdt();
                }
            });
        } catch (Exception e) {
            throw new IllegalStateException("Could not start parameter variations", e);
        }
    }

    public void dispose() {
        cancelExecutor();
        comparisonSelection.clearForAccept();
        if (comparisonDialog != null) {
            comparisonDialog.dispose();
            comparisonDialog = null;
        }
        disposeGridWindow();
        disposeCells();
        Window window = getWindow();
        if (window != null) {
            window.dispose();
        }
    }

    ParameterSweepEditor editorForTest() {
        return editor;
    }

    VariationGridWindow gridWindowForTest() {
        return gridWindow;
    }

    int cellCountForTest() {
        return cells.size();
    }

    int completedCountForTest() {
        return completedCount;
    }

    void setSweepForTest(ParameterSweep sweep) {
        editor.setSweep(sweep);
        if (sweep != null) {
            currentCropSpec = sweep.cropSpec();
            selectCropButton(currentCropSpec);
        }
        refreshCellEstimate();
    }

    void setStrategyForTest(VariationStrategy strategy) {
        strategyForTest = strategy;
    }

    void setGlobalZForTest(int z) {
        if (gridWindow != null) {
            gridWindow.zSliderForTest().setValue(z);
        }
    }

    void cancelForTest() {
        cancelExecutor();
    }

    void waitForDoneForTest(long timeoutMs) throws Exception {
        VariationExecutor worker = executor;
        if (worker != null) {
            worker.get(timeoutMs, TimeUnit.MILLISECONDS);
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline
                && completedCount < cells.size()) {
            EventQueue.invokeAndWait(new Runnable() {
                @Override public void run() {
                }
            });
            if (completedCount >= cells.size()) {
                return;
            }
            Thread.sleep(10L);
        }
        EventQueue.invokeAndWait(new Runnable() {
            @Override public void run() {
            }
        });
    }

    ImagePlus croppedForComparisonForTest(ImagePlus source) {
        return croppedForComparison(source);
    }

    private void buildUi() {
        addComponent(headerPanel());
        addHeader("Parameters to sweep");
        addComponent(editor);
        addComponent(rangeRow());
        addComponent(cropRow());
        addComponent(statusPanel());

        JButton cancel = addRightFooterButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JButton start = addRightFooterButton("Start");
        start.addActionListener(e -> start());

        editor.addChangeListener(new ChangeListener() {
            @Override public void stateChanged(ChangeEvent e) {
                refreshCellEstimate();
            }
        });
    }

    private JPanel headerPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(0, 4, 6, 4));

        JLabel method = new JLabel("Method: " + context.method().label());
        method.setFont(method.getFont().deriveFont(java.awt.Font.BOLD, 12f));
        panel.add(method);
        panel.add(Box.createHorizontalStrut(24));
        panel.add(new JLabel("Channel: " + safe(context.channelName())));
        panel.add(Box.createHorizontalGlue());
        panel.add(cellsLabel);
        return panel;
    }

    private JPanel rangeRow() {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        suggestButton.addActionListener(e -> runRangeSuggester());
        row.add(suggestButton);
        row.add(Box.createHorizontalGlue());
        return row;
    }

    private JPanel cropRow() {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        ButtonGroup group = new ButtonGroup();
        group.add(fullCrop);
        group.add(centreCrop);
        group.add(customCrop);
        selectCropButton(currentCropSpec);
        fullCrop.setOpaque(false);
        centreCrop.setOpaque(false);
        customCrop.setOpaque(false);

        row.add(new JLabel("Crop: "));
        row.add(fullCrop);
        row.add(Box.createHorizontalStrut(10));
        row.add(centreCrop);
        row.add(Box.createHorizontalStrut(10));
        row.add(customCrop);
        row.add(Box.createHorizontalGlue());

        ActionListener listener = new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                handleCropSelection((JRadioButton) e.getSource());
            }
        };
        fullCrop.addActionListener(listener);
        centreCrop.addActionListener(listener);
        customCrop.addActionListener(listener);
        editor.setCropSpec(currentCropSpec);
        return row;
    }

    private JPanel statusPanel() {
        progressSliver.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        updateProgressSliver();
        return progressSliver;
    }

    private void installWindowCleanup() {
        Window window = getWindow();
        if (window == null) {
            return;
        }
        window.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                cancelExecutor();
                disposeGridWindow();
            }

            @Override public void windowClosed(WindowEvent e) {
                cancelExecutor();
                disposeGridWindow();
            }
        });
    }

    private void installCompareCancelKey() {
        Window window = getWindow();
        if (!(window instanceof JDialog)) {
            return;
        }
        JRootPane root = ((JDialog) window).getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                "cancelVariationComparisonSelection");
        root.getActionMap().put("cancelVariationComparisonSelection",
                new AbstractAction() {
                    @Override public void actionPerformed(ActionEvent e) {
                        if (comparisonSelection.hasPendingSelection()) {
                            comparisonSelection.cancelSelection();
                        }
                    }
                });
    }

    private void offerResumeIfAvailable() {
        Optional<VariationState> prior = stateStore.load();
        if (!prior.isPresent()) {
            return;
        }
        VariationState state = prior.get();
        String currentImageHash = currentImageHash();
        if (!safe(state.imageHash()).equals(safe(currentImageHash))) {
            stateStore.clear();
            return;
        }
        if (state.method() != context.method()
                || !safe(state.channel()).equals(safe(context.channelName()))) {
            return;
        }
        if (state.completed().isEmpty()) {
            stateStore.clear();
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            applyState(state);
            return;
        }
        int completed = state.completed().size();
        long total = state.sweep().cellCount();
        int resume = JOptionPane.showConfirmDialog(getWindow(),
                "A previous sweep is " + completed + "/" + total
                        + " complete. Resume?",
                "Resume Parameter Variations",
                JOptionPane.YES_NO_CANCEL_OPTION);
        if (resume == JOptionPane.YES_OPTION) {
            applyState(state);
        } else if (resume == JOptionPane.NO_OPTION) {
            stateStore.clear();
        }
    }

    private void applyState(VariationState state) {
        if (state == null) {
            return;
        }
        resumeState = state;
        currentCropSpec = state.sweep().cropSpec();
        editor.setSweep(state.sweep());
        selectCropButton(currentCropSpec);
        refreshCellEstimate();
        setStatusTextNow("Resume ready ("
                + state.completed().size() + "/" + state.sweep().cellCount()
                + " complete)");
    }

    private void startOnEdt() {
        cancelExecutor();
        disposeGridWindow();
        currentSweep = editor.currentSweep();
        ImagePlus source = context.filteredSource();
        ResourceGuard.Feasibility feasibility =
                ResourceGuard.assessFeasibility(currentSweep, source);
        if (!feasibility.ok) {
            showMessage(feasibility.message);
            setStatusTextNow("Refused");
            setStrategyText(" ");
            return;
        }

        completedCount = 0;
        failedCount = 0;
        stableCountStatus = "";
        stableMasksStatus = "";
        comparisonSelection.clearForAccept();
        setSuggestionText("Most stable: pending");
        suggestButton.setEnabled(false);
        runStartedAtMs = System.currentTimeMillis();
        ImagePlus croppedSource = currentSweep.cropSpec().apply(source);
        List<ParameterCombo> combos = currentSweep.combos();
        VariationCache runCache = new VariationCache(context.configContext());
        VariationState activeResume = compatibleResumeState(currentSweep);
        Map<String, VariationState.CompletedCell> resumeCompleted =
                activeResume == null
                        ? Collections.<String, VariationState.CompletedCell>emptyMap()
                        : activeResume.completedByComboId();
        List<VariationState.CompletedCell> restoredCompleted =
                new ArrayList<VariationState.CompletedCell>();
        disposeCells();
        cells.clear();
        cellsByCombo.clear();
        cellIndexesByCombo.clear();
        resultsByCell.clear();
        BiConsumer<ParameterCombo, VariationCellPanel> compare =
                new BiConsumer<ParameterCombo, VariationCellPanel>() {
                    @Override public void accept(ParameterCombo combo, VariationCellPanel cell) {
                        handleCompare(combo, cell);
                    }
                };
        for (int i = 0; i < combos.size(); i++) {
            ParameterCombo combo = combos.get(i);
            VariationCellPanel cell = new VariationCellPanel(combo, croppedSource,
                    new Consumer<ParameterCombo>() {
                        @Override public void accept(ParameterCombo accepted) {
                            acceptAndClose(accepted);
                        }
                    },
                    compare,
                    i);
            cell.setState("running");
            cell.setZ(1);
            cells.add(cell);
            cellsByCombo.put(combo.toCanonicalJson(), cell);
            cellIndexesByCombo.put(combo.toCanonicalJson(), Integer.valueOf(i));
            VariationResult restored = restoredResult(combo, resumeCompleted, runCache);
            if (restored == null) {
                resultsByCell.add(null);
            } else {
                cell.setResult(restored);
                resultsByCell.add(restored);
                completedCount++;
                VariationState.CompletedCell completed =
                        resumeCompleted.get(VariationState.comboIdFor(currentSweep, combo));
                if (completed != null) {
                    restoredCompleted.add(completed);
                }
            }
        }
        openGridWindowForRun();
        setStatusTextNow(progressStatus(combos.size(), false));
        setSuggestionText("Most stable: pending");
        if (completedCount >= cells.size() && !cells.isEmpty()) {
            updateTileMetrics();
            applyKneeHint();
            applyStabilityHint();
            updateGridWindowProgress();
            setStatusTextNow(completionStatus());
            suggestButton.setEnabled(true);
            stateStore.clear();
            return;
        }

        VariationStrategy strategy;
        try {
            strategy = strategyForTest == null
                    ? VariationStrategyChooser.choose(currentSweep, context, runCache)
                    : strategyForTest;
        } catch (RuntimeException e) {
            showMessage(e.getMessage());
            setStatusTextNow("Refused");
            setStrategyText(" ");
            suggestButton.setEnabled(true);
            return;
        }
        setStrategyText(strategyDescription(strategy, combos.size()));
        String now = Instant.now().toString();
        String startedAt = activeResume == null ? now : activeResume.startedAt();
        stateStore.save(new VariationState(currentSweep, restoredCompleted, startedAt, now));
        final VariationExecutor[] workerRef = new VariationExecutor[1];
        final VariationExecutor worker = new VariationExecutor(currentSweep,
                strategy,
                runCache,
                (result, index) -> handleResult(workerRef[0], result, index.intValue()),
                status -> setStatusText(status),
                stateStore);
        workerRef[0] = worker;
        executor = worker;
        worker.addPropertyChangeListener(evt -> {
            if ("state".equals(evt.getPropertyName())
                    && javax.swing.SwingWorker.StateValue.DONE == evt.getNewValue()) {
                handleExecutorDone(worker);
            }
        });
        worker.execute();
    }

    private void openGridWindowForRun() {
        gridWindow = new VariationGridWindow(getWindow(), "FLASH variations", cells);
        gridWindow.setOtsuOverlaySelected(false);
        gridWindow.setDownstreamVerdictSelected(false);
        gridWindow.setDownstreamControlsEnabled(false, false,
                "Downstream verdicts are only available for macro variations.");
        gridWindow.setVisible(true);
        updateGridWindowProgress();
    }

    private void updateGridWindowProgress() {
        if (gridWindow == null) {
            return;
        }
        gridWindow.setSliceMax(gridWindow.controllerForTest().maxSlice());
        gridWindow.setCompletedCount(completedCount, cells.size(), failedCount);
    }

    private VariationState compatibleResumeState(ParameterSweep sweep) {
        if (resumeState == null || sweep == null) {
            return null;
        }
        if (!resumeState.isCompatible(sweep.method(),
                sweep.channelName(),
                sweep.sourceImageHash())) {
            return null;
        }
        resumeState = resumeState.validatedForResume(sweep);
        return resumeState;
    }

    private VariationResult restoredResult(ParameterCombo combo,
                                           Map<String, VariationState.CompletedCell> completed,
                                           VariationCache cache) {
        if (currentSweep == null || combo == null || completed == null
                || completed.isEmpty() || cache == null) {
            return null;
        }
        String comboId = VariationState.comboIdFor(currentSweep, combo);
        VariationState.CompletedCell saved = comboId == null
                ? null
                : completed.get(comboId);
        if (saved == null) {
            return null;
        }
        String expectedCacheKey = VariationCache.keyFor(currentSweep, combo);
        if (!expectedCacheKey.equals(saved.labelCacheKey())) {
            return null;
        }
        ImagePlus label = cache.get(saved.labelCacheKey());
        if (label == null) {
            return null;
        }
        return VariationResult.success(combo, label, saved.nObjects(),
                saved.durationMs(), null);
    }

    private void handleResult(final VariationExecutor worker,
                              VariationResult result,
                              int index) {
        if (!SwingUtilities.isEventDispatchThread()) {
            final VariationResult safeResult = result;
            final int safeIndex = index;
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    handleResult(worker, safeResult, safeIndex);
                }
            });
            return;
        }
        if (worker != executor) {
            return;
        }
        if (result == null) {
            return;
        }
        VariationCellPanel cell = cellsByCombo.get(result.combo().toCanonicalJson());
        Integer comboIndex = cellIndexesByCombo.get(result.combo().toCanonicalJson());
        int targetIndex = comboIndex == null ? index : comboIndex.intValue();
        if (cell == null && index >= 0 && index < cells.size()) {
            cell = cells.get(index);
            targetIndex = index;
        }
        if (cell == null) {
            return;
        }
        boolean alreadyCompleted = targetIndex >= 0
                && targetIndex < resultsByCell.size()
                && resultsByCell.get(targetIndex) != null;
        cell.setResult(result);
        if (targetIndex >= 0 && targetIndex < resultsByCell.size()) {
            resultsByCell.set(targetIndex, result);
        }
        if (!alreadyCompleted) {
            completedCount++;
        }
        failedCount = countFailures();
        updateTileMetrics();
        updateGridWindowProgress();
        setStatusTextNow(progressStatus(cells.size(), false));
        if (completedCount >= cells.size()) {
            applyKneeHint();
            applyStabilityHint();
            updateGridWindowProgress();
        }
    }

    private boolean allCellsSuccessful() {
        if (resultsByCell.size() != cells.size() || resultsByCell.isEmpty()) {
            return false;
        }
        for (int i = 0; i < resultsByCell.size(); i++) {
            VariationResult result = resultsByCell.get(i);
            if (result == null || result.hasError()) {
                return false;
            }
        }
        return true;
    }

    private void updateTileMetrics() {
        ParameterId swept = singleNumericAxis();
        if (swept == null) {
            for (int i = 0; i < cells.size(); i++) {
                cells.get(i).clearDeltaN();
            }
            return;
        }
        List<Integer> indexes = new ArrayList<Integer>();
        for (int i = 0; i < cells.size(); i++) {
            if (i >= resultsByCell.size()) {
                cells.get(i).clearDeltaN();
                continue;
            }
            VariationResult result = resultsByCell.get(i);
            Object value = cells.get(i).combo().get(swept);
            if (result == null || result.hasError() || !(value instanceof Number)) {
                cells.get(i).clearDeltaN();
                continue;
            }
            indexes.add(Integer.valueOf(i));
        }
        Collections.sort(indexes, new Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) {
                double av = ((Number) cells.get(a.intValue()).combo().get(swept)).doubleValue();
                double bv = ((Number) cells.get(b.intValue()).combo().get(swept)).doubleValue();
                return Double.compare(av, bv);
            }
        });
        int previous = -1;
        for (int i = 0; i < indexes.size(); i++) {
            int index = indexes.get(i).intValue();
            if (previous < 0) {
                cells.get(index).clearDeltaN();
            } else {
                int delta = resultsByCell.get(index).nObjects()
                        - resultsByCell.get(previous).nObjects();
                cells.get(index).setDeltaN(delta);
            }
            previous = index;
        }
    }

    private void handleExecutorDone(VariationExecutor worker) {
        if (!SwingUtilities.isEventDispatchThread()) {
            final VariationExecutor safeWorker = worker;
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    handleExecutorDone(safeWorker);
                }
            });
            return;
        }
        if (worker == null || worker != executor) {
            return;
        }
        suggestButton.setEnabled(true);
        if (worker.isCancelled()) {
            setStatusTextNow("Cancelled");
            for (int i = 0; i < cells.size(); i++) {
                if ("running".equals(cells.get(i).badgeText())
                        || "pending".equals(cells.get(i).badgeText())) {
                    cells.get(i).setState("cancelled");
                }
            }
            updateGridWindowProgress();
            return;
        }
        try {
            worker.get();
            updateTileMetrics();
            applyKneeHint();
            applyStabilityHint();
            failedCount = countFailures();
            updateGridWindowProgress();
            setStatusTextNow(completionStatus());
            if (allCellsSuccessful()) {
                stateStore.clear();
            }
        } catch (Exception e) {
            setStatusTextNow(statusWithFailures("Error"));
            showMessage(e.getMessage());
        }
    }

    private void applyKneeHint() {
        for (int i = 0; i < cells.size(); i++) {
            cells.get(i).setKneeWinner(false);
        }
        ParameterId swept = singleNumericAxis();
        List<Integer> indexes = swept == null
                ? Collections.<Integer>emptyList()
                : visibleCompletedCountCurveIndexes(swept);
        if (swept == null || indexes.size() < 3) {
            stableCountStatus = "";
            setSuggestionText(indicatorSummary());
            return;
        }
        double[] xs = new double[indexes.size()];
        double[] ys = new double[indexes.size()];
        for (int i = 0; i < indexes.size(); i++) {
            int cellIndex = indexes.get(i).intValue();
            VariationResult result = resultsByCell.get(cellIndex);
            Object value = result.combo().get(swept);
            if (!(value instanceof Number)) {
                return;
            }
            xs[i] = ((Number) value).doubleValue();
            ys[i] = result.nObjects();
        }
        OptionalInt kneeIndex = KneeDetector.findKneeIndex(xs, ys);
        if (!kneeIndex.isPresent()) {
            stableCountStatus = "No count plateau detected";
            setSuggestionText(indicatorSummary());
            return;
        }
        int pointIndex = kneeIndex.getAsInt();
        if (pointIndex < 0 || pointIndex >= indexes.size()) {
            return;
        }
        int cellIndex = indexes.get(pointIndex).intValue();
        if (cellIndex < 0 || cellIndex >= cells.size()) {
            return;
        }
        VariationCellPanel cell = cells.get(cellIndex);
        cell.setBorderHint(VariationCellPanel.BorderHint.KNEE);
        stableCountStatus = "Most stable count at "
                + ParameterLabels.shortKey(swept) + " = "
                + formatValue(cell.combo().get(swept));
        setSuggestionText(indicatorSummary());
    }

    private List<Integer> visibleCompletedCountCurveIndexes(ParameterId driver) {
        List<Integer> indexes = new ArrayList<Integer>();
        for (int i = 0; i < cells.size(); i++) {
            if (!isCompletedCountCurveCell(i, driver)) {
                continue;
            }
            indexes.add(Integer.valueOf(i));
        }
        return indexes;
    }

    private boolean isCompletedCountCurveCell(int index, ParameterId driver) {
        if (driver == null || index < 0 || index >= resultsByCell.size()) {
            return false;
        }
        VariationResult result = resultsByCell.get(index);
        if (result == null || result.hasError()) {
            return false;
        }
        Object value = result.combo().get(driver);
        return value instanceof Number;
    }

    private void applyStabilityHint() {
        for (int i = 0; i < cells.size(); i++) {
            cells.get(i).setStabilityWinner(false);
        }
        if (currentSweep == null
                || cells.size() < 3
                || resultsByCell.size() != cells.size()) {
            stableMasksStatus = "";
            setSuggestionText(indicatorSummary());
            return;
        }
        // Macro sweeps stay eligible for mask agreement: the topology only
        // needs neighbouring completed tiles, while the 3-tile guard suppresses
        // two-value macro-only sweeps.
        List<ParameterCombo> combos = new ArrayList<ParameterCombo>(cells.size());
        List<ImagePlus> labels = new ArrayList<ImagePlus>(cells.size());
        for (int i = 0; i < resultsByCell.size(); i++) {
            VariationResult result = resultsByCell.get(i);
            if (result == null || result.hasError() || result.label() == null) {
                return;
            }
            combos.add(result.combo());
            labels.add(result.label());
        }
        for (int i = 0; i < combos.size(); i++) {
            double mean = IouStability.meanNeighbourIou(combos, labels, i);
            cells.get(i).setIouToNeighbours(mean);
            VariationResult result = resultsByCell.get(i);
            if (result != null && !Double.isNaN(mean)) {
                resultsByCell.set(i, result.withMeanNeighbourIou(mean));
            }
        }
        OptionalInt stableIndex = IouStability.findMostStable(combos, labels);
        if (!stableIndex.isPresent()) {
            stableMasksStatus = stableCountStatus.length() == 0
                    || stableCountStatus.startsWith("No ")
                    ? "No mask consensus detected"
                    : "";
            setSuggestionText(indicatorSummary());
            return;
        }
        int index = stableIndex.getAsInt();
        if (index < 0 || index >= cells.size()) {
            return;
        }
        double mean = IouStability.meanNeighbourIou(combos, labels, index);
        VariationCellPanel cell = cells.get(index);
        cell.setStabilityWinner(true, mean);
        stableMasksStatus = "Most stable object masks at "
                + comboSummaryForStatus(cell.combo())
                + " (IoU " + String.format(Locale.ROOT, "%.2f", Double.valueOf(mean)) + ")";
        setSuggestionText(indicatorSummary());
    }

    private ParameterId singleNumericAxis() {
        if (currentSweep == null) {
            return null;
        }
        List<ParameterId> axes = new ArrayList<ParameterId>();
        for (Map.Entry<ParameterKey, ParameterValueList> entry
                : currentSweep.valueLists().entrySet()) {
            if (!(entry.getKey() instanceof ParameterId)) {
                continue;
            }
            ParameterId id = (ParameterId) entry.getKey();
            ParameterValueList values = entry.getValue();
            if (values == null || values.size() <= 1
                    || id.valueKind() != ParameterKey.ValueKind.NUMBER) {
                continue;
            }
            axes.add(id);
        }
        return axes.size() == 1 ? axes.get(0) : null;
    }

    private void handleCompare(ParameterCombo combo, VariationCellPanel cell) {
        comparisonSelection.handleShiftClick(cell);
    }

    private void openComparison(VariationCellPanel left, VariationCellPanel right) {
        if (left == null || right == null
                || left.cachedLabel() == null || right.cachedLabel() == null) {
            setStatusTextNow("Wait for both tiles to finish rendering.");
            return;
        }
        if (comparisonDialog == null) {
            comparisonDialog = new ComparisonPreviewDialog(
                    gridWindow == null ? getWindow() : gridWindow);
        }
        PreviewDisplaySettings settings =
                PreviewDisplaySettings.defaultFor(context.channelName());
        VariationComparisonPreview.showVariationComparison(
                comparisonDialog,
                left.cachedLabel(),
                "Variation A " + comboSummary(left.combo()),
                right.cachedLabel(),
                "Variation B " + comboSummary(right.combo()),
                croppedForComparison(context.rawSource()),
                settings,
                croppedForComparison(context.filteredSource()),
                settings,
                currentGridSlice());
    }

    private void disposeCells() {
        for (int i = 0; i < cells.size(); i++) {
            cells.get(i).disposeImages();
        }
    }

    private void disposeGridWindow() {
        if (gridWindow != null) {
            gridWindow.dispose();
            gridWindow = null;
        }
    }

    private int currentGridSlice() {
        return gridWindow == null ? 1 : gridWindow.controllerForTest().currentSlice();
    }

    private void acceptAndClose(ParameterCombo combo) {
        comparisonSelection.clearForAccept();
        try {
            if (onAccept != null) {
                onAccept.accept(combo);
            }
        } finally {
            dispose();
        }
    }

    private void cancelExecutor() {
        VariationExecutor worker = executor;
        if (worker != null && !worker.isDone()) {
            worker.cancel(true);
        }
    }

    private void setStatusTextNow(String text) {
        if (!SwingUtilities.isEventDispatchThread()) {
            final String safeText = text;
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    setStatusTextNow(safeText);
                }
            });
            return;
        }
        statusLabel.setText(safe(text));
        updateProgressSliver();
    }

    private void setSuggestionText(String text) {
        if (!SwingUtilities.isEventDispatchThread()) {
            final String safeText = text;
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    setSuggestionText(safeText);
                }
            });
            return;
        }
        suggestionLabel.setText(safe(text));
        updateProgressSliver();
    }

    private void setStrategyText(String text) {
        if (!SwingUtilities.isEventDispatchThread()) {
            final String safeText = text;
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    setStrategyText(safeText);
                }
            });
            return;
        }
        strategyLabel.setText(safe(text));
        updateProgressSliver();
    }

    private void setStatusText(final String text) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                setStatusTextNow(safe(text));
            }
        });
    }

    private void updateProgressSliver() {
        int total = cells.isEmpty() ? estimatedCellCount() : cells.size();
        progressSliver.setCounts(completedCount, total, failedCount);
        progressSliver.setLineText(progressLine(total));
        progressSliver.setStrategyHint(strategyLabel.getText());
    }

    private int estimatedCellCount() {
        try {
            long count = editor.currentSweep().cellCount();
            return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(0, (int) count);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private String progressLine(int total) {
        if (total > 0 && (isExecutorActive() || completedCount > 0)) {
            if (failedCount > 0 && completedCount >= total) {
                return completedCount + "/" + total + separator()
                        + failedCount + " failed (hover for details)"
                        + separator() + elapsedText();
            }
            if (completedCount >= total) {
                return completedCount + "/" + total + separator()
                        + indicatorSummary() + separator() + elapsedText();
            }
            return completedCount + "/" + total + separator()
                    + "running" + separator() + elapsedText() + " elapsed";
        }
        return safe(statusLabel.getText());
    }

    private String indicatorSummary() {
        List<String> parts = new ArrayList<String>();
        if (stableCountStatus != null && stableCountStatus.length() > 0) {
            parts.add(stableCountStatus);
        }
        if (stableMasksStatus != null && stableMasksStatus.length() > 0) {
            parts.add(stableMasksStatus);
        }
        if (parts.isEmpty()) {
            String current = safe(suggestionLabel.getText());
            return current.length() == 0 ? "running" : current;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                out.append(separator());
            }
            out.append(parts.get(i));
        }
        return out.toString();
    }

    private static String separator() {
        return " \u00b7 ";
    }

    private void runRangeSuggester() {
        if (isExecutorActive()) {
            setStatusTextNow("Stop the running sweep before suggesting ranges.");
            return;
        }
        final ParameterSweep draft;
        try {
            draft = editor.currentSweep();
        } catch (RuntimeException e) {
            showMessage(e.getMessage());
            return;
        }
        suggestButton.setEnabled(false);
        setSuggestionText("Range suggestions working");
        setStatusTextNow("Suggesting ranges");
        SwingWorker<Map<ParameterId, ParameterValueList>, Void> worker =
                new SwingWorker<Map<ParameterId, ParameterValueList>, Void>() {
                    @Override protected Map<ParameterId, ParameterValueList> doInBackground() {
                        return RangeSuggester.suggest(context, draft);
                    }

                    @Override protected void done() {
                        try {
                            Map<ParameterId, ParameterValueList> suggestions = get();
                            editor.applySuggestedValues(suggestions);
                            refreshCellEstimate();
                            setSuggestionText(suggestions.size() + " range rows updated");
                            setStatusTextNow(feasibilitySummary(editor.currentSweep()));
                        } catch (Exception e) {
                            setSuggestionText("Range suggestion failed");
                            setStatusTextNow(feasibilitySummary(draft));
                            showMessage(e.getMessage());
                        } finally {
                            suggestButton.setEnabled(true);
                        }
                    }
                };
        worker.execute();
    }

    private void refreshCellEstimate() {
        try {
            ParameterSweep sweep = editor.currentSweep();
            long count = sweep.cellCount();
            cellsLabel.setText("Cells: " + count);
            if (!isExecutorActive()) {
                setStatusTextNow(feasibilitySummary(sweep));
                setStrategyText(" ");
            }
        } catch (RuntimeException e) {
            cellsLabel.setText("Cells: ?");
            if (!isExecutorActive()) {
                setStatusTextNow("Choose at least one value for each selected parameter.");
                setStrategyText(" ");
            }
        }
    }

    private boolean isExecutorActive() {
        return executor != null && !executor.isDone();
    }

    private int countFailures() {
        int failures = 0;
        for (int i = 0; i < resultsByCell.size(); i++) {
            VariationResult result = resultsByCell.get(i);
            if (result != null && result.hasError()) {
                failures++;
            }
        }
        return failures;
    }

    private String progressStatus(int total, boolean includeElapsed) {
        String base = completedCount + "/" + Math.max(0, total)
                + separator() + "running";
        if (includeElapsed) {
            base += separator() + elapsedText() + " elapsed";
        }
        return statusWithFailures(base);
    }

    private String completionStatus() {
        String base = completedCount + "/" + Math.max(0, cells.size())
                + separator() + indicatorSummary()
                + separator() + elapsedText();
        return statusWithFailures(base);
    }

    private String statusWithFailures(String base) {
        if (failedCount <= 0) {
            return base;
        }
        return completedCount + "/" + Math.max(0, cells.size())
                + separator() + failedCount + " failed (hover for details)"
                + separator() + elapsedText();
    }

    private String elapsedText() {
        long elapsed = runStartedAtMs <= 0L
                ? 0L
                : Math.max(0L, System.currentTimeMillis() - runStartedAtMs);
        return formatDuration(elapsed);
    }

    private String feasibilitySummary(ParameterSweep sweep) {
        if (sweep == null) {
            return "Ready";
        }
        return sweep.cellCount() + " cells, crop " + cropSummary(sweep.cropSpec());
    }

    private String cropSummary(CropSpec spec) {
        ImagePlus source = context.filteredSource();
        if (source == null) {
            return "unknown";
        }
        CropSpec active = spec == null ? CropSpec.full() : spec;
        try {
            Rectangle bounds = active.boundsFor(source);
            return bounds.width + "x" + bounds.height + "x" + sourceSliceCount();
        } catch (RuntimeException e) {
            return "invalid";
        }
    }

    private static String formatDuration(long elapsedMs) {
        long totalSeconds = Math.max(0L, Math.round(elapsedMs / 1000.0d));
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return hours + "h " + minutes + "m " + seconds + "s";
        }
        if (minutes > 0L) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }

    private static String strategyDescription(VariationStrategy strategy, int cellCount) {
        if (strategy instanceof StarDistFastNms) {
            return "Using StarDist fast path (1 inference + parallel NMS for "
                    + cellCount + " cells)";
        }
        if (strategy instanceof CellposePersistent) {
            return "Using Cellpose persistent helper";
        }
        if (strategy instanceof CellposeOneShot) {
            return "Using one-shot fallback";
        }
        if (strategy instanceof StarDistPerCell) {
            return "Using StarDist per-cell fallback";
        }
        if (strategy instanceof ClassicalSweep) {
            return "Using Classical CPU sweep (" + cellCount + " cells)";
        }
        return "Using " + (strategy == null ? "unknown strategy"
                : strategy.getClass().getSimpleName()) + " (" + cellCount + " cells)";
    }

    private String currentImageHash() {
        try {
            return editor.currentSweep().sourceImageHash();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private CropSpec selectedCropSpec() {
        return currentCropSpec;
    }

    private ImagePlus croppedForComparison(ImagePlus source) {
        if (source == null) {
            return null;
        }
        CropSpec spec = currentSweep == null ? currentCropSpec : currentSweep.cropSpec();
        if (spec == null) {
            return source;
        }
        try {
            return spec.apply(source);
        } catch (RuntimeException e) {
            return source;
        }
    }

    private void handleCropSelection(JRadioButton selected) {
        if (suppressCropEvents) {
            return;
        }
        CropSpec previous = currentCropSpec;
        if (selected == fullCrop) {
            currentCropSpec = CropSpec.full();
        } else if (selected == centreCrop) {
            currentCropSpec = CropSpec.centre256();
        } else if (selected == customCrop) {
            ImagePlus source = context.filteredSource();
            if (source == null) {
                showMessage("No source image is available for custom crop selection.");
                currentCropSpec = previous;
                selectCropButton(previous);
                return;
            }
            Rectangle initial = previous.mode() == CropSpec.Mode.CUSTOM
                    ? previous.bounds()
                    : null;
            Rectangle chosen = CustomCropPicker.choose(getWindow(), source, initial);
            if (chosen == null) {
                currentCropSpec = previous;
                selectCropButton(previous);
                editor.setCropSpec(currentCropSpec);
                refreshCellEstimate();
                return;
            }
            currentCropSpec = CropSpec.custom(chosen);
        }
        editor.setCropSpec(currentCropSpec);
        refreshCellEstimate();
    }

    private void selectCropButton(CropSpec spec) {
        suppressCropEvents = true;
        try {
            CropSpec.Mode mode = spec == null ? CropSpec.Mode.CENTRE_256 : spec.mode();
            fullCrop.setSelected(mode == CropSpec.Mode.FULL);
            centreCrop.setSelected(mode == CropSpec.Mode.CENTRE_256);
            customCrop.setSelected(mode == CropSpec.Mode.CUSTOM);
        } finally {
            suppressCropEvents = false;
        }
    }

    private int sourceSliceCount() {
        ImagePlus source = context.filteredSource();
        if (source == null) {
            return 1;
        }
        int slices = Math.max(1, source.getNSlices());
        if (slices <= 1) {
            slices = Math.max(1, source.getStackSize());
        }
        return slices;
    }

    private void showMessage(final String message) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                JOptionPane.showMessageDialog(getWindow(), safe(message),
                        "Parameter Variations", JOptionPane.WARNING_MESSAGE);
            }
        });
    }

    private static final class ProgressSliverPanel extends JPanel {
        private static final Color BAR_FILL = new Color(0x56, 0xB4, 0xE9);
        private static final Color BAR_FAILED = new Color(0xE6, 0x9F, 0x00);
        private static final Color BAR_BACKGROUND = new Color(0x3A, 0x40, 0x46);
        private final JLabel lineLabel = new JLabel("Idle");
        private int completed;
        private int total;
        private int failed;

        ProgressSliverPanel() {
            super(new BorderLayout());
            setOpaque(false);
            lineLabel.setForeground(new Color(0x41, 0x46, 0x4B));
            lineLabel.setFont(lineLabel.getFont().deriveFont(Font.PLAIN, 11f));
            lineLabel.setBorder(BorderFactory.createEmptyBorder(7, 0, 0, 0));
            add(lineLabel, BorderLayout.CENTER);
        }

        void setCounts(int completed, int total, int failed) {
            this.completed = Math.max(0, completed);
            this.total = Math.max(0, total);
            this.failed = Math.max(0, failed);
            repaint();
        }

        void setLineText(String text) {
            lineLabel.setText(safe(text));
        }

        void setStrategyHint(String hint) {
            String safeHint = safe(hint);
            setToolTipText(safeHint.trim().isEmpty() ? null : safeHint);
            lineLabel.setToolTipText(getToolTipText());
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int width = Math.max(0, getWidth());
            int barHeight = 4;
            g2.setColor(BAR_BACKGROUND);
            g2.fillRoundRect(0, 0, width, barHeight, barHeight, barHeight);
            if (total > 0 && width > 0) {
                int completedWidth = Math.min(width,
                        Math.round(width * (completed / (float) total)));
                g2.setColor(BAR_FILL);
                g2.fillRoundRect(0, 0, completedWidth, barHeight, barHeight, barHeight);
                int failedWidth = Math.min(completedWidth,
                        Math.round(width * (failed / (float) total)));
                if (failedWidth > 0) {
                    g2.setColor(BAR_FAILED);
                    g2.fillRect(completedWidth - failedWidth, 0, failedWidth, barHeight);
                }
            }
            g2.dispose();
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String comboSummary(ParameterCombo combo) {
        return combo == null ? "" : combo.toCanonicalJson();
    }

    private String comboSummaryForStatus(ParameterCombo combo) {
        if (combo == null) {
            return "";
        }
        List<ParameterKey> keys = new ArrayList<ParameterKey>();
        if (currentSweep != null) {
            for (Map.Entry<ParameterKey, ParameterValueList> entry
                    : currentSweep.valueLists().entrySet()) {
                ParameterValueList values = entry.getValue();
                if (values != null && values.size() > 1) {
                    keys.add(entry.getKey());
                }
            }
        }
        if (keys.isEmpty()) {
            keys.addAll(combo.values().keySet());
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            ParameterKey key = keys.get(i);
            if (i > 0) {
                out.append(", ");
            }
            out.append(ParameterLabels.shortKey(key))
                    .append("=")
                    .append(formatValue(combo.get(key)));
        }
        return out.toString();
    }

    private static String formatValue(Object value) {
        if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            return String.format(Locale.ROOT, "%.3f", Double.valueOf(number))
                    .replaceAll("0+$", "")
                    .replaceAll("\\.$", "");
        }
        return safe(String.valueOf(value));
    }
}
