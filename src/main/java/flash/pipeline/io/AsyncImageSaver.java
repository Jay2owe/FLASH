package flash.pipeline.io;

import flash.pipeline.image.ImageOps;
import ij.IJ;
import ij.ImagePlus;
import ij.io.FileSaver;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
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

    private static final List<Future<?>> pending = new ArrayList<Future<?>>();
    private static final AtomicBoolean firstSaveLogged = new AtomicBoolean(false);

    /**
     * Saves the image as TIFF asynchronously. The image is duplicated
     * immediately so the caller can close/reuse the original.
     */
    public static void saveAsTiffAsync(ImagePlus imp, String path) {
        logFirstSave();
        final ImagePlus copy = ImageOps.duplicateThreadSafe(imp);
        Runnable task = new Runnable() {
            @Override
            public void run() {
                try {
                    saveTiffAtomically(copy, path);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to save TIFF atomically: " + path, e);
                } finally {
                    copy.changes = false;
                    copy.close();
                }
            }
        };
        synchronized (pending) {
            pending.add(IO_POOL.submit(task));
        }
    }

    /**
     * Saves the image as PNG asynchronously. The image is duplicated
     * immediately so the caller can close/reuse the original.
     */
    public static void saveAsPngAsync(ImagePlus imp, String path) {
        logFirstSave();
        final ImagePlus copy = ImageOps.duplicateThreadSafe(imp);
        Runnable task = new Runnable() {
            @Override
            public void run() {
                try {
                    savePngAtomically(copy, path);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to save PNG atomically: " + path, e);
                } finally {
                    copy.changes = false;
                    copy.close();
                }
            }
        };
        synchronized (pending) {
            pending.add(IO_POOL.submit(task));
        }
    }

    /** Package-private: submit a synthetic save job (test seam). */
    static void submitTask(Runnable task) {
        synchronized (pending) {
            pending.add(IO_POOL.submit(task));
        }
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
            for (Future<?> f : pending) {
                f.cancel(true);
            }
            pending.clear();
        }
        IO_POOL.purge();
        IO_POOL.setCorePoolSize(1);
        IO_POOL.setMaximumPoolSize(1);
        firstSaveLogged.set(false);
    }

    private static void logFirstSave() {
        if (firstSaveLogged.compareAndSet(false, true)) {
            IJ.log("  Background saver started — images will be saved asynchronously.");
        }
    }

    private static void saveTiffAtomically(ImagePlus image, String path) throws IOException {
        File target = new File(path);
        File temp = createTempSibling(target, ".tif");
        boolean complete = false;
        try {
            FileSaver saver = new FileSaver(image);
            boolean saved = image.getStackSize() > 1
                    ? saver.saveAsTiffStack(temp.getAbsolutePath())
                    : saver.saveAsTiff(temp.getAbsolutePath());
            if (!saved) {
                throw new IOException("ImageJ failed to save temporary TIFF: " + temp.getAbsolutePath());
            }
            moveIntoPlace(temp, target);
            complete = true;
        } finally {
            if (!complete) {
                Files.deleteIfExists(temp.toPath());
            }
        }
    }

    private static void savePngAtomically(ImagePlus image, String path) throws IOException {
        File target = new File(path);
        File temp = createTempSibling(target, ".png");
        boolean complete = false;
        try {
            boolean saved = new FileSaver(image).saveAsPng(temp.getAbsolutePath());
            if (!saved) {
                throw new IOException("ImageJ failed to save temporary PNG: " + temp.getAbsolutePath());
            }
            moveIntoPlace(temp, target);
            complete = true;
        } finally {
            if (!complete) {
                Files.deleteIfExists(temp.toPath());
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

    private static void moveIntoPlace(File temp, File target) throws IOException {
        // Atomic move with retry/backoff for transient locks (cloud-sync, AV).
        // No in-place fallback: images can be large, so we never read them into memory.
        IoUtils.moveReplacing(temp.toPath(), target.toPath());
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
        List<Future<?>> toWait;
        synchronized (pending) {
            toWait = new ArrayList<Future<?>>(pending);
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

        // Wait for all save jobs, updating progress as each completes
        List<String> errors = new ArrayList<String>();
        int done = 0;
        for (Future<?> f : toWait) {
            try {
                f.get();
            } catch (Exception e) {
                String msg = e.getMessage();
                if (e.getCause() != null) {
                    msg = e.getCause().getMessage();
                }
                errors.add(msg);
            }
            done++;
            IJ.showProgress(done, total);
            IJ.showStatus("Saving images... " + done + "/" + total);
        }

        for (String msg : errors) {
            IJ.log("Async save error: " + msg);
        }

        if (effectiveDrainThreads > 1) {
            IJ.log("All " + total + " images saved (" + effectiveDrainThreads + " writers).");
        } else {
            IJ.log("All " + total + " images saved.");
        }

        // Shrink pool back to single writer for next batch
        IO_POOL.setCorePoolSize(1);
        IO_POOL.setMaximumPoolSize(1);

        IJ.showStatus("");
        firstSaveLogged.set(false);
    }
}
