package flash.pipeline.ui.sandbox;

import java.awt.Menu;
import java.awt.MenuBar;
import java.awt.MenuItem;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FilterCatalogTest {

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
}
