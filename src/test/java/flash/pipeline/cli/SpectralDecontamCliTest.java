package flash.pipeline.cli;

import flash.pipeline.decontamination.SpectralDecontaminationConfig;
import flash.pipeline.decontamination.wizard.SpectralDecontaminationWizard;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SpectralDecontamCliTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void spectralPresetAndTargetOverrideParseAndSerialize() {
        CLIConfig parsed = CLIArgumentParser.parse(
                "dir=[/tmp/data] spectral.preset=combined_aggressive spectral.target=0");

        assertTrue(parsed.getSelectedAnalyses()[12]);
        assertEquals("combined_aggressive", parsed.getSpectral().getPresetName());
        assertEquals(Integer.valueOf(0), parsed.getSpectral().getTargetChannelIndex());

        CLIConfig reparsed = CLIArgumentParser.parse(CLIArgumentParser.serialize(parsed));
        assertEquals("combined_aggressive", reparsed.getSpectral().getPresetName());
        assertEquals(Integer.valueOf(0), reparsed.getSpectral().getTargetChannelIndex());
    }

    @Test
    public void stockPresetLoadsWithFieldOverride() throws Exception {
        File root = temp.newFolder("project");

        SpectralDecontaminationConfig config = SpectralDecontaminationWizard.applyCliOverrides(
                new SpectralDecontaminationConfig(),
                "patchy_autofluorescence",
                null,
                Integer.valueOf(2),
                null,
                null,
                null,
                null,
                null,
                root);

        assertEquals(2, config.getTargetChannelIndex());
        assertEquals("local_k_correction", config.getCorrectionPipeline().getFeatureIds().get(1));
        assertTrue(config.getCorrectionPipeline().getFeatureIds().contains("veto_masks"));
    }
}
