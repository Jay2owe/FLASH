package flash.pipeline.ui.variations.strategy;

import flash.pipeline.decontamination.SpectralDecontaminationConfig;
import flash.pipeline.image.WindowManagerLock;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.SpectralComboSettings;
import flash.pipeline.ui.variations.SpectralPreviewAdapter;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Runs a spectral-decontamination parameter sweep, publishing one colored "after" merge per
 * combination into the variations grid. Mirrors {@link DeconvolutionSweep}: results are
 * {@code FILTER}-kind ({@link VariationResult#filterSuccess}) so each grid cell shows the merge
 * itself, scrollable in Z, with SNR / background readouts (computed on the grayscale corrected
 * target) in the footer.
 *
 * <p>Combinations are dispatched centre-out via {@link SweepDispatchOrder} so the grid fills
 * hole-free. The single cropped multi-channel raw source is shared across combinations; the
 * {@link SpectralPreviewAdapter} is responsible for not mutating it.</p>
 */
public final class SpectralDecontaminationSweep implements VariationStrategy {

    private final ImagePlus rawCropMultiChannel;
    private final SpectralPreviewAdapter adapter;
    private final SpectralDecontaminationConfig base;
    private final List<ParameterCombo> plannedCombos;

    public SpectralDecontaminationSweep(ImagePlus rawCropMultiChannel,
                                        SpectralPreviewAdapter adapter,
                                        SpectralDecontaminationConfig base) {
        this(rawCropMultiChannel, adapter, base, null);
    }

    public SpectralDecontaminationSweep(ImagePlus rawCropMultiChannel,
                                        SpectralPreviewAdapter adapter,
                                        SpectralDecontaminationConfig base,
                                        List<ParameterCombo> plannedCombos) {
        if (rawCropMultiChannel == null) {
            throw new IllegalArgumentException("rawCropMultiChannel must not be null");
        }
        if (adapter == null) {
            throw new IllegalArgumentException("adapter must not be null");
        }
        if (base == null) {
            throw new IllegalArgumentException("base must not be null");
        }
        this.rawCropMultiChannel = rawCropMultiChannel;
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
        if (sweep.method() != ParameterSweep.Method.SPECTRAL) {
            throw new IllegalArgumentException("SpectralDecontaminationSweep only accepts Spectral sweeps");
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
                runOne(combo, publisher, cancelCheck);
            } catch (Throwable t) {
                rethrowIfFatal(t);
                publishFailure(combo, t, publisher, cancelCheck);
            }
        }
    }

    private void runOne(final ParameterCombo combo,
                        Consumer<VariationResult> publisher,
                        BooleanSupplier cancelCheck) {
        long started = System.currentTimeMillis();
        SpectralPreviewAdapter.Result result = null;
        ImagePlus merge = null;
        ImagePlus corrected = null;
        ImagePlus mask = null;
        try {
            if (isCancelled(cancelCheck)) {
                return;
            }
            final SpectralDecontaminationConfig resolved = SpectralComboSettings.resolve(combo, base);
            result = runIsolated(new PreviewCall() {
                @Override public SpectralPreviewAdapter.Result run() throws Exception {
                    return adapter.decontaminatePreview(rawCropMultiChannel, resolved);
                }
            });
            if (result == null) {
                throw new IllegalStateException("Spectral decontamination preview returned no image.");
            }
            merge = result.mergeRgb();
            corrected = result.correctedGray();
            mask = result.maskOrNull();
            result = null; // Cleanup below is identity-aware; do not use the bundle closer.
            if (merge == null) {
                throw new IllegalStateException("Spectral decontamination preview returned no image.");
            }
            if (merge == rawCropMultiChannel) {
                throw new IllegalStateException("Spectral preview returned the borrowed source image.");
            }
            if (isCancelled(cancelCheck)) {
                Throwable closeFailure = closeImages(null, merge, corrected, mask);
                merge = null;
                corrected = null;
                mask = null;
                rethrowFailure(closeFailure);
                return;
            }
            long durationMs = Math.max(1L, System.currentTimeMillis() - started);
            ImagePlus metricsSource = corrected != null ? corrected : merge;
            ImageQualityMetrics.Result metrics = ImageQualityMetrics.compute(metricsSource);
            // Ancillary images are only needed for metrics. Identity aliases of the
            // published merge are deliberately excluded from cleanup.
            Throwable ancillaryFailure = closeImages(merge, corrected, mask);
            corrected = null;
            mask = null;
            rethrowFailure(ancillaryFailure);

            VariationResult variationResult = VariationResult.filterSuccess(combo, merge,
                    durationMs, metrics.histogram, metrics.snr, metrics.bgSigma,
                    new VariationResult.ImageDisposer() {
                        @Override public void dispose(ImagePlus image) {
                            adapter.close(new SpectralPreviewAdapter.Result(image, null, null));
                        }
                    });
            merge = null; // VariationResult now owns the published image.
            safePublish(variationResult, publisher, cancelCheck);
        } catch (Throwable t) {
            Throwable failure = t;
            if (result != null) {
                failure = mergeFailure(failure, closePreviewResult(result));
            }
            failure = mergeFailure(failure, closeImages(null, merge, corrected, mask));
            rethrowIfFatal(failure);
            publishFailure(combo, failure, publisher, cancelCheck);
        }
    }

    private void publishFailure(ParameterCombo combo,
                                Throwable error,
                                Consumer<VariationResult> publisher,
                                BooleanSupplier cancelCheck) {
        if (isCancelled(cancelCheck)) {
            return;
        }
        IJ.log("Spectral decontamination variation failed for " + safeCombo(combo)
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
                IJ.log("Could not dispose unpublished spectral variation result for "
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
            IJ.log("Could not publish spectral variation result for "
                    + safeCombo(result.combo()) + ": " + errorMessage(failure));
            return false;
        }
    }

    private SpectralPreviewAdapter.Result runIsolated(PreviewCall call) throws Exception {
        SpectralPreviewAdapter.Result produced = null;
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
                            closeStrayPreviewWindows(windowsBefore, produced));
                } finally {
                    if (restoreInterrupt) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            if (failure != null && produced != null) {
                failure = mergeFailure(failure, closePreviewResult(produced));
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

    private Throwable closeStrayPreviewWindows(int[] beforeIds,
                                               SpectralPreviewAdapter.Result produced) {
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
            if (image == null || image == rawCropMultiChannel || isProduced(image, produced)) {
                continue;
            }
            failure = mergeFailure(failure, closeImage(image));
        }
        return failure;
    }

    private static boolean isProduced(ImagePlus image, SpectralPreviewAdapter.Result produced) {
        return produced != null
                && (image == produced.mergeRgb()
                || image == produced.correctedGray()
                || image == produced.maskOrNull());
    }

    private Throwable closePreviewResult(SpectralPreviewAdapter.Result result) {
        if (result == null) {
            return null;
        }
        return closeImages(null, result.mergeRgb(), result.correctedGray(),
                result.maskOrNull());
    }

    private Throwable closeImages(ImagePlus excluded, ImagePlus... images) {
        if (images == null || images.length == 0) {
            return null;
        }
        Set<ImagePlus> seen = Collections.newSetFromMap(
                new IdentityHashMap<ImagePlus, Boolean>());
        Throwable failure = null;
        boolean restoreInterrupt = Thread.interrupted();
        try {
            for (int i = 0; i < images.length; i++) {
                ImagePlus image = images[i];
                if (image == null || image == excluded
                        || image == rawCropMultiChannel || !seen.add(image)) {
                    continue;
                }
                failure = mergeFailure(failure, closeAdapterImage(image));
            }
        } finally {
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
        return failure;
    }

    private Throwable closeAdapterImage(ImagePlus image) {
        try {
            adapter.close(new SpectralPreviewAdapter.Result(image, null, null));
            return null;
        } catch (Throwable t) {
            return t;
        }
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
        throw new IllegalStateException("Could not clean up spectral preview.", failure);
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
        SpectralPreviewAdapter.Result run() throws Exception;
    }
}
