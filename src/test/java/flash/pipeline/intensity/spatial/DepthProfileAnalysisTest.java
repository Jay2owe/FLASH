package flash.pipeline.intensity.spatial;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import ij.ImagePlus;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DepthProfileAnalysisTest {
    @Test
    public void boundaryRimSignalHasHigherRimCoreAndEdgeCouplingThanUniform() {
        DepthProfileAnalysis analysis = new DepthProfileAnalysis();
        IntensitySpatialResult uniform = analysis.measure(context(
                uniformImage(41, 41, 20.0f), null, config()));
        IntensitySpatialResult rim = analysis.measure(context(
                rimImage(41, 41, 4.0, 10.0f, 100.0f), null, config()));

        assertTrue(rim.value("Intensity_RimCoreRatio")
                > uniform.value("Intensity_RimCoreRatio"));
        assertTrue(rim.value("Intensity_EdgeCouplingIdx")
                > uniform.value("Intensity_EdgeCouplingIdx"));
        assertTrue(rim.value("Intensity_DepthBin0to10")
                > rim.value("Intensity_DepthBin10to20"));
    }

    @Test
    public void linearBoundaryGradientReportsPositiveDepthSlope() {
        IntensitySpatialResult result = new DepthProfileAnalysis().measure(context(
                boundaryGradientImage(41, 41, 5.0f, 3.0f), null, config()));

        assertTrue(result.value("Intensity_DepthSlope") > 1.0);
        assertTrue(result.value("Intensity_DepthBin10to20")
                > result.value("Intensity_DepthBin0to10"));
    }

    @Test
    public void binarizedPartnerAddsDepthColumnsAndDepthIsEitherValid() {
        DepthProfileAnalysis analysis = new DepthProfileAnalysis();
        IntensitySpatialResult rawOnly = analysis.measure(context(
                boundaryGradientImage(41, 41, 5.0f, 3.0f), null, config()));
        IntensitySpatialResult withBinarized = analysis.measure(context(
                boundaryGradientImage(41, 41, 5.0f, 3.0f),
                rimImage(41, 41, 4.0, 0.0f, 1.0f),
                config()));

        assertEquals(IntensitySpatialAnalysis.AnalysisValidity.EITHER_VALID, analysis.validity());
        assertFalse(rawOnly.values().containsKey("Intensity_DepthBin0to10_binarized"));
        assertTrue(withBinarized.values().containsKey("Intensity_DepthBin0to10_binarized"));
        assertTrue(withBinarized.values().containsKey("Intensity_EdgeCouplingIdx_binarized"));
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
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.DEPTH_PROFILE)
                .depthBinWidthUm(10.0)
                .rimDepthUm(6.0)
                .build();
    }

    private static ImagePlus uniformImage(int width, int height, float value) {
        float[] pixels = new float[width * height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = value;
        }
        return image(width, height, pixels);
    }

    private static ImagePlus rimImage(int width, int height, double rimDepth,
                                      float core, float rim) {
        float[] pixels = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double depth = edgeDepth(width, height, x, y);
                pixels[y * width + x] = depth <= rimDepth ? rim : core;
            }
        }
        return image(width, height, pixels);
    }

    private static ImagePlus boundaryGradientImage(int width, int height,
                                                   float intercept, float slope) {
        float[] pixels = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = (float) (intercept + slope * edgeDepth(width, height, x, y));
            }
        }
        return image(width, height, pixels);
    }

    private static double edgeDepth(int width, int height, int x, int y) {
        return Math.min(Math.min(x, y), Math.min(width - 1 - x, height - 1 - y));
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
