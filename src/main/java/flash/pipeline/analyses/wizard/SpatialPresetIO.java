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
 * Reads and writes Spatial & Morphometric Analysis presets from the project root.
 */
public class SpatialPresetIO extends PresetIO<SpatialPreset> {

    static final String PRESET_CATEGORY_NAME = "Spatial Analysis";

    private static final List<String> STOCK_RESOURCE_FILES = Collections.unmodifiableList(
            Arrays.asList(
                    "exploratory_all.json",
                    "microglia_morphology.json",
                    "microglia_plaque_contact.json",
                    "population_phenotype.json",
                    "density_hotspots.json",
                    "ripley_clustering.json"
            ));

    private static final List<String> STOCK_PRESET_NAMES = Collections.unmodifiableList(
            Arrays.asList(
                    "Exploratory (all features)",
                    "Cell-level morphology",
                    "Cell morphology + contact",
                    "Population phenotype scoring",
                    "Density hotspots + clusters",
                    "Ripley clustering analysis"
            ));

    public SpatialPresetIO() {
        this(defaultProjectRoot());
    }

    public SpatialPresetIO(File projectRoot) {
        super(projectRoot);
    }

    protected String presetDirectoryName() {
        return PRESET_CATEGORY_NAME;
    }

    protected SpatialPreset parsePreset(String json) throws IOException {
        return SpatialPreset.fromJson(json);
    }

    protected List<String> stockResourceFiles() {
        return STOCK_RESOURCE_FILES;
    }

    @Override
    public List<SpatialPreset> listAll() throws IOException {
        List<SpatialPreset> presets = new ArrayList<SpatialPreset>(super.listAll());
        Collections.sort(presets, new Comparator<SpatialPreset>() {
            @Override
            public int compare(SpatialPreset left, SpatialPreset right) {
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

    protected String stockResourceDirectory() {
        return "spatial_presets";
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

    private static int stockPresetOrder(SpatialPreset preset) {
        if (preset == null) {
            return STOCK_PRESET_NAMES.size();
        }
        int index = STOCK_PRESET_NAMES.indexOf(preset.getName());
        return index < 0 ? STOCK_PRESET_NAMES.size() : index;
    }
}
