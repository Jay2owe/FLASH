package flash.pipeline.ui.variations;

import ij.ImagePlus;
import ij.measure.ResultsTable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class VariationResult {

    /** Releases an image owned by a result. Implementations may throw on failure. */
    public interface ImageDisposer {
        void dispose(ImagePlus image);
    }

    private static final ImageDisposer DEFAULT_IMAGE_DISPOSER = new ImageDisposer() {
        @Override public void dispose(ImagePlus image) {
            disposeImage(image);
        }
    };

    public enum Kind {
        SEGMENTATION,
        FILTER
    }

    private final Kind kind;
    private final ParameterCombo combo;
    private final ImagePlus label;
    private final ImagePlus previewImage;
    private final int nObjects;
    private final long durationMs;
    private final ResultsTable stats;
    private final Throwable error;
    private final double meanNeighbourIou;
    private final int[] histogram;
    private final double snr;
    private final double bgSigma;
    private final ImageOwnership imageOwnership;

    public VariationResult(ParameterCombo combo,
                           ImagePlus label,
                           int nObjects,
                           long durationMs,
                           ResultsTable stats,
                           Throwable error) {
        this(combo, label, nObjects, durationMs, stats, error, Double.NaN);
    }

    public VariationResult(ParameterCombo combo,
                           ImagePlus label,
                           int nObjects,
                           long durationMs,
                           ResultsTable stats,
                           Throwable error,
                           double meanNeighbourIou) {
        this(Kind.SEGMENTATION, combo, label, null, nObjects, durationMs, stats,
                error, meanNeighbourIou, null, Double.NaN, Double.NaN,
                ImageOwnership.owned(label, null, DEFAULT_IMAGE_DISPOSER));
    }

    private VariationResult(Kind kind,
                            ParameterCombo combo,
                            ImagePlus label,
                            ImagePlus previewImage,
                            int nObjects,
                            long durationMs,
                            ResultsTable stats,
                            Throwable error,
                            double meanNeighbourIou,
                            int[] histogram,
                            double snr,
                            double bgSigma,
                            ImageOwnership imageOwnership) {
        if (combo == null) {
            throw new IllegalArgumentException("combo must not be null");
        }
        this.kind = kind == null ? Kind.SEGMENTATION : kind;
        this.combo = combo;
        this.label = label;
        this.previewImage = previewImage;
        this.nObjects = nObjects;
        this.durationMs = durationMs;
        this.stats = stats;
        this.error = error;
        this.meanNeighbourIou = meanNeighbourIou;
        this.histogram = histogram == null ? null : histogram.clone();
        this.snr = snr;
        this.bgSigma = bgSigma;
        this.imageOwnership = imageOwnership == null
                ? ImageOwnership.none()
                : imageOwnership;
    }

    public static VariationResult success(ParameterCombo combo,
                                          ImagePlus label,
                                          int nObjects,
                                          long durationMs,
                                          ResultsTable stats) {
        return new VariationResult(combo, label, nObjects, durationMs, stats, null);
    }

    public static VariationResult failure(ParameterCombo combo, Throwable error) {
        return new VariationResult(Kind.SEGMENTATION, combo, null, null,
                0, 0L, null, error, Double.NaN, null, Double.NaN,
                Double.NaN, ImageOwnership.none());
    }

    public static VariationResult filterSuccess(ParameterCombo combo,
                                                ImagePlus filteredImage,
                                                long durationMs,
                                                int[] histogram,
                                                double snr,
                                                double bgSigma) {
        return filterSuccess(combo, filteredImage, durationMs, histogram, snr,
                bgSigma, DEFAULT_IMAGE_DISPOSER);
    }

    /**
     * Creates an owned filter result whose image is released with {@code disposer}
     * unless ownership is transferred to a successful consumer.
     */
    public static VariationResult filterSuccess(ParameterCombo combo,
                                                ImagePlus filteredImage,
                                                long durationMs,
                                                int[] histogram,
                                                double snr,
                                                double bgSigma,
                                                ImageDisposer disposer) {
        return new VariationResult(Kind.FILTER, combo, filteredImage, filteredImage,
                0, durationMs, null, null, Double.NaN, histogram, snr, bgSigma,
                ImageOwnership.owned(filteredImage, filteredImage,
                        disposer == null ? DEFAULT_IMAGE_DISPOSER : disposer));
    }

    /** Creates a filter result that borrows an image owned by another component. */
    public static VariationResult borrowedFilterSuccess(ParameterCombo combo,
                                                        ImagePlus filteredImage,
                                                        long durationMs,
                                                        int[] histogram,
                                                        double snr,
                                                        double bgSigma) {
        return new VariationResult(Kind.FILTER, combo, filteredImage, filteredImage,
                0, durationMs, null, null, Double.NaN, histogram, snr, bgSigma,
                ImageOwnership.none());
    }

    public Kind kind() {
        return kind;
    }

    public Kind getKind() {
        return kind;
    }

    public ParameterCombo combo() {
        return combo;
    }

    public ParameterCombo getCombo() {
        return combo;
    }

    public ImagePlus label() {
        return label;
    }

    public ImagePlus getLabel() {
        return label;
    }

    public ImagePlus previewImage() {
        return previewImage;
    }

    public ImagePlus getPreviewImage() {
        return previewImage;
    }

    public int nObjects() {
        return nObjects;
    }

    public int getNObjects() {
        return nObjects;
    }

    public long durationMs() {
        return durationMs;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public ResultsTable stats() {
        return stats;
    }

    public ResultsTable getStats() {
        return stats;
    }

    public Throwable error() {
        return error;
    }

    public Throwable getError() {
        return error;
    }

    public boolean hasError() {
        return error != null;
    }

    public double meanNeighbourIou() {
        return meanNeighbourIou;
    }

    public double getMeanNeighbourIou() {
        return meanNeighbourIou;
    }

    public VariationResult withMeanNeighbourIou(double meanNeighbourIou) {
        return new VariationResult(kind, combo, label, previewImage, nObjects,
                durationMs, stats, error, meanNeighbourIou, histogram, snr, bgSigma,
                imageOwnership);
    }

    public int[] histogram() {
        return histogram == null ? null : histogram.clone();
    }

    public int[] getHistogram() {
        return histogram();
    }

    public double snr() {
        return snr;
    }

    public double getSnr() {
        return snr;
    }

    public double bgSigma() {
        return bgSigma;
    }

    public double getBgSigma() {
        return bgSigma;
    }

    /**
     * Transfers owned images to the successful consumer. This is one-way; a later
     * {@link #dispose()} call cannot close transferred images.
     */
    public void transferOwnership() {
        imageOwnership.transfer();
    }

    /** Releases every still-owned image exactly once, deduplicated by identity. */
    public void dispose() {
        imageOwnership.dispose();
    }

    /**
     * Releases images after ownership was transferred to a successful cell.
     * Retained identities are handed to the replacement result instead of
     * being closed underneath it.
     */
    void releaseTransferredImages(ImagePlus... retainedImages) {
        imageOwnership.releaseTransferred(retainedImages);
    }

    ImagePlus[] pendingTransferredImages() {
        return imageOwnership.pendingTransferredImages();
    }

    List<CleanupClaim> detachTransferredCleanup(ImagePlus... retainedImages) {
        return imageOwnership.detachTransferredCleanup(retainedImages);
    }

    void adoptTransferredCleanup(List<CleanupClaim> claims) {
        imageOwnership.adoptTransferredCleanup(claims);
    }

    boolean sharesImageOwnershipWith(VariationResult other) {
        return other != null && imageOwnership == other.imageOwnership;
    }

    boolean ownsImagesForTest() {
        return imageOwnership.ownsImages();
    }

    boolean hasDirectOwnership() {
        return imageOwnership.hasDirectOwnership();
    }

    private static void disposeImage(ImagePlus image) {
        if (image == null) {
            return;
        }
        Throwable failure = null;
        try {
            image.changes = false;
        } catch (Throwable t) {
            failure = mergeFailure(failure, t);
            if (isFatal(failure)) throwFailure(failure);
        }
        try {
            image.close();
        } catch (Throwable t) {
            failure = mergeFailure(failure, t);
            if (isFatal(failure)) throwFailure(failure);
        }
        try {
            image.flush();
        } catch (Throwable t) {
            failure = mergeFailure(failure, t);
        }
        throwFailure(failure);
    }

    /** Tracks successful built-in cleanup phases across transferred retries. */
    private static final class BuiltInImageDisposer implements ImageDisposer {
        private boolean changesCleared;
        private boolean closed;
        private boolean flushed;

        @Override public synchronized void dispose(ImagePlus image) {
            dispose(image, false);
        }

        synchronized void disposeTransferred(ImagePlus image) {
            dispose(image, true);
        }

        private void dispose(ImagePlus image, boolean stopAfterFailedPhase) {
            if (image == null) {
                changesCleared = true;
                closed = true;
                flushed = true;
                return;
            }
            Throwable failure = null;
            if (!changesCleared) {
                try {
                    image.changes = false;
                    changesCleared = true;
                } catch (Throwable t) {
                    failure = mergeFailure(failure, t);
                }
                if (isFatal(failure)) {
                    throwFailure(failure);
                }
                if (failure != null && stopAfterFailedPhase) {
                    throwFailure(failure);
                }
            }
            if (!closed) {
                try {
                    image.close();
                    closed = true;
                } catch (Throwable t) {
                    failure = mergeFailure(failure, t);
                }
                if (isFatal(failure)) {
                    throwFailure(failure);
                }
                if (failure != null && stopAfterFailedPhase) {
                    throwFailure(failure);
                }
            }
            if (!flushed) {
                try {
                    image.flush();
                    flushed = true;
                } catch (Throwable t) {
                    failure = mergeFailure(failure, t);
                }
            }
            throwFailure(failure);
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

    private static void addSuppressed(Throwable primary, Throwable suppressed) {
        if (primary == suppressed) {
            return;
        }
        try {
            primary.addSuppressed(suppressed);
        } catch (RuntimeException ignored) {
            // Suppression is diagnostic only; never obscure the original failure.
        }
    }

    private static boolean isFatal(Throwable failure) {
        return failure instanceof ThreadDeath || failure instanceof VirtualMachineError;
    }

    static boolean containsInterruptedFailure(Throwable failure) {
        return containsInterruptedFailure(failure,
                new IdentityHashMap<Throwable, Boolean>());
    }

    private static boolean containsInterruptedFailure(
            Throwable failure,
            IdentityHashMap<Throwable, Boolean> visited) {
        if (failure == null || visited.containsKey(failure)) {
            return false;
        }
        if (failure instanceof InterruptedException) {
            return true;
        }
        visited.put(failure, Boolean.TRUE);
        if (containsInterruptedFailure(failure.getCause(), visited)) {
            return true;
        }
        Throwable[] suppressed = failure.getSuppressed();
        for (int i = 0; i < suppressed.length; i++) {
            if (containsInterruptedFailure(suppressed[i], visited)) {
                return true;
            }
        }
        return false;
    }

    private static void throwFailure(Throwable failure) {
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
        throw new IllegalStateException("Could not dispose variation result images.", failure);
    }

    /** One transferable per-image cleanup operation, including retry progress. */
    static final class CleanupClaim {
        private final ImagePlus image;
        private final ImageDisposer disposer;

        private CleanupClaim(ImagePlus image, ImageDisposer disposer) {
            this.image = image;
            this.disposer = disposer;
        }
    }

    /** Shared by derived VariationResult values so aliases cannot double-close. */
    private static final class ImageOwnership {
        private static final int OWNED = 0;
        private static final int TRANSFERRED = 1;
        private static final int DISPOSED = 2;

        private final IdentityHashMap<ImagePlus, ImageDisposer> images;
        private final IdentityHashMap<ImagePlus, Boolean> inFlight =
                new IdentityHashMap<ImagePlus, Boolean>();
        private final IdentityHashMap<ImagePlus, Boolean> adoptedCleanup =
                new IdentityHashMap<ImagePlus, Boolean>();
        private int state;

        private ImageOwnership(IdentityHashMap<ImagePlus, ImageDisposer> images) {
            this.images = images;
            this.state = OWNED;
        }

        static ImageOwnership none() {
            return new ImageOwnership(new IdentityHashMap<ImagePlus, ImageDisposer>());
        }

        static ImageOwnership owned(ImagePlus first,
                                    ImagePlus second,
                                    ImageDisposer disposer) {
            IdentityHashMap<ImagePlus, ImageDisposer> images =
                    new IdentityHashMap<ImagePlus, ImageDisposer>();
            if (first != null) {
                images.put(first, disposerForClaim(disposer));
            }
            if (second != null && !images.containsKey(second)) {
                images.put(second, disposerForClaim(disposer));
            }
            return new ImageOwnership(images);
        }

        private static ImageDisposer disposerForClaim(ImageDisposer disposer) {
            return disposer == DEFAULT_IMAGE_DISPOSER
                    ? new BuiltInImageDisposer()
                    : disposer;
        }

        synchronized void transfer() {
            if (state == OWNED) {
                state = TRANSFERRED;
            }
        }

        void dispose() {
            disposeOwned(false, null);
        }

        void releaseTransferred(ImagePlus... retainedImages) {
            disposeOwned(true, retainedImages);
        }

        synchronized List<CleanupClaim> detachTransferredCleanup(
                ImagePlus... retainedImages) {
            List<CleanupClaim> detached = new ArrayList<CleanupClaim>();
            if (state != TRANSFERRED || retainedImages == null) {
                return detached;
            }
            for (int i = 0; i < retainedImages.length; i++) {
                ImagePlus image = retainedImages[i];
                if (image == null || inFlight.containsKey(image)) {
                    continue;
                }
                ImageDisposer disposer = images.remove(image);
                if (disposer != null) {
                    adoptedCleanup.remove(image);
                    detached.add(new CleanupClaim(image, disposer));
                }
            }
            completeIfEmpty();
            return detached;
        }

        synchronized void adoptTransferredCleanup(List<CleanupClaim> claims) {
            if (state != TRANSFERRED || claims == null) {
                return;
            }
            for (int i = 0; i < claims.size(); i++) {
                CleanupClaim claim = claims.get(i);
                if (claim != null && claim.image != null
                        && !adoptedCleanup.containsKey(claim.image)) {
                    images.put(claim.image, claim.disposer);
                    adoptedCleanup.put(claim.image, Boolean.TRUE);
                }
            }
        }

        private void disposeOwned(boolean transferred,
                                  ImagePlus[] retainedImages) {
            List<Map.Entry<ImagePlus, ImageDisposer>> claimed;
            synchronized (this) {
                int expectedState = transferred ? TRANSFERRED : OWNED;
                if (state != expectedState) {
                    return;
                }
                if (!transferred) {
                    // Direct owners get one best-effort cleanup pass. Even when
                    // a disposer reports failure, a later dispose() must not
                    // repeat a close/flush that may already have taken effect.
                    state = DISPOSED;
                }
                if (transferred && retainedImages != null) {
                    List<ImagePlus> retained = new ArrayList<ImagePlus>(images.keySet());
                    for (int i = 0; i < retained.size(); i++) {
                        ImagePlus image = retained.get(i);
                        if (containsIdentity(retainedImages, image)
                                && !inFlight.containsKey(image)) {
                            images.remove(image);
                            adoptedCleanup.remove(image);
                        }
                    }
                }
                claimed = new ArrayList<Map.Entry<ImagePlus, ImageDisposer>>();
                for (Map.Entry<ImagePlus, ImageDisposer> entry : images.entrySet()) {
                    if (!inFlight.containsKey(entry.getKey())) {
                        claimed.add(entry);
                        inFlight.put(entry.getKey(), Boolean.TRUE);
                    }
                }
                completeIfEmpty();
            }
            Throwable failure = null;
            boolean restoreInterrupt = Thread.interrupted();
            try {
                for (int i = 0; i < claimed.size(); i++) {
                    Map.Entry<ImagePlus, ImageDisposer> entry = claimed.get(i);
                    boolean succeeded = false;
                    boolean fatalClaim = false;
                    try {
                        ImageDisposer disposer = entry.getValue();
                        if (transferred && disposer instanceof BuiltInImageDisposer) {
                            ((BuiltInImageDisposer) disposer)
                                    .disposeTransferred(entry.getKey());
                        } else {
                            disposer.dispose(entry.getKey());
                        }
                        succeeded = true;
                    } catch (Throwable t) {
                        failure = mergeFailure(failure, t);
                        fatalClaim = isFatal(failure);
                    } finally {
                        finishClaim(entry.getKey(), succeeded
                                || (!transferred && !fatalClaim));
                    }
                    if (fatalClaim) {
                        releaseUnattemptedClaims(claimed, i + 1);
                        if (!transferred) {
                            retainDirectOwnershipAfterFatal();
                        }
                        break;
                    }
                }
            } finally {
                if (restoreInterrupt) {
                    Thread.currentThread().interrupt();
                }
            }
            throwFailure(failure);
        }

        private synchronized void retainDirectOwnershipAfterFatal() {
            if (state == DISPOSED && !images.isEmpty()) {
                state = OWNED;
            }
        }

        private synchronized void releaseUnattemptedClaims(
                List<Map.Entry<ImagePlus, ImageDisposer>> claimed,
                int firstUnattempted) {
            for (int i = firstUnattempted; i < claimed.size(); i++) {
                inFlight.remove(claimed.get(i).getKey());
            }
        }

        private synchronized void finishClaim(ImagePlus image,
                                              boolean succeeded) {
            inFlight.remove(image);
            if (succeeded) {
                images.remove(image);
                adoptedCleanup.remove(image);
            }
            completeIfEmpty();
        }

        private void completeIfEmpty() {
            if (images.isEmpty() && inFlight.isEmpty()) {
                state = DISPOSED;
            }
        }

        synchronized ImagePlus[] pendingTransferredImages() {
            if (state != TRANSFERRED || images.isEmpty()) {
                return new ImagePlus[0];
            }
            return images.keySet().toArray(new ImagePlus[images.size()]);
        }

        private static boolean containsIdentity(ImagePlus[] images,
                                                ImagePlus candidate) {
            if (images == null || candidate == null) {
                return false;
            }
            for (int i = 0; i < images.length; i++) {
                if (images[i] == candidate) {
                    return true;
                }
            }
            return false;
        }

        synchronized boolean ownsImages() {
            return state == OWNED && !images.isEmpty();
        }

        synchronized boolean hasDirectOwnership() {
            return state == OWNED;
        }
    }
}
