package flash.pipeline.analyses.wizard;

import flash.pipeline.bin.BinConfig;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BinPresetIOTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void roundTripSaveLoadAndDelete() throws Exception {
        File root = temp.newFolder("bin-preset");
        BinPresetIO io = new BinPresetIO(root);
        BinPreset preset = preset("My Bin Preset", "12");

        io.save(preset);

        assertTrue(new File(root, "FLASH/Presets/Channel Configuration/my_bin_preset.json").isFile());
        assertFalse(new File(root, "Bin Presets/my_bin_preset.json").exists());
        BinPreset loaded = io.load("my_bin_preset");
        assertEquals("My Bin Preset", loaded.getName());
        assertEquals("12", loaded.getPayload().channelThresholds.get(0));
        assertEquals("microglia_iba1", loaded.getMarkerIds().get(0));

        io.delete("My Bin Preset");
        assertFalse(new File(io.presetDirectory(), "my_bin_preset.json").exists());
    }

    @Test
    public void stockPresetsBootstrapWhenDirectoryIsEmpty() throws Exception {
        File root = temp.newFolder("stock");
        BinPresetIO io = new BinPresetIO(root);

        List<BinPreset> presets = io.listAll();

        assertEquals(4, presets.size());
        assertTrue(new File(io.presetDirectory(), "dapi_iba1_gfap.json").isFile());
        assertTrue(new File(io.presetDirectory(), "synaptic_puncta.json").isFile());
    }

    @Test
    public void atomicWriteLeavesOriginalOnFailedMove() throws Exception {
        File root = temp.newFolder("atomic");
        CrashyBinPresetIO io = new CrashyBinPresetIO(root);
        io.save(preset("Crash Test", "10"));

        io.crashOnMove = true;
        try {
            io.save(preset("Crash Test", "20"));
        } catch (IOException expected) {
            // expected
        }

        assertEquals("10", io.load("Crash Test").getPayload().channelThresholds.get(0));
        File[] leftovers = io.presetDirectory().listFiles((dir, name) -> name.endsWith(".tmp"));
        assertTrue(leftovers == null || leftovers.length == 0);
        String persisted = new String(Files.readAllBytes(new File(io.presetDirectory(), "crash_test.json").toPath()),
                StandardCharsets.UTF_8);
        assertTrue(persisted.contains("\"objectThreshold\":\"10\""));
    }

    @Test
    public void loadFindsLegacyProjectRootPresetFolder() throws Exception {
        File root = temp.newFolder("legacy-bin-preset");
        File legacyDir = new File(root, "Bin Presets");
        assertTrue(legacyDir.mkdirs());
        Files.write(new File(legacyDir, "legacy_setup.json").toPath(),
                JsonIO.write(preset("Legacy Setup", "33").toJsonObject()).getBytes(StandardCharsets.UTF_8));
        BinPresetIO io = new BinPresetIO(root);

        BinPreset loaded = io.load("Legacy Setup");

        assertEquals("Legacy Setup", loaded.getName());
        assertEquals("33", loaded.getPayload().channelThresholds.get(0));
    }

    private static BinPreset preset(String name, String threshold) {
        BinConfig config = new BinConfig();
        config.channelNames.add("IBA1");
        config.channelColors.add("Green");
        config.channelThresholds.add(threshold);
        config.channelSizes.add("100-50000");
        config.channelMinMax.add("100-65535");
        config.channelIntensityThresholds.add("10");
        config.segmentationMethods.add("classical");
        config.channelFilterPresets.add("Ramified Cells (Microglia/Astrocytes)");
        return new BinPreset(name, "test", "1", config,
                Arrays.asList("microglia_iba1"),
                Arrays.asList("complex"),
                Arrays.asList(Boolean.TRUE));
    }

    private static final class CrashyBinPresetIO extends BinPresetIO {
        boolean crashOnMove = false;

        private CrashyBinPresetIO(File projectRoot) {
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
