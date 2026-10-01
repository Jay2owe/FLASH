package flash.pipeline.intensity.spatial;

import java.util.Arrays;

/** Exact separable squared-distance transform, with independently calibrated axes. */
final class ExactEuclideanDistance {
    private ExactEuclideanDistance() {}

    static double[] toMask(boolean[] mask, boolean[] valid, int width, int height,
                           int depth, double sx, double sy, double sz) {
        long size = (long) width * height * depth;
        if (width <= 0 || height <= 0 || depth <= 0 || size > Integer.MAX_VALUE
                || mask == null || valid == null || mask.length != size || valid.length != size) {
            throw new IllegalArgumentException("Distance-transform dimensions must match mask and validity arrays.");
        }
        double[] distances = new double[(int) size];
        if (!positiveFinite(sx) || !positiveFinite(sy) || (depth > 1 && !positiveFinite(sz))) {
            Arrays.fill(distances, Double.NaN);
            return distances;
        }
        // Squaring an otherwise valid physical scale can overflow or underflow.
        // Work in a common dimensionless scale and restore physical units at the end.
        double physicalScale = Math.max(sx, sy);
        if (depth > 1) physicalScale = Math.max(physicalScale, sz);
        sx /= physicalScale;
        sy /= physicalScale;
        if (depth > 1) sz /= physicalScale;
        if (!positiveFinite(sx * sx) || !positiveFinite(sy * sy)
                || (depth > 1 && !positiveFinite(sz * sz))) {
            Arrays.fill(distances, Double.NaN);
            return distances;
        }
        for (int i = 0; i < distances.length; i++) {
            distances[i] = mask[i] && valid[i] ? 0.0 : Double.POSITIVE_INFINITY;
        }
        int longest = Math.max(width, Math.max(height, depth));
        double[] input = new double[longest];
        double[] output = new double[longest];
        int[] sites = new int[longest];
        double[] starts = new double[longest];
        int slice = width * height;
        for (int z = 0; z < depth; z++) {
            for (int y = 0; y < height; y++) {
                transformLine(distances, z * slice + y * width, 1, width, sx,
                        input, output, sites, starts);
            }
        }
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                transformLine(distances, z * slice + x, width, height, sy,
                        input, output, sites, starts);
            }
        }
        if (depth > 1) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    transformLine(distances, y * width + x, slice, depth, sz,
                            input, output, sites, starts);
                }
            }
        }
        for (int i = 0; i < distances.length; i++) {
            distances[i] = Math.sqrt(distances[i]) * physicalScale;
        }
        return distances;
    }

    private static boolean positiveFinite(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    // Each finite input defines a parabola f[q] + spacing^2 * (p-q)^2.
    // Keep its lower envelope, then evaluate every output in linear time.
    // Geometry follows Felzenszwalb & Huttenlocher (2012), doi:10.4086/toc.2012.v008a019.
    private static void transformLine(double[] distances, int start, int stride, int length,
                                      double spacing, double[] input, double[] output,
                                      int[] sites, double[] starts) {
        for (int i = 0; i < length; i++) input[i] = distances[start + i * stride];
        double weight = spacing * spacing;
        int last = -1;
        for (int q = 0; q < length; q++) {
            if (!Double.isFinite(input[q])) continue;
            double intersection = Double.NEGATIVE_INFINITY;
            while (last >= 0) {
                int previous = sites[last];
                intersection = (input[q] - input[previous]) / (2.0 * weight * (q - previous))
                        + (q + (double) previous) / 2.0;
                if (intersection > starts[last]) break;
                last--;
            }
            last++;
            sites[last] = q;
            starts[last] = last == 0 ? Double.NEGATIVE_INFINITY : intersection;
        }
        if (last < 0) {
            Arrays.fill(output, 0, length, Double.POSITIVE_INFINITY);
        } else {
            int envelope = 0;
            for (int p = 0; p < length; p++) {
                while (envelope < last && starts[envelope + 1] < p) envelope++;
                double delta = p - (double) sites[envelope];
                output[p] = input[sites[envelope]] + weight * delta * delta;
            }
        }
        for (int i = 0; i < length; i++) distances[start + i * stride] = output[i];
    }
}
