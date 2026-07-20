package flash.pipeline.deconv;

import flash.pipeline.io.IoUtils;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/** Re-entrant in-process and cross-process lock for one deconvolution artifact family. */
public final class DeconvolutionFamilyLock {

    private static final ConcurrentMap<String, State> STATES =
            new ConcurrentHashMap<String, State>();

    private DeconvolutionFamilyLock() {}

    public static Handle acquire(File rootDir,
                                 DeconvolutionIO.ArtifactIdentity identity) throws IOException {
        if (rootDir == null || identity == null || !identity.isPublishable()) {
            throw new IOException("A project root and publishable deconvolution identity are required.");
        }
        File lockDir = new File(DeconvolutionIO.cacheDir(rootDir), ".family-locks");
        IoUtils.mustMkdirs(lockDir);
        File lockFile = new File(lockDir, identity.familyLockToken() + ".lck");
        String key = lockFile.getCanonicalPath();
        State candidate = new State();
        State state = STATES.putIfAbsent(key, candidate);
        if (state == null) state = candidate;

        state.local.lock();
        boolean success = false;
        try {
            if (state.local.getHoldCount() == 1) {
                state.channel = FileChannel.open(lockFile.toPath(),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                try {
                    state.fileLock = state.channel.lock();
                } catch (Throwable failure) {
                    try {
                        state.channel.close();
                    } catch (Throwable closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                    state.channel = null;
                    rethrow(failure);
                }
            }
            success = true;
            return new Handle(state);
        } finally {
            if (!success) state.local.unlock();
        }
    }

    private static void rethrow(Throwable failure) throws IOException {
        if (failure instanceof IOException) throw (IOException) failure;
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
        throw new IOException("Could not acquire deconvolution family lock.", failure);
    }

    public static final class Handle implements AutoCloseable {
        private State state;

        private Handle(State state) {
            this.state = state;
        }

        @Override
        public void close() throws IOException {
            State owned = state;
            if (owned == null) return;
            state = null;
            Throwable failure = null;
            try {
                if (owned.local.getHoldCount() == 1) {
                    try {
                        if (owned.fileLock != null) owned.fileLock.release();
                    } catch (Throwable releaseFailure) {
                        failure = releaseFailure;
                    } finally {
                        owned.fileLock = null;
                    }
                    try {
                        if (owned.channel != null) owned.channel.close();
                    } catch (Throwable closeFailure) {
                        if (failure == null) failure = closeFailure;
                        else failure.addSuppressed(closeFailure);
                    } finally {
                        owned.channel = null;
                    }
                }
            } finally {
                owned.local.unlock();
            }
            if (failure != null) rethrow(failure);
        }
    }

    private static final class State {
        final ReentrantLock local = new ReentrantLock(true);
        FileChannel channel;
        FileLock fileLock;
    }
}
