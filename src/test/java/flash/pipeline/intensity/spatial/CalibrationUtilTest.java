package flash.pipeline.intensity.spatial;

import ij.ImagePlus;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class CalibrationUtilTest {
    @Test
    public void pixelSizeUmKeepsMicronCalibration() {
        ImagePlus image = imageWithCalibration(0.5, 0.75, 2.0, "um");

        assertEquals(0.5, CalibrationUtil.pixelSizeUm(image, CalibrationUtil.Axis.X), 0.0);
        assertEquals(0.75, CalibrationUtil.pixelSizeUm(image, CalibrationUtil.Axis.Y), 0.0);
        assertEquals(2.0, CalibrationUtil.pixelSizeUm(image, CalibrationUtil.Axis.Z), 0.0);
    }

    @Test
    public void toMicronsConvertsNanometersAndMillimeters() {
        assertEquals(0.5, CalibrationUtil.toMicrons(500.0, "nm"), 0.0);
        assertEquals(2.0, CalibrationUtil.toMicrons(0.002, "millimeters"), 0.0);
    }

    @Test
    public void toMicronsConvertsCentimetersAndMeters() {
        assertEquals(100.0, CalibrationUtil.toMicrons(0.01, "cm"), 0.0);
        assertEquals(1000000.0, CalibrationUtil.toMicrons(1.0, "meter"), 0.0);
    }

    @Test
    public void toMicronsNormalizesSymbolsCaseAndWhitespace() {
        assertEquals(3.0, CalibrationUtil.toMicrons(3.0, " \u00b5M / pixel "), 0.0);
        assertEquals(4.0, CalibrationUtil.toMicrons(4.0, "micro-meters"), 0.0);
    }

    @Test
    public void pixelAndUnknownUnitsAreExplicitFailures() {
        expectIllegalArgument(new ThrowingRunnable() {
            @Override
            public void run() {
                CalibrationUtil.toMicrons(1.0, "pixel");
            }
        });
        expectIllegalArgument(new ThrowingRunnable() {
            @Override
            public void run() {
                CalibrationUtil.toMicrons(1.0, "furlong");
            }
        });
    }

    @Test
    public void invalidPhysicalPixelSizeFallsBackToOneMicron() {
        ImagePlus image = imageWithCalibration(Double.NaN, -2.0, Double.POSITIVE_INFINITY, "um");

        assertEquals(1.0, CalibrationUtil.pixelSizeUm(image, CalibrationUtil.Axis.X), 0.0);
        assertEquals(1.0, CalibrationUtil.pixelSizeUm(image, CalibrationUtil.Axis.Y), 0.0);
        assertEquals(1.0, CalibrationUtil.pixelSizeUm(image, CalibrationUtil.Axis.Z), 0.0);
    }

    private static ImagePlus imageWithCalibration(double pixelWidth,
                                                  double pixelHeight,
                                                  double pixelDepth,
                                                  String unit) {
        ImagePlus image = new ImagePlus("synthetic",
                new FloatProcessor(1, 1, new float[]{1.0f}, null));
        Calibration calibration = new Calibration();
        calibration.pixelWidth = pixelWidth;
        calibration.pixelHeight = pixelHeight;
        calibration.pixelDepth = pixelDepth;
        calibration.setUnit(unit);
        image.setCalibration(calibration);
        return image;
    }

    private static void expectIllegalArgument(ThrowingRunnable action) {
        try {
            action.run();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected explicit unit failure.
        }
    }

    private interface ThrowingRunnable {
        void run();
    }
}
