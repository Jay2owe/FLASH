package flash.pipeline.intelligence;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

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
}
