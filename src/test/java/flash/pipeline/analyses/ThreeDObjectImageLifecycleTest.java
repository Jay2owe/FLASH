package flash.pipeline.analyses;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.io.DeferredImageSupplier;
import ij.ImagePlus;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ThreeDObjectImageLifecycleTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test(timeout = 10000) public void calibrationFailureClosesCurrentAndLatePrefetchedImages()
            throws Exception {
        CountDownLatch prefetchStarted = new CountDownLatch(1);
        AtomicBoolean loaderStopped = new AtomicBoolean();
        TrackingImage first = new TrackingImage("first");
        TrackingImage late = new TrackingImage("prefetched");
        first.getCalibration().setUnit("unknown");
        first.awaitPrefetch = prefetchStarted;
        DeferredImageSupplier supplier = new DeferredImageSupplier(Arrays.asList(
                new File("unused-first.tif"), new File("unused-second.tif")), "source") {
            @Override public ImagePlus openSeries(int index) throws Exception {
                if (index == 0) return first;
                prefetchStarted.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException expected) {
                    // A cancelled importer can still return an image after cleanup.
                } finally {
                    loaderStopped.set(true);
                }
                return late;
            }
        };
        ThreeDObjectAnalysis analysis = new ThreeDObjectAnalysis();
        Method process = null;
        for (Method candidate : ThreeDObjectAnalysis.class.getDeclaredMethods()) {
            if (candidate.getName().equals("processImagesSequential")) process = candidate;
        }
        assertNotNull(process);
        process.setAccessible(true);
        Object emptyRois = Array.newInstance(process.getParameterTypes()[7].getComponentType(), 0);
        File output = temp.newFolder();
        try {
            process.invoke(analysis, supplier, 2, output.getPath(), new BinConfig(), output,
                    null, new LinkedHashMap<String, ij.measure.ResultsTable>(), emptyRois,
                    false, -1, new boolean[0], System.currentTimeMillis());
            fail("Invalid calibration was accepted");
        } catch (InvocationTargetException expected) {
            assertTrue(expected.getCause().toString(), expected.getCause() instanceof IllegalStateException);
            assertTrue(expected.getCause().getMessage().contains("calibration"));
        }
        assertTrue(first.closed);
        assertTrue("Prefetch loader must exit before failure returns", loaderStopped.get());
        assertTrue("Cancelled Future must not hide a returned image", late.closed);
    }

    private static final class TrackingImage extends ImagePlus {
        volatile boolean closed;
        volatile CountDownLatch awaitPrefetch;
        TrackingImage(String title) { super(title, new ByteProcessor(2, 2)); }
        @Override public Calibration getCalibration() {
            CountDownLatch latch = awaitPrefetch;
            if (latch != null) {
                try {
                    if (!latch.await(3, TimeUnit.SECONDS))
                        throw new AssertionError("Prefetch task never started");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            return super.getCalibration();
        }
        @Override public void close() { closed = true; super.close(); }
    }
}
