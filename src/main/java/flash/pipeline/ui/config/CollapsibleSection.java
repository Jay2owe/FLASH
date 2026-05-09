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
import java.awt.Container;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Header + body container that toggles body visibility on header click. Used
 * by the filter-hyperparameters wizard stage to show one collapsible row per
 * macro section. Pure Swing; safe under {@code GraphicsEnvironment.isHeadless()}.
 *
 * <p>Stage 04 added an east-side controls slot ({@link #getHeaderControls()})
 * for the per-row eye, drag, and delete buttons, plus
 * {@link #setRowEnabled(boolean)} and {@link #setHeaderStrikethrough(boolean)}
 * to render disabled rows.</p>
 */
public final class CollapsibleSection extends JPanel {

    private final JPanel headerBar;
    private final JLabel headerLabel;
    private final JPanel headerControls;
    private final JPanel body;
    private String headerText;
    private boolean expanded;
    private boolean strikethrough;

    public CollapsibleSection(String headerText, boolean initiallyExpanded) {
        super(new BorderLayout(0, 4));
        setOpaque(false);
        setAlignmentX(Component.LEFT_ALIGNMENT);

        this.headerBar = new JPanel(new BorderLayout(6, 0));
        this.headerBar.setOpaque(true);
        this.headerBar.setBackground(new Color(238, 238, 238));
        this.headerBar.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));

        this.headerLabel = new JLabel();
        this.headerLabel.setHorizontalAlignment(SwingConstants.LEFT);
        this.headerLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        this.headerLabel.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                setExpanded(!CollapsibleSection.this.expanded);
            }
        });
        this.headerBar.add(this.headerLabel, BorderLayout.CENTER);

        this.headerControls = new JPanel();
        this.headerControls.setOpaque(false);
        this.headerControls.setLayout(new BoxLayout(this.headerControls, BoxLayout.X_AXIS));
        this.headerBar.add(this.headerControls, BorderLayout.EAST);

        add(this.headerBar, BorderLayout.NORTH);

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

    /** Returns the east-side header strip; callers add row controls here. */
    public JPanel getHeaderControls() {
        return headerControls;
    }

    /** Greys out the entire row (header + body + controls). */
    public void setRowEnabled(boolean enabled) {
        headerLabel.setEnabled(enabled);
        setComponentsEnabledRecursive(body, enabled);
        // Header controls (eye/x) intentionally remain enabled so the user
        // can re-enable a disabled row.
    }

    /** Wraps the header text in {@code <strike>} when {@code true}. */
    public void setHeaderStrikethrough(boolean strike) {
        this.strikethrough = strike;
        refreshHeader();
    }

    private void refreshHeader() {
        String prefix = expanded ? "[-] " : "[+] ";
        String text = prefix + escape(headerText);
        if (strikethrough) {
            headerLabel.setText("<html><strike>" + text + "</strike></html>");
        } else {
            headerLabel.setText(text);
        }
    }

    private static String escape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void setComponentsEnabledRecursive(Component comp, boolean enabled) {
        comp.setEnabled(enabled);
        if (comp instanceof Container) {
            Component[] children = ((Container) comp).getComponents();
            for (int i = 0; i < children.length; i++) {
                setComponentsEnabledRecursive(children[i], enabled);
            }
        }
    }
}
