package flash.pipeline.cli;

import flash.pipeline.deconv.engine.Algorithm;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DeconvPerChannelCliTest {

    @Test
    public void parsesPerChannelOverridesOnTopOfGlobals() {
        CLIConfig cfg = CLIArgumentParser.parse(
                "dir=[/tmp] deconv.engine=DL2 deconv.iterations=15 "
                        + "deconv.ch0.algorithm=TIKHONOV "
                        + "deconv.ch1.engine=CLIJ2 deconv.ch1.iterations=30");
        CLIConfig.DeconvConfig deconv = cfg.getDeconv();
        assertTrue(deconv.isEnabled());

        Map<Integer, CLIConfig.DeconvConfig.ChannelOverride> perChannel = deconv.getPerChannel();
        assertEquals(2, perChannel.size());

        CLIConfig.DeconvConfig.ChannelOverride ch0 = perChannel.get(Integer.valueOf(0));
        assertEquals(Algorithm.TIKHONOV, ch0.getAlgorithm());
        assertNull("ch0 iterations not set -> falls back to global", ch0.getIterations());
        assertNull(ch0.getEngine());

        CLIConfig.DeconvConfig.ChannelOverride ch1 = perChannel.get(Integer.valueOf(1));
        assertNotNull(ch1.getEngine());
        assertEquals(Integer.valueOf(30), ch1.getIterations());
    }

    @Test
    public void noPerChannelFlagsLeavesMapEmpty() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[/tmp] deconv.engine=DL2");
        assertTrue(cfg.getDeconv().getPerChannel().isEmpty());
    }
}
