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

    private static IntensitySpatialContext context(ImagePlus raw,
                                                   ImagePlus binarized,
                                                   IntensitySpatialConfig config) {
        return new IntensitySpatialContext(config, raw, binarized, 1, null,
                IntensitySpatialOutputMode.BASE, "synthetic", "DAPI", "", null);
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
