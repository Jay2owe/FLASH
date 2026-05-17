package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterMacroEditorModel;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Compact "sweep parameter" editor: pick a numeric parameter on the focused
 * chain step and a min/max/steps range, the range is rendered into a single
 * {@link ParameterValueList} that the executor consumes.
 */
public final class SweepRangeEditor extends JPanel {

    /** Maximum sweep steps. */
    public static final int MAX_STEPS = 9;

    private final FilterVariationEngineContext context;
    private final JLabel stepLabel = new JLabel(" ");
    private final JComboBox<ParameterChoice> paramCombo = new JComboBox<ParameterChoice>();
    private final SpinnerNumberModel minModel = new SpinnerNumberModel(0.0, -1.0e9, 1.0e9, 0.1);
    private final SpinnerNumberModel maxModel = new SpinnerNumberModel(0.0, -1.0e9, 1.0e9, 0.1);
    private final SpinnerNumberModel stepsModel = new SpinnerNumberModel(2, 1, MAX_STEPS, 1);
    private final JSpinner minSpinner = new JSpinner(minModel);
    private final JSpinner maxSpinner = new JSpinner(maxModel);
    private final JSpinner stepsSpinner = new JSpinner(stepsModel);
    private final JLabel previewLabel = new JLabel(" ");
    private final List<ChangeListener> listeners = new ArrayList<ChangeListener>();

    private int focusedStepIndex = -1;
    private boolean updatingParamCombo;

    public SweepRangeEditor(FilterVariationEngineContext context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context;
        setOpaque(false);
        setBorder(BorderFactory.createLineBorder(new Color(214, 220, 224)));
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

        stepLabel.setBorder(BorderFactory.createEmptyBorder(6, 10, 2, 10));
        stepLabel.setFont(stepLabel.getFont().deriveFont(java.awt.Font.BOLD));
        add(stepLabel);

        add(paramRow());
        add(rangeRow());
        add(previewRow());

        paramCombo.addActionListener(e -> {
            if (updatingParamCombo) return;
            applyDefaultsForParam();
            refreshPreview();
            fireChanged();
        });
        minSpinner.addChangeListener(e -> {
            refreshPreview();
            fireChanged();
        });
        maxSpinner.addChangeListener(e -> {
            refreshPreview();
            fireChanged();
        });
        stepsSpinner.addChangeListener(e -> {
            refreshPreview();
            fireChanged();
        });
    }

    public void setFocusedStepIndex(int stepIndex) {
        this.focusedStepIndex = stepIndex;
        rebuildParamCombo();
        applyDefaultsForParam();
        refreshPreview();
        fireChanged();
    }

    public int focusedStepIndex() {
        return focusedStepIndex;
    }

    public int alternativeCount() {
        if (!isReady()) return 0;
        return ((Number) stepsModel.getValue()).intValue();
    }

    public boolean isReady() {
        return focusedStepIndex >= 0 && selectedParam() != null;
    }

    public void addChangeListener(ChangeListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** Test convenience: pick the param choice by its parameter key. */
    boolean selectParamByKeyForTest(String paramKey) {
        if (paramKey == null) return false;
        for (int i = 0; i < paramCombo.getItemCount(); i++) {
            ParameterChoice choice = paramCombo.getItemAt(i);
            if (choice != null && paramKey.equals(choice.id.paramKey())) {
                paramCombo.setSelectedIndex(i);
                return true;
            }
        }
        return false;
    }

    int paramCountForTest() {
        return paramCombo.getItemCount();
    }

    void setRangeForTest(double min, double max, int steps) {
        minModel.setValue(min);
        maxModel.setValue(max);
        stepsModel.setValue(Math.max(1, Math.min(MAX_STEPS, steps)));
        refreshPreview();
        fireChanged();
    }

    int stepsValueForTest() {
        return ((Number) stepsModel.getValue()).intValue();
    }

    SpinnerNumberModel stepsModelForTest() {
        return stepsModel;
    }

    public ParameterSweep currentSweep(CropSpec cropSpec) {
        if (!isReady()) {
            throw new IllegalStateException(
                    "Pick a chain step and parameter to sweep.");
        }
        ParameterChoice choice = selectedParam();
        double min = ((Number) minModel.getValue()).doubleValue();
        double max = ((Number) maxModel.getValue()).doubleValue();
        int steps = ((Number) stepsModel.getValue()).intValue();
        if (steps < 1) steps = 1;
        if (max < min) {
            double tmp = min;
            min = max;
            max = tmp;
        }
        List<Object> values = arithmeticValues(min, max, steps, choice.integerValued);
        LinkedHashMap<ParameterKey, ParameterValueList> sweepValues =
                new LinkedHashMap<ParameterKey, ParameterValueList>();
        sweepValues.put(choice.id, new ParameterValueList(values));
        return new ParameterSweep(ParameterSweep.Method.FILTER,
                sweepValues,
                cropSpec == null ? CropSpec.full() : cropSpec,
                context.channelName(),
                context.sourceImageHash(),
                context.cacheNamespace() + ":range:" + focusedStepIndex
                        + ":" + choice.id.paramKey());
    }

    private JPanel paramRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        row.setOpaque(false);
        row.add(new JLabel("Parameter:"));
        paramCombo.setRenderer(new ParameterChoiceRenderer());
        Dimension size = paramCombo.getPreferredSize();
        paramCombo.setPreferredSize(new Dimension(Math.max(260, size.width), size.height));
        row.add(paramCombo);
        return row;
    }

    private JPanel rangeRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        row.setOpaque(false);
        row.add(new JLabel("Min:"));
        minSpinner.setPreferredSize(new Dimension(90, 24));
        row.add(minSpinner);
        row.add(Box.createHorizontalStrut(8));
        row.add(new JLabel("Max:"));
        maxSpinner.setPreferredSize(new Dimension(90, 24));
        row.add(maxSpinner);
        row.add(Box.createHorizontalStrut(8));
        row.add(new JLabel("Steps:"));
        stepsSpinner.setPreferredSize(new Dimension(60, 24));
        row.add(stepsSpinner);
        row.add(new JLabel("(1–" + MAX_STEPS + ")"));
        return row;
    }

    private JPanel previewRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(0, 10, 6, 10));
        previewLabel.setForeground(new Color(78, 93, 101));
        row.add(previewLabel);
        return row;
    }

    private ParameterChoice selectedParam() {
        Object item = paramCombo.getSelectedItem();
        return item instanceof ParameterChoice ? (ParameterChoice) item : null;
    }

    private void rebuildParamCombo() {
        updatingParamCombo = true;
        try {
            DefaultComboBoxModel<ParameterChoice> model =
                    new DefaultComboBoxModel<ParameterChoice>();
            String stepLabelText = " ";
            if (focusedStepIndex >= 0) {
                List<ParameterChoice> choices = numericParamsForStep(focusedStepIndex);
                for (int i = 0; i < choices.size(); i++) {
                    model.addElement(choices.get(i));
                }
                stepLabelText = stepLabelFor(focusedStepIndex);
            }
            paramCombo.setModel(model);
            if (model.getSize() > 0) {
                paramCombo.setSelectedIndex(0);
            }
            stepLabel.setText(stepLabelText);
        } finally {
            updatingParamCombo = false;
        }
    }

    private List<ParameterChoice> numericParamsForStep(int targetStepIndex) {
        List<ParameterChoice> out = new ArrayList<ParameterChoice>();
        FilterMacroEditorModel.MacroDefinition macro = context.baseMacro();
        if (macro == null || targetStepIndex < 0) {
            return out;
        }
        List<FilterMacroEditorModel.Section> sections = macro.getSections();
        int stepIndex = 0;
        for (int i = 0; i < sections.size(); i++) {
            FilterMacroEditorModel.Section section = sections.get(i);
            for (int j = 0; j < section.entries.size(); j++) {
                FilterMacroEditorModel.Entry entry = section.entries.get(j);
                if (stepIndex == targetStepIndex) {
                    for (int k = 0; k < entry.parameters.size(); k++) {
                        FilterMacroEditorModel.Parameter parameter =
                                entry.parameters.get(k);
                        Double base = parseFiniteDouble(parameter == null ? null
                                : parameter.getValue());
                        if (base == null && parameter != null) {
                            base = parseFiniteDouble(parameter.defaultValue);
                        }
                        if (base == null) {
                            continue;
                        }
                        boolean isInteger = looksLikeInteger(parameter.key,
                                parameter.getValue(), base.doubleValue());
                        FilterParameterId id = new FilterParameterId(i, j, k,
                                entry.label, parameter.key,
                                ParameterKey.ValueKind.NUMBER);
                        out.add(new ParameterChoice(id, base.doubleValue(),
                                isInteger));
                    }
                    return out;
                }
                stepIndex++;
            }
        }
        return out;
    }

    private String stepLabelFor(int targetStepIndex) {
        FilterMacroEditorModel.MacroDefinition macro = context.baseMacro();
        if (macro == null) return " ";
        List<FilterMacroEditorModel.Section> sections = macro.getSections();
        int stepIndex = 0;
        for (int i = 0; i < sections.size(); i++) {
            FilterMacroEditorModel.Section section = sections.get(i);
            for (int j = 0; j < section.entries.size(); j++) {
                FilterMacroEditorModel.Entry entry = section.entries.get(j);
                if (stepIndex == targetStepIndex) {
                    String label = entry.label == null || entry.label.trim().isEmpty()
                            ? ("Step " + (stepIndex + 1))
                            : entry.label.trim();
                    return "Sweep parameter on " + label;
                }
                stepIndex++;
            }
        }
        return " ";
    }

    private void applyDefaultsForParam() {
        ParameterChoice choice = selectedParam();
        if (choice == null) {
            previewLabel.setText("Pick a parameter to sweep.");
            return;
        }
        double base = choice.baseValue;
        double min;
        double max;
        if (Math.abs(base) < 1e-9) {
            min = 0.0;
            max = 1.0;
        } else if (base > 0) {
            min = base / 2.0;
            max = base * 2.0;
        } else {
            min = base * 2.0;
            max = base / 2.0;
        }
        if (choice.integerValued) {
            min = Math.rint(min);
            max = Math.rint(max);
        }
        minModel.setValue(min);
        maxModel.setValue(max);
        stepsModel.setValue(2);
    }

    private void refreshPreview() {
        ParameterChoice choice = selectedParam();
        if (choice == null) {
            previewLabel.setText("Pick a parameter to sweep.");
            return;
        }
        double min = ((Number) minModel.getValue()).doubleValue();
        double max = ((Number) maxModel.getValue()).doubleValue();
        int steps = ((Number) stepsModel.getValue()).intValue();
        if (max < min) {
            double tmp = min;
            min = max;
            max = tmp;
        }
        List<Object> values = arithmeticValues(min, max, steps, choice.integerValued);
        StringBuilder sb = new StringBuilder("Values: ");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(formatNumber(values.get(i)));
        }
        previewLabel.setText(sb.toString());
    }

    private static List<Object> arithmeticValues(double min,
                                                 double max,
                                                 int steps,
                                                 boolean asInteger) {
        if (steps < 1) return Collections.emptyList();
        List<Object> out = new ArrayList<Object>(steps);
        if (steps == 1) {
            out.add(boxValue(min, asInteger));
            return out;
        }
        double stride = (max - min) / (steps - 1);
        for (int i = 0; i < steps; i++) {
            double v = min + i * stride;
            out.add(boxValue(v, asInteger));
        }
        return out;
    }

    private static Object boxValue(double v, boolean asInteger) {
        if (asInteger) {
            return Integer.valueOf((int) Math.rint(v));
        }
        return Double.valueOf(v);
    }

    private static String formatNumber(Object value) {
        if (value instanceof Integer) {
            return Integer.toString(((Integer) value).intValue());
        }
        double v = ((Number) value).doubleValue();
        if (Math.abs(v - Math.rint(v)) < 1e-9) {
            return String.format(Locale.US, "%.0f", v);
        }
        return String.format(Locale.US, "%.2f", v);
    }

    private static Double parseFiniteDouble(String value) {
        try {
            double parsed = Double.parseDouble(value == null ? "" : value.trim());
            return Double.isNaN(parsed) || Double.isInfinite(parsed)
                    ? null
                    : Double.valueOf(parsed);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean looksLikeInteger(String key,
                                            String rawValue,
                                            double parsed) {
        if (Math.rint(parsed) != parsed
                || parsed < Integer.MIN_VALUE
                || parsed > Integer.MAX_VALUE) {
            return false;
        }
        String lowerKey = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        if (lowerKey.equals("sigma") || lowerKey.equals("x")
                || lowerKey.equals("y") || lowerKey.equals("z")) {
            return false;
        }
        if (lowerKey.equals("radius") || lowerKey.equals("rolling")
                || lowerKey.equals("threshold") || lowerKey.equals("iterations")
                || lowerKey.equals("count") || lowerKey.equals("frame")
                || lowerKey.endsWith("_min") || lowerKey.endsWith("_max")
                || lowerKey.indexOf("size") >= 0) {
            return true;
        }
        String text = rawValue == null ? "" : rawValue.trim();
        return text.matches("-?\\d+");
    }

    private void fireChanged() {
        ChangeEvent event = new ChangeEvent(this);
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).stateChanged(event);
        }
    }

    static final class ParameterChoice {
        final FilterParameterId id;
        final double baseValue;
        final boolean integerValued;

        ParameterChoice(FilterParameterId id, double baseValue, boolean integerValued) {
            this.id = id;
            this.baseValue = baseValue;
            this.integerValued = integerValued;
        }

        @Override public String toString() {
            return id.paramKey() + " (base " + (integerValued
                    ? Integer.toString((int) Math.rint(baseValue))
                    : String.format(Locale.US, "%.2f", baseValue)) + ")";
        }
    }

    private static final class ParameterChoiceRenderer
            extends javax.swing.DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(javax.swing.JList<?> list,
                                                      Object value,
                                                      int index,
                                                      boolean isSelected,
                                                      boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected,
                    cellHasFocus);
            if (value instanceof ParameterChoice) {
                setText(((ParameterChoice) value).toString());
            }
            return this;
        }
    }
}
