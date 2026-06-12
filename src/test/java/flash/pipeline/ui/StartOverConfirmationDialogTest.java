package flash.pipeline.ui;

import org.junit.Assume;
import org.junit.Test;

import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class StartOverConfirmationDialogTest {

    @Test
    public void headlessKeepsWorking() {
        Assume.assumeTrue(GraphicsEnvironment.isHeadless());

        assertEquals(StartOverConfirmationDialog.Choice.KEEP_WORKING,
                StartOverConfirmationDialog.show(null,
                        Arrays.asList("Done - Channel names", "Current - Quality check"),
                        123L));
    }

    @Test(timeout = 3000)
    public void escapeKeepsWorkingByDefault() {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());

        javax.swing.Timer closer = new javax.swing.Timer(100, e -> triggerEscBinding());
        closer.setRepeats(false);
        closer.start();

        StartOverConfirmationDialog.Choice choice = StartOverConfirmationDialog.show(null,
                Arrays.asList("Done - Channel names", "Current - Quality check"),
                123L);

        assertEquals(StartOverConfirmationDialog.Choice.KEEP_WORKING, choice);
    }

    @Test(timeout = 3000)
    public void startOverButtonConfirmsStartOver() {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());

        javax.swing.Timer closer = new javax.swing.Timer(100,
                e -> clickButton("Start Over Set Up Configuration?", "Start Over"));
        closer.setRepeats(false);
        closer.start();

        StartOverConfirmationDialog.Choice choice = StartOverConfirmationDialog.show(null,
                Arrays.asList("Done - Channel names", "Current - Quality check"),
                123L);

        assertEquals(StartOverConfirmationDialog.Choice.START_OVER, choice);
    }

    private static void triggerEscBinding() {
        for (Window window : Window.getWindows()) {
            if (window instanceof JDialog && window.isShowing()) {
                JDialog dialog = (JDialog) window;
                if ("Start Over Set Up Configuration?".equals(dialog.getTitle())) {
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

    private static void clickButton(String title, String text) {
        for (Window window : Window.getWindows()) {
            if (window instanceof JDialog && window.isShowing()) {
                JDialog dialog = (JDialog) window;
                if (title.equals(dialog.getTitle())) {
                    JButton button = findButton(dialog.getContentPane(), text);
                    if (button != null) {
                        button.doClick();
                        return;
                    }
                }
            }
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                clickButton(title, text);
            }
        });
    }

    private static JButton findButton(Container container, String text) {
        java.awt.Component[] components = container.getComponents();
        for (int i = 0; i < components.length; i++) {
            java.awt.Component component = components[i];
            if (component instanceof JButton && text.equals(((JButton) component).getText())) {
                return (JButton) component;
            }
            if (component instanceof Container) {
                JButton nested = findButton((Container) component, text);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }
}
