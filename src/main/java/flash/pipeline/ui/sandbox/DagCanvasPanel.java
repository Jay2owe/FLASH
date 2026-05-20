package flash.pipeline.ui.sandbox;

import flash.pipeline.image.dag.DagToIjmEmitter;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public final class DagCanvasPanel extends JPanel {

    public interface CatalogSupplier {
        FilterCatalog.Entry getSelectedCatalogEntry();
    }

    public interface NodeCreator {
        boolean addNode(SandboxModel.Line line, FilterCatalog.Entry entry);
    }

    private final SandboxModel model;
    private final CatalogSupplier catalogSupplier;
    private final NodeCreator nodeCreator;
    private final Runnable selectionCallback;
    private final Runnable changeCallback;

    private SandboxModel.Line dragLine;
    private SandboxModel.Node dragNode;

    public DagCanvasPanel(SandboxModel model, CatalogSupplier catalogSupplier,
                          NodeCreator nodeCreator,
                          Runnable selectionCallback, Runnable changeCallback) {
        super(new GridBagLayout());
        this.model = model;
        this.catalogSupplier = catalogSupplier;
        this.nodeCreator = nodeCreator;
        this.selectionCallback = selectionCallback;
        this.changeCallback = changeCallback;
        setBorder(BorderFactory.createTitledBorder("Your filter"));
        rebuild();
    }

    public void rebuild() {
        removeAll();
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.anchor = GridBagConstraints.NORTH;
        for (int i = 0; i < model.lines.size(); i++) {
            gbc.gridx = i;
            gbc.weightx = 1.0;
            gbc.fill = GridBagConstraints.BOTH;
            add(buildLinePanel(model.lines.get(i)), gbc);
        }

        gbc.gridx = model.lines.size();
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.VERTICAL;
        JButton addLine = new JButton("+ Add parallel branch");
        addLine.setEnabled(model.lines.size() < SandboxModel.MAX_LINES);
        addLine.addActionListener(e -> {
            model.addLine();
            changedAndSelected();
        });
        add(addLine, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = model.lines.size() + 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        add(buildCombinerRow(), gbc);

        revalidate();
        repaint();
    }

    private JPanel buildLinePanel(final SandboxModel.Line line) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(model.selected == line ? new Color(70, 120, 200) : new Color(180, 180, 180), 2),
                BorderFactory.createEmptyBorder(6, 6, 6, 6)));
        panel.setBackground(new Color(250, 250, 250));
        panel.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                model.selected = line;
                selected();
            }
        });

        GridBagConstraints gbc = baseGbc();
        gbc.gridy = 0;
        int branchIndex = model.lines.indexOf(line) + 1;
        JLabel title = new JLabel("Branch " + branchIndex);
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD));
        panel.add(title, gbc);

        JButton deleteLine = new JButton("x");
        deleteLine.setEnabled(model.lines.size() > 1);
        deleteLine.addActionListener(e -> {
            model.removeLine(line);
            changedAndSelected();
        });
        gbc.gridx = 1;
        panel.add(deleteLine, gbc);

        gbc.gridx = 0;
        gbc.gridwidth = 2;
        for (int i = 0; i < line.nodes.size(); i++) {
            gbc.gridy = i + 1;
            panel.add(buildNodeCard(line, line.nodes.get(i)), gbc);
        }

        gbc.gridy = line.nodes.size() + 1;
        JButton addNode = new JButton("+ Add step");
        addNode.addActionListener(e -> {
            FilterCatalog.Entry entry = catalogSupplier == null ? null : catalogSupplier.getSelectedCatalogEntry();
            boolean added;
            if (nodeCreator != null) {
                added = nodeCreator.addNode(line, entry);
            } else {
                model.addNode(line, entry);
                added = true;
            }
            if (added) changedAndSelected();
        });
        panel.add(addNode, gbc);
        return panel;
    }

    private JPanel buildNodeCard(final SandboxModel.Line line, final SandboxModel.Node node) {
        JPanel card = new JPanel(new GridBagLayout());
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(model.selected == node ? new Color(70, 120, 200) : new Color(205, 205, 205), 2),
                BorderFactory.createEmptyBorder(6, 6, 6, 6)));
        card.setBackground(Color.WHITE);

        String command = node.commandName.length() > 0 ? node.commandName : DagToIjmEmitter.commandFor(node.type);
        String displayName = command == null ? node.type.name() : command;
        String glyph = node.isLegacy() ? " 🐢" : "";
        JLabel label = new JLabel("<html><b>" + displayName + glyph
                + "</b><br><span style='font-size:9px;'>" + node.args + "</span></html>");
        String tooltip = node.isLegacy()
                ? "Runs through Fiji's slower single-image path"
                : "Runs through the fast batched path";
        label.setToolTipText(tooltip);
        card.setToolTipText(tooltip);
        GridBagConstraints gbc = baseGbc();
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        card.add(label, gbc);

        JButton delete = new JButton("x");
        delete.addActionListener(e -> {
            model.removeNode(line, node);
            changedAndSelected();
        });
        gbc.gridx = 1;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        card.add(delete, gbc);

        MouseAdapter adapter = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                dragLine = line;
                dragNode = node;
                model.selected = node;
                selected();
            }

            @Override public void mouseReleased(MouseEvent e) {
                if (dragLine == null || dragNode == null) return;
                Point p = javax.swing.SwingUtilities.convertPoint((Component) e.getSource(), e.getPoint(),
                        DagCanvasPanel.this);
                int newIndex = yToIndex(dragLine, p.y);
                int oldIndex = dragLine.nodes.indexOf(dragNode);
                if (oldIndex >= 0 && newIndex >= 0 && newIndex != oldIndex) {
                    dragLine.nodes.remove(oldIndex);
                    if (newIndex > dragLine.nodes.size()) newIndex = dragLine.nodes.size();
                    dragLine.nodes.add(newIndex, dragNode);
                    changedAndSelected();
                }
                dragLine = null;
                dragNode = null;
            }
        };
        card.addMouseListener(adapter);
        label.addMouseListener(adapter);
        return card;
    }

    private int yToIndex(SandboxModel.Line line, int y) {
        int lineIndex = model.lines.indexOf(line);
        if (lineIndex < 0) return -1;
        Component linePanel = getComponent(lineIndex);
        Point origin = linePanel.getLocation();
        int relative = y - origin.y - 34;
        int index = relative / 72;
        if (index < 0) index = 0;
        if (index >= line.nodes.size()) index = line.nodes.size() - 1;
        return index;
    }

    private JPanel buildCombinerRow() {
        JPanel row = new JPanel(new GridBagLayout());
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(180, 180, 180)),
                BorderFactory.createEmptyBorder(8, 0, 0, 0)));
        GridBagConstraints gbc = baseGbc();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        for (int i = 0; i < model.combiners.size(); i++) {
            final SandboxModel.CombinerNode combiner = model.combiners.get(i);
            gbc.gridx = i;
            row.add(buildCombinerCard(combiner), gbc);
        }
        gbc.gridx = model.combiners.size();
        gbc.weightx = 0.0;
        JButton addCombiner = new JButton("+ Merge branches");
        addCombiner.setEnabled(model.lines.size() >= 2);
        addCombiner.addActionListener(e -> {
            model.addCombiner();
            changedAndSelected();
        });
        row.add(addCombiner, gbc);
        if (model.lines.size() < 2) {
            gbc.gridx++;
            gbc.weightx = 1.0;
            JLabel hint = new JLabel("Add a 2nd branch to combine two channels here.");
            hint.setForeground(new Color(110, 110, 110));
            hint.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
            row.add(hint, gbc);
        }
        return row;
    }

    private JPanel buildCombinerCard(final SandboxModel.CombinerNode combiner) {
        JPanel card = new JPanel(new GridBagLayout());
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(model.selected == combiner ? new Color(70, 120, 200) : new Color(205, 205, 205), 2),
                BorderFactory.createEmptyBorder(6, 6, 6, 6)));
        card.setBackground(Color.WHITE);
        GridBagConstraints gbc = baseGbc();
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        card.add(new JLabel("combiner: " + combiner.inputs + " " + combiner.op), gbc);
        JButton delete = new JButton("x");
        delete.addActionListener(e -> {
            model.removeCombiner(combiner);
            changedAndSelected();
        });
        gbc.gridx = 1;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        card.add(delete, gbc);
        card.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                model.selected = combiner;
                selected();
            }
        });
        return card;
    }

    private GridBagConstraints baseGbc() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.insets = new Insets(3, 3, 3, 3);
        gbc.anchor = GridBagConstraints.WEST;
        return gbc;
    }

    private void selected() {
        if (selectionCallback != null) selectionCallback.run();
        rebuild();
    }

    private void changedAndSelected() {
        if (changeCallback != null) changeCallback.run();
        selected();
    }
}
