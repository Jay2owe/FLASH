package flash.pipeline.intensity.spatial;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.DependencyService;
import flash.pipeline.runtime.DependencyStatus;
import flash.pipeline.runtime.FeatureDependencyGate;
import ij.ImagePlus;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.EnumMap;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GranularityAnalysisTest {
    @After
    public void resetDependencyGate() {
        FeatureDependencyGate.configure(new DependencyService(), null);
        FeatureDependencyGate.setUiMode(false);
    }

    @Test
    public void checkerboardScaleReportsPeakAndCentroidNearKnownScale() {
        IntensitySpatialResult result = new GranularityAnalysis().measure(context(
                checkerboardImage(64, 64, 8, 10.0f, 90.0f), null, config()));

        assertTrue(Math.abs(result.value("Intensity_GranularityPeak_um") - 8.0) <= 0.5);
        assertTrue(Math.abs(result.value("Intensity_GranularityCentroid_um") - 8.0) <= 1.0);
        assertTrue(result.value("Intensity_GranularityEnergy8")
                > result.value("Intensity_GranularityEnergy4"));
        assertTrue(result.value("Intensity_GranularityEnergy8")
                > result.value("Intensity_GranularityEnergy16"));
    }

    @Test
    public void binarizedPartnerAddsOnlyBinarizedGranularityColumnsWhenPresent() {
        GranularityAnalysis analysis = new GranularityAnalysis();
        IntensitySpatialResult rawOnly = analysis.measure(context(
                checkerboardImage(64, 64, 8, 10.0f, 90.0f), null, config()));
        IntensitySpatialResult withBinarized = analysis.measure(context(
                checkerboardImage(64, 64, 8, 10.0f, 90.0f),
                checkerboardImage(64, 64, 8, 0.0f, 1.0f),
                config()));

        assertFalse(rawOnly.values().containsKey("Intensity_GranularityPeak_um_binarized"));
        assertTrue(withBinarized.values().containsKey("Intensity_GranularityPeak_um_binarized"));
        assertTrue(withBinarized.values().containsKey("Intensity_GranularityEnergy8_binarized"));
    }

    @Test
    public void runnerSkipsGranularityWhenImgLib2AlgorithmDependencyIsMissing() throws Exception {
        installDependencyStatuses(DependencyId.IMGLIB2_ALGORITHM_RUNTIME);
        IntensitySpatialRunner runner = IntensitySpatialRunner.standard();

        IntensitySpatialResult result = runner.measure(context(
                checkerboardImage(64, 64, 8, 10.0f, 90.0f), null, config()));

        assertTrue(Double.isNaN(result.value("Intensity_GranularityPeak_um")));
        assertTrue(Double.isNaN(result.value("Intensity_GranularityEnergy8")));
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
                        return "granularity dependency status provider";
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
        return new IntensitySpatialContext(config, raw, binarized, 1, null,
                IntensitySpatialOutputMode.BASE, "synthetic", "DAPI", "", null);
    }

    private static IntensitySpatialConfig config() {
        return IntensitySpatialConfig.builder()
                .enabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.GRANULARITY)
                .granularityScalesUm(new double[]{2.0, 4.0, 8.0, 16.0, 32.0})
                .build();
    }

    private static ImagePlus checkerboardImage(int width, int height, int tileSize,
                                               float low, float high) {
        float[] pixels = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean highTile = ((x / tileSize) + (y / tileSize)) % 2 == 0;
                pixels[y * width + x] = highTile ? high : low;
            }
        }
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
