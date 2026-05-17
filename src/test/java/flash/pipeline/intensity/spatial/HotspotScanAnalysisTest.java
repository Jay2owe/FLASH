package flash.pipeline.intensity.spatial;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
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
import java.util.EnumMap;

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
        return new IntensitySpatialContext(config, raw, binarized, 1, null,
                IntensitySpatialOutputMode.BASE, "synthetic", "DAPI", "", null);
    }

    private static IntensitySpatialConfig config() {
        return IntensitySpatialConfig.builder()
                .enabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.HOTSPOTSCAN)
                .permutations(99)
                .seed(1L)
                .build();
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
