package flash.pipeline.intensity.spatial;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import ij.ImagePlus;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class EntropyMiAnalysisTest {
    @Test
    public void identicalGradientHasHigherNmiThanScrambledGradient() {
        ImagePlus source = gradientImage(40, 40, 0);
        ImagePlus identical = gradientImage(40, 40, 0);
        ImagePlus scrambled = gradientImage(40, 40, 17);

        IntensitySpatialResult same = new EntropyMiAnalysis().measure(context(source, identical));
        IntensitySpatialResult mixed = new EntropyMiAnalysis().measure(context(source, scrambled));

        assertTrue(same.value("DAPI_NMI_mCherry") > 0.8);
        assertTrue(same.value("DAPI_NMI_mCherry") > mixed.value("DAPI_NMI_mCherry"));
        assertTrue(Double.isFinite(same.value("DAPI_MIPeakStrength_mCherry")));
    }

    private static IntensitySpatialPairContext context(ImagePlus source, ImagePlus partner) {
        return new IntensitySpatialPairContext(config(), source, null, null, partner, null, null,
                1, null, IntensitySpatialOutputMode.BASE, "synthetic",
                "DAPI", "mCherry", "", null);
    }

    private static IntensitySpatialConfig config() {
        return IntensitySpatialConfig.builder()
                .enabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.ENTROPY_MI)
                .build();
    }

    private static ImagePlus gradientImage(int width, int height, int scramble) {
        float[] pixels = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int xx = scramble == 0 ? x : (x * scramble + y * 3) % width;
                int yy = scramble == 0 ? y : (y * scramble + x * 5) % height;
                pixels[y * width + x] = (float) (xx * 2 + yy * 3);
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
