package flash.pipeline.decontamination;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CorrectionPipelineTest {

    private final CorrectionFeatureRegistry registry = CorrectionFeatureRegistry.getDefault();

    @Test
    public void basicPresetValidWithContaminantChannels() {
        SpectralDecontaminationConfig config = baseConfig();
        config.setBleedThroughChannelIndexes(indexes(1));

        CorrectionPipeline pipeline = new CorrectionPipeline();
        pipeline.setPresetId(CorrectionFeatureRegistry.PRESET_BASIC);
        pipeline.setFeatureIds(strings(
                "linear_unmixing",
                "threshold_corrected_target",
                "size_filter"));

        assertTrue(pipeline.validate(registry, config, false).isEmpty());
    }

    @Test
    public void sizeFilterBeforeMaskIsRejected() {
        SpectralDecontaminationConfig config = baseConfig();
        config.setBleedThroughChannelIndexes(indexes(1));

        CorrectionPipeline pipeline = new CorrectionPipeline();
        pipeline.setFeatureIds(strings("size_filter"));

        List<String> errors = pipeline.validate(registry, config, false);

        assertTrue(contains(errors, "Size filter: Mask is not available yet."));
    }

    @Test
    public void secondThresholdFeatureRequiresExpertMode() {
        SpectralDecontaminationConfig config = baseConfig();
        config.setBleedThroughChannelIndexes(indexes(1));
        config.setAutofluorescenceChannelIndexes(indexes(2));

        CorrectionPipeline pipeline = new CorrectionPipeline();
        pipeline.setFeatureIds(strings(
                "local_k_correction",
                "quiet_channel_gate",
                "threshold_corrected_target"));

        List<String> errors = pipeline.validate(registry, config, false);

        assertTrue(contains(errors,
                "Threshold corrected target: Only one threshold-based mask feature is allowed unless expert mode is enabled."));
    }

    @Test
    public void expertOnlyFeaturesHiddenUntilExpertModeEnabled() {
        SpectralDecontaminationConfig config = baseConfig();
        config.setBleedThroughChannelIndexes(indexes(1));
        config.setAutofluorescenceChannelIndexes(indexes(2));

        List<CorrectionFeature> hidden = registry.getAvailableNextFeatures(
                new ArrayList<String>(), config, false, false);
        List<CorrectionFeature> shown = registry.getAvailableNextFeatures(
                new ArrayList<String>(), config, true, false);

        assertFalse(hasFeature(hidden, "Full forward model"));
        assertTrue(hasFeature(shown, "Full forward model"));
    }

    @Test
    public void autofluorescencePresetValidWithAutofluorescenceChannels() {
        SpectralDecontaminationConfig config = baseConfig();
        config.setAutofluorescenceChannelIndexes(indexes(1));

        CorrectionPipeline pipeline = new CorrectionPipeline();
        pipeline.setPresetId(CorrectionFeatureRegistry.PRESET_AUTOFLUORESCENCE);
        pipeline.setFeatureIds(registry.getPreset(CorrectionFeatureRegistry.PRESET_AUTOFLUORESCENCE).getFeatureIds());

        assertTrue(pipeline.validate(registry, config, false).isEmpty());
    }

    @Test
    public void vetoMasksRequiresEarlierVetoMask() {
        SpectralDecontaminationConfig config = baseConfig();
        config.setBleedThroughChannelIndexes(indexes(1));

        CorrectionPipeline pipeline = new CorrectionPipeline();
        pipeline.setExpertMode(true);
        pipeline.setFeatureIds(strings(
                "linear_unmixing",
                "threshold_corrected_target",
                "veto_masks"));

        List<String> errors = pipeline.validate(registry, config, false);

        assertTrue(contains(errors, "Veto masks: Add a veto-mask feature earlier in the stack."));
    }

    @Test
    public void expertPresetValidWithBleedAndAutofluorescenceChannels() {
        SpectralDecontaminationConfig config = baseConfig();
        config.setBleedThroughChannelIndexes(indexes(1, 2));
        config.setAutofluorescenceChannelIndexes(indexes(3));

        CorrectionPipeline pipeline = new CorrectionPipeline();
        pipeline.setPresetId(CorrectionFeatureRegistry.PRESET_EXPERT);
        pipeline.setExpertMode(true);
        pipeline.setFeatureIds(registry.getPreset(CorrectionFeatureRegistry.PRESET_EXPERT).getFeatureIds());

        assertTrue(pipeline.validate(registry, config, false).isEmpty());
    }

    private SpectralDecontaminationConfig baseConfig() {
        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(0);
        config.setGoal(SpectralDecontaminationConfig.Goal.CREATE_CLEANED_MASK);
        return config;
    }

    private static boolean hasFeature(List<CorrectionFeature> features, String displayName) {
        for (CorrectionFeature feature : features) {
            if (displayName.equals(feature.getDisplayName())) return true;
        }
        return false;
    }

    private static boolean contains(List<String> errors, String expected) {
        for (String error : errors) {
            if (expected.equals(error)) return true;
        }
        return false;
    }

    private static List<Integer> indexes(int... values) {
        List<Integer> indexes = new ArrayList<Integer>();
        for (int value : values) {
            indexes.add(Integer.valueOf(value));
        }
        return indexes;
    }

    private static List<String> strings(String... values) {
        List<String> strings = new ArrayList<String>();
        for (String value : values) {
            strings.add(value);
        }
        return strings;
    }
}
