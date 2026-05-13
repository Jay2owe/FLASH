package flash.pipeline.intensity.spatial;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.process.ImageProcessor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.numeric.real.FloatType;

import java.awt.Rectangle;
import java.util.Arrays;

/**
 * Shared native-3D source/partner voxel extraction with ROI and user masks.
 */
final class PairVolume3D {
    final int width;
    final int height;
    final int depth;
    final double[] source;
    final double[] partner;
    final boolean[] sourceMask;
    final boolean[] partnerMask;
    final boolean[] valid;
    final int count;
    final double pixelWidthUm;
    final double pixelHeightUm;
    final double pixelDepthUm;

    private PairVolume3D(int width,
                         int height,
                         int depth,
                         double[] source,
                         double[] partner,
                         boolean[] sourceMask,
                         boolean[] partnerMask,
                         boolean[] valid,
                         int count,
                         double pixelWidthUm,
                         double pixelHeightUm,
                         double pixelDepthUm) {
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.source = source;
        this.partner = partner;
        this.sourceMask = sourceMask;
        this.partnerMask = partnerMask;
        this.valid = valid;
        this.count = count;
        this.pixelWidthUm = pixelWidthUm;
        this.pixelHeightUm = pixelHeightUm;
        this.pixelDepthUm = pixelDepthUm;
    }

    static PairVolume3D raw(IntensitySpatialPairContext context) {
        return from(context.sourceImage(), context.partnerImage(),
                context.sourceMaskImage(), context.partnerMaskImage(), context.roi());
    }

    static PairVolume3D binarized(IntensitySpatialPairContext context) {
        return from(context.sourceBinarizedImage(), context.partnerBinarizedImage(),
                context.sourceMaskImage(), context.partnerMaskImage(), context.roi());
    }

    static PairVolume3D from(ImagePlus sourceImage,
                             ImagePlus partnerImage,
                             ImagePlus sourceMaskImage,
                             ImagePlus partnerMaskImage,
                             Roi roi) {
        if (sourceImage == null || partnerImage == null) {
            return empty(sourceImage, partnerImage);
        }
        int width = Math.min(sourceImage.getWidth(), partnerImage.getWidth());
        int height = Math.min(sourceImage.getHeight(), partnerImage.getHeight());
        int depth = Math.min(Math.max(1, sourceImage.getStackSize()),
                Math.max(1, partnerImage.getStackSize()));
        if (width <= 0 || height <= 0 || depth <= 0) {
            return empty(sourceImage, partnerImage);
        }

        Rectangle bounds = clippedBounds(width, height, roi);
        int size = width * height * depth;
        double[] source = new double[size];
        double[] partner = new double[size];
        boolean[] sourceMask = new boolean[size];
        boolean[] partnerMask = new boolean[size];
        boolean[] valid = new boolean[size];
        int count = 0;

        for (int z = 0; z < depth; z++) {
            ImageProcessor sourceProcessor = sourceImage.getStack().getProcessor(z + 1);
            ImageProcessor partnerProcessor = partnerImage.getStack().getProcessor(z + 1);
            ImageProcessor sourceMaskProcessor = processorOrNull(sourceMaskImage, z + 1);
            ImageProcessor partnerMaskProcessor = processorOrNull(partnerMaskImage, z + 1);
            for (int y = bounds.y; y < bounds.y + bounds.height; y++) {
                for (int x = bounds.x; x < bounds.x + bounds.width; x++) {
                    if (roi != null && !roi.contains(x, y)) continue;
                    double s = sourceProcessor.getf(x, y);
                    double p = partnerProcessor.getf(x, y);
                    if (!isFinite(s) || !isFinite(p)) continue;
                    int index = index(x, y, z, width, height);
                    source[index] = s;
                    partner[index] = p;
                    sourceMask[index] = maskValue(sourceMaskProcessor, x, y);
                    partnerMask[index] = maskValue(partnerMaskProcessor, x, y);
                    valid[index] = true;
                    count++;
                }
            }
        }

        return new PairVolume3D(width, height, depth, source, partner,
                sourceMask, partnerMask, valid, count,
                pixelSize(sourceImage, Axis.X),
                pixelSize(sourceImage, Axis.Y),
                pixelSize(sourceImage, Axis.Z));
    }

    ColocImages toColocImages() {
        float[] sourcePixels = new float[source.length];
        float[] partnerPixels = new float[partner.length];
        float[] maskPixels = new float[valid.length];
        for (int i = 0; i < source.length; i++) {
            sourcePixels[i] = (float) source[i];
            partnerPixels[i] = (float) partner[i];
            maskPixels[i] = valid[i] ? 1.0f : 0.0f;
        }
        long[] dims = new long[]{width, height, depth};
        return new ColocImages(
                ArrayImgs.floats(sourcePixels, dims),
                ArrayImgs.floats(partnerPixels, dims),
                ArrayImgs.floats(maskPixels, dims),
                dims);
    }

    boolean hasPartnerMask() {
        for (int i = 0; i < partnerMask.length; i++) {
            if (valid[i] && partnerMask[i]) return true;
        }
        return false;
    }

    static int index(int x, int y, int z, int width, int height) {
        return (z * height + y) * width + x;
    }

    static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static PairVolume3D empty(ImagePlus sourceImage, ImagePlus partnerImage) {
        int width = sourceImage == null ? 0 : sourceImage.getWidth();
        int height = sourceImage == null ? 0 : sourceImage.getHeight();
        int depth = sourceImage == null ? 0 : Math.max(1, sourceImage.getStackSize());
        if (partnerImage != null) {
            width = width == 0 ? partnerImage.getWidth() : Math.min(width, partnerImage.getWidth());
            height = height == 0 ? partnerImage.getHeight() : Math.min(height, partnerImage.getHeight());
            depth = depth == 0 ? Math.max(1, partnerImage.getStackSize())
                    : Math.min(depth, Math.max(1, partnerImage.getStackSize()));
        }
        int size = Math.max(0, width * height * depth);
        return new PairVolume3D(width, height, depth, new double[size], new double[size],
                new boolean[size], new boolean[size], new boolean[size], 0,
                pixelSize(sourceImage, Axis.X), pixelSize(sourceImage, Axis.Y),
                pixelSize(sourceImage, Axis.Z));
    }

    private static ImageProcessor processorOrNull(ImagePlus image, int sliceIndex) {
        if (image == null || image.getStackSize() < sliceIndex) return null;
        return image.getStack().getProcessor(sliceIndex);
    }

    private static boolean maskValue(ImageProcessor processor, int x, int y) {
        if (processor == null || x >= processor.getWidth() || y >= processor.getHeight()) return false;
        double value = processor.getf(x, y);
        return isFinite(value) && value > 0.0;
    }

    private static Rectangle clippedBounds(int width, int height, Roi roi) {
        Rectangle raw = roi == null ? new Rectangle(0, 0, width, height) : roi.getBounds();
        int x0 = Math.max(0, raw.x);
        int y0 = Math.max(0, raw.y);
        int x1 = Math.min(width, raw.x + raw.width);
        int y1 = Math.min(height, raw.y + raw.height);
        return new Rectangle(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
    }

    private static double pixelSize(ImagePlus image, Axis axis) {
        CalibrationUtil.Axis utilAxis = axis == Axis.X ? CalibrationUtil.Axis.X
                : axis == Axis.Y ? CalibrationUtil.Axis.Y
                : CalibrationUtil.Axis.Z;
        return CalibrationUtil.pixelSizeUm(image, utilAxis);
    }

    private enum Axis {
        X,
        Y,
        Z
    }

    static final class ColocImages {
        final RandomAccessibleInterval<FloatType> source;
        final RandomAccessibleInterval<FloatType> partner;
        final RandomAccessibleInterval<FloatType> mask;
        final long[] dimensions;

        private ColocImages(RandomAccessibleInterval<FloatType> source,
                            RandomAccessibleInterval<FloatType> partner,
                            RandomAccessibleInterval<FloatType> mask,
                            long[] dimensions) {
            this.source = source;
            this.partner = partner;
            this.mask = mask;
            this.dimensions = Arrays.copyOf(dimensions, dimensions.length);
        }
    }
}
