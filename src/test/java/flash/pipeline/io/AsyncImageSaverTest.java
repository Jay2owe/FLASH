package flash.pipeline.io;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.concurrent.CountDownLatch;
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

    @Before
    public void setUp() {
        AsyncImageSaver.resetForTest();
    }

    @After
    public void tearDown() {
        AsyncImageSaver.resetForTest();
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
    public void failingJobDoesNotBreakDrain() throws Exception {
        AsyncImageSaver.submitTask(new Runnable() {
            @Override public void run() { /* success */ }
        });
        AsyncImageSaver.submitTask(new Runnable() {
            @Override public void run() { throw new RuntimeException("test failure"); }
        });
        AsyncImageSaver.submitTask(new Runnable() {
            @Override public void run() { /* success */ }
        });

        // Should not throw
        AsyncImageSaver.waitForAllWithProgress(2);
        assertEquals(0, AsyncImageSaver.pendingCount());

        // Subsequent batch should still work
        final AtomicInteger done = new AtomicInteger(0);
        AsyncImageSaver.submitTask(new Runnable() {
            @Override public void run() { done.incrementAndGet(); }
        });
        AsyncImageSaver.waitForAllWithProgress(1);
        assertEquals(1, done.get());
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

    private static void updateMax(AtomicInteger max, int value) {
        int prev;
        do {
            prev = max.get();
            if (value <= prev) break;
        } while (!max.compareAndSet(prev, value));
    }
}
