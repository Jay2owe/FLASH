package flash.pipeline.analyses.wizard;

import flash.pipeline.ui.wizard.JsonIO;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class IntensityPresetIOTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void roundTripSaveAndLoad() throws Exception {
        File root = temp.newFolder("intensity-preset");
        IntensityPresetIO io = new IntensityPresetIO(root);
        Map<String, String> modes = new LinkedHashMap<String, String>();
        modes.put("2", IntensityWizard.MODE_THRESHOLD_MEAN);
        Map<String, String> thresholds = new LinkedHashMap<String, String>();
        thresholds.put("2", "45");

        IntensityPreset preset = new IntensityPreset("My Intensity Preset", "test", "1",
                "custom", IntensityWizard.MODE_WHOLE_ROI_MEAN, modes,
                thresholds, "NeuN", Arrays.asList("LH"));

        io.save(preset);

        assertTrue(new File(root,
                "FLASH/.settings/Presets/Fluorescence Intensity/my_intensity_preset.json").isFile());
        IntensityPreset loaded = io.load("my_intensity_preset");

        assertEquals("My Intensity Preset", loaded.getName());
        assertEquals(IntensityWizard.MODE_THRESHOLD_MEAN, loaded.getChannelModes().get("2"));
        assertEquals("45", loaded.getThresholds().get("2"));
        assertEquals("NeuN", loaded.getMaskChannelHint());
    }

    @Test
    public void stockPresetsBootstrapWhenDirectoryIsEmpty() throws Exception {
        File root = temp.newFolder("stock");
        IntensityPresetIO io = new IntensityPresetIO(root);

        List<IntensityPreset> presets = io.listAll();

        assertEquals(5, presets.size());
        assertTrue(new File(io.presetDirectory(), "threshold_puncta.json").isFile());
        assertTrue(new File(io.presetDirectory(), "neun_restricted.json").isFile());
    }

    @Test
    public void loadFindsLegacyProjectRootPresetFolder() throws Exception {
        File root = temp.newFolder("legacy-intensity");
        File legacyDir = new File(root, "Intensity Presets");
        assertTrue(legacyDir.mkdirs());
        IntensityPreset preset = new IntensityPreset("Legacy Intensity", "test", "1",
                "custom", IntensityWizard.MODE_WHOLE_ROI_MEAN,
                new LinkedHashMap<String, String>(), new LinkedHashMap<String, String>(),
                "", Arrays.asList("LH"));
        Files.write(new File(legacyDir, "legacy_intensity.json").toPath(),
                JsonIO.write(preset.toJsonObject()).getBytes(StandardCharsets.UTF_8));

        IntensityPreset loaded = new IntensityPresetIO(root).load("Legacy Intensity");

        assertEquals("Legacy Intensity", loaded.getName());
    }
}
