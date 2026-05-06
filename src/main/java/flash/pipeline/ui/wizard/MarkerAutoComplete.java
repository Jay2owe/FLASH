package flash.pipeline.ui.wizard;

import flash.pipeline.marker.MarkerLibrary;

import javax.swing.JList;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.List;

/**
 * Lightweight autocomplete decorator: attaches a popup-menu of marker suggestions
 * to an existing {@link JTextField}. The text field continues to accept arbitrary
 * input — suggestions are advisory only.
 *
 * <p>Use {@link #attach(JTextField, MarkerLibrary)} to wire it up; pass {@code null}
 * for the library to fall back to the bundled one.
 */
public final class MarkerAutoComplete {

    /** Maximum number of suggestions shown in the popup. */
    private static final int MAX_SUGGESTIONS = 5;

    private final JTextField field;
    private final MarkerLibrary library;
    private final JPopupMenu popup;
    private final JList<MarkerLibrary.Entry> list;
    private final javax.swing.DefaultListModel<MarkerLibrary.Entry> model;
    private boolean updatingFromSuggestion;

    private MarkerAutoComplete(JTextField field, MarkerLibrary library) {
        this.field = field;
        this.library = library;
        this.model = new javax.swing.DefaultListModel<MarkerLibrary.Entry>();
        this.list = new JList<MarkerLibrary.Entry>(model);
        this.list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.list.setVisibleRowCount(MAX_SUGGESTIONS);
        this.popup = new JPopupMenu();
        this.popup.setFocusable(false);
        this.popup.add(new JScrollPane(list));

        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refresh(); }
            @Override public void removeUpdate(DocumentEvent e) { refresh(); }
            @Override public void changedUpdate(DocumentEvent e) { refresh(); }
        });
        field.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) { handleKey(e); }
        });
        // Tab is normally a focus-traversal key on JTextField, so Swing eats the
        // keyPressed event before our listener sees it. Disable that — we want
        // Tab to insert the highlighted suggestion.
        field.setFocusTraversalKeysEnabled(false);
        field.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { hide(); }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 1) {
                    pickSelected();
                }
            }
        });
    }

    public static MarkerAutoComplete attach(JTextField field, MarkerLibrary library) {
        if (field == null) {
            throw new IllegalArgumentException("field");
        }
        MarkerLibrary effective = library;
        if (effective == null) {
            try {
                effective = MarkerLibrary.loadBundled();
            } catch (IOException e) {
                effective = null;
            }
        }
        if (effective == null) {
            return null;
        }
        return new MarkerAutoComplete(field, effective);
    }

    /** For tests: the entries currently shown in the popup. */
    public List<MarkerLibrary.Entry> currentSuggestions() {
        java.util.List<MarkerLibrary.Entry> out = new java.util.ArrayList<MarkerLibrary.Entry>();
        for (int i = 0; i < model.size(); i++) {
            out.add(model.getElementAt(i));
        }
        return out;
    }

    /** For tests: whether the popup is currently visible. */
    public boolean isPopupVisible() {
        return popup.isVisible();
    }

    private void refresh() {
        if (updatingFromSuggestion) {
            return;
        }
        String query = field.getText();
        List<MarkerLibrary.Entry> suggestions = library.search(query, MAX_SUGGESTIONS);
        model.clear();
        for (MarkerLibrary.Entry entry : suggestions) {
            model.addElement(entry);
        }
        if (!suggestions.isEmpty()) {
            list.setSelectedIndex(0);
        }
        if (suggestions.isEmpty() || !field.isShowing()) {
            popup.setVisible(false);
            return;
        }
        if (!popup.isVisible()) {
            popup.show(field, 0, field.getHeight());
        } else {
            popup.pack();
        }
    }

    private void handleKey(KeyEvent e) {
        if (model.isEmpty()) {
            // No suggestion to insert. We disabled focus-traversal keys above,
            // so Tab/Shift-Tab still need to move focus manually here.
            if (e.getKeyCode() == KeyEvent.VK_TAB) {
                if (e.isShiftDown()) {
                    field.transferFocusBackward();
                } else {
                    field.transferFocus();
                }
                e.consume();
            }
            return;
        }
        int idx = list.getSelectedIndex();
        if (e.getKeyCode() == KeyEvent.VK_DOWN) {
            list.setSelectedIndex(Math.min(model.size() - 1, idx + 1));
            e.consume();
        } else if (e.getKeyCode() == KeyEvent.VK_UP) {
            list.setSelectedIndex(Math.max(0, idx - 1));
            e.consume();
        } else if (e.getKeyCode() == KeyEvent.VK_ENTER) {
            hide();
        } else if (e.getKeyCode() == KeyEvent.VK_TAB) {
            pickSelected();
            e.consume();
        } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
            hide();
            e.consume();
        }
    }

    private void pickSelected() {
        MarkerLibrary.Entry entry = list.getSelectedValue();
        if (entry == null) {
            return;
        }
        updatingFromSuggestion = true;
        try {
            field.setText(entry.getDisplayName());
        } finally {
            updatingFromSuggestion = false;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() { hide(); }
        });
    }

    private void hide() {
        popup.setVisible(false);
    }
}
