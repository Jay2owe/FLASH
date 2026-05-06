package flash.pipeline.cellpose;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ShortProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class Cellpose3DRunnerTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void buildCellposeCommand_usesSingleImageNative3dFlags() throws Exception {
        ImagePlus input = createStack(4, 3, 5);
        Calibration cal = new Calibration();
        cal.pixelWidth = 0.5;
        cal.pixelHeight = 0.5;
        cal.pixelDepth = 2.0;
        input.setCalibration(cal);

        Path tempDir = temp.newFolder("cellpose-command").toPath();
        Path inputStackPath = tempDir.resolve("cellpose_input.tif");

        List<String> command = Cellpose3DRunner.buildCellposeCommand(
                "C:\\python.exe",
                inputStackPath,
                tempDir,
                "cyto3",
                input,
                30.0,
                0.4,
                0.0,
                false);

        assertTrue(command.contains("--image_path"));
        assertTrue(command.contains(inputStackPath.toString()));
        assertTrue(command.contains("--savedir"));
        assertTrue(command.contains(tempDir.toString()));
        assertTrue(command.contains("--do_3D"));
        assertTrue(command.contains("--z_axis"));
        assertTrue(command.contains("--anisotropy"));
        assertTrue(command.contains("4.0"));
        assertTrue(command.contains("--save_tif"));
        assertFalse(command.contains("--save_png"));
        assertFalse(command.contains("--dir"));
    }

    @Test
    public void buildCellposeCommand_usesCompanionChannelFlagsForMergedInput() throws Exception {
        ImagePlus primary = createStack(4, 3, 5);
        ImagePlus companion = createStack(4, 3, 5);
        ImagePlus merged = Cellpose3DRunner.prepareRuntimeInput(primary, companion, "GFAP");

        Path tempDir = temp.newFolder("cellpose-command-multichannel").toPath();
        Path inputStackPath = tempDir.resolve("cellpose_input.tif");

        List<String> command = Cellpose3DRunner.buildCellposeCommand(
                "C:\\python.exe",
                inputStackPath,
                tempDir,
                "cyto3",
                merged,
                true,
                30.0,
                0.4,
                0.0,
                true);

        assertTrue(command.contains("--chan"));
        assertTrue(command.contains("1"));
        assertTrue(command.contains("--chan2"));
        assertTrue(command.contains("2"));
        assertTrue(command.contains("--channel_axis"));
        assertTrue(command.contains("--use_gpu"));
    }

    @Test
    public void computeAnisotropy_returnsNullWithoutValidCalibration() {
        ImagePlus input = createStack(4, 3, 5);

        assertNull(Cellpose3DRunner.computeAnisotropy(input));
    }

    @Test
    public void readMaskImage_loadsThreeDimensionalMaskTif() throws Exception {
        ImagePlus input = createStack(3, 2, 3);
        Calibration cal = new Calibration();
        cal.pixelWidth = 0.5;
        cal.pixelHeight = 0.5;
        cal.pixelDepth = 2.0;
        input.setCalibration(cal);

        Path tempDir = temp.newFolder("cellpose-mask").toPath();
        Path maskPath = Cellpose3DRunner.expectedMaskPath(tempDir);
        IJ.saveAsTiff(maskStack(3, 2, new int[][][] {
                { {0, 0, 1} },
                { {1, 1, 2} },
                { {2, 1, 3} }
        }), maskPath.toString());

        ImagePlus labels = Cellpose3DRunner.readMaskImage(maskPath, input, "Test");

        assertNotNull(labels);
        assertEquals(3, labels.getStackSize());
        assertEquals(1, labels.getStack().getProcessor(1).get(0, 0));
        assertEquals(2, labels.getStack().getProcessor(2).get(1, 1));
        assertEquals(3, labels.getStack().getProcessor(3).get(2, 1));
        assertEquals(3, Cellpose3DRunner.countLabels(labels));
        assertEquals(0.5, labels.getCalibration().pixelWidth, 0.0);
    }

    @Test
    public void writeInputStack_createsExpectedTemporaryTiff() throws Exception {
        ImagePlus input = createStack(4, 3, 2);
        Path tempDir = temp.newFolder("cellpose-input").toPath();

        Path inputStackPath = Cellpose3DRunner.writeInputStack(input, tempDir);

        assertEquals(tempDir.resolve("cellpose_input.tif"), inputStackPath);
        assertTrue(java.nio.file.Files.isRegularFile(inputStackPath));
    }

    @Test
    public void prepareRuntimeInput_mergesPrimaryAndCompanionIntoTwoChannelHyperstack() {
        ImagePlus primary = createStack(4, 3, 5);
        ImagePlus companion = createStack(4, 3, 5);

        ImagePlus merged = Cellpose3DRunner.prepareRuntimeInput(primary, companion, "GFAP");

        assertNotNull(merged);
        assertEquals(2, merged.getNChannels());
        assertEquals(5, merged.getNSlices());
        assertEquals(10, merged.getStackSize());
    }

    private static ImagePlus createStack(int width, int height, int slices) {
        ImageStack stack = new ImageStack(width, height);
        for (int s = 0; s < slices; s++) {
            stack.addSlice(new ShortProcessor(width, height));
        }
        return new ImagePlus("stack", stack);
    }

    private static ImagePlus maskStack(int width, int height, int[][][] labeledPixelsPerSlice) {
        ImageStack stack = new ImageStack(width, height);
        for (int[][] slicePixels : labeledPixelsPerSlice) {
            ShortProcessor processor = new ShortProcessor(width, height);
            for (int[] pixel : slicePixels) {
                processor.set(pixel[0], pixel[1], pixel[2]);
            }
            stack.addSlice(processor);
        }
        return new ImagePlus("mask", stack);
    }
}
