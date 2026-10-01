package flash.pipeline.objects;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import org.junit.Test;
import static org.junit.Assert.*;

public class ObjectsCounterBorderConsistencyTest {
    @Test
    public void excludedBorderObjectsDisappearFromStatisticsLabelsAndRedirectedMask() {
        ImageStack stack = new ImageStack(7, 7);
        for (int z = 0; z < 7; z++) stack.addSlice(new FloatProcessor(7, 7));
        stack.getProcessor(1).setf(0, 0, 200.0f);
        stack.getProcessor(4).setf(3, 3, 200.0f);
        ImagePlus source = new ImagePlus("two objects", stack);
        ObjectsCounter3DWrapper.Result result = new ObjectsCounter3DWrapper().runNative(
                source, 100, 1, 10, true, source, true, true);
        assertEquals(1, result.getStatistics().size());
        assertEquals(1, CpcUtils.extractObjects(result.getObjectsMap()).size());
        assertEquals(0.0f, result.getObjectsMap().getStack().getProcessor(1).getf(0, 0), 0.0f);
        assertEquals(0.0f, result.getMaskedImage().getStack().getProcessor(1).getf(0, 0), 0.0f);
        assertEquals(200.0f, source.getStack().getProcessor(1).getf(0, 0), 0.0f);
    }
}
