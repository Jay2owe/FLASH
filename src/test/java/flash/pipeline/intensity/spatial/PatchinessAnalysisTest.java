package flash.pipeline.intensity.spatial;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import ij.ImagePlus;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PatchinessAnalysisTest {
    @Test
    public void uniformRoiHasNearZeroPatchiness() {
        IntensitySpatialResult result = new PatchinessAnalysis().measure(context(
                uniformImage(8, 8, 25.0f), null, config()));

        assertEquals(0.0, result.value("Intensity_PatchinessCV2"), 1e-9);
        assertEquals(0.0, result.value("Intensity_PatchinessGini"), 1e-9);
        assertEquals(0.0, result.value("Intensity_Lacunarity2"), 1e-9);
    }

    @Test
    public void tiledIntensityHasHigherTileCvAndLacunarityThanUniform() {
        IntensitySpatialResult uniform = new PatchinessAnalysis().measure(context(
                uniformImage(8, 8, 25.0f), null, config()));
        IntensitySpatialResult tiled = new PatchinessAnalysis().measure(context(
                tiledImage(8, 8, 2, 10.0f, 90.0f), null, config()));

        assertTrue(tiled.value("Intensity_PatchinessCV2")
                > uniform.value("Intensity_PatchinessCV2"));
        assertTrue(tiled.value("Intensity_Lacunarity2")
                > uniform.value("Intensity_Lacunarity2"));
        assertTrue(tiled.value("Intensity_PatchinessGini")
                > uniform.value("Intensity_PatchinessGini"));
    }

    @Test
    public void binarizedPartnerAddsBinarizedPatchinessColumns() {
        IntensitySpatialResult result = new PatchinessAnalysis().measure(context(
                tiledImage(8, 8, 2, 10.0f, 90.0f),
                tiledImage(8, 8, 2, 0.0f, 90.0f),
                config()));

        assertTrue(result.values().containsKey("Intensity_PatchinessCV2_binarized"));
        assertTrue(result.values().containsKey("Intensity_Lacunarity2_binarized"));
    }

    @Test
    public void emptyRoiReturnsNanAndWarns() {
        final List<String> warnings = new ArrayList<String>();
        IntensitySpatialContext context = new IntensitySpatialContext(
                config(), uniformImage(8, 8, 25.0f), null, 1,
                new ij.gui.Roi(20, 20, 3, 3), IntensitySpatialOutputMode.BASE,
                "synthetic", "DAPI", "outside", new IntensitySpatialContext.WarningSink() {
            @Override
            public void warn(String message) {
                warnings.add(message);
            }
        });

        IntensitySpatialResult result = new PatchinessAnalysis().measure(context);

        assertTrue(Double.isNaN(result.value("Intensity_PatchinessCV2")));
        assertFalse(warnings.isEmpty());
    }

    @Test
    public void tileScaleUsesNanometerCalibrationAsMicrons() {
        IntensitySpatialResult result = new PatchinessAnalysis().measure(context(
                halfSplitImage(4, 4, 10.0f, 90.0f, 500.0, 500.0, "nm"), null,
                singleScaleConfig()));

        assertEquals(0.0, result.value("Intensity_PatchinessCV2"), 1e-9);
        assertEquals(0.0, result.value("Intensity_Lacunarity2"), 1e-9);
    }

    @Test
    public void negativeAndMixedSignGiniAreUndefinedAndDiagnosed() {
        final List<String> negativeWarnings = new ArrayList<String>();
        IntensitySpatialResult negative = new PatchinessAnalysis().measure(context(
                image(2, 2, new float[]{-4.0f, -3.0f, -2.0f, -1.0f}), null,
                singleScaleConfig(), negativeWarnings));

        final List<String> mixedWarnings = new ArrayList<String>();
        IntensitySpatialResult mixed = new PatchinessAnalysis().measure(context(
                image(2, 1, new float[]{-1.0f, 2.0f}), null,
                singleScaleConfig(), mixedWarnings));

        assertTrue(Double.isNaN(negative.value("Intensity_PatchinessGini")));
        assertTrue(Double.isNaN(mixed.value("Intensity_PatchinessGini")));
        assertWarningContains(negativeWarnings, "Gini", "undefined", "negative");
        assertWarningContains(mixedWarnings, "Gini", "undefined", "negative");
    }

    @Test
    public void nonFiniteGiniInputsAreMissingAndDiagnosed() {
        final List<String> nanWarnings = new ArrayList<String>();
        IntensitySpatialResult nanResult = new PatchinessAnalysis().measure(context(
                image(2, 2, new float[]{0.0f, 1.0f, Float.NaN, 3.0f}), null,
                singleScaleConfig(), nanWarnings));

        final List<String> infinityWarnings = new ArrayList<String>();
        IntensitySpatialResult infinityResult = new PatchinessAnalysis().measure(context(
                image(2, 2, new float[]{0.0f, 1.0f, Float.POSITIVE_INFINITY, 3.0f}), null,
                singleScaleConfig(), infinityWarnings));

        assertTrue(Double.isNaN(nanResult.value("Intensity_PatchinessGini")));
        assertTrue(Double.isNaN(infinityResult.value("Intensity_PatchinessGini")));
        assertWarningContains(nanWarnings, "Gini", "undefined", "non-finite");
        assertWarningContains(infinityWarnings, "Gini", "undefined", "non-finite");
    }

    @Test
    public void nonnegativeGiniMatchesIndependentHandComputedOracle() {
        IntensitySpatialResult result = new PatchinessAnalysis().measure(context(
                image(3, 1, new float[]{0.0f, 1.0f, 3.0f}), null,
                singleScaleConfig()));

        // Independent pairwise-difference oracle:
        // (|0-1| + |0-3| + |1-3|) / (n^2 * mean) = 6 / 12 = 0.5.
        double actual = result.value("Intensity_PatchinessGini");
        assertEquals(0.5, actual, 1e-12);
        assertTrue(actual >= 0.0 && actual <= 1.0);
    }

    @Test
    public void allZeroAndAllEqualNonnegativeGiniAreZero() {
        IntensitySpatialResult allZero = new PatchinessAnalysis().measure(context(
                uniformImage(4, 1, 0.0f), null, singleScaleConfig()));
        IntensitySpatialResult allEqual = new PatchinessAnalysis().measure(context(
                uniformImage(4, 1, 7.0f), null, singleScaleConfig()));

        assertEquals(0.0, allZero.value("Intensity_PatchinessGini"), 0.0);
        assertEquals(0.0, allEqual.value("Intensity_PatchinessGini"), 1e-12);
    }

    private static IntensitySpatialContext context(ImagePlus raw,
                                                   ImagePlus binarized,
                                                   IntensitySpatialConfig config) {
        return new IntensitySpatialContext(config, raw, binarized, 1, null,
                IntensitySpatialOutputMode.BASE, "synthetic", "DAPI", "", null);
    }

    private static IntensitySpatialContext context(ImagePlus raw,
                                                   ImagePlus binarized,
                                                   IntensitySpatialConfig config,
                                                   final List<String> warnings) {
        return new IntensitySpatialContext(config, raw, binarized, 1, null,
                IntensitySpatialOutputMode.BASE, "synthetic", "DAPI", "",
                new IntensitySpatialContext.WarningSink() {
                    @Override
                    public void warn(String message) {
                        warnings.add(message);
                    }
                });
    }

    private static void assertWarningContains(List<String> warnings, String... fragments) {
        assertFalse(warnings.isEmpty());
        String warning = warnings.get(0);
        for (String fragment : fragments) {
            assertTrue("Expected warning to contain '" + fragment + "': " + warning,
                    warning.contains(fragment));
        }
    }

    private static IntensitySpatialConfig config() {
        return IntensitySpatialConfig.builder()
                .enabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.PATCHINESS)
                .tileScalesUm(new double[]{2.0, 4.0})
                .build();
    }

    private static IntensitySpatialConfig singleScaleConfig() {
        return IntensitySpatialConfig.builder()
                .enabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.PATCHINESS)
                .tileScalesUm(new double[]{2.0})
                .build();
    }

    private static ImagePlus uniformImage(int width, int height, float value) {
        float[] pixels = new float[width * height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = value;
        }
        return image(width, height, pixels);
    }

    private static ImagePlus tiledImage(int width, int height, int tileSize,
                                        float low, float high) {
        float[] pixels = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean highTile = ((x / tileSize) + (y / tileSize)) % 2 == 0;
                pixels[y * width + x] = highTile ? high : low;
            }
        }
        return image(width, height, pixels);
    }

    private static ImagePlus halfSplitImage(int width,
                                            int height,
                                            float low,
                                            float high,
                                            double pixelWidth,
                                            double pixelHeight,
                                            String unit) {
        float[] pixels = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = x < width / 2 ? low : high;
            }
        }
        return image(width, height, pixels, pixelWidth, pixelHeight, unit);
    }

    private static ImagePlus image(int width, int height, float[] pixels) {
        return image(width, height, pixels, 1.0, 1.0, "um");
    }

    private static ImagePlus image(int width,
                                   int height,
                                   float[] pixels,
                                   double pixelWidth,
                                   double pixelHeight,
                                   String unit) {
        ImagePlus image = new ImagePlus("synthetic",
                new FloatProcessor(width, height, pixels, null));
        Calibration calibration = new Calibration();
        calibration.pixelWidth = pixelWidth;
        calibration.pixelHeight = pixelHeight;
        calibration.setUnit(unit);
        image.setCalibration(calibration);
        return image;
    }
}
