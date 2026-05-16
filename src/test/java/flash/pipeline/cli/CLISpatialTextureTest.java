package flash.pipeline.cli;

import flash.pipeline.analyses.SpatialAnalysis;
import flash.pipeline.analyses.wizard.SpatialAnalysisWizard;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CLISpatialTextureTest {

    @Test
    public void parsesAndSerializesSpatialTextureOptions() {
        CLIConfig parsed = CLIArgumentParser.parse("dir=[/tmp/data] "
                + "spatial.texture.glcm=true "
                + "spatial.texture.fractal=true "
                + "spatial.texture.class=true "
                + "spatial.texture.classfractions=true "
                + "spatial.texture.native3d=true "
                + "spatial.texture.k=6");

        assertTrue(parsed.getSelectedAnalyses()[5]);
        assertEquals(Boolean.TRUE, parsed.getSpatial().getTextureGlcm());
        assertEquals(Boolean.TRUE, parsed.getSpatial().getTextureFractal());
        assertEquals(Boolean.TRUE, parsed.getSpatial().getTextureClass());
        assertEquals(Boolean.TRUE, parsed.getSpatial().getTextureClassFractions());
        assertEquals(Boolean.TRUE, parsed.getSpatial().getTextureNative3D());
        assertEquals(Integer.valueOf(6), parsed.getSpatial().getTextureClassK());

        CLIConfig reparsed = CLIArgumentParser.parse(CLIArgumentParser.serialize(parsed));
        assertEquals(Boolean.TRUE, reparsed.getSpatial().getTextureGlcm());
        assertEquals(Boolean.TRUE, reparsed.getSpatial().getTextureFractal());
        assertEquals(Boolean.TRUE, reparsed.getSpatial().getTextureClass());
        assertEquals(Boolean.TRUE, reparsed.getSpatial().getTextureClassFractions());
        assertEquals(Boolean.TRUE, reparsed.getSpatial().getTextureNative3D());
        assertEquals(Integer.valueOf(6), reparsed.getSpatial().getTextureClassK());
    }

    @Test
    public void cliSpatialOverridesPopulateDerivedTextureConfig() throws Exception {
        CLIConfig parsed = CLIArgumentParser.parse("dir=[/tmp/data] "
                + "spatial.texture.glcm=true "
                + "spatial.texture.fractal=true "
                + "spatial.texture.class=true "
                + "spatial.texture.classfractions=true "
                + "spatial.texture.native3d=true "
                + "spatial.texture.k=6");
        SpatialAnalysisWizard.DerivedConfig derived =
                new SpatialAnalysisWizard.DerivedConfig();

        Method method = SpatialAnalysis.class.getDeclaredMethod("applyCliSpatialOverrides",
                SpatialAnalysisWizard.DerivedConfig.class, CLIConfig.SpatialConfig.class);
        method.setAccessible(true);
        method.invoke(null, derived, parsed.getSpatial());

        assertTrue(derived.doObjectGLCM);
        assertTrue(derived.doObjectFractal);
        assertTrue(derived.doObjectTextureClass);
        assertTrue(derived.doObjectTextureClassFractions);
        assertTrue(derived.doNative3DTexture);
        assertEquals(6, derived.textureClassK);
    }
}
