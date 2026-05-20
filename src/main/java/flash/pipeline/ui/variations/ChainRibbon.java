package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterMacroEditorModel;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ChainRibbon extends JPanel {

    public enum StepState {
        FIXED,
        SWEPT,
        FOCUSED
    }

    public enum InteractionMode {
        /** Sweep Parameter / Sweep Step: left-click moves the focus pill. */
        FOCUS,
        /** Full Sweep: left-click toggles FIXED ↔ SWEPT on sweepable steps. */
        SWEEP_TOGGLE,
        /** Sweep Presets: clicks are ignored. */
        PASSIVE
    }

    public interface Listener {
        void stepStateChanged(int stepIndex, StepState newState);
    }

    static final Color FIXED_FILL = new Color(0x26, 0x2A, 0x2E);
    static final Color FIXED_TEXT = new Color(0xF0, 0xF0, 0xF0);
    static final Color SWEPT_FILL = new Color(0x56, 0xB4, 0xE9);
    static final Color SWEPT_TEXT = new Color(0x22, 0x22, 0x22);
    static final Color FOCUSED_STROKE = new Color(0x56, 0xB4, 0xE9);
    static final Color CHEVRON = new Color(0x8E, 0x9A, 0xA3);

    private final List<Step> steps;
    private final List<StepPill> pills = new ArrayList<StepPill>();
    private final List<Listener> listeners = new ArrayList<Listener>();
    private final Set<Integer> focusableStepIndexes = new LinkedHashSet<Integer>();
    private final java.util.Map<Integer, String> focusDisabledReasons =
            new java.util.LinkedHashMap<Integer, String>();
    private InteractionMode interactionMode = InteractionMode.SWEEP_TOGGLE;
    private boolean restrictFocusableSteps;

    public ChainRibbon(FilterMacroEditorModel.MacroDefinition macro) {
        this(flattenSteps(macro));
    }

    ChainRibbon(List<Step> steps) {
        this.steps = Collections.unmodifiableList(new ArrayList<Step>(
                steps == null ? Collections.<Step>emptyList() : steps));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 18, 0));
        rebuild();
    }

    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public int stepCount() {
        return steps.size();
    }

    public StepState getStepState(int stepIndex) {
        return pillAt(stepIndex).state;
    }

    public void setStepState(int stepIndex, StepState state) {
        setStepState(stepIndex, state, true);
    }

    public void setInteractionMode(InteractionMode mode) {
        InteractionMode requested = mode == null ? InteractionMode.PASSIVE : mode;
        if (this.interactionMode == requested) {
            return;
        }
        this.interactionMode = requested;
        if (requested != InteractionMode.FOCUS) {
            clearFocusedStep(false);
        }
        if (requested != InteractionMode.SWEEP_TOGGLE) {
            clearAllSwept(false);
        }
        Cursor cursor = requested == InteractionMode.PASSIVE
                ? Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
                : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
        for (int i = 0; i < pills.size(); i++) {
            pills.get(i).setCursor(cursor);
        }
        updateTooltips();
    }

    public InteractionMode interactionMode() {
        return interactionMode;
    }

    public void setFocusableStepIndexes(Set<Integer> focusable,
                                        java.util.Map<Integer, String> disabledReasons) {
        focusableStepIndexes.clear();
        if (focusable != null) {
            for (Integer stepIndex : focusable) {
                if (stepIndex != null
                        && stepIndex.intValue() >= 0
                        && stepIndex.intValue() < pills.size()) {
                    focusableStepIndexes.add(stepIndex);
                }
            }
        }
        focusDisabledReasons.clear();
        if (disabledReasons != null) {
            focusDisabledReasons.putAll(disabledReasons);
        }
        restrictFocusableSteps = true;
        updateTooltips();
    }

    public int focusedStepIndex() {
        for (int i = 0; i < pills.size(); i++) {
            if (pills.get(i).state == StepState.FOCUSED) {
                return i;
            }
        }
        return -1;
    }

    public void focusStep(int stepIndex) {
        focusStep(stepIndex, true);
    }

    public void clearFocusedStep() {
        clearFocusedStep(true);
    }

    public Set<Integer> stepIndexesInState(StepState state) {
        Set<Integer> out = new LinkedHashSet<Integer>();
        if (state == null) {
            return out;
        }
        for (int i = 0; i < pills.size(); i++) {
            if (pills.get(i).state == state) {
                out.add(Integer.valueOf(i));
            }
        }
        return out;
    }

    Rectangle stepBoundsForTest(int stepIndex) {
        return new Rectangle(pillAt(stepIndex).getBounds());
    }

    JComponent stepComponentForTest(int stepIndex) {
        return pillAt(stepIndex);
    }

    void clickStepForTest(int stepIndex, int button) {
        StepPill pill = pillAt(stepIndex);
        pill.handleClick(button);
    }

    private void rebuild() {
        removeAll();
        pills.clear();
        for (int i = 0; i < steps.size(); i++) {
            StepPill pill = new StepPill(i, steps.get(i));
            pills.add(pill);
            add(pill);
        }
        revalidate();
        repaint();
    }

    private void setStepState(int stepIndex, StepState state, boolean notify) {
        if (state == StepState.FOCUSED) {
            focusStep(stepIndex, notify);
            return;
        }
        StepPill pill = pillAt(stepIndex);
        StepState safeState = state == null ? StepState.FIXED : state;
        if (safeState == StepState.SWEPT && !pill.step.sweepable) {
            safeState = StepState.FIXED;
        }
        if (pill.state == safeState) {
            return;
        }
        pill.state = safeState;
        pill.repaint();
        if (notify) {
            fireStepStateChanged(stepIndex, safeState);
        }
    }

    private void focusStep(int stepIndex, boolean notify) {
        StepPill target = pillAt(stepIndex);
        if (interactionMode != InteractionMode.FOCUS
                || !isFocusable(stepIndex)) {
            return;
        }
        int previous = focusedStepIndex();
        if (previous == stepIndex) {
            return;
        }
        if (previous >= 0) {
            StepPill previousPill = pillAt(previous);
            previousPill.state = StepState.FIXED;
            previousPill.repaint();
        }
        target.state = StepState.FOCUSED;
        target.repaint();
        if (notify) {
            fireStepStateChanged(stepIndex, StepState.FOCUSED);
        }
    }

    private void clearFocusedStep(boolean notify) {
        int focused = focusedStepIndex();
        if (focused < 0) {
            return;
        }
        StepPill pill = pillAt(focused);
        pill.state = StepState.FIXED;
        pill.repaint();
        if (notify) {
            fireStepStateChanged(focused, StepState.FIXED);
        }
    }

    private void clearAllSwept(boolean notify) {
        for (int i = 0; i < pills.size(); i++) {
            StepPill pill = pills.get(i);
            if (pill.state == StepState.SWEPT) {
                pill.state = StepState.FIXED;
                pill.repaint();
                if (notify) {
                    fireStepStateChanged(i, StepState.FIXED);
                }
            }
        }
    }

    private boolean isFocusable(int stepIndex) {
        if (stepIndex < 0 || stepIndex >= pills.size()) {
            return false;
        }
        if (!restrictFocusableSteps) {
            return true;
        }
        return focusableStepIndexes.contains(Integer.valueOf(stepIndex));
    }

    private void updateTooltips() {
        for (int i = 0; i < pills.size(); i++) {
            pills.get(i).updateTooltip();
        }
    }

    private StepPill pillAt(int stepIndex) {
        if (stepIndex < 0 || stepIndex >= pills.size()) {
            throw new IndexOutOfBoundsException("stepIndex " + stepIndex);
        }
        return pills.get(stepIndex);
    }

    private void fireStepStateChanged(int stepIndex, StepState state) {
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).stepStateChanged(stepIndex, state);
        }
    }

    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (pills.size() < 2) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(CHEVRON);
        g2.setStroke(new BasicStroke(1.5f));
        for (int i = 0; i < pills.size() - 1; i++) {
            Rectangle left = pills.get(i).getBounds();
            Rectangle right = pills.get(i + 1).getBounds();
            int x = left.x + left.width + (right.x - (left.x + left.width)) / 2;
            int y = left.y + left.height / 2;
            int size = 5;
            g2.drawLine(x - size, y - size, x, y);
            g2.drawLine(x, y, x - size, y + size);
        }
        g2.dispose();
    }

    private final class StepPill extends JComponent {
        private static final int MIN_WIDTH = 72;
        private static final int HEIGHT = 26;
        private static final int H_PAD = 12;

        private final int stepIndex;
        private final Step step;
        private StepState state = StepState.FIXED;

        StepPill(int stepIndex, Step step) {
            this.stepIndex = stepIndex;
            this.step = step;
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(step.fullLabel);
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        handleClick(MouseEvent.BUTTON1);
                    }
                }
            });
        }

        void handleClick(int button) {
            if (button != MouseEvent.BUTTON1) {
                return;
            }
            if (interactionMode == InteractionMode.FOCUS) {
                focusStep(stepIndex, true);
                return;
            }
            if (interactionMode == InteractionMode.SWEEP_TOGGLE) {
                if (!step.sweepable) {
                    return;
                }
                StepState next = state == StepState.SWEPT
                        ? StepState.FIXED
                        : StepState.SWEPT;
                setStepState(stepIndex, next, true);
            }
            // PASSIVE: no-op
        }

        @Override public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            int textWidth = fm.stringWidth(displayText());
            return new Dimension(Math.max(MIN_WIDTH, textWidth + H_PAD * 2),
                    HEIGHT);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            int arc = h;
            if (state == StepState.SWEPT) {
                g2.setColor(SWEPT_FILL);
                g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
            } else if (state == StepState.FOCUSED) {
                g2.setColor(FIXED_FILL);
                g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
                g2.setColor(FOCUSED_STROKE);
                g2.setStroke(new BasicStroke(3.0f));
                g2.drawRoundRect(2, 2, Math.max(1, w - 5),
                        Math.max(1, h - 5), Math.max(1, arc - 4),
                        Math.max(1, arc - 4));
            } else {
                g2.setColor(FIXED_FILL);
                g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
            }

            String text = displayText();
            if (state == StepState.FOCUSED) {
                g2.setFont(g2.getFont().deriveFont(java.awt.Font.BOLD));
            }
            FontMetrics fm = g2.getFontMetrics();
            int x = (w - fm.stringWidth(text)) / 2;
            int y = (h - fm.getHeight()) / 2 + fm.getAscent();
            g2.setColor(textColor());
            g2.drawString(text, x, y);
            g2.dispose();
        }

        private String displayText() {
            if (state == StepState.SWEPT) {
                return "≈ " + step.shortLabel;
            }
            return step.shortLabel;
        }

        private Color textColor() {
            if (state == StepState.SWEPT) {
                return SWEPT_TEXT;
            }
            return FIXED_TEXT;
        }

        private void updateTooltip() {
            if (interactionMode == InteractionMode.PASSIVE) {
                setToolTipText(step.fullLabel);
                return;
            }
            if (interactionMode == InteractionMode.FOCUS
                    && !ChainRibbon.this.isFocusable(stepIndex)) {
                String reason = focusDisabledReasons.get(Integer.valueOf(stepIndex));
                setToolTipText(reason == null || reason.trim().isEmpty()
                        ? "No native alternatives available"
                        : reason.trim());
                return;
            }
            setToolTipText(step.fullLabel);
        }
    }

    private static List<Step> flattenSteps(FilterMacroEditorModel.MacroDefinition macro) {
        List<Step> out = new ArrayList<Step>();
        if (macro == null) {
            return out;
        }
        List<FilterMacroEditorModel.Section> sections = macro.getSections();
        for (int i = 0; i < sections.size(); i++) {
            FilterMacroEditorModel.Section section = sections.get(i);
            for (int j = 0; j < section.entries.size(); j++) {
                FilterMacroEditorModel.Entry entry = section.entries.get(j);
                String label = entry.label == null || entry.label.trim().isEmpty()
                        ? "Step " + (out.size() + 1)
                        : entry.label.trim();
                out.add(new Step(label, shorten(label), hasNumericParameter(entry)));
            }
        }
        return out;
    }

    static List<Integer> entryLineIndexes(FilterMacroEditorModel.MacroDefinition macro) {
        List<Integer> out = new ArrayList<Integer>();
        if (macro == null) {
            return out;
        }
        List<FilterMacroEditorModel.Section> sections = macro.getSections();
        for (int i = 0; i < sections.size(); i++) {
            FilterMacroEditorModel.Section section = sections.get(i);
            for (int j = 0; j < section.entries.size(); j++) {
                out.add(Integer.valueOf(section.entries.get(j).lineIndex));
            }
        }
        return out;
    }

    private static boolean hasNumericParameter(FilterMacroEditorModel.Entry entry) {
        if (entry == null) {
            return false;
        }
        for (int i = 0; i < entry.parameters.size(); i++) {
            FilterMacroEditorModel.Parameter parameter = entry.parameters.get(i);
            if (isFiniteDouble(parameter == null ? null : parameter.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFiniteDouble(String value) {
        try {
            double parsed = Double.parseDouble(value == null ? "" : value.trim());
            return !Double.isNaN(parsed) && !Double.isInfinite(parsed);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String shorten(String label) {
        String trimmed = label == null ? "" : label.trim();
        if (trimmed.isEmpty()) {
            return "Step";
        }
        int paren = trimmed.indexOf('(');
        if (paren > 0) {
            trimmed = trimmed.substring(0, paren).trim();
        }
        String[] words = trimmed.split("\\s+");
        if (words.length > 0 && words[0].length() >= 4) {
            return words[0];
        }
        if (trimmed.length() <= 14) {
            return trimmed;
        }
        return trimmed.substring(0, 13).trim() + ".";
    }

    static final class Step {
        final String fullLabel;
        final String shortLabel;
        final boolean sweepable;

        Step(String fullLabel, String shortLabel, boolean sweepable) {
            this.fullLabel = fullLabel == null ? "" : fullLabel;
            this.shortLabel = shortLabel == null || shortLabel.trim().isEmpty()
                    ? "Step"
                    : shortLabel.trim();
            this.sweepable = sweepable;
        }
    }
}
