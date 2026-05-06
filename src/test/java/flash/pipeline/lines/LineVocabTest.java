package flash.pipeline.lines;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LineVocabTest {

    @Test
    public void bundledVocabContainsRequiredLandmarksAndCustomSentinel() throws Exception {
        LineVocab vocab = LineVocab.loadBundled();
        assertTrue(vocab.vocabularyVersion() >= 1);

        List<String> labels = vocab.labels();
        assertTrue("vocab must contain Ventricle wall: " + labels,
                labels.contains("Ventricle wall"));
        assertTrue("vocab must contain Cortical pial surface: " + labels,
                labels.contains("Cortical pial surface"));
        assertTrue("vocab must contain White-matter boundary: " + labels,
                labels.contains("White-matter boundary"));
        assertTrue("vocab must contain Wound margin: " + labels,
                labels.contains("Wound margin"));
        assertTrue("vocab must contain Tissue edge: " + labels,
                labels.contains("Tissue edge"));
        assertTrue("vocab must contain Axon tract: " + labels,
                labels.contains("Axon tract"));

        // Custom sentinel must be present and exposed via the constant.
        assertTrue("vocab must contain Custom sentinel: " + labels,
                labels.contains(LineVocab.CUSTOM_LABEL));

        LineVocab.Entry custom = vocab.findMatch(LineVocab.CUSTOM_LABEL);
        assertNotNull(custom);
        assertTrue(custom.isCustom());
    }

    @Test
    public void findMatchReturnsEntryByLabelAndAliasCaseInsensitively() throws Exception {
        LineVocab vocab = LineVocab.loadBundled();

        LineVocab.Entry direct = vocab.findMatch("ventricle wall");
        assertNotNull(direct);
        assertEquals("Ventricle wall", direct.getLabel());

        LineVocab.Entry alias = vocab.findMatch("PIA");
        assertNotNull(alias);
        assertEquals("Cortical pial surface", alias.getLabel());

        assertNull(vocab.findMatch(""));
        assertNull(vocab.findMatch(null));
        assertNull(vocab.findMatch("not a real landmark xyz"));
    }

    @Test
    public void matchExistingSetNameMatchesByExactNameAndByAlias() throws Exception {
        LineVocab vocab = LineVocab.loadBundled();
        List<String> existing = Arrays.asList("Ventricle wall", "TissueEdge", "Pia");

        // Exact case-insensitive match
        assertEquals("Ventricle wall",
                vocab.matchExistingSetName("ventricle wall", existing));

        // Vocab alias resolves to a vocab entry whose alias matches an existing set
        // (Pia is an alias of Cortical pial surface, and "Pia" exists)
        assertEquals("Pia", vocab.matchExistingSetName("Cortical pial surface", existing));

        assertNull(vocab.matchExistingSetName("totally novel name", existing));
        assertNull(vocab.matchExistingSetName("", existing));
        assertNull(vocab.matchExistingSetName(null, existing));
        assertNull(vocab.matchExistingSetName("Ventricle wall", Collections.<String>emptyList()));
    }

    @Test
    public void fromJsonSkipsBlankLabels() throws Exception {
        String json = "{ \"vocabularyVersion\": 2, \"entries\": ["
                + "{ \"label\": \"\", \"aliases\": [] },"
                + "{ \"label\": \"Foo\", \"aliases\": [\"f\"] },"
                + "{ \"label\": \"   \", \"aliases\": [] }"
                + "] }";
        LineVocab vocab = LineVocab.fromJson(json);
        assertEquals(2, vocab.vocabularyVersion());
        assertEquals(1, vocab.entries().size());
        assertEquals("Foo", vocab.entries().get(0).getLabel());
        assertEquals(Arrays.asList("f"), vocab.entries().get(0).getAliases());
    }

    @Test
    public void entryAliasesAreImmutable() {
        List<String> aliases = new ArrayList<String>(Arrays.asList("a", "b"));
        LineVocab.Entry entry = new LineVocab.Entry("Test", aliases);
        try {
            entry.getAliases().add("c");
            assertFalse("aliases must be immutable", true);
        } catch (UnsupportedOperationException expected) {
            // good
        }
        // Mutating the source list must not leak through.
        aliases.add("c");
        assertEquals(2, entry.getAliases().size());
    }
}
