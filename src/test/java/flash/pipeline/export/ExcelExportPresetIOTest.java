package flash.pipeline.export;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ExcelExportPresetIOTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void stockPresetsBootstrapWhenDirectoryIsEmpty() throws Exception {
        File root = temp.newFolder("excel-stock");
        ExcelExportPresetIO io = new ExcelExportPresetIO(root);

        List<ExcelExportPreset> presets = io.listAll();

        assertEquals(7, presets.size());
        assertTrue(new File(io.presetDirectory(),
                "Exploratory (default - all sheets, raw values).json").isFile());
        assertTrue(new File(io.presetDirectory(),
                "Supervisor review (conditions + statistics only).json").isFile());
        assertTrue(new File(io.presetDirectory(), "Figure-ready supplement.json").isFile());
        assertTrue(new File(io.presetDirectory(),
                "Collaborator handoff (raw, no stats).json").isFile());
        assertTrue(new File(io.presetDirectory(),
                "Methods supplement (macros + data summary).json").isFile());
        assertTrue(new File(io.presetDirectory(), "Minimal (metric sheets only).json").isFile());
        assertTrue(new File(io.presetDirectory(),
                "Archive (everything + methods + gradient).json").isFile());
    }

    @Test
    public void figureReadyPresetParsesExpectedFields() throws Exception {
        File root = temp.newFolder("excel-figure");
        ExcelExportPresetIO io = new ExcelExportPresetIO(root);
        io.bootstrapStockPresets();

        ExcelExportPreset preset = io.load("Figure-ready supplement");
        assertNotNull(preset);
        assertTrue(preset.isIncludeExperimentalConditionsSheet());
        assertFalse(preset.isIncludeDataSummarySheet());
        assertTrue(preset.isIncludePerMetricSheets());
        assertTrue(preset.isIncludeStatisticsSheet());
        assertEquals(ExcelExportPreset.MetricSheetDetail.SUMMARY_STATISTICS,
                preset.getMetricSheetDetail());
        assertEquals(ExcelExportPreset.SignificanceHighlight.P_GRADIENT,
                preset.getSignificanceHighlight());
        assertEquals(ExcelExportPreset.HeaderStyle.FIGURE_READY, preset.getHeaderStyle());
        assertTrue(preset.isSignificanceStars());
    }

    @Test
    public void saveLoadDeleteRoundTrip() throws Exception {
        File root = temp.newFolder("excel-roundtrip");
        ExcelExportPresetIO io = new ExcelExportPresetIO(root);

        ExcelExportPreset preset = new ExcelExportPreset(
                "My Custom Preset",
                "For testing",
                true, false, true, false,
                ExcelExportPreset.MetricSheetDetail.BOTH,
                true,
                ExcelExportPreset.SignificanceHighlight.P_GRADIENT,
                ExcelExportPreset.HeaderStyle.FIGURE_READY,
                true,
                true);
        io.save(preset);

        ExcelExportPreset loaded = io.load("my_custom_preset");
        assertEquals("My Custom Preset", loaded.getName());
        assertEquals(ExcelExportPreset.MetricSheetDetail.BOTH, loaded.getMetricSheetDetail());
        assertEquals(ExcelExportPreset.SignificanceHighlight.P_GRADIENT,
                loaded.getSignificanceHighlight());
        assertTrue(loaded.isIncludeMethodsAppendix());
        assertFalse(loaded.isIncludeStatisticsSheet());
        assertTrue(loaded.isSignificanceStars());
        assertTrue(loaded.isIncludeTextureFeatures());

        io.delete("My Custom Preset");
        assertFalse(new File(io.presetDirectory(), "my_custom_preset.json").exists());
    }

    @Test
    public void withFieldOverridesIndividualValues() {
        ExcelExportPreset base = ExcelExportPreset.exploratoryDefault();
        assertTrue(base.isIncludeStatisticsSheet());

        ExcelExportPreset overridden = base.withField("stats_sheet", "false");
        assertFalse(overridden.isIncludeStatisticsSheet());
        assertTrue(overridden.isIncludeExperimentalConditionsSheet());

        ExcelExportPreset methods = overridden.withField("methods_appendix", "true");
        assertTrue(methods.isIncludeMethodsAppendix());

        ExcelExportPreset textureFeatures = methods.withField("texture_features", "true");
        assertTrue(textureFeatures.isIncludeTextureFeatures());
    }
}
