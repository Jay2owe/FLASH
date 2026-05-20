package flash.pipeline.intensity.spatial;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.process.ImageProcessor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.numeric.real.FloatType;

import java.awt.Rectangle;
import java.util.Arrays;
import java.util.Locale;

/**
 * Shared 2D source/partner pixel extraction with ROI and optional user masks.
 */
final class PairPlane2D {
    final int width;
    final int height;
    final double[] source;
    final double[] partner;
    final boolean[] sourceMask;
    final boolean[] partnerMask;
    final boolean[] valid;
    final int count;
    final double pixelWidthUm;
    final double pixelHeightUm;

    private PairPlane2D(int width,
                        int height,
                        double[] source,
                        double[] partner,
                        boolean[] sourceMask,
                        boolean[] partnerMask,
                        boolean[] valid,
                        int count,
                        double pixelWidthUm,
                        double pixelHeightUm) {
        this.width = width;
        this.height = height;
        this.source = source;
        this.partner = partner;
        this.sourceMask = sourceMask;
        this.partnerMask = partnerMask;
        this.valid = valid;
        this.count = count;
        this.pixelWidthUm = pixelWidthUm;
        this.pixelHeightUm = pixelHeightUm;
    }

    static PairPlane2D raw(IntensitySpatialPairContext context) {
        return from(context.sourceImage(), context.partnerImage(),
                context.sourceMaskImage(), context.partnerMaskImage(),
                context.sliceIndex(), context.roi());
    }

    static PairPlane2D binarized(IntensitySpatialPairContext context) {
        return from(context.sourceBinarizedImage(), context.partnerBinarizedImage(),
                context.sourceMaskImage(), context.partnerMaskImage(),
                context.sliceIndex(), context.roi());
    }

    static PairPlane2D from(ImagePlus sourceImage,
                            ImagePlus partnerImage,
                            ImagePlus sourceMaskImage,
                            ImagePlus partnerMaskImage,
                            int sliceIndex,
                            Roi roi) {
        if (sourceImage == null || partnerImage == null
                || sourceImage.getStackSize() < sliceIndex
                || partnerImage.getStackSize() < sliceIndex) {
            return empty(sourceImage, partnerImage);
        }
        int width = Math.min(sourceImage.getWidth(), partnerImage.getWidth());
        int height = Math.min(sourceImage.getHeight(), partnerImage.getHeight());
        if (width <= 0 || height <= 0) return empty(sourceImage, partnerImage);

        ImageProcessor sourceProcessor = sourceImage.getStack().getProcessor(sliceIndex);
        ImageProcessor partnerProcessor = partnerImage.getStack().getProcessor(sliceIndex);
        ImageProcessor sourceMaskProcessor = processorOrNull(sourceMaskImage, sliceIndex);
        ImageProcessor partnerMaskProcessor = processorOrNull(partnerMaskImage, sliceIndex);
        Rectangle bounds = clippedBounds(width, height, roi);

        double[] source = new double[width * height];
        double[] partner = new double[source.length];
        boolean[] sourceMask = new boolean[source.length];
        boolean[] partnerMask = new boolean[source.length];
        boolean[] valid = new boolean[source.length];
        int count = 0;

        for (int y = bounds.y; y < bounds.y + bounds.height; y++) {
            for (int x = bounds.x; x < bounds.x + bounds.width; x++) {
                if (roi != null && !roi.contains(x, y)) continue;
                double s = sourceProcessor.getf(x, y);
                double p = partnerProcessor.getf(x, y);
                if (!isFinite(s) || !isFinite(p)) continue;
                int index = y * width + x;
                source[index] = s;
                partner[index] = p;
                sourceMask[index] = maskValue(sourceMaskProcessor, x, y);
                partnerMask[index] = maskValue(partnerMaskProcessor, x, y);
                valid[index] = true;
                count++;
            }
        }

        return new PairPlane2D(width, height, source, partner, sourceMask, partnerMask, valid, count,
                pixelSize(sourceImage, true), pixelSize(sourceImage, false));
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
        long[] dims = new long[]{width, height};
        return new ColocImages(
                ArrayImgs.floats(sourcePixels, dims),
                ArrayImgs.floats(partnerPixels, dims),
                ArrayImgs.floats(maskPixels, dims),
                dims);
    }

    ShiftedSamples shiftedSamples(int dx, int dy) {
        int capacity = Math.max(0, count);
        double[] a = new double[capacity];
        double[] b = new double[capacity];
        int n = 0;
        for (int y = 0; y < height; y++) {
            int yy = y + dy;
            if (yy < 0 || yy >= height) continue;
            for (int x = 0; x < width; x++) {
                int xx = x + dx;
                if (xx < 0 || xx >= width) continue;
                int sourceIndex = y * width + x;
                int partnerIndex = yy * width + xx;
                if (!valid[sourceIndex] || !valid[partnerIndex]) continue;
                a[n] = source[sourceIndex];
                b[n] = partner[partnerIndex];
                n++;
            }
        }
        return new ShiftedSamples(a, b, n);
    }

    double shiftedPearson(int dx, int dy) {
        double sumA = 0.0;
        double sumB = 0.0;
        double sumAA = 0.0;
        double sumBB = 0.0;
        double sumAB = 0.0;
        int n = 0;
        for (int y = 0; y < height; y++) {
            int yy = y + dy;
            if (yy < 0 || yy >= height) continue;
            for (int x = 0; x < width; x++) {
                int xx = x + dx;
                if (xx < 0 || xx >= width) continue;
                int sourceIndex = y * width + x;
                int partnerIndex = yy * width + xx;
                if (!valid[sourceIndex] || !valid[partnerIndex]) continue;
                double a = source[sourceIndex];
                double b = partner[partnerIndex];
                sumA += a;
                sumB += b;
                sumAA += a * a;
                sumBB += b * b;
                sumAB += a * b;
                n++;
            }
        }
        if (n < 2) return Double.NaN;
        double cov = sumAB - (sumA * sumB / n);
        double varA = sumAA - (sumA * sumA / n);
        double varB = sumBB - (sumB * sumB / n);
        double denom = Math.sqrt(varA * varB);
        return denom <= 0.0 || !isFinite(denom) ? Double.NaN : cov / denom;
    }

    boolean hasPartnerMask() {
        for (int i = 0; i < partnerMask.length; i++) {
            if (valid[i] && partnerMask[i]) return true;
        }
        return false;
    }

    double meanSource() {
        if (count == 0) return Double.NaN;
        double sum = 0.0;
        int n = 0;
        for (int i = 0; i < source.length; i++) {
            if (!valid[i]) continue;
            sum += source[i];
            n++;
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    double meanPartner() {
        if (count == 0) return Double.NaN;
        double sum = 0.0;
        int n = 0;
        for (int i = 0; i < partner.length; i++) {
            if (!valid[i]) continue;
            sum += partner[i];
            n++;
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    static double slope(double[] xs, double[] ys, int n) {
        if (n < 2) return Double.NaN;
        double meanX = 0.0;
        double meanY = 0.0;
        for (int i = 0; i < n; i++) {
            meanX += xs[i];
            meanY += ys[i];
        }
        meanX /= n;
        meanY /= n;
        double cov = 0.0;
        double var = 0.0;
        for (int i = 0; i < n; i++) {
            double dx = xs[i] - meanX;
            cov += dx * (ys[i] - meanY);
            var += dx * dx;
        }
        return var <= 0.0 ? Double.NaN : cov / var;
    }

    static String scaleToken(double value) {
        if (Double.compare(value, Math.rint(value)) == 0) {
            return String.valueOf((long) value);
        }
        String text = String.format(Locale.ROOT, "%.6f", value);
        while (text.contains(".") && text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        return text.replace('.', 'p');
    }

    private static PairPlane2D empty(ImagePlus sourceImage, ImagePlus partnerImage) {
        int width = sourceImage == null ? 0 : sourceImage.getWidth();
        int height = sourceImage == null ? 0 : sourceImage.getHeight();
        if (partnerImage != null) {
            width = width == 0 ? partnerImage.getWidth() : Math.min(width, partnerImage.getWidth());
            height = height == 0 ? partnerImage.getHeight() : Math.min(height, partnerImage.getHeight());
        }
        int size = Math.max(0, width * height);
        return new PairPlane2D(width, height, new double[size], new double[size],
                new boolean[size], new boolean[size], new boolean[size], 0,
                pixelSize(sourceImage, true), pixelSize(sourceImage, false));
    }

    private static ImageProcessor processorOrNull(ImagePlus image, int sliceIndex) {
        if (image == null || image.getStackSize() < sliceIndex) return null;
        return image.getStack().getProcessor(sliceIndex);
    }

    private static boolean maskValue(ImageProcessor processor, int x, int y) {
        if (processor == null
                || x < 0 || y < 0
                || x >= processor.getWidth() || y >= processor.getHeight()) {
            return false;
        }
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

    private static double pixelSize(ImagePlus image, boolean xAxis) {
        return CalibrationUtil.pixelSizeUm(image,
                xAxis ? CalibrationUtil.Axis.X : CalibrationUtil.Axis.Y);
    }

    static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
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

    static final class ShiftedSamples {
        final double[] source;
        final double[] partner;
        final int count;

        private ShiftedSamples(double[] source, double[] partner, int count) {
            this.source = source;
            this.partner = partner;
            this.count = count;
        }
    }
}
