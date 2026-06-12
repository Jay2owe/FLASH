package flash.pipeline.runtime;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import ij.IJ;

/**
 * Detects and clears the stale imagej-tensorflow crash sentinel that otherwise
 * blocks every TensorFlow load for the rest of a Fiji install's life.
 *
 * <p>imagej-tensorflow writes {@code Fiji.app/lib/<platform>/.crashed} immediately
 * before a native TensorFlow load and deletes it again once the load succeeds. If
 * Fiji dies during that window (a hard crash, an out-of-memory kill, or the user
 * force-closing it) the sentinel is orphaned. On every later launch
 * imagej-tensorflow sees the orphaned file, concludes "TensorFlow crashed last
 * time, do not retry", refuses to load it, and pops the dialog
 * "Could not load TensorFlow. Opening the TensorFlow Library Management tool." even
 * though TensorFlow is perfectly fine. See
 * {@code docs/fixed_bugs/tensorflow-stale-crash-sentinel.md}.
 *
 * <p>We clear the orphaned sentinel automatically before StarDist triggers the
 * native load, but with a deliberate one-retry guard. The sentinel exists to stop
 * an infinite hard-crash loop when TensorFlow genuinely crashes on every load (a
 * corrupt DLL, a CPU without the AVX instructions TF 1.15 requires). So when we
 * clear it we drop a {@link #MARKER_NAME} marker; if a fresh sentinel reappears
 * while that marker is still present, the crash is genuinely repeatable and we
 * leave the sentinel in place rather than risk crashing Fiji on every run. A
 * successful StarDist run removes the marker, re-arming the automatic clear for the
 * next genuinely-orphaned sentinel.
 */
public final class TensorFlowCrashSentinel {

    static final String SENTINEL_NAME = ".crashed";
    static final String MARKER_NAME = ".flash-cleared-crashed";

    private static final AtomicBoolean CLEARED_THIS_SESSION = new AtomicBoolean(false);

    private TensorFlowCrashSentinel() {
    }

    /** What {@link #evaluate(File)} decided about the native-lib directory. */
    enum Outcome {
        /** No orphaned sentinel to act on. */
        NONE,
        /** An orphaned sentinel was deleted and the one-retry marker was written. */
        CLEARED,
        /** A sentinel reappeared after a prior clear: a genuine, repeatable crash. */
        REPEATED
    }

    /**
     * Clears an orphaned TensorFlow crash sentinel at most once per Fiji session.
     * Safe to call before every StarDist run; it is a no-op when there is nothing
     * to clear, when it has already cleared this session, or when the crash looks
     * genuinely repeatable.
     *
     * @return true only if a stale sentinel was actually deleted by this call
     */
    public static boolean clearIfStale() {
        if (CLEARED_THIS_SESSION.get()) {
            return false;
        }
        try {
            File nativeDir = nativeLibDir();
            if (nativeDir == null) {
                return false;
            }
            Outcome outcome = evaluate(nativeDir);
            switch (outcome) {
                case CLEARED:
                    CLEARED_THIS_SESSION.set(true);
                    IJ.log("StarDist: cleared a stale TensorFlow crash flag left by a previous "
                            + "Fiji session (" + new File(nativeDir, SENTINEL_NAME).getAbsolutePath()
                            + "). TensorFlow will be retried. If Fiji now crashes while loading "
                            + "TensorFlow, the TensorFlow install itself is at fault, not a stale flag.");
                    return true;
                case REPEATED:
                    IJ.log("StarDist: a TensorFlow crash flag reappeared after an automatic clear, "
                            + "so the crash is genuine and repeatable. Leaving it in place. Use "
                            + "Edit > Options > TensorFlow... to select a working TensorFlow version, "
                            + "then restart Fiji.");
                    return false;
                case NONE:
                default:
                    return false;
            }
        } catch (Throwable ignored) {
            // Best-effort: sentinel handling must never break StarDist itself.
            return false;
        }
    }

    /**
     * Clears a stale sentinel immediately, ignoring the once-per-session guard. Used
     * by the manual "Auto-Fix TensorFlow Native" repair action, where the user has
     * explicitly asked to repair the runtime.
     *
     * @return a human-readable description of what was done, or an empty string if
     *         there was nothing to clear
     */
    public static String clearNow() {
        try {
            File nativeDir = nativeLibDir();
            if (nativeDir == null) {
                return "";
            }
            File sentinel = new File(nativeDir, SENTINEL_NAME);
            if (!sentinel.isFile()) {
                return "";
            }
            if (sentinel.delete()) {
                deleteQuietly(new File(nativeDir, MARKER_NAME));
                return "Cleared a stale TensorFlow crash flag: " + sentinel.getAbsolutePath();
            }
            return "Could not delete the TensorFlow crash flag: " + sentinel.getAbsolutePath();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * Records that TensorFlow loaded successfully, removing the one-retry marker so a
     * future genuinely-orphaned sentinel will be auto-cleared again. Call after a
     * StarDist run that produced a result (TensorFlow demonstrably loaded).
     */
    public static void noteTensorFlowLoadedOk() {
        try {
            noteLoadedOk(nativeLibDir());
        } catch (Throwable ignored) {
            // Best-effort.
        }
    }

    // --- package-private core, exercised directly by tests with a temp directory ---

    static Outcome evaluate(File nativeDir) {
        if (nativeDir == null || !nativeDir.isDirectory()) {
            return Outcome.NONE;
        }
        File sentinel = new File(nativeDir, SENTINEL_NAME);
        if (!sentinel.isFile()) {
            return Outcome.NONE;
        }
        File marker = new File(nativeDir, MARKER_NAME);
        if (marker.isFile()) {
            // We already cleared once and a sentinel is back: do not clear again.
            return Outcome.REPEATED;
        }
        if (sentinel.delete()) {
            createQuietly(marker);
            return Outcome.CLEARED;
        }
        return Outcome.NONE;
    }

    static void noteLoadedOk(File nativeDir) {
        if (nativeDir == null) {
            return;
        }
        deleteQuietly(new File(nativeDir, MARKER_NAME));
    }

    static File nativeLibDir() {
        File fijiDir = DependencyRegistry.resolveFijiDir();
        if (fijiDir == null) {
            return null;
        }
        String sub = platformSubdir();
        if (sub == null) {
            return null;
        }
        File dir = new File(new File(fijiDir, "lib"), sub);
        return dir.isDirectory() ? dir : null;
    }

    static String platformSubdir() {
        String os = System.getProperty("os.name");
        os = os == null ? "" : os.toLowerCase(Locale.ROOT);
        boolean is64 = is64Bit();
        if (os.contains("win")) {
            return is64 ? "win64" : "win32";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "macosx";
        }
        if (os.contains("nux") || os.contains("nix") || os.contains("aix")) {
            return is64 ? "linux64" : "linux32";
        }
        return null;
    }

    private static boolean is64Bit() {
        String arch = System.getProperty("os.arch");
        return arch == null || arch.contains("64");
    }

    private static void createQuietly(File file) {
        try {
            file.createNewFile();
        } catch (Throwable ignored) {
            // Marker is an optimisation; failing to write it just means we may
            // auto-clear one extra time, which is harmless.
        }
    }

    private static void deleteQuietly(File file) {
        try {
            if (file.isFile()) {
                file.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    /** Test hook: reset the once-per-session guard. */
    static void resetSessionGuardForTest() {
        CLEARED_THIS_SESSION.set(false);
    }
}
