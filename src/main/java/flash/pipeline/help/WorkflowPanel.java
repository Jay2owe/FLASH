package flash.pipeline.help;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

/**
 * Compact visual renderer for an analysis workflow.
 */
public final class WorkflowPanel extends JPanel {

    private static final Color STEP_BG = new Color(255, 255, 255);
    private static final Color STEP_BORDER = new Color(174, 189, 197);
    private static final Color TEXT_COLOR = new Color(33, 33, 33);
    private static final Color ARROW_COLOR = new Color(78, 93, 101);
    private static final int HORIZONTAL_THRESHOLD = 620;
    private static final int HORIZONTAL_TEXT_WIDTH = 130;
    private static final int VERTICAL_TEXT_WIDTH = 430;

    private final List<String> steps;
    private boolean horizontal = true;

    public WorkflowPanel(List<String> steps) {
        this.steps = copyNonBlank(steps);
        setOpaque(false);
        setAlignmentX(Component.LEFT_ALIGNMENT);
        rebuild(true);
    }

    @Override
    public void doLayout() {
        boolean shouldBeHorizontal = getWidth() <= 0 || getWidth() >= HORIZONTAL_THRESHOLD;
        if (shouldBeHorizontal != horizontal) {
            rebuild(shouldBeHorizontal);
        }
        super.doLayout();
    }

    private void rebuild(boolean horizontalMode) {
        horizontal = horizontalMode;
        removeAll();
        if (horizontal) {
            setLayout(new FlowLayout(FlowLayout.LEFT, 8, 6));
        } else {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        }

        if (steps.isEmpty()) {
            add(createStepBox("Workflow details will be added in a later content stage.", VERTICAL_TEXT_WIDTH));
            return;
        }

        for (int i = 0; i < steps.size(); i++) {
            add(createStepBox(steps.get(i), horizontal ? HORIZONTAL_TEXT_WIDTH : VERTICAL_TEXT_WIDTH));
            if (i < steps.size() - 1) {
                add(createArrow(horizontal));
            }
        }
        revalidate();
        repaint();
    }

    private static JLabel createArrow(boolean horizontal) {
        JLabel arrow = new JLabel(horizontal ? ">" : "v", SwingConstants.CENTER);
        arrow.setForeground(ARROW_COLOR);
        arrow.setFont(arrow.getFont().deriveFont(Font.BOLD, 14f));
        if (horizontal) {
            arrow.setPreferredSize(new Dimension(14, 42));
        } else {
            arrow.setAlignmentX(Component.LEFT_ALIGNMENT);
            arrow.setBorder(new EmptyBorder(0, 14, 0, 0));
        }
        return arrow;
    }

    private static JPanel createStepBox(String text, int width) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(STEP_BG);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(STEP_BORDER),
                new EmptyBorder(7, 9, 7, 9)));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel label = new JLabel("<html><body width='" + width + "'>"
                + htmlText(text) + "</body></html>");
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
        label.setForeground(TEXT_COLOR);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(label);
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private static List<String> copyNonBlank(List<String> values) {
        List<String> copy = new ArrayList<String>();
        if (values == null) {
            return copy;
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                copy.add(value.trim());
            }
        }
        return copy;
    }

    private static String htmlText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br>");
    }
}
