package flash.pipeline.bin;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class BinMacroIndexTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Before
    public void clearIndex() {
        BinMacroIndex.clearForTests();
    }

    @Test
    public void savedCustomFilterPresetNamesUseAllReadDirsDedupeAndSort() throws Exception {
        File project = temp.newFolder("project");
        File binFolder = configurationDir(project);
        assertTrue(binFolder.mkdirs());
        writePreset(project, "FLASH/.settings/Presets/Custom Filter Presets", "zeta cleanup");
        writePreset(project, "FLASH/.settings/Presets/Custom Filter Presets", "Alpha cleanup");
        writePreset(project, "FLASH/Presets/Custom Filter Presets", "legacy setup cleanup");
        writePreset(project, ".bin/Custom Filter Presets", "alpha cleanup");
        Files.write(new File(project, ".bin/Custom Filter Presets/notes.txt").toPath(),
                "ignore".getBytes(StandardCharsets.UTF_8));

        List<String> names = BinMacroIndex.listSavedCustomFilterPresetNames(binFolder);

        assertEquals(3, names.size());
        assertEquals("Alpha cleanup", names.get(0));
        assertEquals("legacy setup cleanup", names.get(1));
        assertEquals("zeta cleanup", names.get(2));
    }

    @Test
    public void asyncSavedCustomFilterPresetNamesAreCoalescedUntilInvalidated() throws Exception {
        File project = temp.newFolder("project-async");
        File binFolder = configurationDir(project);
        assertTrue(binFolder.mkdirs());
        writePreset(project, "FLASH/.settings/Presets/Custom Filter Presets", "Initial cleanup");

        CompletableFuture<List<String>> first = BinMacroIndex.savedCustomFilterPresetNamesAsync(binFolder);
        CompletableFuture<List<String>> second = BinMacroIndex.savedCustomFilterPresetNamesAsync(binFolder);

        assertSame(first, second);
        assertTrue(first.get(5, TimeUnit.SECONDS).contains("Initial cleanup"));

        writePreset(project, "FLASH/.settings/Presets/Custom Filter Presets", "New cleanup");
        assertFalse(BinMacroIndex.savedCustomFilterPresetNamesAsync(binFolder)
                .get(5, TimeUnit.SECONDS).contains("New cleanup"));

        BinMacroIndex.invalidate(binFolder);
        assertTrue(BinMacroIndex.savedCustomFilterPresetNamesAsync(binFolder)
                .get(5, TimeUnit.SECONDS).contains("New cleanup"));
    }

    private static File configurationDir(File project) {
        return new File(project, "FLASH/Config/.settings");
    }

    private static void writePreset(File project, String relativeDir, String name) throws Exception {
        File dir = new File(project, relativeDir);
        assertTrue(dir.mkdirs() || dir.isDirectory());
        Files.write(new File(dir, name + ".ijm").toPath(),
                ("run(\"Median...\", \"radius=2 stack\");\n").getBytes(StandardCharsets.UTF_8));
    }
}
