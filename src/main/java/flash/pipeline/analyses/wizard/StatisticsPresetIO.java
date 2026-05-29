package flash.pipeline.analyses.wizard;

import flash.pipeline.ui.wizard.PresetIO;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Reads and writes Statistics Wizard presets from the project root.
 * <p>
 * Stock presets ship in {@code src/main/resources/stats_presets/} and are
 * bootstrapped into the project's FLASH preset directory on
 * first use.
 */
public class StatisticsPresetIO extends PresetIO<StatisticsPreset> {

    static final String PRESET_CATEGORY_NAME = "Statistics";

    private static final List<String> STOCK_RESOURCE_FILES = Collections.unmodifiableList(
            Arrays.asList(
                    "2_group_automatic.json",
                    "2_group_paired_lh_vs_rh.json",
                    "small_n_cautious.json",
                    "large_n_parametric.json",
                    "multi_group_tukey.json",
                    "multi_group_dunns.json",
                    "raw_p_values.json",
                    "focused_single_metric.json"));

    public StatisticsPresetIO() {
        this(defaultProjectRoot());
    }

    public StatisticsPresetIO(File projectRoot) {
        super(projectRoot);
    }

    @Override
    protected String presetDirectoryName() {
        return PRESET_CATEGORY_NAME;
    }

    @Override
    protected StatisticsPreset parsePreset(String json) throws IOException {
        return StatisticsPreset.fromJson(json);
    }

    @Override
    protected List<String> stockResourceFiles() {
        return STOCK_RESOURCE_FILES;
    }

    @Override
    protected String stockResourceDirectory() {
        return "stats_presets";
    }

    private static File defaultProjectRoot() {
        String userDir = System.getProperty("user.dir");
        return userDir == null || userDir.trim().isEmpty() ? new File(".") : new File(userDir);
    }
}
