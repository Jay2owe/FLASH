package flash.pipeline.ui.variations.strategy;

import flash.pipeline.deconv.engine.DeconvSettings;
import flash.pipeline.image.WindowManagerLock;
import flash.pipeline.ui.variations.DeconvComboSettings;
import flash.pipeline.ui.variations.DeconvolutionPreviewAdapter;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.VariationCache;
import flash.pipeline.ui.variations.VariationCleanupSupport;
import flash.pipeline.ui.variations.VariationResult;
import flash.pipeline.ui.variations.VariationStrategy;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.macro.Interpreter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Runs a deconvolution parameter sweep, publishing one deconvolved stack per
 * combination into the variations grid. Mirrors {@link FilterSweepStrategy}: the
 * results are {@code FILTER}-kind ({@link VariationResult#filterSuccess}) so each grid
 * cell shows the processed stack itself, scrollable in Z, with SNR / background
 * readouts in the footer.
 *
 * <p>Combinations are dispatched centre-out via {@link SweepDispatchOrder} so the grid
 * fills hole-free. The single cropped raw source is shared across combinations; the
 * {@link DeconvolutionPreviewAdapter} is responsible for not mutating it.
 */
public final class DeconvolutionSweep implements VariationStrategy {

    private final ImagePlus rawCrop;
    private final VariationCache cache;
    private final DeconvolutionPreviewAdapter adapter;
    private final DeconvSettings base;
    private final List<ParameterCombo> plannedCombos;

    public DeconvolutionSweep(ImagePlus rawCrop,
                              VariationCache cache,
                              DeconvolutionPreviewAdapter adapter,
                              DeconvSettings base) {
        this(rawCrop, cache, adapter, base, null);
    }

    public DeconvolutionSweep(ImagePlus rawCrop,
                              VariationCache cache,
                              DeconvolutionPreviewAdapter adapter,
                              DeconvSettings base,
                              List<ParameterCombo> plannedCombos) {
        if (rawCrop == null) {
            throw new IllegalArgumentException("rawCrop must not be null");
        }
        if (adapter == null) {
            throw new IllegalArgumentException("adapter must not be null");
        }
        if (base == null) {
            throw new IllegalArgumentException("base must not be null");
        }
        this.rawCrop = rawCrop;
        this.cache = cache;
        this.adapter = adapter;
        this.base = base;
        this.plannedCombos = plannedCombos == null
                ? null
                : Collections.unmodifiableList(new ArrayList<ParameterCombo>(plannedCombos));
    }

    @Override
    public void dispatch(ParameterSweep sweep,
                         Consumer<VariationResult> publisher,
                         BooleanSupplier cancelCheck) {
        if (sweep == null) {
            throw new IllegalArgumentException("sweep must not be null");
        }
        if (sweep.method() != ParameterSweep.Method.DECONVOLUTION) {
            throw new IllegalArgumentException("DeconvolutionSweep only accepts Deconvolution sweeps");
        }
        if (publisher == null) {
            throw new IllegalArgumentException("publisher must not be null");
        }
        List<ParameterCombo> ordered = plannedCombos == null
                ? SweepDispatchOrder.order(sweep)
                : SweepDispatchOrder.order(sweep, plannedCombos);
        for (int i = 0; i < ordered.size(); i++) {
            if (isCancelled(cancelCheck)) {
                return;
            }
            ParameterCombo combo = ordered.get(i);
            try {
                dispatchOne(sweep, combo, publisher, cancelCheck);
            } catch (Throwable t) {
                rethrowIfFatal(t);
                publishFailure(combo, t, publisher, cancelCheck);
            }
        }
    }

    private void dispatchOne(ParameterSweep sweep,
                             ParameterCombo combo,
                             Consumer<VariationResult> publisher,
                             BooleanSupplier cancelCheck) {
        String cacheKey = VariationCache.keyFor(sweep, combo);
        ImagePlus cached = cache == null ? null : cache.get(cacheKey);
        if (cached != null) {
            publishResult(combo, cached, 0L, true, publisher, cancelCheck);
            return;
        }
        runOne(combo, cacheKey, publisher, cancelCheck);
    }

    private void runOne(ParameterCombo combo,
                        String cacheKey,
                        Consumer<VariationResult> publisher,
                        BooleanSupplier cancelCheck) {
        long started = System.currentTimeMillis();
        ImagePlus result = null;
        boolean cacheOwnsResult = false;
        try {
            if (isCancelled(cancelCheck)) {
                return;
            }
            DeconvSettings settings = DeconvComboSettings.resolve(combo, base);
            result = runIsolated(new PreviewCall() {
                @Override public ImagePlus run() throws Exception {
                    return adapter.deconvolvePreview(rawCrop, settings);
                }
            });
            if (result == null) {
                throw new IllegalStateException("Deconvolution preview returned no image.");
            }
            if (result == rawCrop) {
                throw new IllegalStateException("Deconvolution preview returned the borrowed source image.");
            }
            if (isCancelled(cancelCheck)) {
                Throwable closeFailure = closeProduced(result);
                result = null;
                rethrowFailure(closeFailure);
                return;
            }
            long durationMs = Math.max(1L, System.currentTimeMillis() - started);
            ImageQualityMetrics.Result metrics = ImageQualityMetrics.compute(result);
            if (cache != null) {
                cache.put(cacheKey, result);
                cacheOwnsResult = true;
            }
            VariationResult variationResult = cacheOwnsResult
                    ? VariationResult.borrowedFilterSuccess(combo, result, durationMs,
                    metrics.histogram, metrics.snr, metrics.bgSigma)
                    : VariationResult.filterSuccess(combo, result, durationMs,
                    metrics.histogram, metrics.snr, metrics.bgSigma,
                    new VariationResult.ImageDisposer() {
                        @Override public void dispose(ImagePlus image) {
                            adapter.close(image);
                        }
                    });
            result = null; // The cache or VariationResult lease now owns the image.
            safePublish(variationResult, publisher, cancelCheck);
        } catch (Throwable t) {
            Throwable failure = t;
            if (result != null && !cacheOwnsResult) {
                failure = mergeFailure(failure, closeProduced(result));
            }
            rethrowIfFatal(failure);
            publishFailure(combo, failure, publisher, cancelCheck);
        }
    }

    private boolean publishResult(ParameterCombo combo,
                                  ImagePlus image,
                                  long durationMs,
                                  boolean borrowed,
                                  Consumer<VariationResult> publisher,
                                  BooleanSupplier cancelCheck) {
        ImageQualityMetrics.Result metrics = ImageQualityMetrics.compute(image);
        VariationResult result = borrowed
                ? VariationResult.borrowedFilterSuccess(combo, image, durationMs,
                metrics.histogram, metrics.snr, metrics.bgSigma)
                : VariationResult.filterSuccess(combo, image, durationMs,
                metrics.histogram, metrics.snr, metrics.bgSigma);
        return safePublish(result, publisher, cancelCheck);
    }

    private void publishFailure(ParameterCombo combo,
                                Throwable error,
                                Consumer<VariationResult> publisher,
                                BooleanSupplier cancelCheck) {
        if (isCancelled(cancelCheck)) {
            return;
        }
        IJ.log("Deconvolution variation failed for " + safeCombo(combo)
                + ": " + errorMessage(error));
        safePublish(VariationResult.failure(combo, error), publisher, cancelCheck);
    }

    private static boolean safePublish(VariationResult result,
                                       Consumer<VariationResult> publisher,
                                       BooleanSupplier cancelCheck) {
        if (isCancelled(cancelCheck) || result == null || publisher == null) {
            Throwable failure = disposeAfterFailedPublish(result, null);
            rethrowIfFatal(failure);
            if (failure != null) {
                IJ.log("Could not dispose unpublished deconvolution variation result for "
                        + safeCombo(result == null ? null : result.combo()) + ": "
                        + errorMessage(failure));
            }
            return false;
        }
        try {
            publisher.accept(result);
            return true;
        } catch (Throwable t) {
            Throwable failure = disposeAfterFailedPublish(result, t);
            rethrowIfFatal(failure);
            IJ.log("Could not publish deconvolution variation result for "
                    + safeCombo(result.combo()) + ": " + errorMessage(failure));
            return false;
        }
    }

    private ImagePlus runIsolated(PreviewCall call) throws Exception {
        ImagePlus produced = null;
        Throwable failure = null;
        WindowManagerLock.LOCK.lock();
        try {
            int[] windowsBefore = snapshotOpenImageWindows();
            boolean previousBatchMode = Interpreter.batchMode;
            Interpreter.batchMode = true;
            try {
                produced = call.run();
            } catch (Throwable t) {
                failure = t;
            } finally {
                boolean restoreInterrupt = Thread.interrupted();
                try {
                    try {
                        Interpreter.batchMode = previousBatchMode;
                    } catch (Throwable t) {
                        failure = mergeFailure(failure, t);
                    }
                    failure = mergeFailure(failure,
                            closeStrayPreviewWindows(windowsBefore, rawCrop, produced));
                } finally {
                    if (restoreInterrupt) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            if (failure != null && produced != null) {
                failure = mergeFailure(failure, closeProduced(produced));
                produced = null;
            }
            rethrowChecked(failure);
            return produced;
        } finally {
            WindowManagerLock.LOCK.unlock();
        }
    }

    private static int[] snapshotOpenImageWindows() {
        try {
            int[] ids = WindowManager.getIDList();
            return ids == null ? new int[0] : ids.clone();
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return null;
        }
    }

    private static Throwable closeStrayPreviewWindows(int[] beforeIds,
                                                      ImagePlus raw,
                                                      ImagePlus produced) {
        if (beforeIds == null) {
            return null;
        }
        Set<Integer> before = new HashSet<Integer>();
        for (int i = 0; i < beforeIds.length; i++) {
            before.add(Integer.valueOf(beforeIds[i]));
        }
        int[] afterIds;
        try {
            afterIds = WindowManager.getIDList();
        } catch (Throwable t) {
            return t;
        }
        if (afterIds == null) {
            return null;
        }
        Throwable failure = null;
        for (int i = 0; i < afterIds.length; i++) {
            int id = afterIds[i];
            if (before.contains(Integer.valueOf(id))) {
                continue;
            }
            ImagePlus image;
            try {
                image = WindowManager.getImage(id);
            } catch (Throwable t) {
                failure = mergeFailure(failure, t);
                continue;
            }
            if (image == null || image == raw || image == produced) {
                continue;
            }
            failure = mergeFailure(failure, closeImage(image));
        }
        return failure;
    }

    private static Throwable closeImage(ImagePlus image) {
        if (image == null) {
            return null;
        }
        Throwable failure = null;
        try {
            image.changes = false;
        } catch (Throwable t) {
            failure = mergeFailure(failure, t);
        }
        try {
            image.close();
        } catch (Throwable t) {
            failure = mergeFailure(failure, t);
        }
        try {
            image.flush();
        } catch (Throwable t) {
            failure = mergeFailure(failure, t);
        }
        return failure;
    }

    private Throwable closeProduced(ImagePlus image) {
        if (image == null || image == rawCrop) {
            return null;
        }
        boolean restoreInterrupt = Thread.interrupted();
        try {
            adapter.close(image);
            return null;
        } catch (Throwable t) {
            return t;
        } finally {
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Throwable disposeAfterFailedPublish(VariationResult result,
                                                       Throwable primary) {
        if (result == null) {
            return primary;
        }
        return mergeFailure(primary,
                VariationCleanupSupport.disposeProducerOwnedRejectedResult(result));
    }

    private static boolean isCancelled(BooleanSupplier cancelCheck) {
        return Thread.currentThread().isInterrupted()
                || (cancelCheck != null && cancelCheck.getAsBoolean());
    }

    private static void rethrowIfFatal(Throwable t) {
        if (t instanceof ThreadDeath) {
            throw (ThreadDeath) t;
        }
        if (t instanceof VirtualMachineError) {
            throw (VirtualMachineError) t;
        }
    }

    private static Throwable mergeFailure(Throwable primary, Throwable additional) {
        if (additional == null) {
            return primary;
        }
        if (primary == null) {
            return additional;
        }
        if (isFatal(additional) && !isFatal(primary)) {
            addSuppressed(additional, primary);
            return additional;
        }
        addSuppressed(primary, additional);
        return primary;
    }

    private static boolean isFatal(Throwable t) {
        return t instanceof ThreadDeath || t instanceof VirtualMachineError;
    }

    private static void addSuppressed(Throwable primary, Throwable suppressed) {
        if (primary == suppressed) {
            return;
        }
        try {
            primary.addSuppressed(suppressed);
        } catch (RuntimeException ignored) {
            // Suppression is diagnostic only.
        }
    }

    private static void rethrowFailure(Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof ThreadDeath) {
            throw (ThreadDeath) failure;
        }
        if (failure instanceof VirtualMachineError) {
            throw (VirtualMachineError) failure;
        }
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw new IllegalStateException("Could not clean up deconvolution preview.", failure);
    }

    private static void rethrowChecked(Throwable failure) throws Exception {
        if (failure == null) {
            return;
        }
        if (failure instanceof Exception) {
            throw (Exception) failure;
        }
        rethrowFailure(failure);
    }

    private static String safeCombo(ParameterCombo combo) {
        if (combo == null) {
            return "{}";
        }
        try {
            return combo.toCanonicalJson();
        } catch (RuntimeException e) {
            return combo.toString();
        }
    }

    private static String errorMessage(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message.trim();
    }

    private interface PreviewCall {
        ImagePlus run() throws Exception;
    }
}
