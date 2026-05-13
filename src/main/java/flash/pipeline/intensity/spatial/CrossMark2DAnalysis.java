package flash.pipeline.intensity.spatial;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.FeatureDependencyGate;
import net.imglib2.TwinCursor;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import sc.fiji.coloc.algorithms.AutoThresholdRegression;
import sc.fiji.coloc.algorithms.CostesSignificanceTest;
import sc.fiji.coloc.algorithms.MandersColocalization;
import sc.fiji.coloc.algorithms.PearsonsCorrelation;
import sc.fiji.coloc.gadgets.DataContainer;
import sc.fiji.coloc.gadgets.ThresholdMode;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 2D source/partner colocalization, cross-correlation, and mark-correlation metrics.
 */
public final class CrossMark2DAnalysis implements IntensitySpatialPairAnalysis {
    private static final int DEFAULT_COSTES_PSF_PIXELS = 3;
    private static final int MAX_SHIFT_PIXELS = 12;
    private static final int MAX_MARK_RADIUS_PIXELS = 12;

    @Override
    public IntensitySpatialConfig.AnalysisKey key() {
        return IntensitySpatialConfig.AnalysisKey.CROSSMARK;
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
        columns.add(column(sourceChannel, "Pearson", partnerChannel));
        columns.add(column(sourceChannel, "CCFPeakDist_um", partnerChannel));
        columns.add(column(sourceChannel, "CCFPeakAmp", partnerChannel));
        columns.add(column(sourceChannel, "MarkCorrRadius_um", partnerChannel));
        columns.add(column(sourceChannel, "MarkCorrStrength", partnerChannel));
        columns.add(column(sourceChannel, "CostesP", partnerChannel));
        columns.add(column(sourceChannel, "CostesTa", partnerChannel));
        columns.add(column(sourceChannel, "CostesTb", partnerChannel));
        if (sourceBinarized && partnerBinarized) {
            columns.add(column(sourceChannel, "CostesP", partnerChannel) + "_binarized");
        }
        columns.add(column(sourceChannel, "MandersM1", partnerChannel));
        columns.add(column(sourceChannel, "MandersM2", partnerChannel));
        if (sourceBinarized && partnerBinarized) {
            columns.add(column(sourceChannel, "MandersM1", partnerChannel) + "_binarized");
            columns.add(column(sourceChannel, "MandersM2", partnerChannel) + "_binarized");
        }
        return columns;
    }

    @Override
    public int estimatedCost() {
        return 10;
    }

    @Override
    public IntensitySpatialResult measure(IntensitySpatialPairContext context) {
        LinkedHashMap<String, Double> values = new LinkedHashMap<String, Double>();
        PairPlane2D plane = PairPlane2D.raw(context);
        boolean sourceBinarized = context.hasSourceBinarizedImage();
        boolean partnerBinarized = context.hasPartnerBinarizedImage();
        if (plane.count < 3) {
            context.warn("cross-channel metrics have insufficient valid ROI pixels; returning NaN.");
            return IntensitySpatialResult.nanFor(columns(context.config(), context.sourceChannelName(),
                    context.partnerChannelName(), sourceBinarized, partnerBinarized));
        }

        ColocMetrics coloc = ColocMetrics.nan();
        BinarizedColocMetrics binarized = BinarizedColocMetrics.nan();
        boolean colocAvailable = FeatureDependencyGate.isAvailable(DependencyId.COLOC2_RUNTIME);
        if (colocAvailable) {
            try {
                coloc = colocMetrics(plane, context, false);
                if (sourceBinarized && partnerBinarized) {
                    PairPlane2D binarizedPlane = PairPlane2D.binarized(context);
                    if (binarizedPlane.count >= 3) {
                        binarized = binarizedColocMetrics(binarizedPlane, context);
                    }
                }
            } catch (Exception ex) {
                context.warn("Coloc 2 cross-channel metrics failed: " + safeMessage(ex));
            } catch (LinkageError err) {
                context.warn("Coloc 2 cross-channel metrics failed: " + safeMessage(err));
            }
        } else {
            context.warn("Coloc 2 runtime is unavailable; Pearson, Costes, and Manders columns are NaN.");
        }

        String source = context.sourceChannelName();
        String partner = context.partnerChannelName();
        values.put(column(source, "Pearson", partner), Double.valueOf(coloc.pearson));

        Peak ccf = ccfPeak(plane);
        values.put(column(source, "CCFPeakDist_um", partner), Double.valueOf(ccf.radiusUm));
        values.put(column(source, "CCFPeakAmp", partner), Double.valueOf(ccf.strength));

        Peak mark = markCorrelationPeak(plane);
        values.put(column(source, "MarkCorrRadius_um", partner), Double.valueOf(mark.radiusUm));
        values.put(column(source, "MarkCorrStrength", partner), Double.valueOf(mark.strength));

        values.put(column(source, "CostesP", partner), Double.valueOf(coloc.costesP));
        values.put(column(source, "CostesTa", partner), Double.valueOf(coloc.thresholdA));
        values.put(column(source, "CostesTb", partner), Double.valueOf(coloc.thresholdB));
        if (sourceBinarized && partnerBinarized) {
            values.put(column(source, "CostesP", partner) + "_binarized",
                    Double.valueOf(binarized.costesP));
        }
        values.put(column(source, "MandersM1", partner), Double.valueOf(coloc.mandersM1));
        values.put(column(source, "MandersM2", partner), Double.valueOf(coloc.mandersM2));
        if (sourceBinarized && partnerBinarized) {
            values.put(column(source, "MandersM1", partner) + "_binarized",
                    Double.valueOf(binarized.mandersM1));
            values.put(column(source, "MandersM2", partner) + "_binarized",
                    Double.valueOf(binarized.mandersM2));
        }
        return new IntensitySpatialResult(values);
    }

    private static ColocMetrics colocMetrics(PairPlane2D plane,
                                             IntensitySpatialPairContext context,
                                             boolean binarized) throws Exception {
        PairPlane2D.ColocImages images = plane.toColocImages();
        DataContainer<FloatType> container = new DataContainer<FloatType>(
                images.source, images.partner, 1, 2,
                context.sourceChannelName(), context.partnerChannelName(),
                images.mask, new long[]{0, 0}, images.dimensions);

        PearsonsCorrelation<FloatType> pearsons =
                new PearsonsCorrelation<FloatType>(PearsonsCorrelation.Implementation.Fast);
        AutoThresholdRegression<FloatType> auto =
                new AutoThresholdRegression<FloatType>(pearsons);
        container.setAutoThreshold(auto);
        auto.execute(container);

        double pearson = pearsons.calculatePearsons(images.source, images.partner, container.getMask());
        double thresholdA = realValue(auto.getCh1MaxThreshold());
        double thresholdB = realValue(auto.getCh2MaxThreshold());

        MandersColocalization<FloatType> manders = new MandersColocalization<FloatType>();
        TwinCursor<FloatType> cursor = new TwinCursor<FloatType>(
                images.source.randomAccess(), images.partner.randomAccess(),
                Views.iterable(container.getMask()).localizingCursor());
        MandersColocalization.MandersResults mandersResult =
                manders.calculateMandersCorrelation(cursor,
                        new FloatType((float) thresholdA),
                        new FloatType((float) thresholdB),
                        ThresholdMode.Above);

        double costesP = costesP(pearsons, container, context.config().getPermutations());
        return new ColocMetrics(pearson, costesP, thresholdA, thresholdB,
                finiteOrNan(mandersResult.m1), finiteOrNan(mandersResult.m2));
    }

    private static BinarizedColocMetrics binarizedColocMetrics(PairPlane2D plane,
                                                               IntensitySpatialPairContext context) throws Exception {
        PairPlane2D.ColocImages images = plane.toColocImages();
        DataContainer<FloatType> container = new DataContainer<FloatType>(
                images.source, images.partner, 1, 2,
                context.sourceChannelName(), context.partnerChannelName(),
                images.mask, new long[]{0, 0}, images.dimensions);

        PearsonsCorrelation<FloatType> pearsons =
                new PearsonsCorrelation<FloatType>(PearsonsCorrelation.Implementation.Fast);
        AutoThresholdRegression<FloatType> auto =
                new AutoThresholdRegression<FloatType>(pearsons);
        container.setAutoThreshold(auto);
        auto.execute(container);

        MandersColocalization<FloatType> manders = new MandersColocalization<FloatType>();
        TwinCursor<FloatType> cursor = new TwinCursor<FloatType>(
                images.source.randomAccess(), images.partner.randomAccess(),
                Views.iterable(container.getMask()).localizingCursor());
        MandersColocalization.MandersResults mandersResult =
                manders.calculateMandersCorrelation(cursor,
                        new FloatType(0.0f), new FloatType(0.0f), ThresholdMode.Above);

        double costesP = costesP(pearsons, container, context.config().getPermutations());
        return new BinarizedColocMetrics(costesP,
                finiteOrNan(mandersResult.m1), finiteOrNan(mandersResult.m2));
    }

    private static double costesP(PearsonsCorrelation<FloatType> pearsons,
                                  DataContainer<FloatType> container,
                                  int permutations) throws Exception {
        int randomizations = Math.max(1, permutations);
        CostesSignificanceTest<FloatType> costes =
                new CostesSignificanceTest<FloatType>(pearsons, DEFAULT_COSTES_PSF_PIXELS,
                        randomizations, false);
        costes.execute(container);
        return finiteOrNan(costes.getCostesPValue());
    }

    private static Peak ccfPeak(PairPlane2D plane) {
        int maxShift = Math.min(MAX_SHIFT_PIXELS, Math.max(0, Math.min(plane.width, plane.height) / 4));
        double best = Double.NEGATIVE_INFINITY;
        int bestDx = 0;
        int bestDy = 0;
        for (int dy = -maxShift; dy <= maxShift; dy++) {
            for (int dx = -maxShift; dx <= maxShift; dx++) {
                double r = plane.shiftedPearson(dx, dy);
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

    private static Peak markCorrelationPeak(PairPlane2D plane) {
        double meanSource = plane.meanSource();
        double meanPartner = plane.meanPartner();
        double baseline = meanSource * meanPartner;
        if (baseline <= 0.0 || !PairPlane2D.isFinite(baseline)) return Peak.nan();

        int maxRadius = Math.min(MAX_MARK_RADIUS_PIXELS,
                Math.max(0, Math.min(plane.width, plane.height) / 4));
        double best = Double.NEGATIVE_INFINITY;
        int bestRadius = 0;
        for (int radius = 0; radius <= maxRadius; radius++) {
            double sum = 0.0;
            int n = 0;
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    int rounded = (int) Math.round(Math.sqrt(dx * dx + dy * dy));
                    if (rounded != radius) continue;
                    for (int y = 0; y < plane.height; y++) {
                        int yy = y + dy;
                        if (yy < 0 || yy >= plane.height) continue;
                        for (int x = 0; x < plane.width; x++) {
                            int xx = x + dx;
                            if (xx < 0 || xx >= plane.width) continue;
                            int a = y * plane.width + x;
                            int b = yy * plane.width + xx;
                            if (!plane.valid[a] || !plane.valid[b]) continue;
                            sum += plane.source[a] * plane.partner[b];
                            n++;
                        }
                    }
                }
            }
            if (n == 0) continue;
            double strength = (sum / n) / baseline;
            if (strength > best) {
                best = strength;
                bestRadius = radius;
            }
        }
        if (best == Double.NEGATIVE_INFINITY) return Peak.nan();
        double radiusUm = bestRadius * (plane.pixelWidthUm + plane.pixelHeightUm) / 2.0;
        return new Peak(radiusUm, best);
    }

    private static double distanceUm(int dx, int dy, PairPlane2D plane) {
        double x = dx * plane.pixelWidthUm;
        double y = dy * plane.pixelHeightUm;
        return Math.sqrt(x * x + y * y);
    }

    private static String column(String source, String token, String partner) {
        return source + "_" + token + "_" + partner;
    }

    private static double realValue(FloatType value) {
        return value == null ? Double.NaN : finiteOrNan(value.getRealDouble());
    }

    private static double finiteOrNan(double value) {
        return PairPlane2D.isFinite(value) ? value : Double.NaN;
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName()
                : message.trim();
    }

    private static final class ColocMetrics {
        final double pearson;
        final double costesP;
        final double thresholdA;
        final double thresholdB;
        final double mandersM1;
        final double mandersM2;

        private ColocMetrics(double pearson,
                             double costesP,
                             double thresholdA,
                             double thresholdB,
                             double mandersM1,
                             double mandersM2) {
            this.pearson = pearson;
            this.costesP = costesP;
            this.thresholdA = thresholdA;
            this.thresholdB = thresholdB;
            this.mandersM1 = mandersM1;
            this.mandersM2 = mandersM2;
        }

        static ColocMetrics nan() {
            return new ColocMetrics(Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN);
        }
    }

    private static final class BinarizedColocMetrics {
        final double costesP;
        final double mandersM1;
        final double mandersM2;

        private BinarizedColocMetrics(double costesP, double mandersM1, double mandersM2) {
            this.costesP = costesP;
            this.mandersM1 = mandersM1;
            this.mandersM2 = mandersM2;
        }

        static BinarizedColocMetrics nan() {
            return new BinarizedColocMetrics(Double.NaN, Double.NaN, Double.NaN);
        }
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
