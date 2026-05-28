package flash.pipeline.help;

import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.ToggleSwitch;

import java.awt.GraphicsEnvironment;
import java.io.File;

import javax.swing.JButton;
import java.awt.Window;

/**
 * Main help panel for first-run guidance and folder-aware advice.
 */
public final class HelpDialog {

    private static final String[] COMMON_PITFALLS = {
            "Set thresholds carefully — every downstream count depends on them.",
            "Each image needs exactly one ROI set with two ROIs.",
            "Slow on large stacks; only run if your microscope metadata is reliable.",
            "Auto-stretch is fine for figures; don't use it on data you'll quantify elsewhere.",
            "Pick Volumetric OR CPC, not both, unless you specifically want both columns.",
            "Re-runs from existing CSVs — won't re-segment images.",
            "Lines must be drawn before this runs; check Draw and Save ROIs first.",
            "Mean vs area-fraction give very different numbers — pick deliberately.",
            "Deprecated compatibility path; use 3D Object Analysis for nuclear counts.",
            "Re-run after any per-image analysis change.",
            "Auto picks the test from your data; override only if you know why.",
            "Pick a preset that matches your audience — Archive for the lab, Figure-ready for the paper.",
            "Placeholder — does nothing useful in this build."
    };

    private static final String[][] GLOSSARY = {
            {"Configuration folder", "per-experiment setup: channel names, thresholds, and particle sizes. Lives in FLASH/Config. Required by every analysis."},
            {"ROI (Region of Interest)", "a hand-drawn region used to restrict counting/intensity. Each image needs its own ROI set."},
            {"Colocalisation", "measuring how much two markers occupy the same space. FLASH supports volumetric overlap (% of object A inside object B) and CPC (centroid coincidence)."},
            {"Headless mode", "image windows are not shown during a run. Faster on big batches."},
            {"Parallel processing", "process multiple images on different CPU cores at the same time."},
            {"Animal ID / hemisphere / region", "parsed from the filename convention Experiment-AnimalID_Hemisphere_Region. Hemisphere must be LH or RH."}
    };

    private final String directory;
    private final String[] analyses;
    private final String[] descriptions;
    private final ToggleSwitch[] toggles;
    private final AnalysisAdvisor advisor;

    public HelpDialog(String directory, String[] analyses,
                      String[] descriptions, ToggleSwitch[] toggles) {
        this.directory = directory;
        this.analyses = analyses == null ? new String[0] : analyses;
        this.descriptions = descriptions == null ? new String[0] : descriptions;
        this.toggles = toggles;
        this.advisor = new AnalysisAdvisor();
    }

    public void open() {
        open(null);
    }

    public void open(Window owner) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        final PipelineDialog pd = owner == null
                ? new PipelineDialog("FLASH Help")
                : new PipelineDialog(owner, "FLASH Help");
        pd.setDefaultButtonsVisible(false);

        addQuickStart(pd);
        addAdvisor(pd);
        addModuleReference(pd);
        addGlossary(pd);

        JButton closeBtn = pd.addFooterButton("Close");
        closeBtn.addActionListener(e -> pd.closeWithAction("close"));
        pd.showDialog();
    }

    private void addQuickStart(PipelineDialog pd) {
        pd.addHeader("Quick start");
        pd.addMessage("1. Pick a directory. FLASH works on a folder of images (`.lif`, `.tif`, `.czi`, etc.) — not single files. Drop your acquisition folder anywhere accessible to ImageJ.");
        pd.addMessage("2. Set up channels once with Set Up Configuration. This tells FLASH what each colour channel represents, what intensity threshold counts as \"signal\", and how big a \"cell\" is in pixels. The result is saved into a Configuration folder and reused by every other analysis. You only do this once per experiment.");
        pd.addMessage("3. Tick what you want and click OK. FLASH runs each analysis in order, drops outputs into `FLASH/`, and aggregates everything into master CSV files plus an Excel workbook at the end. For a first run, click the Standard 3D + Intensity recipe to skip the picking.");
    }

    private void addAdvisor(final PipelineDialog pd) {
        pd.addHeader("What should I run?");
        final AdvisorResult result = advisor.recommend(directory == null ? null : new File(directory));
        pd.addSubHeader(result.getTitle());
        pd.addMessage(result.getParagraph());
        if (result.getSuggestedRecipe() != null) {
            pd.addHelpText("Recipe: " + result.getSuggestedRecipe());
        }

        if (result.getSuggestedAnalysisIndices().length > 0) {
            JButton tickBtn = pd.addButton("Tick recommended for me");
            tickBtn.addActionListener(e -> {
                int[] indices = result.getSuggestedAnalysisIndices();
                for (int i = 0; i < indices.length; i++) {
                    int idx = indices[i];
                    if (toggles != null && idx >= 0 && idx < toggles.length && toggles[idx] != null) {
                        toggles[idx].setSelected(true);
                    }
                }
                pd.closeWithAction("tick_recommended");
            });
        }
        pd.addHelpText("Or close this and choose manually.");
    }

    private void addModuleReference(PipelineDialog pd) {
        pd.addHeader("What does each module do?");
        int count = Math.min(analyses.length, descriptions.length);
        for (int i = 0; i < count; i++) {
            pd.addSubHeader(analyses[i]);
            pd.addMessage(descriptions[i]);
            if (i < COMMON_PITFALLS.length) {
                pd.addHelpText("Common pitfalls: " + COMMON_PITFALLS[i]);
            }
        }
    }

    private void addGlossary(PipelineDialog pd) {
        pd.addHeader("Glossary");
        for (int i = 0; i < GLOSSARY.length; i++) {
            pd.addSubHeader(GLOSSARY[i][0]);
            pd.addMessage(GLOSSARY[i][1]);
        }
    }
}
