package flash.pipeline.analyses.wizard;

import flash.pipeline.analyses.StatisticsConfig;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class StatisticsWizardTest {

    @Test
    public void emptyAnswersReproduceEngineDefaults() {
        StatisticsConfig defaults = new StatisticsConfig();
        StatisticsConfig derived = StatisticsWizard.deriveConfig(
                new LinkedHashMap<String, Object>(),
                new AggregationConfig(),
                Collections.<String>emptyList());

        assertEquals(defaults.pairedMode, derived.pairedMode);
        assertEquals(defaults.distributionMode, derived.distributionMode);
        assertEquals(defaults.postHocMethod, derived.postHocMethod);
        assertNull(derived.metricFilter);
    }

    @Test
    public void independentAutomaticBonferroniAllMetricsMatchesDefaultConfig() {
        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("design.choice", StatisticsWizard.DESIGN_INDEPENDENT);
        answers.put("distribution.choice", StatisticsWizard.DIST_AUTO);
        answers.put("posthoc.choice", StatisticsWizard.POSTHOC_BONFERRONI);
        answers.put("metric.scope", StatisticsWizard.METRIC_ALL);

        StatisticsConfig defaults = new StatisticsConfig();
        StatisticsConfig derived = StatisticsWizard.deriveConfig(
                answers, hemisphereAggregation(), Collections.<String>emptyList());

        assertEquals(defaults.pairedMode, derived.pairedMode);
        assertEquals(defaults.distributionMode, derived.distributionMode);
        assertEquals(defaults.postHocMethod, derived.postHocMethod);
        assertNull(derived.metricFilter);
    }

    @Test
    public void pairedLhVsRhDesignMapsToHemispherePairing() {
        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("design.choice", StatisticsWizard.DESIGN_PAIRED_LH_RH);

        StatisticsConfig derived = StatisticsWizard.deriveConfig(
                answers, hemisphereAggregation(), Collections.<String>emptyList());

        assertEquals(StatisticsConfig.PairedMode.HEMISPHERE, derived.pairedMode);
    }

    @Test
    public void pairedAcrossRegionsDesignMapsToRegionPairing() {
        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("design.choice", StatisticsWizard.DESIGN_PAIRED_REGIONS);

        StatisticsConfig derived = StatisticsWizard.deriveConfig(
                answers, hemisphereAggregation(), Collections.<String>emptyList());

        assertEquals(StatisticsConfig.PairedMode.REGION, derived.pairedMode);
    }

    @Test
    public void repeatedMeasuresSentinelMapsToSessionPairing() {
        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("design.choice", StatisticsWizard.DESIGN_PAIRED_REPEATED);

        StatisticsConfig derived = StatisticsWizard.deriveConfig(
                answers, hemisphereAggregation(), Collections.<String>emptyList());

        assertEquals(StatisticsConfig.PairedMode.SESSION, derived.pairedMode);
    }

    @Test
    public void distributionAssumeNormalDisablesDunns() {
        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("distribution.choice", StatisticsWizard.DIST_NORMAL);
        answers.put("posthoc.choice", StatisticsWizard.POSTHOC_DUNNS);

        StatisticsConfig derived = StatisticsWizard.deriveConfig(
                answers, new AggregationConfig(), Collections.<String>emptyList());

        assertEquals(StatisticsConfig.DistributionMode.ASSUME_NORMAL, derived.distributionMode);
        assertEquals(StatisticsConfig.PostHocMethod.BONFERRONI, derived.postHocMethod);
    }

    @Test
    public void distributionAssumeSkewedDisablesTukey() {
        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("distribution.choice", StatisticsWizard.DIST_SKEWED);
        answers.put("posthoc.choice", StatisticsWizard.POSTHOC_TUKEY);

        StatisticsConfig derived = StatisticsWizard.deriveConfig(
                answers, new AggregationConfig(), Collections.<String>emptyList());

        assertEquals(StatisticsConfig.DistributionMode.ASSUME_SKEWED, derived.distributionMode);
        assertEquals(StatisticsConfig.PostHocMethod.BONFERRONI, derived.postHocMethod);
    }

    @Test
    public void tukeyPostHocSurvivesWhenAuto() {
        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("distribution.choice", StatisticsWizard.DIST_AUTO);
        answers.put("posthoc.choice", StatisticsWizard.POSTHOC_TUKEY);

        StatisticsConfig derived = StatisticsWizard.deriveConfig(
                answers, new AggregationConfig(), Collections.<String>emptyList());

        assertEquals(StatisticsConfig.PostHocMethod.TUKEY, derived.postHocMethod);
    }

    @Test
    public void dunnsPostHocSurvivesWhenAssumeSkewed() {
        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("distribution.choice", StatisticsWizard.DIST_SKEWED);
        answers.put("posthoc.choice", StatisticsWizard.POSTHOC_DUNNS);

        StatisticsConfig derived = StatisticsWizard.deriveConfig(
                answers, new AggregationConfig(), Collections.<String>emptyList());

        assertEquals(StatisticsConfig.PostHocMethod.DUNNS, derived.postHocMethod);
    }

    @Test
    public void noneRawPValuesPostHocPasses() {
        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("posthoc.choice", StatisticsWizard.POSTHOC_NONE);

        StatisticsConfig derived = StatisticsWizard.deriveConfig(
                answers, new AggregationConfig(), Collections.<String>emptyList());

        assertEquals(StatisticsConfig.PostHocMethod.NONE, derived.postHocMethod);
    }

    @Test
    public void onlySelectedMetricsBuildsFilterFromToggles() {
        List<String> available = Arrays.asList("count", "density", "mean_intensity");
        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("metric.scope", StatisticsWizard.METRIC_SELECTED);
        answers.put("metric.0", Boolean.FALSE);
        answers.put("metric.1", Boolean.TRUE);
        answers.put("metric.2", Boolean.TRUE);

        StatisticsConfig derived = StatisticsWizard.deriveConfig(answers, new AggregationConfig(), available);

        assertNotNull(derived.metricFilter);
        assertArrayEquals(new String[]{"density", "mean_intensity"},
                derived.metricFilter.toArray(new String[0]));
    }

    @Test
    public void freeformMetricsParseAsCommaSeparatedList() {
        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("metric.scope", StatisticsWizard.METRIC_SELECTED);
        answers.put("metric.freeform", " density , mean_intensity ");

        StatisticsConfig derived = StatisticsWizard.deriveConfig(
                answers, new AggregationConfig(), Collections.<String>emptyList());

        assertNotNull(derived.metricFilter);
        assertArrayEquals(new String[]{"density", "mean_intensity"},
                derived.metricFilter.toArray(new String[0]));
    }

    @Test
    public void onlySelectedWithNoSelectionsAndEmptyFreeformReturnsNullFilter() {
        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("metric.scope", StatisticsWizard.METRIC_SELECTED);

        StatisticsConfig derived = StatisticsWizard.deriveConfig(
                answers, new AggregationConfig(), Arrays.asList("count", "density"));

        assertNull(derived.metricFilter);
    }

    @Test
    public void allMetricsScopeIgnoresMetricToggles() {
        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("metric.scope", StatisticsWizard.METRIC_ALL);
        answers.put("metric.0", Boolean.TRUE);
        answers.put("metric.1", Boolean.TRUE);

        StatisticsConfig derived = StatisticsWizard.deriveConfig(
                answers, new AggregationConfig(), Arrays.asList("count", "density"));

        assertNull(derived.metricFilter);
    }

    @Test
    public void postHocOptionsHideTukeyWhenAssumeSkewed() {
        String[] options = StatisticsWizard.postHocOptions(
                StatisticsConfig.DistributionMode.ASSUME_SKEWED);
        List<String> list = Arrays.asList(options);
        assertTrue(list.contains(StatisticsWizard.POSTHOC_BONFERRONI));
        assertTrue(list.contains(StatisticsWizard.POSTHOC_DUNNS));
        assertTrue(list.contains(StatisticsWizard.POSTHOC_NONE));
        assertEquals(-1, list.indexOf(StatisticsWizard.POSTHOC_TUKEY));
    }

    @Test
    public void postHocOptionsHideDunnsWhenAssumeNormal() {
        String[] options = StatisticsWizard.postHocOptions(
                StatisticsConfig.DistributionMode.ASSUME_NORMAL);
        List<String> list = Arrays.asList(options);
        assertTrue(list.contains(StatisticsWizard.POSTHOC_BONFERRONI));
        assertTrue(list.contains(StatisticsWizard.POSTHOC_TUKEY));
        assertTrue(list.contains(StatisticsWizard.POSTHOC_NONE));
        assertEquals(-1, list.indexOf(StatisticsWizard.POSTHOC_DUNNS));
    }

    @Test
    public void postHocOptionsKeepBothWhenAuto() {
        String[] options = StatisticsWizard.postHocOptions(
                StatisticsConfig.DistributionMode.AUTO);
        List<String> list = Arrays.asList(options);
        assertTrue(list.contains(StatisticsWizard.POSTHOC_TUKEY));
        assertTrue(list.contains(StatisticsWizard.POSTHOC_DUNNS));
    }

    @Test
    public void hemisphereAggregationClearsHemisphereHint() {
        AggregationConfig agg = hemisphereAggregation();
        assertEquals("",
                StatisticsWizard.pairedDesignHint(StatisticsWizard.DESIGN_PAIRED_LH_RH, agg));
    }

    @Test
    public void animalAggregationFlagsHemisphereHint() {
        AggregationConfig agg = new AggregationConfig();
        assertEquals("(requires per-hemisphere aggregation)",
                StatisticsWizard.pairedDesignHint(StatisticsWizard.DESIGN_PAIRED_LH_RH, agg));
    }

    @Test
    public void regionAggregationClearsRegionHint() {
        AggregationConfig agg = new AggregationConfig();
        agg.setGranularity(AggregationConfig.Granularity.REGION);
        assertEquals("",
                StatisticsWizard.pairedDesignHint(StatisticsWizard.DESIGN_PAIRED_REGIONS, agg));
    }

    @Test
    public void fromPresetReproducesPresetChoices() {
        StatisticsPreset preset = new StatisticsPreset(
                "Tukey on multi-group", null,
                StatisticsConfig.PairedMode.OFF,
                StatisticsConfig.DistributionMode.ASSUME_NORMAL,
                StatisticsConfig.PostHocMethod.TUKEY,
                Arrays.asList("density", "count"));

        StatisticsConfig cfg = StatisticsWizard.fromPreset(preset);

        assertEquals(StatisticsConfig.PairedMode.OFF, cfg.pairedMode);
        assertEquals(StatisticsConfig.DistributionMode.ASSUME_NORMAL, cfg.distributionMode);
        assertEquals(StatisticsConfig.PostHocMethod.TUKEY, cfg.postHocMethod);
        assertEquals(Arrays.asList("density", "count"), cfg.metricFilter);
    }

    @Test
    public void fromPresetWithNullReturnsNewDefaultConfig() {
        StatisticsConfig cfg = StatisticsWizard.fromPreset(null);
        StatisticsConfig defaults = new StatisticsConfig();
        assertEquals(defaults.pairedMode, cfg.pairedMode);
        assertEquals(defaults.distributionMode, cfg.distributionMode);
        assertEquals(defaults.postHocMethod, cfg.postHocMethod);
        assertNull(cfg.metricFilter);
    }

    private static AggregationConfig hemisphereAggregation() {
        AggregationConfig agg = new AggregationConfig();
        agg.setGranularity(AggregationConfig.Granularity.HEMISPHERE);
        return agg;
    }
}
