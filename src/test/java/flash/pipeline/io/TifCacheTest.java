package flash.pipeline.io;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TifCacheTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void getCacheDir_usesFlashCacheTifFolder() throws Exception {
        File dir = temp.newFolder("cache-path");

        assertEquals(new File(dir, "FLASH/Cache/TIF").getAbsolutePath(),
                TifCache.getCacheDir(dir.getAbsolutePath()).getAbsolutePath());
    }

    @Test
    public void bareFilenamePrefixesAreNotImageCacheHits() throws Exception {
        File dir = temp.newFolder("unverified-cache");
        File cache = TifCache.getCacheDir(dir.getAbsolutePath());
        assertTrue(cache.mkdirs());
        assertTrue(new File(cache, "0000_first.tif").createNewFile());
        assertTrue(new File(cache, "0002_third.tif").createNewFile());

        assertFalse(TifCache.hasAllSeries(dir.getAbsolutePath(), Arrays.asList(0, 2)));
        assertFalse(TifCache.hasAllSeries(dir.getAbsolutePath(), Collections.<Integer>emptyList()));
        assertNull(TifCache.loadSingle(dir.getAbsolutePath(), 0));
        assertEquals("The old file remains available only to copy-only export callers",
                new File(cache, "0000_first.tif"),
                TifCache.cachedFileForSeries(dir.getAbsolutePath(), 0));
    }

    @Test
    public void unchangedSourceHitsAndReturnsExpectedPixels() throws Exception {
        File dir = temp.newFolder("verified-hit");
        File source = sourceFile(dir, "source.lif", 4096, 11);
        TifCache.CacheRequest request = TifCache.requestFor(source, 3);
        ImagePlus image = singlePixel("same title", 73);
        try {
            TifCache.saveToCache(dir.getAbsolutePath(), image, 4, request);
        } finally {
            close(image);
        }

        Map<Integer, TifCache.CacheRequest> requests = requestMap(4, request);
        assertTrue(TifCache.hasAllSeries(dir.getAbsolutePath(), requests));
        ImagePlus loaded = TifCache.loadSingle(dir.getAbsolutePath(), 4,
                TifCache.requestFor(source, 3));
        try {
            assertNotNull(loaded);
            assertEquals("same title", loaded.getTitle());
            assertEquals(73, loaded.getProcessor().get(0, 0));
        } finally {
            close(loaded);
        }
    }

    @Test
    public void sourceReplacementAndMiddleByteMutationMissEvenWithSameMetadata()
            throws Exception {
        File dir = temp.newFolder("source-replacement");
        // Larger than the full-hash threshold so the deterministic middle sample
        // is what catches this mutation.
        File source = sourceFile(dir, "large-source.czi", 9 * 1024 * 1024, 29);
        FileTime originalTime = Files.getLastModifiedTime(source.toPath());
        TifCache.CacheRequest original = TifCache.requestFor(source, 0);
        ImagePlus image = singlePixel("identical title and dimensions", 31);
        try {
            TifCache.saveToCache(dir.getAbsolutePath(), image, 0, original);
        } finally {
            close(image);
        }

        RandomAccessFile replacement = new RandomAccessFile(source, "rw");
        try {
            long middle = replacement.length() / 2L;
            replacement.seek(middle);
            int old = replacement.read();
            replacement.seek(middle);
            replacement.write(old ^ 0xff);
        } finally {
            replacement.close();
        }
        Files.setLastModifiedTime(source.toPath(), originalTime);

        TifCache.CacheRequest changed = TifCache.requestFor(source, 0);
        assertFalse("Content identity must not collapse to path, size, mtime, title, or dimensions",
                original.sourceFingerprint.equals(changed.sourceFingerprint));
        assertFalse(TifCache.hasAllSeries(dir.getAbsolutePath(), requestMap(0, changed)));
        assertNull(TifCache.loadSingle(dir.getAbsolutePath(), 0, changed));
        assertNull("Legacy callers must also revalidate a source-bound entry",
                TifCache.loadSingle(dir.getAbsolutePath(), 0));
    }

    @Test
    public void projectReorderAndLocalSeriesChangeCannotCrossReuse() throws Exception {
        File dir = temp.newFolder("project-reorder");
        File sourceA = sourceFile(dir, "a.lif", 2048, 7);
        File sourceB = sourceFile(dir, "b.lif", 2048, 8);
        TifCache.CacheRequest a0 = TifCache.requestFor(sourceA, 0);
        ImagePlus image = singlePixel("same", 40);
        try {
            TifCache.saveToCache(dir.getAbsolutePath(), image, 0, a0);
        } finally {
            close(image);
        }

        assertFalse("A reordered project source must miss at the same global index",
                TifCache.hasAllSeries(dir.getAbsolutePath(),
                        requestMap(0, TifCache.requestFor(sourceB, 0))));
        assertFalse("A different source-local series must miss",
                TifCache.hasAllSeries(dir.getAbsolutePath(),
                        requestMap(0, TifCache.requestFor(sourceA, 1))));
        assertTrue(TifCache.hasAllSeries(dir.getAbsolutePath(), requestMap(0, a0)));
    }

    @Test
    public void boundedLoaderRejectsStaleEntryAndLoadsFreshSourcePixels() throws Exception {
        final File dir = temp.newFolder("loader-source-binding");
        final File source = sourceFile(dir, "source.lif", 4096, 12);
        TifCache.CacheRequest oldRequest = TifCache.requestFor(source, 0);
        ImagePlus stale = singlePixel("same title", 21);
        try {
            TifCache.saveToCache(dir.getAbsolutePath(), stale, 0, oldRequest);
        } finally {
            close(stale);
        }

        RandomAccessFile changed = new RandomAccessFile(source, "rw");
        try {
            changed.seek(changed.length() / 2L);
            int old = changed.read();
            changed.seek(changed.length() / 2L);
            changed.write(old ^ 0xff);
        } finally {
            changed.close();
        }

        DeferredImageSupplier supplier = new DeferredImageSupplier(
                Collections.singletonList(source), "test") {
            @Override
            public boolean isTiffFolderMode() {
                return false;
            }

            @Override
            public ImagePlus openSeriesMaterialized(int seriesIndex) {
                return singlePixel("same title", 84);
            }
        };
        BoundedImageLoader loader = new BoundedImageLoader(supplier,
                Collections.singletonList(Integer.valueOf(0)), 1, 1, true,
                dir.getAbsolutePath());
        loader.start();
        BoundedImageLoader.IndexedImage loaded = loader.take();
        try {
            assertNotNull(loaded);
            assertEquals("The loader must not serve stale pixels from a title/dimension match",
                    84, loaded.image.getProcessor().get(0, 0));
            assertNull(loader.take());
        } finally {
            if (loaded != null) close(loaded.image);
        }
        ImagePlus refreshed = TifCache.loadSingle(dir.getAbsolutePath(), 0);
        try {
            assertNotNull(refreshed);
            assertEquals(84, refreshed.getProcessor().get(0, 0));
        } finally {
            close(refreshed);
        }
    }

    @Test
    public void boundedLoaderDoesNotPublishPixelsAcrossAConcurrentSourceChange()
            throws Exception {
        final File dir = temp.newFolder("loader-source-race");
        final File source = sourceFile(dir, "source.lif", 4096, 44);
        DeferredImageSupplier supplier = new DeferredImageSupplier(
                Collections.singletonList(source), "test") {
            @Override
            public boolean isTiffFolderMode() {
                return false;
            }

            @Override
            public ImagePlus openSeriesMaterialized(int seriesIndex) throws Exception {
                RandomAccessFile changed = new RandomAccessFile(source, "rw");
                try {
                    changed.seek(changed.length() / 2L);
                    int old = changed.read();
                    changed.seek(changed.length() / 2L);
                    changed.write(old ^ 0xff);
                } finally {
                    changed.close();
                }
                return singlePixel("loaded-before-revalidation", 62);
            }
        };
        BoundedImageLoader loader = new BoundedImageLoader(supplier,
                Collections.singletonList(Integer.valueOf(0)), 1, 1, true,
                dir.getAbsolutePath());
        loader.start();
        BoundedImageLoader.IndexedImage loaded = loader.take();
        try {
            assertNotNull(loaded);
            assertEquals(62, loaded.image.getProcessor().get(0, 0));
            assertNull(loader.take());
        } finally {
            if (loaded != null) close(loaded.image);
        }
        assertFalse("Pixels opened across two source identities must not be published",
                new File(TifCache.getCacheDir(dir.getAbsolutePath()),
                        "0000.complete").exists());
    }

    @Test
    public void corruptedCachedTiffIsRejectedWithoutChangingItsSize() throws Exception {
        File dir = temp.newFolder("corrupt-tiff");
        File source = sourceFile(dir, "source.nd2", 1024, 4);
        TifCache.CacheRequest request = TifCache.requestFor(source, 0);
        ImagePlus image = image(32, 32, "pixels", 19);
        try {
            TifCache.saveToCache(dir.getAbsolutePath(), image, 0, request);
        } finally {
            close(image);
        }

        File tiff = currentTiff(dir, 0);
        long originalSize = tiff.length();
        RandomAccessFile corrupt = new RandomAccessFile(tiff, "rw");
        try {
            long middle = corrupt.length() / 2L;
            corrupt.seek(middle);
            int old = corrupt.read();
            corrupt.seek(middle);
            corrupt.write(old ^ 0xff);
        } finally {
            corrupt.close();
        }
        assertEquals(originalSize, tiff.length());
        assertFalse(TifCache.hasAllSeries(dir.getAbsolutePath(), requestMap(0, request)));
        assertNull(TifCache.loadSingle(dir.getAbsolutePath(), 0, request));
    }

    @Test
    public void everyFailedPublicationBoundaryPreservesPriorCompletedGeneration()
            throws Exception {
        File dir = temp.newFolder("publication-faults");
        File source = sourceFile(dir, "source.lif", 4096, 3);
        final TifCache.CacheRequest request = TifCache.requestFor(source, 0);
        ImagePlus prior = singlePixel("prior", 17);
        try {
            TifCache.saveToCache(dir.getAbsolutePath(), prior, 0, request);
        } finally {
            close(prior);
        }
        File pointer = new File(TifCache.getCacheDir(dir.getAbsolutePath()), "0000.complete");
        byte[] priorPointer = Files.readAllBytes(pointer.toPath());
        File priorTiff = currentTiff(dir, 0);
        byte[] priorTiffBytes = Files.readAllBytes(priorTiff.toPath());

        for (final TifCache.PublicationStep step : TifCache.PublicationStep.values()) {
            ImagePlus replacement = singlePixel("replacement", 99);
            try {
                TifCache.saveToCache(dir.getAbsolutePath(), replacement, 0, request,
                        new TifCache.PublicationFault() {
                            @Override
                            public void before(TifCache.PublicationStep current,
                                               java.nio.file.Path affectedPath)
                                    throws IOException {
                                if (current == step) {
                                    throw new IOException("injected-" + step.name()
                                            + " at " + affectedPath.toAbsolutePath());
                                }
                            }
                        });
                fail("Expected deterministic failure at " + step);
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("injected-" + step.name()));
            } finally {
                close(replacement);
            }

            assertArrayEquals("Completion pointer changed after " + step,
                    priorPointer, Files.readAllBytes(pointer.toPath()));
            assertArrayEquals("Prior TIFF changed after " + step,
                    priorTiffBytes, Files.readAllBytes(priorTiff.toPath()));
            assertNoCandidateFiles(dir);
            ImagePlus loaded = TifCache.loadSingle(dir.getAbsolutePath(), 0, request);
            try {
                assertNotNull("Prior generation unavailable after " + step, loaded);
                assertEquals(17, loaded.getProcessor().get(0, 0));
            } finally {
                close(loaded);
            }
        }
    }

    @Test
    public void completedLegacyEntriesRemainSeparateFromSourceBoundEntries() throws Exception {
        File dir = temp.newFolder("legacy-isolation");
        ImagePlus legacy = singlePixel("legacy", 5);
        try {
            TifCache.saveToCache(dir.getAbsolutePath(), legacy, 2);
        } finally {
            close(legacy);
        }
        assertTrue(TifCache.hasAllSeries(dir.getAbsolutePath(), Collections.singletonList(2)));
        assertNotNull(TifCache.cachedFileForSeries(dir.getAbsolutePath(), 2));

        File source = sourceFile(dir, "unrelated.lif", 1024, 1);
        TifCache.CacheRequest request = TifCache.requestFor(source, 0);
        assertFalse(TifCache.hasAllSeries(dir.getAbsolutePath(), requestMap(2, request)));
        assertNull(TifCache.loadSingle(dir.getAbsolutePath(), 2, request));
    }

    private static Map<Integer, TifCache.CacheRequest> requestMap(
            int index, TifCache.CacheRequest request) {
        Map<Integer, TifCache.CacheRequest> requests =
                new HashMap<Integer, TifCache.CacheRequest>();
        requests.put(Integer.valueOf(index), request);
        return requests;
    }

    private static File sourceFile(File directory, String name, int size, int seed)
            throws Exception {
        File file = new File(directory, name);
        RandomAccessFile output = new RandomAccessFile(file, "rw");
        try {
            byte[] block = new byte[8192];
            for (int i = 0; i < block.length; i++) block[i] = (byte) (seed + i * 31);
            int remaining = size;
            while (remaining > 0) {
                int count = Math.min(block.length, remaining);
                output.write(block, 0, count);
                remaining -= count;
            }
        } finally {
            output.close();
        }
        return file;
    }

    private static File currentTiff(File directory, int index) throws Exception {
        File cache = TifCache.getCacheDir(directory.getAbsolutePath());
        File pointer = new File(cache, String.format("%04d.complete", index));
        String manifestName = new String(Files.readAllBytes(pointer.toPath()),
                StandardCharsets.UTF_8).trim();
        Properties manifest = new Properties();
        java.io.InputStream input = Files.newInputStream(new File(cache, manifestName).toPath());
        try {
            manifest.load(input);
        } finally {
            input.close();
        }
        return new File(cache, manifest.getProperty("tiff"));
    }

    private static void assertNoCandidateFiles(File directory) {
        File[] files = TifCache.getCacheDir(directory.getAbsolutePath()).listFiles();
        assertNotNull(files);
        for (File file : files) {
            assertFalse("Leaked cache candidate " + file,
                    file.getName().contains("candidate-"));
        }
    }

    private static ImagePlus singlePixel(String title, int value) {
        return image(1, 1, title, value);
    }

    private static ImagePlus image(int width, int height, String title, int value) {
        ImageStack stack = new ImageStack(width, height);
        ByteProcessor processor = new ByteProcessor(width, height);
        processor.setValue(value);
        processor.fill();
        stack.addSlice(processor);
        return new ImagePlus(title, stack);
    }

    private static void close(ImagePlus image) {
        if (image == null) return;
        image.changes = false;
        image.close();
        image.flush();
    }
}
