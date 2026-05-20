package flash.pipeline.image;

/**
 * Computes how many CPU threads each concurrent deep-learning inference should
 * be allowed to use, given a GPU-semaphore permit count and the host's logical
 * core count.
 * <p>
 * The rationale, in short: both TensorFlow (StarDist) and PyTorch/MKL
 * (Cellpose) default to grabbing every available logical thread. Running
 * {@code permits} concurrent inferences without capping each one produces
 * {@code permits × cores} software threads contending for {@code cores}
 * hardware threads — a context-switch storm that makes parallel inference
 * slower than serial on CPU. This helper turns a permit count into a thread
 * cap per inference, leaving one "slot" for non-DL work (the 3D Object
 * Analysis filter pool).
 * <p>
 * Formula: {@code threadsPerInference = max(2, cores / (permits + 1))}. The
 * {@code permits + 1} term reserves a slot for filter / loader pools. See
 * {@code docs/GPU_CONCURRENCY/PLAN_2_DL_THREAD_CAPPING.md}.
 */
public final class CpuBudget {

    /** Never cap below two threads — a single-threaded DL run risks pathological
     *  stalls on tiny batch dims where TF/Torch expect at least a pair of
     *  workers for prefetch overlap. */
    static final int MIN_THREADS_PER_INFERENCE = 2;

    private CpuBudget() {}

    /**
     * Thread cap for each concurrent DL inference.
     *
     * @param permits     GPU semaphore permit count (number of concurrent DL
     *                    inferences allowed). Values &lt; 1 are treated as 1.
     * @param totalCores  total logical cores on the host (typically
     *                    {@link Runtime#availableProcessors()}). Values &lt; 1
     *                    are treated as 1.
     * @return threads each inference may use. At least {@value #MIN_THREADS_PER_INFERENCE}.
     */
    public static int computeDlThreadBudget(int permits, int totalCores) {
        int p = permits < 1 ? 1 : permits;
        int c = totalCores < 1 ? 1 : totalCores;
        int share = c / (p + 1);
        return Math.max(MIN_THREADS_PER_INFERENCE, share);
    }
}
