package flash.pipeline.intensity.spatial;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.DependencyService;
import flash.pipeline.runtime.DependencyStatus;
import flash.pipeline.runtime.FeatureDependencyGate;
import ij.ImagePlus;
import ij.IJ;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CrossMark2DAnalysisTest {
    @After
    public void resetDependencyGate() {
        FeatureDependencyGate.configure(new DependencyService(), null);
        FeatureDependencyGate.setUiMode(false);
        System.clearProperty(SpatialResourceGuards.MAX_MIP_PIXELS_PROPERTY);
        System.clearProperty(SpatialResourceGuards.MAX_PAIR_PLANE_PIXELS_PROPERTY);
        System.clearProperty(SpatialResourceGuards.MAX_COLOC_IMAGE_PIXELS_PROPERTY);
    }

    @Test
    public void colocatedFixtureProducesHigherPearsonAndMandersThanAntiCorrelatedFixture() throws Exception {
        installDependencyStatuses(null);
        ImagePlus source = gradientImage(32, 32, false);
        ImagePlus colocated = gradientImage(32, 32, false);
        ImagePlus anti = gradientImage(32, 32, true);

        IntensitySpatialResult colocatedResult = new CrossMark2DAnalysis().measure(context(
                source, binarizedRaw(source, 96.0), binaryMask(source, 96.0),
                colocated, binarizedRaw(colocated, 96.0), binaryMask(colocated, 96.0)));
        IntensitySpatialResult antiResult = new CrossMark2DAnalysis().measure(context(
                source, binarizedRaw(source, 96.0), binaryMask(source, 96.0),
                anti, binarizedRaw(anti, 96.0), binaryMask(anti, 96.0)));

        assertTrue(colocatedResult.value("DAPI_Pearson_mCherry") > 0.95);
        assertTrue(antiResult.value("DAPI_Pearson_mCherry") < -0.85);
        assertTrue(colocatedResult.value("DAPI_MandersM1_mCherry_binarized")
                > antiResult.value("DAPI_MandersM1_mCherry_binarized"));
        assertTrue(colocatedResult.value("DAPI_MandersM2_mCherry_binarized")
                > antiResult.value("DAPI_MandersM2_mCherry_binarized"));
    }

    @Test
    public void pairColumnsUseSourceMetricPartnerAndBinarizedOnlyAtEnd() {
        List<String> columns = new CrossMark2DAnalysis().columns(config(), "DAPI", "mCherry",
                true, true);

        assertTrue(columns.contains("DAPI_Pearson_mCherry"));
        assertTrue(columns.contains("DAPI_CostesP_mCherry_binarized"));
        assertTrue(columns.contains("DAPI_MandersM1_mCherry_binarized"));
        for (String column : columns) {
            if (column.contains("_binarized")) {
                assertTrue(column.endsWith("_binarized"));
                assertFalse(column.contains("_binarized_"));
            }
        }
    }

    @Test
    public void zeroPermutationsSkipCostesPButKeepOtherCrossmarkMetrics() throws Exception {
        installDependencyStatuses(null);
        IntensitySpatialConfig noRandomization = IntensitySpatialConfig.builder()
                .enabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.CROSSMARK)
                .permutations(0)
                .build();
        ImagePlus source = gradientImage(32, 32, false);
        ImagePlus partner = gradientImage(32, 32, false);

        IntensitySpatialResult result = new CrossMark2DAnalysis().measure(context(noRandomization,
                source, null, null, partner, null, null));

        assertTrue(Double.isNaN(result.value("DAPI_CostesP_mCherry")));
        assertTrue(Double.isFinite(result.value("DAPI_Pearson_mCherry")));
        assertTrue(Double.isFinite(result.value("DAPI_MandersM1_mCherry")));
        assertTrue(Double.isFinite(result.value("DAPI_CCFPeakAmp_mCherry")));
        assertTrue(Double.isFinite(result.value("DAPI_MarkCorrStrength_mCherry")));
    }

    @Test
    public void separateCostesPermutationsControlSkipsCostesWithoutChangingHotspotPermutations() throws Exception {
        installDependencyStatuses(null);
        IntensitySpatialConfig noCostesRandomization = IntensitySpatialConfig.builder()
                .enabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.CROSSMARK)
                .permutations(9)
                .costesPermutations(0)
                .build();
        ImagePlus source = gradientImage(32, 32, false);
        ImagePlus partner = gradientImage(32, 32, false);

        IntensitySpatialResult result = new CrossMark2DAnalysis().measure(context(noCostesRandomization,
                source, null, null, partner, null, null));

        assertTrue(noCostesRandomization.getPermutations() == 9);
        assertTrue(noCostesRandomization.getCostesPermutations() == 0);
        assertTrue(Double.isNaN(result.value("DAPI_CostesP_mCherry")));
        assertTrue(Double.isFinite(result.value("DAPI_MandersM1_mCherry")));
    }

    @Test
    public void largeColocPlaneSkipsColocCopiesButKeepsDirectSpatialMetrics() throws Exception {
        installDependencyStatuses(null);
        System.setProperty(SpatialResourceGuards.MAX_COLOC_IMAGE_PIXELS_PROPERTY, "4");
        ImagePlus source = gradientImage(3, 3, false);
        ImagePlus partner = gradientImage(3, 3, false);

        IntensitySpatialResult result = new CrossMark2DAnalysis().measure(context(
                source, null, null, partner, null, null));

        assertTrue(result.value("DAPI_Pearson_mCherry") > 0.95);
        assertTrue(Double.isNaN(result.value("DAPI_CostesP_mCherry")));
        assertTrue(Double.isNaN(result.value("DAPI_MandersM1_mCherry")));
        assertTrue(Double.isFinite(result.value("DAPI_CCFPeakAmp_mCherry")));
        assertTrue(Double.isFinite(result.value("DAPI_MarkCorrStrength_mCherry")));
    }

    @Test
    public void fastCrossCorrelationDoesNotRequireColoc2OrEmitFullCrossmarkColumns() throws Exception {
        installDependencyStatuses(DependencyId.COLOC2_RUNTIME);
        IntensitySpatialConfig fastOnly = IntensitySpatialConfig.builder()
                .enabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.CROSSCORR_FAST,
                        IntensitySpatialOutputMode.BASE)
                .build();
        ImagePlus source = gradientImage(32, 32, false);
        ImagePlus partner = gradientImage(32, 32, false);

        IntensitySpatialResult result = IntensitySpatialRunner.standard().measurePair(
                context(fastOnly, source, null, null, partner, null, null));

        assertTrue(Double.isFinite(result.value("DAPI_Pearson_mCherry")));
        assertTrue(Double.isFinite(result.value("DAPI_CCFPeakAmp_mCherry")));
        assertFalse(result.values().containsKey("DAPI_MarkCorrStrength_mCherry"));
        assertFalse(result.values().containsKey("DAPI_MandersM1_mCherry"));
    }

    @Test
    public void fastCrossCorrelationBytecodeDoesNotReferenceFullCrossmarkOrColoc2() throws Exception {
        assertClassFileDoesNotReference(FastCrossCorrelation2DAnalysis.class,
                "CrossMark2DAnalysis", "sc/fiji/coloc");
        assertClassFileDoesNotReference(FastCrossCorrelation2DCore.class,
                "CrossMark2DAnalysis", "sc/fiji/coloc");
    }

    @Test
    public void combinedFastAndFullCrossmarkKeepFastPearsonWhenColoc2Unavailable() throws Exception {
        installDependencyStatuses(DependencyId.COLOC2_RUNTIME);
        IntensitySpatialConfig combined = IntensitySpatialConfig.builder()
                .enabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.CROSSCORR_FAST,
                        IntensitySpatialOutputMode.BASE)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.CROSSMARK,
                        IntensitySpatialOutputMode.BASE)
                .build();
        ImagePlus source = gradientImage(32, 32, false);
        ImagePlus partner = gradientImage(32, 32, false);

        IntensitySpatialResult result = IntensitySpatialRunner.standard().measurePair(
                context(combined, source, null, null, partner, null, null));

        assertTrue(Double.isFinite(result.value("DAPI_Pearson_mCherry")));
        assertTrue(Double.isFinite(result.value("DAPI_CCFPeakAmp_mCherry")));
        assertTrue(Double.isFinite(result.value("DAPI_MarkCorrStrength_mCherry")));
        assertTrue(Double.isNaN(result.value("DAPI_MandersM1_mCherry")));
    }

    @Test
    public void fastCrossCorrelationStillRunsWhenFullCrossmarkIsSelectedButNotRegistered() throws Exception {
        installDependencyStatuses(null);
        IntensitySpatialConfig combined = IntensitySpatialConfig.builder()
                .enabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.CROSSCORR_FAST,
                        IntensitySpatialOutputMode.BASE)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.CROSSMARK,
                        IntensitySpatialOutputMode.BASE)
                .build();
        ImagePlus source = gradientImage(32, 32, false);
        ImagePlus partner = gradientImage(32, 32, false);
        IntensitySpatialRunner fastOnlyRunner = new IntensitySpatialRunner(
                Collections.<IntensitySpatialAnalysis>emptyList(),
                Collections.<IntensitySpatialPairAnalysis>singletonList(
                        new FastCrossCorrelation2DAnalysis()));

        IntensitySpatialResult result = fastOnlyRunner.measurePair(
                context(combined, source, null, null, partner, null, null));

        assertTrue(Double.isFinite(result.value("DAPI_Pearson_mCherry")));
        assertTrue(Double.isFinite(result.value("DAPI_CCFPeakAmp_mCherry")));
        assertFalse(result.values().containsKey("DAPI_MarkCorrStrength_mCherry"));
    }

    @Test
    public void mipPairProgressLogNamesBothProjectedImages() throws Exception {
        installDependencyStatuses(DependencyId.COLOC2_RUNTIME);
        ImagePlus source = gradientImage(16, 16, false);
        ImagePlus partner = gradientImage(16, 16, true);

        String log = captureImageJLogOutput(new ThrowingRunnable() {
            @Override
            public void run() {
                IntensitySpatialRunner.standardWithProgress().measurePair(new IntensitySpatialPairContext(
                        config(), source, null, null, partner, null, null, 1, null,
                        IntensitySpatialOutputMode.MIP, "synthetic", "DAPI", "mCherry",
                        "SCN2", null));
            }
        });

        assertTrue(log.contains("source DAPI MIP -> partner mCherry MIP ROI SCN2"));
    }

    @Test
    public void missingColocSkipsOnlyColocBackedCrossmarkColumns() throws Exception {
        installDependencyStatuses(DependencyId.COLOC2_RUNTIME);
        ImagePlus source = gradientImage(32, 32, false);
        ImagePlus partner = gradientImage(32, 32, false);

        IntensitySpatialResult result = IntensitySpatialRunner.standard().measurePair(context(
                source, null, null, partner, null, null));

        assertTrue(Double.isFinite(result.value("DAPI_Pearson_mCherry")));
        assertTrue(Double.isNaN(result.value("DAPI_MandersM1_mCherry")));
        assertTrue(Double.isFinite(result.value("DAPI_CCFPeakAmp_mCherry")));
        assertTrue(Double.isFinite(result.value("DAPI_MarkCorrStrength_mCherry")));
    }

    @Test
    public void seedDerivationHasAnIndependentKnownAnswerAndContextBoundaries() {
        IntensitySpatialConfig seeded = config(123L, 11);
        IntensitySpatialPairContext mip = context(seeded,
                gradientImage(8, 8, false), null, null,
                gradientImage(8, 8, true), null, null,
                2, IntensitySpatialOutputMode.MIP, "image-A", "ROI-1");

        long actual = SpatialRandomSeeds.costes(mip, false, "2d");

        assertEquals(2032943225298541L, actual);
        assertEquals(actual, (long) (double) actual);
        assertTrue(actual != SpatialRandomSeeds.costes(mip, true, "2d"));
        assertTrue(actual != SpatialRandomSeeds.costes(mip, false, "3d"));
        assertTrue(actual != SpatialRandomSeeds.derive(124L,
                "costes", "2d", "image-A", "DAPI", "mCherry", "ROI-1",
                "2", "MIP", "raw"));
    }

    @Test
    public void seededCostesIsBitwiseRepeatableAcrossParallelSchedulingAndNative3d() throws Exception {
        installDependencyStatuses(null);
        IntensitySpatialConfig seeded = config(731L, 13);
        ImagePlus source2d = patternedImage(15, 15, 0);
        ImagePlus partner2d = patternedImage(15, 15, 9);
        final IntensitySpatialPairContext pair2d = context(seeded,
                source2d, null, null, partner2d, null, null,
                1, IntensitySpatialOutputMode.BASE, "schedule-image", "ROI-A");
        IntensitySpatialResult expected2d = new CrossMark2DAnalysis().measure(pair2d);
        assertTrue(Double.isFinite(expected2d.value("DAPI_CostesP_mCherry")));
        assertTrue(Double.isFinite(expected2d.value("DAPI_CostesSeed_mCherry")));

        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Callable<IntensitySpatialResult>> tasks = new ArrayList<Callable<IntensitySpatialResult>>();
            for (int i = 0; i < 3; i++) {
                tasks.add(new Callable<IntensitySpatialResult>() {
                    @Override
                    public IntensitySpatialResult call() {
                        return new CrossMark2DAnalysis().measure(pair2d);
                    }
                });
            }
            Collections.reverse(tasks);
            for (Future<IntensitySpatialResult> future : pool.invokeAll(tasks)) {
                assertSameBits(expected2d.value("DAPI_CostesP_mCherry"),
                        future.get().value("DAPI_CostesP_mCherry"));
                assertSameBits(expected2d.value("DAPI_CostesSeed_mCherry"),
                        future.get().value("DAPI_CostesSeed_mCherry"));
            }
        } finally {
            pool.shutdownNow();
        }

        IntensitySpatialConfig changedSeed = config(732L, 13);
        IntensitySpatialResult changed = new CrossMark2DAnalysis().measure(context(changedSeed,
                source2d, null, null, partner2d, null, null,
                1, IntensitySpatialOutputMode.BASE, "schedule-image", "ROI-A"));
        assertTrue(expected2d.value("DAPI_CostesSeed_mCherry")
                != changed.value("DAPI_CostesSeed_mCherry"));

        ImagePlus source3d = patternedStack(12, 12, 3, 0);
        ImagePlus partner3d = patternedStack(12, 12, 3, 5);
        IntensitySpatialPairContext pair3d = context(seeded,
                source3d, null, null, partner3d, null, null,
                1, IntensitySpatialOutputMode.NATIVE_3D, "schedule-image", "ROI-A");
        IntensitySpatialResult first3d = new CrossMark3DAnalysis().measure(pair3d);
        IntensitySpatialResult second3d = new CrossMark3DAnalysis().measure(pair3d);
        assertTrue(Double.isFinite(first3d.value("DAPI_CostesP3D_mCherry")));
        assertSameBits(first3d.value("DAPI_CostesP3D_mCherry"),
                second3d.value("DAPI_CostesP3D_mCherry"));
        assertSameBits(first3d.value("DAPI_CostesSeed3D_mCherry"),
                second3d.value("DAPI_CostesSeed3D_mCherry"));
    }

    private static void installDependencyStatuses(final DependencyId missing) throws Exception {
        final EnumMap<DependencyId, DependencyStatus> statuses =
                new EnumMap<DependencyId, DependencyStatus>(DependencyId.class);
        for (DependencyId id : DependencyId.values()) {
            statuses.put(id, id == missing
                    ? DependencyStatus.missing(id.name() + " missing")
                    : DependencyStatus.present(id.name() + " present"));
        }

        Class<?> providerType = Class.forName(
                "flash.pipeline.runtime.DependencyService$StatusSnapshotProvider");
        Object provider = Proxy.newProxyInstance(
                providerType.getClassLoader(),
                new Class<?>[]{providerType},
                (proxyObject, method, args) -> {
                    if ("snapshot".equals(method.getName())) {
                        return new EnumMap<DependencyId, DependencyStatus>(statuses);
                    }
                    if ("toString".equals(method.getName())) {
                        return "crossmark dependency status provider";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return Integer.valueOf(System.identityHashCode(proxyObject));
                    }
                    if ("equals".equals(method.getName())) {
                        return Boolean.valueOf(proxyObject == args[0]);
                    }
                    return null;
                });
        Constructor<DependencyService> ctor = DependencyService.class.getDeclaredConstructor(providerType);
        ctor.setAccessible(true);
        FeatureDependencyGate.configure(ctor.newInstance(provider), null);
        FeatureDependencyGate.setUiMode(false);
    }

    private static IntensitySpatialPairContext context(ImagePlus source,
                                                       ImagePlus sourceBinarized,
                                                       ImagePlus sourceMask,
                                                       ImagePlus partner,
                                                       ImagePlus partnerBinarized,
                                                       ImagePlus partnerMask) {
        return context(config(), source, sourceBinarized, sourceMask,
                partner, partnerBinarized, partnerMask);
    }

    private static IntensitySpatialPairContext context(IntensitySpatialConfig config,
                                                       ImagePlus source,
                                                       ImagePlus sourceBinarized,
                                                       ImagePlus sourceMask,
                                                       ImagePlus partner,
                                                       ImagePlus partnerBinarized,
                                                       ImagePlus partnerMask) {
        return context(config, source, sourceBinarized, sourceMask,
                partner, partnerBinarized, partnerMask, 1,
                IntensitySpatialOutputMode.BASE, "synthetic", "");
    }

    private static IntensitySpatialPairContext context(IntensitySpatialConfig config,
                                                       ImagePlus source,
                                                       ImagePlus sourceBinarized,
                                                       ImagePlus sourceMask,
                                                       ImagePlus partner,
                                                       ImagePlus partnerBinarized,
                                                       ImagePlus partnerMask,
                                                       int slice,
                                                       IntensitySpatialOutputMode mode,
                                                       String imageId,
                                                       String roiLabel) {
        return new IntensitySpatialPairContext(config, source, sourceBinarized, sourceMask,
                partner, partnerBinarized, partnerMask, slice, null,
                mode, imageId, "DAPI", "mCherry", roiLabel, null);
    }

    private static IntensitySpatialConfig config() {
        return config(IntensitySpatialConfig.DEFAULT_SEED, 9);
    }

    private static IntensitySpatialConfig config(long seed, int costesPermutations) {
        return IntensitySpatialConfig.builder()
                .enabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.CROSSMARK, IntensitySpatialOutputMode.BASE)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.CROSSMARK, IntensitySpatialOutputMode.MIP)
                .permutations(9)
                .costesPermutations(costesPermutations)
                .seed(seed)
                .build();
    }

    private static ImagePlus patternedImage(int width, int height, int phase) {
        float[] pixels = patternedPixels(width, height, 0, phase);
        return image(width, height, pixels);
    }

    private static ImagePlus patternedStack(int width, int height, int depth, int phase) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            stack.addSlice(new FloatProcessor(width, height,
                    patternedPixels(width, height, z, phase), null));
        }
        ImagePlus image = new ImagePlus("patterned-stack", stack);
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 1.0;
        calibration.pixelHeight = 1.0;
        calibration.pixelDepth = 1.5;
        calibration.setUnit("um");
        image.setCalibration(calibration);
        return image;
    }

    private static float[] patternedPixels(int width, int height, int z, int phase) {
        float[] pixels = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int texture = (x * 17 + y * 31 + z * 13 + x * y * 3 + phase * 11) % 97;
                pixels[y * width + x] = (float) (5.0 + texture
                        + ((x + phase) % 4 == 0 ? 18.0 : 0.0));
            }
        }
        return pixels;
    }

    private static void assertSameBits(double expected, double actual) {
        assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(actual));
    }

    private static ImagePlus gradientImage(int width, int height, boolean inverse) {
        float[] pixels = new float[width * height];
        double max = (width - 1) + (height - 1);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double value = x + y;
                if (inverse) value = max - value;
                pixels[y * width + x] = (float) (10.0 + value * 4.0);
            }
        }
        return image(width, height, pixels);
    }

    private static ImagePlus binarizedRaw(ImagePlus raw, double threshold) {
        int width = raw.getWidth();
        int height = raw.getHeight();
        float[] pixels = new float[width * height];
        float[] rawPixels = (float[]) raw.getProcessor().getPixels();
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = rawPixels[i] >= threshold ? rawPixels[i] : 0.0f;
        }
        return image(width, height, pixels);
    }

    private static ImagePlus binaryMask(ImagePlus raw, double threshold) {
        int width = raw.getWidth();
        int height = raw.getHeight();
        float[] pixels = new float[width * height];
        float[] rawPixels = (float[]) raw.getProcessor().getPixels();
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = rawPixels[i] >= threshold ? 255.0f : 0.0f;
        }
        return image(width, height, pixels);
    }

    private static ImagePlus image(int width, int height, float[] pixels) {
        ImagePlus image = new ImagePlus("synthetic",
                new FloatProcessor(width, height, pixels, null));
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 1.0;
        calibration.pixelHeight = 1.0;
        calibration.setUnit("um");
        image.setCalibration(calibration);
        return image;
    }

    private static String captureImageJLogOutput(ThrowingRunnable runnable) throws Exception {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8.name()));
        String ijLog = null;
        try {
            if (IJ.getLog() != null) IJ.log("\\Clear");
            runnable.run();
            ijLog = IJ.getLog();
        } finally {
            System.out.flush();
            System.setOut(originalOut);
        }
        return out.toString(StandardCharsets.UTF_8.name()) + (ijLog == null ? "" : ijLog);
    }

    private static void assertClassFileDoesNotReference(Class<?> type, String... references) throws Exception {
        String classText = new String(classBytes(type), StandardCharsets.ISO_8859_1);
        for (String reference : references) {
            assertFalse(type.getSimpleName() + " should not reference " + reference,
                    classText.contains(reference));
        }
    }

    private static byte[] classBytes(Class<?> type) throws Exception {
        InputStream in = type.getResourceAsStream(type.getSimpleName() + ".class");
        assertTrue("Missing class resource for " + type.getName(), in != null);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
