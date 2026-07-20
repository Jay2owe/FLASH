package flash.pipeline.ui.config;

import flash.pipeline.cellpose.CellposeRuntime;
import flash.pipeline.objects.ObjectsCounter3DWrapper;
import flash.pipeline.ui.preview.PreviewPairPanel;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import org.junit.Test;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConfigPreviewEpochTest {

    private static final long TIMEOUT_MILLIS = 5000L;

    @Test(timeout = 10000)
    public void filterCompletionQueuedOnEdtAfterLeaveIsDisposed() throws Exception {
        final ConfigQcContext context = context();
        final RecordingActions actions = new RecordingActions();
        final BlockingFilterAdapter adapter = new BlockingFilterAdapter();
        final FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"),
                new MacroStore(), adapter, null, null);

        onEdt(new Runnable() {
            @Override public void run() {
                stage.buildControls(context, actions);
                stage.onEnter(context, previewPanel());
                stage.runPreviewOnWorkerForTest();
            }
        });
        assertTrue(adapter.gate.started.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));

        onEdt(new Runnable() {
            @Override public void run() {
                adapter.gate.release.countDown();
                waitOnEdtFor(new BooleanSupplier() {
                    @Override public boolean getAsBoolean() {
                        return stage.previewWorkerBackgroundDoneForTest();
                    }
                });
                stage.onLeave(context);
            }
        });
        final String statusAfterLeave = actions.status;
        final int adjustedAfterLeave = actions.adjustedUpdates;

        drainEdt();
        await(new BooleanSupplier() {
            @Override public boolean getAsBoolean() {
                return adapter.closeCount(adapter.output) == 1;
            }
        });

        assertEquals(statusAfterLeave, actions.status);
        assertEquals(adjustedAfterLeave, actions.adjustedUpdates);
        assertEquals(1, adapter.closeCount(adapter.output));
        assertFalse(stage.previewWorkerActiveForTest());
    }

    @Test(timeout = 10000)
    public void particleRestartCancelsQueuedRequestCapturesThresholdAndDisposesBothImages()
            throws Exception {
        final CountDownLatch poolRelease = new CountDownLatch(1);
        List<SwingWorker<Void, Void>> blockers = saturateSwingWorkerPool(poolRelease);
        try {
            final ConfigQcContext context = context();
            final RecordingActions actions = new RecordingActions();
            final BlockingParticleAdapter adapter = new BlockingParticleAdapter();
            final ParticleSizeStage stage = new ParticleSizeStage(new SizeStore(), adapter);

            onEdt(new Runnable() {
                @Override public void run() {
                    stage.buildControls(context, actions);
                    stage.onEnter(context, previewPanel());
                    assertEquals(42, stage.thresholdForTest());
                    stage.runPreviewOnWorkerForTest();
                    stage.setThresholdForTest(99);
                }
            });

            poolRelease.countDown();
            assertTrue(adapter.gate.started.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
            assertEquals("Threshold must be captured when Run Preview is pressed.",
                    42, adapter.lastThreshold);

            onEdt(new Runnable() {
                @Override public void run() {
                    stage.restartStage(context);
                }
            });
            final String statusAfterRestart = actions.status;
            assertFalse(stage.previewWorkerActiveForTest());

            adapter.gate.release.countDown();
            awaitObjectResultClosed(adapter);
            drainEdt();

            assertTrue(adapter.gate.interrupted);
            assertEquals(statusAfterRestart, actions.status);
            assertEquals(0, actions.adjustedUpdates);
            assertEquals(1, adapter.closeCount(adapter.labels));
            assertEquals(1, adapter.closeCount(adapter.masked));
            assertFalse(stage.previewWorkerActiveForTest());
        } finally {
            poolRelease.countDown();
            for (int i = 0; i < blockers.size(); i++) {
                blockers.get(i).cancel(true);
            }
        }
    }

    @Test(timeout = 10000)
    public void classicalLeaveDisposesInterruptIgnoringResultWithoutLateUiMutation()
            throws Exception {
        final ConfigQcContext context = context();
        final RecordingActions actions = new RecordingActions();
        final BlockingClassicalAdapter adapter = new BlockingClassicalAdapter();
        final ClassicalSegmentationStage stage = new ClassicalSegmentationStage(
                new ThresholdStore(), new SizeStore(), adapter);

        startClassical(stage, context, actions);
        assertTrue(adapter.gate.started.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        onEdt(new Runnable() {
            @Override public void run() {
                stage.onLeave(context);
            }
        });
        final String statusAfterLeave = actions.status;
        final int adjustedAfterLeave = actions.adjustedUpdates;
        assertFalse(stage.previewWorkerActiveForTest());

        adapter.gate.release.countDown();
        awaitObjectResultClosed(adapter);
        drainEdt();

        assertTrue(adapter.gate.interrupted);
        assertEquals(statusAfterLeave, actions.status);
        assertEquals(adjustedAfterLeave, actions.adjustedUpdates);
        assertEquals(1, adapter.closeCount(adapter.labels));
        assertEquals(1, adapter.closeCount(adapter.masked));
        assertFalse(stage.previewWorkerActiveForTest());
    }

    @Test(timeout = 10000)
    public void starDistReenterInvalidatesPriorSessionAndDisposesItsLateResult()
            throws Exception {
        final ConfigQcContext firstContext = context("first");
        final ConfigQcContext replacementContext = context("replacement");
        final RecordingActions actions = new RecordingActions();
        final BlockingStarDistAdapter adapter = new BlockingStarDistAdapter();
        final StarDistParameterStage stage = new StarDistParameterStage(
                new MethodStore("stardist:0.5:0.4"), new SizeStore(), adapter);

        onEdt(new Runnable() {
            @Override public void run() {
                stage.buildControls(firstContext, actions);
                stage.onEnter(firstContext, previewPanel());
                stage.runPreviewOnWorkerForTest();
            }
        });
        assertTrue(adapter.gate.started.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));

        onEdt(new Runnable() {
            @Override public void run() {
                stage.onEnter(replacementContext, previewPanel());
            }
        });
        final String statusAfterReplacement = actions.status;
        final int adjustedAfterReplacement = actions.adjustedUpdates;
        assertFalse(stage.previewWorkerActiveForTest());

        adapter.gate.release.countDown();
        awaitImageClosed(adapter, new BooleanSupplier() {
            @Override public boolean getAsBoolean() {
                return adapter.closeCount(adapter.output) == 1;
            }
        });
        drainEdt();

        assertTrue(adapter.gate.interrupted);
        assertEquals(statusAfterReplacement, actions.status);
        assertEquals(adjustedAfterReplacement, actions.adjustedUpdates);
        assertEquals(1, adapter.closeCount(adapter.output));
        assertFalse(stage.previewWorkerActiveForTest());
    }

    @Test(timeout = 10000)
    public void cellposeTerminalDialogCloseOwnsAndDisposesLateResult() throws Exception {
        final ConfigQcContext context = context();
        final BlockingCellposeAdapter adapter = new BlockingCellposeAdapter();
        final CellposeParameterStage stage = new CellposeParameterStage(
                new MethodStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false"),
                new SizeStore(), adapter, new RuntimeAdapter(),
                Arrays.asList("Primary", "Companion"), 0, false);
        final AtomicReference<ConfigQcDialog> dialogRef = new AtomicReference<ConfigQcDialog>();

        onEdt(new Runnable() {
            @Override public void run() {
                dialogRef.set(ConfigQcDialog.createForTest(
                        context, Arrays.<ConfigQcStage>asList(stage)));
                stage.runPreviewOnWorkerForTest();
            }
        });
        assertTrue(adapter.gate.started.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));

        onEdt(new Runnable() {
            @Override public void run() {
                dialogRef.get().actionsForTest().cancel();
            }
        });
        drainEdt();
        final String statusAfterClose = dialogRef.get().statusTextForTest();
        assertFalse(stage.previewWorkerActiveForTest());

        adapter.gate.release.countDown();
        awaitImageClosed(adapter, new BooleanSupplier() {
            @Override public boolean getAsBoolean() {
                return adapter.closeCount(adapter.output) == 1;
            }
        });
        drainEdt();

        assertTrue(adapter.gate.interrupted);
        assertEquals(statusAfterClose, dialogRef.get().statusTextForTest());
        assertEquals(1, adapter.closeCount(adapter.output));
        assertFalse(stage.previewWorkerActiveForTest());
    }

    private static void startClassical(final ClassicalSegmentationStage stage,
                                       final ConfigQcContext context,
                                       final RecordingActions actions) throws Exception {
        onEdt(new Runnable() {
            @Override public void run() {
                stage.buildControls(context, actions);
                stage.onEnter(context, previewPanel());
                stage.runPreviewOnWorkerForTest();
            }
        });
    }

    private static List<SwingWorker<Void, Void>> saturateSwingWorkerPool(
            final CountDownLatch release) throws Exception {
        final CountDownLatch started = new CountDownLatch(10);
        List<SwingWorker<Void, Void>> workers = new ArrayList<SwingWorker<Void, Void>>();
        for (int i = 0; i < 10; i++) {
            SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
                @Override protected Void doInBackground() {
                    started.countDown();
                    boolean waiting = true;
                    while (waiting) {
                        try {
                            release.await();
                            waiting = false;
                        } catch (InterruptedException ignored) {
                            // Test cleanup also releases the latch.
                        }
                    }
                    return null;
                }
            };
            workers.add(worker);
            worker.execute();
        }
        assertTrue("Could not saturate the SwingWorker pool deterministically.",
                started.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        return workers;
    }

    private static void awaitObjectResultClosed(final TrackingObjectAdapter adapter)
            throws Exception {
        await(new BooleanSupplier() {
            @Override public boolean getAsBoolean() {
                return adapter.closeCount(adapter.labels) == 1
                        && adapter.closeCount(adapter.masked) == 1;
            }
        });
    }

    private static void awaitImageClosed(TrackingImages adapter, BooleanSupplier condition)
            throws Exception {
        await(condition);
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue("Timed out waiting for asynchronous preview cleanup.", condition.getAsBoolean());
    }

    private static void waitOnEdtFor(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertTrue("Background result did not finish while the EDT was occupied.",
                condition.getAsBoolean());
    }

    private static void onEdt(final Runnable runnable) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
            return;
        }
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                try {
                    runnable.run();
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            }
        });
        if (failure.get() != null) {
            if (failure.get() instanceof AssertionError) throw (AssertionError) failure.get();
            throw new RuntimeException(failure.get());
        }
    }

    private static void drainEdt() throws Exception {
        onEdt(new Runnable() {
            @Override public void run() {
            }
        });
    }

    private static PreviewPairPanel previewPanel() {
        return new PreviewPairPanel("Original", "Adjusted");
    }

    private static ConfigQcContext context() {
        return context("QC image");
    }

    private static ConfigQcContext context(String title) {
        return ConfigQcContext.fromImages(
                null, null, null,
                Arrays.asList(image(title)),
                Arrays.asList("Primary", "Companion"),
                0);
    }

    private static ImagePlus image(String title) {
        ImageStack stack = new ImageStack(4, 1);
        ByteProcessor processor = new ByteProcessor(4, 1);
        processor.set(0, 0, 10);
        processor.set(1, 0, 30);
        processor.set(2, 0, 60);
        processor.set(3, 0, 100);
        stack.addSlice(processor);
        return new ImagePlus(title, stack);
    }

    private static final class Gate {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean interrupted;

        void awaitIgnoringInterrupt() {
            started.countDown();
            boolean waiting = true;
            while (waiting) {
                try {
                    release.await();
                    waiting = false;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        }
    }

    private abstract static class TrackingImages {
        private final Map<ImagePlus, Integer> closeCounts = Collections.synchronizedMap(
                new IdentityHashMap<ImagePlus, Integer>());

        final void recordClose(ImagePlus image) {
            if (image == null) return;
            synchronized (closeCounts) {
                Integer count = closeCounts.get(image);
                closeCounts.put(image, Integer.valueOf(count == null ? 1 : count.intValue() + 1));
            }
            image.flush();
        }

        final int closeCount(ImagePlus image) {
            if (image == null) return 0;
            synchronized (closeCounts) {
                Integer count = closeCounts.get(image);
                return count == null ? 0 : count.intValue();
            }
        }
    }

    private abstract static class TrackingObjectAdapter extends TrackingImages {
        final Gate gate = new Gate();
        volatile ImagePlus labels;
        volatile ImagePlus masked;

        final ObjectsCounter3DWrapper.Result blockedResult() {
            labels = image("late labels");
            masked = image("late masked");
            ResultsTable table = new ResultsTable();
            table.incrementCounter();
            table.setValue("Label", 0, 1);
            table.setValue("Volume (pixel^3)", 0, 4);
            gate.awaitIgnoringInterrupt();
            return new ObjectsCounter3DWrapper.Result(table, labels, masked, true);
        }
    }

    private static final class BlockingFilterAdapter extends TrackingImages
            implements FilterParameterStage.PreviewAdapter {
        final Gate gate = new Gate();
        volatile ImagePlus output;

        @Override public ImagePlus createSource(ConfigQcContext context) {
            return context.getCurrentImagePlus().duplicate();
        }

        @Override public ImagePlus createFilteredPreview(ImagePlus source, String macroContent) {
            output = source.duplicate();
            output.setTitle("late filter result");
            gate.awaitIgnoringInterrupt();
            return output;
        }

        @Override public void close(ImagePlus image) {
            recordClose(image);
        }
    }

    private static final class BlockingParticleAdapter extends TrackingObjectAdapter
            implements ParticleSizeStage.PreviewAdapter {
        volatile int lastThreshold = -1;

        @Override public ImagePlus createRawSource(ConfigQcContext context) {
            return context.getCurrentImagePlus().duplicate();
        }

        @Override public ImagePlus createFilteredSource(ConfigQcContext context) {
            return context.getCurrentImagePlus().duplicate();
        }

        @Override public int resolveThreshold(ImagePlus filteredSource, ConfigQcContext context) {
            return 42;
        }

        @Override public ObjectsCounter3DWrapper.Result runPreview(
                ImagePlus filteredSource, int threshold, int minSize, int maxSize) {
            lastThreshold = threshold;
            return blockedResult();
        }

        @Override public int countObjects(ObjectsCounter3DWrapper.Result result) {
            return 1;
        }

        @Override public void close(ImagePlus image) {
            recordClose(image);
        }
    }

    private static final class BlockingClassicalAdapter extends TrackingObjectAdapter
            implements ClassicalSegmentationStage.PreviewAdapter {

        @Override public ImagePlus createRawSource(ConfigQcContext context) {
            return context.getCurrentImagePlus().duplicate();
        }

        @Override public ImagePlus createFilteredSource(ConfigQcContext context) {
            return context.getCurrentImagePlus().duplicate();
        }

        @Override public ObjectsCounter3DWrapper.Result runPreview(
                ImagePlus filteredSource, int threshold, int minSize, int maxSize) {
            return blockedResult();
        }

        @Override public int countObjects(ObjectsCounter3DWrapper.Result result) {
            return 1;
        }

        @Override public void close(ImagePlus image) {
            recordClose(image);
        }
    }

    private static final class BlockingStarDistAdapter extends TrackingImages
            implements StarDistParameterStage.PreviewAdapter {
        final Gate gate = new Gate();
        volatile ImagePlus output;

        @Override public ImagePlus createRawSource(ConfigQcContext context) {
            return context.getCurrentImagePlus().duplicate();
        }

        @Override public ImagePlus createFilteredSource(ConfigQcContext context) {
            return context.getCurrentImagePlus().duplicate();
        }

        @Override public ImagePlus runPreview(
                ImagePlus filteredSource, StarDistParameterStage.Parameters parameters) {
            output = image("late StarDist result");
            gate.awaitIgnoringInterrupt();
            return output;
        }

        @Override public int countLabels(ImagePlus labelImage) {
            return 1;
        }

        @Override public void close(ImagePlus image) {
            recordClose(image);
        }
    }

    private static final class BlockingCellposeAdapter extends TrackingImages
            implements CellposeParameterStage.PreviewAdapter {
        final Gate gate = new Gate();
        volatile ImagePlus output;

        @Override public ImagePlus createRawSource(ConfigQcContext context) {
            return context.getCurrentImagePlus().duplicate();
        }

        @Override public ImagePlus createFilteredSource(ConfigQcContext context) {
            return context.getCurrentImagePlus().duplicate();
        }

        @Override public ImagePlus createFilteredCompanionSource(
                ConfigQcContext context, int channelIndex) {
            return context.getCurrentImagePlus().duplicate();
        }

        @Override public ImagePlus runPreview(
                ImagePlus filteredSource,
                ImagePlus filteredCompanionSource,
                CellposeParameterStage.Parameters parameters) {
            output = image("late Cellpose result");
            gate.awaitIgnoringInterrupt();
            return output;
        }

        @Override public int countLabels(ImagePlus labelImage) {
            return 1;
        }

        @Override public void close(ImagePlus image) {
            recordClose(image);
        }
    }

    private static final class RecordingActions implements ConfigQcActions {
        volatile String status = "";
        volatile int adjustedUpdates;
        volatile int runningUpdates;
        volatile JButton previewButton;

        @Override public void setStatus(String text) {
            status = text;
        }

        @Override public void markPreviewStale(String text) {
            status = text;
        }

        @Override public void setAdjustedPreview(ImagePlus image, String text) {
            adjustedUpdates++;
            status = text;
        }

        @Override public void registerPreviewButton(JButton button) {
            previewButton = button;
        }

        @Override public void setPreviewButtonRunning(boolean running) {
            runningUpdates++;
        }

        @Override public void nextImage() {
        }

        @Override public void skipCurrentImage() {
        }

        @Override public void restartStage() {
        }

        @Override public void cancel() {
        }
    }

    private static final class MacroStore implements FilterParameterStage.MacroStore {
        private static final String MACRO = "run(\"Gaussian Blur...\", \"sigma=1\");";

        @Override public String getInitialPreset() {
            return "Default";
        }

        @Override public String loadInitialMacro() {
            return MACRO;
        }

        @Override public String loadPresetMacro(String presetName) {
            return MACRO;
        }

        @Override public void save(String presetName, String macroContent) {
        }

        @Override public void saveAsPreset(String presetName, String macroContent) {
        }
    }

    private static final class MethodStore
            implements StarDistParameterStage.ParameterStore,
            CellposeParameterStage.ParameterStore {
        private String token;

        MethodStore(String token) {
            this.token = token;
        }

        @Override public String getMethodToken() {
            return token;
        }

        @Override public void save(String methodToken) {
            token = methodToken;
        }
    }

    private static final class SizeStore
            implements ParticleSizeStage.SizeStore,
            ClassicalSegmentationStage.SizeStore,
            StarDistParameterStage.SizeStore,
            CellposeParameterStage.SizeStore {
        private String token = "0-Infinity";

        @Override public String get() {
            return token;
        }

        @Override public void set(String token) {
            this.token = token;
        }
    }

    private static final class ThresholdStore
            implements ClassicalSegmentationStage.ThresholdStore {
        private String token = "25";

        @Override public String get() {
            return token;
        }

        @Override public void set(String token) {
            this.token = token;
        }
    }

    private static final class RuntimeAdapter implements CellposeParameterStage.RuntimeAdapter {
        private final CellposeRuntime.Status status = CellposeRuntime.probe("");

        @Override public CellposeRuntime.Status cachedRuntimeStatus() {
            return status;
        }

        @Override public CompletableFuture<CellposeRuntime.Status> probeRuntimeAsync() {
            return CompletableFuture.completedFuture(status);
        }

        @Override public boolean nvidiaGpuLikelyAvailable() {
            return false;
        }

        @Override public CellposeParameterStage.GpuInstallResult installGpuSupport() {
            return new CellposeParameterStage.GpuInstallResult(false, "not installed", "");
        }
    }
}
