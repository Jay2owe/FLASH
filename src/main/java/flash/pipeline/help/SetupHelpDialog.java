package flash.pipeline.help;

import flash.pipeline.ui.PipelineDialog;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.util.List;

/**
 * Focused help dialog for one Set Up Configuration step.
 */
public final class SetupHelpDialog {

    private static final Color BG = new Color(245, 245, 245);
    private static final Color HEADER = new Color(55, 71, 79);
    private static final Color SUBHEADER = new Color(78, 93, 101);
    private static final Color TEXT = new Color(33, 33, 33);
    private static final int TEXT_WIDTH = 650;

    private SetupHelpDialog() {
    }

    public static void show(Component owner, SetupHelpTopic topic) {
        if (topic == null || GraphicsEnvironment.isHeadless()) {
            return;
        }

        Window window = owner instanceof Window
                ? (Window) owner
                : owner == null ? null : SwingUtilities.getWindowAncestor(owner);
        final PipelineDialog dialog = window == null
                ? new PipelineDialog("About " + topic.title)
                : new PipelineDialog(window, "About " + topic.title);
        dialog.setDefaultButtonsVisible(false);
        dialog.addComponent(buildContentPanel(topic));

        JButton close = dialog.addRightFooterButton("Close");
        close.addActionListener(e -> dialog.closeWithAction("close"));
        dialog.showDialog();
    }

    static JPanel buildContentPanel(SetupHelpTopic topic) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(BG);
        panel.setBorder(new EmptyBorder(0, 0, 4, 0));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        panel.add(titleLabel(topic.title));
        panel.add(Box.createVerticalStrut(5));
        panel.add(paragraph(topic.summary, 12f, TEXT, TEXT_WIDTH));

        if (topic.key != null && HelpDiagram.has(topic.key)) {
            javax.swing.Icon diagram = HelpDiagram.diagramFor(topic.key, 320);
            if (diagram != null) {
                panel.add(Box.createVerticalStrut(10));
                JLabel diagramLabel = new JLabel(diagram);
                diagramLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
                panel.add(diagramLabel);
            }
        }
        panel.add(Box.createVerticalStrut(8));

        for (SetupHelpTopic.Section section : topic.sections) {
            addListSection(panel, section.heading, section.items);
        }
        return panel;
    }

    private static JLabel titleLabel(String text) {
        JLabel label = new JLabel(text == null ? "" : text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 17f));
        label.setForeground(HEADER);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static void addListSection(JPanel panel, String heading, List<String> values) {
        panel.add(Box.createVerticalStrut(8));
        JLabel headingLabel = new JLabel(heading == null ? "" : heading);
        headingLabel.setFont(headingLabel.getFont().deriveFont(Font.BOLD, 13f));
        headingLabel.setForeground(SUBHEADER);
        headingLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(headingLabel);
        panel.add(Box.createVerticalStrut(4));

        if (values == null || values.isEmpty()) {
            return;
        }
        for (String value : values) {
            panel.add(bullet(value));
            panel.add(Box.createVerticalStrut(3));
        }
        panel.add(Box.createVerticalStrut(4));
    }

    private static JLabel bullet(String text) {
        JLabel label = new JLabel("<html><body width='" + TEXT_WIDTH + "'>"
                + "&bull; " + htmlText(text) + "</body></html>");
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
        label.setForeground(TEXT);
        label.setBorder(new EmptyBorder(0, 8, 0, 0));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static JLabel paragraph(String text, float size, Color color, int width) {
        JLabel label = new JLabel("<html><body width='" + width + "'>"
                + htmlText(text) + "</body></html>");
        label.setFont(label.getFont().deriveFont(Font.PLAIN, size));
        label.setForeground(color);
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
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
