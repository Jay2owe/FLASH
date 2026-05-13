package flash.pipeline.ui.sandbox.variation;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public class SharedSliceDriverTest {

    @Test
    public void setSliceFansOutToAllRegisteredTiles() {
        SharedSliceDriver driver = new SharedSliceDriver();
        ImagePlus a = stack(8, 8, 10);
        ImagePlus b = stack(8, 8, 10);
        ImagePlus c = stack(8, 8, 10);
        AtomicInteger repaints = new AtomicInteger();
        Runnable bump = new Runnable() {
            @Override public void run() {
                repaints.incrementAndGet();
            }
        };
        driver.register(a, bump);
        driver.register(b, bump);
        driver.register(c, bump);

        driver.setSlice(5);

        assertEquals(5, a.getCurrentSlice());
        assertEquals(5, b.getCurrentSlice());
        assertEquals(5, c.getCurrentSlice());
        assertEquals(5, driver.currentSlice());
        assertEquals(3, repaints.get());
    }

    @Test
    public void setSliceClampsAboveMaxSlice() {
        SharedSliceDriver driver = new SharedSliceDriver();
        ImagePlus a = stack(8, 8, 10);
        ImagePlus b = stack(8, 8, 10);
        driver.register(a, null);
        driver.register(b, null);

        driver.setSlice(1000);

        assertEquals(10, driver.currentSlice());
        assertEquals(10, a.getCurrentSlice());
        assertEquals(10, b.getCurrentSlice());
    }

    @Test
    public void setSliceClampsBelowOne() {
        SharedSliceDriver driver = new SharedSliceDriver();
        driver.register(stack(8, 8, 10), null);
        driver.setSlice(-7);
        assertEquals(1, driver.currentSlice());
    }

    @Test
    public void maxSliceIsMinimumAcrossRegisteredTiles() {
        SharedSliceDriver driver = new SharedSliceDriver();
        driver.register(stack(8, 8, 12), null);
        driver.register(stack(8, 8, 4), null);
        driver.register(stack(8, 8, 12), null);
        assertEquals(4, driver.maxSlice());
    }

    @Test
    public void unregisterRemovesImageAndClampsCurrentSlice() {
        SharedSliceDriver driver = new SharedSliceDriver();
        ImagePlus tall = stack(8, 8, 12);
        ImagePlus shortStack = stack(8, 8, 4);
        driver.register(tall, null);
        driver.register(shortStack, null);
        driver.setSlice(4);

        driver.unregister(shortStack);
        driver.setSlice(10);

        assertEquals(10, driver.currentSlice());
        assertEquals(10, tall.getCurrentSlice());
        assertEquals(1, driver.size());
    }

    @Test
    public void maxSliceIsOneWhenNoTilesRegistered() {
        assertEquals(1, new SharedSliceDriver().maxSlice());
    }

    @Test
    public void nullRepaintCallbackIsTolerated() {
        SharedSliceDriver driver = new SharedSliceDriver();
        ImagePlus a = stack(8, 8, 10);
        driver.register(a, null);
        driver.setSlice(3);
        assertEquals(3, a.getCurrentSlice());
    }

    private static ImagePlus stack(int width, int height, int slices) {
        ImageStack stack = new ImageStack(width, height);
        for (int i = 0; i < slices; i++) {
            stack.addSlice("s" + i, new ByteProcessor(width, height));
        }
        return new ImagePlus("test-" + slices, stack);
    }
}
