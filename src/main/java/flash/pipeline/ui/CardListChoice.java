package flash.pipeline.ui;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.border.Border;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.ArrayList;
import java.util.List;

/**
 * Vertical, scrollable sibling of {@link CardChoice}: a stacked column of
 * full-width radio rows backed by a hidden {@link JComboBox} that holds the
 * canonical string value. Exactly one row is selected at a time.
 *
 * <p>Used where the option set is larger than a horizontal row of tiles can
 * hold and may change at runtime (e.g. the Excel export preset list: seven
 * stock presets plus any the user has saved). The backing combo is the single
 * source of truth, so it drops in for a {@code JComboBox} dropdown: clicking a
 * row calls {@code combo.setSelectedItem(value)} (firing the combo's
 * listeners), and an external {@code combo.setSelectedItem(...)} syncs the row
 * highlight back. {@link #setRows} rebuilds the list when the option set
 * changes.
 *
 * <p>Visual language matches {@link CardChoice}: a left radio dot, bold title,
 * caption description, optional green chip, slate-blue selection border.
 */
public final class CardListChoice extends JPanel {

    /** One row. {@code value} must equal the matching combo item string. */
    public static final class Row {
        final String value;
        final String title;
        final String description;
        final String chip;
        final boolean enabled;

        public Row(String value, String title, String description) {
            this(value, title, description, null, true);
        }

        public Row(String value, String title, String description, String chip) {
            this(value, title, description, chip, true);
        }

        public Row(String value, String title, String description, String chip, boolean enabled) {
            this.value = value;
            this.title = title;
            this.description = description;
            this.chip = chip;
            this.enabled = enabled;
        }
    }

    private static final int MAX_VIEWPORT_HEIGHT = 268;
    private static final int LIST_WIDTH = 384;
    private static final int RADIO_GUTTER = 34;
    private static final int TEXT_WIDTH = 250;

    private final JComboBox<String> combo = new JComboBox<String>();
    private final JPanel listPanel = new JPanel();
    private final JScrollPane scrollPane;
    private final List<RowCard> cards = new ArrayList<RowCard>();
    private final List<Runnable> selectionListeners = new ArrayList<Runnable>();
    private boolean syncing;

    public CardListChoice(Row[] rows, String defaultValue) {
        setLayout(new BorderLayout());
        setOpaque(false);
        setAlignmentX(Component.LEFT_ALIGNMENT);

        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setOpaque(false);

        scrollPane = new JScrollPane(listPanel);
        scrollPane.setBorder(BorderFactory.createLineBorder(FlashTheme.BORDER, 1, true));
        scrollPane.getViewport().setOpaque(false);
        scrollPane.setOpaque(false);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(scrollPane, BorderLayout.CENTER);

        // Combo is the source of truth: external setSelectedItem re-syncs rows.
        combo.addActionListener(new java.awt.event.ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                if (!syncing) {
                    syncCardsToCombo();
                    fireSelection();
                }
            }
        });

        setRows(rows, defaultValue);
    }

    /** Backing combo — the canonical value holder. */
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

    /** Runs whenever the selected row changes (row click or external combo set). */
    public void addSelectionListener(Runnable listener) {
        if (listener != null) {
            selectionListeners.add(listener);
        }
    }

    /**
     * Rebuilds the row list (and the backing combo's items) to {@code rows},
     * selecting {@code selectedValue}. Does not fire selection listeners — the
     * caller owns the resulting selection, exactly like construction.
     */
    public void setRows(Row[] rows, String selectedValue) {
        Row[] safe = rows == null ? new Row[0] : rows;
        cards.clear();
        listPanel.removeAll();

        String[] values = new String[safe.length];
        for (int i = 0; i < safe.length; i++) {
            values[i] = safe[i].value;
            RowCard card = new RowCard(safe[i]);
            wireCard(card);
            listPanel.add(card);
            cards.add(card);
        }

        String initial = selectedValue;
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

        // Repopulate the combo and set the default without firing listeners. The
        // internal `syncing` flag suppresses this control's own listener, but a
        // host may have attached directly to comboBox() (e.g. a dialog's preset
        // loader). Detach every action listener across the model rebuild so the
        // churn cannot fire them, then restore them; the caller owns the result.
        java.awt.event.ActionListener[] comboListeners = combo.getActionListeners();
        for (java.awt.event.ActionListener l : comboListeners) {
            combo.removeActionListener(l);
        }
        syncing = true;
        combo.removeAllItems();
        for (int i = 0; i < values.length; i++) {
            combo.addItem(values[i]);
        }
        combo.setSelectedItem(initial);
        syncing = false;
        for (java.awt.event.ActionListener l : comboListeners) {
            combo.addActionListener(l);
        }
        syncCardsToCombo();
        sizeViewport(safe.length);

        listPanel.revalidate();
        listPanel.repaint();
    }

    private void sizeViewport(int rowCount) {
        int rowsHeight = 0;
        for (RowCard card : cards) {
            rowsHeight += card.getPreferredSize().height;
        }
        if (rowCount == 0) {
            rowsHeight = 48;
        }
        int height = Math.min(rowsHeight + 4, MAX_VIEWPORT_HEIGHT);
        Dimension size = new Dimension(LIST_WIDTH, height);
        scrollPane.setPreferredSize(size);
        scrollPane.setMaximumSize(new Dimension(LIST_WIDTH, height));
        scrollPane.setMinimumSize(new Dimension(220, Math.min(height, 96)));
        // Bound the panel too, so the dialog's vertical BoxLayout cannot stretch
        // the scroll pane past its capped height.
        setPreferredSize(size);
        setMaximumSize(new Dimension(LIST_WIDTH, height));
    }

    private void wireCard(final RowCard card) {
        MouseListener click = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (card.row.enabled) {
                    select(card.row.value, true);
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
                if (card.row.enabled) {
                    select(card.row.value, true);
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
        for (RowCard card : cards) {
            card.setSelected(card.row.value != null && card.row.value.equals(value));
        }
    }

    private void fireSelection() {
        for (Runnable listener : selectionListeners) {
            listener.run();
        }
    }

    private static final class RowCard extends JPanel {
        final Row row;
        private final JLabel titleLabel;
        private final JLabel descriptionLabel;
        private boolean selected;
        private boolean hovered;

        RowCard(Row row) {
            this.row = row;
            setLayout(new BorderLayout(8, 0));
            setOpaque(true);
            setFocusable(row.enabled);
            setCursor(row.enabled
                    ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    : Cursor.getDefaultCursor());

            add(javax.swing.Box.createHorizontalStrut(RADIO_GUTTER), BorderLayout.WEST);

            JPanel text = new JPanel();
            text.setOpaque(false);
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));

            titleLabel = CardText.left(row.title, FlashTheme.h2(), TEXT_WIDTH);
            titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            text.add(titleLabel);

            if (row.description != null && !row.description.isEmpty()) {
                descriptionLabel = CardText.left(row.description, FlashTheme.caption(), TEXT_WIDTH);
                descriptionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
                text.add(javax.swing.Box.createVerticalStrut(2));
                text.add(descriptionLabel);
            } else {
                descriptionLabel = null;
            }
            add(text, BorderLayout.CENTER);

            if (row.chip != null && !row.chip.isEmpty()) {
                JPanel chipHolder = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
                chipHolder.setOpaque(false);
                chipHolder.add(chip(row.chip));
                add(chipHolder, BorderLayout.EAST);
            }

            getAccessibleContext().setAccessibleName(row.title);
            getAccessibleContext().setAccessibleDescription(row.description);
            updateVisuals();
        }

        @Override public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
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
            Color foreground = row.enabled ? FlashTheme.TEXT_HEADER : FlashTheme.TEXT_DISABLED;
            Color description = row.enabled ? FlashTheme.TEXT_MUTED : FlashTheme.TEXT_DISABLED;
            if (!row.enabled) {
                setBackground(FlashTheme.SURFACE_MUTED);
            } else if (selected) {
                setBackground(FlashTheme.TILE_HOVER_BG);
            } else if (hovered) {
                setBackground(FlashTheme.TILE_BG);
            } else {
                setBackground(FlashTheme.SURFACE_RAISED);
            }
            setBorder(borderFor(selected, row.enabled));
            titleLabel.setForeground(foreground);
            if (descriptionLabel != null) {
                descriptionLabel.setForeground(description);
            }
            repaint();
        }

        @Override protected void paintChildren(Graphics g) {
            super.paintChildren(g);
            ChoiceRadioIndicator.paintLeftCentered(this, g, selected, row.enabled);
        }

        private static Border borderFor(boolean selected, boolean enabled) {
            Color line = !enabled
                    ? FlashTheme.BORDER_MUTED
                    : (selected ? FlashTheme.SELECTION_BORDER : FlashTheme.BORDER_STRONG);
            return BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, FlashTheme.BORDER_MUTED),
                    BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(line, selected && enabled ? 2 : 1, true),
                            FlashTheme.pad(selected && enabled ? 9 : 10,
                                    selected && enabled ? 9 : 10,
                                    selected && enabled ? 9 : 10,
                                    selected && enabled ? 9 : 10)));
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
