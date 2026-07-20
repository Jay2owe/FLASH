package flash.pipeline.project;

import flash.pipeline.bin.ChannelConfigIO;
import flash.pipeline.io.FlashProjectLayout;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ProjectServiceTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void classifyProjectRootWithProjectJsonAsValidFlash() throws Exception {
        File outputRoot = createProject("project-root", new ProjectFile()).outputRoot;

        assertEquals(ProjectService.ProjectKind.VALID_FLASH, ProjectService.classify(outputRoot));
    }

    @Test
    public void classifyFlashConfigSettingsAndProjectJsonSelectionsAsValidFlash() throws Exception {
        ProjectFixture fixture = createProject("selection-project", new ProjectFile());
        File flashFolder = new File(fixture.outputRoot, FlashProjectLayout.FLASH_DIR);
        File configFolder = new File(flashFolder, FlashProjectLayout.CONFIGURATION_DIR);

        assertEquals(ProjectService.ProjectKind.VALID_FLASH, ProjectService.classify(flashFolder));
        assertEquals(ProjectService.ProjectKind.VALID_FLASH, ProjectService.classify(configFolder));
        assertEquals(ProjectService.ProjectKind.VALID_FLASH, ProjectService.classify(fixture.settingsDir));
        assertEquals(ProjectService.ProjectKind.VALID_FLASH, ProjectService.classify(fixture.projectJson));
    }

    @Test
    public void classifyProjectWithOnlyChannelConfigMarkerAsValidFlash() throws Exception {
        File outputRoot = temp.newFolder("channel-only");
        File settingsDir = FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath()).configurationWriteDir();
        assertTrue(settingsDir.mkdirs());
        assertTrue(new File(settingsDir, ChannelConfigIO.FILE_NAME).createNewFile());

        assertEquals(ProjectService.ProjectKind.VALID_FLASH, ProjectService.classify(outputRoot));
        assertEquals(ProjectService.ProjectKind.VALID_FLASH,
                ProjectService.classify(new File(outputRoot, FlashProjectLayout.FLASH_DIR)));
        assertEquals(ProjectService.ProjectKind.VALID_FLASH, ProjectService.classify(settingsDir));
    }

    @Test
    public void classifyEmptyWritableFolderAsNewEmpty() throws Exception {
        File folder = temp.newFolder("empty");

        assertEquals(ProjectService.ProjectKind.NEW_EMPTY, ProjectService.classify(folder));
    }

    @Test
    public void classifyFolderWithUnrelatedFilesAsForeign() throws Exception {
        File folder = temp.newFolder("foreign");
        assertTrue(new File(folder, "notes.txt").createNewFile());

        assertEquals(ProjectService.ProjectKind.FOREIGN, ProjectService.classify(folder));
    }

    @Test
    public void resolveProjectJsonAcceptsProjectFolderPointers() throws Exception {
        ProjectFixture fixture = createProject("resolve-project", new ProjectFile());

        File resolved = ProjectService.resolveProjectJson(fixture.outputRoot);

        assertNotNull(resolved);
        assertEquals(fixture.projectJson.getCanonicalPath(), resolved.getCanonicalPath());
    }

    @Test
    public void resolveRecentReportsNoRelocationForStoredProjectJson() throws Exception {
        ProjectFixture fixture = createProject("recent-same", new ProjectFile());

        ProjectService.ResolveOutcome outcome = ProjectService.resolveRecent(fixture.projectJson.getAbsolutePath());

        assertNotNull(outcome.projectJson);
        assertEquals(fixture.projectJson.getCanonicalPath(), outcome.projectJson.getCanonicalPath());
        assertEquals(fixture.projectJson.getAbsolutePath(), outcome.storedPath);
        assertFalse(outcome.relocated);
    }

    @Test
    public void resolveRecentReportsRelocationWhenStoredPointerDiffersFromResolvedProjectJson() throws Exception {
        ProjectFixture fixture = createProject("recent-root-pointer", new ProjectFile());

        ProjectService.ResolveOutcome outcome = ProjectService.resolveRecent(fixture.outputRoot.getAbsolutePath());

        assertNotNull(outcome.projectJson);
        assertEquals(fixture.projectJson.getCanonicalPath(), outcome.projectJson.getCanonicalPath());
        assertEquals(fixture.outputRoot.getAbsolutePath(), outcome.storedPath);
        assertTrue(outcome.relocated);
    }

    @Test
    public void resolveRecentRelocatesProjectMovedUnderDropboxRoot() throws Exception {
        File oldHome = temp.newFolder("old-home");
        File oldProject = new File(new File(oldHome, "Dropbox"), "Cohort");
        ProjectFixture fixture = createProjectAt(oldProject, new ProjectFile());
        String storedPath = fixture.projectJson.getAbsolutePath();

        File currentHome = temp.newFolder("current-home");
        File currentDropbox = new File(currentHome, "Dropbox");
        assertTrue(currentDropbox.mkdirs());
        File currentProject = new File(currentDropbox, "Cohort");
        Files.move(oldProject.toPath(), currentProject.toPath());
        File currentProjectJson = new File(
                FlashProjectLayout.forDirectory(currentProject.getAbsolutePath()).configurationWriteDir(),
                ProjectFileIO.FILE_NAME);

        String originalUserHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", currentHome.getAbsolutePath());

            ProjectService.ResolveOutcome outcome = ProjectService.resolveRecent(storedPath);

            assertNotNull(outcome.projectJson);
            assertEquals(currentProjectJson.getCanonicalPath(), outcome.projectJson.getCanonicalPath());
            assertEquals(storedPath, outcome.storedPath);
            assertTrue(outcome.relocated);
        } finally {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    public void resolveRecentReturnsUnresolvedOutcomeForMissingProject() throws Exception {
        File missing = new File(temp.getRoot(), "missing-project.json");

        ProjectService.ResolveOutcome outcome = ProjectService.resolveRecent(missing.getAbsolutePath());

        assertNull(outcome.projectJson);
        assertEquals(missing.getAbsolutePath(), outcome.storedPath);
        assertFalse(outcome.relocated);
    }

    @Test
    public void loadRelocatesProjectToOpenedRoot() throws Exception {
        File staleRoot = new File(temp.getRoot(), "stale-root");
        ProjectFile project = new ProjectFile();
        project.name = "Moved project";
        project.outputRoot = staleRoot.getAbsolutePath();
        ProjectFixture fixture = createProject("actual-root", project);

        ProjectFile loaded = ProjectService.load(fixture.projectJson);

        assertNotNull(loaded);
        assertEquals("Moved project", loaded.name);
        assertEquals(fixture.outputRoot.getAbsolutePath(), loaded.outputRoot);
    }

    private ProjectFixture createProject(String folderName, ProjectFile project) throws Exception {
        File outputRoot = temp.newFolder(folderName);
        return createProjectAt(outputRoot, project);
    }

    private ProjectFixture createProjectAt(File outputRoot, ProjectFile project) throws Exception {
        File settingsDir = FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath()).configurationWriteDir();
        ProjectFileIO.write(settingsDir, project);
        return new ProjectFixture(outputRoot, settingsDir, new File(settingsDir, ProjectFileIO.FILE_NAME));
    }

    private static final class ProjectFixture {
        final File outputRoot;
        final File settingsDir;
        final File projectJson;

        ProjectFixture(File outputRoot, File settingsDir, File projectJson) {
            this.outputRoot = outputRoot;
            this.settingsDir = settingsDir;
            this.projectJson = projectJson;
        }
    }
}
