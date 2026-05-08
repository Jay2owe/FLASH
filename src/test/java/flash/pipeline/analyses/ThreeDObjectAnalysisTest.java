package flash.pipeline.analyses;

import flash.pipeline.bin.BinField;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinSetupChooser;
import flash.pipeline.bin.BinSetupDispatcher;
import flash.pipeline.bin.ChannelIdentities;
import flash.pipeline.analyses.wizard.ThreeDObjectPreset;
import flash.pipeline.analyses.wizard.ThreeDObjectPresetIO;
import flash.pipeline.analyses.wizard.ThreeDObjectWizard;
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.DependencyService;
import flash.pipeline.runtime.DependencyStatus;
import flash.pipeline.runtime.FeatureDependencyGate;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
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

        new ThreeDObjectAnalysis().execute(dir.getAbsolutePath());

        assertEquals(1, chooserCalls.get());
        assertFalse(new File(dir, "Data Analysis").exists());
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
        writeChannelData(dir,
                "DAPI GFAP",
                "",
                "",
                "",
                "",
                "10 20",
                "",
                "",
                "zslice:full");
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
        assertTrue(new File(new File(dir, ".bin"), "C1_Filters.ijm").isFile());
        assertTrue(new File(new File(dir, ".bin"), "C2_Filters.ijm").isFile());
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
            ThreeDObjectWizard.DerivedConfig derived = ThreeDObjectWizard.fromPreset(cfg, identities, preset);

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
        ThreeDObjectWizard.DerivedConfig derived = ThreeDObjectWizard.fromPreset(
                cfg, dapiIba1AbetaIdentities(), preset);

        applyThreeDObjectDerivedConfig(analysis, cfg, derived);

        assertFalse(classicalCentroidFilter(analysis));
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
                        writeChannelData(new File((String) args[0]),
                                "DAPI GFAP",
                                "Blue Green",
                                "100 200",
                                "50-Infinity 25-500",
                                "None None",
                                "default default",
                                "classical classical",
                                "default default",
                                "zslice:full");
                        if (writeFilterMacros && fields.contains(BinField.FILTER_PRESETS)) {
                            File bin = new File((String) args[0], ".bin");
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

    private static boolean classicalCentroidFilter(ThreeDObjectAnalysis analysis) throws Exception {
        Field field = ThreeDObjectAnalysis.class.getDeclaredField("classicalCentroidFilter");
        field.setAccessible(true);
        return field.getBoolean(analysis);
    }

    private static void applyThreeDObjectDerivedConfig(ThreeDObjectAnalysis analysis,
                                                       BinConfig cfg,
                                                       ThreeDObjectWizard.DerivedConfig derived) throws Exception {
        Method method = ThreeDObjectAnalysis.class.getDeclaredMethod(
                "applyThreeDObjectDerivedConfig",
                BinConfig.class,
                ThreeDObjectWizard.DerivedConfig.class);
        method.setAccessible(true);
        method.invoke(analysis, cfg, derived);
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

    private static void writeChannelData(File dir, String... lines) throws Exception {
        File bin = new File(dir, ".bin");
        assertTrue(bin.isDirectory() || bin.mkdirs());
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            content.append(lines[i]).append("\n");
        }
        Files.write(new File(bin, "Channel_Data.txt").toPath(),
                content.toString().getBytes(StandardCharsets.UTF_8));
    }

    private interface InvocationResult {
        Object invoke(Method method, Object[] args) throws Exception;
    }
}
