package flash.pipeline.image;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the Puncta Resolve Filter macro safely.
 * <p>
 * The macro creates intermediate images (Duplicate, selectWindow, imageCalculator)
 * and ends with close(original) which destroys the input. We strip that line
 * and handle the result transfer ourselves.
 * <p>
 * Must be called under a lock (synchronized) since it uses WindowManager.
 */
public final class PunctaResolveFilter {

    private PunctaResolveFilter() {}

    /** Counter for generating unique window titles to prevent collisions in parallel mode. */
    private static final AtomicInteger EXEC_COUNTER = new AtomicInteger(0);

    /**
     * Applies the Puncta Resolve filter to the given image in-place.
     * <p>
     * MUST be called from a synchronized block — uses WindowManager globally.
     *
     * @param imp single-channel Z-stack (modified in-place to 8-bit result)
     * @param macroContent the macro source (from .ijm file or resource)
     */
    public static void apply(ImagePlus imp, String macroContent) {
        // Strip close(original) — the macro would destroy our input image.
        // We handle cleanup ourselves after grabbing the result.
        String safeMacro = macroContent
                .replaceAll("(?m)^\\s*close\\(original\\).*$", "// [stripped by pipeline]");

        // Use a unique title to prevent WindowManager collisions when multiple
        // threads run PunctaResolve on images with the same channel name.
        // The macro uses getTitle() at runtime, so it picks up our unique title
        // and all selectWindow() calls target the correct image.
        String originalTitle = imp.getTitle();
        String uniqueTitle = originalTitle + "__prf_" + EXEC_COUNTER.incrementAndGet();
        imp.setTitle(uniqueTitle);

        // Snapshot existing window IDs so we can clean up everything created by the macro
        Set<Integer> preExisting = new HashSet<Integer>();
        int[] preIds = WindowManager.getIDList();
        if (preIds != null) {
            for (int id : preIds) preExisting.add(id);
        }

        try {
            // Show and activate imp so the macro can find it
            imp.show();
            imp.setActivated();

            // Run the macro — it creates the result as the new active image
            IJ.runMacro(safeMacro);

            // The result of imageCalculator (after blur + min3D) is now the active image.
            // The original imp is still open (we stripped close(original)).
            ImagePlus result = WindowManager.getCurrentImage();
            if (result != null && result != imp) {
                // Deep-copy result pixels into imp
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

                // Close the result window
                result.changes = false;
                result.close();
                result.flush();
            }
        } finally {
            // Always clean up: close imp's window and any intermediates.
            // imp.hide() removes from WindowManager but preserves pixel data
            // (ImagePlus.close() does NOT call flush() — stack is preserved).
            imp.changes = false;
            if (imp.getWindow() != null) {
                imp.hide();
            }

            // Clean up ALL windows created during this macro execution
            int[] postIds = WindowManager.getIDList();
            if (postIds != null) {
                for (int id : postIds) {
                    if (preExisting.contains(id)) continue;
                    ImagePlus w = WindowManager.getImage(id);
                    if (w != null && w != imp) {
                        w.changes = false;
                        w.close();
                        w.flush();
                    }
                }
            }

            // Restore original title (caller expects the original name)
            imp.setTitle(originalTitle);
        }
    }

    /**
     * Returns true if the given macro content is the Puncta Resolve filter
     * (contains the distinctive dual-path Duplicate + imageCalculator pattern).
     */
    public static boolean matches(String macroContent) {
        if (macroContent == null) return false;
        return macroContent.contains("_density") && macroContent.contains("_edge")
                && macroContent.contains("imageCalculator");
    }
}
