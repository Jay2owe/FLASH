package flash.pipeline.morphometry;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ShortProcessor;
import mcib3d.geom2.Object3DInt;
import mcib3d.geom2.Objects3DIntPopulation;
import mcib3d.image3d.ImageHandler;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ObjectArborizationTest {

    @Test
    public void straightSkeletonObjectProducesShollAndGraphCounts() {
        ImagePlus label = lineLabelImage();
        Object3DInt object = objectByLabel(label).get(Integer.valueOf(7));

        ObjectArborization.Result result = ObjectArborization.compute(object, label, 1.0);

        assertTrue(result.valid);
        assertEquals(1.0, result.skeletonBranches, 0.0);
        assertEquals(0.0, result.skeletonJunctions, 0.0);
        assertEquals(2.0, result.skeletonEndpoints, 0.0);
        assertEquals(2.0, result.shollCriticalIntersections, 0.0);
        assertEquals(1.0, result.shollSchoenenIndex, 0.0);
        assertFalse(result.shollProfile.isEmpty());
    }

    private static ImagePlus lineLabelImage() {
        int width = 9;
        int height = 5;
        ImageStack stack = new ImageStack(width, height);
        ShortProcessor sp = new ShortProcessor(width, height);
        for (int x = 2; x <= 6; x++) {
            sp.set(x, 2, 7);
        }
        stack.addSlice(sp);
        ImagePlus label = new ImagePlus("line-label", stack);
        Calibration cal = new Calibration(label);
        cal.pixelWidth = 1.0;
        cal.pixelHeight = 1.0;
        cal.pixelDepth = 1.0;
        cal.setUnit("um");
        label.setCalibration(cal);
        return label;
    }

    private static Map<Integer, Object3DInt> objectByLabel(ImagePlus label) {
        Objects3DIntPopulation population = new Objects3DIntPopulation(ImageHandler.wrap(label));
        Map<Integer, Object3DInt> out = new LinkedHashMap<Integer, Object3DInt>();
        for (Object3DInt object : population.getObjects3DInt()) {
            out.put(Integer.valueOf((int) object.getLabel()), object);
        }
        return out;
    }
}
