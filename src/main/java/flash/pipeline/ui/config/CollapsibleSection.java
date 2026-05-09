package flash.pipeline.ui.config;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Header + body container that toggles body visibility on header click. Used
 * by the filter-hyperparameters wizard stage to show one collapsible row per
 * macro section. Pure Swing; safe under {@code GraphicsEnvironment.isHeadless()}.
 */
public final class CollapsibleSection extends JPanel {

    private final JLabel headerLabel;
    private final JPanel body;
    private String headerText;
    private boolean expanded;

    public CollapsibleSection(String headerText, boolean initiallyExpanded) {
        super(new BorderLayout(0, 4));
        setOpaque(false);
        setAlignmentX(Component.LEFT_ALIGNMENT);

        this.headerLabel = new JLabel();
        this.headerLabel.setOpaque(true);
        this.headerLabel.setBackground(new Color(238, 238, 238));
        this.headerLabel.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        this.headerLabel.setHorizontalAlignment(SwingConstants.LEFT);
        this.headerLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        this.headerLabel.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                setExpanded(!CollapsibleSection.this.expanded);
            }
        });
        add(this.headerLabel, BorderLayout.NORTH);

        this.body = new JPanel();
        this.body.setOpaque(false);
        this.body.setLayout(new BoxLayout(this.body, BoxLayout.Y_AXIS));
        this.body.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        this.body.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(this.body, BorderLayout.CENTER);

        this.headerText = headerText == null ? "" : headerText;
        this.expanded = initiallyExpanded;
        this.body.setVisible(initiallyExpanded);
        refreshHeader();
    }

    public void setHeaderText(String text) {
        this.headerText = text == null ? "" : text;
        refreshHeader();
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void setExpanded(boolean newState) {
        if (this.expanded == newState) return;
        this.expanded = newState;
        body.setVisible(newState);
        refreshHeader();
        revalidate();
        repaint();
    }

    public JComponent getBody() {
        return body;
    }

    private void refreshHeader() {
        String prefix = expanded ? "[-] " : "[+] ";
        headerLabel.setText(prefix + headerText);
    }
}
