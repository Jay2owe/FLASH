package flash.pipeline.intensity.spatial;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import flash.pipeline.cli.CLIArgumentParser;
import flash.pipeline.cli.CLIConfig;
import ij.ImagePlus;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TextureClassAnalysisTest {
    @Test
    public void fourSyntheticTextureClassesReportQuarterFractions() throws Exception {
        IntensitySpatialResult result = new TextureClassAnalysis().measure(context(
                quadrantImage(64, 64), null, config()));

        double sum = 0.0;
        for (int i = 1; i <= 4; i++) {
            double fraction = result.value("Intensity_TextureClass" + i + "_fraction");
            assertEquals(0.25, fraction, 0.08);
            sum += fraction;
        }
        assertEquals(1.0, sum, 1e-9);
    }

    @Test
    public void uniformImageUsesOneDominantClassWithoutCrashing() throws Exception {
        IntensitySpatialResult result = new TextureClassAnalysis().measure(context(
                uniformImage(32, 32, 20.0f), null, config()));

        assertEquals(1.0, result.value("Intensity_TextureClass1_fraction"), 1e-9);
        assertEquals(0.0, result.value("Intensity_TextureClass2_fraction"), 1e-9);
        assertEquals(0.0, result.value("Intensity_TextureClass3_fraction"), 1e-9);
        assertEquals(0.0, result.value("Intensity_TextureClass4_fraction"), 1e-9);
    }

    @Test
    public void advancedAnalysesAreNotSelectedByDefault() throws Exception {
        IntensitySpatialConfig presetConfig =
                IntensitySpatialConfig.fromJsonObject(new LinkedHashMap<String, Object>());
        assertFalse(containsAdvanced(presetConfig.getEnabledAnalyses()));

        CLIConfig cliConfig = CLIArgumentParser.parse("dir=[/tmp] intensity.spatial=true");
        assertTrue(cliConfig != null);
        assertFalse(containsAdvanced(cliConfig.getIntensity().getSpatialAnalyses()));
    }

    @Test
    public void textureClassIsRawOnlyAndDoesNotEmitBinarizedColumns() throws Exception {
        TextureClassAnalysis analysis = new TextureClassAnalysis();
        IntensitySpatialResult result = analysis.measure(context(
                quadrantImage(32, 32),
                quadrantImage(32, 32),
                config()));

        assertEquals(IntensitySpatialAnalysis.AnalysisValidity.RAW_ONLY, analysis.validity());
        assertFalse(result.values().containsKey("Intensity_TextureClass1_fraction_binarized"));
    }

    private static boolean containsAdvanced(Set<IntensitySpatialConfig.AnalysisKey> analyses) {
        return analyses != null
                && (analyses.contains(IntensitySpatialConfig.AnalysisKey.PERIODICITY)
                || analyses.contains(IntensitySpatialConfig.AnalysisKey.GLCM)
                || analyses.contains(IntensitySpatialConfig.AnalysisKey.TEXTURECLASS)
                || analyses.contains(IntensitySpatialConfig.AnalysisKey.SCALEDIVERGENCE));
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
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.TEXTURECLASS)
                .textureClassCount(4)
                .build();
    }

    private static ImagePlus uniformImage(int width, int height, float value) {
        float[] pixels = new float[width * height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = value;
        }
        return image(width, height, pixels);
    }

    private static ImagePlus quadrantImage(int width, int height) {
        float[] pixels = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean right = x >= width / 2;
                boolean bottom = y >= height / 2;
                if (!right && !bottom) {
                    pixels[y * width + x] = 20.0f;
                } else if (right && !bottom) {
                    pixels[y * width + x] = 70.0f;
                } else if (!right) {
                    pixels[y * width + x] = 130.0f;
                } else {
                    pixels[y * width + x] = 200.0f;
                }
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
