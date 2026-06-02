package flash.pipeline.project;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecentProjectsStoreTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void readMissingReturnsEmpty() throws Exception {
        File plugins = temp.newFolder("plugins");
        assertTrue(RecentProjectsStore.read(plugins).isEmpty());
    }

    @Test
    public void writeReadRoundTripPreservesEntries() throws Exception {
        File plugins = temp.newFolder("plugins");
        List<RecentProject> entries = Arrays.asList(
                new RecentProject("Cohort A", "D:/p1/project.json", 1000L),
                new RecentProject("Cohort B", "D:/p2/project.json", 2000L));

        RecentProjectsStore.write(plugins, entries);
        List<RecentProject> back = RecentProjectsStore.read(plugins);

        assertEquals(2, back.size());
        assertEquals("Cohort B", back.get(0).name);
        assertEquals("Cohort A", back.get(1).name);
    }

    @Test
    public void readReturnsMostRecentFirst() throws Exception {
        File plugins = temp.newFolder("plugins");
        RecentProjectsStore.write(plugins, Arrays.asList(
                new RecentProject("old", "D:/p1/project.json", 100L),
                new RecentProject("middle", "D:/p2/project.json", 500L),
                new RecentProject("new", "D:/p3/project.json", 999L)));

        List<RecentProject> back = RecentProjectsStore.read(plugins);

        assertEquals("new", back.get(0).name);
        assertEquals("middle", back.get(1).name);
        assertEquals("old", back.get(2).name);
    }

    @Test
    public void writeCapsAtMaxEntries() throws Exception {
        File plugins = temp.newFolder("plugins");
        List<RecentProject> over = new ArrayList<RecentProject>();
        for (int i = 0; i < RecentProject.MAX_ENTRIES + 5; i++) {
            over.add(new RecentProject("P" + i, "D:/p" + i + "/project.json", i));
        }

        RecentProjectsStore.write(plugins, over);
        List<RecentProject> back = RecentProjectsStore.read(plugins);

        assertEquals(RecentProject.MAX_ENTRIES, back.size());
        assertEquals("P" + (RecentProject.MAX_ENTRIES + 4), back.get(0).name);
    }

    @Test
    public void recordOpenedPrependsAndDeduplicates() throws Exception {
        File plugins = temp.newFolder("plugins");
        RecentProjectsStore.write(plugins, Arrays.asList(
                new RecentProject("A", "D:/p1/project.json", 100L),
                new RecentProject("B", "D:/p2/project.json", 200L)));

        List<RecentProject> after = RecentProjectsStore.recordOpened(plugins,
                new RecentProject("A-renamed", "D:/p1/project.json", 999L));

        assertEquals(2, after.size());
        assertEquals("A-renamed", after.get(0).name);
        assertEquals("B", after.get(1).name);
    }

    @Test
    public void recordOpenedDedupIgnoresCase() throws Exception {
        File plugins = temp.newFolder("plugins");
        File realPath = new File(temp.newFolder("project-folder"), "project.json");
        assertTrue(realPath.createNewFile());

        RecentProjectsStore.write(plugins, Arrays.asList(
                new RecentProject("A", realPath.getAbsolutePath(), 100L)));

        // Re-open with the exact same path — should de-dup to one entry.
        List<RecentProject> after = RecentProjectsStore.recordOpened(plugins,
                new RecentProject("A2", realPath.getAbsolutePath(), 999L));

        assertEquals(1, after.size());
        assertEquals("A2", after.get(0).name);
    }

    @Test
    public void recordOpenedReplacingDropsObsoletePath() throws Exception {
        File plugins = temp.newFolder("plugins");
        RecentProjectsStore.write(plugins, Arrays.asList(
                new RecentProject("Old", "C:/Users/Owner/project/project.json", 100L),
                new RecentProject("Other", "D:/other/project.json", 200L)));

        List<RecentProject> after = RecentProjectsStore.recordOpenedReplacing(plugins,
                new RecentProject("Moved", "C:/Users/jamie/project/project.json", 999L),
                "C:/Users/Owner/project/project.json");

        assertEquals(2, after.size());
        assertEquals("Moved", after.get(0).name);
        assertEquals("C:/Users/jamie/project/project.json", after.get(0).path);
        assertEquals("Other", after.get(1).name);
    }

    @Test
    public void recordOpenedSkipsBlankEntry() throws Exception {
        File plugins = temp.newFolder("plugins");
        RecentProjectsStore.write(plugins, Arrays.asList(
                new RecentProject("A", "D:/p1/project.json", 100L)));

        List<RecentProject> after = RecentProjectsStore.recordOpened(plugins,
                new RecentProject("", "", 999L));

        assertEquals(1, after.size());
        assertEquals("A", after.get(0).name);
    }

    @Test
    public void readCorruptReturnsEmpty() throws Exception {
        File plugins = temp.newFolder("plugins");
        File file = new File(plugins, RecentProjectsStore.FILE_NAME);
        java.nio.file.Files.write(file.toPath(),
                "{not json".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertTrue(RecentProjectsStore.read(plugins).isEmpty());
    }

    @Test
    public void readRejectsWrongSchemaVersion() throws Exception {
        File plugins = temp.newFolder("plugins");
        File file = new File(plugins, RecentProjectsStore.FILE_NAME);
        java.nio.file.Files.write(file.toPath(),
                "{\"schemaVersion\":2,\"entries\":[{\"path\":\"x\"}]}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertTrue(RecentProjectsStore.read(plugins).isEmpty());
    }

    @Test
    public void readDropsEntriesWithBlankPath() throws Exception {
        File plugins = temp.newFolder("plugins");
        File file = new File(plugins, RecentProjectsStore.FILE_NAME);
        java.nio.file.Files.write(file.toPath(),
                ("{\"schemaVersion\":1,\"entries\":["
                        + "{\"name\":\"good\",\"path\":\"D:/p/project.json\",\"lastOpenedAt\":1},"
                        + "{\"name\":\"blank-path\",\"path\":\"\",\"lastOpenedAt\":2}"
                        + "]}").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        List<RecentProject> back = RecentProjectsStore.read(plugins);

        assertEquals(1, back.size());
        assertEquals("good", back.get(0).name);
    }

    @Test
    public void writeIsAtomicTmpFileDoesNotRemain() throws Exception {
        File plugins = temp.newFolder("plugins");
        RecentProjectsStore.write(plugins, Arrays.asList(
                new RecentProject("A", "D:/p1/project.json", 1L)));

        assertTrue(new File(plugins, RecentProjectsStore.FILE_NAME).isFile());
        assertFalse(new File(plugins, RecentProjectsStore.FILE_NAME + ".tmp").exists());
    }
}
