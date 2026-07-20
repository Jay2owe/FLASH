package flash.pipeline.io;

import flash.pipeline.project.ProjectFile;
import flash.pipeline.project.ProjectFileIO;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ImageCacheTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void getImagesUsesProjectManifestSourceOutsideOutputRoot() throws Exception {
        File outputRoot = temp.newFolder("cache-manifest-output");
        File sourceRoot = temp.newFolder("cache-manifest-source");
        File staleRootTiff = new File(outputRoot, "stale-root.tif");
        File manifestTiff = new File(sourceRoot, "manifest-source.tif");
        writeSyntheticTiff(staleRootTiff, "stale", 3, 2, 1);
        writeSyntheticTiff(manifestTiff, "manifest", 7, 5, 1);

        ProjectFile project = new ProjectFile();
        project.outputRoot = outputRoot.getAbsolutePath();
        project.items.add(projectItem(manifestTiff));
        ProjectFileIO.write(
                FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath()).configurationWriteDir(),
                project);

        ImageCache cache = new ImageCache();
        List<ImagePlus> images = cache.getImages(outputRoot.getAbsolutePath());
        try {
            assertNotNull(images);
            assertEquals(1, images.size());
            assertTrue(images.get(0).getTitle().contains("manifest-source"));
            assertEquals(7, images.get(0).getWidth());
            assertEquals(5, images.get(0).getHeight());
        } finally {
            cache.release();
        }
    }

    @Test
    public void nullSecondSeriesRejectsLoadAndClosesFirstImage() {
        final TrackingImagePlus first = new TrackingImagePlus("first-before-null");
        final AtomicInteger shutdowns = new AtomicInteger();
        DeferredImageSupplier supplier = failingSupplier(first, null, shutdowns);
        ImageCache cache = cacheUsing(supplier);

        assertNull(cache.getImages("null-series"));
        cache.release();

        assertTrue("first partial image should be closed", first.closeCalls > 0);
        assertTrue("first partial image should be flushed", first.flushCalls > 0);
        assertEquals("prefetch shutdown should run exactly once", 1, shutdowns.get());
    }

    @Test
    public void throwingSecondSeriesRejectsLoadAndClosesFirstImage() {
        final TrackingImagePlus first = new TrackingImagePlus("first-before-throw");
        final AtomicInteger shutdowns = new AtomicInteger();
        DeferredImageSupplier supplier = failingSupplier(
                first, new Exception("synthetic second-series failure"), shutdowns);
        ImageCache cache = cacheUsing(supplier);

        assertNull(cache.getImages("throwing-series"));
        cache.release();

        assertTrue("first partial image should be closed", first.closeCalls > 0);
        assertTrue("first partial image should be flushed", first.flushCalls > 0);
        assertEquals("prefetch shutdown should run exactly once", 1, shutdowns.get());
    }

    @Test
    public void errorFromSecondSeriesClosesFirstImageBeforeEscaping() {
        final TrackingImagePlus first = new TrackingImagePlus("first-before-error");
        final AtomicInteger shutdowns = new AtomicInteger();
        AssertionError failure = new AssertionError("synthetic cache Error");
        ImageCache cache = cacheUsing(failingSupplier(first, failure, shutdowns));

        try {
            cache.getImages("error-series");
            fail("Expected supplier Error");
        } catch (AssertionError expected) {
            assertSame(failure, expected);
        }
        cache.release();

        assertTrue("first partial image should be closed", first.closeCalls > 0);
        assertTrue("first partial image should be flushed", first.flushCalls > 0);
        assertEquals("prefetch shutdown should run exactly once", 1, shutdowns.get());
    }

    @Test
    public void fatalPartialCleanupFailureTakesPrecedenceAndEveryImageIsFlushed() {
        final RuntimeException earlier = new RuntimeException("earlier cache cleanup failure");
        final ThreadDeath fatal = new ThreadDeath();
        final CleanupFailingImagePlus first =
                new CleanupFailingImagePlus("cache-nonfatal-cleanup", earlier);
        final CleanupFailingImagePlus second =
                new CleanupFailingImagePlus("cache-fatal-cleanup", fatal);
        DeferredImageSupplier supplier = new DeferredImageSupplier(
                Arrays.asList(new File("unused-0.tif"), new File("unused-1.tif"),
                        new File("unused-2.tif")), "test") {
            @Override
            public ImagePlus openSeriesMaterialized(int seriesIndex) {
                if (seriesIndex == 0) return first;
                if (seriesIndex == 1) return second;
                return null;
            }
        };
        ImageCache cache = cacheUsing(supplier);

        try {
            cache.getImages("fatal-partial-cleanup");
            fail("Expected fatal partial-cache cleanup failure");
        } catch (ThreadDeath expected) {
            assertSame(fatal, expected);
        }

        assertEquals(1, fatal.getSuppressed().length);
        Throwable loadFailure = fatal.getSuppressed()[0];
        assertTrue(loadFailure instanceof IllegalStateException);
        assertEquals(1, loadFailure.getSuppressed().length);
        assertSame(earlier, loadFailure.getSuppressed()[0]);
        assertTrue("first partial image should still be flushed", first.flushCalls > 0);
        assertTrue("second partial image should still be flushed", second.flushCalls > 0);
        cache.release();
    }

    private static ProjectFile.Item projectItem(File source) {
        ProjectFile.Item item = new ProjectFile.Item();
        item.path = source.getAbsolutePath();
        item.include = true;
        return item;
    }

    private static void writeSyntheticTiff(File target, String title,
                                           int width, int height, int slices) {
        ImagePlus image = IJ.createImage(title, "8-bit ramp", width, height, slices);
        try {
            IJ.saveAsTiff(image, target.getAbsolutePath());
        } finally {
            image.close();
            image.flush();
        }
    }

    private static ImageCache cacheUsing(final DeferredImageSupplier supplier) {
        return new ImageCache() {
            @Override
            DeferredImageSupplier createSupplier(String directory) {
                return supplier;
            }
        };
    }

    private static DeferredImageSupplier failingSupplier(
            final TrackingImagePlus first,
            final Throwable secondFailure,
            final AtomicInteger shutdowns) {
        return new DeferredImageSupplier(
                Arrays.asList(new File("unused-0.tif"), new File("unused-1.tif")), "test") {
            @Override
            public ImagePlus openSeriesMaterialized(int seriesIndex) throws Exception {
                if (seriesIndex == 0) return first;
                if (secondFailure instanceof Exception) throw (Exception) secondFailure;
                if (secondFailure instanceof Error) throw (Error) secondFailure;
                return null;
            }

            @Override
            public void shutdownPrefetch() {
                shutdowns.incrementAndGet();
                super.shutdownPrefetch();
            }
        };
    }

    private static final class TrackingImagePlus extends ImagePlus {
        volatile int closeCalls;
        volatile int flushCalls;

        TrackingImagePlus(String title) {
            super(title, onePixelStack());
        }

        @Override
        public void close() {
            closeCalls++;
            super.close();
        }

        @Override
        public void flush() {
            flushCalls++;
            super.flush();
        }
    }

    private static final class CleanupFailingImagePlus extends ImagePlus {
        private final Throwable closeFailure;
        volatile int flushCalls;

        CleanupFailingImagePlus(String title, Throwable closeFailure) {
            super(title, onePixelStack());
            this.closeFailure = closeFailure;
        }

        @Override
        public void close() {
            if (closeFailure instanceof Error) throw (Error) closeFailure;
            throw (RuntimeException) closeFailure;
        }

        @Override
        public void flush() {
            flushCalls++;
            super.flush();
        }
    }

    private static ImageStack onePixelStack() {
        ImageStack stack = new ImageStack(1, 1);
        stack.addSlice(new ByteProcessor(1, 1));
        return stack;
    }
}
