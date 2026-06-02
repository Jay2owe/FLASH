package flash.pipeline.project;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ProjectManifestTableModelPortablePathTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void toProjectFile_addsRelativeHintForSourceUnderOutputRoot() throws Exception {
        File outputRoot = temp.newFolder("output");
        File sourceDir = new File(outputRoot, "WT");
        assertTrue(sourceDir.mkdirs());
        File source = new File(sourceDir, "Exp-A_LH_X.lif");
        assertTrue(source.createNewFile());

        ProjectManifestTableModel model = new ProjectManifestTableModel();
        model.addFile(source);

        ProjectFile project = model.toProjectFile("Portable", outputRoot.getAbsolutePath(), "test");

        assertEquals("WT/Exp-A_LH_X.lif",
                project.items.get(0).extras.get(ProjectPathResolver.K_PATH_RELATIVE_TO_OUTPUT_ROOT));
    }
}
