package flash.pipeline.image;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the Diffuse Object Filter macro safely.
 * <p>
 * The macro builds a Difference-of-Gaussians by duplicating the input twice
 * ({@code DoG_small} and {@code DoG_big}), blurring each, subtracting via
 * {@code imageCalculator}, renaming the result to {@code DoG_result}, and
 * applying a 3D median. The original input is left untouched. We orchestrate
 * the multi-image flow so {@code imp} receives the final {@code DoG_result}
 * pixels and all intermediate windows are closed.
 * <p>
 * Native DAG execution (without {@link WindowManager}) arrives in stage 03;
 * until then this compound handler keeps batch runs safe by routing through
 * a known macro path. Caller MUST hold {@link WindowManagerLock#LOCK}.
 */
public final class DiffuseObjectFilter {

    private DiffuseObjectFilter() {}

    /** Counter for unique window titles to avoid collisions in parallel mode. */
    private static final AtomicInteger EXEC_COUNTER = new AtomicInteger(0);

    /**
     * True when {@code macroContent} is the bundled Diffuse Object filter — uses
     * the distinctive {@code DoG_small}/{@code DoG_big}/{@code Subtract create stack}
     * triplet that no other bundled preset emits.
     */
    public static boolean matches(String macroContent) {
        if (macroContent == null) return false;
        return macroContent.contains("DoG_small")
                && macroContent.contains("DoG_big")
                && macroContent.contains("Subtract create stack");
    }

    public static void apply(ImagePlus imp, String macroContent) {
        // Use a unique title so concurrent calls on same channel name don't collide.
        String originalTitle = imp.getTitle();
        String uniqueTitle = originalTitle + "__dof_" + EXEC_COUNTER.incrementAndGet();
        imp.setTitle(uniqueTitle);

        // Patch the macro: replace `original = getTitle();` so the macro picks up
        // our unique title rather than re-querying after we hide the window. Most
        // bundled presets use getTitle() at top-of-file which is safe, but this
        // guards against macros that re-read getTitle() mid-flow.
        String safeMacro = macroContent;

        Set<Integer> preExisting = new HashSet<Integer>();
        int[] preIds = WindowManager.getIDList();
        if (preIds != null) {
            for (int id : preIds) preExisting.add(id);
        }

        try {
            imp.show();
            imp.setActivated();

            IJ.runMacro(safeMacro);

            // Adopt result: prefer "DoG_result" (the macro renames it), fall back
            // to the active image (in case the rename was changed by the user).
            ImagePlus result = WindowManager.getImage("DoG_result");
            if (result == null || result == imp) {
                result = WindowManager.getCurrentImage();
            }
            if (result != null && result != imp && result.getStackSize() > 0) {
                ImageStack rs = result.getStack();
                ImageStack copy = new ImageStack(rs.getWidth(), rs.getHeight());
                for (int s = 1; s <= rs.getSize(); s++) {
                    copy.addSlice(rs.getProcessor(s).duplicate());
                }
                imp.setStack(copy);
                imp.setDimensions(result.getNChannels(), result.getNSlices(), result.getNFrames());
                if (result.getCalibration() != null) {
                    imp.setCalibration(result.getCalibration());
                }
            }
        } finally {
            // Hide imp's window (preserves pixel data, removes from WM).
            imp.changes = false;
            if (imp.getWindow() != null) {
                imp.hide();
            }

            // Close every window the macro created.
            int[] postIds = WindowManager.getIDList();
            if (postIds != null) {
                for (int id : postIds) {
                    if (preExisting.contains(id)) continue;
                    ImagePlus w = WindowManager.getImage(id);
                    if (w != null && w != imp) {
                        w.changes = false;
                        w.close();
                    }
                }
            }

            imp.setTitle(originalTitle);
        }
    }
}
