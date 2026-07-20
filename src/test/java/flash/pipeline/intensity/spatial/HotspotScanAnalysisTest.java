package flash.pipeline.intensity.spatial;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import flash.pipeline.export.ExcelNameMap;
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.DependencyService;
import flash.pipeline.runtime.DependencyStatus;
import flash.pipeline.runtime.FeatureDependencyGate;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HotspotScanAnalysisTest {
    @After
    public void resetDependencyGate() {
        FeatureDependencyGate.configure(new DependencyService(), null);
        FeatureDependencyGate.setUiMode(false);
    }

    @Test
    public void gaussianHotspotHasNonzeroFractionAndHigherStatisticThanUniform() {
        IntensitySpatialResult uniform = new HotspotScanAnalysis().measure(context(
                uniformImage(64, 64, 20.0f), null, config()));
        IntensitySpatialResult hotspot = new HotspotScanAnalysis().measure(context(
                gaussianHotspotImage(64, 64), null, config()));

        assertTrue(hotspot.value("Intensity_HotspotFraction") > 0.0);
        assertTrue(hotspot.value("Intensity_HotspotMoransI")
                > uniform.value("Intensity_HotspotMoransI"));
        assertTrue(hotspot.value("Intensity_HotspotP") <= 0.10);
    }

    @Test
    public void binarizedPartnerAddsOnlyBinarizedHotspotColumnsWhenPresent() {
        HotspotScanAnalysis analysis = new HotspotScanAnalysis();
        IntensitySpatialResult rawOnly = analysis.measure(context(
                gaussianHotspotImage(64, 64), null, config()));
        IntensitySpatialResult withBinarized = analysis.measure(context(
                gaussianHotspotImage(64, 64), thresholdedHotspotImage(64, 64), config()));

        assertFalse(rawOnly.values().containsKey("Intensity_HotspotFraction_binarized"));
        assertTrue(withBinarized.values().containsKey("Intensity_HotspotFraction_binarized"));
        assertTrue(withBinarized.values().containsKey("Intensity_HotspotMoransI_binarized"));
        assertTrue(withBinarized.values().containsKey("Intensity_HotspotP_binarized"));
    }

    @Test
    public void runnerUsesNonFftHotspotFallbackWhenFftDependencyIsMissing() throws Exception {
        installDependencyStatuses(DependencyId.IMGLIB2_FFT_RUNTIME);
        IntensitySpatialResult result = IntensitySpatialRunner.standard().measure(context(
                gaussianHotspotImage(64, 64), null, config()));

        assertTrue(result.value("Intensity_HotspotFraction") > 0.0);
        assertTrue(Double.isFinite(result.value("Intensity_HotspotMoransI")));
        assertTrue(Double.isFinite(result.value("Intensity_HotspotP")));
    }

    @Test
    public void roiOutsideImageReturnsNanInsteadOfThrowing() {
        IntensitySpatialContext context = new IntensitySpatialContext(
                config(), gaussianHotspotImage(32, 32), null, 1,
                new Roi(100, 100, 10, 10),
                IntensitySpatialOutputMode.BASE, "synthetic", "DAPI", "", null);

        IntensitySpatialResult result = new HotspotScanAnalysis().measure(context);

        assertTrue(Double.isNaN(result.value("Intensity_HotspotFraction")));
        assertTrue(Double.isNaN(result.value("Intensity_HotspotMoransI")));
        assertTrue(Double.isNaN(result.value("Intensity_HotspotP")));
        assertTrue(Double.isNaN(result.value("Intensity_HotspotSeed")));
    }

    @Test
    public void hotspotSeedHasKnownAnswerAndSeparatesRawFromBinarized() {
        IntensitySpatialContext context = context(config(123L, 17),
                gaussianHotspotImage(32, 32), thresholdedHotspotImage(32, 32),
                2, IntensitySpatialOutputMode.MIP, "image-A", "ROI-1");

        long raw = SpatialRandomSeeds.hotspot(context, false);

        assertEquals(6291487431244731L, raw);
        assertEquals(raw, (long) (double) raw);
        assertTrue(raw != SpatialRandomSeeds.hotspot(context, true));
    }

    @Test
    public void hotspotOutputsAndRecordedSeedsIgnoreTaskOrderAndWorkerCount() throws Exception {
        final IntensitySpatialConfig seeded = config(91L, 23);
        final IntensitySpatialContext contextA = context(seeded,
                gaussianHotspotImage(48, 48), thresholdedHotspotImage(48, 48),
                1, IntensitySpatialOutputMode.BASE, "image-A", "ROI-1");
        final IntensitySpatialContext contextB = context(seeded,
                gaussianHotspotImage(48, 48), thresholdedHotspotImage(48, 48),
                1, IntensitySpatialOutputMode.BASE, "image-B", "ROI-1");
        IntensitySpatialResult expectedA = new HotspotScanAnalysis().measure(contextA);
        IntensitySpatialResult expectedB = new HotspotScanAnalysis().measure(contextB);
        assertTrue(Double.isFinite(expectedA.value("Intensity_HotspotSeed")));
        assertTrue(expectedA.value("Intensity_HotspotSeed")
                != expectedA.value("Intensity_HotspotSeed_binarized"));

        List<Callable<IntensitySpatialResult>> reversed = new ArrayList<Callable<IntensitySpatialResult>>();
        reversed.add(new Callable<IntensitySpatialResult>() {
            @Override
            public IntensitySpatialResult call() {
                return new HotspotScanAnalysis().measure(contextB);
            }
        });
        reversed.add(new Callable<IntensitySpatialResult>() {
            @Override
            public IntensitySpatialResult call() {
                return new HotspotScanAnalysis().measure(contextA);
            }
        });
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<IntensitySpatialResult>> actual = pool.invokeAll(reversed);
            assertHotspotBits(expectedB, actual.get(0).get(), "");
            assertHotspotBits(expectedB, actual.get(0).get(), "_binarized");
            assertHotspotBits(expectedA, actual.get(1).get(), "");
            assertHotspotBits(expectedA, actual.get(1).get(), "_binarized");
        } finally {
            pool.shutdownNow();
        }

        IntensitySpatialResult changed = new HotspotScanAnalysis().measure(context(
                config(92L, 23), gaussianHotspotImage(48, 48),
                thresholdedHotspotImage(48, 48), 1,
                IntensitySpatialOutputMode.BASE, "image-A", "ROI-1"));
        assertTrue(expectedA.value("Intensity_HotspotSeed")
                != changed.value("Intensity_HotspotSeed"));
    }

    @Test
    public void workbookMethodsDescribeTheExecutedSeedAndBlockPolicy() {
        String[] hotspot = ExcelNameMap.convert("DAPI_ROI_Intensity_HotspotPMean");
        String[] costes = ExcelNameMap.convert("DAPI_ROI_DAPI_CostesP_mCherryMean");
        String[] costesSeed = ExcelNameMap.convert("DAPI_ROI_DAPI_CostesSeed_mCherryMean");

        assertTrue(hotspot[1].contains("effective seed"));
        assertTrue(costes[1].contains("3-pixel block"));
        assertTrue(costes[1].contains("effective seed"));
        assertTrue(costesSeed[1].contains("exact integer child seed"));
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
                        return "hotspot dependency status provider";
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

    private static IntensitySpatialContext context(ImagePlus raw,
                                                   ImagePlus binarized,
                                                   IntensitySpatialConfig config) {
        return context(config, raw, binarized, 1,
                IntensitySpatialOutputMode.BASE, "synthetic", "");
    }

    private static IntensitySpatialContext context(IntensitySpatialConfig config,
                                                   ImagePlus raw,
                                                   ImagePlus binarized,
                                                   int slice,
                                                   IntensitySpatialOutputMode mode,
                                                   String imageId,
                                                   String roiLabel) {
        return new IntensitySpatialContext(config, raw, binarized, slice, null,
                mode, imageId, "DAPI", roiLabel, null);
    }

    private static IntensitySpatialConfig config() {
        return config(1L, 99);
    }

    private static IntensitySpatialConfig config(long seed, int permutations) {
        return IntensitySpatialConfig.builder()
                .enabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.HOTSPOTSCAN)
                .permutations(permutations)
                .seed(seed)
                .build();
    }

    private static void assertHotspotBits(IntensitySpatialResult expected,
                                          IntensitySpatialResult actual,
                                          String suffix) {
        assertSameBits(expected.value("Intensity_HotspotFraction" + suffix),
                actual.value("Intensity_HotspotFraction" + suffix));
        assertSameBits(expected.value("Intensity_HotspotMoransI" + suffix),
                actual.value("Intensity_HotspotMoransI" + suffix));
        assertSameBits(expected.value("Intensity_HotspotP" + suffix),
                actual.value("Intensity_HotspotP" + suffix));
        assertSameBits(expected.value("Intensity_HotspotSeed" + suffix),
                actual.value("Intensity_HotspotSeed" + suffix));
    }

    private static void assertSameBits(double expected, double actual) {
        assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(actual));
    }

    private static ImagePlus uniformImage(int width, int height, float value) {
        float[] pixels = new float[width * height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = value;
        }
        return image(width, height, pixels);
    }

    private static ImagePlus gaussianHotspotImage(int width, int height) {
        float[] pixels = new float[width * height];
        double cx = (width - 1) / 2.0;
        double cy = (height - 1) / 2.0;
        double sigma = 7.0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double dx = x - cx;
                double dy = y - cy;
                pixels[y * width + x] = (float) (10.0
                        + 120.0 * Math.exp(-(dx * dx + dy * dy) / (2.0 * sigma * sigma)));
            }
        }
        return image(width, height, pixels);
    }

    private static ImagePlus thresholdedHotspotImage(int width, int height) {
        float[] pixels = new float[width * height];
        double cx = (width - 1) / 2.0;
        double cy = (height - 1) / 2.0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double dx = x - cx;
                double dy = y - cy;
                pixels[y * width + x] = dx * dx + dy * dy <= 100.0 ? 1.0f : 0.0f;
            }
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
}
