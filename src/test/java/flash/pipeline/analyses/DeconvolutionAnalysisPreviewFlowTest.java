package flash.pipeline.analyses;

import flash.pipeline.deconv.engine.Algorithm;
import flash.pipeline.deconv.psf.PsfModel;
import flash.pipeline.deconv.psf.ScopeModality;
import flash.pipeline.deconv.qc.DeconvPreviewDialog;
import flash.pipeline.intelligence.MetadataDiagnostics;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Stage 04 coverage: drives the accept / reconfigure / cancel / stale flow through the same
 * primitives the setup dialog and batch launch use ({@code previewFingerprint} +
 * {@link DeconvolutionAnalysis.PreviewState}), so the "accepted preview skips the duplicate
 * pre-batch preview, edits re-trigger it" contract is locked.
 *
 * <p>The Swing wiring (footer button, {@code runChildWorkflow}, {@code closeWithAction("cancel")})
 * cannot be driven headlessly here; it is covered by compilation and manual Fiji smoke-testing.
 */
public class DeconvolutionAnalysisPreviewFlowTest {

    private static final int CROP = 256;

    private final DeconvolutionAnalysis analysis = new DeconvolutionAnalysis();

    /** Mirrors the setup preview button: only RUN_FULL_BATCH records acceptance. */
    private void applyPreviewDecision(DeconvolutionAnalysis.PreviewState state,
                                      DeconvPreviewDialog.Decision decision,
                                      String fingerprint) {
        if (decision == DeconvPreviewDialog.Decision.RUN_FULL_BATCH) {
            state.accept(fingerprint);
        }
        // RECONFIGURE / CANCEL: no acceptance recorded.
    }

    /** Mirrors the OK path: previewAccepted iff the accepted fingerprint still matches. */
    private boolean previewAcceptedOnOk(DeconvolutionAnalysis.PreviewState state,
                                        DeconvolutionAnalysis.RunSettings settings,
                                        DeconvolutionAnalysis.SeriesJob job) {
        int channel = firstSelected(settings.selectedChannels);
        return state.matches(analysis.previewFingerprint(settings, job, channel, CROP));
    }

    @Test
    public void acceptedPreviewWithUnchangedSettingsSkipsDuplicatePreview() {
        DeconvolutionAnalysis.SeriesJob job = job(0);
        DeconvolutionAnalysis.RunSettings settings = baseSettings();
        DeconvolutionAnalysis.PreviewState state = new DeconvolutionAnalysis.PreviewState();

        // User clicks "Use settings & run batch" in the preview dialog.
        applyPreviewDecision(state, DeconvPreviewDialog.Decision.RUN_FULL_BATCH,
                analysis.previewFingerprint(settings, job, firstSelected(settings.selectedChannels), CROP));

        // Pressing OK with no further edits: the auto pre-batch preview is skipped.
        assertTrue(previewAcceptedOnOk(state, settings, job));
    }

    @Test
    public void editingAfterAcceptanceReTriggersPreview() {
        DeconvolutionAnalysis.SeriesJob job = job(0);
        DeconvolutionAnalysis.RunSettings settings = baseSettings();
        DeconvolutionAnalysis.PreviewState state = new DeconvolutionAnalysis.PreviewState();

        applyPreviewDecision(state, DeconvPreviewDialog.Decision.RUN_FULL_BATCH,
                analysis.previewFingerprint(settings, job, firstSelected(settings.selectedChannels), CROP));

        // The user then edits a preview-affecting field. This asserts the authoritative gate: the
        // OK-path fingerprint no longer matches, so the pre-batch preview reappears. (The live
        // dialog ALSO clears acceptance via the markPreviewStale listeners on edit; that
        // PreviewState.clear() path is Swing-bound and is covered separately in
        // DeconvolutionAnalysisPreviewStateTest#previewStateMatchesOnlyForAcceptedFingerprint.)
        settings.iterations += 7;
        assertFalse("changed settings must re-trigger the pre-batch preview",
                previewAcceptedOnOk(state, settings, job));
    }

    @Test
    public void reconfigureDecisionDoesNotMarkAccepted() {
        DeconvolutionAnalysis.SeriesJob job = job(0);
        DeconvolutionAnalysis.RunSettings settings = baseSettings();
        DeconvolutionAnalysis.PreviewState state = new DeconvolutionAnalysis.PreviewState();

        applyPreviewDecision(state, DeconvPreviewDialog.Decision.RECONFIGURE,
                analysis.previewFingerprint(settings, job, firstSelected(settings.selectedChannels), CROP));

        assertFalse(previewAcceptedOnOk(state, settings, job));
    }

    @Test
    public void cancelDecisionDoesNotMarkAccepted() {
        DeconvolutionAnalysis.SeriesJob job = job(0);
        DeconvolutionAnalysis.RunSettings settings = baseSettings();
        DeconvolutionAnalysis.PreviewState state = new DeconvolutionAnalysis.PreviewState();

        applyPreviewDecision(state, DeconvPreviewDialog.Decision.CANCEL,
                analysis.previewFingerprint(settings, job, firstSelected(settings.selectedChannels), CROP));

        assertFalse(previewAcceptedOnOk(state, settings, job));
    }

    private static int firstSelected(boolean[] selected) {
        if (selected == null) return -1;
        for (int i = 0; i < selected.length; i++) {
            if (selected[i]) return i;
        }
        return -1;
    }

    private static DeconvolutionAnalysis.RunSettings baseSettings() {
        DeconvolutionAnalysis.RunSettings settings = new DeconvolutionAnalysis.RunSettings();
        settings.enabled = true;
        settings.engineKey = "DL2";
        settings.algorithm = Algorithm.RL;
        settings.psfModel = PsfModel.GIBSON_LANNI;
        settings.scopeModality = ScopeModality.WIDEFIELD;
        settings.iterations = 15;
        settings.regularization = 0.01;
        settings.pinholeAiryUnits = Double.valueOf(1.0);
        settings.sampleRiOverride = Double.valueOf(1.33);
        settings.naOverride = Double.valueOf(1.30);
        settings.immersionRiOverride = Double.valueOf(1.515);
        settings.xyPixelSizeOverrideUm = Double.valueOf(0.10);
        settings.zStepOverrideUm = Double.valueOf(0.30);
        settings.channelNames = new String[]{"C0", "C1"};
        settings.selectedChannels = new boolean[]{true, false};
        settings.emissionOverridesNm = new double[]{568.0, 488.0};
        return settings;
    }

    private static DeconvolutionAnalysis.SeriesJob job(int seriesIndex) {
        MetadataDiagnostics.SeriesInfo info = new MetadataDiagnostics.SeriesInfo();
        info.seriesIndex = seriesIndex;
        info.imageName = "series" + seriesIndex;
        return new DeconvolutionAnalysis.SeriesJob(new File("synthetic.lif"), seriesIndex, "synthetic", info);
    }
}
