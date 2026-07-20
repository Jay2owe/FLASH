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

public class GlcmTextureAnalysisTest {
    @Test
    public void checkerboardTextureReportsHighKnownContrast() {
        IntensitySpatialResult uniform = new GlcmTextureAnalysis().measure(context(
                uniformImage(32, 32, 50.0f), null, config(), null));
        IntensitySpatialResult checkerboard = new GlcmTextureAnalysis().measure(context(
                checkerboardImage(32, 32, 1, 0.0f, 100.0f), null, config(), null));

        assertEquals(0.0, uniform.value(GlcmTextureAnalysis.COLUMN_CONTRAST), 1e-9);
        assertTrue(checkerboard.value(GlcmTextureAnalysis.COLUMN_CONTRAST) > 400.0);
        assertTrue(checkerboard.value(GlcmTextureAnalysis.COLUMN_HOMOGENEITY)
                < uniform.value(GlcmTextureAnalysis.COLUMN_HOMOGENEITY));
        assertTrue(checkerboard.value(GlcmTextureAnalysis.COLUMN_ASM) > 0.0);
    }

    @Test
    public void emptyRoiReturnsNanAndWarns() {
        final List<String> warnings = new ArrayList<String>();
        IntensitySpatialContext context = new IntensitySpatialContext(
                config(), uniformImage(8, 8, 10.0f), null, 1,
                new ij.gui.Roi(20, 20, 2, 2), IntensitySpatialOutputMode.BASE,
                "synthetic", "DAPI", "outside", new IntensitySpatialContext.WarningSink() {
            @Override
            public void warn(String message) {
                warnings.add(message);
            }
        });

        IntensitySpatialResult result = new GlcmTextureAnalysis().measure(context);

        assertTrue(Double.isNaN(result.value(GlcmTextureAnalysis.COLUMN_CONTRAST)));
        assertFalse(warnings.isEmpty());
    }

    @Test
    public void glcmIsRawOnlyAndDoesNotEmitBinarizedColumns() {
        GlcmTextureAnalysis analysis = new GlcmTextureAnalysis();
        IntensitySpatialResult result = analysis.measure(context(
                checkerboardImage(16, 16, 1, 0.0f, 100.0f),
                checkerboardImage(16, 16, 1, 0.0f, 1.0f),
                config(),
                null));

        assertEquals(IntensitySpatialAnalysis.AnalysisValidity.RAW_ONLY, analysis.validity());
        assertFalse(result.values().containsKey("Intensity_GLCMContrast_binarized"));
    }

    private static IntensitySpatialContext context(ImagePlus raw,
                                                   ImagePlus binarized,
                                                   IntensitySpatialConfig config,
                                                   final List<String> warnings) {
        IntensitySpatialContext.WarningSink sink = warnings == null
                ? null
                : new IntensitySpatialContext.WarningSink() {
            @Override
            public void warn(String message) {
                warnings.add(message);
            }
        };
        return new IntensitySpatialContext(config, raw, binarized, 1, null,
                IntensitySpatialOutputMode.BASE, "synthetic", "DAPI", "", sink);
    }

    private static IntensitySpatialConfig config() {
        return IntensitySpatialConfig.builder()
                .enabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.GLCM)
                .build();
    }

    private static ImagePlus uniformImage(int width, int height, float value) {
        float[] pixels = new float[width * height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = value;
        }
        return image(width, height, pixels);
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
