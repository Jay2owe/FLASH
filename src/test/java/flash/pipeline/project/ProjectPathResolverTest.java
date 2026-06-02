package flash.pipeline.project;

import flash.pipeline.io.FlashProjectLayout;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ProjectPathResolverTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void projectJsonFromSelectedLocation_acceptsProjectRoot() throws Exception {
        File outputRoot = temp.newFolder("project-root");
        File settingsDir = FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath())
                .configurationWriteDir();
        ProjectFileIO.write(settingsDir, new ProjectFile());

        File projectJson = ProjectPathResolver.projectJsonFromSelectedLocation(outputRoot);

        assertEquals(new File(settingsDir, ProjectFileIO.FILE_NAME).getCanonicalPath(),
                projectJson.getCanonicalPath());
    }

    @Test
    public void projectJsonFromSelectedLocation_acceptsFlashFolderDirectly() throws Exception {
        File outputRoot = temp.newFolder("project-root");
        File settingsDir = FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath())
                .configurationWriteDir();
        ProjectFileIO.write(settingsDir, new ProjectFile());
        File flashFolder = new File(outputRoot, FlashProjectLayout.FLASH_DIR);

        File projectJson = ProjectPathResolver.projectJsonFromSelectedLocation(flashFolder);

        assertEquals(new File(settingsDir, ProjectFileIO.FILE_NAME).getCanonicalPath(),
                projectJson.getCanonicalPath());
    }

    @Test
    public void projectJsonFromSelectedLocation_acceptsSettingsDir() throws Exception {
        File outputRoot = temp.newFolder("project-root");
        File settingsDir = FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath())
                .configurationWriteDir();
        ProjectFileIO.write(settingsDir, new ProjectFile());

        File projectJson = ProjectPathResolver.projectJsonFromSelectedLocation(settingsDir);

        assertEquals(new File(settingsDir, ProjectFileIO.FILE_NAME).getCanonicalPath(),
                projectJson.getCanonicalPath());
    }

    @Test
    public void projectJsonFromSelectedLocation_rejectsPlainFolder() throws Exception {
        assertNull(ProjectPathResolver.projectJsonFromSelectedLocation(temp.newFolder("plain")));
    }

    @Test
    public void addRelativePathHints_recordsSourcesUnderOutputRoot() throws Exception {
        File outputRoot = temp.newFolder("output");
        File sourceDir = new File(outputRoot, "WT");
        assertTrue(sourceDir.mkdirs());
        File source = new File(sourceDir, "Exp-A_LH_X.lif");
        assertTrue(source.createNewFile());

        ProjectFile project = new ProjectFile();
        ProjectFile.Item item = new ProjectFile.Item();
        item.path = source.getAbsolutePath();
        project.items.add(item);

        ProjectPathResolver.addRelativePathHints(project, outputRoot);

        assertEquals("WT/Exp-A_LH_X.lif",
                project.items.get(0).extras.get(ProjectPathResolver.K_PATH_RELATIVE_TO_OUTPUT_ROOT));
    }

    @Test
    public void relocateForLoad_usesRelativeHintWhenAbsolutePathMoved() throws Exception {
        File outputRoot = temp.newFolder("new-output");
        File sourceDir = new File(outputRoot, "WT");
        assertTrue(sourceDir.mkdirs());
        File source = new File(sourceDir, "Exp-A_LH_X.lif");
        assertTrue(source.createNewFile());
        File settingsDir = FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath())
                .configurationWriteDir();
        assertTrue(settingsDir.mkdirs());
        File projectJson = new File(settingsDir, ProjectFileIO.FILE_NAME);

        ProjectFile project = new ProjectFile();
        project.outputRoot = new File(temp.getRoot(), "old-output").getAbsolutePath();
        ProjectFile.Item item = new ProjectFile.Item();
        item.path = new File(temp.getRoot(), "old-output/WT/Exp-A_LH_X.lif").getAbsolutePath();
        item.extras.put(ProjectPathResolver.K_PATH_RELATIVE_TO_OUTPUT_ROOT, "WT/Exp-A_LH_X.lif");
        project.items.add(item);

        ProjectPathResolver.relocateForLoad(project, projectJson, outputRoot);

        assertEquals(outputRoot.getAbsolutePath(), project.outputRoot);
        assertEquals(source.getAbsolutePath(), project.items.get(0).path);
    }

    @Test
    public void relocateForLoad_anchorsRootToOpenedLocationWhenStoredRootStillExists() throws Exception {
        // Reproduces the reopen bug: a project folder is copied / restored to a
        // new location while the ORIGINAL still exists on disk. project.json
        // carries the original's absolute outputRoot. Reopening from the new
        // location must resolve the root to where project.json physically lives
        // now, so channel_config.json (which sits beside project.json) reloads
        // from the opened project, not the stale original.
        File originalRoot = temp.newFolder("original-project");
        // Make the stale path a believable FLASH project directory that exists.
        assertTrue(FlashProjectLayout.forDirectory(originalRoot.getAbsolutePath())
                .configurationWriteDir().mkdirs());

        File actualRoot = temp.newFolder("copied-project");
        File settingsDir = FlashProjectLayout.forDirectory(actualRoot.getAbsolutePath())
                .configurationWriteDir();
        assertTrue(settingsDir.mkdirs());
        File projectJson = new File(settingsDir, ProjectFileIO.FILE_NAME);

        ProjectFile project = new ProjectFile();
        project.outputRoot = originalRoot.getAbsolutePath();

        ProjectPathResolver.relocateForLoad(project, projectJson, actualRoot);

        assertEquals(actualRoot.getAbsolutePath(), project.outputRoot);
    }

    @Test
    public void relocateForLoad_mapsOldOutputRootPrefixToCurrentRoot() throws Exception {
        File oldOutputRoot = new File(temp.getRoot(), "old-output");
        File outputRoot = temp.newFolder("new-output");
        File sourceDir = new File(outputRoot, "KO");
        assertTrue(sourceDir.mkdirs());
        File source = new File(sourceDir, "Exp-B_RH_Y.tif");
        assertTrue(source.createNewFile());
        File settingsDir = FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath())
                .configurationWriteDir();
        assertTrue(settingsDir.mkdirs());
        File projectJson = new File(settingsDir, ProjectFileIO.FILE_NAME);

        ProjectFile project = new ProjectFile();
        project.outputRoot = oldOutputRoot.getAbsolutePath();
        ProjectFile.Item item = new ProjectFile.Item();
        item.path = new File(oldOutputRoot, "KO/Exp-B_RH_Y.tif").getAbsolutePath();
        project.items.add(item);

        ProjectPathResolver.relocateForLoad(project, projectJson, outputRoot);

        assertEquals(outputRoot.getAbsolutePath(), project.outputRoot);
        assertEquals(source.getAbsolutePath(), project.items.get(0).path);
    }
}
