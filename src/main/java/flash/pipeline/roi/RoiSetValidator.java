package flash.pipeline.roi;

import ij.IJ;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import flash.pipeline.ui.PipelineDialog;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Validator for ROI Manager contents.
 *
 * <p>Legacy positional mode ({@link #validateStrict}) preserves the historical indexing
 * assumption: for each image i, ROI[i*2] is uncropped and ROI[i*2+1] is cropped, with
 * every zip covering every image.
 *
 * <p>Identity mode ({@link #validateStructural}) backs the region-scoped format where a
 * zip covers any subset of images and each image's pair is bound by
 * {@link RoiSetImageBinding} token (drawn + {@code _Cropped}), in any order. The two modes
 * coexist while callers migrate; positional methods are removed once all consumers use the
 * token-based binding.
 */
public final class RoiSetValidator {

    private RoiSetValidator() {}

    public static void validateStrict(RoiManager rm, int startOffset, int nImages) {
        if (rm == null) throw new IllegalStateException("ROI Manager not available");
        if (startOffset < 0) {
            throw new IllegalArgumentException("startOffset must be >= 0");
        }
        if (nImages < 0) {
            throw new IllegalArgumentException("nImages must be >= 0");
        }
        int count = rm.getCount();
        long expectedNewLong = (long) nImages * 2L;
        long expectedTotalLong = (long) startOffset + expectedNewLong;
        if (expectedTotalLong > Integer.MAX_VALUE) {
            throw new IllegalStateException("ROI Manager expected ROI count is too large: "
                    + expectedTotalLong);
        }

        int expectedNew = (int) expectedNewLong;
        int expectedTotal = (int) expectedTotalLong;
        if (count != expectedTotal) {
            throw new IllegalStateException("ROI Manager has unexpected ROI count. Expected exactly " + expectedTotal + " (" + expectedNew + " new) but found " + count + ".\n" +
                    "Strict mode requires exactly 2 ROIs per image (uncropped + cropped). Do not add extra ROIs.");
        }

        // Validate pair ordering and names
        for (int i = 0; i < nImages; i++) {
            int uncroppedIndex = startOffset + (i * 2);
            int croppedIndex = startOffset + (i * 2) + 1;

            String uncroppedName = rm.getName(uncroppedIndex);
            String croppedName = rm.getName(croppedIndex);

            if (uncroppedName != null && uncroppedName.endsWith("_Cropped")) {
                throw new IllegalStateException("ROI ordering error: ROI " + uncroppedIndex + " should be uncropped but ends with _Cropped");
            }
            if (croppedName == null || !croppedName.endsWith("_Cropped")) {
                throw new IllegalStateException("ROI ordering error: ROI " + croppedIndex + " should be cropped and end with _Cropped");
            }
        }
    }


    public static boolean validateStrictWithDialog(RoiManager rm, int startOffset, int nImages,
                                                   String partialZipPath) {
        try {
            validateStrict(rm, startOffset, nImages);
            return true;
        } catch (Exception e) {
            StringBuilder body = new StringBuilder();
            body.append("ROI set is not in the required order for downstream analyses:\n")
                .append("For each image i, ROI[i*2] must be uncropped and ROI[i*2+1] must be cropped (_Cropped).\n\n")
                .append("Error: ").append(e.getMessage()).append("\n\n");
            if (partialZipPath != null && !partialZipPath.isEmpty()) {
                body.append("Your in-progress ROI set has been saved to:\n  ")
                    .append(partialZipPath).append("\n\n")
                    .append("To recover: in a new Draw ROIs run, choose 'Append to existing'\n")
                    .append("and select that zip (or copy it into the ROI output folder first).\n\n");
            }
            body.append("Fix the ROI Manager ordering and try again.");

            PipelineDialog dialog = new PipelineDialog("ROI Validation Failed");
            dialog.addMessage(body.toString().replace("\n", "<br>"));
            dialog.showDialog();
            IJ.log("ROI Validation Failed: " + e.getMessage());
            if (partialZipPath != null && !partialZipPath.isEmpty()) {
                IJ.log("ROI Validation Failed: partial set saved to " + partialZipPath);
            }
            return false;
        }
    }

    // ---- Identity-based structural validation (region-scoped format) ----------------

    /**
     * Structural validation for the identity-based ROI format: each binding token has
     * exactly one drawn and one cropped ROI, with no duplicate tokens or orphans. Coverage
     * may be any non-empty subset of the project's images.
     */
    public static void validateStructural(RoiManager rm) {
        if (rm == null) throw new IllegalStateException("ROI Manager not available");
        validateStructural(Arrays.asList(rm.getRoisAsArray()));
    }

    /** Structural validation over an explicit ROI list (testable without a RoiManager). */
    public static void validateStructural(List<Roi> rois) {
        if (rois == null || rois.isEmpty()) {
            throw new IllegalStateException("ROI set is empty.");
        }
        Map<String, RoiSetImageBinding.RoiPair> byToken = RoiSetImageBinding.indexByToken(rois);
        if (byToken.isEmpty()) {
            throw new IllegalStateException(
                    "ROI set has no recognisable image-bound ROIs (names missing binding tokens).");
        }
        for (Map.Entry<String, RoiSetImageBinding.RoiPair> e : byToken.entrySet()) {
            RoiSetImageBinding.RoiPair pair = e.getValue();
            if (pair.drawn == null) {
                throw new IllegalStateException(
                        "ROI token " + e.getKey() + " is missing its drawn ROI.");
            }
            if (pair.cropped == null) {
                throw new IllegalStateException(
                        "ROI token " + e.getKey() + " is missing its cropped (_Cropped) ROI.");
            }
        }
        // indexByToken drops duplicate/unnamed ROIs; require exactly 2 per token so strays
        // (duplicate or unbound ROIs) are rejected rather than silently ignored.
        if (rois.size() != byToken.size() * 2) {
            throw new IllegalStateException("ROI set has " + rois.size() + " ROIs but "
                    + byToken.size() + " image tokens; expected exactly 2 per token (drawn + "
                    + "cropped). Duplicate or unbound ROIs are present.");
        }
    }

    /** Dialog wrapper mirroring {@link #validateStrictWithDialog} for the identity format. */
    public static boolean validateStructuralWithDialog(RoiManager rm, String partialZipPath) {
        try {
            validateStructural(rm);
            return true;
        } catch (Exception e) {
            StringBuilder body = new StringBuilder();
            body.append("ROI set is not valid for downstream analyses:\n")
                .append("Each image must have exactly one drawn ROI and one cropped (_Cropped) ROI.\n\n")
                .append("Error: ").append(e.getMessage()).append("\n\n");
            if (partialZipPath != null && !partialZipPath.isEmpty()) {
                body.append("Your in-progress ROI set has been saved to:\n  ")
                    .append(partialZipPath).append("\n\n")
                    .append("To recover: in a new Draw ROIs run, choose 'Append to existing'\n")
                    .append("and select that zip (or copy it into the ROI output folder first).\n\n");
            }
            body.append("Fix the ROI set and try again.");

            PipelineDialog dialog = new PipelineDialog("ROI Validation Failed");
            dialog.addMessage(body.toString().replace("\n", "<br>"));
            dialog.showDialog();
            IJ.log("ROI Validation Failed: " + e.getMessage());
            if (partialZipPath != null && !partialZipPath.isEmpty()) {
                IJ.log("ROI Validation Failed: partial set saved to " + partialZipPath);
            }
            return false;
        }
    }
}
