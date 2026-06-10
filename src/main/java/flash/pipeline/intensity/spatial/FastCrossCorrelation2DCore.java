package flash.pipeline.intensity.spatial;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Coloc2-free Pearson and CCF helpers shared by fast and full 2D cross-channel analysis.
 */
final class FastCrossCorrelation2DCore {
    private static final int MAX_SPATIAL_CORRELATION_SAMPLES = 32_768;
    private static final int MAX_SHIFT_PIXELS = 12;

    private FastCrossCorrelation2DCore() {
    }

    static List<String> columns(String sourceChannel, String partnerChannel) {
        ArrayList<String> columns = new ArrayList<String>();
        columns.add(column(sourceChannel, "Pearson", partnerChannel));
        columns.add(column(sourceChannel, "CCFPeakDist_um", partnerChannel));
        columns.add(column(sourceChannel, "CCFPeakAmp", partnerChannel));
        return columns;
    }

    static LinkedHashMap<String, Double> values(PairPlane2D plane,
                                                String source,
                                                String partner) {
        LinkedHashMap<String, Double> values = new LinkedHashMap<String, Double>();
        values.put(column(source, "Pearson", partner), Double.valueOf(directPearson(plane)));
        Peak ccf = ccfPeak(plane);
        values.put(column(source, "CCFPeakDist_um", partner), Double.valueOf(ccf.radiusUm));
        values.put(column(source, "CCFPeakAmp", partner), Double.valueOf(ccf.strength));
        return values;
    }

    static double directPearson(PairPlane2D plane) {
        return shiftedPearson(plane, 0, 0, sampleStep(plane.count));
    }

    static Peak ccfPeak(PairPlane2D plane) {
        int maxShift = Math.min(MAX_SHIFT_PIXELS, Math.max(0, Math.min(plane.width, plane.height) / 4));
        int sampleStep = sampleStep(plane.count);
        double best = Double.NEGATIVE_INFINITY;
        int bestDx = 0;
        int bestDy = 0;
        for (int dy = -maxShift; dy <= maxShift; dy++) {
            for (int dx = -maxShift; dx <= maxShift; dx++) {
                double r = shiftedPearson(plane, dx, dy, sampleStep);
                if (Double.isNaN(r) || Double.isInfinite(r)) continue;
                if (r > best) {
                    best = r;
                    bestDx = dx;
                    bestDy = dy;
                }
            }
        }
        if (best == Double.NEGATIVE_INFINITY) return Peak.nan();
        return new Peak(distanceUm(bestDx, bestDy, plane), best);
    }

    private static double shiftedPearson(PairPlane2D plane, int dx, int dy, int sampleStep) {
        double sumA = 0.0;
        double sumB = 0.0;
        double sumAA = 0.0;
        double sumBB = 0.0;
        double sumAB = 0.0;
        int n = 0;
        int step = Math.max(1, sampleStep);
        int start = step == 1 ? 0 : step / 2;
        for (int y = start; y < plane.height; y += step) {
            int yy = y + dy;
            if (yy < 0 || yy >= plane.height) continue;
            for (int x = start; x < plane.width; x += step) {
                int xx = x + dx;
                if (xx < 0 || xx >= plane.width) continue;
                int sourceIndex = y * plane.width + x;
                int partnerIndex = yy * plane.width + xx;
                if (!plane.valid[sourceIndex] || !plane.valid[partnerIndex]) continue;
                double a = plane.source[sourceIndex];
                double b = plane.partner[partnerIndex];
                sumA += a;
                sumB += b;
                sumAA += a * a;
                sumBB += b * b;
                sumAB += a * b;
                n++;
            }
        }
        if (n < 2 && step > 1) {
            return shiftedPearson(plane, dx, dy, 1);
        }
        if (n < 2) return Double.NaN;
        double cov = sumAB - (sumA * sumB / n);
        double varA = sumAA - (sumA * sumA / n);
        double varB = sumBB - (sumB * sumB / n);
        double denom = Math.sqrt(varA * varB);
        return denom <= 0.0 || !PairPlane2D.isFinite(denom) ? Double.NaN : cov / denom;
    }

    private static int sampleStep(int validPixels) {
        if (validPixels <= MAX_SPATIAL_CORRELATION_SAMPLES) {
            return 1;
        }
        return Math.max(1, (int) Math.ceil(Math.sqrt(
                validPixels / (double) MAX_SPATIAL_CORRELATION_SAMPLES)));
    }

    private static double distanceUm(int dx, int dy, PairPlane2D plane) {
        double x = dx * plane.pixelWidthUm;
        double y = dy * plane.pixelHeightUm;
        return Math.sqrt(x * x + y * y);
    }

    private static String column(String source, String token, String partner) {
        return source + "_" + token + "_" + partner;
    }

    static final class Peak {
        final double radiusUm;
        final double strength;

        private Peak(double radiusUm, double strength) {
            this.radiusUm = radiusUm;
            this.strength = strength;
        }

        static Peak nan() {
            return new Peak(Double.NaN, Double.NaN);
        }
    }
}
