package flash.pipeline.ui.wizard;

import org.junit.Test;

import java.awt.Dimension;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class MarkerTypeaheadTest {

    @Test
    public void ranksPrefixBeforeSubstringAndHints() {
        MarkerTypeahead typeahead = new MarkerTypeahead(testLibrary());

        List<MarkerLibrary.Entry> iba = typeahead.suggest("iba");
        assertEquals("IBA1", iba.get(0).getDisplayName());

        List<MarkerLibrary.Entry> p2 = typeahead.suggest("p2");
        assertEquals("P2RY12", p2.get(0).getDisplayName());
    }

    @Test
    public void emptyInputShowsNoSuggestions() {
        MarkerTypeahead typeahead = new MarkerTypeahead(testLibrary());

        assertTrue(typeahead.suggest("").isEmpty());
        assertFalse(typeahead.isSuggestionListVisible());
    }

    @Test
    public void browseSelectionRoundTripsMarkerThroughSetter() {
        MarkerTypeahead typeahead = new MarkerTypeahead(testLibrary());
        MarkerLibrary.Entry selected = testLibrary().entries().get(2);

        typeahead.setSelectedMarker(selected);

        assertSame(selected, typeahead.getSelectedMarker());
        assertEquals(selected.getDisplayName(), typeahead.getTextField().getText());
        Map<String, List<MarkerLibrary.Entry>> grouped = typeahead.groupedMarkers();
        assertTrue(grouped.containsKey("microglia"));
    }

    @Test
    public void enterKeyKeepsTypedTextUnselected() {
        MarkerTypeahead typeahead = new MarkerTypeahead(testLibrary());

        typeahead.getTextField().setText("iba");
        KeyEvent enter = new KeyEvent(typeahead.getTextField(), KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(), 0, KeyEvent.VK_ENTER, '\n');
        for (java.awt.event.KeyListener l : typeahead.getTextField().getKeyListeners()) {
            l.keyPressed(enter);
        }

        assertEquals("iba", typeahead.getTextField().getText());
        assertNull(typeahead.getSelectedMarker());
    }

    @Test
    public void editingSelectedMarkerClearsSelection() {
        MarkerTypeahead typeahead = new MarkerTypeahead(testLibrary());
        MarkerLibrary.Entry selected = testLibrary().entries().get(1);

        typeahead.setSelectedMarker(selected);
        typeahead.getTextField().setText("H31L21");

        assertEquals("H31L21", typeahead.getTextField().getText());
        assertNull(typeahead.getSelectedMarker());
    }

    @Test
    public void suggestionsDoNotChangeComponentHeight() {
        MarkerTypeahead typeahead = new MarkerTypeahead(testLibrary());
        Dimension before = typeahead.getPreferredSize();

        typeahead.getTextField().setText("iba");
        Dimension after = typeahead.getPreferredSize();

        assertEquals(before.height, after.height);
    }

    @Test
    public void autofluorescenceSentinelAppearsWhenFewRealMatches() {
        MarkerTypeahead typeahead = new MarkerTypeahead(new MarkerLibrary(Arrays.asList(
                entry("rare", "RareMarker", "rare", "round", false, false, "rareAlias"))));

        List<MarkerLibrary.Entry> suggestions = typeahead.suggest("rare");

        assertEquals("RareMarker", suggestions.get(0).getDisplayName());
        assertTrue(suggestions.contains(MarkerLibrary.AUTOFLUORESCENCE));
        assertFalse("OTHER_CUSTOM sentinel should no longer appear in typeahead suggestions",
                suggestions.contains(MarkerLibrary.OTHER_CUSTOM));
    }

    @Test
    public void autofluorescenceSentinelStaysPresentWhenManyMarkersMatch() {
        MarkerTypeahead typeahead = new MarkerTypeahead(new MarkerLibrary(Arrays.asList(
                entry("rare1", "RareMarker1", "rare", "round", false, false, "rare"),
                entry("rare2", "RareMarker2", "rare", "round", false, false, "rare"),
                entry("rare3", "RareMarker3", "rare", "round", false, false, "rare"),
                entry("rare4", "RareMarker4", "rare", "round", false, false, "rare"),
                entry("rare5", "RareMarker5", "rare", "round", false, false, "rare"))));

        List<MarkerLibrary.Entry> suggestions = typeahead.suggest("rare");

        assertEquals(4, suggestions.size());
        assertTrue(suggestions.contains(MarkerLibrary.AUTOFLUORESCENCE));
        assertFalse(suggestions.contains(MarkerLibrary.OTHER_CUSTOM));
    }

    @Test
    public void seedLibraryQueriesStayUnderTenMilliseconds() {
        MarkerTypeahead typeahead = new MarkerTypeahead(MarkerLibrary.seedLibrary());

        long start = System.nanoTime();
        typeahead.suggest("iba");
        long ibaMs = (System.nanoTime() - start) / 1000000L;

        start = System.nanoTime();
        typeahead.suggest("p2");
        long p2Ms = (System.nanoTime() - start) / 1000000L;

        assertTrue("iba query took " + ibaMs + " ms", ibaMs < 10L);
        assertTrue("p2 query took " + p2Ms + " ms", p2Ms < 10L);
    }

    private static MarkerLibrary testLibrary() {
        List<MarkerLibrary.Entry> entries = new ArrayList<MarkerLibrary.Entry>();
        entries.add(entry("map2", "MAP2", "neuronal", "complex", true, true, "dendrite"));
        entries.add(entry("iba1", "IBA1", "microglia", "complex", true, true, "Iba-1", "AIF1"));
        entries.add(entry("p2ry12", "P2RY12", "microglia", "complex", true, true, "P2Y12"));
        entries.add(entry("cd68", "CD68", "lysosome", "puncta_like", false, false, "phagosome"));
        return new MarkerLibrary(entries);
    }

    private static MarkerLibrary.Entry entry(String id,
                                             String displayName,
                                             String category,
                                             String shape,
                                             boolean crowdingSensitive,
                                             boolean crowdedByDefault,
                                             String... aliases) {
        return new MarkerLibrary.Entry(id, displayName, Arrays.asList(aliases),
                Arrays.<String>asList(), category, shape, crowdingSensitive, crowdedByDefault);
    }
}
