package flash.pipeline.analyses;

import flash.pipeline.bin.BinField;
import flash.pipeline.bin.BinSetupChooser;
import flash.pipeline.bin.BinSetupDispatcher;
import flash.pipeline.io.CsvTableIO;
import flash.pipeline.io.FlashProjectLayout;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.io.RoiEncoder;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class LineDistanceAnalysisTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @After
    public void resetDispatcher() throws Exception {
        invokeDispatcherReset();
    }

    @Test
    public void declaresOnlyZSliceAndRoiBenefit() {
        LineDistanceAnalysis analysis = new LineDistanceAnalysis();

        assertEquals(EnumSet.of(BinField.Z_SLICE), analysis.requiredBinFields());
        assertTrue(analysis.benefitsFromRois());
        assertTrue(analysis.requiresHeadedMode());
    }

    @Test
    public void executeReturnsGracefullyWhenDispatcherCancelsMissingBin() throws Exception {
        File dir = temp.newFolder("cancelled");
        installDispatcherChoice(BinSetupChooser.Choice.CANCELLED, new AtomicInteger(0));

        new LineDistanceAnalysis().execute(dir.getAbsolutePath());

        assertFalse(new File(dir, "Data Analysis").exists());
    }

    @Test
    public void executeOverridesHeadlessFlagWhenDisplayIsAvailable() throws Exception {
        File dir = temp.newFolder("headedOverride");
        AtomicInteger chooserCalls = new AtomicInteger(0);
        installDispatcherChoice(BinSetupChooser.Choice.CANCELLED, chooserCalls);

        LineDistanceAnalysis analysis = new LineDistanceAnalysis();
        analysis.setHeadless(true);
        analysis.execute(dir.getAbsolutePath());

        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            assertEquals(1, chooserCalls.get());
        }
        assertFalse(new File(dir, "Data Analysis").exists());
    }

    @Test
    public void zSliceOnlyBinCompletesWithoutChooser() throws Exception {
        File dir = temp.newFolder("zsliceOnly");
        writeChannelData(dir, "", "", "", "", "", "", "", "", "zslice:full");
        AtomicInteger chooserCalls = new AtomicInteger(0);
        installDispatcherChoice(BinSetupChooser.Choice.CANCELLED, chooserCalls);

        LineDistanceAnalysis analysis = new LineDistanceAnalysis();
        BinSetupDispatcher.Outcome outcome = BinSetupDispatcher.ensure(
                dir.getAbsolutePath(), "Line Distance Analysis",
                analysis.requiredBinFields(), analysis.benefitsFromRois());

        assertEquals(BinSetupDispatcher.Outcome.COMPLETED, outcome);
        assertEquals(0, chooserCalls.get());
    }

    @Test
    public void lineDistancePathsUseFlashLayout() throws Exception {
        File dir = temp.newFolder("paths");

        assertEquals(new File(dir, "FLASH/Image Analysis/Line Distance Analysis").getAbsolutePath(),
                LineDistanceAnalysis.lineDistanceOutputDir(dir.getAbsolutePath()).getAbsolutePath());
        assertEquals(new File(dir, "FLASH/Image Analysis/Line Distance Analysis/Line Sets").getAbsolutePath(),
                LineDistanceAnalysis.lineSetWriteDir(dir.getAbsolutePath()).getAbsolutePath());
    }

    @Test
    public void lineSetNamesPreferNewLayoutAndIncludeLegacyFallbacks() throws Exception {
        File dir = temp.newFolder("lineSets");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(dir.getAbsolutePath());
        File newLines = layout.lineSetWriteDir();
        File legacyLines = new File(dir, "Data Analysis/Lines");
        assertTrue(newLines.mkdirs());
        assertTrue(legacyLines.mkdirs());
        assertTrue(new File(newLines, "Ventricle.zip").createNewFile());
        assertTrue(new File(newLines, "Boundary.zip").createNewFile());
        assertTrue(new File(legacyLines, "Ventricle.zip").createNewFile());
        assertTrue(new File(legacyLines, "Legacy.zip").createNewFile());

        assertEquals(Arrays.asList("Boundary", "Ventricle", "Legacy"),
                LineDistanceAnalysis.lineSetNames(dir.getAbsolutePath()));
    }

    @Test
    public void computeDistancesReadsObjectFallbackAndWritesLineDistanceCsvCopies() throws Exception {
        File dir = temp.newFolder("computePaths");
        File objects = flash.pipeline.io.FlashProjectLayout.forDirectory(dir.getAbsolutePath())
                .tablesObjectsWriteDir();
        assertTrue(objects.mkdirs());
        writeCsv(new File(objects, "Marker_A.csv"),
                "Region,XM,YM\nSCN1,10,20\n");

        LineDistanceAnalysis analysis = new LineDistanceAnalysis();
        analysis.computeDistances(dir.getAbsolutePath(),
                LineDistanceAnalysis.lineSetWriteDir(dir.getAbsolutePath()),
                Arrays.asList("MissingLineSet"));

        File out = new File(dir, "FLASH/Image Analysis/Line Distance Analysis/Marker_A.csv");
        assertTrue(out.isFile());
        assertTrue(new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8)
                .contains("Region,XM,YM"));
    }

    @Test
    public void computeDistancesReturnsGracefullyWhenSelectedSetsMissing() throws Exception {
        File dir = temp.newFolder("noSelectedLineSets");
        File objects = flash.pipeline.io.FlashProjectLayout.forDirectory(dir.getAbsolutePath())
                .tablesObjectsWriteDir();
        assertTrue(objects.mkdirs());
        writeCsv(new File(objects, "Marker_A.csv"),
                "Region,XM,YM\nSCN1,10,20\n");

        new LineDistanceAnalysis().computeDistances(dir.getAbsolutePath(),
                LineDistanceAnalysis.lineSetWriteDir(dir.getAbsolutePath()), null);

        assertFalse(new File(dir, "FLASH/Image Analysis/Line Distance Analysis/Marker_A.csv").exists());
    }

    @Test
    public void computeDistancesSkipsNonFiniteObjectCoordinates() throws Exception {
        File dir = temp.newFolder("nonFiniteLineDistance");
        File objects = flash.pipeline.io.FlashProjectLayout.forDirectory(dir.getAbsolutePath())
                .tablesObjectsWriteDir();
        assertTrue(objects.mkdirs());
        writeCsv(new File(objects, "Marker_A.csv"),
                "Region,XM,YM\nSCN1,NaN,20\nSCN1,3,4\n");

        File lines = LineDistanceAnalysis.lineSetWriteDir(dir.getAbsolutePath());
        assertTrue(lines.mkdirs());
        writeLineZip(new File(lines, "Boundary.zip"));

        new LineDistanceAnalysis().computeDistances(dir.getAbsolutePath(), lines,
                Arrays.asList(" Boundary ", "", null, "Boundary"));

        File out = new File(dir, "FLASH/Image Analysis/Line Distance Analysis/Marker_A.csv");
        CsvTableIO.ChannelData cd = CsvTableIO.loadChannelCsv(out, "Marker_A");
        assertNotNull(cd);
        assertEquals("Inf", cd.get(0, "Marker_A_DistTo_Boundary"));
        assertEquals("4.000000", cd.get(1, "Marker_A_DistTo_Boundary"));
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

    private static void writeCsv(File file, String content) throws Exception {
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeLineZip(File zipFile) throws Exception {
        int[] xs = new int[]{0, 10};
        int[] ys = new int[]{0, 0};
        PolygonRoi roi = new PolygonRoi(xs, ys, xs.length, Roi.POLYLINE);
        roi.setName("SCN1");

        ByteArrayOutputStream roiBytes = new ByteArrayOutputStream();
        RoiEncoder encoder = new RoiEncoder(roiBytes);
        encoder.write(roi);

        ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipFile.toPath()));
        try {
            zip.putNextEntry(new ZipEntry("0001.roi"));
            zip.write(roiBytes.toByteArray());
            zip.closeEntry();
        } finally {
            zip.close();
        }
    }

    private interface InvocationResult {
        Object invoke(Method method, Object[] args);
    }
}
