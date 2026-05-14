package flash.pipeline.ui.variations;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class VariationCellPanelHoldToPeekTest {

    @Test
    public void holdShowsRawPreviewUntilRelease() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                ImagePlus raw = image("raw", 200);
                VariationCellPanel cell = renderedCell(null);
                ImagePlus filteredPreview = cell.currentPreviewImageForTest();
                cell.setRawSource(raw);

                press(cell, 8, 8);
                cell.firePeekDelayForTest();

                assertTrue(cell.isPeekingForTest());
                assertSame(raw, cell.currentPreviewImageForTest());
                assertEquals(200, centerPixel(cell.currentPreviewImageForTest()));

                release(cell, 8, 8);

                assertFalse(cell.isPeekingForTest());
                assertSame(filteredPreview, cell.currentPreviewImageForTest());
                assertNotSame(raw, cell.currentPreviewImageForTest());
            }
        });
    }

    @Test
    public void dragBeyondThresholdCancelsPendingPeek() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                ImagePlus raw = image("raw", 200);
                VariationCellPanel cell = renderedCell(null);
                ImagePlus filteredPreview = cell.currentPreviewImageForTest();
                cell.setRawSource(raw);

                press(cell, 8, 8);
                drag(cell, 23, 8);
                cell.firePeekDelayForTest();

                assertFalse(cell.isPeekingForTest());
                assertFalse(cell.isPeekDelayRunningForTest());
                assertSame(filteredPreview, cell.currentPreviewImageForTest());
            }
        });
    }

    @Test
    public void shortClickAcceptsWithoutSuppressingClick() throws Exception {
        final AtomicInteger accepts = new AtomicInteger();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                VariationCellPanel cell = renderedCell(accepts);
                cell.setRawSource(image("raw", 200));

                press(cell, 8, 8);
                release(cell, 8, 8);
                click(cell, false);

                assertEquals(1, accepts.get());
                assertFalse(cell.suppressNextClickForTest());
                assertFalse(cell.isPeekingForTest());
            }
        });
    }

    @Test
    public void longHoldSuppressesFollowingClick() throws Exception {
        final AtomicInteger accepts = new AtomicInteger();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                ImagePlus raw = image("raw", 200);
                VariationCellPanel cell = renderedCell(accepts);
                ImagePlus filteredPreview = cell.currentPreviewImageForTest();
                cell.setRawSource(raw);

                press(cell, 8, 8);
                cell.firePeekDelayForTest();
                release(cell, 8, 8);
                click(cell, false);

                assertEquals(0, accepts.get());
                assertFalse(cell.suppressNextClickForTest());
                assertFalse(cell.isPeekingForTest());
                assertSame(filteredPreview, cell.currentPreviewImageForTest());
            }
        });
    }

    @Test
    public void removeNotifyStopsPeekTimerAndRestoresPreview() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                ImagePlus raw = image("raw", 200);
                VariationCellPanel cell = renderedCell(null);
                ImagePlus filteredPreview = cell.currentPreviewImageForTest();
                cell.setRawSource(raw);

                press(cell, 8, 8);
                assertTrue(cell.isPeekDelayRunningForTest());
                cell.firePeekDelayForTest();
                assertSame(raw, cell.currentPreviewImageForTest());

                cell.removeNotify();

                assertFalse(cell.isPeekDelayRunningForTest());
                assertFalse(cell.isPeekingForTest());
                assertSame(filteredPreview, cell.currentPreviewImageForTest());
            }
        });
    }

    private static VariationCellPanel renderedCell(AtomicInteger accepts) {
        final AtomicReference<ParameterCombo> ignored =
                new AtomicReference<ParameterCombo>();
        VariationCellPanel cell = new VariationCellPanel(combo(), image("filtered", 10),
                accepted -> {
                    if (accepts != null) {
                        accepts.incrementAndGet();
                    }
                    ignored.set(accepted);
                },
                (clickedCombo, clickedCell) -> {
                });
        cell.setLabel(image("label", 0), null, 0, 1L);
        return cell;
    }

    private static void press(VariationCellPanel cell, int x, int y) {
        MouseEvent event = event(cell, MouseEvent.MOUSE_PRESSED, x, y, 0);
        MouseListener[] listeners = cell.getMouseListeners();
        for (int i = 0; i < listeners.length; i++) {
            listeners[i].mousePressed(event);
        }
    }

    private static void release(VariationCellPanel cell, int x, int y) {
        MouseEvent event = event(cell, MouseEvent.MOUSE_RELEASED, x, y, 0);
        MouseListener[] listeners = cell.getMouseListeners();
        for (int i = 0; i < listeners.length; i++) {
            listeners[i].mouseReleased(event);
        }
    }

    private static void click(VariationCellPanel cell, boolean shiftDown) {
        int modifiers = shiftDown ? InputEvent.SHIFT_DOWN_MASK : 0;
        MouseEvent event = event(cell, MouseEvent.MOUSE_CLICKED, 8, 8, modifiers);
        MouseListener[] listeners = cell.getMouseListeners();
        for (int i = 0; i < listeners.length; i++) {
            listeners[i].mouseClicked(event);
        }
    }

    private static void drag(VariationCellPanel cell, int x, int y) {
        MouseEvent event = event(cell, MouseEvent.MOUSE_DRAGGED, x, y, 0);
        MouseMotionListener[] listeners = cell.getMouseMotionListeners();
        for (int i = 0; i < listeners.length; i++) {
            listeners[i].mouseDragged(event);
        }
    }

    private static MouseEvent event(VariationCellPanel cell, int id,
                                    int x, int y, int modifiers) {
        return new MouseEvent(cell,
                id,
                System.currentTimeMillis(),
                modifiers,
                x,
                y,
                1,
                false,
                MouseEvent.BUTTON1);
    }

    private static int centerPixel(ImagePlus image) {
        return image.getProcessor().getPixel(2, 2);
    }

    private static ParameterCombo combo() {
        return ParameterCombo.builder()
                .put(ParameterId.THRESHOLD, Integer.valueOf(100))
                .build();
    }

    private static ImagePlus image(String title, int value) {
        ByteProcessor processor = new ByteProcessor(4, 4);
        processor.setValue(value);
        processor.fill();
        return new ImagePlus(title, processor);
    }
}
