package flash.pipeline.ui.variations;

import flash.pipeline.deconv.engine.Algorithm;
import flash.pipeline.deconv.engine.DeconvSettings;
import flash.pipeline.deconv.psf.PsfModel;
import flash.pipeline.testutil.UiTestAssumptions;

import ij.ImagePlus;
import ij.process.ByteProcessor;

import org.junit.Test;

import javax.swing.SwingUtilities;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DeconvVariationsDialogOverlayToggleTest {

    @Test(timeout = 15000)
    public void zSliderCanMoveBeforePreviewResultsComplete() throws Exception {
        UiTestAssumptions.assumeInteractiveUiTestsEnabled();
        final BlockingDeconvAdapter adapter = new BlockingDeconvAdapter();
        final AtomicReference<DeconvVariationsDialog> ref =
                new AtomicReference<DeconvVariationsDialog>();

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                DeconvVariationsDialog dialog = new DeconvVariationsDialog(null,
                        "DAPI",
                        stackImage("raw", 5),
                        base(),
                        adapter,
                        Arrays.asList("DL2"),
                        Arrays.asList(Algorithm.RL.name()),
                        Arrays.asList(PsfModel.GIBSON_LANNI.name()),
                        new Consumer<DeconvSettings>() {
                            @Override public void accept(DeconvSettings settings) {
                            }
                        });
                configureSweep(dialog.editorForTest());
                dialog.generateButtonForTest().doClick();
                ref.set(dialog);
            }
        });

        final DeconvVariationsDialog dialog = ref.get();
        try {
            assertTrue(adapter.awaitStarted());
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    assertEquals(0, dialog.completedCountForTest());
                    assertEquals(5, dialog.gridWindowForTest()
                            .zSliderForTest().getMaximum());
                    dialog.gridWindowForTest().zSliderForTest().setValue(4);
                    assertEquals(4, dialog.gridWindowForTest()
                            .zSliderForTest().getValue());
                    assertEquals(4, dialog.gridWindowForTest()
                            .controllerForTest().currentSlice());
                    assertEquals("4 / 5", dialog.gridWindowForTest()
                            .zSliceLabelForTest().getText());
                }
            });
        } finally {
            adapter.release();
            if (dialog != null) {
                dialog.waitForDoneForTest(5000L);
            }
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    if (ref.get() != null) {
                        ref.get().dispose();
                    }
                }
            });
        }
    }

    @Test(timeout = 15000)
    public void toggleAppliesToExistingCellsAndNewRuns() throws Exception {
        UiTestAssumptions.assumeInteractiveUiTestsEnabled();
        final AtomicReference<DeconvVariationsDialog> ref =
                new AtomicReference<DeconvVariationsDialog>();

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                DeconvVariationsDialog dialog = new DeconvVariationsDialog(null,
                        "DAPI",
                        image("raw"),
                        base(),
                        new SyntheticDeconvAdapter(),
                        Arrays.asList("DL2"),
                        Arrays.asList(Algorithm.RL.name()),
                        Arrays.asList(PsfModel.GIBSON_LANNI.name()),
                        new Consumer<DeconvSettings>() {
                            @Override public void accept(DeconvSettings settings) {
                            }
                        });
                configureSweep(dialog.editorForTest());
                dialog.generateButtonForTest().doClick();
                ref.set(dialog);
            }
        });

        final DeconvVariationsDialog dialog = ref.get();
        try {
            dialog.waitForDoneForTest(5000L);
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    assertEquals(2, dialog.completedCountForTest());
                    assertEquals(0, dialog.failedCountForTest());
                    assertFalse(dialog.gridWindowForTest()
                            .otsuOverlayCheckBoxForTest().isSelected());
                    assertCellsHaveMode(dialog.cellsForTest(),
                            VariationCellPanel.OverlayMode.NONE);
                    assertPlainImages(dialog);

                    dialog.gridWindowForTest().otsuOverlayCheckBoxForTest().doClick();
                    assertTrue(dialog.gridWindowForTest()
                            .otsuOverlayCheckBoxForTest().isSelected());
                    assertCellsHaveMode(dialog.cellsForTest(),
                            VariationCellPanel.OverlayMode.OTSU_MASK);
                    assertOverlayImages(dialog);

                    dialog.gridWindowForTest().otsuOverlayCheckBoxForTest().doClick();
                    assertFalse(dialog.gridWindowForTest()
                            .otsuOverlayCheckBoxForTest().isSelected());
                    assertCellsHaveMode(dialog.cellsForTest(),
                            VariationCellPanel.OverlayMode.NONE);
                    assertPlainImages(dialog);

                    dialog.gridWindowForTest().otsuOverlayCheckBoxForTest().doClick();
                    dialog.generateButtonForTest().doClick();
                    assertCellsHaveMode(dialog.cellsForTest(),
                            VariationCellPanel.OverlayMode.OTSU_MASK);
                }
            });

            dialog.waitForDoneForTest(5000L);
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    assertEquals(2, dialog.completedCountForTest());
                    assertEquals(0, dialog.failedCountForTest());
                    assertTrue(dialog.gridWindowForTest()
                            .otsuOverlayCheckBoxForTest().isSelected());
                    assertCellsHaveMode(dialog.cellsForTest(),
                            VariationCellPanel.OverlayMode.OTSU_MASK);
                    assertOverlayImages(dialog);
                }
            });
        } finally {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    if (ref.get() != null) {
                        ref.get().dispose();
                    }
                }
            });
        }
    }

    private static void configureSweep(ParameterSweepEditor editor) {
        editor.setSweptForTest(DeconvParameterId.ITERATIONS, true);
        editor.setParameterValuesForTest(DeconvParameterId.ITERATIONS,
                Arrays.<Object>asList(Integer.valueOf(10), Integer.valueOf(20)));
    }

    private static void assertCellsHaveMode(List<VariationCellPanel> cells,
                                            VariationCellPanel.OverlayMode mode) {
        assertEquals(3, cells.size());
        for (int i = 0; i < cells.size(); i++) {
            VariationCellPanel cell = cells.get(i);
            if (cell.isBaselineForTest()) {
                assertEquals(VariationCellPanel.OverlayMode.NONE,
                        cell.overlayModeForTest());
            } else {
                assertEquals(mode, cell.overlayModeForTest());
            }
        }
    }

    private static void assertOverlayImages(DeconvVariationsDialog dialog) {
        List<VariationCellPanel> cells = dialog.cellsForTest();
        for (int i = 0; i < cells.size(); i++) {
            VariationCellPanel cell = cells.get(i);
            if (cell.isBaselineForTest()) {
                continue;
            }
            assertNotNull(cell.currentPreviewImageForTest());
            assertEquals("Threshold red overlay",
                    cell.currentPreviewImageForTest().getTitle());
            assertTrue("Otsu threshold should exclude the background peak.",
                    cell.cachedOtsuLowerForTest() > 20.0d);
        }
    }

    private static void assertPlainImages(DeconvVariationsDialog dialog) {
        List<VariationCellPanel> cells = dialog.cellsForTest();
        for (int i = 0; i < cells.size(); i++) {
            VariationCellPanel cell = cells.get(i);
            if (cell.isBaselineForTest()) {
                continue;
            }
            assertNotNull(cell.currentPreviewImageForTest());
            assertFalse("Threshold red overlay".equals(
                    cell.currentPreviewImageForTest().getTitle()));
        }
    }

    private static DeconvSettings base() {
        return new DeconvSettings("DL2", Algorithm.RL,
                PsfModel.GIBSON_LANNI, 15, 0.01d);
    }

    private static ImagePlus image(String title) {
        ByteProcessor processor = new ByteProcessor(32, 32);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                processor.set(x, y,
                        x >= 10 && x < 24 && y >= 10 && y < 24 ? 220 : 20);
            }
        }
        return new ImagePlus(title, processor);
    }

    private static ImagePlus stackImage(String title, int slices) {
        ij.ImageStack stack = new ij.ImageStack(32, 32);
        for (int z = 0; z < slices; z++) {
            stack.addSlice("z" + (z + 1), image(title + "-z" + z).getProcessor());
        }
        ImagePlus image = new ImagePlus(title, stack);
        image.setDimensions(1, slices, 1);
        return image;
    }

    private static final class SyntheticDeconvAdapter implements DeconvolutionPreviewAdapter {
        @Override
        public ImagePlus deconvolvePreview(ImagePlus rawCrop, DeconvSettings settings) {
            return image("deconv-" + settings.iterations());
        }

        @Override
        public void close(ImagePlus image) {
        }
    }

    private static final class BlockingDeconvAdapter implements DeconvolutionPreviewAdapter {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        boolean awaitStarted() throws InterruptedException {
            return started.await(5L, TimeUnit.SECONDS);
        }

        void release() {
            release.countDown();
        }

        @Override
        public ImagePlus deconvolvePreview(ImagePlus rawCrop, DeconvSettings settings) {
            started.countDown();
            try {
                release.await(5L, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return stackImage("deconv-" + settings.iterations(), 5);
        }

        @Override
        public void close(ImagePlus image) {
        }
    }
}
