package flash.pipeline.analyses;

import flash.pipeline.TestConfigFiles;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinField;
import flash.pipeline.bin.BinSetupChooser;
import flash.pipeline.bin.BinSetupDispatcher;
import flash.pipeline.bin.ChannelConfig;
import flash.pipeline.bin.ChannelConfigIO;
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.DependencyService;
import flash.pipeline.runtime.DependencyStatus;
import flash.pipeline.runtime.FeatureDependencyGate;
import flash.pipeline.io.AsyncImageSaver;
import flash.pipeline.io.BoundedImageLoader;
import flash.pipeline.io.DeferredImageSupplier;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.naming.NameParts;
import flash.pipeline.presentation.PresentationTileConfig;
import flash.pipeline.presentation.PresentationTileRecord;
import flash.pipeline.presentation.PresentationTileWriter;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ColorProcessor;
import ij.process.ShortProcessor;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import javax.swing.JComboBox;
import java.io.File;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SplitAndMergeImageChannelsAnalysisTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @After
    public void resetHooks() throws Exception {
        AsyncImageSaver.waitForAll();
        invokeDispatcherReset();
        FeatureDependencyGate.configure(new DependencyService(), null);
        FeatureDependencyGate.setUiMode(false);
    }

    @Test
    public void channelSettingsGridColorsChannelTitlesFromConfiguredLuts() {
        SplitAndMergeImageChannelsAnalysis.ChannelSettingsGrid grid =
                SplitAndMergeImageChannelsAnalysis.buildChannelSettingsGrid(
                        new String[]{"DAPI", "GFAP", "Iba1"},
                        new String[]{"None", "None", "None"},
                        new String[]{"Blue", "Green", "Magenta"});

        assertEquals(SplitAndMergeImageChannelsAnalysis.channelHeaderColorForLut("Blue"),
                grid.channelLabels[0].getForeground());
        assertEquals(SplitAndMergeImageChannelsAnalysis.channelHeaderColorForLut("Green"),
                grid.channelLabels[1].getForeground());
        assertEquals(SplitAndMergeImageChannelsAnalysis.channelHeaderColorForLut("Magenta"),
                grid.channelLabels[2].getForeground());
    }

    @Test
    public void mergePseudoColorsToRgbHonoursPerChannelDisplayRange() throws Exception {
        // 16-bit channel with raw values 0..3000 but display range clamped to 0..1000.
        // The merge must rescale through (raw - min)/(max - min) — naively bit-shifting
        // by 8 was the bug: it produced ~12 of 255 (≈5% brightness) for a pixel that the
        // user wanted to see at full intensity.
        ShortProcessor red = new ShortProcessor(2, 1);
        red.set(0, 0);
        red.set(1, 3000);
        red.setMinAndMax(0, 1000);
        ImagePlus redImp = new ImagePlus("red", red);

        ShortProcessor blue = new ShortProcessor(2, 1);
        blue.set(0, 500);
        blue.set(1, 0);
        blue.setMinAndMax(0, 1000);
        ImagePlus blueImp = new ImagePlus("blue", blue);

        Method merge = SplitAndMergeImageChannelsAnalysis.class.getDeclaredMethod(
                "mergePseudoColorsToRgb", ImagePlus[].class, String[].class);
        merge.setAccessible(true);
        ImagePlus merged = (ImagePlus) merge.invoke(null,
                (Object) new ImagePlus[]{redImp, blueImp},
                (Object) new String[]{"Red", "Blue"});

        assertNotNull(merged);
        ColorProcessor cp = (ColorProcessor) merged.getProcessor();
        int pixel0 = cp.get(0);
        int pixel1 = cp.get(1);

        // Pixel 0: red raw=0 (0%), blue raw=500 (50% of 0..1000 range) → (R=0, G=0, B≈128).
        assertEquals("red @ pixel0", 0, (pixel0 >> 16) & 0xff);
        assertEquals("green @ pixel0", 0, (pixel0 >> 8) & 0xff);
        assertTrue("blue @ pixel0 should be ~128 (was " + (pixel0 & 0xff) + ")",
                Math.abs((pixel0 & 0xff) - 128) <= 2);

        // Pixel 1: red raw=3000 clamps above the 1000 max → R=255. Blue=0 → B=0.
        assertEquals("red @ pixel1", 255, (pixel1 >> 16) & 0xff);
        assertEquals("green @ pixel1", 0, (pixel1 >> 8) & 0xff);
        assertEquals("blue @ pixel1", 0, pixel1 & 0xff);
    }

    @Test
    public void declaresSplitMergeBinRequirementsWithoutRoiBenefit() {
        SplitAndMergeImageChannelsAnalysis analysis = new SplitAndMergeImageChannelsAnalysis();

        assertEquals(EnumSet.of(
                BinField.CHANNEL_NAMES,
                BinField.CHANNEL_COLORS,
                BinField.DISPLAY_MIN_MAX,
                BinField.Z_SLICE),
                analysis.requiredBinFields());
        assertFalse(analysis.benefitsFromRois());
        assertTrue("Split/merge must open its setup dialog during GUI runs",
                analysis.requiresHeadedMode());
    }

    @Test
    public void manualProcessingOverridesHideWindowsOnlyForInteractiveGuiRuns() {
        assertTrue(SplitAndMergeImageChannelsAnalysis.hasManualProcessing(
                new String[]{"None", "Manual"}));
        assertFalse(SplitAndMergeImageChannelsAnalysis.hasManualProcessing(
                new String[]{"None", "Automatic"}));

        assertTrue(SplitAndMergeImageChannelsAnalysis.shouldOverrideHideWindowsForManualProcessing(
                true, true, null, false));
        assertFalse(SplitAndMergeImageChannelsAnalysis.shouldOverrideHideWindowsForManualProcessing(
                true, true, new flash.pipeline.cli.CLIConfig(), false));
        assertFalse(SplitAndMergeImageChannelsAnalysis.shouldOverrideHideWindowsForManualProcessing(
                true, true, null, true));
        assertFalse(SplitAndMergeImageChannelsAnalysis.shouldOverrideHideWindowsForManualProcessing(
                false, true, null, false));
    }

    @Test
    public void manualAdjustmentRunsWhenWindowsAreVisibleAndWorkerIsSequential() {
        assertTrue(SplitAndMergeImageChannelsAnalysis.canRunManualAdjustment(false, false));
        assertFalse(SplitAndMergeImageChannelsAnalysis.canRunManualAdjustment(true, false));
        assertFalse(SplitAndMergeImageChannelsAnalysis.canRunManualAdjustment(false, true));
    }

    @Test
    public void channelSettingsGridUsesSettingRowsWithChannelColumnsAndHelperText() {
        SplitAndMergeImageChannelsAnalysis.ChannelSettingsGrid grid =
                SplitAndMergeImageChannelsAnalysis.buildChannelSettingsGrid(
                        new String[]{"DAPI", "GFAP"},
                        new String[]{"None", "12-345"});

        assertEquals("Processing Method", grid.rowLabels[0].getText());
        assertEquals("Display Ranges", grid.rowLabels[1].getText());
        assertEquals("Saturation", grid.rowLabels[2].getText());

        assertEquals(2, grid.methodBoxes.length);
        assertEquals("Automatic", grid.methodBoxes[0].getSelectedItem());
        assertEquals("Custom Min-Max Display Ranges", grid.methodBoxes[1].getSelectedItem());
        assertArrayEquals(new String[]{"None", "Automatic", "Manual", "Custom Min-Max Display Ranges"},
                comboItems(grid.methodBoxes[0]));

        assertEquals("", grid.displayRangeFields[0].getText());
        assertEquals("12-345", grid.displayRangeFields[1].getText());
        assertTrue(grid.saturationFields[0].isEnabled());
        assertFalse(grid.displayRangeFields[0].isEnabled());
        assertFalse(grid.saturationFields[1].isEnabled());
        assertTrue(grid.displayRangeFields[1].isEnabled());

        Color helperGrey = new Color(117, 117, 117);
        for (int row = 0; row < grid.helperLabels.length; row++) {
            for (int ch = 0; ch < grid.helperLabels[row].length; ch++) {
                assertNotNull(grid.helperLabels[row][ch]);
                assertEquals(helperGrey, grid.helperLabels[row][ch].getForeground());
                assertFalse(grid.helperLabels[row][ch].getText().trim().isEmpty());
            }
        }
    }

    @Test
    public void channelSettingsGridReadsDirectSwingValuesWithExistingFallbacks() {
        SplitAndMergeImageChannelsAnalysis.ChannelSettingsGrid grid =
                SplitAndMergeImageChannelsAnalysis.buildChannelSettingsGrid(
                        new String[]{"DAPI", "GFAP"},
                        new String[]{"None", "10-200"});

        grid.methodBoxes[0].setSelectedItem("Custom Min-Max Display Ranges");
        grid.displayRangeFields[0].setText(" 25-250 ");
        grid.saturationFields[0].setText("not-a-number");

        grid.methodBoxes[1].setSelectedItem("Automatic");
        grid.displayRangeFields[1].setText("   ");
        grid.saturationFields[1].setText("0.5");

        SplitAndMergeImageChannelsAnalysis.ChannelSettingsSelections selections =
                SplitAndMergeImageChannelsAnalysis.readChannelSettingsGrid(grid);

        assertArrayEquals(new String[]{"Custom Min-Max Display Ranges", "Automatic"},
                selections.processMethodPerCh);
        assertArrayEquals(new String[]{"25-250", "None"}, selections.customMinMaxPerCh);
        assertEquals(0.0, selections.saturationsPerCh[0], 0.0);
        assertEquals(0.5, selections.saturationsPerCh[1], 0.0);
        assertTrue(grid.displayRangeFields[0].isEnabled());
        assertFalse(grid.saturationFields[0].isEnabled());
        assertFalse(grid.displayRangeFields[1].isEnabled());
        assertTrue(grid.saturationFields[1].isEnabled());
    }

    @Test
    public void executeReturnsGracefullyWhenDispatcherCancelsMissingBin() throws Exception {
        installAllDependenciesPresentForGate();
        File dir = temp.newFolder("cancelled");
        AtomicInteger chooserCalls = new AtomicInteger(0);
        installDispatcherChoice(BinSetupChooser.Choice.CANCELLED, chooserCalls);

        new SplitAndMergeImageChannelsAnalysis().execute(dir.getAbsolutePath());

        assertEquals(1, chooserCalls.get());
        assertFalse(new File(dir, "Images").exists());
        assertFalse(new File(new File(new File(dir, "FLASH"), "Results"),
                "Presentation Images").exists());
    }

    @Test
    public void completeRequiredBinCompletesWithoutChooser() throws Exception {
        File dir = temp.newFolder("complete");
        BinConfig cfg = TestConfigFiles.basicBinConfig("DAPI", "GFAP");
        cfg.channelColors.clear();
        cfg.channelColors.add("Blue");
        cfg.channelColors.add("Green");
        cfg.channelMinMax.clear();
        cfg.channelMinMax.add("None");
        cfg.channelMinMax.add("0-4095");
        TestConfigFiles.writeChannelConfig(dir, cfg);
        AtomicInteger chooserCalls = new AtomicInteger(0);
        installDispatcherChoice(BinSetupChooser.Choice.CANCELLED, chooserCalls);

        SplitAndMergeImageChannelsAnalysis analysis = new SplitAndMergeImageChannelsAnalysis();
        BinSetupDispatcher.Outcome outcome = BinSetupDispatcher.ensure(
                dir.getAbsolutePath(), "Split & Merge Image Channels",
                analysis.requiredBinFields(), analysis.benefitsFromRois());

        assertEquals(BinSetupDispatcher.Outcome.COMPLETED, outcome);
        assertEquals(0, chooserCalls.get());
    }

    @Test
    public void splitMergeMinMaxWritebackUpdatesChannelConfigJson() throws Exception {
        File dir = temp.newFolder("partialOrigin");
        TestConfigFiles.writeChannelConfig(dir, TestConfigFiles.basicBinConfig("DAPI", "GFAP"));

        invokeUpdateBinMinMax(new SplitAndMergeImageChannelsAnalysis(), dir,
                new String[]{"Custom Min-Max Display Ranges", "None"},
                new String[]{"10-200", "50-4000"},
                2);

        BinConfig updated = BinConfigIO.readPartialFromDirectory(dir.getAbsolutePath());
        assertEquals(Arrays.asList("10-200", "None"), updated.channelMinMax);
    }

    @Test
    public void minMaxWritebackCompletesPendingDisplayRangeInChannelConfig() throws Exception {
        File dir = temp.newFolder("shortPartial");
        writePartialNamesAndColorsConfig(dir, "DAPI", "GFAP");

        BinConfigIO.updateMinMax(dir.getAbsolutePath(), new String[]{"10-200", "50-4000"});

        BinConfig updated = BinConfigIO.readPartialFromDirectory(dir.getAbsolutePath());
        assertEquals(Arrays.asList("10-200", "50-4000"), updated.channelMinMax);
    }

    @Test
    public void splitMergeOutputHelpersResolveToPresentationImagesLayout() throws Exception {
        File dir = temp.newFolder("layout");
        File presentationRoot = new File(new File(new File(dir, "FLASH"), "Results"), "Presentation Images");

        assertEquals(new File(presentationRoot, "Images"),
                SplitAndMergeImageChannelsAnalysis.splitMergeImageWriteRoot(dir.getAbsolutePath()));
        assertEquals(new File(presentationRoot, "OME-TIFF"),
                SplitAndMergeImageChannelsAnalysis.splitMergeOmeTiffWriteRoot(dir.getAbsolutePath()));
        assertEquals(
                new File(new File(new File(new File(new File(dir, "FLASH"), "Results"),
                        "Run Records"), "analysis_details"), "Split and Merge"),
                SplitAndMergeImageChannelsAnalysis.splitMergeAnalysisDetailsRoot(dir.getAbsolutePath()));
    }

    @Test
    public void splitMergePrimaryOutputCheckUsesPresentationImagesDir() throws Exception {
        File dir = temp.newFolder("primaryCheck");
        NameParts parts = new NameParts("Experiment", "Animal1", "LH", "Cortex");
        File primaryOutRoot = SplitAndMergeImageChannelsAnalysis.splitMergeImageWriteRoot(dir.getAbsolutePath());

        File presentationAnimalDir = new File(primaryOutRoot, "Animal1");
        assertTrue(presentationAnimalDir.mkdirs());
        assertTrue(new File(presentationAnimalDir, "DAPI_LH_Cortex.png").createNewFile());
        assertTrue(SplitAndMergeImageChannelsAnalysis.splitMergePrimaryChannelOutputExists(
                dir.getAbsolutePath(), primaryOutRoot, parts, "DAPI"));
        assertFalse(SplitAndMergeImageChannelsAnalysis.splitMergePrimaryChannelOutputExists(
                dir.getAbsolutePath(), primaryOutRoot, parts, "GFAP"));
    }

    @Test
    public void processOneImageWritesOmeTiffWhenMergePngIsDisabled() throws Exception {
        File dir = temp.newFolder("omeWithoutMerge");
        File outDir = new File(dir, "Images/Animal1");
        File tifDir = new File(dir, "OME-TIFF");
        File detailsDir = new File(dir, "Analysis Details");
        assertTrue(outDir.mkdirs());
        assertTrue(detailsDir.mkdirs());

        ImageStack stack = new ImageStack(2, 2);
        stack.addSlice("DAPI", new byte[]{0, 64, 127, (byte) 255});
        stack.addSlice("GFAP", new byte[]{5, 25, 125, (byte) 200});
        ImagePlus imp = new ImagePlus("Experiment-Animal1_LH_Cortex", stack);
        imp.setDimensions(2, 1, 1);
        imp.setOpenAsHyperStack(true);

        invokeProcessOneImage(new SplitAndMergeImageChannelsAnalysis(),
                imp,
                new String[]{"DAPI", "GFAP"},
                new String[]{"Blue", "Green"},
                outDir,
                tifDir,
                detailsDir,
                false,
                true,
                new NameParts("Experiment", "Animal1", "LH", "Cortex"));

        File omeFile = new File(tifDir, "Animal1_LH_Cortex.ome.tif");
        assertTrue("OME-TIFF should be written when saveOmeTiff is true", omeFile.isFile());
        assertTrue("OME-TIFF should not be empty", omeFile.length() > 0L);
    }

    @Test
    public void processOneImageWritesBackgroundSubtractedPngOutputs() throws Exception {
        File dir = temp.newFolder("backgroundPngs");
        File outDir = new File(dir, "Images/Animal1");
        File tifDir = new File(dir, "OME-TIFF");
        File detailsDir = new File(dir, "Analysis Details");
        assertTrue(outDir.mkdirs());

        ImageStack stack = new ImageStack(2, 2);
        stack.addSlice("DAPI", new byte[]{10, 20, 30, 40});
        stack.addSlice("GFAP", new byte[]{40, 50, 60, 70});
        ImagePlus imp = new ImagePlus("Experiment-Animal1_LH_Cortex", stack);
        imp.setDimensions(2, 1, 1);
        imp.setOpenAsHyperStack(true);

        invokeProcessOneImage(new SplitAndMergeImageChannelsAnalysis(),
                imp,
                new String[]{"DAPI", "GFAP"},
                new String[]{"Blue", "Green"},
                outDir,
                tifDir,
                detailsDir,
                true,
                false,
                true,
                0,
                new boolean[]{false, true},
                new NameParts("Experiment", "Animal1", "LH", "Cortex"));

        AsyncImageSaver.waitForAll();
        assertFileWritten(new File(outDir, "DAPI_LH_Cortex.png"));
        assertFileWritten(new File(outDir, "DAPI_Raw_LH_Cortex.png"));
        assertFileWritten(new File(outDir, "GFAP_LH_Cortex.png"));
        assertFileWritten(new File(outDir, "Merge_LH_Cortex.png"));
        assertFileWritten(new File(detailsDir, "DAPI_LH_Cortex_details.txt"));
    }

    @Test
    public void processOneImageDefaultsMissingChannelColorsToGrays() throws Exception {
        File dir = temp.newFolder("missingColors");
        File outDir = new File(dir, "Images/Animal1");
        File tifDir = new File(dir, "OME-TIFF");
        File detailsDir = new File(dir, "Analysis Details");
        assertTrue(outDir.mkdirs());

        ImageStack stack = new ImageStack(2, 2);
        stack.addSlice("DAPI", new byte[]{10, 20, 30, 40});
        stack.addSlice("GFAP", new byte[]{40, 50, 60, 70});
        ImagePlus imp = new ImagePlus("Experiment-Animal1_LH_Cortex", stack);
        imp.setDimensions(2, 1, 1);
        imp.setOpenAsHyperStack(true);

        invokeProcessOneImage(new SplitAndMergeImageChannelsAnalysis(),
                imp,
                new String[]{"DAPI", "GFAP"},
                new String[]{"Blue"},
                outDir,
                tifDir,
                detailsDir,
                true,
                false,
                new NameParts("Experiment", "Animal1", "LH", "Cortex"));

        AsyncImageSaver.waitForAll();
        assertFileWritten(new File(outDir, "DAPI_LH_Cortex.png"));
        assertFileWritten(new File(outDir, "GFAP_LH_Cortex.png"));
        assertFileWritten(new File(outDir, "Merge_LH_Cortex.png"));
        String details = new String(Files.readAllBytes(
                new File(detailsDir, "DAPI_LH_Cortex_details.txt").toPath()), StandardCharsets.UTF_8);
        assertTrue(details.contains("C2 GFAP"));
        assertTrue(details.contains("Grays"));
    }

    @Test
    public void parallelProcessingCanSkipOrientationTransformsForPresentationImages() throws Exception {
        File dir = temp.newFolder("skipOrientationTransforms");

        BufferedImage applied = runOneChannelParallelPresentationExport(
                dir, "applied", true);
        BufferedImage skipped = runOneChannelParallelPresentationExport(
                dir, "skipped", false);

        assertEquals(2, applied.getWidth());
        assertEquals(4, applied.getHeight());
        assertEquals(4, skipped.getWidth());
        assertEquals(2, skipped.getHeight());
    }

    @Test
    public void presentationManifestMergeKeepsDuplicateLabelsWhenImageIdsDiffer() throws Exception {
        File presentationRoot = temp.newFolder("presentationMerge");
        File images = new File(presentationRoot, "Images/Animal1");
        assertTrue(images.mkdirs());

        File existingImage = new File(images, "DAPI_existing.png");
        File currentImage = new File(images, "DAPI_current.png");
        assertTrue(existingImage.createNewFile());
        assertTrue(currentImage.createNewFile());

        File manifest = new File(presentationRoot, "Presentation_Image_Manifest.csv");
        PresentationTileRecord existing = new PresentationTileRecord(
                existingImage, "Animal1", "LH", "Cortex", "source-series-001",
                "DAPI", "DAPI", 0, 40, 40, 1.0, 1.0);
        PresentationTileWriter.writeManifest(manifest,
                Collections.singletonList(existing),
                Collections.<String, String>emptyMap());

        PresentationTileRecord current = new PresentationTileRecord(
                currentImage, "Animal1", "LH", "Cortex", "source-series-002",
                "DAPI", "DAPI", 0, 40, 40, 1.0, 1.0);

        List<PresentationTileRecord> merged = invokeMergeExistingPresentationManifest(
                manifest, Collections.singletonList(current));

        assertEquals(2, merged.size());
        assertEquals(Arrays.asList("source-series-001", "source-series-002"),
                Arrays.asList(merged.get(0).imageId(), merged.get(1).imageId()));
    }

    @Test
    public void disabledTileOptionsStillWritePresentationManifest() throws Exception {
        File dir = temp.newFolder("manifestWithoutTiles");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(dir.getAbsolutePath());
        File outDir = new File(layout.presentationImagesDir(), "Animal1");
        File tifDir = layout.presentationOmeTiffDir();
        File detailsDir = new File(layout.analysisDetailsWriteDir(), "Split and Merge/Animal1");
        assertTrue(outDir.mkdirs());
        assertTrue(detailsDir.mkdirs());

        ImageStack stack = new ImageStack(2, 2);
        stack.addSlice("DAPI", new byte[]{0, 64, 127, (byte) 255});
        stack.addSlice("GFAP", new byte[]{5, 25, 125, (byte) 200});
        ImagePlus imp = new ImagePlus("Experiment-Animal1_LH_Cortex", stack);
        imp.setDimensions(2, 1, 1);
        imp.setOpenAsHyperStack(true);

        SplitAndMergeImageChannelsAnalysis analysis = new SplitAndMergeImageChannelsAnalysis();
        invokeProcessOneImage(analysis,
                imp,
                new String[]{"DAPI", "GFAP"},
                new String[]{"Blue", "Green"},
                outDir,
                tifDir,
                detailsDir,
                true,
                false,
                new NameParts("Experiment", "Animal1", "LH", "Cortex"));

        AsyncImageSaver.waitForAll();
        invokeWritePresentationTileOutputs(analysis, dir.getAbsolutePath(), layout,
                PresentationTileConfig.disabled(Arrays.asList("DAPI", "GFAP")));

        File manifest = new File(layout.presentationImagesRoot(), "Presentation_Image_Manifest.csv");
        assertTrue(manifest.isFile());
        List<PresentationTileRecord> records = PresentationTileWriter.readManifest(manifest);
        assertEquals(3, records.size());
        assertEquals("source-series-test", records.get(0).imageId());
    }

    @Test
    public void parallelProcessingRethrowsWorkerFailuresAfterWorkersFinish() throws Exception {
        File dir = temp.newFolder("parallelFailure");
        File outRoot = new File(dir, "Images");
        File tifDir = new File(dir, "OME-TIFF");
        File detailsRoot = new File(dir, "Analysis Details");
        assertTrue(outRoot.createNewFile());

        ImageStack stack = new ImageStack(2, 2);
        stack.addSlice("DAPI", new byte[]{1, 2, 3, 4});
        ImagePlus imp = new ImagePlus("Experiment-Animal1_LH_Cortex", stack);
        imp.setDimensions(1, 1, 1);
        imp.setOpenAsHyperStack(true);

        BoundedImageLoader loader = new BoundedImageLoader(
                new SyntheticDeferredImageSupplier(imp),
                Collections.singletonList(Integer.valueOf(0)),
                1);
        loader.start();

        SplitAndMergeImageChannelsAnalysis analysis = new SplitAndMergeImageChannelsAnalysis();
        Object dialogResult = newMainDialogResult(1);

        try {
            invokeProcessImagesParallel(analysis, loader, dir, null,
                    new String[]{"DAPI"}, new String[]{"Blue"},
                    outRoot, tifDir, detailsRoot, dialogResult, 1);
            fail("Expected split/merge worker failure to be rethrown");
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            assertTrue(cause instanceof RuntimeException);
            assertTrue(cause.getMessage(), cause.getMessage().contains("Split/Merge failed for 1 image(s)"));
            assertEquals(1, cause.getSuppressed().length);
            assertTrue(cause.getSuppressed()[0] instanceof java.io.IOException);
        }
    }

    @Test
    public void saveSaturationsWritesToActiveConfigurationFolder() throws Exception {
        File dir = temp.newFolder("saturations");

        invokeSaveSaturations(new SplitAndMergeImageChannelsAnalysis(), dir,
                new String[]{"DAPI", "GFAP"},
                new String[]{"Automatic", "None"},
                new double[]{0.35, 0.5});

        File saturationFile = new File(
                new File(new File(new File(dir, "FLASH"), "Config"), ".settings"), "Saturations.txt");
        List<String> lines = Files.readAllLines(saturationFile.toPath(), StandardCharsets.UTF_8);
        assertEquals("DAPI 0.35", lines.get(0));
        assertEquals("GFAP N/A", lines.get(1));
        assertFalse(new File(new File(dir, ".bin"), "Saturations.txt").exists());
    }

    private static void invokeUpdateBinMinMax(SplitAndMergeImageChannelsAnalysis analysis,
                                              File dir,
                                              String[] processMethodPerCh,
                                              String[] customMinMaxPerCh,
                                              int channelCount) throws Exception {
        Method method = SplitAndMergeImageChannelsAnalysis.class.getDeclaredMethod(
                "updateBinMinMax", String.class, String[].class, String[].class, int.class);
        method.setAccessible(true);
        method.invoke(analysis, dir.getAbsolutePath(), processMethodPerCh, customMinMaxPerCh, channelCount);
    }

    private static void invokeSaveSaturations(SplitAndMergeImageChannelsAnalysis analysis,
                                              File dir,
                                              String[] channelNames,
                                              String[] processMethodPerCh,
                                              double[] saturationsPerCh) throws Exception {
        Method method = SplitAndMergeImageChannelsAnalysis.class.getDeclaredMethod(
                "saveSaturations", String.class, String[].class, String[].class, double[].class);
        method.setAccessible(true);
        method.invoke(analysis, dir.getAbsolutePath(), channelNames, processMethodPerCh, saturationsPerCh);
    }

    private static void invokeProcessImagesParallel(SplitAndMergeImageChannelsAnalysis analysis,
                                                    BoundedImageLoader loader,
                                                    File dir,
                                                    BinConfig cfg,
                                                    String[] channelNames,
                                                    String[] channelColors,
                                                    File outRoot,
                                                    File tifDir,
                                                    File detailsRoot,
                                                    Object dialogResult,
                                                    int nThreads) throws Exception {
        Method method = SplitAndMergeImageChannelsAnalysis.class.getDeclaredMethod(
                "processImagesParallel",
                BoundedImageLoader.class,
                String.class,
                BinConfig.class,
                String[].class,
                String[].class,
                File.class,
                File.class,
                File.class,
                dialogResult.getClass(),
                int.class,
                int.class);
        method.setAccessible(true);
        method.invoke(analysis, loader, dir.getAbsolutePath(), cfg, channelNames, channelColors,
                outRoot, tifDir, detailsRoot, dialogResult, Integer.valueOf(nThreads), Integer.valueOf(1));
    }

    private static Object newMainDialogResult(int channelCount) throws Exception {
        Class<?> type = Class.forName(
                "flash.pipeline.analyses.SplitAndMergeImageChannelsAnalysis$MainDialogResult");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object result = constructor.newInstance();
        setField(result, "processMethodPerCh", fill(channelCount, "None"));
        setField(result, "customMinMaxPerCh", fill(channelCount, "None"));
        setField(result, "saturationsPerCh", fill(channelCount, 0.35));
        setField(result, "createMerge", Boolean.FALSE);
        setField(result, "saveOmeTiff", Boolean.FALSE);
        setField(result, "additionalMergeSpec", "");
        setField(result, "subtractBackground", Boolean.FALSE);
        setField(result, "backgroundIndex", Integer.valueOf(-1));
        setField(result, "subtractFromChannels", new boolean[channelCount]);
        setField(result, "applyOrientationTransforms", Boolean.TRUE);
        return result;
    }

    private BufferedImage runOneChannelParallelPresentationExport(File project,
                                                                  String outputName,
                                                                  boolean applyOrientation)
            throws Exception {
        File outRoot = new File(project, outputName + "/Images");
        File tifDir = new File(project, outputName + "/OME-TIFF");
        File detailsRoot = new File(project, outputName + "/Details");
        Object dialogResult = newMainDialogResult(1);
        setField(dialogResult, "applyOrientationTransforms", Boolean.valueOf(applyOrientation));

        ImagePlus imp = oneChannelImage("Experiment-Animal1_LH_Cortex", 4, 2);
        BoundedImageLoader loader = new BoundedImageLoader(
                new SyntheticDeferredImageSupplier(imp),
                Collections.singletonList(Integer.valueOf(0)),
                1);
        loader.start();

        invokeProcessImagesParallel(new SplitAndMergeImageChannelsAnalysis(),
                loader,
                project,
                null,
                new String[]{"DAPI"},
                new String[]{"Blue"},
                outRoot,
                tifDir,
                detailsRoot,
                dialogResult,
                1);
        AsyncImageSaver.waitForAll();
        File output = new File(new File(outRoot, "Animal1"), "DAPI_LH_Cortex.png");
        assertFileWritten(output);
        return ImageIO.read(output);
    }

    private static ImagePlus oneChannelImage(String title, int width, int height) {
        byte[] pixels = new byte[width * height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (byte) (i + 1);
        }
        ImageStack stack = new ImageStack(width, height);
        stack.addSlice("DAPI", pixels);
        ImagePlus image = new ImagePlus(title, stack);
        image.setDimensions(1, 1, 1);
        image.setOpenAsHyperStack(true);
        return image;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void invokeProcessOneImage(SplitAndMergeImageChannelsAnalysis analysis,
                                              ImagePlus imp,
                                              String[] channelNames,
                                              String[] channelColors,
                                              File outDir,
                                              File tifDir,
                                              File detailsDir,
                                              boolean createMerge,
                                              boolean saveOmeTiff,
                                              NameParts parts) throws Exception {
        Method method = SplitAndMergeImageChannelsAnalysis.class.getDeclaredMethod(
                "processOneImage",
                ImagePlus.class,
                String[].class,
                String[].class,
                File.class,
                File.class,
                File.class,
                boolean.class,
                boolean.class,
                boolean.class,
                int.class,
                boolean[].class,
                String.class,
                String[].class,
                String[].class,
                double[].class,
                NameParts.class,
                String.class);
        method.setAccessible(true);
        method.invoke(analysis, imp, channelNames, channelColors, outDir, tifDir, detailsDir,
                createMerge, saveOmeTiff, false, -1, new boolean[channelNames.length], "",
                fill(channelNames.length, "None"), fill(channelNames.length, "None"),
                fill(channelNames.length, 0.35), parts, "source-series-test");
    }

    private static void invokeProcessOneImage(SplitAndMergeImageChannelsAnalysis analysis,
                                              ImagePlus imp,
                                              String[] channelNames,
                                              String[] channelColors,
                                              File outDir,
                                              File tifDir,
                                              File detailsDir,
                                              boolean createMerge,
                                              boolean saveOmeTiff,
                                              boolean subtractBackground,
                                              int backgroundIndex,
                                              boolean[] subtractFromChannels,
                                              NameParts parts) throws Exception {
        Method method = SplitAndMergeImageChannelsAnalysis.class.getDeclaredMethod(
                "processOneImage",
                ImagePlus.class,
                String[].class,
                String[].class,
                File.class,
                File.class,
                File.class,
                boolean.class,
                boolean.class,
                boolean.class,
                int.class,
                boolean[].class,
                String.class,
                String[].class,
                String[].class,
                double[].class,
                NameParts.class,
                String.class);
        method.setAccessible(true);
        method.invoke(analysis, imp, channelNames, channelColors, outDir, tifDir, detailsDir,
                createMerge, saveOmeTiff, subtractBackground, backgroundIndex, subtractFromChannels, "",
                fill(channelNames.length, "None"), fill(channelNames.length, "None"),
                fill(channelNames.length, 0.35), parts, "source-series-test");
    }

    @SuppressWarnings("unchecked")
    private static List<PresentationTileRecord> invokeMergeExistingPresentationManifest(
            File manifest,
            List<PresentationTileRecord> currentRecords) throws Exception {
        Method method = SplitAndMergeImageChannelsAnalysis.class.getDeclaredMethod(
                "mergeExistingPresentationManifest", File.class, List.class);
        method.setAccessible(true);
        return (List<PresentationTileRecord>) method.invoke(null, manifest, currentRecords);
    }

    private static void invokeWritePresentationTileOutputs(
            SplitAndMergeImageChannelsAnalysis analysis,
            String directory,
            FlashProjectLayout layout,
            PresentationTileConfig config) throws Exception {
        Method method = SplitAndMergeImageChannelsAnalysis.class.getDeclaredMethod(
                "writePresentationTileOutputs",
                String.class,
                FlashProjectLayout.class,
                PresentationTileConfig.class);
        method.setAccessible(true);
        method.invoke(analysis, directory, layout, config);
    }

    private static final class SyntheticDeferredImageSupplier extends DeferredImageSupplier {
        private final ImagePlus image;

        private SyntheticDeferredImageSupplier(ImagePlus image) {
            super(Collections.singletonList(new File("synthetic.tif")), "Synthetic");
            this.image = image;
        }

        @Override
        public ImagePlus openSeriesMaterialized(int seriesIndex) {
            return image;
        }
    }

    private static void assertFileWritten(File file) {
        assertTrue(file.getAbsolutePath(), file.isFile());
        assertTrue(file.getAbsolutePath(), file.length() > 0L);
    }

    private static String[] fill(int length, String value) {
        String[] out = new String[length];
        for (int i = 0; i < length; i++) {
            out[i] = value;
        }
        return out;
    }

    private static double[] fill(int length, double value) {
        double[] out = new double[length];
        for (int i = 0; i < length; i++) {
            out[i] = value;
        }
        return out;
    }

    private static String[] comboItems(JComboBox<String> combo) {
        String[] items = new String[combo.getItemCount()];
        for (int i = 0; i < combo.getItemCount(); i++) {
            items[i] = combo.getItemAt(i);
        }
        return items;
    }

    private static void installDispatcherChoice(final BinSetupChooser.Choice choice,
                                                final AtomicInteger chooserCalls) throws Exception {
        setDispatcherHook("setHeadlessProbeForTest",
                "flash.pipeline.bin.BinSetupDispatcher$HeadlessProbe",
                new InvocationResult() {
                    @Override public Object invoke(Method method, Object[] args) {
                        return Boolean.FALSE;
                    }
                });
        setDispatcherHook("setChooserForTest",
                "flash.pipeline.bin.BinSetupDispatcher$Chooser",
                new InvocationResult() {
                    @Override public Object invoke(Method method, Object[] args) {
                        chooserCalls.incrementAndGet();
                        return choice;
                    }
                });
    }

    private static void setDispatcherHook(String setterName, String interfaceName,
                                          final InvocationResult result) throws Exception {
        Class<?> hookType = Class.forName(interfaceName);
        Object proxy = Proxy.newProxyInstance(
                hookType.getClassLoader(),
                new Class<?>[]{hookType},
                (proxyObject, method, args) -> result.invoke(method, args));
        Method setter = BinSetupDispatcher.class.getDeclaredMethod(setterName, hookType);
        setter.setAccessible(true);
        setter.invoke(null, proxy);
    }

    private static void invokeDispatcherReset() throws Exception {
        Method reset = BinSetupDispatcher.class.getDeclaredMethod("resetForTest");
        reset.setAccessible(true);
        reset.invoke(null);
    }

    private static void installAllDependenciesPresentForGate() throws Exception {
        final EnumMap<DependencyId, DependencyStatus> statuses =
                new EnumMap<DependencyId, DependencyStatus>(DependencyId.class);
        for (DependencyId id : DependencyId.values()) {
            statuses.put(id, DependencyStatus.present(id.name() + " present"));
        }

        Class<?> providerType = Class.forName(
                "flash.pipeline.runtime.DependencyService$StatusSnapshotProvider");
        Object provider = Proxy.newProxyInstance(
                providerType.getClassLoader(),
                new Class<?>[]{providerType},
                (proxyObject, method, args) -> {
                    if ("snapshot".equals(method.getName())) {
                        return new EnumMap<DependencyId, DependencyStatus>(statuses);
                    }
                    if ("toString".equals(method.getName())) {
                        return "all-present dependency status provider";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return Integer.valueOf(System.identityHashCode(proxyObject));
                    }
                    if ("equals".equals(method.getName())) {
                        return Boolean.valueOf(proxyObject == args[0]);
                    }
                    return null;
                });
        Constructor<DependencyService> ctor = DependencyService.class.getDeclaredConstructor(providerType);
        ctor.setAccessible(true);
        FeatureDependencyGate.configure(ctor.newInstance(provider), null);
        FeatureDependencyGate.setUiMode(false);
    }

    private static void writePartialNamesAndColorsConfig(File dir, String... names) throws Exception {
        ChannelConfig cfg = new ChannelConfig();
        for (int i = 0; i < names.length; i++) {
            ChannelConfig.Channel channel = new ChannelConfig.Channel();
            channel.index = i;
            channel.name = names[i];
            channel.color = i == 0 ? "Blue" : "Green";
            channel.status.put(ChannelConfig.P_NAME, ChannelConfig.PropertyStatus.COMMITTED);
            channel.status.put(ChannelConfig.P_COLOR, ChannelConfig.PropertyStatus.COMMITTED);
            cfg.channels.add(channel);
        }
        ChannelConfigIO.write(TestConfigFiles.settingsDir(dir), cfg);
    }

    private interface InvocationResult {
        Object invoke(Method method, Object[] args);
    }
}
