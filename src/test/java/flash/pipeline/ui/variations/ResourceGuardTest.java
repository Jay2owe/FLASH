package flash.pipeline.ui.variations;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ResourceGuardTest {

    @Test
    public void refusesLargeThousandCellSweep() {
        ParameterSweep sweep = sweepWithCellCount(1000, CropSpec.full());
        ImagePlus source = stack("large", 1024, 1024, 30);

        ResourceGuard.Feasibility feasibility = ResourceGuard.assessFeasibility(sweep, source);

        assertFalse(feasibility.ok);
        assertTrue(feasibility.estimatedBytes > feasibility.availableBytes);
    }

    @Test
    public void acceptsSmallTwentyFiveCellCropWithHeadroom() {
        requestGarbageCollection();
        ParameterSweep sweep = sweepWithCellCount(25, CropSpec.centre256());
        ImagePlus source = stack("small", 512, 512, 30);

        ResourceGuard.Feasibility feasibility = ResourceGuard.assessFeasibility(sweep, source);

        assertTrue("Expected memory headroom, available=" + feasibility.availableBytes
                + " estimated=" + feasibility.estimatedBytes + " message=" + feasibility.message,
                feasibility.ok);
        assertTrue(feasibility.availableBytes >= feasibility.estimatedBytes * 2L);
    }

    private static void requestGarbageCollection() {
        System.gc();
        System.runFinalization();
        System.gc();
    }

    private static ParameterSweep sweepWithCellCount(int count, CropSpec cropSpec) {
        List<Object> values = new ArrayList<Object>();
        for (int i = 0; i < count; i++) {
            values.add(Integer.valueOf(i));
        }
        Map<ParameterId, ParameterValueList> map =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        map.put(ParameterId.THRESHOLD, new ParameterValueList(values));
        return new ParameterSweep(ParameterSweep.Method.CLASSICAL, map, cropSpec, "DAPI", "abc");
    }

    private static ImagePlus stack(String title, int width, int height, int slices) {
        ImageStack stack = new ImageStack(width, height);
        for (int i = 0; i < slices; i++) {
            stack.addSlice(new ByteProcessor(width, height));
        }
        return new ImagePlus(title, stack);
    }
}
