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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PeriodicityAnalysisTest {
    @After
    public void resetDependencyGate() {
        FeatureDependencyGate.configure(new DependencyService(), null);
        FeatureDependencyGate.setUiMode(false);
    }

    @Test
    public void periodicStripesReportKnownWavelengthAndStripeAngle() {
        double expectedAngle = 0.0;
        double expectedWavelength = 12.0;

        IntensitySpatialResult result = new PeriodicityAnalysis().measure(context(
                stripeImage(96, 96, expectedAngle, expectedWavelength),
                null,
                periodicityConfig()));

        assertEquals(expectedWavelength,
                result.value(PeriodicityAnalysis.COLUMN_WAVELENGTH), 0.75);
        assertTrue(angleDifference(result.value(PeriodicityAnalysis.COLUMN_ANGLE), expectedAngle) <= 8.0);
        assertTrue(result.value(PeriodicityAnalysis.COLUMN_STRIPINESS) > 5.0);
        assertTrue(result.value(PeriodicityAnalysis.COLUMN_PEAK_POWER) > 0.05);
    }

    @Test
    public void periodicityIsRawOnlyAndDoesNotEmitBinarizedColumns() {
        PeriodicityAnalysis analysis = new PeriodicityAnalysis();
        IntensitySpatialResult result = analysis.measure(context(
                stripeImage(64, 64, 0.0, 8.0),
                stripeImage(64, 64, 0.0, 8.0),
                periodicityConfig()));

        assertEquals(IntensitySpatialAnalysis.AnalysisValidity.RAW_ONLY, analysis.validity());
        assertFalse(result.values().containsKey("Intensity_PeriodicityWavelength_um_binarized"));
    }

    @Test
    public void runnerSkipsOnlyFftBackedFamilyWhenJTransformsIsMissing() throws Exception {
        installDependencyStatuses(DependencyId.JTRANSFORMS_RUNTIME);
        IntensitySpatialRunner runner = IntensitySpatialRunner.standard();

        IntensitySpatialResult result = runner.measure(context(
                stripeImage(64, 64, 0.0, 8.0),
                null,
                IntensitySpatialConfig.builder()
                        .enabled(true)
                        .addAnalysis(IntensitySpatialConfig.AnalysisKey.PERIODICITY)
                        .addAnalysis(IntensitySpatialConfig.AnalysisKey.GLCM)
                        .build()));

        assertTrue(Double.isNaN(result.value(PeriodicityAnalysis.COLUMN_WAVELENGTH)));
        assertTrue(Double.isFinite(result.value(GlcmTextureAnalysis.COLUMN_CONTRAST)));
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
                        return "periodicity dependency status provider";
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

    private static IntensitySpatialConfig periodicityConfig() {
        return IntensitySpatialConfig.builder()
                .enabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.PERIODICITY)
                .build();
    }

    private static ImagePlus stripeImage(int width, int height,
                                         double orientationDegrees,
                                         double periodPixels) {
        float[] pixels = new float[width * height];
        double orientation = Math.toRadians(orientationDegrees);
        double normal = Math.PI / 2.0 - orientation;
        double cx = (width - 1) / 2.0;
        double cy = (height - 1) / 2.0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double coordinate = (x - cx) * Math.cos(normal) + (y - cy) * Math.sin(normal);
                pixels[y * width + x] = (float) (100.0
                        + 80.0 * Math.sin(2.0 * Math.PI * coordinate / periodPixels));
            }
        }
        return image(width, height, pixels);
    }

    private static double angleDifference(double actual, double expected) {
        double diff = Math.abs(actual - expected) % 180.0;
        return diff > 90.0 ? 180.0 - diff : diff;
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
