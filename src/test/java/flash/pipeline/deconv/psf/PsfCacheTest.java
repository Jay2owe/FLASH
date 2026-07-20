package flash.pipeline.deconv.psf;

import ij.ImagePlus;
import ij.process.FloatProcessor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

public class PsfCacheTest {

    @Before
    public void setUp() {
        PsfCache.resetForTest();
    }

    @After
    public void tearDown() {
        PsfCache.resetForTest();
    }

    @Test
    public void lruEvictionDropsTheLeastRecentlyUsedEntry() {
        final AtomicInteger synthCalls = new AtomicInteger(0);
        PsfCache.setMaxEntriesForTest(2);
        PsfCache.setSynthesizerForTest(new PsfCache.Synthesizer() {
            @Override
            public ImagePlus synthesize(PsfSpec spec, PsfModel model) {
                return imageWithValue("psf-" + synthCalls.incrementAndGet(), synthCalls.get());
            }
        });

        close(PsfCache.get(spec(510.0), PsfModel.BORN_WOLF));
        close(PsfCache.get(spec(520.0), PsfModel.BORN_WOLF));
        close(PsfCache.get(spec(510.0), PsfModel.BORN_WOLF)); // refresh 510
        close(PsfCache.get(spec(530.0), PsfModel.BORN_WOLF)); // evicts 520
        close(PsfCache.get(spec(520.0), PsfModel.BORN_WOLF)); // must synthesize again

        assertEquals(4, synthCalls.get());
        assertEquals(2, PsfCache.sizeForTest());
    }

    @Test
    public void returnedCopiesCanBeClosedOrModifiedWithoutTouchingTheCache() {
        final AtomicInteger synthCalls = new AtomicInteger(0);
        PsfCache.setSynthesizerForTest(new PsfCache.Synthesizer() {
            @Override
            public ImagePlus synthesize(PsfSpec spec, PsfModel model) {
                synthCalls.incrementAndGet();
                return imageWithValue("owned", 7.0);
            }
        });

        ImagePlus first = PsfCache.get(spec(520.0), PsfModel.GIBSON_LANNI);
        assertNotNull(first);
        first.getProcessor().setf(0, 99.0f);
        close(first);

        ImagePlus second = PsfCache.get(spec(520.0), PsfModel.GIBSON_LANNI);
        assertNotNull(second);
        assertEquals(7.0, second.getProcessor().getf(0), 0.0);
        assertEquals(1, synthCalls.get());
        close(second);
    }

    @Test
    public void concurrentGetsForTheSameKeyOnlySynthesizeOnce() throws Exception {
        final AtomicInteger synthCalls = new AtomicInteger(0);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        PsfCache.setSynthesizerForTest(new PsfCache.Synthesizer() {
            @Override
            public ImagePlus synthesize(PsfSpec spec, PsfModel model) {
                synthCalls.incrementAndGet();
                started.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting to release synthesizer.");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting to release PSF cache synthesizer", e);
                }
                return imageWithValue("shared", 3.0);
            }
        });

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<ImagePlus>> futures = new ArrayList<Future<ImagePlus>>();
            final PsfSpec spec = spec(568.0);
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(new Callable<ImagePlus>() {
                    @Override
                    public ImagePlus call() {
                        return PsfCache.get(spec, PsfModel.DOUGHERTY_THEORETICAL);
                    }
                }));
            }

            assertTrue(started.await(10, TimeUnit.SECONDS));
            release.countDown();

            ImagePlus previous = null;
            for (Future<ImagePlus> future : futures) {
                ImagePlus image = future.get(10, TimeUnit.SECONDS);
                assertNotNull(image);
                if (previous != null) {
                    assertNotSame(previous, image);
                }
                previous = image;
                close(image);
            }
            assertEquals(1, synthCalls.get());
        } finally {
            pool.shutdownNow();
        }
    }

    private static PsfSpec spec(double wavelengthNm) {
        return new PsfSpec(
                1.35,
                1.515,
                1.450,
                wavelengthNm,
                80.0,
                250.0,
                32,
                32,
                16,
                ScopeModality.WIDEFIELD,
                null);
    }

    private static ImagePlus imageWithValue(String title, double value) {
        float[] pixels = new float[16];
        pixels[0] = (float) value;
        return new ImagePlus(title, new FloatProcessor(4, 4, pixels, null));
    }

    private static void close(ImagePlus image) {
        if (image == null) return;
        image.changes = false;
        image.close();
        image.flush();
    }
}
