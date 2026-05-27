package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.image.FilterMacroParser;
import flash.pipeline.image.FilterMacroParser.OpType;
import flash.pipeline.ui.FlashTheme;
import flash.pipeline.ui.sandbox.FilterAlternatives;
import flash.pipeline.ui.sandbox.FilterAlternatives.Alternative;
import flash.pipeline.ui.sandbox.FilterAlternatives.SlotRole;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Compact "swap step" editor: shows the alternative filters for the focused
 * chain step. Baseline is auto-ticked and disabled; the user picks 0–8 extra
 * alternatives. Produces a {@link ParameterSweep} backed by
 * {@link SlotSubstitutionKey} that the existing executor knows how to consume.
 */
public final class StepSwapEditor extends JPanel {

    public static final int MAX_TICKED = 8;

    private final FilterVariationEngineContext context;
    private final JLabel stepLabel = new JLabel(" ");
    private final JLabel hintLabel = new JLabel(" ");
    private final JPanel checkboxColumn = new JPanel();
    private final List<AlternativeBox> boxes = new ArrayList<AlternativeBox>();
    private final List<ChangeListener> listeners = new ArrayList<ChangeListener>();

    private int focusedStepIndex = -1;
    private SlotRole currentRole;
    private OpType currentBaselineType = OpType.UNKNOWN;

    public StepSwapEditor(FilterVariationEngineContext context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context;
        setOpaque(false);
        setBorder(BorderFactory.createLineBorder(FlashTheme.BORDER));
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

        stepLabel.setBorder(BorderFactory.createEmptyBorder(6, 10, 2, 10));
        stepLabel.setFont(stepLabel.getFont().deriveFont(java.awt.Font.BOLD));
        add(stepLabel);

        hintLabel.setBorder(BorderFactory.createEmptyBorder(0, 10, 4, 10));
        hintLabel.setForeground(FlashTheme.TEXT_MUTED);
        add(hintLabel);

        checkboxColumn.setOpaque(false);
        checkboxColumn.setLayout(new BoxLayout(checkboxColumn, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(checkboxColumn,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder(0, 10, 8, 10));
        scroll.setPreferredSize(new Dimension(320, 180));
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(scroll);
    }

    public void setFocusedStepIndex(int stepIndex) {
        this.focusedStepIndex = stepIndex;
        rebuildCheckboxes();
        fireChanged();
    }

    public int focusedStepIndex() {
        return focusedStepIndex;
    }

    public int alternativeCount() {
        int count = 0;
        for (int i = 0; i < boxes.size(); i++) {
            AlternativeBox box = boxes.get(i);
            if (box.checkbox.isSelected() && box.alternative.type() != currentBaselineType) {
                count++;
            }
        }
        // baseline cell is always included for comparison; total cells = baseline + alts
        return Math.min(count, MAX_TICKED) + 1;
    }

    public boolean isReady() {
        return focusedStepIndex >= 0
                && currentRole != null
                && !boxes.isEmpty();
    }

    public void addChangeListener(ChangeListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** Test convenience: tick exactly the given alternative labels (case-insensitive). */
    void setTickedForTest(String... labels) {
        java.util.Set<String> wanted = new java.util.HashSet<String>();
        if (labels != null) {
            for (int i = 0; i < labels.length; i++) {
                if (labels[i] != null) {
                    wanted.add(labels[i].toLowerCase(Locale.ROOT));
                }
            }
        }
        for (int i = 0; i < boxes.size(); i++) {
            AlternativeBox box = boxes.get(i);
            if (box.alternative.type() == currentBaselineType) {
                box.checkbox.setSelected(true);
            } else {
                box.checkbox.setSelected(
                        wanted.contains(box.alternative.label().toLowerCase(Locale.ROOT)));
            }
        }
        fireChanged();
    }

    int checkboxCountForTest() {
        return boxes.size();
    }

    boolean baselineDisabledForTest() {
        for (int i = 0; i < boxes.size(); i++) {
            AlternativeBox box = boxes.get(i);
            if (box.alternative.type() == currentBaselineType) {
                return !box.checkbox.isEnabled() && box.checkbox.isSelected();
            }
        }
        return false;
    }

    public ParameterSweep currentSweep(CropSpec cropSpec) {
        if (!isReady()) {
            throw new IllegalStateException(
                    "Pick a chain step with native alternatives.");
        }
        List<String> selectedLabels = new ArrayList<String>();
        // Always include baseline first
        selectedLabels.add(baselineLabel());
        for (int i = 0; i < boxes.size(); i++) {
            AlternativeBox box = boxes.get(i);
            if (!box.checkbox.isSelected()) continue;
            if (box.alternative.type() == currentBaselineType) continue;
            selectedLabels.add(box.alternative.label());
            if (selectedLabels.size() >= MAX_TICKED + 1) break;
        }
        if (selectedLabels.size() < 2) {
            throw new IllegalStateException(
                    "Tick at least one alternative filter to compare.");
        }

        List<String> scaleLabels = scaleLabelsFor(currentRole);
        LinkedHashMap<ParameterKey, ParameterValueList> values =
                new LinkedHashMap<ParameterKey, ParameterValueList>();
        values.put(SlotSubstitutionKey.filterAxis(focusedStepIndex, currentRole.name()),
                new ParameterValueList(selectedLabels));
        values.put(SlotSubstitutionKey.scaleAxis(focusedStepIndex, currentRole.name()),
                new ParameterValueList(scaleLabels));
        return new ParameterSweep(ParameterSweep.Method.FILTER,
                values,
                cropSpec == null ? CropSpec.full() : cropSpec,
                context.channelName(),
                context.sourceImageHash(),
                context.cacheNamespace() + ":swap:" + focusedStepIndex);
    }

    private String baselineLabel() {
        Alternative baseline = FilterAlternatives.alternativeFor(currentRole,
                currentBaselineType);
        if (baseline != null) return baseline.label();
        return currentBaselineType == null ? "" : currentBaselineType.name();
    }

    private void rebuildCheckboxes() {
        checkboxColumn.removeAll();
        boxes.clear();
        currentRole = null;
        currentBaselineType = OpType.UNKNOWN;

        if (focusedStepIndex < 0) {
            stepLabel.setText(" ");
            hintLabel.setText(" ");
            checkboxColumn.revalidate();
            checkboxColumn.repaint();
            return;
        }
        OpType type = opTypeForStep(focusedStepIndex);
        SlotRole role = FilterAlternatives.slotRoleFor(type);
        List<Alternative> alternatives = role == null
                ? Collections.<Alternative>emptyList()
                : FilterAlternatives.alternativesFor(role);
        String entryLabel = entryLabelFor(focusedStepIndex);
        stepLabel.setText("Swap filter for " + entryLabel);

        if (role == null || alternatives.isEmpty()
                || !FilterAlternatives.hasUsefulAlternatives(role)) {
            hintLabel.setText("No native alternatives available for this step.");
            checkboxColumn.revalidate();
            checkboxColumn.repaint();
            return;
        }
        hintLabel.setText("Tick 1–" + MAX_TICKED
                + " alternative filters to compare against the baseline.");
        currentRole = role;
        currentBaselineType = type;
        for (int i = 0; i < alternatives.size(); i++) {
            Alternative alt = alternatives.get(i);
            boolean isBaseline = alt.type() == type;
            JCheckBox cb = new JCheckBox(isBaseline
                    ? alt.label() + "  (baseline)"
                    : alt.label());
            cb.setOpaque(false);
            if (isBaseline) {
                cb.setSelected(true);
                cb.setEnabled(false);
            } else {
                cb.setSelected(false);
            }
            cb.setAlignmentX(LEFT_ALIGNMENT);
            cb.addActionListener(e -> {
                enforceCap(cb);
                fireChanged();
            });
            checkboxColumn.add(cb);
            checkboxColumn.add(Box.createVerticalStrut(2));
            boxes.add(new AlternativeBox(alt, cb));
        }
        checkboxColumn.revalidate();
        checkboxColumn.repaint();
    }

    private void enforceCap(JCheckBox justClicked) {
        if (!justClicked.isSelected()) return;
        int ticked = 0;
        for (int i = 0; i < boxes.size(); i++) {
            AlternativeBox box = boxes.get(i);
            if (box.alternative.type() == currentBaselineType) continue;
            if (box.checkbox.isSelected()) ticked++;
        }
        if (ticked > MAX_TICKED) {
            justClicked.setSelected(false);
            java.awt.Toolkit.getDefaultToolkit().beep();
        }
    }

    private OpType opTypeForStep(int targetStepIndex) {
        FilterMacroEditorModel.MacroDefinition macro = context.baseMacro();
        if (macro == null || targetStepIndex < 0) return OpType.UNKNOWN;
        String[] lines = macro.render()
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .split("\n", -1);
        List<FilterMacroEditorModel.Section> sections = macro.getSections();
        int stepIndex = 0;
        for (int i = 0; i < sections.size(); i++) {
            FilterMacroEditorModel.Section section = sections.get(i);
            for (int j = 0; j < section.entries.size(); j++) {
                FilterMacroEditorModel.Entry entry = section.entries.get(j);
                if (stepIndex == targetStepIndex) {
                    if (entry.lineIndex >= 0 && entry.lineIndex < lines.length) {
                        List<FilterMacroParser.Op> ops =
                                FilterMacroParser.parseString(lines[entry.lineIndex]);
                        if (!ops.isEmpty() && ops.get(0) != null) {
                            return ops.get(0).type;
                        }
                    }
                    return OpType.UNKNOWN;
                }
                stepIndex++;
            }
        }
        return OpType.UNKNOWN;
    }

    private String entryLabelFor(int targetStepIndex) {
        FilterMacroEditorModel.MacroDefinition macro = context.baseMacro();
        if (macro == null) return "step " + targetStepIndex;
        List<FilterMacroEditorModel.Section> sections = macro.getSections();
        int stepIndex = 0;
        for (int i = 0; i < sections.size(); i++) {
            FilterMacroEditorModel.Section section = sections.get(i);
            for (int j = 0; j < section.entries.size(); j++) {
                FilterMacroEditorModel.Entry entry = section.entries.get(j);
                if (stepIndex == targetStepIndex) {
                    return entry.label == null || entry.label.trim().isEmpty()
                            ? ("Step " + (stepIndex + 1))
                            : entry.label.trim();
                }
                stepIndex++;
            }
        }
        return "step " + targetStepIndex;
    }

    private static List<String> scaleLabelsFor(SlotRole role) {
        if (role == null) {
            return Collections.singletonList(SlotSubstitutionCombo.DEFAULT_SCALE_LABEL);
        }
        List<Alternative> alternatives = FilterAlternatives.alternativesFor(role);
        boolean anyScaled = false;
        for (int i = 0; i < alternatives.size(); i++) {
            Alternative alt = alternatives.get(i);
            if (alt != null && !CanonicalScale.isParameterless(alt.type())) {
                anyScaled = true;
                break;
            }
        }
        if (!anyScaled) {
            return Collections.singletonList(SlotSubstitutionCombo.DEFAULT_SCALE_LABEL);
        }
        return Arrays.asList(CanonicalScale.MEDIUM.label());
    }

    private void fireChanged() {
        ChangeEvent event = new ChangeEvent(this);
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).stateChanged(event);
        }
    }

    private static final class AlternativeBox {
        final Alternative alternative;
        final JCheckBox checkbox;

        AlternativeBox(Alternative alternative, JCheckBox checkbox) {
            this.alternative = alternative;
            this.checkbox = checkbox;
        }
    }
}
