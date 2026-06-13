package flash.pipeline.cli;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
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
}
