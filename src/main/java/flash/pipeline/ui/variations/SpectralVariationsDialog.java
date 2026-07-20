package flash.pipeline.ui.variations;

import flash.pipeline.decontamination.CorrectionFeatureRegistry;
import flash.pipeline.decontamination.SpectralDecontaminationConfig;
import flash.pipeline.decontamination.SpectralMergeRenderer;
import flash.pipeline.ui.FlashTheme;
import flash.pipeline.ui.variations.strategy.SpectralDecontaminationSweep;

import ij.ImagePlus;

import javax.swing.BorderFactory;
import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Spectral-decontamination parameter-variations grid. Mirrors {@link DeconvVariationsDialog}:
 * the user ticks which knobs to sweep (subtraction strength, fit percentile, mask percentile,
 * size, AF mode/window), generates a gap-free centre-out grid of colored "after" merges, then
 * clicks the best cell. The chosen combination is resolved to a {@link SpectralDecontaminationConfig}
 * (via {@link SpectralComboSettings}) and delivered to {@code onAccept}.
 *
 * <p>The single cropped multi-channel source is shared across all combinations; each cell shows the
 * post-correction merge (target green, contaminants palette) and the baseline tile shows the
 * pre-correction merge, so removed bleed-through is visible in the same colour language.</p>
 */
public final class SpectralVariationsDialog extends JDialog {

    private static final int MAX_CELLS = 400;
    private static final String CANCEL_ACTION_KEY = "cancel-spectral-variations";

    private final ParameterSweepEditor editor;
    private final ImagePlus rawCropMultiChannel;
    private final SpectralDecontaminationConfig base;
    private final SpectralPreviewAdapter adapter;
    private final CorrectionFeatureRegistry registry;
    private final Consumer<SpectralDecontaminationConfig> onAccept;

    private final JLabel cellCountLabel = new JLabel();
    private final JButton cancelButton = new JButton("Cancel");
    private final JButton generateButton = new JButton("Generate grid");
    private final List<VariationCellPanel> cells = new ArrayList<VariationCellPanel>();
    private final Map<String, VariationCellPanel> cellsByCombo =
            new HashMap<String, VariationCellPanel>();

    private final ImagePlus beforeMergeRgb;
    private final VariationResult beforeMergeLease;

    private VariationGridWindow gridWindow;
    private VariationExecutor executor;
    private int completedCount;
    private int failedCount;
    private int totalCount;
    private boolean committed;
    private volatile boolean disposed;
    private boolean disposalComplete;
    private final WeakRefreshListener editorChangeListener;
    private final WindowAdapter cancelWindowListener;

    public SpectralVariationsDialog(Window owner,
                                    String channelName,
                                    ImagePlus rawCropMultiChannel,
                                    SpectralDecontaminationConfig base,
                                    Consumer<SpectralDecontaminationConfig> onAccept) {
        super(owner, "Spectral variations - " + safe(channelName), ModalityType.APPLICATION_MODAL);
        if (rawCropMultiChannel == null) {
            throw new IllegalArgumentException("rawCropMultiChannel must not be null");
        }
        if (base == null) {
            throw new IllegalArgumentException("base must not be null");
        }
        this.rawCropMultiChannel = rawCropMultiChannel;
        this.base = base;
        this.onAccept = onAccept;
        this.registry = CorrectionFeatureRegistry.getDefault();
        this.adapter = new SpectralPreviewAdapterImpl(rawCropMultiChannel, base, registry);

        SpectralMergeRenderer.DisplayScales scales =
                SpectralMergeRenderer.computeScales(rawCropMultiChannel, base);
        this.beforeMergeRgb = SpectralMergeRenderer.buildBeforeMerge(
                rawCropMultiChannel, base, scales, "spectral_before_merge");
        this.beforeMergeLease = VariationResult.success(
                ParameterCombo.builder().build(), beforeMergeRgb, 0, 0L, null);
        this.beforeMergeLease.transferOwnership();

        this.editor = ParameterSweepEditor.forSpectral(
                safe(channelName),
                FilterVariationEngineContext.sourceImageHash(rawCropMultiChannel),
                base);
        this.editor.applySuggestedValues(suggestedOptions(base));
        this.editorChangeListener = new WeakRefreshListener(this);
        this.editor.addChangeListener(editorChangeListener);
        buildUi();
        refreshCellCount();
        cancelWindowListener = new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                cancelAndDispose();
            }
        };
        addWindowListener(cancelWindowListener);
    }

    private void buildUi() {
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.setBackground(FlashTheme.SURFACE);

        JLabel intro = new JLabel("<html>Tick the knobs to sweep, set the values, then generate the "
                + "grid. Each cell is the corrected merge (target green, contaminants coloured); the "
                + "first tile is the uncorrected merge. Click the best result to use it.</html>");
        intro.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        root.add(intro, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(editor);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setPreferredSize(new Dimension(560, 320));
        root.add(scroll, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        cellCountLabel.setForeground(FlashTheme.TEXT_SUBHEADER);
        footer.add(cellCountLabel, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setOpaque(false);
        cancelButton.addActionListener(e -> cancelAndDispose());
        generateButton.addActionListener(e -> generateGrid());
        buttons.add(cancelButton);
        buttons.add(generateButton);
        footer.add(buttons, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);

        setContentPane(root);
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), CANCEL_ACTION_KEY);
        getRootPane().getActionMap().put(CANCEL_ACTION_KEY, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                cancelAndDispose();
            }
        });
        pack();
        setLocationRelativeTo(getOwner());
    }

    private void refreshCellCount() {
        SpectralSweepPlan plan;
        try {
            plan = SpectralSweepPlan.forSweep(editor.currentSweep(), base, registry, MAX_CELLS);
        } catch (SpectralSweepPlan.TooManyCombinationsException e) {
            cellCountLabel.setText(formatTooManyVariations(e.rawCount(), e.maxRawCombos()));
            generateButton.setEnabled(false);
            return;
        } catch (RuntimeException e) {
            plan = null;
        }
        long count = plan == null ? 0L : plan.executableCount();
        cellCountLabel.setText(count + (count == 1 ? " variation" : " variations"));
        if (plan != null && plan.skippedCount() > 0) {
            cellCountLabel.setText(cellCountLabel.getText() + " ("
                    + plan.skippedCount() + " skipped)");
        }
        generateButton.setEnabled(count >= 1 && count <= MAX_CELLS);
        if (count > MAX_CELLS) {
            cellCountLabel.setText(count + " variations - reduce the sweep (max " + MAX_CELLS + ")");
        }
    }

    private void generateGrid() {
        ParameterSweep sweep = editor.currentSweep();
        SpectralSweepPlan plan;
        try {
            plan = SpectralSweepPlan.forSweep(sweep, base, registry, MAX_CELLS);
        } catch (SpectralSweepPlan.TooManyCombinationsException e) {
            cellCountLabel.setText(formatTooManyVariations(e.rawCount(), e.maxRawCombos()));
            generateButton.setEnabled(false);
            return;
        }
        List<ParameterCombo> combos = plan.executableCombos();
        long count = combos.size();
        if (count < 1 || count > MAX_CELLS) {
            return;
        }
        cancelRun();
        cells.clear();
        cellsByCombo.clear();
        completedCount = 0;
        failedCount = 0;
        totalCount = combos.size();
        final List<ParameterKey> footerKeys = sweep.parameterKeys();

        VariationCellPanel baseline = VariationCellPanel.baseline(beforeMergeRgb);
        baseline.setZ(1);
        cells.add(baseline);
        for (int i = 0; i < combos.size(); i++) {
            final ParameterCombo combo = combos.get(i);
            VariationCellPanel cell = new VariationCellPanel(combo, beforeMergeRgb,
                    new Consumer<ParameterCombo>() {
                        @Override public void accept(ParameterCombo accepted) {
                            commit(accepted);
                        }
                    },
                    null,
                    i);
            cell.setOnPickCommit(new Consumer<ParameterCombo>() {
                @Override public void accept(ParameterCombo accepted) {
                    commit(accepted);
                }
            });
            cell.setState("running");
            cell.setZ(1);
            cell.setOverlayMode(VariationCellPanel.OverlayMode.NONE);
            cell.setFooterParameterKeys(footerKeys);
            cells.add(cell);
            cellsByCombo.put(combo.toCanonicalJson(), cell);
        }

        gridWindow = new VariationGridWindow(this,
                "Spectral variations (" + totalCount + ")", cells);
        gridWindow.setSliceMax(cropSliceCount());
        gridWindow.setCompletedCount(0, totalCount, 0);
        gridWindow.setVisible(true);

        SpectralDecontaminationSweep strategy =
                new SpectralDecontaminationSweep(rawCropMultiChannel, adapter, base, combos);
        executor = new VariationExecutor(sweep, strategy, null,
                new BiConsumer<VariationResult, Integer>() {
                    @Override public void accept(VariationResult result, Integer index) {
                        handleResult(result);
                    }
                },
                null);
        executor.execute();
    }

    private static String formatTooManyVariations(long count, long max) {
        String countText = count == Long.MAX_VALUE ? "Too many" : String.valueOf(count);
        return countText + " variations - reduce the sweep (max " + max + ")";
    }

    private void handleResult(VariationResult result) {
        if (result == null) {
            return;
        }
        if (disposed) {
            VariationCleanupSupport.rethrow(
                    VariationCleanupSupport.disposeRejectedResult(result));
            return;
        }
        VariationCellPanel cell = cellsByCombo.get(result.combo().toCanonicalJson());
        if (cell == null) {
            VariationCleanupSupport.rethrow(
                    VariationCleanupSupport.disposeRejectedResult(result));
            return;
        }
        cell.setFilterResult(result);
        if (result.hasError()) {
            failedCount++;
        }
        completedCount++;
        if (gridWindow != null) {
            gridWindow.setSliceMax(cropSliceCount());
            gridWindow.setCompletedCount(completedCount, totalCount, failedCount);
        }
    }

    void handleResultForTest(VariationResult result) {
        handleResult(result);
    }

    private int cropSliceCount() {
        int slices = Math.max(1, beforeMergeRgb.getNSlices());
        if (slices <= 1) {
            slices = Math.max(1, beforeMergeRgb.getStackSize());
        }
        return slices;
    }

    private void commit(ParameterCombo combo) {
        if (committed || combo == null) {
            return;
        }
        committed = true;
        SpectralDecontaminationConfig chosen = SpectralComboSettings.resolve(combo, base);
        cancelRun();
        try {
            if (onAccept != null) {
                onAccept.accept(chosen);
            }
        } finally {
            dispose();
        }
    }

    private void cancelAndDispose() {
        dispose();
    }

    private void cancelRun() {
        if (executor != null) {
            executor.cancel(true);
            executor = null;
        }
        Throwable failure = null;
        try {
            disposeCells();
        } catch (Throwable cellFailure) {
            failure = VariationCleanupSupport.merge(failure, cellFailure);
            VariationCleanupSupport.rethrowFatalAfterRegisteringCells(failure, cells);
        }
        if (gridWindow != null) {
            gridWindow.dispose();
            gridWindow = null;
        }
        if (cellsCleanupComplete()) {
            cells.clear();
            cellsByCombo.clear();
        }
        VariationCleanupSupport.rethrow(failure);
    }

    private void disposeCells() {
        VariationCellPanel.disposeAllImages(cells);
    }

    private boolean cellsCleanupComplete() {
        for (int i = 0; i < cells.size(); i++) {
            if (!cells.get(i).terminalCleanupComplete()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void dispose() {
        if (!SwingUtilities.isEventDispatchThread()) {
            VariationCleanupSupport.runOnEdtAndWait(
                    new VariationCleanupSupport.Task() {
                        @Override public void run() {
                            dispose();
                        }
                    });
            return;
        }
        if (disposalComplete) {
            return;
        }
        disposed = true;
        Throwable failure = null;
        try {
            cancelRun();
        } catch (Throwable cleanupFailure) {
            failure = VariationCleanupSupport.merge(failure, cleanupFailure);
            retainBeforeMergeAfterFatal(failure);
            VariationCleanupSupport.rethrowFatalAfterRegisteringCells(failure, cells);
        }
        VariationCleanupCoordinator.registerCells(cells);
        cells.clear();
        cellsByCombo.clear();
        try {
            editorChangeListener.detach();
            removeWindowListener(cancelWindowListener);
            removeActionListeners(cancelButton);
            removeActionListeners(generateButton);
            getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).remove(
                    KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
            getRootPane().getActionMap().remove(CANCEL_ACTION_KEY);
            super.dispose();
        } catch (Throwable windowFailure) {
            failure = VariationCleanupSupport.merge(failure, windowFailure);
            retainBeforeMergeAfterFatal(failure);
            VariationCleanupSupport.rethrowFatalAfterRegisteringCells(failure, cells);
        }
        failure = VariationCleanupSupport.merge(failure,
                VariationCleanupSupport.disposeRejectedResult(beforeMergeLease));
        VariationCleanupSupport.rethrowFatalAfterRegisteringCells(failure, cells);
        VariationCleanupCoordinator.registerResult(beforeMergeLease);
        disposalComplete = true;
        VariationCleanupSupport.rethrow(failure);
    }

    private void retainBeforeMergeAfterFatal(Throwable failure) {
        if (VariationCleanupSupport.isVmFatal(failure)) {
            VariationCleanupSupport.retainRejectedResultAfterFatal(beforeMergeLease);
        }
    }

    private static void removeActionListeners(JButton button) {
        ActionListener[] listeners = button.getActionListeners();
        for (int i = 0; i < listeners.length; i++) {
            button.removeActionListener(listeners[i]);
        }
    }

    private static Map<ParameterKey, ParameterValueList> suggestedOptions(SpectralDecontaminationConfig base) {
        Map<ParameterKey, ParameterValueList> out =
                new LinkedHashMap<ParameterKey, ParameterValueList>();
        Object baseMode = SpectralComboSettings.baseValueFor(base, SpectralParameterId.AF_MODE);
        List<String> modes = orderWithBaseFirst(
                java.util.Arrays.asList(SpectralParameterId.AF_MODE_GLOBAL, SpectralParameterId.AF_MODE_LOCAL),
                baseMode instanceof String ? (String) baseMode : SpectralParameterId.AF_MODE_GLOBAL);
        if (!modes.isEmpty()) {
            out.put(SpectralParameterId.AF_MODE, ParameterValueList.of(modes));
        }
        return out;
    }

    /** Returns the options with {@code first} at index 0 (the un-swept default), no duplicates. */
    private static List<String> orderWithBaseFirst(List<String> options, String first) {
        List<String> out = new ArrayList<String>();
        if (first != null && !first.trim().isEmpty()) {
            out.add(first);
        }
        if (options != null) {
            for (int i = 0; i < options.size(); i++) {
                String value = options.get(i);
                if (value != null && !value.trim().isEmpty() && !out.contains(value)) {
                    out.add(value);
                }
            }
        }
        return out;
    }

    ParameterSweepEditor editorForTest() {
        return editor;
    }

    JButton generateButtonForTest() {
        return generateButton;
    }

    JButton cancelButtonForTest() {
        return cancelButton;
    }

    void commitForTest(ParameterCombo combo) {
        commit(combo);
    }

    boolean editorListenerDetachedForTest() {
        return editorChangeListener.isDetached();
    }

    ImagePlus beforeMergeForTest() {
        return beforeMergeRgb;
    }

    VariationGridWindow gridWindowForTest() {
        return gridWindow;
    }

    List<VariationCellPanel> cellsForTest() {
        return new ArrayList<VariationCellPanel>(cells);
    }

    int completedCountForTest() {
        return completedCount;
    }

    int failedCountForTest() {
        return failedCount;
    }

    void waitForDoneForTest(long timeoutMs) throws Exception {
        VariationExecutor worker = executor;
        if (worker != null) {
            worker.get(timeoutMs, TimeUnit.MILLISECONDS);
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && completedCount < totalCount) {
            EventQueue.invokeAndWait(new Runnable() {
                @Override public void run() {
                }
            });
            if (completedCount >= totalCount) {
                return;
            }
            Thread.sleep(10L);
        }
        EventQueue.invokeAndWait(new Runnable() {
            @Override public void run() {
            }
        });
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class WeakRefreshListener implements ChangeListener {
        private final WeakReference<SpectralVariationsDialog> dialog;

        private WeakRefreshListener(SpectralVariationsDialog dialog) {
            this.dialog = new WeakReference<SpectralVariationsDialog>(dialog);
        }

        @Override public void stateChanged(ChangeEvent e) {
            SpectralVariationsDialog target = dialog.get();
            if (target != null && !target.disposed) {
                target.refreshCellCount();
            }
        }

        private void detach() {
            dialog.clear();
        }

        private boolean isDetached() {
            return dialog.get() == null;
        }
    }
}
