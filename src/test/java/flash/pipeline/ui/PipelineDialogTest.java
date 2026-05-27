package flash.pipeline.ui;

import flash.pipeline.testutil.UiTestAssumptions;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PipelineDialogTest {

    @Test(timeout = 3000)
    public void modelessShowDialogOnEventThreadDoesNotBlockChildDialogEvents() throws Exception {
        UiTestAssumptions.assumeInteractiveUiTestsEnabled();
        final AtomicBoolean closeEventRan = new AtomicBoolean(false);
        final AtomicBoolean showDialogReturned = new AtomicBoolean(false);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                final PipelineDialog dialog = new PipelineDialog("Modeless Regression Guard");
                dialog.setModal(false);
                dialog.addMessage("This dialog must not block the Swing event queue while open.");

                javax.swing.Timer closer = new javax.swing.Timer(50, e -> {
                    closeEventRan.set(true);
                    dialog.closeWithAction("auto_close");
                });
                closer.setRepeats(false);
                closer.start();

                boolean accepted = dialog.showDialog();

                showDialogReturned.set(true);
                assertFalse(accepted);
                assertEquals("auto_close", dialog.getActionCommand());
            }
        });

        assertTrue(closeEventRan.get());
        assertTrue(showDialogReturned.get());
    }
}
