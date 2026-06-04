package flash.pipeline.decontamination.wizard;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.decontamination.SpectralDecontaminationConfig;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpectralDecontaminationSetupTest {

    @Test
    public void combinedStandardCleanedImageUsesCanonicalOrder() {
        SpectralDecontaminationSetup.Selection selection = baseSelection();
        selection.contaminationType = SpectralDecontaminationSetup.ContaminationType.BOTH;
        selection.goal = SpectralDecontaminationSetup.DownstreamUse.CLEANED_IMAGE;
        selection.strength = SpectralDecontaminationSetup.Strength.STANDARD;
        selection.bleedThroughChannels.add(Integer.valueOf(2));
        selection.autofluorescenceChannels.add(Integer.valueOf(3));

        SpectralDecontaminationSetup.DerivedConfig derived =
                SpectralDecontaminationSetup.derive(binConfig(), auto(), selection);

        assertEquals(SpectralDecontaminationConfig.Goal.CREATE_CLEANED_IMAGE, derived.config.getGoal());
        assertEquals(Arrays.asList("saturation_exclusion", "linear_unmixing",
                "threshold_corrected_target", "size_filter"),
                derived.config.getCorrectionPipeline().getFeatureIds());
        assertFalse(derived.config.getCorrectionPipeline().isExpertMode());
    }

    @Test
    public void rocCalibrationReplacesManualThreshold() {
        SpectralDecontaminationSetup.Selection selection = baseSelection();
        selection.contaminationType = SpectralDecontaminationSetup.ContaminationType.BROAD_AF;
        selection.goal = SpectralDecontaminationSetup.DownstreamUse.CLEANED_MASK;
        selection.calibration = SpectralDecontaminationSetup.Calibration.ROC;
        selection.hasControls = true;
        selection.autofluorescenceChannels.add(Integer.valueOf(3));

        List<String> stack = SpectralDecontaminationSetup.derive(binConfig(), auto(), selection)
                .config.getCorrectionPipeline().getFeatureIds();

        assertTrue(stack.contains("roc_threshold_search"));
        assertFalse(stack.contains("threshold_corrected_target"));
    }

    @Test
    public void aggressiveCombinedUsesForwardModelAndExpertMode() {
        SpectralDecontaminationSetup.Selection selection = baseSelection();
        selection.contaminationType = SpectralDecontaminationSetup.ContaminationType.BOTH;
        selection.strength = SpectralDecontaminationSetup.Strength.AGGRESSIVE;
        selection.bleedThroughChannels.add(Integer.valueOf(2));
        selection.autofluorescenceChannels.add(Integer.valueOf(3));

        SpectralDecontaminationConfig config =
                SpectralDecontaminationSetup.derive(binConfig(), auto(), selection).config;

        assertTrue(config.getCorrectionPipeline().isExpertMode());
        assertEquals(Arrays.asList("saturation_exclusion", "full_forward_model",
                "threshold_corrected_target", "size_filter"),
                config.getCorrectionPipeline().getFeatureIds());
    }

    @Test
    public void saturationAndSizeFilterCanBeDisabled() {
        SpectralDecontaminationSetup.Selection selection = baseSelection();
        selection.contaminationType = SpectralDecontaminationSetup.ContaminationType.BLEED_THROUGH;
        selection.saturationExclusion = false;
        selection.sizeFilter = false;
        selection.bleedThroughChannels.add(Integer.valueOf(2));

        assertEquals(Arrays.asList("linear_unmixing", "threshold_corrected_target"),
                SpectralDecontaminationSetup.derive(binConfig(), auto(), selection)
                        .config.getCorrectionPipeline().getFeatureIds());
    }

    private static SpectralDecontaminationSetup.Selection baseSelection() {
        SpectralDecontaminationSetup.Selection selection = new SpectralDecontaminationSetup.Selection();
        selection.targetChannelIndex = 1;
        selection.goal = SpectralDecontaminationSetup.DownstreamUse.CLEANED_IMAGE;
        selection.saturationExclusion = true;
        selection.sizeFilter = true;
        selection.minimumVoxels = 50;
        return selection;
    }

    private static SpectralDecontaminationSetup.AutoDetection auto() {
        SpectralDecontaminationSetup.AutoDetection auto = new SpectralDecontaminationSetup.AutoDetection();
        auto.targetChannelIndex = 1;
        return auto;
    }

    private static BinConfig binConfig() {
        BinConfig config = new BinConfig();
        config.channelNames.addAll(Arrays.asList("DAPI", "IBA1", "Abeta", "405"));
        config.channelSizes.addAll(Arrays.asList("100-Infinity", "50-Infinity", "25-Infinity", "50-Infinity"));
        return config;
    }
}
