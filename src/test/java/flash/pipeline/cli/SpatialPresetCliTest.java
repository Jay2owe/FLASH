package flash.pipeline.cli;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SpatialPresetCliTest {

    @Test
    public void spatialPresetAndHeatmapOverrideParseAndSerialize() {
        CLIConfig parsed = CLIArgumentParser.parse(
                "dir=[/tmp/data] spatial.preset=microglia_plaque_contact spatial.heatmaps=true");

        assertTrue(parsed.getSelectedAnalyses()[5]);
        assertEquals("microglia_plaque_contact", parsed.getSpatial().getPresetName());
        assertEquals(Boolean.TRUE, parsed.getSpatial().getDoHeatmaps());

        CLIConfig reparsed = CLIArgumentParser.parse(CLIArgumentParser.serialize(parsed));
        assertEquals("microglia_plaque_contact", reparsed.getSpatial().getPresetName());
        assertEquals(Boolean.TRUE, reparsed.getSpatial().getDoHeatmaps());
    }

    @Test
    public void spatialComplexAutoImplies3dOnCli() {
        CLIConfig parsed = CLIArgumentParser.parse("dir=[/tmp/data] spatial.complex=true");

        assertEquals(Boolean.TRUE, parsed.getSpatial().getDoCompositeIndices());
        assertEquals(Boolean.TRUE, parsed.getSpatial().getDo3DMorphology());
    }
}
