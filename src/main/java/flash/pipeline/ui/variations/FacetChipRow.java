package flash.pipeline.ui.variations;

import flash.pipeline.ui.FlashTheme;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FacetChipRow extends JPanel {

    public interface FacetSelectionListener {
        void facetSelected(ParameterKey axis, Object value);
    }

    public interface ValueLabelProvider {
        String labelFor(ParameterKey axis, Object value);

        String tooltipFor(ParameterKey axis, Object value);
    }

    private static final Color SKY_BLUE = new Color(0x56B4E9);
    private static final Color CHIP_FILL = new Color(246, 248, 250);
    private static final Color CHIP_SELECTED_FILL = new Color(236, 247, 253);
    private static final Color CHIP_BORDER = new Color(190, 198, 205);
    private static final Color TEXT = new Color(33, 33, 33);
    private static final ValueLabelProvider DEFAULT_LABEL_PROVIDER =
            new ValueLabelProvider() {
                @Override public String labelFor(ParameterKey axis, Object value) {
                    return valueText(value);
                }

                @Override public String tooltipFor(ParameterKey axis, Object value) {
                    return null;
                }
            };

    private final LinkedHashMap<ParameterKey, List<Object>> valuesByAxis =
            new LinkedHashMap<ParameterKey, List<Object>>();
    private final LinkedHashMap<ParameterKey, Object> selectedValues =
            new LinkedHashMap<ParameterKey, Object>();
    private final FacetSelectionListener listener;
    private final ValueLabelProvider labelProvider;

    public FacetChipRow(Map<? extends ParameterKey, ? extends List<?>> facetValues,
                        Map<? extends ParameterKey, ?> activeValues,
                        FacetSelectionListener listener) {
        this(facetValues, activeValues, listener, null);
    }

    public FacetChipRow(Map<? extends ParameterKey, ? extends List<?>> facetValues,
                        Map<? extends ParameterKey, ?> activeValues,
                        FacetSelectionListener listener,
                        ValueLabelProvider labelProvider) {
        super(new FlowLayout(FlowLayout.LEFT, 8, 2));
        this.listener = listener;
        this.labelProvider = labelProvider == null
                ? DEFAULT_LABEL_PROVIDER
                : labelProvider;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        copyValues(facetValues, activeValues);
        rebuild();
    }

    public Map<ParameterKey, Object> selectedValues() {
        return new LinkedHashMap<ParameterKey, Object>(selectedValues);
    }

    public Object selectedValue(ParameterKey axis) {
        return selectedValues.get(axis);
    }

    public void setSelectedValue(ParameterKey axis, Object value) {
        if (axis == null || !valuesByAxis.containsKey(axis)) {
            return;
        }
        List<Object> values = valuesByAxis.get(axis);
        if (!values.contains(value)) {
            return;
        }
        selectedValues.put(axis, value);
        rebuild();
    }

    private void copyValues(Map<? extends ParameterKey, ? extends List<?>> facetValues,
                            Map<? extends ParameterKey, ?> activeValues) {
        if (facetValues == null) {
            return;
        }
        for (Map.Entry<? extends ParameterKey, ? extends List<?>> entry
                : facetValues.entrySet()) {
            ParameterKey axis = entry.getKey();
            List<?> sourceValues = entry.getValue();
            if (axis == null || sourceValues == null || sourceValues.isEmpty()) {
                continue;
            }
            List<Object> copy = new ArrayList<Object>();
            for (int i = 0; i < sourceValues.size(); i++) {
                copy.add(sourceValues.get(i));
            }
            valuesByAxis.put(axis, copy);
            Object selected = activeValues == null ? null : activeValues.get(axis);
            selectedValues.put(axis, copy.contains(selected) ? selected : copy.get(0));
        }
    }

    private void rebuild() {
        removeAll();
        for (Map.Entry<ParameterKey, List<Object>> entry : valuesByAxis.entrySet()) {
            add(groupFor(entry.getKey(), entry.getValue()));
        }
        revalidate();
        repaint();
    }

    private JPanel groupFor(final ParameterKey axis, List<Object> values) {
        JPanel group = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        group.setOpaque(false);

        JLabel label = new JLabel(ParameterLabels.labelFor(axis));
        label.setFont(FlashTheme.bodyMedium().deriveFont(Font.BOLD, 11f));
        label.setForeground(new Color(78, 93, 101));
        group.add(label);

        for (int i = 0; i < values.size(); i++) {
            final Object value = values.get(i);
            ChipButton chip = new ChipButton(labelFor(axis, value),
                    value instanceof Number);
            chip.setSelected(value.equals(selectedValues.get(axis)));
            String tooltip = tooltipFor(axis, value);
            chip.setToolTipText(tooltip == null || tooltip.trim().isEmpty()
                    ? null
                    : tooltip);
            chip.addActionListener(e -> select(axis, value));
            group.add(chip);
        }
        return group;
    }

    private void select(ParameterKey axis, Object value) {
        selectedValues.put(axis, value);
        rebuild();
        if (listener != null) {
            listener.facetSelected(axis, value);
        }
    }

    private static String valueText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String labelFor(ParameterKey axis, Object value) {
        String text = labelProvider.labelFor(axis, value);
        return text == null ? valueText(value) : text;
    }

    private String tooltipFor(ParameterKey axis, Object value) {
        return labelProvider.tooltipFor(axis, value);
    }

    private static final class ChipButton extends JButton {
        ChipButton(String text, boolean numeric) {
            super(text);
            setFont(numeric ? FlashTheme.mono(11f)
                    : FlashTheme.bodyMedium().deriveFont(11f));
            setForeground(TEXT);
            setFocusPainted(false);
            setBorder(BorderFactory.createEmptyBorder(3, 18, 3, 9));
            setContentAreaFilled(false);
            setOpaque(false);
            setPreferredSize(preferredFor(text));
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                int width = getWidth();
                int height = getHeight();
                int arc = Math.max(10, height - 2);
                g2.setColor(isSelected() ? CHIP_SELECTED_FILL : CHIP_FILL);
                g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc);
                g2.setColor(isSelected() ? SKY_BLUE : CHIP_BORDER);
                g2.setStroke(new BasicStroke(isSelected() ? 1.5f : 1f));
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc);
                if (isSelected()) {
                    int diameter = 6;
                    g2.setColor(SKY_BLUE);
                    g2.fillOval(8, height / 2 - diameter / 2, diameter, diameter);
                }
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }

        private static Dimension preferredFor(String text) {
            int length = text == null ? 0 : text.length();
            return new Dimension(Math.max(44, 28 + length * 7), 24);
        }
    }
}
