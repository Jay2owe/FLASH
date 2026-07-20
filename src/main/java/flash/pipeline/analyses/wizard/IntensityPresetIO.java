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
 * Reads and writes Intensity Analysis presets from the project root.
 */
public class IntensityPresetIO extends PresetIO<IntensityPreset> {

    static final String PRESET_CATEGORY_NAME = "Fluorescence Intensity";

    private static final List<String> STOCK_RESOURCE_FILES = Collections.unmodifiableList(
            Arrays.asList(
                    "roi_mean.json",
                    "threshold_puncta.json",
                    "neun_restricted.json",
                    "area_fraction.json",
                    "lh_only.json"
            ));

    public IntensityPresetIO() {
        this(defaultProjectRoot());
    }

    public IntensityPresetIO(File projectRoot) {
        super(projectRoot);
    }

    protected String presetDirectoryName() {
        return PRESET_CATEGORY_NAME;
    }

    protected IntensityPreset parsePreset(String json) throws IOException {
        return IntensityPreset.fromJson(json);
    }

    protected List<String> stockResourceFiles() {
        return STOCK_RESOURCE_FILES;
    }

    protected String stockResourceDirectory() {
        return "intensity_presets";
    }

    @Override
    public void bootstrapStockPresets() throws IOException {
        super.bootstrapStockPresets();
        // Re-copy the bundled stock presets every time so built-in updates reach
        // existing projects (matching SpatialPresetIO). User-saved presets, which
        // have different filenames, are untouched.
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
}
