package flash.pipeline.cli;

import flash.pipeline.analyses.StatisticsConfig;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Coverage for {@code stats.*} CLI flag parsing.
 */
public class CLIArgumentParserStatsTest {

    @Test
    public void parse_statsPresetOnly() {
        CLIConfig cfg = CLIArgumentParser.parse(
                "dir=[/tmp] stats.preset=multi_group_tukey");
        assertNotNull(cfg);
        CLIConfig.StatsConfig stats = cfg.getStats();
        assertEquals("multi_group_tukey", stats.getPresetName());
        assertNull(stats.getPairedMode());
        assertNull(stats.getDistMode());
        assertNull(stats.getPostHoc());
        assertNull(stats.getMetrics());
        assertNull(stats.getMetricAggregations());
    }

    @Test
    public void parse_statsExplicitOnly() {
        CLIConfig cfg = CLIArgumentParser.parse(
                "dir=[/tmp] stats.paired=hemisphere stats.distribution=parametric "
                        + "stats.posthoc=tukey");
        assertNotNull(cfg);
        CLIConfig.StatsConfig stats = cfg.getStats();
        assertNull(stats.getPresetName());
        assertEquals(StatisticsConfig.PairedMode.HEMISPHERE, stats.getPairedMode());
        assertEquals(StatisticsConfig.DistributionMode.ASSUME_NORMAL, stats.getDistMode());
        assertEquals(StatisticsConfig.PostHocMethod.TUKEY, stats.getPostHoc());
    }

    @Test
    public void parse_statsPresetAndOverrideCoexist() {
        CLIConfig cfg = CLIArgumentParser.parse(
                "dir=[/tmp] stats.preset=2_group_paired_lh_vs_rh stats.posthoc=dunns");
        assertNotNull(cfg);
        CLIConfig.StatsConfig stats = cfg.getStats();
        assertEquals("2_group_paired_lh_vs_rh", stats.getPresetName());
        assertEquals(StatisticsConfig.PostHocMethod.DUNNS, stats.getPostHoc());
        // Paired mode is supplied by the preset at apply-time, not parse-time.
        assertNull(stats.getPairedMode());
    }

    @Test
    public void parse_statsDistributionNonParametricAlias() {
        CLIConfig cfg = CLIArgumentParser.parse(
                "dir=[/tmp] stats.distribution=non_parametric");
        assertNotNull(cfg);
        assertEquals(StatisticsConfig.DistributionMode.ASSUME_SKEWED,
                cfg.getStats().getDistMode());
    }

    @Test
    public void parse_statsBadEnumTokensFallBack() {
        CLIConfig cfg = CLIArgumentParser.parse(
                "dir=[/tmp] stats.paired=garbage stats.distribution=bogus stats.posthoc=hokum");
        assertNotNull(cfg);
        CLIConfig.StatsConfig stats = cfg.getStats();
        assertEquals(StatisticsConfig.PairedMode.OFF, stats.getPairedMode());
        assertEquals(StatisticsConfig.DistributionMode.AUTO, stats.getDistMode());
        assertEquals(StatisticsConfig.PostHocMethod.BONFERRONI, stats.getPostHoc());
    }

    @Test
    public void parse_statsMetricsParsesCommaSeparatedTrimmingWhitespace() {
        CLIConfig cfg = CLIArgumentParser.parse(
                "dir=[/tmp] stats.metrics=[Count_A , Count_B,Volume_um3]");
        assertNotNull(cfg);
        List<String> metrics = cfg.getStats().getMetrics();
        assertNotNull(metrics);
        assertEquals(3, metrics.size());
        assertEquals("Count_A", metrics.get(0));
        assertEquals("Count_B", metrics.get(1));
        assertEquals("Volume_um3", metrics.get(2));
    }

    @Test
    public void parse_statsMetricAggregationParsesPairsAndConvenienceLists() {
        CLIConfig cfg = CLIArgumentParser.parse(
                "dir=[/tmp] stats.metric_aggregation=[ObjectsDetected:sum,CellCount:mean] "
                        + "stats.sum_metrics=SpotTotal stats.mean_metrics=AreaPercent");
        assertNotNull(cfg);

        Map<String, StatisticsConfig.MetricAggregation> aggregations =
                cfg.getStats().getMetricAggregations();
        assertNotNull(aggregations);
        assertEquals(4, aggregations.size());
        assertEquals(StatisticsConfig.MetricAggregation.SUM,
                aggregations.get("ObjectsDetected"));
        assertEquals(StatisticsConfig.MetricAggregation.MEAN,
                aggregations.get("CellCount"));
        assertEquals(StatisticsConfig.MetricAggregation.SUM,
                aggregations.get("SpotTotal"));
        assertEquals(StatisticsConfig.MetricAggregation.MEAN,
                aggregations.get("AreaPercent"));
    }

    @Test
    public void parse_statsMetricAggregationLaterCaseVariantWins() {
        CLIConfig cfg = CLIArgumentParser.parse(
                "dir=[/tmp] stats.metric_aggregation=CellCount:sum stats.mean_metrics=cellcount");
        assertNotNull(cfg);

        Map<String, StatisticsConfig.MetricAggregation> aggregations =
                cfg.getStats().getMetricAggregations();
        assertNotNull(aggregations);
        assertEquals(1, aggregations.size());
        assertEquals(StatisticsConfig.MetricAggregation.MEAN,
                aggregations.get("cellcount"));
    }

    @Test
    public void parse_statsConfigurationAutoSelectsRunStatistics() {
        CLIConfig cfg = CLIArgumentParser.parse(
                "dir=[/tmp] stats.posthoc=dunns");
        assertNotNull(cfg);
        // Index 9 is Statistical Analysis
        assertTrue(cfg.getSelectedAnalyses()[9]);
    }

    @Test
    public void parse_statsHasNoConfigurationByDefault() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[/tmp]");
        assertNotNull(cfg);
        assertTrue(!cfg.getStats().hasConfiguration());
    }

    @Test
    public void parse_statsPairedAlphabeticTokens() {
        CLIConfig cfg = CLIArgumentParser.parse(
                "dir=[/tmp] stats.paired=region");
        assertNotNull(cfg);
        assertEquals(StatisticsConfig.PairedMode.REGION,
                cfg.getStats().getPairedMode());
    }

    @Test
    public void parse_statsPostHocAlphabeticTokens() {
        CLIConfig cfg = CLIArgumentParser.parse(
                "dir=[/tmp] stats.posthoc=none");
        assertNotNull(cfg);
        assertEquals(StatisticsConfig.PostHocMethod.NONE,
                cfg.getStats().getPostHoc());
    }

    @Test
    public void serialize_roundTripsStatsOptions() {
        CLIConfig cfg = CLIArgumentParser.parse(
                "dir=[/tmp] stats.preset=multi_group_tukey "
                        + "stats.paired=hemisphere stats.distribution=parametric "
                        + "stats.posthoc=tukey stats.metrics=Count_A,Count_B "
                        + "stats.metric_aggregation=ObjectsDetected:sum,CellCount:mean");
        assertNotNull(cfg);
        String serialized = CLIArgumentParser.serialize(cfg);
        assertTrue(serialized, serialized.contains("stats.preset=multi_group_tukey"));
        assertTrue(serialized, serialized.contains("stats.paired=hemisphere"));
        assertTrue(serialized, serialized.contains("stats.distribution=parametric"));
        assertTrue(serialized, serialized.contains("stats.posthoc=tukey"));
        assertTrue(serialized, serialized.contains("stats.metrics=Count_A,Count_B"));
        assertTrue(serialized, serialized.contains(
                "stats.metric_aggregation=ObjectsDetected:sum,CellCount:mean"));
    }
}
