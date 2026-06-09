package flash.pipeline.analyses;

import flash.pipeline.TestConfigFiles;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinField;
import flash.pipeline.bin.BinSetupChooser;
import flash.pipeline.bin.BinSetupDispatcher;
import flash.pipeline.bin.ChannelConfig;
import flash.pipeline.bin.ChannelConfigIO;
import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import flash.pipeline.intensity.spatial.IntensitySpatialOutputKey;
import flash.pipeline.intensity.spatial.IntensitySpatialOutputMode;
import flash.pipeline.io.CsvTableIO;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.naming.NameParts;
import flash.pipeline.roi.RoiIO;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.Calibration;
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.DependencyService;
import flash.pipeline.runtime.DependencyStatus;
import flash.pipeline.runtime.FeatureDependencyGate;
import ij.measure.ResultsTable;
import ij.ImageStack;
import ij.process.FloatProcessor;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class IntensityAnalysisV2Test {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @After
    public void resetHooks() throws Exception {
        invokeDispatcherReset();
        FeatureDependencyGate.configure(new DependencyService(), null);
        FeatureDependencyGate.setUiMode(false);
    }

    @Test
    public void declaresIntensityBinRequirementsAndRoiBenefit() {
        IntensityAnalysisV2 analysis = new IntensityAnalysisV2();

        assertEquals(EnumSet.of(
                BinField.CHANNEL_NAMES,
                BinField.FILTER_PRESETS,
                BinField.INTENSITY_THRESHOLDS,
                BinField.Z_SLICE),
                analysis.requiredBinFields());
        assertTrue(analysis.benefitsFromRois());
    }

    @Test
    public void executeReturnsGracefullyWhenDispatcherCancelsMissingBin() throws Exception {
        installAllDependenciesPresentForGate();
        File dir = temp.newFolder("cancelled");
        AtomicInteger chooserCalls = new AtomicInteger(0);
        installDispatcherChoice(BinSetupChooser.Choice.CANCELLED, chooserCalls);

        new IntensityAnalysisV2().execute(dir.getAbsolutePath());

        assertEquals(1, chooserCalls.get());
        assertFalse(new File(dir, "FLASH/Results/Tables/Intensity").exists());
        assertFalse(new File(dir, "FLASH/Results/Analysis Images/Intensity Overlays").exists());
    }

    @Test
    public void drawRoisHandoffContinuesToIntensityConfigurationChooser() throws Exception {
        installAllDependenciesPresentForGate();
        final File dir = temp.newFolder("roi-handoff");
        final AtomicInteger stage = new AtomicInteger(0);

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
                        assertEquals(2, stage.getAndIncrement());
                        assertEquals("Intensity Analysis", args[0]);
                        return BinSetupChooser.Choice.CANCELLED;
                    }
                });

        IntensityAnalysisV2 analysis = new IntensityAnalysisV2();
        analysis.setNoRoiDecisionPromptForTest(new IntensityAnalysisV2.NoRoiDecisionPrompt() {
            @Override
            public IntensityAnalysisV2.NoRoiDecision choose() {
                assertEquals(0, stage.getAndIncrement());
                return IntensityAnalysisV2.NoRoiDecision.DRAW_ROIS;
            }
        });
        analysis.setRoiDrawingWorkflowLauncherForTest(new IntensityAnalysisV2.RoiDrawingWorkflowLauncher() {
            @Override
            public void launch(String directory) {
                assertEquals(1, stage.getAndIncrement());
                try {
                    File roiDir = RoiIO.roiSetWriteDir(new File(directory));
                    assertTrue(roiDir.isDirectory() || roiDir.mkdirs());
                    Files.write(new File(roiDir, "SCN ROIs.zip").toPath(), new byte[]{1, 2, 3});
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });

        analysis.execute(dir.getAbsolutePath());

        assertEquals(3, stage.get());
        assertEquals(BinSetupDispatcher.Outcome.CANCELLED, BinSetupDispatcher.getLastOutcome());
    }

    @Test
    public void intensityPathsResolveUnderResultsLayout() throws Exception {
        File dir = temp.newFolder("intensityPaths");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(dir.getAbsolutePath());

        File writeRoot = layout.tablesIntensityWriteDir();
        File overlayRoot = layout.analysisImagesIntensityOverlaysDir();

        assertEquals(new File(dir, "FLASH/Results/Tables/Intensity").getAbsolutePath(),
                writeRoot.getAbsolutePath());
        assertEquals(new File(dir, "FLASH/Results/Analysis Images/Intensity Overlays").getAbsolutePath(),
                overlayRoot.getAbsolutePath());
        assertEquals(new File(writeRoot, "GFAP in DAPI ROI.csv").getAbsolutePath(),
                IntensityAnalysisV2.intensityOutputCsv(writeRoot, "GFAP", true, 1,
                        new String[]{"DAPI", "GFAP"}).getAbsolutePath());
        assertEquals(new File(writeRoot, "GFAP_MIP.csv").getAbsolutePath(),
                IntensityAnalysisV2.intensityOutputCsv(writeRoot, "GFAP",
                        IntensitySpatialOutputMode.MIP).getAbsolutePath());
        assertEquals(new File(writeRoot, "GFAP_3D.csv").getAbsolutePath(),
                IntensityAnalysisV2.intensityOutputCsv(writeRoot, "GFAP",
                        IntensitySpatialOutputMode.NATIVE_3D).getAbsolutePath());
    }

    @Test
    public void intensityCleanupDoesNotCloseMainOrFlashWindows() {
        assertFalse(IntensityAnalysisV2.shouldCloseIntensityNonImageFrame("Log", "ij.text.TextWindow"));
        assertFalse(IntensityAnalysisV2.shouldCloseIntensityNonImageFrame("ImageJ", "ij.ImageJ"));
        assertFalse(IntensityAnalysisV2.shouldCloseIntensityNonImageFrame(
                "FLASH - The Pipeline for Fluorescence Automated Spatial Histology",
                "javax.swing.JFrame"));
        assertFalse(IntensityAnalysisV2.shouldCloseIntensityNonImageFrame(
                "Repeat Pipeline?", "javax.swing.JFrame"));
        assertTrue(IntensityAnalysisV2.shouldCloseIntensityNonImageFrame(
                "Results", "ij.text.TextWindow"));
    }

    @Test
    public void selectedMipOutputIsNotSuppressedByExistingBaseCsv() throws Exception {
        File writeRoot = temp.newFolder("skip-mip");
        assertTrue(new File(writeRoot, "DAPI.csv").createNewFile());
        IntensitySpatialConfig spatial = IntensitySpatialConfig.builder()
                .enabled(true)
                .mipEnabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.PATCHINESS)
                .build();

        IntensityAnalysisV2.IntensityOutputPlan plan = IntensityAnalysisV2.buildOutputPlan(
                writeRoot, new String[]{"DAPI"}, false, -1, spatial, 5, true);

        IntensitySpatialOutputKey base = IntensitySpatialOutputKey.base("DAPI");
        IntensitySpatialOutputKey mip = IntensitySpatialOutputKey.of("DAPI",
                IntensitySpatialOutputMode.MIP);
        assertTrue(plan.selectedKeys().contains(base));
        assertTrue(plan.selectedKeys().contains(mip));
        assertTrue(plan.isSkipped(base));
        assertFalse(plan.isSkipped(mip));
        assertFalse(plan.allSelectedOutputsSkipped());
        assertEquals(new File(writeRoot, "DAPI_MIP.csv").getAbsolutePath(),
                plan.fileFor(mip).getAbsolutePath());
    }

    @Test
    public void mipSourceKeepsBaseCsvForIntensityOnlyAndSpatialInMipCsv() {
        IntensitySpatialConfig spatial = IntensitySpatialConfig.builder()
                .enabled(true)
                .mipEnabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.PATCHINESS)
                .build();

        List<String> baseColumns = IntensityAnalysisV2.buildOrderedIntensityColumns(
                IntensitySpatialOutputKey.base("DAPI"),
                new ResultsTable(),
                new String[]{"DAPI"},
                spatial,
                new boolean[]{false});
        List<String> mipColumns = IntensityAnalysisV2.buildOrderedIntensityColumns(
                IntensitySpatialOutputKey.of("DAPI", IntensitySpatialOutputMode.MIP),
                new ResultsTable(),
                new String[]{"DAPI"},
                spatial,
                new boolean[]{false});

        assertFalse(baseColumns.contains("Intensity_PatchinessCV50"));
        assertTrue(mipColumns.contains("Intensity_PatchinessCV50"));
        assertTrue(mipColumns.contains("z"));
        assertFalse(mipColumns.contains("IntDen"));
        assertTrue(mipColumns.indexOf("z")
                < mipColumns.indexOf("Intensity_PatchinessCV50"));
    }

    @Test
    public void failedSpatialMeasurementKeepsSelectedMipAndNativeRowsAvailable() throws Exception {
        File writeRoot = temp.newFolder("failed-spatial-placeholders");
        IntensitySpatialConfig spatial = IntensitySpatialConfig.builder()
                .enabled(true)
                .mipEnabled(true)
                .native3dEnabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.GRANULARITY)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.ANISOTROPY_3D)
                .build();
        IntensityAnalysisV2.IntensityOutputPlan plan = IntensityAnalysisV2.buildOutputPlan(
                writeRoot, new String[]{"DAPI"}, false, -1, spatial, 5, false);

        Object failed = invokeFailedChannelSpatialResults(
                syntheticImage(16, 16), "DAPI", plan, spatial);

        assertNull(fieldValue(failed, "baseResults"));
        assertNotNull(fieldValue(failed, "mipResult"));
        assertNotNull(fieldValue(failed, "nativeResult"));
    }

    @Test
    public void skipExistingTracksBaseMipAndNative3dFilesIndependently() throws Exception {
        File writeRoot = temp.newFolder("skip-matrix");
        assertTrue(new File(writeRoot, "DAPI.csv").createNewFile());
        assertTrue(new File(writeRoot, "DAPI_3D.csv").createNewFile());
        IntensitySpatialConfig spatial = IntensitySpatialConfig.builder()
                .enabled(true)
                .mipEnabled(true)
                .native3dEnabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.PATCHINESS)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.ANISOTROPY_3D)
                .build();

        IntensityAnalysisV2.IntensityOutputPlan plan = IntensityAnalysisV2.buildOutputPlan(
                writeRoot, new String[]{"DAPI"}, false, -1, spatial, 6, true);

        IntensitySpatialOutputKey base = IntensitySpatialOutputKey.base("DAPI");
        IntensitySpatialOutputKey mip = IntensitySpatialOutputKey.of("DAPI",
                IntensitySpatialOutputMode.MIP);
        IntensitySpatialOutputKey native3d = IntensitySpatialOutputKey.of("DAPI",
                IntensitySpatialOutputMode.NATIVE_3D);
        assertTrue(plan.selectedKeys().contains(base));
        assertTrue(plan.selectedKeys().contains(mip));
        assertTrue(plan.selectedKeys().contains(native3d));
        assertTrue(plan.isSkipped(base));
        assertFalse(plan.isSkipped(mip));
        assertTrue(plan.isSkipped(native3d));
        assertFalse(plan.allSelectedOutputsSkipped());
        assertEquals(new File(writeRoot, "DAPI_MIP.csv").getAbsolutePath(),
                plan.fileFor(mip).getAbsolutePath());
    }

    @Test
    public void native3dOutputIsNotSelectedUnlessNative3dIsEnabled() throws Exception {
        File writeRoot = temp.newFolder("native-off");
        IntensitySpatialConfig spatial = IntensitySpatialConfig.builder()
                .enabled(true)
                .native3dEnabled(false)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.ANISOTROPY_3D)
                .build();

        IntensityAnalysisV2.IntensityOutputPlan plan = IntensityAnalysisV2.buildOutputPlan(
                writeRoot, new String[]{"DAPI"}, false, -1, spatial, 8, false);

        assertFalse(plan.selectedKeys().contains(IntensitySpatialOutputKey.of("DAPI",
                IntensitySpatialOutputMode.NATIVE_3D)));
        assertTrue(plan.selectedKeys().contains(IntensitySpatialOutputKey.base("DAPI")));
    }

    @Test
    public void channelRoiMaskOutputsStayBaseOnly() throws Exception {
        File writeRoot = temp.newFolder("roi-mask-base");
        IntensitySpatialConfig spatial = IntensitySpatialConfig.builder()
                .enabled(true)
                .mipEnabled(true)
                .native3dEnabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.PATCHINESS)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.ANISOTROPY_3D)
                .build();

        IntensityAnalysisV2.IntensityOutputPlan plan = IntensityAnalysisV2.buildOutputPlan(
                writeRoot, new String[]{"DAPI", "GFAP"}, true, 1, spatial, 8, false);

        IntensitySpatialOutputKey gfapBase = IntensitySpatialOutputKey.roiMaskBase("GFAP", "DAPI");
        assertTrue(plan.selectedKeys().contains(gfapBase));
        assertEquals(new File(writeRoot, "GFAP in DAPI ROI.csv").getAbsolutePath(),
                plan.fileFor(gfapBase).getAbsolutePath());
        assertFalse(plan.selectedKeys().contains(IntensitySpatialOutputKey.of("GFAP",
                IntensitySpatialOutputMode.MIP)));
        assertFalse(plan.selectedKeys().contains(IntensitySpatialOutputKey.of("GFAP",
                IntensitySpatialOutputMode.NATIVE_3D)));
    }

    @Test
    public void orderedIntensityColumnsGroupMetadataBasicSameChannelThenPartners() {
        IntensitySpatialConfig spatial = IntensitySpatialConfig.builder()
                .enabled(true)
                .spatialSourceMode(IntensitySpatialConfig.SpatialSourceMode.FULL_STACK)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.PATCHINESS)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.NULLMODEL)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.CROSSMARK)
                .build();

        List<String> columns = IntensityAnalysisV2.buildOrderedIntensityColumns(
                IntensitySpatialOutputKey.base("DAPI"),
                new ResultsTable(),
                new String[]{"DAPI", "GFAP"},
                spatial,
                new boolean[]{true, false});

        assertEquals(Arrays.asList("Region", "Atlas Key", "Region ID", "Region Acronym",
                "Region Name", "Hemisphere", "ROI", "Animal Name",
                "z",
                "IntDen", "IntDen_binarized", "%Area", "%Area_binarized",
                "IntDen_Unfiltered"),
                columns.subList(0, 14));
        assertTrue(columns.indexOf("Intensity_PatchinessCV50")
                < columns.indexOf("DAPI_Pearson_GFAP"));
        assertTrue(columns.contains("Intensity_PatchinessCV50_binarized"));
        assertTrue(columns.contains("Intensity_Lacunarity250_binarized"));
        assertFalse(columns.contains("Intensity_NullModelP_binarized"));
        assertTrue(columns.indexOf("DAPI_Pearson_GFAP")
                < columns.indexOf("DAPI_MarkCorrStrength_GFAP"));
    }

    @Test
    public void measurementColumnsUseRawFirstSchemaForBinarizedRows() {
        ResultsTable table = new ResultsTable();
        table.incrementCounter();

        IntensityAnalysisV2.writeMeasurementColumns(table, 0,
                10.0, 20.0, 30.0, true, 40.0, 50.0);

        assertEquals(10.0, table.getValue("IntDen", 0), 0.0001);
        assertEquals(40.0, table.getValue("IntDen_binarized", 0), 0.0001);
        assertEquals(20.0, table.getValue("%Area", 0), 0.0001);
        assertEquals(50.0, table.getValue("%Area_binarized", 0), 0.0001);
        assertEquals(30.0, table.getValue("IntDen_Unfiltered", 0), 0.0001);
    }

    @Test
    public void baseIntensityRowsRecordOriginalZSliceNumbers() throws Exception {
        File dir = temp.newFolder("z-column");
        File binDir = new File(dir, ".bin");
        assertTrue(binDir.mkdirs());
        File outputRoot = FlashProjectLayout.forDirectory(dir.getAbsolutePath())
                .tablesIntensityWriteDir();
        assertTrue(outputRoot.mkdirs());

        String[] channelNames = {"DAPI"};
        boolean[] binarization = {false};
        String[] thresholds = {"0"};
        String[] filterSources = {"Basic background and noise removal"};
        IntensitySpatialConfig spatial = IntensitySpatialConfig.disabled();
        IntensityAnalysisV2.IntensityOutputPlan plan = IntensityAnalysisV2.buildOutputPlan(
                outputRoot, channelNames, false, -1, spatial, 3, false);
        Object totalTables = newOutputTables(plan);

        invokeRunIntensityMeasurementsForThisImage(
                new IntensityAnalysisV2(),
                new NameParts("", "SyntheticMouse", "LH", "SCN"),
                new ImagePlus[]{syntheticStackImage(8, 8, 3)},
                1,
                binarization,
                thresholds,
                channelNames,
                -1,
                plan,
                totalTables,
                1,
                null,
                1,
                5,
                intensityConfig("DAPI", "default"),
                filterSources,
                binDir,
                "",
                null);

        ResultsTable table = tableFor(totalTables, IntensitySpatialOutputKey.base("DAPI"));
        assertEquals(3, table.size());
        assertEquals(5.0, table.getValue("z", 0), 0.0);
        assertEquals(6.0, table.getValue("z", 1), 0.0);
        assertEquals(7.0, table.getValue("z", 2), 0.0);
    }

    @Test
    public void completeRequiredBinCompletesWithoutChooser() throws Exception {
        File dir = temp.newFolder("complete");
        BinConfig cfg = TestConfigFiles.basicBinConfig("DAPI", "GFAP");
        cfg.channelIntensityThresholds.clear();
        cfg.channelIntensityThresholds.add("10");
        cfg.channelIntensityThresholds.add("20");
        TestConfigFiles.writeChannelConfig(dir, cfg);
        AtomicInteger chooserCalls = new AtomicInteger(0);
        installDispatcherChoice(BinSetupChooser.Choice.CANCELLED, chooserCalls);

        IntensityAnalysisV2 analysis = new IntensityAnalysisV2();
        BinSetupDispatcher.Outcome outcome = BinSetupDispatcher.ensure(
                dir.getAbsolutePath(), "Intensity Analysis",
                analysis.requiredBinFields(), analysis.benefitsFromRois());

        assertEquals(BinSetupDispatcher.Outcome.COMPLETED, outcome);
        assertEquals(0, chooserCalls.get());
    }

    @Test
    public void channelNamesOnlyBinPromptsForIntensityThresholdsAndFilters() throws Exception {
        File dir = temp.newFolder("partial");
        writeChannelNamesOnlyConfig(dir, "DAPI", "GFAP");
        final AtomicReference<Set<BinField>> missingFields = new AtomicReference<Set<BinField>>();
        installDispatcherChoice(BinSetupChooser.Choice.CANCELLED, new AtomicInteger(0),
                missingFields);

        IntensityAnalysisV2 analysis = new IntensityAnalysisV2();
        BinSetupDispatcher.Outcome outcome = BinSetupDispatcher.ensure(
                dir.getAbsolutePath(), "Intensity Analysis",
                analysis.requiredBinFields(), analysis.benefitsFromRois());

        assertEquals(BinSetupDispatcher.Outcome.CANCELLED, outcome);
        assertEquals(EnumSet.of(BinField.FILTER_PRESETS, BinField.INTENSITY_THRESHOLDS),
                missingFields.get());
    }

    @Test
    public void filterSummaryShowsNotNeededWhenNoThresholdAndNoBinarisation() throws Exception {
        BinConfig cfg = intensityConfig("DAPI", "default");
        String summary = invokeFilterSummaryLine(0, cfg,
                new String[]{"DAPI"}, new String[]{"Bin filter"}, new boolean[]{false});

        assertTrue(summary.contains("Threshold: not needed unless Binarise is enabled"));
    }

    @Test
    public void filterSummaryShowsConfiguredNumericThresholdWhenBinarisationOff() throws Exception {
        BinConfig cfg = intensityConfig("GFAP", "750");
        String summary = invokeFilterSummaryLine(0, cfg,
                new String[]{"GFAP"}, new String[]{"Bin filter"}, new boolean[]{false});

        assertTrue(summary.contains("Threshold: 750 (from configuration; used if Binarise is enabled)"));
        assertFalse(summary.contains("not needed"));
    }

    @Test
    public void filterSummaryPromptsNextDialogForDefaultThresholdWhenBinarisationEnabled() throws Exception {
        BinConfig cfg = intensityConfig("IBA1", "default");
        String summary = invokeFilterSummaryLine(0, cfg,
                new String[]{"IBA1"}, new String[]{"Bin filter"}, new boolean[]{true});

        assertTrue(summary.contains("Threshold: Enter on next dialogue"));
        assertFalse(summary.contains("not needed"));
    }

    @Test
    public void interactiveIntensitySpatialToggleLaunchesOptionsDialogAndStoresConfig() throws Exception {
        IntensityAnalysisV2 analysis = new IntensityAnalysisV2();
        final AtomicInteger launches = new AtomicInteger(0);
        final AtomicReference<IntensitySpatialConfig> currentConfig =
                new AtomicReference<IntensitySpatialConfig>();
        final AtomicReference<String[]> launchedChannels = new AtomicReference<String[]>();
        final AtomicReference<boolean[]> launchedBinarization = new AtomicReference<boolean[]>();
        final IntensitySpatialConfig selected = IntensitySpatialConfig.builder()
                .enabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.GRANULARITY)
                .build();
        analysis.setIntensitySpatialOptionsDialogLauncherForTest(
                new IntensityAnalysisV2.IntensitySpatialOptionsDialogLauncher() {
                    @Override
                    public IntensitySpatialConfig launch(String directory,
                                                         IntensitySpatialConfig current,
                                                         String[] channelNames,
                                                         boolean[] binarization,
                                                         Integer likelyStackDepth) {
                        launches.incrementAndGet();
                        currentConfig.set(current);
                        launchedChannels.set(channelNames);
                        launchedBinarization.set(binarization);
                        return selected;
                    }
                });

        assertTrue(analysis.prepareIntensitySpatialOptionsBeforeAnalysis(
                temp.newFolder("intensity-spatial-launch").getAbsolutePath(),
                TestConfigFiles.basicBinConfig("DAPI", "GFAP"),
                new String[]{"DAPI", "GFAP"},
                new boolean[]{false, false},
                true));

        assertEquals(1, launches.get());
        assertFalse(currentConfig.get().isEnabled());
        assertTrue(Arrays.equals(new String[]{"DAPI", "GFAP"}, launchedChannels.get()));
        assertTrue(Arrays.equals(new boolean[]{false, false}, launchedBinarization.get()));
        assertTrue(analysis.getIntensitySpatialConfigForTest().isEnabled());
        assertTrue(analysis.getIntensitySpatialConfigForTest().getEnabledAnalyses()
                .contains(IntensitySpatialConfig.AnalysisKey.GRANULARITY));
    }

    @Test
    public void cancelledIntensitySpatialOptionsCancelBeforeProcessing() throws Exception {
        IntensityAnalysisV2 analysis = new IntensityAnalysisV2();
        final AtomicInteger launches = new AtomicInteger(0);
        analysis.setIntensitySpatialOptionsDialogLauncherForTest(
                new IntensityAnalysisV2.IntensitySpatialOptionsDialogLauncher() {
                    @Override
                    public IntensitySpatialConfig launch(String directory,
                                                         IntensitySpatialConfig current,
                                                         String[] channelNames,
                                                         boolean[] binarization,
                                                         Integer likelyStackDepth) {
                        launches.incrementAndGet();
                        return null;
                    }
                });

        assertFalse(analysis.prepareIntensitySpatialOptionsBeforeAnalysis(
                temp.newFolder("intensity-spatial-cancel").getAbsolutePath(),
                intensityConfig("DAPI", "default"),
                new String[]{"DAPI"},
                new boolean[]{false},
                true));
        assertEquals(1, launches.get());
        assertFalse(analysis.getIntensitySpatialConfigForTest().isEnabled());
    }

    @Test
    public void hideImageWindowsStillLaunchesInteractiveIntensitySpatialOptions() throws Exception {
        IntensityAnalysisV2 analysis = new IntensityAnalysisV2();
        analysis.setHeadless(true);
        final AtomicInteger launches = new AtomicInteger(0);
        final IntensitySpatialConfig selected = IntensitySpatialConfig.builder()
                .enabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.PATCHINESS)
                .build();
        analysis.setIntensitySpatialOptionsDialogLauncherForTest(
                new IntensityAnalysisV2.IntensitySpatialOptionsDialogLauncher() {
                    @Override
                    public IntensitySpatialConfig launch(String directory,
                                                         IntensitySpatialConfig current,
                                                         String[] channelNames,
                                                         boolean[] binarization,
                                                         Integer likelyStackDepth) {
                        launches.incrementAndGet();
                        return selected;
                    }
                });

        assertTrue(analysis.prepareIntensitySpatialOptionsBeforeAnalysis(
                temp.newFolder("intensity-spatial-image-headless").getAbsolutePath(),
                intensityConfig("DAPI", "default"),
                new String[]{"DAPI"},
                new boolean[]{false},
                true));

        assertEquals(1, launches.get());
        assertTrue(analysis.getIntensitySpatialConfigForTest().isEnabled());
    }

    @Test
    public void suppressedIntensitySpatialSetupValidatesExistingConfigWithoutDialog() throws Exception {
        IntensityAnalysisV2 analysis = new IntensityAnalysisV2();
        analysis.setSuppressDialogs(true);
        setIntensitySpatialConfigForTest(analysis, IntensitySpatialConfig.builder()
                .enabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.PATCHINESS)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.CROSSMARK)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL)
                .build());
        final AtomicInteger launches = new AtomicInteger(0);
        analysis.setIntensitySpatialOptionsDialogLauncherForTest(
                new IntensityAnalysisV2.IntensitySpatialOptionsDialogLauncher() {
                    @Override
                    public IntensitySpatialConfig launch(String directory,
                                                         IntensitySpatialConfig current,
                                                         String[] channelNames,
                                                         boolean[] binarization,
                                                         Integer likelyStackDepth) {
                        launches.incrementAndGet();
                        return current;
                    }
                });

        assertTrue(analysis.prepareIntensitySpatialOptionsBeforeAnalysis(
                temp.newFolder("intensity-spatial-suppressed").getAbsolutePath(),
                intensityConfig("DAPI", "default"),
                new String[]{"DAPI"},
                new boolean[]{false},
                true));

        assertEquals(0, launches.get());
        IntensitySpatialConfig validated = analysis.getIntensitySpatialConfigForTest();
        assertTrue(validated.isEnabled());
        assertTrue(validated.getEnabledAnalyses().contains(IntensitySpatialConfig.AnalysisKey.PATCHINESS));
        assertFalse(validated.getEnabledAnalyses().contains(IntensitySpatialConfig.AnalysisKey.CROSSMARK));
        assertFalse(validated.getEnabledAnalyses().contains(IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL));
    }

    @Test
    public void headlessSpatialMeasurementSkipsMissingOptionalDependencyAndStillWritesBaseCsv() throws Exception {
        installDependencyStatusesForGate(DependencyId.IMGLIB2_ALGORITHM_RUNTIME);
        File dir = temp.newFolder("headless-spatial-missing-dependency");
        File binDir = new File(dir, ".bin");
        assertTrue(binDir.mkdirs());
        File outputRoot = FlashProjectLayout.forDirectory(dir.getAbsolutePath())
                .tablesIntensityWriteDir();
        assertTrue(outputRoot.mkdirs());
        String[] channelNames = {"DAPI"};
        boolean[] binarization = {false};
        String[] thresholds = {"0"};
        String[] filterSources = {"Basic background and noise removal"};
        IntensitySpatialConfig spatial = IntensitySpatialConfig.builder()
                .enabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.GRANULARITY)
                .build();
        IntensityAnalysisV2.IntensityOutputPlan plan = IntensityAnalysisV2.buildOutputPlan(
                outputRoot, channelNames, false, -1, spatial, 1, false);
        Object totalTables = newOutputTables(plan);

        IntensityAnalysisV2 analysis = new IntensityAnalysisV2();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        setIntensitySpatialConfigForTest(analysis, spatial);

        String log = captureImageJLogOutput(new ThrowingRunnable() {
            @Override
            public void run() throws Exception {
                invokeRunIntensityMeasurementsForThisImage(
                        analysis,
                        new NameParts("", "SyntheticMouse", "LH", "SCN"),
                        new ImagePlus[]{syntheticImage(24, 24)},
                        1,
                        binarization,
                        thresholds,
                        channelNames,
                        -1,
                        plan,
                        totalTables,
                        1,
                        null,
                        intensityConfig("DAPI", "default"),
                        filterSources,
                        binDir,
                        "",
                        null);
            }
        });

        IntensitySpatialOutputKey baseKey = IntensitySpatialOutputKey.base("DAPI");
        ResultsTable table = tableFor(totalTables, baseKey);
        File out = IntensityAnalysisV2.intensityOutputCsv(outputRoot, baseKey);
        CsvTableIO.writeResultsTableCsv(out, table,
                IntensityAnalysisV2.buildOrderedIntensityColumns(baseKey, table,
                        channelNames, spatial, binarization));
        assertTrue(out.isFile());
        String csv = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("Region,Atlas Key,Region ID,Region Acronym,Region Name,Hemisphere,ROI,Animal Name,z,IntDen,%Area,IntDen_Unfiltered"));
        assertTrue(csv.contains("Intensity_GranularityPeak_um"));
        assertTrue(csv.contains("NaN"));
        assertTrue(log.contains("granularity skipped"));
        assertTrue(log.contains("missing dependency IMGLIB2_ALGORITHM_RUNTIME"));
    }

    @Test
    public void intensitySpatialProgressLogIdentifiesImageChannelSliceAndPair() throws Exception {
        File dir = temp.newFolder("intensity-spatial-progress-log");
        File binDir = new File(dir, ".bin");
        assertTrue(binDir.mkdirs());
        File outputRoot = FlashProjectLayout.forDirectory(dir.getAbsolutePath())
                .tablesIntensityWriteDir();
        assertTrue(outputRoot.mkdirs());

        String[] channelNames = {"DAPI", "GFAP"};
        boolean[] binarization = {false, false};
        String[] thresholds = {"0", "0"};
        String[] filterSources = {
                "Basic background and noise removal",
                "Basic background and noise removal"
        };
        IntensitySpatialConfig spatial = IntensitySpatialConfig.builder()
                .enabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.PATCHINESS)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.ENTROPY_MI)
                .build();
        IntensityAnalysisV2.IntensityOutputPlan plan = IntensityAnalysisV2.buildOutputPlan(
                outputRoot, channelNames, false, -1, spatial, 2, false);
        Object totalTables = newOutputTables(plan);

        IntensityAnalysisV2 analysis = new IntensityAnalysisV2();
        setIntensitySpatialConfigForTest(analysis, spatial);

        String log = captureImageJLogOutput(new ThrowingRunnable() {
            @Override
            public void run() throws Exception {
                invokeRunIntensityMeasurementsForThisImage(
                        analysis,
                        new NameParts("", "SyntheticMouse", "LH", "SCN"),
                        new ImagePlus[]{
                                syntheticStackImage(12, 12, 2),
                                syntheticStackImage(12, 12, 2)
                        },
                        2,
                        binarization,
                        thresholds,
                        channelNames,
                        -1,
                        plan,
                        totalTables,
                        2,
                        null,
                        5,
                        intensityConfig("DAPI", "0"),
                        filterSources,
                        binDir,
                        "",
                        null);
            }
        });

        String context = "image 2/5 SyntheticMouse_LH_SCN ROI SCN2";
        assertTrue(log, log.contains(context + ": starting intensity measurements (2 channels)"));
        assertTrue(log, log.contains(context + ": channel 1/2 DAPI started"));
        assertTrue(log, log.contains(context + ": intensity-spatial same-channel [DAPI]"));
        assertTrue(log, log.contains("Intensity-spatial patchiness running: image 2/5 "
                + "SyntheticMouse_LH_SCN channel DAPI ROI SCN2 base slice 1"));
        assertTrue(log, log.contains(context + ": intensity-spatial cross-channel"));
        assertTrue(log, log.contains(context + ": pair [1/2] DAPI -> GFAP base slice 1/2"));
        assertTrue(log, log.contains("Intensity-spatial mi running: image 2/5 "
                + "SyntheticMouse_LH_SCN source DAPI -> partner GFAP ROI SCN2 base slice 1"));
    }

    @Test
    public void mipOnlyCrossChannelSpatialDoesNotRunPerSliceCrossChannelWork() throws Exception {
        File dir = temp.newFolder("mip-only-cross-channel-spatial");
        File binDir = new File(dir, ".bin");
        assertTrue(binDir.mkdirs());
        File outputRoot = FlashProjectLayout.forDirectory(dir.getAbsolutePath())
                .tablesIntensityWriteDir();
        assertTrue(outputRoot.mkdirs());

        String[] channelNames = {"DAPI", "GFAP"};
        boolean[] binarization = {false, false};
        String[] thresholds = {"0", "0"};
        String[] filterSources = {
                "Basic background and noise removal",
                "Basic background and noise removal"
        };
        IntensitySpatialConfig spatial = IntensitySpatialConfig.builder()
                .enabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.CROSSMARK,
                        IntensitySpatialOutputMode.MIP)
                .build();
        IntensityAnalysisV2.IntensityOutputPlan plan = IntensityAnalysisV2.buildOutputPlan(
                outputRoot, channelNames, false, -1, spatial, 3, false);
        Object totalTables = newOutputTables(plan);

        IntensityAnalysisV2 analysis = new IntensityAnalysisV2();
        setIntensitySpatialConfigForTest(analysis, spatial);

        String log = captureImageJLogOutput(new ThrowingRunnable() {
            @Override
            public void run() throws Exception {
                invokeRunIntensityMeasurementsForThisImage(
                        analysis,
                        new NameParts("", "SyntheticMouse", "LH", "SCN"),
                        new ImagePlus[]{
                                syntheticStackImage(8, 8, 3),
                                syntheticStackImage(8, 8, 3)
                        },
                        2,
                        binarization,
                        thresholds,
                        channelNames,
                        -1,
                        plan,
                        totalTables,
                        1,
                        null,
                        intensityConfig("DAPI", "0"),
                        filterSources,
                        binDir,
                        "",
                        null);
            }
        });

        assertTrue(log, log.contains("intensity-spatial cross-channel: MIP crossmark"));
        assertTrue(log, log.contains("DAPI MIP -> GFAP MIP"));
        assertFalse(log, log.contains("starting same-channel intensity-spatial"));
        assertFalse(log, log.contains("DAPI -> GFAP base"));
        assertFalse(log, log.contains("crossmark running: image 1/1 "
                + "SyntheticMouse_LH_SCN source DAPI -> partner GFAP ROI SCN1 base slice"));

        ResultsTable base = tableFor(totalTables, IntensitySpatialOutputKey.base("DAPI"));
        ResultsTable mip = tableFor(totalTables, IntensitySpatialOutputKey.of("DAPI",
                IntensitySpatialOutputMode.MIP));
        assertEquals(3, base.size());
        assertEquals(1, mip.size());
        assertFalse(Arrays.asList(base.getHeadings()).contains("DAPI_Pearson_GFAP"));
        assertTrue(Arrays.asList(mip.getHeadings()).contains("DAPI_Pearson_GFAP"));
    }

    @Test
    public void mipOnlySameChannelSpatialDoesNotRunPerSliceSameChannelWork() throws Exception {
        File dir = temp.newFolder("mip-only-same-channel-spatial");
        File binDir = new File(dir, ".bin");
        assertTrue(binDir.mkdirs());
        File outputRoot = FlashProjectLayout.forDirectory(dir.getAbsolutePath())
                .tablesIntensityWriteDir();
        assertTrue(outputRoot.mkdirs());

        String[] channelNames = {"DAPI"};
        boolean[] binarization = {false};
        String[] thresholds = {"0"};
        String[] filterSources = {"Basic background and noise removal"};
        IntensitySpatialConfig spatial = IntensitySpatialConfig.builder()
                .enabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.PATCHINESS,
                        IntensitySpatialOutputMode.MIP)
                .build();
        IntensityAnalysisV2.IntensityOutputPlan plan = IntensityAnalysisV2.buildOutputPlan(
                outputRoot, channelNames, false, -1, spatial, 4, false);
        Object totalTables = newOutputTables(plan);

        IntensityAnalysisV2 analysis = new IntensityAnalysisV2();
        setIntensitySpatialConfigForTest(analysis, spatial);

        String log = captureImageJLogOutput(new ThrowingRunnable() {
            @Override
            public void run() throws Exception {
                invokeRunIntensityMeasurementsForThisImage(
                        analysis,
                        new NameParts("", "SyntheticMouse", "LH", "SCN"),
                        new ImagePlus[]{syntheticStackImage(8, 8, 4)},
                        1,
                        binarization,
                        thresholds,
                        channelNames,
                        -1,
                        plan,
                        totalTables,
                        1,
                        null,
                        intensityConfig("DAPI", "0"),
                        filterSources,
                        binDir,
                        "",
                        null);
            }
        });

        IntensitySpatialOutputKey baseKey = IntensitySpatialOutputKey.base("DAPI");
        IntensitySpatialOutputKey mipKey = IntensitySpatialOutputKey.of("DAPI",
                IntensitySpatialOutputMode.MIP);
        ResultsTable base = tableFor(totalTables, baseKey);
        ResultsTable mip = tableFor(totalTables, mipKey);
        assertEquals(4, base.size());
        assertEquals(1, mip.size());
        assertTrue(log, log.contains("intensity-spatial same-channel [DAPI]: MIP patchiness"));
        assertTrue(log, log.contains("same-channel DAPI MIP: building max-intensity projection"));
        assertFalse(log, log.contains("same-channel DAPI base output"));
        assertFalse(Arrays.asList(base.getHeadings()).contains("Intensity_PatchinessCV50"));
        assertTrue(Arrays.asList(mip.getHeadings()).contains("Intensity_PatchinessCV50"));
    }

    @Test
    public void perSliceOnlySpatialDoesNotSelectOrRunMipOrNativeOutputs() throws Exception {
        File dir = temp.newFolder("per-slice-only-spatial");
        File binDir = new File(dir, ".bin");
        assertTrue(binDir.mkdirs());
        File outputRoot = FlashProjectLayout.forDirectory(dir.getAbsolutePath())
                .tablesIntensityWriteDir();
        assertTrue(outputRoot.mkdirs());

        String[] channelNames = {"DAPI"};
        boolean[] binarization = {false};
        String[] thresholds = {"0"};
        String[] filterSources = {"Basic background and noise removal"};
        IntensitySpatialConfig spatial = IntensitySpatialConfig.builder()
                .enabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.PATCHINESS,
                        IntensitySpatialOutputMode.BASE)
                .build();
        IntensityAnalysisV2.IntensityOutputPlan plan = IntensityAnalysisV2.buildOutputPlan(
                outputRoot, channelNames, false, -1, spatial, 5, false);
        IntensitySpatialOutputKey baseKey = IntensitySpatialOutputKey.base("DAPI");
        assertTrue(plan.selectedKeys().contains(baseKey));
        assertFalse(plan.selectedKeys().contains(IntensitySpatialOutputKey.of("DAPI",
                IntensitySpatialOutputMode.MIP)));
        assertFalse(plan.selectedKeys().contains(IntensitySpatialOutputKey.of("DAPI",
                IntensitySpatialOutputMode.NATIVE_3D)));
        Object totalTables = newOutputTables(plan);

        IntensityAnalysisV2 analysis = new IntensityAnalysisV2();
        setIntensitySpatialConfigForTest(analysis, spatial);

        String log = captureImageJLogOutput(new ThrowingRunnable() {
            @Override
            public void run() throws Exception {
                invokeRunIntensityMeasurementsForThisImage(
                        analysis,
                        new NameParts("", "SyntheticMouse", "LH", "SCN"),
                        new ImagePlus[]{syntheticStackImage(8, 8, 5)},
                        1,
                        binarization,
                        thresholds,
                        channelNames,
                        -1,
                        plan,
                        totalTables,
                        1,
                        null,
                        intensityConfig("DAPI", "0"),
                        filterSources,
                        binDir,
                        "",
                        null);
            }
        });

        ResultsTable base = tableFor(totalTables, baseKey);
        assertEquals(5, base.size());
        assertTrue(log, log.contains("intensity-spatial same-channel [DAPI]: per-slice patchiness"));
        assertTrue(log, log.contains("same-channel DAPI base output: 5 slices"));
        assertFalse(log, log.contains("same-channel DAPI MIP:"));
        assertFalse(log, log.contains("native 3D output"));
        assertTrue(Arrays.asList(base.getHeadings()).contains("Intensity_PatchinessCV50"));
    }

    @Test
    public void native3dOnlySpatialDoesNotRun2dOrMipWork() throws Exception {
        File dir = temp.newFolder("native-3d-only-spatial");
        File binDir = new File(dir, ".bin");
        assertTrue(binDir.mkdirs());
        File outputRoot = FlashProjectLayout.forDirectory(dir.getAbsolutePath())
                .tablesIntensityWriteDir();
        assertTrue(outputRoot.mkdirs());

        String[] channelNames = {"DAPI"};
        boolean[] binarization = {false};
        String[] thresholds = {"0"};
        String[] filterSources = {"Basic background and noise removal"};
        IntensitySpatialConfig spatial = IntensitySpatialConfig.builder()
                .enabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.ANISOTROPY_3D,
                        IntensitySpatialOutputMode.NATIVE_3D)
                .build();
        IntensityAnalysisV2.IntensityOutputPlan plan = IntensityAnalysisV2.buildOutputPlan(
                outputRoot, channelNames, false, -1, spatial, 5, false);
        IntensitySpatialOutputKey baseKey = IntensitySpatialOutputKey.base("DAPI");
        IntensitySpatialOutputKey nativeKey = IntensitySpatialOutputKey.of("DAPI",
                IntensitySpatialOutputMode.NATIVE_3D);
        assertTrue(plan.selectedKeys().contains(baseKey));
        assertFalse(plan.selectedKeys().contains(IntensitySpatialOutputKey.of("DAPI",
                IntensitySpatialOutputMode.MIP)));
        assertTrue(plan.selectedKeys().contains(nativeKey));
        Object totalTables = newOutputTables(plan);

        IntensityAnalysisV2 analysis = new IntensityAnalysisV2();
        setIntensitySpatialConfigForTest(analysis, spatial);

        String log = captureImageJLogOutput(new ThrowingRunnable() {
            @Override
            public void run() throws Exception {
                invokeRunIntensityMeasurementsForThisImage(
                        analysis,
                        new NameParts("", "SyntheticMouse", "LH", "SCN"),
                        new ImagePlus[]{syntheticStackImage(8, 8, 5)},
                        1,
                        binarization,
                        thresholds,
                        channelNames,
                        -1,
                        plan,
                        totalTables,
                        1,
                        null,
                        intensityConfig("DAPI", "0"),
                        filterSources,
                        binDir,
                        "",
                        null);
            }
        });

        ResultsTable base = tableFor(totalTables, baseKey);
        ResultsTable native3d = tableFor(totalTables, nativeKey);
        assertEquals(5, base.size());
        assertEquals(1, native3d.size());
        assertTrue(log, log.contains("intensity-spatial same-channel [DAPI]: native 3D anisotropy_3d"));
        assertTrue(log, log.contains("same-channel DAPI native 3D output: 5 slices"));
        assertFalse(log, log.contains("same-channel DAPI base output"));
        assertFalse(log, log.contains("same-channel DAPI MIP:"));
        assertFalse(Arrays.asList(base.getHeadings()).contains("Intensity_Anisotropy3DCoherency"));
        assertTrue(Arrays.asList(native3d.getHeadings()).contains("Intensity_Anisotropy3DCoherency"));
    }

    @Test
    public void parallelChannelThresholdFailureAbortsBeforePartialRowsAreMerged() throws Exception {
        File dir = temp.newFolder("parallel-channel-failure");
        File binDir = new File(dir, ".bin");
        assertTrue(binDir.mkdirs());
        File outputRoot = FlashProjectLayout.forDirectory(dir.getAbsolutePath())
                .tablesIntensityWriteDir();
        assertTrue(outputRoot.mkdirs());

        String[] channelNames = {"DAPI", "GFAP"};
        boolean[] binarization = {true, true};
        String[] thresholds = {"5", "not-a-number"};
        String[] filterSources = {
                "Basic background and noise removal",
                "Basic background and noise removal"
        };
        IntensitySpatialConfig spatial = IntensitySpatialConfig.disabled();
        IntensityAnalysisV2.IntensityOutputPlan plan = IntensityAnalysisV2.buildOutputPlan(
                outputRoot, channelNames, false, -1, spatial, 1, false);
        Object totalTables = newOutputTables(plan);

        IntensityAnalysisV2 analysis = new IntensityAnalysisV2();
        analysis.setParallelThreads(2);

        try {
            invokeRunIntensityMeasurementsForThisImage(
                    analysis,
                    new NameParts("", "SyntheticMouse", "LH", "SCN"),
                    new ImagePlus[]{syntheticImage(8, 8), syntheticImage(8, 8)},
                    2,
                    binarization,
                    thresholds,
                    channelNames,
                    -1,
                    plan,
                    totalTables,
                    1,
                    null,
                    intensityConfig("DAPI", "5"),
                    filterSources,
                    binDir,
                    "",
                    null);
            fail("Expected parallel channel threshold failure to abort intensity analysis");
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            assertTrue(cause instanceof RuntimeException);
            assertTrue(cause.getMessage(), cause.getMessage().contains(
                    "Intensity analysis failed for 1 channel(s)"));
            assertEquals(1, cause.getSuppressed().length);
            assertTrue(cause.getSuppressed()[0] instanceof NumberFormatException);
            assertTrue(cause.getSuppressed()[0].getMessage(),
                    cause.getSuppressed()[0].getMessage().contains("channel 'GFAP'"));
        }

        assertEquals(0, tableFor(totalTables, IntensitySpatialOutputKey.base("DAPI")).size());
        assertEquals(0, tableFor(totalTables, IntensitySpatialOutputKey.base("GFAP")).size());
    }

    @Test
    public void negativeThresholdFailureIncludesChannelImageAndRoiContext() throws Exception {
        File dir = temp.newFolder("negative-threshold-failure");
        File binDir = new File(dir, ".bin");
        assertTrue(binDir.mkdirs());
        File outputRoot = FlashProjectLayout.forDirectory(dir.getAbsolutePath())
                .tablesIntensityWriteDir();
        assertTrue(outputRoot.mkdirs());

        String[] channelNames = {"DAPI"};
        boolean[] binarization = {true};
        String[] thresholds = {"-1"};
        String[] filterSources = {"Basic background and noise removal"};
        IntensitySpatialConfig spatial = IntensitySpatialConfig.disabled();
        IntensityAnalysisV2.IntensityOutputPlan plan = IntensityAnalysisV2.buildOutputPlan(
                outputRoot, channelNames, false, -1, spatial, 1, false);
        Object totalTables = newOutputTables(plan);

        try {
            invokeRunIntensityMeasurementsForThisImage(
                    new IntensityAnalysisV2(),
                    new NameParts("", "SyntheticMouse", "LH", "SCN"),
                    new ImagePlus[]{syntheticImage(8, 8)},
                    1,
                    binarization,
                    thresholds,
                    channelNames,
                    -1,
                    plan,
                    totalTables,
                    1,
                    "LH",
                    intensityConfig("DAPI", "-1"),
                    filterSources,
                    binDir,
                    "",
                    null);
            fail("Expected negative threshold to abort intensity analysis");
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            assertTrue(cause instanceof NumberFormatException);
            assertTrue(cause.getMessage(), cause.getMessage().contains("channel 'DAPI'"));
            assertTrue(cause.getMessage(), cause.getMessage().contains("SyntheticMouse"));
            assertTrue(cause.getMessage(), cause.getMessage().contains("ROI"));
            assertEquals(0, tableFor(totalTables, IntensitySpatialOutputKey.base("DAPI")).size());
        }
    }

    private static void installDispatcherChoice(final BinSetupChooser.Choice choice,
                                                final AtomicInteger chooserCalls) throws Exception {
        installDispatcherChoice(choice, chooserCalls, null);
    }

    private static BinConfig intensityConfig(String channelName, String intensityThreshold) {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add(channelName);
        cfg.channelIntensityThresholds.add(intensityThreshold);
        cfg.channelFilterPresets.add("Default");
        return cfg;
    }

    private static String invokeFilterSummaryLine(int channelIndex,
                                                  BinConfig cfg,
                                                  String[] channelNames,
                                                  String[] filterSources,
                                                  boolean[] binarization) throws Exception {
        Method method = IntensityAnalysisV2.class.getDeclaredMethod("buildFilterSummaryLine",
                int.class, BinConfig.class, String[].class, String[].class, boolean[].class);
        method.setAccessible(true);
        return (String) method.invoke(null, channelIndex, cfg, channelNames, filterSources, binarization);
    }

    private static void installDispatcherChoice(final BinSetupChooser.Choice choice,
                                                final AtomicInteger chooserCalls,
                                                final AtomicReference<Set<BinField>> missingFields) throws Exception {
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
                    @SuppressWarnings("unchecked")
                    @Override public Object invoke(Method method, Object[] args) {
                        chooserCalls.incrementAndGet();
                        if (missingFields != null && args != null && args.length > 1) {
                            missingFields.set((Set<BinField>) args[1]);
                        }
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
        installDependencyStatusesForGate(null);
    }

    private static void installDependencyStatusesForGate(final DependencyId missing) throws Exception {
        final EnumMap<DependencyId, DependencyStatus> statuses =
                new EnumMap<DependencyId, DependencyStatus>(DependencyId.class);
        for (DependencyId id : DependencyId.values()) {
            statuses.put(id, id == missing
                    ? DependencyStatus.missing(id.name() + " missing")
                    : DependencyStatus.present(id.name() + " present"));
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

    private static ImagePlus syntheticImage(int width, int height) {
        float[] pixels = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = (float) (25.0 + x + y);
            }
        }
        ImagePlus image = new ImagePlus("synthetic", new FloatProcessor(width, height, pixels, null));
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 1.0;
        calibration.pixelHeight = 1.0;
        calibration.setUnit("um");
        image.setCalibration(calibration);
        return image;
    }

    private static ImagePlus syntheticStackImage(int width, int height, int slices) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 1; z <= slices; z++) {
            float[] pixels = new float[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y * width + x] = (float) (25.0 + z + x + y);
                }
            }
            stack.addSlice("z" + z, new FloatProcessor(width, height, pixels, null));
        }
        ImagePlus image = new ImagePlus("synthetic-stack", stack);
        image.setDimensions(1, Math.max(1, slices), 1);
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 1.0;
        calibration.pixelHeight = 1.0;
        calibration.setUnit("um");
        image.setCalibration(calibration);
        return image;
    }

    private static String captureImageJLogOutput(ThrowingRunnable action) throws Exception {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, "UTF-8"));
        String ijLog = null;
        try {
            if (IJ.getLog() != null) IJ.log("\\Clear");
            action.run();
            ijLog = IJ.getLog();
        } finally {
            System.out.flush();
            System.setOut(originalOut);
        }
        return out.toString("UTF-8") + (ijLog == null ? "" : ijLog);
    }

    private static Object newOutputTables(IntensityAnalysisV2.IntensityOutputPlan plan) throws Exception {
        Method method = plan.getClass().getDeclaredMethod("newTables");
        method.setAccessible(true);
        return method.invoke(plan);
    }

    private static Object invokeFailedChannelSpatialResults(
            ImagePlus raw,
            String channelName,
            IntensityAnalysisV2.IntensityOutputPlan outputPlan,
            IntensitySpatialConfig spatialConfig) throws Exception {
        Method method = IntensityAnalysisV2.class.getDeclaredMethod(
                "failedChannelSpatialResults",
                ImagePlus.class,
                String.class,
                IntensityAnalysisV2.IntensityOutputPlan.class,
                IntensitySpatialConfig.class);
        method.setAccessible(true);
        return method.invoke(null, raw, channelName, outputPlan, spatialConfig);
    }

    private static Object fieldValue(Object target, String fieldName) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static ResultsTable tableFor(Object totalTables,
                                         IntensitySpatialOutputKey key) throws Exception {
        Method method = totalTables.getClass().getDeclaredMethod("table",
                IntensitySpatialOutputKey.class);
        method.setAccessible(true);
        return (ResultsTable) method.invoke(totalTables, key);
    }

    private static void setIntensitySpatialConfigForTest(IntensityAnalysisV2 analysis,
                                                         IntensitySpatialConfig spatial) throws Exception {
        java.lang.reflect.Field field = IntensityAnalysisV2.class.getDeclaredField("intensitySpatialConfig");
        field.setAccessible(true);
        field.set(analysis, spatial);
    }

    private static void invokeRunIntensityMeasurementsForThisImage(
            IntensityAnalysisV2 analysis,
            NameParts parts,
            ImagePlus[] chans,
            int n,
            boolean[] binarization,
            String[] thresholds,
            String[] channelNames,
            int roiChannelIndex1Based,
            IntensityAnalysisV2.IntensityOutputPlan outputPlan,
            Object totalTables,
            int scnIndex1Based,
            String roiSetName,
            BinConfig cfg,
            String[] filterSources,
            File binDir,
            String basicFilterMacro,
            Roi roi) throws Exception {
        invokeRunIntensityMeasurementsForThisImage(analysis, parts, chans, n,
                binarization, thresholds, channelNames, roiChannelIndex1Based,
                outputPlan, totalTables, scnIndex1Based, roiSetName, 1,
                cfg, filterSources, binDir, basicFilterMacro, roi);
    }

    private static void invokeRunIntensityMeasurementsForThisImage(
            IntensityAnalysisV2 analysis,
            NameParts parts,
            ImagePlus[] chans,
            int n,
            boolean[] binarization,
            String[] thresholds,
            String[] channelNames,
            int roiChannelIndex1Based,
            IntensityAnalysisV2.IntensityOutputPlan outputPlan,
            Object totalTables,
            int scnIndex1Based,
            String roiSetName,
            int totalImages,
            BinConfig cfg,
            String[] filterSources,
            File binDir,
            String basicFilterMacro,
            Roi roi) throws Exception {
        invokeRunIntensityMeasurementsForThisImage(analysis, parts, chans, n,
                binarization, thresholds, channelNames, roiChannelIndex1Based,
                outputPlan, totalTables, scnIndex1Based, roiSetName, totalImages, 1,
                cfg, filterSources, binDir, basicFilterMacro, roi);
    }

    private static void invokeRunIntensityMeasurementsForThisImage(
            IntensityAnalysisV2 analysis,
            NameParts parts,
            ImagePlus[] chans,
            int n,
            boolean[] binarization,
            String[] thresholds,
            String[] channelNames,
            int roiChannelIndex1Based,
            IntensityAnalysisV2.IntensityOutputPlan outputPlan,
            Object totalTables,
            int scnIndex1Based,
            String roiSetName,
            int totalImages,
            int firstZSlice,
            BinConfig cfg,
            String[] filterSources,
            File binDir,
            String basicFilterMacro,
            Roi roi) throws Exception {
        Class<?> outputTablesType = Class.forName(
                "flash.pipeline.analyses.IntensityAnalysisV2$IntensityOutputTables");
        Method method = IntensityAnalysisV2.class.getDeclaredMethod(
                "runIntensityMeasurementsForThisImage",
                NameParts.class,
                ImagePlus[].class,
                int.class,
                boolean[].class,
                String[].class,
                String[].class,
                int.class,
                IntensityAnalysisV2.IntensityOutputPlan.class,
                outputTablesType,
                int.class,
                String.class,
                int.class,
                int.class,
                BinConfig.class,
                String[].class,
                File.class,
                String.class,
                Roi.class);
        method.setAccessible(true);
        method.invoke(analysis, parts, chans, Integer.valueOf(n), binarization,
                thresholds, channelNames, Integer.valueOf(roiChannelIndex1Based),
                outputPlan, totalTables, Integer.valueOf(scnIndex1Based), roiSetName,
                Integer.valueOf(totalImages), Integer.valueOf(firstZSlice),
                cfg, filterSources, binDir, basicFilterMacro, roi);
    }

    private static void writeChannelNamesOnlyConfig(File dir, String... names) throws Exception {
        ChannelConfig cfg = new ChannelConfig();
        for (int i = 0; i < names.length; i++) {
            ChannelConfig.Channel channel = new ChannelConfig.Channel();
            channel.index = i;
            channel.name = names[i];
            channel.status.put(ChannelConfig.P_NAME, ChannelConfig.PropertyStatus.COMMITTED);
            cfg.channels.add(channel);
        }
        ChannelConfigIO.write(TestConfigFiles.settingsDir(dir), cfg);
    }

    private interface InvocationResult {
        Object invoke(Method method, Object[] args);
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
