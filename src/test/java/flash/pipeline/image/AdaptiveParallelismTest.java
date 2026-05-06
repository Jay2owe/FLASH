package flash.pipeline.image;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AdaptiveParallelismTest {

    @Test
    public void computeSafeThreadsForInMemoryTasks_clampsToAvailableWorkWhenEstimateUnavailable() {
        assertEquals(3, AdaptiveParallelism.computeSafeThreadsForInMemoryTasks(0L, 8, 3));
    }

    @Test
    public void computeSafeThreadsForInMemoryTasks_reducesToSingleWorkerWhenEstimateIsHuge() {
        assertEquals(1, AdaptiveParallelism.computeSafeThreadsForInMemoryTasks(Long.MAX_VALUE / 4L, 8, 8));
    }
}
