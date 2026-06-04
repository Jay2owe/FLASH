package flash.pipeline.analyses.wizard;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ThreeDObjectPresetIOTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void stockPresetsBootstrapWhenDirectoryIsEmpty() throws Exception {
        ThreeDObjectPresetIO io = new ThreeDObjectPresetIO(temp.newFolder("stock"));

        List<ThreeDObjectPreset> presets = io.listAll();

        assertEquals(6, presets.size());
        assertEquals(Arrays.asList(
                "Full workflow",
                "Count Only",
                "Count + Coloc Standard",
                "Count + Coloc Strict",
                "Count + Coloc Loose",
                "Count + Process Length"), presetNames(presets));
        assertEquals(Arrays.asList(
                "full_workflow.json",
                "count_only.json",
                "count_coloc_standard.json",
                "count_coloc_strict.json",
                "count_coloc_loose.json",
                "count_process_length.json"), io.stockResourceFiles());
        for (ThreeDObjectPreset preset : presets) {
            assertTrue(preset.getName(), preset.isClassicalCentroidFiltering());
        }
        assertTrue(new File(io.presetDirectory(), "full_workflow.json").isFile());
        assertTrue(new File(io.presetDirectory(), "count_only.json").isFile());
        assertTrue(new File(io.presetDirectory(), "count_coloc_loose.json").isFile());
        assertTrue(new File(io.presetDirectory(), "count_process_length.json").isFile());
        assertFalse(new File(io.presetDirectory(), "amyloid_loose.json").isFile());
        assertFalse(new File(io.presetDirectory(), "microglia_processes.json").isFile());
    }

    @Test
    public void roundTripSaveLoadAndDelete() throws Exception {
        ThreeDObjectPresetIO io = new ThreeDObjectPresetIO(temp.newFolder("roundtrip"));
        ThreeDObjectPreset preset = preset("My Object Preset", 42.0);

        io.save(preset);

        assertTrue(new File(temp.getRoot(),
                "roundtrip/FLASH/.settings/Presets/3D Object Analysis/my_object_preset.json").isFile());
        ThreeDObjectPreset loaded = io.load("my_object_preset");
        assertEquals("My Object Preset", loaded.getName());
        assertEquals(42.0, loaded.getColocThresholdPercent(), 0.0001);
        assertFalse(loaded.isDoIntensityColoc());
        assertEquals("microglia", loaded.getProcessMarkerHints().get(0));

        io.delete("My Object Preset");
    }

    @Test
    public void roundTripPreservesIntensityColocalizationFlag() throws Exception {
        ThreeDObjectPresetIO io = new ThreeDObjectPresetIO(temp.newFolder("intensity-coloc"));
        ThreeDObjectPreset preset = new ThreeDObjectPreset(
                "Intensity Coloc",
                "test",
                "1",
                false,
                false,
                true,
                false,
                false,
                true,
                30.0,
                null,
                null);

        io.save(preset);

        ThreeDObjectPreset loaded = io.load("intensity_coloc");
        assertTrue(loaded.isDoIntensityColoc());
    }

    @Test
    public void legacyStockColocPresetJsonMigratesIntensityColocalization() throws Exception {
        String json = "{"
                + "\"name\":\"Count + Coloc Standard\","
                + "\"libraryVersion\":\"1\","
                + "\"doVolumetric\":true,"
                + "\"doCpc\":true,"
                + "\"doIntensityColoc\":false,"
                + "\"extractProcessLength\":false,"
                + "\"runSpatial\":false,"
                + "\"classicalCentroidFiltering\":true,"
                + "\"colocThresholdPercent\":30"
                + "}";

        ThreeDObjectPreset loaded = ThreeDObjectPreset.fromJson(json);

        assertTrue(loaded.isDoIntensityColoc());
        assertFalse(loaded.isRunSpatial());
    }

    @Test
    public void legacyFullWorkflowPresetJsonMigratesSpatialAnalysis() throws Exception {
        String json = "{"
                + "\"name\":\"Full workflow\","
                + "\"libraryVersion\":\"1\","
                + "\"doVolumetric\":true,"
                + "\"doCpc\":true,"
                + "\"doIntensityColoc\":false,"
                + "\"extractProcessLength\":true,"
                + "\"runSpatial\":false,"
                + "\"classicalCentroidFiltering\":true,"
                + "\"colocThresholdPercent\":30"
                + "}";

        ThreeDObjectPreset loaded = ThreeDObjectPreset.fromJson(json);

        assertTrue(loaded.isDoIntensityColoc());
        assertTrue(loaded.isRunSpatial());
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

    private static ThreeDObjectPreset preset(String name, double threshold) {
        return new ThreeDObjectPreset(name, "test", "1",
                true, true, true, false, false, threshold,
                Arrays.asList("microglia"), Arrays.asList("nuclei"));
    }

    private static List<String> presetNames(List<ThreeDObjectPreset> presets) {
        List<String> names = new ArrayList<String>();
        for (ThreeDObjectPreset preset : presets) {
            names.add(preset.getName());
        }
        return names;
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
