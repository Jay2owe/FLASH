package flash.pipeline.morphometry;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import mcib3d.geom2.Object3DInt;
import mcib3d.geom2.Objects3DIntPopulation;
import mcib3d.image3d.ImageHandler;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ObjectPatchBuilderTest {

    @Test
    public void buildMipPadsBoundingBoxAndProjectsObjectMaskAcrossSlices() {
        ImagePlus label = labelStack();
        ImagePlus raw = rawStack();
        Object3DInt object = objectByLabel(label).get(Integer.valueOf(7));

        ObjectPatch patch = ObjectPatchBuilder.buildMIP(object, label, raw);

        assertEquals(6, patch.width);
        assertEquals(6, patch.height);
        assertEquals(16, patch.objectPixelCount());
        assertEquals(0.5, patch.pixelSize_um, 1e-12);
        assertEquals(265.0f, patch.intensity[patch.index(5 - 3, 6 - 4)], 1e-6f);
        assertTrue(patch.containsObjectPixel(4 - 3, 5 - 4));
        assertEquals(0, patch.mask[patch.index(3 - 3, 4 - 4)]);
    }

    @Test
    public void buildSliceUsesOnlyTheRequestedZSliceMask() {
        ImagePlus label = labelStack();
        ImagePlus raw = rawStack();
        Object3DInt object = objectByLabel(label).get(Integer.valueOf(7));

        ObjectPatch emptySlice = ObjectPatchBuilder.buildSlice(object, label, raw, 0);
        ObjectPatch objectSlice = ObjectPatchBuilder.buildSlice(object, label, raw, 1);

        assertEquals(0, emptySlice.objectPixelCount());
        assertEquals(16, objectSlice.objectPixelCount());
        assertEquals(165.0f, objectSlice.intensity[objectSlice.index(5 - 3, 6 - 4)], 1e-6f);
    }

    @Test
    public void buildVolumetricPadsAllAxesAndUsesObjectVoxelsForMask() {
        ImagePlus label = labelStack();
        ImagePlus raw = rawStack();
        Object3DInt object = objectByLabel(label).get(Integer.valueOf(7));

        ObjectPatch3D patch = ObjectPatchBuilder.buildVolumetric(object, raw);

        assertEquals(6, patch.width);
        assertEquals(6, patch.height);
        assertEquals(3, patch.depth);
        assertEquals(32, patch.objectVoxelCount());
        assertEquals(0.5, patch.pixelSize_um, 1e-12);
        assertEquals(2.0, patch.sliceSpacing_um, 1e-12);
        assertEquals(265.0f, patch.intensity[patch.index(5 - 3, 6 - 4, 2)], 1e-6f);
        assertTrue(patch.containsObjectVoxel(4 - 3, 5 - 4, 1));
        assertEquals(0, patch.mask[patch.index(3 - 3, 4 - 4, 0)]);
    }

    private static ImagePlus labelStack() {
        int width = 12;
        int height = 12;
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < 3; z++) {
            ShortProcessor sp = new ShortProcessor(width, height);
            if (z == 1 || z == 2) {
                for (int y = 5; y <= 8; y++) {
                    for (int x = 4; x <= 7; x++) {
                        sp.set(x, y, 7);
                    }
                }
            }
            stack.addSlice(sp);
        }
        return new ImagePlus("labels", stack);
    }

    private static ImagePlus rawStack() {
        int width = 12;
        int height = 12;
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < 3; z++) {
            FloatProcessor fp = new FloatProcessor(width, height);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    fp.setf(x, y, z * 100 + y * 10 + x);
                }
            }
            stack.addSlice(fp);
        }
        ImagePlus raw = new ImagePlus("raw", stack);
        Calibration cal = new Calibration(raw);
        cal.pixelWidth = 0.5;
        cal.pixelDepth = 2.0;
        raw.setCalibration(cal);
        return raw;
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
