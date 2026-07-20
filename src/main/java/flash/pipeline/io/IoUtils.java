package flash.pipeline.io;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Filesystem helpers shared across the pipeline. */
public final class IoUtils {

    private static final int REPLACE_MOVE_ATTEMPTS = 5;
    private static final int ROLLBACK_ATTEMPTS = 5;
    private static final int CLEANUP_ATTEMPTS = 3;
    private static final long REPLACE_MOVE_INITIAL_DELAY_MS = 100L;
    private static final int COPY_BUFFER_BYTES = 64 * 1024;

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

    private static final FileMover DEFAULT_FILE_MOVER = new FileMover() {
        @Override
        public void move(Path source, Path target, CopyOption... options) throws IOException {
            Files.move(source, target, options);
        }
    };

    private static final Sleeper DEFAULT_SLEEPER = new Sleeper() {
        @Override
        public void sleep(long millis) throws InterruptedException {
            Thread.sleep(millis);
        }
    };

    private static final FaultInjector NO_FAULTS = new FaultInjector() {
        @Override
        public void checkpoint(FaultPoint point, Path path) throws IOException {
            // Production no-op; package-private overloads provide bounded test seams.
        }
    };

    /**
     * Move a completed sibling temp file over its target. Candidate and prior
     * bytes are first copied to verified, same-directory snapshots. Publication
     * is accepted only after reopening and hashing the target. If a provider
     * truncates or replaces the target before reporting a failed move, the exact
     * prior generation is restored and verified before the failure is exposed.
     */
    public static void moveReplacing(Path source, Path target) throws IOException {
        moveReplacing(source, target, DEFAULT_FILE_MOVER, DEFAULT_SLEEPER);
    }

    /**
     * Commit a small sibling temp file over {@code target}. This has the same
     * verified transaction contract as {@link #moveReplacing}; when Windows or
     * sync software rejects every directory-entry replacement, it additionally
     * streams the verified candidate through the existing target entry. The
     * caller's temp file is removed on success and on failures for which a
     * last-good target generation was verified. If rollback itself cannot be
     * verified, recovery copies are deliberately retained and named in the
     * thrown diagnostic.
     */
    public static void commitReplacingSmallFile(Path temp, Path target) throws IOException {
        commitReplacingSmallFile(temp, target, DEFAULT_FILE_MOVER, DEFAULT_SLEEPER);
    }

    static void commitReplacingSmallFile(Path temp, Path target,
                                         FileMover mover, Sleeper sleeper) throws IOException {
        commitReplacingSmallFile(temp, target, mover, sleeper, NO_FAULTS);
    }

    static void commitReplacingSmallFile(Path temp, Path target, FileMover mover,
                                         Sleeper sleeper, FaultInjector faults)
            throws IOException {
        replaceDurably(temp, target, mover, sleeper, faults, true, true);
    }

    static void moveReplacing(Path source, Path target,
                              FileMover mover, Sleeper sleeper) throws IOException {
        moveReplacing(source, target, mover, sleeper, NO_FAULTS);
    }

    static void moveReplacing(Path source, Path target, FileMover mover,
                              Sleeper sleeper, FaultInjector faults) throws IOException {
        replaceDurably(source, target, mover, sleeper, faults, false, false);
    }

    private static void replaceDurably(Path source, Path target, FileMover mover,
                                       Sleeper sleeper, FaultInjector faults,
                                       boolean allowInPlace, boolean removeSourceOnFailure)
            throws IOException {
        requireArguments(source, target, mover, sleeper, faults);
        Transaction transaction = new Transaction(source, target, mover, sleeper, faults);
        Throwable failure = null;
        try {
            transaction.prepare();
            transaction.publicationStarted = true;
            IOException moveFailure = transaction.publishByMoving();
            if (moveFailure != null) {
                if (!allowInPlace) {
                    throw moveFailure;
                }
                transaction.publishInPlace(moveFailure);
            }
            transaction.targetGenerationVerified = true;
            transaction.finishSuccessfulCleanup();
        } catch (Throwable primary) {
            failure = primary;
            if (transaction.publicationStarted) {
                try {
                    transaction.rollback();
                } catch (Throwable rollbackFailure) {
                    failure = combineFailures(failure, rollbackFailure);
                }
            }
        } finally {
            try {
                transaction.cleanupInternalFiles();
            } catch (Throwable cleanupFailure) {
                failure = combineFailures(failure, cleanupFailure);
            }
            if (removeSourceOnFailure && failure != null
                    && !transaction.recoveryArtifactsRequired) {
                try {
                    transaction.deleteVerified(source, FaultPoint.BEFORE_CLEANUP);
                } catch (Throwable cleanupFailure) {
                    failure = combineFailures(failure, cleanupFailure);
                }
            }
            if (failure != null && transaction.recoveryArtifactsRequired) {
                failure = combineFailures(failure, transaction.recoveryDiagnostic());
            }
        }
        if (failure != null) {
            rethrow(failure);
        }
    }

    private static void requireArguments(Path source, Path target, FileMover mover,
                                         Sleeper sleeper, FaultInjector faults)
            throws IOException {
        if (source == null) throw new IOException("source path is null");
        if (target == null) throw new IOException("target path is null");
        if (mover == null) throw new IOException("file mover is null");
        if (sleeper == null) throw new IOException("sleeper is null");
        if (faults == null) throw new IOException("fault injector is null");
        if (source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
            throw new IOException("source and target must be different paths: " + source);
        }
    }

    private static final class Transaction {
        private final Path source;
        private final Path target;
        private final FileMover mover;
        private final Sleeper sleeper;
        private final FaultInjector faults;
        private final Path parent;
        private final String tempPrefix;

        private Path desired;
        private Path backup;
        private Path attempt;
        private FileStamp desiredStamp;
        private PriorGeneration prior;
        private boolean publicationStarted;
        private boolean targetGenerationVerified;
        private boolean recoveryArtifactsRequired;

        private Transaction(Path source, Path target, FileMover mover,
                            Sleeper sleeper, FaultInjector faults) throws IOException {
            this.source = source;
            this.target = target;
            this.mover = mover;
            this.sleeper = sleeper;
            this.faults = faults;
            Path absoluteTarget = target.toAbsolutePath().normalize();
            this.parent = absoluteTarget.getParent();
            if (parent == null) {
                throw new IOException("target has no parent directory: " + target);
            }
            this.tempPrefix = ".flash-" + safeFileName(target) + "-";
        }

        private void prepare() throws IOException {
            faults.checkpoint(FaultPoint.BEFORE_STAGING, source);
            desired = stageVerified(source, ".candidate");
            desiredStamp = stamp(desired);

            faults.checkpoint(FaultPoint.BEFORE_BACKUP, target);
            prior = capturePriorGeneration();
            targetGenerationVerified = true;
            faults.checkpoint(FaultPoint.AFTER_BACKUP, target);
        }

        private PriorGeneration capturePriorGeneration() throws IOException {
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(target, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException absent) {
                return PriorGeneration.absent();
            }
            if (!attributes.isRegularFile()) {
                throw new IOException("replacement target is not a regular file: " + target);
            }

            FileStamp before = stamp(target);
            backup = Files.createTempFile(parent, tempPrefix, ".backup");
            Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
            FileStamp backupStamp = stamp(backup);
            FileStamp after = stamp(target);
            if (!before.equals(backupStamp) || !before.equals(after)) {
                throw new IOException("target changed while its prior generation was backed up: "
                        + target);
            }
            return PriorGeneration.present(backupStamp);
        }

        /** @return null on success, otherwise the aggregate move failure. */
        private IOException publishByMoving() throws IOException {
            IOException atomicFailure = runMoveAttempt(true);
            if (atomicFailure == null) {
                return null;
            }

            IOException[] ordinaryFailures = new IOException[REPLACE_MOVE_ATTEMPTS];
            for (int index = 0; index < REPLACE_MOVE_ATTEMPTS; index++) {
                IOException ordinaryFailure = runMoveAttempt(false);
                if (ordinaryFailure == null) {
                    return null;
                }
                ordinaryFailures[index] = ordinaryFailure;
                if (index + 1 < REPLACE_MOVE_ATTEMPTS) {
                    sleepBeforeRetry(retryDelayMillis(index), ordinaryFailure,
                            "replace", source, target);
                }
            }

            IOException failure = new IOException(
                    "Could not replace " + target + " from temp file " + source
                            + " after " + REPLACE_MOVE_ATTEMPTS
                            + " attempts. The destination may be open in another application "
                            + "or temporarily held by file-sync software.",
                    ordinaryFailures[ordinaryFailures.length - 1]);
            failure.addSuppressed(atomicFailure);
            for (int i = 0; i + 1 < ordinaryFailures.length; i++) {
                if (ordinaryFailures[i] != null) {
                    failure.addSuppressed(ordinaryFailures[i]);
                }
            }
            return failure;
        }

        private IOException runMoveAttempt(boolean atomic) throws IOException {
            attempt = stageVerified(desired, ".attempt");
            IOException result = null;
            Throwable failure = null;
            try {
                IOException moveFailure = null;
                faults.checkpoint(FaultPoint.BEFORE_REPLACEMENT, target);
                targetGenerationVerified = false;
                try {
                    if (atomic) {
                        mover.move(attempt, target, StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.ATOMIC_MOVE);
                    } else {
                        mover.move(attempt, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (AtomicMoveNotSupportedException e) {
                    moveFailure = e;
                } catch (IOException e) {
                    moveFailure = e;
                }
                faults.checkpoint(FaultPoint.AFTER_REPLACEMENT, target);
                faults.checkpoint(FaultPoint.BEFORE_VALIDATION, target);
                try {
                    verifyExact(target, desiredStamp,
                            "published target does not match the staged candidate");
                    targetGenerationVerified = true;
                } catch (ContentMismatchException mismatch) {
                    if (moveFailure != null) {
                        mismatch.addSuppressed(moveFailure);
                    }
                    result = mismatch;
                }
            } catch (Throwable primary) {
                failure = primary;
            }
            try {
                Path toDelete = attempt;
                deleteVerified(toDelete, FaultPoint.BEFORE_CLEANUP);
                attempt = null;
            } catch (Throwable cleanupFailure) {
                failure = combineFailures(failure, cleanupFailure);
            }
            if (failure != null) {
                rethrow(failure);
            }
            return result;
        }

        private void publishInPlace(IOException moveFailure) throws IOException {
            IOException writeFailure = null;
            faults.checkpoint(FaultPoint.BEFORE_REPLACEMENT, target);
            targetGenerationVerified = false;
            try {
                copyInPlace(desired, target);
            } catch (IOException e) {
                writeFailure = e;
            }
            faults.checkpoint(FaultPoint.AFTER_REPLACEMENT, target);
            faults.checkpoint(FaultPoint.BEFORE_VALIDATION, target);
            try {
                verifyExact(target, desiredStamp,
                        "in-place target does not match the staged candidate");
                targetGenerationVerified = true;
            } catch (IOException validationFailure) {
                if (writeFailure != null) {
                    validationFailure.addSuppressed(writeFailure);
                }
                validationFailure.addSuppressed(moveFailure);
                throw validationFailure;
            }
        }

        private void rollback() throws IOException {
            if (prior == null) {
                return;
            }
            boolean interrupted = Thread.interrupted();
            boolean restored = false;
            IOException lastFailure = null;
            try {
                for (int index = 0; index < ROLLBACK_ATTEMPTS; index++) {
                    IOException operationFailure = null;
                    try {
                        faults.checkpoint(FaultPoint.BEFORE_ROLLBACK, target);
                        targetGenerationVerified = false;
                        if (prior.existed) {
                            copyInPlace(backup, target);
                        } else {
                            Files.deleteIfExists(target);
                        }
                    } catch (IOException e) {
                        operationFailure = e;
                    }
                    try {
                        faults.checkpoint(FaultPoint.BEFORE_ROLLBACK_VALIDATION, target);
                        if (prior.existed) {
                            verifyExact(target, prior.stamp,
                                    "rollback target does not match the prior generation");
                        } else {
                            verifyAbsent(target);
                        }
                        targetGenerationVerified = true;
                        recoveryArtifactsRequired = false;
                        restored = true;
                        return;
                    } catch (IOException validationFailure) {
                        if (operationFailure != null) {
                            validationFailure.addSuppressed(operationFailure);
                        }
                        lastFailure = validationFailure;
                    }
                    if (index + 1 < ROLLBACK_ATTEMPTS && !interrupted) {
                        try {
                            sleeper.sleep(retryDelayMillis(index));
                        } catch (InterruptedException pendingInterrupt) {
                            interrupted = true;
                            if (lastFailure != null) {
                                lastFailure.addSuppressed(new IOException(
                                        "Interrupted while waiting to retry rollback of "
                                                + target + ". Remaining rollback attempts "
                                                + "continued without delay.",
                                        pendingInterrupt));
                            }
                        }
                    }
                }
                throw new IOException("Could not restore the exact prior generation of " + target
                        + " after " + ROLLBACK_ATTEMPTS + " attempts. "
                        + recoveryPathsSummary(), lastFailure);
            } finally {
                if (!restored) {
                    targetGenerationVerified = false;
                    recoveryArtifactsRequired = true;
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void finishSuccessfulCleanup() throws IOException {
            // Keep the verified prior generation until every other fallible
            // cleanup has completed. A reported cleanup failure can therefore
            // still roll the target back exactly.
            deleteVerified(attempt, FaultPoint.BEFORE_CLEANUP);
            attempt = null;
            deleteVerified(desired, FaultPoint.BEFORE_CLEANUP);
            desired = null;
            deleteVerified(source, FaultPoint.BEFORE_CLEANUP);
            if (backup != null) {
                Path oldBackup = backup;
                deleteVerified(oldBackup, FaultPoint.BEFORE_CLEANUP);
                backup = null;
            }
        }

        private void cleanupInternalFiles() throws IOException {
            if (recoveryArtifactsRequired && !targetGenerationVerified) {
                // The verified backup and candidate are the only trustworthy
                // generations after an unverified rollback. Keep them for
                // manual recovery. An attempt copy is redundant only while the
                // verified candidate snapshot is still available.
                if (attempt != null && desired != null) {
                    Path redundantAttempt = attempt;
                    deleteVerified(redundantAttempt, FaultPoint.BEFORE_CLEANUP);
                    attempt = null;
                }
                return;
            }
            IOException failure = null;
            Path[] paths = new Path[] {attempt, desired, backup};
            attempt = null;
            desired = null;
            backup = null;
            for (Path path : paths) {
                try {
                    deleteVerified(path, FaultPoint.BEFORE_CLEANUP);
                } catch (IOException cleanupFailure) {
                    if (failure == null) {
                        failure = cleanupFailure;
                    } else {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private IOException recoveryDiagnostic() {
            return new IOException("Rollback was not verified for target "
                    + target.toAbsolutePath().normalize() + ". " + recoveryPathsSummary());
        }

        private String recoveryPathsSummary() {
            StringBuilder message = new StringBuilder(
                    "Recovery artifacts retained (do not delete until recovery succeeds): ");
            boolean found = false;
            if (backup != null) {
                appendRecoveryPath(message, "prior", backup,
                        prior == null ? null : prior.stamp, found);
                found = true;
            }
            if (desired != null) {
                appendRecoveryPath(message, "candidate", desired, desiredStamp, found);
                found = true;
            }
            if (attempt != null) {
                appendRecoveryPath(message, "candidate-attempt", attempt, desiredStamp, found);
                found = true;
            }
            if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                appendRecoveryPath(message, "caller-candidate", source, desiredStamp, found);
                found = true;
            }
            if (!found) {
                message.append("none available");
            }
            return message.toString();
        }

        private void appendRecoveryPath(StringBuilder message, String label, Path path,
                                        FileStamp expected, boolean separator) {
            if (separator) {
                message.append("; ");
            }
            message.append(label).append('=').append(path.toAbsolutePath().normalize());
            if (expected != null) {
                message.append(" [").append(expected.description()).append(']');
            }
        }

        private Path stageVerified(Path original, String suffix) throws IOException {
            Path staged = null;
            Path result = null;
            Throwable failure = null;
            try {
                FileStamp before = stamp(original);
                staged = Files.createTempFile(parent, tempPrefix, suffix);
                Files.copy(original, staged, StandardCopyOption.REPLACE_EXISTING);
                FileStamp stagedStamp = stamp(staged);
                FileStamp after = stamp(original);
                if (!before.equals(stagedStamp) || !before.equals(after)) {
                    throw new IOException("source changed while a verified staging copy was made: "
                            + original);
                }
                result = staged;
            } catch (Throwable primary) {
                failure = primary;
            }
            if (failure != null && staged != null) {
                try {
                    deleteVerified(staged, FaultPoint.BEFORE_CLEANUP);
                } catch (Throwable cleanupFailure) {
                    failure = combineFailures(failure, cleanupFailure);
                }
            }
            if (failure != null) {
                rethrow(failure);
            }
            return result;
        }

        private void deleteVerified(Path path, FaultPoint point) throws IOException {
            if (path == null) {
                return;
            }
            IOException lastFailure = null;
            for (int index = 0; index < CLEANUP_ATTEMPTS; index++) {
                try {
                    faults.checkpoint(point, path);
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    lastFailure = e;
                }
                if (isAbsent(path)) {
                    return;
                }
                if (index + 1 < CLEANUP_ATTEMPTS) {
                    sleepBeforeRetry(retryDelayMillis(index), lastFailure,
                            "clean up", path, path);
                }
            }
            throw new IOException("Could not remove transaction file " + path + " after "
                    + CLEANUP_ATTEMPTS + " attempts.", lastFailure);
        }

        private void sleepBeforeRetry(long millis, IOException priorFailure,
                                      String action, Path from, Path to) throws IOException {
            try {
                sleeper.sleep(millis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                IOException failure = new IOException("Interrupted while waiting to " + action
                        + " " + to + " from " + from + ".", interrupted);
                if (priorFailure != null) {
                    failure.addSuppressed(priorFailure);
                }
                throw failure;
            }
        }
    }

    private static void copyInPlace(Path source, Path target) throws IOException {
        try (InputStream input = Files.newInputStream(source, StandardOpenOption.READ);
             OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    output.write(buffer, 0, read);
                }
            }
            output.flush();
        }
    }

    private static FileStamp stamp(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by the Java runtime", impossible);
        }
        long size = 0L;
        try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ)) {
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                    size += read;
                }
            }
        }
        return new FileStamp(size, digest.digest());
    }

    private static void verifyExact(Path path, FileStamp expected, String message)
            throws IOException {
        FileStamp actual;
        try {
            actual = stamp(path);
        } catch (NoSuchFileException absent) {
            throw new ContentMismatchException(message + ": target is absent: " + path, absent);
        }
        if (!expected.equals(actual)) {
            throw new ContentMismatchException(message + ": " + path);
        }
    }

    private static void verifyAbsent(Path path) throws IOException {
        if (!isAbsent(path)) {
            throw new ContentMismatchException(
                    "rollback target should be absent but exists: " + path);
        }
    }

    private static boolean isAbsent(Path path) throws IOException {
        try {
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return false;
        } catch (NoSuchFileException absent) {
            return true;
        }
    }

    private static String safeFileName(Path target) {
        Path namePath = target.getFileName();
        String name = namePath == null ? "target" : namePath.toString();
        String clean = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return clean.length() < 3 ? "target-" + clean : clean;
    }

    private static long retryDelayMillis(int attempt) {
        long multiplier = 1L << Math.min(attempt, 3);
        return REPLACE_MOVE_INITIAL_DELAY_MS * multiplier;
    }

    private static Throwable combineFailures(Throwable primary, Throwable additional) {
        if (primary == null) {
            return additional;
        }
        if (additional == null || additional == primary) {
            return primary;
        }
        if (isVmFatal(additional) && !isVmFatal(primary)) {
            additional.addSuppressed(primary);
            return additional;
        }
        primary.addSuppressed(additional);
        return primary;
    }

    private static boolean isVmFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    private static void rethrow(Throwable failure) throws IOException {
        if (failure instanceof IOException) {
            throw (IOException) failure;
        }
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw new IOException("Unexpected filesystem transaction failure", failure);
    }

    private static final class FileStamp {
        private final long size;
        private final byte[] digest;

        private FileStamp(long size, byte[] digest) {
            this.size = size;
            this.digest = digest;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof FileStamp)) {
                return false;
            }
            FileStamp that = (FileStamp) other;
            return size == that.size && Arrays.equals(digest, that.digest);
        }

        @Override
        public int hashCode() {
            return 31 * (int) (size ^ (size >>> 32)) + Arrays.hashCode(digest);
        }

        private String description() {
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", Integer.valueOf(value & 0xff)));
            }
            return "size=" + size + ", sha256=" + hex;
        }
    }

    private static final class PriorGeneration {
        private final boolean existed;
        private final FileStamp stamp;

        private PriorGeneration(boolean existed, FileStamp stamp) {
            this.existed = existed;
            this.stamp = stamp;
        }

        private static PriorGeneration absent() {
            return new PriorGeneration(false, null);
        }

        private static PriorGeneration present(FileStamp stamp) {
            return new PriorGeneration(true, stamp);
        }
    }

    private static final class ContentMismatchException extends IOException {
        private ContentMismatchException(String message) {
            super(message);
        }

        private ContentMismatchException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    enum FaultPoint {
        BEFORE_STAGING,
        BEFORE_BACKUP,
        AFTER_BACKUP,
        BEFORE_REPLACEMENT,
        AFTER_REPLACEMENT,
        BEFORE_VALIDATION,
        BEFORE_ROLLBACK,
        BEFORE_ROLLBACK_VALIDATION,
        BEFORE_CLEANUP
    }

    interface FaultInjector {
        void checkpoint(FaultPoint point, Path path) throws IOException;
    }

    interface FileMover {
        void move(Path source, Path target, CopyOption... options) throws IOException;
    }

    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
