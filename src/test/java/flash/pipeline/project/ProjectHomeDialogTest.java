package flash.pipeline.project;

import flash.pipeline.io.FlashProjectLayout;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void relocatedDropboxRecentCanBeConfirmedAndStoredAtResolvedPath() throws Exception {
        File plugins = temp.newFolder("plugins");
        File oldHome = temp.newFolder("old-home");
        File oldProject = new File(new File(oldHome, "Dropbox"), "Cohort");
        File oldProjectJson = createProject(oldProject);
        String storedPath = oldProjectJson.getAbsolutePath();

        File currentHome = temp.newFolder("current-home");
        File currentDropbox = new File(currentHome, "Dropbox");
        assertTrue(currentDropbox.mkdirs());
        File currentProject = new File(currentDropbox, "Cohort");
        java.nio.file.Files.move(oldProject.toPath(), currentProject.toPath());
        File currentProjectJson = new File(
                FlashProjectLayout.forDirectory(currentProject.getAbsolutePath()).configurationWriteDir(),
                ProjectFileIO.FILE_NAME);

        String originalUserHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", currentHome.getAbsolutePath());

            RecentProjectsStore.write(plugins, Arrays.asList(
                    new RecentProject("Cohort", storedPath, 1L)));
            ProjectService.ResolveOutcome outcome = ProjectService.resolveRecent(storedPath);

            assertNotNull(outcome.projectJson);
            assertTrue(outcome.relocated);
            assertEquals(currentProjectJson.getCanonicalPath(),
                    outcome.projectJson.getCanonicalPath());
            assertTrue(ProjectHomeDialog.relocationMessage(outcome.projectJson)
                    .contains("Reconnected"));

            RecentProjectsStore.recordOpenedReplacing(plugins,
                    ProjectHomeDialog.replacementEntryForResolvedPath(
                            new RecentProject("Cohort", storedPath, 1L),
                            outcome.projectJson,
                            99L),
                    outcome.storedPath);

            List<RecentProject> after = RecentProjectsStore.read(plugins);
            assertEquals(1, after.size());
            assertEquals(currentProjectJson.getAbsolutePath(), after.get(0).path);
            assertEquals(99L, after.get(0).lastOpenedAt);
        } finally {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    public void startingDirectoryUsesStoredParentWhenItExists() throws Exception {
        File projectFolder = temp.newFolder("lost-project-parent");
        File storedProjectJson = new File(projectFolder, "project.json");

        File start = ProjectHomeDialog.startingDirectoryForStoredPath(
                storedProjectJson.getAbsolutePath());

        assertEquals(projectFolder.getCanonicalPath(), start.getCanonicalPath());
    }

    @Test
    public void unavailableTextDistinguishesMissingFolderFromOfflineRoot() throws Exception {
        File missingUnderCurrentRoot = new File(temp.getRoot(), "missing/project.json");

        assertEquals("Unavailable - folder missing",
                ProjectHomeDialog.unavailableTextForPath(missingUnderCurrentRoot.getAbsolutePath()));

        Assume.assumeTrue("offline drive check is Windows-specific", File.separatorChar == '\\');
        String missingDrivePath = missingDrivePath();
        Assume.assumeTrue("all drive letters are mounted", missingDrivePath != null);

        assertFalse(ProjectHomeDialog.storedRootAvailable(missingDrivePath));
        assertEquals("Unavailable - still syncing or offline?",
                ProjectHomeDialog.unavailableTextForPath(missingDrivePath));
    }

    private File createProject(File outputRoot) throws Exception {
        File settingsDir = FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath())
                .configurationWriteDir();
        assertTrue(settingsDir.mkdirs());
        ProjectFile project = new ProjectFile();
        project.name = outputRoot.getName();
        ProjectFileIO.write(settingsDir, project);
        return new File(settingsDir, ProjectFileIO.FILE_NAME);
    }

    private static String missingDrivePath() {
        boolean[] mounted = new boolean[26];
        File[] roots = File.listRoots();
        for (int i = 0; roots != null && i < roots.length; i++) {
            String path = roots[i].getAbsolutePath();
            if (path.length() > 0) {
                char c = Character.toUpperCase(path.charAt(0));
                if (c >= 'A' && c <= 'Z') {
                    mounted[c - 'A'] = true;
                }
            }
        }
        for (char c = 'Z'; c >= 'D'; c--) {
            if (!mounted[c - 'A']) {
                return c + ":\\offline\\project.json";
            }
        }
        return null;
    }
}
