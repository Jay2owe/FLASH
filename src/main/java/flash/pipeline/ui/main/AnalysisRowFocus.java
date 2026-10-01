package flash.pipeline.ui.main;

import flash.pipeline.ui.FlashTheme;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks which analysis row the user last clicked or tabbed into, highlights
 * it, and shows it in the side panel.
 */
public final class AnalysisRowFocus {

    static final Color FOCUS_BG = FlashTheme.STAGE_ACTIVE_BG;

    private final AnalysisDetailPanel detail;
    private final Map<Integer, List<JComponent>> rows = new LinkedHashMap<Integer, List<JComponent>>();
    private int focused = -1;

    public AnalysisRowFocus(AnalysisDetailPanel detail) {
        this.detail = detail;
    }

    /**
     * Registers the components that make up one analysis row. Clicking any of
     * them, or focusing the given focus target, selects the analysis.
     */
    public void register(final int analysisIndex, Component focusTarget, JComponent... parts) {
        List<JComponent> list = new ArrayList<JComponent>();
        MouseAdapter click = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                focus(analysisIndex);
            }
        };
        for (JComponent part : parts) {
            if (part == null) continue;
            list.add(part);
            listenRecursively(part, click);
        }
        if (focusTarget != null) {
            focusTarget.addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent e) {
                    focus(analysisIndex);
                }
            });
        }
        rows.put(Integer.valueOf(analysisIndex), list);
        if (focused < 0) focus(analysisIndex);
    }

    public void focus(int analysisIndex) {
        if (!rows.containsKey(Integer.valueOf(analysisIndex))) return;
        if (focused == analysisIndex) {
            if (detail != null && detail.getShownAnalysis() != analysisIndex) detail.show(analysisIndex);
            return;
        }
        paint(focused, false);
        focused = analysisIndex;
        paint(focused, true);
        if (detail != null) detail.show(analysisIndex);
    }

    public int getFocused() {
        return focused;
    }

    boolean isHighlightedForTests(int analysisIndex) {
        List<JComponent> parts = rows.get(Integer.valueOf(analysisIndex));
        return parts != null && !parts.isEmpty() && parts.get(0).isOpaque()
                && FOCUS_BG.equals(parts.get(0).getBackground());
    }

    private void paint(int analysisIndex, boolean on) {
        List<JComponent> parts = rows.get(Integer.valueOf(analysisIndex));
        if (parts == null) return;
        for (JComponent part : parts) {
            part.setOpaque(on);
            if (on) part.setBackground(FOCUS_BG);
            part.repaint();
        }
    }

    private static void listenRecursively(Component component, MouseAdapter click) {
        component.addMouseListener(click);
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                listenRecursively(child, click);
            }
        }
    }
}
