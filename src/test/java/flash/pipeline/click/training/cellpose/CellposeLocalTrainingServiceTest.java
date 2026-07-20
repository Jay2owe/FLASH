package flash.pipeline.click.training.cellpose;

import flash.pipeline.cellpose.Cellpose3DRunner;
import flash.pipeline.cellpose.CellposeRuntime;
import flash.pipeline.cellpose.CellposeWorkerRequest;
import flash.pipeline.click.ClickStore;
import flash.pipeline.click.suggest.CellposeFilterSuggester;
import flash.pipeline.click.suggest.SuggestionContext;
import flash.pipeline.click.training.ImagePlusProvider;
import flash.pipeline.click.training.ObjectFeatureExtractor;
import flash.pipeline.click.training.stardist.StarDistDatasetPackager;
import flash.pipeline.ui.wizard.JsonIO;
import flash.pipeline.ui.variations.integration.CellposeOneShotIntegrationTest;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ShortProcessor;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
        assertEquals(artifacts.wrapperFile.toString(), artifacts.command.get(1));
        assertTrue(Files.isRegularFile(artifacts.wrapperFile));
        assertTrue(artifacts.command.contains("-m"));
        assertTrue(artifacts.command.contains("cellpose"));
        assertTrue(artifacts.command.contains("--dir"));
        assertFalse(artifacts.command.contains("--delete-project"));
        assertTrue(Files.isDirectory(artifacts.modelsDir));
        assertTrue(artifacts.expectedModelFile.startsWith(artifacts.modelsDir));
        assertFalse(Files.exists(artifacts.artifactMarkerFile));
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
    public void persistsSeededCellposeBootstrapBeforeBackendConstruction() throws Exception {
        Path dataset = temp.newFolder("cp-reproducibility").toPath();
        CellposeLocalTrainingService.Config seeded =
                new CellposeLocalTrainingService.Config(true, "python", 4, 1,
                        0.00001, 0.1, 8675309);

        CellposeLocalTrainingService.TrainingArtifacts artifacts =
                CellposeLocalTrainingService.prepareTrainingArtifacts(dataset,
                        dataset.resolve("train_command.txt"), "cyto3", seeded);

        assertEquals(Integer.toString(8675309),
                artifacts.environment.get("FLASH_TRAINING_SEED"));
        assertTrue(artifacts.environment.get("PYTHONPATH")
                .contains(".flash-repro"));
        assertTrue(Files.isRegularFile(artifacts.siteCustomizeFile));
        String bootstrap = new String(Files.readAllBytes(artifacts.siteCustomizeFile),
                StandardCharsets.UTF_8);
        assertTrue(bootstrap.contains("random.seed(seed)"));
        assertTrue(bootstrap.contains("np.random.seed(seed)"));
        assertTrue(bootstrap.contains("torch.manual_seed(seed)"));
        assertTrue(bootstrap.contains("torch.cuda.manual_seed_all(seed)"));
        assertTrue(bootstrap.contains("evidenceWrittenBeforeCellposeImportAndModelConstruction"));
        assertTrue(bootstrap.contains("status = 'best-effort'"));
        assertTrue(bootstrap.contains("Cellpose augmentation and device kernels may remain nondeterministic"));
        String requested = new String(Files.readAllBytes(artifacts.reproducibilityFile),
                StandardCharsets.UTF_8);
        assertTrue(requested.contains("\"seed\":8675309"));
        assertTrue(requested.contains("\"runtimeEvidence\":\"pending\""));
        assertTrue(artifacts.command.contains("--expected-model"));
        assertTrue(artifacts.command.contains(CellposeRuntime.SUPPORTED_CELLPOSE_VERSION));
        String wrapper = new String(Files.readAllBytes(artifacts.wrapperFile),
                StandardCharsets.UTF_8);
        assertTrue(wrapper.contains("models.CellposeModel"));
        assertTrue(wrapper.contains("hashlib.sha256"));
        assertTrue(wrapper.contains("artifactSha256"));
    }

    @Test
    public void rejectsNonFiniteTrainingAndWorkerValuesBeforeClamping() {
        double[] nonFinite = new double[] {
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY
        };
        for (final double value : nonFinite) {
            expectIllegalArgument(new Runnable() {
                @Override public void run() {
                    new CellposeLocalTrainingService.Config(true, "python", 1, 1,
                            value, 0.1, 42);
                }
            }, "learning rate");
            expectIllegalArgument(new Runnable() {
                @Override public void run() {
                    new CellposeLocalTrainingService.Config(true, "python", 1, 1,
                            0.001, value, 42);
                }
            }, "weight decay");
            expectIllegalArgument(new Runnable() {
                @Override public void run() {
                    new CellposeWorkerRequest("flow", 1.0, value, 0.0);
                }
            }, "flowThreshold");
            expectIllegalArgument(new Runnable() {
                @Override public void run() {
                    new CellposeWorkerRequest("cellprob", 1.0, 0.4, value);
                }
            }, "cellprobThreshold");
        }

        CellposeLocalTrainingService.Config edge =
                new CellposeLocalTrainingService.Config(true, "python", 1, 1,
                        0.0, Double.MAX_VALUE, 42);
        assertEquals(0.0, edge.learningRate, 0.0);
        assertEquals(Double.MAX_VALUE, edge.weightDecay, 0.0);
        CellposeWorkerRequest request = new CellposeWorkerRequest("edge", 1.0,
                -Double.MAX_VALUE, Double.MAX_VALUE);
        assertEquals(-Double.MAX_VALUE, request.flowThreshold(), 0.0);
        assertEquals(Double.MAX_VALUE, request.cellprobThreshold(), 0.0);
    }

    @Test
    public void invalidNonFiniteSystemPropertiesUseLoggedFiniteFallback() {
        String previousPython = System.getProperty(CellposeLocalTrainingService.PYTHON_PROPERTY);
        String previousLearning = System.getProperty(
                CellposeLocalTrainingService.LEARNING_RATE_PROPERTY);
        String previousWeight = System.getProperty(
                CellposeLocalTrainingService.WEIGHT_DECAY_PROPERTY);
        try {
            System.setProperty(CellposeLocalTrainingService.PYTHON_PROPERTY, "python");
            System.setProperty(CellposeLocalTrainingService.LEARNING_RATE_PROPERTY, "NaN");
            System.setProperty(CellposeLocalTrainingService.WEIGHT_DECAY_PROPERTY, "Infinity");
            CellposeLocalTrainingService.Config config =
                    CellposeLocalTrainingService.Config.fromSystemProperties();
            assertEquals(0.00001, config.learningRate, 0.0);
            assertEquals(0.1, config.weightDecay, 0.0);
        } finally {
            restoreProperty(CellposeLocalTrainingService.PYTHON_PROPERTY, previousPython);
            restoreProperty(CellposeLocalTrainingService.LEARNING_RATE_PROPERTY, previousLearning);
            restoreProperty(CellposeLocalTrainingService.WEIGHT_DECAY_PROPERTY, previousWeight);
        }
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
        assertTrue(result.logFile.getFileName().toString()
                .startsWith("cellpose_training-"));
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
    public void trainingRejectsAbsentAuxiliaryAndAmbiguousArtifactMarkers() throws Exception {
        assertArtifactFailure(ArtifactMode.MISSING_MARKER, "machine-readable artifact marker");
        assertArtifactFailure(ArtifactMode.AUXILIARY, "undeclared or auxiliary file");
        assertArtifactFailure(ArtifactMode.AMBIGUOUS, "ambiguous");
    }

    @Test
    public void trainingRejectsChangedSizeMalformedDigestAndPostValidationTampering()
            throws Exception {
        assertArtifactFailure(ArtifactMode.WRONG_SIZE, "size changed");
        assertArtifactFailure(ArtifactMode.MALFORMED_DIGEST, "malformed SHA-256");
        assertArtifactFailure(ArtifactMode.TAMPERED, "SHA-256 mismatch");
    }

    @Test
    public void geometryBoundariesRejectEveryAxisBeforeProducingPartialOutputs()
            throws Exception {
        final ImagePlus primary = geometryImage("primary", 4, 3, 1, 2, 1);
        final ImagePlus unexpectedTime = geometryImage("time", 4, 3, 1, 1, 2);
        expectIllegalArgument(new Runnable() {
            @Override public void run() {
                Cellpose3DRunner.prepareRuntimeInput(unexpectedTime, null, "DAPI");
            }
        }, "T=2");

        Path maskPath = temp.newFolder("geometry-mask").toPath().resolve("mask.tif");
        ImagePlus wrongHeightMask = geometryImage("mask", 4, 5, 1, 2, 1);
        IJ.saveAsTiff(wrongHeightMask, maskPath.toString());
        Method readMask = Cellpose3DRunner.class.getDeclaredMethod(
                "readMaskImage", Path.class, ImagePlus.class, String.class);
        readMask.setAccessible(true);
        try {
            readMask.invoke(null, maskPath, primary, "DAPI");
            fail("Expected mismatched Cellpose mask geometry.");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            assertTrue(expected.getCause() instanceof Cellpose3DRunner.ImageGeometryException);
            assertTrue(expected.getCause().getMessage().contains("Y=5"));
        }

        Path project = temp.newFolder("geometry-stardist").toPath();
        final ImagePlus raw = geometryImage("raw", 4, 3, 1, 2, 1);
        final ImagePlus wrongWidthLabels = geometryImage("labels", 5, 3, 1, 2, 1);
        ClickStore clicks = clickStore("Image1");
        try {
            new StarDistDatasetPackager().packageDataset(project, "geometry", 1, clicks,
                    provider(raw), provider(wrongWidthLabels));
            fail("Expected mismatched StarDist training geometry.");
        } catch (StarDistDatasetPackager.GeometryMismatchException expected) {
            assertTrue(expected.getMessage().contains("X=4"));
            assertTrue(expected.getMessage().contains("X=5"));
        }
        assertFalse("Rejected training pair must not install a dataset",
                containsFileNamed(project, "metadata.json"));

        Path timedProject = temp.newFolder("geometry-stardist-time").toPath();
        ImagePlus timedRaw = geometryImage("raw-time", 4, 3, 1, 1, 2);
        ImagePlus timedLabels = geometryImage("labels-time", 4, 3, 1, 1, 2);
        try {
            new StarDistDatasetPackager().packageDataset(timedProject, "geometry", 1,
                    clicks, provider(timedRaw), provider(timedLabels));
            fail("Expected StarDist time axis to be rejected.");
        } catch (StarDistDatasetPackager.GeometryMismatchException expected) {
            assertTrue(expected.getMessage().contains("T=2"));
        }

        final ImagePlus labels = geometryImage("labels", 4, 3, 1, 2, 1);
        final ImagePlus partialZ = geometryImage("values", 4, 3, 1, 1, 1);
        List<ClickStore.Click> negative = new ArrayList<ClickStore.Click>();
        negative.add(click("Image1", 1));
        negative.add(click("Image1", 2));
        negative.add(click("Image1", 3));
        final SuggestionContext context = new SuggestionContext(partialZ, labels, null,
                negative, Collections.<ClickStore.Click>emptyList(),
                Collections.<String, Double>emptyMap());
        expectIllegalArgument(new Runnable() {
            @Override public void run() {
                new CellposeFilterSuggester().suggest(context);
            }
        }, "no partial statistics");

        final ImagePlus twoChannelValues = geometryImage("two-channel", 4, 3, 2, 1, 1);
        expectIllegalArgument(new Runnable() {
            @Override public void run() {
                new ObjectFeatureExtractor().extractFromLabelImage(
                        labels, twoChannelValues, null, null);
            }
        }, "C=2");
    }

    @Test
    public void identicallyInvalidCalibrationsAreNotAcceptedAsRegistered() {
        final ImagePlus labels = geometryImage("labels", 4, 3, 1, 1, 1);
        final ImagePlus values = geometryImage("values", 4, 3, 1, 1, 1);
        Calibration invalidLabels = labels.getCalibration().copy();
        Calibration invalidValues = values.getCalibration().copy();
        invalidLabels.pixelWidth = Double.NaN;
        invalidValues.pixelWidth = Double.NaN;
        labels.setCalibration(invalidLabels);
        values.setCalibration(invalidValues);

        expectIllegalArgument(new Runnable() {
            @Override public void run() {
                new ObjectFeatureExtractor().extractFromLabelImage(
                        labels, values, null, null);
            }
        }, "spatial calibration");
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
            Path logFile = onlyRunLog(dataset, "cellpose_training-");
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

    @Test
    public void managedProcessTimeoutTerminatesRootAndPipeInheritingDescendant()
            throws Exception {
        Process process = new ProcessBuilder(stubCommand("TREE", 8)).start();
        long rootPid = processId(process);
        final AtomicLong childPid = new AtomicLong(-1L);
        try {
            CellposeLocalTrainingService.runManagedProcess(process,
                    "Cellpose tree fixture", 1L, 0L,
                    new CellposeLocalTrainingService.LineConsumer() {
                        @Override public void accept(String line) {
                            capturePid(line, childPid);
                        }
                    }, null);
            fail("Expected the tree fixture to time out.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("timed out"));
        } finally {
            forceCleanup(process, rootPid);
        }
        assertPidGone(rootPid);
        assertTrue("The fixture did not report its descendant PID", childPid.get() > 0L);
        assertPidGone(childPid.get());
    }

    @Test
    public void drainerFailureIsRetainedAndTerminatesTheWholeTree() throws Exception {
        Process process = new ProcessBuilder(stubCommand("TREE", 8)).start();
        long rootPid = processId(process);
        final AtomicLong childPid = new AtomicLong(-1L);
        try {
            CellposeLocalTrainingService.runManagedProcess(process,
                    "Cellpose drainer fixture", 10L, 0L,
                    new CellposeLocalTrainingService.LineConsumer() {
                        @Override public void accept(String line) throws IOException {
                            capturePid(line, childPid);
                            if (line.startsWith("DESCENDANT_PID=")) {
                                throw new IOException("injected stdout collector failure");
                            }
                        }
                    }, null);
            fail("Expected the injected drainer failure.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("drainer failed"));
            assertNotNull(expected.getCause());
            assertTrue(expected.getCause().getMessage().contains("injected stdout"));
        } finally {
            forceCleanup(process, rootPid);
        }
        assertPidGone(rootPid);
        assertTrue("The fixture did not report its descendant PID", childPid.get() > 0L);
        assertPidGone(childPid.get());
    }

    @Test
    public void assertionErrorInDrainerIsReportedAndTerminatesTheWholeTree()
            throws Exception {
        Process process = new ProcessBuilder(stubCommand("TREE", 8)).start();
        long rootPid = processId(process);
        final AtomicLong childPid = new AtomicLong(-1L);
        try {
            CellposeLocalTrainingService.runManagedProcess(process,
                    "Cellpose assertion fixture", 10L, 0L,
                    new CellposeLocalTrainingService.LineConsumer() {
                        @Override public void accept(String line) {
                            capturePid(line, childPid);
                            if (line.startsWith("DESCENDANT_PID=")) {
                                throw new AssertionError("injected collector assertion");
                            }
                        }
                    }, null);
            fail("Expected the injected drainer assertion.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("drainer failed"));
            assertTrue(expected.getCause() instanceof AssertionError);
            assertTrue(expected.getCause().getMessage()
                    .contains("injected collector assertion"));
        } finally {
            forceCleanup(process, rootPid);
        }
        assertPidGone(rootPid);
        assertTrue("The fixture did not report its descendant PID", childPid.get() > 0L);
        assertPidGone(childPid.get());
    }

    @Test
    public void java8WindowsFallbackKillsTrackedOrphanAfterRootExits()
            throws Exception {
        Assume.assumeTrue(System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).contains("win"));
        Process root = new ProcessBuilder(testJavaCommand(RootExitsFirstMain.class))
                .redirectErrorStream(true).start();
        long rootPid = processId(root);
        final AtomicLong childPid = new AtomicLong(-1L);
        boolean childSurvivedCleanup = false;
        try {
            CellposeLocalTrainingService.runManagedProcessJava8FallbackForTest(
                    root, "Java 8 root-exits-first fixture", 15L, 0L,
                    new CellposeLocalTrainingService.LineConsumer() {
                        @Override public void accept(String line) {
                            capturePid(line, childPid);
                        }
                    }, null);
            fail("Expected the orphaned pipe to fail the bounded drainer join.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("output drainers"));
            assertTrue("The fixture did not report its descendant PID",
                    childPid.get() > 0L);
            childSurvivedCleanup = CellposeOneShotIntegrationTest.StubProcess
                    .isPidAlive(childPid.get());
        } finally {
            forceCleanup(root, rootPid);
            if (childPid.get() > 0L) {
                forcePidCleanup(childPid.get());
            }
        }
        assertFalse("Tracked orphan survived Java 8 fallback cleanup",
                childSurvivedCleanup);
        assertPidGone(rootPid);
        assertPidGone(childPid.get());
    }

    @Test
    public void osFallbackHelperOutputAndCleanupAreBoundedWhenHelperHangs()
            throws Exception {
        Method capture = CellposeLocalTrainingService.class.getDeclaredMethod(
                "captureUtility", List.class, long.class);
        capture.setAccessible(true);
        long started = System.nanoTime();
        Object result = capture.invoke(null, stubCommand("HANG", 8), Long.valueOf(200L));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - started);

        Field complete = result.getClass().getDeclaredField("complete");
        complete.setAccessible(true);
        assertFalse("A timed-out helper was reported complete",
                complete.getBoolean(result));
        assertTrue("Helper output capture exceeded its bounded cleanup window: "
                        + elapsedMillis + " ms",
                elapsedMillis < 5_000L);
    }

    private void assertArtifactFailure(ArtifactMode mode, String messagePart) throws Exception {
        Path dataset = temp.newFolder("cp-artifact-" + mode.name().toLowerCase(Locale.ROOT))
                .toPath();
        writeMinimalDataset(dataset);
        FakeRunner runner = new FakeRunner(0, mode);
        CellposeLocalTrainingService service =
                new CellposeLocalTrainingService(config(true), runner);
        try {
            service.train(new CellposeDatasetPackager.PackagingResult(
                            dataset, commandFile(dataset), 1, 1, 1, 1),
                    "Artifact contract", CellposeLocalTrainingService.NO_PROGRESS);
            fail("Expected artifact contract failure for " + mode);
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(messagePart));
        }
    }

    private static ImagePlus geometryImage(String title,
                                           int width,
                                           int height,
                                           int channels,
                                           int slices,
                                           int frames) {
        ImageStack stack = new ImageStack(width, height);
        int planes = channels * slices * frames;
        for (int i = 0; i < planes; i++) {
            stack.addSlice(new ShortProcessor(width, height));
        }
        ImagePlus image = new ImagePlus(title, stack);
        image.setDimensions(channels, slices, frames);
        image.setOpenAsHyperStack(channels > 1 || frames > 1);
        return image;
    }

    private static ImagePlusProvider provider(final ImagePlus image) {
        return new ImagePlusProvider() {
            @Override public ImagePlus get(String imageName) {
                return image;
            }
        };
    }

    private static ClickStore clickStore(String imageName) {
        ClickStore store = new ClickStore();
        store.add(click(imageName, 1));
        return store;
    }

    private static ClickStore.Click click(String imageName, int label) {
        return new ClickStore.Click(imageName, 1, label, 1, 0.0, 0.0,
                ClickStore.Verdict.POSITIVE, 1L);
    }

    private static boolean containsFileNamed(Path root, final String fileName) throws IOException {
        java.util.stream.Stream<Path> stream = Files.walk(root);
        try {
            java.util.Iterator<Path> iterator = stream.iterator();
            while (iterator.hasNext()) {
                Path name = iterator.next().getFileName();
                if (name != null && fileName.equals(name.toString())) return true;
            }
            return false;
        } finally {
            stream.close();
        }
    }

    private static Path commandPath(List<String> command, String option) throws IOException {
        int index = command == null ? -1 : command.indexOf(option);
        if (index < 0 || index + 1 >= command.size()) {
            throw new IOException("Missing fake-runner command option " + option);
        }
        return java.nio.file.Paths.get(command.get(index + 1)).toAbsolutePath().normalize();
    }

    private static void writeArtifactMarker(Path marker,
                                            Path dataset,
                                            List<Path> artifacts,
                                            int candidateCount,
                                            long artifactBytes,
                                            String artifactSha256) throws IOException {
        Map<String, Object> root = JsonIO.object();
        root.put("version", Integer.valueOf(1));
        root.put("status", "success");
        root.put("artifactKind", "cellpose-model-weights");
        List<Object> relative = new ArrayList<Object>();
        for (Path artifact : artifacts) {
            relative.add(dataset.toAbsolutePath().normalize()
                    .relativize(artifact.toAbsolutePath().normalize())
                    .toString().replace('\\', '/'));
        }
        root.put("artifacts", relative);
        root.put("candidateCount", Integer.valueOf(candidateCount));
        root.put("validatedBy", "CellposeModel");
        root.put("cellposeVersion", CellposeRuntime.SUPPORTED_CELLPOSE_VERSION);
        root.put("artifactBytes", Long.valueOf(artifactBytes));
        root.put("artifactSha256", artifactSha256);
        Files.write(marker, (JsonIO.write(root) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(file));
        StringBuilder out = new StringBuilder(64);
        byte[] bytes = digest.digest();
        for (int i = 0; i < bytes.length; i++) {
            out.append(String.format(Locale.ROOT, "%02x", Integer.valueOf(bytes[i] & 0xff)));
        }
        return out.toString();
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

    private static void expectIllegalArgument(Runnable action, String messagePart) {
        try {
            action.run();
            fail("Expected IllegalArgumentException containing " + messagePart);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains(messagePart));
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private static List<String> stubCommand(String mode, int burstLines) {
        return testJavaCommand(CellposeOneShotIntegrationTest.StubProcessMain.class,
                mode, String.valueOf(burstLines));
    }

    private static List<String> testJavaCommand(Class<?> mainClass,
                                                String... arguments) {
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
        command.add(mainClass.getName());
        Collections.addAll(command, arguments);
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

    private static void forcePidCleanup(long pid) {
        if (pid <= 0L) {
            return;
        }
        try {
            if (!CellposeOneShotIntegrationTest.StubProcess.isPidAlive(pid)) {
                return;
            }
        } catch (Exception ignored) {
            // A failed liveness probe must not prevent best-effort cleanup.
        }
        Process killer = null;
        try {
            if (System.getProperty("os.name", "")
                    .toLowerCase(Locale.ROOT).contains("win")) {
                killer = new ProcessBuilder("taskkill", "/PID", String.valueOf(pid),
                        "/T", "/F").redirectErrorStream(true)
                        .redirectOutput(new java.io.File("NUL")).start();
            } else {
                killer = new ProcessBuilder("kill", "-KILL", String.valueOf(pid))
                        .redirectErrorStream(true)
                        .redirectOutput(new java.io.File("/dev/null")).start();
            }
            killer.waitFor(3L, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        } finally {
            if (killer != null && killer.isAlive()) {
                killer.destroyForcibly();
            }
        }
    }

    private enum ArtifactMode {
        VALID,
        MISSING_MARKER,
        AUXILIARY,
        AMBIGUOUS,
        WRONG_SIZE,
        MALFORMED_DIGEST,
        TAMPERED
    }

    private static final class FakeRunner implements CellposeLocalTrainingService.ProcessRunner {
        final int exitCode;
        final ArtifactMode artifactMode;
        final List<String> stdout = new ArrayList<String>();
        final List<String> stderr = new ArrayList<String>();
        IOException failure;
        List<String> command;

        FakeRunner(int exitCode, boolean reportModelPath) {
            this(exitCode, ArtifactMode.VALID);
        }

        FakeRunner(int exitCode, ArtifactMode artifactMode) {
            this.exitCode = exitCode;
            this.artifactMode = artifactMode == null ? ArtifactMode.VALID : artifactMode;
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
                Path model = commandPath(spec.command, "--expected-model");
                Path marker = commandPath(spec.command, "--artifact-marker");
                Files.createDirectories(model.getParent());
                Files.write(model, "modelA".getBytes(StandardCharsets.UTF_8));
                if (artifactMode == ArtifactMode.MISSING_MARKER) {
                    return new CellposeLocalTrainingService.ProcessResult(exitCode);
                }
                Path declared = model;
                List<Path> declaredArtifacts = new ArrayList<Path>();
                int candidateCount = 1;
                if (artifactMode == ArtifactMode.AUXILIARY) {
                    declared = model.getParent().resolve("training.log");
                    Files.write(declared, "auxiliary".getBytes(StandardCharsets.UTF_8));
                }
                declaredArtifacts.add(declared);
                if (artifactMode == ArtifactMode.AMBIGUOUS) {
                    Path second = model.getParent().resolve("second_model");
                    Files.write(second, "modelB".getBytes(StandardCharsets.UTF_8));
                    declaredArtifacts.add(second);
                    candidateCount = 2;
                }
                long bytes = Files.size(declared);
                String digest;
                try {
                    digest = sha256(declared);
                } catch (Exception e) {
                    throw new IOException("Could not hash fake model", e);
                }
                if (artifactMode == ArtifactMode.WRONG_SIZE) bytes++;
                if (artifactMode == ArtifactMode.MALFORMED_DIGEST) digest = "not-a-digest";
                writeArtifactMarker(marker, spec.workingDirectory, declaredArtifacts,
                        candidateCount, bytes, digest);
                if (artifactMode == ArtifactMode.TAMPERED) {
                    Files.write(model, "modelB".getBytes(StandardCharsets.UTF_8));
                }
            }
            return new CellposeLocalTrainingService.ProcessResult(exitCode);
        }
    }

    public static final class RootExitsFirstMain {
        private RootExitsFirstMain() {
        }

        public static void main(String[] args) throws Exception {
            Process child = new ProcessBuilder(stubCommand("LEAF", 8))
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            System.out.println("DESCENDANT_PID=" + processId(child));
            System.out.flush();
            Thread.sleep(2_000L);
        }
    }
}
