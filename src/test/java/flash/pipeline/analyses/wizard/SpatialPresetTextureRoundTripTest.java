package flash.pipeline.analyses.wizard;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SpatialPresetTextureRoundTripTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void roundTripPreservesObjectTextureFields() throws Exception {
        File root = temp.newFolder("texture-preset");
        SpatialPresetIO io = new SpatialPresetIO(root);
        SpatialPreset preset = new SpatialPreset("Texture Preset", "test", "1",
                true, false, true, true, false, true, false,
                true, true, true, false, false,
                true, true, true, true, true, 6,
                4.5, "Cyan", 3, 30.0);

        io.save(preset);

        SpatialPreset loaded = io.load("Texture Preset");
        assertEquals(preset.getName(), loaded.getName());
        assertEquals(preset.isDoObjectGLCM(), loaded.isDoObjectGLCM());
        assertEquals(preset.isDoObjectFractal(), loaded.isDoObjectFractal());
        assertEquals(preset.isDoObjectTextureClass(), loaded.isDoObjectTextureClass());
        assertEquals(preset.isDoObjectTextureClassFractions(), loaded.isDoObjectTextureClassFractions());
        assertEquals(preset.isDoNative3DTexture(), loaded.isDoNative3DTexture());
        assertEquals(preset.getTextureClassK(), loaded.getTextureClassK());
        assertEquals(preset.toJsonObject(), loaded.toJsonObject());

        SpatialAnalysisWizard.DerivedConfig derived = SpatialAnalysisWizard.fromPreset(loaded);
        assertTrue(derived.doObjectGLCM);
        assertTrue(derived.doObjectFractal);
        assertTrue(derived.doObjectTextureClass);
        assertTrue(derived.doObjectTextureClassFractions);
        assertTrue(derived.doNative3DTexture);
        assertEquals(6, derived.textureClassK);
    }

    @Test
    public void oldPresetResourceLoadsWithTextureDefaultsOff() throws Exception {
        InputStream stream = getClass().getResourceAsStream(
                "/spatial_presets/microglia_morphology.json");
        assertNotNull(stream);
        String json;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            json = new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            stream.close();
        }

        SpatialPreset loaded = SpatialPreset.fromJson(json);

        assertFalse(loaded.isDoObjectGLCM());
        assertFalse(loaded.isDoObjectFractal());
        assertFalse(loaded.isDoObjectTextureClass());
        assertFalse(loaded.isDoObjectTextureClassFractions());
        assertFalse(loaded.isDoNative3DTexture());
        assertEquals(4, loaded.getTextureClassK());

        SpatialAnalysisWizard.DerivedConfig derived = SpatialAnalysisWizard.fromPreset(loaded);
        assertFalse(derived.doObjectGLCM);
        assertFalse(derived.doObjectFractal);
        assertFalse(derived.doObjectTextureClass);
        assertFalse(derived.doObjectTextureClassFractions);
        assertFalse(derived.doNative3DTexture);
        assertEquals(4, derived.textureClassK);
    }
}
