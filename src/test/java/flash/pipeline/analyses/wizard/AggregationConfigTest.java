package flash.pipeline.analyses.wizard;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AggregationConfigTest {

    @Test
    public void granularityParseAcceptsExpectedTokens() {
        assertEquals(AggregationConfig.Granularity.ANIMAL,
                AggregationConfig.Granularity.parse("animal", AggregationConfig.Granularity.ANIMAL));
        assertEquals(AggregationConfig.Granularity.HEMISPHERE,
                AggregationConfig.Granularity.parse("hemisphere", AggregationConfig.Granularity.ANIMAL));
        assertEquals(AggregationConfig.Granularity.HEMISPHERE,
                AggregationConfig.Granularity.parse("hemi", AggregationConfig.Granularity.ANIMAL));
        assertEquals(AggregationConfig.Granularity.REGION,
                AggregationConfig.Granularity.parse("region", AggregationConfig.Granularity.ANIMAL));
        assertEquals(AggregationConfig.Granularity.SECTION,
                AggregationConfig.Granularity.parse("section", AggregationConfig.Granularity.ANIMAL));
    }

    @Test
    public void granularityParseFallsBackOnUnknown() {
        assertEquals(AggregationConfig.Granularity.ANIMAL,
                AggregationConfig.Granularity.parse("nonsense", AggregationConfig.Granularity.ANIMAL));
        assertEquals(AggregationConfig.Granularity.HEMISPHERE,
                AggregationConfig.Granularity.parse(null, AggregationConfig.Granularity.HEMISPHERE));
    }

    @Test
    public void outputModeParseMatchesAllSpokenAliases() {
        assertEquals(AggregationConfig.OutputMode.RAW_AND_PERMM3,
                AggregationConfig.OutputMode.parse("both", AggregationConfig.OutputMode.RAW_ONLY));
        assertEquals(AggregationConfig.OutputMode.RAW_ONLY,
                AggregationConfig.OutputMode.parse("raw", AggregationConfig.OutputMode.RAW_AND_PERMM3));
        assertEquals(AggregationConfig.OutputMode.PERMM3_ONLY,
                AggregationConfig.OutputMode.parse("normalized", AggregationConfig.OutputMode.RAW_AND_PERMM3));
        assertEquals(AggregationConfig.OutputMode.PERMM3_ONLY,
                AggregationConfig.OutputMode.parse("normalised", AggregationConfig.OutputMode.RAW_AND_PERMM3));
        assertEquals(AggregationConfig.OutputMode.PERMM3_ONLY,
                AggregationConfig.OutputMode.parse("permm3", AggregationConfig.OutputMode.RAW_AND_PERMM3));
    }

    @Test
    public void tokenRoundTripsThroughParse() {
        for (AggregationConfig.Granularity g : AggregationConfig.Granularity.values()) {
            assertEquals(g, AggregationConfig.Granularity.parse(g.token(), AggregationConfig.Granularity.ANIMAL));
        }
        for (AggregationConfig.OutputMode m : AggregationConfig.OutputMode.values()) {
            assertEquals(m, AggregationConfig.OutputMode.parse(m.token(), AggregationConfig.OutputMode.RAW_AND_PERMM3));
        }
    }

    @Test
    public void defaultsAreAnimalAndRawAndPermm3() {
        AggregationConfig config = new AggregationConfig();
        assertEquals(AggregationConfig.Granularity.ANIMAL, config.getGranularity());
        assertEquals(AggregationConfig.OutputMode.RAW_AND_PERMM3, config.getOutputMode());
    }
}
