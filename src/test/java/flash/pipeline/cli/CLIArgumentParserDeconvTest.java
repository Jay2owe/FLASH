package flash.pipeline.cli;

import flash.pipeline.deconv.engine.Algorithm;
import flash.pipeline.deconv.psf.PsfModel;
import flash.pipeline.deconv.psf.ScopeModality;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CLIArgumentParserDeconvTest {

    @Test
    public void deconvolutionFlagsRoundTripThroughParseAndSerialize() {
        String options = "dir=[/tmp/data] run_deconv headless=true "
                + "deconv.enabled=true "
                + "deconv.engine=DL2 "
                + "deconv.algorithm=RL_TV "
                + "deconv.psf=BornWolf "
                + "deconv.iterations=22 "
                + "deconv.regularization=0.045 "
                + "deconv.scopeModality=confocal "
                + "deconv.pinholeAU=1.2 "
                + "deconv.sampleRI=1.41 "
                + "deconv.mountingMedium=prolong "
                + "deconv.channels=0,2,3 "
                + "deconv.strictNyquist=true "
                + "deconv.useCache=false "
                + "deconv.skipPreview=true "
                + "splitmerge.useDeconv=false "
                + "threeD.useDeconv=true "
                + "intensityV2.useDeconv=true";

        CLIConfig parsed = CLIArgumentParser.parse(options);
        assertNotNull(parsed);
        assertTrue(parsed.getSelectedAnalyses()[2]);
        assertEquals("DL2", parsed.getDeconv().getEngine());
        assertEquals(Algorithm.RL_TV, parsed.getDeconv().getAlgorithm());
        assertEquals(PsfModel.BORN_WOLF, parsed.getDeconv().getPsfModel());
        assertEquals(22, parsed.getDeconv().getIterations());
        assertEquals(0.045, parsed.getDeconv().getRegularization(), 1e-12);
        assertEquals(ScopeModality.CONFOCAL, parsed.getDeconv().getScopeModality());
        assertEquals(1.2, parsed.getDeconv().getPinholeAiryUnits(), 1e-12);
        assertEquals(1.41, parsed.getDeconv().getSampleRI(), 1e-12);
        assertEquals("prolong", parsed.getDeconv().getMountingMedium());
        assertArrayEquals(new int[]{0, 2, 3}, parsed.getDeconv().getChannels());
        assertTrue(parsed.getDeconv().isStrictNyquist());
        assertEquals(false, parsed.getDeconv().isUseCache());
        assertTrue(parsed.getDeconv().isSkipPreview());
        assertEquals(false, parsed.isSplitMergeUseDeconv());
        assertEquals(true, parsed.isThreeDUseDeconv());
        assertEquals(true, parsed.isIntensityV2UseDeconv());

        CLIConfig reparsed = CLIArgumentParser.parse(CLIArgumentParser.serialize(parsed));
        assertNotNull(reparsed);
        assertTrue(reparsed.getSelectedAnalyses()[2]);
        assertEquals(parsed.getDeconv().getEngine(), reparsed.getDeconv().getEngine());
        assertEquals(parsed.getDeconv().getAlgorithm(), reparsed.getDeconv().getAlgorithm());
        assertEquals(parsed.getDeconv().getPsfModel(), reparsed.getDeconv().getPsfModel());
        assertEquals(parsed.getDeconv().getIterations(), reparsed.getDeconv().getIterations());
        assertEquals(parsed.getDeconv().getRegularization(), reparsed.getDeconv().getRegularization(), 1e-12);
        assertEquals(parsed.getDeconv().getScopeModality(), reparsed.getDeconv().getScopeModality());
        assertEquals(parsed.getDeconv().getPinholeAiryUnits(), reparsed.getDeconv().getPinholeAiryUnits());
        assertEquals(parsed.getDeconv().getSampleRI(), reparsed.getDeconv().getSampleRI());
        assertEquals(parsed.getDeconv().getMountingMedium(), reparsed.getDeconv().getMountingMedium());
        assertArrayEquals(parsed.getDeconv().getChannels(), reparsed.getDeconv().getChannels());
        assertEquals(parsed.getDeconv().isStrictNyquist(), reparsed.getDeconv().isStrictNyquist());
        assertEquals(parsed.getDeconv().isUseCache(), reparsed.getDeconv().isUseCache());
        assertEquals(parsed.getDeconv().isSkipPreview(), reparsed.getDeconv().isSkipPreview());
        assertEquals(parsed.isSplitMergeUseDeconv(), reparsed.isSplitMergeUseDeconv());
        assertEquals(parsed.isThreeDUseDeconv(), reparsed.isThreeDUseDeconv());
        assertEquals(parsed.isIntensityV2UseDeconv(), reparsed.isIntensityV2UseDeconv());
    }
}
