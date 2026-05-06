package flash.pipeline.analyses.wizard;

import flash.pipeline.ui.wizard.PresetIO;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Reads and writes channel configuration presets from the project root.
 */
public class BinPresetIO extends PresetIO<BinPreset> {

    static final String PRESET_DIR_NAME = "Bin Presets";
    static final String PRESET_CATEGORY_NAME = "Channel Configuration";

    private static final List<String> STOCK_RESOURCE_FILES = Collections.unmodifiableList(
            Arrays.asList(
                    "dapi_iba1_gfap.json",
                    "dapi_neun_abeta.json",
                    "synaptic_puncta.json",
                    "dapi_only.json"
            ));

    public BinPresetIO() {
        this(defaultProjectRoot());
    }

    public BinPresetIO(File projectRoot) {
        super(projectRoot);
    }

    protected String presetDirectoryName() {
        return PRESET_CATEGORY_NAME;
    }

    @Override
    protected List<File> legacyPresetDirectories() {
        return Collections.singletonList(new File(projectRootDirectory(), PRESET_DIR_NAME));
    }

    protected BinPreset parsePreset(String json) throws IOException {
        return BinPreset.fromJson(json);
    }

    protected List<String> stockResourceFiles() {
        return STOCK_RESOURCE_FILES;
    }

    protected String stockResourceDirectory() {
        return "bin_presets";
    }

    private static File defaultProjectRoot() {
        String userDir = System.getProperty("user.dir");
        return userDir == null || userDir.trim().isEmpty() ? new File(".") : new File(userDir);
    }

}
