package flash.pipeline.analyses.wizard;

import flash.pipeline.ui.wizard.PresetIO;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Reads and writes Intensity Analysis presets from the project root.
 */
public class IntensityPresetIO extends PresetIO<IntensityPreset> {

    static final String PRESET_DIR_NAME = "Intensity Presets";
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

    @Override
    protected List<File> legacyPresetDirectories() {
        return Collections.singletonList(new File(projectRootDirectory(), PRESET_DIR_NAME));
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

    private static File defaultProjectRoot() {
        String userDir = System.getProperty("user.dir");
        return userDir == null || userDir.trim().isEmpty() ? new File(".") : new File(userDir);
    }
}
