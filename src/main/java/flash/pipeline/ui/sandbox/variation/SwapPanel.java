package flash.pipeline.ui.sandbox.variation;

import flash.pipeline.image.FilterMacroParser.OpType;
import flash.pipeline.image.dag.DagNode;
import flash.pipeline.image.variation.FilterCompatibility;
import flash.pipeline.image.variation.OpTypeParamRegistry;
import flash.pipeline.image.variation.VariantAxis;
import flash.pipeline.image.variation.VariantAxis.AlternativeValue;
import flash.pipeline.image.variation.VariantAxis.Kind;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SwapPanel extends JPanel {

    public static final int MAX_TICKED = 8;

    public interface StateListener {
        void onStateChanged();
    }

    private DagNode node;
    private final JLabel nodeLabel = new JLabel(" ");
    private final JLabel emptyHint = new JLabel(" ");
    private final JPanel checkboxColumn = new JPanel();
    private final Map<OpType, JCheckBox> checkboxes = new LinkedHashMap<OpType, JCheckBox>();
    private StateListener listener;

    public SwapPanel() {
        super(new BorderLayout(0, 4));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        nodeLabel.setFont(nodeLabel.getFont().deriveFont(java.awt.Font.BOLD));
        emptyHint.setForeground(new java.awt.Color(120, 120, 120));

        JPanel header = new JPanel(new BorderLayout(0, 2));
        header.add(nodeLabel, BorderLayout.NORTH);
        header.add(emptyHint, BorderLayout.SOUTH);

        checkboxColumn.setLayout(new BoxLayout(checkboxColumn, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(checkboxColumn,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEtchedBorder());
        scroll.setPreferredSize(new Dimension(280, 180));

        add(header, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
    }

    public void setStateListener(StateListener listener) {
        this.listener = listener;
    }

    public void setNode(DagNode node) {
        this.node = node;
        rebuildCheckboxes();
        fireChange();
    }

    public DagNode getNode() {
        return node;
    }

    public void setTicked(OpType... types) {
        Set<OpType> wanted = new HashSet<OpType>();
        if (types != null) {
            for (int i = 0; i < types.length; i++) {
                if (types[i] != null) wanted.add(types[i]);
            }
        }
        for (Map.Entry<OpType, JCheckBox> entry : checkboxes.entrySet()) {
            entry.getValue().setSelected(wanted.contains(entry.getKey()));
        }
        fireChange();
    }

    public int alternativeCount() {
        if (node == null) return 0;
        int count = 0;
        for (Map.Entry<OpType, JCheckBox> entry : checkboxes.entrySet()) {
            if (entry.getValue().isSelected()) count++;
        }
        return Math.min(count, MAX_TICKED);
    }

    public boolean isReady() {
        return node != null && alternativeCount() >= 1;
    }

    public VariantAxis buildAxis() {
        if (node == null) throw new IllegalStateException("no node selected");
        List<AlternativeValue> alts = new ArrayList<AlternativeValue>();
        for (Map.Entry<OpType, JCheckBox> entry : checkboxes.entrySet()) {
            OpType alt = entry.getKey();
            if (!entry.getValue().isSelected()) continue;
            String args = OpTypeParamRegistry.argsForDefaults(alt);
            alts.add(new AlternativeValue(FilterCompatibility.displayName(alt), alt, args));
            if (alts.size() >= MAX_TICKED) break;
        }
        return new VariantAxis(node.id, Kind.FILTER_SWAP, alts);
    }

    private void rebuildCheckboxes() {
        checkboxColumn.removeAll();
        checkboxes.clear();
        if (node == null) {
            nodeLabel.setText(" ");
            emptyHint.setText(" ");
            checkboxColumn.revalidate();
            checkboxColumn.repaint();
            return;
        }

        nodeLabel.setText(node.type.name() + "  [id=" + node.id + "]");
        List<OpType> compatible = FilterCompatibility.alternativesExcludingBaseline(node.type);
        emptyHint.setText(compatible.isEmpty()
                ? "No alternative filters known for this operation."
                : "Tick 1-8 compatible alternatives.");

        for (int i = 0; i < compatible.size(); i++) {
            final OpType type = compatible.get(i);
            final JCheckBox box = new JCheckBox(FilterCompatibility.displayName(type));
            box.setSelected(false);
            box.addActionListener(e -> {
                enforceCap(box);
                fireChange();
            });
            checkboxColumn.add(box);
            checkboxes.put(type, box);
        }
        checkboxColumn.revalidate();
        checkboxColumn.repaint();
    }

    private void enforceCap(JCheckBox box) {
        if (!box.isSelected()) return;
        int ticked = 0;
        for (Map.Entry<OpType, JCheckBox> entry : checkboxes.entrySet()) {
            if (entry.getValue().isSelected()) ticked++;
        }
        if (ticked > MAX_TICKED) {
            box.setSelected(false);
            java.awt.Toolkit.getDefaultToolkit().beep();
        }
    }

    private void fireChange() {
        if (listener != null) listener.onStateChanged();
    }
}
