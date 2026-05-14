package flash.pipeline.ui.variations;

import flash.pipeline.ui.FlashTheme;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class ValueChipPanel extends JPanel {

    public interface ValueParser {
        Object parse(String text);
        String format(Object value);
    }

    private static final Color CHIP_BG = new Color(0xF4, 0xF7, 0xFA);
    private static final Color CHIP_FG = new Color(0x2F, 0x3A, 0x43);
    private static final Color CHIP_BORDER = new Color(0xA8, 0xB3, 0xBD);
    private static final Color CHIP_DISABLED_BG = new Color(0xE5, 0xE8, 0xEB);
    private static final Color CHIP_DISABLED_FG = new Color(0x92, 0x98, 0x9E);
    private static final Color ADD_BG = new Color(0xE8, 0xF4, 0xFB);
    private static final Color ADD_BORDER = new Color(0x56, 0xB4, 0xE9);

    private final ValueParser parser;
    private final List<Object> values = new ArrayList<Object>();
    private final List<ChangeListener> listeners = new ArrayList<ChangeListener>();

    public ValueChipPanel(ParameterValueList initialValues, ValueParser parser) {
        super(new FlowLayout(FlowLayout.LEFT, 4, 2));
        if (parser == null) {
            throw new IllegalArgumentException("parser must not be null");
        }
        this.parser = parser;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        setValues(initialValues == null
                ? Collections.singletonList(Integer.valueOf(0))
                : initialValues.values());
    }

    public static ValueParser doubleParser() {
        return new ValueParser() {
            @Override public Object parse(String text) {
                String trimmed = text == null ? "" : text.trim();
                double parsed = Double.parseDouble(trimmed);
                if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
                    throw new IllegalArgumentException("Value must be finite.");
                }
                return Double.valueOf(parsed);
            }

            @Override public String format(Object value) {
                return String.valueOf(value);
            }
        };
    }

    public static ValueParser intParser() {
        return new ValueParser() {
            @Override public Object parse(String text) {
                String trimmed = text == null ? "" : text.trim();
                return Integer.valueOf(Integer.parseInt(trimmed));
            }

            @Override public String format(Object value) {
                return String.valueOf(value);
            }
        };
    }

    public static ValueParser maxSizeParser() {
        return new ValueParser() {
            @Override public Object parse(String text) {
                String trimmed = text == null ? "" : text.trim();
                String normalized = trimmed.toLowerCase(Locale.ROOT);
                if ("infinity".equals(normalized)
                        || "inf".equals(normalized)
                        || "\u221e".equals(trimmed)
                        || "max_value".equals(normalized)) {
                    return Integer.valueOf(Integer.MAX_VALUE);
                }
                return Integer.valueOf(Integer.parseInt(trimmed));
            }

            @Override public String format(Object value) {
                if (value instanceof Number
                        && ((Number) value).intValue() == Integer.MAX_VALUE) {
                    return "\u221e";
                }
                return String.valueOf(value);
            }
        };
    }

    public static ValueParser stringParser() {
        return new ValueParser() {
            @Override public Object parse(String text) {
                String trimmed = text == null ? "" : text.trim();
                if (trimmed.isEmpty()) {
                    throw new IllegalArgumentException("Value must not be blank.");
                }
                return trimmed;
            }

            @Override public String format(Object value) {
                return value == null ? "" : String.valueOf(value);
            }
        };
    }

    public ParameterValueList currentValueList() {
        return new ParameterValueList(values);
    }

    public List<Object> valuesForTest() {
        return new ArrayList<Object>(values);
    }

    public void setValues(List<?> newValues) {
        values.clear();
        if (newValues != null) {
            for (int i = 0; i < newValues.size(); i++) {
                values.add(ParameterValueList.normalizeValue(newValues.get(i)));
            }
        }
        if (values.isEmpty()) {
            values.add(Integer.valueOf(0));
        }
        rebuild();
        fireChanged();
    }

    public void addChangeListener(ChangeListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    void addValueForTest(String text) {
        addParsedValue(text);
    }

    void editValueForTest(int index, String text) {
        replaceParsedValue(index, text);
    }

    void removeValueForTest(int index) {
        removeValue(index);
    }

    private void promptAndAdd() {
        String text = JOptionPane.showInputDialog(this, "Value:");
        if (text != null) {
            addParsedValue(text);
        }
    }

    private void promptAndEdit(final int index) {
        String current = parser.format(values.get(index));
        String text = JOptionPane.showInputDialog(this, "Value:", current);
        if (text != null) {
            replaceParsedValue(index, text);
        }
    }

    private void addParsedValue(String text) {
        try {
            values.add(ParameterValueList.normalizeValue(parser.parse(text)));
            rebuild();
            fireChanged();
        } catch (RuntimeException e) {
            showParseError(e);
        }
    }

    private void replaceParsedValue(int index, String text) {
        if (index < 0 || index >= values.size()) {
            return;
        }
        try {
            values.set(index, ParameterValueList.normalizeValue(parser.parse(text)));
            rebuild();
            fireChanged();
        } catch (RuntimeException e) {
            showParseError(e);
        }
    }

    private void removeValue(int index) {
        if (index < 0 || index >= values.size() || values.size() <= 1) {
            return;
        }
        values.remove(index);
        rebuild();
        fireChanged();
    }

    private void rebuild() {
        removeAll();
        for (int i = 0; i < values.size(); i++) {
            add(createChip(i));
        }
        add(createAddChip());
        revalidate();
        repaint();
    }

    private JPanel createChip(final int index) {
        JPanel chip = new JPanel();
        chip.setOpaque(false);
        chip.setLayout(new FlowLayout(FlowLayout.LEFT, 0, 0));

        Object value = values.get(index);
        Chip valueChip = new Chip(parser.format(value), CHIP_BG, CHIP_FG, CHIP_BORDER);
        valueChip.setFont(value instanceof Number
                ? FlashTheme.mono(11f)
                : FlashTheme.bodyMedium().deriveFont(11f));
        valueChip.setToolTipText("Edit value");
        valueChip.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                promptAndEdit(index);
            }
        });
        chip.add(valueChip);

        boolean removable = values.size() > 1;
        Chip removeChip = new Chip("x",
                removable ? CHIP_BG : CHIP_DISABLED_BG,
                removable ? CHIP_FG : CHIP_DISABLED_FG,
                CHIP_BORDER);
        removeChip.setFont(FlashTheme.bodyMedium().deriveFont(11f));
        removeChip.setToolTipText(removable ? "Remove value" : "At least one value is required");
        removeChip.setEnabled(removable);
        if (removable) {
            removeChip.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    removeValue(index);
                }
            });
        }
        chip.add(removeChip);
        return chip;
    }

    private Chip createAddChip() {
        Chip addChip = new Chip("+", ADD_BG, CHIP_FG, ADD_BORDER);
        addChip.setFont(FlashTheme.bodyMedium().deriveFont(12f));
        addChip.setToolTipText("Add value");
        addChip.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                promptAndAdd();
            }
        });
        return addChip;
    }

    private void showParseError(RuntimeException e) {
        final String message = e.getMessage() == null
                ? "Could not parse that value."
                : e.getMessage();
        if (SwingUtilities.isEventDispatchThread()) {
            JOptionPane.showMessageDialog(this, message, "Parameter value",
                    JOptionPane.WARNING_MESSAGE);
        } else {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    JOptionPane.showMessageDialog(ValueChipPanel.this, message,
                            "Parameter value", JOptionPane.WARNING_MESSAGE);
                }
            });
        }
    }

    private void fireChanged() {
        ChangeEvent event = new ChangeEvent(this);
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).stateChanged(event);
        }
    }

    private static final class Chip extends JLabel {
        private final Color bg;
        private final Color fg;
        private final Color border;

        Chip(String text, Color bg, Color fg, Color border) {
            super(text);
            this.bg = bg;
            this.fg = fg;
            this.border = border;
            setOpaque(false);
            setForeground(fg);
            setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            setCursor(enabled
                    ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    : Cursor.getDefaultCursor());
        }

        @Override public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            return new Dimension(size.width, Math.max(22, size.height));
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            int arc = h;
            g2.setColor(isEnabled() ? bg : CHIP_DISABLED_BG);
            g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
            g2.setColor(border);
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawRoundRect(1, 1, Math.max(1, w - 3), Math.max(1, h - 3),
                    Math.max(1, arc - 2), Math.max(1, arc - 2));
            g2.dispose();
            setForeground(isEnabled() ? fg : CHIP_DISABLED_FG);
            super.paintComponent(g);
        }
    }
}
