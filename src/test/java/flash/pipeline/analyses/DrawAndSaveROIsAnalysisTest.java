package flash.pipeline.analyses;

import flash.pipeline.TestConfigFiles;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinField;
import flash.pipeline.bin.BinSetupChooser;
import flash.pipeline.bin.BinSetupDispatcher;
import flash.pipeline.io.OrientationManifestIO;
import flash.pipeline.naming.NameParts;
import flash.pipeline.naming.OrientationManifestRow;
import flash.pipeline.naming.ResolvedImageMetadata;
import flash.pipeline.orientation.BroadcastScope;
import flash.pipeline.orientation.OrientationBatchController;
import flash.pipeline.orientation.OrientationPresetStore;
import flash.pipeline.orientation.OrientationTransformState;
import flash.pipeline.orientation.RoiOrientationManifestService;
import flash.pipeline.roi.RoiIO;
import flash.pipeline.zslice.ZSliceMode;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.process.ByteProcessor;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.IndexColorModel;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DrawAndSaveROIsAnalysisTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @After
    public void resetDispatcher() throws Exception {
        invokeDispatcherReset();
    }

    @Test
    public void declaresChannelNamesAndZSliceWithoutRoiBenefit() {
        DrawAndSaveROIsAnalysis analysis = new DrawAndSaveROIsAnalysis();

        assertEquals(EnumSet.of(BinField.CHANNEL_NAMES, BinField.Z_SLICE),
                analysis.requiredBinFields());
        assertFalse(analysis.benefitsFromRois());
        assertTrue(analysis.requiresHeadedMode());
    }

    @Test
    public void roiOutputHelpersPointDrawRoisAtResultsFolders() throws Exception {
        File dir = temp.newFolder("roi-output-layout");

        assertEquals(new File(dir, "FLASH/Results/Analysis Images/ROIs"),
                RoiIO.roiSetWriteDir(dir));
        assertEquals(new File(dir, "FLASH/Results/Analysis Images/ROIs"),
                RoiIO.imageOutputsWriteDir(dir));
        assertEquals(new File(dir, "FLASH/Results/Tables/ROIs"),
                RoiIO.attributesWriteDir(dir));
    }

    @Test
    public void roiImageWindowLocationOpensBelowImageJBarOnSameScreen() {
        Point location = DrawAndSaveROIsAnalysis.roiImageWindowLocationNearAnchor(
                new Rectangle(1200, 40, 520, 90),
                new Dimension(700, 500),
                new Rectangle(1000, 0, 1200, 900));

        assertEquals(new Point(1200, 142), location);
    }

    @Test
    public void roiImageWindowLocationClampsToImageJScreenWhenSpaceIsTight() {
        Point location = DrawAndSaveROIsAnalysis.roiImageWindowLocationNearAnchor(
                new Rectangle(2100, 700, 150, 90),
                new Dimension(500, 400),
                new Rectangle(1000, 0, 1200, 900));

        assertEquals(new Point(1700, 288), location);
    }

    @Test
    public void executeReturnsGracefullyWhenDispatcherCancelsMissingBin() throws Exception {
        File dir = temp.newFolder("cancelled");
        installDispatcherChoice(BinSetupChooser.Choice.CANCELLED, new AtomicInteger(0));

        new DrawAndSaveROIsAnalysis().execute(dir.getAbsolutePath());

        assertFalse(new File(dir, "ROIs").exists());
    }

    @Test
    public void executeOverridesHeadlessFlagWhenDisplayIsAvailable() throws Exception {
        File dir = temp.newFolder("headedOverride");
        AtomicInteger chooserCalls = new AtomicInteger(0);
        installDispatcherChoice(BinSetupChooser.Choice.CANCELLED, chooserCalls);

        DrawAndSaveROIsAnalysis analysis = new DrawAndSaveROIsAnalysis();
        analysis.setHeadless(true);
        analysis.execute(dir.getAbsolutePath());

        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            assertEquals(1, chooserCalls.get());
        }
        assertFalse(new File(dir, "ROIs").exists());
    }

    @Test
    public void channelNamesAndZSliceBinCompletesWithoutChooser() throws Exception {
        File dir = temp.newFolder("partial");
        TestConfigFiles.writeChannelConfig(dir, TestConfigFiles.basicBinConfig("DAPI", "GFAP"));
        AtomicInteger chooserCalls = new AtomicInteger(0);
        installDispatcherChoice(BinSetupChooser.Choice.CANCELLED, chooserCalls);

        DrawAndSaveROIsAnalysis analysis = new DrawAndSaveROIsAnalysis();
        BinSetupDispatcher.Outcome outcome = BinSetupDispatcher.ensure(
                dir.getAbsolutePath(), "Draw ROIs and Orientate Images",
                analysis.requiredBinFields(), analysis.benefitsFromRois());

        assertEquals(BinSetupDispatcher.Outcome.COMPLETED, outcome);
        assertEquals(0, chooserCalls.get());
    }

    @Test
    public void buildRoiChannelChoices_usesBinChannelNamesWhenPresent() {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("IBA1");
        cfg.channelNames.add("DAPI");
        cfg.channelNames.add("GFAP");

        assertArrayEquals(new String[]{"1 (IBA1)", "2 (DAPI)", "3 (GFAP)"},
                DrawAndSaveROIsAnalysis.buildRoiChannelChoices(cfg));
    }

    @Test
    public void buildRoiChannelChoices_defaultsToFourPlainChannelsWithoutBin() {
        assertArrayEquals(new String[]{"1", "2", "3", "4"},
                DrawAndSaveROIsAnalysis.buildRoiChannelChoices(null));
    }

    @Test
    public void defaultRoiChannelChoice_prefersNuclearBoundaryMarker() {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("IBA1");
        cfg.channelNames.add("Hoechst");
        cfg.channelNames.add("GFAP");

        String[] choices = DrawAndSaveROIsAnalysis.buildRoiChannelChoices(cfg);

        assertEquals("2 (Hoechst)", DrawAndSaveROIsAnalysis.defaultRoiChannelChoice(cfg, choices));
    }

    @Test
    public void defaultRoiChannelChoice_fallsBackToFirstChannel() {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("IBA1");
        cfg.channelNames.add("GFAP");

        String[] choices = DrawAndSaveROIsAnalysis.buildRoiChannelChoices(cfg);

        assertEquals("1 (IBA1)", DrawAndSaveROIsAnalysis.defaultRoiChannelChoice(cfg, choices));
    }

    @Test
    public void parseRoiChannelChoice_readsLeadingChannelNumber() {
        assertEquals(3, DrawAndSaveROIsAnalysis.parseRoiChannelChoice("3 (DAPI)"));
        assertEquals(12, DrawAndSaveROIsAnalysis.parseRoiChannelChoice("12"));
        assertEquals(1, DrawAndSaveROIsAnalysis.parseRoiChannelChoice("DAPI"));
        assertEquals(1, DrawAndSaveROIsAnalysis.parseRoiChannelChoice(null));
    }

    @Test
    public void resolveImportedRoiLayout_acceptsOnePerImageOrFlashPairs() {
        assertEquals(DrawAndSaveROIsAnalysis.ImportedRoiLayout.ONE_PER_IMAGE,
                DrawAndSaveROIsAnalysis.resolveImportedRoiLayout(4, 4));
        assertEquals(DrawAndSaveROIsAnalysis.ImportedRoiLayout.FLASH_PAIRS,
                DrawAndSaveROIsAnalysis.resolveImportedRoiLayout(8, 4));
    }

    @Test
    public void resolveImportedRoiLayout_rejectsMismatchedCounts() {
        try {
            DrawAndSaveROIsAnalysis.resolveImportedRoiLayout(5, 4);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Expected either 4 ROI"));
            return;
        }
        throw new AssertionError("mismatched ROI count should be rejected");
    }

    @Test
    public void sourceRoiForImport_usesEvenRoisFromFlashPairs() {
        List<Roi> rois = new ArrayList<Roi>();
        Roi first = new Roi(0, 0, 2, 2);
        Roi firstCropped = new Roi(0, 0, 1, 1);
        Roi second = new Roi(1, 1, 2, 2);
        Roi secondCropped = new Roi(0, 0, 1, 1);
        rois.add(first);
        rois.add(firstCropped);
        rois.add(second);
        rois.add(secondCropped);

        assertEquals(first, DrawAndSaveROIsAnalysis.sourceRoiForImport(rois,
                DrawAndSaveROIsAnalysis.ImportedRoiLayout.FLASH_PAIRS, 0));
        assertEquals(second, DrawAndSaveROIsAnalysis.sourceRoiForImport(rois,
                DrawAndSaveROIsAnalysis.ImportedRoiLayout.FLASH_PAIRS, 1));
    }

    @Test
    public void validateImportedFlashPairNames_requiresCroppedOddSlots() {
        List<Roi> rois = new ArrayList<Roi>();
        Roi first = new Roi(0, 0, 2, 2);
        first.setName("Mouse_LH_SCN");
        Roi firstCropped = new Roi(0, 0, 1, 1);
        firstCropped.setName("Mouse_LH_SCN_Cropped");
        rois.add(first);
        rois.add(firstCropped);

        DrawAndSaveROIsAnalysis.validateImportedFlashPairNames(rois, 1);

        firstCropped.setName("Mouse_LH_SCN");
        try {
            DrawAndSaveROIsAnalysis.validateImportedFlashPairNames(rois, 1);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("_Cropped"));
            return;
        }
        throw new AssertionError("FLASH pair import should reject unnamed cropped slot");
    }

    @Test
    public void importedRoiBoundsWithinImage_rejectsOutOfBoundsAndInvalidRois() {
        assertTrue(DrawAndSaveROIsAnalysis.importedRoiBoundsWithinImage(
                new Roi(1, 1, 3, 2), 5, 5));
        assertFalse(DrawAndSaveROIsAnalysis.importedRoiBoundsWithinImage(
                new Roi(3, 1, 3, 2), 5, 5));
        assertFalse(DrawAndSaveROIsAnalysis.importedRoiBoundsWithinImage(
                new Roi(-1, 1, 2, 2), 5, 5));
        assertFalse(DrawAndSaveROIsAnalysis.importedRoiBoundsWithinImage(
                null, 5, 5));
    }

    @Test
    public void importSetNameFromZip_stripsRoiSuffixAndSanitizesFilename() {
        assertEquals("SCN", DrawAndSaveROIsAnalysis.importSetNameFromZip(
                new File("SCN ROIs.zip")));
        assertEquals("bad_name", DrawAndSaveROIsAnalysis.importSetNameFromZip(
                new File("bad:name.zip")));
    }

    @Test
    public void shouldShowZSliceSourceChoice_onlyForSubsetModes() {
        BinConfig cfg = new BinConfig();
        cfg.zSliceMode = ZSliceMode.FULL;
        assertFalse(DrawAndSaveROIsAnalysis.shouldShowZSliceSourceChoice(cfg));

        cfg.zSliceMode = ZSliceMode.SAME_COUNT;
        assertTrue(DrawAndSaveROIsAnalysis.shouldShowZSliceSourceChoice(cfg));
    }

    @Test
    public void roiChannelLutName_usesSelectedChannelColorAndDefaultsToGrays() {
        BinConfig cfg = new BinConfig();
        cfg.channelColors.add("Blue");
        cfg.channelColors.add("Green");

        assertEquals("Green", DrawAndSaveROIsAnalysis.roiChannelLutName(cfg, 2));
        assertEquals("Grays", DrawAndSaveROIsAnalysis.roiChannelLutName(cfg, 3));
        assertEquals("Grays", DrawAndSaveROIsAnalysis.roiChannelLutName(null, 1));
    }

    @Test
    public void applyRoiDisplayLut_appliesSelectedColorAndGreyFallback() {
        ImagePlus green = new ImagePlus("green", new ByteProcessor(1, 1));
        DrawAndSaveROIsAnalysis.applyRoiDisplayLut(green, "Green");

        IndexColorModel greenModel = (IndexColorModel) green.getProcessor().getColorModel();
        assertEquals(0, greenModel.getRed(255));
        assertEquals(255, greenModel.getGreen(255));
        assertEquals(0, greenModel.getBlue(255));

        ImagePlus gray = new ImagePlus("gray", new ByteProcessor(1, 1));
        DrawAndSaveROIsAnalysis.applyRoiDisplayLut(gray, null);

        IndexColorModel grayModel = (IndexColorModel) gray.getProcessor().getColorModel();
        assertEquals(255, grayModel.getRed(255));
        assertEquals(255, grayModel.getGreen(255));
        assertEquals(255, grayModel.getBlue(255));
    }

    @Test
    public void toggleRoiDisplayLut_switchesBetweenGreyAndSelectedChannelLut() throws Exception {
        File dir = temp.newFolder("roiLutToggle");
        assertTrue(new File(dir, "ImageA.tif").createNewFile());
        ImagePlus original = new ImagePlus("ImageA", new ByteProcessor(1, 1));
        ImagePlus max = new ImagePlus("MAX_delete", new ByteProcessor(1, 1));
        ImagePlus roiStack = new ImagePlus("delete", new ByteProcessor(1, 1));
        ResolvedImageMetadata seed = new ResolvedImageMetadata(
                "",
                "ImageA",
                "ImageA",
                "Mouse",
                "LH",
                "SCN",
                OrientationManifestRow.RotationDegrees.DEG_0,
                false,
                false,
                OrientationManifestRow.ViewPolicy.MANUAL_ONLY,
                ResolvedImageMetadata.Source.FILENAME_FALLBACK);

        DrawAndSaveROIsAnalysis.PreparedImage prepared =
                DrawAndSaveROIsAnalysis.buildPreparedImage(
                        dir.getAbsolutePath(), 0, original, max, roiStack,
                        new NameParts("Exp", "Mouse", "LH", "SCN", true),
                        seed, "", "Red");

        assertEquals("Red", DrawAndSaveROIsAnalysis.effectiveRoiLutName(prepared));
        assertEquals("Grey LUT", DrawAndSaveROIsAnalysis.roiLutToggleButtonText(prepared));

        DrawAndSaveROIsAnalysis.toggleRoiDisplayLut(prepared);

        assertEquals("Grays", DrawAndSaveROIsAnalysis.effectiveRoiLutName(prepared));
        assertEquals("Red LUT", DrawAndSaveROIsAnalysis.roiLutToggleButtonText(prepared));
        IndexColorModel grayModel = (IndexColorModel) max.getProcessor().getColorModel();
        assertEquals(255, grayModel.getRed(255));
        assertEquals(255, grayModel.getGreen(255));
        assertEquals(255, grayModel.getBlue(255));

        DrawAndSaveROIsAnalysis.toggleRoiDisplayLut(prepared);

        assertEquals("Red", DrawAndSaveROIsAnalysis.effectiveRoiLutName(prepared));
        assertEquals("Grey LUT", DrawAndSaveROIsAnalysis.roiLutToggleButtonText(prepared));
        IndexColorModel redModel = (IndexColorModel) max.getProcessor().getColorModel();
        assertEquals(255, redModel.getRed(255));
        assertEquals(0, redModel.getGreen(255));
        assertEquals(0, redModel.getBlue(255));
    }

    @Test
    public void createNewRangeStartsAtFirstSeries() {
        DrawAndSaveROIsAnalysis.RoiSeriesRange range =
                DrawAndSaveROIsAnalysis.RoiSeriesRange.forMode(true, 4, 5);

        assertEquals(0, range.firstSeriesIndexInclusive);
        assertEquals(5, range.totalSeries);
        assertEquals(5, range.imageCountToProcess);
        assertEquals(0, range.processingIndexFor(0));
    }

    @Test
    public void appendRangeStartsAfterExistingRoiPairs() {
        DrawAndSaveROIsAnalysis.RoiSeriesRange range =
                DrawAndSaveROIsAnalysis.RoiSeriesRange.forMode(false, 4, 5);

        assertEquals(2, range.firstSeriesIndexInclusive);
        assertEquals(5, range.totalSeries);
        assertEquals(3, range.imageCountToProcess);
        assertEquals(0, range.processingIndexFor(2));
    }

    @Test
    public void appendRangeProcessesZeroImagesWhenCoverageIsComplete() {
        DrawAndSaveROIsAnalysis.RoiSeriesRange range =
                DrawAndSaveROIsAnalysis.RoiSeriesRange.forMode(false, 10, 5);

        assertEquals(5, range.firstSeriesIndexInclusive);
        assertEquals(5, range.totalSeries);
        assertEquals(0, range.imageCountToProcess);
    }

    @Test
    public void buildPreparedImageCarriesIdentitySeedMetadataAndTransformState() throws Exception {
        File dir = temp.newFolder("prepared");
        assertTrue(new File(dir, "Experiment.lif").createNewFile());
        String title = "Experiment.lif - Mouse_RH_SCN";
        ImagePlus original = new ImagePlus(title, new ByteProcessor(4, 3));
        ImagePlus max = new ImagePlus("MAX_delete", new ByteProcessor(4, 3));
        ImagePlus roiStack = new ImagePlus("delete", new ByteProcessor(4, 3));
        ResolvedImageMetadata seed = ResolvedImageMetadata.fromNameParts(
                new NameParts("Experiment", "Mouse", "RH", "SCN", true),
                ResolvedImageMetadata.Source.STRICT_FILENAME);

        DrawAndSaveROIsAnalysis.PreparedImage prepared =
                DrawAndSaveROIsAnalysis.buildPreparedImage(
                        dir.getAbsolutePath(), 0, original, max, roiStack,
                        seed.toNameParts(), seed);

        assertEquals(0, prepared.seriesIndex);
        assertEquals("Experiment.lif", prepared.identity.sourceFile);
        assertEquals(1, prepared.identity.seriesIndex);
        assertEquals(title, prepared.identity.originalName);
        assertEquals(seed, prepared.seedMetadata);
        assertEquals(OrientationManifestRow.RotationDegrees.DEG_270,
                prepared.transformState.rotateDegrees);
        assertTrue(prepared.transformState.flipHorizontal);
        assertFalse(prepared.transformState.flipVertical);

        OrientationTransformState reset = prepared.transformState.reset();
        prepared.transformState = reset;
        assertTrue(prepared.transformState.isIdentity());
    }

    @Test
    public void currentImageAdapterRendersStateBeforeFirstShowWithoutOpeningWindow()
            throws Exception {
        File dir = temp.newFolder("currentImageAdapter");
        DrawAndSaveROIsAnalysis analysis = new DrawAndSaveROIsAnalysis();
        DrawAndSaveROIsAnalysis.PreparedImage prepared =
                preparedImage(dir, "Exp-Mouse_LH_SCN", "LH", "Red");

        OrientationBatchController.CurrentImage current =
                analysis.currentImageFor(prepared);
        OrientationTransformState next =
                OrientationTransformState.fromCsv("90", true, false);

        current.applyState(next);

        assertEquals(OrientationManifestRow.Hemisphere.LH, current.hemisphere());
        assertEquals(OrientationManifestRow.RotationDegrees.DEG_90,
                prepared.transformState.rotateDegrees);
        assertTrue(prepared.transformState.flipHorizontal);
        assertFalse(prepared.transformState.flipVertical);
        assertNotNull(prepared.roiStack);
        assertNotNull(prepared.maxProjection);
        assertEquals("delete", prepared.roiStack.getTitle());
        assertEquals("MAX_delete", prepared.maxProjection.getTitle());
        assertNull(prepared.maxProjection.getWindow());
        assertEquals("Red", DrawAndSaveROIsAnalysis.effectiveRoiLutName(prepared));
    }

    @Test
    public void activeRuleOnOpenRendersPrefetchedImageBeforeShow() throws Exception {
        File dir = temp.newFolder("activeRuleOnOpen");
        DrawAndSaveROIsAnalysis analysis = new DrawAndSaveROIsAnalysis();
        OrientationBatchController controller = new OrientationBatchController(
                new OrientationPresetStore(dir.getAbsolutePath()), 4);
        DrawAndSaveROIsAnalysis.PreparedImage source =
                preparedImage(dir, "Exp-Source_LH_SCN", "LH", "Grays");
        source.transformState = OrientationTransformState.fromCsv("90", true, false);
        controller.bindCurrent(analysis.currentImageFor(source), 0);
        controller.setRule(BroadcastScope.ALL_LITERAL);

        DrawAndSaveROIsAnalysis.PreparedImage target =
                preparedImage(dir, "Exp-Target_RH_SCN", "RH", "Grays");
        controller.bindCurrent(analysis.currentImageFor(target), 2);

        assertTrue(controller.applyActiveRuleOnOpen());
        assertEquals(OrientationManifestRow.RotationDegrees.DEG_90,
                target.transformState.rotateDegrees);
        assertTrue(target.transformState.flipHorizontal);
        assertFalse(target.transformState.flipVertical);
        assertNotNull(target.roiStack);
        assertNotNull(target.maxProjection);
        assertNull(target.maxProjection.getWindow());
        assertTrue(controller.ruleStatusText().contains("1 remaining"));
    }

    @Test
    public void saveOrientationDecisionWritesPreparedTransformAsManualRow() throws Exception {
        File dir = temp.newFolder("saveOrientation");
        assertTrue(new File(dir, "ImageA.tif").createNewFile());
        String title = "ImageA";
        ImagePlus original = new ImagePlus(title, new ByteProcessor(4, 3));
        ImagePlus max = new ImagePlus("MAX_delete", new ByteProcessor(4, 3));
        ImagePlus roiStack = new ImagePlus("delete", new ByteProcessor(4, 3));
        ResolvedImageMetadata seed = new ResolvedImageMetadata(
                "",
                title,
                title,
                "SeedMouse",
                "LH",
                "SCN",
                OrientationManifestRow.RotationDegrees.DEG_0,
                false,
                false,
                OrientationManifestRow.ViewPolicy.MANUAL_ONLY,
                ResolvedImageMetadata.Source.FILENAME_FALLBACK);

        DrawAndSaveROIsAnalysis.PreparedImage prepared =
                DrawAndSaveROIsAnalysis.buildPreparedImage(
                        dir.getAbsolutePath(), 0, original, max, roiStack,
                        new NameParts("Exp", "ManualMouse", "RH", "PVN", true),
                        seed);
        prepared.transformState = OrientationTransformState.fromCsv("90", true, true);

        OrientationManifestRow row = DrawAndSaveROIsAnalysis.saveOrientationDecision(
                new RoiOrientationManifestService(dir.getAbsolutePath()),
                prepared,
                "unit test");

        assertEquals("ManualMouse", row.animalName);
        assertEquals(OrientationManifestRow.Hemisphere.RH, row.hemisphere);
        assertEquals("PVN", row.region);
        assertEquals(OrientationManifestRow.RotationDegrees.DEG_90, row.rotateDegrees);
        assertTrue(row.flipHorizontal);
        assertTrue(row.flipVertical);
        assertEquals(OrientationManifestRow.ViewPolicy.MANUAL_ONLY, row.viewPolicy);
        assertEquals(OrientationManifestRow.DecisionSource.MANUAL, row.decisionSource);
        assertEquals(OrientationManifestRow.ConfirmationState.YES, row.confirmed);
    }

    @Test
    public void roiOrientationSave_flattensStrictFilenameTransformToManualOnly() throws Exception {
        File dir = temp.newFolder("flattenStrict");
        assertTrue(new File(dir, "Exp-Mouse_RH_SCN.tif").createNewFile());
        String title = "Exp-Mouse_RH_SCN";
        ImagePlus original = new ImagePlus(title, new ByteProcessor(4, 3));
        ImagePlus max = new ImagePlus("MAX_delete", new ByteProcessor(4, 3));
        ImagePlus roiStack = new ImagePlus("delete", new ByteProcessor(4, 3));
        ResolvedImageMetadata seed = ResolvedImageMetadata.fromNameParts(
                new NameParts("Exp", "Mouse", "RH", "SCN", true),
                ResolvedImageMetadata.Source.STRICT_FILENAME);

        DrawAndSaveROIsAnalysis.PreparedImage prepared =
                DrawAndSaveROIsAnalysis.buildPreparedImage(
                        dir.getAbsolutePath(), 0, original, max, roiStack,
                        seed.toNameParts(), seed);

        OrientationManifestRow row = DrawAndSaveROIsAnalysis.saveOrientationDecision(
                new RoiOrientationManifestService(dir.getAbsolutePath()),
                prepared,
                "Saved during Draw ROIs and Orientate Images");

        assertEquals(OrientationManifestRow.RotationDegrees.DEG_270, row.rotateDegrees);
        assertTrue(row.flipHorizontal);
        assertFalse(row.flipVertical);
        assertEquals(OrientationManifestRow.ViewPolicy.MANUAL_ONLY, row.viewPolicy);
        assertEquals(OrientationManifestRow.DecisionSource.MANUAL, row.decisionSource);
        assertEquals(OrientationManifestRow.ConfirmationState.YES, row.confirmed);
    }

    @Test
    public void skippedImage_stillSavesConfirmedOrientationRow() throws Exception {
        File dir = temp.newFolder("skippedOrientation");
        assertTrue(new File(dir, "Skipped_Mouse_LH_SCN.tif").createNewFile());
        String title = "Skipped_Mouse_LH_SCN";
        ImagePlus original = new ImagePlus(title, new ByteProcessor(4, 3));
        ImagePlus max = new ImagePlus("MAX_delete", new ByteProcessor(4, 3));
        ImagePlus roiStack = new ImagePlus("delete", new ByteProcessor(4, 3));
        ResolvedImageMetadata seed = new ResolvedImageMetadata(
                "",
                title,
                title,
                "SkippedMouse",
                "LH",
                "SCN",
                OrientationManifestRow.RotationDegrees.DEG_0,
                false,
                false,
                OrientationManifestRow.ViewPolicy.MANUAL_ONLY,
                ResolvedImageMetadata.Source.FILENAME_FALLBACK);

        DrawAndSaveROIsAnalysis.PreparedImage prepared =
                DrawAndSaveROIsAnalysis.buildPreparedImage(
                        dir.getAbsolutePath(), 0, original, max, roiStack,
                        new NameParts("Exp", "SkippedMouse", "LH", "SCN", true),
                        seed);

        OrientationManifestRow row = DrawAndSaveROIsAnalysis.saveOrientationDecision(
                new RoiOrientationManifestService(dir.getAbsolutePath()),
                prepared,
                "Skipped during Draw ROIs and Orientate Images; placeholder ROIs padded");

        assertEquals(OrientationManifestRow.RotationDegrees.DEG_0, row.rotateDegrees);
        assertFalse(row.flipHorizontal);
        assertFalse(row.flipVertical);
        assertEquals(OrientationManifestRow.ConfirmationState.YES, row.confirmed);
        assertTrue(row.notes.contains("Skipped during Draw ROIs and Orientate Images"));

        List<OrientationManifestRow> rows =
                OrientationManifestIO.readIfExists(dir.getAbsolutePath());
        assertEquals(1, rows.size());
        assertEquals(row.imageKey, rows.get(0).imageKey);
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

    private static DrawAndSaveROIsAnalysis.PreparedImage preparedImage(
            File dir, String title, String hemisphere, String roiLutName)
            throws Exception {
        assertTrue(new File(dir, title + ".tif").createNewFile());
        ImagePlus original = new ImagePlus(title, new ByteProcessor(4, 3));
        ResolvedImageMetadata seed = new ResolvedImageMetadata(
                "",
                title,
                title,
                "Mouse",
                hemisphere,
                "SCN",
                OrientationManifestRow.RotationDegrees.DEG_0,
                false,
                false,
                OrientationManifestRow.ViewPolicy.MANUAL_ONLY,
                ResolvedImageMetadata.Source.FILENAME_FALLBACK);
        return DrawAndSaveROIsAnalysis.buildPreparedImage(
                dir.getAbsolutePath(), 0, original, null, null,
                new NameParts("Exp", "Mouse", hemisphere, "SCN", true),
                seed, "", roiLutName);
    }

    private interface InvocationResult {
        Object invoke(Method method, Object[] args);
    }
}
