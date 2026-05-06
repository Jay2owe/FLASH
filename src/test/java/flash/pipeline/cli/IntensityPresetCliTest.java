package flash.pipeline.cli;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
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
}
