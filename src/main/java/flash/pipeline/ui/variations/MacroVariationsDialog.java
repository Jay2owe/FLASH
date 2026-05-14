package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.image.dag.DagIR;
import flash.pipeline.image.dag.DagLine;
import flash.pipeline.image.dag.DagNode;
import flash.pipeline.image.dag.DagToIjmEmitter;
import flash.pipeline.image.dag.IjmToDagLoader;
import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.variations.analysis.HistogramShapeStability;
import flash.pipeline.ui.variations.strategy.FilterSweepStrategy;

import ij.ImagePlus;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
    private final Map<String, VariationCellPanel> cellsByCombo =
            new HashMap<String, VariationCellPanel>();
    private final Map<String, Integer> cellIndexesByCombo =
            new HashMap<String, Integer>();
    private final List<VariationCellPanel> cells = new ArrayList<VariationCellPanel>();
    private final List<VariationResult> resultsByCell =
            new ArrayList<VariationResult>();

    private JButton openLargeMontageButton;
    private JButton useComboButton;
    private JButton runButton;
    private VariationExecutor executor;
    private ParameterSweep currentSweep;
    private HistogramShapeStrip histogramShapeStrip;
    private Mode mode = Mode.PARAMS;
    private CropSpec currentCropSpec;
    private ParameterCombo selectedCombo;
    private FilterSweepStrategy selectedStrategy;
    private VariationCellPanel selectedCell;
    private boolean showOtsuOverlay;
    private boolean suppressCropEvents;
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
        this.chainRibbon = new ChainRibbon(context.baseMacro());
        this.chainEntryLineIndexes = ChainRibbon.entryLineIndexes(context.baseMacro());
        this.editor.setChainStepFilter(Collections.<Integer>emptySet(),
                Collections.<Integer>emptySet());
        this.currentCropSpec = context.initialCropSpec();
        setDefaultButtonsVisible(false);
        buildUi();
        refreshCellEstimate();
    }

    public void dispose() {
        cancelExecutor();
        Window window = getWindow();
        if (window != null) {
            window.dispose();
        }
    }

    void setMode(Mode mode) {
        if (mode != Mode.PARAMS) {
            paramsButton.setSelected(true);
            return;
        }
        this.mode = Mode.PARAMS;
        paramsButton.setSelected(true);
    }

    Mode modeForTest() {
        return mode;
    }

    ParameterSweepEditor editorForTest() {
        return editor;
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

    private void buildUi() {
        addComponent(headerPanel());
        addComponent(modeToggleRow());
        addComponent(chainRibbonRow());
        addHeader("Parameters to sweep");
        addComponent(editor);
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
        try {
            currentSweep = withChainCacheNamespace(editor.currentSweep());
        } catch (RuntimeException e) {
            showMessage(e.getMessage());
            setStatusTextNow("Choose at least one value for each selected parameter.");
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
        selectedCombo = null;
        selectedStrategy = null;
        if (selectedCell != null) {
            selectedCell.setSelectedForCompare(false);
            selectedCell = null;
        }
        clearHistogramShapeIndicator();
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
        List<ParameterCombo> combos = currentSweep.combos();
        VariationCache runCache = new VariationCache(context.configContext());
        final FilterSweepStrategy strategy = new FilterSweepStrategy(
                context.baseMacro(),
                context.previewAdapter(),
                croppedSource,
                runCache,
                chainMacroPostProcessor());

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
        gridPanel.setRawSource(croppedSource);
        gridPanel.setCells(cells);
        setStatusTextNow(progressStatus());
        setSuggestionText("Most stable: pending");
        setStrategyText("Using Filter sweep (" + combos.size() + " cells)");

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
        panel.add(cellsLabel);
        return panel;
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
        stepsButton.setEnabled(false);
        presetsButton.setEnabled(false);
        stepsButton.setToolTipText("Coming soon");
        presetsButton.setToolTipText("Coming soon");

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

    private FilterSweepStrategy.MacroPostProcessor chainMacroPostProcessor() {
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
            ParameterSweep sweep = editor.currentSweep();
            cellsLabel.setText("Cells: " + sweep.cellCount());
            if (!isExecutorActive()) {
                setStatusTextNow(sweep.cellCount() + " cells, crop "
                        + cropSummary(sweep.cropSpec()));
                setStrategyText(" ");
            }
            gridPanel.setSweep(sweep);
        } catch (RuntimeException e) {
            cellsLabel.setText("Cells: ?");
            if (!isExecutorActive()) {
                setStatusTextNow("Choose at least one value for each selected parameter.");
                setStrategyText(" ");
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
        } catch (Exception e) {
            failedCount = countFailures();
            setStatusTextNow("Error: " + safe(e.getMessage()));
            showMessage(e.getMessage());
        }
    }

    private void updateHistogramShapeIndicator() {
        clearStableShapeRibbons();
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
            long count = editor.currentSweep().cellCount();
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

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
