package flash.pipeline.intensity.spatial;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.DependencyService;
import flash.pipeline.runtime.DependencyStatus;
import flash.pipeline.runtime.FeatureDependencyGate;
import ij.ImagePlus;
import ij.IJ;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;

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

        assertTrue(Double.isNaN(result.value("DAPI_Pearson_mCherry")));
        assertTrue(Double.isNaN(result.value("DAPI_MandersM1_mCherry")));
        assertTrue(Double.isFinite(result.value("DAPI_CCFPeakAmp_mCherry")));
        assertTrue(Double.isFinite(result.value("DAPI_MarkCorrStrength_mCherry")));
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
        return new IntensitySpatialPairContext(config, source, sourceBinarized, sourceMask,
                partner, partnerBinarized, partnerMask, 1, null,
                IntensitySpatialOutputMode.BASE, "synthetic", "DAPI", "mCherry", "", null);
    }

    private static IntensitySpatialConfig config() {
        return IntensitySpatialConfig.builder()
                .enabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.CROSSMARK, IntensitySpatialOutputMode.BASE)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.CROSSMARK, IntensitySpatialOutputMode.MIP)
                .permutations(9)
                .build();
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

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
