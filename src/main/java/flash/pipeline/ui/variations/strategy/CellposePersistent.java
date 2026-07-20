package flash.pipeline.ui.variations.strategy;

import flash.pipeline.cellpose.Cellpose3DRunner;
import flash.pipeline.cellpose.CellposePersistentWorker;
import flash.pipeline.cellpose.CellposeWorkerRequest;
import flash.pipeline.cellpose.CellposeWorkerResult;
import flash.pipeline.ui.config.CellposeParameterStage;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.preview.ObjectSizeFilterPreview;
import flash.pipeline.ui.variations.CropSpec;
import flash.pipeline.ui.variations.MacroPreprocessor;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.VariationCache;
import flash.pipeline.ui.variations.VariationResult;
import flash.pipeline.ui.variations.VariationStrategy;

import ij.ImagePlus;
import ij.measure.ResultsTable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;

public final class CellposePersistent implements VariationStrategy {

    private static final long DEFAULT_REQUEST_TIMEOUT_MILLIS =
            TimeUnit.MINUTES.toMillis(30L);
    private static final long REQUEST_POLL_MILLIS = 100L;

    interface WorkerHandle {
        Future<CellposeWorkerResult> submit(CellposeWorkerRequest request);

        void close() throws Exception;
    }

    interface WorkerFactory {
        WorkerHandle open(Path imagePath,
                          Path outputDir,
                          ImagePlus referenceInput,
                          ImagePlus runtimeInput,
                          String model,
                          boolean useGpu,
                          String channelName,
                          File projectRoot) throws Exception;
    }

    interface TempDirectoryFactory {
        Path create() throws Exception;
    }

    interface PathOperations {
        Stream<Path> walk(Path root) throws Exception;

        void deleteIfExists(Path path) throws Exception;
    }

    interface NanoClock {
        long nanoTime();
    }

    private final ImagePlus filteredSource;
    private final CropSpec crop;
    private final VariationCache cache;
    private final CellposeParameterStage.PreviewAdapter previewAdapter;
    private final CellposeParameterStage.Parameters baseParams;
    private final ConfigQcContext configContext;
    private final String channelName;
    private final WorkerFactory workerFactory;
    private final TempDirectoryFactory tempDirectoryFactory;
    private final PathOperations pathOperations;
    private final NanoClock clock;
    private final long requestTimeoutNanos;
    private final MacroPreprocessor macroPreprocessor = new MacroPreprocessor();

    public CellposePersistent(ImagePlus filteredSource,
                              CropSpec crop,
                              VariationCache cache,
                              CellposeParameterStage.PreviewAdapter previewAdapter,
                              CellposeParameterStage.Parameters baseParams,
                              ConfigQcContext configContext,
                              String channelName) {
        this(filteredSource, crop, cache, previewAdapter, baseParams,
                configContext, channelName, defaultWorkerFactory(),
                defaultTempDirectoryFactory(), defaultPathOperations(),
                systemNanoClock(), DEFAULT_REQUEST_TIMEOUT_MILLIS);
    }

    CellposePersistent(ImagePlus filteredSource,
                       CropSpec crop,
                       VariationCache cache,
                       CellposeParameterStage.PreviewAdapter previewAdapter,
                       CellposeParameterStage.Parameters baseParams,
                       ConfigQcContext configContext,
                       String channelName,
                       WorkerFactory workerFactory) {
        this(filteredSource, crop, cache, previewAdapter, baseParams,
                configContext, channelName, workerFactory,
                defaultTempDirectoryFactory(), defaultPathOperations(),
                systemNanoClock(), DEFAULT_REQUEST_TIMEOUT_MILLIS);
    }

    CellposePersistent(ImagePlus filteredSource,
                       CropSpec crop,
                       VariationCache cache,
                       CellposeParameterStage.PreviewAdapter previewAdapter,
                       CellposeParameterStage.Parameters baseParams,
                       ConfigQcContext configContext,
                       String channelName,
                       WorkerFactory workerFactory,
                       TempDirectoryFactory tempDirectoryFactory,
                       PathOperations pathOperations,
                       NanoClock clock,
                       long requestTimeoutMillis) {
        if (filteredSource == null) {
            throw new IllegalArgumentException("filteredSource must not be null");
        }
        if (previewAdapter == null) {
            throw new IllegalArgumentException("previewAdapter must not be null");
        }
        if (baseParams == null) {
            throw new IllegalArgumentException("baseParams must not be null");
        }
        if (workerFactory == null) {
            throw new IllegalArgumentException("workerFactory must not be null");
        }
        if (tempDirectoryFactory == null) {
            throw new IllegalArgumentException("tempDirectoryFactory must not be null");
        }
        if (pathOperations == null) {
            throw new IllegalArgumentException("pathOperations must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        if (requestTimeoutMillis <= 0L) {
            throw new IllegalArgumentException("requestTimeoutMillis must be positive");
        }
        this.filteredSource = filteredSource;
        this.crop = crop == null ? CropSpec.full() : crop;
        this.cache = cache;
        this.previewAdapter = previewAdapter;
        this.baseParams = baseParams;
        this.configContext = configContext;
        this.channelName = channelName == null ? "" : channelName;
        this.workerFactory = workerFactory;
        this.tempDirectoryFactory = tempDirectoryFactory;
        this.pathOperations = pathOperations;
        this.clock = clock;
        this.requestTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(requestTimeoutMillis);
    }

    @Override
    public void dispatch(ParameterSweep sweep,
                         Consumer<VariationResult> publisher,
                         BooleanSupplier cancelCheck) throws Exception {
        validate(sweep, publisher);
        if (CellposeOneShot.sweepsModel(sweep)) {
            oneShot().dispatch(sweep, publisher, cancelCheck);
            return;
        }

        CancellationProbe cancellation = new CancellationProbe(cancelCheck);
        CropSpec activeCrop = sweep.cropSpec() == null ? crop : sweep.cropSpec();
        final ResourceScope resources = new ResourceScope();
        List<ParameterCombo> fallbackCombos = null;
        Throwable primaryFailure = null;
        List<ParameterCombo> ordered = SweepDispatchOrder.order(sweep);
        try {
            final ImagePlus cropped = activeCrop.apply(filteredSource);
            if (cropped != null && cropped != filteredSource) {
                resources.own("cropped Cellpose source", new CleanupAction() {
                    @Override public void close() {
                        previewAdapter.close(cropped);
                    }
                });
            }
            final ImagePlus companion = createCroppedCompanion(
                    activeCrop, cropped, resources);
            List<MacroGroup> groups = groupByMacro(ordered);
            for (int i = 0; i < groups.size(); i++) {
                if (cancellation.isCancelled()) {
                    break;
                }
                fallbackCombos = processGroup(sweep,
                        cropped,
                        companion,
                        groups,
                        i,
                        publisher,
                        cancellation);
                if (fallbackCombos != null) {
                    break;
                }
            }
        } catch (Throwable t) {
            primaryFailure = promotedFatal(t);
            if (primaryFailure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }

        CleanupReport cleanup = resources.close();
        Throwable outcome = mergeFailures(primaryFailure, cleanup.failure);
        if (outcome != null) {
            throwFailure(outcome);
        }

        if (fallbackCombos != null && !cancellation.isCancelled()) {
            oneShot().dispatchCombos(sweep, fallbackCombos, publisher, cancelCheck);
        }
    }

    private List<ParameterCombo> processGroup(ParameterSweep sweep,
                                              ImagePlus cropped,
                                              ImagePlus companion,
                                              List<MacroGroup> groups,
                                              int groupIndex,
                                              Consumer<VariationResult> publisher,
                                              CancellationProbe cancellation)
            throws Exception {
        MacroGroup group = groups.get(groupIndex);
        List<PendingCombo> pending = new ArrayList<PendingCombo>();
        for (int i = 0; i < group.items.size(); i++) {
            IndexedCombo item = group.items.get(i);
            String cacheKey = VariationCache.keyFor(sweep, item.combo);
            ImagePlus cached = cache == null ? null : cache.get(cacheKey);
            if (cached != null) {
                VariationResult cachedResult = resultFor(item.combo, cached, cropped, 0L);
                if (cancellation.isCancelled()) {
                    return null;
                }
                publisher.accept(cachedResult);
            } else {
                pending.add(new PendingCombo(item.index, item.combo, cacheKey));
            }
        }
        if (pending.isEmpty() || cancellation.isCancelled()) {
            return null;
        }

        final ResourceScope resources = new ResourceScope();
        Throwable primaryFailure = null;
        List<ParameterCombo> fallback = null;
        boolean cancelled = false;
        FailurePolicy failurePolicy = FailurePolicy.PUBLISH_FAILURES;
        int pendingIndex = 0;
        try {
            final ImagePlus input = macroPreprocessor.prepare(
                    cropped, sweep, pending.get(0).combo);
            if (input != null && input != cropped) {
                resources.own("macro-preprocessed Cellpose input", new CleanupAction() {
                    @Override public void close() {
                        macroPreprocessor.closeIfOwned(input, cropped);
                    }
                });
            }
            CellposeParameterStage.Parameters firstParameters =
                    CellposeOneShot.overlay(baseParams, pending.get(0).combo);
            failurePolicy = FailurePolicy.FALLBACK;
            WorkerHandle worker = openWorker(
                    input, cropped, companion, firstParameters, resources);
            for (pendingIndex = 0; pendingIndex < pending.size(); pendingIndex++) {
                if (cancellation.isCancelled()) {
                    cancelled = true;
                    break;
                }
                PendingCombo item = pending.get(pendingIndex);
                CellposeParameterStage.Parameters parameters =
                        CellposeOneShot.overlay(baseParams, item.combo);
                CellposeWorkerResult result = submitAndAwait(worker,
                        new CellposeWorkerRequest(requestId(item.index, item.cacheKey),
                                parameters.diameter,
                                parameters.flowThreshold,
                                parameters.cellprobThreshold),
                        cancellation);
                if (result == null) {
                    cancelled = true;
                    break;
                }
                if (result.hasError()) {
                    String message = result.errorText();
                    throw new IllegalStateException(message);
                }
                final ImagePlus label = result.labelImage();
                failurePolicy = FailurePolicy.PROPAGATE;
                requireOwnedLabel(label, input, cropped, companion);
                final boolean[] labelTransferred = new boolean[] { false };
                if (label != null && label != input && label != cropped
                        && label != companion) {
                    resources.own("unpublished Cellpose label", new CleanupAction() {
                        @Override public void close() {
                            if (!labelTransferred[0]) {
                                previewAdapter.close(label);
                            }
                        }
                    });
                }
                VariationResult variationResult = resultFor(
                        item.combo, label, input, result.durationMs());
                failurePolicy = FailurePolicy.PROPAGATE;
                if (cancellation.isCancelled()) {
                    cancelled = true;
                    break;
                }
                if (cache != null) {
                    cache.put(item.cacheKey, variationResult.label());
                    labelTransferred[0] = true;
                }
                if (!cancellation.isCancelled()) {
                    publisher.accept(variationResult);
                    labelTransferred[0] = true;
                } else {
                    cancelled = true;
                    break;
                }
                failurePolicy = FailurePolicy.FALLBACK;
            }
        } catch (Throwable t) {
            boolean callbackFailure = cancellation.failedWith(t);
            primaryFailure = promotedFatal(t);
            if (callbackFailure) {
                failurePolicy = FailurePolicy.PROPAGATE;
            }
            if (primaryFailure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // Do not invoke user cancellation code while handling another failure:
            // a throwing supplier here would bypass this scope's mandatory cleanup.
            cancelled = Thread.currentThread().isInterrupted()
                    || primaryFailure instanceof InterruptedException;
            if (!cancelled && !isVmFatal(primaryFailure)
                    && failurePolicy == FailurePolicy.FALLBACK) {
                fallback = fallbackCombos(
                        groups, groupIndex, pending, pendingIndex);
            }
        }

        CleanupReport cleanup = resources.close();
        Throwable outcome = mergeFailures(primaryFailure, cleanup.failure);
        if (cleanup.failed || isVmFatal(outcome)) {
            throwFailure(outcome);
        }
        if (primaryFailure != null && !cancelled) {
            if (fallback != null) {
                return fallback;
            }
            if (failurePolicy == FailurePolicy.PUBLISH_FAILURES) {
                try {
                    publishFailures(pending, primaryFailure, publisher, cancellation);
                } catch (Throwable publicationFailure) {
                    throwFailure(mergeFailures(primaryFailure,
                            promotedFatal(publicationFailure)));
                }
                return null;
            }
            throwFailure(primaryFailure);
        }
        return null;
    }

    private void validate(ParameterSweep sweep, Consumer<VariationResult> publisher) {
        if (sweep == null) {
            throw new IllegalArgumentException("sweep must not be null");
        }
        if (sweep.method() != ParameterSweep.Method.CELLPOSE) {
            throw new IllegalArgumentException("CellposePersistent only accepts Cellpose sweeps");
        }
        if (publisher == null) {
            throw new IllegalArgumentException("publisher must not be null");
        }
    }

    private WorkerHandle openWorker(final ImagePlus input,
                                   final ImagePlus cropped,
                                   final ImagePlus companion,
                                   CellposeParameterStage.Parameters parameters,
                                   ResourceScope resources)
            throws Exception {
        final Path tempDir = tempDirectoryFactory.create();
        resources.own("Cellpose temporary tree " + tempDir, new CleanupAction() {
            @Override public void close() throws Throwable {
                deleteRecursively(tempDir);
            }
        });
        final ImagePlus runtimeInput = Cellpose3DRunner.prepareRuntimeInput(
                input, companionFor(parameters, companion), channelName);
        if (runtimeInput != null && runtimeInput != input
                && runtimeInput != cropped && runtimeInput != companion) {
            resources.own("Cellpose runtime input", new CleanupAction() {
                @Override public void close() {
                    previewAdapter.close(runtimeInput);
                }
            });
        }
        Path inputPath = Cellpose3DRunner.writeInputStack(runtimeInput, tempDir);
        final WorkerHandle worker = workerFactory.open(inputPath,
                tempDir,
                input,
                runtimeInput,
                parameters.modelToken,
                parameters.useGpu,
                channelName,
                projectRoot());
        if (worker == null) {
            throw new IllegalStateException("Cellpose worker factory returned no session.");
        }
        resources.own("Cellpose persistent worker/model session", new CleanupAction() {
            @Override public void close() throws Exception {
                worker.close();
            }
        });
        return worker;
    }

    private CellposeWorkerResult submitAndAwait(WorkerHandle worker,
                                                CellposeWorkerRequest request,
                                                CancellationProbe cancellation)
            throws Exception {
        final ResourceScope requestResources = new ResourceScope();
        Throwable primaryFailure = null;
        CellposeWorkerResult result = null;
        try {
            final Future<CellposeWorkerResult> future = worker.submit(request);
            if (future == null) {
                throw new IllegalStateException(
                        "Cellpose worker returned no request future.");
            }
            requestResources.own("Cellpose request", new CleanupAction() {
                @Override public void close() {
                    if (!future.isDone()) {
                        future.cancel(true);
                    }
                }
            });
            result = await(future, cancellation);
        } catch (Throwable t) {
            primaryFailure = promotedFatal(t);
            if (primaryFailure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        CleanupReport cleanup = requestResources.close();
        Throwable outcome = mergeFailures(primaryFailure, cleanup.failure);
        if (outcome != null) {
            throwFailure(outcome);
        }
        return result;
    }

    private CellposeWorkerResult await(Future<CellposeWorkerResult> future,
                                       CancellationProbe cancellation)
            throws Exception {
        long started = clock.nanoTime();
        while (true) {
            if (cancellation.isCancelled()) {
                return null;
            }
            long elapsed = clock.nanoTime() - started;
            long remaining = requestTimeoutNanos - Math.max(0L, elapsed);
            if (remaining <= 0L) {
                throw new TimeoutException("Cellpose helper request timed out after "
                        + TimeUnit.NANOSECONDS.toMillis(requestTimeoutNanos)
                        + " ms.");
            }
            long waitMillis = TimeUnit.NANOSECONDS.toMillis(remaining);
            if (waitMillis <= 0L) {
                waitMillis = 1L;
            }
            waitMillis = Math.min(REQUEST_POLL_MILLIS, waitMillis);
            try {
                return future.get(waitMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                // Poll so cancellation can close the helper promptly.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
    }

    private VariationResult resultFor(ParameterCombo combo,
                                      ImagePlus label,
                                      ImagePlus reference,
                                      long durationMs) {
        ResultsTable stats = ObjectSizeFilterPreview.statisticsFromLabelMap(label, reference);
        int count = label == null ? 0 : Cellpose3DRunner.countLabels(label);
        return VariationResult.success(combo, label, count, durationMs, stats);
    }

    private static void requireOwnedLabel(ImagePlus label,
                                          ImagePlus input,
                                          ImagePlus cropped,
                                          ImagePlus companion) {
        if (label == input || label == cropped || label == companion) {
            throw new IllegalStateException("Persistent Cellpose worker returned a shared input "
                    + "as its label map; expected a distinct owned label image.");
        }
    }

    private ImagePlus createCroppedCompanion(CropSpec activeCrop,
                                             ImagePlus croppedSource,
                                             ResourceScope resources)
            throws Exception {
        if (baseParams.secondChannelIndex < 0) {
            return null;
        }
        try {
            final ImagePlus full = previewAdapter.createFilteredCompanionSource(
                    configContext, baseParams.secondChannelIndex);
            if (full == null) {
                return null;
            }
            if (full != filteredSource && full != croppedSource) {
                resources.own("full Cellpose companion", new CleanupAction() {
                    @Override public void close() {
                        previewAdapter.close(full);
                    }
                });
            }
            final ImagePlus cropped = activeCrop.apply(full);
            if (cropped != null && cropped != full
                    && cropped != filteredSource && cropped != croppedSource) {
                resources.own("cropped Cellpose companion", new CleanupAction() {
                    @Override public void close() {
                        previewAdapter.close(cropped);
                    }
                });
            }
            return cropped;
        } catch (Throwable t) {
            Throwable outcome = promotedFatal(t);
            if (outcome instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throwFailure(outcome);
            }
            if (isVmFatal(outcome)) {
                throwFailure(outcome);
            }
        }
        // Companion creation is optional. Preserve the existing single-channel fallback
        // when acquisition itself fails. The caller's scope still owns and closes any
        // partially acquired full/cropped images in strict reverse order.
        return null;
    }

    private CellposeOneShot oneShot() {
        return new CellposeOneShot(filteredSource,
                crop,
                cache,
                previewAdapter,
                baseParams,
                configContext);
    }

    private File projectRoot() {
        return configContext == null ? null : configContext.getProjectDirectory();
    }

    private static ImagePlus companionFor(CellposeParameterStage.Parameters parameters,
                                          ImagePlus companion) {
        return parameters != null && parameters.secondChannelIndex >= 0
                ? companion
                : null;
    }

    private static String requestId(int index, String cacheKey) {
        return "v" + index + "_" + (cacheKey == null ? "cellpose" : cacheKey);
    }

    private static List<MacroGroup> groupByMacro(List<ParameterCombo> ordered) {
        LinkedHashMap<String, MacroGroup> byToken =
                new LinkedHashMap<String, MacroGroup>();
        if (ordered == null) {
            return new ArrayList<MacroGroup>();
        }
        for (int i = 0; i < ordered.size(); i++) {
            ParameterCombo combo = ordered.get(i);
            String token = MacroPreprocessor.macroToken(combo);
            MacroGroup group = byToken.get(token);
            if (group == null) {
                group = new MacroGroup();
                byToken.put(token, group);
            }
            group.items.add(new IndexedCombo(i, combo));
        }
        return new ArrayList<MacroGroup>(byToken.values());
    }

    private static List<ParameterCombo> fallbackCombos(List<MacroGroup> groups,
                                                       int groupIndex,
                                                       List<PendingCombo> pending,
                                                       int pendingIndex) {
        List<ParameterCombo> out = new ArrayList<ParameterCombo>();
        for (int i = Math.max(0, pendingIndex); pending != null && i < pending.size(); i++) {
            out.add(pending.get(i).combo);
        }
        for (int i = groupIndex + 1; groups != null && i < groups.size(); i++) {
            MacroGroup group = groups.get(i);
            for (int j = 0; j < group.items.size(); j++) {
                out.add(group.items.get(j).combo);
            }
        }
        return out;
    }

    private static void publishFailures(List<PendingCombo> pending,
                                        Throwable error,
                                        Consumer<VariationResult> publisher,
                                        CancellationProbe cancellation) {
        if (pending == null || publisher == null || cancellation.isCancelled()) {
            return;
        }
        for (int i = 0; i < pending.size(); i++) {
            if (cancellation.isCancelled()) {
                return;
            }
            publisher.accept(VariationResult.failure(pending.get(i).combo, error));
        }
    }

    private static WorkerFactory defaultWorkerFactory() {
        return new WorkerFactory() {
            @Override public WorkerHandle open(Path imagePath,
                                               Path outputDir,
                                               ImagePlus referenceInput,
                                               ImagePlus runtimeInput,
                                               String model,
                                               boolean useGpu,
                                               String channelName,
                                               File projectRoot) throws Exception {
                final CellposePersistentWorker worker = new CellposePersistentWorker(
                        imagePath, outputDir, referenceInput, runtimeInput, model,
                        useGpu, channelName, projectRoot);
                return new WorkerHandle() {
                    @Override public Future<CellposeWorkerResult> submit(
                            CellposeWorkerRequest request) {
                        return worker.submit(request);
                    }

                    @Override public void close() {
                        worker.close();
                    }
                };
            }
        };
    }

    private static TempDirectoryFactory defaultTempDirectoryFactory() {
        return new TempDirectoryFactory() {
            @Override public Path create() throws Exception {
                return Files.createTempDirectory("flash-cellpose-variations-");
            }
        };
    }

    private static PathOperations defaultPathOperations() {
        return new PathOperations() {
            @Override public Stream<Path> walk(Path root) throws Exception {
                return Files.walk(root);
            }

            @Override public void deleteIfExists(Path path) throws Exception {
                Files.deleteIfExists(path);
            }
        };
    }

    private static NanoClock systemNanoClock() {
        return new NanoClock() {
            @Override public long nanoTime() {
                return System.nanoTime();
            }
        };
    }

    private static final class MacroGroup {
        final List<IndexedCombo> items = new ArrayList<IndexedCombo>();

        MacroGroup() {
        }
    }

    private static final class IndexedCombo {
        final int index;
        final ParameterCombo combo;

        IndexedCombo(int index, ParameterCombo combo) {
            this.index = index;
            this.combo = combo;
        }
    }

    private static final class PendingCombo {
        final int index;
        final ParameterCombo combo;
        final String cacheKey;

        PendingCombo(int index, ParameterCombo combo, String cacheKey) {
            this.index = index;
            this.combo = combo;
            this.cacheKey = cacheKey;
        }
    }

    private enum FailurePolicy {
        PUBLISH_FAILURES,
        FALLBACK,
        PROPAGATE
    }

    private static final class CancellationProbe {
        private final BooleanSupplier supplier;
        private Throwable failure;

        CancellationProbe(BooleanSupplier supplier) {
            this.supplier = supplier;
        }

        boolean isCancelled() {
            if (Thread.currentThread().isInterrupted()) {
                return true;
            }
            if (supplier == null) {
                return false;
            }
            try {
                return supplier.getAsBoolean();
            } catch (RuntimeException e) {
                failure = promotedFatal(e);
                if (failure instanceof Error) {
                    throw (Error) failure;
                }
                throw e;
            } catch (Error e) {
                failure = promotedFatal(e);
                throw (Error) failure;
            }
        }

        boolean failedWith(Throwable candidate) {
            return failure != null && failure == candidate;
        }
    }

    private void deleteRecursively(Path root) throws Throwable {
        if (root == null || Files.notExists(root)) {
            return;
        }
        List<Path> paths = new ArrayList<Path>();
        Throwable failure = null;
        try {
            try (Stream<Path> stream = pathOperations.walk(root)) {
                if (stream == null) {
                    throw new IllegalStateException(
                            "Directory walk returned no stream for " + root);
                }
                stream.sorted(Comparator.reverseOrder()).forEach(paths::add);
            }
        } catch (Throwable t) {
            if (!(t instanceof NoSuchFileException)) {
                failure = promotedFatal(t);
            }
        }

        // The walk stream is closed before any deletion. This is required on
        // Windows, where the open directory handle otherwise blocks root removal.
        for (int i = 0; i < paths.size(); i++) {
            Path path = paths.get(i);
            if (root.equals(path)) {
                continue;
            }
            try {
                pathOperations.deleteIfExists(path);
            } catch (Throwable t) {
                failure = mergeFailures(failure,
                        cleanupDiagnostic("temporary path " + path, t));
            }
        }

        Throwable firstRootFailure = null;
        try {
            pathOperations.deleteIfExists(root);
        } catch (Throwable t) {
            firstRootFailure = promotedFatal(t);
        }
        if (firstRootFailure != null && !isVmFatal(firstRootFailure)) {
            // Retry only after the walk stream and every child handle are closed.
            try {
                pathOperations.deleteIfExists(root);
                firstRootFailure = null;
            } catch (Throwable retryFailure) {
                firstRootFailure = mergeFailures(firstRootFailure,
                        cleanupDiagnostic("temporary root retry " + root,
                                retryFailure));
            }
        }
        if (firstRootFailure != null) {
            failure = mergeFailures(failure,
                    cleanupDiagnostic("temporary root " + root,
                            firstRootFailure));
        }
        if (failure != null) {
            throw failure;
        }
    }

    private interface CleanupAction {
        void close() throws Throwable;
    }

    private static final class OwnedCleanup {
        final String description;
        final CleanupAction action;

        OwnedCleanup(String description, CleanupAction action) {
            this.description = description;
            this.action = action;
        }
    }

    private static final class CleanupReport {
        final Throwable failure;
        final boolean failed;

        CleanupReport(Throwable failure, boolean failed) {
            this.failure = failure;
            this.failed = failed;
        }
    }

    private static final class ResourceScope {
        private final List<OwnedCleanup> resources = new ArrayList<OwnedCleanup>();
        private boolean closed;

        synchronized void own(String description, CleanupAction action) {
            if (closed) {
                throw new IllegalStateException("Cannot acquire a resource after cleanup.");
            }
            if (action == null) {
                throw new IllegalArgumentException("cleanup action must not be null");
            }
            resources.add(new OwnedCleanup(description, action));
        }

        synchronized CleanupReport close() {
            if (closed) {
                return new CleanupReport(null, false);
            }
            closed = true;
            Throwable failure = null;
            boolean failed = false;
            for (int i = resources.size() - 1; i >= 0; i--) {
                OwnedCleanup resource = resources.get(i);
                try {
                    resource.action.close();
                } catch (Throwable t) {
                    failed = true;
                    failure = mergeFailures(failure,
                            cleanupDiagnostic(resource.description, t));
                }
            }
            resources.clear();
            return new CleanupReport(failure, failed);
        }
    }

    private static final class CleanupDiagnostic extends Exception {
        CleanupDiagnostic(String description, Throwable cause) {
            super("Failed to close " + description + ": "
                    + messageFor(cause), cause);
        }
    }

    private static Throwable cleanupDiagnostic(String description, Throwable failure) {
        Throwable promoted = promotedFatal(failure);
        if (isVmFatal(promoted)) {
            return promoted;
        }
        return new CleanupDiagnostic(description, promoted);
    }

    private static Throwable promotedFatal(Throwable failure) {
        Throwable fatal = findVmFatal(failure,
                new IdentityHashMap<Throwable, Boolean>());
        return fatal == null ? failure : fatal;
    }

    private static Throwable findVmFatal(Throwable failure,
                                         IdentityHashMap<Throwable, Boolean> seen) {
        if (failure == null || seen.put(failure, Boolean.TRUE) != null) {
            return null;
        }
        if (isVmFatal(failure)) {
            return failure;
        }
        Throwable fatal = findVmFatal(failure.getCause(), seen);
        if (fatal != null) {
            return fatal;
        }
        Throwable[] suppressed = failure.getSuppressed();
        for (int i = 0; suppressed != null && i < suppressed.length; i++) {
            fatal = findVmFatal(suppressed[i], seen);
            if (fatal != null) {
                return fatal;
            }
        }
        return null;
    }

    private static boolean isVmFatal(Throwable failure) {
        return failure instanceof VirtualMachineError
                || failure instanceof ThreadDeath
                || failure instanceof LinkageError;
    }

    private static Throwable mergeFailures(Throwable primary, Throwable secondary) {
        if (primary == null) {
            return secondary;
        }
        if (secondary == null || secondary == primary) {
            return primary;
        }
        if (isVmFatal(secondary) && !isVmFatal(primary)) {
            addSuppressed(secondary, primary);
            return secondary;
        }
        addSuppressed(primary, secondary);
        return primary;
    }

    private static void addSuppressed(Throwable primary, Throwable secondary) {
        if (primary == null || secondary == null || primary == secondary) {
            return;
        }
        try {
            primary.addSuppressed(secondary);
        } catch (RuntimeException ignored) {
            // A malformed/self-suppression diagnostic must not prevent sibling cleanup.
        }
    }

    private static String messageFor(Throwable failure) {
        if (failure == null) {
            return "unknown cleanup failure";
        }
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty()
                ? failure.getClass().getSimpleName()
                : message.trim();
    }

    private static void throwFailure(Throwable failure) throws Exception {
        if (failure == null) {
            return;
        }
        if (failure instanceof Exception) {
            throw (Exception) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw new RuntimeException(failure);
    }
}
