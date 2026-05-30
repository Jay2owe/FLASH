package flash.pipeline.runrecord;

import flash.pipeline.audit.RunSettingsSnapshot;

/**
 * Captures the build/runtime environment a run executed in. Values are computed
 * once on first access and cached for the JVM lifetime, since they never change
 * during a session. Tests reset the cache via {@link #clearCacheForTests()}.
 *
 * <p>Deliberately does NOT capture raw {@code user.name} or hostname: run
 * records are designed to travel with copied projects, so avoidable personal
 * identifiers are left out in v1.
 */
public final class EnvironmentSnapshot {

    private static String flashVersion;
    private static String fijiBuild;
    private static String jdkVersion;
    private static String osName;
    private static String biofVersion;
    private static String machineFingerprint;

    private EnvironmentSnapshot() {
    }

    /** FLASH plugin version, reusing the shared audit lookup. */
    public static synchronized String flashVersion() {
        if (flashVersion == null) {
            flashVersion = safe(RunSettingsSnapshot.flashVersion());
        }
        return flashVersion;
    }

    /** ImageJ/Fiji version and build, e.g. {@code 1.54f / 1.54f99}. */
    public static synchronized String fijiBuild() {
        if (fijiBuild == null) {
            fijiBuild = computeFijiBuild();
        }
        return fijiBuild;
    }

    /** Running JDK version. */
    public static synchronized String jdkVersion() {
        if (jdkVersion == null) {
            jdkVersion = safe(System.getProperty("java.version"));
        }
        return jdkVersion;
    }

    /** OS name, version and architecture. */
    public static synchronized String osName() {
        if (osName == null) {
            osName = computeOsName();
        }
        return osName;
    }

    /** Bio-Formats library version. */
    public static synchronized String biofVersion() {
        if (biofVersion == null) {
            biofVersion = computeBiofVersion();
        }
        return biofVersion;
    }

    /**
     * Optional salted machine fingerprint. Empty by default; a deployment may
     * opt in later. Never the raw hostname or username.
     */
    public static synchronized String machineFingerprint() {
        if (machineFingerprint == null) {
            machineFingerprint = "";
        }
        return machineFingerprint;
    }

    static synchronized void clearCacheForTests() {
        flashVersion = null;
        fijiBuild = null;
        jdkVersion = null;
        osName = null;
        biofVersion = null;
        machineFingerprint = null;
    }

    private static String computeFijiBuild() {
        try {
            String version = safe(ij.IJ.getVersion());
            String full = "";
            try {
                full = safe(ij.IJ.getFullVersion());
            } catch (Throwable ignored) {
                // getFullVersion may be unavailable on very old ImageJ; version alone is fine.
            }
            if (!full.isEmpty() && !full.equals(version)) {
                return version.isEmpty() ? full : version + " / " + full;
            }
            return version;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String computeOsName() {
        String name = safe(System.getProperty("os.name"));
        String version = safe(System.getProperty("os.version"));
        String arch = safe(System.getProperty("os.arch"));
        StringBuilder out = new StringBuilder(name);
        if (!version.isEmpty()) {
            out.append(' ').append(version);
        }
        if (!arch.isEmpty()) {
            out.append(" (").append(arch).append(')');
        }
        return out.toString().trim();
    }

    private static String computeBiofVersion() {
        try {
            return safe(loci.formats.FormatTools.VERSION);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
