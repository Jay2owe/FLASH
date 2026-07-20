package flash.pipeline.intelligence.identity;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Unit tests for {@link IdentityResolver} (pure; uses fake strategies). */
public class IdentityResolverTest {

    private static IdentityStrategy fixed(final Map<SourceRecord, PartialIdentity> map) {
        return new IdentityStrategy() {
            @Override
            public Map<SourceRecord, PartialIdentity> detect(List<SourceRecord> batch) {
                return map;
            }
        };
    }

    @Test
    public void higherPrecedenceStrategyWinsPerField() {
        SourceRecord r = SourceRecord.looseFile("img.tif", "WT", null);
        List<SourceRecord> batch = Collections.singletonList(r);

        Map<SourceRecord, PartialIdentity> high = new HashMap<SourceRecord, PartialIdentity>();
        high.put(r, new PartialIdentity().animal("M14", Confidence.HIGH, "grammar"));

        Map<SourceRecord, PartialIdentity> low = new HashMap<SourceRecord, PartialIdentity>();
        low.put(r, new PartialIdentity()
                .animal("WHOLE", Confidence.LOW, "fallback")
                .region("SCN", Confidence.MEDIUM, "vocab"));

        IdentityResolver resolver = new IdentityResolver(Arrays.asList(fixed(high), fixed(low)));
        IdentityCandidate c = resolver.resolve(batch).get(r);

        assertEquals("M14", c.animalValue());                       // higher precedence wins
        assertEquals(Confidence.HIGH, c.getAnimal().confidence);
        assertEquals("grammar", c.getAnimal().provenance);
        assertEquals("SCN", c.regionValue());                       // only the low strategy had it
    }

    @Test
    public void conditionsMergePerAxisFirstWins() {
        SourceRecord r = SourceRecord.looseFile("img.tif", null, null);
        List<SourceRecord> batch = Collections.singletonList(r);

        Map<SourceRecord, PartialIdentity> a = new HashMap<SourceRecord, PartialIdentity>();
        a.put(r, new PartialIdentity().condition("Genotype", "hAPP", Confidence.HIGH, "grammar"));

        Map<SourceRecord, PartialIdentity> b = new HashMap<SourceRecord, PartialIdentity>();
        b.put(r, new PartialIdentity()
                .condition("Genotype", "WT", Confidence.LOW, "folder")        // loses to grammar
                .condition("Timepoint", "ZT06", Confidence.MEDIUM, "vocab")); // new axis kept

        IdentityResolver resolver = new IdentityResolver(Arrays.asList(fixed(a), fixed(b)));
        IdentityCandidate c = resolver.resolve(batch).get(r);

        assertEquals("hAPP", c.conditionValue("Genotype"));
        assertEquals("ZT06", c.conditionValue("Timepoint"));
        assertEquals(2, c.conditions().size());
    }

    @Test
    public void blankContributionsIgnoredAndResolveIsIdempotent() {
        SourceRecord r = SourceRecord.looseFile("img.tif", null, null);
        List<SourceRecord> batch = Collections.singletonList(r);

        Map<SourceRecord, PartialIdentity> only = new HashMap<SourceRecord, PartialIdentity>();
        only.put(r, new PartialIdentity()
                .animal("   ", Confidence.HIGH, "x")          // blank -> dropped
                .region("SCN", Confidence.HIGH, "vocab"));

        IdentityResolver resolver = new IdentityResolver(Collections.singletonList(fixed(only)));
        IdentityCandidate c1 = resolver.resolve(batch).get(r);
        IdentityCandidate c2 = resolver.resolve(batch).get(r);

        assertEquals("", c1.animalValue());
        assertNull(c1.getAnimal());
        assertEquals("SCN", c1.regionValue());
        assertEquals(c1.animalValue(), c2.animalValue());   // idempotent
        assertEquals(c1.regionValue(), c2.regionValue());
    }

    @Test
    public void recordWithNoContributionsResolvesEmpty() {
        SourceRecord r = SourceRecord.looseFile("img.tif", null, null);
        IdentityResolver resolver = new IdentityResolver(Collections.<IdentityStrategy>emptyList());
        IdentityCandidate c = resolver.resolve(Collections.singletonList(r)).get(r);
        assertEquals("", c.animalValue());
        assertNull(c.getAnimal());
        assertTrue(c.conditions().isEmpty());
    }
}
