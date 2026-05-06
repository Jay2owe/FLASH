package flash.pipeline.cli;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ThreeDObjectPresetCliTest {

    @Test
    public void objectPresetAndNuclearMarkerOverrideParseAndSerialize() {
        CLIConfig parsed = CLIArgumentParser.parse(
                "dir=[/tmp/data] object.preset=microglia_processes object.nuclear_marker=2");

        assertTrue(parsed.getSelectedAnalyses()[4]);
        assertEquals("microglia_processes", parsed.getObject().getPresetName());
        assertEquals(Integer.valueOf(1), parsed.getObject().getNuclearMarkerIndex());

        CLIConfig reparsed = CLIArgumentParser.parse(CLIArgumentParser.serialize(parsed));
        assertEquals("microglia_processes", reparsed.getObject().getPresetName());
        assertEquals(Integer.valueOf(1), reparsed.getObject().getNuclearMarkerIndex());
    }
}
