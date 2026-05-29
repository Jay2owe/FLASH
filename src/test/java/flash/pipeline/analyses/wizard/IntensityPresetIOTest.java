package flash.pipeline.analyses.wizard;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
        assertFalse(loaded.getSpatial().isEnabled());
    }

    @Test
    public void roundTripSpatialPresetData() throws Exception {
        File root = temp.newFolder("intensity-spatial-preset");
        IntensityPresetIO io = new IntensityPresetIO(root);
        IntensitySpatialConfig spatial = IntensitySpatialConfig.builder()
                .enabled(true)
                .enabledAnalyses(EnumSet.of(
                        IntensitySpatialConfig.AnalysisKey.PATCHINESS,
                        IntensitySpatialConfig.AnalysisKey.ENTROPY_MI))
                .mipEnabled(true)
                .overlaysEnabled(true)
                .shellWidthUm(12.5)
                .shellCount(8)
                .tileScalesUm(new double[]{25.0, 75.0})
                .granularityScalesUm(new double[]{3.0, 9.0})
                .depthBinWidthUm(6.0)
                .rimDepthUm(18.0)
                .textureClassCount(5)
                .permutations(99)
                .seed(42L)
                .build();
        IntensityPreset preset = new IntensityPreset("Spatial Intensity", "test", null,
                "custom", IntensityWizard.MODE_WHOLE_ROI_MEAN,
                new LinkedHashMap<String, String>(), new LinkedHashMap<String, String>(),
                null, Arrays.asList("LH"), spatial);

        io.save(preset);
        IntensityPreset loaded = io.load("spatial_intensity");

        assertEquals(IntensityPreset.CURRENT_LIBRARY_VERSION, loaded.getLibraryVersion());
        assertTrue(loaded.getSpatial().isEnabled());
        assertTrue(loaded.getSpatial().getEnabledAnalyses()
                .contains(IntensitySpatialConfig.AnalysisKey.PATCHINESS));
        assertTrue(loaded.getSpatial().getEnabledAnalyses()
                .contains(IntensitySpatialConfig.AnalysisKey.ENTROPY_MI));
        assertTrue(loaded.getSpatial().isMipEnabled());
        assertTrue(loaded.getSpatial().isOverlaysEnabled());
        assertEquals(12.5, loaded.getSpatial().getShellWidthUm(), 0.0001);
        assertEquals(8, loaded.getSpatial().getShellCount());
        assertEquals(6.0, loaded.getSpatial().getDepthBinWidthUm(), 0.0001);
        assertEquals(18.0, loaded.getSpatial().getRimDepthUm(), 0.0001);
        assertEquals(5, loaded.getSpatial().getTextureClassCount());
        assertEquals(99, loaded.getSpatial().getPermutations());
        assertEquals(42L, loaded.getSpatial().getSeed());
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

}
