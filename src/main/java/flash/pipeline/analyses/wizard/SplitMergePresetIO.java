package flash.pipeline.analyses.wizard;

import flash.pipeline.ui.wizard.PresetIO;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Preset storage for Split & Merge Image Channels.
 */
public class SplitMergePresetIO extends PresetIO<SplitMergePreset> {

    public SplitMergePresetIO() {
        this(new File(System.getProperty("user.dir", ".")));
    }

    public SplitMergePresetIO(File projectRoot) {
        super(projectRoot);
    }

    @Override
    protected String presetDirectoryName() {
        return "Split and Merge";
    }

    @Override
    protected List<File> legacyPresetDirectories() {
        return Collections.singletonList(new File(projectRootDirectory(), "Split Merge Presets"));
    }

    @Override
    protected SplitMergePreset parsePreset(String json) throws IOException {
        return SplitMergePreset.fromJson(json);
    }

    @Override
    protected List<String> stockResourceFiles() {
        return Arrays.asList(
                "Preview QC (auto-contrast).json",
                "Figure-ready composite (OME-TIFF).json",
                "Quantitative raw (no stretch).json",
                "Subtract autofluorescence + figure-ready.json");
    }

    @Override
    protected String stockResourceDirectory() {
        return "split_merge_presets";
    }
}
