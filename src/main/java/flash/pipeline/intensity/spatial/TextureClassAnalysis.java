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
 * Conservative texture-class fractions from Gaussian bandpass and gradient
 * features clustered with deterministic k-means.
 */
public final class TextureClassAnalysis implements IntensitySpatialAnalysis {
    private static final int MAX_SIDE = 128;
    private static final int FEATURE_COUNT = 4;
    private static final int MAX_ITERATIONS = 30;

    @Override
    public IntensitySpatialConfig.AnalysisKey key() {
        return IntensitySpatialConfig.AnalysisKey.TEXTURECLASS;
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
        int k = classCount(config);
        java.util.ArrayList<String> columns = new java.util.ArrayList<String>();
        for (int i = 1; i <= k; i++) {
            columns.add(column(i));
        }
        return columns;
    }

    @Override
    public int estimatedCost() {
        return 7;
    }

    @Override
    public IntensitySpatialResult measure(IntensitySpatialContext context) throws Exception {
        int k = classCount(context.config());
        Plane plane = Plane.from(context.image(), context.sliceIndex(), context.roi());
        LinkedHashMap<String, Double> values = new LinkedHashMap<String, Double>();
        if (plane.count == 0 || plane.width < 2 || plane.height < 2) {
            context.warn("texture classes have no valid ROI pixels; returning NaN.");
            putNan(values, k);
            return new IntensitySpatialResult(values);
        }
        if (plane.count < k) {
            context.warn("texture classes have fewer valid pixels than classes; using available pixels.");
        }

        double[][] features = FeatureTable.from(plane).standardizedFeatures;
        double[] fractions = KMeans.fractions(features, Math.min(k, features.length), k);
        for (int i = 0; i < k; i++) {
            values.put(column(i + 1), Double.valueOf(fractions[i]));
        }
        return new IntensitySpatialResult(values);
    }

    private static String column(int oneBasedClass) {
        return "Intensity_TextureClass" + oneBasedClass + "_fraction";
    }

    private static int classCount(IntensitySpatialConfig config) {
        int value = config == null
                ? IntensitySpatialConfig.DEFAULT_TEXTURE_CLASS_COUNT
                : config.getTextureClassCount();
        return Math.max(1, value);
    }

    private static void putNan(LinkedHashMap<String, Double> values, int k) {
        for (int i = 1; i <= k; i++) {
            values.put(column(i), Double.valueOf(Double.NaN));
        }
    }

    private static double pixelSize(ImagePlus image, boolean xAxis) {
        return CalibrationUtil.pixelSizeUm(image,
                xAxis ? CalibrationUtil.Axis.X : CalibrationUtil.Axis.Y);
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

    private static final class FeatureTable {
        final double[][] standardizedFeatures;

        private FeatureTable(double[][] standardizedFeatures) {
            this.standardizedFeatures = standardizedFeatures;
        }

        static FeatureTable from(Plane plane) throws Exception {
            ArrayImg<FloatType, FloatArray> source = ArrayImgs.floats(plane.values.clone(),
                    plane.width, plane.height);
            ArrayImg<FloatType, FloatArray> smoothSmall = ArrayImgs.floats(plane.width, plane.height);
            ArrayImg<FloatType, FloatArray> smoothLarge = ArrayImgs.floats(plane.width, plane.height);
            ArrayImg<FloatType, FloatArray> gx = ArrayImgs.floats(plane.width, plane.height);
            ArrayImg<FloatType, FloatArray> gy = ArrayImgs.floats(plane.width, plane.height);

            Gauss3.gauss(new double[]{1.0, 1.0}, Views.extendMirrorSingle(source), smoothSmall);
            Gauss3.gauss(new double[]{3.0, 3.0}, Views.extendMirrorSingle(source), smoothLarge);
            PartialDerivative.gradientCentralDifference(Views.extendMirrorSingle(smoothSmall), gx, 0);
            PartialDerivative.gradientCentralDifference(Views.extendMirrorSingle(smoothSmall), gy, 1);

            RandomAccess<FloatType> smallRa = smoothSmall.randomAccess();
            RandomAccess<FloatType> largeRa = smoothLarge.randomAccess();
            RandomAccess<FloatType> gxRa = gx.randomAccess();
            RandomAccess<FloatType> gyRa = gy.randomAccess();
            double[][] features = new double[plane.count][FEATURE_COUNT];
            int row = 0;
            for (int y = 0; y < plane.height; y++) {
                for (int x = 0; x < plane.width; x++) {
                    int index = y * plane.width + x;
                    if (!plane.valid[index]) continue;
                    smallRa.setPosition(x, 0);
                    smallRa.setPosition(y, 1);
                    largeRa.setPosition(x, 0);
                    largeRa.setPosition(y, 1);
                    gxRa.setPosition(x, 0);
                    gxRa.setPosition(y, 1);
                    gyRa.setPosition(x, 0);
                    gyRa.setPosition(y, 1);

                    double intensity = plane.values[index];
                    double small = smallRa.get().getRealDouble();
                    double large = largeRa.get().getRealDouble();
                    double dx = gxRa.get().getRealDouble() / plane.pixelWidthUm;
                    double dy = gyRa.get().getRealDouble() / plane.pixelHeightUm;
                    features[row][0] = intensity;
                    features[row][1] = small - large;
                    features[row][2] = Math.sqrt(dx * dx + dy * dy);
                    features[row][3] = Math.abs(intensity - small);
                    row++;
                }
            }
            standardize(features);
            for (double[] sample : features) {
                sample[0] *= 6.0;
                sample[1] *= 0.35;
                sample[2] *= 0.35;
                sample[3] *= 0.35;
            }
            return new FeatureTable(features);
        }

        private static void standardize(double[][] features) {
            if (features.length == 0) return;
            for (int f = 0; f < FEATURE_COUNT; f++) {
                double mean = 0.0;
                for (double[] sample : features) {
                    mean += sample[f];
                }
                mean /= features.length;
                double variance = 0.0;
                for (double[] sample : features) {
                    double delta = sample[f] - mean;
                    variance += delta * delta;
                }
                double sd = Math.sqrt(variance / Math.max(1, features.length - 1));
                if (sd <= 0.0 || Double.isNaN(sd) || Double.isInfinite(sd)) {
                    for (double[] sample : features) {
                        sample[f] = 0.0;
                    }
                } else {
                    for (double[] sample : features) {
                        sample[f] = (sample[f] - mean) / sd;
                    }
                }
            }
        }
    }

    private static final class KMeans {
        static double[] fractions(double[][] features, int activeK, int requestedK) {
            double[] fractions = new double[requestedK];
            if (features.length == 0 || activeK <= 0) {
                java.util.Arrays.fill(fractions, Double.NaN);
                return fractions;
            }
            double[][] centroids = initialCentroids(features, activeK);
            int[] assignments = new int[features.length];
            java.util.Arrays.fill(assignments, -1);

            for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
                boolean changed = assign(features, centroids, assignments);
                recompute(features, activeK, centroids, assignments);
                if (!changed && iteration > 0) break;
            }

            int[] counts = new int[activeK];
            for (int assignment : assignments) {
                if (assignment >= 0 && assignment < activeK) counts[assignment]++;
            }
            int[] order = sortedClusterOrder(centroids, activeK);
            for (int rank = 0; rank < activeK; rank++) {
                fractions[rank] = counts[order[rank]] / (double) features.length;
            }
            for (int rank = activeK; rank < requestedK; rank++) {
                fractions[rank] = 0.0;
            }
            return fractions;
        }

        private static double[][] initialCentroids(double[][] features, int k) {
            Integer[] order = new Integer[features.length];
            for (int i = 0; i < order.length; i++) order[i] = Integer.valueOf(i);
            java.util.Arrays.sort(order, new java.util.Comparator<Integer>() {
                @Override
                public int compare(Integer a, Integer b) {
                    return Double.compare(features[a.intValue()][0], features[b.intValue()][0]);
                }
            });
            double[][] centroids = new double[k][FEATURE_COUNT];
            for (int c = 0; c < k; c++) {
                int index = (int) Math.round((c + 0.5) * features.length / k - 0.5);
                index = Math.max(0, Math.min(features.length - 1, index));
                System.arraycopy(features[order[index].intValue()], 0, centroids[c], 0, FEATURE_COUNT);
            }
            return centroids;
        }

        private static boolean assign(double[][] features, double[][] centroids, int[] assignments) {
            boolean changed = false;
            for (int i = 0; i < features.length; i++) {
                int best = 0;
                double bestDistance = squaredDistance(features[i], centroids[0]);
                for (int c = 1; c < centroids.length; c++) {
                    double distance = squaredDistance(features[i], centroids[c]);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = c;
                    }
                }
                if (assignments[i] != best) {
                    assignments[i] = best;
                    changed = true;
                }
            }
            return changed;
        }

        private static void recompute(double[][] features,
                                      int k,
                                      double[][] centroids,
                                      int[] assignments) {
            double[][] sums = new double[k][FEATURE_COUNT];
            int[] counts = new int[k];
            for (int i = 0; i < features.length; i++) {
                int assignment = assignments[i];
                counts[assignment]++;
                for (int f = 0; f < FEATURE_COUNT; f++) {
                    sums[assignment][f] += features[i][f];
                }
            }
            for (int c = 0; c < k; c++) {
                if (counts[c] == 0) {
                    int replacement = Math.min(features.length - 1,
                            (int) Math.round((c + 0.5) * features.length / k - 0.5));
                    System.arraycopy(features[replacement], 0, centroids[c], 0, FEATURE_COUNT);
                    continue;
                }
                for (int f = 0; f < FEATURE_COUNT; f++) {
                    centroids[c][f] = sums[c][f] / counts[c];
                }
            }
        }

        private static int[] sortedClusterOrder(final double[][] centroids, int k) {
            Integer[] order = new Integer[k];
            for (int i = 0; i < k; i++) order[i] = Integer.valueOf(i);
            java.util.Arrays.sort(order, new java.util.Comparator<Integer>() {
                @Override
                public int compare(Integer a, Integer b) {
                    int byIntensity = Double.compare(
                            centroids[a.intValue()][0], centroids[b.intValue()][0]);
                    if (byIntensity != 0) return byIntensity;
                    return Integer.compare(a.intValue(), b.intValue());
                }
            });
            int[] out = new int[k];
            for (int i = 0; i < k; i++) out[i] = order[i].intValue();
            return out;
        }

        private static double squaredDistance(double[] a, double[] b) {
            double sum = 0.0;
            for (int i = 0; i < FEATURE_COUNT; i++) {
                double delta = a[i] - b[i];
                sum += delta * delta;
            }
            return sum;
        }
    }

    private static final class Plane {
        final int width;
        final int height;
        final float[] values;
        final boolean[] valid;
        final int count;
        final double pixelWidthUm;
        final double pixelHeightUm;

        private Plane(int width,
                      int height,
                      float[] values,
                      boolean[] valid,
                      int count,
                      double pixelWidthUm,
                      double pixelHeightUm) {
            this.width = width;
            this.height = height;
            this.values = values;
            this.valid = valid;
            this.count = count;
            this.pixelWidthUm = pixelWidthUm;
            this.pixelHeightUm = pixelHeightUm;
        }

        static Plane from(ImagePlus image, int slice, Roi roi) {
            if (image == null || image.getStackSize() < slice) {
                return empty();
            }
            Rectangle bounds = clippedBounds(image, roi);
            if (bounds.width <= 0 || bounds.height <= 0) {
                return empty();
            }
            int stepX = Math.max(1, (bounds.width + MAX_SIDE - 1) / MAX_SIDE);
            int stepY = Math.max(1, (bounds.height + MAX_SIDE - 1) / MAX_SIDE);
            int width = Math.max(1, (bounds.width + stepX - 1) / stepX);
            int height = Math.max(1, (bounds.height + stepY - 1) / stepY);
            float[] values = new float[width * height];
            boolean[] valid = new boolean[values.length];
            ImageProcessor ip = image.getStack().getProcessor(slice);
            int count = 0;
            double mean = 0.0;

            for (int yy = 0; yy < height; yy++) {
                int y0 = bounds.y + yy * stepY;
                int y1 = Math.min(bounds.y + bounds.height, y0 + stepY);
                for (int xx = 0; xx < width; xx++) {
                    int x0 = bounds.x + xx * stepX;
                    int x1 = Math.min(bounds.x + bounds.width, x0 + stepX);
                    double sum = 0.0;
                    int n = 0;
                    for (int y = y0; y < y1; y++) {
                        for (int x = x0; x < x1; x++) {
                            if (roi != null && !roi.contains(x, y)) continue;
                            double value = ip.getf(x, y);
                            if (Double.isNaN(value) || Double.isInfinite(value)) continue;
                            sum += value;
                            n++;
                        }
                    }
                    if (n == 0) continue;
                    float blockMean = (float) (sum / n);
                    int index = yy * width + xx;
                    values[index] = blockMean;
                    valid[index] = true;
                    count++;
                    mean += (blockMean - mean) / count;
                }
            }

            for (int i = 0; i < values.length; i++) {
                if (!valid[i]) {
                    values[i] = (float) mean;
                }
            }
            return new Plane(width, height, values, valid, count,
                    pixelSize(image, true) * stepX,
                    pixelSize(image, false) * stepY);
        }

        private static Plane empty() {
            return new Plane(0, 0, new float[0], new boolean[0], 0, 1.0, 1.0);
        }
    }
}
