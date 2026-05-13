package flash.pipeline.intensity.spatial;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 2D normalized mutual information and shifted spatial-MI peak metrics.
 */
public final class EntropyMiAnalysis implements IntensitySpatialPairAnalysis {
    private static final int HISTOGRAM_BINS = 32;
    private static final int MAX_SHIFT_PIXELS = 12;

    @Override
    public IntensitySpatialConfig.AnalysisKey key() {
        return IntensitySpatialConfig.AnalysisKey.ENTROPY_MI;
    }

    @Override
    public EnumSet<IntensitySpatialOutputMode> outputModes() {
        return EnumSet.of(IntensitySpatialOutputMode.BASE, IntensitySpatialOutputMode.MIP);
    }

    @Override
    public List<String> columns(IntensitySpatialConfig config,
                                String sourceChannel,
                                String partnerChannel,
                                boolean sourceBinarized,
                                boolean partnerBinarized) {
        ArrayList<String> columns = new ArrayList<String>();
        columns.add(column(sourceChannel, "NMI", partnerChannel));
        columns.add(column(sourceChannel, "MIPeakRadius_um", partnerChannel));
        columns.add(column(sourceChannel, "MIPeakStrength", partnerChannel));
        return columns;
    }

    @Override
    public int estimatedCost() {
        return 6;
    }

    @Override
    public IntensitySpatialResult measure(IntensitySpatialPairContext context) {
        PairPlane2D plane = PairPlane2D.raw(context);
        LinkedHashMap<String, Double> values = new LinkedHashMap<String, Double>();
        String source = context.sourceChannelName();
        String partner = context.partnerChannelName();
        if (plane.count < 4) {
            context.warn("mutual information has insufficient valid ROI pixels; returning NaN.");
            values.put(column(source, "NMI", partner), Double.valueOf(Double.NaN));
            values.put(column(source, "MIPeakRadius_um", partner), Double.valueOf(Double.NaN));
            values.put(column(source, "MIPeakStrength", partner), Double.valueOf(Double.NaN));
            return new IntensitySpatialResult(values);
        }

        PairPlane2D.ShiftedSamples zero = plane.shiftedSamples(0, 0);
        values.put(column(source, "NMI", partner),
                Double.valueOf(normalizedMutualInformation(zero.source, zero.partner, zero.count)));

        Peak peak = shiftedMiPeak(plane);
        values.put(column(source, "MIPeakRadius_um", partner), Double.valueOf(peak.radiusUm));
        values.put(column(source, "MIPeakStrength", partner), Double.valueOf(peak.strength));
        return new IntensitySpatialResult(values);
    }

    private static Peak shiftedMiPeak(PairPlane2D plane) {
        int maxShift = Math.min(MAX_SHIFT_PIXELS, Math.max(0, Math.min(plane.width, plane.height) / 4));
        double best = Double.NEGATIVE_INFINITY;
        int bestDx = 0;
        int bestDy = 0;
        for (int dy = -maxShift; dy <= maxShift; dy++) {
            for (int dx = -maxShift; dx <= maxShift; dx++) {
                PairPlane2D.ShiftedSamples samples = plane.shiftedSamples(dx, dy);
                if (samples.count < 4) continue;
                double nmi = normalizedMutualInformation(samples.source, samples.partner, samples.count);
                if (Double.isNaN(nmi) || Double.isInfinite(nmi)) continue;
                if (nmi > best) {
                    best = nmi;
                    bestDx = dx;
                    bestDy = dy;
                }
            }
        }
        if (best == Double.NEGATIVE_INFINITY) return Peak.nan();
        return new Peak(distanceUm(bestDx, bestDy, plane), best);
    }

    static double normalizedMutualInformation(double[] source, double[] partner, int count) {
        if (source == null || partner == null || count < 4) return Double.NaN;
        double minA = Double.POSITIVE_INFINITY;
        double maxA = Double.NEGATIVE_INFINITY;
        double minB = Double.POSITIVE_INFINITY;
        double maxB = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            double a = source[i];
            double b = partner[i];
            if (!PairPlane2D.isFinite(a) || !PairPlane2D.isFinite(b)) continue;
            if (a < minA) minA = a;
            if (a > maxA) maxA = a;
            if (b < minB) minB = b;
            if (b > maxB) maxB = b;
        }
        if (!PairPlane2D.isFinite(minA) || !PairPlane2D.isFinite(minB)
                || maxA <= minA || maxB <= minB) {
            return 0.0;
        }

        int[][] joint = new int[HISTOGRAM_BINS][HISTOGRAM_BINS];
        int[] histA = new int[HISTOGRAM_BINS];
        int[] histB = new int[HISTOGRAM_BINS];
        int n = 0;
        for (int i = 0; i < count; i++) {
            double a = source[i];
            double b = partner[i];
            if (!PairPlane2D.isFinite(a) || !PairPlane2D.isFinite(b)) continue;
            int ai = bin(a, minA, maxA);
            int bi = bin(b, minB, maxB);
            joint[ai][bi]++;
            histA[ai]++;
            histB[bi]++;
            n++;
        }
        if (n == 0) return Double.NaN;

        double mi = 0.0;
        double hA = 0.0;
        double hB = 0.0;
        for (int i = 0; i < HISTOGRAM_BINS; i++) {
            if (histA[i] > 0) {
                double p = histA[i] / (double) n;
                hA -= p * Math.log(p);
            }
            if (histB[i] > 0) {
                double p = histB[i] / (double) n;
                hB -= p * Math.log(p);
            }
        }
        for (int i = 0; i < HISTOGRAM_BINS; i++) {
            for (int j = 0; j < HISTOGRAM_BINS; j++) {
                int countIJ = joint[i][j];
                if (countIJ == 0) continue;
                double pxy = countIJ / (double) n;
                double px = histA[i] / (double) n;
                double py = histB[j] / (double) n;
                mi += pxy * Math.log(pxy / (px * py));
            }
        }
        double denom = hA + hB;
        if (denom <= 0.0 || !PairPlane2D.isFinite(denom)) return 0.0;
        double nmi = 2.0 * mi / denom;
        if (nmi < 0.0 && nmi > -1e-12) return 0.0;
        return Math.max(0.0, Math.min(1.0, nmi));
    }

    private static int bin(double value, double min, double max) {
        double fraction = (value - min) / (max - min);
        int index = (int) Math.floor(fraction * HISTOGRAM_BINS);
        if (index < 0) return 0;
        if (index >= HISTOGRAM_BINS) return HISTOGRAM_BINS - 1;
        return index;
    }

    private static double distanceUm(int dx, int dy, PairPlane2D plane) {
        double x = dx * plane.pixelWidthUm;
        double y = dy * plane.pixelHeightUm;
        return Math.sqrt(x * x + y * y);
    }

    private static String column(String source, String token, String partner) {
        return source + "_" + token + "_" + partner;
    }

    private static final class Peak {
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
