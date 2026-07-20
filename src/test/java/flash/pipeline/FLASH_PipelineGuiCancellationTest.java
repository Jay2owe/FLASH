package flash.pipeline;

import flash.pipeline.analyses.Analysis;
import flash.pipeline.execution.AnalysisCancellation;
import flash.pipeline.io.FlashProjectLayout;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertFalse;

public class FLASH_PipelineGuiCancellationTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void guiCancellationBeforeOutputsLeavesNoRunFiles() throws Exception {
        File project = temp.newFolder("cancelled-gui-run");
        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        Analysis cancelled = new Analysis() {
            @Override
            public void execute(String directory) {
                AnalysisCancellation.markDialogCancelRequested();
            }
        };

        boolean completed = pipeline.executeAnalysisSafelyForGui(
                cancelled, FLASH_Pipeline.IDX_INTENSITY, project.getAbsolutePath());

        FlashProjectLayout layout = FlashProjectLayout.forDirectory(project.getAbsolutePath());
        assertFalse(completed);
        assertFalse(layout.runRecordsRoot().exists());
        assertFalse(layout.statusWriteFile().exists());
        assertFalse(layout.startHereWriteFile().exists());
    }
}
