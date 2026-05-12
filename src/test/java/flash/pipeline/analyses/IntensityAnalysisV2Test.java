package flash.pipeline.analyses;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinField;
import flash.pipeline.bin.BinSetupChooser;
import flash.pipeline.bin.BinSetupDispatcher;
import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import flash.pipeline.intensity.spatial.IntensitySpatialOutputKey;
import flash.pipeline.intensity.spatial.IntensitySpatialOutputMode;
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.DependencyService;
import flash.pipeline.runtime.DependencyStatus;
import flash.pipeline.runtime.FeatureDependencyGate;
import ij.measure.ResultsTable;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Constructor;
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
import static org.junit.Assert.assertTrue;

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
        assertFalse(new File(dir, "Data Analysis/ROI Intensities").exists());
        assertFalse(new File(dir, "FLASH/Image Analysis/Image Intensities").exists());
    }

    @Test
    public void intensityPathsUseFlashOutputAndLegacyReadFallback() throws Exception {
        File dir = temp.newFolder("intensityPaths");

        File writeRoot = IntensityAnalysisV2.intensityWriteRoot(dir.getAbsolutePath());
        List<File> readRoots = IntensityAnalysisV2.intensityReadRoots(dir.getAbsolutePath());

        assertEquals(new File(dir, "FLASH/Image Analysis/Image Intensities").getAbsolutePath(),
                writeRoot.getAbsolutePath());
        assertEquals(new File(dir, "FLASH/Image Analysis/Image Intensities").getAbsolutePath(),
                readRoots.get(0).getAbsolutePath());
        assertEquals(new File(dir, "FLASH/04 - Fluorescence Intensity").getAbsolutePath(),
                readRoots.get(1).getAbsolutePath());
        assertEquals(new File(dir, "Data Analysis/ROI Intensities").getAbsolutePath(),
                readRoots.get(2).getAbsolutePath());
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
                .mipEnabled(true)
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

        assertEquals(Arrays.asList("Region", "Hemisphere", "ROI", "Animal Name",
                "IntDen", "IntDen_binarized", "%Area", "%Area_binarized",
                "IntDen_Unfiltered"),
                columns.subList(0, 9));
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
    public void completeRequiredBinCompletesWithoutChooser() throws Exception {
        File dir = temp.newFolder("complete");
        writeChannelData(dir, "DAPI GFAP", "", "", "", "", "10 20", "", "", "zslice:full");
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
    public void channelNamesOnlyBinPromptsOnlyForIntensityThresholdsAndZSlice() throws Exception {
        File dir = temp.newFolder("partial");
        writeChannelData(dir, "DAPI GFAP");
        final AtomicReference<Set<BinField>> missingFields = new AtomicReference<Set<BinField>>();
        installDispatcherChoice(BinSetupChooser.Choice.CANCELLED, new AtomicInteger(0),
                missingFields);

        IntensityAnalysisV2 analysis = new IntensityAnalysisV2();
        BinSetupDispatcher.Outcome outcome = BinSetupDispatcher.ensure(
                dir.getAbsolutePath(), "Intensity Analysis",
                analysis.requiredBinFields(), analysis.benefitsFromRois());

        assertEquals(BinSetupDispatcher.Outcome.CANCELLED, outcome);
        assertEquals(EnumSet.of(BinField.INTENSITY_THRESHOLDS, BinField.Z_SLICE),
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

    private static void writeChannelData(File dir, String... lines) throws Exception {
        File bin = new File(dir, ".bin");
        assertTrue(bin.mkdirs());
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            content.append(lines[i]).append("\n");
        }
        Files.write(new File(bin, "Channel_Data.txt").toPath(),
                content.toString().getBytes(StandardCharsets.UTF_8));
    }

    private interface InvocationResult {
        Object invoke(Method method, Object[] args);
    }
}
