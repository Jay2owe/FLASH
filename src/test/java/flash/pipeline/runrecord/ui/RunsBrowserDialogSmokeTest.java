package flash.pipeline.runrecord.ui;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.awt.GraphicsEnvironment;

import static org.junit.Assert.assertEquals;

public class RunsBrowserDialogSmokeTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void constructsWhenGraphicsAreAvailable() {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());

        RunsBrowserDialog dialog = new RunsBrowserDialog(null, temp.getRoot());
        try {
            assertEquals("FLASH Runs", dialog.getTitle());
        } finally {
            dialog.dispose();
        }
    }
}
