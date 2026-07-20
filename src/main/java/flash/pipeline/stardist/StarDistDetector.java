package flash.pipeline.stardist;

import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.DependencyRegistry;
import flash.pipeline.runtime.DependencySpec;
import flash.pipeline.runtime.DependencyStatus;
import java.util.Map;

import net.imagej.ImgPlus;

/**
 * Runtime detection of StarDist availability via TrackMate-StarDist.
 * StarDist is optional - users must install the StarDist and CSBDeep update sites.
 */
public class StarDistDetector {

    private static Boolean available = null;
    private static String availabilityMessage = null;
    private static RuntimeDependencyProbe runtimeDependencyProbe =
            new DefaultRuntimeDependencyProbe();
    private static TrackMateApiProbe trackMateApiProbe =
            new DefaultTrackMateApiProbe();

    interface RuntimeDependencyProbe {
        DependencyStatus status(DependencyId id);
    }

    interface TrackMateApiProbe {
        void verify() throws ClassNotFoundException, NoSuchMethodException, LinkageError;
    }

    /**
     * Returns true if TrackMate-StarDist is installed and API-compatible in the
     * current Fiji environment.
     */
    public static boolean isAvailable() {
        ensureProbed();
        return available.booleanValue();
    }

    /**
     * Returns a user-facing status / remediation message for the current runtime.
     */
    public static String getAvailabilityMessage() {
        ensureProbed();
        return availabilityMessage;
    }

    /**
     * Backward-compatible alias used by existing UI code.
     */
    public static String getInstallInstructions() {
        return getAvailabilityMessage();
    }

    private static synchronized void ensureProbed() {
        if (available != null) {
            return;
        }

        try {
            trackMateApiProbe.verify();

            DependencyStatus starDistRuntime =
                    runtimeDependencyProbe.status(DependencyId.STARDIST_RUNTIME);
            if (starDistRuntime == null || !starDistRuntime.isPresent()) {
                markRuntimeUnavailable("StarDist Java runtime", starDistRuntime,
                        "Run Pipeline Dependencies > Auto-Fix StarDist, then restart Fiji.");
                return;
            }

            // REGRESSION GUARD: this only checks that the TF native jars are PRESENT. It does
            // NOT catch a stale imagej-tensorflow crash sentinel (Fiji.app/lib/<platform>/.crashed,
            // e.g. lib/win64/.crashed), which is left behind when Fiji dies mid-load and then makes
            // every later launch refuse to load TensorFlow ("Could not load TensorFlow" dialog) even
            // though all jars are present. So this probe can report present() while StarDist still
            // fails at run time; markRuntimeFailure() handles that at-load failure. Fix for the
            // sentinel is to delete the .crashed file with Fiji closed, then relaunch.
            DependencyStatus tensorFlowNative =
                    runtimeDependencyProbe.status(DependencyId.TENSORFLOW_NATIVE_RUNTIME);
            if (tensorFlowNative == null || !tensorFlowNative.isPresent()) {
                markRuntimeUnavailable("TensorFlow native runtime", tensorFlowNative,
                        "Run Pipeline Dependencies > Auto-Fix TensorFlow Native, then restart Fiji.");
                return;
            }

            available = Boolean.TRUE;
            availabilityMessage = "TrackMate-StarDist is available.";
        } catch (ClassNotFoundException e) {
            markMissing();
        } catch (NoClassDefFoundError e) {
            markMissing();
        } catch (NoSuchMethodException e) {
            markIncompatible();
        } catch (LinkageError e) {
            available = Boolean.FALSE;
            availabilityMessage =
                    "TrackMate-StarDist could not be loaded cleanly: "
                            + e.getClass().getSimpleName()
                            + " - "
                            + e.getMessage()
                            + "\n"
                            + "Reinstall a matching TrackMate / TrackMate-StarDist pair.";
        }
    }

    public static synchronized void markRuntimeFailure(Throwable failure) {
        available = Boolean.FALSE;
        availabilityMessage =
                "StarDist could not load TensorFlow in this Fiji session: "
                        + exceptionSummary(failure)
                        + "\n"
                        + "Run Pipeline Dependencies > Auto-Fix TensorFlow Native, "
                        + "then restart Fiji before retrying StarDist.";
    }

    private static void markMissing() {
        available = Boolean.FALSE;
        availabilityMessage =
                "TrackMate-StarDist is not installed.\n"
                        + "StarDist requires the CSBDeep, StarDist, and TrackMate update sites.\n"
                        + "Go to: Help > Update > Manage Update Sites\n"
                        + "Enable: 'CSBDeep', 'StarDist', and check that TrackMate is up to date.";
    }

    private static void markIncompatible() {
        available = Boolean.FALSE;

        String starDistVersion = implementationVersion("fiji.plugin.trackmate.stardist.StarDistDetectorFactory");
        String trackMateVersion = implementationVersion("fiji.plugin.trackmate.TrackMate");

        StringBuilder sb = new StringBuilder();
        sb.append("Installed TrackMate-StarDist");
        if (starDistVersion != null) {
            sb.append(" ").append(starDistVersion);
        }
        sb.append(" is incompatible with TrackMate");
        if (trackMateVersion != null) {
            sb.append(" ").append(trackMateVersion);
        }
        sb.append(".\n");

        if (starDistVersion != null && starDistVersion.startsWith("2.")
                && trackMateVersion != null && trackMateVersion.startsWith("7.")) {
            sb.append("TrackMate-StarDist 2.x expects the TrackMate 8 detector API.\n");
        }

        sb.append("Use TrackMate-StarDist 1.2.x with TrackMate 7.x, or upgrade TrackMate and TrackMate-StarDist together.");
        availabilityMessage = sb.toString();
    }

    private static void markRuntimeUnavailable(String label,
                                               DependencyStatus status,
                                               String repair) {
        available = Boolean.FALSE;
        StringBuilder sb = new StringBuilder();
        sb.append(label).append(" is not ready for StarDist.");
        if (status != null && status.getDetailMessage() != null
                && !status.getDetailMessage().trim().isEmpty()) {
            sb.append("\n").append(status.getDetailMessage().trim());
        }
        sb.append("\n").append(repair);
        availabilityMessage = sb.toString();
    }

    static synchronized void resetForTest() {
        available = null;
        availabilityMessage = null;
        runtimeDependencyProbe = new DefaultRuntimeDependencyProbe();
        trackMateApiProbe = new DefaultTrackMateApiProbe();
    }

    static synchronized void setRuntimeDependencyProbeForTest(
            RuntimeDependencyProbe probe) {
        available = null;
        availabilityMessage = null;
        runtimeDependencyProbe = probe == null
                ? new DefaultRuntimeDependencyProbe()
                : probe;
    }

    static synchronized void setTrackMateApiProbeForTest(TrackMateApiProbe probe) {
        available = null;
        availabilityMessage = null;
        trackMateApiProbe = probe == null
                ? new DefaultTrackMateApiProbe()
                : probe;
    }

    private static String implementationVersion(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            Package pkg = clazz.getPackage();
            if (pkg == null) {
                return null;
            }
            return pkg.getImplementationVersion();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String exceptionSummary(Throwable throwable) {
        if (throwable == null) {
            return "unknown TensorFlow load failure";
        }
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName()
                + (message == null || message.trim().isEmpty()
                ? ""
                : " - " + message.trim());
    }

    private static final class DefaultTrackMateApiProbe implements TrackMateApiProbe {
        @Override
        public void verify()
                throws ClassNotFoundException, NoSuchMethodException, LinkageError {
            ClassLoader loader = StarDistDetector.class.getClassLoader();
            Class<?> factoryClass = Class.forName(
                    "fiji.plugin.trackmate.stardist.StarDistDetectorFactory",
                    false,
                    loader);
            factoryClass.getMethod("setTarget", ImgPlus.class, Map.class);
            Class.forName("net.imagej.tensorflow.TensorFlowService", false, loader);
            Class.forName("org.tensorflow.TensorFlow", false, loader);
        }
    }

    private static final class DefaultRuntimeDependencyProbe
            implements RuntimeDependencyProbe {
        @Override
        public DependencyStatus status(DependencyId id) {
            DependencySpec spec = DependencyRegistry.get(id);
            if (spec == null) {
                return DependencyStatus.error("Unknown dependency id: " + id);
            }
            java.io.File fijiDir = DependencyRegistry.resolveFijiDir();
            java.util.List<String> issues = DependencyRegistry.checkJarRequirements(
                    fijiDir, spec.getJarRequirements(), spec.getJarIgnorePrefixes());
            if (issues.isEmpty()) {
                return DependencyStatus.present("All required Fiji jars are present.");
            }
            StringBuilder sb = new StringBuilder();
            for (String issue : issues) {
                if (issue == null || issue.trim().isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(issue.trim());
            }
            return DependencyStatus.missing(sb.toString());
        }
    }
}
