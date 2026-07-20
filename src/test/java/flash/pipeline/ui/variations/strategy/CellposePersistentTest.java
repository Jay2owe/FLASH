package flash.pipeline.ui.variations.strategy;

import flash.pipeline.cellpose.CellposeWorkerRequest;
import flash.pipeline.cellpose.CellposeWorkerResult;
import flash.pipeline.ui.config.CellposeParameterStage;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.variations.CropSpec;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterId;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.ParameterValueList;
import flash.pipeline.ui.variations.VariationCache;
import flash.pipeline.ui.variations.VariationResult;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.ShortProcessor;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.awt.Rectangle;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CellposePersistentTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void helperOpenFailureFallsBackAndRemovesTempTree() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        FixedWorkerFactory factory = new FixedWorkerFactory(null);
        factory.openFailure = new IllegalStateException("synthetic helper failure");
        TrackingTempFactory tempFactory = tempFactory();
        List<VariationResult> results = new ArrayList<VariationResult>();

        strategy(adapter, factory, tempFactory, realPathOperations(),
                systemClock(), 1_000L, CropSpec.full(), baseParameters(-1))
                .dispatch(sweep(CropSpec.full()), results::add, () -> false);

        assertEquals(2, results.size());
        assertEquals(2, adapter.previewRuns);
        assertFalse(results.get(0).hasError());
        assertFalse(results.get(1).hasError());
        assertEquals(1, factory.openCalls);
        assertSingleTreeRemoved(tempFactory);
    }

    @Test
    public void successClosesWorkerAndTemporaryTreeExactlyOnce() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        FakeWorker worker = FakeWorker.success();
        FixedWorkerFactory factory = new FixedWorkerFactory(worker);
        TrackingTempFactory tempFactory = tempFactory();
        List<VariationResult> results = new ArrayList<VariationResult>();

        strategy(adapter, factory, tempFactory, realPathOperations(),
                systemClock(), 1_000L, CropSpec.full(), baseParameters(-1))
                .dispatch(sweep(CropSpec.full()), results::add, () -> false);

        assertEquals(2, results.size());
        assertEquals(2, worker.submitCalls);
        assertEquals(1, worker.closeCalls);
        assertSingleTreeRemoved(tempFactory);
    }

    @Test
    public void workerResultFailureClosesSessionThenFallsBack() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        FakeWorker worker = FakeWorker.resultFailure();
        TrackingTempFactory tempFactory = tempFactory();
        List<VariationResult> results = new ArrayList<VariationResult>();

        strategy(adapter, new FixedWorkerFactory(worker), tempFactory,
                realPathOperations(), systemClock(), 1_000L,
                CropSpec.full(), baseParameters(-1))
                .dispatch(sweep(CropSpec.full()), results::add, () -> false);

        assertEquals(2, results.size());
        assertEquals(2, adapter.previewRuns);
        assertEquals(1, worker.submitCalls);
        assertEquals(1, worker.closeCalls);
        assertSingleTreeRemoved(tempFactory);
    }

    @Test
    public void cancellationCancelsOutstandingRequestBeforeClosingWorker() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        List<String> events = new ArrayList<String>();
        FakeWorker worker = FakeWorker.never(events);
        TrackingTempFactory tempFactory = tempFactory();
        AtomicInteger checks = new AtomicInteger();
        BooleanSupplier cancellation = new BooleanSupplier() {
            @Override public boolean getAsBoolean() {
                return checks.incrementAndGet() >= 4;
            }
        };
        List<VariationResult> results = new ArrayList<VariationResult>();

        strategy(adapter, new FixedWorkerFactory(worker), tempFactory,
                realPathOperations(), systemClock(), 1_000L,
                CropSpec.full(), baseParameters(-1))
                .dispatch(sweep(CropSpec.full()), results::add, cancellation);

        assertTrue(results.isEmpty());
        assertEquals(1, worker.neverFuture.cancelCalls);
        assertEquals(1, worker.closeCalls);
        assertBefore(events, "request-cancel", "worker-close");
        assertSingleTreeRemoved(tempFactory);
    }

    @Test
    public void cachedResultsPollCancellationBeforeEveryPublication() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        FixedWorkerFactory factory = new FixedWorkerFactory(FakeWorker.success());
        TrackingTempFactory tempFactory = tempFactory();
        ParameterSweep sweep = sweep(CropSpec.full());
        VariationCache cache = new VariationCache((File) null);
        List<ParameterCombo> combos = SweepDispatchOrder.order(sweep);
        for (int i = 0; i < combos.size(); i++) {
            cache.put(VariationCache.keyFor(sweep, combos.get(i)), labelImage());
        }
        List<VariationResult> results = new ArrayList<VariationResult>();

        strategy(adapter, factory, tempFactory, realPathOperations(),
                systemClock(), 1_000L, CropSpec.full(), baseParameters(-1), cache)
                .dispatch(sweep, results::add, new CancelOnCheckSupplier(3));

        assertEquals(1, results.size());
        assertEquals(0, factory.openCalls);
        assertTrue("Cached-only cancellation must not open a temporary worker tree",
                tempFactory.created.isEmpty());
    }

    @Test
    public void timeoutCancelsOutstandingRequestAndFallsBackDeterministically()
            throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        List<String> events = new ArrayList<String>();
        FakeWorker worker = FakeWorker.never(events);
        TrackingTempFactory tempFactory = tempFactory();
        List<VariationResult> results = new ArrayList<VariationResult>();

        strategy(adapter, new FixedWorkerFactory(worker), tempFactory,
                realPathOperations(), new DeadlineClock(), 10L,
                CropSpec.full(), baseParameters(-1))
                .dispatch(sweep(CropSpec.full()), results::add, () -> false);

        assertEquals(2, results.size());
        assertEquals(2, adapter.previewRuns);
        assertEquals(1, worker.neverFuture.cancelCalls);
        assertEquals(1, worker.closeCalls);
        assertBefore(events, "request-cancel", "worker-close");
        assertSingleTreeRemoved(tempFactory);
    }

    @Test
    public void interruptionIsRestoredAfterRequestAndWorkerCleanup() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        FakeWorker worker = FakeWorker.interrupting();
        TrackingTempFactory tempFactory = tempFactory();
        try {
            strategy(adapter, new FixedWorkerFactory(worker), tempFactory,
                    realPathOperations(), systemClock(), 1_000L,
                    CropSpec.full(), baseParameters(-1))
                    .dispatch(sweep(CropSpec.full()), result -> { }, () -> false);

            assertTrue("request interruption was not restored",
                    Thread.currentThread().isInterrupted());
            assertEquals(1, worker.closeCalls);
            assertSingleTreeRemoved(tempFactory);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void companionAcquisitionInterruptionIsRestoredAndRethrownBeforeWorkerOpen()
            throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        InterruptedException interruption =
                new InterruptedException("synthetic companion interruption");
        adapter.companionFailure = interruption;
        FixedWorkerFactory factory = new FixedWorkerFactory(FakeWorker.success());

        try {
            strategy(adapter, factory, tempFactory(), realPathOperations(),
                    systemClock(), 1_000L, CropSpec.full(), baseParameters(1))
                    .dispatch(sweep(CropSpec.full()), result -> { }, () -> false);
            fail("Expected companion interruption.");
        } catch (InterruptedException actual) {
            assertSame(interruption, actual);
            assertTrue("companion interruption was not restored",
                    Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }

        assertEquals(0, factory.openCalls);
        assertEquals(0, adapter.previewRuns);
    }

    @Test
    public void cancellationCallbackFailureAtRequestBoundaryPropagatesAfterCleanup()
            throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        FakeWorker worker = FakeWorker.success();
        TrackingTempFactory tempFactory = tempFactory();
        RuntimeException callbackFailure =
                new RuntimeException("cancel callback failed at request boundary");

        try {
            strategy(adapter, new FixedWorkerFactory(worker), tempFactory,
                    realPathOperations(), systemClock(), 1_000L,
                    CropSpec.full(), baseParameters(-1))
                    .dispatch(sweep(CropSpec.full()), result -> { },
                            new ThrowOnCheckSupplier(3, callbackFailure));
            fail("Expected cancellation callback failure");
        } catch (RuntimeException actual) {
            assertSame(callbackFailure, actual);
        }

        assertEquals(0, worker.submitCalls);
        assertEquals(1, worker.closeCalls);
        assertEquals(0, adapter.previewRuns);
        assertSingleTreeRemoved(tempFactory);
    }

    @Test
    public void cancellationCallbackFailureInsideAwaitCancelsRequestAndPropagates()
            throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        List<String> events = new ArrayList<String>();
        FakeWorker worker = FakeWorker.never(events);
        TrackingTempFactory tempFactory = tempFactory();
        RuntimeException callbackFailure =
                new RuntimeException("cancel callback failed during await");

        try {
            strategy(adapter, new FixedWorkerFactory(worker), tempFactory,
                    realPathOperations(), systemClock(), 1_000L,
                    CropSpec.full(), baseParameters(-1))
                    .dispatch(sweep(CropSpec.full()), result -> { },
                            new ThrowOnCheckSupplier(4, callbackFailure));
            fail("Expected cancellation callback failure");
        } catch (RuntimeException actual) {
            assertSame(callbackFailure, actual);
        }

        assertEquals(1, worker.submitCalls);
        assertEquals(1, worker.neverFuture.cancelCalls);
        assertEquals(1, worker.closeCalls);
        assertEquals(0, adapter.previewRuns);
        assertBefore(events, "request-cancel", "worker-close");
        assertSingleTreeRemoved(tempFactory);
    }

    @Test
    public void callbackFailureWhilePublishingMacroFailureKeepsMacroPrimary()
            throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        FakeWorker worker = FakeWorker.success();
        FixedWorkerFactory workerFactory = new FixedWorkerFactory(worker);
        TrackingTempFactory tempFactory = tempFactory();
        RuntimeException callbackFailure =
                new RuntimeException("cancel callback failed during error publication");

        try {
            strategy(adapter, workerFactory, tempFactory,
                    realPathOperations(), systemClock(), 1_000L,
                    CropSpec.full(), baseParameters(-1))
                    .dispatch(macroFailureSweep(), result -> { },
                            new ThrowOnCheckSupplier(3, callbackFailure));
            fail("Expected macro preprocessing failure");
        } catch (Exception actual) {
            assertTrue(actual.getMessage().contains("Macro preprocessing failed"));
            assertTrue("callback failure was not retained under the macro primary",
                    containsIdentity(actual.getSuppressed(), callbackFailure));
        }

        assertEquals(0, workerFactory.openCalls);
        assertTrue(tempFactory.created.isEmpty());
        assertEquals(0, adapter.previewRuns);
    }

    @Test
    public void primaryFailureKeepsCleanupFailureAsSuppressedDiagnostic()
            throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        IllegalStateException primary = new IllegalStateException("request failed");
        Exception closeFailure = new Exception("worker close failed");
        FakeWorker worker = FakeWorker.throwing(primary);
        worker.closeFailure = closeFailure;
        TrackingTempFactory tempFactory = tempFactory();

        try {
            strategy(adapter, new FixedWorkerFactory(worker), tempFactory,
                    realPathOperations(), systemClock(), 1_000L,
                    CropSpec.full(), baseParameters(-1))
                    .dispatch(sweep(CropSpec.full()), result -> { }, () -> false);
            fail("Expected the request failure");
        } catch (IllegalStateException actual) {
            assertSame(primary, actual);
            assertTrue("cleanup diagnostic was not attached",
                    containsMessage(actual.getSuppressed(), "worker close failed"));
        }

        assertEquals(1, worker.closeCalls);
        assertSingleTreeRemoved(tempFactory);
    }

    @Test
    public void fatalCleanupIsPromotedAfterSiblingTemporaryCleanup() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        IllegalStateException primary = new IllegalStateException("request failed");
        LinkageError fatal = new LinkageError("fatal worker teardown");
        FakeWorker worker = FakeWorker.throwing(primary);
        worker.fatalCloseFailure = fatal;
        TrackingTempFactory tempFactory = tempFactory();

        try {
            strategy(adapter, new FixedWorkerFactory(worker), tempFactory,
                    realPathOperations(), systemClock(), 1_000L,
                    CropSpec.full(), baseParameters(-1))
                    .dispatch(sweep(CropSpec.full()), result -> { }, () -> false);
            fail("Expected fatal teardown failure");
        } catch (LinkageError actual) {
            assertSame(fatal, actual);
            assertTrue("ordinary primary was not retained on the fatal failure",
                    containsIdentity(actual.getSuppressed(), primary));
        }

        assertEquals(1, worker.closeCalls);
        assertSingleTreeRemoved(tempFactory);
    }

    @Test
    public void directoryWalkClosesBeforeDeletionAndAfterWorkerSession()
            throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        List<String> events = new ArrayList<String>();
        FakeWorker worker = FakeWorker.success(events);
        TrackingTempFactory tempFactory = tempFactory();
        RecordingPathOperations pathOperations =
                new RecordingPathOperations(events);

        strategy(adapter, new FixedWorkerFactory(worker), tempFactory,
                pathOperations, systemClock(), 1_000L,
                CropSpec.full(), baseParameters(-1))
                .dispatch(sweep(CropSpec.full()), result -> { }, () -> false);

        assertEquals(1, worker.closeCalls);
        assertBefore(events, "worker-close", "walk-close");
        assertBefore(events, "walk-close", "delete-root");
        assertSingleTreeRemoved(tempFactory);
    }

    @Test
    public void directoryStreamCloseFailureIsDurableAfterTreeDeletion()
            throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        FakeWorker worker = FakeWorker.success();
        TrackingTempFactory tempFactory = tempFactory();
        FailingStreamClosePathOperations pathOperations =
                new FailingStreamClosePathOperations();

        try {
            strategy(adapter, new FixedWorkerFactory(worker), tempFactory,
                    pathOperations, systemClock(), 1_000L,
                    CropSpec.full(), baseParameters(-1))
                    .dispatch(sweep(CropSpec.full()), result -> { }, () -> false);
            fail("Expected directory stream cleanup failure");
        } catch (Exception expected) {
            assertTrue(containsMessage(new Throwable[] { expected },
                    "stream close failed"));
        }

        assertTrue(pathOperations.streamClosed);
        assertEquals(1, worker.closeCalls);
        assertSingleTreeRemoved(tempFactory);
    }

    @Test
    public void companionCleanupFailureStillClosesCroppedCompanionAndSourceInLifoOrder()
            throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        adapter.fullCompanion = image("companion-full", 4, 1);
        adapter.failWhenClosing = adapter.fullCompanion;
        FakeWorker worker = FakeWorker.success();
        TrackingTempFactory tempFactory = tempFactory();
        CropSpec crop = CropSpec.custom(new Rectangle(0, 0, 2, 1));

        try {
            strategy(adapter, new FixedWorkerFactory(worker), tempFactory,
                    realPathOperations(), systemClock(), 1_000L,
                    crop, baseParameters(0))
                    .dispatch(sweep(crop), result -> { }, () -> false);
            fail("Expected companion cleanup failure");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("full Cellpose companion"));
        }

        int fullIndex = adapter.closedImages.indexOf(adapter.fullCompanion);
        int croppedCompanionIndex = adapter.indexOfClosedCompanionCrop();
        int croppedSourceIndex = adapter.indexOfClosedSourceCrop();
        assertTrue("cropped companion was not closed", croppedCompanionIndex >= 0);
        assertTrue("full companion close was not attempted", fullIndex >= 0);
        assertTrue("cropped source was not closed", croppedSourceIndex >= 0);
        assertTrue("companion resources did not close in LIFO order",
                croppedCompanionIndex < fullIndex);
        assertTrue("cleanup stopped before the cropped source sibling",
                fullIndex < croppedSourceIndex);
        assertSingleTreeRemoved(tempFactory);
    }

    @Test
    public void publisherFailureClosesUnpublishedLabelBeforeWorker() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        List<String> events = new ArrayList<String>();
        FakeWorker worker = FakeWorker.success(events);
        worker.adapter = adapter;
        TrackingTempFactory tempFactory = tempFactory();
        RuntimeException primary = new RuntimeException("publisher failed");

        try {
            strategy(adapter, new FixedWorkerFactory(worker), tempFactory,
                    realPathOperations(), systemClock(), 1_000L,
                    CropSpec.full(), baseParameters(-1))
                    .dispatch(sweep(CropSpec.full()), result -> {
                        throw primary;
                    }, () -> false);
            fail("Expected publisher failure");
        } catch (RuntimeException actual) {
            assertSame(primary, actual);
        }

        assertTrue("unpublished label was not closed", adapter.closedLabelCount > 0);
        assertEquals(1, worker.closeCalls);
        assertSingleTreeRemoved(tempFactory);
    }

    @Test
    public void workerInputAliasIsRejectedWithoutClosingSharedSource() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        FakeWorker worker = FakeWorker.referenceAlias();
        TrackingTempFactory tempFactory = tempFactory();

        try {
            strategy(adapter, new FixedWorkerFactory(worker), tempFactory,
                    realPathOperations(), systemClock(), 1_000L,
                    CropSpec.full(), baseParameters(-1))
                    .dispatch(sweep(CropSpec.full()), result -> { }, () -> false);
            fail("Expected shared worker-label rejection.");
        } catch (IllegalStateException actual) {
            assertTrue(actual.getMessage().contains("distinct owned label"));
        }

        assertTrue("Shared source must not be closed as an unpublished label",
                adapter.closedImages.isEmpty());
        assertEquals(1, worker.closeCalls);
        assertSingleTreeRemoved(tempFactory);
    }

    @Test
    public void workerCompanionAliasIsRejectedAndCompanionClosesExactlyOnce()
            throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        adapter.fullCompanion = image("companion-alias", 4, 1);
        FakeWorker worker = FakeWorker.fixedLabel(adapter.fullCompanion);
        TrackingTempFactory tempFactory = tempFactory();

        try {
            strategy(adapter, new FixedWorkerFactory(worker), tempFactory,
                    realPathOperations(), systemClock(), 1_000L,
                    CropSpec.full(), baseParameters(0))
                    .dispatch(sweep(CropSpec.full()), result -> { }, () -> false);
            fail("Expected shared worker-label rejection.");
        } catch (IllegalStateException actual) {
            assertTrue(actual.getMessage().contains("distinct owned label"));
        }

        int companionCloseCalls = 0;
        for (int i = 0; i < adapter.closedImages.size(); i++) {
            if (adapter.closedImages.get(i) == adapter.fullCompanion) {
                companionCloseCalls++;
            }
        }
        assertEquals(1, companionCloseCalls);
        assertEquals(1, worker.closeCalls);
        assertSingleTreeRemoved(tempFactory);
    }

    private CellposePersistent strategy(RecordingPreviewAdapter adapter,
                                        CellposePersistent.WorkerFactory factory,
                                        TrackingTempFactory tempFactory,
                                        CellposePersistent.PathOperations pathOperations,
                                        CellposePersistent.NanoClock clock,
                                         long timeoutMillis,
                                         CropSpec crop,
                                         CellposeParameterStage.Parameters parameters) {
        return strategy(adapter, factory, tempFactory, pathOperations, clock,
                timeoutMillis, crop, parameters, null);
    }

    private CellposePersistent strategy(RecordingPreviewAdapter adapter,
                                         CellposePersistent.WorkerFactory factory,
                                         TrackingTempFactory tempFactory,
                                         CellposePersistent.PathOperations pathOperations,
                                         CellposePersistent.NanoClock clock,
                                         long timeoutMillis,
                                         CropSpec crop,
                                         CellposeParameterStage.Parameters parameters,
                                         VariationCache cache) {
        return new CellposePersistent(sourceImage(),
                crop,
                cache,
                adapter,
                parameters,
                null,
                "DAPI",
                factory,
                tempFactory,
                pathOperations,
                clock,
                timeoutMillis);
    }

    private TrackingTempFactory tempFactory() {
        return new TrackingTempFactory(temp.getRoot().toPath());
    }

    private static ParameterSweep sweep(CropSpec crop) {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.DIAMETER, ParameterValueList.ofDoubles(20.0d, 30.0d));
        values.put(ParameterId.FLOW_THRESHOLD, ParameterValueList.ofDoubles(0.4d));
        values.put(ParameterId.CELLPROB_THRESHOLD, ParameterValueList.ofDoubles(0.0d));
        return new ParameterSweep(ParameterSweep.Method.CELLPOSE,
                values,
                crop,
                "DAPI",
                "synthetic");
    }

    private static ParameterSweep macroFailureSweep() {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.DIAMETER, ParameterValueList.ofDoubles(20.0d, 30.0d));
        values.put(ParameterId.FLOW_THRESHOLD, ParameterValueList.ofDoubles(0.4d));
        values.put(ParameterId.CELLPROB_THRESHOLD, ParameterValueList.ofDoubles(0.0d));
        values.put(ParameterId.MACRO,
                ParameterValueList.ofStrings("macro:adhoc:missing"));
        return new ParameterSweep(ParameterSweep.Method.CELLPOSE,
                values,
                CropSpec.full(),
                "DAPI",
                "synthetic");
    }

    private static CellposeParameterStage.Parameters baseParameters(int companionIndex) {
        return new CellposeParameterStage.Parameters(
                "cyto3",
                companionIndex,
                30.0d,
                0.4d,
                0.0d,
                false);
    }

    private static ImagePlus sourceImage() {
        return image("source", 4, 1);
    }

    private static ImagePlus image(String title, int width, int height) {
        return new ImagePlus(title, new ByteProcessor(width, height));
    }

    private static ImagePlus labelImage() {
        return labelImage(4, 1);
    }

    private static ImagePlus labelImage(int width, int height) {
        ShortProcessor processor = new ShortProcessor(width, height);
        processor.set(0, 0, 1);
        if (width > 1) {
            processor.set(1, 0, 1);
        }
        if (width > 2) {
            processor.set(2, 0, 2);
        }
        if (width > 3) {
            processor.set(3, 0, 2);
        }
        return new ImagePlus("labels", processor);
    }

    private static CellposePersistent.PathOperations realPathOperations() {
        return new CellposePersistent.PathOperations() {
            @Override public Stream<Path> walk(Path root) throws Exception {
                return Files.walk(root);
            }

            @Override public void deleteIfExists(Path path) throws Exception {
                Files.deleteIfExists(path);
            }
        };
    }

    private static CellposePersistent.NanoClock systemClock() {
        return new CellposePersistent.NanoClock() {
            @Override public long nanoTime() {
                return System.nanoTime();
            }
        };
    }

    private static void assertSingleTreeRemoved(TrackingTempFactory factory) {
        assertEquals(1, factory.created.size());
        assertFalse("temporary Cellpose tree remained: " + factory.created.get(0),
                Files.exists(factory.created.get(0)));
    }

    private static void assertBefore(List<String> events, String first, String second) {
        int firstIndex = events.indexOf(first);
        int secondIndex = events.indexOf(second);
        assertTrue("missing event " + first + " in " + events, firstIndex >= 0);
        assertTrue("missing event " + second + " in " + events, secondIndex >= 0);
        assertTrue(first + " must precede " + second + ": " + events,
                firstIndex < secondIndex);
    }

    private static boolean containsMessage(Throwable[] failures, String text) {
        for (int i = 0; failures != null && i < failures.length; i++) {
            Throwable failure = failures[i];
            if (failure != null && failure.getMessage() != null
                    && failure.getMessage().contains(text)) {
                return true;
            }
            if (failure != null && containsMessage(
                    failure.getSuppressed(), text)) {
                return true;
            }
            if (failure != null && failure.getCause() != failure
                    && containsMessage(new Throwable[] { failure.getCause() }, text)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsIdentity(Throwable[] failures, Throwable expected) {
        for (int i = 0; failures != null && i < failures.length; i++) {
            if (failures[i] == expected) {
                return true;
            }
        }
        return false;
    }

    private static final class TrackingTempFactory
            implements CellposePersistent.TempDirectoryFactory {
        final Path parent;
        final List<Path> created = new ArrayList<Path>();

        TrackingTempFactory(Path parent) {
            this.parent = parent;
        }

        @Override public Path create() throws Exception {
            Path path = Files.createTempDirectory(parent, "cellpose-session-");
            created.add(path);
            return path;
        }
    }

    private static final class DeadlineClock implements CellposePersistent.NanoClock {
        int calls;

        @Override public long nanoTime() {
            return calls++ == 0 ? 0L : TimeUnit.SECONDS.toNanos(1L);
        }
    }

    private static final class ThrowOnCheckSupplier implements BooleanSupplier {
        final int throwingCheck;
        final RuntimeException failure;
        int checks;

        ThrowOnCheckSupplier(int throwingCheck, RuntimeException failure) {
            this.throwingCheck = throwingCheck;
            this.failure = failure;
        }

        @Override public boolean getAsBoolean() {
            checks++;
            if (checks == throwingCheck) {
                throw failure;
            }
            return false;
        }
    }

    private static final class CancelOnCheckSupplier implements BooleanSupplier {
        private final int cancelOn;
        private int checks;

        CancelOnCheckSupplier(int cancelOn) {
            this.cancelOn = cancelOn;
        }

        @Override public boolean getAsBoolean() {
            checks++;
            return checks >= cancelOn;
        }
    }

    private static final class FixedWorkerFactory
            implements CellposePersistent.WorkerFactory {
        final FakeWorker worker;
        RuntimeException openFailure;
        int openCalls;

        FixedWorkerFactory(FakeWorker worker) {
            this.worker = worker;
        }

        @Override public CellposePersistent.WorkerHandle open(
                Path imagePath,
                Path outputDir,
                ImagePlus referenceInput,
                ImagePlus runtimeInput,
                String model,
                boolean useGpu,
                String channelName,
                File projectRoot) {
            openCalls++;
            if (openFailure != null) {
                throw openFailure;
            }
            worker.referenceInput = referenceInput;
            return worker;
        }
    }

    private static final class FakeWorker implements CellposePersistent.WorkerHandle {
        enum Mode {
            SUCCESS,
            RESULT_FAILURE,
            NEVER,
            INTERRUPT,
            THROW
        }

        final Mode mode;
        final List<String> events;
        final RuntimeException submitFailure;
        NeverFuture neverFuture;
        Exception closeFailure;
        Error fatalCloseFailure;
        RecordingPreviewAdapter adapter;
        ImagePlus referenceInput;
        boolean returnReferenceInput;
        ImagePlus fixedLabel;
        int submitCalls;
        int closeCalls;

        private FakeWorker(Mode mode,
                           RuntimeException submitFailure,
                           List<String> events) {
            this.mode = mode;
            this.submitFailure = submitFailure;
            this.events = events == null ? new ArrayList<String>() : events;
            if (mode == Mode.NEVER) {
                neverFuture = new NeverFuture(this.events);
            }
        }

        static FakeWorker success() {
            return success(new ArrayList<String>());
        }

        static FakeWorker success(List<String> events) {
            return new FakeWorker(Mode.SUCCESS, null, events);
        }

        static FakeWorker referenceAlias() {
            FakeWorker worker = success();
            worker.returnReferenceInput = true;
            return worker;
        }

        static FakeWorker fixedLabel(ImagePlus label) {
            FakeWorker worker = success();
            worker.fixedLabel = label;
            return worker;
        }

        static FakeWorker resultFailure() {
            return new FakeWorker(Mode.RESULT_FAILURE, null, null);
        }

        static FakeWorker never(List<String> events) {
            return new FakeWorker(Mode.NEVER, null, events);
        }

        static FakeWorker interrupting() {
            return new FakeWorker(Mode.INTERRUPT, null, null);
        }

        static FakeWorker throwing(RuntimeException failure) {
            return new FakeWorker(Mode.THROW, failure, null);
        }

        @Override public Future<CellposeWorkerResult> submit(
                CellposeWorkerRequest request) {
            submitCalls++;
            if (mode == Mode.THROW) {
                throw submitFailure;
            }
            if (mode == Mode.NEVER) {
                return neverFuture;
            }
            if (mode == Mode.INTERRUPT) {
                return new InterruptingFuture();
            }
            if (mode == Mode.RESULT_FAILURE) {
                return CompletableFuture.completedFuture(
                        CellposeWorkerResult.failure(request.id(), "synthetic failure"));
            }
            ImagePlus label;
            if (returnReferenceInput) {
                label = referenceInput;
            } else if (fixedLabel != null) {
                label = fixedLabel;
            } else {
                label = referenceInput == null
                        ? labelImage()
                        : labelImage(referenceInput.getWidth(), referenceInput.getHeight());
            }
            if (adapter != null) {
                adapter.workerLabels.add(label);
            }
            return CompletableFuture.completedFuture(
                    CellposeWorkerResult.success(request.id(), label, 1L));
        }

        @Override public void close() throws Exception {
            closeCalls++;
            events.add("worker-close");
            if (fatalCloseFailure != null) {
                throw fatalCloseFailure;
            }
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    private static final class NeverFuture implements Future<CellposeWorkerResult> {
        final List<String> events;
        boolean done;
        int cancelCalls;

        NeverFuture(List<String> events) {
            this.events = events;
        }

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            cancelCalls++;
            done = true;
            events.add("request-cancel");
            return true;
        }

        @Override public boolean isCancelled() {
            return done;
        }

        @Override public boolean isDone() {
            return done;
        }

        @Override public CellposeWorkerResult get()
                throws InterruptedException, ExecutionException {
            throw new AssertionError("untimed get must not be used");
        }

        @Override public CellposeWorkerResult get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            throw new TimeoutException("still running");
        }
    }

    private static final class InterruptingFuture
            implements Future<CellposeWorkerResult> {
        boolean done;

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            done = true;
            return true;
        }

        @Override public boolean isCancelled() {
            return done;
        }

        @Override public boolean isDone() {
            return done;
        }

        @Override public CellposeWorkerResult get()
                throws InterruptedException, ExecutionException {
            throw new InterruptedException("synthetic interruption");
        }

        @Override public CellposeWorkerResult get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            throw new InterruptedException("synthetic interruption");
        }
    }

    private static final class RecordingPathOperations
            implements CellposePersistent.PathOperations {
        final List<String> events;
        Path root;
        boolean streamClosed;

        RecordingPathOperations(List<String> events) {
            this.events = events;
        }

        @Override public Stream<Path> walk(Path root) throws Exception {
            this.root = root;
            return Files.walk(root).onClose(() -> {
                streamClosed = true;
                events.add("walk-close");
            });
        }

        @Override public void deleteIfExists(Path path) throws Exception {
            if (path.equals(root)) {
                assertTrue("root deletion began while directory stream was open",
                        streamClosed);
                events.add("delete-root");
            }
            Files.deleteIfExists(path);
        }
    }

    private static final class FailingStreamClosePathOperations
            implements CellposePersistent.PathOperations {
        boolean streamClosed;

        @Override public Stream<Path> walk(Path root) throws Exception {
            return Files.walk(root).onClose(() -> {
                streamClosed = true;
                throw new IllegalStateException("stream close failed");
            });
        }

        @Override public void deleteIfExists(Path path) throws Exception {
            Files.deleteIfExists(path);
        }
    }

    private static final class RecordingPreviewAdapter
            implements CellposeParameterStage.PreviewAdapter {
        int previewRuns;
        int closedLabelCount;
        ImagePlus fullCompanion;
        Exception companionFailure;
        ImagePlus failWhenClosing;
        final List<ImagePlus> closedImages = new ArrayList<ImagePlus>();
        final List<ImagePlus> workerLabels = new ArrayList<ImagePlus>();

        @Override public ImagePlus createRawSource(ConfigQcContext context) {
            return null;
        }

        @Override public ImagePlus createFilteredSource(ConfigQcContext context) {
            return null;
        }

        @Override public ImagePlus createFilteredCompanionSource(ConfigQcContext context,
                                                                 int channelIndex) throws Exception {
            if (companionFailure != null) {
                throw companionFailure;
            }
            return fullCompanion;
        }

        @Override public ImagePlus runPreview(ImagePlus filteredSource,
                                              ImagePlus filteredCompanionSource,
                                              CellposeParameterStage.Parameters parameters) {
            previewRuns++;
            return labelImage();
        }

        @Override public int countLabels(ImagePlus labelImage) {
            return labelImage == null ? 0
                    : (int) labelImage.getProcessor().getStats().max;
        }

        @Override public void close(ImagePlus image) {
            if (image == null) {
                return;
            }
            closedImages.add(image);
            if (workerLabels.contains(image)) {
                closedLabelCount++;
            }
            if (image == failWhenClosing) {
                throw new IllegalStateException("full companion close failed");
            }
        }

        int indexOfClosedCompanionCrop() {
            for (int i = 0; i < closedImages.size(); i++) {
                ImagePlus image = closedImages.get(i);
                if (image != fullCompanion
                        && "companion-full".equals(image.getTitle())) {
                    return i;
                }
            }
            return -1;
        }

        int indexOfClosedSourceCrop() {
            for (int i = 0; i < closedImages.size(); i++) {
                ImagePlus image = closedImages.get(i);
                if ("source".equals(image.getTitle())
                        && image.getWidth() == 2
                        && image.getNChannels() == 1) {
                    return i;
                }
            }
            return -1;
        }
    }
}
