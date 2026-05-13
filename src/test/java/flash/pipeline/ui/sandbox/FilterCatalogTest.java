package flash.pipeline.ui.sandbox;

import java.awt.Menu;
import java.awt.MenuBar;
import java.awt.MenuItem;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FilterCatalogTest {

    @Before
    public void clearCatalogCachesBeforeTest() {
        FijiCommandRegistry.clearForTests();
        FilterCatalog.clearTierTwoCacheForTests();
    }

    @After
    public void clearCatalogCachesAfterTest() {
        FijiCommandRegistry.clearForTests();
        FilterCatalog.clearTierTwoCacheForTests();
    }

    @Test
    public void collectTierTwoWalksIncludedMenuBranchesOnly() {
        MenuBar bar = new MenuBar();
        Menu file = new Menu("File");
        file.add(new MenuItem("Open..."));
        bar.add(file);

        Menu process = new Menu("Process");
        Menu fft = new Menu("FFT");
        fft.add(new MenuItem("Bandpass Filter..."));
        process.add(fft);
        bar.add(process);

        Menu plugins = new Menu("Plugins");
        Menu filters = new Menu("Filters");
        filters.add(new MenuItem("Kuwahara Filter..."));
        plugins.add(filters);
        Menu scripts = new Menu("Scripts");
        scripts.add(new MenuItem("Not a Filter..."));
        plugins.add(scripts);
        bar.add(plugins);

        List<FilterCatalog.Entry> entries = FilterCatalog.collectTierTwoFromMenuBar(bar);

        assertTrue(containsCommand(entries, "Bandpass Filter",
                "Process > FFT > Bandpass Filter..."));
        assertTrue(containsCommand(entries, "Kuwahara Filter",
                "Plugins > Filters > Kuwahara Filter..."));
        assertFalse(containsLabel(entries, "Open"));
        assertFalse(containsLabel(entries, "Not a Filter"));
    }

    @Test
    public void searchCombinesFastAndLegacyEntriesWithBadges() {
        FilterCatalog catalog = new FilterCatalog(Arrays.asList(
                FilterCatalog.Entry.legacy("Tier 2", "Kuwahara Filter",
                        "Plugins > Filters > Kuwahara Filter...")));

        catalog.setSearchTextForTests("kuwahara");
        List<FilterCatalog.Entry> visible = catalog.visibleEntriesForTests();

        assertEquals(1, visible.size());
        assertEquals("Kuwahara Filter", visible.get(0).label);
        assertEquals("[legacy]", visible.get(0).badge());

        catalog.setSearchTextForTests("enhance");
        visible = catalog.visibleEntriesForTests();
        assertTrue(containsFastEnhance(visible));
    }

    @Test
    public void registryOnlyCommandsAppearAsLegacyEntriesWithGenericPath() {
        Map<String, String> commands = new LinkedHashMap<String, String>();
        commands.put("Plugin Blur...", "example.Plugin_Blur");
        FijiCommandRegistry.setForTests(commands);
        FilterCatalog.clearTierTwoCacheForTests();

        FilterCatalog catalog = new FilterCatalog();
        FilterCatalog.Entry entry = findByCommand(catalog.getAllEntries(), "Plugin Blur");

        assertEquals("Fiji commands", entry.category);
        assertEquals("Plugin Blur", entry.label);
        assertEquals("Plugin Blur", entry.commandName);
        assertEquals("Fiji commands > Plugin Blur", entry.menuPath);
        assertTrue(entry.legacy);
        assertEquals("[legacy]", entry.badge());
    }

    @Test
    public void registryCommandMatchingMenuEntryKeepsMenuPath() {
        Map<String, String> commands = new LinkedHashMap<String, String>();
        commands.put("Bandpass Filter...", "ij.plugin.filter.FFTFilter");
        FijiCommandRegistry.setForTests(commands);

        List<FilterCatalog.Entry> merged = FilterCatalog.mergeRegistryCommands(Arrays.asList(
                FilterCatalog.Entry.legacy("Process", "Bandpass Filter",
                        "Process > FFT > Bandpass Filter...")),
                FijiCommandRegistry.allCommands());

        assertEquals(1, countCommand(merged, "Bandpass Filter"));
        assertTrue(containsCommand(merged, "Bandpass Filter",
                "Process > FFT > Bandpass Filter..."));
    }

    @Test
    public void registryCommandMatchingFastCommandDoesNotDuplicateFullCatalogEntry() {
        Map<String, String> commands = new LinkedHashMap<String, String>();
        commands.put("Median...", "ij.plugin.filter.RankFilters");
        FijiCommandRegistry.setForTests(commands);
        FilterCatalog.clearTierTwoCacheForTests();

        FilterCatalog catalog = new FilterCatalog();
        List<FilterCatalog.Entry> entries = catalog.getAllEntries();

        assertEquals(1, countCommand(entries, "Median"));
        FilterCatalog.Entry median = findByCommand(entries, "Median");
        assertFalse(median.legacy);
        assertEquals("[fast]", median.badge());
    }

    @Test
    public void skippedRegistryCommandsAreAbsent() {
        Map<String, String> commands = new LinkedHashMap<String, String>();
        commands.put("Refresh Menus", "ij.Menus");
        commands.put("About Plugin", "example.About");
        commands.put("Help", "ij.Help");
        commands.put("Compile and Run...", "ij.plugin.Compiler");
        commands.put("Useful Filter...", "example.Useful_Filter");
        FijiCommandRegistry.setForTests(commands);
        FilterCatalog.clearTierTwoCacheForTests();

        List<FilterCatalog.Entry> entries = new FilterCatalog().getAllEntries();

        assertFalse(containsLabel(entries, "Refresh Menus"));
        assertFalse(containsLabel(entries, "About Plugin"));
        assertFalse(containsLabel(entries, "Help"));
        assertFalse(containsLabel(entries, "Compile and Run"));
        assertTrue(containsLabel(entries, "Useful Filter"));
    }

    private static boolean containsCommand(List<FilterCatalog.Entry> entries, String label, String path) {
        for (int i = 0; i < entries.size(); i++) {
            FilterCatalog.Entry entry = entries.get(i);
            if (label.equals(entry.commandName) && path.equals(entry.menuPath)) return true;
        }
        return false;
    }

    private static boolean containsLabel(List<FilterCatalog.Entry> entries, String label) {
        for (int i = 0; i < entries.size(); i++) {
            if (label.equals(entries.get(i).label)) return true;
        }
        return false;
    }

    private static boolean containsFastEnhance(List<FilterCatalog.Entry> entries) {
        for (int i = 0; i < entries.size(); i++) {
            FilterCatalog.Entry entry = entries.get(i);
            if ("Enhance Contrast".equals(entry.label) && "[fast]".equals(entry.badge())) return true;
        }
        return false;
    }

    private static FilterCatalog.Entry findByCommand(List<FilterCatalog.Entry> entries, String command) {
        for (int i = 0; i < entries.size(); i++) {
            FilterCatalog.Entry entry = entries.get(i);
            if (command.equals(entry.commandName)) return entry;
        }
        throw new AssertionError("Missing catalog command: " + command);
    }

    private static int countCommand(List<FilterCatalog.Entry> entries, String command) {
        int count = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (command.equals(entries.get(i).commandName)) count++;
        }
        return count;
    }
}
