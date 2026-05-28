package flash.pipeline.ui.wizard;

import flash.pipeline.ui.PipelineDialog;

import javax.swing.JButton;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public final class ResumePromptDialog {
    public enum Choice { RESUME, START_OVER, CANCEL }

    private static final String ACTION_START_OVER = "start_over";

    private ResumePromptDialog() {
    }

    public static Choice show(Window owner, List<String> progressLines, long lastUpdatedMillis) {
        if (GraphicsEnvironment.isHeadless()) {
            return Choice.CANCEL;
        }

        final PipelineDialog dialog = owner == null
                ? new PipelineDialog("Resume previous Set Up Configuration?", PipelineDialog.Phase.SETUP)
                : new PipelineDialog(owner, "Resume previous Set Up Configuration?", PipelineDialog.Phase.SETUP);
        dialog.setBreadcrumb(null, null);
        dialog.addHeader("Resume previous Set Up Configuration?");
        dialog.addMessage("You have unfinished work in this project:");
        if (progressLines != null) {
            for (String line : progressLines) {
                dialog.addMessage(line == null ? "" : line);
            }
        }
        dialog.addMessage("Last update: " + formatLastUpdate(lastUpdatedMillis));

        JButton startOver = dialog.addFooterButton("Start Over");
        startOver.addActionListener(e -> dialog.closeWithAction(ACTION_START_OVER));
        dialog.setPrimaryButtonText("Resume");
        dialog.focusPrimaryButtonOnShow();

        if (dialog.showDialog()) {
            return Choice.RESUME;
        }
        if (ACTION_START_OVER.equals(dialog.getActionCommand())) {
            return Choice.START_OVER;
        }
        return Choice.CANCEL;
    }

    private static String formatLastUpdate(long millis) {
        if (millis <= 0L) {
            return "unknown";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(millis));
    }
}
