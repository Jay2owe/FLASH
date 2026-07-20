package flash.pipeline.cellpose;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class Cellpose3DRunnerCellprobIntegrationTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void attachesCellprobSidecarUsingPersistentWorkerFilenameContract()
            throws Exception {
        Path tempDir = temp.newFolder("cellprob-sidecar").toPath();
        Path cellprobPath = Cellpose3DRunner.expectedCellprobPath(tempDir);
        ImagePlus labels = labels(4, 3, 2);
        ImagePlus sourceCellprob = cellprob(4, 3, 2);
        IJ.saveAsTiff(sourceCellprob, cellprobPath.toString());

        Cellpose3DRunner.attachCellprobImage(labels, cellprobPath);

        Object property = labels.getProperty(Cellpose3DRunner.CELLPROB_IMAGE_PROPERTY);
        assertNotNull(property);
        assertTrue(property instanceof ImagePlus);
        ImagePlus attached = (ImagePlus) property;
        assertEquals(4, attached.getWidth());
        assertEquals(3, attached.getHeight());
        assertEquals(2, attached.getStackSize());
        assertEquals(32, attached.getBitDepth());
        assertEquals(1.25f, attached.getStack().getProcessor(2).getf(1, 1), 1.0e-6f);
    }

    private static ImagePlus labels(int width, int height, int slices) {
        ImageStack stack = new ImageStack(width, height);
        for (int s = 0; s < slices; s++) {
            ShortProcessor processor = new ShortProcessor(width, height);
            processor.set(1, 1, s + 1);
            stack.addSlice(processor);
        }
        return new ImagePlus("labels", stack);
    }

    private static ImagePlus cellprob(int width, int height, int slices) {
        ImageStack stack = new ImageStack(width, height);
        for (int s = 0; s < slices; s++) {
            FloatProcessor processor = new FloatProcessor(width, height);
            processor.setf(1, 1, s + 0.25f);
            stack.addSlice(processor);
        }
        return new ImagePlus("cellprob", stack);
    }
}
