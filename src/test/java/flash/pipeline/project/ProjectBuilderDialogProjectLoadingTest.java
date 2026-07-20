package flash.pipeline.project;

import flash.pipeline.io.FlashProjectLayout;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ProjectBuilderDialogProjectLoadingTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void existingProjectSettingsDir_detectsModernProjectJson() throws Exception {
        File outputRoot = temp.newFolder("existing-project");
        ProjectFile project = new ProjectFile();
        project.name = "Existing";
        ProjectFileIO.write(
                FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath()).configurationWriteDir(),
                project);

        File settingsDir = ProjectBuilderDialog.existingProjectSettingsDir(outputRoot);

        assertEquals(FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath())
                .configurationWriteDir().getAbsolutePath(), settingsDir.getAbsolutePath());
    }

    @Test
    public void existingProjectSettingsDir_returnsNullWithoutProjectJson() throws Exception {
        File outputRoot = temp.newFolder("plain-folder");

        assertNull(ProjectBuilderDialog.existingProjectSettingsDir(outputRoot));
    }

    @Test
    public void collectAcceptedSources_skipsGeneratedFlashTree() throws Exception {
        File outputRoot = temp.newFolder("source-scan");
        File source = new File(outputRoot, "source.lif");
        assertTrue(source.createNewFile());

        File conditionDir = new File(outputRoot, "WT");
        assertTrue(conditionDir.mkdirs());
        File nestedSource = new File(conditionDir, "nested.czi");
        assertTrue(nestedSource.createNewFile());

        File flashDir = new File(outputRoot, FlashProjectLayout.FLASH_DIR);
        assertTrue(flashDir.mkdirs());
        File generated = new File(flashDir, "generated.tif");
        assertTrue(generated.createNewFile());

        List<File> sources = ProjectBuilderDialog.collectAcceptedSourcesForTests(outputRoot);

        assertEquals(2, sources.size());
        assertTrue(sources.contains(source));
        assertTrue(sources.contains(nestedSource));
    }
}
