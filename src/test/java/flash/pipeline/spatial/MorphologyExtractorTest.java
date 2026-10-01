package flash.pipeline.spatial;

import ij.ImagePlus;
import ij.process.FloatProcessor;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class MorphologyExtractorTest {
    @Test
    public void singlePixelLabelRemainsInQuantificationWithFourPhysicalEdges() {
        MorphologyExtractor.ObjectMorphology result = MorphologyExtractor.extract(
                new ImagePlus("pixel", new FloatProcessor(1, 1, new float[]{7})),
                2.0, 3.0).get(0);
        assertEquals(7, result.label);
        assertEquals(6.0, result.areaUm2, 0.0);
        assertEquals(10.0, result.perimeter, 0.0);
        assertEquals(Math.sqrt(13.0), result.feretDiameter, 1e-12);
        assertEquals(6.0, result.convexHullArea, 0.0);
        assertEquals(1.0, result.solidity, 0.0);
    }
    @Test
    public void squarePerimeterCountsEdgesRatherThanBoundaryPixels() {
        MorphologyExtractor.ObjectMorphology result = MorphologyExtractor.extract(
                new ImagePlus("square", new FloatProcessor(2, 2, new float[]{1, 1, 1, 1})),
                1.0, 1.0).get(0);
        assertEquals(4.0, result.areaUm2, 0.0);
        assertEquals(8.0, result.perimeter, 0.0);
        assertEquals(Math.PI / 4.0, result.circularity, 1e-12);
    }

    @Test
    public void anisotropicFootprintUsesBothAxesForAreaPerimeterFeretAndAspect() {
        MorphologyExtractor.ObjectMorphology result = MorphologyExtractor.extract(
                new ImagePlus("rectangle", new FloatProcessor(2, 2, new float[]{1, 1, 1, 1})),
                2.0, 3.0).get(0);
        assertEquals(24.0, result.areaUm2, 0.0);
        assertEquals(20.0, result.perimeter, 0.0);
        assertEquals(Math.sqrt(52.0), result.feretDiameter, 1e-12);
        assertEquals(1.5, result.aspectRatio, 0.0);
        assertEquals(4.0 * Math.PI * 24.0 / 400.0, result.circularity, 1e-12);
    }

    @Test
    public void concaveThreePixelFootprintHasHullAreaThreeAndAHalf() {
        MorphologyExtractor.ObjectMorphology result = MorphologyExtractor.extract(
                new ImagePlus("L", new FloatProcessor(2, 2, new float[]{1, 1, 1, 0})),
                1.0, 1.0).get(0);
        assertEquals(3.0, result.areaUm2, 0.0);
        assertEquals(3.5, result.convexHullArea, 0.0);
        assertEquals(6.0 / 7.0, result.solidity, 1e-12);
        assertEquals(Math.sqrt(8.0), result.feretDiameter, 1e-12);
    }

    @Test(expected = IllegalArgumentException.class)
    public void scalarAdapterCannotInventPhysicalCalibration() {
        MorphologyExtractor.extract(
                new ImagePlus("pixel", new FloatProcessor(1, 1, new float[]{7})), Double.NaN);
    }
}
