package flash.pipeline.runrecord;

import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.roi.RoiIO;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.Assert.*;

public class ConfigurationSnapshotTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void capturesSettingsPresetsRegionSelectionsAndIdentityTablesButNotResults() throws Exception {
        File root = temp.newFolder("project");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(root.getAbsolutePath());
        File settings = write(new File(layout.configurationWriteDir(), "channels.json"), "channels");
        File preset = write(new File(layout.presetsRoot(), "analysis/example.json"), "preset");
        File rois = write(new File(RoiIO.roiSetWriteDir(root), "sample_ROIs.zip"), "regions");
        File conditions = write(layout.projectSummaryWriteFile(FlashProjectLayout.CONDITIONS_FILENAME), "conditions");
        File orientation = write(layout.projectSummaryWriteFile(FlashProjectLayout.ORIENTATION_MANIFEST_FILENAME), "orientation");
        write(new File(layout.configurationWriteDir(), "pending.tmp"), "temporary");
        write(new File(root, "unrelated.csv"), "results");
        Map<String, Object> snapshot = ConfigurationSnapshot.capture(root);
        for (File source : new File[]{settings, preset, rois, conditions, orientation}) {
            String relative = root.toPath().relativize(source.toPath()).toString().replace(File.separatorChar, '/');
            assertEquals(InputFingerprinter.fullFingerprint(source).value, snapshot.get(relative));
        }
        assertEquals(5, snapshot.size());
        Files.write(rois.toPath(), "changed regions".getBytes(StandardCharsets.UTF_8));
        assertNotEquals(snapshot, ConfigurationSnapshot.capture(root));
    }

    @Test
    public void emptyProjectExplicitlyCapturesEmptyConfiguration() throws Exception {
        assertTrue(ConfigurationSnapshot.capture(temp.newFolder("empty")).isEmpty());
    }

    @Test
    public void recordedPathCannotEscapeProject() throws Exception {
        File root = temp.newFolder("project");
        try {
            ConfigurationSnapshot.resolve(root, "../other/settings.json");
            fail("outside path must be rejected");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("escapes the project"));
        }
    }

    private static File write(File file, String content) throws Exception {
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
