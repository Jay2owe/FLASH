package flash.pipeline.cellpose;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class CellposeRuntimeCacheTest {

    @After
    public void tearDown() {
        CellposeRuntime.resetTestHooks();
        CellposeRuntime.invalidateCache();
    }

    @Test
    public void cachedStatusIsUnknownBeforeFirstProbe() {
        CellposeRuntime.invalidateCache();

        CellposeRuntime.Status status = CellposeRuntime.cachedStatus();

        assertTrue(status.unknown);
        assertFalse(status.ready);
        assertEquals("Checking Cellpose...", status.message);
    }

    @Test
    public void probeAsyncCoalescesConcurrentCallersAndProbeConfiguredUsesFreshCache() throws Exception {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        CellposeRuntime.setProbeBackendForTest(new CellposeRuntime.ProbeBackend() {
            @Override public CellposeRuntime.Status probeConfigured() {
                calls.incrementAndGet();
                started.countDown();
                await(release);
                return readyStatus(false);
            }
        });

        CompletableFuture<CellposeRuntime.Status> first = CellposeRuntime.probeAsync();
        CompletableFuture<CellposeRuntime.Status> second = CellposeRuntime.probeAsync();

        assertSame(first, second);
        assertTrue(started.await(1, TimeUnit.SECONDS));
        release.countDown();
        assertTrue(first.get(1, TimeUnit.SECONDS).ready);
        assertEquals(1, calls.get());

        CellposeRuntime.Status sync = CellposeRuntime.probeConfigured();

        assertTrue(sync.ready);
        assertEquals("Fresh cache should avoid another backend call", 1, calls.get());
    }

    @Test
    public void nonReadyStatusExpiresAfterMissingTtl() throws Exception {
        final AtomicLong now = new AtomicLong(1_000L);
        final AtomicInteger calls = new AtomicInteger();
        CellposeRuntime.setClockForTest(new java.util.function.LongSupplier() {
            @Override public long getAsLong() {
                return now.get();
            }
        });
        CellposeRuntime.setProbeBackendForTest(new CellposeRuntime.ProbeBackend() {
            @Override public CellposeRuntime.Status probeConfigured() {
                return missingStatus(calls.incrementAndGet());
            }
        });

        assertEquals("missing-1", CellposeRuntime.probeAsync().get(1, TimeUnit.SECONDS).message);
        now.addAndGet(CellposeRuntime.MISSING_TTL_MS + 1L);

        assertEquals("missing-2", CellposeRuntime.probeAsync().get(1, TimeUnit.SECONDS).message);
        assertEquals(2, calls.get());
    }

    @Test
    public void readyStatusDoesNotExpireAtMissingTtl() throws Exception {
        final AtomicLong now = new AtomicLong(1_000L);
        final AtomicInteger calls = new AtomicInteger();
        CellposeRuntime.setClockForTest(new java.util.function.LongSupplier() {
            @Override public long getAsLong() {
                return now.get();
            }
        });
        CellposeRuntime.setProbeBackendForTest(new CellposeRuntime.ProbeBackend() {
            @Override public CellposeRuntime.Status probeConfigured() {
                calls.incrementAndGet();
                return readyStatus(false);
            }
        });

        assertTrue(CellposeRuntime.probeAsync().get(1, TimeUnit.SECONDS).ready);
        now.addAndGet(CellposeRuntime.MISSING_TTL_MS + 1L);

        assertTrue(CellposeRuntime.probeAsync().get(1, TimeUnit.SECONDS).ready);
        assertEquals(1, calls.get());
    }

    @Test
    public void invalidatePreventsOlderInFlightCompletionFromRepopulatingCache() throws Exception {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        CellposeRuntime.setProbeBackendForTest(new CellposeRuntime.ProbeBackend() {
            @Override public CellposeRuntime.Status probeConfigured() {
                started.countDown();
                await(release);
                return readyStatus(true);
            }
        });

        CompletableFuture<CellposeRuntime.Status> future = CellposeRuntime.probeAsync();
        assertTrue(started.await(1, TimeUnit.SECONDS));
        CellposeRuntime.invalidateCache();
        release.countDown();

        assertTrue(future.get(1, TimeUnit.SECONDS).ready);
        assertTrue(CellposeRuntime.cachedStatus().unknown);
    }

    @Test
    public void setPythonPathInvalidatesCachedStatus() throws Exception {
        String original = CellposeRuntime.getPythonPath();
        try {
            CellposeRuntime.setProbeBackendForTest(new CellposeRuntime.ProbeBackend() {
                @Override public CellposeRuntime.Status probeConfigured() {
                    return readyStatus(false);
                }
            });
            assertTrue(CellposeRuntime.probeAsync().get(1, TimeUnit.SECONDS).ready);

            CellposeRuntime.setPythonPath(original);

            assertTrue(CellposeRuntime.cachedStatus().unknown);
        } finally {
            CellposeRuntime.setPythonPath(original);
        }
    }

    private static CellposeRuntime.Status readyStatus(boolean gpu) {
        return new CellposeRuntime.Status("python", true, true, true,
                CellposeRuntime.SUPPORTED_CELLPOSE_VERSION, gpu,
                "Cellpose is ready.", "");
    }

    private static CellposeRuntime.Status missingStatus(int call) {
        return new CellposeRuntime.Status("python", true, true, false, "", false,
                "missing-" + call, "details-" + call);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test latch.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
