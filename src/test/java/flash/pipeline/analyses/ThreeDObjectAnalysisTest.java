package flash.pipeline.analyses;

import flash.pipeline.bin.BinField;
import flash.pipeline.bin.BinSetupChooser;
import flash.pipeline.bin.BinSetupDispatcher;
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
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.EnumMap;
import java.util.EnumSet;
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
        Object invoke(Method method, Object[] args) throws Exception;
    }
}
