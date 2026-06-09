package flash.pipeline.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Pure-helper tests for the "Next: &lt;destination&gt;" button labels. These
 * assert the exact strings users see and lock the convention against
 * regressions. No dialog is constructed (the test JVM is not headless).
 */
public class NextStepLabelsTest {

    @Test
    public void afterSettingsModePrefersZSliceWhenSubsetRequested() {
        assertEquals(NextStepLabels.ZSLICE_SELECTION,
                NextStepLabels.afterSettingsMode(true, true));
        assertEquals(NextStepLabels.ZSLICE_SELECTION,
                NextStepLabels.afterSettingsMode(true, false));
    }

    @Test
    public void afterSettingsModeGoesToQcWhenAnythingToggled() {
        assertEquals(NextStepLabels.QC_IMAGES,
                NextStepLabels.afterSettingsMode(false, true));
    }

    @Test
    public void afterSettingsModeGoesToReviewWhenNothingToggled() {
        assertEquals(NextStepLabels.REVIEW,
                NextStepLabels.afterSettingsMode(false, false));
    }

    @Test
    public void qcPrimaryLabelShowsNextImageWhenMoreImagesOfSameStage() {
        assertEquals("Lock in & next image",
                NextStepLabels.qcPrimaryLabel(true, "Filter"));
        assertEquals("Lock in & next image",
                NextStepLabels.qcPrimaryLabel(true, null));
    }

    @Test
    public void qcPrimaryLabelNamesNextStepWhenStageComplete() {
        assertEquals("Next: Filter",
                NextStepLabels.qcPrimaryLabel(false, "Filter"));
        assertEquals("Next: Object Segmentation",
                NextStepLabels.qcPrimaryLabel(false, "Object Segmentation"));
        assertEquals("Next: StarDist",
                NextStepLabels.qcPrimaryLabel(false, "  StarDist  "));
    }

    @Test
    public void qcPrimaryLabelFinishesWhenNoNextStep() {
        assertEquals("Lock in & finish",
                NextStepLabels.qcPrimaryLabel(false, null));
        assertEquals("Lock in & finish",
                NextStepLabels.qcPrimaryLabel(false, "   "));
    }

    @Test
    public void destinationConstantsUseNextPrefix() {
        assertEquals("Next: Analysis scope", NextStepLabels.ANALYSIS_SCOPE);
        assertEquals("Next: Settings mode", NextStepLabels.SETTINGS_MODE);
        assertEquals("Next: Name channels & colours", NextStepLabels.NAME_CHANNELS);
        assertEquals("Next: Choose images", NextStepLabels.QC_PICK_IMAGES);
        assertEquals("Next: Review metadata", NextStepLabels.QC_METADATA);
    }
}
