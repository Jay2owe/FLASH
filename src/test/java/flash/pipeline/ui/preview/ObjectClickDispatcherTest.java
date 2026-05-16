package flash.pipeline.ui.preview;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.awt.event.MouseEvent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ObjectClickDispatcherTest {

    @Test
    public void popupClickStillFires() {
        final int[] seenLabel = {0};
        final int[] seenZ = {0};
        final boolean[] seenPositive = {false};
        final boolean[] seenClear = {false};

        ObjectClickDispatcher.dispatch(labels("objects"), 0.0, 0.0, 1,
                MouseEvent.BUTTON1, MouseEvent.SHIFT_DOWN_MASK,
                new ObjectClickDispatcher.Handler() {
                    @Override public void objectClicked(int label, int z,
                                                        double x, double y,
                                                        boolean positive, boolean clear) {
                        seenLabel[0] = label;
                        seenZ[0] = z;
                        seenPositive[0] = positive;
                        seenClear[0] = clear;
                    }
                });

        assertEquals(5, seenLabel[0]);
        assertEquals(1, seenZ[0]);
        assertTrue(seenPositive[0]);
        assertFalse(seenClear[0]);
    }

    private static ImagePlus labels(String title) {
        ByteProcessor processor = new ByteProcessor(2, 1);
        processor.set(0, 0, 5);
        processor.set(1, 0, 0);
        return new ImagePlus(title, processor);
    }
}
