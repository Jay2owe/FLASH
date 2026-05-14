package flash.pipeline.ui.variations;

import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.variations.analysis.IouStability;
import flash.pipeline.ui.variations.analysis.KneeDetector;
import flash.pipeline.ui.variations.strategy.VariationStrategyChooser;

import ij.ImagePlus;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class VariationsDialog extends PipelineDialog {

    private final VariationEngineContext context;
    private final Consumer<ParameterCombo> onAccept;
    private final ParameterSweepEditor editor;
    private final VariationGridPanel gridPanel = new VariationGridPanel();
    private final JLabel cellsLabel = new JLabel("Cells: 1");
    private final JLabel zLabel = new JLabel("1/1");
    private final JLabel suggestionLabel = new JLabel("Suggested: pending");
    private final JLabel statusLabel = new JLabel("Status: idle");
    private final JSlider zSlider = new JSlider(1, 1, 1);
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

    private VariationExecutor executor;
    private ParameterSweep currentSweep;
    private ParameterCombo pendingCompareCombo;
    private VariationCellPanel pendingCompareCell;
    private CropSpec currentCropSpec = CropSpec.centre256();
    private boolean suppressCropEvents;
    private int completedCount;
    private VariationStrategy strategyForTest;

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
        setDefaultButtonsVisible(false);
        buildUi();
        installWindowCleanup();
        refreshCellEstimate();
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
        Window window = getWindow();
        if (window != null) {
            window.dispose();
        }
    }

    ParameterSweepEditor editorForTest() {
        return editor;
    }

    VariationGridPanel gridPanelForTest() {
        return gridPanel;
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
        zSlider.setValue(z);
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

    private void buildUi() {
        addComponent(headerPanel());
        addHeader("Parameters to sweep");
        addComponent(editor);
        addComponent(rangeRow());
        addComponent(cropRow());
        addHeader("Preview grid");
        addComponent(gridScrollPane());
        addComponent(zRow());
        addComponent(statusRow());

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

    private JScrollPane gridScrollPane() {
        JScrollPane scrollPane = new JScrollPane(gridPanel);
        scrollPane.setBorder(BorderFactory.createLineBorder(new Color(214, 220, 224)));
        scrollPane.setPreferredSize(new Dimension(780, 440));
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.getViewport().setBackground(Color.WHITE);
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

    private JPanel statusRow() {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        suggestionLabel.setForeground(new Color(90, 90, 90));
        statusLabel.setForeground(new Color(65, 70, 75));
        row.add(suggestionLabel, BorderLayout.WEST);
        row.add(statusLabel, BorderLayout.EAST);
        return row;
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

    private void installWindowCleanup() {
        Window window = getWindow();
        if (window == null) {
            return;
        }
        window.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                cancelExecutor();
            }

            @Override public void windowClosed(WindowEvent e) {
                cancelExecutor();
            }
        });
    }

    private void startOnEdt() {
        cancelExecutor();
        currentSweep = editor.currentSweep();
        ImagePlus source = context.filteredSource();
        ResourceGuard.Feasibility feasibility =
                ResourceGuard.assessFeasibility(currentSweep, source);
        if (!feasibility.ok) {
            showMessage(feasibility.message);
            statusLabel.setText("Status: refused");
            return;
        }

        completedCount = 0;
        ImagePlus croppedSource = currentSweep.cropSpec().apply(source);
        List<ParameterCombo> combos = currentSweep.combos();
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
            cell.setZ(zSlider.getValue());
            cells.add(cell);
            cellsByCombo.put(combo.toCanonicalJson(), cell);
            cellIndexesByCombo.put(combo.toCanonicalJson(), Integer.valueOf(i));
            resultsByCell.add(null);
        }
        gridPanel.setSweep(currentSweep);
        gridPanel.setCells(cells);
        statusLabel.setText("Status: 0/" + combos.size() + " complete");
        suggestionLabel.setText("Suggested: pending");

        VariationCache runCache = new VariationCache(context.configContext());
        VariationStrategy strategy;
        try {
            strategy = strategyForTest == null
                    ? VariationStrategyChooser.choose(currentSweep, context, runCache)
                    : strategyForTest;
        } catch (RuntimeException e) {
            showMessage(e.getMessage());
            statusLabel.setText("Status: refused");
            return;
        }
        executor = new VariationExecutor(currentSweep,
                strategy,
                runCache,
                (result, index) -> handleResult(result, index.intValue()),
                status -> setStatusText(status));
        executor.addPropertyChangeListener(evt -> {
            if ("state".equals(evt.getPropertyName())
                    && javax.swing.SwingWorker.StateValue.DONE == evt.getNewValue()) {
                handleExecutorDone();
            }
        });
        executor.execute();
    }

    private void handleResult(VariationResult result, int index) {
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
        cell.setResult(result);
        if (targetIndex >= 0 && targetIndex < resultsByCell.size()) {
            resultsByCell.set(targetIndex, result);
        }
        completedCount++;
        statusLabel.setText("Status: " + completedCount + "/" + cells.size() + " complete");
        if (completedCount >= cells.size()) {
            applyKneeHint();
            applyStabilityHint();
        }
    }

    private void handleExecutorDone() {
        VariationExecutor worker = executor;
        if (worker == null) {
            return;
        }
        if (worker.isCancelled()) {
            statusLabel.setText("Status: cancelled");
            for (int i = 0; i < cells.size(); i++) {
                if ("running".equals(cells.get(i).badgeText())
                        || "pending".equals(cells.get(i).badgeText())) {
                    cells.get(i).setState("cancelled");
                }
            }
            return;
        }
        try {
            worker.get();
            applyKneeHint();
            applyStabilityHint();
            statusLabel.setText("Status: done");
        } catch (Exception e) {
            statusLabel.setText("Status: error");
            showMessage(e.getMessage());
        }
    }

    private void applyKneeHint() {
        ParameterId swept = singleNumericAxis();
        if (swept == null || resultsByCell.size() != cells.size()) {
            return;
        }
        double[] xs = new double[cells.size()];
        double[] ys = new double[cells.size()];
        for (int i = 0; i < cells.size(); i++) {
            Object value = cells.get(i).combo().get(swept);
            if (!(value instanceof Number)) {
                return;
            }
            xs[i] = ((Number) value).doubleValue();
            VariationResult result = resultsByCell.get(i);
            ys[i] = result == null || result.hasError()
                    ? Double.NaN
                    : result.nObjects();
        }
        OptionalInt kneeIndex = KneeDetector.findKneeIndex(xs, ys);
        if (!kneeIndex.isPresent()) {
            suggestionLabel.setText("Suggested: no clear knee");
            return;
        }
        int index = kneeIndex.getAsInt();
        if (index < 0 || index >= cells.size()) {
            return;
        }
        VariationCellPanel cell = cells.get(index);
        cell.setBorderHint(VariationCellPanel.BorderHint.KNEE);
        suggestionLabel.setText("Suggested: knee at "
                + swept.name() + " = " + safe(String.valueOf(cell.combo().get(swept))));
    }

    private void applyStabilityHint() {
        if (currentSweep == null
                || cells.size() < 3
                || resultsByCell.size() != cells.size()) {
            return;
        }
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
        OptionalInt stableIndex = IouStability.findMostStable(combos, labels);
        if (!stableIndex.isPresent()) {
            if ("Suggested: pending".equals(suggestionLabel.getText())) {
                suggestionLabel.setText("Suggested: no stable cell");
            }
            return;
        }
        int index = stableIndex.getAsInt();
        if (index < 0 || index >= cells.size()) {
            return;
        }
        double mean = IouStability.meanNeighbourIou(combos, labels, index);
        VariationCellPanel cell = cells.get(index);
        cell.setStabilityWinner(true, mean);
        suggestionLabel.setText("Suggested: stable cell " + (index + 1));
    }

    private ParameterId singleNumericAxis() {
        if (currentSweep == null) {
            return null;
        }
        ParameterId swept = null;
        for (Map.Entry<ParameterId, ParameterValueList> entry
                : currentSweep.valueLists().entrySet()) {
            ParameterValueList values = entry.getValue();
            int size = values == null ? 0 : values.size();
            if (size <= 1) {
                continue;
            }
            if (swept != null) {
                return null;
            }
            for (int i = 0; i < size; i++) {
                if (!(values.get(i) instanceof Number)) {
                    return null;
                }
            }
            swept = entry.getKey();
        }
        return swept;
    }

    private void handleCompare(ParameterCombo combo, VariationCellPanel cell) {
        if (pendingCompareCombo == null) {
            pendingCompareCombo = combo;
            pendingCompareCell = cell;
            if (cell != null) {
                cell.setSelectedForCompare(true);
            }
            System.out.println("Compare first: " + combo);
            return;
        }
        System.out.println("Compare pair: " + pendingCompareCombo + " <> " + combo);
        if (pendingCompareCell != null) {
            pendingCompareCell.setSelectedForCompare(false);
        }
        pendingCompareCombo = null;
        pendingCompareCell = null;
        if (cell != null) {
            cell.setSelectedForCompare(false);
        }
    }

    private void acceptAndClose(ParameterCombo combo) {
        if (onAccept != null) {
            onAccept.accept(combo);
        }
        dispose();
    }

    private void cancelExecutor() {
        VariationExecutor worker = executor;
        if (worker != null && !worker.isDone()) {
            worker.cancel(true);
        }
    }

    private void setStatusText(final String text) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                statusLabel.setText("Status: " + safe(text));
            }
        });
    }

    private void runRangeSuggester() {
        final ParameterSweep draft;
        try {
            draft = editor.currentSweep();
        } catch (RuntimeException e) {
            showMessage(e.getMessage());
            return;
        }
        suggestButton.setEnabled(false);
        suggestionLabel.setText("Suggested: working...");
        statusLabel.setText("Status: suggesting ranges");
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
                            suggestionLabel.setText("Suggested: "
                                    + suggestions.size() + " rows updated");
                            statusLabel.setText("Status: idle");
                        } catch (Exception e) {
                            suggestionLabel.setText("Suggested: failed");
                            statusLabel.setText("Status: idle");
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
        } catch (RuntimeException e) {
            cellsLabel.setText("Cells: ?");
        }
    }

    private CropSpec selectedCropSpec() {
        return currentCropSpec;
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

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
