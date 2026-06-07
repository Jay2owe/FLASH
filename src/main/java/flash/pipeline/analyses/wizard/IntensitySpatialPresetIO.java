package flash.pipeline.analyses.wizard;

import flash.pipeline.ui.wizard.PresetIO;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Reads and writes Intensity-Spatial Analysis presets from the project root.
 */
public class IntensitySpatialPresetIO extends PresetIO<IntensitySpatialPreset> {

    static final String PRESET_CATEGORY_NAME = "Intensity-Spatial Analysis";

    private static final List<String> STOCK_RESOURCE_FILES = Collections.unmodifiableList(
            Arrays.asList(
                    "evenly_distributed_or_pocketed.json",
                    "where_is_signal_concentrated.json",
                    "edge_to_center_gradient.json",
                    "aligned_or_directional.json",
                    "texture_or_complexity.json",
                    "two_channel_spatial_relationship.json",
                    "continues_through_z_stack.json"
            ));

    private static final List<String> STOCK_PRESET_NAMES = Collections.unmodifiableList(
            Arrays.asList(
                    "Is the signal evenly distributed or pocketed?",
                    "Where is the signal concentrated?",
                    "Does signal change from the edge to the center?",
                    "Are signal structures aligned or directional?",
                    "What texture or complexity does the signal have?",
                    "How do two channels relate spatially?",
                    "Do patterns continue through the z-stack?"
            ));

    public IntensitySpatialPresetIO() {
        this(defaultProjectRoot());
    }

    public IntensitySpatialPresetIO(File projectRoot) {
        super(projectRoot);
    }

    protected String presetDirectoryName() {
        return PRESET_CATEGORY_NAME;
    }

    protected IntensitySpatialPreset parsePreset(String json) throws IOException {
        return IntensitySpatialPreset.fromJson(json);
    }

    protected List<String> stockResourceFiles() {
        return STOCK_RESOURCE_FILES;
    }

    protected String stockResourceDirectory() {
        return "intensity_spatial_presets";
    }

    @Override
    public List<IntensitySpatialPreset> listAll() throws IOException {
        List<IntensitySpatialPreset> presets =
                new ArrayList<IntensitySpatialPreset>(super.listAll());
        Collections.sort(presets, new Comparator<IntensitySpatialPreset>() {
            @Override
            public int compare(IntensitySpatialPreset left, IntensitySpatialPreset right) {
                int order = stockPresetOrder(left) - stockPresetOrder(right);
                if (order != 0) {
                    return order;
                }
                return left.getName().compareToIgnoreCase(right.getName());
            }
        });
        return presets;
    }

    @Override
    public void bootstrapStockPresets() throws IOException {
        super.bootstrapStockPresets();
        File dir = presetDirectory();
        if (!dir.isDirectory()) {
            return;
        }
        for (String resourceName : STOCK_RESOURCE_FILES) {
            byte[] content = readStockResource(resourceName);
            if (content != null) {
                Files.write(new File(dir, new File(resourceName).getName()).toPath(), content);
            }
        }
    }

    private byte[] readStockResource(String resourceName) throws IOException {
        InputStream stream = openStockResource(resourceName);
        if (stream == null) {
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            stream.close();
        }
    }

    private static File defaultProjectRoot() {
        String userDir = System.getProperty("user.dir");
        return userDir == null || userDir.trim().isEmpty() ? new File(".") : new File(userDir);
    }

    private static int stockPresetOrder(IntensitySpatialPreset preset) {
        if (preset == null) {
            return STOCK_PRESET_NAMES.size();
        }
        int index = STOCK_PRESET_NAMES.indexOf(preset.getName());
        return index < 0 ? STOCK_PRESET_NAMES.size() : index;
    }
}
