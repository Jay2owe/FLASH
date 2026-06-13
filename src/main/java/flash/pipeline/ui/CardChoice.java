package flash.pipeline.ui;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.border.Border;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.ArrayList;
import java.util.List;

/**
 * Generic radio-card chooser: a horizontal row of selectable cards backed by a
 * hidden {@link JComboBox} that holds the canonical string value. Exactly one
 * card is selected at a time.
 *
 * <p>The backing combo is the single source of truth, so this drops in for a
 * {@code PipelineDialog.addChoice(...)}: clicking a card calls
 * {@code combo.setSelectedItem(value)} (firing the combo's listeners), and an
 * external {@code combo.setSelectedItem(...)} syncs the card highlight back.
 * {@code PipelineDialog.getNextChoice()} reads the combo unchanged.
 *
 * <p>Visual language matches {@code RepresentativeStatisticChoicePanel}: icon +
 * bold title + caption + optional green chip, slate-blue selection border, and a
 * filled radio dot.
 */
public final class CardChoice extends JPanel {

    /** One card. {@code value} must equal the matching combo item string. */
    public static final class Option {
        final String value;
        final String title;
        final String description;
        final String iconName;
        final String chip;
        final boolean enabled;

        public Option(String value, String title, String description, String iconName) {
            this(value, title, description, iconName, null, true);
        }

        public Option(String value, String title, String description, String iconName, String chip) {
            this(value, title, description, iconName, chip, true);
        }

        public Option(String value, String title, String description, String iconName,
                      String chip, boolean enabled) {
            this.value = value;
            this.title = title;
            this.description = description;
            this.iconName = iconName;
            this.chip = chip;
            this.enabled = enabled;
        }
    }

    private static final int CARD_WIDTH = 166;
    private static final int TEXT_WIDTH = 130;
    private static final int TILE_HEIGHT = 104;

    private final JComboBox<String> combo;
    private final List<Card> cards = new ArrayList<Card>();
    private final List<Runnable> selectionListeners = new ArrayList<Runnable>();
    private boolean syncing;

    public CardChoice(Option[] options, String defaultValue) {
        Option[] safe = options == null ? new Option[0] : options;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setAlignmentX(Component.LEFT_ALIGNMENT);

        String[] values = new String[safe.length];
        for (int i = 0; i < safe.length; i++) {
            values[i] = safe[i].value;
        }
        combo = new JComboBox<String>(values);

        int columns = Math.max(1, safe.length);
        JPanel row = new JPanel(new GridLayout(1, columns, 10, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        int width = columns * CARD_WIDTH + (columns - 1) * 10;

        for (int i = 0; i < safe.length; i++) {
            Card card = new Card(safe[i]);
            wireCard(card);
            row.add(card);
            cards.add(card);
        }
        Dimension rowSize = CardText.rowSizeForCards(cards, width, TILE_HEIGHT);
        row.setMaximumSize(rowSize);
        row.setPreferredSize(rowSize);
        add(row);

        // Combo is the source of truth: external setSelectedItem re-syncs cards.
        combo.addActionListener(new java.awt.event.ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                if (!syncing) {
                    syncCardsToCombo();
                    fireSelection();
                }
            }
        });

        String initial = defaultValue;
        boolean valid = false;
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null && values[i].equals(initial)) {
                valid = true;
                break;
            }
        }
        if (!valid && values.length > 0) {
            initial = values[0];
        }
        // Push the default into the combo (the source of truth) without firing
        // selection listeners during construction, then mirror it on the cards.
        syncing = true;
        combo.setSelectedItem(initial);
        syncing = false;
        syncCardsToCombo();
    }

    /** Backing combo — register this in PipelineDialog's choice list. */
    public JComboBox<String> comboBox() {
        return combo;
    }

    public String getSelectedValue() {
        Object item = combo.getSelectedItem();
        return item == null ? "" : item.toString();
    }

    public void setSelectedValue(String value) {
        select(value, true);
    }

    /** Runs whenever the selected card changes (card click or external combo set). */
    public void addSelectionListener(Runnable listener) {
        if (listener != null) {
            selectionListeners.add(listener);
        }
    }

    private void wireCard(final Card card) {
        MouseListener click = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (card.option.enabled) {
                    select(card.option.value, true);
                    card.requestFocusInWindow();
                }
            }

            @Override public void mouseEntered(MouseEvent e) {
                card.setHovered(true);
            }

            @Override public void mouseExited(MouseEvent e) {
                card.setHovered(false);
            }
        };
        addClickRecursively(card, click);
        card.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("SPACE"), "select");
        card.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "select");
        card.getActionMap().put("select", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (card.option.enabled) {
                    select(card.option.value, true);
                }
            }
        });
    }

    private static void addClickRecursively(Component component, MouseListener listener) {
        component.addMouseListener(listener);
        if (component instanceof Container) {
            Component[] children = ((Container) component).getComponents();
            for (int i = 0; i < children.length; i++) {
                addClickRecursively(children[i], listener);
            }
        }
    }

    private void select(String value, boolean updateCombo) {
        if (value == null) {
            return;
        }
        // Match the backing combo's semantics: selecting the already-selected
        // value is a no-op (no listener fire). This keeps card clicks and
        // external combo.setSelectedItem in lockstep.
        if (value.equals(getSelectedValue())) {
            syncCardsToCombo();
            return;
        }
        if (updateCombo) {
            syncing = true;
            combo.setSelectedItem(value);
            syncing = false;
        }
        syncCardsToCombo();
        if (updateCombo) {
            fireSelection();
        }
    }

    private void syncCardsToCombo() {
        Object item = combo.getSelectedItem();
        String value = item == null ? null : item.toString();
        for (Card card : cards) {
            card.setSelected(card.option.value != null && card.option.value.equals(value));
        }
    }

    private void fireSelection() {
        for (Runnable listener : selectionListeners) {
            listener.run();
        }
    }

    private static Icon iconFor(Option option) {
        Color color = option.enabled ? FlashTheme.TEXT_HEADER : FlashTheme.TEXT_DISABLED;
        Icon icon = FlashIcons.named(option.iconName, 24, color);
        return icon;
    }

    private static final class Card extends JPanel {
        final Option option;
        private final JLabel iconLabel = new JLabel();
        private final JLabel titleLabel;
        private final JLabel descriptionLabel;
        private boolean selected;
        private boolean hovered;

        Card(Option option) {
            this.option = option;
            setLayout(new GridBagLayout());
            setOpaque(true);
            setFocusable(option.enabled);
            setCursor(option.enabled
                    ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    : Cursor.getDefaultCursor());

            GridBagConstraints gbc = new GridBagConstraints();
            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.weightx = 1.0;
            gbc.anchor = GridBagConstraints.CENTER;
            gbc.insets = new Insets(8, 0, 0, 0);
            add(Box.createRigidArea(ChoiceRadioIndicator.reservedSize()), gbc);

            iconLabel.setHorizontalAlignment(JLabel.CENTER);
            gbc = new GridBagConstraints();
            gbc.gridx = 0;
            gbc.gridy = 1;
            gbc.weightx = 1.0;
            gbc.anchor = GridBagConstraints.CENTER;
            gbc.insets = new Insets(2, 0, 0, 0);
            add(iconLabel, gbc);

            titleLabel = CardText.centered(option.title, FlashTheme.h2(), TEXT_WIDTH);
            gbc = new GridBagConstraints();
            gbc.gridx = 0;
            gbc.gridy = 2;
            gbc.weightx = 1.0;
            gbc.anchor = GridBagConstraints.CENTER;
            gbc.insets = new Insets(10, 4, 0, 4);
            add(titleLabel, gbc);

            descriptionLabel = CardText.centered(option.description, FlashTheme.caption(), TEXT_WIDTH);
            gbc = new GridBagConstraints();
            gbc.gridx = 0;
            gbc.gridy = 3;
            gbc.weightx = 1.0;
            gbc.anchor = GridBagConstraints.CENTER;
            gbc.insets = new Insets(3, 4, 0, 4);
            add(descriptionLabel, gbc);

            if (option.chip != null && !option.chip.isEmpty()) {
                gbc = new GridBagConstraints();
                gbc.gridx = 0;
                gbc.gridy = 4;
                gbc.weightx = 1.0;
                gbc.anchor = GridBagConstraints.CENTER;
                gbc.insets = new Insets(3, 4, 6, 4);
                add(chip(option.chip), gbc);
            } else {
                gbc = new GridBagConstraints();
                gbc.gridx = 0;
                gbc.gridy = 4;
                gbc.weighty = 1.0;
                add(Box.createVerticalGlue(), gbc);
            }

            getAccessibleContext().setAccessibleName(option.title);
            getAccessibleContext().setAccessibleDescription(option.description);
            updateVisuals();
            CardText.fitCardToContent(this, CARD_WIDTH, 120, TILE_HEIGHT);
        }

        void setSelected(boolean selected) {
            this.selected = selected;
            updateVisuals();
        }

        void setHovered(boolean hovered) {
            this.hovered = hovered;
            updateVisuals();
        }

        private void updateVisuals() {
            Color foreground = option.enabled ? FlashTheme.TEXT_HEADER : FlashTheme.TEXT_DISABLED;
            Color description = option.enabled ? FlashTheme.TEXT_MUTED : FlashTheme.TEXT_DISABLED;
            if (!option.enabled) {
                setBackground(FlashTheme.SURFACE_MUTED);
            } else if (selected) {
                setBackground(FlashTheme.TILE_HOVER_BG);
            } else if (hovered) {
                setBackground(FlashTheme.TILE_BG);
            } else {
                setBackground(FlashTheme.SURFACE_RAISED);
            }
            setBorder(borderFor(selected, option.enabled));
            titleLabel.setForeground(foreground);
            descriptionLabel.setForeground(description);
            iconLabel.setIcon(iconFor(option));
            repaint();
        }

        @Override protected void paintChildren(Graphics g) {
            super.paintChildren(g);
            ChoiceRadioIndicator.paintTopLeft(this, g, selected, option.enabled);
        }

        private static Border borderFor(boolean selected, boolean enabled) {
            Color line = !enabled
                    ? FlashTheme.BORDER_MUTED
                    : (selected ? FlashTheme.SELECTION_BORDER : FlashTheme.BORDER_STRONG);
            return BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(line, selected && enabled ? 2 : 1, true),
                    FlashTheme.pad(selected && enabled ? 8 : 9,
                            selected && enabled ? 9 : 10,
                            selected && enabled ? 8 : 9,
                            selected && enabled ? 9 : 10));
        }

        private static JLabel chip(String text) {
            JLabel label = new JLabel(text);
            label.setOpaque(true);
            label.setBackground(FlashTheme.PRIMARY_BG);
            label.setForeground(FlashTheme.PRIMARY_FG);
            label.setFont(label.getFont().deriveFont(9f));
            label.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(FlashTheme.PRIMARY_BORDER, 1, true),
                    FlashTheme.pad(1, 7, 1, 7)));
            return label;
        }
    }
}
