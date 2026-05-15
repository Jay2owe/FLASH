package flash.pipeline.analyses.wizard;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpatialAnalysisWizardTextureTest {

    @Test
    public void textureNoneLeavesAllObjectTextureTogglesOff() {
        SpatialAnalysisWizard.DerivedConfig config = derive(SpatialAnalysisWizard.TEXTURE_NONE, 4);

        assertFalse(config.doObjectGLCM);
        assertFalse(config.doObjectFractal);
        assertFalse(config.doObjectTextureClass);
        assertEquals(4, config.textureClassK);
    }

    @Test
    public void textureGlcmEnablesOnlyGlcm() {
        SpatialAnalysisWizard.DerivedConfig config = derive(SpatialAnalysisWizard.TEXTURE_GLCM, 4);

        assertTrue(config.doObjectGLCM);
        assertFalse(config.doObjectFractal);
        assertFalse(config.doObjectTextureClass);
    }

    @Test
    public void textureFractalEnablesOnlyFractal() {
        SpatialAnalysisWizard.DerivedConfig config = derive(SpatialAnalysisWizard.TEXTURE_FRACTAL, 4);

        assertFalse(config.doObjectGLCM);
        assertTrue(config.doObjectFractal);
        assertFalse(config.doObjectTextureClass);
    }

    @Test
    public void textureClassEnablesOnlyTextureClassAndClampsK() {
        SpatialAnalysisWizard.DerivedConfig low = derive(SpatialAnalysisWizard.TEXTURE_CLASS, 1);
        SpatialAnalysisWizard.DerivedConfig high = derive(SpatialAnalysisWizard.TEXTURE_CLASS, 99);

        assertFalse(low.doObjectGLCM);
        assertFalse(low.doObjectFractal);
        assertTrue(low.doObjectTextureClass);
        assertEquals(2, low.textureClassK);
        assertEquals(10, high.textureClassK);
    }

    @Test
    public void textureAllEnablesAllObjectTextureToggles() {
        SpatialAnalysisWizard.DerivedConfig config = derive(SpatialAnalysisWizard.TEXTURE_ALL, 6);

        assertTrue(config.doObjectGLCM);
        assertTrue(config.doObjectFractal);
        assertTrue(config.doObjectTextureClass);
        assertEquals(6, config.textureClassK);
    }

    @Test
    public void textureScreenDefaultsAreRegisteredBetweenMorphometryAndHeatmapDefaults() {
        SpatialAnalysisWizard wizard = new SpatialAnalysisWizard(
                flash.pipeline.ui.wizard.WizardFlow.MainPanelBinding.NULL,
                null,
                null,
                true,
                false,
                true);
        List<String> keys = new ArrayList<String>(wizard.currentAnswers().keySet());

        assertTrue(keys.indexOf("morph.question") < keys.indexOf("texture.question"));
        assertTrue(keys.indexOf("texture.question") < keys.indexOf("heatmap.lut"));
        assertEquals(Integer.valueOf(4), wizard.currentAnswers().get("texture.k"));
    }

    private static SpatialAnalysisWizard.DerivedConfig derive(String textureOption, int k) {
        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("spatial.question", SpatialAnalysisWizard.SPATIAL_NONE);
        answers.put("morph.question", SpatialAnalysisWizard.MORPH_NONE);
        answers.put("texture.question", textureOption);
        answers.put("texture.k", Integer.valueOf(k));
        return SpatialAnalysisWizard.deriveConfig(null, null, true, answers);
    }
}
