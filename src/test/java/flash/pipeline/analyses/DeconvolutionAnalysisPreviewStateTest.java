package flash.pipeline.analyses;

import flash.pipeline.deconv.engine.Algorithm;
import flash.pipeline.deconv.psf.PsfModel;
import flash.pipeline.deconv.psf.ScopeModality;
import flash.pipeline.intelligence.MetadataDiagnostics;
import org.junit.Test;

import javax.swing.JButton;
import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Stage 02 coverage: the preview fingerprint must be stable for identical settings and change when
 * any preview-affecting field changes; {@link DeconvolutionAnalysis.PreviewState} must only report
 * a match for the exact accepted fingerprint.
 */
public class DeconvolutionAnalysisPreviewStateTest {

    private final DeconvolutionAnalysis analysis = new DeconvolutionAnalysis();

    @Test
    public void identicalSettingsProduceIdenticalFingerprint() {
        DeconvolutionAnalysis.RunSettings a = baseSettings();
        DeconvolutionAnalysis.RunSettings b = baseSettings();
        DeconvolutionAnalysis.SeriesJob job = job(0);

        assertEquals(analysis.previewFingerprint(a, job, 0, 256),
                analysis.previewFingerprint(b, job, 0, 256));
    }

    @Test
    public void changingIterationsChangesFingerprint() {
        DeconvolutionAnalysis.RunSettings a = baseSettings();
        DeconvolutionAnalysis.RunSettings b = baseSettings();
        b.iterations = a.iterations + 5;
        DeconvolutionAnalysis.SeriesJob job = job(0);

        assertNotEquals(analysis.previewFingerprint(a, job, 0, 256),
                analysis.previewFingerprint(b, job, 0, 256));
    }

    @Test
    public void unusedAlgorithmParametersDoNotChangeFingerprint() {
        DeconvolutionAnalysis.RunSettings a = baseSettings();
        DeconvolutionAnalysis.RunSettings b = baseSettings();
        b.regularization = 0.05;
        DeconvolutionAnalysis.SeriesJob job = job(0);

        assertEquals("DL2 Richardson-Lucy does not consume regularization",
                analysis.previewFingerprint(a, job, 0, 256),
                analysis.previewFingerprint(b, job, 0, 256));

        a.algorithm = Algorithm.TIKHONOV;
        b.algorithm = Algorithm.TIKHONOV;
        b.iterations = a.iterations + 5;
        b.regularization = a.regularization;
        assertEquals("DL2 Tikhonov does not consume iterations",
                analysis.previewFingerprint(a, job, 0, 256),
                analysis.previewFingerprint(b, job, 0, 256));

        b.regularization = 0.05;
        assertNotEquals("DL2 Tikhonov does consume regularization",
                analysis.previewFingerprint(a, job, 0, 256),
                analysis.previewFingerprint(b, job, 0, 256));
    }

    @Test
    public void pendingPerChannelWavelengthFeedsPreviewSnapshotOnly() {
        DeconvolutionAnalysis.RunSettings settings = baseSettings();
        settings.emissionOverridesNm = new double[]{Double.NaN, 488.0};

        DeconvolutionAnalysis.RunSettings snapshot =
                DeconvolutionAnalysis.snapshotWithChannelWavelengthForPreview(
                        settings, 2, 0, "610");

        assertEquals(610.0, snapshot.emissionOverridesNm[0], 1e-9);
        assertEquals(488.0, snapshot.emissionOverridesNm[1], 1e-9);
        assertTrue("Preview snapshot must not mutate accepted settings",
                Double.isNaN(settings.emissionOverridesNm[0]));
    }

    @Test
    public void changingSelectedChannelsChangesFingerprint() {
        DeconvolutionAnalysis.RunSettings a = baseSettings();
        DeconvolutionAnalysis.RunSettings b = baseSettings();
        b.selectedChannels = new boolean[]{true, true};
        DeconvolutionAnalysis.SeriesJob job = job(0);

        assertNotEquals(analysis.previewFingerprint(a, job, 0, 256),
                analysis.previewFingerprint(b, job, 0, 256));
    }

    @Test
    public void changingChannelIndexOrCropChangesFingerprint() {
        DeconvolutionAnalysis.RunSettings a = baseSettings();
        DeconvolutionAnalysis.SeriesJob job = job(0);

        assertNotEquals(analysis.previewFingerprint(a, job, 0, 256),
                analysis.previewFingerprint(a, job, 1, 256));
        assertNotEquals(analysis.previewFingerprint(a, job, 0, 256),
                analysis.previewFingerprint(a, job, 0, 128));
    }

    @Test
    public void changingRepresentativeSeriesChangesFingerprint() {
        DeconvolutionAnalysis.RunSettings a = baseSettings();

        assertNotEquals(analysis.previewFingerprint(a, job(0), 0, 256),
                analysis.previewFingerprint(a, job(3), 0, 256));
    }

    @Test
    public void previewStateMatchesOnlyForAcceptedFingerprint() {
        DeconvolutionAnalysis.PreviewState state = new DeconvolutionAnalysis.PreviewState();
        assertFalse("fresh state matches nothing", state.matches("fp"));

        state.accept("fp");
        assertTrue(state.matches("fp"));
        assertFalse(state.matches("other"));

        state.clear();
        assertFalse("cleared state matches nothing", state.matches("fp"));
    }

    @Test
    public void channelPreviewButtonShowsWorkerState() {
        JButton button = new JButton();

        DeconvolutionAnalysis.setChannelPreviewButtonState(button,
                DeconvolutionAnalysis.ChannelPreviewButtonState.READY);
        assertEquals(DeconvolutionAnalysis.CHANNEL_PREVIEW_READY_TEXT, button.getText());
        assertTrue(button.getToolTipText().contains("fresh"));

        DeconvolutionAnalysis.setChannelPreviewButtonState(button,
                DeconvolutionAnalysis.ChannelPreviewButtonState.RUNNING);
        assertEquals(DeconvolutionAnalysis.CHANNEL_PREVIEW_RUNNING_TEXT, button.getText());
        assertTrue(button.getToolTipText().contains("running"));

        DeconvolutionAnalysis.setChannelPreviewButtonState(button,
                DeconvolutionAnalysis.ChannelPreviewButtonState.FINISHED);
        assertEquals(DeconvolutionAnalysis.CHANNEL_PREVIEW_FINISHED_TEXT, button.getText());
        assertTrue(button.getToolTipText().contains("finished"));

        DeconvolutionAnalysis.setChannelPreviewButtonState(button,
                DeconvolutionAnalysis.ChannelPreviewButtonState.FAILED);
        assertEquals(DeconvolutionAnalysis.CHANNEL_PREVIEW_FAILED_TEXT, button.getText());
        assertTrue(button.getToolTipText().contains("failed"));
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
