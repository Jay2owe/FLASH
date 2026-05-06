package flash.pipeline.analyses;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinField;
import flash.pipeline.bin.BinSetupChooser;
import flash.pipeline.bin.BinSetupDispatcher;
import flash.pipeline.zslice.ZSliceMode;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
        writeChannelData(dir, "DAPI GFAP", "", "", "", "", "", "", "", "zslice:full");
        AtomicInteger chooserCalls = new AtomicInteger(0);
        installDispatcherChoice(BinSetupChooser.Choice.CANCELLED, chooserCalls);

        DrawAndSaveROIsAnalysis analysis = new DrawAndSaveROIsAnalysis();
        BinSetupDispatcher.Outcome outcome = BinSetupDispatcher.ensure(
                dir.getAbsolutePath(), "Draw and Save ROIs",
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
    public void shouldShowZSliceSourceChoice_onlyForSubsetModes() {
        BinConfig cfg = new BinConfig();
        cfg.zSliceMode = ZSliceMode.FULL;
        assertFalse(DrawAndSaveROIsAnalysis.shouldShowZSliceSourceChoice(cfg));

        cfg.zSliceMode = ZSliceMode.SAME_COUNT;
        assertTrue(DrawAndSaveROIsAnalysis.shouldShowZSliceSourceChoice(cfg));
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
