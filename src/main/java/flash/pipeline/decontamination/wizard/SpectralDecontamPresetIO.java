package flash.pipeline.decontamination.wizard;

import flash.pipeline.ui.wizard.PresetIO;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Reads and writes Spectral Decontamination wizard presets.
 */
public class SpectralDecontamPresetIO extends PresetIO<SpectralDecontamPreset> {

    static final String PRESET_DIR_NAME = "Spectral Decontamination Presets";
    static final String PRESET_CATEGORY_NAME = "Spectral Decontamination";

    private static final List<String> STOCK_RESOURCE_FILES = Collections.unmodifiableList(
            Arrays.asList(
                    "bleedthrough_standard.json",
                    "broad_autofluorescence.json",
                    "patchy_autofluorescence.json",
                    "control_calibrated_roc.json",
                    "combined_standard.json",
                    "combined_aggressive.json",
                    "score_existing_objects.json"
            ));

    public SpectralDecontamPresetIO() {
        this(defaultProjectRoot());
    }

    public SpectralDecontamPresetIO(File projectRoot) {
        super(projectRoot);
    }

    protected String presetDirectoryName() {
        return PRESET_CATEGORY_NAME;
    }

    @Override
    protected List<File> legacyPresetDirectories() {
        return Collections.singletonList(new File(projectRootDirectory(), PRESET_DIR_NAME));
    }

    protected SpectralDecontamPreset parsePreset(String json) throws IOException {
        return SpectralDecontamPreset.fromJson(json);
    }

    protected List<String> stockResourceFiles() {
        return STOCK_RESOURCE_FILES;
    }

    protected String stockResourceDirectory() {
        return "spectral_presets";
    }

    private static File defaultProjectRoot() {
        String userDir = System.getProperty("user.dir");
        return userDir == null || userDir.trim().isEmpty() ? new File(".") : new File(userDir);
    }
}
