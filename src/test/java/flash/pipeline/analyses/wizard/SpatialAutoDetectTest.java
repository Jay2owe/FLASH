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
        assertFalse(SpatialSetupConfig.isSpatialOptionEnabled(
                SpatialSetupConfig.SPATIAL_CLUSTERED, false));
        assertEquals(SpatialSetupConfig.RIPLEY_CALIBRATION_WARNING,
                SpatialSetupConfig.spatialOptionWarning(
                        SpatialSetupConfig.SPATIAL_CLUSTERED, false));

        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("spatial.question", SpatialSetupConfig.SPATIAL_CLUSTERED);

        SpatialSetupConfig.DerivedConfig config = SpatialSetupConfig.deriveConfig(
                null, null, false, answers);

        assertFalse(config.doSpatialStats);
    }

    @Test
    public void shallowStackDisables3dMorphometryOptions() {
        MetadataDiagnostics.SeriesInfo info = new MetadataDiagnostics.SeriesInfo();
        info.sizeZ = 3;

        assertFalse(SpatialSetupConfig.isMorphologyOptionEnabled(
                SpatialSetupConfig.MORPH_3D, info));
        assertEquals("(requires z-stack with more slices)",
                SpatialSetupConfig.morphologyOptionWarning(
                        SpatialSetupConfig.MORPH_3D, info));

        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("morph.question", SpatialSetupConfig.MORPH_POPULATION);

        SpatialSetupConfig.DerivedConfig config = SpatialSetupConfig.deriveConfig(
                null, info, true, answers);

        assertFalse(config.do3DMorphology);
        assertFalse(config.doCompositeIndices);
        assertFalse(config.doPopMorphometrics);
    }
}
