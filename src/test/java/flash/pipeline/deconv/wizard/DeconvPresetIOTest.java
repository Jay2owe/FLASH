package flash.pipeline.deconv.wizard;

import flash.pipeline.deconv.engine.Algorithm;
import flash.pipeline.deconv.psf.PsfModel;
import flash.pipeline.deconv.psf.ScopeModality;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DeconvPresetIOTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void roundTripSaveLoadAndDelete() throws Exception {
        File root = temp.newFolder("preset-io");
        DeconvPresetIO io = new DeconvPresetIO(root);
        DeconvPreset preset = new DeconvPreset(
                "My Deep Tissue Preset",
                "round-trip",
                "DL2",
                Algorithm.RL_TV,
                PsfModel.GIBSON_LANNI,
                18,
                0.02,
                ScopeModality.CONFOCAL,
                Double.valueOf(1.1),
                Double.valueOf(1.45));

        io.save(preset);

        assertEquals(preset, io.load("My Deep Tissue Preset"));
        assertEquals(preset, io.load("my_deep_tissue_preset"));

        List<DeconvPreset> all = io.listAll();
        assertEquals(1, all.size());
        assertEquals("My Deep Tissue Preset", all.get(0).getName());

        io.delete("My Deep Tissue Preset");
        assertFalse(new File(io.presetDirectory(), "my_deep_tissue_preset.json").exists());
    }

    @Test
    public void stockPresetsBootstrapWhenDirectoryIsEmpty() throws Exception {
        File root = temp.newFolder("preset-bootstrap");
        DeconvPresetIO io = new DeconvPresetIO(root);

        List<DeconvPreset> presets = io.listAll();

        assertEquals(4, presets.size());
        assertEquals("Cleared Tissue — Gibson-Lanni 20", presets.get(0).getName());
        assertEquals("Confocal Puncta — CLIJ2 RL-TV 20", presets.get(1).getName());
        assertEquals("Default — Gibson-Lanni, RL 15", presets.get(2).getName());
        assertEquals("Widefield Morphology — DL2 RL 10", presets.get(3).getName());
        assertTrue(new File(io.presetDirectory(), "default.json").isFile());
        assertTrue(new File(io.presetDirectory(), "confocal_puncta.json").isFile());
    }

    @Test
    public void atomicWriteSurvivesSimulatedCrash() throws Exception {
        File root = temp.newFolder("preset-crash");
        CrashyPresetIO io = new CrashyPresetIO(root);
        DeconvPreset original = new DeconvPreset(
                "Crash Test",
                null,
                "CLIJ2",
                Algorithm.RL_TV,
                PsfModel.GIBSON_LANNI,
                15,
                0.01,
                ScopeModality.WIDEFIELD,
                null,
                Double.valueOf(1.33));
        io.save(original);

        DeconvPreset updated = new DeconvPreset(
                "Crash Test",
                null,
                "CLIJ2",
                Algorithm.RL_TV,
                PsfModel.GIBSON_LANNI,
                30,
                0.02,
                ScopeModality.WIDEFIELD,
                null,
                Double.valueOf(1.33));

        io.crashOnMove = true;
        try {
            io.save(updated);
        } catch (IOException expected) {
            // expected
        }

        assertEquals(original, io.load("Crash Test"));
        File[] leftovers = io.presetDirectory().listFiles((dir, name) -> name.endsWith(".tmp"));
        assertTrue(leftovers == null || leftovers.length == 0);
        String persisted = new String(Files.readAllBytes(new File(io.presetDirectory(), "crash_test.json").toPath()),
                StandardCharsets.UTF_8);
        assertTrue(persisted.contains("\"iterations\":15"));
    }

    private static final class CrashyPresetIO extends DeconvPresetIO {
        boolean crashOnMove = false;

        private CrashyPresetIO(File projectRoot) {
            super(projectRoot);
        }

        @Override
        protected void moveAtomically(File source, File target) throws IOException {
            if (crashOnMove) {
                throw new IOException("simulated crash before atomic move");
            }
            super.moveAtomically(source, target);
        }
    }
}
