package flash.pipeline.intelligence.identity;

import org.junit.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Verifies {@link LegacyParserStrategy} adapts {@code ImageNameParser} correctly. */
public class LegacyParserStrategyTest {

    @Test
    public void strictConventionPopulatesFields() {
        SourceRecord r = SourceRecord.looseFile("MyExp-Mouse5_LH_SCN.tif", null, null);
        Map<SourceRecord, PartialIdentity> out =
                new LegacyParserStrategy().detect(Collections.singletonList(r));
        PartialIdentity p = out.get(r);
        assertEquals("Mouse5", p.animal().value);
        assertEquals("LH", p.hemisphere().value);
        assertEquals("SCN", p.region().value);
        assertEquals(Confidence.MEDIUM, p.animal().confidence);
    }

    @Test
    public void containerSeriesSeedParsesSeriesIdentity() {
        // The container is the experiment; the series name carries
        // Animal_Hemisphere_Region. (A leading experiment token inside the
        // series name would be absorbed into the animal, by design.)
        SourceRecord r = SourceRecord.containerSeries(
                "experiment.lif", 3, "Mouse7_RH_PVN", "KO", null);
        PartialIdentity p = new LegacyParserStrategy().detect(Collections.singletonList(r)).get(r);
        assertEquals("Mouse7", p.animal().value);
        assertEquals("RH", p.hemisphere().value);
        assertEquals("PVN", p.region().value);
    }

    @Test
    public void nonConventionFallsBackToAnimalOnly() {
        SourceRecord r = SourceRecord.looseFile("randomName.tif", null, null);
        PartialIdentity p = new LegacyParserStrategy().detect(Collections.singletonList(r)).get(r);
        assertEquals("randomName", p.animal().value);
        assertEquals(Confidence.LOW, p.animal().confidence);
        assertNull(p.hemisphere());
        assertNull(p.region());
    }
}
