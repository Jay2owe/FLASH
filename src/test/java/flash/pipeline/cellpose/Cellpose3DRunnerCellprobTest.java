package flash.pipeline.cellpose;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class Cellpose3DRunnerCellprobTest {

    @Test
    public void perObjectMeanCellprobReturnsExpectedMeans() {
        ImagePlus labels = image(labels(new int[][] {
                {1, 1, 2},
                {2, 3, 0}
        }));
        ImagePlus cellprob = image(cellprob(new float[][] {
                {0.2f, 0.4f, 0.6f},
                {0.8f, 0.5f, 0.9f}
        }));

        double[] means = Cellpose3DRunner.perObjectMeanCellprob(labels, cellprob);

        assertEquals(4, means.length);
        assertTrue(Double.isNaN(means[0]));
        assertEquals(0.3d, means[1], 1.0e-6);
        assertEquals(0.7d, means[2], 1.0e-6);
        assertEquals(0.5d, means[3], 1.0e-6);
    }

    @Test
    public void perObjectMeanCellprobReturnsNaNForMissingLabels() {
        ImagePlus labels = image(labels(new int[][] {
                {1, 3, 3}
        }));
        ImagePlus cellprob = image(cellprob(new float[][] {
                {0.25f, 0.5f, 1.0f}
        }));

        double[] means = Cellpose3DRunner.perObjectMeanCellprob(labels, cellprob);

        assertEquals(4, means.length);
        assertEquals(0.25d, means[1], 1.0e-6);
        assertTrue(Double.isNaN(means[2]));
        assertEquals(0.75d, means[3], 1.0e-6);
    }

    @Test
    public void sparseMaximumExactLabelUsesOnlyObservedIdentityKeys() {
        FloatProcessor labelProcessor = new FloatProcessor(3, 1);
        labelProcessor.setf(0, 0, 1.0f);
        labelProcessor.setf(1, 0, 16_777_216.0f);
        ImagePlus labels = image(labelProcessor);
        ImagePlus cellprob = image(cellprob(new float[][] {
                {0.25f, 0.75f, 0.5f}
        }));

        Map<Integer, Double> means =
                Cellpose3DRunner.perObjectMeanCellprobByLabel(labels, cellprob);

        assertEquals(2, means.size());
        assertEquals(0.25d, means.get(Integer.valueOf(1)).doubleValue(), 1.0e-6);
        assertEquals(0.75d,
                means.get(Integer.valueOf(16_777_216)).doubleValue(), 1.0e-6);
    }

    @Test
    public void legacyDenseApiRejectsSparseWideLabelBeforeUnsafeAllocation() {
        FloatProcessor labelProcessor = new FloatProcessor(2, 1);
        labelProcessor.setf(0, 0, 1.0f);
        labelProcessor.setf(1, 0, 16_777_216.0f);
        ImagePlus labels = image(labelProcessor);
        ImagePlus cellprob = image(cellprob(new float[][] {{0.25f, 0.75f}}));

        try {
            Cellpose3DRunner.perObjectMeanCellprob(labels, cellprob);
            org.junit.Assert.fail("Expected bounded dense API rejection.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("16777216"));
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("perObjectMeanCellprobByLabel"));
        }
    }

    private static ImagePlus image(ij.process.ImageProcessor processor) {
        ImageStack stack = new ImageStack(processor.getWidth(), processor.getHeight());
        stack.addSlice(processor);
        return new ImagePlus("image", stack);
    }

    private static ShortProcessor labels(int[][] values) {
        int height = values.length;
        int width = values[0].length;
        ShortProcessor processor = new ShortProcessor(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                processor.set(x, y, values[y][x]);
            }
        }
        return processor;
    }

    private static FloatProcessor cellprob(float[][] values) {
        int height = values.length;
        int width = values[0].length;
        FloatProcessor processor = new FloatProcessor(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                processor.setf(x, y, values[y][x]);
            }
        }
        return processor;
    }
}
