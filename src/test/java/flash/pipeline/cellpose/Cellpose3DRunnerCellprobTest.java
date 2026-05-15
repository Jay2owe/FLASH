package flash.pipeline.cellpose;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import org.junit.Test;

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
