package flash.pipeline.cli;

import flash.pipeline.analyses.wizard.AggregationConfig;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CLIAggregateOptionsTest {

    @Test
    public void parse_aggregatePresetRecordsName() {
        CLIConfig cfg = CLIArgumentParser.parse(
                "dir=[/tmp] aggregate.preset=[Per-animal standard (raw + per-mm3)]");
        assertNotNull(cfg);
        assertEquals("Per-animal standard (raw + per-mm3)",
                cfg.getAggregate().getPresetName());
    }

    @Test
    public void parse_aggregateGranularityParsesHemisphere() {
        CLIConfig cfg = CLIArgumentParser.parse(
                "dir=[/tmp] aggregate.granularity=hemisphere");
        assertNotNull(cfg);
        assertEquals(AggregationConfig.Granularity.HEMISPHERE,
                cfg.getAggregate().getGranularity());
    }

    @Test
    public void parse_aggregateOutputParsesRaw() {
        CLIConfig cfg = CLIArgumentParser.parse(
                "dir=[/tmp] aggregate.output=raw");
        assertNotNull(cfg);
        assertEquals(AggregationConfig.OutputMode.RAW_ONLY,
                cfg.getAggregate().getOutputMode());
    }

    @Test
    public void parse_aggregateOutputAcceptsNormalizedAlias() {
        CLIConfig cfg = CLIArgumentParser.parse(
                "dir=[/tmp] aggregate.output=normalized");
        assertNotNull(cfg);
        assertEquals(AggregationConfig.OutputMode.PERMM3_ONLY,
                cfg.getAggregate().getOutputMode());
    }

    @Test
    public void parse_aggregateOptionsAutoSelectRunAggregate() {
        CLIConfig cfg = CLIArgumentParser.parse(
                "dir=[/tmp] aggregate.granularity=region");
        assertNotNull(cfg);
        // Index 8 is Master Data Aggregation
        assertTrue(cfg.getSelectedAnalyses()[8]);
    }

    @Test
    public void serialize_roundTripsAllThreeOptions() {
        CLIConfig cfg = CLIArgumentParser.parse(
                "dir=[/tmp] aggregate.preset=[Per-region subdivision] "
                        + "aggregate.granularity=section aggregate.output=both");
        assertNotNull(cfg);
        String serialized = CLIArgumentParser.serialize(cfg);
        assertTrue(serialized, serialized.contains("aggregate.preset=[Per-region subdivision]"));
        assertTrue(serialized, serialized.contains("aggregate.granularity=section"));
        assertTrue(serialized, serialized.contains("aggregate.output=both"));
    }

    @Test
    public void aggregateConfigHasNoConfigurationByDefault() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[/tmp]");
        assertNotNull(cfg);
        assertTrue(!cfg.getAggregate().hasConfiguration());
    }
}
