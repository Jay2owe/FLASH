package flash.pipeline.ui.wizard;

import flash.pipeline.marker.MarkerLibrary;
import org.junit.Test;

import javax.swing.JFrame;
import javax.swing.JTextField;
import java.awt.GraphicsEnvironment;
import java.awt.event.KeyEvent;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

public class MarkerAutoCompleteTest {

    @Test
    public void suggestionsRankIba1FirstForIbaQuery() throws Exception {
        assumeFalse("Swing components require a display", GraphicsEnvironment.isHeadless());
        MarkerLibrary library = MarkerLibrary.loadBundled();
        JTextField field = new JTextField();

        MarkerAutoComplete autocomplete = MarkerAutoComplete.attach(field, library);
        assertNotNull(autocomplete);

        field.setText("iba");
        List<MarkerLibrary.Entry> suggestions = autocomplete.currentSuggestions();
        assertTrue("expected at least one suggestion for 'iba'", !suggestions.isEmpty());
        assertEquals("IBA1", suggestions.get(0).getDisplayName());
    }

    @Test
    public void freeFormTextIsNeverOverwritten() throws Exception {
        assumeFalse("Swing components require a display", GraphicsEnvironment.isHeadless());
        MarkerLibrary library = MarkerLibrary.loadBundled();
        JTextField field = new JTextField();
        MarkerAutoComplete.attach(field, library);

        field.setText("MyTotallyNovelMarkerName");

        // The text the user typed must survive verbatim — fuzzy match never
        // rewrites the field; it only offers suggestions.
        assertEquals("MyTotallyNovelMarkerName", field.getText());
    }

    @Test
    public void emptyQueryProducesNoSuggestions() throws Exception {
        assumeFalse("Swing components require a display", GraphicsEnvironment.isHeadless());
        MarkerLibrary library = MarkerLibrary.loadBundled();
        JTextField field = new JTextField();

        MarkerAutoComplete autocomplete = MarkerAutoComplete.attach(field, library);

        field.setText("");
        assertTrue(autocomplete.currentSuggestions().isEmpty());
    }

    @Test
    public void tabKeyAutofillsTopSuggestion() throws Exception {
        assumeFalse("Swing components require a display", GraphicsEnvironment.isHeadless());
        MarkerLibrary library = MarkerLibrary.loadBundled();
        JTextField field = new JTextField();
        JFrame frame = new JFrame();
        frame.add(field);
        frame.pack();
        try {
            MarkerAutoComplete autocomplete = MarkerAutoComplete.attach(field, library);
            assertNotNull(autocomplete);

            field.setText("iba");
            // Top suggestion should be IBA1; Tab should rewrite the field.
            KeyEvent tab = new KeyEvent(field, KeyEvent.KEY_PRESSED,
                    System.currentTimeMillis(), 0, KeyEvent.VK_TAB, '\t');
            for (java.awt.event.KeyListener l : field.getKeyListeners()) {
                l.keyPressed(tab);
            }
            assertEquals("IBA1", field.getText());
        } finally {
            frame.dispose();
        }
    }

    @Test
    public void enterKeyKeepsTypedText() throws Exception {
        assumeFalse("Swing components require a display", GraphicsEnvironment.isHeadless());
        MarkerLibrary library = MarkerLibrary.loadBundled();
        JTextField field = new JTextField();
        JFrame frame = new JFrame();
        frame.add(field);
        frame.pack();
        try {
            MarkerAutoComplete autocomplete = MarkerAutoComplete.attach(field, library);
            assertNotNull(autocomplete);

            field.setText("iba");
            KeyEvent enter = new KeyEvent(field, KeyEvent.KEY_PRESSED,
                    System.currentTimeMillis(), 0, KeyEvent.VK_ENTER, '\n');
            for (java.awt.event.KeyListener l : field.getKeyListeners()) {
                l.keyPressed(enter);
            }
            assertEquals("iba", field.getText());
        } finally {
            frame.dispose();
        }
    }

    @Test
    public void tabKeyTransfersFocusWhenNoSuggestion() throws Exception {
        assumeFalse("Swing components require a display", GraphicsEnvironment.isHeadless());
        MarkerLibrary library = MarkerLibrary.loadBundled();
        JTextField field = new JTextField();
        MarkerAutoComplete.attach(field, library);

        field.setText("");
        // With no suggestions, the field must NOT swallow the typed text,
        // and Tab handler should not throw.
        KeyEvent tab = new KeyEvent(field, KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(), 0, KeyEvent.VK_TAB, '\t');
        for (java.awt.event.KeyListener l : field.getKeyListeners()) {
            l.keyPressed(tab);
        }
        assertEquals("", field.getText());
    }
}
