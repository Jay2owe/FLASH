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
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Window;
import java.util.List;

/**
 * Focused help dialog for one analysis row.
 */
public final class AnalysisHelpDialog {

    private static final Color BG = new Color(245, 245, 245);
    private static final Color HEADER = new Color(55, 71, 79);
    private static final Color SUBHEADER = new Color(78, 93, 101);
    private static final Color TEXT = new Color(33, 33, 33);
    private static final Color MUTED = new Color(117, 117, 117);
    private static final int TEXT_WIDTH = 650;
    private static final int IMAGE_GALLERY_COLUMNS = 2;

    private AnalysisHelpDialog() {
    }

    public static void show(Component owner, AnalysisHelpTopic topic) {
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

    static JPanel buildContentPanel(AnalysisHelpTopic topic) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(BG);
        panel.setBorder(new EmptyBorder(0, 0, 4, 0));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        panel.add(titleLabel(topic.title));
        panel.add(Box.createVerticalStrut(5));
        panel.add(paragraph(topic.summary, 12f, TEXT, TEXT_WIDTH));
        panel.add(Box.createVerticalStrut(8));

        addListSection(panel, "When to use", topic.whenToUse);
        addListSection(panel, "Inputs", topic.inputs);
        addListSection(panel, "Setup decisions", topic.setup);
        addWorkflowSection(panel, topic.workflow);
        addListSection(panel, "Outputs", topic.outputs);
        addImageSection(panel, topic.images);
        addListSection(panel, "Common pitfalls", topic.pitfalls);
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
        addSectionHeading(panel, heading);
        if (values == null || values.isEmpty()) {
            panel.add(paragraph("Details will be added in a later content stage.", 11f, MUTED, TEXT_WIDTH));
            panel.add(Box.createVerticalStrut(5));
            return;
        }
        for (String value : values) {
            panel.add(bullet(value));
            panel.add(Box.createVerticalStrut(3));
        }
        panel.add(Box.createVerticalStrut(4));
    }

    private static void addWorkflowSection(JPanel panel, List<String> workflow) {
        addSectionHeading(panel, "Workflow");
        panel.add(new WorkflowPanel(workflow));
        panel.add(Box.createVerticalStrut(8));
    }

    private static void addImageSection(JPanel panel, List<AnalysisHelpTopic.HelpImage> images) {
        addSectionHeading(panel, "Example images");
        JPanel gallery = new JPanel(new GridLayout(0, IMAGE_GALLERY_COLUMNS, 8, 8));
        gallery.setOpaque(false);
        gallery.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (images == null || images.isEmpty()) {
            gallery.add(new HelpImagePanel(null));
        } else {
            for (AnalysisHelpTopic.HelpImage image : images) {
                gallery.add(new HelpImagePanel(image));
            }
        }

        JPanel wrapper = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        wrapper.setOpaque(false);
        wrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrapper.add(gallery);
        panel.add(wrapper);
        panel.add(Box.createVerticalStrut(8));
    }

    private static void addSectionHeading(JPanel panel, String text) {
        panel.add(Box.createVerticalStrut(8));
        JLabel label = new JLabel(text == null ? "" : text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
        label.setForeground(SUBHEADER);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(label);
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
