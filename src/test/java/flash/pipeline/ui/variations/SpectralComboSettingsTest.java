package flash.pipeline.ui.variations;

import flash.pipeline.decontamination.CorrectionPipeline;
import flash.pipeline.decontamination.SpectralDecontaminationConfig;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

public class SpectralComboSettingsTest {

    private static SpectralDecontaminationConfig base(String... featureIds) {
        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(0);
        config.setBleedThroughChannelIndexes(Arrays.asList(Integer.valueOf(1)));
        config.setAutofluorescenceChannelIndexes(Arrays.asList(Integer.valueOf(2)));
        CorrectionPipeline pipeline = new CorrectionPipeline();
        pipeline.setFeatureIds(Arrays.asList(featureIds));
        config.setCorrectionPipeline(pipeline);
        return config;
    }

    private static double d(SpectralDecontaminationConfig cfg, String feature, String key) {
        return cfg.getFeatureSettings(feature).getDouble(key, Double.NaN);
    }

    private static int i(SpectralDecontaminationConfig cfg, String feature, String key) {
        return cfg.getFeatureSettings(feature).getInt(key, Integer.MIN_VALUE);
    }

    @Test
    public void nullComboReturnsIndependentCopy() {
        SpectralDecontaminationConfig b = base("linear_unmixing", "size_filter");
        SpectralDecontaminationConfig out = SpectralComboSettings.resolve(null, b);
        assertNotSame(b, out);
        assertEquals(b.getCorrectionPipeline().getFeatureIds(), out.getCorrectionPipeline().getFeatureIds());
    }

    @Test
    public void strengthAppliedToLinearUnmixing() {
        SpectralDecontaminationConfig b = base("linear_unmixing", "size_filter");
        ParameterCombo combo = ParameterCombo.builder()
                .put(SpectralParameterId.STRENGTH, Double.valueOf(0.5)).build();
        SpectralDecontaminationConfig out = SpectralComboSettings.resolve(combo, b);
        assertEquals(0.5, d(out, "linear_unmixing", "weight_scale"), 1e-9);
    }

    @Test
    public void fitPercentileAppliedToUnmixingOnly() {
        // FIT_PERCENTILE is the unmixing quiet-pixel percentile; it must NOT touch the AF feature
        // (which has its own axis + different default), preserving the fall-through guarantee.
        SpectralDecontaminationConfig b = base("linear_unmixing", "global_ratio_correction");
        ParameterCombo combo = ParameterCombo.builder()
                .put(SpectralParameterId.FIT_PERCENTILE, Double.valueOf(70.0)).build();
        SpectralDecontaminationConfig out = SpectralComboSettings.resolve(combo, b);
        assertEquals(70.0, d(out, "linear_unmixing", "fit_percentile"), 1e-9);
        assertTrue(out.getFeatureSettings("global_ratio_correction").getValues().isEmpty());
    }

    @Test
    public void afQuietPercentileAppliedToAutofluorescenceOnly() {
        SpectralDecontaminationConfig b = base("linear_unmixing", "local_k_correction");
        ParameterCombo combo = ParameterCombo.builder()
                .put(SpectralParameterId.AF_QUIET_PERCENTILE, Double.valueOf(60.0)).build();
        SpectralDecontaminationConfig out = SpectralComboSettings.resolve(combo, b);
        assertEquals(60.0, d(out, "local_k_correction", "quiet_target_percentile"), 1e-9);
        assertTrue(out.getFeatureSettings("linear_unmixing").getValues().isEmpty());
    }

    @Test
    public void fitAndAfQuietStayIndependentInACombinedStack() {
        SpectralDecontaminationConfig b = base("linear_unmixing", "local_k_correction");
        ParameterCombo combo = ParameterCombo.builder()
                .put(SpectralParameterId.FIT_PERCENTILE, Double.valueOf(40.0))
                .put(SpectralParameterId.AF_QUIET_PERCENTILE, Double.valueOf(90.0)).build();
        SpectralDecontaminationConfig out = SpectralComboSettings.resolve(combo, b);
        assertEquals(40.0, d(out, "linear_unmixing", "fit_percentile"), 1e-9);
        assertEquals(90.0, d(out, "local_k_correction", "quiet_target_percentile"), 1e-9);
    }

    @Test
    public void afQuietOnlyTouchesPrimaryFeatureInDualAfStack() {
        // Custom stack containing BOTH AF features: global pre-set to 60, local at its default (85).
        SpectralDecontaminationConfig b = base("global_ratio_correction", "local_k_correction");
        b.setFeatureSettings("global_ratio_correction",
                new CorrectionPipeline.Settings().putDouble("quiet_target_percentile", 60.0));
        // A seeded (un-swept) AF_QUIET_PERCENTILE row carries local-k's base default (85).
        ParameterCombo combo = ParameterCombo.builder()
                .put(SpectralParameterId.AF_QUIET_PERCENTILE, Double.valueOf(85.0)).build();
        SpectralDecontaminationConfig out = SpectralComboSettings.resolve(combo, b);
        assertEquals(85.0, d(out, "local_k_correction", "quiet_target_percentile"), 1e-9);
        // The secondary AF feature keeps its base value — fall-through preserved.
        assertEquals(60.0, d(out, "global_ratio_correction", "quiet_target_percentile"), 1e-9);
    }

    @Test
    public void afModeStructurallySwapsFeature() {
        SpectralDecontaminationConfig b = base("global_ratio_correction", "size_filter");
        ParameterCombo combo = ParameterCombo.builder()
                .put(SpectralParameterId.AF_MODE, SpectralParameterId.AF_MODE_LOCAL).build();
        SpectralDecontaminationConfig out = SpectralComboSettings.resolve(combo, b);
        assertTrue(out.getCorrectionPipeline().getFeatureIds().contains("local_k_correction"));
        assertFalse(out.getCorrectionPipeline().getFeatureIds().contains("global_ratio_correction"));
    }

    @Test
    public void afModeStructuralSwapMarksPipelineCustom() {
        SpectralDecontaminationConfig b = base("global_ratio_correction", "size_filter");
        CorrectionPipeline p = b.getCorrectionPipeline();
        p.setPresetId("broad_autofluorescence");
        b.setCorrectionPipeline(p);
        ParameterCombo combo = ParameterCombo.builder()
                .put(SpectralParameterId.AF_MODE, SpectralParameterId.AF_MODE_LOCAL).build();
        SpectralDecontaminationConfig out = SpectralComboSettings.resolve(combo, b);
        assertTrue(out.getCorrectionPipeline().getFeatureIds().contains("local_k_correction"));
        assertEquals(CorrectionPipeline.CUSTOM_PRESET_ID, out.getCorrectionPipeline().getPresetId());
    }

    @Test
    public void numericOnlyComboKeepsNamedPreset() {
        SpectralDecontaminationConfig b = base("linear_unmixing", "size_filter");
        CorrectionPipeline p = b.getCorrectionPipeline();
        p.setPresetId("bleedthrough_standard");
        b.setCorrectionPipeline(p);
        ParameterCombo combo = ParameterCombo.builder()
                .put(SpectralParameterId.STRENGTH, Double.valueOf(0.5)).build();
        SpectralDecontaminationConfig out = SpectralComboSettings.resolve(combo, b);
        assertEquals("bleedthrough_standard", out.getCorrectionPipeline().getPresetId());
    }

    @Test
    public void afWindowRadiusAppliedWhenLocalKPresent() {
        SpectralDecontaminationConfig b = base("local_k_correction", "size_filter");
        ParameterCombo combo = ParameterCombo.builder()
                .put(SpectralParameterId.AF_WINDOW_RADIUS, Integer.valueOf(4)).build();
        SpectralDecontaminationConfig out = SpectralComboSettings.resolve(combo, b);
        assertEquals(4, i(out, "local_k_correction", "window_radius"));
    }

    @Test
    public void unknownAfModeTokenKeepsBaseStack() {
        SpectralDecontaminationConfig b = base("global_ratio_correction", "size_filter");
        ParameterCombo combo = ParameterCombo.builder()
                .put(SpectralParameterId.AF_MODE, "bogus").build();
        SpectralDecontaminationConfig out = SpectralComboSettings.resolve(combo, b);
        assertTrue(out.getCorrectionPipeline().getFeatureIds().contains("global_ratio_correction"));
        assertFalse(out.getCorrectionPipeline().getFeatureIds().contains("local_k_correction"));
    }

    @Test
    public void maskPercentileSetsPercentileModeAndValue() {
        SpectralDecontaminationConfig b = base("linear_unmixing", "threshold_corrected_target");
        ParameterCombo combo = ParameterCombo.builder()
                .put(SpectralParameterId.MASK_PERCENTILE, Double.valueOf(95.0)).build();
        SpectralDecontaminationConfig out = SpectralComboSettings.resolve(combo, b);
        assertEquals("percentile",
                out.getFeatureSettings("threshold_corrected_target").get("threshold_mode", ""));
        assertEquals(95.0, d(out, "threshold_corrected_target", "threshold_percentile"), 1e-9);
    }

    @Test
    public void maskPercentileLeavesNonPercentileThresholdModeUntouched() {
        // A base stack with a fixed-mode threshold must NOT be silently switched to percentile by
        // an un-swept (seeded) MASK_PERCENTILE row.
        SpectralDecontaminationConfig b = base("linear_unmixing", "threshold_corrected_target");
        b.setFeatureSettings("threshold_corrected_target",
                new CorrectionPipeline.Settings().put("threshold_mode", "fixed").putDouble("threshold_value", 100.0));
        ParameterCombo combo = ParameterCombo.builder()
                .put(SpectralParameterId.MASK_PERCENTILE, Double.valueOf(95.0)).build();
        SpectralDecontaminationConfig out = SpectralComboSettings.resolve(combo, b);
        assertEquals("fixed", out.getFeatureSettings("threshold_corrected_target").get("threshold_mode", ""));
        assertEquals(100.0, d(out, "threshold_corrected_target", "threshold_value"), 1e-9);
        assertTrue(Double.isNaN(d(out, "threshold_corrected_target", "threshold_percentile")));
    }

    @Test
    public void sizeFilterMinMaxApplied() {
        SpectralDecontaminationConfig b = base("linear_unmixing", "threshold_corrected_target", "size_filter");
        ParameterCombo combo = ParameterCombo.builder()
                .put(SpectralParameterId.SIZE_MIN, Integer.valueOf(10))
                .put(SpectralParameterId.SIZE_MAX, Integer.valueOf(500)).build();
        SpectralDecontaminationConfig out = SpectralComboSettings.resolve(combo, b);
        assertEquals(10, i(out, "size_filter", "min_size_voxels"));
        assertEquals(500, i(out, "size_filter", "max_size_voxels"));
    }

    @Test
    public void axisIgnoredWhenFeatureAbsent() {
        SpectralDecontaminationConfig b = base("linear_unmixing");
        ParameterCombo combo = ParameterCombo.builder()
                .put(SpectralParameterId.SIZE_MIN, Integer.valueOf(10)).build();
        SpectralDecontaminationConfig out = SpectralComboSettings.resolve(combo, b);
        assertTrue(out.getFeatureSettings("size_filter").getValues().isEmpty());
    }

    @Test
    public void baseValueForReflectsStack() {
        assertEquals(SpectralParameterId.AF_MODE_LOCAL,
                SpectralComboSettings.baseValueFor(base("local_k_correction"), SpectralParameterId.AF_MODE));
        assertEquals(SpectralParameterId.AF_MODE_GLOBAL,
                SpectralComboSettings.baseValueFor(base("global_ratio_correction"), SpectralParameterId.AF_MODE));
        assertEquals(Double.valueOf(1.0),
                SpectralComboSettings.baseValueFor(base("linear_unmixing"), SpectralParameterId.STRENGTH));
    }

    @Test
    public void parameterIdMetadata() {
        assertEquals(ParameterKey.ValueKind.NUMBER, SpectralParameterId.STRENGTH.valueKind());
        assertEquals(ParameterKey.ValueKind.STRING, SpectralParameterId.AF_MODE.valueKind());
        assertEquals(SpectralParameterId.STRENGTH, SpectralParameterId.fromStableKey("unmix_strength"));
        assertEquals(SpectralParameterId.AF_MODE, SpectralParameterId.fromStableKey("AF_MODE"));
    }
}
