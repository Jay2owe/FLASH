package flash.pipeline.ui;

import org.junit.Assume;
import org.junit.Test;

import javax.swing.Action;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class CancelConfirmationDialogTest {

    @Test
    public void headlessReturnsDiscard() {
        Assume.assumeTrue(GraphicsEnvironment.isHeadless());

        assertEquals(CancelConfirmationDialog.Choice.DISCARD_AND_EXIT,
                CancelConfirmationDialog.show(null, "Step 4 of 6 - Z-slice QC",
                        Arrays.asList("\u2713 Channel name", "\u25d0 Threshold QC"),
                        "C:\\tmp\\draft.properties"));
    }

    @Test(timeout = 3000)
    public void keepWorkingByDefault() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());

        javax.swing.Timer closer = new javax.swing.Timer(100, e -> triggerEscBinding());
        closer.setRepeats(false);
        closer.start();

        CancelConfirmationDialog.Choice choice = CancelConfirmationDialog.show(null,
                "Step 4 of 6 - Z-slice QC",
                Arrays.asList("\u2713 Channel name", "\u25d0 Threshold QC", "\u25cb Z-slice QC"),
                "C:\\tmp\\draft.properties");

        assertEquals(CancelConfirmationDialog.Choice.KEEP_WORKING, choice);
    }

    private static void triggerEscBinding() {
        for (Window window : Window.getWindows()) {
            if (window instanceof JDialog && window.isShowing()) {
                JDialog dialog = (JDialog) window;
                if ("Cancel Set Up Configuration?".equals(dialog.getTitle())) {
                    Action action = dialog.getRootPane().getActionMap().get("keepWorking");
                    assertNotNull(action);
                    action.actionPerformed(new ActionEvent(dialog, ActionEvent.ACTION_PERFORMED, "esc"));
                    return;
                }
            }
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                triggerEscBinding();
            }
        });
    }
}
