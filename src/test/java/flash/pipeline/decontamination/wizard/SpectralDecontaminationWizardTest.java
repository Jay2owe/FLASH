package flash.pipeline.decontamination.wizard;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.decontamination.SpectralDecontaminationConfig;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpectralDecontaminationWizardTest {

    @Test
    public void combinedStandardCleanedImageUsesCanonicalOrder() {
        SpectralDecontaminationWizard.Selection selection = baseSelection();
        selection.contaminationType = SpectralDecontaminationWizard.ContaminationType.BOTH;
        selection.goal = SpectralDecontaminationWizard.DownstreamUse.CLEANED_IMAGE;
        selection.strength = SpectralDecontaminationWizard.Strength.STANDARD;
        selection.bleedThroughChannels.add(Integer.valueOf(2));
        selection.autofluorescenceChannels.add(Integer.valueOf(3));

        SpectralDecontaminationWizard.DerivedConfig derived =
                SpectralDecontaminationWizard.derive(binConfig(), auto(), selection);

        assertEquals(SpectralDecontaminationConfig.Goal.CREATE_CLEANED_IMAGE, derived.config.getGoal());
        assertEquals(Arrays.asList("saturation_exclusion", "linear_unmixing",
                "threshold_corrected_target", "size_filter"),
                derived.config.getCorrectionPipeline().getFeatureIds());
        assertFalse(derived.config.getCorrectionPipeline().isExpertMode());
    }

    @Test
    public void rocCalibrationReplacesManualThreshold() {
        SpectralDecontaminationWizard.Selection selection = baseSelection();
        selection.contaminationType = SpectralDecontaminationWizard.ContaminationType.BROAD_AF;
        selection.goal = SpectralDecontaminationWizard.DownstreamUse.CLEANED_MASK;
        selection.calibration = SpectralDecontaminationWizard.Calibration.ROC;
        selection.hasControls = true;
        selection.autofluorescenceChannels.add(Integer.valueOf(3));

        List<String> stack = SpectralDecontaminationWizard.derive(binConfig(), auto(), selection)
                .config.getCorrectionPipeline().getFeatureIds();

        assertTrue(stack.contains("roc_threshold_search"));
        assertFalse(stack.contains("threshold_corrected_target"));
    }

    @Test
    public void aggressiveCombinedUsesForwardModelAndExpertMode() {
        SpectralDecontaminationWizard.Selection selection = baseSelection();
        selection.contaminationType = SpectralDecontaminationWizard.ContaminationType.BOTH;
        selection.strength = SpectralDecontaminationWizard.Strength.AGGRESSIVE;
        selection.bleedThroughChannels.add(Integer.valueOf(2));
        selection.autofluorescenceChannels.add(Integer.valueOf(3));

        SpectralDecontaminationConfig config =
                SpectralDecontaminationWizard.derive(binConfig(), auto(), selection).config;

        assertTrue(config.getCorrectionPipeline().isExpertMode());
        assertEquals(Arrays.asList("saturation_exclusion", "full_forward_model",
                "threshold_corrected_target", "size_filter"),
                config.getCorrectionPipeline().getFeatureIds());
    }

    @Test
    public void saturationAndSizeFilterCanBeDisabled() {
        SpectralDecontaminationWizard.Selection selection = baseSelection();
        selection.contaminationType = SpectralDecontaminationWizard.ContaminationType.BLEED_THROUGH;
        selection.saturationExclusion = false;
        selection.sizeFilter = false;
        selection.bleedThroughChannels.add(Integer.valueOf(2));

        assertEquals(Arrays.asList("linear_unmixing", "threshold_corrected_target"),
                SpectralDecontaminationWizard.derive(binConfig(), auto(), selection)
                        .config.getCorrectionPipeline().getFeatureIds());
    }

    private static SpectralDecontaminationWizard.Selection baseSelection() {
        SpectralDecontaminationWizard.Selection selection = new SpectralDecontaminationWizard.Selection();
        selection.targetChannelIndex = 1;
        selection.goal = SpectralDecontaminationWizard.DownstreamUse.CLEANED_IMAGE;
        selection.saturationExclusion = true;
        selection.sizeFilter = true;
        selection.minimumVoxels = 50;
        return selection;
    }

    private static SpectralDecontaminationWizard.AutoDetection auto() {
        SpectralDecontaminationWizard.AutoDetection auto = new SpectralDecontaminationWizard.AutoDetection();
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
