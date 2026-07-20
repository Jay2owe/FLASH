package flash.pipeline.analyses.wizard;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpatialSetupConfigTextureTest {

    @Test
    public void textureNoneLeavesAllObjectTextureTogglesOff() {
        SpatialSetupConfig.DerivedConfig config = derive(SpatialSetupConfig.TEXTURE_NONE, 4);

        assertFalse(config.doObjectGLCM);
        assertFalse(config.doObjectFractal);
        assertFalse(config.doObjectTextureClass);
        assertFalse(config.doNative3DTexture);
        assertEquals(4, config.textureClassK);
    }

    @Test
    public void textureGlcmEnablesOnlyGlcm() {
        SpatialSetupConfig.DerivedConfig config = derive(SpatialSetupConfig.TEXTURE_GLCM, 4);

        assertTrue(config.doObjectGLCM);
        assertFalse(config.doObjectFractal);
        assertFalse(config.doObjectTextureClass);
    }

    @Test
    public void textureFractalEnablesOnlyFractal() {
        SpatialSetupConfig.DerivedConfig config = derive(SpatialSetupConfig.TEXTURE_FRACTAL, 4);

        assertFalse(config.doObjectGLCM);
        assertTrue(config.doObjectFractal);
        assertFalse(config.doObjectTextureClass);
    }

    @Test
    public void textureClassEnablesOnlyTextureClassAndClampsK() {
        SpatialSetupConfig.DerivedConfig low = derive(SpatialSetupConfig.TEXTURE_CLASS, 1);
        SpatialSetupConfig.DerivedConfig high = derive(SpatialSetupConfig.TEXTURE_CLASS, 99);

        assertFalse(low.doObjectGLCM);
        assertFalse(low.doObjectFractal);
        assertTrue(low.doObjectTextureClass);
        assertEquals(2, low.textureClassK);
        assertEquals(10, high.textureClassK);
    }

    @Test
    public void textureAllEnablesAllObjectTextureToggles() {
        SpatialSetupConfig.DerivedConfig config = derive(SpatialSetupConfig.TEXTURE_ALL, 6);

        assertTrue(config.doObjectGLCM);
        assertTrue(config.doObjectFractal);
        assertTrue(config.doObjectTextureClass);
        assertEquals(6, config.textureClassK);
    }

    @Test
    public void native3DTextureToggleRoundTripsThroughDerivedConfig() {
        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("spatial.question", SpatialSetupConfig.SPATIAL_NONE);
        answers.put("morph.question", SpatialSetupConfig.MORPH_NONE);
        answers.put("texture.question", SpatialSetupConfig.TEXTURE_NONE);
        answers.put("texture.k", Integer.valueOf(4));
        answers.put("spatial.texture.native3d", Boolean.TRUE);

        SpatialSetupConfig.DerivedConfig config =
                SpatialSetupConfig.deriveConfig(null, null, true, answers);

        assertTrue(config.doNative3DTexture);
        assertTrue(config.anyEarlyPhaseToggleOn());
    }

    private static SpatialSetupConfig.DerivedConfig derive(String textureOption, int k) {
        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("spatial.question", SpatialSetupConfig.SPATIAL_NONE);
        answers.put("morph.question", SpatialSetupConfig.MORPH_NONE);
        answers.put("texture.question", textureOption);
        answers.put("texture.k", Integer.valueOf(k));
        return SpatialSetupConfig.deriveConfig(null, null, true, answers);
    }
}
