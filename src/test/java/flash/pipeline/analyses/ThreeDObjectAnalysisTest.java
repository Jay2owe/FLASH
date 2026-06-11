package flash.pipeline.analyses;

import flash.pipeline.TestConfigFiles;
import flash.pipeline.bin.BinField;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinSetupChooser;
import flash.pipeline.bin.BinSetupDispatcher;
import flash.pipeline.bin.ChannelConfig;
import flash.pipeline.bin.ChannelConfigIO;
import flash.pipeline.bin.ChannelIdentities;
import flash.pipeline.analyses.wizard.SpatialSetupConfig;
import flash.pipeline.analyses.wizard.ThreeDObjectPreset;
import flash.pipeline.analyses.wizard.ThreeDObjectPresetIO;
import flash.pipeline.analyses.wizard.ThreeDObjectSetupConfig;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.roi.RoiIO;
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.DependencyService;
import flash.pipeline.runtime.DependencyStatus;
import flash.pipeline.runtime.FeatureDependencyGate;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ThreeDObjectAnalysisTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @After
    public void resetHooks() throws Exception {
        invokeDispatcherReset();
        FeatureDependencyGate.configure(new DependencyService(), null);
        FeatureDependencyGate.setUiMode(false);
    }

    @Test
    public void declaresThreeDObjectBinRequirementsAndRoiBenefit() {
        ThreeDObjectAnalysis analysis = new ThreeDObjectAnalysis();

        assertEquals(EnumSet.of(
                BinField.CHANNEL_NAMES,
                BinField.CHANNEL_COLORS,
                BinField.OBJECT_THRESHOLDS,
                BinField.PARTICLE_SIZES,
                BinField.SEGMENTATION_METHODS,
                BinField.FILTER_PRESETS,
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

        ThreeDObjectAnalysis analysis = new ThreeDObjectAnalysis();
        analysis.setNoRoiDecisionPromptForTest(new ThreeDObjectAnalysis.NoRoiDecisionPrompt() {
            @Override
            public ThreeDObjectAnalysis.NoRoiDecision choose() {
                return ThreeDObjectAnalysis.NoRoiDecision.ANALYSE_FULL_IMAGE;
            }
        });
        analysis.execute(dir.getAbsolutePath());

        assertEquals(1, chooserCalls.get());
        assertFalse(new File(dir, "Data Analysis").exists());
    }

    @Test
    public void drawRoisHandoffContinuesToThreeDConfigurationChooser() throws Exception {
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
                        assertEquals("3D Object Analysis", args[0]);
                        return BinSetupChooser.Choice.CANCELLED;
                    }
                });

        ThreeDObjectAnalysis analysis = new ThreeDObjectAnalysis();
        analysis.setNoRoiDecisionPromptForTest(new ThreeDObjectAnalysis.NoRoiDecisionPrompt() {
            @Override
            public ThreeDObjectAnalysis.NoRoiDecision choose() {
                assertEquals(0, stage.getAndIncrement());
                return ThreeDObjectAnalysis.NoRoiDecision.DRAW_ROIS;
            }
        });
        analysis.setRoiDrawingWorkflowLauncherForTest(new ThreeDObjectAnalysis.RoiDrawingWorkflowLauncher() {
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
    public void emptyFolderFullSetupRunsWizardWithAllBinFields() throws Exception {
        File dir = temp.newFolder("full");
        installDispatcherChoice(BinSetupChooser.Choice.FULL, new AtomicInteger(0));
        final AtomicReference<Set<BinField>> wizardFields = new AtomicReference<Set<BinField>>();
        installWizardRunner(wizardFields, false);

        ThreeDObjectAnalysis analysis = new ThreeDObjectAnalysis();
        BinSetupDispatcher.Outcome outcome = BinSetupDispatcher.ensure(
                dir.getAbsolutePath(), "3D Object Analysis",
                analysis.requiredBinFields(), analysis.benefitsFromRois());

        assertEquals(BinSetupDispatcher.Outcome.COMPLETED, outcome);
        assertEquals(BinField.all(), wizardFields.get());
    }

    @Test
    public void intensityPopulatedBinPartialSetupSkipsExistingNamesAndZSliceButIncludesFilterPresets() throws Exception {
        File dir = temp.newFolder("partial");
        writeNamesAndIntensityConfig(dir, "DAPI", "GFAP");
        installDispatcherChoice(BinSetupChooser.Choice.PARTIAL, new AtomicInteger(0));
        final AtomicReference<Set<BinField>> wizardFields = new AtomicReference<Set<BinField>>();
        installWizardRunner(wizardFields, true);

        ThreeDObjectAnalysis analysis = new ThreeDObjectAnalysis();
        BinSetupDispatcher.Outcome outcome = BinSetupDispatcher.ensure(
                dir.getAbsolutePath(), "3D Object Analysis",
                analysis.requiredBinFields(), analysis.benefitsFromRois());

        assertEquals(BinSetupDispatcher.Outcome.COMPLETED, outcome);
        assertEquals(EnumSet.of(
                BinField.CHANNEL_COLORS,
                BinField.OBJECT_THRESHOLDS,
                BinField.PARTICLE_SIZES,
                BinField.SEGMENTATION_METHODS,
                BinField.FILTER_PRESETS),
                wizardFields.get());
        File settingsDir = TestConfigFiles.settingsDir(dir);
        assertTrue(new File(settingsDir, "C1_Filters.ijm").isFile());
        assertTrue(new File(settingsDir, "C2_Filters.ijm").isFile());
    }

    @Test
    public void dialogCentroidRoiFilteringDefaultsOnWithoutPreset() throws Exception {
        ThreeDObjectAnalysis analysis = new ThreeDObjectAnalysis();

        assertTrue(classicalCentroidFilter(analysis));
    }

    @Test
    public void applyingEveryStockPresetLeavesDialogCentroidRoiFilteringOn() throws Exception {
        BinConfig cfg = dapiIba1AbetaConfig();
        ChannelIdentities identities = dapiIba1AbetaIdentities();
        ThreeDObjectPresetIO io = new ThreeDObjectPresetIO(temp.newFolder("analysis-stock-presets"));
        List<ThreeDObjectPreset> presets = io.listAll();

        assertEquals(6, presets.size());
        for (ThreeDObjectPreset preset : presets) {
            ThreeDObjectAnalysis analysis = new ThreeDObjectAnalysis();
            ThreeDObjectSetupConfig.DerivedConfig derived = ThreeDObjectSetupConfig.fromPreset(cfg, identities, preset);

            applyThreeDObjectDerivedConfig(analysis, cfg, derived);

            assertTrue(preset.getName(), classicalCentroidFilter(analysis));
        }
    }

    @Test
    public void applyingExplicitPresetWithCentroidRoiFilteringOffIsRespected() throws Exception {
        BinConfig cfg = dapiIba1AbetaConfig();
        ThreeDObjectPreset preset = new ThreeDObjectPreset(
                "User preset",
                "Explicitly disables centroid ROI filtering",
                ThreeDObjectPreset.CURRENT_LIBRARY_VERSION,
                false,
                false,
                false,
                false,
                false,
                30.0,
                null,
                null);
        ThreeDObjectAnalysis analysis = new ThreeDObjectAnalysis();
        ThreeDObjectSetupConfig.DerivedConfig derived = ThreeDObjectSetupConfig.fromPreset(
                cfg, dapiIba1AbetaIdentities(), preset);

        applyThreeDObjectDerivedConfig(analysis, cfg, derived);

        assertFalse(classicalCentroidFilter(analysis));
    }

    @Test
    public void interactiveSpatialHandoffLaunchesFullSpatialOptionsAndStoresConfig() throws Exception {
        ThreeDObjectAnalysis analysis = new ThreeDObjectAnalysis();
        analysis.setGuiDecisionDialogsAvailableForTest(Boolean.TRUE);
        final SpatialSetupConfig.DerivedConfig expected = new SpatialSetupConfig.DerivedConfig();
        expected.doHeatmaps = true;
        final AtomicInteger launches = new AtomicInteger(0);
        final AtomicReference<List<String>> launchedChannels = new AtomicReference<List<String>>();
        final AtomicReference<Map<String, Double>> thresholds = new AtomicReference<Map<String, Double>>();
        final AtomicReference<Boolean> lockVolumetric = new AtomicReference<Boolean>();
        final AtomicReference<Boolean> lockCpc = new AtomicReference<Boolean>();
        setMarkerThresholds(analysis, singletonThresholds());
        analysis.setSpatialOptionsDialogLauncherForTest(new ThreeDObjectAnalysis.SpatialOptionsDialogLauncher() {
            @Override
            public SpatialSetupConfig.DerivedConfig launch(String directory,
                                                              List<String> channelNames,
                                                              Map<String, Double> markerThresholds,
                                                              Map<String, Double> bbThresholds,
                                                              boolean lockVolumetricColoc,
                                                              boolean lockCpcColoc,
                                                              boolean lockBBOverlap,
                                                              boolean lockBBCpc,
                                                              boolean lockBBVol) {
                launches.incrementAndGet();
                launchedChannels.set(channelNames);
                thresholds.set(markerThresholds);
                lockVolumetric.set(Boolean.valueOf(lockVolumetricColoc));
                lockCpc.set(Boolean.valueOf(lockCpcColoc));
                return expected;
            }
        });

        assertTrue(analysis.prepareSpatialHandoffBeforeAnalysis(
                temp.newFolder("spatial-handoff").getAbsolutePath(),
                dapiIba1AbetaConfig().channelNames,
                true));

        assertEquals(1, launches.get());
        assertEquals(dapiIba1AbetaConfig().channelNames, launchedChannels.get());
        assertEquals(singletonThresholds(), thresholds.get());
        assertEquals(Boolean.TRUE, lockVolumetric.get());
        assertEquals(Boolean.TRUE, lockCpc.get());
        assertSame(expected, fieldValue(analysis, "wizardSpatialConfig"));
    }

    @Test
    public void interactiveSpatialHandoffPassesOnlySelectedObjectColocalizationLocks() throws Exception {
        ThreeDObjectAnalysis analysis = new ThreeDObjectAnalysis();
        analysis.setGuiDecisionDialogsAvailableForTest(Boolean.TRUE);
        setField(analysis, "doVolumetric", Boolean.FALSE);
        setField(analysis, "doCpc", Boolean.TRUE);
        setField(analysis, "doBBOverlap", Boolean.TRUE);
        setField(analysis, "doBBCpc", Boolean.FALSE);
        setField(analysis, "doBBVol", Boolean.TRUE);
        final AtomicReference<Boolean> lockVolumetric = new AtomicReference<Boolean>();
        final AtomicReference<Boolean> lockCpc = new AtomicReference<Boolean>();
        final AtomicReference<Boolean> lockBBOverlapRef = new AtomicReference<Boolean>();
        final AtomicReference<Boolean> lockBBCpcRef = new AtomicReference<Boolean>();
        final AtomicReference<Boolean> lockBBVolRef = new AtomicReference<Boolean>();
        analysis.setSpatialOptionsDialogLauncherForTest(new ThreeDObjectAnalysis.SpatialOptionsDialogLauncher() {
            @Override
            public SpatialSetupConfig.DerivedConfig launch(String directory,
                                                              List<String> channelNames,
                                                              Map<String, Double> markerThresholds,
                                                              Map<String, Double> bbThresholds,
                                                              boolean lockVolumetricColoc,
                                                              boolean lockCpcColoc,
                                                              boolean lockBBOverlap,
                                                              boolean lockBBCpc,
                                                              boolean lockBBVol) {
                lockVolumetric.set(Boolean.valueOf(lockVolumetricColoc));
                lockCpc.set(Boolean.valueOf(lockCpcColoc));
                lockBBOverlapRef.set(Boolean.valueOf(lockBBOverlap));
                lockBBCpcRef.set(Boolean.valueOf(lockBBCpc));
                lockBBVolRef.set(Boolean.valueOf(lockBBVol));
                return new SpatialSetupConfig.DerivedConfig();
            }
        });

        assertTrue(analysis.prepareSpatialHandoffBeforeAnalysis(
                temp.newFolder("spatial-locks").getAbsolutePath(),
                dapiIba1AbetaConfig().channelNames,
                true));

        assertEquals(Boolean.FALSE, lockVolumetric.get());
        assertEquals(Boolean.TRUE, lockCpc.get());
        // Each BB toggle ticked in 3D Object Analysis is forwarded as a lock to the chained Spatial dialog.
        assertEquals(Boolean.TRUE, lockBBOverlapRef.get());
        assertEquals(Boolean.FALSE, lockBBCpcRef.get());
        assertEquals(Boolean.TRUE, lockBBVolRef.get());
    }

    @Test
    public void cancelledSpatialOptionsCancelsObjectAnalysisBeforeProcessing() throws Exception {
        ThreeDObjectAnalysis analysis = new ThreeDObjectAnalysis();
        analysis.setGuiDecisionDialogsAvailableForTest(Boolean.TRUE);
        final AtomicInteger launches = new AtomicInteger(0);
        analysis.setSpatialOptionsDialogLauncherForTest(new ThreeDObjectAnalysis.SpatialOptionsDialogLauncher() {
            @Override
            public SpatialSetupConfig.DerivedConfig launch(String directory,
                                                              List<String> channelNames,
                                                              Map<String, Double> markerThresholds,
                                                              Map<String, Double> bbThresholds,
                                                              boolean lockVolumetricColoc,
                                                              boolean lockCpcColoc,
                                                              boolean lockBBOverlap,
                                                              boolean lockBBCpc,
                                                              boolean lockBBVol) {
                launches.incrementAndGet();
                return null;
            }
        });

        assertFalse(analysis.prepareSpatialHandoffBeforeAnalysis(
                temp.newFolder("spatial-cancel").getAbsolutePath(),
                dapiIba1AbetaConfig().channelNames,
                true));

        assertEquals(1, launches.get());
        assertNull(fieldValue(analysis, "wizardSpatialConfig"));
    }

    @Test
    public void hideImageWindowsStillLaunchesInteractiveSpatialHandoffBeforeProcessing() throws Exception {
        ThreeDObjectAnalysis analysis = new ThreeDObjectAnalysis();
        analysis.setHeadless(true);
        analysis.setGuiDecisionDialogsAvailableForTest(Boolean.TRUE);
        final AtomicInteger launches = new AtomicInteger(0);
        analysis.setSpatialOptionsDialogLauncherForTest(countingSpatialOptionsLauncher(launches));

        assertTrue(analysis.prepareSpatialHandoffBeforeAnalysis(
                temp.newFolder("spatial-image-headless").getAbsolutePath(),
                dapiIba1AbetaConfig().channelNames,
                true));

        assertEquals(1, launches.get());
        SpatialAnalysis spatial = analysis.createSpatialAnalysisForRun();
        assertTrue(booleanField(spatial, "headless"));
        assertFalse(booleanField(spatial, "suppressDialogs"));
    }

    @Test
    public void hideImageWindowsStillAllowsGuiDecisionDialogs() {
        assertTrue(ThreeDObjectAnalysis.canShowGuiDecisionDialog(false, null, false, true));
        assertFalse(ThreeDObjectAnalysis.canShowGuiDecisionDialog(true, null, false, true));
        assertFalse(ThreeDObjectAnalysis.canShowGuiDecisionDialog(false, new CLIConfig(), false, true));
        assertFalse(ThreeDObjectAnalysis.canShowGuiDecisionDialog(false, null, true, true));
    }

    @Test
    public void noImageJUiSuppressesGuiDecisionDialogsInUnitTests() {
        assertFalse(ThreeDObjectAnalysis.canShowGuiDecisionDialog(false, null, false, false));
    }

    @Test
    public void missingImageJUiSuppressesInteractiveSpatialHandoff() throws Exception {
        ThreeDObjectAnalysis analysis = new ThreeDObjectAnalysis();
        analysis.setGuiDecisionDialogsAvailableForTest(Boolean.FALSE);
        final AtomicInteger launches = new AtomicInteger(0);
        analysis.setSpatialOptionsDialogLauncherForTest(countingSpatialOptionsLauncher(launches));

        assertTrue(analysis.prepareSpatialHandoffBeforeAnalysis(
                temp.newFolder("spatial-no-ui").getAbsolutePath(),
                dapiIba1AbetaConfig().channelNames,
                true));

        assertEquals(0, launches.get());
    }

    @Test
    public void spatialHandoffKeepsNoninteractiveRunsNoninteractive() throws Exception {
        ThreeDObjectAnalysis suppressed = new ThreeDObjectAnalysis();
        final AtomicInteger suppressedLaunches = new AtomicInteger(0);
        suppressed.setSuppressDialogs(true);
        suppressed.setSpatialOptionsDialogLauncherForTest(countingSpatialOptionsLauncher(suppressedLaunches));

        assertTrue(suppressed.prepareSpatialHandoffBeforeAnalysis(
                temp.newFolder("spatial-suppressed").getAbsolutePath(),
                dapiIba1AbetaConfig().channelNames,
                true));
        assertEquals(0, suppressedLaunches.get());
        assertTrue(booleanField(suppressed.createSpatialAnalysisForRun(), "suppressDialogs"));

        ThreeDObjectAnalysis cli = new ThreeDObjectAnalysis();
        final AtomicInteger cliLaunches = new AtomicInteger(0);
        cli.setCliConfig(new CLIConfig());
        cli.setSpatialOptionsDialogLauncherForTest(countingSpatialOptionsLauncher(cliLaunches));

        assertTrue(cli.prepareSpatialHandoffBeforeAnalysis(
                temp.newFolder("spatial-cli").getAbsolutePath(),
                dapiIba1AbetaConfig().channelNames,
                true));
        assertEquals(0, cliLaunches.get());
        assertTrue(booleanField(cli.createSpatialAnalysisForRun(), "suppressDialogs"));
    }

    @Test
    public void chainedSpatialAnalysisReceivesStoredConfigAndNoninteractiveMode() throws Exception {
        ThreeDObjectAnalysis analysis = new ThreeDObjectAnalysis();
        analysis.setSuppressDialogs(true);
        SpatialSetupConfig.DerivedConfig config = new SpatialSetupConfig.DerivedConfig();
        config.doDistances = true;
        setField(analysis, "wizardSpatialConfig", config);

        SpatialAnalysis spatial = analysis.createSpatialAnalysisForRun();

        assertSame(config, fieldValue(spatial, "configuredOptions"));
        assertTrue(booleanField(spatial, "suppressDialogs"));

        ThreeDObjectAnalysis cli = new ThreeDObjectAnalysis();
        cli.setCliConfig(new CLIConfig());
        SpatialAnalysis cliSpatial = cli.createSpatialAnalysisForRun();

        assertTrue(booleanField(cliSpatial, "suppressDialogs"));
    }

    @Test
    public void fullImageRoiSetDoesNotRequireSavedRoiPairs() throws Exception {
        ThreeDObjectAnalysis analysis = new ThreeDObjectAnalysis();

        assertTrue(validateRoiSets(analysis, fullImageRoiSets(), 3));
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

    private static void installWizardRunner(final AtomicReference<Set<BinField>> wizardFields,
                                            final boolean writeFilterMacros) throws Exception {
        setDispatcherHook("setWizardRunnerForTest",
                "flash.pipeline.bin.BinSetupDispatcher$WizardRunner",
                new InvocationResult() {
                    @SuppressWarnings("unchecked")
                    @Override public Object invoke(Method method, Object[] args) throws Exception {
                        Set<BinField> fields = (Set<BinField>) args[1];
                        wizardFields.set(fields);
                        File projectRoot = new File((String) args[0]);
                        TestConfigFiles.writeChannelConfig(projectRoot,
                                TestConfigFiles.basicBinConfig("DAPI", "GFAP"));
                        if (writeFilterMacros && fields.contains(BinField.FILTER_PRESETS)) {
                            File bin = TestConfigFiles.settingsDir(projectRoot);
                            assertTrue(bin.isDirectory() || bin.mkdirs());
                            Files.write(new File(bin, "C1_Filters.ijm").toPath(),
                                    "run(\"Median...\", \"radius=2 stack\");\n".getBytes(StandardCharsets.UTF_8));
                            Files.write(new File(bin, "C2_Filters.ijm").toPath(),
                                    "run(\"Median...\", \"radius=2 stack\");\n".getBytes(StandardCharsets.UTF_8));
                        }
                        return null;
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

    private static ThreeDObjectAnalysis.SpatialOptionsDialogLauncher countingSpatialOptionsLauncher(
            final AtomicInteger launches) {
        return new ThreeDObjectAnalysis.SpatialOptionsDialogLauncher() {
            @Override
            public SpatialSetupConfig.DerivedConfig launch(String directory,
                                                              List<String> channelNames,
                                                              Map<String, Double> markerThresholds,
                                                              Map<String, Double> bbThresholds,
                                                              boolean lockVolumetricColoc,
                                                              boolean lockCpcColoc,
                                                              boolean lockBBOverlap,
                                                              boolean lockBBCpc,
                                                              boolean lockBBVol) {
                launches.incrementAndGet();
                return new SpatialSetupConfig.DerivedConfig();
            }
        };
    }

    private static Map<String, Double> singletonThresholds() {
        Map<String, Double> thresholds = new LinkedHashMap<String, Double>();
        thresholds.put("DAPI", Double.valueOf(30.0));
        return thresholds;
    }

    private static void setMarkerThresholds(ThreeDObjectAnalysis analysis,
                                            Map<String, Double> thresholds) throws Exception {
        setField(analysis, "markerThresholds", thresholds);
    }

    private static Object fieldValue(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static boolean booleanField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static boolean classicalCentroidFilter(ThreeDObjectAnalysis analysis) throws Exception {
        Field field = ThreeDObjectAnalysis.class.getDeclaredField("classicalCentroidFilter");
        field.setAccessible(true);
        return field.getBoolean(analysis);
    }

    private static void applyThreeDObjectDerivedConfig(ThreeDObjectAnalysis analysis,
                                                       BinConfig cfg,
                                                       ThreeDObjectSetupConfig.DerivedConfig derived) throws Exception {
        Method method = ThreeDObjectAnalysis.class.getDeclaredMethod(
                "applyThreeDObjectDerivedConfig",
                BinConfig.class,
                ThreeDObjectSetupConfig.DerivedConfig.class);
        method.setAccessible(true);
        method.invoke(analysis, cfg, derived);
    }

    private static Object fullImageRoiSets() throws Exception {
        Class<?> roiSetType = Class.forName("flash.pipeline.analyses.ThreeDObjectAnalysis$RoiSetData");
        Method fullImage = roiSetType.getDeclaredMethod("fullImage");
        fullImage.setAccessible(true);
        Object roiSet = fullImage.invoke(null);
        Object array = Array.newInstance(roiSetType, 1);
        Array.set(array, 0, roiSet);
        return array;
    }

    private static boolean validateRoiSets(ThreeDObjectAnalysis analysis,
                                           Object roiSets,
                                           int totalImages) throws Exception {
        Method method = ThreeDObjectAnalysis.class.getDeclaredMethod(
                "validateRoiSets", roiSets.getClass(), int.class);
        method.setAccessible(true);
        return ((Boolean) method.invoke(analysis, roiSets, totalImages)).booleanValue();
    }

    private static BinConfig dapiIba1AbetaConfig() {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("DAPI");
        cfg.channelNames.add("IBA1");
        cfg.channelNames.add("Abeta");
        return cfg;
    }

    private static ChannelIdentities dapiIba1AbetaIdentities() {
        return new ChannelIdentities(Arrays.asList(
                new ChannelIdentities.Entry(0, "nuclei_dapi", "round", false),
                new ChannelIdentities.Entry(1, "microglia_iba1", "complex", true),
                new ChannelIdentities.Entry(2, "amyloid_abeta_pan", "puncta_like", false)));
    }

    private static void writeNamesAndIntensityConfig(File dir, String... names) throws Exception {
        ChannelConfig cfg = new ChannelConfig();
        for (int i = 0; i < names.length; i++) {
            ChannelConfig.Channel channel = new ChannelConfig.Channel();
            channel.index = i;
            channel.name = names[i];
            channel.intensityThreshold = i == 0 ? "10" : "20";
            channel.status.put(ChannelConfig.P_NAME, ChannelConfig.PropertyStatus.COMMITTED);
            channel.status.put(ChannelConfig.P_INTENSITY, ChannelConfig.PropertyStatus.COMMITTED);
            cfg.channels.add(channel);
        }
        ChannelConfigIO.write(TestConfigFiles.settingsDir(dir), cfg);
    }

    private interface InvocationResult {
        Object invoke(Method method, Object[] args) throws Exception;
    }
}
