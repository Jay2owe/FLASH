package flash.pipeline.ui.wizard;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JList;
import javax.swing.JPopupMenu;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Five-match marker typeahead input.
 */
public class MarkerTypeahead extends JPanel {

    public interface SelectionListener {
        void markerSelected(MarkerLibrary.Entry marker);
    }

    private static final String PLACEHOLDER = "Start typing a marker...";
    private static final String CATEGORY_PREFIX = "__category__";
    private final MarkerLibrary library;
    private final JTextField textField;
    private final DefaultListModel<MarkerLibrary.Entry> suggestionModel;
    private final JList<MarkerLibrary.Entry> suggestionList;
    private final JPopupMenu suggestionPopup;
    private final JButton browseButton;
    private final List<SelectionListener> listeners = new ArrayList<SelectionListener>();
    private MarkerLibrary.Entry selectedMarker;
    private boolean updating;

    public MarkerTypeahead(MarkerLibrary library) {
        super(new BorderLayout(4, 2));
        this.library = library == null ? MarkerLibrary.seedLibrary() : library;
        this.textField = new PlaceholderTextField(PLACEHOLDER, 22);
        this.suggestionModel = new DefaultListModel<MarkerLibrary.Entry>();
        this.suggestionList = new JList<MarkerLibrary.Entry>(suggestionModel);
        this.browseButton = new JButton("[Browse all markers]");

        suggestionList.setVisibleRowCount(5);
        suggestionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.suggestionPopup = new JPopupMenu();
        this.suggestionPopup.setFocusable(false);
        this.suggestionPopup.add(new JScrollPane(suggestionList));

        JPanel inputRow = new JPanel(new BorderLayout(4, 0));
        inputRow.setOpaque(false);
        inputRow.add(textField, BorderLayout.CENTER);
        inputRow.add(browseButton, BorderLayout.EAST);
        add(inputRow, BorderLayout.NORTH);

        textField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refreshSuggestions(); }
            @Override public void removeUpdate(DocumentEvent e) { refreshSuggestions(); }
            @Override public void changedUpdate(DocumentEvent e) { refreshSuggestions(); }
        });
        textField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                handleKey(e);
            }
        });
        // Tab is normally a focus-traversal key on JTextField; Swing eats it
        // before our listener sees it. Disable so Tab can autofill the top match.
        textField.setFocusTraversalKeysEnabled(false);
        textField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { hideSuggestions(); }
        });
        suggestionList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 1) {
                    selectSuggestion();
                }
            }
        });
        browseButton.addActionListener(e -> openBrowseDialog(SwingUtilities.getWindowAncestor(this)));
    }

    public MarkerTypeahead(Collection<MarkerLibrary.Entry> entries) {
        this(new MarkerLibrary(entries == null
                ? Collections.<MarkerLibrary.Entry>emptyList()
                : new ArrayList<MarkerLibrary.Entry>(entries)));
    }

    public MarkerLibrary.Entry getSelectedMarker() {
        return selectedMarker;
    }

    public JTextField getTextField() {
        return textField;
    }

    public JButton getBrowseButton() {
        return browseButton;
    }

    public boolean isSuggestionListVisible() {
        return suggestionPopup.isVisible();
    }

    public List<MarkerLibrary.Entry> currentSuggestions() {
        List<MarkerLibrary.Entry> out = new ArrayList<MarkerLibrary.Entry>();
        for (int i = 0; i < suggestionModel.size(); i++) {
            out.add(suggestionModel.getElementAt(i));
        }
        return out;
    }

    public List<MarkerLibrary.Entry> suggest(String query) {
        return computeSuggestions(query, library.entries());
    }

    public List<MarkerLibrary.Entry> findMatches(String query) {
        return suggest(query);
    }

    public void addSelectionListener(SelectionListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void setSelectedMarker(MarkerLibrary.Entry marker) {
        selectedMarker = marker;
        updating = true;
        try {
            textField.setText(marker == null ? "" : marker.getDisplayName());
        } finally {
            updating = false;
        }
        hideSuggestions();
        for (SelectionListener listener : listeners) {
            listener.markerSelected(marker);
        }
    }

    public void selectForTest(MarkerLibrary.Entry marker) {
        setSelectedMarker(marker);
    }

    public boolean openBrowseDialog(Component parent) {
        if (GraphicsEnvironment.isHeadless()) {
            return false;
        }
        final JDialog dialog = new JDialog(parent instanceof Frame ? (Frame) parent : null,
                "Browse all markers", true);
        final JList<MarkerLibrary.Entry> allMarkers = new JList<MarkerLibrary.Entry>(groupedListModel());
        allMarkers.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JButton select = new JButton("Select");
        select.addActionListener(e -> {
            MarkerLibrary.Entry entry = allMarkers.getSelectedValue();
            if (entry != null && !isCategoryHeader(entry)) {
                setSelectedMarker(entry);
                dialog.dispose();
            }
        });
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        footer.add(select);
        dialog.getContentPane().setLayout(new BorderLayout());
        dialog.getContentPane().add(new JScrollPane(allMarkers), BorderLayout.CENTER);
        dialog.getContentPane().add(footer, BorderLayout.SOUTH);
        dialog.setSize(360, 420);
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
        return true;
    }

    public Map<String, List<MarkerLibrary.Entry>> groupedMarkers() {
        // An entry shows up under every category it belongs to (primary + additional),
        // so a researcher browsing "lysosomes" sees CD68 even though its primary
        // category is "microglia".
        Map<String, List<MarkerLibrary.Entry>> grouped = new LinkedHashMap<String, List<MarkerLibrary.Entry>>();
        for (MarkerLibrary.Entry entry : library.entries()) {
            for (String category : entry.getAllCategories()) {
                List<MarkerLibrary.Entry> entries = grouped.get(category);
                if (entries == null) {
                    entries = new ArrayList<MarkerLibrary.Entry>();
                    grouped.put(category, entries);
                }
                entries.add(entry);
            }
            if (entry.getAllCategories().isEmpty()) {
                List<MarkerLibrary.Entry> entries = grouped.get("");
                if (entries == null) {
                    entries = new ArrayList<MarkerLibrary.Entry>();
                    grouped.put("", entries);
                }
                entries.add(entry);
            }
        }
        return grouped;
    }

    private DefaultListModel<MarkerLibrary.Entry> groupedListModel() {
        DefaultListModel<MarkerLibrary.Entry> model = new DefaultListModel<MarkerLibrary.Entry>();
        Map<String, List<MarkerLibrary.Entry>> grouped = groupedMarkers();
        for (Map.Entry<String, List<MarkerLibrary.Entry>> group : grouped.entrySet()) {
            model.addElement(categoryHeader(group.getKey()));
            for (MarkerLibrary.Entry entry : group.getValue()) {
                model.addElement(entry);
            }
        }
        return model;
    }

    private void refreshSuggestions() {
        if (updating) {
            return;
        }
        if (selectedMarker != null && !textField.getText().equals(selectedMarker.getDisplayName())) {
            selectedMarker = null;
        }
        List<MarkerLibrary.Entry> suggestions = computeSuggestions(textField.getText(), library.entries());
        suggestionModel.clear();
        for (MarkerLibrary.Entry entry : suggestions) {
            suggestionModel.addElement(entry);
        }
        if (!suggestions.isEmpty()) {
            suggestionList.setSelectedIndex(0);
        }
        if (suggestions.isEmpty() || !textField.isShowing()) {
            hideSuggestions();
            return;
        }
        if (!suggestionPopup.isVisible()) {
            suggestionPopup.show(textField, 0, textField.getHeight());
        } else {
            suggestionPopup.pack();
        }
    }

    private void handleKey(KeyEvent e) {
        if (suggestionModel.isEmpty()) {
            // Focus traversal is disabled on this field, so Tab/Shift-Tab still
            // need to move focus manually when there's no suggestion to insert.
            if (e.getKeyCode() == KeyEvent.VK_TAB) {
                if (e.isShiftDown()) {
                    textField.transferFocusBackward();
                } else {
                    textField.transferFocus();
                }
                e.consume();
            }
            return;
        }
        int selected = suggestionList.getSelectedIndex();
        if (e.getKeyCode() == KeyEvent.VK_DOWN) {
            suggestionList.setSelectedIndex(Math.min(suggestionModel.size() - 1, selected + 1));
            e.consume();
        } else if (e.getKeyCode() == KeyEvent.VK_UP) {
            suggestionList.setSelectedIndex(Math.max(0, selected - 1));
            e.consume();
        } else if (e.getKeyCode() == KeyEvent.VK_ENTER) {
            hideSuggestions();
        } else if (e.getKeyCode() == KeyEvent.VK_TAB) {
            selectSuggestion();
            e.consume();
        }
    }

    private void selectSuggestion() {
        MarkerLibrary.Entry entry = suggestionList.getSelectedValue();
        if (entry != null) {
            setSelectedMarker(entry);
        }
    }

    private void hideSuggestions() {
        suggestionPopup.setVisible(false);
    }

    private static List<MarkerLibrary.Entry> computeSuggestions(String query, List<MarkerLibrary.Entry> entries) {
        String normalized = normalize(query);
        if (normalized.length() == 0) {
            return Collections.emptyList();
        }
        List<ScoredEntry> scored = new ArrayList<ScoredEntry>();
        for (int i = 0; i < entries.size(); i++) {
            MarkerLibrary.Entry entry = entries.get(i);
            if (isSentinel(entry)) {
                continue;
            }
            int score = score(entry, normalized);
            if (score < MarkerSearchRanking.NO_MATCH) {
                scored.add(new ScoredEntry(entry, score, i));
            }
        }
        Collections.sort(scored, new Comparator<ScoredEntry>() {
            @Override
            public int compare(ScoredEntry left, ScoredEntry right) {
                if (left.score != right.score) {
                    return left.score - right.score;
                }
                return left.index - right.index;
            }
        });
        List<MarkerLibrary.Entry> out = new ArrayList<MarkerLibrary.Entry>();
        for (ScoredEntry entry : scored) {
            if (out.size() >= 3) {
                break;
            }
            out.add(entry.entry);
        }
        addSentinel(out, MarkerLibrary.AUTOFLUORESCENCE);
        return out;
    }

    private static void addSentinel(List<MarkerLibrary.Entry> out, MarkerLibrary.Entry sentinel) {
        if (out.contains(sentinel)) {
            return;
        }
        out.add(sentinel);
    }

    private static int score(MarkerLibrary.Entry entry, String query) {
        return MarkerSearchRanking.rank(entry.getId(), entry.getDisplayName(),
                entry.getAliases(), entry.getNameHints(), query);
    }

    private static boolean isSentinel(MarkerLibrary.Entry entry) {
        return MarkerLibrary.AUTOFLUORESCENCE.equals(entry) || MarkerLibrary.OTHER_CUSTOM.equals(entry);
    }

    private static MarkerLibrary.Entry categoryHeader(String category) {
        String safe = category == null || category.trim().isEmpty() ? "uncategorized" : category.trim();
        return new MarkerLibrary.Entry(CATEGORY_PREFIX + safe, safe, Collections.<String>emptyList(),
                Collections.<String>emptyList(), safe, "", false, false);
    }

    private static boolean isCategoryHeader(MarkerLibrary.Entry entry) {
        return entry != null && entry.getId().startsWith(CATEGORY_PREFIX);
    }

    private static String normalize(String value) {
        return MarkerSearchRanking.normalize(value);
    }

    private static final class ScoredEntry {
        final MarkerLibrary.Entry entry;
        final int score;
        final int index;

        ScoredEntry(MarkerLibrary.Entry entry, int score, int index) {
            this.entry = entry;
            this.score = score;
            this.index = index;
        }
    }

    private static final class PlaceholderTextField extends JTextField {
        private final String placeholder;

        PlaceholderTextField(String placeholder, int columns) {
            super(columns);
            this.placeholder = placeholder;
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            super.paintComponent(g);
            if (getText().length() == 0 && !isFocusOwner()) {
                g.setColor(new java.awt.Color(130, 130, 130));
                g.drawString(placeholder, 6, getHeight() / 2 + g.getFontMetrics().getAscent() / 2 - 2);
            }
        }
    }
}
