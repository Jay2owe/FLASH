package flash.pipeline.roi;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;

/** Macro remove_non_ROI implemented in Java. */
public final class RoiOps {

    private RoiOps() {}

    public static void removeNonRoi(ImagePlus imp, RoiManager rm, int roiIndex, int croppedRoiIndex) {
        if (imp == null || rm == null) return;

        if (roiIndex >= 0 && roiIndex < rm.getCount()) {
            rm.select(imp, roiIndex);
            IJ.run(imp, "Crop", "");
        }

        if (croppedRoiIndex >= 0 && croppedRoiIndex < rm.getCount()) {
            rm.select(imp, croppedRoiIndex);
            IJ.setBackgroundColor(0, 0, 0);
            IJ.run(imp, "Clear Outside", "stack");
        }

        imp.deleteRoi();
        rm.deselect();
    }

    /**
     * RoiManager-free version: applies pre-loaded Roi objects directly.
     * Uses IJ.run — NOT safe for concurrent use on different images.
     */
    public static void removeNonRoi(ImagePlus imp, Roi cropRoi, Roi clearOutsideRoi) {
        if (imp == null) return;

        if (cropRoi != null) {
            imp.setRoi((Roi) cropRoi.clone());
            IJ.run(imp, "Crop", "");
        }

        if (clearOutsideRoi != null) {
            imp.setRoi((Roi) clearOutsideRoi.clone());
            IJ.setBackgroundColor(0, 0, 0);
            IJ.run(imp, "Clear Outside", "stack");
        }

        imp.deleteRoi();
    }

    /**
     * Thread-safe version: crops and clears outside using direct pixel
     * operations instead of IJ.run commands.  Safe for concurrent use
     * on different ImagePlus instances.
     */
    public static void removeNonRoiThreadSafe(ImagePlus imp, Roi cropRoi, Roi clearOutsideRoi) {
        if (imp == null) return;

        // Crop to bounding box of cropRoi (no Duplicator — it is NOT thread-safe)
        if (cropRoi != null) {
            java.awt.Rectangle bounds = cropRoi.getBounds();
            ij.ImageStack oldStack = imp.getStack();
            int nCh = imp.getNChannels();
            int nSl = imp.getNSlices();
            int nFr = imp.getNFrames();
            ij.ImageStack newStack = new ij.ImageStack(bounds.width, bounds.height);
            for (int s = 1; s <= oldStack.getSize(); s++) {
                ij.process.ImageProcessor ip = oldStack.getProcessor(s);
                java.awt.Rectangle oldRoi = ip.getRoi();
                try {
                    ip.setRoi(bounds);
                    newStack.addSlice(oldStack.getSliceLabel(s), ip.crop());
                } finally {
                    if (oldRoi != null) {
                        ip.setRoi(oldRoi);
                    } else {
                        ip.resetRoi();
                    }
                }
            }
            imp.setStack(newStack);
            imp.setDimensions(nCh, nSl, nFr);
            imp.deleteRoi();
        }

        // Clear outside the clearOutsideRoi shape
        if (clearOutsideRoi != null) {
            // The clearRoi coordinates are relative to the original image.
            // After cropping, we need to offset by the crop origin.
            Roi adjustedRoi = (Roi) clearOutsideRoi.clone();
            if (cropRoi != null) {
                java.awt.Rectangle cropBounds = cropRoi.getBounds();
                adjustedRoi.setLocation(
                        adjustedRoi.getBounds().x - cropBounds.x,
                        adjustedRoi.getBounds().y - cropBounds.y);
            }
            ij.ImageStack stack = imp.getStack();
            for (int s = 1; s <= stack.getSize(); s++) {
                ij.process.ImageProcessor ip = stack.getProcessor(s);
                java.awt.Rectangle oldRoi = ip.getRoi();
                try {
                    ip.setRoi(adjustedRoi);
                    ip.setColor(0);
                    ip.fillOutside(adjustedRoi);
                } finally {
                    if (oldRoi != null) {
                        ip.setRoi(oldRoi);
                    } else {
                        ip.resetRoi();
                    }
                }
            }
            imp.deleteRoi();
        }
    }
}
