package flash.pipeline.help;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;

/**
 * Renders one help image with a compact missing-asset placeholder.
 */
public final class HelpImagePanel extends JPanel {

    private static final Color BORDER = new Color(190, 201, 207);
    private static final Color PLACEHOLDER_BG = new Color(238, 242, 244);
    private static final Color TEXT = new Color(33, 33, 33);
    private static final Color MUTED = new Color(117, 117, 117);
    private static final int MAX_IMAGE_WIDTH = 260;
    private static final int MAX_IMAGE_HEIGHT = 150;

    public HelpImagePanel(AnalysisHelpTopic.HelpImage image) {
        setLayout(new javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS));
        setBackground(Color.WHITE);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(8, 8, 8, 8)));
        setAlignmentX(Component.LEFT_ALIGNMENT);

        String title = image == null ? "Example image" : image.title;
        String caption = image == null ? "Image coming later." : image.caption;
        String resourcePath = image == null ? null : image.resourcePath;

        JLabel titleLabel = new JLabel(title == null ? "" : title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 11f));
        titleLabel.setForeground(TEXT);
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(titleLabel);
        add(javax.swing.Box.createVerticalStrut(5));

        JLabel imageLabel = loadImageLabel(resourcePath);
        imageLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(imageLabel);
        add(javax.swing.Box.createVerticalStrut(5));

        JLabel captionLabel = new JLabel("<html><body width='240'>"
                + htmlText(caption) + "</body></html>");
        captionLabel.setFont(captionLabel.getFont().deriveFont(Font.PLAIN, 10f));
        captionLabel.setForeground(MUTED);
        captionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(captionLabel);
    }

    private static JLabel loadImageLabel(String resourcePath) {
        ImageIcon icon = HelpImageLoader.loadScaledIcon(resourcePath, MAX_IMAGE_WIDTH, MAX_IMAGE_HEIGHT);
        if (icon == null) {
            JLabel placeholder = new JLabel("Image coming later", SwingConstants.CENTER);
            placeholder.setOpaque(true);
            placeholder.setBackground(PLACEHOLDER_BG);
            placeholder.setForeground(MUTED);
            placeholder.setFont(placeholder.getFont().deriveFont(Font.ITALIC, 11f));
            placeholder.setPreferredSize(new Dimension(MAX_IMAGE_WIDTH, 90));
            placeholder.setMinimumSize(new Dimension(180, 70));
            placeholder.setMaximumSize(new Dimension(MAX_IMAGE_WIDTH, 90));
            return placeholder;
        }

        JLabel label = new JLabel(icon);
        label.setPreferredSize(new Dimension(icon.getIconWidth(), icon.getIconHeight()));
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
