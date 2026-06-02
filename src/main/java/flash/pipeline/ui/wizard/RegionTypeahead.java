package flash.pipeline.ui.wizard;

import flash.pipeline.atlas.AtlasRegion;
import flash.pipeline.atlas.AtlasRegionLibrary;
import flash.pipeline.atlas.AtlasRegionLibraryIO;

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
import java.util.List;

/** Atlas-backed typeahead for brain-region acronyms and names. */
public final class RegionTypeahead extends JPanel {
    public interface SelectionListener {
        void regionSelected(AtlasRegion region);
    }

    private static final String PLACEHOLDER = "Start typing a brain region...";

    private final AtlasRegionLibrary library;
    private final JTextField textField;
    private final DefaultListModel<AtlasRegion> suggestionModel;
    private final JList<AtlasRegion> suggestionList;
    private final JPopupMenu suggestionPopup;
    private final JButton browseButton;
    private final List<SelectionListener> listeners = new ArrayList<SelectionListener>();
    private AtlasRegion selectedRegion;
    private boolean updating;

    public RegionTypeahead(AtlasRegionLibrary library) {
        super(new BorderLayout(4, 2));
        this.library = library == null ? AtlasRegionLibraryIO.loadBundledQuietly() : library;
        this.textField = new PlaceholderTextField(PLACEHOLDER, 22);
        this.suggestionModel = new DefaultListModel<AtlasRegion>();
        this.suggestionList = new JList<AtlasRegion>(suggestionModel);
        this.suggestionPopup = new JPopupMenu();
        this.browseButton = new JButton("[Browse all regions]");

        suggestionList.setVisibleRowCount(6);
        suggestionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        suggestionPopup.setFocusable(false);
        suggestionPopup.add(new JScrollPane(suggestionList));

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
            @Override public void keyPressed(KeyEvent e) { handleKey(e); }
        });
        textField.setFocusTraversalKeysEnabled(false);
        textField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { hideSuggestions(); }
        });
        suggestionList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 1) selectSuggestion();
            }
        });
        browseButton.addActionListener(e -> openBrowseDialog(SwingUtilities.getWindowAncestor(this)));
    }

    public AtlasRegion getSelectedRegion() {
        return selectedRegion;
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

    public List<AtlasRegion> currentSuggestions() {
        List<AtlasRegion> out = new ArrayList<AtlasRegion>();
        for (int i = 0; i < suggestionModel.size(); i++) {
            out.add(suggestionModel.getElementAt(i));
        }
        return out;
    }

    public List<AtlasRegion> suggest(String query) {
        return library.search(query, 5);
    }

    public void addSelectionListener(SelectionListener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void setSelectedRegion(AtlasRegion region) {
        selectedRegion = region;
        updating = true;
        try {
            textField.setText(region == null ? "" : region.getAcronym());
        } finally {
            updating = false;
        }
        hideSuggestions();
        for (SelectionListener listener : listeners) {
            listener.regionSelected(region);
        }
    }

    public boolean openBrowseDialog(Component parent) {
        if (GraphicsEnvironment.isHeadless()) return false;
        final JDialog dialog = new JDialog(parent instanceof Frame ? (Frame) parent : null,
                "Browse all regions", true);
        final JList<AtlasRegion> allRegions =
                new JList<AtlasRegion>(library.regions().toArray(new AtlasRegion[0]));
        allRegions.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JButton select = new JButton("Select");
        select.addActionListener(e -> {
            AtlasRegion region = allRegions.getSelectedValue();
            if (region != null) {
                setSelectedRegion(region);
                dialog.dispose();
            }
        });
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        footer.add(select);
        dialog.getContentPane().setLayout(new BorderLayout());
        dialog.getContentPane().add(new JScrollPane(allRegions), BorderLayout.CENTER);
        dialog.getContentPane().add(footer, BorderLayout.SOUTH);
        dialog.setSize(420, 460);
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
        return true;
    }

    private void refreshSuggestions() {
        if (updating) return;
        if (selectedRegion != null && !textField.getText().equals(selectedRegion.getAcronym())) {
            selectedRegion = null;
        }
        List<AtlasRegion> suggestions = suggest(textField.getText());
        suggestionModel.clear();
        for (AtlasRegion region : suggestions) {
            suggestionModel.addElement(region);
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
            if (e.getKeyCode() == KeyEvent.VK_TAB) {
                if (e.isShiftDown()) textField.transferFocusBackward();
                else textField.transferFocus();
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

    private void hideSuggestions() {
        suggestionPopup.setVisible(false);
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
