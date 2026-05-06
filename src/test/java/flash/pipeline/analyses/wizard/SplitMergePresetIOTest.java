package flash.pipeline.analyses.wizard;

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

public class SplitMergePresetIOTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void roundTripSaveLoadAndDelete() throws Exception {
        File root = temp.newFolder("split-merge-preset");
        SplitMergePresetIO io = new SplitMergePresetIO(root);
        SplitMergePreset preset = preset("My Split Merge Preset", "Manual");

        io.save(preset);

        SplitMergePreset loaded = io.load("my_split_merge_preset");
        assertEquals("My Split Merge Preset", loaded.getName());
        assertEquals("Manual", loaded.methodForChannel(0, "Automatic"));
        assertEquals(0.5, loaded.saturationForChannel(0, 0.35), 0.001);

        io.delete("My Split Merge Preset");
        assertFalse(new File(io.presetDirectory(), "my_split_merge_preset.json").exists());
    }

    @Test
    public void stockPresetsBootstrapWhenDirectoryIsEmpty() throws Exception {
        File root = temp.newFolder("stock");
        SplitMergePresetIO io = new SplitMergePresetIO(root);

        List<SplitMergePreset> presets = io.listAll();

        assertEquals(4, presets.size());
        assertTrue(new File(io.presetDirectory(), "Preview QC (auto-contrast).json").isFile());
        assertTrue(new File(io.presetDirectory(), "Subtract autofluorescence + figure-ready.json").isFile());
    }

    @Test
    public void atomicWriteLeavesOriginalOnFailedMove() throws Exception {
        File root = temp.newFolder("atomic");
        CrashySplitMergePresetIO io = new CrashySplitMergePresetIO(root);
        io.save(preset("Crash Test", "Automatic"));

        io.crashOnMove = true;
        try {
            io.save(preset("Crash Test", "Manual"));
        } catch (IOException expected) {
            // expected
        }

        assertEquals("Automatic", io.load("Crash Test").methodForChannel(0, "None"));
        File[] leftovers = io.presetDirectory().listFiles((dir, name) -> name.endsWith(".tmp"));
        assertTrue(leftovers == null || leftovers.length == 0);
        String persisted = new String(Files.readAllBytes(new File(io.presetDirectory(), "crash_test.json").toPath()),
                StandardCharsets.UTF_8);
        assertTrue(persisted.contains("\"Automatic\""));
    }

    private static SplitMergePreset preset(String name, String method) {
        return new SplitMergePreset(name, "test",
                Arrays.asList(method),
                Arrays.asList("100-5000"),
                Arrays.asList(Double.valueOf(0.5)),
                true,
                true,
                "1-2",
                true,
                2,
                Arrays.asList(Boolean.TRUE, Boolean.TRUE, Boolean.FALSE));
    }

    private static final class CrashySplitMergePresetIO extends SplitMergePresetIO {
        boolean crashOnMove = false;

        private CrashySplitMergePresetIO(File projectRoot) {
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
