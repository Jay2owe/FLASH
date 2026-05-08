package flash.pipeline.objects;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ObjectsCounter3DWrapperTest {

    @Test
    public void parseSizeHelpersAcceptInfinityAndRoundedNumbers() {
        ImageStack stack = new ImageStack(4, 5);
        stack.addSlice(new ByteProcessor(4, 5));
        stack.addSlice(new ByteProcessor(4, 5));
        stack.addSlice(new ByteProcessor(4, 5));
        ImagePlus reference = new ImagePlus("reference", stack);

        assertEquals(13, ObjectsCounter3DWrapper.parseMinSizeVoxels("12.6", 100));
        assertEquals(60, ObjectsCounter3DWrapper.parseMaxSizeVoxels("Infinity", reference));
        assertEquals(60, ObjectsCounter3DWrapper.parseMaxSizeVoxels("inf", reference));
        assertEquals(8, ObjectsCounter3DWrapper.parseMaxSizeVoxels("7.6", reference));
    }
}
