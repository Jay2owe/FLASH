package flash.pipeline.project;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ProjectFileIORoundTripTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void writeReadRoundTripPreservesAllFields() throws Exception {
        File settingsDir = temp.newFolder("settings");

        ProjectFile project = sampleProject();
        project.extras.put("futureRoot", "keep");
        project.items.get(0).extras.put("futureItem", "keep");

        ProjectFileIO.write(settingsDir, project);
        ProjectFile back = ProjectFileIO.read(settingsDir);

        assertEquals("test-writer", back.writerId);
        assertEquals(123L, back.writtenAtMillis);
        assertEquals("Cohort A", back.name);
        assertEquals("D:/out", back.outputRoot);
        assertEquals(1, back.items.size());
        assertEquals("X.lif", back.items.get(0).path);
        assertEquals("WT", back.items.get(0).condition);
        assertEquals("keep", back.extras.get("futureRoot"));
        assertEquals("keep", back.items.get(0).extras.get("futureItem"));
    }

    @Test
    public void writeIsAtomicTmpFileDoesNotRemain() throws Exception {
        File settingsDir = temp.newFolder("atomic");

        ProjectFileIO.write(settingsDir, sampleProject());

        assertTrue(new File(settingsDir, ProjectFileIO.FILE_NAME).isFile());
        assertFalse(new File(settingsDir, ProjectFileIO.FILE_NAME + ".tmp").exists());
    }

    @Test
    public void writeCreatesMissingSettingsDir() throws Exception {
        File settingsDir = new File(temp.newFolder("created"), "nested/.settings");

        ProjectFileIO.write(settingsDir, sampleProject());

        assertTrue(new File(settingsDir, ProjectFileIO.FILE_NAME).isFile());
    }

    @Test
    public void readMissingReturnsNull() throws Exception {
        assertNull(ProjectFileIO.read(temp.newFolder("missing")));
    }

    @Test
    public void readCorruptReturnsNullWithoutThrowing() throws Exception {
        File settingsDir = temp.newFolder("corrupt");
        Files.write(new File(settingsDir, ProjectFileIO.FILE_NAME).toPath(),
                "{not json".getBytes(StandardCharsets.UTF_8));

        assertNull(ProjectFileIO.read(settingsDir));
    }

    @Test
    public void existsReportsPresence() throws Exception {
        File settingsDir = temp.newFolder("exists");

        assertFalse(ProjectFileIO.exists(settingsDir));
        ProjectFileIO.write(settingsDir, sampleProject());
        assertTrue(ProjectFileIO.exists(settingsDir));
    }

    @Test
    public void deleteRemovesFile() throws Exception {
        File settingsDir = temp.newFolder("delete");
        ProjectFileIO.write(settingsDir, sampleProject());
        assertTrue(ProjectFileIO.exists(settingsDir));

        ProjectFileIO.delete(settingsDir);

        assertFalse(ProjectFileIO.exists(settingsDir));
    }

    static ProjectFile sampleProject() {
        ProjectFile project = new ProjectFile();
        project.writerId = "test-writer";
        project.writtenAtMillis = 123L;
        project.name = "Cohort A";
        project.outputRoot = "D:/out";

        ProjectFile.Item item = new ProjectFile.Item();
        item.path = "X.lif";
        item.series.addAll(Arrays.asList(Integer.valueOf(0)));
        item.include = true;
        item.animalId = "03";
        item.hemisphere = "LH";
        item.region = "Hb";
        item.condition = "WT";
        item.notes = "";
        project.items.add(item);

        return project;
    }
}
