package flash.pipeline.cli;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;

import org.junit.Test;

import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IntensityPresetCliTest {

    @Test
    public void intensityPresetAndThresholdOverrideParseAndSerialize() {
        CLIConfig parsed = CLIArgumentParser.parse(
                "dir=[/tmp/data] intensity.preset=threshold_puncta intensity.threshold_channel2=45");

        assertTrue(parsed.getSelectedAnalyses()[7]);
        assertEquals("threshold_puncta", parsed.getIntensity().getPresetName());
        assertEquals("45", parsed.getIntensity().getThresholds().get(Integer.valueOf(1)));

        CLIConfig reparsed = CLIArgumentParser.parse(CLIArgumentParser.serialize(parsed));
        assertEquals("threshold_puncta", reparsed.getIntensity().getPresetName());
        assertEquals("45", reparsed.getIntensity().getThresholds().get(Integer.valueOf(1)));
    }

    @Test
    public void intensityRegionFilterParseAndSerialize() {
        CLIConfig parsed = CLIArgumentParser.parse(
                "dir=[/tmp/data] intensity.regions=[SCN,Cortex] intensity.exclude_regions=[PVN]");

        assertTrue(parsed.getSelectedAnalyses()[7]);
        assertEquals("SCN", parsed.getIntensity().getIncludeRegions().get(0));
        assertEquals("Cortex", parsed.getIntensity().getIncludeRegions().get(1));
        assertEquals("PVN", parsed.getIntensity().getExcludeRegions().get(0));

        CLIConfig reparsed = CLIArgumentParser.parse(CLIArgumentParser.serialize(parsed));
        assertEquals("SCN", reparsed.getIntensity().getIncludeRegions().get(0));
        assertEquals("Cortex", reparsed.getIntensity().getIncludeRegions().get(1));
        assertEquals("PVN", reparsed.getIntensity().getExcludeRegions().get(0));
    }

    @Test
    public void intensitySpatialPresetDefaultsMergeWithExplicitCliOverrides() {
        IntensitySpatialConfig preset = IntensitySpatialConfig.builder()
                .enabled(true)
                .enabledAnalyses(EnumSet.of(
                        IntensitySpatialConfig.AnalysisKey.PATCHINESS,
                        IntensitySpatialConfig.AnalysisKey.CROSSMARK))
                .mipEnabled(true)
                .shellCount(3)
                .build();
        CLIConfig parsed = CLIArgumentParser.parse("dir=[/tmp/data] "
                + "intensity.preset=spatial_preset "
                + "intensity.spatial.analyses=mi,distance_shell "
                + "intensity.spatial.shell_count=7");

        IntensitySpatialConfig merged = parsed.getIntensity().mergeSpatialConfig(
                preset, 2, new boolean[]{false, true}, null);

        assertTrue(merged.isEnabled());
        assertTrue("Preset MIP default should be preserved", merged.isMipEnabled());
        assertEquals(7, merged.getShellCount());
        assertTrue(merged.getEnabledAnalyses().contains(IntensitySpatialConfig.AnalysisKey.ENTROPY_MI));
        assertTrue(merged.getEnabledAnalyses().contains(IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL));
        assertFalse("Explicit CLI analyses replace preset analyses",
                merged.getEnabledAnalyses().contains(IntensitySpatialConfig.AnalysisKey.PATCHINESS));
        assertFalse("Explicit CLI analyses replace preset analyses",
                merged.getEnabledAnalyses().contains(IntensitySpatialConfig.AnalysisKey.CROSSMARK));
    }

    @Test
    public void intensitySpatialLocksClearInvalidCrossChannelSelectionsWithoutBinarizing() {
        boolean[] binarization = new boolean[]{false};
        CLIConfig parsed = CLIArgumentParser.parse("dir=[/tmp/data] "
                + "intensity.spatial=true "
                + "intensity.spatial.analyses=patchiness,crossmark,distance_shell");

        IntensitySpatialConfig locked = parsed.getIntensity().mergeSpatialConfig(
                IntensitySpatialConfig.disabled(), 1, binarization, null);

        assertTrue(locked.isEnabled());
        assertTrue(locked.getEnabledAnalyses().contains(IntensitySpatialConfig.AnalysisKey.PATCHINESS));
        assertFalse(locked.getEnabledAnalyses().contains(IntensitySpatialConfig.AnalysisKey.CROSSMARK));
        assertFalse(locked.getEnabledAnalyses().contains(IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL));
        assertFalse("CLI validation must not auto-enable binarisation", binarization[0]);
    }

    @Test
    public void intensitySpatialLocksClearDistanceShellWhenNoPartnerIsBinarized() {
        CLIConfig parsed = CLIArgumentParser.parse("dir=[/tmp/data] "
                + "intensity.spatial=true "
                + "intensity.spatial.analyses=patchiness,distance_shell");

        IntensitySpatialConfig locked = parsed.getIntensity().mergeSpatialConfig(
                IntensitySpatialConfig.disabled(), 2, new boolean[]{false, false}, null);

        assertTrue(locked.getEnabledAnalyses().contains(IntensitySpatialConfig.AnalysisKey.PATCHINESS));
        assertFalse(locked.getEnabledAnalyses().contains(IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL));
    }
}
