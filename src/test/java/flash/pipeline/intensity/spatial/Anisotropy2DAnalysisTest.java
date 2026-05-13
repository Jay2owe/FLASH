package flash.pipeline.intensity.spatial;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import ij.ImagePlus;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class Anisotropy2DAnalysisTest {
    @Test
    public void uniformImageProducesUndefinedOrLowCoherencyWithoutCrashing() throws Exception {
        IntensitySpatialResult result = new Anisotropy2DAnalysis().measure(context(
                uniformImage(64, 64, 25.0f), null, config()));
        double coherency = result.value(Anisotropy2DAnalysis.COLUMN_COHERENCY);

        assertTrue(Double.isNaN(coherency) || coherency < 0.1);
        assertTrue(result.values().containsKey(Anisotropy2DAnalysis.COLUMN_ANGLE));
    }

    @Test
    public void orientedStripesProduceHighCoherencyAtKnownAngle() throws Exception {
        double expectedAngle = 30.0;
        IntensitySpatialResult result = new Anisotropy2DAnalysis().measure(context(
                stripeImage(96, 96, expectedAngle, 12.0), null, config()));

        assertTrue(result.value(Anisotropy2DAnalysis.COLUMN_COHERENCY) > 0.9);
        assertTrue(angleDifference(result.value(Anisotropy2DAnalysis.COLUMN_ANGLE), expectedAngle) <= 7.0);
        assertTrue(result.value(Anisotropy2DAnalysis.COLUMN_ENTROPY) < 0.5);
    }

    @Test
    public void anisotropyIsRawOnlyAndDoesNotEmitBinarizedColumns() throws Exception {
        Anisotropy2DAnalysis analysis = new Anisotropy2DAnalysis();
        IntensitySpatialResult result = analysis.measure(context(
                stripeImage(64, 64, 0.0, 10.0),
                stripeImage(64, 64, 0.0, 10.0),
                config()));

        assertEquals(IntensitySpatialAnalysis.AnalysisValidity.RAW_ONLY, analysis.validity());
        assertFalse(result.values().containsKey("Intensity_AnisotropyCoherency_binarized"));
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
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.ANISOTROPY)
                .build();
    }

    private static ImagePlus uniformImage(int width, int height, float value) {
        float[] pixels = new float[width * height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = value;
        }
        return image(width, height, pixels);
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
