package flash.pipeline.ui.preview;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import javax.swing.JPanel;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ImagePreviewPanelClickTest {

    @Test
    public void clickInvertsPaintTransformAtMultipleCanvasScales() {
        assertClickMapsToImageCoordinate(260, 260, 4.25, 3.5);
        assertClickMapsToImageCoordinate(520, 260, 8.5, 6.25);
        assertClickMapsToImageCoordinate(260, 420, 2.0, 1.25);
    }

    @Test
    public void canvasInterceptsClicksOnlyWhenPixelClickListenerIsSet() {
        // REGRESSION: the canvas used to register a mouse listener
        // unconditionally in its constructor. Because Swing delivers a mouse
        // event to the deepest component that has a listener, the canvas
        // swallowed clicks meant for an ancestor (e.g. a variation tile's
        // pick/select handler) whenever no pixel-click consumer was set, so
        // clicking a variation tile appeared to do nothing. The canvas must
        // only listen while a PixelClickListener is registered.
        ImagePreviewPanel panel = new ImagePreviewPanel("Preview");
        assertEquals(0, panel.canvasForTest().getMouseListeners().length);

        panel.setPixelClickListener(new ImagePreviewPanel.PixelClickListener() {
            @Override public void pixelClicked(ImagePreviewPanel src, double imageX,
                                               double imageY, int z, int button,
                                               int modifiers) {
            }
        });
        assertEquals(1, panel.canvasForTest().getMouseListeners().length);

        panel.setPixelClickListener(null);
        assertEquals(0, panel.canvasForTest().getMouseListeners().length);
    }

    @Test
    public void clickOutsideDrawnImageIsIgnored() {
        ImagePreviewPanel panel = paintedPanel(260, 260);
        final int[] count = {0};
        panel.setPixelClickListener(new ImagePreviewPanel.PixelClickListener() {
            @Override public void pixelClicked(ImagePreviewPanel src, double imageX, double imageY,
                                               int z, int button, int modifiers) {
                count[0]++;
            }
        });

        dispatchClick(panel.canvasForTest(),
                panel.drawOriginXForTest() - 3,
                panel.drawOriginYForTest() - 3,
                0,
                MouseEvent.BUTTON1);

        assertEquals(0, count[0]);
    }

    private static void assertClickMapsToImageCoordinate(int width, int height,
                                                         final double imageX,
                                                         final double imageY) {
        ImagePreviewPanel panel = paintedPanel(width, height);
        final double[] observed = {Double.NaN, Double.NaN};
        final int[] meta = {0, 0, 0};
        panel.setPixelClickListener(new ImagePreviewPanel.PixelClickListener() {
            @Override public void pixelClicked(ImagePreviewPanel src, double x, double y,
                                               int z, int button, int modifiers) {
                observed[0] = x;
                observed[1] = y;
                meta[0] = z;
                meta[1] = button;
                meta[2] = modifiers;
            }
        });
        int sx = panel.drawOriginXForTest()
                + (int) Math.round(imageX * panel.drawScaleForTest());
        int sy = panel.drawOriginYForTest()
                + (int) Math.round(imageY * panel.drawScaleForTest());

        dispatchClick(panel.canvasForTest(), sx, sy,
                MouseEvent.SHIFT_DOWN_MASK, MouseEvent.BUTTON1);

        assertEquals(imageX, observed[0], 0.75);
        assertEquals(imageY, observed[1], 0.75);
        assertEquals(2, meta[0]);
        assertEquals(MouseEvent.BUTTON1, meta[1]);
        assertTrue((meta[2] & MouseEvent.SHIFT_DOWN_MASK) != 0);
    }

    private static ImagePreviewPanel paintedPanel(int width, int height) {
        ImagePreviewPanel panel = new ImagePreviewPanel("Preview");
        panel.setImage(stack("source", 3));
        panel.setCurrentZ(2);
        panel.setSize(width, height);
        panel.doLayout();

        BufferedImage rendered = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rendered.createGraphics();
        try {
            panel.paint(graphics);
        } finally {
            graphics.dispose();
        }
        assertTrue(panel.renderedImageWidthForTest() > 0);
        assertTrue(panel.renderedImageHeightForTest() > 0);
        return panel;
    }

    private static void dispatchClick(JPanel canvas, int x, int y,
                                      int modifiers, int button) {
        MouseEvent event = new MouseEvent(canvas,
                MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                modifiers,
                x,
                y,
                1,
                false,
                button);
        java.awt.event.MouseListener[] listeners = canvas.getMouseListeners();
        for (int i = 0; i < listeners.length; i++) {
            listeners[i].mouseClicked(event);
        }
    }

    private static ImagePlus stack(String title, int slices) {
        ImageStack stack = new ImageStack(12, 8);
        for (int i = 0; i < slices; i++) {
            ByteProcessor processor = new ByteProcessor(12, 8);
            processor.set(1, 1, i + 1);
            stack.addSlice(processor);
        }
        return new ImagePlus(title, stack);
    }
}
