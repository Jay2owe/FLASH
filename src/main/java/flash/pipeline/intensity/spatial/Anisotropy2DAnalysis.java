package flash.pipeline.intensity.spatial;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import flash.pipeline.runtime.DependencyId;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.process.ImageProcessor;
import net.imglib2.RandomAccess;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.algorithm.gradient.PartialDerivative;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.FloatArray;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

import java.awt.Rectangle;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * In-house 2D structure-tensor anisotropy metrics for raw intensity images.
 */
public final class Anisotropy2DAnalysis implements IntensitySpatialAnalysis {
    public static final String COLUMN_COHERENCY = "Intensity_AnisotropyCoherency";
    public static final String COLUMN_ANGLE = "Intensity_AnisotropyAngle_deg";
    public static final String COLUMN_ENTROPY = "Intensity_AnisotropyEntropy";

    private static final int ANGLE_BINS = 18;
    private static final double DEFAULT_INTEGRATION_SCALE_UM = 2.0;

    @Override
    public IntensitySpatialConfig.AnalysisKey key() {
        return IntensitySpatialConfig.AnalysisKey.ANISOTROPY;
    }

    @Override
    public AnalysisValidity validity() {
        return AnalysisValidity.RAW_ONLY;
    }

    @Override
    public EnumSet<IntensitySpatialOutputMode> outputModes() {
        return EnumSet.of(IntensitySpatialOutputMode.BASE, IntensitySpatialOutputMode.MIP);
    }

    @Override
    public Set<DependencyId> dependencyIds() {
        return Collections.singleton(DependencyId.IMGLIB2_ALGORITHM_RUNTIME);
    }

    @Override
    public List<String> columns(IntensitySpatialConfig config, boolean binarizedPartner) {
        return java.util.Arrays.asList(COLUMN_COHERENCY, COLUMN_ANGLE, COLUMN_ENTROPY);
    }

    @Override
    public int estimatedCost() {
        return 5;
    }

    @Override
    public IntensitySpatialResult measure(IntensitySpatialContext context) throws Exception {
        Plane plane = Plane.from(context.image(), context.sliceIndex(), context.roi());
        LinkedHashMap<String, Double> values = new LinkedHashMap<String, Double>();
        if (plane.count < 4 || plane.width < 2 || plane.height < 2) {
            context.warn("2D anisotropy has insufficient valid ROI pixels; returning NaN.");
            putNan(values);
            return new IntensitySpatialResult(values);
        }

        TensorImages tensor = TensorImages.from(plane, context.image());
        TensorStats stats = TensorStats.from(plane, tensor);
        values.put(COLUMN_COHERENCY, Double.valueOf(stats.coherency));
        values.put(COLUMN_ANGLE, Double.valueOf(stats.angleDegrees));
        values.put(COLUMN_ENTROPY, Double.valueOf(stats.entropy));
        return new IntensitySpatialResult(values);
    }

    private static void putNan(LinkedHashMap<String, Double> values) {
        values.put(COLUMN_COHERENCY, Double.valueOf(Double.NaN));
        values.put(COLUMN_ANGLE, Double.valueOf(Double.NaN));
        values.put(COLUMN_ENTROPY, Double.valueOf(Double.NaN));
    }

    private static double pixelSize(ImagePlus image, boolean xAxis) {
        return CalibrationUtil.pixelSizeUm(image,
                xAxis ? CalibrationUtil.Axis.X : CalibrationUtil.Axis.Y);
    }

    private static double integrationSigmaPixels(ImagePlus image, boolean xAxis) {
        return Math.max(0.5, DEFAULT_INTEGRATION_SCALE_UM / pixelSize(image, xAxis));
    }

    private static Rectangle clippedBounds(ImagePlus image, Roi roi) {
        int width = image == null ? 0 : image.getWidth();
        int height = image == null ? 0 : image.getHeight();
        Rectangle raw = roi == null ? new Rectangle(0, 0, width, height) : roi.getBounds();
        int x0 = Math.max(0, raw.x);
        int y0 = Math.max(0, raw.y);
        int x1 = Math.min(width, raw.x + raw.width);
        int y1 = Math.min(height, raw.y + raw.height);
        return new Rectangle(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
    }

    private static double normalizedAngle(double angleRadians) {
        double angle = angleRadians % Math.PI;
        if (angle < 0.0) angle += Math.PI;
        return angle;
    }

    private static final class TensorImages {
        final ArrayImg<FloatType, FloatArray> ixx;
        final ArrayImg<FloatType, FloatArray> iyy;
        final ArrayImg<FloatType, FloatArray> ixy;

        private TensorImages(ArrayImg<FloatType, FloatArray> ixx,
                             ArrayImg<FloatType, FloatArray> iyy,
                             ArrayImg<FloatType, FloatArray> ixy) {
            this.ixx = ixx;
            this.iyy = iyy;
            this.ixy = ixy;
        }

        static TensorImages from(Plane plane, ImagePlus image) throws Exception {
            ArrayImg<FloatType, FloatArray> source = ArrayImgs.floats(plane.values.clone(),
                    plane.width, plane.height);
            ArrayImg<FloatType, FloatArray> gx = ArrayImgs.floats(plane.width, plane.height);
            ArrayImg<FloatType, FloatArray> gy = ArrayImgs.floats(plane.width, plane.height);
            PartialDerivative.gradientCentralDifference(Views.extendMirrorSingle(source), gx, 0);
            PartialDerivative.gradientCentralDifference(Views.extendMirrorSingle(source), gy, 1);

            ArrayImg<FloatType, FloatArray> rawIxx = ArrayImgs.floats(plane.width, plane.height);
            ArrayImg<FloatType, FloatArray> rawIyy = ArrayImgs.floats(plane.width, plane.height);
            ArrayImg<FloatType, FloatArray> rawIxy = ArrayImgs.floats(plane.width, plane.height);
            RandomAccess<FloatType> gxRa = gx.randomAccess();
            RandomAccess<FloatType> gyRa = gy.randomAccess();
            RandomAccess<FloatType> ixxRa = rawIxx.randomAccess();
            RandomAccess<FloatType> iyyRa = rawIyy.randomAccess();
            RandomAccess<FloatType> ixyRa = rawIxy.randomAccess();
            double pixelWidth = pixelSize(image, true);
            double pixelHeight = pixelSize(image, false);
            for (int y = 0; y < plane.height; y++) {
                for (int x = 0; x < plane.width; x++) {
                    gxRa.setPosition(x, 0);
                    gxRa.setPosition(y, 1);
                    gyRa.setPosition(x, 0);
                    gyRa.setPosition(y, 1);
                    double ix = gxRa.get().getRealDouble() / pixelWidth;
                    double iy = gyRa.get().getRealDouble() / pixelHeight;
                    ixxRa.setPosition(x, 0);
                    ixxRa.setPosition(y, 1);
                    iyyRa.setPosition(x, 0);
                    iyyRa.setPosition(y, 1);
                    ixyRa.setPosition(x, 0);
                    ixyRa.setPosition(y, 1);
                    ixxRa.get().setReal(ix * ix);
                    iyyRa.get().setReal(iy * iy);
                    ixyRa.get().setReal(ix * iy);
                }
            }

            ArrayImg<FloatType, FloatArray> smoothIxx = ArrayImgs.floats(plane.width, plane.height);
            ArrayImg<FloatType, FloatArray> smoothIyy = ArrayImgs.floats(plane.width, plane.height);
            ArrayImg<FloatType, FloatArray> smoothIxy = ArrayImgs.floats(plane.width, plane.height);
            double[] sigma = new double[]{
                    integrationSigmaPixels(image, true),
                    integrationSigmaPixels(image, false)
            };
            Gauss3.gauss(sigma, Views.extendMirrorSingle(rawIxx), smoothIxx);
            Gauss3.gauss(sigma, Views.extendMirrorSingle(rawIyy), smoothIyy);
            Gauss3.gauss(sigma, Views.extendMirrorSingle(rawIxy), smoothIxy);
            return new TensorImages(smoothIxx, smoothIyy, smoothIxy);
        }
    }

    private static final class TensorStats {
        final double coherency;
        final double angleDegrees;
        final double entropy;

        private TensorStats(double coherency, double angleDegrees, double entropy) {
            this.coherency = coherency;
            this.angleDegrees = angleDegrees;
            this.entropy = entropy;
        }

        static TensorStats from(Plane plane, TensorImages tensor) {
            RandomAccess<FloatType> ixxRa = tensor.ixx.randomAccess();
            RandomAccess<FloatType> iyyRa = tensor.iyy.randomAccess();
            RandomAccess<FloatType> ixyRa = tensor.ixy.randomAccess();
            double epsilon = Math.max(1e-12, plane.meanAbsIntensity * plane.meanAbsIntensity * 1e-12);
            double coherencySum = 0.0;
            int coherencyCount = 0;
            double weightedSin = 0.0;
            double weightedCos = 0.0;
            double fallbackSin = 0.0;
            double fallbackCos = 0.0;
            double weightSum = 0.0;
            int[] histogram = new int[ANGLE_BINS];
            for (int y = 0; y < plane.height; y++) {
                for (int x = 0; x < plane.width; x++) {
                    int index = y * plane.width + x;
                    if (!plane.valid[index]) continue;
                    ixxRa.setPosition(x, 0);
                    ixxRa.setPosition(y, 1);
                    iyyRa.setPosition(x, 0);
                    iyyRa.setPosition(y, 1);
                    ixyRa.setPosition(x, 0);
                    ixyRa.setPosition(y, 1);
                    double ixx = ixxRa.get().getRealDouble();
                    double iyy = iyyRa.get().getRealDouble();
                    double ixy = ixyRa.get().getRealDouble();
                    double trace = ixx + iyy;
                    if (trace < epsilon || Double.isNaN(trace) || Double.isInfinite(trace)) {
                        continue;
                    }
                    double det = ixx * iyy - ixy * ixy;
                    double discriminant = Math.max(0.0, trace * trace * 0.25 - det);
                    double root = Math.sqrt(discriminant);
                    double lambda1 = trace * 0.5 + root;
                    double lambda2 = trace * 0.5 - root;
                    double denominator = lambda1 + lambda2;
                    if (denominator < epsilon) continue;
                    double coherency = (lambda1 - lambda2) / denominator;
                    if (Double.isNaN(coherency) || Double.isInfinite(coherency)) continue;
                    double angle = normalizedAngle(0.5 * Math.atan2(2.0 * ixy, iyy - ixx));
                    coherencySum += coherency;
                    coherencyCount++;
                    double doubled = 2.0 * angle;
                    double intensityWeight = Math.max(0.0, plane.values[index]);
                    weightedSin += intensityWeight * Math.sin(doubled);
                    weightedCos += intensityWeight * Math.cos(doubled);
                    weightSum += intensityWeight;
                    fallbackSin += Math.sin(doubled);
                    fallbackCos += Math.cos(doubled);
                    int bin = Math.min(ANGLE_BINS - 1,
                            (int) Math.floor(angle / Math.PI * ANGLE_BINS));
                    histogram[bin]++;
                }
            }
            if (coherencyCount == 0) {
                return new TensorStats(Double.NaN, Double.NaN, Double.NaN);
            }
            double sin = weightSum > 0.0 ? weightedSin : fallbackSin;
            double cos = weightSum > 0.0 ? weightedCos : fallbackCos;
            double angle = normalizedAngle(0.5 * Math.atan2(sin, cos));
            return new TensorStats(
                    coherencySum / coherencyCount,
                    Math.toDegrees(angle),
                    entropy(histogram, coherencyCount));
        }

        private static double entropy(int[] histogram, int total) {
            if (total <= 0) return Double.NaN;
            double h = 0.0;
            for (int count : histogram) {
                if (count == 0) continue;
                double p = count / (double) total;
                h -= p * Math.log(p);
            }
            return h / Math.log(histogram.length);
        }
    }

    private static final class Plane {
        final int width;
        final int height;
        final float[] values;
        final boolean[] valid;
        final int count;
        final double meanAbsIntensity;

        private Plane(int width,
                      int height,
                      float[] values,
                      boolean[] valid,
                      int count,
                      double meanAbsIntensity) {
            this.width = width;
            this.height = height;
            this.values = values;
            this.valid = valid;
            this.count = count;
            this.meanAbsIntensity = meanAbsIntensity;
        }

        static Plane from(ImagePlus image, int slice, Roi roi) {
            if (image == null || image.getStackSize() < slice) {
                return empty();
            }
            Rectangle bounds = clippedBounds(image, roi);
            if (bounds.width <= 0 || bounds.height <= 0) {
                return empty();
            }
            ImageProcessor ip = image.getStack().getProcessor(slice);
            int width = bounds.width;
            int height = bounds.height;
            float[] values = new float[width * height];
            boolean[] valid = new boolean[values.length];
            int count = 0;
            double sum = 0.0;
            double absSum = 0.0;
            for (int yy = 0; yy < height; yy++) {
                int y = bounds.y + yy;
                for (int xx = 0; xx < width; xx++) {
                    int x = bounds.x + xx;
                    int index = yy * width + xx;
                    double value = ip.getf(x, y);
                    boolean finite = !Double.isNaN(value) && !Double.isInfinite(value);
                    if (finite) {
                        values[index] = (float) value;
                    }
                    if ((roi == null || roi.contains(x, y)) && finite) {
                        valid[index] = true;
                        count++;
                        sum += value;
                        absSum += Math.abs(value);
                    }
                }
            }
            float fill = count == 0 ? 0.0f : (float) (sum / count);
            for (int i = 0; i < values.length; i++) {
                if (Double.isNaN(values[i]) || Double.isInfinite(values[i])) {
                    values[i] = fill;
                }
            }
            return new Plane(width, height, values, valid, count,
                    count == 0 ? Double.NaN : absSum / count);
        }

        private static Plane empty() {
            return new Plane(0, 0, new float[0], new boolean[0], 0, Double.NaN);
        }
    }
}
