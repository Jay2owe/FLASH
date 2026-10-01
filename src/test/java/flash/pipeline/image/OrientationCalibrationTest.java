package flash.pipeline.image;

import flash.pipeline.naming.OrientationManifestRow;
import ij.ImagePlus;
import ij.process.FloatProcessor;
import org.junit.Test;
import static org.junit.Assert.*;

public class OrientationCalibrationTest {
    @Test public void quarterTurnsRotatePixelScaleAndOriginWithImage() {
        for (int angle : new int[]{90, 270}) {
            ImagePlus image = image();
            OrientationOps.applyTransform(image, angle, false, false, "",
                    OrientationManifestRow.ViewPolicy.MANUAL_ONLY);
            assertEquals(3, image.getWidth());
            assertEquals(5, image.getHeight());
            assertEquals(2, image.getCalibration().pixelWidth, 0);
            assertEquals(0.5, image.getCalibration().pixelHeight, 0);
            assertEquals(angle == 90 ? 0 : 2, image.getCalibration().xOrigin, 0);
            assertEquals(angle == 90 ? 1 : 3, image.getCalibration().yOrigin, 0);
            int originX = (int) image.getCalibration().xOrigin;
            int originY = (int) image.getCalibration().yOrigin;
            assertEquals(99, image.getProcessor().getf(originX, originY), 0);
        }
    }

    @Test public void halfTurnAndMirrorsTransformOriginWithoutSwappingScale() {
        ImagePlus image = image();
        OrientationOps.applyTransform(image, 180, false, false, "",
                OrientationManifestRow.ViewPolicy.MANUAL_ONLY);
        assertEquals(3, image.getCalibration().xOrigin, 0);
        assertEquals(0, image.getCalibration().yOrigin, 0);
        assertEquals(0.5, image.getCalibration().pixelWidth, 0);
        assertEquals(2, image.getCalibration().pixelHeight, 0);
        assertEquals(99, image.getProcessor().getf(3, 0), 0);
    }

    private static ImagePlus image() {
        ImagePlus image = new ImagePlus("anisotropic", new FloatProcessor(5, 3));
        image.getCalibration().pixelWidth = 0.5;
        image.getCalibration().pixelHeight = 2;
        image.getCalibration().xOrigin = 1;
        image.getCalibration().yOrigin = 2;
        image.getProcessor().setf(1, 2, 99);
        return image;
    }
}
