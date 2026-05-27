package flash.pipeline.click.training.cellpose;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CellposeLocalTrainingServiceTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void prepareTrainingArtifactsRebuildsPackagedTrainCommand() throws Exception {
        Path dataset = temp.newFolder("cp-dataset").toPath();
        Path commandFile = dataset.resolve("train_command.txt");
        Files.write(commandFile,
                Collections.singletonList("\"C:\\Temp\\malicious.exe\" --delete-project"),
                StandardCharsets.UTF_8);

        CellposeLocalTrainingService.TrainingArtifacts artifacts =
                CellposeLocalTrainingService.prepareTrainingArtifacts(
                        dataset, commandFile, "cyto3", config(true));

        assertEquals("python", artifacts.command.get(0));
        assertEquals("-m", artifacts.command.get(1));
        assertEquals("cellpose", artifacts.command.get(2));
        assertTrue(artifacts.command.contains("--dir"));
        assertFalse(artifacts.command.contains("--delete-project"));
        assertTrue(Files.isDirectory(artifacts.modelsDir));
        assertEquals(commandFile.toAbsolutePath().normalize(), artifacts.commandFile);
        String rewritten = new String(Files.readAllBytes(commandFile), StandardCharsets.UTF_8);
        assertFalse(rewritten.contains("malicious.exe"));
    }

    @Test
    public void buildsEquivalentCommandWhenPackagedCommandIsMissing() throws Exception {
        Path dataset = temp.newFolder("cp-build-command").toPath();

        CellposeLocalTrainingService.TrainingArtifacts artifacts =
                CellposeLocalTrainingService.prepareTrainingArtifacts(
                        dataset, dataset.resolve("train_command.txt"), "nuclei", config(true));

        assertEquals("python", artifacts.command.get(0));
        assertTrue(artifacts.command.contains("--train"));
        assertTrue(artifacts.command.contains("--pretrained_model"));
        assertTrue(artifacts.command.contains("nuclei"));
        assertTrue(Files.isRegularFile(dataset.resolve("train_command.txt")));
    }

    @Test
    public void progressParserHandlesCommonEpochLines() {
        CellposeTrainingProgressParser.Progress flash =
                CellposeTrainingProgressParser.parse("FLASH_CELLPOSE_EPOCH 3/10 loss=0.4");
        assertNotNull(flash);
        assertEquals(3, flash.epoch);
        assertEquals(10, flash.totalEpochs);
        assertEquals(0.3, flash.fraction, 0.0001);

        CellposeTrainingProgressParser.Progress epoch =
                CellposeTrainingProgressParser.parse("Epoch 4/20");
        assertNotNull(epoch);
        assertEquals(4, epoch.epoch);
        assertEquals(20, epoch.totalEpochs);
        assertEquals("Cellpose epoch 4/20", epoch.message);

        assertEquals(null, CellposeTrainingProgressParser.parse("loss: 0.2"));
    }

    @Test
    public void successfulFakeProcessWritesLogAndDetectsModelFile() throws Exception {
        Path dataset = temp.newFolder("cp-fake-success").toPath();
        writeMinimalDataset(dataset);
        Path commandFile = commandFile(dataset);
        FakeRunner runner = new FakeRunner(0, false);
        runner.stdout.add("Epoch 2/4");
        runner.stdout.add("Epoch 4/4");
        CellposeLocalTrainingService service =
                new CellposeLocalTrainingService(config(true), runner);
        final List<String> progress = new ArrayList<String>();

        CellposeLocalTrainingService.TrainingResult result = service.train(
                new CellposeDatasetPackager.PackagingResult(
                        dataset, commandFile, 1, 1, 1, 1),
                "Microglia Cellpose",
                new CellposeLocalTrainingService.ProgressSink() {
                    @Override public void update(double fraction, String message) {
                        progress.add(message + "=" + fraction);
                    }
                });

        assertTrue(Files.isRegularFile(result.modelFile));
        assertEquals(dataset.resolve("models").toAbsolutePath().normalize(),
                result.modelsDir);
        assertTrue(Files.isRegularFile(result.logFile));
        String log = new String(Files.readAllBytes(result.logFile), StandardCharsets.UTF_8);
        assertTrue(log.contains("[STDOUT] Epoch 2/4"));
        assertTrue(log.contains("[FLASH] Command:"));
        assertFalse(progress.isEmpty());
        assertTrue(progress.toString().contains("Cellpose epoch 4/4"));
        assertTrue(progress.get(progress.size() - 1).startsWith("Local Cellpose training complete."));
        assertTrue(runner.command.contains("-m"));
        assertTrue(runner.command.contains("cellpose"));
    }

    @Test
    public void failingFakeProcessThrowsAndKeepsLogFile() throws Exception {
        Path dataset = temp.newFolder("cp-fake-failure").toPath();
        writeMinimalDataset(dataset);
        Path commandFile = commandFile(dataset);
        FakeRunner runner = new FakeRunner(7, false);
        runner.stderr.add("ModuleNotFoundError: No module named 'cellpose'");
        CellposeLocalTrainingService service =
                new CellposeLocalTrainingService(config(true), runner);

        try {
            service.train(new CellposeDatasetPackager.PackagingResult(
                            dataset, commandFile, 1, 1, 1, 1),
                    "Bad Cellpose", CellposeLocalTrainingService.NO_PROGRESS);
            fail("Expected failing process to throw.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("exit code 7"));
            assertTrue(expected.getMessage().contains("ModuleNotFoundError"));
            Path logFile = dataset.resolve("cellpose_training.log");
            assertTrue(Files.isRegularFile(logFile));
            String log = new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8);
            assertTrue(log.contains("[STDERR] ModuleNotFoundError"));
        }
    }

    @Test
    public void runnerIoFailureIncludesCapturedStreams() throws Exception {
        Path dataset = temp.newFolder("cp-stall").toPath();
        writeMinimalDataset(dataset);
        Path commandFile = commandFile(dataset);
        FakeRunner runner = new FakeRunner(0, false);
        runner.stdout.add("Epoch 1/4");
        runner.stderr.add("still importing cellpose");
        runner.failure = new IOException("Local Cellpose training produced no output for 2 seconds.");
        CellposeLocalTrainingService service =
                new CellposeLocalTrainingService(config(true), runner);

        try {
            service.train(new CellposeDatasetPackager.PackagingResult(
                            dataset, commandFile, 1, 1, 1, 1),
                    "Stalled Cellpose", CellposeLocalTrainingService.NO_PROGRESS);
            fail("Expected runner I/O failure to throw.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("no output for 2 seconds"));
            assertTrue(expected.getMessage().contains("still importing cellpose"));
            assertTrue(expected.getMessage().contains("Epoch 1/4"));
        }
    }

    @Test
    public void trainRefusesMissingMaskPairBeforeLaunchingProcess() throws Exception {
        Path dataset = temp.newFolder("cp-missing-mask").toPath();
        Files.write(dataset.resolve("image_001.tif"),
                "image".getBytes(StandardCharsets.UTF_8));
        Path commandFile = commandFile(dataset);
        FakeRunner runner = new FakeRunner(0, false);
        CellposeLocalTrainingService service =
                new CellposeLocalTrainingService(config(true), runner);

        try {
            service.train(new CellposeDatasetPackager.PackagingResult(
                            dataset, commandFile, 1, 1, 1, 1),
                    "Bad dataset", CellposeLocalTrainingService.NO_PROGRESS);
            fail("Expected missing mask pair to throw.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("missing mask TIFF pairs"));
            assertEquals(null, runner.command);
        }
    }

    private static Path commandFile(Path dataset) throws IOException {
        Path commandFile = dataset.resolve("train_command.txt");
        Files.write(commandFile,
                Collections.singletonList("python -m cellpose --train --dir \""
                        + dataset.toAbsolutePath().normalize()
                        + "\" --pretrained_model cyto3"),
                StandardCharsets.UTF_8);
        return commandFile;
    }

    private static void writeMinimalDataset(Path dataset) throws IOException {
        Files.write(dataset.resolve("image_001.tif"),
                "image".getBytes(StandardCharsets.UTF_8));
        Files.write(dataset.resolve("image_001_masks.tif"),
                "mask".getBytes(StandardCharsets.UTF_8));
    }

    private static CellposeLocalTrainingService.Config config(boolean enabled) {
        return new CellposeLocalTrainingService.Config(
                enabled,
                "python",
                4,
                1,
                0.00001,
                0.1);
    }

    private static final class FakeRunner implements CellposeLocalTrainingService.ProcessRunner {
        final int exitCode;
        final boolean reportModelPath;
        final List<String> stdout = new ArrayList<String>();
        final List<String> stderr = new ArrayList<String>();
        IOException failure;
        List<String> command;

        FakeRunner(int exitCode, boolean reportModelPath) {
            this.exitCode = exitCode;
            this.reportModelPath = reportModelPath;
        }

        @Override public CellposeLocalTrainingService.ProcessResult run(
                CellposeLocalTrainingService.ProcessSpec spec,
                CellposeLocalTrainingService.LineConsumer out,
                CellposeLocalTrainingService.LineConsumer err) throws IOException {
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
                Path model = spec.workingDirectory.resolve("models")
                        .resolve("cellpose_model_001").toAbsolutePath().normalize();
                Files.createDirectories(model.getParent());
                Files.write(model, "model".getBytes(StandardCharsets.UTF_8));
                if (reportModelPath) {
                    out.accept("FLASH_CELLPOSE_MODEL=" + model);
                }
            }
            return new CellposeLocalTrainingService.ProcessResult(exitCode);
        }
    }
}
