package flash.pipeline.ui.wizard;

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

    @Test(timeout = 5000)
    public void startOverRequiresConfirmation() {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());

        javax.swing.Timer clickStartOver = new javax.swing.Timer(100,
                e -> clickButton("Resume previous Set Up Configuration?", "Start Over"));
        clickStartOver.setRepeats(false);
        clickStartOver.start();

        javax.swing.Timer keepWorking = new javax.swing.Timer(200,
                e -> triggerAction("Start Over Set Up Configuration?", "keepWorking"));
        keepWorking.setRepeats(false);
        keepWorking.start();

        javax.swing.Timer closeResume = new javax.swing.Timer(300,
                e -> triggerAction("Resume previous Set Up Configuration?", "cancelDialog"));
        closeResume.setRepeats(false);
        closeResume.start();

        ResumePromptDialog.Choice choice = ResumePromptDialog.show(
                null,
                Arrays.asList("Done - Channel names", "Current - Quality check"),
                123L);

        assertEquals(ResumePromptDialog.Choice.CANCEL, choice);
    }

    @Test(timeout = 5000)
    public void confirmedStartOverMapsToStartOver() {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());

        javax.swing.Timer clickStartOver = new javax.swing.Timer(100,
                e -> clickButton("Resume previous Set Up Configuration?", "Start Over"));
        clickStartOver.setRepeats(false);
        clickStartOver.start();

        javax.swing.Timer confirmStartOver = new javax.swing.Timer(200,
                e -> clickButton("Start Over Set Up Configuration?", "Start Over"));
        confirmStartOver.setRepeats(false);
        confirmStartOver.start();

        ResumePromptDialog.Choice choice = ResumePromptDialog.show(
                null,
                Arrays.asList("Done - Channel names", "Current - Quality check"),
                123L);

        assertEquals(ResumePromptDialog.Choice.START_OVER, choice);
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

    private static void triggerAction(String title, String actionKey) {
        for (Window window : Window.getWindows()) {
            if (window instanceof JDialog && window.isShowing()) {
                JDialog dialog = (JDialog) window;
                if (title.equals(dialog.getTitle())) {
                    Action action = dialog.getRootPane().getActionMap().get(actionKey);
                    assertNotNull(action);
                    action.actionPerformed(new ActionEvent(dialog, ActionEvent.ACTION_PERFORMED, actionKey));
                    return;
                }
            }
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                triggerAction(title, actionKey);
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
