package flash.pipeline.analyses.wizard;

import flash.pipeline.ui.wizard.PresetIO;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Reads and writes 3D Object Analysis presets from the project root.
 */
public class ThreeDObjectPresetIO extends PresetIO<ThreeDObjectPreset> {

    static final String PRESET_DIR_NAME = "3D Object Presets";
    static final String PRESET_CATEGORY_NAME = "3D Object Analysis";

    private static final List<String> STOCK_RESOURCE_FILES = Collections.unmodifiableList(
            Arrays.asList(
                    "count_only.json",
                    "count_coloc_standard.json",
                    "count_coloc_strict.json",
                    "microglia_processes.json",
                    "amyloid_loose.json",
                    "full_workflow.json"
            ));

    public ThreeDObjectPresetIO() {
        this(defaultProjectRoot());
    }

    public ThreeDObjectPresetIO(File projectRoot) {
        super(projectRoot);
    }

    protected String presetDirectoryName() {
        return PRESET_CATEGORY_NAME;
    }

    @Override
    protected List<File> legacyPresetDirectories() {
        return Collections.singletonList(new File(projectRootDirectory(), PRESET_DIR_NAME));
    }

    protected ThreeDObjectPreset parsePreset(String json) throws IOException {
        return ThreeDObjectPreset.fromJson(json);
    }

    protected List<String> stockResourceFiles() {
        return STOCK_RESOURCE_FILES;
    }

    protected String stockResourceDirectory() {
        return "three_d_object_presets";
    }

    private static File defaultProjectRoot() {
        String userDir = System.getProperty("user.dir");
        return userDir == null || userDir.trim().isEmpty() ? new File(".") : new File(userDir);
    }
}
