package flash.pipeline.ui.wizard;

import flash.pipeline.atlas.AtlasRegion;
import flash.pipeline.atlas.AtlasRegionLibrary;
import flash.pipeline.atlas.AtlasRegionLibraryIO;
import org.junit.Test;

import java.awt.Dimension;
import java.awt.event.KeyEvent;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class RegionTypeaheadTest {

    @Test
    public void ranksAcronymAndAliasQueries() throws Exception {
        RegionTypeahead typeahead = new RegionTypeahead(AtlasRegionLibraryIO.loadBundled());

        assertEquals("CA1", typeahead.suggest("ca1").get(0).getAcronym());
        assertEquals("SCH", typeahead.suggest("scn").get(0).getAcronym());
    }

    @Test
    public void ranksNameQueries() throws Exception {
        RegionTypeahead typeahead = new RegionTypeahead(AtlasRegionLibraryIO.loadBundled());

        List<AtlasRegion> results = typeahead.suggest("suprachiasmatic");

        assertFalse(results.isEmpty());
        assertEquals("SCH", results.get(0).getAcronym());
    }

    @Test
    public void selectionWritesCanonicalAcronym() throws Exception {
        AtlasRegionLibrary library = AtlasRegionLibraryIO.loadBundled();
        RegionTypeahead typeahead = new RegionTypeahead(library);
        AtlasRegion selected = library.byAcronym("VISp");

        typeahead.setSelectedRegion(selected);

        assertSame(selected, typeahead.getSelectedRegion());
        assertEquals("VISp", typeahead.getTextField().getText());
    }

    @Test
    public void editingSelectedRegionClearsSelection() throws Exception {
        AtlasRegionLibrary library = AtlasRegionLibraryIO.loadBundled();
        RegionTypeahead typeahead = new RegionTypeahead(library);

        typeahead.setSelectedRegion(library.byAcronym("VISp"));
        typeahead.getTextField().setText("VIS");

        assertNull(typeahead.getSelectedRegion());
    }

    @Test
    public void enterKeyKeepsTypedTextUnselected() throws Exception {
        RegionTypeahead typeahead = new RegionTypeahead(AtlasRegionLibraryIO.loadBundled());

        typeahead.getTextField().setText("hippo");
        KeyEvent enter = new KeyEvent(typeahead.getTextField(), KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(), 0, KeyEvent.VK_ENTER, '\n');
        for (java.awt.event.KeyListener listener : typeahead.getTextField().getKeyListeners()) {
            listener.keyPressed(enter);
        }

        assertEquals("hippo", typeahead.getTextField().getText());
        assertNull(typeahead.getSelectedRegion());
    }

    @Test
    public void suggestionsDoNotChangeComponentHeight() throws Exception {
        RegionTypeahead typeahead = new RegionTypeahead(AtlasRegionLibraryIO.loadBundled());
        Dimension before = typeahead.getPreferredSize();

        typeahead.getTextField().setText("ca");
        Dimension after = typeahead.getPreferredSize();

        assertEquals(before.height, after.height);
    }

    @Test
    public void emptyInputShowsNoSuggestions() throws Exception {
        RegionTypeahead typeahead = new RegionTypeahead(AtlasRegionLibraryIO.loadBundled());

        assertTrue(typeahead.suggest("").isEmpty());
        assertFalse(typeahead.isSuggestionListVisible());
    }
}
