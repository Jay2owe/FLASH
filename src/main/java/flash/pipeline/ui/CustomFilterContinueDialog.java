package flash.pipeline.ui;

import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;

/**
 * Pre-step picker shown when a channel already has a custom filter macro.
 * Routes to "continue building" (open the visual builder pre-loaded),
 * "adjust parameters" (tweak numeric values without changing structure),
 * or "start over" (fall through to the existing three-tile entry chooser).
 *
 * <p>Per design feedback this dialog never shows the existing macro content.
 */
public final class CustomFilterContinueDialog {

    private CustomFilterContinueDialog() {}

    public enum Action { CONTINUE_BUILD, ADJUST_PARAMS, START_OVER, CANCEL }

    public static Action show(String channelLabel) {
        if (GraphicsEnvironment.isHeadless()) return Action.CANCEL;

        final PipelineDialog dialog = new PipelineDialog("Custom filter — " + safe(channelLabel));
        dialog.setDefaultButtonsVisible(false);
        dialog.addHeader("This channel already has a custom filter. What do you want to do?");

        JPanel tiles = new JPanel(new GridLayout(1, 3, 10, 0));
        tiles.setOpaque(false);
        final JPanel firstTile = CustomFilterEntryDialog.makeTile("Continue building",
                "Open the visual builder with the current filter loaded. " +
                        "Add, remove, or rearrange steps.",
                dialog, "continue", true);
        tiles.add(firstTile);
        tiles.add(CustomFilterEntryDialog.makeTile("Adjust parameters",
                "Tweak the numbers on the existing steps without changing what they are.",
                dialog, "adjust", false));
        tiles.add(CustomFilterEntryDialog.makeTile("Start over",
                "Throw the current filter away and pick a fresh starting point.",
                dialog, "restart", false));
        dialog.addComponent(tiles);

        JButton help = dialog.addFooterButton("?");
        help.setToolTipText("What do these options do?");
        help.addActionListener(e -> showContinueHelp());
        JButton cancel = dialog.addRightFooterButton("Cancel");
        cancel.addActionListener(e -> dialog.closeWithAction("cancel"));
        dialog.requestFocusOnShow(firstTile);
        dialog.showDialog();

        String cmd = dialog.getActionCommand();
        if ("continue".equals(cmd)) return Action.CONTINUE_BUILD;
        if ("adjust".equals(cmd))   return Action.ADJUST_PARAMS;
        if ("restart".equals(cmd))  return Action.START_OVER;
        return Action.CANCEL;
    }

    private static void showContinueHelp() {
        String msg = "<html><body style='width:340px;'>"
                + "This channel already has a custom filter saved. Pick what you want to do with it."
                + "<br><br>"
                + "<b>Continue building</b><br>"
                + "Opens the visual builder with the existing filter pre-loaded "
                + "so you can add, remove, or rearrange steps."
                + "<br><br>"
                + "<b>Adjust parameters</b><br>"
                + "Tweak the numeric values on the existing steps "
                + "(e.g. blur radius, threshold) without changing what the steps do."
                + "<br><br>"
                + "<b>Start over</b><br>"
                + "Throw the current filter away and pick a fresh authoring tool "
                + "(builder, recorder, or import)."
                + "<br><br>"
                + "<b>Cancel</b><br>"
                + "Close this dialog and keep the existing filter as-is."
                + "</body></html>";
        JOptionPane.showMessageDialog(null, msg, "Custom filter — Help",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
