package flash.pipeline.ui.preview;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public final class FijiStyleRangeSliderPanel extends JPanel {

    public interface Listener {
        void valueChanged(double value, boolean adjusting);
    }

    private static final int SLIDER_STEPS = 1000;
    private static final DecimalFormat NUMBER_FORMAT =
            new DecimalFormat("0.###", DecimalFormatSymbols.getInstance(Locale.US));

    private final JLabel label;
    private final JSlider slider = new JSlider(0, SLIDER_STEPS, 0);
    private final JTextField valueField = new JTextField(8);

    private double minimum = 0.0;
    private double maximum = 255.0;
    private double value = 0.0;
    private boolean updating;
    private Listener listener;

    public FijiStyleRangeSliderPanel(String labelText) {
        super(new BorderLayout(8, 0));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));

        label = new JLabel(labelText == null ? "" : labelText);
        label.setPreferredSize(new Dimension(112, 24));
        valueField.setHorizontalAlignment(SwingConstants.RIGHT);
        valueField.setMinimumSize(new Dimension(64, 24));
        valueField.setPreferredSize(new Dimension(78, 24));

        add(label, BorderLayout.WEST);
        add(slider, BorderLayout.CENTER);
        add(valueField, BorderLayout.EAST);

        slider.addChangeListener(new ChangeListener() {
            @Override public void stateChanged(ChangeEvent e) {
                if (updating) return;
                value = valueFromSlider();
                updateValueField();
                fireValueChanged(slider.getValueIsAdjusting());
            }
        });

        valueField.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                commitFieldValue();
            }
        });
        valueField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) {
                commitFieldValue();
            }
        });

        setRange(0.0, 255.0);
        setValue(0.0);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setRange(double minimum, double maximum) {
        double safeMinimum = Double.isFinite(minimum) ? minimum : 0.0;
        double safeMaximum = Double.isFinite(maximum) ? maximum : safeMinimum + 1.0;
        if (!(safeMaximum > safeMinimum)) {
            safeMaximum = safeMinimum + 1.0;
        }
        this.minimum = safeMinimum;
        this.maximum = safeMaximum;
        setValue(this.value);
    }

    public void setValue(double value) {
        this.value = clamp(value, minimum, maximum);
        updating = true;
        try {
            slider.setValue(sliderPosition(this.value));
            updateValueField();
        } finally {
            updating = false;
        }
    }

    public double getValue() {
        return value;
    }

    public double getMinimumValue() {
        return minimum;
    }

    public double getMaximumValue() {
        return maximum;
    }

    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        label.setEnabled(enabled);
        slider.setEnabled(enabled);
        valueField.setEnabled(enabled);
    }

    JSlider sliderForTest() {
        return slider;
    }

    JTextField valueFieldForTest() {
        return valueField;
    }

    private void commitFieldValue() {
        if (updating) return;
        double parsed;
        try {
            parsed = Double.parseDouble(valueField.getText().trim());
        } catch (NumberFormatException ex) {
            updateValueField();
            return;
        }
        double clamped = clamp(parsed, minimum, maximum);
        setValue(clamped);
        fireValueChanged(false);
    }

    private int sliderPosition(double value) {
        if (!(maximum > minimum)) return 0;
        double normalized = (value - minimum) / (maximum - minimum);
        return (int) Math.round(clamp(normalized, 0.0, 1.0) * SLIDER_STEPS);
    }

    private double valueFromSlider() {
        if (!(maximum > minimum)) return minimum;
        double normalized = slider.getValue() / (double) SLIDER_STEPS;
        return minimum + normalized * (maximum - minimum);
    }

    private void updateValueField() {
        valueField.setText(formatNumber(value));
    }

    private void fireValueChanged(boolean adjusting) {
        if (listener != null) {
            listener.valueChanged(value, adjusting);
        }
    }

    static double clamp(double value, double minimum, double maximum) {
        if (maximum < minimum) return minimum;
        if (!Double.isFinite(value)) return minimum;
        if (value < minimum) return minimum;
        if (value > maximum) return maximum;
        return value;
    }

    static String formatNumber(double value) {
        if (!Double.isFinite(value)) return "";
        double rounded = Math.rint(value);
        if (Math.abs(value - rounded) < 0.0000001 && Math.abs(rounded) < 1000000000.0) {
            return String.valueOf((long) rounded);
        }
        synchronized (NUMBER_FORMAT) {
            return NUMBER_FORMAT.format(value);
        }
    }
}
