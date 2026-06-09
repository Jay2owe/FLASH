package flash.pipeline.analyses.wizard;

import flash.pipeline.intensity.spatial.IntensitySpatialOutputMode;
import org.junit.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Validates {@link IntensitySpatialConfig#validateForChannelSetup} channel/3D
 * locking behaviour. These checks previously lived in the deleted intensity
 * spatial setup wizard test; they exercise pure config logic only.
 */
public class IntensitySpatialConfigValidationTest {

    @Test
    public void validationDisablesConfigWhenEverySelectedAnalysisIsLockedOut() {
        IntensitySpatialConfig config = IntensitySpatialConfig.builder()
                .enabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.CROSSMARK)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL)
                .build()
                .validateForChannelSetup(1, new boolean[]{false});

        assertFalse(config.isEnabled());
        assertTrue(config.getEnabledAnalyses().isEmpty());
    }

    @Test
    public void native3dSelectionsRequireNativeMode() {
        IntensitySpatialConfig nativeOff = IntensitySpatialConfig.builder()
                .enabled(true)
                .enabledAnalyses(EnumSet.of(
                        IntensitySpatialConfig.AnalysisKey.CROSSMARK,
                        IntensitySpatialConfig.AnalysisKey.CROSSMARK_3D))
                .native3dEnabled(false)
                .build()
                .validateForChannelSetup(2, new boolean[]{true, true});

        assertTrue(nativeOff.getEnabledAnalyses().contains(IntensitySpatialConfig.AnalysisKey.CROSSMARK));
        assertFalse(nativeOff.getEnabledAnalyses().contains(IntensitySpatialConfig.AnalysisKey.CROSSMARK_3D));
    }

    @Test
    public void perModeSelectionsAreIndependentAndUnioned() {
        IntensitySpatialConfig config = IntensitySpatialConfig.builder()
                .enabled(true)
                .enabledPerSlice(EnumSet.of(
                        IntensitySpatialConfig.AnalysisKey.NULLMODEL,
                        IntensitySpatialConfig.AnalysisKey.GLCM))
                .enabledMip(EnumSet.of(
                        IntensitySpatialConfig.AnalysisKey.PATCHINESS,
                        IntensitySpatialConfig.AnalysisKey.GLCM))
                .enabled3D(EnumSet.of(IntensitySpatialConfig.AnalysisKey.ANISOTROPY_3D))
                .build();

        assertTrue(config.isEnabledIn(IntensitySpatialConfig.AnalysisKey.GLCM,
                IntensitySpatialOutputMode.BASE));
        assertTrue(config.isEnabledIn(IntensitySpatialConfig.AnalysisKey.GLCM,
                IntensitySpatialOutputMode.MIP));
        assertFalse(config.isEnabledIn(IntensitySpatialConfig.AnalysisKey.NULLMODEL,
                IntensitySpatialOutputMode.MIP));
        assertTrue(config.isEnabledIn(IntensitySpatialConfig.AnalysisKey.ANISOTROPY_3D,
                IntensitySpatialOutputMode.NATIVE_3D));
        assertTrue(config.anyEnabled());
        assertTrue(config.isMipEnabled());
        assertTrue(config.isNative3dEnabled());
        assertTrue(config.getEnabledAnalyses().containsAll(EnumSet.of(
                IntensitySpatialConfig.AnalysisKey.NULLMODEL,
                IntensitySpatialConfig.AnalysisKey.GLCM,
                IntensitySpatialConfig.AnalysisKey.PATCHINESS,
                IntensitySpatialConfig.AnalysisKey.ANISOTROPY_3D)));
    }

    @Test
    public void perModeJsonRoundTripsExactly() throws Exception {
        IntensitySpatialConfig config = IntensitySpatialConfig.builder()
                .enabled(true)
                .enabledPerSlice(EnumSet.of(IntensitySpatialConfig.AnalysisKey.NULLMODEL))
                .enabledMip(EnumSet.of(
                        IntensitySpatialConfig.AnalysisKey.PATCHINESS,
                        IntensitySpatialConfig.AnalysisKey.GLCM))
                .enabled3D(EnumSet.of(IntensitySpatialConfig.AnalysisKey.CROSSMARK_3D))
                .build();

        IntensitySpatialConfig back = IntensitySpatialConfig.fromJsonObject(config.toJsonObject());
        assertEquals(config.getEnabledPerSlice(), back.getEnabledPerSlice());
        assertEquals(config.getEnabledMip(), back.getEnabledMip());
        assertEquals(config.getEnabled3D(), back.getEnabled3D());
    }

    @Test
    public void legacyJsonSplitsFlatListByModeFlags() throws Exception {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("enabled", Boolean.TRUE);
        root.put("analyses", Arrays.asList("patchiness", "glcm", "anisotropy_3d"));
        root.put("sourceMode", "mip");
        root.put("native3d", Boolean.TRUE);

        IntensitySpatialConfig config = IntensitySpatialConfig.fromJsonObject(root);
        assertTrue(config.getEnabledPerSlice().isEmpty());
        assertEquals(EnumSet.of(
                IntensitySpatialConfig.AnalysisKey.PATCHINESS,
                IntensitySpatialConfig.AnalysisKey.GLCM), config.getEnabledMip());
        assertEquals(EnumSet.of(IntensitySpatialConfig.AnalysisKey.ANISOTROPY_3D),
                config.getEnabled3D());
    }

    @Test
    public void legacyFullStackJsonGoesToPerSliceAndDropsNativeWithoutFlag() throws Exception {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("enabled", Boolean.TRUE);
        root.put("analyses", Arrays.asList("nullmodel", "glcm", "anisotropy_3d"));
        root.put("sourceMode", "full_stack");

        IntensitySpatialConfig config = IntensitySpatialConfig.fromJsonObject(root);
        assertEquals(EnumSet.of(
                IntensitySpatialConfig.AnalysisKey.NULLMODEL,
                IntensitySpatialConfig.AnalysisKey.GLCM), config.getEnabledPerSlice());
        assertTrue(config.getEnabledMip().isEmpty());
        assertTrue(config.getEnabled3D().isEmpty());
    }

    @Test
    public void validateClearsCrossChannelFromEveryModeWithOneChannel() {
        IntensitySpatialConfig config = IntensitySpatialConfig.builder()
                .enabled(true)
                .enabledPerSlice(EnumSet.of(
                        IntensitySpatialConfig.AnalysisKey.CROSSMARK,
                        IntensitySpatialConfig.AnalysisKey.NULLMODEL))
                .enabledMip(EnumSet.of(
                        IntensitySpatialConfig.AnalysisKey.ENTROPY_MI,
                        IntensitySpatialConfig.AnalysisKey.PATCHINESS))
                .build()
                .validateForChannelSetup(1, new boolean[]{false});

        assertFalse(config.getEnabledPerSlice().contains(IntensitySpatialConfig.AnalysisKey.CROSSMARK));
        assertTrue(config.getEnabledPerSlice().contains(IntensitySpatialConfig.AnalysisKey.NULLMODEL));
        assertFalse(config.getEnabledMip().contains(IntensitySpatialConfig.AnalysisKey.ENTROPY_MI));
        assertTrue(config.getEnabledMip().contains(IntensitySpatialConfig.AnalysisKey.PATCHINESS));
    }

    @Test
    public void validateClearsMipAndNative3dForSingleSlice() {
        IntensitySpatialConfig config = IntensitySpatialConfig.builder()
                .enabled(true)
                .enabledPerSlice(EnumSet.of(IntensitySpatialConfig.AnalysisKey.NULLMODEL))
                .enabledMip(EnumSet.of(IntensitySpatialConfig.AnalysisKey.PATCHINESS))
                .enabled3D(EnumSet.of(IntensitySpatialConfig.AnalysisKey.ANISOTROPY_3D))
                .build()
                .validateForChannelSetup(2, new boolean[]{false, false}, Integer.valueOf(1), null);

        assertEquals(EnumSet.of(IntensitySpatialConfig.AnalysisKey.NULLMODEL),
                config.getEnabledPerSlice());
        assertTrue(config.getEnabledMip().isEmpty());
        assertTrue(config.getEnabled3D().isEmpty());
    }

    @Test
    public void validateClearsNative3dBelowMinimumSlices() {
        IntensitySpatialConfig config = IntensitySpatialConfig.builder()
                .enabled(true)
                .enabledMip(EnumSet.of(IntensitySpatialConfig.AnalysisKey.PATCHINESS))
                .enabled3D(EnumSet.of(IntensitySpatialConfig.AnalysisKey.ANISOTROPY_3D))
                .build()
                .validateForChannelSetup(2, new boolean[]{false, false},
                        Integer.valueOf(IntensitySpatialConfig.MIN_NATIVE_3D_SLICES - 1), null);

        assertTrue(config.getEnabledMip().contains(IntensitySpatialConfig.AnalysisKey.PATCHINESS));
        assertTrue(config.getEnabled3D().isEmpty());
    }

    @Test
    public void standaloneLegacyFlagDoesNotCollapsePerModeBase() {
        IntensitySpatialConfig base = IntensitySpatialConfig.builder()
                .enabled(true)
                .enabledPerSlice(EnumSet.of(IntensitySpatialConfig.AnalysisKey.NULLMODEL))
                .enabledMip(EnumSet.of(IntensitySpatialConfig.AnalysisKey.PATCHINESS))
                .build();

        // Setting only a legacy mode flag must NOT re-split the base union.
        IntensitySpatialConfig afterNative3d = IntensitySpatialConfig.builder(base)
                .native3dEnabled(false)
                .build();
        assertEquals(EnumSet.of(IntensitySpatialConfig.AnalysisKey.NULLMODEL),
                afterNative3d.getEnabledPerSlice());
        assertEquals(EnumSet.of(IntensitySpatialConfig.AnalysisKey.PATCHINESS),
                afterNative3d.getEnabledMip());

        IntensitySpatialConfig afterSource = IntensitySpatialConfig.builder(base)
                .spatialSourceMode(IntensitySpatialConfig.SpatialSourceMode.MIP)
                .build();
        assertEquals(EnumSet.of(IntensitySpatialConfig.AnalysisKey.NULLMODEL),
                afterSource.getEnabledPerSlice());
        assertEquals(EnumSet.of(IntensitySpatialConfig.AnalysisKey.PATCHINESS),
                afterSource.getEnabledMip());
    }

    @Test
    public void standaloneLegacyNative3dFalseStillClearsNative3dOnPerModeBase() {
        IntensitySpatialConfig base = IntensitySpatialConfig.builder()
                .enabled(true)
                .enabledMip(EnumSet.of(IntensitySpatialConfig.AnalysisKey.PATCHINESS))
                .enabled3D(EnumSet.of(IntensitySpatialConfig.AnalysisKey.ANISOTROPY_3D))
                .build();

        IntensitySpatialConfig merged = IntensitySpatialConfig.builder(base)
                .native3dEnabled(false)
                .build();
        assertEquals(EnumSet.of(IntensitySpatialConfig.AnalysisKey.PATCHINESS),
                merged.getEnabledMip());
        assertTrue(merged.getEnabled3D().isEmpty());
    }
}
