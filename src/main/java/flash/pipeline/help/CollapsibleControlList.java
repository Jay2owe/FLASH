package flash.pipeline.help;

import javax.swing.AbstractButton;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a {@link ControlHelpTopic} as an old-school manual: section headings
 * with a collapsible entry per control. Every entry starts collapsed so the
 * panel opens as a scannable table of contents; clicking an entry (or "Expand
 * all") reveals the full explanation.
 */
public final class CollapsibleControlList extends JPanel {

    private static final Color SUBHEADER = new Color(78, 93, 101);
    private static final Color TEXT = new Color(33, 33, 33);
    private static final Color MUTED = new Color(117, 117, 117);
    private static final Color ENTRY_BG = new Color(255, 255, 255);
    private static final Color ENTRY_BORDER = new Color(224, 227, 229);
    private static final int TEXT_WIDTH = 600;

    private final List<Entry> entries = new ArrayList<Entry>();

    public CollapsibleControlList(ControlHelpTopic topic) {
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setAlignmentX(Component.LEFT_ALIGNMENT);
        setBorder(new EmptyBorder(0, 0, 6, 0));

        add(buildToolbar());
        add(Box.createVerticalStrut(6));

        for (ControlHelpTopic.Group group : topic.groups) {
            add(groupHeading(group.heading));
            if (group.intro != null) {
                JLabel intro = paragraph(group.intro, 11f, MUTED);
                intro.setBorder(new EmptyBorder(0, 0, 4, 0));
                add(intro);
            }
            for (ControlHelpTopic.Control control : group.controls) {
                Entry entry = new Entry(control);
                entries.add(entry);
                add(entry);
                add(Box.createVerticalStrut(4));
            }
            add(Box.createVerticalStrut(8));
        }
    }

    private JPanel buildToolbar() {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));

        JLabel hint = new JLabel("Click a control to see what it does");
        hint.setFont(hint.getFont().deriveFont(Font.ITALIC, 11f));
        hint.setForeground(MUTED);
        row.add(hint);
        row.add(Box.createHorizontalGlue());
        row.add(linkButton("Expand all", true));
        row.add(Box.createHorizontalStrut(4));
        row.add(linkButton("Collapse all", false));
        return row;
    }

    private JButton linkButton(String text, final boolean expand) {
        JButton button = new JButton(text);
        button.setFont(button.getFont().deriveFont(Font.PLAIN, 11f));
        button.setForeground(SUBHEADER);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setMargin(new java.awt.Insets(0, 4, 0, 4));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.addActionListener(e -> setAllExpanded(expand));
        return button;
    }

    /** Expands the first {@code count} entries; used by offscreen previews. */
    public void expandFirst(int count) {
        for (int i = 0; i < entries.size() && i < count; i++) {
            entries.get(i).setExpanded(true);
        }
        revalidate();
        repaint();
    }

    private void setAllExpanded(boolean expanded) {
        for (Entry entry : entries) {
            entry.setExpanded(expanded);
        }
        revalidate();
        repaint();
    }

    private JLabel groupHeading(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
        label.setForeground(SUBHEADER);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(new EmptyBorder(6, 0, 4, 0));
        return label;
    }

    /** One collapsible control row: a disclosure header plus a hidden detail body. */
    private final class Entry extends JPanel {
        private final JButton header;
        private final JPanel detail;
        private final String labelText;
        private final String badgeText;
        private boolean expanded;

        Entry(ControlHelpTopic.Control control) {
            this.labelText = control.label;
            this.badgeText = control.badge;
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBackground(ENTRY_BG);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setBorder(javax.swing.BorderFactory.createCompoundBorder(
                    javax.swing.BorderFactory.createLineBorder(ENTRY_BORDER),
                    new EmptyBorder(2, 8, 4, 8)));

            header = new JButton(headerHtml(false));
            header.setHorizontalAlignment(AbstractButton.LEFT);
            header.setBorderPainted(false);
            header.setContentAreaFilled(false);
            header.setFocusPainted(false);
            header.setMargin(new java.awt.Insets(3, 0, 3, 0));
            header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            header.setAlignmentX(Component.LEFT_ALIGNMENT);
            header.addActionListener(e -> {
                setExpanded(!expanded);
                CollapsibleControlList.this.revalidate();
                CollapsibleControlList.this.repaint();
            });
            add(header);

            detail = buildDetail(control);
            detail.setVisible(false);
            add(detail);
        }

        private JPanel buildDetail(ControlHelpTopic.Control control) {
            JPanel panel = new JPanel();
            panel.setOpaque(false);
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.setBorder(new EmptyBorder(2, 18, 4, 0));

            panel.add(paragraph(control.summary, 12f, TEXT));
            for (String line : control.details) {
                panel.add(Box.createVerticalStrut(3));
                panel.add(bullet(line));
            }
            return panel;
        }

        void setExpanded(boolean value) {
            if (expanded == value) {
                return;
            }
            expanded = value;
            detail.setVisible(value);
            header.setText(headerHtml(value));
        }

        private String headerHtml(boolean open) {
            String triangle = open ? "&#9662;" : "&#9656;"; // down / right pointing triangle
            String badge = badgeText == null
                    ? ""
                    : "&nbsp;&nbsp;<font color='#757575'>&mdash; " + escape(badgeText) + "</font>";
            return "<html><body>" + triangle + "&nbsp;&nbsp;<b>" + escape(labelText) + "</b>"
                    + badge + "</body></html>";
        }
    }

    private static JLabel paragraph(String text, float size, Color color) {
        JLabel label = new JLabel("<html><body width='" + TEXT_WIDTH + "'>" + escape(text) + "</body></html>");
        label.setFont(label.getFont().deriveFont(Font.PLAIN, size));
        label.setForeground(color);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static JLabel bullet(String text) {
        JLabel label = new JLabel("<html><body width='" + TEXT_WIDTH + "'>&bull;&nbsp;" + escape(text) + "</body></html>");
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
        label.setForeground(TEXT);
        label.setBorder(new EmptyBorder(0, 6, 0, 0));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br>");
    }
}
