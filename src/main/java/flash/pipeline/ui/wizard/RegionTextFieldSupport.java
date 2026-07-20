package flash.pipeline.ui.wizard;

import flash.pipeline.atlas.AtlasRegion;
import flash.pipeline.atlas.AtlasRegionLibrary;
import flash.pipeline.atlas.AtlasRegionLibraryIO;
import flash.pipeline.atlas.AtlasRegionResolver;

import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Rectangle;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/** Attaches atlas-region autocomplete behavior to an existing text field. */
public final class RegionTextFieldSupport {
    private RegionTextFieldSupport() {
    }

    public static Handle install(JTextField field, AtlasRegionLibrary library) {
        return new Handle(field, library == null ? AtlasRegionLibraryIO.loadBundledQuietly() : library);
    }

    public static final class Handle {
        private final JTextField field;
        private final AtlasRegionLibrary library;
        private final DefaultListModel<AtlasRegion> suggestionModel =
                new DefaultListModel<AtlasRegion>();
        private final JList<AtlasRegion> suggestionList =
                new JList<AtlasRegion>(suggestionModel);
        private final JPopupMenu suggestionPopup = new JPopupMenu();
        private final DocumentListener documentListener;
        private final KeyAdapter keyListener;
        private final MouseAdapter mouseListener;
        private final FocusAdapter focusListener;
        private AtlasRegion selectedRegion;
        private boolean updating;

        private Handle(JTextField field, AtlasRegionLibrary library) {
            if (field == null) {
                throw new IllegalArgumentException("Region text field must not be null.");
            }
            this.field = field;
            this.library = library;
            suggestionList.setVisibleRowCount(6);
            suggestionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            suggestionPopup.setFocusable(false);
            suggestionPopup.add(new JScrollPane(suggestionList));

            documentListener = new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { refreshSuggestions(); }
                @Override public void removeUpdate(DocumentEvent e) { refreshSuggestions(); }
                @Override public void changedUpdate(DocumentEvent e) { refreshSuggestions(); }
            };
            keyListener = new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) { handleKey(e); }
            };
            mouseListener = new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { selectSuggestionAt(e); }
            };
            focusListener = new FocusAdapter() {
                @Override public void focusLost(FocusEvent e) { hideSuggestions(); }
            };
            field.getDocument().addDocumentListener(documentListener);
            field.addKeyListener(keyListener);
            field.addFocusListener(focusListener);
            suggestionList.addMouseListener(mouseListener);
            field.setFocusTraversalKeysEnabled(false);
        }

        public AtlasRegion selectedRegion() {
            return selectedRegion;
        }

        public String canonicalText() {
            if (selectedRegion != null && field.getText().trim().equals(selectedRegion.getAcronym())) {
                return selectedRegion.getAcronym();
            }
            return AtlasRegionResolver.canonicalizeIfExact(field.getText(), library);
        }

        public List<AtlasRegion> suggest(String query) {
            return library.search(query, 5);
        }

        public List<AtlasRegion> currentSuggestions() {
            List<AtlasRegion> out = new ArrayList<AtlasRegion>();
            for (int i = 0; i < suggestionModel.size(); i++) {
                out.add(suggestionModel.getElementAt(i));
            }
            return out;
        }

        public void setSelectedRegionForTest(AtlasRegion region) {
            setSelectedRegion(region);
        }

        JList<AtlasRegion> suggestionListForTest() {
            return suggestionList;
        }

        public void dispose() {
            field.getDocument().removeDocumentListener(documentListener);
            field.removeKeyListener(keyListener);
            field.removeFocusListener(focusListener);
            suggestionList.removeMouseListener(mouseListener);
            suggestionPopup.setVisible(false);
        }

        private void refreshSuggestions() {
            if (updating) return;
            if (selectedRegion != null && !field.getText().trim().equals(selectedRegion.getAcronym())) {
                selectedRegion = null;
            }
            List<AtlasRegion> suggestions = suggest(field.getText());
            suggestionModel.clear();
            for (AtlasRegion region : suggestions) {
                suggestionModel.addElement(region);
            }
            if (!suggestions.isEmpty()) {
                suggestionList.setSelectedIndex(0);
            }
            if (suggestions.isEmpty() || !field.isShowing()) {
                hideSuggestions();
                return;
            }
            if (!suggestionPopup.isVisible()) {
                suggestionPopup.show(field, 0, field.getHeight());
            } else {
                suggestionPopup.pack();
            }
        }

        private void handleKey(KeyEvent e) {
            if (suggestionModel.isEmpty()) {
                if (e.getKeyCode() == KeyEvent.VK_TAB) {
                    if (e.isShiftDown()) field.transferFocusBackward();
                    else field.transferFocus();
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
            AtlasRegion region = suggestionList.getSelectedValue();
            if (region != null) setSelectedRegion(region);
        }

        private void selectSuggestionAt(MouseEvent event) {
            int index = suggestionList.locationToIndex(event.getPoint());
            if (index < 0) return;
            Rectangle bounds = suggestionList.getCellBounds(index, index);
            if (bounds != null && !bounds.contains(event.getPoint())) return;
            suggestionList.setSelectedIndex(index);
            selectSuggestion();
            field.requestFocusInWindow();
        }

        private void setSelectedRegion(AtlasRegion region) {
            selectedRegion = region;
            updating = true;
            try {
                field.setText(region == null ? "" : region.getAcronym());
            } finally {
                updating = false;
            }
            hideSuggestions();
        }

        private void hideSuggestions() {
            suggestionPopup.setVisible(false);
        }
    }
}
