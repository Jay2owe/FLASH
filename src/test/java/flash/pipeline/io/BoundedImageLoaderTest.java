package flash.pipeline.io;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
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

    @Test
    public void take_returnsLoadedImagesThenRethrowsLoaderFailure() throws Exception {
        DeferredImageSupplier supplier = new DeferredImageSupplier(
                Arrays.asList(new File("unused-0.tif"), new File("unused-1.tif")), "test") {
            @Override
            public ImagePlus openSeriesMaterialized(int seriesIndex) throws Exception {
                if (seriesIndex == 0) {
                    ImageStack stack = new ImageStack(1, 1);
                    stack.addSlice(new ByteProcessor(1, 1));
                    return new ImagePlus("loaded", stack);
                }
                throw new Exception("boom");
            }
        };

        BoundedImageLoader loader = new BoundedImageLoader(
                supplier, Arrays.asList(0, 1), 2, 1);
        loader.start();

        BoundedImageLoader.IndexedImage first = loader.take();
        assertNotNull(first);
        assertEquals(0, first.index);

        try {
            loader.take();
            fail("Expected loader failure to be rethrown");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Failed to load series 2"));
            assertNotNull(expected.getCause());
            assertEquals("boom", expected.getCause().getMessage());
        } finally {
            first.image.close();
            first.image.flush();
        }
    }
}
