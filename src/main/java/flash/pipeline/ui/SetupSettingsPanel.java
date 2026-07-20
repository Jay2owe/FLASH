package flash.pipeline.ui;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
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
import java.awt.Font;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Screen 2 of Set Up Configuration: a grid of multi-select setting tiles, shared
 * by the "Redo some settings" and "Start from scratch" cards. Tick the settings
 * to (re)configure; each tile shows how much saved data it already has (Saved /
 * Partly done / Not set). The visual language matches {@code CardChoice}.
 *
 * <p>This panel never writes anything — it just collects the user's selection.
 */
public final class SetupSettingsPanel extends JPanel {

    /** How much saved data a setting already has. */
    public enum Status { NONE, PARTIAL, FULL }

    /** One selectable setting tile. {@code value} is the caller's key. */
    public static final class SettingTile {
        final String value;
        final String title;
        final String iconName;
        final Status status;

        public SettingTile(String value, String title, String iconName, Status status) {
            this.value = value;
            this.title = title;
            this.iconName = iconName;
            this.status = status == null ? Status.NONE : status;
        }
    }

    private static final int WIDTH = 520;
    private static final int TILE_WIDTH = 166;
    private static final int TILE_HEIGHT = 116;
    private static final int GRID_COLUMNS = 3;

    private final List<Tile> tiles = new ArrayList<Tile>();
    private final List<Runnable> selectionListeners = new ArrayList<Runnable>();

    public SetupSettingsPanel(SettingTile[] settingTiles, boolean preselectAll) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setAlignmentX(Component.LEFT_ALIGNMENT);
        setMaximumSize(new Dimension(WIDTH, Integer.MAX_VALUE));

        SettingTile[] safe = settingTiles == null ? new SettingTile[0] : settingTiles;
        int rows = Math.max(1, (safe.length + GRID_COLUMNS - 1) / GRID_COLUMNS);
        JPanel grid = new JPanel(new GridLayout(rows, GRID_COLUMNS, 10, 10));
        grid.setOpaque(false);
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        int gridHeight = rows * TILE_HEIGHT + (rows - 1) * 10;
        grid.setMaximumSize(new Dimension(WIDTH, gridHeight));
        grid.setPreferredSize(new Dimension(WIDTH, gridHeight));
        for (int i = 0; i < safe.length; i++) {
            Tile tile = new Tile(safe[i]);
            tile.setChecked(preselectAll);
            wireTile(tile);
            grid.add(tile);
            tiles.add(tile);
        }
        add(grid);
    }

    /** Tile values currently ticked. */
    public Set<String> getSelectedSettings() {
        Set<String> out = new LinkedHashSet<String>();
        for (Tile tile : tiles) {
            if (tile.checked) {
                out.add(tile.setting.value);
            }
        }
        return out;
    }

    public boolean isSettingSelected(String value) {
        return getSelectedSettings().contains(value);
    }

    public boolean hasSelection() {
        for (Tile tile : tiles) {
            if (tile.checked) {
                return true;
            }
        }
        return false;
    }

    /** Runs whenever any tile is ticked or unticked. */
    public void addSelectionListener(Runnable listener) {
        if (listener != null) {
            selectionListeners.add(listener);
        }
    }

    // ── Test hooks ──────────────────────────────────────────────────────
    void toggleTileForTests(String value) {
        for (Tile tile : tiles) {
            if (tile.setting.value.equals(value)) {
                toggleTile(tile);
                return;
            }
        }
    }

    // ── Internals ───────────────────────────────────────────────────────
    private void wireTile(final Tile tile) {
        MouseListener click = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                toggleTile(tile);
                tile.requestFocusInWindow();
            }

            @Override public void mouseEntered(MouseEvent e) {
                tile.setHovered(true);
            }

            @Override public void mouseExited(MouseEvent e) {
                tile.setHovered(false);
            }
        };
        addClickRecursively(tile, click);
        tile.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("SPACE"), "toggle");
        tile.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "toggle");
        tile.getActionMap().put("toggle", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                toggleTile(tile);
            }
        });
    }

    private void toggleTile(Tile tile) {
        tile.setChecked(!tile.checked);
        for (Runnable listener : selectionListeners) {
            listener.run();
        }
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

    private static String htmlEscape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** A single multi-select setting tile. */
    private static final class Tile extends JPanel {
        final SettingTile setting;
        private final JLabel iconLabel = new JLabel();
        private final JLabel titleLabel;
        private boolean checked;
        private boolean hovered;

        Tile(SettingTile setting) {
            this.setting = setting;
            setLayout(new GridBagLayout());
            setOpaque(true);
            setFocusable(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(TILE_WIDTH, TILE_HEIGHT));
            setMinimumSize(new Dimension(120, TILE_HEIGHT));

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

            titleLabel = new JLabel("<html><body width='130' style='text-align:center'>"
                    + htmlEscape(setting.title) + "</body></html>");
            titleLabel.setFont(FlashTheme.h2());
            titleLabel.setHorizontalAlignment(JLabel.CENTER);
            gbc = new GridBagConstraints();
            gbc.gridx = 0;
            gbc.gridy = 2;
            gbc.weightx = 1.0;
            gbc.anchor = GridBagConstraints.CENTER;
            gbc.insets = new Insets(8, 4, 0, 4);
            add(titleLabel, gbc);

            gbc = new GridBagConstraints();
            gbc.gridx = 0;
            gbc.gridy = 3;
            gbc.weightx = 1.0;
            gbc.weighty = 1.0;
            gbc.anchor = GridBagConstraints.CENTER;
            gbc.insets = new Insets(6, 4, 8, 4);
            add(statusChip(setting.status), gbc);

            getAccessibleContext().setAccessibleName(setting.title);
            updateVisuals();
        }

        void setChecked(boolean value) {
            this.checked = value;
            updateVisuals();
        }

        void setHovered(boolean value) {
            this.hovered = value;
            updateVisuals();
        }

        private void updateVisuals() {
            if (checked) {
                setBackground(FlashTheme.TILE_HOVER_BG);
            } else if (hovered) {
                setBackground(FlashTheme.TILE_BG);
            } else {
                setBackground(FlashTheme.SURFACE_RAISED);
            }
            setBorder(borderFor(checked));
            titleLabel.setForeground(FlashTheme.TEXT_HEADER);
            iconLabel.setIcon(FlashIcons.named(setting.iconName, 24, FlashTheme.TEXT_HEADER));
            repaint();
        }

        @Override protected void paintChildren(Graphics g) {
            super.paintChildren(g);
            ChoiceRadioIndicator.paintCheckTopLeft(this, g, checked, true);
        }

        private static Border borderFor(boolean checked) {
            Color line = checked ? FlashTheme.SELECTION_BORDER : FlashTheme.BORDER_STRONG;
            return BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(line, checked ? 2 : 1, true),
                    FlashTheme.pad(checked ? 8 : 9, checked ? 9 : 10,
                            checked ? 8 : 9, checked ? 9 : 10));
        }

        private static JLabel statusChip(Status status) {
            String text;
            Color bg;
            Color fg;
            Color line;
            if (status == Status.FULL) {
                text = "Saved";
                bg = FlashTheme.PRIMARY_BG;
                fg = FlashTheme.PRIMARY_FG;
                line = FlashTheme.PRIMARY_BORDER;
            } else if (status == Status.PARTIAL) {
                text = "Partly done";
                bg = FlashTheme.WARNING_BG;
                fg = FlashTheme.WARNING_FG;
                line = FlashTheme.WARNING_BORDER;
            } else {
                text = "Not set";
                bg = FlashTheme.SURFACE_MUTED;
                fg = FlashTheme.TEXT_MUTED;
                line = FlashTheme.BORDER_STRONG;
            }
            JLabel label = new JLabel(text);
            label.setOpaque(true);
            label.setBackground(bg);
            label.setForeground(fg);
            label.setFont(label.getFont().deriveFont(Font.PLAIN, 9f));
            label.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(line, 1, true),
                    FlashTheme.pad(1, 7, 1, 7)));
            return label;
        }
    }
}
