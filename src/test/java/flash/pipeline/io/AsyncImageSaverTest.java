package flash.pipeline.io;

import flash.pipeline.execution.AnalysisCancellation;
import flash.pipeline.testutil.TestWait;
import flash.pipeline.testutil.UiTestAssumptions;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.Opener;
import ij.process.ByteProcessor;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Regression tests for {@link AsyncImageSaver} proving that the writer pool
 * actually scales during drain and resets cleanly between batches.
 */
public class AsyncImageSaverTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();
    private TestWait.ResourceSnapshot resources;
    private long originalKeepAliveMillis;

    @Before
    public void setUp() throws Exception {
        AsyncImageSaver.resetForTest();
        ThreadPoolExecutor saverPool = saverPool();
        originalKeepAliveMillis = saverPool.getKeepAliveTime(TimeUnit.MILLISECONDS);
        saverPool.setKeepAliveTime(10L, TimeUnit.MILLISECONDS);
        initializeImageIoInfrastructure();
        resources = UiTestAssumptions.snapshotOwnedResources();
        AsyncImageSaver.resetForTest();
    }

    @After
    public void tearDown() throws Exception {
        try {
            AsyncImageSaver.resetForTest();
            resources.assertNoLeaks("AsyncImageSaverTest", 2000L);
        } finally {
            saverPool().setKeepAliveTime(originalKeepAliveMillis, TimeUnit.MILLISECONDS);
        }
    }

    // ── serial drain ────────────────────────────────────────────────

    @Test
    public void singleWriterDrainStaysSerial() throws Exception {
        final AtomicInteger maxConcurrent = new AtomicInteger(0);
        final AtomicInteger running = new AtomicInteger(0);

        for (int i = 0; i < 5; i++) {
            AsyncImageSaver.submitTask(new Runnable() {
                @Override
                public void run() {
                    int r = running.incrementAndGet();
                    updateMax(maxConcurrent, r);
                    running.decrementAndGet();
                }
            });
        }

        AsyncImageSaver.waitForAllWithProgress(1);

        assertEquals("Max concurrent writers should be 1", 1, maxConcurrent.get());
        assertEquals(0, AsyncImageSaver.pendingCount());
    }

    // ── multi-writer drain ──────────────────────────────────────────

    @Test
    public void multiWriterDrainRunsConcurrently() throws Exception {
        final AtomicInteger maxConcurrent = new AtomicInteger(0);
        final AtomicInteger running = new AtomicInteger(0);
        final CountDownLatch gate = new CountDownLatch(1);
        final CountDownLatch allStarted = new CountDownLatch(3);

        for (int i = 0; i < 5; i++) {
            AsyncImageSaver.submitTask(new Runnable() {
                @Override
                public void run() {
                    int r = running.incrementAndGet();
                    updateMax(maxConcurrent, r);
                    allStarted.countDown();
                    try { gate.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                    running.decrementAndGet();
                }
            });
        }

        Thread drainThread = new Thread(new Runnable() {
            @Override
            public void run() {
                AsyncImageSaver.waitForAllWithProgress(3);
            }
        }, "test-drain");
        drainThread.start();

        assertTrue("Expected 3 concurrent jobs to start",
                allStarted.await(10, TimeUnit.SECONDS));
        assertTrue("Max concurrent should be >= 2 (was " + maxConcurrent.get() + ")",
                maxConcurrent.get() >= 2);

        gate.countDown();
        drainThread.join(10000);
        assertFalse("Drain thread should have finished", drainThread.isAlive());
        assertEquals(0, AsyncImageSaver.pendingCount());
    }

    // ── state reset across batches ──────────────────────────────────

    @Test
    public void stateResetsAcrossBatches() throws Exception {
        // First batch
        for (int i = 0; i < 3; i++) {
            AsyncImageSaver.submitTask(new Runnable() {
                @Override public void run() { }
            });
        }
        assertEquals(3, AsyncImageSaver.pendingCount());
        AsyncImageSaver.waitForAllWithProgress(2);
        assertEquals(0, AsyncImageSaver.pendingCount());

        // Second batch should work independently
        final AtomicInteger batchTwoDone = new AtomicInteger(0);
        for (int i = 0; i < 2; i++) {
            AsyncImageSaver.submitTask(new Runnable() {
                @Override
                public void run() {
                    batchTwoDone.incrementAndGet();
                }
            });
        }
        assertEquals(2, AsyncImageSaver.pendingCount());
        AsyncImageSaver.waitForAllWithProgress(1);
        assertEquals(0, AsyncImageSaver.pendingCount());
        assertEquals(2, batchTwoDone.get());
    }

    // ── failure handling ────────────────────────────────────────────

    @Test
    public void failingJobsDrainAndSurfaceEveryTargetAndCause() throws Exception {
        final AtomicInteger attempted = new AtomicInteger(0);
        AsyncImageSaver.submitTask("success-before.tif", new Runnable() {
            @Override public void run() { attempted.incrementAndGet(); }
        });
        AsyncImageSaver.submitTask("failed-one.tif", new Runnable() {
            @Override public void run() {
                attempted.incrementAndGet();
                throw new IllegalStateException("first injected cause");
            }
        });
        AsyncImageSaver.submitTask("failed-two.png", new Runnable() {
            @Override public void run() {
                attempted.incrementAndGet();
                throw new IllegalArgumentException("second injected cause");
            }
        });
        AsyncImageSaver.submitTask("success-after.tif", new Runnable() {
            @Override public void run() { attempted.incrementAndGet(); }
        });

        try {
            AsyncImageSaver.waitForAllWithProgress(2);
            fail("Required image publication failures must be surfaced");
        } catch (AsyncImageSaver.ImagePublicationException expected) {
            assertEquals(2, expected.getFailures().size());
            assertTrue(expected.getMessage().contains("failed-one.tif"));
            assertTrue(expected.getMessage().contains("first injected cause"));
            assertTrue(expected.getMessage().contains("failed-two.png"));
            assertTrue(expected.getMessage().contains("second injected cause"));
            assertEquals(IllegalStateException.class,
                    expected.getFailures().get(0).getCause().getClass());
            assertEquals(IllegalArgumentException.class,
                    expected.getFailures().get(1).getCause().getClass());
        }
        assertEquals("Every queued job must be attempted despite earlier failures",
                4, attempted.get());
        assertEquals(0, AsyncImageSaver.pendingCount());

        // Subsequent batch should still work
        final AtomicInteger done = new AtomicInteger(0);
        AsyncImageSaver.submitTask(new Runnable() {
            @Override public void run() { done.incrementAndGet(); }
        });
        AsyncImageSaver.waitForAllWithProgress(1);
        assertEquals(1, done.get());
    }

    @Test
    public void writeReopenAndMoveFaultsPreservePriorImageGeneration() throws Exception {
        final byte[] sentinel = new byte[] {9, 8, 7, 6, 5};
        ImageStack stack = new ImageStack(2, 1);
        stack.addSlice(new ByteProcessor(2, 1, new byte[] {1, 2}, null));
        ImagePlus source = new ImagePlus("fault-source", stack);
        try {
            for (final ImageFault fault : ImageFault.values()) {
                AsyncImageSaver.resetForTest();
                final AsyncImageSaver.ImagePublicationOperations defaults =
                        AsyncImageSaver.defaultImagePublicationOperations();
                File target = new File(temp.getRoot(), "preserved-" + fault.name() + ".tif");
                Files.write(target.toPath(), sentinel);

                AsyncImageSaver.setImagePublicationOperationsForTest(
                        new AsyncImageSaver.ImagePublicationOperations() {
                            @Override
                            public boolean save(ImagePlus image, File staged,
                                                AsyncImageSaver.ImageFormat format)
                                    throws IOException {
                                if (fault == ImageFault.WRITE) {
                                    Files.write(staged.toPath(), new byte[] {1, 2});
                                    return false;
                                }
                                return defaults.save(image, staged, format);
                            }

                            @Override
                            public void validate(ImagePlus image, File staged,
                                                 AsyncImageSaver.ImageFormat format)
                                    throws IOException {
                                if (fault == ImageFault.REOPEN) {
                                    throw new IOException("injected reopen validation failure");
                                }
                                defaults.validate(image, staged, format);
                            }

                            @Override
                            public void replace(Path staged, Path destination)
                                    throws IOException {
                                if (fault == ImageFault.MOVE) {
                                    throw new IOException("injected replacement failure");
                                }
                                defaults.replace(staged, destination);
                            }
                        });

                AsyncImageSaver.saveAsTiffAsync(source, target.getAbsolutePath());
                try {
                    AsyncImageSaver.waitForAllWithProgress(1);
                    fail("Expected injected " + fault + " publication failure");
                } catch (AsyncImageSaver.ImagePublicationException expected) {
                    assertEquals(1, expected.getFailures().size());
                    assertTrue(expected.getMessage().contains(target.getAbsolutePath()));
                    assertTrue(expected.getMessage().contains(fault.messageFragment));
                }

                assertArrayEquals("Fault " + fault + " replaced the prior valid image",
                        sentinel, Files.readAllBytes(target.toPath()));
                File[] leftovers = target.getParentFile().listFiles((dir, name) ->
                        name.startsWith("." + target.getName() + ".")
                                && name.endsWith(".tif"));
                assertTrue("Fault " + fault + " exposed a staged image",
                        leftovers == null || leftovers.length == 0);
            }
        } finally {
            source.changes = false;
            source.close();
            AsyncImageSaver.resetForTest();
        }
    }

    // ── VM-fatal propagation ────────────────────────────────────────

    @Test
    public void vmFatalFailureIsRethrownAfterRemainingJobsDrain() {
        final OutOfMemoryError fatal = new OutOfMemoryError("injected fatal saver failure");
        final AtomicInteger afterFatal = new AtomicInteger(0);
        AsyncImageSaver.submitTask("fatal.tif", new Runnable() {
            @Override
            public void run() {
                throw fatal;
            }
        });
        AsyncImageSaver.submitTask("after-fatal.tif", new Runnable() {
            @Override
            public void run() {
                afterFatal.incrementAndGet();
            }
        });

        try {
            AsyncImageSaver.waitForAllWithProgress(2);
            fail("VM-fatal failure must not be converted to an ordinary publication error");
        } catch (OutOfMemoryError expected) {
            assertSame(fatal, expected);
        }
        assertEquals("Remaining jobs must drain before the fatal failure is rethrown",
                1, afterFatal.get());
        assertEquals(0, AsyncImageSaver.pendingCount());
    }

    // ── empty batch ─────────────────────────────────────────────────

    @Test
    public void emptyBatchCompletes() {
        assertEquals(0, AsyncImageSaver.pendingCount());
        AsyncImageSaver.waitForAllWithProgress(4); // must not hang
        assertEquals(0, AsyncImageSaver.pendingCount());
    }

    // ── helper ──────────────────────────────────────────────────────

    @Test
    public void guiCancellationDoesNotWaitForBlockedDrain() throws Exception {
        final CountDownLatch running = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicInteger queuedRan = new AtomicInteger(0);

        AsyncImageSaver.submitTask(new Runnable() {
            @Override
            public void run() {
                running.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            }
        });
        assertTrue("first save should start", running.await(5, TimeUnit.SECONDS));

        AsyncImageSaver.submitTask(new Runnable() {
            @Override
            public void run() {
                queuedRan.incrementAndGet();
            }
        });
        assertEquals(2, AsyncImageSaver.pendingCount());

        AnalysisCancellation.Scope scope = AnalysisCancellation.openGuiAnalysisScope();
        long elapsedMillis;
        try {
            AnalysisCancellation.markDialogCancelRequested();
            long started = System.nanoTime();
            AsyncImageSaver.waitForAllWithProgress(2);
            elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        } finally {
            scope.close();
            release.countDown();
        }

        assertTrue("cancelled drain should return promptly; took " + elapsedMillis + " ms",
                elapsedMillis < 1000L);
        assertEquals(0, AsyncImageSaver.pendingCount());
        TestWait.awaitLatch("blocked save worker to finish", finished, 2000L);
        resources.assertNoLeaks("cancelled AsyncImageSaver drain", 2000L);
        assertEquals("queued save should have been cancelled", 0, queuedRan.get());
    }

    @Test
    public void pngSavePublishesOnlyFinalFile() throws Exception {
        ImageStack stack = new ImageStack(2, 1);
        stack.addSlice(new ByteProcessor(2, 1, new byte[] {1, 2}, null));
        ImagePlus image = new ImagePlus("atomic-png", stack);
        File out = temp.newFile("saved.png");
        assertTrue(out.delete());

        AsyncImageSaver.saveAsPngAsync(image, out.getAbsolutePath());
        AsyncImageSaver.waitForAllWithProgress(1);

        assertTrue(out.isFile());
        File[] leftovers = out.getParentFile().listFiles((dir, name) ->
                name.startsWith("." + out.getName() + ".") && name.endsWith(".png"));
        assertTrue(leftovers == null || leftovers.length == 0);
    }

    @Test
    public void tiffStackSavePublishesReadableFullStack() throws Exception {
        ImageStack stack = new ImageStack(2, 1);
        stack.addSlice(new ByteProcessor(2, 1, new byte[] {1, 2}, null));
        stack.addSlice(new ByteProcessor(2, 1, new byte[] {3, 4}, null));
        ImagePlus image = new ImagePlus("atomic-stack", stack);
        File out = new File(temp.getRoot(), "saved-stack.tif");
        try {
            AsyncImageSaver.saveAsTiffAsync(image, out.getAbsolutePath());
            AsyncImageSaver.waitForAllWithProgress(1);

            ImagePlus reopened = new Opener().openImage(out.getAbsolutePath());
            assertNotNull(reopened);
            try {
                assertEquals(2, reopened.getWidth());
                assertEquals(1, reopened.getHeight());
                assertEquals(2, reopened.getStackSize());
            } finally {
                reopened.changes = false;
                reopened.close();
            }
        } finally {
            image.changes = false;
            image.close();
        }
    }

    private static void updateMax(AtomicInteger max, int value) {
        int prev;
        do {
            prev = max.get();
            if (value <= prev) break;
        } while (!max.compareAndSet(prev, value));
    }

    private enum ImageFault {
        WRITE("failed to save temporary"),
        REOPEN("injected reopen validation failure"),
        MOVE("injected replacement failure");

        final String messageFragment;

        ImageFault(String messageFragment) {
            this.messageFragment = messageFragment;
        }
    }

    private static ThreadPoolExecutor saverPool() throws Exception {
        Field field = AsyncImageSaver.class.getDeclaredField("IO_POOL");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(null);
    }

    private void initializeImageIoInfrastructure() throws Exception {
        ImageStack stack = new ImageStack(1, 1);
        stack.addSlice(new ByteProcessor(1, 1, new byte[] {1}, null));
        ImagePlus probe = new ImagePlus("image-io-infrastructure-probe", stack);
        File output = new File(temp.getRoot(), ".image-io-infrastructure-probe.tif");
        try {
            // FileSaver/Opener can lazily start JVM-global AWT infrastructure.
            // Exercise the real production path before the resource baseline so
            // those process-owned threads are not attributed to an individual test.
            AsyncImageSaver.saveAsTiffAsync(probe, output.getAbsolutePath());
            AsyncImageSaver.waitForAllWithProgress(1);
        } finally {
            probe.changes = false;
            probe.close();
            Files.deleteIfExists(output.toPath());
        }
    }
}
