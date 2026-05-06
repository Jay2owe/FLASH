package flash.pipeline.bin;

import flash.pipeline.ui.PipelineDialog;

import javax.swing.JButton;
import java.util.Set;

/** Three-way chooser for missing channel configuration. */
public final class BinSetupChooser {
    public enum Choice { FULL, PARTIAL, BYPASS, CANCELLED }

    private BinSetupChooser() {}

    public static Choice show(String analysisDisplayName, Set<BinField> missing, boolean showRoiTip) {
        String analysis = cleanAnalysisName(analysisDisplayName);
        PipelineDialog dialog = new PipelineDialog("Pipeline configuration required");
        dialog.setDefaultButtonsVisible(false);

        dialog.addHeader("Pipeline configuration required");
        dialog.addMessage("Running " + analysis + " requires channel parameters that have "
                + "not yet been configured for this folder. Choose how you would like "
                + "to set them up:");

        addChoiceButton(dialog,
                "Run full configuration setup (recommended)",
                "Launches the complete configuration workflow. Every parameter is "
                        + "set with live preview against your own images, so you can verify "
                        + "each choice before committing. Produces a complete, reusable "
                        + "configuration that all subsequent analyses on this folder will "
                        + "reuse without prompting.",
                "full");

        addChoiceButton(dialog,
                "Run partial configuration setup for this analysis only",
                "Launches only the configuration steps that " + analysis + " needs, "
                        + "skipping the rest. Same live image previews and validation as the "
                        + "full setup, with fewer steps. Other analyses will prompt for their "
                        + "own parameters when you run them later.",
                "partial");

        addChoiceButton(dialog,
                "Enter parameters directly without previews (expert-only)",
                "Opens a plain input dialog with FLASH's defaults pre-filled for "
                        + "the parameters this analysis needs. No image previews, no "
                        + "validation. Recommended only when reproducing a previous run or "
                        + "transferring known-good settings between similar datasets.",
                "bypass");

        dialog.addMessage("Tip: parameters set here are saved to the Configuration folder and "
                + "reused automatically the next time you run an analysis on this folder.");

        if (showRoiTip) {
            dialog.addMessage("Tip: " + analysis + " can be restricted to regions of interest (ROIs) "
                    + "that you draw on each image — for example, a single hemisphere or "
                    + "anatomical subregion — to limit the measurement to those areas. No "
                    + "ROIs have been saved for this folder yet. Consider running "
                    + "Draw and Save ROIs first if you want this analysis to operate on "
                    + "specific regions rather than whole images.");
        }

        JButton cancel = dialog.addRightFooterButton("Cancel");
        cancel.addActionListener(e -> dialog.closeWithAction("cancel"));

        dialog.showDialog();
        String action = dialog.getActionCommand();
        if ("full".equals(action)) return Choice.FULL;
        if ("partial".equals(action)) return Choice.PARTIAL;
        if ("bypass".equals(action)) return Choice.BYPASS;
        return Choice.CANCELLED;
    }

    private static void addChoiceButton(PipelineDialog dialog, String buttonText,
                                        String helpText, final String action) {
        JButton button = dialog.addButton(buttonText);
        button.addActionListener(e -> dialog.closeWithAction(action));
        dialog.addHelpText(helpText);
    }

    private static String cleanAnalysisName(String analysisDisplayName) {
        if (analysisDisplayName == null || analysisDisplayName.trim().isEmpty()) {
            return "this analysis";
        }
        return analysisDisplayName.trim();
    }
}
