package flash.pipeline.ui.variations;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import javax.swing.SwingUtilities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class VariationCellPanelBaselineTest {

    @Test
    public void baselineCellHasNoAcceptCallback() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                VariationCellPanel cell = VariationCellPanel.baseline(source());

                assertTrue(cell.isBaselineForTest());
                assertFalse(cell.isAcceptEnabledForTest());
                assertFalse(cell.isPickPillVisibleForTest());
            }
        });
    }

    @Test
    public void baselineCellShowsOriginalImage() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                ImagePlus source = source();
                VariationCellPanel cell = VariationCellPanel.baseline(source);
                SyncedSliceController controller = new SyncedSliceController();

                controller.register(cell);
                controller.setSlice(2);

                assertSame(source, cell.currentPreviewImageForTest());
                assertEquals(2, cell.currentZForTest());
                assertEquals(80, cell.currentPreviewImageForTest()
                        .getStack()
                        .getProcessor(cell.currentZForTest())
                        .getPixel(0, 0));
            }
        });
    }

    @Test
    public void baselineCellHasBaselineRibbonLabel() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                VariationCellPanel cell = VariationCellPanel.baseline(source());

                assertEquals("Original", cell.footerTextForTest());
            }
        });
    }

    private static ImagePlus source() {
        ImageStack stack = new ImageStack(4, 4);
        for (int z = 1; z <= 3; z++) {
            ByteProcessor processor = new ByteProcessor(4, 4);
            processor.setValue(z * 40);
            processor.fill();
            stack.addSlice("z" + z, processor);
        }
        return new ImagePlus("source", stack);
    }
}
