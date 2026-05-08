package flash.pipeline.ui;

import flash.pipeline.orientation.OrientationTransformState;
import ij.ImagePlus;
import ij.gui.ImageWindow;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Modeless draw confirmation and rotate/flip controls shown beside each ROI
 * drawing image.
 */
public final class RoiOrientationPanel {
    private static final Color BG_COLOR = new Color(245, 245, 245);
    private static final Color BORDER_COLOR = new Color(180, 190, 196);
    private static final Color STATUS_COLOR = new Color(78, 93, 101);

    private final JDialog dialog;
    private final OrientationActionTarget target;
    private final JLabel statusLabel;
    private final Object resultLock = new Object();
    private DrawDialogResult result;
    private SecondaryLoop waitLoop;

    public enum OrientationAction {
        ROTATE_LEFT,
        ROTATE_RIGHT,
        FLIP_HORIZONTAL,
        FLIP_VERTICAL,
        RESET
    }

    public enum DrawDialogResult {
        CONFIRMED,
        CANCELLED
    }

    public interface OrientationActionTarget {
        OrientationTransformState getState();
        void setState(OrientationTransformState state);
        void redrawFromState();
        void clearUnsavedRoiAfterOrientationChange();
        String statusText();
    }

    public RoiOrientationPanel(Window owner, OrientationActionTarget target) {
        this(owner, target, "", "");
    }

    public RoiOrientationPanel(Window owner, OrientationActionTarget target,
                               String imageProgress, String imageTitle) {
        this.target = target;
        if (GraphicsEnvironment.isHeadless()) {
            this.dialog = null;
            this.statusLabel = null;
            return;
        }

        this.dialog = owner == null
                ? new JDialog((Frame) null, "Draw ROI and Orientation", false)
                : new JDialog(owner, "Draw ROI and Orientation",
                        Dialog.ModalityType.MODELESS);
        this.dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        this.dialog.setAlwaysOnTop(false);
        this.dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                finish(DrawDialogResult.CANCELLED);
            }
        });

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(BG_COLOR);
        body.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));

        JLabel instructionLabel = new JLabel(instructionHtml(imageProgress, imageTitle));
        instructionLabel.setAlignmentX(JLabel.LEFT_ALIGNMENT);

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttonRow.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        buttonRow.setOpaque(false);
        buttonRow.add(button("Rotate left", OrientationAction.ROTATE_LEFT));
        buttonRow.add(button("Rotate right", OrientationAction.ROTATE_RIGHT));
        buttonRow.add(button("Flip horizontal", OrientationAction.FLIP_HORIZONTAL));
        buttonRow.add(button("Flip vertical", OrientationAction.FLIP_VERTICAL));
        buttonRow.add(button("Reset", OrientationAction.RESET));

        statusLabel = new JLabel(statusText());
        statusLabel.setForeground(STATUS_COLOR);
        statusLabel.setFont(statusLabel.getFont().deriveFont(11f));
        statusLabel.setAlignmentX(JLabel.LEFT_ALIGNMENT);

        JButton cancelButton = new JButton("Cancel ROI run");
        cancelButton.setFocusPainted(false);
        cancelButton.addActionListener(e -> finish(DrawDialogResult.CANCELLED));

        JButton confirmButton = new JButton("Finish drawing");
        confirmButton.setFocusPainted(false);
        confirmButton.addActionListener(e -> finish(DrawDialogResult.CONFIRMED));

        JPanel confirmationRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        confirmationRow.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        confirmationRow.setOpaque(false);
        confirmationRow.add(cancelButton);
        confirmationRow.add(confirmButton);

        body.add(instructionLabel);
        body.add(Box.createVerticalStrut(8));
        body.add(buttonRow);
        body.add(Box.createVerticalStrut(6));
        body.add(statusLabel);
        body.add(Box.createVerticalStrut(10));
        body.add(confirmationRow);
        dialog.getContentPane().add(body);
        dialog.getRootPane().setDefaultButton(confirmButton);
    }

    public void showNear(ImagePlus image) {
        if (dialog == null) return;
        dialog.pack();
        dialog.setMinimumSize(new Dimension(560, dialog.getPreferredSize().height));
        ImageWindow window = image == null ? null : image.getWindow();
        if (window != null) {
            Rectangle bounds = window.getBounds();
            dialog.setLocation(bounds.x + bounds.width + 12, bounds.y);
        } else {
            dialog.setLocationByPlatform(true);
        }
        refreshStatus();
        dialog.setVisible(true);
        dialog.toFront();
    }

    public DrawDialogResult showNearAndWait(ImagePlus image) {
        if (dialog == null) return DrawDialogResult.CANCELLED;
        showNear(image);
        waitForResult();
        return resultOrCancelled();
    }

    public void close() {
        finish(DrawDialogResult.CANCELLED);
    }

    void performOrientationAction(OrientationAction action) {
        applyAction(target, action);
        refreshStatus();
    }

    static String instructionHtml(String imageProgress, String imageTitle) {
        String progress = safeTrim(imageProgress);
        String title = safeTrim(imageTitle);
        StringBuilder html = new StringBuilder("<html><body style='width:430px'>");
        if (!progress.isEmpty()) {
            html.append("<b>").append(escapeHtml(progress)).append("</b><br>");
        }
        if (!title.isEmpty()) {
            html.append("Draw ROI for: ")
                    .append(escapeHtml(title))
                    .append("<br><br>");
        }
        html.append("Draw or edit the ROI in ImageJ. Use orientation buttons ")
                .append("if needed, then click <b>Finish drawing</b> to lock ")
                .append("in this image's ROI.");
        html.append("</body></html>");
        return html.toString();
    }

    public static void applyAction(OrientationActionTarget target,
                                   OrientationAction action) {
        if (target == null || action == null) return;
        OrientationTransformState current = target.getState();
        if (current == null) current = OrientationTransformState.identity();
        OrientationTransformState next = stateAfter(current, action);
        if (sameState(current, next)) return;
        target.clearUnsavedRoiAfterOrientationChange();
        target.setState(next);
        target.redrawFromState();
    }

    public static OrientationTransformState stateAfter(OrientationTransformState current,
                                                       OrientationAction action) {
        OrientationTransformState state = current == null
                ? OrientationTransformState.identity()
                : current;
        if (action == null) return state;
        switch (action) {
            case ROTATE_LEFT:
                return state.rotateLeft();
            case ROTATE_RIGHT:
                return state.rotateRight();
            case FLIP_HORIZONTAL:
                return state.flipHorizontal();
            case FLIP_VERTICAL:
                return state.flipVertical();
            case RESET:
                return state.reset();
            default:
                return state;
        }
    }

    private JButton button(String label, final OrientationAction action) {
        JButton button = new JButton(label);
        button.setFocusPainted(false);
        button.addActionListener(e -> performOrientationAction(action));
        return button;
    }

    private void refreshStatus() {
        if (statusLabel != null) {
            statusLabel.setText(statusText());
        }
    }

    private String statusText() {
        String text = target == null ? "" : target.statusText();
        return text == null || text.trim().isEmpty() ? "Orientation ready" : text;
    }

    private void waitForResult() {
        synchronized (resultLock) {
            if (result != null) return;
        }

        if (SwingUtilities.isEventDispatchThread()) {
            SecondaryLoop loop =
                    Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
            synchronized (resultLock) {
                if (result != null) return;
                waitLoop = loop;
            }
            loop.enter();
            return;
        }

        synchronized (resultLock) {
            while (result == null) {
                try {
                    resultLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    finish(DrawDialogResult.CANCELLED);
                    return;
                }
            }
        }
    }

    private DrawDialogResult resultOrCancelled() {
        synchronized (resultLock) {
            return result == null ? DrawDialogResult.CANCELLED : result;
        }
    }

    private void finish(DrawDialogResult next) {
        DrawDialogResult safeNext = next == null
                ? DrawDialogResult.CANCELLED
                : next;
        SecondaryLoop loopToExit;
        synchronized (resultLock) {
            if (result != null) return;
            result = safeNext;
            loopToExit = waitLoop;
            resultLock.notifyAll();
        }
        disposeDialog();
        if (loopToExit != null) {
            loopToExit.exit();
        }
    }

    private void disposeDialog() {
        if (dialog == null) return;
        if (SwingUtilities.isEventDispatchThread()) {
            dialog.dispose();
        } else {
            SwingUtilities.invokeLater(dialog::dispose);
        }
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String escapeHtml(String value) {
        String text = value == null ? "" : value;
        StringBuilder escaped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '&':
                    escaped.append("&amp;");
                    break;
                case '<':
                    escaped.append("&lt;");
                    break;
                case '>':
                    escaped.append("&gt;");
                    break;
                case '"':
                    escaped.append("&quot;");
                    break;
                case '\'':
                    escaped.append("&#39;");
                    break;
                default:
                    escaped.append(ch);
                    break;
            }
        }
        return escaped.toString();
    }

    private static boolean sameState(OrientationTransformState a,
                                     OrientationTransformState b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.rotateDegrees == b.rotateDegrees
                && a.flipHorizontal == b.flipHorizontal
                && a.flipVertical == b.flipVertical;
    }
}
