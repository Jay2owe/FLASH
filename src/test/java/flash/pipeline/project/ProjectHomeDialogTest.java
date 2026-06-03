package flash.pipeline.project;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ProjectHomeDialogTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void openThrowsWhenHeadless() throws Exception {
        Assume.assumeTrue(GraphicsEnvironment.isHeadless());
        File plugins = temp.newFolder("plugins");

        try {
            ProjectHomeDialog.open(null, plugins);
        } catch (IllegalStateException e) {
            assertEquals("ProjectHomeDialog cannot run headless.", e.getMessage());
            return;
        }
        org.junit.Assert.fail("Expected ProjectHomeDialog.open to reject headless mode.");
    }

    @Test
    public void choiceFactoriesPopulateOnlyRelevantTarget() throws Exception {
        File projectJson = temp.newFile("project.json");
        File folder = temp.newFolder("folder");

        ProjectHomeDialog.Choice open = ProjectHomeDialog.Choice.openExisting(projectJson);
        assertEquals(ProjectHomeDialog.Choice.Action.OPEN_EXISTING, open.action);
        assertEquals(projectJson, open.projectJson);
        assertNull(open.folder);

        ProjectHomeDialog.Choice browse = ProjectHomeDialog.Choice.browseFolder(folder);
        assertEquals(ProjectHomeDialog.Choice.Action.BROWSE_FOLDER, browse.action);
        assertNull(browse.projectJson);
        assertEquals(folder, browse.folder);

        ProjectHomeDialog.Choice cancel = ProjectHomeDialog.Choice.cancel();
        assertEquals(ProjectHomeDialog.Choice.Action.CANCEL, cancel.action);
        assertNull(cancel.projectJson);
        assertNull(cancel.folder);
    }

    @Test
    public void withoutRecentRemovesByStoredPathOnly() {
        RecentProject a = new RecentProject("A", "C:/projects/a/project.json", 1L);
        RecentProject b = new RecentProject("B", "C:/projects/b/project.json", 2L);
        RecentProject c = new RecentProject("C", "C:/projects/c/project.json", 3L);

        List<RecentProject> out = ProjectHomeDialog.withoutRecent(Arrays.asList(a, b, c),
                new RecentProject("renamed", "c:/projects/b/project.json", 99L));

        assertEquals(2, out.size());
        assertEquals(a, out.get(0));
        assertEquals(c, out.get(1));
    }
}
