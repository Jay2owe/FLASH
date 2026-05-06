package flash.pipeline.analyses.wizard;

import flash.pipeline.ui.wizard.PresetIO;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Reads and writes Spatial & Morphometric Analysis presets from the project root.
 */
public class SpatialPresetIO extends PresetIO<SpatialPreset> {

    static final String PRESET_DIR_NAME = "Spatial Morphometry Presets";
    static final String PRESET_CATEGORY_NAME = "Spatial Analysis";

    private static final List<String> STOCK_RESOURCE_FILES = Collections.unmodifiableList(
            Arrays.asList(
                    "microglia_morphology.json",
                    "microglia_plaque_contact.json",
                    "population_phenotype.json",
                    "density_hotspots.json",
                    "ripley_clustering.json",
                    "exploratory_all.json"
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

    @Override
    protected List<File> legacyPresetDirectories() {
        return Collections.singletonList(new File(projectRootDirectory(), PRESET_DIR_NAME));
    }

    protected SpatialPreset parsePreset(String json) throws IOException {
        return SpatialPreset.fromJson(json);
    }

    protected List<String> stockResourceFiles() {
        return STOCK_RESOURCE_FILES;
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
}
