package flash.pipeline.ui;

import org.junit.Assume;
import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JDialog;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowEvent;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PipelineDialogCancelHookTest {

    @Test
    public void cancelHookCalledOnCancelClick() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());

        PipelineDialog dialog = new PipelineDialog("Cancel Hook");
        final AtomicBoolean called = new AtomicBoolean(false);
        dialog.setCancelConfirmation(() -> {
            called.set(true);
            return true;
        });
        backingDialog(dialog).pack();

        button(dialog, "cancelButton").doClick();

        assertTrue(called.get());
        assertFalse(backingDialog(dialog).isDisplayable());
    }

    @Test
    public void hookReturningFalseSuppressesCancel() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());

        PipelineDialog dialog = new PipelineDialog("Cancel Hook");
        dialog.setCancelConfirmation(() -> false);
        JDialog backing = backingDialog(dialog);
        backing.pack();

        button(dialog, "cancelButton").doClick();

        assertTrue(backing.isDisplayable());
        backing.dispose();
    }

    @Test
    public void hookReturningFalseSuppressesWindowClose() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());

        PipelineDialog dialog = new PipelineDialog("Window Close Hook");
        final AtomicBoolean called = new AtomicBoolean(false);
        dialog.setCancelConfirmation(() -> {
            called.set(true);
            return false;
        });
        JDialog backing = backingDialog(dialog);
        backing.pack();

        backing.dispatchEvent(new WindowEvent(backing, WindowEvent.WINDOW_CLOSING));

        assertTrue(called.get());
        assertTrue(backing.isDisplayable());
        backing.dispose();
    }

    private static JDialog backingDialog(PipelineDialog dialog) throws Exception {
        Field field = PipelineDialog.class.getDeclaredField("dialog");
        field.setAccessible(true);
        return (JDialog) field.get(dialog);
    }

    private static JButton button(PipelineDialog dialog, String fieldName) throws Exception {
        Field field = PipelineDialog.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (JButton) field.get(dialog);
    }
}
