package flash.pipeline.objects;

import flash.pipeline.stardist.StarDist3DRunner;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class LabelIndexTest {

    @Test
    public void getLabelAtHandlesCoordinatesAndZBoundaries() {
        ImageStack stack = new ImageStack(4, 3);
        ByteProcessor first = new ByteProcessor(4, 3);
        ByteProcessor second = new ByteProcessor(4, 3);
        first.set(2, 1, 9);
        second.set(1, 2, 12);
        stack.addSlice(first);
        stack.addSlice(second);
        ImagePlus labels = new ImagePlus("labels", stack);

        assertEquals(9, LabelIndex.getLabelAt(labels, 2, 1, 1));
        assertEquals(12, LabelIndex.getLabelAt(labels, 1, 2, 2));
        assertEquals(0, LabelIndex.getLabelAt(labels, -1, 1, 1));
        assertEquals(0, LabelIndex.getLabelAt(labels, 4, 1, 1));
        assertEquals(0, LabelIndex.getLabelAt(labels, 1, 3, 1));
        assertEquals(0, LabelIndex.getLabelAt(labels, 1, 1, 0));
        assertEquals(0, LabelIndex.getLabelAt(labels, 1, 1, 3));
        assertEquals(0, LabelIndex.getLabelAt(null, 1, 1, 1));
    }

    @Test
    public void starDistStatsRoundTripFromImageProperty() {
        ImagePlus labels = new ImagePlus("labels", new ByteProcessor(2, 2));
        ResultsTable stats = new ResultsTable();
        stats.incrementCounter();
        stats.setValue("Label", 0, 47);
        labels.setProperty(StarDist3DRunner.OBJECT_STATS_PROPERTY, stats);

        assertTrue(LabelIndex.hasStarDistStats(labels));
        assertSame(stats, LabelIndex.starDistStats(labels));
        assertFalse(LabelIndex.hasStarDistStats(new ImagePlus("empty", new ByteProcessor(1, 1))));
    }
}
