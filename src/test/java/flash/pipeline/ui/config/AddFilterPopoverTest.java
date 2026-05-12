package flash.pipeline.ui.config;

import flash.pipeline.image.FilterMacroParser.OpType;
import flash.pipeline.ui.sandbox.FilterCatalog;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AddFilterPopoverTest {

    @Test
    public void pickerEntriesAreFastOnlyByDefault() {
        List<FilterCatalog.Entry> entries = Arrays.asList(
                entry("Smoothing", "Median", OpType.MEDIAN, "radius=2", false, false,
                        "Median", ""),
                entry("Fiji commands", "Plugin Filter", OpType.UNKNOWN, "", false, true,
                        "Plugin Filter", "Fiji commands > Plugin Filter"));

        List<FilterCatalog.Entry> visible = AddFilterPopover.pickerEntries(entries, false);

        assertEquals(1, visible.size());
        assertEquals("Median", visible.get(0).label);
        assertFalse(visible.get(0).legacy);
    }

    @Test
    public void pickerEntriesCanIncludeLegacy() {
        List<FilterCatalog.Entry> entries = Arrays.asList(
                entry("Smoothing", "Median", OpType.MEDIAN, "radius=2", false, false,
                        "Median", ""),
                entry("Fiji commands", "Plugin Filter", OpType.UNKNOWN, "", false, true,
                        "Plugin Filter", "Fiji commands > Plugin Filter"));

        List<FilterCatalog.Entry> visible = AddFilterPopover.pickerEntries(entries, true);

        assertEquals(2, visible.size());
        assertEquals("Median", visible.get(0).label);
        assertEquals("Plugin Filter", visible.get(1).label);
        assertTrue(visible.get(1).legacy);
    }

    @Test
    public void pickerEntriesSkipNullAndStubEntries() {
        List<FilterCatalog.Entry> entries = Arrays.asList(
                null,
                entry("Group", "Placeholder", OpType.UNKNOWN, "", true, false, "", ""),
                entry("Smoothing", "Median", OpType.MEDIAN, "radius=2", false, false,
                        "Median", ""));

        List<FilterCatalog.Entry> visible = AddFilterPopover.pickerEntries(entries, true);

        assertEquals(1, visible.size());
        assertEquals("Median", visible.get(0).label);
    }

    @Test
    public void searchMatchesLabelCategoryMenuPathAndBadge() {
        FilterCatalog.Entry legacy = entry("Fiji commands", "Plugin Filter", OpType.UNKNOWN,
                "", false, true, "Plugin Filter", "Fiji commands > Plugin Filter");

        assertTrue(AddFilterPopover.matches(legacy, "plugin"));
        assertTrue(AddFilterPopover.matches(legacy, "fiji commands"));
        assertTrue(AddFilterPopover.matches(legacy, "plugin filter"));
        assertTrue(AddFilterPopover.matches(legacy, "[legacy]"));
        assertFalse(AddFilterPopover.matches(legacy, "median"));
    }

    @Test
    public void renderTextIncludesBadgeAndSourcePath() {
        FilterCatalog.Entry legacy = entry("Fiji commands", "Plugin Filter", OpType.UNKNOWN,
                "", false, true, "Plugin Filter", "Fiji commands > Plugin Filter");
        FilterCatalog.Entry fast = entry("Smoothing", "Median", OpType.MEDIAN,
                "radius=2", false, false, "Median", "");

        assertEquals("Plugin Filter [legacy]   (Fiji commands > Plugin Filter)",
                AddFilterPopover.renderText(legacy));
        assertEquals("Median [fast]   (Smoothing)",
                AddFilterPopover.renderText(fast));
    }

    private static FilterCatalog.Entry entry(String category, String label, OpType type,
                                             String defaultArgs, boolean stub, boolean legacy,
                                             String commandName, String menuPath) {
        try {
            Constructor<FilterCatalog.Entry> constructor = FilterCatalog.Entry.class
                    .getDeclaredConstructor(String.class, String.class, OpType.class,
                            String.class, boolean.class, boolean.class, String.class, String.class);
            constructor.setAccessible(true);
            return constructor.newInstance(category, label, type, defaultArgs, stub, legacy,
                    commandName, menuPath);
        } catch (Exception e) {
            throw new AssertionError("Unable to create catalog entry for test", e);
        }
    }
}
