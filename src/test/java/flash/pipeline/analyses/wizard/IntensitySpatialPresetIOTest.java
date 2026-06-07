package flash.pipeline.analyses.wizard;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class IntensitySpatialPresetIOTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void roundTripSaveAndLoad() throws Exception {
        File root = temp.newFolder("intensity-spatial-preset");
        IntensitySpatialPresetIO io = new IntensitySpatialPresetIO(root);
        IntensitySpatialConfig config = IntensitySpatialConfig.builder()
                .enabled(true)
                .enabledAnalyses(EnumSet.of(
                        IntensitySpatialConfig.AnalysisKey.PATCHINESS,
                        IntensitySpatialConfig.AnalysisKey.GLCM))
                .mipEnabled(true)
                .overlaysEnabled(true)
                .textureClassCount(5)
                .seed(42L)
                .build();

        io.save(new IntensitySpatialPreset("My Spatial Intensity", "test", null, config));

        assertTrue(new File(root,
                "FLASH/.settings/Presets/Intensity-Spatial Analysis/my_spatial_intensity.json").isFile());
        IntensitySpatialPreset loaded = io.load("my_spatial_intensity");

        assertEquals("My Spatial Intensity", loaded.getName());
        assertEquals(IntensitySpatialPreset.CURRENT_LIBRARY_VERSION, loaded.getLibraryVersion());
        assertTrue(loaded.getConfig().isEnabled());
        assertTrue(loaded.getConfig().getEnabledAnalyses()
                .contains(IntensitySpatialConfig.AnalysisKey.PATCHINESS));
        assertTrue(loaded.getConfig().getEnabledAnalyses()
                .contains(IntensitySpatialConfig.AnalysisKey.GLCM));
        assertTrue(loaded.getConfig().isMipEnabled());
        assertTrue(loaded.getConfig().isOverlaysEnabled());
        assertEquals(5, loaded.getConfig().getTextureClassCount());
        assertEquals(42L, loaded.getConfig().getSeed());
    }

    @Test
    public void stockPresetsBootstrapWhenDirectoryIsEmpty() throws Exception {
        File root = temp.newFolder("stock");
        IntensitySpatialPresetIO io = new IntensitySpatialPresetIO(root);

        List<IntensitySpatialPreset> presets = io.listAll();

        assertEquals(7, presets.size());
        assertEquals(Arrays.asList(
                "evenly_distributed_or_pocketed.json",
                "where_is_signal_concentrated.json",
                "edge_to_center_gradient.json",
                "aligned_or_directional.json",
                "texture_or_complexity.json",
                "two_channel_spatial_relationship.json",
                "continues_through_z_stack.json"), io.stockResourceFiles());
        assertEquals(Arrays.asList(
                "Is the signal evenly distributed or pocketed?",
                "Where is the signal concentrated?",
                "Does signal change from the edge to the center?",
                "Are signal structures aligned or directional?",
                "What texture or complexity does the signal have?",
                "How do two channels relate spatially?",
                "Do patterns continue through the z-stack?"), presetNames(presets));
        assertTrue(new File(io.presetDirectory(), "where_is_signal_concentrated.json").isFile());
        assertTrue(new File(io.presetDirectory(), "continues_through_z_stack.json").isFile());
    }

    @Test
    public void stockPresetsRefreshExistingBundledFiles() throws Exception {
        File root = temp.newFolder("stock-refresh");
        IntensitySpatialPresetIO io = new IntensitySpatialPresetIO(root);
        assertTrue(io.presetDirectory().mkdirs());
        File stale = new File(io.presetDirectory(), "where_is_signal_concentrated.json");
        Files.write(stale.toPath(), ("{"
                + "\"name\":\"Where is the signal concentrated?\","
                + "\"config\":{\"enabled\":true,\"analyses\":[\"patchiness\"],\"overlays\":false}"
                + "}").getBytes(StandardCharsets.UTF_8));

        IntensitySpatialPreset loaded = io.load("Where is the signal concentrated?");

        assertTrue(loaded.getConfig().isOverlaysEnabled());
        assertTrue(loaded.getConfig().getEnabledAnalyses()
                .contains(IntensitySpatialConfig.AnalysisKey.HOTSPOTSCAN));
    }

    private static List<String> presetNames(List<IntensitySpatialPreset> presets) {
        List<String> names = new ArrayList<String>();
        for (IntensitySpatialPreset preset : presets) {
            names.add(preset.getName());
        }
        return names;
    }
}
