package flash.pipeline.io;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;

import static org.junit.Assert.*;

/**
 * Tests for {@link BoundedImageLoader} edge cases.
 */
public class BoundedImageLoaderTest {

    @Test
    public void start_noOpsOnEmptyIndexList() throws InterruptedException {
        // Empty schedule should not crash or create any threads
        BoundedImageLoader loader = new BoundedImageLoader(
                null, // supplier not needed — no indices to load
                Collections.<Integer>emptyList(),
                2);
        loader.start();

        // take() should return null immediately (all producers done)
        assertNull(loader.take());
    }

    @Test
    public void totalToLoad_returnsZeroForEmptySchedule() {
        BoundedImageLoader loader = new BoundedImageLoader(
                null,
                new ArrayList<Integer>(),
                2);

        assertEquals(0, loader.totalToLoad());
    }
}
