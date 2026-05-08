package flash.pipeline.analyses.wizard;

import flash.pipeline.ui.wizard.PresetIO;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Reads and writes 3D Object Analysis presets from the project root.
 */
public class ThreeDObjectPresetIO extends PresetIO<ThreeDObjectPreset> {

    static final String PRESET_DIR_NAME = "3D Object Presets";
    static final String PRESET_CATEGORY_NAME = "3D Object Analysis";

    private static final List<String> STOCK_RESOURCE_FILES = Collections.unmodifiableList(
            Arrays.asList(
                    "full_workflow.json",
                    "count_only.json",
                    "count_coloc_standard.json",
                    "count_coloc_strict.json",
                    "count_coloc_loose.json",
                    "count_process_length.json"
            ));

    private static final List<String> STOCK_PRESET_NAMES = Collections.unmodifiableList(
            Arrays.asList(
                    "Full workflow",
                    "Count Only",
                    "Count + Coloc Standard",
                    "Count + Coloc Strict",
                    "Count + Coloc Loose",
                    "Count + Process Length"
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

    @Override
    public List<ThreeDObjectPreset> listAll() throws IOException {
        List<ThreeDObjectPreset> presets = new ArrayList<ThreeDObjectPreset>(super.listAll());
        Collections.sort(presets, new Comparator<ThreeDObjectPreset>() {
            @Override
            public int compare(ThreeDObjectPreset left, ThreeDObjectPreset right) {
                int order = stockPresetOrder(left) - stockPresetOrder(right);
                if (order != 0) {
                    return order;
                }
                return left.getName().compareToIgnoreCase(right.getName());
            }
        });
        return presets;
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

    private static int stockPresetOrder(ThreeDObjectPreset preset) {
        if (preset == null) {
            return STOCK_PRESET_NAMES.size();
        }
        int index = STOCK_PRESET_NAMES.indexOf(preset.getName());
        return index < 0 ? STOCK_PRESET_NAMES.size() : index;
    }
}
