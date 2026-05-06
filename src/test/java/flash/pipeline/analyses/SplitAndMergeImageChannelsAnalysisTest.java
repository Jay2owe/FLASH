package flash.pipeline.analyses;

import flash.pipeline.bin.BinConfigIO;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SplitAndMergeImageChannelsAnalysisTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @After
    public void resetHooks() throws Exception {
        invokeDispatcherReset();
        FeatureDependencyGate.configure(new DependencyService(), null);
        FeatureDependencyGate.setUiMode(false);
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
        assertFalse(SplitAndMergeImageChannelsAnalysis.splitMergeWriteRoot(dir.getAbsolutePath()).exists());
    }

    @Test
    public void completeRequiredBinCompletesWithoutChooser() throws Exception {
        File dir = temp.newFolder("complete");
        writeChannelData(dir,
                "DAPI GFAP",
                "Blue Green",
                "",
                "",
                "None 0-4095",
                "",
                "",
                "",
                "zslice:full");
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
    public void splitMergeMinMaxWritebackPreservesPartialSetupLines() throws Exception {
        File dir = temp.newFolder("partialOrigin");
        writeChannelData(dir,
                "DAPI GFAP",
                "Blue Green",
                "default default",
                "100-Infinity 100-Infinity",
                "None None",
                "default default",
                "classical classical",
                "default default",
                "zslice:full");

        invokeUpdateBinMinMax(new SplitAndMergeImageChannelsAnalysis(), dir,
                new String[]{"Custom Min-Max Display Ranges", "None"},
                new String[]{"10-200", "50-4000"},
                2);

        File activeChannelData = new File(
                new File(new File(dir, "FLASH"), "00 - Configuration"), "Channel_Data.txt");
        List<String> lines = Files.readAllLines(activeChannelData.toPath());
        assertEquals("DAPI GFAP", lines.get(0));
        assertEquals("Blue Green", lines.get(1));
        assertEquals("default default", lines.get(2));
        assertEquals("100-Infinity 100-Infinity", lines.get(3));
        // updateMinMax overwrites only line 5 with the new tab delimiter; other
        // lines stay in whatever delimiter they had on disk.
        assertEquals("10-200\tNone", lines.get(4));
        assertEquals("default default", lines.get(5));
        assertEquals("classical classical", lines.get(6));
        assertEquals("default default", lines.get(7));
        assertEquals("zslice:full", lines.get(8));
    }

    @Test
    public void minMaxWritebackPadsShortPartialSetupFileToLineFive() throws Exception {
        File dir = temp.newFolder("shortPartial");
        writeChannelData(dir, "DAPI GFAP", "Blue Green");

        BinConfigIO.updateMinMax(dir.getAbsolutePath(), new String[]{"10-200", "50-4000"});

        File activeChannelData = new File(
                new File(new File(dir, "FLASH"), "00 - Configuration"), "Channel_Data.txt");
        List<String> lines = Files.readAllLines(activeChannelData.toPath());
        assertEquals(5, lines.size());
        assertEquals("DAPI GFAP", lines.get(0));
        assertEquals("Blue Green", lines.get(1));
        assertEquals("", lines.get(2));
        assertEquals("", lines.get(3));
        assertEquals("10-200\t50-4000", lines.get(4));
    }

    @Test
    public void splitMergeOutputHelpersUseStageFolderLayout() throws Exception {
        File dir = temp.newFolder("layout");
        File splitRoot = new File(new File(dir, "FLASH"), "03 - Split and Merge");

        assertEquals(new File(splitRoot, "Images"),
                SplitAndMergeImageChannelsAnalysis.splitMergeImageWriteRoot(dir.getAbsolutePath()));
        assertEquals(new File(splitRoot, "OME-TIFF"),
                SplitAndMergeImageChannelsAnalysis.splitMergeOmeTiffWriteRoot(dir.getAbsolutePath()));
        assertEquals(new File(splitRoot, "Analysis Details"),
                SplitAndMergeImageChannelsAnalysis.splitMergeDetailsWriteRoot(dir.getAbsolutePath()));
    }

    @Test
    public void saveSaturationsWritesToActiveConfigurationFolder() throws Exception {
        File dir = temp.newFolder("saturations");

        invokeSaveSaturations(new SplitAndMergeImageChannelsAnalysis(), dir,
                new String[]{"DAPI", "GFAP"},
                new String[]{"Automatic", "None"},
                new double[]{0.35, 0.5});

        File saturationFile = new File(
                new File(new File(dir, "FLASH"), "00 - Configuration"), "Saturations.txt");
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
