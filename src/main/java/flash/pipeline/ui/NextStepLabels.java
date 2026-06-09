package flash.pipeline.ui;

/**
 * Single source of truth for the "Next: &lt;destination&gt;" primary-button
 * labels used across the Set Up Configuration wizard and the embedded QC
 * dialog. Naming the next screen on each advance button lets the user see what
 * is coming instead of a generic "OK" / "Lock in & Next".
 *
 * <p>Every method here is a pure function (no Swing, no state) so the exact
 * strings users see can be unit-tested in the non-headless test JVM without
 * constructing a dialog.
 */
public final class NextStepLabels {

    private NextStepLabels() {}

    // ── Top-level setup destinations ────────────────────────────────────
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

    // ── Embedded QC stage labels ────────────────────────────────────────

    /**
     * Primary-button label for the embedded QC dialog's lock-in button. The QC
     * sequence is stage-outer / image-inner: locking in walks every image of the
     * current stage, then advances to the next stage.
     *
     * @param moreImagesSameStage true when locking in advances to another image
     *                            of the <em>same</em> stage (the screen's controls
     *                            don't change, only the image)
     * @param nextStepLabel       user-facing name of the next stage/step screen
     *                            once the current stage is finished, or
     *                            {@code null} when this is the final step
     */
    public static String qcPrimaryLabel(boolean moreImagesSameStage, String nextStepLabel) {
        if (moreImagesSameStage) {
            return "Lock in & next image";
        }
        if (nextStepLabel != null && !nextStepLabel.trim().isEmpty()) {
            return "Next: " + nextStepLabel.trim();
        }
        return "Lock in & finish";
    }
}
