package flash.pipeline.decontamination.wizard;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpectralDecontamConditionalScreensTest {

    @Test
    public void calibrationScreenRequiresControlsAndMaskGoal() {
        SpectralDecontaminationWizard.Selection selection = new SpectralDecontaminationWizard.Selection();
        selection.goal = SpectralDecontaminationWizard.DownstreamUse.CLEANED_MASK;
        selection.hasControls = false;
        assertFalse(SpectralDecontaminationWizard.derive(null, new SpectralDecontaminationWizard.AutoDetection(), selection)
                .screen4Visible);

        selection.hasControls = true;
        assertTrue(SpectralDecontaminationWizard.derive(null, new SpectralDecontaminationWizard.AutoDetection(), selection)
                .screen4Visible);
    }

    @Test
    public void strengthScreenOnlyShowsForBothContaminationTypes() {
        SpectralDecontaminationWizard.Selection selection = new SpectralDecontaminationWizard.Selection();
        selection.contaminationType = SpectralDecontaminationWizard.ContaminationType.BLEED_THROUGH;
        assertFalse(SpectralDecontaminationWizard.derive(null, new SpectralDecontaminationWizard.AutoDetection(), selection)
                .screen5Visible);

        selection.contaminationType = SpectralDecontaminationWizard.ContaminationType.BOTH;
        assertTrue(SpectralDecontaminationWizard.derive(null, new SpectralDecontaminationWizard.AutoDetection(), selection)
                .screen5Visible);
    }
}
