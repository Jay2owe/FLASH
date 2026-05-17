package flash.pipeline.ui.variations;

import ij.ImagePlus;
import ij.process.ShortProcessor;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class VariationCacheTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void putGetRoundTripUsesMemoryTier() throws Exception {
        VariationCache cache = new VariationCache(temp.newFolder(".bin"));
        ImagePlus label = labelImage("label", 7);

        cache.put("aaaaaaaaaaaaaaaa", label);

        assertSame(label, cache.get("aaaaaaaaaaaaaaaa"));
    }

    @Test
    public void memoryTierEvictsLeastRecentlyUsedAtCapacityFiftyOne() throws Exception {
        VariationCache cache = new VariationCache(temp.newFolder(".bin"));

        for (int i = 0; i < 51; i++) {
            cache.put(String.format(java.util.Locale.ROOT, "%016d", Integer.valueOf(i)),
                    labelImage("label-" + i, i));
        }

        assertEquals(50, cache.memorySizeForTest());
        assertFalse(cache.isInMemoryForTest("0000000000000000"));
        assertTrue(cache.isInMemoryForTest("0000000000000050"));
    }

    @Test
    public void diskRoundTripSurvivesNewCacheInstance() throws Exception {
        File bin = temp.newFolder(".bin");
        VariationCache first = new VariationCache(bin);
        first.put("bbbbbbbbbbbbbbbb", labelImage("disk-label", 123));

        VariationCache reopened = new VariationCache(bin);
        ImagePlus loaded = reopened.get("bbbbbbbbbbbbbbbb");

        assertNotNull(loaded);
        assertEquals(123, loaded.getStack().getProcessor(1).getPixel(0, 0));
    }

    @Test
    public void corruptDiskFileReturnsMiss() throws Exception {
        VariationCache cache = new VariationCache(temp.newFolder(".bin"));
        File file = cache.fileForTest("cccccccccccccccc");
        assertTrue(file.getParentFile().mkdirs());
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(new byte[] { 1, 2, 3, 4, 5 });
        } finally {
            out.close();
        }

        assertNull(cache.get("cccccccccccccccc"));
    }

    @Test
    public void purgeOlderThanDeletesOnlyOldTiffs() throws Exception {
        File bin = temp.newFolder(".bin");
        VariationCache cache = new VariationCache(bin);
        File oldFile = cache.fileForTest("dddddddddddddddd");
        File newFile = cache.fileForTest("eeeeeeeeeeeeeeee");
        assertTrue(oldFile.getParentFile().mkdirs());
        touch(oldFile);
        touch(newFile);
        long now = System.currentTimeMillis();
        assertTrue(oldFile.setLastModified(now - TimeUnit.DAYS.toMillis(8)));
        assertTrue(newFile.setLastModified(now - TimeUnit.DAYS.toMillis(1)));

        int deleted = VariationCache.purgeOlderThan(bin,
                TimeUnit.DAYS.toMillis(7));

        assertEquals(1, deleted);
        assertFalse(oldFile.exists());
        assertTrue(newFile.exists());
    }

    private static ImagePlus labelImage(String title, int value) {
        ShortProcessor processor = new ShortProcessor(2, 2);
        processor.set(0, 0, value);
        processor.set(1, 0, value + 1);
        processor.set(0, 1, value + 2);
        processor.set(1, 1, value + 3);
        return new ImagePlus(title, processor);
    }

    private static void touch(File file) throws Exception {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(1);
        } finally {
            out.close();
        }
    }
}
