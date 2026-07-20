package flash.pipeline.cellpose;

import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.DependencyRegistry;
import flash.pipeline.runtime.DependencySpec;
import flash.pipeline.runtime.DependencyStatus;
import org.junit.After;
import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    public void dependencyProbeReportsCheckingWithoutWaitingForSlowBackend() throws Exception {
        DependencyRegistry.snapshotStatuses(Collections.<DependencySpec>emptyList());
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        CellposeRuntime.setProbeBackendForTest(new CellposeRuntime.ProbeBackend() {
            @Override public CellposeRuntime.Status probeConfigured() {
                started.countDown();
                await(release);
                return readyStatus(false);
            }
        });

        final CountDownLatch returned = new CountDownLatch(1);
        final AtomicReference<DependencyStatus> statusRef = new AtomicReference<DependencyStatus>();
        final AtomicReference<Throwable> failureRef = new AtomicReference<Throwable>();
        Thread snapshotThread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    statusRef.set(DependencyRegistry.snapshotStatuses(Collections.singletonList(
                            DependencyRegistry.get(DependencyId.CELLPOSE_RUNTIME)))
                            .get(DependencyId.CELLPOSE_RUNTIME));
                } catch (Throwable t) {
                    failureRef.set(t);
                } finally {
                    returned.countDown();
                }
            }
        }, "cellpose-dependency-probe-test");
        snapshotThread.setDaemon(true);
        snapshotThread.start();

        try {
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertTrue("Dependency snapshot should not wait for the slow Cellpose backend.",
                    returned.await(500, TimeUnit.MILLISECONDS));
            if (failureRef.get() != null) {
                throw new AssertionError(failureRef.get());
            }
            DependencyStatus status = statusRef.get();
            assertTrue(status.getDetailMessage(), status.isChecking());
            assertFalse(status.getDetailMessage(), status.needsAttention());
        } finally {
            release.countDown();
            snapshotThread.join(1000);
        }
        assertTrue(CellposeRuntime.probeAsync().get(1, TimeUnit.SECONDS).ready);
    }

    @Test
    public void dependencyProbeReportsCheckingWhenStaleMissingCacheRefreshes() throws Exception {
        final AtomicLong now = new AtomicLong(1_000L);
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch refreshStarted = new CountDownLatch(1);
        final CountDownLatch releaseRefresh = new CountDownLatch(1);
        CellposeRuntime.setClockForTest(new java.util.function.LongSupplier() {
            @Override public long getAsLong() {
                return now.get();
            }
        });
        CellposeRuntime.setProbeBackendForTest(new CellposeRuntime.ProbeBackend() {
            @Override public CellposeRuntime.Status probeConfigured() {
                int call = calls.incrementAndGet();
                if (call == 1) {
                    return missingStatus(call);
                }
                refreshStarted.countDown();
                await(releaseRefresh);
                return readyStatus(false);
            }
        });

        assertFalse(CellposeRuntime.probeAsync().get(1, TimeUnit.SECONDS).ready);
        now.addAndGet(CellposeRuntime.MISSING_TTL_MS + 1L);

        DependencyStatus status = snapshotCellposeDependencyStatus();

        try {
            assertTrue(refreshStarted.await(1, TimeUnit.SECONDS));
            assertTrue(status.getDetailMessage(), status.isChecking());
            assertFalse(status.getDetailMessage(), status.needsAttention());
        } finally {
            releaseRefresh.countDown();
        }
        assertTrue(CellposeRuntime.probeAsync().get(1, TimeUnit.SECONDS).ready);
    }

    @Test
    public void dependencyProbeReportsCheckingWhenStaleReadyCacheRefreshes() throws Exception {
        final AtomicLong now = new AtomicLong(1_000L);
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch refreshStarted = new CountDownLatch(1);
        final CountDownLatch releaseRefresh = new CountDownLatch(1);
        CellposeRuntime.setClockForTest(new java.util.function.LongSupplier() {
            @Override public long getAsLong() {
                return now.get();
            }
        });
        CellposeRuntime.setProbeBackendForTest(new CellposeRuntime.ProbeBackend() {
            @Override public CellposeRuntime.Status probeConfigured() {
                int call = calls.incrementAndGet();
                if (call == 1) {
                    return readyStatus(false);
                }
                refreshStarted.countDown();
                await(releaseRefresh);
                return missingStatus(call);
            }
        });

        assertTrue(CellposeRuntime.probeAsync().get(1, TimeUnit.SECONDS).ready);
        now.addAndGet(CellposeRuntime.READY_TTL_MS + 1L);

        DependencyStatus status = snapshotCellposeDependencyStatus();

        try {
            assertTrue(refreshStarted.await(1, TimeUnit.SECONDS));
            assertTrue(status.getDetailMessage(), status.isChecking());
            assertFalse("Stale ready cache must not allow the feature while refresh is running.",
                    status.isPresent());
        } finally {
            releaseRefresh.countDown();
        }
        assertFalse(CellposeRuntime.probeAsync().get(1, TimeUnit.SECONDS).ready);
    }

    private static DependencyStatus snapshotCellposeDependencyStatus() {
        return DependencyRegistry.snapshotStatuses(Collections.singletonList(
                DependencyRegistry.get(DependencyId.CELLPOSE_RUNTIME)))
                .get(DependencyId.CELLPOSE_RUNTIME);
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
