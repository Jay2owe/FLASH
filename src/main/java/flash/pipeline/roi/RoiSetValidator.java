package flash.pipeline.roi;

import ij.IJ;
import ij.gui.GenericDialog;
import ij.plugin.frame.RoiManager;

/**
 * Strict validator for ROI Manager contents to preserve Jamie macro indexing assumptions:
 * for each image i, ROI[i*2] is uncropped and ROI[i*2+1] is cropped.
 */
public final class RoiSetValidator {

    private RoiSetValidator() {}

    public static void validateStrict(RoiManager rm, int startOffset, int nImages) {
        if (rm == null) throw new IllegalStateException("ROI Manager not available");
        int count = rm.getCount();
        int expectedNew = nImages * 2;

        int expectedTotal = startOffset + expectedNew;
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
                    .append("and select that zip (or copy it into the ROI Sets folder first).\n\n");
            }
            body.append("Fix the ROI Manager ordering and try again.");

            GenericDialog gd = new GenericDialog("ROI Validation Failed");
            gd.addMessage(body.toString());
            gd.showDialog();
            IJ.log("ROI Validation Failed: " + e.getMessage());
            if (partialZipPath != null && !partialZipPath.isEmpty()) {
                IJ.log("ROI Validation Failed: partial set saved to " + partialZipPath);
            }
            return false;
        }
    }
}
