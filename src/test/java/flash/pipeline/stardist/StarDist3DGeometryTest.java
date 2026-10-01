package flash.pipeline.stardist;

import flash.pipeline.segmentation.SegmentationRunFailureException;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ShortProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class StarDist3DGeometryTest {
    @Test
    public void realTimeFramesCannotBeFlattenedAndLinkedAsDepth() {
        assertRejectedGeometry(1, 2, 2);
    }

    @Test
    public void multipleChannelsCannotSilentlyQuantifyOnlyTheFirst() {
        assertRejectedGeometry(2, 2, 1);
    }

    private static void assertRejectedGeometry(int channels, int slices, int frames) {
        ImageStack stack = new ImageStack(1, 1);
        for (int i = 0; i < channels * slices * frames; i++) {
            stack.addSlice(new ShortProcessor(1, 1, new short[]{(short) (i + 1)}, null));
        }
        ImagePlus source = new ImagePlus("unsupported source", stack);
        source.setDimensions(channels, slices, frames);
        try {
            StarDist3DRunner.run(source, 0.5, 0.3);
            fail("Unsupported source must fail before model/runtime access");
        } catch (SegmentationRunFailureException expected) {
            assertTrue(expected.getMessage().contains("one channel and one time frame"));
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
            assertEquals(channels, source.getNChannels());
            assertEquals(slices, source.getNSlices());
            assertEquals(frames, source.getNFrames());
            for (int i = 1; i <= stack.size(); i++) assertEquals(i, stack.getProcessor(i).get(0));
        } finally {
            source.close();
            source.flush();
        }
    }
}
