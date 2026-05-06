package flash.pipeline.cli;

import flash.pipeline.deconv.engine.Algorithm;
import flash.pipeline.deconv.psf.ScopeModality;
import flash.pipeline.deconv.wizard.DeconvPresetIO;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DeconvPresetCliTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void presetValuesLoadAndExplicitFlagsOverrideFieldByField() throws Exception {
        File root = temp.newFolder("cli-presets");
        DeconvPresetIO io = new DeconvPresetIO(root);

        CLIConfig parsed = CLIArgumentParser.parse(
                "dir=[/tmp/data] deconv.preset=confocal_puncta deconv.iterations=30",
                io);

        assertNotNull(parsed);
        assertTrue(parsed.getSelectedAnalyses()[2]);
        assertTrue(parsed.getDeconv().isEnabled());
        assertEquals("confocal_puncta", parsed.getDeconv().getPresetName());
        assertEquals("CLIJ2", parsed.getDeconv().getEngine());
        assertEquals(Algorithm.RL_TV, parsed.getDeconv().getAlgorithm());
        assertEquals(30, parsed.getDeconv().getIterations());
        assertEquals(0.02, parsed.getDeconv().getRegularization(), 1e-12);
        assertEquals(ScopeModality.CONFOCAL, parsed.getDeconv().getScopeModality());
        assertEquals(1.0, parsed.getDeconv().getPinholeAiryUnits(), 1e-12);
    }
}
