package flash.pipeline.runrecord.ui;

import flash.pipeline.testutil.UiTestAssumptions;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;

public class RunsBrowserDialogSmokeTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void constructsWhenGraphicsAreAvailable() {
        UiTestAssumptions.assumeInteractiveUiTestsEnabled();

        RunsBrowserDialog dialog = new RunsBrowserDialog(null, temp.getRoot());
        try {
            assertEquals("FLASH Runs", dialog.getTitle());
        } finally {
            dialog.dispose();
        }
    }
}
