package flash.pipeline.analyses.wizard;

import flash.pipeline.ui.wizard.JsonIO;

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
import static org.junit.Assert.assertTrue;

public class ThreeDObjectPresetIOTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void stockPresetsBootstrapWhenDirectoryIsEmpty() throws Exception {
        ThreeDObjectPresetIO io = new ThreeDObjectPresetIO(temp.newFolder("stock"));

        List<ThreeDObjectPreset> presets = io.listAll();

        assertEquals(6, presets.size());
        assertTrue(new File(io.presetDirectory(), "count_only.json").isFile());
        assertTrue(new File(io.presetDirectory(), "microglia_processes.json").isFile());
    }

    @Test
    public void roundTripSaveLoadAndDelete() throws Exception {
        ThreeDObjectPresetIO io = new ThreeDObjectPresetIO(temp.newFolder("roundtrip"));
        ThreeDObjectPreset preset = preset("My Object Preset", 42.0);

        io.save(preset);

        assertTrue(new File(temp.getRoot(), "roundtrip/FLASH/Presets/3D Object Analysis/my_object_preset.json").isFile());
        ThreeDObjectPreset loaded = io.load("my_object_preset");
        assertEquals("My Object Preset", loaded.getName());
        assertEquals(42.0, loaded.getColocThresholdPercent(), 0.0001);
        assertEquals("microglia", loaded.getProcessMarkerHints().get(0));

        io.delete("My Object Preset");
    }

    @Test
    public void atomicWriteLeavesOriginalOnFailedMove() throws Exception {
        CrashyThreeDObjectPresetIO io = new CrashyThreeDObjectPresetIO(temp.newFolder("atomic"));
        io.save(preset("Crash Test", 10.0));

        io.crashOnMove = true;
        try {
            io.save(preset("Crash Test", 20.0));
        } catch (IOException expected) {
            // expected
        }

        assertEquals(10.0, io.load("Crash Test").getColocThresholdPercent(), 0.0001);
        File[] leftovers = io.presetDirectory().listFiles((dir, name) -> name.endsWith(".tmp"));
        assertTrue(leftovers == null || leftovers.length == 0);
        String persisted = new String(Files.readAllBytes(new File(io.presetDirectory(), "crash_test.json").toPath()),
                StandardCharsets.UTF_8);
        assertTrue(persisted.contains("\"colocThresholdPercent\":10.0"));
    }

    @Test
    public void loadFindsLegacyProjectRootPresetFolder() throws Exception {
        File root = temp.newFolder("legacy-object");
        File legacyDir = new File(root, "3D Object Presets");
        assertTrue(legacyDir.mkdirs());
        Files.write(new File(legacyDir, "legacy_object.json").toPath(),
                JsonIO.write(preset("Legacy Object", 12.0).toJsonObject()).getBytes(StandardCharsets.UTF_8));
        ThreeDObjectPresetIO io = new ThreeDObjectPresetIO(root);

        ThreeDObjectPreset loaded = io.load("Legacy Object");

        assertEquals(12.0, loaded.getColocThresholdPercent(), 0.0001);
    }

    private static ThreeDObjectPreset preset(String name, double threshold) {
        return new ThreeDObjectPreset(name, "test", "1",
                true, true, true, false, false, threshold,
                Arrays.asList("microglia"), Arrays.asList("nuclei"));
    }

    private static final class CrashyThreeDObjectPresetIO extends ThreeDObjectPresetIO {
        boolean crashOnMove = false;

        private CrashyThreeDObjectPresetIO(File projectRoot) {
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
