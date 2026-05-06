package flash.pipeline.ui.sandbox;

import flash.pipeline.image.dag.CombinerOp;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;

public final class CombinerEditorPanel extends JPanel {

    private final SandboxModel model;
    private final Runnable changeCallback;
    private SandboxModel.CombinerNode combiner;
    private boolean rebuilding;

    public CombinerEditorPanel(SandboxModel model, Runnable changeCallback) {
        super(new GridBagLayout());
        this.model = model;
        this.changeCallback = changeCallback;
        setBorder(BorderFactory.createTitledBorder("Merge branches"));
        setCombiner(null);
    }

    public void setCombiner(SandboxModel.CombinerNode combiner) {
        this.combiner = combiner;
        rebuild();
    }

    private void rebuild() {
        rebuilding = true;
        removeAll();
        if (combiner == null) {
            addLabel("Click a merge point in 'Your filter' to choose how branches combine.", 0, 0, 2);
            finish();
            return;
        }

        addLabel("Operation", 0, 0, 1);
        final JComboBox<CombinerOp> op = new JComboBox<CombinerOp>(CombinerOp.values());
        op.setSelectedItem(combiner.op);
        op.addActionListener(e -> {
            if (rebuilding || CombinerEditorPanel.this.combiner == null) return;
            CombinerEditorPanel.this.combiner.op = (CombinerOp) op.getSelectedItem();
            notifyChanged();
        });
        addField(op, 1, 0);

        addLabel("Inputs", 0, 1, 2);
        int row = 2;
        for (int i = 0; i < model.lines.size(); i++) {
            final SandboxModel.Line line = model.lines.get(i);
            final JCheckBox box = new JCheckBox(line.id, combiner.inputs.contains(line.id));
            box.addActionListener(e -> {
                if (rebuilding || CombinerEditorPanel.this.combiner == null) return;
                List<String> inputs = new ArrayList<String>(CombinerEditorPanel.this.combiner.inputs);
                if (box.isSelected() && !inputs.contains(line.id)) {
                    inputs.add(line.id);
                } else if (!box.isSelected()) {
                    inputs.remove(line.id);
                }
                if (inputs.size() >= 2) {
                    CombinerEditorPanel.this.combiner.inputs = inputs;
                    notifyChanged();
                } else {
                    box.setSelected(true);
                }
            });
            addField(box, 0, row++);
        }
        finish();
    }

    private void notifyChanged() {
        if (changeCallback != null) changeCallback.run();
    }

    private void finish() {
        rebuilding = false;
        revalidate();
        repaint();
    }

    private void addLabel(String text, int x, int y, int width) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = x;
        gbc.gridy = y;
        gbc.gridwidth = width;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(3, 4, 3, 4);
        add(new JLabel(text), gbc);
    }

    private void addField(java.awt.Component field, int x, int y) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = x;
        gbc.gridy = y;
        gbc.gridwidth = x == 0 ? 2 : 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(3, 4, 3, 4);
        add(field, gbc);
    }
}
