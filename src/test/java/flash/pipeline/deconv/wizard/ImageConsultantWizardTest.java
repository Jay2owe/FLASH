package flash.pipeline.deconv.wizard;

import flash.pipeline.deconv.RefractiveIndexEstimator;
import flash.pipeline.deconv.engine.Algorithm;
import flash.pipeline.deconv.psf.PsfModel;
import flash.pipeline.deconv.psf.ScopeModality;
import flash.pipeline.intelligence.MetadataDiagnostics;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ImageConsultantWizardTest {

    @Test
    public void derivationMatrixCoversPriorityDepthAndConditionalScreens() {
        ImageConsultantWizard.Availability availability =
                new ImageConsultantWizard.Availability(true, true, true);

        for (ImageConsultantWizard.Priority priority : ImageConsultantWizard.Priority.values()) {
            for (ImageConsultantWizard.SampleDepth sampleDepth : ImageConsultantWizard.SampleDepth.values()) {
                for (boolean modalityQuestion : new boolean[]{false, true}) {
                    MetadataDiagnostics.SeriesInfo info = modalityQuestion
                            ? unknownModalitySeriesInfo()
                            : knownWidefieldSeriesInfo();
                    ScopeModality modalityAnswer = modalityQuestion ? ScopeModality.CONFOCAL : null;
                    Double pinhole = modalityQuestion ? Double.valueOf(1.4) : null;

                    if (sampleDepth == ImageConsultantWizard.SampleDepth.THIN) {
                        assertFalse(ImageConsultantWizard.needsMountingMediumQuestion(sampleDepth, null));
                        assertRecommendationMatches(priority, sampleDepth, info, modalityAnswer, pinhole, null,
                                availability, thinIterations(priority), 0.005,
                                PsfModel.BORN_WOLF,
                                modalityQuestion ? ScopeModality.CONFOCAL : ScopeModality.WIDEFIELD,
                                modalityQuestion ? Double.valueOf(1.4) : null,
                                RefractiveIndexEstimator.inferSampleRI(info.objectiveImmersion, null));
                        continue;
                    }

                    String knownHint = sampleDepth == ImageConsultantWizard.SampleDepth.CLEARED ? "clarity" : "vectashield";
                    assertEquals(sampleDepth == ImageConsultantWizard.SampleDepth.DEEP,
                            ImageConsultantWizard.needsMountingMediumQuestion(sampleDepth, null));
                    assertFalse(ImageConsultantWizard.needsMountingMediumQuestion(sampleDepth, knownHint));

                    assertRecommendationMatches(priority, sampleDepth, info, modalityAnswer, pinhole, knownHint,
                            availability,
                            expectedIterations(priority, sampleDepth),
                            expectedRegularization(priority, sampleDepth),
                            PsfModel.GIBSON_LANNI,
                            modalityQuestion ? ScopeModality.CONFOCAL : ScopeModality.WIDEFIELD,
                            modalityQuestion ? Double.valueOf(1.4) : null,
                            RefractiveIndexEstimator.mountingMediumRI(knownHint));

                    String unknownHint = sampleDepth == ImageConsultantWizard.SampleDepth.DEEP ? null : "clarity";
                    double expectedRi = sampleDepth == ImageConsultantWizard.SampleDepth.DEEP
                            ? RefractiveIndexEstimator.inferSampleRI(info.objectiveImmersion, null)
                            : 1.46;
                    assertRecommendationMatches(priority, sampleDepth, info, modalityAnswer, pinhole, unknownHint,
                            availability,
                            expectedIterations(priority, sampleDepth),
                            expectedRegularization(priority, sampleDepth),
                            PsfModel.GIBSON_LANNI,
                            modalityQuestion ? ScopeModality.CONFOCAL : ScopeModality.WIDEFIELD,
                            modalityQuestion ? Double.valueOf(1.4) : null,
                            expectedRi);
                }
            }
        }
    }

    @Test
    public void engineFallbackOrderMatchesPhaseSpec() {
        MetadataDiagnostics.SeriesInfo info = knownWidefieldSeriesInfo();
        ImageConsultantWizard.Answers answers =
                new ImageConsultantWizard.Answers(
                        ImageConsultantWizard.Priority.SPEED,
                        ImageConsultantWizard.SampleDepth.DEEP,
                        null,
                        null,
                        "vectashield");

        assertEquals("CLIJ2", ImageConsultantWizard.deriveRecommendation(info, answers,
                new ImageConsultantWizard.Availability(true, true, true)).getEngineKey());
        assertEquals("IterativeDeconvolve3D", ImageConsultantWizard.deriveRecommendation(info, answers,
                new ImageConsultantWizard.Availability(false, true, true)).getEngineKey());
        assertEquals("DL2", ImageConsultantWizard.deriveRecommendation(info, answers,
                new ImageConsultantWizard.Availability(false, true, false)).getEngineKey());

        ImageConsultantWizard.Answers accuracyAnswers =
                new ImageConsultantWizard.Answers(
                        ImageConsultantWizard.Priority.ACCURACY,
                        ImageConsultantWizard.SampleDepth.DEEP,
                        null,
                        null,
                        "vectashield");
        assertEquals("DL2", ImageConsultantWizard.deriveRecommendation(info, accuracyAnswers,
                new ImageConsultantWizard.Availability(true, true, true)).getEngineKey());
        assertEquals("CLIJ2", ImageConsultantWizard.deriveRecommendation(info, accuracyAnswers,
                new ImageConsultantWizard.Availability(true, false, true)).getEngineKey());
        assertEquals("IterativeDeconvolve3D", ImageConsultantWizard.deriveRecommendation(info, accuracyAnswers,
                new ImageConsultantWizard.Availability(false, false, true)).getEngineKey());
    }

    @Test
    public void modalityAnswersMapExactly() {
        MetadataDiagnostics.SeriesInfo info = unknownModalitySeriesInfo();

        assertEquals(ScopeModality.WIDEFIELD,
                recommendationFor(info, ScopeModality.WIDEFIELD, null).getScopeModality());
        assertEquals(ScopeModality.SPINNING_DISK,
                recommendationFor(info, ScopeModality.SPINNING_DISK, null).getScopeModality());

        ImageConsultantWizard.Recommendation confocal =
                recommendationFor(info, ScopeModality.CONFOCAL, Double.valueOf(1.25));
        assertEquals(ScopeModality.CONFOCAL, confocal.getScopeModality());
        assertEquals(1.25, confocal.getPinholeAU(), 1e-12);
    }

    @Test
    public void mountingMediumHintsMapToExpectedSampleRi() {
        MetadataDiagnostics.SeriesInfo info = knownWidefieldSeriesInfo();
        assertEquals(1.45, deepRecommendation(info, "vectashield").getSampleRI(), 1e-12);
        assertEquals(1.47, deepRecommendation(info, "prolong").getSampleRI(), 1e-12);
        assertEquals(1.52, deepRecommendation(info, "cfm3").getSampleRI(), 1e-12);
        assertEquals(1.47, deepRecommendation(info, "glycerol").getSampleRI(), 1e-12);
        assertEquals(1.33, deepRecommendation(info, "aqueous").getSampleRI(), 1e-12);
        assertEquals(1.46, deepRecommendation(info, "clarity").getSampleRI(), 1e-12);
        assertEquals(RefractiveIndexEstimator.inferSampleRI(info.objectiveImmersion, null),
                deepRecommendation(info, null).getSampleRI(), 1e-12);
    }

    @Test
    public void iterativeFallbackUsesPlainRichardsonLucy() {
        MetadataDiagnostics.SeriesInfo info = knownWidefieldSeriesInfo();
        ImageConsultantWizard.Answers answers =
                new ImageConsultantWizard.Answers(
                        ImageConsultantWizard.Priority.SPEED,
                        ImageConsultantWizard.SampleDepth.DEEP,
                        null,
                        null,
                        null);

        ImageConsultantWizard.Recommendation recommendation =
                ImageConsultantWizard.deriveRecommendation(info, answers,
                        new ImageConsultantWizard.Availability(false, false, true));

        assertEquals("IterativeDeconvolve3D", recommendation.getEngineKey());
        assertEquals(Algorithm.RL, recommendation.getAlgorithm());
    }

    private static void assertRecommendationMatches(ImageConsultantWizard.Priority priority,
                                                    ImageConsultantWizard.SampleDepth sampleDepth,
                                                    MetadataDiagnostics.SeriesInfo info,
                                                    ScopeModality modalityAnswer,
                                                    Double pinhole,
                                                    String mountingHint,
                                                    ImageConsultantWizard.Availability availability,
                                                    int expectedIterations,
                                                    double expectedRegularization,
                                                    PsfModel expectedPsf,
                                                    ScopeModality expectedModality,
                                                    Double expectedPinhole,
                                                    double expectedSampleRi) {
        ImageConsultantWizard.Recommendation recommendation =
                ImageConsultantWizard.deriveRecommendation(
                        info,
                        new ImageConsultantWizard.Answers(priority, sampleDepth, modalityAnswer, pinhole, mountingHint),
                        availability);

        assertEquals(priority == ImageConsultantWizard.Priority.SPEED ? "CLIJ2" : "DL2",
                recommendation.getEngineKey());
        assertEquals(Algorithm.RL_TV, recommendation.getAlgorithm());
        assertEquals(expectedPsf, recommendation.getPsfModel());
        assertEquals(expectedModality, recommendation.getScopeModality());
        assertEquals(expectedIterations, recommendation.getIterations());
        assertEquals(expectedRegularization, recommendation.getRegularization(), 1e-12);
        if (expectedPinhole == null) {
            assertNull(recommendation.getPinholeAU());
        } else {
            assertEquals(expectedPinhole, recommendation.getPinholeAU());
        }
        assertEquals(expectedSampleRi, recommendation.getSampleRI(), 1e-12);
    }

    private static int expectedIterations(ImageConsultantWizard.Priority priority,
                                          ImageConsultantWizard.SampleDepth sampleDepth) {
        if (priority == ImageConsultantWizard.Priority.SPEED && sampleDepth == ImageConsultantWizard.SampleDepth.THIN) {
            return 10;
        }
        if (priority == ImageConsultantWizard.Priority.SPEED) {
            return 15;
        }
        if (sampleDepth == ImageConsultantWizard.SampleDepth.THIN) {
            return 15;
        }
        return 20;
    }

    private static double expectedRegularization(ImageConsultantWizard.Priority priority,
                                                 ImageConsultantWizard.SampleDepth sampleDepth) {
        if (priority == ImageConsultantWizard.Priority.SPEED && sampleDepth == ImageConsultantWizard.SampleDepth.THIN) {
            return 0.005;
        }
        if (priority == ImageConsultantWizard.Priority.SPEED) {
            return 0.01;
        }
        if (sampleDepth == ImageConsultantWizard.SampleDepth.THIN) {
            return 0.005;
        }
        if (sampleDepth == ImageConsultantWizard.SampleDepth.DEEP) {
            return 0.02;
        }
        return 0.01;
    }

    private static int thinIterations(ImageConsultantWizard.Priority priority) {
        return priority == ImageConsultantWizard.Priority.SPEED ? 10 : 15;
    }

    private static ImageConsultantWizard.Recommendation recommendationFor(MetadataDiagnostics.SeriesInfo info,
                                                                          ScopeModality modality,
                                                                          Double pinhole) {
        return ImageConsultantWizard.deriveRecommendation(
                info,
                new ImageConsultantWizard.Answers(
                        ImageConsultantWizard.Priority.ACCURACY,
                        ImageConsultantWizard.SampleDepth.THIN,
                        modality,
                        pinhole,
                        null),
                new ImageConsultantWizard.Availability(true, true, true));
    }

    private static ImageConsultantWizard.Recommendation deepRecommendation(MetadataDiagnostics.SeriesInfo info,
                                                                           String mountingHint) {
        return ImageConsultantWizard.deriveRecommendation(
                info,
                new ImageConsultantWizard.Answers(
                        ImageConsultantWizard.Priority.ACCURACY,
                        ImageConsultantWizard.SampleDepth.DEEP,
                        null,
                        null,
                        mountingHint),
                new ImageConsultantWizard.Availability(true, true, true));
    }

    private static MetadataDiagnostics.SeriesInfo knownWidefieldSeriesInfo() {
        MetadataDiagnostics.SeriesInfo info = new MetadataDiagnostics.SeriesInfo();
        info.objectiveImmersion = "oil";
        info.objectiveMag = Double.valueOf(20.0);
        return info;
    }

    private static MetadataDiagnostics.SeriesInfo unknownModalitySeriesInfo() {
        MetadataDiagnostics.SeriesInfo info = new MetadataDiagnostics.SeriesInfo();
        info.objectiveImmersion = "oil";
        info.objectiveMag = Double.valueOf(30.0);
        return info;
    }
}
