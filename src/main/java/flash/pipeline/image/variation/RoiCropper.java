package flash.pipeline.image.variation;

import ij.CompositeImage;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.plugin.Duplicator;

import java.awt.Rectangle;

/**
 * Crops a source image to an ROI while preserving stack dimensions and display
 * state needed for variant previews.
 */
public final class RoiCropper {

    private RoiCropper() {}

    public static ImagePlus cropToRoi(ImagePlus source, Roi roi) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        if (roi == null) {
            return source;
        }

        Rectangle bounds = clampToImage(roi.getBounds(),
                source.getWidth(), source.getHeight());
        if (bounds.width <= 0 || bounds.height <= 0) {
            throw new IllegalArgumentException(
                    "ROI bounds are empty for image " + source.getWidth()
                            + "x" + source.getHeight() + ": " + roi.getBounds());
        }

        Roi previousRoi = source.getRoi();
        Roi spatialRoi = new Roi(bounds);
        ImagePlus cropped;
        try {
            source.setRoi(spatialRoi);
            int channels = Math.max(1, source.getNChannels());
            int slices = Math.max(1, source.getNSlices());
            int frames = Math.max(1, source.getNFrames());
            cropped = new Duplicator().run(source,
                    1, channels,
                    1, slices,
                    1, frames);
        } finally {
            if (previousRoi != null) source.setRoi(previousRoi);
            else source.deleteRoi();
        }

        if (source.getCalibration() != null) {
            Calibration cal = source.getCalibration().copy();
            cropped.setCalibration(cal);
        }
        if (source.isHyperStack()) {
            cropped.setOpenAsHyperStack(true);
        }
        copyDisplayState(source, cropped);
        return cropped;
    }

    static Rectangle clampToImage(Rectangle in, int width, int height) {
        if (in == null) return new Rectangle(0, 0, width, height);
        int x = Math.max(0, in.x);
        int y = Math.max(0, in.y);
        int x2 = Math.min(width, in.x + in.width);
        int y2 = Math.min(height, in.y + in.height);
        return new Rectangle(x, y, Math.max(0, x2 - x), Math.max(0, y2 - y));
    }

    static void copyDisplayState(ImagePlus source, ImagePlus target) {
        if (source == null || target == null) return;
        if (source instanceof CompositeImage && target instanceof CompositeImage) {
            CompositeImage src = (CompositeImage) source;
            CompositeImage dst = (CompositeImage) target;
            int channels = Math.min(src.getNChannels(), dst.getNChannels());
            int srcC = src.getChannel();
            int dstC = dst.getChannel();
            try {
                for (int ch = 1; ch <= channels; ch++) {
                    src.setPositionWithoutUpdate(ch, src.getSlice(), src.getFrame());
                    dst.setPositionWithoutUpdate(ch, dst.getSlice(), dst.getFrame());
                    dst.setDisplayRange(src.getDisplayRangeMin(), src.getDisplayRangeMax());
                }
            } finally {
                src.setPositionWithoutUpdate(srcC, src.getSlice(), src.getFrame());
                dst.setPositionWithoutUpdate(dstC, dst.getSlice(), dst.getFrame());
            }
        } else {
            target.setDisplayRange(source.getDisplayRangeMin(), source.getDisplayRangeMax());
        }
    }
}
