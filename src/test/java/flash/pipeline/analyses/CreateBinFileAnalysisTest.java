package flash.pipeline.analyses;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinField;
import flash.pipeline.image.NamedFilterLoader;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.runtime.DependencyService;
import flash.pipeline.runtime.FeatureDependencyGate;
import flash.pipeline.ui.ToggleSwitch;
import flash.pipeline.ui.config.ChannelThresholdStage;
import flash.pipeline.ui.config.ClassicalSegmentationStage;
import flash.pipeline.ui.config.ConfigQcActions;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.ConfigQcResult;
import flash.pipeline.ui.config.ConfigQcStage;
import flash.pipeline.ui.config.DisplayRangeStage;
import flash.pipeline.ui.config.FilterParameterStage;
import flash.pipeline.ui.config.SegmentationMethodStage;
import flash.pipeline.ui.preview.PreviewPairPanel;
import flash.pipeline.zslice.ZSliceMode;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

public class CreateBinFileAnalysisTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Before
    public void blockDependencyGateDialogs() {
        FeatureDependencyGate.setUiMode(true);
    }

    @After
    public void resetDependencyGateDialogs() {
        FeatureDependencyGate.configure(new DependencyService(), null);
        FeatureDependencyGate.setUiMode(false);
    }

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
    public void zSliceContextImagesUseMetadataPlaceholders() {
        File lifFile = new File("Experiment_Mouse3.lif");
        List<SeriesMeta> metas = Arrays.asList(
                new SeriesMeta(2, "Mouse3_LH_CA1", 12, 1.0, 1.0, 1.0, "pixel"),
                new SeriesMeta(5, "", 8, 1.0, 1.0, 1.0, "pixel"));

        List<ConfigQcContext.ConfigQcImage> images =
                CreateBinFileAnalysis.zSliceContextImages(lifFile, metas);

        assertEquals(2, images.size());
        assertEquals(2, images.get(0).getSeriesIndex());
        assertEquals("Experiment_Mouse3.lif :: Mouse3_LH_CA1", images.get(0).getSeriesName());
        assertEquals(5, images.get(1).getSeriesIndex());
        assertEquals("Experiment_Mouse3.lif :: Series 6", images.get(1).getSeriesName());
        assertEquals(null, images.get(0).getImage());
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
        File presetDir = new File(project, "FLASH/.settings/Presets/Custom Filter Presets");
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
                "FLASH/.settings/Presets/Custom Filter Presets/IBA1 cleanup filter.ijm").isFile());
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

    @Test
    public void executeFiltered_segmentationMethodRoutesThroughObjectQcNotStandalonePage() throws Exception {
        File dir = temp.newFolder("filtered-segmentation-method");
        File bin = new File(dir, ".bin");
        assertTrue(bin.mkdirs());
        Files.write(new File(bin, "Channel_Data.txt").toPath(), Arrays.asList(
                "IBA1",
                "Green",
                "100",
                "50-Infinity",
                "10-100",
                "30",
                "classical",
                "default",
                "zslice:full"
        ), StandardCharsets.UTF_8);
        RecordingFilteredAnalysis analysis = new RecordingFilteredAnalysis();

        analysis.executeFiltered(dir.getAbsolutePath(),
                EnumSet.of(BinField.SEGMENTATION_METHODS));

        assertEquals(Collections.singletonList("qc"), analysis.visited);
        assertEquals(EnumSet.of(BinField.SEGMENTATION_METHODS), analysis.qcFields);
    }

    @Test
    public void segmentationDialogDefaultDemotesUnavailableLegacyAiSegmentation() {
        assertEquals("Classical",
                CreateBinFileAnalysis.segmentationChoiceForDialogDefault(
                        "stardist:0.5:0.4", false, true));
        assertEquals("Classical",
                CreateBinFileAnalysis.segmentationChoiceForDialogDefault(
                        "cellpose:30:cyto3:0.4:0.0:gpu=true", true, false));
        assertEquals("StarDist 3D",
                CreateBinFileAnalysis.segmentationChoiceForDialogDefault(
                        "stardist:0.5:0.4", true, true));
        assertEquals("Cellpose",
                CreateBinFileAnalysis.segmentationChoiceForDialogDefault(
                        "cellpose:30:cyto3:0.4:0.0:gpu=true", true, true));
    }

    @Test
    public void channelIdentityGridUsesIdentityRowsAndNoFilterControls() {
        CreateBinFileAnalysis.BinUserConfig defaults = twoChannelConfig();
        defaults.names.set(0, "DAPI");
        defaults.names.set(1, "GFAP");
        defaults.colors.set(1, "Red");
        defaults.segmentationMethods.set(1, "stardist:0.5:0.4");

        CreateBinFileAnalysis.ChannelIdentityGrid grid =
                CreateBinFileAnalysis.buildChannelIdentityGrid(defaults, true, true, null);

        assertEquals("Channel name", grid.rowLabels[0].getText());
        assertEquals("LUT", grid.rowLabels[1].getText());
        assertEquals(2, grid.rowLabels.length);
        assertEquals(2, grid.nameFields.length);
        assertEquals("DAPI", grid.nameFields[0].getText());
        assertEquals("GFAP", grid.nameFields[1].getText());
        assertEquals("Blue", grid.lutCombos[0].getSelectedItem());
        assertEquals("Red", grid.lutCombos[1].getSelectedItem());
        assertNull(grid.segmentationCombos[0]);
        assertNull(grid.segmentationCombos[1]);
        assertFalse(containsComponentText(grid.panel, "Filter Preset"));
        assertFalse(containsComponentText(grid.panel, "Segmentation"));
        assertEquals(0, countComponentNamesContaining(grid.panel, "filter"));
    }

    @Test
    public void buildConfigFromDialogReadsIdentityGridAndPreservesHiddenFilters() throws Exception {
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();
        CreateBinFileAnalysis.BinUserConfig draft = twoChannelConfig();
        draft.filterPresets.set(0, "Puncta Resolve");
        draft.filterPresets.set(1, "Custom");
        draft.segmentationMethods.set(1, "stardist:0.5:0.4");
        CreateBinFileAnalysis.ChannelIdentityGrid grid =
                CreateBinFileAnalysis.buildChannelIdentityGrid(draft, true, true, null);
        grid.nameFields[0].setText("NeuN");
        grid.nameFields[1].setText("IBA1");
        grid.lutCombos[0].setSelectedItem("Green");
        grid.lutCombos[1].setSelectedItem("Magenta");

        Object bindings = newBinSetupBindings(2);
        copyBindingArray(bindings, "nameFields", grid.nameFields);
        copyBindingArray(bindings, "colorCombos", grid.lutCombos);

        CreateBinFileAnalysis.BinUserConfig result =
                invokeBuildBinUserConfigFromDialog(analysis, 2, draft, bindings);

        assertEquals(Arrays.asList("NeuN", "IBA1"), result.names);
        assertEquals(Arrays.asList("Green", "Magenta"), result.colors);
        assertEquals(Arrays.asList("Puncta Resolve", "Custom"), result.filterPresets);
        assertEquals("classical", result.segmentationMethods.get(0));
        assertEquals("stardist:0.5:0.4", result.segmentationMethods.get(1));
    }

    @Test
    public void buildConfigFromDialogUsesDraftAndAppliedHiddenChannelMetadata() throws Exception {
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();
        CreateBinFileAnalysis.BinUserConfig draft = twoChannelConfig();
        draft.names.set(0, "NeuN");
        draft.names.set(1, "IBA1");
        draft.objectThresholds.set(0, "120");
        draft.objectThresholds.set(1, "220");
        draft.filterPresets.set(1, "Ramified Cells (Microglia/Astrocytes)");

        CreateBinFileAnalysis.BinUserConfig applied = CreateBinFileAnalysis.copyBinUserConfig(draft);
        applied.objectThresholds.clear();
        applied.objectThresholds.addAll(Arrays.asList("25", "45"));
        applied.sizes.clear();
        applied.sizes.addAll(Arrays.asList("50-500", "70-Infinity"));
        applied.minmax.clear();
        applied.minmax.addAll(Arrays.asList("10-90", "20-120"));
        applied.filterPresets.clear();
        applied.filterPresets.addAll(Arrays.asList("Default", "High Signal-Noise Particle Filter"));
        applied.intensityThresholds.clear();
        applied.intensityThresholds.addAll(Arrays.asList("33", "44"));
        applied.markerIds.clear();
        applied.markerIds.addAll(Arrays.asList("neun", "iba1"));
        applied.markerShapes.clear();
        applied.markerShapes.addAll(Arrays.asList("round", "ramified"));
        applied.markerCrowdingSensitive.clear();
        applied.markerCrowdingSensitive.addAll(Arrays.asList(Boolean.FALSE, Boolean.TRUE));
        applied.zSliceMode = ZSliceMode.SAME_COUNT;

        Object bindings = newBinSetupBindings(2);
        setAppliedConfig(bindings, applied);

        CreateBinFileAnalysis.BinUserConfig result =
                invokeBuildBinUserConfigFromDialog(analysis, 2, draft, bindings);

        assertEquals(Arrays.asList("NeuN", "IBA1"), result.names);
        assertEquals(Arrays.asList("25", "45"), result.objectThresholds);
        assertEquals(Arrays.asList("50-500", "70-Infinity"), result.sizes);
        assertEquals(Arrays.asList("10-90", "20-120"), result.minmax);
        assertEquals(Arrays.asList("Default", "High Signal-Noise Particle Filter"), result.filterPresets);
        assertEquals(Arrays.asList("33", "44"), result.intensityThresholds);
        assertEquals(Arrays.asList("neun", "iba1"), result.markerIds);
        assertEquals(Arrays.asList("round", "ramified"), result.markerShapes);
        assertEquals(Arrays.asList(Boolean.FALSE, Boolean.TRUE), result.markerCrowdingSensitive);
        assertEquals(ZSliceMode.SAME_COUNT, result.zSliceMode);
    }

    @Test
    public void settingsModeTickAllGroup_selectsAndClearsMemberToggles() {
        ToggleSwitch selector = new ToggleSwitch(false);
        ToggleSwitch c1 = new ToggleSwitch(false);
        ToggleSwitch c2 = new ToggleSwitch(true);
        CreateBinFileAnalysis.SettingsModeTickAllGroup group =
                new CreateBinFileAnalysis.SettingsModeTickAllGroup(selector);

        group.add(c1);
        group.add(c2);

        assertFalse(selector.isSelected());
        selector.setSelected(true);
        assertTrue(c1.isSelected());
        assertTrue(c2.isSelected());
        assertTrue(selector.isSelected());
        assertTrue(group.allSelected());

        selector.setSelected(false);
        assertFalse(c1.isSelected());
        assertFalse(c2.isSelected());
        assertFalse(selector.isSelected());
        assertFalse(group.allSelected());
    }

    @Test
    public void settingsModeTickAllGroup_globalControlSelectsAndTracksGroupedToggles() {
        ToggleSwitch globalSelector = new ToggleSwitch(false);
        ToggleSwitch displaySelector = new ToggleSwitch(false);
        ToggleSwitch thresholdSelector = new ToggleSwitch(false);
        ToggleSwitch displayC1 = new ToggleSwitch(false);
        ToggleSwitch displayC2 = new ToggleSwitch(false);
        ToggleSwitch thresholdC1 = new ToggleSwitch(false);
        CreateBinFileAnalysis.SettingsModeTickAllGroup globalGroup =
                new CreateBinFileAnalysis.SettingsModeTickAllGroup(globalSelector);
        CreateBinFileAnalysis.SettingsModeTickAllGroup displayGroup =
                new CreateBinFileAnalysis.SettingsModeTickAllGroup(displaySelector);
        CreateBinFileAnalysis.SettingsModeTickAllGroup thresholdGroup =
                new CreateBinFileAnalysis.SettingsModeTickAllGroup(thresholdSelector);

        displayGroup.add(displayC1);
        displayGroup.add(displayC2);
        thresholdGroup.add(thresholdC1);
        globalGroup.add(displayC1);
        globalGroup.add(displayC2);
        globalGroup.add(thresholdC1);

        globalSelector.setSelected(true);
        assertTrue(displayC1.isSelected());
        assertTrue(displayC2.isSelected());
        assertTrue(thresholdC1.isSelected());
        assertTrue(displaySelector.isSelected());
        assertTrue(thresholdSelector.isSelected());
        assertTrue(globalSelector.isSelected());

        displayC1.setSelected(false);
        assertFalse(displaySelector.isSelected());
        assertTrue(thresholdSelector.isSelected());
        assertFalse(globalSelector.isSelected());

        displaySelector.setSelected(true);
        assertTrue(displayC1.isSelected());
        assertTrue(displayC2.isSelected());
        assertTrue(globalSelector.isSelected());
    }

    @Test
    public void settingsDataStatusForFields_reportsNonePartialAndFullChannelData() {
        BinConfig cfg = new BinConfig();
        int[] channels = new int[]{0, 1};

        assertEquals(CreateBinFileAnalysis.SettingsDataStatus.NONE,
                CreateBinFileAnalysis.settingsDataStatusForFields(
                        cfg, channels, BinField.DISPLAY_MIN_MAX));

        cfg.channelMinMax.add("None");
        assertEquals(CreateBinFileAnalysis.SettingsDataStatus.PARTIAL,
                CreateBinFileAnalysis.settingsDataStatusForFields(
                        cfg, channels, BinField.DISPLAY_MIN_MAX));

        cfg.channelMinMax.add("0-4095");
        assertEquals(CreateBinFileAnalysis.SettingsDataStatus.FULL,
                CreateBinFileAnalysis.settingsDataStatusForFields(
                        cfg, channels, BinField.DISPLAY_MIN_MAX));
    }

    @Test
    public void settingsDataStatusForFields_reportsNoneWithoutSavedConfig() {
        assertEquals(CreateBinFileAnalysis.SettingsDataStatus.NONE,
                CreateBinFileAnalysis.settingsDataStatusForFields(
                        null, new int[]{0, 1}, BinField.FILTER_PRESETS));
    }

    @Test
    public void settingsDataStatusForFields_combinesObjectAndIntensityThresholdCompleteness() {
        BinConfig cfg = new BinConfig();
        int[] channels = new int[]{0, 1};
        cfg.channelThresholds.add("default");
        cfg.channelThresholds.add("250");
        cfg.channelIntensityThresholds.add("default");

        assertEquals(CreateBinFileAnalysis.SettingsDataStatus.PARTIAL,
                CreateBinFileAnalysis.settingsDataStatusForFields(
                        cfg, channels, BinField.OBJECT_THRESHOLDS,
                        BinField.INTENSITY_THRESHOLDS));

        cfg.channelIntensityThresholds.add("250");
        assertEquals(CreateBinFileAnalysis.SettingsDataStatus.FULL,
                CreateBinFileAnalysis.settingsDataStatusForFields(
                        cfg, channels, BinField.OBJECT_THRESHOLDS,
                        BinField.INTENSITY_THRESHOLDS));
    }

    @Test
    public void combineSettingsDataStatuses_marksMixedFullAndNoneAsPartial() {
        assertEquals(CreateBinFileAnalysis.SettingsDataStatus.PARTIAL,
                CreateBinFileAnalysis.combineSettingsDataStatuses(
                        CreateBinFileAnalysis.SettingsDataStatus.FULL,
                        CreateBinFileAnalysis.SettingsDataStatus.NONE));
        assertEquals(CreateBinFileAnalysis.SettingsDataStatus.FULL,
                CreateBinFileAnalysis.combineSettingsDataStatuses(
                        CreateBinFileAnalysis.SettingsDataStatus.FULL,
                        CreateBinFileAnalysis.SettingsDataStatus.FULL));
        assertEquals(CreateBinFileAnalysis.SettingsDataStatus.NONE,
                CreateBinFileAnalysis.combineSettingsDataStatuses(
                        CreateBinFileAnalysis.SettingsDataStatus.NONE,
                        CreateBinFileAnalysis.SettingsDataStatus.NONE));
    }

    @Test
    public void readThresholdFromImage_returnsLeftMinThreshold() throws Exception {
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();
        ByteProcessor processor = new ByteProcessor(2, 2, new byte[]{0, 40, 80, (byte) 200}, null);
        processor.setThreshold(17.0, 203.0, ImageProcessor.NO_LUT_UPDATE);
        ImagePlus imp = new ImagePlus("threshold-min", processor);

        Double threshold = invokeReadThresholdFromImage(analysis, imp);

        assertEquals(Double.valueOf(17.0), threshold);
    }

    @Test
    public void prepareChannelThresholdPreview_usesReplacementReturnedByFilterHook() throws Exception {
        ReplacementThresholdPreviewAnalysis analysis = new ReplacementThresholdPreviewAnalysis();
        ByteProcessor rawProcessor = new ByteProcessor(1, 1);
        rawProcessor.set(0, 0, 5);
        ImagePlus raw = new ImagePlus("raw-threshold-preview", rawProcessor);
        analysis.replacement.getProcessor().setThreshold(44.0, 255.0, ImageProcessor.NO_LUT_UPDATE);

        ImagePlus preview = analysis.prepareChannelThresholdPreview(raw, "fake replacement filter");
        Double threshold = invokeReadThresholdFromImage(analysis, preview);

        assertTrue("Returned filter image must become the threshold preview",
                preview == analysis.replacement);
        assertEquals("Raw duplicate should remain separate from replacement preview",
                5, raw.getProcessor().get(0, 0));
        assertEquals("Threshold readback should use replacement preview",
                Double.valueOf(44.0), threshold);
    }

    @Test
    public void interactiveQcRoutesDisplayThresholdAndFilterStagesThroughEmbeddedDialog() throws Exception {
        File binFolder = temp.newFolder("embedded-qc-routing");
        Files.write(new File(binFolder, "C1_Filters.ijm").toPath(),
                "".getBytes(StandardCharsets.UTF_8));
        CreateBinFileAnalysis.BinUserConfig cfg = oneChannelConfig("Custom");
        List<?> images = privateQcSelections(byteImage("embedded route"));
        RecordingEmbeddedDialogAnalysis analysis = new RecordingEmbeddedDialogAnalysis();

        assertEquals("continue", invokePrivateQcStep(
                analysis, "interactiveDisplayRangeQC", images, cfg, binFolder, 0));
        assertEquals(Collections.<Class<?>>singletonList(DisplayRangeStage.class), analysis.stageTypes);

        analysis.stageTypes.clear();
        assertEquals("continue", invokePrivateQcStep(
                analysis, "interactiveChannelThresholdQC", images, cfg, binFolder, 0));
        assertEquals(Collections.<Class<?>>singletonList(ChannelThresholdStage.class), analysis.stageTypes);

        analysis.stageTypes.clear();
        assertEquals("continue", invokePrivateQcStep(
                analysis, "interactiveFilterParameterQC", images, cfg, binFolder, 0));
        assertEquals(Collections.<Class<?>>singletonList(FilterParameterStage.class), analysis.stageTypes);
    }

    @Test
    public void segmentationObjectQcRoutesClassicalThroughSingleMergedStage() throws Exception {
        File binFolder = temp.newFolder("classical-merged-routing");
        Files.write(new File(binFolder, "C1_Filters.ijm").toPath(),
                "".getBytes(StandardCharsets.UTF_8));
        CreateBinFileAnalysis.BinUserConfig cfg = oneChannelConfig("Default");
        List<?> images = privateQcSelections(byteImage("classical route"));
        RecordingEmbeddedDialogAnalysis analysis = new RecordingEmbeddedDialogAnalysis();

        assertEquals("continue", invokeInteractiveSegmentationObjectQc(
                analysis, images, cfg, binFolder, 0, false));

        assertEquals(Arrays.asList("Segmentation Method", "Classical Segmentation"),
                analysis.applicableStageTitles);
    }

    @Test
    public void segmentationObjectQcKeepsAiThresholdOnlyForAiMethodsWhenRequested() throws Exception {
        File binFolder = temp.newFolder("ai-threshold-routing");
        Files.write(new File(binFolder, "C1_Filters.ijm").toPath(),
                "".getBytes(StandardCharsets.UTF_8));
        List<?> images = privateQcSelections(byteImage("ai threshold route"));

        CreateBinFileAnalysis.BinUserConfig starDistCfg = oneChannelConfig("Default");
        starDistCfg.segmentationMethods.set(0, "stardist:0.5:0.4");
        RecordingEmbeddedDialogAnalysis withThreshold = new RecordingEmbeddedDialogAnalysis();
        assertEquals("continue", invokeInteractiveSegmentationObjectQc(
                withThreshold, images, starDistCfg, binFolder, 0, true));
        assertEquals(Arrays.asList(
                "Segmentation Method",
                "StarDist",
                "Channel Threshold"),
                withThreshold.applicableStageTitles);

        CreateBinFileAnalysis.BinUserConfig classicalCfg = oneChannelConfig("Default");
        RecordingEmbeddedDialogAnalysis classical = new RecordingEmbeddedDialogAnalysis();
        assertEquals("continue", invokeInteractiveSegmentationObjectQc(
                classical, images, classicalCfg, binFolder, 0, true));
        assertEquals(Arrays.asList("Segmentation Method", "Classical Segmentation"),
                classical.applicableStageTitles);
    }

    @Test
    public void interactiveQcBackReturnsToPreviousEmbeddedStage() throws Exception {
        File binFolder = temp.newFolder("embedded-qc-back-history");
        CreateBinFileAnalysis.BinUserConfig cfg = oneChannelConfig("Default");
        List<?> images = privateQcSelections(byteImage("embedded back route"));
        boolean[][] customSettings = new boolean[5][1];
        customSettings[1][0] = true; // display range
        customSettings[2][0] = true; // unified threshold
        customSettings[3][0] = true; // classical object threshold mirror
        SequencedEmbeddedDialogAnalysis analysis = new SequencedEmbeddedDialogAnalysis(
                ConfigQcResult.DONE,
                ConfigQcResult.BACK,
                ConfigQcResult.DONE,
                ConfigQcResult.DONE);

        String result = invokeInteractiveQc(analysis, images, cfg, binFolder, customSettings);

        assertEquals("done", result);
        assertEquals(Arrays.<Class<?>>asList(
                DisplayRangeStage.class,
                SegmentationMethodStage.class,
                DisplayRangeStage.class,
                SegmentationMethodStage.class), analysis.firstStageByDialog);
        assertTrue(analysis.stageTitles.contains("Classical Segmentation"));
        assertEquals(Arrays.asList("Display", "Object Segmentation"),
                analysis.stagePaths.get(0));
        assertEquals(0, analysis.stagePathIndices.get(0).intValue());
        assertEquals(Arrays.asList("Display", "Object Segmentation"),
                analysis.stagePaths.get(1));
        assertEquals(1, analysis.stagePathIndices.get(1).intValue());
    }

    @Test
    public void interactiveQcStepPlanRunsEachChannelThroughAllSelectedStagesBeforeNextChannel() throws Exception {
        CreateBinFileAnalysis.BinUserConfig cfg = twoChannelConfig();
        boolean[][] customSettings = new boolean[6][2];
        customSettings[0][0] = true; // filter parameters C1
        customSettings[0][1] = true; // filter parameters C2
        customSettings[1][0] = true; // display range C1
        customSettings[1][1] = true; // display range C2
        customSettings[2][0] = true; // unified threshold C1
        customSettings[3][0] = true; // classical object threshold mirror C1
        customSettings[4][0] = true; // object size C1

        assertEquals(Arrays.asList(
                "DISPLAY_RANGE:0",
                "FILTER_PARAMETERS:0",
                "SEGMENTATION_OBJECT:0",
                "DISPLAY_RANGE:1",
                "FILTER_PARAMETERS:1"),
                invokeInteractiveQcStepPlan(new CreateBinFileAnalysis(), cfg, customSettings));
    }

    @Test
    public void interactiveQcStepPlanRunsSegmentationObjectQcWhenOnlySegmentationMethodSelected() throws Exception {
        CreateBinFileAnalysis.BinUserConfig cfg = twoChannelConfig();
        boolean[][] customSettings = new boolean[6][2];
        customSettings[5][1] = true; // segmentation method C2

        assertEquals(Collections.singletonList("SEGMENTATION_OBJECT:1"),
                invokeInteractiveQcStepPlan(new CreateBinFileAnalysis(), cfg, customSettings));
    }

    @Test
    public void embeddedConfigQcDialogBuildsAndEntersStagesOnSwingThread() {
        CreateBinFileAnalysis analysis = new ExposedEmbeddedDialogAnalysis();
        EdtRecordingStage stage = new EdtRecordingStage();
        ConfigQcContext context = ConfigQcContext.fromImages(
                temp.getRoot(),
                temp.getRoot(),
                oneChannelConfig("Default"),
                Arrays.asList(byteImage("edt route")),
                Arrays.asList("IBA1"),
                0);

        ConfigQcResult result = ((ExposedEmbeddedDialogAnalysis) analysis)
                .showForTest(context, Collections.<ConfigQcStage>singletonList(stage));

        assertEquals(ConfigQcResult.CANCEL, result);
        assertTrue(stage.buildControlsOnSwingThread);
        assertTrue(stage.enteredOnSwingThread);
    }

    @Test
    public void embeddedStageFactoriesWriteBackToBinUserConfig() throws Exception {
        File binFolder = temp.newFolder("embedded-stage-writeback");
        Files.write(new File(binFolder, "C1_Filters.ijm").toPath(),
                "".getBytes(StandardCharsets.UTF_8));
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();
        CreateBinFileAnalysis.BinUserConfig cfg = oneChannelConfig("Custom");
        ConfigQcContext context = ConfigQcContext.fromImages(
                temp.getRoot(),
                binFolder,
                cfg,
                Arrays.asList(byteImage("writeback")),
                cfg.names,
                0);
        NoopConfigQcActions actions = new NoopConfigQcActions();

        DisplayRangeStage displayStage = analysis.createDisplayRangeStage(cfg, 0);
        displayStage.buildControls(context, actions);
        displayStage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));
        invokeStageTestMethod(displayStage, "setRangeForTest",
                new Class<?>[]{double.class, double.class},
                new Object[]{Double.valueOf(12.0), Double.valueOf(90.0)});
        assertTrue(displayStage.lockIn(context));
        displayStage.onLeave(context);
        assertEquals("12-90", cfg.minmax.get(0));

        ChannelThresholdStage thresholdStage = analysis.createChannelThresholdStage(cfg, binFolder, 0);
        thresholdStage.buildControls(context, actions);
        thresholdStage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));
        invokeStageTestMethod(thresholdStage, "setThresholdForTest",
                new Class<?>[]{double.class, double.class},
                new Object[]{Double.valueOf(44.0), Double.valueOf(255.0)});
        assertTrue(thresholdStage.lockIn(context));
        thresholdStage.onLeave(context);
        assertEquals("44", cfg.objectThresholds.get(0));
        assertEquals("44", cfg.intensityThresholds.get(0));

        ClassicalSegmentationStage classicalStage =
                analysis.createClassicalSegmentationStage(cfg, binFolder, 0);
        classicalStage.buildControls(context, actions);
        classicalStage.onEnter(context, new PreviewPairPanel("Original", "Objects"));
        invokeStageTestMethod(classicalStage, "setThresholdForTest",
                new Class<?>[]{double.class, double.class},
                new Object[]{Double.valueOf(55.0), Double.valueOf(255.0)});
        invokeStageTestMethod(classicalStage, "setMinSizeForTest",
                new Class<?>[]{String.class},
                new Object[]{"12"});
        invokeStageTestMethod(classicalStage, "setMaxSizeForTest",
                new Class<?>[]{String.class},
                new Object[]{"34"});
        assertTrue(classicalStage.lockIn(context));
        classicalStage.onLeave(context);
        assertEquals("55", cfg.objectThresholds.get(0));
        assertEquals("55", cfg.intensityThresholds.get(0));
        assertEquals("12-34", cfg.sizes.get(0));
    }

    @Test
    public void filteredSetupSourceUsesConfirmedCacheBeforeRunningMacro() throws Exception {
        File binFolder = temp.newFolder("filtered-stack-cache");
        String macro = "not valid imagej macro syntax";
        Files.write(new File(binFolder, "C1_Filters.ijm").toPath(),
                macro.getBytes(StandardCharsets.UTF_8));
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();
        CreateBinFileAnalysis.BinUserConfig cfg = oneChannelConfig("Custom");
        ConfigQcContext.FilteredStackCache cache = new ConfigQcContext.FilteredStackCache();
        ConfigQcContext context = new ConfigQcContext(
                temp.getRoot(),
                binFolder,
                cfg,
                Arrays.asList(new ConfigQcContext.ConfigQcImage(0, "raw", byteImage("raw"))),
                cfg.names,
                0,
                cache);
        ImagePlus cached = byteImage("cached");
        cached.getProcessor().set(0, 0, 77);
        context.cacheCurrentFilteredStack(macro, cached);

        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                "createFilteredSetupSource",
                ConfigQcContext.class,
                CreateBinFileAnalysis.BinUserConfig.class,
                File.class,
                int.class,
                String.class);
        method.setAccessible(true);
        ImagePlus result = (ImagePlus) method.invoke(
                analysis, context, cfg, binFolder, Integer.valueOf(0), "Threshold input");

        assertEquals(77, result.getProcessor().get(0, 0));
    }

    private static File configurationDir(File dir) {
        return new File(dir, "FLASH/Set Up Configuration/.settings");
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

    private static CreateBinFileAnalysis.BinUserConfig twoChannelConfig() {
        return new CreateBinFileAnalysis.BinUserConfig(
                new ArrayList<String>(Arrays.asList("Channel1", "Channel2")),
                new ArrayList<String>(Arrays.asList("Blue", "Green")),
                new ArrayList<String>(Arrays.asList("default", "default")),
                new ArrayList<String>(Arrays.asList("100-Infinity", "100-Infinity")),
                new ArrayList<String>(Arrays.asList("None", "None")),
                new ArrayList<String>(Arrays.asList("Default", "Default")),
                new ArrayList<String>(Arrays.asList("default", "default")));
    }

    private static Object newBinSetupBindings(int channelCount) throws Exception {
        Class<?> type = Class.forName("flash.pipeline.analyses.CreateBinFileAnalysis$BinSetupBindings");
        java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(Integer.valueOf(channelCount));
    }

    private static void setAppliedConfig(Object bindings,
                                         CreateBinFileAnalysis.BinUserConfig cfg) throws Exception {
        java.lang.reflect.Field field = bindings.getClass().getDeclaredField("appliedConfig");
        field.setAccessible(true);
        field.set(bindings, cfg);
    }

    private static void copyBindingArray(Object bindings, String fieldName, Object[] values) throws Exception {
        java.lang.reflect.Field field = bindings.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        Object[] target = (Object[]) field.get(bindings);
        System.arraycopy(values, 0, target, 0, Math.min(values.length, target.length));
    }

    private static CreateBinFileAnalysis.BinUserConfig invokeBuildBinUserConfigFromDialog(
            CreateBinFileAnalysis analysis,
            int channelCount,
            CreateBinFileAnalysis.BinUserConfig draft,
            Object bindings) throws Exception {
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                "buildBinUserConfigFromDialog",
                int.class,
                BinConfig.class,
                CreateBinFileAnalysis.BinUserConfig.class,
                bindings.getClass());
        method.setAccessible(true);
        return (CreateBinFileAnalysis.BinUserConfig) method.invoke(
                analysis, Integer.valueOf(channelCount), null, draft, bindings);
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

    private static Double invokeReadThresholdFromImage(CreateBinFileAnalysis analysis,
                                                       ImagePlus imp) throws Exception {
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod("readThresholdFromImage", ImagePlus.class);
        method.setAccessible(true);
        return (Double) method.invoke(analysis, imp);
    }

    private static String invokePrivateQcStep(CreateBinFileAnalysis analysis,
                                              String methodName,
                                              List<?> images,
                                              CreateBinFileAnalysis.BinUserConfig cfg,
                                              File binFolder,
                                              int channelIndex) throws Exception {
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                methodName, List.class, CreateBinFileAnalysis.BinUserConfig.class, File.class, int.class);
        method.setAccessible(true);
        return (String) method.invoke(
                analysis, images, cfg, binFolder, Integer.valueOf(channelIndex));
    }

    private static String invokeInteractiveSegmentationObjectQc(CreateBinFileAnalysis analysis,
                                                                List<?> images,
                                                                CreateBinFileAnalysis.BinUserConfig cfg,
                                                                File binFolder,
                                                                int channelIndex,
                                                                boolean includeAiChannelThreshold) throws Exception {
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                "interactiveSegmentationObjectQC",
                List.class,
                CreateBinFileAnalysis.BinUserConfig.class,
                File.class,
                int.class,
                boolean.class);
        method.setAccessible(true);
        return (String) method.invoke(
                analysis,
                images,
                cfg,
                binFolder,
                Integer.valueOf(channelIndex),
                Boolean.valueOf(includeAiChannelThreshold));
    }

    private static String invokeInteractiveQc(CreateBinFileAnalysis analysis,
                                              List<?> images,
                                              CreateBinFileAnalysis.BinUserConfig cfg,
                                              File binFolder,
                                              boolean[][] customSettings) throws Exception {
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                "interactiveQC", List.class, CreateBinFileAnalysis.BinUserConfig.class,
                File.class, boolean[][].class);
        method.setAccessible(true);
        return (String) method.invoke(analysis, images, cfg, binFolder, customSettings);
    }

    private static List<String> invokeInteractiveQcStepPlan(CreateBinFileAnalysis analysis,
                                                            CreateBinFileAnalysis.BinUserConfig cfg,
                                                            boolean[][] customSettings) throws Exception {
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                "buildInteractiveQcSteps", CreateBinFileAnalysis.BinUserConfig.class,
                boolean[][].class);
        method.setAccessible(true);
        List<?> steps = (List<?>) method.invoke(analysis, cfg, customSettings);
        List<String> names = new ArrayList<String>();
        for (Object step : steps) {
            Field stageField = step.getClass().getDeclaredField("stage");
            Field channelField = step.getClass().getDeclaredField("channelIndex");
            stageField.setAccessible(true);
            channelField.setAccessible(true);
            names.add(String.valueOf(stageField.get(step)) + ":"
                    + String.valueOf(channelField.get(step)));
        }
        return names;
    }

    private static void invokeStageTestMethod(Object target,
                                              String methodName,
                                              Class<?>[] parameterTypes,
                                              Object[] args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        method.invoke(target, args);
    }

    private static List<?> privateQcSelections(ImagePlus image) throws Exception {
        Class<?> type = Class.forName(
                "flash.pipeline.analyses.CreateBinFileAnalysis$QcImageSelection");
        Constructor<?> constructor = type.getDeclaredConstructor(int.class, String.class, ImagePlus.class);
        constructor.setAccessible(true);
        return Collections.singletonList(constructor.newInstance(
                Integer.valueOf(0), image == null ? "" : image.getTitle(), image));
    }

    private static ImagePlus byteImage(String title) {
        ByteProcessor processor = new ByteProcessor(4, 1);
        processor.set(0, 0, 0);
        processor.set(1, 0, 25);
        processor.set(2, 0, 75);
        processor.set(3, 0, 100);
        return new ImagePlus(title, processor);
    }

    private static boolean contains(String[] values, String expected) {
        for (String value : values) {
            if (expected.equals(value)) return true;
        }
        return false;
    }

    private static boolean containsComponentText(Component component, String expected) {
        if (component instanceof javax.swing.JLabel) {
            String text = ((javax.swing.JLabel) component).getText();
            if (expected.equals(text)) return true;
        }
        if (component instanceof javax.swing.AbstractButton) {
            String text = ((javax.swing.AbstractButton) component).getText();
            if (expected.equals(text)) return true;
        }
        if (component instanceof Container) {
            Component[] children = ((Container) component).getComponents();
            for (Component child : children) {
                if (containsComponentText(child, expected)) return true;
            }
        }
        return false;
    }

    private static int countComponentNamesContaining(Component component, String needle) {
        int count = 0;
        String name = component == null ? null : component.getName();
        if (name != null && name.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT))) {
            count++;
        }
        if (component instanceof Container) {
            Component[] children = ((Container) component).getComponents();
            for (Component child : children) {
                count += countComponentNamesContaining(child, needle);
            }
        }
        return count;
    }

    private static final class ReplacementThresholdPreviewAnalysis extends CreateBinFileAnalysis {
        final ImagePlus replacement;

        ReplacementThresholdPreviewAnalysis() {
            ByteProcessor replacementProcessor = new ByteProcessor(1, 1);
            replacementProcessor.set(0, 0, 123);
            replacement = new ImagePlus("filtered-threshold-preview", replacementProcessor);
        }

        @Override
        protected ImagePlus runChannelThresholdFilter(ImagePlus rawDuplicate, String filterContent) {
            return replacement;
        }
    }

    private static final class RecordingEmbeddedDialogAnalysis extends CreateBinFileAnalysis {
        final List<Class<?>> stageTypes = new ArrayList<Class<?>>();
        final List<String> applicableStageTitles = new ArrayList<String>();

        @Override
        protected boolean embeddedConfigQcUiAvailable() {
            return true;
        }

        @Override
        protected ConfigQcResult showEmbeddedConfigQcDialog(ConfigQcContext context,
                                                            List<ConfigQcStage> stages) {
            if (stages != null) {
                for (ConfigQcStage stage : stages) {
                    stageTypes.add(stage == null ? null : stage.getClass());
                    if (stage != null && stage.isApplicable(context)) {
                        applicableStageTitles.add(stage.title());
                    }
                }
            }
            return ConfigQcResult.DONE;
        }
    }

    private static final class SequencedEmbeddedDialogAnalysis extends CreateBinFileAnalysis {
        final List<Class<?>> stageTypes = new ArrayList<Class<?>>();
        final List<Class<?>> firstStageByDialog = new ArrayList<Class<?>>();
        final List<String> stageTitles = new ArrayList<String>();
        final List<List<String>> stagePaths = new ArrayList<List<String>>();
        final List<Integer> stagePathIndices = new ArrayList<Integer>();
        private final List<ConfigQcResult> results;
        private int nextResultIndex;

        SequencedEmbeddedDialogAnalysis(ConfigQcResult... results) {
            this.results = Arrays.asList(results);
        }

        @Override
        protected boolean embeddedConfigQcUiAvailable() {
            return true;
        }

        @Override
        protected ConfigQcResult showEmbeddedConfigQcDialog(ConfigQcContext context,
                                                            List<ConfigQcStage> stages) {
            stagePaths.add(new ArrayList<String>(currentEmbeddedStagePath()));
            stagePathIndices.add(Integer.valueOf(currentEmbeddedStagePathIndex()));
            if (stages != null) {
                if (!stages.isEmpty()) {
                    firstStageByDialog.add(stages.get(0) == null ? null : stages.get(0).getClass());
                }
                for (ConfigQcStage stage : stages) {
                    stageTypes.add(stage == null ? null : stage.getClass());
                    stageTitles.add(stage == null ? null : stage.title());
                }
            }
            if (nextResultIndex < results.size()) {
                return results.get(nextResultIndex++);
            }
            return ConfigQcResult.DONE;
        }
    }

    private static final class ExposedEmbeddedDialogAnalysis extends CreateBinFileAnalysis {
        ConfigQcResult showForTest(ConfigQcContext context, List<ConfigQcStage> stages) {
            return super.showEmbeddedConfigQcDialog(context, stages);
        }
    }

    private static final class EdtRecordingStage implements ConfigQcStage {
        boolean buildControlsOnSwingThread;
        boolean enteredOnSwingThread;
        ConfigQcActions actions;

        @Override public String title() {
            return "EDT Recording";
        }

        @Override public void onEnter(ConfigQcContext context, PreviewPairPanel preview) {
            enteredOnSwingThread = javax.swing.SwingUtilities.isEventDispatchThread();
            javax.swing.SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    if (actions != null) {
                        actions.cancel();
                    }
                }
            });
        }

        @Override public javax.swing.JComponent buildControls(ConfigQcContext context,
                                                              ConfigQcActions actions) {
            this.actions = actions;
            buildControlsOnSwingThread = javax.swing.SwingUtilities.isEventDispatchThread();
            return new javax.swing.JPanel();
        }

        @Override public boolean lockIn(ConfigQcContext context) {
            return true;
        }
    }

    private static final class NoopConfigQcActions implements ConfigQcActions {
        @Override public void setStatus(String text) {
        }

        @Override public void markPreviewStale(String text) {
        }

        @Override public void setAdjustedPreview(ImagePlus image, String text) {
        }

        @Override public void nextImage() {
        }

        @Override public void skipCurrentImage() {
        }

        @Override public void restartStage() {
        }

        @Override public void cancel() {
        }
    }

    private static final class RecordingFilteredAnalysis extends CreateBinFileAnalysis {
        final List<String> visited = new ArrayList<String>();
        Set<BinField> qcFields = Collections.emptySet();

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
            qcFields = fields == null ? Collections.<BinField>emptySet() : EnumSet.copyOf(fields);
            return true;
        }
    }
}
