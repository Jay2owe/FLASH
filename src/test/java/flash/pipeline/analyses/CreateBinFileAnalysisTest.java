package flash.pipeline.analyses;

import flash.pipeline.bin.BinField;
import flash.pipeline.image.NamedFilterLoader;
import flash.pipeline.zslice.ZSliceMode;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

public class CreateBinFileAnalysisTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void escapeHtmlText_escapesHtmlSensitiveCharacters() {
        String raw = "A&B <tag> \"quote\" 'apostrophe'";

        String escaped = CreateBinFileAnalysis.escapeHtmlText(raw);

        assertEquals("A&amp;B &lt;tag&gt; &quot;quote&quot; &#39;apostrophe&#39;", escaped);
    }

    @Test
    public void escapeHtmlText_returnsEmptyStringForNull() {
        assertEquals("", CreateBinFileAnalysis.escapeHtmlText(null));
    }

    @Test
    public void prepareQcImageOpen_returnsCancelWhenMultipleLifFilesExist() throws Exception {
        File dir = temp.newFolder("ambiguous");
        new File(dir, "alpha.lif").createNewFile();
        new File(dir, "beta.lif").createNewFile();

        CreateBinFileAnalysis.QcOpenPreparation result =
                CreateBinFileAnalysis.prepareQcImageOpen(
                        dir.getAbsolutePath(),
                        Collections.singletonList(Integer.valueOf(0)),
                        false);

        assertEquals(CreateBinFileAnalysis.QcOpenStatus.CANCEL, result.status);
        assertEquals(
                "Cannot run quality check: Multiple .lif files found in directory (expected exactly one): "
                        + "alpha.lif, beta.lif\nDirectory: " + dir.getAbsolutePath(),
                result.message);
    }

    @Test
    public void prepareQcImageOpen_returnsCancelWhenNoLifFileExists() throws Exception {
        File dir = temp.newFolder("missing");

        CreateBinFileAnalysis.QcOpenPreparation result =
                CreateBinFileAnalysis.prepareQcImageOpen(
                        dir.getAbsolutePath(),
                        Collections.singletonList(Integer.valueOf(0)),
                        false);

        assertEquals(CreateBinFileAnalysis.QcOpenStatus.CANCEL, result.status);
        assertEquals(
                "Cannot run quality check: No .lif file found in: " + dir.getAbsolutePath(),
                result.message);
    }

    @Test
    public void prepareQcImageOpen_returnsReadyWhenSingleLifFileExists() throws Exception {
        File dir = temp.newFolder("single");
        File lifFile = new File(dir, "experiment.lif");
        lifFile.createNewFile();

        CreateBinFileAnalysis.QcOpenPreparation result =
                CreateBinFileAnalysis.prepareQcImageOpen(
                        dir.getAbsolutePath(),
                        Collections.singletonList(Integer.valueOf(2)),
                        false);

        assertEquals(CreateBinFileAnalysis.QcOpenStatus.READY, result.status);
        assertEquals(lifFile.getAbsolutePath(), result.lifFile.getAbsolutePath());
        assertEquals(Collections.singletonList(Integer.valueOf(2)), result.selectedSeriesIndexes);
        assertTrue(result.message.isEmpty());
    }

    @Test
    public void prepareQcImageOpen_returnsCancelWhenSelectionDialogWasCanceled() throws Exception {
        File dir = temp.newFolder("cancelled");
        new File(dir, "experiment.lif").createNewFile();

        CreateBinFileAnalysis.QcOpenPreparation result =
                CreateBinFileAnalysis.prepareQcImageOpen(
                        dir.getAbsolutePath(),
                        null,
                        true);

        assertEquals(CreateBinFileAnalysis.QcOpenStatus.CANCEL, result.status);
        assertTrue(result.message.isEmpty());
    }

    @Test
    public void writeChannelFilters_suppressDialogsKeepsCustomSilent() throws Exception {
        File binFolder = temp.newFolder("suppressDialogs");
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();
        analysis.setSuppressDialogs(true);
        CreateBinFileAnalysis.BinUserConfig cfg = oneChannelConfig("Custom");

        invokeWriteChannelFilters(analysis, binFolder, cfg);

        assertEquals("Custom", cfg.filterPresets.get(0));
        assertEquals(NamedFilterLoader.loadFilterContent("Default"),
                new String(Files.readAllBytes(new File(binFolder, "C1_Filters.ijm").toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void writeChannelFilters_selectiveOverrideSuppressDialogsKeepsCustomSilent() throws Exception {
        File binFolder = temp.newFolder("selectiveOverrideSuppressDialogs");
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();
        analysis.setSuppressDialogs(true);
        CreateBinFileAnalysis.BinUserConfig cfg = oneChannelConfig("Custom");

        invokeWriteChannelFilters(analysis, binFolder, cfg);

        assertEquals("Custom", cfg.filterPresets.get(0));
        assertEquals(NamedFilterLoader.loadFilterContent("Default"),
                new String(Files.readAllBytes(new File(binFolder, "C1_Filters.ijm").toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void canShowCustomFilterDialog_ignoresHideImageWindowsFlagWhenSwingIsAvailable() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();
        analysis.setHeadless(true);

        Method method = CreateBinFileAnalysis.class.getDeclaredMethod("canShowCustomFilterDialog");
        method.setAccessible(true);

        assertEquals(Boolean.TRUE, method.invoke(analysis));
    }

    @Test
    public void qcSelectionSettings_marksCustomFilterChannelsForQcImageSelection() throws Exception {
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();
        CreateBinFileAnalysis.BinUserConfig customCfg = oneChannelConfig("Custom");
        CreateBinFileAnalysis.BinUserConfig defaultCfg = oneChannelConfig("Default");

        boolean[][] customSelection = invokeQcSelectionSettings(analysis, null, customCfg);
        boolean[][] defaultSelection = invokeQcSelectionSettings(analysis, null, defaultCfg);

        assertTrue(customSelection[0][0]);
        assertFalse(defaultSelection[0][0]);
    }

    @Test
    public void filterPresetOptions_includesSavedCustomFilterPresetNames() throws Exception {
        File project = temp.newFolder("project-with-saved-filters");
        File binFolder = configurationDir(project);
        assertTrue(binFolder.mkdirs());
        File presetDir = new File(project, "FLASH/Presets/Custom Filter Presets");
        assertTrue(presetDir.mkdirs());
        Files.write(new File(presetDir, "IBA1 cleanup filter.ijm").toPath(),
                "run(\"Median...\", \"radius=2 stack\");\n".getBytes(StandardCharsets.UTF_8));
        File legacyPresetDir = new File(project, ".bin/Custom Filter Presets");
        assertTrue(legacyPresetDir.mkdirs());
        Files.write(new File(legacyPresetDir, "Legacy cleanup filter.ijm").toPath(),
                "run(\"Median...\", \"radius=3 stack\");\n".getBytes(StandardCharsets.UTF_8));
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();

        String[] options = invokeFilterPresetOptions(analysis, binFolder, "Default");

        assertTrue(contains(options, "IBA1 cleanup filter"));
        assertTrue(contains(options, "Legacy cleanup filter"));
        assertTrue(contains(options, "Custom"));
    }

    @Test
    public void saveCustomFilterPreset_writesToFlashPresetsFolder() throws Exception {
        File project = temp.newFolder("project-save-custom-filter");
        File binFolder = configurationDir(project);
        assertTrue(binFolder.mkdirs());
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();

        invokeSaveCustomFilterPreset(analysis, binFolder, "IBA1 cleanup filter",
                "run(\"Median...\", \"radius=2 stack\");\n");

        assertTrue(new File(project,
                "FLASH/Presets/Custom Filter Presets/IBA1 cleanup filter.ijm").isFile());
        assertFalse(new File(binFolder, "Custom Filter Presets/IBA1 cleanup filter.ijm").exists());
    }

    @Test
    public void executeFiltered_visitsOnlyRequestedPagesAndPreservesExistingLines() throws Exception {
        File dir = temp.newFolder("filtered-existing");
        File bin = new File(dir, ".bin");
        assertTrue(bin.mkdirs());
        File channelData = new File(bin, "Channel_Data.txt");
        Files.write(channelData.toPath(), Arrays.asList(
                "IBA1 GFAP",
                "Green Red",
                "100 200",
                "50-Infinity 25-500",
                "10-100 20-200",
                "30 40",
                "classical stardist:0.5:0.4",
                "default puncta_resolve",
                "zslice:per_image"
        ), StandardCharsets.UTF_8);
        RecordingFilteredAnalysis analysis = new RecordingFilteredAnalysis();

        analysis.executeFiltered(dir.getAbsolutePath(),
                EnumSet.of(BinField.CHANNEL_NAMES, BinField.Z_SLICE));

        assertEquals(Arrays.asList("names", "zslice"), analysis.visited);
        List<String> lines = Files.readAllLines(configurationFile(dir, "Channel_Data.txt").toPath(), StandardCharsets.UTF_8);
        assertEquals("NeuN\tDAPI", lines.get(0));
        assertEquals("Green\tRed", lines.get(1));
        assertEquals("100\t200", lines.get(2));
        assertEquals("50-Infinity\t25-500", lines.get(3));
        assertEquals("10-100\t20-200", lines.get(4));
        assertEquals("30\t40", lines.get(5));
        assertEquals("classical\tstardist:0.5:0.4", lines.get(6));
        assertEquals("default\tpuncta_resolve", lines.get(7));
        assertEquals("zslice:full", lines.get(8));
        assertEquals("IBA1 GFAP", Files.readAllLines(channelData.toPath(), StandardCharsets.UTF_8).get(0));
        assertFalse(new File(configurationDir(dir), "C1_Filters.ijm").exists());
    }

    @Test
    public void executeFiltered_newFolderWritesDefaultsForSkippedFields() throws Exception {
        File dir = temp.newFolder("filtered-new");
        RecordingFilteredAnalysis analysis = new RecordingFilteredAnalysis();

        analysis.executeFiltered(dir.getAbsolutePath(), EnumSet.of(BinField.CHANNEL_NAMES));

        File bin = configurationDir(dir);
        File channelData = configurationFile(dir, "Channel_Data.txt");
        assertTrue(channelData.isFile());
        assertEquals(Collections.singletonList("names"), analysis.visited);
        List<String> lines = Files.readAllLines(channelData.toPath(), StandardCharsets.UTF_8);
        assertEquals("NeuN\tDAPI", lines.get(0));
        assertEquals("Grays\tGrays", lines.get(1));
        assertEquals("default\tdefault", lines.get(2));
        assertEquals("100-Infinity\t100-Infinity", lines.get(3));
        assertEquals("None\tNone", lines.get(4));
        assertEquals("default\tdefault", lines.get(5));
        assertEquals("classical\tclassical", lines.get(6));
        assertEquals("default\tdefault", lines.get(7));
        assertEquals("zslice:full", lines.get(8));
        assertFalse(new File(bin, "C1_Filters.ijm").exists());
    }

    private static File configurationDir(File dir) {
        return new File(dir, "FLASH/00 - Configuration");
    }

    private static File configurationFile(File dir, String name) {
        return new File(configurationDir(dir), name);
    }

    @Test
    public void analysisDefaultsDeclareNoBinRequirementsOrRoiBenefit() {
        Analysis analysis = new Analysis() {
            @Override public void execute(String directory) {}
        };

        assertTrue(analysis.requiredBinFields().isEmpty());
        assertFalse(analysis.benefitsFromRois());
    }

    private static CreateBinFileAnalysis.BinUserConfig oneChannelConfig(String filterPreset) {
        List<String> names = new ArrayList<String>();
        names.add("IBA1");
        List<String> colors = new ArrayList<String>();
        colors.add("Green");
        List<String> thresholds = new ArrayList<String>();
        thresholds.add("default");
        List<String> sizes = new ArrayList<String>();
        sizes.add("100-Infinity");
        List<String> minmax = new ArrayList<String>();
        minmax.add("None");
        List<String> filters = new ArrayList<String>();
        filters.add(filterPreset);
        List<String> intensity = new ArrayList<String>();
        intensity.add("default");
        return new CreateBinFileAnalysis.BinUserConfig(names, colors, thresholds, sizes, minmax, filters, intensity);
    }

    private static void invokeWriteChannelFilters(CreateBinFileAnalysis analysis, File binFolder,
                                                  CreateBinFileAnalysis.BinUserConfig cfg) throws Exception {
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                "writeChannelFilters", File.class, CreateBinFileAnalysis.BinUserConfig.class);
        method.setAccessible(true);
        method.invoke(analysis, binFolder, cfg);
    }

    private static boolean[][] invokeQcSelectionSettings(CreateBinFileAnalysis analysis,
                                                         boolean[][] customSettings,
                                                         CreateBinFileAnalysis.BinUserConfig cfg) throws Exception {
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                "qcSelectionSettings", boolean[][].class, CreateBinFileAnalysis.BinUserConfig.class);
        method.setAccessible(true);
        return (boolean[][]) method.invoke(analysis, customSettings, cfg);
    }

    private static String[] invokeFilterPresetOptions(CreateBinFileAnalysis analysis, File binFolder,
                                                      String selectedPreset) throws Exception {
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                "filterPresetOptions", File.class, String.class);
        method.setAccessible(true);
        return (String[]) method.invoke(analysis, binFolder, selectedPreset);
    }

    private static void invokeSaveCustomFilterPreset(CreateBinFileAnalysis analysis, File binFolder,
                                                     String presetName, String macroContent) throws Exception {
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                "saveCustomFilterPreset", File.class, String.class, String.class);
        method.setAccessible(true);
        method.invoke(analysis, binFolder, presetName, macroContent);
    }

    private static boolean contains(String[] values, String expected) {
        for (String value : values) {
            if (expected.equals(value)) return true;
        }
        return false;
    }

    private static final class RecordingFilteredAnalysis extends CreateBinFileAnalysis {
        final List<String> visited = new ArrayList<String>();

        @Override
        protected boolean showFilteredChannelNamesPage(String directory, File binFolder,
                                                       BinUserConfig cfg) {
            visited.add("names");
            cfg.names.clear();
            cfg.names.add("NeuN");
            cfg.names.add("DAPI");
            return true;
        }

        @Override
        protected boolean showFilteredChannelColorsPage(String directory, File binFolder,
                                                        BinUserConfig cfg) {
            visited.add("colors");
            return true;
        }

        @Override
        protected boolean showFilteredFilterPresetsPage(String directory, File binFolder,
                                                        BinUserConfig cfg) {
            visited.add("filters");
            return true;
        }

        @Override
        protected boolean showFilteredSegmentationMethodsPage(String directory, File binFolder,
                                                              BinUserConfig cfg) {
            visited.add("segmentation");
            return true;
        }

        @Override
        protected boolean showFilteredZSlicePage(String directory, File binFolder,
                                                 BinUserConfig cfg) {
            visited.add("zslice");
            cfg.zSliceMode = ZSliceMode.FULL;
            cfg.zSliceSelections.clear();
            return true;
        }

        @Override
        protected boolean showFilteredQcPages(String directory, File binFolder,
                                              BinUserConfig cfg, Set<BinField> fields) {
            visited.add("qc");
            return true;
        }
    }
}
