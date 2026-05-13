package flash.pipeline.image.variation;

/**
 * Immutable result from {@link MemoryEstimator#estimate}.
 */
public final class MemoryEstimate {

    public final long sourceBytes;
    public final long projectedBytes;
    public final long maxHeap;
    public final double headroomFraction;
    public final boolean exceedsBudget;
    public final String humanReadable;

    public MemoryEstimate(long sourceBytes, long projectedBytes, long maxHeap,
                          double headroomFraction, boolean exceedsBudget,
                          String humanReadable) {
        this.sourceBytes = sourceBytes;
        this.projectedBytes = projectedBytes;
        this.maxHeap = maxHeap;
        this.headroomFraction = headroomFraction;
        this.exceedsBudget = exceedsBudget;
        this.humanReadable = humanReadable == null ? "" : humanReadable;
    }

    @Override
    public String toString() {
        return humanReadable;
    }
}
