package flash.pipeline.ui.variations;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.testutil.UiTestAssumptions;
import flash.pipeline.ui.config.ConfigQcContext;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class VariationsDialogTest {

    @Test
    public void titleBarCloseDisposesWrapperAndRejectsLateResultExactlyOnce()
            throws Exception {
        UiTestAssumptions.assumeDisplayAvailable();
        final TrackingImage installed = new TrackingImage("title-installed");
        final TrackingImage late = new TrackingImage("title-late");

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                VariationsDialog dialog = new VariationsDialog(null, context(), null);
                VariationCellPanel cell = new VariationCellPanel(
                        ParameterCombo.builder().build(), stack("cell-source", 1),
                        null, null);
                cell.setResult(VariationResult.success(cell.combo(), installed,
                        1, 1L, null));
                dialog.addCellForTest(cell);
                Window window = dialog.getWindow();
                window.pack();
                window.dispatchEvent(new WindowEvent(window,
                        WindowEvent.WINDOW_CLOSING));
                assertFalse(window.isDisplayable());
                cell.setResult(VariationResult.success(cell.combo(), late,
                        1, 1L, null));
                window.dispatchEvent(new WindowEvent(window,
                        WindowEvent.WINDOW_CLOSING));
                dialog.dispose();
            }
        });

        assertEquals(1, installed.closeCalls);
        assertEquals(1, installed.flushCalls);
        assertEquals(1, late.closeCalls);
        assertEquals(1, late.flushCalls);
    }

    @Test
    public void transientCellCleanupStillClosesBackingWindow()
            throws Exception {
        UiTestAssumptions.assumeDisplayAvailable();
        final RuntimeException cleanupFailure =
                new RuntimeException("transient modal cleanup");
        final FailingOnceDisposer disposer =
                new FailingOnceDisposer(cleanupFailure);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                VariationsDialog dialog = new VariationsDialog(null, context(), null);
                VariationCellPanel cell = new VariationCellPanel(
                        ParameterCombo.builder().build(), stack("failure-source", 1),
                        null, null);
                VariationResult result = VariationResult.filterSuccess(cell.combo(),
                        new TrackingImage("failure-result"), 1L, new int[256],
                        1.0d, 1.0d, disposer);
                cell.setResult(result);
                dialog.addCellForTest(cell);
                Window window = dialog.getWindow();
                window.pack();
                try {
                    dialog.dispose();
                    fail("Expected reported transient cleanup failure.");
                } catch (RuntimeException expected) {
                    assertSame(cleanupFailure, expected);
                }
                assertFalse("cleanup failure must not strand the modal peer",
                        window.isDisplayable());
                dialog.dispose();
            }
        });

        assertEquals(2, disposer.calls);
        assertEquals(1, disposer.successfulCloses);
    }

    @Test
    public void fatalCellCleanupReleasesDisposeGuardForExplicitRecovery()
            throws Exception {
        UiTestAssumptions.assumeDisplayAvailable();
        VariationCleanupCoordinator.resetForTest();
        final ThreadDeath fatal = new ThreadDeath();
        final FatalOnceDisposer disposer = new FatalOnceDisposer(fatal);
        final VariationsDialog[] holder = new VariationsDialog[1];
        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    VariationsDialog dialog = new VariationsDialog(
                            null, context(), null);
                    holder[0] = dialog;
                    VariationCellPanel cell = new VariationCellPanel(
                            ParameterCombo.builder().build(),
                            stack("fatal-source", 1), null, null);
                    cell.setResult(VariationResult.filterSuccess(cell.combo(),
                            new TrackingImage("fatal-result"), 1L, new int[256],
                            1.0d, 1.0d, disposer));
                    dialog.addCellForTest(cell);
                    Window window = dialog.getWindow();
                    window.pack();

                    try {
                        dialog.dispose();
                        fail("Expected VM-fatal cleanup failure.");
                    } catch (ThreadDeath expected) {
                        assertSame(fatal, expected);
                    }
                    assertEquals(1, disposer.calls);
                }
            });
            Thread.sleep(400L);
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    assertEquals("fatal cleanup must remain paused until explicit recovery",
                            1, disposer.calls);
                    Window window = holder[0].getWindow();
                    holder[0].dispose();
                    assertFalse(window.isDisplayable());
                }
            });
            assertEquals(2, disposer.calls);
            assertEquals(1, disposer.successfulCloses);
        } finally {
            VariationCleanupCoordinator.resetForTest();
        }
    }

    private static final class EchoStrategy implements VariationStrategy {

        @Override
        public void dispatch(ParameterSweep sweep,
                             Consumer<VariationResult> publisher,
                             BooleanSupplier cancelCheck) {
            List<ParameterCombo> combos = sweep.combos();
            for (int i = 0; i < combos.size(); i++) {
                if (cancelCheck != null && cancelCheck.getAsBoolean()) {
                    return;
                }
                ParameterCombo combo = combos.get(i);
                long started = System.currentTimeMillis();
                int syntheticCount = 10
                        + Math.abs(combo.toCanonicalJson().hashCode() % 90);
                long duration = Math.max(1L,
                        System.currentTimeMillis() - started);
                publisher.accept(VariationResult.success(combo, null,
                        syntheticCount, duration, null));
            }
        }
    }

    private static final class NoopStrategy implements VariationStrategy {

        private final int resultCount;

        private NoopStrategy(int resultCount) {
            this.resultCount = Math.max(0, resultCount);
        }

        @Override
        public void dispatch(ParameterSweep sweep,
                             Consumer<VariationResult> publisher,
                             BooleanSupplier cancelCheck) {
            for (int i = 0; i < resultCount; i++) {
                if (cancelCheck != null && cancelCheck.getAsBoolean()) {
                    return;
                }
                ParameterCombo combo = comboForIndex(sweep, i);
                ImagePlus label = new ImagePlus("noop-" + i,
                        new ByteProcessor(1, 1));
                publisher.accept(VariationResult.success(combo, label,
                        i, 0L, null));
            }
        }

        private static ParameterCombo comboForIndex(ParameterSweep sweep,
                                                    int index) {
            if (sweep != null) {
                List<ParameterCombo> combos = sweep.combos();
                if (!combos.isEmpty()) {
                    return combos.get(index % combos.size());
                }
            }
            return ParameterCombo.builder().build();
        }
    }

    @Test(timeout = 10000)
    public void constructStartAndCancelCycleCompletesPlaceholderSweep() throws Exception {
        UiTestAssumptions.assumeInteractiveUiTestsEnabled();
        final VariationsDialog[] holder = new VariationsDialog[1];
        final AtomicReference<ParameterCombo> accepted =
                new AtomicReference<ParameterCombo>();

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                VariationsDialog dialog = new VariationsDialog(null,
                        context(),
                        new java.util.function.Consumer<ParameterCombo>() {
                            @Override public void accept(ParameterCombo combo) {
                                accepted.set(combo);
                            }
                        });
                dialog.setSweepForTest(twoAxisSweep());
                dialog.setStrategyForTest(new EchoStrategy());
                dialog.start();
                holder[0] = dialog;
            }
        });

        holder[0].waitForDoneForTest(5000L);

        assertEquals(7, holder[0].cellCountForTest());
        assertEquals(6, holder[0].completedCountForTest());
        assertEquals(7, holder[0].gridWindowForTest().cellsForTest().size());

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                holder[0].setGlobalZForTest(3);
                java.util.List<VariationCellPanel> cells =
                        holder[0].gridWindowForTest().cellsForTest();
                assertTrue(cells.get(0).isBaselineForTest());
                assertEquals("Original", cells.get(0).footerTextForTest());
                for (int i = 0; i < cells.size(); i++) {
                    assertEquals(3, cells.get(i).currentZForTest());
                }
                for (int i = 1; i < cells.size(); i++) {
                    assertTrue(cells.get(i).cachedLabelForTest() != null);
                }
                // Click selects the tile; the toolbar "Pick selected" button
                // commits it.
                cells.get(1).clickForTest(false);
                assertTrue(holder[0].gridWindowForTest()
                        .pickSelectedButtonForTest().isEnabled());
                holder[0].gridWindowForTest()
                        .pickSelectedButtonForTest().doClick();
            }
        });
        assertTrue(accepted.get() != null);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                holder[0].cancelForTest();
                holder[0].dispose();
            }
        });
    }

    @Test(timeout = 10000)
    public void objectGridLutToggleUsesChannelColourThenGrey() throws Exception {
        UiTestAssumptions.assumeInteractiveUiTestsEnabled();
        final VariationsDialog[] holder = new VariationsDialog[1];

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                VariationsDialog dialog = new VariationsDialog(null,
                        colouredContext("DAPI", "Red"),
                        new java.util.function.Consumer<ParameterCombo>() {
                            @Override public void accept(ParameterCombo combo) {
                            }
                        });
                dialog.setSweepForTest(twoAxisSweep());
                dialog.setStrategyForTest(new EchoStrategy());
                dialog.start();
                holder[0] = dialog;
            }
        });

        holder[0].waitForDoneForTest(5000L);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                VariationCellPanel cell = holder[0].gridWindowForTest()
                        .cellsForTest().get(1);
                // Default channel LUT must be the channel COLOUR ("Red"), not the
                // marker name ("DAPI") — the latter made both modes render grey.
                assertEquals("Red",
                        cell.objectDisplaySettingsForTest().effectiveLutName());

                holder[0].gridWindowForTest().lutToggleButtonForTest().doClick();
                assertEquals("Grays",
                        cell.objectDisplaySettingsForTest().effectiveLutName());

                holder[0].gridWindowForTest().lutToggleButtonForTest().doClick();
                assertEquals("Red",
                        cell.objectDisplaySettingsForTest().effectiveLutName());

                holder[0].dispose();
            }
        });
    }

    private static VariationEngineContext context() {
        ImagePlus source = stack("synthetic", 10);
        return context(source, source);
    }

    private static VariationEngineContext colouredContext(String channelName, String lutColour) {
        ImagePlus source = stack(channelName, 10);
        File bin = new File("target/variation-dialog-test-bin-"
                + channelName.replaceAll("[^A-Za-z0-9_.-]", "_") + "-" + lutColour);
        BinConfig config = new BinConfig();
        config.channelNames.add(channelName);
        config.channelColors.add(lutColour);
        ConfigQcContext qc = ConfigQcContext.fromImages(new File("."), bin, config,
                Collections.singletonList(source),
                Collections.singletonList(channelName),
                0);
        ParameterCombo base = ParameterCombo.builder()
                .put(ParameterId.THRESHOLD, Integer.valueOf(100))
                .put(ParameterId.MIN_SIZE, Integer.valueOf(50))
                .put(ParameterId.MAX_SIZE, Integer.valueOf(500))
                .build();
        return VariationEngineContext.forClassical(channelName, source, source,
                qc, base, null);
    }

    private static VariationEngineContext context(ImagePlus rawSource,
                                                  ImagePlus filteredSource) {
        File bin = new File("target/variation-dialog-test-bin-"
                + rawSource.getTitle().replaceAll("[^A-Za-z0-9_.-]", "_"));
        ConfigQcContext config = ConfigQcContext.fromImages(new File("."), bin, null,
                Collections.singletonList(rawSource),
                Collections.singletonList("DAPI"),
                0);
        ParameterCombo base = ParameterCombo.builder()
                .put(ParameterId.THRESHOLD, Integer.valueOf(100))
                .put(ParameterId.MIN_SIZE, Integer.valueOf(50))
                .put(ParameterId.MAX_SIZE, Integer.valueOf(500))
                .build();
        return VariationEngineContext.forClassical("DAPI", rawSource, filteredSource,
                config, base, null);
    }

    private static VariationsDialog startedDialog(
            final VariationEngineContext context,
            final ParameterSweep sweep) throws Exception {
        UiTestAssumptions.assumeInteractiveUiTestsEnabled();
        final AtomicReference<VariationsDialog> ref =
                new AtomicReference<VariationsDialog>();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                VariationsDialog dialog = new VariationsDialog(null,
                        context,
                        new java.util.function.Consumer<ParameterCombo>() {
                            @Override public void accept(ParameterCombo combo) {
                            }
                        });
                dialog.setSweepForTest(sweep);
                dialog.setStrategyForTest(new NoopStrategy(1));
                dialog.start();
                ref.set(dialog);
            }
        });
        VariationsDialog dialog = ref.get();
        dialog.waitForDoneForTest(5000L);
        return dialog;
    }

    private static void dispose(final VariationsDialog dialog) throws Exception {
        if (dialog == null) {
            return;
        }
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                dialog.dispose();
            }
        });
    }

    private static ParameterSweep twoAxisSweep() {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.THRESHOLD, ParameterValueList.ofInts(80, 100, 120));
        values.put(ParameterId.MIN_SIZE, ParameterValueList.ofInts(20, 40));
        values.put(ParameterId.MAX_SIZE, ParameterValueList.ofInts(500));
        return new ParameterSweep(ParameterSweep.Method.CLASSICAL, values,
                CropSpec.centre256(), "DAPI", "hash");
    }

    private static ParameterSweep oneCellSweep() {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.THRESHOLD, ParameterValueList.ofInts(100));
        values.put(ParameterId.MIN_SIZE, ParameterValueList.ofInts(50));
        values.put(ParameterId.MAX_SIZE, ParameterValueList.ofInts(500));
        return new ParameterSweep(ParameterSweep.Method.CLASSICAL, values,
                CropSpec.centre256(), "DAPI", "hash");
    }

    private static ImagePlus stack(String title, int slices) {
        return stack(title, slices, 16, 16);
    }

    private static final class TrackingImage extends ImagePlus {
        int closeCalls;
        int flushCalls;

        TrackingImage(String title) {
            super(title, new ByteProcessor(1, 1));
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

    private static final class FailingOnceDisposer
            implements VariationResult.ImageDisposer {
        private final RuntimeException failure;
        int calls;
        int successfulCloses;

        FailingOnceDisposer(RuntimeException failure) {
            this.failure = failure;
        }

        @Override public void dispose(ImagePlus image) {
            calls++;
            if (calls == 1) {
                throw failure;
            }
            image.close();
            successfulCloses++;
        }
    }

    private static final class FatalOnceDisposer
            implements VariationResult.ImageDisposer {
        private final ThreadDeath fatal;
        int calls;
        int successfulCloses;

        FatalOnceDisposer(ThreadDeath fatal) {
            this.fatal = fatal;
        }

        @Override public void dispose(ImagePlus image) {
            calls++;
            if (calls == 1) {
                throw fatal;
            }
            image.close();
            successfulCloses++;
        }
    }

    private static ImagePlus stack(String title, int slices, int width, int height) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < slices; z++) {
            ByteProcessor processor = new ByteProcessor(width, height);
            processor.setValue(z + 1);
            processor.fill();
            stack.addSlice("z" + (z + 1), processor);
        }
        ImagePlus image = new ImagePlus(title, stack);
        image.setDimensions(1, slices, 1);
        return image;
    }

}
