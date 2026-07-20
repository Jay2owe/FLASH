package flash.pipeline.intensity.spatial;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.FeatureDependencyGate;
import net.imglib2.Cursor;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.TwinCursor;
import net.imglib2.algorithm.gauss.Gauss;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.logic.BitType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import sc.fiji.coloc.algorithms.AutoThresholdRegression;
import sc.fiji.coloc.algorithms.MandersColocalization;
import sc.fiji.coloc.algorithms.MissingPreconditionException;
import sc.fiji.coloc.algorithms.PearsonsCorrelation;
import sc.fiji.coloc.gadgets.DataContainer;
import sc.fiji.coloc.gadgets.Statistics;
import sc.fiji.coloc.gadgets.ThresholdMode;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;

/**
 * 2D source/partner colocalization, cross-correlation, and mark-correlation metrics.
 */
public final class CrossMark2DAnalysis implements IntensitySpatialPairAnalysis {
    private static final int DEFAULT_COSTES_PSF_PIXELS = 3;
    private static final int MAX_COSTES_RANDOMIZATION_PIXELS = 262_144;
    private static final int MAX_COSTES_RANDOMIZATIONS = 199;
    private static final int MAX_SPATIAL_CORRELATION_SAMPLES = 32_768;
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
        columns.addAll(FastCrossCorrelation2DCore.columns(sourceChannel, partnerChannel));
        columns.add(column(sourceChannel, "MarkCorrRadius_um", partnerChannel));
        columns.add(column(sourceChannel, "MarkCorrStrength", partnerChannel));
        columns.add(column(sourceChannel, "CostesP", partnerChannel));
        columns.add(column(sourceChannel, "CostesSeed", partnerChannel));
        columns.add(column(sourceChannel, "CostesTa", partnerChannel));
        columns.add(column(sourceChannel, "CostesTb", partnerChannel));
        if (sourceBinarized && partnerBinarized) {
            columns.add(column(sourceChannel, "CostesP", partnerChannel) + "_binarized");
            columns.add(column(sourceChannel, "CostesSeed", partnerChannel) + "_binarized");
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
        boolean sourceBinarized = context.hasSourceBinarizedImage();
        boolean partnerBinarized = context.hasPartnerBinarizedImage();
        List<String> resultColumns = columns(context.config(), context.sourceChannelName(),
                context.partnerChannelName(), sourceBinarized, partnerBinarized);
        PairPlane2D plane;
        try {
            plane = PairPlane2D.raw(context);
        } catch (IllegalArgumentException ex) {
            context.warn("cross-channel metrics skipped before allocation: " + safeMessage(ex));
            return IntensitySpatialResult.nanFor(resultColumns);
        }
        if (plane.count < 3) {
            context.warn("cross-channel metrics have insufficient valid ROI pixels; returning NaN.");
            return IntensitySpatialResult.nanFor(resultColumns);
        }

        double directPearson = FastCrossCorrelation2DCore.directPearson(plane);
        ColocMetrics coloc = ColocMetrics.pearsonOnly(directPearson);
        BinarizedColocMetrics binarized = BinarizedColocMetrics.nan();
        boolean colocAvailable = FeatureDependencyGate.isAvailable(DependencyId.COLOC2_RUNTIME);
        if (colocAvailable) {
            try {
                if (colocImagesAllowed(plane, context, false)) {
                    coloc = colocMetrics(plane, context, false);
                } else {
                    coloc = ColocMetrics.pearsonOnly(directPearson);
                }
            } catch (Exception ex) {
                context.warn("Coloc 2 cross-channel metrics failed: " + safeMessage(ex)
                        + "; Pearson is computed directly, Costes and Manders are NaN.");
                coloc = ColocMetrics.pearsonOnly(directPearson);
            } catch (LinkageError err) {
                context.warn("Coloc 2 cross-channel metrics failed: " + safeMessage(err)
                        + "; Pearson is computed directly, Costes and Manders are NaN.");
                coloc = ColocMetrics.pearsonOnly(directPearson);
            }
            if (sourceBinarized && partnerBinarized) {
                try {
                    PairPlane2D binarizedPlane = PairPlane2D.binarized(context);
                    if (binarizedPlane.count >= 3) {
                        if (colocImagesAllowed(binarizedPlane, context, true)) {
                            binarized = binarizedColocMetrics(binarizedPlane, context);
                        }
                    }
                } catch (Exception ex) {
                    context.warn("Coloc 2 binarized cross-channel metrics failed: "
                            + safeMessage(ex));
                } catch (LinkageError err) {
                    context.warn("Coloc 2 binarized cross-channel metrics failed: "
                            + safeMessage(err));
                }
            }
        } else {
            context.warn("Coloc 2 runtime is unavailable; Pearson is computed directly, "
                    + "Costes and Manders columns are NaN.");
        }

        String source = context.sourceChannelName();
        String partner = context.partnerChannelName();
        values.put(column(source, "Pearson", partner), Double.valueOf(coloc.pearson));

        FastCrossCorrelation2DCore.Peak ccf = FastCrossCorrelation2DCore.ccfPeak(plane);
        values.put(column(source, "CCFPeakDist_um", partner), Double.valueOf(ccf.radiusUm));
        values.put(column(source, "CCFPeakAmp", partner), Double.valueOf(ccf.strength));

        Peak mark = markCorrelationPeak(plane);
        values.put(column(source, "MarkCorrRadius_um", partner), Double.valueOf(mark.radiusUm));
        values.put(column(source, "MarkCorrStrength", partner), Double.valueOf(mark.strength));

        values.put(column(source, "CostesP", partner), Double.valueOf(coloc.costesP));
        values.put(column(source, "CostesSeed", partner), Double.valueOf(coloc.costesSeed));
        values.put(column(source, "CostesTa", partner), Double.valueOf(coloc.thresholdA));
        values.put(column(source, "CostesTb", partner), Double.valueOf(coloc.thresholdB));
        if (sourceBinarized && partnerBinarized) {
            values.put(column(source, "CostesP", partner) + "_binarized",
                    Double.valueOf(binarized.costesP));
            values.put(column(source, "CostesSeed", partner) + "_binarized",
                    Double.valueOf(binarized.costesSeed));
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

        int permutations = context.config().getCostesPermutations();
        long seed = SpatialRandomSeeds.costes(context, false, "2d");
        double costesP = costesP(pearsons, container, permutations,
                plane.count, seed, context);
        return new ColocMetrics(pearson, costesP, recordedSeed(permutations, plane.count, seed),
                thresholdA, thresholdB,
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

        int permutations = context.config().getCostesPermutations();
        long seed = SpatialRandomSeeds.costes(context, true, "2d");
        double costesP = costesP(pearsons, container, permutations,
                plane.count, seed, context);
        return new BinarizedColocMetrics(costesP,
                recordedSeed(permutations, plane.count, seed),
                finiteOrNan(mandersResult.m1), finiteOrNan(mandersResult.m2));
    }

    private static double costesP(PearsonsCorrelation<FloatType> pearsons,
                                  DataContainer<FloatType> container,
                                  int permutations,
                                  int validPixels,
                                  long seed,
                                  IntensitySpatialPairContext context) throws Exception {
        if (permutations <= 0) {
            return Double.NaN;
        }
        if (validPixels > MAX_COSTES_RANDOMIZATION_PIXELS) {
            context.warn("Costes significance skipped for large cross-channel plane ("
                    + validPixels + " valid pixels; limit "
                    + MAX_COSTES_RANDOMIZATION_PIXELS
                    + "); CostesP is NaN, Pearson and Manders still measured.");
            return Double.NaN;
        }
        int randomizations = Math.min(permutations, MAX_COSTES_RANDOMIZATIONS);
        if (randomizations < permutations) {
            context.warn("Costes significance permutations capped at "
                    + MAX_COSTES_RANDOMIZATIONS + " for runtime.");
        }
        return finiteOrNan(DeterministicCostesShuffler.pValue(
                pearsons, container, DEFAULT_COSTES_PSF_PIXELS, randomizations, seed));
    }

    private static double recordedSeed(int permutations, int validPixels, long seed) {
        return permutations > 0 && validPixels <= MAX_COSTES_RANDOMIZATION_PIXELS
                ? (double) seed : Double.NaN;
    }

    private static boolean colocImagesAllowed(PairPlane2D plane,
                                              IntensitySpatialPairContext context,
                                              boolean binarized) {
        if (SpatialResourceGuards.colocImagesAllowed(plane.pixelCount())) {
            return true;
        }
        String suffix = binarized
                ? "; binarized CostesP and Manders columns are NaN."
                : "; Pearson is computed directly, CostesP and Manders columns are NaN.";
        context.warn("Coloc 2 image conversion skipped for large cross-channel plane ("
                + plane.pixelCount() + " pixels; limit "
                + SpatialResourceGuards.maxColocImagePixels() + ")" + suffix);
        return false;
    }

    private static Peak markCorrelationPeak(PairPlane2D plane) {
        double meanSource = plane.meanSource();
        double meanPartner = plane.meanPartner();
        double baseline = meanSource * meanPartner;
        if (baseline <= 0.0 || !PairPlane2D.isFinite(baseline)) return Peak.nan();

        int maxRadius = Math.min(MAX_MARK_RADIUS_PIXELS,
                Math.max(0, Math.min(plane.width, plane.height) / 4));
        int sampleStep = sampleStep(plane.count);
        int sampleStart = sampleStep == 1 ? 0 : sampleStep / 2;
        double best = Double.NEGATIVE_INFINITY;
        int bestRadius = 0;
        for (int radius = 0; radius <= maxRadius; radius++) {
            double sum = 0.0;
            int n = 0;
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    int rounded = (int) Math.round(Math.sqrt(dx * dx + dy * dy));
                    if (rounded != radius) continue;
                    for (int y = sampleStart; y < plane.height; y += sampleStep) {
                        int yy = y + dy;
                        if (yy < 0 || yy >= plane.height) continue;
                        for (int x = sampleStart; x < plane.width; x += sampleStep) {
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

    private static int sampleStep(int validPixels) {
        if (validPixels <= MAX_SPATIAL_CORRELATION_SAMPLES) {
            return 1;
        }
        return Math.max(1, (int) Math.ceil(Math.sqrt(
                validPixels / (double) MAX_SPATIAL_CORRELATION_SAMPLES)));
    }

    static String column(String source, String token, String partner) {
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
        final double costesSeed;
        final double thresholdA;
        final double thresholdB;
        final double mandersM1;
        final double mandersM2;

        private ColocMetrics(double pearson,
                             double costesP,
                             double costesSeed,
                             double thresholdA,
                             double thresholdB,
                             double mandersM1,
                             double mandersM2) {
            this.pearson = pearson;
            this.costesP = costesP;
            this.costesSeed = costesSeed;
            this.thresholdA = thresholdA;
            this.thresholdB = thresholdB;
            this.mandersM1 = mandersM1;
            this.mandersM2 = mandersM2;
        }

        static ColocMetrics nan() {
            return new ColocMetrics(Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN);
        }

        static ColocMetrics pearsonOnly(double pearson) {
            return new ColocMetrics(finiteOrNan(pearson), Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
    }

    private static final class BinarizedColocMetrics {
        final double costesP;
        final double costesSeed;
        final double mandersM1;
        final double mandersM2;

        private BinarizedColocMetrics(double costesP,
                                      double costesSeed,
                                      double mandersM1,
                                      double mandersM2) {
            this.costesP = costesP;
            this.costesSeed = costesSeed;
            this.mandersM1 = mandersM1;
            this.mandersM2 = mandersM2;
        }

        static BinarizedColocMetrics nan() {
            return new BinarizedColocMetrics(Double.NaN, Double.NaN, Double.NaN, Double.NaN);
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

/** Stable, encounter-order-independent child seeds for spatial randomization. */
final class SpatialRandomSeeds {
    private static final long EXACT_DOUBLE_MASK = (1L << 53) - 1L;
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private SpatialRandomSeeds() {
    }

    static long costes(IntensitySpatialPairContext context,
                       boolean binarized,
                       String dimensionality) {
        return derive(context.config().getSeed(),
                "costes",
                dimensionality,
                context.imageId(),
                context.sourceChannelName(),
                context.partnerChannelName(),
                context.roiLabel(),
                Integer.toString(context.sliceIndex()),
                context.outputMode().name(),
                binarized ? "binarized" : "raw");
    }

    static long hotspot(IntensitySpatialContext context, boolean binarized) {
        return derive(context.config().getSeed(),
                "hotspot",
                context.imageId(),
                context.channelName(),
                context.roiLabel(),
                Integer.toString(context.sliceIndex()),
                context.outputMode().name(),
                binarized ? "binarized" : "raw");
    }

    static long derive(long baseSeed, String... fields) {
        long hash = appendLong(FNV_OFFSET, baseSeed);
        if (fields != null) {
            for (String field : fields) {
                hash = appendLong(hash, field == null ? -1L : field.length());
                if (field != null) {
                    for (int i = 0; i < field.length(); i++) {
                        char value = field.charAt(i);
                        hash = appendByte(hash, value & 0xff);
                        hash = appendByte(hash, value >>> 8);
                    }
                }
            }
        }
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        hash *= 0xc4ceb9fe1a85ec53L;
        hash ^= hash >>> 33;
        return hash & EXACT_DOUBLE_MASK;
    }

    private static long appendLong(long hash, long value) {
        long current = hash;
        for (int shift = 0; shift < 64; shift += 8) {
            current = appendByte(current, (int) (value >>> shift) & 0xff);
        }
        return current;
    }

    private static long appendByte(long hash, int value) {
        return (hash ^ (value & 0xff)) * FNV_PRIME;
    }
}

/** Deterministic Costes block permutation using an explicit random stream. */
final class DeterministicCostesShuffler {
    private static final int MAX_NUMERICAL_RETRIES = 3;

    private DeterministicCostesShuffler() {
    }

    static double pValue(PearsonsCorrelation<FloatType> pearsons,
                         DataContainer<FloatType> container,
                         int blockSize,
                         int randomizations,
                         long seed) throws MissingPreconditionException {
        RandomAccessibleInterval<FloatType> source = container.getSourceImage1();
        RandomAccessibleInterval<FloatType> partner = container.getSourceImage2();
        RandomAccessibleInterval<BitType> mask = container.getMask();
        long[] dimensions = new long[source.numDimensions()];
        source.dimensions(dimensions);
        long[] maskOffset = container.getMaskBBOffset();
        long[] maskSize = container.getMaskBBSize();
        int[] blocksPerDimension = blockCounts(maskSize, blockSize);
        int blockCount = checkedBlockCount(blocksPerDimension);
        int[] sourceOrder = new int[blockCount];
        for (int i = 0; i < sourceOrder.length; i++) {
            sourceOrder[i] = i;
        }

        float[] shuffledPixels = new float[checkedElementCount(dimensions)];
        Img<FloatType> shuffled = ArrayImgs.floats(shuffledPixels, dimensions);
        RandomAccessible<FloatType> mirroredSource = Views.extendMirrorSingle(source);
        RandomAccess<FloatType> sourceAccess = mirroredSource.randomAccess();
        RandomAccess<FloatType> outputAccess = shuffled.randomAccess();
        Random random = new Random(seed);
        double[] smoothingRadius = new double[dimensions.length];
        for (int d = 0; d < smoothingRadius.length; d++) {
            smoothingRadius[d] = blockSize;
        }

        List<Double> randomizedPearsons = new ArrayList<Double>(randomizations);
        int retries = 0;
        while (randomizedPearsons.size() < randomizations) {
            shuffle(sourceOrder, random);
            clear(shuffled);
            copyPermutedBlocks(sourceAccess, outputAccess, dimensions, maskOffset,
                    blocksPerDimension, sourceOrder, blockSize);
            Img<FloatType> smoothed = Gauss.inFloat(smoothingRadius, shuffled);
            try {
                randomizedPearsons.add(Double.valueOf(
                        pearsons.calculatePearsons(smoothed, partner, mask)));
            } catch (MissingPreconditionException ex) {
                if (retries >= MAX_NUMERICAL_RETRIES) {
                    throw new MissingPreconditionException(
                            "Costes randomization remained numerically invalid after "
                                    + retries + " retries: " + ex.getMessage(), ex);
                }
                retries++;
            }
        }

        double observed = pearsons.calculatePearsons(source, partner, mask);
        double mean = mean(randomizedPearsons);
        double standardDeviation = Statistics.stdDeviation(randomizedPearsons);
        double p = Statistics.phi(observed, mean, standardDeviation);
        if (p > 1.0) return 1.0;
        if (p < 0.0) return 0.0;
        return p;
    }

    private static int[] blockCounts(long[] maskSize, int blockSize) {
        int[] counts = new int[maskSize.length];
        for (int d = 0; d < counts.length; d++) {
            long count = (maskSize[d] + blockSize - 1L) / blockSize;
            if (count <= 0L || count > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Invalid Costes block count in dimension " + d);
            }
            counts[d] = (int) count;
        }
        return counts;
    }

    private static int checkedBlockCount(int[] counts) {
        long total = 1L;
        for (int count : counts) {
            if (count <= 0 || total > Integer.MAX_VALUE / (long) count) {
                throw new IllegalArgumentException("Costes block count exceeds the supported limit");
            }
            total *= count;
        }
        return (int) total;
    }

    private static int checkedElementCount(long[] dimensions) {
        long total = 1L;
        for (long dimension : dimensions) {
            if (dimension <= 0L || dimension > Integer.MAX_VALUE
                    || total > Integer.MAX_VALUE / dimension) {
                throw new IllegalArgumentException("Costes image dimensions exceed the supported limit");
            }
            total *= dimension;
        }
        return (int) total;
    }

    private static void shuffle(int[] values, Random random) {
        for (int i = values.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int value = values[i];
            values[i] = values[j];
            values[j] = value;
        }
    }

    private static void clear(Img<FloatType> image) {
        Cursor<FloatType> cursor = image.cursor();
        while (cursor.hasNext()) {
            cursor.next().setZero();
        }
    }

    private static void copyPermutedBlocks(RandomAccess<FloatType> source,
                                           RandomAccess<FloatType> output,
                                           long[] dimensions,
                                           long[] offset,
                                           int[] blocksPerDimension,
                                           int[] sourceOrder,
                                           int blockSize) {
        int dimensionCount = dimensions.length;
        int samplesPerBlock = 1;
        for (int d = 0; d < dimensionCount; d++) {
            samplesPerBlock *= blockSize;
        }
        int[] destinationBlock = new int[dimensionCount];
        int[] sourceBlock = new int[dimensionCount];
        int[] withinBlock = new int[dimensionCount];
        long[] destination = new long[dimensionCount];
        long[] sourcePosition = new long[dimensionCount];
        for (int destinationIndex = 0; destinationIndex < sourceOrder.length;
             destinationIndex++) {
            decode(destinationIndex, blocksPerDimension, destinationBlock);
            decode(sourceOrder[destinationIndex], blocksPerDimension, sourceBlock);
            for (int sample = 0; sample < samplesPerBlock; sample++) {
                decodeUniform(sample, blockSize, withinBlock);
                boolean destinationInBounds = true;
                for (int d = 0; d < dimensionCount; d++) {
                    destination[d] = offset[d]
                            + (long) destinationBlock[d] * blockSize + withinBlock[d];
                    sourcePosition[d] = offset[d]
                            + (long) sourceBlock[d] * blockSize + withinBlock[d];
                    if (destination[d] < 0L || destination[d] >= dimensions[d]) {
                        destinationInBounds = false;
                    }
                }
                if (!destinationInBounds) continue;
                source.setPosition(sourcePosition);
                output.setPosition(destination);
                output.get().set(source.get());
            }
        }
    }

    private static void decode(int index, int[] radices, int[] out) {
        int remaining = index;
        for (int d = 0; d < out.length; d++) {
            out[d] = remaining % radices[d];
            remaining /= radices[d];
        }
    }

    private static void decodeUniform(int index, int radix, int[] out) {
        int remaining = index;
        for (int d = 0; d < out.length; d++) {
            out[d] = remaining % radix;
            remaining /= radix;
        }
    }

    private static double mean(List<Double> values) {
        double sum = 0.0;
        for (Double value : values) {
            sum += value.doubleValue();
        }
        return sum / values.size();
    }
}
