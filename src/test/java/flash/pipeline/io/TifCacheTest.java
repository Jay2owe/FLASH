package flash.pipeline.io;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
    public void cacheExists_readsLegacyCacheFallback() throws Exception {
        File dir = temp.newFolder("legacy-cache");
        File legacy = new File(dir, ".tif_cache");
        assertTrue(legacy.mkdirs());
        assertTrue(new File(legacy, "0001_image.tif").createNewFile());

        assertTrue(TifCache.cacheExists(dir.getAbsolutePath()));
        assertEquals(1, TifCache.cacheSize(dir.getAbsolutePath()));
    }

    @Test
    public void hasAllSeriesRequiresMatchingSeriesPrefixes() throws Exception {
        File dir = temp.newFolder("sparse-cache");
        File cache = TifCache.getCacheDir(dir.getAbsolutePath());
        assertTrue(cache.mkdirs());
        assertTrue(new File(cache, "0000_first.tif").createNewFile());
        assertTrue(new File(cache, "0002_third.tif").createNewFile());

        assertTrue(TifCache.hasAllSeries(dir.getAbsolutePath(), Arrays.asList(0, 2)));
        assertFalse(TifCache.hasAllSeries(dir.getAbsolutePath(), Arrays.asList(0, 1)));
        assertFalse(TifCache.hasAllSeries(dir.getAbsolutePath(), Collections.<Integer>emptyList()));
    }
}
