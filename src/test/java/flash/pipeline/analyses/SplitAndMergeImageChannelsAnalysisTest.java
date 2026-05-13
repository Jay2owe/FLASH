package flash.pipeline.analyses;

import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.bin.BinField;
import flash.pipeline.bin.BinSetupChooser;
import flash.pipeline.bin.BinSetupDispatcher;
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.DependencyService;
import flash.pipeline.runtime.DependencyStatus;
import flash.pipeline.runtime.FeatureDependencyGate;
import flash.pipeline.io.AsyncImageSaver;
import flash.pipeline.naming.NameParts;
import ij.ImagePlus;
import ij.ImageStack;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.JComboBox;
import java.io.File;
import java.awt.Color;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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
                new File(new File(new File(dir, "FLASH"), "Set Up Configuration"), ".settings"), "Channel_Data.txt");
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
                new File(new File(new File(dir, "FLASH"), "Set Up Configuration"), ".settings"), "Channel_Data.txt");
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
        File splitRoot = new File(new File(dir, "FLASH"), "Presentation-Ready Images");

        assertEquals(new File(splitRoot, "Images"),
                SplitAndMergeImageChannelsAnalysis.splitMergeImageWriteRoot(dir.getAbsolutePath()));
        assertEquals(new File(splitRoot, "OME-TIFF"),
                SplitAndMergeImageChannelsAnalysis.splitMergeOmeTiffWriteRoot(dir.getAbsolutePath()));
        assertEquals(new File(splitRoot, "Analysis Details"),
                SplitAndMergeImageChannelsAnalysis.splitMergeDetailsWriteRoot(dir.getAbsolutePath()));
    }

    @Test
    public void splitMergeSkipExistingFindsLegacyPresentationFolders() throws Exception {
        File dir = temp.newFolder("legacySplitMerge");
        NameParts parts = new NameParts("Experiment", "Animal1", "LH", "Cortex");
        File primaryOutRoot = SplitAndMergeImageChannelsAnalysis.splitMergeImageWriteRoot(dir.getAbsolutePath());

        File oldPresentationAnimalDir = new File(
                new File(new File(new File(dir, "FLASH"), "Make Presentation-Ready Images"), "Images"),
                "Animal1");
        assertTrue(oldPresentationAnimalDir.mkdirs());
        assertTrue(new File(oldPresentationAnimalDir, "DAPI_LH_Cortex.png").createNewFile());
        assertTrue(SplitAndMergeImageChannelsAnalysis.splitMergePrimaryChannelOutputExists(
                dir.getAbsolutePath(), primaryOutRoot, parts, "DAPI"));

        File numberedAnimalDir = new File(
                new File(new File(new File(dir, "FLASH"), "03 - Split and Merge"), "Images"),
                "Animal1");
        assertTrue(numberedAnimalDir.mkdirs());
        assertTrue(new File(numberedAnimalDir, "GFAP_LH_Cortex.png").createNewFile());
        assertTrue(SplitAndMergeImageChannelsAnalysis.splitMergePrimaryChannelOutputExists(
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
    public void saveSaturationsWritesToActiveConfigurationFolder() throws Exception {
        File dir = temp.newFolder("saturations");

        invokeSaveSaturations(new SplitAndMergeImageChannelsAnalysis(), dir,
                new String[]{"DAPI", "GFAP"},
                new String[]{"Automatic", "None"},
                new double[]{0.35, 0.5});

        File saturationFile = new File(
                new File(new File(new File(dir, "FLASH"), "Set Up Configuration"), ".settings"), "Saturations.txt");
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
                NameParts.class);
        method.setAccessible(true);
        method.invoke(analysis, imp, channelNames, channelColors, outDir, tifDir, detailsDir,
                createMerge, saveOmeTiff, false, -1, new boolean[channelNames.length], "",
                fill(channelNames.length, "None"), fill(channelNames.length, "None"),
                fill(channelNames.length, 0.35), parts);
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
                NameParts.class);
        method.setAccessible(true);
        method.invoke(analysis, imp, channelNames, channelColors, outDir, tifDir, detailsDir,
                createMerge, saveOmeTiff, subtractBackground, backgroundIndex, subtractFromChannels, "",
                fill(channelNames.length, "None"), fill(channelNames.length, "None"),
                fill(channelNames.length, 0.35), parts);
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
