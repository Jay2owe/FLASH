package flash.pipeline.ui.config;

import flash.pipeline.ui.sandbox.FilterCatalog;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Inline filter-add popover. Anchors a small search-as-you-type
 * picker under the {@code + Add filter...} row so the user can append a new
 * step to the linear pipeline without leaving the wizard. The default setup
 * picker remains fast-only until Recorder probing is wired in.
 */
public final class AddFilterPopover {

    public interface Selection {
        void onSelected(FilterCatalog.Entry entry);
    }

    private AddFilterPopover() {}

    public static void show(JComponent anchor, FilterCatalog catalog, Selection callback) {
        show(anchor, catalog, callback, false);
    }

    public static void show(JComponent anchor, FilterCatalog catalog,
                            Selection callback, boolean includeLegacy) {
        if (anchor == null || catalog == null || callback == null) return;
        if (GraphicsEnvironment.isHeadless()) return;

        final List<FilterCatalog.Entry> all = pickerEntries(catalog.getAllEntries(), includeLegacy);

        final JPopupMenu popup = new JPopupMenu("Add filter");
        popup.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        final DefaultListModel<FilterCatalog.Entry> listModel = new DefaultListModel<FilterCatalog.Entry>();
        for (int i = 0; i < all.size(); i++) listModel.addElement(all.get(i));
        final JList<FilterCatalog.Entry> list = new JList<FilterCatalog.Entry>(listModel);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(10);
        list.setCellRenderer(new EntryRenderer());
        if (!listModel.isEmpty()) list.setSelectedIndex(0);

        final JTextField search = new JTextField();
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refresh(); }
            @Override public void removeUpdate(DocumentEvent e) { refresh(); }
            @Override public void changedUpdate(DocumentEvent e) { refresh(); }
            private void refresh() {
                String q = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
                listModel.clear();
                for (int i = 0; i < all.size(); i++) {
                    FilterCatalog.Entry e = all.get(i);
                    if (q.isEmpty() || matches(e, q)) listModel.addElement(e);
                }
                if (!listModel.isEmpty()) list.setSelectedIndex(0);
            }
        });

        final Runnable commit = new Runnable() {
            @Override public void run() {
                FilterCatalog.Entry selected = list.getSelectedValue();
                if (selected == null) return;
                popup.setVisible(false);
                callback.onSelected(selected);
            }
        };

        search.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    commit.run();
                } else if (e.getKeyCode() == KeyEvent.VK_DOWN) {
                    int next = Math.min(list.getSelectedIndex() + 1, listModel.size() - 1);
                    if (next >= 0) list.setSelectedIndex(next);
                } else if (e.getKeyCode() == KeyEvent.VK_UP) {
                    int prev = Math.max(list.getSelectedIndex() - 1, 0);
                    if (prev >= 0) list.setSelectedIndex(prev);
                } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    popup.setVisible(false);
                }
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 2 && SwingUtilities.isLeftMouseButton(e)) {
                    commit.run();
                }
            }
        });
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "commit");
        list.getActionMap().put("commit", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { commit.run(); }
        });

        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(260, 220));

        javax.swing.JPanel content = new javax.swing.JPanel(new BorderLayout(4, 4));
        content.add(search, BorderLayout.NORTH);
        content.add(scroll, BorderLayout.CENTER);
        popup.add(content);

        popup.show(anchor, 0, anchor.getHeight());
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() { search.requestFocusInWindow(); }
        });
    }

    static boolean matches(FilterCatalog.Entry e, String q) {
        if (e == null || q == null) return false;
        if (e.label.toLowerCase(Locale.ROOT).contains(q)) return true;
        if (e.category.toLowerCase(Locale.ROOT).contains(q)) return true;
        if (e.menuPath.toLowerCase(Locale.ROOT).contains(q)) return true;
        if (e.badge().toLowerCase(Locale.ROOT).contains(q)) return true;
        return false;
    }

    static List<FilterCatalog.Entry> pickerEntries(List<FilterCatalog.Entry> in,
                                                   boolean includeLegacy) {
        List<FilterCatalog.Entry> out = new ArrayList<FilterCatalog.Entry>();
        if (in == null) return out;
        for (int i = 0; i < in.size(); i++) {
            FilterCatalog.Entry e = in.get(i);
            if (e == null || e.stub) continue;
            if (!includeLegacy && e.legacy) continue;
            out.add(e);
        }
        return out;
    }

    static String renderText(FilterCatalog.Entry e) {
        if (e == null) return "";
        String source = e.menuPath.length() > 0 ? e.menuPath : e.category;
        return e.label + " " + e.badge() + "   (" + source + ")";
    }

    private static final class EntryRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(
                    list, value, index, isSelected, cellHasFocus);
            if (value instanceof FilterCatalog.Entry) {
                label.setText(renderText((FilterCatalog.Entry) value));
            }
            return label;
        }
    }
}
