package flash.pipeline.ui.wizard;

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

public class ResumePromptDialogTest {
    @Test
    public void headlessGuardReturnsCancel() {
        Assume.assumeTrue(GraphicsEnvironment.isHeadless());

        assertEquals(ResumePromptDialog.Choice.CANCEL,
                ResumePromptDialog.show(null, Arrays.asList("Current - Quality check"), 123L));
    }

    @Test(timeout = 3000)
    public void escMapsToCancel() {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());

        javax.swing.Timer closer = new javax.swing.Timer(100, e -> triggerEscBinding());
        closer.setRepeats(false);
        closer.start();

        ResumePromptDialog.Choice choice = ResumePromptDialog.show(
                null,
                Arrays.asList("Done - Channel names", "Current - Quality check"),
                123L);

        assertEquals(ResumePromptDialog.Choice.CANCEL, choice);
    }

    private static void triggerEscBinding() {
        for (Window window : Window.getWindows()) {
            if (window instanceof JDialog && window.isShowing()) {
                JDialog dialog = (JDialog) window;
                if ("Resume previous Set Up Configuration?".equals(dialog.getTitle())) {
                    Action action = dialog.getRootPane().getActionMap().get("cancelDialog");
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
