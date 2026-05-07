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
import javax.swing.WindowConstants;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;

/**
 * Modeless rotate/flip controls shown beside each ROI drawing image.
 */
public final class RoiOrientationPanel {
    private static final Color BG_COLOR = new Color(245, 245, 245);
    private static final Color BORDER_COLOR = new Color(180, 190, 196);
    private static final Color STATUS_COLOR = new Color(78, 93, 101);

    private final JDialog dialog;
    private final OrientationActionTarget target;
    private final JLabel statusLabel;

    public enum OrientationAction {
        ROTATE_LEFT,
        ROTATE_RIGHT,
        FLIP_HORIZONTAL,
        FLIP_VERTICAL,
        RESET
    }

    public interface OrientationActionTarget {
        OrientationTransformState getState();
        void setState(OrientationTransformState state);
        void redrawFromState();
        void clearUnsavedRoiAfterOrientationChange();
        String statusText();
    }

    public RoiOrientationPanel(Window owner, OrientationActionTarget target) {
        this.target = target;
        if (GraphicsEnvironment.isHeadless()) {
            this.dialog = null;
            this.statusLabel = null;
            return;
        }

        this.dialog = owner == null
                ? new JDialog((Frame) null, "ROI Orientation", false)
                : new JDialog(owner, "ROI Orientation", Dialog.ModalityType.MODELESS);
        this.dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        this.dialog.setAlwaysOnTop(false);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(BG_COLOR);
        body.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
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

        body.add(buttonRow);
        body.add(Box.createVerticalStrut(6));
        body.add(statusLabel);
        dialog.getContentPane().add(body);
    }

    public void showNear(ImagePlus image) {
        if (dialog == null) return;
        dialog.pack();
        dialog.setMinimumSize(new Dimension(520, dialog.getPreferredSize().height));
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

    public void close() {
        if (dialog != null) {
            dialog.dispose();
        }
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
        button.addActionListener(e -> {
            applyAction(target, action);
            refreshStatus();
        });
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

    private static boolean sameState(OrientationTransformState a,
                                     OrientationTransformState b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.rotateDegrees == b.rotateDegrees
                && a.flipHorizontal == b.flipHorizontal
                && a.flipVertical == b.flipVertical;
    }
}
