package flash.pipeline.ui.variations;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class VariationCellPanelShiftClickTest {

    @Test
    public void shiftClickTwoRenderedCellsOpensComparisonPair() throws Exception {
        final AtomicReference<String> status = new AtomicReference<String>();
        final AtomicReference<VariationCellPanel> openedLeft =
                new AtomicReference<VariationCellPanel>();
        final AtomicReference<VariationCellPanel> openedRight =
                new AtomicReference<VariationCellPanel>();

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                VariationComparisonSelection selection = new VariationComparisonSelection(
                        status::set,
                        new VariationComparisonSelection.Opener() {
                            @Override public void openComparison(VariationCellPanel left,
                                                                 VariationCellPanel right) {
                                openedLeft.set(left);
                                openedRight.set(right);
                            }
                        });
                VariationCellPanel first = renderedCell(combo(1), selection, null);
                VariationCellPanel second = renderedCell(combo(2), selection, null);

                click(first, true);
                assertTrue(first.isSelectedForCompareForTest());
                assertEquals("Shift-click a second tile to compare.", status.get());

                click(second, true);

                assertSame(first, openedLeft.get());
                assertSame(second, openedRight.get());
                assertFalse(first.isSelectedForCompareForTest());
                assertFalse(second.isSelectedForCompareForTest());
            }
        });
    }

    @Test
    public void shiftClickSameCellCancelsSelection() throws Exception {
        final AtomicReference<String> status = new AtomicReference<String>();
        final AtomicReference<VariationCellPanel> openedLeft =
                new AtomicReference<VariationCellPanel>();

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                VariationComparisonSelection selection = new VariationComparisonSelection(
                        status::set,
                        new VariationComparisonSelection.Opener() {
                            @Override public void openComparison(VariationCellPanel left,
                                                                 VariationCellPanel right) {
                                openedLeft.set(left);
                            }
                        });
                VariationCellPanel cell = renderedCell(combo(1), selection, null);

                click(cell, true);
                click(cell, true);

                assertFalse(cell.isSelectedForCompareForTest());
                assertEquals("Comparison cancelled.", status.get());
                assertSame(null, openedLeft.get());
            }
        });
    }

    @Test
    public void shiftClickAfterShortRawPeekPressStillSelectsCell() throws Exception {
        final AtomicReference<String> status = new AtomicReference<String>();

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                VariationComparisonSelection selection = new VariationComparisonSelection(
                        status::set,
                        new VariationComparisonSelection.Opener() {
                            @Override public void openComparison(VariationCellPanel left,
                                                                 VariationCellPanel right) {
                            }
                        });
                VariationCellPanel cell = renderedCell(combo(1), selection, null);
                cell.setRawSource(source());

                press(cell, true);
                release(cell, true);

                assertTrue(cell.isSelectedForCompareForTest());
                assertFalse(cell.isPeekDelayRunningForTest());
                assertEquals("Shift-click a second tile to compare.", status.get());
            }
        });
    }

    @Test
    public void nonShiftClickAcceptsAndClearsPendingSelection() throws Exception {
        final AtomicReference<String> status = new AtomicReference<String>();
        final AtomicReference<ParameterCombo> accepted =
                new AtomicReference<ParameterCombo>();
        final AtomicReference<VariationCellPanel> openedLeft =
                new AtomicReference<VariationCellPanel>();

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                VariationComparisonSelection selection = new VariationComparisonSelection(
                        status::set,
                        new VariationComparisonSelection.Opener() {
                            @Override public void openComparison(VariationCellPanel left,
                                                                 VariationCellPanel right) {
                                openedLeft.set(left);
                            }
                        });
                VariationCellPanel first = renderedCell(combo(1), selection, accepted);
                ParameterCombo secondCombo = combo(2);
                VariationCellPanel second = renderedCell(secondCombo, selection, accepted);

                click(first, true);
                assertTrue(first.isSelectedForCompareForTest());

                click(second, false);

                assertSame(secondCombo, accepted.get());
                assertFalse(first.isSelectedForCompareForTest());
                assertSame(null, openedLeft.get());
            }
        });
    }

    @Test
    public void shiftClickPendingCellDoesNotSelectIt() throws Exception {
        final AtomicReference<String> status = new AtomicReference<String>();

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                VariationComparisonSelection selection = new VariationComparisonSelection(
                        status::set,
                        new VariationComparisonSelection.Opener() {
                            @Override public void openComparison(VariationCellPanel left,
                                                                 VariationCellPanel right) {
                            }
                        });
                VariationCellPanel pending = new VariationCellPanel(combo(1), source(),
                        accepted -> {
                        },
                        (combo, cell) -> selection.handleShiftClick(cell));

                click(pending, true);

                assertFalse(pending.isSelectedForCompareForTest());
                assertEquals("Wait for both tiles to finish rendering.", status.get());
            }
        });
    }

    @Test
    public void setResultFromWorkerThreadMarshalsToEdt() throws Exception {
        final VariationCellPanel cell = new VariationCellPanel(combo(1), source(),
                accepted -> {
                },
                null);
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                cell.setResult(VariationResult.success(combo(1), label(), 1, 1L, null));
            }
        }, "variation-cell-test-worker");

        worker.start();
        worker.join(2000L);
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
            }
        });

        assertTrue(cell.hasCachedLabel());
        assertEquals("1", cell.footerTextForTest());
    }

    @Test
    public void setStateClearsStaleCachedLabel() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                VariationCellPanel cell = renderedCell(combo(1),
                        new VariationComparisonSelection(status -> {
                        }, new VariationComparisonSelection.Opener() {
                            @Override public void openComparison(VariationCellPanel left,
                                                                 VariationCellPanel right) {
                            }
                        }),
                        null);
                assertTrue(cell.hasCachedLabel());

                cell.setState("running");

                assertFalse(cell.hasCachedLabel());
                assertEquals("running", cell.footerTextForTest());
            }
        });
    }

    @Test
    public void failedCellBlocksAcceptButCanStillBeCompared() throws Exception {
        final AtomicReference<ParameterCombo> accepted =
                new AtomicReference<ParameterCombo>();
        final AtomicReference<VariationCellPanel> openedLeft =
                new AtomicReference<VariationCellPanel>();
        final AtomicReference<VariationCellPanel> openedRight =
                new AtomicReference<VariationCellPanel>();

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                VariationComparisonSelection selection = new VariationComparisonSelection(
                        status -> {
                        },
                        new VariationComparisonSelection.Opener() {
                            @Override public void openComparison(VariationCellPanel left,
                                                                 VariationCellPanel right) {
                                openedLeft.set(left);
                                openedRight.set(right);
                            }
                        });
                VariationCellPanel failed = new VariationCellPanel(combo(1), source(),
                        accepted::set,
                        (clickedCombo, clickedCell) -> selection.handleShiftClick(clickedCell));
                failed.setResult(VariationResult.failure(combo(1),
                        new IllegalStateException("cellpose stderr: bad mask")));
                VariationCellPanel success = renderedCell(combo(2), selection, accepted);

                click(failed, false);
                assertSame(null, accepted.get());
                assertEquals("\u26a0", failed.footerTextForTest());
                assertTrue(failed.getToolTipText().contains("cellpose stderr"));

                click(failed, true);
                click(success, true);

                assertSame(failed, openedLeft.get());
                assertSame(success, openedRight.get());
            }
        });
    }

    private static VariationCellPanel renderedCell(
            final ParameterCombo combo,
            final VariationComparisonSelection selection,
            final AtomicReference<ParameterCombo> accepted) {
        VariationCellPanel cell = new VariationCellPanel(combo, source(),
                acceptedCombo -> {
                    selection.clearForAccept();
                    if (accepted != null) {
                        accepted.set(acceptedCombo);
                    }
                },
                (clickedCombo, clickedCell) -> selection.handleShiftClick(clickedCell));
        cell.setLabel(label(), null, 1, 1L);
        return cell;
    }

    private static void click(VariationCellPanel cell, boolean shiftDown) {
        int modifiers = shiftDown ? InputEvent.SHIFT_DOWN_MASK : 0;
        MouseEvent event = new MouseEvent(cell,
                MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),
                modifiers,
                8,
                8,
                1,
                false,
                MouseEvent.BUTTON1);
        MouseListener[] listeners = cell.getMouseListeners();
        for (int i = 0; i < listeners.length; i++) {
            listeners[i].mousePressed(event);
        }
    }

    private static void press(VariationCellPanel cell, boolean shiftDown) {
        int modifiers = shiftDown ? InputEvent.SHIFT_DOWN_MASK : 0;
        MouseEvent event = new MouseEvent(cell,
                MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),
                modifiers,
                8,
                8,
                1,
                false,
                MouseEvent.BUTTON1);
        MouseListener[] listeners = cell.getMouseListeners();
        for (int i = 0; i < listeners.length; i++) {
            listeners[i].mousePressed(event);
        }
    }

    private static void release(VariationCellPanel cell, boolean shiftDown) {
        int modifiers = shiftDown ? InputEvent.SHIFT_DOWN_MASK : 0;
        MouseEvent event = new MouseEvent(cell,
                MouseEvent.MOUSE_RELEASED,
                System.currentTimeMillis(),
                modifiers,
                8,
                8,
                1,
                false,
                MouseEvent.BUTTON1);
        MouseListener[] listeners = cell.getMouseListeners();
        for (int i = 0; i < listeners.length; i++) {
            listeners[i].mouseReleased(event);
        }
    }

    private static ParameterCombo combo(int threshold) {
        return ParameterCombo.builder()
                .put(ParameterId.THRESHOLD, Integer.valueOf(threshold))
                .build();
    }

    private static ImagePlus source() {
        return image("source", 0);
    }

    private static ImagePlus label() {
        return image("label", 1);
    }

    private static ImagePlus image(String title, int value) {
        ByteProcessor processor = new ByteProcessor(4, 4);
        processor.setValue(value);
        processor.fill();
        return new ImagePlus(title, processor);
    }
}
