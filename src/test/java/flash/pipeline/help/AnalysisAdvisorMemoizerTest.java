package flash.pipeline.help;

import flash.pipeline.TestConfigFiles;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.project.ProjectFile;
import flash.pipeline.project.ProjectFileIO;
import ij.IJ;
import ij.ImagePlus;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AnalysisAdvisorMemoizerTest {

    private Path tmp;

    @Before
    public void setUp() throws Exception {
        tmp = Files.createTempDirectory("flash-advisor-memoizer");
    }

    @After
    public void tearDown() throws Exception {
        if (tmp != null) {
            deleteRecursively(tmp);
        }
    }

    @Test
    public void recommendDoesNotWriteBfmemoNextToImage() throws Exception {
        Path input = Files.createDirectories(tmp.resolve("input"));
        Path tiff = input.resolve("Sample-Mouse1_LH_CA1.tif");
        createTinyTiff(tiff);

        TestConfigFiles.writeChannelConfig(tmp.toFile(),
                TestConfigFiles.basicBinConfig("Channel1", "Channel2"));

        AnalysisAdvisor advisor = new AnalysisAdvisor();
        AdvisorResult result = advisor.recommend(tmp.toFile());
        assertNotNull("advisor must return a result", result);

        List<Path> bfmemo = findBfmemoFiles(tmp);
        if (!bfmemo.isEmpty()) {
            StringBuilder sb = new StringBuilder(
                    "AnalysisAdvisor leaked .bfmemo files into the data folder: ");
            for (Path p : bfmemo) sb.append('\n').append(p);
            fail(sb.toString());
        }
    }

    @Test
    public void recommendUsesProjectManifestSourceForDimensions() throws Exception {
        Path outputRoot = Files.createDirectories(tmp.resolve("advisor-output"));
        Path sourceRoot = Files.createDirectories(tmp.resolve("advisor-sources"));
        Path staleRootTiff = outputRoot.resolve("stale-root.tif");
        Path manifestTiff = sourceRoot.resolve("manifest-source.tif");
        createTiff(staleRootTiff, 1, 4);
        createTiff(manifestTiff, 3, 4);
        TestConfigFiles.writeChannelConfig(outputRoot.toFile(),
                TestConfigFiles.basicBinConfig("DAPI", "GFP", "RFP", "CY5"));

        ProjectFile project = new ProjectFile();
        project.outputRoot = outputRoot.toString();
        project.items.add(projectItem(manifestTiff.toFile()));
        writeProject(outputRoot.toFile(), project);

        AdvisorResult result = new AnalysisAdvisor().recommend(outputRoot.toFile());

        assertEquals("standard-3d-intensity", result.suggestedRecipe());
    }

    @Test
    public void recommendDoesNotScanOutputRootWhenManifestHasNoSources() throws Exception {
        Path outputRoot = Files.createDirectories(tmp.resolve("advisor-empty-output"));
        Path staleRootTiff = outputRoot.resolve("stale-root.tif");
        createTiff(staleRootTiff, 3, 4);
        TestConfigFiles.writeChannelConfig(outputRoot.toFile(),
                TestConfigFiles.basicBinConfig("DAPI", "GFP", "RFP", "CY5"));

        ProjectFile project = new ProjectFile();
        project.outputRoot = outputRoot.toString();
        writeProject(outputRoot.toFile(), project);

        AdvisorResult result = new AnalysisAdvisor().recommend(outputRoot.toFile());

        assertEquals("I don't see any images in this folder.", result.title());
        assertNull(result.suggestedRecipe());
    }

    @Test
    public void preInterruptedDimensionSniffRestoresInterruptAndCancelsWorker() throws Exception {
        final AtomicReference<Thread> worker = new AtomicReference<Thread>();
        final AtomicBoolean workerInterrupted = new AtomicBoolean(false);
        Thread.currentThread().interrupt();
        try {
            AnalysisAdvisor.DimensionSniff result =
                    AnalysisAdvisor.runDimensionSniffForTests(
                            new Callable<AnalysisAdvisor.DimensionSniff>() {
                                @Override public AnalysisAdvisor.DimensionSniff call() {
                                    worker.set(Thread.currentThread());
                                    try {
                                        new CountDownLatch(1).await();
                                    } catch (InterruptedException expected) {
                                        workerInterrupted.set(true);
                                    }
                                    return AnalysisAdvisor.DimensionSniff.uncertain();
                                }
                            }, 5000L);

            assertFalse(result.confident);
            assertTrue("caller interrupt must be restored",
                    Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        waitForWorkerStop(worker.get());
        if (worker.get() != null) {
            assertTrue("started worker must receive cancellation", workerInterrupted.get());
        }
    }

    @Test
    public void wrappedVirtualMachineErrorIsRethrownWithIdentityPreserved() throws Exception {
        final SentinelVirtualMachineError fatal = new SentinelVirtualMachineError();
        final AtomicReference<Thread> worker = new AtomicReference<Thread>();
        try {
            AnalysisAdvisor.runDimensionSniffForTests(
                    new Callable<AnalysisAdvisor.DimensionSniff>() {
                        @Override public AnalysisAdvisor.DimensionSniff call() {
                            worker.set(Thread.currentThread());
                            throw fatal;
                        }
                    }, 5000L);
            fail("expected the worker's fatal error");
        } catch (SentinelVirtualMachineError expected) {
            assertSame(fatal, expected);
        }
        waitForWorkerStop(worker.get());
    }

    @Test
    public void wrappedThreadDeathIsRethrownWithIdentityPreserved() throws Exception {
        final ThreadDeath fatal = new ThreadDeath();
        final AtomicReference<Thread> worker = new AtomicReference<Thread>();
        try {
            AnalysisAdvisor.runDimensionSniffForTests(
                    new Callable<AnalysisAdvisor.DimensionSniff>() {
                        @Override public AnalysisAdvisor.DimensionSniff call() {
                            worker.set(Thread.currentThread());
                            throw fatal;
                        }
                    }, 5000L);
            fail("expected the worker's ThreadDeath");
        } catch (ThreadDeath expected) {
            assertSame(fatal, expected);
        }
        waitForWorkerStop(worker.get());
    }

    @Test
    public void directProjectReaderFatalIsNotConvertedToUncertainty() {
        final SentinelVirtualMachineError fatal = new SentinelVirtualMachineError();
        try {
            AnalysisAdvisor.readProjectDimensions(tmp.toFile(),
                    new AnalysisAdvisor.ProjectMetadataSource() {
                        @Override public List<flash.pipeline.io.SeriesMeta> read(File directory) {
                            throw fatal;
                        }
                    });
            fail("expected project-reader fatal error");
        } catch (SentinelVirtualMachineError expected) {
            assertSame(fatal, expected);
        }
    }

    @Test
    public void directFileReaderFatalIsNotConvertedToUncertainty() throws Exception {
        Path image = tmp.resolve("fatal-reader.tif");
        Files.write(image, new byte[] { 1 });
        final SentinelVirtualMachineError fatal = new SentinelVirtualMachineError();
        try {
            AnalysisAdvisor.readFileDimensions(image.toFile(),
                    new AnalysisAdvisor.FileDimensionSource() {
                        @Override public int readZSize(File sourceFile) {
                            throw fatal;
                        }
                    });
            fail("expected file-reader fatal error");
        } catch (SentinelVirtualMachineError expected) {
            assertSame(fatal, expected);
        }
    }

    @Test
    public void ordinaryWorkerFailureRemainsUncertainAndWorkerStops() throws Exception {
        final AtomicReference<Thread> worker = new AtomicReference<Thread>();
        AnalysisAdvisor.DimensionSniff result = AnalysisAdvisor.runDimensionSniffForTests(
                new Callable<AnalysisAdvisor.DimensionSniff>() {
                    @Override public AnalysisAdvisor.DimensionSniff call() throws Exception {
                        worker.set(Thread.currentThread());
                        throw new IOException("ordinary reader failure");
                    }
                }, 1000L);

        assertTrue(result.hasImage);
        assertFalse(result.confident);
        waitForWorkerStop(worker.get());
    }

    @Test
    public void timeoutRemainsUncertainCancelsTaskAndStopsWorker() throws Exception {
        final AtomicReference<Thread> worker = new AtomicReference<Thread>();
        final AtomicBoolean interrupted = new AtomicBoolean(false);
        final CountDownLatch entered = new CountDownLatch(1);
        AnalysisAdvisor.DimensionSniff result = AnalysisAdvisor.runDimensionSniffForTests(
                new Callable<AnalysisAdvisor.DimensionSniff>() {
                    @Override public AnalysisAdvisor.DimensionSniff call() {
                        worker.set(Thread.currentThread());
                        entered.countDown();
                        try {
                            new CountDownLatch(1).await();
                        } catch (InterruptedException expected) {
                            interrupted.set(true);
                        }
                        return AnalysisAdvisor.DimensionSniff.uncertain();
                    }
                }, 5000L);

        assertTrue("worker should start before the timeout", entered.await(1L, TimeUnit.SECONDS));
        assertTrue(result.hasImage);
        assertFalse(result.confident);
        waitForTrue(interrupted, "timed-out worker must be interrupted");
        waitForWorkerStop(worker.get());
    }

    @Test
    public void ordinaryDirectReaderFailuresRemainUncertain() throws Exception {
        AnalysisAdvisor.DimensionSniff project = AnalysisAdvisor.readProjectDimensions(
                tmp.toFile(), new AnalysisAdvisor.ProjectMetadataSource() {
                    @Override public List<flash.pipeline.io.SeriesMeta> read(File directory)
                            throws Throwable {
                        throw new IOException("project metadata unavailable");
                    }
                });
        Path image = tmp.resolve("ordinary-reader.tif");
        Files.write(image, new byte[] { 1 });
        AnalysisAdvisor.DimensionSniff file = AnalysisAdvisor.readFileDimensions(
                image.toFile(), new AnalysisAdvisor.FileDimensionSource() {
                    @Override public int readZSize(File sourceFile) throws Throwable {
                        throw new IOException("image metadata unavailable");
                    }
                });

        assertFalse(project.confident);
        assertFalse(file.confident);
    }

    private static void createTinyTiff(Path target) throws IOException {
        createTiff(target, 1, 2);
    }

    private static void createTiff(Path target, int slices, int channels) throws IOException {
        ImagePlus imp = IJ.createImage(target.getFileName().toString(),
                "16-bit", 8, 8, channels, slices, 1);
        try {
            IJ.saveAsTiff(imp, target.toString());
            assertTrue("synthesised TIFF must exist: " + target,
                    Files.isRegularFile(target) && Files.size(target) > 0);
        } finally {
            imp.close();
        }
    }

    private static ProjectFile.Item projectItem(File source) {
        ProjectFile.Item item = new ProjectFile.Item();
        item.path = source.getAbsolutePath();
        item.include = true;
        return item;
    }

    private static void writeProject(File outputRoot, ProjectFile project) throws Exception {
        ProjectFileIO.write(
                FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath()).configurationWriteDir(),
                project);
    }

    private static List<Path> findBfmemoFiles(Path root) throws IOException {
        final List<Path> hits = new ArrayList<Path>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".bfmemo")) hits.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        return hits;
    }

    private static void waitForTrue(AtomicBoolean value, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (!value.get() && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertTrue(message, value.get());
    }

    private static void waitForWorkerStop(Thread worker) throws Exception {
        if (worker == null) {
            return;
        }
        worker.join(2000L);
        assertFalse("dimension-sniff worker must stop", worker.isAlive());
    }

    private static final class SentinelVirtualMachineError extends VirtualMachineError {
        private static final long serialVersionUID = 1L;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                    throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
