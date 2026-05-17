package flash.pipeline.click.training.stardist;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
            Path logFile = dataset.resolve("stardist_training.log");
            assertTrue(Files.isRegularFile(logFile));
            String log = new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8);
            assertTrue(log.contains("[STDERR] ModuleNotFoundError"));
        }
    }

    private Path datasetDir() throws IOException {
        Path dataset = temp.newFolder("stardist-dataset").toPath();
        Files.createDirectories(dataset.resolve("raw"));
        Files.createDirectories(dataset.resolve("labels"));
        return dataset;
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

    private static final class FakeRunner implements StarDistLocalTrainingService.ProcessRunner {
        final int exitCode;
        final List<String> stdout = new ArrayList<String>();
        final List<String> stderr = new ArrayList<String>();
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
            if (exitCode == 0) {
                Path zip = argumentAfter(spec.command, "--output-zip");
                Files.write(zip, "zip".getBytes(StandardCharsets.UTF_8));
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
    }
}
