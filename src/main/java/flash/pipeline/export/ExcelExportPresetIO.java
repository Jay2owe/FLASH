package flash.pipeline.export;

import flash.pipeline.ui.wizard.PresetIO;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Preset storage for {@link ExcelExportPreset}.
 * <p>
 * Stock presets live under {@code src/main/resources/excel_presets/} and are
 * bootstrapped into {@code <project>/Excel Presets/} on first use.
 */
public class ExcelExportPresetIO extends PresetIO<ExcelExportPreset> {

    public ExcelExportPresetIO() {
        this(new File(System.getProperty("user.dir", ".")));
    }

    public ExcelExportPresetIO(File projectRoot) {
        super(projectRoot);
    }

    @Override
    protected String presetDirectoryName() {
        return "Excel Export";
    }

    @Override
    protected List<File> legacyPresetDirectories() {
        return Collections.singletonList(new File(projectRootDirectory(), "Excel Presets"));
    }

    @Override
    protected ExcelExportPreset parsePreset(String json) throws IOException {
        return ExcelExportPreset.fromJson(json);
    }

    @Override
    protected List<String> stockResourceFiles() {
        return Arrays.asList(
                "Exploratory (default - all sheets, raw values).json",
                "Supervisor review (conditions + statistics only).json",
                "Figure-ready supplement.json",
                "Collaborator handoff (raw, no stats).json",
                "Methods supplement (macros + data summary).json",
                "Minimal (metric sheets only).json",
                "Archive (everything + methods + gradient).json");
    }

    @Override
    protected String stockResourceDirectory() {
        return "excel_presets";
    }
}
