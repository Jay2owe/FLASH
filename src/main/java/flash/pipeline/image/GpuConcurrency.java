package flash.pipeline.image;

import ij.IJ;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Concurrency primitives shared by deep-learning segmentation runners.
 * <p>
 * {@code gpuSemaphore()} caps simultaneous GPU inference calls across StarDist
 * and Cellpose. Permits are late-bound: a daemon probe runs at plugin init
 * ({@link #initAsync()}) and writes {@link #autoDetectedPermits}; any UI / CLI
 * override goes through {@link #setUserOverride(int)}; the semaphore is built
 * once on first access and the final permit count is logged for observability.
 * <p>
 * Thread capping (Plan 2) is live: each concurrent DL inference is capped to
 * {@link #threadsPerInference()} TF / MKL / OMP threads so permits &gt; 1 no
 * longer thrashes the CPU. {@link #DL_THREAD_CAPPING_ENABLED} toggles the
 * clamp-to-1 safety net: when {@code false}, any resolved permit count is
 * clamped to 1 regardless of auto-detect or user override. See
 * {@code docs/GPU_CONCURRENCY/PLAN_1_AUTO_DETECT_AND_UI.md} and
 * {@code docs/GPU_CONCURRENCY/PLAN_2_DL_THREAD_CAPPING.md}.
 * <p>
 * The GPU semaphore is now the sole gate on StarDist / Cellpose inference.
 * The old {@code STARDIST_LOCK} is gone: StarDist pads its channel dim so the
 * Z→T swap produces a hyperstack without a visible window, so there is no
 * shared {@code WindowManager} state to serialise against.
 */
public final class GpuConcurrency {

    /** Master gate on DL parallelism. Enabled now that TF / MKL / OMP threads
     *  are capped by {@link #threadsPerInference()} (see
     *  {@link CpuBudget#computeDlThreadBudget(int, int)}). Flip to {@code false}
     *  to restore the Plan 1 clamp-to-1 safety net without other code changes. */
    public static final boolean DL_THREAD_CAPPING_ENABLED = true;

    /** Auto-detected permits written by the init thread. {@code 0} until the
     *  probe completes. Volatile so post-init readers see it without locking. */
    private static volatile int autoDetectedPermits = 0;

    /** User override from Options dialog / CLI. {@code 0} = defer to auto. */
    private static volatile int userOverridePermits = 0;

    /** Completes when the init probe has finished. */
    private static final CountDownLatch INIT_LATCH = new CountDownLatch(1);

    /** Lazily constructed on first {@link #gpuSemaphore()} call, after auto
     *  and override are both resolved. */
    private static volatile Semaphore gpuSemaphore;
    /** Total (configured) permit count captured when the semaphore is built.
     *  Needed because {@link Semaphore#availablePermits()} reports <em>remaining</em>
     *  permits, which underreports when a caller queries from inside the
     *  semaphore block (e.g. {@link Cellpose3DRunner} setting env vars after
     *  acquiring a permit). */
    private static volatile int configuredPermits = 0;
    private static final Object LOCK = new Object();

    /** Initial-startup-property override path, preserved for backwards
     *  compatibility with existing {@code -Dihf.gpu.permits=N} invocations. */
    private static final String SYS_PROP = "ihf.gpu.permits";

    private GpuConcurrency() {}

    /**
     * Kicks off the probe thread. Idempotent — safe to call multiple times.
     * Does nothing after the first invocation.
     */
    public static void initAsync() {
        if (INIT_STARTED.getAndSet(true)) return;
        Thread t = new Thread(GpuConcurrency::runProbe, "ihf-gpu-probe");
        t.setDaemon(true);
        t.start();
    }
    private static final java.util.concurrent.atomic.AtomicBoolean INIT_STARTED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Sets the user override from UI / CLI. {@code 0} means "defer to
     * auto-detect". Must be called before {@link #gpuSemaphore()}; changes
     * after the semaphore is built have no effect (Semaphore does not support
     * safe runtime resize).
     */
    public static void setUserOverride(int permits) {
        userOverridePermits = permits < 0 ? 0 : permits;
    }

    /**
     * Returns the GPU inference semaphore, building it on first access from
     * the final resolved permit count. Blocks up to 2 s for the init probe.
     */
    public static Semaphore gpuSemaphore() {
        Semaphore s = gpuSemaphore;
        if (s != null) return s;
        synchronized (LOCK) {
            if (gpuSemaphore != null) return gpuSemaphore;
            // Kick off the probe if nobody else has yet. The init typically
            // happens at plugin startup, but this protects against DL code
            // being hit before the plugin UI loads (e.g. unit tests).
            initAsync();
            // Wait, but don't block a pipeline cold-start forever.
            try {
                INIT_LATCH.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            int permits = resolveFinalPermits(/* log */ true);
            configuredPermits = permits;
            gpuSemaphore = new Semaphore(permits, true);
            return gpuSemaphore;
        }
    }

    /**
     * Total (design-time) permit count captured when the semaphore was built.
     * Unlike {@link Semaphore#availablePermits()} this does <em>not</em>
     * fluctuate as permits are acquired / released. Use this when computing
     * thread budgets that must reflect the configured concurrency, not the
     * current idle capacity.
     */
    public static int configuredPermits() {
        // Ensure the semaphore is built so configuredPermits is populated.
        gpuSemaphore();
        return configuredPermits;
    }

    /** For tests / diagnostics. Latest auto-detected value, or 0 before the
     *  probe finishes. */
    public static int getAutoDetectedPermits() {
        return autoDetectedPermits;
    }

    /**
     * Per-inference CPU thread cap for StarDist / Cellpose. Derived from the
     * configured permit count and {@link Runtime#availableProcessors()} via
     * {@link CpuBudget#computeDlThreadBudget(int, int)}.
     * <p>
     * Uses {@link #configuredPermits()}, not the semaphore's live available
     * count, so the cap remains stable whether the caller queries before or
     * after acquiring a permit.
     */
    public static int threadsPerInference() {
        int permits = configuredPermits();
        int cores = Runtime.getRuntime().availableProcessors();
        return CpuBudget.computeDlThreadBudget(permits, cores);
    }

    /**
     * Logs the final permit count and per-inference thread cap for the current
     * run. Safe to call multiple times — each call reprints the current
     * decision so every run's log captures the effective parallelism.
     */
    public static void logEffectivePermits() {
        int permits = configuredPermits();
        int cores = Runtime.getRuntime().availableProcessors();
        int tpi = CpuBudget.computeDlThreadBudget(permits, cores);
        String msg;
        if (!DL_THREAD_CAPPING_ENABLED) {
            msg = "GPU concurrency: " + permits + " permits in effect"
                    + " (thread capping disabled)";
        } else {
            msg = "GPU concurrency: " + permits + " permits in effect, "
                    + tpi + " threads per inference"
                    + " (cores=" + cores + ")";
        }
        try { IJ.log(msg); } catch (Throwable ignored) { }
    }

    // ── internals ──────────────────────────────────────────────────────

    private static void runProbe() {
        try {
            int vramPermits = GpuProbe.probeNvidiaVramPermits();
            int heapPermits = HeapBudget.heapPermitsFor(HeapBudget.BYTES_PER_STARDIST_WORKER);
            int ramPermits = SystemRamBudget.ramPermitsFor(SystemRamBudget.BYTES_PER_CELLPOSE_WORKER);

            int auto;
            if (vramPermits <= 0) {
                auto = Math.max(1, Math.min(heapPermits, ramPermits));
            } else {
                auto = Math.max(1, Math.min(vramPermits, Math.min(heapPermits, ramPermits)));
            }
            autoDetectedPermits = auto;

            // Log the probe decision here so the auto value is visible even if
            // the semaphore is never constructed this run (e.g. pipeline that
            // skips DL analyses).
            logProbeDecision(vramPermits, heapPermits, ramPermits, auto);
        } catch (Throwable t) {
            autoDetectedPermits = 1;
            try {
                IJ.log("GPU concurrency: probe failed (" + t.getClass().getSimpleName()
                        + "), defaulting to 1 permit");
            } catch (Throwable ignored) { }
        } finally {
            INIT_LATCH.countDown();
        }
    }

    private static void logProbeDecision(int vramPermits, int heapPermits,
                                         int ramPermits, int auto) {
        StringBuilder sb = new StringBuilder("GPU concurrency probe: ");
        if (vramPermits <= 0) {
            sb.append("no CUDA GPU; CPU inference");
        } else {
            int vramMiB = GpuProbe.probeNvidiaVramMiB();
            sb.append("vram=").append(vramPermits)
              .append(" (").append(vramMiB).append(" MiB)");
        }
        sb.append(", heap=").append(heapPermits)
          .append(", ram=").append(ramPermits)
          .append(", auto=").append(auto);
        try {
            IJ.log(sb.toString());
        } catch (Throwable ignored) { }
    }

    private static int resolveFinalPermits(boolean log) {
        int raw;
        String reason;
        // Priority: explicit user override > -Dihf.gpu.permits > auto-detect.
        if (userOverridePermits > 0) {
            raw = userOverridePermits;
            reason = "user override";
        } else {
            String prop = System.getProperty(SYS_PROP);
            if (prop != null && !prop.trim().isEmpty()) {
                int parsed = -1;
                try { parsed = Integer.parseInt(prop.trim()); } catch (NumberFormatException ignored) { }
                if (parsed >= 1) {
                    raw = parsed;
                    reason = "-D" + SYS_PROP;
                } else {
                    raw = Math.max(1, autoDetectedPermits);
                    reason = "auto-detect";
                }
            } else {
                raw = Math.max(1, autoDetectedPermits);
                reason = "auto-detect";
            }
        }

        int clamped = DL_THREAD_CAPPING_ENABLED ? raw : Math.min(raw, 1);
        if (log) {
            String msg;
            if (clamped == raw) {
                msg = "GPU concurrency: " + clamped + " permits (" + reason + ")";
            } else {
                msg = "GPU concurrency: " + clamped + " permits (" + reason
                        + "=" + raw + ", clamped pending DL thread capping)";
            }
            try { IJ.log(msg); } catch (Throwable ignored) { }
        }
        return clamped;
    }
}
