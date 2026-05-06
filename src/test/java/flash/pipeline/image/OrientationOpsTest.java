package flash.pipeline.image;

import flash.pipeline.naming.OrientationManifestRow;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class OrientationOpsTest {

    @Test
    public void normalizeRotationDegrees_acceptsQuarterTurnsOnly() {
        assertEquals(0, OrientationOps.normalizeRotationDegrees(0));
        assertEquals(90, OrientationOps.normalizeRotationDegrees(90));
        assertEquals(180, OrientationOps.normalizeRotationDegrees(180));
        assertEquals(270, OrientationOps.normalizeRotationDegrees(270));
        assertEquals(90, OrientationOps.normalizeRotationDegrees(450));
        assertEquals(270, OrientationOps.normalizeRotationDegrees(-90));
        assertEquals(0, OrientationOps.normalizeRotationDegrees(45));
    }

    @Test
    public void applyTransform_rotatesClockwiseAndFlipsAfterNormalization() {
        ImagePlus imp = image(2, 2, new byte[] {
                1, 2,
                3, 4
        });

        OrientationOps.applyTransform(
                imp,
                450,
                true,
                false,
                "LH",
                OrientationManifestRow.ViewPolicy.MANUAL_ONLY);

        assertEquals(2, imp.getWidth());
        assertEquals(2, imp.getHeight());
        assertPixelValues(imp, new int[] {
                1, 3,
                2, 4
        });
    }

    @Test
    public void applyTransform_standardizeToLeftMirrorsRightHemisphere() {
        ImagePlus imp = image(2, 1, new byte[] { 7, 9 });

        OrientationOps.applyTransform(
                imp,
                0,
                false,
                false,
                "RH",
                OrientationManifestRow.ViewPolicy.STANDARDIZE_TO_LEFT);

        assertPixelValues(imp, new int[] { 9, 7 });
    }

    @Test
    public void applyTransform_standardizeToRightMirrorsLeftHemisphere() {
        ImagePlus imp = image(2, 1, new byte[] { 7, 9 });

        OrientationOps.applyTransform(
                imp,
                0,
                false,
                false,
                "LH",
                OrientationManifestRow.ViewPolicy.STANDARDIZE_TO_RIGHT);

        assertPixelValues(imp, new int[] { 9, 7 });
    }

    @Test
    public void applyTransform_keepAsAcquiredAppliesManualOnly() {
        ImagePlus imp = image(2, 1, new byte[] { 7, 9 });

        OrientationOps.applyTransform(
                imp,
                0,
                false,
                false,
                "RH",
                OrientationManifestRow.ViewPolicy.KEEP_AS_ACQUIRED);

        assertPixelValues(imp, new int[] { 7, 9 });
    }

    @Test
    public void applyTransform_flipVerticalMirrorsRows() {
        ImagePlus imp = image(2, 2, new byte[] {
                1, 2,
                3, 4
        });

        OrientationOps.applyTransform(
                imp,
                0,
                false,
                true,
                "LH",
                OrientationManifestRow.ViewPolicy.MANUAL_ONLY);

        assertPixelValues(imp, new int[] {
                3, 4,
                1, 2
        });
    }

    private static ImagePlus image(int width, int height, byte[] pixels) {
        ImageStack stack = new ImageStack(width, height);
        stack.addSlice(new ByteProcessor(width, height, pixels, null));
        return new ImagePlus("test", stack);
    }

    private static void assertPixelValues(ImagePlus imp, int[] expected) {
        int i = 0;
        for (int y = 0; y < imp.getHeight(); y++) {
            for (int x = 0; x < imp.getWidth(); x++) {
                assertEquals("pixel " + i, expected[i],
                        imp.getProcessor().getPixel(x, y));
                i++;
            }
        }
    }
}
