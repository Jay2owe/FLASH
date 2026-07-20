package flash.pipeline.help;

import flash.pipeline.FLASH_Pipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * In-depth per-control manual for Spectral Decontamination (experimental). The
 * feature is a multi-stage wizard; this manual collects the controls across its
 * setup, conditions, correction-stack, and expert stages.
 */
public final class SpectralControlsHelp {

    private SpectralControlsHelp() {
    }

    static ControlHelpTopic topic() {
        List<ControlHelpTopic.Group> groups = new ArrayList<ControlHelpTopic.Group>();

        groups.add(g("Goal & Channel Roles (Setup)", null,
                c("Goal", "Card choice",
                        "What to produce: a cleaned image, a cleaned mask, object scores, or measure-only."),
                c("Target channel", "Dropdown",
                        "The channel to decontaminate."),
                c("Bleed-through Channels", "Toggles per channel",
                        "Channels that can leak signal into the target channel."),
                c("Autofluorescence Channels", "Toggles per channel",
                        "Channels contributing autofluorescence.",
                        "A channel may be both bleed-through and autofluorescence."),
                c("Excluded Channels", "Toggles per channel",
                        "Channels to ignore in the later decontamination steps.")));

        groups.add(g("Conditions",
                "How images map to experimental conditions, and which conditions are controls.",
                c("Condition source", "Card choice",
                        "Use an existing CSV, infer condition names from image names, or assign them manually."),
                c("Control Conditions", "Toggles per condition",
                        "Conditions expected to have little or no true target signal."),
                c("Experimental Conditions", "Toggles per condition",
                        "Conditions expected to contain target signal.")));

        groups.add(g("Correction Stack",
                "Build the ordered list of correction features applied to the target channel.",
                c("Expert mode", "Toggle",
                        "Reveals advanced features and allows more than one threshold-based mask feature."),
                c("Preset", "Dropdown",
                        "A correction-stack preset, or Custom to build your own ordered stack."),
                c("Ordered Features", "Dropdowns (Feature 1..N)",
                        "The ordered correction features.",
                        "Later slots only offer features that remain valid after the earlier ones.")));

        groups.add(g("Expert Settings (conditional)",
                "Shown only when the chosen stack contains the relevant feature.",
                c("Full Forward Model", "Numeric fields",
                        "Local window radius, quiet/bright percentiles, minimum fit pixels, and optional per-pixel coefficient maps."),
                c("Envelope Correction", "Numeric fields",
                        "Dominant-contaminant and envelope percentiles, envelope bin count, and minimum pixels per bin."),
                c("ROC Threshold Search", "Fields",
                        "Optimisation metric, allowed control false-positive rate, and the threshold-grid bounds and step.")));

        return new ControlHelpTopic(FLASH_Pipeline.IDX_SPECTRAL_DECONTAMINATION, "spectral-controls",
                "Spectral Decontamination (Experimental)", groups);
    }

    private static ControlHelpTopic.Group g(String heading, String intro, ControlHelpTopic.Control... controls) {
        return new ControlHelpTopic.Group(heading, intro, Arrays.asList(controls));
    }

    private static ControlHelpTopic.Control c(String label, String badge, String summary, String... details) {
        return new ControlHelpTopic.Control(label, badge, summary, Arrays.asList(details));
    }
}
