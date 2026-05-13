package flash.pipeline.ui.sandbox.variation;

import flash.pipeline.image.dag.DagIR;
import flash.pipeline.image.dag.DagLine;
import flash.pipeline.image.dag.DagNode;
import flash.pipeline.image.variation.MemoryEstimate;
import flash.pipeline.image.variation.MemoryEstimator;
import flash.pipeline.image.variation.OpTypeParamRegistry;
import flash.pipeline.image.variation.ProgressCallback;
import flash.pipeline.image.variation.VariantAxis;
import flash.pipeline.image.variation.VariantExecutor;
import flash.pipeline.image.variation.VariantPlan;
import flash.pipeline.image.variation.VariantResult;
import flash.pipeline.image.variation.VariantSampler;
import flash.pipeline.image.variation.VariationRunResult;
import flash.pipeline.image.variation.VariationSourcePreparer;
import flash.pipeline.image.variation.FilterCompatibility;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Modal dialog that asks which active filter node should be varied, then runs
 * the selected variants on FLASH's already channel-isolated preview source.
 */
public final class VariationChooserDialog extends JDialog {

    public static final int MAX_VARIANTS_HARD_CAP = 16;
    public static final int DEFAULT_MAX_VARIANTS = 9;

    private final DagIR baseline;
    private final ImagePlus source;
    private final Consumer<VariationRunResult> resultCallback;

    private final ButtonGroup modeGroup = new ButtonGroup();
    private final JRadioButton sweepRadio = new JRadioButton("Sweep parameter", true);
    private final JRadioButton swapRadio = new JRadioButton("Swap filter", false);

    private final JComboBox<NodeChoice> nodeCombo = new JComboBox<NodeChoice>();
    private final CardLayout panelStack = new CardLayout();
    private final JPanel modePanel = new JPanel(panelStack);
    private final SweepPanel sweepPanel = new SweepPanel();
    private final SwapPanel swapPanel = new SwapPanel();

    private final JCheckBox advancedToggle = new JCheckBox("Advanced...");
    private final JPanel advancedBody = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
    private final JCheckBox cartesianCheck = new JCheckBox("Cartesian product");
    private final SpinnerNumberModel maxVariantsModel =
            new SpinnerNumberModel(DEFAULT_MAX_VARIANTS, 1, MAX_VARIANTS_HARD_CAP, 1);
    private final JSpinner maxVariantsSpinner = new JSpinner(maxVariantsModel);

    private final JLabel memoryLabel = new JLabel(" ");
    private final JButton generateButton = new JButton("Generate");
    private final JButton cancelButton = new JButton("Cancel");

    private boolean updatingNodeCombo;

    public VariationChooserDialog(Window owner,
                                  DagIR baseline,
                                  ImagePlus source,
                                  Consumer<VariationRunResult> resultCallback) {
        super(owner, "Create variations", ModalityType.APPLICATION_MODAL);
        if (baseline == null) throw new IllegalArgumentException("baseline must not be null");
        if (resultCallback == null) {
            throw new IllegalArgumentException("resultCallback must not be null");
        }
        this.baseline = baseline;
        this.source = source;
        this.resultCallback = resultCallback;

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        modeGroup.add(sweepRadio);
        modeGroup.add(swapRadio);
        modePanel.add(sweepPanel, "sweep");
        modePanel.add(swapPanel, "swap");

        buildLayout();
        wireListeners();
        populateNodeCombo();
        refreshGenerateState();
        refreshMemoryEstimate();
    }

    private void buildLayout() {
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        JPanel modeBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        modeBar.add(new JLabel("Mode:"));
        modeBar.add(sweepRadio);
        modeBar.add(swapRadio);

        JPanel nodeBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        nodeBar.add(new JLabel("Step:"));
        nodeBar.add(nodeCombo);

        JPanel header = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, 4, 0);
        gbc.gridy = 0;
        header.add(modeBar, gbc);
        gbc.gridy = 1;
        header.add(nodeBar, gbc);

        advancedBody.add(cartesianCheck);
        advancedBody.add(new JLabel("Max variants:"));
        advancedBody.add(maxVariantsSpinner);
        advancedBody.setVisible(false);

        JPanel advancedPanel = new JPanel(new BorderLayout(0, 2));
        advancedPanel.add(advancedToggle, BorderLayout.NORTH);
        advancedPanel.add(advancedBody, BorderLayout.CENTER);

        memoryLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 1, 0, new Color(220, 220, 220)),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)));

        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.add(modePanel, BorderLayout.CENTER);
        center.add(advancedPanel, BorderLayout.SOUTH);

        JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttonBar.add(cancelButton);
        buttonBar.add(generateButton);

        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.add(memoryLabel, BorderLayout.NORTH);
        south.add(buttonBar, BorderLayout.SOUTH);

        content.add(header, BorderLayout.NORTH);
        content.add(center, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);

        generateButton.setToolTipText(
                "<html>Variants run on the current timepoint only.<br>"
                        + "Promote a winner, then FLASH applies that filter normally.</html>");
        getRootPane().setDefaultButton(generateButton);
        pack();
        setLocationRelativeTo(getOwner());
    }

    private void wireListeners() {
        sweepRadio.addActionListener(e -> switchMode(true));
        swapRadio.addActionListener(e -> switchMode(false));
        nodeCombo.addActionListener(e -> {
            if (updatingNodeCombo) return;
            DagNode selected = selectedNode();
            sweepPanel.setNode(selected);
            swapPanel.setNode(selected);
            refreshGenerateState();
            refreshMemoryEstimate();
        });

        sweepPanel.setStateListener(new SweepPanel.StateListener() {
            @Override public void onStateChanged() {
                refreshGenerateState();
                refreshMemoryEstimate();
            }
        });
        swapPanel.setStateListener(new SwapPanel.StateListener() {
            @Override public void onStateChanged() {
                refreshGenerateState();
                refreshMemoryEstimate();
            }
        });

        advancedToggle.addActionListener(e -> {
            advancedBody.setVisible(advancedToggle.isSelected());
            if (!advancedToggle.isSelected()) cartesianCheck.setSelected(false);
            revalidate();
            repaint();
            refreshMemoryEstimate();
        });
        cartesianCheck.addActionListener(e -> refreshMemoryEstimate());
        maxVariantsSpinner.addChangeListener(e -> refreshMemoryEstimate());

        cancelButton.addActionListener(e -> dispose());
        generateButton.addActionListener(e -> onGenerate());
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                dispose();
            }
        });
    }

    private void switchMode(boolean sweep) {
        panelStack.show(modePanel, sweep ? "sweep" : "swap");
        populateNodeCombo();
        refreshGenerateState();
        refreshMemoryEstimate();
    }

    private void populateNodeCombo() {
        updatingNodeCombo = true;
        try {
            DefaultComboBoxModel<NodeChoice> model = new DefaultComboBoxModel<NodeChoice>();
            List<NodeChoice> choices = nodeChoicesFor(baseline, sweepRadio.isSelected());
            for (int i = 0; i < choices.size(); i++) {
                model.addElement(choices.get(i));
            }
            nodeCombo.setModel(model);
            if (model.getSize() > 0) {
                nodeCombo.setSelectedIndex(0);
                DagNode selected = model.getElementAt(0).node;
                sweepPanel.setNode(selected);
                swapPanel.setNode(selected);
            } else {
                sweepPanel.setNode(null);
                swapPanel.setNode(null);
            }
        } finally {
            updatingNodeCombo = false;
        }
    }

    private DagNode selectedNode() {
        Object item = nodeCombo.getSelectedItem();
        return item instanceof NodeChoice ? ((NodeChoice) item).node : null;
    }

    private void refreshGenerateState() {
        boolean ready = sweepRadio.isSelected() ? sweepPanel.isReady() : swapPanel.isReady();
        generateButton.setEnabled(source != null && ready);
    }

    private void refreshMemoryEstimate() {
        int variants = estimatedVariantCount();
        if (source == null) {
            memoryLabel.setText("No preview source is available.");
            memoryLabel.setForeground(new Color(180, 0, 0));
            return;
        }
        try {
            MemoryEstimate estimate = MemoryEstimator.estimate(source, variants);
            memoryLabel.setText(estimate.humanReadable);
            memoryLabel.setForeground(estimate.exceedsBudget ? new Color(180, 0, 0) : Color.BLACK);
        } catch (RuntimeException re) {
            memoryLabel.setText("Memory estimate unavailable: " + re.getMessage());
            memoryLabel.setForeground(new Color(180, 0, 0));
        }
    }

    private int estimatedVariantCount() {
        int alts = sweepRadio.isSelected()
                ? sweepPanel.alternativeCount()
                : swapPanel.alternativeCount();
        if (advancedToggle.isSelected() && cartesianCheck.isSelected()) {
            return Math.max(1, alts);
        }
        return Math.max(1, alts + 1);
    }

    private void onGenerate() {
        if (source == null) {
            IJ.error("Variations", "No preview source is available.");
            return;
        }
        boolean sweep = sweepRadio.isSelected();
        VariantAxis axis = sweep ? sweepPanel.buildAxis() : swapPanel.buildAxis();
        if (axis.alternatives.isEmpty()) return;

        boolean advanced = advancedToggle.isSelected();
        boolean cartesian = advanced && cartesianCheck.isSelected();
        int cap = advanced
                ? ((Number) maxVariantsModel.getValue()).intValue()
                : DEFAULT_MAX_VARIANTS;

        final List<VariantPlan> plans;
        try {
            plans = choosePlans(baseline, Collections.singletonList(axis),
                    advanced, cartesian, cap);
        } catch (RuntimeException ex) {
            IJ.error("Variations", ex.getMessage());
            return;
        }
        if (plans.isEmpty()) return;

        generateButton.setEnabled(false);
        cancelButton.setEnabled(false);
        final ProgressDialog progress = new ProgressDialog(this, plans.size());
        progress.setStatusText("Preparing variant source...");

        SwingWorker<VariationRunResult, Void> worker =
                new SwingWorker<VariationRunResult, Void>() {
            @Override
            protected VariationRunResult doInBackground() {
                VariationSourcePreparer.PreparedSource prepared =
                        prepareSourceForExecution(plans.size());
                if (prepared == null) return null;
                try {
                    List<VariantResult> results = VariantExecutor.runAll(
                            prepared.executionSource, plans, new ProgressCallback() {
                                @Override public void onStart(int total) {
                                    progress.setProgress(0);
                                }

                                @Override public void onVariantComplete(int completed,
                                                                        int total,
                                                                        VariantResult result) {
                                    progress.setProgress(completed);
                                }

                                @Override public void onAllDone(List<VariantResult> results) {
                                    progress.setProgress(plans.size());
                                }
                            });
                    return new VariationRunResult(prepared.displaySource, results);
                } catch (RuntimeException ex) {
                    prepared.displaySource.flush();
                    throw ex;
                } finally {
                    prepared.executionSource.flush();
                }
            }

            @Override
            protected void done() {
                progress.dispose();
                VariationRunResult result;
                try {
                    result = get();
                } catch (Exception ex) {
                    IJ.handleException(ex);
                    setReadyAfterCancelledRun();
                    return;
                }
                if (result == null) {
                    setReadyAfterCancelledRun();
                    return;
                }
                VariationChooserDialog.this.dispose();
                resultCallback.accept(result);
            }
        };
        worker.execute();
        progress.setVisible(true);
    }

    private VariationSourcePreparer.PreparedSource prepareSourceForExecution(int variantCount) {
        MemoryEstimate estimate = MemoryEstimator.estimate(source, variantCount);
        Roi roi = null;
        if (estimate.exceedsBudget) {
            roi = RoiPromptDialog.prompt(source, estimate.humanReadable);
            if (roi == null) return null;
        }
        return VariationSourcePreparer.prepare(source, roi);
    }

    private void setReadyAfterCancelledRun() {
        cancelButton.setEnabled(true);
        refreshGenerateState();
    }

    public static List<VariantPlan> choosePlans(DagIR baseline,
                                                List<VariantAxis> axes,
                                                boolean advancedOpen,
                                                boolean cartesianRequested,
                                                int maxVariants) {
        if (cartesianRequested && !advancedOpen) {
            throw new IllegalStateException(
                    "Cartesian product is only available with the Advanced panel open.");
        }
        int cap = Math.min(maxVariants, MAX_VARIANTS_HARD_CAP);
        if (cap < 1) cap = 1;
        return cartesianRequested
                ? VariantSampler.cartesian(baseline, axes, cap)
                : VariantSampler.ofat(baseline, axes, cap);
    }

    static List<NodeChoice> nodeChoicesFor(DagIR baseline, boolean sweepMode) {
        if (baseline == null || baseline.lines == null) {
            return Collections.emptyList();
        }
        List<NodeChoice> out = new ArrayList<NodeChoice>();
        for (int i = 0; i < baseline.lines.size(); i++) {
            DagLine line = baseline.lines.get(i);
            if (line == null || line.ops == null) continue;
            String lineId = line.id;
            for (int j = 0; j < line.ops.size(); j++) {
                DagNode node = line.ops.get(j);
                if (node == null || node.disabled) continue;
                if (sweepMode) {
                    if (OpTypeParamRegistry.paramsOf(node.type).isEmpty()) continue;
                } else if (FilterCompatibility.alternativesExcludingBaseline(node.type).isEmpty()) {
                    continue;
                }
                out.add(new NodeChoice(node, lineId));
            }
        }
        return Collections.unmodifiableList(out);
    }

    SweepPanel sweepPanelForTest() {
        return sweepPanel;
    }

    SwapPanel swapPanelForTest() {
        return swapPanel;
    }

    JCheckBox advancedToggleForTest() {
        return advancedToggle;
    }

    JCheckBox cartesianCheckForTest() {
        return cartesianCheck;
    }

    static final class NodeChoice {
        final DagNode node;
        final String lineId;

        NodeChoice(DagNode node, String lineId) {
            this.node = node;
            this.lineId = lineId == null ? "" : lineId;
        }

        @Override
        public String toString() {
            return lineId + ": " + node.type.name() + " [id=" + node.id + "]";
        }
    }
}
