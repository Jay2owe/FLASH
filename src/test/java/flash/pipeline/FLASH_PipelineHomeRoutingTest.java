package flash.pipeline;

import flash.pipeline.bin.ChannelConfigIO;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.project.ProjectBuilderDialog;
import flash.pipeline.project.ProjectFile;
import flash.pipeline.project.ProjectFileIO;
import flash.pipeline.project.ProjectHomeDialog;
import flash.pipeline.project.RecentProject;
import flash.pipeline.project.RecentProjectsStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.awt.Window;
import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class FLASH_PipelineHomeRoutingTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void openExistingChoiceLoadsProjectRecordsRecentAndMarksFastReopen() throws Exception {
        File plugins = temp.newFolder("plugins");
        ProjectFixture fixture = createProject("cohort", "TBI Cohort");

        FLASH_Pipeline.ProjectLaunchSelection selection = FLASH_Pipeline.routeHomeChoice(
                ProjectHomeDialog.Choice.openExisting(fixture.projectJson),
                null,
                plugins,
                failingBuilder());

        assertNotNull(selection);
        assertTrue(selection.fastReopen);
        assertEquals(fixture.outputRoot.getCanonicalPath(),
                selection.outputRoot.getCanonicalPath());

        List<RecentProject> recents = RecentProjectsStore.read(plugins);
        assertEquals(1, recents.size());
        assertEquals("TBI Cohort", recents.get(0).name);
        assertEquals(fixture.projectJson.getAbsolutePath(), recents.get(0).path);
    }

    @Test
    public void browseValidFlashFolderLoadsProjectWithoutBuilder() throws Exception {
        File plugins = temp.newFolder("plugins");
        ProjectFixture fixture = createProject("existing", "Existing Project");

        FLASH_Pipeline.ProjectLaunchSelection selection = FLASH_Pipeline.routeHomeChoice(
                ProjectHomeDialog.Choice.browseFolder(fixture.outputRoot),
                null,
                plugins,
                failingBuilder());

        assertNotNull(selection);
        assertTrue(selection.fastReopen);
        assertEquals(fixture.outputRoot.getCanonicalPath(),
                selection.outputRoot.getCanonicalPath());
        assertEquals(fixture.projectJson.getAbsolutePath(),
                RecentProjectsStore.read(plugins).get(0).path);
    }

    @Test
    public void browseConfigOnlyFlashFolderFallsBackToBuilder() throws Exception {
        File plugins = temp.newFolder("plugins");
        File outputRoot = temp.newFolder("config-only");
        File settingsDir = FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath())
                .configurationWriteDir();
        assertTrue(settingsDir.mkdirs());
        assertTrue(new File(settingsDir, ChannelConfigIO.FILE_NAME).createNewFile());
        final File[] suggested = new File[1];
        final boolean[] opened = new boolean[1];

        FLASH_Pipeline.ProjectLaunchSelection selection = FLASH_Pipeline.routeHomeChoice(
                ProjectHomeDialog.Choice.browseFolder(outputRoot),
                null,
                plugins,
                capturingBuilder(opened, suggested));

        assertNull(selection);
        assertTrue(opened[0]);
        assertEquals(outputRoot.getCanonicalPath(), suggested[0].getCanonicalPath());
    }

    @Test
    public void newProjectChoiceOpensBlankBuilder() throws Exception {
        File plugins = temp.newFolder("plugins");
        final File[] suggested = new File[1];
        final boolean[] opened = new boolean[1];

        FLASH_Pipeline.ProjectLaunchSelection selection = FLASH_Pipeline.routeHomeChoice(
                ProjectHomeDialog.Choice.newProject(),
                null,
                plugins,
                capturingBuilder(opened, suggested));

        assertNull(selection);
        assertTrue(opened[0]);
        assertNull(suggested[0]);
    }

    @Test
    public void browseEmptyFolderOpensBuilderWithThatFolder() throws Exception {
        File plugins = temp.newFolder("plugins");
        File empty = temp.newFolder("empty-source");
        final File[] suggested = new File[1];
        final boolean[] opened = new boolean[1];

        FLASH_Pipeline.ProjectLaunchSelection selection = FLASH_Pipeline.routeHomeChoice(
                ProjectHomeDialog.Choice.browseFolder(empty),
                null,
                plugins,
                capturingBuilder(opened, suggested));

        assertNull(selection);
        assertTrue(opened[0]);
        assertEquals(empty.getCanonicalPath(), suggested[0].getCanonicalPath());
    }

    @Test
    public void browseForeignFolderOpensBuilderWithThatFolder() throws Exception {
        File plugins = temp.newFolder("plugins");
        File foreign = temp.newFolder("foreign-source");
        assertTrue(new File(foreign, "notes.txt").createNewFile());
        final File[] suggested = new File[1];
        final boolean[] opened = new boolean[1];

        FLASH_Pipeline.ProjectLaunchSelection selection = FLASH_Pipeline.routeHomeChoice(
                ProjectHomeDialog.Choice.browseFolder(foreign),
                null,
                plugins,
                capturingBuilder(opened, suggested));

        assertNull(selection);
        assertTrue(opened[0]);
        assertEquals(foreign.getCanonicalPath(), suggested[0].getCanonicalPath());
    }

    @Test
    public void editExistingChoiceOpensBuilderAtProjectRoot() throws Exception {
        File plugins = temp.newFolder("plugins");
        ProjectFixture fixture = createProject("editable", "Editable Project");
        final File[] suggested = new File[1];
        final boolean[] opened = new boolean[1];

        FLASH_Pipeline.ProjectLaunchSelection selection = FLASH_Pipeline.routeHomeChoice(
                ProjectHomeDialog.Choice.editExisting(fixture.projectJson),
                null,
                plugins,
                capturingBuilder(opened, suggested));

        assertNull(selection);
        assertTrue(opened[0]);
        assertEquals(fixture.outputRoot.getCanonicalPath(),
                suggested[0].getCanonicalPath());
    }

    @Test
    public void cancelChoiceReturnsNoSelection() throws Exception {
        File plugins = temp.newFolder("plugins");

        FLASH_Pipeline.ProjectLaunchSelection selection = FLASH_Pipeline.routeHomeChoice(
                ProjectHomeDialog.Choice.cancel(),
                null,
                plugins,
                failingBuilder());

        assertNull(selection);
    }

    private ProjectFixture createProject(String folderName, String projectName) throws Exception {
        File outputRoot = temp.newFolder(folderName);
        File settingsDir = FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath())
                .configurationWriteDir();
        ProjectFile project = new ProjectFile();
        project.name = projectName;
        project.outputRoot = outputRoot.getAbsolutePath();
        ProjectFileIO.write(settingsDir, project);
        return new ProjectFixture(outputRoot, new File(settingsDir, ProjectFileIO.FILE_NAME));
    }

    private static FLASH_Pipeline.ProjectBuilderOpener failingBuilder() {
        return new FLASH_Pipeline.ProjectBuilderOpener() {
            @Override public ProjectBuilderDialog.Result open(Window owner, File pluginsDir,
                                                              File suggestedSourceFolder) {
                fail("Builder should not be opened for fast-reopen routes.");
                return null;
            }
        };
    }

    private static FLASH_Pipeline.ProjectBuilderOpener capturingBuilder(final boolean[] opened,
                                                                        final File[] suggested) {
        return new FLASH_Pipeline.ProjectBuilderOpener() {
            @Override public ProjectBuilderDialog.Result open(Window owner, File pluginsDir,
                                                              File suggestedSourceFolder) {
                opened[0] = true;
                suggested[0] = suggestedSourceFolder;
                return null;
            }
        };
    }

    private static final class ProjectFixture {
        final File outputRoot;
        final File projectJson;

        ProjectFixture(File outputRoot, File projectJson) {
            this.outputRoot = outputRoot;
            this.projectJson = projectJson;
        }
    }
}
