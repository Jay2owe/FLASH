package flash.pipeline.analyses.wizard;

import flash.pipeline.intelligence.MetadataDiagnostics;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class SpatialAutoDetectTest {

    @Test
    public void missingCalibrationDisablesRipleyOptionAndWarningIsShown() {
        assertFalse(SpatialAnalysisWizard.isSpatialOptionEnabled(
                SpatialAnalysisWizard.SPATIAL_CLUSTERED, false));
        assertEquals(SpatialAnalysisWizard.RIPLEY_CALIBRATION_WARNING,
                SpatialAnalysisWizard.spatialOptionWarning(
                        SpatialAnalysisWizard.SPATIAL_CLUSTERED, false));

        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("spatial.question", SpatialAnalysisWizard.SPATIAL_CLUSTERED);

        SpatialAnalysisWizard.DerivedConfig config = SpatialAnalysisWizard.deriveConfig(
                null, null, false, answers);

        assertFalse(config.doSpatialStats);
    }

    @Test
    public void shallowStackDisables3dMorphometryOptions() {
        MetadataDiagnostics.SeriesInfo info = new MetadataDiagnostics.SeriesInfo();
        info.sizeZ = 3;

        assertFalse(SpatialAnalysisWizard.isMorphologyOptionEnabled(
                SpatialAnalysisWizard.MORPH_3D, info));
        assertEquals("(requires z-stack with more slices)",
                SpatialAnalysisWizard.morphologyOptionWarning(
                        SpatialAnalysisWizard.MORPH_3D, info));

        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("morph.question", SpatialAnalysisWizard.MORPH_POPULATION);

        SpatialAnalysisWizard.DerivedConfig config = SpatialAnalysisWizard.deriveConfig(
                null, info, true, answers);

        assertFalse(config.do3DMorphology);
        assertFalse(config.doCompositeIndices);
        assertFalse(config.doPopMorphometrics);
    }
}
