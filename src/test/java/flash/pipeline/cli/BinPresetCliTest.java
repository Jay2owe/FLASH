package flash.pipeline.cli;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BinPresetCliTest {

    @Test
    public void binPresetAndChannelOverrideParseAndSerialize() {
        CLIConfig parsed = CLIArgumentParser.parse(
                "dir=[/tmp/data] bin.preset=synaptic_puncta bin.channel2_threshold=20");

        assertTrue(parsed.getSelectedAnalyses()[0]);
        assertEquals("synaptic_puncta", parsed.getBin().getPresetName());
        assertEquals("20", parsed.getBin().getObjectThresholds().get(Integer.valueOf(1)));

        CLIConfig reparsed = CLIArgumentParser.parse(CLIArgumentParser.serialize(parsed));
        assertEquals("synaptic_puncta", reparsed.getBin().getPresetName());
        assertEquals("20", reparsed.getBin().getObjectThresholds().get(Integer.valueOf(1)));
    }
}
