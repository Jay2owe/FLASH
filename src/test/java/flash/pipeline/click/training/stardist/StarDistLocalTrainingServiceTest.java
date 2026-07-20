package flash.pipeline.click.training.stardist;

import flash.pipeline.click.training.cellpose.CellposeLocalTrainingService;
import flash.pipeline.testutil.TestWait;
import flash.pipeline.ui.variations.integration.CellposeOneShotIntegrationTest;
import ij.ImagePlus;
import ij.io.FileSaver;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class StarDistLocalTrainingServiceTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void generatesConcreteScriptAndCondaCommand() throws Exception {
        Path dataset = datasetDir();
        StarDistLocalTrainingService.Config config = config(
                true, "python", "flash-stardist-train", "conda", 7);

        StarDistLocalTrainingService.TrainingArtifacts artifacts =
                StarDistLocalTrainingService.prepareTrainingArtifacts(
                        dataset, "Iba1 StarDist", config);

        assertTrue(artifacts.scriptText.contains(
                "from stardist.models import Config2D, StarDist2D"));
        assertTrue(artifacts.scriptText.contains("from csbdeep.utils import normalize"));
        assertTrue(artifacts.scriptText.contains("model.export_TF()"));
        assertEquals("conda", artifacts.command.get(0));
        assertEquals("run", artifacts.command.get(1));
        assertEquals("-n", artifacts.command.get(2));
        assertEquals("flash-stardist-train", artifacts.command.get(3));
        assertTrue(artifacts.command.contains("--dataset"));
        assertTrue(artifacts.command.contains("--output-zip"));
        assertTrue(Files.isRegularFile(artifacts.commandFile));
        assertTrue(new String(Files.readAllBytes(artifacts.commandFile), StandardCharsets.UTF_8)
                .contains("train_stardist_flash.py"));
    }

    @Test
    public void progressParserHandlesFlashAndKerasEpochLines() {
        StarDistTrainingProgressParser.Progress flash =
                StarDistTrainingProgressParser.parse("FLASH_EPOCH 3/10 loss=0.4");
        assertNotNull(flash);
        assertEquals(3, flash.epoch);
        assertEquals(10, flash.totalEpochs);
        assertEquals(0.3, flash.fraction, 0.0001);

        StarDistTrainingProgressParser.Progress keras =
                StarDistTrainingProgressParser.parse("Epoch 4/20");
        assertNotNull(keras);
        assertEquals(4, keras.epoch);
        assertEquals(20, keras.totalEpochs);
        assertEquals("StarDist epoch 4/20", keras.message);

        assertEquals(null, StarDistTrainingProgressParser.parse("loss: 0.2"));
    }

    @Test
    public void successfulFakeProcessWritesLogAndReturnsZip() throws Exception {
        Path dataset = datasetDir();
        FakeRunner runner = new FakeRunner(0);
        runner.stdout.add("Epoch 2/4");
        runner.stdout.add("FLASH_EPOCH 4/4 loss=0.1");
        StarDistLocalTrainingService service = new StarDistLocalTrainingService(
                config(true, "python", "", "conda", 4), runner);
        final List<String> progress = new ArrayList<String>();

        StarDistLocalTrainingService.TrainingResult result = service.train(
                new StarDistDatasetPackager.PackagingResult(dataset, 2, 2, 0),
                "Microglia model",
                new StarDistLocalTrainingService.ProgressSink() {
                    @Override public void update(double fraction, String message) {
                        progress.add(message + "=" + fraction);
                    }
                });

        assertTrue(Files.isRegularFile(result.outputZip));
        assertTrue(Files.isRegularFile(result.logFile));
        assertTrue(result.logFile.getFileName().toString()
                .startsWith("stardist_training-"));
        String log = new String(Files.readAllBytes(result.logFile), StandardCharsets.UTF_8);
        assertTrue(log.contains("[STDOUT] Epoch 2/4"));
        assertTrue(log.contains("FLASH_EXPORT_ZIP="));
        assertFalse(progress.isEmpty());
        assertTrue(progress.get(progress.size() - 1).startsWith("Local StarDist training complete."));
        assertTrue(runner.command.contains("--model-name"));
        assertTrue(runner.command.contains("microglia_model"));
    }

    @Test
    public void failingFakeProcessThrowsAndKeepsLogFile() throws Exception {
        Path dataset = datasetDir();
        FakeRunner runner = new FakeRunner(5);
        runner.stderr.add("ModuleNotFoundError: No module named 'stardist'");
        StarDistLocalTrainingService service = new StarDistLocalTrainingService(
                config(true, "python", "", "conda", 3), runner);

        try {
            service.train(new StarDistDatasetPackager.PackagingResult(dataset, 1, 1, 0),
                    "Bad model", StarDistLocalTrainingService.NO_PROGRESS);
            fail("Expected failing process to throw.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("exit code 5"));
            assertTrue(expected.getMessage().contains("ModuleNotFoundError"));
            Path logFile = onlyRunLog(dataset, "stardist_training-");
            assertTrue(Files.isRegularFile(logFile));
            String log = new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8);
            assertTrue(log.contains("[STDERR] ModuleNotFoundError"));
        }
    }

    @Test
    public void runnerIoFailureIncludesCapturedStreams() throws Exception {
        Path dataset = datasetDir();
        FakeRunner runner = new FakeRunner(0);
        runner.stdout.add("Epoch 1/4");
        runner.stderr.add("loading tensorflow");
        runner.failure = new IOException("Local StarDist training produced no output for 2 seconds.");
        StarDistLocalTrainingService service = new StarDistLocalTrainingService(
                config(true, "python", "", "conda", 4), runner);

        try {
            service.train(new StarDistDatasetPackager.PackagingResult(dataset, 1, 1, 0),
                    "Stalled model", StarDistLocalTrainingService.NO_PROGRESS);
            fail("Expected runner I/O failure to throw.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("no output for 2 seconds"));
            assertTrue(expected.getMessage().contains("loading tensorflow"));
            assertTrue(expected.getMessage().contains("Epoch 1/4"));
        }
    }

    @Test
    public void trainRefusesUnpairedDatasetBeforeLaunchingProcess() throws Exception {
        Path dataset = temp.newFolder("stardist-unpaired").toPath();
        Files.createDirectories(dataset.resolve("raw"));
        Files.createDirectories(dataset.resolve("labels"));
        writeCanonicalTiff(dataset.resolve("raw").resolve("image_001.tif"), 10);
        FakeRunner runner = new FakeRunner(0);
        StarDistLocalTrainingService service = new StarDistLocalTrainingService(
                config(true, "python", "", "conda", 3), runner);

        try {
            service.train(new StarDistDatasetPackager.PackagingResult(dataset, 1, 1, 0),
                    "Bad model", StarDistLocalTrainingService.NO_PROGRESS);
            fail("Expected unpaired dataset to throw.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("missing label TIFFs"));
            assertEquals(null, runner.command);
        }
    }

    @Test
    public void fractionalHandoffLabelIsRejectedBeforeTrainingArtifactsAreWritten()
            throws Exception {
        Path dataset = temp.newFolder("stardist-fractional-handoff").toPath();
        Files.createDirectories(dataset.resolve("raw"));
        Files.createDirectories(dataset.resolve("labels"));
        writeCanonicalTiff(dataset.resolve("raw").resolve("image_001.tif"), 10);
        Path labelPath = dataset.resolve("labels").resolve("image_001.tif");
        FloatProcessor processor = new FloatProcessor(2, 1);
        processor.setf(0, 0, 70_000.5f);
        ImagePlus image = new ImagePlus("fractional", processor);
        try {
            assertTrue(new FileSaver(image).saveAsTiff(labelPath.toString()));
        } finally {
            image.changes = false;
            image.close();
            image.flush();
        }

        try {
            StarDistLocalTrainingService.prepareTrainingArtifacts(dataset, "model",
                    config(true, "python", "", "conda", 3));
            fail("Expected fractional handoff label rejection.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("LABEL_IDENTITY_UNSUPPORTED"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("70000.5"));
        }
        assertFalse(Files.exists(dataset.resolve("training_split.json")));
        assertFalse(Files.exists(dataset.resolve("training_reproducibility.json")));
        assertFalse(Files.exists(dataset.resolve("train_stardist_flash.py")));
        assertFalse(Files.exists(dataset.resolve("train_stardist_command.txt")));
    }

    @Test
    public void trainRejectsInvalidOutputZipAfterSuccessfulProcess() throws Exception {
        Path dataset = datasetDir();
        FakeRunner runner = new FakeRunner(0);
        runner.writeValidZip = false;
        StarDistLocalTrainingService service = new StarDistLocalTrainingService(
                config(true, "python", "", "conda", 3), runner);

        try {
            service.train(new StarDistDatasetPackager.PackagingResult(dataset, 1, 1, 0),
                    "Invalid zip", StarDistLocalTrainingService.NO_PROGRESS);
            fail("Expected invalid StarDist zip to throw.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("StarDist model zip"));
        }
    }

    @Test
    public void defaultRunnerDrainsBothStreamsBeforeReturning() throws Exception {
        final AtomicLong stdoutLines = new AtomicLong();
        final AtomicLong stderrLines = new AtomicLong();
        StarDistLocalTrainingService.ProcessSpec spec =
                new StarDistLocalTrainingService.ProcessSpec(
                        stubCommand("BURST", 512), temp.getRoot().toPath(), 10, 0);

        StarDistLocalTrainingService.ProcessResult result =
                new StarDistLocalTrainingService.DefaultProcessRunner().run(spec,
                        new StarDistLocalTrainingService.LineConsumer() {
                            @Override public void accept(String line) {
                                stdoutLines.incrementAndGet();
                            }
                        }, new StarDistLocalTrainingService.LineConsumer() {
                            @Override public void accept(String line) {
                                stderrLines.incrementAndGet();
                            }
                        });

        assertEquals(0, result.exitCode);
        assertEquals(514L, stdoutLines.get());
        assertEquals(513L, stderrLines.get());
    }

    @Test
    public void interruptionRestoresStatusAndTerminatesRootAndDescendant()
            throws Exception {
        final Process process = new ProcessBuilder(stubCommand("TREE", 8)).start();
        final long rootPid = processId(process);
        final AtomicLong childPid = new AtomicLong(-1L);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final AtomicBoolean interruptRestored = new AtomicBoolean();
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    CellposeLocalTrainingService.runManagedProcess(process,
                            "StarDist interruption fixture", 30L, 0L,
                            new CellposeLocalTrainingService.LineConsumer() {
                                @Override public void accept(String line) {
                                    capturePid(line, childPid);
                                }
                            }, null);
                } catch (Throwable t) {
                    failure.set(t);
                    interruptRestored.set(Thread.currentThread().isInterrupted());
                }
            }
        }, "stardist-interruption-contract");
        worker.start();
        try {
            TestWait.await("StarDist fixture descendant PID", 5_000L,
                    new TestWait.Condition() {
                        @Override public boolean isMet() {
                            return childPid.get() > 0L;
                        }
                    });
            worker.interrupt();
            worker.join(10_000L);
            assertFalse("Interrupted process owner did not return", worker.isAlive());
        } finally {
            if (worker.isAlive()) {
                worker.interrupt();
            }
            forceCleanup(process, rootPid);
        }
        assertTrue(failure.get() instanceof InterruptedException);
        assertTrue("Interrupt status was not restored after cleanup",
                interruptRestored.get());
        assertPidGone(rootPid);
        assertPidGone(childPid.get());
    }

    private Path datasetDir() throws IOException {
        Path dataset = temp.newFolder("stardist-dataset").toPath();
        Files.createDirectories(dataset.resolve("raw"));
        Files.createDirectories(dataset.resolve("labels"));
        writeCanonicalTiff(dataset.resolve("raw").resolve("image_001.tif"), 10);
        writeCanonicalTiff(dataset.resolve("labels").resolve("image_001.tif"), 1);
        return dataset;
    }

    private static void writeCanonicalTiff(Path path, int value) throws IOException {
        Files.createDirectories(path.getParent());
        ShortProcessor processor = new ShortProcessor(2, 1);
        processor.set(0, 0, value);
        ImagePlus image = new ImagePlus(path.getFileName().toString(), processor);
        try {
            if (!new FileSaver(image).saveAsTiff(path.toString())) {
                throw new IOException("Could not write test TIFF: " + path);
            }
        } finally {
            image.changes = false;
            image.close();
            image.flush();
        }
    }

    private static Path onlyRunLog(Path directory, String prefix) throws IOException {
        java.util.stream.Stream<Path> stream = Files.list(directory);
        try {
            List<Path> logs = new ArrayList<Path>();
            java.util.Iterator<Path> iterator = stream.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (path.getFileName().toString().startsWith(prefix)
                        && path.getFileName().toString().endsWith(".log")) {
                    logs.add(path);
                }
            }
            assertEquals("Expected one run-keyed log", 1, logs.size());
            return logs.get(0);
        } finally {
            stream.close();
        }
    }

    private static StarDistLocalTrainingService.Config config(boolean enabled,
                                                             String python,
                                                             String condaEnv,
                                                             String condaExe,
                                                             int epochs) {
        return new StarDistLocalTrainingService.Config(
                enabled,
                python,
                condaEnv,
                condaExe,
                epochs,
                1,
                2,
                0.0003,
                32,
                2,
                0.2,
                42,
                false);
    }

    private static List<String> stubCommand(String mode, int burstLines) {
        List<String> command = new ArrayList<String>();
        String executable = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        command.add(java.nio.file.Paths.get(System.getProperty("java.home"),
                "bin", executable).toAbsolutePath().normalize().toString());
        command.add("-cp");
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.trim().isEmpty()) {
            classpath = System.getProperty("java.class.path");
        }
        command.add(classpath);
        command.add(CellposeOneShotIntegrationTest.StubProcessMain.class.getName());
        command.add(mode);
        command.add(String.valueOf(burstLines));
        return command;
    }

    private static void capturePid(String line, AtomicLong target) {
        if (line != null && line.startsWith("DESCENDANT_PID=")) {
            target.compareAndSet(-1L,
                    Long.parseLong(line.substring("DESCENDANT_PID=".length())));
        }
    }

    private static long processId(Process process) throws Exception {
        try {
            return ((Number) Process.class.getMethod("pid").invoke(process)).longValue();
        } catch (NoSuchMethodException ignored) {
            Class<?> type = process.getClass();
            while (type != null) {
                try {
                    Field field = type.getDeclaredField("pid");
                    field.setAccessible(true);
                    return ((Number) field.get(process)).longValue();
                } catch (NoSuchFieldException absent) {
                    type = type.getSuperclass();
                }
            }
            throw new IllegalStateException("Could not determine fixture process ID");
        }
    }

    private static void assertPidGone(long pid) throws Exception {
        assertTrue("Fixture did not report a valid process ID", pid > 0L);
        CellposeOneShotIntegrationTest.StubProcess.awaitPidExit(pid);
        assertFalse("Process " + pid + " is still alive",
                CellposeOneShotIntegrationTest.StubProcess.isPidAlive(pid));
    }

    private static void forceCleanup(Process process, long pid) {
        if (!process.isAlive()) {
            return;
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            Process taskkill = null;
            try {
                taskkill = new ProcessBuilder("taskkill", "/PID", String.valueOf(pid),
                        "/T", "/F").redirectErrorStream(true)
                        .redirectOutput(new java.io.File("NUL")).start();
                taskkill.waitFor(3L, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            } finally {
                if (taskkill != null && taskkill.isAlive()) {
                    taskkill.destroyForcibly();
                }
            }
        }
        process.destroyForcibly();
        try {
            process.waitFor(3L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class FakeRunner implements StarDistLocalTrainingService.ProcessRunner {
        final int exitCode;
        final List<String> stdout = new ArrayList<String>();
        final List<String> stderr = new ArrayList<String>();
        boolean writeValidZip = true;
        IOException failure;
        List<String> command;

        FakeRunner(int exitCode) {
            this.exitCode = exitCode;
        }

        @Override public StarDistLocalTrainingService.ProcessResult run(
                StarDistLocalTrainingService.ProcessSpec spec,
                StarDistLocalTrainingService.LineConsumer out,
                StarDistLocalTrainingService.LineConsumer err) throws IOException {
            command = spec.command;
            for (String line : stdout) {
                out.accept(line);
            }
            for (String line : stderr) {
                err.accept(line);
            }
            if (failure != null) {
                throw failure;
            }
            if (exitCode == 0) {
                Path zip = argumentAfter(spec.command, "--output-zip");
                if (writeValidZip) {
                    writeValidStarDistZip(zip);
                } else {
                    Files.write(zip, "zip".getBytes(StandardCharsets.UTF_8));
                }
                out.accept("FLASH_EXPORT_ZIP=" + zip.toString());
            }
            return new StarDistLocalTrainingService.ProcessResult(exitCode);
        }

        private static Path argumentAfter(List<String> command, String key) {
            int index = command.indexOf(key);
            if (index < 0 || index + 1 >= command.size()) {
                throw new IllegalArgumentException("Missing command argument: " + key);
            }
            return java.nio.file.Paths.get(command.get(index + 1));
        }

        private static void writeValidStarDistZip(Path path) throws IOException {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
                zip.putNextEntry(new ZipEntry("saved_model.pb"));
                zip.write("model".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }
}
