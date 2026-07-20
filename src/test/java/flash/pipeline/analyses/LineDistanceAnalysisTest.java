package flash.pipeline.analyses;

import flash.pipeline.TestConfigFiles;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinField;
import flash.pipeline.bin.BinSetupChooser;
import flash.pipeline.bin.BinSetupDispatcher;
import flash.pipeline.execution.AnalysisRunCoordinator;
import flash.pipeline.execution.RunResult;
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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.concurrent.Callable;
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
        BinConfig cfg = new BinConfig();
        cfg.zSliceConfigPresent = true;
        TestConfigFiles.writeChannelConfig(dir, cfg);
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
    public void lineDistancePathsUseResultsLayout() throws Exception {
        File dir = temp.newFolder("paths");

        assertEquals(new File(dir, "FLASH/Results/Tables/Line Distance").getAbsolutePath(),
                LineDistanceAnalysis.lineDistanceOutputDir(dir.getAbsolutePath()).getAbsolutePath());
        assertEquals(new File(dir, "FLASH/Results/Tables/Line Distance/Line Sets").getAbsolutePath(),
                LineDistanceAnalysis.lineSetWriteDir(dir.getAbsolutePath()).getAbsolutePath());
    }

    @Test
    public void lineSetNamesEnumerateZipsInWriteDir() throws Exception {
        File dir = temp.newFolder("lineSets");
        File lineSets = LineDistanceAnalysis.lineSetWriteDir(dir.getAbsolutePath());
        assertTrue(lineSets.mkdirs());
        assertTrue(new File(lineSets, "Ventricle.zip").createNewFile());
        assertTrue(new File(lineSets, "Boundary.zip").createNewFile());

        assertEquals(Arrays.asList("Boundary", "Ventricle"),
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

        File out = new File(dir, "FLASH/Results/Tables/Line Distance/Marker_A.csv");
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

        assertFalse(new File(dir, "FLASH/Results/Tables/Line Distance/Marker_A.csv").exists());
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

        File out = new File(dir, "FLASH/Results/Tables/Line Distance/Marker_A.csv");
        CsvTableIO.ChannelData cd = CsvTableIO.loadChannelCsv(out, "Marker_A");
        assertNotNull(cd);
        assertEquals("Inf", cd.get(0, "Marker_A_DistTo_Boundary"));
        assertEquals("4.000000", cd.get(1, "Marker_A_DistTo_Boundary"));
    }

    @Test
    public void requiredMeasurementPublicationFailureNamesTargetAndPropagatesCause()
            throws Exception {
        File dir = temp.newFolder("required-publication-failure");
        File objects = FlashProjectLayout.forDirectory(dir.getAbsolutePath())
                .tablesObjectsWriteDir();
        assertTrue(objects.mkdirs());
        writeCsv(new File(objects, "Marker_A.csv"),
                "Region,XM,YM\nSCN1,10,20\n");

        final IOException injected = new IOException("injected line-distance disk fault");
        LineDistanceAnalysis analysis = new LineDistanceAnalysis();
        analysis.setChannelCsvPublisherForTests(
                new LineDistanceAnalysis.ChannelCsvPublisher() {
                    @Override
                    public void publish(File target, CsvTableIO.ChannelData data, String runId)
                            throws IOException {
                        throw injected;
                    }
                });

        File expected = new File(dir,
                "FLASH/Results/Tables/Line Distance/Marker_A.csv");
        AnalysisRunCoordinator coordinator = new AnalysisRunCoordinator();
        final RunResult[] emitted = new RunResult[1];
        installTerminalCollector(coordinator, emitted);
        try {
            coordinator.run(analysis, -1, "Line Distance Analysis",
                    dir.getAbsolutePath(), null, null, "", new Callable<Void>() {
                        @Override
                        public Void call() {
                            analysis.computeDistances(dir.getAbsolutePath(),
                                    LineDistanceAnalysis.lineSetWriteDir(dir.getAbsolutePath()),
                                    Arrays.asList("OptionalMissingLineSet"));
                            return null;
                        }
                    });
            org.junit.Assert.fail("Required measurement publication must fail the analysis");
        } catch (UncheckedIOException failure) {
            assertTrue(failure.getMessage().contains(expected.getAbsolutePath()));
            assertEquals(injected, failure.getCause());
        }
        assertFalse(expected.exists());
        assertNotNull(emitted[0]);
        assertEquals(RunResult.TerminalState.FAILED, emitted[0].terminalState);
    }

    @Test
    public void missingOptionalLineSetWarnsOnlyAfterRequiredCsvCommits() throws Exception {
        File dir = temp.newFolder("optional-line-set-warning");
        File objects = FlashProjectLayout.forDirectory(dir.getAbsolutePath())
                .tablesObjectsWriteDir();
        assertTrue(objects.mkdirs());
        writeCsv(new File(objects, "Marker_A.csv"),
                "Region,XM,YM\nSCN1,10,20\n");

        final LineDistanceAnalysis analysis = new LineDistanceAnalysis();
        RunResult result = new AnalysisRunCoordinator().run(
                analysis, -1, "Line Distance Analysis", dir.getAbsolutePath(),
                null, null, "", new Callable<Void>() {
                    @Override
                    public Void call() {
                        analysis.computeDistances(dir.getAbsolutePath(),
                                LineDistanceAnalysis.lineSetWriteDir(dir.getAbsolutePath()),
                                Arrays.asList("OptionalMissingLineSet"));
                        return null;
                    }
                });

        assertEquals(RunResult.TerminalState.COMPLETED_WITH_WARNINGS,
                result.terminalState);
        assertTrue(new File(dir,
                "FLASH/Results/Tables/Line Distance/Marker_A.csv").isFile());
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

    private static void installTerminalCollector(
            AnalysisRunCoordinator coordinator, final RunResult[] emitted) throws Exception {
        Class<?> emitterType = Class.forName(
                "flash.pipeline.execution.AnalysisRunCoordinator$TerminalResultEmitter");
        Object emitter = Proxy.newProxyInstance(
                emitterType.getClassLoader(), new Class<?>[]{emitterType},
                (proxy, method, args) -> {
                    if (args != null && args.length == 1 && args[0] instanceof RunResult) {
                        emitted[0] = (RunResult) args[0];
                    }
                    return null;
                });
        Method setter = AnalysisRunCoordinator.class.getDeclaredMethod(
                "setTerminalResultEmitterForTests", emitterType);
        setter.setAccessible(true);
        setter.invoke(coordinator, emitter);
    }

    private static void invokeDispatcherReset() throws Exception {
        Method reset = BinSetupDispatcher.class.getDeclaredMethod("resetForTest");
        reset.setAccessible(true);
        reset.invoke(null);
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
