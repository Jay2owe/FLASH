package flash.pipeline.analyses.wizard;

import flash.pipeline.marker.MarkerLibrary;
import org.junit.Test;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class MarkerLibraryIOTest {

    @Test
    public void bundledEntriesHaveRequiredFieldsLowercaseHintsAndUniqueDisplayNames() throws Exception {
        MarkerLibrary library = MarkerLibraryIO.loadBundled();
        Set<String> displayNames = new HashSet<String>();

        assertTrue(library.libraryVersion() >= 1);
        assertTrue(library.entries().size() >= 200);

        for (MarkerLibrary.Entry entry : library.entries()) {
            assertRequired(entry.getId());
            assertRequired(entry.getDisplayName());
            assertRequired(entry.getCategory());
            assertRequired(entry.getConventionalLUT());
            assertRequired(entry.getFilterPreset());
            assertRequired(entry.getShape());
            assertRequired(entry.getParticleSizeHint());
            assertRequired(entry.getIntensityLevel());
            assertTrue("duplicate display name " + entry.getDisplayName(), displayNames.add(entry.getDisplayName()));
            for (String hint : entry.getNameHints()) {
                assertFalse(hint.trim().isEmpty());
                assertTrue("nameHint must be lowercase: " + hint,
                        hint.equals(hint.toLowerCase(Locale.ROOT)));
            }
        }

        assertNotNull(library.byId("microglia_iba1"));
        assertNotNull(library.byId("astrocytes_gfap"));
        assertNotNull(library.byId("synapse_psd95"));
    }

    private static void assertRequired(String value) {
        assertNotNull(value);
        assertFalse(value.trim().isEmpty());
    }
}
