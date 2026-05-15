package flash.pipeline.intensity.spatial;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class PairVolume3DTest {
    @Test
    public void fromConvertsMillimeterCalibrationToMicrons() {
        ImagePlus source = stack(2, 2, 2, 0.001, 0.002, 0.003, "mm");
        ImagePlus partner = stack(2, 2, 2, 1.0, 1.0, 1.0, "um");

        PairVolume3D volume = PairVolume3D.from(source, partner, null, null, null);

        assertEquals(1.0, volume.pixelWidthUm, 0.0);
        assertEquals(2.0, volume.pixelHeightUm, 0.0);
        assertEquals(3.0, volume.pixelDepthUm, 0.0);
    }

    @Test
    public void smallerMaskImageDoesNotThrowOrMarkOutOfBoundsVoxels() {
        ImagePlus source = stack(3, 3, 2, 1.0, 1.0, 1.0, "um");
        ImagePlus partner = stack(3, 3, 2, 1.0, 1.0, 1.0, "um");
        ImagePlus partnerMask = stack(3, 1, 2, 1.0, 1.0, 1.0, "um");

        PairVolume3D volume = PairVolume3D.from(source, partner, null, partnerMask, null);

        assertEquals(18, volume.count);
        assertFalse(volume.partnerMask[PairVolume3D.index(1, 2, 0, volume.width, volume.height)]);
    }

    private static ImagePlus stack(int width,
                                   int height,
                                   int depth,
                                   double pixelWidth,
                                   double pixelHeight,
                                   double pixelDepth,
                                   String unit) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            float[] pixels = new float[width * height];
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = 1.0f;
            }
            stack.addSlice(new FloatProcessor(width, height, pixels, null));
        }
        ImagePlus image = new ImagePlus("synthetic", stack);
        Calibration calibration = new Calibration();
        calibration.pixelWidth = pixelWidth;
        calibration.pixelHeight = pixelHeight;
        calibration.pixelDepth = pixelDepth;
        calibration.setUnit(unit);
        image.setCalibration(calibration);
        return image;
    }
}
