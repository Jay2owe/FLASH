package flash.pipeline.ui.variations;

import flash.pipeline.decontamination.SpectralDecontaminationConfig;
import flash.pipeline.deconv.engine.Algorithm;
import flash.pipeline.deconv.engine.DeconvSettings;
import flash.pipeline.deconv.psf.PsfModel;
import flash.pipeline.testutil.TestWait;
import flash.pipeline.testutil.UiTestAssumptions;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ShortProcessor;

import org.junit.Test;
import org.junit.After;
import org.junit.Before;

import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Headed Swing lifecycle coverage for the variation modals. A true graphical host is required
 * because constructing a {@link JDialog} in a headless JVM cannot prove native-peer disposal;
 * the release-wide headed matrix remains the final environment proof.
 */
public class VariationModalDisposalTest {

    private static final long LEAK_TIMEOUT_MS = 3000L;

    @Before
    public void resetCoordinatorBeforeTest() {
        VariationCleanupCoordinator.resetForTest();
    }

    @After
    public void resetCoordinatorAfterTest() {
        VariationCleanupCoordinator.resetForTest();
    }

    @Test(timeout = 30000L)
    public void windowClosingCancelsAndDisposesEveryModal() throws Exception {
        exerciseCancellation(CloseRoute.WINDOW_MANAGER);
    }

    @Test(timeout = 30000L)
    public void escapeCancelsAndDisposesEveryModal() throws Exception {
        exerciseCancellation(CloseRoute.ESCAPE);
    }

    @Test(timeout = 30000L)
    public void cancelButtonCancelsAndDisposesEveryModal() throws Exception {
        exerciseCancellation(CloseRoute.CANCEL_BUTTON);
    }

    @Test(timeout = 30000L)
    public void programmaticCancellationIsIdempotentForEveryModal() throws Exception {
        exerciseCancellation(CloseRoute.PROGRAMMATIC);
    }

    @Test(timeout = 30000L)
    public void acceptedResultsAreDeliveredExactlyOnceBeforeDisposal() throws Exception {
        assumeHeadedAndWarmEventQueue();
        assertDeconvolutionAcceptance();
        assertSpectralAcceptance();
        assertMacroAcceptance();
    }

    @Test(timeout = 30000L)
    public void lateAdapterResultsRetryThroughCoordinatorWithoutDoubleClose()
            throws Exception {
        assumeHeadedAndWarmEventQueue();
        final TestWait.ResourceSnapshot resources =
                UiTestAssumptions.snapshotOwnedResources();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                final FailingAdapterClose deconvClose =
                        new FailingAdapterClose();
                final DeconvolutionPreviewAdapter deconvAdapter =
                        new DeconvolutionPreviewAdapter() {
                            @Override public ImagePlus deconvolvePreview(
                                    ImagePlus rawCrop,
                                    DeconvSettings settings) {
                                return rawCrop.duplicate();
                            }

                            @Override public void close(ImagePlus image) {
                                deconvClose.close(image);
                            }
                        };
                final DeconvVariationsDialog deconv =
                        newDeconvolutionDialog(new AtomicInteger(), deconvAdapter);
                deconv.dispose();
                assertLateAdapterResultRetried("deconvolution",
                        new ResultReceiver() {
                            @Override public void accept(VariationResult result) {
                                deconv.handleResultForTest(result);
                            }
                        }, new VariationResult.ImageDisposer() {
                            @Override public void dispose(ImagePlus image) {
                                deconvAdapter.close(image);
                            }
                        }, deconvClose);

                final FailingAdapterClose spectralClose =
                        new FailingAdapterClose();
                final SpectralPreviewAdapter spectralAdapter =
                        new SpectralPreviewAdapter() {
                            @Override public Result decontaminatePreview(
                                    ImagePlus rawCropMultiChannel,
                                    SpectralDecontaminationConfig resolvedConfig) {
                                return new Result(rawCropMultiChannel.duplicate(),
                                        null, null);
                            }

                            @Override public void close(Result result) {
                                spectralClose.close(result == null
                                        ? null : result.mergeRgb());
                            }
                        };
                final SpectralVariationsDialog spectral =
                        newSpectralDialog(new AtomicInteger());
                spectral.dispose();
                assertLateAdapterResultRetried("spectral",
                        new ResultReceiver() {
                            @Override public void accept(VariationResult result) {
                                spectral.handleResultForTest(result);
                            }
                        }, new VariationResult.ImageDisposer() {
                            @Override public void dispose(ImagePlus image) {
                                spectralAdapter.close(
                                        new SpectralPreviewAdapter.Result(
                                                image, null, null));
                            }
                        }, spectralClose);
            }
        });
        resources.assertNoLeaks("late variation adapter results", LEAK_TIMEOUT_MS);
    }

    private static void assertLateAdapterResultRetried(
            String name,
            ResultReceiver receiver,
            VariationResult.ImageDisposer adapterDisposer,
            FailingAdapterClose adapterClose) {
        int pendingBefore = VariationCleanupCoordinator.pendingCountForTest();
        TrackingImage image = new TrackingImage(name + "-late-result");
        VariationResult result = VariationResult.filterSuccess(
                ParameterCombo.builder().build(), image, 1L, new int[256],
                1.0d, 1.0d, adapterDisposer);

        try {
            receiver.accept(result);
            fail("Expected " + name + " adapter close failure.");
        } catch (RuntimeException expected) {
            assertSame(adapterClose.failure, expected);
        }
        assertEquals(8, adapterClose.calls);
        assertEquals(0, adapterClose.successfulCloses);
        assertEquals(0, image.closeCalls);
        assertEquals(1, result.pendingTransferredImages().length);
        assertEquals(pendingBefore + 1,
                VariationCleanupCoordinator.pendingCountForTest());

        assertNull(VariationCleanupCoordinator.drainNowForTest());
        assertEquals(9, adapterClose.calls);
        assertEquals(1, adapterClose.successfulCloses);
        assertEquals(1, image.closeCalls);
        assertEquals(1, image.flushCalls);
        assertEquals(0, result.pendingTransferredImages().length);
        assertEquals(pendingBefore,
                VariationCleanupCoordinator.pendingCountForTest());

        assertNull(VariationCleanupCoordinator.drainNowForTest());
        assertEquals(9, adapterClose.calls);
        assertEquals(1, image.closeCalls);
    }

    private static void exerciseCancellation(CloseRoute route) throws Exception {
        assumeHeadedAndWarmEventQueue();
        List<ModalFactory> factories = factories();
        for (int i = 0; i < factories.size(); i++) {
            exerciseCancellation(factories.get(i), route);
        }
    }

    private static void exerciseCancellation(final ModalFactory factory,
                                             final CloseRoute route) throws Exception {
        final TestWait.ResourceSnapshot resources =
                UiTestAssumptions.snapshotOwnedResources();
        final AtomicInteger callbacks = new AtomicInteger();
        final AtomicReference<ModalHarness> harnessRef =
                new AtomicReference<ModalHarness>();
        final AtomicReference<Action> escapeActionRef = new AtomicReference<Action>();

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                ModalHarness harness = factory.create(callbacks);
                harness.window().pack();
                assertTrue(factory.name() + " should have a native peer after pack",
                        harness.window().isDisplayable());
                escapeActionRef.set(escapeAction(harness.window()));
                harnessRef.set(harness);
            }
        });

        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    ModalHarness harness = harnessRef.get();
                    route.close(harness, escapeActionRef.get());
                    assertFalse(factory.name() + " should dispose its native peer",
                            harness.window().isDisplayable());
                    harness.assertCancelledAndDetached();
                    assertEquals(factory.name() + " cancellation must not accept a result",
                            0, callbacks.get());

                    // Re-deliver every terminal event. None may recreate the peer, callback,
                    // listener graph, or owned resources.
                    harness.window().dispatchEvent(new WindowEvent(harness.window(),
                            WindowEvent.WINDOW_CLOSING));
                    escapeActionRef.get().actionPerformed(new ActionEvent(harness.window(),
                            ActionEvent.ACTION_PERFORMED, "repeat-escape"));
                    harness.cancelButton().doClick();
                    harness.window().dispose();
                    assertFalse(harness.window().isDisplayable());
                    harness.assertCancelledAndDetached();
                    assertEquals(0, callbacks.get());
                }
            });
        } finally {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    ModalHarness harness = harnessRef.get();
                    if (harness != null) {
                        harness.window().dispose();
                    }
                }
            });
        }
        resources.assertNoLeaks(factory.name() + " " + route, LEAK_TIMEOUT_MS);
    }

    private static void assertDeconvolutionAcceptance() throws Exception {
        final TestWait.ResourceSnapshot resources =
                UiTestAssumptions.snapshotOwnedResources();
        final AtomicInteger callbacks = new AtomicInteger();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                DeconvVariationsDialog dialog = newDeconvolutionDialog(callbacks);
                ParameterCombo combo = ParameterCombo.builder()
                        .put(DeconvParameterId.ITERATIONS, Integer.valueOf(20))
                        .build();
                dialog.commitForTest(combo);
                dialog.commitForTest(combo);
                dialog.dispose();
                assertEquals(1, callbacks.get());
                assertFalse(dialog.isDisplayable());
                assertDeconvolutionDetached(dialog);
            }
        });
        resources.assertNoLeaks("accepted deconvolution variations", LEAK_TIMEOUT_MS);
    }

    private static void assertSpectralAcceptance() throws Exception {
        final TestWait.ResourceSnapshot resources =
                UiTestAssumptions.snapshotOwnedResources();
        final AtomicInteger callbacks = new AtomicInteger();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                SpectralVariationsDialog dialog = newSpectralDialog(callbacks);
                ParameterCombo combo = ParameterCombo.builder()
                        .put(SpectralParameterId.STRENGTH, Double.valueOf(0.5d))
                        .build();
                dialog.commitForTest(combo);
                dialog.commitForTest(combo);
                dialog.dispose();
                assertEquals(1, callbacks.get());
                assertFalse(dialog.isDisplayable());
                assertSpectralDetached(dialog);
            }
        });
        resources.assertNoLeaks("accepted spectral variations", LEAK_TIMEOUT_MS);
    }

    private static void assertMacroAcceptance() throws Exception {
        final TestWait.ResourceSnapshot resources =
                UiTestAssumptions.snapshotOwnedResources();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                MacroVariationPickerDialog picker = newMacroPicker();
                picker.dialogForTest().pack();
                picker.listForTest().setSelectedIndex(0);
                MacroVariation expected = picker.listForTest().getSelectedValue();
                picker.addButtonForTest().doClick();
                picker.addButtonForTest().doClick();
                picker.dialogForTest().dispose();
                assertEquals(Collections.singletonList(expected), picker.acceptedForTest());
                assertFalse(picker.dialogForTest().isDisplayable());
                assertMacroDetached(picker);
            }
        });
        resources.assertNoLeaks("accepted macro variation", LEAK_TIMEOUT_MS);
    }

    private static List<ModalFactory> factories() {
        return Arrays.<ModalFactory>asList(
                new ModalFactory() {
                    @Override public String name() {
                        return "deconvolution variations";
                    }

                    @Override public ModalHarness create(AtomicInteger callbacks) {
                        final DeconvVariationsDialog dialog =
                                newDeconvolutionDialog(callbacks);
                        return new ModalHarness() {
                            @Override public Window window() {
                                return dialog;
                            }

                            @Override public JButton cancelButton() {
                                return dialog.cancelButtonForTest();
                            }

                            @Override public void assertCancelledAndDetached() {
                                assertDeconvolutionDetached(dialog);
                            }
                        };
                    }
                },
                new ModalFactory() {
                    @Override public String name() {
                        return "spectral variations";
                    }

                    @Override public ModalHarness create(AtomicInteger callbacks) {
                        final SpectralVariationsDialog dialog = newSpectralDialog(callbacks);
                        return new ModalHarness() {
                            @Override public Window window() {
                                return dialog;
                            }

                            @Override public JButton cancelButton() {
                                return dialog.cancelButtonForTest();
                            }

                            @Override public void assertCancelledAndDetached() {
                                assertSpectralDetached(dialog);
                            }
                        };
                    }
                },
                new ModalFactory() {
                    @Override public String name() {
                        return "macro variation picker";
                    }

                    @Override public ModalHarness create(AtomicInteger callbacks) {
                        final MacroVariationPickerDialog picker = newMacroPicker();
                        return new ModalHarness() {
                            @Override public Window window() {
                                return picker.dialogForTest();
                            }

                            @Override public JButton cancelButton() {
                                return picker.cancelButtonForTest();
                            }

                            @Override public void assertCancelledAndDetached() {
                                assertTrue(picker.acceptedForTest().isEmpty());
                                assertMacroDetached(picker);
                            }
                        };
                    }
                });
    }

    private static DeconvVariationsDialog newDeconvolutionDialog(
            final AtomicInteger callbacks) {
        return newDeconvolutionDialog(callbacks,
                new DeconvolutionPreviewAdapter() {
                    @Override public ImagePlus deconvolvePreview(ImagePlus rawCrop,
                                                                 DeconvSettings settings) {
                        return rawCrop.duplicate();
                    }

                    @Override public void close(ImagePlus image) {
                        if (image != null) {
                            image.close();
                        }
                    }
                });
    }

    private static DeconvVariationsDialog newDeconvolutionDialog(
            final AtomicInteger callbacks,
            DeconvolutionPreviewAdapter adapter) {
        return new DeconvVariationsDialog(null, "DAPI", singleChannelImage("deconv-raw"),
                new DeconvSettings("DL2", Algorithm.RL, PsfModel.GIBSON_LANNI, 15, 0.01d),
                adapter,
                Collections.singletonList("DL2"),
                Collections.singletonList(Algorithm.RL.name()),
                Collections.singletonList(PsfModel.GIBSON_LANNI.name()),
                new Consumer<DeconvSettings>() {
                    @Override public void accept(DeconvSettings settings) {
                        callbacks.incrementAndGet();
                    }
                });
    }

    private static SpectralVariationsDialog newSpectralDialog(
            final AtomicInteger callbacks) {
        return new SpectralVariationsDialog(null, "DAPI", spectralImage(), spectralConfig(),
                new Consumer<SpectralDecontaminationConfig>() {
                    @Override public void accept(SpectralDecontaminationConfig config) {
                        callbacks.incrementAndGet();
                    }
                });
    }

    private static MacroVariationPickerDialog newMacroPicker() {
        return new MacroVariationPickerDialog(null, MacroVariationCatalog.empty());
    }

    private static void assertDeconvolutionDetached(DeconvVariationsDialog dialog) {
        assertEquals(0, dialog.getWindowListeners().length);
        assertEquals(0, dialog.cancelButtonForTest().getActionListeners().length);
        assertEquals(0, dialog.generateButtonForTest().getActionListeners().length);
        assertNull(dialog.gridWindowForTest());
        assertTrue(dialog.cellsForTest().isEmpty());
        assertTrue(dialog.editorListenerDetachedForTest());
        assertEscapeBindingRemoved(dialog);
    }

    private static void assertSpectralDetached(SpectralVariationsDialog dialog) {
        assertEquals(0, dialog.getWindowListeners().length);
        assertEquals(0, dialog.cancelButtonForTest().getActionListeners().length);
        assertEquals(0, dialog.generateButtonForTest().getActionListeners().length);
        assertNull(dialog.gridWindowForTest());
        assertTrue(dialog.cellsForTest().isEmpty());
        assertTrue(dialog.editorListenerDetachedForTest());
        assertNull("owned before-merge pixels should be flushed",
                dialog.beforeMergeForTest().getProcessor());
        assertEscapeBindingRemoved(dialog);
    }

    private static void assertMacroDetached(MacroVariationPickerDialog picker) {
        assertEquals(0, picker.dialogForTest().getWindowListeners().length);
        assertEquals(0, picker.cancelButtonForTest().getActionListeners().length);
        assertEquals(0, picker.addButtonForTest().getActionListeners().length);
        assertEquals(0, picker.listForTest().getListSelectionListeners().length);
        assertEscapeBindingRemoved(picker.dialogForTest());
    }

    private static void assertEscapeBindingRemoved(Window window) {
        JDialog dialog = (JDialog) window;
        KeyStroke escape = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
        assertNull(dialog.getRootPane()
                .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(escape));
    }

    private static Action escapeAction(Window window) {
        JDialog dialog = (JDialog) window;
        KeyStroke escape = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
        Object key = dialog.getRootPane()
                .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(escape);
        assertNotNull("Escape must be bound", key);
        Action action = dialog.getRootPane().getActionMap().get(key);
        assertNotNull("Escape action must be installed", action);
        return action;
    }

    private static void assumeHeadedAndWarmEventQueue() throws Exception {
        UiTestAssumptions.assumeDisplayAvailable();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                // Ensure the shared event-dispatch thread predates leak snapshots.
            }
        });
    }

    private static ImagePlus singleChannelImage(String title) {
        return new ImagePlus(title,
                new ShortProcessor(4, 4, new short[16], null));
    }

    private static ImagePlus spectralImage() {
        ImageStack stack = new ImageStack(4, 4);
        stack.addSlice(new ShortProcessor(4, 4, filledPixels(1000), null));
        stack.addSlice(new ShortProcessor(4, 4, filledPixels(500), null));
        stack.addSlice(new ShortProcessor(4, 4, filledPixels(100), null));
        ImagePlus image = new ImagePlus("spectral-raw", stack);
        image.setDimensions(3, 1, 1);
        return image;
    }

    private static short[] filledPixels(int value) {
        short[] pixels = new short[16];
        Arrays.fill(pixels, (short) value);
        return pixels;
    }

    private static SpectralDecontaminationConfig spectralConfig() {
        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(0);
        config.setBleedThroughChannelIndexes(Collections.singletonList(Integer.valueOf(1)));
        config.setAutofluorescenceChannelIndexes(Collections.singletonList(Integer.valueOf(2)));
        return config;
    }

    private interface ModalFactory {
        String name();
        ModalHarness create(AtomicInteger callbacks);
    }

    private interface ModalHarness {
        Window window();
        JButton cancelButton();
        void assertCancelledAndDetached();
    }

    private interface ResultReceiver {
        void accept(VariationResult result);
    }

    private static final class FailingAdapterClose {
        final RuntimeException failure =
                new RuntimeException("adapter close failed before close");
        int calls;
        int successfulCloses;

        void close(ImagePlus image) {
            calls++;
            if (calls <= 8) {
                throw failure;
            }
            image.close();
            image.flush();
            successfulCloses++;
        }
    }

    private static final class TrackingImage extends ImagePlus {
        int closeCalls;
        int flushCalls;

        TrackingImage(String title) {
            super(title, new ShortProcessor(1, 1, new short[1], null));
        }

        @Override public void close() {
            closeCalls++;
            super.close();
        }

        @Override public void flush() {
            flushCalls++;
            super.flush();
        }
    }

    private enum CloseRoute {
        WINDOW_MANAGER {
            @Override void close(ModalHarness harness, Action escapeAction) {
                harness.window().dispatchEvent(new WindowEvent(harness.window(),
                        WindowEvent.WINDOW_CLOSING));
            }
        },
        ESCAPE {
            @Override void close(ModalHarness harness, Action escapeAction) {
                escapeAction.actionPerformed(new ActionEvent(harness.window(),
                        ActionEvent.ACTION_PERFORMED, "escape"));
            }
        },
        CANCEL_BUTTON {
            @Override void close(ModalHarness harness, Action escapeAction) {
                harness.cancelButton().doClick();
            }
        },
        PROGRAMMATIC {
            @Override void close(ModalHarness harness, Action escapeAction) {
                harness.window().dispose();
            }
        };

        abstract void close(ModalHarness harness, Action escapeAction);
    }
}
