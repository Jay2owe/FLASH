package flash.pipeline.analyses.wizard;

import flash.pipeline.ui.wizard.PresetIO;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Preset storage for {@link AggregationPreset}. Stock presets live in
 * {@code src/main/resources/aggregation_presets/} and are bootstrapped into
 * the project directory on first use.
 */
public class AggregationPresetIO extends PresetIO<AggregationPreset> {

    public AggregationPresetIO() {
        this(new File(System.getProperty("user.dir", ".")));
    }

    public AggregationPresetIO(File projectRoot) {
        super(projectRoot);
    }

    @Override
    protected String presetDirectoryName() {
        return "Result Aggregation";
    }

    @Override
    protected AggregationPreset parsePreset(String json) throws IOException {
        return AggregationPreset.fromJson(json);
    }

    @Override
    protected List<String> stockResourceFiles() {
        return Arrays.asList(
                "Per-animal standard (raw + per-mm3).json",
                "Per-animal per-hemisphere.json",
                "Per-section exploratory (raw only).json",
                "Per-region subdivision.json");
    }

    @Override
    protected String stockResourceDirectory() {
        return "aggregation_presets";
    }
}
