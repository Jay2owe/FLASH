package flash.pipeline.ui.variations;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;

import java.awt.Rectangle;
import java.util.LinkedHashMap;

public final class CropSpec {

    public enum Mode {
        FULL,
        CENTRE_256,
        CUSTOM
    }

    private static final int CENTRE_SIZE = 256;

    private final Mode mode;
    private final Rectangle bounds;

    private CropSpec(Mode mode, Rectangle bounds) {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        this.mode = mode;
        this.bounds = bounds == null ? null : new Rectangle(bounds);
        if (mode == Mode.CUSTOM) {
            if (this.bounds == null || this.bounds.width <= 0 || this.bounds.height <= 0) {
                throw new IllegalArgumentException("custom crop bounds must be positive");
            }
        }
    }

    public static CropSpec full() {
        return new CropSpec(Mode.FULL, null);
    }

    public static CropSpec centre256() {
        return new CropSpec(Mode.CENTRE_256, null);
    }

    public static CropSpec custom(Rectangle bounds) {
        return new CropSpec(Mode.CUSTOM, bounds);
    }

    public Mode mode() {
        return mode;
    }

    public Mode getMode() {
        return mode;
    }

    public Rectangle bounds() {
        return bounds == null ? null : new Rectangle(bounds);
    }

    public Rectangle getBounds() {
        return bounds();
    }

    public ImagePlus apply(ImagePlus source) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        if (mode == Mode.FULL) {
            return source;
        }
        Rectangle resolved = boundsFor(source);
        ImageStack input = source.getStack();
        ImageStack output = new ImageStack(resolved.width, resolved.height);
        for (int i = 1; i <= input.getSize(); i++) {
            ImageProcessor processor = input.getProcessor(i).duplicate();
            processor.setRoi(resolved.x, resolved.y, resolved.width, resolved.height);
            output.addSlice(input.getSliceLabel(i), processor.crop());
        }
        ImagePlus cropped = new ImagePlus(source.getTitle(), output);
        if (source.getCalibration() != null) {
            cropped.setCalibration(source.getCalibration().copy());
        }
        cropped.setDimensions(1, output.getSize(), 1);
        cropped.setOpenAsHyperStack(output.getSize() > 1);
        return cropped;
    }

    /**
     * Crops every plane like {@link #apply(ImagePlus)} but preserves the source's channel /
     * slice / frame dimensions, so a multi-channel hyperstack stays a multi-channel hyperstack
     * with the original channel indices intact. Required for spectral decontamination, whose
     * features address contaminant channels by index off a single hyperstack. Uses
     * {@link ImageProcessor#crop()} per plane ({@code ij.plugin.Duplicator} is not thread-safe).
     */
    public ImagePlus applyMultiChannel(ImagePlus source) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        if (mode == Mode.FULL) {
            return source;
        }
        Rectangle resolved = boundsFor(source);
        ImageStack input = source.getStack();
        ImageStack output = new ImageStack(resolved.width, resolved.height);
        for (int i = 1; i <= input.getSize(); i++) {
            ImageProcessor processor = input.getProcessor(i).duplicate();
            processor.setRoi(resolved.x, resolved.y, resolved.width, resolved.height);
            output.addSlice(input.getSliceLabel(i), processor.crop());
        }
        ImagePlus cropped = new ImagePlus(source.getTitle(), output);
        if (source.getCalibration() != null) {
            cropped.setCalibration(source.getCalibration().copy());
        }
        cropped.setDimensions(source.getNChannels(), source.getNSlices(), source.getNFrames());
        cropped.setOpenAsHyperStack(output.getSize() > 1);
        return cropped;
    }

    public Rectangle boundsFor(ImagePlus source) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        Rectangle imageBounds = new Rectangle(0, 0, source.getWidth(), source.getHeight());
        Rectangle requested;
        if (mode == Mode.FULL) {
            requested = imageBounds;
        } else if (mode == Mode.CENTRE_256) {
            int width = Math.min(CENTRE_SIZE, source.getWidth());
            int height = Math.min(CENTRE_SIZE, source.getHeight());
            int x = Math.max(0, (source.getWidth() - width) / 2);
            int y = Math.max(0, (source.getHeight() - height) / 2);
            requested = new Rectangle(x, y, width, height);
        } else {
            requested = new Rectangle(bounds);
        }
        Rectangle clipped = requested.intersection(imageBounds);
        if (clipped.width <= 0 || clipped.height <= 0) {
            throw new IllegalArgumentException("crop bounds do not overlap the source image");
        }
        return clipped;
    }

    public String toCanonicalJson() {
        LinkedHashMap<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("mode", mode.name());
        if (mode == Mode.CUSTOM && bounds != null) {
            root.put("height", Integer.valueOf(bounds.height));
            root.put("width", Integer.valueOf(bounds.width));
            root.put("x", Integer.valueOf(bounds.x));
            root.put("y", Integer.valueOf(bounds.y));
        }
        return CanonicalJson.write(root);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof CropSpec)) return false;
        CropSpec other = (CropSpec) obj;
        if (mode != other.mode) return false;
        return bounds == null ? other.bounds == null : bounds.equals(other.bounds);
    }

    @Override
    public int hashCode() {
        int result = mode.hashCode();
        result = 31 * result + (bounds == null ? 0 : bounds.hashCode());
        return result;
    }
}
