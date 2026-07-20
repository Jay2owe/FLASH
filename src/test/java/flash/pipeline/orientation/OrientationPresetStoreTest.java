package flash.pipeline.orientation;

import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.naming.OrientationManifestRow;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static flash.pipeline.naming.OrientationManifestRow.RotationDegrees.DEG_180;
import static flash.pipeline.naming.OrientationManifestRow.RotationDegrees.DEG_270;
import static flash.pipeline.naming.OrientationManifestRow.RotationDegrees.DEG_90;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OrientationPresetStoreTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void saveThenLoadRoundTripPreservesPresetsAndUsesSettingsDir() throws Exception {
        File projectDir = temp.newFolder("round-trip");
        OrientationPresetStore store = new OrientationPresetStore(projectDir.getAbsolutePath());
        List<OrientationPreset> input = Arrays.asList(
                preset("Sideways slides", DEG_90, true, false),
                preset("Upside down", DEG_180, false, true));

        store.save(input);

        File expectedFile = presetFile(projectDir);
        File expectedSettingsDir = new File(
                new File(new File(projectDir, FlashProjectLayout.FLASH_DIR),
                        FlashProjectLayout.CONFIGURATION_DIR),
                FlashProjectLayout.SETTINGS_DIR);
        assertTrue(expectedFile.isFile());
        assertEquals(expectedSettingsDir.getCanonicalFile(),
                expectedFile.getParentFile().getCanonicalFile());
        assertEquals(input, store.load());
    }

    @Test
    public void absentFileReturnsEmptyMutableList() throws Exception {
        OrientationPresetStore store =
                new OrientationPresetStore(temp.newFolder("absent").getAbsolutePath());

        List<OrientationPreset> presets = store.load();

        assertTrue(presets.isEmpty());
        presets.add(preset("Later", DEG_90, false, false));
        assertEquals(1, presets.size());
    }

    @Test
    public void malformedJsonReturnsEmptyListWithoutThrowing() throws Exception {
        File projectDir = temp.newFolder("malformed");
        File file = presetFile(projectDir);
        write(file, "{not json");
        OrientationPresetStore store = new OrientationPresetStore(projectDir.getAbsolutePath());

        assertTrue(store.load().isEmpty());
    }

    @Test
    public void entryWithBadRotationIsSkipped() throws Exception {
        File projectDir = temp.newFolder("bad-rotation");
        File file = presetFile(projectDir);
        write(file, "{"
                + "\"version\":1,"
                + "\"presets\":["
                + "{\"name\":\"Good\",\"rotate\":270,\"flipH\":true,\"flipV\":false},"
                + "{\"name\":\"Bad\",\"rotate\":\"sideways\",\"flipH\":false,\"flipV\":true}"
                + "]"
                + "}");
        OrientationPresetStore store = new OrientationPresetStore(projectDir.getAbsolutePath());

        List<OrientationPreset> presets = store.load();

        assertEquals(1, presets.size());
        assertEquals("Good", presets.get(0).name);
        assertTransform(DEG_270, true, false, presets.get(0).transform);
    }

    @Test
    public void addReplacesSameNamePresetCaseInsensitively() throws Exception {
        File projectDir = temp.newFolder("replace");
        OrientationPresetStore store = new OrientationPresetStore(projectDir.getAbsolutePath());
        store.save(Arrays.asList(preset("Sideways", DEG_90, false, false)));

        List<OrientationPreset> after =
                store.add(preset("sideWAYS", DEG_180, true, true));

        assertEquals(1, after.size());
        assertEquals("sideWAYS", after.get(0).name);
        assertTransform(DEG_180, true, true, after.get(0).transform);
        assertEquals(after, store.load());
    }

    @Test
    public void removeByNameRemovesPresetCaseInsensitively() throws Exception {
        File projectDir = temp.newFolder("remove");
        OrientationPresetStore store = new OrientationPresetStore(projectDir.getAbsolutePath());
        store.save(Arrays.asList(
                preset("Keep", DEG_90, false, false),
                preset("Remove", DEG_180, true, true)));

        List<OrientationPreset> after = store.removeByName("remove");

        assertEquals(1, after.size());
        assertEquals("Keep", after.get(0).name);
        assertFalse(store.load().contains(preset("Remove", DEG_180, true, true)));
    }

    private static OrientationPreset preset(String name,
                                            OrientationManifestRow.RotationDegrees rotateDegrees,
                                            boolean flipHorizontal,
                                            boolean flipVertical) {
        return new OrientationPreset(
                name,
                new OrientationTransformState(rotateDegrees, flipHorizontal, flipVertical));
    }

    private static File presetFile(File projectDir) {
        return new File(
                FlashProjectLayout.forDirectory(projectDir.getAbsolutePath()).configurationWriteDir(),
                OrientationPresetStore.FILE_NAME);
    }

    private static void write(File file, String text) throws Exception {
        File parent = file.getParentFile();
        assertTrue(parent.mkdirs() || parent.isDirectory());
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertTransform(OrientationManifestRow.RotationDegrees rotateDegrees,
                                        boolean flipHorizontal,
                                        boolean flipVertical,
                                        OrientationTransformState actual) {
        assertEquals(rotateDegrees, actual.rotateDegrees);
        assertEquals(flipHorizontal, actual.flipHorizontal);
        assertEquals(flipVertical, actual.flipVertical);
    }
}
