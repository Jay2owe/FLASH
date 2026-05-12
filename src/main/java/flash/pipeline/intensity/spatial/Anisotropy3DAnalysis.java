package flash.pipeline.intensity.spatial;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import flash.pipeline.runtime.DependencyId;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.process.ImageProcessor;

import java.awt.Rectangle;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Native-3D structure-tensor anisotropy metrics for raw intensity stacks.
 */
public final class Anisotropy3DAnalysis implements IntensitySpatialAnalysis {
    public static final String COLUMN_COHERENCY = "Intensity_Anisotropy3DCoherency";
    public static final String COLUMN_ANGLE = "Intensity_Anisotropy3DAngle_deg";
    public static final String COLUMN_ENTROPY = "Intensity_Anisotropy3DEntropy";

    private static final int ANGLE_BINS = 18;

    @Override
    public IntensitySpatialConfig.AnalysisKey key() {
        return IntensitySpatialConfig.AnalysisKey.ANISOTROPY_3D;
    }

    @Override
    public AnalysisValidity validity() {
        return AnalysisValidity.NATIVE_3D;
    }

    @Override
    public EnumSet<IntensitySpatialOutputMode> outputModes() {
        return EnumSet.of(IntensitySpatialOutputMode.NATIVE_3D);
    }

    @Override
    public Set<DependencyId> dependencyIds() {
        return Collections.emptySet();
    }

    @Override
    public List<String> columns(IntensitySpatialConfig config, boolean binarizedPartner) {
        return Arrays.asList(COLUMN_COHERENCY, COLUMN_ANGLE, COLUMN_ENTROPY);
    }

    @Override
    public int estimatedCost() {
        return 8;
    }

    @Override
    public IntensitySpatialResult measure(IntensitySpatialContext context) {
        List<String> columns = columns(context.config(), false);
        IntensitySpatialResult bridged = OrientationJBridge.measure(context, columns);
        if (bridged != null) {
            return bridged;
        }

        Volume volume = Volume.from(context.image(), context.roi());
        LinkedHashMap<String, Double> values = new LinkedHashMap<String, Double>();
        if (volume.count < 8 || volume.depth < IntensitySpatialConfig.MIN_NATIVE_3D_SLICES) {
            context.warn("native-3D anisotropy requires at least "
                    + IntensitySpatialConfig.MIN_NATIVE_3D_SLICES
                    + " slices and enough valid voxels; returning NaN.");
            putNan(values);
            return new IntensitySpatialResult(values);
        }

        TensorStats stats = TensorStats.from(volume);
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

    private static double normalizedAngle(double angleRadians) {
        double angle = angleRadians % Math.PI;
        if (angle < 0.0) angle += Math.PI;
        return angle;
    }

    private static double pixelSize(ImagePlus image, Axis axis) {
        Calibration cal = image == null ? null : image.getCalibration();
        double value = 1.0;
        if (cal != null) {
            if (axis == Axis.X) value = cal.pixelWidth;
            if (axis == Axis.Y) value = cal.pixelHeight;
            if (axis == Axis.Z) value = cal.pixelDepth;
        }
        return value > 0.0 && PairVolume3D.isFinite(value) ? value : 1.0;
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

    private enum Axis {
        X,
        Y,
        Z
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

        static TensorStats from(Volume volume) {
            double[][] tensor = new double[3][3];
            int[] histogram = new int[ANGLE_BINS];
            int orientationCount = 0;
            for (int z = 0; z < volume.depth; z++) {
                for (int y = 0; y < volume.height; y++) {
                    for (int x = 0; x < volume.width; x++) {
                        int index = volume.index(x, y, z);
                        if (!volume.valid[index]) continue;
                        double gx = volume.gradientX(x, y, z);
                        double gy = volume.gradientY(x, y, z);
                        double gz = volume.gradientZ(x, y, z);
                        double magnitude = Math.sqrt(gx * gx + gy * gy + gz * gz);
                        if (magnitude <= volume.epsilon) continue;
                        tensor[0][0] += gx * gx;
                        tensor[0][1] += gx * gy;
                        tensor[0][2] += gx * gz;
                        tensor[1][1] += gy * gy;
                        tensor[1][2] += gy * gz;
                        tensor[2][2] += gz * gz;

                        double xyMagnitude = Math.sqrt(gx * gx + gy * gy);
                        if (xyMagnitude > volume.epsilon) {
                            double angle = normalizedAngle(Math.atan2(gy, gx) + Math.PI / 2.0);
                            int bin = Math.min(ANGLE_BINS - 1,
                                    (int) Math.floor(angle / Math.PI * ANGLE_BINS));
                            histogram[bin]++;
                            orientationCount++;
                        }
                    }
                }
            }
            tensor[1][0] = tensor[0][1];
            tensor[2][0] = tensor[0][2];
            tensor[2][1] = tensor[1][2];

            Eigen eigen = Eigen.symmetric(tensor);
            double l1 = Math.max(0.0, eigen.values[0]);
            double l2 = Math.max(0.0, eigen.values[1]);
            double l3 = Math.max(0.0, eigen.values[2]);
            double denom = l1 + l2 + l3;
            if (denom <= volume.epsilon) {
                return new TensorStats(Double.NaN, Double.NaN, Double.NaN);
            }
            double coherency = (l1 - l2) / denom;
            if (coherency < 0.0) coherency = 0.0;
            if (coherency > 1.0) coherency = 1.0;

            double vx = eigen.vectors[0][0];
            double vy = eigen.vectors[1][0];
            double angle = Math.sqrt(vx * vx + vy * vy) <= volume.epsilon
                    ? Double.NaN
                    : Math.toDegrees(normalizedAngle(Math.atan2(vy, vx) + Math.PI / 2.0));
            return new TensorStats(coherency, angle, entropy(histogram, orientationCount));
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

    private static final class Volume {
        final int width;
        final int height;
        final int depth;
        final float[] values;
        final boolean[] valid;
        final int count;
        final double pixelWidth;
        final double pixelHeight;
        final double pixelDepth;
        final double epsilon;

        private Volume(int width,
                       int height,
                       int depth,
                       float[] values,
                       boolean[] valid,
                       int count,
                       double pixelWidth,
                       double pixelHeight,
                       double pixelDepth,
                       double meanAbsIntensity) {
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.values = values;
            this.valid = valid;
            this.count = count;
            this.pixelWidth = pixelWidth;
            this.pixelHeight = pixelHeight;
            this.pixelDepth = pixelDepth;
            this.epsilon = Math.max(1e-12, meanAbsIntensity * meanAbsIntensity * 1e-12);
        }

        static Volume from(ImagePlus image, Roi roi) {
            if (image == null || image.getStackSize() <= 0) {
                return empty(image);
            }
            Rectangle bounds = clippedBounds(image, roi);
            int depth = Math.max(1, image.getStackSize());
            if (bounds.width <= 0 || bounds.height <= 0 || depth <= 0) {
                return empty(image);
            }
            int width = bounds.width;
            int height = bounds.height;
            float[] values = new float[width * height * depth];
            boolean[] valid = new boolean[values.length];
            int count = 0;
            double sum = 0.0;
            double absSum = 0.0;
            for (int z = 0; z < depth; z++) {
                ImageProcessor ip = image.getStack().getProcessor(z + 1);
                for (int yy = 0; yy < height; yy++) {
                    int y = bounds.y + yy;
                    for (int xx = 0; xx < width; xx++) {
                        int x = bounds.x + xx;
                        int index = (z * height + yy) * width + xx;
                        double value = ip.getf(x, y);
                        boolean finite = PairVolume3D.isFinite(value);
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
            }
            float fill = count == 0 ? 0.0f : (float) (sum / count);
            for (int i = 0; i < values.length; i++) {
                if (Float.isNaN(values[i]) || Float.isInfinite(values[i])) {
                    values[i] = fill;
                }
            }
            return new Volume(width, height, depth, values, valid, count,
                    pixelSize(image, Axis.X), pixelSize(image, Axis.Y), pixelSize(image, Axis.Z),
                    count == 0 ? 1.0 : absSum / count);
        }

        int index(int x, int y, int z) {
            return (z * height + y) * width + x;
        }

        double value(int x, int y, int z) {
            int xx = Math.max(0, Math.min(width - 1, x));
            int yy = Math.max(0, Math.min(height - 1, y));
            int zz = Math.max(0, Math.min(depth - 1, z));
            return values[index(xx, yy, zz)];
        }

        double gradientX(int x, int y, int z) {
            int left = Math.max(0, x - 1);
            int right = Math.min(width - 1, x + 1);
            double spacing = Math.max(pixelWidth, (right - left) * pixelWidth);
            return (value(right, y, z) - value(left, y, z)) / spacing;
        }

        double gradientY(int x, int y, int z) {
            int top = Math.max(0, y - 1);
            int bottom = Math.min(height - 1, y + 1);
            double spacing = Math.max(pixelHeight, (bottom - top) * pixelHeight);
            return (value(x, bottom, z) - value(x, top, z)) / spacing;
        }

        double gradientZ(int x, int y, int z) {
            int before = Math.max(0, z - 1);
            int after = Math.min(depth - 1, z + 1);
            double spacing = Math.max(pixelDepth, (after - before) * pixelDepth);
            return (value(x, y, after) - value(x, y, before)) / spacing;
        }

        private static Volume empty(ImagePlus image) {
            return new Volume(0, 0, 0, new float[0], new boolean[0], 0,
                    pixelSize(image, Axis.X), pixelSize(image, Axis.Y), pixelSize(image, Axis.Z), 1.0);
        }
    }

    private static final class Eigen {
        final double[] values;
        final double[][] vectors;

        private Eigen(double[] values, double[][] vectors) {
            this.values = values;
            this.vectors = vectors;
        }

        static Eigen symmetric(double[][] source) {
            double[][] a = new double[3][3];
            double[][] v = new double[][]{
                    {1.0, 0.0, 0.0},
                    {0.0, 1.0, 0.0},
                    {0.0, 0.0, 1.0}
            };
            for (int i = 0; i < 3; i++) {
                System.arraycopy(source[i], 0, a[i], 0, 3);
            }
            for (int iter = 0; iter < 50; iter++) {
                int p = 0;
                int q = 1;
                double max = Math.abs(a[0][1]);
                if (Math.abs(a[0][2]) > max) {
                    p = 0;
                    q = 2;
                    max = Math.abs(a[0][2]);
                }
                if (Math.abs(a[1][2]) > max) {
                    p = 1;
                    q = 2;
                    max = Math.abs(a[1][2]);
                }
                if (max < 1e-12) break;

                double app = a[p][p];
                double aqq = a[q][q];
                double apq = a[p][q];
                double phi = 0.5 * Math.atan2(2.0 * apq, aqq - app);
                double c = Math.cos(phi);
                double s = Math.sin(phi);

                for (int k = 0; k < 3; k++) {
                    double aik = a[k][p];
                    double akq = a[k][q];
                    a[k][p] = c * aik - s * akq;
                    a[k][q] = s * aik + c * akq;
                }
                for (int k = 0; k < 3; k++) {
                    double aip = a[p][k];
                    double aiq = a[q][k];
                    a[p][k] = c * aip - s * aiq;
                    a[q][k] = s * aip + c * aiq;
                }
                for (int k = 0; k < 3; k++) {
                    double vip = v[k][p];
                    double viq = v[k][q];
                    v[k][p] = c * vip - s * viq;
                    v[k][q] = s * vip + c * viq;
                }
            }

            double[] values = new double[]{a[0][0], a[1][1], a[2][2]};
            for (int i = 0; i < values.length - 1; i++) {
                for (int j = i + 1; j < values.length; j++) {
                    if (values[j] > values[i]) {
                        double tmp = values[i];
                        values[i] = values[j];
                        values[j] = tmp;
                        for (int row = 0; row < 3; row++) {
                            double tv = v[row][i];
                            v[row][i] = v[row][j];
                            v[row][j] = tv;
                        }
                    }
                }
            }
            return new Eigen(values, v);
        }
    }
}
