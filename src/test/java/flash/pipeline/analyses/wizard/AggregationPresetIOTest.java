package flash.pipeline.analyses.wizard;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AggregationPresetIOTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void stockPresetsBootstrapWhenDirectoryIsEmpty() throws Exception {
        File root = temp.newFolder("agg-stock");
        AggregationPresetIO io = new AggregationPresetIO(root);

        List<AggregationPreset> presets = io.listAll();

        assertEquals(4, presets.size());
        assertTrue(new File(io.presetDirectory(),
                "Per-animal standard (raw + per-mm3).json").isFile());
        assertTrue(new File(io.presetDirectory(),
                "Per-animal per-hemisphere.json").isFile());
        assertTrue(new File(io.presetDirectory(),
                "Per-section exploratory (raw only).json").isFile());
        assertTrue(new File(io.presetDirectory(),
                "Per-region subdivision.json").isFile());
    }

    @Test
    public void perAnimalStockPresetParsesWithBothOutput() throws Exception {
        File root = temp.newFolder("agg-standard");
        AggregationPresetIO io = new AggregationPresetIO(root);
        io.bootstrapStockPresets();

        AggregationPreset preset = io.load("Per-animal standard (raw + per-mm3)");
        assertNotNull(preset);
        assertEquals(AggregationConfig.Granularity.ANIMAL, preset.getGranularity());
        assertEquals(AggregationConfig.OutputMode.RAW_AND_PERMM3, preset.getOutputMode());
    }

    @Test
    public void perSectionStockPresetUsesRawOnly() throws Exception {
        File root = temp.newFolder("agg-section");
        AggregationPresetIO io = new AggregationPresetIO(root);
        io.bootstrapStockPresets();

        AggregationPreset preset = io.load("Per-section exploratory (raw only)");
        assertNotNull(preset);
        assertEquals(AggregationConfig.Granularity.SECTION, preset.getGranularity());
        assertEquals(AggregationConfig.OutputMode.RAW_ONLY, preset.getOutputMode());
    }

    @Test
    public void saveLoadDeleteRoundTrip() throws Exception {
        File root = temp.newFolder("agg-roundtrip");
        AggregationPresetIO io = new AggregationPresetIO(root);

        AggregationPreset preset = new AggregationPreset(
                "My Custom",
                "For testing",
                AggregationConfig.Granularity.HEMISPHERE,
                AggregationConfig.OutputMode.PERMM3_ONLY);
        io.save(preset);

        AggregationPreset loaded = io.load("my_custom");
        assertEquals("My Custom", loaded.getName());
        assertEquals(AggregationConfig.Granularity.HEMISPHERE, loaded.getGranularity());
        assertEquals(AggregationConfig.OutputMode.PERMM3_ONLY, loaded.getOutputMode());

        io.delete("My Custom");
        assertFalse(new File(io.presetDirectory(), "my_custom.json").exists());
    }

    @Test
    public void applyPresetToConfigUpdatesAllFields() {
        AggregationPreset preset = new AggregationPreset(
                "Sample",
                null,
                AggregationConfig.Granularity.REGION,
                AggregationConfig.OutputMode.RAW_ONLY);
        AggregationConfig config = new AggregationConfig();
        config.applyPreset(preset);
        assertEquals(AggregationConfig.Granularity.REGION, config.getGranularity());
        assertEquals(AggregationConfig.OutputMode.RAW_ONLY, config.getOutputMode());
        assertEquals("Sample", config.getPresetName());
    }
}
