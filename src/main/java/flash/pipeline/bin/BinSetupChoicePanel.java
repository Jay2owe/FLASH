package flash.pipeline.bin;

import flash.pipeline.ui.FlashIcons;
import flash.pipeline.ui.FlashTheme;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.Border;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.ArrayList;
import java.util.List;

/**
 * Three selectable cards (Full / Partial / Manual) for the missing-configuration
 * chooser. Exactly one card is selected at a time; the caller reads
 * {@link #getSelectedChoice()} after the dialog's OK button is pressed. The full
 * card is selected by default so OK is immediately actionable.
 */
final class BinSetupChoicePanel extends JPanel {

    private final List<Card> cards = new ArrayList<Card>();
    private Card selected;

    BinSetupChoicePanel(String analysis) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setAlignmentX(Component.LEFT_ALIGNMENT);

        addCard(new Card(BinSetupChooser.Choice.FULL,
                FlashIcons.dotsFull(22, FlashTheme.TEXT_HEADER),
                "Full setup",
                "Set up every channel with live previews of your own images. "
                        + "Reused by every analysis on this folder.",
                "Recommended", Tone.RECOMMENDED));
        addCard(new Card(BinSetupChooser.Choice.PARTIAL,
                FlashIcons.dotsPartial(22, FlashTheme.TEXT_HEADER),
                "Partial setup",
                "Only the steps " + analysis + " needs, with the same live "
                        + "previews. Other analyses ask for theirs later.",
                "This analysis", Tone.NEUTRAL));
        addCard(new Card(BinSetupChooser.Choice.BYPASS,
                FlashIcons.pencil(20, FlashTheme.TEXT_HEADER),
                "Manual entry",
                "Type the values yourself, with FLASH defaults pre-filled. "
                        + "Fast, but no previews or checks.",
                "No previews", Tone.NEUTRAL));

        select(cards.get(0));
    }

    /** The choice backing the currently selected card. */
    BinSetupChooser.Choice getSelectedChoice() {
        return selected == null ? BinSetupChooser.Choice.FULL : selected.choice;
    }

    private void addCard(final Card card) {
        if (!cards.isEmpty()) {
            add(Box.createVerticalStrut(8));
        }
        MouseListener select = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                select(card);
            }
        };
        addClickRecursively(card, select);
        add(card);
        cards.add(card);
    }

    private void select(Card card) {
        selected = card;
        for (Card c : cards) {
            c.setSelected(c == card);
        }
    }

    private static void addClickRecursively(Component component, MouseListener listener) {
        component.addMouseListener(listener);
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                addClickRecursively(child, listener);
            }
        }
    }

    private enum Tone { RECOMMENDED, NEUTRAL }

    /** A single clickable, selectable option card. */
    private static final class Card extends JPanel {
        final BinSetupChooser.Choice choice;
        private boolean selected;
        private final RadioDot radio = new RadioDot();

        Card(BinSetupChooser.Choice choice, Icon icon, String title,
             String description, String chipText, Tone chipTone) {
            this.choice = choice;
            setLayout(new BorderLayout());
            setOpaque(true);
            setBackground(FlashTheme.SURFACE_RAISED);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(borderFor(false));

            // Left column: selection dot + coverage-state icon, pinned to the top.
            JPanel iconsRow = new JPanel();
            iconsRow.setOpaque(false);
            iconsRow.setLayout(new BoxLayout(iconsRow, BoxLayout.X_AXIS));
            iconsRow.add(radio);
            iconsRow.add(Box.createHorizontalStrut(7));
            iconsRow.add(new JLabel(icon));
            JPanel leftWrap = new JPanel(new BorderLayout());
            leftWrap.setOpaque(false);
            leftWrap.add(iconsRow, BorderLayout.NORTH);
            add(leftWrap, BorderLayout.WEST);

            // Right column: title row (title + chip) over a wrapped description.
            JPanel text = new JPanel();
            text.setOpaque(false);
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
            text.setBorder(FlashTheme.pad(0, 8, 0, 0));

            JPanel titleRow = new JPanel();
            titleRow.setOpaque(false);
            titleRow.setLayout(new BoxLayout(titleRow, BoxLayout.X_AXIS));
            titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            titleRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
            JLabel titleLabel = new JLabel(title);
            titleLabel.setFont(FlashTheme.h2());
            titleLabel.setForeground(FlashTheme.TEXT_HEADER);
            titleRow.add(titleLabel);
            titleRow.add(Box.createHorizontalStrut(8));
            titleRow.add(Box.createHorizontalGlue());
            titleRow.add(chip(chipText, chipTone));
            text.add(titleRow);
            text.add(Box.createVerticalStrut(3));

            JLabel desc = new JLabel("<html><body width='250'>" + htmlEscape(description)
                    + "</body></html>");
            desc.setFont(FlashTheme.caption());
            desc.setForeground(FlashTheme.TEXT_HELP);
            desc.setAlignmentX(Component.LEFT_ALIGNMENT);
            text.add(desc);

            add(text, BorderLayout.CENTER);
        }

        void setSelected(boolean value) {
            this.selected = value;
            setBackground(value ? FlashTheme.TILE_HOVER_BG : FlashTheme.SURFACE_RAISED);
            setBorder(borderFor(value));
            radio.setSelected(value);
            repaint();
        }

        @Override public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        private static Border borderFor(boolean selected) {
            Color line = selected ? FlashTheme.SELECTION_BORDER : FlashTheme.BORDER_STRONG;
            return BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(line, selected ? 2 : 1, true),
                    FlashTheme.pad(selected ? 9 : 10, selected ? 11 : 12,
                            selected ? 9 : 10, selected ? 11 : 12));
        }

        private static JLabel chip(String chipText, Tone tone) {
            Color bg = tone == Tone.RECOMMENDED ? FlashTheme.PRIMARY_BG : FlashTheme.SURFACE_MUTED;
            Color fg = tone == Tone.RECOMMENDED ? FlashTheme.PRIMARY_FG : FlashTheme.TEXT_MUTED;
            Color line = tone == Tone.RECOMMENDED ? FlashTheme.PRIMARY_BORDER : FlashTheme.BORDER_STRONG;
            JLabel label = new JLabel(chipText);
            label.setOpaque(true);
            label.setBackground(bg);
            label.setForeground(fg);
            label.setFont(label.getFont().deriveFont(Font.PLAIN, 10f));
            label.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(line, 1, true),
                    FlashTheme.pad(2, 7, 2, 7)));
            return label;
        }

        private static String htmlEscape(String text) {
            if (text == null) return "";
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }

    /** Small custom-painted radio indicator that mirrors its card's selected state. */
    private static final class RadioDot extends JComponent {
        private static final int SIZE = 18;
        private boolean selected;

        RadioDot() {
            Dimension d = new Dimension(SIZE, SIZE);
            setMinimumSize(d);
            setPreferredSize(d);
            setMaximumSize(d);
            setOpaque(false);
        }

        void setSelected(boolean value) {
            this.selected = value;
            repaint();
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int d = 14;
            int x = (getWidth() - d) / 2;
            int y = (getHeight() - d) / 2;
            if (selected) {
                g2.setColor(FlashTheme.SELECTION_BORDER);
                g2.fillOval(x, y, d, d);
                g2.setColor(Color.WHITE);
                g2.fillOval(x + 4, y + 4, d - 8, d - 8);
            } else {
                g2.setColor(FlashTheme.BORDER_STRONG);
                g2.setStroke(new BasicStroke(1.6f));
                g2.drawOval(x, y, d - 1, d - 1);
            }
            g2.dispose();
        }
    }
}
