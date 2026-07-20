package flash.pipeline.ui.variations;

import flash.pipeline.deconv.engine.Algorithm;
import flash.pipeline.deconv.engine.DeconvSettings;
import flash.pipeline.deconv.psf.PsfModel;

import ij.ImagePlus;
import ij.process.ByteProcessor;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DeconvVariationsDialogPhysicalOwnershipTest {

    private static final BooleanSupplier NEVER_CANCEL = new BooleanSupplier() {
        @Override public boolean getAsBoolean() {
            return false;
        }
    };

    @After
    public void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    public void acceptedRunClosesOnlyItsTwoInputsAndLeavesPublishedOutputOwned() throws Exception {
        ImagePlus modalCrop = image("modal");
        TrackingAdapter adapter = new TrackingAdapter();
        ParameterSweep sweep = sweep();
        DeconvVariationsDialog.PhysicalRunStrategy strategy = strategy(modalCrop, adapter, sweep);
        final List<VariationResult> published = new ArrayList<VariationResult>();

        strategy.dispatch(sweep, addTo(published), NEVER_CANCEL);

        assertEquals(1, adapter.callInputs.size());
        assertNotSame(modalCrop, adapter.callInputs.get(0));
        assertEquals(2, adapter.totalCloseCalls());
        assertEquals(1, adapter.closeCount(adapter.callInputs.get(0)));
        assertEquals(1, published.size());
        ImagePlus output = published.get(0).previewImage();
        assertSame(adapter.outputs.get(0), output);
        assertEquals(0, adapter.closeCount(output));
        assertEquals(0, adapter.closeCount(modalCrop));
        assertNotNull("published output remains caller-owned", output.getProcessor());
        assertTrue(strategy.physicalDoneForTest());
    }

    @Test(timeout = 10000L)
    public void interruptIgnoringCallKeepsPrivatePixelsUntilItsLatePhysicalReturn()
            throws Exception {
        final ImagePlus modalCrop = image("modal");
        final BlockingAdapter adapter = new BlockingAdapter();
        final ParameterSweep sweep = sweep();
        final DeconvVariationsDialog.PhysicalRunStrategy strategy =
                strategy(modalCrop, adapter, sweep);
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    strategy.dispatch(sweep, new Consumer<VariationResult>() {
                        @Override public void accept(VariationResult result) {
                            fail("a cancelled late result must not be published");
                        }
                    }, new BooleanSupplier() {
                        @Override public boolean getAsBoolean() {
                            return cancelled.get();
                        }
                    });
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            }
        }, "deconv-physical-cancel-test");
        worker.start();

        assertTrue(adapter.started.await(5L, TimeUnit.SECONDS));
        ImagePlus physicalInput = adapter.callInputs.get(0);
        assertNotSame(modalCrop, physicalInput);
        cancelled.set(true);
        worker.interrupt();
        modalCrop.flush(); // Models DeconvolutionStage releasing the modal-owned crop.

        assertFalse("Swing cancellation is not physical completion",
                strategy.physicalDoneForTest());
        assertNotNull("the engine's private input remains live", physicalInput.getProcessor());
        assertEquals(0, adapter.closeCount(physicalInput));

        adapter.release.countDown();
        worker.join(5000L);
        assertFalse("physical worker must exit after the adapter returns", worker.isAlive());
        assertNull(failure.get());
        assertTrue(strategy.physicalDoneForTest());
        assertEquals(1, adapter.closeCount(physicalInput));
        assertEquals(1, adapter.closeCount(adapter.outputs.get(0)));
        assertEquals("call input, cancelled output, and run input", 3,
                adapter.totalCloseCalls());
        assertEquals(0, adapter.closeCount(modalCrop));
    }

    @Test
    public void cancellationBeforeDispatchClosesQueuedRunExactlyOnce() throws Exception {
        ImagePlus modalCrop = image("modal");
        TrackingAdapter adapter = new TrackingAdapter();
        ParameterSweep sweep = sweep();
        DeconvVariationsDialog.PhysicalRunStrategy strategy = strategy(modalCrop, adapter, sweep);

        assertNull(strategy.cancelBeforeStart());
        assertNull(strategy.cancelBeforeStart());
        strategy.dispatch(sweep, addTo(new ArrayList<VariationResult>()), NEVER_CANCEL);

        assertEquals(0, adapter.callInputs.size());
        assertEquals(1, adapter.totalCloseCalls());
        assertEquals(0, adapter.closeCount(modalCrop));
        assertTrue(strategy.physicalDoneForTest());
    }

    @Test
    public void ordinaryEngineFailureClosesBothInputsAndPublishesTheSameDiagnostic()
            throws Exception {
        final RuntimeException engineFailure = new RuntimeException("engine failed");
        TrackingAdapter adapter = new TrackingAdapter() {
            @Override protected ImagePlus run(ImagePlus rawCrop, DeconvSettings settings) {
                throw engineFailure;
            }
        };
        ParameterSweep sweep = sweep();
        DeconvVariationsDialog.PhysicalRunStrategy strategy = strategy(image("modal"), adapter, sweep);
        List<VariationResult> published = new ArrayList<VariationResult>();

        strategy.dispatch(sweep, addTo(published), NEVER_CANCEL);

        assertEquals(1, published.size());
        assertSame(engineFailure, published.get(0).error());
        assertEquals(2, adapter.totalCloseCalls());
        assertEquals(1, adapter.closeCount(adapter.callInputs.get(0)));
        assertTrue(strategy.physicalDoneForTest());
    }

    @Test
    public void fatalInputCleanupTakesPrecedenceAndRetainsOrdinaryEngineFailure()
            throws Exception {
        final RuntimeException engineFailure = new RuntimeException("engine failed");
        final OutOfMemoryError cleanupFailure = new OutOfMemoryError("input close failed");
        TrackingAdapter adapter = new TrackingAdapter() {
            @Override protected ImagePlus run(ImagePlus rawCrop, DeconvSettings settings) {
                throw engineFailure;
            }

            @Override public void close(ImagePlus image) {
                super.close(image);
                if (image == callInputs.get(0)) {
                    throw cleanupFailure;
                }
            }
        };
        ParameterSweep sweep = sweep();
        DeconvVariationsDialog.PhysicalRunStrategy strategy = strategy(image("modal"), adapter, sweep);

        try {
            strategy.dispatch(sweep, addTo(new ArrayList<VariationResult>()), NEVER_CANCEL);
            fail("Expected fatal cleanup failure");
        } catch (OutOfMemoryError actual) {
            assertSame(cleanupFailure, actual);
            assertEquals(1, actual.getSuppressed().length);
            assertSame(engineFailure, actual.getSuppressed()[0]);
        }
        assertEquals(2, adapter.totalCloseCalls());
        assertTrue(strategy.physicalDoneForTest());
    }

    @Test
    public void interruptionIsClearedForBothClosesAndRestoredAfterPhysicalReturn()
            throws Exception {
        final InterruptedException interruption = new InterruptedException("engine interrupted");
        TrackingAdapter adapter = new TrackingAdapter() {
            @Override protected ImagePlus run(ImagePlus rawCrop, DeconvSettings settings)
                    throws Exception {
                Thread.currentThread().interrupt();
                throw interruption;
            }
        };
        ParameterSweep sweep = sweep();
        DeconvVariationsDialog.PhysicalRunStrategy strategy = strategy(image("modal"), adapter, sweep);
        List<VariationResult> published = new ArrayList<VariationResult>();

        strategy.dispatch(sweep, addTo(published), NEVER_CANCEL);

        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals(2, adapter.closeSawInterrupted.size());
        assertFalse(adapter.closeSawInterrupted.get(0).booleanValue());
        assertFalse(adapter.closeSawInterrupted.get(1).booleanValue());
        assertTrue("an interrupted run is cancellation, not a published failure",
                published.isEmpty());
    }

    private static DeconvVariationsDialog.PhysicalRunStrategy strategy(
            ImagePlus modalCrop, DeconvolutionPreviewAdapter adapter, ParameterSweep sweep) {
        return DeconvVariationsDialog.PhysicalRunStrategy.create(
                modalCrop, adapter, base(), sweep.combos());
    }

    private static Consumer<VariationResult> addTo(final List<VariationResult> results) {
        return new Consumer<VariationResult>() {
            @Override public void accept(VariationResult result) {
                results.add(result);
            }
        };
    }

    private static ParameterSweep sweep() {
        Map<ParameterKey, ParameterValueList> values =
                new LinkedHashMap<ParameterKey, ParameterValueList>();
        values.put(DeconvParameterId.ITERATIONS, ParameterValueList.ofInts(12));
        return new ParameterSweep(ParameterSweep.Method.DECONVOLUTION,
                values, null, "DAPI", "physical-input-test");
    }

    private static DeconvSettings base() {
        return new DeconvSettings("DL2", Algorithm.RL,
                PsfModel.GIBSON_LANNI, 15, 0.01d);
    }

    private static ImagePlus image(String title) {
        ByteProcessor processor = new ByteProcessor(8, 8);
        for (int i = 0; i < processor.getPixelCount(); i++) {
            processor.set(i, i * 3);
        }
        return new ImagePlus(title, processor);
    }

    private static class TrackingAdapter implements DeconvolutionPreviewAdapter {
        final List<ImagePlus> callInputs = new ArrayList<ImagePlus>();
        final List<ImagePlus> outputs = new ArrayList<ImagePlus>();
        final List<Boolean> closeSawInterrupted = new ArrayList<Boolean>();
        final IdentityHashMap<ImagePlus, Integer> closes =
                new IdentityHashMap<ImagePlus, Integer>();

        @Override public final ImagePlus deconvolvePreview(ImagePlus rawCrop,
                                                           DeconvSettings settings)
                throws Exception {
            callInputs.add(rawCrop);
            ImagePlus output = run(rawCrop, settings);
            if (output != null) {
                outputs.add(output);
            }
            return output;
        }

        protected ImagePlus run(ImagePlus rawCrop, DeconvSettings settings) throws Exception {
            return image("output-" + settings.iterations());
        }

        @Override public void close(ImagePlus image) {
            closeSawInterrupted.add(Boolean.valueOf(Thread.currentThread().isInterrupted()));
            Integer count = closes.get(image);
            closes.put(image, Integer.valueOf(count == null ? 1 : count.intValue() + 1));
            image.flush();
        }

        int closeCount(ImagePlus image) {
            Integer count = closes.get(image);
            return count == null ? 0 : count.intValue();
        }

        int totalCloseCalls() {
            int total = 0;
            for (Integer count : closes.values()) {
                total += count.intValue();
            }
            return total;
        }
    }

    private static final class BlockingAdapter extends TrackingAdapter {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override protected ImagePlus run(ImagePlus rawCrop, DeconvSettings settings) {
            started.countDown();
            boolean released = false;
            while (!released) {
                try {
                    released = release.await(5L, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // Deliberately model a native/library call that ignores cancellation.
                }
            }
            assertNotNull("private input stays live for the whole physical call",
                    rawCrop.getProcessor());
            return image("late-output");
        }
    }
}
