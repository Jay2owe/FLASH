package flash.pipeline.analyses.wizard;

import flash.pipeline.analyses.StatisticsConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class StatisticsPresetIOTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void stockPresetsBootstrapWhenDirectoryIsEmpty() throws Exception {
        File root = temp.newFolder("stats-stock");
        StatisticsPresetIO io = new StatisticsPresetIO(root);

        List<StatisticsPreset> presets = io.listAll();

        assertEquals(8, presets.size());
        File presetDir = io.presetDirectory();
        assertTrue(new File(presetDir, "2_group_automatic.json").isFile());
        assertTrue(new File(presetDir, "2_group_paired_lh_vs_rh.json").isFile());
        assertTrue(new File(presetDir, "small_n_cautious.json").isFile());
        assertTrue(new File(presetDir, "large_n_parametric.json").isFile());
        assertTrue(new File(presetDir, "multi_group_tukey.json").isFile());
        assertTrue(new File(presetDir, "multi_group_dunns.json").isFile());
        assertTrue(new File(presetDir, "raw_p_values.json").isFile());
        assertTrue(new File(presetDir, "focused_single_metric.json").isFile());
    }

    @Test
    public void everyStockPresetLoadsAndRoundTripsThroughJson() throws Exception {
        File root = temp.newFolder("stats-roundtrip");
        StatisticsPresetIO io = new StatisticsPresetIO(root);

        List<StatisticsPreset> presets = io.listAll();
        assertEquals(8, presets.size());

        for (StatisticsPreset preset : presets) {
            String json = preset.toJson();
            StatisticsPreset reparsed = StatisticsPreset.fromJson(json);
            assertEquals(preset.getName(), reparsed.getName());
            assertEquals(preset.getPairedMode(), reparsed.getPairedMode());
            assertEquals(preset.getDistributionMode(), reparsed.getDistributionMode());
            assertEquals(preset.getPostHocMethod(), reparsed.getPostHocMethod());
            assertEquals(preset.getMetricFilter(), reparsed.getMetricFilter());
            assertEquals(preset.getMetricAggregationOverrides(),
                    reparsed.getMetricAggregationOverrides());
        }
    }

    @Test
    public void conditionAxisIdRoundTripsThroughJsonAndConfig() throws Exception {
        StatisticsPreset preset = new StatisticsPreset(
                "By genotype", "desc",
                StatisticsConfig.PairedMode.OFF,
                StatisticsConfig.DistributionMode.AUTO,
                StatisticsConfig.PostHocMethod.BONFERRONI,
                null, null, "genotype");
        assertEquals("genotype", preset.getConditionAxisId());
        assertEquals("genotype", preset.toConfig().conditionAxisId);

        StatisticsPreset reparsed = StatisticsPreset.fromJson(preset.toJson());
        assertEquals("genotype", reparsed.getConditionAxisId());
        assertEquals("genotype", reparsed.toConfig().conditionAxisId);

        // legacy preset without the field stays combined (null)
        StatisticsPreset legacy = StatisticsPreset.fromJson(
                "{\"name\":\"legacy\",\"pairedMode\":\"OFF\",\"distributionMode\":\"AUTO\","
                        + "\"postHocMethod\":\"BONFERRONI\"}");
        assertNull(legacy.getConditionAxisId());
    }

    @Test
    public void twoGroupAutomaticPresetUsesEngineDefaults() throws Exception {
        StatisticsPreset preset = loadStock("2_group_automatic");

        assertEquals(StatisticsConfig.PairedMode.OFF, preset.getPairedMode());
        assertEquals(StatisticsConfig.DistributionMode.AUTO, preset.getDistributionMode());
        assertEquals(StatisticsConfig.PostHocMethod.BONFERRONI, preset.getPostHocMethod());
        assertNull(preset.getMetricFilter());
    }

    @Test
    public void pairedHemispherePresetRequestsHemispherePairing() throws Exception {
        StatisticsPreset preset = loadStock("2_group_paired_lh_vs_rh");

        assertEquals(StatisticsConfig.PairedMode.HEMISPHERE, preset.getPairedMode());
        assertEquals(StatisticsConfig.DistributionMode.AUTO, preset.getDistributionMode());
        assertEquals(StatisticsConfig.PostHocMethod.BONFERRONI, preset.getPostHocMethod());
    }

    @Test
    public void smallNCautiousPresetForcesNonParametric() throws Exception {
        StatisticsPreset preset = loadStock("small_n_cautious");
        assertEquals(StatisticsConfig.DistributionMode.ASSUME_SKEWED, preset.getDistributionMode());
    }

    @Test
    public void largeNParametricPresetForcesParametric() throws Exception {
        StatisticsPreset preset = loadStock("large_n_parametric");
        assertEquals(StatisticsConfig.DistributionMode.ASSUME_NORMAL, preset.getDistributionMode());
    }

    @Test
    public void multiGroupTukeyPresetSelectsTukeyHsd() throws Exception {
        StatisticsPreset preset = loadStock("multi_group_tukey");
        assertEquals(StatisticsConfig.DistributionMode.ASSUME_NORMAL, preset.getDistributionMode());
        assertEquals(StatisticsConfig.PostHocMethod.TUKEY, preset.getPostHocMethod());
    }

    @Test
    public void multiGroupDunnsPresetSelectsDunns() throws Exception {
        StatisticsPreset preset = loadStock("multi_group_dunns");
        assertEquals(StatisticsConfig.DistributionMode.ASSUME_SKEWED, preset.getDistributionMode());
        assertEquals(StatisticsConfig.PostHocMethod.DUNNS, preset.getPostHocMethod());
    }

    @Test
    public void rawPValuesPresetDisablesPostHocCorrection() throws Exception {
        StatisticsPreset preset = loadStock("raw_p_values");
        assertEquals(StatisticsConfig.PostHocMethod.NONE, preset.getPostHocMethod());
    }

    @Test
    public void focusedSingleMetricTemplatePreservesReplaceMeSentinel() throws Exception {
        StatisticsPreset preset = loadStock("focused_single_metric");

        assertNotNull(preset.getMetricFilter());
        assertEquals(1, preset.getMetricFilter().size());
        assertEquals("__REPLACE_ME__", preset.getMetricFilter().get(0));
    }

    @Test
    public void saveLoadDeleteRoundTrip() throws Exception {
        File root = temp.newFolder("stats-roundtrip-custom");
        StatisticsPresetIO io = new StatisticsPresetIO(root);

        StatisticsPreset preset = new StatisticsPreset(
                "My Custom",
                "For testing",
                StatisticsConfig.PairedMode.HEMISPHERE,
                StatisticsConfig.DistributionMode.ASSUME_NORMAL,
                StatisticsConfig.PostHocMethod.TUKEY,
                Arrays.asList("density", "count"));
        io.save(preset);

        StatisticsPreset loaded = io.load("My Custom");
        assertEquals("My Custom", loaded.getName());
        assertEquals(StatisticsConfig.PairedMode.HEMISPHERE, loaded.getPairedMode());
        assertEquals(StatisticsConfig.DistributionMode.ASSUME_NORMAL, loaded.getDistributionMode());
        assertEquals(StatisticsConfig.PostHocMethod.TUKEY, loaded.getPostHocMethod());
        assertEquals(Arrays.asList("density", "count"), loaded.getMetricFilter());

        io.delete("My Custom");
        assertFalse(new File(io.presetDirectory(), "my_custom.json").exists());
    }

    @Test
    public void presetWithNullMetricFilterRoundTripsAsNull() throws Exception {
        StatisticsPreset preset = new StatisticsPreset(
                "All metrics",
                "default",
                StatisticsConfig.PairedMode.OFF,
                StatisticsConfig.DistributionMode.AUTO,
                StatisticsConfig.PostHocMethod.BONFERRONI,
                null);

        StatisticsPreset reparsed = StatisticsPreset.fromJson(preset.toJson());
        assertNull(reparsed.getMetricFilter());
    }

    @Test
    public void presetWithEmptyMetricFilterRoundTripsAsEmptyList() throws Exception {
        StatisticsPreset preset = new StatisticsPreset(
                "Empty list",
                "test",
                StatisticsConfig.PairedMode.OFF,
                StatisticsConfig.DistributionMode.AUTO,
                StatisticsConfig.PostHocMethod.BONFERRONI,
                Collections.<String>emptyList());

        StatisticsPreset reparsed = StatisticsPreset.fromJson(preset.toJson());
        assertNotNull(reparsed.getMetricFilter());
        assertTrue(reparsed.getMetricFilter().isEmpty());
    }

    @Test
    public void presetMetricAggregationOverridesRoundTrip() throws Exception {
        Map<String, StatisticsConfig.MetricAggregation> aggregation =
                new LinkedHashMap<String, StatisticsConfig.MetricAggregation>();
        aggregation.put("ObjectsDetected", StatisticsConfig.MetricAggregation.SUM);
        aggregation.put("CellCount", StatisticsConfig.MetricAggregation.MEAN);
        StatisticsPreset preset = new StatisticsPreset(
                "Aggregation overrides",
                "test",
                StatisticsConfig.PairedMode.OFF,
                StatisticsConfig.DistributionMode.AUTO,
                StatisticsConfig.PostHocMethod.BONFERRONI,
                null,
                aggregation);

        StatisticsPreset reparsed = StatisticsPreset.fromJson(preset.toJson());

        assertEquals(aggregation, reparsed.getMetricAggregationOverrides());
        assertEquals(StatisticsConfig.MetricAggregation.SUM,
                reparsed.toConfig().metricAggregationFor("ObjectsDetected"));
        assertEquals(StatisticsConfig.MetricAggregation.MEAN,
                reparsed.toConfig().metricAggregationFor("CellCount"));
    }

    private StatisticsPreset loadStock(String stockName) throws Exception {
        File root = temp.newFolder("stats-stock-" + stockName);
        StatisticsPresetIO io = new StatisticsPresetIO(root);
        io.bootstrapStockPresets();
        return io.load(stockName);
    }
}
