package flash.pipeline.ui.variations.strategy;

import flash.pipeline.cellpose.Cellpose3DRunner;
import flash.pipeline.segmentation.SegmentationMethod;
import flash.pipeline.ui.config.CellposeParameterStage;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.preview.ObjectSizeFilterPreview;
import flash.pipeline.ui.variations.CropSpec;
import flash.pipeline.ui.variations.MacroPreprocessor;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterId;
import flash.pipeline.ui.variations.ParameterKey;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.ParameterValueList;
import flash.pipeline.ui.variations.VariationCache;
import flash.pipeline.ui.variations.VariationResult;
import flash.pipeline.ui.variations.VariationStrategy;

import ij.ImagePlus;
import ij.measure.ResultsTable;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class CellposeOneShot implements VariationStrategy {

    private final ImagePlus filteredSource;
    private final CropSpec crop;
    interface CacheAccess {
        ImagePlus get(String key);
        void put(String key, ImagePlus label);
    }

    private final CacheAccess cache;
    private final CellposeParameterStage.PreviewAdapter previewAdapter;
    private final CellposeParameterStage.Parameters baseParams;
    private final ConfigQcContext configContext;
    private final MacroPreprocessor macroPreprocessor = new MacroPreprocessor();

    public CellposeOneShot(ImagePlus filteredSource,
                           CropSpec crop,
                           VariationCache cache,
                           CellposeParameterStage.PreviewAdapter previewAdapter,
                           CellposeParameterStage.Parameters baseParams,
                           ConfigQcContext configContext) {
        this(filteredSource, crop, cacheAccess(cache), previewAdapter,
                baseParams, configContext, true);
    }

    CellposeOneShot(ImagePlus filteredSource,
                    CropSpec crop,
                    VariationCache ignored,
                    CellposeParameterStage.PreviewAdapter previewAdapter,
                    CellposeParameterStage.Parameters baseParams,
                    ConfigQcContext configContext,
                    CacheAccess cacheAccess) {
        this(filteredSource, crop, cacheAccess, previewAdapter,
                baseParams, configContext, true);
    }

    private CellposeOneShot(ImagePlus filteredSource,
                            CropSpec crop,
                            CacheAccess cache,
                            CellposeParameterStage.PreviewAdapter previewAdapter,
                            CellposeParameterStage.Parameters baseParams,
                            ConfigQcContext configContext,
                            boolean internal) {
        if (filteredSource == null) {
            throw new IllegalArgumentException("filteredSource must not be null");
        }
        if (previewAdapter == null) {
            throw new IllegalArgumentException("previewAdapter must not be null");
        }
        if (baseParams == null) {
            throw new IllegalArgumentException("baseParams must not be null");
        }
        this.filteredSource = filteredSource;
        this.crop = crop == null ? CropSpec.full() : crop;
        this.cache = cache;
        this.previewAdapter = previewAdapter;
        this.baseParams = baseParams;
        this.configContext = configContext;
    }

    @Override
    public void dispatch(ParameterSweep sweep,
                         Consumer<VariationResult> publisher,
                         BooleanSupplier cancelCheck) throws Exception {
        dispatchCombos(sweep, SweepDispatchOrder.order(sweep), publisher, cancelCheck);
    }

    void dispatchCombos(ParameterSweep sweep,
                        List<ParameterCombo> ordered,
                        Consumer<VariationResult> publisher,
                        BooleanSupplier cancelCheck) throws Exception {
        validate(sweep, publisher);
        CropSpec activeCrop = sweep.cropSpec() == null ? crop : sweep.cropSpec();
        ImagePlus cropped = activeCrop.apply(filteredSource);
        ImagePlus companion = null;
        Throwable primaryFailure = null;
        try {
            companion = createCroppedCompanion(activeCrop);
            for (int i = 0; i < ordered.size(); i++) {
                if (isCancelled(cancelCheck)) {
                    break;
                }
                ParameterCombo combo = ordered.get(i);
                CellposeParameterStage.Parameters parameters = overlay(baseParams, combo);
                String cacheKey = VariationCache.keyFor(sweep, combo);
                ImagePlus cached = cache == null ? null : cache.get(cacheKey);
                if (cached != null) {
                    publisher.accept(resultFor(combo, cached, cropped, 0L));
                    continue;
                }
                runOne(cropped, sweep, companionFor(parameters, companion), combo, cacheKey,
                        parameters, publisher, cancelCheck);
            }
        } catch (Throwable t) {
            primaryFailure = promotedFatal(t);
            if (primaryFailure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        primaryFailure = closeOwned(companion, filteredSource, cropped, primaryFailure);
        primaryFailure = closeOwned(cropped, filteredSource, null, primaryFailure);
        if (primaryFailure != null) {
            throwFailure(primaryFailure);
        }
    }

    private void validate(ParameterSweep sweep, Consumer<VariationResult> publisher) {
        if (sweep == null) {
            throw new IllegalArgumentException("sweep must not be null");
        }
        if (sweep.method() != ParameterSweep.Method.CELLPOSE) {
            throw new IllegalArgumentException("CellposeOneShot only accepts Cellpose sweeps");
        }
        if (publisher == null) {
            throw new IllegalArgumentException("publisher must not be null");
        }
    }

    private void runOne(ImagePlus cropped,
                        ParameterSweep sweep,
                        ImagePlus companion,
                        ParameterCombo combo,
                        String cacheKey,
                        CellposeParameterStage.Parameters parameters,
                        Consumer<VariationResult> publisher,
                        BooleanSupplier cancelCheck) throws Exception {
        CancellationProbe cancellation = new CancellationProbe(cancelCheck);
        if (cancellation.isCancelled()) {
            return;
        }
        long started = System.currentTimeMillis();
        ImagePlus input = null;
        ImagePlus label = null;
        boolean labelTransferred = false;
        boolean propagateFailure = false;
        boolean cleanupFailed = false;
        boolean cancelled = false;
        Throwable primaryFailure = null;
        try {
            input = macroPreprocessor.prepare(cropped, sweep, combo);
            label = previewAdapter.runPreview(input, companion, parameters);
            if (label == null) {
                throw new IllegalStateException("Cellpose returned no label map.");
            }
            requireOwnedLabel(label, input, cropped, companion);
            long durationMs = Math.max(1L, System.currentTimeMillis() - started);
            VariationResult result = resultFor(combo, label, input, durationMs);
            // Failures from this point are infrastructure/callback failures and must
            // propagate after cleanup rather than being converted into a cell result.
            propagateFailure = true;
            if (cancellation.isCancelled()) {
                cancelled = true;
            } else {
                if (cache != null) {
                    cache.put(cacheKey, result.label());
                    labelTransferred = true;
                }
                if (cancellation.isCancelled()) {
                    cancelled = true;
                } else {
                    publisher.accept(result);
                    labelTransferred = true;
                }
            }
        } catch (Throwable t) {
            primaryFailure = promotedFatal(t);
            if (cancellation.failedWith(t)) {
                propagateFailure = true;
            }
            if (primaryFailure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                propagateFailure = true;
                cancelled = true;
            }
        }

        if (!labelTransferred && label != companion) {
            Throwable beforeCleanup = primaryFailure;
            int suppressedBeforeCleanup = suppressedCount(primaryFailure);
            primaryFailure = closeOwned(label, input, cropped, primaryFailure);
            cleanupFailed |= primaryFailure != beforeCleanup
                    || suppressedCount(primaryFailure) > suppressedBeforeCleanup;
        }
        try {
            macroPreprocessor.closeIfOwned(input, cropped);
        } catch (Throwable cleanupFailure) {
            cleanupFailed = true;
            primaryFailure = mergeFailures(primaryFailure,
                    promotedFatal(cleanupFailure));
        }

        if (primaryFailure == null) {
            return;
        }
        if (cleanupFailed || propagateFailure || cancelled
                || isVmFatal(primaryFailure)
                || Thread.currentThread().isInterrupted()) {
            throwFailure(primaryFailure);
        }
        try {
            if (!cancellation.isCancelled()) {
                publisher.accept(VariationResult.failure(combo, primaryFailure));
            }
        } catch (Throwable publicationFailure) {
            throwFailure(mergeFailures(primaryFailure,
                    promotedFatal(publicationFailure)));
        }
    }

    private VariationResult resultFor(ParameterCombo combo,
                                      ImagePlus label,
                                      ImagePlus reference,
                                      long durationMs) {
        ResultsTable stats = ObjectSizeFilterPreview.statisticsFromLabelMap(label, reference);
        int count = label == null ? 0 : previewAdapter.countLabels(label);
        return VariationResult.success(combo, label, count, durationMs, stats);
    }

    private ImagePlus createCroppedCompanion(CropSpec activeCrop) throws Exception {
        if (baseParams.secondChannelIndex < 0) {
            return null;
        }
        ImagePlus full = null;
        ImagePlus cropped = null;
        try {
            full = previewAdapter.createFilteredCompanionSource(
                    configContext, baseParams.secondChannelIndex);
            if (full == null) {
                return null;
            }
            cropped = activeCrop.apply(full);
        } catch (Throwable acquisitionFailure) {
            boolean interrupted = acquisitionFailure instanceof InterruptedException;
            Throwable outcome = promotedFatal(acquisitionFailure);
            Throwable beforeCleanup = outcome;
            int suppressedBeforeCleanup = suppressedCount(outcome);
            outcome = closeOwned(cropped, filteredSource, full, outcome);
            boolean cleanupFailed = outcome != beforeCleanup
                    || suppressedCount(outcome) > suppressedBeforeCleanup;
            beforeCleanup = outcome;
            suppressedBeforeCleanup = suppressedCount(outcome);
            outcome = closeOwned(full, filteredSource, cropped, outcome);
            cleanupFailed |= outcome != beforeCleanup
                    || suppressedCount(outcome) > suppressedBeforeCleanup;
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (interrupted || cleanupFailed || isVmFatal(outcome)) {
                throwFailure(outcome);
            }
            return null;
        }

        if (cropped != full && full != filteredSource) {
            Throwable cleanupFailure = closeOwned(full, filteredSource, cropped, null);
            if (cleanupFailure != null) {
                cleanupFailure = closeOwned(cropped, filteredSource, full, cleanupFailure);
                throwFailure(cleanupFailure);
            }
        }
        return cropped;
    }

    private static ImagePlus companionFor(CellposeParameterStage.Parameters parameters,
                                          ImagePlus companion) {
        return parameters != null && parameters.secondChannelIndex >= 0
                ? companion
                : null;
    }

    static boolean sweepsModel(ParameterSweep sweep) {
        if (sweep == null) {
            return false;
        }
        Map<ParameterKey, ParameterValueList> values = sweep.valueLists();
        ParameterValueList modelValues = values.get(ParameterId.MODEL);
        return modelValues != null && modelValues.size() > 1;
    }

    static CellposeParameterStage.Parameters overlay(
            CellposeParameterStage.Parameters base,
            ParameterCombo combo) {
        CellposeParameterStage.Parameters p = base == null
                ? CellposeParameterStage.parseMethod(null)
                : base;
        String model = stringParameter(combo, ParameterId.MODEL, p.modelToken);
        return new CellposeParameterStage.Parameters(
                SegmentationMethod.canonicalCellposeModelKey(model),
                p.secondChannelIndex,
                doubleParameter(combo, ParameterId.DIAMETER, p.diameter),
                doubleParameter(combo, ParameterId.FLOW_THRESHOLD, p.flowThreshold),
                doubleParameter(combo, ParameterId.CELLPROB_THRESHOLD,
                        p.cellprobThreshold),
                p.useGpu);
    }

    static double doubleParameter(ParameterCombo combo,
                                  ParameterId id,
                                  double fallback) {
        Object value = combo == null ? null : combo.get(id);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            double parsed = Double.parseDouble(String.valueOf(value));
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String stringParameter(ParameterCombo combo,
                                          ParameterId id,
                                          String fallback) {
        Object value = combo == null ? null : combo.get(id);
        String text = value == null ? fallback : String.valueOf(value);
        return text == null || text.trim().isEmpty() ? fallback : text.trim();
    }

    private Throwable closeOwned(ImagePlus image,
                                 ImagePlus firstOwner,
                                 ImagePlus secondOwner,
                                 Throwable primaryFailure) {
        if (image == null || image == firstOwner || image == secondOwner) {
            return primaryFailure;
        }
        try {
            previewAdapter.close(image);
        } catch (Throwable cleanupFailure) {
            return mergeFailures(primaryFailure, promotedFatal(cleanupFailure));
        }
        return primaryFailure;
    }

    private static boolean isCancelled(BooleanSupplier cancelCheck) {
        return Thread.currentThread().isInterrupted()
                || (cancelCheck != null && cancelCheck.getAsBoolean());
    }

    private static void requireOwnedLabel(ImagePlus label,
                                          ImagePlus input,
                                          ImagePlus cropped,
                                          ImagePlus companion) {
        if (label == input || label == cropped || label == companion) {
            throw new IllegalStateException("Cellpose preview returned a shared input as its "
                    + "label map; expected a distinct owned label image.");
        }
    }

    private static CacheAccess cacheAccess(final VariationCache cache) {
        if (cache == null) return null;
        return new CacheAccess() {
            @Override public ImagePlus get(String key) {
                return cache.get(key);
            }

            @Override public void put(String key, ImagePlus label) {
                cache.put(key, label);
            }
        };
    }

    private static final class CancellationProbe {
        private final BooleanSupplier supplier;
        private Throwable failure;

        CancellationProbe(BooleanSupplier supplier) {
            this.supplier = supplier;
        }

        boolean isCancelled() {
            if (Thread.currentThread().isInterrupted()) return true;
            if (supplier == null) return false;
            try {
                return supplier.getAsBoolean();
            } catch (RuntimeException e) {
                failure = promotedFatal(e);
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

    private static Throwable promotedFatal(Throwable failure) {
        Throwable fatal = findVmFatal(failure,
                new IdentityHashMap<Throwable, Boolean>());
        return fatal == null ? failure : fatal;
    }

    private static Throwable findVmFatal(Throwable failure,
                                         IdentityHashMap<Throwable, Boolean> seen) {
        if (failure == null || seen.put(failure, Boolean.TRUE) != null) return null;
        if (isVmFatal(failure)) return failure;
        Throwable fatal = findVmFatal(failure.getCause(), seen);
        if (fatal != null) return fatal;
        Throwable[] suppressed = failure.getSuppressed();
        for (int i = 0; i < suppressed.length; i++) {
            fatal = findVmFatal(suppressed[i], seen);
            if (fatal != null) return fatal;
        }
        return null;
    }

    private static boolean isVmFatal(Throwable failure) {
        return failure instanceof VirtualMachineError
                || failure instanceof ThreadDeath
                || failure instanceof LinkageError;
    }

    private static Throwable mergeFailures(Throwable primary, Throwable secondary) {
        if (primary == null) return secondary;
        if (secondary == null || secondary == primary) return primary;
        if (isVmFatal(secondary) && !isVmFatal(primary)) {
            addSuppressed(secondary, primary);
            return secondary;
        }
        addSuppressed(primary, secondary);
        return primary;
    }

    private static void addSuppressed(Throwable primary, Throwable secondary) {
        try {
            primary.addSuppressed(secondary);
        } catch (Throwable ignored) {
            // Best effort only; never replace either failure while recording diagnostics.
        }
    }

    private static int suppressedCount(Throwable failure) {
        if (failure == null) return 0;
        try {
            return failure.getSuppressed().length;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static void throwFailure(Throwable failure) throws Exception {
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
        throw new RuntimeException(failure);
    }
}
