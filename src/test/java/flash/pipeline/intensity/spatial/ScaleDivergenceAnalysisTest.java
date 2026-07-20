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

public class ScaleDivergenceAnalysisTest {
    @Test
    public void uniformImageHasNearZeroSpectrumWidth() {
        IntensitySpatialResult result = new ScaleDivergenceAnalysis().measure(context(
                uniformImage(64, 64, 10.0f), null, config(), null));

        assertEquals(0.0, result.value(ScaleDivergenceAnalysis.COLUMN_DELTA_ALPHA), 0.05);
        assertEquals(0.0, result.value(ScaleDivergenceAnalysis.COLUMN_ASYMMETRY), 0.05);
    }

    @Test
    public void multiscaleSpotImageHasMoreScaleDivergenceThanUniform() {
        ScaleDivergenceAnalysis analysis = new ScaleDivergenceAnalysis();
        IntensitySpatialResult uniform = analysis.measure(context(
                uniformImage(64, 64, 10.0f), null, config(), null));
        IntensitySpatialResult spots = analysis.measure(context(
                multiscaleSpotImage(64, 64), null, config(), null));

        assertTrue(Double.isFinite(spots.value(ScaleDivergenceAnalysis.COLUMN_DELTA_ALPHA)));
        assertTrue(spots.value(ScaleDivergenceAnalysis.COLUMN_DELTA_ALPHA)
                > uniform.value(ScaleDivergenceAnalysis.COLUMN_DELTA_ALPHA));
        assertTrue(Math.abs(spots.value(ScaleDivergenceAnalysis.COLUMN_ASYMMETRY)) <= 1.0);
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

        IntensitySpatialResult result = new ScaleDivergenceAnalysis().measure(context);

        assertTrue(Double.isNaN(result.value(ScaleDivergenceAnalysis.COLUMN_DELTA_ALPHA)));
        assertFalse(warnings.isEmpty());
    }

    @Test
    public void scaleDivergenceIsRawOnlyAndDoesNotEmitBinarizedColumns() {
        ScaleDivergenceAnalysis analysis = new ScaleDivergenceAnalysis();
        IntensitySpatialResult result = analysis.measure(context(
                multiscaleSpotImage(32, 32),
                multiscaleSpotImage(32, 32),
                config(),
                null));

        assertEquals(IntensitySpatialAnalysis.AnalysisValidity.RAW_ONLY, analysis.validity());
        assertFalse(result.values().containsKey("Intensity_MultifractalDeltaAlpha_binarized"));
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
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.SCALEDIVERGENCE)
                .build();
    }

    private static ImagePlus uniformImage(int width, int height, float value) {
        float[] pixels = new float[width * height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = value;
        }
        return image(width, height, pixels);
    }

    private static ImagePlus multiscaleSpotImage(int width, int height) {
        float[] pixels = new float[width * height];
        double cx = (width - 1) / 2.0;
        double cy = (height - 1) / 2.0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double dx = x - cx;
                double dy = y - cy;
                double r2 = dx * dx + dy * dy;
                double broad = 40.0 * Math.exp(-r2 / (2.0 * 12.0 * 12.0));
                double sharp = 120.0 * Math.exp(-r2 / (2.0 * 3.0 * 3.0));
                double tiled = ((x / 8 + y / 8) % 2 == 0) ? 15.0 : 2.0;
                pixels[y * width + x] = (float) (1.0 + tiled + broad + sharp);
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
