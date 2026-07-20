package flash.pipeline.cli;

import flash.pipeline.deconv.engine.Algorithm;
import flash.pipeline.deconv.psf.PsfModel;
import flash.pipeline.deconv.psf.ScopeModality;
import flash.pipeline.deconv.wizard.DeconvPreset;
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

    @Test
    public void publicParseLoadsProjectPresetFromDir() throws Exception {
        File root = temp.newFolder("cli-project-presets");
        DeconvPreset preset = new DeconvPreset(
                "Local Research Preset",
                null,
                "DL2",
                Algorithm.WIENER,
                PsfModel.DOUGHERTY_THEORETICAL,
                37,
                0.04,
                ScopeModality.SPINNING_DISK,
                null,
                Double.valueOf(1.46));
        new DeconvPresetIO(root).save(preset);

        CLIConfig parsed = CLIArgumentParser.parse(
                "dir=[" + root.getAbsolutePath() + "] deconv.preset=[Local Research Preset]");

        assertNotNull(parsed);
        assertTrue(parsed.getDeconv().isEnabled());
        assertEquals("Local Research Preset", parsed.getDeconv().getPresetName());
        assertEquals("DL2", parsed.getDeconv().getEngine());
        assertEquals(Algorithm.WIENER, parsed.getDeconv().getAlgorithm());
        assertEquals(PsfModel.DOUGHERTY_THEORETICAL, parsed.getDeconv().getPsfModel());
        assertEquals(37, parsed.getDeconv().getIterations());
        assertEquals(0.04, parsed.getDeconv().getRegularization(), 1e-12);
        assertEquals(ScopeModality.SPINNING_DISK, parsed.getDeconv().getScopeModality());
        assertEquals(1.46, parsed.getDeconv().getSampleRI(), 1e-12);
    }
}
