package flash.pipeline.ui.variations;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;

public class SyncedSliceControllerTest {

    @Test
    public void registerAndUnregisterTrackSize() throws Exception {
        SyncedSliceController controller = new SyncedSliceController();
        VariationCellPanel first = cellWithSlices(3);
        VariationCellPanel second = cellWithSlices(5);

        controller.register(first);
        controller.register(second);
        assertEquals(2, controller.size());

        controller.unregister(first);
        assertEquals(1, controller.size());
        assertEquals(5, controller.maxSlice());
    }

    @Test
    public void setSliceClampsAndTracksCurrentSlice() throws Exception {
        final SyncedSliceController controller = new SyncedSliceController();
        final VariationCellPanel cell = cellWithSlices(4);
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                controller.register(cell);
                controller.setSlice(3);
            }
        });
        assertEquals(3, controller.currentSlice());
        assertEquals(3, cell.currentZForTest());

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                controller.setSlice(0);
            }
        });
        assertEquals(1, controller.currentSlice());
        assertEquals(1, cell.currentZForTest());

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                controller.setSlice(99);
            }
        });
        assertEquals(4, controller.currentSlice());
        assertEquals(4, cell.currentZForTest());
    }

    @Test
    public void maxSliceUsesMinimumRegisteredStackDepth() throws Exception {
        final SyncedSliceController controller = new SyncedSliceController();
        final VariationCellPanel deep = cellWithSlices(7);
        final VariationCellPanel shallow = cellWithSlices(3);
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                controller.register(deep);
                controller.register(shallow);
                controller.setSlice(6);
            }
        });
        assertEquals(3, controller.maxSlice());
        assertEquals(3, controller.currentSlice());
        assertEquals(3, deep.currentZForTest());
        assertEquals(3, shallow.currentZForTest());
    }

    private static VariationCellPanel cellWithSlices(final int slices)
            throws Exception {
        final AtomicReference<VariationCellPanel> ref =
                new AtomicReference<VariationCellPanel>();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                VariationCellPanel cell = new VariationCellPanel(
                        ParameterCombo.builder().build(), null, null, null);
                cell.preview().setImage(stack(slices));
                ref.set(cell);
            }
        });
        return ref.get();
    }

    private static ImagePlus stack(int slices) {
        ImageStack stack = new ImageStack(8, 8);
        for (int z = 0; z < slices; z++) {
            ByteProcessor processor = new ByteProcessor(8, 8);
            processor.setValue(z + 1);
            processor.fill();
            stack.addSlice("z" + (z + 1), processor);
        }
        ImagePlus image = new ImagePlus("stack-" + slices, stack);
        image.setDimensions(1, slices, 1);
        return image;
    }
}
