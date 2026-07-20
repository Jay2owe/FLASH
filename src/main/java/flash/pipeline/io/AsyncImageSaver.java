package flash.pipeline.io;

import flash.pipeline.execution.AnalysisCancellation;
import flash.pipeline.image.ImageOps;
import ij.IJ;
import ij.ImagePlus;
import ij.io.FileSaver;
import ij.io.Opener;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Asynchronous image saving utility. Queues save operations on a background
 * writer thread so the main processing pipeline can continue without waiting
 * for disk I/O.
 *
 * <p>During analysis a single writer thread processes save jobs. When the
 * analysis finishes and calls {@link #waitForAllWithProgress(int)}, the writer
 * pool is temporarily expanded to the requested number of concurrent writers
 * so queued saves execute in parallel, then shrunk back to one writer for the
 * next batch.</p>
 */
public class AsyncImageSaver {
    private static final ThreadPoolExecutor IO_POOL;
    static {
        IO_POOL = new ThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>());
        IO_POOL.allowCoreThreadTimeOut(true);
    }

    private static final List<PendingSave> pending = new ArrayList<PendingSave>();
    private static final AtomicBoolean firstSaveLogged = new AtomicBoolean(false);

    private static final ImagePublicationOperations DEFAULT_IMAGE_PUBLICATION_OPERATIONS =
            new ImagePublicationOperations() {
                @Override
                public boolean save(ImagePlus image, File staged, ImageFormat format) {
                    FileSaver saver = new FileSaver(image);
                    if (format == ImageFormat.PNG) {
                        return saver.saveAsPng(staged.getAbsolutePath());
                    }
                    return image.getStackSize() > 1
                            ? saver.saveAsTiffStack(staged.getAbsolutePath())
                            : saver.saveAsTiff(staged.getAbsolutePath());
                }

                @Override
                public void validate(ImagePlus source, File staged, ImageFormat format)
                        throws IOException {
                    validateStagedImage(source, staged, format);
                }

                @Override
                public void replace(Path source, Path target) throws IOException {
                    IoUtils.moveReplacing(source, target);
                }
            };

    private static volatile ImagePublicationOperations imagePublicationOperations =
            DEFAULT_IMAGE_PUBLICATION_OPERATIONS;

    /**
     * Saves the image as TIFF asynchronously. The image is duplicated
     * immediately so the caller can close/reuse the original.
     */
    public static void saveAsTiffAsync(ImagePlus imp, String path) {
        logFirstSave();
        final ImagePlus copy = ImageOps.duplicateThreadSafe(imp);
        submitPending(new Runnable() {
            @Override
            public void run() {
                try {
                    saveTiffAtomically(copy, path);
                } catch (IOException e) {
                    throw new ImageSaveTaskException(path, e);
                }
            }
        }, copy, path);
    }

    /**
     * Saves the image as PNG asynchronously. The image is duplicated
     * immediately so the caller can close/reuse the original.
     */
    public static void saveAsPngAsync(ImagePlus imp, String path) {
        logFirstSave();
        final ImagePlus copy = ImageOps.duplicateThreadSafe(imp);
        submitPending(new Runnable() {
            @Override
            public void run() {
                try {
                    savePngAtomically(copy, path);
                } catch (IOException e) {
                    throw new ImageSaveTaskException(path, e);
                }
            }
        }, copy, path);
    }

    private static void submitPending(Runnable task, ImagePlus imageToClose, String target) {
        PendingSave save = new PendingSave(task, imageToClose, target);
        Future<?> future;
        try {
            future = IO_POOL.submit(save);
        } catch (RuntimeException rejection) {
            save.closeImage(rejection);
            throw rejection;
        } catch (Error rejection) {
            save.closeImage(rejection);
            throw rejection;
        }
        save.setFuture(future);
        synchronized (pending) {
            pending.add(save);
        }
    }

    /** Package-private: submit a synthetic save job (test seam). */
    static void submitTask(Runnable task) {
        submitTask("<synthetic-save>", task);
    }

    /** Package-private: submit a named synthetic save job (test seam). */
    static void submitTask(String target, Runnable task) {
        submitPending(task, null, target);
    }

    /** Package-private: number of pending save jobs (test seam). */
    static int pendingCount() {
        synchronized (pending) {
            return pending.size();
        }
    }

    /** Package-private: reset all state for test isolation. */
    static void resetForTest() {
        synchronized (pending) {
            cancelAll(pending);
            pending.clear();
        }
        IO_POOL.purge();
        IO_POOL.setCorePoolSize(1);
        IO_POOL.setMaximumPoolSize(1);
        firstSaveLogged.set(false);
        imagePublicationOperations = DEFAULT_IMAGE_PUBLICATION_OPERATIONS;
    }

    private static void logFirstSave() {
        if (firstSaveLogged.compareAndSet(false, true)) {
            IJ.log("  Background saver started - images will be saved asynchronously.");
        }
    }

    private static void saveTiffAtomically(ImagePlus image, String path) throws IOException {
        publishImage(image, path, ImageFormat.TIFF);
    }

    private static void savePngAtomically(ImagePlus image, String path) throws IOException {
        publishImage(image, path, ImageFormat.PNG);
    }

    private static void publishImage(ImagePlus image, String path, ImageFormat format)
            throws IOException {
        if (image == null) {
            throw new IOException("Image is null for " + path);
        }
        if (path == null || path.trim().isEmpty()) {
            throw new IOException("Image output path is empty");
        }
        File target = new File(path).getAbsoluteFile();
        File temp = createTempSibling(target, format.suffix);
        boolean published = false;
        Throwable primaryFailure = null;
        try {
            ImagePublicationOperations operations = imagePublicationOperations;
            boolean saved = operations.save(image, temp, format);
            if (!saved) {
                throw new IOException("ImageJ failed to save temporary " + format.label
                        + " for " + target.getAbsolutePath());
            }
            forceFile(temp.toPath());
            operations.validate(image, temp, format);
            long stagedBytes = Files.size(temp.toPath());
            operations.replace(temp.toPath(), target.toPath());
            verifyCommittedImage(target.toPath(), stagedBytes);
            published = true;
        } catch (IOException failure) {
            IOException wrapped = new IOException(
                    "Failed to publish verified " + format.label + " image "
                    + target.getAbsolutePath() + ": " + failure.getMessage(), failure);
            primaryFailure = wrapped;
            throw wrapped;
        } catch (RuntimeException failure) {
            primaryFailure = failure;
            throw failure;
        } catch (Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            if (!published) {
                try {
                    Files.deleteIfExists(temp.toPath());
                } catch (IOException cleanupFailure) {
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(cleanupFailure);
                    } else {
                        throw cleanupFailure;
                    }
                }
            }
        }
    }

    private static File createTempSibling(File target, String suffix) throws IOException {
        File absolute = target.getAbsoluteFile();
        File parent = absolute.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            Files.createDirectories(parent.toPath());
        }
        return File.createTempFile("." + absolute.getName() + ".", suffix, parent);
    }

    private static void forceFile(Path file) throws IOException {
        FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE);
        try {
            channel.force(true);
        } finally {
            channel.close();
        }
    }

    private static void validateStagedImage(ImagePlus source, File staged, ImageFormat format)
            throws IOException {
        if (!staged.isFile() || staged.length() <= 0L) {
            throw new IOException("Temporary " + format.label + " image is empty: "
                    + staged.getAbsolutePath());
        }
        ImagePlus reopened = new Opener().openImage(staged.getAbsolutePath());
        if (reopened == null) {
            throw new IOException("Could not reopen temporary " + format.label + " image: "
                    + staged.getAbsolutePath());
        }
        try {
            int expectedSlices = format == ImageFormat.PNG ? 1 : source.getStackSize();
            if (reopened.getWidth() != source.getWidth()
                    || reopened.getHeight() != source.getHeight()
                    || reopened.getStackSize() != expectedSlices) {
                throw new IOException("Reopened " + format.label + " dimensions differ for "
                        + staged.getAbsolutePath() + ": expected "
                        + source.getWidth() + "x" + source.getHeight() + "x" + expectedSlices
                        + " but found " + reopened.getWidth() + "x" + reopened.getHeight()
                        + "x" + reopened.getStackSize());
            }
        } finally {
            reopened.changes = false;
            reopened.close();
        }
    }

    private static void verifyCommittedImage(Path target, long expectedBytes) throws IOException {
        if (!Files.isRegularFile(target)) {
            throw new IOException("Committed image is not a regular file: " + target);
        }
        long actualBytes = Files.size(target);
        if (actualBytes != expectedBytes) {
            throw new IOException("Committed image size changed for " + target
                    + ": expected " + expectedBytes + " bytes but found " + actualBytes);
        }
    }

    /**
     * Blocks until all queued save operations have completed.
     * Logs any errors that occurred during saving.
     */
    public static void waitForAll() {
        waitForAllWithProgress(1);
    }

    /**
     * Blocks until all queued save operations have completed,
     * showing progress bar and status updates. Uses a single writer.
     */
    public static void waitForAllWithProgress() {
        waitForAllWithProgress(1);
    }

    /**
     * Blocks until all queued save operations have completed,
     * showing progress bar and status updates.
     *
     * <p>If {@code drainThreads > 1} and enough saves remain, the writer pool
     * is temporarily expanded so multiple save jobs execute concurrently on
     * real writer threads.</p>
     *
     * @param drainThreads number of concurrent writers to use during drain.
     *                     Capped at the number of remaining saves.
     */
    public static void waitForAllWithProgress(int drainThreads) {
        if (AnalysisCancellation.wasCancelRequestedInActiveScope()) {
            cancelPendingSaves("Analysis cancelled; queued image saves will not block the main UI.");
            return;
        }

        List<PendingSave> toWait;
        synchronized (pending) {
            toWait = new ArrayList<PendingSave>(pending);
            pending.clear();
        }
        final int total = toWait.size();
        if (total == 0) {
            firstSaveLogged.set(false);
            return;
        }

        int effectiveDrainThreads = Math.max(1, Math.min(drainThreads, total));

        if (effectiveDrainThreads > 1) {
            IJ.log("Flushing " + total + " queued saves with "
                    + effectiveDrainThreads + " concurrent writers.");
        } else {
            IJ.log("Flushing " + total + " queued saves...");
        }
        IJ.showStatus("Saving images to disk...");
        IJ.showProgress(0, total);

        // Temporarily expand the writer pool for concurrent drain
        if (effectiveDrainThreads > 1) {
            IO_POOL.setMaximumPoolSize(effectiveDrainThreads);
            IO_POOL.setCorePoolSize(effectiveDrainThreads);
            IO_POOL.prestartAllCoreThreads();
        }

        // Wait for every save job even after failures, updating progress as each completes.
        List<PublicationFailure> failures = new ArrayList<PublicationFailure>();
        VirtualMachineError fatalVmError = null;
        ThreadDeath fatalThreadDeath = null;
        boolean interrupted = false;
        int done = 0;
        try {
            for (PendingSave save : toWait) {
                try {
                    Future<?> f = save.future();
                    if (f == null) {
                        failures.add(new PublicationFailure(save.target(),
                                new IllegalStateException("Save job has no completion future")));
                        continue;
                    }
                    boolean complete = false;
                    while (!complete) {
                        try {
                            f.get();
                            complete = true;
                        } catch (InterruptedException e) {
                            interrupted = true;
                            failures.add(new PublicationFailure(save.target(), e));
                            // Continue waiting with the cleared interrupt flag so all owned
                            // images are closed before the interruption is restored.
                        }
                    }
                } catch (CancellationException e) {
                    failures.add(new PublicationFailure(save.target(), e));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    if (cause instanceof ImageSaveTaskException
                            && cause.getCause() != null) {
                        cause = cause.getCause();
                    }
                    failures.add(new PublicationFailure(save.target(), cause));
                    if (cause instanceof VirtualMachineError && fatalVmError == null) {
                        fatalVmError = (VirtualMachineError) cause;
                    } else if (cause instanceof ThreadDeath && fatalThreadDeath == null) {
                        fatalThreadDeath = (ThreadDeath) cause;
                    }
                } finally {
                    done++;
                    IJ.showProgress(done, total);
                    IJ.showStatus("Saving images... " + done + "/" + total);
                }
            }
        } finally {
            // Shrink the pool and clear process-global UI state even when publication fails.
            IO_POOL.setCorePoolSize(1);
            IO_POOL.setMaximumPoolSize(1);
            IJ.showStatus("");
            firstSaveLogged.set(false);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        if (!failures.isEmpty()) {
            for (PublicationFailure failure : failures) {
                IJ.log("Async save error for " + failure.getTarget() + ": "
                        + messageOf(failure.getCause()));
            }
            IJ.log("Image publication failed for " + failures.size() + " of "
                    + total + " queued saves; all jobs were drained.");

            if (fatalVmError != null) {
                addOtherFailuresAsSuppressed(fatalVmError, failures);
                throw fatalVmError;
            }
            if (fatalThreadDeath != null) {
                addOtherFailuresAsSuppressed(fatalThreadDeath, failures);
                throw fatalThreadDeath;
            }
            throw new ImagePublicationException(failures);
        }

        if (effectiveDrainThreads > 1) {
            IJ.log("All " + total + " images saved and verified ("
                    + effectiveDrainThreads + " writers).");
        } else {
            IJ.log("All " + total + " images saved and verified.");
        }
    }

    private static final class ImageSaveTaskException extends RuntimeException {
        private ImageSaveTaskException(String target, IOException cause) {
            super("Failed image save task for " + target, cause);
        }
    }

    /** One failed target retained in an aggregate publication exception. */
    public static final class PublicationFailure {
        private final String target;
        private final Throwable cause;

        private PublicationFailure(String target, Throwable cause) {
            this.target = target == null || target.trim().isEmpty()
                    ? "<unknown-target>" : target;
            this.cause = cause == null
                    ? new IllegalStateException("Image publication failed without a cause")
                    : cause;
        }

        public String getTarget() {
            return target;
        }

        public Throwable getCause() {
            return cause;
        }
    }

    /** Aggregate required-output failure thrown only after every queued job is drained. */
    public static final class ImagePublicationException extends RuntimeException {
        private final List<PublicationFailure> failures;

        private ImagePublicationException(List<PublicationFailure> failures) {
            super(aggregateMessage(failures));
            this.failures = Collections.unmodifiableList(
                    new ArrayList<PublicationFailure>(failures));
            for (PublicationFailure failure : failures) {
                addSuppressed(failure.getCause());
            }
        }

        public List<PublicationFailure> getFailures() {
            return failures;
        }
    }

    private static String aggregateMessage(List<PublicationFailure> failures) {
        StringBuilder message = new StringBuilder("Failed to publish ")
                .append(failures.size()).append(" queued image save(s) after draining all jobs");
        for (PublicationFailure failure : failures) {
            message.append("; ").append(failure.getTarget()).append(": ")
                    .append(messageOf(failure.getCause()));
        }
        return message.toString();
    }

    private static String messageOf(Throwable cause) {
        if (cause == null) {
            return "unknown failure";
        }
        String message = cause.getMessage();
        return message == null || message.trim().isEmpty()
                ? cause.getClass().getName() : message;
    }

    private static void addOtherFailuresAsSuppressed(
            Throwable fatal, List<PublicationFailure> failures) {
        for (PublicationFailure failure : failures) {
            if (failure.getCause() != fatal) {
                fatal.addSuppressed(failure.getCause());
            }
        }
    }

    enum ImageFormat {
        TIFF("TIFF", ".tif"),
        PNG("PNG", ".png");

        final String label;
        final String suffix;

        ImageFormat(String label, String suffix) {
            this.label = label;
            this.suffix = suffix;
        }
    }

    /** Narrow file-operation seam used by deterministic image-publication tests. */
    interface ImagePublicationOperations {
        boolean save(ImagePlus image, File staged, ImageFormat format) throws IOException;

        void validate(ImagePlus source, File staged, ImageFormat format) throws IOException;

        void replace(Path source, Path target) throws IOException;
    }

    static ImagePublicationOperations defaultImagePublicationOperations() {
        return DEFAULT_IMAGE_PUBLICATION_OPERATIONS;
    }

    static void setImagePublicationOperationsForTest(ImagePublicationOperations operations) {
        if (operations == null) {
            throw new IllegalArgumentException("image publication operations must not be null");
        }
        imagePublicationOperations = operations;
    }

    private static void cancelPendingSaves(String reason) {
        List<PendingSave> toCancel;
        synchronized (pending) {
            toCancel = new ArrayList<PendingSave>(pending);
            pending.clear();
        }
        if (toCancel.isEmpty()) {
            firstSaveLogged.set(false);
            return;
        }

        CancelCounts counts = cancelAll(toCancel);
        IO_POOL.purge();
        IO_POOL.setCorePoolSize(1);
        IO_POOL.setMaximumPoolSize(1);
        firstSaveLogged.set(false);

        IJ.log("[FLASH] " + reason);
        IJ.log("[FLASH] Image-save cleanup: cancelled " + counts.queued
                + " queued, released " + counts.running
                + " running, already finished " + counts.finished + ".");
        IJ.showStatus("Image saving cancelled.");
        IJ.showProgress(1.0);
    }

    private static CancelCounts cancelAll(List<PendingSave> saves) {
        CancelCounts counts = new CancelCounts();
        List<PendingSave> remaining = new ArrayList<PendingSave>();
        for (PendingSave save : saves) {
            if (save.cancelQueuedBeforeInterrupt()) {
                counts.queued++;
            } else {
                remaining.add(save);
            }
        }
        for (PendingSave save : remaining) {
            PendingSave.CancelResult result = save.cancelAfterQueuedPass();
            if (result == PendingSave.CancelResult.QUEUED) {
                counts.queued++;
            } else if (result == PendingSave.CancelResult.RUNNING) {
                counts.running++;
            } else {
                counts.finished++;
            }
        }
        return counts;
    }

    private static final class CancelCounts {
        int queued;
        int running;
        int finished;
    }

    private static final class PendingSave implements Runnable {
        enum CancelResult {
            QUEUED,
            RUNNING,
            FINISHED
        }

        private final Runnable task;
        private final ImagePlus imageToClose;
        private final String target;
        private final AtomicBoolean started = new AtomicBoolean(false);
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile Future<?> future;

        PendingSave(Runnable task, ImagePlus imageToClose, String target) {
            this.task = task;
            this.imageToClose = imageToClose;
            this.target = target == null || target.trim().isEmpty()
                    ? "<unknown-target>" : target;
        }

        void setFuture(Future<?> future) {
            this.future = future;
        }

        Future<?> future() {
            return future;
        }

        String target() {
            return target;
        }

        @Override
        public void run() {
            started.set(true);
            Throwable primaryFailure = null;
            try {
                if (task != null) {
                    task.run();
                }
            } catch (RuntimeException failure) {
                primaryFailure = failure;
                throw failure;
            } catch (Error failure) {
                primaryFailure = failure;
                throw failure;
            } finally {
                finished.set(true);
                closeImage(primaryFailure);
            }
        }

        boolean cancelQueuedBeforeInterrupt() {
            if (started.get() || finished.get()) {
                return false;
            }
            Future<?> f = future;
            if (f != null) {
                f.cancel(false);
            }
            if (!started.get()) {
                closeImage();
                return true;
            }
            return false;
        }

        CancelResult cancelAfterQueuedPass() {
            Future<?> f = future;
            boolean wasStarted = started.get();
            boolean wasFinished = finished.get() || (f != null && f.isDone() && !wasStarted);
            if (f != null) {
                f.cancel(true);
            }
            if (wasFinished || finished.get()) {
                return CancelResult.FINISHED;
            }
            if (wasStarted || started.get()) {
                return CancelResult.RUNNING;
            }
            closeImage();
            return CancelResult.QUEUED;
        }

        private void closeImage() {
            closeImage(null);
        }

        private void closeImage(Throwable primaryFailure) {
            if (imageToClose != null && closed.compareAndSet(false, true)) {
                Throwable cleanupFailure = null;
                try {
                    imageToClose.changes = false;
                    imageToClose.close();
                } catch (RuntimeException failure) {
                    cleanupFailure = failure;
                } catch (Error failure) {
                    cleanupFailure = failure;
                }
                try {
                    imageToClose.flush();
                } catch (RuntimeException failure) {
                    cleanupFailure = appendCleanupFailure(cleanupFailure, failure);
                } catch (Error failure) {
                    cleanupFailure = appendCleanupFailure(cleanupFailure, failure);
                }
                if (cleanupFailure != null) {
                    if (primaryFailure != null && cleanupFailure != primaryFailure) {
                        if (isVmFatal(cleanupFailure) && !isVmFatal(primaryFailure)) {
                            addSuppressedIfDistinct(cleanupFailure, primaryFailure);
                            rethrowCleanup(cleanupFailure);
                        }
                        addSuppressedIfDistinct(primaryFailure, cleanupFailure);
                    } else if (cleanupFailure instanceof RuntimeException) {
                        throw (RuntimeException) cleanupFailure;
                    } else if (cleanupFailure instanceof Error) {
                        throw (Error) cleanupFailure;
                    }
                }
            }
        }

        private static Throwable appendCleanupFailure(
                Throwable primaryCleanup, Throwable additionalCleanup) {
            if (primaryCleanup == null) return additionalCleanup;
            if (primaryCleanup == additionalCleanup) return primaryCleanup;
            if (isVmFatal(additionalCleanup) && !isVmFatal(primaryCleanup)) {
                addSuppressedIfDistinct(additionalCleanup, primaryCleanup);
                return additionalCleanup;
            }
            addSuppressedIfDistinct(primaryCleanup, additionalCleanup);
            return primaryCleanup;
        }

        private static void addSuppressedIfDistinct(
                Throwable primary, Throwable additional) {
            if (primary != null && additional != null && primary != additional) {
                primary.addSuppressed(additional);
            }
        }

        private static boolean isVmFatal(Throwable failure) {
            return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
        }

        private static void rethrowCleanup(Throwable failure) {
            if (failure instanceof RuntimeException) {
                throw (RuntimeException) failure;
            }
            throw (Error) failure;
        }
    }
}
