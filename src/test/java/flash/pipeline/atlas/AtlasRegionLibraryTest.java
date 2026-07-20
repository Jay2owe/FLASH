package flash.pipeline.atlas;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AtlasRegionLibraryTest {

    @Test
    public void bundledAtlasLoadsExpectedRegions() throws Exception {
        AtlasRegionLibrary library = AtlasRegionLibraryIO.loadBundled();

        assertEquals("allen_mouse_25um", library.getAtlasKey());
        assertTrue("expected a full ontology, found " + library.regions().size(),
                library.regions().size() > 1000);

        AtlasRegion ca1 = library.byAcronym("CA1");
        assertNotNull(ca1);
        assertEquals(382, ca1.getId());
        assertEquals("Field CA1", ca1.getName());
        assertFalse(ca1.getStructureIdPath().isEmpty());

        AtlasRegion visp = library.byAcronym("VISp");
        assertNotNull(visp);
        assertEquals("Primary visual area", visp.getName());
    }

    @Test
    public void searchFindsAcronymsNamesAndAliases() throws Exception {
        AtlasRegionLibrary library = AtlasRegionLibraryIO.loadBundled();

        assertEquals("CA1", library.search("ca1", 5).get(0).getAcronym());
        assertEquals("SCH", library.search("suprachiasmatic", 5).get(0).getAcronym());
        assertEquals("SCH", library.search("SCN", 5).get(0).getAcronym());
        assertEquals("SCH", library.search("286", 5).get(0).getAcronym());
    }

    @Test
    public void resolverKeepsDigitAcronymsBeforeTryingSectionSuffixes() throws Exception {
        AtlasRegionLibrary library = AtlasRegionLibraryIO.loadBundled();

        AtlasRegionResolver.Resolved ca1 = AtlasRegionResolver.resolve("CA1", library);

        assertTrue(ca1.isResolved());
        assertEquals("CA1", ca1.getRegion().getAcronym());
        assertTrue(ca1.isExact());
        assertFalse(ca1.isStrippedSectionSuffix());
    }

    @Test
    public void resolverCanStripSectionSuffixWhenNoExactMatchExists() throws Exception {
        AtlasRegionLibrary library = AtlasRegionLibraryIO.loadBundled();

        AtlasRegionResolver.Resolved section = AtlasRegionResolver.resolveFromRegionAndRoi("", "SCN12", library);

        assertTrue(section.isResolved());
        assertEquals("SCH", section.getRegion().getAcronym());
        assertEquals("SCN", section.getMatchedText());
        assertFalse(section.isExact());
        assertTrue(section.isStrippedSectionSuffix());
    }

    @Test
    public void resolverCanonicalizesNumericAtlasIds() throws Exception {
        AtlasRegionLibrary library = AtlasRegionLibraryIO.loadBundled();

        AtlasRegionResolver.Resolved resolved = AtlasRegionResolver.resolve("286", library);

        assertTrue(resolved.isResolved());
        assertEquals("SCH", resolved.getRegion().getAcronym());
        assertTrue(resolved.isExact());
        assertEquals("SCH", AtlasRegionResolver.canonicalizeIfExact("286", library));
    }

    @Test
    public void unknownFreeformRegionStaysUnresolved() throws Exception {
        AtlasRegionLibrary library = AtlasRegionLibraryIO.loadBundled();

        AtlasRegionResolver.Resolved resolved = AtlasRegionResolver.resolve("My custom region", library);

        assertFalse(resolved.isResolved());
        assertNull(resolved.getRegion());
        assertEquals("My custom region",
                AtlasRegionResolver.canonicalizeIfExact("My custom region", library));
    }

    @Test
    public void bundledAtlasHasUniqueIdsAndAcronyms() throws Exception {
        AtlasRegionLibrary library = AtlasRegionLibraryIO.loadBundled();
        Set<Integer> ids = new HashSet<Integer>();
        Set<String> acronyms = new HashSet<String>();

        for (AtlasRegion region : library.regions()) {
            assertTrue("duplicate id " + region.getId(), ids.add(Integer.valueOf(region.getId())));
            assertTrue("duplicate acronym " + region.getAcronym(), acronyms.add(region.getAcronym()));
            assertFalse(region.getName().trim().isEmpty());
        }
    }

    @Test
    public void topResultsAreBounded() throws Exception {
        AtlasRegionLibrary library = AtlasRegionLibraryIO.loadBundled();

        List<AtlasRegion> results = library.search("area", 3);

        assertTrue(results.size() <= 3);
    }
}
