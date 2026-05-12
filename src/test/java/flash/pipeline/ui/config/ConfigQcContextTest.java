package flash.pipeline.ui.config;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;

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
