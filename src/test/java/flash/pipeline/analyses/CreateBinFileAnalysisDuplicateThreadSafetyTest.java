package flash.pipeline.analyses;

import flash.pipeline.image.ImageOps;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import org.junit.Test;

import java.awt.Rectangle;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class CreateBinFileAnalysisDuplicateThreadSafetyTest {

    @Test
    public void duplicateThreadSafeClonesSharedImageFromEightThreads()
            throws Exception {
        final ImagePlus source = syntheticStack();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        final CountDownLatch start = new CountDownLatch(1);
        List<Future<ImagePlus>> futures = new ArrayList<Future<ImagePlus>>();

        try {
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(new Callable<ImagePlus>() {
                    @Override public ImagePlus call() throws Exception {
                        if (!start.await(5L, TimeUnit.SECONDS)) {
                            throw new AssertionError("duplicate workers did not start");
                        }
                        return ImageOps.duplicateThreadSafe(source);
                    }
                }));
            }

            start.countDown();

            for (int i = 0; i < futures.size(); i++) {
                ImagePlus duplicate = futures.get(i).get(5L, TimeUnit.SECONDS);
                assertDuplicateMatchesSource("worker " + i, source, duplicate);
                duplicate.flush();
            }
        } finally {
            executor.shutdownNow();
            assertTrue("duplicate executor did not terminate",
                    executor.awaitTermination(5L, TimeUnit.SECONDS));
            source.flush();
        }
    }

    @Test
    public void setupPreviewCopiesEveryPlaneWithoutMutatingSourcePixelsOrRois()
            throws Exception {
        final ImagePlus source = syntheticStack();
        final List<Rectangle> sourceRois = new ArrayList<Rectangle>();
        final List<Integer> sourceFirstPixels = new ArrayList<Integer>();
        for (int slice = 1; slice <= source.getStackSize(); slice++) {
            ImageProcessor processor = source.getStack().getProcessor(slice);
            Rectangle roi = new Rectangle(slice, slice, 4, 3);
            processor.setRoi(roi);
            sourceRois.add(new Rectangle(processor.getRoi()));
            sourceFirstPixels.add(Integer.valueOf(processor.get(0, 0)));
        }

        TrackingCloser closer = new TrackingCloser();
        CreateBinFileAnalysis.SetupPreviewTask task =
                new CreateBinFileAnalysis.SetupPreviewTask(100L, closer);
        ImagePlus label = task.run(source, null,
                new CreateBinFileAnalysis.SetupPreviewComputation() {
            @Override public ImagePlus run(ImagePlus workerSource, ImagePlus ignored) {
                assertNotSame(source, workerSource);
                assertEquals(source.getStackSize(), workerSource.getStackSize());
                for (int slice = 1; slice <= source.getStackSize(); slice++) {
                    assertNotSame(source.getStack().getProcessor(slice),
                            workerSource.getStack().getProcessor(slice));
                    assertEquals(sourceFirstPixels.get(slice - 1).intValue(),
                            workerSource.getStack().getProcessor(slice).get(0, 0));
                }
                workerSource.getStack().getProcessor(1).set(0, 0, 0);
                return onePixelImage("detached label", 7);
            }
        });

        assertNotNull(label);
        assertFalse(task.hasActiveWorker());
        for (int slice = 1; slice <= source.getStackSize(); slice++) {
            ImageProcessor processor = source.getStack().getProcessor(slice);
            assertEquals(sourceFirstPixels.get(slice - 1).intValue(), processor.get(0, 0));
            assertEquals(sourceRois.get(slice - 1), processor.getRoi());
        }
        assertFalse("accepted result remains owned by the stage", closer.wasClosed(label));
        source.flush();
        label.flush();
    }

    @Test
    public void starDistCancelDefersSourceCloseAndRejectsLateLabel() throws Exception {
        assertCancelRejectsLateLabel("StarDist");
    }

    @Test
    public void starDistCancelBeforeTaskRegistrationNeverReadsClosedSource()
            throws Exception {
        final ImagePlus source = syntheticStack();
        final TrackingCloser closer = new TrackingCloser();
        final CountDownLatch registrationGap = new CountDownLatch(1);
        final AtomicBoolean releaseRegistration = new AtomicBoolean();
        final AtomicInteger engineCalls = new AtomicInteger();
        final AtomicReference<ImagePlus> result = new AtomicReference<ImagePlus>();
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final CreateBinFileAnalysis.SetupPreviewTask task =
                new CreateBinFileAnalysis.SetupPreviewTask(20L, closer, new Runnable() {
            @Override public void run() {
                registrationGap.countDown();
                while (!releaseRegistration.get()) Thread.yield();
            }
        });

        Thread worker = previewThread("stardist-registration-gap", task, source,
                new CreateBinFileAnalysis.SetupPreviewComputation() {
            @Override public ImagePlus run(ImagePlus workerSource, ImagePlus ignored) {
                engineCalls.incrementAndGet();
                return onePixelImage("must not run", 1);
            }
        }, result, failure);
        worker.start();
        assertTrue(registrationGap.await(2L, TimeUnit.SECONDS));

        // Mirrors StarDistParameterStage.onLeave: SwingWorker cancellation
        // interrupts first, then the adapter closes its dialog-owned source.
        worker.interrupt();
        task.cancelAndClose(source);
        assertTrue("no task was registered, so source close should complete",
                closer.wasClosed(source));
        releaseRegistration.set(true);

        worker.join(2000L);
        assertFalse(worker.isAlive());
        assertNull(result.get());
        assertEquals("canceled registration gap must not copy pixels or run StarDist",
                0, engineCalls.get());
        assertFalse(task.hasActiveWorker());
        assertNoFailure(failure);
        source.flush();
    }

    @Test
    public void cellposeCancelDefersSourceCloseAndRejectsLateLabel() throws Exception {
        assertCancelRejectsLateLabel("Cellpose");
    }

    @Test
    public void cellposeCancelBetweenCompanionCopyAndEngineNeverReopensClosedSource()
            throws Exception {
        final ImagePlus primary = syntheticStack();
        final ImagePlus companionCache = syntheticStack();
        final TrackingCloser closer = new TrackingCloser();
        final CreateBinFileAnalysis.SetupPreviewTask task =
                new CreateBinFileAnalysis.SetupPreviewTask(20L, closer);
        final CountDownLatch companionReady = new CountDownLatch(1);
        final CountDownLatch continueToEngine = new CountDownLatch(1);
        final AtomicInteger engineCalls = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                ImagePlus companion = null;
                try {
                    companion = task.copyForWorker(companionCache);
                    companionReady.countDown();
                    awaitIgnoringInterrupt(continueToEngine);
                    ImagePlus result = task.run(primary, companion,
                            new CreateBinFileAnalysis.SetupPreviewComputation() {
                        @Override public ImagePlus run(ImagePlus workerSource,
                                                       ImagePlus workerCompanion) {
                            engineCalls.incrementAndGet();
                            return onePixelImage("must not escape", 5);
                        }
                    });
                    assertNull(result);
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                } finally {
                    task.cancelAndClose(companion);
                }
            }
        }, "cellpose-companion-gap");
        worker.start();
        assertTrue(companionReady.await(2L, TimeUnit.SECONDS));

        task.cancelAndClose(primary);
        task.cancelAndClose(companionCache);
        assertFalse("primary closed before reserved worker acknowledged cancellation",
                closer.wasClosed(primary));
        assertFalse("companion cache closed during its reserved copy lifecycle",
                closer.wasClosed(companionCache));

        continueToEngine.countDown();
        worker.join(2000L);
        assertFalse(worker.isAlive());
        assertEquals("canceled gap must not enter Cellpose", 0, engineCalls.get());
        assertTrue(closer.wasClosed(primary));
        assertTrue(closer.wasClosed(companionCache));
        assertFalse(task.hasActiveWorker());
        assertNoFailure(failure);
        primary.flush();
        companionCache.flush();
    }

    @Test
    public void replacementGenerationRejectsOldPreviewAndAcceptsNewest() throws Exception {
        final ImagePlus source = syntheticStack();
        final TrackingCloser closer = new TrackingCloser();
        final CreateBinFileAnalysis.SetupPreviewTask task =
                new CreateBinFileAnalysis.SetupPreviewTask(20L, closer);
        final CountDownLatch firstEntered = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final ImagePlus firstLabel = onePixelImage("first generation", 1);
        final ImagePlus secondLabel = onePixelImage("second generation", 2);
        final AtomicReference<ImagePlus> firstResult = new AtomicReference<ImagePlus>();
        final AtomicReference<ImagePlus> secondResult = new AtomicReference<ImagePlus>();
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

        Thread first = previewThread("setup-preview-first", task, source,
                new CreateBinFileAnalysis.SetupPreviewComputation() {
            @Override public ImagePlus run(ImagePlus workerSource, ImagePlus ignored) {
                firstEntered.countDown();
                awaitIgnoringInterrupt(releaseFirst);
                return firstLabel;
            }
        }, firstResult, failure);
        first.start();
        assertTrue(firstEntered.await(2L, TimeUnit.SECONDS));

        Thread second = previewThread("setup-preview-second", task, source,
                new CreateBinFileAnalysis.SetupPreviewComputation() {
            @Override public ImagePlus run(ImagePlus workerSource, ImagePlus ignored) {
                return secondLabel;
            }
        }, secondResult, failure);
        second.start();
        second.join(2000L);
        assertFalse("replacement preview worker remained alive", second.isAlive());
        assertSame(secondLabel, secondResult.get());
        assertFalse(closer.wasClosed(secondLabel));

        releaseFirst.countDown();
        first.join(2000L);
        assertFalse("replaced preview worker remained alive", first.isAlive());
        assertNull(firstResult.get());
        assertTrue("late generation label was not closed", closer.wasClosed(firstLabel));
        assertFalse(task.hasActiveWorker());
        assertNoFailure(failure);
        source.flush();
        secondLabel.flush();
    }

    @Test
    public void cleanupFaultStillTerminatesTaskAndAttemptsLaterClosures() throws Exception {
        final TrackingCloser closer = new TrackingCloser();
        closer.failNextClose.set(1);
        CreateBinFileAnalysis.SetupPreviewTask task =
                new CreateBinFileAnalysis.SetupPreviewTask(50L, closer);
        ImagePlus source = syntheticStack();
        final ImagePlus result = onePixelImage("result", 1);

        ImagePlus accepted = task.run(source, null,
                new CreateBinFileAnalysis.SetupPreviewComputation() {
            @Override public ImagePlus run(ImagePlus workerSource, ImagePlus ignored) {
                return result;
            }
        });
        assertSame(result, accepted);
        assertFalse(task.hasActiveWorker());

        task.cancelAndClose(result);
        assertTrue("cleanup after the injected fault was not attempted",
                closer.wasClosed(result));
        assertFalse(task.hasActiveWorker());
        source.flush();
    }

    @Test
    public void invocationOwnershipNeverAdoptsPreExistingChangedImage() {
        ImagePlus unrelated = onePixelImage("unrelated changed image", 42);
        unrelated.changes = true;
        ImagePlus owned = onePixelImage("setup owned", 9);
        ImagePlus directOwned = onePixelImage("setup direct owned", 11);
        TrackingCloser closer = new TrackingCloser();
        Set<ImagePlus> baseline = Collections.newSetFromMap(
                new IdentityHashMap<ImagePlus, Boolean>());
        baseline.add(unrelated);
        Set<Window> baselineWindows = Collections.newSetFromMap(
                new IdentityHashMap<Window, Boolean>());
        CreateBinFileAnalysis.SetupResourceOwnership ownership =
                new CreateBinFileAnalysis.SetupResourceOwnership(
                        baseline, baselineWindows, closer);

        ownership.ownImage(unrelated);
        ownership.ownImage(owned);
        ownership.ownImage(directOwned);
        assertFalse("direct cleanup must reject an invocation-baseline image",
                ownership.closeDirect(unrelated));
        assertTrue("direct cleanup must retain its normal setup-owned behavior",
                ownership.closeDirect(directOwned));
        ownership.closeAll();

        assertFalse(closer.wasClosed(unrelated));
        assertTrue(unrelated.changes);
        assertEquals(42, unrelated.getProcessor().get(0, 0));
        assertTrue(closer.wasClosed(owned));
        assertTrue(closer.wasClosed(directOwned));
        unrelated.flush();
        owned.flush();
        directOwned.flush();
    }

    private static void assertCancelRejectsLateLabel(String engine) throws Exception {
        final ImagePlus source = syntheticStack();
        source.setTitle(engine + " source");
        final ImagePlus lateLabel = onePixelImage(engine + " late label", 3);
        final TrackingCloser closer = new TrackingCloser();
        final CreateBinFileAnalysis.SetupPreviewTask task =
                new CreateBinFileAnalysis.SetupPreviewTask(20L, closer);
        final CountDownLatch enteredEngine = new CountDownLatch(1);
        final CountDownLatch releaseEngine = new CountDownLatch(1);
        final AtomicReference<ImagePlus> result = new AtomicReference<ImagePlus>();
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

        Thread worker = previewThread(engine.toLowerCase() + "-setup-preview",
                task, source, new CreateBinFileAnalysis.SetupPreviewComputation() {
            @Override public ImagePlus run(ImagePlus workerSource, ImagePlus ignored) {
                enteredEngine.countDown();
                awaitIgnoringInterrupt(releaseEngine);
                return lateLabel;
            }
        }, result, failure);
        worker.start();
        assertTrue(engine + " preview did not enter its engine",
                enteredEngine.await(2L, TimeUnit.SECONDS));

        long started = System.nanoTime();
        task.cancelAndClose(source);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(engine + " cancellation exceeded its bounded await: " + elapsedMillis + " ms",
                elapsedMillis < 1000L);
        assertFalse(engine + " source closed while its worker still had a reference",
                closer.wasClosed(source));
        assertTrue(engine + " worker should still be tracked until it exits",
                task.hasActiveWorker());

        releaseEngine.countDown();
        worker.join(2000L);
        assertFalse(engine + " preview worker remained alive", worker.isAlive());
        assertNull(engine + " late callback result escaped", result.get());
        assertTrue(engine + " late label was not closed", closer.wasClosed(lateLabel));
        assertTrue(engine + " deferred source was not closed", closer.wasClosed(source));
        assertFalse(engine + " task still reports a live worker", task.hasActiveWorker());
        assertNoFailure(failure);
        source.flush();
        lateLabel.flush();
    }

    private static Thread previewThread(String name,
                                        final CreateBinFileAnalysis.SetupPreviewTask task,
                                        final ImagePlus source,
                                        final CreateBinFileAnalysis.SetupPreviewComputation computation,
                                        final AtomicReference<ImagePlus> result,
                                        final AtomicReference<Throwable> failure) {
        return new Thread(new Runnable() {
            @Override public void run() {
                try {
                    result.set(task.run(source, null, computation));
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                }
            }
        }, name);
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean complete = false;
        while (!complete) {
            try {
                complete = latch.await(2L, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                // Simulates a native engine that does not stop on Java interrupt.
            }
        }
    }

    private static void assertNoFailure(AtomicReference<Throwable> failure) {
        Throwable throwable = failure.get();
        if (throwable != null) {
            throw new AssertionError("preview worker failed", throwable);
        }
    }

    private static ImagePlus onePixelImage(String title, int value) {
        ShortProcessor processor = new ShortProcessor(1, 1);
        processor.set(0, 0, value);
        return new ImagePlus(title, processor);
    }

    private static final class TrackingCloser implements CreateBinFileAnalysis.SetupImageCloser {
        final Set<ImagePlus> closed = Collections.newSetFromMap(
                new IdentityHashMap<ImagePlus, Boolean>());
        final AtomicInteger failNextClose = new AtomicInteger();

        @Override public synchronized void close(ImagePlus image) {
            if (image == null) return;
            closed.add(image);
            if (failNextClose.getAndDecrement() > 0) {
                throw new IllegalStateException("injected cleanup failure");
            }
        }

        synchronized boolean wasClosed(ImagePlus image) {
            return closed.contains(image);
        }
    }

    private static ImagePlus syntheticStack() {
        int width = 17;
        int height = 13;
        int slices = 5;
        ProcessorBackedImageStack stack = new ProcessorBackedImageStack(width, height);
        for (int z = 1; z <= slices; z++) {
            ShortProcessor processor = new ShortProcessor(width, height);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    processor.set(x, y, z * 1000 + y * width + x);
                }
            }
            stack.addSourceSlice("z" + z, processor);
        }
        ImagePlus image = new ImagePlus("shared synthetic stack", stack);
        image.setDimensions(1, slices, 1);
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 0.4d;
        calibration.pixelHeight = 0.5d;
        calibration.pixelDepth = 1.25d;
        image.setCalibration(calibration);
        return image;
    }

    private static final class ProcessorBackedImageStack extends ImageStack {
        private final List<ImageProcessor> processors = new ArrayList<ImageProcessor>();

        ProcessorBackedImageStack(int width, int height) {
            super(width, height);
        }

        void addSourceSlice(String label, ImageProcessor processor) {
            super.addSlice(label, processor);
            processors.add(processor);
        }

        @Override public ImageProcessor getProcessor(int index) {
            return processors.get(index - 1);
        }
    }

    private static void assertDuplicateMatchesSource(String prefix,
                                                     ImagePlus source,
                                                     ImagePlus duplicate) {
        assertNotNull(prefix, duplicate);
        assertNotSame(prefix, source, duplicate);
        assertEquals(prefix, source.getWidth(), duplicate.getWidth());
        assertEquals(prefix, source.getHeight(), duplicate.getHeight());
        assertEquals(prefix, source.getStackSize(), duplicate.getStackSize());
        assertEquals(prefix, source.getNChannels(), duplicate.getNChannels());
        assertEquals(prefix, source.getNSlices(), duplicate.getNSlices());
        assertEquals(prefix, source.getNFrames(), duplicate.getNFrames());
        assertEquals(prefix, source.getCalibration().pixelWidth,
                duplicate.getCalibration().pixelWidth, 0.0d);
        assertNotSame(prefix, source.getStack().getProcessor(1),
                duplicate.getStack().getProcessor(1));

        int[][] samples = new int[][]{{0, 0}, {3, 4}, {16, 12}, {8, 6}};
        for (int slice = 1; slice <= source.getStackSize(); slice++) {
            ImageProcessor expected = source.getStack().getProcessor(slice);
            ImageProcessor actual = duplicate.getStack().getProcessor(slice);
            for (int i = 0; i < samples.length; i++) {
                int x = samples[i][0];
                int y = samples[i][1];
                assertEquals(prefix + " slice " + slice + " sample " + i,
                        expected.get(x, y), actual.get(x, y));
            }
        }
    }
}
