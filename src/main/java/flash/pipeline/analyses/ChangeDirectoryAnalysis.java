package flash.pipeline.analyses;

import ij.IJ;
import ij.io.DirectoryChooser;

import java.awt.GraphicsEnvironment;

/**
 * Matches the macro's "Change Directory" step.
 *
 * NOTE: the main plugin currently also updates its Directory separately.
 * This class is kept minimal and just prompts + logs.
 */
public class ChangeDirectoryAnalysis implements Analysis {

    @Override
    public boolean requiresHeadedMode() {
        return true;
    }

    @Override
    public void execute(String directory) {
        if (GraphicsEnvironment.isHeadless()) {
            IJ.log("[ChangeDirectoryAnalysis] is interactive only and cannot run headless. Skipping.");
            return;
        }
        DirectoryChooser dc = new DirectoryChooser("Choose a new Directory");
        String newDir = dc.getDirectory();
        if (newDir != null) {
            IJ.showMessage("Directory changed to: " + newDir);
        } else {
            IJ.showMessage("Directory change cancelled");
        }
    }
}
