package flash.pipeline.ui.variations;

import flash.pipeline.deconv.engine.DeconvSettings;
import flash.pipeline.image.ImageOps;
import flash.pipeline.ui.FlashTheme;
import flash.pipeline.ui.variations.strategy.DeconvolutionSweep;

import ij.IJ;
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
 * Deconvolution parameter-variations grid. Looks and behaves like the segmentation
 * variations grid (gap-free centre-out fill, scrollable Z, click a cell to choose it)
 * but is self-contained: it composes the reusable grid components
 * ({@link ParameterSweepEditor}, {@link VariationExecutor}, {@link VariationGridWindow},
 * {@link VariationCellPanel}) directly and never touches the Set-Up-Configuration
 * {@code ConfigQcContext}.
 *
 * <p>The user toggles which axes (engine, algorithm, iterations, regularization, PSF)
 * to sweep, generates the grid, then clicks the best cell; the chosen
 * {@link DeconvSettings} is delivered to {@code onAccept} and the dialog closes.
 */
public final class DeconvVariationsDialog extends JDialog {

    private static final int MAX_CELLS = 400;
    private static final String CANCEL_ACTION_KEY = "cancel-deconvolution-variations";

    private final ParameterSweepEditor editor;
    private final ImagePlus rawCrop;
    private final DeconvSettings base;
    private final DeconvolutionPreviewAdapter adapter;
    private final Consumer<DeconvSettings> onAccept;

    private final JLabel cellCountLabel = new JLabel();
    private final JButton cancelButton = new JButton("Cancel");
    private final JButton generateButton = new JButton("Generate grid");
    private final List<VariationCellPanel> cells = new ArrayList<VariationCellPanel>();
    private final Map<String, VariationCellPanel> cellsByCombo =
            new HashMap<String, VariationCellPanel>();

    private VariationGridWindow gridWindow;
    private VariationExecutor executor;
    private PhysicalRunStrategy physicalRun;
    private int completedCount;
    private int failedCount;
    private int totalCount;
    private boolean committed;
    private volatile boolean disposed;
    private boolean disposalComplete;
    private boolean showOtsuOverlay;
    private final WeakRefreshListener editorChangeListener;
    private final WindowAdapter cancelWindowListener;

    public DeconvVariationsDialog(Window owner,
                                  String channelName,
                                  ImagePlus rawCrop,
                                  DeconvSettings base,
                                  DeconvolutionPreviewAdapter adapter,
                                  List<String> engineKeys,
                                  List<String> algorithmNames,
                                  List<String> psfNames,
                                  Consumer<DeconvSettings> onAccept) {
        super(owner, "Deconvolution variations - " + safe(channelName),
                ModalityType.APPLICATION_MODAL);
        if (rawCrop == null) {
            throw new IllegalArgumentException("rawCrop must not be null");
        }
        if (base == null) {
            throw new IllegalArgumentException("base must not be null");
        }
        if (adapter == null) {
            throw new IllegalArgumentException("adapter must not be null");
        }
        this.rawCrop = rawCrop;
        this.base = base;
        this.adapter = adapter;
        this.onAccept = onAccept;
        this.editor = ParameterSweepEditor.forDeconvolution(
                safe(channelName),
                FilterVariationEngineContext.sourceImageHash(rawCrop),
                base);
        this.editor.applySuggestedValues(suggestedOptions(engineKeys, algorithmNames, psfNames, base));
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

        JLabel intro = new JLabel("<html>Tick the axes to sweep, set the values, then "
                + "generate the grid. Click the best result to use it.</html>");
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
        DeconvSweepPlan plan;
        try {
            plan = DeconvSweepPlan.forSweep(editor.currentSweep(), base, MAX_CELLS);
        } catch (DeconvSweepPlan.TooManyCombinationsException e) {
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
                    + plan.skippedCount() + " unsupported skipped)");
        }
        generateButton.setEnabled(count >= 1 && count <= MAX_CELLS);
        if (count > MAX_CELLS) {
            cellCountLabel.setText(count + " variations - reduce the sweep (max " + MAX_CELLS + ")");
        }
    }

    private void generateGrid() {
        ParameterSweep sweep = editor.currentSweep();
        DeconvSweepPlan plan;
        try {
            plan = DeconvSweepPlan.forSweep(sweep, base, MAX_CELLS);
        } catch (DeconvSweepPlan.TooManyCombinationsException e) {
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

        VariationCellPanel baseline = VariationCellPanel.baseline(rawCrop);
        baseline.setZ(1);
        cells.add(baseline);
        for (int i = 0; i < combos.size(); i++) {
            final ParameterCombo combo = combos.get(i);
            VariationCellPanel cell = new VariationCellPanel(combo, rawCrop,
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
            cell.setOverlayMode(currentOverlayMode());
            cell.setFooterParameterKeys(footerKeys);
            cells.add(cell);
            cellsByCombo.put(combo.toCanonicalJson(), cell);
        }

        gridWindow = new VariationGridWindow(this,
                "Deconvolution variations (" + totalCount + ")", cells);
        gridWindow.setOtsuOverlaySelected(showOtsuOverlay);
        gridWindow.attachOtsuOverlayActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                setShowOtsuOverlay(gridWindow != null
                        && gridWindow.otsuOverlayCheckBoxForTest().isSelected());
            }
        });
        gridWindow.setSliceMax(rawCropSliceCount());
        gridWindow.setCompletedCount(0, totalCount, 0);
        gridWindow.setVisible(true);

        try {
            physicalRun = PhysicalRunStrategy.create(rawCrop, adapter, base, combos);
        } catch (Throwable failure) {
            rethrowIfVmFatal(failure);
            cellCountLabel.setText("Could not start variations: " + errorMessage(failure));
            IJ.log("Could not create an owned deconvolution variations input: "
                    + errorMessage(failure));
            disposeCells();
            if (gridWindow != null) {
                gridWindow.dispose();
                gridWindow = null;
            }
            cells.clear();
            cellsByCombo.clear();
            return;
        }
        try {
            executor = new VariationExecutor(sweep, physicalRun, null,
                    new BiConsumer<VariationResult, Integer>() {
                        @Override public void accept(VariationResult result, Integer index) {
                            handleResult(result);
                        }
                    },
                    null);
            executor.execute();
        } catch (Throwable failure) {
            VariationExecutor failedExecutor = executor;
            executor = null;
            if (failedExecutor != null) {
                failedExecutor.cancel(true);
            }
            PhysicalRunStrategy failedRun = physicalRun;
            physicalRun = null;
            Throwable cleanupFailure = failedRun == null
                    ? null : failedRun.cancelBeforeStart();
            Throwable outcome = combineFailures(failure, cleanupFailure);
            rethrowIfVmFatal(outcome);
            cellCountLabel.setText("Could not start variations: " + errorMessage(outcome));
            IJ.log("Could not start deconvolution variations: " + errorMessage(outcome));
        }
    }

    private static String formatTooManyVariations(long count, long max) {
        String countText = count == Long.MAX_VALUE ? "Too many" : String.valueOf(count);
        return countText + " variations - reduce the sweep (max " + max + ")";
    }

    private void setShowOtsuOverlay(boolean show) {
        showOtsuOverlay = show;
        if (gridWindow != null) {
            gridWindow.setOtsuOverlaySelected(show);
        }
        VariationCellPanel.OverlayMode mode = currentOverlayMode();
        for (VariationCellPanel cell : cellsByCombo.values()) {
            if (cell != null) {
                cell.setOverlayMode(mode);
            }
        }
    }

    private VariationCellPanel.OverlayMode currentOverlayMode() {
        return showOtsuOverlay
                ? VariationCellPanel.OverlayMode.OTSU_MASK
                : VariationCellPanel.OverlayMode.NONE;
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
            gridWindow.setSliceMax(rawCropSliceCount());
            gridWindow.setCompletedCount(completedCount, totalCount, failedCount);
        }
    }

    void handleResultForTest(VariationResult result) {
        handleResult(result);
    }

    private int rawCropSliceCount() {
        int slices = Math.max(1, rawCrop.getNSlices());
        if (slices <= 1) {
            slices = Math.max(1, rawCrop.getStackSize());
        }
        return slices;
    }

    private void commit(ParameterCombo combo) {
        if (committed || combo == null) {
            return;
        }
        committed = true;
        DeconvSettings chosen = DeconvComboSettings.resolve(combo, base);
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
        PhysicalRunStrategy run = physicalRun;
        physicalRun = null;
        Throwable preStartCleanupFailure = run == null ? null : run.cancelBeforeStart();
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
        if (preStartCleanupFailure != null) {
            failure = VariationCleanupSupport.merge(failure,
                    preStartCleanupFailure);
            VariationCleanupSupport.rethrowFatalAfterRegisteringCells(failure, cells);
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
            VariationCleanupSupport.rethrowFatalAfterRegisteringCells(failure, cells);
        }
        disposalComplete = true;
        VariationCleanupSupport.rethrow(failure);
    }

    private static void removeActionListeners(JButton button) {
        ActionListener[] listeners = button.getActionListeners();
        for (int i = 0; i < listeners.length; i++) {
            button.removeActionListener(listeners[i]);
        }
    }

    private static Map<ParameterKey, ParameterValueList> suggestedOptions(List<String> engineKeys,
                                                                          List<String> algorithmNames,
                                                                         List<String> psfNames,
                                                                         DeconvSettings base) {
        Map<ParameterKey, ParameterValueList> out =
                new LinkedHashMap<ParameterKey, ParameterValueList>();
        List<String> engines = orderWithBaseFirst(engineKeys, base.engineKey());
        if (!engines.isEmpty()) {
            out.put(DeconvParameterId.ENGINE, ParameterValueList.of(engines));
        }
        List<String> algorithms = orderWithBaseFirst(algorithmNames,
                base.algorithm() == null ? null : base.algorithm().name());
        if (!algorithms.isEmpty()) {
            out.put(DeconvParameterId.ALGORITHM, ParameterValueList.of(algorithms));
        }
        List<String> psfs = orderWithBaseFirst(psfNames,
                base.psfModel() == null ? null : base.psfModel().name());
        if (!psfs.isEmpty()) {
            out.put(DeconvParameterId.PSF_MODEL, ParameterValueList.of(psfs));
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
        while (System.currentTimeMillis() < deadline
                && completedCount < totalCount) {
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

    /**
     * Owns pixels independently of the modal dialog until the strategy physically returns.
     * SwingWorker's cancelled/DONE state is intentionally not used as a physical-exit signal.
     */
    static final class PhysicalRunStrategy implements VariationStrategy {
        private static final int NEW = 0;
        private static final int RUNNING = 1;
        private static final int FINISHED = 2;

        private final ImagePlus runCrop;
        private final DeconvolutionPreviewAdapter adapter;
        private final DeconvolutionSweep delegate;
        private int state = NEW;

        private PhysicalRunStrategy(ImagePlus runCrop,
                                    DeconvolutionPreviewAdapter adapter,
                                    DeconvSettings base,
                                    List<ParameterCombo> combos) {
            this.runCrop = runCrop;
            this.adapter = adapter;
            this.delegate = new DeconvolutionSweep(runCrop, null,
                    new PhysicalCallAdapter(adapter), base, combos);
        }

        static PhysicalRunStrategy create(ImagePlus modalCrop,
                                          DeconvolutionPreviewAdapter adapter,
                                          DeconvSettings base,
                                          List<ParameterCombo> combos) {
            if (modalCrop == null) {
                throw new IllegalArgumentException("modalCrop must not be null");
            }
            if (adapter == null) {
                throw new IllegalArgumentException("adapter must not be null");
            }
            if (base == null) {
                throw new IllegalArgumentException("base must not be null");
            }
            ImagePlus runCrop = ImageOps.duplicateThreadSafe(modalCrop);
            if (runCrop == null) {
                throw new IllegalStateException(
                        "Could not duplicate the deconvolution variations input.");
            }
            try {
                return new PhysicalRunStrategy(runCrop, adapter, base, combos);
            } catch (Throwable failure) {
                Throwable cleanupFailure = closeOwned(adapter, runCrop, failure);
                throwUnchecked(combineFailures(failure, cleanupFailure));
                return null;
            }
        }

        @Override public void dispatch(ParameterSweep sweep,
                                       Consumer<VariationResult> publisher,
                                       java.util.function.BooleanSupplier cancelCheck)
                throws Exception {
            synchronized (this) {
                if (state == FINISHED) {
                    return;
                }
                if (state != NEW) {
                    throw new IllegalStateException("A physical variations run cannot be reused.");
                }
                state = RUNNING;
            }

            Throwable primaryFailure = null;
            try {
                delegate.dispatch(sweep, publisher, cancelCheck);
            } catch (Throwable failure) {
                primaryFailure = failure;
            }
            Throwable cleanupFailure = closeOwned(adapter, runCrop, primaryFailure);
            synchronized (this) {
                state = FINISHED;
                notifyAll();
            }
            throwFailure(combineFailures(primaryFailure, cleanupFailure));
        }

        /** Releases a queued run, or leaves a physically running call to release itself. */
        Throwable cancelBeforeStart() {
            synchronized (this) {
                if (state != NEW) {
                    return null;
                }
                state = FINISHED;
                notifyAll();
            }
            return closeOwned(adapter, runCrop, null);
        }

        synchronized boolean physicalDoneForTest() {
            return state == FINISHED;
        }
    }

    /** Gives each engine invocation its own input and retains it until physical return. */
    private static final class PhysicalCallAdapter implements DeconvolutionPreviewAdapter {
        private final DeconvolutionPreviewAdapter delegate;

        private PhysicalCallAdapter(DeconvolutionPreviewAdapter delegate) {
            this.delegate = delegate;
        }

        @Override public ImagePlus deconvolvePreview(ImagePlus runCrop,
                                                     DeconvSettings settings)
                throws Exception {
            ImagePlus callCrop = ImageOps.duplicateThreadSafe(runCrop);
            if (callCrop == null) {
                throw new IllegalStateException(
                        "Could not duplicate the deconvolution engine input.");
            }

            ImagePlus output = null;
            Throwable primaryFailure = null;
            try {
                output = delegate.deconvolvePreview(callCrop, settings);
                if (output == callCrop) {
                    throw new IllegalStateException(
                            "A deconvolution preview must return a new image.");
                }
            } catch (Throwable failure) {
                primaryFailure = failure;
            }

            Throwable inputCleanupFailure = closeOwned(delegate, callCrop, primaryFailure);
            Throwable outcome = combineFailures(primaryFailure, inputCleanupFailure);
            if (outcome != null && output != null && output != callCrop) {
                Throwable outputCleanupFailure = closeOwned(delegate, output, outcome);
                outcome = combineFailures(outcome, outputCleanupFailure);
            }
            throwFailure(outcome);
            return output;
        }

        @Override public void close(ImagePlus image) {
            delegate.close(image);
        }
    }

    /** Clears cancellation while ImageJ releases pixels, then restores it for the caller. */
    private static Throwable closeOwned(DeconvolutionPreviewAdapter adapter,
                                        ImagePlus image,
                                        Throwable precedingFailure) {
        boolean interrupted = precedingFailure instanceof InterruptedException;
        interrupted = Thread.interrupted() || interrupted;
        Throwable cleanupFailure = null;
        if (image != null) {
            try {
                adapter.close(image);
            } catch (Throwable failure) {
                cleanupFailure = failure;
            }
        }
        interrupted = Thread.interrupted() || interrupted;
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return cleanupFailure;
    }

    private static Throwable combineFailures(Throwable primaryFailure,
                                             Throwable cleanupFailure) {
        if (primaryFailure == null) {
            return cleanupFailure;
        }
        if (cleanupFailure == null || cleanupFailure == primaryFailure) {
            return primaryFailure;
        }
        if (isVmFatal(cleanupFailure) && !isVmFatal(primaryFailure)) {
            cleanupFailure.addSuppressed(primaryFailure);
            return cleanupFailure;
        }
        primaryFailure.addSuppressed(cleanupFailure);
        return primaryFailure;
    }

    private static void throwFailure(Throwable failure) throws Exception {
        if (failure == null) {
            return;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        if (failure instanceof Exception) {
            throw (Exception) failure;
        }
        throw new RuntimeException(failure);
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        throw new IllegalStateException(failure);
    }

    private static void rethrowIfVmFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError) {
            throw (VirtualMachineError) failure;
        }
        if (failure instanceof ThreadDeath) {
            throw (ThreadDeath) failure;
        }
    }

    private static boolean isVmFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    private static String errorMessage(Throwable failure) {
        if (failure == null) {
            return "unknown error";
        }
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty()
                ? failure.getClass().getSimpleName() : message.trim();
    }

    private static final class WeakRefreshListener implements ChangeListener {
        private final WeakReference<DeconvVariationsDialog> dialog;

        private WeakRefreshListener(DeconvVariationsDialog dialog) {
            this.dialog = new WeakReference<DeconvVariationsDialog>(dialog);
        }

        @Override public void stateChanged(ChangeEvent e) {
            DeconvVariationsDialog target = dialog.get();
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
