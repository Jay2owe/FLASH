package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.image.FilterMacroParser;
import flash.pipeline.image.FilterMacroParser.OpType;
import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.preview.PipelineFigureExporter;
import flash.pipeline.ui.sandbox.FilterAlternatives;
import flash.pipeline.ui.sandbox.FilterAlternatives.SlotRole;
import flash.pipeline.ui.variations.strategy.FilterSweepStrategy;

import ij.ImagePlus;
import ij.io.FileInfo;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class MacroVariationsDialog extends PipelineDialog {

    public enum Mode {
        SWEEP_PARAMETER,
        SWEEP_STEP,
        SWEEP_PRESETS,
        FULL_SWEEP
    }

    private static final String CARD_SWEEP_PARAMETER = "sweep-parameter";
    private static final String CARD_SWEEP_STEP = "sweep-step";
    private static final String CARD_SWEEP_PRESETS = "sweep-presets";
    private static final String CARD_FULL_SWEEP = "full-sweep";
    private static final long CACHE_PURGE_MAX_AGE_MILLIS =
            7L * 24L * 60L * 60L * 1000L;

    private final FilterVariationEngineContext context;
    private final Consumer<String> onAccept;
    private final ParameterSweepEditor editor;
    private final PresetPickerPanel presetEditor;
    private final SweepRangeEditor sweepRangeEditor;
    private final StepSwapEditor stepSwapEditor;
    private final CardLayout editorCards = new CardLayout();
    private final JPanel editorCardsPanel = new JPanel(editorCards);
    private final ChainRibbon chainRibbon;
    private final JLabel cellsLabel = new JLabel("Cells: 1");
    private final JLabel suggestionLabel = new JLabel("Most stable: pending");
    private final JLabel statusLabel = new JLabel("Idle");
    private final JLabel strategyLabel = new JLabel(" ");
    private final JLabel chainRibbonLabel = new JLabel();
    private final JRadioButton fullCrop = new JRadioButton("full image");
    private final JRadioButton centreCrop = new JRadioButton("centered 256 x 256");
    private final JRadioButton customCrop = new JRadioButton("custom...");
    private final JToggleButton sweepParamButton = new JToggleButton("Sweep parameter");
    private final JToggleButton sweepStepButton = new JToggleButton("Sweep step");
    private final JToggleButton sweepPresetsButton = new JToggleButton("Sweep presets");
    private final JToggleButton fullSweepButton = new JToggleButton("Full sweep");
    private final Map<String, VariationCellPanel> cellsByCombo =
            new HashMap<String, VariationCellPanel>();
    private final Map<String, Integer> cellIndexesByCombo =
            new HashMap<String, Integer>();
    private final List<VariationCellPanel> cells = new ArrayList<VariationCellPanel>();
    private final List<VariationResult> resultsByCell =
            new ArrayList<VariationResult>();
    private final Map<String, DownstreamVerdict.Verdict> downstreamVerdicts =
            new HashMap<String, DownstreamVerdict.Verdict>();
    private final DownstreamSegmenter.Resolution downstreamResolution;

    private JButton exportPipelineFigureButton;
    private JButton useComboButton;
    private JButton runButton;
    private VariationExecutor executor;
    private SwingWorker<Map<ParameterCombo, DownstreamVerdict.Verdict>, Void>
            downstreamWorker;
    private ParameterSweep currentSweep;
    private VariationCache currentRunCache;
    private ImagePlus currentBaselineCrop;
    private VariationGridWindow gridWindow;
    private Mode mode = Mode.SWEEP_PARAMETER;
    private CropSpec currentCropSpec;
    private ParameterCombo selectedCombo;
    private FilterSweepStrategy selectedStrategy;
    private VariationCellPanel selectedCell;
    private boolean showOtsuOverlay;
    private boolean downstreamVerdictSelected;
    private boolean suppressCropEvents;
    private boolean downstreamStartedForCurrentResults;
    private File pipelineFigureExportFileForTest;
    private int completedCount;
    private int failedCount;

    public MacroVariationsDialog(Window owner,
                                 FilterVariationEngineContext context,
                                 Consumer<String> onAccept) {
        super(owner, "Macro Variations");
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context;
        this.onAccept = onAccept;
        this.editor = ParameterSweepEditor.forFilter(context);
        this.presetEditor = new PresetPickerPanel(context);
        this.sweepRangeEditor = new SweepRangeEditor(context);
        this.stepSwapEditor = new StepSwapEditor(context);
        this.chainRibbon = new ChainRibbon(context.baseMacro());
        this.downstreamResolution = DownstreamSegmenter.resolve(context);
        this.editor.setSelectedChainStepIndexes(Collections.<Integer>emptySet());
        this.currentCropSpec = context.initialCropSpec();
        scheduleCachePurge();
        setDefaultButtonsVisible(false);
        buildUi();
        installWindowCleanup();
        applyInteractionModeForMode();
        refreshCellEstimate();
    }

    public void dispose() {
        cancelExecutor();
        cancelDownstreamWorker();
        disposeGridWindow();
        disposeCells();
        Window window = getWindow();
        if (window != null) {
            window.dispose();
        }
    }

    public void setMode(Mode mode) {
        Mode requested = mode == null ? Mode.SWEEP_PARAMETER : mode;
        if (this.mode == requested) {
            modeButtonFor(requested).setSelected(true);
            if ((requested == Mode.SWEEP_PARAMETER || requested == Mode.SWEEP_STEP)
                    && chainRibbon.focusedStepIndex() < 0) {
                defaultFocusFirstFocusableStep();
            }
            refreshCellEstimate();
            return;
        }
        this.mode = requested;
        sweepParamButton.setSelected(requested == Mode.SWEEP_PARAMETER);
        sweepStepButton.setSelected(requested == Mode.SWEEP_STEP);
        sweepPresetsButton.setSelected(requested == Mode.SWEEP_PRESETS);
        fullSweepButton.setSelected(requested == Mode.FULL_SWEEP);
        showEditorCardFor(requested);
        applyInteractionModeForMode();
        if (requested == Mode.SWEEP_PARAMETER || requested == Mode.SWEEP_STEP) {
            configureFocusabilityFor(requested);
            if (!defaultFocusFirstFocusableStep()) {
                refreshCellEstimate();
            }
        } else {
            refreshCellEstimate();
        }
    }

    private JToggleButton modeButtonFor(Mode value) {
        if (value == Mode.SWEEP_STEP) {
            return sweepStepButton;
        }
        if (value == Mode.SWEEP_PRESETS) {
            return sweepPresetsButton;
        }
        if (value == Mode.FULL_SWEEP) {
            return fullSweepButton;
        }
        return sweepParamButton;
    }

    private void applyInteractionModeForMode() {
        if (chainRibbon == null) {
            return;
        }
        switch (mode) {
            case SWEEP_PARAMETER:
            case SWEEP_STEP:
                chainRibbon.setInteractionMode(ChainRibbon.InteractionMode.FOCUS);
                break;
            case FULL_SWEEP:
                chainRibbon.setInteractionMode(ChainRibbon.InteractionMode.SWEEP_TOGGLE);
                break;
            case SWEEP_PRESETS:
            default:
                chainRibbon.setInteractionMode(ChainRibbon.InteractionMode.PASSIVE);
                break;
        }
    }

    private void scheduleCachePurge() {
        final File binFolder = context.binFolder();
        if (binFolder == null) {
            return;
        }
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                VariationCache.purgeOlderThan(binFolder,
                        CACHE_PURGE_MAX_AGE_MILLIS);
            }
        }, "flash-variations-cache-purge");
        worker.setDaemon(true);
        worker.start();
    }

    private void showEditorCardFor(Mode requested) {
        String card;
        switch (requested) {
            case SWEEP_STEP:
                card = CARD_SWEEP_STEP;
                break;
            case SWEEP_PRESETS:
                card = CARD_SWEEP_PRESETS;
                break;
            case FULL_SWEEP:
                card = CARD_FULL_SWEEP;
                break;
            case SWEEP_PARAMETER:
            default:
                card = CARD_SWEEP_PARAMETER;
                break;
        }
        editorCards.show(editorCardsPanel, card);
        Window window = getWindow();
        if (window != null) {
            window.validate();
        }
    }

    Mode modeForTest() {
        return mode;
    }

    ParameterSweepEditor editorForTest() {
        return editor;
    }

    JPanel presetEditorPanelForTest() {
        return presetEditor;
    }

    void configurePresetsForTest(List<String> selectedNames) {
        presetEditor.setSelectedNamesForTest(selectedNames);
        refreshCellEstimate();
    }

    JToggleButton sweepParamButtonForTest() {
        return sweepParamButton;
    }

    JToggleButton sweepStepButtonForTest() {
        return sweepStepButton;
    }

    JToggleButton sweepPresetsButtonForTest() {
        return sweepPresetsButton;
    }

    JToggleButton fullSweepButtonForTest() {
        return fullSweepButton;
    }

    SweepRangeEditor sweepRangeEditorForTest() {
        return sweepRangeEditor;
    }

    StepSwapEditor stepSwapEditorForTest() {
        return stepSwapEditor;
    }

    JButton exportPipelineFigureButtonForTest() {
        return exportPipelineFigureButton;
    }

    void setPipelineFigureExportFileForTest(File file) {
        pipelineFigureExportFileForTest = file;
    }

    JButton useComboButtonForTest() {
        return useComboButton;
    }

    JButton runButtonForTest() {
        return runButton;
    }

    void cancelRunForTest() {
        cancelExecutor();
    }

    VariationGridWindow gridWindowForTest() {
        return gridWindow;
    }

    JLabel chainRibbonLabelForTest() {
        return chainRibbonLabel;
    }

    ChainRibbon chainRibbonForTest() {
        return chainRibbon;
    }

    int completedCountForTest() {
        return completedCount;
    }

    int failedCountForTest() {
        return failedCount;
    }

    List<VariationResult> resultsForTest() {
        return new ArrayList<VariationResult>(resultsByCell);
    }

    List<VariationCellPanel> cellsForTest() {
        return new ArrayList<VariationCellPanel>(cells);
    }

    JLabel suggestionLabelForTest() {
        return suggestionLabel;
    }

    void waitForDoneForTest(long timeoutMs) throws Exception {
        VariationExecutor worker = executor;
        if (worker != null) {
            worker.get(timeoutMs, TimeUnit.MILLISECONDS);
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline
                && completedCount < currentVariationTotal()) {
            EventQueue.invokeAndWait(new Runnable() {
                @Override public void run() {
                }
            });
            if (completedCount >= currentVariationTotal()) {
                return;
            }
            Thread.sleep(10L);
        }
        EventQueue.invokeAndWait(new Runnable() {
            @Override public void run() {
            }
        });
    }

    void waitForDownstreamDoneForTest(long timeoutMs) throws Exception {
        SwingWorker<Map<ParameterCombo, DownstreamVerdict.Verdict>, Void> worker =
                downstreamWorker;
        if (worker != null) {
            worker.get(timeoutMs, TimeUnit.MILLISECONDS);
        }
        EventQueue.invokeAndWait(new Runnable() {
            @Override public void run() {
            }
        });
    }

    private void buildUi() {
        addComponent(headerPanel());
        addComponent(modeToggleRow());
        addComponent(chainRibbonRow());
        addHeader("Parameters to sweep");
        editorCardsPanel.setOpaque(false);
        editorCardsPanel.add(sweepRangeEditor, CARD_SWEEP_PARAMETER);
        editorCardsPanel.add(stepSwapEditor, CARD_SWEEP_STEP);
        editorCardsPanel.add(presetEditor, CARD_SWEEP_PRESETS);
        editorCardsPanel.add(editor, CARD_FULL_SWEEP);
        editorCards.show(editorCardsPanel, CARD_SWEEP_PARAMETER);
        addComponent(editorCardsPanel);
        addComponent(cropRow());
        addComponent(statusPanel());

        exportPipelineFigureButton = addFooterButton("Export pipeline figure");
        exportPipelineFigureButton.setEnabled(false);
        exportPipelineFigureButton.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                exportSelectedPipelineFigure();
            }
        });
        JButton close = addRightFooterButton("Close");
        close.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                cancelExecutor();
                dispose();
            }
        });
        useComboButton = addRightFooterButton("Use this combo");
        useComboButton.setEnabled(false);
        useComboButton.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                if (selectedCombo == null || selectedStrategy == null) {
                    setStatusTextNow("Select a completed tile first.");
                    return;
                }
                acceptAndClose(selectedCombo, selectedStrategy);
            }
        });
        runButton = addRightFooterButton("Run");
        runButton.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                start();
            }
        });

        editor.addChangeListener(new ChangeListener() {
            @Override public void stateChanged(ChangeEvent e) {
                refreshCellEstimate();
            }
        });
        presetEditor.addChangeListener(new ChangeListener() {
            @Override public void stateChanged(ChangeEvent e) {
                refreshCellEstimate();
            }
        });
        sweepRangeEditor.addChangeListener(new ChangeListener() {
            @Override public void stateChanged(ChangeEvent e) {
                refreshCellEstimate();
            }
        });
        stepSwapEditor.addChangeListener(new ChangeListener() {
            @Override public void stateChanged(ChangeEvent e) {
                refreshCellEstimate();
            }
        });
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
            throw new IllegalStateException("Could not start macro variations", e);
        }
    }

    private void startOnEdt() {
        cancelExecutor();
        cancelDownstreamWorker();
        disposeGridWindow();
        try {
            currentSweep = withChainCacheNamespace(currentSweepForMode());
        } catch (RuntimeException e) {
            showMessage(e.getMessage());
            setStatusTextNow(mode == Mode.FULL_SWEEP
                    ? "Choose at least one value for each selected parameter."
                    : safe(e.getMessage()));
            return;
        }
        ImagePlus source = context.sourceImage();
        ResourceGuard.Feasibility feasibility =
                ResourceGuard.assessFeasibility(currentSweep, source);
        if (!feasibility.ok) {
            showMessage(feasibility.message);
            setStatusTextNow("Refused");
            return;
        }

        completedCount = 0;
        failedCount = 0;
        downstreamStartedForCurrentResults = false;
        selectedCombo = null;
        selectedStrategy = null;
        if (selectedCell != null) {
            selectedCell.setSelectedForCompare(false);
            selectedCell = null;
        }
        clearDownstreamVerdicts();
        disposeCells();
        cells.clear();
        cellsByCombo.clear();
        cellIndexesByCombo.clear();
        resultsByCell.clear();
        if (runButton != null) {
            runButton.setEnabled(false);
        }
        useComboButton.setEnabled(false);
        if (gridWindow != null) {
            gridWindow.setPickSelectedEnabled(false);
        }
        exportPipelineFigureButton.setEnabled(false);

        final ImagePlus croppedSource = currentSweep.cropSpec().apply(source);
        currentBaselineCrop = croppedSource;
        List<ParameterCombo> combos = currentSweep.combos();
        VariationCache runCache = new VariationCache(context.configContext());
        currentRunCache = runCache;
        final FilterSweepStrategy strategy = new FilterSweepStrategy(
                context.baseMacro(),
                context.previewAdapter(),
                croppedSource,
                runCache,
                null,
                context.presetMacroLoader());

        VariationCellPanel baseline = VariationCellPanel.baseline(croppedSource);
        baseline.setZ(1);
        cells.add(baseline);
        for (int i = 0; i < combos.size(); i++) {
            ParameterCombo combo = combos.get(i);
            VariationCellPanel cell = new VariationCellPanel(combo, croppedSource,
                    new Consumer<ParameterCombo>() {
                        @Override public void accept(ParameterCombo accepted) {
                            selectCombo(accepted, strategy);
                            acceptAndClose(accepted, strategy);
                        }
                    },
                    null,
                    i);
            cell.setState("running");
            cell.setZ(1);
            cell.setOverlayMode(currentOverlayMode());
            cells.add(cell);
            cellsByCombo.put(combo.toCanonicalJson(), cell);
            cellIndexesByCombo.put(combo.toCanonicalJson(), Integer.valueOf(i));
            resultsByCell.add(null);
        }

        openGridWindowForRun();
        setStatusTextNow(progressStatus());
        setSuggestionText("Most stable: pending");
        setStrategyText(strategyTextForMode(combos.size()));

        final VariationExecutor[] workerRef = new VariationExecutor[1];
        final VariationExecutor worker = new VariationExecutor(currentSweep,
                strategy,
                runCache,
                new java.util.function.BiConsumer<VariationResult, Integer>() {
                    @Override public void accept(VariationResult result, Integer index) {
                        handleResult(workerRef[0], result,
                                index == null ? -1 : index.intValue());
                    }
                },
                null);
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
        gridWindow = new VariationGridWindow(getWindow(), gridWindowTitle(), cells);
        gridWindow.setOtsuOverlaySelected(showOtsuOverlay);
        gridWindow.setDownstreamVerdictSelected(downstreamVerdictSelected);
        gridWindow.setDownstreamControlsEnabled(
                downstreamResolution.isAvailable(),
                false,
                downstreamTooltip());
        gridWindow.attachOtsuOverlayActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                setShowOtsuOverlay(gridWindow != null
                        && gridWindow.otsuOverlayCheckBoxForTest().isSelected());
            }
        });
        gridWindow.attachDownstreamVerdictActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                handleDownstreamToggle();
            }
        });
        gridWindow.attachStopDownstreamActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                cancelDownstreamWorker();
                setStatusTextNow("Downstream cancelled");
            }
        });
        gridWindow.attachPickSelectedActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                if (selectedCombo != null && selectedStrategy != null) {
                    acceptAndClose(selectedCombo, selectedStrategy);
                }
            }
        });
        gridWindow.attachSaveCacheActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                snapshotVariationsCache();
            }
        });
        gridWindow.setAutoRequestFocus(true);
        gridWindow.setVisible(true);
        gridWindow.toFront();
        gridWindow.requestFocus();
    }

    private void snapshotVariationsCache() {
        VariationCache cache = currentRunCache;
        ParameterSweep sweep = currentSweep;
        if (gridWindow == null) {
            return;
        }
        if (cache == null || sweep == null) {
            gridWindow.setActionStatus("No variations to save yet.");
            return;
        }
        int written = cache.snapshotResultsToDisk(sweep,
                new ArrayList<VariationResult>(resultsByCell));
        gridWindow.setActionStatus(written == 0
                ? "No variations to save yet."
                : "Saved " + written + " variation" + (written == 1 ? "" : "s")
                        + " to the disk cache.");
    }

    private String gridWindowTitle() {
        return "FLASH variations";
    }

    private String downstreamTooltip() {
        return downstreamResolution.isAvailable()
                ? "Runs the active segmenter on each filter output. May take 30-60 s."
                : downstreamResolution.unavailableReason();
    }

    private JPanel headerPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(0, 4, 6, 4));

        JLabel method = new JLabel("Method: Filter");
        method.setFont(method.getFont().deriveFont(java.awt.Font.BOLD, 12f));
        panel.add(method);
        panel.add(Box.createHorizontalStrut(24));
        panel.add(new JLabel("Channel: " + safe(context.channelName())));
        panel.add(Box.createHorizontalGlue());
        panel.add(cellsLabel);
        return panel;
    }

    private void handleDownstreamToggle() {
        downstreamVerdictSelected = gridWindow != null
                && gridWindow.downstreamVerdictCheckBoxForTest().isSelected();
        if (!downstreamVerdictSelected) {
            cancelDownstreamWorker();
            downstreamStartedForCurrentResults = false;
            clearDownstreamVerdicts();
            setStatusTextNow(completionStatus());
            return;
        }
        if (!downstreamResolution.isAvailable()) {
            downstreamVerdictSelected = false;
            if (gridWindow != null) {
                gridWindow.setDownstreamVerdictSelected(false);
            }
            setStatusTextNow(downstreamResolution.unavailableReason());
            return;
        }
        if (completedCount < resultCellTotal() || resultCellTotal() == 0) {
            setStatusTextNow("Downstream will run after the preview grid completes.");
            return;
        }
        startDownstreamVerdict();
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

    private JPanel modeToggleRow() {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setBorder(BorderFactory.createEmptyBorder(0, 4, 2, 4));

        ButtonGroup group = new ButtonGroup();
        group.add(sweepParamButton);
        group.add(sweepStepButton);
        group.add(sweepPresetsButton);
        group.add(fullSweepButton);
        sweepParamButton.setSelected(true);
        sweepParamButton.setToolTipText(
                "Sweep one numeric parameter on one chain step across a range of values");
        sweepStepButton.setToolTipText(
                "Swap one chain step for native filter alternatives");
        sweepPresetsButton.setToolTipText("Compare readable filter presets");
        fullSweepButton.setToolTipText(
                "Click multiple chain steps to sweep them together as a cartesian grid");

        sweepParamButton.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                setMode(Mode.SWEEP_PARAMETER);
            }
        });
        sweepStepButton.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                setMode(Mode.SWEEP_STEP);
            }
        });
        sweepPresetsButton.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                setMode(Mode.SWEEP_PRESETS);
            }
        });
        fullSweepButton.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                setMode(Mode.FULL_SWEEP);
            }
        });

        row.add(new JLabel("Mode: "));
        row.add(sweepParamButton);
        row.add(Box.createHorizontalStrut(6));
        row.add(sweepStepButton);
        row.add(Box.createHorizontalStrut(6));
        row.add(sweepPresetsButton);
        row.add(Box.createHorizontalStrut(6));
        row.add(fullSweepButton);
        row.add(Box.createHorizontalGlue());
        return row;
    }

    private JPanel chainRibbonRow() {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(2, 4, 8, 4));
        chainRibbonLabel.setText("Chain: " + chainSummary());
        chainRibbonLabel.setForeground(new Color(78, 93, 101));
        chainRibbon.addListener(new ChainRibbon.Listener() {
            @Override public void stepStateChanged(int stepIndex,
                                                   ChainRibbon.StepState newState) {
                handleChainStateChanged(stepIndex, newState);
            }
        });
        row.add(chainRibbon, BorderLayout.CENTER);
        return row;
    }

    private void handleChainStateChanged(int stepIndex,
                                         ChainRibbon.StepState newState) {
        if (mode == Mode.SWEEP_PRESETS) {
            return;
        }
        if (mode == Mode.FULL_SWEEP) {
            Set<Integer> swept = chainRibbon.stepIndexesInState(
                    ChainRibbon.StepState.SWEPT);
            editor.setSelectedChainStepIndexes(swept);
            refreshCellEstimate();
            return;
        }
        // SWEEP_PARAMETER or SWEEP_STEP (focus modes)
        if (newState != ChainRibbon.StepState.FOCUSED) {
            return;
        }
        if (mode == Mode.SWEEP_PARAMETER) {
            sweepRangeEditor.setFocusedStepIndex(stepIndex);
        } else if (mode == Mode.SWEEP_STEP) {
            stepSwapEditor.setFocusedStepIndex(stepIndex);
        }
        refreshCellEstimate();
    }

    private ParameterSweep currentSweepForMode() {
        if (mode == Mode.SWEEP_PRESETS) {
            return presetEditor.currentSweep(currentCropSpec,
                    context.channelName(),
                    context.sourceImageHash(),
                    context.cacheNamespace() + ":presets");
        }
        if (mode == Mode.SWEEP_STEP) {
            int focused = chainRibbon.focusedStepIndex();
            if (focused < 0) {
                throw new IllegalStateException("Choose a focusable chain step.");
            }
            return stepSwapEditor.currentSweep(currentCropSpec);
        }
        if (mode == Mode.SWEEP_PARAMETER) {
            int focused = chainRibbon.focusedStepIndex();
            if (focused < 0) {
                throw new IllegalStateException("Choose a chain step to sweep.");
            }
            return sweepRangeEditor.currentSweep(currentCropSpec);
        }
        // FULL_SWEEP
        ParameterSweep sweep = editor.currentSweep();
        return new ParameterSweep(sweep.method(), sweep.valueLists(),
                currentCropSpec, sweep.channelName(), sweep.sourceImageHash(),
                sweep.cacheNamespace() + ":full", sweep.macroVariations());
    }

    static ParameterSweep buildPresetsSweepForTest(List<String> presetNames,
                                                   CropSpec cropSpec,
                                                   String channelName,
                                                   String sourceImageHash,
                                                   String cacheNamespace) {
        return buildPresetsSweep(presetNames, cropSpec,
                channelName, sourceImageHash, cacheNamespace);
    }

    private static ParameterSweep buildPresetsSweep(List<String> presetNames,
                                                    CropSpec cropSpec,
                                                    String channelName,
                                                    String sourceImageHash,
                                                    String cacheNamespace) {
        List<String> names = new ArrayList<String>();
        if (presetNames != null) {
            for (int i = 0; i < presetNames.size(); i++) {
                String name = presetNames.get(i);
                if (name != null && name.trim().length() > 0) {
                    names.add(name.trim());
                }
            }
        }
        if (names.isEmpty()) {
            throw new IllegalStateException("Choose at least one readable preset.");
        }
        LinkedHashMap<ParameterKey, ParameterValueList> values =
                new LinkedHashMap<ParameterKey, ParameterValueList>();
        values.put(PresetSweepKey.presetName(),
                new ParameterValueList(names));
        return new ParameterSweep(ParameterSweep.Method.FILTER,
                values,
                cropSpec,
                channelName,
                sourceImageHash,
                cacheNamespace);
    }

    private void configureFocusabilityFor(Mode requested) {
        StepFocusModel focusModel = stepsFocusModel(context.baseMacro(),
                requested == Mode.SWEEP_STEP);
        chainRibbon.setFocusableStepIndexes(focusModel.focusable,
                focusModel.reasons);
    }

    private boolean defaultFocusFirstFocusableStep() {
        configureFocusabilityFor(mode);
        StepFocusModel focusModel = stepsFocusModel(context.baseMacro(),
                mode == Mode.SWEEP_STEP);
        if (focusModel.focusable.isEmpty()) {
            setStatusTextNow(mode == Mode.SWEEP_STEP
                    ? "No native alternatives available for any chain step."
                    : "No sweepable parameters available on any chain step.");
            return false;
        }
        int focused = chainRibbon.focusedStepIndex();
        if (focusModel.focusable.contains(Integer.valueOf(focused))) {
            if (mode == Mode.SWEEP_PARAMETER) {
                sweepRangeEditor.setFocusedStepIndex(focused);
            } else if (mode == Mode.SWEEP_STEP) {
                stepSwapEditor.setFocusedStepIndex(focused);
            }
            return true;
        }
        chainRibbon.focusStep(focusModel.focusable.iterator().next().intValue());
        return true;
    }

    private static StepFocusModel stepsFocusModel(
            FilterMacroEditorModel.MacroDefinition macro,
            boolean requireFilterAlternatives) {
        StepFocusModel model = new StepFocusModel();
        List<OpType> types = opTypesForSteps(macro);
        for (int i = 0; i < types.size(); i++) {
            SlotRole role = FilterAlternatives.slotRoleFor(types.get(i));
            if (requireFilterAlternatives) {
                if (role == null
                        || !FilterAlternatives.hasUsefulAlternatives(role)) {
                    model.reasons.put(Integer.valueOf(i),
                            "No native alternatives available");
                    continue;
                }
            }
            model.focusable.add(Integer.valueOf(i));
        }
        return model;
    }

    private static List<OpType> opTypesForSteps(
            FilterMacroEditorModel.MacroDefinition macro) {
        List<OpType> out = new ArrayList<OpType>();
        if (macro == null) {
            return out;
        }
        String[] lines = macro.render()
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .split("\n", -1);
        List<FilterMacroEditorModel.Section> sections = macro.getSections();
        for (int i = 0; i < sections.size(); i++) {
            FilterMacroEditorModel.Section section = sections.get(i);
            for (int j = 0; j < section.entries.size(); j++) {
                FilterMacroEditorModel.Entry entry = section.entries.get(j);
                OpType type = OpType.UNKNOWN;
                if (entry.lineIndex >= 0 && entry.lineIndex < lines.length) {
                    List<FilterMacroParser.Op> ops =
                            FilterMacroParser.parseString(lines[entry.lineIndex]);
                    if (!ops.isEmpty() && ops.get(0) != null) {
                        type = ops.get(0).type;
                    }
                }
                out.add(type);
            }
        }
        return out;
    }

    private static final class StepFocusModel {
        final Set<Integer> focusable = new LinkedHashSet<Integer>();
        final Map<Integer, String> reasons = new LinkedHashMap<Integer, String>();
    }

    private static final class PresetPickerPanel extends JPanel {
        private final PresetEnumerator.Result enumeration;
        private final DefaultListModel<PresetListItem> presetModel =
                new DefaultListModel<PresetListItem>();
        private final JList<PresetListItem> presetList =
                new JList<PresetListItem>(presetModel);
        private final List<ChangeListener> listeners =
                new ArrayList<ChangeListener>();
        private boolean suppressSelectionEvents;

        PresetPickerPanel(FilterVariationEngineContext context) {
            super(new BorderLayout(8, 6));
            setOpaque(false);
            setBorder(BorderFactory.createLineBorder(new Color(214, 220, 224)));
            enumeration = new PresetEnumerator(
                    context == null ? Collections.<String>emptyList()
                            : context.presetOptions(),
                    context == null ? null : context.presetMacroLoader())
                    .enumerate();
            buildPresetModel();
            buildUi();
            selectAllReadable();
            installListeners();
        }

        void addChangeListener(ChangeListener listener) {
            if (listener != null) {
                listeners.add(listener);
            }
        }

        ParameterSweep currentSweep(CropSpec cropSpec,
                                    String channelName,
                                    String sourceImageHash,
                                    String cacheNamespace) {
            List<PresetEnumerator.PresetInfo> selected = selectedReadablePresets();
            if (selected.isEmpty()) {
                throw new IllegalStateException(statusMessage());
            }
            return buildPresetsSweep(namesFor(selected),
                    cropSpec,
                    channelName,
                    sourceImageHash,
                    cacheNamespace);
        }

        Map<String, String> rowCaptionsForSelected() {
            LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
            List<PresetEnumerator.PresetInfo> selected = selectedReadablePresets();
            for (int i = 0; i < selected.size(); i++) {
                PresetEnumerator.PresetInfo info = selected.get(i);
                out.put(info.name(), info.chainSummary());
            }
            return out;
        }

        void setSelectedNamesForTest(List<String> names) {
            Set<String> wanted = new LinkedHashSet<String>();
            if (names != null) {
                for (int i = 0; i < names.size(); i++) {
                    String name = names.get(i);
                    if (name != null) {
                        wanted.add(name.trim().toLowerCase(java.util.Locale.ROOT));
                    }
                }
            }
            List<Integer> indexes = new ArrayList<Integer>();
            for (int i = 0; i < presetModel.size(); i++) {
                PresetListItem item = presetModel.getElementAt(i);
                if (item.isReadable()
                        && wanted.contains(item.name().toLowerCase(java.util.Locale.ROOT))) {
                    indexes.add(Integer.valueOf(i));
                }
            }
            int[] selected = new int[indexes.size()];
            for (int i = 0; i < indexes.size(); i++) {
                selected[i] = indexes.get(i).intValue();
            }
            presetList.setSelectedIndices(selected);
            fireChanged();
        }

        private void buildUi() {
            presetList.setVisibleRowCount(Math.min(6,
                    Math.max(3, presetModel.getSize())));
            presetList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            presetList.setCellRenderer(new PresetListRenderer());
            JScrollPane scroller = new JScrollPane(presetList);
            scroller.setBorder(BorderFactory.createEmptyBorder(2, 8, 8, 8));
            scroller.setPreferredSize(new Dimension(420, 96));

            add(scroller, BorderLayout.CENTER);
        }

        private void installListeners() {
            presetList.addListSelectionListener(new ListSelectionListener() {
                @Override public void valueChanged(ListSelectionEvent e) {
                    if (!e.getValueIsAdjusting()) {
                        removeSkippedSelections();
                        fireChanged();
                    }
                }
            });
        }

        private void buildPresetModel() {
            List<PresetEnumerator.PresetInfo> readable =
                    enumeration.readablePresets();
            for (int i = 0; i < readable.size(); i++) {
                presetModel.addElement(PresetListItem.readable(readable.get(i)));
            }
            List<PresetEnumerator.SkippedPreset> skipped =
                    enumeration.skippedPresets();
            for (int i = 0; i < skipped.size(); i++) {
                presetModel.addElement(PresetListItem.skipped(skipped.get(i)));
            }
        }

        private void selectAllReadable() {
            List<Integer> indexes = new ArrayList<Integer>();
            for (int i = 0; i < presetModel.size(); i++) {
                if (presetModel.getElementAt(i).isReadable()) {
                    indexes.add(Integer.valueOf(i));
                }
            }
            int[] selected = new int[indexes.size()];
            for (int i = 0; i < indexes.size(); i++) {
                selected[i] = indexes.get(i).intValue();
            }
            presetList.setSelectedIndices(selected);
        }

        private void removeSkippedSelections() {
            if (suppressSelectionEvents) {
                return;
            }
            int[] selected = presetList.getSelectedIndices();
            List<Integer> readable = new ArrayList<Integer>();
            boolean changed = false;
            for (int i = 0; i < selected.length; i++) {
                if (presetModel.getElementAt(selected[i]).isReadable()) {
                    readable.add(Integer.valueOf(selected[i]));
                } else {
                    changed = true;
                }
            }
            if (!changed) {
                return;
            }
            int[] next = new int[readable.size()];
            for (int i = 0; i < readable.size(); i++) {
                next[i] = readable.get(i).intValue();
            }
            suppressSelectionEvents = true;
            try {
                presetList.setSelectedIndices(next);
            } finally {
                suppressSelectionEvents = false;
            }
        }

        private List<PresetEnumerator.PresetInfo> selectedReadablePresets() {
            int[] selected = presetList.getSelectedIndices();
            List<PresetEnumerator.PresetInfo> out =
                    new ArrayList<PresetEnumerator.PresetInfo>();
            for (int i = 0; i < selected.length; i++) {
                PresetListItem item = presetModel.getElementAt(selected[i]);
                if (item.isReadable()) {
                    out.add(item.info);
                }
            }
            return out;
        }

        private String statusMessage() {
            if (enumeration.readablePresets().isEmpty()) {
                return enumeration.skippedPresets().isEmpty()
                        ? "No preset options available."
                        : "No readable presets available.";
            }
            List<PresetEnumerator.PresetInfo> selected = selectedReadablePresets();
            if (selected.isEmpty()) {
                return "Choose at least one readable preset.";
            }
            return "";
        }

        private List<String> namesFor(List<PresetEnumerator.PresetInfo> selected) {
            List<String> out = new ArrayList<String>();
            for (int i = 0; i < selected.size(); i++) {
                out.add(selected.get(i).name());
            }
            return out;
        }

        private void fireChanged() {
            ChangeEvent event = new ChangeEvent(this);
            for (int i = 0; i < listeners.size(); i++) {
                listeners.get(i).stateChanged(event);
            }
        }

        private static final class PresetListItem {
            final PresetEnumerator.PresetInfo info;
            final PresetEnumerator.SkippedPreset skipped;

            private PresetListItem(PresetEnumerator.PresetInfo info,
                                   PresetEnumerator.SkippedPreset skipped) {
                this.info = info;
                this.skipped = skipped;
            }

            static PresetListItem readable(PresetEnumerator.PresetInfo info) {
                return new PresetListItem(info, null);
            }

            static PresetListItem skipped(PresetEnumerator.SkippedPreset skipped) {
                return new PresetListItem(null, skipped);
            }

            boolean isReadable() {
                return info != null;
            }

            String name() {
                return isReadable() ? info.name() : skipped.name();
            }

            @Override public String toString() {
                if (isReadable()) {
                    return info.name();
                }
                return skipped.name() + " (unreadable)";
            }
        }

        private static final class PresetListRenderer
                extends DefaultListCellRenderer {
            @Override public Component getListCellRendererComponent(
                    JList<?> list,
                    Object value,
                    int index,
                    boolean isSelected,
                    boolean cellHasFocus) {
                Component component = super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
                if (!(component instanceof JLabel)
                        || !(value instanceof PresetListItem)) {
                    return component;
                }
                JLabel label = (JLabel) component;
                PresetListItem item = (PresetListItem) value;
                if (item.isReadable()) {
                    label.setText(item.info.name());
                    label.setToolTipText(item.info.chainSummary());
                    label.setEnabled(true);
                } else {
                    label.setText(item.skipped.name() + " (unreadable)");
                    label.setToolTipText(item.skipped.reason());
                    label.setEnabled(false);
                    if (!isSelected) {
                        label.setForeground(new Color(130, 136, 142));
                    }
                }
                return component;
            }
        }
    }

    private ParameterSweep withChainCacheNamespace(ParameterSweep sweep) {
        if (sweep == null) {
            return null;
        }
        if (mode == Mode.SWEEP_PRESETS) {
            return sweep;
        }
        String key = chainStateKey();
        if (key.length() == 0) {
            return sweep;
        }
        String namespace = sweep.cacheNamespace();
        String chainNamespace = (namespace == null || namespace.trim().isEmpty())
                ? "chain:" + key
                : namespace.trim() + ":chain:" + key;
        return new ParameterSweep(sweep.method(), sweep.valueLists(),
                sweep.cropSpec(), sweep.channelName(), sweep.sourceImageHash(),
                chainNamespace, sweep.macroVariations());
    }

    private String chainStateKey() {
        if (chainRibbon == null || chainRibbon.stepCount() == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        boolean anyNonFixed = false;
        for (int i = 0; i < chainRibbon.stepCount(); i++) {
            ChainRibbon.StepState state = chainRibbon.getStepState(i);
            if (state != ChainRibbon.StepState.FIXED) {
                anyNonFixed = true;
            }
            if (i > 0) {
                out.append(',');
            }
            out.append(i).append('=').append(state.name());
        }
        return anyNonFixed ? out.toString() : "";
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
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        statusLabel.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        suggestionLabel.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        strategyLabel.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        panel.add(statusLabel);
        panel.add(suggestionLabel);
        panel.add(strategyLabel);
        return panel;
    }

    private void refreshCellEstimate() {
        try {
            ParameterSweep sweep = currentSweepForMode();
            cellsLabel.setText("Cells: " + sweep.cellCount());
            if (!isExecutorActive()) {
                setStatusTextNow(sweep.cellCount() + " cells, crop "
                        + cropSummary(sweep.cropSpec()));
                setStrategyText(" ");
            }
            if (runButton != null && !isExecutorActive()) {
                runButton.setEnabled(true);
            }
        } catch (RuntimeException e) {
            cellsLabel.setText("Cells: ?");
            if (!isExecutorActive()) {
                setStatusTextNow(mode == Mode.FULL_SWEEP
                        ? "Choose at least one value for each selected parameter."
                        : safe(e.getMessage()));
                setStrategyText(" ");
            }
            if (runButton != null && !isExecutorActive()) {
                runButton.setEnabled(false);
            }
        }
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
        String comboId = result.combo().toCanonicalJson();
        VariationCellPanel cell = cellsByCombo.get(comboId);
        Integer comboIndex = cellIndexesByCombo.get(comboId);
        int targetIndex = comboIndex == null ? index : comboIndex.intValue();
        if (cell == null) {
            cell = cellForResultIndex(index);
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
        updateGridWindowProgress();
        setStatusTextNow(progressStatus());
        if (completedCount >= resultCellTotal() && resultCellTotal() > 0) {
            if (downstreamVerdictSelected) {
                startDownstreamVerdict();
            }
        }
        if (selectedCombo != null
                && selectedCombo.toCanonicalJson().equals(comboId)) {
            updateSelectedStatus(result);
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
        if (runButton != null) {
            runButton.setEnabled(true);
        }
        if (worker.isCancelled()) {
            setStatusTextNow("Cancelled");
            markRunningCellsCancelled();
            updateGridWindowProgress();
            return;
        }
        try {
            worker.get();
            failedCount = countFailures();
            updateGridWindowProgress();
            setStatusTextNow(completionStatus());
            if (downstreamVerdictSelected) {
                startDownstreamVerdict();
            }
        } catch (Exception e) {
            failedCount = countFailures();
            updateGridWindowProgress();
            setStatusTextNow("Error: " + safe(e.getMessage()));
            showMessage(e.getMessage());
        }
    }

    private boolean allCellsSuccessful() {
        if (resultsByCell.isEmpty()) {
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

    private void markRunningCellsCancelled() {
        for (int i = 0; i < cells.size(); i++) {
            String badge = cells.get(i).badgeText();
            if ("running".equals(badge) || "pending".equals(badge)) {
                cells.get(i).setState("cancelled");
            }
        }
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

    private void updateGridWindowProgress() {
        if (gridWindow == null) {
            return;
        }
        gridWindow.setSliceMax(gridWindow.controllerForTest().maxSlice());
        gridWindow.setCompletedCount(completedCount, currentVariationTotal(), failedCount);
    }

    private void startDownstreamVerdict() {
        if (!downstreamResolution.isAvailable()
                || currentSweep == null
                || currentBaselineCrop == null
                || currentRunCache == null
                || !allCellsSuccessful()
                || downstreamStartedForCurrentResults
                || isDownstreamActive()) {
            return;
        }
        downstreamStartedForCurrentResults = true;
        clearDownstreamVerdicts();
        updateGridWindowDownstreamControls(true);
        setStatusTextNow("Downstream: starting");
        final DownstreamSegmenter segmenter =
                downstreamResolution.segmenter().forFilterSweep(currentSweep);
        final List<VariationResult> snapshot =
                new ArrayList<VariationResult>(resultsByCell);
        final ImagePlus baseline = currentBaselineCrop;
        final VariationCache cache = currentRunCache;
        final SwingWorker<Map<ParameterCombo, DownstreamVerdict.Verdict>, Void>[] workerRef =
                new SwingWorker[1];
        SwingWorker<Map<ParameterCombo, DownstreamVerdict.Verdict>, Void> worker =
                new SwingWorker<Map<ParameterCombo, DownstreamVerdict.Verdict>, Void>() {
            @Override protected Map<ParameterCombo, DownstreamVerdict.Verdict>
            doInBackground() {
                return DownstreamVerdict.compute(snapshot,
                        segmenter,
                        baseline,
                        cache,
                        new java.util.function.BooleanSupplier() {
                            @Override public boolean getAsBoolean() {
                                return isCancelled();
                            }
                        },
                        new Consumer<DownstreamVerdict.Progress>() {
                            @Override public void accept(final DownstreamVerdict.Progress progress) {
                                SwingUtilities.invokeLater(new Runnable() {
                                    @Override public void run() {
                                        handleDownstreamProgress(workerRef[0], progress);
                                    }
                                });
                            }
                        });
            }

            @Override protected void done() {
                if (this != downstreamWorker) {
                    return;
                }
                updateGridWindowDownstreamControls(false);
                if (isCancelled()) {
                    setStatusTextNow("Downstream cancelled");
                    applyBestDownstreamRibbon();
                    return;
                }
                try {
                    get();
                    applyBestDownstreamRibbon();
                    setStatusTextNow("Downstream complete");
                } catch (Exception e) {
                    setStatusTextNow("Downstream error: " + safe(e.getMessage()));
                }
            }
        };
        workerRef[0] = worker;
        downstreamWorker = worker;
        worker.execute();
    }

    private void handleDownstreamProgress(
            final SwingWorker<Map<ParameterCombo, DownstreamVerdict.Verdict>, Void> worker,
            DownstreamVerdict.Progress progress) {
        if (!SwingUtilities.isEventDispatchThread()) {
            final DownstreamVerdict.Progress safeProgress = progress;
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    handleDownstreamProgress(worker, safeProgress);
                }
            });
            return;
        }
        if (worker != downstreamWorker) {
            return;
        }
        if (progress == null) {
            return;
        }
        if (progress.combo != null && progress.verdict != null) {
            downstreamVerdicts.put(progress.combo.toCanonicalJson(),
                    progress.verdict);
            VariationCellPanel cell = cellsByCombo.get(
                    progress.combo.toCanonicalJson());
            if (cell != null) {
                cell.setDownstreamDelta(progress.verdict.deltaCells);
            }
            applyBestDownstreamRibbon();
        }
        if (progress.message != null && progress.message.trim().length() > 0) {
            setStatusTextNow(progress.message);
        }
    }

    private void applyBestDownstreamRibbon() {
        VariationCellPanel bestCell = null;
        int bestDelta = Integer.MIN_VALUE;
        for (int i = 0; i < cells.size(); i++) {
            cells.get(i).setDownstreamRibbonLabel(null);
        }
        for (Map.Entry<String, DownstreamVerdict.Verdict> entry
                : downstreamVerdicts.entrySet()) {
            DownstreamVerdict.Verdict verdict = entry.getValue();
            if (verdict == null || !verdict.isHelp) {
                continue;
            }
            if (verdict.deltaCells > bestDelta) {
                VariationCellPanel cell = cellsByCombo.get(entry.getKey());
                if (cell != null) {
                    bestCell = cell;
                    bestDelta = verdict.deltaCells;
                }
            }
        }
        if (bestCell != null) {
            bestCell.setRibbonLabel("HELPS DOWNSTREAM");
        }
    }

    private void clearDownstreamVerdicts() {
        downstreamVerdicts.clear();
        for (int i = 0; i < cells.size(); i++) {
            cells.get(i).clearDownstreamVerdict();
        }
    }

    private boolean isDownstreamActive() {
        return downstreamWorker != null && !downstreamWorker.isDone();
    }

    private void cancelDownstreamWorker() {
        SwingWorker<Map<ParameterCombo, DownstreamVerdict.Verdict>, Void> worker =
                downstreamWorker;
        if (worker != null && !worker.isDone()) {
            worker.cancel(true);
        }
        updateGridWindowDownstreamControls(false);
    }

    private void updateGridWindowDownstreamControls(boolean stopEnabled) {
        if (gridWindow == null) {
            return;
        }
        gridWindow.setDownstreamControlsEnabled(
                downstreamResolution.isAvailable(),
                stopEnabled,
                downstreamTooltip());
    }

    private void installWindowCleanup() {
        Window window = getWindow();
        if (window == null) {
            return;
        }
        window.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                cancelExecutor();
                cancelDownstreamWorker();
                disposeGridWindow();
            }

            @Override public void windowClosed(WindowEvent e) {
                cancelExecutor();
                cancelDownstreamWorker();
                disposeGridWindow();
            }
        });
    }

    private void disposeGridWindow() {
        if (gridWindow != null) {
            gridWindow.dispose();
            gridWindow = null;
        }
    }

    private void disposeCells() {
        for (int i = 0; i < cells.size(); i++) {
            cells.get(i).disposeImages();
        }
    }

    private void acceptAndClose(ParameterCombo combo, FilterSweepStrategy strategy) {
        try {
            if (onAccept != null && combo != null && strategy != null) {
                onAccept.accept(strategy.renderMacroForCombo(combo));
            }
        } finally {
            dispose();
        }
    }

    private void selectCombo(ParameterCombo combo, FilterSweepStrategy strategy) {
        if (combo == null || strategy == null) {
            return;
        }
        selectedCombo = combo;
        selectedStrategy = strategy;
        if (selectedCell != null) {
            selectedCell.setSelectedForCompare(false);
        }
        selectedCell = cellsByCombo.get(combo.toCanonicalJson());
        if (selectedCell != null) {
            selectedCell.setSelectedForCompare(true);
        }
        if (useComboButton != null) {
            useComboButton.setEnabled(true);
        }
        if (gridWindow != null) {
            gridWindow.setPickSelectedEnabled(true);
        }
        if (exportPipelineFigureButton != null) {
            exportPipelineFigureButton.setEnabled(true);
        }
        updateSelectedStatus(resultForCombo(combo));
    }

    private void exportSelectedPipelineFigure() {
        if (selectedCombo == null || selectedStrategy == null) {
            setStatusTextNow("Select a completed tile first.");
            return;
        }
        ImagePlus source = currentBaselineCrop == null
                ? context.sourceImage()
                : currentBaselineCrop;
        if (source == null) {
            setStatusTextNow("No source image is available for export.");
            return;
        }
        File out = choosePipelineFigureFile();
        if (out == null) {
            return;
        }
        try {
            String macro = selectedStrategy.renderMacroForCombo(selectedCombo);
            BufferedImage figure = PipelineFigureExporter.render(macro,
                    source,
                    context.previewAdapter(),
                    currentRunCache);
            PipelineFigureExporter.exportPNG(figure, out);
            setStatusTextNow("Exported pipeline figure: " + out.getName());
        } catch (Exception ex) {
            setStatusTextNow("Export failed: " + safe(ex.getMessage()));
            showMessage("Could not export pipeline figure: " + ex.getMessage());
        }
    }

    private File choosePipelineFigureFile() {
        if (pipelineFigureExportFileForTest != null) {
            return withPngExtension(pipelineFigureExportFileForTest);
        }
        if (GraphicsEnvironment.isHeadless()) {
            return null;
        }
        File directory = defaultExportDirectory();
        JFileChooser chooser = new JFileChooser(directory);
        chooser.setDialogTitle("Export pipeline figure");
        chooser.setSelectedFile(new File(directory, defaultExportFileName()));
        int choice = chooser.showSaveDialog(getWindow());
        if (choice != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        return withPngExtension(chooser.getSelectedFile());
    }

    private File defaultExportDirectory() {
        ImagePlus source = context.sourceImage();
        if (source != null) {
            FileInfo info = source.getOriginalFileInfo();
            if (info != null && info.directory != null
                    && info.directory.trim().length() > 0) {
                File parent = new File(info.directory);
                if (parent.isDirectory()) {
                    return parent;
                }
            }
        }
        File bin = context.binFolder();
        if (bin != null) {
            return bin;
        }
        return new File(".");
    }

    private String defaultExportFileName() {
        ImagePlus source = context.sourceImage();
        String title = source == null ? "pipeline" : source.getTitle();
        String base = title == null || title.trim().isEmpty()
                ? "pipeline"
                : title.trim();
        base = base.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]+", "_").trim();
        if (base.length() == 0) {
            base = "pipeline";
        }
        return base + "-pipeline-figure.png";
    }

    private static File withPngExtension(File file) {
        if (file == null) {
            return null;
        }
        String name = file.getName();
        if (name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
            return file;
        }
        File parent = file.getParentFile();
        String path = file.getPath() + ".png";
        return parent == null ? new File(path) : new File(parent, name + ".png");
    }

    private VariationResult resultForCombo(ParameterCombo combo) {
        if (combo == null) return null;
        Integer index = cellIndexesByCombo.get(combo.toCanonicalJson());
        if (index == null) return null;
        int i = index.intValue();
        if (i < 0 || i >= resultsByCell.size()) return null;
        return resultsByCell.get(i);
    }

    private void updateSelectedStatus(VariationResult result) {
        if (result == null) {
            setStatusTextNow("Selected tile pending.");
        } else if (result.hasError()) {
            setStatusTextNow("Selected tile failed.");
        } else if (result.kind() == VariationResult.Kind.FILTER) {
            setStatusTextNow("Selected tile: SNR "
                    + formatOneDecimal(result.snr())
                    + ", bg sigma "
                    + formatOneDecimal(result.bgSigma()));
        } else {
            setStatusTextNow("Selected tile.");
        }
    }

    private void cancelExecutor() {
        VariationExecutor worker = executor;
        if (worker != null && !worker.isDone()) {
            worker.cancel(true);
        }
    }

    private boolean isExecutorActive() {
        return executor != null && !executor.isDone();
    }

    private int currentVariationTotal() {
        if (!resultsByCell.isEmpty()) {
            return resultsByCell.size();
        }
        if (!cells.isEmpty()) {
            return Math.max(0, cells.size() - baselineCellOffset());
        }
        return estimatedCellCount();
    }

    private int resultCellTotal() {
        return resultsByCell.size();
    }

    private VariationCellPanel cellForResultIndex(int resultIndex) {
        int cellIndex = resultIndex + baselineCellOffset();
        if (cellIndex < 0 || cellIndex >= cells.size()) {
            return null;
        }
        return cells.get(cellIndex);
    }

    private int baselineCellOffset() {
        return !cells.isEmpty() && cells.get(0).isBaselineForTest() ? 1 : 0;
    }

    private String progressStatus() {
        int total = currentVariationTotal();
        String base = completedCount + "/" + Math.max(0, total);
        if (failedCount > 0) {
            return base + " (" + failedCount + " failed)";
        }
        return base;
    }

    private String completionStatus() {
        int total = currentVariationTotal();
        if (failedCount > 0) {
            return completedCount + "/" + Math.max(0, total)
                    + " complete (" + failedCount + " failed)";
        }
        return completedCount + "/" + Math.max(0, total) + " complete";
    }

    private int estimatedCellCount() {
        try {
            long count = currentSweepForMode().cellCount();
            return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(0, (int) count);
        } catch (RuntimeException e) {
            return 0;
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
    }

    private String strategyTextForMode(int comboCount) {
        if (mode == Mode.SWEEP_STEP) {
            return "Using Filter substitution (" + comboCount + " cells)";
        }
        if (mode == Mode.SWEEP_PRESETS) {
            return "Using Filter presets (" + comboCount + " cells)";
        }
        return "Using Filter sweep (" + comboCount + " cells)";
    }

    private void setStatusText(final String text) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                setStatusTextNow(text);
            }
        });
    }

    private void showMessage(final String message) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                javax.swing.JOptionPane.showMessageDialog(getWindow(), safe(message),
                        "Macro Variations", javax.swing.JOptionPane.WARNING_MESSAGE);
            }
        });
    }

    private String chainSummary() {
        List<String> names = new ArrayList<String>();
        List<FilterMacroEditorModel.Section> sections = context.baseMacro().getSections();
        for (int i = 0; i < sections.size(); i++) {
            FilterMacroEditorModel.Section section = sections.get(i);
            if (section.entries.isEmpty()) {
                names.add(section.headerText());
                continue;
            }
            for (int j = 0; j < section.entries.size(); j++) {
                String label = section.entries.get(j).label;
                if (label != null && label.trim().length() > 0) {
                    names.add(label.trim());
                }
            }
        }
        if (names.isEmpty()) {
            return "none";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(names.get(i));
        }
        return out.toString();
    }

    private String cropSummary(CropSpec spec) {
        ImagePlus source = context.sourceImage();
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
            ImagePlus source = context.sourceImage();
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
        ImagePlus source = context.sourceImage();
        if (source == null) {
            return 1;
        }
        int slices = Math.max(1, source.getNSlices());
        if (slices <= 1) {
            slices = Math.max(1, source.getStackSize());
        }
        return slices;
    }

    private static String axisLabelForStatus(ParameterKey axis) {
        if (axis instanceof FilterParameterId) {
            return ((FilterParameterId) axis).paramKey();
        }
        return axis == null ? "" : axis.displayLabel();
    }

    private static String formatValue(Object value) {
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (Math.abs(number - Math.rint(number)) < 0.0000001d
                    && Math.abs(number) < 1000000000.0d) {
                return String.valueOf((long) Math.rint(number));
            }
            String text = String.format(java.util.Locale.ROOT, "%.3f",
                    Double.valueOf(number));
            return text.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return safe(value == null ? "" : String.valueOf(value));
    }

    private static String formatOneDecimal(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "n/a";
        }
        return String.format(java.util.Locale.ROOT, "%.1f", Double.valueOf(value));
    }

    private static boolean valueEquals(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
