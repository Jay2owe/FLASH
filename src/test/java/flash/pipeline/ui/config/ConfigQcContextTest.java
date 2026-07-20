package flash.pipeline.ui.config;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class ConfigQcContextTest {

    @Test
    public void shortDisplayNameDropsContainerPrefix() {
        assertEquals("Mouse1_LH_SCN",
                ConfigQcContext.shortDisplayName("Experiment.lif - Mouse1_LH_SCN"));
        assertEquals("Mouse2_RH_CA1",
                ConfigQcContext.shortDisplayName("Experiment.lif :: Mouse2_RH_CA1"));
    }

    @Test
    public void filteredStackCacheKeysByImageChannelAndMacroAndReturnsDuplicates() {
        ConfigQcContext context = ConfigQcContext.fromImages(
                null,
                null,
                null,
                Arrays.asList(image("Series A", 1), image("Series B", 2)),
                Arrays.asList("IBA1", "GFAP"),
                0);
        ImagePlus filtered = image("filtered", 42);

        context.cacheCurrentFilteredStack("run(\"Median...\", \"radius=2 stack\");", filtered);

        ImagePlus first = context.duplicateCurrentFilteredStack(
                "run(\"Median...\", \"radius=2 stack\");");
        assertNotNull(first);
        assertNotSame(filtered, first);
        first.getProcessor().set(0, 0, 99);

        ImagePlus second = context.duplicateCurrentFilteredStack(
                "run(\"Median...\", \"radius=2 stack\");");
        assertEquals(42, second.getProcessor().get(0, 0));

        context.setChannelIndex(1);
        assertNull(context.duplicateCurrentFilteredStack(
                "run(\"Median...\", \"radius=2 stack\");"));

        context.setChannelIndex(0);
        context.setCurrentImageIndex(1);
        assertNull(context.duplicateCurrentFilteredStack(
                "run(\"Median...\", \"radius=2 stack\");"));

        context.setCurrentImageIndex(0);
        assertNull(context.duplicateCurrentFilteredStack(
                "run(\"Median...\", \"radius=3 stack\");"));

        context.clearFilteredStackCache();
        assertEquals(0, context.filteredStackCacheSizeForTest());
    }

    @Test
    public void replacingFilteredStackForSameImageChannelDropsOlderMacro() {
        ConfigQcContext context = ConfigQcContext.fromImages(
                null,
                null,
                null,
                Arrays.asList(image("Series A", 1)),
                Arrays.asList("IBA1"),
                0);

        context.cacheCurrentFilteredStack("macro A", image("filtered A", 10));
        context.cacheCurrentFilteredStack("macro B", image("filtered B", 20));

        assertNull(context.duplicateCurrentFilteredStack("macro A"));
        assertEquals(20, context.duplicateCurrentFilteredStack("macro B")
                .getProcessor().get(0, 0));
        assertEquals(1, context.filteredStackCacheSizeForTest());
    }

    @Test
    public void filteredStackCacheCanBeSharedAcrossSetupContexts() {
        ConfigQcContext.FilteredStackCache cache = new ConfigQcContext.FilteredStackCache();
        ConfigQcContext first = contextWithSharedCache(cache);
        ConfigQcContext second = contextWithSharedCache(cache);

        first.cacheCurrentFilteredStack("macro A", image("filtered A", 33));

        assertEquals(33, second.duplicateCurrentFilteredStack("macro A")
                .getProcessor().get(0, 0));
    }

    @Test
    public void deconvMirrorCacheIsExposedForDownstreamStages() {
        DeconvMirrorCache mirrors = new DeconvMirrorCache();
        java.io.File mirrorFile = new java.io.File("Series_1_C0.tif");
        mirrors.put(7, 0, DeconvMirrorCache.Mirror.deconvolved(mirrorFile, "HASH123456"));
        mirrors.put(7, 1, DeconvMirrorCache.Mirror.rawFallback());

        ConfigQcContext context = new ConfigQcContext(
                null,
                null,
                null,
                Arrays.asList(new ConfigQcContext.ConfigQcImage(7, "Shared series", image("Shared series", 1))),
                Arrays.asList("IBA1", "GFAP"),
                0,
                null,
                mirrors);

        assertSame(mirrors, context.getDeconvMirrorCache());

        DeconvMirrorCache.Mirror deconvolved = context.getDeconvMirror(7, 0);
        assertNotNull(deconvolved);
        assertEquals(mirrorFile, deconvolved.mirrorFile);
        assertEquals("HASH123456", deconvolved.paramsHash);
        assertEquals(false, deconvolved.rawFallback);

        DeconvMirrorCache.Mirror raw = context.getDeconvMirror(7, 1);
        assertNotNull(raw);
        assertEquals(true, raw.rawFallback);
        assertNull(raw.mirrorFile);

        // Nothing computed for this pair -> null (distinct from a raw-fallback record).
        assertNull(context.getDeconvMirror(7, 2));
    }

    @Test
    public void filteredStackKeyChangeInvalidatesStaleDeconvStack() {
        ConfigQcContext context = ConfigQcContext.fromImages(
                null,
                null,
                null,
                Arrays.asList(image("Series A", 1)),
                Arrays.asList("IBA1"),
                0);

        // A DECONV filtered stack tuned on params hash H1.
        context.cacheCurrentFilteredStack("macro", "DECONV", "H1", image("deconv filtered", 20));
        assertEquals(20, context.duplicateCurrentFilteredStack("macro", "DECONV", "H1")
                .getProcessor().get(0, 0));

        // Same channel + macro, but a params change (H2) or a routing flip to RAW must MISS -
        // never serve the stale H1 deconvolved pixels.
        assertNull(context.duplicateCurrentFilteredStack("macro", "DECONV", "H2"));
        assertNull(context.duplicateCurrentFilteredStack("macro", "RAW", ""));

        // The legacy RAW-default lookup must also miss (it is a distinct key).
        assertNull(context.duplicateCurrentFilteredStack("macro"));

        // Per-channel invalidation (fired after the deconv QC pass) clears every variant.
        context.clearFilteredStackCacheForChannel(0);
        assertEquals(0, context.filteredStackCacheSizeForTest());
        assertNull(context.duplicateCurrentFilteredStack("macro", "DECONV", "H1"));
    }

    @Test
    public void rawDefaultFilteredStackUnaffectedBySourceKindOverload() {
        // Non-deconv path: the RAW-default cache/lookup wrappers still round-trip exactly as before.
        ConfigQcContext context = ConfigQcContext.fromImages(
                null,
                null,
                null,
                Arrays.asList(image("Series A", 1)),
                Arrays.asList("IBA1"),
                0);

        context.cacheCurrentFilteredStack("macro", image("raw filtered", 7));

        assertEquals(7, context.duplicateCurrentFilteredStack("macro").getProcessor().get(0, 0));
        // The RAW default and the explicit RAW/empty-fingerprint key are the same slot.
        assertEquals(7, context.duplicateCurrentFilteredStack("macro", "RAW", "")
                .getProcessor().get(0, 0));
    }

    @Test
    public void deconvMirrorAccessorsAreNullWithoutACache() {
        ConfigQcContext context = ConfigQcContext.fromImages(
                null,
                null,
                null,
                Arrays.asList(image("Series A", 1)),
                Arrays.asList("IBA1"),
                0);

        assertNull(context.getDeconvMirrorCache());
        assertNull(context.getDeconvMirror(0, 0));
    }

    private static ConfigQcContext contextWithSharedCache(ConfigQcContext.FilteredStackCache cache) {
        return new ConfigQcContext(
                null,
                null,
                null,
                Arrays.asList(new ConfigQcContext.ConfigQcImage(7, "Shared series",
                        image("Shared series", 1))),
                Arrays.asList("IBA1"),
                0,
                cache);
    }

    private static ImagePlus image(String title, int value) {
        ByteProcessor processor = new ByteProcessor(2, 1);
        processor.set(0, 0, value);
        ImagePlus image = new ImagePlus(title, processor);
        image.setTitle(title);
        return image;
    }
}
