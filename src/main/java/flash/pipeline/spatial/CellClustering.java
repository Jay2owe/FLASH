package flash.pipeline.spatial;

import java.util.Arrays;
import java.util.Random;

/**
 * K-means++ clustering on per-object feature vectors.
 *
 * <p>Pure Java 8 implementation — no Apache Commons Math dependency.
 * Features can include volume, surface, intensity, colocalization values,
 * or any numeric per-object columns from the object CSVs.
 *
 * <p>Supports automatic cluster count detection via silhouette score
 * (sweeps k=2..maxK, picks the k with highest mean silhouette).
 *
 * <p>No ImageJ dependencies.
 */
public final class CellClustering {

    private CellClustering() {}

    private static final int DEFAULT_MAX_ITER = 300;

    /** Clustering result. */
    public static final class ClusterResult {
        /** Cluster assignment per object (0-based). */
        public final int[] assignments;
        /** Cluster centroids [k][nFeatures]. */
        public final double[][] centroids;
        /** Number of clusters. */
        public final int k;
        /** Mean silhouette score (-1 to 1). */
        public final double silhouetteScore;

        public ClusterResult(int[] assignments, double[][] centroids, int k, double silhouetteScore) {
            this.assignments = assignments;
            this.centroids = centroids;
            this.k = k;
            this.silhouetteScore = silhouetteScore;
        }
    }

    /**
     * Runs k-means++ clustering with a fixed k.
     *
     * @param features [n][d] feature matrix (will be z-score normalized internally)
     * @param k        number of clusters
     * @param seed     random seed
     * @return clustering result
     */
    public static ClusterResult cluster(double[][] features, int k, long seed) {
        if (features == null || features.length == 0 || k <= 0) {
            return new ClusterResult(new int[0], new double[0][0], 0, 0);
        }
        int n = features.length;
        int d = features[0] == null ? 0 : features[0].length;
        if (d == 0) {
            return new ClusterResult(new int[n], new double[0][0], 0, 0);
        }
        k = Math.min(k, n);

        // Z-score normalize
        double[][] norm = zScoreNormalize(features);

        // K-means++ initialization
        Random rng = new Random(seed);
        double[][] centers = kMeansPPInit(norm, k, rng);

        // Iterate
        int[] assignments = new int[n];
        for (int iter = 0; iter < DEFAULT_MAX_ITER; iter++) {
            // Assign
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                int best = nearest(norm[i], centers);
                if (best != assignments[i]) {
                    assignments[i] = best;
                    changed = true;
                }
            }
            if (!changed && iter > 0) break;

            // Update centers
            double[][] sums = new double[k][d];
            int[] counts = new int[k];
            for (int i = 0; i < n; i++) {
                int c = assignments[i];
                counts[c]++;
                for (int j = 0; j < d; j++) sums[c][j] += norm[i][j];
            }
            for (int c = 0; c < k; c++) {
                if (counts[c] == 0) continue;
                for (int j = 0; j < d; j++) centers[c][j] = sums[c][j] / counts[c];
            }
        }

        double sil = silhouetteScore(norm, assignments, k);
        return new ClusterResult(assignments, centers, k, sil);
    }

    /**
     * Automatically detects the optimal k by maximizing the mean silhouette
     * score over the range [minK, maxK].
     *
     * @param features [n][d] feature matrix
     * @param minK     minimum k to try (typically 2)
     * @param maxK     maximum k to try (typically 10)
     * @param seed     random seed
     * @return clustering result with the best k
     */
    public static ClusterResult autoCluster(double[][] features, int minK, int maxK, long seed) {
        if (features == null || features.length < 2) {
            return new ClusterResult(new int[features == null ? 0 : features.length],
                    new double[0][0], 1, 0);
        }

        minK = Math.max(2, minK);
        maxK = Math.min(maxK, features.length);
        if (maxK < minK) maxK = minK;

        ClusterResult best = null;
        for (int k = minK; k <= maxK; k++) {
            ClusterResult result = cluster(features, k, seed + k);
            if (best == null || result.silhouetteScore > best.silhouetteScore) {
                best = result;
            }
        }
        return best;
    }

    /**
     * Computes mean silhouette score for a given clustering.
     *
     * @param features    [n][d] normalized feature matrix
     * @param assignments cluster assignment per object
     * @param k           number of clusters
     * @return mean silhouette (-1 to 1), 0 if k < 2 or n < 2
     */
    public static double silhouetteScore(double[][] features, int[] assignments, int k) {
        int n = features.length;
        if (n < 2 || k < 2) return 0;

        double totalSil = 0;
        for (int i = 0; i < n; i++) {
            int ci = assignments[i];

            // a(i) = mean distance to same-cluster points
            double sumA = 0;
            int countA = 0;
            for (int j = 0; j < n; j++) {
                if (j == i) continue;
                if (assignments[j] == ci) {
                    sumA += euclidean(features[i], features[j]);
                    countA++;
                }
            }
            double a = countA > 0 ? sumA / countA : 0;

            // b(i) = min over other clusters of mean distance
            double b = Double.MAX_VALUE;
            for (int c = 0; c < k; c++) {
                if (c == ci) continue;
                double sumB = 0;
                int countB = 0;
                for (int j = 0; j < n; j++) {
                    if (assignments[j] == c) {
                        sumB += euclidean(features[i], features[j]);
                        countB++;
                    }
                }
                if (countB > 0) {
                    double meanB = sumB / countB;
                    if (meanB < b) b = meanB;
                }
            }

            double sil = 0;
            double denom = Math.max(a, b);
            if (denom > 0) sil = (b - a) / denom;
            totalSil += sil;
        }
        return totalSil / n;
    }

    // ── Internal ──────────────────────────────────────────────────

    private static double[][] kMeansPPInit(double[][] data, int k, Random rng) {
        int n = data.length;
        int d = data[0].length;
        double[][] centers = new double[k][d];

        // First center: random point
        int first = rng.nextInt(n);
        System.arraycopy(data[first], 0, centers[0], 0, d);

        double[] minDist = new double[n];
        Arrays.fill(minDist, Double.MAX_VALUE);

        for (int c = 1; c < k; c++) {
            // Update min distances
            double totalDist = 0;
            for (int i = 0; i < n; i++) {
                double dist = euclideanSq(data[i], centers[c - 1]);
                if (dist < minDist[i]) minDist[i] = dist;
                totalDist += minDist[i];
            }

            // Weighted random selection
            double target = rng.nextDouble() * totalDist;
            double cumulative = 0;
            int selected = n - 1;
            for (int i = 0; i < n; i++) {
                cumulative += minDist[i];
                if (cumulative >= target) {
                    selected = i;
                    break;
                }
            }
            System.arraycopy(data[selected], 0, centers[c], 0, d);
        }
        return centers;
    }

    private static int nearest(double[] point, double[][] centers) {
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int c = 0; c < centers.length; c++) {
            double d = euclideanSq(point, centers[c]);
            if (d < bestDist) {
                bestDist = d;
                best = c;
            }
        }
        return best;
    }

    private static double[][] zScoreNormalize(double[][] features) {
        int n = features.length;
        int d = features[0].length;
        double[][] result = new double[n][d];

        for (int j = 0; j < d; j++) {
            double sum = 0, sumSq = 0;
            int count = 0;
            for (int i = 0; i < n; i++) {
                double value = valueAt(features, i, j);
                if (!Double.isFinite(value)) continue;
                sum += value;
                sumSq += value * value;
                count++;
            }
            double mean = count == 0 ? 0.0 : sum / count;
            double std = count == 0 ? 1.0 : Math.sqrt(Math.max(0.0, sumSq / count - mean * mean));
            if (!Double.isFinite(std) || std < 1e-10) std = 1.0;

            for (int i = 0; i < n; i++) {
                double value = valueAt(features, i, j);
                result[i][j] = Double.isFinite(value) ? (value - mean) / std : 0.0;
            }
        }
        return result;
    }

    private static double valueAt(double[][] features, int row, int col) {
        if (features[row] == null || col >= features[row].length) return Double.NaN;
        return features[row][col];
    }

    private static double euclidean(double[] a, double[] b) {
        return Math.sqrt(euclideanSq(a, b));
    }

    private static double euclideanSq(double[] a, double[] b) {
        double sum = 0;
        int dims = Math.min(a.length, b.length);
        for (int d = 0; d < dims; d++) {
            double diff = a[d] - b[d];
            sum += diff * diff;
        }
        return sum;
    }
}
