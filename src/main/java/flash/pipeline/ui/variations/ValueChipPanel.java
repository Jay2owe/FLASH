package flash.pipeline.ui.variations;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.Color;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ValueChipPanel extends JPanel {

    public interface ValueParser {
        Object parse(String text);
        String format(Object value);
    }

    private final ValueParser parser;
    private final List<Object> values = new ArrayList<Object>();
    private final List<ChangeListener> listeners = new ArrayList<ChangeListener>();
    private final JButton addButton = new JButton("[+]");

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
        addButton.setToolTipText("Add value");
        addButton.addActionListener(e -> promptAndAdd());
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
        add(addButton);
        revalidate();
        repaint();
    }

    private JPanel createChip(final int index) {
        JPanel chip = new JPanel();
        chip.setOpaque(false);
        chip.setLayout(new BoxLayout(chip, BoxLayout.X_AXIS));

        JButton valueButton = new JButton(parser.format(values.get(index)));
        valueButton.setToolTipText("Edit value");
        valueButton.setBackground(new Color(246, 248, 250));
        valueButton.setFocusPainted(false);
        valueButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(190, 198, 205)),
                BorderFactory.createEmptyBorder(1, 6, 1, 6)));
        valueButton.addActionListener(e -> promptAndEdit(index));
        chip.add(valueButton);

        JButton removeButton = new JButton("x");
        removeButton.setToolTipText("Remove value");
        removeButton.setEnabled(values.size() > 1);
        removeButton.setFocusPainted(false);
        removeButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(210, 210, 210)),
                BorderFactory.createEmptyBorder(1, 4, 1, 4)));
        removeButton.addActionListener(e -> removeValue(index));
        chip.add(removeButton);
        return chip;
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
}
