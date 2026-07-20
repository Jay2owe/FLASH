package flash.pipeline.cli;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ThreeDObjectPresetCliTest {

    @Test
    public void objectPresetAndNuclearMarkerOverrideParseAndSerialize() {
        CLIConfig parsed = CLIArgumentParser.parse(
                "dir=[/tmp/data] object.preset=microglia_processes object.nuclear_marker=2 object.doIntensityColoc=true");

        assertTrue(parsed.getSelectedAnalyses()[4]);
        assertEquals("microglia_processes", parsed.getObject().getPresetName());
        assertEquals(Integer.valueOf(1), parsed.getObject().getNuclearMarkerIndex());
        assertEquals(Boolean.TRUE, parsed.getObject().getDoIntensityColoc());

        CLIConfig reparsed = CLIArgumentParser.parse(CLIArgumentParser.serialize(parsed));
        assertEquals("microglia_processes", reparsed.getObject().getPresetName());
        assertEquals(Integer.valueOf(1), reparsed.getObject().getNuclearMarkerIndex());
        assertEquals(Boolean.TRUE, reparsed.getObject().getDoIntensityColoc());
    }

    @Test
    public void objectRegionFilterParseAndSerialize() {
        CLIConfig parsed = CLIArgumentParser.parse(
                "dir=[/tmp/data] object.regions=[SCN,Cortex] object.exclude_regions=[PVN]");

        assertTrue(parsed.getSelectedAnalyses()[4]);
        assertEquals("SCN", parsed.getObject().getIncludeRegions().get(0));
        assertEquals("Cortex", parsed.getObject().getIncludeRegions().get(1));
        assertEquals("PVN", parsed.getObject().getExcludeRegions().get(0));

        CLIConfig reparsed = CLIArgumentParser.parse(CLIArgumentParser.serialize(parsed));
        assertEquals("SCN", reparsed.getObject().getIncludeRegions().get(0));
        assertEquals("Cortex", reparsed.getObject().getIncludeRegions().get(1));
        assertEquals("PVN", reparsed.getObject().getExcludeRegions().get(0));
    }

    @Test
    public void objectIntensityColocalizationAliasesParse() {
        CLIConfig snakeCase = CLIArgumentParser.parse(
                "dir=[/tmp/data] object.do_intensity_coloc=true");
        CLIConfig descriptive = CLIArgumentParser.parse(
                "dir=[/tmp/data] object.intensity_colocalization=true");

        assertEquals(Boolean.TRUE, snakeCase.getObject().getDoIntensityColoc());
        assertEquals(Boolean.TRUE, descriptive.getObject().getDoIntensityColoc());
    }

    @Test
    public void objectIntensityProfilingOptionsParseAndSerialize() {
        CLIConfig parsed = CLIArgumentParser.parse(
                "dir=[/tmp/data] object.oip.radial=false object.oip.marginal=false "
                        + "object.oip.principal_axis=true object.oip.angular=true "
                        + "object.oip.shell=true object.oip.within_box=true "
                        + "object.oip.figures=false object.oip.region=object_voxels "
                        + "object.oip.norm=zscore object.oip.radial_bins=16 "
                        + "object.oip.angular_bins=8 object.oip.shells=4 "
                        + "object.oip.resample_n=64 object.oip.box_pad_pct=10 "
                        + "object.oip.ring_threshold_pct=35");

        assertTrue(parsed.getSelectedAnalyses()[4]);
        assertEquals(Boolean.FALSE, parsed.getObject().getDoRadialProfile());
        assertEquals(Boolean.FALSE, parsed.getObject().getDoMarginalProfile());
        assertEquals(Boolean.TRUE, parsed.getObject().getDoPrincipalAxisProfile());
        assertEquals(Boolean.TRUE, parsed.getObject().getDoAngularProfile());
        assertEquals(Boolean.TRUE, parsed.getObject().getDoShellColoc());
        assertEquals(Boolean.TRUE, parsed.getObject().getDoWithinBoxCorr());
        assertEquals(Boolean.FALSE, parsed.getObject().getOipGenerateFigures());
        assertEquals("OBJECT_VOXELS", parsed.getObject().getOipRegion());
        assertEquals("ZSCORE", parsed.getObject().getOipIntensityNorm());
        assertEquals(Integer.valueOf(16), parsed.getObject().getOipRadialBins());
        assertEquals(Integer.valueOf(8), parsed.getObject().getOipAngularBins());
        assertEquals(Integer.valueOf(4), parsed.getObject().getOipShells());
        assertEquals(Integer.valueOf(64), parsed.getObject().getOipResampleN());
        assertEquals(Double.valueOf(10.0), parsed.getObject().getOipBoxPadPct());
        assertEquals(Double.valueOf(35.0), parsed.getObject().getOipRingThresholdPct());
        assertFalse(parsed.getObject().hasSetupConfiguration());
        assertTrue(parsed.getObject().hasOipConfiguration());

        CLIConfig reparsed = CLIArgumentParser.parse(CLIArgumentParser.serialize(parsed));
        assertEquals(Boolean.FALSE, reparsed.getObject().getDoRadialProfile());
        assertEquals(Boolean.TRUE, reparsed.getObject().getDoAngularProfile());
        assertEquals("OBJECT_VOXELS", reparsed.getObject().getOipRegion());
        assertEquals("ZSCORE", reparsed.getObject().getOipIntensityNorm());
        assertEquals(Integer.valueOf(64), reparsed.getObject().getOipResampleN());
    }
}
