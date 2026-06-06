package flash.pipeline.bin;

import flash.pipeline.help.SetupHelpTopic;
import flash.pipeline.ui.PipelineDialog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Three-way chooser for missing channel configuration. */
public final class BinSetupChooser {
    public enum Choice { FULL, PARTIAL, BYPASS, CANCELLED }

    private BinSetupChooser() {}

    public static Choice show(String analysisDisplayName, Set<BinField> missing, boolean showRoiTip) {
        String analysis = cleanAnalysisName(analysisDisplayName);
        PipelineDialog dialog = new PipelineDialog("Pipeline configuration required");

        // Header carries the "?" helper explaining why configuration matters and
        // how the three choices differ.
        dialog.addSetupHelpHeader("Pipeline configuration required", helpTopic(analysis));
        dialog.addMessage(analysis + " needs channel settings for this folder before it can run. "
                + "Pick how to set them up, then press OK.");

        BinSetupChoicePanel choices = new BinSetupChoicePanel(analysis);
        dialog.addComponent(choices);

        dialog.addHelpText("Whatever you choose is saved to the Configuration folder and reused "
                + "automatically next time you run an analysis on this folder.");

        if (showRoiTip) {
            dialog.addHelpText(analysis + " can be limited to regions you draw on each image, "
                    + "such as a single hemisphere. No regions have been saved for this folder yet "
                    + "— run Draw and Save ROIs first if you want that.");
        }

        dialog.setDefaultButtonsVisible(true);
        dialog.focusPrimaryButtonOnShow();

        boolean confirmed = dialog.showDialog();
        return confirmed ? choices.getSelectedChoice() : Choice.CANCELLED;
    }

    private static SetupHelpTopic helpTopic(String analysis) {
        String summary = "FLASH measures each fluorescence channel using a small set of settings: "
                + "which marker the channel shows, how it is displayed, the threshold that separates "
                + "signal from background, and how objects are segmented. These are saved once per "
                + "folder and reused by every analysis, so consistent settings give consistent, "
                + "reproducible measurements across your whole dataset. Until they are set, an "
                + "analysis has nothing to measure against, which is why you are being asked now.";

        List<SetupHelpTopic.Section> sections = new ArrayList<SetupHelpTopic.Section>();
        sections.add(new SetupHelpTopic.Section("Full setup (recommended)", Arrays.asList(
                "Walks through every channel parameter: marker, colour, display range, detection "
                        + "threshold, particle size, segmentation method and filters.",
                "Every choice is made against a live preview of your own images, so you see the "
                        + "effect before committing.",
                "Saved once and reused by every analysis on this folder, so you are not asked again.",
                "Best for a new folder or the first time you analyse this dataset.")));
        sections.add(new SetupHelpTopic.Section("Partial setup", Arrays.asList(
                "Runs only the steps " + analysis + " actually needs and skips the rest.",
                "Same live previews and validation as the full setup, just fewer screens.",
                "Other analyses will prompt for their own parameters when you run them later.",
                "Best when you only want to run this one analysis right now.")));
        sections.add(new SetupHelpTopic.Section("Manual entry", Arrays.asList(
                "Opens a plain form with FLASH's default values pre-filled for the parameters this "
                        + "analysis needs.",
                "No image previews and no validation — you are trusted to enter sensible values.",
                "Best for reproducing a previous run or copying known-good settings between similar "
                        + "datasets.",
                "You can run the full setup later to verify the values visually.")));

        return new SetupHelpTopic("bin-setup-config", "Channel configuration", summary, sections);
    }

    private static String cleanAnalysisName(String analysisDisplayName) {
        if (analysisDisplayName == null || analysisDisplayName.trim().isEmpty()) {
            return "this analysis";
        }
        return analysisDisplayName.trim();
    }
}
