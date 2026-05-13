package flash.pipeline.stardist;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ShortProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class StarDist3DRunnerObjectFilterTest {

    @Test
    public void applyObjectFiltersRemovesLabelsByStarDistObjectMetrics() {
        ImagePlus labels = labelImage(new int[] {1, 2, 2, 2});
        ResultsTable stats = objectStats();

        int removed = StarDist3DRunner.applyObjectFilters(labels, stats,
                5, 10, 0.5, 50);

        assertEquals(2, removed);
        assertEquals(0, labels.getProcessor().get(0, 0));
        assertEquals(0, labels.getProcessor().get(1, 0));
        assertEquals(0, StarDist3DRunner.countLabels(labels));
    }

    @Test
    public void countLabelsCountsDistinctPositiveLabelsRatherThanMaximumLabelValue() {
        ImagePlus labels = labelImage(new int[] {1, 7, 7, 0});

        assertEquals(2, StarDist3DRunner.countLabels(labels));
    }

    private static ResultsTable objectStats() {
        ResultsTable stats = new ResultsTable();
        stats.incrementCounter();
        stats.setValue("Label", 0, 1);
        stats.setValue(StarDist3DRunner.STATS_AREA_MEAN, 0, 4);
        stats.setValue(StarDist3DRunner.STATS_QUALITY_MEAN, 0, 0.2);
        stats.setValue(StarDist3DRunner.STATS_INTENSITY_MEAN, 0, 10);
        stats.incrementCounter();
        stats.setValue("Label", 1, 2);
        stats.setValue(StarDist3DRunner.STATS_AREA_MEAN, 1, 20);
        stats.setValue(StarDist3DRunner.STATS_QUALITY_MEAN, 1, 0.9);
        stats.setValue(StarDist3DRunner.STATS_INTENSITY_MEAN, 1, 100);
        return stats;
    }

    private static ImagePlus labelImage(int[] pixels) {
        ShortProcessor processor = new ShortProcessor(pixels.length, 1);
        for (int x = 0; x < pixels.length; x++) {
            processor.set(x, 0, pixels[x]);
        }
        ImageStack stack = new ImageStack(pixels.length, 1);
        stack.addSlice(processor);
        return new ImagePlus("labels", stack);
    }
}
