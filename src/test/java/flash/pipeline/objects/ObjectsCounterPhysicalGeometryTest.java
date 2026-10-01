package flash.pipeline.objects;

import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.measure.ResultsTable;
import ij.process.FloatProcessor;
import org.junit.Test;
import static org.junit.Assert.*;

public class ObjectsCounterPhysicalGeometryTest {
    @Test
    public void nanometreCuboidUsesAllThreePhysicalAxesForVolumeSurfaceAndBox() {
        ImagePlus labels = new ImagePlus("cuboid", new FloatProcessor(2, 1, new float[]{7, 7}));
        labels.getCalibration().setUnit("nm");
        labels.getCalibration().pixelWidth = 2000;
        labels.getCalibration().pixelHeight = 3000;
        labels.getCalibration().pixelDepth = 4000;
        ResultsTable table = new ObjectsCounter3DWrapper().fromLabelImage(labels, null, false, false)
                .getStatistics();
        // The footprint is a 4 x 3 x 4 micron cuboid: V=48, S=2*(12+16+12)=80.
        assertEquals(48.0, table.getValue("Volume (micron^3)", 0), 1e-12);
        assertEquals(80.0, table.getValue("Surface (micron^2)", 0), 1e-12);
        assertEquals(48.0, table.getValue("B-volume (micron^3)", 0), 1e-12);
        assertEquals(0.5, table.getValue("XM", 0), 1e-12);
        assertTrue(Double.isNaN(table.getValue("Mean", 0)));
        assertTrue(Double.isNaN(table.getValue("IntDen", 0)));
    }

    @Test
    public void nativeCountingWithoutRedirectMeasuresSourceIntensity() {
        ImagePlus source = new ImagePlus("source", new FloatProcessor(2, 1, new float[]{20, 40}));
        ResultsTable table = new ObjectsCounter3DWrapper().runNative(source,
                10, 1, 10, false, null, false, false).getStatistics();
        assertEquals(30.0, table.getValue("Mean", 0), 1e-12);
        assertEquals(60.0, table.getValue("IntDen", 0), 1e-12);
    }

    @Test(expected = IllegalArgumentException.class)
    public void mismatchedRedirectCannotReturnPartiallyUnmaskedData() {
        new ObjectsCounter3DWrapper().fromLabelImage(
                new ImagePlus("labels", new FloatProcessor(2, 1, new float[]{1, 1})),
                new ImagePlus("redirect", new FloatProcessor(1, 1)), true, true);
    }

    @Test
    public void uncalibratedVolumeKeepsVoxelUnitAndMissingPhysicalBox() {
        ImagePlus labels = new ImagePlus("pixel", new FloatProcessor(1, 1, new float[]{1}));
        ResultsTable table = new ObjectsCounter3DWrapper().fromLabelImage(labels, null, false, false)
                .getStatistics();
        assertEquals(1.0, table.getValue("Volume (pixel^3)", 0), 0.0);
        assertEquals(6.0, table.getValue("Surface (pixel^2)", 0), 0.0);
        assertTrue(Double.isNaN(table.getValue("B-volume (micron^3)", 0)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void legacyRequestedRedirectCannotSilentlyMeasureSourceInstead() {
        String key = "3D-OC-Options_redirectTo.string";
        String previous = Prefs.get(key, "none");
        boolean previousMask = Prefs.get("3D-OC-Options_showMaskedImg.boolean", false);
        try {
            new ObjectsCounter3DWrapper().run(
                    new ImagePlus("source", new FloatProcessor(1, 1, new float[]{20})),
                    10, 1, 10, false, true, "FLASH test absent intensity redirect 78139", false, false);
        } finally {
            Prefs.set(key, previous);
            Prefs.set("3D-OC-Options_showMaskedImg.boolean", previousMask);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void nativeTimeSeriesCannotBeCountedAsOneSpatialVolume() {
        ImagePlus image = twoPlanes();
        image.setDimensions(1, 1, 2);
        new ObjectsCounter3DWrapper().runNative(image, 10, 1, 10, false, null, false, false);
    }

    @Test(expected = IllegalArgumentException.class)
    public void multichannelLabelsCannotBeCountedAsAdditionalSpatialSlices() {
        ImagePlus labels = twoPlanes();
        labels.setDimensions(2, 1, 1);
        new ObjectsCounter3DWrapper().fromLabelImage(labels, null, false, false);
    }

    @Test(expected = IllegalArgumentException.class)
    public void timeSeriesRedirectCannotPassAnEqualStackSizeCheck() {
        ImagePlus redirect = twoPlanes();
        redirect.setDimensions(1, 1, 2);
        new ObjectsCounter3DWrapper().fromLabelImage(twoPlanes(), redirect, false, false);
    }

    private static ImagePlus twoPlanes() {
        ImageStack stack = new ImageStack(1, 1);
        stack.addSlice(new FloatProcessor(1, 1, new float[]{20}));
        stack.addSlice(new FloatProcessor(1, 1, new float[]{20}));
        return new ImagePlus("two planes", stack);
    }
}
