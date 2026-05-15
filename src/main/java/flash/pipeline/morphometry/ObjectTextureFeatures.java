package flash.pipeline.morphometry;

import org.apache.commons.math3.ml.clustering.CentroidCluster;
import org.apache.commons.math3.ml.clustering.DoublePoint;
import org.apache.commons.math3.ml.clustering.KMeansPlusPlusClusterer;
import org.apache.commons.math3.ml.distance.EuclideanDistance;
import org.apache.commons.math3.random.JDKRandomGenerator;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Gabor and wavelet object texture features plus deterministic k-means helpers.
 */
public final class ObjectTextureFeatures {
    public static final int DEFAULT_GABOR_ORIENTATIONS = 4;
    public static final int DEFAULT_WAVELET_SCALES = 4;
    public static final int DEFAULT_FEATURE_DIM = 8;
    public static final int DEFAULT_K = 4;

    private static final long KMEANS_SEED = 3405691582L;
    private static final int KMEANS_MAX_ITERATIONS = 100;
    private static final double[] ATROUS_KERNEL = {1.0 / 16.0, 4.0 / 16.0, 6.0 / 16.0, 4.0 / 16.0, 1.0 / 16.0};

    private ObjectTextureFeatures() {
    }

    public static final class FeatureVector {
        public final float[] features;
        public final boolean valid;
        public final boolean reliable;

        public FeatureVector(float[] features, boolean valid, boolean reliable) {
            this.features = features;
            this.valid = valid;
            this.reliable = reliable;
        }
    }

    public static final class ClassAssignment {
        public final int classLabel;
        public final double classDistance;

        public ClassAssignment(int classLabel, double classDistance) {
            this.classLabel = classLabel;
            this.classDistance = classDistance;
        }
    }

    public static FeatureVector computeFeatures(ObjectPatch patch) {
        return computeFeatures(patch, DEFAULT_GABOR_ORIENTATIONS, DEFAULT_WAVELET_SCALES);
    }

    static FeatureVector computeFeatures(ObjectPatch patch, int gaborOrientations, int waveletScales) {
        if (patch == null) {
            throw new IllegalArgumentException("patch must not be null");
        }
        if (gaborOrientations <= 0) {
            throw new IllegalArgumentException("gaborOrientations must be positive");
        }
        if (waveletScales <= 0) {
            throw new IllegalArgumentException("waveletScales must be positive");
        }

        MaskStats stats = maskStats(patch);
        int featureDim = gaborOrientations + waveletScales;
        float[] features = new float[featureDim];
        if (stats.count == 0) {
            return new FeatureVector(features, false, false);
        }

        double[] centered = centeredImage(patch, stats.mean);
        GaborSpec gabor = gaborSpec(patch.width, patch.height);
        double[][] kernels = gaborKernels(gaborOrientations, gabor);
        for (int i = 0; i < gaborOrientations; i++) {
            features[i] = (float) maskedMeanAbsConvolution(centered, patch.mask,
                    patch.width, patch.height, kernels[i], gabor.kernelSize, stats.count);
        }

        double[] current = Arrays.copyOf(centered, centered.length);
        for (int scale = 0; scale < waveletScales; scale++) {
            int step = 1 << scale;
            double[] smooth = atrousSmooth(current, patch.width, patch.height, step);
            double energy = 0.0;
            for (int i = 0; i < current.length; i++) {
                if (patch.mask[i] == 0) continue;
                double coeff = current[i] - smooth[i];
                energy += coeff * coeff;
            }
            features[gaborOrientations + scale] = (float) (energy / stats.count);
            current = smooth;
        }

        boolean reliable = stats.count >= 16
                && patch.width >= gabor.kernelSize
                && patch.height >= gabor.kernelSize;
        return new FeatureVector(features, true, reliable);
    }

    public static ClassAssignment assignToCentroids(FeatureVector vec, double[][] centroids) {
        if (vec == null || vec.features == null || vec.features.length == 0) {
            throw new IllegalArgumentException("feature vector must not be empty");
        }
        if (centroids == null || centroids.length == 0) {
            throw new IllegalArgumentException("centroids must not be empty");
        }
        int best = -1;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int c = 0; c < centroids.length; c++) {
            if (centroids[c] == null || centroids[c].length != vec.features.length) {
                throw new IllegalArgumentException("centroid dimensionality mismatch");
            }
            double distance = euclidean(vec.features, centroids[c]);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = c;
            }
        }
        return new ClassAssignment(best, bestDistance);
    }

    public static double[][] fitCentroids(List<FeatureVector> all, int k) {
        if (all == null) {
            throw new IllegalArgumentException("feature vectors must not be null");
        }
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive");
        }

        List<double[]> vectors = usableVectors(all);
        if (vectors.isEmpty()) {
            return new double[0][0];
        }
        int dim = vectors.get(0).length;
        while (vectors.size() < k) {
            vectors.add(Arrays.copyOf(vectors.get(vectors.size() - 1), dim));
        }

        List<DoublePoint> points = new ArrayList<DoublePoint>();
        for (int i = 0; i < vectors.size(); i++) {
            points.add(new DoublePoint(vectors.get(i)));
        }

        JDKRandomGenerator random = new JDKRandomGenerator();
        random.setSeed(KMEANS_SEED);
        KMeansPlusPlusClusterer<DoublePoint> clusterer =
                new KMeansPlusPlusClusterer<DoublePoint>(
                        k, KMEANS_MAX_ITERATIONS, new EuclideanDistance(), random);
        List<CentroidCluster<DoublePoint>> clusters = clusterer.cluster(points);
        List<double[]> centroids = new ArrayList<double[]>();
        for (int i = 0; i < clusters.size(); i++) {
            double[] center = clusters.get(i).getCenter().getPoint();
            centroids.add(Arrays.copyOf(center, center.length));
        }
        Collections.sort(centroids, new LexicographicDoubleArrayComparator());
        return centroids.toArray(new double[centroids.size()][]);
    }

    private static List<double[]> usableVectors(List<FeatureVector> all) {
        List<double[]> vectors = new ArrayList<double[]>();
        int dim = -1;
        for (int i = 0; i < all.size(); i++) {
            FeatureVector vector = all.get(i);
            if (vector == null || !vector.valid || vector.features == null) continue;
            if (!allFinite(vector.features)) continue;
            if (dim < 0) {
                dim = vector.features.length;
            } else if (vector.features.length != dim) {
                throw new IllegalArgumentException("feature dimensionality mismatch");
            }
            double[] copy = new double[vector.features.length];
            for (int f = 0; f < vector.features.length; f++) {
                copy[f] = vector.features[f];
            }
            vectors.add(copy);
        }
        return vectors;
    }

    private static boolean allFinite(float[] values) {
        for (int i = 0; i < values.length; i++) {
            if (!isFinite(values[i])) return false;
        }
        return true;
    }

    private static MaskStats maskStats(ObjectPatch patch) {
        int count = 0;
        double sum = 0.0;
        for (int i = 0; i < patch.intensity.length; i++) {
            if (patch.mask[i] == 0) continue;
            float value = patch.intensity[i];
            if (!isFinite(value)) continue;
            sum += value;
            count++;
        }
        return new MaskStats(count, count == 0 ? Double.NaN : sum / count);
    }

    private static double[] centeredImage(ObjectPatch patch, double maskMean) {
        double[] centered = new double[patch.intensity.length];
        double mean = isFinite(maskMean) ? maskMean : 0.0;
        for (int i = 0; i < patch.intensity.length; i++) {
            float value = patch.intensity[i];
            centered[i] = isFinite(value) ? value - mean : 0.0;
        }
        return centered;
    }

    private static GaborSpec gaborSpec(int width, int height) {
        int shortSide = Math.max(1, Math.min(width, height));
        double wavelength = Math.max(4.0, Math.min(16.0, shortSide / 4.0));
        double sigma = Math.max(2.0, wavelength / 2.0);
        int radius = Math.max(2, (int) Math.ceil(3.0 * sigma));
        return new GaborSpec(wavelength, sigma, 2 * radius + 1);
    }

    private static double[][] gaborKernels(int orientations, GaborSpec spec) {
        double[][] kernels = new double[orientations][];
        for (int i = 0; i < orientations; i++) {
            double theta = Math.PI * i / orientations;
            kernels[i] = gaborKernel(theta, spec);
        }
        return kernels;
    }

    private static double[] gaborKernel(double theta, GaborSpec spec) {
        int size = spec.kernelSize;
        int radius = size / 2;
        double[] kernel = new double[size * size];
        double gamma = 0.5;
        double sum = 0.0;
        for (int y = -radius; y <= radius; y++) {
            for (int x = -radius; x <= radius; x++) {
                double xr = x * Math.cos(theta) + y * Math.sin(theta);
                double yr = -x * Math.sin(theta) + y * Math.cos(theta);
                double envelope = Math.exp(-(xr * xr + gamma * gamma * yr * yr)
                        / (2.0 * spec.sigma * spec.sigma));
                double carrier = Math.cos(2.0 * Math.PI * xr / spec.wavelength);
                double value = envelope * carrier;
                kernel[(y + radius) * size + (x + radius)] = value;
                sum += value;
            }
        }
        double mean = sum / kernel.length;
        double norm = 0.0;
        for (int i = 0; i < kernel.length; i++) {
            kernel[i] -= mean;
            norm += Math.abs(kernel[i]);
        }
        if (norm <= 0.0) norm = 1.0;
        for (int i = 0; i < kernel.length; i++) {
            kernel[i] /= norm;
        }
        return kernel;
    }

    private static double maskedMeanAbsConvolution(double[] image,
                                                   byte[] mask,
                                                   int width,
                                                   int height,
                                                   double[] kernel,
                                                   int kernelSize,
                                                   int maskCount) {
        int radius = kernelSize / 2;
        double sum = 0.0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int center = y * width + x;
                if (mask[center] == 0) continue;
                double response = 0.0;
                for (int ky = -radius; ky <= radius; ky++) {
                    int yy = y + ky;
                    if (yy < 0 || yy >= height) continue;
                    for (int kx = -radius; kx <= radius; kx++) {
                        int xx = x + kx;
                        if (xx < 0 || xx >= width) continue;
                        double kval = kernel[(ky + radius) * kernelSize + (kx + radius)];
                        response += image[yy * width + xx] * kval;
                    }
                }
                sum += Math.abs(response);
            }
        }
        return maskCount == 0 ? Double.NaN : sum / maskCount;
    }

    private static double[] atrousSmooth(double[] image, int width, int height, int step) {
        double[] temp = new double[image.length];
        double[] output = new double[image.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double sum = 0.0;
                for (int k = -2; k <= 2; k++) {
                    int xx = clamp(x + k * step, 0, width - 1);
                    sum += image[y * width + xx] * ATROUS_KERNEL[k + 2];
                }
                temp[y * width + x] = sum;
            }
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double sum = 0.0;
                for (int k = -2; k <= 2; k++) {
                    int yy = clamp(y + k * step, 0, height - 1);
                    sum += temp[yy * width + x] * ATROUS_KERNEL[k + 2];
                }
                output[y * width + x] = sum;
            }
        }
        return output;
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private static double euclidean(float[] features, double[] centroid) {
        double sum = 0.0;
        for (int i = 0; i < features.length; i++) {
            double delta = features[i] - centroid[i];
            sum += delta * delta;
        }
        return Math.sqrt(sum);
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    public static final class CentroidsIO {
        private CentroidsIO() {
        }

        public static double[][] load(File centroidFile, int expectedFeatureDim) throws IOException {
            if (centroidFile == null || !centroidFile.isFile()) {
                return null;
            }
            if (expectedFeatureDim <= 0) {
                throw new IllegalArgumentException("expectedFeatureDim must be positive");
            }
            List<double[]> rows = new ArrayList<double[]>();
            BufferedReader reader = new BufferedReader(new FileReader(centroidFile));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    String[] parts = line.split("\\s+");
                    if (parts.length != expectedFeatureDim) {
                        return null;
                    }
                    double[] row = new double[parts.length];
                    for (int i = 0; i < parts.length; i++) {
                        row[i] = Double.parseDouble(parts[i]);
                        if (!isFinite(row[i])) return null;
                    }
                    rows.add(row);
                }
            } finally {
                reader.close();
            }
            if (rows.isEmpty()) return null;
            return rows.toArray(new double[rows.size()][]);
        }

        public static void save(File centroidFile, double[][] centroids) throws IOException {
            if (centroidFile == null) {
                throw new IllegalArgumentException("centroidFile must not be null");
            }
            if (centroids == null) {
                throw new IllegalArgumentException("centroids must not be null");
            }
            File parent = centroidFile.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Could not create centroid directory: " + parent);
            }
            BufferedWriter writer = new BufferedWriter(new FileWriter(centroidFile));
            try {
                for (int r = 0; r < centroids.length; r++) {
                    if (centroids[r] == null) {
                        throw new IllegalArgumentException("centroid row must not be null");
                    }
                    for (int c = 0; c < centroids[r].length; c++) {
                        if (c > 0) writer.write(' ');
                        writer.write(String.format(Locale.ROOT, "%.12g", centroids[r][c]));
                    }
                    writer.newLine();
                }
            } finally {
                writer.close();
            }
        }
    }

    private static final class MaskStats {
        final int count;
        final double mean;

        private MaskStats(int count, double mean) {
            this.count = count;
            this.mean = mean;
        }
    }

    private static final class GaborSpec {
        final double wavelength;
        final double sigma;
        final int kernelSize;

        private GaborSpec(double wavelength, double sigma, int kernelSize) {
            this.wavelength = wavelength;
            this.sigma = sigma;
            this.kernelSize = kernelSize;
        }
    }

    private static final class LexicographicDoubleArrayComparator implements Comparator<double[]> {
        @Override
        public int compare(double[] left, double[] right) {
            int n = Math.min(left.length, right.length);
            for (int i = 0; i < n; i++) {
                int cmp = Double.compare(left[i], right[i]);
                if (cmp != 0) return cmp;
            }
            return Integer.compare(left.length, right.length);
        }
    }
}
