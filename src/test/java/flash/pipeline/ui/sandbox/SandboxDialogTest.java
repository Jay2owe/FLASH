package flash.pipeline.ui.sandbox;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

public class SandboxDialogTest {

    @Test
    public void defaultDisplaySourceDoesNotReuseExecutionSource() throws Exception {
        AtomicInteger created = new AtomicInteger();
        SandboxDialog.PreviewHandler handler = new SandboxDialog.PreviewHandler() {
            @Override public ImagePlus createSource() {
                int index = created.incrementAndGet();
                return image("source-" + index);
            }

            @Override public ImagePlus showPreview(ImagePlus result, ImagePlus existingPreview) {
                return result;
            }

            @Override public void close(ImagePlus imp) {
                // No-op for this contract test.
            }
        };

        ImagePlus displaySource = handler.getSourceForDisplay();
        ImagePlus executionSource = handler.createSource();

        assertEquals(2, created.get());
        assertNotSame(displaySource, executionSource);
        assertEquals("source-1", displaySource.getTitle());
        assertEquals("source-2", executionSource.getTitle());
    }

    private static ImagePlus image(String title) {
        ImageStack stack = new ImageStack(2, 2);
        stack.addSlice(new ByteProcessor(2, 2));
        return new ImagePlus(title, stack);
    }
}
