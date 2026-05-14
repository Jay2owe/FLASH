package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.image.FilterMacroParser;
import flash.pipeline.image.FilterMacroParser.OpType;
import flash.pipeline.image.dag.DagIR;
import flash.pipeline.image.dag.DagLine;
import flash.pipeline.image.dag.DagNode;
import flash.pipeline.image.dag.DagToIjmEmitter;
import flash.pipeline.image.dag.IjmToDagLoader;
import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.sandbox.FilterAlternatives;
import flash.pipeline.ui.sandbox.FilterAlternatives.Alternative;
import flash.pipeline.ui.sandbox.FilterAlternatives.SlotRole;
import flash.pipeline.ui.variations.analysis.HistogramShapeStability;
import flash.pipeline.ui.variations.strategy.FilterSweepStrategy;

import ij.ImagePlus;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.JToggleButton;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class MacroVariationsDialog extends PipelineDialog {

    enum Mode {
        PARAMS,
        STEPS,
        PRESETS
    }

    private final FilterVariationEngineContext context;
    private final Consumer<String> onAccept;
    private final ParameterSweepEditor editor;
    private final PresetPickerPanel presetEditor;
    private final ChainRibbon chainRibbon;
    private final List<Integer> chainEntryLineIndexes;
    private final VariationGridPanel gridPanel = new VariationGridPanel();
    private final JPanel histogramShapeSlot = new JPanel(new BorderLayout());
    private final JLabel cellsLabel = new JLabel("Cells: 1");
    private final JLabel zLabel = new JLabel("1/1");
    private final JLabel suggestionLabel = new JLabel("Most stable: pending");
    private final JLabel statusLabel = new JLabel("Idle");
    private final JLabel strategyLabel = new JLabel(" ");
    private final JLabel chainRibbonLabel = new JLabel();
    private final JSlider zSlider = new JSlider(1, 1, 1);
    private final JRadioButton fullCrop = new JRadioButton("full image");
    private final JRadioButton centreCrop = new JRadioButton("centered 256 x 256");
    private final JRadioButton customCrop = new JRadioButton("custom...");
    private final JToggleButton paramsButton = new JToggleButton("Params");
    private final JToggleButton stepsButton = new JToggleButton("Steps");
    private final JToggleButton presetsButton = new JToggleButton("Presets");
    private final JCheckBox otsuOverlayCheckBox =
            new JCheckBox("Show Otsu overlay");
    private final JCheckBox downstreamVerdictCheckBox =
            new JCheckBox("Show downstream verdict");
    private final JButton stopDownstreamButton = new JButton("Stop downstream");
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

    private JButton openLargeMontageButton;
    private JButton useComboButton;
    private JButton runButton;
    private VariationExecutor executor;
    private SwingWorker<Map<ParameterCombo, DownstreamVerdict.Verdict>, Void>
            downstreamWorker;
    private ParameterSweep currentSweep;
    private VariationCache currentRunCache;
    private ImagePlus currentBaselineCrop;
    private HistogramShapeStrip histogramShapeStrip;
    private Mode mode = Mode.PARAMS;
    private CropSpec currentCropSpec;
    private ParameterCombo selectedCombo;
    private FilterSweepStrategy selectedStrategy;
    private VariationCellPanel selectedCell;
    private boolean showOtsuOverlay;
    private boolean suppressCropEvents;
    private boolean downstreamStartedForCurrentResults;
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
        this.chainRibbon = new ChainRibbon(context.baseMacro());
        this.chainEntryLineIndexes = ChainRibbon.entryLineIndexes(context.baseMacro());
        this.downstreamResolution = DownstreamSegmenter.resolve(context);
        this.editor.setChainStepFilter(Collections.<Integer>emptySet(),
                Collections.<Integer>emptySet());
        this.currentCropSpec = context.initialCropSpec();
        setDefaultButtonsVisible(false);
        buildUi();
        refreshCellEstimate();
    }

    public void dispose() {
        cancelExecutor();
        cancelDownstreamWorker();
        Window window = getWindow();
        if (window != null) {
            window.dispose();
        }
    }

    void setMode(Mode mode) {
        Mode requested = mode == null ? Mode.PARAMS : mode;
        if (this.mode == requested) {
            modeButtonFor(requested).setSelected(true);
            if (requested == Mode.STEPS && chainRibbon.focusedStepIndex() < 0) {
                defaultFocusFirstStepsSlot();
            }
            refreshCellEstimate();
            return;
        }
        this.mode = requested;
        paramsButton.setSelected(requested == Mode.PARAMS);
        stepsButton.setSelected(requested == Mode.STEPS);
        presetsButton.setSelected(requested == Mode.PRESETS);
        updateModeEditorVisibility();
        chainRibbon.setInteractionEnabled(requested != Mode.PRESETS);
        chainRibbon.setStepsMode(requested == Mode.STEPS);
        if (requested == Mode.STEPS) {
            configureStepsFocusability();
            if (!defaultFocusFirstStepsSlot()) {
                refreshCellEstimate();
            }
        } else if (requested == Mode.PRESETS) {
            refreshCellEstimate();
        } else {
            refreshCellEstimate();
        }
    }

    private JToggleButton modeButtonFor(Mode value) {
        if (value == Mode.STEPS) {
            return stepsButton;
        }
        if (value == Mode.PRESETS) {
            return presetsButton;
        }
        return paramsButton;
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

    void configurePresetsForTest(List<String> selectedNames,
                                 String xParamKey,
                                 List<?> xValues) {
        presetEditor.setSelectedNamesForTest(selectedNames);
        presetEditor.setXParamForTest(xParamKey);
        presetEditor.setXValuesForTest(xValues);
        refreshCellEstimate();
    }

    VariationGridPanel gridPanelForTest() {
        return gridPanel;
    }

    JToggleButton paramsButtonForTest() {
        return paramsButton;
    }

    JToggleButton stepsButtonForTest() {
        return stepsButton;
    }

    JToggleButton presetsButtonForTest() {
        return presetsButton;
    }

    JButton openLargeMontageButtonForTest() {
        return openLargeMontageButton;
    }

    JButton useComboButtonForTest() {
        return useComboButton;
    }

    JButton runButtonForTest() {
        return runButton;
    }

    JCheckBox otsuOverlayCheckBoxForTest() {
        return otsuOverlayCheckBox;
    }

    JCheckBox downstreamVerdictCheckBoxForTest() {
        return downstreamVerdictCheckBox;
    }

    JButton stopDownstreamButtonForTest() {
        return stopDownstreamButton;
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

    HistogramShapeStrip histogramShapeStripForTest() {
        return histogramShapeStrip;
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
        addComponent(editor);
        addComponent(presetEditor);
        presetEditor.setVisible(false);
        addComponent(cropRow());
        addComponent(histogramShapeSlot());
        addHeader("Preview grid");
        addComponent(gridScrollPane());
        addComponent(zRow());
        addComponent(statusPanel());

        openLargeMontageButton = addFooterButton("Open large montage");
        openLargeMontageButton.setEnabled(false);
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
    }

    private JPanel histogramShapeSlot() {
        histogramShapeSlot.setOpaque(false);
        histogramShapeSlot.setVisible(false);
        return histogramShapeSlot;
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
        try {
            currentSweep = withChainCacheNamespace(currentSweepForMode());
        } catch (RuntimeException e) {
            showMessage(e.getMessage());
            setStatusTextNow(mode == Mode.STEPS || mode == Mode.PRESETS
                    ? safe(e.getMessage())
                    : "Choose at least one value for each selected parameter.");
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
        clearHistogramShapeIndicator();
        clearDownstreamVerdicts();
        cells.clear();
        cellsByCombo.clear();
        cellIndexesByCombo.clear();
        resultsByCell.clear();
        if (runButton != null) {
            runButton.setEnabled(false);
        }
        useComboButton.setEnabled(false);
        openLargeMontageButton.setEnabled(false);

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
                chainMacroPostProcessor(),
                context.presetMacroLoader());

        for (int i = 0; i < combos.size(); i++) {
            ParameterCombo combo = combos.get(i);
            VariationCellPanel cell = new VariationCellPanel(combo, croppedSource,
                    new Consumer<ParameterCombo>() {
                        @Override public void accept(ParameterCombo accepted) {
                            selectCombo(accepted, strategy);
                        }
                    },
                    null,
                    i);
            cell.setState("running");
            cell.setZ(zSlider.getValue());
            cell.setOverlayMode(currentOverlayMode());
            cells.add(cell);
            cellsByCombo.put(combo.toCanonicalJson(), cell);
            cellIndexesByCombo.put(combo.toCanonicalJson(), Integer.valueOf(i));
            resultsByCell.add(null);
        }

        gridPanel.setSweep(currentSweep);
        gridPanel.setPresetRowCaptions(mode == Mode.PRESETS
                ? presetEditor.rowCaptionsForSelected()
                : Collections.<String, String>emptyMap());
        gridPanel.setRawSource(croppedSource);
        gridPanel.setCells(cells);
        setStatusTextNow(progressStatus());
        setSuggestionText("Most stable: pending");
        setStrategyText(strategyTextForMode(combos.size()));

        executor = new VariationExecutor(currentSweep,
                strategy,
                runCache,
                new java.util.function.BiConsumer<VariationResult, Integer>() {
                    @Override public void accept(VariationResult result, Integer index) {
                        handleResult(result, index == null ? -1 : index.intValue());
                    }
                },
                null);
        executor.addPropertyChangeListener(evt -> {
            if ("state".equals(evt.getPropertyName())
                    && javax.swing.SwingWorker.StateValue.DONE == evt.getNewValue()) {
                handleExecutorDone();
            }
        });
        executor.execute();
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
        otsuOverlayCheckBox.setOpaque(false);
        otsuOverlayCheckBox.setSelected(showOtsuOverlay);
        otsuOverlayCheckBox.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                setShowOtsuOverlay(otsuOverlayCheckBox.isSelected());
            }
        });
        panel.add(otsuOverlayCheckBox);
        panel.add(Box.createHorizontalStrut(18));
        configureDownstreamControls();
        panel.add(downstreamVerdictCheckBox);
        panel.add(Box.createHorizontalStrut(6));
        panel.add(stopDownstreamButton);
        panel.add(Box.createHorizontalStrut(18));
        panel.add(cellsLabel);
        return panel;
    }

    private void configureDownstreamControls() {
        downstreamVerdictCheckBox.setOpaque(false);
        downstreamVerdictCheckBox.setSelected(false);
        if (!downstreamResolution.isAvailable()) {
            downstreamVerdictCheckBox.setEnabled(false);
            downstreamVerdictCheckBox.setToolTipText(
                    downstreamResolution.unavailableReason());
        } else {
            downstreamVerdictCheckBox.setEnabled(true);
            downstreamVerdictCheckBox.setToolTipText(
                    "Runs the active segmenter on each filter output. May take 30-60 s.");
        }
        downstreamVerdictCheckBox.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                handleDownstreamToggle();
            }
        });
        stopDownstreamButton.setEnabled(false);
        stopDownstreamButton.setOpaque(false);
        stopDownstreamButton.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                cancelDownstreamWorker();
                setStatusTextNow("Downstream cancelled");
            }
        });
    }

    private void handleDownstreamToggle() {
        if (!downstreamVerdictCheckBox.isSelected()) {
            cancelDownstreamWorker();
            downstreamStartedForCurrentResults = false;
            clearDownstreamVerdicts();
            setStatusTextNow(completionStatus());
            return;
        }
        if (!downstreamResolution.isAvailable()) {
            downstreamVerdictCheckBox.setSelected(false);
            setStatusTextNow(downstreamResolution.unavailableReason());
            return;
        }
        if (completedCount < cells.size() || cells.isEmpty()) {
            setStatusTextNow("Downstream will run after the preview grid completes.");
            return;
        }
        startDownstreamVerdict();
    }

    private void setShowOtsuOverlay(boolean show) {
        showOtsuOverlay = show;
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
        group.add(paramsButton);
        group.add(stepsButton);
        group.add(presetsButton);
        paramsButton.setSelected(true);
        stepsButton.setEnabled(true);
        presetsButton.setEnabled(true);
        stepsButton.setToolTipText("Try native filter alternatives at one chain step");
        presetsButton.setToolTipText("Compare readable filter presets");

        paramsButton.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                setMode(Mode.PARAMS);
            }
        });
        stepsButton.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                setMode(Mode.STEPS);
            }
        });
        presetsButton.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                setMode(Mode.PRESETS);
            }
        });

        row.add(new JLabel("Mode: "));
        row.add(paramsButton);
        row.add(Box.createHorizontalStrut(6));
        row.add(stepsButton);
        row.add(Box.createHorizontalStrut(6));
        row.add(presetsButton);
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
        if (mode == Mode.PRESETS) {
            return;
        }
        if (mode == Mode.STEPS) {
            handleStepsChainStateChanged(stepIndex, newState);
            return;
        }
        Set<Integer> swept = chainRibbon.stepIndexesInState(
                ChainRibbon.StepState.SWEPT);
        Set<Integer> disabled = chainRibbon.disabledStepIndexes();
        editor.setChainStepFilter(swept, disabled);
        refreshCellEstimate();
        if (!disabled.isEmpty() && !isLinearMacro(context.baseMacro().render())) {
            setStatusTextNow("Open the visual builder to vary branched macros.");
            showMessage("Open the visual builder to vary branched macros.");
            return;
        }
        start();
    }

    private void updateModeEditorVisibility() {
        if (editor != null) {
            editor.setVisible(mode != Mode.PRESETS);
        }
        if (presetEditor != null) {
            presetEditor.setVisible(mode == Mode.PRESETS);
        }
        Window window = getWindow();
        if (window != null) {
            window.validate();
        }
    }

    private void handleStepsChainStateChanged(int stepIndex,
                                              ChainRibbon.StepState newState) {
        configureStepsFocusability();
        Set<Integer> disabled = chainRibbon.disabledStepIndexes();
        if (!disabled.isEmpty() && !isLinearMacro(context.baseMacro().render())) {
            setStatusTextNow("Open the visual builder to vary branched macros.");
            showMessage("Open the visual builder to vary branched macros.");
            return;
        }
        if (chainRibbon.focusedStepIndex() < 0) {
            if (!defaultFocusFirstStepsSlot()) {
                refreshCellEstimate();
            }
            return;
        }
        refreshCellEstimate();
        start();
    }

    private ParameterSweep currentSweepForMode() {
        if (mode == Mode.PRESETS) {
            return presetEditor.currentSweep(currentCropSpec,
                    context.channelName(),
                    context.sourceImageHash(),
                    context.cacheNamespace() + ":presets");
        }
        if (mode == Mode.STEPS) {
            int focused = chainRibbon.focusedStepIndex();
            if (focused < 0) {
                throw new IllegalStateException("Choose a focusable chain step.");
            }
            return buildStepsSubstitutionSweep(context.baseMacro(),
                    currentCropSpec,
                    context.channelName(),
                    context.sourceImageHash(),
                    context.cacheNamespace() + ":steps:" + focused,
                    focused);
        }
        return editor.currentSweep();
    }

    static ParameterSweep buildPresetsSweepForTest(List<String> presetNames,
                                                   String xParamKey,
                                                   List<?> xValues,
                                                   CropSpec cropSpec,
                                                   String channelName,
                                                   String sourceImageHash,
                                                   String cacheNamespace) {
        return buildPresetsSweep(presetNames, xParamKey, xValues, cropSpec,
                channelName, sourceImageHash, cacheNamespace);
    }

    private static ParameterSweep buildPresetsSweep(List<String> presetNames,
                                                    String xParamKey,
                                                    List<?> xValues,
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
        String paramKey = xParamKey == null ? "" : xParamKey.trim();
        if (paramKey.isEmpty()) {
            throw new IllegalStateException("Choose a numeric X-axis parameter.");
        }
        if (xValues == null || xValues.isEmpty()) {
            throw new IllegalStateException("Choose at least one X-axis value.");
        }
        LinkedHashMap<ParameterKey, ParameterValueList> values =
                new LinkedHashMap<ParameterKey, ParameterValueList>();
        values.put(PresetSweepKey.xValue(paramKey),
                new ParameterValueList(xValues));
        values.put(PresetSweepKey.presetName(),
                new ParameterValueList(names));
        values.put(PresetSweepKey.xParamKey(),
                new ParameterValueList(Collections.singletonList(paramKey)));
        return new ParameterSweep(ParameterSweep.Method.FILTER,
                values,
                cropSpec,
                channelName,
                sourceImageHash,
                cacheNamespace);
    }

    private void configureStepsFocusability() {
        StepFocusModel focusModel = stepsFocusModel(context.baseMacro(),
                chainRibbon.disabledStepIndexes());
        chainRibbon.setFocusableStepIndexes(focusModel.focusable,
                focusModel.reasons);
    }

    private boolean defaultFocusFirstStepsSlot() {
        configureStepsFocusability();
        StepFocusModel focusModel = stepsFocusModel(context.baseMacro(),
                chainRibbon.disabledStepIndexes());
        if (focusModel.focusable.isEmpty()) {
            setStatusTextNow("No native alternatives available for Steps mode.");
            return false;
        }
        int focused = chainRibbon.focusedStepIndex();
        if (focusModel.focusable.contains(Integer.valueOf(focused))) {
            start();
            return true;
        }
        chainRibbon.focusStep(focusModel.focusable.iterator().next().intValue());
        return true;
    }

    static ParameterSweep buildStepsSubstitutionSweepForTest(
            FilterMacroEditorModel.MacroDefinition macro,
            CropSpec cropSpec,
            String channelName,
            String sourceImageHash,
            String cacheNamespace,
            int stepIndex) {
        return buildStepsSubstitutionSweep(macro, cropSpec, channelName,
                sourceImageHash, cacheNamespace, stepIndex);
    }

    private static ParameterSweep buildStepsSubstitutionSweep(
            FilterMacroEditorModel.MacroDefinition macro,
            CropSpec cropSpec,
            String channelName,
            String sourceImageHash,
            String cacheNamespace,
            int stepIndex) {
        OpType focusedType = opTypeForStep(macro, stepIndex);
        SlotRole role = FilterAlternatives.slotRoleFor(focusedType);
        if (role == null || !FilterAlternatives.hasUsefulAlternatives(role)) {
            throw new IllegalStateException("No native alternatives available");
        }

        List<Alternative> alternatives = FilterAlternatives.alternativesFor(role);
        List<String> filterLabels = new ArrayList<String>();
        for (int i = 0; i < alternatives.size(); i++) {
            filterLabels.add(alternatives.get(i).label());
        }

        List<String> scaleLabels = scaleLabelsFor(alternatives);
        LinkedHashMap<ParameterKey, ParameterValueList> values =
                new LinkedHashMap<ParameterKey, ParameterValueList>();
        values.put(SlotSubstitutionKey.filterAxis(stepIndex, role.name()),
                new ParameterValueList(filterLabels));
        values.put(SlotSubstitutionKey.scaleAxis(stepIndex, role.name()),
                new ParameterValueList(scaleLabels));
        return new ParameterSweep(ParameterSweep.Method.FILTER,
                values,
                cropSpec,
                channelName,
                sourceImageHash,
                cacheNamespace);
    }

    private static StepFocusModel stepsFocusModel(
            FilterMacroEditorModel.MacroDefinition macro,
            Set<Integer> disabledStepIndexes) {
        StepFocusModel model = new StepFocusModel();
        List<OpType> types = opTypesForSteps(macro);
        for (int i = 0; i < types.size(); i++) {
            if (disabledStepIndexes != null
                    && disabledStepIndexes.contains(Integer.valueOf(i))) {
                model.reasons.put(Integer.valueOf(i), "Step is bypassed or off");
                continue;
            }
            SlotRole role = FilterAlternatives.slotRoleFor(types.get(i));
            if (role == null) {
                model.reasons.put(Integer.valueOf(i),
                        "No native alternatives available");
                continue;
            }
            if (!FilterAlternatives.hasUsefulAlternatives(role)) {
                model.reasons.put(Integer.valueOf(i),
                        "No native alternatives available");
                continue;
            }
            model.focusable.add(Integer.valueOf(i));
        }
        return model;
    }

    private static List<String> scaleLabelsFor(List<Alternative> alternatives) {
        boolean anyScaled = false;
        if (alternatives != null) {
            for (int i = 0; i < alternatives.size(); i++) {
                Alternative alternative = alternatives.get(i);
                if (alternative != null
                        && !CanonicalScale.isParameterless(alternative.type())) {
                    anyScaled = true;
                    break;
                }
            }
        }
        if (!anyScaled) {
            return Collections.singletonList(SlotSubstitutionCombo.DEFAULT_SCALE_LABEL);
        }
        return Arrays.asList(CanonicalScale.SMALL.label(),
                CanonicalScale.MEDIUM.label(),
                CanonicalScale.LARGE.label());
    }

    private static OpType opTypeForStep(FilterMacroEditorModel.MacroDefinition macro,
                                        int stepIndex) {
        List<OpType> types = opTypesForSteps(macro);
        if (stepIndex < 0 || stepIndex >= types.size()) {
            throw new IllegalArgumentException("Filter step index is out of bounds: "
                    + stepIndex);
        }
        return types.get(stepIndex);
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
        private final JComboBox<String> xParamCombo = new JComboBox<String>();
        private final ValueChipPanel xValues;
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
            String defaultParam = enumeration.defaultXParamKey();
            List<String> params = orderedParamChoices(defaultParam);
            for (int i = 0; i < params.size(); i++) {
                xParamCombo.addItem(params.get(i));
            }
            if (defaultParam.length() > 0) {
                xParamCombo.setSelectedItem(defaultParam);
            }
            xValues = new ValueChipPanel(new ParameterValueList(
                    suggestedValues(defaultParam)), ValueChipPanel.doubleParser());
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
            String xParam = selectedXParam();
            if (xParam.length() == 0) {
                throw new IllegalStateException(statusMessage());
            }
            if (!anySelectedHasParam(selected, xParam)) {
                throw new IllegalStateException(statusMessage());
            }
            return buildPresetsSweep(namesFor(selected),
                    xParam,
                    xValues.currentValueList().values(),
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

        void setXParamForTest(String paramKey) {
            if (paramKey == null) {
                return;
            }
            if (((javax.swing.DefaultComboBoxModel<String>) xParamCombo.getModel())
                    .getIndexOf(paramKey) < 0) {
                xParamCombo.addItem(paramKey);
            }
            xParamCombo.setSelectedItem(paramKey);
            fireChanged();
        }

        void setXValuesForTest(List<?> values) {
            xValues.setValues(values);
            fireChanged();
        }

        private void buildUi() {
            JPanel controls = new JPanel();
            controls.setOpaque(false);
            controls.setLayout(new BoxLayout(controls, BoxLayout.X_AXIS));
            controls.setBorder(BorderFactory.createEmptyBorder(6, 8, 2, 8));
            controls.add(new JLabel("X parameter: "));
            controls.add(xParamCombo);
            controls.add(Box.createHorizontalStrut(12));
            controls.add(new JLabel("Values: "));
            controls.add(xValues);
            controls.add(Box.createHorizontalGlue());

            presetList.setVisibleRowCount(Math.min(6,
                    Math.max(3, presetModel.getSize())));
            presetList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            presetList.setCellRenderer(new PresetListRenderer());
            JScrollPane scroller = new JScrollPane(presetList);
            scroller.setBorder(BorderFactory.createEmptyBorder(2, 8, 8, 8));
            scroller.setPreferredSize(new Dimension(420, 96));

            add(controls, BorderLayout.NORTH);
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
            xParamCombo.addActionListener(new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) {
                    fireChanged();
                }
            });
            xValues.addChangeListener(new ChangeListener() {
                @Override public void stateChanged(ChangeEvent e) {
                    fireChanged();
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

        private String selectedXParam() {
            Object selected = xParamCombo.getSelectedItem();
            return selected == null ? "" : String.valueOf(selected).trim();
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
            String xParam = selectedXParam();
            if (xParam.length() == 0) {
                return "Choose a numeric X-axis parameter.";
            }
            if (!anySelectedHasParam(selected, xParam)) {
                return "No selected preset has numeric parameter " + xParam + ".";
            }
            return "";
        }

        private boolean anySelectedHasParam(List<PresetEnumerator.PresetInfo> selected,
                                            String xParam) {
            for (int i = 0; i < selected.size(); i++) {
                if (selected.get(i).hasNumericParam(xParam)) {
                    return true;
                }
            }
            return false;
        }

        private List<String> namesFor(List<PresetEnumerator.PresetInfo> selected) {
            List<String> out = new ArrayList<String>();
            for (int i = 0; i < selected.size(); i++) {
                out.add(selected.get(i).name());
            }
            return out;
        }

        private List<String> orderedParamChoices(String defaultParam) {
            List<String> out = new ArrayList<String>();
            addIfPresent(out, defaultParam);
            List<String> common = enumeration.commonNumericParamKeys();
            for (int i = 0; i < common.size(); i++) {
                addIfPresent(out, common.get(i));
            }
            List<String> all = enumeration.allNumericParamKeys();
            for (int i = 0; i < all.size(); i++) {
                addIfPresent(out, all.get(i));
            }
            return out;
        }

        private static void addIfPresent(List<String> out, String value) {
            String safe = value == null ? "" : value.trim();
            if (safe.length() == 0 || out.contains(safe)) {
                return;
            }
            out.add(safe);
        }

        private List<Object> suggestedValues(String paramKey) {
            double base = 1.0d;
            List<PresetEnumerator.PresetInfo> readable =
                    enumeration.readablePresets();
            for (int i = 0; i < readable.size(); i++) {
                PresetEnumerator.NumericParam param =
                        readable.get(i).numericParam(paramKey);
                if (param != null) {
                    base = param.baseValue();
                    break;
                }
            }
            List<Object> values = new ArrayList<Object>();
            if (Math.abs(base) < 0.0000001d) {
                values.add(Double.valueOf(0.0d));
                values.add(Double.valueOf(1.0d));
                values.add(Double.valueOf(2.0d));
            } else {
                values.add(Double.valueOf(round(base * 0.5d)));
                values.add(Double.valueOf(round(base)));
                values.add(Double.valueOf(round(base * 2.0d)));
            }
            return values;
        }

        private void fireChanged() {
            ChangeEvent event = new ChangeEvent(this);
            for (int i = 0; i < listeners.size(); i++) {
                listeners.get(i).stateChanged(event);
            }
        }

        private static double round(double value) {
            return Math.round(value * 1000.0d) / 1000.0d;
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

    private FilterSweepStrategy.MacroPostProcessor chainMacroPostProcessor() {
        if (mode == Mode.PRESETS) {
            return null;
        }
        final Set<Integer> disabled = chainRibbon.disabledStepIndexes();
        if (disabled.isEmpty()) {
            return null;
        }
        final List<Integer> entryLineIndexes =
                new ArrayList<Integer>(chainEntryLineIndexes);
        return new FilterSweepStrategy.MacroPostProcessor() {
            @Override public String apply(String macroContent) {
                return renderMacroWithDisabledSteps(macroContent, disabled,
                        entryLineIndexes);
            }
        };
    }

    private ParameterSweep withChainCacheNamespace(ParameterSweep sweep) {
        if (sweep == null) {
            return null;
        }
        if (mode == Mode.PRESETS) {
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

    private static boolean isLinearMacro(String macroContent) {
        try {
            return IjmToDagLoader.load(macroContent).isLinear();
        } catch (RuntimeException e) {
            return false;
        }
    }

    static String renderMacroWithDisabledStepsForTest(String macroContent,
                                                      Set<Integer> disabledStepIndexes,
                                                      List<Integer> entryLineIndexes) {
        return renderMacroWithDisabledSteps(macroContent, disabledStepIndexes,
                entryLineIndexes);
    }

    private static String renderMacroWithDisabledSteps(String macroContent,
                                                       Set<Integer> disabledStepIndexes,
                                                       List<Integer> entryLineIndexes) {
        if (disabledStepIndexes == null || disabledStepIndexes.isEmpty()) {
            return macroContent;
        }
        DagIR dag = IjmToDagLoader.load(macroContent);
        if (!dag.isLinear()) {
            throw new IllegalStateException(
                    "Open the visual builder to vary branched macros.");
        }
        if (dag.lines.isEmpty()) {
            return macroContent;
        }
        Set<Integer> disabledOps = disabledOpIndexes(macroContent,
                disabledStepIndexes, entryLineIndexes);
        DagLine line = dag.lines.get(0);
        List<DagNode> clonedOps = new ArrayList<DagNode>();
        for (int i = 0; i < line.ops.size(); i++) {
            DagNode source = line.ops.get(i);
            DagNode copy = new DagNode(source.id, source.type, source.args,
                    source.commandName, source.menuPath);
            copy.disabled = source.disabled || disabledOps.contains(Integer.valueOf(i));
            clonedOps.add(copy);
        }
        DagIR modified = new DagIR(dag.version,
                Collections.singletonList(new DagLine(line.id, clonedOps)),
                dag.combiners,
                dag.output,
                dag.executionTier);
        return DagToIjmEmitter.emit(modified);
    }

    private static Set<Integer> disabledOpIndexes(String macroContent,
                                                  Set<Integer> disabledStepIndexes,
                                                  List<Integer> entryLineIndexes) {
        Set<Integer> disabledLines = new HashSet<Integer>();
        if (entryLineIndexes != null) {
            for (Integer stepIndex : disabledStepIndexes) {
                if (stepIndex != null
                        && stepIndex.intValue() >= 0
                        && stepIndex.intValue() < entryLineIndexes.size()) {
                    disabledLines.add(entryLineIndexes.get(stepIndex.intValue()));
                }
            }
        }

        Set<Integer> out = new LinkedHashSet<Integer>();
        String[] lines = (macroContent == null ? "" : macroContent)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .split("\n", -1);
        int opIndex = 0;
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            if (!isDagOpLine(lines[lineIndex])) {
                continue;
            }
            if (disabledLines.contains(Integer.valueOf(lineIndex))) {
                out.add(Integer.valueOf(opIndex));
            }
            opIndex++;
        }
        if (out.size() < disabledStepIndexes.size()) {
            for (Integer stepIndex : disabledStepIndexes) {
                if (stepIndex != null && stepIndex.intValue() >= 0) {
                    out.add(stepIndex);
                }
            }
        }
        return out;
    }

    private static boolean isDagOpLine(String line) {
        String trimmed = line == null ? "" : line.trim();
        return !trimmed.isEmpty()
                && !trimmed.startsWith("//")
                && !trimmed.startsWith("/*")
                && !trimmed.startsWith("*");
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

    private JScrollPane gridScrollPane() {
        JScrollPane scrollPane = new JScrollPane(gridPanel);
        scrollPane.setBorder(BorderFactory.createLineBorder(new Color(0x3A, 0x40, 0x46)));
        scrollPane.setPreferredSize(new Dimension(780, 440));
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.getViewport().setBackground(new Color(0x1E, 0x20, 0x24));
        return scrollPane;
    }

    private JPanel zRow() {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        row.add(new JLabel("z:"), BorderLayout.WEST);
        configureZSlider();
        row.add(zSlider, BorderLayout.CENTER);
        row.add(zLabel, BorderLayout.EAST);
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

    private void configureZSlider() {
        int slices = sourceSliceCount();
        zSlider.setMinimum(1);
        zSlider.setMaximum(Math.max(1, slices));
        zSlider.setValue(1);
        zSlider.setEnabled(slices > 1);
        zLabel.setText("1/" + Math.max(1, slices));
        zSlider.addChangeListener(new ChangeListener() {
            @Override public void stateChanged(ChangeEvent e) {
                int z = zSlider.getValue();
                zLabel.setText(z + "/" + Math.max(1, zSlider.getMaximum()));
                gridPanel.broadcastZ(z);
            }
        });
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
            gridPanel.setSweep(sweep);
            gridPanel.setPresetRowCaptions(mode == Mode.PRESETS
                    ? presetEditor.rowCaptionsForSelected()
                    : Collections.<String, String>emptyMap());
            if (runButton != null && !isExecutorActive()) {
                runButton.setEnabled(true);
            }
        } catch (RuntimeException e) {
            cellsLabel.setText("Cells: ?");
            if (!isExecutorActive()) {
                setStatusTextNow(mode == Mode.STEPS || mode == Mode.PRESETS
                        ? safe(e.getMessage())
                        : "Choose at least one value for each selected parameter.");
                setStrategyText(" ");
            }
            if (runButton != null && !isExecutorActive()) {
                runButton.setEnabled(false);
            }
        }
    }

    private void handleResult(VariationResult result, int index) {
        if (result == null) {
            return;
        }
        String comboId = result.combo().toCanonicalJson();
        VariationCellPanel cell = cellsByCombo.get(comboId);
        Integer comboIndex = cellIndexesByCombo.get(comboId);
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
        setStatusTextNow(progressStatus());
        if (completedCount >= cells.size() && !cells.isEmpty()) {
            updateHistogramShapeIndicator();
            if (downstreamVerdictCheckBox.isSelected()) {
                startDownstreamVerdict();
            }
        }
        if (selectedCombo != null
                && selectedCombo.toCanonicalJson().equals(comboId)) {
            updateSelectedStatus(result);
        }
    }

    private void handleExecutorDone() {
        VariationExecutor worker = executor;
        if (worker == null) {
            return;
        }
        if (runButton != null) {
            runButton.setEnabled(true);
        }
        if (worker.isCancelled()) {
            setStatusTextNow("Cancelled");
            markRunningCellsCancelled();
            return;
        }
        try {
            worker.get();
            failedCount = countFailures();
            updateHistogramShapeIndicator();
            setStatusTextNow(completionStatus());
            if (downstreamVerdictCheckBox.isSelected()) {
                startDownstreamVerdict();
            }
        } catch (Exception e) {
            failedCount = countFailures();
            setStatusTextNow("Error: " + safe(e.getMessage()));
            showMessage(e.getMessage());
        }
    }

    private void updateHistogramShapeIndicator() {
        clearStableShapeRibbons();
        if (mode == Mode.PRESETS) {
            updatePresetHistogramShapeIndicator();
            return;
        }
        if (currentSweep == null
                || cells.isEmpty()
                || resultsByCell.size() != cells.size()
                || completedCount < cells.size()
                || !allCellsSuccessful()) {
            clearHistogramShapeStrip();
            setSuggestionText("No stable shape plateau detected");
            return;
        }

        ParameterKey axis = singleNumericFilterAxis();
        if (axis == null) {
            clearHistogramShapeStrip();
            setSuggestionText("No stable shape plateau detected");
            return;
        }

        HistogramShapeStability.Result result =
                HistogramShapeStability.detect(resultsByCell, axis);
        if (result == null || !result.hasPlateau()) {
            clearHistogramShapeStrip();
            setSuggestionText("No stable shape plateau detected");
            return;
        }

        ensureHistogramShapeStrip(result);
        VariationCellPanel winner = cellsByCombo.get(
                result.winnerCombo.toCanonicalJson());
        if (winner != null) {
            winner.setRibbonLabel("STABLE SHAPE");
            winner.setBorderHint(VariationCellPanel.BorderHint.KNEE);
        }
        setSuggestionText("Most stable shape at "
                + axisLabelForStatus(axis)
                + " = "
                + formatValue(result.winnerCombo.get(axis)));
    }

    private void updatePresetHistogramShapeIndicator() {
        if (currentSweep == null
                || cells.isEmpty()
                || resultsByCell.size() != cells.size()
                || completedCount < cells.size()) {
            clearHistogramShapeStrip();
            setSuggestionText("No stable shape plateau detected");
            return;
        }
        ParameterKey xAxis = presetXValueAxis();
        ParameterKey presetAxis = presetNameAxis();
        if (xAxis == null || presetAxis == null) {
            clearHistogramShapeStrip();
            setSuggestionText("No stable shape plateau detected");
            return;
        }

        HistogramShapeStability.Result best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        List<Object> presetNames = currentSweep.valueLists().get(presetAxis).values();
        for (int i = 0; i < presetNames.size(); i++) {
            List<VariationResult> row = successfulPresetResults(
                    presetAxis, presetNames.get(i));
            HistogramShapeStability.Result result =
                    HistogramShapeStability.detect(row, xAxis);
            if (result == null || !result.hasPlateau()) {
                continue;
            }
            VariationCellPanel rowWinner = cellsByCombo.get(
                    result.winnerCombo.toCanonicalJson());
            if (rowWinner != null) {
                rowWinner.setRibbonLabel("STABLE SHAPE");
                rowWinner.setBorderHint(VariationCellPanel.BorderHint.KNEE);
            }
            double score = plateauScore(result);
            if (score < bestScore) {
                bestScore = score;
                best = result;
            }
        }

        if (best == null) {
            clearHistogramShapeStrip();
            setSuggestionText("No stable shape plateau detected");
            return;
        }

        ensureHistogramShapeStrip(best);
        PresetSweepCombo winner = PresetSweepCombo.from(best.winnerCombo);
        String presetName = winner == null ? "" : winner.presetName();
        setSuggestionText("Most stable shape: "
                + presetName
                + " at "
                + axisLabelForStatus(xAxis)
                + " = "
                + formatValue(best.winnerCombo.get(xAxis)));
    }

    private List<VariationResult> successfulPresetResults(ParameterKey presetAxis,
                                                          Object presetName) {
        List<VariationResult> out = new ArrayList<VariationResult>();
        for (int i = 0; i < resultsByCell.size(); i++) {
            VariationResult result = resultsByCell.get(i);
            if (result == null
                    || result.hasError()
                    || result.kind() != VariationResult.Kind.FILTER
                    || !valueEquals(result.combo().get(presetAxis), presetName)) {
                continue;
            }
            out.add(result);
        }
        return out;
    }

    private ParameterKey presetXValueAxis() {
        if (currentSweep == null) {
            return null;
        }
        for (ParameterKey key : currentSweep.valueLists().keySet()) {
            if (key instanceof PresetSweepKey
                    && ((PresetSweepKey) key).role() == PresetSweepKey.Role.X_VALUE) {
                return key;
            }
        }
        return null;
    }

    private ParameterKey presetNameAxis() {
        if (currentSweep == null) {
            return null;
        }
        for (ParameterKey key : currentSweep.valueLists().keySet()) {
            if (key instanceof PresetSweepKey
                    && ((PresetSweepKey) key).role() == PresetSweepKey.Role.PRESET_NAME) {
                return key;
            }
        }
        return null;
    }

    private static double plateauScore(HistogramShapeStability.Result result) {
        if (result == null || result.distances.length == 0) {
            return Double.POSITIVE_INFINITY;
        }
        int[] range = result.plateauRange();
        if (range == null || range.length < 2) {
            return Double.POSITIVE_INFINITY;
        }
        int start = Math.max(0, Math.min(range[0], range[1]));
        int end = Math.min(result.distances.length - 1,
                Math.max(range[0], range[1]) - 1);
        if (end < start) {
            return Double.POSITIVE_INFINITY;
        }
        double total = 0.0d;
        int count = 0;
        for (int i = start; i <= end; i++) {
            total += result.distances[i];
            count++;
        }
        return count == 0 ? Double.POSITIVE_INFINITY : total / count;
    }

    private void ensureHistogramShapeStrip(HistogramShapeStability.Result result) {
        if (histogramShapeStrip == null) {
            histogramShapeStrip = new HistogramShapeStrip(result);
            histogramShapeSlot.removeAll();
            histogramShapeSlot.add(histogramShapeStrip, BorderLayout.CENTER);
        } else {
            histogramShapeStrip.setData(result);
        }
        histogramShapeStrip.setVisible(true);
        histogramShapeSlot.setVisible(true);
        histogramShapeSlot.revalidate();
        histogramShapeSlot.repaint();
        Window window = getWindow();
        if (window != null) {
            window.validate();
        }
    }

    private void clearHistogramShapeIndicator() {
        clearStableShapeRibbons();
        clearHistogramShapeStrip();
    }

    private void clearStableShapeRibbons() {
        for (int i = 0; i < cells.size(); i++) {
            cells.get(i).setBorderHint(VariationCellPanel.BorderHint.NONE);
        }
    }

    private void clearHistogramShapeStrip() {
        histogramShapeStrip = null;
        histogramShapeSlot.removeAll();
        histogramShapeSlot.setVisible(false);
        histogramShapeSlot.revalidate();
        histogramShapeSlot.repaint();
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

    private ParameterKey singleNumericFilterAxis() {
        if (currentSweep == null) {
            return null;
        }
        List<ParameterKey> swept = new ArrayList<ParameterKey>();
        for (Map.Entry<ParameterKey, ParameterValueList> entry
                : currentSweep.valueLists().entrySet()) {
            ParameterValueList values = entry.getValue();
            if (values == null || values.size() <= 1) {
                continue;
            }
            ParameterKey key = entry.getKey();
            if (key == null
                    || key.valueKind() != ParameterKey.ValueKind.NUMBER
                    || !allNumeric(values)) {
                return null;
            }
            swept.add(key);
        }
        return swept.size() == 1 ? swept.get(0) : null;
    }

    private static boolean allNumeric(ParameterValueList values) {
        if (values == null) {
            return false;
        }
        for (int i = 0; i < values.size(); i++) {
            if (!(values.get(i) instanceof Number)) {
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
        stopDownstreamButton.setEnabled(true);
        setStatusTextNow("Downstream: starting");
        final DownstreamSegmenter segmenter =
                downstreamResolution.segmenter().forFilterSweep(currentSweep);
        final List<VariationResult> snapshot =
                new ArrayList<VariationResult>(resultsByCell);
        final ImagePlus baseline = currentBaselineCrop;
        final VariationCache cache = currentRunCache;
        downstreamWorker = new SwingWorker<Map<ParameterCombo, DownstreamVerdict.Verdict>, Void>() {
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
                                        handleDownstreamProgress(progress);
                                    }
                                });
                            }
                        });
            }

            @Override protected void done() {
                stopDownstreamButton.setEnabled(false);
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
        downstreamWorker.execute();
    }

    private void handleDownstreamProgress(DownstreamVerdict.Progress progress) {
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
            worker.cancel(false);
        }
        stopDownstreamButton.setEnabled(false);
    }

    private void acceptAndClose(ParameterCombo combo, FilterSweepStrategy strategy) {
        if (onAccept != null && combo != null && strategy != null) {
            onAccept.accept(strategy.renderMacroForCombo(combo));
        }
        dispose();
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
        updateSelectedStatus(resultForCombo(combo));
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
            worker.cancel(false);
        }
    }

    private boolean isExecutorActive() {
        return executor != null && !executor.isDone();
    }

    private String progressStatus() {
        int total = cells.isEmpty() ? estimatedCellCount() : cells.size();
        String base = completedCount + "/" + Math.max(0, total);
        if (failedCount > 0) {
            return base + " (" + failedCount + " failed)";
        }
        return base;
    }

    private String completionStatus() {
        int total = cells.isEmpty() ? estimatedCellCount() : cells.size();
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
        statusLabel.setText(safe(text));
    }

    private void setSuggestionText(String text) {
        suggestionLabel.setText(safe(text));
    }

    private void setStrategyText(String text) {
        strategyLabel.setText(safe(text));
    }

    private String strategyTextForMode(int comboCount) {
        if (mode == Mode.STEPS) {
            return "Using Filter substitution (" + comboCount + " cells)";
        }
        if (mode == Mode.PRESETS) {
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
