package flash.pipeline.intensity.spatial;

import ij.ImagePlus;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PairPlane2DTest {
    @Test
    public void fromConvertsNanometerCalibrationToMicrons() {
        ImagePlus source = image(2, 2, 500.0, 250.0, "nm");
        ImagePlus partner = image(2, 2, 1.0, 1.0, "um");

        PairPlane2D plane = PairPlane2D.from(source, partner, null, null, 1, null);

        assertEquals(0.5, plane.pixelWidthUm, 0.0);
        assertEquals(0.25, plane.pixelHeightUm, 0.0);
    }

    private static ImagePlus image(int width,
                                   int height,
                                   double pixelWidth,
                                   double pixelHeight,
                                   String unit) {
        float[] pixels = new float[width * height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = 1.0f;
        }
        ImagePlus image = new ImagePlus("synthetic",
                new FloatProcessor(width, height, pixels, null));
        Calibration calibration = new Calibration();
        calibration.pixelWidth = pixelWidth;
        calibration.pixelHeight = pixelHeight;
        calibration.pixelDepth = 1.0;
        calibration.setUnit(unit);
        image.setCalibration(calibration);
        return image;
    }
}
