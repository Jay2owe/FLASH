package flash.pipeline.representative;

import flash.pipeline.ui.ChoiceRadioIndicator;
import flash.pipeline.ui.CardText;
import flash.pipeline.ui.FlashIcons;
import flash.pipeline.ui.FlashTheme;

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
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.ArrayList;
import java.util.List;

/**
 * Visual statistic-source chooser for the representative figure workflow.
 */
public final class RepresentativeStatisticChoicePanel extends JPanel {
    private static final int WIDTH = 520;
    private static final int CARD_WIDTH = 166;
    private static final int TEXT_WIDTH = 130;
    private static final int TILE_HEIGHT = 104;
    private static final int DETAIL_HEIGHT = 64;

    private final List<Card> cards = new ArrayList<Card>();
    private final Card existingCard;
    private final JComboBox<String> existingChoice;
    private final JPanel detailsPanel;
    private LockIcon lockIcon;
    private JLabel detailsTitle;
    private JLabel detailsCaption;
    private RepresentativeStatistic selectedStatistic;

    public RepresentativeStatisticChoicePanel(RepresentativeStatistic initialStatistic,
                                              String[] existingLabels,
                                              String defaultExistingLabel,
                                              boolean hasExistingOptions) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setAlignmentX(Component.LEFT_ALIGNMENT);
        setMaximumSize(new Dimension(WIDTH, Integer.MAX_VALUE));

        JPanel tileRow = new JPanel(new GridLayout(1, 3, 10, 0));
        tileRow.setOpaque(false);
        tileRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        Card quick = new Card(RepresentativeStatistic.QUICK,
                "Quick", "Auto score from images", true, true);
        existingCard = new Card(RepresentativeStatistic.EXISTING_RESULT,
                "Existing result",
                hasExistingOptions ? "Use a saved numeric column" : "No result columns found",
                false, hasExistingOptions);
        Card none = new Card(RepresentativeStatistic.NONE,
                "None", "Choose by eye only", false, true);

        addCard(tileRow, quick);
        addCard(tileRow, existingCard);
        addCard(tileRow, none);
        Dimension tileRowSize = CardText.rowSizeForCards(cards, WIDTH, TILE_HEIGHT);
        tileRow.setMaximumSize(tileRowSize);
        tileRow.setPreferredSize(tileRowSize);
        add(tileRow);
        add(Box.createVerticalStrut(12));

        String[] safeExistingLabels = existingLabels == null
                ? new String[0]
                : existingLabels;
        if (safeExistingLabels.length == 0 && defaultExistingLabel != null) {
            safeExistingLabels = new String[]{defaultExistingLabel};
        }
        existingChoice = new JComboBox<String>(safeExistingLabels);
        if (defaultExistingLabel != null) {
            existingChoice.setSelectedItem(defaultExistingLabel);
        }
        existingChoice.setMaximumSize(new Dimension(280, 24));
        existingChoice.setPreferredSize(new Dimension(280, 24));

        detailsPanel = buildDetailsPanel(hasExistingOptions);
        add(detailsPanel);

        RepresentativeStatistic initial = initialStatistic == null
                ? RepresentativeStatistic.QUICK
                : initialStatistic;
        if (initial == RepresentativeStatistic.EXISTING_RESULT && !hasExistingOptions) {
            initial = RepresentativeStatistic.QUICK;
        }
        select(initial);
    }

    public RepresentativeStatistic getSelectedStatistic() {
        return selectedStatistic == null ? RepresentativeStatistic.QUICK : selectedStatistic;
    }

    public String getSelectedExistingLabel() {
        Object item = existingChoice.getSelectedItem();
        return item == null ? "" : item.toString();
    }

    void selectStatisticForTests(RepresentativeStatistic statistic) {
        select(statistic);
    }

    boolean isExistingResultDetailsEnabledForTests() {
        return existingChoice.isEnabled();
    }

    boolean isExistingResultTileEnabledForTests() {
        return existingCard.isCardEnabled();
    }

    JComboBox<String> existingChoiceForTests() {
        return existingChoice;
    }

    private void addCard(JPanel tileRow, final Card card) {
        MouseListener select = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (card.isCardEnabled()) {
                    select(card.statistic);
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
        addClickRecursively(card, select);
        card.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke("SPACE"), "select");
        card.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke("ENTER"), "select");
        card.getActionMap().put("select", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (card.isCardEnabled()) {
                    select(card.statistic);
                }
            }
        });
        tileRow.add(card);
        cards.add(card);
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

    private JPanel buildDetailsPanel(boolean hasExistingOptions) {
        JPanel panel = new JPanel(new BorderLayout(12, 0));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(WIDTH, DETAIL_HEIGHT));
        panel.setPreferredSize(new Dimension(WIDTH, DETAIL_HEIGHT));

        JPanel left = new JPanel(new GridBagLayout());
        left.setOpaque(false);

        lockIcon = new LockIcon();
        GridBagConstraints lockGbc = new GridBagConstraints();
        lockGbc.gridx = 0;
        lockGbc.gridy = 0;
        lockGbc.gridheight = 2;
        lockGbc.insets = new Insets(0, 0, 0, 8);
        lockGbc.anchor = GridBagConstraints.NORTHWEST;
        left.add(lockIcon, lockGbc);

        detailsTitle = new JLabel("Existing result details");
        detailsTitle.setFont(FlashTheme.h2());
        GridBagConstraints titleGbc = new GridBagConstraints();
        titleGbc.gridx = 1;
        titleGbc.gridy = 0;
        titleGbc.anchor = GridBagConstraints.WEST;
        titleGbc.weightx = 1.0;
        left.add(detailsTitle, titleGbc);

        detailsCaption = new JLabel(hasExistingOptions
                ? "Column shown during selection"
                : "No numeric existing result column was found");
        detailsCaption.setFont(FlashTheme.caption());
        GridBagConstraints captionGbc = new GridBagConstraints();
        captionGbc.gridx = 1;
        captionGbc.gridy = 1;
        captionGbc.anchor = GridBagConstraints.WEST;
        captionGbc.weightx = 1.0;
        left.add(detailsCaption, captionGbc);

        panel.add(left, BorderLayout.WEST);

        JPanel comboWrap = new JPanel(new GridBagLayout());
        comboWrap.setOpaque(false);
        comboWrap.add(existingChoice);
        panel.add(comboWrap, BorderLayout.EAST);
        return panel;
    }

    private void select(RepresentativeStatistic statistic) {
        RepresentativeStatistic next = statistic == null
                ? RepresentativeStatistic.QUICK
                : statistic;
        if (next == RepresentativeStatistic.EXISTING_RESULT
                && !existingCard.isCardEnabled()) {
            return;
        }
        selectedStatistic = next;
        for (Card card : cards) {
            card.setSelected(card.statistic == selectedStatistic);
        }
        updateExistingResultDetails();
    }

    private void updateExistingResultDetails() {
        boolean active = selectedStatistic == RepresentativeStatistic.EXISTING_RESULT
                && existingCard.isCardEnabled();
        detailsPanel.setBackground(active ? FlashTheme.SURFACE_RAISED : FlashTheme.SURFACE_MUTED);
        detailsPanel.setBorder(detailBorder(active));
        lockIcon.setVisible(!active);
        detailsTitle.setForeground(active ? FlashTheme.TEXT_HEADER : FlashTheme.TEXT_DISABLED);
        detailsCaption.setForeground(active ? FlashTheme.TEXT_MUTED : FlashTheme.TEXT_DISABLED);
        if (active) {
            detailsCaption.setText("Column shown during selection");
        } else if (existingCard.isCardEnabled()) {
            detailsCaption.setText("Locked unless Existing result is selected");
        } else {
            detailsCaption.setText("No numeric existing result column was found");
        }
        existingChoice.setEnabled(active);
        detailsPanel.repaint();
    }

    private static Border detailBorder(boolean active) {
        Color line = active ? FlashTheme.BORDER_STRONG : FlashTheme.BORDER_MUTED;
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(line, 1, true),
                FlashTheme.pad(9, 12, 9, 12));
    }

    private static Icon iconFor(RepresentativeStatistic statistic, boolean enabled) {
        Color color = enabled ? FlashTheme.TEXT_HEADER : FlashTheme.TEXT_DISABLED;
        if (statistic == RepresentativeStatistic.QUICK) {
            return FlashIcons.chartBar(24, color);
        }
        if (statistic == RepresentativeStatistic.EXISTING_RESULT) {
            return FlashIcons.fileExport(24, color);
        }
        return FlashIcons.closeX(24, color);
    }

    private static final class Card extends JPanel {
        final RepresentativeStatistic statistic;
        private final boolean recommended;
        private final boolean cardEnabled;
        private final JLabel iconLabel = new JLabel();
        private final JLabel titleLabel;
        private final JLabel descriptionLabel;
        private boolean selected;
        private boolean hovered;

        Card(RepresentativeStatistic statistic, String title, String description,
             boolean recommended, boolean cardEnabled) {
            this.statistic = statistic;
            this.recommended = recommended;
            this.cardEnabled = cardEnabled;
            setLayout(new GridBagLayout());
            setOpaque(true);
            setFocusable(cardEnabled);
            setCursor(cardEnabled
                    ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    : Cursor.getDefaultCursor());
            setBorder(borderFor(false, cardEnabled));

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

            titleLabel = CardText.centered(title, FlashTheme.h2(), TEXT_WIDTH);
            gbc = new GridBagConstraints();
            gbc.gridx = 0;
            gbc.gridy = 2;
            gbc.weightx = 1.0;
            gbc.anchor = GridBagConstraints.CENTER;
            gbc.insets = new Insets(11, 4, 0, 4);
            add(titleLabel, gbc);

            descriptionLabel = CardText.centered(description, FlashTheme.caption(), TEXT_WIDTH);
            gbc = new GridBagConstraints();
            gbc.gridx = 0;
            gbc.gridy = 3;
            gbc.weightx = 1.0;
            gbc.anchor = GridBagConstraints.CENTER;
            gbc.insets = new Insets(3, 4, 0, 4);
            add(descriptionLabel, gbc);

            if (recommended) {
                gbc = new GridBagConstraints();
                gbc.gridx = 0;
                gbc.gridy = 4;
                gbc.weightx = 1.0;
                gbc.anchor = GridBagConstraints.CENTER;
                gbc.insets = new Insets(3, 4, 6, 4);
                add(chip("Recommended"), gbc);
            } else {
                gbc = new GridBagConstraints();
                gbc.gridx = 0;
                gbc.gridy = 4;
                gbc.weighty = 1.0;
                add(Box.createVerticalGlue(), gbc);
            }

            getAccessibleContext().setAccessibleName(title);
            getAccessibleContext().setAccessibleDescription(description);
            updateVisuals();
            CardText.fitCardToContent(this, CARD_WIDTH, 130, TILE_HEIGHT);
        }

        boolean isCardEnabled() {
            return cardEnabled;
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
            Color foreground = cardEnabled ? FlashTheme.TEXT_HEADER : FlashTheme.TEXT_DISABLED;
            Color description = cardEnabled ? FlashTheme.TEXT_MUTED : FlashTheme.TEXT_DISABLED;
            if (!cardEnabled) {
                setBackground(FlashTheme.SURFACE_MUTED);
            } else if (selected) {
                setBackground(FlashTheme.TILE_HOVER_BG);
            } else if (hovered) {
                setBackground(FlashTheme.TILE_BG);
            } else {
                setBackground(FlashTheme.SURFACE_RAISED);
            }
            setBorder(borderFor(selected, cardEnabled));
            titleLabel.setForeground(foreground);
            descriptionLabel.setForeground(description);
            iconLabel.setIcon(iconFor(statistic, cardEnabled));
            repaint();
        }

        @Override protected void paintChildren(Graphics g) {
            super.paintChildren(g);
            ChoiceRadioIndicator.paintTopLeft(this, g, selected, cardEnabled);
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

    private static final class LockIcon extends JComponent {
        private static final int WIDTH = 18;
        private static final int HEIGHT = 20;

        LockIcon() {
            Dimension d = new Dimension(WIDTH, HEIGHT);
            setMinimumSize(d);
            setPreferredSize(d);
            setMaximumSize(d);
            setOpaque(false);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(FlashTheme.TEXT_DISABLED);
            g2.setStroke(new BasicStroke(1.6f));
            g2.drawArc(4, 1, 10, 12, 180, -180);
            g2.drawRect(2, 8, 14, 10);
            g2.fillOval(8, 12, 3, 3);
            g2.dispose();
        }
    }
}
