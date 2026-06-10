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
    public void afterRepresentativeDisplayRangeChoiceNamesBranchDestination() {
        assertEquals(NextStepLabels.ADJUST_DISPLAY_RANGES,
                NextStepLabels.afterRepresentativeDisplayRangeChoice(true));
        assertEquals(NextStepLabels.LAYOUT,
                NextStepLabels.afterRepresentativeDisplayRangeChoice(false));
    }

    @Test
    public void representativeDisplayRangesChannelBuildsBreadcrumbLabel() {
        assertEquals("Display ranges",
                NextStepLabels.representativeDisplayRangesChannel(null));
        assertEquals("Display ranges (C1 - DAPI)",
                NextStepLabels.representativeDisplayRangesChannel(" C1 - DAPI "));
    }

    @Test
    public void afterSpectralConditionSourceNamesManualOrRoleDestination() {
        assertEquals(NextStepLabels.ASSIGN_CONDITIONS,
                NextStepLabels.afterSpectralConditionSource(true, false, true));
        assertEquals(NextStepLabels.ASSIGN_CONDITIONS,
                NextStepLabels.afterSpectralConditionSource(false, true, false));
        assertEquals(NextStepLabels.CONDITION_ROLES,
                NextStepLabels.afterSpectralConditionSource(false, true, true));
        assertEquals(NextStepLabels.CONDITION_ROLES,
                NextStepLabels.afterSpectralConditionSource(false, false, false));
    }

    @Test
    public void afterSpectralFeatureStackNamesNextExpertOrPreviewScreen() {
        assertEquals(NextStepLabels.FULL_FORWARD_MODEL,
                NextStepLabels.afterSpectralFeatureStack(true, true, true));
        assertEquals(NextStepLabels.ENVELOPE_CORRECTION,
                NextStepLabels.afterSpectralFeatureStack(false, true, true));
        assertEquals(NextStepLabels.ROC_THRESHOLD_SEARCH,
                NextStepLabels.afterSpectralFeatureStack(false, false, true));
        assertEquals(NextStepLabels.SPECTRAL_PREVIEW,
                NextStepLabels.afterSpectralFeatureStack(false, false, false));
    }

    @Test
    public void afterSpectralFullForwardModelNamesRemainingExpertOrPreviewScreen() {
        assertEquals(NextStepLabels.ENVELOPE_CORRECTION,
                NextStepLabels.afterSpectralFullForwardModel(true, true));
        assertEquals(NextStepLabels.ROC_THRESHOLD_SEARCH,
                NextStepLabels.afterSpectralFullForwardModel(false, true));
        assertEquals(NextStepLabels.SPECTRAL_PREVIEW,
                NextStepLabels.afterSpectralFullForwardModel(false, false));
    }

    @Test
    public void afterSpectralEnvelopeNamesRocOrPreviewScreen() {
        assertEquals(NextStepLabels.ROC_THRESHOLD_SEARCH,
                NextStepLabels.afterSpectralEnvelope(true));
        assertEquals(NextStepLabels.SPECTRAL_PREVIEW,
                NextStepLabels.afterSpectralEnvelope(false));
    }

    @Test
    public void afterIntensityMainNamesRoiThresholdSpatialOrTerminalDestination() {
        assertEquals(NextStepLabels.ROI_THRESHOLD_SETTINGS,
                NextStepLabels.afterIntensityMain(true, false, false));
        assertEquals(NextStepLabels.ROI_THRESHOLD_SETTINGS,
                NextStepLabels.afterIntensityMain(false, true, true));
        assertEquals(NextStepLabels.INTENSITY_SPATIAL_OPTIONS,
                NextStepLabels.afterIntensityMain(false, false, true));
        assertEquals(NextStepLabels.RUN_INTENSITY_ANALYSIS,
                NextStepLabels.afterIntensityMain(false, false, false));
    }

    @Test
    public void afterIntensityRoiThresholdNamesSpatialOrTerminalDestination() {
        assertEquals(NextStepLabels.INTENSITY_SPATIAL_OPTIONS,
                NextStepLabels.afterIntensityRoiThreshold(true));
        assertEquals(NextStepLabels.RUN_INTENSITY_ANALYSIS,
                NextStepLabels.afterIntensityRoiThreshold(false));
    }

    @Test
    public void afterThreeDObjectMainNamesProcessSpatialOrTerminalDestination() {
        assertEquals(NextStepLabels.PROCESS_ANALYSIS,
                NextStepLabels.afterThreeDObjectMain(true, false));
        assertEquals(NextStepLabels.PROCESS_ANALYSIS,
                NextStepLabels.afterThreeDObjectMain(true, true));
        assertEquals(NextStepLabels.SPATIAL_OPTIONS,
                NextStepLabels.afterThreeDObjectMain(false, true));
        assertEquals(NextStepLabels.RUN_3D_OBJECT_ANALYSIS,
                NextStepLabels.afterThreeDObjectMain(false, false));
    }

    @Test
    public void afterThreeDObjectProcessNamesSpatialOrTerminalDestination() {
        assertEquals(NextStepLabels.SPATIAL_OPTIONS,
                NextStepLabels.afterThreeDObjectProcess(true));
        assertEquals(NextStepLabels.RUN_3D_OBJECT_ANALYSIS,
                NextStepLabels.afterThreeDObjectProcess(false));
    }

    @Test
    public void spatialOptionsPrimaryLabelDefaultsStandaloneAndHonorsOverride() {
        assertEquals(NextStepLabels.RUN_SPATIAL_ANALYSIS,
                NextStepLabels.spatialOptionsPrimaryLabel(null));
        assertEquals(NextStepLabels.RUN_SPATIAL_ANALYSIS,
                NextStepLabels.spatialOptionsPrimaryLabel("   "));
        assertEquals(NextStepLabels.RUN_3D_OBJECT_ANALYSIS,
                NextStepLabels.spatialOptionsPrimaryLabel("  Run 3D object analysis  "));
    }

    @Test
    public void importRoiPrimaryLabelNamesPreviewOrTerminalImport() {
        assertEquals(NextStepLabels.PREVIEW_IMPORTED_ROIS,
                NextStepLabels.importRoiPrimaryLabel(true));
        assertEquals(NextStepLabels.IMPORT_ROI_SET,
                NextStepLabels.importRoiPrimaryLabel(false));
    }

    @Test
    public void afterRoiSetupNamesFirstDrawingImageOrCompletedSetCheck() {
        assertEquals("Next: Draw ROI for image 1",
                NextStepLabels.afterRoiSetup(true, 8, 4));
        assertEquals("Next: Draw ROI for image 3",
                NextStepLabels.afterRoiSetup(false, 4, 5));
        assertEquals(NextStepLabels.CHECK_ROI_SET,
                NextStepLabels.afterRoiSetup(false, 10, 5));
        assertEquals(NextStepLabels.CHECK_ROI_SET,
                NextStepLabels.afterRoiSetup(true, 0, 0));
    }

    @Test
    public void roiFinalDestinationNamesLineHandoffOrSave() {
        assertEquals(NextStepLabels.DRAW_LINE_SET,
                NextStepLabels.roiFinalDestination(true));
        assertEquals(NextStepLabels.SAVE_ROI_SET,
                NextStepLabels.roiFinalDestination(false));
    }

    @Test
    public void roiDrawingPrimaryLabelNamesNextImageUntilFinalDestination() {
        assertEquals("Next: image 2",
                NextStepLabels.roiDrawingPrimaryLabel(0, 3, NextStepLabels.SAVE_ROI_SET));
        assertEquals("Next: image 3",
                NextStepLabels.roiDrawingPrimaryLabel(1, 3, NextStepLabels.DRAW_LINE_SET));
        assertEquals(NextStepLabels.DRAW_LINE_SET,
                NextStepLabels.roiDrawingPrimaryLabel(2, 3, NextStepLabels.DRAW_LINE_SET));
        assertEquals(NextStepLabels.SAVE_ROI_SET,
                NextStepLabels.roiDrawingPrimaryLabel(0, 1, NextStepLabels.SAVE_ROI_SET));
        assertEquals(NextStepLabels.SAVE_ROI_SET,
                NextStepLabels.roiDrawingPrimaryLabel(0, 1, "   "));
    }

    @Test
    public void afterLineSetSelectionNamesDrawOrMeasureDestination() {
        assertEquals(NextStepLabels.DRAW_LINE_SET,
                NextStepLabels.afterLineSetSelection(true));
        assertEquals(NextStepLabels.MEASURE_LINE_DISTANCES,
                NextStepLabels.afterLineSetSelection(false));
    }

    @Test
    public void lineDrawingPrimaryLabelNamesNextImageUntilSave() {
        assertEquals("Next: image 2",
                NextStepLabels.lineDrawingPrimaryLabel(0, 2));
        assertEquals(NextStepLabels.SAVE_LINE_SET,
                NextStepLabels.lineDrawingPrimaryLabel(1, 2));
        assertEquals(NextStepLabels.SAVE_LINE_SET,
                NextStepLabels.lineDrawingPrimaryLabel(0, 1));
    }

    @Test
    public void afterDeconvolutionSetupRequiresPreviewUntilAccepted() {
        assertEquals(NextStepLabels.PREVIEW_DECONVOLUTION,
                NextStepLabels.afterDeconvolutionSetup(false));
        assertEquals(NextStepLabels.RUN_DECONVOLUTION,
                NextStepLabels.afterDeconvolutionSetup(true));
    }

    @Test
    public void qcStageShortNameMapsConfigStageTitles() {
        assertEquals("Display range", NextStepLabels.qcStageShortName("Display Range"));
        assertEquals("Filter & parameters", NextStepLabels.qcStageShortName("Set Filter and Parameters"));
        assertEquals("Segmentation", NextStepLabels.qcStageShortName("Segmentation Method"));
        assertEquals("Segmentation", NextStepLabels.qcStageShortName("Classical Segmentation"));
        assertEquals("Segmentation", NextStepLabels.qcStageShortName("Enhanced Classical Segmentation"));
        assertEquals("Segmentation", NextStepLabels.qcStageShortName("StarDist"));
        assertEquals("Segmentation", NextStepLabels.qcStageShortName("Cellpose"));
        assertEquals("Segmentation", NextStepLabels.qcStageShortName("Trained RF"));
        assertEquals("Particle size", NextStepLabels.qcStageShortName("Particle Size"));
        assertEquals("Channel threshold", NextStepLabels.qcStageShortName("Channel Threshold"));
        assertEquals("Z-slice selection", NextStepLabels.qcStageShortName("Z-Slice Subset"));
    }

    @Test
    public void qcStageShortNameMapsSequentialBreadcrumbLabels() {
        assertEquals("Display range", NextStepLabels.qcStageShortName("Display"));
        assertEquals("Filter & parameters", NextStepLabels.qcStageShortName("Filter"));
        assertEquals("Segmentation", NextStepLabels.qcStageShortName("Object Segmentation"));
        assertEquals("Channel threshold", NextStepLabels.qcStageShortName("Threshold"));
        assertEquals("Display ranges (DAPI)",
                NextStepLabels.qcStageShortName("Display ranges (DAPI)"));
    }

    @Test
    public void qcStageShortNameReturnsUnknownTitleTrimmed() {
        assertEquals("Custom Stage", NextStepLabels.qcStageShortName("  Custom Stage  "));
        assertEquals("", NextStepLabels.qcStageShortName(null));
    }

    @Test
    public void qcPrimaryLabelNamesNextStageWhenStageComplete() {
        assertEquals("Next: Filter & parameters",
                NextStepLabels.qcPrimaryLabel("Set Filter and Parameters", false, 0));
        assertEquals("Next: Segmentation",
                NextStepLabels.qcPrimaryLabel("Object Segmentation", false, 0));
    }

    @Test
    public void qcPrimaryLabelNamesSameStageOnNextImage() {
        assertEquals("Next: Display range (image 2)",
                NextStepLabels.qcPrimaryLabel("Display Range", true, 2));
        assertEquals("Next: Segmentation (image 3)",
                NextStepLabels.qcPrimaryLabel("StarDist", true, 3));
    }

    @Test
    public void qcPrimaryLabelFallsBackToImageWhenStageNameIsUnavailable() {
        assertEquals("Next: image 3",
                NextStepLabels.qcPrimaryLabel(null, true, 3));
    }

    @Test
    public void qcPrimaryLabelFinishesWhenNoNextStep() {
        assertEquals("Lock in & finish",
                NextStepLabels.qcPrimaryLabel(null, false, 0));
        assertEquals("Lock in & finish",
                NextStepLabels.qcPrimaryLabel("   ", false, 0));
    }

    @Test
    public void destinationConstantsUseNextPrefix() {
        assertEquals("Next: Set up configuration", NextStepLabels.SETUP);
        assertEquals("Next: Channel setup", NextStepLabels.CHANNEL_SETUP);
        assertEquals("Next: Name channels & colours", NextStepLabels.NAME_CHANNELS);
        assertEquals("Next: Analysis scope", NextStepLabels.ANALYSIS_SCOPE);
        assertEquals("Next: Settings mode", NextStepLabels.SETTINGS_MODE);
        assertEquals("Next: Z-slice selection", NextStepLabels.ZSLICE_SELECTION);
        assertEquals("Next: Quality-check images", NextStepLabels.QC_IMAGES);
        assertEquals("Next: Quality-check stages", NextStepLabels.QC_STAGES);
        assertEquals("Next: Review & save", NextStepLabels.REVIEW);
        assertEquals("Next: Assign conditions", NextStepLabels.ASSIGN_CONDITIONS);
        assertEquals("Next: Select images", NextStepLabels.SELECT_REPRESENTATIVES);
        assertEquals("Next: Display ranges", NextStepLabels.DISPLAY_RANGES);
        assertEquals("Next: Adjust display ranges", NextStepLabels.ADJUST_DISPLAY_RANGES);
        assertEquals("Next: Layout", NextStepLabels.LAYOUT);
        assertEquals("Build figure", NextStepLabels.BUILD_FIGURE);
        assertEquals("Next: Condition source", NextStepLabels.CONDITION_SOURCE);
        assertEquals("Next: Condition roles", NextStepLabels.CONDITION_ROLES);
        assertEquals("Next: Correction stack", NextStepLabels.CORRECTION_STACK);
        assertEquals("Next: Full forward model", NextStepLabels.FULL_FORWARD_MODEL);
        assertEquals("Next: Envelope correction", NextStepLabels.ENVELOPE_CORRECTION);
        assertEquals("Next: ROC threshold search", NextStepLabels.ROC_THRESHOLD_SEARCH);
        assertEquals("Next: Preview", NextStepLabels.SPECTRAL_PREVIEW);
        assertEquals("Run spectral batch", NextStepLabels.RUN_SPECTRAL_BATCH);
        assertEquals("Next: ROI & threshold settings", NextStepLabels.ROI_THRESHOLD_SETTINGS);
        assertEquals("Next: Intensity-spatial options", NextStepLabels.INTENSITY_SPATIAL_OPTIONS);
        assertEquals("Run intensity analysis", NextStepLabels.RUN_INTENSITY_ANALYSIS);
        assertEquals("Next: Process analysis", NextStepLabels.PROCESS_ANALYSIS);
        assertEquals("Next: Spatial Analysis options", NextStepLabels.SPATIAL_OPTIONS);
        assertEquals("Run 3D object analysis", NextStepLabels.RUN_3D_OBJECT_ANALYSIS);
        assertEquals("Run spatial analysis", NextStepLabels.RUN_SPATIAL_ANALYSIS);
        assertEquals("Next: ROI setup options", NextStepLabels.ROI_SETUP_OPTIONS);
        assertEquals("Next: Draw ROI for image 1", NextStepLabels.DRAW_ROI_IMAGE);
        assertEquals("Next: Check ROI set", NextStepLabels.CHECK_ROI_SET);
        assertEquals("Next: Preview imported ROIs", NextStepLabels.PREVIEW_IMPORTED_ROIS);
        assertEquals("Import ROI set", NextStepLabels.IMPORT_ROI_SET);
        assertEquals("Next: Draw line set", NextStepLabels.DRAW_LINE_SET);
        assertEquals("Save ROI set", NextStepLabels.SAVE_ROI_SET);
        assertEquals("Save line set", NextStepLabels.SAVE_LINE_SET);
        assertEquals("Measure line distances", NextStepLabels.MEASURE_LINE_DISTANCES);
        assertEquals("Next: Preview deconvolution", NextStepLabels.PREVIEW_DECONVOLUTION);
        assertEquals("Run deconvolution", NextStepLabels.RUN_DECONVOLUTION);
        assertEquals("Next: Existing Cellpose install", NextStepLabels.EXISTING_CELLPOSE_INSTALL);
        assertEquals("Verify Cellpose install", NextStepLabels.VERIFY_CELLPOSE_INSTALL);
    }
}
