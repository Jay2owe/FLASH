package flash.pipeline.decontamination.wizard;

import flash.pipeline.decontamination.CorrectionPipeline;
import flash.pipeline.decontamination.SpectralDecontaminationConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpectralDecontamPresetIOTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void roundTripSaveLoadDelete() throws Exception {
        File root = temp.newFolder("roundtrip");
        SpectralDecontamPresetIO io = new SpectralDecontamPresetIO(root);

        io.save(preset("My Spectral Preset"));

        SpectralDecontamPreset loaded = io.load("my_spectral_preset");
        assertEquals("My Spectral Preset", loaded.getName());
        assertEquals(2, loaded.getPayload().getTargetChannelIndex());
        assertEquals(Arrays.asList("saturation_exclusion", "linear_unmixing"),
                loaded.getPayload().getCorrectionPipeline().getFeatureIds());

        io.delete("My Spectral Preset");
        assertFalse(new File(io.presetDirectory(), "my_spectral_preset.json").exists());
    }

    @Test
    public void stockPresetsBootstrapWhenDirectoryIsEmpty() throws Exception {
        File root = temp.newFolder("stock");
        SpectralDecontamPresetIO io = new SpectralDecontamPresetIO(root);

        List<SpectralDecontamPreset> presets = io.listAll();

        assertEquals(7, presets.size());
        assertTrue(new File(io.presetDirectory(), "combined_aggressive.json").isFile());
        assertTrue(new File(io.presetDirectory(), "score_existing_objects.json").isFile());
    }

    @Test
    public void atomicWriteLeavesOriginalOnFailedMove() throws Exception {
        File root = temp.newFolder("atomic");
        CrashySpectralPresetIO io = new CrashySpectralPresetIO(root);
        io.save(preset("Crash Test"));

        io.crashOnMove = true;
        try {
            io.save(preset("Crash Test"));
        } catch (IOException expected) {
            // expected
        }

        String persisted = new String(Files.readAllBytes(
                new File(io.presetDirectory(), "crash_test.json").toPath()), StandardCharsets.UTF_8);
        assertTrue(persisted.contains("\"targetChannelIndex\":2"));
        File[] leftovers = io.presetDirectory().listFiles((dir, name) -> name.endsWith(".tmp"));
        assertTrue(leftovers == null || leftovers.length == 0);
    }

    private static SpectralDecontamPreset preset(String name) {
        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(2);
        CorrectionPipeline pipeline = new CorrectionPipeline();
        pipeline.setFeatureIds(Arrays.asList("saturation_exclusion", "linear_unmixing"));
        config.setCorrectionPipeline(pipeline);
        return new SpectralDecontamPreset(name, "test", "1", config,
                "bleedthrough", "manual", "standard");
    }

    private static final class CrashySpectralPresetIO extends SpectralDecontamPresetIO {
        boolean crashOnMove = false;

        private CrashySpectralPresetIO(File projectRoot) {
            super(projectRoot);
        }

        @Override
        protected void moveAtomically(File source, File target) throws IOException {
            if (crashOnMove) {
                throw new IOException("simulated crash");
            }
            super.moveAtomically(source, target);
        }
    }
}
