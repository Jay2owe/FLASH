package flash.pipeline.intelligence;

import flash.pipeline.io.FlashProjectLayout;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PreFlightChecksOutputFolderTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void knownProjectReopenSkipsExistingOutputFolderPrompt() throws Exception {
        File outputRoot = temp.newFolder("project");
        assertTrue(new File(outputRoot, "FLASH").mkdir());

        assertTrue(PreFlightChecks.confirmProceedOnOutputFolder(
                outputRoot.getAbsolutePath(), true));
    }

    @Test
    public void nonOutputFolderStillPassesThroughSingleArgMethod() throws Exception {
        File outputRoot = temp.newFolder("new-project");

        assertTrue(PreFlightChecks.confirmProceedOnOutputFolder(outputRoot.getAbsolutePath()));
    }

    @Test
    public void projectBootstrapFilesDoNotLookLikePreviousRunOutput() throws Exception {
        File outputRoot = temp.newFolder("project-bootstrap");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath());
        File settingsDir = layout.configurationWriteDir();
        assertTrue(settingsDir.mkdirs());
        assertTrue(new File(settingsDir, "project.json").createNewFile());

        File conditions = layout.projectSummaryWriteFile(FlashProjectLayout.CONDITIONS_FILENAME);
        assertTrue(conditions.getParentFile().mkdirs());
        assertTrue(conditions.createNewFile());
        File orientation = layout.projectSummaryWriteFile(FlashProjectLayout.ORIENTATION_MANIFEST_FILENAME);
        assertTrue(orientation.createNewFile());

        PreFlightChecks.OutputFolderResult result =
                PreFlightChecks.detectOutputFolder(outputRoot.getAbsolutePath());

        assertFalse(result.likelyOutput);
    }

    @Test
    public void realResultArtifactStillLooksLikePreviousRunOutput() throws Exception {
        File outputRoot = temp.newFolder("project-with-results");
        File objects = FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath())
                .projectSummaryWriteFile(FlashProjectLayout.MASTER_OBJECTS_FILENAME);
        assertTrue(objects.getParentFile().mkdirs());
        assertTrue(objects.createNewFile());

        PreFlightChecks.OutputFolderResult result =
                PreFlightChecks.detectOutputFolder(outputRoot.getAbsolutePath());

        assertTrue(result.likelyOutput);
    }
}
