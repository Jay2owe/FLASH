package flash.pipeline.analyses.wizard;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpatialPresetIOTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void roundTripSaveAndLoad() throws Exception {
        File root = temp.newFolder("spatial-preset");
        SpatialPresetIO io = new SpatialPresetIO(root);

        SpatialPreset preset = new SpatialPreset("My Spatial Preset", "test", "1",
                true, false, true, true, false, true, false,
                false, true, true, false, false,
                4.5, "Cyan", 3, 30.0);

        io.save(preset);

        assertTrue(new File(root,
                "FLASH/.settings/Presets/Spatial Analysis/my_spatial_preset.json").isFile());
        SpatialPreset loaded = io.load("my_spatial_preset");

        assertEquals("My Spatial Preset", loaded.getName());
        assertTrue(loaded.isDoDistances());
        assertTrue(loaded.isDoVolColoc());
        assertTrue(loaded.isDo3DMorphology());
        assertEquals("Cyan", loaded.getHeatmapLut());
        assertEquals(3, loaded.getClusterK());
    }

    @Test
    public void roundTripPreservesBoundingBoxFields() throws Exception {
        File root = temp.newFolder("spatial-preset-bb");
        SpatialPresetIO io = new SpatialPresetIO(root);

        SpatialPreset preset = new SpatialPreset("BB Preset", "test", "1",
                true, false, false, false, false, false, false,
                false, false, false, false, false,
                false, false, false, false, false, 4,
                0.0, "Fire", 0, 30.0,
                true, true, false, 45.0);

        io.save(preset);
        SpatialPreset loaded = io.load("BB Preset");

        assertTrue(loaded.isDoBBOverlap());
        assertTrue(loaded.isDoBBCpc());
        assertFalse(loaded.isDoBBVol());
        assertEquals(45.0, loaded.getBBColocThresholdPercent(), 0.0001);

        SpatialSetupConfig.DerivedConfig derived = SpatialSetupConfig.fromPreset(loaded);
        assertTrue(derived.doBBOverlap);
        assertTrue(derived.doBBCpc);
        assertFalse(derived.doBBVol);
        assertEquals(45.0, derived.bbColocThresholdPercent, 0.0001);
    }

    @Test
    public void legacyPresetWithoutBoundingBoxKeysDefaultsOff() throws Exception {
        File root = temp.newFolder("spatial-preset-legacy");
        SpatialPresetIO io = new SpatialPresetIO(root);
        assertTrue(io.presetDirectory().mkdirs());
        File legacy = new File(io.presetDirectory(), "legacy.json");
        Files.write(legacy.toPath(), ("{"
                + "\"name\":\"Legacy\","
                + "\"doCpc\":true"
                + "}").getBytes(StandardCharsets.UTF_8));

        SpatialPreset loaded = io.load("Legacy");

        assertFalse(loaded.isDoBBOverlap());
        assertFalse(loaded.isDoBBCpc());
        assertFalse(loaded.isDoBBVol());
        assertEquals(30.0, loaded.getBBColocThresholdPercent(), 0.0001);
    }

    @Test
    public void stockPresetsBootstrapWhenDirectoryIsEmpty() throws Exception {
        File root = temp.newFolder("stock");
        SpatialPresetIO io = new SpatialPresetIO(root);

        List<SpatialPreset> presets = io.listAll();

        assertEquals(6, presets.size());
        assertEquals(Arrays.asList(
                "exploratory_all.json",
                "microglia_morphology.json",
                "microglia_plaque_contact.json",
                "population_phenotype.json",
                "density_hotspots.json",
                "ripley_clustering.json"), io.stockResourceFiles());
        assertEquals(Arrays.asList(
                "Exploratory (all features)",
                "Cell-level morphology",
                "Cell morphology + contact",
                "Population phenotype scoring",
                "Density hotspots + clusters",
                "Ripley clustering analysis"), presetNames(presets));
        assertFalse("Exploratory remains broad, but follows the default-off Ripley's K policy.",
                presets.get(0).isDoSpatialStats());
        assertTrue(new File(io.presetDirectory(), "microglia_plaque_contact.json").isFile());
        assertTrue(new File(io.presetDirectory(), "exploratory_all.json").isFile());
    }

    @Test
    public void stockPresetsRefreshExistingBundledFiles() throws Exception {
        File root = temp.newFolder("stock-refresh");
        SpatialPresetIO io = new SpatialPresetIO(root);
        assertTrue(io.presetDirectory().mkdirs());
        File stale = new File(io.presetDirectory(), "density_hotspots.json");
        Files.write(stale.toPath(), ("{"
                + "\"name\":\"Density hotspots + clusters\","
                + "\"doHeatmaps\":false,"
                + "\"doPhenotyping\":false"
                + "}").getBytes(StandardCharsets.UTF_8));

        SpatialPreset loaded = io.load("Density hotspots + clusters");

        assertTrue(loaded.isDoHeatmaps());
        assertTrue(loaded.isDoPhenotyping());
    }

    private static List<String> presetNames(List<SpatialPreset> presets) {
        List<String> names = new ArrayList<String>();
        for (SpatialPreset preset : presets) {
            names.add(preset.getName());
        }
        return names;
    }
}
