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
 * Native-3D source/partner colocalization metrics.
 */
public final class CrossMark3DAnalysis implements IntensitySpatialPairAnalysis {
    private static final int DEFAULT_COSTES_PSF_PIXELS = 3;
    private static final int MAX_COSTES_RANDOMIZATION_VOXELS = 262_144;
    private static final int MAX_COSTES_RANDOMIZATIONS = 199;

    @Override
    public IntensitySpatialConfig.AnalysisKey key() {
        return IntensitySpatialConfig.AnalysisKey.CROSSMARK_3D;
    }

    @Override
    public EnumSet<IntensitySpatialOutputMode> outputModes() {
        return EnumSet.of(IntensitySpatialOutputMode.NATIVE_3D);
    }

    @Override
    public List<String> columns(IntensitySpatialConfig config,
                                String sourceChannel,
                                String partnerChannel,
                                boolean sourceBinarized,
                                boolean partnerBinarized) {
        ArrayList<String> columns = new ArrayList<String>();
        columns.add(column(sourceChannel, "Pearson3D", partnerChannel));
        columns.add(column(sourceChannel, "CostesP3D", partnerChannel));
        columns.add(column(sourceChannel, "CostesTa3D", partnerChannel));
        columns.add(column(sourceChannel, "CostesTb3D", partnerChannel));
        if (sourceBinarized && partnerBinarized) {
            columns.add(column(sourceChannel, "CostesP3D", partnerChannel) + "_binarized");
        }
        columns.add(column(sourceChannel, "MandersM13D", partnerChannel));
        columns.add(column(sourceChannel, "MandersM23D", partnerChannel));
        if (sourceBinarized && partnerBinarized) {
            columns.add(column(sourceChannel, "MandersM13D", partnerChannel) + "_binarized");
            columns.add(column(sourceChannel, "MandersM23D", partnerChannel) + "_binarized");
        }
        return columns;
    }

    @Override
    public int estimatedCost() {
        return 12;
    }

    @Override
    public IntensitySpatialResult measure(IntensitySpatialPairContext context) {
        PairVolume3D volume = PairVolume3D.raw(context);
        boolean sourceBinarized = context.hasSourceBinarizedImage();
        boolean partnerBinarized = context.hasPartnerBinarizedImage();
        List<String> columns = columns(context.config(), context.sourceChannelName(),
                context.partnerChannelName(), sourceBinarized, partnerBinarized);
        if (volume.count < 3) {
            context.warn("native-3D colocalization has insufficient valid ROI voxels; returning NaN.");
            return IntensitySpatialResult.nanFor(columns);
        }

        ColocMetrics coloc = ColocMetrics.nan();
        BinarizedColocMetrics binarized = BinarizedColocMetrics.nan();
        if (FeatureDependencyGate.isAvailable(DependencyId.COLOC2_RUNTIME)) {
            try {
                coloc = colocMetrics(volume, context);
                if (sourceBinarized && partnerBinarized) {
                    PairVolume3D binarizedVolume = PairVolume3D.binarized(context);
                    if (binarizedVolume.count >= 3) {
                        binarized = binarizedColocMetrics(binarizedVolume, context);
                    }
                }
            } catch (Exception ex) {
                context.warn("Coloc 2 native-3D metrics failed: " + safeMessage(ex));
            } catch (LinkageError err) {
                context.warn("Coloc 2 native-3D metrics failed: " + safeMessage(err));
            }
        } else {
            context.warn("Coloc 2 runtime is unavailable; native-3D Pearson, Costes, and Manders columns are NaN.");
        }

        String source = context.sourceChannelName();
        String partner = context.partnerChannelName();
        LinkedHashMap<String, Double> values = new LinkedHashMap<String, Double>();
        values.put(column(source, "Pearson3D", partner), Double.valueOf(coloc.pearson));
        values.put(column(source, "CostesP3D", partner), Double.valueOf(coloc.costesP));
        values.put(column(source, "CostesTa3D", partner), Double.valueOf(coloc.thresholdA));
        values.put(column(source, "CostesTb3D", partner), Double.valueOf(coloc.thresholdB));
        if (sourceBinarized && partnerBinarized) {
            values.put(column(source, "CostesP3D", partner) + "_binarized",
                    Double.valueOf(binarized.costesP));
        }
        values.put(column(source, "MandersM13D", partner), Double.valueOf(coloc.mandersM1));
        values.put(column(source, "MandersM23D", partner), Double.valueOf(coloc.mandersM2));
        if (sourceBinarized && partnerBinarized) {
            values.put(column(source, "MandersM13D", partner) + "_binarized",
                    Double.valueOf(binarized.mandersM1));
            values.put(column(source, "MandersM23D", partner) + "_binarized",
                    Double.valueOf(binarized.mandersM2));
        }
        return new IntensitySpatialResult(values);
    }

    private static ColocMetrics colocMetrics(PairVolume3D volume,
                                             IntensitySpatialPairContext context) throws Exception {
        PairVolume3D.ColocImages images = volume.toColocImages();
        DataContainer<FloatType> container = new DataContainer<FloatType>(
                images.source, images.partner, 1, 2,
                context.sourceChannelName(), context.partnerChannelName(),
                images.mask, new long[]{0, 0, 0}, images.dimensions);

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

        double costesP = costesP(pearsons, container, context.config().getPermutations(),
                volume.count, context);
        return new ColocMetrics(pearson, costesP, thresholdA, thresholdB,
                finiteOrNan(mandersResult.m1), finiteOrNan(mandersResult.m2));
    }

    private static BinarizedColocMetrics binarizedColocMetrics(PairVolume3D volume,
                                                               IntensitySpatialPairContext context) throws Exception {
        PairVolume3D.ColocImages images = volume.toColocImages();
        DataContainer<FloatType> container = new DataContainer<FloatType>(
                images.source, images.partner, 1, 2,
                context.sourceChannelName(), context.partnerChannelName(),
                images.mask, new long[]{0, 0, 0}, images.dimensions);

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

        double costesP = costesP(pearsons, container, context.config().getPermutations(),
                volume.count, context);
        return new BinarizedColocMetrics(costesP,
                finiteOrNan(mandersResult.m1), finiteOrNan(mandersResult.m2));
    }

    private static double costesP(PearsonsCorrelation<FloatType> pearsons,
                                  DataContainer<FloatType> container,
                                  int permutations,
                                  int validVoxels,
                                  IntensitySpatialPairContext context) throws Exception {
        if (permutations <= 0) {
            return Double.NaN;
        }
        if (validVoxels > MAX_COSTES_RANDOMIZATION_VOXELS) {
            context.warn("Costes significance skipped for large native-3D volume ("
                    + validVoxels + " valid voxels; limit "
                    + MAX_COSTES_RANDOMIZATION_VOXELS
                    + "); CostesP3D is NaN, Pearson3D and Manders3D still measured.");
            return Double.NaN;
        }
        int randomizations = Math.min(permutations, MAX_COSTES_RANDOMIZATIONS);
        if (randomizations < permutations) {
            context.warn("Costes significance permutations capped at "
                    + MAX_COSTES_RANDOMIZATIONS + " for runtime.");
        }
        CostesSignificanceTest<FloatType> costes =
                new CostesSignificanceTest<FloatType>(pearsons, DEFAULT_COSTES_PSF_PIXELS,
                        randomizations, false);
        costes.execute(container);
        return finiteOrNan(costes.getCostesPValue());
    }

    private static String column(String source, String token, String partner) {
        return source + "_" + token + "_" + partner;
    }

    private static double realValue(FloatType value) {
        return value == null ? Double.NaN : finiteOrNan(value.getRealDouble());
    }

    private static double finiteOrNan(double value) {
        return PairVolume3D.isFinite(value) ? value : Double.NaN;
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
}
