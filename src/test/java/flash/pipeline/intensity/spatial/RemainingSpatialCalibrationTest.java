package flash.pipeline.intensity.spatial;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import ij.ImagePlus;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;

public class RemainingSpatialCalibrationTest {
    @Test
    public void remaining2dHelpersConvertNanometerCalibrationToMicrons() throws Exception {
        ImagePlus image = imageWithCalibration(500.0, 250.0, 1.0, "nm");

        assertEquals(0.5, pixelSize2d(Anisotropy2DAnalysis.class, image, true), 0.0);
        assertEquals(0.25, pixelSize2d(Anisotropy2DAnalysis.class, image, false), 0.0);
        assertEquals(0.5, pixelSize2d(DepthProfileAnalysis.class, image, true), 0.0);
        assertEquals(0.25, pixelSize2d(DepthProfileAnalysis.class, image, false), 0.0);
        assertEquals(0.5, pixelSize2d(GranularityAnalysis.class, image, true), 0.0);
        assertEquals(0.25, pixelSize2d(GranularityAnalysis.class, image, false), 0.0);
        assertEquals(0.5, pixelSize2d(HotspotScanAnalysis.class, image, true), 0.0);
        assertEquals(0.25, pixelSize2d(HotspotScanAnalysis.class, image, false), 0.0);
        assertEquals(0.5, pixelSize2d(PeriodicityAnalysis.class, image, true), 0.0);
        assertEquals(0.25, pixelSize2d(PeriodicityAnalysis.class, image, false), 0.0);
        assertEquals(0.5, pixelSize2d(TextureClassAnalysis.class, image, true), 0.0);
        assertEquals(0.25, pixelSize2d(TextureClassAnalysis.class, image, false), 0.0);
    }

    @Test
    public void anisotropy3dHelperConvertsMillimeterCalibrationToMicrons() throws Exception {
        ImagePlus image = imageWithCalibration(0.001, 0.002, 0.003, "mm");

        assertEquals(1.0, pixelSize3d(image, "X"), 0.0);
        assertEquals(2.0, pixelSize3d(image, "Y"), 0.0);
        assertEquals(3.0, pixelSize3d(image, "Z"), 0.0);
    }

    @Test
    public void periodicityWavelengthUsesConvertedNanometerCalibration() {
        ImagePlus image = stripeImage(96, 96, 0.0, 12.0, 500.0, 500.0, "nm");

        IntensitySpatialResult result = new PeriodicityAnalysis().measure(context(image));

        assertEquals(6.0, result.value(PeriodicityAnalysis.COLUMN_WAVELENGTH), 0.75);
    }

    private static IntensitySpatialContext context(ImagePlus raw) {
        IntensitySpatialConfig config = IntensitySpatialConfig.builder()
                .enabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.PERIODICITY)
                .build();
        return new IntensitySpatialContext(config, raw, null, 1, null,
                IntensitySpatialOutputMode.BASE, "synthetic", "DAPI", "", null);
    }

    private static double pixelSize2d(Class<?> analysisClass,
                                      ImagePlus image,
                                      boolean xAxis) throws Exception {
        Method method = analysisClass.getDeclaredMethod("pixelSize", ImagePlus.class, Boolean.TYPE);
        method.setAccessible(true);
        return ((Double) method.invoke(null, image, Boolean.valueOf(xAxis))).doubleValue();
    }

    private static double pixelSize3d(ImagePlus image, String axisName) throws Exception {
        Class<?> axisType = Class.forName(
                "flash.pipeline.intensity.spatial.Anisotropy3DAnalysis$Axis");
        Method method = Anisotropy3DAnalysis.class.getDeclaredMethod(
                "pixelSize", ImagePlus.class, axisType);
        method.setAccessible(true);
        Object axis = enumConstant(axisType, axisName);
        return ((Double) method.invoke(null, image, axis)).doubleValue();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(Class<?> axisType, String axisName) {
        return Enum.valueOf((Class) axisType.asSubclass(Enum.class), axisName);
    }

    private static ImagePlus stripeImage(int width,
                                         int height,
                                         double orientationDegrees,
                                         double periodPixels,
                                         double pixelWidth,
                                         double pixelHeight,
                                         String unit) {
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
        return image(width, height, pixels, pixelWidth, pixelHeight, 1.0, unit);
    }

    private static ImagePlus imageWithCalibration(double pixelWidth,
                                                  double pixelHeight,
                                                  double pixelDepth,
                                                  String unit) {
        return image(1, 1, new float[]{1.0f}, pixelWidth, pixelHeight, pixelDepth, unit);
    }

    private static ImagePlus image(int width,
                                   int height,
                                   float[] pixels,
                                   double pixelWidth,
                                   double pixelHeight,
                                   double pixelDepth,
                                   String unit) {
        ImagePlus image = new ImagePlus("synthetic",
                new FloatProcessor(width, height, pixels, null));
        Calibration calibration = new Calibration();
        calibration.pixelWidth = pixelWidth;
        calibration.pixelHeight = pixelHeight;
        calibration.pixelDepth = pixelDepth;
        calibration.setUnit(unit);
        image.setCalibration(calibration);
        return image;
    }
}
