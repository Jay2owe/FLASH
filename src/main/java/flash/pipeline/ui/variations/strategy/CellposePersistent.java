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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class CellposePersistent implements VariationStrategy {

    interface WorkerFactory {
        CellposePersistentWorker open(Path imagePath,
                                      Path outputDir,
                                      ImagePlus referenceInput,
                                      ImagePlus runtimeInput,
                                      String model,
                                      boolean useGpu,
                                      String channelName,
                                      File projectRoot) throws Exception;
    }

    private final ImagePlus filteredSource;
    private final CropSpec crop;
    private final VariationCache cache;
    private final CellposeParameterStage.PreviewAdapter previewAdapter;
    private final CellposeParameterStage.Parameters baseParams;
    private final ConfigQcContext configContext;
    private final String channelName;
    private final WorkerFactory workerFactory;
    private final MacroPreprocessor macroPreprocessor = new MacroPreprocessor();

    public CellposePersistent(ImagePlus filteredSource,
                              CropSpec crop,
                              VariationCache cache,
                              CellposeParameterStage.PreviewAdapter previewAdapter,
                              CellposeParameterStage.Parameters baseParams,
                              ConfigQcContext configContext,
                              String channelName) {
        this(filteredSource, crop, cache, previewAdapter, baseParams,
                configContext, channelName, defaultWorkerFactory());
    }

    CellposePersistent(ImagePlus filteredSource,
                       CropSpec crop,
                       VariationCache cache,
                       CellposeParameterStage.PreviewAdapter previewAdapter,
                       CellposeParameterStage.Parameters baseParams,
                       ConfigQcContext configContext,
                       String channelName,
                       WorkerFactory workerFactory) {
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
        this.filteredSource = filteredSource;
        this.crop = crop == null ? CropSpec.full() : crop;
        this.cache = cache;
        this.previewAdapter = previewAdapter;
        this.baseParams = baseParams;
        this.configContext = configContext;
        this.channelName = channelName == null ? "" : channelName;
        this.workerFactory = workerFactory;
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

        CropSpec activeCrop = sweep.cropSpec() == null ? crop : sweep.cropSpec();
        ImagePlus cropped = activeCrop.apply(filteredSource);
        ImagePlus companion = null;
        List<ParameterCombo> ordered = SweepDispatchOrder.order(sweep);
        List<ParameterCombo> fallbackCombos = null;
        try {
            companion = createCroppedCompanion(activeCrop);
            List<MacroGroup> groups = groupByMacro(ordered);
            for (int i = 0; i < groups.size(); i++) {
                if (isCancelled(cancelCheck)) {
                    return;
                }
                fallbackCombos = processGroup(sweep,
                        cropped,
                        companion,
                        groups,
                        i,
                        publisher,
                        cancelCheck);
                if (fallbackCombos != null) {
                    break;
                }
            }
        } finally {
            closeIfOwned(companion, filteredSource, cropped);
            closeIfOwned(cropped, filteredSource, null);
        }

        if (fallbackCombos != null && !isCancelled(cancelCheck)) {
            oneShot().dispatchCombos(sweep, fallbackCombos, publisher, cancelCheck);
        }
    }

    private List<ParameterCombo> processGroup(ParameterSweep sweep,
                                              ImagePlus cropped,
                                              ImagePlus companion,
                                              List<MacroGroup> groups,
                                              int groupIndex,
                                              Consumer<VariationResult> publisher,
                                              BooleanSupplier cancelCheck)
            throws Exception {
        MacroGroup group = groups.get(groupIndex);
        List<PendingCombo> pending = new ArrayList<PendingCombo>();
        for (int i = 0; i < group.items.size(); i++) {
            IndexedCombo item = group.items.get(i);
            String cacheKey = VariationCache.keyFor(sweep, item.combo);
            ImagePlus cached = cache == null ? null : cache.get(cacheKey);
            if (cached != null) {
                publisher.accept(resultFor(item.combo, cached, cropped, 0L));
            } else {
                pending.add(new PendingCombo(item.index, item.combo, cacheKey));
            }
        }
        if (pending.isEmpty() || isCancelled(cancelCheck)) {
            return null;
        }

        ImagePlus input = null;
        WorkerState worker = null;
        try {
            input = macroPreprocessor.prepare(cropped, sweep, pending.get(0).combo);
        } catch (Exception e) {
            publishFailures(pending, e, publisher, cancelCheck);
            return null;
        }

        int pendingIndex = 0;
        try {
            CellposeParameterStage.Parameters firstParameters =
                    CellposeOneShot.overlay(baseParams, pending.get(0).combo);
            worker = openWorker(input, companion, firstParameters);
            for (pendingIndex = 0; pendingIndex < pending.size(); pendingIndex++) {
                if (isCancelled(cancelCheck)) {
                    return null;
                }
                PendingCombo item = pending.get(pendingIndex);
                CellposeParameterStage.Parameters parameters =
                        CellposeOneShot.overlay(baseParams, item.combo);
                CellposeWorkerResult result = await(worker.worker.submit(
                                new CellposeWorkerRequest(requestId(item.index, item.cacheKey),
                                        parameters.diameter,
                                        parameters.flowThreshold,
                                        parameters.cellprobThreshold)),
                        cancelCheck);
                if (result == null || result.hasError()) {
                    String message = result == null
                            ? "Cellpose helper was cancelled."
                            : result.errorText();
                    throw new IllegalStateException(message);
                }
                VariationResult variationResult = resultFor(item.combo,
                        result.labelImage(), input, result.durationMs());
                if (cache != null) {
                    cache.put(item.cacheKey, variationResult.label());
                }
                if (!isCancelled(cancelCheck)) {
                    publisher.accept(variationResult);
                }
            }
            return null;
        } catch (Exception e) {
            return fallbackCombos(groups, groupIndex, pending, pendingIndex);
        } finally {
            if (worker != null) {
                worker.close();
            }
            macroPreprocessor.closeIfOwned(input, cropped);
        }
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

    private WorkerState openWorker(ImagePlus cropped,
                                   ImagePlus companion,
                                   CellposeParameterStage.Parameters parameters)
            throws Exception {
        Path tempDir = Files.createTempDirectory("flash-cellpose-variations-");
        ImagePlus runtimeInput = null;
        try {
            runtimeInput = Cellpose3DRunner.prepareRuntimeInput(
                    cropped, companionFor(parameters, companion), channelName);
            Path inputPath = Cellpose3DRunner.writeInputStack(runtimeInput, tempDir);
            CellposePersistentWorker worker = workerFactory.open(inputPath,
                    tempDir,
                    cropped,
                    runtimeInput,
                    parameters.modelToken,
                    parameters.useGpu,
                    channelName,
                    projectRoot());
            return new WorkerState(worker, tempDir, runtimeInput, cropped, companion);
        } catch (Exception e) {
            if (runtimeInput != null && runtimeInput != cropped
                    && runtimeInput != companion) {
                previewAdapter.close(runtimeInput);
            }
            deleteRecursively(tempDir);
            throw e;
        }
    }

    private CellposeWorkerResult await(Future<CellposeWorkerResult> future,
                                       BooleanSupplier cancelCheck)
            throws Exception {
        while (true) {
            if (isCancelled(cancelCheck)) {
                future.cancel(true);
                return null;
            }
            try {
                return future.get(100L, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                // Poll so cancellation can close the helper promptly.
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

    private ImagePlus createCroppedCompanion(CropSpec activeCrop) {
        if (baseParams.secondChannelIndex < 0) {
            return null;
        }
        ImagePlus full = null;
        try {
            full = previewAdapter.createFilteredCompanionSource(
                    configContext, baseParams.secondChannelIndex);
            if (full == null) {
                return null;
            }
            ImagePlus cropped = activeCrop.apply(full);
            if (cropped != full) {
                previewAdapter.close(full);
            }
            return cropped;
        } catch (Exception e) {
            if (full != null) {
                previewAdapter.close(full);
            }
            return null;
        }
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
                                        BooleanSupplier cancelCheck) {
        if (pending == null || publisher == null || isCancelled(cancelCheck)) {
            return;
        }
        for (int i = 0; i < pending.size(); i++) {
            if (isCancelled(cancelCheck)) {
                return;
            }
            publisher.accept(VariationResult.failure(pending.get(i).combo, error));
        }
    }

    private void closeIfOwned(ImagePlus image,
                              ImagePlus firstOwner,
                              ImagePlus secondOwner) {
        if (image == null || image == firstOwner || image == secondOwner) {
            return;
        }
        previewAdapter.close(image);
    }

    private static boolean isCancelled(BooleanSupplier cancelCheck) {
        return Thread.currentThread().isInterrupted()
                || (cancelCheck != null && cancelCheck.getAsBoolean());
    }

    private static WorkerFactory defaultWorkerFactory() {
        return new WorkerFactory() {
            @Override public CellposePersistentWorker open(Path imagePath,
                                                           Path outputDir,
                                                           ImagePlus referenceInput,
                                                           ImagePlus runtimeInput,
                                                           String model,
                                                           boolean useGpu,
                                                           String channelName,
                                                           File projectRoot) throws Exception {
                return new CellposePersistentWorker(imagePath, outputDir,
                        referenceInput, runtimeInput, model, useGpu, channelName,
                        projectRoot);
            }
        };
    }

    private final class WorkerState {
        final CellposePersistentWorker worker;
        final Path tempDir;
        final ImagePlus runtimeInput;
        final ImagePlus cropped;
        final ImagePlus companion;

        WorkerState(CellposePersistentWorker worker,
                    Path tempDir,
                    ImagePlus runtimeInput,
                    ImagePlus cropped,
                    ImagePlus companion) {
            this.worker = worker;
            this.tempDir = tempDir;
            this.runtimeInput = runtimeInput;
            this.cropped = cropped;
            this.companion = companion;
        }

        void close() {
            try {
                worker.close();
            } catch (Exception ignored) {
            }
            if (runtimeInput != null && runtimeInput != cropped
                    && runtimeInput != companion) {
                previewAdapter.close(runtimeInput);
            }
            deleteRecursively(tempDir);
        }
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

    private static void deleteRecursively(Path root) {
        if (root == null) {
            return;
        }
        try {
            Files.walk(root)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception ignored) {
        }
    }
}
