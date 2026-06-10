package flash.pipeline.ui;

/**
 * Single source of truth for the "Next: &lt;destination&gt;" primary-button
 * labels used across sequential setup and analysis dialogs. Naming the next
 * screen on each advance button lets the user see what is coming instead of a
 * generic "OK" / "Lock in & Next".
 *
 * <p>Every method here is a pure function (no Swing, no state) so the exact
 * strings users see can be unit-tested in the non-headless test JVM without
 * constructing a dialog.
 */
public final class NextStepLabels {

    private static final String NEXT_PREFIX = "Next: ";

    private NextStepLabels() {}

    // Top-level setup destinations.
    public static final String SETUP            = "Next: Set up configuration";
    public static final String CHANNEL_SETUP    = "Next: Channel setup";
    public static final String NAME_CHANNELS    = "Next: Name channels & colours";
    public static final String ANALYSIS_SCOPE   = "Next: Analysis scope";
    public static final String SETTINGS_MODE    = "Next: Settings mode";
    public static final String ZSLICE_SELECTION = "Next: Z-slice selection";
    public static final String QC_IMAGES        = "Next: Quality-check images";
    public static final String QC_STAGES        = "Next: Quality-check stages";
    public static final String QC_PICK_IMAGES   = "Next: Choose images";
    public static final String QC_METADATA      = "Next: Review metadata";
    public static final String REVIEW           = "Next: Review & save";

    // Representative Figure destinations.
    public static final String ASSIGN_CONDITIONS    = "Next: Assign conditions";
    public static final String SELECT_REPRESENTATIVES = "Next: Select images";
    public static final String DISPLAY_RANGES       = "Next: Display ranges";
    public static final String ADJUST_DISPLAY_RANGES = "Next: Adjust display ranges";
    public static final String LAYOUT               = "Next: Layout";
    public static final String BUILD_FIGURE         = "Build figure";

    // Spectral Decontamination destinations.
    public static final String CONDITION_SOURCE     = "Next: Condition source";
    public static final String CONDITION_ROLES      = "Next: Condition roles";
    public static final String CORRECTION_STACK     = "Next: Correction stack";
    public static final String FULL_FORWARD_MODEL   = "Next: Full forward model";
    public static final String ENVELOPE_CORRECTION  = "Next: Envelope correction";
    public static final String ROC_THRESHOLD_SEARCH = "Next: ROC threshold search";
    public static final String SPECTRAL_PREVIEW     = "Next: Preview";
    public static final String RUN_SPECTRAL_BATCH   = "Run spectral batch";

    // Fluorescence Intensity Analysis destinations.
    public static final String ROI_THRESHOLD_SETTINGS = "Next: ROI & threshold settings";
    public static final String INTENSITY_SPATIAL_OPTIONS = "Next: Intensity-spatial options";
    public static final String RUN_INTENSITY_ANALYSIS = "Run intensity analysis";

    // 3D Object and Spatial Analysis destinations.
    public static final String PROCESS_ANALYSIS = "Next: Process analysis";
    public static final String SPATIAL_OPTIONS = "Next: Spatial Analysis options";
    public static final String RUN_3D_OBJECT_ANALYSIS = "Run 3D object analysis";
    public static final String RUN_SPATIAL_ANALYSIS = "Run spatial analysis";

    // Draw ROIs and Line Distance destinations.
    public static final String ROI_SETUP_OPTIONS = "Next: ROI setup options";
    public static final String DRAW_ROI_IMAGE = "Next: Draw ROI for image 1";
    public static final String CHECK_ROI_SET = "Next: Check ROI set";
    public static final String PREVIEW_IMPORTED_ROIS = "Next: Preview imported ROIs";
    public static final String IMPORT_ROI_SET = "Import ROI set";
    public static final String DRAW_LINE_SET = "Next: Draw line set";
    public static final String SAVE_ROI_SET = "Save ROI set";
    public static final String SAVE_LINE_SET = "Save line set";
    public static final String MEASURE_LINE_DISTANCES = "Measure line distances";

    // 3D Deconvolution destinations.
    public static final String PREVIEW_DECONVOLUTION = "Next: Preview deconvolution";
    public static final String RUN_DECONVOLUTION = "Run deconvolution";

    // Cellpose Runtime Setup destinations.
    public static final String EXISTING_CELLPOSE_INSTALL = "Next: Existing Cellpose install";
    public static final String VERIFY_CELLPOSE_INSTALL = "Verify Cellpose install";

    /**
     * The screen reached after the Settings Mode screen, which branches on the
     * z-slice flag (decided earlier) and whether any QC toggle is on (decided
     * on the Settings Mode screen itself).
     */
    public static String afterSettingsMode(boolean usesZSliceSubset,
                                           boolean anyQcToggleOn) {
        if (usesZSliceSubset) return ZSLICE_SELECTION;
        if (anyQcToggleOn)    return QC_IMAGES;
        return REVIEW;
    }

    public static String afterRepresentativeDisplayRangeChoice(boolean adjustNow) {
        return adjustNow ? ADJUST_DISPLAY_RANGES : LAYOUT;
    }

    public static String representativeDisplayRangesChannel(String channelLabel) {
        String base = destinationText(DISPLAY_RANGES);
        String channel = channelLabel == null ? "" : channelLabel.trim();
        return channel.isEmpty() ? base : base + " (" + channel + ")";
    }

    public static String afterSpectralConditionSource(boolean assignManuallySelected,
                                                      boolean useExistingConditionFileSelected,
                                                      boolean existingConditionFileAvailable) {
        boolean manualAssignmentRequired =
                assignManuallySelected
                        || (useExistingConditionFileSelected && !existingConditionFileAvailable);
        return manualAssignmentRequired ? ASSIGN_CONDITIONS : CONDITION_ROLES;
    }

    public static String afterSpectralFeatureStack(boolean includesFullForwardModel,
                                                  boolean includesEnvelopeCorrection,
                                                  boolean includesRocThresholdSearch) {
        if (includesFullForwardModel) {
            return FULL_FORWARD_MODEL;
        }
        return afterSpectralFullForwardModel(
                includesEnvelopeCorrection, includesRocThresholdSearch);
    }

    public static String afterSpectralFullForwardModel(boolean includesEnvelopeCorrection,
                                                       boolean includesRocThresholdSearch) {
        if (includesEnvelopeCorrection) {
            return ENVELOPE_CORRECTION;
        }
        return afterSpectralEnvelope(includesRocThresholdSearch);
    }

    public static String afterSpectralEnvelope(boolean includesRocThresholdSearch) {
        return includesRocThresholdSearch ? ROC_THRESHOLD_SEARCH : SPECTRAL_PREVIEW;
    }

    public static String afterIntensityMain(boolean anyBinarize,
                                            boolean roiAnalysis,
                                            boolean spatial) {
        if (anyBinarize || roiAnalysis) {
            return ROI_THRESHOLD_SETTINGS;
        }
        return spatial ? INTENSITY_SPATIAL_OPTIONS : RUN_INTENSITY_ANALYSIS;
    }

    public static String afterIntensityRoiThreshold(boolean spatial) {
        return spatial ? INTENSITY_SPATIAL_OPTIONS : RUN_INTENSITY_ANALYSIS;
    }

    public static String afterThreeDObjectMain(boolean processLength,
                                               boolean spatial) {
        if (processLength) {
            return PROCESS_ANALYSIS;
        }
        return spatial ? SPATIAL_OPTIONS : RUN_3D_OBJECT_ANALYSIS;
    }

    public static String afterThreeDObjectProcess(boolean spatial) {
        return spatial ? SPATIAL_OPTIONS : RUN_3D_OBJECT_ANALYSIS;
    }

    public static String spatialOptionsPrimaryLabel(String requestedLabel) {
        String label = requestedLabel == null ? "" : requestedLabel.trim();
        return label.isEmpty() ? RUN_SPATIAL_ANALYSIS : label;
    }

    public static String importRoiPrimaryLabel(boolean previewEnabled) {
        return previewEnabled ? PREVIEW_IMPORTED_ROIS : IMPORT_ROI_SET;
    }

    public static String afterRoiSetup(boolean createNew,
                                       int existingRoiCount,
                                       int totalImages) {
        int normalizedTotal = Math.max(0, totalImages);
        if (normalizedTotal <= 0) {
            return CHECK_ROI_SET;
        }
        int firstImageZeroBased = createNew ? 0 : Math.max(0, existingRoiCount / 2);
        if (firstImageZeroBased >= normalizedTotal) {
            return CHECK_ROI_SET;
        }
        return drawRoiImage(firstImageZeroBased + 1);
    }

    public static String drawRoiImage(int imageNumberOneBased) {
        return "Next: Draw ROI for image " + Math.max(1, imageNumberOneBased);
    }

    public static String roiFinalDestination(boolean drawLineSet) {
        return drawLineSet ? DRAW_LINE_SET : SAVE_ROI_SET;
    }

    public static String roiDrawingPrimaryLabel(int imageIndexZeroBased,
                                                int totalImages,
                                                String finalDestination) {
        if (hasMoreImages(imageIndexZeroBased, totalImages)) {
            return "Next: image " + (imageIndexZeroBased + 2);
        }
        String label = finalDestination == null ? "" : finalDestination.trim();
        return label.isEmpty() ? SAVE_ROI_SET : label;
    }

    public static String afterLineSetSelection(boolean drawingNewSet) {
        return drawingNewSet ? DRAW_LINE_SET : MEASURE_LINE_DISTANCES;
    }

    public static String lineDrawingPrimaryLabel(int imageIndexZeroBased,
                                                 int totalImages) {
        if (hasMoreImages(imageIndexZeroBased, totalImages)) {
            return "Next: image " + (imageIndexZeroBased + 2);
        }
        return SAVE_LINE_SET;
    }

    public static String afterDeconvolutionSetup(boolean previewAccepted) {
        return previewAccepted ? RUN_DECONVOLUTION : PREVIEW_DECONVOLUTION;
    }

    /**
     * Friendly short name for a ConfigQcStage.title() or the sequential QC
     * breadcrumb label used between embedded dialogs.
     */
    public static String qcStageShortName(String stageTitle) {
        String title = stageTitle == null ? "" : stageTitle.trim();
        if (title.isEmpty()) {
            return "";
        }
        String lower = title.toLowerCase();
        if ((lower.startsWith("display ranges (") || lower.startsWith("display range ("))
                && title.endsWith(")")) {
            return title;
        }
        if (lower.contains("display")) {
            return "Display range";
        }
        if (lower.contains("filter")) {
            return "Filter & parameters";
        }
        if (lower.contains("segmentation")
                || lower.contains("stardist")
                || lower.contains("cellpose")
                || lower.contains("trained rf")
                || lower.contains("classical")) {
            return "Segmentation";
        }
        if (lower.contains("particle")) {
            return "Particle size";
        }
        if (lower.contains("threshold")) {
            return "Channel threshold";
        }
        if (lower.contains("z-slice") || lower.contains("zslice")) {
            return "Z-slice selection";
        }
        return title;
    }

    /**
     * Primary-button label for the embedded QC dialog's lock-in button. The QC
     * sequence is stage-outer / image-inner: locking in walks every selected
     * image of the current stage, then advances to the next internal stage or
     * the next outer breadcrumb step.
     *
     * @param nextStepTitle title/label of the next stage or step, or
     *                      {@code null} when this is the final step
     * @param nextImageSameStep true when the next step is another image of the
     *                          same stage
     * @param nextImageIndex 1-based image index shown only when
     *                       {@code nextImageSameStep} is true
     */
    public static String qcPrimaryLabel(String nextStepTitle,
                                        boolean nextImageSameStep,
                                        int nextImageIndex) {
        String name = qcStageShortName(nextStepTitle);
        if (name.isEmpty()) {
            if (nextImageSameStep) {
                return nextImageIndex > 0
                        ? "Next: image " + nextImageIndex
                        : "Next: next image";
            }
            return "Lock in & finish";
        }
        if (nextImageSameStep) {
            return nextImageIndex > 0
                    ? "Next: " + name + " (image " + nextImageIndex + ")"
                    : "Next: " + name;
        }
        return "Next: " + name;
    }

    private static String destinationText(String nextLabel) {
        String text = nextLabel == null ? "" : nextLabel.trim();
        return text.startsWith(NEXT_PREFIX) ? text.substring(NEXT_PREFIX.length()) : text;
    }

    private static boolean hasMoreImages(int imageIndexZeroBased, int totalImages) {
        return totalImages > 0 && imageIndexZeroBased >= 0
                && imageIndexZeroBased < totalImages - 1;
    }
}
