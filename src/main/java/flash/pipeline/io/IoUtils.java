package flash.pipeline.io;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Filesystem helpers shared across the pipeline. */
public final class IoUtils {

    private static final int REPLACE_MOVE_ATTEMPTS = 5;
    private static final long REPLACE_MOVE_INITIAL_DELAY_MS = 100L;

    private IoUtils() {}

    /**
     * Create {@code f} as a directory (and any missing parents). Throws a clear
     * {@link IOException} if the path already exists as a file, or if creation
     * fails for any reason (permissions, cloud-sync conflict, long path,
     * reserved device name, etc.). Silently succeeds if the directory already
     * exists.
     */
    public static void mustMkdirs(File f) throws IOException {
        if (f == null) throw new IOException("null directory");
        if (f.exists()) {
            if (!f.isDirectory()) {
                throw new IOException("path exists but is not a directory: " + f.getAbsolutePath());
            }
            return;
        }
        if (!f.mkdirs() && !f.isDirectory()) {
            throw new IOException("could not create directory: " + f.getAbsolutePath());
        }
    }

    /**
     * Move a completed sibling temp file over its target. The first attempt is
     * atomic; if Windows/cloud-sync folders reject that as a generic
     * {@link IOException}, retry with a normal replace before surfacing a
     * clearer locked-file error.
     */
    public static void moveReplacing(Path source, Path target) throws IOException {
        moveReplacing(source, target, new FileMover() {
            @Override
            public void move(Path source, Path target, CopyOption... options) throws IOException {
                Files.move(source, target, options);
            }
        }, new Sleeper() {
            @Override
            public void sleep(long millis) throws InterruptedException {
                Thread.sleep(millis);
            }
        });
    }

    static void moveReplacing(Path source, Path target,
                              FileMover mover, Sleeper sleeper) throws IOException {
        if (source == null) throw new IOException("source path is null");
        if (target == null) throw new IOException("target path is null");

        IOException atomicFailure = null;
        try {
            mover.move(source, target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (AtomicMoveNotSupportedException e) {
            atomicFailure = e;
        } catch (IOException e) {
            atomicFailure = e;
        }

        IOException lastFailure = null;
        for (int attempt = 0; attempt < REPLACE_MOVE_ATTEMPTS; attempt++) {
            try {
                mover.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException e) {
                lastFailure = e;
                if (attempt + 1 < REPLACE_MOVE_ATTEMPTS) {
                    sleepBeforeReplaceRetry(sleeper, retryDelayMillis(attempt),
                            source, target, atomicFailure, lastFailure);
                }
            }
        }

        IOException failure = new IOException(
                "Could not replace " + target + " from temp file " + source
                        + " after " + REPLACE_MOVE_ATTEMPTS
                        + " attempts. The destination may be open in another application "
                        + "or temporarily held by file-sync software.",
                lastFailure);
        if (atomicFailure != null) {
            failure.addSuppressed(atomicFailure);
        }
        throw failure;
    }

    private static void sleepBeforeReplaceRetry(Sleeper sleeper, long millis,
                                                Path source, Path target,
                                                IOException atomicFailure,
                                                IOException lastFailure) throws IOException {
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            IOException interrupted = new IOException(
                    "Interrupted while waiting to replace " + target
                            + " from temp file " + source + ".",
                    e);
            if (lastFailure != null) {
                interrupted.addSuppressed(lastFailure);
            }
            if (atomicFailure != null) {
                interrupted.addSuppressed(atomicFailure);
            }
            throw interrupted;
        }
    }

    private static long retryDelayMillis(int attempt) {
        long multiplier = 1L << Math.min(attempt, 3);
        return REPLACE_MOVE_INITIAL_DELAY_MS * multiplier;
    }

    interface FileMover {
        void move(Path source, Path target, CopyOption... options) throws IOException;
    }

    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
