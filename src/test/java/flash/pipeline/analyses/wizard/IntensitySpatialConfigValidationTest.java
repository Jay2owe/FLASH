package flash.pipeline.analyses.wizard;

import org.junit.Test;

import java.util.EnumSet;

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
}
