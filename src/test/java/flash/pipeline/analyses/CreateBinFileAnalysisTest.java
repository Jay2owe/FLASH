package flash.pipeline.analyses;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinField;
import flash.pipeline.bin.BinMacroIndex;
import flash.pipeline.bin.ChannelConfig;
import flash.pipeline.bin.ChannelConfigIO;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.image.NamedFilterLoader;
import flash.pipeline.io.DeferredImageSupplier;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.naming.ImageNameParser;
import flash.pipeline.naming.NameParts;
import flash.pipeline.project.ProjectFile;
import flash.pipeline.project.ProjectFileIO;
import flash.pipeline.qc.QcMinMaxPerConditionSelector;
import flash.pipeline.qc.QcSelectionCandidate;
import flash.pipeline.runtime.DependencyService;
import flash.pipeline.runtime.FeatureDependencyGate;
import flash.pipeline.ui.CancelConfirmationDialog;
import flash.pipeline.ui.CustomFilterEntryDialog;
import flash.pipeline.ui.PipelineDialog;
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
import flash.pipeline.zslice.ZSliceRange;
import flash.pipeline.zslice.ZSliceSelection;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.JComboBox;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
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
    public void execute_headlessWithoutCliBinConfigDoesNotOpenInteractiveSetup() throws Exception {
        File dir = temp.newFolder("headless-no-bin-cli");
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();
        analysis.setHeadless(true);

        analysis.execute(dir.getAbsolutePath());

        assertFalse(new File(dir, "FLASH/Config/.settings/channel_config.json").exists());
    }

    @Test(expected = IllegalStateException.class)
    public void execute_headlessCliWithoutBinConfigFailsInsteadOfSilentSuccess() throws Exception {
        File dir = temp.newFolder("headless-cli-no-bin");
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();
        analysis.setHeadless(true);
        analysis.setCliConfig(new CLIConfig());

        analysis.execute(dir.getAbsolutePath());
    }

    @Test
    public void setUpConfigurationDeclaresHeadedModeForMainGui() {
        assertTrue(new CreateBinFileAnalysis().requiresHeadedMode());
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
    public void cancelSaveAndExitLeavesChannelConfig() throws Exception {
        File binFolder = temp.newFolder("cancel-save-draft");
        CreateBinFileAnalysis.BinUserConfig cfg = oneChannelConfig("Default");
        boolean[][] customSettings = new boolean[][]{{true}, {false}};
        ChannelConfig seeded = ChannelConfigIO.fromBinUserConfig(cfg);
        seeded.extras.put("lastStepIndex", Integer.valueOf(3));
        seeded.extras.put("lastStepLabel", "Settings Mode");
        ChannelConfigIO.write(FlashProjectLayout.settingsDir(binFolder), seeded);
        CancelChoiceAnalysis analysis = new CancelChoiceAnalysis(
                CancelConfirmationDialog.Choice.SAVE_AND_EXIT);

        assertTrue(invokeHandleCancelRequest(analysis, binFolder, cfg, customSettings, 3, "Settings Mode"));

        ChannelConfig draft = ChannelConfigIO.read(FlashProjectLayout.settingsDir(binFolder));
        assertEquals(3, ((Number) draft.extras.get("lastStepIndex")).intValue());
        assertEquals("Settings Mode", draft.extras.get("lastStepLabel"));
        assertEquals(cfg.names.get(0), draft.channels.get(0).name);
    }

    @Test
    public void cancelSaveAndExitSuppressesFollowUpCancelledMessage() throws Exception {
        File binFolder = temp.newFolder("cancel-save-message");
        CreateBinFileAnalysis.BinUserConfig cfg = oneChannelConfig("Default");
        CancelChoiceAnalysis analysis = new CancelChoiceAnalysis(
                CancelConfirmationDialog.Choice.SAVE_AND_EXIT);

        assertTrue(invokeHandleCancelRequest(analysis, binFolder, cfg, null, 3, "Settings Mode"));
        assertTrue(invokeShouldExitAfterWizardCancel(analysis, binFolder, cfg, null, 3, "Settings Mode"));

        assertFalse(invokeShouldShowWizardCancelMessage(analysis));
    }

    @Test
    public void cancelDiscardAndExitDeletesChannelConfig() throws Exception {
        File binFolder = temp.newFolder("cancel-discard-draft");
        ChannelConfigIO.write(FlashProjectLayout.settingsDir(binFolder),
                ChannelConfigIO.fromBinUserConfig(oneChannelConfig("Default")));
        CancelChoiceAnalysis analysis = new CancelChoiceAnalysis(
                CancelConfirmationDialog.Choice.DISCARD_AND_EXIT);

        assertTrue(invokeHandleCancelRequest(analysis, binFolder,
                oneChannelConfig("Default"), null, 2, "Analysis Scope"));

        assertFalse(ChannelConfigIO.exists(FlashProjectLayout.settingsDir(binFolder)));
    }

    @Test
    public void resumeFromChannelConfigRestoresCfg() throws Exception {
        File binFolder = temp.newFolder("resume-draft");
        CreateBinFileAnalysis.BinUserConfig cfg = oneChannelConfig("Custom");
        cfg.names.set(0, "GFAP");
        boolean[][] customSettings = new boolean[][]{{false}, {true}};
        ChannelConfig channelConfig = ChannelConfigIO.fromBinUserConfig(cfg);
        channelConfig.channels.get(0).status.put(ChannelConfig.P_THRESHOLD, ChannelConfig.PropertyStatus.PENDING);
        channelConfig.extras.put("lastStepIndex", Integer.valueOf(5));
        channelConfig.extras.put("lastStepLabel", "Quality Check");
        List<Object> rows = new ArrayList<Object>();
        rows.add(Arrays.<Object>asList(Boolean.FALSE));
        rows.add(Arrays.<Object>asList(Boolean.TRUE));
        channelConfig.extras.put("customSettings", rows);
        ChannelConfigIO.write(FlashProjectLayout.settingsDir(binFolder), channelConfig);

        CreateBinFileAnalysis.WizardResumeState draft =
                new CreateBinFileAnalysis().readWizardResumeState(binFolder);

        assertEquals(5, draft.stepIndex);
        assertEquals("GFAP", draft.cfg.names.get(0));
        assertEquals("Custom", draft.cfg.filterPresets.get(0));
        assertArrayEquals(customSettings[1], draft.customSettings[1]);
    }

    @Test
    public void prepareQcImageOpen_usesDispatcherContainerChoiceWhenMultipleContainersExist() throws Exception {
        File dir = temp.newFolder("ambiguous");
        new File(dir, "alpha.lif").createNewFile();
        File beta = new File(dir, "beta.lif");
        beta.createNewFile();

        setContainerChoiceOverrideForTests("beta.lif");
        try {
            CreateBinFileAnalysis.QcOpenPreparation result =
                    CreateBinFileAnalysis.prepareQcImageOpen(
                            dir.getAbsolutePath(),
                            Collections.singletonList(Integer.valueOf(0)),
                            false);

            assertEquals(CreateBinFileAnalysis.QcOpenStatus.READY, result.status);
            assertEquals(beta.getAbsolutePath(), result.sourceFile.getAbsolutePath());
        } finally {
            setContainerChoiceOverrideForTests(null);
            clearContainerChoiceCacheForTests();
        }
    }

    @Test
    public void prepareQcImageOpen_returnsCancelWhenNoCompatibleSourceExists() throws Exception {
        File dir = temp.newFolder("missing");

        CreateBinFileAnalysis.QcOpenPreparation result =
                CreateBinFileAnalysis.prepareQcImageOpen(
                        dir.getAbsolutePath(),
                        Collections.singletonList(Integer.valueOf(0)),
                        false);

        assertEquals(CreateBinFileAnalysis.QcOpenStatus.CANCEL, result.status);
        assertTrue(result.message.contains("No compatible input found"));
        assertTrue(result.message.contains(dir.getAbsolutePath()));
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
    public void prepareQcImageOpen_preservesSelectedSeriesOrder() throws Exception {
        File dir = temp.newFolder("ordered");
        File lifFile = new File(dir, "experiment.lif");
        lifFile.createNewFile();

        CreateBinFileAnalysis.QcOpenPreparation result =
                CreateBinFileAnalysis.prepareQcImageOpen(
                        dir.getAbsolutePath(),
                        Arrays.asList(Integer.valueOf(5), Integer.valueOf(1), Integer.valueOf(5)),
                        false);

        assertEquals(CreateBinFileAnalysis.QcOpenStatus.READY, result.status);
        assertEquals(lifFile.getAbsolutePath(), result.lifFile.getAbsolutePath());
        assertEquals(Arrays.asList(Integer.valueOf(5), Integer.valueOf(1)), result.selectedSeriesIndexes);
    }

    @Test
    public void prepareQcImageOpen_usesProjectJsonContainerOutsideOutputRoot() throws Exception {
        File outputRoot = temp.newFolder("project-output");
        File stainingDir = new File(outputRoot, "IgG.Iba1.Cas3");
        assertTrue(stainingDir.mkdirs());
        File lifFile = new File(stainingDir, "Cas3.All.Time.Points.lif");
        assertTrue(lifFile.createNewFile());

        ProjectFile project = new ProjectFile();
        project.outputRoot = outputRoot.getAbsolutePath();
        ProjectFile.Item item = new ProjectFile.Item();
        item.path = lifFile.getAbsolutePath();
        item.include = true;
        project.items.add(item);
        ProjectFileIO.write(
                FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath()).configurationWriteDir(),
                project);

        CreateBinFileAnalysis.QcOpenPreparation result =
                CreateBinFileAnalysis.prepareQcImageOpen(
                        outputRoot.getAbsolutePath(),
                        Collections.singletonList(Integer.valueOf(0)),
                        false);

        assertEquals(CreateBinFileAnalysis.QcOpenStatus.READY, result.status);
        assertEquals(lifFile.getAbsolutePath(), result.lifFile.getAbsolutePath());
    }

    @Test
    public void prepareQcImageOpen_projectMissingSourceDoesNotFallBackToRootLif() throws Exception {
        File outputRoot = temp.newFolder("project-missing-source-output");
        assertTrue(new File(outputRoot, "stale-root.lif").createNewFile());

        ProjectFile project = new ProjectFile();
        ProjectFile.Item item = new ProjectFile.Item();
        item.path = new File(temp.getRoot(), "missing-source.lif").getAbsolutePath();
        item.include = true;
        project.items.add(item);
        ProjectFileIO.write(
                FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath()).configurationWriteDir(),
                project);

        CreateBinFileAnalysis.QcOpenPreparation result =
                CreateBinFileAnalysis.prepareQcImageOpen(
                        outputRoot.getAbsolutePath(),
                        Collections.singletonList(Integer.valueOf(0)),
                        false);

        assertEquals(CreateBinFileAnalysis.QcOpenStatus.CANCEL, result.status);
        assertTrue(result.message.contains("missing included source file"));
        assertTrue(result.message.contains("missing-source.lif"));
    }

    @Test
    public void prepareQcImageOpen_tiffProjectUsesProjectTiffInsteadOfRootLif() throws Exception {
        File outputRoot = temp.newFolder("project-tiff-output");
        assertTrue(new File(outputRoot, "stale-root.lif").createNewFile());
        File sourceRoot = temp.newFolder("project-tiff-sources");
        File tiff = new File(sourceRoot, "section.tif");
        assertTrue(tiff.createNewFile());

        ProjectFile project = new ProjectFile();
        ProjectFile.Item item = new ProjectFile.Item();
        item.path = tiff.getAbsolutePath();
        item.include = true;
        project.items.add(item);
        ProjectFileIO.write(
                FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath()).configurationWriteDir(),
                project);

        CreateBinFileAnalysis.QcOpenPreparation result =
                CreateBinFileAnalysis.prepareQcImageOpen(
                        outputRoot.getAbsolutePath(),
                        Collections.singletonList(Integer.valueOf(0)),
                        false);

        assertEquals(CreateBinFileAnalysis.QcOpenStatus.READY, result.status);
        assertEquals(tiff.getAbsolutePath(), result.sourceFile.getAbsolutePath());
        assertEquals(Collections.singletonList(Integer.valueOf(0)), result.selectedSeriesIndexes);
        assertTrue(result.message.isEmpty());
    }

    @Test
    public void prepareQcImageOpen_returnsReadyForLooseTiffs() throws Exception {
        File dir = temp.newFolder("loose-tiffs");
        File tiff = new File(dir, "MouseA_LH_SCN.tif");
        assertTrue(tiff.createNewFile());

        CreateBinFileAnalysis.QcOpenPreparation result =
                CreateBinFileAnalysis.prepareQcImageOpen(
                        dir.getAbsolutePath(),
                        Collections.singletonList(Integer.valueOf(0)),
                        false);

        assertEquals(CreateBinFileAnalysis.QcOpenStatus.READY, result.status);
        assertEquals(tiff.getAbsolutePath(), result.sourceFile.getAbsolutePath());
        assertEquals("loose-tiffs", result.sourceLabel);
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
    public void zSliceContextImagesUseLooseTiffLabels() {
        List<SeriesMeta> metas = Arrays.asList(
                new SeriesMeta(0, "loose-tiffs - Mouse3_LH_CA1", 12, 1.0, 1.0, 1.0, "pixel"),
                new SeriesMeta(1, "loose-tiffs - Mouse4_RH_SCN", 8, 1.0, 1.0, 1.0, "pixel"));

        List<ConfigQcContext.ConfigQcImage> images =
                CreateBinFileAnalysis.zSliceContextImages("loose-tiffs", metas);

        assertEquals(2, images.size());
        assertEquals("loose-tiffs :: Mouse3_LH_CA1", images.get(0).getSeriesName());
        assertEquals("loose-tiffs :: Mouse4_RH_SCN", images.get(1).getSeriesName());
    }

    @Test
    public void metadataOriginalNamePrefixesBareSeriesForConventionParsing() throws Exception {
        File lifFile = new File("Cas3.All.Time.Points.lif");
        SeriesMeta meta = new SeriesMeta(
                0, "hAPP1Week2_LH_SCN", 12, 1.0, 1.0, 1.0, "pixel");

        String originalName = invokeMetadataOriginalName(lifFile, meta);
        NameParts parsed = ImageNameParser.parse(originalName);

        assertEquals("Cas3.All.Time.Points.lif - hAPP1Week2_LH_SCN", originalName);
        assertTrue(parsed.strictMatch);
        assertEquals("hAPP1Week2", parsed.animal);
        assertEquals("LH", parsed.hemisphere);
        assertEquals("SCN", parsed.region);
    }

    @Test
    public void finalizeZSliceSelectionsAutoAcceptsIdenticalAbsoluteRanges() throws Exception {
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();
        CreateBinFileAnalysis.BinUserConfig cfg = oneChannelConfig("Default");
        cfg.zSliceMode = ZSliceMode.PER_IMAGE;
        cfg.zSliceSelections.put(Integer.valueOf(0),
                new ZSliceSelection(0, "Series 1", 30, new ZSliceRange(1, 19)));
        cfg.zSliceSelections.put(Integer.valueOf(1),
                new ZSliceSelection(1, "Series 2", 19, new ZSliceRange(1, 19)));
        cfg.zSliceSelections.put(Integer.valueOf(2),
                new ZSliceSelection(2, "Series 3", 24, new ZSliceRange(1, 19)));

        assertEquals("done", invokeFinalizeZSliceSelections(analysis, cfg));

        assertEquals(ZSliceMode.SAME_ABSOLUTE, cfg.zSliceMode);
        assertEquals(3, cfg.zSliceSelections.size());
        assertEquals("1-19", cfg.zSliceSelections.get(Integer.valueOf(0)).range.toToken());
    }

    @Test
    public void minMaxSelectionModeRecognizesOverallAndPerCondition() throws Exception {
        assertTrue(invokeIsMinMaxSelectionMode("Min and max overall"));
        assertTrue(invokeIsMinMaxSelectionMode("Min and max per condition"));
        assertFalse(invokeIsMinMaxSelectionMode("Randomly select images"));
        assertFalse(invokeIsMinMaxSelectionMode("Manually select images"));
    }

    @Test
    public void minMaxOverallDoesNotRequireConditionMetadataReview() throws Exception {
        assertFalse(invokeRequiresMinMaxConditionMetadataReview("Min and max overall"));
        assertTrue(invokeRequiresMinMaxConditionMetadataReview("Min and max per condition"));
        assertFalse(invokeRequiresMinMaxConditionMetadataReview("Randomly select images"));
    }

    @Test
    public void minMaxRoleLabelMapsOverallRolesForHeader() throws Exception {
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();

        assertEquals("Overall MAX", invokeMinMaxRoleLabel(analysis, "OVERALL_MAX"));
        assertEquals("Overall MIN", invokeMinMaxRoleLabel(analysis, "OVERALL_MIN"));
        assertEquals("Overall MIN/MAX", invokeMinMaxRoleLabel(analysis, "OVERALL_MIN_MAX"));
        assertEquals("MAX", invokeMinMaxRoleLabel(analysis, "MAX"));
    }

    @Test
    public void minMaxReviewLabelOmitsConditionForOverallRolesOnly() throws Exception {
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();

        assertEquals("Overall MAX", invokeMinMaxReviewLabel(analysis,
                newSelectedSeries(2, "MouseA_LH_SCN", "MouseA", "Treatment", "OVERALL_MAX")));
        assertEquals("Treatment - MAX", invokeMinMaxReviewLabel(analysis,
                newSelectedSeries(2, "MouseA_LH_SCN", "MouseA", "Treatment", "MAX")));
    }

    @Test
    public void openQcSelectionsAddsNonBlockingZSliceWarningForInvalidSavedRange() throws Exception {
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();
        CreateBinFileAnalysis.BinUserConfig cfg = oneChannelConfig("Default");
        cfg.zSliceMode = ZSliceMode.PER_IMAGE;
        cfg.zSliceSelections.put(Integer.valueOf(4),
                new ZSliceSelection(4, "MouseA_LH_SCN", 30, new ZSliceRange(11, 30)));

        ImagePlus opened = stackWithSlices("MouseA_LH_SCN", 19);
        LinkedHashMap<Integer, ImagePlus> openedBySeries = new LinkedHashMap<Integer, ImagePlus>();
        openedBySeries.put(Integer.valueOf(4), opened);
        List<?> selections = invokeOpenQcSelections(
                analysis,
                new StubDeferredImageSupplier(openedBySeries),
                new File("experiment.lif"),
                Collections.singletonList(Integer.valueOf(4)),
                cfg);

        assertEquals(1, selections.size());
        Object selection = selections.get(0);
        assertEquals(Integer.valueOf(4), selectionField(selection, "seriesIndex"));
        assertEquals("Z-slice warning: saved range 11-30 does not fit this image (19 slices); showing the full stack.",
                selectionField(selection, "warning"));
        ImagePlus resultImage = (ImagePlus) selectionField(selection, "image");
        assertEquals(19, resultImage.getNSlices());
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
    public void setupAnalysisDialogsHideMainPhaseBreadcrumb() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        PipelineDialog dialog = invokeSetupAnalysisDialog("Set Up Configuration");
        try {
            javax.swing.JPanel breadcrumb = pipelineDialogPanel(dialog, "breadcrumbPanel");
            assertFalse(breadcrumb.isVisible());
        } finally {
            disposePipelineDialog(dialog);
        }
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
    public void qcContextImages_useMinMaxOrderForRequestedChannel() throws Exception {
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();
        List<?> selections = privateQcSelectionsWithChannelOrders();
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                "filterParameterContextImages", List.class, int.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<ConfigQcContext.ConfigQcImage> c1 =
                (List<ConfigQcContext.ConfigQcImage>) method.invoke(
                        analysis, selections, Integer.valueOf(0));
        @SuppressWarnings("unchecked")
        List<ConfigQcContext.ConfigQcImage> c2 =
                (List<ConfigQcContext.ConfigQcImage>) method.invoke(
                        analysis, selections, Integer.valueOf(1));
        @SuppressWarnings("unchecked")
        List<ConfigQcContext.ConfigQcImage> c3 =
                (List<ConfigQcContext.ConfigQcImage>) method.invoke(
                        analysis, selections, Integer.valueOf(2));

        assertEquals("Alpha", c1.get(0).getSeriesName());
        assertEquals("Beta", c1.get(1).getSeriesName());
        assertEquals("Gamma", c1.get(2).getSeriesName());

        assertEquals("Beta", c2.get(0).getSeriesName());
        assertEquals("Gamma", c2.get(1).getSeriesName());
        assertEquals("Alpha", c2.get(2).getSeriesName());
    }

    @Test
    public void qcContextImages_filterMinMaxSelectionsToRequestedChannelAndUseChannelLabel() throws Exception {
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();
        List<?> selections = privateQcSelectionsWithPartialChannelOrdersAndLabels();
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                "filterParameterContextImages", List.class, int.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<ConfigQcContext.ConfigQcImage> c1 =
                (List<ConfigQcContext.ConfigQcImage>) method.invoke(
                        analysis, selections, Integer.valueOf(0));
        @SuppressWarnings("unchecked")
        List<ConfigQcContext.ConfigQcImage> c2 =
                (List<ConfigQcContext.ConfigQcImage>) method.invoke(
                        analysis, selections, Integer.valueOf(1));
        @SuppressWarnings("unchecked")
        List<ConfigQcContext.ConfigQcImage> c3 =
                (List<ConfigQcContext.ConfigQcImage>) method.invoke(
                        analysis, selections, Integer.valueOf(2));

        assertEquals(2, c1.size());
        assertEquals("Alpha", c1.get(0).getSeriesName());
        assertEquals("C1 MAX", c1.get(0).getReviewLabel());
        assertEquals("Shared", c1.get(1).getSeriesName());
        assertEquals("C1 MIN", c1.get(1).getReviewLabel());

        assertEquals(2, c2.size());
        assertEquals("Beta", c2.get(0).getSeriesName());
        assertEquals("C2 MAX", c2.get(0).getReviewLabel());
        assertEquals("Shared", c2.get(1).getSeriesName());
        assertEquals("C2 MIN", c2.get(1).getReviewLabel());

        assertEquals(0, c3.size());
    }

    @Test
    public void filterParameterStageCustomBuilderUsesChannelFilteredQcImages() throws Exception {
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();
        CreateBinFileAnalysis.BinUserConfig cfg = twoChannelConfig();
        List<?> selections = privateQcSelectionsWithPartialChannelOrdersAndLabels();

        FilterParameterStage stage = analysis.createFilterParameterStage(
                rawQcSelections(selections), cfg, temp.getRoot(), 1);
        List<?> builderImages = capturedCustomFilterBuilderImages(stage);

        assertEquals(2, builderImages.size());
        assertEquals("Beta", selectionField(builderImages.get(0), "seriesName"));
        assertEquals("Shared", selectionField(builderImages.get(1), "seriesName"));
    }

    @Test
    public void customFilterPlaceholderRoutesThroughStandardFilterQcStage() throws Exception {
        CreateBinFileAnalysis.BinUserConfig cfg = oneChannelConfig("Custom");
        boolean[][] noSelectedSettings = new boolean[6][1];

        assertEquals(Collections.singletonList("FILTER_PARAMETERS:0"),
                invokeInteractiveQcStepPlan(new CreateBinFileAnalysis(), cfg, noSelectedSettings));
    }

    @Test
    public void filterPresetOptions_returnsFastBaseAndSelectedPresetWithoutSavedScan() throws Exception {
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

        String[] options = invokeFilterPresetOptions(analysis, binFolder, "Missing saved filter");

        assertFalse(contains(options, "IBA1 cleanup filter"));
        assertFalse(contains(options, "Legacy cleanup filter"));
        assertTrue(contains(options, "Missing saved filter"));
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
    public void saveCustomFilterPreset_invalidatesSharedAsyncIndex() throws Exception {
        File project = temp.newFolder("project-save-custom-filter-invalidates");
        File binFolder = configurationDir(project);
        assertTrue(binFolder.mkdirs());
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();

        assertFalse(BinMacroIndex.savedCustomFilterPresetNamesAsync(binFolder)
                .get(5, TimeUnit.SECONDS).contains("IBA1 cleanup filter"));

        invokeSaveCustomFilterPreset(analysis, binFolder, "IBA1 cleanup filter",
                "run(\"Median...\", \"radius=2 stack\");\n");

        assertTrue(BinMacroIndex.savedCustomFilterPresetNamesAsync(binFolder)
                .get(5, TimeUnit.SECONDS).contains("IBA1 cleanup filter"));
    }

    @Test
    public void importedCustomFilterWritesMacroAndDemotedPreset() throws Exception {
        File project = temp.newFolder("project-import-custom-filter");
        File binFolder = configurationDir(project);
        assertTrue(binFolder.mkdirs());
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();
        CreateBinFileAnalysis.BinUserConfig cfg = oneChannelConfig("Custom");
        String macro = "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n";

        boolean applied = invokeApplyCustomFilterEntryResult(
                analysis,
                binFolder,
                cfg,
                0,
                CustomFilterEntryDialog.Result.imported(macro, "Default"),
                false);

        assertTrue(applied);
        assertEquals("Default", cfg.filterPresets.get(0));
        assertEquals(macro, new String(Files.readAllBytes(
                new File(binFolder, "C1_Filters.ijm").toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void executeFiltered_visitsOnlyRequestedPagesAndPreservesExistingConfig() throws Exception {
        File dir = temp.newFolder("filtered-existing");
        ChannelConfigIO.write(configurationDir(dir), existingFilteredConfig());
        RecordingFilteredAnalysis analysis = new RecordingFilteredAnalysis();

        analysis.executeFiltered(dir.getAbsolutePath(),
                EnumSet.of(BinField.CHANNEL_NAMES, BinField.Z_SLICE));

        assertEquals(Arrays.asList("identity", "zslice"), analysis.visited);
        BinConfig updated = ChannelConfigIO.toBinConfig(ChannelConfigIO.read(configurationDir(dir)));
        assertEquals(Arrays.asList("NeuN", "DAPI"), updated.channelNames);
        assertEquals(Arrays.asList("Green", "Red"), updated.channelColors);
        assertEquals(Arrays.asList("100", "200"), updated.channelThresholds);
        assertEquals(Arrays.asList("50-Infinity", "25-500"), updated.channelSizes);
        assertEquals(Arrays.asList("10-100", "20-200"), updated.channelMinMax);
        assertEquals(Arrays.asList("30", "40"), updated.channelIntensityThresholds);
        assertEquals(Arrays.asList("classical", "stardist:0.5:0.4"), updated.segmentationMethods);
        assertEquals(Arrays.asList("Default", "Puncta Resolve"), updated.channelFilterPresets);
        assertEquals(ZSliceMode.FULL, updated.zSliceMode);
        assertFalse(new File(configurationDir(dir), "C1_Filters.ijm").exists());
    }

    @Test
    public void executeFiltered_newFolderWritesDefaultsForSkippedFields() throws Exception {
        File dir = temp.newFolder("filtered-new");
        RecordingFilteredAnalysis analysis = new RecordingFilteredAnalysis();

        analysis.executeFiltered(dir.getAbsolutePath(), EnumSet.of(BinField.CHANNEL_NAMES));

        File bin = configurationDir(dir);
        assertTrue(new File(bin, ChannelConfigIO.FILE_NAME).isFile());
        assertEquals(Collections.singletonList("identity"), analysis.visited);
        BinConfig updated = ChannelConfigIO.toBinConfig(ChannelConfigIO.read(bin));
        assertEquals(Arrays.asList("NeuN", "DAPI"), updated.channelNames);
        assertEquals(Arrays.asList("Grays", "Grays"), updated.channelColors);
        assertEquals(Arrays.asList("default", "default"), updated.channelThresholds);
        assertEquals(Arrays.asList("100-Infinity", "100-Infinity"), updated.channelSizes);
        assertEquals(Arrays.asList("None", "None"), updated.channelMinMax);
        assertEquals(Arrays.asList("default", "default"), updated.channelIntensityThresholds);
        assertEquals(Arrays.asList("classical:otsu", "classical:otsu"), updated.segmentationMethods);
        assertEquals(Arrays.asList("Default", "Default"), updated.channelFilterPresets);
        assertFalse(new File(bin, "C1_Filters.ijm").exists());

        ChannelConfig saved = ChannelConfigIO.read(bin);
        assertEquals(Boolean.FALSE, saved.complete);
        assertEquals(ChannelConfig.PropertyStatus.CONFIGURED,
                saved.channels.get(0).statusOf(ChannelConfig.P_NAME));
        assertEquals(ChannelConfig.PropertyStatus.CONFIGURED,
                saved.channels.get(0).statusOf(ChannelConfig.P_COLOR));
        assertEquals(ChannelConfig.PropertyStatus.PENDING,
                saved.channels.get(0).statusOf(ChannelConfig.P_THRESHOLD));
        assertEquals(ChannelConfig.PropertyStatus.PENDING,
                saved.channels.get(0).statusOf(ChannelConfig.P_FILTER));
    }

    @Test
    public void executeFiltered_segmentationMethodRoutesThroughObjectQcNotStandalonePage() throws Exception {
        File dir = temp.newFolder("filtered-segmentation-method");
        ChannelConfigIO.write(configurationDir(dir), existingFilteredConfig("IBA1"));
        RecordingFilteredAnalysis analysis = new RecordingFilteredAnalysis();

        analysis.executeFiltered(dir.getAbsolutePath(),
                EnumSet.of(BinField.SEGMENTATION_METHODS));

        assertEquals(Collections.singletonList("qc"), analysis.visited);
        assertEquals(EnumSet.of(BinField.SEGMENTATION_METHODS), analysis.qcFields);
    }

    @Test
    public void executeFiltered_filterPresetsRouteThroughEmbeddedQcNotDropdownPage() throws Exception {
        File dir = temp.newFolder("filtered-filter-presets");
        ChannelConfigIO.write(configurationDir(dir), existingFilteredConfig("IBA1"));
        RecordingFilteredAnalysis analysis = new RecordingFilteredAnalysis();

        analysis.executeFiltered(dir.getAbsolutePath(),
                EnumSet.of(BinField.FILTER_PRESETS));

        assertEquals(Collections.singletonList("qc"), analysis.visited);
        assertEquals(EnumSet.of(BinField.FILTER_PRESETS), analysis.qcFields);
        File bin = configurationDir(dir);
        assertTrue(new File(bin, "defaultFilter.ijm").isFile());
        assertTrue(new File(bin, "C1_Filters.ijm").isFile());
    }

    @Test
    public void executeFiltered_segmentationPartialDoesNotOverwriteExistingIntensityThreshold() throws Exception {
        File dir = temp.newFolder("filtered-segmentation-preserves-intensity");
        ChannelConfigIO.write(configurationDir(dir), existingFilteredConfig("IBA1"));
        CreateBinFileAnalysis analysis = new RecordingFilteredAnalysis() {
            @Override
            protected boolean showFilteredQcPages(String directory, File binFolder,
                                                  BinUserConfig cfg, Set<BinField> fields) {
                super.showFilteredQcPages(directory, binFolder, cfg, fields);
                cfg.objectThresholds.set(0, "999");
                cfg.intensityThresholds.set(0, "999");
                return true;
            }
        };

        analysis.executeFiltered(dir.getAbsolutePath(), EnumSet.of(BinField.SEGMENTATION_METHODS));

        BinConfig updated = ChannelConfigIO.toBinConfig(ChannelConfigIO.read(configurationDir(dir)));
        assertEquals(Collections.singletonList("999"), updated.channelThresholds);
        assertEquals(Collections.singletonList("30"), updated.channelIntensityThresholds);
    }

    @Test
    public void executeFiltered_segmentationPartialPreservesPendingRawIntensityThreshold() throws Exception {
        File dir = temp.newFolder("filtered-segmentation-preserves-pending-intensity");
        ChannelConfig seed = oneChannelDraftWithPendingThresholds("IBA1", "100", "222");
        ChannelConfigIO.write(configurationDir(dir), seed);
        CreateBinFileAnalysis analysis = new RecordingFilteredAnalysis() {
            @Override
            protected boolean showFilteredQcPages(String directory, File binFolder,
                                                  BinUserConfig cfg, Set<BinField> fields) {
                super.showFilteredQcPages(directory, binFolder, cfg, fields);
                cfg.objectThresholds.set(0, "999");
                cfg.intensityThresholds.set(0, "999");
                return true;
            }
        };

        analysis.executeFiltered(dir.getAbsolutePath(), EnumSet.of(BinField.SEGMENTATION_METHODS));

        ChannelConfig saved = ChannelConfigIO.read(configurationDir(dir));
        assertEquals("999", saved.channels.get(0).threshold);
        assertEquals("222", saved.channels.get(0).intensityThreshold);
        assertEquals(ChannelConfig.PropertyStatus.PENDING,
                saved.channels.get(0).statusOf(ChannelConfig.P_INTENSITY));
    }

    @Test
    public void executeFiltered_intensityPartialPreservesPendingRawObjectThreshold() throws Exception {
        File dir = temp.newFolder("filtered-intensity-preserves-pending-object");
        ChannelConfig seed = oneChannelDraftWithPendingThresholds("IBA1", "321", "222");
        ChannelConfigIO.write(configurationDir(dir), seed);
        CreateBinFileAnalysis analysis = new RecordingFilteredAnalysis() {
            @Override
            protected boolean showFilteredQcPages(String directory, File binFolder,
                                                  BinUserConfig cfg, Set<BinField> fields) {
                super.showFilteredQcPages(directory, binFolder, cfg, fields);
                cfg.objectThresholds.set(0, "999");
                cfg.intensityThresholds.set(0, "888");
                return true;
            }
        };

        analysis.executeFiltered(dir.getAbsolutePath(), EnumSet.of(BinField.INTENSITY_THRESHOLDS));

        ChannelConfig saved = ChannelConfigIO.read(configurationDir(dir));
        assertEquals("321", saved.channels.get(0).threshold);
        assertEquals("888", saved.channels.get(0).intensityThreshold);
        assertEquals(ChannelConfig.PropertyStatus.PENDING,
                saved.channels.get(0).statusOf(ChannelConfig.P_THRESHOLD));
        assertEquals(ChannelConfig.PropertyStatus.CONFIGURED,
                saved.channels.get(0).statusOf(ChannelConfig.P_INTENSITY));
    }

    @Test
    public void autoSelectedFilteredSettings_ticksRequiredSettingsForEveryChannel() {
        // The partial path skips the Settings Mode toggle screen and auto-selects
        // the analysis's required settings for every channel. Matrix rows mirror
        // the private SETTINGS_* constants: 0=filter parameters, 1=min-max,
        // 2=ROI/intensity threshold, 3=object threshold, 4=object size filter,
        // 5=segmentation method.
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();
        CreateBinFileAnalysis.BinUserConfig cfg = twoChannelConfig();
        cfg.segmentationMethods.clear();
        cfg.segmentationMethods.addAll(Arrays.asList("classical", "stardist:0.5:0.4"));

        boolean[][] filterParameters = analysis.autoSelectedFilteredSettings(
                cfg, true, false, false, false, false, false);
        assertTrue(filterParameters[0][0]);
        assertTrue(filterParameters[0][1]);
        assertFalse(filterParameters[1][0]);
        assertFalse(filterParameters[2][0]);

        // Object analysis (segmentation method + particle size) for both channels.
        boolean[][] object = analysis.autoSelectedFilteredSettings(
                cfg, false, false, true, false, true, true);
        for (int ch = 0; ch < 2; ch++) {
            assertTrue(object[5][ch]);
            assertTrue(object[3][ch]);
            assertTrue(object[4][ch]);
            assertFalse(object[2][ch]);
            assertFalse(object[1][ch]);
        }

        // Intensity threshold only must not route classical channels into object segmentation.
        boolean[][] intensity = analysis.autoSelectedFilteredSettings(
                cfg, false, false, false, true, false, false);
        assertTrue(intensity[2][0]);
        assertFalse(intensity[3][0]);
        assertTrue(intensity[2][1]);
        assertFalse(intensity[3][1]);
        assertFalse(intensity[5][0]);

        // Object threshold only remains an object-segmentation setting for classical channels.
        boolean[][] objectThreshold = analysis.autoSelectedFilteredSettings(
                cfg, false, false, true, false, false, false);
        assertFalse(objectThreshold[2][0]);
        assertTrue(objectThreshold[3][0]);
        assertFalse(objectThreshold[2][1]);
        assertFalse(objectThreshold[3][1]);
        assertFalse(objectThreshold[5][0]);
    }

    @Test
    public void intensityThresholdOnlyPartialSetupUsesChannelThresholdQcNotObjectSegmentation() throws Exception {
        CreateBinFileAnalysis.BinUserConfig cfg = twoChannelConfig();
        boolean[][] customSettings = new CreateBinFileAnalysis()
                .autoSelectedFilteredSettings(cfg, false, false, false, true, false, false);

        assertEquals(Arrays.asList(
                "CHANNEL_THRESHOLD:0",
                "CHANNEL_THRESHOLD:1"),
                invokeInteractiveQcStepPlan(new CreateBinFileAnalysis(), cfg, customSettings));
    }

    @Test
    public void intensityPartialSetupUsesFilterQcBeforeChannelThresholdQc() throws Exception {
        CreateBinFileAnalysis.BinUserConfig cfg = twoChannelConfig();
        boolean[][] customSettings = new CreateBinFileAnalysis()
                .autoSelectedFilteredSettings(cfg, true, false, false, true, false, false);

        assertEquals(Arrays.asList(
                "FILTER_PARAMETERS:0",
                "CHANNEL_THRESHOLD:0",
                "FILTER_PARAMETERS:1",
                "CHANNEL_THRESHOLD:1"),
                invokeInteractiveQcStepPlan(new CreateBinFileAnalysis(), cfg, customSettings));
    }

    @Test
    public void intensityOnlyChannelThresholdStageDoesNotMutateObjectThreshold() throws Exception {
        File binFolder = temp.newFolder("intensity-only-threshold-stage");
        Files.write(new File(binFolder, "C1_Filters.ijm").toPath(), new byte[0]);
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();
        CreateBinFileAnalysis.BinUserConfig cfg = oneChannelConfig("Default");
        cfg.objectThresholds.set(0, "100");
        cfg.intensityThresholds.set(0, "30");
        ConfigQcContext context = ConfigQcContext.fromImages(
                temp.getRoot(),
                binFolder,
                cfg,
                Arrays.asList(byteImage("threshold intensity only")),
                cfg.names,
                0);
        ChannelThresholdStage stage = invokeCreateChannelThresholdStage(
                analysis, cfg, binFolder, 0, false);

        stage.buildControls(context, new NoopConfigQcActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));
        invokeStageTestMethod(stage, "setThresholdForTest",
                new Class<?>[]{double.class, double.class},
                new Object[]{Double.valueOf(44.0), Double.valueOf(255.0)});
        assertTrue(stage.lockIn(context));
        stage.onLeave(context);

        assertEquals("100", cfg.objectThresholds.get(0));
        assertFalse("30".equals(cfg.intensityThresholds.get(0)));
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
        assertFalse(containsComponentText(grid.panel, "Display color"));
        assertEquals(0, countComponentNamesContaining(grid.panel, "filter"));
    }

    @Test
    public void channelIdentityGridAppliesReporterProteinLutOnExactMarkerName() {
        CreateBinFileAnalysis.BinUserConfig defaults = twoChannelConfig();
        defaults.names.set(0, "Channel1");
        defaults.names.set(1, "Channel2");
        defaults.colors.set(0, "Blue");
        defaults.colors.set(1, "Magenta");

        CreateBinFileAnalysis.ChannelIdentityGrid grid =
                CreateBinFileAnalysis.buildChannelIdentityGrid(defaults, true, true, null);

        grid.nameFields[0].setText("mCh");
        assertEquals("Blue", grid.lutCombos[0].getSelectedItem());

        grid.nameFields[0].setText("mCherry");
        assertEquals("Red", grid.lutCombos[0].getSelectedItem());

        grid.nameFields[0].setText("mCherry fusion");
        assertEquals("Blue", grid.lutCombos[0].getSelectedItem());

        grid.nameFields[1].setText("DAPI");
        assertEquals("Magenta", grid.lutCombos[1].getSelectedItem());

        grid.nameFields[1].setText("reporter_mcherry");
        assertEquals("Magenta", grid.lutCombos[1].getSelectedItem());

        grid.nameFields[1].setText("GFP");
        assertEquals("Green", grid.lutCombos[1].getSelectedItem());
    }

    @Test
    public void channelIdentityGridLoadedConfigDoesNotSeedAutoLutState() throws Exception {
        CreateBinFileAnalysis.BinUserConfig defaults = twoChannelConfig();
        defaults.names.set(0, "Channel1");
        defaults.colors.set(0, "Blue");
        CreateBinFileAnalysis.ChannelIdentityGrid grid =
                CreateBinFileAnalysis.buildChannelIdentityGrid(defaults, true, true, null);
        Object bindings = newBinSetupBindings(2);
        copyBindingArray(bindings, "nameFields", grid.nameFields);
        copyBindingArray(bindings, "colorCombos", grid.lutCombos);

        CreateBinFileAnalysis.BinUserConfig loaded = twoChannelConfig();
        loaded.names.set(0, "mCherry");
        loaded.colors.set(0, "Red");
        invokeApplyLoadedConfigToIdentityBindings(loaded, bindings);
        assertEquals("Red", grid.lutCombos[0].getSelectedItem());

        grid.nameFields[0].setText("mCherry fusion");
        assertEquals("Red", grid.lutCombos[0].getSelectedItem());

        loaded.colors.set(0, "Blue");
        invokeApplyLoadedConfigToIdentityBindings(loaded, bindings);
        assertEquals("Blue", grid.lutCombos[0].getSelectedItem());
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
    public void buildConfigFromDialogSwitchingToStarDistClearsGenericSizeDefault() throws Exception {
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();
        CreateBinFileAnalysis.BinUserConfig draft = twoChannelConfig();
        Object bindings = newBinSetupBindings(2);
        @SuppressWarnings("unchecked")
        JComboBox<String>[] segmentationCombos = new JComboBox[]{
                new JComboBox<String>(new String[]{"Classical", "StarDist 3D"}),
                new JComboBox<String>(new String[]{"Classical", "StarDist 3D"})
        };
        segmentationCombos[0].setSelectedItem("Classical");
        segmentationCombos[1].setSelectedItem("StarDist 3D");
        copyBindingArray(bindings, "segmentationCombos", segmentationCombos);

        CreateBinFileAnalysis.BinUserConfig result =
                invokeBuildBinUserConfigFromDialog(analysis, 2, draft, bindings);

        assertEquals("classical", result.segmentationMethods.get(0));
        assertEquals("stardist:0.5:0.4", result.segmentationMethods.get(1));
        assertEquals("100-Infinity", result.sizes.get(0));
        assertEquals("0-Infinity", result.sizes.get(1));
    }

    @Test
    public void buildConfigFromDialogSwitchingToStarDistPreservesExplicitSizeFilter() throws Exception {
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();
        CreateBinFileAnalysis.BinUserConfig draft = twoChannelConfig();
        draft.sizes.set(1, "80-Infinity");
        Object bindings = newBinSetupBindings(2);
        @SuppressWarnings("unchecked")
        JComboBox<String>[] segmentationCombos = new JComboBox[]{
                new JComboBox<String>(new String[]{"Classical", "StarDist 3D"}),
                new JComboBox<String>(new String[]{"Classical", "StarDist 3D"})
        };
        segmentationCombos[0].setSelectedItem("Classical");
        segmentationCombos[1].setSelectedItem("StarDist 3D");
        copyBindingArray(bindings, "segmentationCombos", segmentationCombos);

        CreateBinFileAnalysis.BinUserConfig result =
                invokeBuildBinUserConfigFromDialog(analysis, 2, draft, bindings);

        assertEquals("stardist:0.5:0.4", result.segmentationMethods.get(1));
        assertEquals("80-Infinity", result.sizes.get(1));
    }

    @Test
    public void buildConfigFromDialogDoesNotPersistLoadingFiltersPlaceholder() throws Exception {
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();
        CreateBinFileAnalysis.BinUserConfig draft = twoChannelConfig();
        draft.filterPresets.set(0, "Puncta Resolve");
        Object bindings = newBinSetupBindings(2);
        @SuppressWarnings("unchecked")
        JComboBox<String>[] filterCombos = new JComboBox[]{
                new JComboBox<String>(new String[]{"Loading filters..."}),
                new JComboBox<String>(new String[]{"Custom"})
        };
        filterCombos[0].setSelectedItem("Loading filters...");
        filterCombos[1].setSelectedItem("Custom");
        copyBindingArray(bindings, "filterCombos", filterCombos);

        CreateBinFileAnalysis.BinUserConfig result =
                invokeBuildBinUserConfigFromDialog(analysis, 2, draft, bindings);

        assertEquals("Puncta Resolve", result.filterPresets.get(0));
        assertEquals("Custom", result.filterPresets.get(1));
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
        applied.segmentationMethods.clear();
        applied.segmentationMethods.addAll(Arrays.asList("cellpose:cyto2:30:true:0.4:0.0", "stardist:0.6:0.3"));
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
        assertEquals(Arrays.asList("cellpose:cyto2:30:true:0.4:0.0", "stardist:0.6:0.3"),
                result.segmentationMethods);
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
        boolean[][] customSettings = new boolean[6][1];
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
    public void resumeQualityCheckStartsAtFirstIncompleteSubStep() throws Exception {
        File projectRoot = temp.newFolder("resume-qc-substep");
        File binFolder = FlashProjectLayout.forDirectory(
                projectRoot.getAbsolutePath()).configurationWriteDir();
        assertTrue(binFolder.mkdirs());

        CreateBinFileAnalysis.BinUserConfig cfg = oneChannelConfig("Default");
        cfg.minmax.set(0, "10-100");
        boolean[][] customSettings = new boolean[6][1];
        customSettings[1][0] = true; // display range
        customSettings[2][0] = true; // unified threshold

        ChannelConfig draft = ChannelConfigIO.fromBinUserConfig(cfg);
        markAllPending(draft.channels.get(0));
        draft.channels.get(0).status.put(ChannelConfig.P_NAME,
                ChannelConfig.PropertyStatus.CONFIGURED);
        draft.channels.get(0).status.put(ChannelConfig.P_COLOR,
                ChannelConfig.PropertyStatus.CONFIGURED);
        draft.channels.get(0).status.put(ChannelConfig.P_MARKER,
                ChannelConfig.PropertyStatus.CONFIGURED);
        draft.channels.get(0).status.put(ChannelConfig.P_MINMAX,
                ChannelConfig.PropertyStatus.CONFIGURED);
        draft.extras.put("lastStepIndex", Integer.valueOf(5));
        draft.extras.put("lastStepLabel", "Quality Check");
        draft.extras.put("customSettings", Arrays.<Object>asList(
                Arrays.<Object>asList(Boolean.FALSE),
                Arrays.<Object>asList(Boolean.TRUE),
                Arrays.<Object>asList(Boolean.TRUE),
                Arrays.<Object>asList(Boolean.FALSE),
                Arrays.<Object>asList(Boolean.FALSE),
                Arrays.<Object>asList(Boolean.FALSE)));
        ChannelConfigIO.write(binFolder, draft);

        ResumeQualityCheckAnalysis analysis = new ResumeQualityCheckAnalysis(
                privateQcSelections(byteImage("resume qc route")));
        analysis.setSuppressDialogs(true);

        analysis.handleFullCreation(projectRoot.getAbsolutePath(), binFolder, null);

        assertEquals(Collections.<Class<?>>singletonList(ChannelThresholdStage.class),
                analysis.firstStageByDialog);
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
    public void segmentationObjectIncrementalSaveDoesNotPersistUnrequestedIntensityThreshold() throws Exception {
        File project = temp.newFolder("segmentation-incremental-preserves-intensity");
        File binFolder = configurationDir(project);
        ChannelConfigIO.write(binFolder, existingFilteredConfig("IBA1"));
        CreateBinFileAnalysis.BinUserConfig cfg = oneChannelConfig("Default");
        cfg.names.set(0, "IBA1");
        cfg.objectThresholds.set(0, "999");
        cfg.intensityThresholds.set(0, "999");
        cfg.sizes.set(0, "12-34");
        boolean[][] customSettings = new boolean[6][1];
        customSettings[5][0] = true; // object segmentation only; no ROI/intensity threshold
        Object step = firstInteractiveQcStep(new CreateBinFileAnalysis(), cfg, customSettings);

        invokePersistInteractiveQcStep(new CreateBinFileAnalysis(), binFolder, cfg, customSettings, step);

        ChannelConfig saved = ChannelConfigIO.read(binFolder);
        ChannelConfig.Channel channel = saved.channels.get(0);
        assertEquals("999", channel.threshold);
        assertEquals("12-34", channel.size);
        assertEquals("30", channel.intensityThreshold);
        assertEquals(ChannelConfig.PropertyStatus.CONFIGURED,
                channel.statusOf(ChannelConfig.P_THRESHOLD));
        assertEquals(ChannelConfig.PropertyStatus.CONFIGURED,
                channel.statusOf(ChannelConfig.P_SIZE));
        assertEquals(ChannelConfig.PropertyStatus.CONFIGURED,
                channel.statusOf(ChannelConfig.P_SEGMENTATION));
        assertEquals(ChannelConfig.PropertyStatus.COMMITTED,
                channel.statusOf(ChannelConfig.P_INTENSITY));
    }

    @Test
    public void segmentationObjectIncrementalSavePreservesPendingRawIntensityThreshold() throws Exception {
        File project = temp.newFolder("segmentation-incremental-preserves-pending-intensity");
        File binFolder = configurationDir(project);
        ChannelConfigIO.write(binFolder, oneChannelDraftWithPendingThresholds("IBA1", "100", "222"));
        CreateBinFileAnalysis.BinUserConfig cfg = oneChannelConfig("Default");
        cfg.names.set(0, "IBA1");
        cfg.objectThresholds.set(0, "999");
        cfg.intensityThresholds.set(0, "999");
        cfg.sizes.set(0, "12-34");
        boolean[][] customSettings = new boolean[6][1];
        customSettings[5][0] = true; // object segmentation only; no ROI/intensity threshold
        Object step = firstInteractiveQcStep(new CreateBinFileAnalysis(), cfg, customSettings);

        invokePersistInteractiveQcStep(new CreateBinFileAnalysis(), binFolder, cfg, customSettings, step);

        ChannelConfig saved = ChannelConfigIO.read(binFolder);
        ChannelConfig.Channel channel = saved.channels.get(0);
        assertEquals("999", channel.threshold);
        assertEquals("222", channel.intensityThreshold);
        assertEquals(ChannelConfig.PropertyStatus.CONFIGURED,
                channel.statusOf(ChannelConfig.P_THRESHOLD));
        assertEquals(ChannelConfig.PropertyStatus.PENDING,
                channel.statusOf(ChannelConfig.P_INTENSITY));
    }

    @Test
    public void segmentationObjectIncrementalSavePreservesPendingRawValuesOnOtherChannels() throws Exception {
        File project = temp.newFolder("segmentation-incremental-preserves-other-channel");
        File binFolder = configurationDir(project);
        ChannelConfigIO.write(binFolder, twoChannelDraftWithPendingThresholds(
                new String[]{"IBA1", "GFAP"},
                new String[]{"100", "200"},
                new String[]{"222", "333"}));
        CreateBinFileAnalysis.BinUserConfig cfg = twoChannelConfig();
        cfg.names.set(0, "IBA1");
        cfg.names.set(1, "GFAP");
        cfg.objectThresholds.set(0, "999");
        cfg.objectThresholds.set(1, "777");
        cfg.intensityThresholds.set(0, "999");
        cfg.intensityThresholds.set(1, "777");
        cfg.sizes.set(0, "12-34");
        cfg.sizes.set(1, "56-78");
        boolean[][] customSettings = new boolean[6][2];
        customSettings[5][0] = true; // object segmentation only on C1
        Object step = firstInteractiveQcStep(new CreateBinFileAnalysis(), cfg, customSettings);

        invokePersistInteractiveQcStep(new CreateBinFileAnalysis(), binFolder, cfg, customSettings, step);

        ChannelConfig saved = ChannelConfigIO.read(binFolder);
        ChannelConfig.Channel c1 = saved.channels.get(0);
        ChannelConfig.Channel c2 = saved.channels.get(1);
        assertEquals("999", c1.threshold);
        assertEquals("222", c1.intensityThreshold);
        assertEquals("200", c2.threshold);
        assertEquals("333", c2.intensityThreshold);
        assertEquals("100-Infinity", c2.size);
        assertEquals(ChannelConfig.PropertyStatus.PENDING, c2.statusOf(ChannelConfig.P_THRESHOLD));
        assertEquals(ChannelConfig.PropertyStatus.PENDING, c2.statusOf(ChannelConfig.P_INTENSITY));
        assertEquals(ChannelConfig.PropertyStatus.PENDING, c2.statusOf(ChannelConfig.P_SIZE));
    }

    @Test
    public void channelThresholdIncrementalSaveDoesNotPersistUnrequestedObjectThreshold() throws Exception {
        File project = temp.newFolder("threshold-incremental-preserves-object");
        File binFolder = configurationDir(project);
        ChannelConfig seed = new ChannelConfig();
        seed.complete = Boolean.FALSE;
        ChannelConfig.Channel seededChannel = new ChannelConfig.Channel();
        seededChannel.index = 0;
        seededChannel.name = "IBA1";
        seededChannel.color = "Green";
        seededChannel.threshold = "321";
        seededChannel.status.put(ChannelConfig.P_NAME, ChannelConfig.PropertyStatus.CONFIGURED);
        seededChannel.status.put(ChannelConfig.P_COLOR, ChannelConfig.PropertyStatus.CONFIGURED);
        seededChannel.status.put(ChannelConfig.P_MARKER, ChannelConfig.PropertyStatus.CONFIGURED);
        seededChannel.status.put(ChannelConfig.P_THRESHOLD, ChannelConfig.PropertyStatus.PENDING);
        seededChannel.status.put(ChannelConfig.P_SIZE, ChannelConfig.PropertyStatus.PENDING);
        seededChannel.status.put(ChannelConfig.P_MINMAX, ChannelConfig.PropertyStatus.PENDING);
        seededChannel.status.put(ChannelConfig.P_INTENSITY, ChannelConfig.PropertyStatus.PENDING);
        seededChannel.status.put(ChannelConfig.P_SEGMENTATION, ChannelConfig.PropertyStatus.PENDING);
        seededChannel.status.put(ChannelConfig.P_FILTER, ChannelConfig.PropertyStatus.PENDING);
        seed.channels.add(seededChannel);
        ChannelConfigIO.write(binFolder, seed);
        CreateBinFileAnalysis.BinUserConfig cfg = oneChannelConfig("Default");
        cfg.names.set(0, "IBA1");
        cfg.objectThresholds.set(0, "999");
        cfg.intensityThresholds.set(0, "888");
        boolean[][] customSettings = new boolean[6][1];
        customSettings[2][0] = true; // ROI/intensity threshold only; no object threshold
        Object step = firstInteractiveQcStep(new CreateBinFileAnalysis(), cfg, customSettings);

        invokePersistInteractiveQcStep(new CreateBinFileAnalysis(), binFolder, cfg, customSettings, step);

        ChannelConfig saved = ChannelConfigIO.read(binFolder);
        ChannelConfig.Channel channel = saved.channels.get(0);
        assertEquals("321", channel.threshold);
        assertEquals("888", channel.intensityThreshold);
        assertEquals(ChannelConfig.PropertyStatus.PENDING,
                channel.statusOf(ChannelConfig.P_THRESHOLD));
        assertEquals(ChannelConfig.PropertyStatus.CONFIGURED,
                channel.statusOf(ChannelConfig.P_INTENSITY));
    }

    @Test
    public void channelThresholdIncrementalSavePreservesPendingRawValuesOnOtherChannels() throws Exception {
        File project = temp.newFolder("threshold-incremental-preserves-other-channel");
        File binFolder = configurationDir(project);
        ChannelConfigIO.write(binFolder, twoChannelDraftWithPendingThresholds(
                new String[]{"IBA1", "GFAP"},
                new String[]{"100", "200"},
                new String[]{"222", "333"}));
        CreateBinFileAnalysis.BinUserConfig cfg = twoChannelConfig();
        cfg.names.set(0, "IBA1");
        cfg.names.set(1, "GFAP");
        cfg.objectThresholds.set(0, "999");
        cfg.objectThresholds.set(1, "777");
        cfg.intensityThresholds.set(0, "888");
        cfg.intensityThresholds.set(1, "666");
        boolean[][] customSettings = new boolean[6][2];
        customSettings[2][0] = true; // ROI/intensity threshold only on C1
        Object step = firstInteractiveQcStep(new CreateBinFileAnalysis(), cfg, customSettings);

        invokePersistInteractiveQcStep(new CreateBinFileAnalysis(), binFolder, cfg, customSettings, step);

        ChannelConfig saved = ChannelConfigIO.read(binFolder);
        ChannelConfig.Channel c1 = saved.channels.get(0);
        ChannelConfig.Channel c2 = saved.channels.get(1);
        assertEquals("100", c1.threshold);
        assertEquals("888", c1.intensityThreshold);
        assertEquals("200", c2.threshold);
        assertEquals("333", c2.intensityThreshold);
        assertEquals("100-Infinity", c2.size);
        assertEquals(ChannelConfig.PropertyStatus.PENDING, c1.statusOf(ChannelConfig.P_THRESHOLD));
        assertEquals(ChannelConfig.PropertyStatus.CONFIGURED, c1.statusOf(ChannelConfig.P_INTENSITY));
        assertEquals(ChannelConfig.PropertyStatus.PENDING, c2.statusOf(ChannelConfig.P_THRESHOLD));
        assertEquals(ChannelConfig.PropertyStatus.PENDING, c2.statusOf(ChannelConfig.P_INTENSITY));
        assertEquals(ChannelConfig.PropertyStatus.PENDING, c2.statusOf(ChannelConfig.P_SIZE));
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

    @Test
    public void setupPreviewExtractsConfiguredThirdChannelFromUnderReportedStack() throws Exception {
        File binFolder = temp.newFolder("third-channel-preview");
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();
        CreateBinFileAnalysis.BinUserConfig cfg = threeChannelConfig();
        ConfigQcContext context = ConfigQcContext.fromImages(
                temp.getRoot(),
                binFolder,
                cfg,
                Arrays.asList(underReportedThreeChannelStack("interleaved")),
                cfg.names,
                2);

        ImagePlus extracted = invokeDuplicateCurrentChannel(analysis, context, 3);

        assertEquals(1, extracted.getNChannels());
        assertEquals(1, extracted.getStackSize());
        assertEquals(33, extracted.getProcessor().get(0, 0));
        assertEquals(34, extracted.getProcessor().get(1, 0));

        ImagePlus firstChannel = invokeDuplicateCurrentChannel(analysis, context, 1);
        assertEquals(1, firstChannel.getNChannels());
        assertEquals(1, firstChannel.getStackSize());
        assertEquals(11, firstChannel.getProcessor().get(0, 0));
        assertEquals(12, firstChannel.getProcessor().get(1, 0));

        Files.write(new File(binFolder, "C3_Filters.ijm").toPath(), new byte[0]);
        ClassicalSegmentationStage stage = analysis.createClassicalSegmentationStage(cfg, binFolder, 2);
        stage.buildControls(context, new NoopConfigQcActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Objects"));
        invokeStageTestMethod(stage, "setThresholdForTest",
                new Class<?>[]{double.class, double.class},
                new Object[]{Double.valueOf(20.0), Double.valueOf(255.0)});
        assertTrue(stage.lockIn(context));
        stage.onLeave(context);
    }

    @Test
    public void setupPreviewFailsLoudlyWhenConfiguredChannelCannotBeExtracted() throws Exception {
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();
        CreateBinFileAnalysis.BinUserConfig cfg = threeChannelConfig();
        ConfigQcContext context = ConfigQcContext.fromImages(
                temp.getRoot(),
                temp.getRoot(),
                cfg,
                Arrays.asList(twoPlaneUnderReportedStack("bad interleaving")),
                cfg.names,
                2);

        try {
            invokeDuplicateCurrentChannel(analysis, context, 3);
            assertTrue("Expected channel extraction to fail.", false);
        } catch (java.lang.reflect.InvocationTargetException e) {
            assertTrue(e.getCause() instanceof IllegalStateException);
            assertTrue(e.getCause().getMessage().contains("Cannot extract C3"));
        }
    }

    private static File configurationDir(File dir) {
        return new File(dir, "FLASH/Config/.settings");
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

    private static ChannelConfig existingFilteredConfig() {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.addAll(Arrays.asList("IBA1", "GFAP"));
        cfg.channelColors.addAll(Arrays.asList("Green", "Red"));
        cfg.channelThresholds.addAll(Arrays.asList("100", "200"));
        cfg.channelSizes.addAll(Arrays.asList("50-Infinity", "25-500"));
        cfg.channelMinMax.addAll(Arrays.asList("10-100", "20-200"));
        cfg.channelIntensityThresholds.addAll(Arrays.asList("30", "40"));
        cfg.segmentationMethods.addAll(Arrays.asList("classical", "stardist:0.5:0.4"));
        cfg.channelFilterPresets.addAll(Arrays.asList("Default", "Puncta Resolve"));
        cfg.zSliceMode = ZSliceMode.PER_IMAGE;
        cfg.zSliceConfigPresent = true;
        return ChannelConfigIO.fromBinConfig(cfg);
    }

    private static ChannelConfig existingFilteredConfig(String name) {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add(name);
        cfg.channelColors.add("Green");
        cfg.channelThresholds.add("100");
        cfg.channelSizes.add("50-Infinity");
        cfg.channelMinMax.add("10-100");
        cfg.channelIntensityThresholds.add("30");
        cfg.segmentationMethods.add("classical");
        cfg.channelFilterPresets.add("Default");
        cfg.zSliceMode = ZSliceMode.FULL;
        cfg.zSliceConfigPresent = true;
        return ChannelConfigIO.fromBinConfig(cfg);
    }

    private static ChannelConfig oneChannelDraftWithPendingThresholds(String name,
                                                                      String objectThreshold,
                                                                      String intensityThreshold) {
        return twoChannelDraftWithPendingThresholds(
                new String[]{name},
                new String[]{objectThreshold},
                new String[]{intensityThreshold});
    }

    private static ChannelConfig twoChannelDraftWithPendingThresholds(String[] names,
                                                                      String[] objectThresholds,
                                                                      String[] intensityThresholds) {
        ChannelConfig cfg = new ChannelConfig();
        cfg.complete = Boolean.FALSE;
        int n = names == null ? 0 : names.length;
        for (int i = 0; i < n; i++) {
            ChannelConfig.Channel channel = new ChannelConfig.Channel();
            channel.index = i;
            channel.name = names[i];
            channel.color = i == 0 ? "Green" : "Red";
            channel.threshold = objectThresholds == null || i >= objectThresholds.length
                    ? "default" : objectThresholds[i];
            channel.intensityThreshold = intensityThresholds == null || i >= intensityThresholds.length
                    ? "default" : intensityThresholds[i];
            channel.size = "100-Infinity";
            channel.minmax = "None";
            channel.segmentationMethod = "classical";
            channel.filterPreset = "Default";
            channel.status.put(ChannelConfig.P_NAME, ChannelConfig.PropertyStatus.CONFIGURED);
            channel.status.put(ChannelConfig.P_COLOR, ChannelConfig.PropertyStatus.CONFIGURED);
            channel.status.put(ChannelConfig.P_MARKER, ChannelConfig.PropertyStatus.CONFIGURED);
            channel.status.put(ChannelConfig.P_THRESHOLD, ChannelConfig.PropertyStatus.PENDING);
            channel.status.put(ChannelConfig.P_SIZE, ChannelConfig.PropertyStatus.PENDING);
            channel.status.put(ChannelConfig.P_MINMAX, ChannelConfig.PropertyStatus.PENDING);
            channel.status.put(ChannelConfig.P_INTENSITY, ChannelConfig.PropertyStatus.PENDING);
            channel.status.put(ChannelConfig.P_SEGMENTATION, ChannelConfig.PropertyStatus.PENDING);
            channel.status.put(ChannelConfig.P_FILTER, ChannelConfig.PropertyStatus.PENDING);
            cfg.channels.add(channel);
        }
        return cfg;
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

    private static CreateBinFileAnalysis.BinUserConfig threeChannelConfig() {
        return new CreateBinFileAnalysis.BinUserConfig(
                new ArrayList<String>(Arrays.asList("Channel1", "Channel2", "Channel3")),
                new ArrayList<String>(Arrays.asList("Blue", "Green", "Magenta")),
                new ArrayList<String>(Arrays.asList("default", "default", "default")),
                new ArrayList<String>(Arrays.asList("100-Infinity", "100-Infinity", "100-Infinity")),
                new ArrayList<String>(Arrays.asList("None", "None", "None")),
                new ArrayList<String>(Arrays.asList("Default", "Default", "Default")),
                new ArrayList<String>(Arrays.asList("default", "default", "default")));
    }

    private static ImagePlus underReportedThreeChannelStack(String title) {
        ImageStack stack = new ImageStack(2, 1);
        stack.addSlice("C1", byteSlice(11, 12));
        stack.addSlice("C2", byteSlice(22, 23));
        stack.addSlice("C3", byteSlice(33, 34));
        return new ImagePlus(title, stack);
    }

    private static ImagePlus twoPlaneUnderReportedStack(String title) {
        ImageStack stack = new ImageStack(2, 1);
        stack.addSlice("Plane 1", byteSlice(11, 12));
        stack.addSlice("Plane 2", byteSlice(22, 23));
        return new ImagePlus(title, stack);
    }

    private static ImagePlus stackWithSlices(String title, int nSlices) {
        ImageStack stack = new ImageStack(2, 1);
        for (int i = 0; i < nSlices; i++) {
            stack.addSlice("Z" + (i + 1), byteSlice(10 + i, 20 + i));
        }
        return new ImagePlus(title, stack);
    }

    private static ByteProcessor byteSlice(int first, int second) {
        ByteProcessor processor = new ByteProcessor(2, 1);
        processor.set(0, 0, first);
        processor.set(1, 0, second);
        return processor;
    }

    private static void markAllPending(ChannelConfig.Channel channel) {
        channel.status.put(ChannelConfig.P_NAME, ChannelConfig.PropertyStatus.PENDING);
        channel.status.put(ChannelConfig.P_COLOR, ChannelConfig.PropertyStatus.PENDING);
        channel.status.put(ChannelConfig.P_MARKER, ChannelConfig.PropertyStatus.PENDING);
        channel.status.put(ChannelConfig.P_THRESHOLD, ChannelConfig.PropertyStatus.PENDING);
        channel.status.put(ChannelConfig.P_SIZE, ChannelConfig.PropertyStatus.PENDING);
        channel.status.put(ChannelConfig.P_MINMAX, ChannelConfig.PropertyStatus.PENDING);
        channel.status.put(ChannelConfig.P_INTENSITY, ChannelConfig.PropertyStatus.PENDING);
        channel.status.put(ChannelConfig.P_SEGMENTATION, ChannelConfig.PropertyStatus.PENDING);
        channel.status.put(ChannelConfig.P_FILTER, ChannelConfig.PropertyStatus.PENDING);
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

    private static void invokeApplyLoadedConfigToIdentityBindings(
            CreateBinFileAnalysis.BinUserConfig cfg,
            Object bindings) throws Exception {
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                "applyLoadedConfigToIdentityBindings",
                CreateBinFileAnalysis.BinUserConfig.class,
                bindings.getClass());
        method.setAccessible(true);
        method.invoke(null, cfg, bindings);
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

    private static boolean invokeIsMinMaxSelectionMode(String mode) throws Exception {
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                "isMinMaxSelectionMode", String.class);
        method.setAccessible(true);
        return ((Boolean) method.invoke(null, mode)).booleanValue();
    }

    private static boolean invokeRequiresMinMaxConditionMetadataReview(String mode) throws Exception {
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                "requiresMinMaxConditionMetadataReview", String.class);
        method.setAccessible(true);
        return ((Boolean) method.invoke(null, mode)).booleanValue();
    }

    private static String invokeMinMaxRoleLabel(CreateBinFileAnalysis analysis, String role) throws Exception {
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                "minMaxRoleLabel", String.class);
        method.setAccessible(true);
        return (String) method.invoke(analysis, role);
    }

    private static String invokeMinMaxReviewLabel(
            CreateBinFileAnalysis analysis,
            QcMinMaxPerConditionSelector.SelectedSeries selected) throws Exception {
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                "minMaxReviewLabel", QcMinMaxPerConditionSelector.SelectedSeries.class);
        method.setAccessible(true);
        return (String) method.invoke(analysis, selected);
    }

    private static QcMinMaxPerConditionSelector.SelectedSeries newSelectedSeries(
            int seriesIndex,
            String seriesName,
            String animalName,
            String conditionName,
            String role) throws Exception {
        QcSelectionCandidate candidate = new QcSelectionCandidate(
                seriesIndex, seriesName, animalName, conditionName);
        Class<?> scoreRecordClass =
                Class.forName("flash.pipeline.qc.QcMinMaxPerConditionSelector$ScoreRecord");
        Constructor<?> scoreRecordConstructor =
                scoreRecordClass.getDeclaredConstructor(QcSelectionCandidate.class);
        scoreRecordConstructor.setAccessible(true);
        Object scoreRecord = scoreRecordConstructor.newInstance(candidate);
        Constructor<QcMinMaxPerConditionSelector.SelectedSeries> selectedConstructor =
                QcMinMaxPerConditionSelector.SelectedSeries.class.getDeclaredConstructor(
                        scoreRecordClass, String.class);
        selectedConstructor.setAccessible(true);
        return selectedConstructor.newInstance(scoreRecord, role);
    }

    private static List<?> invokeOpenQcSelections(CreateBinFileAnalysis analysis,
                                                  DeferredImageSupplier supplier,
                                                  File lifFile,
                                                  List<Integer> selectedSeriesIndexes,
                                                  CreateBinFileAnalysis.BinUserConfig cfg) throws Exception {
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                "openQcSelections",
                DeferredImageSupplier.class,
                File.class,
                List.class,
                CreateBinFileAnalysis.BinUserConfig.class,
                Map.class);
        method.setAccessible(true);
        return (List<?>) method.invoke(
                analysis,
                supplier,
                lifFile,
                selectedSeriesIndexes,
                cfg,
                Collections.emptyMap());
    }

    private static void setContainerChoiceOverrideForTests(String name) throws Exception {
        Method method = ImageSourceDispatcher.class.getDeclaredMethod(
                "setContainerChoiceOverrideForTests", String.class);
        method.setAccessible(true);
        method.invoke(null, name);
    }

    private static void clearContainerChoiceCacheForTests() throws Exception {
        Method method = ImageSourceDispatcher.class.getDeclaredMethod(
                "clearContainerChoiceCacheForTests");
        method.setAccessible(true);
        method.invoke(null);
    }

    private static Object selectionField(Object selection, String fieldName) throws Exception {
        Field field = selection.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(selection);
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

    private static boolean invokeApplyCustomFilterEntryResult(
            CreateBinFileAnalysis analysis,
            File binFolder,
            CreateBinFileAnalysis.BinUserConfig cfg,
            int channelIndex,
            CustomFilterEntryDialog.Result result,
            boolean writeConfigOnDemote) throws Exception {
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                "applyCustomFilterEntryResult",
                File.class,
                CreateBinFileAnalysis.BinUserConfig.class,
                int.class,
                CustomFilterEntryDialog.Result.class,
                boolean.class);
        method.setAccessible(true);
        return ((Boolean) method.invoke(
                analysis,
                binFolder,
                cfg,
                Integer.valueOf(channelIndex),
                result,
                Boolean.valueOf(writeConfigOnDemote))).booleanValue();
    }

    private static boolean invokeHandleCancelRequest(CreateBinFileAnalysis analysis,
                                                     File binFolder,
                                                     CreateBinFileAnalysis.BinUserConfig cfg,
                                                     boolean[][] customSettings,
                                                     int step,
                                                     String label) throws Exception {
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                "handleCancelRequest",
                File.class,
                CreateBinFileAnalysis.BinUserConfig.class,
                boolean[][].class,
                int.class,
                String.class);
        method.setAccessible(true);
        return ((Boolean) method.invoke(
                analysis,
                binFolder,
                cfg,
                customSettings,
                Integer.valueOf(step),
                label)).booleanValue();
    }

    private static boolean invokeShouldExitAfterWizardCancel(CreateBinFileAnalysis analysis,
                                                            File binFolder,
                                                            CreateBinFileAnalysis.BinUserConfig cfg,
                                                            boolean[][] customSettings,
                                                            int step,
                                                            String label) throws Exception {
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                "shouldExitAfterWizardCancel",
                File.class,
                CreateBinFileAnalysis.BinUserConfig.class,
                boolean[][].class,
                int.class,
                String.class);
        method.setAccessible(true);
        return ((Boolean) method.invoke(
                analysis,
                binFolder,
                cfg,
                customSettings,
                Integer.valueOf(step),
                label)).booleanValue();
    }

    private static boolean invokeShouldShowWizardCancelMessage(CreateBinFileAnalysis analysis)
            throws Exception {
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                "shouldShowWizardCancelMessage");
        method.setAccessible(true);
        return ((Boolean) method.invoke(analysis)).booleanValue();
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

    private static String invokeFinalizeZSliceSelections(CreateBinFileAnalysis analysis,
                                                         CreateBinFileAnalysis.BinUserConfig cfg) throws Exception {
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                "finalizeZSliceSelections", CreateBinFileAnalysis.BinUserConfig.class);
        method.setAccessible(true);
        return (String) method.invoke(analysis, cfg);
    }

    private static String invokeMetadataOriginalName(File lifFile, SeriesMeta meta) throws Exception {
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                "metadataOriginalName", File.class, SeriesMeta.class);
        method.setAccessible(true);
        return (String) method.invoke(null, lifFile, meta);
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
        List<?> steps = invokeInteractiveQcSteps(analysis, cfg, customSettings);
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

    private static List<?> invokeInteractiveQcSteps(CreateBinFileAnalysis analysis,
                                                    CreateBinFileAnalysis.BinUserConfig cfg,
                                                    boolean[][] customSettings) throws Exception {
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                "buildInteractiveQcSteps", CreateBinFileAnalysis.BinUserConfig.class,
                boolean[][].class);
        method.setAccessible(true);
        return (List<?>) method.invoke(analysis, cfg, customSettings);
    }

    private static Object firstInteractiveQcStep(CreateBinFileAnalysis analysis,
                                                 CreateBinFileAnalysis.BinUserConfig cfg,
                                                 boolean[][] customSettings) throws Exception {
        List<?> steps = invokeInteractiveQcSteps(analysis, cfg, customSettings);
        assertFalse(steps.isEmpty());
        return steps.get(0);
    }

    private static void invokePersistInteractiveQcStep(CreateBinFileAnalysis analysis,
                                                       File binFolder,
                                                       CreateBinFileAnalysis.BinUserConfig cfg,
                                                       boolean[][] customSettings,
                                                       Object step) throws Exception {
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                "persistInteractiveQcStep",
                File.class,
                CreateBinFileAnalysis.BinUserConfig.class,
                boolean[][].class,
                step.getClass());
        method.setAccessible(true);
        method.invoke(analysis, binFolder, cfg, customSettings, step);
    }

    private static void invokeStageTestMethod(Object target,
                                              String methodName,
                                              Class<?>[] parameterTypes,
                                              Object[] args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        method.invoke(target, args);
    }

    private static ImagePlus invokeDuplicateCurrentChannel(CreateBinFileAnalysis analysis,
                                                          ConfigQcContext context,
                                                          int channelNum) throws Exception {
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                "duplicateCurrentChannel", ConfigQcContext.class, int.class);
        method.setAccessible(true);
        return (ImagePlus) method.invoke(analysis, context, Integer.valueOf(channelNum));
    }

    private static ChannelThresholdStage invokeCreateChannelThresholdStage(
            CreateBinFileAnalysis analysis,
            CreateBinFileAnalysis.BinUserConfig cfg,
            File binFolder,
            int channelIndex,
            boolean mirrorObjectThreshold) throws Exception {
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                "createChannelThresholdStage",
                CreateBinFileAnalysis.BinUserConfig.class,
                File.class,
                int.class,
                boolean.class);
        method.setAccessible(true);
        return (ChannelThresholdStage) method.invoke(
                analysis,
                cfg,
                binFolder,
                Integer.valueOf(channelIndex),
                Boolean.valueOf(mirrorObjectThreshold));
    }

    private static List<?> privateQcSelections(ImagePlus image) throws Exception {
        Class<?> type = Class.forName(
                "flash.pipeline.analyses.CreateBinFileAnalysis$QcImageSelection");
        Constructor<?> constructor = type.getDeclaredConstructor(int.class, String.class, ImagePlus.class);
        constructor.setAccessible(true);
        return Collections.singletonList(constructor.newInstance(
                Integer.valueOf(0), image == null ? "" : image.getTitle(), image));
    }

    private static List<?> privateQcSelectionsWithChannelOrders() throws Exception {
        Class<?> type = Class.forName(
                "flash.pipeline.analyses.CreateBinFileAnalysis$QcImageSelection");
        Constructor<?> constructor = type.getDeclaredConstructor(
                int.class, String.class, ImagePlus.class, String.class, String.class, Map.class);
        constructor.setAccessible(true);

        List<Object> selections = new ArrayList<Object>();
        selections.add(constructor.newInstance(
                Integer.valueOf(0), "Alpha", byteImage("Alpha"), "", "",
                orderMap(0, 2)));
        selections.add(constructor.newInstance(
                Integer.valueOf(1), "Beta", byteImage("Beta"), "", "",
                orderMap(1, 0)));
        selections.add(constructor.newInstance(
                Integer.valueOf(2), "Gamma", byteImage("Gamma"), "", "",
                orderMap(2, 1)));
        return selections;
    }

    private static List<?> privateQcSelectionsWithPartialChannelOrdersAndLabels() throws Exception {
        Class<?> type = Class.forName(
                "flash.pipeline.analyses.CreateBinFileAnalysis$QcImageSelection");
        Constructor<?> constructor = type.getDeclaredConstructor(
                int.class, String.class, ImagePlus.class, String.class, String.class,
                Map.class, Map.class);
        constructor.setAccessible(true);

        List<Object> selections = new ArrayList<Object>();
        selections.add(constructor.newInstance(
                Integer.valueOf(0), "Alpha", byteImage("Alpha"), "", "",
                singleOrderMap(1, 0), singleLabelMap(1, "C1 MAX")));
        selections.add(constructor.newInstance(
                Integer.valueOf(1), "Beta", byteImage("Beta"), "", "",
                singleOrderMap(2, 0), singleLabelMap(2, "C2 MAX")));
        selections.add(constructor.newInstance(
                Integer.valueOf(2), "Shared", byteImage("Shared"), "", "",
                orderMap(1, 1), dualLabelMap("C1 MIN", "C2 MIN")));
        return selections;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List rawQcSelections(List<?> selections) {
        return (List) selections;
    }

    private static List<?> capturedCustomFilterBuilderImages(FilterParameterStage stage) throws Exception {
        Field builderField = FilterParameterStage.class.getDeclaredField("customFilterBuilder");
        builderField.setAccessible(true);
        Object builder = builderField.get(stage);
        Field[] fields = builder.getClass().getDeclaredFields();
        for (int i = 0; i < fields.length; i++) {
            Field field = fields[i];
            if (!List.class.isAssignableFrom(field.getType())) {
                continue;
            }
            field.setAccessible(true);
            Object value = field.get(builder);
            if (!(value instanceof List<?>)) {
                continue;
            }
            List<?> list = (List<?>) value;
            if (!list.isEmpty()
                    && list.get(0) != null
                    && list.get(0).getClass().getName().contains("$QcImageSelection")) {
                return list;
            }
        }
        return Collections.emptyList();
    }

    private static Map<Integer, Integer> orderMap(int c1Order, int c2Order) {
        LinkedHashMap<Integer, Integer> order = new LinkedHashMap<Integer, Integer>();
        order.put(Integer.valueOf(1), Integer.valueOf(c1Order));
        order.put(Integer.valueOf(2), Integer.valueOf(c2Order));
        return order;
    }

    private static Map<Integer, Integer> singleOrderMap(int channelNumber, int orderValue) {
        LinkedHashMap<Integer, Integer> order = new LinkedHashMap<Integer, Integer>();
        order.put(Integer.valueOf(channelNumber), Integer.valueOf(orderValue));
        return order;
    }

    private static Map<Integer, String> singleLabelMap(int channelNumber, String label) {
        LinkedHashMap<Integer, String> labels = new LinkedHashMap<Integer, String>();
        labels.put(Integer.valueOf(channelNumber), label);
        return labels;
    }

    private static Map<Integer, String> dualLabelMap(String c1Label, String c2Label) {
        LinkedHashMap<Integer, String> labels = new LinkedHashMap<Integer, String>();
        labels.put(Integer.valueOf(1), c1Label);
        labels.put(Integer.valueOf(2), c2Label);
        return labels;
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

    private static PipelineDialog invokeSetupAnalysisDialog(String title) throws Exception {
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod("setupAnalysisDialog", String.class);
        method.setAccessible(true);
        return (PipelineDialog) method.invoke(null, title);
    }

    private static javax.swing.JPanel pipelineDialogPanel(PipelineDialog dialog, String fieldName) throws Exception {
        Field field = PipelineDialog.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (javax.swing.JPanel) field.get(dialog);
    }

    private static void disposePipelineDialog(PipelineDialog pipelineDialog) throws Exception {
        Field field = PipelineDialog.class.getDeclaredField("dialog");
        field.setAccessible(true);
        ((javax.swing.JDialog) field.get(pipelineDialog)).dispose();
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

    private static final class ResumeQualityCheckAnalysis extends CreateBinFileAnalysis {
        final List<Class<?>> firstStageByDialog = new ArrayList<Class<?>>();
        private final List<?> images;

        ResumeQualityCheckAnalysis(List<?> images) {
            this.images = images;
        }

        @Override
        protected int showResumePrompt(WizardResumeState draft) {
            return 0;
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        protected QcImageOpenResult openImagesForQC(String directory, File binFolder,
                                                    BinUserConfig cfg, boolean[][] customSettings) {
            return QcImageOpenResult.ready((List) images, "");
        }

        @Override
        protected boolean embeddedConfigQcUiAvailable() {
            return true;
        }

        @Override
        protected ConfigQcResult showEmbeddedConfigQcDialog(ConfigQcContext context,
                                                            List<ConfigQcStage> stages) {
            if (stages != null && !stages.isEmpty()) {
                firstStageByDialog.add(stages.get(0) == null ? null : stages.get(0).getClass());
            }
            return ConfigQcResult.DONE;
        }
    }

    private static final class StubDeferredImageSupplier extends DeferredImageSupplier {
        private final Map<Integer, ImagePlus> images;

        StubDeferredImageSupplier(Map<Integer, ImagePlus> images) {
            super(Collections.singletonList(new File("stub.tif")), "stub");
            this.images = images == null
                    ? Collections.<Integer, ImagePlus>emptyMap()
                    : images;
        }

        @Override
        public ImagePlus openSeries(int seriesIndex) {
            return images.get(Integer.valueOf(seriesIndex));
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

    private static class RecordingFilteredAnalysis extends CreateBinFileAnalysis {
        final List<String> visited = new ArrayList<String>();
        Set<BinField> qcFields = Collections.emptySet();

        @Override
        protected BinUserConfig collectBinConfigFromUser(String directory, File binFolder,
                                                         BinConfig existing, BinUserConfig draft) {
            visited.add("identity");
            BinUserConfig c = twoChannelConfig();
            c.names.clear();
            c.names.addAll(Arrays.asList("NeuN", "DAPI"));
            c.colors.clear();
            if (existing != null && existing.channelColors.size() >= 2) {
                c.colors.addAll(existing.channelColors.subList(0, 2));
            } else {
                c.colors.addAll(Arrays.asList("Grays", "Grays"));
            }
            return c;
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

    private static final class CancelChoiceAnalysis extends CreateBinFileAnalysis {
        private final CancelConfirmationDialog.Choice choice;

        CancelChoiceAnalysis(CancelConfirmationDialog.Choice choice) {
            this.choice = choice;
        }

        @Override
        protected CancelConfirmationDialog.Choice showCancelConfirmation(
                Window owner,
                String stepLabel,
                List<String> progressLines,
                String draftPath) {
            return choice;
        }
    }
}
