package flash.pipeline.stardist;

import java.util.Map;

import net.imagej.ImgPlus;

/**
 * Runtime detection of StarDist availability via TrackMate-StarDist.
 * StarDist is optional - users must install the StarDist and CSBDeep update sites.
 */
public class StarDistDetector {

    private static Boolean available = null;
    private static String availabilityMessage = null;

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
            Class<?> factoryClass = Class.forName("fiji.plugin.trackmate.stardist.StarDistDetectorFactory");
            factoryClass.getMethod("setTarget", ImgPlus.class, Map.class);

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
}
